package io.aaps.copilot.ui.diary

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.data.local.entity.LocalDiaryEntryEntity
import io.aaps.copilot.data.repository.*
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalDiaryScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var root: View
    private val at = 1_800_000_000_000L
    private fun entry() = LocalDiaryEntryEntity("meal-a", 1, "MEAL", at, "UTC", 42500, "", "", "Lunch", false, at)
    private fun draft(kind: DiaryKind) = DiaryDraft("entry-a", kind = kind, occurredAtMs = at, zoneId = "UTC")

    @Test fun emptyDiaryOffersBothLocalEntryTypesWithoutSourceOrArming() {
        val kinds = mutableListOf<DiaryKind>()
        screen({ DiaryUiState(loading = false) }, start = { kinds += it }) {
            compose.onNodeWithTag("diary_add_meal").performClick()
            compose.onNodeWithTag("diary_add_injection").performClick()
            assertEquals(listOf(DiaryKind.MEAL, DiaryKind.INJECTION), kinds)
        }
    }

    @Test fun mealEditorRetainsCommaAmountAndSavesOnlyOnTap() {
        var state by mutableStateOf(DiaryUiState(draft = draft(DiaryKind.MEAL)))
        var saves = 0
        screen({ state }, change = { state = state.copy(draft = it) }, save = { saves++ }) {
            compose.onNodeWithTag("diary_amount").performTextInput("42,5")
            assertEquals("42,5", state.draft?.amount)
            assertEquals(0, saves)
            compose.onNodeWithTag("diary_save").performClick()
            assertEquals(1, saves)
            compose.onNodeWithTag("diary_insulin_name").assertDoesNotExist()
        }
    }

    @Test fun injectionRequiresExplicitTypeAndHasNoSuggestedDose() {
        var state by mutableStateOf(DiaryUiState(draft = draft(DiaryKind.INJECTION)))
        screen({ state }, change = { state = state.copy(draft = it) }) {
            assertEquals("", state.draft?.amount)
            assertNull(state.draft?.insulinKind)
            compose.onNodeWithTag("diary_insulin_name").performScrollTo().performTextInput("Example insulin")
            compose.onNodeWithTag("diary_kind_LONG").performScrollTo().performClick()
            assertEquals(DiaryInsulinKind.LONG, state.draft?.insulinKind)
            assertEquals("Example insulin", state.draft?.insulinName)
        }
    }

    @Test fun pendingSaveDisablesFieldsAndDuplicateSubmit() {
        screen({ DiaryUiState(draft = draft(DiaryKind.MEAL), saving = true) }) {
            compose.onNodeWithTag("diary_save").assertIsNotEnabled()
            compose.onNodeWithTag("diary_amount").assertIsNotEnabled()
            compose.onNodeWithTag("diary_time").assertIsNotEnabled()
        }
    }

    @Test fun deleteRequiresConfirmationAndDoesNotInvokeEdit() {
        var deleted: LocalDiaryEntryEntity? = null
        screen({ DiaryUiState(entries = listOf(entry()), loading = false) }, delete = { row, value -> assertTrue(value); deleted = row }) {
            compose.onNodeWithTag("diary_delete_meal-a").performClick()
            assertNull(deleted)
            compose.onNodeWithTag("diary_confirm_delete").performClick()
            assertEquals(entry(), deleted)
        }
    }

    @Test fun staleEditErrorRemainsVisibleWithDraftIntact() {
        screen({ DiaryUiState(draft = draft(DiaryKind.MEAL).copy(amount = "30"), error = DiaryError.CONFLICT) }) {
            compose.onNodeWithTag("diary_error").assertIsDisplayed()
            compose.onNodeWithTag("diary_editor").assertExists()
            compose.onNodeWithTag("diary_amount").assertTextContains("30")
        }
    }

    @Config(qualifiers = "ru-rRU-w320dp-h640dp-port")
    @Test fun russianListAndInjectionFitSmallScreenAndLargeFont() {
        var state by mutableStateOf(DiaryUiState(entries = listOf(entry()), loading = false))
        screen({ state }, scale = 1.5f) {
            compose.onNodeWithTag("diary_add_meal").assertIsDisplayed()
            val food = compose.onNodeWithTag("diary_add_meal").fetchSemanticsNode().boundsInRoot
            val insulin = compose.onNodeWithTag("diary_add_injection").fetchSemanticsNode().boundsInRoot
            assertTrue(insulin.top >= food.bottom)
            assertTrue(insulin.width > 280f)
            raster("diary-list-large")
            compose.runOnIdle { state = state.copy(draft = draft(DiaryKind.INJECTION)) }
            compose.onNodeWithTag("diary_save").assertIsDisplayed()
            compose.onNodeWithTag("diary_kind_OTHER").performScrollTo().assertIsDisplayed()
            raster("diary-injection-large")
        }
    }

    private fun raster(name: String) {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue(pixels.toSet().size > 32)
        val directory = File("build/reports/diary-raster").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun screen(state: () -> DiaryUiState, start: (DiaryKind) -> Unit = { error("Unexpected start") },
        change: (DiaryDraft?) -> Unit = { error("Unexpected change") }, save: () -> Unit = { error("Unexpected save") },
        delete: (LocalDiaryEntryEntity, Boolean) -> Unit = { _, _ -> error("Unexpected delete") },
        scale: Float = 1f, block: () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        root = controller.get().window.decorView
        try {
            controller.get().setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    AapsCopilotTheme(darkTheme = false, dynamicColor = false, uiStyle = UiStyle.CLARITY) {
                        LocalDiaryScreen(state(), start, { error("Unexpected edit") }, change, save, delete,
                            { error("Unexpected filter") }, { error("Unexpected reload") }, { error("Unexpected more") },
                            { error("Unexpected history") })
                    }
                }
            }
            compose.waitForIdle(); block(); compose.waitForIdle()
        } finally { controller.pause().stop().destroy() }
    }
}
