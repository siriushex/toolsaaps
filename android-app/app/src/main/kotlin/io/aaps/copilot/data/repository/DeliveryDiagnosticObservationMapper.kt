package io.aaps.copilot.data.repository

import com.google.gson.Gson
import io.aaps.copilot.domain.alerts.DeliveryDiagnosticObservation
import io.aaps.copilot.domain.alerts.DeliveryDiagnosticPolicy
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucoseCalibrationCycleIdentity
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.predict.InsulinRuntimeSnapshot
import io.aaps.copilot.domain.predict.InsulinRuntimeSource
import io.aaps.copilot.domain.predict.SensitivityRuntimeSnapshot
import io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer
import io.aaps.copilot.domain.predict.SensitivityRuntimeConsumerContext
import java.security.MessageDigest

internal object DeliveryDiagnosticObservationMapper {
    fun mapAccepted(
        nowTs: Long, glucose: GlucosePoint, sensorTrusted: Boolean,
        insulin: AutomationRepository.InsulinCycleContext, sensitivity: SensitivityRuntimeConsumerContext,
        uam: AutomationRepository.UnifiedUamRuntimeSnapshot, forecasts: AutomationRepository.AcceptedClinicalForecasts,
        calibration: GlucoseCalibrationCycleIdentity
    ): DeliveryDiagnosticObservation? {
        // UAM time is the forecast origin (CGM time), not the later accepted-cycle wall clock.
        if (insulin.cycleTimestamp != nowTs || uam.timestamp != forecasts.generationTimestamp ||
            sensitivity.consumer != SensitivityRuntimeConsumer.ALERT_CAUSE ||
            uam.sensitivityCycleId != sensitivity.snapshot.forecastCycleId ||
            uam.sensitivitySettingsRevision != sensitivity.snapshot.settingsRevision ||
            uam.sensitivityIsfMmolPerUnit != sensitivity.snapshot.isf.effective ||
            uam.sensitivityCrGramPerUnit != sensitivity.snapshot.cr.effective ||
            uam.acceptedForecastGenerationTimestamp != forecasts.generationTimestamp ||
            uam.acceptedForecastDigest != forecasts.digest || uam.calibrationIdentity != calibration
        ) return null
        return map(nowTs, glucose, insulin.snapshot, sensitivity.snapshot, forecasts.forecasts,
            forecasts.generationTimestamp, sensorTrusted && !uam.sensorBlocked, calibration,
            uam.effectiveCobGrams, uam.flag > 0.0 || uam.controlFlag > 0.0)
    }

    fun map(
        nowTs: Long,
        glucose: GlucosePoint,
        insulin: InsulinRuntimeSnapshot?,
        sensitivity: SensitivityRuntimeSnapshot,
        forecasts: List<Forecast>,
        generationTimestamp: Long,
        sensorTrusted: Boolean,
        calibration: GlucoseCalibrationCycleIdentity?,
        cobGrams: Double,
        uamActive: Boolean
    ): DeliveryDiagnosticObservation? {
        fun fresh(ts: Long?) = ts != null && ts > 0 && ts <= nowTs && nowTs - ts <= DeliveryDiagnosticPolicy.MAX_GAP_MS
        if (!sensorTrusted || glucose.quality != DataQuality.OK || !fresh(glucose.ts) || !fresh(generationTimestamp) ||
            !fresh(sensitivity.timestamp) || !fresh(calibration?.preparedAtTs) || glucose.valueMmol < 4.0
        ) return null
        insulin ?: return null
        if (insulin.source != InsulinRuntimeSource.AAPS_COMPONENTS || !fresh(insulin.timestamp) ||
            !fresh(insulin.evidenceTimestamp) || insulin.evidenceTimestamp != insulin.timestamp ||
            insulin.confidence !in 0.7..1.0 || insulin.therapyCoverage !in 0.7..1.0 ||
            sensitivity.isf.confidence !in 0.7..1.0 || sensitivity.cr.confidence !in 0.7..1.0
        ) return null
        val activity = insulin.insulinActivity ?: return null
        if (forecasts.size != 3 || forecasts.map { it.horizonMinutes }.toSet() != setOf(5, 30, 60)) return null
        if (forecasts.any { f ->
            !f.valueMmol.isFinite() || !f.ciLow.isFinite() || !f.ciHigh.isFinite() || f.ciLow < 4.0 ||
                f.ciLow > f.valueMmol || f.valueMmol > f.ciHigh || f.ts <= 0 ||
                f.ts - f.horizonMinutes * 60_000L != generationTimestamp
        }) return null
        val f30 = forecasts.single { it.horizonMinutes == 30 }
        // Stable identity excludes per-cycle timestamps and values; never mix calibration/source revisions.
        val basis = Gson().toJson(listOf("delivery-v1", glucose.source, calibration?.modelId, calibration?.sensorSessionKey,
            sensitivity.settingsRevision.toString(), sensitivity.isf.resolved.name, sensitivity.cr.resolved.name))
        val hash = MessageDigest.getInstance("SHA-256").digest(basis.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return DeliveryDiagnosticObservation(observedAt = nowTs, glucoseTs = glucose.ts, glucoseMmol = glucose.valueMmol,
            positiveIobUnits = insulin.effectivePositiveIobUnits, activityUnitsPerMinute = activity,
            isfMmolPerUnit = sensitivity.isf.effective, crGramsPerUnit = sensitivity.cr.effective,
            cobGrams = cobGrams, uamActive = uamActive, forecast30Mmol = f30.valueMmol,
            forecast30CiLow = f30.ciLow, forecast30CiHigh = f30.ciHigh, basisKey = hash,
            forecast30TargetTs = f30.ts).takeIf(DeliveryDiagnosticPolicy::validObservation)
    }
}
