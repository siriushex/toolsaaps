package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.TherapyEvent
import org.junit.Test

class SyntheticUamEventGuardsTest {

    @Test
    fun detectsSourceOnlySyntheticUamCarbEvent() {
        val event = TherapyEvent(
            ts = 1_760_000_000_000L,
            type = "carbs",
            payload = mapOf(
                "grams" to "18",
                "source" to "uam_engine",
                "reason" to "uam_engine"
            )
        )

        assertThat(isSyntheticUamCarbEvent(event)).isTrue()
        assertThat(syntheticUamTag(event)).isNull()
    }

    @Test
    fun detectsTaggedSyntheticUamCarbEvent() {
        val event = TherapyEvent(
            ts = 1_760_000_000_000L,
            type = "carbs",
            payload = mapOf(
                "grams" to "18",
                "notes" to "UAM_ENGINE|id=test|seq=2|ver=1|mode=BOOST|"
            )
        )

        assertThat(isSyntheticUamCarbEvent(event)).isTrue()
        val tag = syntheticUamTag(event)
        assertThat(tag).isNotNull()
        assertThat(tag!!.id).isEqualTo("test")
        assertThat(tag.seq).isEqualTo(2)
    }

    @Test
    fun leavesManualCarbEventUntouched() {
        val event = TherapyEvent(
            ts = 1_760_000_000_000L,
            type = "carbs",
            payload = mapOf(
                "grams" to "20",
                "note" to "manual snack"
            )
        )

        assertThat(isSyntheticUamCarbEvent(event)).isFalse()
        assertThat(syntheticUamTag(event)).isNull()
    }
}
