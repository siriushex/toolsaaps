package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.repository.CalibrationModelAuthority
import io.aaps.copilot.data.repository.CalibrationAuthorityStateCodec
import io.aaps.copilot.domain.model.GlucoseCalibrationModel
import io.aaps.copilot.domain.model.GlucoseCalibrationModelStatus
import io.aaps.copilot.domain.model.GlucoseCalibrationModelType
import org.junit.Test

class MainViewModelPrimaryHistoryTest {

    @Test
    fun overviewCurrentGlucoseSelectionIsCausalAndSanitized() {
        val now = 1_800_000_000_000L
        val futureOnly = selectOverviewCurrentGlucoseForUi(
            glucose = listOf(glucose(ts = now + 1L, mmol = 9.0)),
            nowTs = now
        )
        val futureAndCausal = selectOverviewCurrentGlucoseForUi(
            glucose = listOf(
                glucose(ts = now + 1L, mmol = 9.0),
                glucose(ts = now - 1L, mmol = 5.8),
                glucose(ts = now - 2L, mmol = 5.7)
            ),
            nowTs = now
        )
        val exactBoundary = selectOverviewCurrentGlucoseForUi(
            glucose = listOf(
                glucose(ts = now, mmol = 6.0),
                glucose(ts = now - 1L, mmol = 5.9)
            ),
            nowTs = now
        )
        val invalidOnly = selectOverviewCurrentGlucoseForUi(
            glucose = listOf(
                glucose(ts = now, mmol = 7.0).copy(quality = "INVALID"),
                glucose(ts = now - 1L, mmol = Double.POSITIVE_INFINITY)
            ),
            nowTs = now
        )

        assertThat(futureOnly.latest).isNull()
        assertThat(futureOnly.previous).isNull()
        assertThat(futureAndCausal.latest?.timestamp).isEqualTo(now - 1L)
        assertThat(futureAndCausal.previous?.timestamp).isEqualTo(now - 2L)
        assertThat(exactBoundary.latest?.timestamp).isEqualTo(now)
        assertThat(exactBoundary.previous?.timestamp).isEqualTo(now - 1L)
        assertThat(invalidOnly.latest).isNull()
        assertThat(invalidOnly.previous).isNull()
    }

    @Test
    fun buildGlucoseHistoryPointsForUi_keepsRecentPointsInTimestampOrder() {
        val now = 1_800_000_000_000L
        val rows = listOf(
            glucose(ts = now, mmol = 5.8),
            glucose(ts = now - 25L * 60L * 60_000L, mmol = 6.1),
            glucose(ts = now - 2L * 60L * 60_000L, mmol = 5.4)
        )

        val result = buildGlucoseHistoryPointsForUi(rows, nowTs = now)

        assertThat(result).containsExactly(
            GlucoseHistoryRowUi(timestamp = now - 2L * 60L * 60_000L, valueMmol = 5.4),
            GlucoseHistoryRowUi(timestamp = now, valueMmol = 5.8)
        ).inOrder()
    }

    @Test
    fun buildCalibrationResolvedHistoryPointsForUi_appliesModelToEveryPrimaryPoint() {
        val points = listOf(
            GlucoseHistoryRowUi(timestamp = 1_000L, valueMmol = 5.4),
            GlucoseHistoryRowUi(timestamp = 2_000L, valueMmol = 5.5)
        )

        val result = buildCalibrationResolvedHistoryPointsForUi(points) { _, rawMmol ->
            rawMmol + 2.0
        }

        assertThat(result.map { it.value }).containsExactly(7.4, 7.5).inOrder()
    }

    @Test
    fun activeCalibrationModelOverridesOlderOffTelemetryForPrimaryGlucose() {
        val pointTs = 2_000_000L
        val model = calibrationModel(
            createdAt = pointTs,
            offsetMmol = 2.0,
            status = GlucoseCalibrationModelStatus.ACTIVE
        )

        val resolved = resolveUiCalibratedGlucose(
            pointTs = pointTs,
            rawMmol = 4.2,
            telemetryCalibratedMmol = 4.2,
            telemetryCalibrationSourceTs = pointTs,
            activeModel = model
        )

        assertThat(resolved).isWithin(0.0001).of(6.2)
    }

