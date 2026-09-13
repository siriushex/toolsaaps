package io.aaps.copilot.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.aaps.copilot.domain.alerts.AlertCauseDirection
import io.aaps.copilot.domain.alerts.AlertCauseSnapshot
import io.aaps.copilot.domain.alerts.AlertCauseSnapshotCodec
import io.aaps.copilot.domain.model.TherapyComponentPolicy
import java.security.MessageDigest
import okio.ByteString

internal object AlertAiContextValidator {
    private const val DAY_MS = 24L * 60L * 60L * 1_000L
    private const val FOURTEEN_DAYS_MS = 14L * DAY_MS
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val SAFE_STAGE = Regex("^[A-Za-z0-9_]{1,64}$")
    private val ROOT_KEYS = listOf(
        "schemaVersion",
        "generatedAt",
        "stage",
        "direction",
        "localCause",
        "detail24h",
        "aggregate14dFromTs",
        "aggregate14dThroughTsExclusive",
        "daily14d",
        "events14d"
    )
    private val DETAIL_KEYS = listOf(
        "fromTs",
        "throughTsExclusive",
        "rawGlucose",
        "calibratedGlucose",
        "therapy",
        "targets",
        "forecasts",
        "telemetry"
    )
    private val DAILY_KEYS = listOf(
        "day",
        "glucoseCount",
        "meanGlucose",
        "minimumGlucose",
        "maximumGlucose",
        "insulinUnits",
        "enteredCarbsGrams",
        "uamCarbsGrams"
    )
    private val EVENT_KEYS = listOf(
        "type",
        "subtype",
        "startTs",
        "endTs",
        "severity",
        "source",
        "title",
        "note"
    )

    fun isValid(context: AlertAiCanonicalContext): Boolean = try {
        validate(context, trigger = null, requireBuilderIssued = true)
        true
    } catch (_: Exception) {
        false
    }

    fun isValid(
        context: AlertAiCanonicalContext,
        trigger: AlertAiAnalysisTrigger
    ): Boolean = try {
        validate(context, trigger, requireBuilderIssued = true)
        true
    } catch (_: Exception) {
        false
    }

    internal fun isStructurallyValid(
        canonicalJson: String,
        canonicalBytes: ByteString,
        sha256: String,
        rowCount: Int
    ): Boolean = try {
        validate(
            AlertAiCanonicalContext(canonicalJson, canonicalBytes, sha256, rowCount),
            trigger = null,
            requireBuilderIssued = false
        )
        true
    } catch (_: Exception) {
        false
    }

