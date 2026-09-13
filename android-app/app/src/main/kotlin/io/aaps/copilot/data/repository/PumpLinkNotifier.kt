package io.aaps.copilot.data.repository

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.aaps.copilot.MainActivity
import io.aaps.copilot.R
import io.aaps.copilot.domain.pump.PumpLinkCondition
import io.aaps.copilot.receiver.GlucoseAlertActionReceiver

class PumpLinkNotifier(private val context: Context) {
    // Called only inside the monitor's global mute coordination boundary.
    internal fun post(condition: PumpLinkCondition): PumpLinkNotice {
        if (!condition.needsAttention) return PumpLinkNotice.FAILED
        val manager = context.getSystemService(NotificationManager::class.java) ?: return PumpLinkNotice.FAILED
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        ) return PumpLinkNotice.FAILED
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                context.getString(R.string.pump_link_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return PumpLinkNotice.FAILED
        val message = context.getString(condition.pumpLinkMessageRes())
        val compact = RemoteViews(context.packageName, R.layout.notification_glucose_alert_compact).apply {
            setTextViewText(R.id.glucose_alert_compact_message, message)
            setTextViewText(R.id.glucose_alert_compact_off_30, context.getString(GlucoseAlertMuteOption.MINUTES_30.compactLabelRes))
            setTextViewText(R.id.glucose_alert_compact_off_60, context.getString(GlucoseAlertMuteOption.MINUTES_60.compactLabelRes))
            setOnClickPendingIntent(R.id.glucose_alert_compact_off_30, mutePendingIntent(GlucoseAlertMuteOption.MINUTES_30))
            setOnClickPendingIntent(R.id.glucose_alert_compact_off_60, mutePendingIntent(GlucoseAlertMuteOption.MINUTES_60))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.pump_link_channel))
            .setContentText(message)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(compact)
            .setCustomBigContentView(compact)
            .build()
        return try {
            manager.notify(TAG, ID, notification)
            PumpLinkNotice.DELIVERED
        } catch (_: SecurityException) { PumpLinkNotice.FAILED }
    }

    internal fun mutePendingIntent(option: GlucoseAlertMuteOption): PendingIntent {
        val intent = Intent(context, GlucoseAlertActionReceiver::class.java)
            .setAction(option.action)
            .setPackage(context.packageName)
            .setData("copilot://pump-link/mute/${option.minutes}".toUri())
            .putExtra(GlucoseAlertNotifier.EXTRA_MUTE_DURATION_MS, option.durationMs)
            .putExtra(GlucoseAlertNotifier.EXTRA_MUTE_SOURCE_KIND, "PUMP_LINK")
        return PendingIntent.getBroadcast(context, ID xor option.requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val TAG = "copilot_pump_link"
        const val ID = 31_010
        const val CHANNEL = "copilot_pump_link_v1"
        fun clear(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(TAG, ID)
        }
    }
}

internal fun PumpLinkCondition.pumpLinkMessageRes(): Int = when (this) {
    PumpLinkCondition.UNKNOWN -> R.string.pump_link_unknown
    PumpLinkCondition.VERIFYING -> R.string.pump_link_verifying
    PumpLinkCondition.HEALTHY -> R.string.pump_link_healthy
    PumpLinkCondition.INTENTIONALLY_DISCONNECTED -> R.string.pump_link_intentional
    PumpLinkCondition.AAPS_UNREACHABLE -> R.string.pump_link_aaps_unreachable
    PumpLinkCondition.STATUS_STALE -> R.string.pump_link_status_stale
    PumpLinkCondition.BLUETOOTH_OFF -> R.string.pump_link_bluetooth_off
    PumpLinkCondition.PERMISSION_MISSING -> R.string.pump_link_permission_missing
    PumpLinkCondition.DRIVER_ERROR -> R.string.pump_link_driver_error
    PumpLinkCondition.MONITOR_UNAVAILABLE -> R.string.pump_link_monitor_unavailable
}
