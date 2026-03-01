package io.aaps.copilot.domain.rules

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.RuleState
import org.junit.Test

class AdaptiveTargetControllerRuleTest {

    private val rule = AdaptiveTargetControllerRule()

    @Test
    fun lowersTarget_whenForecastsAreHigh() {
        val now = System.currentTimeMillis()
        val decision = rule.evaluate(
            context(
                now = now,
                glucose = listOf(
                    GlucosePoint(now - 5 * 60_000, 5.5, "test", DataQuality.OK),
                    GlucosePoint(now, 5.5, "test", DataQuality.OK)
                ),
                forecasts = listOf(
                    Forecast(now + 5 * 60_000, 5, 8.0, 7.6, 8.4, "test"),
                    Forecast(now + 30 * 60_000, 30, 8.0, 7.2, 8.8, "test"),
                    Forecast(now + 60 * 60_000, 60, 8.0, 6.8, 9.2, "test")
                )
            )
        )

        assertThat(decision.state).isEqualTo(RuleState.TRIGGERED)
        assertThat(decision.actionProposal).isNotNull()
        assertThat(decision.actionProposal!!.targetMmol).isLessThan(5.5)
        assertThat(decision.actionProposal!!.durationMinutes).isEqualTo(30)
    }

    @Test
    fun raisesTarget_whenForecastsAreLow() {
        val now = System.currentTimeMillis()
        val decision = rule.evaluate(
            context(
                now = now,
                glucose = listOf(
                    GlucosePoint(now - 5 * 60_000, 5.4, "test", DataQuality.OK),
                    GlucosePoint(now, 5.3, "test", DataQuality.OK)
                ),
                forecasts = listOf(
                    Forecast(now + 5 * 60_000, 5, 4.4, 4.0, 4.8, "test"),
                    Forecast(now + 30 * 60_000, 30, 4.2, 3.8, 4.6, "test"),
                    Forecast(now + 60 * 60_000, 60, 4.0, 3.4, 4.6, "test")
                )
            )
        )

        assertThat(decision.state).isEqualTo(RuleState.TRIGGERED)
        assertThat(decision.actionProposal).isNotNull()
        assertThat(decision.actionProposal!!.targetMmol).isGreaterThan(5.5)
    }

    @Test
    fun returnsNoMatch_insideDeadband() {
        val now = System.currentTimeMillis()
        val decision = rule.evaluate(
            context(
                now = now,
                glucose = listOf(
                    GlucosePoint(now - 5 * 60_000, 5.50, "test", DataQuality.OK),
                    GlucosePoint(now, 5.50, "test", DataQuality.OK)
                ),
                forecasts = listOf(
                    Forecast(now + 5 * 60_000, 5, 5.55, 5.35, 5.9, "test"),
                    Forecast(now + 30 * 60_000, 30, 5.52, 5.32, 5.9, "test"),
                    Forecast(now + 60 * 60_000, 60, 5.51, 5.31, 6.0, "test")
                )
            )
        )

        assertThat(decision.state).isEqualTo(RuleState.NO_MATCH)
        assertThat(decision.reasons).contains("target_equals_base")
        assertThat(decision.reasons).contains("reason=control_deadband")
    }

    @Test
    fun controllerStillTriggers_withTelemetryProvided() {
        val now = System.currentTimeMillis()
        val decision = rule.evaluate(
            context(
                now = now,
                glucose = listOf(
                    GlucosePoint(now - 5 * 60_000, 5.6, "test", DataQuality.OK),
                    GlucosePoint(now, 5.3, "test", DataQuality.OK)
                ),
                forecasts = listOf(
                    Forecast(now + 5 * 60_000, 5, 5.1, 4.8, 5.4, "test"),
                    Forecast(now + 30 * 60_000, 30, 4.9, 4.5, 5.3, "test"),
                    Forecast(now + 60 * 60_000, 60, 4.8, 4.2, 5.4, "test")
                ),
                telemetry = mapOf("iob_units" to 2.4)
            )
        )

        assertThat(decision.state).isEqualTo(RuleState.TRIGGERED)
        assertThat(decision.reasons.any { it.startsWith("reason=") }).isTrue()
    }

    @Test
    fun lowersTarget_whenCobIsSignificant() {
        val now = System.currentTimeMillis()
        val decision = rule.evaluate(
            context(
                now = now,
                glucose = listOf(
                    GlucosePoint(now - 5 * 60_000, 5.6, "test", DataQuality.OK),
                    GlucosePoint(now, 5.6, "test", DataQuality.OK)
                ),
                forecasts = listOf(
                    Forecast(now + 5 * 60_000, 5, 5.7, 5.2, 6.2, "test"),
                    Forecast(now + 30 * 60_000, 30, 5.8, 5.0, 6.5, "test"),
                    Forecast(now + 60 * 60_000, 60, 5.9, 4.8, 7.0, "test")
                ),
                telemetry = mapOf("cob_grams" to 35.0)
            )
        )

        assertThat(decision.state).isEqualTo(RuleState.TRIGGERED)
        assertThat(decision.actionProposal).isNotNull()
        assertThat(decision.actionProposal!!.targetMmol).isAtMost(4.2)
        val tbValue = decision.reasons
            .firstOrNull { it.startsWith("Tb=") }
            ?.substringAfter("=")
            ?.replace(",", ".")
            ?.toDoubleOrNull()
        assertThat(tbValue).isNotNull()
        assertThat(tbValue!!).isWithin(0.01).of(4.2)
    }

    private fun context(
        now: Long,
        glucose: List<GlucosePoint>,
        forecasts: List<Forecast>,
        telemetry: Map<String, Double?> = emptyMap()
    ): RuleContext = RuleContext(
        nowTs = now,
        glucose = glucose,
        therapyEvents = emptyList(),
        forecasts = forecasts,
        currentDayPattern = null,
        baseTargetMmol = 5.5,
        dataFresh = true,
        activeTempTargetMmol = null,
        actionsLast6h = 0,
        sensorBlocked = false,
        latestTelemetry = telemetry,
        adaptiveMaxStepMmol = 0.25
    )
}
