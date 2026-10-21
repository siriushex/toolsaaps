package io.aaps.copilot.data.repository

import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.alerts.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GlucoseAlarmOwnerPhase { OFF, STARTING, WAITING, RUNNING, PAUSED, LIMITED, UNAVAILABLE }

data class GlucoseAlarmOwnerState(
    val phase: GlucoseAlarmOwnerPhase = GlucoseAlarmOwnerPhase.OFF,
    val exactAlarmAvailable: Boolean = false,
    val nextAlarmElapsedMs: Long? = null,
    val acknowledgement: LocalAlarmAcknowledgement? = null,
    val sessionId: String? = null,
    val acknowledgementPauseMinutes: Int = (LOCAL_ALARM_ACK_MIN_PAUSE_MS / 60_000L).toInt()
)

data class GlucoseAlarmCapabilities(val notifications: Boolean, val audio: Boolean,
    val volume: Boolean, val exactAlarm: Boolean)

interface GlucoseAlarmPlatform {
    fun capabilities(): GlucoseAlarmCapabilities
    fun publish(state: GlucoseAlarmOwnerState): LocalAlarmNotificationResult
    fun schedule(wake: LocalAlarmWake?): Boolean
    fun acquireWake(cycle: LocalAlarmCycle): Boolean
    fun releaseWake(cycle: LocalAlarmCycle)
    fun vibrate(cycle: LocalAlarmCycle): LocalAlarmVibrationResult
    fun stopVibration(cycle: LocalAlarmCycle)
}

data class LocalGlucoseInitialResult(val audio: GlucoseAlertAudioPlaybackResult,
    val vibration: Boolean)

