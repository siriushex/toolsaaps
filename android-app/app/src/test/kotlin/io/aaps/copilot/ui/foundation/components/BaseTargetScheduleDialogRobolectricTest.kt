package io.aaps.copilot.ui.foundation.components

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.domain.target.BaseTargetInterval
import io.aaps.copilot.domain.target.BaseTargetSchedule
import io.aaps.copilot.domain.target.CircadianAutoState
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BaseTargetScheduleDialogRobolectricTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun schedule() = BaseTargetSchedule(revision = 7, defaultTargetMmol = 5.8,
        intervals = listOf(BaseTargetInterval("night", 0, 360, 5.6), BaseTargetInterval("day", 360, 720, 6.0)))

    @Test fun collapsedIntervalCanChangeTargetWithoutEditingTime() {
        val saved = mutableListOf<BaseTargetSchedule>()
        val original = schedule()
        withDialog(original, onSave = saved::add).use {
            adjust("night", R.string.action_increase)
            adjust("day", R.string.action_decrease)
            assertTrue(saved.isEmpty())
            compose.onNodeWithTag("baseTargetScheduleSave").performClick()
            val result = saved.single()
            assertEquals(5.7, result.intervals.single { it.id == "night" }.targetMmol, 0.001)
            assertEquals(5.9, result.intervals.single { it.id == "day" }.targetMmol, 0.001)
            assertEquals(original.defaultTargetMmol, result.defaultTargetMmol, 0.0)
            assertEquals(original.intervals.map { it.id to (it.startMinuteOfDay to it.endMinuteOfDay) },
                result.intervals.map { it.id to (it.startMinuteOfDay to it.endMinuteOfDay) })
            assertEquals(original.revision, result.revision)
            assertNoOverflow()
            capture("base-target-direct-320.png")
        }
    }

    @Test fun directControlsKeepBoundsAndCancelDoesNotSave() {
        var saved = 0
        var dismissed = 0
        val original = schedule().copy(intervals = listOf(
            BaseTargetInterval("night", 0, 360, 4.0), BaseTargetInterval("day", 360, 720, 10.0)))
        withDialog(original, onSave = { saved++ }, onDismiss = { dismissed++ }).use {
            button("night", R.string.action_decrease).assertIsNotEnabled()
            button("day", R.string.action_increase).assertIsNotEnabled()
            adjust("night", R.string.action_increase)
            button("night", R.string.action_decrease).assertIsEnabled().performClick()
            button("night", R.string.action_decrease).assertIsNotEnabled()
            compose.onNodeWithTag("baseTargetScheduleCancel").performClick()
            assertEquals(0, saved)
            assertEquals(1, dismissed)
            assertEquals(4.0, original.intervals[0].targetMmol, 0.0)
        }
    }

    @Test fun editExpandsOnlyTimeAndKeepsOneTargetControl() {
        val saved = mutableListOf<BaseTargetSchedule>()
        val original = schedule().copy(intervals = listOf(BaseTargetInterval("night", 1320, 360, 5.6)))
        withDialog(original, onSave = saved::add).use {
            adjust("night", R.string.action_increase)
            compose.onNodeWithTag("baseTargetIntervalEdit-night").performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.base_target_schedule_start)).assertExists()
            compose.onNodeWithText(text(R.string.base_target_schedule_end)).assertExists()
            compose.onAllNodesWithTag("baseTargetIntervalTarget-night").assertCountEquals(1)
            compose.onNodeWithTag("baseTargetScheduleSave").performClick()
            assertEquals(1320, saved.single().intervals.single().startMinuteOfDay)
            assertEquals(360, saved.single().intervals.single().endMinuteOfDay)
        }
    }

    @Test fun invalidOverlapStillBlocksSaveAfterDirectChange() {
        val original = schedule().copy(intervals = listOf(
            BaseTargetInterval("night", 0, 360, 5.6), BaseTargetInterval("day", 300, 720, 6.0)))
        withDialog(original).use {
            adjust("night", R.string.action_increase)
            compose.onNodeWithTag("baseTargetScheduleSave").assertIsNotEnabled()
            compose.onAllNodesWithText(text(R.string.base_target_schedule_error_overlap)).assertCountEquals(2)
        }
    }

    @Test fun largeFontDarkControlsRemainReachableWithoutClipping() {
        withDialog(schedule(), dark = true, fontScale = 1.8f).use {
            adjust("night", R.string.action_increase)
            button("night", R.string.action_increase).assertHeightIsAtLeast(48.dp)
            adjust("day", R.string.action_decrease)
            assertNoOverflow()
            capture("base-target-direct-dark-large.png")
            compose.onNodeWithTag("baseTargetScheduleCancel").assertIsDisplayed()
        }
    }

    @Test fun largeDefaultLabelDoesNotBreakWords() {
        withDialog(schedule(), fontScale = 1.8f).use {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text(R.string.base_target_schedule_default), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val word = "умолчанию"
            val start = layout.layoutInput.text.text.indexOf(word)
            assertTrue(start >= 0)
            assertEquals("default label must not split its longest word", layout.getLineForOffset(start),
                layout.getLineForOffset(start + word.length - 1))
        }
    }

    private fun button(id: String, action: Int): SemanticsNodeInteraction {
        val row = compose.onNodeWithTag("baseTargetIntervalTarget-$id").performScrollTo().assertIsDisplayed()
        return row.onChildren().filterToOne(hasContentDescription(text(action)))
    }
    private fun adjust(id: String, action: Int) = button(id, action).performClick()
    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun withDialog(schedule: BaseTargetSchedule, dark: Boolean = false, fontScale: Float = 1f,
        onDismiss: () -> Unit = {}, onSave: (BaseTargetSchedule) -> Unit = {}): AutoCloseable {
        val app = RuntimeEnvironment.getApplication()
        val previous = Configuration(app.resources.configuration)
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(Configuration(previous).apply {
            this.fontScale = fontScale; setLocale(Locale.forLanguageTag("ru"))
        }, app.resources.displayMetrics)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        controller.get().setContent {
            AapsCopilotTheme(darkTheme = dark, dynamicColor = false, uiStyle = UiStyle.CLARITY) {
                BaseTargetScheduleDialog(schedule, 5.8, 0.0, CircadianAutoState.OFF, null,
                    4.0, 10.0, onDismiss, onSave)
            }
        }
        return AutoCloseable {
            controller.pause().stop().destroy()
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(previous, app.resources.displayMetrics)
        }
    }

    private fun assertNoOverflow() {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        repeat(nodes.fetchSemanticsNodes().size) { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            layouts.forEach { layout ->
                assertFalse(layout.layoutInput.text.text, layout.multiParagraph.didExceedMaxLines)
                repeat(layout.lineCount) { line ->
                    assertTrue(layout.layoutInput.text.text, layout.getLineLeft(line) >= -1f)
                    assertTrue(layout.layoutInput.text.text, layout.getLineRight(line) <= layout.size.width + 1f)
                }
            }
        }
    }

    private fun capture(name: String) {
        val directory = System.getenv("CLARITY_UI_EVIDENCE_DIR") ?: return
        compose.runOnIdle {
            val view = checkNotNull(ShadowDialog.getLatestDialog().window).decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
}
