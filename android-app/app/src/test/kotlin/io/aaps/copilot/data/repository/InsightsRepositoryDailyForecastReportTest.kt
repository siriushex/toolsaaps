package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import java.util.Locale
import org.junit.Test

class InsightsRepositoryDailyForecastReportTest {

    @Test
    fun buildDailyForecastReportPayload_computesMetricsByHorizon() {
        val now = 1_800_000_000_000L
        val since = now - 24L * 60L * 60L * 1000L
        val glucose = buildGlucose(since, now)
        val forecasts = buildForecasts(glucose)

        val payload = InsightsRepository.buildDailyForecastReportPayloadStatic(
            forecasts = forecasts,
            glucose = glucose,
            sinceTs = since,
            untilTs = now
        )

        assertThat(payload.forecastRows).isEqualTo(forecasts.size)
        assertThat(payload.matchedSamples).isGreaterThan(100)
        assertThat(payload.horizonStats.map { it.horizonMinutes }).containsAtLeast(5, 30, 60)
        assertThat(payload.horizonStats.first { it.horizonMinutes == 60 }.mardPct)
            .isGreaterThan(payload.horizonStats.first { it.horizonMinutes == 5 }.mardPct)
        assertThat(payload.horizonStats.first { it.horizonMinutes == 5 }.ciCoveragePct).isGreaterThan(80.0)
        assertThat(payload.horizonStats.first { it.horizonMinutes == 30 }.ciMeanWidth).isGreaterThan(0.0)
        assertThat(payload.worstSamples).isNotEmpty()
    }

    @Test
    fun buildDailyForecastReportPayload_emitsRecommendationsForHighMardAndBias() {
        val now = 1_800_000_000_000L
        val since = now - 24L * 60L * 60L * 1000L
        val glucose = buildGlucose(since, now)
        val forecasts = glucose.flatMap { sample ->
            listOf(
                ForecastEntity(
                    timestamp = sample.timestamp,
                    horizonMinutes = 5,
                    valueMmol = sample.mmol + 0.1,
                    ciLow = sample.mmol - 0.4,
                    ciHigh = sample.mmol + 0.6,
                    modelVersion = "local-hybrid-v3"
                ),
                ForecastEntity(
                    timestamp = sample.timestamp,
                    horizonMinutes = 60,
                    valueMmol = sample.mmol + 2.0,
                    ciLow = sample.mmol + 1.2,
                    ciHigh = sample.mmol + 2.8,
                    modelVersion = "local-hybrid-v3"
                )
            )
        }

        val payload = InsightsRepository.buildDailyForecastReportPayloadStatic(
            forecasts = forecasts,
            glucose = glucose,
            sinceTs = since,
            untilTs = now
        )

        assertThat(payload.horizonStats.first { it.horizonMinutes == 60 }.mardPct).isGreaterThan(15.0)
        assertThat(payload.horizonStats.first { it.horizonMinutes == 60 }.ciCoveragePct).isLessThan(30.0)
        assertThat(payload.recommendations).isNotEmpty()
        assertThat(payload.recommendations.joinToString(" "))
            .contains("60m MARD is high")
    }

    @Test
    fun buildRollingForecastPayloadsStatic_returnsExpectedWindowsAndMonotonicCoverage() {
        val now = 1_800_000_000_000L
        val since = now - 120L * 24L * 60L * 60L * 1000L
        val glucose = buildGlucose(since, now)
        val forecasts = buildForecasts(glucose)

        val rolling = InsightsRepository.buildRollingForecastPayloadsStatic(
            forecasts = forecasts,
            glucose = glucose,
            untilTs = now
        )

        assertThat(rolling.keys).containsAtLeast(14, 30, 90)
        val p14 = requireNotNull(rolling[14])
        val p30 = requireNotNull(rolling[30])
        val p90 = requireNotNull(rolling[90])

        assertThat(p14.sinceTs).isEqualTo(now - 14L * 24L * 60L * 60L * 1000L)
        assertThat(p30.sinceTs).isEqualTo(now - 30L * 24L * 60L * 60L * 1000L)
        assertThat(p90.sinceTs).isEqualTo(now - 90L * 24L * 60L * 60L * 1000L)
        assertThat(p14.matchedSamples).isGreaterThan(0)
        assertThat(p30.matchedSamples).isAtLeast(p14.matchedSamples)
        assertThat(p90.matchedSamples).isAtLeast(p30.matchedSamples)
        assertThat(p90.horizonStats.map { it.horizonMinutes }).containsAtLeast(5, 30, 60)
    }

