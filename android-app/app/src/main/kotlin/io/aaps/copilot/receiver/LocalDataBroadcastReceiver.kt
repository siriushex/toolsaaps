package io.aaps.copilot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.aaps.copilot.CopilotApp
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
                app.container.broadcastIngestRepository.ingest(payload)
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
