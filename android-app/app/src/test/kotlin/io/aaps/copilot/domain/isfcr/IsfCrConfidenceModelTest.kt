package io.aaps.copilot.domain.isfcr

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.predict.TelemetrySignal
import org.junit.Test

class IsfCrConfidenceModelTest {

    private val model = IsfCrConfidenceModel()

    @Test
    fun evaluate_highWearAndAmbiguity_reduceConfidenceAndWidenCi() {
        val evidence = sampleEvidence(count = 24, quality = 0.9)
        val telemetryBase = listOf(
            TelemetrySignal(ts = 1_700_000_000_000L, key = "sensor_quality_score", valueDouble = 0.95, valueText = null),
            TelemetrySignal(ts = 1_700_000_000_000L, key = "sensor_quality_noise_std5", valueDouble = 0.08, valueText = null),
            TelemetrySignal(ts = 1_700_000_000_000L, key = "uam_value", valueDouble = 0.0, valueText = null)
        )
        val telemetryPenalized = telemetryBase + listOf(
            TelemetrySignal(ts = 1_700_000_060_000L, key = "sensor_quality_suspect_false_low", valueDouble = 1.0, valueText = null)
        )

        val base = model.evaluate(
            isfEff = 2.6,
            crEff = 10.5,
            evidence = evidence,
            telemetry = telemetryBase,
            factors = mapOf(
                "set_age_hours" to 18.0,
                "sensor_age_hours" to 24.0,
                "manual_stress_tag" to 0.0,
                "manual_illness_tag" to 0.0,
                "manual_hormone_tag" to 0.0,
                "latent_stress" to 0.0
            )
        )
        val penalized = model.evaluate(
            isfEff = 2.6,
            crEff = 10.5,
            evidence = evidence,
            telemetry = telemetryPenalized,
            factors = mapOf(
                "set_age_hours" to 220.0,
                "sensor_age_hours" to 280.0,
                "manual_stress_tag" to 0.8,
                "manual_illness_tag" to 0.7,
                "manual_hormone_tag" to 0.9,
                "latent_stress" to 0.85
            )
        )

        val baseIsfWidth = base.ciIsfHigh - base.ciIsfLow
        val penalizedIsfWidth = penalized.ciIsfHigh - penalized.ciIsfLow
        val baseCrWidth = base.ciCrHigh - base.ciCrLow
        val penalizedCrWidth = penalized.ciCrHigh - penalized.ciCrLow

        assertThat(penalized.confidence).isLessThan(base.confidence)
        assertThat(penalized.qualityScore).isLessThan(base.qualityScore)
        assertThat(penalizedIsfWidth).isGreaterThan(baseIsfWidth)
        assertThat(penalizedCrWidth).isGreaterThan(baseCrWidth)
    }

    @Test
    fun evaluate_outputsStayInBounds() {
        val evidence = sampleEvidence(count = 8, quality = 0.35)
        val telemetry = listOf(
            TelemetrySignal(ts = 1_700_000_000_000L, key = "sensor_quality_score", valueDouble = 0.2, valueText = null),
            TelemetrySignal(ts = 1_700_000_000_000L, key = "sensor_quality_noise_std5", valueDouble = 1.9, valueText = null),
            TelemetrySignal(ts = 1_700_000_000_000L, key = "uam_value", valueDouble = 1.0, valueText = null)
        )
        val output = model.evaluate(
            isfEff = 1.9,
            crEff = 8.7,
            evidence = evidence,
            telemetry = telemetry,
            factors = mapOf(
                "set_age_hours" to 300.0,
                "sensor_age_hours" to 360.0,
                "latent_stress" to 1.0
            )
        )

        assertThat(output.confidence).isAtLeast(0.0)
        assertThat(output.confidence).isAtMost(0.99)
        assertThat(output.qualityScore).isAtLeast(0.0)
        assertThat(output.qualityScore).isAtMost(1.0)
        assertThat(output.ciIsfLow).isAtLeast(0.8)
        assertThat(output.ciIsfHigh).isAtMost(18.0)
        assertThat(output.ciCrLow).isAtLeast(2.0)
        assertThat(output.ciCrHigh).isAtMost(60.0)
    }

    private fun sampleEvidence(count: Int, quality: Double): List<IsfCrEvidenceSample> {
        val now = 1_700_000_000_000L
        return List(count) { idx ->
            val isIsf = idx % 2 == 0
            IsfCrEvidenceSample(
                id = "e$idx",
                ts = now - idx * 15 * 60_000L,
                sampleType = if (isIsf) IsfCrSampleType.ISF else IsfCrSampleType.CR,
                hourLocal = idx % 24,
                dayType = DayType.WEEKDAY,
                value = if (isIsf) 2.4 else 10.0,
                weight = 0.8,
                qualityScore = quality,
                context = emptyMap(),
                window = emptyMap()
            )
        }
    }
}

