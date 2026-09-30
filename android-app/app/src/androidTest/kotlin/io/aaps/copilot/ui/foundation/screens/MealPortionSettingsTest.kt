package io.aaps.copilot.ui.foundation.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.aaps.copilot.domain.nutrition.MealPortionSettings
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MealPortionSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun invalidInputNeverSavesAndValidEditPreservesOtherRanges() {
        val saved = mutableListOf<MealPortionSettings>()
        compose.setContent {
            AapsCopilotTheme {
                MealPortionSettingsEditor(
                    value = MealPortionSettings(), onSave = saved::add,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
        }
        compose.onNodeWithTag("mealPortion_SMALL_default").performScrollTo().performTextReplacement("99")
        compose.onNodeWithTag("mealPortionsSave").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, saved.size) }
        compose.onNodeWithTag("mealPortion_SMALL_default").performScrollTo().performTextReplacement("12,5")
        compose.onNodeWithTag("mealPortionsSave").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, saved.size)
            assertEquals(12.5, saved.single().small.defaultGrams, 0.001)
            assertEquals(MealPortionSettings().medium, saved.single().medium)
            assertEquals(false, saved.single().showCalories)
        }
    }
}
