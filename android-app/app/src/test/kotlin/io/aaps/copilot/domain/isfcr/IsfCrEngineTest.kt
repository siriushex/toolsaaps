package io.aaps.copilot.domain.isfcr

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.TelemetrySignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

class IsfCrEngineTest {

    @Test
    fun fitBaseModel_buildsHourlyStateFromEvidence() {
        val engine = IsfCrEngine()
        val now = 1_700_000_000_000L
        val history = buildHistory(nowTs = now)

        val result = engine.fitBaseModel(
            history = history,
            settings = IsfCrSettings(
                lookbackDays = 30,
                confidenceThreshold = 0.55
            ),
            existingState = null,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )

        assertEquals(24, result.state.hourlyIsf.size)
        assertEquals(24, result.state.hourlyCr.size)
        assertTrue(result.evidence.isNotEmpty())
        assertTrue(result.state.fitMetrics["isfEvidenceCount"] ?: 0.0 >= 1.0)
    }

    @Test
    fun computeRealtime_lowConfidenceUsesFallbackMode() {
        val engine = IsfCrEngine()
        val now = 1_700_100_000_000L
        val sparseGlucose = listOf(
            GlucosePoint(now - 30 * 60_000L, 7.2, "cgm", DataQuality.OK),
            GlucosePoint(now - 15 * 60_000L, 7.1, "cgm", DataQuality.OK),
            GlucosePoint(now, 7.0, "cgm", DataQuality.OK)
        )
        val result = engine.computeRealtime(
            nowTs = now,
            glucose = sparseGlucose,
            therapy = emptyList(),
            telemetry = emptyList(),
            tags = emptyList(),
            activeModel = null,
            previousSnapshot = null,
            settings = IsfCrSettings(
                lookbackDays = 30,
                confidenceThreshold = 0.9,
                shadowMode = false
            ),
            fallbackIsf = 2.5,
            fallbackCr = 12.0
        )

        assertEquals(IsfCrRuntimeMode.FALLBACK, result.snapshot.mode)
        assertTrue(result.snapshot.isfEff in 0.8..18.0)
        assertTrue(result.snapshot.crEff in 2.0..60.0)
        assertTrue(result.snapshot.reasons.contains("low_confidence_fallback"))
        assertEquals(IsfCrRuntimeMode.FALLBACK, result.diagnostics.mode)
        assertTrue(result.diagnostics.lowConfidence)
        assertTrue(result.diagnostics.qualityScore in 0.0..1.0)
    }

    @Test
    fun computeRealtime_appliesActivityFactorInShadowMode() {
        val engine = IsfCrEngine()
        val now = 1_700_200_000_000L
        val history = buildHistory(nowTs = now)
        val fit = engine.fitBaseModel(
            history = history,
            settings = IsfCrSettings(
                lookbackDays = 30,
                confidenceThreshold = 0.2,
                shadowMode = true
            ),
            existingState = null,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )
        val result = engine.computeRealtime(
            nowTs = now,
            glucose = history.glucose,
            therapy = history.therapy,
            telemetry = listOf(
                TelemetrySignal(now - 60_000L, "activity_ratio", 1.35, null),
                TelemetrySignal(now - 60_000L, "steps_rate_15m", 120.0, null),
                TelemetrySignal(now - 60_000L, "sensor_quality_score", 0.95, null)
            ),
            tags = emptyList(),
            activeModel = fit.state,
            previousSnapshot = null,
            settings = IsfCrSettings(
                lookbackDays = 30,
                confidenceThreshold = 0.2,
                shadowMode = true,
                useActivityFactor = true,
                minIsfEvidencePerHour = 0,
                minCrEvidencePerHour = 0
            ),
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )

        assertNotNull(result.snapshot.factors["activity_factor"])
        assertTrue((result.snapshot.factors["activity_factor"] ?: 1.0) >= 1.0)
        assertEquals(IsfCrRuntimeMode.SHADOW, result.snapshot.mode)
        assertTrue(result.diagnostics.qualityScore in 0.0..1.0)
    }

