package io.aaps.copilot.scheduler

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class EnergyProfileRefreshWiringTest {
    @Test fun inferenceDoesNotMaterializeUnrelatedTelemetryHistory() {
        val container = source("io/aaps/copilot/service/AppContainer.kt")
        val wiring = container.substringAfter("inferenceDataSource = EnergyProfileInferenceDataSource(")
            .substringBefore("decodeTherapy =")
        assertThat(wiring).contains("betweenForEnergyProfileInference")
        assertThat(wiring).doesNotContain(".since(fromTs)")
    }

    @Test fun enabledProfileCanRequestItsMissingSnapshotBeforeTheNightlyWorker() {
        val container = source("io/aaps/copilot/service/AppContainer.kt")
        assertThat(container).contains("ensureInitialEnergyProfileRefresh(")
        assertThat(container).contains("WorkScheduler.requestInitialEnergyProfileRefresh(")
    }

    @Test fun initialRequestReusesTheDiagnosticWorkerWithoutReplacingQueuedWork() {
        val scheduler = source("io/aaps/copilot/scheduler/WorkScheduler.kt")
        val request = scheduler.substringAfter("internal fun requestInitialEnergyProfileRefresh(")
            .substringBefore("fun scheduleEatingWindowRefresh(")
        assertThat(request).contains("ENERGY_PROFILE_INITIAL_REFRESH_WORK_NAME")
        assertThat(request).contains("ExistingWorkPolicy.KEEP")
        assertThat(request).contains("OneTimeWorkRequestBuilder<EatingWindowRefreshWorker>()")
        assertThat(request).doesNotContain("SyncAndAutomateWorker")
        assertThat(request).doesNotContain("PeriodicWorkRequestBuilder")
    }

    private fun source(path: String): String = File(
        checkNotNull(generateSequence(File(System.getProperty("user.dir").orEmpty()).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "src/main/kotlin").isDirectory }),
        "src/main/kotlin/$path"
    ).readText()
}
