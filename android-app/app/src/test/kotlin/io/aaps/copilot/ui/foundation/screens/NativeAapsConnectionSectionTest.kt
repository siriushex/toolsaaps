package io.aaps.copilot.ui.foundation.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.service.AapsV3ConnectionState
import io.aaps.copilot.service.AapsV3ConnectionStatus
import io.aaps.copilot.service.AapsV3ObservedProfileStatus
import io.aaps.copilot.service.ComboTransferAudit
import io.aaps.copilot.service.ComboTransferPhase
import io.aaps.copilot.service.ComboTransferState
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeAapsConnectionSectionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ComponentActivity

    @Test fun pairingRequiresPinAndExplicitConsentAndDoesNotRetainPinWhenReopened() {
        var calls = 0
        screen(onPair = { pin -> assertArrayEquals("23456789".toCharArray(), pin); pin.fill('\u0000'); calls++ }) {
            compose.onNodeWithTag("native_aaps_pair").performClick()
            settle()
            saveCredentialRaster("normal")
            compose.onNodeWithTag("native_aaps_confirm").assertIsNotEnabled()
            compose.onNodeWithTag("native_aaps_pin").performTextInput("23456789")
            settle()
            compose.onNodeWithTag("native_aaps_confirm").assertIsNotEnabled()
            compose.onNodeWithTag("native_aaps_consent").performScrollTo().performClick()
            settle()
            compose.onNodeWithTag("native_aaps_confirm").performScrollTo().performClick()
            settle()
            assertEquals(1, calls)
            compose.onNodeWithTag("native_aaps_pair").performClick()
            settle()
            compose.onNodeWithTag("native_aaps_confirm").assertIsNotEnabled()
            compose.onNodeWithTag("native_aaps_pin").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        }
    }

    @Test fun connectedMeansCommunicationOnlyAndCheckRequiresAnExplicitTap() {
        var checks = 0
        screen(AapsV3ConnectionState(AapsV3ConnectionStatus.CONNECTED), onCheck = { checks++ }) {
            compose.onNodeWithText("Signed connection confirmed").assertIsDisplayed()
            compose.onNodeWithText("AAPS 4 target control remains blocked", substring = true).assertIsDisplayed()
            assertEquals(0, checks)
            compose.onNodeWithContentDescription("Check connection").performClick()
            assertEquals(1, checks)
        }
    }

    @Test fun busyPairingCannotBeSubmittedTwice() {
        screen(AapsV3ConnectionState(busy = true)) {
            compose.onNodeWithTag("native_aaps_pair").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Check connection").assertIsNotEnabled()
        }
    }

    @Test fun receivedProfileDoesNotClaimCompleteHistoryOrTargetReadiness() {
        screen(AapsV3ConnectionState(AapsV3ConnectionStatus.CONNECTED, observedProfile = AapsV3ObservedProfileStatus.OBSERVED)) {
            compose.onNodeWithTag("native_aaps_profile_status").assertTextEquals("Profile received. History completeness is not yet confirmed.")
            compose.onNodeWithText("AAPS 4 target control remains blocked", substring = true).assertIsDisplayed()
        }
    }

    private fun historyAudit() = ComboTransferAudit(1_800_000_000_000L,
        12, 3, 1, 72, 4, 2, 9, 3, 1, 2, 5, true, 6)

    @Test fun historyDetailsShowSnapshotCountersWithoutStartingChecks() {
        screen(AapsV3ConnectionState(AapsV3ConnectionStatus.CONNECTED,
            comboTransfer = ComboTransferState(ComboTransferPhase.PARTIAL, lastAudit = historyAudit()))) {
            compose.onNodeWithTag("native_combo_reads").assertDoesNotExist()
            compose.onNodeWithTag("native_combo_details").performClick()
            compose.onNodeWithTag("native_combo_reads").assertTextEquals("Reads: retained 12, missing 3, conflicting 1")
            compose.onNodeWithTag("native_combo_clocks").performScrollTo().assertTextEquals(
                "Clock changes: 2; backwards pairs: 3; rewinds after a change: 1")
            compose.onNodeWithText("Inconsistent reader timestamps").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("native_combo_delivery").performScrollTo().assertTextContains("not verified", substring = true)
        }
    }

    @Test fun failedLatestAttemptDoesNotPresentPreviousAuditAsCurrentSuccess() {
        screen(AapsV3ConnectionState(AapsV3ConnectionStatus.CONNECTED,
            comboTransfer = ComboTransferState(ComboTransferPhase.UNAVAILABLE, lastAudit = historyAudit()))) {
            compose.onNodeWithTag("native_combo_status").assertTextEquals("Latest journal check unavailable")
            compose.onNodeWithTag("native_combo_details").performClick()
            compose.onNodeWithTag("native_combo_checked_at").assertTextContains("Last check:", substring = true)
        }
    }

    @Test fun noAuditDoesNotDisplayZeroEventsAsCompleteHistory() {
        screen(AapsV3ConnectionState(AapsV3ConnectionStatus.CONNECTED)) {
            compose.onNodeWithTag("native_combo_status").assertTextEquals("No completed journal check")
            compose.onNodeWithTag("native_combo_details").performClick()
            compose.onNodeWithTag("native_combo_reads").assertDoesNotExist()
            compose.onNodeWithTag("native_combo_checked_at").assertDoesNotExist()
            compose.onNodeWithTag("native_combo_delivery").performScrollTo().assertIsDisplayed()
        }
    }

    @Config(qualifiers = "ru-rRU-w320dp-h640dp-port")
    @Test fun historyDiagnosticsRemainReachableWithLargeRussianText() {
        screen(AapsV3ConnectionState(AapsV3ConnectionStatus.CONNECTED,
            comboTransfer = ComboTransferState(ComboTransferPhase.PARTIAL, lastAudit = historyAudit())), fontScale = 1.5f) {
            compose.onNodeWithTag("native_combo_details").performScrollTo().performClick()
            compose.onNodeWithTag("native_combo_reads").performScrollTo().assertIsDisplayed()
            saveRaster("history-reads")
            compose.onNodeWithTag("native_combo_clocks").performScrollTo().assertIsDisplayed()
            saveRaster("history-clocks")
            compose.onNodeWithTag("native_combo_delivery").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Проверить связь").performScrollTo().assertIsDisplayed()
        }
    }

    @Config(qualifiers = "ru-rRU-w320dp-h640dp-port")
    @Test fun profileConflictRemainsReadableAtLargeRussianFont() {
        screen(AapsV3ConnectionState(AapsV3ConnectionStatus.CONNECTED, observedProfile = AapsV3ObservedProfileStatus.CONFLICTING), fontScale = 1.5f) {
            compose.onNodeWithTag("native_aaps_profile_status").assertIsDisplayed()
            compose.onNodeWithText("В AAPS получены разные профили на одно время.").assertIsDisplayed()
            compose.onNodeWithText("Управление целями AAPS 4 заблокировано", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("Проверить связь").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun backgroundingClearsPinAndRequiresConsentAgain() {
        screen {
            compose.onNodeWithTag("native_aaps_pair").performClick()
            compose.onNodeWithTag("native_aaps_pin").performTextInput("23456789")
            compose.onNodeWithTag("native_aaps_consent").performScrollTo().performClick()
            compose.runOnUiThread {
                val lifecycle = activity.lifecycle as androidx.lifecycle.LifecycleRegistry
                lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
                lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
            }
            settle()
            compose.onNodeWithTag("native_aaps_pin").assertDoesNotExist()
            compose.onNodeWithTag("native_aaps_pair").performClick()
            compose.onNodeWithTag("native_aaps_pin").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.onNodeWithTag("native_aaps_consent").assertIsOff()
            compose.onNodeWithTag("native_aaps_confirm").assertIsNotEnabled()
        }
    }

    @Config(qualifiers = "ru-rRU-w320dp-h640dp-port")
    @Test fun smallRussianScreenAndLargeTextKeepConsentAndConfirmationReachable() {
        screen(fontScale = 1.5f) {
            compose.onNodeWithTag("native_aaps_pair").assertIsDisplayed().performClick()
            settle()
            saveCredentialRaster("small-russian-large-font")
            compose.onNodeWithTag("native_aaps_pin").performScrollTo().performTextInput("23456789")
            settle()
            compose.onNodeWithTag("native_aaps_consent").performScrollTo().performClick()
            settle()
            compose.onNodeWithTag("native_aaps_confirm").performScrollTo().assertIsDisplayed().assertIsEnabled()
        }
    }

    private fun screen(state: AapsV3ConnectionState = AapsV3ConnectionState(),
        onPair: (CharArray) -> Unit = { it.fill('\u0000'); error("Unexpected pairing") },
        onCheck: () -> Unit = { error("Unexpected check") }, fontScale: Float = 1f, test: () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        activity = controller.get()
        try {
            controller.get().setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    AapsCopilotTheme(darkTheme = false, dynamicColor = false, uiStyle = UiStyle.CLARITY) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            NativeAapsConnectionSection(state, onPair, onCheck, { error("Unexpected revoke") })
                        }
                    }
                }
            }
            settle()
            val root = controller.get().window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(pixels.toSet().size > 32)
            val directory = File("build/reports/native-pairing-raster").apply { mkdirs() }
            File(directory, "connection-${state.status}-$fontScale.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            test(); compose.waitForIdle()
        } finally { controller.pause().stop().destroy() }
    }

    private fun settle() {
        compose.waitForIdle()
    }

    private fun saveCredentialRaster(name: String) {
        assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
        saveRaster(name)
    }

    private fun saveRaster(name: String) {
        val root = activity.window.decorView
        assertTrue(root.width > 0 && root.height > 0)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/reports/native-pairing-raster/entry-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
