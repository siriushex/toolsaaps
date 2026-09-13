package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import org.junit.Test

class UnifiedUamRuntimeTest {

    @Test
    fun estimatorRetainsExactAcceptedSensitivitySnapshotForUamDiagnostics() {
        val now = 1_760_000_600_000L
        val accepted = io.aaps.copilot.testSensitivityRuntimeSnapshot(
            cycleId = "accepted-uam-source-change",
            settingsRevision = 43L,
            timestamp = now - 60_000L
        )
        val context = io.aaps.copilot.testSensitivityRuntimeContext(
            consumer = SensitivityRuntimeConsumer.UAM,
            snapshot = accepted
        )

        val result = UnifiedUamEstimator.estimate(
            UnifiedUamInput(
                nowTs = now,
                glucose = glucose5m(now, 5.8, 6.0, 6.3, 6.7),
                insulinImpactMmol5 = -0.10,
                csfMmolPerGram = 0.20,
                sensorTrust = 0.95,
                therapyCoverage = 0.90,
                sensorBlocked = false,
                sensitivityRuntime = context
            )
        )

        assertThat(result.sensitivityRuntime.snapshot).isSameInstanceAs(accepted)
        assertThat(result.sensitivityRuntime.snapshot.forecastCycleId)
            .isEqualTo("accepted-uam-source-change")
        assertThat(result.sensitivityRuntime.snapshot.settingsRevision).isEqualTo(43L)
    }

    @Test
    fun sustainedRiseBecomesActiveAfterTwoCanonicalBucketsWithCausalOnset() {
        val now = 1_760_000_600_000L

        val result = UnifiedUamEstimator.estimate(
            UnifiedUamInput(
                nowTs = now,
                glucose = glucose5m(now, 5.8, 6.0, 6.3, 6.7),
                insulinImpactMmol5 = -0.10,
                csfMmolPerGram = 0.20,
                sensorTrust = 0.95,
                therapyCoverage = 0.90,
                sensorBlocked = false,
                sensitivityRuntime = uamSensitivityContext()
            )
        )

        assertThat(result.state).isEqualTo(UamRuntimeState.ACTIVE)
        assertThat(result.activeForForecast).isTrue()
        assertThat(result.activeForControl).isTrue()
        assertThat(result.onsetTs).isAtMost(now - 10 * 60_000L)
        assertThat(result.onsetTs).isAtMost(result.firstDetectionTs!!)
        assertThat(result.activeSinceTs).isEqualTo(result.onsetTs)
        assertThat(result.firstDetectionTs).isAtLeast(result.activeSinceTs!!)
        assertThat(result.activeSinceTs).isAtMost(now)
        assertThat(result.supportStableBuckets).isAtLeast(2)
        assertThat(result.supportedLowerBoundCarbsGrams).isAtLeast(0.0)
    }

    @Test
    fun isolatedJumpDoesNotBecomeActive() {
        val now = 1_760_000_600_000L

        val result = UnifiedUamEstimator.estimate(
            UnifiedUamInput(
                nowTs = now,
                glucose = glucose5m(now, 5.8, 5.9, 8.0, 5.9),
                insulinImpactMmol5 = -0.05,
                csfMmolPerGram = 0.20,
                sensorTrust = 0.95,
                therapyCoverage = 0.90,
                sensorBlocked = false,
                sensitivityRuntime = uamSensitivityContext()
            )
        )

        assertThat(result.state).isNotEqualTo(UamRuntimeState.ACTIVE)
        assertThat(result.activeForControl).isFalse()
        assertThat(result.supportStableBuckets).isLessThan(2)
    }

    @Test
    fun activeRequiresTwoContiguousSupportedBuckets() {
        val now = 1_760_000_600_000L
        val oneBucket = estimate(now, glucose5m(now, 6.0, 6.0, 6.0, 6.2))
        val twoBuckets = estimate(now, glucose5m(now, 6.0, 6.0, 6.2, 6.4))

        assertThat(oneBucket.state).isEqualTo(UamRuntimeState.SUSPECTED)
        assertThat(oneBucket.supportStableBuckets).isEqualTo(1)
        assertThat(oneBucket.activeForControl).isFalse()
        assertThat(twoBuckets.state).isEqualTo(UamRuntimeState.ACTIVE)
        assertThat(twoBuckets.supportStableBuckets).isEqualTo(2)
        assertThat(twoBuckets.onsetTs).isEqualTo(now - 10 * 60_000L)
        assertThat(twoBuckets.firstDetectionTs).isEqualTo(now - 5 * 60_000L)
        assertThat(twoBuckets.activeSinceTs).isEqualTo(twoBuckets.onsetTs)
        assertThat(twoBuckets.lowerBoundStableBuckets).isEqualTo(2)
        assertThat(twoBuckets.supportedLowerBoundCarbsGrams).isNotNull()
    }

