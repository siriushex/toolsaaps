package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GlucoseAlertMuteExpiryTest {

    @Test
    fun offActionsSetExactRequestedExpiryFromNow() {
        val now = 10_000L

        assertThat(glucoseAlertMuteExpiry(now, GlucoseAlertMuteOption.MINUTES_30))
            .isEqualTo(now + 30 * 60_000L)
        assertThat(glucoseAlertMuteExpiry(now, GlucoseAlertMuteOption.MINUTES_60))
            .isEqualTo(now + 60 * 60_000L)
    }

    @Test
    fun roomMuteExpiresExactlyAtBoundaryWithoutPolling() = runTest {
        val roomMutedUntil = MutableStateFlow(60_000L)
        val observed = mutableListOf<Long>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            roomMutedUntil.withExactMuteExpiry { testScheduler.currentTime }.collect(observed::add)
        }

        assertThat(observed).containsExactly(60_000L)
        advanceTimeBy(59_999L)
        runCurrent()
        assertThat(observed.last()).isEqualTo(60_000L)
        assertThat(glucoseAlertBellAction(observed.last(), testScheduler.currentTime))
            .isEqualTo(GlucoseAlertBellAction.RESUME)

        advanceTimeBy(1L)
        runCurrent()
        assertThat(observed.last()).isEqualTo(0L)
        assertThat(glucoseAlertBellAction(observed.last(), testScheduler.currentTime))
            .isEqualTo(GlucoseAlertBellAction.MUTE_30_MINUTES)

        collector.cancel()
    }
}