    @Test
    fun buildIsfCrDataQualityRecommendations_emitsCrIntegrityGuidance() {
        val recommendations = InsightsRepository.buildIsfCrDataQualityRecommendations(
            droppedTotal = 120,
            eventCount = 30,
            reasonCounts = mapOf(
                "cr_sensor_blocked" to 36,
                "cr_gross_gap" to 28,
                "cr_uam_ambiguity" to 30
            )
        )

        assertThat(recommendations).isNotEmpty()
        assertThat(recommendations.joinToString(" "))
            .contains("sensor quality")
        assertThat(recommendations.joinToString(" "))
            .contains("gross CGM gaps")
        assertThat(recommendations.joinToString(" "))
            .contains("UAM ambiguity")
    }

    @Test
    fun buildIsfCrDataQualityRecommendations_returnsEmptyWhenNoDroppedWindows() {
        val recommendations = InsightsRepository.buildIsfCrDataQualityRecommendations(
            droppedTotal = 0,
            eventCount = 50,
            reasonCounts = mapOf("cr_sensor_blocked" to 10)
        )

        assertThat(recommendations).isEmpty()
    }

    @Test
    fun buildIsfCrDroppedQualityLines_includesTopReasons() {
        val lines = InsightsRepository.buildIsfCrDroppedQualityLines(
            sourceMessage = "isfcr_evidence_extracted",
            eventCount = 40,
            droppedTotal = 120,
            reasonCounts = mapOf(
                "cr_sensor_blocked" to 30,
                "cr_gross_gap" to 25,
                "cr_uam_ambiguity" to 20,
                "isf_small_units" to 10
            )
        )

        assertThat(lines).hasSize(4)
        assertThat(lines[0]).contains("source=isfcr_evidence_extracted")
        assertThat(lines[1]).contains("Quality risk:")
        assertThat(lines[2]).contains("sensorBlocked")
        assertThat(lines[3]).contains("Top dropped reasons")
        assertThat(lines[3]).contains("cr_sensor_blocked=30")
    }

    @Test
    fun buildIsfCrDataQualityRiskLabel_returnsUnknownWhenNoEvidence() {
        val risk = InsightsRepository.buildIsfCrDataQualityRiskLabel(
            eventCount = 0,
            droppedTotal = 0,
            reasonCounts = emptyMap()
        )

        assertThat(risk).isEqualTo("UNKNOWN")
    }

    @Test
    fun buildIsfCrDataQualityRiskLabel_returnsHighForDominantSensorBlocked() {
        val risk = InsightsRepository.buildIsfCrDataQualityRiskLabel(
            eventCount = 20,
            droppedTotal = 80,
            reasonCounts = mapOf(
                "cr_sensor_blocked" to 32,
                "cr_gross_gap" to 12
            )
        )

        assertThat(risk).startsWith("HIGH (sensorBlocked")
    }

    @Test
    fun buildIsfCrDataQualityRiskLabel_returnsMediumForModerateGapRate() {
        val risk = InsightsRepository.buildIsfCrDataQualityRiskLabel(
            eventCount = 30,
            droppedTotal = 45,
            reasonCounts = mapOf(
                "cr_gross_gap" to 10,
                "cr_sensor_blocked" to 4
            )
        )

        assertThat(risk).startsWith("MEDIUM (gap")
    }

    @Test
    fun buildIsfCrDataQualityRiskLabel_returnsLowForMinorRates() {
        val risk = InsightsRepository.buildIsfCrDataQualityRiskLabel(
            eventCount = 40,
            droppedTotal = 20,
            reasonCounts = mapOf(
                "cr_uam_ambiguity" to 2,
                "cr_gross_gap" to 1
            )
        )

        assertThat(risk).startsWith("LOW (uamAmbiguity")
    }

