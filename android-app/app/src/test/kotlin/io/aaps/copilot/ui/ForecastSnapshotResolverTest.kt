package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.repository.AcceptedForecastTupleError
import io.aaps.copilot.data.repository.AcceptedCalibrationAuthority
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_MODEL_FINGERPRINT_KEY
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_MODEL_ID_KEY
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_NONE
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_PREPARED_AT_KEY
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_SESSION_KEY
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_SOURCE
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_AUTHORITY_TOKEN_KEY
import io.aaps.copilot.data.repository.ForecastSnapshotResolver
import io.aaps.copilot.data.repository.CalibrationAuthorityStateCodec
import io.aaps.copilot.data.repository.acceptedCalibrationModelFingerprint
import io.aaps.copilot.domain.model.GlucoseCalibrationModel
import io.aaps.copilot.domain.model.GlucoseCalibrationModelStatus
import io.aaps.copilot.domain.model.GlucoseCalibrationModelType
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_CYCLE_ID_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY
import io.aaps.copilot.domain.predict.AcceptedSensitivityTupleFreshness
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDigest
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDecomposition
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDecompositionCodec
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastRow
import io.aaps.copilot.domain.predict.SensitivityRuntimeSettingsIdentity
import io.aaps.copilot.domain.predict.SensitivitySourcePreference
import io.aaps.copilot.testSensitivityRuntimeSnapshot
import org.junit.Test

class ForecastSnapshotResolverTest {

    @Test
    fun acceptedTupleReturnsSensitivityAndExactForecastSetTogether() {
        val forecasts = rows(base = 6.0)
        val snapshot = acceptedSnapshot()

        val result = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = forecasts,
            telemetry = markers(forecasts),
            snapshot = snapshot,
            currentSettings = identity(),
            authoritativeNowTs = MARKER_TS
        )

