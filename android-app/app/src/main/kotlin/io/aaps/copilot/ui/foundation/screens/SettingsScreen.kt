package io.aaps.copilot.ui.foundation.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.aaps.copilot.BuildConfig
import io.aaps.copilot.R
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.config.OpenAiCompatibleProtocol
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.config.TargetManagerTimingSetting
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.domain.target.BaseTargetSchedule
import io.aaps.copilot.security.ServerAiConnectionState
import io.aaps.copilot.ui.foundation.components.BaseTargetScheduleDialog
import io.aaps.copilot.ui.foundation.components.buildAutoStatusText
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.format.UiFormatters
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import io.aaps.copilot.ui.foundation.theme.LocalUiStyle
import kotlin.math.roundToInt

private val SettingsSectionShape = RoundedCornerShape(18.dp)
private val SettingsInfoShape = RoundedCornerShape(12.dp)
internal val CredentialKeyboardOptions = KeyboardOptions(
    keyboardType = KeyboardType.Password,
    autoCorrectEnabled = false
)

private enum class SettingsTab {
    BASIC,
    ADVANCED
}

internal fun targetManagerModeChangeNeedsConfirmation(
    currentMode: String,
    candidateMode: String
): Boolean = candidateMode.equals("ACTIVE", ignoreCase = true) &&
    !currentMode.equals("ACTIVE", ignoreCase = true)

