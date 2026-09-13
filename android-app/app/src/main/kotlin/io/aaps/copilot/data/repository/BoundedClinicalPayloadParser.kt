package io.aaps.copilot.data.repository

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.aaps.copilot.domain.model.TherapyEventComponentTrust
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class ParsedClinicalPayload(
    val numbers: Map<String, String>,
    val booleans: Map<String, Boolean>,
    val markers: Map<String, String>,
    val presentKeys: Set<String>,
    val numericTokenKeys: Set<String>,
    val topLevelScalars: Map<String, String>,
    val duplicateSemanticKeys: Set<String>,
    val canonicalCarbId: Long?,
    val canonicalCarbRevision: String?,
    val canonicalReferenceConflict: Boolean
) {
    fun number(vararg keys: String): Double? = keys.firstNotNullOfOrNull { key ->
        numbers[normalizeClinicalKey(key)]
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf(Double::isFinite)
    }

    fun exactLong(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
        numbers[normalizeClinicalKey(key)]?.toBigDecimalOrNull()?.runCatching {
            longValueExact()
        }?.getOrNull()
    }

    fun exactPositiveJsonLong(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
        val normalizedKey = normalizeClinicalKey(key)
        if (normalizedKey == CANONICAL_CARB_ID_KEY) return@firstNotNullOfOrNull canonicalCarbId
        val raw = numbers[normalizedKey]?.takeIf { normalizedKey in numericTokenKeys }
            ?: return@firstNotNullOfOrNull null
        raw.takeIf { it.isNotEmpty() && it.first() in '1'..'9' && it.all(Char::isDigit) }
            ?.toLongOrNull()
    }

    fun boolean(vararg keys: String): Boolean? =
        keys.firstNotNullOfOrNull { booleans[normalizeClinicalKey(it)] }

    fun hasNumber(vararg keys: String): Boolean =
        keys.any { normalizeClinicalKey(it) in numbers }

    fun hasField(vararg keys: String): Boolean =
        keys.any { normalizeClinicalKey(it) in presentKeys }

    fun componentTrust(): TherapyEventComponentTrust = TherapyEventComponentTrust(
        canonicalCarbId = canonicalCarbId,
        canonicalCarbRevision = canonicalCarbRevision,
        canonicalSemanticConflict = duplicateSemanticKeys.any(CANONICAL_CARB_SEMANTIC_KEYS::contains),
        canonicalReferenceConflict = canonicalReferenceConflict,
        legacyValidityConflict = LEGACY_VALIDITY_KEY in duplicateSemanticKeys
    )

    fun scalarValues(): Map<String, String> = buildMap {
        topLevelScalars.forEach { (rawKey, value) ->
            val normalizedKey = normalizeClinicalKey(rawKey)
            if (normalizedKey != CANONICAL_CARB_ID_KEY && normalizedKey !in INTERNAL_CONFLICT_MARKER_KEYS) {
                val outputKey = CLINICAL_OUTPUT_KEYS[normalizedKey] ?: rawKey
                putIfAbsent(outputKey, value)
            }
        }
        UNAVAILABLE_COMPONENT_KEYS.forEach { key ->
            if (key in presentKeys && clinicalOutputKey(key) !in this) {
                put(clinicalOutputKey(key), UNAVAILABLE_COMPONENT_VALUE)
            }
        }
        duplicateSemanticKeys.forEach { key ->
            put(clinicalOutputKey(key), UNAVAILABLE_COMPONENT_VALUE)
        }
    }
}

internal object BoundedClinicalPayloadParser {
    const val MAX_PAYLOAD_BYTES = 16 * 1_024
    private const val MAX_DEPTH = 4
    private const val MAX_TOKENS = 160
    private const val MAX_KEY_CHARS = 80
    private const val MAX_STRING_CHARS = 512

    private val NUMBER_KEYS = setOf(
        "units", "bolusunits", "insulinunits", "insulin", "enteredinsulin", "amount",
        "grams", "carbs", "carbsgrams", "enteredcarbs", "mealcarbs",
        "absolute", "rate", "percentage",
        "aapscarbid", "aapscarbamount",
        "targetmmol", "target", "targetbottommmol", "targetlowmmol",
        "targetbottom", "targetlow", "targettopmmol", "targethighmmol",
        "targettop", "targethigh", "durationinmilliseconds", "durationms",
        "targetdurationms", "durationminutes", "durationinminutes", "duration",
        "endts", "endtimestamp", "expiresat", "targetendts", "temptargetexpiresat"
    )
    private val BOOLEAN_KEYS = setOf(
        "cancelled", "canceled", "iscancelled", "iscanceled", "isvalid", "synthetic",
        "aapscarbisvalid", "aapscarbsynthetic", "aapscarbsuperseded",
        "inferred", "recovered", "iscorrection", "blocked"
    )
    private val MARKER_KEYS = setOf(
        "synthetic", "source", "reason", "synthetictype", "note", "notes",
        "classification", "aapscarbclassification", "method", "inferred", "recovered",
        "iscorrection", "eventtype", "carbtype", "absorptiontype", "mealtype",
        "food", "product", "meal", "dish", "description", "title", "label", "comment",
        "carbsource", "enteredby", "blocked"
    )

