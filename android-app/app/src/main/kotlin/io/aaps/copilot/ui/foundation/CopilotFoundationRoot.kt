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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.aaps.copilot.R
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.ui.MainViewModel
import io.aaps.copilot.ui.foundation.components.AppHealthBanner
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.screens.AnalyticsScreen
import io.aaps.copilot.ui.foundation.screens.AiAnalysisScreen
import io.aaps.copilot.ui.foundation.screens.AuditScreen
import io.aaps.copilot.ui.foundation.screens.ForecastScreen
import io.aaps.copilot.ui.foundation.screens.OverviewScreen
import io.aaps.copilot.ui.foundation.screens.SafetyScreen
import io.aaps.copilot.ui.foundation.screens.SettingsScreen
import io.aaps.copilot.ui.foundation.screens.UamScreen
import io.aaps.copilot.ui.foundation.theme.CopilotStyledBackground
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.rememberDrawerState

private sealed class RootDestination(val route: String)
private sealed class BottomDestination(route: String, val titleRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) : RootDestination(route)
private sealed class MoreDestination(route: String, val titleRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) : RootDestination(route)

private object Destinations {
    object Overview : BottomDestination("overview", R.string.nav_overview, Icons.Default.Home)
    object Forecast : BottomDestination("forecast", R.string.nav_forecast, Icons.Default.ShowChart)
    object Uam : BottomDestination("uam", R.string.nav_uam, Icons.Default.Undo)
    object Safety : BottomDestination("safety", R.string.nav_safety, Icons.Default.Security)

    object Audit : MoreDestination("audit", R.string.menu_audit_log, Icons.Default.TableChart)
    object AiAnalysis : MoreDestination("ai_analysis", R.string.menu_ai_analysis, Icons.Default.Assessment)
    object Analytics : MoreDestination("analytics", R.string.menu_analytics, Icons.Default.Assessment)
    object Settings : MoreDestination("settings", R.string.menu_settings, Icons.Default.Tune)

    val bottom = listOf(Overview, Forecast, Uam, Safety)
    val more = listOf(Audit, AiAnalysis, Analytics, Settings)
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
    val aiAnalysis by viewModel.aiAnalysisUiState.collectAsStateWithLifecycle()
    val analytics by viewModel.analyticsUiState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsUiState.collectAsStateWithLifecycle()
    val message by viewModel.messageUiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val selectedUiStyle = UiStyle.fromRaw(settings.uiStyle)

    LaunchedEffect(message) {
        val text = message
        if (!text.isNullOrBlank()) {
            snackbarHostState.showSnackbar(text)
            viewModel.clearMessage()
        }
    }

