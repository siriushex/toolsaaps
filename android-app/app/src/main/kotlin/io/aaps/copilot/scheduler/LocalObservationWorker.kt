package io.aaps.copilot.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.service.LocalNightscoutForegroundService
import io.aaps.copilot.service.PowerSaveController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun runLocalObservationRefresh(
    enabled: Boolean,
    therapyArmed: Boolean,
    powerSave: Boolean,
    minuteLoopActive: Boolean,
    refreshLocal: suspend () -> Unit
): Boolean {
    // A queued observer can never upgrade itself into the therapeutic pipeline.
    if (!enabled || therapyArmed || powerSave || minuteLoopActive) return false
    refreshLocal()
    return true
}

class LocalObservationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as CopilotApp).container
        return try {
            val completed = withTimeoutOrNull(90_000L) {
                val settings = container.settingsStore.settings.first()
                runLocalObservationRefresh(
                    enabled = settings.localObservationEnabled,
                    therapyArmed = settings.therapyActionsArmed,
                    powerSave = PowerSaveController.isActive(settings),
                    minuteLoopActive = LocalNightscoutForegroundService.isMinuteLoopActive(),
                    refreshLocal = { container.automationRepository.runLocalReadOnlyCycle() }
                )
                true
            } ?: false
            if (completed) Result.success() else retryOrFail()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            retryOrFail()
        }
    }

    private fun retryOrFail(): Result = if (runAttemptCount < 2) Result.retry() else Result.failure()
}
