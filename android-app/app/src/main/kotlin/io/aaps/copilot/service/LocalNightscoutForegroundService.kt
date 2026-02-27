package io.aaps.copilot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.MainActivity
import io.aaps.copilot.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

class LocalNightscoutForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var settingsMonitorJob: Job? = null

    @Volatile
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        val app = application as? CopilotApp ?: return START_NOT_STICKY
        val settings = runBlocking {
            app.container.settingsStore.settings.first()
        }
        if (!settings.localNightscoutEnabled) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        ensureForeground(settings.localNightscoutPort)
        if (settingsMonitorJob == null) {
            settingsMonitorJob = serviceScope.launch {
                app.container.settingsStore.settings.collectLatest { latest ->
                    if (!latest.localNightscoutEnabled) {
                        stopSelfSafely()
                        return@collectLatest
                    }
                    ensureForeground(latest.localNightscoutPort)
                }
            }
        }
        // Service stays sticky so local Nightscout loopback remains reachable for AAPS.
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        settingsMonitorJob?.cancel()
        settingsMonitorJob = null
        stopForegroundIfNeeded()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureForeground(port: Int) {
        val safePort = port.coerceIn(1_024, 65_535)
        val notification = buildNotification(safePort)
        val manager = getSystemService(NotificationManager::class.java)
        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, notification)
            foregroundStarted = true
        } else {
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun stopSelfSafely() {
        stopForegroundIfNeeded()
        stopSelf()
    }

    private fun stopForegroundIfNeeded() {
        if (foregroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            foregroundStarted = false
        }
    }

    private fun buildNotification(port: Int): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.local_nightscout_notification_title))
            .setContentText(getString(R.string.local_nightscout_notification_text, port))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.local_nightscout_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.local_nightscout_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "io.aaps.copilot.local_nightscout.START"
        const val ACTION_STOP = "io.aaps.copilot.local_nightscout.STOP"

        private const val CHANNEL_ID = "local_nightscout_runtime"
        private const val NOTIFICATION_ID = 17580
    }
}
