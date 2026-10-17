package io.aaps.copilot.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.service.PowerSaveController
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Local aggregates only; independent of provider authorization and the live prepared report. */
class ClinicalDisplayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as CopilotApp).container
        return try {
            if (PowerSaveController.isActive(container.settingsStore.settings.first())) return Result.retry()
            container.clinicalDisplayRepository.refreshIfDue(System.currentTimeMillis(), ZoneId.systemDefault())
            WorkScheduler.rescheduleClinicalDisplay(applicationContext)
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}
