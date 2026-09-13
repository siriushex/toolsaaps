package io.aaps.copilot.receiver

import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.pump.PumpLinkAdapterState
import io.aaps.copilot.domain.pump.PumpLinkDriverState
import io.aaps.copilot.domain.pump.PumpLinkSnapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PumpLinkHealthContractTest {
    private val snapshot = PumpLinkSnapshot(12, 1_000L, 2, 3_000_000L, true,
        PumpLinkAdapterState.ON, PumpLinkDriverState.IDLE, false, 2_900_000L)

    @Test fun typedRoundTripIncludesVerifiedResponseOnlyWhenKnown() {
        assertThat(PumpLinkHealthContract.decode(PumpLinkHealthContract.encode(snapshot))).isEqualTo(snapshot)
        val unknown = snapshot.copy(lastVerifiedStatusElapsedMs = null)
        assertThat(PumpLinkHealthContract.decode(PumpLinkHealthContract.encode(unknown))).isEqualTo(unknown)
        assertThat(PumpLinkHealthContract.encode(unknown).containsKey("lastVerifiedStatusElapsedMs")).isFalse()
    }

    @Test fun explicitCanonicalFixtureFromSenderIsAccepted() {
        val packet = Bundle().apply {
            putInt("protocolVersion", 1)
            putInt("bootCount", 12)
            putLong("sessionStartedElapsedMs", 1_000L)
            putLong("sequence", 2L)
            putLong("sampledElapsedMs", 3_000_000L)
            putBoolean("supported", true)
            putString("adapterState", "ON")
            putString("driverState", "IDLE")
            putBoolean("intentionalDisconnect", false)
            putLong("lastVerifiedStatusElapsedMs", 2_900_000L)
        }
        assertThat(PumpLinkHealthContract.decode(packet)).isEqualTo(snapshot)
    }

    @Test fun missingWrongTypeAndUnknownVersionAreRejected() {
        assertThat(PumpLinkHealthContract.decode(null)).isNull()
        assertThat(PumpLinkHealthContract.decode(Bundle())).isNull()
        val valid = PumpLinkHealthContract.encode(snapshot)
        assertThat(PumpLinkHealthContract.decode(Bundle(valid).apply { remove("bootCount") })).isNull()
        assertThat(PumpLinkHealthContract.decode(Bundle(valid).apply { putString("sequence", "2") })).isNull()
        assertThat(PumpLinkHealthContract.decode(Bundle(valid).apply { putInt("protocolVersion", 2) })).isNull()
        assertThat(PumpLinkHealthContract.decode(Bundle(valid).apply { putLong("protocolVersion", 1L) })).isNull()
    }

    @Test fun unknownEnumsAndUnrelatedSensitiveExtrasAreRejected() {
        val valid = PumpLinkHealthContract.encode(snapshot)
        assertThat(PumpLinkHealthContract.decode(Bundle(valid).apply { putString("adapterState", "BAD") })).isNull()
        assertThat(PumpLinkHealthContract.decode(Bundle(valid).apply { putString("driverState", "x".repeat(5_000)) })).isNull()
        assertThat(PumpLinkHealthContract.decode(Bundle(valid).apply { putString("serialNumber", "not-allowed") })).isNull()
    }

    @Test fun persistedOldPacketCanBeDecodedButMonitorOwnsFreshnessValidation() {
        val old = snapshot.copy(sampledElapsedMs = 2_000L, lastVerifiedStatusElapsedMs = 1_500L)
        assertThat(PumpLinkHealthContract.decode(PumpLinkHealthContract.encode(old))).isEqualTo(old)
    }
}
