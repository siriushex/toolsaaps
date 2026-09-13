package io.aaps.copilot.scheduler

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ClinicalInputInvalidationRouteWiringTest {

    @Test
    fun everyRequiredRouteUsesPositiveDurableMutationGate() = runTest {
        val invalidated = mutableListOf<ClinicalInputInvalidationSource>()
        val required = listOf(
            ClinicalInputInvalidationSource.PLANNED_ACTIVITY,
            ClinicalInputInvalidationSource.CONTEXT_EVENT,
            ClinicalInputInvalidationSource.STARTUP_RECONCILIATION,
            ClinicalInputInvalidationSource.NIGHTSCOUT_RECONCILIATION,
            ClinicalInputInvalidationSource.LOCAL_NIGHTSCOUT,
            ClinicalInputInvalidationSource.BROADCAST_INGEST,
            ClinicalInputInvalidationSource.LOCAL_ACTIVITY,
            ClinicalInputInvalidationSource.HEALTH_CONNECT_ACTIVITY
        )

        required.forEach { source ->
            assertThat(notifyClinicalInputInvalidationIfApplied(0, source, invalidated::add)).isFalse()
            assertThat(notifyClinicalInputInvalidationIfApplied(1, source, invalidated::add)).isTrue()
        }

        assertThat(invalidated).containsExactlyElementsIn(required).inOrder()
    }

    @Test
    fun startupAndNightscoutReconciliationInvalidateOnlyForPositiveAppliedCount() = runTest {
        val invalidated = mutableListOf<ClinicalInputInvalidationSource>()

        assertThat(
            notifyClinicalInputInvalidationAfterReconciliation(0, startup = true, invalidated::add)
        ).isFalse()
        assertThat(
            notifyClinicalInputInvalidationAfterReconciliation(2, startup = true, invalidated::add)
        ).isTrue()
        assertThat(
            notifyClinicalInputInvalidationAfterReconciliation(1, startup = false, invalidated::add)
        ).isTrue()

        assertThat(invalidated).containsExactly(
            ClinicalInputInvalidationSource.STARTUP_RECONCILIATION,
            ClinicalInputInvalidationSource.NIGHTSCOUT_RECONCILIATION
        ).inOrder()
    }

    @Test
    fun localNightscoutWritesAreConflatedOnlyByAppCoordinator() {
        val localNightscout = source("io/aaps/copilot/service/LocalNightscoutServer.kt")

        assertThat(localNightscout).contains("onClinicalInputPersisted()")
        assertThat(localNightscout).doesNotContain("REACTIVE_AUTOMATION_DEBOUNCE_MS")
        assertThat(localNightscout).doesNotContain("lastReactiveAutomationEnqueueTs")
    }

    @Test
    fun appContainerOwnsCoordinatorAndAllDurableProducerCallbacks() {
        val container = source("io/aaps/copilot/service/AppContainer.kt")
        val broadcastIngest = source(
            "io/aaps/copilot/data/repository/BroadcastIngestRepository.kt"
        )
        val mainViewModel = source("io/aaps/copilot/ui/MainViewModel.kt")

        assertThat(container).contains("internal val clinicalInputInvalidationCoordinator")
        assertThat(container).contains("ClinicalInputInvalidationSource.CONTEXT_EVENT")
        assertThat(container).contains("reconcileContextEventsAndInvalidate")
        assertThat(container).contains("ClinicalInputInvalidationSource.LOCAL_NIGHTSCOUT")
        assertThat(broadcastIngest)
            .contains("onClinicalInputPersisted(ClinicalInputInvalidationSource.BROADCAST_INGEST)")
        assertThat(container).contains("onClinicalInputPersisted = { source ->")
        assertThat(container).contains("invalidatePersistedInput(source)")
        assertThat(container).contains("ClinicalInputInvalidationSource.LOCAL_ACTIVITY")
        assertThat(container).contains("ClinicalInputInvalidationSource.HEALTH_CONNECT_ACTIVITY")
        assertThat(mainViewModel).contains("ClinicalInputInvalidationSource.PLANNED_ACTIVITY")
    }

    @Test
    fun appContainerCreatesOneExecutionEvidenceStoreSharedBySchedulerAndWorker() {
        val container = source("io/aaps/copilot/service/AppContainer.kt")
        val worker = source("io/aaps/copilot/scheduler/SyncAndAutomateWorker.kt")

        assertThat(
            container.windowed(
                size = "DataStoreClinicalInvalidationExecutionEvidenceStore(appContext)".length,
                step = 1
            ).count { it == "DataStoreClinicalInvalidationExecutionEvidenceStore(appContext)" }
        ).isEqualTo(1)
        assertThat(container).contains("clinicalInvalidationExecutionEvidenceStore")
        assertThat(container).contains("clinicalInputInvalidationOwnershipStatus(")
        assertThat(worker).contains("container.clinicalInvalidationExecutionEvidenceStore")
        assertThat(worker).doesNotContain("DataStoreClinicalInvalidationExecutionEvidenceStore(")
    }

    @Test
    fun durableProducersDoNotBypassCoordinatorThroughWorkScheduler() {
        val producerPaths = listOf(
            "io/aaps/copilot/data/repository/BroadcastIngestRepository.kt",
            "io/aaps/copilot/service/LocalActivitySensorCollector.kt",
            "io/aaps/copilot/service/HealthConnectActivityCollector.kt",
            "io/aaps/copilot/service/LocalNightscoutServer.kt"
        )

        producerPaths.forEach { path ->
            assertThat(source(path)).doesNotContain("WorkScheduler.triggerReactiveAutomation")
        }
    }

    @Test
    fun coordinatorHasNoIndependentTherapyWriterOrPresentationAccess() {
        val coordinator = source("io/aaps/copilot/scheduler/ClinicalInputInvalidationCoordinator.kt")

        assertThat(coordinator).doesNotContain("TargetManagerRepository")
        assertThat(coordinator).doesNotContain("ActionRepository")
        assertThat(coordinator).doesNotContain("CarbRepository")
        assertThat(coordinator).doesNotContain("Widget")
        assertThat(coordinator).doesNotContain("Alert")
    }

    private fun source(relativePath: String): String = File(
        checkNotNull(generateSequence(File(System.getProperty("user.dir").orEmpty()).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "src/main/kotlin").isDirectory }) {
            "Unable to locate app module from ${System.getProperty("user.dir")}"
        },
        "src/main/kotlin/$relativePath"
    ).readText()
}
