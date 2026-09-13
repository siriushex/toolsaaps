package io.aaps.copilot.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonWriter
import io.aaps.copilot.domain.alerts.AlertCauseDirection
import io.aaps.copilot.domain.alerts.AlertCauseSnapshot
import io.aaps.copilot.domain.alerts.AlertCauseSnapshotCodec
import io.aaps.copilot.domain.model.TherapyComponentPolicy
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.round
import okio.ByteString
import okio.ByteString.Companion.toByteString

data class AlertAiContextRequest(
    val nowTs: Long,
    val dataset: AlertAiContextDataset,
    val localCauseSnapshot: AlertCauseSnapshot,
    val stage: String,
    val direction: AlertCauseDirection
)

class AlertAiCanonicalContext internal constructor(
    val canonicalJson: String,
    val canonicalBytes: ByteString,
    val sha256: String,
    val rowCount: Int,
    issuer: Any? = null
) {
    private val builderIssued = issuer === AlertAiContextBuilderIssuer

    internal fun isBuilderIssued(): Boolean = builderIssued

    override fun equals(other: Any?): Boolean =
        other is AlertAiCanonicalContext &&
            canonicalJson == other.canonicalJson &&
            canonicalBytes == other.canonicalBytes &&
            sha256 == other.sha256 &&
            rowCount == other.rowCount

    override fun hashCode(): Int {
        var result = canonicalJson.hashCode()
        result = 31 * result + canonicalBytes.hashCode()
        result = 31 * result + sha256.hashCode()
        return 31 * result + rowCount
    }
}

private object AlertAiContextBuilderIssuer

sealed class AlertAiContextException : Exception() {
    class InvalidInput : AlertAiContextException()
    class LimitExceeded : AlertAiContextException()
}

internal data class AlertAiContextByteObservation(
    val attemptedBytes: Int,
    val retainedBytes: Int,
    val limit: Int
)

internal fun interface AlertAiContextByteProbe {
    fun onWrite(observation: AlertAiContextByteObservation)

    companion object {
        val NONE = AlertAiContextByteProbe { }
    }
}