    @Test
    fun announcedCarbImpactIsRemovedBeforeUamResidualIsSupported() {
        val now = 1_760_000_600_000L
        val glucose = glucose5m(now, 5.8, 6.0, 6.3, 6.7)
        val unannounced = estimate(now, glucose, announcedCarbImpactMmol5 = 0.0)
        val announced = estimate(now, glucose, announcedCarbImpactMmol5 = 0.45)

        assertThat(unannounced.state).isEqualTo(UamRuntimeState.ACTIVE)
        assertThat(unannounced.supportedLowerBoundCarbsGrams).isNotNull()
        assertThat(announced.activeForControl).isFalse()
        assertThat(announced.supportedLowerBoundCarbsGrams).isNull()
        assertThat(announced.signedResidualMmolPer5m).isLessThan(unannounced.signedResidualMmolPer5m)
    }

    @Test
    fun longRiseKeepsEpisodeTimestampsAtFullCanonicalRunStart() {
        val now = 1_760_000_600_000L
        val values = (0..10).map { index -> 6.0 + index * 0.15 }.toDoubleArray()

        val result = estimate(now, glucose5m(now, *values))

        val expectedOnset = now - 10 * 5L * 60_000L
        assertThat(result.supportStableBuckets).isEqualTo(10)
        assertThat(result.onsetTs).isEqualTo(expectedOnset)
        assertThat(result.firstDetectionTs).isEqualTo(expectedOnset + 5 * 60_000L)
        assertThat(result.activeSinceTs).isEqualTo(expectedOnset)
    }

    @Test
    fun adjacentCyclesDoNotMoveConfirmedEpisodeTimestampsLater() {
        val now = 1_760_000_600_000L
        val values = (0..10).map { index -> 6.0 + index * 0.15 }.toDoubleArray()
        val previousNow = now - 5 * 60_000L
        val previous = estimate(
            previousNow,
            glucose5m(previousNow, *values.dropLast(1).toDoubleArray())
        )
        val current = estimate(now, glucose5m(now, *values))

        assertThat(previous.state).isEqualTo(UamRuntimeState.ACTIVE)
        assertThat(current.state).isEqualTo(UamRuntimeState.ACTIVE)
        assertThat(current.onsetTs).isEqualTo(previous.onsetTs)
        assertThat(current.firstDetectionTs).isEqualTo(previous.firstDetectionTs)
        assertThat(current.activeSinceTs).isEqualTo(previous.activeSinceTs)
    }

    @Test
    fun canonicalSeriesProducesRobustCurrentAndShortDelta() {
        val now = 1_760_000_600_000L
        val raw = (
            glucose5m(now, 5.8, 6.0, 6.3, 6.7) +
                GlucosePoint(
                    ts = now - 5 * 60_000L,
                    valueMmol = 20.0,
                    source = "bad",
                    quality = DataQuality.SENSOR_ERROR
                )
            ).reversed()

        val result = estimate(now, raw, insulinImpactMmol5 = -0.10)

        assertThat(result.shortAverageDeltaMmol5).isWithin(1e-9).of(0.30)
        assertThat(result.signedResidualMmolPer5m).isWithin(1e-9).of(0.40)
        assertThat(result.impactMmolPer5m).isWithin(1e-9).of(0.40)
    }

    @Test
    fun longAverageTrendAgreementContributesToConfidence() {
        val now = 1_760_000_600_000L
        val aligned = estimate(now, glucose5m(now, 5.2, 5.4, 5.6, 5.8, 6.0, 6.2, 6.4))
        val conflicted = estimate(now, glucose5m(now, 7.0, 6.6, 6.2, 5.8, 6.0, 6.2, 6.4))

        assertThat(aligned.shortAverageDeltaMmol5)
            .isWithin(1e-9)
            .of(conflicted.shortAverageDeltaMmol5)
        assertThat(aligned.impactMmolPer5m)
            .isWithin(1e-9)
            .of(conflicted.impactMmolPer5m)
        assertThat(conflicted.confidence).isLessThan(aligned.confidence)
    }

    @Test
    fun fallingShortAverageDecaysWithoutCreatingSupport() {
        val now = 1_760_000_600_000L

        val result = estimate(
            now = now,
            glucose = glucose5m(now, 6.0, 6.4, 6.8, 5.8),
            insulinImpactMmol5 = -0.10
        )

        assertThat(result.shortAverageDeltaMmol5).isLessThan(0.0)
        assertThat(result.state).isEqualTo(UamRuntimeState.DECAYING)
        assertThat(result.supportStableBuckets).isEqualTo(0)
        assertThat(result.activeForControl).isFalse()
        assertThat(result.onsetTs).isEqualTo(now - 15 * 60_000L)
        assertThat(result.firstDetectionTs).isEqualTo(now - 10 * 60_000L)
        assertThat(result.activeSinceTs).isEqualTo(result.onsetTs)
    }

