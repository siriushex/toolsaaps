package io.aaps.copilot.data.repository

import com.google.gson.Gson
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.SensorLagCorrectionMode
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.ActionProposal
import io.aaps.copilot.domain.model.CircadianDayType
import io.aaps.copilot.domain.model.CircadianForecastPrior
import io.aaps.copilot.domain.model.CircadianReplayBucketStatus
import io.aaps.copilot.domain.model.RuleDecision
import io.aaps.copilot.domain.model.RuleState
import io.aaps.copilot.domain.model.SensorLagAgeSource
import io.aaps.copilot.domain.model.SensorLagEstimate
import io.aaps.copilot.domain.isfcr.IsfCrRealtimeSnapshot
import io.aaps.copilot.domain.isfcr.IsfCrRuntimeMode
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import org.junit.Test

class AutomationRepositoryForecastBiasTest {

    private val gson = Gson()

    @Test
    fun cobBias_raisesForecasts() {
        val source = sampleForecasts()
        val adjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = source,
            cobGrams = 40.0,
            iobUnits = 0.0
        )

        assertThat(adjusted[0].valueMmol).isGreaterThan(source[0].valueMmol)
        assertThat(adjusted[1].valueMmol).isGreaterThan(source[1].valueMmol)
        assertThat(adjusted[2].valueMmol).isGreaterThan(source[2].valueMmol)
        assertThat(adjusted.all { it.modelVersion.contains("cob_iob_bias_v1") }).isTrue()
    }

    @Test
    fun externalCobAdjustment_subtractsSyntheticUamResidual() {
        val adjusted = AutomationRepository.adjustExternalCobForSyntheticUamStatic(
            externalCobGrams = 28.0,
            syntheticUamCobGrams = 9.5,
            carbMaxGrams = 60.0
        )

        assertThat(adjusted).isWithin(0.001).of(18.5)
    }

    @Test
    fun externalCobAdjustment_neverDropsBelowZero() {
        val adjusted = AutomationRepository.adjustExternalCobForSyntheticUamStatic(
            externalCobGrams = 6.0,
            syntheticUamCobGrams = 12.0,
            carbMaxGrams = 60.0
        )

        assertThat(adjusted).isWithin(0.001).of(0.0)
    }

    @Test
    fun iobBias_lowersForecasts() {
        val source = sampleForecasts()
        val adjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = source,
            cobGrams = 0.0,
            iobUnits = 3.0
        )

        assertThat(adjusted[0].valueMmol).isLessThan(source[0].valueMmol)
        assertThat(adjusted[1].valueMmol).isLessThan(source[1].valueMmol)
        assertThat(adjusted[2].valueMmol).isLessThan(source[2].valueMmol)
    }

    @Test
    fun circadianPrior_biasesForecastsAndMarksModelVersion() {
        val source = sampleForecasts()
        val adjusted = AutomationRepository.applyCircadianPatternForecastBiasStatic(
            forecasts = source,
            prior = sampleCircadianPrior(
                delta15 = 0.6,
                delta30 = 1.4,
                delta60 = 2.1,
                confidence = 0.9,
                acuteAttenuation = 1.0,
                staleBlocked = false
            ),
            latestGlucoseMmol = 6.0,
            weight30 = 0.25,
            weight60 = 0.35
        )

        assertThat(adjusted.first { it.horizonMinutes == 30 }.valueMmol)
            .isGreaterThan(source.first { it.horizonMinutes == 30 }.valueMmol)
        assertThat(adjusted.first { it.horizonMinutes == 60 }.valueMmol)
            .isGreaterThan(source.first { it.horizonMinutes == 60 }.valueMmol)
        assertThat(adjusted.all { it.modelVersion.contains("circadian_v2") }).isTrue()
    }

    @Test
    fun circadianPrior_acuteAttenuationReducesBiasMagnitude() {
        val source = sampleForecasts()
        val calm = AutomationRepository.applyCircadianPatternForecastBiasStatic(
            forecasts = source,
            prior = sampleCircadianPrior(
                delta15 = 0.3,
                delta30 = 1.0,
                delta60 = 1.8,
                confidence = 1.0,
                acuteAttenuation = 1.0,
                staleBlocked = false
            ),
            latestGlucoseMmol = 6.0,
            weight30 = 0.25,
            weight60 = 0.35
        )
        val acute = AutomationRepository.applyCircadianPatternForecastBiasStatic(
            forecasts = source,
            prior = sampleCircadianPrior(
                delta15 = 0.3,
                delta30 = 1.0,
                delta60 = 1.8,
                confidence = 1.0,
                acuteAttenuation = 0.4,
                staleBlocked = false
            ),
            latestGlucoseMmol = 6.0,
            weight30 = 0.25,
            weight60 = 0.35
        )

        val calm60Shift = calm.first { it.horizonMinutes == 60 }.valueMmol - source.first { it.horizonMinutes == 60 }.valueMmol
        val acute60Shift = acute.first { it.horizonMinutes == 60 }.valueMmol - source.first { it.horizonMinutes == 60 }.valueMmol
        assertThat(acute60Shift).isLessThan(calm60Shift)
        assertThat(acute60Shift).isGreaterThan(0.0)
    }

    @Test
    fun circadianPrior_helpfulLowAcuteBucketsGetStrongerLongHorizonWeight() {
        val prior = sampleCircadianPrior(
            delta15 = 0.3,
            delta30 = 1.0,
            delta60 = 1.8,
            confidence = 1.0,
            acuteAttenuation = 1.0,
            staleBlocked = false
        ).copy(
            replaySampleCount30 = 16,
            replaySampleCount60 = 18,
            replayWinRate30 = 0.62,
            replayWinRate60 = 0.64,
            replayMaeImprovement30 = -0.11,
            replayMaeImprovement60 = -0.18,
            replayBucketStatus30 = CircadianReplayBucketStatus.HELPFUL,
            replayBucketStatus60 = CircadianReplayBucketStatus.HELPFUL
        )
        val boosted30 = AutomationRepository.patternPriorWeightForHorizonStatic(
            horizonMinutes = 30,
            prior = prior,
            weight30 = 0.25,
            weight60 = 0.35
        )
        val boosted60 = AutomationRepository.patternPriorWeightForHorizonStatic(
            horizonMinutes = 60,
            prior = prior,
            weight30 = 0.25,
            weight60 = 0.35
        )
        val acutePrior = prior.copy(acuteAttenuation = 0.4)
        val acute30 = AutomationRepository.patternPriorWeightForHorizonStatic(
            horizonMinutes = 30,
            prior = acutePrior,
            weight30 = 0.25,
            weight60 = 0.35
        )
        val acute60 = AutomationRepository.patternPriorWeightForHorizonStatic(
            horizonMinutes = 60,
            prior = acutePrior,
            weight30 = 0.25,
            weight60 = 0.35
        )

        assertThat(boosted30).isGreaterThan(acute30)
        assertThat(boosted60).isGreaterThan(acute60)
    }

    @Test
    fun circadianPrior_skipsWhenStaleBlocked() {
        val source = sampleForecasts()
        val adjusted = AutomationRepository.applyCircadianPatternForecastBiasStatic(
            forecasts = source,
            prior = sampleCircadianPrior(
                delta15 = 0.8,
                delta30 = 1.6,
                delta60 = 2.4,
                confidence = 0.9,
                acuteAttenuation = 1.0,
                staleBlocked = true
            ),
            latestGlucoseMmol = 6.0,
            weight30 = 0.25,
            weight60 = 0.35
        )

        assertThat(adjusted).isEqualTo(source)
    }

    @Test
    fun circadianPrior_medianReversionPullsLongHorizonsBackTowardSlotMedian() {
        val source = sampleForecasts()
        val withoutReversion = AutomationRepository.applyCircadianPatternForecastBiasStatic(
            forecasts = source,
            prior = sampleCircadianPrior(
                delta15 = 0.2,
                delta30 = 0.4,
                delta60 = 0.6,
                confidence = 0.95,
                acuteAttenuation = 1.0,
                staleBlocked = false
            ),
            latestGlucoseMmol = 7.0,
            weight30 = 0.25,
            weight60 = 0.35
        )
        val withReversion = AutomationRepository.applyCircadianPatternForecastBiasStatic(
            forecasts = source,
            prior = sampleCircadianPrior(
                delta15 = 0.2,
                delta30 = 0.4,
                delta60 = 0.6,
                confidence = 0.95,
                acuteAttenuation = 1.0,
                staleBlocked = false
            ).copy(
                medianReversion30 = -0.12,
                medianReversion60 = -0.28
            ),
            latestGlucoseMmol = 7.0,
            weight30 = 0.25,
            weight60 = 0.35
        )

        assertThat(
            AutomationRepository.priorDeltaForHorizonStatic(
                prior = sampleCircadianPrior(
                    delta15 = 0.2,
                    delta30 = 0.4,
                    delta60 = 0.6,
                    confidence = 0.95,
                    acuteAttenuation = 1.0,
                    staleBlocked = false
                ).copy(
                    medianReversion30 = -0.12,
                    medianReversion60 = -0.28
                ),
                horizonMinutes = 30
            )
        ).isLessThan(
            AutomationRepository.priorDeltaForHorizonStatic(
                prior = sampleCircadianPrior(
                    delta15 = 0.2,
                    delta30 = 0.4,
                    delta60 = 0.6,
                    confidence = 0.95,
                    acuteAttenuation = 1.0,
                    staleBlocked = false
                ),
                horizonMinutes = 30
            )
        )
        assertThat(withReversion.first { it.horizonMinutes == 60 }.valueMmol)
            .isLessThan(withoutReversion.first { it.horizonMinutes == 60 }.valueMmol)
    }

    @Test
    fun circadianTelemetryRows_includeReplayWeightsAndBiases() {
        val nowTs = 1_773_104_160_000L
        val rows = AutomationRepository.buildCircadianPriorTelemetryRowsStatic(
            nowTs = nowTs,
            prior = sampleCircadianPrior(
                delta15 = 0.1,
                delta30 = 0.8,
                delta60 = 1.2,
                confidence = 0.9,
                acuteAttenuation = 0.4,
                staleBlocked = false
            ).copy(
                replayBias30 = 0.12,
                replayBias60 = -0.18,
                stabilityScore = 0.82,
                horizonQuality30 = 0.74,
                horizonQuality60 = 0.68,
                replayBucketStatus30 = CircadianReplayBucketStatus.HELPFUL,
                replayBucketStatus60 = CircadianReplayBucketStatus.HARMFUL
            ),
            weight30 = 0.25,
            weight60 = 0.35
        )

        fun row(key: String): TelemetrySampleEntity = rows.first { it.key == key }

        assertThat(row("pattern_prior_replay_weight_30").valueDouble).isGreaterThan(0.0)
        assertThat(row("pattern_prior_replay_weight_60").valueDouble).isGreaterThan(0.0)
        assertThat(row("pattern_prior_replay_bias_30").valueDouble).isWithin(1e-6).of(0.12)
        assertThat(row("pattern_prior_replay_bias_60").valueDouble).isWithin(1e-6).of(-0.18)
        assertThat(row("pattern_prior_horizon_quality_30").valueDouble).isWithin(1e-6).of(0.74)
        assertThat(row("pattern_prior_horizon_quality_60").valueDouble).isWithin(1e-6).of(0.68)
        assertThat(row("pattern_prior_stability_score").valueDouble).isWithin(1e-6).of(0.82)
        assertThat(row("pattern_prior_replay_status_30").valueText).isEqualTo("HELPFUL")
        assertThat(row("pattern_prior_replay_status_60").valueText).isEqualTo("HARMFUL")
    }

    @Test
    fun cobIobBias_appliesLowRiskDownshiftWhenGlucoseLowAndIobHigh() {
        val source = listOf(
            Forecast(System.currentTimeMillis() + 5 * 60_000L, 5, 7.6, 5.8, 9.2, "test"),
            Forecast(System.currentTimeMillis() + 30 * 60_000L, 30, 8.6, 6.0, 11.2, "test"),
            Forecast(System.currentTimeMillis() + 60 * 60_000L, 60, 9.6, 6.2, 12.8, "test")
        )
        val adjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = source,
            cobGrams = 60.0,
            iobUnits = 4.0,
            latestGlucoseMmol = 3.8,
            uamActive = false
        )

        assertThat(adjusted.first { it.horizonMinutes == 60 }.valueMmol)
            .isLessThan(source.first { it.horizonMinutes == 60 }.valueMmol)
        assertThat(adjusted.first { it.horizonMinutes == 30 }.valueMmol)
            .isLessThan(source.first { it.horizonMinutes == 30 }.valueMmol)
    }

    @Test
    fun cobIobBias_doesNotApplyExtraLowRiskDownshiftWhenUamActive() {
        val source = listOf(
            Forecast(System.currentTimeMillis() + 5 * 60_000L, 5, 7.6, 5.8, 9.2, "test"),
            Forecast(System.currentTimeMillis() + 30 * 60_000L, 30, 8.6, 6.0, 11.2, "test"),
            Forecast(System.currentTimeMillis() + 60 * 60_000L, 60, 9.6, 6.2, 12.8, "test")
        )
        val lowRiskAdjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = source,
            cobGrams = 60.0,
            iobUnits = 4.0,
            latestGlucoseMmol = 3.8,
            uamActive = false
        )
        val uamAdjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = source,
            cobGrams = 60.0,
            iobUnits = 4.0,
            latestGlucoseMmol = 3.8,
            uamActive = true
        )

        val lowRisk60 = lowRiskAdjusted.first { it.horizonMinutes == 60 }.valueMmol
        val uam60 = uamAdjusted.first { it.horizonMinutes == 60 }.valueMmol
        assertThat(lowRisk60).isLessThan(uam60)
    }

    @Test
    fun ciAndValueStayWithinPhysiologicBounds() {
        val source = listOf(
            Forecast(
                ts = System.currentTimeMillis() + 60 * 60_000L,
                horizonMinutes = 60,
                valueMmol = 21.8,
                ciLow = 20.8,
                ciHigh = 22.0,
                modelVersion = "test"
            )
        )

        val adjusted = AutomationRepository.applyCobIobForecastBiasStatic(
            forecasts = source,
            cobGrams = 200.0,
            iobUnits = 0.0
        )

        assertThat(adjusted.single().valueMmol).isAtMost(22.0)
        assertThat(adjusted.single().ciLow).isAtLeast(2.2)
        assertThat(adjusted.single().ciHigh).isAtMost(22.0)
        assertThat(adjusted.single().ciLow).isAtMost(adjusted.single().valueMmol)
        assertThat(adjusted.single().ciHigh).isAtLeast(adjusted.single().valueMmol)
    }

    @Test
    fun contextBias_highActivityAndLowPatternLowersForecast() {
        val source = sampleForecasts()
        val pattern = io.aaps.copilot.domain.model.PatternWindow(
            dayType = DayType.WEEKDAY,
            hour = 9,
            sampleCount = 100,
            activeDays = 30,
            lowRate = 0.36,
            highRate = 0.10,
            recommendedTargetMmol = 6.1,
            isRiskWindow = true
        )
        val adjusted = AutomationRepository.applyContextFactorForecastBiasStatic(
            forecasts = source,
            telemetry = mapOf(
                "isf_factor_activity_factor" to 1.30,
                "isf_factor_set_factor" to 1.0,
                "isf_factor_dawn_factor" to 1.0,
                "isf_factor_stress_factor" to 1.0,
                "isf_factor_hormone_factor" to 1.0,
                "isf_factor_steroid_factor" to 1.0,
                "sensor_quality_score" to 0.9,
                "isf_factor_context_ambiguity" to 0.1
            ),
            latestGlucoseMmol = 7.2,
            pattern = pattern
        )

        assertThat(adjusted[2].valueMmol).isLessThan(source[2].valueMmol)
        assertThat(adjusted[2].modelVersion).contains("ctx_bias_v1")
    }

    @Test
    fun contextBias_lowSensorQualityWidensCi() {
        val source = sampleForecasts()
        val adjusted = AutomationRepository.applyContextFactorForecastBiasStatic(
            forecasts = source,
            telemetry = mapOf(
                "sensor_quality_score" to 0.2,
                "isf_factor_context_ambiguity" to 0.8
            ),
            latestGlucoseMmol = 8.0,
            pattern = null
        )
        val src60 = source.first { it.horizonMinutes == 60 }
        val adj60 = adjusted.first { it.horizonMinutes == 60 }
        val srcWidth = src60.ciHigh - src60.ciLow
        val adjWidth = adj60.ciHigh - adj60.ciLow

        assertThat(adjWidth).isGreaterThan(srcWidth)
        assertThat(adj60.modelVersion).contains("ctx_bias_v1")
    }

    @Test
    fun contextBias_doesNotRaiseForecastWhenCurrentGlucoseLow() {
        val source = sampleForecasts()
        val adjusted = AutomationRepository.applyContextFactorForecastBiasStatic(
            forecasts = source,
            telemetry = mapOf(
                "isf_factor_set_factor" to 0.70,
                "isf_factor_dawn_factor" to 0.75,
                "isf_factor_stress_factor" to 0.80,
                "sensor_quality_score" to 0.95,
                "isf_factor_context_ambiguity" to 0.0
            ),
            latestGlucoseMmol = 3.9,
            pattern = null
        )

        assertThat(adjusted[2].valueMmol).isAtMost(source[2].valueMmol)
    }

    @Test
    fun baseAlignment_isSkippedForAdaptiveRule() {
        val skipped = AutomationRepository.shouldSkipBaseAlignmentStatic(
            sourceRuleId = "AdaptiveTargetController.v1",
            actionReason = "adaptive_pi_ci_v2|mode=control_pi"
        )

        assertThat(skipped).isTrue()
    }

    @Test
    fun baseAlignment_isAllowedForNonAdaptiveRules() {
        val skipped = AutomationRepository.shouldSkipBaseAlignmentStatic(
            sourceRuleId = "PatternAdaptiveTargetRule.v1",
            actionReason = "pattern_weekday_high"
        )

        assertThat(skipped).isFalse()
    }

    @Test
    fun runtimeDia_ignoresRawTelemetryWithoutProfileDerivedRefinement() {
        val resolution = AutomationRepository.resolveEffectiveDiaHoursStatic(
            profileDurationHours = 4.67,
            profileBaseOnsetMinutes = 8.0,
            realProfileOnsetMinutes = null,
            realProfileShapeScale = null,
            realProfileConfidence = null,
            realProfileStatus = null,
            realProfileSourceId = null,
            selectedProfileId = "APIDRA"
        )

        assertThat(resolution.profileHours).isWithin(0.001).of(4.67)
        assertThat(resolution.effectiveHours).isWithin(0.001).of(4.67)
        assertThat(resolution.source).isEqualTo("profile_default")
    }

    @Test
    fun runtimeDia_usesRealProfileScaleWhenConfident() {
        val resolution = AutomationRepository.resolveEffectiveDiaHoursStatic(
            profileDurationHours = 5.0,
            profileBaseOnsetMinutes = 10.0,
            realProfileOnsetMinutes = 18.0,
            realProfileShapeScale = 0.82,
            realProfileConfidence = 0.74,
            realProfileStatus = "estimated_daily",
            realProfileSourceId = "NOVORAPID",
            selectedProfileId = "NOVORAPID"
        )

        val rawEstimated = ((5.0 * 60.0 * 0.82) + (18.0 - 10.0)) / 60.0
        val expected = 5.0 + (rawEstimated - 5.0) * 0.74
        assertThat(resolution.rawEstimatedHours).isWithin(0.001).of(rawEstimated)
        assertThat(resolution.effectiveHours).isWithin(0.001).of(expected)
        assertThat(resolution.source).isEqualTo("profile_real_blended")
    }

    @Test
    fun runtimeDia_isHardClampedToFiftyPercentOfProfile() {
        val resolution = AutomationRepository.resolveEffectiveDiaHoursStatic(
            profileDurationHours = 5.0,
            profileBaseOnsetMinutes = 10.0,
            realProfileOnsetMinutes = 95.0,
            realProfileShapeScale = 1.80,
            realProfileConfidence = 0.95,
            realProfileStatus = "estimated_daily",
            realProfileSourceId = "NOVORAPID",
            selectedProfileId = "NOVORAPID"
        )

        assertThat(resolution.rawEstimatedHours).isWithin(0.001).of(7.5)
        assertThat(resolution.effectiveHours).isAtMost(7.5)
        assertThat(resolution.effectiveHours).isAtLeast(2.5)
    }

    @Test
    fun realProfileRecompute_waitsUntilSeventyTwoHoursForStableEstimate() {
        val nowTs = 10L * 24 * 60 * 60 * 1000

        val shouldRecompute = AutomationRepository.shouldRecomputeRealInsulinProfileStatic(
            existingUpdatedTs = nowTs - (48L * 60 * 60 * 1000),
            nowTs = nowTs,
            existingAlgoVersion = "v2",
            existingStatus = "estimated_daily",
            existingSampleCount = 4
        )

        assertThat(shouldRecompute).isFalse()
    }

    @Test
    fun realProfileRecompute_runsAfterSeventyTwoHours() {
        val nowTs = 10L * 24 * 60 * 60 * 1000

        val shouldRecompute = AutomationRepository.shouldRecomputeRealInsulinProfileStatic(
            existingUpdatedTs = nowTs - (73L * 60 * 60 * 1000),
            nowTs = nowTs,
            existingAlgoVersion = "v2",
            existingStatus = "estimated_daily",
            existingSampleCount = 4
        )

        assertThat(shouldRecompute).isTrue()
    }

    @Test
    fun adaptiveCooldownBypass_allowsMaterialRetarget() {
        val bypass = AutomationRepository.shouldBypassAdaptiveCooldownStatic(
            decision = RuleDecision(
                ruleId = "AdaptiveTargetController.v1",
                state = RuleState.TRIGGERED,
                reasons = listOf("reason=control_pi"),
                actionProposal = ActionProposal(
                    type = "temp_target",
                    targetMmol = 4.1,
                    durationMinutes = 30,
                    reason = "adaptive_pi_ci_v2|mode=control_pi"
                )
            ),
            activeTempTargetMmol = 5.0
        )

        assertThat(bypass).isTrue()
    }

    @Test
    fun adaptiveCooldownBypass_rejectsSameTargetResend() {
        val bypass = AutomationRepository.shouldBypassAdaptiveCooldownStatic(
            decision = RuleDecision(
                ruleId = "AdaptiveTargetController.v1",
                state = RuleState.TRIGGERED,
                reasons = listOf("reason=control_pi"),
                actionProposal = ActionProposal(
                    type = "temp_target",
                    targetMmol = 4.1,
                    durationMinutes = 30,
                    reason = "adaptive_pi_ci_v2|mode=control_pi"
                )
            ),
            activeTempTargetMmol = 4.1
        )

        assertThat(bypass).isFalse()
    }

    @Test
    fun adaptiveCooldownBypass_allowsSingleStepRetarget() {
        val bypass = AutomationRepository.shouldBypassAdaptiveCooldownStatic(
            decision = RuleDecision(
                ruleId = "AdaptiveTargetController.v1",
                state = RuleState.TRIGGERED,
                reasons = listOf("reason=control_pi"),
                actionProposal = ActionProposal(
                    type = "temp_target",
                    targetMmol = 4.95,
                    durationMinutes = 30,
                    reason = "adaptive_pi_ci_v2|mode=control_pi"
                )
            ),
            activeTempTargetMmol = 5.0
        )

        assertThat(bypass).isTrue()
    }

    @Test
    fun resolveActiveTempTarget_convertsMgdlPayloadToMmol() {
        val now = 1_000_000L
        val resolved = AutomationRepository.resolveActiveTempTargetStatic(
            now = now,
            recentTargets = listOf(
                TherapyEventEntity(
                    id = "tt-1",
                    timestamp = now - 5 * 60_000L,
                    type = "temp_target",
                    payloadJson = """
                        {"targetBottom":"74.0","targetTop":"74.0","duration":"30"}
                    """.trimIndent()
                )
            ),
            gson = gson
        )

        assertThat(resolved).isWithin(0.01).of(4.1069)
    }

    @Test
    fun resolveActiveTempTarget_prefersExplicitMmolOverMgdlFields() {
        val now = 2_000_000L
        val resolved = AutomationRepository.resolveActiveTempTargetStatic(
            now = now,
            recentTargets = listOf(
                TherapyEventEntity(
                    id = "tt-2",
                    timestamp = now - 2 * 60_000L,
                    type = "temp_target",
                    payloadJson = """
                        {"targetBottom":"74.0","targetTop":"74.0","targetBottomMmol":"4.20","targetTopMmol":"4.20","duration":"30"}
                    """.trimIndent()
                )
            ),
            gson = gson
        )

        assertThat(resolved).isWithin(0.0001).of(4.20)
    }

    @Test
    fun resolveActiveTempTarget_returnsNullWhenLatestTempTargetExpired() {
        val now = 3_000_000L
        val resolved = AutomationRepository.resolveActiveTempTargetStatic(
            now = now,
            recentTargets = listOf(
                TherapyEventEntity(
                    id = "tt-old",
                    timestamp = now - 40 * 60_000L,
                    type = "temp_target",
                    payloadJson = """
                        {"targetBottom":"74.0","targetTop":"74.0","duration":"30"}
                    """.trimIndent()
                ),
                TherapyEventEntity(
                    id = "tt-new-expired",
                    timestamp = now - 10 * 60_000L,
                    type = "temp_target",
                    payloadJson = """
                        {"targetBottom":"85.0","targetTop":"85.0","duration":"5"}
                    """.trimIndent()
                )
            ),
            gson = gson
        )

        assertThat(resolved).isNull()
    }

    @Test
    fun calibrationBias_raisesWhenHistoryUnderpredicts() {
        val source = sampleForecasts()
        val history = buildList {
            repeat(40) { idx ->
                add(
                    AutomationRepository.ForecastCalibrationPoint(
                        horizonMinutes = 60,
                        errorMmol = 1.0, // actual > pred
                        ageMs = (idx + 1) * 5 * 60_000L
                    )
                )
            }
        }

        val adjusted = AutomationRepository.applyRecentForecastCalibrationBiasStatic(
            forecasts = source,
            history = history
        )

        val src60 = source.first { it.horizonMinutes == 60 }
        val adj60 = adjusted.first { it.horizonMinutes == 60 }
        assertThat(adj60.valueMmol).isGreaterThan(src60.valueMmol)
        assertThat(adj60.modelVersion).contains("calib_v1")
    }

    @Test
    fun calibrationBias_lowersWhenHistoryOverpredicts_withClamp() {
        val source = sampleForecasts()
        val history = buildList {
            repeat(40) { idx ->
                add(
                    AutomationRepository.ForecastCalibrationPoint(
                        horizonMinutes = 30,
                        errorMmol = -2.0, // actual < pred
                        ageMs = (idx + 1) * 5 * 60_000L
                    )
                )
            }
        }

        val adjusted = AutomationRepository.applyRecentForecastCalibrationBiasStatic(
            forecasts = source,
            history = history
        )

        val src30 = source.first { it.horizonMinutes == 30 }
        val adj30 = adjusted.first { it.horizonMinutes == 30 }
        assertThat(adj30.valueMmol).isLessThan(src30.valueMmol)
        assertThat(src30.valueMmol - adj30.valueMmol).isAtMost(0.46)
    }

    @Test
    fun calibrationBias_notAppliedWhenSamplesInsufficient() {
        val source = sampleForecasts()
        val history = listOf(
            AutomationRepository.ForecastCalibrationPoint(
                horizonMinutes = 60,
                errorMmol = 1.5,
                ageMs = 10 * 60_000L
            )
        )

        val adjusted = AutomationRepository.applyRecentForecastCalibrationBiasStatic(
            forecasts = source,
            history = history
        )

        assertThat(adjusted).isEqualTo(source)
    }

    @Test
    fun calibrationBias_aiTuningIncreasesPositiveCorrectionFor60m() {
        val source = sampleForecasts()
        val history = buildList {
            repeat(40) { idx ->
                add(
                    AutomationRepository.ForecastCalibrationPoint(
                        horizonMinutes = 60,
                        errorMmol = 1.0,
                        ageMs = (idx + 1) * 5 * 60_000L
                    )
                )
            }
        }

        val baseline = AutomationRepository.applyRecentForecastCalibrationBiasStatic(
            forecasts = source,
            history = history
        )
        val tuned = AutomationRepository.applyRecentForecastCalibrationBiasStatic(
            forecasts = source,
            history = history,
            aiTuning = mapOf(
                60 to AutomationRepository.CalibrationAiTuning(
                    gainScale = 1.35,
                    maxUpScale = 1.25,
                    maxDownScale = 1.0
                )
            )
        )

        val src60 = source.first { it.horizonMinutes == 60 }.valueMmol
        val baseline60 = baseline.first { it.horizonMinutes == 60 }.valueMmol
        val tuned60 = tuned.first { it.horizonMinutes == 60 }.valueMmol
        assertThat(baseline60).isGreaterThan(src60)
        assertThat(tuned60).isGreaterThan(baseline60)
    }

    @Test
    fun calibrationBias_expandsCiWhenRecentResidualsAreWide() {
        val source = sampleForecasts()
        val history = buildList {
            repeat(36) { idx ->
                add(
                    AutomationRepository.ForecastCalibrationPoint(
                        horizonMinutes = 60,
                        errorMmol = if (idx % 2 == 0) 2.2 else -2.0,
                        ageMs = (idx + 1) * 5 * 60_000L,
                        predictedMmol = 8.6
                    )
                )
            }
        }

        val adjusted = AutomationRepository.applyRecentForecastCalibrationBiasStatic(
            forecasts = source,
            history = history
        )

        val src60 = source.first { it.horizonMinutes == 60 }
        val adj60 = adjusted.first { it.horizonMinutes == 60 }
        val srcWidth = src60.ciHigh - src60.ciLow
        val adjWidth = adj60.ciHigh - adj60.ciLow

        assertThat(adjWidth).isGreaterThan(srcWidth)
        assertThat(adj60.modelVersion).contains("ci_calib_v1")
    }

    @Test
    fun calibrationBias_canModestlyShrinkCiWhenResidualsAreTight() {
        val source = sampleForecasts()
        val history = buildList {
            repeat(36) { idx ->
                add(
                    AutomationRepository.ForecastCalibrationPoint(
                        horizonMinutes = 30,
                        errorMmol = if (idx % 2 == 0) 0.08 else -0.10,
                        ageMs = (idx + 1) * 5 * 60_000L,
                        predictedMmol = 6.3
                    )
                )
            }
        }

        val adjusted = AutomationRepository.applyRecentForecastCalibrationBiasStatic(
            forecasts = source,
            history = history
        )

        val src30 = source.first { it.horizonMinutes == 30 }
        val adj30 = adjusted.first { it.horizonMinutes == 30 }
        val srcWidth = src30.ciHigh - src30.ciLow
        val adjWidth = adj30.ciHigh - adj30.ciLow

        assertThat(adjWidth).isLessThan(srcWidth)
        assertThat(adjWidth).isAtLeast(srcWidth * 0.80)
        assertThat(adj30.modelVersion).contains("ci_calib_v1")
    }

    @Test
    fun resolveAiCalibrationTuning_blocksStaleOptimizerPayload() {
        val nowTs = System.currentTimeMillis()
        val staleTelemetry = mapOf(
            "daily_report_ai_opt_apply_flag" to 1.0,
            "daily_report_ai_opt_confidence" to 0.80,
            "daily_report_ai_opt_generated_ts" to (nowTs - 48L * 60 * 60 * 1000).toDouble(),
            "daily_report_matched_samples" to 200.0,
            "daily_report_isfcr_quality_risk_level" to 1.0,
            "daily_report_ai_opt_gain_scale_60m" to 1.25,
            "daily_report_ai_opt_max_up_scale_60m" to 1.20,
            "daily_report_ai_opt_max_down_scale_60m" to 1.0
        )

        val tuning = AutomationRepository.resolveAiCalibrationTuningStatic(
            latestTelemetry = staleTelemetry,
            nowTs = nowTs
        )

        assertThat(tuning).isEmpty()
    }

    @Test
    fun resolveAiCalibrationTuning_blocksWhenIsfCrRiskHigh() {
        val nowTs = System.currentTimeMillis()
        val highRiskTelemetry = mapOf(
            "daily_report_ai_opt_apply_flag" to 1.0,
            "daily_report_ai_opt_confidence" to 0.80,
            "daily_report_ai_opt_generated_ts" to (nowTs - 30 * 60_000L).toDouble(),
            "daily_report_matched_samples" to 240.0,
            "daily_report_isfcr_quality_risk_level" to 3.0,
            "daily_report_ai_opt_gain_scale_30m" to 1.25,
            "daily_report_ai_opt_max_up_scale_30m" to 1.20,
            "daily_report_ai_opt_max_down_scale_30m" to 0.95
        )

        val tuning = AutomationRepository.resolveAiCalibrationTuningStatic(
            latestTelemetry = highRiskTelemetry,
            nowTs = nowTs
        )

        assertThat(tuning).isEmpty()
    }

    @Test
    fun resolveAiCalibrationTuning_blocksWhenMatchedSamplesTooLow() {
        val nowTs = System.currentTimeMillis()
        val sparseTelemetry = mapOf(
            "daily_report_ai_opt_apply_flag" to 1.0,
            "daily_report_ai_opt_confidence" to 0.80,
            "daily_report_ai_opt_generated_ts" to (nowTs - 30 * 60_000L).toDouble(),
            "daily_report_matched_samples" to 12.0,
            "daily_report_isfcr_quality_risk_level" to 1.0,
            "daily_report_ai_opt_gain_scale_30m" to 1.25,
            "daily_report_ai_opt_max_up_scale_30m" to 1.20,
            "daily_report_ai_opt_max_down_scale_30m" to 0.95
        )

        val tuning = AutomationRepository.resolveAiCalibrationTuningStatic(
            latestTelemetry = sparseTelemetry,
            nowTs = nowTs
        )

        assertThat(tuning).isEmpty()
    }

    @Test
    fun resolveAiCalibrationTuning_returnsClampedValuesWhenFreshAndValid() {
        val nowTs = System.currentTimeMillis()
        val telemetry = mapOf(
            "daily_report_ai_opt_apply_flag" to 1.0,
            "daily_report_ai_opt_confidence" to 0.90,
            "daily_report_ai_opt_generated_ts" to (nowTs - 20 * 60_000L).toDouble(),
            "daily_report_matched_samples" to 220.0,
            "daily_report_isfcr_quality_risk_level" to 1.0,
            "daily_report_ai_opt_gain_scale_5m" to 4.0,
            "daily_report_ai_opt_max_up_scale_5m" to 0.1,
            "daily_report_ai_opt_max_down_scale_5m" to 9.0,
            "daily_report_ai_opt_gain_scale_30m" to 1.2,
            "daily_report_ai_opt_max_up_scale_30m" to 1.3,
            "daily_report_ai_opt_max_down_scale_30m" to 1.1,
            "daily_report_ai_opt_gain_scale_60m" to 1.35,
            "daily_report_ai_opt_max_up_scale_60m" to 1.25,
            "daily_report_ai_opt_max_down_scale_60m" to 1.0
        )

        val tuning = AutomationRepository.resolveAiCalibrationTuningStatic(
            latestTelemetry = telemetry,
            nowTs = nowTs
        )

        assertThat(tuning.keys).containsAtLeast(5, 30, 60)
        assertThat(tuning.getValue(5).gainScale).isAtMost(1.50)
        assertThat(tuning.getValue(5).maxUpScale).isAtLeast(0.80)
        assertThat(tuning.getValue(5).maxDownScale).isAtMost(1.50)
        assertThat(tuning.getValue(60).gainScale).isWithin(1e-9).of(1.35)
    }

    @Test
    fun sensorQuality_detectsSuspectFalseLow() {
        val now = System.currentTimeMillis()
        val glucose = listOf(
            GlucosePoint(now - 10 * 60_000L, 8.8, "test", DataQuality.OK),
            GlucosePoint(now - 5 * 60_000L, 8.6, "test", DataQuality.OK),
            GlucosePoint(now, 3.3, "test", DataQuality.OK)
        )

        val assessment = AutomationRepository.evaluateSensorQualityStatic(
            glucose = glucose,
            nowTs = now,
            staleMaxMinutes = 15
        )

        assertThat(assessment.blocked).isTrue()
        assertThat(assessment.suspectFalseLow).isTrue()
        assertThat(assessment.reason).isEqualTo("suspect_false_low")
    }

    @Test
    fun sensorQuality_stableSeriesRemainsOk() {
        val now = System.currentTimeMillis()
        val glucose = listOf(
            GlucosePoint(now - 20 * 60_000L, 6.1, "test"),
            GlucosePoint(now - 15 * 60_000L, 6.0, "test"),
            GlucosePoint(now - 10 * 60_000L, 6.1, "test"),
            GlucosePoint(now - 5 * 60_000L, 6.0, "test"),
            GlucosePoint(now, 6.1, "test")
        )

        val assessment = AutomationRepository.evaluateSensorQualityStatic(
            glucose = glucose,
            nowTs = now,
            staleMaxMinutes = 15
        )

        assertThat(assessment.blocked).isFalse()
        assertThat(assessment.score).isGreaterThan(0.70)
    }

    @Test
    fun sensorQuality_deduplicatesMixedSourceTimestampsBeforeDeltaGate() {
        val now = System.currentTimeMillis()
        val sameTs = now - 5 * 60_000L
        val glucose = listOf(
            GlucosePoint(now - 10 * 60_000L, 6.2, "nightscout"),
            GlucosePoint(sameTs, 9.5, "local_broadcast"),
            GlucosePoint(sameTs, 6.1, "nightscout"),
            GlucosePoint(now, 6.0, "nightscout")
        )

        val assessment = AutomationRepository.evaluateSensorQualityStatic(
            glucose = glucose,
            nowTs = now,
            staleMaxMinutes = 15
        )

        assertThat(assessment.blocked).isFalse()
        assertThat(assessment.delta5Mmol).isWithin(0.01).of(-0.1)
    }

    @Test
    fun sensorQualityRollback_sentWhenBlockedAndTargetDrifted() {
        val assessment = AutomationRepository.SensorQualityAssessment(
            score = 0.2,
            blocked = true,
            reason = "suspect_false_low",
            suspectFalseLow = true,
            delta5Mmol = -1.8,
            noiseStd5Mmol = 0.9,
            gapMinutes = 1.0
        )

        val shouldRollback = AutomationRepository.shouldSendSensorQualityRollbackStatic(
            activeTempTarget = 8.0,
            baseTargetMmol = 5.5,
            assessment = assessment
        )

        assertThat(shouldRollback).isTrue()
    }

    @Test
    fun sensorQualityRollback_notSentWhenNearBase() {
        val assessment = AutomationRepository.SensorQualityAssessment(
            score = 0.4,
            blocked = true,
            reason = "rapid_delta",
            suspectFalseLow = false,
            delta5Mmol = 1.7,
            noiseStd5Mmol = 0.2,
            gapMinutes = 1.0
        )

        val shouldRollback = AutomationRepository.shouldSendSensorQualityRollbackStatic(
            activeTempTarget = 5.6,
            baseTargetMmol = 5.5,
            assessment = assessment
        )

        assertThat(shouldRollback).isFalse()
    }

    @Test
    fun normalizeForecastSet_keepsSingleRowPerHorizon() {
        val now = System.currentTimeMillis()
        val source = listOf(
            Forecast(now + 5 * 60_000L, 5, 6.0, 5.0, 7.0, "local-hybrid-v3"),
            Forecast(now + 5 * 60_000L, 5, 6.2, 5.6, 6.8, "local-hybrid-v3"),
            Forecast(now + 30 * 60_000L, 30, 6.6, 5.5, 7.7, "local-hybrid-v3"),
            Forecast(now + 30 * 60_000L, 30, 6.5, 5.9, 7.1, "cloud-v1"),
            Forecast(now + 60 * 60_000L, 60, 7.0, 5.8, 8.2, "local-hybrid-v3")
        )

        val normalized = AutomationRepository.normalizeForecastSetStatic(source)

        assertThat(normalized.map { it.horizonMinutes }).containsExactly(5, 30, 60)
        assertThat(normalized.first { it.horizonMinutes == 5 }.valueMmol).isEqualTo(6.2)
        assertThat(normalized.first { it.horizonMinutes == 30 }.modelVersion).contains("cloud")
    }

    @Test
    fun extractForecastDecomposition_usesDiagnosticsComponents() {
        val now = System.currentTimeMillis()
        val diagnostics = buildDiagnostics()
        val localForecasts = listOf(
            Forecast(now + 5 * 60_000L, 5, 6.0, 5.2, 6.8, "local-hybrid-v3"),
            Forecast(now + 30 * 60_000L, 30, 6.4, 5.6, 7.2, "local-hybrid-v3"),
            Forecast(now + 60 * 60_000L, 60, 6.8, 5.9, 7.7, "local-hybrid-v3|insulin=novorapid")
        )

        val snapshot = AutomationRepository.extractForecastDecompositionSnapshotStatic(
            diagnostics = diagnostics,
            localForecasts = localForecasts
        )

        requireNotNull(snapshot)
        assertThat(snapshot.trend60Mmol).isWithin(1e-9).of(1.2)
        assertThat(snapshot.therapy60Mmol).isWithin(1e-9).of(3.0)
        assertThat(snapshot.uam60Mmol).isWithin(1e-9).of(0.6)
        assertThat(snapshot.residualRoc0Mmol5).isWithin(1e-9).of(-0.12)
        assertThat(snapshot.sigmaEMmol5).isWithin(1e-9).of(0.18)
        assertThat(snapshot.kfSigmaGMmol).isWithin(1e-9).of(0.21)
        assertThat(snapshot.modelVersion).isEqualTo("local-hybrid-v3|insulin=novorapid")
    }

    @Test
    fun extractForecastDecomposition_returnsNullWhenNoDiagnostics() {
        val snapshot = AutomationRepository.extractForecastDecompositionSnapshotStatic(
            diagnostics = null,
            localForecasts = sampleForecasts()
        )

        assertThat(snapshot).isNull()
    }

    @Test
    fun isfCrRuntimeGate_blocksWhenSnapshotMissing() {
        val gate = AutomationRepository.resolveIsfCrRuntimeGateStatic(
            snapshot = null,
            confidenceThreshold = 0.55
        )

        assertThat(gate.applyToRuntime).isFalse()
        assertThat(gate.reason).isEqualTo("no_snapshot")
    }

    @Test
    fun isfCrRuntimeGate_blocksInShadowMode() {
        val gate = AutomationRepository.resolveIsfCrRuntimeGateStatic(
            snapshot = sampleIsfCrSnapshot(mode = IsfCrRuntimeMode.SHADOW, confidence = 0.90),
            confidenceThreshold = 0.55
        )

        assertThat(gate.applyToRuntime).isFalse()
        assertThat(gate.reason).isEqualTo("shadow_mode")
    }

    @Test
    fun isfCrRuntimeGate_blocksInFallbackMode() {
        val gate = AutomationRepository.resolveIsfCrRuntimeGateStatic(
            snapshot = sampleIsfCrSnapshot(mode = IsfCrRuntimeMode.FALLBACK, confidence = 0.90),
            confidenceThreshold = 0.55
        )

        assertThat(gate.applyToRuntime).isFalse()
        assertThat(gate.reason).isEqualTo("fallback_mode")
    }

    @Test
    fun isfCrRuntimeGate_blocksWhenConfidenceBelowThreshold() {
        val gate = AutomationRepository.resolveIsfCrRuntimeGateStatic(
            snapshot = sampleIsfCrSnapshot(mode = IsfCrRuntimeMode.ACTIVE, confidence = 0.49),
            confidenceThreshold = 0.55
        )

        assertThat(gate.applyToRuntime).isFalse()
        assertThat(gate.reason).isEqualTo("low_confidence")
    }

    @Test
    fun isfCrRuntimeGate_allowsWhenActiveAndConfident() {
        val gate = AutomationRepository.resolveIsfCrRuntimeGateStatic(
            snapshot = sampleIsfCrSnapshot(mode = IsfCrRuntimeMode.ACTIVE, confidence = 0.72),
            confidenceThreshold = 0.55
        )

        assertThat(gate.applyToRuntime).isTrue()
        assertThat(gate.reason).isEqualTo("active_confident")
    }

    @Test
    fun isfCrOverrideBlendWeight_isFullWhenActiveAndApplied() {
        val snapshot = sampleIsfCrSnapshot(mode = IsfCrRuntimeMode.ACTIVE, confidence = 0.80)
        val gate = AutomationRepository.resolveIsfCrRuntimeGateStatic(snapshot = snapshot, confidenceThreshold = 0.55)

        val weight = AutomationRepository.resolveIsfCrOverrideBlendWeightStatic(
            snapshot = snapshot,
            runtimeGate = gate,
            confidenceThreshold = 0.55
        )

        assertThat(weight).isEqualTo(1.0)
    }

    @Test
    fun isfCrOverrideBlendWeight_usesSoftBlendInShadow() {
        val snapshot = sampleIsfCrSnapshot(mode = IsfCrRuntimeMode.SHADOW, confidence = 0.90)
        val gate = AutomationRepository.resolveIsfCrRuntimeGateStatic(snapshot = snapshot, confidenceThreshold = 0.55)

        val weight = AutomationRepository.resolveIsfCrOverrideBlendWeightStatic(
            snapshot = snapshot,
            runtimeGate = gate,
            confidenceThreshold = 0.55
        )

        requireNotNull(weight)
        assertThat(weight).isGreaterThan(0.24)
        assertThat(weight).isAtMost(0.65)
    }

    @Test
    fun isfCrOverrideBlendWeight_returnsNullWhenConfidenceLow() {
        val snapshot = sampleIsfCrSnapshot(mode = IsfCrRuntimeMode.SHADOW, confidence = 0.50)
        val gate = AutomationRepository.resolveIsfCrRuntimeGateStatic(snapshot = snapshot, confidenceThreshold = 0.55)

        val weight = AutomationRepository.resolveIsfCrOverrideBlendWeightStatic(
            snapshot = snapshot,
            runtimeGate = gate,
            confidenceThreshold = 0.55
        )

        assertThat(weight).isNull()
    }

    @Test
    fun isfCrShadowActivation_blocksWhenSamplesInsufficient() {
        val assessment = AutomationRepository.evaluateIsfCrShadowActivationStatic(
            samples = listOf(
                AutomationRepository.IsfCrShadowDiffSample(0.8, 10.0, 10.0),
                AutomationRepository.IsfCrShadowDiffSample(0.9, 8.0, 7.0)
            ),
            minSamples = 12,
            minMeanConfidence = 0.65,
            maxMeanAbsIsfDeltaPct = 25.0,
            maxMeanAbsCrDeltaPct = 25.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("insufficient_samples")
    }

    @Test
    fun isfCrShadowActivation_blocksWhenConfidenceLow() {
        val samples = List(20) {
            AutomationRepository.IsfCrShadowDiffSample(
                confidence = 0.5,
                isfDeltaPct = 5.0,
                crDeltaPct = 4.0
            )
        }
        val assessment = AutomationRepository.evaluateIsfCrShadowActivationStatic(
            samples = samples,
            minSamples = 12,
            minMeanConfidence = 0.65,
            maxMeanAbsIsfDeltaPct = 25.0,
            maxMeanAbsCrDeltaPct = 25.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("low_mean_confidence")
    }

    @Test
    fun isfCrShadowActivation_blocksWhenDeltaTooHigh() {
        val samples = List(30) {
            AutomationRepository.IsfCrShadowDiffSample(
                confidence = 0.9,
                isfDeltaPct = 35.0,
                crDeltaPct = 10.0
            )
        }
        val assessment = AutomationRepository.evaluateIsfCrShadowActivationStatic(
            samples = samples,
            minSamples = 12,
            minMeanConfidence = 0.65,
            maxMeanAbsIsfDeltaPct = 25.0,
            maxMeanAbsCrDeltaPct = 25.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("isf_delta_out_of_bounds")
    }

    @Test
    fun isfCrShadowActivation_allowsWhenKpiPasses() {
        val samples = List(80) { idx ->
            val confidence = if (idx % 2 == 0) 0.76 else 0.82
            AutomationRepository.IsfCrShadowDiffSample(
                confidence = confidence,
                isfDeltaPct = 12.0,
                crDeltaPct = 15.0
            )
        }
        val assessment = AutomationRepository.evaluateIsfCrShadowActivationStatic(
            samples = samples,
            minSamples = 72,
            minMeanConfidence = 0.65,
            maxMeanAbsIsfDeltaPct = 25.0,
            maxMeanAbsCrDeltaPct = 25.0
        )

        assertThat(assessment.eligible).isTrue()
        assertThat(assessment.reason).isEqualTo("eligible")
        assertThat(assessment.sampleCount).isEqualTo(80)
        assertThat(assessment.meanConfidence).isGreaterThan(0.75)
    }

    @Test
    fun isfCrDayTypeGate_blocksWhenSamplesInsufficient() {
        val samples = listOf(
            AutomationRepository.IsfCrDayTypeStabilitySample(
                isfSameDayTypeRatio = 0.8,
                crSameDayTypeRatio = 0.7,
                isfSparseFlag = false,
                crSparseFlag = false
            )
        )

        val assessment = AutomationRepository.evaluateIsfCrDayTypeStabilityStatic(
            samples = samples,
            minSamples = 12,
            minMeanSameDayTypeRatio = 0.3,
            maxSparseRatePct = 75.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("insufficient_day_type_samples")
    }

    @Test
    fun isfCrDayTypeGate_blocksWhenSameDayTypeRatioLow() {
        val samples = List(20) {
            AutomationRepository.IsfCrDayTypeStabilitySample(
                isfSameDayTypeRatio = 0.20,
                crSameDayTypeRatio = 0.65,
                isfSparseFlag = false,
                crSparseFlag = false
            )
        }

        val assessment = AutomationRepository.evaluateIsfCrDayTypeStabilityStatic(
            samples = samples,
            minSamples = 12,
            minMeanSameDayTypeRatio = 0.30,
            maxSparseRatePct = 75.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("isf_day_type_ratio_low")
    }

    @Test
    fun isfCrDayTypeGate_blocksWhenSparseRateTooHigh() {
        val samples = List(30) { idx ->
            AutomationRepository.IsfCrDayTypeStabilitySample(
                isfSameDayTypeRatio = 0.8,
                crSameDayTypeRatio = 0.78,
                isfSparseFlag = idx < 26,
                crSparseFlag = false
            )
        }

        val assessment = AutomationRepository.evaluateIsfCrDayTypeStabilityStatic(
            samples = samples,
            minSamples = 12,
            minMeanSameDayTypeRatio = 0.30,
            maxSparseRatePct = 75.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("isf_day_type_sparse_rate_high")
    }

    @Test
    fun isfCrDayTypeGate_allowsWhenRatiosAndSparseRatesStable() {
        val samples = List(48) {
            AutomationRepository.IsfCrDayTypeStabilitySample(
                isfSameDayTypeRatio = 0.62,
                crSameDayTypeRatio = 0.58,
                isfSparseFlag = false,
                crSparseFlag = false
            )
        }

        val assessment = AutomationRepository.evaluateIsfCrDayTypeStabilityStatic(
            samples = samples,
            minSamples = 24,
            minMeanSameDayTypeRatio = 0.30,
            maxSparseRatePct = 75.0
        )

        assertThat(assessment.eligible).isTrue()
        assertThat(assessment.reason).isEqualTo("eligible")
        assertThat(assessment.meanIsfSameDayTypeRatio).isAtLeast(0.6)
        assertThat(assessment.meanCrSameDayTypeRatio).isAtLeast(0.5)
        assertThat(assessment.isfSparseRatePct).isEqualTo(0.0)
        assertThat(assessment.crSparseRatePct).isEqualTo(0.0)
    }

    @Test
    fun isfCrSensorGate_blocksWhenSamplesInsufficient() {
        val samples = listOf(
            AutomationRepository.IsfCrSensorQualitySample(
                qualityScore = 0.75,
                sensorFactor = 0.95,
                wearConfidencePenalty = 0.05,
                sensorAgeHighFlag = false,
                suspectFalseLowFlag = false
            )
        )

        val assessment = AutomationRepository.evaluateIsfCrSensorQualityStatic(
            samples = samples,
            minSamples = 12,
            minMeanQualityScore = 0.46,
            minMeanSensorFactor = 0.90,
            maxMeanWearPenalty = 0.12,
            maxSensorAgeHighRatePct = 70.0,
            maxSuspectFalseLowRatePct = 35.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("insufficient_sensor_quality_samples")
    }

    @Test
    fun isfCrSensorGate_blocksWhenQualityLow() {
        val samples = List(24) {
            AutomationRepository.IsfCrSensorQualitySample(
                qualityScore = 0.35,
                sensorFactor = 0.94,
                wearConfidencePenalty = 0.04,
                sensorAgeHighFlag = false,
                suspectFalseLowFlag = false
            )
        }

        val assessment = AutomationRepository.evaluateIsfCrSensorQualityStatic(
            samples = samples,
            minSamples = 12,
            minMeanQualityScore = 0.46,
            minMeanSensorFactor = 0.90,
            maxMeanWearPenalty = 0.12,
            maxSensorAgeHighRatePct = 70.0,
            maxSuspectFalseLowRatePct = 35.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("sensor_quality_score_low")
    }

    @Test
    fun isfCrSensorGate_blocksWhenWearPenaltyHigh() {
        val samples = List(24) {
            AutomationRepository.IsfCrSensorQualitySample(
                qualityScore = 0.78,
                sensorFactor = 0.94,
                wearConfidencePenalty = 0.17,
                sensorAgeHighFlag = false,
                suspectFalseLowFlag = false
            )
        }

        val assessment = AutomationRepository.evaluateIsfCrSensorQualityStatic(
            samples = samples,
            minSamples = 12,
            minMeanQualityScore = 0.46,
            minMeanSensorFactor = 0.90,
            maxMeanWearPenalty = 0.12,
            maxSensorAgeHighRatePct = 70.0,
            maxSuspectFalseLowRatePct = 35.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("wear_penalty_high")
    }

    @Test
    fun isfCrSensorGate_allowsWhenMetricsStable() {
        val samples = List(36) { idx ->
            AutomationRepository.IsfCrSensorQualitySample(
                qualityScore = 0.72,
                sensorFactor = 0.95,
                wearConfidencePenalty = 0.06,
                sensorAgeHighFlag = idx < 8,
                suspectFalseLowFlag = idx < 10
            )
        }

        val assessment = AutomationRepository.evaluateIsfCrSensorQualityStatic(
            samples = samples,
            minSamples = 18,
            minMeanQualityScore = 0.46,
            minMeanSensorFactor = 0.90,
            maxMeanWearPenalty = 0.12,
            maxSensorAgeHighRatePct = 70.0,
            maxSuspectFalseLowRatePct = 35.0
        )

        assertThat(assessment.eligible).isTrue()
        assertThat(assessment.reason).isEqualTo("eligible")
        assertThat(assessment.meanQualityScore).isAtLeast(0.7)
        assertThat(assessment.meanSensorFactor).isAtLeast(0.9)
        assertThat(assessment.meanWearPenalty).isAtMost(0.12)
        assertThat(assessment.sensorAgeHighRatePct).isAtMost(70.0)
        assertThat(assessment.suspectFalseLowRatePct).isAtMost(35.0)
    }

    @Test
    fun isfCrSensorGate_blocksWhenSuspectFalseLowRateHigh() {
        val samples = List(36) { idx ->
            AutomationRepository.IsfCrSensorQualitySample(
                qualityScore = 0.75,
                sensorFactor = 0.95,
                wearConfidencePenalty = 0.06,
                sensorAgeHighFlag = false,
                suspectFalseLowFlag = idx < 20
            )
        }

        val assessment = AutomationRepository.evaluateIsfCrSensorQualityStatic(
            samples = samples,
            minSamples = 18,
            minMeanQualityScore = 0.46,
            minMeanSensorFactor = 0.90,
            maxMeanWearPenalty = 0.12,
            maxSensorAgeHighRatePct = 70.0,
            maxSuspectFalseLowRatePct = 35.0
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("sensor_suspect_false_low_rate")
    }

    @Test
    fun isfCrDailyQualityGate_blocksWhenDailyReportMissing() {
        val assessment = AutomationRepository.evaluateIsfCrDailyQualityGateStatic(
            matchedSamples = null,
            mae30Mmol = null,
            mae60Mmol = null,
            hypoRatePct24h = 2.0,
            ciCoverage30Pct = 65.0,
            ciCoverage60Pct = 60.0,
            ciWidth30Mmol = 1.1,
            ciWidth60Mmol = 1.8,
            minDailyMatchedSamples = 120,
            maxDailyMae30Mmol = 0.9,
            maxDailyMae60Mmol = 1.4,
            maxHypoRatePct = 6.0,
            minDailyCiCoverage30Pct = 55.0,
            minDailyCiCoverage60Pct = 55.0,
            maxDailyCiWidth30Mmol = 1.8,
            maxDailyCiWidth60Mmol = 2.6
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("daily_report_missing")
    }

    @Test
    fun isfCrDailyQualityGate_blocksWhenMaeOrHypoOutOfBounds() {
        val highMae = AutomationRepository.evaluateIsfCrDailyQualityGateStatic(
            matchedSamples = 240,
            mae30Mmol = 1.2,
            mae60Mmol = 1.3,
            hypoRatePct24h = 2.0,
            ciCoverage30Pct = 65.0,
            ciCoverage60Pct = 60.0,
            ciWidth30Mmol = 1.1,
            ciWidth60Mmol = 1.8,
            minDailyMatchedSamples = 120,
            maxDailyMae30Mmol = 0.9,
            maxDailyMae60Mmol = 1.4,
            maxHypoRatePct = 6.0,
            minDailyCiCoverage30Pct = 55.0,
            minDailyCiCoverage60Pct = 55.0,
            maxDailyCiWidth30Mmol = 1.8,
            maxDailyCiWidth60Mmol = 2.6
        )
        val highHypo = AutomationRepository.evaluateIsfCrDailyQualityGateStatic(
            matchedSamples = 240,
            mae30Mmol = 0.6,
            mae60Mmol = 1.1,
            hypoRatePct24h = 9.0,
            ciCoverage30Pct = 65.0,
            ciCoverage60Pct = 60.0,
            ciWidth30Mmol = 1.1,
            ciWidth60Mmol = 1.8,
            minDailyMatchedSamples = 120,
            maxDailyMae30Mmol = 0.9,
            maxDailyMae60Mmol = 1.4,
            maxHypoRatePct = 6.0,
            minDailyCiCoverage30Pct = 55.0,
            minDailyCiCoverage60Pct = 55.0,
            maxDailyCiWidth30Mmol = 1.8,
            maxDailyCiWidth60Mmol = 2.6
        )

        assertThat(highMae.eligible).isFalse()
        assertThat(highMae.reason).isEqualTo("daily_mae30_out_of_bounds")
        assertThat(highHypo.eligible).isFalse()
        assertThat(highHypo.reason).isEqualTo("daily_hypo_rate_out_of_bounds")
    }

    @Test
    fun isfCrDailyQualityGate_allowsWhenThresholdsPass() {
        val assessment = AutomationRepository.evaluateIsfCrDailyQualityGateStatic(
            matchedSamples = 260,
            mae30Mmol = 0.58,
            mae60Mmol = 1.08,
            hypoRatePct24h = 3.1,
            ciCoverage30Pct = 66.0,
            ciCoverage60Pct = 58.0,
            ciWidth30Mmol = 1.25,
            ciWidth60Mmol = 1.95,
            minDailyMatchedSamples = 120,
            maxDailyMae30Mmol = 0.9,
            maxDailyMae60Mmol = 1.4,
            maxHypoRatePct = 6.0,
            minDailyCiCoverage30Pct = 55.0,
            minDailyCiCoverage60Pct = 55.0,
            maxDailyCiWidth30Mmol = 1.8,
            maxDailyCiWidth60Mmol = 2.6
        )

        assertThat(assessment.eligible).isTrue()
        assertThat(assessment.reason).isEqualTo("eligible")
        assertThat(assessment.matchedSamples).isEqualTo(260)
    }

    @Test
    fun isfCrDailyQualityGate_blocksWhenCiCoverageOrWidthOutOfBounds() {
        val lowCoverage = AutomationRepository.evaluateIsfCrDailyQualityGateStatic(
            matchedSamples = 260,
            mae30Mmol = 0.58,
            mae60Mmol = 1.08,
            hypoRatePct24h = 3.1,
            ciCoverage30Pct = 49.0,
            ciCoverage60Pct = 58.0,
            ciWidth30Mmol = 1.25,
            ciWidth60Mmol = 1.95,
            minDailyMatchedSamples = 120,
            maxDailyMae30Mmol = 0.9,
            maxDailyMae60Mmol = 1.4,
            maxHypoRatePct = 6.0,
            minDailyCiCoverage30Pct = 55.0,
            minDailyCiCoverage60Pct = 55.0,
            maxDailyCiWidth30Mmol = 1.8,
            maxDailyCiWidth60Mmol = 2.6
        )
        val wideCi = AutomationRepository.evaluateIsfCrDailyQualityGateStatic(
            matchedSamples = 260,
            mae30Mmol = 0.58,
            mae60Mmol = 1.08,
            hypoRatePct24h = 3.1,
            ciCoverage30Pct = 66.0,
            ciCoverage60Pct = 58.0,
            ciWidth30Mmol = 2.4,
            ciWidth60Mmol = 1.95,
            minDailyMatchedSamples = 120,
            maxDailyMae30Mmol = 0.9,
            maxDailyMae60Mmol = 1.4,
            maxHypoRatePct = 6.0,
            minDailyCiCoverage30Pct = 55.0,
            minDailyCiCoverage60Pct = 55.0,
            maxDailyCiWidth30Mmol = 1.8,
            maxDailyCiWidth60Mmol = 2.6
        )

        assertThat(lowCoverage.eligible).isFalse()
        assertThat(lowCoverage.reason).isEqualTo("daily_ci_coverage30_out_of_bounds")
        assertThat(wideCi.eligible).isFalse()
        assertThat(wideCi.reason).isEqualTo("daily_ci_width30_out_of_bounds")
    }

    @Test
    fun isfCrRollingGate_blocksWhenNotEnoughWindowsAvailable() {
        val windows = listOf(
            AutomationRepository.IsfCrRollingQualityWindowAssessment(
                days = 14,
                available = true,
                eligible = true,
                reason = "eligible",
                matchedSamples = 900,
                mae30Mmol = 0.7,
                mae60Mmol = 1.1,
                ciCoverage30Pct = 60.0,
                ciCoverage60Pct = 58.0,
                ciWidth30Mmol = 1.4,
                ciWidth60Mmol = 2.0
            ),
            AutomationRepository.IsfCrRollingQualityWindowAssessment(
                days = 30,
                available = false,
                eligible = false,
                reason = "rolling_report_missing",
                matchedSamples = null,
                mae30Mmol = null,
                mae60Mmol = null,
                ciCoverage30Pct = null,
                ciCoverage60Pct = null,
                ciWidth30Mmol = null,
                ciWidth60Mmol = null
            ),
            AutomationRepository.IsfCrRollingQualityWindowAssessment(
                days = 90,
                available = false,
                eligible = false,
                reason = "rolling_report_missing",
                matchedSamples = null,
                mae30Mmol = null,
                mae60Mmol = null,
                ciCoverage30Pct = null,
                ciCoverage60Pct = null,
                ciWidth30Mmol = null,
                ciWidth60Mmol = null
            )
        )

        val assessment = AutomationRepository.evaluateIsfCrRollingQualityGateStatic(
            windows = windows,
            minRequiredWindows = 2
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("rolling_windows_insufficient")
        assertThat(assessment.requiredWindowCount).isEqualTo(2)
        assertThat(assessment.evaluatedWindowCount).isEqualTo(1)
    }

    @Test
    fun isfCrRollingGate_blocksWhenAnyAvailableWindowFails() {
        val windows = listOf(
            AutomationRepository.IsfCrRollingQualityWindowAssessment(
                days = 14,
                available = true,
                eligible = true,
                reason = "eligible",
                matchedSamples = 900,
                mae30Mmol = 0.7,
                mae60Mmol = 1.1,
                ciCoverage30Pct = 60.0,
                ciCoverage60Pct = 58.0,
                ciWidth30Mmol = 1.4,
                ciWidth60Mmol = 2.0
            ),
            AutomationRepository.IsfCrRollingQualityWindowAssessment(
                days = 30,
                available = true,
                eligible = false,
                reason = "daily_mae60_out_of_bounds",
                matchedSamples = 1800,
                mae30Mmol = 0.9,
                mae60Mmol = 1.8,
                ciCoverage30Pct = 58.0,
                ciCoverage60Pct = 56.0,
                ciWidth30Mmol = 1.5,
                ciWidth60Mmol = 2.2
            ),
            AutomationRepository.IsfCrRollingQualityWindowAssessment(
                days = 90,
                available = false,
                eligible = false,
                reason = "rolling_report_missing",
                matchedSamples = null,
                mae30Mmol = null,
                mae60Mmol = null,
                ciCoverage30Pct = null,
                ciCoverage60Pct = null,
                ciWidth30Mmol = null,
                ciWidth60Mmol = null
            )
        )

        val assessment = AutomationRepository.evaluateIsfCrRollingQualityGateStatic(
            windows = windows,
            minRequiredWindows = 2
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("rolling_30d_daily_mae60_out_of_bounds")
        assertThat(assessment.evaluatedWindowCount).isEqualTo(2)
        assertThat(assessment.passedWindowCount).isEqualTo(1)
    }

    @Test
    fun isfCrRollingGate_allowsWhenRequiredWindowsPass() {
        val windows = listOf(
            AutomationRepository.IsfCrRollingQualityWindowAssessment(
                days = 14,
                available = true,
                eligible = true,
                reason = "eligible",
                matchedSamples = 900,
                mae30Mmol = 0.7,
                mae60Mmol = 1.1,
                ciCoverage30Pct = 60.0,
                ciCoverage60Pct = 58.0,
                ciWidth30Mmol = 1.4,
                ciWidth60Mmol = 2.0
            ),
            AutomationRepository.IsfCrRollingQualityWindowAssessment(
                days = 30,
                available = true,
                eligible = true,
                reason = "eligible",
                matchedSamples = 1800,
                mae30Mmol = 0.8,
                mae60Mmol = 1.2,
                ciCoverage30Pct = 59.0,
                ciCoverage60Pct = 57.0,
                ciWidth30Mmol = 1.5,
                ciWidth60Mmol = 2.2
            ),
            AutomationRepository.IsfCrRollingQualityWindowAssessment(
                days = 90,
                available = false,
                eligible = false,
                reason = "rolling_report_missing",
                matchedSamples = null,
                mae30Mmol = null,
                mae60Mmol = null,
                ciCoverage30Pct = null,
                ciCoverage60Pct = null,
                ciWidth30Mmol = null,
                ciWidth60Mmol = null
            )
        )

        val assessment = AutomationRepository.evaluateIsfCrRollingQualityGateStatic(
            windows = windows,
            minRequiredWindows = 2
        )

        assertThat(assessment.eligible).isTrue()
        assertThat(assessment.reason).isEqualTo("eligible")
        assertThat(assessment.requiredWindowCount).isEqualTo(2)
        assertThat(assessment.evaluatedWindowCount).isEqualTo(2)
        assertThat(assessment.passedWindowCount).isEqualTo(2)
    }

    @Test
    fun isfCrDailyRiskGate_blocksWhenRiskHigh() {
        val assessment = AutomationRepository.evaluateIsfCrDailyRiskGateStatic(
            riskLevel = 3,
            blockedRiskLevel = 3
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("daily_risk_high")
        assertThat(assessment.riskLevel).isEqualTo(3)
    }

    @Test
    fun isfCrDailyRiskGate_allowsWhenRiskMedium() {
        val assessment = AutomationRepository.evaluateIsfCrDailyRiskGateStatic(
            riskLevel = 2,
            blockedRiskLevel = 3
        )

        assertThat(assessment.eligible).isTrue()
        assertThat(assessment.reason).isEqualTo("eligible")
        assertThat(assessment.riskLevel).isEqualTo(2)
    }

    @Test
    fun isfCrDailyRiskGate_allowsWhenRiskMissing() {
        val assessment = AutomationRepository.evaluateIsfCrDailyRiskGateStatic(
            riskLevel = null,
            blockedRiskLevel = 3
        )

        assertThat(assessment.eligible).isTrue()
        assertThat(assessment.reason).isEqualTo("daily_risk_missing_or_unknown")
        assertThat(assessment.riskLevel).isEqualTo(0)
    }

    @Test
    fun isfCrDailyRiskGate_blocksMediumWhenThresholdIsMedium() {
        val assessment = AutomationRepository.evaluateIsfCrDailyRiskGateStatic(
            riskLevel = 2,
            blockedRiskLevel = 2
        )

        assertThat(assessment.eligible).isFalse()
        assertThat(assessment.reason).isEqualTo("daily_risk_high")
        assertThat(assessment.riskLevel).isEqualTo(2)
    }

    @Test
    fun isfCrDailyRiskGate_clampsConfiguredThresholdIntoSafeRange() {
        val assessment = AutomationRepository.evaluateIsfCrDailyRiskGateStatic(
            riskLevel = 2,
            blockedRiskLevel = 99
        )

        assertThat(assessment.eligible).isTrue()
        assertThat(assessment.reason).isEqualTo("eligible")
        assertThat(assessment.riskLevel).isEqualTo(2)
    }

    @Test
    fun parseIsfCrQualityRiskLevel_parsesEnglishLabels() {
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("HIGH(gap)")
        ).isEqualTo(3)
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("MEDIUM(sensorBlocked)")
        ).isEqualTo(2)
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("LOW")
        ).isEqualTo(1)
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("UNKNOWN")
        ).isEqualTo(0)
    }

    @Test
    fun parseIsfCrQualityRiskLevel_parsesRussianLabelsAndNumeric() {
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("ВЫСОКИЙ (sensor)")
        ).isEqualTo(3)
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("средний")
        ).isEqualTo(2)
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("низкий риск")
        ).isEqualTo(1)
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("неизвестно")
        ).isEqualTo(0)
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("3")
        ).isEqualTo(3)
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("2")
        ).isEqualTo(2)
    }

    @Test
    fun parseIsfCrQualityRiskLevel_returnsNullForUnrecognizedText() {
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("RISK?")
        ).isNull()
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic("")
        ).isNull()
        assertThat(
            AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic(null)
        ).isNull()
    }

    @Test
    fun resolveIsfCrDailyRiskLevelSource_prefersTextFallbackFlag() {
        val source = AutomationRepository.resolveIsfCrDailyRiskLevelSourceStatic(
            riskLevel = 2,
            fallbackUsed = true
        )

        assertThat(source).isEqualTo("text_fallback")
    }

    @Test
    fun resolveIsfCrDailyRiskLevelSource_marksMissingWhenUnknown() {
        val source = AutomationRepository.resolveIsfCrDailyRiskLevelSourceStatic(
            riskLevel = 0,
            fallbackUsed = false
        )

        assertThat(source).isEqualTo("missing_or_unknown")
    }

    @Test
    fun sensorLagShadowRuleChanged_detectsRuleOrActionDrift() {
        val baseline = listOf(
            triggeredDecision(ruleId = "adaptive_target", type = "temp_target", targetMmol = 5.6)
        )
        val changedRule = listOf(
            triggeredDecision(ruleId = "pattern_target", type = "temp_target", targetMmol = 5.6)
        )
        val changedTarget = listOf(
            triggeredDecision(ruleId = "adaptive_target", type = "temp_target", targetMmol = 5.68)
        )
        val unchanged = listOf(
            triggeredDecision(ruleId = "adaptive_target", type = "temp_target", targetMmol = 5.62)
        )

        assertThat(
            AutomationRepository.hasSensorLagShadowRuleChangedStatic(
                baseline = baseline,
                candidate = changedRule
            )
        ).isTrue()
        assertThat(
            AutomationRepository.hasSensorLagShadowRuleChangedStatic(
                baseline = baseline,
                candidate = changedTarget
            )
        ).isTrue()
        assertThat(
            AutomationRepository.hasSensorLagShadowRuleChangedStatic(
                baseline = baseline,
                candidate = unchanged
            )
        ).isFalse()
    }

    @Test
    fun sensorLagShadowTargetDelta_returnsSignedTargetShift() {
        val baseline = listOf(
            triggeredDecision(ruleId = "adaptive_target", type = "temp_target", targetMmol = 5.5)
        )
        val higherCandidate = listOf(
            triggeredDecision(ruleId = "adaptive_target", type = "temp_target", targetMmol = 5.9)
        )
        val noCandidate = emptyList<RuleDecision>()

        assertThat(
            AutomationRepository.resolveSensorLagShadowTargetDeltaMmolStatic(
                baseline = baseline,
                candidate = higherCandidate
            )
        ).isWithin(0.001).of(0.4)
        assertThat(
            AutomationRepository.resolveSensorLagShadowTargetDeltaMmolStatic(
                baseline = baseline,
                candidate = noCandidate
            )
        ).isWithin(0.001).of(-5.5)
        assertThat(
            AutomationRepository.resolveSensorLagShadowTargetDeltaMmolStatic(
                baseline = emptyList(),
                candidate = emptyList()
            )
        ).isNull()
    }

    @Test
    fun sensorLagControlPlan_offModeKeepsRawControlPath() {
        val merged = sampleForecasts()
        val lagCorrected = merged.map {
            it.copy(valueMmol = it.valueMmol + 0.3, modelVersion = "${it.modelVersion}|lag")
        }

        val plan = AutomationRepository.resolveSensorLagControlPlanStatic(
            requestedMode = SensorLagCorrectionMode.OFF,
            estimate = sampleSensorLagEstimate(mode = SensorLagCorrectionMode.OFF),
            rawCurrentGlucoseMmol = 6.0,
            mergedForecasts = merged,
            lagCorrectedForecasts = lagCorrected
        )

        assertThat(plan.effectiveCurrentGlucoseMmol).isWithin(0.001).of(6.0)
        assertThat(plan.controlForecasts).containsExactlyElementsIn(merged).inOrder()
        assertThat(plan.shouldEvaluateShadow).isFalse()
        assertThat(plan.shadowCurrentGlucoseMmol).isNull()
        assertThat(plan.shadowForecasts).isNull()
    }

    @Test
    fun sensorLagControlPlan_shadowFallbackKeepsLiveRawButRunsShadowPath() {
        val merged = sampleForecasts()
        val lagCorrected = merged.map {
            it.copy(valueMmol = it.valueMmol + 0.4, modelVersion = "${it.modelVersion}|lag")
        }

        val plan = AutomationRepository.resolveSensorLagControlPlanStatic(
            requestedMode = SensorLagCorrectionMode.ACTIVE,
            estimate = sampleSensorLagEstimate(
                mode = SensorLagCorrectionMode.SHADOW,
                rawGlucoseMmol = 6.0,
                correctedGlucoseMmol = 6.6
            ),
            rawCurrentGlucoseMmol = 6.0,
            mergedForecasts = merged,
            lagCorrectedForecasts = lagCorrected
        )

        assertThat(plan.effectiveCurrentGlucoseMmol).isWithin(0.001).of(6.0)
        assertThat(plan.controlForecasts).containsExactlyElementsIn(merged).inOrder()
        assertThat(plan.shouldEvaluateShadow).isTrue()
        assertThat(plan.shadowCurrentGlucoseMmol).isWithin(0.001).of(6.6)
        assertThat(plan.shadowForecasts).containsExactlyElementsIn(lagCorrected).inOrder()
    }

    @Test
    fun sensorLagControlPlan_activeModeUsesCorrectedControlPath() {
        val merged = sampleForecasts()
        val lagCorrected = merged.map {
            it.copy(valueMmol = it.valueMmol + 0.5, modelVersion = "${it.modelVersion}|lag")
        }

        val plan = AutomationRepository.resolveSensorLagControlPlanStatic(
            requestedMode = SensorLagCorrectionMode.ACTIVE,
            estimate = sampleSensorLagEstimate(
                mode = SensorLagCorrectionMode.ACTIVE,
                rawGlucoseMmol = 6.0,
                correctedGlucoseMmol = 6.7
            ),
            rawCurrentGlucoseMmol = 6.0,
            mergedForecasts = merged,
            lagCorrectedForecasts = lagCorrected
        )

        assertThat(plan.effectiveCurrentGlucoseMmol).isWithin(0.001).of(6.7)
        assertThat(plan.controlForecasts).containsExactlyElementsIn(lagCorrected).inOrder()
        assertThat(plan.shouldEvaluateShadow).isFalse()
        assertThat(plan.shadowCurrentGlucoseMmol).isNull()
        assertThat(plan.shadowForecasts).isNull()
    }

    @Test
    fun sensorLagTelemetryRows_includeExpectedKeysAndForecastHorizons() {
        val nowTs = 1_736_000_000_000L
        val rows = AutomationRepository.buildSensorLagTelemetryRowsStatic(
            nowTs = nowTs,
            estimate = sampleSensorLagEstimate(
                mode = SensorLagCorrectionMode.ACTIVE,
                rawGlucoseMmol = 6.1,
                correctedGlucoseMmol = 6.6
            ),
            latestGlucoseInput = GlucoseInputMetadata(
                key = "raw_sgv",
                kind = "raw"
            ),
            controlForecasts = listOf(
                Forecast(nowTs + 5 * 60_000L, 5, 6.6, 6.2, 7.0, "control"),
                Forecast(nowTs + 15 * 60_000L, 15, 6.8, 6.1, 7.5, "control"),
                Forecast(nowTs + 30 * 60_000L, 30, 7.0, 6.3, 7.7, "control"),
                Forecast(nowTs + 60 * 60_000L, 60, 7.3, 6.4, 8.2, "control")
            ),
            candidateForecasts = listOf(
                Forecast(nowTs + 5 * 60_000L, 5, 6.7, 6.3, 7.1, "lag"),
                Forecast(nowTs + 15 * 60_000L, 15, 6.9, 6.2, 7.6, "lag"),
                Forecast(nowTs + 30 * 60_000L, 30, 7.1, 6.4, 7.8, "lag"),
                Forecast(nowTs + 60 * 60_000L, 60, 7.4, 6.5, 8.3, "lag")
            ),
            shadowRuleChanged = true,
            shadowTargetDeltaMmol = 0.25
        )

        val byKey = rows.associateBy { it.key }
        assertThat(byKey.keys).containsAtLeast(
            "sensor_lag_age_hours",
            "sensor_lag_age_source",
            "sensor_lag_minutes",
            "sensor_lag_correction_mmol",
            "sensor_lag_corrected_glucose_mmol",
            "sensor_lag_confidence",
            "sensor_lag_active",
            "sensor_lag_mode",
            "sensor_lag_disable_reason",
            "sensor_lag_shadow_rule_changed",
            "sensor_lag_shadow_target_delta_mmol",
            "sensor_lag_control_forecast_5m",
            "sensor_lag_control_forecast_30m",
            "sensor_lag_control_forecast_60m",
            "sensor_lag_candidate_forecast_5m",
            "sensor_lag_candidate_forecast_30m",
            "sensor_lag_candidate_forecast_60m",
            "glucose_input_key",
            "glucose_input_kind"
        )
        assertThat(byKey.keys).doesNotContain("sensor_lag_control_forecast_15m")
        assertThat(byKey.keys).doesNotContain("sensor_lag_candidate_forecast_15m")
        assertThat(byKey["sensor_lag_active"]?.valueDouble).isWithin(0.001).of(1.0)
        assertThat(byKey["sensor_lag_mode"]?.valueText).isEqualTo("ACTIVE")
        assertThat(byKey["sensor_lag_age_source"]?.valueText).isEqualTo("explicit")
        assertThat(byKey["sensor_lag_shadow_rule_changed"]?.valueDouble).isWithin(0.001).of(1.0)
        assertThat(byKey["sensor_lag_shadow_target_delta_mmol"]?.valueDouble).isWithin(0.001).of(0.25)
        assertThat(byKey["sensor_lag_control_forecast_30m"]?.valueDouble).isWithin(0.001).of(7.0)
        assertThat(byKey["sensor_lag_candidate_forecast_30m"]?.valueDouble).isWithin(0.001).of(7.1)
        assertThat(byKey["glucose_input_key"]?.valueText).isEqualTo("raw_sgv")
        assertThat(byKey["glucose_input_kind"]?.valueText).isEqualTo("raw")
        assertThat(rows.all { it.timestamp == nowTs }).isTrue()
        assertThat(rows.all { it.source == "copilot_sensor_lag" }).isTrue()
    }

    @Test
    fun sensorLagTelemetryRows_markMissingOptionalValuesAsStale() {
        val rows = AutomationRepository.buildSensorLagTelemetryRowsStatic(
            nowTs = 42L,
            estimate = sampleSensorLagEstimate(
                mode = SensorLagCorrectionMode.SHADOW,
                rawGlucoseMmol = 5.8,
                correctedGlucoseMmol = 6.0
            ).copy(
                ageHours = null,
                ageSource = SensorLagAgeSource.MISSING,
                disableReason = "missing_sensor_age"
            ),
            latestGlucoseInput = GlucoseInputMetadata(
                key = " ",
                kind = " "
            ),
            controlForecasts = emptyList(),
            candidateForecasts = emptyList(),
            shadowRuleChanged = null,
            shadowTargetDeltaMmol = null
        )

        val byKey = rows.associateBy { it.key }
        assertThat(byKey["sensor_lag_age_hours"]?.quality).isEqualTo("STALE")
        assertThat(byKey["sensor_lag_age_source"]?.valueText).isEqualTo("missing")
        assertThat(byKey["sensor_lag_active"]?.valueDouble).isWithin(0.001).of(0.0)
        assertThat(byKey["sensor_lag_mode"]?.valueText).isEqualTo("SHADOW")
        assertThat(byKey["sensor_lag_disable_reason"]?.valueText).isEqualTo("missing_sensor_age")
        assertThat(byKey["sensor_lag_shadow_rule_changed"]?.quality).isEqualTo("STALE")
        assertThat(byKey["sensor_lag_shadow_target_delta_mmol"]?.quality).isEqualTo("STALE")
        assertThat(byKey["glucose_input_key"]?.quality).isEqualTo("STALE")
        assertThat(byKey["glucose_input_kind"]?.quality).isEqualTo("STALE")
    }

    @Test
    fun sensorLagTelemetryRows_offModeOmitsCandidateForecasts() {
        val nowTs = 1_736_000_000_000L
        val rows = AutomationRepository.buildSensorLagTelemetryRowsStatic(
            nowTs = nowTs,
            estimate = sampleSensorLagEstimate(
                mode = SensorLagCorrectionMode.OFF,
                rawGlucoseMmol = 6.1,
                correctedGlucoseMmol = 6.1
            ).copy(
                lagMinutes = 0.0,
                correctionMmol = 0.0,
                confidence = 0.0,
                disableReason = "mode_off"
            ),
            latestGlucoseInput = GlucoseInputMetadata(
                key = "sgv",
                kind = "sgv"
            ),
            controlForecasts = listOf(
                Forecast(nowTs + 5 * 60_000L, 5, 6.1, 5.8, 6.4, "control"),
                Forecast(nowTs + 30 * 60_000L, 30, 6.4, 5.9, 6.9, "control"),
                Forecast(nowTs + 60 * 60_000L, 60, 6.8, 6.0, 7.4, "control")
            ),
            candidateForecasts = listOf(
                Forecast(nowTs + 5 * 60_000L, 5, 6.2, 5.9, 6.5, "lag"),
                Forecast(nowTs + 30 * 60_000L, 30, 6.5, 6.0, 7.0, "lag"),
                Forecast(nowTs + 60 * 60_000L, 60, 6.9, 6.1, 7.5, "lag")
            ),
            shadowRuleChanged = null,
            shadowTargetDeltaMmol = null
        )

        val byKey = rows.associateBy { it.key }
        assertThat(byKey["sensor_lag_mode"]?.valueText).isEqualTo("OFF")
        assertThat(byKey.keys).containsAtLeast(
            "sensor_lag_control_forecast_5m",
            "sensor_lag_control_forecast_30m",
            "sensor_lag_control_forecast_60m"
        )
        assertThat(byKey.keys).doesNotContain("sensor_lag_candidate_forecast_5m")
        assertThat(byKey.keys).doesNotContain("sensor_lag_candidate_forecast_30m")
        assertThat(byKey.keys).doesNotContain("sensor_lag_candidate_forecast_60m")
    }

    @Test
    fun resolveLatestGlucoseInputMetadata_prefersMatchingSourceRows() {
        val ts = 1_736_000_000_000L
        val resolved = AutomationRepository.resolveLatestGlucoseInputMetadataStatic(
            rows = listOf(
                telemetryText(ts = ts, source = "nightscout", key = "glucose_input_key", value = "sgv"),
                telemetryText(ts = ts, source = "nightscout", key = "glucose_input_kind", value = "sgv"),
                telemetryText(ts = ts, source = "aaps_broadcast", key = "glucose_input_key", value = "raw_sgv"),
                telemetryText(ts = ts, source = "aaps_broadcast", key = "glucose_input_kind", value = "raw")
            ),
            latestGlucoseTs = ts,
            latestGlucoseSource = "aaps_broadcast"
        )

        assertThat(resolved).isEqualTo(GlucoseInputMetadata(key = "raw_sgv", kind = "raw"))
    }

    @Test
    fun resolveLatestGlucoseInputMetadata_fallsBackToTimestampWhenSourceDoesNotMatch() {
        val ts = 1_736_000_000_000L
        val resolved = AutomationRepository.resolveLatestGlucoseInputMetadataStatic(
            rows = listOf(
                telemetryText(ts = ts, source = "nightscout", key = "glucose_input_key", value = "sgv"),
                telemetryText(ts = ts, source = "nightscout", key = "glucose_input_kind", value = "estimate"),
                telemetryText(ts = ts - 5_000L, source = "nightscout", key = "glucose_input_key", value = "older")
            ),
            latestGlucoseTs = ts,
            latestGlucoseSource = "xdrip_broadcast"
        )

        assertThat(resolved).isEqualTo(GlucoseInputMetadata(key = "sgv", kind = "estimate"))
    }

    @Test
    fun sensorLagTelemetryMapUpdates_encodeAgeSourceAndInputKind() {
        val updates = AutomationRepository.buildSensorLagTelemetryMapUpdatesStatic(
            estimate = sampleSensorLagEstimate(
                mode = SensorLagCorrectionMode.ACTIVE,
                rawGlucoseMmol = 6.0,
                correctedGlucoseMmol = 6.5
            ).copy(ageSource = SensorLagAgeSource.INFERRED),
            latestGlucoseInput = GlucoseInputMetadata(
                key = "raw_sgv",
                kind = "raw"
            )
        )

        assertThat(updates["sensor_lag_age_hours"]).isWithin(0.001).of(280.0)
        assertThat(updates["sensor_lag_minutes"]).isWithin(0.001).of(12.0)
        assertThat(updates["sensor_lag_correction_mmol"]).isWithin(0.001).of(0.5)
        assertThat(updates["sensor_lag_corrected_glucose_mmol"]).isWithin(0.001).of(6.5)
        assertThat(updates["sensor_lag_confidence"]).isWithin(0.001).of(0.82)
        assertThat(updates["sensor_lag_active"]).isWithin(0.001).of(1.0)
        assertThat(updates["sensor_lag_age_source"]).isWithin(0.001).of(0.5)
        assertThat(updates["glucose_input_kind"]).isWithin(0.001).of(1.0)
    }

    @Test
    fun sensorLagTelemetryMapUpdates_defaultUnknownInputKindToZero() {
        val updates = AutomationRepository.buildSensorLagTelemetryMapUpdatesStatic(
            estimate = sampleSensorLagEstimate(
                mode = SensorLagCorrectionMode.SHADOW
            ).copy(ageSource = SensorLagAgeSource.MISSING),
            latestGlucoseInput = GlucoseInputMetadata(
                key = "sgv",
                kind = "sgv"
            )
        )

        assertThat(updates["sensor_lag_active"]).isWithin(0.001).of(0.0)
        assertThat(updates["sensor_lag_age_source"]).isWithin(0.001).of(0.0)
        assertThat(updates["glucose_input_kind"]).isWithin(0.001).of(0.0)
    }

    private fun sampleForecasts(): List<Forecast> {
        val now = System.currentTimeMillis()
        return listOf(
            Forecast(now + 5 * 60_000L, 5, 6.0, 5.5, 6.5, "test"),
            Forecast(now + 30 * 60_000L, 30, 6.4, 5.7, 7.1, "test"),
            Forecast(now + 60 * 60_000L, 60, 6.8, 5.8, 7.8, "test")
        )
    }

    private fun sampleIsfCrSnapshot(
        mode: IsfCrRuntimeMode,
        confidence: Double
    ): IsfCrRealtimeSnapshot {
        val now = System.currentTimeMillis()
        return IsfCrRealtimeSnapshot(
            id = "snapshot-$now",
            ts = now,
            isfEff = 2.8,
            crEff = 11.0,
            isfBase = 2.5,
            crBase = 10.0,
            ciIsfLow = 2.0,
            ciIsfHigh = 3.2,
            ciCrLow = 9.0,
            ciCrHigh = 12.5,
            confidence = confidence,
            qualityScore = 0.8,
            factors = mapOf("activity" to 1.0),
            mode = mode,
            isfEvidenceCount = 4,
            crEvidenceCount = 5,
            reasons = emptyList()
        )
    }

    private fun triggeredDecision(
        ruleId: String,
        type: String,
        targetMmol: Double
    ): RuleDecision {
        return RuleDecision(
            ruleId = ruleId,
            state = RuleState.TRIGGERED,
            reasons = listOf("test"),
            actionProposal = ActionProposal(
                type = type,
                targetMmol = targetMmol,
                durationMinutes = 30,
                reason = "test"
            )
        )
    }

    private fun sampleSensorLagEstimate(
        mode: SensorLagCorrectionMode,
        rawGlucoseMmol: Double = 6.0,
        correctedGlucoseMmol: Double = 6.4
    ): SensorLagEstimate {
        return SensorLagEstimate(
            rawGlucoseMmol = rawGlucoseMmol,
            correctedGlucoseMmol = correctedGlucoseMmol,
            lagMinutes = 12.0,
            correctionMmol = correctedGlucoseMmol - rawGlucoseMmol,
            ageHours = 280.0,
            ageSource = SensorLagAgeSource.EXPLICIT,
            confidence = 0.82,
            mode = mode,
            disableReason = null
        )
    }

    private fun telemetryText(
        ts: Long,
        source: String,
        key: String,
        value: String
    ): TelemetrySampleEntity {
        return TelemetrySampleEntity(
            id = "tm-$key-$ts-$source",
            timestamp = ts,
            source = source,
            key = key,
            valueDouble = null,
            valueText = value,
            unit = null,
            quality = "OK"
        )
    }

    private fun sampleCircadianPrior(
        delta15: Double,
        delta30: Double,
        delta60: Double,
        confidence: Double,
        acuteAttenuation: Double,
        staleBlocked: Boolean
    ): CircadianForecastPrior {
        return CircadianForecastPrior(
            requestedDayType = CircadianDayType.WEEKDAY,
            segmentSource = CircadianDayType.WEEKDAY,
            slotIndex = 48,
            bgMedian = 6.4,
            slotP10 = 5.7,
            slotP25 = 6.0,
            slotP75 = 6.8,
            slotP90 = 7.1,
            delta15 = delta15,
            delta30 = delta30,
            delta60 = delta60,
            residualBias30 = 0.0,
            residualBias60 = 0.0,
            medianReversion30 = 0.0,
            medianReversion60 = 0.0,
            replayBias30 = 0.0,
            replayBias60 = 0.0,
            replaySampleCount30 = 12,
            replaySampleCount60 = 12,
            replayWinRate30 = 0.60,
            replayWinRate60 = 0.60,
            replayMaeImprovement30 = -0.08,
            replayMaeImprovement60 = -0.10,
            replayBucketStatus30 = CircadianReplayBucketStatus.HELPFUL,
            replayBucketStatus60 = CircadianReplayBucketStatus.HELPFUL,
            confidence = confidence,
            qualityScore = 0.8,
            stabilityScore = 0.85,
            horizonQuality30 = 0.90,
            horizonQuality60 = 0.92,
            acuteAttenuation = acuteAttenuation,
            staleBlocked = staleBlocked
        )
    }

    private fun buildDiagnostics(): HybridPredictionEngine.V3Diagnostics {
        val therapyCum = MutableList(13) { idx -> idx * 0.25 }
        val trendStep = listOf(0.0) + List(12) { 0.1 }
        val uamStep = listOf(0.0) + List(12) { 0.05 }
        val therapyStep = listOf(0.0) + List(12) { 0.25 }
        val glucosePath = listOf(6.0) + List(12) { idx -> 6.0 + (idx + 1) * 0.08 }
        return HybridPredictionEngine.V3Diagnostics(
            gNowRaw = 6.0,
            gNowUsed = 6.0,
            rocPer5Used = 0.05,
            kfSigmaG = 0.21,
            kfEwmaNis = 1.0,
            kfSigmaZ = 0.2,
            kfSigmaA = 0.03,
            kfWarmedUp = true,
            insulinProfileId = "novorapid",
            insulinDurationHours = 5.0,
            insulinAgeScale = 1.0,
            residualRoc0 = -0.12,
            uci0 = 0.08,
            uciMax = 0.25,
            k = -0.01,
            uamActive = false,
            virtualMealCarbs = null,
            virtualMealConfidence = null,
            usingVirtualMeal = false,
            arMu = -0.03,
            arPhi = 0.82,
            arSigmaE = 0.18,
            arUsedFallback = false,
            therapyStep = therapyStep,
            therapyCumClamped = therapyCum,
            carbFastActiveGrams = 0.0,
            carbMediumActiveGrams = 0.0,
            carbProteinSlowActiveGrams = 0.0,
            residualCarbsNowGrams = 0.0,
            residualCarbs30mGrams = 0.0,
            residualCarbs60mGrams = 0.0,
            residualCarbs120mGrams = 0.0,
            uamStep = uamStep,
            trendStep = trendStep,
            glucosePath = glucosePath,
            trendCum60Raw = 1.2,
            trendCum60Clamped = 1.2,
            predByHorizon = mapOf(5 to 6.1, 30 to 6.5, 60 to 6.9)
        )
    }
}