    CopilotStyledBackground(
        uiStyle = selectedUiStyle,
        modifier = Modifier.fillMaxSize()
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                Text(
                    text = stringResource(id = R.string.menu_more),
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                )
                Destinations.more.forEach { destination ->
                    NavigationDrawerItem(
                        label = { Text(text = stringResource(id = destination.titleRes)) },
                        selected = currentRoute == destination.route,
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(destination.route) {
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    label = { Text(text = stringResource(id = R.string.app_health_title)) },
                    selected = false,
                    onClick = { scope.launch { drawerState.close() } },
                    icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                }
            }
        ) {
            Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopBar(
                    title = screenTitle(currentRoute),
                    staleData = appHealth.staleData,
                    killSwitchEnabled = appHealth.killSwitchEnabled,
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onOpenHealth = {
                        navController.navigate(Destinations.Audit.route) {
                            launchSingleTop = true
                        }
                    },
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
                    composable(Destinations.Overview.route) {
                        OverviewScreen(
                            state = overview,
                            onRunCycleNow = viewModel::runCycleNow,
                            onSetKillSwitch = viewModel::setKillSwitch
                        )
                    }
                    composable(Destinations.Forecast.route) {
                        ForecastScreen(
                            state = forecast,
                            onSelectRange = viewModel::setForecastRange,
                            onLayerChange = { layers ->
                                viewModel.setForecastLayers(
                                    showTrend = layers.showTrend,
                                    showTherapy = layers.showTherapy,
                                    showUam = layers.showUam,
                                    showCi = layers.showCi
                                )
                            }
                        )
                    }
                    composable(Destinations.Uam.route) {
                        UamScreen(
                            state = uam,
                            onMarkCorrect = viewModel::markUamEventCorrect,
                            onMarkWrong = viewModel::markUamEventWrong,
                            onMergeWithManual = viewModel::mergeUamEventWithManualCarbs,
                            onExportToAaps = viewModel::exportUamEventToAaps
                        )
                    }
                    composable(Destinations.Safety.route) {
                        SafetyScreen(
                            state = safety,
                            onKillSwitchToggle = viewModel::setKillSwitch,
                            onSafetyBoundsChange = viewModel::setSafetyTargetBounds
                        )
                    }
                    composable(Destinations.Audit.route) {
                        AuditScreen(
                            state = audit,
                            onSelectWindow = viewModel::setAuditWindow,
                            onOnlyErrorsChange = viewModel::setAuditOnlyErrors
                        )
                    }
                    composable(Destinations.AiAnalysis.route) {
                        AiAnalysisScreen(
                            state = aiAnalysis,
                            onRunDailyAnalysis = viewModel::runDailyAnalysisNow,
                            onRefreshCloudJobs = { viewModel.refreshCloudJobs(silent = false) },
                            onRefreshInsights = { viewModel.refreshAnalysisHistory(silent = false) },
                            onApplyFilters = viewModel::applyInsightsFilters,
                            onRunReplay = viewModel::runCloudReplayNow,
                            onExportInsightsCsv = viewModel::exportInsightsCsv,
                            onExportInsightsPdf = viewModel::exportInsightsPdf,
                            onExportReplayCsv = viewModel::exportReplayCsv,
                            onExportReplayPdf = viewModel::exportReplayPdf,
                            onSendChatPrompt = viewModel::sendAiChatPrompt
                        )
                    }
                    composable(Destinations.Analytics.route) {
                        AnalyticsScreen(
                            state = analytics,
                            onRunDailyAnalysis = viewModel::runDailyAnalysisNow,
                            onInsulinProfileActivate = viewModel::setInsulinProfile
                        )
                    }
                    composable(Destinations.Settings.route) {
                        val updateUamRuntime: (
                            enableInference: Boolean,
                            enableBoost: Boolean,
                            enableExport: Boolean,
                            dryRunExport: Boolean
                        ) -> Unit = { enableInference, enableBoost, enableExport, dryRunExport ->
                            viewModel.setUamRuntimeConfig(
                                enableInference = enableInference,
                                enableBoost = enableBoost,
                                enableExport = enableExport,
                                exportModeRaw = settings.uamExportMode,
                                dryRunExport = dryRunExport
                            )
                        }
                        SettingsScreen(
                            state = settings,
                            onVerboseLogsToggle = viewModel::setVerboseLogsEnabled,
                            onProModeToggle = viewModel::setProModeEnabled,
                            onNightscoutUrlSave = viewModel::setNightscoutUrl,
                            onAiApiSettingsSave = viewModel::setAiApiSettings,
                            onUiStyleChange = viewModel::setUiStyle,
                            onBaseTargetChange = viewModel::setBaseTarget,
                            onInsulinProfileSelect = viewModel::setInsulinProfile,
                            onLocalNightscoutToggle = viewModel::setLocalNightscoutEnabled,
                            onLocalBroadcastIngestToggle = viewModel::setLocalBroadcastIngestEnabled,
                            onStrictSenderValidationToggle = viewModel::setStrictBroadcastValidation,
                            onUamExportModeChange = viewModel::setUamExportMode,
                            onUamSnackConfigChange = viewModel::setUamSnackConfig,
                            onUamInferenceToggle = { enabled ->
                                updateUamRuntime(
                                    enabled,
                                    settings.enableUamBoost,
                                    settings.enableUamExportToAaps,
                                    settings.dryRunExport
                                )
                            },
                            onUamBoostToggle = { enabled ->
                                updateUamRuntime(
                                    settings.enableUamInference,
                                    enabled,
                                    settings.enableUamExportToAaps,
                                    settings.dryRunExport
                                )
                            },
                            onUamExportToggle = { enabled ->
                                updateUamRuntime(
                                    settings.enableUamInference,
                                    settings.enableUamBoost,
                                    enabled,
                                    settings.dryRunExport
                                )
                            },
                            onUamDryRunToggle = { enabled ->
                                updateUamRuntime(
                                    settings.enableUamInference,
                                    settings.enableUamBoost,
                                    settings.enableUamExportToAaps,
                                    enabled
                                )
                            },
                            onIsfCrShadowModeToggle = viewModel::setIsfCrShadowMode,
                            onIsfCrConfidenceThresholdChange = viewModel::setIsfCrConfidenceThreshold,
                            onIsfCrUseActivityToggle = viewModel::setIsfCrUseActivity,
                            onIsfCrUseManualTagsToggle = viewModel::setIsfCrUseManualTags,
                            onIsfCrMinEvidencePerHourChange = viewModel::setIsfCrMinEvidencePerHour,
                            onIsfCrCrIntegrityGateSettingsChange = viewModel::setIsfCrCrIntegrityGateSettings,
                            onIsfCrRetentionChange = viewModel::setIsfCrRetention,
                            onIsfCrAutoActivationEnabledToggle = viewModel::setIsfCrAutoActivationEnabled,
                            onIsfCrAutoActivationLookbackHoursChange = viewModel::setIsfCrAutoActivationLookbackHours,
                            onIsfCrAutoActivationMinSamplesChange = viewModel::setIsfCrAutoActivationMinSamples,
                            onIsfCrAutoActivationMinMeanConfidenceChange = viewModel::setIsfCrAutoActivationMinMeanConfidence,
                            onIsfCrAutoActivationMaxMeanAbsDeltaPctChange = viewModel::setIsfCrAutoActivationMaxMeanAbsDeltaPct,
                            onIsfCrAutoActivationSensorThresholdsChange = viewModel::setIsfCrAutoActivationSensorThresholds,
                            onIsfCrAutoActivationDayTypeThresholdsChange = viewModel::setIsfCrAutoActivationDayTypeThresholds,
                            onIsfCrAutoActivationRequireDailyQualityGateToggle = viewModel::setIsfCrAutoActivationRequireDailyQualityGate,
                            onIsfCrAutoActivationDailyRiskBlockLevelChange = viewModel::setIsfCrAutoActivationDailyRiskBlockLevel,
                            onIsfCrAutoActivationDailyQualityThresholdsChange = viewModel::setIsfCrAutoActivationDailyQualityThresholds,
                            onIsfCrAutoActivationRollingGateSettingsChange = viewModel::setIsfCrAutoActivationRollingGateSettings,
                            onAddPhysioTag = viewModel::addPhysioTag,
                            onClosePhysioTag = viewModel::closePhysioTag,
                            onClearPhysioTags = viewModel::clearActivePhysioTags,
                            onAdaptiveControllerToggle = viewModel::setAdaptiveControllerEnabled,
                            onSafetyBoundsChange = viewModel::setSafetyTargetBounds,
                            onPostHypoThresholdChange = viewModel::setPostHypoThreshold,
                            onPostHypoTargetChange = viewModel::setPostHypoTarget,
                            onRetentionDaysChange = viewModel::setRetentionDays
                        )
                    }
                }
            }
        }
    }
}
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TopBar(
    title: String,
    staleData: Boolean,
    killSwitchEnabled: Boolean,
    onMenuClick: () -> Unit,
    onOpenHealth: () -> Unit,
    onNavigateMore: (MoreDestination) -> Unit
) {
    var moreExpanded by remember { mutableStateOf(false) }
    val containerColor = when {
        killSwitchEnabled -> MaterialTheme.colorScheme.errorContainer
        staleData -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        killSwitchEnabled -> MaterialTheme.colorScheme.onErrorContainer
        staleData -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusCount = listOf(staleData, killSwitchEnabled).count { it }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            titleContentColor = contentColor,
            actionIconContentColor = contentColor,
            navigationIconContentColor = contentColor
        ),
        title = { Text(text = title) },
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = if (staleData || killSwitchEnabled) Icons.Default.Warning else Icons.Default.Menu,
                    contentDescription = stringResource(id = R.string.menu_more)
                )
            }
        },
        actions = {
            BadgedBox(
                badge = {
                    if (statusCount > 0) {
                        Badge { Text(text = statusCount.toString()) }
                    }
                }
            ) {
                IconButton(onClick = onOpenHealth) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = stringResource(id = R.string.app_health_title)
                    )
                }
            }
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
        Destinations.AiAnalysis.route -> stringResource(id = R.string.menu_ai_analysis)
        Destinations.Analytics.route -> stringResource(id = R.string.menu_analytics)
        Destinations.Settings.route -> stringResource(id = R.string.menu_settings)
        else -> stringResource(id = R.string.nav_overview)
    }
}
