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
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking

class LocalNightscoutForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var settingsMonitorJob: Job? = null
    private var minuteCycleJob: Job? = null
    private val lastMinuteBucket = AtomicLong(-1L)

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
        if (!isRuntimeNeeded(settings.localNightscoutEnabled, settings.localBroadcastIngestEnabled)) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        ensureForeground(
            port = settings.localNightscoutPort,
            localNightscoutEnabled = settings.localNightscoutEnabled
        )
        if (settingsMonitorJob == null) {
            settingsMonitorJob = serviceScope.launch {
                app.container.settingsStore.settings.collectLatest { latest ->
                    if (!isRuntimeNeeded(latest.localNightscoutEnabled, latest.localBroadcastIngestEnabled)) {
                        stopSelfSafely()
                        return@collectLatest
                    }
                    ensureForeground(
                        port = latest.localNightscoutPort,
                        localNightscoutEnabled = latest.localNightscoutEnabled
                    )
                }
            }
        }
        ensureMinuteCycle(app)
        // Keep process alive for local loopback and high-frequency local broadcast ingest.
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        settingsMonitorJob?.cancel()
        settingsMonitorJob = null
        minuteCycleJob?.cancel()
        minuteCycleJob = null
        stopForegroundIfNeeded()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureForeground(port: Int, localNightscoutEnabled: Boolean) {
        val safePort = port.coerceIn(1_024, 65_535)
        val notification = buildNotification(
            port = safePort,
            localNightscoutEnabled = localNightscoutEnabled
        )
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

    private fun buildNotification(port: Int, localNightscoutEnabled: Boolean): Notification {
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
            .setContentTitle(
                if (localNightscoutEnabled) {
                    getString(R.string.local_nightscout_notification_title)
                } else {
                    getString(R.string.local_ingest_notification_title)
                }
            )
            .setContentText(
                if (localNightscoutEnabled) {
                    getString(R.string.local_nightscout_notification_text, port)
                } else {
                    getString(R.string.local_ingest_notification_text)
                }
            )
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

    private fun isRuntimeNeeded(localNightscoutEnabled: Boolean, localBroadcastIngestEnabled: Boolean): Boolean {
        return localNightscoutEnabled || localBroadcastIngestEnabled
    }

    private fun ensureMinuteCycle(app: CopilotApp) {
        if (minuteCycleJob != null) return
        minuteCycleJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                val settings = runCatching {
                    app.container.settingsStore.settings.first()
                }.getOrNull()

                if (
                    settings == null ||
                    !isRuntimeNeeded(settings.localNightscoutEnabled, settings.localBroadcastIngestEnabled)
                ) {
                    delay(10_000L)
                    continue
                }

                val now = System.currentTimeMillis()
                val minuteBucket = now / MINUTE_MS
                if (lastMinuteBucket.get() != minuteBucket && lastMinuteBucket.compareAndSet(minuteBucket - 1, minuteBucket)) {
                    runCatching {
                        app.container.automationRepository.runAutomationCycle()
                    }.onFailure {
                        app.container.auditLogger.warn(
                            "minute_cycle_failed",
                            mapOf("error" to (it.message ?: "unknown"))
                        )
                    }
                } else if (lastMinuteBucket.get() < minuteBucket) {
                    // Recover CAS state if process resumed after long sleep.
                    lastMinuteBucket.set(minuteBucket)
                    runCatching {
                        app.container.automationRepository.runAutomationCycle()
                    }.onFailure {
                        app.container.auditLogger.warn(
                            "minute_cycle_failed",
                            mapOf("error" to (it.message ?: "unknown"))
                        )
                    }
                }

                val waitMs = (MINUTE_MS - (System.currentTimeMillis() % MINUTE_MS)).coerceIn(1_000L, MINUTE_MS)
                delay(waitMs)
            }
        }
    }

    companion object {
        const val ACTION_START = "io.aaps.copilot.local_nightscout.START"
        const val ACTION_STOP = "io.aaps.copilot.local_nightscout.STOP"

        private const val CHANNEL_ID = "local_nightscout_runtime"
        private const val NOTIFICATION_ID = 17580
        private const val MINUTE_MS = 60_000L
    }
}
