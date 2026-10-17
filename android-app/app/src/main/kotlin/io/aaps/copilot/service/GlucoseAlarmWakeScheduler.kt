package io.aaps.copilot.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.aaps.copilot.AlertNotificationEntryActivity
import io.aaps.copilot.data.repository.GlucoseAlertNotifier
import io.aaps.copilot.data.repository.LocalAlarmSourceClock
import io.aaps.copilot.domain.alerts.LocalAlarmWake
import java.util.UUID
import kotlin.math.abs

/** A wake token carries no clinical authority. Fresh accepted evidence is still required. */
class GlucoseAlarmWakeScheduler(private val context: Context, private val clock: () -> LocalAlarmSourceClock?) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences("glucose_alarm_wake_v1", Context.MODE_PRIVATE)
    private var scheduled: LocalAlarmWake? = null
    private var ownedToken: String? = null

    fun available(): Boolean = try { alarms?.canScheduleExactAlarms() == true } catch (_: RuntimeException) { false }

    fun schedule(wake: LocalAlarmWake?): Boolean {
        if (wake == null) return try { cancel(); true } catch (_: RuntimeException) { false }
        val now = clock()
        if (now == null || !available() || !wake.level.strong || wake.bootCount != now.bootCount ||
            wake.dueElapsedMs <= now.elapsedMs || wake.dueElapsedMs >= wake.validUntilElapsedMs ||
            wake.dueElapsedMs - now.elapsedMs > Long.MAX_VALUE - now.wallMs) {
            runCatching { cancel() }; return false
        }
        if (scheduled == wake && ownedToken == prefs.getString("token", null)) return true
        try { cancel() } catch (_: RuntimeException) { return false }
        val token = UUID.randomUUID().toString()
        val intent = operationIntent().putExtra(EXTRA_TOKEN, token)
        return try {
            if (!prefs.edit().putString("token", token).putInt("boot", now.bootCount)
                    .putLong("created_elapsed", now.elapsedMs).putLong("created_wall", now.wallMs)
                    .putLong("due", wake.dueElapsedMs).putLong("expiry", wake.validUntilElapsedMs).commit()) return false
            ownedToken = token
            val show = PendingIntent.getActivity(context, 8412,
                Intent(context, AlertNotificationEntryActivity::class.java).setAction(GlucoseAlertNotifier.ACTION_OPEN_ALERTS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val operation = PendingIntent.getForegroundService(context, 8411, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(now.wallMs + wake.dueElapsedMs - now.elapsedMs, show), operation)
            scheduled = wake
            true
        } catch (_: RuntimeException) { runCatching { cancel() }; false }
    }

    /** Reject replaced, rebooted, time-shifted, premature and expired pending operations. */
    fun consume(intent: Intent?): Boolean {
        if (intent?.action != ACTION_WAKE) return false
        val token = intent.getStringExtra(EXTRA_TOKEN)?.takeIf { it.length == 36 } ?: return false
        if (token != prefs.getString("token", null)) return false
        val now = clock() ?: return false
        val valid = validWakeWindow(now, prefs.getInt("boot", -1), prefs.getLong("created_elapsed", -1),
            prefs.getLong("created_wall", -1), prefs.getLong("due", -1), prefs.getLong("expiry", -1))
        // Consume even a matched but invalid token; it must never become valid later.
        prefs.edit().remove("token").commit()
        scheduled = null
        return valid
    }

    fun cancel() {
        scheduled = null
        // A newly created service may clear an obsolete operation; a retired owner
        // may not cancel a replacement installed by a newer lifetime.
        val stored = prefs.getString("token", null)
        if (ownedToken != null && stored != null && ownedToken != stored) return
        try {
            PendingIntent.getForegroundService(context, 8411, operationIntent(),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { alarms?.cancel(it); it.cancel() }
        } finally {
            prefs.edit().remove("token").commit()
            ownedToken = null
        }
    }

    private fun operationIntent() = Intent(context, GlucoseAlarmService::class.java).setAction(ACTION_WAKE)

    companion object {
        const val ACTION_WAKE = "io.aaps.copilot.ALARM_WAKE"
        const val EXTRA_TOKEN = "wake_token"
        internal fun validWakeWindow(now: LocalAlarmSourceClock, boot: Int, elapsed: Long, wall: Long,
            due: Long, expiry: Long): Boolean = boot >= 0 && now.bootCount == boot && elapsed >= 0 && wall > 0 &&
            now.elapsedMs >= elapsed && now.wallMs >= wall && due > elapsed && expiry > due &&
            now.elapsedMs >= due && now.elapsedMs < expiry &&
            abs((now.wallMs - wall) - (now.elapsedMs - elapsed)) <= 5_000L
    }
}
