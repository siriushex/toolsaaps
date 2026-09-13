package io.aaps.copilot.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ForegroundStartupJob(
    private val scope: CoroutineScope,
    private val isResumed: () -> Boolean,
    private val postAfterDraw: (() -> Unit) -> Unit
) {
    private var pending: Job? = null
    private var permissionsAttempted = false

    fun enqueue(action: suspend () -> Unit) {
        if (pending?.isCompleted == false) return
        // Own the lazy job before posting, so pause also cancels callbacks not yet run.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(250)
            if (isResumed()) action()
        }
        pending = job
        postAfterDraw { job.start() }
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }

    fun runPermissionsOnce(action: () -> Unit) {
        if (permissionsAttempted) return
        permissionsAttempted = true
        action()
    }
}
