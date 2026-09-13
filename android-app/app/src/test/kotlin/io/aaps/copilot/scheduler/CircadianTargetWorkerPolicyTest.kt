package io.aaps.copilot.scheduler

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.CircadianTargetRunEntity
import io.aaps.copilot.domain.target.CircadianAutoState
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CircadianTargetWorkerPolicyTest {

    @Test
    fun actionSkipsPowerSaveBeforeInspectingAuto() {
        assertThat(CircadianTargetWorkerPolicy.action(autoEnabled = true, powerSaveActive = true))
            .isEqualTo(CircadianTargetWorkAction.SKIP_POWER_SAVE)
        assertThat(CircadianTargetWorkerPolicy.action(autoEnabled = false, powerSaveActive = true))
            .isEqualTo(CircadianTargetWorkAction.SKIP_POWER_SAVE)
    }

    @Test
    fun actionEvaluatesOnlyWhenAutoEnabledAndPowerSaveInactive() {
        assertThat(CircadianTargetWorkerPolicy.action(autoEnabled = false, powerSaveActive = false))
            .isEqualTo(CircadianTargetWorkAction.SKIP_AUTO_OFF)
        assertThat(CircadianTargetWorkerPolicy.action(autoEnabled = true, powerSaveActive = false))
            .isEqualTo(CircadianTargetWorkAction.EVALUATE)
    }

    @Test
    fun immediateEvaluationTriggersOnlyOnExplicitFalseToTrueTransition() {
        assertThat(CircadianTargetWorkerPolicy.becameEnabled(null, current = true)).isFalse()
        assertThat(CircadianTargetWorkerPolicy.becameEnabled(true, current = true)).isFalse()
        assertThat(CircadianTargetWorkerPolicy.becameEnabled(false, current = false)).isFalse()
        assertThat(CircadianTargetWorkerPolicy.becameEnabled(false, current = true)).isTrue()
    }

    @Test
    fun promotionRequiresAllGlobalEvidenceGates() {
        assertThat(CircadianTargetWorkerPolicy.canPromote(activeRun())).isTrue()
        assertThat(
            CircadianTargetWorkerPolicy.canPromote(
                activeRun().copy(status = CircadianAutoState.BLOCKED_SENSOR.name)
            )
        ).isFalse()
        assertThat(CircadianTargetWorkerPolicy.canPromote(activeRun().copy(validDays = 6))).isFalse()
        assertThat(CircadianTargetWorkerPolicy.canPromote(activeRun().copy(trustedShare = 0.89))).isFalse()
        assertThat(CircadianTargetWorkerPolicy.canPromote(activeRun().copy(lowRiskPassed = false))).isFalse()
    }

    @Test
    fun malformedPromotionSummaryFailsClosed() {
        assertThat(CircadianTargetWorkerPolicy.canPromote(activeRun().copy(trustedShare = Double.NaN)))
            .isFalse()
        assertThat(CircadianTargetWorkerPolicy.canPromote(activeRun().copy(trustedShare = 1.1)))
            .isFalse()
        assertThat(CircadianTargetWorkerPolicy.canPromote(activeRun().copy(completedAt = 0L)))
            .isFalse()
    }

    @Test
    fun failedStoredRunRequestsWorkerRetry() {
        assertThat(CircadianTargetWorkerPolicy.shouldRetry(activeRun().copy(status = "FAILED"))).isTrue()
        assertThat(CircadianTargetWorkerPolicy.shouldRetry(activeRun())).isFalse()
    }

    @Test
    fun auditPersistenceFailureDoesNotStopSchedulingFlow() = runTest {
        var continued = false

        CircadianTargetAudit.runBestEffort {
            throw IllegalStateException("audit database unavailable")
        }
        continued = true

        assertThat(continued).isTrue()
    }

    @Test
    fun auditBestEffortStillPropagatesCancellationAndFatalErrors() = runTest {
        val cancellation = CancellationException("cancelled")
        val cancellationResult = runCatching {
            CircadianTargetAudit.runBestEffort {
                throw cancellation
            }
        }
        assertThat(cancellationResult.exceptionOrNull()).isSameInstanceAs(cancellation)

        val fatal = AssertionError("fatal")
        val fatalResult = runCatching {
            CircadianTargetAudit.runBestEffort {
                throw fatal
            }
        }
        assertThat(fatalResult.exceptionOrNull()).isSameInstanceAs(fatal)
    }

    @Test
    fun startupDateLookupFailureFallsBackWithoutStoppingObserver() = runTest {
        var auditedFailure: Throwable? = null
        val failure = IllegalStateException("database temporarily unavailable")

        val result = CircadianTargetStartup.loadLastCompletedDateOrNull(
            load = { throw failure },
            onFailure = { auditedFailure = it }
        )

        assertThat(result).isNull()
        assertThat(auditedFailure).isSameInstanceAs(failure)
    }

    @Test
    fun startupDateLookupPreservesSuccessfulValueAndCancellation() = runTest {
        val expected = LocalDate.of(2026, 7, 20)
        assertThat(
            CircadianTargetStartup.loadLastCompletedDateOrNull(
                load = { expected },
                onFailure = { error("must not audit success") }
            )
        ).isEqualTo(expected)

        val cancellation = CancellationException("cancelled")
        val cancelled = runCatching {
            CircadianTargetStartup.loadLastCompletedDateOrNull(
                load = { throw cancellation },
                onFailure = { error("must not audit cancellation") }
            )
        }
        assertThat(cancelled.exceptionOrNull()).isSameInstanceAs(cancellation)
    }

    private fun activeRun(): CircadianTargetRunEntity = CircadianTargetRunEntity(
        runId = "run",
        scheduleRevision = 1L,
        localRunDate = "2026-07-20",
        startedAt = 1L,
        completedAt = 2L,
        lookbackStart = 0L,
        lookbackEnd = 2L,
        status = CircadianAutoState.ACTIVE.name,
        validDays = 7,
        trustedShare = 0.90,
        lowRiskPassed = true,
        reasonCodesJson = "[]"
    )
}
