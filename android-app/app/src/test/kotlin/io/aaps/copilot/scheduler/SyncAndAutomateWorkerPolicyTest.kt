package io.aaps.copilot.scheduler

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.repository.AutomationRepository
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SyncAndAutomateWorkerPolicyTest {

    @Test
    fun completedWorkerDoesNotClaimAcceptanceForRejectedCycle() = runTest {
        val policy = resolveStartedWorkerPolicy(
            therapyActionsArmed = true, powerSaveActive = false, minuteLoopActive = false
        )
        var cycles = 0
        val outcome = runStartedWorkerCycle(
            policy = policy,
            runAutomationCycle = { cycles++; false },
            cancelFutureRuntimeWork = {},
            schedulePowerSaveResume = {},
            stopRuntimeControllers = {}
        )
        assertThat(cycles).isEqualTo(1)
        assertThat(outcome).isNotEqualTo(StartedWorkerOutcome.EXECUTED)
        assertThat(outcome).isNotEqualTo(StartedWorkerOutcome.SKIPPED_MINUTE_LOOP)
    }

    @Test
    fun alreadyStartedDisarmedWorkerCalculatesLocallyBeforeCancellingFutureWork() = runTest {
        val order = mutableListOf<String>()
        val policy = resolveStartedWorkerPolicy(
            therapyActionsArmed = false,
            powerSaveActive = false,
            minuteLoopActive = false
        )

        runStartedWorkerCycle(
            policy = policy,
            runAutomationCycle = { intent -> order += "cycle:${intent.name}"; true },
            cancelFutureRuntimeWork = { order += "cancel-future" },
            schedulePowerSaveResume = { order += "resume" },
            stopRuntimeControllers = { order += "stop" }
        )

        assertThat(order).containsExactly("cycle:LOCAL_READ_ONLY", "cancel-future").inOrder()
        assertThat(policy.cycleIntent)
            .isEqualTo(AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY)
        val repositoryPolicy = AutomationRepository.resolveCyclePolicyStatic(
            intent = policy.cycleIntent,
            therapyActionsArmed = true,
            killSwitch = false,
            powerSaveActive = false
        )
        assertThat(repositoryPolicy.runCalculations).isTrue()
        assertThat(repositoryPolicy.runRemoteRefresh).isFalse()
        assertThat(repositoryPolicy.therapyWritersAllowed).isFalse()
        assertThat(repositoryPolicy.allowSensitivityMaintenance).isFalse()
        assertThat(repositoryPolicy.allowActionRepositoryAccess).isFalse()
        assertThat(repositoryPolicy.publishWidgetAfterAcceptance).isFalse()
    }

    @Test
    fun alreadyStartedPowerSaveWorkerCalculatesThenStopsAndKeepsFutureWorkCancelled() = runTest {
        val order = mutableListOf<String>()

        runStartedWorkerCycle(
            policy = resolveStartedWorkerPolicy(
                therapyActionsArmed = true,
                powerSaveActive = true,
                minuteLoopActive = true
            ),
            runAutomationCycle = { intent -> order += "cycle:${intent.name}"; true },
            cancelFutureRuntimeWork = { order += "cancel-future" },
            schedulePowerSaveResume = { order += "resume" },
            stopRuntimeControllers = { order += "stop" }
        )

        assertThat(order)
            .containsExactly("cycle:LOCAL_READ_ONLY", "cancel-future", "resume", "stop")
            .inOrder()
    }

    @Test
    fun armedWorkerStillAvoidsDuplicateCycleWhenMinuteLoopOwnsRuntime() = runTest {
        var cycles = 0

        val outcome = runStartedWorkerCycle(
            policy = resolveStartedWorkerPolicy(
                therapyActionsArmed = true,
                powerSaveActive = false,
                minuteLoopActive = true
            ),
            runAutomationCycle = { cycles += 1; true },
            cancelFutureRuntimeWork = {},
            schedulePowerSaveResume = {},
            stopRuntimeControllers = {}
        )

        assertThat(outcome).isEqualTo(StartedWorkerOutcome.SKIPPED_MINUTE_LOOP)
        assertThat(cycles).isEqualTo(0)
    }

    @Test
    fun syncPeriodicCadenceRemainsFifteenMinutes() {
        assertThat(WorkScheduler.SYNC_PERIODIC_INTERVAL_MINUTES).isEqualTo(15L)
    }

    @Test
    fun armedWorkerSelectsNormalIntentOnceAtStart() = runTest {
        val policy = resolveStartedWorkerPolicy(
            therapyActionsArmed = true,
            powerSaveActive = false,
            minuteLoopActive = false
        )
        val intents = mutableListOf<AutomationRepository.AutomationCycleIntent>()

        val outcome = runStartedWorkerCycle(
            policy = policy,
            runAutomationCycle = { intent -> intents += intent; true },
            cancelFutureRuntimeWork = {},
            schedulePowerSaveResume = {},
            stopRuntimeControllers = {}
        )

        assertThat(policy.cycleIntent).isEqualTo(AutomationRepository.AutomationCycleIntent.NORMAL)
        assertThat(intents).containsExactly(AutomationRepository.AutomationCycleIntent.NORMAL)
        assertThat(outcome).isEqualTo(StartedWorkerOutcome.EXECUTED)
    }
}
