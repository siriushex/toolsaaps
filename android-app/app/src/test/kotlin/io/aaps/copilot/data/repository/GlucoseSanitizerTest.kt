package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import org.junit.Test

class GlucoseSanitizerTest {

    @Test
    fun deduplicatesSameTimestamp_prefersAapsBroadcast() {
        val ts = 1_700_000_000_000L
        val sanitized = GlucoseSanitizer.filterEntities(
            listOf(
                GlucoseSampleEntity(id = 1, timestamp = ts, mmol = 7.1, source = "nightscout", quality = "OK"),
                GlucoseSampleEntity(id = 2, timestamp = ts, mmol = 7.2, source = "aaps_broadcast", quality = "OK")
            )
        )

        assertThat(sanitized).hasSize(1)
        assertThat(sanitized.first().source).isEqualTo("aaps_broadcast")
        assertThat(sanitized.first().mmol).isEqualTo(7.2)
    }

    @Test
    fun deduplicatesSameTimestampSameSource_prefersLatestRowId() {
        val ts = 1_700_000_001_000L
        val sanitized = GlucoseSanitizer.filterEntities(
            listOf(
                GlucoseSampleEntity(id = 10, timestamp = ts, mmol = 6.4, source = "nightscout", quality = "OK"),
                GlucoseSampleEntity(id = 12, timestamp = ts, mmol = 6.6, source = "nightscout", quality = "OK")
            )
        )

        assertThat(sanitized).hasSize(1)
        assertThat(sanitized.first().id).isEqualTo(12L)
        assertThat(sanitized.first().mmol).isEqualTo(6.6)
    }

    @Test
    fun removesLegacyLocalBroadcastArtifacts() {
        val ts = 1_700_000_002_000L
        val sanitized = GlucoseSanitizer.filterEntities(
            listOf(
                GlucoseSampleEntity(id = 1, timestamp = ts, mmol = 31.0, source = "local_broadcast", quality = "OK"),
                GlucoseSampleEntity(id = 2, timestamp = ts + 60_000L, mmol = 7.0, source = "nightscout", quality = "OK")
            )
        )

        assertThat(sanitized).hasSize(1)
        assertThat(sanitized.first().mmol).isEqualTo(7.0)
    }

    @Test
    fun deduplicatesDomainPointsByTimestamp() {
        val ts = 1_700_000_003_000L
        val points = listOf(
            GlucosePoint(ts = ts, valueMmol = 8.1, source = "nightscout", quality = DataQuality.OK),
            GlucosePoint(ts = ts, valueMmol = 8.0, source = "aaps_broadcast", quality = DataQuality.OK)
        )

        val sanitized = GlucoseSanitizer.filterPoints(points)
        assertThat(sanitized).hasSize(1)
        assertThat(sanitized.first().source).isEqualTo("aaps_broadcast")
    }
}
