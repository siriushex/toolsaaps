package io.aaps.copilot.ui

import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal data class RollingGateWindowUi(
    val days: Int,
    val available: Boolean,
    val eligible: Boolean,
    val reason: String,
    val matchedSamples: Int?,
    val mae30Mmol: Double?,
    val mae60Mmol: Double?
)

internal fun parseRollingGateWindows(raw: String?): List<RollingGateWindowUi> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val root = JsonParser.parseString(raw)
        if (!root.isJsonArray) return@runCatching emptyList()
        val windowsArray = root.asJsonArray
        buildList {
            windowsArray.forEach { item ->
                val window = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val days = window.intOrDefault("days", -1)
                if (days <= 0) return@forEach
                val available = window.booleanOrDefault("available", false)
                val eligible = window.booleanOrDefault("eligible", false)
                val reason = window.stringOrNull("reason")?.ifBlank { "n/a" } ?: "n/a"
                val matchedSamples = window.intOrNull("matchedSamples")?.takeIf { it >= 0 }
                val mae30 = window.doubleOrNull("mae30Mmol")
                val mae60 = window.doubleOrNull("mae60Mmol")
                add(
                    RollingGateWindowUi(
                        days = days,
                        available = available,
                        eligible = eligible,
                        reason = reason,
                        matchedSamples = matchedSamples,
                        mae30Mmol = mae30,
                        mae60Mmol = mae60
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun JsonObject.intOrDefault(key: String, default: Int): Int {
    return intOrNull(key) ?: default
}

private fun JsonObject.intOrNull(key: String): Int? {
    val value = get(key) ?: return null
    return runCatching { value.asInt }.getOrNull()
}

private fun JsonObject.doubleOrNull(key: String): Double? {
    val value = get(key) ?: return null
    return runCatching { value.asDouble }.getOrNull()
}

private fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean {
    val value = get(key) ?: return default
    return runCatching { value.asBoolean }.getOrDefault(default)
}

private fun JsonObject.stringOrNull(key: String): String? {
    val value = get(key) ?: return null
    return runCatching { value.asString }.getOrNull()
}

internal fun formatRollingGateWindowLine(
    window: RollingGateWindowUi,
    formatDouble: (Double?) -> String
): String {
    val status = when {
        !window.available -> "missing"
        window.eligible -> "pass"
        else -> "fail"
    }
    return buildString {
        append("Rolling ${window.days}d gate: status=$status, reason=${window.reason}")
        append(", n=${window.matchedSamples ?: 0}")
        if (window.mae30Mmol != null || window.mae60Mmol != null) {
            append(", MAE30=${formatDouble(window.mae30Mmol)}, MAE60=${formatDouble(window.mae60Mmol)}")
        }
    }
}
