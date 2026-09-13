package io.aaps.copilot.ui.foundation.screens

import androidx.compose.ui.text.input.KeyboardType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WindowSecureFlagControllerTest {

    @Test
    fun credentialKeyboardUsesPasswordInputWithoutAutocorrect() {
        assertThat(CredentialKeyboardOptions.keyboardType).isEqualTo(KeyboardType.Password)
        assertThat(CredentialKeyboardOptions.autoCorrectEnabled).isFalse()
    }

    @Test
    fun secondTrackedOwnerPreventsClearUntilItsRelease() {
        val ownership = WindowSecureFlagOwnership(baselineSecure = false)
        val dialogOwner = Any()
        val secondOwner = Any()

        ownership.acquire(dialogOwner)
        ownership.acquire(secondOwner)

        assertThat(ownership.release(dialogOwner)).isFalse()
        assertThat(ownership.ownerCount).isEqualTo(1)
        assertThat(ownership.release(secondOwner)).isTrue()
        assertThat(ownership.ownerCount).isEqualTo(0)
    }

    @Test
    fun baselineSecureFlagIsNeverClearedByTrackedOwners() {
        val ownership = WindowSecureFlagOwnership(baselineSecure = true)
        val owner = Any()

        ownership.acquire(owner)

        assertThat(ownership.release(owner)).isFalse()
        assertThat(ownership.ownerCount).isEqualTo(0)
    }

    @Test
    fun deferredDialogReleaseWaitsForResumeFrameAndKeepsSecondOwner() {
        val ownership = WindowSecureFlagOwnership(baselineSecure = false)
        val dialogOwner = Any()
        val secondOwner = Any()
        ownership.acquire(dialogOwner)
        ownership.acquire(secondOwner)

        ownership.deferRelease(dialogOwner)

        assertThat(ownership.ownerCount).isEqualTo(2)
        assertThat(ownership.releaseDeferred()).isFalse()
        assertThat(ownership.ownerCount).isEqualTo(1)
        assertThat(ownership.release(secondOwner)).isTrue()
    }
}
