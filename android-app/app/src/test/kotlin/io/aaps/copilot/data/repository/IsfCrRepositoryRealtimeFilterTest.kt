package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.TherapyEvent
import org.junit.Test

class IsfCrRepositoryRealtimeFilterTest {

    @Test
    fun `realtime filter keeps only isfcr relevant therapy events`() {
        val tempTarget = TherapyEvent(
            ts = 1L,
            type = "temp_target",
            payload = mapOf("targetBottom" to "90")
        )
        val correctionBolus = TherapyEvent(
            ts = 2L,
            type = "Bolus",
            payload = mapOf("enteredInsulin" to "2.5")
        )
        val carbs = TherapyEvent(
            ts = 3L,
            type = "carbs",
            payload = mapOf("grams" to "24")
        )
        val setChange = TherapyEvent(
            ts = 4L,
            type = "infusion_set_change",
            payload = emptyMap()
        )

        assertThat(isRelevantRealtimeIsfCrTherapyEvent(tempTarget)).isFalse()
        assertThat(isRelevantRealtimeIsfCrTherapyEvent(correctionBolus)).isTrue()
        assertThat(isRelevantRealtimeIsfCrTherapyEvent(carbs)).isTrue()
        assertThat(isRelevantRealtimeIsfCrTherapyEvent(setChange)).isTrue()
    }

    @Test
    fun `realtime therapy scan widens when raw rows are saturated by temp targets`() {
        assertThat(
            shouldWidenRealtimeTherapyScan(
                rawRowCount = 720,
                relevantRowCount = 6,
                currentLimit = 720
            )
        ).isTrue()
        assertThat(
            shouldWidenRealtimeTherapyScan(
                rawRowCount = 300,
                relevantRowCount = 6,
                currentLimit = 720
            )
        ).isFalse()
        assertThat(
            shouldWidenRealtimeTherapyScan(
                rawRowCount = 720,
                relevantRowCount = 40,
                currentLimit = 720
            )
        ).isFalse()
    }
}