    fun parse(raw: String): ParsedClinicalPayload? {
        if (raw.length > MAX_PAYLOAD_BYTES) return null
        if (raw.toByteArray(StandardCharsets.UTF_8).size > MAX_PAYLOAD_BYTES) return null
        return runCatching {
            val state = State(JsonReader(StringReader(raw)))
            state.readPayload()
        }.getOrNull()
    }

    private class State(private val reader: JsonReader) {
        private var tokens = 0
        private val numbers = linkedMapOf<String, String>()
        private val booleans = linkedMapOf<String, Boolean>()
        private val markers = linkedMapOf<String, String>()
        private val presentKeys = linkedSetOf<String>()
        private val numericTokenKeys = linkedSetOf<String>()
        private val topLevelScalars = linkedMapOf<String, String>()
        private val semanticOccurrences = linkedMapOf<String, Int>()
        private var canonicalCarbIdOccurrences = 0
        private var canonicalCarbIdCandidate: Long? = null
        private var canonicalCarbRevisionOccurrences = 0
        private var canonicalCarbRevisionCandidate: String? = null

        fun readPayload(): ParsedClinicalPayload {
            token()
            require(reader.peek() == JsonToken.BEGIN_OBJECT)
            reader.beginObject()
            while (reader.hasNext()) {
                token()
                val rawName = reader.nextName()
                require(rawName.length <= MAX_KEY_CHARS)
                val name = normalizeClinicalKey(rawName)
                presentKeys += name
                if (name in TRACKED_SEMANTIC_KEYS) {
                    semanticOccurrences[name] = semanticOccurrences.getOrDefault(name, 0) + 1
                }
                readTopLevelValue(rawName, name)
            }
            token()
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT)
            return ParsedClinicalPayload(
                numbers,
                booleans,
                markers,
                presentKeys,
                numericTokenKeys,
                topLevelScalars,
                semanticOccurrences.filterValues { it > 1 }.keys,
                canonicalCarbIdCandidate.takeIf { canonicalCarbIdOccurrences == 1 },
                canonicalCarbRevisionCandidate.takeIf { canonicalCarbRevisionOccurrences == 1 },
                canonicalCarbIdOccurrences > 1 || canonicalCarbRevisionOccurrences > 1
            )
        }

        private fun readTopLevelValue(rawName: String, name: String) {
            token()
            val valueToken = reader.peek()
            if (name == CANONICAL_CARB_ID_KEY) {
                canonicalCarbIdOccurrences += 1
                if (canonicalCarbIdOccurrences > 1) canonicalCarbIdCandidate = null
            }
            if (name == CANONICAL_CARB_REVISION_KEY) {
                canonicalCarbRevisionOccurrences += 1
                if (canonicalCarbRevisionOccurrences > 1) canonicalCarbRevisionCandidate = null
            }
            when (valueToken) {
                JsonToken.STRING, JsonToken.NUMBER -> {
                    val value = reader.nextString()
                    require(value.length <= MAX_STRING_CHARS)
                    topLevelScalars.putIfAbsent(rawName, value)
                    if (name in NUMBER_KEYS && name !in numbers) {
                        numbers[name] = value
                        if (valueToken == JsonToken.NUMBER) numericTokenKeys += name
                    }
                    if (name == CANONICAL_CARB_ID_KEY && canonicalCarbIdOccurrences == 1) {
                        canonicalCarbIdCandidate = value
                            .takeIf {
                                valueToken == JsonToken.NUMBER &&
                                    it.isNotEmpty() && it.first() in '1'..'9' && it.all(Char::isDigit)
                            }
                            ?.toLongOrNull()
                    }
                    if (name == CANONICAL_CARB_REVISION_KEY && canonicalCarbRevisionOccurrences == 1) {
                        canonicalCarbRevisionCandidate = value.trim().takeIf { it.isNotEmpty() }
                    }
                    if (name in BOOLEAN_KEYS) parseBoolean(value)?.let { booleans.putIfAbsent(name, it) }
                    if (name in MARKER_KEYS) markers.putIfAbsent(name, value)
                }
                JsonToken.BOOLEAN -> {
                    val value = reader.nextBoolean()
                    topLevelScalars.putIfAbsent(rawName, value.toString())
                    if (name in BOOLEAN_KEYS) booleans.putIfAbsent(name, value)
                    if (name in MARKER_KEYS) markers.putIfAbsent(name, value.toString())
                }
                JsonToken.NULL -> reader.nextNull()
                JsonToken.BEGIN_OBJECT, JsonToken.BEGIN_ARRAY -> skipValue(depth = 1)
                else -> error("Unsupported JSON token")
            }
        }

