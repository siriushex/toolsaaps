package io.aaps.copilot.ui.foundation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import io.aaps.copilot.ui.foundation.components.OverviewBaseTargetBanner
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.screens.AnalyticsScreen
import io.aaps.copilot.ui.foundation.screens.AiAnalysisScreen
import io.aaps.copilot.ui.foundation.screens.AuditScreen
import io.aaps.copilot.ui.foundation.screens.ForecastScreen
import io.aaps.copilot.ui.foundation.screens.OverviewScreen
import io.aaps.copilot.ui.foundation.screens.SafetyScreen
import io.aaps.copilot.ui.foundation.screens.SettingsScreen
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import io.aaps.copilot.ui.foundation.theme.CopilotStyledBackground

private sealed class RootDestination(val route: String)
private sealed class BottomDestination(route: String, val titleRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) : RootDestination(route)
private sealed class MoreDestination(route: String, val titleRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) : RootDestination(route)

private object Destinations {
    object Overview : BottomDestination("overview", R.string.nav_overview, Icons.Default.Home)
    object Forecast : BottomDestination("forecast", R.string.nav_forecast, Icons.Default.ShowChart)
    object Analytics : BottomDestination("analytics", R.string.menu_analytics, Icons.Default.Assessment)
    object Safety : BottomDestination("safety", R.string.nav_safety, Icons.Default.Security)

    object Audit : MoreDestination("audit", R.string.menu_audit_log, Icons.Default.TableChart)
    object AiAnalysis : MoreDestination("ai_analysis", R.string.menu_ai_analysis, Icons.Default.Assessment)
    object Settings : MoreDestination("settings", R.string.menu_settings, Icons.Default.Tune)

    val bottom = listOf(Overview, Forecast, Analytics, Safety)
    val more = listOf(Audit, AiAnalysis, Settings)
}