class AlertAiContextBuilder(
    private val maxBytes: Int = MAX_CONTEXT_BYTES,
    private val maxRows: Int = MAX_CONTEXT_ROWS
) {
    private var byteProbe: AlertAiContextByteProbe = AlertAiContextByteProbe.NONE

    internal constructor(
        maxBytes: Int,
        maxRows: Int = MAX_CONTEXT_ROWS,
        byteProbe: AlertAiContextByteProbe
    ) : this(maxBytes, maxRows) {
        this.byteProbe = byteProbe
    }

    init {
        require(maxBytes in 256..MAX_CONTEXT_BYTES)
        require(maxRows in 1..MAX_CONTEXT_ROWS)
    }

    fun build(request: AlertAiContextRequest): AlertAiCanonicalContext {
        if (request.nowTs <= 0L || request.stage.length !in 1..64 ||
            request.stage.any { !(it == '_' || it.isLetterOrDigit()) }
        ) {
            throw AlertAiContextException.InvalidInput()
        }
        val retainedWorkBudget = request.dataset.retainedWorkBudget
        retainedWorkBudget?.reserve(AlertAiDatasetDerivedSourceName.ALERT_CONTEXT_LOCAL_CAUSE, 1)
        val localCauseJson = canonicalRedactedAlertCause(request.localCauseSnapshot)
            ?: throw AlertAiContextException.InvalidInput()
        val from24 = subtractExactOrFail(request.nowTs, DAY_MS)
        val from14 = subtractExactOrFail(request.nowTs, FOURTEEN_DAYS_MS)
        val dataset = request.dataset

        val detailRawGlucose = selectGlucose(
            source = dataset.detail24h.glucose,
            fromTs = from24,
            throughTsExclusive = request.nowTs,
            retainedWorkBudget = retainedWorkBudget
        )
        val detailCalibratedGlucose = selectGlucose(
            source = dataset.detail24h.calibratedGlucose,
            fromTs = from24,
            throughTsExclusive = request.nowTs,
            retainedWorkBudget = retainedWorkBudget
        )
        val detailTherapy = selectReferences(
            source = dataset.detail24h.therapy,
            fromTs = from24,
            throughTsExclusive = request.nowTs,
            timestamp = ClinicalTherapyPoint::ts,
            validate = ::requireValidTherapy,
            ordering = AlertAiCanonicalOrdering.therapy,
            retainedWorkBudget = retainedWorkBudget
        )
        val detailTargets = selectReferences(
            source = dataset.detail24h.targets,
            fromTs = from24,
            throughTsExclusive = request.nowTs,
            timestamp = ClinicalTargetPoint::ts,
            validate = ::requireValidTarget,
            ordering = AlertAiCanonicalOrdering.target,
            retainedWorkBudget = retainedWorkBudget
        )
        val detailForecasts = selectReferences(
            source = dataset.detail24h.forecasts,
            fromTs = from24,
            throughTsExclusive = request.nowTs,
            timestamp = ClinicalForecastPoint::ts,
            validate = ::requireValidForecast,
            ordering = AlertAiCanonicalOrdering.forecast,
            retainedWorkBudget = retainedWorkBudget
        )
        val detailTelemetry = selectReferences(
            source = dataset.detail24h.telemetry,
            fromTs = from24,
            throughTsExclusive = request.nowTs,
            timestamp = ClinicalTelemetryPoint::ts,
            validate = ::requireValidTelemetry,
            ordering = AlertAiCanonicalOrdering.telemetry,
            retainedWorkBudget = retainedWorkBudget
        )

        val glucose14 = selectGlucose(
            dataset.glucose14d,
            from14,
            request.nowTs,
            retainedWorkBudget
        )
        val therapy14 = selectReferences(
            source = dataset.therapy14d,
            fromTs = from14,
            throughTsExclusive = request.nowTs,
            timestamp = ClinicalTherapyPoint::ts,
            validate = ::requireValidTherapy,
            ordering = AlertAiCanonicalOrdering.therapy,
            retainedWorkBudget = retainedWorkBudget
        )
        val daily14 = buildDailyAggregates(
            from14,
            glucose14,
            therapy14,
            retainedWorkBudget
        )
        val events14 = buildCanonicalEvents(
            dataset.events14d,
            from14,
            request.nowTs,
            retainedWorkBudget
        )

        var rowCount = 0
        rowCount = addExactOrFail(rowCount, detailRawGlucose.size)
        rowCount = addExactOrFail(rowCount, detailCalibratedGlucose.size)
        rowCount = addExactOrFail(rowCount, detailTherapy.size)
        rowCount = addExactOrFail(rowCount, detailTargets.size)
        rowCount = addExactOrFail(rowCount, detailForecasts.size)
        rowCount = addExactOrFail(rowCount, detailTelemetry.size)
        rowCount = addExactOrFail(rowCount, daily14.size)
        rowCount = addExactOrFail(rowCount, events14.size)
        if (rowCount > maxRows) throw AlertAiContextException.LimitExceeded()

        val output = AlertAiCanonicalUtf8Writer(maxBytes, byteProbe)
        JsonWriter(output).use { writer ->
            writeCanonicalJson(
                writer = writer,
                request = request,
                localCauseJson = localCauseJson,
                from24 = from24,
                from14 = from14,
                detailRawGlucose = detailRawGlucose,
                detailCalibratedGlucose = detailCalibratedGlucose,
                detailTherapy = detailTherapy,
                detailTargets = detailTargets,
                detailForecasts = detailForecasts,
                detailTelemetry = detailTelemetry,
                daily14 = daily14,
                events14 = events14
            )
        }
        val canonicalJson = output.finish()
        val bytes = canonicalJson.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > maxBytes || bytes.size != output.byteCount()) {
            throw AlertAiContextException.LimitExceeded()
        }
        retainedWorkBudget?.reserve(AlertAiDatasetDerivedSourceName.ALERT_CANONICAL_CONTEXT, 1)
        return AlertAiCanonicalContext(
            canonicalJson = canonicalJson,
            canonicalBytes = bytes.toByteString(),
            sha256 = sha256(bytes),
            rowCount = rowCount,
            issuer = AlertAiContextBuilderIssuer
        ).also { context ->
            if (!AlertAiContextValidator.isValid(context)) {
                throw AlertAiContextException.InvalidInput()
            }
        }
    }

    private fun selectGlucose(
        source: List<ClinicalGlucosePoint>,
        fromTs: Long,
        throughTsExclusive: Long,
        retainedWorkBudget: AlertAiRetainedDerivedBudget?
    ): List<ClinicalGlucosePoint> {
        reserveContextList(retainedWorkBudget)
        val selected = ArrayList<ClinicalGlucosePoint>()
        source.forEach { point ->
            if (point.ts in fromTs until throughTsExclusive) {
                requireClinicalMmol(point.mmol)
                selected += point
            }
        }
        selected.sortWith(compareBy(ClinicalGlucosePoint::ts, ClinicalGlucosePoint::mmol))
        val iterator = selected.listIterator()
        var previousTs: Long? = null
        while (iterator.hasNext()) {
            val point = iterator.next()
            if (point.ts == previousTs) iterator.remove() else previousTs = point.ts
        }
        return selected
    }

    private fun <T> selectReferences(
        source: List<T>,
        fromTs: Long,
        throughTsExclusive: Long,
        timestamp: (T) -> Long,
        validate: (T) -> Unit,
        ordering: Comparator<T>,
        retainedWorkBudget: AlertAiRetainedDerivedBudget?
    ): List<T> {
        reserveContextList(retainedWorkBudget)
        val selected = ArrayList<T>()
        source.forEach { row ->
            if (timestamp(row) in fromTs until throughTsExclusive) {
                validate(row)
                selected += row
            }
        }
        selected.sortWith(ordering)
        return selected
    }

    private fun buildDailyAggregates(
        from14: Long,
        glucose: List<ClinicalGlucosePoint>,
        therapy: List<ClinicalTherapyPoint>,
        retainedWorkBudget: AlertAiRetainedDerivedBudget?
    ): List<AlertAiDailyAggregate> {
        reserveContextList(retainedWorkBudget)
        val glucoseCounts = IntArray(14)
        val glucoseSums = DoubleArray(14)
        val glucoseMinimums = DoubleArray(14) { Double.POSITIVE_INFINITY }
        val glucoseMaximums = DoubleArray(14) { Double.NEGATIVE_INFINITY }
        val insulinSums = DoubleArray(14)
        val enteredCarbSums = DoubleArray(14)
        val uamCarbSums = DoubleArray(14)
        glucose.forEach { point ->
            val day = ((point.ts - from14) / DAY_MS).toInt()
            if (day !in 0 until 14) throw AlertAiContextException.InvalidInput()
            glucoseCounts[day] = addExactOrFail(glucoseCounts[day], 1)
            glucoseSums[day] = addFinite(glucoseSums[day], point.mmol)
            glucoseMinimums[day] = minOf(glucoseMinimums[day], point.mmol)
            glucoseMaximums[day] = maxOf(glucoseMaximums[day], point.mmol)
        }
        therapy.forEach { point ->
            val day = ((point.ts - from14) / DAY_MS).toInt()
            if (day !in 0 until 14) throw AlertAiContextException.InvalidInput()
            point.insulinU?.let { value ->
                insulinSums[day] = addBounded(
                    insulinSums[day],
                    value,
                    ClinicalOpenAiClient.MAX_RECORDED_INSULIN_UNITS
                )
            }
            point.carbsG?.let { value ->
                if (point.syntheticUam) {
                    uamCarbSums[day] = addBounded(
                        uamCarbSums[day],
                        value,
                        ClinicalOpenAiClient.MAX_RECORDED_CARBS_GRAMS
                    )
                } else {
                    enteredCarbSums[day] = addBounded(
                        enteredCarbSums[day],
                        value,
                        ClinicalOpenAiClient.MAX_RECORDED_CARBS_GRAMS
                    )
                }
            }
        }
        val result = ArrayList<AlertAiDailyAggregate>(14)
        repeat(14) { day ->
            retainedWorkBudget?.reserve(
                AlertAiDatasetDerivedSourceName.ALERT_CONTEXT_DAILY_AGGREGATE,
                1
            )
            val count = glucoseCounts[day]
            result += AlertAiDailyAggregate(
                day = day,
                glucoseCount = count,
                meanGlucose = if (count == 0) null else glucoseSums[day] / count,
                minimumGlucose = glucoseMinimums[day].takeIf { count > 0 },
                maximumGlucose = glucoseMaximums[day].takeIf { count > 0 },
                insulinUnits = round6(insulinSums[day]),
                enteredCarbsGrams = round6(enteredCarbSums[day]),
                uamCarbsGrams = round6(uamCarbSums[day])
            )
        }
        return result
    }

    private fun buildCanonicalEvents(
        source: List<ClinicalEventSummary>,
        fromTs: Long,
        throughTsExclusive: Long,
        retainedWorkBudget: AlertAiRetainedDerivedBudget?
    ): List<ClinicalContextEvent> {
        reserveContextList(retainedWorkBudget)
        val events = ArrayList<ClinicalContextEvent>()
        source.forEach { event ->
            if (event.startTs < throughTsExclusive && event.endTs >= fromTs &&
                event.type.isNotBlank() && event.startTs >= 0L && event.endTs >= event.startTs
            ) {
                requireWellFormedUtf16(event.type)
                requireWellFormedUtf16(event.subtype)
                requireWellFormedUtf16(event.severity)
                requireWellFormedUtf16(event.source)
                requireWellFormedUtf16(event.title)
                event.note?.let(::requireWellFormedUtf16)
                retainedWorkBudget?.reserve(
                    AlertAiDatasetDerivedSourceName.ALERT_CONTEXT_CANONICAL_EVENT,
                    1
                )
                events += ClinicalContextEvent(
                    type = event.type.take(40),
                    subtype = event.subtype.takeIf(String::isNotBlank)?.take(40),
                    startTs = event.startTs,
                    endTs = event.endTs.takeIf { it > event.startTs },
                    severity = event.severity.take(24),
                    source = coarseEventSource(event.source),
                    title = sanitizedEventText(event.title, 60),
                    note = event.note?.let { sanitizedEventText(it, 500) }
                )
            }
        }
        events.sortWith(AlertAiCanonicalOrdering.event)
        return events
    }

    private fun sanitizedEventText(value: String, limit: Int): String? {
        if (value.isBlank()) return null
        val withoutMarkers = CopilotContextNoteMarker.stripProtectedMarkers(value)
        val sanitized = if (withoutMarkers == value) value else withoutMarkers.trim('|', ' ')
        return sanitized.takeIf(String::isNotBlank)?.take(limit)?.let(::requireWellFormedUtf16)
    }

    private fun coarseEventSource(source: String): String =
        when (source.trim().uppercase(Locale.US)) {
            "USER" -> "USER"
            "AAPS" -> "AAPS"
            else -> "AUTOMATIC"
        }

    @Suppress("LongParameterList")
    private fun writeCanonicalJson(
        writer: JsonWriter,
        request: AlertAiContextRequest,
        localCauseJson: String,
        from24: Long,
        from14: Long,
        detailRawGlucose: List<ClinicalGlucosePoint>,
        detailCalibratedGlucose: List<ClinicalGlucosePoint>,
        detailTherapy: List<ClinicalTherapyPoint>,
        detailTargets: List<ClinicalTargetPoint>,
        detailForecasts: List<ClinicalForecastPoint>,
        detailTelemetry: List<ClinicalTelemetryPoint>,
        daily14: List<AlertAiDailyAggregate>,
        events14: List<ClinicalContextEvent>
    ) {
        writer.beginObject()
        writer.name("schemaVersion").value(1)
        writer.name("generatedAt").value(request.nowTs)
        writer.name("stage").writeString(request.stage)
        writer.name("direction").writeString(request.direction.name)
        writer.name("localCause").jsonValue(requireWellFormedUtf16(localCauseJson))
        writer.name("detail24h").beginObject()
        writer.name("fromTs").value(from24)
        writer.name("throughTsExclusive").value(request.nowTs)
        writer.name("rawGlucose").writeGlucose(detailRawGlucose)
        writer.name("calibratedGlucose").writeGlucose(detailCalibratedGlucose)
        writer.name("therapy").writeTherapy(detailTherapy)
        writer.name("targets").writeTargets(detailTargets)
        writer.name("forecasts").writeForecasts(detailForecasts)
        writer.name("telemetry").writeTelemetry(detailTelemetry)
        writer.endObject()
        writer.name("aggregate14dFromTs").value(from14)
        writer.name("aggregate14dThroughTsExclusive").value(request.nowTs)
        writer.name("daily14d").beginArray()
        daily14.forEach { day -> writer.writeDailyAggregate(day) }
        writer.endArray()
        writer.name("events14d").beginArray()
        events14.forEach { event -> writer.writeEvent(event) }
        writer.endArray()
        writer.endObject()
    }

    private fun JsonWriter.writeGlucose(rows: List<ClinicalGlucosePoint>) {
        beginArray()
        rows.forEach { point ->
            beginArray()
            value(point.ts)
            value(round6(point.mmol))
            endArray()
        }
        endArray()
    }

    private fun JsonWriter.writeTherapy(rows: List<ClinicalTherapyPoint>) {
        beginArray()
        rows.forEach { point ->
            beginArray()
            value(point.ts)
            writeNullableNumber(point.insulinU)
            writeNullableNumber(point.carbsG)
            value(point.syntheticUam)
            writeNullableString(point.insulinEvidence?.name)
            writeNullableString(point.contextKind?.name)
            endArray()
        }
        endArray()
    }

    private fun JsonWriter.writeTargets(rows: List<ClinicalTargetPoint>) {
        beginArray()
        rows.forEach { point ->
            beginArray()
            value(point.ts)
            writeNullableNumber(point.lowMmol)
            writeNullableNumber(point.highMmol)
            point.durationMs?.let(::value) ?: nullValue()
            point.endTs?.let(::value) ?: nullValue()
            point.cancelled?.let(::value) ?: nullValue()
            endArray()
        }
        endArray()
    }

    private fun JsonWriter.writeForecasts(rows: List<ClinicalForecastPoint>) {
        beginArray()
        rows.forEach { point ->
            beginArray()
            value(point.ts)
            value(point.horizonMin)
            value(round6(point.mmol))
            value(round6(point.lower))
            value(round6(point.upper))
            endArray()
        }
        endArray()
    }

    private fun JsonWriter.writeTelemetry(rows: List<ClinicalTelemetryPoint>) {
        beginArray()
        rows.forEach { point ->
            beginArray()
            value(point.ts)
            writeString(point.key.take(64))
            value(round6(point.value))
            writeString(point.quality.take(24))
            writeString(point.origin.name)
            endArray()
        }
        endArray()
    }

    private fun JsonWriter.writeDailyAggregate(day: AlertAiDailyAggregate) {
        beginObject()
        name("day").value(day.day)
        name("glucoseCount").value(day.glucoseCount)
        name("meanGlucose").writeNullableNumber(day.meanGlucose)
        name("minimumGlucose").writeNullableNumber(day.minimumGlucose)
        name("maximumGlucose").writeNullableNumber(day.maximumGlucose)
        name("insulinUnits").value(day.insulinUnits)
        name("enteredCarbsGrams").value(day.enteredCarbsGrams)
        name("uamCarbsGrams").value(day.uamCarbsGrams)
        endObject()
    }

    private fun JsonWriter.writeEvent(event: ClinicalContextEvent) {
        beginObject()
        name("type").writeString(event.type)
        name("subtype").writeNullableString(event.subtype)
        name("startTs").value(event.startTs)
        name("endTs").apply { event.endTs?.let(::value) ?: nullValue() }
        name("severity").writeString(event.severity)
        name("source").writeString(event.source)
        name("title").writeNullableString(event.title)
        name("note").writeNullableString(event.note)
        endObject()
    }

    private fun JsonWriter.writeNullableNumber(value: Double?) {
        if (value == null) nullValue() else value(round6(value))
    }

    private fun JsonWriter.writeNullableString(value: String?) {
        if (value == null) nullValue() else writeString(value)
    }

    private fun JsonWriter.writeString(value: String) {
        this.value(requireWellFormedUtf16(value))
    }

    private fun reserveContextList(retainedWorkBudget: AlertAiRetainedDerivedBudget?) {
        retainedWorkBudget?.reserve(AlertAiDatasetDerivedSourceName.ALERT_CONTEXT_LIST, 1)
    }

    private fun requireValidTherapy(point: ClinicalTherapyPoint) {
        if (
            !TherapyComponentPolicy.isValidCanonicalRow(
                insulinU = point.insulinU,
                carbsG = point.carbsG,
                syntheticUam = point.syntheticUam,
                hasInsulinEvidence = point.insulinEvidence != null,
                hasContext = point.contextKind != null
            )
        ) throw AlertAiContextException.InvalidInput()
    }

    private fun requireValidTarget(point: ClinicalTargetPoint) {
        if (!AlertAiCanonicalTargetPolicy.isValid(point)) {
            throw AlertAiContextException.InvalidInput()
        }
    }

    private fun requireValidForecast(point: ClinicalForecastPoint) {
        if (point.horizonMin !in 0..180) throw AlertAiContextException.InvalidInput()
        requireClinicalMmol(point.mmol)
        requireClinicalMmol(point.lower)
        requireClinicalMmol(point.upper)
        if (point.lower > point.mmol || point.mmol > point.upper) {
            throw AlertAiContextException.InvalidInput()
        }
    }

    private fun requireValidTelemetry(point: ClinicalTelemetryPoint) {
        if (!ClinicalReportDatasetBuilder.isValidCanonicalTelemetryPoint(point)) {
            throw AlertAiContextException.InvalidInput()
        }
        requireWellFormedUtf16(point.key)
        requireWellFormedUtf16(point.quality)
    }

    /** Alert context rejects malformed UTF-16 instead of encoding replacement characters. */
    private fun requireWellFormedUtf16(value: String): String {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                current.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                        throw AlertAiContextException.InvalidInput()
                    }
                    index += 2
                }
                current.isLowSurrogate() -> throw AlertAiContextException.InvalidInput()
                else -> index += 1
            }
        }
        return value
    }

    private fun requireClinicalMmol(value: Double) {
        if (
            !value.isFinite() ||
            value !in ClinicalOpenAiClient.MIN_CLINICAL_MMOL..ClinicalOpenAiClient.MAX_CLINICAL_MMOL
        ) throw AlertAiContextException.InvalidInput()
    }

    private fun addFinite(left: Double, right: Double): Double = (left + right).also { sum ->
        if (!right.isFinite() || !sum.isFinite()) throw AlertAiContextException.InvalidInput()
    }

    private fun addBounded(left: Double, right: Double, maximum: Double): Double =
        addFinite(left, right).also { sum ->
            if (right < 0.0 || sum > maximum) throw AlertAiContextException.InvalidInput()
        }

    private fun round6(value: Double): Double {
        if (!value.isFinite()) throw AlertAiContextException.InvalidInput()
        val scaled = value * 1_000_000.0
        if (!scaled.isFinite()) throw AlertAiContextException.InvalidInput()
        return (round(scaled) / 1_000_000.0).also {
            if (!it.isFinite()) throw AlertAiContextException.InvalidInput()
        }
    }

    private fun subtractExactOrFail(left: Long, right: Long): Long = try {
        Math.subtractExact(left, right)
    } catch (_: ArithmeticException) {
        throw AlertAiContextException.InvalidInput()
    }

    private fun addExactOrFail(left: Int, right: Int): Int = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        throw AlertAiContextException.LimitExceeded()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_CONTEXT_BYTES = 256 * 1_024
        const val MAX_CONTEXT_ROWS = 12_000
        private const val DAY_MS = 24L * 60L * 60L * 1_000L
        private const val FOURTEEN_DAYS_MS = 14L * DAY_MS
    }
}

