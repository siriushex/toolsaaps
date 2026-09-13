package io.aaps.copilot.data.repository

internal class AapsCarbRevisionSignalCoordinator {
    private val pending = mutableSetOf<Long>()
    private var workerOwned = false
    private var activeRevision: Long? = null
    private var terminalFloor = NO_REVISION

    @Synchronized
    fun tryStart(revisionId: Long): Boolean {
        if (revisionId < 0L || revisionId <= terminalFloor) {
            return false
        }
        pending += revisionId
        if (workerOwned) return false
        workerOwned = true
        return true
    }

    @Synchronized
    fun nextRevision(): Long? {
        check(workerOwned) { "AAPS carb revision worker is not owned" }
        check(activeRevision == null) { "AAPS carb revision is already active" }
        val next = pending.maxOrNull()
        if (next == null) {
            workerOwned = false
            return null
        }
        pending -= next
        activeRevision = next
        return next
    }

    @Synchronized
    fun finish(revisionId: Long, result: AapsCarbHistorySyncResult) {
        check(activeRevision == revisionId) {
            "AAPS carb revision $revisionId is not the active revision"
        }
        activeRevision = null
        val terminal = when (result) {
            is AapsCarbHistorySyncResult.Completed -> true
            is AapsCarbHistorySyncResult.UpToDate -> true
            is AapsCarbHistorySyncResult.Superseded ->
                result.observedRevisionId?.let { observed ->
                    observed > revisionId && pending.any { it >= observed }
                } == true
            is AapsCarbHistorySyncResult.Failed,
            is AapsCarbHistorySyncResult.PageCapReached -> false
        }
        if (terminal && revisionId > terminalFloor) {
            terminalFloor = revisionId
            pending.removeAll { it <= terminalFloor }
        } else if (result is AapsCarbHistorySyncResult.Superseded) {
            pending -= revisionId
        }
    }

    @Synchronized
    fun interrupt(revisionId: Long): Boolean {
        check(activeRevision == revisionId) {
            "AAPS carb revision $revisionId is not the active revision"
        }
        activeRevision = null
        workerOwned = false
        if (pending.isEmpty()) return false
        workerOwned = true
        return true
    }

    private companion object {
        const val NO_REVISION = -1L
    }
}

internal suspend fun runAapsCarbRevisionSignalWorker(
    coordinator: AapsCarbRevisionSignalCoordinator,
    syncForRevision: suspend (Long) -> AapsCarbHistorySyncResult,
    relaunch: () -> Unit
) {
    while (true) {
        val revisionId = coordinator.nextRevision() ?: return
        val result = try {
            syncForRevision(revisionId)
        } catch (error: Throwable) {
            if (coordinator.interrupt(revisionId)) {
                relaunch()
            }
            throw error
        }
        coordinator.finish(revisionId, result)
    }
}
