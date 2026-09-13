package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.BloodGlucoseCheck
import io.aaps.copilot.domain.model.BloodGlucoseCheckStatus
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucoseCalibrationModel
import io.aaps.copilot.domain.model.GlucoseCalibrationModelStatus
import io.aaps.copilot.domain.model.GlucoseCalibrationModelType
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import org.junit.Test

class GlucoseCalibrationRepositoryTest {

    @Test
    fun currentBloodCheckUsesProvisionalRawUntilLagAlignedPointArrives() {
        val bloodTs = 2_000_000L
        val delayed = calibrationAssessment(
            lagAlignedTs = bloodTs + 10L * 60_000L,
            matchedRaw = null,
            status = BloodGlucoseCheckStatus.OUT_OF_WINDOW,
            reason = "aligned_glucose_gap"
        )
        val immediate = calibrationAssessment(
            lagAlignedTs = bloodTs,
            matchedRaw = GlucosePoint(
                ts = bloodTs,
                valueMmol = 4.2,
                source = "aaps",
                quality = DataQuality.OK
            ),
            status = BloodGlucoseCheckStatus.VALID,
            reason = "aligned"
        )

        val selected = selectInitialCalibrationAssessment(
            delayed = delayed,
            immediate = immediate,
            enteredAt = bloodTs + 30_000L
        )

        assertThat(selected.status).isEqualTo(BloodGlucoseCheckStatus.VALID)
        assertThat(selected.reason).isEqualTo(PROVISIONAL_CURRENT_RAW_REASON)
        assertThat(selected.lagAlignedTs).isEqualTo(delayed.lagAlignedTs)
        assertThat(selected.effectiveLagMinutes).isEqualTo(10.0)
        assertThat(selected.matchedRaw?.valueMmol).isEqualTo(4.2)
    }

    @Test
    fun rejectedImmediateRawDoesNotCreateProvisionalCalibration() {
        val bloodTs = 2_000_000L
        val delayed = calibrationAssessment(
            lagAlignedTs = bloodTs + 10L * 60_000L,
            matchedRaw = null,
            status = BloodGlucoseCheckStatus.OUT_OF_WINDOW,
            reason = "aligned_glucose_gap"
        )
        val immediate = calibrationAssessment(
            lagAlignedTs = bloodTs,
            matchedRaw = GlucosePoint(
                ts = bloodTs,
                valueMmol = 2.8,
                source = "aaps",
                quality = DataQuality.OK
            ),
            status = BloodGlucoseCheckStatus.REJECTED,
            reason = "raw_blood_gap_extreme"
        )

        val selected = selectInitialCalibrationAssessment(
            delayed = delayed,
            immediate = immediate,
            enteredAt = bloodTs + 30_000L
        )

        assertThat(selected).isEqualTo(delayed)
    }

    @Test
    fun provisionalCheckSurvivesMissingFuturePointButUsesAlignedResultWhenAvailable() {
        val bloodTs = 2_000_000L
        val alignedTs = bloodTs + 10L * 60_000L
        val provisional = bloodGlucoseCheck(
            timestamp = bloodTs,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = alignedTs
        ).copy(
            matchedRawGlucose = 4.2,
            reason = PROVISIONAL_CURRENT_RAW_REASON
        )
        val stillMissing = provisional.copy(
            matchedRawGlucose = null,
            status = BloodGlucoseCheckStatus.OUT_OF_WINDOW,
            reason = "aligned_glucose_gap"
        )
        val aligned = provisional.copy(
            matchedRawGlucose = 4.8,
            reason = "aligned"
        )

        assertThat(
            selectReassessedCalibrationCheck(
                previous = provisional,
                reassessed = stillMissing,
                nowTs = alignedTs + 2L * 60_000L
            )
        ).isEqualTo(provisional)
        assertThat(
            selectReassessedCalibrationCheck(
                previous = provisional,
                reassessed = aligned,
                nowTs = alignedTs + 2L * 60_000L
            )
        ).isEqualTo(aligned)
    }

    @Test
    fun provisionalCheckIgnoresCurrentSensorBlockUntilAlignedTimeExists() {
        val bloodTs = 2_000_000L
        val alignedTs = bloodTs + 10L * 60_000L
        val provisional = bloodGlucoseCheck(
            timestamp = bloodTs,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = alignedTs
        ).copy(
            matchedRawGlucose = 4.2,
            reason = PROVISIONAL_CURRENT_RAW_REASON
        )
        val blockedWithoutAlignedRaw = provisional.copy(
            matchedRawGlucose = null,
            status = BloodGlucoseCheckStatus.REJECTED,
            reason = "sensor_blocked_aligned"
        )

        val beforeAlignedTime = selectReassessedCalibrationCheck(
            previous = provisional,
            reassessed = blockedWithoutAlignedRaw,
            nowTs = alignedTs - 1L
        )
        val atAlignedTime = selectReassessedCalibrationCheck(
            previous = provisional,
            reassessed = blockedWithoutAlignedRaw,
            nowTs = alignedTs
        )

        assertThat(beforeAlignedTime).isEqualTo(provisional)
        assertThat(atAlignedTime).isEqualTo(blockedWithoutAlignedRaw)
    }

