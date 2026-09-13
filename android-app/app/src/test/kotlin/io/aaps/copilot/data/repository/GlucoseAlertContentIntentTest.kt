package io.aaps.copilot.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.AlertNotificationEntryActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class GlucoseAlertContentIntentTest {
    @Test
    fun contentIntentCarriesOnlyTrustedEpisodeSelectionAndHasDistinctEpisodeIdentity() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val first = glucoseAlertContentIntent(context, "glucose-alert-0001")
        val second = glucoseAlertContentIntent(context, "glucose-alert-0002")

        assertThat(first.component?.className).isEqualTo(AlertNotificationEntryActivity::class.java.name)
        assertThat(first.action).isEqualTo(GlucoseAlertNotifier.ACTION_OPEN_ALERTS)
        assertThat(first.getStringExtra(GlucoseAlertNotifier.EXTRA_OPEN_ALERT_EPISODE_ID))
            .isEqualTo("glucose-alert-0001")
        assertThat(first.extras?.keySet()).containsExactly(GlucoseAlertNotifier.EXTRA_OPEN_ALERT_EPISODE_ID)
        assertThat(first.data).isNotEqualTo(second.data)
    }
}
