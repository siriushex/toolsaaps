package io.aaps.copilot.ui.foundation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.filterNotNull
import io.aaps.copilot.R
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.ui.MainViewModel
import io.aaps.copilot.ui.AutomaticEventAiAnalysisSettingsAction
import io.aaps.copilot.ui.foundation.components.AppHealthBanner
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.screens.AnalyticsScreen
import io.aaps.copilot.ui.foundation.screens.AlertsScreen
import io.aaps.copilot.ui.foundation.screens.AiAnalysisScreen
import io.aaps.copilot.ui.foundation.screens.AuditScreen
import io.aaps.copilot.ui.foundation.screens.ForecastScreen
import io.aaps.copilot.ui.foundation.screens.OverviewScreen
import io.aaps.copilot.ui.foundation.screens.OverviewAuthoritativeEditor
import io.aaps.copilot.ui.foundation.screens.EventsDialog
import io.aaps.copilot.ui.foundation.screens.remainingGlucoseAlertMuteMinutes
import io.aaps.copilot.ui.foundation.screens.SafetyScreen
import io.aaps.copilot.ui.foundation.screens.SettingsScreen
import io.aaps.copilot.ui.foundation.screens.UamScreen
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import io.aaps.copilot.ui.foundation.theme.CopilotStyledBackground

private sealed class RootDestination(val route: String)
private sealed class BottomDestination(route: String, val titleRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) : RootDestination(route)
private sealed class MoreDestination(route: String, val titleRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) : RootDestination(route)

private object Destinations {
    object Overview : BottomDestination("overview", R.string.nav_overview, Icons.Default.Home)
    object Forecast : BottomDestination("forecast", R.string.nav_forecast, Icons.Default.ShowChart)
    object Analytics : BottomDestination("analytics", R.string.menu_analytics, Icons.Default.Assessment)

    object Uam : MoreDestination("uam", R.string.nav_uam, Icons.Default.Warning)
    object Safety : MoreDestination("safety", R.string.nav_safety, Icons.Default.Security)
    object Audit : MoreDestination("audit", R.string.menu_audit_log, Icons.Default.TableChart)
    object AiAnalysis : MoreDestination("ai_analysis", R.string.menu_ai_analysis, Icons.Default.Assessment)
    object Alerts : MoreDestination("alerts", R.string.nav_alerts, Icons.Default.Notifications)
    object Settings : MoreDestination("settings", R.string.menu_settings, Icons.Default.Tune)

    val bottom = listOf(Overview, Forecast, Analytics)
    val more = listOf(Uam, Safety, Audit, AiAnalysis, Alerts, Settings)
}

internal data class ClinicalAiSettingsRootCallbacks(
    val onAutomaticCauseAnalysisToggle: (Boolean) -> Unit
)

internal fun clinicalAiSettingsRootCallbacks(
    actions: AutomaticEventAiAnalysisSettingsAction
): ClinicalAiSettingsRootCallbacks = ClinicalAiSettingsRootCallbacks(
    onAutomaticCauseAnalysisToggle = actions::setAutomaticEventAiAnalysisEnabled
)

