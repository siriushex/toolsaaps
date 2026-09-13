package io.aaps.copilot.service

import android.app.Notification
import android.app.ForegroundServiceStartNotAllowedException
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
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.scheduler.WorkScheduler
import java.util.concurrent.atomic.AtomicBoolean
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
import kotlinx.coroutines.withContext

internal fun stopLocalRuntimeAfterServiceExit(
    foregroundStartDenied: Boolean,
    stopRuntime: (LocalNightscoutRuntimeReason) -> Unit
) {
    stopRuntime(
        if (foregroundStartDenied) LocalNightscoutRuntimeReason.FOREGROUND_START_NOT_ALLOWED
        else LocalNightscoutRuntimeReason.SERVICE_STOPPED
    )
}

class LocalNightscoutForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var settingsMonitorJob: Job? = null
    private var runtimeStateMonitorJob: Job? = null
    private var minuteCycleJob: Job? = null
    private var debugVacuumJob: Job? = null
    private val lastPeriodicCycleBucket = AtomicLong(-1L)
    @Volatile
    private var latestSettingsSnapshot: AppSettings? = null

    @Volatile
    private var foregroundStarted = false
    private var foregroundStartDenied = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        // Android requires calling startForeground almost immediately after startForegroundService.
        try {
            ensureForeground(port = DEFAULT_NOTIFICATION_PORT, localNightscoutEnabled = true)
            foregroundStartDenied = false
        } catch (error: IllegalStateException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                error !is ForegroundServiceStartNotAllowedException) throw error
            foregroundStartDenied = true
            LocalNightscoutRuntimeState.stopped(LocalNightscoutRuntimeReason.FOREGROUND_START_NOT_ALLOWED)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val app = application as? CopilotApp ?: run {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        if (runtimeStateMonitorJob == null) {
            runtimeStateMonitorJob = serviceScope.launch {
                LocalNightscoutRuntimeState.snapshot.collectLatest { runtime ->
                    val settings = latestSettingsSnapshot
                    if (foregroundStarted && settings != null) {
                        ensureForeground(
                            port = runtime.port ?: settings.localNightscoutPort,
                            localNightscoutEnabled = settings.localNightscoutEnabled
                        )
                    }
                }
            }
        }
        if (intent?.action == ACTION_DEBUG_VACUUM) {
            startDebugVacuum(app)
            return START_STICKY
        }
        if (intent?.action == ACTION_DEBUG_DB_DIAGNOSTICS) {
            startDebugDatabaseDiagnostics(app)
            return START_STICKY
        }
        serviceScope.launch {
            val settings = app.container.settingsStore.settings.first()
            latestSettingsSnapshot = settings
            PowerSaveRuntimeState.update(settings)
            TherapyActionRuntimeState.update(settings)
            if (!settings.therapyActionsArmed) {
                app.container.stopRuntimeControllers(
                    LocalNightscoutRuntimeReason.THERAPY_ACTIONS_DISARMED
                )
                WorkScheduler.cancelRuntimeWork(applicationContext)
                stopSelfSafely()
                return@launch
            }
            if (PowerSaveController.isActive(settings)) {
                app.container.stopRuntimeControllers(LocalNightscoutRuntimeReason.POWER_SAVE_ACTIVE)
                WorkScheduler.cancelRuntimeWork(applicationContext)
                WorkScheduler.schedulePowerSaveResume(applicationContext, settings.powerSaveUntilMs)
                stopSelfSafely()
                return@launch
            }
            if (!isRuntimeNeeded(settings.localNightscoutEnabled, settings.localBroadcastIngestEnabled)) {
                app.container.stopRuntimeControllers(LocalNightscoutRuntimeReason.DISABLED)
                stopSelfSafely()
                return@launch
            }
            app.container.startRuntimeControllers()
            ensureForeground(
                port = settings.localNightscoutPort,
                localNightscoutEnabled = settings.localNightscoutEnabled
            )
            if (settingsMonitorJob == null) {
                settingsMonitorJob = serviceScope.launch {
                    app.container.settingsStore.settings.collectLatest { latest ->
                        latestSettingsSnapshot = latest
                        PowerSaveRuntimeState.update(latest)
                        TherapyActionRuntimeState.update(latest)
                        if (!latest.therapyActionsArmed) {
                            app.container.stopRuntimeControllers(
                                LocalNightscoutRuntimeReason.THERAPY_ACTIONS_DISARMED
                            )
                            WorkScheduler.cancelRuntimeWork(applicationContext)
                            stopSelfSafely()
                            return@collectLatest
                        }
                        if (PowerSaveController.isActive(latest)) {
                            app.container.stopRuntimeControllers(
                                LocalNightscoutRuntimeReason.POWER_SAVE_ACTIVE
                            )
                            WorkScheduler.cancelRuntimeWork(applicationContext)
                            WorkScheduler.schedulePowerSaveResume(applicationContext, latest.powerSaveUntilMs)
                            stopSelfSafely()
                            return@collectLatest
                        }
                        if (!isRuntimeNeeded(latest.localNightscoutEnabled, latest.localBroadcastIngestEnabled)) {
                            app.container.stopRuntimeControllers(LocalNightscoutRuntimeReason.DISABLED)
                            stopSelfSafely()
                            return@collectLatest
                        }
                        app.container.startRuntimeControllers()
                        ensureForeground(
                            port = latest.localNightscoutPort,
                            localNightscoutEnabled = latest.localNightscoutEnabled
                        )
                    }
                }
            }
            ensureMinuteCycle(app)
        }
        // Keep process alive for local loopback and high-frequency local broadcast ingest.
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        settingsMonitorJob?.cancel()
        settingsMonitorJob = null
        runtimeStateMonitorJob?.cancel()
        runtimeStateMonitorJob = null
        minuteCycleJob?.cancel()
        minuteCycleJob = null
        debugVacuumJob?.cancel()
        debugVacuumJob = null
        stopLocalRuntimeAfterServiceExit(foregroundStartDenied) { reason ->
            (application as? CopilotApp)?.container?.stopRuntimeControllers(reason)
        }
        minuteLoopActive.set(false)
        stopForegroundIfNeeded()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startDebugVacuum(app: CopilotApp) {
        val activeJob = debugVacuumJob
        if (activeJob?.isActive == true) {
            serviceScope.launch(Dispatchers.IO) {
                app.container.auditLogger.warn(
                    "db_vacuum_debug_trigger_skipped",
                    mapOf("reason" to "already_running")
                )
            }
            return
        }
        debugVacuumJob = serviceScope.launch(Dispatchers.IO) {
            runCatching {
                app.container.auditLogger.info(
                    "db_vacuum_debug_foreground_started",
                    mapOf("source" to "foreground_service")
                )
                app.container.automationRepository.runDbHousekeepingNowForDebug()
                app.container.broadcastIngestRepository.runPeriodicMaintenanceNowForDebug()
                CopilotDatabaseCompactor.vacuumNowForDebug(
                    context = applicationContext,
                    db = app.container.db,
                    auditLogger = app.container.auditLogger
                )
            }.onSuccess { result ->
                app.container.auditLogger.info(
                    "db_vacuum_debug_trigger_completed",
                    mapOf(
                        "source" to "foreground_service",
                        "skipped" to result.skipped,
                        "reason" to result.reason,
                        "beforeBytes" to result.beforeBytes,
                        "afterBytes" to result.afterBytes,
                        "durationMs" to result.durationMs
                    )
                )
            }.onFailure { error ->
                app.container.auditLogger.error(
                    "db_vacuum_debug_trigger_failed",
                    mapOf("error" to (error.message ?: error::class.java.simpleName).take(320))
                )
            }

            val settings = runCatching {
                app.container.settingsStore.settings.first()
            }.getOrNull()
            latestSettingsSnapshot = settings
            withContext(Dispatchers.Main.immediate) {
                debugVacuumJob = null
                if (
                    settings == null ||
                    PowerSaveController.isActive(settings) ||
                    !isRuntimeNeeded(settings.localNightscoutEnabled, settings.localBroadcastIngestEnabled)
                ) {
                    app.container.stopRuntimeControllers(runtimeStopReason(settings))
                    if (settings != null && PowerSaveController.isActive(settings)) {
                        WorkScheduler.cancelRuntimeWork(applicationContext)
                        WorkScheduler.schedulePowerSaveResume(applicationContext, settings.powerSaveUntilMs)
                    }
                    stopSelfSafely()
                } else {
                    app.container.startRuntimeControllers()
                    ensureForeground(
                        port = settings.localNightscoutPort,
                        localNightscoutEnabled = settings.localNightscoutEnabled
                    )
                    ensureMinuteCycle(app)
                }
            }
        }
    }

    private fun startDebugDatabaseDiagnostics(app: CopilotApp) {
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                app.container.auditLogger.info(
                    "db_table_diagnostics_debug_started",
                    mapOf("source" to "foreground_service")
                )
                CopilotDatabaseDiagnostics.logSummaryForDebug(
                    db = app.container.db,
                    auditLogger = app.container.auditLogger
                )
            }.onFailure { error ->
                app.container.auditLogger.error(
                    "db_table_diagnostics_debug_failed",
                    mapOf("error" to (error.message ?: error::class.java.simpleName).take(320))
                )
            }

            val settings = runCatching {
                app.container.settingsStore.settings.first()
            }.getOrNull()
            latestSettingsSnapshot = settings
            withContext(Dispatchers.Main.immediate) {
                if (
                    settings == null ||
                    PowerSaveController.isActive(settings) ||
                    !isRuntimeNeeded(settings.localNightscoutEnabled, settings.localBroadcastIngestEnabled)
                ) {
                    app.container.stopRuntimeControllers(runtimeStopReason(settings))
                    if (settings != null && PowerSaveController.isActive(settings)) {
                        WorkScheduler.cancelRuntimeWork(applicationContext)
                        WorkScheduler.schedulePowerSaveResume(applicationContext, settings.powerSaveUntilMs)
                    }
                    stopSelfSafely()
                } else {
                    app.container.startRuntimeControllers()
                    ensureForeground(
                        port = settings.localNightscoutPort,
                        localNightscoutEnabled = settings.localNightscoutEnabled
                    )
                    ensureMinuteCycle(app)
                }
            }
        }
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

        val runtime = LocalNightscoutRuntimeState.value
        val reasonSuffix = runtime.reason?.let { " (reason=$it)" }.orEmpty()
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
                    getString(
                        R.string.local_nightscout_notification_text,
                        runtime.status.name,
                        port,
                        reasonSuffix
                    )
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

    private fun runtimeStopReason(settings: AppSettings?): LocalNightscoutRuntimeReason = when {
        settings == null -> LocalNightscoutRuntimeReason.SERVICE_STOPPED
        !settings.therapyActionsArmed -> LocalNightscoutRuntimeReason.THERAPY_ACTIONS_DISARMED
        PowerSaveController.isActive(settings) -> LocalNightscoutRuntimeReason.POWER_SAVE_ACTIVE
        !isRuntimeNeeded(settings.localNightscoutEnabled, settings.localBroadcastIngestEnabled) ->
            LocalNightscoutRuntimeReason.DISABLED
        else -> LocalNightscoutRuntimeReason.SERVICE_STOPPED
    }

    private fun ensureMinuteCycle(app: CopilotApp) {
        if (minuteCycleJob != null) return
        minuteCycleJob = serviceScope.launch(Dispatchers.IO) {
            minuteLoopActive.set(true)
            try {
                while (isActive) {
                    val settings = latestSettingsSnapshot ?: runCatching {
                        app.container.settingsStore.settings.first()
                    }.getOrNull()?.also { latestSettingsSnapshot = it }

                    if (
                        settings == null ||
                        PowerSaveController.isActive(settings) ||
                        !isRuntimeNeeded(settings.localNightscoutEnabled, settings.localBroadcastIngestEnabled)
                    ) {
                        if (settings != null && PowerSaveController.isActive(settings)) {
                            app.container.stopRuntimeControllers(
                                LocalNightscoutRuntimeReason.POWER_SAVE_ACTIVE
                            )
                            stopSelfSafely()
                            return@launch
                        }
                        delay(10_000L)
                        continue
                    }

                    val now = System.currentTimeMillis()
                    val cycleBucket = now / PERIODIC_CYCLE_INTERVAL_MS
                    if (
                        lastPeriodicCycleBucket.get() != cycleBucket &&
                        lastPeriodicCycleBucket.compareAndSet(cycleBucket - 1, cycleBucket)
                    ) {
                        runCatching {
                            app.container.automationRepository.runAutomationCycle()
                        }.onFailure {
                            app.container.auditLogger.warn(
                                "minute_cycle_failed",
                                mapOf("error" to (it.message ?: "unknown"))
                            )
                        }
                    } else if (lastPeriodicCycleBucket.get() < cycleBucket) {
                        // Recover CAS state if process resumed after long sleep.
                        lastPeriodicCycleBucket.set(cycleBucket)
                        runCatching {
                            app.container.automationRepository.runAutomationCycle()
                        }.onFailure {
                            app.container.auditLogger.warn(
                                "minute_cycle_failed",
                                mapOf("error" to (it.message ?: "unknown"))
                            )
                        }
                    }

                    val msUntilNextCycle =
                        PERIODIC_CYCLE_INTERVAL_MS - (System.currentTimeMillis() % PERIODIC_CYCLE_INTERVAL_MS)
                    val waitMs = msUntilNextCycle.coerceIn(5_000L, PERIODIC_CYCLE_INTERVAL_MS)
                    delay(waitMs)
                }
            } finally {
                minuteLoopActive.set(false)
            }
        }
    }

    companion object {
        const val ACTION_START = "io.aaps.copilot.local_nightscout.START"
        const val ACTION_STOP = "io.aaps.copilot.local_nightscout.STOP"
        const val ACTION_DEBUG_VACUUM = "io.aaps.copilot.local_nightscout.DEBUG_VACUUM"
        const val ACTION_DEBUG_DB_DIAGNOSTICS = "io.aaps.copilot.local_nightscout.DEBUG_DB_DIAGNOSTICS"

        private const val CHANNEL_ID = "local_nightscout_runtime"
        private const val NOTIFICATION_ID = 17580
        private const val PERIODIC_CYCLE_INTERVAL_MS = 5L * 60_000L
        private const val DEFAULT_NOTIFICATION_PORT = 17582
        private val minuteLoopActive = AtomicBoolean(false)

        fun isMinuteLoopActive(): Boolean = minuteLoopActive.get()

    }
}
