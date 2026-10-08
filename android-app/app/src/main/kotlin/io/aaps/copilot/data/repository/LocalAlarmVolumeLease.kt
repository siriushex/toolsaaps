package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.alerts.LocalAlarmCycle
import io.aaps.copilot.domain.alerts.LocalAlarmPolicy
import io.aaps.copilot.domain.alerts.LocalAlarmProfiles
import io.aaps.copilot.domain.alerts.LocalAlarmState
import kotlinx.coroutines.CancellationException

data class AlarmVolumeSnapshot(val index: Int, val maximum: Int, val fixed: Boolean)

interface AlarmVolumePort {
    fun read(): AlarmVolumeSnapshot
    fun set(index: Int)
}

enum class AlarmVolumeStatus {
    ACQUIRED, ALREADY_OWNED, CONFIRMED, RESTORED, RELEASED, OVERRIDDEN,
    DENIED, UNAVAILABLE, WRONG_OWNER, INVALID_TARGET, BUSY
}

data class AlarmVolumeResult(val status: AlarmVolumeStatus, val confirmedIndex: Int? = null)

// The serialized coordinator must supply fresh source/OFF/capability admission.
// This volume-only helper never authorizes an alarm or starts audio itself.
class LocalAlarmVolumeLease(
    private val port: AlarmVolumePort,
    private val nowElapsedMs: () -> Long,
    private val admitted: (LocalAlarmCycle) -> Boolean
) {
    private data class Owner(
        val cycle: LocalAlarmCycle,
        val baseline: Int,
        val maximum: Int,
        var confirmed: Int,
        var changed: Boolean = false,
        var stopped: AlarmVolumeStatus? = null
    )

    private var owner: Owner? = null
    private var acquiring = false
    private var restoring = false

    @Synchronized
    fun acquire(cycle: LocalAlarmCycle): AlarmVolumeResult {
        if (acquiring || restoring) return result(AlarmVolumeStatus.BUSY)
        owner?.let {
            return result(if (it.cycle == cycle) AlarmVolumeStatus.ALREADY_OWNED else AlarmVolumeStatus.BUSY)
        }
        acquiring = true
        try {
            val shape = LocalAlarmState(cycle.key, cycle.generation, cycle.level, cycle.bootCount,
                cycle.startedElapsedMs, cycle.deadlineElapsedMs, cycle.ordinal, cycle, true)
            if (!LocalAlarmPolicy.validStateShape(shape) || !allowed(cycle)) return result(AlarmVolumeStatus.DENIED)
            val snapshot = read() ?: return result(AlarmVolumeStatus.UNAVAILABLE)
            if (!valid(snapshot)) return result(AlarmVolumeStatus.UNAVAILABLE)
            if (!allowed(cycle)) return result(AlarmVolumeStatus.DENIED)
            owner = Owner(cycle, snapshot.index, snapshot.maximum, snapshot.index)
            return result(AlarmVolumeStatus.ACQUIRED)
        } finally {
            acquiring = false
        }
    }

    @Synchronized
    fun raise(cycle: LocalAlarmCycle, percent: Int): AlarmVolumeResult {
        val lease = owner?.takeIf { it.cycle == cycle } ?: return result(AlarmVolumeStatus.WRONG_OWNER)
        if (percent !in 1..100) return result(AlarmVolumeStatus.INVALID_TARGET)
        lease.stopped?.let { return result(it) }
        // Unknown/cancelled hardware operations must never grant restoration.
        lease.stopped = AlarmVolumeStatus.UNAVAILABLE
        admissionStatus(lease)?.let { return stop(lease, it) }
        val before = read() ?: return result(AlarmVolumeStatus.UNAVAILABLE)
        if (owner !== lease) return result(AlarmVolumeStatus.WRONG_OWNER)
        if (!valid(before) || before.maximum != lease.maximum) return result(AlarmVolumeStatus.UNAVAILABLE)
        if (before.index != lease.confirmed) return stop(lease, AlarmVolumeStatus.OVERRIDDEN)
        val target = LocalAlarmProfiles.targetVolume(percent, before.maximum, before.index)
            ?: return result(AlarmVolumeStatus.UNAVAILABLE)
        admissionStatus(lease)?.let { return stop(lease, it) }
        if (target != before.index) {
            if (!write(target)) return result(AlarmVolumeStatus.UNAVAILABLE)
            if (owner !== lease) return result(AlarmVolumeStatus.WRONG_OWNER)
            val after = read() ?: return result(AlarmVolumeStatus.UNAVAILABLE)
            if (owner !== lease) return result(AlarmVolumeStatus.WRONG_OWNER)
            if (!valid(after) || after.maximum != lease.maximum || after.index != target) {
                return result(AlarmVolumeStatus.UNAVAILABLE)
            }
            lease.confirmed = after.index
            lease.changed = true
        }
        admissionStatus(lease)?.let { return stop(lease, it) }
        lease.stopped = null
        return AlarmVolumeResult(AlarmVolumeStatus.CONFIRMED, lease.confirmed)
    }

    @Synchronized
    fun release(cycle: LocalAlarmCycle): AlarmVolumeResult {
        val lease = owner?.takeIf { it.cycle == cycle } ?: return result(AlarmVolumeStatus.WRONG_OWNER)
        owner = null
        restoring = true
        try {
            if (lease.stopped == AlarmVolumeStatus.OVERRIDDEN || lease.stopped == AlarmVolumeStatus.UNAVAILABLE) {
                return result(lease.stopped!!)
            }
            val current = read() ?: return result(AlarmVolumeStatus.UNAVAILABLE)
            if (!valid(current) || current.maximum != lease.maximum) return result(AlarmVolumeStatus.UNAVAILABLE)
            if (current.index != lease.confirmed) return result(AlarmVolumeStatus.OVERRIDDEN)
            if (!lease.changed) return result(AlarmVolumeStatus.RELEASED)
            // OFF and expiry still allow cleanup, but only while our value matches.
            if (!write(lease.baseline)) return result(AlarmVolumeStatus.UNAVAILABLE)
            val restored = read() ?: return result(AlarmVolumeStatus.UNAVAILABLE)
            if (!valid(restored) || restored.maximum != lease.maximum || restored.index != lease.baseline) {
                return result(AlarmVolumeStatus.UNAVAILABLE)
            }
            return AlarmVolumeResult(AlarmVolumeStatus.RESTORED, restored.index)
        } finally {
            restoring = false
        }
    }

    private fun admissionStatus(lease: Owner): AlarmVolumeStatus? {
        val accepted = allowed(lease.cycle)
        return when {
            owner !== lease -> AlarmVolumeStatus.WRONG_OWNER
            !accepted -> AlarmVolumeStatus.DENIED
            else -> null
        }
    }

    private fun allowed(cycle: LocalAlarmCycle): Boolean = try {
        fun withinDeadline(): Boolean = nowElapsedMs().let {
            it >= cycle.startedElapsedMs && it < cycle.deadlineElapsedMs
        }
        withinDeadline() && admitted(cycle) && withinDeadline()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        false
    }

    private fun read(): AlarmVolumeSnapshot? = try {
        port.read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        null
    }

    private fun write(index: Int): Boolean = try {
        port.set(index)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        false
    }

    private fun valid(snapshot: AlarmVolumeSnapshot): Boolean = !snapshot.fixed && snapshot.maximum > 0 &&
        snapshot.index in 0..snapshot.maximum

    private fun stop(lease: Owner, status: AlarmVolumeStatus): AlarmVolumeResult {
        lease.stopped = status
        return result(status)
    }

    private fun result(status: AlarmVolumeStatus) = AlarmVolumeResult(status)
}
