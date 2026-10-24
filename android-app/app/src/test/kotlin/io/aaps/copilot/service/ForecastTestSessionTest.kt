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

    @Test fun continuesEveryMinuteForSixHoursUntilManualStop() = runTest {
        val session = ForecastTestSession { testScheduler.currentTime }
        val starts = mutableListOf<Long>()
        val runner = launch { session.run(session.start(), { null }) { starts += testScheduler.currentTime; true } }
        try {
            advanceTimeBy(6 * 60 * 60_000L + 1)
            assertThat(session.state.value.active).isTrue()
            assertThat(starts).hasSize(361)
            assertThat(starts.zipWithNext().all { (previous, next) -> next - previous == 60_000L }).isTrue()
            assertThat(session.state.value.completedCycles).isEqualTo(361)
            assertThat(session.state.value.elapsedMinutes).isEqualTo(360L)
        } finally {
            session.stop()
            runner.join()
        }
        advanceTimeBy(120_001)
        assertThat(starts).hasSize(361)
        assertThat(session.state.value.status).isEqualTo(ForecastTestStatus.OFF)
        assertThat(session.state.value.active).isFalse()
    }

    @Test fun thermalStopStillInterruptsAfterFormerHourLimitAndNeverAutoResumes() = runTest {
        val session = ForecastTestSession { testScheduler.currentTime }
        var stop: ForecastTestStatus? = null
        var calls = 0
        var cancelled = false
        val runner = launch { session.run(session.start(), { stop }) {
            calls++
            if (calls == 62) try { awaitCancellation() } finally { cancelled = true }
            true
        } }
        try {
            advanceTimeBy(61 * 60_000L + 1)
            assertThat(session.state.value.status).isEqualTo(ForecastTestStatus.CALCULATING)
            stop = ForecastTestStatus.THERMAL
            advanceTimeBy(1_001)
            assertThat(cancelled).isTrue()
            assertThat(session.state.value.status).isEqualTo(ForecastTestStatus.THERMAL)
            assertThat(session.state.value.active).isFalse()
            stop = null
            advanceTimeBy(120_000)
            assertThat(calls).isEqualTo(62)
            assertThat(session.state.value.active).isFalse()
        } finally {
            session.stop()
            runner.join()
        }
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

    @Test fun backwardsMonotonicClockFailsClosedWithoutNewCalculation() = runTest {
        var now = 10_000L
        val session = ForecastTestSession { now }
        var calls = 0
        val runner = launch { session.run(session.start(), { null }) { calls++; true } }
        runCurrent()
        now = 9_000L
        advanceTimeBy(1_001)
        assertThat(session.state.value.status).isEqualTo(ForecastTestStatus.UNAVAILABLE)
        assertThat(session.state.value.active).isFalse()
        assertThat(calls).isEqualTo(1)
        runner.join()
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
