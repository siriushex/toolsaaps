package io.aaps.copilot.data.local

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import org.junit.Test

class TelemetrySampleSelectorTest {

    @Test
    fun directAapsPacketWinsWhenDerivedMirrorHasSameTimestamp() {
        val timestamp = 1_700_000_000_000L
        val selected = TelemetrySampleSelector.selectLatestByKey(
            listOf(
                telemetry(timestamp, source = "nightscout_devicestatus", value = 7.4),
                telemetry(timestamp, source = "aaps_broadcast", value = 22.0)
            )
        )

        assertThat(selected["cob_grams"]?.valueDouble).isEqualTo(22.0)
        assertThat(selected["cob_grams"]?.source).isEqualTo("aaps_broadcast")
    }

    @Test
    fun newerSampleWinsEvenWhenItComesFromLowerPrioritySource() {
        val selected = TelemetrySampleSelector.selectLatestByKey(
            listOf(
                telemetry(1_700_000_000_000L, source = "aaps_broadcast", value = 22.0),
                telemetry(1_700_000_300_000L, source = "nightscout_devicestatus", value = 20.0)
            )
        )

        assertThat(selected["cob_grams"]?.valueDouble).isEqualTo(20.0)
    }

    private fun telemetry(
        timestamp: Long,
        source: String,
        value: Double
    ) = TelemetrySampleEntity(
        id = "$source-$timestamp-$value",
        timestamp = timestamp,
        source = source,
        key = "cob_grams",
        valueDouble = value,
        valueText = null,
        unit = "g",
        quality = "OK"
    )
}