    @Test
    fun missingApplicableCalibrationModelCannotReuseOldTelemetry() {
        val pointTs = 2_000_000L
        val model = calibrationModel(
            createdAt = pointTs,
            offsetMmol = 2.0,
            status = GlucoseCalibrationModelStatus.RETIRED
        )

        val resolved = resolveUiCalibratedGlucose(
            pointTs = pointTs,
            rawMmol = 4.2,
            telemetryCalibratedMmol = 4.4,
            telemetryCalibrationSourceTs = pointTs,
            activeModel = model
        )

        assertThat(resolved).isEqualTo(4.2)
    }

    @Test
    fun activeCalibrationModelCannotOverrideTelemetryOutsideValidityWindow() {
        val model = calibrationModel(
            createdAt = 2_000_000L,
            offsetMmol = 2.0,
            status = GlucoseCalibrationModelStatus.ACTIVE
        )

        val beforeWindow = resolveUiCalibratedGlucose(
            pointTs = model.validFromTs - 1L,
            rawMmol = 4.2,
            telemetryCalibratedMmol = 4.3,
            telemetryCalibrationSourceTs = model.validFromTs - 1L,
            activeModel = model
        )
        val afterWindow = resolveUiCalibratedGlucose(
            pointTs = model.validToTs + 1L,
            rawMmol = 4.2,
            telemetryCalibratedMmol = 4.3,
            telemetryCalibrationSourceTs = model.validToTs + 1L,
            activeModel = model
        )

        assertThat(beforeWindow).isEqualTo(4.2)
        assertThat(afterWindow).isEqualTo(4.2)
    }

    @Test
    fun telemetryCalibrationCannotApplyToDifferentGlucoseTimestamp() {
        val resolved = resolveUiCalibratedGlucose(
            pointTs = 2_000_000L,
            rawMmol = 4.2,
            telemetryCalibratedMmol = 6.2,
            telemetryCalibrationSourceTs = 1_700_000L,
            activeModel = null
        )

        assertThat(resolved).isEqualTo(4.2)
    }

    @Test
    fun activeModelFromDifferentSensorSessionIsRejectedForUi() {
        val nowTs = 2_000_000_000L
        val model = calibrationModel(
            createdAt = nowTs,
            offsetMmol = 2.0,
            status = GlucoseCalibrationModelStatus.ACTIVE
        )
        val ageSample = TelemetrySampleEntity(
            id = "age",
            timestamp = nowTs,
            source = "test",
            key = "sensor_age_hours",
            valueDouble = 1.0,
            valueText = null,
            unit = "h",
            quality = "OK"
        )

        val selected = selectUiActiveCalibrationModel(
            model = model,
            nowTs = nowTs,
            sensorAgeSamples = listOf(ageSample)
        )

        assertThat(selected).isNull()
    }

    @Test
    fun freshMatchingSensorSessionAllowsActiveModelForUi() {
        val nowTs = 2_000_000_000L
        val ageHours = 1.0
        val sessionKey = io.aaps.copilot.data.repository.calibrationSensorSessionKeyFromAgeSample(
            sampleTs = nowTs,
            ageHours = ageHours
        )
        val model = calibrationModel(
            createdAt = nowTs,
            offsetMmol = 2.0,
            status = GlucoseCalibrationModelStatus.ACTIVE,
            sensorSessionKey = requireNotNull(sessionKey)
        )
        val ageSample = TelemetrySampleEntity(
            id = "age",
            timestamp = nowTs,
            source = "test",
            key = "sensor_age_hours",
            valueDouble = ageHours,
            valueText = null,
            unit = "h",
            quality = "OK"
        )

        val selected = selectUiActiveCalibrationModel(
            model = model,
            nowTs = nowTs,
            sensorAgeSamples = listOf(ageSample)
        )

        assertThat(selected).isEqualTo(model)
    }