@Composable
fun CopilotFoundationRoot(
    viewModel: MainViewModel = viewModel()
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val message by viewModel.messageUiState.collectAsStateWithLifecycle()
    val appHealth by viewModel.appHealthUiState.collectAsStateWithLifecycle()
    val overview by viewModel.overviewUiState.collectAsStateWithLifecycle()
    val uiStyleRaw by viewModel.uiStyleState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val selectedUiStyle = UiStyle.fromRaw(uiStyleRaw)
    var drawerOpen by remember { mutableStateOf(false) }
    var chromeDetailsReady by remember { mutableStateOf(false) }
    var routeShellReady by remember { mutableStateOf(false) }
    var openSensorLagTrendDetailRequest by remember { mutableStateOf(false) }
    var showPowerSaveDialog by remember { mutableStateOf(false) }
    var showEventsDialog by remember { mutableStateOf(false) }
    var overviewAuthoritativeEditor by remember { mutableStateOf<OverviewAuthoritativeEditor?>(null) }
    var openPlannedActivityRequest by remember { mutableStateOf(0) }
    val clinicalAiSettingsCallbacks = remember(viewModel) {
        clinicalAiSettingsRootCallbacks(viewModel)
    }

    if (showEventsDialog) {
        EventsDialog(
            events = overview.events,
            nowTs = overview.eventTimelineNowTs,
            sex = overview.physiologicalSex,
            showOnGraph = overview.chart.showEvents,
            onShowOnGraphChange = viewModel::setShowEventsOnGraph,
            onDismiss = { showEventsDialog = false },
            onSave = viewModel::saveManualEvent,
            onClose = viewModel::closeManualEvent,
            onDelete = viewModel::deleteManualEvent,
            onOpenMealAction = {
                showEventsDialog = false
                overviewAuthoritativeEditor = OverviewAuthoritativeEditor.MEAL
                navController.navigate(Destinations.Overview.route) { launchSingleTop = true }
            },
            onOpenPlannedActivity = {
                showEventsDialog = false
                openPlannedActivityRequest += 1
                navController.navigate(Destinations.Settings.route) { launchSingleTop = true }
            },
            onOpenBloodCheck = {
                showEventsDialog = false
                overviewAuthoritativeEditor = OverviewAuthoritativeEditor.BLOOD_CHECK
                navController.navigate(Destinations.Overview.route) { launchSingleTop = true }
            },
            onOpenReadOnlyDiagnostic = {
                showEventsDialog = false
                navController.navigate(Destinations.Safety.route) { launchSingleTop = true }
            }
        )
    }

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

    LaunchedEffect(navController, viewModel) {
        viewModel.alertNavigationRequests.filterNotNull().collect { request ->
            try {
                viewModel.refreshAlertsWindow()
                viewModel.selectAlertEpisode(request.episodeId)
                navController.navigate(Destinations.Alerts.route) { launchSingleTop = true }
            } finally {
                viewModel.acknowledgeAlertNavigation(request.token)
            }
        }
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
                        showGlucoseAlertToggle = currentRoute == Destinations.Overview.route,
                        glucoseAlertMutedUntilTs = overview.glucoseAlertMutedUntilTs,
                        showPowerSaveAction = currentRoute == Destinations.Overview.route,
                        onMenuClick = { drawerOpen = !drawerOpen },
                        onMuteGlucoseAlerts30m = viewModel::muteGlucoseAlerts30m,
                        onMuteGlucoseAlerts60m = viewModel::muteGlucoseAlerts60m,
                        onResumeGlucoseAlerts = viewModel::resumeGlucoseAlerts,
                        onOpenAlertHistory = {
                            viewModel.refreshAlertsWindow()
                            viewModel.selectAlertEpisode(null)
                            navController.navigate(Destinations.Alerts.route) { launchSingleTop = true }
                        },
                        onOpenEvents = { showEventsDialog = true },
                        activeEventCount = overview.events.count { it.isActiveAt(overview.eventTimelineNowTs) },
                        onPowerSaveClick = { showPowerSaveDialog = true },
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
                        val showHealthWarning = appHealth.staleData || appHealth.killSwitchEnabled
                        if (currentRoute != Destinations.Overview.route && showHealthWarning) {
                            AppHealthBanner(
                                staleData = appHealth.staleData,
                                killSwitchEnabled = appHealth.killSwitchEnabled,
                                lastSyncText = appHealth.lastSyncText,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(Spacing.sm))
                        }
                        NavHost(
                            navController = navController,
                            startDestination = Destinations.Overview.route,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            composable(Destinations.Overview.route) {
                                OverviewScreen(
                                    state = overview,
                                    onRunCycleNow = viewModel::runCycleNow,
                                    onSetKillSwitch = viewModel::setKillSwitch,
                                    onDisablePowerSave = viewModel::disablePowerSave,
                                    onAddBloodCheck = viewModel::addManualBloodGlucoseCheck,
                                    onResetBloodCalibration = viewModel::resetManualGlucoseCalibration,
                                    onBaseTargetScheduleSave = viewModel::saveBaseTargetSchedule,
                                    onManualCarbs = { carbs, reason, profile, manualMealEnergyKcal, eatingSoon, submissionId ->
                                        viewModel.sendManualCarbs(
                                            carbs,
                                            reason,
                                            profile,
                                            manualMealEnergyKcal,
                                            eatingSoon, submissionId
                                        )
                                    },
                                    onOpenAapsBolus = viewModel::openAapsBolusDialog,
                                    onUamExportUiModeChange = viewModel::setUamExportUiMode,
                                    onIsfRuntimeSourceChange = viewModel::setIsfRuntimeSourcePreference,
                                    onCrRuntimeSourceChange = viewModel::setCrRuntimeSourcePreference,
                                    onOpenClinicalReport = {
                                        navController.navigate(Destinations.AiAnalysis.route) {
                                            launchSingleTop = true
                                        }
                                    },
                                    onOpenSensorLagAnalytics = {
                                        openSensorLagTrendDetailRequest = true
                                        navController.navigate(Destinations.Analytics.route) {
                                            launchSingleTop = true
                                        }
                                    },
                                    onOpenIsfCrAnalytics = {
                                        navController.navigate(Destinations.Analytics.route) {
                                            launchSingleTop = true
                                        }
                                    },
                                    authoritativeEditorRequest = overviewAuthoritativeEditor,
                                    onAuthoritativeEditorRequestHandled = {
                                        overviewAuthoritativeEditor = null
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
                            composable(Destinations.Uam.route) {
                                val uam by viewModel.uamUiState.collectAsStateWithLifecycle()
                                UamScreen(
                                    state = uam,
                                    onMarkCorrect = viewModel::markUamEventCorrect,
                                    onMarkWrong = viewModel::markUamEventWrong,
                                    onMergeWithManual = viewModel::mergeUamEventWithManualCarbs
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
                                    onMergeUamWithManual = viewModel::mergeUamEventWithManualCarbs
                                )
                            }
                            composable(Destinations.AiAnalysis.route) {
                                val aiAnalysis by viewModel.aiAnalysisUiState.collectAsStateWithLifecycle()
                                AiAnalysisScreen(
                                    state = aiAnalysis,
                                    onPrepareClinicalReport = viewModel::prepareClinicalReport,
                                    onRetryLocalClinicalReportPreparation =
                                        viewModel::retryLocalClinicalReportPreparation,
                                    onSendClinicalReport = viewModel::sendClinicalReport,
                                    onCancelClinicalReport = viewModel::cancelClinicalReport,
                                    onRequestUnknownClinicalReportRetry =
                                        viewModel::requestUnknownClinicalReportRetryConfirmation,
                                    onDismissUnknownClinicalReportRetry =
                                        viewModel::dismissUnknownClinicalReportRetryConfirmation,
                                    onConfirmUnknownClinicalReportRetry =
                                        viewModel::retryUnknownClinicalReport,
                                    onOpenClinicalReportSettings = {
                                        navController.navigate(Destinations.Settings.route) {
                                            launchSingleTop = true
                                        }
                                    },
                                    onBeginClinicalReportPdfExport =
                                        viewModel::beginClinicalReportPdfExport,
                                    onReprepareClinicalReportPdf =
                                        viewModel::reprepareClinicalReportPdf,
                                    onClaimClinicalReportPdfExportTicket =
                                        viewModel::claimClinicalReportPdfExportTicket,
                                    onResolveClinicalReportPdfExport =
                                        viewModel::resolveClinicalReportPdfExport,
                                    onShareClinicalReportPdf =
                                        viewModel::shareClinicalReportPdf,
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
                            composable(Destinations.Alerts.route) {
                                val alerts by viewModel.alertsUiState.collectAsStateWithLifecycle()
                                LaunchedEffect(Unit) { viewModel.refreshAlertsWindow() }
                                AlertsScreen(
                                    state = alerts,
                                    onSelectEpisode = viewModel::selectAlertEpisode
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
                                val serverAiConnection by
                                    viewModel.serverAiConnectionState.collectAsStateWithLifecycle()
                                LaunchedEffect(Unit) {
                                    viewModel.loadServerAiConnection()
                                }
                                SettingsScreen(
                                    telegramSettingsContent = {
                                        io.aaps.copilot.ui.foundation.screens.TelegramSettingsSection(
                                            viewModel.telegramRepository, viewModel::sendTelegramSummary
                                        )
                                    },
                                    state = settings,
                                    serverAiConnectionState = serverAiConnection,
                                    onServerAiActivate = viewModel::activateServerAiConnection,
                                    onServerAiResume = viewModel::resumeServerAiConnection,
                                    onServerAiCheck = viewModel::checkServerAiConnection,
                                    targetManagerStatus = overview.targetManagerLiveStatus,
                                    onTargetManagerTimingChange = viewModel::setTargetManagerTiming,
                                    onVerboseLogsToggle = viewModel::setVerboseLogsEnabled,
                                    onProModeToggle = viewModel::setProModeEnabled,
                                    onNightscoutUrlSave = viewModel::setNightscoutUrl,
                                    onAiApiSettingsSave = viewModel::setAiApiSettings,
                                    onClinicalAiProviderChange =
                                        viewModel::setClinicalAiProvider,
                                    onClinicalAiModelChange =
                                        viewModel::setClinicalAiModel,
                                    onClinicalAiCustomModelChange =
                                        viewModel::setClinicalAiCustomModel,
                                    onClinicalAiEndpointChange =
                                        viewModel::setClinicalAiEndpoint,
                                    onClinicalAiProtocolChange =
                                        viewModel::setClinicalAiProtocol,
                                    onAutomaticCauseAnalysisToggle =
                                        clinicalAiSettingsCallbacks.onAutomaticCauseAnalysisToggle,
                                    onClinicalAiCredentialReplace =
                                        viewModel::replaceClinicalAiCredential,
                                    onClinicalAiCredentialDelete =
                                        viewModel::deleteClinicalAiCredential,
                                    onClinicalAiTestConnection =
                                        viewModel::testClinicalAiConnection,
                                    onUiStyleChange = viewModel::setUiStyle,
                                    onBaseTargetScheduleSave = viewModel::saveBaseTargetSchedule,
                                    onInsulinProfileSelect = viewModel::setInsulinProfile,
                                    onLocalNightscoutToggle = viewModel::setLocalNightscoutEnabled,
                                    onLocalNightscoutLegacyMigrationAcknowledged =
                                        viewModel::setLocalNightscoutLegacyMigrationAcknowledged,
                                    onLocalNightscoutSecretCopy =
                                        viewModel::copyLocalNightscoutApiSecret,
                                    onLocalNightscoutCertificateInstall =
                                        viewModel::installLocalNightscoutCertificate,
                                    onLocalNightscoutCertificateExport =
                                        viewModel::exportLocalNightscoutCertificate,
                                    onLocalNightscoutIdentityReset =
                                        viewModel::resetLocalNightscoutIdentity,
                                    onLocalBroadcastIngestToggle = viewModel::setLocalBroadcastIngestEnabled,
                                    onStrictSenderValidationToggle = viewModel::setStrictBroadcastValidation,
                                    onUamExportUiModeChange = viewModel::setUamExportUiMode,
                                    onUamSnackConfigChange = viewModel::setUamSnackConfig,
                                    onUamInferenceToggle = viewModel::setUamInferenceEnabled,
                                    onUamBoostToggle = viewModel::setUamBoostEnabled,
                                    onUamAutoExportCapChange = viewModel::setUamAutoExportCap,
                                    onCircadianPatternsEnabledToggle = viewModel::setCircadianPatternsEnabled,
                                    onCircadianLookbackChange = viewModel::setCircadianLookback,
                                    onCircadianWeekendSplitToggle = viewModel::setCircadianWeekendSplit,
                                    onCircadianReplayResidualBiasToggle = viewModel::setCircadianReplayResidualBias,
                                    onCircadianForecastWeightsChange = viewModel::setCircadianForecastWeights,
                                    onSoftAlertEnabledToggle = { viewModel.setGlucoseAlertSettings(softEnabled = it) },
                                    onWatch60AlertEnabledToggle = { viewModel.setGlucoseAlertSettings(watch60Enabled = it) },
                                    onWarning30AlertEnabledToggle = { viewModel.setGlucoseAlertSettings(warning30Enabled = it) },
                                    onSoftHighAlertEnabledToggle = { viewModel.setGlucoseAlertSettings(softHighEnabled = it) },
                                    onCritical5AlertEnabledToggle = { viewModel.setGlucoseAlertSettings(critical5Enabled = it) },
                                    onLowNowAlertEnabledToggle = { viewModel.setGlucoseAlertSettings(lowNowEnabled = it) },
                                    onSoftAlertLowChange = { viewModel.setGlucoseAlertSettings(softLowMmol = it) },
                                    onSoftAlertHighChange = { viewModel.setGlucoseAlertSettings(softHighMmol = it) },
                                    onUrgentLowChange = { viewModel.setGlucoseAlertSettings(urgentLowMmol = it) },
                                    onSoftAlertAudioSettingsChange = viewModel::setSoftGlucoseAlertAudio,
                                    onCriticalAlertAudio1SettingsChange = viewModel::setCriticalGlucoseAlertAudio1,
                                    onCriticalAlertAudio2SettingsChange = viewModel::setCriticalGlucoseAlertAudio2,
                                    onReplaceGlucoseAlertAudio = viewModel::replaceGlucoseAlertAudioSource,
                                    onPreviewGlucoseAlertAudio = viewModel::previewGlucoseAlertAudio,
                                    onStopPreviewGlucoseAlertAudio = viewModel::stopPreviewGlucoseAlertAudio,
                                    onResetGlucoseAlertAudio = viewModel::resetRecommendedGlucoseAlertAudio,
                                    onSensorLagCorrectionModeChange = viewModel::setSensorLagCorrectionMode,
                                    onTargetManagerModeChange = viewModel::setTargetManagerMode,
                                    onTargetManagerAutomaticMode = viewModel::setTargetManagerAutomaticMode,
                                    onTargetManagerCopilotPriorityChange =
                                        viewModel::setTargetManagerCopilotPriorityEnabled,
                                    onIsfRuntimeSourceChange = viewModel::setIsfRuntimeSourcePreference,
                                    onCrRuntimeSourceChange = viewModel::setCrRuntimeSourcePreference,
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
                                    onRetentionDaysChange = viewModel::setRetentionDays,
                                    onEnergyProfileEnabledChange = viewModel::setEnergyProfileEnabled,
                                    onEnergyProfileUserProfileSave = viewModel::saveEnergyProfileUserProfile,
                                    onEnergyProfileFoodSettingsSave = viewModel::saveEnergyProfileFoodSettings,
                                    onEnergyProfileActivitySettingsSave = viewModel::saveEnergyProfileActivitySettings,
                                    onPlannedActivitySave = viewModel::savePlannedActivity,
                                    onPlannedActivityDelete = viewModel::deletePlannedActivity,
                                    onEnergyGoalSettingsSave = viewModel::saveEnergyGoalSettings,
                                    openPlannedActivityRequest = openPlannedActivityRequest
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
                if (showPowerSaveDialog) {
                    RootPowerSaveDialog(
                        onDismiss = { showPowerSaveDialog = false },
                        onEnablePowerSave = { durationMs ->
                            showPowerSaveDialog = false
                            viewModel.enablePowerSave(durationMs)
                        }
                    )
                }
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
    showGlucoseAlertToggle: Boolean,
    glucoseAlertMutedUntilTs: Long,
    showPowerSaveAction: Boolean,
    onMenuClick: () -> Unit,
    onMuteGlucoseAlerts30m: () -> Unit,
    onMuteGlucoseAlerts60m: () -> Unit,
    onResumeGlucoseAlerts: () -> Unit,
    onOpenAlertHistory: () -> Unit,
    onOpenEvents: () -> Unit,
    activeEventCount: Int,
    onPowerSaveClick: () -> Unit,
    onOpenHealth: () -> Unit,
    onNavigateMore: (MoreDestination) -> Unit
) {
    var moreExpanded by remember { mutableStateOf(false) }
    val statusCount = if (showStatusIndicators) listOf(staleData, killSwitchEnabled).count { it } else 0
    val alertsMuted = io.aaps.copilot.ui.foundation.screens.isAuthoritativelyMuted(
        glucoseAlertMutedUntilTs
    )
    val remainingMuteMinutes = if (alertsMuted) {
        remainingGlucoseAlertMuteMinutes(
            mutedUntilTs = glucoseAlertMutedUntilTs,
            nowTs = System.currentTimeMillis()
        )
    } else {
        0
    }
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
            if (showGlucoseAlertToggle) {
                EventAlertActions(
                    activeEventCount = activeEventCount,
                    alertsMuted = alertsMuted,
                    remainingMuteMinutes = remainingMuteMinutes,
                    onOpenEvents = onOpenEvents,
                    onMute30m = onMuteGlucoseAlerts30m,
                    onMute60m = onMuteGlucoseAlerts60m,
                    onResume = onResumeGlucoseAlerts,
                    onOpenHistory = onOpenAlertHistory
                )
            } else if (showStatusIndicators) {
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
                    if (showPowerSaveAction) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(id = R.string.overview_power_save_title)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.PowerSettingsNew,
                                    contentDescription = stringResource(id = R.string.overview_power_save_title)
                                )
                            },
                            onClick = {
                                moreExpanded = false
                                onPowerSaveClick()
                            }
                        )
                    }
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

internal fun activeEventBadgeText(activeEventCount: Int): String? = when {
    activeEventCount <= 0 -> null
    activeEventCount <= MAX_VISIBLE_EVENT_BADGE_COUNT -> activeEventCount.toString()
    else -> "$MAX_VISIBLE_EVENT_BADGE_COUNT+"
}

@Composable
internal fun EventAlertActions(
    activeEventCount: Int,
    alertsMuted: Boolean,
    remainingMuteMinutes: Int,
    onOpenEvents: () -> Unit,
    onMute30m: () -> Unit,
    onMute60m: () -> Unit,
    onResume: () -> Unit,
    onOpenHistory: () -> Unit
) {
    var alertMenuExpanded by remember { mutableStateOf(false) }
    val badgeText = activeEventBadgeText(activeEventCount)
    BadgedBox(
        badge = {
            badgeText?.let { text ->
                val description = stringResource(R.string.events_active_badge_description, text)
                Badge(
                    modifier = Modifier
                        .testTag("eventsActiveBadge")
                        .semantics { contentDescription = description }
                ) {
                    Text(text)
                }
            }
        }
    ) {
        IconButton(onClick = onOpenEvents, modifier = Modifier.testTag("eventsNotebookAction")) {
            Icon(
                imageVector = Icons.Default.MenuBook,
                contentDescription = stringResource(R.string.events_notebook_description)
            )
        }
    }
    Box {
        IconButton(
            onClick = {
                when (alertBellPressAction(alertsMuted)) {
                    AlertBellPressAction.OPEN_MENU -> alertMenuExpanded = true
                    AlertBellPressAction.RESUME -> onResume()
                }
            },
            modifier = Modifier.testTag("glucoseAlertBellAction")
        ) {
            Icon(
                imageVector = if (alertsMuted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                contentDescription = if (alertsMuted) {
                    stringResource(R.string.glucose_alert_action_resume_with_remaining, remainingMuteMinutes)
                } else {
                    stringResource(R.string.alerts_options)
                }
            )
        }
        DropdownMenu(
            expanded = alertMenuExpanded,
            onDismissRequest = { alertMenuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.glucose_alert_action_mute_30m)) },
                onClick = { alertMenuExpanded = false; onMute30m() },
                modifier = Modifier.testTag("glucoseAlertMute30Action")
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.glucose_alert_action_mute_60m)) },
                onClick = { alertMenuExpanded = false; onMute60m() },
                modifier = Modifier.testTag("glucoseAlertMute60Action")
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.alerts_open_history)) },
                onClick = { alertMenuExpanded = false; onOpenHistory() },
                modifier = Modifier.testTag("glucoseAlertHistoryAction")
            )
        }
    }
}

