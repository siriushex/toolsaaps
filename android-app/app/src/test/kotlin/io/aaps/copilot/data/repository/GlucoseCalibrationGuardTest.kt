package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.aaps.copilot.domain.model.BloodGlucoseCheck
import io.aaps.copilot.domain.model.BloodGlucoseCheckStatus
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import org.junit.Test

class GlucoseCalibrationGuardTest {

    @Test
    fun qualityTimestampPrefersLagAlignedTime() {
        val check = bloodGlucoseCheck(
            timestamp = 1_000L,
            lagAlignedTs = 61_000L
        )

        assertThat(calibrationQualityTimestamp(check)).isEqualTo(61_000L)
    }

    @Test
    fun qualityTimestampFallsBackForLegacyCheck() {
        val check = bloodGlucoseCheck(
            timestamp = 1_000L,
            lagAlignedTs = null
        )

        assertThat(calibrationQualityTimestamp(check)).isEqualTo(1_000L)
    }

    @Test
    fun readsTrustAtLagAlignedTimestamp() {
        val bloodTs = 1_000_000L
        val lagMinutes = 16.0
        val alignedTs = bloodTs + 16 * MINUTE_MS
        var queriedTs: Long? = null

        val result = assessment(
            bloodTs = bloodTs,
            lagMinutes = lagMinutes,
            rawGlucose = listOf(GlucosePoint(alignedTs, 8.0, "test")),
            telemetryAt = { targetTs ->
                queriedTs = targetTs
                safeTrust()
            }
        )

        assertThat(queriedTs).isEqualTo(alignedTs)
        assertThat(result.lagAlignedTs).isEqualTo(alignedTs)
        assertThat(result.effectiveLagMinutes).isEqualTo(lagMinutes)
        assertThat(result.status).isEqualTo(BloodGlucoseCheckStatus.VALID)
    }

    @Test
    fun rejectsBlockedAlignedTrust() {
        val result = assessment(
            trust = safeTrust(sensorBlocked = true)
        )

        assertThat(result.status).isEqualTo(BloodGlucoseCheckStatus.REJECTED)
        assertThat(result.reason).isEqualTo("sensor_blocked_aligned")
    }

    @Test
    fun rejectsSuspectFalseLowAlignedTrust() {
        val result = assessment(
            trust = safeTrust(sensorSuspectFalseLow = true)
        )

        assertThat(result.status).isEqualTo(BloodGlucoseCheckStatus.REJECTED)
        assertThat(result.reason).isEqualTo("sensor_blocked_aligned")
    }

    @Test
    fun rejectsAbsoluteGapAboveThreeMmol() {
        val bloodTs = 1_000_000L

        val result = assessment(
            bloodTs = bloodTs,
            bloodMmol = 11.0,
            rawGlucose = listOf(GlucosePoint(bloodTs, 7.8, "test"))
        )

        assertThat(result.status).isEqualTo(BloodGlucoseCheckStatus.REJECTED)
        assertThat(result.reason).isEqualTo("raw_blood_gap_extreme")
    }

    @Test
    fun rejectsRelativeGapAboveFortyPercent() {
        val bloodTs = 1_000_000L

        val result = assessment(
            bloodTs = bloodTs,
            bloodMmol = 4.0,
            rawGlucose = listOf(GlucosePoint(bloodTs, 6.8, "test"))
        )

        assertThat(result.status).isEqualTo(BloodGlucoseCheckStatus.REJECTED)
        assertThat(result.reason).isEqualTo("raw_blood_gap_extreme")
        assertThat(result.absoluteGapMmol).isWithin(1e-9).of(2.8)
        assertThat(result.relativeGap).isWithin(1e-9).of(2.8 / 6.8)
    }

    @Test
    fun noSafeMatchIsOutOfWindow() {
        val bloodTs = 1_000_000L

        val result = assessment(
            bloodTs = bloodTs,
            rawGlucose = listOf(GlucosePoint(bloodTs + 4 * MINUTE_MS, 8.0, "test"))
        )

        assertThat(result.status).isEqualTo(BloodGlucoseCheckStatus.OUT_OF_WINDOW)
        assertThat(result.reason).isEqualTo("aligned_glucose_gap")
        assertThat(result.matchedRaw).isNull()
    }

    @Test
    fun staleAlignedTrustIsStale() {
        val result = assessment(
            trust = safeTrust(stale = true)
        )

        assertThat(result.status).isEqualTo(BloodGlucoseCheckStatus.STALE)
        assertThat(result.reason).isEqualTo("stale_sensor_context")
    }

