package io.aaps.copilot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.scheduler.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LocalDataBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? CopilotApp ?: return
        val payload = intent ?: return

        val pendingResult = goAsync()
        receiverScope.launch {
            runCatching {
                val result = app.container.broadcastIngestRepository.ingest(payload)
                if (result.glucoseImported > 0 || result.therapyImported > 0) {
                    WorkScheduler.triggerReactiveAutomation(context.applicationContext)
                    app.container.auditLogger.info(
                        "broadcast_reactive_automation_enqueued",
                        mapOf(
                            "action" to payload.action.orEmpty(),
                            "glucose" to result.glucoseImported,
                            "therapy" to result.therapyImported
                        )
                    )
                }
            }.onFailure { error ->
                app.container.auditLogger.warn(
                    "broadcast_ingest_failed",
                    mapOf(
                        "action" to payload.action.orEmpty(),
                        "message" to (error.message ?: error::class.java.simpleName)
                    )
                )
            }
            pendingResult.finish()
        }
    }

    private companion object {
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