private const val MAX_VISIBLE_EVENT_BADGE_COUNT = 99

internal enum class AlertBellPressAction { OPEN_MENU, RESUME }

internal fun alertBellPressAction(alertsMuted: Boolean): AlertBellPressAction =
    if (alertsMuted) AlertBellPressAction.RESUME else AlertBellPressAction.OPEN_MENU

internal fun overviewClinicalActionOrder(): List<String> = listOf("events", "alerts")

@Composable
private fun RootPowerSaveDialog(
    onDismiss: () -> Unit,
    onEnablePowerSave: (Long?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.overview_power_save_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                PowerSaveDialogOption(
                    text = stringResource(id = R.string.overview_power_save_1h),
                    onClick = { onEnablePowerSave(60L * 60_000L) }
                )
                PowerSaveDialogOption(
                    text = stringResource(id = R.string.overview_power_save_2h),
                    onClick = { onEnablePowerSave(2L * 60L * 60_000L) }
                )
                PowerSaveDialogOption(
                    text = stringResource(id = R.string.overview_power_save_4h),
                    onClick = { onEnablePowerSave(4L * 60L * 60_000L) }
                )
                PowerSaveDialogOption(
                    text = stringResource(id = R.string.overview_power_save_full),
                    onClick = { onEnablePowerSave(null) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun PowerSaveDialogOption(
    text: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = text, modifier = Modifier.fillMaxWidth())
    }
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
                            text = stringResource(id = R.string.app_name),
                            modifier = Modifier.padding(start = 16.dp, top = 20.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(id = R.string.app_subtitle_predictive_analytics),
                            modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiStyle == UiStyle.MIDNIGHT_GLASS) Color(0xFF8DB6FF) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (uiStyle == UiStyle.MIDNIGHT_GLASS) Color(0x1400E676) else MaterialTheme.colorScheme.secondaryContainer,
                            border = BorderStroke(1.dp, if (uiStyle == UiStyle.MIDNIGHT_GLASS) Color(0x3300E676) else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (uiStyle == UiStyle.MIDNIGHT_GLASS) Color(0xFF34D399) else MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(999.dp)
                                        )
                                        .width(8.dp)
                                        .height(8.dp)
                                )
                                Text(
                                    text = stringResource(id = R.string.status_connected_loop_active),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        Text(
                            text = stringResource(id = R.string.menu_more),
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (uiStyle == UiStyle.MIDNIGHT_GLASS) Color(0xFF93A5C3) else MaterialTheme.colorScheme.onSurfaceVariant
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
        Destinations.Uam.route -> stringResource(id = R.string.nav_uam)
        Destinations.Analytics.route -> stringResource(id = R.string.menu_analytics)
        Destinations.Safety.route -> stringResource(id = R.string.nav_safety)
        Destinations.Audit.route -> stringResource(id = R.string.menu_audit_log)
        Destinations.AiAnalysis.route -> stringResource(id = R.string.menu_ai_analysis)
        Destinations.Alerts.route -> stringResource(id = R.string.nav_alerts)
        Destinations.Settings.route -> stringResource(id = R.string.menu_settings)
        else -> stringResource(id = R.string.nav_overview)
    }
}