    @Test
    fun extractor_collectsDroppedReasonCounts() {
        val now = 1_700_300_000_000L
        val t0 = now - 5 * 60 * 60_000L
        val glucose = mutableListOf<GlucosePoint>()
        var ts = t0
        var value = 8.4
        while (ts <= now) {
            glucose += GlucosePoint(ts = ts, valueMmol = value, source = "cgm", quality = DataQuality.OK)
            value += -0.02
            ts += 5 * 60_000L
        }
        val therapy = listOf(
            TherapyEvent(
                ts = t0 + 60 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "0.05")
            ),
            TherapyEvent(
                ts = t0 + 120 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "1.0")
            ),
            TherapyEvent(
                ts = t0 + 130 * 60_000L,
                type = "meal",
                payload = mapOf("carbs" to "20")
            ),
            TherapyEvent(
                ts = t0 + 180 * 60_000L,
                type = "meal",
                payload = mapOf("carbs" to "30")
            )
        )
        val extractor = IsfCrWindowExtractor()
        val extraction = extractor.extract(
            history = IsfCrHistoryBundle(
                glucose = glucose,
                therapy = therapy,
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(),
            isfReference = 2.4
        )

        assertTrue(extraction.droppedCount >= 3)
        assertTrue((extraction.droppedReasonCounts["isf_small_units"] ?: 0) >= 1)
        assertTrue((extraction.droppedReasonCounts["isf_carbs_around"] ?: 0) >= 1)
        assertTrue((extraction.droppedReasonCounts["cr_no_bolus_nearby"] ?: 0) >= 1)
    }

    @Test
    fun computeRealtime_hourlyEvidenceBelowMinAddsReasonsAndDiagnostics() {
        val engine = IsfCrEngine()
        val now = 1_700_400_000_000L
        val history = buildHistory(nowTs = now)
        val fit = engine.fitBaseModel(
            history = history,
            settings = IsfCrSettings(
                lookbackDays = 30,
                confidenceThreshold = 0.2,
                shadowMode = false
            ),
            existingState = null,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )

        val result = engine.computeRealtime(
            nowTs = now,
            glucose = history.glucose,
            therapy = history.therapy,
            telemetry = emptyList(),
            tags = emptyList(),
            activeModel = fit.state,
            previousSnapshot = null,
            settings = IsfCrSettings(
                lookbackDays = 30,
                confidenceThreshold = 0.2,
                shadowMode = false,
                minIsfEvidencePerHour = 99,
                minCrEvidencePerHour = 99
            ),
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )

        assertTrue(result.snapshot.reasons.contains("isf_hourly_evidence_below_min"))
        assertTrue(result.snapshot.reasons.contains("cr_hourly_evidence_below_min"))
        assertEquals(99, result.diagnostics.minIsfEvidencePerHour)
        assertEquals(99, result.diagnostics.minCrEvidencePerHour)
        assertTrue(result.diagnostics.hourWindowIsfEvidenceCount < result.diagnostics.minIsfEvidencePerHour)
        assertTrue(result.diagnostics.hourWindowCrEvidenceCount < result.diagnostics.minCrEvidencePerHour)
        assertEquals(IsfCrRuntimeMode.FALLBACK, result.snapshot.mode)
    }

    @Test
    fun fallbackResolver_keepsCrCandidateWhenGlobalCrEvidenceIsStrong() {
        val resolver = IsfCrFallbackResolver()
        val snapshot = IsfCrRealtimeSnapshot(
            id = "snapshot-test",
            ts = 1_700_900_000_000L,
            isfEff = 2.8,
            crEff = 15.4,
            isfBase = 3.1,
            crBase = 10.0,
            ciIsfLow = 2.0,
            ciIsfHigh = 3.6,
            ciCrLow = 12.0,
            ciCrHigh = 18.8,
            confidence = 0.42,
            qualityScore = 0.46,
            factors = mapOf(
                "isf_hour_window_evidence_enough" to 0.0,
                "cr_hour_window_evidence_enough" to 0.0,
                "isf_global_evidence_strong" to 0.0,
                "cr_global_evidence_strong" to 1.0,
                "sensor_quality_suspect_false_low" to 0.0
            ),
            mode = IsfCrRuntimeMode.SHADOW,
            isfEvidenceCount = 0,
            crEvidenceCount = 5,
            reasons = listOf("isf_evidence_sparse", "cr_hourly_evidence_below_min")
        )

        val resolved = resolver.resolve(
            snapshot = snapshot,
            settings = IsfCrSettings(
                confidenceThreshold = 0.55,
                minIsfEvidencePerHour = 2,
                minCrEvidencePerHour = 2
            ),
            fallbackIsf = 3.1,
            fallbackCr = 10.0
        )

        assertThat(resolved.mode).isEqualTo(IsfCrRuntimeMode.FALLBACK)
        assertThat(resolved.reasons).contains("partial_metric_keep")
        assertThat(resolved.reasons).contains("isf_metric_fallback_applied")
        assertThat(resolved.reasons).doesNotContain("cr_metric_fallback_applied")
        assertThat(resolved.isfEff).isWithin(0.001).of(3.1)
        assertThat(resolved.crEff).isWithin(0.001).of(15.4)
    }

    @Test
    fun fallbackResolver_promotesSoftShadowWhenBothMetricsHaveCompEvidenceSupport() {
        val resolver = IsfCrFallbackResolver()
        val snapshot = IsfCrRealtimeSnapshot(
            id = "snapshot-soft-shadow",
            ts = 1_700_901_000_000L,
            isfEff = 2.75,
            crEff = 15.8,
            isfBase = 3.1,
            crBase = 10.2,
            ciIsfLow = 2.2,
            ciIsfHigh = 3.3,
            ciCrLow = 13.0,
            ciCrHigh = 18.6,
            confidence = 0.48,
            qualityScore = 0.56,
            factors = mapOf(
                "isf_hour_window_evidence_enough" to 1.0,
                "cr_hour_window_evidence_enough" to 1.0,
                "isf_global_evidence_strong" to 1.0,
                "cr_global_evidence_strong" to 1.0,
                "sensor_quality_suspect_false_low" to 0.0
            ),
            mode = IsfCrRuntimeMode.SHADOW,
            isfEvidenceCount = 3,
            crEvidenceCount = 5,
            reasons = listOf("candidate_ready")
        )

        val resolved = resolver.resolve(
            snapshot = snapshot,
            settings = IsfCrSettings(
                confidenceThreshold = 0.55,
                shadowMode = true,
                minIsfEvidencePerHour = 2,
                minCrEvidencePerHour = 2
            ),
            fallbackIsf = 3.1,
            fallbackCr = 10.0
        )

        assertThat(resolved.mode).isEqualTo(IsfCrRuntimeMode.SHADOW)
        assertThat(resolved.isfEff).isWithin(0.001).of(2.75)
        assertThat(resolved.crEff).isWithin(0.001).of(15.8)
        assertThat(resolved.reasons).contains("soft_shadow_keep")
        assertThat(resolved.reasons).doesNotContain("low_confidence_fallback")
    }

    @Test
    fun computeRealtime_addsAgeAndAmbiguityReasons() {
        val engine = IsfCrEngine()
        val now = 1_700_500_000_000L
        val baseHistory = buildHistory(nowTs = now)
        val agedTherapy = baseHistory.therapy
            .filterNot { event ->
                event.type.lowercase().contains("infusion_set_change") ||
                    event.type.lowercase().contains("sensor_change")
            } + listOf(
            TherapyEvent(
                ts = now - 9L * 24L * 60L * 60L * 1_000L,
                type = "infusion_set_change",
                payload = emptyMap()
            ),
            TherapyEvent(
                ts = now - 8L * 24L * 60L * 60L * 1_000L,
                type = "sensor_change",
                payload = emptyMap()
            )
        )

        val fit = engine.fitBaseModel(
            history = IsfCrHistoryBundle(
                glucose = baseHistory.glucose,
                therapy = agedTherapy,
                telemetry = emptyList(),
                tags = emptyList()
            ),
            settings = IsfCrSettings(
                lookbackDays = 30,
                confidenceThreshold = 0.2,
                shadowMode = true
            ),
            existingState = null,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )

        val result = engine.computeRealtime(
            nowTs = now,
            glucose = baseHistory.glucose,
            therapy = agedTherapy,
            telemetry = listOf(
                TelemetrySignal(now - 60_000L, "stress_score", 0.92, null),
                TelemetrySignal(now - 60_000L, "sensor_quality_score", 0.90, null)
            ),
            tags = emptyList(),
            activeModel = fit.state,
            previousSnapshot = null,
            settings = IsfCrSettings(
                lookbackDays = 30,
                confidenceThreshold = 0.2,
                shadowMode = true,
                minIsfEvidencePerHour = 0,
                minCrEvidencePerHour = 0
            ),
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )

        assertThat(result.snapshot.reasons).contains("set_age_high")
        assertThat(result.snapshot.reasons).contains("sensor_age_high")
        assertThat(result.snapshot.reasons).contains("context_ambiguity_high")
    }

    @Test
    fun computeRealtime_sensorFalseLowFlagAddsReasonAndLowersConfidence() {
        val engine = IsfCrEngine()
        val now = 1_700_520_000_000L
        val history = buildHistory(nowTs = now)
        val fit = engine.fitBaseModel(
            history = history,
            settings = IsfCrSettings(
                lookbackDays = 30,
                confidenceThreshold = 0.2,
                shadowMode = true
            ),
            existingState = null,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )
        val base = engine.computeRealtime(
            nowTs = now,
            glucose = history.glucose,
            therapy = history.therapy,
            telemetry = listOf(
                TelemetrySignal(now - 60_000L, "sensor_quality_score", 0.92, null)
            ),
            tags = emptyList(),
            activeModel = fit.state,
            previousSnapshot = null,
            settings = IsfCrSettings(
                lookbackDays = 30,
                confidenceThreshold = 0.2,
                shadowMode = true,
                minIsfEvidencePerHour = 0,
                minCrEvidencePerHour = 0
            ),
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )
        val flagged = engine.computeRealtime(
            nowTs = now,
            glucose = history.glucose,
            therapy = history.therapy,
            telemetry = listOf(
                TelemetrySignal(now - 60_000L, "sensor_quality_score", 0.92, null),
                TelemetrySignal(now - 60_000L, "sensor_quality_suspect_false_low", 1.0, null)
            ),
            tags = emptyList(),
            activeModel = fit.state,
            previousSnapshot = null,
            settings = IsfCrSettings(
                lookbackDays = 30,
                confidenceThreshold = 0.2,
                shadowMode = true,
                minIsfEvidencePerHour = 0,
                minCrEvidencePerHour = 0
            ),
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )

        assertThat(flagged.snapshot.reasons).contains("sensor_quality_suspect_false_low")
        assertThat(flagged.snapshot.confidence).isLessThan(base.snapshot.confidence)
        assertThat(flagged.snapshot.qualityScore).isLessThan(base.snapshot.qualityScore)
        val baseIsfWidth = base.snapshot.ciIsfHigh - base.snapshot.ciIsfLow
        val flaggedIsfWidth = flagged.snapshot.ciIsfHigh - flagged.snapshot.ciIsfLow
        assertThat(flaggedIsfWidth).isGreaterThan(baseIsfWidth)
    }

    @Test
    fun computeRealtime_addsDayTypeSparseReasonsWhenHourWindowHasOnlyOtherDayType() {
        val engine = IsfCrEngine()
        val zoneId = ZoneId.systemDefault()
        val saturdayHistoryNowTs = ZonedDateTime.of(2026, 2, 28, 8, 0, 0, 0, zoneId)
            .toInstant()
            .toEpochMilli()

        val weekendHistory = buildHistory(nowTs = saturdayHistoryNowTs)
        val correctionTs = weekendHistory.therapy
            .first { it.type == "correction_bolus" }
            .ts
        val correctionHour = Instant.ofEpochMilli(correctionTs).atZone(zoneId).hour
        val mondayNowTs = ZonedDateTime.of(2026, 3, 2, correctionHour, 0, 0, 0, zoneId)
            .toInstant()
            .toEpochMilli()
        val fit = engine.fitBaseModel(
            history = weekendHistory,
            settings = IsfCrSettings(
                lookbackDays = 30,
                confidenceThreshold = 0.2,
                shadowMode = true
            ),
            existingState = null,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )

        val result = engine.computeRealtime(
            nowTs = mondayNowTs,
            glucose = weekendHistory.glucose,
            therapy = weekendHistory.therapy,
            telemetry = emptyList(),
            tags = emptyList(),
            activeModel = fit.state,
            previousSnapshot = null,
            settings = IsfCrSettings(
                lookbackDays = 30,
                confidenceThreshold = 0.2,
                shadowMode = true,
                minIsfEvidencePerHour = 0,
                minCrEvidencePerHour = 0
            ),
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )

        assertThat(result.snapshot.reasons).contains("isf_day_type_evidence_sparse")
    }

    @Test
    fun fitBaseModel_persistsDayTypeHourlyParams_whenCoverageIsSufficientOrReportsSparseCoverage() {
        val engine = IsfCrEngine()
        val zoneId = ZoneId.systemDefault()
        val saturdayNowTs = ZonedDateTime.of(2026, 2, 28, 8, 0, 0, 0, zoneId)
            .toInstant()
            .toEpochMilli()
        val history = buildHistory(nowTs = saturdayNowTs)

        val fit = engine.fitBaseModel(
            history = history,
            settings = IsfCrSettings(
                lookbackDays = 30,
                confidenceThreshold = 0.2,
                shadowMode = true
            ),
            existingState = null,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )

        val weekendIsfKeys = fit.state.params.keys.filter { it.startsWith("weekend_isf_h") }
        val weekendCrKeys = fit.state.params.keys.filter { it.startsWith("weekend_cr_h") }
        val weekendIsfCoverage = fit.state.fitMetrics["weekendIsfCoverageHours"] ?: 0.0
        val weekendCrCoverage = fit.state.fitMetrics["weekendCrCoverageHours"] ?: 0.0

        if (weekendIsfCoverage >= 1.0) {
            assertThat(weekendIsfKeys).isNotEmpty()
        } else {
            assertThat(weekendIsfKeys).isEmpty()
        }
        if (weekendCrCoverage >= 1.0) {
            assertThat(weekendCrKeys).isNotEmpty()
        } else {
            assertThat(weekendCrKeys).isEmpty()
        }
    }

    @Test
    fun computeRealtime_prefersDayTypeBaseWhenAvailable() {
        val engine = IsfCrEngine()
        val zoneId = ZoneId.systemDefault()
        val mondayNowTs = ZonedDateTime.of(2026, 3, 2, 8, 0, 0, 0, zoneId)
            .toInstant()
            .toEpochMilli()
        val glucose = listOf(
            GlucosePoint(
                ts = mondayNowTs - 10 * 60_000L,
                valueMmol = 6.7,
                source = "cgm",
                quality = DataQuality.OK
            ),
            GlucosePoint(
                ts = mondayNowTs - 5 * 60_000L,
                valueMmol = 6.8,
                source = "cgm",
                quality = DataQuality.OK
            ),
            GlucosePoint(
                ts = mondayNowTs,
                valueMmol = 6.9,
                source = "cgm",
                quality = DataQuality.OK
            )
        )
        val activeModel = IsfCrModelState(
            updatedAt = mondayNowTs,
            hourlyIsf = List(24) { 2.20 },
            hourlyCr = List(24) { 10.0 },
            params = mapOf(
                "weekday_isf_h08" to 3.10,
                "weekday_cr_h08" to 14.0
            ),
            fitMetrics = emptyMap()
        )

        val result = engine.computeRealtime(
            nowTs = mondayNowTs,
            glucose = glucose,
            therapy = emptyList(),
            telemetry = emptyList(),
            tags = emptyList(),
            activeModel = activeModel,
            previousSnapshot = null,
            settings = IsfCrSettings(
                lookbackDays = 30,
                confidenceThreshold = 0.0,
                shadowMode = true,
                minIsfEvidencePerHour = 0,
                minCrEvidencePerHour = 0
            ),
            fallbackIsf = 2.4,
            fallbackCr = 10.5
        )

        assertThat(abs(result.snapshot.isfBase - 3.10)).isLessThan(0.001)
        assertThat(abs(result.snapshot.crBase - 14.0)).isLessThan(0.001)
        assertThat(result.snapshot.factors["isf_base_source_day_type"]).isEqualTo(1.0)
        assertThat(result.snapshot.factors["cr_base_source_day_type"]).isEqualTo(1.0)
    }

    @Test
    fun computeRealtime_usesHourlyBaseAndFlagsMissingDayTypeBase() {
        val engine = IsfCrEngine()
        val zoneId = ZoneId.systemDefault()
        val mondayNowTs = ZonedDateTime.of(2026, 3, 2, 9, 0, 0, 0, zoneId)
            .toInstant()
            .toEpochMilli()
        val glucose = listOf(
            GlucosePoint(
                ts = mondayNowTs - 10 * 60_000L,
                valueMmol = 6.1,
                source = "cgm",
                quality = DataQuality.OK
            ),
            GlucosePoint(
                ts = mondayNowTs - 5 * 60_000L,
                valueMmol = 6.2,
                source = "cgm",
                quality = DataQuality.OK
            ),
            GlucosePoint(
                ts = mondayNowTs,
                valueMmol = 6.3,
                source = "cgm",
                quality = DataQuality.OK
            )
        )
        val activeModel = IsfCrModelState(
            updatedAt = mondayNowTs,
            hourlyIsf = List(24) { 2.55 },
            hourlyCr = List(24) { 12.4 },
            params = emptyMap(),
            fitMetrics = emptyMap()
        )

        val result = engine.computeRealtime(
            nowTs = mondayNowTs,
            glucose = glucose,
            therapy = emptyList(),
            telemetry = emptyList(),
            tags = emptyList(),
            activeModel = activeModel,
            previousSnapshot = null,
            settings = IsfCrSettings(
                lookbackDays = 30,
                confidenceThreshold = 0.0,
                shadowMode = true,
                minIsfEvidencePerHour = 0,
                minCrEvidencePerHour = 0
            ),
            fallbackIsf = 2.0,
            fallbackCr = 9.5
        )

        assertThat(abs(result.snapshot.isfBase - 2.55)).isLessThan(0.001)
        assertThat(abs(result.snapshot.crBase - 12.4)).isLessThan(0.001)
        assertThat(result.snapshot.reasons).contains("isf_day_type_base_missing")
        assertThat(result.snapshot.reasons).contains("cr_day_type_base_missing")
        assertThat(result.snapshot.factors["isf_base_source_hourly"]).isEqualTo(1.0)
        assertThat(result.snapshot.factors["cr_base_source_hourly"]).isEqualTo(1.0)
        assertThat(result.diagnostics.isfBaseSource).isEqualTo("hourly")
        assertThat(result.diagnostics.crBaseSource).isEqualTo("hourly")
        assertThat(result.diagnostics.isfDayTypeBaseAvailable).isFalse()
        assertThat(result.diagnostics.crDayTypeBaseAvailable).isFalse()
    }

    private fun buildHistory(nowTs: Long): IsfCrHistoryBundle {
        val t0 = nowTs - 6 * 60 * 60_000L
        val glucose = mutableListOf<GlucosePoint>()
        var ts = t0
        var value = 8.8
        while (ts <= nowTs) {
            glucose += GlucosePoint(
                ts = ts,
                valueMmol = value,
                source = "cgm",
                quality = DataQuality.OK
            )
            value += when {
                ts in (t0 + 60 * 60_000L)..(t0 + 110 * 60_000L) -> -0.10
                ts in (t0 + 180 * 60_000L)..(t0 + 220 * 60_000L) -> 0.12
                else -> -0.01
            }
            ts += 5 * 60_000L
        }

        val therapy = listOf(
            TherapyEvent(
                ts = t0 + 55 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "1.0")
            ),
            TherapyEvent(
                ts = t0 + 180 * 60_000L,
                type = "meal",
                payload = mapOf("carbs" to "40")
            ),
            TherapyEvent(
                ts = t0 + 178 * 60_000L,
                type = "bolus",
                payload = mapOf("units" to "4.0")
            ),
            TherapyEvent(
                ts = t0 + 30 * 60_000L,
                type = "infusion_set_change",
                payload = emptyMap()
            )
        )

        return IsfCrHistoryBundle(
            glucose = glucose,
            therapy = therapy,
            telemetry = emptyList(),
            tags = emptyList()
        )
    }
}
