package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ForecastTestSessionTest {
    @Test fun disabledByDefaultAndNewProcessDoesNotResume() = runTest {
        val session = ForecastTestSession { testScheduler.currentTime }
        assertThat(session.state.value.active).isFalse()
        session.start()
        assertThat(ForecastTestSession { testScheduler.currentTime }.state.value.active).isFalse()
    }

    @Test fun cadenceIsOneMinuteWithoutAdditionalOwners() = runTest {
        val session = ForecastTestSession { testScheduler.currentTime }
        val id = session.start()
        val starts = mutableListOf<Long>()
        val runner = launch { session.run(id, { null }) { starts += testScheduler.currentTime; delay(2_000); true } }
        runCurrent()
        assertThat(session.start()).isEqualTo(id)
        session.run(id, { null }) { error("duplicate owner") }
        advanceTimeBy(122_001)
        assertThat(starts).containsExactly(0L, 60_000L, 120_000L).inOrder()
        assertThat(session.state.value.completedCycles).isEqualTo(3)
        assertThat(session.state.value.lastDurationMs).isEqualTo(2_000)
        session.stop()
        runner.join()
    }

    @Test fun disableCancelsInFlightAndNoMoreTicksOccur() = runTest {
        val session = ForecastTestSession { testScheduler.currentTime }
        var cleaned = false
        var calls = 0
        val id = session.start()
        val runner = launch { session.run(id, { null }) {
            calls++
            try { awaitCancellation() } finally { cleaned = true }
        } }
        runCurrent()
        session.stop()
        runner.join()
        advanceTimeBy(180_000)
        assertThat(cleaned).isTrue()
        assertThat(calls).isEqualTo(1)
        assertThat(session.state.value.status).isEqualTo(ForecastTestStatus.OFF)
        assertThat(session.state.value.completedCycles).isEqualTo(0)
    }

    @Test fun timeoutReleasesCalculationAndWaitsForNextSlot() = runTest {
        val session = ForecastTestSession { testScheduler.currentTime }
        var calls = 0
        var cleaned = 0
        val runner = launch { session.run(session.start(), { null }) {
            calls++
            try { awaitCancellation() } finally { cleaned++ }
        } }
        advanceTimeBy(45_001)
        assertThat(session.state.value.status).isEqualTo(ForecastTestStatus.TIMEOUT)
        assertThat(cleaned).isEqualTo(1)
        assertThat(session.state.value.completedCycles).isEqualTo(0)
        advanceTimeBy(15_000)
        assertThat(calls).isEqualTo(2)
        session.stop()
        runner.join()
    }

    @Test fun exceptionAndBusyAttemptsAreNotReportedAsSuccess() = runTest {
        val session = ForecastTestSession { testScheduler.currentTime }
        var calls = 0
        val runner = launch { session.run(session.start(), { null }) {
            if (calls++ == 0) error("synthetic failure") else false
        } }
        runCurrent()
        assertThat(session.state.value.status).isEqualTo(ForecastTestStatus.FAILED)
        advanceTimeBy(60_001)
        assertThat(session.state.value.status).isEqualTo(ForecastTestStatus.SKIPPED)
        assertThat(session.state.value.completedCycles).isEqualTo(0)
        session.stop()
        runner.join()
    }

    @Test fun expiryUsesElapsedTimeAndNeverRunsSixtyFirstCycle() = runTest {
        val session = ForecastTestSession { testScheduler.currentTime }
        var calls = 0
        val runner = launch { session.run(session.start(), { null }) { calls++; true } }
        advanceTimeBy(ForecastTestSession.SESSION_MS + 1)
        assertThat(session.state.value.status).isEqualTo(ForecastTestStatus.EXPIRED)
        assertThat(session.state.value.active).isFalse()
        assertThat(calls).isEqualTo(60)
        runner.join()
    }

    @Test fun thermalStopInterruptsCalculationWithoutWaitingForNextMinute() = runTest {
        val session = ForecastTestSession { testScheduler.currentTime }
        var stop: ForecastTestStatus? = null
        val runner = launch { session.run(session.start(), { stop }) { awaitCancellation() } }
        runCurrent()
        stop = ForecastTestStatus.THERMAL
        advanceTimeBy(1_001)
        assertThat(session.state.value.status).isEqualTo(ForecastTestStatus.THERMAL)
        assertThat(session.state.value.active).isFalse()
        runner.join()
    }

    @Test fun powerSaveBeforeFirstCyclePreventsCalculation() = runTest {
        val session = ForecastTestSession { testScheduler.currentTime }
        val runner = launch { session.run(session.start(), { ForecastTestStatus.POWER_SAVE }) {
            error("must not run")
        } }
        runner.join()
        assertThat(session.state.value.status).isEqualTo(ForecastTestStatus.POWER_SAVE)
    }

    @Test fun ownerExitDisablesTestAndOldCompletionCannotStopNewSession() = runTest {
        val session = ForecastTestSession { testScheduler.currentTime }
        val old = session.start()
        val runner = launch { session.run(old, { null }) { awaitCancellation() } }
        runCurrent()
        runner.cancelAndJoin()
        assertThat(session.state.value.status).isEqualTo(ForecastTestStatus.UNAVAILABLE)
        val next = session.start()
        session.stop(ForecastTestStatus.FAILED, old)
        session.run(old, { null }) { error("old session") }
        assertThat(session.state.value.sessionId).isEqualTo(next)
        assertThat(session.state.value.active).isTrue()
        session.stop()
    }

    @Test fun noCatchUpBurstAfterElapsedClockJump() = runTest {
        var extra = 0L
        val session = ForecastTestSession { testScheduler.currentTime + extra }
        var calls = 0
        val runner = launch { session.run(session.start(), { null }) {
            calls++
            if (calls == 1) extra = 5 * 60_000L + 10_000
            true
        } }
        runCurrent()
        advanceTimeBy(49_999)
        assertThat(calls).isEqualTo(1)
        advanceTimeBy(2)
        assertThat(calls).isEqualTo(2)
        session.stop()
        runner.join()
    }
}
