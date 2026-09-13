package io.aaps.copilot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.data.repository.GlucoseAlertNotifier
import io.aaps.copilot.data.repository.GlucoseAlertMuteOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GlucoseAlertActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val muteOption = GlucoseAlertMuteOption.fromAction(intent?.action) ?: return
        val requestedDurationMs = intent?.getLongExtra(
            GlucoseAlertNotifier.EXTRA_MUTE_DURATION_MS,
            -1L
        ) ?: return
        if (requestedDurationMs != muteOption.durationMs) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val app = context.applicationContext as? CopilotApp ?: return@runCatching
                val now = System.currentTimeMillis()
                val mutedUntilTs = app.container.episodeAlertDelivery.muteFromNotification(
                    nowTs = now,
                    durationMs = requestedDurationMs
                )
                app.container.auditLogger.info(
                    "glucose_alert_muted_from_notification",
                    mapOf(
                        "durationMinutes" to muteOption.minutes,
                        "mutedUntilTs" to mutedUntilTs,
                        "sourceKind" to intent?.getStringExtra(GlucoseAlertNotifier.EXTRA_MUTE_SOURCE_KIND).orEmpty(),
                        "sourceState" to intent?.getStringExtra(GlucoseAlertNotifier.EXTRA_MUTE_SOURCE_STATE).orEmpty(),
                        "episodeId" to intent?.getStringExtra(GlucoseAlertNotifier.EXTRA_EPISODE_ID).orEmpty()
                    )
                )
            }.onFailure { error ->
                Log.w("GlucoseAlertAction", "Failed to mute glucose alerts", error)
            }
            pendingResult.finish()
        }
    }
}
