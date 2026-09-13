package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModelManualCalibrationWidgetRefreshTest {

    @Test
    fun automationFollowUpExceptionIsNonFatalButCancellationAndFatalBoundaryPropagate() = runTest {
        val operational = runManualCalibrationFollowUp {
            error("automation unavailable")
        }
        assertThat(operational).hasMessageThat().isEqualTo("automation unavailable")

        val cancellation = try {
            runManualCalibrationFollowUp { throw CancellationException("automation cancelled") }
            null
        } catch (failure: Throwable) {
            failure
        }
        val fatal = try {
            runManualCalibrationFollowUp { throw ManualDirectThrowable("automation fatal") }
            null
        } catch (failure: Throwable) {
            failure
        }

        assertThat(cancellation).isInstanceOf(CancellationException::class.java)
        assertThat(fatal).isInstanceOf(ManualDirectThrowable::class.java)
    }

    @Test
    fun repositoryOwnsManualDurabilityWidgetCallbackAndViewModelDoesNotDuplicateIt() {
        val mainViewModel = productionSource("io/aaps/copilot/ui/MainViewModel.kt")
        val addBlock = mainViewModel.substringAfter("fun addManualBloodGlucoseCheck(")
            .substringBefore("fun resetManualGlucoseCalibration()")
        val resetBlock = mainViewModel.substringAfter("fun resetManualGlucoseCalibration()")
            .substringBefore("fun setForecastRange(")
        val appContainer = productionSource("io/aaps/copilot/service/AppContainer.kt")
        val calibrationRepositoryBlock = appContainer
            .substringAfter("val glucoseCalibrationRepository = GlucoseCalibrationRepository(")
            .substringBefore("private val clinicalReportDatasetBuilder")

        assertThat(addBlock).doesNotContain("runManualCalibrationOperationAndRefreshWidget(")
        assertThat(resetBlock).doesNotContain("runManualCalibrationOperationAndRefreshWidget(")
        assertThat(addBlock).contains("runManualCalibrationFollowUp")
        assertThat(addBlock).doesNotContain("runCatching")
        assertThat(resetBlock).doesNotContain("runCatching")
        assertThat(addBlock).doesNotContain("container::refreshGlucoseWidget")
        assertThat(resetBlock).doesNotContain("container::refreshGlucoseWidget")
        assertThat(appContainer).contains("suspend fun refreshGlucoseWidget()")
        assertThat(calibrationRepositoryBlock)
            .contains("onManualCalibrationDurableMutation = { refreshGlucoseWidget() }")
        assertThat(appContainer).contains("onWidgetDataChanged = ::refreshGlucoseWidget")
    }

    private fun productionSource(relativePath: String): String {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        return File(root, "src/main/kotlin/$relativePath").readText()
    }
}

private class ManualDirectThrowable(message: String) : Throwable(message)
