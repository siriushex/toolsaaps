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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pendingResult = goAsync()
                receiverScope.launch {
                    runCatching {
                        val app = context.applicationContext as? CopilotApp ?: return@runCatching
                        app.container.settingsStore.ensureUamThreeModeConsentV1()
                        val settings = app.container.settingsStore.settings.first()
                        PowerSaveRuntimeState.update(settings)
                        TherapyActionRuntimeState.update(settings)
                        if (!settings.therapyActionsArmed) {
                            WorkScheduler.cancelRuntimeWork(context.applicationContext)
                            app.container.stopRuntimeControllers(
                                LocalNightscoutRuntimeReason.THERAPY_ACTIONS_DISARMED
                            )
                            LocalNightscoutServiceController.stop(
                                context.applicationContext,
                                LocalNightscoutRuntimeReason.THERAPY_ACTIONS_DISARMED
                            )
                            return@runCatching
                        }
                        if (PowerSaveController.isActive(settings)) {
                            WorkScheduler.cancelRuntimeWork(context.applicationContext)
                            WorkScheduler.schedulePowerSaveResume(
                                context = context.applicationContext,
                                untilMs = settings.powerSaveUntilMs
                            )
                            app.container.stopRuntimeControllers(
                                LocalNightscoutRuntimeReason.POWER_SAVE_ACTIVE
                            )
                            LocalNightscoutServiceController.stop(
                                context.applicationContext,
                                LocalNightscoutRuntimeReason.POWER_SAVE_ACTIVE
                            )
                            return@runCatching
                        }
                        WorkScheduler.schedule(context.applicationContext)
                        if (settings.localNightscoutEnabled || settings.localBroadcastIngestEnabled) {
                            LocalNightscoutServiceController.start(context.applicationContext, allowBackground = true)
                        }
                    }
                    pendingResult.finish()
                }
            }
        }
    }

    private companion object {
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
