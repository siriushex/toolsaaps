package io.aaps.copilot.data.repository

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class InfusionSetNotifierTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
    @Test fun silentMaintenanceSlotCannotRemoveGlucoseNotification() {
        manager.createNotificationChannel(NotificationChannel("existing_glucose", "test", NotificationManager.IMPORTANCE_HIGH))
        manager.notify("glucose", 31_000, Notification.Builder(context, "existing_glucose")
            .setSmallIcon(android.R.drawable.ic_dialog_alert).build())
        assertEquals(InfusionPostResult.POSTED, InfusionSetNotifier(context).post())
        val channel = manager.getNotificationChannel(InfusionSetNotifier.CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.canBypassDnd())
        val reminder = manager.activeNotifications.single { it.tag == InfusionSetNotifier.TAG }.notification
        assertEquals(Notification.VISIBILITY_PRIVATE, reminder.visibility)
        assertEquals(0, reminder.defaults)
        assertNull(shadowOf(reminder.contentIntent).savedIntent.extras)
        InfusionSetNotifier.clear(context)
        assertEquals(listOf("glucose"), manager.activeNotifications.map { it.tag })
    }
    @Test fun permissionOrChannelBlockIsKnownFailureNotPosted() {
        val notifier = InfusionSetNotifier(context)
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(notifier.capable())
        assertEquals(InfusionPostResult.BLOCKED, notifier.post())
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.createNotificationChannel(NotificationChannel(InfusionSetNotifier.CHANNEL, "disabled", NotificationManager.IMPORTANCE_NONE))
        assertFalse(notifier.capable())
        assertEquals(InfusionPostResult.BLOCKED, notifier.post())
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
