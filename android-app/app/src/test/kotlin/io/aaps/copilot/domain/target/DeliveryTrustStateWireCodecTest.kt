package io.aaps.copilot.domain.target

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeliveryTrustStateWireCodecTest {

    @Test
    fun `stable codes are explicit and permanently pinned`() {
        assertThat(DeliveryTrustStateWireCodec.encode(DeliveryTrustState.NORMAL)).isEqualTo(100.0)
        assertThat(DeliveryTrustStateWireCodec.encode(DeliveryTrustState.WATCH)).isEqualTo(110.0)
        assertThat(DeliveryTrustStateWireCodec.encode(DeliveryTrustState.SUSPECTED_NONRESPONSE)).isEqualTo(120.0)
        assertThat(DeliveryTrustStateWireCodec.encode(DeliveryTrustState.UNKNOWN)).isEqualTo(190.0)
    }

    @Test
    fun `stable decoder rejects nonfinite fractional and unknown codes`() {
        listOf(
            null,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            100.5,
            0.0,
            3.0,
            999.0
        ).forEach { value ->
            assertThat(DeliveryTrustStateWireCodec.decodeStable(value)).isNull()
        }
    }

    @Test
    fun `legacy decoder preserves only the deployed ordinal mapping`() {
        fun decode(value: Double?) = DeliveryTrustStateWireCodec.decodeTelemetry(
            DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY,
            DeliveryTrustStateWireCodec.LEGACY_SOURCE,
            value
        )
        assertThat(decode(0.0)).isEqualTo(DeliveryTrustState.NORMAL)
        assertThat(decode(1.0)).isEqualTo(DeliveryTrustState.WATCH)
        assertThat(decode(2.0)).isEqualTo(DeliveryTrustState.SUSPECTED_NONRESPONSE)
        assertThat(decode(3.0)).isEqualTo(DeliveryTrustState.UNKNOWN)
        listOf(null, -1.0, 1.5, 4.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { value ->
            assertThat(decode(value)).isNull()
        }
    }

    @Test
    fun `telemetry keys select only their matching codec version`() {
        assertThat(
            DeliveryTrustStateWireCodec.decodeTelemetry(
                DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
                "future_internal_source",
                120.0
            )
        ).isEqualTo(DeliveryTrustState.SUSPECTED_NONRESPONSE)
        assertThat(
            DeliveryTrustStateWireCodec.decodeTelemetry(
                DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY,
                DeliveryTrustStateWireCodec.LEGACY_SOURCE,
                2.0
            )
        ).isEqualTo(DeliveryTrustState.SUSPECTED_NONRESPONSE)
        assertThat(
            DeliveryTrustStateWireCodec.decodeTelemetry(
                DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
                DeliveryTrustStateWireCodec.LEGACY_SOURCE,
                2.0
            )
        ).isNull()
        assertThat(
            DeliveryTrustStateWireCodec.decodeTelemetry(
                "target_delivery_trust_state_v3",
                DeliveryTrustStateWireCodec.LEGACY_SOURCE,
                120.0
            )
        ).isNull()
        assertThat(
            DeliveryTrustStateWireCodec.decodeTelemetry(
                DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY,
                "foreign_source",
                2.0
            )
        ).isNull()
    }
}
