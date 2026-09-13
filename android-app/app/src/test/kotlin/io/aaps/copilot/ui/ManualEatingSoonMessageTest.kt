package io.aaps.copilot.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.R
import io.aaps.copilot.data.repository.EatingSoonResult
import io.aaps.copilot.data.repository.ManualMealResult
import io.aaps.copilot.data.repository.MealDeliveryStatus
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ManualEatingSoonMessageTest {
    private fun context(language: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(language))
        return base.createConfigurationContext(config)
    }

    @Test fun blockedForecastIncludesItsActualHorizonInsteadOfGenericFailure() {
        val context = context("en")
        val message = manualEatingSoonBlockedMessage(context, "forecast_60m_ci_low")
        assertThat(message).contains("60")
        assertThat(message).contains("4.0")
        assertThat(message).isNotEqualTo(context.getString(R.string.overview_eating_soon_blocked))
    }

    @Test fun refusalReasonsRemainDistinctAndLocalized() {
        val reasons = listOf("kill_switch_active", "actions_disarmed", "target_out_of_bounds",
            "sensor_untrusted", "glucose_too_low", "glucose_stale", "accepted_forecast_unavailable",
            "forecast_stale", "chronology_unresolved", "settings_changed", "target_authority_read_timeout",
            "meal_profile_not_saved", "missing_nightscout_url")
        for (language in listOf("en", "ru")) {
            val context = context(language)
            val messages = reasons.map { manualEatingSoonBlockedMessage(context, it) }
            assertThat(messages.distinct()).hasSize(reasons.size)
            assertThat(messages.all { it.startsWith(context.getString(R.string.overview_eating_soon_blocked)) }).isTrue()
        }
        assertThat(manualEatingSoonBlockedMessage(context("ru"), "sensor_untrusted"))
            .contains("сенсор")
    }

    @Test fun unknownUntrustedReasonDoesNotExposeRawDetails() {
        val context = context("en")
        val message = manualEatingSoonBlockedMessage(context, "https://private.example/?token=test-secret")
        assertThat(message).doesNotContain("private.example")
        assertThat(message).doesNotContain("test-secret")
        assertThat(message).isEqualTo(manualEatingSoonBlockedMessage(context, null))
    }

    @Test fun persistedGuardPrefixAndOtherForecastHorizonsAreHandled() {
        val context = context("en")
        for (minutes in listOf(5, 30, 60)) {
            assertThat(manualEatingSoonBlockedMessage(context, "managed_preflight:forecast_${minutes}m_ci_low"))
                .isEqualTo(manualEatingSoonBlockedMessage(context, "forecast_${minutes}m_ci_low"))
            assertThat(manualEatingSoonBlockedMessage(context, "forecast_${minutes}m_too_low"))
                .contains(minutes.toString())
        }
    }

    @Test fun composedMealProfileFailureAppearsOnceWithOrWithoutEatingSoon() {
        for (language in listOf("en", "ru")) {
            val context = context(language)
            for (status in listOf(MealDeliveryStatus.BLOCKED, MealDeliveryStatus.NOT_REQUESTED)) {
                val result = ManualMealResult(MealDeliveryStatus.SENT,
                    EatingSoonResult(status, "meal_profile_not_saved"), profileSaved = false)
                val message = manualMealSubmissionMessage(context, 20.0, result)
                assertThat(message.lines()).hasSize(2)
                val expectedReason = if (status == MealDeliveryStatus.BLOCKED)
                    R.string.eating_soon_reason_profile else R.string.overview_meal_profile_failed
                assertThat(message).contains(context.getString(expectedReason))
            }
        }
    }
}