    private fun validate(
        context: AlertAiCanonicalContext,
        trigger: AlertAiAnalysisTrigger?,
        requireBuilderIssued: Boolean
    ) {
        if (requireBuilderIssued) require(context.isBuilderIssued())
        require(context.canonicalBytes.size <= AlertAiContextBuilder.MAX_CONTEXT_BYTES)
        require(context.rowCount in 0..AlertAiContextBuilder.MAX_CONTEXT_ROWS)
        require(SHA256.matches(context.sha256))
        val bytes = context.canonicalBytes.toByteArray()
        require(context.canonicalJson.toByteArray(Charsets.UTF_8).contentEquals(bytes))
        require(sha256(bytes) == context.sha256)

        val root = JsonParser.parseString(context.canonicalJson).requiredObject()
        require(root.keysInOrder() == ROOT_KEYS)
        require(root.requiredInt("schemaVersion") == 1)
        val generatedAt = root.requiredLong("generatedAt").also { require(it > 0L) }
        val stage = root.requiredString("stage").also { require(SAFE_STAGE.matches(it)) }
        val direction = root.requiredString("direction")
            .also { require(it in AlertCauseDirection.entries.map { entry -> entry.name }) }
        validateLocalCause(root.get("localCause"))
        if (trigger != null) {
            require(generatedAt == trigger.requestedAt)
            require(stage == trigger.stage)
            require(direction == trigger.direction.name)
            require(
                root.get("localCause").toString() ==
                    canonicalRedactedAlertCause(trigger.localCauseSnapshot)
            )
        }

        val detailFrom = subtractExact(generatedAt, DAY_MS)
        val aggregateFrom = subtractExact(generatedAt, FOURTEEN_DAYS_MS)
        val detail = root.get("detail24h").requiredObject()
        require(detail.keysInOrder() == DETAIL_KEYS)
        require(detail.requiredLong("fromTs") == detailFrom)
        require(detail.requiredLong("throughTsExclusive") == generatedAt)
        require(root.requiredLong("aggregate14dFromTs") == aggregateFrom)
        require(root.requiredLong("aggregate14dThroughTsExclusive") == generatedAt)

        var rows = 0
        rows = addRows(rows, validateGlucose(detail.requiredArray("rawGlucose"), detailFrom, generatedAt))
        rows = addRows(
            rows,
            validateGlucose(detail.requiredArray("calibratedGlucose"), detailFrom, generatedAt)
        )
        rows = addRows(
            rows,
            validateTherapy(detail.requiredArray("therapy"), detailFrom, generatedAt)
        )
        rows = addRows(rows, validateTargets(detail.requiredArray("targets"), detailFrom, generatedAt))
        rows = addRows(rows, validateForecasts(detail.requiredArray("forecasts"), detailFrom, generatedAt))
        rows = addRows(rows, validateTelemetry(detail.requiredArray("telemetry"), detailFrom, generatedAt))

        val daily = root.requiredArray("daily14d")
        require(daily.size() == 14)
        daily.forEachIndexed { index, element -> validateDaily(element, index) }
        rows = addRows(rows, daily.size())

        val events = root.requiredArray("events14d")
        var previousEvent: ClinicalContextEvent? = null
        events.forEach {
            val event = validateEvent(it, aggregateFrom, generatedAt)
            require(
                previousEvent == null ||
                    AlertAiCanonicalOrdering.event.compare(previousEvent, event) < 0
            )
            previousEvent = event
        }
        rows = addRows(rows, events.size())
        require(rows == context.rowCount)
        require(root.toString() == context.canonicalJson)
    }

    private fun validateLocalCause(element: JsonElement?) {
        val source = element.requiredObject()
        val sanitized = AlertCauseSnapshotCodec.sanitize(AlertCauseSnapshot(source.toString()))
            ?: throw IllegalArgumentException()
        val canonical = JsonParser.parseString(sanitized.canonicalJson).requiredObject()
        require(source.toString() == canonical.toString())
    }

    private fun validateGlucose(rows: JsonArray, fromTs: Long, throughTs: Long): Int {
        var previousTs: Long? = null
        rows.forEach { element ->
            val row = element.requiredArray(2)
            val ts = row[0].requiredLong().also { require(it in fromTs until throughTs) }
            require(previousTs == null || ts > previousTs!!)
            previousTs = ts
            row[1].requiredClinicalMmol()
        }
        return rows.size()
    }

    private fun validateTherapy(rows: JsonArray, fromTs: Long, throughTs: Long): Int {
        var previous: ClinicalTherapyPoint? = null
        rows.forEach { element ->
            val row = element.requiredArray(6)
            val point = ClinicalTherapyPoint(
                ts = row[0].requiredLong().also { require(it in fromTs until throughTs) },
                insulinU = row[1].optionalFiniteDouble(),
                carbsG = row[2].optionalFiniteDouble(),
                syntheticUam = row[3].requiredBoolean(),
                insulinEvidence = row[4].optionalEnum(
                    ClinicalInsulinEvidence.entries.map { it.name }.toSet()
                )?.let(ClinicalInsulinEvidence::valueOf),
                contextKind = row[5].optionalEnum(
                    ClinicalTherapyContextKind.entries.map { it.name }.toSet()
                )?.let(ClinicalTherapyContextKind::valueOf)
            )
            require(
                TherapyComponentPolicy.isValidCanonicalRow(
                    insulinU = point.insulinU,
                    carbsG = point.carbsG,
                    syntheticUam = point.syntheticUam,
                    hasInsulinEvidence = point.insulinEvidence != null,
                    hasContext = point.contextKind != null
                )
            )
            require(previous == null || AlertAiCanonicalOrdering.therapy.compare(previous, point) < 0)
            previous = point
        }
        return rows.size()
    }

