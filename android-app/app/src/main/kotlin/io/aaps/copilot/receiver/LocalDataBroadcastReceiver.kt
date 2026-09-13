package io.aaps.copilot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.scheduler.WorkScheduler
import io.aaps.copilot.service.LocalNightscoutServiceController
import io.aaps.copilot.service.LocalNightscoutRuntimeReason
import io.aaps.copilot.service.PowerSaveController
import io.aaps.copilot.service.PowerSaveRuntimeState
import io.aaps.copilot.service.TherapyActionRuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class LocalDataBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? CopilotApp ?: return
        val payload = intent ?: return

        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                val action = payload.action.orEmpty()
                val settings = app.container.settingsStore.settings.first()
                PowerSaveRuntimeState.update(settings)
                TherapyActionRuntimeState.update(settings)
                if (PowerSaveController.isActive(settings)) {
                    WorkScheduler.cancelRuntimeWork(context.applicationContext)
                    WorkScheduler.schedulePowerSaveResume(context.applicationContext, settings.powerSaveUntilMs)
                    app.container.stopRuntimeControllers(
                        LocalNightscoutRuntimeReason.POWER_SAVE_ACTIVE
                    )
                    LocalNightscoutServiceController.stop(
                        context.applicationContext,
                        LocalNightscoutRuntimeReason.POWER_SAVE_ACTIVE
                    )
                }
                if (!settings.localBroadcastIngestEnabled) {
                    app.container.auditLogger.warn(
                        "broadcast_ingest_skipped",
                        mapOf("reason" to "disabled_in_settings", "action" to action)
                    )
                    return@launch
                }

                if (!LocalBroadcastTrustPolicy.isAllowed(action)) {
                    app.container.auditLogger.warn(
                        "broadcast_ingest_skipped",
                        mapOf(
                            "reason" to "unsupported_action",
                            "action" to action,
                            "strictValidation" to settings.strictBroadcastSenderValidation
                        )
                    )
                    return@launch
                }
                if (settings.therapyActionsArmed && !PowerSaveController.isActive(settings)) {
                    ensureRuntimeService(context.applicationContext)
                }
                val queued = app.container.broadcastIngestRepository.enqueue(payload)
                if (!queued) {
                    app.container.auditLogger.warn(
                        "broadcast_ingest_skipped",
                        mapOf(
                            "reason" to "queue_rejected",
                            "action" to payload.action.orEmpty()
                        )
                    )
                }
            } catch (error: Throwable) {
                error.rethrowIfCancellationOrFatal()
                try {
                    app.container.auditLogger.warn(
                        "broadcast_ingest_failed",
                        mapOf(
                            "action" to payload.action.orEmpty(),
                            "message" to (error.message ?: error::class.java.simpleName)
                        )
                    )
                } catch (auditError: Throwable) {
                    auditError.rethrowIfCancellationOrFatal()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val lastServiceEnsureAtMs = AtomicLong(0L)
        const val SERVICE_ENSURE_INTERVAL_MS = 10 * 60 * 1000L
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private fun ensureRuntimeService(context: Context) {
        val now = System.currentTimeMillis()
        val last = lastServiceEnsureAtMs.get()
        if (now - last < SERVICE_ENSURE_INTERVAL_MS) return
        if (!lastServiceEnsureAtMs.compareAndSet(last, now)) return
        runCatching {
            LocalNightscoutServiceController.start(context, allowBackground = false)
        }.exceptionOrNull()?.rethrowIfCancellationOrFatal()
    }

    private fun Throwable.rethrowIfCancellationOrFatal() {
        when (this) {
            is CancellationException,
            is VirtualMachineError,
            is LinkageError,
            is ThreadDeath -> throw this
        }
    }
}