    @Test
    fun buildIsfCrDataQualityRiskLevel_returnsExpectedScale() {
        val unknown = InsightsRepository.buildIsfCrDataQualityRiskLevel(
            eventCount = 0,
            droppedTotal = 0,
            reasonCounts = emptyMap()
        )
        val low = InsightsRepository.buildIsfCrDataQualityRiskLevel(
            eventCount = 40,
            droppedTotal = 20,
            reasonCounts = mapOf("cr_gross_gap" to 2)
        )
        val medium = InsightsRepository.buildIsfCrDataQualityRiskLevel(
            eventCount = 20,
            droppedTotal = 60,
            reasonCounts = mapOf("cr_gross_gap" to 14)
        )
        val high = InsightsRepository.buildIsfCrDataQualityRiskLevel(
            eventCount = 20,
            droppedTotal = 90,
            reasonCounts = mapOf("cr_sensor_blocked" to 40)
        )

        assertThat(unknown).isEqualTo(0)
        assertThat(low).isEqualTo(1)
        assertThat(medium).isEqualTo(2)
        assertThat(high).isEqualTo(3)
    }

    @Test
    fun buildDailyForecastReportPayload_buildsReplayHotspotsAndFactorContributions() {
        val now = 1_800_000_000_000L
        val since = now - 7L * 24L * 60L * 60L * 1000L
        val glucose = buildGlucose(since, now)
        val telemetry = buildReplayTelemetry(since, now)
        val forecasts = buildReplayForecasts(glucose)

        val payload = InsightsRepository.buildDailyForecastReportPayloadStatic(
            forecasts = forecasts,
            glucose = glucose,
            telemetrySamples = telemetry,
            sinceTs = since,
            untilTs = now
        )

        assertThat(payload.replayHotspots).isNotEmpty()
        assertThat(payload.replayHotspots.map { it.horizonMinutes }).contains(60)
        assertThat(payload.factorContributions).isNotEmpty()
        assertThat(payload.replayFactorCoverage).isNotEmpty()
        assertThat(payload.replayFactorRegimes).isNotEmpty()
        assertThat(payload.replayFactorPairs).isNotEmpty()
        assertThat(payload.replayErrorClusters).isNotEmpty()
        assertThat(payload.replayDayTypeGaps).isNotEmpty()
        assertThat(payload.replayFactorCoverage.map { it.factor }).contains("COB")
        assertThat(payload.replayFactorCoverage.map { it.factor }).contains("DIA_H")
        assertThat(payload.replayFactorCoverage.map { it.factor }).contains("SENSOR_AGE_H")
        assertThat(payload.replayFactorCoverage.map { it.factor }).contains("STEROID")
        assertThat(payload.factorContributions.map { it.factor }).contains("COB")
        assertThat(payload.replayFactorRegimes.map { it.factor }).containsAtLeast("COB", "IOB", "UAM", "CI")
        assertThat(payload.replayFactorRegimes.map { it.bucket }).containsAnyOf("LOW", "MID", "HIGH")
        assertThat(payload.replayFactorPairs.map { it.factorA to it.factorB })
            .containsAnyOf("COB" to "IOB", "COB" to "UAM", "UAM" to "CI")

        val top60 = payload.factorContributions
            .filter { it.horizonMinutes == 60 }
            .maxByOrNull { it.contributionScore }
        assertThat(top60).isNotNull()
        val topFactor60 = top60!!.factor
        assertThat(topFactor60).isIn(listOf("COB", "IOB", "UAM", "CI"))
        assertThat(top60.upliftPct).isGreaterThan(0.0)
        assertThat(payload.recommendations.any { it.contains("60m top factor=$topFactor60") }).isTrue()
        assertThat(
            payload.recommendations.any {
                it.contains(topFactor60) && (it.contains("error") || it.contains("protective"))
            }
        ).isTrue()
        assertThat(
            payload.recommendations.any {
                it.contains("top pair=")
            }
        ).isTrue()
        assertThat(payload.replayTopMisses.map { it.horizonMinutes }).containsAtLeast(5, 30, 60)
        assertThat(payload.replayTopMisses.all { it.absError > 0.0 }).isTrue()
        assertThat(payload.replayErrorClusters.map { it.horizonMinutes }).containsAtLeast(5, 30, 60)
        val topCluster60 = payload.replayErrorClusters
            .filter { it.horizonMinutes == 60 }
            .maxByOrNull { it.mae }
        assertThat(topCluster60).isNotNull()
        assertThat(topCluster60!!.dominantFactor()?.first).isEqualTo("COB")
        assertThat(topCluster60.dayType).isIn(listOf("WEEKDAY", "WEEKEND"))
        val topGap60 = payload.replayDayTypeGaps
            .filter { it.horizonMinutes == 60 }
            .maxByOrNull { kotlin.math.abs(it.maeGapMmol) }
        assertThat(topGap60).isNotNull()
        assertThat(topGap60!!.worseDayType).isIn(listOf("WEEKDAY", "WEEKEND"))
        assertThat(
            payload.recommendations.any {
                it.contains("error cluster") || it.contains("weekday/weekend gap")
            }
        ).isTrue()
    }

