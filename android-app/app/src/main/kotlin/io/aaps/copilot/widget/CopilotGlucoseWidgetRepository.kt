package io.aaps.copilot.widget

import androidx.room.withTransaction
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.TelemetrySampleSelector
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_AUTHORITY_TOKEN_KEY
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_SOURCE
import io.aaps.copilot.data.repository.AcceptedSensitivityTupleRoomLoader
import io.aaps.copilot.data.repository.CalibrationAuthorityStateCodec
import io.aaps.copilot.data.repository.CalibrationModelAuthority
import io.aaps.copilot.data.repository.loadCurrentGlucoseCalibrationAuthorityInTransaction
import io.aaps.copilot.domain.model.GlucoseCalibrationModel
import io.aaps.copilot.domain.predict.SensitivityRuntimeSettingsIdentity
import kotlin.math.abs

class CopilotGlucoseWidgetRepository(
    private val db: CopilotDatabase,
    private val currentSettingsIdentity: suspend () -> SensitivityRuntimeSettingsIdentity
) {
    suspend fun loadSnapshot(nowMs: Long = System.currentTimeMillis()): CopilotGlucoseWidgetSnapshot {
        val identityBefore = currentSettingsIdentity()
        // Display calibration and accepted forecast authority must share one Room snapshot.
        val snapshot = db.withTransaction {
            loadSnapshotInTransaction(nowMs, identityBefore)
        }
        return if (identityBefore == currentSettingsIdentity()) {
            snapshot
        } else {
            snapshot.copy(predicted30Mmol = null, forecastAgeMinutes = null)
        }
    }

    private suspend fun loadSnapshotInTransaction(
        nowMs: Long,
        identity: SensitivityRuntimeSettingsIdentity
    ): CopilotGlucoseWidgetSnapshot {
        val currentCalibrationAuthority = loadCurrentGlucoseCalibrationAuthorityInTransaction(db, nowMs)
        val causalGlucose = currentCalibrationAuthority.causalGlucose
        val latestGlucose = causalGlucose.latest
        val previousGlucose = causalGlucose.previous

        val telemetryByKey = db.telemetryDao()
            .latestByKeysSince(
                since = nowMs - TELEMETRY_LOOKBACK_MS,
                keys = WIDGET_TELEMETRY_KEYS
            )
            .let(TelemetrySampleSelector::selectLatestByKey)

        val acceptedForecast = resolveAcceptedForecast30(nowMs, identity).takeIf { accepted ->
            isWidgetForecastCalibrationCoherent(
                acceptedModel = accepted.calibrationModel,
                acceptedSessionKey = accepted.calibrationSessionKey,
                acceptedAuthorityToken = accepted.calibrationAuthorityToken,
                currentModel = currentCalibrationAuthority.activeModel,
                currentSessionKey = currentCalibrationAuthority.context.currentSessionKey,
                currentAuthorityToken = currentCalibrationAuthority.authorityToken,
                currentContextValid = currentCalibrationAuthority.context.contextValid
            )
        } ?: AcceptedWidgetForecast()
        val displayCalibrationModel = currentCalibrationAuthority.activeModel
        val currentGlucose = latestGlucose?.let { applyCalibration(it, displayCalibrationModel) }
        val previousCalibrated = previousGlucose?.let { applyCalibration(it, displayCalibrationModel) }
        val trendDelta = if (currentGlucose != null && previousCalibrated != null) {
            currentGlucose - previousCalibrated
        } else {
            null
        }

        val iob = resolveByRecency(
            telemetryByKey["iob_units"],
            telemetryByKey["iob_effective_units"],
            telemetryByKey["iob_real_units"]
        )?.coerceIn(0.0, 30.0)
        val cob = resolveByRecency(
            telemetryByKey["cob_effective_grams"],
            telemetryByKey["cob_grams"],
            telemetryByKey["raw_cob"]
        )?.coerceIn(0.0, 400.0)

        return CopilotGlucoseWidgetSnapshot(
            currentGlucoseMmol = currentGlucose,
            rawGlucoseMmol = latestGlucose?.mmol,
            predicted30Mmol = acceptedForecast.forecast30?.valueMmol,
            iobUnits = iob,
            cobGrams = cob,
            trendDeltaMmol = trendDelta,
            sampleAgeMinutes = latestGlucose?.timestamp?.ageMinutes(nowMs),
            forecastAgeMinutes = acceptedForecast.generationTimestamp?.ageMinutes(nowMs),
            updatedAtMs = nowMs,
            calibrationApplied = latestGlucose?.let { latest ->
                abs((currentGlucose ?: latest.mmol) - latest.mmol) >= 0.01
            } ?: false
        )
    }

    private suspend fun resolveAcceptedForecast30(
        nowMs: Long,
        identity: SensitivityRuntimeSettingsIdentity
    ): AcceptedWidgetForecast {
        val accepted = AcceptedSensitivityTupleRoomLoader(db).load(identity, nowMs)
            ?: return AcceptedWidgetForecast()
        val authorityToken = db.telemetryDao().atTimestampBySourceAndKeys(
            source = ACCEPTED_CALIBRATION_SOURCE,
            timestamp = accepted.acceptedAtTs,
            keys = listOf(ACCEPTED_CALIBRATION_AUTHORITY_TOKEN_KEY)
        ).singleOrNull()?.valueText ?: return AcceptedWidgetForecast()
        return AcceptedWidgetForecast(
            forecast30 = accepted.accepted.forecastsByHorizon[30],
            generationTimestamp = accepted.accepted.generationTimestamp,
            calibrationModel = accepted.calibrationModel,
            calibrationSessionKey = accepted.accepted.calibrationSessionKey,
            calibrationAuthorityToken = authorityToken
        )
    }

    private fun applyCalibration(
        sample: GlucoseSampleEntity,
        model: GlucoseCalibrationModel?
    ): Double {
        val activeModel = model ?: return sample.mmol
        return CalibrationModelAuthority.applyToGlucose(
            pointTs = sample.timestamp,
            rawMmol = sample.mmol,
            model = activeModel
        )
    }

    private fun TelemetrySampleEntity?.numericValue(): Double? {
        val sample = this ?: return null
        return sample.valueDouble ?: sample.valueText?.replace(",", ".")?.toDoubleOrNull()
    }

    private fun resolveByRecency(vararg samples: TelemetrySampleEntity?): Double? {
        return samples
            .filterNotNull()
            .maxByOrNull { it.timestamp }
            .numericValue()
    }

    private fun Long.ageMinutes(nowMs: Long): Long = ((nowMs - this).coerceAtLeast(0L)) / 60_000L

    private companion object {
        private const val TELEMETRY_LOOKBACK_MS = 6L * 60L * 60L * 1000L
        private val WIDGET_TELEMETRY_KEYS = listOf(
            "glucose_calibrated_mmol",
            "iob_units",
            "iob_effective_units",
            "iob_real_units",
            "cob_effective_grams",
            "cob_grams",
            "raw_cob"
        )
    }
}

private data class AcceptedWidgetForecast(
    val forecast30: io.aaps.copilot.data.local.entity.ForecastEntity? = null,
    val generationTimestamp: Long? = null,
    val calibrationModel: GlucoseCalibrationModel? = null,
    val calibrationSessionKey: String? = null,
    val calibrationAuthorityToken: String? = null
)

internal fun isWidgetForecastCalibrationCoherent(
    acceptedModel: GlucoseCalibrationModel?,
    acceptedSessionKey: String?,
    acceptedAuthorityToken: String?,
    currentModel: GlucoseCalibrationModel?,
    currentSessionKey: String?,
    currentAuthorityToken: String?,
    currentContextValid: Boolean
): Boolean {
    if (
        !currentContextValid ||
        acceptedAuthorityToken == null ||
        acceptedAuthorityToken != currentAuthorityToken ||
        acceptedSessionKey != currentSessionKey ||
        acceptedModel != currentModel
    ) return false
    return CalibrationAuthorityStateCodec.exactlyRepresents(
        token = acceptedAuthorityToken,
        model = acceptedModel,
        sessionKey = acceptedSessionKey
    )
}