    private fun validateTargets(rows: JsonArray, fromTs: Long, throughTs: Long): Int {
        var previous: ClinicalTargetPoint? = null
        rows.forEach { element ->
            val row = element.requiredArray(6)
            val ts = row[0].requiredLong().also { require(it in fromTs until throughTs) }
            val low = row[1].optionalClinicalMmol()
            val high = row[2].optionalClinicalMmol()
            val durationMs = row[3].optionalLong()
            val endTs = row[4].optionalLong()
            val cancelled = row[5].optionalBoolean()
            val point = ClinicalTargetPoint(ts, low, high, durationMs, endTs, cancelled)
            require(AlertAiCanonicalTargetPolicy.isValid(point))
            require(previous == null || AlertAiCanonicalOrdering.target.compare(previous, point) < 0)
            previous = point
        }
        return rows.size()
    }

    private fun validateForecasts(rows: JsonArray, fromTs: Long, throughTs: Long): Int {
        var previous: ClinicalForecastPoint? = null
        rows.forEach { element ->
            val row = element.requiredArray(5)
            val point = ClinicalForecastPoint(
                ts = row[0].requiredLong().also { require(it in fromTs until throughTs) },
                horizonMin = row[1].requiredInt().also { require(it in 0..180) },
                mmol = row[2].requiredClinicalMmol(),
                lower = row[3].requiredClinicalMmol(),
                upper = row[4].requiredClinicalMmol()
            )
            require(point.lower <= point.mmol && point.mmol <= point.upper)
            require(previous == null || AlertAiCanonicalOrdering.forecast.compare(previous, point) < 0)
            previous = point
        }
        return rows.size()
    }

    private fun validateTelemetry(rows: JsonArray, fromTs: Long, throughTs: Long): Int {
        var previous: ClinicalTelemetryPoint? = null
        rows.forEach { element ->
            val row = element.requiredArray(5)
            val point = ClinicalTelemetryPoint(
                ts = row[0].requiredLong().also { require(it in fromTs until throughTs) },
                key = row[1].requiredString(1, 64),
                value = row[2].requiredFiniteDouble(),
                quality = row[3].requiredString(1, 24),
                origin = ClinicalTelemetryOrigin.valueOf(row[4].requiredString())
            )
            require(ClinicalReportDatasetBuilder.isValidCanonicalTelemetryPoint(point))
            require(previous == null || AlertAiCanonicalOrdering.telemetry.compare(previous, point) < 0)
            previous = point
        }
        return rows.size()
    }

    private fun validateDaily(element: JsonElement, expectedDay: Int) {
        val day = element.requiredObject()
        require(day.keysInOrder() == DAILY_KEYS)
        require(day.requiredInt("day") == expectedDay)
        require(day.requiredInt("glucoseCount") in 0..AlertAiContextBuilder.MAX_CONTEXT_ROWS)
        val mean = day.get("meanGlucose").optionalClinicalMmol()
        val minimum = day.get("minimumGlucose").optionalClinicalMmol()
        val maximum = day.get("maximumGlucose").optionalClinicalMmol()
        require((mean == null) == (minimum == null) && (mean == null) == (maximum == null))
        if (mean != null && minimum != null && maximum != null) {
            require(minimum <= mean && mean <= maximum)
        }
        require(day.requiredBoundedDouble("insulinUnits", ClinicalOpenAiClient.MAX_RECORDED_INSULIN_UNITS) >= 0.0)
        require(day.requiredBoundedDouble("enteredCarbsGrams", ClinicalOpenAiClient.MAX_RECORDED_CARBS_GRAMS) >= 0.0)
        require(day.requiredBoundedDouble("uamCarbsGrams", ClinicalOpenAiClient.MAX_RECORDED_CARBS_GRAMS) >= 0.0)
    }

    private fun validateEvent(
        element: JsonElement,
        fromTs: Long,
        throughTs: Long
    ): ClinicalContextEvent {
        val event = element.requiredObject()
        require(event.keysInOrder() == EVENT_KEYS)
        val type = event.requiredString("type", 1, 40)
        val subtype = event.optionalString("subtype", 40)
        val startTs = event.requiredLong("startTs")
        require(startTs in 0 until throughTs && startTs < throughTs)
        val endTs = event.get("endTs").optionalLong()
        require(endTs == null || endTs > startTs)
        require((endTs ?: startTs) >= fromTs)
        val severity = event.requiredString("severity", 1, 24)
        val source = event.requiredString("source").also { require(it in EVENT_SOURCES) }
        val title = event.optionalString("title", 60)?.also(::requireSanitizedEventText)
        val note = event.optionalString("note", 500)?.also(::requireSanitizedEventText)
        return ClinicalContextEvent(type, subtype, startTs, endTs, severity, source, title, note)
    }