        private fun skipValue(depth: Int) {
            require(depth <= MAX_DEPTH)
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        token()
                        require(reader.nextName().length <= MAX_KEY_CHARS)
                        token()
                        skipValue(depth + 1)
                    }
                    token()
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        token()
                        skipValue(depth + 1)
                    }
                    token()
                    reader.endArray()
                }
                JsonToken.STRING, JsonToken.NUMBER -> require(reader.nextString().length <= MAX_STRING_CHARS)
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> reader.nextNull()
                else -> error("Unsupported JSON token")
            }
        }

        private fun token() {
            tokens += 1
            require(tokens <= MAX_TOKENS)
        }
    }

    private fun parseBoolean(raw: String): Boolean? = when (raw.trim().lowercase(Locale.US)) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}

internal fun normalizeClinicalKey(value: String): String =
    value.lowercase(Locale.US).filter(Char::isLetterOrDigit)

private const val CANONICAL_CARB_ID_KEY = "aapscarbid"
private const val CANONICAL_CARB_REVISION_KEY = "aapsrevisionid"
private const val LEGACY_VALIDITY_KEY = "isvalid"
private const val UNAVAILABLE_COMPONENT_VALUE = "__unavailable_scalar__"
private val CANONICAL_CARB_SEMANTIC_KEYS = setOf(
    "aapscarbamount",
    "aapscarbisvalid",
    "aapscarbclassification",
    "aapscarbsynthetic",
    "aapscarbsuperseded"
)
private val TRACKED_SEMANTIC_KEYS = CANONICAL_CARB_SEMANTIC_KEYS + LEGACY_VALIDITY_KEY
private val INTERNAL_CONFLICT_MARKER_KEYS = setOf(
    normalizeClinicalKey("__copilotCanonicalCarbSemanticConflict"),
    normalizeClinicalKey("__copilotLegacyValidityConflict")
)
private val UNAVAILABLE_COMPONENT_KEYS = setOf(
    "isvalid",
    "aapscarbamount",
    "aapscarbisvalid",
    "aapscarbclassification",
    "aapscarbsynthetic",
    "aapscarbsuperseded"
)
private val CLINICAL_OUTPUT_KEYS = mapOf(
    "bolusunits" to "bolusUnits",
    "insulinunits" to "insulinUnits",
    "enteredinsulin" to "enteredInsulin",
    "carbsgrams" to "carbsGrams",
    "enteredcarbs" to "enteredCarbs",
    "mealcarbs" to "mealCarbs",
    "aapscarbamount" to "aapsCarbAmount",
    "aapscarbisvalid" to "aapsCarbIsValid",
    "aapscarbclassification" to "aapsCarbClassification",
    "aapscarbsynthetic" to "aapsCarbSynthetic",
    "aapscarbsuperseded" to "aapsCarbSuperseded",
    "isvalid" to "isValid",
    "iscorrection" to "isCorrection",
    "synthetictype" to "syntheticType",
    "eventtype" to "eventType",
    "carbtype" to "carbType",
    "absorptiontype" to "absorptionType",
    "mealtype" to "mealType",
    "carbsource" to "carbSource",
    "enteredby" to "enteredBy",
    "targetmmol" to "targetMmol",
    "targetbottommmol" to "targetBottomMmol",
    "targetlowmmol" to "targetLowMmol",
    "targetbottom" to "targetBottom",
    "targetlow" to "targetLow",
    "targettopmmol" to "targetTopMmol",
    "targethighmmol" to "targetHighMmol",
    "targettop" to "targetTop",
    "targethigh" to "targetHigh",
    "durationinmilliseconds" to "durationInMilliseconds",
    "durationms" to "durationMs",
    "targetdurationms" to "targetDurationMs",
    "durationminutes" to "durationMinutes",
    "durationinminutes" to "durationInMinutes",
    "endts" to "endTs",
    "endtimestamp" to "endTimestamp",
    "expiresat" to "expiresAt",
    "targetendts" to "targetEndTs",
    "temptargetexpiresat" to "tempTargetExpiresAt"
)

private fun clinicalOutputKey(normalizedKey: String): String =
    CLINICAL_OUTPUT_KEYS[normalizedKey] ?: normalizedKey
