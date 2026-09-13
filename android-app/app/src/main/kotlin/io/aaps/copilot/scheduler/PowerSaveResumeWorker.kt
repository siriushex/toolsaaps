package io.aaps.copilot.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.service.LocalNightscoutServiceController
import io.aaps.copilot.service.LocalNightscoutRuntimeReason
import io.aaps.copilot.service.PowerSaveController
import io.aaps.copilot.service.PowerSaveRuntimeState
import io.aaps.copilot.service.TherapyActionRuntimeState
import kotlinx.coroutines.flow.first

class PowerSaveResumeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as CopilotApp
        val container = app.container
        val settings = container.settingsStore.settings.first()
        val now = System.currentTimeMillis()
        TherapyActionRuntimeState.update(settings)
        if (!settings.therapyActionsArmed) {
            WorkScheduler.cancelRuntimeWork(applicationContext)
            LocalNightscoutServiceController.stop(
                applicationContext,
                LocalNightscoutRuntimeReason.THERAPY_ACTIONS_DISARMED
            )
            return Result.success()
        }

        if (PowerSaveController.isIndefinite(settings.powerSaveUntilMs)) {
            PowerSaveRuntimeState.update(settings)
            return Result.success()
        }

        if (PowerSaveController.isActive(settings, now)) {
            PowerSaveRuntimeState.update(settings)
            WorkScheduler.schedulePowerSaveResume(applicationContext, settings.powerSaveUntilMs)
            return Result.success()
        }

        if (settings.powerSaveUntilMs > PowerSaveController.OFF_UNTIL_MS) {
            container.settingsStore.update {
                it.copy(powerSaveUntilMs = PowerSaveController.OFF_UNTIL_MS)
            }
        }
        PowerSaveRuntimeState.clear()
        WorkScheduler.cancelPowerSaveResume(applicationContext)
        WorkScheduler.schedule(applicationContext)
        if (settings.localNightscoutEnabled || settings.localBroadcastIngestEnabled) {
            LocalNightscoutServiceController.start(applicationContext, allowBackground = true)
        }
        container.auditLogger.info(
            "power_save_auto_resumed",
            mapOf("expiredUntilMs" to settings.powerSaveUntilMs)
        )
        return Result.success()
    }
}
