package io.aaps.copilot.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.data.local.entity.CircadianTargetRunEntity
import io.aaps.copilot.domain.target.BaseTargetSchedule
import io.aaps.copilot.domain.target.CircadianAutoState
import io.aaps.copilot.service.AppContainer
import io.aaps.copilot.service.PowerSaveController
import io.aaps.copilot.service.PowerSaveRuntimeState
import io.aaps.copilot.service.TherapyActionRuntimeState
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal enum class CircadianTargetWorkAction {
    SKIP_AUTO_OFF,
    SKIP_POWER_SAVE,
    EVALUATE
}

internal object CircadianTargetWorkerPolicy {
    fun action(autoEnabled: Boolean, powerSaveActive: Boolean): CircadianTargetWorkAction = when {
        powerSaveActive -> CircadianTargetWorkAction.SKIP_POWER_SAVE
        !autoEnabled -> CircadianTargetWorkAction.SKIP_AUTO_OFF
        else -> CircadianTargetWorkAction.EVALUATE
    }

    fun becameEnabled(previous: Boolean?, current: Boolean): Boolean =
        previous == false && current

    fun canPromote(run: CircadianTargetRunEntity): Boolean =
        run.status == CircadianAutoState.ACTIVE.name &&
            run.completedAt > 0L &&
            run.validDays >= MINIMUM_VALID_DAYS &&
            run.trustedShare.isFinite() &&
            run.trustedShare in MINIMUM_TRUSTED_SHARE..1.0 &&
            run.lowRiskPassed

    fun shouldRetry(run: CircadianTargetRunEntity): Boolean = run.status == FAILED_STATUS

    private const val MINIMUM_VALID_DAYS = 7
    private const val MINIMUM_TRUSTED_SHARE = 0.90
    private const val FAILED_STATUS = "FAILED"
}

internal object CircadianTargetAudit {
    suspend fun runBestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Throwable) {
            // Audit persistence must not change deterministic scheduling behavior.
        }
    }
}

internal object CircadianTargetStartup {
    suspend fun loadLastCompletedDateOrNull(
        load: suspend () -> LocalDate?,
        onFailure: suspend (Throwable) -> Unit
    ): LocalDate? = try {
        load()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (fatal: Error) {
        throw fatal
    } catch (error: Throwable) {
        CircadianTargetAudit.runBestEffort { onFailure(error) }
        null
    }
}

class CircadianTargetWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as CopilotApp).container
        val settings = container.settingsStore.settings.first()
        PowerSaveRuntimeState.update(settings)
        TherapyActionRuntimeState.update(settings)
        if (!settings.therapyActionsArmed) {
            WorkScheduler.cancelRuntimeWork(applicationContext)
            return Result.success()
        }

        var scheduleNext = true
        return try {
            when (
                CircadianTargetWorkerPolicy.action(
                    autoEnabled = settings.baseTargetSchedule.autoEnabled,
                    powerSaveActive = PowerSaveController.isActive(settings)
                )
            ) {
                CircadianTargetWorkAction.SKIP_POWER_SAVE -> {
                    WorkScheduler.cancelRuntimeWork(applicationContext)
                    WorkScheduler.schedulePowerSaveResume(
                        context = applicationContext,
                        untilMs = settings.powerSaveUntilMs
                    )
                    container.stopRuntimeControllers()
                    Result.success()
                }

                CircadianTargetWorkAction.SKIP_AUTO_OFF -> Result.success()
                CircadianTargetWorkAction.EVALUATE -> {
                    evaluate(container, settings.baseTargetSchedule)
                    Result.success()
                }
            }
        } catch (_: RetryableCircadianTargetRunException) {
            scheduleNext = false
            Result.retry()
        } finally {
            if (scheduleNext) WorkScheduler.scheduleNextCircadianTarget(applicationContext)
        }
    }

    private suspend fun evaluate(
        container: AppContainer,
        schedule: BaseTargetSchedule
    ) {
        try {
        val run = container.circadianTargetRepository.evaluateAndPublish(
            now = System.currentTimeMillis(),
            schedule = schedule,
            zoneId = ZoneId.systemDefault()
        )
        if (CircadianTargetWorkerPolicy.shouldRetry(run)) {
            CircadianTargetAudit.runBestEffort {
                container.auditLogger.error("circadian_target_run_failed", run.auditMetadata())
            }
            throw RetryableCircadianTargetRunException()
        } else {
            CircadianTargetAudit.runBestEffort {
                container.auditLogger.info("circadian_target_run_completed", run.auditMetadata())
            }
        }
        if (CircadianTargetWorkerPolicy.canPromote(run)) {
            val promoted = container.settingsStore.promoteTargetManagerToActive(schedule.revision)
            if (promoted) {
                CircadianTargetAudit.runBestEffort {
                    container.auditLogger.info(
                        "target_manager_auto_promoted",
                        mapOf("scheduleRevision" to schedule.revision, "runId" to run.runId)
                    )
                }
            }
        }
        } catch (retry: RetryableCircadianTargetRunException) {
            throw retry
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (error: Throwable) {
            CircadianTargetAudit.runBestEffort {
                container.auditLogger.error(
                    "circadian_target_run_failed",
                    mapOf(
                        "scheduleRevision" to schedule.revision,
                        "failureType" to error.javaClass.simpleName
                    )
                )
            }
            throw RetryableCircadianTargetRunException(error)
        }
    }

    private fun CircadianTargetRunEntity.auditMetadata(): Map<String, Any?> = mapOf(
        "runId" to runId,
        "scheduleRevision" to scheduleRevision,
        "localRunDate" to localRunDate,
        "status" to status,
        "validDays" to validDays,
        "trustedShare" to trustedShare,
        "lowRiskPassed" to lowRiskPassed,
        "completedAt" to completedAt
    )

    private class RetryableCircadianTargetRunException(
        cause: Throwable? = null
    ) : RuntimeException(cause)
}