    @Test
    fun oneProvisionalLowerBoundBucketIsNotControlEligible() {
        val now = 1_760_000_600_000L

        val result = estimate(now, glucose5m(now, 6.0, 6.0, 6.0, 6.2))

        assertThat(result.state).isEqualTo(UamRuntimeState.SUSPECTED)
        assertThat(result.lowerBoundStableBuckets).isEqualTo(1)
        assertThat(result.supportedLowerBoundCarbsGrams).isNull()
        assertThat(result.activeForControl).isFalse()
    }

    @Test
    fun abruptEquivalentBoundRevisionDoesNotClaimTwoStableBuckets() {
        val now = 1_760_000_600_000L

        val result = estimate(
            now,
            glucose5m(now, 6.0, 6.0, 6.1, 7.0),
            insulinImpactMmol5 = 0.0
        )

        assertThat(result.state).isEqualTo(UamRuntimeState.ACTIVE)
        assertThat(result.supportStableBuckets).isEqualTo(2)
        assertThat(result.lowerBoundStableBuckets).isEqualTo(1)
        assertThat(result.supportedLowerBoundCarbsGrams).isNull()
        assertThat(result.activeForControl).isFalse()
    }

    @Test
    fun confirmedLowerBoundUsesMinimumAcrossStableTailInBothDirections() {
        val rising = UnifiedUamEstimator.resolveStableLowerBoundTail(
            listOf(6.7, 8.8)
        )
        val falling = UnifiedUamEstimator.resolveStableLowerBoundTail(
            listOf(8.8, 6.7)
        )

        assertThat(rising.count).isEqualTo(2)
        assertThat(rising.confirmedMinimumGrams).isAtMost(6.7)
        assertThat(falling.count).isEqualTo(2)
        assertThat(falling.confirmedMinimumGrams).isEqualTo(6.7)
    }

    @Test
    fun sustainedHighQualityLowerBoundReachesEarliestTenMinuteGate() {
        val now = 1_760_000_600_000L

        val result = estimate(now, glucose5m(now, 6.0, 6.0, 6.2, 6.4))

        assertThat(result.state).isEqualTo(UamRuntimeState.ACTIVE)
        assertThat(result.onsetTs).isEqualTo(now - 10 * 60_000L)
        assertThat(result.activeSinceTs).isEqualTo(now - 10 * 60_000L)
        assertThat(result.lowerBoundStableBuckets).isEqualTo(2)
        assertThat(result.supportedLowerBoundCarbsGrams).isNotNull()
    }

    @Test
    fun isolatedPriorSupportFollowedByFallDoesNotEnterDecaying() {
        val now = 1_760_000_600_000L

        val result = estimate(
            now = now,
            glucose = glucose5m(now, 6.0, 6.0, 6.2, 5.5),
            insulinImpactMmol5 = 0.0
        )

        assertThat(result.shortAverageDeltaMmol5).isLessThan(0.0)
        assertThat(result.state).isEqualTo(UamRuntimeState.INACTIVE)
        assertThat(result.supportStableBuckets).isEqualTo(0)
        assertThat(result.activeForForecast).isFalse()
        assertThat(result.activeForControl).isFalse()
    }

    @Test
    fun sensorBlockDisablesControlAndLowerBound() {
        val now = 1_760_000_600_000L

        val result = estimate(
            now = now,
            glucose = glucose5m(now, 5.8, 6.0, 6.3, 6.7),
            sensorBlocked = true
        )

        assertThat(result.state).isEqualTo(UamRuntimeState.BLOCKED)
        assertThat(result.activeForForecast).isFalse()
        assertThat(result.activeForControl).isFalse()
        assertThat(result.supportedLowerBoundCarbsGrams).isNull()
        assertThat(result.reasons).contains("sensor_blocked")
    }

    @Test
    fun staleOrInsufficientCanonicalDataIsBlocked() {
        val now = 1_760_000_600_000L
        val stale = estimate(now, glucose5m(now - 11 * 60_000L, 5.8, 6.0, 6.3, 6.7))
        val staleQuality = estimate(
            now,
            glucose5m(now, 5.8, 6.0, 6.3, 6.7).mapIndexed { index, point ->
                if (index == 3) point.copy(quality = DataQuality.STALE) else point
            }
        )
        val insufficient = estimate(now, glucose5m(now, 6.0, 6.2, 6.4))

        assertThat(stale.state).isEqualTo(UamRuntimeState.BLOCKED)
        assertThat(stale.reasons).contains("stale_canonical_glucose")
        assertThat(staleQuality.state).isEqualTo(UamRuntimeState.BLOCKED)
        assertThat(staleQuality.reasons).contains("stale_canonical_glucose")
        assertThat(staleQuality.reasons).contains("invalid_latest_causal_glucose")
        assertThat(insufficient.state).isEqualTo(UamRuntimeState.BLOCKED)
        assertThat(insufficient.reasons).contains("insufficient_canonical_glucose")
    }

