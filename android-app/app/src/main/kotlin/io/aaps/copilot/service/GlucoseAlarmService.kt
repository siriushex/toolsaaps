package io.aaps.copilot.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

object GlucoseAlarmRuntimeState {
    private val mutable = MutableStateFlow(GlucoseAlarmOwnerState())
    val state = mutable.asStateFlow()
    fun update(state: GlucoseAlarmOwnerState) { mutable.value = state }
}

object GlucoseAlarmServiceController {
    suspend fun reconcile(context: Context) {
        val app = context.applicationContext as? CopilotApp ?: return
        try {
            val enabled = app.container.settingsStore.settings.first().localAlarmRuntimeEnabled
            if (!enabled) {
                app.container.glucoseAlarmRouter.stop()
                context.stopService(Intent(context, GlucoseAlarmService::class.java))
                GlucoseAlarmRuntimeState.update(GlucoseAlarmOwnerState())
            } else ContextCompat.startForegroundService(context, Intent(context, GlucoseAlarmService::class.java))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            GlucoseAlarmRuntimeState.update(GlucoseAlarmOwnerState(GlucoseAlarmOwnerPhase.UNAVAILABLE))
        }
    }
}

class GlucoseAlarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var owner: GlucoseAlarmOwner? = null
    private lateinit var platform: GlucoseAlarmAndroidPlatform
    private val container get() = (application as CopilotApp).container
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        platform = GlucoseAlarmAndroidPlatform(this, GlucoseAlarmWakeScheduler(this, container::currentAlarmSourceClock))
        try {
            platform.ensureChannel()
            val notice = platform.notification(GlucoseAlarmOwnerState(GlucoseAlarmOwnerPhase.STARTING))
            if (Build.VERSION.SDK_INT >= 34) startForeground(GlucoseAlarmAndroidPlatform.NOTIFICATION_ID, notice,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(GlucoseAlarmAndroidPlatform.NOTIFICATION_ID, notice)
            foreground = true
        } catch (_: RuntimeException) {
            GlucoseAlarmRuntimeState.update(GlucoseAlarmOwnerState(GlucoseAlarmOwnerPhase.UNAVAILABLE))
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foreground) return START_NOT_STICKY
        if (intent?.action == GlucoseAlarmWakeScheduler.ACTION_WAKE &&
            !runCatching { platform.wakes.consume(intent) }.getOrDefault(false)) {
            if (job == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (job?.isCompleted == false) { owner?.signal(); return START_STICKY }
        job = scope.launch {
            var attached = false
            val candidate = GlucoseAlarmOwner(container.db, container.settingsStore.settings,
                container.episodeAlertDelivery.alarmSource, container.episodeAlertDelivery.mutedUntil,
                container.episodeAlertDelivery::currentAlarmSource, container::currentAlarmSourceClock,
                AndroidAlarmVolumePort(getSystemService(AudioManager::class.java)),
                container.glucoseAlertAudioController, platform)
            try {
                if (!container.settingsStore.settings.first().localAlarmRuntimeEnabled) return@launch
                attached = container.glucoseAlarmRouter.attach(candidate)
                if (!attached) return@launch
                owner = candidate
                candidate.run()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                GlucoseAlarmRuntimeState.update(GlucoseAlarmOwnerState(GlucoseAlarmOwnerPhase.UNAVAILABLE))
            } finally {
                if (attached) {
                    container.glucoseAlarmRouter.detach(candidate)
                    val cancelled = runCatching { platform.wakes.cancel() }.isSuccess
                    GlucoseAlarmRuntimeState.update(if (cancelled) candidate.state.value else
                        GlucoseAlarmOwnerState(GlucoseAlarmOwnerPhase.UNAVAILABLE))
                }
                owner = null
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        owner?.stop()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
