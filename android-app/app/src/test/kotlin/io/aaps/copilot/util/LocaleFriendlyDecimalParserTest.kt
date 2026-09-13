package io.aaps.copilot.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocaleFriendlyDecimalParserTest {

    @Test
    fun acceptsTrimmedDotAndSingleCommaAndReturnsCanonicalText() {
        assertThat(LocaleFriendlyDecimalParser.parse(" 5.6 "))
            .isEqualTo(LocaleFriendlyDecimalParser.Parsed(value = 5.6, canonical = "5.6"))
        assertThat(LocaleFriendlyDecimalParser.parse(" 5,6 "))
            .isEqualTo(LocaleFriendlyDecimalParser.Parsed(value = 5.6, canonical = "5.6"))
    }

    @Test
    fun rejectsMalformedSeparatorsAndNonFiniteValues() {
        listOf(
            "",
            "5,6,7",
            "5.6.7",
            "5,6.7",
            "5.6,7",
            "NaN",
            "Infinity",
            "-Infinity"
        ).forEach { raw ->
            assertThat(LocaleFriendlyDecimalParser.parse(raw)).isNull()
        }
    }
}
