package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.pump.PumpLinkAdapterState
import io.aaps.copilot.domain.pump.PumpLinkCondition
import io.aaps.copilot.domain.pump.PumpLinkDriverState
import io.aaps.copilot.domain.pump.PumpLinkHealthPolicy
import io.aaps.copilot.domain.pump.PumpLinkSnapshot
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PumpLinkAlarmSourceTest {
    private val start = 3_000_000L
    private val fault = PumpLinkSnapshot(12, 1_000L, 1, start, true, PumpLinkAdapterState.ON,
        PumpLinkDriverState.ERROR, false, start - 60_000L)
    private val unmuted = PumpLinkMuteCoordinator { it(false) }

    private class Store : PumpLinkRecordStore {
        var record = PumpLinkRecord()
        var beforeSave: suspend () -> Unit = {}
        override suspend fun load() = record
        override suspend fun save(record: PumpLinkRecord) { beforeSave(); this.record = record }
    }

    @Test fun constructorHasNoSourceAndConfirmedFaultCarriesExactDurableProvenance() = runTest {
        val store = Store()
        val monitor = PumpLinkHealthMonitor(backgroundScope, store, { start }, { 12 },
            { PumpLinkNotice.DELIVERED }, {}, unmuted)
        assertThat(monitor.alarmSource.value).isNull()
        assertThat(monitor.currentAlarmSource()).isNull()
        monitor.accept(fault)
        val source = requireNotNull(monitor.currentAlarmSource())
        assertThat(source.snapshot).isEqualTo(store.record.snapshot)
        assertThat(source.episode).isEqualTo(store.record.episode)
        assertThat(source.condition).isEqualTo(PumpLinkCondition.DRIVER_ERROR)
        assertThat(source.evaluatedElapsedMs).isEqualTo(start)
        assertThat(source.nextEvaluationElapsedMs).isEqualTo(start + PumpLinkHealthPolicy.HEARTBEAT_TIMEOUT_MS)
    }

    @Test fun sourceIsNotPublishedWhileInitialSaveOrNoticeIsSuspended() = runTest {
        val store = Store()
        val saving = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<Unit>()
        val posting = CompletableDeferred<Unit>()
        val posted = CompletableDeferred<Unit>()
        store.beforeSave = { saving.complete(Unit); saved.await() }
        val monitor = PumpLinkHealthMonitor(backgroundScope, store, { start }, { 12 },
            { posting.complete(Unit); posted.await(); PumpLinkNotice.DELIVERED }, {}, unmuted)
        val accept = launch { monitor.accept(fault) }
        saving.await()
        assertThat(monitor.alarmSource.value).isNull()
        saved.complete(Unit)
        posting.await()
        assertThat(monitor.alarmSource.value).isNull()
        posted.complete(Unit)
        accept.join()
        assertThat(monitor.currentAlarmSource()).isNotNull()
    }

    @Test fun rejectedDuplicateCannotRepublishOrMoveAcceptedEvaluation() = runTest {
        val monitor = PumpLinkHealthMonitor(backgroundScope, Store(), { start + testScheduler.currentTime }, { 12 },
            { PumpLinkNotice.DELIVERED }, {}, unmuted)
        monitor.accept(fault)
        val source = monitor.alarmSource.value
        advanceTimeBy(1_000)
        assertThat(monitor.accept(fault)).isFalse()
        assertThat(monitor.alarmSource.value).isSameInstanceAs(source)
    }

    @Test fun freshPacketsPreserveEpisodeOriginWithoutInventingAGeneration() = runTest {
        val monitor = PumpLinkHealthMonitor(backgroundScope, Store(), { start + testScheduler.currentTime }, { 12 },
            { PumpLinkNotice.DELIVERED }, {}, unmuted)
        monitor.accept(fault)
        val episode = monitor.alarmSource.value!!.episode
        advanceTimeBy(1_000)
        monitor.accept(fault.copy(sequence = 2, sampledElapsedMs = start + 1_000))
        assertThat(monitor.alarmSource.value!!.episode).isEqualTo(episode)
        assertThat(monitor.alarmSource.value!!.snapshot.sequence).isEqualTo(2)
        assertThat(monitor.alarmSource.value!!.evaluatedElapsedMs).isEqualTo(start + 1_000)
    }

    @Test fun supersedingPacketWithdrawsSourceBeforeASuspendingSave() = runTest {
        val store = Store()
        val monitor = PumpLinkHealthMonitor(backgroundScope, store, { start }, { 12 },
            { PumpLinkNotice.DELIVERED }, {}, unmuted)
        monitor.accept(fault)
        val saving = CompletableDeferred<Unit>()
        store.beforeSave = { saving.complete(Unit); awaitCancellation() }
        val accept = launch { monitor.accept(fault.copy(sequence = 2, adapterState = PumpLinkAdapterState.OFF)) }
        saving.await()
        assertThat(monitor.alarmSource.value).isNull()
        accept.cancelAndJoin()
        assertThat(monitor.currentAlarmSource()).isNull()
    }

    @Test fun storageFailureWithdrawsOldSourceEvenWhenEpisodeRemainsOpen() = runTest {
        val store = Store()
        val monitor = PumpLinkHealthMonitor(backgroundScope, store, { start }, { 12 },
            { PumpLinkNotice.DELIVERED }, {}, unmuted)
        monitor.accept(fault)
        store.beforeSave = { throw IOException("synthetic storage failure") }
        assertThat(runCatching { monitor.accept(fault.copy(sequence = 2)) }.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(store.record.episode).isNotNull()
        assertThat(monitor.alarmSource.value).isNull()
        assertThat(monitor.currentAlarmSource()).isNull()
    }

    @Test fun recoveryAndUnconfirmedRecoveryCannotRetainFaultAuthority() = runTest {
        val monitor = PumpLinkHealthMonitor(backgroundScope, Store(), { start }, { 12 },
            { PumpLinkNotice.DELIVERED }, {}, unmuted)
        monitor.accept(fault)
        monitor.accept(fault.copy(sequence = 2, driverState = PumpLinkDriverState.IDLE))
        assertThat(monitor.state.value).isEqualTo(PumpLinkUiStatus(PumpLinkCondition.VERIFYING, true))
        assertThat(monitor.currentAlarmSource()).isNull()
        monitor.accept(fault.copy(sequence = 3, driverState = PumpLinkDriverState.IDLE, lastVerifiedStatusElapsedMs = start))
        assertThat(monitor.state.value.condition).isEqualTo(PumpLinkCondition.HEALTHY)
        assertThat(monitor.alarmSource.value).isNull()
    }

    @Test fun intentionalDisconnectStaysSilentEvenAfterHeartbeatTimeout() = runTest {
        val monitor = PumpLinkHealthMonitor(backgroundScope, Store(), { start + testScheduler.currentTime }, { 12 },
            { PumpLinkNotice.DELIVERED }, {}, unmuted)
        monitor.accept(fault)
        monitor.accept(fault.copy(sequence = 2, intentionalDisconnect = true))
        assertThat(monitor.alarmSource.value).isNull()
        advanceTimeBy(PumpLinkHealthPolicy.HEARTBEAT_TIMEOUT_MS)
        runCurrent()
        assertThat(monitor.currentAlarmSource()).isNull()
    }

    @Test fun getterRejectsOldConditionAtDeadlineBeforeTimerRuns() = runTest {
        var now = start
        val monitor = PumpLinkHealthMonitor(backgroundScope, Store(), { now }, { 12 },
            { PumpLinkNotice.DELIVERED }, {}, unmuted)
        monitor.accept(fault)
        now += PumpLinkHealthPolicy.HEARTBEAT_TIMEOUT_MS
        assertThat(monitor.alarmSource.value).isNotNull()
        assertThat(monitor.currentAlarmSource()).isNull()
        monitor.refresh()
        assertThat(monitor.currentAlarmSource()!!.condition).isEqualTo(PumpLinkCondition.AAPS_UNREACHABLE)
        assertThat(monitor.currentAlarmSource()!!.nextEvaluationElapsedMs).isNull()
    }

    @Test fun bootChangeBackwardTimeAndClockFailureDenyCurrentRead() = runTest {
        var now = start
        var boot = 12
        var failure = false
        val monitor = PumpLinkHealthMonitor(backgroundScope, Store(), { if (failure) error("synthetic clock failure") else now }, { boot },
            { PumpLinkNotice.DELIVERED }, {}, unmuted)
        monitor.accept(fault)
        boot = 13
        assertThat(monitor.currentAlarmSource()).isNull()
        boot = 12
        now--
        assertThat(monitor.currentAlarmSource()).isNull()
        now = start
        failure = true
        assertThat(monitor.currentAlarmSource()).isNull()
    }

    @Test fun mutedTransitionAndExplicitOffRevokeWithoutWaitingForMonitorLock() = runTest {
        var muted = false
        val monitor = PumpLinkHealthMonitor(backgroundScope, Store(), { start }, { 12 },
            { PumpLinkNotice.DELIVERED }, {}, PumpLinkMuteCoordinator { it(muted) })
        monitor.accept(fault)
        monitor.invalidateAlarmSource()
        assertThat(monitor.alarmSource.value).isNull()
        assertThat(monitor.state.value.episodeOpen).isTrue()
        muted = true
        monitor.refresh()
        assertThat(monitor.currentAlarmSource()).isNull()
        muted = false
        assertThat(monitor.currentAlarmSource()).isNull()
        monitor.refresh()
        assertThat(monitor.currentAlarmSource()).isNotNull()
    }

    @Test fun unsupportedUnpairedUnknownAndMissingProvenanceNeverPublish() = runTest {
        for (packet in listOf(fault.copy(supported = false), fault.copy(driverState = PumpLinkDriverState.UNPAIRED),
            fault.copy(driverState = PumpLinkDriverState.UNKNOWN))) {
            val monitor = PumpLinkHealthMonitor(backgroundScope, Store(), { start }, { 12 },
                { PumpLinkNotice.DELIVERED }, {}, unmuted)
            monitor.accept(packet)
            assertThat(monitor.currentAlarmSource()).isNull()
        }
        val empty = PumpLinkHealthMonitor(backgroundScope, Store(), { start }, { 12 },
            { PumpLinkNotice.DELIVERED }, {}, unmuted)
        empty.refresh()
        assertThat(empty.alarmSource.value).isNull()
    }

    @Test fun getterPropagatesCancellationRatherThanTreatingItAsClockFailure() = runTest {
        var cancelled = false
        val monitor = PumpLinkHealthMonitor(backgroundScope, Store(),
            { if (cancelled) throw CancellationException("synthetic cancellation") else start }, { 12 },
            { PumpLinkNotice.DELIVERED }, {}, unmuted)
        monitor.accept(fault)
        cancelled = true
        assertThat(runCatching { monitor.currentAlarmSource() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }

    @Test fun invalidationDuringClockReadCannotReturnCapturedOldSource() = runTest {
        var revoke = false
        lateinit var monitor: PumpLinkHealthMonitor
        monitor = PumpLinkHealthMonitor(backgroundScope, Store(), { start },
            { if (revoke) monitor.invalidateAlarmSource(); 12 },
            { PumpLinkNotice.DELIVERED }, {}, unmuted)
        monitor.accept(fault)
        revoke = true
        assertThat(monitor.currentAlarmSource()).isNull()
        assertThat(monitor.alarmSource.value).isNull()
    }

    @Test fun failedOlderGlobalWaitDoesNotWithdrawANewerAcceptedFault() = runTest {
        var calls = 0
        val release = CompletableDeferred<Unit>()
        val monitor = PumpLinkHealthMonitor(backgroundScope, Store(), { start }, { 12 },
            { PumpLinkNotice.DELIVERED }, {}, PumpLinkMuteCoordinator {
                if (++calls == 1) { release.await(); throw IOException("synthetic older wait failure") }
                it(false)
            })
        val old = launch { runCatching { monitor.refresh() } }
        runCurrent()
        monitor.accept(fault)
        val source = monitor.currentAlarmSource()
        release.complete(Unit)
        old.join()
        assertThat(monitor.currentAlarmSource()).isSameInstanceAs(source)
    }

    @Test fun expiredConditionDuringDurableNoticeCannotPublishLateAuthority() = runTest {
        var now = start
        val monitor = PumpLinkHealthMonitor(backgroundScope, Store(), { now }, { 12 },
            { now += PumpLinkHealthPolicy.HEARTBEAT_TIMEOUT_MS; PumpLinkNotice.DELIVERED }, {}, unmuted)
        monitor.accept(fault)
        assertThat(monitor.alarmSource.value).isNull()
        assertThat(monitor.currentAlarmSource()).isNull()
        monitor.refresh()
        assertThat(monitor.currentAlarmSource()!!.condition).isEqualTo(PumpLinkCondition.AAPS_UNREACHABLE)
    }

    @Test fun restartedMonitorReevaluatesSavedEpisodeButCannotAdoptUiHistory() = runTest {
        var boot = 12
        val store = Store()
        fun monitor() = PumpLinkHealthMonitor(backgroundScope, store, { start }, { boot },
            { PumpLinkNotice.DELIVERED }, {}, unmuted)
        monitor().accept(fault)
        val restarted = monitor()
        assertThat(restarted.alarmSource.value).isNull()
        restarted.refresh()
        assertThat(restarted.currentAlarmSource()!!.episode).isEqualTo(store.record.episode)
        boot = 13
        val afterBoot = monitor()
        afterBoot.refresh()
        assertThat(afterBoot.state.value.episodeOpen).isTrue()
        assertThat(afterBoot.currentAlarmSource()).isNull()
    }
}