    @Test
    fun unavailableAlignedTrustIsStale() {
        val result = assessment(
            trust = safeTrust(available = false)
        )

        assertThat(result.status).isEqualTo(BloodGlucoseCheckStatus.STALE)
        assertThat(result.reason).isEqualTo("stale_sensor_context")
        assertThat(result.status).isNotEqualTo(BloodGlucoseCheckStatus.VALID)
    }

    @Test
    fun nullOrBlankSessionIsOutOfWindow() {
        listOf<String?>(null, "", "   ").forEach { sessionKey ->
            val result = assessment(sessionKey = sessionKey)

            assertWithMessage("sessionKey=$sessionKey")
                .that(result.status)
                .isEqualTo(BloodGlucoseCheckStatus.OUT_OF_WINDOW)
            assertWithMessage("sessionKey=$sessionKey")
                .that(result.reason)
                .isEqualTo("sensor_session_unresolved")
        }
    }

    @Test
    fun thresholdBoundariesRemainInclusive() {
        val bloodTs = 1_000_000L
        val atAbsoluteBoundary = assessment(
            bloodTs = bloodTs,
            bloodMmol = 10.0,
            rawGlucose = listOf(GlucosePoint(bloodTs, 7.0, "test"))
        )
        val atRelativeBoundary = assessment(
            bloodTs = bloodTs,
            bloodMmol = 3.0,
            rawGlucose = listOf(GlucosePoint(bloodTs, 5.0, "test"))
        )

        assertThat(atAbsoluteBoundary.absoluteGapMmol).isEqualTo(3.0)
        assertThat(atAbsoluteBoundary.status).isEqualTo(BloodGlucoseCheckStatus.VALID)
        assertThat(atRelativeBoundary.relativeGap).isEqualTo(0.4)
        assertThat(atRelativeBoundary.status).isEqualTo(BloodGlucoseCheckStatus.VALID)
    }

