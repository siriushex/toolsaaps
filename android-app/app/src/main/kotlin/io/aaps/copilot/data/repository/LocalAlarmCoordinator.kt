package io.aaps.copilot.data.repository

import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.domain.alerts.LocalAlarmAdmission
import io.aaps.copilot.domain.alerts.LocalAlarmArbitrationStatus
import io.aaps.copilot.domain.alerts.LocalAlarmCycle
import io.aaps.copilot.domain.alerts.LocalAlarmEnvironment
import io.aaps.copilot.domain.alerts.LocalAlarmEvidence
import io.aaps.copilot.domain.alerts.LocalAlarmPolicy
import io.aaps.copilot.domain.alerts.LocalAlarmRequest
import io.aaps.copilot.domain.alerts.LocalAlarmTiming
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

data class LocalAlarmCoordinatorContext(
    val settings: AppSettings,
    val requests: List<LocalAlarmRequest>,
    val environment: LocalAlarmEnvironment,
    val audioAvailable: Boolean,
    val volumeAvailable: Boolean
)

enum class LocalAlarmCoordinatorExit { BUSY, STOPPED, UNAVAILABLE }

/** Inactive until explicitly run by a source owner with accepted, current authority. */
class LocalAlarmCoordinator(
    private val store: RoomLocalAlarmStore,
    private val volume: AlarmVolumePort,
    private val audio: LocalAlarmAudioPort,
    private val current: () -> LocalAlarmCoordinatorContext?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val waitMs: suspend (Long) -> Unit = { delay(it) }
) {
    private class Running(val selected: LocalAlarmEvidence, val settings: AppSettings, val startedAt: Long) {
        lateinit var job: Job
        val revoked = AtomicBoolean(false)
        @Volatile var cycle: LocalAlarmCycle? = null
        @Volatile var result: LocalAlarmExecutionResult? = null
        @Volatile var failed = false
    }
    private val wakes = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var execution: Running? = null
    @Volatile var lastExit: LocalAlarmCoordinatorExit? = null
        private set

    /** Nonblocking revocation; persistence and hardware cleanup stay on the runner. */
    fun signal() {
        execution?.let { running ->
            if (!continues(running, snapshot())) revoke(running)
        }
        wakes.trySend(Unit)
    }

    suspend fun run(): LocalAlarmCoordinatorExit = withContext(dispatcher) {
        if (!store.runtimeMutex.tryLock()) return@withContext LocalAlarmCoordinatorExit.BUSY
        var exit = LocalAlarmCoordinatorExit.STOPPED
        try {
            if (store.runtimeUnavailable.get()) {
                exit = LocalAlarmCoordinatorExit.UNAVAILABLE
                return@withContext exit
            }
            coroutineScope {
                val executor = LocalAlarmCycleExecutor(store, volume, audio, ::executionContext, dispatcher, waitMs,
                    onClaimed = { cycle -> execution?.let { it.cycle = cycle }; wakes.trySend(Unit) },
                    onFinished = { result -> execution?.let { it.result = result }; wakes.trySend(Unit) })
                var noClaimSnapshot: LocalAlarmCoordinatorContext? = null
                try {
                while (true) {
                    execution?.takeIf { it.job.isCompleted }?.let { finished ->
                        finished.job.join()
                        execution = null
                        if (!clean(finished)) return@coroutineScope LocalAlarmCoordinatorExit.UNAVAILABLE
                    }
                    val captured = snapshot()
                    if (captured == null || !available(captured)) {
                        if (!stopOwned()) return@coroutineScope LocalAlarmCoordinatorExit.UNAVAILABLE
                        awaitWake(this, null, captured?.environment?.nowElapsedMs)
                        continue
                    }
                    val running = execution
                    if (running != null && !continues(running, captured)) {
                        if (!stopOwned()) return@coroutineScope LocalAlarmCoordinatorExit.UNAVAILABLE
                        continue
                    }
                    // A claim may be committed before its callback resumes. Do not recover our own in-flight claim.
                    if (running != null && running.cycle == null) {
                        awaitWake(this, running.selected.validUntilElapsedMs, captured.environment.nowElapsedMs)
                        continue
                    }
                    val decision = store.previewArbitration(captured.requests, running?.cycle, timing(captured.settings))
                        ?: return@coroutineScope LocalAlarmCoordinatorExit.UNAVAILABLE
                    val after = snapshot()
                    if (!sameSnapshot(captured, after)) continue
                    if (decision.status == LocalAlarmArbitrationStatus.INVALID_SNAPSHOT) {
                        return@coroutineScope LocalAlarmCoordinatorExit.UNAVAILABLE
                    }
                    if (decision.status == LocalAlarmArbitrationStatus.RECOVERY_REQUIRED) {
                        if (!stopOwned()) return@coroutineScope LocalAlarmCoordinatorExit.UNAVAILABLE
                        for (cycle in decision.interruptedCycles) {
                            if (!store.markInterrupted(cycle)) return@coroutineScope LocalAlarmCoordinatorExit.UNAVAILABLE
                        }
                        continue
                    }
                    if (running != null && decision.retainedCycle != running.cycle) {
                        if (!stopOwned()) return@coroutineScope LocalAlarmCoordinatorExit.UNAVAILABLE
                        continue
                    }
                    val selected = decision.selected
                    if (selected != null) {
                        if (sameSnapshot(noClaimSnapshot, after)) return@coroutineScope LocalAlarmCoordinatorExit.UNAVAILABLE
                        val next = Running(selected, captured.settings, requireNotNull(after).environment.nowElapsedMs)
                        if (!continues(next, after)) continue
                        next.job = launch(start = CoroutineStart.LAZY) {
                            try {
                                executor.execute(next.settings)
                            } catch (cancelled: CancellationException) {
                                if (cancelled.suppressed.isNotEmpty()) next.failed = true
                                throw cancelled
                            } catch (_: Exception) {
                                next.failed = true
                            } finally {
                                if (next.cycle == null) noClaimSnapshot = captured
                                wakes.trySend(Unit)
                            }
                        }
                        execution = next
                        next.job.start()
                        continue
                    }
                    awaitWake(this, decision.nextWakeElapsedMs, requireNotNull(after).environment.nowElapsedMs)
                }
                @Suppress("UNREACHABLE_CODE")
                LocalAlarmCoordinatorExit.STOPPED
                } finally {
                    withContext(NonCancellable + dispatcher) {
                        if (!stopOwned()) exit = LocalAlarmCoordinatorExit.UNAVAILABLE
                    }
                }
            }.also { exit = it }
        } finally {
            withContext(NonCancellable + dispatcher) {
                try {
                    if (!stopOwned()) exit = LocalAlarmCoordinatorExit.UNAVAILABLE
                } finally {
                    lastExit = exit
                    if (exit == LocalAlarmCoordinatorExit.UNAVAILABLE) store.runtimeUnavailable.set(true)
                    store.runtimeMutex.unlock()
                }
            }
        }
    }

    private suspend fun stopOwned(): Boolean {
        val running = execution ?: return true
        revoke(running)
        running.job.join()
        execution = null
        return clean(running)
    }

    private fun revoke(running: Running) {
        running.revoked.set(true)
        running.job.cancel(CancellationException("local_alarm_authority_revoked"))
    }

    private fun clean(running: Running): Boolean = !running.failed && when (running.result?.status) {
        LocalAlarmExecutionStatus.FINISHED, LocalAlarmExecutionStatus.CANCELLED,
        LocalAlarmExecutionStatus.DENIED, LocalAlarmExecutionStatus.NOT_STARTED,
        LocalAlarmExecutionStatus.MISSED_START -> true
        null -> running.cycle == null && running.job.isCancelled
        else -> false
    }

    private suspend fun awaitWake(scope: CoroutineScope, deadline: Long?, now: Long?) {
        if (deadline != null && now != null && deadline <= now) return
        val timer = if (deadline != null && now != null) scope.launch {
            waitMs(deadline - now)
            wakes.trySend(Unit)
        } else null
        try {
            select<Unit> {
                wakes.onReceive { }
                execution?.job?.onJoin { }
            }
        } finally {
            withContext(NonCancellable) { timer?.cancelAndJoin() }
        }
    }

    private fun executionContext(): LocalAlarmExecutionContext? {
        val running = execution ?: return null
        val captured = snapshot() ?: return null
        if (!continues(running, captured)) return null
        val evidence = captured.requests.single { it.evidence.key == running.selected.key }.evidence
        return LocalAlarmExecutionContext(evidence, captured.environment, captured.audioAvailable, captured.volumeAvailable)
    }

    private fun continues(running: Running, captured: LocalAlarmCoordinatorContext?): Boolean {
        if (running.revoked.get() || captured == null || !available(captured) || captured.settings != running.settings ||
            captured.environment.nowElapsedMs < running.startedAt) return false
        val evidence = captured.requests.singleOrNull { it.evidence.key == running.selected.key }?.evidence ?: return false
        if (evidence.generation != running.selected.generation || evidence.level != running.selected.level ||
            evidence.bootCount != running.selected.bootCount || evidence.observedElapsedMs < running.selected.observedElapsedMs) return false
        return LocalAlarmPolicy.evaluate(evidence, null, captured.environment, timing(captured.settings)).admission == LocalAlarmAdmission.START
    }

    private fun available(captured: LocalAlarmCoordinatorContext) =
        captured.requests.size <= 64 && captured.environment.enabled && captured.environment.armed && captured.audioAvailable && captured.volumeAvailable &&
            LocalAlarmPolicy.validEnvironment(captured.environment) && timing(captured.settings).valid()

    private fun snapshot(): LocalAlarmCoordinatorContext? = try {
        current()?.let { if (it.requests.size > 64) it else it.copy(requests = it.requests.toList()) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        null
    }

    // Advancing clocks do not constitute new authority; every use still rechecks freshness.
    private fun sameSnapshot(first: LocalAlarmCoordinatorContext?, second: LocalAlarmCoordinatorContext?): Boolean =
        first != null && second != null && first.settings == second.settings && first.requests == second.requests &&
            first.audioAvailable == second.audioAvailable && first.volumeAvailable == second.volumeAvailable &&
            first.environment.copy(nowElapsedMs = 0, nowWallMs = 0) == second.environment.copy(nowElapsedMs = 0, nowWallMs = 0)

    private fun timing(settings: AppSettings) = LocalAlarmTiming(
        GlucoseAlertAudioProfiles.resolve(settings, GlucoseAlertAudioSlot.SOFT).durationMs,
        GlucoseAlertAudioProfiles.resolve(settings, GlucoseAlertAudioSlot.CRITICAL_PRIMARY).durationMs,
        settings.softAlertRepeatMinutes, settings.strongLowRepeatMinutes)
}
