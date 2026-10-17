package io.aaps.copilot.ui.foundation.screens

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.aaps.copilot.R
import io.aaps.copilot.data.repository.*
import io.aaps.copilot.domain.nutrition.*
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.ui.foundation.components.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-w320dp-h640dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MealPhotoFlowRobolectricTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun enlargedPhotoChoicesRemainReachableWithoutUploading() {
        val app = RuntimeEnvironment.getApplication()
        val before = android.content.res.Configuration(app.resources.configuration)
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(android.content.res.Configuration(before).apply { fontScale = 1.8f },
            app.resources.displayMetrics)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent {
                AapsCopilotTheme(dynamicColor = false) {
                    MealPhotoFlowDialog(80.0, { MealPhotoAvailability.READY },
                        { error("Visual test must not upload") }, {}, { error("No meal may be applied") })
                }
            }
            val camera = compose.onNodeWithTag("mealPhotoCamera").performScrollTo().assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            val gallery = compose.onNodeWithTag("mealPhotoGallery").performScrollTo().assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            assertTrue(gallery.top >= camera.bottom)
            saveEvidence("meal-photo-large", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
        } finally {
            controller.pause().stop().destroy()
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(before, app.resources.displayMetrics)
        }
    }

    @Test fun unconfiguredServerDoesNotOfferImageOrRunAnalysis() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            controller.get().setContent {
                AapsCopilotTheme(dynamicColor = false) {
                    MealPhotoFlowDialog(80.0, { MealPhotoAvailability.NEEDS_CONNECTION },
                        { error("Unconfigured route must never analyze") }, {}, { error("No meal may be applied") })
                }
            }
            compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.meal_photo_needs_connection))
                .assertIsDisplayed()
            compose.onNodeWithTag("mealPhotoCamera").assertDoesNotExist()
            compose.onNodeWithTag("mealPhotoGallery").assertDoesNotExist()
            compose.onNodeWithTag("mealPhotoUse").assertDoesNotExist()
            saveEvidence("meal-photo-unconfigured", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun openingAndCancellingPhotoKeepsManualFoodAndNeverSubmitsIt() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val meals = mutableListOf<MealEntryConfirmation>()
        try {
            controller.get().setContent {
                AapsCopilotTheme(dynamicColor = false) {
                    MealEntryDialog(MealPortionSettings(), 80.0, {}, { meals.add(it) },
                        { MealPhotoAvailability.UNSUPPORTED }, { error("Text-only route must not analyze") })
                }
            }
            compose.onNodeWithTag("mealManualCarbs").performClick()
            compose.onNodeWithTag("mealConfirmGrams").performScrollTo().performTextReplacement("42")
            compose.onNodeWithTag("mealPhotoOpen").performClick()
            compose.onNodeWithTag("mealPhotoCamera").assertDoesNotExist()
            compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.action_cancel)).performClick()
            compose.onNodeWithTag("mealConfirmGrams").performScrollTo().assertTextContains("42")
            assertTrue(meals.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun reviewRequiresMassAndScalesPerHundredGramFactsAndEatenFraction() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var latest = MealPhotoDraftEditor.initial(estimate())
        try {
            controller.get().setContent {
                var state by remember { mutableStateOf(latest) }
                AapsCopilotTheme(dynamicColor = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        MealPhotoReview(state, { state = it; latest = it }, "", {}, 80.0)
                    }
                }
            }
            compose.onNodeWithTag("mealPhotoTotals").assertDoesNotExist()
            compose.onNodeWithTag("mealPhotoMass_rice").performTextReplacement("200")
            compose.onNodeWithTag("mealPhotoTotals").performScrollTo().assertIsDisplayed()
            fun carbs() = ((MealNutritionCalculator.calculate(
                (MealPhotoDraftEditor.build(latest) as MealPhotoDraftBuildResult.Ready).draft)
                as MealNutritionCalculationResult.Calculated).value.carbohydrates.completeTotalGrams)!!
            assertEquals(50.0, carbs().minimum, 0.0)
            assertEquals(60.0, carbs().maximum, 0.0)
            compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.meal_photo_eaten))
                .performScrollTo().performTextReplacement("50")
            assertEquals(25.0, carbs().minimum, 0.0)
            saveEvidence("meal-photo-review", controller.get().window.decorView)
            assertEquals("", compose.onNodeWithTag("mealPhotoConfirmedCarbs").performScrollTo()
                .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            compose.onNodeWithTag("mealPhotoMass_rice").performScrollTo().performTextReplacement("999")
            compose.onNodeWithTag("mealPhotoTotals").assertDoesNotExist()
        } finally { controller.pause().stop().destroy() }
    }

    private fun saveEvidence(name: String, view: android.view.View) {
        val directory = System.getenv("MEAL_UI_EVIDENCE_DIR") ?: return
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            view.draw(android.graphics.Canvas(bitmap))
            java.io.File(directory, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun estimate() = MealPhotoNutritionEstimate(1, "synthetic-meal", "Rice",
        listOf(MealPhotoIngredientEstimate("rice", "Rice", NutritionRange(180.0, 220.0),
            PreparationState.COOKED, CarbohydrateBasis.TOTAL, NutritionRange(25.0, 30.0),
            NutritionRange(2.0, 3.0), NutritionRange(1.0, 2.0), NutritionRange(0.0, 1.0),
            NutritionRange(1.0, 2.0), null, PhotoEnergyEstimate(NutritionRange(100.0, 120.0), EnergyUnit.KCAL))),
        MealAbsorptionProfile.MIXED, 120)
}
