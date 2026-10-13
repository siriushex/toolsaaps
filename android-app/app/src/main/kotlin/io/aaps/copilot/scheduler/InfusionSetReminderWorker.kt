package io.aaps.copilot.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.domain.pump.InfusionSetPolicy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

internal fun infusionReminderWork(generation: String, dueTs: Long, nowTs: Long): OneTimeWorkRequest {
    require(generation.matches(Regex("infusion-[0-9]{1,19}")) && dueTs > 0 && nowTs > 0)
    val delay = (dueTs - nowTs).coerceAtLeast(0)
    require(delay <= InfusionSetPolicy.INTERVAL_MS)
    return OneTimeWorkRequestBuilder<InfusionSetReminderWorker>()
        .setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(workDataOf("generation" to generation)).build()
}

object InfusionSetReminderScheduler {
    private const val NAME = "infusion-set-maintenance-v1"
    fun schedule(context: Context, generation: String, dueTs: Long, replace: Boolean) {
        WorkManager.getInstance(context).enqueueUniqueWork(NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            infusionReminderWork(generation, dueTs, System.currentTimeMillis()))
    }
    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
}

class InfusionSetReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val generation = inputData.getString("generation") ?: return Result.failure()
        val container = (applicationContext as? CopilotApp)?.container ?: return Result.failure()
        return try { container.infusionSetReminder.runDue(generation); Result.success() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Result.failure() }
    }
}