    @Test
    fun buildDailyForecastReportPayload_buildsSensorLagReplayAndShadowBuckets() {
        val now = 1_800_000_000_000L
        val since = now - 16L * 24L * 60L * 60L * 1000L
        val glucose = buildGlucose(since, now)
        val forecasts = buildReplayForecasts(glucose)
        val telemetry = buildSensorLagReplayTelemetry(
            since = since,
            until = now,
            glucose = glucose,
            modeResolver = { ts ->
                val ageHours = ((ts - since).toDouble() / (60.0 * 60.0 * 1000.0)).coerceAtLeast(0.0)
                if (ageHours >= 240.0) "SHADOW" else "ACTIVE"
            }
        )

        val payload = InsightsRepository.buildDailyForecastReportPayloadStatic(
            forecasts = forecasts,
            glucose = glucose,
            telemetrySamples = telemetry,
            sinceTs = since,
            untilTs = now
        )

        assertThat(payload.sensorLagReplayBuckets).isNotEmpty()
        assertThat(payload.sensorLagReplayBuckets.map { it.bucket })
            .containsAtLeast("1-10d", "10-12d", "12-14d", ">14d")
        val lateLife60 = payload.sensorLagReplayBuckets
            .firstOrNull { it.horizonMinutes == 60 && it.bucket == "12-14d" }
        assertThat(lateLife60).isNotNull()
        assertThat(lateLife60!!.maeImprovementMmol).isGreaterThan(0.0)
        assertThat(payload.sensorLagShadowBuckets).isNotEmpty()
        assertThat(payload.sensorLagShadowBuckets.map { it.bucket }).contains("12-14d")
        val lateLifeShadow = payload.sensorLagShadowBuckets.first { it.bucket == "12-14d" }
        assertThat(lateLifeShadow.ruleChangedRatePct).isGreaterThan(0.0)
        assertThat(
            payload.recommendations.any {
                it.contains("Sensor-lag replay") || it.contains("Sensor-lag SHADOW")
            }
        ).isTrue()
    }

    @Test
    fun buildDailyForecastReportPayload_excludesSensorLagReplayWhenModeOff() {
        val now = 1_800_000_000_000L
        val since = now - 3L * 24L * 60L * 60L * 1000L
        val glucose = buildGlucose(since, now)
        val forecasts = buildReplayForecasts(glucose)
        val telemetry = buildSensorLagReplayTelemetry(
            since = since,
            until = now,
            glucose = glucose,
            modeResolver = { "OFF" }
        )

        val payload = InsightsRepository.buildDailyForecastReportPayloadStatic(
            forecasts = forecasts,
            glucose = glucose,
            telemetrySamples = telemetry,
            sinceTs = since,
            untilTs = now
        )

        assertThat(payload.sensorLagReplayBuckets).isEmpty()
        assertThat(payload.sensorLagShadowBuckets).isEmpty()
    }