@Composable
fun CopilotFoundationRoot(
    viewModel: MainViewModel = viewModel()
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val message by viewModel.messageUiState.collectAsStateWithLifecycle()
    val appHealth by viewModel.appHealthUiState.collectAsStateWithLifecycle()
    val baseTargetBanner by viewModel.baseTargetBannerUiState.collectAsStateWithLifecycle()
    val uiStyleRaw by viewModel.uiStyleState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val selectedUiStyle = UiStyle.fromRaw(uiStyleRaw)
    var drawerOpen by remember { mutableStateOf(false) }
    var chromeDetailsReady by remember { mutableStateOf(false) }
    var routeShellReady by remember { mutableStateOf(false) }
    var openSensorLagTrendDetailRequest by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        val text = message
        if (!text.isNullOrBlank()) {
            snackbarHostState.showSnackbar(text)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(currentRoute) {
        viewModel.setActiveRoute(currentRoute)
        drawerOpen = false
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        chromeDetailsReady = true
        withFrameNanos { }
        routeShellReady = true
    }

    BackHandler(enabled = drawerOpen) {
        drawerOpen = false
    }

    val scaffoldContent: @Composable () -> Unit = {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopBar(
                        title = screenTitle(currentRoute),
                        uiStyle = selectedUiStyle,
                        staleData = appHealth.staleData,
                        killSwitchEnabled = appHealth.killSwitchEnabled,
                        showStatusIndicators = chromeDetailsReady,
                        onMenuClick = { drawerOpen = !drawerOpen },
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
                    NavigationBar(
                        containerColor = shellChromeColor(selectedUiStyle),
                        contentColor = shellChromeContentColor(selectedUiStyle)
                    ) {
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
                                icon = {
                                    Icon(
                                        destination.icon,
                                        contentDescription = stringResource(id = destination.titleRes)
                                    )
                                },
                                label = if (chromeDetailsReady) {
                                    { Text(text = stringResource(id = destination.titleRes)) }
                                } else {
                                    null
                                },
                                alwaysShowLabel = chromeDetailsReady,
                                colors = if (selectedUiStyle == UiStyle.MIDNIGHT_GLASS) {
                                    NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color(0xFF7FB3FF),
                                        selectedTextColor = Color(0xFF7FB3FF),
                                        indicatorColor = Color(0xFF1D4ED8),
                                        unselectedIconColor = Color(0xFF93A5C3),
                                        unselectedTextColor = Color(0xFF93A5C3),
                                        disabledIconColor = Color(0xFF61718D),
                                        disabledTextColor = Color(0xFF61718D)
                                    )
                                } else {
                                    NavigationBarItemDefaults.colors()
                                }
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
                    if (routeShellReady) {
                        if (currentRoute == Destinations.Overview.route) {
                            OverviewBaseTargetBanner(
                                baseTargetMmol = baseTargetBanner.baseTargetMmol,
                                minTargetMmol = baseTargetBanner.minTargetMmol,
                                maxTargetMmol = baseTargetBanner.maxTargetMmol,
                                staleData = appHealth.staleData,
                                killSwitchEnabled = appHealth.killSwitchEnabled,
                                lastSyncText = appHealth.lastSyncText,
                                onBaseTargetChange = viewModel::setBaseTarget,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            AppHealthBanner(
                                staleData = appHealth.staleData,
                                killSwitchEnabled = appHealth.killSwitchEnabled,
                                lastSyncText = appHealth.lastSyncText,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        NavHost(
                            navController = navController,
                            startDestination = Destinations.Overview.route,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            composable(Destinations.Overview.route) {
                                val overview by viewModel.overviewUiState.collectAsStateWithLifecycle()
                                OverviewScreen(
                                    state = overview,
                                    onRunCycleNow = viewModel::runCycleNow,
                                    onSetKillSwitch = viewModel::setKillSwitch,
                                    onOpenSensorLagAnalytics = {
                                        openSensorLagTrendDetailRequest = true
                                        navController.navigate(Destinations.Analytics.route) {
                                            launchSingleTop = true
                                        }
                                    }
                                )
                            }
                            composable(Destinations.Forecast.route) {
                                val forecast by viewModel.forecastUiState.collectAsStateWithLifecycle()
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
                            composable(Destinations.Safety.route) {
                                val safety by viewModel.safetyUiState.collectAsStateWithLifecycle()
                                SafetyScreen(
                                    state = safety,
                                    onKillSwitchToggle = viewModel::setKillSwitch,
                                    onSafetyBoundsChange = viewModel::setSafetyTargetBounds
                                )
                            }
                            composable(Destinations.Audit.route) {
                                val audit by viewModel.auditUiState.collectAsStateWithLifecycle()
                                val uam by viewModel.uamUiState.collectAsStateWithLifecycle()
                                AuditScreen(
                                    state = audit,
                                    uamState = uam,
                                    onSelectWindow = viewModel::setAuditWindow,
                                    onOnlyErrorsChange = viewModel::setAuditOnlyErrors,
                                    onMarkUamCorrect = viewModel::markUamEventCorrect,
                                    onMarkUamWrong = viewModel::markUamEventWrong,
                                    onMergeUamWithManual = viewModel::mergeUamEventWithManualCarbs,
                                    onExportUamToAaps = viewModel::exportUamEventToAaps
                                )
                            }
                            composable(Destinations.AiAnalysis.route) {
                                val aiAnalysis by viewModel.aiAnalysisUiState.collectAsStateWithLifecycle()
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
                                    onSendChatPrompt = viewModel::sendAiChatPrompt,
                                    onChatDraftChange = viewModel::updateAiChatDraft,
                                    onAttachImage = { viewModel.addAiChatAttachment(it, preferImage = true) },
                                    onAttachFile = { viewModel.addAiChatAttachment(it, preferImage = false) },
                                    onRemoveChatAttachment = viewModel::removeAiChatAttachment,
                                    onVoiceRepliesToggle = viewModel::setAiChatVoiceRepliesEnabled,
                                    onStartVoiceRecording = viewModel::startAiVoiceRecording,
                                    onStopVoiceRecording = viewModel::stopAiVoiceRecordingAndSend
                                )
                            }
                            composable(Destinations.Analytics.route) {
                                val analytics by viewModel.analyticsUiState.collectAsStateWithLifecycle()
                                AnalyticsScreen(
                                    state = analytics,
                                    onRunDailyAnalysis = viewModel::runDailyAnalysisNow,
                                    onInsulinProfileActivate = viewModel::setInsulinProfile,
                                    onSensorLagCorrectionModeChange = viewModel::setSensorLagCorrectionMode,
                                    openSensorLagTrendDetailRequest = openSensorLagTrendDetailRequest,
                                    onSensorLagTrendDetailHandled = {
                                        openSensorLagTrendDetailRequest = false
                                    }
                                )
                            }
                            composable(Destinations.Settings.route) {
                                val settings by viewModel.settingsUiState.collectAsStateWithLifecycle()
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
                                    onCircadianPatternsEnabledToggle = viewModel::setCircadianPatternsEnabled,
                                    onCircadianLookbackChange = viewModel::setCircadianLookback,
                                    onCircadianWeekendSplitToggle = viewModel::setCircadianWeekendSplit,
                                    onCircadianReplayResidualBiasToggle = viewModel::setCircadianReplayResidualBias,
                                    onCircadianForecastWeightsChange = viewModel::setCircadianForecastWeights,
                                    onSensorLagCorrectionModeChange = viewModel::setSensorLagCorrectionMode,
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
                    } else {
                        Spacer(modifier = Modifier.fillMaxSize())
                    }
                }
            }
    }

    AapsCopilotTheme(uiStyle = selectedUiStyle) {
        CopilotStyledBackground(
            uiStyle = selectedUiStyle,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                scaffoldContent()
                RootDrawerOverlay(
                    visible = drawerOpen,
                    currentRoute = currentRoute,
                    uiStyle = selectedUiStyle,
                    onDismiss = { drawerOpen = false },
                    onNavigate = { destination ->
                        drawerOpen = false
                        navController.navigate(destination.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }
    }
}
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TopBar(
    title: String,
    uiStyle: UiStyle,
    staleData: Boolean,
    killSwitchEnabled: Boolean,
    showStatusIndicators: Boolean,
    onMenuClick: () -> Unit,
    onOpenHealth: () -> Unit,
    onNavigateMore: (MoreDestination) -> Unit
) {
    var moreExpanded by remember { mutableStateOf(false) }
    val statusCount = if (showStatusIndicators) listOf(staleData, killSwitchEnabled).count { it } else 0
    val statusContainerColor = when {
        uiStyle == UiStyle.MIDNIGHT_GLASS -> shellChromeColor(uiStyle)
        showStatusIndicators && killSwitchEnabled -> MaterialTheme.colorScheme.errorContainer
        showStatusIndicators && staleData -> MaterialTheme.colorScheme.tertiaryContainer
        else -> shellChromeColor(uiStyle)
    }
    val contentColor = when {
        uiStyle == UiStyle.MIDNIGHT_GLASS -> shellChromeContentColor(uiStyle)
        showStatusIndicators && killSwitchEnabled -> MaterialTheme.colorScheme.onErrorContainer
        showStatusIndicators && staleData -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> shellChromeContentColor(uiStyle)
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = statusContainerColor,
            titleContentColor = contentColor,
            actionIconContentColor = contentColor,
            navigationIconContentColor = contentColor
        ),
        title = { Text(text = title) },
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = stringResource(id = R.string.menu_more)
                )
            }
        },
        actions = {
            if (showStatusIndicators) {
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
            }
            IconButton(onClick = { moreExpanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(id = R.string.menu_more)
                )
            }
            if (moreExpanded) {
                DropdownMenu(
                    expanded = true,
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
        }
    )
}

@Composable
private fun RootDrawerOverlay(
    visible: Boolean,
    currentRoute: String?,
    uiStyle: UiStyle,
    onDismiss: () -> Unit,
    onNavigate: (MoreDestination) -> Unit
) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(durationMillis = 140)),
                exit = fadeOut(animationSpec = tween(durationMillis = 120))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(10f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(drawerScrimColor(uiStyle))
                            .clickable(onClick = onDismiss)
                    )
            AnimatedVisibility(
                visible = visible,
                enter = slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(durationMillis = 240)
                ) + fadeIn(animationSpec = tween(durationMillis = 180)),
                exit = slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(durationMillis = 200)
                ) + fadeOut(animationSpec = tween(durationMillis = 120))
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(288.dp)
                        .align(Alignment.CenterStart),
                    color = drawerSurfaceColor(uiStyle),
                    contentColor = shellChromeContentColor(uiStyle),
                    tonalElevation = 6.dp,
                    shadowElevation = 16.dp
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = stringResource(id = R.string.menu_more),
                            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Destinations.more.forEach { destination ->
                            NavigationDrawerItem(
                                label = { Text(text = stringResource(id = destination.titleRes)) },
                                selected = currentRoute == destination.route,
                                onClick = { onNavigate(destination) },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                                colors = if (uiStyle == UiStyle.MIDNIGHT_GLASS) {
                                    NavigationDrawerItemDefaults.colors(
                                        selectedContainerColor = Color(0xFF10275A),
                                        selectedTextColor = Color(0xFFDCEBFF),
                                        selectedIconColor = Color(0xFF7FB3FF),
                                        unselectedContainerColor = Color.Transparent,
                                        unselectedTextColor = Color(0xFF93A5C3),
                                        unselectedIconColor = Color(0xFF93A5C3)
                                    )
                                } else {
                                    NavigationDrawerItemDefaults.colors()
                                }
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        NavigationDrawerItem(
                            label = { Text(text = stringResource(id = R.string.app_health_title)) },
                            selected = false,
                            onClick = onDismiss,
                            icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                            colors = if (uiStyle == UiStyle.MIDNIGHT_GLASS) {
                                NavigationDrawerItemDefaults.colors(
                                    unselectedContainerColor = Color.Transparent,
                                    unselectedTextColor = Color(0xFF93A5C3),
                                    unselectedIconColor = Color(0xFF93A5C3)
                                )
                            } else {
                                NavigationDrawerItemDefaults.colors()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun shellChromeColor(uiStyle: UiStyle): Color {
    return when (uiStyle) {
        UiStyle.MIDNIGHT_GLASS -> Color(0xFF10213F)
        UiStyle.DYNAMIC_GRADIENT -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
        UiStyle.CLASSIC -> MaterialTheme.colorScheme.surfaceVariant
    }
}

@Composable
private fun drawerSurfaceColor(uiStyle: UiStyle): Color {
    return when (uiStyle) {
        UiStyle.MIDNIGHT_GLASS -> Color(0xFF0B1831)
        UiStyle.DYNAMIC_GRADIENT -> MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        UiStyle.CLASSIC -> MaterialTheme.colorScheme.surface
    }
}

@Composable
private fun shellChromeContentColor(uiStyle: UiStyle): Color {
    return when (uiStyle) {
        UiStyle.MIDNIGHT_GLASS -> Color(0xFFF6FAFF)
        UiStyle.DYNAMIC_GRADIENT -> MaterialTheme.colorScheme.onSurfaceVariant
        UiStyle.CLASSIC -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun drawerScrimColor(uiStyle: UiStyle): Color {
    return when (uiStyle) {
        UiStyle.MIDNIGHT_GLASS -> Color.Black.copy(alpha = 0.56f)
        UiStyle.DYNAMIC_GRADIENT -> Color.Black.copy(alpha = 0.48f)
        UiStyle.CLASSIC -> Color.Black.copy(alpha = 0.38f)
    }
}

@Composable
private fun screenTitle(route: String?): String {
    return when (route) {
        Destinations.Forecast.route -> stringResource(id = R.string.nav_forecast)
        Destinations.Analytics.route -> stringResource(id = R.string.menu_analytics)
        Destinations.Safety.route -> stringResource(id = R.string.nav_safety)
        Destinations.Audit.route -> stringResource(id = R.string.menu_audit_log)
        Destinations.AiAnalysis.route -> stringResource(id = R.string.menu_ai_analysis)
        Destinations.Settings.route -> stringResource(id = R.string.menu_settings)
        else -> stringResource(id = R.string.nav_overview)
    }
}
