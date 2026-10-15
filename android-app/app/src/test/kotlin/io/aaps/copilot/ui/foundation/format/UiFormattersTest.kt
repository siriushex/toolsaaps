package io.aaps.copilot.ui.foundation.format

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class UiFormattersTest {
    @Test fun exactGramsPreserveDecimalQuantityWithoutGroupingOrInventingInvalidValues() {
        assertThat(UiFormatters.formatExactGrams(59.25, Locale.US)).isEqualTo("59.25")
        assertThat(UiFormatters.formatExactGrams(59.25, Locale.forLanguageTag("ru"))).isEqualTo("59,25")
        assertThat(UiFormatters.formatExactGrams(10.125, Locale.US)).isEqualTo("10.125")
        assertThat(UiFormatters.formatExactGrams(80.0, Locale.US)).isEqualTo("80")
        for (value in listOf(null, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThat(UiFormatters.formatExactGrams(value)).isEqualTo("--")
        }
    }

    @Test
    fun formatMmolAndUnits_returnsPlaceholderForNull() {
        assertThat(UiFormatters.formatMmol(null)).isEqualTo("--")
        assertThat(UiFormatters.formatUnits(null)).isEqualTo("--")
    }

    @Test
    fun formatSignedDelta_formatsSignAndPrecision() {
        assertThat(UiFormatters.formatSignedDelta(0.1234)).isEqualTo("+0.12")
        assertThat(UiFormatters.formatSignedDelta(-0.126)).isEqualTo("-0.13")
    }

    @Test
    fun formatMinutes_handlesUnderMinuteAndNull() {
        assertThat(UiFormatters.formatMinutes(null)).isEqualTo("--")
        assertThat(UiFormatters.formatMinutes(0)).isEqualTo("<1 min")
        assertThat(UiFormatters.formatMinutes(7)).isEqualTo("7 min")
    }

    @Test
    fun hasWideCi_detectsWideAndInvalidRanges() {
        assertThat(UiFormatters.hasWideCi(ciLow = 5.0, ciHigh = 6.0, widthThreshold = 2.0)).isFalse()
        assertThat(UiFormatters.hasWideCi(ciLow = 5.0, ciHigh = 7.5, widthThreshold = 2.0)).isTrue()
        assertThat(UiFormatters.hasWideCi(ciLow = 7.0, ciHigh = 6.0, widthThreshold = 2.0)).isTrue()
    }

    @Test
    fun formatPercent_convertsToPercentScale() {
        assertThat(UiFormatters.formatPercent(0.731, decimals = 0)).isEqualTo("73%")
        assertThat(UiFormatters.formatPercent(0.731, decimals = 1)).isEqualTo("73.1%")
    }
}