    @Test
    fun buildDailyForecastReportPayload_prefersCandidateForecastsOverControlTelemetry() {
        val now = 1_800_000_000_000L
        val since = now - 16L * 24L * 60L * 60L * 1000L
        val glucose = buildGlucose(since, now)
        val forecasts = buildReplayForecasts(glucose)
        val telemetry = buildSensorLagReplayTelemetry(
            since = since,
            until = now,
            glucose = glucose,
            modeResolver = { "SHADOW" },
            includeCandidateForecasts = true,
            includeControlForecasts = true,
            controlErrorMultiplier = 2.4,
            candidateErrorMultiplier = 0.55
        )

        val payload = InsightsRepository.buildDailyForecastReportPayloadStatic(
            forecasts = forecasts,
            glucose = glucose,
            telemetrySamples = telemetry,
            sinceTs = since,
            untilTs = now
        )

        val replay60 = payload.sensorLagReplayBuckets.firstOrNull { it.horizonMinutes == 60 && it.bucket == "12-14d" }
        assertThat(replay60).isNotNull()
        assertThat(replay60!!.maeImprovementMmol).isGreaterThan(0.0)
        assertThat(replay60.lagMae).isLessThan(replay60.rawMae)
    }

    @Test
    fun resolveSensorLagReplayForecastRows_prefersCandidateRowsForHorizon() {
        val ts = 1_800_000_000_000L
        val telemetryByKey = listOf(
            TelemetrySampleEntity(
                id = "candidate-30",
                timestamp = ts,
                source = "sensor-lag-test",
                key = "sensor_lag_candidate_forecast_30m",
                valueDouble = 6.2,
                valueText = null,
                unit = "mmol/L",
                quality = "OK"
            ),
            TelemetrySampleEntity(
                id = "control-30",
                timestamp = ts,
                source = "sensor-lag-test",
                key = "sensor_lag_control_forecast_30m",
                valueDouble = 6.8,
                valueText = null,
                unit = "mmol/L",
                quality = "OK"
            )
        ).groupBy { it.key.lowercase(Locale.US) }

        val resolved = InsightsRepository.resolveSensorLagReplayForecastRowsStatic(
            telemetryByKey = telemetryByKey,
            horizonMinutes = 30
        )

        assertThat(resolved).isNotNull()
        assertThat(resolved!!.modelFamily).isEqualTo("sensor_lag_candidate")
        assertThat(resolved.rows).hasSize(1)
        assertThat(resolved.rows.single().valueDouble).isWithin(0.001).of(6.2)
    }

    @Test
    fun resolveSensorLagReplayForecastRows_fallsBackToControlRowsWhenCandidateMissing() {
        val ts = 1_800_000_000_000L
        val telemetryByKey = listOf(
            TelemetrySampleEntity(
                id = "candidate-30",
                timestamp = ts,
                source = "sensor-lag-test",
                key = "sensor_lag_candidate_forecast_30m",
                valueDouble = 6.2,
                valueText = null,
                unit = "mmol/L",
                quality = "OK"
            ),
            TelemetrySampleEntity(
                id = "control-60",
                timestamp = ts,
                source = "sensor-lag-test",
                key = "sensor_lag_control_forecast_60m",
                valueDouble = 7.1,
                valueText = null,
                unit = "mmol/L",
                quality = "OK"
            )
        ).groupBy { it.key.lowercase(Locale.US) }

        val resolved = InsightsRepository.resolveSensorLagReplayForecastRowsStatic(
            telemetryByKey = telemetryByKey,
            horizonMinutes = 60
        )

        assertThat(resolved).isNotNull()
        assertThat(resolved!!.modelFamily).isEqualTo("sensor_lag_control")
        assertThat(resolved.rows).hasSize(1)
        assertThat(resolved.rows.single().valueDouble).isWithin(0.001).of(7.1)
    }

    private fun buildGlucose(since: Long, until: Long): List<GlucoseSampleEntity> {
        val points = mutableListOf<GlucoseSampleEntity>()
        var ts = since
        var value = 6.0
        while (ts <= until) {
            points += GlucoseSampleEntity(
                timestamp = ts,
                mmol = value,
                source = "test",
                quality = "OK"
            )
            value += if ((ts / (60 * 60_000L)) % 2L == 0L) 0.01 else -0.005
            ts += 5 * 60_000L
        }
        return points
    }