    @Test
    fun manuallyResetCheckCannotBeReactivatedByReassessment() {
        val reset = bloodGlucoseCheck(
            timestamp = 2_000_000L,
            status = BloodGlucoseCheckStatus.REJECTED,
            lagAlignedTs = 2_600_000L
        ).copy(reason = MANUAL_CALIBRATION_RESET_REASON)
        val reassessed = reset.copy(
            status = BloodGlucoseCheckStatus.VALID,
            reason = "aligned",
            matchedRawGlucose = 5.0
        )

        val selected = selectReassessedCalibrationCheck(
            previous = reset,
            reassessed = reassessed,
            nowTs = 3_000_000L
        )

        assertThat(selected).isEqualTo(reset)
    }

    @Test
    fun newestProvisionalCheckTemporarilyOwnsImmediateOffsetFit() {
        val older = bloodGlucoseCheck(
            timestamp = 1_000_000L,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = 1_600_000L
        ).copy(id = "older", reason = "aligned", matchedRawGlucose = 5.0)
        val provisional = bloodGlucoseCheck(
            timestamp = 2_000_000L,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = 2_600_000L
        ).copy(
            id = "latest",
            reason = PROVISIONAL_CURRENT_RAW_REASON,
            matchedRawGlucose = 4.2
        )

        val selected = selectChecksForCalibrationFit(listOf(older, provisional))

        assertThat(selected).containsExactly(provisional)
    }

    @Test
    fun newestAlignedCalibrationRegimeDoesNotCollapseIntoOlderContradictoryOffsets() {
        val oldNearZero = bloodGlucoseCheck(
            timestamp = 1_000_000L,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = 1_600_000L
        ).copy(
            id = "old-near-zero",
            reason = "aligned",
            mmol = 7.0,
            matchedRawGlucose = 7.1
        )
        val recentFirst = bloodGlucoseCheck(
            timestamp = 2_000_000L,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = 2_600_000L
        ).copy(
            id = "recent-first",
            reason = "aligned",
            mmol = 10.0,
            matchedRawGlucose = 7.7
        )
        val recentLatest = bloodGlucoseCheck(
            timestamp = 2_300_000L,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = 2_900_000L
        ).copy(
            id = "recent-latest",
            reason = "aligned",
            mmol = 10.2,
            matchedRawGlucose = 7.8
        )

        val selected = selectChecksForCalibrationFit(
            listOf(oldNearZero, recentFirst, recentLatest)
        )

        assertThat(selected).containsExactly(recentFirst, recentLatest).inOrder()
    }

    @Test
    fun newestAlignedCalibrationRegimeKeepsOnlyBoundedRecentEvidence() {
        val latestTs = 20L * 60L * 60L * 1000L
        val tooOld = bloodGlucoseCheck(
            timestamp = latestTs - 13L * 60L * 60L * 1000L,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = latestTs - 13L * 60L * 60L * 1000L + 600_000L
        ).copy(
            id = "too-old",
            reason = "aligned",
            mmol = 8.0,
            matchedRawGlucose = 6.0
        )
        val recent = bloodGlucoseCheck(
            timestamp = latestTs - 30L * 60L * 1000L,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = latestTs - 20L * 60L * 1000L
        ).copy(
            id = "recent",
            reason = "aligned",
            mmol = 8.2,
            matchedRawGlucose = 6.1
        )
        val latest = bloodGlucoseCheck(
            timestamp = latestTs,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = latestTs + 600_000L
        ).copy(
            id = "latest",
            reason = "aligned",
            mmol = 8.4,
            matchedRawGlucose = 6.2
        )

        val selected = selectChecksForCalibrationFit(listOf(tooOld, recent, latest))

        assertThat(selected).containsExactly(recent, latest).inOrder()
    }

    @Test
    fun rejectedLatestCheckFallsBackToNewestCoherentValidRegime() {
        val oldNearZero = bloodGlucoseCheck(
            timestamp = 1_000_000L,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = 1_600_000L
        ).copy(
            id = "old-near-zero",
            reason = "aligned",
            mmol = 7.0,
            matchedRawGlucose = 7.1
        )
        val recentFirst = bloodGlucoseCheck(
            timestamp = 2_000_000L,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = 2_600_000L
        ).copy(
            id = "recent-first",
            reason = "aligned",
            mmol = 10.0,
            matchedRawGlucose = 7.7
        )
        val recentLatest = bloodGlucoseCheck(
            timestamp = 2_300_000L,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = 2_900_000L
        ).copy(
            id = "recent-latest",
            reason = "aligned",
            mmol = 11.0,
            matchedRawGlucose = 8.8
        )
        val rejectedLatest = bloodGlucoseCheck(
            timestamp = 2_600_000L,
            status = BloodGlucoseCheckStatus.REJECTED,
            lagAlignedTs = 3_200_000L
        ).copy(
            id = "rejected-latest",
            reason = "raw_blood_gap_extreme",
            mmol = 10.0,
            matchedRawGlucose = 6.99
        )

        val selected = selectChecksForCalibrationFit(
            listOf(oldNearZero, recentFirst, recentLatest, rejectedLatest)
        )

        assertThat(selected).containsExactly(recentFirst, recentLatest).inOrder()
    }

