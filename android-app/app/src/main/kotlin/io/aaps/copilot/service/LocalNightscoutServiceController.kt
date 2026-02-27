package io.aaps.copilot.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object LocalNightscoutServiceController {

    fun reconcile(context: Context, enabled: Boolean) {
        if (enabled) {
            start(context)
            return
        }
        // Runtime service is also used to keep local broadcast ingest reliable.
        // Let service decide whether it should stay alive based on current settings.
        start(context)
    }

    fun start(context: Context) {
        val intent = Intent(context, LocalNightscoutForegroundService::class.java)
            .setAction(LocalNightscoutForegroundService.ACTION_START)
        runCatching {
            ContextCompat.startForegroundService(context, intent)
        }.onFailure {
            runCatching {
                context.startService(intent)
            }
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, LocalNightscoutForegroundService::class.java))
    }
}
