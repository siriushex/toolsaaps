package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AcceptedSensitivityTupleFreshnessTest {

    @Test
    fun acceptsOnlyZeroThroughFifteenMinutesInclusive() {
        val generationTs = 1_800_000_000_000L

        assertThat(AcceptedSensitivityTupleFreshness.isFresh(generationTs, generationTs - 1L)).isFalse()
        assertThat(AcceptedSensitivityTupleFreshness.isFresh(generationTs, generationTs)).isTrue()
        assertThat(
            AcceptedSensitivityTupleFreshness.isFresh(
                generationTs,
                generationTs + AcceptedSensitivityTupleFreshness.MAX_AGE_MS
            )
        ).isTrue()
        assertThat(
            AcceptedSensitivityTupleFreshness.isFresh(
                generationTs,
                generationTs + AcceptedSensitivityTupleFreshness.MAX_AGE_MS + 1L
            )
        ).isFalse()
    }

    @Test
    fun clockArithmeticOverflowFailsClosed() {
        assertThat(AcceptedSensitivityTupleFreshness.isFresh(Long.MIN_VALUE, Long.MAX_VALUE)).isFalse()
        assertThat(AcceptedSensitivityTupleFreshness.isFresh(Long.MAX_VALUE, Long.MIN_VALUE)).isFalse()
    }
}
