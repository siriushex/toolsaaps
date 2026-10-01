package io.aaps.copilot.ui.foundation.screens

import android.app.Application
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import io.aaps.copilot.R
import io.aaps.copilot.domain.nutrition.MealCarbLimits
import io.aaps.copilot.domain.nutrition.MealPortionSettings
import io.aaps.copilot.ui.foundation.components.MealEntryDialog
import io.aaps.copilot.ui.foundation.components.MealEntryConfirmation
import io.aaps.copilot.domain.nutrition.MealPortionProvenance
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.Locale
import java.io.File
import android.graphics.Bitmap
import android.graphics.Canvas
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MealEntryLayoutRobolectricTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun enlargedRussianTextUsesRealFontScaleAndSeparateCheckboxRows() = verifyAppearance(1.8f, false)
    @Test fun enlargedDarkRussianDialogKeepsLabelsAndSendReachable() = verifyAppearance(1.8f, true)
    @Test fun normalLightRussianDialogKeepsCompactCheckboxRow() = verifyAppearance(1.0f, false)
    @Test fun normalDarkRussianDialogKeepsCompactCheckboxRow() = verifyAppearance(1.0f, true)
    @Test fun enlargedLightDialogRejectsOverflowAndConfirmsExactEightyOnce() = verifyAppearance(1.8f, false, true)
    @Test fun enlargedDarkDialogRejectsOverflowAndConfirmsExactEightyOnce() = verifyAppearance(1.8f, true, true)

    private fun verifyAppearance(fontScale: Float, dark: Boolean, interact: Boolean = false) {
        val app = RuntimeEnvironment.getApplication()
        val old = Configuration(app.resources.configuration)
        val enlarged = Configuration(old).apply {
            this.fontScale = fontScale
            setLocale(Locale.forLanguageTag("ru"))
        }
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(enlarged, app.resources.displayMetrics)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val confirmations = mutableListOf<MealEntryConfirmation>()
        try {
            controller.get().setContent {
                AapsCopilotTheme(darkTheme = dark, dynamicColor = false) {
                    MealEntryDialog(MealPortionSettings(), MealCarbLimits.MAX_MANUAL_MEAL_GRAMS,
                        {}, { if (interact) confirmations.add(it) else error("Visual test must not submit therapy") })
                }
            }
            val label = app.getString(R.string.meal_portion_small)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(
                SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("Dialog must actually render the requested font scale",
                fontScale, layouts.single().layoutInput.density.fontScale, 0.01f)
            val evidence = System.getenv("MEAL_UI_EVIDENCE_DIR")
            if (evidence != null) {
                val view = checkNotNull(ShadowDialog.getLatestDialog().window).decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val file = File(evidence, "meal-${if (dark) "dark" else "light"}-$fontScale.png")
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
            for (id in listOf(R.string.meal_portion_small, R.string.meal_portion_medium,
                R.string.meal_portion_large, R.string.food_profile_fast_label,
                R.string.food_profile_mixed_label, R.string.food_profile_slow_label)) {
                val result = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(app.getString(id), useUnmergedTree = true).performSemanticsAction(
                    SemanticsActions.GetTextLayoutResult) { it(result) }
                val text = result.single()
                assertTrue("A choice must not split its first word across lines",
                    text.getLineEnd(0, visibleEnd = true) >= app.getString(id).substringBefore(' ').length)
                assertFalse("Choice ${app.getString(id)} must not overflow: size=${text.size}, " +
                    "lines=${text.lineCount}, paragraph=${text.multiParagraph.height}, " +
                    "heightOverflow=${text.didOverflowHeight}, widthOverflow=${text.didOverflowWidth}",
                    text.hasVisualOverflow)
            }
            compose.onNodeWithTag("overviewEatingSoon").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("mealManualCarbs").performScrollTo().assertIsDisplayed()
            val eating = compose.onNodeWithTag("overviewEatingSoon").fetchSemanticsNode().boundsInRoot
            val manual = compose.onNodeWithTag("mealManualCarbs").fetchSemanticsNode().boundsInRoot
            if (fontScale > 1.3f) assertTrue("Large labels need separate rows", manual.top >= eating.bottom)
            else assertEquals("Normal labels retain a compact row", eating.top, manual.top, 0.5f)
            compose.onNodeWithTag("overviewPrepareMeal").assertIsDisplayed()
            compose.onNodeWithTag("overviewManualMealEnergyKcal").assertDoesNotExist()
            if (interact) {
                compose.onNodeWithTag("mealPortion_SMALL").performScrollTo().performClick()
                compose.onNodeWithTag("mealProfile_FAST").performScrollTo().performClick()
                assertTrue(confirmations.isEmpty())
                compose.onNodeWithTag("mealManualCarbs").performScrollTo().performClick()
                compose.onNodeWithTag("mealConfirmGrams").performScrollTo().performTextReplacement("80,001")
                compose.onNodeWithTag("overviewPrepareMeal").assertIsNotEnabled()
                compose.onNodeWithTag("mealConfirmGrams").performTextReplacement("80")
                compose.onNodeWithTag("overviewPrepareMeal").assertIsEnabled()
                compose.onNodeWithText(app.getString(R.string.meal_prepare_amount, "80")).assertIsDisplayed()
                compose.onNodeWithTag("overviewEatingSoon").performScrollTo().performClick()
                compose.onNodeWithTag("overviewPrepareMeal").performClick()
                assertEquals(1, confirmations.size)
                assertEquals(80.0, confirmations.single().grams, 0.0)
                assertNull(confirmations.single().energyKcal)
                assertFalse(confirmations.single().eatingSoon)
                assertEquals(MealPortionProvenance.USER_CORRECTED, confirmations.single().provenance)
                compose.onNodeWithTag("overviewPrepareMeal").assertDoesNotExist()
            }
        } finally {
            controller.pause().stop().destroy()
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(old, app.resources.displayMetrics)
        }
    }
}
