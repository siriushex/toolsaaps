package io.aaps.copilot.data.repository

import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.domain.alerts.LocalAlarmAdmission
import io.aaps.copilot.domain.alerts.LocalAlarmAudioResult
import io.aaps.copilot.domain.alerts.LocalAlarmCycle
import io.aaps.copilot.domain.alerts.LocalAlarmEnvironment
import io.aaps.copilot.domain.alerts.LocalAlarmEvidence
import io.aaps.copilot.domain.alerts.LocalAlarmNotificationResult
import io.aaps.copilot.domain.alerts.LocalAlarmPolicy
import io.aaps.copilot.domain.alerts.LocalAlarmProfile
import io.aaps.copilot.domain.alerts.LocalAlarmProfiles
import io.aaps.copilot.domain.alerts.LocalAlarmState
import io.aaps.copilot.domain.alerts.LocalAlarmStepResult
import io.aaps.copilot.domain.alerts.LocalAlarmTiming
import io.aaps.copilot.domain.alerts.LocalAlarmVibrationResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

interface LocalAlarmAudioPort {
    suspend fun playLocalAlarm(cycle: LocalAlarmCycle, stepIndex: Int, settings: AppSettings,
        admitted: (LocalAlarmCycle) -> Boolean): GlucoseAlertAudioPlaybackResult
    fun stopLocalAlarm(cycle: LocalAlarmCycle)
}

// The source owner must publish current accepted authority/OFF/capabilities and
// cancel the exact cycle on their loss. This executor never creates that authority.
data class LocalAlarmExecutionContext(
    val evidence: LocalAlarmEvidence,
    val environment: LocalAlarmEnvironment,
    val audioAvailable: Boolean,
    val volumeAvailable: Boolean
)

enum class LocalAlarmExecutionStatus {
    FINISHED, NOT_STARTED, BUSY, DENIED, INVALID_SETTINGS, MISSED_START,
    VOLUME_UNAVAILABLE, AUDIO_FAILED, JOURNAL_UNAVAILABLE, CLEANUP_UNAVAILABLE
}

data class LocalAlarmExecutionResult(val status: LocalAlarmExecutionStatus, val cycle: LocalAlarmCycle? = null)

