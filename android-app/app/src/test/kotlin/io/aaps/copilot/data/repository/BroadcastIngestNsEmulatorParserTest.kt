package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BroadcastIngestNsEmulatorParserTest {

    @Test
    fun parsesJsonPayloadWithQuotedKeys() {
        val raw = """[{"type":"sgv","date":1772276521108,"sgv":230,"direction":"Flat"}]"""

        val glucose = BroadcastIngestRepository.parseNsEmulatorGlucoseRaw(raw)
        val timestamp = BroadcastIngestRepository.parseNsEmulatorTimestampRaw(raw)

        assertThat(glucose).isEqualTo(230.0)
        assertThat(timestamp).isEqualTo(1_772_276_521_108L)
    }

    @Test
    fun parsesKeyValuePayload() {
        val raw = "sgv=245,date=1772275577000"

        val glucose = BroadcastIngestRepository.parseNsEmulatorGlucoseRaw(raw)
        val timestamp = BroadcastIngestRepository.parseNsEmulatorTimestampRaw(raw)

        assertThat(glucose).isEqualTo(245.0)
        assertThat(timestamp).isEqualTo(1_772_275_577_000L)
    }

    @Test
    fun returnsNullWhenPayloadHasNoNumericSgv() {
        val raw = "[type:sgv]"

        val glucose = BroadcastIngestRepository.parseNsEmulatorGlucoseRaw(raw)
        val timestamp = BroadcastIngestRepository.parseNsEmulatorTimestampRaw(raw)

        assertThat(glucose).isNull()
        assertThat(timestamp).isNull()
    }

    @Test
    fun acceptsEntriesCollectionAsGlucoseStream() {
        assertThat(BroadcastIngestRepository.isNsEmulatorCollectionValue("entries")).isTrue()
        assertThat(BroadcastIngestRepository.isNsEmulatorCollectionValue("entry")).isTrue()
        assertThat(BroadcastIngestRepository.isNsEmulatorCollectionValue("sgv")).isTrue()
        assertThat(BroadcastIngestRepository.isNsEmulatorCollectionValue("treatments")).isFalse()
    }
}
