package io.aaps.copilot.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.aaps.copilot.domain.alerts.AlertCauseCode
import io.aaps.copilot.domain.alerts.CauseConfidence
import io.aaps.copilot.util.ordinaryExceptionOrNull
import java.io.StringReader

class AlertAiPresentation(
    val primaryCause: AlertCauseCode,
    val confidence: CauseConfidence,
    val evidence: List<AlertEvidenceKind>,
    val advice: AlertCauseCode,
    internal val canonicalEvidenceCodes: List<String> = emptyList()
) {
    override fun equals(other: Any?): Boolean = other is AlertAiPresentation &&
        primaryCause == other.primaryCause &&
        confidence == other.confidence &&
        evidence == other.evidence &&
        advice == other.advice &&
        canonicalEvidenceCodes == other.canonicalEvidenceCodes

    override fun hashCode(): Int {
        var result = primaryCause.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + evidence.hashCode()
        result = 31 * result + advice.hashCode()
        return 31 * result + canonicalEvidenceCodes.hashCode()
    }

    override fun toString(): String =
        "AlertAiPresentation(primaryCause=$primaryCause, confidence=$confidence, " +
            "evidence=$evidence, advice=$advice)"
}

internal object AlertAiTypedPresentationParser {
    private const val MAX_JSON_CHARS = 8_192
    private const val MAX_EVIDENCE_CODES = 8
    private val requiredKeys = setOf(
        "schemaVersion",
        "primaryCauseCode",
        "confidence",
        "evidenceCodes",
        "adviceCode"
    )

    fun parse(json: String): AlertAiPresentation? = ordinaryExceptionOrNull {
        if (json.length > MAX_JSON_CHARS) return@ordinaryExceptionOrNull null
        JsonReader(StringReader(json)).use { reader ->
            reader.strictness = Strictness.STRICT
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return@ordinaryExceptionOrNull null
            reader.beginObject()
            val seen = mutableSetOf<String>()
            var primaryCause: AlertCauseCode? = null
            var confidence: CauseConfidence? = null
            var evidence: List<AiEvidenceCode>? = null
            var advice: AlertCauseCode? = null

            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name !in requiredKeys || !seen.add(name)) return@ordinaryExceptionOrNull null
                when (name) {
                    "schemaVersion" -> {
                        if (reader.peek() != JsonToken.NUMBER) return@ordinaryExceptionOrNull null
                        if (reader.nextString() != "1") return@ordinaryExceptionOrNull null
                    }
                    "primaryCauseCode" -> {
                        primaryCause = reader.nextEnumOrNull<AlertCauseCode>()
                            ?: return@ordinaryExceptionOrNull null
                    }
                    "confidence" -> {
                        confidence = reader.nextEnumOrNull<CauseConfidence>()
                            ?: return@ordinaryExceptionOrNull null
                    }
                    "evidenceCodes" -> {
                        evidence = reader.nextEvidenceKindsOrNull()
                            ?: return@ordinaryExceptionOrNull null
                    }
                    "adviceCode" -> {
                        advice = reader.nextEnumOrNull<AlertCauseCode>()
                            ?: return@ordinaryExceptionOrNull null
                    }
                }
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT || seen != requiredKeys) {
                return@ordinaryExceptionOrNull null
            }
            val evidenceResult = evidence ?: return@ordinaryExceptionOrNull null
            AlertAiPresentation(
                primaryCause = primaryCause ?: return@ordinaryExceptionOrNull null,
                confidence = confidence ?: return@ordinaryExceptionOrNull null,
                evidence = evidenceResult.map(AiEvidenceCode::kind).distinct(),
                advice = advice ?: return@ordinaryExceptionOrNull null,
                canonicalEvidenceCodes = evidenceResult.map(AiEvidenceCode::code).distinct()
            )
        }
    }

    fun encodeCanonical(value: AlertAiPresentation): String? = ordinaryExceptionOrNull {
        val evidenceCodes = value.canonicalEvidenceCodes
        if (evidenceCodes.size > MAX_EVIDENCE_CODES ||
            evidenceCodes.any { it !in AI_EVIDENCE_KIND_BY_CODE }
        ) {
            return@ordinaryExceptionOrNull null
        }
        JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("primaryCauseCode", value.primaryCause.name)
            addProperty("confidence", value.confidence.name)
            add("evidenceCodes", JsonArray().apply { evidenceCodes.forEach(::add) })
            addProperty("adviceCode", value.advice.name)
        }.toString()
    }

    fun allowedEvidenceCodes(): List<String> = AI_EVIDENCE_KIND_BY_CODE.keys.sorted()

    private inline fun <reified T : Enum<T>> JsonReader.nextEnumOrNull(): T? {
        if (peek() != JsonToken.STRING) return null
        val value = nextString()
        return enumValues<T>().firstOrNull { it.name == value }
    }

    private fun JsonReader.nextEvidenceKindsOrNull(): List<AiEvidenceCode>? {
        if (peek() != JsonToken.BEGIN_ARRAY) return null
        beginArray()
        val values = mutableListOf<AiEvidenceCode>()
        var count = 0
        while (hasNext()) {
            if (++count > MAX_EVIDENCE_CODES || peek() != JsonToken.STRING) return null
            val code = nextString()
            values += AiEvidenceCode(
                code = code,
                kind = AI_EVIDENCE_KIND_BY_CODE[code] ?: return null
            )
        }
        endArray()
        return values.distinct()
    }
}

