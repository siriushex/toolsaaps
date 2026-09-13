package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.pump.PumpLinkAdapterState
import io.aaps.copilot.domain.pump.PumpLinkCondition
import io.aaps.copilot.domain.pump.PumpLinkDriverState
import io.aaps.copilot.domain.pump.PumpLinkSnapshot
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PumpLinkHealthMonitorTest {
    private val unmuted = PumpLinkMuteCoordinator { it(false) }
    private val start = 3_000_000L
    private val sample = PumpLinkSnapshot(12, 1_000L, 1, start, true, PumpLinkAdapterState.ON,
        PumpLinkDriverState.IDLE, false, start - 60_000L)

    private class MemoryStore(var record: PumpLinkRecord = PumpLinkRecord()) : PumpLinkRecordStore {
        var writes = 0
        override suspend fun load() = record
        override suspend fun save(record: PumpLinkRecord) { this.record = record; writes++ }
    }

    @Test fun repeatedFailureAndProcessRestartHaveOnlyOneNotice() = runTest {
        val store = MemoryStore()
        var notices = 0
        fun monitor() = PumpLinkHealthMonitor(backgroundScope, store, { start + testScheduler.currentTime }, { 12 },
            { notices++; PumpLinkNotice.DELIVERED }, {}, unmuted)
        val first = monitor()
        assertThat(first.accept(sample.copy(driverState = PumpLinkDriverState.ERROR))).isTrue()
        first.accept(sample.copy(sequence = 2, driverState = PumpLinkDriverState.ERROR))
        val restarted = monitor()
        restarted.refresh()
        restarted.accept(sample.copy(sequence = 3, driverState = PumpLinkDriverState.ERROR))
        assertThat(notices).isEqualTo(1)
        assertThat(store.record.episode?.notice).isEqualTo(PumpLinkNotice.DELIVERED)
    }

    @Test fun episodeSeenDuringMuteIsNotReplayedAfterMuteExpires() = runTest {
        val store = MemoryStore()
        var muted = true
        var attempts = 0
        var posts = 0
        val monitor = PumpLinkHealthMonitor(backgroundScope, store, { start + testScheduler.currentTime }, { 12 }, {
            attempts++
            if (muted) PumpLinkNotice.SUPPRESSED else { posts++; PumpLinkNotice.DELIVERED }
        }, {}, unmuted)
        monitor.accept(sample.copy(driverState = PumpLinkDriverState.ERROR))
        muted = false
        monitor.accept(sample.copy(sequence = 2, driverState = PumpLinkDriverState.ERROR))
        assertThat(attempts).isEqualTo(1)
        assertThat(posts).isEqualTo(0)
    }

    @Test fun retryIsNotRecoveryButNewResponseClosesEpisode() = runTest {
        val store = MemoryStore()
        var clears = 0
        val monitor = PumpLinkHealthMonitor(backgroundScope, store, { start + testScheduler.currentTime }, { 12 },
            { PumpLinkNotice.DELIVERED }, { clears++ }, unmuted)
        monitor.accept(sample.copy(driverState = PumpLinkDriverState.ERROR))
        monitor.accept(sample.copy(sequence = 2))
        assertThat(monitor.state.value).isEqualTo(PumpLinkUiStatus(PumpLinkCondition.VERIFYING, true))
        monitor.accept(sample.copy(sequence = 3, lastVerifiedStatusElapsedMs = start))
        assertThat(monitor.state.value.condition).isEqualTo(PumpLinkCondition.HEALTHY)
        assertThat(store.record.episode).isNull()
        assertThat(clears).isEqualTo(1)
    }

    @Test fun oneDeadlineDetectsMissingHeartbeatWithoutPolling() = runTest {
        val store = MemoryStore()
        var notices = 0
        val monitor = PumpLinkHealthMonitor(backgroundScope, store, { start + testScheduler.currentTime }, { 12 },
            { notices++; PumpLinkNotice.DELIVERED }, {}, unmuted)
        monitor.accept(sample)
        advanceTimeBy(450_000L)
        runCurrent()
        assertThat(monitor.state.value.condition).isEqualTo(PumpLinkCondition.AAPS_UNREACHABLE)
        val writes = store.writes
        advanceTimeBy(3_600_000L)
        runCurrent()
        assertThat(notices).isEqualTo(1)
        assertThat(store.writes).isEqualTo(writes)
    }

    @Test fun newSessionWithPreSessionProofCannotCloseExistingEpisode() = runTest {
        val store = MemoryStore()
        var clears = 0
        val monitor = PumpLinkHealthMonitor(backgroundScope, store, { start }, { 12 },
            { PumpLinkNotice.DELIVERED }, { clears++ }, unmuted)
        monitor.accept(sample.copy(driverState = PumpLinkDriverState.ERROR))
        val before = store.record
        val writes = store.writes
        val newSession = sample.copy(sessionStartedElapsedMs = start - 10_000L,
            lastVerifiedStatusElapsedMs = start - 30_000L)
        assertThat(monitor.accept(newSession)).isFalse()
        assertThat(store.record).isEqualTo(before)
        assertThat(store.writes).isEqualTo(writes)
        assertThat(monitor.state.value).isEqualTo(PumpLinkUiStatus(PumpLinkCondition.DRIVER_ERROR, true))
        assertThat(clears).isEqualTo(0)

        assertThat(monitor.accept(newSession.copy(lastVerifiedStatusElapsedMs = start))).isTrue()
        assertThat(monitor.state.value.condition).isEqualTo(PumpLinkCondition.HEALTHY)
        assertThat(store.record.episode).isNull()
        assertThat(clears).isEqualTo(1)
    }

    @Test fun neverSupportedAndNewBootWithoutPacketRemainUnknown() = runTest {
        for (initial in listOf(PumpLinkRecord(), PumpLinkRecord(snapshot = sample.copy(bootCount = 11)))) {
            val store = MemoryStore(initial)
            var notices = 0
            val monitor = PumpLinkHealthMonitor(backgroundScope, store, { start + testScheduler.currentTime }, { 12 },
                { notices++; PumpLinkNotice.DELIVERED }, {}, unmuted)
            monitor.refresh()
            advanceTimeBy(1_800_000L)
            runCurrent()
            assertThat(monitor.state.value.condition).isEqualTo(PumpLinkCondition.UNKNOWN)
            assertThat(notices).isEqualTo(0)
        }
    }

    @Test fun rejectedPacketCannotRefreshHeartbeatOrWriteState() = runTest {
        val store = MemoryStore(PumpLinkRecord(sample))
        val monitor = PumpLinkHealthMonitor(backgroundScope, store, { start }, { 12 }, { PumpLinkNotice.DELIVERED }, {}, unmuted)
        assertThat(monitor.accept(sample)).isFalse()
        assertThat(store.writes).isEqualTo(0)
    }

    @Test fun cancellationAfterDurableClaimDoesNotRepeatNotificationOnRestart() = runTest {
        val store = MemoryStore()
        var attempts = 0
        val first = PumpLinkHealthMonitor(backgroundScope, store, { start }, { 12 }, { attempts++; awaitCancellation() }, {}, unmuted)
        val action = launch { first.accept(sample.copy(driverState = PumpLinkDriverState.ERROR)) }
        runCurrent()
        action.cancelAndJoin()
        assertThat(store.record.episode?.notice).isEqualTo(PumpLinkNotice.CLAIMED)
        val restarted = PumpLinkHealthMonitor(backgroundScope, store, { start }, { 12 }, { attempts++; PumpLinkNotice.DELIVERED }, {}, unmuted)
        restarted.refresh()
        assertThat(attempts).isEqualTo(1)
    }

    @Test fun slowNotificationDoesNotPostponeHeartbeatDeadline() = runTest {
        val monitor = PumpLinkHealthMonitor(backgroundScope, MemoryStore(), { start + testScheduler.currentTime }, { 12 },
            { delay(1_000L); PumpLinkNotice.DELIVERED }, {}, unmuted)
        val action = launch { monitor.accept(sample.copy(driverState = PumpLinkDriverState.ERROR)) }
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        action.join()
        advanceTimeBy(449_000L)
        runCurrent()
        assertThat(monitor.state.value.condition).isEqualTo(PumpLinkCondition.AAPS_UNREACHABLE)
    }

    @Test fun storageFailureCannotLeaveAnApparentlyWorkingMonitor() = runTest {
        val store = object : PumpLinkRecordStore {
            override suspend fun load() = PumpLinkRecord()
            override suspend fun save(record: PumpLinkRecord) { throw IOException("test storage failure") }
        }
        var notices = 0
        val monitor = PumpLinkHealthMonitor(backgroundScope, store, { start }, { 12 }, { notices++; PumpLinkNotice.DELIVERED }, {}, unmuted)
        val failure = runCatching { monitor.accept(sample) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(monitor.state.value.condition.name).isEqualTo("MONITOR_UNAVAILABLE")
        assertThat(notices).isEqualTo(0)
    }

    @Test fun muteDispositionIsDurableEvenWhenProcessStopsBeforeDeliveryClaim() = runTest {
        var crashAfterFaultSave = true
        val store = object : PumpLinkRecordStore {
            var record = PumpLinkRecord()
            override suspend fun load() = record
            override suspend fun save(record: PumpLinkRecord) {
                this.record = record
                if (crashAfterFaultSave && record.snapshot?.driverState == PumpLinkDriverState.ERROR) throw CancellationException("simulated process stop")
            }
        }
        var posts = 0
        val first = PumpLinkHealthMonitor(backgroundScope, store, { start }, { 12 },
            { posts++; PumpLinkNotice.DELIVERED }, {}, PumpLinkMuteCoordinator { it(true) })
        runCatching { first.accept(sample.copy(driverState = PumpLinkDriverState.ERROR)) }
        crashAfterFaultSave = false
        val restartedAfterMute = PumpLinkHealthMonitor(backgroundScope, store, { start }, { 12 },
            { posts++; PumpLinkNotice.DELIVERED }, {}, unmuted)
        restartedAfterMute.refresh()
        assertThat(store.record.episode?.notice).isEqualTo(PumpLinkNotice.SUPPRESSED)
        assertThat(posts).isEqualTo(0)
    }

    @Test fun cancelledSaveRehydratesDurableMuteBeforeNextPacketInSameProcess() = runTest {
        var cancelSave = true
        var muted = true
        val store = object : PumpLinkRecordStore {
            var record = PumpLinkRecord()
            override suspend fun load() = record
            override suspend fun save(record: PumpLinkRecord) {
                this.record = record
                if (cancelSave) throw CancellationException("save completed as receiver expired")
            }
        }
        var posts = 0
        val monitor = PumpLinkHealthMonitor(backgroundScope, store, { start }, { 12 },
            { posts++; PumpLinkNotice.DELIVERED }, {}, PumpLinkMuteCoordinator { it(muted) })
        runCatching { monitor.accept(sample.copy(driverState = PumpLinkDriverState.ERROR)) }
        cancelSave = false
        muted = false
        monitor.accept(sample.copy(sequence = 2, driverState = PumpLinkDriverState.ERROR))
        assertThat(posts).isEqualTo(0)
        assertThat(store.record.episode?.notice).isEqualTo(PumpLinkNotice.SUPPRESSED)
    }

    @Test fun intentionalDisconnectClearsVisibleFaultWithoutClaimingRecovery() = runTest {
        val store = MemoryStore()
        var posts = 0
        var clears = 0
        val monitor = PumpLinkHealthMonitor(backgroundScope, store, { start }, { 12 },
            { posts++; PumpLinkNotice.DELIVERED }, { clears++ }, unmuted)
        monitor.accept(sample.copy(driverState = PumpLinkDriverState.ERROR))
        monitor.accept(sample.copy(sequence = 2, intentionalDisconnect = true))
        assertThat(monitor.state.value.condition).isEqualTo(PumpLinkCondition.INTENTIONALLY_DISCONNECTED)
        assertThat(clears).isEqualTo(1)
        assertThat(store.record.episode).isNotNull()
        monitor.accept(sample.copy(sequence = 3, driverState = PumpLinkDriverState.ERROR))
        assertThat(posts).isEqualTo(1)
    }

    @Test fun stalledStartupStorageReleasesGlobalMuteBoundaryWithinEightSeconds() = runTest {
        val global = Mutex()
        val store = object : PumpLinkRecordStore {
            override suspend fun load(): PumpLinkRecord = awaitCancellation()
            override suspend fun save(record: PumpLinkRecord) = error("not reached")
        }
        val monitor = PumpLinkHealthMonitor(backgroundScope, store, { start }, { 12 },
            { error("no notification") }, {}, PumpLinkMuteCoordinator { op -> global.withLock { op(false) } })
        val refresh = backgroundScope.launch { runCatching { monitor.refresh() } }
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()
        assertThat(refresh.isCompleted).isTrue()
        val released = global.tryLock()
        if (released) global.unlock()
        assertThat(released).isTrue()
        assertThat(monitor.state.value.condition).isEqualTo(PumpLinkCondition.MONITOR_UNAVAILABLE)
    }

    @Test fun failedOlderCoordinationCannotInvalidateANewerAcceptedHeartbeat() = runTest {
        var calls = 0
        val releaseFailure = CompletableDeferred<Unit>()
        val coordinator = PumpLinkMuteCoordinator { operation ->
            if (++calls == 1) {
                releaseFailure.await()
                throw IOException("older coordination failed")
            }
            operation(false)
        }
        var notices = 0
        val monitor = PumpLinkHealthMonitor(backgroundScope, MemoryStore(),
            { start + testScheduler.currentTime }, { 12 }, { notices++; PumpLinkNotice.DELIVERED }, {}, coordinator)
        val earlier = backgroundScope.launch { runCatching { monitor.refresh() } }
        runCurrent()
        monitor.accept(sample)
        releaseFailure.complete(Unit)
        earlier.join()
        assertThat(monitor.state.value.condition).isEqualTo(PumpLinkCondition.HEALTHY)
        advanceTimeBy(450_000)
        runCurrent()
        assertThat(notices).isEqualTo(1)
        assertThat(monitor.state.value.condition).isEqualTo(PumpLinkCondition.AAPS_UNREACHABLE)
    }
}
