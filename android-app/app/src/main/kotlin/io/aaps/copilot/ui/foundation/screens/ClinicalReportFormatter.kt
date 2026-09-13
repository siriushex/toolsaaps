package io.aaps.copilot.ui.foundation.screens

import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object ClinicalReportFormatter {

    fun formatNumber(value: Double, decimals: Int, locale: Locale): String? {
        if (!value.isFinite() || decimals !in 0..3) return null
        return NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = false
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }.format(value)
    }

    fun formatPercent(fraction: Double, decimals: Int, locale: Locale): String? {
        if (!fraction.isFinite() || decimals !in 0..3) return null
        return NumberFormat.getPercentInstance(locale).apply {
            isGroupingUsed = false
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }.format(fraction)
    }

    fun formatTimestamp(
        timestamp: Long,
        locale: Locale,
        zoneId: java.time.ZoneId
    ): String? {
        if (timestamp <= 0L) return null
        return runCatching {
            DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT,
                locale
            ).apply {
                timeZone = TimeZone.getTimeZone(zoneId)
            }.format(Date(timestamp))
        }.getOrNull()
    }
}
