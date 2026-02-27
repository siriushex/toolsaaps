package io.aaps.copilot.data.repository

import java.util.Locale

internal object GlucoseValueResolver {

    private val exactKeys = listOf(
        "com.eveningoutpost.dexdrip.Extras.BgEstimate",
        "sgv",
        "mgdl",
        "glucose",
        "bgestimate"
    )

    private val blockedTokens = setOf(
        "mills",
        "timestamp",
        "time",
        "date",
        "created",
        "delta",
        "avgdelta",
        "future",
        "target",
        "low",
        "high",
        "direction",
        "cob",
        "iob",
        "carb",
        "insulin",
        "battery"
    )

    data class Candidate(
        val valueRaw: Double,
        val key: String
    )

    fun resolve(values: Map<String, String>): Candidate? {
        exactKeys.forEach { exact ->
            values.entries.firstOrNull { it.key.equals(exact, ignoreCase = true) }?.let { entry ->
                entry.value.toDoubleOrNullLocale()?.let { parsed ->
                    return Candidate(valueRaw = parsed, key = entry.key)
                }
            }
        }

        val candidate = values.entries
            .asSequence()
            .mapNotNull { entry ->
                val parsed = entry.value.toDoubleOrNullLocale() ?: return@mapNotNull null
                val normalizedKey = normalizeKey(entry.key)
                if (normalizedKey.isBlank()) return@mapNotNull null
                val parts = normalizedKey.split('_').filter { it.isNotBlank() }
                if (parts.any { it in blockedTokens }) return@mapNotNull null
                val baseScore = score(normalizedKey)
                if (baseScore <= 0) return@mapNotNull null
                val depthPenalty = parts.size.coerceAtLeast(1) - 1
                val score = baseScore - depthPenalty
                ScoredCandidate(
                    candidate = Candidate(
                        valueRaw = parsed,
                        key = entry.key
                    ),
                    score = score
                )
            }
            .maxByOrNull { it.score }

        return candidate?.candidate
    }

    private fun score(normalizedKey: String): Int {
        if (normalizedKey == "sgv" || normalizedKey.endsWith("_sgv")) return 100
        if (normalizedKey.contains("mgdl")) return 95
        if (normalizedKey.contains("bgestimate")) return 92
        if (normalizedKey == "glucose" || normalizedKey.endsWith("_glucose")) return 88
        if (normalizedKey.contains("_glucose_")) return 72
        return 0
    }

    private fun String.toDoubleOrNullLocale(): Double? = replace(",", ".").toDoubleOrNull()

    private fun normalizeKey(value: String): String {
        return value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private data class ScoredCandidate(
        val candidate: Candidate,
        val score: Int
    )
}