    @Test
    fun calibrationTelemetryAloneCannotAuthorizeModelWithoutCurrentSensorAge() {
        val nowTs = 2_000_000_000L
        val model = calibrationModel(
            createdAt = nowTs,
            offsetMmol = 2.0,
            status = GlucoseCalibrationModelStatus.ACTIVE,
            sensorSessionKey = "sensor-current"
        )

        val selected = selectUiActiveCalibrationModel(
            model = model,
            nowTs = nowTs,
            sensorAgeSamples = listOf(
                calibrationTelemetryText(
                    id = "calibration-session",
                    key = "glucose_calibration_sensor_session_key",
                    timestamp = nowTs,
                    value = "sensor-current"
                ),
                calibrationTelemetryText(
                    id = "calibration-status",
                    key = "glucose_calibration_status",
                    timestamp = nowTs,
                    value = "ACTIVE"
                ),
                calibrationTelemetryNumber(
                    id = "calibration-source-ts",
                    key = "glucose_calibration_source_ts",
                    timestamp = nowTs,
                    value = (nowTs - 60_000L).toDouble()
                )
            )
        )

        assertThat(selected).isNull()
    }

    @Test
    fun staleRepositorySessionEvidenceCannotBridgeCalibration() {
        val nowTs = 2_000_000_000L
        val model = calibrationModel(
            createdAt = nowTs,
            offsetMmol = 2.0,
            status = GlucoseCalibrationModelStatus.ACTIVE,
            sensorSessionKey = "sensor-current"
        )

        val selected = selectUiActiveCalibrationModel(
            model = model,
            nowTs = nowTs,
            sensorAgeSamples = listOf(
                calibrationTelemetryText(
                    id = "calibration-session",
                    key = "glucose_calibration_sensor_session_key",
                    timestamp = nowTs - 31L * 60_000L,
                    value = "sensor-current"
                ),
                calibrationTelemetryText(
                    id = "calibration-status",
                    key = "glucose_calibration_status",
                    timestamp = nowTs - 31L * 60_000L,
                    value = "ACTIVE"
                ),
                calibrationTelemetryNumber(
                    id = "calibration-source-ts",
                    key = "glucose_calibration_source_ts",
                    timestamp = nowTs - 31L * 60_000L,
                    value = (nowTs - 31L * 60_000L).toDouble()
                )
            )
        )

        assertThat(selected).isNull()
    }

    @Test
    fun conflictingFreshSensorAgeSourcesRejectActiveModelForUi() {
        val nowTs = 2_000_000_000L
        val matchingAgeHours = 1.0
        val sessionKey = io.aaps.copilot.data.repository.calibrationSensorSessionKeyFromAgeSample(
            sampleTs = nowTs,
            ageHours = matchingAgeHours
        )
        val model = calibrationModel(
            createdAt = nowTs,
            offsetMmol = 2.0,
            status = GlucoseCalibrationModelStatus.ACTIVE,
            sensorSessionKey = requireNotNull(sessionKey)
        )

        val selected = selectUiActiveCalibrationModel(
            model = model,
            nowTs = nowTs,
            sensorAgeSamples = listOf(
                sensorAgeSample("age-primary", "aaps", "sensor_age_hours", nowTs, matchingAgeHours),
                sensorAgeSample("age-conflict", "xdrip", "isf_factor_sensor_age_hours", nowTs, 6.0)
            )
        )

        assertThat(selected).isNull()
    }

