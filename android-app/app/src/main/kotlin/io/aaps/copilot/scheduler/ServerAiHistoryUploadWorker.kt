package io.aaps.copilot.scheduler

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.config.AiHistoryUploadSettings
import io.aaps.copilot.data.repository.RoomAiExportStatus
import io.aaps.copilot.data.repository.ServerAiHistoryError
import io.aaps.copilot.data.repository.ServerAiHistoryFailure
import io.aaps.copilot.security.ServerAiConnectionError
import io.aaps.copilot.security.ServerAiConnectionFailure
import io.aaps.copilot.service.PowerSaveController
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

internal enum class HistoryUploadOutcome { SKIPPED, COMPLETE, MORE }

internal fun isRetryableHistoryUploadFailure(failure: Exception): Boolean = when (failure) {
    is ServerAiHistoryFailure -> failure.reason in setOf(
        ServerAiHistoryError.NETWORK, ServerAiHistoryError.UNAVAILABLE, ServerAiHistoryError.RATE_LIMITED
    )
    is ServerAiConnectionFailure -> failure.reason == ServerAiConnectionError.NETWORK
    is java.io.IOException -> true
    else -> false
}

internal fun historyUploadAllowed(
    enabled: Boolean,
    manual: Boolean,
    powerSave: Boolean,
    hasSession: Boolean
): Boolean = (enabled || manual) && !powerSave && hasSession

internal suspend fun runHistoryUpload(
    allowed: Flow<Boolean>,
    synchronize: suspend () -> RoomAiExportStatus
): HistoryUploadOutcome {
    if (!allowed.first()) return HistoryUploadOutcome.SKIPPED
    return coroutineScope {
        val stop = async { allowed.first { !it } }
        val upload = async { synchronize() }
        try {
            select {
                stop.onAwait { upload.cancelAndJoin(); HistoryUploadOutcome.SKIPPED }
                upload.onAwait { status ->
                    if (status.scanFinished && !status.needsBackfill) HistoryUploadOutcome.COMPLETE
                    else HistoryUploadOutcome.MORE
                }
            }
        } finally {
            stop.cancel()
            upload.cancel()
        }
    }
}

internal suspend fun uploadHistorySlices(synchronize: suspend () -> RoomAiExportStatus): RoomAiExportStatus {
    var status = synchronize()
    repeat(3) {
        if (status.scanFinished && !status.needsBackfill) return status
        status = synchronize()
    }
    return status
}

/** The periodic clock only requests a coalesced drain; backlog has its own durable chain. */
class ServerAiHistoryScheduleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as CopilotApp).container
        return try {
            val settings = container.settingsStore.settings.first()
            if (!settings.aiHistoryUpload.enabled || PowerSaveController.isActive(settings)) return Result.success()
            container.serverAiConnectionInitialization.await()
            if (container.serverAiConnectionManager.state.value.hasStoredSession) {
                ServerAiHistoryUploadSchedule.requestAutomatic(applicationContext)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

/** Persistent, network-constrained transport. Never requests a model or a therapy action. */
class ServerAiHistoryUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as CopilotApp).container
        return try {
            val settings = container.settingsStore.settings.first()
            val manual = inputData.getBoolean(ServerAiHistoryUploadSchedule.MANUAL, false)
            if ((!settings.aiHistoryUpload.enabled && !manual) || PowerSaveController.isActive(settings)) {
                return Result.success()
            }
            // Share startup loading; periodic uploads must not reset the chat/vision readiness UI.
            // The signed client independently rereads credentials and validates/renews access.
            container.serverAiConnectionInitialization.await()
            val outcome = withTimeoutOrNull(120_000L) {
                runHistoryUpload(combine(container.settingsStore.settings, container.serverAiConnectionManager.state) { current, connection ->
                    historyUploadAllowed(current.aiHistoryUpload.enabled, manual,
                        PowerSaveController.isActive(current), connection.hasStoredSession)
                }) { uploadHistorySlices(container::synchronizeServerAiHistory) }
            }
            when (outcome) {
                HistoryUploadOutcome.SKIPPED, HistoryUploadOutcome.COMPLETE -> Result.success()
                HistoryUploadOutcome.MORE -> {
                    ServerAiHistoryUploadSchedule.continueUpload(applicationContext, id, manual)
                    Result.success()
                }
                null -> Result.retry()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // The existing export ledger retains exact pending bytes across worker/process retries.
            if (isRetryableHistoryUploadFailure(failure)) Result.retry() else Result.failure()
        }
    }
}

internal object ServerAiHistoryUploadSchedule {
    const val PERIODIC = "server.history.upload.periodic"
    const val AUTO_WORK = "server.history.upload.automatic"
    const val MANUAL_WORK = "server.history.upload.manual"
    const val MANUAL = "manual_upload"

    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun periodicRequest(settings: AiHistoryUploadSettings) =
        PeriodicWorkRequestBuilder<ServerAiHistoryScheduleWorker>(settings.intervalMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()

    fun manualRequest() = uploadRequest(manual = true)

    fun uploadRequest(manual: Boolean, parentId: UUID? = null) =
        OneTimeWorkRequestBuilder<ServerAiHistoryUploadWorker>()
            .setInputData(workDataOf(MANUAL to manual))
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .apply {
                if (parentId != null) {
                    setId(UUID.nameUUIDFromBytes("history-upload-next:$parentId".toByteArray(Charsets.US_ASCII)))
                    setInitialDelay(5, TimeUnit.SECONDS)
                }
            }.build()

    suspend fun apply(context: Context, settings: AiHistoryUploadSettings, manualAllowed: Boolean = true) {
        val manager = WorkManager.getInstance(context)
        if (settings.enabled) {
            manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest(settings)).await()
        } else {
            manager.cancelUniqueWork(PERIODIC).await()
            manager.cancelUniqueWork(AUTO_WORK).await()
        }
        if (!manualAllowed) manager.cancelUniqueWork(MANUAL_WORK).await()
    }

    suspend fun requestManual(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, manualRequest()).await()
    }

    suspend fun requestAutomatic(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(AUTO_WORK, ExistingWorkPolicy.KEEP, uploadRequest(false)).await()
    }

    suspend fun continueUpload(context: Context, parentId: UUID, manual: Boolean) {
        val manager = WorkManager.getInstance(context)
        val next = uploadRequest(manual, parentId)
        // Recovery after enqueue-but-before-success must not create another successor.
        if (manager.getWorkInfoByIdFlow(next.id).first() != null) return
        manager.enqueueUniqueWork(if (manual) MANUAL_WORK else AUTO_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE, next).await()
    }
}
