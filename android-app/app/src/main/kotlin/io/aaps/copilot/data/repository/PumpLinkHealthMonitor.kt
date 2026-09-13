package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.pump.PumpLinkCondition
import io.aaps.copilot.domain.pump.PumpLinkHealthPolicy
import io.aaps.copilot.domain.pump.PumpLinkSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

enum class PumpLinkNotice { NEW, CLAIMED, DELIVERED, SUPPRESSED, FAILED }

data class PumpLinkEpisode(
    val bootCount: Int,
    val startedElapsedMs: Long,
    val responseAtStartElapsedMs: Long?,
    val cause: PumpLinkCondition,
    val notice: PumpLinkNotice = PumpLinkNotice.NEW
)

data class PumpLinkRecord(val snapshot: PumpLinkSnapshot? = null, val episode: PumpLinkEpisode? = null)
data class PumpLinkUiStatus(val condition: PumpLinkCondition = PumpLinkCondition.UNKNOWN, val episodeOpen: Boolean = false)

interface PumpLinkRecordStore {
    suspend fun load(): PumpLinkRecord
    suspend fun save(record: PumpLinkRecord)
}

fun interface PumpLinkMuteCoordinator {
    suspend fun coordinate(operation: suspend (muted: Boolean) -> Unit)
}

class PumpLinkHealthMonitor(
    private val scope: CoroutineScope,
    private val store: PumpLinkRecordStore,
    private val elapsedMs: () -> Long,
    private val bootCount: () -> Int,
    private val notify: suspend (PumpLinkCondition) -> PumpLinkNotice,
    private val clearNotification: () -> Unit,
    private val muteCoordinator: PumpLinkMuteCoordinator,
    private val onFailure: (Exception) -> Unit = {}
) {
    private val mutableState = MutableStateFlow(PumpLinkUiStatus())
    val state: StateFlow<PumpLinkUiStatus> = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var loaded = false
    private var record = PumpLinkRecord()
    private var deadlineJob: Job? = null
    @Volatile private var deadlineGeneration = 0L

    suspend fun accept(snapshot: PumpLinkSnapshot): Boolean {
        var accepted = false
        serialized { muted ->
            loadLocked()
            if (PumpLinkHealthPolicy.accepts(record.snapshot, snapshot, elapsedMs(), bootCount())) {
                evaluateLocked(muted, snapshot)
                accepted = true
            }
        }
        return accepted
    }

    suspend fun refresh() = serialized { muted ->
        loadLocked()
        evaluateLocked(muted)
    }

    private suspend fun serialized(action: suspend (Boolean) -> Unit) {
        val generationBeforeCoordination = deadlineGeneration
        try {
            // The global mute lock must precede the monitor lock, including the
            // first durable fault write and the notification side effect.
            withTimeout(8_000L) {
                muteCoordinator.coordinate { muted ->
                    mutex.withLock {
                        try {
                            action(muted)
                        } catch (failure: Exception) {
                            // A cancelled/failed save may already be durable. Re-read
                            // before handling another packet, even in this process.
                            invalidateLocked()
                            throw failure
                        }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            invalidateUnstartedOperation(generationBeforeCoordination)
            onFailure(timeout)
            throw timeout
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            invalidateUnstartedOperation(generationBeforeCoordination)
            onFailure(failure)
            throw failure
        }
    }

    private fun invalidateUnstartedOperation(generation: Long) {
        // An older failed Room/mute wait must not cancel a newer packet's timer.
        // A running monitor operation will publish or invalidate its own result.
        if (!mutex.tryLock()) return
        try {
            if (deadlineGeneration == generation) invalidateLocked()
        } finally { mutex.unlock() }
    }

    private fun invalidateLocked() {
        loaded = false
        deadlineJob?.cancel()
        deadlineJob = null
        deadlineGeneration++
        mutableState.value = PumpLinkUiStatus(PumpLinkCondition.MONITOR_UNAVAILABLE, record.episode != null)
    }

    private suspend fun loadLocked() {
        if (!loaded) {
            record = store.load()
            loaded = true
        }
    }

    private suspend fun writeLocked(next: PumpLinkRecord) {
        if (next != record) {
            store.save(next)
            record = next
        }
    }

    private suspend fun evaluateLocked(muted: Boolean, snapshot: PumpLinkSnapshot? = record.snapshot) {
        deadlineJob?.cancel()
        deadlineJob = null
        deadlineGeneration++
        val now = elapsedMs()
        val boot = bootCount()
        var condition = PumpLinkHealthPolicy.condition(snapshot, now, boot)
        val existing = record.episode
        var nextEpisode = existing
        var recovered = false
        if (condition == PumpLinkCondition.HEALTHY && existing != null && snapshot != null) {
            val response = snapshot.lastVerifiedStatusElapsedMs
            val confirmedRecovery = existing.cause == PumpLinkCondition.AAPS_UNREACHABLE ||
                (response != null && (existing.bootCount != boot || response > (existing.responseAtStartElapsedMs ?: -1L)))
            if (confirmedRecovery) {
                nextEpisode = null
                recovered = true
            } else {
                condition = PumpLinkCondition.VERIFYING
            }
        }
        if (condition.needsAttention && snapshot?.intentionalDisconnect != true && nextEpisode == null) {
            nextEpisode = PumpLinkEpisode(boot, now, snapshot?.lastVerifiedStatusElapsedMs, condition)
        }
        if (muted && nextEpisode?.notice == PumpLinkNotice.NEW) {
            nextEpisode = nextEpisode.copy(notice = PumpLinkNotice.SUPPRESSED)
        }
        // Persist the fault and its mute disposition together. A restart after
        // this write must not resurrect an episode first observed during OFF.
        writeLocked(PumpLinkRecord(snapshot, nextEpisode))
        if (recovered || (snapshot?.intentionalDisconnect == true && existing != null)) clearNotification()
        mutableState.value = PumpLinkUiStatus(condition, record.episode != null)

        val episode = record.episode
        if (episode?.notice == PumpLinkNotice.NEW && condition.needsAttention && snapshot?.intentionalDisconnect != true) {
            // Persist the attempt before the Android side effect: a process restart must
            // not repeat an episode whose delivery outcome is unknown.
            writeLocked(record.copy(episode = episode.copy(notice = PumpLinkNotice.CLAIMED)))
            val result = try {
                when (val notice = notify(condition)) {
                    PumpLinkNotice.DELIVERED, PumpLinkNotice.SUPPRESSED, PumpLinkNotice.FAILED -> notice
                    else -> PumpLinkNotice.FAILED
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PumpLinkNotice.FAILED
            }
            writeLocked(record.copy(episode = episode.copy(notice = result)))
        }
        scheduleLocked(elapsedMs(), bootCount())
    }

    private fun scheduleLocked(now: Long, boot: Int) {
        val deadline = PumpLinkHealthPolicy.nextDeadline(record.snapshot, now, boot) ?: return
        val generation = deadlineGeneration
        deadlineJob = scope.launch {
            delay(deadline - now)
            try {
                serialized { muted ->
                    if (generation == deadlineGeneration) {
                        deadlineJob = null
                        evaluateLocked(muted)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Failure is visible in state and reported once by serialized().
                // Wait for the next real packet; do not add a storage retry loop.
            }
        }
    }
}
