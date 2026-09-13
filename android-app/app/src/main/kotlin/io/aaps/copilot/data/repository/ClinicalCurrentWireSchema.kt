package io.aaps.copilot.data.repository

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

internal object ClinicalCurrentWireSchema {
    private data class NumericField(
        val key: String,
        val value: (ClinicalCurrentSnapshot) -> Double?,
        val range: ClosedFloatingPointRange<Double>? = null,
        val allowed: Set<Double>? = null
    ) {
        fun isValid(value: Double?): Boolean = value == null ||
            value.isFinite() &&
            (range == null || value in range) &&
            (allowed == null || value in allowed)
    }

    private data class OptionalField(
        val key: String,
        val value: (ClinicalCurrentSnapshot) -> JsonPrimitive?
    )

    private val requiredNumericPrefixFields = listOf(
        NumericField("g", ClinicalCurrentSnapshot::rawGlucoseMmol, 1.0..40.0),
        NumericField("gc", ClinicalCurrentSnapshot::calibratedGlucoseMmol, 1.0..40.0),
        NumericField("age", ClinicalCurrentSnapshot::glucoseSampleAgeMinutes, 0.0..1_440.0),
        NumericField("p30", ClinicalCurrentSnapshot::prediction30mMmol, 1.0..40.0),
        NumericField("p30Age", ClinicalCurrentSnapshot::prediction30mAgeMinutes, 0.0..15.0),
        NumericField("iob", ClinicalCurrentSnapshot::effectiveIobUnits, -30.0..30.0),
        NumericField("cob", ClinicalCurrentSnapshot::effectiveCobGrams, 0.0..500.0),
        NumericField("isf", ClinicalCurrentSnapshot::selectedIsfMmolPerUnit, 0.2..18.0),
        NumericField(
            "isfSrc",
            ClinicalCurrentSnapshot::selectedIsfSourceCode,
            allowed = setOf(0.0, 1.0, 2.0, 3.0)
        ),
        NumericField("cr", ClinicalCurrentSnapshot::selectedCrGramsPerUnit, 2.0..60.0),
        NumericField(
            "crSrc",
            ClinicalCurrentSnapshot::selectedCrSourceCode,
            allowed = setOf(0.0, 1.0, 2.0, 3.0)
        )
    )
    private val requiredNumericSuffixFields = listOf(
        NumericField("uam", ClinicalCurrentSnapshot::uamActiveFlag, allowed = setOf(0.0, 1.0)),
        NumericField("uamCarbs", ClinicalCurrentSnapshot::uamEquivalentCarbsGrams, 0.0..500.0),
        NumericField("uamConf", ClinicalCurrentSnapshot::uamConfidence, 0.0..1.0),
        NumericField("sensorQ", ClinicalCurrentSnapshot::sensorQualityScore, 0.0..1.0),
        NumericField(
            "sensorBlocked",
            ClinicalCurrentSnapshot::sensorBlockedFlag,
            allowed = setOf(0.0, 1.0)
        ),
        NumericField("sensorAge", ClinicalCurrentSnapshot::sensorAgeHours, 0.0..720.0),
        NumericField("sensorLag", ClinicalCurrentSnapshot::sensorLagMinutes, 0.0..60.0),
        NumericField("activity", ClinicalCurrentSnapshot::activityRatio, 0.2..3.0),
        NumericField("steps", ClinicalCurrentSnapshot::stepsCount, 0.0..150_000.0),
        NumericField("targetLow", ClinicalCurrentSnapshot::activeTargetLowMmol, 2.2..15.0),
        NumericField("targetHigh", ClinicalCurrentSnapshot::activeTargetHighMmol, 2.2..15.0)
    )
    private val requiredNumericFields = requiredNumericPrefixFields + requiredNumericSuffixFields
    private val requiredKeys = requiredNumericFields.mapTo(linkedSetOf(), NumericField::key)
    private val optionalFields = listOf(
        OptionalField(SENSITIVITY_REVISION_KEY) { snapshot ->
            snapshot.sensitivitySettingsRevision?.let(::JsonPrimitive)
        },
        OptionalField(ISF_PROVENANCE_KEY) { snapshot ->
            snapshot.selectedIsfSource?.let(::JsonPrimitive)
        },
        OptionalField(CR_PROVENANCE_KEY) { snapshot ->
            snapshot.selectedCrSource?.let(::JsonPrimitive)
        }
    )
    private val optionalKeys = optionalFields.mapTo(linkedSetOf(), OptionalField::key)
    private val provenanceCodes = mapOf(
        "AAPS" to 1.0,
        "EVIDENCE_BLEND" to 2.0,
        "COPILOT_NATIVE" to 3.0
    )

