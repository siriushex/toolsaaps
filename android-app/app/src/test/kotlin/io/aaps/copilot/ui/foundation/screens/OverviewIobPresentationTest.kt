package io.aaps.copilot.ui.foundation.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class OverviewIobPresentationTest {
    @Test fun preservesNegativeNetAndFixedOrder() {
        val value = overviewIobPresentation(1.8, -0.4)
        assertEquals("1.8", value.current)
        assertEquals("-0.4", value.signedNet)
        assertEquals("1.8 /\u00a0-0.4", value.value)
    }

    @Test fun preservesPositiveAndZeroNet() {
        assertEquals("1.8 /\u00a01.2", overviewIobPresentation(1.8, 1.2).value)
        assertEquals("0.0 /\u00a00.0", overviewIobPresentation(0.0, 0.0).value)
    }

    @Test fun missingValuesAreIndependentAndNotCopied() {
        assertEquals("1.8 /\u00a0--", overviewIobPresentation(1.8, null).value)
        assertEquals("-- /\u00a0-0.4", overviewIobPresentation(null, -0.4).value)
        assertEquals("-- /\u00a0--", overviewIobPresentation(null, null).value)
    }

    @Test fun nonfiniteValuesAreUnavailableIndependently() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalid ->
            assertEquals("-- /\u00a0-0.4", overviewIobPresentation(invalid, -0.4).value)
            assertEquals("1.8 /\u00a0--", overviewIobPresentation(1.8, invalid).value)
        }
    }

    @Test fun keepsExistingPrecisionAndDoesNotClipSignedNet() {
        assertEquals("60.0 /\u00a0-60.0", overviewIobPresentation(60.0, -60.0).value)
        assertEquals("1.2 /\u00a0-0.4", overviewIobPresentation(1.24, -0.44).value)
    }
}
