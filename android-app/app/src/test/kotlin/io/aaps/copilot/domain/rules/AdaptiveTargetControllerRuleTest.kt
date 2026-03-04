package io.aaps.copilot.domain.rules

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.RuleState
import org.junit.Test

class AdaptiveTargetControllerRuleTest {

    @Test
    fun lowersTarget_whenForecastsAreHigh() {
        val rule = AdaptiveTargetControllerRule()
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
        val rule = AdaptiveTargetControllerRule()
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
        val rule = AdaptiveTargetControllerRule()
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
        val rule = AdaptiveTargetControllerRule()
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
        val rule = AdaptiveTargetControllerRule()
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

    @Test
    fun activityTriggerRaisesTargetInConfiguredRange() {
        val rule = AdaptiveTargetControllerRule()
        val now = System.currentTimeMillis()

        rule.evaluate(
            context(
                now = now,
                glucose = defaultGlucose(now),
                forecasts = defaultForecasts(now),
                telemetry = mapOf(
                    "steps_count" to 1000.0,
                    "activity_ratio" to 1.02
                )
            )
        )

        val decision = rule.evaluate(
            context(
                now = now + 5 * 60_000,
                glucose = defaultGlucose(now + 5 * 60_000),
                forecasts = defaultForecasts(now + 5 * 60_000),
                telemetry = mapOf(
                    "steps_count" to 1140.0,
                    "activity_ratio" to 1.34
                )
            )
        )

        assertThat(decision.state).isEqualTo(RuleState.TRIGGERED)
        assertThat(decision.actionProposal).isNotNull()
        assertThat(decision.reasons).contains("activity_protection_active")
        assertThat(decision.actionProposal!!.targetMmol).isAtLeast(7.7)
        assertThat(decision.actionProposal!!.targetMmol).isAtMost(8.7)
    }

    @Test
    fun activityRecoveryReturnsBaseAfterSustainedLowLoad() {
        val rule = AdaptiveTargetControllerRule()
        val now = System.currentTimeMillis()

        rule.evaluate(
            context(
                now = now,
                glucose = defaultGlucose(now),
                forecasts = defaultForecasts(now),
                telemetry = mapOf(
                    "steps_count" to 800.0,
                    "activity_ratio" to 1.00
                )
            )
        )

        val activation = rule.evaluate(
            context(
                now = now + 5 * 60_000,
                glucose = defaultGlucose(now + 5 * 60_000),
                forecasts = defaultForecasts(now + 5 * 60_000),
                telemetry = mapOf(
                    "steps_count" to 960.0,
                    "activity_ratio" to 1.38
                )
            )
        )
        assertThat(activation.state).isEqualTo(RuleState.TRIGGERED)
        assertThat(activation.reasons).contains("activity_protection_active")

        var steps = 960.0
        var recoveryDecision = activation
        for (i in 1..6) {
            steps += 4.0
            val ts = now + (1L + i) * 5L * 60_000L
            recoveryDecision = rule.evaluate(
                context(
                    now = ts,
                    glucose = defaultGlucose(ts),
                    forecasts = defaultForecasts(ts),
                    telemetry = mapOf(
                        "steps_count" to steps,
                        "activity_ratio" to 1.01
                    ),
                    activeTempTargetMmol = 8.2
                )
            )
        }

        assertThat(recoveryDecision.state).isEqualTo(RuleState.TRIGGERED)
        assertThat(recoveryDecision.reasons).contains("activity_recovery_to_base")
        assertThat(recoveryDecision.actionProposal).isNotNull()
        assertThat(recoveryDecision.actionProposal!!.targetMmol).isWithin(0.01).of(5.5)
    }

    @Test
    fun safetyForceHigh_usesAdaptiveMaxBoundFromContext() {
        val rule = AdaptiveTargetControllerRule()
        val now = System.currentTimeMillis()
        val decision = rule.evaluate(
            context(
                now = now,
                glucose = listOf(
                    GlucosePoint(now - 5 * 60_000, 5.6, "test", DataQuality.OK),
                    GlucosePoint(now, 5.5, "test", DataQuality.OK)
                ),
                forecasts = listOf(
                    Forecast(now + 5 * 60_000, 5, 5.0, 3.8, 6.2, "test"),
                    Forecast(now + 30 * 60_000, 30, 5.0, 4.4, 5.8, "test"),
                    Forecast(now + 60 * 60_000, 60, 5.0, 4.5, 5.7, "test")
                ),
                adaptiveMaxTargetMmol = 10.0
            )
        )

        assertThat(decision.state).isEqualTo(RuleState.TRIGGERED)
        assertThat(decision.actionProposal).isNotNull()
        assertThat(decision.actionProposal!!.targetMmol).isEqualTo(10.0)
        assertThat(decision.reasons).contains("reason=safety_force_high")
        assertThat(decision.reasons.any { it.startsWith("targetMax=") }).isTrue()
    }

    private fun defaultGlucose(now: Long): List<GlucosePoint> = listOf(
        GlucosePoint(now - 5 * 60_000, 5.5, "test", DataQuality.OK),
        GlucosePoint(now, 5.5, "test", DataQuality.OK)
    )

    private fun defaultForecasts(now: Long): List<Forecast> = listOf(
        Forecast(now + 5 * 60_000, 5, 5.6, 5.2, 6.0, "test"),
        Forecast(now + 30 * 60_000, 30, 5.7, 5.1, 6.2, "test"),
        Forecast(now + 60 * 60_000, 60, 5.8, 5.0, 6.4, "test")
    )

    private fun context(
        now: Long,
        glucose: List<GlucosePoint>,
        forecasts: List<Forecast>,
        telemetry: Map<String, Double?> = emptyMap(),
        baseTarget: Double = 5.5,
        activeTempTargetMmol: Double? = null,
        adaptiveMinTargetMmol: Double = 4.0,
        adaptiveMaxTargetMmol: Double = 9.0
    ): RuleContext = RuleContext(
        nowTs = now,
        glucose = glucose,
        therapyEvents = emptyList(),
        forecasts = forecasts,
        currentDayPattern = null,
        baseTargetMmol = baseTarget,
        dataFresh = true,
        activeTempTargetMmol = activeTempTargetMmol,
        actionsLast6h = 0,
        sensorBlocked = false,
        latestTelemetry = telemetry,
        adaptiveMaxStepMmol = 0.25,
        adaptiveMinTargetMmol = adaptiveMinTargetMmol,
        adaptiveMaxTargetMmol = adaptiveMaxTargetMmol
    )
}