    private fun requireSanitizedEventText(value: String) {
        require(CopilotContextNoteMarker.stripProtectedMarkers(value) == value)
        require(!value.contains(CopilotContextNoteMarker.PREFIX, ignoreCase = true))
    }

    private fun JsonElement?.requiredObject(): JsonObject =
        requireNotNull(this).also { require(it.isJsonObject) }.asJsonObject

    private fun JsonElement.requiredArray(expectedSize: Int? = null): JsonArray {
        require(isJsonArray)
        return asJsonArray.also { array -> expectedSize?.let { require(array.size() == it) } }
    }

    private fun JsonObject.keysInOrder(): List<String> = entrySet().map { it.key }
    private fun JsonObject.requiredArray(name: String): JsonArray = get(name).requiredArray()
    private fun JsonObject.requiredString(name: String, min: Int = 1, max: Int = Int.MAX_VALUE): String =
        get(name).requiredString(min, max)

    private fun JsonObject.optionalString(name: String, max: Int): String? =
        get(name).optionalString(max)

    private fun JsonObject.requiredLong(name: String): Long = get(name).requiredLong()
    private fun JsonObject.requiredInt(name: String): Int = get(name).requiredInt()
    private fun JsonObject.requiredBoundedDouble(name: String, maximum: Double): Double =
        get(name).requiredFiniteDouble().also { require(it in 0.0..maximum) }

    private fun JsonElement.requiredString(min: Int = 1, max: Int = Int.MAX_VALUE): String {
        require(isJsonPrimitive && asJsonPrimitive.isString)
        return asString.also { require(it.length in min..max) }
    }

    private fun JsonElement?.optionalString(max: Int): String? {
        if (this == null || isJsonNull) return null
        return requiredString(1, max)
    }

    private fun JsonElement.requiredLong(): Long {
        val primitive = requiredNumberPrimitive()
        return primitive.asString.toLongOrNull() ?: throw IllegalArgumentException()
    }

    private fun JsonElement?.optionalLong(): Long? {
        if (this == null || isJsonNull) return null
        return requiredLong()
    }

    private fun JsonElement.requiredInt(): Int {
        val primitive = requiredNumberPrimitive()
        return primitive.asString.toIntOrNull() ?: throw IllegalArgumentException()
    }

    private fun JsonElement.requiredFiniteDouble(): Double {
        val value = requiredNumberPrimitive().asString.toDoubleOrNull() ?: throw IllegalArgumentException()
        require(value.isFinite())
        return value
    }

    private fun JsonElement?.optionalFiniteDouble(): Double? {
        if (this == null || isJsonNull) return null
        return requiredFiniteDouble()
    }

    private fun JsonElement.requiredClinicalMmol(): Double =
        requiredFiniteDouble().also {
            require(it in ClinicalOpenAiClient.MIN_CLINICAL_MMOL..ClinicalOpenAiClient.MAX_CLINICAL_MMOL)
        }

    private fun JsonElement?.optionalClinicalMmol(): Double? {
        if (this == null || isJsonNull) return null
        return requiredClinicalMmol()
    }

    private fun JsonElement.requiredBoolean(): Boolean {
        require(isJsonPrimitive && asJsonPrimitive.isBoolean)
        return asBoolean
    }

    private fun JsonElement?.optionalBoolean(): Boolean? {
        if (this == null || isJsonNull) return null
        return requiredBoolean()
    }

    private fun JsonElement?.optionalEnum(allowed: Set<String>): String? {
        if (this == null || isJsonNull) return null
        return requiredString().also { require(it in allowed) }
    }

    private fun JsonElement.requiredNumberPrimitive(): JsonPrimitive {
        require(isJsonPrimitive && asJsonPrimitive.isNumber)
        return asJsonPrimitive
    }

    private fun addRows(left: Int, right: Int): Int = Math.addExact(left, right).also {
        require(it <= AlertAiContextBuilder.MAX_CONTEXT_ROWS)
    }

    private fun subtractExact(left: Long, right: Long): Long = Math.subtractExact(left, right)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private val EVENT_SOURCES = setOf("USER", "AAPS", "AUTOMATIC")
}