internal fun targetManagerPriorityChangeNeedsConfirmation(
    currentEnabled: Boolean,
    candidateEnabled: Boolean
): Boolean = candidateEnabled && !currentEnabled

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onVerboseLogsToggle: (Boolean) -> Unit,
    onProModeToggle: (Boolean) -> Unit,
    serverAiConnectionState: ServerAiConnectionState = ServerAiConnectionState(),
    onServerAiActivate: (String) -> Unit = {},
    onServerAiResume: () -> Unit = {},
    onServerAiCheck: () -> Unit = {},
    targetManagerStatus: TargetManagerLiveStatusUi = TargetManagerLiveStatusUi(),
    onTargetManagerTimingChange: (TargetManagerTimingSetting, Int) -> Unit = { _, _ -> },
    onNightscoutUrlSave: (String) -> Unit = {},
    onAiApiSettingsSave: (String) -> Unit = {},
    onAiCredentialReplace: (String) -> Unit = {},
    onAiCredentialDelete: () -> Unit = {},
    onClinicalAiProviderChange: (ClinicalAiProviderId) -> Unit = {},
    onClinicalAiModelChange: (String) -> Unit = {},
    onClinicalAiCustomModelChange: (String) -> Unit = {},
    onClinicalAiEndpointChange: (String) -> Unit = {},
    onClinicalAiProtocolChange: (OpenAiCompatibleProtocol) -> Unit = {},
    onAutomaticCauseAnalysisToggle: (Boolean) -> Unit = {},
    onClinicalAiCredentialReplace: (ClinicalAiProviderId, String) -> Unit = { _, _ -> },
    onClinicalAiCredentialDelete: (ClinicalAiProviderId) -> Unit = {},
    onClinicalAiTestConnection: () -> Unit = {},
    onUiStyleChange: (String) -> Unit = {},
    onBaseTargetScheduleSave: (BaseTargetSchedule) -> Unit = {},
    onInsulinProfileSelect: (String) -> Unit = {},
    onLocalNightscoutToggle: (Boolean) -> Unit = {},
    onLocalNightscoutLegacyMigrationAcknowledged: (Boolean) -> Unit = {},
    onLocalNightscoutSecretCopy: () -> Unit = {},
    onLocalNightscoutCertificateInstall: () -> Unit = {},
    onLocalNightscoutCertificateExport: () -> Unit = {},
    onLocalNightscoutIdentityReset: (Boolean) -> Unit = {},
    onLocalBroadcastIngestToggle: (Boolean) -> Unit = {},
    onStrictSenderValidationToggle: (Boolean) -> Unit = {},
    onUamInferenceToggle: (Boolean) -> Unit = {},
    onUamBoostToggle: (Boolean) -> Unit = {},
    onUamExportUiModeChange: (String) -> Unit = {},
    onUamSnackConfigChange: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onUamAutoExportCapChange: (Boolean, Int) -> Unit = { _, _ -> },
    onCircadianPatternsEnabledToggle: (Boolean) -> Unit = {},
    onCircadianLookbackChange: (Int, Int) -> Unit = { _, _ -> },
    onCircadianWeekendSplitToggle: (Boolean) -> Unit = {},
    onCircadianReplayResidualBiasToggle: (Boolean) -> Unit = {},
    onCircadianForecastWeightsChange: (Double, Double) -> Unit = { _, _ -> },
    onSoftAlertEnabledToggle: (Boolean) -> Unit = {},
    onWatch60AlertEnabledToggle: (Boolean) -> Unit = {},
    onWarning30AlertEnabledToggle: (Boolean) -> Unit = {},
    onSoftHighAlertEnabledToggle: (Boolean) -> Unit = {},
    onCritical5AlertEnabledToggle: (Boolean) -> Unit = {},
    onLowNowAlertEnabledToggle: (Boolean) -> Unit = {},
    onSoftAlertLowChange: (Double) -> Unit = {},
    onSoftAlertHighChange: (Double) -> Unit = {},
    onUrgentLowChange: (Double) -> Unit = {},
    onSoftAlertAudioSettingsChange: (Int, Int) -> Unit = { _, _ -> },
    onCriticalAlertAudio1SettingsChange: (Int, Int) -> Unit = { _, _ -> },
    onCriticalAlertAudio2SettingsChange: (Int, Int) -> Unit = { _, _ -> },
    onReplaceGlucoseAlertAudio: (String, String) -> Unit = { _, _ -> },
    onPreviewGlucoseAlertAudio: (String) -> Unit = {},
    onStopPreviewGlucoseAlertAudio: () -> Unit = {},
    onResetGlucoseAlertAudio: () -> Unit = {},
    onSensorLagCorrectionModeChange: (String) -> Unit = {},
    onTargetManagerModeChange: (String) -> Unit = {},
    onTargetManagerAutomaticMode: () -> Unit = {},
    onTargetManagerCopilotPriorityChange: (Boolean) -> Unit = {},
    onIsfRuntimeSourceChange: (String) -> Unit = {},
    onCrRuntimeSourceChange: (String) -> Unit = {},
    onIsfCrShadowModeToggle: (Boolean) -> Unit = {},
    onIsfCrConfidenceThresholdChange: (Double) -> Unit = {},
    onIsfCrUseActivityToggle: (Boolean) -> Unit = {},
    onIsfCrUseManualTagsToggle: (Boolean) -> Unit = {},
    onIsfCrMinEvidencePerHourChange: (Int, Int) -> Unit = { _, _ -> },
    onIsfCrCrIntegrityGateSettingsChange: (Int, Double, Double) -> Unit = { _, _, _ -> },
    onIsfCrRetentionChange: (Int, Int) -> Unit = { _, _ -> },
    onIsfCrAutoActivationEnabledToggle: (Boolean) -> Unit = {},
    onIsfCrAutoActivationLookbackHoursChange: (Int) -> Unit = {},
    onIsfCrAutoActivationMinSamplesChange: (Int) -> Unit = {},
    onIsfCrAutoActivationMinMeanConfidenceChange: (Double) -> Unit = {},
    onIsfCrAutoActivationMaxMeanAbsDeltaPctChange: (Double, Double) -> Unit = { _, _ -> },
    onIsfCrAutoActivationSensorThresholdsChange: (Double, Double, Double, Double, Double) -> Unit = { _, _, _, _, _ -> },
    onIsfCrAutoActivationDayTypeThresholdsChange: (Double, Double) -> Unit = { _, _ -> },
    onIsfCrAutoActivationRequireDailyQualityGateToggle: (Boolean) -> Unit = {},
    onIsfCrAutoActivationDailyRiskBlockLevelChange: (Int) -> Unit = {},
    onIsfCrAutoActivationDailyQualityThresholdsChange: (Int, Double, Double, Double, Double, Double, Double, Double) -> Unit =
        { _, _, _, _, _, _, _, _ -> },
    onIsfCrAutoActivationRollingGateSettingsChange: (Int, Double, Double, Double) -> Unit =
        { _, _, _, _ -> },
    onAddPhysioTag: (String, Double, Int) -> Unit = { _, _, _ -> },
    onClosePhysioTag: (String, Long) -> Unit = { _, _ -> },
    onClearPhysioTags: () -> Unit = {},
    onAdaptiveControllerToggle: (Boolean) -> Unit = {},
    onSafetyBoundsChange: (Double, Double) -> Unit = { _, _ -> },
    onPostHypoThresholdChange: (Double) -> Unit = {},
    onPostHypoTargetChange: (Double) -> Unit = {},
    onRetentionDaysChange: (Int) -> Unit = {},
    onEnergyProfileEnabledChange: (Boolean) -> Unit = {},
    onEnergyProfileUserProfileSave: (UserProfileDraftUi) -> Unit = {},
    onEnergyProfileFoodSettingsSave: (FoodProfileSettingsUi) -> Unit = {},
    onEnergyProfileActivitySettingsSave: (ActivityProfileSettingsUi) -> Unit = {},
    onPlannedActivitySave: (PlannedActivityEventUi) -> Unit = {},
    onPlannedActivityDelete: (String) -> Unit = {},
    onEnergyGoalSettingsSave: (EnergyGoalSettingsUi) -> Unit = {},
    openPlannedActivityRequest: Int = 0,
    telegramSettingsContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var selectedTabRaw by rememberSaveable { mutableStateOf(SettingsTab.BASIC.name) }
    var showBaseTargetDialog by rememberSaveable { mutableStateOf(false) }
    var pendingTargetManagerMode by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingTargetManagerPriorityEnable by rememberSaveable { mutableStateOf(false) }
    var pendingLocalNightscoutIdentityReset by rememberSaveable { mutableStateOf(false) }
    val selectedTab = runCatching { SettingsTab.valueOf(selectedTabRaw) }
        .getOrDefault(SettingsTab.BASIC)
    LaunchedEffect(openPlannedActivityRequest) {
        if (openPlannedActivityRequest > 0) {
            selectedTabRaw = SettingsTab.ADVANCED.name
        }
    }
    pendingTargetManagerMode?.let { pendingMode ->
        AlertDialog(
            onDismissRequest = { pendingTargetManagerMode = null },
            title = {
                Text(text = stringResource(id = R.string.settings_target_manager_active_title))
            },
            text = {
                Text(text = stringResource(id = R.string.settings_target_manager_active_body))
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingTargetManagerMode = null
                        onTargetManagerModeChange(pendingMode)
                    }
                ) {
                    Text(text = stringResource(id = R.string.settings_target_manager_enable_active))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingTargetManagerMode = null }) {
                    Text(text = stringResource(id = android.R.string.cancel))
                }
            }
        )
    }
    if (pendingTargetManagerPriorityEnable) {
        AlertDialog(
            modifier = Modifier.testTag("target_manager_priority_confirm_dialog"),
            onDismissRequest = { pendingTargetManagerPriorityEnable = false },
            title = {
                Text(text = stringResource(id = R.string.settings_target_manager_priority_confirm_title))
            },
            text = {
                Text(text = stringResource(id = R.string.settings_target_manager_priority_confirm_body))
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingTargetManagerPriorityEnable = false
                        onTargetManagerCopilotPriorityChange(true)
                    },
                    modifier = Modifier.testTag("target_manager_priority_confirm")
                ) {
                    Text(text = stringResource(id = R.string.settings_target_manager_priority_confirm_enable))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingTargetManagerPriorityEnable = false },
                    modifier = Modifier.testTag("target_manager_priority_cancel")
                ) {
                    Text(text = stringResource(id = android.R.string.cancel))
                }
            }
        )
    }
    if (pendingLocalNightscoutIdentityReset) {
        AlertDialog(
            onDismissRequest = { pendingLocalNightscoutIdentityReset = false },
            title = { Text(stringResource(id = R.string.settings_local_ns_reset_title)) },
            text = { Text(stringResource(id = R.string.settings_local_ns_reset_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingLocalNightscoutIdentityReset = false
                        onLocalNightscoutIdentityReset(true)
                    }
                ) {
                    Text(stringResource(id = R.string.settings_local_ns_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLocalNightscoutIdentityReset = false }) {
                    Text(stringResource(id = android.R.string.cancel))
                }
            }
        )
    }
    if (showBaseTargetDialog) {
        BaseTargetScheduleDialog(
            schedule = state.baseTargetSchedule,
            effectiveTargetMmol = state.effectiveBaseTargetMmol,
            autoDeltaMmol = state.baseTargetAutoDeltaMmol,
            autoState = state.baseTargetAutoState,
            autoReason = state.baseTargetAutoReason,
            minTargetMmol = state.safetyMinTargetMmol,
            maxTargetMmol = state.safetyMaxTargetMmol,
            onDismiss = { showBaseTargetDialog = false },
            onSave = { schedule ->
                showBaseTargetDialog = false
                onBaseTargetScheduleSave(schedule)
            }
        )
    }
    ScreenStateLayout(
        loadState = state.loadState,
        isStale = state.isStale,
        errorText = state.errorText,
        emptyText = stringResource(id = R.string.settings_empty)
    ) {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                SettingsTabCard(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTabRaw = it.name }
                )
            }

            when (selectedTab) {
                SettingsTab.BASIC -> {
                    item {
                        DataSourcesCard(
                            state = state,
                            serverAiConnectionState = serverAiConnectionState,
                            onServerAiActivate = onServerAiActivate,
                            onServerAiResume = onServerAiResume,
                            onServerAiCheck = onServerAiCheck,
                            onNightscoutUrlSave = onNightscoutUrlSave,
                            onAiApiSettingsSave = onAiApiSettingsSave,
                            onAiCredentialReplace = onAiCredentialReplace,
                            onAiCredentialDelete = onAiCredentialDelete,
                            onClinicalAiProviderChange = onClinicalAiProviderChange,
                            onClinicalAiModelChange = onClinicalAiModelChange,
                            onClinicalAiCustomModelChange = onClinicalAiCustomModelChange,
                            onClinicalAiEndpointChange = onClinicalAiEndpointChange,
                            onClinicalAiProtocolChange = onClinicalAiProtocolChange,
                            onAutomaticCauseAnalysisToggle =
                                onAutomaticCauseAnalysisToggle,
                            onClinicalAiCredentialReplace = onClinicalAiCredentialReplace,
                            onClinicalAiCredentialDelete = onClinicalAiCredentialDelete,
                            onClinicalAiTestConnection = onClinicalAiTestConnection,
                            onUiStyleChange = onUiStyleChange,
                            onLocalNightscoutToggle = onLocalNightscoutToggle,
                            onLocalNightscoutLegacyMigrationAcknowledged =
                                onLocalNightscoutLegacyMigrationAcknowledged,
                            onLocalNightscoutSecretCopy = onLocalNightscoutSecretCopy,
                            onLocalNightscoutCertificateInstall = onLocalNightscoutCertificateInstall,
                            onLocalNightscoutCertificateExport = onLocalNightscoutCertificateExport,
                            onLocalNightscoutIdentityReset = {
                                pendingLocalNightscoutIdentityReset = true
                            },
                            onLocalBroadcastIngestToggle = onLocalBroadcastIngestToggle,
                            onStrictSenderValidationToggle = onStrictSenderValidationToggle
                        )
                    }
                    item {
                        UamSettingsCard(
                            state = state,
                            onUamInferenceToggle = onUamInferenceToggle,
                            onUamBoostToggle = onUamBoostToggle,
                            onUamExportUiModeChange = onUamExportUiModeChange,
                            onUamSnackConfigChange = onUamSnackConfigChange
                        )
                    }
                    item {
                        AdaptiveSettingsCard(
                            state = state,
                            onAdaptiveControllerToggle = onAdaptiveControllerToggle,
                            onEditBaseTargetSchedule = { showBaseTargetDialog = true },
                            onInsulinProfileSelect = onInsulinProfileSelect,
                            onSafetyBoundsChange = onSafetyBoundsChange,
                            onPostHypoThresholdChange = onPostHypoThresholdChange,
                            onPostHypoTargetChange = onPostHypoTargetChange
                        )
                    }
                    item {
                        GlucoseAlertsCard(
                            state = state,
                            onSoftAlertEnabledToggle = onSoftAlertEnabledToggle,
                            onWatch60AlertEnabledToggle = onWatch60AlertEnabledToggle,
                            onWarning30AlertEnabledToggle = onWarning30AlertEnabledToggle,
                            onSoftHighAlertEnabledToggle = onSoftHighAlertEnabledToggle,
                            onCritical5AlertEnabledToggle = onCritical5AlertEnabledToggle,
                            onLowNowAlertEnabledToggle = onLowNowAlertEnabledToggle,
                            onSoftAlertLowChange = onSoftAlertLowChange,
                            onSoftAlertHighChange = onSoftAlertHighChange,
                            onUrgentLowChange = onUrgentLowChange,
                            onSoftAlertAudioSettingsChange = onSoftAlertAudioSettingsChange,
                            onCriticalAlertAudio1SettingsChange = onCriticalAlertAudio1SettingsChange,
                            onCriticalAlertAudio2SettingsChange = onCriticalAlertAudio2SettingsChange,
                            onReplaceGlucoseAlertAudio = onReplaceGlucoseAlertAudio,
                            onPreviewGlucoseAlertAudio = onPreviewGlucoseAlertAudio,
                            onStopPreviewGlucoseAlertAudio = onStopPreviewGlucoseAlertAudio,
                            onResetGlucoseAlertAudio = onResetGlucoseAlertAudio
                        )
                    }
                    if (telegramSettingsContent != null) {
                        item { telegramSettingsContent() }
                    }
                    item {
                        DisclaimerCard(text = state.warningText)
                    }
                    item {
                        AppInfoCard()
                    }
                }

                SettingsTab.ADVANCED -> {
                    item {
                        EnergyActivitySettingsSection(
                            state = state.energyProfile,
                            onEnabledChange = onEnergyProfileEnabledChange,
                            onSaveUserProfile = onEnergyProfileUserProfileSave,
                            onSaveFoodSettings = onEnergyProfileFoodSettingsSave,
                            onSaveActivitySettings = onEnergyProfileActivitySettingsSave,
                            onSaveEvent = onPlannedActivitySave,
                            onDeleteEvent = onPlannedActivityDelete,
                            onSaveEnergySettings = onEnergyGoalSettingsSave,
                            openPlannedActivityRequest = openPlannedActivityRequest
                        )
                    }
                    item {
                        UamAutoExportCapCard(
                            state = state,
                            onChange = onUamAutoExportCapChange
                        )
                    }
                    item {
                        TargetManagerSettingsCard(
                            state = state,
                            status = targetManagerStatus,
                            onTimingChange = onTargetManagerTimingChange,
                            onModeChange = { candidate ->
                                when {
                                    candidate == "AUTO" -> onTargetManagerAutomaticMode()
                                    targetManagerModeChangeNeedsConfirmation(
                                        currentMode = state.targetManagerMode,
                                        candidateMode = candidate
                                    ) -> pendingTargetManagerMode = candidate
                                    else -> onTargetManagerModeChange(candidate)
                                }
                            },
                            onPriorityChange = { candidate ->
                                if (
                                    targetManagerPriorityChangeNeedsConfirmation(
                                        currentEnabled = state.targetManagerCopilotPriorityEnabled,
                                        candidateEnabled = candidate
                                    )
                                ) {
                                    pendingTargetManagerPriorityEnable = true
                                } else {
                                    onTargetManagerCopilotPriorityChange(candidate)
                                }
                            }
                        )
                    }
                    item {
                        CircadianSettingsCard(
                            state = state,
                            onCircadianPatternsEnabledToggle = onCircadianPatternsEnabledToggle,
                            onCircadianLookbackChange = onCircadianLookbackChange,
                            onCircadianWeekendSplitToggle = onCircadianWeekendSplitToggle,
                            onCircadianReplayResidualBiasToggle = onCircadianReplayResidualBiasToggle,
                            onCircadianForecastWeightsChange = onCircadianForecastWeightsChange
                        )
                    }
                    item {
                        SensorLagSettingsCard(
                            state = state,
                            onModeChange = onSensorLagCorrectionModeChange
                        )
                    }
                    item {
                        IsfCrSettingsCard(
                            state = state,
                            onIsfRuntimeSourceChange = onIsfRuntimeSourceChange,
                            onCrRuntimeSourceChange = onCrRuntimeSourceChange,
                            onShadowModeToggle = onIsfCrShadowModeToggle,
                            onConfidenceThresholdChange = onIsfCrConfidenceThresholdChange,
                            onUseActivityToggle = onIsfCrUseActivityToggle,
                            onUseManualTagsToggle = onIsfCrUseManualTagsToggle,
                            onMinEvidencePerHourChange = onIsfCrMinEvidencePerHourChange,
                            onCrIntegrityGateSettingsChange = onIsfCrCrIntegrityGateSettingsChange,
                            onRetentionChange = onIsfCrRetentionChange,
                            onAutoActivationEnabledToggle = onIsfCrAutoActivationEnabledToggle,
                            onAutoActivationLookbackHoursChange = onIsfCrAutoActivationLookbackHoursChange,
                            onAutoActivationMinSamplesChange = onIsfCrAutoActivationMinSamplesChange,
                            onAutoActivationMinMeanConfidenceChange = onIsfCrAutoActivationMinMeanConfidenceChange,
                            onAutoActivationMaxMeanAbsDeltaPctChange = onIsfCrAutoActivationMaxMeanAbsDeltaPctChange,
                            onAutoActivationSensorThresholdsChange = onIsfCrAutoActivationSensorThresholdsChange,
                            onAutoActivationDayTypeThresholdsChange = onIsfCrAutoActivationDayTypeThresholdsChange,
                            onAutoActivationRequireDailyQualityGateToggle = onIsfCrAutoActivationRequireDailyQualityGateToggle,
                            onAutoActivationDailyRiskBlockLevelChange = onIsfCrAutoActivationDailyRiskBlockLevelChange,
                            onAutoActivationDailyQualityThresholdsChange = onIsfCrAutoActivationDailyQualityThresholdsChange,
                            onAutoActivationRollingGateSettingsChange = onIsfCrAutoActivationRollingGateSettingsChange,
                            onAddPhysioTag = onAddPhysioTag,
                            onClosePhysioTag = onClosePhysioTag,
                            onClearPhysioTags = onClearPhysioTags
                        )
                    }
                    item {
                        DebugSettingsCard(
                            proModeEnabled = state.proModeEnabled,
                            verboseLogsEnabled = state.verboseLogsEnabled,
                            onProModeToggle = onProModeToggle,
                            onVerboseLogsToggle = onVerboseLogsToggle
                        )
                    }
                    item {
                        PrivacyCard(
                            retentionDays = state.retentionDays,
                            onRetentionDaysChange = onRetentionDaysChange
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetManagerSettingsCard(
    state: SettingsUiState,
    status: TargetManagerLiveStatusUi,
    onTimingChange: (TargetManagerTimingSetting, Int) -> Unit,
    onModeChange: (String) -> Unit,
    onPriorityChange: (Boolean) -> Unit
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    val selectedMode = if (state.targetManagerModeManualOverride) {
        state.targetManagerMode.uppercase()
    } else {
        "AUTO"
    }
    val modes = listOf("AUTO", "OFF", "SHADOW", "ACTIVE")
    var showDiagnostics by remember { mutableStateOf(false) }
    if (showDiagnostics) {
        AlertDialog(
            modifier = Modifier.testTag("settings_target_manager_diagnostics"),
            onDismissRequest = { showDiagnostics = false },
            title = { Text(stringResource(R.string.settings_target_diagnostics)) },
            text = {
                Column {
                    TargetManagerLiveStatusSection(status, midnightGlass = false, framed = false)
                    Text(stringResource(R.string.settings_target_diagnostics_limit), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnostics = false }) {
                    Text(stringResource(R.string.overview_uam_dialog_close))
                }
            }
        )
    }
    SettingsSectionCard {
        SettingsSectionLabel(text = stringResource(id = R.string.section_settings_target_manager))
        Text(
            text = stringResource(id = R.string.settings_target_manager_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = selectedMode == mode,
                    onClick = { onModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    colors = if (midnightGlass) {
                        SegmentedButtonDefaults.colors(
                            activeContainerColor = Color(0xFF1D4ED8),
                            activeContentColor = Color(0xFFF8FAFC),
                            inactiveContainerColor = Color(0xAA101D38),
                            inactiveContentColor = Color(0xFFB5C0D8),
                            activeBorderColor = Color(0x332563EB),
                            inactiveBorderColor = Color(0x1FFFFFFF)
                        )
                    } else {
                        SegmentedButtonDefaults.colors()
                    },
                    label = { Text(text = mode, fontSize = 12.sp) },
                    modifier = Modifier.testTag("target_manager_mode_$mode")
                )
            }
        }
        Text(
            text = stringResource(
                id = if (state.targetManagerModeManualOverride) {
                    R.string.settings_target_manager_manual
                } else {
                    R.string.settings_target_manager_auto
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (midnightGlass) {
                Color(0xFF93A5C3)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        SettingToggleRow(
            title = stringResource(id = R.string.settings_target_manager_priority),
            subtitle = stringResource(id = R.string.settings_target_manager_priority_subtitle),
            infoText = stringResource(id = R.string.settings_target_manager_priority_info),
            value = state.targetManagerCopilotPriorityEnabled,
            onToggle = onPriorityChange,
            testTag = "target_manager_priority_switch"
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_target_ceiling, UiFormatters.formatMmol(state.safetyMaxTargetMmol, 1)),
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium
            )
            IconButton(
                onClick = { showDiagnostics = true },
                modifier = Modifier.testTag("target_manager_diagnostics_open")
            ) { Icon(Icons.Default.Info, contentDescription = stringResource(R.string.settings_target_diagnostics)) }
        }
        if (status.availability == TargetManagerLiveStatusAvailabilityUi.CURRENT) {
            Text(targetManagerReasonLabel(targetManagerReasonUiKind(status.reason)), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.settings_target_retarget), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf(5, 15, 30).forEachIndexed { index, minutes ->
                SegmentedButton(
                    selected = state.adaptiveControllerRetargetMinutes == minutes,
                    onClick = { onTimingChange(TargetManagerTimingSetting.RETARGET, minutes) },
                    shape = SegmentedButtonDefaults.itemShape(index, 3),
                    modifier = Modifier.testTag("target_manager_retarget_$minutes")
                ) { Text("$minutes ${stringResource(R.string.unit_minutes)}") }
            }
        }
        listOf(
            Triple(TargetManagerTimingSetting.POST_HYPO, R.string.settings_target_post_hypo_pause, state.rulePostHypoCooldownMinutes),
            Triple(TargetManagerTimingSetting.PATTERN, R.string.settings_target_pattern_pause, state.rulePatternCooldownMinutes),
            Triple(TargetManagerTimingSetting.SEGMENT, R.string.settings_target_segment_pause, state.ruleSegmentCooldownMinutes)
        ).forEach { (setting, label, minutes) ->
            val title = stringResource(label)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(
                    onClick = { onTimingChange(setting, (minutes - 5).coerceAtLeast(0)) },
                    enabled = minutes > 0,
                    modifier = Modifier.testTag("target_manager_${setting.name}_minus")
                ) { Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.settings_target_decrease, title)) }
                Text("$minutes ${stringResource(R.string.unit_minutes)}", modifier = Modifier.width(64.dp))
                IconButton(
                    onClick = { onTimingChange(setting, (minutes + 5).coerceAtMost(240)) },
                    enabled = minutes < 240,
                    modifier = Modifier.testTag("target_manager_${setting.name}_plus")
                ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.settings_target_increase, title)) }
            }
        }
    }
}

@Composable
private fun SettingsTabCard(
    selectedTab: SettingsTab,
    onTabSelected: (SettingsTab) -> Unit
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    SettingsSectionCard {
        SettingsSectionLabel(text = stringResource(id = R.string.section_settings_mode))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val options = listOf(
                SettingsTab.BASIC to stringResource(id = R.string.settings_tab_basic),
                SettingsTab.ADVANCED to stringResource(id = R.string.settings_tab_advanced)
            )
            options.forEachIndexed { index, (tab, label) ->
                SegmentedButton(
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    ),
                    colors = if (midnightGlass) {
                        SegmentedButtonDefaults.colors(
                            activeContainerColor = Color(0xFF1D4ED8),
                            activeContentColor = Color(0xFFF8FAFC),
                            inactiveContainerColor = Color(0xAA101D38),
                            inactiveContentColor = Color(0xFFB5C0D8),
                            activeBorderColor = Color(0x332563EB),
                            inactiveBorderColor = Color(0x1FFFFFFF)
                        )
                    } else {
                        SegmentedButtonDefaults.colors()
                    }
                ) {
                    Text(text = label)
                }
            }
        }
        Text(
            text = if (selectedTab == SettingsTab.BASIC) {
                stringResource(id = R.string.settings_tab_basic_subtitle)
            } else {
                stringResource(id = R.string.settings_tab_advanced_subtitle)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CircadianSettingsCard(
    state: SettingsUiState,
    onCircadianPatternsEnabledToggle: (Boolean) -> Unit,
    onCircadianLookbackChange: (Int, Int) -> Unit,
    onCircadianWeekendSplitToggle: (Boolean) -> Unit,
    onCircadianReplayResidualBiasToggle: (Boolean) -> Unit,
    onCircadianForecastWeightsChange: (Double, Double) -> Unit
) {
    SettingsSectionCard {
        SettingsSectionLabel(text = stringResource(id = R.string.section_settings_circadian))
        SettingToggleRow(
            title = stringResource(id = R.string.settings_circadian_enabled),
            subtitle = stringResource(id = R.string.settings_circadian_enabled_subtitle),
            infoText = stringResource(id = R.string.settings_info_circadian_enabled),
            value = state.circadianPatternsEnabled,
            onToggle = onCircadianPatternsEnabledToggle
        )
        AnimatedVisibility(visible = state.circadianPatternsEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OptionChipsRow(
                    title = stringResource(id = R.string.settings_circadian_stable_lookback),
                    options = listOf("7d", "10d", "14d"),
                    selected = "${state.circadianStableLookbackDays}d",
                    infoText = stringResource(id = R.string.settings_info_circadian_stable_lookback),
                    onSelect = { raw ->
                        val days = raw.removeSuffix("d").toIntOrNull() ?: state.circadianStableLookbackDays
                        onCircadianLookbackChange(days, state.circadianRecencyLookbackDays)
                    }
                )
                SettingIntStepperRow(
                    title = stringResource(id = R.string.settings_circadian_recency_lookback),
                    subtitle = stringResource(id = R.string.settings_circadian_recency_lookback_subtitle),
                    infoText = stringResource(id = R.string.settings_info_circadian_recency_lookback),
                    value = state.circadianRecencyLookbackDays,
                    min = 3,
                    max = 7,
                    step = 1,
                    onValueChange = { next ->
                        onCircadianLookbackChange(state.circadianStableLookbackDays, next)
                    }
                )
                SettingToggleRow(
                    title = stringResource(id = R.string.settings_circadian_weekend_split),
                    subtitle = stringResource(id = R.string.settings_circadian_weekend_split_subtitle),
                    infoText = stringResource(id = R.string.settings_info_circadian_weekend_split),
                    value = state.circadianUseWeekendSplit,
                    onToggle = onCircadianWeekendSplitToggle
                )
                SettingToggleRow(
                    title = stringResource(id = R.string.settings_circadian_replay_bias),
                    subtitle = stringResource(id = R.string.settings_circadian_replay_bias_subtitle),
                    infoText = stringResource(id = R.string.settings_info_circadian_replay_bias),
                    value = state.circadianUseReplayResidualBias,
                    onToggle = onCircadianReplayResidualBiasToggle
                )
                SettingIntStepperRow(
                    title = stringResource(id = R.string.settings_circadian_weight_30),
                    subtitle = stringResource(id = R.string.settings_circadian_weight_30_subtitle),
                    infoText = stringResource(id = R.string.settings_info_circadian_weight_30),
                    value = (state.circadianForecastWeight30 * 100).roundToInt(),
                    min = 0,
                    max = 45,
                    step = 5,
                    onValueChange = { next ->
                        onCircadianForecastWeightsChange(next / 100.0, state.circadianForecastWeight60)
                    }
                )
                SettingIntStepperRow(
                    title = stringResource(id = R.string.settings_circadian_weight_60),
                    subtitle = stringResource(id = R.string.settings_circadian_weight_60_subtitle),
                    infoText = stringResource(id = R.string.settings_info_circadian_weight_60),
                    value = (state.circadianForecastWeight60 * 100).roundToInt(),
                    min = 0,
                    max = 55,
                    step = 5,
                    onValueChange = { next ->
                        onCircadianForecastWeightsChange(state.circadianForecastWeight30, next / 100.0)
                    }
                )
            }
        }
    }
}

@Composable
private fun SensorLagSettingsCard(
    state: SettingsUiState,
    onModeChange: (String) -> Unit
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    val modes = listOf("OFF", "SHADOW", "ACTIVE")
    SettingsSectionCard {
        SettingsSectionLabel(text = "Sensor Lag")
        Text(
            text = "Estimate current blood glucose from sensor age + ROC. SHADOW computes advisory values only; ACTIVE feeds corrected glucose into Copilot rules.",
            style = MaterialTheme.typography.bodySmall,
            color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.sensorLagCorrectionMode.equals(mode, ignoreCase = true),
                    onClick = { onModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    colors = if (midnightGlass) {
                        SegmentedButtonDefaults.colors(
                            activeContainerColor = Color(0xFF1D4ED8),
                            activeContentColor = Color(0xFFF8FAFC),
                            inactiveContainerColor = Color(0xAA101D38),
                            inactiveContentColor = Color(0xFFB5C0D8),
                            activeBorderColor = Color(0x332563EB),
                            inactiveBorderColor = Color(0x1FFFFFFF)
                        )
                    } else {
                        SegmentedButtonDefaults.colors()
                    },
                    label = { Text(text = mode) }
                )
            }
        }
        Text(
            text = when {
                state.sensorLagCorrectionMode.equals("ACTIVE", ignoreCase = true) ->
                    "ACTIVE is gated by data freshness, sensor quality, age resolution and glucose input source."
                state.sensorLagCorrectionMode.equals("SHADOW", ignoreCase = true) ->
                    "SHADOW keeps raw forecasts/actions unchanged and logs the corrected path for comparison."
                else ->
                    "OFF leaves forecasts and rules on raw sensor glucose."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (midnightGlass) Color(0xFF93A5C3) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun IsfCrSettingsCard(
    state: SettingsUiState,
    onIsfRuntimeSourceChange: (String) -> Unit,
    onCrRuntimeSourceChange: (String) -> Unit,
    onShadowModeToggle: (Boolean) -> Unit,
    onConfidenceThresholdChange: (Double) -> Unit,
    onUseActivityToggle: (Boolean) -> Unit,
    onUseManualTagsToggle: (Boolean) -> Unit,
    onMinEvidencePerHourChange: (Int, Int) -> Unit,
    onCrIntegrityGateSettingsChange: (Int, Double, Double) -> Unit,
    onRetentionChange: (Int, Int) -> Unit,
    onAutoActivationEnabledToggle: (Boolean) -> Unit,
    onAutoActivationLookbackHoursChange: (Int) -> Unit,
    onAutoActivationMinSamplesChange: (Int) -> Unit,
    onAutoActivationMinMeanConfidenceChange: (Double) -> Unit,
    onAutoActivationMaxMeanAbsDeltaPctChange: (Double, Double) -> Unit,
    onAutoActivationSensorThresholdsChange: (Double, Double, Double, Double, Double) -> Unit,
    onAutoActivationDayTypeThresholdsChange: (Double, Double) -> Unit,
    onAutoActivationRequireDailyQualityGateToggle: (Boolean) -> Unit,
    onAutoActivationDailyRiskBlockLevelChange: (Int) -> Unit,
    onAutoActivationDailyQualityThresholdsChange: (Int, Double, Double, Double, Double, Double, Double, Double) -> Unit,
    onAutoActivationRollingGateSettingsChange: (Int, Double, Double, Double) -> Unit,
    onAddPhysioTag: (String, Double, Int) -> Unit,
    onClosePhysioTag: (String, Long) -> Unit,
    onClearPhysioTags: () -> Unit
) {
    var quickTagSeverityPercent by rememberSaveable { mutableStateOf(70) }
    var quickTagDurationHours by rememberSaveable { mutableStateOf(6) }
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    val runtimeSources = listOf(
        "EVIDENCE" to stringResource(id = R.string.settings_isf_runtime_source_evidence),
        "AAPS" to stringResource(id = R.string.settings_isf_runtime_source_aaps),
        "COPILOT" to stringResource(id = R.string.settings_isf_runtime_source_copilot)
    )
    SettingsSectionCard {
        SettingsSectionLabel(text = stringResource(id = R.string.section_settings_isfcr))

        Text(
            text = stringResource(id = R.string.settings_isf_runtime_source),
            style = MaterialTheme.typography.titleSmall,
            color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            runtimeSources.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = state.isfRuntimeSourcePreference.equals(mode, ignoreCase = true),
                    enabled = sensitivitySourceControlsEnabled(state),
                    onClick = { onIsfRuntimeSourceChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = runtimeSources.size),
                    colors = if (midnightGlass) {
                        SegmentedButtonDefaults.colors(
                            activeContainerColor = Color(0xFF1D4ED8),
                            activeContentColor = Color(0xFFF8FAFC),
                            inactiveContainerColor = Color(0xAA101D38),
                            inactiveContentColor = Color(0xFFB5C0D8),
                            activeBorderColor = Color(0x332563EB),
                            inactiveBorderColor = Color(0x1FFFFFFF)
                        )
                    } else {
                        SegmentedButtonDefaults.colors()
                    },
                    label = { Text(text = label) }
                )
            }
        }

        Text(
            text = stringResource(id = R.string.settings_cr_runtime_source),
            style = MaterialTheme.typography.titleSmall,
            color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            runtimeSources.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = state.crRuntimeSourcePreference.equals(mode, ignoreCase = true),
                    enabled = sensitivitySourceControlsEnabled(state),
                    onClick = { onCrRuntimeSourceChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = runtimeSources.size),
                    label = { Text(text = label) }
                )
            }
        }

        if (state.sensitivitySourceApplying) {
            Text(
                text = "${state.sensitivitySourcePendingMetric.orEmpty()}: applying ${state.sensitivitySourcePendingValue.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = if (midnightGlass) Color(0xFF8DB6FF) else MaterialTheme.colorScheme.primary
            )
        } else if (!state.sensitivitySourceApplyError.isNullOrBlank()) {
            Text(
                text = state.sensitivitySourceApplyError.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        SettingToggleRow(
            title = stringResource(id = R.string.settings_isfcr_shadow_mode),
            subtitle = stringResource(id = R.string.settings_isfcr_shadow_mode_subtitle),
            infoText = stringResource(id = R.string.settings_info_isfcr_shadow_mode),
            value = state.isfCrShadowMode,
            onToggle = onShadowModeToggle
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_confidence_threshold),
            subtitle = stringResource(id = R.string.settings_isfcr_confidence_threshold_subtitle),
            infoText = stringResource(id = R.string.settings_info_isfcr_confidence_threshold),
            value = (state.isfCrConfidenceThreshold * 100).roundToInt(),
            min = 20,
            max = 95,
            step = 5,
            onValueChange = { percent ->
                onConfidenceThresholdChange(percent / 100.0)
            }
        )
        SettingToggleRow(
            title = stringResource(id = R.string.settings_isfcr_use_activity),
            subtitle = stringResource(id = R.string.settings_isfcr_use_activity_subtitle),
            infoText = stringResource(id = R.string.settings_info_isfcr_use_activity),
            value = state.isfCrUseActivity,
            onToggle = onUseActivityToggle
        )
        SettingToggleRow(
            title = stringResource(id = R.string.settings_isfcr_use_manual_tags),
            subtitle = stringResource(id = R.string.settings_isfcr_use_manual_tags_subtitle),
            infoText = stringResource(id = R.string.settings_info_isfcr_use_manual_tags),
            value = state.isfCrUseManualTags,
            onToggle = onUseManualTagsToggle
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_min_isf_evidence_per_hour),
            subtitle = stringResource(id = R.string.settings_isfcr_min_isf_evidence_per_hour_subtitle),
            infoText = stringResource(id = R.string.settings_info_isfcr_min_isf_evidence),
            value = state.isfCrMinIsfEvidencePerHour,
            min = 0,
            max = 12,
            step = 1,
            onValueChange = { next ->
                onMinEvidencePerHourChange(next, state.isfCrMinCrEvidencePerHour)
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_min_cr_evidence_per_hour),
            subtitle = stringResource(id = R.string.settings_isfcr_min_cr_evidence_per_hour_subtitle),
            infoText = stringResource(id = R.string.settings_info_isfcr_min_cr_evidence),
            value = state.isfCrMinCrEvidencePerHour,
            min = 0,
            max = 12,
            step = 1,
            onValueChange = { next ->
                onMinEvidencePerHourChange(state.isfCrMinIsfEvidencePerHour, next)
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_cr_max_gap_minutes),
            subtitle = stringResource(id = R.string.settings_isfcr_cr_max_gap_minutes_subtitle),
            infoText = stringResource(id = R.string.settings_info_isfcr_cr_max_gap_minutes),
            value = state.isfCrCrMaxGapMinutes,
            min = 10,
            max = 60,
            step = 5,
            onValueChange = { next ->
                onCrIntegrityGateSettingsChange(
                    next,
                    state.isfCrCrMaxSensorBlockedRatePct,
                    state.isfCrCrMaxUamAmbiguityRatePct
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_cr_max_sensor_blocked_rate),
            subtitle = stringResource(id = R.string.settings_isfcr_cr_max_sensor_blocked_rate_subtitle),
            infoText = stringResource(id = R.string.settings_info_isfcr_cr_max_sensor_blocked_rate),
            value = state.isfCrCrMaxSensorBlockedRatePct.roundToInt(),
            min = 0,
            max = 100,
            step = 1,
            onValueChange = { next ->
                onCrIntegrityGateSettingsChange(
                    state.isfCrCrMaxGapMinutes,
                    next.toDouble(),
                    state.isfCrCrMaxUamAmbiguityRatePct
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_cr_max_uam_ambiguity_rate),
            subtitle = stringResource(id = R.string.settings_isfcr_cr_max_uam_ambiguity_rate_subtitle),
            infoText = stringResource(id = R.string.settings_info_isfcr_cr_max_uam_ambiguity_rate),
            value = state.isfCrCrMaxUamAmbiguityRatePct.roundToInt(),
            min = 0,
            max = 100,
            step = 1,
            onValueChange = { next ->
                onCrIntegrityGateSettingsChange(
                    state.isfCrCrMaxGapMinutes,
                    state.isfCrCrMaxSensorBlockedRatePct,
                    next.toDouble()
                )
            }
        )
        SettingToggleRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_subtitle),
            value = state.isfCrAutoActivationEnabled,
            onToggle = onAutoActivationEnabledToggle
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_lookback_hours),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_lookback_hours_subtitle),
            value = state.isfCrAutoActivationLookbackHours,
            min = 6,
            max = 72,
            step = 6,
            onValueChange = onAutoActivationLookbackHoursChange
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_min_samples),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_min_samples_subtitle),
            value = state.isfCrAutoActivationMinSamples,
            min = 12,
            max = 288,
            step = 12,
            onValueChange = onAutoActivationMinSamplesChange
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_min_confidence),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_min_confidence_subtitle),
            value = (state.isfCrAutoActivationMinMeanConfidence * 100).roundToInt(),
            min = 20,
            max = 95,
            step = 5,
            onValueChange = { percent ->
                onAutoActivationMinMeanConfidenceChange(percent / 100.0)
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_isf_delta_pct),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_isf_delta_pct_subtitle),
            value = state.isfCrAutoActivationMaxMeanAbsIsfDeltaPct.roundToInt(),
            min = 5,
            max = 100,
            step = 5,
            onValueChange = { next ->
                onAutoActivationMaxMeanAbsDeltaPctChange(next.toDouble(), state.isfCrAutoActivationMaxMeanAbsCrDeltaPct)
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_cr_delta_pct),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_cr_delta_pct_subtitle),
            value = state.isfCrAutoActivationMaxMeanAbsCrDeltaPct.roundToInt(),
            min = 5,
            max = 100,
            step = 5,
            onValueChange = { next ->
                onAutoActivationMaxMeanAbsDeltaPctChange(state.isfCrAutoActivationMaxMeanAbsIsfDeltaPct, next.toDouble())
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_min_sensor_quality_score),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_min_sensor_quality_score_subtitle),
            value = (state.isfCrAutoActivationMinSensorQualityScore * 100).roundToInt(),
            min = 0,
            max = 100,
            step = 1,
            onValueChange = { next ->
                onAutoActivationSensorThresholdsChange(
                    next / 100.0,
                    state.isfCrAutoActivationMinSensorFactor,
                    state.isfCrAutoActivationMaxWearConfidencePenalty,
                    state.isfCrAutoActivationMaxSensorAgeHighRatePct,
                    state.isfCrAutoActivationMaxSuspectFalseLowRatePct
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_min_sensor_factor),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_min_sensor_factor_subtitle),
            value = (state.isfCrAutoActivationMinSensorFactor * 100).roundToInt(),
            min = 0,
            max = 100,
            step = 1,
            onValueChange = { next ->
                onAutoActivationSensorThresholdsChange(
                    state.isfCrAutoActivationMinSensorQualityScore,
                    next / 100.0,
                    state.isfCrAutoActivationMaxWearConfidencePenalty,
                    state.isfCrAutoActivationMaxSensorAgeHighRatePct,
                    state.isfCrAutoActivationMaxSuspectFalseLowRatePct
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_max_wear_penalty),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_max_wear_penalty_subtitle),
            value = (state.isfCrAutoActivationMaxWearConfidencePenalty * 100).roundToInt(),
            min = 0,
            max = 100,
            step = 1,
            onValueChange = { next ->
                onAutoActivationSensorThresholdsChange(
                    state.isfCrAutoActivationMinSensorQualityScore,
                    state.isfCrAutoActivationMinSensorFactor,
                    next / 100.0,
                    state.isfCrAutoActivationMaxSensorAgeHighRatePct,
                    state.isfCrAutoActivationMaxSuspectFalseLowRatePct
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_max_sensor_age_high_rate),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_max_sensor_age_high_rate_subtitle),
            value = state.isfCrAutoActivationMaxSensorAgeHighRatePct.roundToInt(),
            min = 0,
            max = 100,
            step = 1,
            onValueChange = { next ->
                onAutoActivationSensorThresholdsChange(
                    state.isfCrAutoActivationMinSensorQualityScore,
                    state.isfCrAutoActivationMinSensorFactor,
                    state.isfCrAutoActivationMaxWearConfidencePenalty,
                    next.toDouble(),
                    state.isfCrAutoActivationMaxSuspectFalseLowRatePct
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_max_suspect_false_low_rate),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_max_suspect_false_low_rate_subtitle),
            value = state.isfCrAutoActivationMaxSuspectFalseLowRatePct.roundToInt(),
            min = 0,
            max = 100,
            step = 1,
            onValueChange = { next ->
                onAutoActivationSensorThresholdsChange(
                    state.isfCrAutoActivationMinSensorQualityScore,
                    state.isfCrAutoActivationMinSensorFactor,
                    state.isfCrAutoActivationMaxWearConfidencePenalty,
                    state.isfCrAutoActivationMaxSensorAgeHighRatePct,
                    next.toDouble()
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_min_day_type_ratio),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_min_day_type_ratio_subtitle),
            value = (state.isfCrAutoActivationMinDayTypeRatio * 100).roundToInt(),
            min = 0,
            max = 100,
            step = 1,
            onValueChange = { next ->
                onAutoActivationDayTypeThresholdsChange(
                    next / 100.0,
                    state.isfCrAutoActivationMaxDayTypeSparseRatePct
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_max_day_type_sparse_rate),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_max_day_type_sparse_rate_subtitle),
            value = state.isfCrAutoActivationMaxDayTypeSparseRatePct.roundToInt(),
            min = 0,
            max = 100,
            step = 1,
            onValueChange = { next ->
                onAutoActivationDayTypeThresholdsChange(
                    state.isfCrAutoActivationMinDayTypeRatio,
                    next.toDouble()
                )
            }
        )
        SettingToggleRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_require_quality_gate),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_require_quality_gate_subtitle),
            value = state.isfCrAutoActivationRequireDailyQualityGate,
            onToggle = onAutoActivationRequireDailyQualityGateToggle
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_daily_risk_block_level),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_daily_risk_block_level_subtitle),
            value = state.isfCrAutoActivationDailyRiskBlockLevel,
            min = 2,
            max = 3,
            step = 1,
            onValueChange = onAutoActivationDailyRiskBlockLevelChange
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_min_daily_samples),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_min_daily_samples_subtitle),
            value = state.isfCrAutoActivationMinDailyMatchedSamples,
            min = 24,
            max = 720,
            step = 12,
            onValueChange = { next ->
                onAutoActivationDailyQualityThresholdsChange(
                    next,
                    state.isfCrAutoActivationMaxDailyMae30Mmol,
                    state.isfCrAutoActivationMaxDailyMae60Mmol,
                    state.isfCrAutoActivationMaxHypoRatePct,
                    state.isfCrAutoActivationMinDailyCiCoverage30Pct,
                    state.isfCrAutoActivationMinDailyCiCoverage60Pct,
                    state.isfCrAutoActivationMaxDailyCiWidth30Mmol,
                    state.isfCrAutoActivationMaxDailyCiWidth60Mmol
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_max_daily_mae_30),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_max_daily_mae_30_subtitle),
            value = (state.isfCrAutoActivationMaxDailyMae30Mmol * 100).roundToInt(),
            min = 30,
            max = 400,
            step = 5,
            onValueChange = { next ->
                onAutoActivationDailyQualityThresholdsChange(
                    state.isfCrAutoActivationMinDailyMatchedSamples,
                    next / 100.0,
                    state.isfCrAutoActivationMaxDailyMae60Mmol,
                    state.isfCrAutoActivationMaxHypoRatePct,
                    state.isfCrAutoActivationMinDailyCiCoverage30Pct,
                    state.isfCrAutoActivationMinDailyCiCoverage60Pct,
                    state.isfCrAutoActivationMaxDailyCiWidth30Mmol,
                    state.isfCrAutoActivationMaxDailyCiWidth60Mmol
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_max_daily_mae_60),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_max_daily_mae_60_subtitle),
            value = (state.isfCrAutoActivationMaxDailyMae60Mmol * 100).roundToInt(),
            min = 50,
            max = 600,
            step = 5,
            onValueChange = { next ->
                onAutoActivationDailyQualityThresholdsChange(
                    state.isfCrAutoActivationMinDailyMatchedSamples,
                    state.isfCrAutoActivationMaxDailyMae30Mmol,
                    next / 100.0,
                    state.isfCrAutoActivationMaxHypoRatePct,
                    state.isfCrAutoActivationMinDailyCiCoverage30Pct,
                    state.isfCrAutoActivationMinDailyCiCoverage60Pct,
                    state.isfCrAutoActivationMaxDailyCiWidth30Mmol,
                    state.isfCrAutoActivationMaxDailyCiWidth60Mmol
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_max_hypo_rate),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_max_hypo_rate_subtitle),
            value = (state.isfCrAutoActivationMaxHypoRatePct * 10).roundToInt(),
            min = 5,
            max = 300,
            step = 5,
            onValueChange = { next ->
                onAutoActivationDailyQualityThresholdsChange(
                    state.isfCrAutoActivationMinDailyMatchedSamples,
                    state.isfCrAutoActivationMaxDailyMae30Mmol,
                    state.isfCrAutoActivationMaxDailyMae60Mmol,
                    next / 10.0,
                    state.isfCrAutoActivationMinDailyCiCoverage30Pct,
                    state.isfCrAutoActivationMinDailyCiCoverage60Pct,
                    state.isfCrAutoActivationMaxDailyCiWidth30Mmol,
                    state.isfCrAutoActivationMaxDailyCiWidth60Mmol
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_min_daily_ci_coverage_30),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_min_daily_ci_coverage_30_subtitle),
            value = state.isfCrAutoActivationMinDailyCiCoverage30Pct.roundToInt(),
            min = 20,
            max = 99,
            step = 1,
            onValueChange = { next ->
                onAutoActivationDailyQualityThresholdsChange(
                    state.isfCrAutoActivationMinDailyMatchedSamples,
                    state.isfCrAutoActivationMaxDailyMae30Mmol,
                    state.isfCrAutoActivationMaxDailyMae60Mmol,
                    state.isfCrAutoActivationMaxHypoRatePct,
                    next.toDouble(),
                    state.isfCrAutoActivationMinDailyCiCoverage60Pct,
                    state.isfCrAutoActivationMaxDailyCiWidth30Mmol,
                    state.isfCrAutoActivationMaxDailyCiWidth60Mmol
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_min_daily_ci_coverage_60),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_min_daily_ci_coverage_60_subtitle),
            value = state.isfCrAutoActivationMinDailyCiCoverage60Pct.roundToInt(),
            min = 20,
            max = 99,
            step = 1,
            onValueChange = { next ->
                onAutoActivationDailyQualityThresholdsChange(
                    state.isfCrAutoActivationMinDailyMatchedSamples,
                    state.isfCrAutoActivationMaxDailyMae30Mmol,
                    state.isfCrAutoActivationMaxDailyMae60Mmol,
                    state.isfCrAutoActivationMaxHypoRatePct,
                    state.isfCrAutoActivationMinDailyCiCoverage30Pct,
                    next.toDouble(),
                    state.isfCrAutoActivationMaxDailyCiWidth30Mmol,
                    state.isfCrAutoActivationMaxDailyCiWidth60Mmol
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_max_daily_ci_width_30),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_max_daily_ci_width_30_subtitle),
            value = (state.isfCrAutoActivationMaxDailyCiWidth30Mmol * 100).roundToInt(),
            min = 30,
            max = 600,
            step = 5,
            onValueChange = { next ->
                onAutoActivationDailyQualityThresholdsChange(
                    state.isfCrAutoActivationMinDailyMatchedSamples,
                    state.isfCrAutoActivationMaxDailyMae30Mmol,
                    state.isfCrAutoActivationMaxDailyMae60Mmol,
                    state.isfCrAutoActivationMaxHypoRatePct,
                    state.isfCrAutoActivationMinDailyCiCoverage30Pct,
                    state.isfCrAutoActivationMinDailyCiCoverage60Pct,
                    next / 100.0,
                    state.isfCrAutoActivationMaxDailyCiWidth60Mmol
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_max_daily_ci_width_60),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_max_daily_ci_width_60_subtitle),
            value = (state.isfCrAutoActivationMaxDailyCiWidth60Mmol * 100).roundToInt(),
            min = 50,
            max = 800,
            step = 5,
            onValueChange = { next ->
                onAutoActivationDailyQualityThresholdsChange(
                    state.isfCrAutoActivationMinDailyMatchedSamples,
                    state.isfCrAutoActivationMaxDailyMae30Mmol,
                    state.isfCrAutoActivationMaxDailyMae60Mmol,
                    state.isfCrAutoActivationMaxHypoRatePct,
                    state.isfCrAutoActivationMinDailyCiCoverage30Pct,
                    state.isfCrAutoActivationMinDailyCiCoverage60Pct,
                    state.isfCrAutoActivationMaxDailyCiWidth30Mmol,
                    next / 100.0
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_rolling_min_windows),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_rolling_min_windows_subtitle),
            value = state.isfCrAutoActivationRollingMinRequiredWindows,
            min = 1,
            max = 3,
            step = 1,
            onValueChange = { next ->
                onAutoActivationRollingGateSettingsChange(
                    next,
                    state.isfCrAutoActivationRollingMaeRelaxFactor,
                    state.isfCrAutoActivationRollingCiCoverageRelaxFactor,
                    state.isfCrAutoActivationRollingCiWidthRelaxFactor
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_rolling_mae_relax),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_rolling_mae_relax_subtitle),
            value = (state.isfCrAutoActivationRollingMaeRelaxFactor * 100).roundToInt(),
            min = 100,
            max = 150,
            step = 1,
            onValueChange = { next ->
                onAutoActivationRollingGateSettingsChange(
                    state.isfCrAutoActivationRollingMinRequiredWindows,
                    next / 100.0,
                    state.isfCrAutoActivationRollingCiCoverageRelaxFactor,
                    state.isfCrAutoActivationRollingCiWidthRelaxFactor
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_rolling_ci_coverage_relax),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_rolling_ci_coverage_relax_subtitle),
            value = (state.isfCrAutoActivationRollingCiCoverageRelaxFactor * 100).roundToInt(),
            min = 70,
            max = 100,
            step = 1,
            onValueChange = { next ->
                onAutoActivationRollingGateSettingsChange(
                    state.isfCrAutoActivationRollingMinRequiredWindows,
                    state.isfCrAutoActivationRollingMaeRelaxFactor,
                    next / 100.0,
                    state.isfCrAutoActivationRollingCiWidthRelaxFactor
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_auto_activation_rolling_ci_width_relax),
            subtitle = stringResource(id = R.string.settings_isfcr_auto_activation_rolling_ci_width_relax_subtitle),
            value = (state.isfCrAutoActivationRollingCiWidthRelaxFactor * 100).roundToInt(),
            min = 100,
            max = 150,
            step = 1,
            onValueChange = { next ->
                onAutoActivationRollingGateSettingsChange(
                    state.isfCrAutoActivationRollingMinRequiredWindows,
                    state.isfCrAutoActivationRollingMaeRelaxFactor,
                    state.isfCrAutoActivationRollingCiCoverageRelaxFactor,
                    next / 100.0
                )
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_snapshot_retention),
            subtitle = stringResource(id = R.string.settings_isfcr_snapshot_retention_subtitle),
            value = state.isfCrSnapshotRetentionDays,
            min = 30,
            max = 730,
            step = 5,
            onValueChange = { next ->
                onRetentionChange(next, state.isfCrEvidenceRetentionDays)
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_evidence_retention),
            subtitle = stringResource(id = R.string.settings_isfcr_evidence_retention_subtitle),
            value = state.isfCrEvidenceRetentionDays,
            min = 30,
            max = 1095,
            step = 5,
            onValueChange = { next ->
                onRetentionChange(state.isfCrSnapshotRetentionDays, next)
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_quick_tag_severity),
            subtitle = stringResource(id = R.string.settings_isfcr_quick_tag_severity_subtitle),
            value = quickTagSeverityPercent,
            min = 10,
            max = 100,
            step = 5,
            onValueChange = { next ->
                quickTagSeverityPercent = next.coerceIn(10, 100)
            }
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_quick_tag_duration),
            subtitle = stringResource(id = R.string.settings_isfcr_quick_tag_duration_subtitle),
            value = quickTagDurationHours,
            min = 1,
            max = 48,
            step = 1,
            onValueChange = { next ->
                quickTagDurationHours = next.coerceIn(1, 48)
            }
        )
        OptionChipsRow(
            title = stringResource(id = R.string.settings_isfcr_add_tag),
            options = listOf("stress", "illness", "hormonal_phase", "steroids", "dawn"),
            selected = "",
            onSelect = { selected ->
                onAddPhysioTag(
                    selected,
                    quickTagSeverityPercent / 100.0,
                    quickTagDurationHours
                )
            }
        )
        SettingReadOnlyRow(
            title = stringResource(id = R.string.settings_isfcr_active_tags),
            value = state.isfCrActiveTags.joinToString(", ").ifBlank {
                stringResource(id = R.string.settings_isfcr_no_tags)
            }
        )
        if (state.isfCrTagJournal.isNotEmpty()) {
            Text(
                text = stringResource(id = R.string.settings_isfcr_tag_journal),
                style = MaterialTheme.typography.labelLarge
            )
            state.isfCrTagJournal.forEach { item ->
                PhysioTagJournalRow(
                    item = item,
                    onCloseTag = onClosePhysioTag
                )
            }
        }
        if (state.isfCrActiveTags.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = onClearPhysioTags) {
                    Text(text = stringResource(id = R.string.settings_isfcr_clear_tags))
                }
            }
        }
    }
}

