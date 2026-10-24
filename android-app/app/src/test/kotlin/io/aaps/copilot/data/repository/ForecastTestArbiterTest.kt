package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.service.ForecastTestSession
import io.aaps.copilot.service.ForecastTestStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ForecastTestArbiterTest {
    @Test fun preemptionSkipsOnlyOneAttemptAndTheMinuteSessionContinues() = runTest {
        val arbiter = ForecastTestArbiter()
        val session = ForecastTestSession { testScheduler.currentTime }
        var calls = 0
        val runner = launch { session.run(session.start(), { null }) {
            arbiter.test {
                if (calls++ == 0) awaitCancellation() else 1
            } != null
        } }
        runCurrent()
        arbiter.normal { }
        runCurrent()
        assertThat(session.state.value.active).isTrue()
        assertThat(session.state.value.status).isEqualTo(ForecastTestStatus.SKIPPED)
        advanceTimeBy(60_001)
        assertThat(session.state.value.completedCycles).isEqualTo(1)
        session.stop()
        runner.join()
    }

    @Test fun normalCyclePreemptsTestAndAcquiresTheReleasedLease() = runTest {
        val arbiter = ForecastTestArbiter()
        val lease = Mutex()
        var cleaned = false
        val test = async { arbiter.test<Int> { lease.withLock {
            try { awaitCancellation() } finally { cleaned = true }
        } } }
        runCurrent()
        assertThat(lease.isLocked).isTrue()
        val result = arbiter.normal {
            assertThat(cleaned).isTrue()
            AutomationRepository.runNormalCycleIfIdleStatic(lease) { 7 }
        }
        assertThat(test.await()).isNull()
        assertThat(result.acquired).isTrue()
        assertThat(result.value).isEqualTo(7)
        assertThat(arbiter.test { 8 }).isEqualTo(8)
    }

    @Test fun testCannotJumpAheadOfPendingNormalOrSettingsOperation() = runTest {
        val arbiter = ForecastTestArbiter()
        val lease = Mutex(locked = true)
        val normal = async { arbiter.normal { lease.withLock { 9 } } }
        runCurrent()
        assertThat(arbiter.test<Int> { error("test jumped queue") }).isNull()
        lease.unlock()
        assertThat(normal.await()).isEqualTo(9)
        assertThat(arbiter.test { 10 }).isEqualTo(10)
    }

    @Test fun cancellingNormalWaitDoesNotLeakAdmissionCountOrUnlockOthers() = runTest {
        val arbiter = ForecastTestArbiter()
        val lease = Mutex(locked = true)
        val normal = launch { arbiter.normal { lease.withLock { error("not acquired") } } }
        runCurrent()
        normal.cancelAndJoin()
        assertThat(lease.isLocked).isTrue()
        assertThat(arbiter.test { 1 }).isEqualTo(1)
        lease.unlock()
    }

    @Test fun externalCancellationStillPropagatesAndReleasesTestOwnership() = runTest {
        val arbiter = ForecastTestArbiter()
        val test = launch { arbiter.test<Int> { awaitCancellation() } }
        runCurrent()
        assertThat(arbiter.test { 0 }).isNull()
        test.cancelAndJoin()
        assertThat(test.isCancelled).isTrue()
        assertThat(arbiter.test { 1 }).isEqualTo(1)
    }

    @Test fun multipleNormalEntrantsKeepTestsOutUntilLastOneCompletes() = runTest {
        val arbiter = ForecastTestArbiter()
        val firstDone = CompletableDeferred<Unit>()
        val secondDone = CompletableDeferred<Unit>()
        val first = launch { arbiter.normal { firstDone.await() } }
        val second = launch { arbiter.normal { secondDone.await() } }
        runCurrent()
        firstDone.complete(Unit)
        first.join()
        assertThat(arbiter.test { 1 }).isNull()
        secondDone.complete(Unit)
        second.join()
        assertThat(arbiter.test { 2 }).isEqualTo(2)
    }

    @Test fun failuresReleaseBothKindsOfOwnership() = runTest {
        val arbiter = ForecastTestArbiter()
        assertThat(runCatching { arbiter.normal { error("normal failure") } }.isFailure).isTrue()
        assertThat(runCatching { arbiter.test { error("test failure") } }.isFailure).isTrue()
        assertThat(arbiter.test { 1 }).isEqualTo(1)
    }
}
