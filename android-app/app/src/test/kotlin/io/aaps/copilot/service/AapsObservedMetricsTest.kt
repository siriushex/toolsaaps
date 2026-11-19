package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AapsObservedMetricsTest {
    private val now = 1_800_000_000_000L

    @Test fun unitsAndActualZeroRemainDistinctFromMissing() {
        val observed = checkNotNull(decode(""","profileIsfMgdl":45,"profileCr":12,"cobGrams":0,"cobAt":$now"""))
        assertThat(observed.profileIsfMmol).isWithin(0.01).of(2.5)
        assertThat(observed.profileCr).isEqualTo(12.0)
        assertThat(observed.cobGrams).isEqualTo(0.0)
        assertThat(decode(""","profileCr":12""")?.cobGrams).isNull()
    }

    @Test fun staleCobDoesNotHideAValidProfileOrSilentlyBecomeZero() {
        val observed = checkNotNull(decode(""","profileCr":12,"cobGrams":10,"cobAt":${now - 600_001}"""))
        assertThat(observed.profileCr).isEqualTo(12.0)
        assertThat(observed.cobGrams).isNull()
    }

    @Test fun rejectsFutureSnapshotsIncompleteCobAndActions() {
        assertThat(decode(""","profileCr":12""", at = now + 1)).isNull()
        assertThat(decode(""","profileCr":12,"cobGrams":10""")).isNull()
        assertThat(decode(""","profileCr":12,"target":100""")).isNull()
    }

    private fun decode(extra: String, at: Long = now) =
        AapsObservedMetrics.decode("""{"version":1,"at":$at$extra}""".encodeToByteArray(), now)
}