    @Test
    fun rejectedDiagnosticAfterProvisionalDoesNotMixProvisionalWithAlignedHistory() {
        val aligned = bloodGlucoseCheck(
            timestamp = 1_000_000L,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = 1_600_000L
        ).copy(
            id = "aligned",
            reason = "aligned",
            mmol = 9.0,
            matchedRawGlucose = 7.0
        )
        val provisional = bloodGlucoseCheck(
            timestamp = 2_000_000L,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = 2_600_000L
        ).copy(
            id = "provisional",
            reason = PROVISIONAL_CURRENT_RAW_REASON,
            mmol = 9.5,
            matchedRawGlucose = 7.0
        )
        val rejectedLatest = bloodGlucoseCheck(
            timestamp = 2_100_000L,
            status = BloodGlucoseCheckStatus.REJECTED,
            lagAlignedTs = 2_700_000L
        ).copy(
            id = "rejected-latest",
            reason = "sensor_blocked_aligned",
            mmol = 9.5,
            matchedRawGlucose = 7.1
        )

        val selected = selectChecksForCalibrationFit(
            listOf(aligned, provisional, rejectedLatest)
        )

        assertThat(selected).containsExactly(provisional)
    }

    @Test
    fun sameTimestampCalibrationChecksUseEntryOrderForNewestRegime() {
        val newestEntry = bloodGlucoseCheck(
            timestamp = 2_000_000L,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = 2_600_000L
        ).copy(
            id = "newest-entry",
            enteredAt = 2_000_200L,
            reason = "aligned",
            mmol = 9.0,
            matchedRawGlucose = 7.0
        )
        val olderEntry = bloodGlucoseCheck(
            timestamp = 2_000_000L,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = 2_600_000L
        ).copy(
            id = "older-entry",
            enteredAt = 2_000_100L,
            reason = "aligned",
            mmol = 7.1,
            matchedRawGlucose = 7.0
        )

        val selected = selectChecksForCalibrationFit(listOf(newestEntry, olderEntry))

        assertThat(selected).containsExactly(newestEntry)
    }

    @Test
    fun recentValidBloodCheckProvidesSessionWhenSensorAgeIsUnavailable() {
        val nowTs = 10_000_000L
        val recentCheck = bloodGlucoseCheck(
            timestamp = nowTs - 5L * 60_000L,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = nowTs
        ).copy(sensorSessionKey = "sensor-current")

        val resolved = resolveCurrentCalibrationSessionKey(
            ageSamples = emptyList(),
            latestCheck = recentCheck,
            nowTs = nowTs
        )

        assertThat(resolved).isEqualTo("sensor-current")
    }

    @Test
    fun recentPendingAlignedCheckProvidesSessionForAutomaticRecovery() {
        val nowTs = 10_000_000L
        val pendingCheck = bloodGlucoseCheck(
            timestamp = nowTs - 5L * 60_000L,
            status = BloodGlucoseCheckStatus.OUT_OF_WINDOW,
            lagAlignedTs = nowTs
        ).copy(
            sensorSessionKey = "sensor-current",
            reason = "aligned_glucose_gap"
        )

        val resolved = resolveCurrentCalibrationSessionKey(
            ageSamples = emptyList(),
            latestCheck = pendingCheck,
            nowTs = nowTs
        )

        assertThat(resolved).isEqualTo("sensor-current")
    }

    @Test
    fun latestRejectedDiagnosticDoesNotHideEarlierCalibrationSessionAnchor() {
        val nowTs = 10_000_000L
        val valid = bloodGlucoseCheck(
            timestamp = nowTs - 20L * 60_000L,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = nowTs - 10L * 60_000L
        ).copy(
            id = "valid",
            sensorSessionKey = "sensor-current",
            reason = "aligned"
        )
        val rejected = bloodGlucoseCheck(
            timestamp = nowTs - 5L * 60_000L,
            status = BloodGlucoseCheckStatus.REJECTED,
            lagAlignedTs = nowTs
        ).copy(
            id = "rejected",
            sensorSessionKey = "sensor-current",
            reason = "sensor_blocked_aligned"
        )

        val selected = selectLatestCalibrationSessionAnchor(listOf(valid, rejected))

        assertThat(selected).isEqualTo(valid)
    }

    @Test
    fun manualResetChecksCannotRestoreAnOlderCalibrationSessionAnchor() {
        val nowTs = 10_000_000L
        val resetChecks = listOf(
            bloodGlucoseCheck(
                timestamp = nowTs - 20L * 60_000L,
                status = BloodGlucoseCheckStatus.REJECTED,
                lagAlignedTs = nowTs - 10L * 60_000L
            ).copy(
                id = "older-reset",
                sensorSessionKey = "sensor-current",
                reason = MANUAL_CALIBRATION_RESET_REASON
            ),
            bloodGlucoseCheck(
                timestamp = nowTs - 5L * 60_000L,
                status = BloodGlucoseCheckStatus.REJECTED,
                lagAlignedTs = nowTs
            ).copy(
                id = "latest-reset",
                sensorSessionKey = "sensor-current",
                reason = MANUAL_CALIBRATION_RESET_REASON
            )
        )

        val selected = selectLatestCalibrationSessionAnchor(resetChecks)

        assertThat(selected).isNull()
    }

