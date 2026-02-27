package io.aaps.copilot.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.aaps.copilot.CopilotApp

class DailyAnalysisWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as CopilotApp).container
        return runCatching {
            container.insightsRepository.runDailyAnalysis()
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}
