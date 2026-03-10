package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import org.junit.Test

class GlucoseSanitizerTest {

    @Test
    fun filterEntities_keepsHighestPriorityRowPerTimestamp() {
        val timestamp = 1_710_000_000_000L
        val rows = listOf(
            GlucoseSampleEntity(
                id = 10L,
                timestamp = timestamp,
                mmol = 7.2,
                source = "xdrip_broadcast",
                quality = "OK"
            ),
            GlucoseSampleEntity(
                id = 11L,
                timestamp = timestamp,
                mmol = 7.1,
                source = "nightscout",
                quality = "OK"
            )
        )

        val filtered = GlucoseSanitizer.filterEntities(rows)

        assertThat(filtered).hasSize(1)
        assertThat(filtered.single().id).isEqualTo(11L)
        assertThat(filtered.single().source).isEqualTo("nightscout")
    }

    @Test
    fun duplicateEntityIdsToDelete_returnsLosersForTimestampDuplicates() {
        val timestamp = 1_710_000_000_000L
        val rows = listOf(
            GlucoseSampleEntity(
                id = 20L,
                timestamp = timestamp,
                mmol = 6.8,
                source = "xdrip_broadcast",
                quality = "OK"
            ),
            GlucoseSampleEntity(
                id = 21L,
                timestamp = timestamp,
                mmol = 6.8,
                source = "nightscout",
                quality = "OK"
            ),
            GlucoseSampleEntity(
                id = 22L,
                timestamp = timestamp + 300_000L,
                mmol = 6.9,
                source = "xdrip_broadcast",
                quality = "OK"
            )
        )

        val ids = GlucoseSanitizer.duplicateEntityIdsToDelete(rows)

        assertThat(ids).containsExactly(20L)
    }
}
