package io.aaps.copilot.scheduler

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ReactiveCycleLeaseWiringTest {

    @Test
    fun reactiveWorkerUsesLeaseWaitingEntryPointInsideExecutionEvidenceBoundary() {
        val worker = source("scheduler/SyncAndAutomateWorker.kt")
        val reactiveBody = worker.substringAfter("val outcome = runReactiveWorkerOrchestration(")
            .substringBefore("onStarted = {")

        assertThat(reactiveBody).contains("runClinicalCycleWithExecutionEvidence(")
        assertThat(reactiveBody).contains("container.automationRepository.runReactiveCycle(intent)")
        assertThat(reactiveBody).doesNotContain("container.automationRepository.runAutomationCycle()")
        assertThat(reactiveBody).doesNotContain("container.automationRepository.runLocalReadOnlyCycle()")
    }

    @Test
    fun periodicWorkerRetainsIdleOnlyRoutes() {
        val periodicBody = source("scheduler/SyncAndAutomateWorker.kt")
            .substringBefore("val outcome = runReactiveWorkerOrchestration(")

        assertThat(periodicBody).contains("container.automationRepository.runAutomationCycle()")
        assertThat(periodicBody).contains("container.automationRepository.runLocalReadOnlyCycle()")
    }

    @Test
    fun reactiveEntryReadsSettingsInsideLeaseAndRejectsSourceChangeIntent() {
        val repository = source("data/repository/AutomationRepository.kt")
        val reactiveEntry = repository.substringAfter("internal suspend fun runReactiveCycle(")
            .substringBefore("internal suspend fun applySensitivitySettings(")
        val cycleBody = repository.substringAfter("private suspend fun runCycle(")
            .substringBefore("private suspend fun prepareProfileEstimatorRevision(")

        assertThat(reactiveEntry).contains("require(intent != AutomationCycleIntent.SENSITIVITY_SOURCE_CHANGE)")
        assertThat(reactiveEntry).contains("runReactiveCycleUnderLeaseStatic(cycleMutex)")
        assertThat(reactiveEntry).contains("runCycle(intent, cycleLeaseOwned = true)")
        assertThat(reactiveEntry).doesNotContain("settingsStore.settings.first()")
        assertThat(cycleBody).contains("val bootstrapSettings = settingsStore.settings.first()")
        assertThat(cycleBody).contains("if (cycleLeaseOwned)")
    }

    private fun source(path: String): String = File(
        checkNotNull(generateSequence(File(System.getProperty("user.dir").orEmpty()).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "src/main/kotlin").isDirectory }),
        "src/main/kotlin/io/aaps/copilot/$path"
    ).readText()
}
