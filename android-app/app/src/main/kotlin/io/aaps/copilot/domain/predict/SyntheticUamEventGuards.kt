package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.TherapyEvent
import java.util.Locale

internal fun therapyEventNote(event: TherapyEvent): String? {
    return event.payload["note"] ?: event.payload["notes"] ?: event.payload["reason"]
}

internal fun syntheticUamTag(event: TherapyEvent): UamTag? {
    return parseUamTag(therapyEventNote(event))
}

internal fun isSyntheticUamCarbEvent(event: TherapyEvent): Boolean {
    val syntheticFlag = event.payload.entries.any { (key, value) ->
        when (normalizeTherapyPayloadKey(key)) {
            "synthetic" -> value.equals("true", ignoreCase = true)
            "source" -> value.equals("uam_engine", ignoreCase = true)
            "reason" -> value.contains("uam_engine", ignoreCase = true)
            "synthetic_type" -> value.contains("uam", ignoreCase = true)
            else -> false
        }
    }
    if (syntheticFlag) return true
    val note = therapyEventNote(event) ?: return false
    return parseUamTag(note) != null || note.contains("UAM_ENGINE|", ignoreCase = true)
}

private fun normalizeTherapyPayloadKey(value: String): String {
    return value
        .replace(CAMEL_CASE_BOUNDARY_REGEX, "$1_$2")
        .lowercase(Locale.US)
        .replace(NON_ALNUM_REGEX, "_")
        .trim('_')
}

private val CAMEL_CASE_BOUNDARY_REGEX = Regex("([a-z0-9])([A-Z])")
private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")
