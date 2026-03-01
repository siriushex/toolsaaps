package io.aaps.copilot.scheduler

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object WorkScheduler {

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncWork = PeriodicWorkRequestBuilder<SyncAndAutomateWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        val analysisWork = PeriodicWorkRequestBuilder<DailyAnalysisWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, syncWork)

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(ANALYSIS_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, analysisWork)

        // Remove legacy keepalive worker that relied on background FGS restarts.
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_RUNTIME_KEEPALIVE_WORK_NAME)
    }

    fun triggerReactiveAutomation(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val last = lastReactiveEnqueueTsMs.get()
        if (now - last < REACTIVE_ENQUEUE_DEBOUNCE_MS) {
            return false
        }
        if (!lastReactiveEnqueueTsMs.compareAndSet(last, now)) {
            return false
        }

        val oneShot = OneTimeWorkRequestBuilder<SyncAndAutomateWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(REACTIVE_WORK_NAME, ExistingWorkPolicy.KEEP, oneShot)
        return true
    }

    const val SYNC_WORK_NAME = "copilot.sync.automate"
    const val ANALYSIS_WORK_NAME = "copilot.analysis.daily"
    const val REACTIVE_WORK_NAME = "copilot.sync.reactive"
    private const val LEGACY_RUNTIME_KEEPALIVE_WORK_NAME = "copilot.runtime.keepalive"
    private const val REACTIVE_ENQUEUE_DEBOUNCE_MS = 45_000L
    private val lastReactiveEnqueueTsMs = AtomicLong(0L)
}
