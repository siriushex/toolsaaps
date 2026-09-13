package io.aaps.copilot.scheduler

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.service.PowerSaveController
import io.aaps.copilot.service.PowerSaveRuntimeState
import io.aaps.copilot.service.TherapyActionRuntimeState
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object WorkScheduler {

    internal const val SYNC_PERIODIC_INTERVAL_MINUTES = 15L

    fun scheduleEatingWindowRefresh(
        context: Context,
        now: ZonedDateTime = ZonedDateTime.now()
    ) {
        val refreshWork = PeriodicWorkRequestBuilder<EatingWindowRefreshWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(EatingWindowRefreshSchedule.initialDelayMillis(now), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            EATING_WINDOW_REFRESH_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            refreshWork
        )
    }

    fun schedule(context: Context) {
        if (!TherapyActionRuntimeState.isArmed()) {
            cancelRuntimeWork(context)
            return
        }
        if (PowerSaveRuntimeState.isActive()) {
            cancelRuntimeWork(context)
            schedulePowerSaveResume(context, PowerSaveRuntimeState.untilMs())
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncWork = PeriodicWorkRequestBuilder<SyncAndAutomateWorker>(
            SYNC_PERIODIC_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        val analysisWork = PeriodicWorkRequestBuilder<DailyAnalysisWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, syncWork)

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(ANALYSIS_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, analysisWork)

        scheduleNextCircadianTarget(context)

        // Remove legacy keepalive worker that relied on background FGS restarts.
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_RUNTIME_KEEPALIVE_WORK_NAME)
        cancelPowerSaveResume(context)
    }

    fun triggerReactiveAutomation(context: Context): Boolean {
        val app = context.applicationContext as? CopilotApp ?: return false
        app.container.clinicalInputInvalidationCoordinator.invalidate(
            ClinicalInputInvalidationSource.RUNTIME_CONFIGURATION
        )
        return true
    }

    internal suspend fun dispatchClinicalInputInvalidation(
        context: Context,
        request: ClinicalInputInvalidationDispatch
    ): Boolean = workManagerTransport(context).enqueue(request)

    internal suspend fun clinicalInputInvalidationOwnershipStatus(
        context: Context,
        request: ClinicalInputInvalidationDispatch,
        evidenceStore: ClinicalInvalidationExecutionEvidenceStore
    ): ClinicalInvalidationOwnershipStatus {
        val manager = WorkManager.getInstance(context)
        return WorkManagerClinicalInvalidationOwnershipStatusSource(
            queryWorkInfos = { tag ->
                manager.getWorkInfosByTagFlow(tag).first().map { info ->
                    ClinicalInvalidationWorkInfo(info.state, info.outputData)
                }
            },
            queryEvidence = { evidenceStore.read() }
        ).status(request)
    }

    fun scheduleNextCircadianTarget(
        context: Context,
        now: ZonedDateTime = ZonedDateTime.now()
    ) {
        if (!TherapyActionRuntimeState.isArmed()) return
        val work = OneTimeWorkRequestBuilder<CircadianTargetWorker>()
            .setInitialDelay(CircadianTargetSchedule.initialDelayMillis(now), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CIRCADIAN_TARGET_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            work
        )
    }

    fun triggerCircadianTargetEvaluation(context: Context) {
        if (!TherapyActionRuntimeState.isArmed()) return
        val work = OneTimeWorkRequestBuilder<CircadianTargetWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CIRCADIAN_TARGET_IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            work
        )
    }

    fun cancelRuntimeWork(context: Context) {
        workManagerTransport(context).cancelGateClosedRuntimeWork()
    }

    fun schedulePowerSaveResume(context: Context, untilMs: Long) {
        if (!PowerSaveController.isActiveUntil(untilMs) || PowerSaveController.isIndefinite(untilMs)) {
            cancelPowerSaveResume(context)
            return
        }
        val delayMs = (untilMs - System.currentTimeMillis()).coerceAtLeast(1_000L)
        val resumeWork = OneTimeWorkRequestBuilder<PowerSaveResumeWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(POWER_SAVE_RESUME_WORK_NAME, ExistingWorkPolicy.REPLACE, resumeWork)
    }

    fun cancelPowerSaveResume(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(POWER_SAVE_RESUME_WORK_NAME)
    }

    const val SYNC_WORK_NAME = "copilot.sync.automate"
    const val ANALYSIS_WORK_NAME = "copilot.analysis.daily"
    const val REACTIVE_WORK_NAME = "copilot.sync.reactive"
    const val CIRCADIAN_TARGET_WORK_NAME = "copilot.target.circadian.next"
    const val CIRCADIAN_TARGET_IMMEDIATE_WORK_NAME = "copilot.target.circadian.immediate"
    const val POWER_SAVE_RESUME_WORK_NAME = "copilot.power_save.resume"
    const val EATING_WINDOW_REFRESH_WORK_NAME = "copilot.eating_window.refresh"
    internal const val LEGACY_RUNTIME_KEEPALIVE_WORK_NAME = "copilot.runtime.keepalive"

    private fun workManagerTransport(context: Context): ClinicalInputInvalidationWorkManagerTransport {
        val manager = WorkManager.getInstance(context)
        return ClinicalInputInvalidationWorkManagerTransport.enqueueWithWorkManager(
            enqueue = manager::enqueueUniqueWork,
            cancel = manager::cancelUniqueWork
        )
    }
}