        assertThat(result.error).isNull()
        assertThat(result.sensitivity).isSameInstanceAs(snapshot)
        assertThat(result.forecastsByHorizon.keys).containsExactly(5, 30, 60)
        assertThat(result.decomposition).isEqualTo(decomposition())
    }

    @Test
    fun overviewManualAddInvalidatesOlderAcceptedCalibrationIdentity() {
        val acceptedModel = calibrationModel(id = "accepted-model", offsetMmol = 0.8)
        val manualModel = calibrationModel(id = "manual-model", offsetMmol = 1.1)

        val result = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = rows(base = 6.0),
            telemetry = markers(rows(base = 6.0), calibrationModel = acceptedModel),
            snapshot = acceptedSnapshot(),
            currentSettings = identity(),
            authoritativeNowTs = MARKER_TS,
            calibrationAuthority = AcceptedCalibrationAuthority(manualModel)
        )

        assertThat(result.error).isEqualTo(AcceptedForecastTupleError.CALIBRATION_MISMATCH)
        assertThat(result.forecastsByHorizon).isEmpty()
        assertThat(resolveOverviewCalibrationModelForUi(result, manualModel)).isSameInstanceAs(manualModel)
    }

    @Test
    fun overviewManualResetInvalidatesOlderAcceptedCalibrationIdentity() {
        val acceptedModel = calibrationModel(id = "accepted-model", offsetMmol = 0.8)

        val result = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = rows(base = 6.0),
            telemetry = markers(rows(base = 6.0), calibrationModel = acceptedModel),
            snapshot = acceptedSnapshot(),
            currentSettings = identity(),
            authoritativeNowTs = MARKER_TS,
            calibrationAuthority = AcceptedCalibrationAuthority(null)
        )

        assertThat(result.error).isEqualTo(AcceptedForecastTupleError.CALIBRATION_MISMATCH)
        assertThat(resolveOverviewCalibrationModelForUi(result, null)).isNull()
    }

    @Test
    fun manualAddWithoutFittedModelStillInvalidatesAcceptedRawCalibrationIdentity() {
        val result = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = rows(base = 6.0),
            telemetry = markers(
                forecasts = rows(base = 6.0),
                authorityToken = "accepted-before-manual"
            ),
            snapshot = acceptedSnapshot(),
            currentSettings = identity(),
            authoritativeNowTs = MARKER_TS,
            calibrationAuthority = AcceptedCalibrationAuthority(
                activeModel = null,
                authorityToken = "manual-after-acceptance"
            )
        )

        assertThat(result.error).isEqualTo(AcceptedForecastTupleError.CALIBRATION_MISMATCH)
        assertThat(result.forecastsByHorizon).isEmpty()
    }

    @Test
    fun overviewAcceptedForecastCarriesExactCalibrationModelContent() {
        val acceptedModel = calibrationModel(id = "accepted-model", offsetMmol = 0.8)

        val result = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = rows(base = 6.0),
            telemetry = markers(rows(base = 6.0), calibrationModel = acceptedModel),
            snapshot = acceptedSnapshot(),
            currentSettings = identity(),
            authoritativeNowTs = MARKER_TS,
            calibrationAuthority = AcceptedCalibrationAuthority(acceptedModel)
        )

        assertThat(result.error).isNull()
        assertThat(result.calibrationModel).isSameInstanceAs(acceptedModel)
        assertThat(resolveOverviewCalibrationModelForUi(result, null)).isSameInstanceAs(acceptedModel)
    }

    @Test
    fun preFeatureMarkerlessTupleFailsClosedWithoutModelOrAuthorityToken() {
        val forecasts = rows(base = 6.0)

        val result = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = forecasts,
            telemetry = sensitivityMarkers(forecasts),
            snapshot = acceptedSnapshot(),
            currentSettings = identity(),
            authoritativeNowTs = MARKER_TS,
            calibrationAuthority = AcceptedCalibrationAuthority(
                activeModel = null,
                authorityToken = ACCEPTED_CALIBRATION_NONE
            )
        )

        assertThat(result.error).isEqualTo(AcceptedForecastTupleError.CALIBRATION_MISMATCH)
        assertThat(result.forecastsByHorizon).isEmpty()
    }

    @Test
    fun explicitRawTupleWithoutSensorSessionDoesNotRequireAStoredCalibration() {
        val forecasts = rows(base = 6.0)
        val token = CalibrationAuthorityStateCodec.raw("raw-without-session", null)
        val result = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = forecasts,
            telemetry = markers(forecasts, authorityToken = token),
            snapshot = acceptedSnapshot(),
            currentSettings = identity(),
            authoritativeNowTs = MARKER_TS,
            calibrationAuthority = AcceptedCalibrationAuthority(null, token, null, contextValid = true)
        )

        assertThat(result.error).isNull()
        assertThat(result.calibrationModel).isNull()
        assertThat(result.calibrationSessionKey).isNull()
        assertThat(result.forecastsByHorizon.keys).containsExactly(5, 30, 60)
    }

    @Test
    fun unboundRawTupleRejectsPendingMalformedAndSessionBoundAuthority() {
        val forecasts = rows(base = 6.0)
        val tokens = listOf(
            ACCEPTED_CALIBRATION_NONE,
            "malformed-authority",
            CalibrationAuthorityStateCodec.pending("manual-pending"),
            CalibrationAuthorityStateCodec.raw("known-session", "session-1")
        )
        tokens.forEach { token ->
            val result = ForecastSnapshotResolver.resolveAcceptedTuple(
                forecasts = forecasts,
                telemetry = markers(forecasts, authorityToken = token),
                snapshot = acceptedSnapshot(),
                currentSettings = identity(),
                authoritativeNowTs = MARKER_TS,
                calibrationAuthority = AcceptedCalibrationAuthority(null, token, null, contextValid = true)
            )
            assertThat(result.error).isEqualTo(AcceptedForecastTupleError.CALIBRATION_MISMATCH)
            assertThat(result.forecastsByHorizon).isEmpty()
        }
    }

    @Test
    fun modelFreeAcceptedTupleRequiresMatchingCurrentSensorSession() {
        val forecasts = rows(base = 6.0)
        val acceptedTelemetry = markers(forecasts).map { row ->
            if (row.key == ACCEPTED_CALIBRATION_SESSION_KEY) {
                row.copy(valueText = "session-1")
            } else {
                row
            }
        }

        val matching = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = forecasts,
            telemetry = acceptedTelemetry,
            snapshot = acceptedSnapshot(),
            currentSettings = identity(),
            authoritativeNowTs = MARKER_TS,
            calibrationAuthority = AcceptedCalibrationAuthority(
                activeModel = null,
                currentSessionKey = "session-1"
            )
        )
        val rollover = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = forecasts,
            telemetry = acceptedTelemetry,
            snapshot = acceptedSnapshot(),
            currentSettings = identity(),
            authoritativeNowTs = MARKER_TS,
            calibrationAuthority = AcceptedCalibrationAuthority(
                activeModel = null,
                currentSessionKey = "session-2"
            )
        )
        val missingOrConflicting = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = forecasts,
            telemetry = acceptedTelemetry,
            snapshot = acceptedSnapshot(),
            currentSettings = identity(),
            authoritativeNowTs = MARKER_TS,
            calibrationAuthority = AcceptedCalibrationAuthority(
                activeModel = null,
                currentSessionKey = null,
                contextValid = false
            )
        )

        assertThat(matching.error).isNull()
        assertThat(matching.calibrationModel).isNull()
        assertThat(matching.calibrationSessionKey).isEqualTo("session-1")
        assertThat(rollover.error).isEqualTo(AcceptedForecastTupleError.CALIBRATION_MISMATCH)
        assertThat(missingOrConflicting.error)
            .isEqualTo(AcceptedForecastTupleError.CALIBRATION_MISMATCH)
    }

    @Test
    fun duplicateAtAcceptedGenerationFailsClosedWithoutCollapsingRows() {
        val forecasts = rows(base = 6.0)
        val duplicate = forecasts.first().copy(id = 99L, valueMmol = 8.0)

        val result = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = forecasts + duplicate,
            telemetry = markers(forecasts),
            snapshot = acceptedSnapshot(),
            currentSettings = identity(),
            authoritativeNowTs = MARKER_TS
        )

        assertThat(result.sensitivity).isNull()
        assertThat(result.forecastsByHorizon).isEmpty()
        assertThat(result.error).isEqualTo(AcceptedForecastTupleError.FORECAST_SET_INVALID)
    }

    @Test
    fun sameGenerationOverwriteFailsDigestAuthentication() {
        val accepted = rows(base = 6.0)
        val overwritten = rows(base = 8.0)

        val result = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = overwritten,
            telemetry = markers(accepted),
            snapshot = acceptedSnapshot(),
            currentSettings = identity(),
            authoritativeNowTs = MARKER_TS
        )

        assertThat(result.error).isEqualTo(AcceptedForecastTupleError.DIGEST_MISMATCH)
        assertThat(result.forecastsByHorizon).isEmpty()
    }

    @Test
    fun missingHorizonFailsClosed() {
        val accepted = rows(base = 6.0)

        val result = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = accepted.filterNot { it.horizonMinutes == 60 },
            telemetry = markers(accepted),
            snapshot = acceptedSnapshot(),
            currentSettings = identity(),
            authoritativeNowTs = MARKER_TS
        )

        assertThat(result.error).isEqualTo(AcceptedForecastTupleError.FORECAST_SET_INVALID)
        assertThat(result.forecastsByHorizon).isEmpty()
    }

    @Test
    fun markerDuplicatesFailClosedInsteadOfSelectingLatestByKey() {
        val forecasts = rows(base = 6.0)
        val markerRows = markers(forecasts)
        val duplicate = markerRows.first { it.key == SENSITIVITY_ACCEPTED_CYCLE_ID_KEY }
            .copy(id = "duplicate", valueText = "other-cycle")

        val result = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = forecasts,
            telemetry = markerRows + duplicate,
            snapshot = acceptedSnapshot(),
            currentSettings = identity(),
            authoritativeNowTs = MARKER_TS
        )

        assertThat(result.error).isEqualTo(AcceptedForecastTupleError.MARKER_SET_INVALID)
    }

    @Test
    fun newerLooseDecompositionCannotReplaceAcceptedDecomposition() {
        val forecasts = rows(base = 6.0)
        val loose = telemetry(
            key = "forecast_therapy_60_mmol",
            valueDouble = 9.9,
            timestamp = MARKER_TS + 1_000L,
            source = "copilot_forecast_decomposition"
        )

        val result = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = forecasts,
            telemetry = markers(forecasts) + loose,
            snapshot = acceptedSnapshot(),
            currentSettings = identity(),
            authoritativeNowTs = MARKER_TS
        )

        assertThat(result.error).isNull()
        assertThat(result.decomposition?.therapy60Mmol).isEqualTo(-0.4)
    }

    @Test
    fun changedAcceptedDecompositionWithOldDigestFailsClosed() {
        val forecasts = rows(base = 6.0)
        val changedMarkers = markers(forecasts).map { row ->
            if (row.key == SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY) {
                row.copy(
                    valueText = SensitivityAcceptedForecastDecompositionCodec.encode(
                        decomposition().copy(therapy60Mmol = -0.9)
                    )
                )
            } else {
                row
            }
        }

        val result = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = forecasts,
            telemetry = changedMarkers,
            snapshot = acceptedSnapshot(),
            currentSettings = identity(),
            authoritativeNowTs = MARKER_TS
        )

        assertThat(result.error).isEqualTo(AcceptedForecastTupleError.DIGEST_MISMATCH)
        assertThat(result.decomposition).isNull()
        assertThat(result.forecastsByHorizon).isEmpty()
    }

    @Test
    fun sensitivityCalculatedAfterLatestSampleKeepsTheAuthenticatedForecastOrigin() {
        val forecasts = rows(base = 6.0)
        val calculationTs = GENERATION_TS + 59_000L
        val publicationTs = calculationTs + 1_000L
        val snapshot = acceptedSnapshot().copy(timestamp = calculationTs)

        val result = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = forecasts,
            telemetry = markers(forecasts).map { it.copy(timestamp = publicationTs) },
            snapshot = snapshot,
            currentSettings = identity(),
            authoritativeNowTs = publicationTs
        )

        assertThat(result.error).isNull()
        assertThat(result.sensitivity).isSameInstanceAs(snapshot)
        assertThat(result.generationTimestamp).isEqualTo(GENERATION_TS)
        assertThat(result.forecastsByHorizon.values).containsExactlyElementsIn(forecasts)
    }

    @Test
    fun sensitivityCalculatedAfterPublicationStillFailsClosed() {
        val forecasts = rows(base = 6.0)
        val result = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = forecasts,
            telemetry = markers(forecasts),
            snapshot = acceptedSnapshot().copy(timestamp = MARKER_TS + 1L),
            currentSettings = identity(),
            authoritativeNowTs = MARKER_TS + 1L
        )

        assertThat(result.error).isEqualTo(AcceptedForecastTupleError.FRESHNESS_INVALID)
        assertThat(result.forecastsByHorizon).isEmpty()
    }

    @Test
    fun futureAcceptedGenerationFailsClosedEvenWhenDigestMatches() {
        val forecasts = rows(base = 6.0)

        val result = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = forecasts,
            telemetry = markers(forecasts),
            snapshot = acceptedSnapshot(),
            currentSettings = identity(),
            authoritativeNowTs = GENERATION_TS - 1L
        )

        assertThat(result.error).isEqualTo(AcceptedForecastTupleError.FRESHNESS_INVALID)
        assertThat(result.forecastsByHorizon).isEmpty()
    }

    @Test
    fun acceptedGenerationOlderThanSharedLimitFailsClosed() {
        val forecasts = rows(base = 6.0)

        val result = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = forecasts,
            telemetry = markers(forecasts),
            snapshot = acceptedSnapshot(),
            currentSettings = identity(),
            authoritativeNowTs = GENERATION_TS + AcceptedSensitivityTupleFreshness.MAX_AGE_MS + 1L
        )

        assertThat(result.error).isEqualTo(AcceptedForecastTupleError.FRESHNESS_INVALID)
        assertThat(result.sensitivity).isNull()
    }

    private fun rows(base: Double): List<ForecastEntity> = listOf(5, 30, 60).map { horizon ->
        ForecastEntity(
            id = horizon.toLong(),
            timestamp = GENERATION_TS + horizon * 60_000L,
            horizonMinutes = horizon,
            valueMmol = base + horizon / 100.0,
            ciLow = base - 0.5,
            ciHigh = base + 0.5,
            modelVersion = "test"
        )
    }

    private fun acceptedSnapshot() = testSensitivityRuntimeSnapshot(
        settingsRevision = 7L,
        cycleId = "cycle-7",
        timestamp = GENERATION_TS
    )

    private fun calibrationModel(id: String, offsetMmol: Double) = GlucoseCalibrationModel(
        id = id,
        sensorSessionKey = "session-1",
        createdAt = GENERATION_TS - 60_000L,
        validFromTs = GENERATION_TS - 60_000L,
        validToTs = GENERATION_TS + 72L * 60L * 60L * 1000L,
        modelType = GlucoseCalibrationModelType.OFFSET,
        gain = 1.0,
        offsetMmol = offsetMmol,
        confidence = 0.9,
        checkCount = 2,
        sensorAgeHours = 4.0,
        lagMinutesAtFit = 10.0,
        status = GlucoseCalibrationModelStatus.ACTIVE,
        diagnosticsJson = "{\"source\":\"test\"}"
    )

    private fun calibrationMarkers(
        model: GlucoseCalibrationModel?,
        authorityToken: String = ACCEPTED_CALIBRATION_NONE
    ): List<TelemetrySampleEntity> = listOf(
        telemetry(
            key = ACCEPTED_CALIBRATION_MODEL_ID_KEY,
            valueText = model?.id ?: ACCEPTED_CALIBRATION_NONE,
            source = ACCEPTED_CALIBRATION_SOURCE
        ),
        telemetry(
            key = ACCEPTED_CALIBRATION_SESSION_KEY,
            valueText = model?.sensorSessionKey ?: ACCEPTED_CALIBRATION_NONE,
            source = ACCEPTED_CALIBRATION_SOURCE
        ),
        telemetry(
            key = ACCEPTED_CALIBRATION_PREPARED_AT_KEY,
            valueText = GENERATION_TS.toString(),
            source = ACCEPTED_CALIBRATION_SOURCE
        ),
        telemetry(
            key = ACCEPTED_CALIBRATION_MODEL_FINGERPRINT_KEY,
            valueText = model?.let(::acceptedCalibrationModelFingerprint) ?: ACCEPTED_CALIBRATION_NONE,
            source = ACCEPTED_CALIBRATION_SOURCE
        ),
        telemetry(
            key = ACCEPTED_CALIBRATION_AUTHORITY_TOKEN_KEY,
            valueText = authorityToken,
            source = ACCEPTED_CALIBRATION_SOURCE
        )
    )

    private fun markers(
        forecasts: List<ForecastEntity>,
        calibrationModel: GlucoseCalibrationModel? = null,
        authorityToken: String = ACCEPTED_CALIBRATION_NONE
    ): List<TelemetrySampleEntity> = sensitivityMarkers(forecasts) +
        calibrationMarkers(calibrationModel, authorityToken)

    private fun sensitivityMarkers(forecasts: List<ForecastEntity>): List<TelemetrySampleEntity> = listOf(
        telemetry(SENSITIVITY_ACCEPTED_CYCLE_ID_KEY, valueText = "cycle-7"),
        telemetry(SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY, valueDouble = 7.0),
        telemetry(SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY, valueDouble = GENERATION_TS.toDouble()),
        telemetry(
            SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY,
            valueText = SensitivityAcceptedForecastDecompositionCodec.encode(decomposition())
        ),
        telemetry(
            SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY,
            valueText = SensitivityAcceptedForecastDigest.compute(
                cycleId = "cycle-7",
                settingsRevision = 7L,
                forecasts = forecasts.map {
                    SensitivityAcceptedForecastRow(
                        horizonMinutes = it.horizonMinutes,
                        targetTimestamp = it.timestamp,
                        valueMmol = it.valueMmol,
                        ciLow = it.ciLow,
                        ciHigh = it.ciHigh,
                        modelVersion = it.modelVersion
                    )
                },
                decomposition = decomposition()
            )
        ),
        telemetry(
            SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY,
            valueText = SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED
        )
    )

    private fun identity() = SensitivityRuntimeSettingsIdentity(
        revision = 7L,
        isfSource = SensitivitySourcePreference.COPILOT,
        crSource = SensitivitySourcePreference.COPILOT
    )

    private fun decomposition() = SensitivityAcceptedForecastDecomposition(
        trend60Mmol = 0.7,
        therapy60Mmol = -0.4,
        uam60Mmol = 0.2,
        residualRoc0Mmol5 = 0.05,
        sigmaEMmol5 = 0.11,
        kfSigmaGMmol = 0.09,
        modelVersion = "local-v3"
    )

    private fun telemetry(
        key: String,
        valueDouble: Double? = null,
        valueText: String? = null,
        timestamp: Long = MARKER_TS,
        source: String = "copilot_sensitivity_cycle"
    ) = TelemetrySampleEntity(
        id = key,
        timestamp = timestamp,
        source = source,
        key = key,
        valueDouble = valueDouble,
        valueText = valueText,
        unit = null,
        quality = "OK"
    )

    private companion object {
        const val GENERATION_TS = 1_800_000_000_000L
        const val MARKER_TS = 1_800_000_100_000L
    }
}
