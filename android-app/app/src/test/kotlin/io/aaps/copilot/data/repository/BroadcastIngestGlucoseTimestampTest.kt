package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BroadcastIngestGlucoseTimestampTest {

    @Test
    fun aapsStatusUsesGlucoseDateInsteadOfEnvelopeTimestamp() {
        val envelopeTimestamp = 1_800_000_000_000L
        val glucoseTimestamp = envelopeTimestamp - 300_000L

        val selected = selectBroadcastGlucoseTimestamp(
            action = "info.nightscout.androidaps.status",
            extras = mapOf(
                "timestamp" to envelopeTimestamp.toString(),
                "date" to glucoseTimestamp.toString()
            ),
            fallbackNowMs = envelopeTimestamp
        )

        assertThat(selected).isEqualTo(glucoseTimestamp)
    }

    @Test
    fun nonStatusBroadcastKeepsEnvelopeTimestampPrecedence() {
        val envelopeTimestamp = 1_800_000_000_000L
        val secondaryDate = envelopeTimestamp - 300_000L

        val selected = selectBroadcastGlucoseTimestamp(
            action = "com.eveningoutpost.dexdrip.BgEstimate",
            extras = mapOf(
                "timestamp" to envelopeTimestamp.toString(),
                "date" to secondaryDate.toString()
            ),
            fallbackNowMs = envelopeTimestamp
        )

        assertThat(selected).isEqualTo(envelopeTimestamp)
    }

    @Test
    fun aapsStatusWithoutValidSampleDateFailsClosed() {
        val envelopeTimestamp = 1_800_000_000_000L

        val selected = selectBroadcastGlucoseTimestamp(
            action = "info.nightscout.androidaps.status",
            extras = mapOf(
                "timestamp" to envelopeTimestamp.toString(),
                "date" to "invalid"
            ),
            fallbackNowMs = envelopeTimestamp
        )

        assertThat(selected).isNull()
    }
}