    @Test
    fun bloodCheckKeepsCurrentSessionFallbackForCalibrationValidityWindow() {
        val nowTs = 100L * 60L * 60_000L
        val recentCheck = bloodGlucoseCheck(
            timestamp = nowTs - 24L * 60L * 60_000L,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = nowTs - 24L * 60L * 60_000L
        ).copy(sensorSessionKey = "sensor-current")

        val resolved = resolveCurrentCalibrationSessionKey(
            ageSamples = emptyList(),
            latestCheck = recentCheck,
            nowTs = nowTs
        )

        assertThat(resolved).isEqualTo("sensor-current")
    }

    @Test
    fun expiredBloodCheckCannotProvideCurrentSessionFallback() {
        val nowTs = 100L * 60L * 60_000L
        val expiredCheck = bloodGlucoseCheck(
            timestamp = nowTs - 73L * 60L * 60_000L,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = nowTs - 73L * 60L * 60_000L
        ).copy(sensorSessionKey = "sensor-old")

        val resolved = resolveCurrentCalibrationSessionKey(
            ageSamples = emptyList(),
            latestCheck = expiredCheck,
            nowTs = nowTs
        )

        assertThat(resolved).isNull()
    }

    @Test
    fun knownSensorBoundaryDisablesManualCheckSessionFallback() {
        val nowTs = 10_000_000L
        val recentCheck = bloodGlucoseCheck(
            timestamp = nowTs - 5L * 60_000L,
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = nowTs
        ).copy(sensorSessionKey = "sensor-old")

        val resolved = resolveCurrentCalibrationSessionKey(
            ageSamples = emptyList(),
            latestCheck = recentCheck,
            nowTs = nowTs,
            knownBoundaryAfterCheck = true
        )

        assertThat(resolved).isNull()
    }

    @Test
    fun knownSensorBoundaryOverridesStillFreshOldSensorAge() {
        val nowTs = 10_000_000L
        val oldSensorAge = CalibrationSensorAgeSample(
            ts = nowTs,
            ageHours = 1.0
        )

        val resolved = resolveCurrentCalibrationSessionKey(
            ageSamples = listOf(oldSensorAge),
            latestCheck = null,
            nowTs = nowTs,
            knownBoundaryAfterCheck = true
        )

        assertThat(resolved).isNull()
    }

    @Test
    fun importSourceChangeIsNotSensorBoundaryButGapOrExplicitStartIs() {
        val checkTs = 2_000_000L
        val sourceChange = hasKnownCalibrationSessionBoundaryAfterCheck(
            checkTs = checkTs,
            glucose = listOf(
                GlucosePoint(checkTs, 5.0, "old", DataQuality.OK),
                GlucosePoint(checkTs + 5L * 60_000L, 5.1, "new", DataQuality.OK)
            ),
            therapy = emptyList()
        )
        val longGap = hasKnownCalibrationSessionBoundaryAfterCheck(
            checkTs = checkTs,
            glucose = listOf(
                GlucosePoint(checkTs, 5.0, "same", DataQuality.OK),
                GlucosePoint(checkTs + 91L * 60_000L, 5.1, "same", DataQuality.OK)
            ),
            therapy = emptyList()
        )
        val explicitStart = hasKnownCalibrationSessionBoundaryAfterCheck(
            checkTs = checkTs,
            glucose = emptyList(),
            therapy = listOf(
                TherapyEvent(
                    ts = checkTs + 1L,
                    type = "sensor_started",
                    payload = emptyMap()
                )
            )
        )

        assertThat(sourceChange).isFalse()
        assertThat(longGap).isTrue()
        assertThat(explicitStart).isTrue()
    }

