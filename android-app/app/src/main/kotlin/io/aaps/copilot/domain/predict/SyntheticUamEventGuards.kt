package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.model.TherapyCarbComponentKind
import io.aaps.copilot.domain.model.resolveTherapyComponents
import java.util.concurrent.ConcurrentHashMap

internal fun therapyEventNote(event: TherapyEvent): String? {
    return event.payload["note"] ?: event.payload["notes"] ?: event.payload["reason"]
}

internal fun syntheticUamTag(event: TherapyEvent): UamTag? {
    return parseUamTag(therapyEventNote(event))
}

internal fun isSyntheticUamCarbEvent(event: TherapyEvent): Boolean {
    val components = resolveTherapyComponents(event)
    if (components.canonicalCarbAuthoritative) {
        return components.carbKind == TherapyCarbComponentKind.UAM_SYNTHETIC
    }
    val payload = event.payload
    val syntheticFlag =
        payloadValueEquals(payload, "true", "synthetic") ||
            payloadValueEquals(payload, "uam_engine", "source") ||
            payloadValueContains(payload, "uam_engine", "reason") ||
            payloadValueContains(payload, "uam", "synthetic_type", "syntheticType") ||
            payload.entries.any { (key, value) ->
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
    if (value.isEmpty()) return value
    return NORMALIZED_KEY_CACHE.getOrPut(value) {
        buildString(value.length + 4) {
            var previousWasUnderscore = false
            var previousWasLowerOrDigit = false
            value.forEach { ch ->
                when {
                    ch.isUpperCase() -> {
                        if (previousWasLowerOrDigit && !previousWasUnderscore && isNotEmpty()) {
                            append('_')
                        }
                        append(ch.lowercaseChar())
                        previousWasUnderscore = false
                        previousWasLowerOrDigit = true
                    }
                    ch.isLowerCase() || ch.isDigit() -> {
                        append(ch)
                        previousWasUnderscore = false
                        previousWasLowerOrDigit = true
                    }
                    else -> {
                        if (!previousWasUnderscore && isNotEmpty()) {
                            append('_')
                            previousWasUnderscore = true
                        }
                        previousWasLowerOrDigit = false
                    }
                }
            }
        }.trim('_')
    }
}

private fun payloadValueEquals(
    payload: Map<String, String>,
    expected: String,
    vararg candidateKeys: String
): Boolean {
    return payloadValue(payload, *candidateKeys)?.equals(expected, ignoreCase = true) == true
}

private fun payloadValueContains(
    payload: Map<String, String>,
    needle: String,
    vararg candidateKeys: String
): Boolean {
    return payloadValue(payload, *candidateKeys)?.contains(needle, ignoreCase = true) == true
}

private fun payloadValue(payload: Map<String, String>, vararg candidateKeys: String): String? {
    candidateKeys.forEach { key ->
        payload[key]?.let { return it }
    }
    if (candidateKeys.isEmpty()) return null
    return payload.entries.firstOrNull { (rawKey, _) ->
        val normalized = normalizeTherapyPayloadKey(rawKey)
        candidateKeys.any { normalizeTherapyPayloadKey(it) == normalized }
    }?.value
}

private val NORMALIZED_KEY_CACHE = ConcurrentHashMap<String, String>()
