package io.aaps.copilot.data.repository

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.R
import io.aaps.copilot.domain.alerts.AlertCauseCode
import java.io.File
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class GlucoseAlertNotificationPresentationTest {

    @Test
    fun notifierPresentationUsesClaimedStageInsteadOfCurrentDecisionStage() {
        val currentLowNow = decision(GlucoseAlertNotifyKind.LOW_NOW)
        val recoveredWarningClaim = EpisodeDeliveryClaim(
            episodeId = "glucose-low-1",
            kind = AlertDeliveryKind.INITIAL,
            notificationTag = "glucose-alert",
            notificationId = 1,
            persistedCause = AlertCauseCode.SENSOR_QUALITY,
            claimedStage = GlucoseAlertState.WARNING_30
        )

        val presentation = claimedDecisionForDelivery(currentLowNow, recoveredWarningClaim)

        assertThat(presentation.state).isEqualTo(GlucoseAlertState.WARNING_30)
        assertThat(presentation.episodeStage).isEqualTo(GlucoseAlertState.WARNING_30)
        assertThat(presentation.notifyKind).isEqualTo(GlucoseAlertNotifyKind.WARNING_30)
    }

    @Test
    fun buildsShortLabelsForEveryVisibleAlertStage() {
        assertThat(compactGlucoseAlertText("LOW NOW", 3.8, locale = Locale.ENGLISH))
            .isEqualTo("LOW NOW · 3.80")
        assertThat(compactGlucoseAlertText("LOW ≤5m", 4.1, locale = Locale.ENGLISH))
            .isEqualTo("LOW ≤5m · 4.10")
        assertThat(compactGlucoseAlertText("LOW ≤30m", 4.7, locale = Locale.ENGLISH))
            .isEqualTo("LOW ≤30m · 4.70")
        assertThat(compactGlucoseAlertText("LOW ≤60m", 5.0, locale = Locale.ENGLISH))
            .isEqualTo("LOW ≤60m · 5.00")
        assertThat(compactGlucoseAlertText("HIGH ≤30m", 10.5, locale = Locale.ENGLISH))
            .isEqualTo("HIGH ≤30m · 10.50")
    }

    @Test
    fun keepsMessageShortWhenCurrentGlucoseIsUnavailable() {
        assertThat(compactGlucoseAlertText("LOW ≤30m", null, locale = Locale.ENGLISH))
            .isEqualTo("LOW ≤30m")
    }

    @Test
    fun addsLocalizedCauseTokenWithoutChangingCompactRiskOrGlucose() {
        assertThat(
            compactGlucoseAlertText(
                riskLabel = "LOW ≤30m",
                currentGlucoseMmol = 4.7,
                causeLabel = "IOB + falling",
                locale = Locale.ENGLISH
            )
        ).isEqualTo("LOW ≤30m · IOB + falling · 4.70")
    }

    @Test
    fun everyCauseHasEnglishAndRussianFixedLabelResources() {
        val english = File("src/main/res/values/strings.xml").readText()
        val russian = File("src/main/res/values-ru/strings.xml").readText()

        AlertCauseCode.entries.forEach { code ->
            val name = "glucose_alert_cause_${code.name.lowercase()}"
            assertThat(english).contains("name=\"$name\"")
            assertThat(russian).contains("name=\"$name\"")
        }
        assertThat(russian).contains("IOB + снижение")
    }

    @Test
    fun compactRiskLabelsExistInEnglishAndRussianResources() {
        val english = File("src/main/res/values/strings.xml").readText()
        val russian = File("src/main/res/values-ru/strings.xml").readText()
        val names = listOf(
            "glucose_alert_compact_risk_low_now",
            "glucose_alert_compact_risk_low_5m",
            "glucose_alert_compact_risk_low_30m",
            "glucose_alert_compact_risk_low_60m",
            "glucose_alert_compact_risk_high_30m"
        )

        names.forEach { name ->
            assertThat(english).contains("name=\"$name\"")
            assertThat(russian).contains("name=\"$name\"")
        }
        assertThat(russian).contains("НИЗКО ≤30м")
    }

    @Test
    fun russianCompactTextUsesLocalizedRiskAndDecimalSeparator() {
        val rendered = compactGlucoseAlertText(
            riskLabel = "НИЗКО ≤30м",
            currentGlucoseMmol = 4.7,
            causeLabel = "IOB + снижение",
            locale = Locale.forLanguageTag("ru-RU")
        )

        assertThat(rendered).isEqualTo("НИЗКО ≤30м · IOB + снижение · 4,70")
    }

    @Test
    fun longestLocalizedCompactTextIsSingleLineAndBounded() {
        val rendered = compactGlucoseAlertText(
            riskLabel = "НИЗКО СЕЙЧАС",
            currentGlucoseMmol = 3.79,
            causeLabel = "возможна доставка инсулина",
            locale = Locale.forLanguageTag("ru-RU")
        )

        assertThat(rendered).doesNotContain("\n")
        assertThat(rendered.length).isAtMost(MAX_COMPACT_ALERT_TEXT_CHARS)
    }

    @Test
    fun deliveryNonresponseUsesResolvedPossibleIssueLabelsInFinalCompactText() {
        val english = localizedContext(Locale.ENGLISH)
        val russianLocale = Locale.forLanguageTag("ru-RU")
        val russian = localizedContext(russianLocale)

        val englishLabel = english.getString(R.string.glucose_alert_cause_delivery_nonresponse)
        val russianLabel = russian.getString(R.string.glucose_alert_cause_delivery_nonresponse)
        val englishText = compactGlucoseAlertText(
            riskLabel = english.getString(R.string.glucose_alert_compact_risk_low_30m),
            currentGlucoseMmol = 4.7,
            causeLabel = englishLabel,
            locale = Locale.ENGLISH
        )
        val russianText = compactGlucoseAlertText(
            riskLabel = russian.getString(R.string.glucose_alert_compact_risk_low_30m),
            currentGlucoseMmol = 4.7,
            causeLabel = russianLabel,
            locale = russianLocale
        )

        assertThat(englishLabel).isEqualTo("possible delivery issue")
        assertThat(russianLabel).isEqualTo("возможна проблема подачи")
        assertThat(englishText).isEqualTo("LOW ≤30m · possible delivery issue · 4.70")
        assertThat(russianText).isEqualTo("НИЗКО ≤30м · возможна проблема подачи · 4,70")
        listOf(englishText, russianText).forEach { rendered ->
            assertThat(rendered).doesNotContain("\n")
            assertThat(rendered.length).isAtMost(MAX_COMPACT_ALERT_TEXT_CHARS)
        }
    }

    @Test
    fun notifierHasNoHardCodedCompactRiskOrUsNumberLocale() {
        val source = File("src/main/kotlin/io/aaps/copilot/data/repository/GlucoseAlertNotifier.kt").readText()

        assertThat(source).doesNotContain("Locale.US")
        assertThat(source).doesNotContain("\"LOW NOW\"")
        assertThat(source).doesNotContain("\"LOW ≤")
        assertThat(source).doesNotContain("\"HIGH ≤")
    }

    @Test
    fun domainAnalyzerContainsNoHardCodedRussianPresentationText() {
        val source = File("src/main/kotlin/io/aaps/copilot/domain/alerts/AlertCauseAnalyzer.kt").readText()

        assertThat(source).doesNotContainMatch("[А-Яа-яЁё]")
    }

    private fun decision(kind: GlucoseAlertNotifyKind): GlucoseAlertDecision {
        val state = when (kind) {
            GlucoseAlertNotifyKind.LOW_NOW -> GlucoseAlertState.LOW_NOW
            GlucoseAlertNotifyKind.CRITICAL_5 -> GlucoseAlertState.CRITICAL_5
            GlucoseAlertNotifyKind.WARNING_30 -> GlucoseAlertState.WARNING_30
            GlucoseAlertNotifyKind.WATCH_60 -> GlucoseAlertState.WATCH_60
            GlucoseAlertNotifyKind.SOFT_HIGH -> GlucoseAlertState.SOFT_HIGH_RISK
            else -> GlucoseAlertState.NONE
        }
        return GlucoseAlertDecision(
            state = state,
            direction = if (kind == GlucoseAlertNotifyKind.SOFT_HIGH) {
                GlucoseAlertDirection.HIGH
            } else {
                GlucoseAlertDirection.LOW
            },
            notifyKind = kind,
            nextState = GlucoseAlertRuntimeState(activeAlertState = state),
            lowThreshold = 4.4,
            highThreshold = 10.0,
            urgentLowThreshold = 4.0,
            pred5 = null,
            pred30 = null,
            pred60 = null,
            ciLow30 = null,
            ciHigh30 = null,
            currentGlucoseMmol = null,
            currentGlucoseFresh = true,
            predictedMinutesToLow = null,
            trendDelta5Mmol = null,
            softActive = kind.isSoft(),
            strongActive = kind.isStrong(),
            repeatSuppressedByTrend = false,
            disableReason = null
        )
    }

    private fun localizedContext(locale: Locale): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        assertThat(base.javaClass).isEqualTo(Application::class.java)
        val configuration = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(configuration)
    }
}
