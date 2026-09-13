package io.aaps.copilot.data.repository

import io.aaps.copilot.report.ClinicalPdfContentBlock
import io.aaps.copilot.report.ClinicalPdfContentCursor
import io.aaps.copilot.report.ClinicalPdfContentIdentity
import io.aaps.copilot.report.ClinicalPdfContentRole
import io.aaps.copilot.report.ClinicalPdfContentSource
import io.aaps.copilot.report.ClinicalReportDocument
import io.aaps.copilot.report.ClinicalReportDocumentContentSource
import io.aaps.copilot.report.iteratorCursor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ClinicalPdfContentSourceFailure {
    MISSING_PREPARED_PAYLOAD,
    REQUEST_HASH_MISMATCH,
    PAYLOAD_HASH_MISMATCH,
    PAYLOAD_CONTENT_MISMATCH,
    GENERATED_AT_MISMATCH,
    ZONE_MISMATCH,
    SCHEMA_MISMATCH,
    LOCAL_SUMMARY_MISMATCH,
    INVALID_PREPARED_PAYLOAD
}

sealed interface ClinicalPdfContentSourceResult {
    data class Ready(val source: ClinicalPdfContentSource) : ClinicalPdfContentSourceResult
    data class Failed(val reason: ClinicalPdfContentSourceFailure) : ClinicalPdfContentSourceResult
}

object ClinicalPdfContentSourceFactory {
    fun create(
        payload: ClinicalReportPayload?,
        local: ClinicalLocalReport,
        complete: ClinicalOpenAiResult?
    ): ClinicalPdfContentSourceResult {
        payload ?: return failed(ClinicalPdfContentSourceFailure.MISSING_PREPARED_PAYLOAD)
        val dataset = payload.dataset

        if (local.requestHash != payload.sha256) {
            return failed(ClinicalPdfContentSourceFailure.REQUEST_HASH_MISMATCH)
        }
        if (local.generatedAt != dataset.generatedAt) {
            return failed(ClinicalPdfContentSourceFailure.GENERATED_AT_MISMATCH)
        }
        if (local.zoneId != dataset.zoneId || runCatching { ZoneId.of(dataset.zoneId) }.isFailure) {
            return failed(ClinicalPdfContentSourceFailure.ZONE_MISMATCH)
        }
        if (dataset.schemaVersion != ClinicalReportDatasetBuilder.SCHEMA_VERSION) {
            return failed(ClinicalPdfContentSourceFailure.SCHEMA_MISMATCH)
        }
        if (ClinicalReportDatasetBuilder.sha256(payload.compactJson) != payload.sha256) {
            return failed(ClinicalPdfContentSourceFailure.PAYLOAD_HASH_MISMATCH)
        }

        val snapshot = try {
            ClinicalReportDatasetBuilder.canonicalPdfSnapshot(dataset).also(
                ClinicalPdfSnapshotValidator::requireValid
            )
        } catch (_: IllegalArgumentException) {
            return failed(ClinicalPdfContentSourceFailure.INVALID_PREPARED_PAYLOAD)
        }
        val canonicalCompact = try {
            ClinicalReportDatasetBuilder.serialize(dataset)
        } catch (_: IllegalArgumentException) {
            return failed(ClinicalPdfContentSourceFailure.INVALID_PREPARED_PAYLOAD)
        }
        if (canonicalCompact != payload.compactJson) {
            return failed(ClinicalPdfContentSourceFailure.PAYLOAD_CONTENT_MISMATCH)
        }
        if (!localMatchesDataset(local, dataset)) {
            return failed(ClinicalPdfContentSourceFailure.LOCAL_SUMMARY_MISMATCH)
        }

        val summaryDocument = try {
            ClinicalReportDocument.from(
                local = local.copy(
                    summary24h = snapshot.summary24h,
                    summary7d = snapshot.summary7d,
                    summary30d = snapshot.summary30d,
                    energyProfile = snapshot.energyProfile,
                    plannedActivities = emptyList(),
                    forecastQuality = snapshot.detail24h.forecastQuality,
                    eventSummaries = emptyList(),
                    eventTypeAssociations = snapshot.eventTypeAssociations,
                    remoteEventPreviewJson = EMPTY_REMOTE_EVENT_PREVIEW_JSON
                ),
                complete = complete
            )
        } catch (_: IllegalArgumentException) {
            return failed(ClinicalPdfContentSourceFailure.INVALID_PREPARED_PAYLOAD)
        }

        return ClinicalPdfContentSourceResult.Ready(
            CanonicalClinicalPdfContentSource(
                baseIdentity = ClinicalPdfContentIdentity(
                    requestSha256 = payload.sha256,
                    generatedAt = dataset.generatedAt,
                    zoneId = dataset.zoneId,
                    schemaVersion = dataset.schemaVersion
                ),
                dataset = snapshot,
                summaryDocument = summaryDocument
            )
        )
    }

