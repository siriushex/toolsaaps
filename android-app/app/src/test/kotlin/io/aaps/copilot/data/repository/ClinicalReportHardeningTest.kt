package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import io.aaps.copilot.data.local.dao.ClinicalForecastProjection
import io.aaps.copilot.data.local.dao.ClinicalTelemetryProjection
import io.aaps.copilot.data.local.dao.ClinicalTherapyProjection
import io.aaps.copilot.data.local.dao.ClinicalGlucoseProjection
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.predict.CanonicalGlucoseConfig
import io.aaps.copilot.domain.predict.Glucose5mCanonicalizer
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class ClinicalReportHardeningTest {

    @Test
    fun targetIntervalsCarryInReplaceCancelClipAndWeightMean() {
        val from = 10 * MINUTE
        val through = 70 * MINUTE
        val events = listOf(
            target("a", 0, """{"target":90,"duration":60}"""),
            target("b", 30, """{"targetBottom":108,"targetTop":126,"duration":30}"""),
            target("c", 50, """{"cancelled":true,"duration":0}""")
        ).mapNotNull(ClinicalReportDatasetBuilder::sanitizeTargetEvent)

        val resolved = ClinicalReportDatasetBuilder.resolveTargets(events, from, through)

        assertThat(resolved.intervals).containsExactly(
            ClinicalTargetInterval(from, 30 * MINUTE, 5.0, 5.0),
            ClinicalTargetInterval(30 * MINUTE, 50 * MINUTE, 6.0, 7.0)
        ).inOrder()
        assertThat(resolved.events.map { it.point.cancelled }).containsExactly(null, null, true)
        assertThat(ClinicalSummaryCalculator.durationWeightedTargetMean(resolved.intervals))
            .isWithin(0.000_001).of(5.75)
    }

    @Test
    fun invalidTargetsAndBoundedPayloadsFailClosed() {
        assertThat(ClinicalReportDatasetBuilder.sanitizeTargetEvent(
            target("low-high", 0, """{"targetBottom":120,"targetTop":90,"duration":30}""")
        )).isNull()
        assertThat(ClinicalReportDatasetBuilder.sanitizeTargetEvent(
            target("reversed", 10, """{"target":100,"endTs":1}""")
        )).isNull()
        assertThat(ClinicalReportDatasetBuilder.sanitizeTargetEvent(
            target("inconsistent", 0, """{"target":100,"duration":30,"endTs":3600000}""")
        )).isNull()
        assertThat(ClinicalReportDatasetBuilder.sanitizeTherapyEvent(
            therapy("large", 0, "carbs", """{"carbs":20,"note":"${"x".repeat(20_000)}"}""")
        )).isNull()
        val partialTherapy = ClinicalReportDatasetBuilder.sanitizeTherapyEvent(
            therapy("partial", 0, "meal_bolus", """{"carbs":20,"units":"bad"}""")
        )
        assertThat(partialTherapy?.point?.insulinU).isNull()
        assertThat(partialTherapy?.point?.carbsG).isEqualTo(20.0)
        assertThat(ClinicalReportDatasetBuilder.sanitizeTargetEvent(
            target("partial-target", 0, """{"targetBottomMmol":100,"targetTopMmol":6}""")
        )).isNull()
        assertThat(ClinicalReportDatasetBuilder.sanitizeTargetEvent(
            target("zero-active", 0, """{"target":100,"duration":0}""")
        )).isNull()
        assertThat(ClinicalReportDatasetBuilder.sanitizeTargetEvent(
            target("zero-valid", 0, """{"target":100,"duration":0,"isValid":true}""")
        )).isNull()
        assertThat(ClinicalReportDatasetBuilder.sanitizeTargetEvent(
            target("zero-cancel", 0, """{"duration":0,"cancelled":true}""")
        )).isNotNull()
    }

    @Test
    fun eventIdsDeduplicateOnlyProvenDuplicates() {
        val rows = listOf(
            therapy("same", 1, "meal_bolus", """{"carbs":20,"units":2}"""),
            therapy("same", 1, "meal_bolus", """{"carbs":20,"units":2}"""),
            therapy("distinct", 1, "meal_bolus", """{"carbs":20,"units":2}""")
        ).mapNotNull(ClinicalReportDatasetBuilder::sanitizeTherapyEvent)

        val points = ClinicalReportDatasetBuilder.canonicalTherapyEvents(rows)

        assertThat(points).hasSize(2)
        assertThat(points.sumOf { it.point.insulinU ?: 0.0 }).isEqualTo(4.0)
        assertThat(points.sumOf { it.point.carbsG ?: 0.0 }).isEqualTo(40.0)
    }

    @Test
    fun forecastsAndTelemetryChooseRankedValidWinnerAndCountRejections() {
        val forecasts = ClinicalReportDatasetBuilder.selectForecasts(
            listOf(
                ClinicalForecastProjection(1, 30, 6.0, 5.0, 7.0, 1, "old"),
                ClinicalForecastProjection(1, 30, 6.5, 5.5, 7.5, 2, "local-hybrid-v3"),
                ClinicalForecastProjection(2, 30, 50.0, 5.0, 7.0, 3, "bad"),
                ClinicalForecastProjection(3, 30, 6.0, 7.0, 5.0, 4, "bad")
            ),
            0,
            10
        )
        val telemetry = ClinicalReportDatasetBuilder.selectTelemetry(
            listOf(
                ClinicalTelemetryProjection(1, "iob_units", 1.0, "STALE", "a", "nightscout", "U"),
                ClinicalTelemetryProjection(1, "iob_units", 2.0, "OK", "b", "nightscout", "U"),
                ClinicalTelemetryProjection(2, "iob_units", 100.0, "OK", "c", "nightscout", "U"),
                ClinicalTelemetryProjection(3, "cob_grams", 10.0, "OK", "d", "nightscout", "U")
            ),
            0,
            10
        )

        assertThat(forecasts.points).containsExactly(ClinicalForecastPoint(1, 30, 6.5, 5.5, 7.5))
        assertThat(forecasts.rejected).isEqualTo(2)
        assertThat(telemetry.points).containsExactly(ClinicalTelemetryPoint(1, "iob_units", 2.0, "OK"))
        assertThat(telemetry.rejected).isEqualTo(2)
    }

    @Test
    fun telemetryWhitelistMatchesPersistedProducerKeysAndUnits() {
        assertThat(ClinicalReportDatasetBuilder.TELEMETRY_KEYS).containsExactly(
            "iob_effective_units",
            "iob_units",
            "cob_effective_grams",
            "cob_grams",
            "isf_runtime_selected_value",
            "cr_runtime_selected_value",
            "isf_runtime_source_resolved",
            "cr_runtime_source_resolved",
            "uam_runtime_control_flag",
            "uam_runtime_equivalent_carbs_grams",
            "uam_runtime_confidence",
            "uam_calculated_carbs_grams",
            "uam_calculated_confidence",
            "sensor_quality_score",
            "sensor_quality_blocked",
            "sensor_age_hours",
            "sensor_lag_minutes",
            "activity_ratio",
            "steps_count",
            "distance_km",
            "active_minutes",
            "calories_active_kcal",
            "basal_rate_u_h",
            "profile_percent"
        ).inOrder()
        val selected = ClinicalReportDatasetBuilder.selectTelemetry(
            listOf(
                telemetry(1, "isf_runtime_selected_value", 2.5, "mmol/L/U"),
                telemetry(2, "cr_runtime_selected_value", 10.0, "g/U"),
                telemetry(3, "uam_calculated_carbs_grams", 18.0, "g"),
                telemetry(4, "uam_calculated_confidence", 0.75, null)
            ),
            0,
            10
        )

        assertThat(selected.rejected).isEqualTo(0)
        assertThat(selected.points.map { it.key }).containsExactly(
            "isf_runtime_selected_value",
            "cr_runtime_selected_value",
            "uam_calculated_carbs_grams",
            "uam_calculated_confidence"
        ).inOrder()
    }

    @Test
    fun telemetryRejectsFractionalSourceCodesAndBinaryFlags() {
        val selected = ClinicalReportDatasetBuilder.selectTelemetry(
            listOf(
                telemetry(1, "isf_runtime_source_resolved", 1.5, null),
                telemetry(2, "cr_runtime_source_resolved", 2.5, null),
                telemetry(3, "uam_runtime_control_flag", 0.5, null),
                telemetry(4, "sensor_quality_blocked", 0.25, null),
                telemetry(5, "isf_runtime_source_resolved", 0.0, null),
                telemetry(6, "cr_runtime_source_resolved", 3.0, null),
                telemetry(7, "uam_runtime_control_flag", 1.0, null),
                telemetry(8, "sensor_quality_blocked", 0.0, null)
            ),
            0,
            10
        )

        assertThat(selected.rejected).isEqualTo(4)
        assertThat(selected.points.map { it.value }).containsExactly(0.0, 3.0, 1.0, 0.0)
            .inOrder()
    }

    @Test
    fun throughBoundaryTargetWithoutSupportedDurationProducesNoResolvedMean() {
        val through = 60 * MINUTE
        val event = checkNotNull(
            ClinicalReportDatasetBuilder.sanitizeTargetEvent(
                target("through", 60, """{"target":100}""")
            )
        )

        val resolved = ClinicalReportDatasetBuilder.resolveTargets(listOf(event), 0, through)

        assertThat(resolved.events).containsExactly(event)
        assertThat(resolved.intervals).isEmpty()
        assertThat(ClinicalSummaryCalculator.durationWeightedTargetMean(resolved.intervals)).isNull()
    }

    @Test
    fun onlyCancellationProducesNoResolvedTargetInterval() {
        val cancellation = checkNotNull(
            ClinicalReportDatasetBuilder.sanitizeTargetEvent(
                target("cancel", 10, """{"duration":0,"isValid":false}""")
            )
        )

        val resolved = ClinicalReportDatasetBuilder.resolveTargets(
            listOf(cancellation),
            0,
            20 * MINUTE
        )

        assertThat(resolved.events).containsExactly(cancellation)
        assertThat(resolved.intervals).isEmpty()
    }

    @Test
    fun serializationRejectsWrongVersionAndOverflowingOffsetsAndIncludesCounts() {
        val dataset = minimalDataset()
        assertThrows(IllegalArgumentException::class.java) {
            ClinicalReportDatasetBuilder.serialize(dataset.copy(schemaVersion = 1))
        }
        assertThrows(ArithmeticException::class.java) {
            ClinicalReportDatasetBuilder.serialize(
                dataset.copy(
                    generatedAt = Long.MAX_VALUE,
                    glucose7d = listOf(ClinicalGlucosePoint(Long.MIN_VALUE, 6.0))
                )
            )
        }

        val json = JsonParser.parseString(ClinicalReportDatasetBuilder.serialize(dataset)).asJsonObject
        val quality = json.getAsJsonObject("s7").getAsJsonObject("quality")
        assertThat(quality.get("rejectedGlucose").asInt).isEqualTo(1)
        assertThat(quality.get("rejectedTherapy").asInt).isEqualTo(2)
        assertThat(quality.get("rejectedTarget").asInt).isEqualTo(3)
        assertThat(quality.get("rejectedForecast").asInt).isEqualTo(4)
        assertThat(quality.get("rejectedTelemetry").asInt).isEqualTo(5)
    }

    @Test
    fun reportGlucoseValuesAreSharedCanonicalizerValues() {
        val from = 1_000_123L
        val rows = listOf(
            ClinicalGlucoseProjection(from, 5.0, "nightscout", "OK"),
            ClinicalGlucoseProjection(from + 10 * MINUTE, 7.0, "nightscout", "OK")
        )
        val buckets = ClinicalReportDatasetBuilder.canonicalizeGlucoseBuckets(
            rows,
            from,
            from + 10 * MINUTE
        )
        val shared = Glucose5mCanonicalizer.build(
            rows.map { GlucosePoint(it.ts, it.mmol, it.source, DataQuality.OK) },
            CanonicalGlucoseConfig(
                fromTs = from,
                throughTs = from + 10 * MINUTE,
                anchorTs = from + 10 * MINUTE,
                maxLookbackMs = 10 * MINUTE
            )
        )

        assertThat(ClinicalReportDatasetBuilder.reportGlucose(buckets))
            .containsExactlyElementsIn(shared.points.map { ClinicalGlucosePoint(it.ts, it.valueMmol) })
            .inOrder()
    }

    @Test
    fun buildUsesOneCapturedSnapshotAndCountsMalformedCategories() = runTest {
        val now = 40 * DAY
        val source = SnapshotOnlySource(
            ClinicalReportSnapshot(
                glucose = listOf(ClinicalGlucoseProjection(now - MINUTE, 0.5, "nightscout", "OK")),
                therapy = listOf(
                    therapy("bad-therapy", 40 * 24 * 60 - 1, "correction_bolus", """{"units":"bad"}"""),
                    target("bad-target", 40 * 24 * 60 - 1, """{"targetBottom":120,"targetTop":90}""")
                ),
                forecasts = listOf(ClinicalForecastProjection(now - MINUTE, 30, 50.0, 5.0, 7.0)),
                telemetry = listOf(
                    ClinicalTelemetryProjection(
                        now - MINUTE, "iob_units", 1.0, "OK",
                        rowId = "bad-unit", source = "nightscout", unit = "g"
                    )
                )
            )
        )

        val payload = ClinicalReportDatasetBuilder(source).build(now, ZoneId.of("UTC"))

        assertThat(source.snapshotCalls).isEqualTo(1)
        assertThat(payload.dataset.summary7d.quality.rejected)
            .isEqualTo(ClinicalRejectionCounts(1, 1, 1, 1, 1))
        assertThat(source.request?.therapyFromTs).isEqualTo(3 * DAY)
    }

    private fun minimalDataset(): ClinicalReportDataset {
        val quality = ClinicalDataQuality(0, 0, 0, null, ClinicalRejectionCounts(1, 2, 3, 4, 5))
        val hourly = (0..23).map { ClinicalHourlyMetric(it, 0, null, null) }
        val summary = ClinicalPeriodSummary(
            7, 0, 7 * DAY, 0.0, null, null, null, null, null, null,
            0.0, 0.0, null, hourly, hourly, quality
        )
        return ClinicalReportDataset(
            ClinicalReportDatasetBuilder.SCHEMA_VERSION,
            7 * DAY,
            "UTC",
            ClinicalDetailWindow(
                6 * DAY,
                7 * DAY,
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList()
            ),
            emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(),
            summary, summary.copy(days = 30, fromTs = -23 * DAY)
        )
    }

    private fun therapy(id: String, minute: Long, type: String, payload: String) =
        ClinicalTherapyProjection(
            ts = minute * MINUTE,
            type = type,
            payloadJson = payload,
            isBroadcastArtifact = false,
            rowId = id
        )

    private fun target(id: String, minute: Long, payload: String) =
        therapy(id, minute, "temp_target", payload)

    private fun telemetry(ts: Long, key: String, value: Double, unit: String?) =
        ClinicalTelemetryProjection(
            ts = ts,
            key = key,
            value = value,
            quality = "OK",
            rowId = "row-$key",
            source = "copilot_runtime",
            unit = unit
        )

    private class SnapshotOnlySource(
        private val snapshot: ClinicalReportSnapshot
    ) : ClinicalReportDataSource {
        var snapshotCalls = 0
        var request: ClinicalReportReadRequest? = null

        override suspend fun readSnapshot(request: ClinicalReportReadRequest): ClinicalReportSnapshot {
            snapshotCalls += 1
            this.request = request
            return snapshot
        }

        override suspend fun glucose(fromTs: Long, toTs: Long) =
            error("Builder must use captured snapshot")

        override suspend fun therapy(fromTs: Long, toTs: Long, types: List<String>) =
            error("Builder must use captured snapshot")

        override suspend fun forecasts(fromTs: Long, toTs: Long) =
            error("Builder must use captured snapshot")

        override suspend fun telemetry(fromTs: Long, toTs: Long, keys: List<String>) =
            error("Builder must use captured snapshot")
    }

    private companion object {
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
