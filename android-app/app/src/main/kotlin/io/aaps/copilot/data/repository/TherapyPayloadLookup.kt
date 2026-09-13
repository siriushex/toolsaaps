package io.aaps.copilot.data.repository

import java.util.Locale

internal object TherapyPayloadLookup {
    private val camelCaseBoundary = Regex("([a-z0-9])([A-Z])")
    private val nonAlphaNumeric = Regex("[^a-z0-9]+")

    fun normalizeKey(value: String): String = value
        .replace(camelCaseBoundary, "$1_$2")
        .lowercase(Locale.US)
        .replace(nonAlphaNumeric, "_")
        .trim('_')

    fun number(payload: Map<String, String>, keys: Array<out String>): Double? {
        if (payload.isEmpty()) return null
        val normalizedKeys = keys.map(::normalizeKey)
        for (candidate in normalizedKeys) {
            for ((rawKey, rawValue) in payload) {
                if (normalizeKey(rawKey) == candidate) {
                    // The first matching raw entry wins, even when its value is malformed.
                    return rawValue.replace(",", ".").toDoubleOrNull()
                }
            }
        }
        return null
    }
}
