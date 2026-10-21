package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.ui.InsulinOnsetCountdown
import io.aaps.copilot.ui.insulinOnsetCountdown
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class InsulinOnsetSession(
    val timer: InsulinOnsetCountdown,
    val profileId: String,
    val dismissed: Boolean = false
)

internal interface InsulinOnsetSessionStore {
    suspend fun load(): InsulinOnsetSession?
    suspend fun save(session: InsulinOnsetSession)
}

/** Retains presentation, never evidence validity or permission for therapy. */
internal fun retainInsulinOnsetSession(previous: InsulinOnsetSession?, rows: List<TherapyEventEntity>,
    model: AiInsulinContext, nowMs: Long): InsulinOnsetSession? {
    val candidate = insulinOnsetCountdown(rows, model, nowMs, previous?.timer?.recordKey)
    if (candidate != null) {
        val same = previous?.timer?.recordKey == candidate.recordKey
        val newer = previous == null || (!same && candidate.recordedAtMs > previous.timer.recordedAtMs)
        if (newer || (same && previous?.profileId == model.configuredProfileId &&
                    previous.timer.recordedAtMs == candidate.recordedAtMs)) {
            return InsulinOnsetSession(candidate.copy(retained = true), model.configuredProfileId,
                dismissed = same && previous?.dismissed == true)
        }
    }
    // Invalid/missing evidence replaces the number with a review state, not a disappearing tile.
    return previous?.copy(timer = previous.timer.copy(retained = true, requiresReview = true))
}

internal class InsulinOnsetSessionRepository(
    scope: CoroutineScope,
    rows: Flow<List<TherapyEventEntity>>,
    telemetry: Flow<List<TelemetrySampleEntity>>,
    profiles: Flow<String>,
    private val store: InsulinOnsetSessionStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val initialEvaluation = CompletableDeferred<Unit>()
    private var session: InsulinOnsetSession? = null
    private val mutableCountdown = MutableStateFlow<InsulinOnsetCountdown?>(null)
    val countdown = mutableCountdown.asStateFlow()

    init {
        scope.launch {
            var storageFailed = false
            try { session = store.load() } catch (_: IOException) { storageFailed = true }
            combine(rows.distinctUntilChanged(), telemetry.distinctUntilChanged(), profiles.distinctUntilChanged()) {
                    events, samples, profile -> Triple(events, samples, profile)
            }.collect { (events, samples, profile) ->
                mutex.withLock {
                    val now = clock()
                    val model = if (samples.size > 128) AiInsulinContext(profile)
                        else AiInsulinContext.fromTelemetry(profile, samples, now)
                    val next = retainInsulinOnsetSession(session, events, model, now)
                    if (next != null && (next != session || storageFailed)) {
                        try { store.save(next); storageFailed = false }
                        catch (_: IOException) { storageFailed = true }
                    }
                    session = next
                    mutableCountdown.value = next?.takeUnless { it.dismissed }?.timer?.let {
                        if (storageFailed) it.copy(requiresReview = true) else it
                    }
                    initialEvaluation.complete(Unit)
                }
            }
        }
    }

    suspend fun awaitInitialEvaluation() = initialEvaluation.await()

    suspend fun dismiss(recordKey: String) = mutex.withLock {
        val current = session?.takeIf { it.timer.recordKey == recordKey } ?: return@withLock
        val next = current.copy(dismissed = true)
        try {
            store.save(next)
            session = next
            mutableCountdown.value = null
        } catch (_: IOException) {
            mutableCountdown.value = current.timer.copy(requiresReview = true)
        }
    }
}