internal fun sensitivitySourceControlsEnabled(state: SettingsUiState): Boolean =
    !state.sensitivitySourceApplying

@Composable
private fun DataSourcesCard(
    state: SettingsUiState,
    serverAiConnectionState: ServerAiConnectionState,
    onServerAiActivate: (String) -> Unit,
    onServerAiResume: () -> Unit,
    onServerAiCheck: () -> Unit,
    onNightscoutUrlSave: (String) -> Unit,
    onAiApiSettingsSave: (String) -> Unit,
    onAiCredentialReplace: (String) -> Unit,
    onAiCredentialDelete: () -> Unit,
    onClinicalAiProviderChange: (ClinicalAiProviderId) -> Unit,
    onClinicalAiModelChange: (String) -> Unit,
    onClinicalAiCustomModelChange: (String) -> Unit,
    onClinicalAiEndpointChange: (String) -> Unit,
    onClinicalAiProtocolChange: (OpenAiCompatibleProtocol) -> Unit,
    onAutomaticCauseAnalysisToggle: (Boolean) -> Unit,
    onClinicalAiCredentialReplace: (ClinicalAiProviderId, String) -> Unit,
    onClinicalAiCredentialDelete: (ClinicalAiProviderId) -> Unit,
    onClinicalAiTestConnection: () -> Unit,
    onUiStyleChange: (String) -> Unit,
    onLocalNightscoutToggle: (Boolean) -> Unit,
    onLocalNightscoutLegacyMigrationAcknowledged: (Boolean) -> Unit,
    onLocalNightscoutSecretCopy: () -> Unit,
    onLocalNightscoutCertificateInstall: () -> Unit,
    onLocalNightscoutCertificateExport: () -> Unit,
    onLocalNightscoutIdentityReset: () -> Unit,
    onLocalBroadcastIngestToggle: (Boolean) -> Unit,
    onStrictSenderValidationToggle: (Boolean) -> Unit
) {
    var nightscoutUrlDraft by rememberSaveable(state.nightscoutUrl) {
        mutableStateOf(state.nightscoutUrl)
    }
    var aiApiUrlDraft by rememberSaveable(state.aiApiUrl) {
        mutableStateOf(state.aiApiUrl)
    }
    SettingsSectionCard {
        SettingsSectionLabel(text = stringResource(id = R.string.section_settings_data_sources))

        SettingTextInputRow(
            title = stringResource(id = R.string.settings_nightscout_url),
            subtitle = stringResource(id = R.string.settings_data_sources_nightscout_subtitle),
            infoText = stringResource(id = R.string.settings_info_nightscout_url),
            value = nightscoutUrlDraft,
            placeholder = stringResource(id = R.string.placeholder_missing),
            onValueChange = { nightscoutUrlDraft = it },
            onApply = { onNightscoutUrlSave(nightscoutUrlDraft) }
        )
        SettingTextInputRow(
            title = stringResource(id = R.string.settings_ai_api_url),
            subtitle = stringResource(id = R.string.settings_ai_api_url_subtitle),
            infoText = stringResource(id = R.string.settings_info_ai_api_url),
            value = aiApiUrlDraft,
            placeholder = stringResource(id = R.string.placeholder_missing),
            onValueChange = { aiApiUrlDraft = it },
            onApply = { onAiApiSettingsSave(aiApiUrlDraft) },
            testTag = "legacy_ai_api_url"
        )
        ServerAiConnectionSection(
            state = serverAiConnectionState,
            onActivate = onServerAiActivate,
            onResume = onServerAiResume,
            onCheck = onServerAiCheck
        )
        ClinicalAiSettingsSection(
            state = if (state.clinicalAi.usesLegacyCredentialState) {
                state.clinicalAi.copy(credential = state.aiCredential)
            } else {
                state.clinicalAi
            },
            onProviderChange = onClinicalAiProviderChange,
            onModelChange = onClinicalAiModelChange,
            onCustomModelChange = onClinicalAiCustomModelChange,
            onEndpointChange = onClinicalAiEndpointChange,
            onProtocolChange = onClinicalAiProtocolChange,
            onAutomaticCauseAnalysisToggle = onAutomaticCauseAnalysisToggle,
            onCredentialReplace = { providerId, value ->
                if (state.clinicalAi.usesLegacyCredentialState) {
                    onAiCredentialReplace(value)
                } else {
                    onClinicalAiCredentialReplace(providerId, value)
                }
            },
            onCredentialDelete = { providerId ->
                if (state.clinicalAi.usesLegacyCredentialState) {
                    onAiCredentialDelete()
                } else {
                    onClinicalAiCredentialDelete(providerId)
                }
            },
            onTestConnection = onClinicalAiTestConnection
        )
        SettingUiStyleRow(
            selectedStyle = state.uiStyle,
            onSelect = onUiStyleChange
        )
        SettingReadOnlyRow(
            title = stringResource(id = R.string.settings_resolved_url),
            value = state.resolvedNightscoutUrl.ifBlank { stringResource(id = R.string.placeholder_missing) }
        )
        SettingReadOnlyRow(
            title = stringResource(id = R.string.settings_local_ns_legacy_migration_fingerprint),
            value = stringResource(id = R.string.settings_local_ns_legacy_ca_sha256)
        )
        SettingToggleRow(
            title = stringResource(id = R.string.settings_local_ns_legacy_migration_ack),
            subtitle = stringResource(id = R.string.settings_local_ns_legacy_migration_body),
            infoText = stringResource(id = R.string.settings_local_ns_legacy_migration_info),
            value = state.localNightscoutLegacyMigrationAcknowledged,
            onToggle = onLocalNightscoutLegacyMigrationAcknowledged
        )
        SettingToggleRow(
            title = stringResource(id = R.string.settings_local_nightscout),
            subtitle = stringResource(id = R.string.settings_local_nightscout_subtitle),
            infoText = stringResource(id = R.string.settings_info_local_nightscout),
            value = state.localNightscoutEnabled,
            onToggle = onLocalNightscoutToggle
        )
        SettingReadOnlyRow(
            title = stringResource(id = R.string.settings_local_ns_runtime_state),
            value = buildString {
                append(state.localNightscoutRuntimeStatus)
                state.localNightscoutRuntimeReason?.let {
                    append(" (reason=").append(it).append(')')
                }
            }
        )
        SettingReadOnlyRow(
            title = stringResource(id = R.string.settings_local_ns_ca_fingerprint),
            value = state.localNightscoutCaFingerprint
                ?: stringResource(id = R.string.placeholder_missing)
        )
        OutlinedButton(
            onClick = onLocalNightscoutSecretCopy,
            enabled = state.localNightscoutLegacyMigrationAcknowledged,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null)
            Text(stringResource(id = R.string.settings_local_ns_copy_secret))
        }
        OutlinedButton(
            onClick = onLocalNightscoutCertificateInstall,
            enabled = state.localNightscoutLegacyMigrationAcknowledged,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Security, contentDescription = null)
            Text(stringResource(id = R.string.settings_local_ns_install_ca))
        }
        OutlinedButton(
            onClick = onLocalNightscoutCertificateExport,
            enabled = state.localNightscoutLegacyMigrationAcknowledged,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Download, contentDescription = null)
            Text(stringResource(id = R.string.settings_local_ns_export_ca))
        }
        OutlinedButton(
            onClick = onLocalNightscoutIdentityReset,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null)
            Text(stringResource(id = R.string.settings_local_ns_reset_identity))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            SourceStatusPill(
                title = stringResource(id = R.string.settings_source_broadcast),
                active = state.localBroadcastIngestEnabled,
                subtitle = if (state.localBroadcastIngestEnabled) {
                    stringResource(id = R.string.settings_source_status_active)
                } else {
                    stringResource(id = R.string.settings_source_status_inactive)
                },
                modifier = Modifier.weight(1f)
            )
            SourceStatusPill(
                title = stringResource(id = R.string.settings_source_nightscout),
                active = state.localNightscoutEnabled,
                subtitle = if (state.localNightscoutEnabled) {
                    if (state.isStale) {
                        stringResource(id = R.string.settings_source_status_stale)
                    } else {
                        stringResource(id = R.string.settings_source_status_active)
                    }
                } else {
                    stringResource(id = R.string.settings_source_status_inactive)
                },
                modifier = Modifier.weight(1f)
            )
        }
        SettingToggleRow(
            title = stringResource(id = R.string.settings_local_broadcast_ingest),
            subtitle = stringResource(id = R.string.settings_data_sources_broadcast_subtitle),
            infoText = stringResource(id = R.string.settings_info_local_broadcast_ingest),
            value = state.localBroadcastIngestEnabled,
            onToggle = onLocalBroadcastIngestToggle
        )
        SettingToggleRow(
            title = stringResource(id = R.string.settings_strict_sender_validation),
            subtitle = stringResource(id = R.string.settings_data_sources_sender_subtitle),
            infoText = stringResource(id = R.string.settings_info_strict_sender_validation),
            value = state.strictBroadcastSenderValidation,
            onToggle = onStrictSenderValidationToggle
        )
    }
}

