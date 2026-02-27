package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import org.junit.Test

class BroadcastIngestStatusTelemetryTest {

    @Test
    fun normalizeStatusTelemetry_keepsCanonicalAndRaw_andProjectsStatusTherapyKeys() {
        val ts = 1_700_000_000_000L
        val source = "aaps_broadcast"
        val mapped = listOf(
            sample(id = "1", key = "iob_units", ts = ts, source = source, value = 1.7),
            sample(id = "2", key = "cob_grams", ts = ts, source = source, value = 12.0),
            sample(id = "3", key = "carbs_grams", ts = ts, source = source, value = 18.0),
            sample(id = "4", key = "insulin_units", ts = ts, source = source, value = 1.2),
            sample(id = "5", key = "raw_reason", ts = ts, source = source, valueText = "test_reason"),
            sample(id = "6", key = "raw_profile", ts = ts, source = source, valueText = "default")
        )

        val normalized = BroadcastIngestRepository.normalizeStatusTelemetry(mapped)
        val keys = normalized.map { it.key }

        assertThat(keys).contains("iob_units")
        assertThat(keys).contains("cob_grams")
        assertThat(keys).contains("raw_reason")
        assertThat(keys).contains("raw_profile")
        assertThat(keys).contains("status_carbs_grams")
        assertThat(keys).contains("status_insulin_units")
        assertThat(keys).doesNotContain("carbs_grams")
        assertThat(keys).doesNotContain("insulin_units")
    }

    @Test
    fun normalizeStatusTelemetry_isDeterministicForProjectedStatusKeys() {
        val ts = 1_700_000_010_000L
        val source = "aaps_broadcast"
        val mapped = listOf(
            sample(id = "a", key = "carbs_grams", ts = ts, source = source, value = 20.0),
            sample(id = "b", key = "insulin_units", ts = ts, source = source, value = 2.0)
        )

        val normalized = BroadcastIngestRepository.normalizeStatusTelemetry(mapped)
        val byKey = normalized.associateBy { it.key }

        assertThat(byKey.keys).containsExactly("status_carbs_grams", "status_insulin_units")
        assertThat(byKey.getValue("status_carbs_grams").id)
            .isEqualTo("tm-$source-status_carbs_grams-$ts")
        assertThat(byKey.getValue("status_insulin_units").id)
            .isEqualTo("tm-$source-status_insulin_units-$ts")
    }

    private fun sample(
        id: String,
        key: String,
        ts: Long,
        source: String,
        value: Double? = null,
        valueText: String? = null
    ): TelemetrySampleEntity {
        return TelemetrySampleEntity(
            id = id,
            timestamp = ts,
            source = source,
            key = key,
            valueDouble = value,
            valueText = valueText,
            unit = null,
            quality = "OK"
        )
    }
}