private data class AlertAiDailyAggregate(
    val day: Int,
    val glucoseCount: Int,
    val meanGlucose: Double?,
    val minimumGlucose: Double?,
    val maximumGlucose: Double?,
    val insulinUnits: Double,
    val enteredCarbsGrams: Double,
    val uamCarbsGrams: Double
)

/**
 * Streams canonical JSON into a StringBuilder whose retained UTF-8 content never exceeds maxBytes.
 * This byte budget is independent from the logical retained-work ledger; JSON primitives are not
 * work rows. The final ByteArray is therefore created only from an already byte-bounded String.
 * Malformed UTF-16 is rejected; it is never replaced or retained as canonical output.
 */
private class AlertAiCanonicalUtf8Writer(
    private val maxBytes: Int,
    private val probe: AlertAiContextByteProbe
) : Writer() {
    private val output = StringBuilder(minOf(maxBytes, INITIAL_CAPACITY))
    private var retainedBytes = 0
    private var pendingHighSurrogate: Char? = null
    private var closed = false

    override fun write(cbuf: CharArray, off: Int, len: Int) {
        requireBounds(cbuf.size, off, len)
        ensureOpen()
        for (index in off until off + len) appendCodeUnit(cbuf[index])
    }

    override fun write(str: String, off: Int, len: Int) {
        requireBounds(str.length, off, len)
        ensureOpen()
        for (index in off until off + len) appendCodeUnit(str[index])
    }

    override fun write(c: Int) {
        ensureOpen()
        appendCodeUnit(c.toChar())
    }

    override fun flush() {
        ensureOpen()
    }

    override fun close() {
        if (closed) return
        flushPendingHighSurrogate()
        closed = true
    }

    fun finish(): String {
        close()
        return output.toString()
    }

    fun byteCount(): Int = retainedBytes

    private fun appendCodeUnit(value: Char) {
        pendingHighSurrogate?.let { high ->
            if (value.isLowSurrogate()) {
                appendChecked(4, high, value)
                pendingHighSurrogate = null
                return
            }
            pendingHighSurrogate = null
            throw AlertAiContextException.InvalidInput()
        }
        when {
            value.isHighSurrogate() -> pendingHighSurrogate = value
            value.isLowSurrogate() -> throw AlertAiContextException.InvalidInput()
            value.code <= 0x7f -> appendChecked(1, value)
            value.code <= 0x7ff -> appendChecked(2, value)
            else -> appendChecked(3, value)
        }
    }

    private fun flushPendingHighSurrogate() {
        if (pendingHighSurrogate != null) throw AlertAiContextException.InvalidInput()
        pendingHighSurrogate = null
    }

    private fun appendChecked(bytes: Int, first: Char, second: Char? = null) {
        val attempted = try {
            Math.addExact(retainedBytes, bytes)
        } catch (_: ArithmeticException) {
            throw AlertAiContextException.LimitExceeded()
        }
        if (attempted > maxBytes) {
            probe.onWrite(AlertAiContextByteObservation(attempted, retainedBytes, maxBytes))
            throw AlertAiContextException.LimitExceeded()
        }
        output.append(first)
        second?.let(output::append)
        retainedBytes = attempted
        probe.onWrite(AlertAiContextByteObservation(attempted, retainedBytes, maxBytes))
    }

    private fun ensureOpen() = check(!closed) { "Writer is closed" }

    private fun requireBounds(size: Int, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= size - length)
    }

    companion object {
        private const val INITIAL_CAPACITY = 16 * 1_024
    }
}

