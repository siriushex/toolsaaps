package io.aaps.copilot.ui.foundation

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

internal fun NavHostController.navigateToPrimary(route: String, fromPrimary: Boolean) {
    if (!fromPrimary && popBackStack(route, inclusive = false)) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = fromPrimary
        }
        launchSingleTop = true
        // A secondary screen must not be saved/restored as the Overview tab.
        restoreState = fromPrimary
    }
}
