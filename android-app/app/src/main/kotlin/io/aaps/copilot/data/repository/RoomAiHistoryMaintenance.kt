package io.aaps.copilot.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** The caller owns the lifecycle/admission flow. This never schedules a provider request. */
internal suspend fun maintainRoomAiHistory(
    enabled: Flow<Boolean>,
    synchronize: suspend () -> RoomAiExportStatus,
    onFailure: () -> Unit,
    pause: suspend (Long) -> Unit = { delay(it) }
) {
    enabled.distinctUntilChanged().collectLatest { active ->
        if (!active) return@collectLatest
        while (true) {
            val waitMs = try {
                val status = synchronize()
                if (status.scanFinished && !status.needsBackfill) 900_000L else 5_000L
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
                60_000L
            }
            pause(waitMs)
        }
    }
}
