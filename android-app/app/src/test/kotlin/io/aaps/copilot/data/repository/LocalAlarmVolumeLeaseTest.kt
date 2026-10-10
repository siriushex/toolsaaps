package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.alerts.LocalAlarmCycle
import io.aaps.copilot.domain.alerts.LocalAlarmKey
import io.aaps.copilot.domain.alerts.LocalAlarmLevel
import io.aaps.copilot.domain.alerts.LocalAlarmSourceKind
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalAlarmVolumeLeaseTest {
    private var now = 1_000L
    private var admitted = true
    private val cycle = LocalAlarmCycle(LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, "source-a"),
        1L, 1L, LocalAlarmLevel.LOW_NOW, 1, 1_000L, 56_000L)
    private val port = FakePort()
    private val lease = LocalAlarmVolumeLease(port, { now }, { admitted })

    @Test fun acquisitionReadsBaselineWithoutChangingVolume() {
        assertThat(lease.acquire(cycle).status).isEqualTo(AlarmVolumeStatus.ACQUIRED)
        assertThat(port.writes).isEmpty()
        assertThat(port.index).isEqualTo(1)
    }

    @Test fun urgentFloorRoundsUpAndRestoresConfirmedOwnership() {
        lease.acquire(cycle)
        assertThat(lease.raise(cycle, 70).confirmedIndex).isEqualTo(5)
        assertThat(lease.raise(cycle, 85).confirmedIndex).isEqualTo(6)
        assertThat(lease.raise(cycle, 100).confirmedIndex).isEqualTo(7)
        assertThat(lease.release(cycle).status).isEqualTo(AlarmVolumeStatus.RESTORED)
        assertThat(port.writes).containsExactly(5, 6, 7, 1).inOrder()
    }

    @Test fun lowerTargetNeverLowersAnAlreadyLouderStream() {
        port.index = 6
        lease.acquire(cycle)
        assertThat(lease.raise(cycle, 25).confirmedIndex).isEqualTo(6)
        assertThat(lease.release(cycle).status).isEqualTo(AlarmVolumeStatus.RELEASED)
        assertThat(port.writes).isEmpty()
    }

    @Test fun aQuieterCallerStepCannotWeakenTheUrgentProfileFloor() {
        lease.acquire(cycle)
        assertThat(lease.raise(cycle, 25).confirmedIndex).isEqualTo(5)
        assertThat(port.writes).containsExactly(5)
        assertThat(lease.release(cycle).status).isEqualTo(AlarmVolumeStatus.RESTORED)
    }

    @Test fun duplicateAcquisitionCannotReplaceTheBaseline() {
        lease.acquire(cycle)
        lease.raise(cycle, 70)
        val reads = port.reads
        assertThat(lease.acquire(cycle).status).isEqualTo(AlarmVolumeStatus.ALREADY_OWNED)
        assertThat(port.reads).isEqualTo(reads)
        lease.release(cycle)
        assertThat(port.index).isEqualTo(1)
    }

    @Test fun secondCycleCannotStealTheSingleOwner() {
        lease.acquire(cycle)
        assertThat(lease.acquire(cycle.copy(ordinal = 2)).status).isEqualTo(AlarmVolumeStatus.BUSY)
        assertThat(lease.raise(cycle, 70).status).isEqualTo(AlarmVolumeStatus.CONFIRMED)
    }

    @Test fun mismatchedCallbacksNeverTouchHardwareOrReleaseTheCurrentOwner() {
        lease.acquire(cycle)
        val reads = port.reads
        listOf(cycle.copy(ordinal = 2), cycle.copy(generation = 2), cycle.copy(bootCount = 2),
            cycle.copy(level = LocalAlarmLevel.CRITICAL_5), cycle.copy(key = cycle.key.copy(id = "source-b")),
            cycle.copy(startedElapsedMs = 2_000)).forEach { stale ->
            assertThat(lease.raise(stale, 100).status).isEqualTo(AlarmVolumeStatus.WRONG_OWNER)
            assertThat(lease.release(stale).status).isEqualTo(AlarmVolumeStatus.WRONG_OWNER)
        }
        assertThat(port.reads).isEqualTo(reads)
        assertThat(port.writes).isEmpty()
        assertThat(lease.raise(cycle, 70).status).isEqualTo(AlarmVolumeStatus.CONFIRMED)
    }

    @Test fun detectedManualChangePermanentlyStopsRaisesAndRestoration() {
        lease.acquire(cycle)
        lease.raise(cycle, 70)
        port.index = 3
        assertThat(lease.raise(cycle, 85).status).isEqualTo(AlarmVolumeStatus.OVERRIDDEN)
        port.index = 5
        assertThat(lease.raise(cycle, 100).status).isEqualTo(AlarmVolumeStatus.OVERRIDDEN)
        assertThat(lease.release(cycle).status).isEqualTo(AlarmVolumeStatus.OVERRIDDEN)
        assertThat(port.writes).containsExactly(5)
        assertThat(port.index).isEqualTo(5)
    }

    @Test fun manualChangeAtReleaseIsNeverOverwritten() {
        lease.acquire(cycle)
        lease.raise(cycle, 70)
        port.index = 4
        assertThat(lease.release(cycle).status).isEqualTo(AlarmVolumeStatus.OVERRIDDEN)
        assertThat(port.writes).containsExactly(5)
    }

    @Test fun deniedAcquisitionDoesNotEvenReadHardware() {
        admitted = false
        assertThat(lease.acquire(cycle).status).isEqualTo(AlarmVolumeStatus.DENIED)
        assertThat(port.reads).isEqualTo(0)
    }

    @Test fun expiryOrAdmissionLossStopsRaisesButAllowsGuardedCleanup() {
        lease.acquire(cycle)
        lease.raise(cycle, 70)
        now = cycle.deadlineElapsedMs
        assertThat(lease.raise(cycle, 100).status).isEqualTo(AlarmVolumeStatus.DENIED)
        admitted = false
        assertThat(lease.release(cycle).status).isEqualTo(AlarmVolumeStatus.RESTORED)
        assertThat(port.writes).containsExactly(5, 1).inOrder()
    }

    @Test fun offArrivingDuringReadPreventsTheQueuedWrite() {
        lease.acquire(cycle)
        port.onRead = { admitted = false }
        assertThat(lease.raise(cycle, 70).status).isEqualTo(AlarmVolumeStatus.DENIED)
        assertThat(port.writes).isEmpty()
    }

    @Test fun admissionIsRecheckedAfterAcquisitionRead() {
        port.onRead = { admitted = false }
        assertThat(lease.acquire(cycle).status).isEqualTo(AlarmVolumeStatus.DENIED)
        port.onRead = null
        admitted = true
        assertThat(lease.acquire(cycle).status).isEqualTo(AlarmVolumeStatus.ACQUIRED)
    }

    @Test fun deniedCycleCannotResumeAfterAnAdmissionToggle() {
        lease.acquire(cycle)
        admitted = false
        assertThat(lease.raise(cycle, 70).status).isEqualTo(AlarmVolumeStatus.DENIED)
        admitted = true
        assertThat(lease.raise(cycle, 70).status).isEqualTo(AlarmVolumeStatus.DENIED)
        assertThat(port.writes).isEmpty()
    }

    @Test fun invalidHardwareCannotAcquireOwnership() {
        listOf(AlarmVolumeSnapshot(0, 0, false), AlarmVolumeSnapshot(-1, 7, false),
            AlarmVolumeSnapshot(8, 7, false), AlarmVolumeSnapshot(1, 7, true)).forEach { invalid ->
            port.index = invalid.index
            port.maximum = invalid.maximum
            port.fixed = invalid.fixed
            assertThat(lease.acquire(cycle).status).isEqualTo(AlarmVolumeStatus.UNAVAILABLE)
        }
        assertThat(port.writes).isEmpty()
    }

    @Test fun changedHardwareMaximumStopsOwnershipWithoutRestoration() {
        lease.acquire(cycle)
        lease.raise(cycle, 70)
        port.maximum = 15
        assertThat(lease.raise(cycle, 100).status).isEqualTo(AlarmVolumeStatus.UNAVAILABLE)
        assertThat(lease.release(cycle).status).isEqualTo(AlarmVolumeStatus.UNAVAILABLE)
        assertThat(port.writes).containsExactly(5)
    }

    @Test fun failedReadIsUnavailableAndDoesNotWrite() {
        port.readFailure = IllegalStateException("unavailable")
        assertThat(lease.acquire(cycle).status).isEqualTo(AlarmVolumeStatus.UNAVAILABLE)
        port.readFailure = null
        lease.acquire(cycle)
        port.readFailure = SecurityException("unavailable")
        assertThat(lease.raise(cycle, 100).status).isEqualTo(AlarmVolumeStatus.UNAVAILABLE)
        assertThat(port.writes).isEmpty()
    }

    @Test fun failedWriteCannotBecomeConfirmedOrAuthorizeRestoration() {
        lease.acquire(cycle)
        port.writeFailure = SecurityException("unavailable")
        assertThat(lease.raise(cycle, 70).status).isEqualTo(AlarmVolumeStatus.UNAVAILABLE)
        port.writeFailure = null
        assertThat(lease.release(cycle).status).isEqualTo(AlarmVolumeStatus.UNAVAILABLE)
        assertThat(port.writes).containsExactly(5)
    }

    @Test fun noOpSetterCannotBecomeConfirmed() {
        lease.acquire(cycle)
        port.ignoreWrites = true
        val result = lease.raise(cycle, 70)
        assertThat(result.status).isEqualTo(AlarmVolumeStatus.UNAVAILABLE)
        assertThat(result.confirmedIndex).isNull()
        lease.release(cycle)
        assertThat(port.writes).containsExactly(5)
    }

    @Test fun failedReadbackCannotAuthorizeRestorationEvenIfWriteTookEffect() {
        lease.acquire(cycle)
        port.onWrite = { port.readFailure = IllegalStateException("unavailable") }
        assertThat(lease.raise(cycle, 70).status).isEqualTo(AlarmVolumeStatus.UNAVAILABLE)
        port.readFailure = null
        port.onWrite = null
        assertThat(lease.release(cycle).status).isEqualTo(AlarmVolumeStatus.UNAVAILABLE)
        assertThat(port.writes).containsExactly(5)
    }

    @Test fun repeatedConfirmedTargetDoesNotIssueAnotherSet() {
        lease.acquire(cycle)
        lease.raise(cycle, 70)
        assertThat(lease.raise(cycle, 70).confirmedIndex).isEqualTo(5)
        assertThat(port.writes).containsExactly(5)
    }

    @Test fun invalidPercentDoesNotTouchHardwareOrDiscardValidOwnership() {
        lease.acquire(cycle)
        val reads = port.reads
        listOf(0, -1, 101, Int.MAX_VALUE).forEach {
            assertThat(lease.raise(cycle, it).status).isEqualTo(AlarmVolumeStatus.INVALID_TARGET)
        }
        assertThat(port.reads).isEqualTo(reads)
        assertThat(lease.raise(cycle, 70).confirmedIndex).isEqualTo(5)
    }

    @Test fun releaseFailureStillDropsOwnershipAndNeverRetriesOldCleanup() {
        lease.acquire(cycle)
        lease.raise(cycle, 70)
        port.readFailure = IllegalStateException("unavailable")
        assertThat(lease.release(cycle).status).isEqualTo(AlarmVolumeStatus.UNAVAILABLE)
        port.readFailure = null
        val reads = port.reads
        assertThat(lease.release(cycle).status).isEqualTo(AlarmVolumeStatus.WRONG_OWNER)
        assertThat(port.reads).isEqualTo(reads)
        assertThat(lease.acquire(cycle.copy(ordinal = 2)).status).isEqualTo(AlarmVolumeStatus.ACQUIRED)
    }

    @Test fun cancellationPropagatesAndUnknownWriteNeverRestores() {
        lease.acquire(cycle)
        port.writeFailure = CancellationException("cancelled")
        assertThrows(CancellationException::class.java) { lease.raise(cycle, 70) }
        port.writeFailure = null
        assertThat(lease.release(cycle).status).isEqualTo(AlarmVolumeStatus.UNAVAILABLE)
        assertThat(port.writes).containsExactly(5)
    }

    @Test fun futureNegativeExpiredAndMalformedCyclesCannotAcquire() {
        listOf(-1L, cycle.startedElapsedMs - 1, cycle.deadlineElapsedMs).forEach {
            now = it
            assertThat(lease.acquire(cycle).status).isEqualTo(AlarmVolumeStatus.DENIED)
        }
        now = 1_000L
        listOf(cycle.copy(ordinal = 0), cycle.copy(generation = 0), cycle.copy(bootCount = -1),
            cycle.copy(deadlineElapsedMs = Long.MAX_VALUE), cycle.copy(level = LocalAlarmLevel.NONE),
            cycle.copy(key = cycle.key.copy(id = ""))).forEach {
            assertThat(lease.acquire(it).status).isEqualTo(AlarmVolumeStatus.DENIED)
        }
        assertThat(port.reads).isEqualTo(0)
    }

    @Test fun cancellationInsideAdmissionCannotWriteAfterOwnershipWasReleased() {
        var cancelAtCheck = false
        lateinit var guarded: LocalAlarmVolumeLease
        guarded = LocalAlarmVolumeLease(port, { now }) {
            if (cancelAtCheck) {
                cancelAtCheck = false
                port.onRead = null
                guarded.release(cycle)
            }
            true
        }
        guarded.acquire(cycle)
        port.onRead = { cancelAtCheck = true }
        assertThat(guarded.raise(cycle, 70).status).isEqualTo(AlarmVolumeStatus.WRONG_OWNER)
        assertThat(port.writes).isEmpty()
    }

    @Test fun acquisitionCallbackCannotReenterAndReplacePendingOwnership() {
        var acquireAtCheck = false
        var nestedStatus: AlarmVolumeStatus? = null
        lateinit var guarded: LocalAlarmVolumeLease
        guarded = LocalAlarmVolumeLease(port, { now }) {
            if (acquireAtCheck) {
                acquireAtCheck = false
                port.onRead = null
                nestedStatus = guarded.acquire(cycle.copy(ordinal = 2)).status
            }
            true
        }
        port.onRead = { acquireAtCheck = true }
        assertThat(guarded.acquire(cycle).status).isEqualTo(AlarmVolumeStatus.ACQUIRED)
        assertThat(nestedStatus).isEqualTo(AlarmVolumeStatus.BUSY)
    }

    @Test fun cleanupCannotLetANewOwnerStartBeforeRestorationFinishes() {
        lease.acquire(cycle)
        lease.raise(cycle, 70)
        var nestedStatus: AlarmVolumeStatus? = null
        port.onRead = {
            port.onRead = null
            nestedStatus = lease.acquire(cycle.copy(ordinal = 2)).status
        }
        assertThat(lease.release(cycle).status).isEqualTo(AlarmVolumeStatus.RESTORED)
        assertThat(nestedStatus).isEqualTo(AlarmVolumeStatus.BUSY)
        assertThat(port.writes).containsExactly(5, 1).inOrder()
        assertThat(lease.acquire(cycle.copy(ordinal = 2)).status).isEqualTo(AlarmVolumeStatus.ACQUIRED)
    }

    private class FakePort : AlarmVolumePort {
        var index = 1
        var maximum = 7
        var fixed = false
        var reads = 0
        val writes = mutableListOf<Int>()
        var readFailure: RuntimeException? = null
        var writeFailure: RuntimeException? = null
        var ignoreWrites = false
        var onRead: (() -> Unit)? = null
        var onWrite: (() -> Unit)? = null
        override fun read(): AlarmVolumeSnapshot {
            reads++
            readFailure?.let { throw it }
            onRead?.invoke()
            return AlarmVolumeSnapshot(index, maximum, fixed)
        }
        override fun set(index: Int) {
            writes += index
            writeFailure?.let { throw it }
            if (!ignoreWrites) this.index = index
            onWrite?.invoke()
        }
    }
}
