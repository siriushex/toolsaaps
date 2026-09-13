package io.aaps.copilot.receiver

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalBroadcastTrustPolicyTest {

    @Test
    fun exactAapsSenderMaySendAapsStatus() {
        assertThat(
            LocalBroadcastTrustPolicy.isAllowed("info.nightscout.androidaps.status")
        ).isTrue()
    }

    @Test
    fun rawXdripActionIsRejectedBecauseAapsRelaysValidatedStatusOnly() {
        assertThat(
            LocalBroadcastTrustPolicy.isAllowed("com.eveningoutpost.dexdrip.BgEstimate")
        ).isFalse()
    }

    @Test
    fun rawAidexAndTomatoActionsAreRejectedBecauseAapsRelaysValidatedStatusOnly() {
        assertThat(
            LocalBroadcastTrustPolicy.isAllowed(
                "com.microtechmd.cgms.aidex.action.BgEstimate"
            )
        ).isFalse()
        assertThat(
            LocalBroadcastTrustPolicy.isAllowed("com.fanqies.tomatofn.BgEstimate")
        ).isFalse()
    }

    @Test
    fun rawJugglucoActionIsRejectedBecauseAapsRelaysValidatedStatusOnly() {
        assertThat(
            LocalBroadcastTrustPolicy.isAllowed("glucodata.Minute")
        ).isFalse()
    }

    @Test
    fun unknownActionFailsClosedForTrustedPackage() {
        assertThat(
            LocalBroadcastTrustPolicy.isAllowed(
                "io.aaps.copilot.BROADCAST_TEST_SEND_TEMP_TARGET"
            )
        ).isFalse()
    }
}