    private fun localMatchesDataset(
        local: ClinicalLocalReport,
        dataset: ClinicalReportDataset
    ): Boolean =
        local.summary24h == dataset.summary24h &&
            local.summary7d == dataset.summary7d &&
            local.summary30d == dataset.summary30d &&
            local.energyProfile == dataset.energyProfile &&
            local.plannedActivities == dataset.plannedActivities &&
            local.forecastQuality == dataset.detail24h.forecastQuality &&
            local.eventSummaries == dataset.eventSummaries &&
            local.eventTypeAssociations == dataset.eventTypeAssociations &&
            local.remoteEventPreviewJson ==
            ClinicalReportDatasetBuilder.remoteEventPreviewJson(dataset)

    private fun failed(reason: ClinicalPdfContentSourceFailure) =
        ClinicalPdfContentSourceResult.Failed(reason)
}

private class CanonicalClinicalPdfContentSource(
    baseIdentity: ClinicalPdfContentIdentity,
    private val dataset: ClinicalReportDataset,
    summaryDocument: ClinicalReportDocument
) : ClinicalPdfContentSource {
    private val summarySource = ClinicalReportDocumentContentSource(summaryDocument)
    override val identity: ClinicalPdfContentIdentity = baseIdentity

    override fun open(): ClinicalPdfContentCursor = openBlocks()

    private fun openBlocks(): ClinicalPdfContentCursor = iteratorCursor(sequence {
        summarySource.open().use { summary ->
            while (true) {
                val block = summary.next() ?: break
                yield(block)
            }
        }
        yield(
            body(
                "All event relationships below are associations, not diagnoses or therapy instructions."
            )
        )
        yield(heading("24h detailed canonical data"))
        yield(heading("24h raw glucose"))
        dataset.detail24h.glucose.forEachIndexed { index, point ->
            yield(row(glucoseText(point), "24h/raw-glucose/$index"))
        }
        yield(heading("24h calibrated glucose"))
        dataset.detail24h.calibratedGlucose.forEachIndexed { index, point ->
            yield(row(glucoseText(point), "24h/calibrated-glucose/$index"))
        }
        yield(heading("24h all insulin, entered carbohydrates, and UAM carbohydrates"))
        dataset.detail24h.therapy.forEachIndexed { index, point ->
            yield(row(therapyText(point), "24h/therapy/$index"))
        }
        yield(heading("24h targets"))
        dataset.detail24h.targets.forEachIndexed { index, point ->
            yield(row(targetText(point), "24h/target/$index"))
        }
        yield(heading("24h forecasts"))
        dataset.detail24h.forecasts.forEachIndexed { index, point ->
            yield(row(forecastText(point), "24h/forecast/$index"))
        }
        yield(heading("24h physical activity and canonical telemetry"))
        dataset.detail24h.telemetry.forEachIndexed { index, point ->
            yield(row(telemetryText(point), "24h/telemetry/$index"))
        }
        yieldPeriodEvents("24h", dataset.detail24h.fromTs, dataset.detail24h.throughTs)
        yieldPeriodPlannedActivities("24h", dataset.detail24h.fromTs, dataset.detail24h.throughTs)

        yield(heading("7d full canonical time series"))
        dataset.glucose7d.forEachIndexed { index, point ->
            yield(row(glucoseText(point), "7d/glucose/$index"))
        }
        dataset.therapy7d.forEachIndexed { index, point ->
            yield(row(therapyText(point), "7d/therapy/$index"))
        }
        dataset.targets7d.forEachIndexed { index, point ->
            yield(row(targetText(point), "7d/target/$index"))
        }
        yieldPeriodEvents("7d", dataset.summary7d.fromTs, dataset.summary7d.throughTs)
        yieldPeriodPlannedActivities("7d", dataset.summary7d.fromTs, dataset.summary7d.throughTs)
        yield(heading("7d physical activity"))
        yield(
            body(
                "The prepared canonical dataset provides the 7d activity summary above; " +
                    "it does not contain a reopenable 7d activity row series."
            )
        )

        yield(heading("30d full canonical time series"))
        dataset.glucose30d.forEachIndexed { index, point ->
            yield(row(glucoseText(point), "30d/glucose/$index"))
        }
        dataset.therapy30d.forEachIndexed { index, point ->
            yield(row(therapyText(point), "30d/therapy/$index"))
        }
        dataset.targets30d.forEachIndexed { index, point ->
            yield(row(targetText(point), "30d/target/$index"))
        }
        yieldPeriodEvents("30d", dataset.summary30d.fromTs, dataset.summary30d.throughTs)
        yieldPeriodPlannedActivities("30d", dataset.summary30d.fromTs, dataset.summary30d.throughTs)
        yield(heading("30d physical activity"))
        yield(
            body(
                "The prepared canonical dataset provides the 30d activity summary above; " +
                    "it does not contain a reopenable 30d activity row series."
            )
        )
    }.iterator())

    private suspend fun SequenceScope<ClinicalPdfContentBlock>.yieldPeriodEvents(
        label: String,
        fromTs: Long,
        throughTs: Long
    ) {
        yield(heading("$label clinical events"))
        dataset.eventSummaries.asSequence()
            .filter { it.startTs <= throughTs && it.endTs >= fromTs }
            .forEach { event ->
                yield(row(eventText(event), "$label/event/${event.localId}"))
            }
    }

    private suspend fun SequenceScope<ClinicalPdfContentBlock>.yieldPeriodPlannedActivities(
        label: String,
        fromTs: Long,
        throughTs: Long
    ) {
        yield(heading("$label planned activity"))
        val window = ClinicalPreparedPeriodWindow(fromTs, throughTs)
        dataset.plannedActivities.asSequence()
            .filter { activity -> ClinicalPlannedActivityPeriodPolicy.overlaps(activity, window) }
            .sortedWith(CLINICAL_PLANNED_ACTIVITY_ORDER)
            .forEachIndexed { index, activity ->
                yield(row(plannedActivityText(activity), "$label/planned-activity/$index"))
            }
    }

}

