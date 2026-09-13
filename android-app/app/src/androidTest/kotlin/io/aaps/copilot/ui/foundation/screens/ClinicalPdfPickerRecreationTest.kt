package io.aaps.copilot.ui.foundation.screens

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.aaps.copilot.ClinicalPdfPickerRecreationActivity
import io.aaps.copilot.UiTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClinicalPdfPickerRecreationTest {

    @Test
    fun productionLauncherRetainsTicketAndResolvesOnceAfterActivityRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as UiTestApplication
        application.pdfPickerRegistry.reset()
        var retainedViewModel: Any? = null

        ActivityScenario.launch(ClinicalPdfPickerRecreationActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                retainedViewModel = activity.viewModelIdentityForTest()
                assertEquals(ClinicalPdfExportUiState.READY, activity.exportPhaseForTest())
            }
            assertEquals(1, application.pdfPickerRegistry.launchCount)

            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertSame(retainedViewModel, activity.viewModelIdentityForTest())
                assertEquals(0, activity.resolveCountForTest())
            }
            assertEquals(1, application.pdfPickerRegistry.launchCount)

            instrumentation.runOnMainSync {
                application.pdfPickerRegistry.deliver(
                    Uri.parse("content://clinical-pdf/result.pdf")
                )
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(1, activity.resolveCountForTest())
                assertEquals(ClinicalPdfExportUiState.COMPLETE, activity.exportPhaseForTest())
            }

            instrumentation.runOnMainSync {
                application.pdfPickerRegistry.deliver(
                    Uri.parse("content://clinical-pdf/stale.pdf")
                )
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(1, activity.resolveCountForTest())
                assertEquals(ClinicalPdfExportUiState.COMPLETE, activity.exportPhaseForTest())
            }
            assertEquals(1, application.pdfPickerRegistry.launchCount)
        }
    }
}
