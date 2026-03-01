package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InsulinActionProfilesTest {

    @Test
    fun parserFallsBackToNovorapidForUnknownOrBlank() {
        assertThat(InsulinActionProfileId.fromRaw(null)).isEqualTo(InsulinActionProfileId.NOVORAPID)
        assertThat(InsulinActionProfileId.fromRaw("")).isEqualTo(InsulinActionProfileId.NOVORAPID)
        assertThat(InsulinActionProfileId.fromRaw("unknown_curve")).isEqualTo(InsulinActionProfileId.NOVORAPID)
    }

    @Test
    fun profilesAreMonotonicAndBounded() {
        InsulinActionProfiles.supportedIds().forEach { id ->
            val profile = InsulinActionProfiles.profile(id)
            var prev = profile.cumulativeAt(0.0)
            for (minute in 1..360) {
                val current = profile.cumulativeAt(minute.toDouble())
                assertThat(current).isAtLeast(prev - 1e-9)
                assertThat(current).isAtLeast(0.0)
                assertThat(current).isAtMost(1.0)
                prev = current
            }
            assertThat(profile.cumulativeAt(600.0)).isWithin(1e-9).of(1.0)
        }
    }
}

