package io.aaps.copilot.ui.foundation.screens

import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import java.util.Locale
import org.junit.Test

class ClinicalReportFormatterTest {

    @Test
    fun numberFormattingUsesRequestedLocaleWithoutChangingNumericIdentity() {
        assertThat(ClinicalReportFormatter.formatNumber(7.25, 2, Locale.US))
            .isEqualTo("7.25")
        assertThat(
            ClinicalReportFormatter.formatNumber(
                7.25,
                2,
                Locale.forLanguageTag("ru-RU")
            )
        ).isEqualTo("7,25")
    }

    @Test
    fun percentFormattingUsesLocaleNativePatternOnFractionValues() {
        assertThat(ClinicalReportFormatter.formatPercent(0.965, 1, Locale.US))
            .isEqualTo("96.5%")
        assertThat(
            ClinicalReportFormatter.formatPercent(
                0.965,
                1,
                Locale.forLanguageTag("ru-RU")
            )
        ).isEqualTo("96,5\u00a0%")
    }

    @Test
    fun timestampFormattingUsesRequestedLocaleAndZone() {
        val timestamp = 1_800_000_000_000L

        val english = ClinicalReportFormatter.formatTimestamp(
            timestamp,
            Locale.US,
            ZoneId.of("UTC")
        )
        val russian = ClinicalReportFormatter.formatTimestamp(
            timestamp,
            Locale.forLanguageTag("ru-RU"),
            ZoneId.of("UTC")
        )

        assertThat(english).isNotEqualTo(russian)
        assertThat(english).contains("/")
        assertThat(russian).contains(".")
    }

    @Test
    fun nonFiniteNumbersAndInvalidTimestampsFailClosed() {
        assertThat(ClinicalReportFormatter.formatNumber(Double.NaN, 1, Locale.US)).isNull()
        assertThat(
            ClinicalReportFormatter.formatNumber(
                Double.POSITIVE_INFINITY,
                1,
                Locale.US
            )
        ).isNull()
        assertThat(
            ClinicalReportFormatter.formatPercent(
                Double.NaN,
                1,
                Locale.US
            )
        ).isNull()
        assertThat(
            ClinicalReportFormatter.formatPercent(
                Double.NEGATIVE_INFINITY,
                1,
                Locale.US
            )
        ).isNull()
        assertThat(
            ClinicalReportFormatter.formatTimestamp(
                0L,
                Locale.US,
                ZoneId.of("UTC")
            )
        ).isNull()
    }
}
