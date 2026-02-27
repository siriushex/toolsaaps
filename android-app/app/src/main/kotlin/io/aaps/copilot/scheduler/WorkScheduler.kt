package io.aaps.copilot.scheduler

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

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
    }

    const val SYNC_WORK_NAME = "copilot.sync.automate"
    const val ANALYSIS_WORK_NAME = "copilot.analysis.daily"
}
