package io.aaps.copilot.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.service.PowerSaveController
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal suspend fun refreshNightlyAdvice(
    powerSaveActive: Boolean,
    refresh: suspend () -> Unit,
    scheduleNext: suspend () -> Unit
): Boolean {
    if (powerSaveActive) return false
    refresh()
    scheduleNext()
    return true
}

class NightlyAdviceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as CopilotApp).container
        return try {
            val settings = container.settingsStore.settings.first()
            val completed = refreshNightlyAdvice(PowerSaveController.isActive(settings),
                refresh = { container.nightlyAdviceRepository.refreshIfDue(System.currentTimeMillis(), ZoneId.systemDefault()) },
                scheduleNext = { WorkScheduler.rescheduleNightlyAdvice(applicationContext) })
            if (completed) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
