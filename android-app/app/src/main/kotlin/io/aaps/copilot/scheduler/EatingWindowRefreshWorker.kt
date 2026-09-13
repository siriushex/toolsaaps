package io.aaps.copilot.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.data.repository.EnergyProfileInferenceRefreshResult
import io.aaps.copilot.data.repository.PersistedEatingWindowSnapshot
import io.aaps.copilot.service.PowerSaveController
import io.aaps.copilot.service.PowerSaveRuntimeState
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Runs both daily diagnostic refreshes in order without creating another WorkManager job. */
internal suspend fun refreshDailyEatingMaintenance(
    refreshEatingWindow: suspend () -> PersistedEatingWindowSnapshot,
    refreshEnergyProfile: suspend () -> EnergyProfileInferenceRefreshResult
): PersistedEatingWindowSnapshot {
    val snapshot = refreshEatingWindow()
    refreshEnergyProfile()
    return snapshot
}

class EatingWindowRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as CopilotApp).container
        val settings = container.settingsStore.settings.first()
        PowerSaveRuntimeState.update(settings)
        if (PowerSaveController.isActive(settings)) return Result.success()

        return try {
            val now = System.currentTimeMillis()
            val zoneId = ZoneId.systemDefault()
            val snapshot = refreshDailyEatingMaintenance(
                refreshEatingWindow = {
                    container.eatingWindowSnapshotRepository.refresh(now = now, zoneId = zoneId)
                },
                refreshEnergyProfile = {
                    container.energyProfileRepository.refreshInference(now = now, zoneId = zoneId)
                }
            )
            container.auditLogger.info(
                "eating_window_snapshot_completed",
                mapOf(
                    "localCompletedDate" to snapshot.localCompletedDate,
                    "recentWindowCount" to snapshot.snapshot.recent.windows.size,
                    "stableWindowCount" to snapshot.snapshot.stable.windows.size
                )
            )
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
