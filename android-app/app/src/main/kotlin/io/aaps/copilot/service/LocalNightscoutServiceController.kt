package io.aaps.copilot.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build
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
        if (PowerSaveRuntimeState.isActive() || !TherapyActionRuntimeState.isArmed()) {
            return
        }
        if (!allowBackground && !AppVisibilityTracker.isForeground()) {
            return
        }
        val intent = Intent(context, LocalNightscoutForegroundService::class.java)
            .setAction(LocalNightscoutForegroundService.ACTION_START)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (error: IllegalStateException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                error !is ForegroundServiceStartNotAllowedException) throw error
            LocalNightscoutRuntimeState.stopped(LocalNightscoutRuntimeReason.FOREGROUND_START_NOT_ALLOWED)
        }
    }

    fun stop(
        context: Context,
        reason: LocalNightscoutRuntimeReason? = LocalNightscoutRuntimeReason.SERVICE_STOPPED
    ) {
        context.stopService(Intent(context, LocalNightscoutForegroundService::class.java))
        reason?.let(LocalNightscoutRuntimeState::stopped)
    }
}
