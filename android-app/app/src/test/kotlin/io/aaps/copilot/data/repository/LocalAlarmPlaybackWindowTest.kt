package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.alerts.*
import org.junit.Test

class LocalAlarmPlaybackWindowTest {
    private val cycle = LocalAlarmCycle(LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, "source-a"),
        1, 1, LocalAlarmLevel.WARNING_30, 1, 1_000, 56_000)

    @Test fun softWindowsAreAbsoluteAndTheLastEndsAtTheCycleDeadline() {
        val first = requireNotNull(LocalAlarmPlaybackWindow.create(cycle, 0, 2_000))
        assertThat(first.remainingStartMs(1_000)).isEqualTo(15_000)
        assertThat(first.remainingStartMs(16_000)).isNull()
        val last = requireNotNull(LocalAlarmPlaybackWindow.create(cycle, 3, 5_000))
        assertThat(last.remainingStartMs(45_999)).isNull()
        assertThat(last.remainingStartMs(46_000)).isEqualTo(10_000)
        assertThat(last.playDurationMs(54_000)).isEqualTo(2_000)
        assertThat(last.playDurationMs(56_000)).isNull()
    }

    @Test fun onlyTheFirstStrongStepStartsOneClip() {
        val strong = cycle.copy(level = LocalAlarmLevel.LOW_NOW)
        assertThat(LocalAlarmPlaybackWindow.create(strong, 0, 20_000)?.remainingStartMs(1_000)).isEqualTo(5_000)
        (1..3).forEach { assertThat(LocalAlarmPlaybackWindow.create(strong, it, 20_000)).isNull() }
    }

    @Test fun malformedCycleSilentSourceWrongLevelAndDurationFailClosed() {
        listOf(cycle.copy(generation = 0), cycle.copy(ordinal = 0), cycle.copy(bootCount = -1),
            cycle.copy(startedElapsedMs = -1), cycle.copy(deadlineElapsedMs = 56_001),
            cycle.copy(level = LocalAlarmLevel.WATCH_60),
            cycle.copy(key = cycle.key.copy(kind = LocalAlarmSourceKind.PUMP_LINK))).forEach {
            assertThat(LocalAlarmPlaybackWindow.create(it, 0, 2_000)).isNull()
        }
        listOf(0, 999, 5_001, Int.MAX_VALUE).forEach {
            assertThat(LocalAlarmPlaybackWindow.create(cycle, 0, it)).isNull()
        }
        listOf(-1, 4, Int.MAX_VALUE).forEach {
            assertThat(LocalAlarmPlaybackWindow.create(cycle, it, 2_000)).isNull()
        }
    }

    @Test fun nearLongMaximumDoesNotOverflow() {
        val end = cycle.copy(startedElapsedMs = Long.MAX_VALUE - 55_000, deadlineElapsedMs = Long.MAX_VALUE)
        val window = requireNotNull(LocalAlarmPlaybackWindow.create(end, 3, 5_000))
        assertThat(window.remainingStartMs(Long.MAX_VALUE - 1)).isEqualTo(1)
        assertThat(window.playDurationMs(Long.MAX_VALUE - 1)).isEqualTo(1)
    }
}