@Composable
private fun ClinicalAiSettingsSection(
    state: ClinicalAiSettingsUiState,
    onProviderChange: (ClinicalAiProviderId) -> Unit,
    onModelChange: (String) -> Unit,
    onCustomModelChange: (String) -> Unit,
    onEndpointChange: (String) -> Unit,
    onProtocolChange: (OpenAiCompatibleProtocol) -> Unit,
    onAutomaticCauseAnalysisToggle: (Boolean) -> Unit,
    onCredentialReplace: (ClinicalAiProviderId, String) -> Unit,
    onCredentialDelete: (ClinicalAiProviderId) -> Unit,
    onTestConnection: () -> Unit
) {
    val labels = state.labels
    val compatible = state.selectedProvider == ClinicalAiProviderId.OPENAI_COMPATIBLE
    val connectionRunning =
        state.connectionTest.phase == ClinicalAiConnectionTestPhaseUi.RUNNING

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Text(
            text = labels.sectionTitle,
            style = MaterialTheme.typography.titleSmall
        )
        ClinicalAiChoiceRow(title = labels.provider) {
            state.providerOptions.forEach { option ->
                FilterChip(
                    selected = option.id == state.selectedProvider,
                    onClick = { onProviderChange(option.id) },
                    label = { Text(text = option.label) },
                    modifier = Modifier.testTag("clinical_ai_provider_${option.id.name}")
                )
            }
        }
        ClinicalAiChoiceRow(title = labels.model) {
            state.modelPresets.forEach { preset ->
                FilterChip(
                    selected = !state.customModelSelected &&
                        preset.id == state.effectiveConfig.modelId,
                    onClick = { onModelChange(preset.id) },
                    label = { Text(text = preset.label) },
                    modifier = Modifier.testTag("clinical_ai_model_${preset.id}")
                )
            }
            FilterChip(
                selected = state.customModelSelected,
                onClick = { onCustomModelChange(state.customModelDraft) },
                label = { Text(text = labels.customModel) },
                modifier = Modifier.testTag("clinical_ai_model_custom")
            )
        }
        if (state.customModelSelected) {
            val invalidModel =
                state.localValidationError == ClinicalAiSettingsValidationErrorUi.INVALID_MODEL
            OutlinedTextField(
                value = state.customModelDraft,
                onValueChange = onCustomModelChange,
                label = { Text(text = labels.customModelInput) },
                singleLine = true,
                isError = invalidModel,
                supportingText = if (invalidModel) {
                    {
                        Text(
                            text = labels.invalidModel,
                            modifier = Modifier.testTag("clinical_ai_model_error")
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("clinical_ai_custom_model")
            )
        }
        if (compatible) {
            val invalidEndpoint =
                state.localValidationError ==
                    ClinicalAiSettingsValidationErrorUi.INVALID_ENDPOINT
            OutlinedTextField(
                value = state.endpointDraft,
                onValueChange = onEndpointChange,
                label = { Text(text = labels.endpoint) },
                singleLine = true,
                isError = invalidEndpoint,
                supportingText = if (invalidEndpoint) {
                    {
                        Text(
                            text = labels.invalidEndpoint,
                            modifier = Modifier.testTag("clinical_ai_endpoint_error")
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("clinical_ai_endpoint")
            )
            Text(
                text = labels.protocol,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                state.protocolOptions.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option.id == state.selectedProtocol,
                        onClick = { onProtocolChange(option.id) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = state.protocolOptions.size
                        ),
                        label = { Text(text = option.label) },
                        modifier = Modifier.testTag(
                            "clinical_ai_protocol_${option.id.name}"
                        )
                    )
                }
            }
        }
        if (state.saveError) {
            Text(
                text = labels.configSaveFailed,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("clinical_ai_save_error")
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = labels.automaticCauseAnalysis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = state.automaticCauseAnalysisEnabled,
                onCheckedChange = onAutomaticCauseAnalysisToggle,
                modifier = Modifier.testTag("clinical_ai_automatic_cause_analysis")
            )
        }
        AiCredentialSettingsRow(
            providerId = state.selectedProvider,
            state = state.credential,
            labels = labels,
            onReplace = onCredentialReplace,
            onDelete = onCredentialDelete
        )
        OutlinedButton(
            onClick = onTestConnection,
            enabled = state.canTestConnection && !connectionRunning,
            modifier = Modifier.testTag("clinical_ai_test_connection")
        ) {
            Text(
                text = if (connectionRunning) {
                    labels.testingConnection
                } else {
                    labels.testConnection
                }
            )
        }
        ClinicalAiConnectionResult(
            state = state.connectionTest,
            labels = labels,
            providerLabel = state.providerOptions
                .firstOrNull { it.id == state.connectionTest.providerId }
                ?.label
                .orEmpty()
        )
        if (
            state.localValidationError ==
            ClinicalAiSettingsValidationErrorUi.INVALID_CONFIGURATION
        ) {
            Text(
                text = labels.invalidConfiguration,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("clinical_ai_configuration_error")
            )
        }
    }
}

@Composable
private fun ClinicalAiChoiceRow(
    title: String,
    content: @Composable () -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        content()
    }
}

@Composable
private fun ClinicalAiConnectionResult(
    state: ClinicalAiConnectionTestUiState,
    labels: ClinicalAiSettingsLabels,
    providerLabel: String
) {
    if (
        state.phase != ClinicalAiConnectionTestPhaseUi.SUCCESS &&
        state.phase != ClinicalAiConnectionTestPhaseUi.FAILURE
    ) {
        return
    }
    val successful = state.phase == ClinicalAiConnectionTestPhaseUi.SUCCESS
    val status = if (successful) {
        labels.connectionSuccess
    } else {
        labels.connectionFailure
    }
    Text(
        text = "$status · $providerLabel · ${state.modelId!!}",
        style = MaterialTheme.typography.bodySmall,
        color = if (successful) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
        modifier = Modifier.testTag("clinical_ai_connection_result")
    )
}

internal class CredentialDialogEphemeralState {
    var replaceDialogProvider by mutableStateOf<ClinicalAiProviderId?>(null)
        private set
    var credentialDraft by mutableStateOf("")
        private set

    fun open(providerId: ClinicalAiProviderId) {
        credentialDraft = ""
        replaceDialogProvider = providerId
    }

    fun updateDraft(value: String) {
        credentialDraft = value
    }

    fun clear() {
        credentialDraft = ""
        replaceDialogProvider = null
    }
}

@Composable
private fun AiCredentialSettingsRow(
    providerId: ClinicalAiProviderId,
    state: AiCredentialUiState,
    labels: ClinicalAiSettingsLabels,
    onReplace: (ClinicalAiProviderId, String) -> Unit,
    onDelete: (ClinicalAiProviderId) -> Unit
) {
    val credentialDialog =
        remember(providerId) { CredentialDialogEphemeralState() }
    var deleteDialogProvider by rememberSaveable(providerId) {
        mutableStateOf<ClinicalAiProviderId?>(null)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, credentialDialog) {
        val observer = LifecycleEventObserver { _, event ->
            if (
                event == Lifecycle.Event.ON_PAUSE ||
                event == Lifecycle.Event.ON_STOP
            ) {
                credentialDialog.clear()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            credentialDialog.clear()
        }
    }
    ProtectCredentialDialogTaskPreview(
        active = credentialDialog.replaceDialogProvider != null
    )

    val statusText = when {
        state.readError -> stringResource(id = R.string.settings_ai_credential_read_error)
        state.migrationError -> stringResource(id = R.string.settings_ai_credential_migration_error)
        state.busy -> stringResource(id = R.string.settings_ai_credential_checking)
        state.configured -> stringResource(id = R.string.settings_ai_credential_configured)
        else -> stringResource(id = R.string.settings_ai_credential_not_configured)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = labels.credential,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.resetRequired) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.testTag("ai_credential_status")
                )
            }
            if (!state.resetRequired) {
                TextButton(
                    enabled = !state.busy,
                    onClick = { credentialDialog.open(providerId) },
                    modifier = Modifier.testTag("ai_credential_replace")
                ) {
                    Text(
                        text = stringResource(
                            id = if (state.configured) {
                                R.string.settings_ai_credential_replace
                            } else {
                                R.string.settings_ai_credential_add
                            }
                        )
                    )
                }
            }
            if (state.configured || state.resetRequired) {
                val resetMode = state.resetRequired
                if (resetMode) {
                    TextButton(
                        enabled = !state.busy,
                        onClick = { deleteDialogProvider = providerId },
                        modifier = Modifier.testTag("ai_credential_reset")
                    ) {
                        Text(text = stringResource(id = R.string.settings_ai_credential_reset))
                    }
                } else {
                    IconButton(
                        enabled = !state.busy,
                        onClick = { deleteDialogProvider = providerId },
                        modifier = Modifier.testTag("ai_credential_delete")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(
                                id = R.string.settings_ai_credential_delete
                            )
                        )
                    }
                }
            }
        }
    }

    credentialDialog.replaceDialogProvider?.let { capturedProvider ->
        AlertDialog(
            onDismissRequest = credentialDialog::clear,
            title = {
                Text(
                    text = if (state.configured) {
                        labels.credentialReplaceTitle
                    } else {
                        labels.credentialAddTitle
                    }
                )
            },
            text = {
                OutlinedTextField(
                    value = credentialDialog.credentialDraft,
                    onValueChange = credentialDialog::updateDraft,
                    singleLine = true,
                    keyboardOptions = CredentialKeyboardOptions,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(text = labels.credential) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_credential_draft")
                )
            },
            confirmButton = {
                TextButton(
                    enabled = credentialDialog.credentialDraft.isNotBlank() && !state.busy,
                    onClick = {
                        val value = credentialDialog.credentialDraft
                        credentialDialog.clear()
                        onReplace(capturedProvider, value)
                    },
                    modifier = Modifier.testTag("ai_credential_save")
                ) {
                    Text(text = stringResource(id = R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = credentialDialog::clear,
                    modifier = Modifier.testTag("ai_credential_cancel")
                ) {
                    Text(text = stringResource(id = R.string.action_cancel))
                }
            }
        )
    }

    deleteDialogProvider?.let { capturedProvider ->
        val resetMode = state.resetRequired
        AlertDialog(
            onDismissRequest = { deleteDialogProvider = null },
            title = {
                Text(
                    text = if (resetMode) {
                        labels.credentialResetTitle
                    } else {
                        labels.credentialDeleteTitle
                    }
                )
            },
            text = {
                Text(
                    text = if (resetMode) {
                        labels.credentialResetMessage
                    } else {
                        labels.credentialDeleteMessage
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !state.busy,
                    onClick = {
                        deleteDialogProvider = null
                        onDelete(capturedProvider)
                    },
                    modifier = Modifier.testTag(
                        if (resetMode) {
                            "ai_credential_reset_confirm"
                        } else {
                            "ai_credential_delete_confirm"
                        }
                    )
                ) {
                    Text(
                        text = stringResource(
                            id = if (resetMode) {
                                R.string.settings_ai_credential_reset
                            } else {
                                R.string.settings_ai_credential_delete
                            }
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteDialogProvider = null },
                    modifier = Modifier.testTag(
                        if (resetMode) {
                            "ai_credential_reset_cancel"
                        } else {
                            "ai_credential_delete_cancel"
                        }
                    )
                ) {
                    Text(text = stringResource(id = R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingUiStyleRow(
    selectedStyle: String,
    onSelect: (String) -> Unit
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    val options = listOf(
        UiStyle.CLASSIC to stringResource(id = R.string.settings_ui_style_classic),
        UiStyle.DYNAMIC_GRADIENT to stringResource(id = R.string.settings_ui_style_dynamic_gradient),
        UiStyle.MIDNIGHT_GLASS to stringResource(id = R.string.settings_ui_style_midnight_glass)
    )
    Surface(
        shape = SettingsInfoShape,
        color = if (midnightGlass) Color(0xAA101D38) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            SettingTitleWithInfo(
                title = stringResource(id = R.string.settings_ui_style),
                subtitle = stringResource(id = R.string.settings_info_ui_style),
                titleStyle = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(id = R.string.settings_ui_style_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                options.forEach { (style, label) ->
                    FilterChip(
                        selected = selectedStyle == style.name,
                        onClick = { onSelect(style.name) },
                        colors = if (midnightGlass) settingsFilterChipColors() else FilterChipDefaults.filterChipColors(),
                        label = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun UamSettingsCard(
    state: SettingsUiState,
    onUamInferenceToggle: (Boolean) -> Unit,
    onUamBoostToggle: (Boolean) -> Unit,
    onUamExportUiModeChange: (String) -> Unit,
    onUamSnackConfigChange: (Int, Int, Int) -> Unit
) {
    SettingsSectionCard {
        SettingsSectionLabel(text = stringResource(id = R.string.section_settings_uam))

        SettingToggleRow(
            title = stringResource(id = R.string.settings_uam_inference),
            subtitle = stringResource(id = R.string.settings_uam_inference_subtitle),
            infoText = stringResource(id = R.string.settings_info_uam_inference),
            value = state.enableUamInference,
            onToggle = onUamInferenceToggle
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settingsUamExportMode"),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Text(
                text = stringResource(id = R.string.uam_mode_title),
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = stringResource(id = R.string.settings_uam_mode_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            UamExportModeSelector(
                mode = state.uamExport.mode,
                onModeSelected = onUamExportUiModeChange,
                enabled = state.uamExport.available
            )
            UamExportRuntimeSummary(control = state.uamExport)
        }
        AnimatedVisibility(visible = state.enableUamInference) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                SettingToggleRow(
                    title = stringResource(id = R.string.settings_uam_boost),
                    subtitle = stringResource(id = R.string.settings_uam_boost_subtitle),
                    infoText = stringResource(id = R.string.settings_info_uam_boost),
                    value = state.enableUamBoost,
                    onToggle = onUamBoostToggle
                )
                SettingIntStepperRow(
                    title = stringResource(id = R.string.settings_uam_min_snack),
                    subtitle = stringResource(id = R.string.settings_uam_min_snack_subtitle),
                    infoText = stringResource(id = R.string.settings_info_uam_min_snack),
                    value = state.uamMinSnackG,
                    min = 5,
                    max = state.uamMaxSnackG,
                    step = 1,
                    onValueChange = { next ->
                        onUamSnackConfigChange(next, state.uamMaxSnackG, state.uamSnackStepG)
                    }
                )
                SettingIntStepperRow(
                    title = stringResource(id = R.string.settings_uam_max_snack),
                    subtitle = stringResource(id = R.string.settings_uam_max_snack_subtitle),
                    infoText = stringResource(id = R.string.settings_info_uam_max_snack),
                    value = state.uamMaxSnackG,
                    min = state.uamMinSnackG,
                    max = 120,
                    step = 1,
                    onValueChange = { next ->
                        onUamSnackConfigChange(state.uamMinSnackG, next, state.uamSnackStepG)
                    }
                )
                SettingIntStepperRow(
                    title = stringResource(id = R.string.settings_uam_snack_step),
                    subtitle = stringResource(id = R.string.settings_uam_snack_step_subtitle),
                    infoText = stringResource(id = R.string.settings_info_uam_snack_step),
                    value = state.uamSnackStepG,
                    min = 1,
                    max = 20,
                    step = 1,
                    onValueChange = { next ->
                        onUamSnackConfigChange(state.uamMinSnackG, state.uamMaxSnackG, next)
                    }
                )
                Surface(
                    shape = SettingsInfoShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
                    ) {
                        Text(
                            text = stringResource(id = R.string.settings_uam_parameters_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(
                                id = R.string.settings_uam_parameters_values,
                                state.uamMinSnackG,
                                state.uamMaxSnackG,
                                state.uamSnackStepG
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UamAutoExportCapCard(
    state: SettingsUiState,
    onChange: (Boolean, Int) -> Unit
) {
    SettingsSectionCard {
        SettingsSectionLabel(text = stringResource(id = R.string.settings_uam_auto_export_cap_section))
        SettingToggleRow(
            title = stringResource(id = R.string.settings_uam_auto_export_cap),
            subtitle = stringResource(id = R.string.settings_uam_auto_export_cap_subtitle),
            infoText = stringResource(id = R.string.settings_info_uam_auto_export_cap),
            value = state.enableUamAutoExportCap,
            onToggle = { enabled -> onChange(enabled, state.uamAutoExportCapGrams) }
        )
        AnimatedVisibility(visible = state.enableUamAutoExportCap) {
            SettingIntStepperRow(
                title = stringResource(id = R.string.settings_uam_auto_export_cap_grams),
                subtitle = stringResource(id = R.string.settings_uam_auto_export_cap_grams_subtitle),
                infoText = stringResource(id = R.string.settings_info_uam_auto_export_cap),
                value = state.uamAutoExportCapGrams,
                min = 1,
                max = 15,
                step = 1,
                onValueChange = { grams -> onChange(true, grams) }
            )
        }
    }
}

@Composable
private fun AdaptiveSettingsCard(
    state: SettingsUiState,
    onAdaptiveControllerToggle: (Boolean) -> Unit,
    onEditBaseTargetSchedule: () -> Unit,
    onInsulinProfileSelect: (String) -> Unit,
    onSafetyBoundsChange: (Double, Double) -> Unit,
    onPostHypoThresholdChange: (Double) -> Unit,
    onPostHypoTargetChange: (Double) -> Unit
) {
    SettingsSectionCard {
        SettingsSectionLabel(text = stringResource(id = R.string.section_settings_adaptive))
        SettingToggleRow(
            title = stringResource(id = R.string.settings_adaptive_enabled),
            subtitle = stringResource(id = R.string.settings_adaptive_enabled_subtitle),
            infoText = stringResource(id = R.string.settings_info_adaptive_enabled),
            value = state.adaptiveControllerEnabled,
            onToggle = onAdaptiveControllerToggle
        )
        AnimatedVisibility(visible = state.adaptiveControllerEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                BaseTargetScheduleSettingRow(
                    state = state,
                    onClick = onEditBaseTargetSchedule
                )
                OptionChipsRow(
                    title = stringResource(id = R.string.settings_insulin_profile),
                    options = InsulinActionProfileId.values().map { it.name },
                    selected = state.insulinProfileId,
                    infoText = stringResource(id = R.string.settings_info_insulin_profile),
                    onSelect = onInsulinProfileSelect
                )
                SettingDoubleStepperRow(
                    title = stringResource(id = R.string.settings_adaptive_low_alert),
                    subtitle = stringResource(id = R.string.settings_adaptive_low_alert_subtitle),
                    infoText = stringResource(id = R.string.settings_info_safety_low_bound),
                    value = state.safetyMinTargetMmol,
                    min = 4.0,
                    max = (state.safetyMaxTargetMmol - 0.2).coerceAtLeast(4.0),
                    step = 0.1,
                    unitLabel = stringResource(id = R.string.unit_mmol_l),
                    onValueChange = { onSafetyBoundsChange(it, state.safetyMaxTargetMmol) }
                )
                SettingDoubleStepperRow(
                    title = stringResource(id = R.string.settings_adaptive_high_alert),
                    subtitle = stringResource(id = R.string.settings_adaptive_high_alert_subtitle),
                    infoText = stringResource(id = R.string.settings_info_safety_high_bound),
                    value = state.safetyMaxTargetMmol,
                    min = (state.safetyMinTargetMmol + 0.2).coerceAtMost(10.0),
                    max = 10.0,
                    step = 0.1,
                    unitLabel = stringResource(id = R.string.unit_mmol_l),
                    onValueChange = { onSafetyBoundsChange(state.safetyMinTargetMmol, it) }
                )
                SettingDoubleStepperRow(
                    title = stringResource(id = R.string.settings_post_hypo_threshold),
                    subtitle = stringResource(id = R.string.settings_post_hypo_threshold_subtitle),
                    infoText = stringResource(id = R.string.settings_info_post_hypo_threshold),
                    value = state.postHypoThresholdMmol,
                    min = 4.0,
                    max = 10.0,
                    step = 0.1,
                    unitLabel = stringResource(id = R.string.unit_mmol_l),
                    onValueChange = onPostHypoThresholdChange
                )
                SettingDoubleStepperRow(
                    title = stringResource(id = R.string.settings_post_hypo_target),
                    subtitle = stringResource(id = R.string.settings_post_hypo_target_subtitle),
                    infoText = stringResource(id = R.string.settings_info_post_hypo_target),
                    value = state.postHypoTargetMmol,
                    min = 4.0,
                    max = 10.0,
                    step = 0.1,
                    unitLabel = stringResource(id = R.string.unit_mmol_l),
                    onValueChange = onPostHypoTargetChange
                )
                Surface(
                    shape = SettingsInfoShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Text(
                            text = stringResource(id = R.string.settings_adaptive_safety_summary),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            AdaptiveSummaryTile(
                                label = stringResource(id = R.string.settings_adaptive_low_alert),
                                value = UiFormatters.formatMmol(state.safetyMinTargetMmol, 1),
                                modifier = Modifier.weight(1f)
                            )
                            AdaptiveSummaryTile(
                                label = stringResource(id = R.string.settings_adaptive_high_alert),
                                value = UiFormatters.formatMmol(state.safetyMaxTargetMmol, 1),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BaseTargetScheduleSettingRow(
    state: SettingsUiState,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingTitleWithInfo(
            title = stringResource(id = R.string.settings_base_target),
            subtitle = stringResource(id = R.string.settings_info_base_target),
            titleStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = buildAutoStatusText(
                    effectiveTargetMmol = state.effectiveBaseTargetMmol,
                    autoDeltaMmol = state.baseTargetAutoDeltaMmol,
                    autoState = state.baseTargetAutoState
                ),
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = stringResource(
                    id = R.string.base_target_schedule_summary,
                    UiFormatters.formatMmol(state.baseTargetSchedule.defaultTargetMmol, 1),
                    state.baseTargetSchedule.intervals.size
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = stringResource(id = R.string.overview_target_edit),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun GlucoseAlertsCard(
    state: SettingsUiState,
    onSoftAlertEnabledToggle: (Boolean) -> Unit,
    onWatch60AlertEnabledToggle: (Boolean) -> Unit,
    onWarning30AlertEnabledToggle: (Boolean) -> Unit,
    onSoftHighAlertEnabledToggle: (Boolean) -> Unit,
    onCritical5AlertEnabledToggle: (Boolean) -> Unit,
    onLowNowAlertEnabledToggle: (Boolean) -> Unit,
    onSoftAlertLowChange: (Double) -> Unit,
    onSoftAlertHighChange: (Double) -> Unit,
    onUrgentLowChange: (Double) -> Unit,
    onSoftAlertAudioSettingsChange: (Int, Int) -> Unit,
    onCriticalAlertAudio1SettingsChange: (Int, Int) -> Unit,
    onCriticalAlertAudio2SettingsChange: (Int, Int) -> Unit,
    onReplaceGlucoseAlertAudio: (String, String) -> Unit,
    onPreviewGlucoseAlertAudio: (String) -> Unit,
    onStopPreviewGlucoseAlertAudio: () -> Unit,
    onResetGlucoseAlertAudio: () -> Unit
) {
    val context = LocalContext.current
    var replaceSlot by remember { mutableStateOf<String?>(null) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val slot = replaceSlot
        replaceSlot = null
        if (uri != null && slot != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            onReplaceGlucoseAlertAudio(slot, uri.toString())
        }
    }
    SettingsSectionCard {
        SettingsSectionLabel(text = stringResource(id = R.string.section_settings_glucose_alerts))
        SettingToggleRow(
            title = stringResource(id = R.string.settings_glucose_alerts_enabled),
            subtitle = stringResource(id = R.string.settings_glucose_alerts_enabled_subtitle),
            infoText = stringResource(id = R.string.settings_info_glucose_alerts_enabled),
            value = state.softAlertEnabled,
            onToggle = onSoftAlertEnabledToggle
        )
        AnimatedVisibility(visible = state.softAlertEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Surface(
                    shape = SettingsInfoShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
                    ) {
                        Text(
                            text = stringResource(id = R.string.settings_glucose_alerts_stage_section),
                            style = MaterialTheme.typography.labelLarge
                        )
                        SettingToggleRow(
                            title = stringResource(id = R.string.settings_glucose_alerts_watch_60),
                            subtitle = stringResource(id = R.string.settings_glucose_alerts_watch_60_subtitle),
                            infoText = stringResource(id = R.string.settings_info_glucose_alerts_watch_60),
                            value = state.watch60AlertEnabled,
                            onToggle = onWatch60AlertEnabledToggle
                        )
                        SettingToggleRow(
                            title = stringResource(id = R.string.settings_glucose_alerts_warning_30),
                            subtitle = stringResource(id = R.string.settings_glucose_alerts_warning_30_subtitle),
                            infoText = stringResource(id = R.string.settings_info_glucose_alerts_warning_30),
                            value = state.warning30AlertEnabled,
                            onToggle = onWarning30AlertEnabledToggle
                        )
                        SettingToggleRow(
                            title = stringResource(id = R.string.settings_glucose_alerts_soft_high_stage),
                            subtitle = stringResource(id = R.string.settings_glucose_alerts_soft_high_stage_subtitle),
                            infoText = stringResource(id = R.string.settings_info_glucose_alerts_soft_high_stage),
                            value = state.softHighAlertEnabled,
                            onToggle = onSoftHighAlertEnabledToggle
                        )
                        SettingToggleRow(
                            title = stringResource(id = R.string.settings_glucose_alerts_critical_5),
                            subtitle = stringResource(id = R.string.settings_glucose_alerts_critical_5_subtitle),
                            infoText = stringResource(id = R.string.settings_info_glucose_alerts_critical_5),
                            value = state.critical5AlertEnabled,
                            onToggle = onCritical5AlertEnabledToggle
                        )
                        SettingToggleRow(
                            title = stringResource(id = R.string.settings_glucose_alerts_low_now_stage),
                            subtitle = stringResource(id = R.string.settings_glucose_alerts_low_now_stage_subtitle),
                            infoText = stringResource(id = R.string.settings_info_glucose_alerts_low_now_stage),
                            value = state.lowNowAlertEnabled,
                            onToggle = onLowNowAlertEnabledToggle
                        )
                    }
                }
                SettingDoubleStepperRow(
                    title = stringResource(id = R.string.settings_glucose_alerts_low),
                    subtitle = stringResource(id = R.string.settings_glucose_alerts_low_subtitle),
                    infoText = stringResource(id = R.string.settings_info_glucose_alerts_low),
                    value = state.softAlertLowMmol,
                    min = 3.6,
                    max = minOf(6.0, state.softAlertHighMmol - 0.1),
                    step = 0.1,
                    unitLabel = stringResource(id = R.string.unit_mmol_l),
                    onValueChange = onSoftAlertLowChange
                )
                SettingDoubleStepperRow(
                    title = stringResource(id = R.string.settings_glucose_alerts_high),
                    subtitle = stringResource(id = R.string.settings_glucose_alerts_high_subtitle),
                    infoText = stringResource(id = R.string.settings_info_glucose_alerts_high),
                    value = state.softAlertHighMmol,
                    min = maxOf(7.0, state.softAlertLowMmol + 0.1),
                    max = 13.9,
                    step = 0.1,
                    unitLabel = stringResource(id = R.string.unit_mmol_l),
                    onValueChange = onSoftAlertHighChange
                )
                SettingDoubleStepperRow(
                    title = stringResource(id = R.string.settings_glucose_alerts_urgent_low),
                    subtitle = stringResource(id = R.string.settings_glucose_alerts_urgent_low_subtitle),
                    infoText = stringResource(id = R.string.settings_info_glucose_alerts_urgent_low),
                    value = state.urgentLowMmol,
                    min = 3.0,
                    max = minOf(4.4, state.softAlertLowMmol),
                    step = 0.1,
                    unitLabel = stringResource(id = R.string.unit_mmol_l),
                    onValueChange = onUrgentLowChange
                )
                Surface(
                    shape = SettingsInfoShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
                    ) {
                        Text(
                            text = stringResource(id = R.string.settings_glucose_alerts_soft_repeat_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(id = R.string.settings_glucose_alerts_strong_repeat_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    shape = SettingsInfoShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Text(
                            text = stringResource(id = R.string.settings_glucose_alerts_audio_section),
                            style = MaterialTheme.typography.labelLarge
                        )
                        AlertAudioClipEditor(
                            title = stringResource(id = R.string.settings_glucose_alerts_audio_soft),
                            clipLabel = state.softAlertClipLabel,
                            startSeconds = state.softAlertAudioStartSeconds,
                            durationSeconds = state.softAlertAudioDurationSeconds,
                            durationRangeSeconds = 1..5,
                            valid = state.softAlertAudioValid,
                            onStartSecondsChange = { next ->
                                onSoftAlertAudioSettingsChange(next, state.softAlertAudioDurationSeconds)
                            },
                            onDurationSecondsChange = { next ->
                                onSoftAlertAudioSettingsChange(state.softAlertAudioStartSeconds, next)
                            },
                            previewing = state.softAlertAudioPreviewing,
                            onReplace = {
                                replaceSlot = "SOFT"
                                audioPicker.launch(arrayOf("audio/*"))
                            },
                            onPreview = { onPreviewGlucoseAlertAudio("SOFT") },
                            onStopPreview = onStopPreviewGlucoseAlertAudio
                        )
                        AlertAudioClipEditor(
                            title = stringResource(id = R.string.settings_glucose_alerts_audio_critical_1),
                            clipLabel = state.criticalAlertClip1Label,
                            startSeconds = state.criticalAlertAudio1StartSeconds,
                            durationSeconds = state.criticalAlertAudio1DurationSeconds,
                            valid = state.criticalAlertAudio1Valid,
                            onStartSecondsChange = { next ->
                                onCriticalAlertAudio1SettingsChange(next, state.criticalAlertAudio1DurationSeconds)
                            },
                            onDurationSecondsChange = { next ->
                                onCriticalAlertAudio1SettingsChange(state.criticalAlertAudio1StartSeconds, next)
                            },
                            previewing = state.criticalAlertAudio1Previewing,
                            onReplace = {
                                replaceSlot = "CRITICAL_PRIMARY"
                                audioPicker.launch(arrayOf("audio/*"))
                            },
                            onPreview = { onPreviewGlucoseAlertAudio("CRITICAL_PRIMARY") },
                            onStopPreview = onStopPreviewGlucoseAlertAudio
                        )
                        AlertAudioClipEditor(
                            title = stringResource(id = R.string.settings_glucose_alerts_audio_critical_2),
                            clipLabel = state.criticalAlertClip2Label,
                            startSeconds = state.criticalAlertAudio2StartSeconds,
                            durationSeconds = state.criticalAlertAudio2DurationSeconds,
                            valid = state.criticalAlertAudio2Valid,
                            onStartSecondsChange = { next ->
                                onCriticalAlertAudio2SettingsChange(next, state.criticalAlertAudio2DurationSeconds)
                            },
                            onDurationSecondsChange = { next ->
                                onCriticalAlertAudio2SettingsChange(state.criticalAlertAudio2StartSeconds, next)
                            },
                            previewing = state.criticalAlertAudio2Previewing,
                            onReplace = {
                                replaceSlot = "CRITICAL_SECONDARY"
                                audioPicker.launch(arrayOf("audio/*"))
                            },
                            onPreview = { onPreviewGlucoseAlertAudio("CRITICAL_SECONDARY") },
                            onStopPreview = onStopPreviewGlucoseAlertAudio
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            OutlinedButton(onClick = onResetGlucoseAlertAudio) {
                                Text(text = stringResource(id = R.string.settings_glucose_alerts_audio_reset))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertAudioClipEditor(
    title: String,
    clipLabel: String,
    startSeconds: Int,
    durationSeconds: Int,
    valid: Boolean,
    previewing: Boolean,
    onStartSecondsChange: (Int) -> Unit,
    onDurationSecondsChange: (Int) -> Unit,
    onReplace: () -> Unit,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
    durationRangeSeconds: IntRange = 15..30
) {
    Surface(
        shape = SettingsInfoShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Text(
                text = "$title · $clipLabel",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = if (valid) {
                    stringResource(id = R.string.settings_glucose_alerts_audio_valid)
                } else {
                    stringResource(id = R.string.settings_glucose_alerts_audio_fallback)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AnimatedVisibility(visible = previewing) {
                Text(
                    text = stringResource(id = R.string.settings_glucose_alerts_audio_previewing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            SettingIntStepperRow(
                title = stringResource(id = R.string.settings_glucose_alerts_audio_start),
                subtitle = clipLabel,
                value = startSeconds,
                min = 0,
                max = 180,
                step = 1,
                onValueChange = onStartSecondsChange
            )
            SettingIntStepperRow(
                title = stringResource(id = R.string.settings_glucose_alerts_audio_duration),
                subtitle = clipLabel,
                value = durationSeconds,
                min = durationRangeSeconds.first,
                max = durationRangeSeconds.last,
                step = 1,
                onValueChange = onDurationSecondsChange
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End)
            ) {
                OutlinedButton(onClick = onReplace) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null
                    )
                    Text(text = stringResource(id = R.string.settings_glucose_alerts_audio_replace))
                }
                OutlinedButton(onClick = onPreview) {
                    Text(text = stringResource(id = R.string.settings_glucose_alerts_audio_preview))
                }
                if (previewing) {
                    Button(onClick = onStopPreview) {
                        Text(text = stringResource(id = R.string.settings_glucose_alerts_audio_stop_preview))
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugSettingsCard(
    proModeEnabled: Boolean,
    verboseLogsEnabled: Boolean,
    onProModeToggle: (Boolean) -> Unit,
    onVerboseLogsToggle: (Boolean) -> Unit
) {
    SettingsSectionCard {
        SettingsSectionLabel(text = stringResource(id = R.string.section_settings_debug))

        SettingToggleRow(
            title = stringResource(id = R.string.settings_pro_mode),
            subtitle = stringResource(id = R.string.settings_pro_mode_subtitle),
            infoText = stringResource(id = R.string.settings_info_pro_mode),
            value = proModeEnabled,
            onToggle = onProModeToggle
        )
        SettingToggleRow(
            title = stringResource(id = R.string.settings_verbose_logs),
            subtitle = stringResource(id = R.string.settings_verbose_logs_subtitle),
            infoText = stringResource(id = R.string.settings_info_verbose_logs),
            value = verboseLogsEnabled,
            onToggle = onVerboseLogsToggle
        )
    }
}

@Composable
private fun PrivacyCard(
    retentionDays: Int,
    onRetentionDaysChange: (Int) -> Unit
) {
    SettingsSectionCard {
        SettingsSectionLabel(text = stringResource(id = R.string.section_settings_privacy))
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_retention_days),
            subtitle = stringResource(id = R.string.settings_retention_days_subtitle),
            infoText = stringResource(id = R.string.settings_info_retention_days),
            value = retentionDays,
            min = 30,
            max = 730,
            step = 5,
            onValueChange = onRetentionDaysChange
        )
    }
}

@Composable
private fun DisclaimerCard(text: String) {
    SettingsSectionCard {
        Surface(
            shape = SettingsInfoShape,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Text(
                        text = stringResource(id = R.string.settings_disclaimer_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun AppInfoCard() {
    SettingsSectionCard {
        Surface(
            shape = SettingsInfoShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
                ) {
                    Text(
                        text = stringResource(id = R.string.app_name),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(id = R.string.settings_app_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingReadOnlyRow(
    title: String,
    value: String,
    infoText: String = ""
) {
    Surface(
        shape = SettingsInfoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingTitleWithInfo(
                title = title,
                subtitle = infoText,
                titleStyle = MaterialTheme.typography.bodySmall,
                titleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = Spacing.xs)
            )
        }
    }
}

@Composable
private fun PhysioTagJournalRow(
    item: PhysioTagJournalItemUi,
    onCloseTag: (String, Long) -> Unit
) {
    val nowTs = System.currentTimeMillis()
    val remainingMinutes = ((item.tsEnd - nowTs).coerceAtLeast(0L)) / 60_000L
    Surface(
        shape = SettingsInfoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "${item.tagType} ${UiFormatters.formatPercent(item.severity, 0)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = if (item.isActive) {
                        stringResource(
                            id = R.string.settings_isfcr_tag_active_remaining,
                            UiFormatters.formatMinutes(remainingMinutes)
                        )
                    } else {
                        stringResource(id = R.string.settings_isfcr_tag_inactive_ended)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${UiFormatters.formatTimestamp(item.tsStart)} - ${UiFormatters.formatTimestamp(item.tsEnd)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (item.isActive) {
                OutlinedButton(onClick = { onCloseTag(item.id, item.revision) }) {
                    Text(text = stringResource(id = R.string.settings_isfcr_tag_close))
                }
            }
        }
    }
}

@Composable
internal fun ProtectCredentialDialogTaskPreview(active: Boolean) {
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(view, lifecycleOwner, active) {
        val activity = view.context.findActivity()
        val windowLifecycleOwner = activity as? LifecycleOwner ?: lifecycleOwner
        val lease = if (active && activity != null) {
            WindowSecureFlagController.acquire(
                window = activity.window,
                lifecycleOwner = windowLifecycleOwner,
                frameView = view
            )
        } else {
            null
        }
        onDispose {
            lease?.let(WindowSecureFlagController::releaseAfterSanitizedFrame)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun SettingTextInputRow(
    title: String,
    subtitle: String,
    infoText: String = subtitle,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onApply: () -> Unit,
    secret: Boolean = false,
    testTag: String? = null
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    var secretVisible by rememberSaveable(title) { mutableStateOf(false) }
    Surface(
        shape = SettingsInfoShape,
        color = if (midnightGlass) Color(0xAA101D38) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            SettingTitleWithInfo(
                title = title,
                subtitle = infoText,
                titleStyle = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(text = placeholder) },
                singleLine = true,
                visualTransformation = if (secret && !secretVisible) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                trailingIcon = if (secret) {
                    {
                        IconButton(onClick = { secretVisible = !secretVisible }) {
                            Icon(
                                imageVector = if (secretVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (secretVisible) {
                                    stringResource(id = R.string.action_hide)
                                } else {
                                    stringResource(id = R.string.action_show)
                                }
                            )
                        }
                    }
                } else {
                    null
                },
                modifier = if (testTag == null) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .testTag(testTag)
                },
                colors = if (midnightGlass) {
                    OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFF8FAFC),
                        unfocusedTextColor = Color(0xFFF8FAFC),
                        focusedBorderColor = Color(0xFF4A82BF),
                        unfocusedBorderColor = Color(0x33FFFFFF),
                        focusedContainerColor = Color(0x33182947),
                        unfocusedContainerColor = Color(0x33182947),
                        cursorColor = Color(0xFF8DB6FF),
                        focusedLabelColor = Color(0xFF8DB6FF),
                        unfocusedLabelColor = Color(0xFFB5C0D8),
                        focusedPlaceholderColor = Color(0x8093A5C3),
                        unfocusedPlaceholderColor = Color(0x8093A5C3)
                    )
                } else {
                    OutlinedTextFieldDefaults.colors()
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onApply,
                    modifier = if (testTag == null) {
                        Modifier
                    } else {
                        Modifier.testTag("${testTag}_save")
                    }
                ) {
                    Text(text = stringResource(id = R.string.action_save))
                }
            }
        }
    }
}

@Composable
private fun OptionChipsRow(
    title: String,
    options: List<String>,
    selected: String,
    infoText: String = "",
    onSelect: (String) -> Unit
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    Surface(
        shape = SettingsInfoShape,
        color = if (midnightGlass) Color(0xAA101D38) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            SettingTitleWithInfo(
                title = title,
                subtitle = infoText,
                titleStyle = MaterialTheme.typography.bodyMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                options.forEach { option ->
                    FilterChip(
                        selected = selected == option,
                        onClick = { onSelect(option) },
                        colors = if (midnightGlass) settingsFilterChipColors() else FilterChipDefaults.filterChipColors(),
                        label = {
                            Text(
                                text = option,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingIntStepperRow(
    title: String,
    subtitle: String,
    infoText: String = subtitle,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onValueChange: (Int) -> Unit
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    Surface(
        shape = SettingsInfoShape,
        color = if (midnightGlass) Color(0xAA101D38) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SettingTitleWithInfo(
                    title = title,
                    subtitle = infoText,
                    titleStyle = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = { onValueChange((value - step).coerceIn(min, max)) },
                enabled = value > min
            ) {
                Text(text = "-")
            }
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            OutlinedButton(
                onClick = { onValueChange((value + step).coerceIn(min, max)) },
                enabled = value < max
            ) {
                Text(text = "+")
            }
        }
    }
}

@Composable
private fun SettingDoubleStepperRow(
    title: String,
    subtitle: String,
    infoText: String = subtitle,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    unitLabel: String,
    onValueChange: (Double) -> Unit
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    fun normalized(next: Double): Double {
        return (next.coerceIn(min, max) * 10.0).roundToInt() / 10.0
    }
    Surface(
        shape = SettingsInfoShape,
        color = if (midnightGlass) Color(0xAA101D38) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SettingTitleWithInfo(
                    title = title,
                    subtitle = infoText,
                    titleStyle = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = { onValueChange(normalized(value - step)) },
                enabled = value > min
            ) {
                Text(text = "-")
            }
            Text(
                text = "${UiFormatters.formatMmol(value, 2)} $unitLabel",
                style = MaterialTheme.typography.titleMedium,
                color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            OutlinedButton(
                onClick = { onValueChange(normalized(value + step)) },
                enabled = value < max
            ) {
                Text(text = "+")
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    infoText: String = subtitle,
    value: Boolean,
    onToggle: (Boolean) -> Unit,
    testTag: String? = null
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    Surface(
        shape = SettingsInfoShape,
        color = if (midnightGlass) Color(0xAA101D38) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(!value) }
                .animateContentSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SettingTitleWithInfo(
                    title = title,
                    subtitle = infoText,
                    titleStyle = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = value,
                onCheckedChange = onToggle,
                modifier = testTag?.let { Modifier.testTag(it) } ?: Modifier,
                colors = if (midnightGlass) {
                    SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFFF8FAFC),
                        checkedTrackColor = Color(0xFF1D4ED8),
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Color(0xFFDCEBFF),
                        uncheckedTrackColor = Color(0xFF334155),
                        uncheckedBorderColor = Color.Transparent
                    )
                } else {
                    SwitchDefaults.colors()
                }
            )
        }
    }
}

@Composable
private fun SettingTitleWithInfo(
    title: String,
    subtitle: String,
    titleStyle: androidx.compose.ui.text.TextStyle,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    var showInfoDialog by rememberSaveable(title, subtitle) { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = titleStyle,
            color = titleColor,
            modifier = Modifier.weight(1f)
        )
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = if (midnightGlass) Color(0x221D4ED8) else Color.Transparent
        ) {
            IconButton(
                onClick = { showInfoDialog = true }
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(
                        id = R.string.settings_info_button_cd,
                        title
                    ),
                    tint = if (midnightGlass) Color(0xFF5CA9FF) else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(text = title) },
            text = {
                Text(
                    text = subtitle.ifBlank {
                        stringResource(id = R.string.settings_info_dialog_fallback)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showInfoDialog = false }
                ) {
                    Text(text = stringResource(id = R.string.action_close))
                }
            }
        )
    }
}

@Composable
private fun SourceStatusPill(
    title: String,
    active: Boolean,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    val tone = when {
        midnightGlass && active -> Color(0xFF9FFFB0)
        midnightGlass -> Color(0xFFFFA7AE)
        active -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    val bg = when {
        midnightGlass && active -> Color(0x55103A35)
        midnightGlass -> Color(0x665A1E25)
        active -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    Surface(
        modifier = modifier,
        shape = SettingsInfoShape,
        color = bg,
        border = BorderStroke(1.dp, tone.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (active) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = tone
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = tone
            )
        }
    }
}

@Composable
private fun AdaptiveSummaryTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    Surface(
        modifier = modifier,
        shape = SettingsInfoShape,
        color = if (midnightGlass) Color(0xFF10275A) else MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x334A82BF) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (midnightGlass) Color(0xFFDCEBFF) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SettingsSectionCard(
    content: @Composable ColumnScope.() -> Unit
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SettingsSectionShape,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x263A4A66) else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = if (midnightGlass) Color(0xF51D2D49) else MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = AppElevation.level1)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            content = content
        )
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
        color = if (midnightGlass) Color(0xFFD0D7E8) else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun settingsFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Color(0x221D4ED8),
    selectedLabelColor = Color(0xFF8DB6FF),
    selectedLeadingIconColor = Color(0xFF8DB6FF),
    containerColor = Color(0xAA101D38),
    labelColor = Color(0xFFB5C0D8),
    iconColor = Color(0xFFB5C0D8)
)

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    AapsCopilotTheme {
        SettingsScreen(
            state = SettingsUiState(
                loadState = ScreenLoadState.READY,
                isStale = false,
                baseTarget = 5.5,
                nightscoutUrl = "https://example.ns",
                aiApiUrl = "https://api.openai.com/v1",
                aiCredential = AiCredentialUiState(configured = true, busy = false),
                uiStyle = UiStyle.CLASSIC.name,
                resolvedNightscoutUrl = "https://127.0.0.1:17582",
                insulinProfileId = "NOVORAPID",
                localNightscoutEnabled = true,
                localBroadcastIngestEnabled = true,
                strictBroadcastSenderValidation = false,
                enableUamInference = true,
                enableUamBoost = true,
                uamExport = UamExportControlUi(mode = "OBSERVE"),
                uamMinSnackG = 15,
                uamMaxSnackG = 60,
                uamSnackStepG = 5,
                isfCrShadowMode = true,
                isfCrConfidenceThreshold = 0.45,
                isfCrUseActivity = true,
                isfCrUseManualTags = true,
                isfCrMinIsfEvidencePerHour = 2,
                isfCrMinCrEvidencePerHour = 2,
                isfCrCrMaxGapMinutes = 30,
                isfCrCrMaxSensorBlockedRatePct = 25.0,
                isfCrCrMaxUamAmbiguityRatePct = 60.0,
                isfCrSnapshotRetentionDays = 365,
                isfCrEvidenceRetentionDays = 730,
                isfCrAutoActivationEnabled = false,
                isfCrAutoActivationLookbackHours = 24,
                isfCrAutoActivationMinSamples = 72,
                isfCrAutoActivationMinMeanConfidence = 0.65,
                isfCrAutoActivationMaxMeanAbsIsfDeltaPct = 25.0,
                isfCrAutoActivationMaxMeanAbsCrDeltaPct = 25.0,
                isfCrAutoActivationMinSensorQualityScore = 0.46,
                isfCrAutoActivationMinSensorFactor = 0.90,
                isfCrAutoActivationMaxWearConfidencePenalty = 0.12,
                isfCrAutoActivationMaxSensorAgeHighRatePct = 70.0,
                isfCrAutoActivationMaxSuspectFalseLowRatePct = 35.0,
                isfCrAutoActivationMinDayTypeRatio = 0.30,
                isfCrAutoActivationMaxDayTypeSparseRatePct = 75.0,
                isfCrAutoActivationRequireDailyQualityGate = true,
                isfCrAutoActivationDailyRiskBlockLevel = 3,
                isfCrAutoActivationMinDailyMatchedSamples = 120,
                isfCrAutoActivationMaxDailyMae30Mmol = 0.90,
                isfCrAutoActivationMaxDailyMae60Mmol = 1.40,
                isfCrAutoActivationMaxHypoRatePct = 6.0,
                isfCrAutoActivationMinDailyCiCoverage30Pct = 55.0,
                isfCrAutoActivationMinDailyCiCoverage60Pct = 55.0,
                isfCrAutoActivationMaxDailyCiWidth30Mmol = 1.80,
                isfCrAutoActivationMaxDailyCiWidth60Mmol = 2.60,
                circadianPatternsEnabled = true,
                circadianStableLookbackDays = 14,
                circadianRecencyLookbackDays = 5,
                circadianUseWeekendSplit = true,
                circadianUseReplayResidualBias = true,
                circadianForecastWeight30 = 0.25,
                circadianForecastWeight60 = 0.35,
                sensorLagCorrectionMode = "OFF",
                softAlertEnabled = true,
                watch60AlertEnabled = true,
                warning30AlertEnabled = true,
                softHighAlertEnabled = true,
                critical5AlertEnabled = true,
                lowNowAlertEnabled = true,
                softAlertLowMmol = 4.4,
                softAlertHighMmol = 10.0,
                urgentLowMmol = 3.9,
                softAlertClipLabel = "Elk Creek.mp3",
                criticalAlertClip1Label = "Night Waltz.mp3",
                criticalAlertClip2Label = "Snowbirds.mp3",
                softAlertAudioStartSeconds = 32,
                softAlertAudioDurationSeconds = 2,
                criticalAlertAudio1StartSeconds = 42,
                criticalAlertAudio1DurationSeconds = 20,
                criticalAlertAudio2StartSeconds = 36,
                criticalAlertAudio2DurationSeconds = 20,
                softAlertAudioValid = true,
                criticalAlertAudio1Valid = true,
                criticalAlertAudio2Valid = true,
                softAlertAudioPreviewing = false,
                criticalAlertAudio1Previewing = false,
                criticalAlertAudio2Previewing = false,
                isfCrActiveTags = listOf("stress"),
                adaptiveControllerEnabled = true,
                safetyMinTargetMmol = 4.0,
                safetyMaxTargetMmol = 10.0,
                postHypoThresholdMmol = 4.0,
                postHypoTargetMmol = 4.4,
                proModeEnabled = false,
                verboseLogsEnabled = false,
                retentionDays = 30,
                warningText = "Not a medical device. Verify all therapy decisions manually."
            ),
            onSoftAlertEnabledToggle = {},
            onSoftAlertLowChange = {},
            onSoftAlertHighChange = {},
            onUrgentLowChange = {},
            onSoftAlertAudioSettingsChange = { _, _ -> },
            onCriticalAlertAudio1SettingsChange = { _, _ -> },
            onCriticalAlertAudio2SettingsChange = { _, _ -> },
            onPreviewGlucoseAlertAudio = {},
            onStopPreviewGlucoseAlertAudio = {},
            onResetGlucoseAlertAudio = {},
            onVerboseLogsToggle = {},
            onProModeToggle = {}
        )
    }
}