    @Test
    fun stalePrimarySensorAgeDoesNotHideFreshMatchingFallback() {
        val nowTs = 2_000_000_000L
        val matchingAgeHours = 1.0
        val sessionKey = io.aaps.copilot.data.repository.calibrationSensorSessionKeyFromAgeSample(
            sampleTs = nowTs,
            ageHours = matchingAgeHours
        )
        val model = calibrationModel(
            createdAt = nowTs,
            offsetMmol = 2.0,
            status = GlucoseCalibrationModelStatus.ACTIVE,
            sensorSessionKey = requireNotNull(sessionKey)
        )

        val selected = selectUiActiveCalibrationModel(
            model = model,
            nowTs = nowTs,
            sensorAgeSamples = listOf(
                sensorAgeSample(
                    id = "age-stale",
                    source = "aaps",
                    key = "sensor_age_hours",
                    timestamp = nowTs - 31L * 60_000L,
                    ageHours = 4.0
                ),
                sensorAgeSample(
                    id = "age-fresh",
                    source = "xdrip",
                    key = "isf_factor_sensor_age_hours",
                    timestamp = nowTs,
                    ageHours = matchingAgeHours
                )
            )
        )

        assertThat(selected).isEqualTo(model)
    }

    @Test
    fun overviewCalibrationSelectionMatchesSharedExpiryAndStrengthAuthority() {
        val nowTs = 2_000_000_000L
        val ageHours = 1.0
        val sessionKey = requireNotNull(
            io.aaps.copilot.data.repository.calibrationSensorSessionKeyFromAgeSample(nowTs, ageHours)
        )
        val evidence = listOf(sensorAgeSample("age", "aaps", "sensor_age_hours", nowTs, ageHours))
        val zeroStrength = calibrationModel(
            createdAt = nowTs - 72L * 60L * 60L * 1000L,
            offsetMmol = 2.0,
            status = GlucoseCalibrationModelStatus.ACTIVE,
            sensorSessionKey = sessionKey
        ).copy(validToTs = nowTs + 1L)

        val shared = CalibrationModelAuthority.selectApplicableModel(
            model = zeroStrength,
            nowTs = nowTs,
            pointTs = nowTs,
            telemetry = evidence
        )
        val overview = selectUiActiveCalibrationModel(
            model = zeroStrength,
            nowTs = nowTs,
            sensorAgeSamples = evidence
        )

        assertThat(shared).isNull()
        assertThat(overview).isEqualTo(shared)
    }

    @Test
    fun overviewCalibrationSelectionFailsClosedWhenDedicatedSensorEvidenceOverflows() {
        val nowTs = 2_000_000_000L
        val sessionStartTs = nowTs - 60L * 60L * 1000L
        val ageHours = (nowTs - sessionStartTs).toDouble() / 3_600_000.0
        val sessionKey = requireNotNull(
            io.aaps.copilot.data.repository.calibrationSensorSessionKeyFromAgeSample(nowTs, ageHours)
        )
        val model = calibrationModel(
            createdAt = nowTs,
            offsetMmol = 2.0,
            status = GlucoseCalibrationModelStatus.ACTIVE,
            sensorSessionKey = sessionKey
        )
        val generalProjection = listOf(
            sensorAgeSample("visible", "aaps", "sensor_age_hours", nowTs, ageHours)
        )
        val dedicatedRows = (0 until CalibrationModelAuthority.SESSION_EVIDENCE_QUERY_LIMIT).map { index ->
            val timestamp = nowTs - index
            sensorAgeSample(
                id = "dedicated-$index",
                source = "source-$index",
                key = "sensor_age_hours",
                timestamp = timestamp,
                ageHours = (timestamp - sessionStartTs).toDouble() / 3_600_000.0
            )
        } + sensorAgeSample(
            id = "hidden-conflict",
            source = "conflict",
            key = "sensor_age_hours",
            timestamp = nowTs - CalibrationModelAuthority.SESSION_EVIDENCE_QUERY_LIMIT,
            ageHours = 0.1
        )

        val incorrectlyProjected = selectUiActiveCalibrationModel(
            model = model,
            nowTs = nowTs,
            pointTs = nowTs,
            sensorAgeSamples = generalProjection
        )
        val selected = selectUiActiveCalibrationModelFromBoundedEvidence(
            model = model,
            nowTs = nowTs,
            pointTs = nowTs,
            queriedSensorEvidence = dedicatedRows,
            authorityToken = CalibrationAuthorityStateCodec.active("overview-overflow", model)
        )

        assertThat(incorrectlyProjected).isEqualTo(model)
        assertThat(selected).isNull()
    }