/** One inactive cycle runner; source arbitration and runtime arming belong to its future owner. */
class LocalAlarmCycleExecutor(
    private val store: RoomLocalAlarmStore,
    volumePort: AlarmVolumePort,
    private val audio: LocalAlarmAudioPort,
    private val current: () -> LocalAlarmExecutionContext?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val waitMs: suspend (Long) -> Unit = { delay(it) }
) {
    private class Session(val state: LocalAlarmState, val timing: LocalAlarmTiming, val job: Job) {
        @Volatile var window: StepWindow? = null
        val cycle = requireNotNull(state.activeCycle)
        val revoked = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        @Volatile var stopConfirmed = false
    }
    private data class StepWindow(val start: Long, val end: Long)
    private val mutex = Mutex()
    @Volatile private var owner: Session? = null
    private val volume = LocalAlarmVolumeLease(volumePort,
        nowElapsedMs = { context()?.environment?.nowElapsedMs ?: -1L }, admitted = ::admittedStep)

    suspend fun execute(settings: AppSettings): LocalAlarmExecutionResult = withContext(dispatcher) {
        if (!mutex.tryLock()) return@withContext LocalAlarmExecutionResult(LocalAlarmExecutionStatus.BUSY)
        var session: Session? = null
        var status = LocalAlarmExecutionStatus.DENIED
        var journalFinished = true
        var cleanupConfirmed = true
        var cancellation: CancellationException? = null
        try {
            val initial = context() ?: return@withContext LocalAlarmExecutionResult(status)
            if (!initial.audioAvailable || !initial.volumeAvailable) return@withContext LocalAlarmExecutionResult(status)
            val claimTiming = timing(settings, GlucoseAlertAudioSlot.CRITICAL_PRIMARY)
            if (LocalAlarmProfiles.create(initial.evidence.level, claimTiming) == null) {
                return@withContext LocalAlarmExecutionResult(LocalAlarmExecutionStatus.INVALID_SETTINGS)
            }
            if (LocalAlarmPolicy.evaluate(initial.evidence, null, initial.environment, claimTiming).admission != LocalAlarmAdmission.START) {
                return@withContext LocalAlarmExecutionResult(status)
            }
            currentCoroutineContext().ensureActive()
            val claim = store.evaluate(initial.evidence, claimTiming)
                ?: return@withContext LocalAlarmExecutionResult(LocalAlarmExecutionStatus.JOURNAL_UNAVAILABLE)
            if (claim.admission != LocalAlarmAdmission.START || claim.startCycle == null || claim.state == null) {
                return@withContext LocalAlarmExecutionResult(LocalAlarmExecutionStatus.NOT_STARTED)
            }
            val cycle = claim.startCycle
            val slot = if (cycle.ordinal % 2L == 1L) GlucoseAlertAudioSlot.CRITICAL_PRIMARY
                else GlucoseAlertAudioSlot.CRITICAL_SECONDARY
            val executionTiming = timing(settings, slot)
            session = Session(claim.state, executionTiming, requireNotNull(currentCoroutineContext()[Job]))
            owner = session
            val profile = LocalAlarmProfiles.create(cycle.level, executionTiming, claim.state.reachedPercent)
            status = if (profile == null) LocalAlarmExecutionStatus.INVALID_SETTINGS else runSteps(session, profile, settings)
        } catch (cancelled: CancellationException) {
            cancellation = cancelled
            throw cancelled
        } finally {
            withContext(NonCancellable + dispatcher) {
                var cleanupCancellation: CancellationException? = null
                try {
                    session?.let {
                        it.revoked.set(true)
                        try {
                            cleanupConfirmed = stopAudio(it)
                        } catch (cancelled: CancellationException) {
                            cleanupCancellation = cancelled
                            cleanupConfirmed = false
                        }
                        try {
                            val released = volume.release(it.cycle).status
                            cleanupConfirmed = cleanupConfirmed && released in setOf(AlarmVolumeStatus.RESTORED,
                                AlarmVolumeStatus.RELEASED, AlarmVolumeStatus.OVERRIDDEN, AlarmVolumeStatus.WRONG_OWNER)
                        } catch (cancelled: CancellationException) {
                            cleanupCancellation = cleanupCancellation ?: cancelled
                            cleanupConfirmed = false
                        }
                        try {
                            journalFinished = store.finish(it.cycle,
                                cancelled = status != LocalAlarmExecutionStatus.FINISHED || !cleanupConfirmed)
                        } catch (cancelled: CancellationException) {
                            cleanupCancellation = cleanupCancellation ?: cancelled
                            journalFinished = false
                        }
                    }
                } finally {
                    owner = null
                    mutex.unlock()
                }
                if (cancellation == null) cleanupCancellation?.let { throw it }
            }
        }
        LocalAlarmExecutionResult(when {
            !journalFinished -> LocalAlarmExecutionStatus.JOURNAL_UNAVAILABLE
            !cleanupConfirmed -> LocalAlarmExecutionStatus.CLEANUP_UNAVAILABLE
            else -> status
        }, session?.cycle)
    }

    /** Stale actions cannot cancel a different generation/ordinal or replacement owner. */
    fun cancel(cycle: LocalAlarmCycle): Boolean {
        val session = owner?.takeIf { it.cycle == cycle } ?: return false
        session.revoked.set(true)
        try {
            stopAudio(session)
        } finally {
            session.job.cancel(CancellationException("local_alarm_cancelled"))
        }
        return true
    }

    private suspend fun runSteps(session: Session, profile: LocalAlarmProfile,
        settings: AppSettings): LocalAlarmExecutionStatus {
        val cycle = session.cycle
        if (!refresh(session)) return LocalAlarmExecutionStatus.DENIED
        session.window = StepWindow(cycle.startedElapsedMs, cycle.startedElapsedMs + profile.steps[1].offsetMs)
        if (volume.acquire(cycle).status != AlarmVolumeStatus.ACQUIRED) return LocalAlarmExecutionStatus.VOLUME_UNAVAILABLE
        var playingUntil = cycle.startedElapsedMs
        for ((index, step) in profile.steps.withIndex()) {
            val due = cycle.startedElapsedMs + step.offsetMs
            if (!waitUntil(session, due)) return LocalAlarmExecutionStatus.DENIED
            val next = profile.steps.getOrNull(index + 1)?.let { cycle.startedElapsedMs + it.offsetMs }
                ?: cycle.deadlineElapsedMs
            val now = context()?.environment?.nowElapsedMs ?: return LocalAlarmExecutionStatus.DENIED
            if (now >= next) {
                if (index == 0) return LocalAlarmExecutionStatus.MISSED_START
                continue
            }
            if (!refresh(session)) return LocalAlarmExecutionStatus.DENIED
            session.window = StepWindow(due, next)
            val raised = volume.raise(cycle, step.targetPercent)
            if (raised.status != AlarmVolumeStatus.CONFIRMED) return LocalAlarmExecutionStatus.VOLUME_UNAVAILABLE
            if (!refresh(session)) return LocalAlarmExecutionStatus.DENIED
            val playback = if (step.startsClip) try {
                audio.playLocalAlarm(cycle, index, settings, ::admittedStep)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                GlucoseAlertAudioPlaybackResult(failureReason = "local_audio_unavailable")
            } else null
            if (!refresh(session)) return LocalAlarmExecutionStatus.DENIED
            val captured = context() ?: return LocalAlarmExecutionStatus.DENIED
            val at = captured.environment.nowElapsedMs
            val result = LocalAlarmStepResult(index, at, LocalAlarmNotificationResult.NOT_REQUESTED,
                when (playback?.played) {
                    true -> LocalAlarmAudioResult.STARTED
                    false -> LocalAlarmAudioResult.FAILED
                    null -> LocalAlarmAudioResult.NOT_REQUESTED
                }, LocalAlarmVibrationResult.NOT_REQUESTED, step.targetPercent)
            if (!store.recordStep(captured.evidence, cycle, result, session.timing)) {
                return LocalAlarmExecutionStatus.JOURNAL_UNAVAILABLE
            }
            if (playback?.played == false) return LocalAlarmExecutionStatus.AUDIO_FAILED
            if (playback?.played == true) {
                val duration = minOf(profile.clipDurationMs.toLong(), (playback.durationMs ?: profile.clipDurationMs).toLong())
                    .coerceAtLeast(0L)
                playingUntil = maxOf(playingUntil, at + minOf(duration, cycle.deadlineElapsedMs - at))
            }
        }
        return if (waitUntil(session, playingUntil) && refresh(session)) LocalAlarmExecutionStatus.FINISHED
            else LocalAlarmExecutionStatus.DENIED
    }

    private suspend fun waitUntil(session: Session, deadline: Long): Boolean {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!admitted(session.cycle)) return false
            val captured = context() ?: return false
            val now = captured.environment.nowElapsedMs
            if (now >= deadline) return true
            val wake = minOf(deadline, captured.evidence.validUntilElapsedMs, session.cycle.deadlineElapsedMs)
            if (wake <= now) return false
            waitMs(wake - now)
            val after = context()?.environment?.nowElapsedMs ?: return false
            if (after <= now) return false
        }
    }

    private suspend fun refresh(session: Session): Boolean {
        currentCoroutineContext().ensureActive()
        if (!admitted(session.cycle)) return false
        val captured = context() ?: return false
        return store.admits(captured.evidence, session.cycle, session.timing) && admitted(session.cycle)
    }

    private fun admitted(cycle: LocalAlarmCycle): Boolean = guard(cycle, inStep = false)

    private fun admittedStep(cycle: LocalAlarmCycle): Boolean = guard(cycle, inStep = true)

    private fun guard(cycle: LocalAlarmCycle, inStep: Boolean): Boolean {
        val session = owner?.takeIf { it.cycle == cycle && !it.revoked.get() && it.job.isActive } ?: return false
        val captured = context() ?: return false
        if (!captured.audioAvailable || !captured.volumeAvailable) return false
        if (inStep) {
            val window = session.window ?: return false
            if (captured.environment.nowElapsedMs < window.start || captured.environment.nowElapsedMs >= window.end) return false
        }
        val evaluation = LocalAlarmPolicy.evaluate(captured.evidence, session.state, captured.environment, session.timing)
        return owner === session && !session.revoked.get() && session.job.isActive &&
            evaluation.admission == LocalAlarmAdmission.ACTIVE && evaluation.state?.activeCycle == cycle
    }

    private fun context(): LocalAlarmExecutionContext? = try {
        current()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        null
    }

    private fun stopAudio(session: Session): Boolean {
        if (session.stopped.compareAndSet(false, true)) {
            session.stopConfirmed = try {
                audio.stopLocalAlarm(session.cycle)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                false
            }
        }
        return session.stopConfirmed
    }

    private fun timing(settings: AppSettings, strongSlot: GlucoseAlertAudioSlot) = LocalAlarmTiming(
        softClipMs = GlucoseAlertAudioProfiles.resolve(settings, GlucoseAlertAudioSlot.SOFT).durationMs,
        strongClipMs = GlucoseAlertAudioProfiles.resolve(settings, strongSlot).durationMs,
        softRepeatMinutes = settings.softAlertRepeatMinutes,
        strongRepeatMinutes = settings.strongLowRepeatMinutes
    )
}
