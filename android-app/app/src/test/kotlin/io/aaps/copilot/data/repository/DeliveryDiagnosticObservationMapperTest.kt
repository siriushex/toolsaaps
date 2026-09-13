package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.GlucoseCalibrationCycleIdentity
import io.aaps.copilot.domain.predict.*
import org.junit.Test

class DeliveryDiagnosticObservationMapperTest {
    private val now = 1_800_000_000_000L
    private val metric = SensitivityMetricDecision(SensitivitySourcePreference.AAPS, SensitivityResolvedSource.AAPS,
        2.0, null, 2.0, null, 2.0, 1.0, null)
    private val sensitivity = SensitivityRuntimeSnapshot(1, "cycle", now, metric, metric.copy(effective = 10.0))
    private val insulin = InsulinRuntimeSnapshot(now, InsulinRuntimeSource.AAPS_COMPONENTS, 3.0, 2.0, 1.0,
        0.02, 3.0, 1.0, null, now, 1.0)
    private val forecasts = listOf(5, 30, 60).map { Forecast(now + it * 60_000L, it, 12.0, 11.0, 13.0, "test") }
    private fun map(iob: InsulinRuntimeSnapshot? = insulin, f: List<Forecast> = forecasts,
        s: SensitivityRuntimeSnapshot = sensitivity, generation: Long = now, trusted: Boolean = true,
        calibration: GlucoseCalibrationCycleIdentity? = GlucoseCalibrationCycleIdentity("model", "session", now)) =
        DeliveryDiagnosticObservationMapper.map(now, GlucosePoint(now, 12.0, "CGM"), iob, s, f,
            generation, trusted, calibration, 40.0, true)

    @Test fun acceptedSnapshotKeepsFoodAndActivityAndActualForecastTarget() {
        val sample = requireNotNull(map())
        assertThat(sample.cobGrams).isEqualTo(40.0)
        assertThat(sample.uamActive).isTrue()
        assertThat(sample.activityUnitsPerMinute).isEqualTo(0.02)
        assertThat(sample.forecast30TargetTs).isEqualTo(now + 30 * 60_000L)
        val delayed = forecasts.map { it.copy(ts = it.ts - 60_000L) }
        assertThat(map(f = delayed, generation = now - 60_000L)?.forecast30TargetTs).isEqualTo(now + 29 * 60_000L)
    }
    @Test fun missingActivityStaleEvidenceUntrustedSensorAndFallbackAreUnavailable() {
        listOf(null, insulin.copy(insulinActivity = null), insulin.copy(insulinActivity = -0.01),
            insulin.copy(evidenceTimestamp = now - 7 * 60_000L), insulin.copy(timestamp = now + 1),
            insulin.copy(source = InsulinRuntimeSource.LEGACY_AAPS), insulin.copy(confidence = Double.NaN),
            insulin.copy(therapyCoverage = 0.69)).forEach { assertThat(map(iob = it)).isNull() }
        assertThat(map(trusted = false)).isNull()
        assertThat(map(calibration = null)).isNull()
    }
    @Test fun missingDuplicateLowMalformedOrMismatchedForecastIsUnavailable() {
        assertThat(map(f = forecasts.drop(1))).isNull()
        assertThat(map(f = forecasts + forecasts[0])).isNull()
        for (index in forecasts.indices) {
            assertThat(map(f = forecasts.mapIndexed { j, f -> if (j == index) f.copy(ciLow = 3.9) else f })).isNull()
        }
        assertThat(map(f = forecasts.map { it.copy(ciHigh = Double.NaN) })).isNull()
        assertThat(map(generation = now - 1)).isNull()
        assertThat(map(s = sensitivity.copy(timestamp = now - 7 * 60_000L))).isNull()
    }
    @Test fun basisIgnoresPreparationTimeButChangesWithCalibrationOrSettings() {
        val basis = requireNotNull(map()).basisKey
        assertThat(map(calibration = GlucoseCalibrationCycleIdentity("model", "session", now - 60_000L))?.basisKey).isEqualTo(basis)
        assertThat(map(calibration = GlucoseCalibrationCycleIdentity("new", "session", now))?.basisKey).isNotEqualTo(basis)
        assertThat(map(s = sensitivity.copy(settingsRevision = 2))?.basisKey).isNotEqualTo(basis)
    }

    @Test fun actualAcceptedFanOutAllowsDelayedAcceptanceButRejectsMixedUamAuthority() {
        val origin = now - 60_000L
        val calibration = GlucoseCalibrationCycleIdentity("model", "session", now)
        val authority = AutomationRepository.AcceptedClinicalForecasts(
            forecasts.map { it.copy(ts = it.ts - 60_000L) }, origin, "accepted-digest")
        val uam = AutomationRepository.projectUnifiedUamRuntimeStatic(null, 40.0, false,
            SensitivityRuntimeConsumerContext(SensitivityRuntimeConsumer.UAM, sensitivity), calibration).copy(
                timestamp = origin, flag = 1.0, acceptedForecastGenerationTimestamp = origin,
                acceptedForecastDigest = authority.digest, acceptedForecasts = authority.forecasts)
        val context = AutomationRepository.InsulinCycleContext(now, origin, insulin, insulin, 3.0, 3.0, 0.0, true, "ok")
        fun accepted(u: AutomationRepository.UnifiedUamRuntimeSnapshot) = DeliveryDiagnosticObservationMapper.mapAccepted(
            now, GlucosePoint(origin, 12.0, "CGM"), true, context,
            SensitivityRuntimeConsumerContext(SensitivityRuntimeConsumer.ALERT_CAUSE, sensitivity), u, authority, calibration)
        assertThat(accepted(uam)?.forecast30TargetTs).isEqualTo(origin + 30 * 60_000L)
        assertThat(accepted(uam)?.uamActive).isTrue()
        assertThat(accepted(uam.copy(sensitivitySettingsRevision = 9))).isNull()
        assertThat(accepted(uam.copy(acceptedForecastDigest = "other"))).isNull()
        assertThat(accepted(uam.copy(timestamp = now))).isNull()
    }
}
