package io.aaps.copilot.ui.foundation.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.aaps.copilot.R
import io.aaps.copilot.IsolatedUiHostRule
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoodProfileSelectorTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(IsolatedUiHostRule()).around(composeRule)

    @Test
    fun selectorUsesOneAccessibleSelectionAndMinimumTouchTargets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fastDescription = context.getString(R.string.food_profile_fast_content_description)
        val mixedDescription = context.getString(R.string.food_profile_mixed_content_description)
        val slowDescription = context.getString(R.string.food_profile_slow_content_description)
        var selected by mutableStateOf(MealAbsorptionProfile.MIXED)

        composeRule.setContent {
            AapsCopilotTheme {
                FoodProfileSelector(
                    selected = selected,
                    onSelected = { selected = it }
                )
            }
        }

        composeRule.onNodeWithContentDescription(mixedDescription).assertIsSelected()
        composeRule.onNodeWithContentDescription(fastDescription).assertHeightIsAtLeast(44.dp)
        composeRule.onNodeWithContentDescription(slowDescription).assertHeightIsAtLeast(44.dp)
        composeRule.onNodeWithContentDescription(fastDescription).performClick().assertIsSelected()
        composeRule.onNodeWithContentDescription(mixedDescription).assertIsNotSelected()
        composeRule.onNodeWithContentDescription(slowDescription).assertIsNotSelected()
        composeRule.runOnIdle { assertEquals(MealAbsorptionProfile.FAST, selected) }
    }
}
