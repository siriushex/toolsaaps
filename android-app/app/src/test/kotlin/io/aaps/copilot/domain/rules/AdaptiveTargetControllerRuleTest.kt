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
    fun looseTelemetryIobCannotAuthorizeLoweringWithoutCycleSafetyIob() {
        val rule = AdaptiveTargetControllerRule()
        val now = System.currentTimeMillis()
        val highForecasts = listOf(
            Forecast(now + 5 * 60_000, 5, 8.0, 7.6, 8.4, "test"),
            Forecast(now + 30 * 60_000, 30, 9.0, 8.4, 9.6, "test"),
            Forecast(now + 60 * 60_000, 60, 10.0, 9.2, 10.8, "test")
        )
        val glucose = listOf(
            GlucosePoint(now - 5 * 60_000, 6.8, "test", DataQuality.OK),
            GlucosePoint(now, 7.2, "test", DataQuality.OK)
        )

        val blocked = rule.evaluate(
            context(
                now = now,
                glucose = glucose,
                forecasts = highForecasts,
                telemetry = mapOf("iob_effective_units" to 0.0),
                safetyIobUnits = null
            )
        )
        val allowed = AdaptiveTargetControllerRule().evaluate(
            context(
                now = now,
                glucose = glucose,
                forecasts = highForecasts,
                telemetry = mapOf("iob_effective_units" to 9.0),
                safetyIobUnits = 0.0
            )
        )

        assertThat(blocked.actionProposal?.targetMmol ?: 5.5).isAtLeast(5.5)
        assertThat(blocked.reasons.any { it.contains("safety_iob") }).isTrue()
        assertThat(allowed.actionProposal?.targetMmol).isLessThan(5.5)
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
    fun significantCobDoesNotForceManualBaseToLegacy4_2() {
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
        assertThat(decision.actionProposal!!.targetMmol).isGreaterThan(4.2)
        val tbValue = decision.reasons
            .firstOrNull { it.startsWith("Tb=") }
            ?.substringAfter("=")
            ?.replace(",", ".")
            ?.toDoubleOrNull()
        assertThat(tbValue).isNotNull()
        assertThat(tbValue!!).isWithin(0.01).of(5.5)
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

    @Test
    fun raisesRoutineFourPointZeroTargetWhenFastRiseIsNotConfirmed() {
        val rule = AdaptiveTargetControllerRule()
        val now = System.currentTimeMillis()
        val decision = rule.evaluate(
            context(
                now = now,
                glucose = listOf(
                    GlucosePoint(now - 5 * 60_000, 5.7, "test", DataQuality.OK),
                    GlucosePoint(now, 5.8, "test", DataQuality.OK)
                ),
                forecasts = listOf(
                    Forecast(now + 5 * 60_000, 5, 9.2, 8.6, 9.8, "test"),
                    Forecast(now + 30 * 60_000, 30, 9.0, 8.1, 9.9, "test"),
                    Forecast(now + 60 * 60_000, 60, 8.2, 7.4, 9.0, "test")
                ),
                activeTempTargetMmol = 4.0
            )
        )

        assertThat(decision.state).isEqualTo(RuleState.TRIGGERED)
        assertThat(decision.actionProposal?.targetMmol).isEqualTo(4.5)
        assertThat(decision.reasons.any { it.startsWith("aggressiveRiseEligible=0") }).isTrue()
    }

    @Test
    fun rapidFarTermFallRequiresTwoDistinctGlucoseSamplesBeforeProtectiveRaise() {
        val rule = AdaptiveTargetControllerRule()
        val now = System.currentTimeMillis()
        val firstContext = context(
            now = now,
            glucose = listOf(
                GlucosePoint(now - 5 * 60_000, 7.62, "test", DataQuality.OK),
                GlucosePoint(now, 7.20, "test", DataQuality.OK)
            ),
            forecasts = listOf(
                Forecast(now + 5 * 60_000, 5, 6.90, 6.31, 7.49, "test"),
                Forecast(now + 30 * 60_000, 30, 6.32, 4.42, 8.22, "test"),
                Forecast(now + 60 * 60_000, 60, 5.95, 3.12, 8.78, "test")
            ),
            activeTempTargetMmol = 4.77,
            adaptiveMaxTargetMmol = 10.0
        )

        val first = rule.evaluate(firstContext)
        val repeatedSameSample = rule.evaluate(firstContext)

        assertThat(first.reasons).contains("reason=control_pi")
        assertThat(first.reasons.any { it.startsWith("rapidFallFarTermLowCandidate=1") }).isTrue()
        assertThat(first.reasons.any { it.startsWith("rapidFallFarTermLowConfirmed=0") }).isTrue()
        assertThat(repeatedSameSample.reasons).doesNotContain("reason=hypo_preemptive_guard")

        val secondNow = now + 5 * 60_000
        val confirmed = rule.evaluate(
            context(
                now = secondNow,
                glucose = listOf(
                    GlucosePoint(now, 7.20, "test", DataQuality.OK),
                    GlucosePoint(secondNow, 6.78, "test", DataQuality.OK)
                ),
                forecasts = listOf(
                    Forecast(secondNow + 5 * 60_000, 5, 6.48, 5.90, 7.06, "test"),
                    Forecast(secondNow + 30 * 60_000, 30, 5.90, 4.42, 7.38, "test"),
                    Forecast(secondNow + 60 * 60_000, 60, 5.53, 3.12, 7.94, "test")
                ),
                activeTempTargetMmol = 4.77,
                adaptiveMaxTargetMmol = 10.0
            )
        )

        assertThat(confirmed.reasons).contains("reason=hypo_preemptive_guard")
        assertThat(confirmed.reasons.any { it.startsWith("rapidFallFarTermLowConfirmed=1") }).isTrue()
        assertThat(confirmed.actionProposal?.targetMmol).isAtLeast(7.0)
    }

    @Test
    fun sensorBlockBetweenCandidatesBreaksRapidFallConfirmation() {
        val rule = AdaptiveTargetControllerRule()
        val now = System.currentTimeMillis()

        rule.evaluate(rapidFallContext(now = now, previousTs = now - 5 * 60_000, previous = 7.62, current = 7.20))
        rule.evaluate(
            rapidFallContext(
                now = now + 60_000,
                previousTs = now,
                previous = 7.20,
                current = 7.10,
                sensorBlocked = true
            )
        )
        val afterBlock = rule.evaluate(
            rapidFallContext(
                now = now + 5 * 60_000,
                previousTs = now,
                previous = 7.20,
                current = 6.78
            )
        )

        assertThat(afterBlock.reasons).contains("reason=control_pi")
        assertThat(afterBlock.reasons.any { it.startsWith("rapidFallFarTermLowCandidate=1") }).isTrue()
        assertThat(afterBlock.reasons.any { it.startsWith("rapidFallFarTermLowConfirmed=0") }).isTrue()
    }

    @Test
    fun rapidFallCandidateOlderThanSixMinutesCannotConfirmNewCandidate() {
        val rule = AdaptiveTargetControllerRule()
        val now = System.currentTimeMillis()

        rule.evaluate(rapidFallContext(now = now, previousTs = now - 5 * 60_000, previous = 7.62, current = 7.20))
        val afterGap = rule.evaluate(
            rapidFallContext(
                now = now + 7 * 60_000,
                previousTs = now,
                previous = 7.20,
                current = 6.61
            )
        )

        assertThat(afterGap.reasons).doesNotContain("reason=hypo_preemptive_guard")
        assertThat(afterGap.reasons.any { it.startsWith("rapidFallFarTermLowConfirmed=0") }).isTrue()
    }

    @Test
    fun uamRuntimeExplicitZeroOverridesAllStaleLegacyFlags() {
        val active = AdaptiveTargetControllerRule.resolveUamActiveStatic(
            mapOf(
                "uam_runtime_control_flag" to 0.0,
                "uam_runtime_flag" to 0.0,
                "uam_active" to 1.0,
                "uam_value" to 1.0,
                "uam_calculated_flag" to 1.0,
                "uam_detected" to 1.0,
                "some_uam_alias" to 1.0
            )
        )

        assertThat(active).isFalse()
    }

    @Test
    fun uamRuntimeControlFlagIsPreferredAndRuntimeForecastFlagIsFallback() {
        assertThat(
            AdaptiveTargetControllerRule.resolveUamActiveStatic(
                mapOf("uam_runtime_control_flag" to 1.0, "uam_runtime_flag" to 0.0)
            )
        ).isTrue()
        assertThat(
            AdaptiveTargetControllerRule.resolveUamActiveStatic(
                mapOf("uam_runtime_flag" to 1.0, "uam_calculated_flag" to 0.0)
            )
        ).isTrue()
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

    private fun rapidFallContext(
        now: Long,
        previousTs: Long,
        previous: Double,
        current: Double,
        sensorBlocked: Boolean = false
    ): RuleContext = context(
        now = now,
        glucose = listOf(
            GlucosePoint(previousTs, previous, "test", DataQuality.OK),
            GlucosePoint(now, current, "test", DataQuality.OK)
        ),
        forecasts = listOf(
            Forecast(now + 5 * 60_000, 5, current - 0.30, current - 0.89, current + 0.29, "test"),
            Forecast(now + 30 * 60_000, 30, current - 0.88, 4.42, current + 1.02, "test"),
            Forecast(now + 60 * 60_000, 60, current - 1.25, 3.12, current + 1.58, "test")
        ),
        activeTempTargetMmol = 4.77,
        adaptiveMaxTargetMmol = 10.0,
        sensorBlocked = sensorBlocked
    )

    private fun context(
        now: Long,
        glucose: List<GlucosePoint>,
        forecasts: List<Forecast>,
        telemetry: Map<String, Double?> = emptyMap(),
        baseTarget: Double = 5.5,
        activeTempTargetMmol: Double? = null,
        adaptiveMinTargetMmol: Double = 4.0,
        adaptiveMaxTargetMmol: Double = 9.0,
        sensorBlocked: Boolean = false,
        safetyIobUnits: Double? = 0.0
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
        sensorBlocked = sensorBlocked,
        safetyIobUnits = safetyIobUnits,
        latestTelemetry = telemetry,
        adaptiveMaxStepMmol = 0.25,
        adaptiveMinTargetMmol = adaptiveMinTargetMmol,
        adaptiveMaxTargetMmol = adaptiveMaxTargetMmol
    )
}
