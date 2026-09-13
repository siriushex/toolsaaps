package io.aaps.copilot.data.repository

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.widget.FrameLayout
import android.widget.TextView
import io.aaps.copilot.R
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.alerts.DeliveryDiagnosticAssessment
import io.aaps.copilot.domain.alerts.DeliveryDiagnosticReason
import io.aaps.copilot.receiver.GlucoseAlertActionReceiver
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DeliveryDiagnosticNotifierTest {
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
        assertThat(DeliveryDiagnosticNotifier(context).post(DeliveryDiagnosticAssessment(DeliveryDiagnosticReason.UNEXPECTED_RISE, foodConfounded = true))).isEqualTo(AlertReceiptResult.DELIVERED)
        assertThat(manager.activeNotifications.map { it.tag }).containsExactly("glucose", DeliveryDiagnosticNotifier.TAG)
        assertThat(manager.getNotificationChannel(DeliveryDiagnosticNotifier.CHANNEL).canBypassDnd()).isFalse()
        DeliveryDiagnosticNotifier.clear(context)
        assertThat(manager.activeNotifications.map { it.tag }).containsExactly("glucose")
    }

    @Test fun deniedOrDisabledNotificationsReturnFailureWithoutPosting() {
        val notifier = DeliveryDiagnosticNotifier(context)
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertThat(notifier.post(DeliveryDiagnosticAssessment(DeliveryDiagnosticReason.PERSISTENT_HIGH))).isEqualTo(AlertReceiptResult.FAILED)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.createNotificationChannel(NotificationChannel(DeliveryDiagnosticNotifier.CHANNEL, "disabled", NotificationManager.IMPORTANCE_NONE))
        assertThat(notifier.post(DeliveryDiagnosticAssessment(DeliveryDiagnosticReason.PERSISTENT_HIGH))).isEqualTo(AlertReceiptResult.FAILED)
        assertThat(manager.activeNotifications).isEmpty()
    }

    @Test fun bothMuteButtonsUseExplicitExistingGlobalMuteReceiver() {
        val notifier = DeliveryDiagnosticNotifier(context)
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

    @Test fun compactAndExpandedViewsBothContainWorkingOffControlsAndCautiousCopy() {
        val notifier = DeliveryDiagnosticNotifier(context)
        notifier.post(DeliveryDiagnosticAssessment(DeliveryDiagnosticReason.UNEXPECTED_RISE, foodConfounded = true))
        val notification = manager.activeNotifications.single().notification
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("High glucose: check delivery")
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).contains("Possible")
        val parent = FrameLayout(context)
        for (remote in listOf(notification.contentView, notification.bigContentView)) {
            val view = remote.apply(context, parent)
            for (id in listOf(R.id.glucose_alert_compact_off_30, R.id.glucose_alert_compact_off_60)) {
                val button = view.findViewById<TextView>(id)
                assertThat(button.text.toString()).startsWith("OFF")
                assertThat(button.hasOnClickListeners()).isTrue()
            }
        }
        val expanded = notification.bigContentView.apply(context, parent)
        assertThat(expanded.findViewById<TextView>(R.id.delivery_diagnostic_detail).text.toString()).contains("Food or UAM")
        assertThat(shadowOf(notification.contentIntent).savedIntent.extras).isNull()
    }
}
