package io.aaps.copilot.data.repository

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.aaps.copilot.MainActivity
import io.aaps.copilot.R
import io.aaps.copilot.ui.InsulinOnsetCountdown
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** Presentation only; never shares audio, channel, notification id or actions with glucose alarms. */
internal class InsulinOnsetNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                context.getString(R.string.onset_notification_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
        }
    }

    fun notification(timer: InsulinOnsetCountdown, nowMs: Long): Notification {
        val review = timer.needsReviewAt(nowMs)
        val running = !review && nowMs < timer.estimatedAtMs
        val expires = minOf(timer.validUntilMs, timer.estimatedAtMs + 15 * 60_000L)
        val next = if (running) minOf(timer.estimatedAtMs, expires) else expires
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val channel = manager.getNotificationChannel(CHANNEL)
        // Explicit silent mode also prevents banners. Use the quiet channel normally;
        // if its settings changed, keep the stronger no-sound requirement.
        val forceSilent = channel == null || channel.sound != null || channel.shouldVibrate()
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.copilot_glyph_low_watch)
            .setContentTitle(context.getString(if (review) R.string.onset_notification_channel else R.string.onset_notification_title))
            .setContentText(if (review) context.getString(R.string.onset_timer_review)
                else if (running) context.getString(R.string.onset_notification_body,
                (if (timer.retained) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    else DateFormat.getTimeInstance(DateFormat.SHORT)).format(Date(timer.estimatedAtMs)))
                else context.getString(R.string.onset_notification_elapsed))
            .setContentIntent(open)
            // Retained notices use the absolute estimate, so process death cannot leave a negative chronometer.
            .setWhen(timer.estimatedAtMs).setShowWhen(running && !timer.retained)
            .setUsesChronometer(running && !timer.retained).setChronometerCountDown(running && !timer.retained)
            .setTimeoutAfter(if (timer.retained) 0L else (next - nowMs).coerceAtLeast(1L))
            .setOngoing(timer.retained)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true).setSilent(forceSilent).setDefaults(0)
            .build()
    }

    fun show(timer: InsulinOnsetCountdown, nowMs: Long): Boolean {
        if (!timer.visibleAt(nowMs)) return false
        return try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            ensureChannel()
            val channel = manager.getNotificationChannel(CHANNEL) ?: return false
            if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
            channel.group?.let { if (manager.getNotificationChannelGroup(it)?.isBlocked == true) return false }
            manager.notify(NOTIFICATION_ID, notification(timer, nowMs))
            true
        } catch (_: RuntimeException) { false }
    }

    fun clear() { try { manager.cancel(NOTIFICATION_ID) } catch (_: RuntimeException) { } }

    suspend fun follow(timer: InsulinOnsetCountdown?, clock: () -> Long = System::currentTimeMillis,
        waitMs: suspend (Long) -> Unit = { delay(it) }) {
        if (timer == null || !timer.visibleAt(clock())) { clear(); return }
        if (!show(timer, clock())) { clear(); return }
        if (timer.needsReviewAt(clock())) return
        val expires = minOf(timer.validUntilMs, timer.estimatedAtMs + 15 * 60_000L)
        if (clock() < timer.estimatedAtMs) {
            waitMs((minOf(timer.estimatedAtMs, expires + 1L) - clock()).coerceAtLeast(1L))
            if (!timer.visibleAt(clock())) { clear(); return }
            if (clock() >= timer.estimatedAtMs && !show(timer, clock())) { clear(); return }
        }
        if (timer.retained) return
        waitMs((expires - clock() + 1L).coerceAtLeast(1L))
        clear()
    }

    companion object {
        const val CHANNEL = "insulin_onset_v1"
        const val NOTIFICATION_ID = 8422
    }
}
