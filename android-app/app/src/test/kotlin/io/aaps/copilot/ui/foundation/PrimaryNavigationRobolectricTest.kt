package io.aaps.copilot.ui.foundation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PrimaryNavigationRobolectricTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun secondaryScreenCannotBeRestoredAsOverviewOrForecast() {
        withNavigation { nav ->
            repeat(2) {
                compose.runOnIdle { nav.navigate("settings") }
                compose.runOnIdle { nav.navigateToPrimary("overview", false) }
                compose.runOnIdle { assertEquals("overview", nav.currentDestination?.route) }
            }
            compose.runOnIdle { nav.navigateToPrimary("forecast", true) }
            compose.runOnIdle { nav.currentBackStackEntry!!.savedStateHandle["selection"] = 3 }
            compose.runOnIdle { nav.navigate("settings") }
            compose.runOnIdle { nav.navigateToPrimary("forecast", false) }
            compose.runOnIdle { assertEquals("forecast", nav.currentDestination?.route) }
            compose.runOnIdle { assertEquals(3, nav.currentBackStackEntry!!.savedStateHandle.get<Int>("selection")) }
        }
    }

    @Test fun primaryTabsStillRestoreTheirOwnSavedState() {
        withNavigation { nav ->
            compose.runOnIdle { nav.navigateToPrimary("forecast", true) }
            compose.runOnIdle { nav.currentBackStackEntry!!.savedStateHandle["selection"] = 6 }
            compose.runOnIdle { nav.navigateToPrimary("overview", true) }
            compose.runOnIdle { nav.navigateToPrimary("forecast", true) }
            compose.runOnIdle { assertEquals(6, nav.currentBackStackEntry!!.savedStateHandle.get<Int>("selection")) }
        }
    }

    private fun withNavigation(block: (NavHostController) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        lateinit var nav: NavHostController
        controller.get().setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "overview") {
                listOf("overview", "forecast", "analytics", "settings").forEach { route ->
                    composable(route) { Text(route) }
                }
            }
        }
        try {
            compose.waitForIdle()
            block(nav)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
