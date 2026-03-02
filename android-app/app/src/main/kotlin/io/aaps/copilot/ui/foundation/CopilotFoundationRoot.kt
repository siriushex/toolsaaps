package io.aaps.copilot.ui.foundation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.aaps.copilot.R
import io.aaps.copilot.ui.MainViewModel
import io.aaps.copilot.ui.foundation.components.AppHealthBanner
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.screens.AnalyticsScreen
import io.aaps.copilot.ui.foundation.screens.AuditScreen
import io.aaps.copilot.ui.foundation.screens.ForecastScreen
import io.aaps.copilot.ui.foundation.screens.OverviewScreen
import io.aaps.copilot.ui.foundation.screens.SafetyScreen
import io.aaps.copilot.ui.foundation.screens.SettingsScreen
import io.aaps.copilot.ui.foundation.screens.UamScreen

private sealed class RootDestination(val route: String)
private sealed class BottomDestination(route: String, val titleRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) : RootDestination(route)
private sealed class MoreDestination(route: String, val titleRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) : RootDestination(route)

private object Destinations {
    object Overview : BottomDestination("overview", R.string.nav_overview, Icons.Default.Home)
    object Forecast : BottomDestination("forecast", R.string.nav_forecast, Icons.Default.ShowChart)
    object Uam : BottomDestination("uam", R.string.nav_uam, Icons.Default.Undo)
    object Safety : BottomDestination("safety", R.string.nav_safety, Icons.Default.Security)

    object Audit : MoreDestination("audit", R.string.menu_audit_log, Icons.Default.TableChart)
    object Analytics : MoreDestination("analytics", R.string.menu_analytics, Icons.Default.Assessment)
    object Settings : MoreDestination("settings", R.string.menu_settings, Icons.Default.Tune)

    val bottom = listOf(Overview, Forecast, Uam, Safety)
    val more = listOf(Audit, Analytics, Settings)
}

@Composable
fun CopilotFoundationRoot(
    viewModel: MainViewModel = viewModel()
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val appHealth by viewModel.appHealthUiState.collectAsStateWithLifecycle()
    val overview by viewModel.overviewUiState.collectAsStateWithLifecycle()
    val forecast by viewModel.forecastUiState.collectAsStateWithLifecycle()
    val uam by viewModel.uamUiState.collectAsStateWithLifecycle()
    val safety by viewModel.safetyUiState.collectAsStateWithLifecycle()
    val audit by viewModel.auditUiState.collectAsStateWithLifecycle()
    val analytics by viewModel.analyticsUiState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsUiState.collectAsStateWithLifecycle()
    val message by viewModel.messageUiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        val text = message
        if (!text.isNullOrBlank()) {
            snackbarHostState.showSnackbar(text)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopBar(
                title = screenTitle(currentRoute),
                onNavigateMore = { destination ->
                    navController.navigate(destination.route) {
                        launchSingleTop = true
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                Destinations.bottom.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = stringResource(id = destination.titleRes)) },
                        label = { Text(text = stringResource(id = destination.titleRes)) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
        ) {
            AppHealthBanner(
                staleData = appHealth.staleData,
                killSwitchEnabled = appHealth.killSwitchEnabled,
                lastSyncText = appHealth.lastSyncText,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            NavHost(
                navController = navController,
                startDestination = Destinations.Overview.route,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(Destinations.Overview.route) { OverviewScreen(state = overview) }
                composable(Destinations.Forecast.route) { ForecastScreen(state = forecast) }
                composable(Destinations.Uam.route) { UamScreen(state = uam) }
                composable(Destinations.Safety.route) { SafetyScreen(state = safety) }
                composable(Destinations.Audit.route) { AuditScreen(state = audit) }
                composable(Destinations.Analytics.route) { AnalyticsScreen(state = analytics) }
                composable(Destinations.Settings.route) { SettingsScreen(state = settings) }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TopBar(
    title: String,
    onNavigateMore: (MoreDestination) -> Unit
) {
    var moreExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(text = title) },
        actions = {
            IconButton(onClick = { moreExpanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(id = R.string.menu_more)
                )
            }
            DropdownMenu(
                expanded = moreExpanded,
                onDismissRequest = { moreExpanded = false }
            ) {
                Destinations.more.forEach { destination ->
                    DropdownMenuItem(
                        text = { Text(text = stringResource(id = destination.titleRes)) },
                        leadingIcon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = stringResource(id = destination.titleRes)
                            )
                        },
                        onClick = {
                            moreExpanded = false
                            onNavigateMore(destination)
                        }
                    )
                }
            }
        }
    )
}

@Composable
private fun screenTitle(route: String?): String {
    return when (route) {
        Destinations.Forecast.route -> stringResource(id = R.string.nav_forecast)
        Destinations.Uam.route -> stringResource(id = R.string.nav_uam)
        Destinations.Safety.route -> stringResource(id = R.string.nav_safety)
        Destinations.Audit.route -> stringResource(id = R.string.menu_audit_log)
        Destinations.Analytics.route -> stringResource(id = R.string.menu_analytics)
        Destinations.Settings.route -> stringResource(id = R.string.menu_settings)
        else -> stringResource(id = R.string.nav_overview)
    }
}