    @Test
    fun ageSamplesFromSameSensorResolveStableSessionKey() {
        val sensorStartedAt = 1_800_000L

        val first = calibrationSensorSessionKeyFromAgeSample(
            sampleTs = sensorStartedAt + 24L * 60L * 60L * 1000L,
            ageHours = 24.0
        )
        val second = calibrationSensorSessionKeyFromAgeSample(
            sampleTs = sensorStartedAt + 48L * 60L * 60L * 1000L,
            ageHours = 48.0
        )

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun sensorAgeResetChangesSessionKeyAndBreaksContinuity() {
        val previous = calibrationSensorSessionKeyFromAgeSample(
            sampleTs = 200L * 60L * 60L * 1000L,
            ageHours = 120.0
        )
        val replacement = calibrationSensorSessionKeyFromAgeSample(
            sampleTs = 200L * 60L * 60L * 1000L,
            ageHours = 1.0
        )

        assertThat(previous).isNotEqualTo(replacement)
        assertThat(
            isCalibrationSessionContinuous(
                currentSessionKey = replacement,
                latestCheckSessionKey = previous
            )
        ).isFalse()
    }

    @Test
    fun smallAgeMeasurementJitterDoesNotCreateNewSession() {
        val sensorStartedAt = 1_800_000L
        val first = calibrationSensorSessionKeyFromAgeSample(
            sampleTs = sensorStartedAt + 24L * 60L * 60L * 1000L + 5_000L,
            ageHours = 24.0
        )
        val second = calibrationSensorSessionKeyFromAgeSample(
            sampleTs = sensorStartedAt + 48L * 60L * 60L * 1000L - 5_000L,
            ageHours = 48.0
        )

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun unresolvedCurrentSessionBreaksContinuityFailClosed() {
        assertThat(
            isCalibrationSessionContinuous(
                currentSessionKey = null,
                latestCheckSessionKey = "sensor-1"
            )
        ).isFalse()
    }

    @Test
    fun conflictingSensorAgeSamplesResolveSessionFailClosed() {
        val samples = listOf(
            CalibrationSensorAgeSample(ts = 200L * 60L * 60L * 1000L, ageHours = 120.0),
            CalibrationSensorAgeSample(ts = 200L * 60L * 60L * 1000L, ageHours = 1.0)
        )

        assertThat(resolveConsistentCalibrationSensorSessionKey(samples)).isNull()
    }

    @Test
    fun duplicateAgeSourcesForSameSensorResolveOneSession() {
        val sensorStartedAt = 1_800_000L
        val samples = listOf(
            CalibrationSensorAgeSample(
                ts = sensorStartedAt + 24L * 60L * 60L * 1000L,
                ageHours = 24.0
            ),
            CalibrationSensorAgeSample(
                ts = sensorStartedAt + 48L * 60L * 60L * 1000L,
                ageHours = 48.0
            )
        )

        assertThat(resolveConsistentCalibrationSensorSessionKey(samples)).isNotNull()
    }

    @Test
    fun missingDerivedAgeDoesNotPoisonValidSensorAgeSession() {
        val sensorStartedAt = 1_800_000L
        val samples = listOf(
            CalibrationSensorAgeSample(
                ts = sensorStartedAt + 24L * 60L * 60L * 1000L,
                ageHours = 24.0
            ),
            CalibrationSensorAgeSample(
                ts = sensorStartedAt + 24L * 60L * 60L * 1000L,
                ageHours = null
            )
        )

        assertThat(resolveConsistentCalibrationSensorSessionKey(samples)).isNotNull()
    }

    @Test
    fun lagEstimateAgeIsNotASensorSessionAgeSource() {
        assertThat(isCalibrationSensorSessionAgeKey("sensor_age_hours")).isTrue()
        assertThat(isCalibrationSensorSessionAgeKey("isf_factor_sensor_age_hours")).isTrue()
        assertThat(isCalibrationSensorSessionAgeKey("sensor_lag_age_hours")).isFalse()
    }

    @Test
    fun equivalentModelsAreReusable() {
        val existing = model()
        val candidate = model(id = "gcm-2", createdAt = 2_000L, validToTs = 200_000L, confidence = 0.84)

        assertThat(areEquivalentModelsForReuse(existing, candidate)).isTrue()
    }

    @Test
    fun changedOffsetBreaksReuse() {
        val existing = model()
        val candidate = model(id = "gcm-2", offsetMmol = -0.31)

        assertThat(areEquivalentModelsForReuse(existing, candidate)).isFalse()
    }

    @Test
    fun changedCheckCountBreaksReuse() {
        val existing = model()
        val candidate = model(id = "gcm-2", checkCount = 2)

        assertThat(areEquivalentModelsForReuse(existing, candidate)).isFalse()
    }

    @Test
    fun recentUnchangedCalibrationCanReuseMemoizedModel() {
        val watermark = calibrationMemoWatermark()
        val canReuse = shouldReuseRecentCalibrationModelStatic(
            nowTs = 700_000L,
            latestCheckTs = 500_000L,
            latestModelId = "gcm-1",
            memoComputedAt = 650_000L,
            memoLatestCheckTs = 500_000L,
            memoModelId = "gcm-1",
            currentInputWatermark = watermark,
            memoInputWatermark = watermark
        )

        assertThat(canReuse).isTrue()
    }

    @Test
    fun changedModelOrCheckBreaksMemoizedReuse() {
        val watermark = calibrationMemoWatermark()
        val changedCheck = shouldReuseRecentCalibrationModelStatic(
            nowTs = 700_000L,
            latestCheckTs = 501_000L,
            latestModelId = "gcm-1",
            memoComputedAt = 650_000L,
            memoLatestCheckTs = 500_000L,
            memoModelId = "gcm-1",
            currentInputWatermark = watermark,
            memoInputWatermark = watermark
        )
        val changedModel = shouldReuseRecentCalibrationModelStatic(
            nowTs = 700_000L,
            latestCheckTs = 500_000L,
            latestModelId = "gcm-2",
            memoComputedAt = 650_000L,
            memoLatestCheckTs = 500_000L,
            memoModelId = "gcm-1",
            currentInputWatermark = watermark,
            memoInputWatermark = watermark
        )

        assertThat(changedCheck).isFalse()
        assertThat(changedModel).isFalse()
    }

    @Test
    fun clockRollbackBreaksMemoizedReuse() {
        val watermark = calibrationMemoWatermark()
        val canReuse = shouldReuseRecentCalibrationModelStatic(
            nowTs = 600_000L,
            latestCheckTs = 500_000L,
            latestModelId = "gcm-1",
            memoComputedAt = 650_000L,
            memoLatestCheckTs = 500_000L,
            memoModelId = "gcm-1",
            currentInputWatermark = watermark,
            memoInputWatermark = watermark
        )

        assertThat(canReuse).isFalse()
    }

    @Test
    fun changedScopedInputWatermarkBreaksMemoizedReuse() {
        val memoWatermark = calibrationMemoWatermark(glucoseGeneration = 10L)
        val currentWatermark = calibrationMemoWatermark(glucoseGeneration = 11L)

        val canReuse = shouldReuseRecentCalibrationModelStatic(
            nowTs = 700_000L,
            latestCheckTs = 500_000L,
            latestModelId = "gcm-1",
            memoComputedAt = 650_000L,
            memoLatestCheckTs = 500_000L,
            memoModelId = "gcm-1",
            currentInputWatermark = currentWatermark,
            memoInputWatermark = memoWatermark
        )

        assertThat(canReuse).isFalse()
    }

    @Test
    fun identicalScopedInputWatermarkAllowsMemoizedReuse() {
        val watermark = calibrationMemoWatermark()

        val canReuse = shouldReuseRecentCalibrationModelStatic(
            nowTs = 700_000L,
            latestCheckTs = 500_000L,
            latestModelId = "gcm-1",
            memoComputedAt = 650_000L,
            memoLatestCheckTs = 500_000L,
            memoModelId = "gcm-1",
            currentInputWatermark = watermark,
            memoInputWatermark = watermark
        )

        assertThat(canReuse).isTrue()
    }

    @Test
    fun calibrationInputScopeStopsAtAlignedMatchWindow() {
        val scope = calibrationInputScope(
            nowTs = 10_000_000L,
            recentChecks = listOf(
                bloodGlucoseCheck(
                    timestamp = 400_000L,
                    status = BloodGlucoseCheckStatus.VALID,
                    lagAlignedTs = 1_000_000L
                )
            )
        )

        assertThat(scope.relevantThroughTs).isEqualTo(1_360_000L)
    }

    @Test
    fun calibrationInputScopeUsesBloodTimestampBeforePendingWindowAndSupportsLegacyChecks() {
        val futureScope = calibrationInputScope(
            nowTs = 10_000_000L,
            recentChecks = listOf(
                bloodGlucoseCheck(
                    timestamp = 400_000L,
                    status = BloodGlucoseCheckStatus.OUT_OF_WINDOW,
                    lagAlignedTs = 20_000_000L
                )
            )
        )
        val legacyScope = calibrationInputScope(
            nowTs = 10_000_000L,
            recentChecks = listOf(
                bloodGlucoseCheck(
                    timestamp = 1_000_000L,
                    status = BloodGlucoseCheckStatus.STALE,
                    lagAlignedTs = null
                )
            )
        )

        assertThat(futureScope.relevantThroughTs).isEqualTo(400_000L)
        assertThat(legacyScope.relevantThroughTs).isEqualTo(1_960_000L)
    }

    @Test
    fun pendingPreWindowMemoWatermarkIsStableAcrossNowValues() {
        val pendingCheck = bloodGlucoseCheck(
            timestamp = 400_000L,
            status = BloodGlucoseCheckStatus.OUT_OF_WINDOW,
            lagAlignedTs = 2_000_000L
        )
        val firstScope = calibrationInputScope(
            nowTs = 700_000L,
            recentChecks = listOf(pendingCheck)
        )
        val secondScope = calibrationInputScope(
            nowTs = 750_000L,
            recentChecks = listOf(pendingCheck)
        )
        val firstWatermark = calibrationMemoWatermark(glucoseGeneration = 10L)
        val secondWatermark = calibrationMemoWatermark(glucoseGeneration = 10L)

        assertThat(firstScope.relevantThroughTs).isEqualTo(400_000L)
        assertThat(secondScope.relevantThroughTs).isEqualTo(firstScope.relevantThroughTs)
        assertThat(firstWatermark).isEqualTo(secondWatermark)
        assertThat(
            shouldReuseRecentCalibrationModelStatic(
                nowTs = 750_000L,
                latestCheckTs = pendingCheck.timestamp,
                latestModelId = "gcm-1",
                memoComputedAt = 700_000L,
                memoLatestCheckTs = pendingCheck.timestamp,
                memoModelId = "gcm-1",
                currentInputWatermark = secondWatermark,
                memoInputWatermark = firstWatermark
            )
        ).isTrue()
    }

    @Test
    fun futurePendingCheckDoesNotHideOlderMatchableHorizon() {
        val scope = calibrationInputScope(
            nowTs = 2_000_000L,
            recentChecks = listOf(
                bloodGlucoseCheck(
                    timestamp = 500_000L,
                    status = BloodGlucoseCheckStatus.VALID,
                    lagAlignedTs = 1_500_000L
                ),
                bloodGlucoseCheck(
                    timestamp = 1_000_000L,
                    status = BloodGlucoseCheckStatus.OUT_OF_WINDOW,
                    lagAlignedTs = 10_000_000L
                )
            )
        )

        assertThat(scope.relevantThroughTs).isEqualTo(1_860_000L)
    }

    @Test
    fun pendingAlignmentBypassesMemoUntilGraceWindowPasses() {
        listOf(
            BloodGlucoseCheckStatus.OUT_OF_WINDOW,
            BloodGlucoseCheckStatus.STALE
        ).forEach { status ->
            val check = bloodGlucoseCheck(
                status = status,
                lagAlignedTs = 1_000_000L
            )

            assertThat(
                shouldBypassCalibrationMemoForPendingAlignment(
                    nowTs = 1_359_999L,
                    latestCheck = check
                )
            ).isTrue()
            assertThat(
                shouldBypassCalibrationMemoForPendingAlignment(
                    nowTs = 1_360_000L,
                    latestCheck = check
                )
            ).isFalse()
        }
    }

    @Test
    fun pendingAlignmentDoesNotBypassBeforeMatchableWindow() {
        val check = bloodGlucoseCheck(
            status = BloodGlucoseCheckStatus.OUT_OF_WINDOW,
            lagAlignedTs = 1_000_000L
        )

        assertThat(
            shouldBypassCalibrationMemoForPendingAlignment(
                nowTs = 639_999L,
                latestCheck = check
            )
        ).isFalse()
        assertThat(
            shouldBypassCalibrationMemoForPendingAlignment(
                nowTs = 640_000L,
                latestCheck = check
            )
        ).isTrue()
    }

    @Test
    fun provisionalAlignmentBypassesMemoFromItsDeadlineUntilReassessment() {
        val check = bloodGlucoseCheck(
            status = BloodGlucoseCheckStatus.VALID,
            lagAlignedTs = 1_000_000L
        ).copy(reason = PROVISIONAL_CURRENT_RAW_REASON)

        assertThat(
            shouldBypassCalibrationMemoForPendingAlignment(
                nowTs = 999_999L,
                latestCheck = check
            )
        ).isFalse()
        assertThat(
            shouldBypassCalibrationMemoForPendingAlignment(
                nowTs = 1_000_000L,
                latestCheck = check
            )
        ).isTrue()
        assertThat(
            shouldBypassCalibrationMemoForPendingAlignment(
                nowTs = 2_000_000L,
                latestCheck = check
            )
        ).isTrue()
    }

    @Test
    fun farFuturePendingAlignmentDoesNotBypassMemo() {
        val check = bloodGlucoseCheck(
            status = BloodGlucoseCheckStatus.STALE,
            lagAlignedTs = 10_000_000L
        )

        assertThat(
            shouldBypassCalibrationMemoForPendingAlignment(
                nowTs = 1_000_000L,
                latestCheck = check
            )
        ).isFalse()
    }

    @Test
    fun pendingAlignmentWindowHandlesTimestampOverflow() {
        val nearMax = bloodGlucoseCheck(
            status = BloodGlucoseCheckStatus.OUT_OF_WINDOW,
            lagAlignedTs = Long.MAX_VALUE - 100_000L
        )
        val nearMin = bloodGlucoseCheck(
            status = BloodGlucoseCheckStatus.OUT_OF_WINDOW,
            lagAlignedTs = Long.MIN_VALUE + 100_000L
        )

        assertThat(
            shouldBypassCalibrationMemoForPendingAlignment(
                nowTs = Long.MAX_VALUE - 1L,
                latestCheck = nearMax
            )
        ).isTrue()
        assertThat(
            shouldBypassCalibrationMemoForPendingAlignment(
                nowTs = Long.MAX_VALUE,
                latestCheck = nearMax
            )
        ).isFalse()
        assertThat(
            shouldBypassCalibrationMemoForPendingAlignment(
                nowTs = Long.MIN_VALUE,
                latestCheck = nearMin
            )
        ).isTrue()
    }

    @Test
    fun missingCalibrationTrustFieldIsUnavailable() {
        val available = areCalibrationTrustFieldsAvailable(
            targetTs = TRUST_TARGET_TS,
            staleWindowMs = TRUST_STALE_WINDOW_MS,
            qualityScore = trustField(value = 0.8),
            sensorBlocked = trustField(value = 0.0),
            sensorSuspectFalseLow = null
        )

        assertThat(available).isFalse()
    }

    @Test
    fun nonFiniteCalibrationTrustFieldIsUnavailable() {
        listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY
        ).forEach { invalidValue ->
            val available = areCalibrationTrustFieldsAvailable(
                targetTs = TRUST_TARGET_TS,
                staleWindowMs = TRUST_STALE_WINDOW_MS,
                qualityScore = trustField(value = invalidValue),
                sensorBlocked = trustField(value = 0.0),
                sensorSuspectFalseLow = trustField(value = 0.0)
            )

            assertThat(available).isFalse()
        }
    }

    @Test
    fun staleCalibrationTrustFieldIsUnavailable() {
        val available = areCalibrationTrustFieldsAvailable(
            targetTs = TRUST_TARGET_TS,
            staleWindowMs = TRUST_STALE_WINDOW_MS,
            qualityScore = trustField(
                ts = TRUST_TARGET_TS - TRUST_STALE_WINDOW_MS - 1L,
                value = 0.8
            ),
            sensorBlocked = trustField(value = 0.0),
            sensorSuspectFalseLow = trustField(value = 0.0)
        )

        assertThat(available).isFalse()
    }

    @Test
    fun futureCalibrationTrustFieldIsUnavailable() {
        val available = areCalibrationTrustFieldsAvailable(
            targetTs = TRUST_TARGET_TS,
            staleWindowMs = TRUST_STALE_WINDOW_MS,
            qualityScore = trustField(ts = TRUST_TARGET_TS + 1L, value = 0.8),
            sensorBlocked = trustField(value = 0.0),
            sensorSuspectFalseLow = trustField(value = 0.0)
        )

        assertThat(available).isFalse()
    }

    @Test
    fun completeFreshCalibrationTrustFieldsAreAvailable() {
        val available = areCalibrationTrustFieldsAvailable(
            targetTs = TRUST_TARGET_TS,
            staleWindowMs = TRUST_STALE_WINDOW_MS,
            qualityScore = trustField(
                ts = TRUST_TARGET_TS - TRUST_STALE_WINDOW_MS,
                value = 0.8
            ),
            sensorBlocked = trustField(value = 0.0),
            sensorSuspectFalseLow = trustField(value = 0.0)
        )

        assertThat(available).isTrue()
    }

    @Test
    fun unavailableTrustSnapshotKeepsNeutralSensorQualityFallback() {
        val snapshot = calibrationTelemetrySnapshotAt(TRUST_TARGET_TS)

        assertThat(snapshot.sensorTrustAvailable).isFalse()
        assertThat(snapshot.stale).isTrue()
        assertThat(snapshot.sensorQualityScore).isEqualTo(1.0)
    }

    private fun calibrationTelemetrySnapshotAt(targetTs: Long): CalibrationTelemetrySnapshotProbe {
        val indexClass = GlucoseCalibrationRepository::class.java.declaredClasses
            .single { it.simpleName == "CalibrationTelemetryIndex" }
        val constructor = indexClass.declaredConstructors.single().apply {
            isAccessible = true
        }
        val index = constructor.newInstance(emptyList<Any>())
        val snapshotMethod = indexClass.getDeclaredMethod("snapshotAt", java.lang.Long.TYPE).apply {
            isAccessible = true
        }
        val snapshot = requireNotNull(snapshotMethod.invoke(index, targetTs))

        fun read(getterName: String): Any {
            return requireNotNull(snapshot.javaClass.getDeclaredMethod(getterName).apply {
                isAccessible = true
            }.invoke(snapshot))
        }

        return CalibrationTelemetrySnapshotProbe(
            sensorTrustAvailable = read("getSensorTrustAvailable") as Boolean,
            stale = read("getStale") as Boolean,
            sensorQualityScore = read("getSensorQualityScore") as Double
        )
    }

    private data class CalibrationTelemetrySnapshotProbe(
        val sensorTrustAvailable: Boolean,
        val stale: Boolean,
        val sensorQualityScore: Double
    )

    private fun trustField(
        ts: Long = TRUST_TARGET_TS,
        value: Double
    ): CalibrationTrustFieldValue = CalibrationTrustFieldValue(
        ts = ts,
        value = value
    )

    private fun calibrationAssessment(
        lagAlignedTs: Long,
        matchedRaw: GlucosePoint?,
        status: BloodGlucoseCheckStatus,
        reason: String
    ): CalibrationCheckAssessment = CalibrationCheckAssessment(
        lagAlignedTs = lagAlignedTs,
        effectiveLagMinutes = if (matchedRaw?.ts == lagAlignedTs) 0.0 else 10.0,
        matchedRaw = matchedRaw,
        status = status,
        reason = reason,
        absoluteGapMmol = null,
        relativeGap = null,
        alignedTrust = CalibrationTrustSnapshot(
            sensorBlocked = false,
            sensorSuspectFalseLow = false,
            stale = false,
            available = true
        )
    )

    private fun bloodGlucoseCheck(
        timestamp: Long = 400_000L,
        status: BloodGlucoseCheckStatus,
        lagAlignedTs: Long?
    ): BloodGlucoseCheck = BloodGlucoseCheck(
        id = "bgc-1",
        timestamp = timestamp,
        mmol = 7.8,
        units = "mmol/L",
        source = "MANUAL",
        note = null,
        enteredAt = 400_500L,
        sensorSessionKey = "sensor-1",
        lagAlignedTs = lagAlignedTs,
        matchedRawGlucose = null,
        status = status,
        reason = "aligned_glucose_gap"
    )

    private fun calibrationMemoWatermark(
        bloodCheckGeneration: Long = 1L,
        glucoseGeneration: Long = 2L,
        therapyGeneration: Long = 3L,
        telemetryGeneration: Long = 4L,
        modelGeneration: Long = 5L
    ): CalibrationMemoInputWatermark = CalibrationMemoInputWatermark(
        bloodCheckGeneration = bloodCheckGeneration,
        glucoseGeneration = glucoseGeneration,
        therapyGeneration = therapyGeneration,
        telemetryGeneration = telemetryGeneration,
        modelGeneration = modelGeneration
    )

    private fun model(
        id: String = "gcm-1",
        createdAt: Long = 1_000L,
        validToTs: Long = 100_000L,
        confidence: Double = 0.8,
        offsetMmol: Double = -0.3,
        checkCount: Int = 3
    ): GlucoseCalibrationModel {
        return GlucoseCalibrationModel(
            id = id,
            sensorSessionKey = "sensor-1",
            createdAt = createdAt,
            validFromTs = 500L,
            validToTs = validToTs,
            modelType = GlucoseCalibrationModelType.OFFSET,
            gain = 1.0,
            offsetMmol = offsetMmol,
            confidence = confidence,
            checkCount = checkCount,
            sensorAgeHours = 120.0,
            lagMinutesAtFit = 12.0,
            status = GlucoseCalibrationModelStatus.ACTIVE,
            diagnosticsJson = "{\"alignedChecks\":3}"
        )
    }

    private companion object {
        const val TRUST_TARGET_TS = 2_000_000L
        const val TRUST_STALE_WINDOW_MS = 30L * 60L * 1000L
    }
}
