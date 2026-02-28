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
        stop(context)
    }

    fun start(context: Context, allowBackground: Boolean = false) {
        if (!allowBackground && !AppVisibilityTracker.isForeground()) {
            return
        }
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
