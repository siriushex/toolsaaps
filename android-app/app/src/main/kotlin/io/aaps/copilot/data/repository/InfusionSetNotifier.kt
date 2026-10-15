package io.aaps.copilot.data.repository

import android.Manifest
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

class InfusionSetNotifier(private val context: Context) {
    fun capable(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return false
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                context.getString(R.string.infusion_set_title), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false)
            })
        }
        return manager.getNotificationChannel(CHANNEL)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true
    }

    fun post(): InfusionPostResult {
        if (!capable()) return InfusionPostResult.BLOCKED
        val manager = context.getSystemService(NotificationManager::class.java) ?: return InfusionPostResult.BLOCKED
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.infusion_set_due))
            .setContentText(context.getString(R.string.infusion_set_reminder))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true).setAutoCancel(true).setSilent(true)
            .setContentIntent(PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        return try { manager.notify(TAG, ID, notification); InfusionPostResult.POSTED }
            catch (_: SecurityException) { InfusionPostResult.BLOCKED }
    }
    companion object {
        const val TAG = "copilot_infusion_maintenance"
        const val ID = 31_030
        const val CHANNEL = "copilot_infusion_maintenance_v1"
        fun clear(context: Context) { context.getSystemService(NotificationManager::class.java)?.cancel(TAG, ID) }
    }
}