    @Test
    fun rejectsNonFiniteBloodValues() {
        listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY
        ).forEach { bloodMmol ->
            val result = assessment(
                bloodMmol = bloodMmol,
                rawGlucose = listOf(GlucosePoint(1_000_000L, 8.0, "test"))
            )

            assertWithMessage("bloodMmol=$bloodMmol")
                .that(result.status)
                .isEqualTo(BloodGlucoseCheckStatus.REJECTED)
            assertWithMessage("bloodMmol=$bloodMmol")
                .that(result.reason)
                .isEqualTo("invalid_calibration_input")
            assertWithMessage("bloodMmol=$bloodMmol")
                .that(result.status)
                .isNotEqualTo(BloodGlucoseCheckStatus.VALID)
        }
    }

    @Test
    fun nonFiniteLagUsesTenMinuteFallbackForTelemetryLookup() {
        val bloodTs = 1_000_000L
        val fallbackAlignedTs = bloodTs + 10 * MINUTE_MS

        listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY
        ).forEach { lagMinutes ->
            var queriedTs: Long? = null
            val result = assessment(
                bloodTs = bloodTs,
                lagMinutes = lagMinutes,
                rawGlucose = listOf(GlucosePoint(fallbackAlignedTs, 8.0, "test")),
                telemetryAt = { targetTs ->
                    queriedTs = targetTs
                    safeTrust()
                }
            )

            assertWithMessage("lagMinutes=$lagMinutes").that(queriedTs).isEqualTo(fallbackAlignedTs)
            assertWithMessage("lagMinutes=$lagMinutes")
                .that(result.effectiveLagMinutes)
                .isEqualTo(10.0)
            assertWithMessage("lagMinutes=$lagMinutes")
                .that(result.status)
                .isEqualTo(BloodGlucoseCheckStatus.VALID)
        }
    }

    @Test
    fun finiteLagRemainsClampedToZeroAndTwentyMinutes() {
        val bloodTs = 1_000_000L

        listOf(
            -5.0 to 0.0,
            25.0 to 20.0
        ).forEach { (lagMinutes, expectedLagMinutes) ->
            val alignedTs = bloodTs + (expectedLagMinutes * MINUTE_MS).toLong()
            var queriedTs: Long? = null
            val result = assessment(
                bloodTs = bloodTs,
                lagMinutes = lagMinutes,
                rawGlucose = listOf(GlucosePoint(alignedTs, 8.0, "test")),
                telemetryAt = { targetTs ->
                    queriedTs = targetTs
                    safeTrust()
                }
            )

            assertWithMessage("lagMinutes=$lagMinutes").that(queriedTs).isEqualTo(alignedTs)
            assertWithMessage("lagMinutes=$lagMinutes")
                .that(result.effectiveLagMinutes)
                .isEqualTo(expectedLagMinutes)
            assertWithMessage("lagMinutes=$lagMinutes")
                .that(result.status)
                .isEqualTo(BloodGlucoseCheckStatus.VALID)
        }
    }

    @Test
    fun rejectsNegativeBloodTimestampWithoutTelemetryLookup() {
        var telemetryCalls = 0

        val result = assessment(
            bloodTs = -1L,
            rawGlucose = emptyList(),
            telemetryAt = {
                telemetryCalls += 1
                safeTrust()
            }
        )

        assertThat(result.status).isEqualTo(BloodGlucoseCheckStatus.REJECTED)
        assertThat(result.reason).isEqualTo("invalid_calibration_input")
        assertNullAlignedTimestamp(result.lagAlignedTs)
        assertThat(telemetryCalls).isEqualTo(0)
    }

    @Test
    fun rejectsLagAlignmentOverflowWithoutTelemetryLookup() {
        var telemetryCalls = 0

        val result = assessment(
            bloodTs = Long.MAX_VALUE - 5 * MINUTE_MS,
            lagMinutes = 10.0,
            rawGlucose = emptyList(),
            telemetryAt = {
                telemetryCalls += 1
                safeTrust()
            }
        )

        assertThat(result.status).isEqualTo(BloodGlucoseCheckStatus.REJECTED)
        assertThat(result.reason).isEqualTo("invalid_calibration_input")
        assertNullAlignedTimestamp(result.lagAlignedTs)
        assertThat(telemetryCalls).isEqualTo(0)
    }

    @Test
    fun exactSensorErrorSampleIsNotMatchable() {
        val target = 1_000_000L

        val matched = GlucoseCalibrationGuard.matchRawGlucoseAt(
            glucose = listOf(
                GlucosePoint(target, 8.0, "test", quality = DataQuality.SENSOR_ERROR)
            ),
            targetTs = target
        )

        assertThat(matched).isNull()
    }

    @Test
    fun sensorErrorInsideOtherwiseValidBracketBlocksInterpolation() {
        val target = 1_000_000L

        val matched = GlucoseCalibrationGuard.matchRawGlucoseAt(
            glucose = listOf(
                GlucosePoint(target - 2 * MINUTE_MS, 8.0, "test"),
                GlucosePoint(target, 3.0, "test", quality = DataQuality.SENSOR_ERROR),
                GlucosePoint(target + 3 * MINUTE_MS, 10.0, "test")
            ),
            targetTs = target
        )

        assertThat(matched).isNull()
    }

    @Test
    fun exactStaleRawSampleProducesStaleAssessment() {
        val bloodTs = 1_000_000L

        val result = assessment(
            bloodTs = bloodTs,
            rawGlucose = listOf(
                GlucosePoint(bloodTs, 8.0, "test", quality = DataQuality.STALE)
            )
        )

        assertThat(result.status).isEqualTo(BloodGlucoseCheckStatus.STALE)
        assertThat(result.reason).isEqualTo("stale_sensor_context")
    }

    @Test
    fun interpolationPropagatesWorstRawQualityAndStaleAssessment() {
        val bloodTs = 1_000_000L

        val result = assessment(
            bloodTs = bloodTs,
            bloodMmol = 8.8,
            rawGlucose = listOf(
                GlucosePoint(bloodTs - 2 * MINUTE_MS, 8.0, "test", quality = DataQuality.OK),
                GlucosePoint(bloodTs + 3 * MINUTE_MS, 10.0, "test", quality = DataQuality.STALE)
            )
        )

        assertThat(requireNotNull(result.matchedRaw).quality).isEqualTo(DataQuality.STALE)
        assertThat(result.status).isEqualTo(BloodGlucoseCheckStatus.STALE)
        assertThat(result.reason).isEqualTo("stale_sensor_context")
    }

    @Test
    fun interpolatesNormalFiveMinuteBracket() {
        val target = 1_000_000L
        val glucose = listOf(
            GlucosePoint(target - 2 * MINUTE_MS, 8.0, "test"),
            GlucosePoint(target + 3 * MINUTE_MS, 10.0, "test")
        )

        val matched = requireNotNull(GlucoseCalibrationGuard.matchRawGlucoseAt(glucose, target))

        assertThat(matched.ts).isEqualTo(target)
        assertThat(matched.valueMmol).isWithin(1e-9).of(8.8)
    }

    @Test
    fun rejectsNineMinuteFalseLowBracket() {
        val target = 1_000_000L
        val glucose = listOf(
            GlucosePoint(target - 5 * MINUTE_MS, 3.0, "test"),
            GlucosePoint(target + 4 * MINUTE_MS, 8.0, "test")
        )

        val matched = GlucoseCalibrationGuard.matchRawGlucoseAt(glucose, target)

        assertThat(matched).isNull()
    }

    @Test
    fun rejectsNearestSampleBeyondThreeMinutes() {
        val target = 1_000_000L
        val glucose = listOf(
            GlucosePoint(target - 4 * MINUTE_MS, 8.0, "test")
        )

        val matched = GlucoseCalibrationGuard.matchRawGlucoseAt(glucose, target)

        assertThat(matched).isNull()
    }

    @Test
    fun rejectsLongMinTimestampOverflow() {
        val matched = GlucoseCalibrationGuard.matchRawGlucoseAt(
            glucose = listOf(GlucosePoint(Long.MIN_VALUE, 8.0, "test")),
            targetTs = 0L
        )

        assertThat(matched).isNull()
    }

    @Test
    fun rejectsNegativeTargetAndSampleTimestamps() {
        val negativeTarget = GlucoseCalibrationGuard.matchRawGlucoseAt(
            glucose = listOf(GlucosePoint(0L, 8.0, "test")),
            targetTs = -1L
        )
        val negativeSample = GlucoseCalibrationGuard.matchRawGlucoseAt(
            glucose = listOf(GlucosePoint(-1L, 8.0, "test")),
            targetTs = 0L
        )

        assertWithMessage("negative target").that(negativeTarget).isNull()
        assertWithMessage("negative sample").that(negativeSample).isNull()
    }

    @Test
    fun interpolationTotalGapHonorsSevenMinuteBoundary() {
        val target = 1_000_000L
        val atLimit = GlucoseCalibrationGuard.matchRawGlucoseAt(
            glucose = listOf(
                GlucosePoint(target - 210_000L, 6.0, "test"),
                GlucosePoint(target + 210_000L, 8.0, "test")
            ),
            targetTs = target
        )
        val beyondLimit = GlucoseCalibrationGuard.matchRawGlucoseAt(
            glucose = listOf(
                GlucosePoint(target - (3 * MINUTE_MS + 1L), 6.0, "test"),
                GlucosePoint(target + 4 * MINUTE_MS, 8.0, "test")
            ),
            targetTs = target
        )

        assertThat(requireNotNull(atLimit).valueMmol).isWithin(1e-9).of(7.0)
        assertThat(beyondLimit).isNull()
    }

    @Test
    fun interpolationHonorsAsymmetricSixMinuteSideBoundary() {
        val target = 1_000_000L
        val atLimit = requireNotNull(
            GlucoseCalibrationGuard.matchRawGlucoseAt(
                glucose = listOf(
                    GlucosePoint(target - 6 * MINUTE_MS, 7.0, "test"),
                    GlucosePoint(target + MINUTE_MS, 14.0, "test")
                ),
                targetTs = target
            )
        )
        val beyondLimit = requireNotNull(
            GlucoseCalibrationGuard.matchRawGlucoseAt(
                glucose = listOf(
                    GlucosePoint(target - (6 * MINUTE_MS + 1L), 7.0, "test"),
                    GlucosePoint(target + (MINUTE_MS - 1L), 14.0, "test")
                ),
                targetTs = target
            )
        )

        assertThat(atLimit.ts).isEqualTo(target)
        assertThat(atLimit.valueMmol).isWithin(1e-9).of(13.0)
        assertThat(beyondLimit.ts).isEqualTo(target)
        assertThat(beyondLimit.valueMmol).isWithin(1e-9).of(14.0)
    }

    @Test
    fun nearestMatchHonorsThreeMinuteBoundary() {
        val target = 1_000_000L
        val atLimit = GlucoseCalibrationGuard.matchRawGlucoseAt(
            glucose = listOf(GlucosePoint(target + 3 * MINUTE_MS, 8.0, "test")),
            targetTs = target
        )
        val beyondLimit = GlucoseCalibrationGuard.matchRawGlucoseAt(
            glucose = listOf(GlucosePoint(target + 3 * MINUTE_MS + 1L, 8.0, "test")),
            targetTs = target
        )

        assertThat(requireNotNull(atLimit).ts).isEqualTo(target)
        assertThat(beyondLimit).isNull()
    }

    @Test
    fun returnsExactMatch() {
        val target = 1_000_000L
        val exact = GlucosePoint(target, 8.0, "test")

        val matched = GlucoseCalibrationGuard.matchRawGlucoseAt(
            glucose = listOf(
                GlucosePoint(target - MINUTE_MS, 7.0, "test"),
                exact,
                GlucosePoint(target + MINUTE_MS, 9.0, "test")
            ),
            targetTs = target
        )

        assertThat(matched).isEqualTo(exact)
    }

    @Test
    fun ignoresNonPositiveAndNonFiniteValues() {
        val target = 1_000_000L

        listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            0.0,
            -1.0
        ).forEach { invalidValue ->
            val matched = GlucoseCalibrationGuard.matchRawGlucoseAt(
                glucose = listOf(GlucosePoint(target, invalidValue, "test")),
                targetTs = target
            )

            assertWithMessage("valueMmol=$invalidValue").that(matched).isNull()
        }
    }

    @Test
    fun sortsUnsortedInputBeforeInterpolation() {
        val target = 1_000_000L
        val glucose = listOf(
            GlucosePoint(target + 3 * MINUTE_MS, 10.0, "test"),
            GlucosePoint(target - 2 * MINUTE_MS, 8.0, "test")
        )

        val matched = requireNotNull(GlucoseCalibrationGuard.matchRawGlucoseAt(glucose, target))

        assertThat(matched.valueMmol).isWithin(1e-9).of(8.8)
    }

    @Test
    fun duplicateTimestampUsesGlucoseSanitizerWinner() {
        val target = 1_000_000L
        val preferred = GlucosePoint(target, 9.0, "nightscout")
        val glucose = listOf(
            GlucosePoint(target, 4.0, "test"),
            preferred
        )

        val matched = GlucoseCalibrationGuard.matchRawGlucoseAt(glucose, target)

        assertThat(matched).isEqualTo(preferred)
    }

    @Test
    fun preparedGlucoseIndexIsReusableAcrossCalibrationChecks() {
        val firstTarget = 1_000_000L
        val secondTarget = 2_000_000L
        val prepared = GlucoseCalibrationGuard.prepareRawGlucose(
            listOf(
                GlucosePoint(secondTarget + 3 * MINUTE_MS, 10.0, "test"),
                GlucosePoint(firstTarget, 7.5, "test"),
                GlucosePoint(secondTarget - 2 * MINUTE_MS, 8.0, "test")
            )
        )

        val first = GlucoseCalibrationGuard.matchPreparedRawGlucoseAt(prepared, firstTarget)
        val second = GlucoseCalibrationGuard.matchPreparedRawGlucoseAt(prepared, secondTarget)

        assertThat(requireNotNull(first).valueMmol).isEqualTo(7.5)
        assertThat(requireNotNull(second).valueMmol).isWithin(1e-9).of(8.8)
    }

    private fun bloodGlucoseCheck(
        timestamp: Long,
        lagAlignedTs: Long?
    ): BloodGlucoseCheck = BloodGlucoseCheck(
        id = "bgc-fixture",
        timestamp = timestamp,
        mmol = 7.8,
        units = "mmol/L",
        source = "MANUAL",
        note = "fixture",
        enteredAt = timestamp + 500L,
        sensorSessionKey = "sensor-1",
        lagAlignedTs = lagAlignedTs,
        matchedRawGlucose = 7.6,
        status = BloodGlucoseCheckStatus.VALID,
        reason = "aligned"
    )

    private fun assessment(
        sessionKey: String? = "sensor-session",
        bloodTs: Long = 1_000_000L,
        bloodMmol: Double = 8.0,
        lagMinutes: Double = 0.0,
        rawGlucose: List<GlucosePoint> = listOf(GlucosePoint(bloodTs, bloodMmol, "test")),
        trust: CalibrationTrustSnapshot = safeTrust(),
        telemetryAt: ((Long) -> CalibrationTrustSnapshot)? = null
    ): CalibrationCheckAssessment = GlucoseCalibrationGuard.assess(
        sessionKey = sessionKey,
        bloodTs = bloodTs,
        bloodMmol = bloodMmol,
        lagMinutes = lagMinutes,
        rawGlucose = rawGlucose,
        telemetryAt = telemetryAt ?: { trust }
    )

    private fun safeTrust(
        sensorBlocked: Boolean = false,
        sensorSuspectFalseLow: Boolean = false,
        stale: Boolean = false,
        available: Boolean = true
    ): CalibrationTrustSnapshot = CalibrationTrustSnapshot(
        sensorBlocked = sensorBlocked,
        sensorSuspectFalseLow = sensorSuspectFalseLow,
        stale = stale,
        available = available
    )

    private fun assertNullAlignedTimestamp(timestamp: Long?) {
        assertThat(timestamp).isNull()
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
