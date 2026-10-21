package io.aaps.copilot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.aaps.copilot.AlertNotificationEntryActivity
import io.aaps.copilot.R
import io.aaps.copilot.data.repository.*
import io.aaps.copilot.domain.alerts.*
import io.aaps.copilot.receiver.GlucoseAlarmAckReceiver

class GlucoseAlarmAndroidPlatform(private val context: Context, val wakes: GlucoseAlarmWakeScheduler) : GlucoseAlarmPlatform {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeCycle: LocalAlarmCycle? = null
    private var vibrationCycle: LocalAlarmCycle? = null
    private var published: GlucoseAlarmOwnerState? = null

    fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL) == null) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.alarm_runtime_title), NotificationManager.IMPORTANCE_LOW)
                .apply { setSound(null, null); enableVibration(false); setShowBadge(false) })
    }

    override fun capabilities(): GlucoseAlarmCapabilities = try {
        val channel = manager.getNotificationChannel(CHANNEL)
        val groupAllowed = channel?.group?.let { manager.getNotificationChannelGroup(it)?.isBlocked == false } ?: true
        GlucoseAlarmCapabilities(NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE && groupAllowed,
            audio != null, audio != null && !audio.isVolumeFixed && audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) > 0,
            wakes.available())
    } catch (_: RuntimeException) { GlucoseAlarmCapabilities(false, false, false, false) }

    fun notification(state: GlucoseAlarmOwnerState): Notification {
        val open = PendingIntent.getActivity(context, 8413,
            Intent(context, AlertNotificationEntryActivity::class.java).setAction(GlucoseAlertNotifier.ACTION_OPEN_ALERTS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.copilot_glyph_low_watch)
            .setContentTitle(context.getString(when (state.acknowledgement?.level) {
                LocalAlarmLevel.LOW_NOW -> R.string.glucose_alert_low_now_title
                LocalAlarmLevel.CRITICAL_5 -> R.string.glucose_alert_critical_title
                LocalAlarmLevel.WARNING_30 -> R.string.glucose_alert_warning_title
                LocalAlarmLevel.SOFT_HIGH_RISK -> R.string.glucose_alert_soft_high_title
                else -> R.string.alarm_runtime_title
            }))
            .setContentText(context.getString(alarmRuntimeStatusText(state.phase)))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (state.sessionId != null && state.acknowledgement != null) {
            builder.addAction(0, context.getString(R.string.alarm_runtime_ack_minutes, state.acknowledgementPauseMinutes),
                GlucoseAlarmAckReceiver.pendingIntent(context, state.sessionId, state.acknowledgement))
        }
        return builder.build()
    }

    override fun publish(state: GlucoseAlarmOwnerState): LocalAlarmNotificationResult {
        GlucoseAlarmRuntimeState.update(state)
        if (!capabilities().notifications) return LocalAlarmNotificationResult.BLOCKED
        return try {
            if (published != state) { manager.notify(NOTIFICATION_ID, notification(state)); published = state }
            LocalAlarmNotificationResult.POSTED
        } catch (_: RuntimeException) { LocalAlarmNotificationResult.FAILED }
    }

    override fun schedule(wake: LocalAlarmWake?) = wakes.schedule(wake)

    override fun acquireWake(cycle: LocalAlarmCycle): Boolean {
        if (wakeCycle != null) return wakeCycle == cycle && wakeLock?.isHeld == true
        return try {
            val lock = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "copilot:glucose-alert")
            lock.setReferenceCounted(false)
            lock.acquire(65_000L)
            wakeLock = lock; wakeCycle = cycle
            lock.isHeld
        } catch (_: RuntimeException) { false }
    }

    override fun releaseWake(cycle: LocalAlarmCycle) {
        if (wakeCycle != cycle) return
        try { wakeLock?.takeIf { it.isHeld }?.release() }
        finally { wakeLock = null; wakeCycle = null }
    }

    override fun vibrate(cycle: LocalAlarmCycle): LocalAlarmVibrationResult = try {
        if (vibrator?.hasVibrator() != true) LocalAlarmVibrationResult.UNAVAILABLE else {
            val pattern = if (cycle.level.strong) longArrayOf(0, 450, 180, 450) else longArrayOf(0, 180)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            vibrationCycle = cycle
            LocalAlarmVibrationResult.REQUESTED
        }
    } catch (_: RuntimeException) { LocalAlarmVibrationResult.FAILED }

    override fun stopVibration(cycle: LocalAlarmCycle) {
        if (vibrationCycle == cycle) { vibrator?.cancel(); vibrationCycle = null }
    }

    companion object {
        const val CHANNEL = "glucose_alarm_owner_v1"
        const val NOTIFICATION_ID = 8410
    }
}

fun alarmRuntimeStatusText(phase: GlucoseAlarmOwnerPhase) = when (phase) {
    GlucoseAlarmOwnerPhase.OFF -> R.string.alarm_runtime_off
    GlucoseAlarmOwnerPhase.STARTING -> R.string.alarm_runtime_starting
    GlucoseAlarmOwnerPhase.WAITING -> R.string.alarm_runtime_waiting
    GlucoseAlarmOwnerPhase.RUNNING -> R.string.alarm_runtime_running
    GlucoseAlarmOwnerPhase.PAUSED -> R.string.alarm_runtime_paused
    GlucoseAlarmOwnerPhase.LIMITED -> R.string.alarm_runtime_limited
    GlucoseAlarmOwnerPhase.UNAVAILABLE -> R.string.alarm_runtime_unavailable
}
