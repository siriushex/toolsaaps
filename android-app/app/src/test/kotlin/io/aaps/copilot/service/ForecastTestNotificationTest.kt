package io.aaps.copilot.service

import android.app.Application
import android.app.Notification
import android.content.pm.ServiceInfo
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], application = Application::class)
class ForecastTestNotificationTest {
    @After fun stop() { ForecastTestRuntime.session.stop() }

    @Test fun notificationOnlyOffersStopWhileActiveAndHasNoSound() {
        val controller = Robolectric.buildService(LocalNightscoutForegroundService::class.java).create()
        try {
            val service = controller.get()
            assertThat(service.buildNotification(17582, false).actions).isNull()
            ForecastTestRuntime.session.start()
            val notification = service.buildNotification(17582, false)
            assertThat(notification.actions).hasLength(1)
            assertThat(notification.extras.getString(Notification.EXTRA_SUB_TEXT))
                .isEqualTo(service.getString(io.aaps.copilot.R.string.forecast_test_notification, 0L))
            assertThat(notification.sound).isNull()
            assertThat(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE).isNotEqualTo(0)
            val stop = shadowOf(notification.actions[0].actionIntent).savedIntent
            assertThat(stop.component?.className).isEqualTo(LocalNightscoutForegroundService::class.java.name)
            service.onStartCommand(stop, 0, 1)
            assertThat(ForecastTestRuntime.session.state.value.active).isFalse()
            assertThat(service.buildNotification(17582, false).actions).isNull()
        } finally { controller.destroy() }
    }

    @Test fun destroyingForegroundOwnerTerminatesTest() {
        val controller = Robolectric.buildService(LocalNightscoutForegroundService::class.java).create()
        ForecastTestRuntime.session.start()
        controller.destroy()
        assertThat(ForecastTestRuntime.session.state.value.status).isEqualTo(ForecastTestStatus.UNAVAILABLE)
        assertThat(ForecastTestRuntime.session.state.value.active).isFalse()
    }

    @Test fun pendingStartDoesNotAdvertiseActiveRuntimeAndOwnerLossCancelsIt() {
        val controller = Robolectric.buildService(LocalNightscoutForegroundService::class.java).create()
        val request = checkNotNull(ForecastTestRuntime.session.requestStart())
        try {
            assertThat(controller.get().buildNotification(17582, false).actions).isNull()
        } finally { controller.destroy() }
        assertThat(ForecastTestRuntime.session.completeStart(request)).isNull()
        assertThat(ForecastTestRuntime.session.state.value.active).isFalse()
        assertThat(ForecastTestRuntime.session.state.value.pendingStart).isFalse()
    }

    @Test fun androidServiceTimeoutStopsTestAndServiceInsteadOfCrashing() {
        val controller = Robolectric.buildService(LocalNightscoutForegroundService::class.java).create()
        try {
            ForecastTestRuntime.session.start()
            controller.get().onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            assertThat(ForecastTestRuntime.session.state.value.active).isFalse()
            assertThat(ForecastTestRuntime.session.state.value.status).isEqualTo(ForecastTestStatus.UNAVAILABLE)
            assertThat(shadowOf(controller.get()).isStoppedBySelf).isTrue()
        } finally { controller.destroy() }
    }
}