    @Test
    fun invalidLatestCausalSampleBlocksInsteadOfFallingBackToOlderHistory() {
        val now = 1_760_000_600_000L
        val risingHistory = glucose5m(now - 5 * 60_000L, 5.8, 6.0, 6.3, 6.7)

        listOf(23.0, 2.1, Double.NaN).forEach { invalidValue ->
            val result = estimate(
                now,
                risingHistory + GlucosePoint(
                    ts = now,
                    valueMmol = invalidValue,
                    source = "invalid-latest"
                )
            )

            assertThat(result.state).isEqualTo(UamRuntimeState.BLOCKED)
            assertThat(result.activeForForecast).isFalse()
            assertThat(result.activeForControl).isFalse()
            assertThat(result.supportedLowerBoundCarbsGrams).isNull()
            assertThat(result.reasons).contains("invalid_latest_causal_glucose")
        }
    }

    @Test
    fun latestDuplicateResolutionUsesLastSampleAtCausalTimestamp() {
        val now = 1_760_000_600_000L
        val risingHistory = glucose5m(now - 5 * 60_000L, 5.8, 6.0, 6.3, 6.7)
        val valid = GlucosePoint(ts = now, valueMmol = 7.0, source = "valid")
        val sensorError = valid.copy(source = "error", quality = DataQuality.SENSOR_ERROR)

        val lastError = estimate(now, risingHistory + valid + sensorError)
        val lastValid = estimate(now, risingHistory + sensorError + valid)

        assertThat(lastError.state).isEqualTo(UamRuntimeState.BLOCKED)
        assertThat(lastError.reasons).contains("invalid_latest_causal_glucose")
        assertThat(lastError.activeForControl).isFalse()
        assertThat(lastError.supportedLowerBoundCarbsGrams).isNull()
        assertThat(lastValid.state).isEqualTo(UamRuntimeState.ACTIVE)
        assertThat(lastValid.reasons).doesNotContain("invalid_latest_causal_glucose")
    }

    @Test
    fun freshnessBoundaryAllowsTenMinutesAndBlocksTenMinutesPlusOneMillisecond() {
        val now = 1_760_000_600_000L
        val exactlyTenMinutes = estimate(
            now,
            glucose5m(now - 10 * 60_000L, 5.8, 6.0, 6.3, 6.7)
        )
        val beyondTenMinutes = estimate(
            now,
            glucose5m(now - 10 * 60_000L - 1L, 5.8, 6.0, 6.3, 6.7)
        )

        assertThat(exactlyTenMinutes.state).isEqualTo(UamRuntimeState.ACTIVE)
        assertThat(exactlyTenMinutes.reasons).doesNotContain("stale_canonical_glucose")
        assertThat(beyondTenMinutes.state).isEqualTo(UamRuntimeState.BLOCKED)
        assertThat(beyondTenMinutes.reasons).contains("stale_canonical_glucose")
    }

    @Test
    fun lowerSensorTrustOrTherapyCoverageLowersConfidence() {
        val now = 1_760_000_600_000L
        val glucose = glucose5m(now, 5.8, 6.0, 6.3, 6.7)
        val trusted = estimate(now, glucose, sensorTrust = 0.95, therapyCoverage = 0.90)
        val weakSensor = estimate(now, glucose, sensorTrust = 0.30, therapyCoverage = 0.90)
        val weakTherapy = estimate(now, glucose, sensorTrust = 0.95, therapyCoverage = 0.30)

        assertThat(weakSensor.confidence).isLessThan(trusted.confidence)
        assertThat(weakTherapy.confidence).isLessThan(trusted.confidence)
    }

    @Test
    fun sensorTrustBelowHardGateDisablesControlAndLowerBound() {
        val now = 1_760_000_600_000L

        val result = estimate(
            now = now,
            glucose = glucose5m(now, 5.8, 6.0, 6.3, 6.7),
            sensorTrust = 0.01,
            therapyCoverage = 1.0
        )

        assertThat(result.state).isEqualTo(UamRuntimeState.ACTIVE)
        assertThat(result.activeForForecast).isTrue()
        assertThat(result.confidence).isGreaterThan(0.65)
        assertThat(result.activeForControl).isFalse()
        assertThat(result.supportedLowerBoundCarbsGrams).isNull()
    }

    @Test
    fun therapyCoverageBelowHardGateDisablesControlAndLowerBound() {
        val now = 1_760_000_600_000L

        val result = estimate(
            now = now,
            glucose = glucose5m(now, 5.8, 6.0, 6.3, 6.7),
            sensorTrust = 1.0,
            therapyCoverage = 0.01
        )

        assertThat(result.state).isEqualTo(UamRuntimeState.ACTIVE)
        assertThat(result.activeForForecast).isTrue()
        assertThat(result.confidence).isGreaterThan(0.65)
        assertThat(result.activeForControl).isFalse()
        assertThat(result.supportedLowerBoundCarbsGrams).isNull()
    }

