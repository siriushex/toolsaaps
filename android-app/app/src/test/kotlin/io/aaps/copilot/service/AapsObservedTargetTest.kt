package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.ui.foundation.screens.observedAapsTargetRange
import org.junit.Test

class AapsObservedTargetTest {
    private val now = 1_800_000_000_000L

    @Test
    fun preservesBothBoundsAndExpiryOfAnActiveTemporaryTarget() {
        val target = checkNotNull(decode("temporary", extra = ""","expiresAt":${now + 10_000}"""))
        assertThat(target.kind).isEqualTo(AapsObservedTarget.Kind.TEMPORARY)
        assertThat(target.expiresAt).isEqualTo(now + 10_000)
        assertThat(observedAapsTargetRange(target)).isEqualTo("5.0 - 6.0")
    }

    @Test
    fun acceptsAdjustedPointWithoutMistakingItForTheProfileRange() {
        val target = checkNotNull(decode("aps_adjusted", high = 90))
        assertThat(target.kind).isEqualTo(AapsObservedTarget.Kind.APS_ADJUSTED)
        assertThat(observedAapsTargetRange(target)).isEqualTo("5.0")
        assertThat(decode("aps_adjusted")).isNull()
    }

    @Test
    fun refusesExpiredFutureMalformedOrAmbiguousReadback() {
        assertThat(decode("temporary", extra = ""","expiresAt":$now""")).isNull()
        assertThat(decode("profile", at = now + 1)).isNull()
        assertThat(decode("profile", at = now - 10_001)).isNull()
        assertThat(decode("profile", high = 89)).isNull()
        assertThat(decode("profile", extra = ""","expiresAt":${now + 1}""")).isNull()
        assertThat(decode("profile", extra = ""","targetTop":110""")).isNull()
        assertThat(decode("unknown")).isNull()
    }

    private fun decode(kind: String, at: Long = now, high: Int = 108, extra: String = "") =
        AapsObservedTarget.decode(
            """{"version":1,"at":$at,"kind":"$kind","lowMgdl":90,"highMgdl":$high$extra}""".encodeToByteArray(),
            now
        )
}