private fun heading(text: String) = ClinicalPdfContentBlock(text, ClinicalPdfContentRole.HEADING)
private fun body(text: String) = ClinicalPdfContentBlock(text, ClinicalPdfContentRole.BODY)
private fun row(text: String, stableKey: String) =
    ClinicalPdfContentBlock(text, ClinicalPdfContentRole.ITEM, stableKey)

private fun glucoseText(point: ClinicalGlucosePoint): String =
    "${instant(point.ts)} | glucose=${decimal(point.mmol)} mmol/L"

private fun therapyText(point: ClinicalTherapyPoint): String = buildString {
    append(instant(point.ts))
    append(" | insulin=").append(point.insulinU?.let(::decimal) ?: "none").append(" U")
    append(" | enteredCarbs=")
        .append(if (!point.syntheticUam) point.carbsG?.let(::decimal) ?: "none" else "none")
        .append(" g")
    append(" | uamCarbs=")
        .append(if (point.syntheticUam) point.carbsG?.let(::decimal) ?: "none" else "none")
        .append(" g")
    point.insulinEvidence?.let { append(" | insulinEvidence=").append(it.name) }
    point.contextKind?.let { append(" | context=").append(it.name) }
}

private fun targetText(point: ClinicalTargetPoint): String =
    "${instant(point.ts)} | low=${point.lowMmol?.let(::decimal) ?: "none"} mmol/L" +
        " | high=${point.highMmol?.let(::decimal) ?: "none"} mmol/L" +
        " | durationMin=${point.durationMs?.let { Math.floorDiv(it, 60_000L) } ?: "none"}" +
        " | end=${point.endTs?.let(::instant) ?: "none"}" +
        " | cancelled=${point.cancelled ?: "none"}"

private fun forecastText(point: ClinicalForecastPoint): String =
    "${instant(point.ts)} | horizon=${point.horizonMin} min" +
        " | predicted=${decimal(point.mmol)} mmol/L" +
        " | interval=${decimal(point.lower)}..${decimal(point.upper)} mmol/L"

private fun telemetryText(point: ClinicalTelemetryPoint): String =
    "${instant(point.ts)} | ${point.key}=${decimal(point.value)}" +
        " | quality=${point.quality} | origin=${point.origin.name}"

private fun eventText(event: ClinicalEventSummary): String = buildString {
    append("localId=").append(event.localId)
    append(" | type=").append(event.type)
    append(" | subtype=").append(event.subtype)
    append(" | start=").append(instant(event.startTs))
    append(" | end=").append(instant(event.endTs))
    append(" | severity=").append(event.severity)
    append(" | source=").append(event.source)
    append(" | title=").append(event.title)
    append(" | note=").append(event.note ?: "none")
    append(" | status=").append(event.status)
    append(" | provenance=").append(event.provenance)
    append(" | syntheticUam=").append(event.syntheticUam)
}

private fun plannedActivityText(activity: ClinicalPlannedActivitySummary): String =
    "${instant(activity.plannedStartMs)} | type=${activity.type}" +
        " | intensity=${activity.intensity} | durationMin=${activity.plannedDurationMinutes}" +
        " | observedMin=${activity.observedMinutes ?: "none"}" +
        " | adherence=${activity.adherence}" +
        " | targetDecision=${activity.targetDecision ?: "none"}" +
        activity.targetBlockers.takeIf(List<String>::isNotEmpty)
            ?.joinToString(prefix = " | blockers=")
            .orEmpty()

private fun instant(timestamp: Long): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timestamp))

private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.4f", value)