    private fun buildForecasts(glucose: List<GlucoseSampleEntity>): List<ForecastEntity> {
        return glucose.flatMap { sample ->
            listOf(
                ForecastEntity(
                    timestamp = sample.timestamp,
                    horizonMinutes = 5,
                    valueMmol = sample.mmol + 0.15,
                    ciLow = sample.mmol - 0.3,
                    ciHigh = sample.mmol + 0.5,
                    modelVersion = "local-hybrid-v3|calib_v1"
                ),
                ForecastEntity(
                    timestamp = sample.timestamp,
                    horizonMinutes = 30,
                    valueMmol = sample.mmol + 0.35,
                    ciLow = sample.mmol - 0.1,
                    ciHigh = sample.mmol + 0.9,
                    modelVersion = "local-hybrid-v3|calib_v1"
                ),
                ForecastEntity(
                    timestamp = sample.timestamp,
                    horizonMinutes = 60,
                    valueMmol = sample.mmol + 0.75,
                    ciLow = sample.mmol + 0.2,
                    ciHigh = sample.mmol + 1.3,
                    modelVersion = "local-hybrid-v3|calib_v1"
                )
            )
        }
    }

    private fun buildReplayForecasts(glucose: List<GlucoseSampleEntity>): List<ForecastEntity> {
        return glucose.flatMap { sample ->
            listOf(5, 30, 60).map { horizon ->
                val generationTs = sample.timestamp - horizon * 60_000L
                val generationHour = ((generationTs / 3_600_000L) % 24L).toInt()
                val highCobWindow = generationHour in 14..22
                val error = when (horizon) {
                    5 -> if (highCobWindow) 0.20 else 0.05
                    30 -> if (highCobWindow) 0.60 else 0.20
                    else -> if (highCobWindow) 1.40 else 0.35
                }
                ForecastEntity(
                    timestamp = sample.timestamp,
                    horizonMinutes = horizon,
                    valueMmol = sample.mmol + error,
                    ciLow = sample.mmol - 0.4,
                    ciHigh = sample.mmol + 0.8,
                    modelVersion = "local-hybrid-v3|replay_test"
                )
            }
        }
    }

    private fun buildReplayTelemetry(since: Long, until: Long): List<TelemetrySampleEntity> {
        val rows = mutableListOf<TelemetrySampleEntity>()
        var ts = since
        while (ts <= until) {
            val hour = ((ts / 3_600_000L) % 24L).toInt()
            val highCobWindow = hour in 14..22
            val cob = if (highCobWindow) 32.0 else 6.0
            val iob = if (highCobWindow) 2.1 else 0.7
            val uam = if (hour in 19..20) 0.3 else 0.0
            val dia = if (hour in 0..5) 5.5 else 4.0
            val sensorAgeHours = ((ts - since).toDouble() / (60.0 * 60.0 * 1000.0)).coerceAtLeast(0.0)
            val steroidFactor = if (hour in 8..14) 0.88 else 1.0
            rows += TelemetrySampleEntity(
                id = "tm-cob-$ts",
                timestamp = ts,
                source = "test",
                key = "cob_grams",
                valueDouble = cob,
                valueText = null,
                unit = "g",
                quality = "OK"
            )
            rows += TelemetrySampleEntity(
                id = "tm-iob-$ts",
                timestamp = ts,
                source = "test",
                key = "iob_units",
                valueDouble = iob,
                valueText = null,
                unit = "U",
                quality = "OK"
            )
            rows += TelemetrySampleEntity(
                id = "tm-uam-$ts",
                timestamp = ts,
                source = "test",
                key = "uam_uci0_mmol5",
                valueDouble = uam,
                valueText = null,
                unit = "mmol/5m",
                quality = "OK"
            )
            rows += TelemetrySampleEntity(
                id = "tm-dia-$ts",
                timestamp = ts,
                source = "test",
                key = "dia_hours",
                valueDouble = dia,
                valueText = null,
                unit = "h",
                quality = "OK"
            )
            rows += TelemetrySampleEntity(
                id = "tm-sensor-age-$ts",
                timestamp = ts,
                source = "test",
                key = "isf_factor_sensor_age_hours",
                valueDouble = sensorAgeHours,
                valueText = null,
                unit = "h",
                quality = "OK"
            )
            rows += TelemetrySampleEntity(
                id = "tm-steroid-$ts",
                timestamp = ts,
                source = "test",
                key = "isf_factor_steroid_factor",
                valueDouble = steroidFactor,
                valueText = null,
                unit = null,
                quality = "OK"
            )
            ts += 5 * 60_000L
        }
        return rows
    }

