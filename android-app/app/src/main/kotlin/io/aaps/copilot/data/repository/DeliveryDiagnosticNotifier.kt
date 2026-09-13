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
import io.aaps.copilot.domain.alerts.DeliveryDiagnosticAssessment
import io.aaps.copilot.domain.alerts.DeliveryDiagnosticReason
import io.aaps.copilot.receiver.GlucoseAlertActionReceiver

class DeliveryDiagnosticNotifier(private val context: Context) {
    // Called only inside the monitor's global mute coordination boundary.
    internal fun post(assessment: DeliveryDiagnosticAssessment): AlertReceiptResult {
        if (assessment.reason !in setOf(DeliveryDiagnosticReason.UNEXPECTED_RISE, DeliveryDiagnosticReason.PERSISTENT_HIGH)) return AlertReceiptResult.FAILED
        val manager = context.getSystemService(NotificationManager::class.java) ?: return AlertReceiptResult.FAILED
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        ) return AlertReceiptResult.FAILED
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                context.getString(R.string.delivery_diagnostic_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return AlertReceiptResult.FAILED
        val message = context.getString(R.string.delivery_diagnostic_compact)
        val compact = RemoteViews(context.packageName, R.layout.notification_glucose_alert_compact).apply {
            setTextViewText(R.id.glucose_alert_compact_message, message)
            setTextViewText(R.id.glucose_alert_compact_off_30, context.getString(GlucoseAlertMuteOption.MINUTES_30.compactLabelRes))
            setTextViewText(R.id.glucose_alert_compact_off_60, context.getString(GlucoseAlertMuteOption.MINUTES_60.compactLabelRes))
            setOnClickPendingIntent(R.id.glucose_alert_compact_off_30, mutePendingIntent(GlucoseAlertMuteOption.MINUTES_30))
            setOnClickPendingIntent(R.id.glucose_alert_compact_off_60, mutePendingIntent(GlucoseAlertMuteOption.MINUTES_60))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.delivery_diagnostic_channel))
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
            .setCustomBigContentView(RemoteViews(context.packageName, R.layout.notification_delivery_diagnostic_detail).apply {
                addView(R.id.delivery_diagnostic_controls, compact)
                setTextViewText(R.id.delivery_diagnostic_detail, context.getString(
                    if (assessment.foodConfounded) R.string.delivery_diagnostic_food_detail else R.string.delivery_diagnostic_detail))
            })
            .build()
        return try {
            manager.notify(TAG, ID, notification)
            AlertReceiptResult.DELIVERED
        } catch (_: SecurityException) { AlertReceiptResult.FAILED }
    }

    internal fun mutePendingIntent(option: GlucoseAlertMuteOption): PendingIntent {
        val intent = Intent(context, GlucoseAlertActionReceiver::class.java)
            .setAction(option.action)
            .setPackage(context.packageName)
            .setData("copilot://delivery-diagnostic/mute/${option.minutes}".toUri())
            .putExtra(GlucoseAlertNotifier.EXTRA_MUTE_DURATION_MS, option.durationMs)
            .putExtra(GlucoseAlertNotifier.EXTRA_MUTE_SOURCE_KIND, "DELIVERY_DIAGNOSTIC")
        return PendingIntent.getBroadcast(context, ID xor option.requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val TAG = "copilot_delivery_diagnostic"
        const val ID = 31_020
        const val CHANNEL = "copilot_delivery_diagnostic_v1"
        fun clear(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(TAG, ID)
        }
    }
}
