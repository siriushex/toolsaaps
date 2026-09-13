package io.aaps.copilot

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ClinicalReportBackgroundReleaseSourceTest {

    @Test
    fun activityReleasesPreparedClinicalPayloadWhenStopped() {
        val source = File(
            "src/main/kotlin/io/aaps/copilot/MainActivity.kt"
        ).readText()
        val onStop = source
            .substringAfter("override fun onStop()")
            .substringBefore("private fun enqueuePostDrawStartup")

        assertThat(onStop).contains("releasePreparedPayloadWhenIdleAsync()")
        assertThat(onStop).doesNotContain("lifecycleScope.launch")
    }

    @Test
    fun payloadReleaseUsesRepositoryLifetimeScope() {
        val source = File(
            "src/main/kotlin/io/aaps/copilot/data/repository/ClinicalReportRepository.kt"
        ).readText()
        val release = source
            .substringAfter("fun releasePreparedPayloadWhenIdleAsync()")
            .substringBefore("internal fun initialize()")

        assertThat(release).contains("scope.launch")
        assertThat(release).contains("releasePreparedPayloadWhenIdle()")
    }

    @Test
    fun exportCaptureStartsUndispatchedBeforeActivityOnStopReleaseCanRun() {
        val viewModel = File(
            "src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt"
        ).readText()
        val coordinator = viewModel
            .substringAfter("internal class ClinicalReportPdfExportCoordinator")
            .substringBefore("internal fun mapClinicalAiCredentialUiState")
        val wiring = viewModel
            .substringAfter("private val clinicalReportPdfExportCoordinator")
            .substringBefore("private val messageState")

        assertThat(coordinator).contains("scope.launch(start = CoroutineStart.UNDISPATCHED)")
        assertThat(coordinator.indexOf("val acquired = acquireSourceLease()"))
            .isLessThan(coordinator.indexOf("withContext(sourceDispatcher)"))
        assertThat(coordinator.indexOf("withContext(sourceDispatcher)"))
            .isLessThan(coordinator.indexOf("stage(captured.source)"))
        assertThat(wiring).contains(
            "acquireSourceLease = " +
                "container.clinicalReportRepository::acquireClinicalPdfSourceLease"
        )
        val repository = File(
            "src/main/kotlin/io/aaps/copilot/data/repository/ClinicalReportRepository.kt"
        ).readText()
        val acquisition = repository
            .substringAfter("suspend fun acquireClinicalPdfSourceLease()")
            .substringBefore("suspend fun claimClinicalPdfReprepare")
        assertThat(acquisition).contains("local !== currentPrepared.local")
        assertThat(acquisition.indexOf("sourceBuilder ="))
            .isLessThan(acquisition.indexOf("ClinicalPdfContentSourceFactory.create("))
    }

    @Test
    fun viewModelTeardownClosesPdfCoordinatorBeforeSuperclassCleanup() {
        val source = File(
            "src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt"
        ).readText()
        val onCleared = source
            .substringAfter("override fun onCleared()")
            .substringBefore("fun refreshCloudJobs")

        assertThat(onCleared).contains("clinicalReportPdfExportCoordinator.close()")
        assertThat(onCleared.indexOf("clinicalReportPdfExportCoordinator.close()"))
            .isLessThan(onCleared.indexOf("super.onCleared()"))
        assertThat(onCleared).doesNotContain("viewModelScope.launch")
    }
}
