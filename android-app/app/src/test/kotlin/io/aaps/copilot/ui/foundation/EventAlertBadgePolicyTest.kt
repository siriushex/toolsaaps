package io.aaps.copilot.ui.foundation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EventAlertBadgePolicyTest {
    @Test fun badgeIsAbsentForNonPositiveCountsAndCapsOverflow() {
        assertThat(activeEventBadgeText(-1)).isNull()
        assertThat(activeEventBadgeText(0)).isNull()
        assertThat(activeEventBadgeText(1)).isEqualTo("1")
        assertThat(activeEventBadgeText(99)).isEqualTo("99")
        assertThat(activeEventBadgeText(100)).isEqualTo("99+")
        assertThat(activeEventBadgeText(Int.MAX_VALUE)).isEqualTo("99+")
    }
}
