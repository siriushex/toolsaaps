package io.aaps.copilot.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.aaps.copilot.CopilotApp
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class SyncAndAutomateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as CopilotApp).container
        val startedAt = System.currentTimeMillis()
        container.auditLogger.info(
            "automation_worker_started",
            mapOf(
                "workId" to id.toString(),
                "runAttemptCount" to runAttemptCount
            )
        )
        return runCatching {
            withTimeout(WORKER_TIMEOUT_MS) {
                container.automationRepository.runAutomationCycle()
            }
            container.auditLogger.info(
                "automation_worker_completed",
                mapOf(
                    "workId" to id.toString(),
                    "runAttemptCount" to runAttemptCount,
                    "durationMs" to (System.currentTimeMillis() - startedAt)
                )
            )
            Result.success()
        }.getOrElse {
            val timeout = it is TimeoutCancellationException
            container.auditLogger.warn(
                "automation_worker_failed",
                mapOf(
                    "workId" to id.toString(),
                    "runAttemptCount" to runAttemptCount,
                    "durationMs" to (System.currentTimeMillis() - startedAt),
                    "timeout" to timeout,
                    "error" to (it.message ?: it::class.simpleName.orEmpty())
                )
            )
            Result.retry()
        }
    }

    private companion object {
        private const val WORKER_TIMEOUT_MS = 240_000L
    }
}
