package io.aaps.copilot.scheduler

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.repository.AutomationRepository
import io.aaps.copilot.service.LocalObservationRuntimeState
import io.aaps.copilot.service.TherapyActionRuntimeState
import io.aaps.copilot.service.localObservationRuntimeAllowed
import io.aaps.copilot.service.runAdmittedLocalRuntimeCycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalObservationRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun observationCanStartRuntimeButCannotArmTherapyAndRevocationTakesEffect() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporary.newFolder().resolve("observation.preferences_pb")
        }
        val defaults = AppSettingsStore(data).settings.first()
        try {
            for (therapy in listOf(false, true)) for (observation in listOf(false, true)) {
                val settings = defaults.copy(therapyActionsArmed = therapy, localObservationEnabled = observation)
                TherapyActionRuntimeState.update(settings)
                assertThat(settings.localObservationRuntimeAllowed()).isEqualTo(therapy || observation)
                assertThat(LocalObservationRuntimeState.isAllowed()).isEqualTo(therapy || observation)
                assertThat(TherapyActionRuntimeState.isArmed()).isEqualTo(therapy)
            }
        } finally { TherapyActionRuntimeState.update(defaults) }
        assertThat(LocalObservationRuntimeState.isAllowed()).isFalse()
    }

    @Test fun serviceRoutesEveryPermissionCombinationWithoutUpgradingObservation() = runTest {
        for (therapy in listOf(false, true)) for (observation in listOf(false, true)) for (power in listOf(false, true)) {
            val calls = mutableListOf<String>()
            runAdmittedLocalRuntimeCycle(therapy, observation, power,
                normal = { calls += "normal" }, local = { calls += "local" })
            assertThat(calls).isEqualTo(when {
                power -> emptyList()
                therapy -> listOf("normal")
                observation -> listOf("local")
                else -> emptyList()
            })
        }
    }

    @Test fun queuedObserverNeverCallsNormalCycleEvenAfterTherapyIsArmed() = runTest {
        for (therapy in listOf(false, true)) for (observation in listOf(false, true))
            for (power in listOf(false, true)) for (minute in listOf(false, true)) {
                var calls = 0
                val ran = runLocalObservationRefresh(observation, therapy, power, minute) { calls++ }
                val expected = observation && !therapy && !power && !minute
                assertThat(ran).isEqualTo(expected)
                assertThat(calls).isEqualTo(if (expected) 1 else 0)
            }
    }

    @Test fun failuresAndCancellationDoNotTriggerFallbackOrAnotherCycle() = runTest {
        for (failure in listOf(IllegalStateException("unavailable"), CancellationException("cancelled"))) {
            var calls = 0
            val caught = runCatching {
                runAdmittedLocalRuntimeCycle(false, true, false,
                    normal = { error("unexpected command pipeline") }, local = { calls++; throw failure })
            }.exceptionOrNull()
            assertThat(caught).isSameInstanceAs(failure)
            assertThat(calls).isEqualTo(1)
        }
    }

    @Test fun reactiveObservationDoesNotCancelItsOwnPeriodicWorkAndKeepsReadOnlyIntent() {
        for (mode in ClinicalInputInvalidationMode.entries) {
            val policy = resolveReactiveWorkerPolicy(mode, false, false, localObservationEnabled = true)
            assertThat(policy.cancelFutureRuntimeWork).isFalse()
            assertThat(policy.cycleIntent).isEqualTo(AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY)
            val cancelled = resolveReactiveWorkerPolicy(mode, false, true, localObservationEnabled = true)
            assertThat(cancelled.cancelFutureRuntimeWork).isTrue()
            assertThat(cancelled.stopRuntimeControllers).isTrue()
        }
        val periodic = resolveStartedWorkerPolicy(false, false, false, localObservationEnabled = true)
        assertThat(periodic.cancelFutureRuntimeWork).isFalse()
        assertThat(periodic.cycleIntent).isEqualTo(AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY)
        assertThat(resolveStartedWorkerPolicy(false, false, true, true).skipForMinuteLoop).isTrue()
    }

    @Test fun localCycleNeverGainsRemoteWritersAlertsOrAiPublicationFromCurrentTherapyState() {
        for (therapy in listOf(false, true)) for (power in listOf(false, true)) for (kill in listOf(false, true)) {
            val policy = AutomationRepository.resolveCyclePolicyStatic(
                AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY, therapy, kill, power)
            assertThat(policy.runCalculations).isTrue()
            assertThat(policy.runRemoteRefresh).isFalse()
            assertThat(policy.therapyWritersAllowed).isFalse()
            assertThat(policy.allowActionRepositoryAccess).isFalse()
            assertThat(policy.allowSensitivityMaintenance).isFalse()
            assertThat(policy.allowAlertPublication).isFalse()
            assertThat(policy.publishWidgetAfterAcceptance).isFalse()
        }
    }

    @Test fun oldUnarmedOrPowerSaveWorkCannotStopObservationEnabledDuringCalculation() = runTest {
        for (reactive in listOf(false, true)) for (powerAtStart in listOf(false, true)) {
            val old = resolveStartedWorkerPolicy(false, powerAtStart, false)
            var current = old
            val events = mutableListOf<String>()
            val calculate: suspend (AutomationRepository.AutomationCycleIntent) -> Boolean = { intent ->
                assertThat(intent).isEqualTo(AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY)
                current = resolveStartedWorkerPolicy(false, false, false, localObservationEnabled = true)
                events += "local"
                true
            }
            if (reactive) {
                runReactiveWorkerCycle(old, calculate, { events += "cancel" }, { events += "resume" },
                    { events += "stop" }, runtimePolicyAtCleanup = { current })
            } else {
                runStartedWorkerCycle(old, calculate, { events += "cancel" }, { events += "resume" },
                    { events += "stop" }, runtimePolicyAtCleanup = { current })
            }
            assertThat(events).containsExactly("local")
        }
    }

    @Test fun disablingObservationDuringCalculationCancelsFutureWorkWithoutChangingCycleIntent() = runTest {
        val initial = resolveStartedWorkerPolicy(false, false, false, true)
        var current = initial
        val events = mutableListOf<String>()
        runStartedWorkerCycle(initial, { intent ->
            assertThat(intent).isEqualTo(AutomationRepository.AutomationCycleIntent.LOCAL_READ_ONLY)
            current = resolveStartedWorkerPolicy(false, false, false, false)
            events += "local"
            true
        }, { events += "cancel" }, { events += "resume" }, { events += "stop" },
            runtimePolicyAtCleanup = { current })
        assertThat(events).containsExactly("local", "cancel").inOrder()
    }

    @Test fun failedCurrentPolicyReadNeverAuthorizesCleanupOrMasksCycleFailure() = runTest {
        for (reactive in listOf(false, true)) for (fatalCycle in listOf(false, true)) {
            val primary = if (fatalCycle) AssertionError("cycle failed") else CancellationException("cycle cancelled")
            val readFailure = IllegalStateException("settings unavailable")
            val policy = resolveStartedWorkerPolicy(false, false, false)
            val caught = runCatching {
                if (reactive) runReactiveWorkerCycle(policy, { throw primary },
                    { error("unexpected cancel") }, { error("unexpected resume") }, { error("unexpected stop") },
                    runtimePolicyAtCleanup = { throw readFailure })
                else runStartedWorkerCycle(policy, { throw primary },
                    { error("unexpected cancel") }, { error("unexpected resume") }, { error("unexpected stop") },
                    runtimePolicyAtCleanup = { throw readFailure })
            }.exceptionOrNull()
            assertThat(caught).isSameInstanceAs(primary)
            assertThat(primary.suppressed.toList()).containsExactly(readFailure)
        }
    }

    @Test fun productionObserverIsIsolatedAndServiceReadsCurrentSettingsBeforeSelectingCycle() {
        val root = File("src/main/kotlin/io/aaps/copilot").takeIf(File::exists)
            ?: File("app/src/main/kotlin/io/aaps/copilot")
        val worker = File(root, "scheduler/LocalObservationWorker.kt").readText()
        assertThat(worker).contains("settingsStore.settings.first()")
        assertThat(worker).contains("runLocalReadOnlyCycle()")
        listOf("runAutomationCycle()", "runDailyAnalysis()", "setTherapyActionsArmed", "actionRepository")
            .forEach { assertThat(worker).doesNotContain(it) }
        val service = File(root, "service/LocalNightscoutForegroundService.kt").readText()
            .substringAfter("private suspend fun runCurrentRuntimeCycle")
        assertThat(service.indexOf("settingsStore.settings.first()")).isLessThan(service.indexOf("runAdmittedLocalRuntimeCycle("))
        val schedule = File(root, "scheduler/WorkScheduler.kt").readText()
        val observerBranch = schedule.substringAfter("if (!TherapyActionRuntimeState.isArmed()) {")
            .substringBefore("val constraints")
        assertThat(observerBranch).contains("PeriodicWorkRequestBuilder<LocalObservationWorker>")
        assertThat(observerBranch).doesNotContain("NetworkType.CONNECTED")
        assertThat(observerBranch).doesNotContain("DailyAnalysisWorker")
        assertThat(schedule.substringAfter("fun cancelRuntimeWork")).contains("cancelUniqueWork(OBSERVATION_WORK_NAME)")
    }
}