internal object AlertAiCanonicalTargetPolicy {
    private const val END_TOLERANCE_MS = 60_000L

    fun isValid(point: ClinicalTargetPoint): Boolean {
        val cancelled = point.cancelled == true
        if (point.lowMmol == null && point.highMmol == null && !cancelled) return false
        if (point.lowMmol != null && !isClinicalMmol(point.lowMmol)) return false
        if (point.highMmol != null && !isClinicalMmol(point.highMmol)) return false
        if (point.lowMmol != null && point.highMmol != null && point.lowMmol > point.highMmol) {
            return false
        }
        val durationMs = point.durationMs
        val endTs = point.endTs
        if ((durationMs == null) != (endTs == null)) return false
        if (durationMs == null || endTs == null) return true
        if (durationMs !in 0L..ClinicalReportDatasetBuilder.MAX_TARGET_DURATION_MS) return false
        val actualDuration = try {
            Math.subtractExact(endTs, point.ts)
        } catch (_: ArithmeticException) {
            return false
        }
        if (actualDuration !in 0L..ClinicalReportDatasetBuilder.MAX_TARGET_DURATION_MS) return false
        if (kotlin.math.abs(actualDuration - durationMs) > END_TOLERANCE_MS) return false
        if (durationMs == 0L && !cancelled) return false
        return !cancelled || (durationMs == 0L && endTs == point.ts)
    }

