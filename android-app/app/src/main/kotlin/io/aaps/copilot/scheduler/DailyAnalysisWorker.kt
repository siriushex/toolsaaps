package io.aaps.copilot.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.service.PowerSaveController
import io.aaps.copilot.service.PowerSaveRuntimeState
import kotlinx.coroutines.flow.first

class DailyAnalysisWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as CopilotApp).container
        val settings = container.settingsStore.settings.first()
        PowerSaveRuntimeState.update(settings)
        if (PowerSaveController.isActive(settings)) {
            WorkScheduler.cancelRuntimeWork(applicationContext)
            WorkScheduler.schedulePowerSaveResume(applicationContext, settings.powerSaveUntilMs)
            container.stopRuntimeControllers()
            return Result.success()
        }
        return runCatching {
            container.insightsRepository.runDailyAnalysis()
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}
