package io.aaps.copilot.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.repository.*
import io.aaps.copilot.domain.alerts.*
import io.aaps.copilot.receiver.GlucoseAlarmAckReceiver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class GlucoseAlarmAndroidPlatformTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val platform = GlucoseAlarmAndroidPlatform(context, GlucoseAlarmWakeScheduler(context) {
        LocalAlarmSourceClock(100_000, 1_000, 2)
    })
    @Test fun manifestUsesPrivateSpecialUseServiceAndPrivateAcknowledgementReceiver() {
        val service = context.packageManager.getServiceInfo(ComponentName(context, GlucoseAlarmService::class.java), 0)
        assertFalse(service.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)
        assertFalse(context.packageManager.getReceiverInfo(ComponentName(context, GlucoseAlarmAckReceiver::class.java), 0).exported)
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.SCHEDULE_EXACT_ALARM in permissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE in permissions)
    }
    @Test fun statusNotificationIsSilentPrivateAndCarriesOnlyCurrentScopedAction() {
        platform.ensureChannel()
        val action = LocalAlarmAcknowledgement(LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, "event-one"), 1, 2, LocalAlarmLevel.LOW_NOW)
        val session = "d07a12c2-e9ab-4b6d-b917-1c53bcb48936"
        val notification = platform.notification(GlucoseAlarmOwnerState(GlucoseAlarmOwnerPhase.RUNNING,
            acknowledgement = action, sessionId = session))
        assertNull(notification.sound)
        assertNull(notification.vibrate)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(1, notification.actions.size)
        val encoded = shadowOf(notification.actions.single().actionIntent).savedIntent
        assertEquals(session to action, GlucoseAlarmAckReceiver.decode(encoded))
        assertTrue(platform.notification(GlucoseAlarmOwnerState()).actions.isNullOrEmpty())
    }
    @Test fun disabledChannelRemainsBlockedAndCannotBeReportedPosted() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.createNotificationChannel(NotificationChannel(GlucoseAlarmAndroidPlatform.CHANNEL, "Blocked owner", NotificationManager.IMPORTANCE_NONE))
        platform.ensureChannel()
        assertFalse(platform.capabilities().notifications)
        assertEquals(LocalAlarmNotificationResult.BLOCKED, platform.publish(GlucoseAlarmOwnerState(GlucoseAlarmOwnerPhase.LIMITED)))
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(GlucoseAlarmAndroidPlatform.CHANNEL).importance)
    }
    @Test fun boundedWakeAndVibrationCleanupCannotReleaseAnotherCycle() {
        val first = LocalAlarmCycle(LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, "event-one"), 1, 1, LocalAlarmLevel.LOW_NOW, 2, 1_000, 56_000)
        val other = first.copy(ordinal = 2)
        assertTrue(platform.acquireWake(first))
        assertFalse(platform.acquireWake(other))
        platform.releaseWake(other)
        assertTrue(platform.acquireWake(first))
        platform.releaseWake(first)
        assertTrue(platform.acquireWake(other))
        platform.releaseWake(other)
    }
}