    @Test
    fun exportTrustBoundaryIsPointSevenWhileControlRemainsPointEight() {
        val now = 1_760_000_600_000L
        val glucose = glucose5m(now, 5.8, 6.0, 6.3, 6.7)
        val belowExport = estimate(now, glucose, sensorTrust = 0.699, therapyCoverage = 0.90)
        val atExportBoundary = estimate(now, glucose, sensorTrust = 0.700, therapyCoverage = 0.90)
        val atControlBoundary = estimate(now, glucose, sensorTrust = 0.800, therapyCoverage = 0.90)

        assertThat(belowExport.activeForControl).isFalse()
        assertThat(belowExport.supportedLowerBoundCarbsGrams).isNull()
        assertThat(atExportBoundary.activeForControl).isFalse()
        assertThat(atExportBoundary.supportedLowerBoundCarbsGrams).isGreaterThan(0.0)
        assertThat(atControlBoundary.activeForControl).isTrue()
        assertThat(atControlBoundary.supportedLowerBoundCarbsGrams).isGreaterThan(0.0)
    }

    @Test
    fun coverageHardBoundaryIsInclusiveAtPointSeven() {
        val now = 1_760_000_600_000L
        val glucose = glucose5m(now, 5.8, 6.0, 6.3, 6.7)
        val below = estimate(now, glucose, sensorTrust = 0.95, therapyCoverage = 0.699)
        val atBoundary = estimate(now, glucose, sensorTrust = 0.95, therapyCoverage = 0.700)

        assertThat(below.activeForControl).isFalse()
        assertThat(below.supportedLowerBoundCarbsGrams).isNull()
        assertThat(atBoundary.activeForControl).isTrue()
        assertThat(atBoundary.supportedLowerBoundCarbsGrams).isGreaterThan(0.0)
    }

    @Test
    fun lowConfidenceSignalRemainsForecastOnly() {
        val now = 1_760_000_600_000L

        val result = estimate(
            now = now,
            glucose = glucose5m(now, 5.8, 6.0, 6.3, 6.7),
            sensorTrust = 0.10,
            therapyCoverage = 0.10
        )

        assertThat(result.state).isEqualTo(UamRuntimeState.ACTIVE)
        assertThat(result.activeForForecast).isTrue()
        assertThat(result.activeForControl).isFalse()
        assertThat(result.supportedLowerBoundCarbsGrams).isNull()
    }

    @Test
    fun forecastUsesSlopeAndThreeHourDecayAndIntegratesThroughCsf() {
        val now = 1_760_000_600_000L
        val csf = 0.20

        val result = estimate(
            now = now,
            glucose = glucose5m(now, 5.0, 5.4, 5.7, 5.9),
            insulinImpactMmol5 = 0.0,
            csfMmolPerGram = csf
        )

        assertThat(result.forecastStepsMmol).hasLength(36)
        assertThat(result.forecastStepsMmol[0]).isWithin(1e-9).of(0.10)
        result.forecastStepsMmol.forEachIndexed { index, stepImpact ->
            val linearCap = result.impactMmolPer5m * (1.0 - (index + 1.0) / 36.0)
            assertThat(stepImpact).isAtLeast(0.0)
            assertThat(stepImpact).isAtMost(linearCap + 1e-9)
        }
        assertThat(result.forecastStepsMmol.last()).isWithin(1e-9).of(0.0)
        assertThat(result.equivalentCarbsGrams)
            .isWithin(1e-9)
            .of(result.forecastStepsMmol.sum() / csf)
        assertThat(result.supportedLowerBoundCarbsGrams).isNull()
        assertThat(result.activeForControl).isFalse()
    }

    @Test
    fun zeroFutureContourCannotBeForecastOrControlActive() {
        val now = 1_760_000_600_000L

        val result = estimate(
            now = now,
            glucose = glucose5m(now, 6.0, 6.0, 7.0, 7.1),
            insulinImpactMmol5 = 0.0
        )

        assertThat(result.state).isEqualTo(UamRuntimeState.ACTIVE)
        assertThat(result.impactMmolPer5m).isGreaterThan(0.0)
        assertThat(result.forecastStepsMmol.sum()).isWithin(1e-9).of(0.0)
        assertThat(result.supportedLowerBoundCarbsGrams).isNull()
        assertThat(result.activeForForecast).isFalse()
        assertThat(result.activeForControl).isFalse()
    }

