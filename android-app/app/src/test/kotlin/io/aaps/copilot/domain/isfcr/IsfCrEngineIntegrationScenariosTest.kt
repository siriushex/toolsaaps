package io.aaps.copilot.domain.isfcr

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.TelemetrySignal
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class IsfCrEngineIntegrationScenariosTest {

    private val engine = IsfCrEngine()

    @Test
    fun infusionSetAgingScenario_reducesSetFactorAndIsfEff() {
        val now = 1_700_900_000_000L
        val baselineHistory = buildHistory(
            nowTs = now,
            setAgeHours = 8.0,
            sensorAgeHours = 24.0
        )
        val agedHistory = buildHistory(
            nowTs = now,
            setAgeHours = 120.0,
            sensorAgeHours = 24.0
        )
        val settings = scenarioSettings()
        val baselineModel = engine.fitBaseModel(
            history = baselineHistory,
            settings = settings,
            existingState = null,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        ).state
        val agedModel = engine.fitBaseModel(
            history = agedHistory,
            settings = settings,
            existingState = null,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        ).state

        val baseline = engine.computeRealtime(
            nowTs = now,
            glucose = baselineHistory.glucose,
            therapy = baselineHistory.therapy,
            telemetry = listOf(TelemetrySignal(now - 60_000L, "sensor_quality_score", 0.95, null)),
            tags = emptyList(),
            activeModel = baselineModel,
            previousSnapshot = null,
            settings = settings,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )
        val aged = engine.computeRealtime(
            nowTs = now,
            glucose = agedHistory.glucose,
            therapy = agedHistory.therapy,
            telemetry = listOf(TelemetrySignal(now - 60_000L, "sensor_quality_score", 0.95, null)),
            tags = emptyList(),
            activeModel = agedModel,
            previousSnapshot = null,
            settings = settings,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )

        assertThat(aged.snapshot.factors["set_age_hours"]).isGreaterThan(baseline.snapshot.factors["set_age_hours"])
        assertThat(aged.snapshot.factors["set_factor"]).isLessThan(baseline.snapshot.factors["set_factor"])
        assertThat(aged.snapshot.isfEff).isLessThan(baseline.snapshot.isfEff)
        assertThat(aged.snapshot.crEff).isGreaterThan(baseline.snapshot.crEff)
        assertThat(aged.snapshot.reasons).contains("set_age_high")
    }

    @Test
    fun sensorDriftScenario_reducesConfidenceAndWidensCi() {
        val now = 1_700_910_000_000L
        val history = buildHistory(nowTs = now, setAgeHours = 12.0, sensorAgeHours = 36.0)
        val settings = scenarioSettings()
        val model = engine.fitBaseModel(
            history = history,
            settings = settings,
            existingState = null,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        ).state

        val baseline = engine.computeRealtime(
            nowTs = now,
            glucose = history.glucose,
            therapy = history.therapy,
            telemetry = listOf(
                TelemetrySignal(now - 60_000L, "sensor_quality_score", 0.95, null)
            ),
            tags = emptyList(),
            activeModel = model,
            previousSnapshot = null,
            settings = settings,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )
        val drift = engine.computeRealtime(
            nowTs = now,
            glucose = history.glucose,
            therapy = history.therapy,
            telemetry = listOf(
                TelemetrySignal(now - 60_000L, "sensor_quality_score", 0.35, null),
                TelemetrySignal(now - 60_000L, "sensor_quality_suspect_false_low", 1.0, null)
            ),
            tags = emptyList(),
            activeModel = model,
            previousSnapshot = null,
            settings = settings,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )

        assertThat(drift.snapshot.confidence).isLessThan(baseline.snapshot.confidence)
        assertThat(drift.snapshot.qualityScore).isLessThan(baseline.snapshot.qualityScore)
        assertThat(drift.snapshot.reasons).contains("sensor_quality_low")
        assertThat(drift.snapshot.reasons).contains("sensor_quality_suspect_false_low")
    }

    @Test
    fun activitySurgeScenario_increasesActivityFactorAndIsfEff() {
        val now = 1_700_920_000_000L
        val history = buildHistory(nowTs = now, setAgeHours = 12.0, sensorAgeHours = 24.0)
        val settings = scenarioSettings()
        val model = engine.fitBaseModel(
            history = history,
            settings = settings,
            existingState = null,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        ).state

        val baseline = engine.computeRealtime(
            nowTs = now,
            glucose = history.glucose,
            therapy = history.therapy,
            telemetry = listOf(
                TelemetrySignal(now - 60_000L, "sensor_quality_score", 0.95, null),
                TelemetrySignal(now - 60_000L, "activity_ratio", 1.0, null),
                TelemetrySignal(now - 60_000L, "steps_rate_15m", 0.0, null)
            ),
            tags = emptyList(),
            activeModel = model,
            previousSnapshot = null,
            settings = settings,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )
        val surge = engine.computeRealtime(
            nowTs = now,
            glucose = history.glucose,
            therapy = history.therapy,
            telemetry = listOf(
                TelemetrySignal(now - 60_000L, "sensor_quality_score", 0.95, null),
                TelemetrySignal(now - 60_000L, "activity_ratio", 1.45, null),
                TelemetrySignal(now - 60_000L, "steps_rate_15m", 180.0, null)
            ),
            tags = emptyList(),
            activeModel = model,
            previousSnapshot = null,
            settings = settings,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )

        assertThat(surge.snapshot.factors["activity_factor"]).isGreaterThan(baseline.snapshot.factors["activity_factor"])
        assertThat(surge.snapshot.isfEff).isGreaterThan(baseline.snapshot.isfEff)
    }

    @Test
    fun dawnScenario_appliesMorningDawnFactor() {
        val zoneId = ZoneId.systemDefault()
        val dawnNow = ZonedDateTime.of(2026, 3, 2, 6, 30, 0, 0, zoneId).toInstant().toEpochMilli()
        val dayNow = ZonedDateTime.of(2026, 3, 2, 14, 30, 0, 0, zoneId).toInstant().toEpochMilli()
        val settings = scenarioSettings()

        val dawnHistory = buildHistory(nowTs = dawnNow, setAgeHours = 10.0, sensorAgeHours = 24.0)
        val dayHistory = buildHistory(nowTs = dayNow, setAgeHours = 10.0, sensorAgeHours = 24.0)
        val dawnModel = engine.fitBaseModel(
            history = dawnHistory,
            settings = settings,
            existingState = null,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        ).state
        val dayModel = engine.fitBaseModel(
            history = dayHistory,
            settings = settings,
            existingState = null,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        ).state

        val dawn = engine.computeRealtime(
            nowTs = dawnNow,
            glucose = dawnHistory.glucose,
            therapy = dawnHistory.therapy,
            telemetry = listOf(TelemetrySignal(dawnNow - 60_000L, "sensor_quality_score", 0.95, null)),
            tags = emptyList(),
            activeModel = dawnModel,
            previousSnapshot = null,
            settings = settings,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )
        val daytime = engine.computeRealtime(
            nowTs = dayNow,
            glucose = dayHistory.glucose,
            therapy = dayHistory.therapy,
            telemetry = listOf(TelemetrySignal(dayNow - 60_000L, "sensor_quality_score", 0.95, null)),
            tags = emptyList(),
            activeModel = dayModel,
            previousSnapshot = null,
            settings = settings,
            fallbackIsf = 2.4,
            fallbackCr = 10.0
        )

        assertThat(dawn.snapshot.factors["dawn_base_factor"]).isLessThan(daytime.snapshot.factors["dawn_base_factor"])
        assertThat(dawn.snapshot.factors["dawn_factor"]).isLessThan(daytime.snapshot.factors["dawn_factor"])
        assertThat(dawn.snapshot.isfEff).isLessThan(daytime.snapshot.isfEff)
    }

    private fun scenarioSettings() = IsfCrSettings(
        lookbackDays = 30,
        confidenceThreshold = 0.2,
        shadowMode = true,
        minIsfEvidencePerHour = 0,
        minCrEvidencePerHour = 0
    )

    private fun buildHistory(
        nowTs: Long,
        setAgeHours: Double,
        sensorAgeHours: Double
    ): IsfCrHistoryBundle {
        val t0 = nowTs - 9 * 60 * 60_000L
        val glucose = mutableListOf<GlucosePoint>()
        var ts = t0
        var value = 8.8
        while (ts <= nowTs) {
            glucose += GlucosePoint(ts = ts, valueMmol = value, source = "cgm", quality = DataQuality.OK)
            value += when {
                ts in (t0 + 90 * 60_000L)..(t0 + 130 * 60_000L) -> -0.09
                ts in (t0 + 240 * 60_000L)..(t0 + 290 * 60_000L) -> 0.11
                else -> -0.008
            }
            ts += 5 * 60_000L
        }

        val therapy = listOf(
            TherapyEvent(
                ts = t0 + 85 * 60_000L,
                type = "correction_bolus",
                payload = mapOf("units" to "1.1")
            ),
            TherapyEvent(
                ts = t0 + 238 * 60_000L,
                type = "bolus",
                payload = mapOf("units" to "4.5")
            ),
            TherapyEvent(
                ts = t0 + 240 * 60_000L,
                type = "meal",
                payload = mapOf("carbs" to "45")
            ),
            TherapyEvent(
                ts = nowTs - (setAgeHours * 3_600_000.0).toLong(),
                type = "infusion_set_change",
                payload = emptyMap()
            ),
            TherapyEvent(
                ts = nowTs - (sensorAgeHours * 3_600_000.0).toLong(),
                type = "sensor_change",
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