/** Foreground lifetime owns execution. Inputs and persisted OFF still authorize each use. */
class GlucoseAlarmOwner(
    db: CopilotDatabase,
    private val settingsFlow: Flow<AppSettings>,
    private val sourceChanges: Flow<CheckedLocalAlarmSource?>,
    private val muteFlow: Flow<Long>,
    private val source: () -> CheckedLocalAlarmSource?,
    private val clock: () -> LocalAlarmSourceClock?,
    private val volume: AlarmVolumePort,
    private val audio: LocalAlarmAudioPort,
    private val platform: GlucoseAlarmPlatform,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val waitMs: suspend (Long) -> Unit = { delay(it) }
) {
    private data class Identity(val key: LocalAlarmKey, val generation: Long, val level: LocalAlarmLevel) {
        fun matches(e: LocalAlarmEvidence) = key == e.key && generation == e.generation && level == e.level
    }
    private class Initial(val identity: Identity) {
        val result = CompletableDeferred<LocalGlucoseInitialResult>()
    }
    private data class NativeStart(val identity: Identity, val boot: Int, val at: Long)

    private val mutableState = MutableStateFlow(GlucoseAlarmOwnerState())
    val state = mutableState.asStateFlow()
    @Volatile private var settings: AppSettings? = null
    @Volatile private var mutedUntil: Long? = null
    @Volatile private var offered: Identity? = null
    @Volatile private var first: Initial? = null
    @Volatile private var runner: Job? = null
    @Volatile private var coordinator: LocalAlarmCoordinator? = null
    private var sessionId: String? = null
    private var lastCycle: LocalAlarmCycle? = null
    private var acknowledged: LocalAlarmAcknowledgement? = null
    private var wakeFailed = false
    private var nativeStart: NativeStart? = null
    private var nativeTimer: Job? = null
    private var scope: CoroutineScope? = null
    private var plannedWake: LocalAlarmWake? = null
    private var notificationResult = LocalAlarmNotificationResult.NOT_REQUESTED
    private var vibrationResult = LocalAlarmVibrationResult.NOT_REQUESTED
    private var wakeHeld: LocalAlarmCycle? = null
    private val store = RoomLocalAlarmStore(db, ::environment)

    suspend fun run() = withContext(dispatcher) {
        check(scope == null)
        coroutineScope {
            scope = this
            sessionId = UUID.randomUUID().toString()
            lastCycle = null
            acknowledged = null
            plannedWake = null
            mutableState.value = GlucoseAlarmOwnerState(GlucoseAlarmOwnerPhase.STARTING)
            val port = object : LocalAlarmAudioPort {
                override suspend fun prepareLocalAlarm(owner: LocalAlarmAudioOwner): Boolean {
                    if (!owner.current() || !platform.acquireWake(owner.cycle)) return false
                    wakeHeld = owner.cycle
                    return audio.prepareLocalAlarm(owner)
                }
                override fun ownsLocalAlarm(cycle: LocalAlarmCycle) = audio.ownsLocalAlarm(cycle)
                override suspend fun playLocalAlarm(cycle: LocalAlarmCycle, stepIndex: Int, settings: AppSettings,
                    admitted: (LocalAlarmCycle) -> Boolean): GlucoseAlertAudioPlaybackResult {
                    if (!admitted(cycle)) return GlucoseAlertAudioPlaybackResult(failureReason = "alarm_authority_lost")
                    notificationResult = publishState()
                    vibrationResult = platform.vibrate(cycle)
                    val played = audio.playLocalAlarm(cycle, stepIndex, settings, admitted)
                    if (admitted(cycle)) first?.takeIf { it.identity.matches(cycle.evidenceIdentity()) }
                        ?.result?.complete(LocalGlucoseInitialResult(played, vibrationResult == LocalAlarmVibrationResult.REQUESTED))
                    return played
                }
                override fun stopLocalAlarm(cycle: LocalAlarmCycle) {
                    try { audio.stopLocalAlarm(cycle) } finally { platform.stopVibration(cycle) }
                }
                override fun notificationResult(cycle: LocalAlarmCycle) = notificationResult
                override fun vibrationResult(cycle: LocalAlarmCycle) = vibrationResult
            }
            val actor = LocalAlarmCoordinator(store, volume, port, ::context, dispatcher, waitMs,
                onClaimed = { lastCycle = it; acknowledged = null; publishState() },
                onFinished = { result ->
                    result.cycle?.let { if (wakeHeld == it) { platform.releaseWake(it); wakeHeld = null } }
                    first?.takeIf { pending -> result.cycle?.let { pending.identity.matches(it.evidenceIdentity()) } == true }
                        ?.result?.complete(LocalGlucoseInitialResult(
                        GlucoseAlertAudioPlaybackResult(failureReason = "alarm_cycle_not_started"), false))
                    publishState()
                },
                onWakeChanged = { plannedWake = it; updateWake(); publishState() })
            coordinator = actor
            val settingsJob = launch {
                settingsFlow.collect {
                    settings = it; actor.signal(); updateWake(); publishState()
                    if (!it.localAlarmRuntimeEnabled) runner?.cancel()
                }
            }
            val muteJob = launch {
                muteFlow.collect { mutedUntil = it; actor.signal(); updateWake(); publishState() }
            }
            val sourceJob = launch {
                sourceChanges.collect { actor.signal(); updateWake(); publishState() }
            }
            try {
                runner = launch { actor.run() }
                if (settings?.localAlarmRuntimeEnabled == false) runner?.cancel()
                runner?.join()
            } finally {
                withContext(NonCancellable) {
                    runner?.cancelAndJoin()
                    settingsJob.cancelAndJoin(); muteJob.cancelAndJoin(); sourceJob.cancelAndJoin()
                    nativeTimer?.cancelAndJoin()
                    var clean = runCatching { platform.schedule(null) }.getOrDefault(false)
                    wakeHeld?.let {
                        clean = runCatching { platform.stopVibration(it) }.isSuccess && clean
                        clean = runCatching { platform.releaseWake(it) }.isSuccess && clean
                    }
                    wakeHeld = null
                    offered = null
                    first?.result?.complete(LocalGlucoseInitialResult(
                        GlucoseAlertAudioPlaybackResult(failureReason = "alarm_owner_stopped"), false))
                    first = null
                    coordinator = null
                    runner = null
                    scope = null
                    sessionId = null
                    mutableState.value = GlucoseAlarmOwnerState(
                        if (!clean || settings?.localAlarmRuntimeEnabled == true) GlucoseAlarmOwnerPhase.UNAVAILABLE else GlucoseAlarmOwnerPhase.OFF)
                }
            }
        }
    }

    /** Null declines ownership before hardware use, preserving the original native path. */
    suspend fun startInitial(checked: CheckedLocalAlarmSource, expected: AppSettings): LocalGlucoseInitialResult? =
        withContext(dispatcher) {
            if (!canOffer(checked) || settings != expected || first != null) return@withContext null
            val request = Initial(identity(checked.evidence))
            offered = request.identity
            first = request
            coordinator?.signal()
            try {
                val result = withTimeoutOrNull(3_000L) { request.result.await() }
                if (result?.audio?.played == true) return@withContext result
                // A failed handoff may use native fallback only after exact owner cleanup.
                offered = null
                coordinator?.signal()
                val cleaned = withContext(NonCancellable) {
                    val actor = coordinator
                    runner?.cancel()
                    withTimeoutOrNull(750L) { runner?.join(); actor?.lastExit == LocalAlarmCoordinatorExit.STOPPED } == true
                }
                if (cleaned && source()?.evidence?.let(request.identity::matches) == true &&
                    environment().let { it.mutedUntilWallMs <= it.nowWallMs }) null
                else result ?: LocalGlucoseInitialResult(GlucoseAlertAudioPlaybackResult(
                    failureReason = "alarm_handoff_unconfirmed"), false)
            } finally {
                if (first === request) first = null
                if (!currentCoroutineContext().isActive) {
                    if (offered == request.identity) offered = null
                    coordinator?.signal()
                }
            }
        }

    suspend fun acceptedWithoutInitial(checked: CheckedLocalAlarmSource?) = withContext(dispatcher) {
        if (checked != null && canOffer(checked)) {
            offered = identity(checked.evidence)
            updateNativeTimer()
            coordinator?.signal()
            updateWake()
        }
    }

    suspend fun nativeInitial(checked: CheckedLocalAlarmSource?, played: Boolean,
        started: LocalAlarmSourceClock? = clock()) = withContext(dispatcher) {
        if (checked == null || !played) return@withContext
        started?.let { nativeStart = NativeStart(identity(checked.evidence), it.bootCount, it.elapsedMs) }
    }

    suspend fun acknowledge(session: String, action: LocalAlarmAcknowledgement): Boolean = withContext(dispatcher) {
        if (session != sessionId || state.value.acknowledgement != action) return@withContext false
        val accepted = coordinator?.acknowledge(action) == true
        if (accepted) { acknowledged = action; publishState() }
        accepted
    }

    fun signal() { coordinator?.signal() }
    fun stop() { offered = null; coordinator?.signal(); runner?.cancel() }

    private fun identity(e: LocalAlarmEvidence) = Identity(e.key, e.generation, e.level)
    private fun LocalAlarmCycle.evidenceIdentity() = LocalAlarmEvidence(key, generation, level, bootCount,
        startedElapsedMs, deadlineElapsedMs, true)

    private fun canOffer(checked: CheckedLocalAlarmSource): Boolean {
        val capabilities = platform.capabilities()
        val live = source()?.evidence ?: return false
        return runner?.isActive == true && settings?.localAlarmRuntimeEnabled == true &&
            live == checked.evidence && enabled(live.level) && capabilities.notifications &&
            capabilities.audio && capabilities.volume && environment().let { it.armed && it.mutedUntilWallMs <= it.nowWallMs }
    }

    private fun enabled(level: LocalAlarmLevel): Boolean = settings?.let { s -> s.softAlertEnabled && when (level) {
        LocalAlarmLevel.LOW_NOW -> s.lowNowAlertEnabled
        LocalAlarmLevel.CRITICAL_5 -> s.critical5AlertEnabled
        LocalAlarmLevel.WARNING_30 -> s.warning30AlertEnabled
        LocalAlarmLevel.SOFT_HIGH_RISK -> s.softHighAlertEnabled
        else -> false
    } } == true

    private fun environment(): LocalAlarmEnvironment {
        val now = clock()
        val s = settings
        return LocalAlarmEnvironment(s?.localAlarmRuntimeEnabled == true && s.softAlertEnabled,
            now?.valid() == true && mutedUntil != null && scope != null && (s?.powerSaveUntilMs ?: Long.MAX_VALUE) <= (now?.wallMs ?: 0L),
            now?.elapsedMs ?: -1L, now?.wallMs ?: -1L, now?.bootCount ?: -1, mutedUntil ?: Long.MAX_VALUE)
    }

    private fun context(): LocalAlarmCoordinatorContext? {
        val s = settings ?: return null
        val capabilities = platform.capabilities()
        val live = source()?.takeIf { offered?.matches(it.evidence) == true && enabled(it.evidence.level) }
        val now = clock()
        val deferred = live != null && nativeDue(live.evidence)?.let { it > (now?.elapsedMs ?: -1L) } == true
        val requests = if (live == null || deferred) emptyList() else listOf(LocalAlarmRequest(live.evidence, live.pendingSinceElapsedMs))
        return LocalAlarmCoordinatorContext(s, requests, environment().let { it.copy(armed = it.armed && capabilities.notifications) },
            capabilities.audio, capabilities.volume)
    }

    private fun nativeDue(evidence: LocalAlarmEvidence): Long? {
        val native = nativeStart?.takeIf { it.identity.matches(evidence) && it.boot == evidence.bootCount } ?: return null
        val minutes = if (evidence.level.strong) settings?.strongLowRepeatMinutes else settings?.softAlertRepeatMinutes
        val delay = (minutes ?: return null).toLong() * 60_000L
        return native.at.takeIf { delay > 0 && it <= Long.MAX_VALUE - delay }?.plus(delay)
    }

    private fun updateNativeTimer() {
        nativeTimer?.cancel()
        val e = source()?.evidence ?: return
        val now = clock() ?: return
        val due = nativeDue(e)?.takeIf { it > now.elapsedMs && it < e.validUntilElapsedMs } ?: return
        nativeTimer = scope?.launch { waitMs(due - now.elapsedMs); coordinator?.signal(); updateWake() }
    }

    private fun updateWake() {
        val live = source()?.evidence
        val env = environment()
        val current = live?.takeIf { offered?.matches(it) == true && enabled(it.level) && env.enabled && env.armed &&
            env.mutedUntilWallMs <= env.nowWallMs }
        val native = current?.takeIf { it.level.strong }?.let { e -> nativeDue(e)?.takeIf {
            it > env.nowElapsedMs && it < e.validUntilElapsedMs
        }?.let { LocalAlarmWake(e.key, e.generation, 0, e.level, e.bootCount, it, e.validUntilElapsedMs) } }
        val plan = plannedWake?.takeIf { current != null && it.key == current.key && it.generation == current.generation &&
            it.level == current.level && it.dueElapsedMs > env.nowElapsedMs && it.dueElapsedMs < current.validUntilElapsedMs }
        val next = listOfNotNull(native, plan).minByOrNull { it.dueElapsedMs }
        val accepted = platform.schedule(next)
        wakeFailed = next != null && !accepted
        mutableState.value = state.value.copy(nextAlarmElapsedMs = next?.dueElapsedMs.takeIf { accepted })
    }

    private fun publishState(): LocalAlarmNotificationResult {
        val env = environment()
        val cap = platform.capabilities()
        val live = source()?.evidence?.takeIf { enabled(it.level) }
        val ack = lastCycle?.takeIf { cycle -> live != null && cycle.key == live.key && cycle.generation == live.generation &&
            cycle.level == live.level && env.mutedUntilWallMs <= env.nowWallMs && env.enabled && env.armed }
            ?.let { LocalAlarmAcknowledgement(it.key, it.generation, it.ordinal, it.level) }
            ?.takeUnless { it == acknowledged }
        val phase = when {
            settings?.localAlarmRuntimeEnabled != true -> GlucoseAlarmOwnerPhase.OFF
            !env.enabled || !env.armed || env.mutedUntilWallMs > env.nowWallMs -> GlucoseAlarmOwnerPhase.PAUSED
            !cap.notifications || !cap.audio || !cap.volume || !cap.exactAlarm || wakeFailed -> GlucoseAlarmOwnerPhase.LIMITED
            acknowledged?.let { live != null && it.key == live.key && it.generation == live.generation && it.level == live.level } == true -> GlucoseAlarmOwnerPhase.PAUSED
            live == null || offered?.matches(live) != true -> GlucoseAlarmOwnerPhase.WAITING
            else -> GlucoseAlarmOwnerPhase.RUNNING
        }
        mutableState.value = state.value.copy(phase = phase, exactAlarmAvailable = cap.exactAlarm,
            acknowledgement = ack, sessionId = sessionId,
            acknowledgementPauseMinutes = maxOf((LOCAL_ALARM_ACK_MIN_PAUSE_MS / 60_000L).toInt(),
                if (ack?.level?.strong == true) settings?.strongLowRepeatMinutes ?: 2
                else settings?.softAlertRepeatMinutes ?: 5))
        return platform.publish(state.value)
    }
}
