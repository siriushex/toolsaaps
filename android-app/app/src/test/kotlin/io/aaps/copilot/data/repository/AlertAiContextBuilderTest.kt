package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.aaps.copilot.data.local.dao.ClinicalForecastProjection
import io.aaps.copilot.data.local.dao.ClinicalGlucoseProjection
import io.aaps.copilot.data.local.dao.ClinicalTelemetryProjection
import io.aaps.copilot.data.local.dao.ClinicalTherapyProjection
import io.aaps.copilot.domain.alerts.AlertCauseDirection
import io.aaps.copilot.domain.alerts.AlertCauseSnapshotCodec
import io.aaps.copilot.domain.alerts.AlertCauseSnapshot
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class AlertAiContextBuilderTest {
    @Test
    fun coordinatorValidationAcceptsProductionDatasetWithActiveCalibration() = runTest {
        val now = 20L * DAY
        val sampleTs = now - 5L * 60_000L
        val rawGlucose = ClinicalGlucoseProjection(sampleTs, 6.0, "nightscout", "OK")
        val calibratedGlucose = ClinicalGlucoseProjection(sampleTs, 7.0, "nightscout", "OK")
        val reportBuilder = ClinicalReportDatasetBuilder(
            source = reportSource(glucose = listOf(rawGlucose)),
            resolveCalibratedGlucose = { _, _ -> emptyList() },
            alertAiSource = AlertAiDatasetSource {
                AlertAiDatasetSourceSnapshot(
                    report = reportSnapshot(glucose = listOf(rawGlucose)),
                    calibratedGlucose = listOf(calibratedGlucose),
                    timelineEvents = emptyList()
                )
            }
        )
        val dataset = reportBuilder.buildAlertAiDataset(now)
        val trigger = trigger(now)

        assertThat(dataset.detail24h.glucose.single().mmol).isEqualTo(6.0)
        assertThat(dataset.glucose14d.single().mmol).isEqualTo(7.0)

        val context = AlertAiContextBuilder().build(request(trigger, dataset))

        assertThat(AlertAiContextValidator.isValid(context, trigger)).isTrue()
    }

    @Test
    fun coordinatorValidationAcceptsProductionDatasetWithExactTherapyTail() = runTest {
        val summaryThrough = 20L * DAY
        val now = summaryThrough + 2L * 60_000L
        val tailTs = summaryThrough + 60_000L
        val therapy = ClinicalTherapyProjection(
            ts = tailTs,
            type = "meal_bolus",
            payloadJson = """{"carbs":9,"units":1}""",
            rowId = "exact-detail-tail"
        )
        val dataset = ClinicalReportDatasetBuilder(
            source = reportSource(therapy = listOf(therapy)),
            resolveCalibratedGlucose = { _, _ -> emptyList() },
            alertAiSource = AlertAiDatasetSource {
                AlertAiDatasetSourceSnapshot(
                    report = reportSnapshot(therapy = listOf(therapy)),
                    calibratedGlucose = emptyList(),
                    timelineEvents = emptyList()
                )
            }
        ).buildAlertAiDataset(now)
        val trigger = trigger(now)

        assertThat(dataset.therapy14d.single().ts).isEqualTo(tailTs)
        assertThat(dataset.detail24h.therapy.single().ts).isEqualTo(tailTs)

        val context = AlertAiContextBuilder().build(request(trigger, dataset))

        assertThat(AlertAiContextValidator.isValid(context, trigger)).isTrue()
    }

    @Test
    fun detailedTargetsPreserveSixCanonicalFieldsAndLifecycleStates() {
        val now = 20L * DAY
        val active = ClinicalTargetPoint(
            ts = now - 10L * 60_000L,
            lowMmol = 5.0,
            highMmol = 6.0,
            durationMs = 30L * 60_000L,
            endTs = now + 20L * 60_000L,
            cancelled = false
        )
        val cancelled = ClinicalTargetPoint(
            ts = now - 20L * 60_000L,
            lowMmol = null,
            highMmol = null,
            durationMs = 0L,
            endTs = now - 20L * 60_000L,
            cancelled = true
        )
        val expired = ClinicalTargetPoint(
            ts = now - 60L * 60_000L,
            lowMmol = 5.5,
            highMmol = 6.5,
            durationMs = 30L * 60_000L,
            endTs = now - 30L * 60_000L,
            cancelled = false
        )

        val context = AlertAiContextBuilder().build(
            request(
                now,
                dataset(now, emptyList(), emptyList(), targets = listOf(active, cancelled, expired))
            )
        )
        val targets = JsonParser.parseString(context.canonicalJson).asJsonObject
            .getAsJsonObject("detail24h")
            .getAsJsonArray("targets")

        assertThat(targets.map { row -> row.asJsonArray.size() }).containsExactly(6, 6, 6)
        assertThat(targets[0].asJsonArray.map(JsonElement::toString)).containsExactly(
            expired.ts.toString(), "5.5", "6.5", expired.durationMs.toString(),
            expired.endTs.toString(), "false"
        ).inOrder()
        assertThat(targets[1].asJsonArray.map(JsonElement::toString)).containsExactly(
            cancelled.ts.toString(), "null", "null", "0", cancelled.endTs.toString(), "true"
        ).inOrder()
        assertThat(targets[2].asJsonArray.map(JsonElement::toString)).containsExactly(
            active.ts.toString(), "5.0", "6.0", active.durationMs.toString(),
            active.endTs.toString(), "false"
        ).inOrder()
    }

    @Test
    fun invalidTargetBoundsTimestampsAndLifecycleFailClosed() {
        val now = 20L * DAY
        val invalidTargets = listOf(
            ClinicalTargetPoint(now - 1L, 6.0, 5.0),
            ClinicalTargetPoint(
                now - 1L,
                5.0,
                6.0,
                ClinicalReportDatasetBuilder.MAX_TARGET_DURATION_MS + 1L,
                now,
                false
            ),
            ClinicalTargetPoint(now - 1L, 5.0, 6.0, 60_000L, now - 120_000L, false),
            ClinicalTargetPoint(now - 1L, 5.0, 6.0, 0L, now - 1L, false),
            ClinicalTargetPoint(now - 1L, null, null, 60_000L, now + 59_999L, true),
            ClinicalTargetPoint(now - 1L, Double.NaN, 6.0, null, null, null)
        )

        invalidTargets.forEach { target ->
            assertThrows(AlertAiContextException.InvalidInput::class.java) {
                AlertAiContextBuilder().build(
                    request(
                        now,
                        dataset(now, emptyList(), emptyList(), targets = listOf(target))
                    )
                )
            }
        }
    }

    @Test
    fun canonicalContextUsesExact24hAnd14dBoundariesAndContainsNoInternalIdentifiers() {
        val now = 20L * DAY
        val dataset = dataset(
            now = now,
            glucose = listOf(
                ClinicalGlucosePoint(now - 14L * DAY, 5.0),
                ClinicalGlucosePoint(now - 24L * HOUR, 5.1),
                ClinicalGlucosePoint(now - 23L * HOUR, 5.2),
                ClinicalGlucosePoint(now - 1L, 5.3),
                ClinicalGlucosePoint(now, 9.9)
            ),
            events = listOf(
                event(
                    "private-local-id",
                    now - 14L * DAY,
                    CopilotContextNoteMarker.commandHeader(
                        "private-local-id",
                        1L,
                        AapsContextEventGateway.Operation.CREATE
                    ) + " | ordinary retained note"
                ),
                event("second-private-id", now - 1L, "ordinary note"),
                event("third-private-id", now - 1L, "another ordinary note")
            )
        )
        val request = AlertAiContextRequest(
            nowTs = now,
            dataset = dataset,
            localCauseSnapshot = AlertCauseSnapshot(
                """{"version":1,"cycleTimestamp":123,"cause":"DATA_INCOMPLETE","confidence":"LOW","factors":["DATA_INCOMPLETE"],"evidence":["CURRENT_EVIDENCE_MISSING"],"identityStatus":"MATCHED","sensitivity":{"cycleId":"private-cycle","settingsRevision":42,"timestamp":123,"isfMmolPerUnit":3.0,"crGramPerUnit":10.0,"isfSource":"AAPS","crSource":"AAPS","confidence":0.8}}"""
            ),
            stage = "LOW_PREDICTED_30",
            direction = AlertCauseDirection.LOW
        )

        val first = AlertAiContextBuilder().build(request)
        val reorderedDataset = dataset.copy(
            detail24h = dataset.detail24h.copy(
                glucose = dataset.detail24h.glucose.reversed(),
                calibratedGlucose = dataset.detail24h.calibratedGlucose.reversed()
            ),
            glucose14d = dataset.glucose14d.reversed(),
            events14d = dataset.events14d.reversed()
        )
        val second = AlertAiContextBuilder().build(request.copy(dataset = reorderedDataset))
        val root = JsonParser.parseString(first.canonicalJson).asJsonObject

        assertThat(first).isEqualTo(second)
        assertThat(first.sha256).isEqualTo(sha256(first.canonicalBytes.toByteArray()))
        assertThat(first.canonicalBytes.size).isAtMost(AlertAiContextBuilder.MAX_CONTEXT_BYTES)
        assertThat(first.rowCount).isAtMost(AlertAiContextBuilder.MAX_CONTEXT_ROWS)
        val detail24h = root.getAsJsonObject("detail24h")
        assertThat(detail24h.getAsJsonArray("rawGlucose")).hasSize(3)
        assertThat(detail24h.getAsJsonArray("calibratedGlucose")).hasSize(3)
        assertThat(detail24h.has("glucose")).isFalse()
        assertThat(root.getAsJsonArray("events14d")).hasSize(3)
        assertThat(first.canonicalJson).doesNotContain("private-local-id")
        assertThat(first.canonicalJson).doesNotContain("second-private-id")
        assertThat(first.canonicalJson).doesNotContain("third-private-id")
        assertThat(first.canonicalJson).doesNotContain("provenance")
        assertThat(first.canonicalJson).doesNotContain(CopilotContextNoteMarker.PREFIX)
        assertThat(first.canonicalJson).contains("ordinary retained note")
        assertThat(first.canonicalJson).doesNotContain("private-cycle")
        assertThat(first.canonicalJson).doesNotContain("settingsRevision")
        assertThat(first.canonicalJson).contains("ordinary note")
        assertThat(first.canonicalJson).contains("LOW_PREDICTED_30")
    }

    @Test
    fun rowAndByteLimitsFailClosedWithoutTruncatingCanonicalData() {
        val now = 20L * DAY
        val dataset = dataset(
            now = now,
            glucose = List(6) { index -> ClinicalGlucosePoint(now - index - 1L, 5.0) },
            events = emptyList()
        )
        val request = AlertAiContextRequest(
            nowTs = now,
            dataset = dataset,
            localCauseSnapshot = AlertCauseSnapshotCodec.failureSnapshot(),
            stage = "HIGH_NOW",
            direction = AlertCauseDirection.HIGH
        )

        assertThrows(AlertAiContextException.LimitExceeded::class.java) {
            AlertAiContextBuilder(maxRows = 5).build(request)
        }
        assertThrows(AlertAiContextException.LimitExceeded::class.java) {
            AlertAiContextBuilder(maxBytes = 1_024).build(request)
        }
    }

    @Test
    fun nonFiniteAndOutOfBoundsClinicalNumbersFailClosed() {
        val now = 20L * DAY
        val invalidDatasets = listOf(
            dataset(now, listOf(ClinicalGlucosePoint(now - 1L, Double.NaN)), emptyList()),
            dataset(
                now,
                emptyList(),
                emptyList(),
                therapy = listOf(ClinicalTherapyPoint(now - 1L, insulinU = Double.POSITIVE_INFINITY))
            ),
            dataset(
                now,
                emptyList(),
                emptyList(),
                targets = listOf(ClinicalTargetPoint(now - 1L, Double.NEGATIVE_INFINITY))
            ),
            dataset(
                now,
                emptyList(),
                emptyList(),
                forecasts = listOf(
                    ClinicalForecastPoint(now - 1L, 30, Double.NaN, 4.0, 8.0)
                )
            ),
            dataset(
                now,
                emptyList(),
                emptyList(),
                telemetry = listOf(
                    ClinicalTelemetryPoint(now - 1L, "iob_units", Double.MAX_VALUE, "OK")
                )
            )
        )

        invalidDatasets.forEach { invalid ->
            assertThrows(AlertAiContextException.InvalidInput::class.java) {
                AlertAiContextBuilder().build(request(now, invalid))
            }
        }
    }

    @Test
    fun aggregateSumsFailClosedBeforeExceedingClinicalBounds() {
        val now = 20L * DAY
        val excessiveTherapy = List(201) { index ->
            ClinicalTherapyPoint(
                ts = now - 14L * DAY + index + 1L,
                carbsG = 500.0
            )
        }
        val invalid = dataset(
            now = now,
            glucose = emptyList(),
            events = emptyList(),
            aggregateTherapy = excessiveTherapy
        )

        assertThrows(AlertAiContextException.InvalidInput::class.java) {
            AlertAiContextBuilder().build(request(now, invalid))
        }
    }

    private fun dataset(
        now: Long,
        glucose: List<ClinicalGlucosePoint>,
        events: List<ClinicalEventSummary>,
        therapy: List<ClinicalTherapyPoint> = emptyList(),
        targets: List<ClinicalTargetPoint> = emptyList(),
        forecasts: List<ClinicalForecastPoint> = emptyList(),
        telemetry: List<ClinicalTelemetryPoint> = emptyList(),
        aggregateTherapy: List<ClinicalTherapyPoint> = therapy
    ): AlertAiContextDataset {
        return AlertAiContextDataset(
            generatedAt = now,
            detail24h = ClinicalDetailWindow(
                fromTs = now - DAY,
                throughTs = now,
                glucose = glucose,
                calibratedGlucose = glucose,
                therapy = therapy,
                targets = targets,
                forecasts = forecasts,
                telemetry = telemetry
            ),
            glucose14d = glucose,
            therapy14d = aggregateTherapy,
            events14d = events
        )
    }

    private fun request(now: Long, dataset: AlertAiContextDataset) = AlertAiContextRequest(
        nowTs = now,
        dataset = dataset,
        localCauseSnapshot = AlertCauseSnapshotCodec.failureSnapshot(),
        stage = "LOW_PREDICTED_30",
        direction = AlertCauseDirection.LOW
    )

    private fun request(
        trigger: AlertAiAnalysisTrigger,
        dataset: AlertAiContextDataset
    ) = AlertAiContextRequest(
        nowTs = trigger.requestedAt,
        dataset = dataset,
        localCauseSnapshot = trigger.localCauseSnapshot,
        stage = trigger.stage,
        direction = trigger.direction
    )

    private fun trigger(now: Long) = AlertAiAnalysisTrigger(
        episodeId = "production-dataset",
        requestedAt = now,
        stage = "LOW_PREDICTED_30",
        direction = AlertCauseDirection.LOW,
        localCauseSnapshot = AlertCauseSnapshotCodec.failureSnapshot()
    )

    private fun reportSource(
        glucose: List<ClinicalGlucoseProjection> = emptyList(),
        therapy: List<ClinicalTherapyProjection> = emptyList()
    ): ClinicalReportDataSource = object : ClinicalReportDataSource {
        override suspend fun glucose(fromTs: Long, toTs: Long) = glucose

        override suspend fun therapy(
            fromTs: Long,
            toTs: Long,
            types: List<String>
        ) = therapy

        override suspend fun forecasts(
            fromTs: Long,
            toTs: Long
        ): List<ClinicalForecastProjection> = emptyList()

        override suspend fun telemetry(
            fromTs: Long,
            toTs: Long,
            keys: List<String>
        ): List<ClinicalTelemetryProjection> = emptyList()
    }

    private fun reportSnapshot(
        glucose: List<ClinicalGlucoseProjection> = emptyList(),
        therapy: List<ClinicalTherapyProjection> = emptyList()
    ) = ClinicalReportSnapshot(
        glucose = glucose,
        therapy = therapy,
        forecasts = emptyList(),
        telemetry = emptyList(),
        summaryTelemetry = emptyList()
    )

    private fun event(id: String, startTs: Long, note: String) = ClinicalEventSummary(
        localId = id,
        type = "STRESS",
        subtype = "",
        startTs = startTs,
        endTs = startTs + HOUR,
        severity = "MEDIUM",
        source = "USER",
        title = "Stress",
        note = note,
        status = "ACTIVE",
        provenance = "AAPS:private",
        syntheticUam = false
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val HOUR = 60L * 60L * 1_000L
        const val DAY = 24L * HOUR
    }
}