    @Test
    fun sustainedCanonicalJumpsAboveThreeMmolAreBlocked() {
        val now = 1_760_000_600_000L

        val result = estimate(
            now = now,
            glucose = glucose5m(now, 3.0, 6.1, 9.2, 12.3),
            insulinImpactMmol5 = 0.0
        )

        assertThat(result.state).isEqualTo(UamRuntimeState.BLOCKED)
        assertThat(result.activeForForecast).isFalse()
        assertThat(result.activeForControl).isFalse()
        assertThat(result.supportedLowerBoundCarbsGrams).isNull()
        assertThat(result.reasons).contains("implausible_canonical_delta")
    }

    @Test
    fun canonicalDeltaAtThreeMmolRemainsWithinSafetyBoundary() {
        val now = 1_760_000_600_000L

        val result = estimate(
            now = now,
            glucose = glucose5m(now, 3.0, 6.0, 9.0, 12.0),
            insulinImpactMmol5 = 0.0
        )

        assertThat(result.state).isEqualTo(UamRuntimeState.ACTIVE)
        assertThat(result.activeForControl).isTrue()
    }

    @Test
    fun outOfRangeGlucoseCannotProduceControlSignal() {
        val now = 1_760_000_600_000L

        val result = estimate(now, glucose5m(now, 23.0, 24.0, 25.0, 26.0))

        assertThat(result.state).isEqualTo(UamRuntimeState.BLOCKED)
        assertThat(result.activeForControl).isFalse()
        assertThat(result.supportedLowerBoundCarbsGrams).isNull()
    }

    @Test
    fun glucoseSafetyBoundariesAreInclusive() {
        val now = 1_760_000_600_000L
        val lowerAccepted = estimate(
            now,
            glucose5m(now, 3.0, 2.8, 2.5, 2.2),
            insulinImpactMmol5 = 0.0
        )
        val lowerRejected = estimate(
            now,
            glucose5m(now, 3.0, 2.8, 2.5, 2.199),
            insulinImpactMmol5 = 0.0
        )
        val upperAccepted = estimate(
            now,
            glucose5m(now, 20.5, 21.0, 21.5, 22.0),
            insulinImpactMmol5 = 0.0
        )
        val upperRejected = estimate(
            now,
            glucose5m(now, 20.5, 21.0, 21.5, 22.001),
            insulinImpactMmol5 = 0.0
        )

        assertThat(lowerAccepted.state).isNotEqualTo(UamRuntimeState.BLOCKED)
        assertThat(lowerAccepted.reasons).doesNotContain("invalid_latest_causal_glucose")
        assertThat(lowerRejected.state).isEqualTo(UamRuntimeState.BLOCKED)
        assertThat(lowerRejected.reasons).contains("invalid_latest_causal_glucose")
        assertThat(upperAccepted.state).isEqualTo(UamRuntimeState.ACTIVE)
        assertThat(upperAccepted.activeForControl).isTrue()
        assertThat(upperRejected.state).isEqualTo(UamRuntimeState.BLOCKED)
        assertThat(upperRejected.reasons).contains("invalid_latest_causal_glucose")
    }

    @Test
    fun csfOutsideProjectSafetyRangeRemovesEquivalentCarbsAndControl() {
        val now = 1_760_000_600_000L
        val glucose = glucose5m(now, 5.8, 6.0, 6.3, 6.7)

        listOf(0.049, 1.501).forEach { csf ->
            val result = estimate(now, glucose, csfMmolPerGram = csf)

            assertThat(result.equivalentCarbsGrams).isNull()
            assertThat(result.supportedLowerBoundCarbsGrams).isNull()
            assertThat(result.activeForControl).isFalse()
        }
    }

    @Test
    fun csfSafetyBoundariesAreInclusive() {
        val now = 1_760_000_600_000L
        val glucose = glucose5m(now, 5.8, 6.0, 6.3, 6.7)

        listOf(0.05, 1.5).forEach { csf ->
            val result = estimate(now, glucose, csfMmolPerGram = csf)

            assertThat(result.equivalentCarbsGrams).isGreaterThan(0.0)
            assertThat(result.supportedLowerBoundCarbsGrams).isGreaterThan(0.0)
            assertThat(result.activeForControl).isTrue()
        }
        listOf(0.049999, 1.500001).forEach { csf ->
            val result = estimate(now, glucose, csfMmolPerGram = csf)

            assertThat(result.equivalentCarbsGrams).isNull()
            assertThat(result.supportedLowerBoundCarbsGrams).isNull()
            assertThat(result.activeForControl).isFalse()
        }
    }

    @Test
    fun nonFiniteInsulinImpactBlocksEstimator() {
        val now = 1_760_000_600_000L
        val glucose = glucose5m(now, 5.8, 6.0, 6.3, 6.7)

        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { insulinImpact ->
            val result = estimate(now, glucose, insulinImpactMmol5 = insulinImpact)

            assertThat(result.state).isEqualTo(UamRuntimeState.BLOCKED)
            assertThat(result.activeForForecast).isFalse()
            assertThat(result.activeForControl).isFalse()
            assertThat(result.supportedLowerBoundCarbsGrams).isNull()
            assertThat(result.reasons).contains("invalid_insulin_impact")
        }
    }