private data class AiEvidenceCode(val code: String, val kind: AlertEvidenceKind)

private val AI_EVIDENCE_KIND_BY_CODE = mapOf(
    "SENSOR_BLOCKED" to AlertEvidenceKind.SENSOR_QUALITY,
    "SENSOR_FALSE_LOW" to AlertEvidenceKind.SENSOR_QUALITY,
    "SENSOR_TRUST_POOR" to AlertEvidenceKind.SENSOR_QUALITY,
    "UAM_ACTIVE" to AlertEvidenceKind.UAM,
    "UAM_CONTROL_ACTIVE" to AlertEvidenceKind.UAM,
    "UAM_POSITIVE_RESIDUAL" to AlertEvidenceKind.UAM,
    "DELIVERY_SUSPECTED_NONRESPONSE" to AlertEvidenceKind.DELIVERY,
    "INSULIN_ACTIVITY_LOW" to AlertEvidenceKind.INSULIN,
    "POSITIVE_IOB_LOW" to AlertEvidenceKind.INSULIN,
    "TARGET_AUTO_DELTA_DOWN" to AlertEvidenceKind.TARGET,
    "TARGET_AUTO_DELTA_UP" to AlertEvidenceKind.TARGET,
    "CIRCADIAN_DELTA_DOWN" to AlertEvidenceKind.CIRCADIAN,
    "CIRCADIAN_DELTA_UP" to AlertEvidenceKind.CIRCADIAN,
    "EVENT_ACTIVITY" to AlertEvidenceKind.EVENT_CONTEXT,
    "EVENT_ALCOHOL" to AlertEvidenceKind.EVENT_CONTEXT,
    "EVENT_HORMONAL" to AlertEvidenceKind.EVENT_CONTEXT,
    "EVENT_ILLNESS" to AlertEvidenceKind.EVENT_CONTEXT,
    "EVENT_INFUSION" to AlertEvidenceKind.EVENT_CONTEXT,
    "EVENT_SENSOR" to AlertEvidenceKind.EVENT_CONTEXT,
    "EVENT_SLEEP" to AlertEvidenceKind.EVENT_CONTEXT,
    "EVENT_STEROID" to AlertEvidenceKind.EVENT_CONTEXT,
    "EVENT_STRESS" to AlertEvidenceKind.EVENT_CONTEXT,
    "CURRENT_EVIDENCE_MISSING" to AlertEvidenceKind.DATA_QUALITY,
    "CURRENT_EVIDENCE_STALE" to AlertEvidenceKind.DATA_QUALITY,
    "CYCLE_IDENTITY_MISMATCH" to AlertEvidenceKind.DATA_QUALITY,
    "EVENT_CONTEXT_UNAVAILABLE" to AlertEvidenceKind.DATA_QUALITY,
    "CAUSE_AMBIGUOUS" to AlertEvidenceKind.DATA_QUALITY,
    "LEGACY_REPAIRED" to AlertEvidenceKind.DATA_QUALITY
)
