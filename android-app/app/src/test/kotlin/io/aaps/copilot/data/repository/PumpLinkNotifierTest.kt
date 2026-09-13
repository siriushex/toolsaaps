package io.aaps.copilot.data.repository

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.pump.PumpLinkCondition
import io.aaps.copilot.receiver.GlucoseAlertActionReceiver
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PumpLinkNotifierTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun independentSlotAndClearCannotRemoveGlucoseNotification() {
        manager.createNotificationChannel(NotificationChannel("existing_glucose", "test", NotificationManager.IMPORTANCE_HIGH))
        manager.notify("glucose", 31_000, Notification.Builder(context, "existing_glucose").setSmallIcon(android.R.drawable.ic_dialog_alert).build())
        assertThat(PumpLinkNotifier(context).post(PumpLinkCondition.DRIVER_ERROR)).isEqualTo(PumpLinkNotice.DELIVERED)
        assertThat(manager.activeNotifications.map { it.tag }).containsExactly("glucose", PumpLinkNotifier.TAG)
        assertThat(manager.getNotificationChannel(PumpLinkNotifier.CHANNEL).canBypassDnd()).isFalse()
        PumpLinkNotifier.clear(context)
        assertThat(manager.activeNotifications.map { it.tag }).containsExactly("glucose")
    }

    @Test fun deniedOrDisabledNotificationsReturnFailureWithoutPosting() {
        val notifier = PumpLinkNotifier(context)
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertThat(notifier.post(PumpLinkCondition.STATUS_STALE)).isEqualTo(PumpLinkNotice.FAILED)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.createNotificationChannel(NotificationChannel(PumpLinkNotifier.CHANNEL, "disabled", NotificationManager.IMPORTANCE_NONE))
        assertThat(notifier.post(PumpLinkCondition.STATUS_STALE)).isEqualTo(PumpLinkNotice.FAILED)
        assertThat(manager.activeNotifications).isEmpty()
    }

    @Test fun bothMuteButtonsUseExplicitExistingGlobalMuteReceiver() {
        val notifier = PumpLinkNotifier(context)
        val intents = GlucoseAlertMuteOption.entries.map { option ->
            val pending = notifier.mutePendingIntent(option)
            val intent = shadowOf(pending).savedIntent
            assertThat(intent.component?.className).isEqualTo(GlucoseAlertActionReceiver::class.java.name)
            assertThat(intent.action).isEqualTo(option.action)
            assertThat(intent.getLongExtra(GlucoseAlertNotifier.EXTRA_MUTE_DURATION_MS, -1)).isEqualTo(option.durationMs)
            pending
        }
        assertThat(intents.distinct()).hasSize(2)
    }
}