    @Test
    fun invalidAnnouncedCarbImpactBlocksEstimator() {
        val now = 1_760_000_600_000L
        val glucose = glucose5m(now, 5.8, 6.0, 6.3, 6.7)

        listOf(-0.001, Double.NaN, Double.POSITIVE_INFINITY).forEach { announcedImpact ->
            val result = estimate(now, glucose, announcedCarbImpactMmol5 = announcedImpact)

            assertThat(result.state).isEqualTo(UamRuntimeState.BLOCKED)
            assertThat(result.activeForForecast).isFalse()
            assertThat(result.activeForControl).isFalse()
            assertThat(result.supportedLowerBoundCarbsGrams).isNull()
            assertThat(result.reasons).contains("invalid_announced_carb_impact")
        }
    }

    @Test
    fun overflowingCombinedTherapyImpactBlocksEstimator() {
        val now = 1_760_000_600_000L
        val result = estimate(
            now = now,
            glucose = glucose5m(now, 5.8, 6.0, 6.3, 6.7),
            insulinImpactMmol5 = Double.MAX_VALUE,
            announcedCarbImpactMmol5 = Double.MAX_VALUE
        )

        assertThat(result.state).isEqualTo(UamRuntimeState.BLOCKED)
        assertThat(result.reasons).contains("invalid_known_therapy_impact")
    }

    @Test
    fun equivalentAndLowerBoundCarbsAreCapped() {
        val now = 1_760_000_600_000L

        val result = estimate(
            now = now,
            glucose = glucose5m(now, 5.0, 5.5, 6.0, 6.5),
            insulinImpactMmol5 = -10.0,
            csfMmolPerGram = 0.05
        )

        assertThat(result.equivalentCarbsGrams).isEqualTo(15.0)
        assertThat(result.supportedLowerBoundCarbsGrams).isAtLeast(0.0)
        assertThat(result.supportedLowerBoundCarbsGrams).isAtMost(15.0)
    }

    @Test
    fun missingOrInvalidCsfRemovesEquivalentCarbs() {
        val now = 1_760_000_600_000L
        val glucose = glucose5m(now, 5.8, 6.0, 6.3, 6.7)

        listOf<Double?>(null, 0.0, -0.20, Double.NaN).forEach { csf ->
            val result = estimate(now, glucose, csfMmolPerGram = csf)

            assertThat(result.equivalentCarbsGrams).isNull()
            assertThat(result.supportedLowerBoundCarbsGrams).isNull()
            assertThat(result.activeForControl).isFalse()
        }
    }

    @Test
    fun futureGlucosePointsAreIgnored() {
        val now = 1_760_000_600_000L
        val causal = glucose5m(now, 5.8, 6.0, 6.3, 6.7)
        val withFuture = causal + GlucosePoint(
            ts = now + 5 * 60_000L,
            valueMmol = Double.NaN,
            source = "invalid-future"
        )

        val expected = estimate(now, causal)
        val actual = estimate(now, withFuture)

        assertSnapshotsEqual(expected, actual)
        assertThat(actual.onsetTs).isAtMost(now)
        assertThat(actual.firstDetectionTs).isAtMost(now)
        assertThat(actual.activeSinceTs).isAtMost(now)
    }

    @Test
    fun identicalInputsProduceDeterministicSnapshotAndForecast() {
        val now = 1_760_000_600_000L
        val input = UnifiedUamInput(
            nowTs = now,
            glucose = glucose5m(now, 5.8, 6.0, 6.3, 6.7),
            insulinImpactMmol5 = -0.10,
            csfMmolPerGram = 0.20,
            sensorTrust = 0.95,
            therapyCoverage = 0.90,
            sensorBlocked = false,
            sensitivityRuntime = uamSensitivityContext()
        )

        val first = UnifiedUamEstimator.estimate(input)
        val second = UnifiedUamEstimator.estimate(input)

        assertThat(first).isEqualTo(second)
        assertThat(first.hashCode()).isEqualTo(second.hashCode())
        assertSnapshotsEqual(first, second)
    }

    @Test
    fun forecastArrayGetterCannotMutateSnapshotState() {
        val now = 1_760_000_600_000L
        val input = UnifiedUamInput(
            nowTs = now,
            glucose = glucose5m(now, 5.8, 6.0, 6.3, 6.7),
            insulinImpactMmol5 = -0.10,
            csfMmolPerGram = 0.20,
            sensorTrust = 0.95,
            therapyCoverage = 0.90,
            sensorBlocked = false,
            sensitivityRuntime = uamSensitivityContext()
        )
        val snapshot = UnifiedUamEstimator.estimate(input)
        val equalSnapshot = UnifiedUamEstimator.estimate(input)
        val expectedFirstStep = snapshot.forecastStepsMmol[0]
        val expectedHashCode = snapshot.hashCode()

        val exposed = snapshot.forecastStepsMmol
        exposed[0] = 999.0

        assertThat(snapshot.forecastStepsMmol[0]).isEqualTo(expectedFirstStep)
        assertThat(snapshot).isEqualTo(equalSnapshot)
        assertThat(snapshot.hashCode()).isEqualTo(expectedHashCode)
    }

