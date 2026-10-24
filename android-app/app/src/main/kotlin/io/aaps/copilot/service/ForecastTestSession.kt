package io.aaps.copilot.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

enum class ForecastTestStatus {
    OFF, STARTING, CALCULATING, COMPLETED, SKIPPED, TIMEOUT, FAILED,
    THERMAL, POWER_SAVE, UNAVAILABLE
}

data class ForecastTestState(
    val sessionId: Long = 0,
    val active: Boolean = false,
    val status: ForecastTestStatus = ForecastTestStatus.OFF,
    val elapsedMinutes: Long = 0,
    val completedCycles: Int = 0,
    val lastDurationMs: Long? = null
)

/** Process-local opt-in: navigation does not stop it, process restart never resumes it. */
class ForecastTestSession(private val elapsedMs: () -> Long) {
    private val mutableState = MutableStateFlow(ForecastTestState())
    val state = mutableState.asStateFlow()
    private val owner = Mutex()
    private var startedAt = 0L
    private var runningJob: Job? = null

    @Synchronized
    fun start(): Long {
        if (state.value.active) return state.value.sessionId
        startedAt = elapsedMs()
        val id = state.value.sessionId + 1
        mutableState.value = ForecastTestState(id, true, ForecastTestStatus.STARTING)
        return id
    }

    @Synchronized
    fun stop(reason: ForecastTestStatus = ForecastTestStatus.OFF, sessionId: Long = state.value.sessionId) {
        if (!state.value.active || state.value.sessionId != sessionId) return
        mutableState.value = state.value.copy(active = false, status = reason)
        runningJob?.cancel()
    }

    @Synchronized
    private fun update(id: Long, transform: (ForecastTestState) -> ForecastTestState) {
        if (state.value.active && state.value.sessionId == id) mutableState.value = transform(state.value)
    }

    suspend fun run(
        id: Long,
        stopReason: () -> ForecastTestStatus?,
        calculate: suspend () -> Boolean
    ) = coroutineScope {
        if (!owner.tryLock()) return@coroutineScope
        val job = currentCoroutineContext().job
        val start = synchronized(this@ForecastTestSession) {
            if (state.value.active && state.value.sessionId == id) {
                runningJob = job
                startedAt
            } else null
        }
        if (start == null) {
            owner.unlock()
            return@coroutineScope
        }
        fun checkAdmission() {
            if (!job.isActive) return
            val age = elapsedMs() - start
            val reason = if (age < 0) ForecastTestStatus.UNAVAILABLE else stopReason()
            if (reason != null) stop(reason, id)
            else update(id) { it.copy(elapsedMinutes = age / INTERVAL_MS) }
        }
        val watchdog = launch {
            while (isActive) {
                checkAdmission()
                delay(1_000)
            }
        }
        try {
            while (isActive) {
                checkAdmission()
                currentCoroutineContext().ensureActive()
                val began = elapsedMs()
                update(id) { it.copy(status = ForecastTestStatus.CALCULATING) }
                val result = try {
                    when (withTimeoutOrNull(CYCLE_TIMEOUT_MS) { calculate() }) {
                        true -> ForecastTestStatus.COMPLETED
                        false -> ForecastTestStatus.SKIPPED
                        null -> ForecastTestStatus.TIMEOUT
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ForecastTestStatus.FAILED
                }
                currentCoroutineContext().ensureActive()
                update(id) { it.copy(
                    status = result,
                    completedCycles = it.completedCycles + if (result == ForecastTestStatus.COMPLETED) 1 else 0,
                    lastDurationMs = (elapsedMs() - began).coerceAtLeast(0)
                ) }
                // Fixed cadence with no backlog or catch-up burst after an overrun/sleep.
                delay(INTERVAL_MS - ((elapsedMs() - start).coerceAtLeast(0) % INTERVAL_MS))
            }
        } finally {
            watchdog.cancel()
            synchronized(this@ForecastTestSession) {
                if (runningJob === job) runningJob = null
                if (state.value.active && state.value.sessionId == id) {
                    mutableState.value = state.value.copy(active = false, status = ForecastTestStatus.UNAVAILABLE)
                }
            }
            owner.unlock()
        }
    }

    companion object {
        const val INTERVAL_MS = 60_000L
        const val CYCLE_TIMEOUT_MS = 45_000L
    }
}
