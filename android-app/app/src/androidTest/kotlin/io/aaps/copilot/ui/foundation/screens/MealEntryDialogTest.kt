package io.aaps.copilot.ui.foundation.screens

import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.aaps.copilot.domain.nutrition.MealPortionSettings
import io.aaps.copilot.domain.nutrition.MealPortionProvenance
import io.aaps.copilot.domain.nutrition.MealCarbLimits
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.components.MealEntryDialog
import io.aaps.copilot.ui.foundation.components.MealEntryConfirmation
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MealEntryDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun lightThemeChoicesAndActionAreReachable() = verifyAppearance(false)
    @Test fun darkThemeChoicesAndActionAreReachable() = verifyAppearance(true)
    @Test fun enlargedLightLabelsUseActualFontScaleWithoutOverflow() = verifyAppearance(false, 1.8f)
    @Test fun enlargedDarkLabelsUseActualFontScaleWithoutOverflow() = verifyAppearance(true, 1.8f)

    private fun verifyAppearance(isDark: Boolean, fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                AapsCopilotTheme(darkTheme = isDark, dynamicColor = false) {
                    MealEntryDialog(MealPortionSettings(), MealCarbLimits.MAX_MANUAL_MEAL_GRAMS,
                        {}, { error("No therapy submission in visual test") })
                }
            }
        }
        compose.waitForIdle()
        for (tag in listOf("mealPortion_SMALL", "mealPortion_MEDIUM", "mealPortion_LARGE",
            "mealProfile_FAST", "mealProfile_MIXED", "mealProfile_FAT_PROTEIN")) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("overviewPrepareMeal").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (id in listOf(R.string.meal_portion_small, R.string.meal_portion_medium,
            R.string.meal_portion_large, R.string.food_profile_fast_label,
            R.string.food_profile_mixed_label, R.string.food_profile_slow_label)) {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(context.getString(id), useUnmergedTree = true).performSemanticsAction(
                SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            assertEquals("Actual text density, not a screenshot filename", fontScale,
                layout.layoutInput.density.fontScale, 0.01f)
            assertFalse("Caption must not overflow", layout.hasVisualOverflow)
            assertTrue("Caption must not split a word", layout.getLineEnd(0, visibleEnd = true) >=
                context.getString(id).substringBefore(' ').length)
        }
        compose.onNodeWithTag("mealPortion_SMALL").performScrollTo()
        capture("device-meal-${if (isDark) "dark" else "light"}-$fontScale")
    }

    private fun capture(name: String) {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "$name.png")
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun enlargedLightManualEightyIsExactAndOverCapIsBlocked() = verifyEighty(false)
    @Test fun enlargedDarkManualEightyIsExactAndOverCapIsBlocked() = verifyEighty(true)

    private fun verifyEighty(isDark: Boolean) {
        val sent = mutableListOf<MealEntryConfirmation>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                AapsCopilotTheme(darkTheme = isDark, dynamicColor = false) {
                    MealEntryDialog(MealPortionSettings(), MealCarbLimits.MAX_MANUAL_MEAL_GRAMS, {}, sent::add)
                }
            }
        }
        compose.onNodeWithTag("mealManualCarbs").performScrollTo().performClick()
        compose.onNodeWithTag("mealConfirmGrams").performScrollTo().performTextReplacement("80,001")
        compose.onNodeWithTag("overviewPrepareMeal").assertIsNotEnabled()
        capture("device-meal-${if (isDark) "dark" else "light"}-invalid")
        compose.onNodeWithTag("mealConfirmGrams").performTextReplacement("80")
        compose.onNodeWithTag("overviewEatingSoon").performScrollTo().performClick()
        capture("device-meal-${if (isDark) "dark" else "light"}-ready80")
        compose.onNodeWithTag("overviewPrepareMeal").performClick()
        compose.onNodeWithTag("overviewPrepareMeal").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, sent.size)
            assertEquals(80.0, sent.single().grams, 0.0)
            assertEquals(MealPortionProvenance.USER_CORRECTED, sent.single().provenance)
            assertFalse(sent.single().eatingSoon)
            assertEquals(null, sent.single().energyKcal)
        }
    }

    @Test fun selectionsAndCancelDoNotSubmitAndCaloriesAreHidden() {
        var submitted = 0
        var dismissed = 0
        compose.setContent {
            AapsCopilotTheme {
                MealEntryDialog(MealPortionSettings(), 60.0, { dismissed++ }, { submitted++ })
            }
        }
        compose.onNodeWithTag("mealPortion_MEDIUM").assertIsSelected()
        compose.onNodeWithTag("mealManualCarbs").assertIsOff()
        compose.onNodeWithTag("mealConfirmGrams").assertDoesNotExist()
        compose.onNodeWithTag("mealPortion_LARGE").performClick().assertIsSelected()
        compose.onNodeWithTag("mealPortion_MEDIUM").assertIsNotSelected()
        compose.onNodeWithTag("mealProfile_FAST").performClick().assertIsSelected()
        compose.onNodeWithTag("mealProfile_MIXED").assertIsNotSelected()
        compose.onNodeWithTag("overviewManualMealEnergyKcal").assertDoesNotExist()
        compose.onNodeWithTag("mealEntryClose").performClick()
        compose.runOnIdle { assertEquals(0, submitted); assertEquals(1, dismissed) }
    }

    @Test fun oversizedPresetRequiresExplicitCorrectionWithoutClamping() {
        val defaults = MealPortionSettings()
        val settings = defaults.copy(large = defaults.large.copy(defaultGrams = 80.0))
        val sent = mutableListOf<MealEntryConfirmation>()
        compose.setContent {
            AapsCopilotTheme { MealEntryDialog(settings, 60.0, {}, sent::add) }
        }
        compose.onNodeWithTag("mealPortion_LARGE").performClick()
        compose.onNodeWithTag("overviewPrepareMeal").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, sent.size) }
        compose.onNodeWithTag("mealManualCarbs").performScrollTo().performClick()
        compose.onNodeWithTag("mealConfirmGrams").performTextReplacement("59.25")
        compose.onNodeWithTag("overviewPrepareMeal").performClick()
        compose.runOnIdle {
            assertEquals(1, sent.size)
            assertEquals(59.25, sent.single().grams, 0.0)
            assertEquals(MealPortionProvenance.USER_CORRECTED, sent.single().provenance)
        }
    }

    @Test fun liveSettingsCannotChangeConfirmationAndSubmittedDialogCloses() {
        val settings = mutableStateOf(MealPortionSettings())
        val sent = mutableListOf<MealEntryConfirmation>()
        compose.setContent {
            AapsCopilotTheme { MealEntryDialog(settings.value, 60.0, {}, sent::add) }
        }
        compose.onNodeWithTag("overviewPrepareMeal").performClick()
        compose.runOnIdle {
            settings.value = settings.value.copy(medium = settings.value.medium.copy(defaultGrams = 35.0))
            assertEquals(1, sent.size)
        }
        compose.onNodeWithTag("overviewPrepareMeal").assertDoesNotExist()
        compose.onNodeWithTag("overviewConfirmMeal").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, sent.size)
            assertEquals(25.0, sent.single().grams, 0.001)
            assertEquals(MealPortionProvenance.ACCEPTED_SUGGESTION, sent.single().provenance)
        }
    }
}