    private fun isClinicalMmol(value: Double): Boolean =
        value.isFinite() &&
            value in ClinicalOpenAiClient.MIN_CLINICAL_MMOL..ClinicalOpenAiClient.MAX_CLINICAL_MMOL
}

internal object AlertAiCanonicalOrdering {
    val therapy = compareBy(
        ClinicalTherapyPoint::ts,
        ClinicalTherapyPoint::insulinU,
        ClinicalTherapyPoint::carbsG,
        ClinicalTherapyPoint::syntheticUam,
        ClinicalTherapyPoint::insulinEvidence,
        ClinicalTherapyPoint::contextKind
    )
    val target = compareBy(
        ClinicalTargetPoint::ts,
        ClinicalTargetPoint::lowMmol,
        ClinicalTargetPoint::highMmol,
        ClinicalTargetPoint::durationMs,
        ClinicalTargetPoint::endTs,
        ClinicalTargetPoint::cancelled
    )
    val forecast = compareBy(
        ClinicalForecastPoint::ts,
        ClinicalForecastPoint::horizonMin,
        ClinicalForecastPoint::mmol,
        ClinicalForecastPoint::lower,
        ClinicalForecastPoint::upper
    )
    val telemetry = compareBy(
        ClinicalTelemetryPoint::ts,
        ClinicalTelemetryPoint::key,
        ClinicalTelemetryPoint::value,
        ClinicalTelemetryPoint::quality,
        ClinicalTelemetryPoint::origin
    )
    val event = compareBy(
        ClinicalContextEvent::startTs,
        ClinicalContextEvent::type,
        ClinicalContextEvent::subtype,
        ClinicalContextEvent::endTs,
        ClinicalContextEvent::severity,
        ClinicalContextEvent::source,
        ClinicalContextEvent::title,
        ClinicalContextEvent::note
    )
}

internal fun canonicalRedactedAlertCause(snapshot: AlertCauseSnapshot): String? {
    val sanitized = AlertCauseSnapshotCodec.sanitize(snapshot) ?: return null
    return redactAlertCauseSnapshot(JsonParser.parseString(sanitized.canonicalJson)).toString()
}

private fun redactAlertCauseSnapshot(element: JsonElement): JsonElement = when {
    element.isJsonObject -> JsonObject().apply {
        element.asJsonObject.entrySet().forEach { (name, value) ->
            if (name !in INTERNAL_SNAPSHOT_FIELDS) {
                add(name, redactAlertCauseSnapshot(value))
            }
        }
    }
    element.isJsonArray -> JsonArray().apply {
        element.asJsonArray.forEach { add(redactAlertCauseSnapshot(it)) }
    }
    element.isJsonPrimitive && element.asJsonPrimitive.isString &&
        element.asString.contains("COPILOT_CTX", ignoreCase = true) -> JsonNull.INSTANCE
    else -> element.deepCopy()
}

private val INTERNAL_SNAPSHOT_FIELDS = setOf(
    "cycleId",
    "settingsRevision",
    "sensitivityCycleId",
    "sensitivitySettingsRevision",
    "episodeId",
    "localId",
    "provenance"
)