    @Test
    fun constructorCopiesForecastSourceArray() {
        val source = doubleArrayOf(0.30, 0.20, 0.10)
        val snapshot = snapshotWithForecast(source)
        val equalSnapshot = snapshotWithForecast(source.copyOf())
        val expectedHashCode = snapshot.hashCode()

        source[0] = 999.0

        assertThat(snapshot.forecastStepsMmol.asList()).containsExactly(0.30, 0.20, 0.10).inOrder()
        assertThat(snapshot).isEqualTo(equalSnapshot)
        assertThat(snapshot.hashCode()).isEqualTo(expectedHashCode)
    }

    private fun estimate(
        now: Long,
        glucose: List<GlucosePoint>,
        insulinImpactMmol5: Double = -0.10,
        csfMmolPerGram: Double? = 0.20,
        sensorTrust: Double = 0.95,
        therapyCoverage: Double = 0.90,
        announcedCarbImpactMmol5: Double = 0.0,
        sensorBlocked: Boolean = false
    ): UnifiedUamRuntimeSnapshot = UnifiedUamEstimator.estimate(
        UnifiedUamInput(
            nowTs = now,
            glucose = glucose,
            insulinImpactMmol5 = insulinImpactMmol5,
            announcedCarbImpactMmol5 = announcedCarbImpactMmol5,
            csfMmolPerGram = csfMmolPerGram,
            sensorTrust = sensorTrust,
            therapyCoverage = therapyCoverage,
            sensorBlocked = sensorBlocked,
            sensitivityRuntime = uamSensitivityContext()
        )
    )

    private fun assertSnapshotsEqual(
        expected: UnifiedUamRuntimeSnapshot,
        actual: UnifiedUamRuntimeSnapshot
    ) {
        assertThat(snapshotScalars(actual)).isEqualTo(snapshotScalars(expected))
        assertThat(actual.forecastStepsMmol.asList())
            .containsExactlyElementsIn(expected.forecastStepsMmol.asList())
            .inOrder()
    }

    private fun snapshotScalars(snapshot: UnifiedUamRuntimeSnapshot): List<Any?> = listOf(
        snapshot.timestamp,
        snapshot.state,
        snapshot.activeForForecast,
        snapshot.activeForControl,
        snapshot.impactMmolPer5m,
        snapshot.signedResidualMmolPer5m,
        snapshot.shortAverageDeltaMmol5,
        snapshot.equivalentCarbsGrams,
        snapshot.supportedLowerBoundCarbsGrams,
        snapshot.confidence,
        snapshot.onsetTs,
        snapshot.firstDetectionTs,
        snapshot.activeSinceTs,
        snapshot.supportStableBuckets,
        snapshot.lowerBoundStableBuckets,
        snapshot.sensorTrust,
        snapshot.therapyCoverage,
        snapshot.source,
        snapshot.reasons,
        snapshot.sensitivityRuntime
    )

    private fun snapshotWithForecast(forecast: DoubleArray): UnifiedUamRuntimeSnapshot =
        UnifiedUamRuntimeSnapshot(
            timestamp = 1_760_000_600_000L,
            state = UamRuntimeState.ACTIVE,
            activeForForecast = true,
            activeForControl = true,
            impactMmolPer5m = 0.30,
            signedResidualMmolPer5m = 0.30,
            shortAverageDeltaMmol5 = 0.30,
            forecastStepsStorage = forecast,
            equivalentCarbsGrams = 10.0,
            supportedLowerBoundCarbsGrams = 5.0,
            confidence = 0.90,
            onsetTs = 1_760_000_000_000L,
            firstDetectionTs = 1_760_000_300_000L,
            activeSinceTs = 1_760_000_600_000L,
            supportStableBuckets = 2,
            lowerBoundStableBuckets = 2,
            sensorTrust = 0.95,
            therapyCoverage = 0.90,
            source = "test",
            reasons = setOf("test"),
            sensitivityRuntime = uamSensitivityContext()
        )

    private fun uamSensitivityContext() = io.aaps.copilot.testSensitivityRuntimeContext(
        consumer = SensitivityRuntimeConsumer.UAM
    )

    private fun glucose5m(nowTs: Long, vararg values: Double): List<GlucosePoint> =
        values.mapIndexed { index, value ->
            GlucosePoint(
                ts = nowTs - (values.lastIndex - index) * 5L * 60_000L,
                valueMmol = value,
                source = "test"
            )
        }
}
