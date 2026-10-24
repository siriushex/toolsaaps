package io.aaps.copilot.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

/** Test work yields before a normal/reactive/settings cycle acquires the common lease. */
internal class ForecastTestArbiter {
    private val guard = Any()
    private var normalEntrants = 0
    private var activeTest: Job? = null

    suspend fun <T> normal(block: suspend () -> T): T {
        val interrupted = synchronized(guard) {
            normalEntrants++
            activeTest
        }
        try {
            interrupted?.cancel(Preempted())
            interrupted?.join()
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            synchronized(guard) { normalEntrants-- }
        }
    }

    suspend fun <T : Any> test(block: suspend () -> T?): T? = try {
        coroutineScope {
            val job = currentCoroutineContext().job
            val admitted = synchronized(guard) {
                if (normalEntrants > 0 || activeTest != null) false
                else { activeTest = job; true }
            }
            if (!admitted) return@coroutineScope null
            try {
                block()
            } finally {
                synchronized(guard) { if (activeTest === job) activeTest = null }
            }
        }
    } catch (_: Preempted) {
        // Preemption skips this attempt, not the user's whole test session.
        currentCoroutineContext().ensureActive()
        null
    }

    private class Preempted : CancellationException("normal calculation has priority")
}
