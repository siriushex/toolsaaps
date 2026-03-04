package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IsfCrDroppedReasonFormatterTest {

    @Test
    fun formatSummaryLines_includesCrIntegrityBreakdownWhenReasonsPresent() {
        val lines = formatIsfCrDroppedReasonSummaryLines(
            eventCount = 12,
            droppedTotal = 20,
            reasonCounts = mapOf(
                "cr_sensor_blocked" to 5,
                "cr_gross_gap" to 3,
                "cr_uam_ambiguity" to 2,
                "isf_small_units" to 8
            )
        )

        assertThat(lines.first()).isEqualTo("Events=12, dropped total=20")
        assertThat(lines).contains(
            "CR integrity drops: gap=15% (3), sensorBlocked=25% (5), uamAmbiguity=10% (2)"
        )
        assertThat(lines).contains("isf_small_units=8")
        assertThat(lines).contains("cr_sensor_blocked=5")
    }

    @Test
    fun formatSummaryLines_withNoCountersReturnsHeaderOnly() {
        val lines = formatIsfCrDroppedReasonSummaryLines(
            eventCount = 3,
            droppedTotal = 0,
            reasonCounts = emptyMap()
        )

        assertThat(lines).containsExactly("Events=3, dropped total=0, no reason counters")
    }
}