    @Test
    fun overviewSharedCalibrationResolverRejectsFutureSelectedGlucose() {
        val nowTs = 2_000_000_000L
        val ageHours = 1.0
        val model = calibrationModel(
            createdAt = nowTs,
            offsetMmol = 2.0,
            status = GlucoseCalibrationModelStatus.ACTIVE,
            sensorSessionKey = requireNotNull(
                io.aaps.copilot.data.repository.calibrationSensorSessionKeyFromAgeSample(
                    nowTs,
                    ageHours
                )
            )
        )

        val selected = selectUiActiveCalibrationModelFromBoundedEvidence(
            model = model,
            nowTs = nowTs,
            pointTs = nowTs + 1L,
            queriedSensorEvidence = listOf(
                sensorAgeSample("age", "aaps", "sensor_age_hours", nowTs, ageHours)
            ),
            authorityToken = CalibrationAuthorityStateCodec.active("overview-future", model)
        )

        assertThat(selected).isNull()
    }

    @Test
    fun overviewSharedCalibrationResolverRejectsFutureCreatedModel() {
        val nowTs = 2_000_000_000L
        val ageHours = 1.0
        val model = calibrationModel(
            createdAt = nowTs + 1L,
            validFromTs = nowTs - 1L,
            offsetMmol = 2.0,
            status = GlucoseCalibrationModelStatus.ACTIVE,
            sensorSessionKey = requireNotNull(
                io.aaps.copilot.data.repository.calibrationSensorSessionKeyFromAgeSample(
                    nowTs,
                    ageHours
                )
            )
        )

        val selected = selectUiActiveCalibrationModelFromBoundedEvidence(
            model = model,
            nowTs = nowTs,
            pointTs = nowTs,
            queriedSensorEvidence = listOf(
                sensorAgeSample("age", "aaps", "sensor_age_hours", nowTs, ageHours)
            ),
            authorityToken = CalibrationAuthorityStateCodec.active("overview-future-model", model)
        )

        assertThat(selected).isNull()
    }

    @Test
    fun overviewModelFreeAuthorityCarriesTrustworthyCurrentSession() {
        val nowTs = 2_000_000_000L
        val sessionKey = requireNotNull(
            io.aaps.copilot.data.repository.calibrationSensorSessionKeyFromAgeSample(nowTs, 1.0)
        )
        val authorityToken = CalibrationAuthorityStateCodec.raw("overview-model-free", sessionKey)
        val matching = resolveUiCalibrationAuthorityFromBoundedEvidence(
            model = null,
            nowTs = nowTs,
            pointTs = nowTs,
            queriedSensorEvidence = listOf(
                sensorAgeSample("age", "aaps", "sensor_age_hours", nowTs, 1.0)
            ),
            authorityToken = authorityToken
        )
        val missing = resolveUiCalibrationAuthorityFromBoundedEvidence(
            model = null,
            nowTs = nowTs,
            pointTs = nowTs,
            queriedSensorEvidence = emptyList(),
            authorityToken = authorityToken
        )
        val conflicting = resolveUiCalibrationAuthorityFromBoundedEvidence(
            model = null,
            nowTs = nowTs,
            pointTs = nowTs,
            queriedSensorEvidence = listOf(
                sensorAgeSample("old", "aaps", "sensor_age_hours", nowTs, 8.0),
                sensorAgeSample("new", "xdrip", "sensor_age_hours", nowTs, 1.0)
            ),
            authorityToken = authorityToken
        )

        assertThat(matching.currentSessionKey).isEqualTo(sessionKey)
        assertThat(matching.contextValid).isTrue()
        assertThat(missing.contextValid).isFalse()
        assertThat(conflicting.contextValid).isFalse()
    }