    fun encode(snapshot: ClinicalCurrentSnapshot): JsonObject {
        if (!isValid(snapshot)) throw ClinicalOpenAiException.InvalidInput()
        return JsonObject().apply {
            addNumericFields(requiredNumericPrefixFields, snapshot)
            optionalFields.forEach { field -> field.value(snapshot)?.let { add(field.key, it) } }
            addNumericFields(requiredNumericSuffixFields, snapshot)
        }
    }

    fun isValid(snapshot: ClinicalCurrentSnapshot): Boolean =
        requiredNumericFields.all { field -> field.isValid(field.value(snapshot)) } &&
            (snapshot.prediction30mMmol == null) == (snapshot.prediction30mAgeMinutes == null) &&
            (snapshot.activeTargetLowMmol == null || snapshot.activeTargetHighMmol == null ||
                snapshot.activeTargetLowMmol <= snapshot.activeTargetHighMmol) &&
            snapshot.sensitivitySettingsRevision?.let { it >= 0L } != false &&
            validProvenance(snapshot.selectedIsfSourceCode, snapshot.selectedIsfSource) &&
            validProvenance(snapshot.selectedCrSourceCode, snapshot.selectedCrSource)

    fun isValidWireObject(current: JsonObject): Boolean {
        val keys = current.keySet().toSet()
        if (!keys.containsAll(requiredKeys) || !keys.all { it in requiredKeys || it in optionalKeys }) {
            return false
        }
        if (!requiredNumericFields.all { field ->
                val element = current.get(field.key)
                if (element.isJsonNull) {
                    true
                } else {
                    val number = element.finiteNumberOrNull()
                    number != null && field.isValid(number)
                }
            }
        ) return false
        val revision = current.get(SENSITIVITY_REVISION_KEY)
        if (revision != null && revision.nonNegativeLongOrNull() == null) return false
        val isfProvenance = current.optionalString(ISF_PROVENANCE_KEY)
            ?: if (current.has(ISF_PROVENANCE_KEY)) return false else null
        val crProvenance = current.optionalString(CR_PROVENANCE_KEY)
            ?: if (current.has(CR_PROVENANCE_KEY)) return false else null
        val prediction = current.get("p30").finiteNumberOrNull()
        val predictionAge = current.get("p30Age").finiteNumberOrNull()
        val targetLow = current.get("targetLow").finiteNumberOrNull()
        val targetHigh = current.get("targetHigh").finiteNumberOrNull()
        return (prediction == null) == (predictionAge == null) &&
            (targetLow == null || targetHigh == null || targetLow <= targetHigh) &&
            validProvenance(current.get("isfSrc").finiteNumberOrNull(), isfProvenance) &&
            validProvenance(current.get("crSrc").finiteNumberOrNull(), crProvenance)
    }

    private fun validProvenance(code: Double?, provenance: String?): Boolean {
        if (provenance == null) return true
        if (provenance.length > MAX_PROVENANCE_CHARS) return false
        val expectedCode = provenanceCodes[provenance.trim().uppercase(Locale.US)]
            ?: return false
        return expectedCode == code
    }

    private fun JsonObject.addNumericFields(
        fields: List<NumericField>,
        snapshot: ClinicalCurrentSnapshot
    ) {
        fields.forEach { field ->
            add(
                field.key,
                field.value(snapshot)
                    ?.let(::canonicalDouble)
                    ?.let(::JsonPrimitive)
                    ?: JsonNull.INSTANCE
            )
        }
    }

    private fun JsonObject.optionalString(key: String): String? {
        val value = get(key) ?: return null
        val primitive = value.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive ?: return null
        return primitive.takeIf(JsonPrimitive::isString)?.asString
    }

    private fun JsonElement?.finiteNumberOrNull(): Double? {
        if (this == null || isJsonNull || !isJsonPrimitive || !asJsonPrimitive.isNumber) return null
        return runCatching { asDouble }.getOrNull()?.takeIf(Double::isFinite)
    }

    private fun JsonElement.nonNegativeLongOrNull(): Long? {
        if (!isJsonPrimitive || !asJsonPrimitive.isNumber) return null
        return runCatching { BigDecimal(asString).longValueExact() }
            .getOrNull()
            ?.takeIf { it >= 0L }
    }

    private fun canonicalDouble(value: Double): Double = BigDecimal.valueOf(value)
        .setScale(3, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toDouble()

    private const val MAX_PROVENANCE_CHARS = 32
    private const val SENSITIVITY_REVISION_KEY = "sensitivityRev"
    private const val ISF_PROVENANCE_KEY = "isfProvenance"
    private const val CR_PROVENANCE_KEY = "crProvenance"
}
