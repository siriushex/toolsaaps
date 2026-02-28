package io.aaps.copilot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.aaps.copilot.scheduler.WorkScheduler

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> WorkScheduler.schedule(context.applicationContext)
        }
    }
}
