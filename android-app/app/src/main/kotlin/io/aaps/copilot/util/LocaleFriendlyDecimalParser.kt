package io.aaps.copilot.util

import java.math.BigDecimal

object LocaleFriendlyDecimalParser {

    data class Parsed(
        val value: Double,
        val canonical: String
    )

    private val decimalPattern = Regex("""[+-]?(?:\d+(?:[.,]\d*)?|[.,]\d+)""")

    fun parse(raw: String): Parsed? {
        val trimmed = raw.trim()
        if (!decimalPattern.matches(trimmed)) return null

        val normalized = trimmed.replace(',', '.')
        val canonical = runCatching {
            BigDecimal(normalized).stripTrailingZeros().toPlainString()
        }.getOrNull() ?: return null
        val value = canonical.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
        return Parsed(value = value, canonical = canonical)
    }
}