    private fun buildSensorLagReplayTelemetry(
        since: Long,
        until: Long,
        glucose: List<GlucoseSampleEntity>,
        modeResolver: (Long) -> String = { "ACTIVE" },
        includeCandidateForecasts: Boolean = false,
        includeControlForecasts: Boolean = true,
        controlErrorMultiplier: Double = 1.0,
        candidateErrorMultiplier: Double = 1.0
    ): List<TelemetrySampleEntity> {
        val rows = mutableListOf<TelemetrySampleEntity>()
        val glucoseByTs = glucose.associateBy { it.timestamp }
        var ts = since
        while (ts <= until) {
            val ageHours = ((ts - since).toDouble() / (60.0 * 60.0 * 1000.0)).coerceAtLeast(0.0)
            val lateLife = ageHours >= 240.0
            val mode = modeResolver(ts)
            rows += TelemetrySampleEntity(
                id = "tm-sensor-lag-age-$ts",
                timestamp = ts,
                source = "sensor-lag-test",
                key = "sensor_lag_age_hours",
                valueDouble = ageHours,
                valueText = null,
                unit = "h",
                quality = "OK"
            )
            rows += TelemetrySampleEntity(
                id = "tm-sensor-lag-mode-$ts",
                timestamp = ts,
                source = "sensor-lag-test",
                key = "sensor_lag_mode",
                valueDouble = null,
                valueText = mode,
                unit = null,
                quality = "OK"
            )
            if (mode.equals("SHADOW", ignoreCase = true)) {
                rows += TelemetrySampleEntity(
                    id = "tm-sensor-lag-shadow-$ts",
                    timestamp = ts,
                    source = "sensor-lag-test",
                    key = "sensor_lag_shadow_rule_changed",
                    valueDouble = if (lateLife) 1.0 else 0.0,
                    valueText = null,
                    unit = null,
                    quality = "OK"
                )
                rows += TelemetrySampleEntity(
                    id = "tm-sensor-lag-delta-$ts",
                    timestamp = ts,
                    source = "sensor-lag-test",
                    key = "sensor_lag_shadow_target_delta_mmol",
                    valueDouble = if (lateLife) 0.22 else 0.04,
                    valueText = null,
                    unit = "mmol/L",
                    quality = "OK"
                )
            }
            listOf(5, 30, 60).forEach { horizon ->
                val target = glucoseByTs[ts + horizon * 60_000L] ?: return@forEach
                val generationHour = ((ts / 3_600_000L) % 24L).toInt()
                val highCobWindow = generationHour in 14..22
                val rawError = when (horizon) {
                    5 -> if (highCobWindow) 0.20 else 0.05
                    30 -> if (highCobWindow) 0.60 else 0.20
                    else -> if (highCobWindow) 1.40 else 0.35
                }
                val adjustedError = if (lateLife) rawError * 0.55 else rawError * 0.92
                if (includeControlForecasts) {
                    rows += TelemetrySampleEntity(
                        id = "tm-sensor-lag-control-$horizon-$ts",
                        timestamp = ts,
                        source = "sensor-lag-test",
                        key = "sensor_lag_control_forecast_${horizon}m",
                        valueDouble = target.mmol + adjustedError * controlErrorMultiplier,
                        valueText = null,
                        unit = "mmol/L",
                        quality = "OK"
                    )
                }
                if (includeCandidateForecasts) {
                    rows += TelemetrySampleEntity(
                        id = "tm-sensor-lag-candidate-$horizon-$ts",
                        timestamp = ts,
                        source = "sensor-lag-test",
                        key = "sensor_lag_candidate_forecast_${horizon}m",
                        valueDouble = target.mmol + adjustedError * candidateErrorMultiplier,
                        valueText = null,
                        unit = "mmol/L",
                        quality = "OK"
                    )
                }
            }
            ts += 5 * 60_000L
        }
        return rows
    }
}