    @Test
    fun overviewDurableAuthorityRequiresExactActiveFingerprintAndState() {
        val nowTs = 2_000_000_000L
        val ageHours = 1.0
        val sessionKey = requireNotNull(
            io.aaps.copilot.data.repository.calibrationSensorSessionKeyFromAgeSample(nowTs, ageHours)
        )
        val model = calibrationModel(
            createdAt = nowTs,
            offsetMmol = 2.0,
            status = GlucoseCalibrationModelStatus.ACTIVE,
            sensorSessionKey = sessionKey
        )
        val evidence = listOf(
            sensorAgeSample("age", "aaps", "sensor_age_hours", nowTs, ageHours)
        )

        val exact = resolveUiCalibrationAuthorityFromBoundedEvidence(
            model = model,
            nowTs = nowTs,
            pointTs = nowTs,
            queriedSensorEvidence = evidence,
            authorityToken = CalibrationAuthorityStateCodec.active("overview-exact", model)
        )
        val pending = resolveUiCalibrationAuthorityFromBoundedEvidence(
            model = model,
            nowTs = nowTs,
            pointTs = nowTs,
            queriedSensorEvidence = evidence,
            authorityToken = CalibrationAuthorityStateCodec.pending("overview-pending")
        )
        val mismatched = resolveUiCalibrationAuthorityFromBoundedEvidence(
            model = model.copy(offsetMmol = 2.5),
            nowTs = nowTs,
            pointTs = nowTs,
            queriedSensorEvidence = evidence,
            authorityToken = CalibrationAuthorityStateCodec.active("overview-mismatch", model)
        )

        assertThat(exact.activeModel).isEqualTo(model)
        assertThat(exact.contextValid).isTrue()
        assertThat(pending.activeModel).isNull()
        assertThat(pending.contextValid).isFalse()
        assertThat(mismatched.activeModel).isNull()
        assertThat(mismatched.contextValid).isFalse()
    }

    private fun glucose(ts: Long, mmol: Double) = GlucoseSampleEntity(
        timestamp = ts,
        mmol = mmol,
        source = "test",
        quality = "OK"
    )

    private fun sensorAgeSample(
        id: String,
        source: String,
        key: String,
        timestamp: Long,
        ageHours: Double
    ) = TelemetrySampleEntity(
        id = id,
        timestamp = timestamp,
        source = source,
        key = key,
        valueDouble = ageHours,
        valueText = null,
        unit = "h",
        quality = "OK"
    )

    private fun calibrationTelemetryText(
        id: String,
        key: String,
        timestamp: Long,
        value: String
    ) = TelemetrySampleEntity(
        id = id,
        timestamp = timestamp,
        source = "copilot_glucose_calibration",
        key = key,
        valueDouble = null,
        valueText = value,
        unit = null,
        quality = "OK"
    )

    private fun calibrationTelemetryNumber(
        id: String,
        key: String,
        timestamp: Long,
        value: Double
    ) = TelemetrySampleEntity(
        id = id,
        timestamp = timestamp,
        source = "copilot_glucose_calibration",
        key = key,
        valueDouble = value,
        valueText = null,
        unit = "epoch_ms",
        quality = "OK"
    )

    private fun calibrationModel(
        createdAt: Long,
        validFromTs: Long = createdAt,
        offsetMmol: Double,
        status: GlucoseCalibrationModelStatus,
        sensorSessionKey: String = "sensor"
    ) = GlucoseCalibrationModel(
        id = "model",
        sensorSessionKey = sensorSessionKey,
        createdAt = createdAt,
        validFromTs = validFromTs,
        validToTs = createdAt + 72L * 60L * 60_000L,
        modelType = GlucoseCalibrationModelType.OFFSET,
        gain = 1.0,
        offsetMmol = offsetMmol,
        confidence = 0.6,
        checkCount = 1,
        sensorAgeHours = null,
        lagMinutesAtFit = 10.0,
        status = status,
        diagnosticsJson = "{}"
    )
}
