package io.aaps.copilot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.aaps.copilot.CopilotApp

class PumpLinkHealthReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != PumpLinkHealthContract.ACTION) return
        val snapshot = PumpLinkHealthContract.decode(intent.extras) ?: return
        val app = context.applicationContext as? CopilotApp ?: return
        val pending = goAsync()
        app.container.acceptPumpLinkHealth(snapshot) { pending.finish() }
    }
}
