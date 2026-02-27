package io.aaps.copilot.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.aaps.copilot.CopilotApp

class SyncAndAutomateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as CopilotApp).container
        return runCatching {
            container.automationRepository.runAutomationCycle()
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}
