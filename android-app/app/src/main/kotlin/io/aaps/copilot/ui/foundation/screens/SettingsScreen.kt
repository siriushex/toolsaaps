package io.aaps.copilot.ui.foundation.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aaps.copilot.BuildConfig
import io.aaps.copilot.R
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.format.UiFormatters
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import kotlin.math.roundToInt

private val SettingsSectionShape = RoundedCornerShape(18.dp)
private val SettingsInfoShape = RoundedCornerShape(12.dp)

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onVerboseLogsToggle: (Boolean) -> Unit,
    onProModeToggle: (Boolean) -> Unit,
    onNightscoutUrlSave: (String) -> Unit = {},
    onBaseTargetChange: (Double) -> Unit = {},
    onInsulinProfileSelect: (String) -> Unit = {},
    onLocalNightscoutToggle: (Boolean) -> Unit = {},
    onLocalBroadcastIngestToggle: (Boolean) -> Unit = {},
    onStrictSenderValidationToggle: (Boolean) -> Unit = {},
    onUamInferenceToggle: (Boolean) -> Unit = {},
    onUamBoostToggle: (Boolean) -> Unit = {},
    onUamExportToggle: (Boolean) -> Unit = {},
    onUamExportModeChange: (String) -> Unit = {},
    onUamSnackConfigChange: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onUamDryRunToggle: (Boolean) -> Unit = {},
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
    onClosePhysioTag: (String) -> Unit = {},
    onClearPhysioTags: () -> Unit = {},
    onAdaptiveControllerToggle: (Boolean) -> Unit = {},
    onSafetyBoundsChange: (Double, Double) -> Unit = { _, _ -> },
    onPostHypoThresholdChange: (Double) -> Unit = {},
    onPostHypoTargetChange: (Double) -> Unit = {},
    onRetentionDaysChange: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
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
                DataSourcesCard(
                    state = state,
                    onNightscoutUrlSave = onNightscoutUrlSave,
                    onLocalNightscoutToggle = onLocalNightscoutToggle,
                    onLocalBroadcastIngestToggle = onLocalBroadcastIngestToggle,
                    onStrictSenderValidationToggle = onStrictSenderValidationToggle
                )
            }
            item {
                UamSettingsCard(
                    state = state,
                    onUamInferenceToggle = onUamInferenceToggle,
                    onUamBoostToggle = onUamBoostToggle,
                    onUamExportToggle = onUamExportToggle,
                    onUamExportModeChange = onUamExportModeChange,
                    onUamSnackConfigChange = onUamSnackConfigChange,
                    onUamDryRunToggle = onUamDryRunToggle
                )
            }
            item {
                IsfCrSettingsCard(
                    state = state,
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
                AdaptiveSettingsCard(
                    state = state,
                    onAdaptiveControllerToggle = onAdaptiveControllerToggle,
                    onBaseTargetChange = onBaseTargetChange,
                    onInsulinProfileSelect = onInsulinProfileSelect,
                    onSafetyBoundsChange = onSafetyBoundsChange,
                    onPostHypoThresholdChange = onPostHypoThresholdChange,
                    onPostHypoTargetChange = onPostHypoTargetChange
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
            item {
                DisclaimerCard(text = state.warningText)
            }
            item {
                AppInfoCard()
            }
        }
    }
}

@Composable
private fun IsfCrSettingsCard(
    state: SettingsUiState,
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
    onClosePhysioTag: (String) -> Unit,
    onClearPhysioTags: () -> Unit
) {
    var quickTagSeverityPercent by rememberSaveable { mutableStateOf(70) }
    var quickTagDurationHours by rememberSaveable { mutableStateOf(6) }
    SettingsSectionCard {
        SettingsSectionLabel(text = stringResource(id = R.string.section_settings_isfcr))

        SettingToggleRow(
            title = stringResource(id = R.string.settings_isfcr_shadow_mode),
            subtitle = stringResource(id = R.string.settings_isfcr_shadow_mode_subtitle),
            value = state.isfCrShadowMode,
            onToggle = onShadowModeToggle
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_confidence_threshold),
            subtitle = stringResource(id = R.string.settings_isfcr_confidence_threshold_subtitle),
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
            value = state.isfCrUseActivity,
            onToggle = onUseActivityToggle
        )
        SettingToggleRow(
            title = stringResource(id = R.string.settings_isfcr_use_manual_tags),
            subtitle = stringResource(id = R.string.settings_isfcr_use_manual_tags_subtitle),
            value = state.isfCrUseManualTags,
            onToggle = onUseManualTagsToggle
        )
        SettingIntStepperRow(
            title = stringResource(id = R.string.settings_isfcr_min_isf_evidence_per_hour),
            subtitle = stringResource(id = R.string.settings_isfcr_min_isf_evidence_per_hour_subtitle),
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

@Composable
private fun DataSourcesCard(
    state: SettingsUiState,
    onNightscoutUrlSave: (String) -> Unit,
    onLocalNightscoutToggle: (Boolean) -> Unit,
    onLocalBroadcastIngestToggle: (Boolean) -> Unit,
    onStrictSenderValidationToggle: (Boolean) -> Unit
) {
    var nightscoutUrlDraft by rememberSaveable(state.nightscoutUrl) {
        mutableStateOf(state.nightscoutUrl)
    }
    SettingsSectionCard {
        SettingsSectionLabel(text = stringResource(id = R.string.section_settings_data_sources))

        SettingTextInputRow(
            title = stringResource(id = R.string.settings_nightscout_url),
            subtitle = stringResource(id = R.string.settings_data_sources_nightscout_subtitle),
            value = nightscoutUrlDraft,
            placeholder = stringResource(id = R.string.placeholder_missing),
            onValueChange = { nightscoutUrlDraft = it },
            onApply = { onNightscoutUrlSave(nightscoutUrlDraft) }
        )
        SettingReadOnlyRow(
            title = stringResource(id = R.string.settings_resolved_url),
            value = state.resolvedNightscoutUrl.ifBlank { stringResource(id = R.string.placeholder_missing) }
        )
        SettingToggleRow(
            title = stringResource(id = R.string.settings_local_nightscout),
            subtitle = stringResource(id = R.string.settings_local_nightscout_subtitle),
            value = state.localNightscoutEnabled,
            onToggle = onLocalNightscoutToggle
        )
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
            value = state.localBroadcastIngestEnabled,
            onToggle = onLocalBroadcastIngestToggle
        )
        SettingToggleRow(
            title = stringResource(id = R.string.settings_strict_sender_validation),
            subtitle = stringResource(id = R.string.settings_data_sources_sender_subtitle),
            value = state.strictBroadcastSenderValidation,
            onToggle = onStrictSenderValidationToggle
        )
    }
}

@Composable
private fun UamSettingsCard(
    state: SettingsUiState,
    onUamInferenceToggle: (Boolean) -> Unit,
    onUamBoostToggle: (Boolean) -> Unit,
    onUamExportToggle: (Boolean) -> Unit,
    onUamExportModeChange: (String) -> Unit,
    onUamSnackConfigChange: (Int, Int, Int) -> Unit,
    onUamDryRunToggle: (Boolean) -> Unit
) {
    SettingsSectionCard {
        SettingsSectionLabel(text = stringResource(id = R.string.section_settings_uam))

        SettingToggleRow(
            title = stringResource(id = R.string.settings_uam_inference),
            subtitle = stringResource(id = R.string.settings_uam_inference_subtitle),
            value = state.enableUamInference,
            onToggle = onUamInferenceToggle
        )
        AnimatedVisibility(visible = state.enableUamInference) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                SettingToggleRow(
                    title = stringResource(id = R.string.settings_uam_boost),
                    subtitle = stringResource(id = R.string.settings_uam_boost_subtitle),
                    value = state.enableUamBoost,
                    onToggle = onUamBoostToggle
                )
                SettingToggleRow(
                    title = stringResource(id = R.string.settings_uam_export),
                    subtitle = stringResource(id = R.string.settings_uam_export_subtitle),
                    value = state.enableUamExportToAaps,
                    onToggle = onUamExportToggle
                )
                SettingToggleRow(
                    title = stringResource(id = R.string.settings_uam_dry_run),
                    subtitle = stringResource(id = R.string.settings_uam_dry_run_subtitle),
                    value = state.dryRunExport,
                    onToggle = onUamDryRunToggle
                )
                OptionChipsRow(
                    title = stringResource(id = R.string.settings_uam_export_mode),
                    options = listOf("OFF", "CONFIRMED_ONLY", "INCREMENTAL"),
                    selected = state.uamExportMode,
                    onSelect = onUamExportModeChange
                )
                SettingIntStepperRow(
                    title = stringResource(id = R.string.settings_uam_min_snack),
                    subtitle = stringResource(id = R.string.settings_uam_min_snack_subtitle),
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
private fun AdaptiveSettingsCard(
    state: SettingsUiState,
    onAdaptiveControllerToggle: (Boolean) -> Unit,
    onBaseTargetChange: (Double) -> Unit,
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
            value = state.adaptiveControllerEnabled,
            onToggle = onAdaptiveControllerToggle
        )
        AnimatedVisibility(visible = state.adaptiveControllerEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                SettingDoubleStepperRow(
                    title = stringResource(id = R.string.settings_base_target),
                    subtitle = stringResource(id = R.string.settings_base_target_subtitle),
                    value = state.baseTarget,
                    min = state.safetyMinTargetMmol,
                    max = state.safetyMaxTargetMmol,
                    step = 0.1,
                    unitLabel = stringResource(id = R.string.unit_mmol_l),
                    onValueChange = onBaseTargetChange
                )
                OptionChipsRow(
                    title = stringResource(id = R.string.settings_insulin_profile),
                    options = InsulinActionProfileId.values().map { it.name },
                    selected = state.insulinProfileId,
                    onSelect = onInsulinProfileSelect
                )
                SettingDoubleStepperRow(
                    title = stringResource(id = R.string.settings_adaptive_low_alert),
                    subtitle = stringResource(id = R.string.settings_adaptive_low_alert_subtitle),
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
            value = proModeEnabled,
            onToggle = onProModeToggle
        )
        SettingToggleRow(
            title = stringResource(id = R.string.settings_verbose_logs),
            subtitle = stringResource(id = R.string.settings_verbose_logs_subtitle),
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
    onCloseTag: (String) -> Unit
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
                OutlinedButton(onClick = { onCloseTag(item.id) }) {
                    Text(text = stringResource(id = R.string.settings_isfcr_tag_close))
                }
            }
        }
    }
}

@Composable
private fun SettingTextInputRow(
    title: String,
    subtitle: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onApply: () -> Unit
) {
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
            SettingTitleWithInfo(
                title = title,
                subtitle = subtitle,
                titleStyle = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(text = placeholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(onClick = onApply) {
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
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onValueChange: (Int) -> Unit
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
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SettingTitleWithInfo(
                    title = title,
                    subtitle = subtitle,
                    titleStyle = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    unitLabel: String,
    onValueChange: (Double) -> Unit
) {
    fun normalized(next: Double): Double {
        return (next.coerceIn(min, max) * 10.0).roundToInt() / 10.0
    }
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
                SettingTitleWithInfo(
                    title = title,
                    subtitle = subtitle,
                    titleStyle = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
    value: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        shape = SettingsInfoShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                    subtitle = subtitle,
                    titleStyle = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = value,
                onCheckedChange = onToggle
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
        IconButton(
            onClick = { showInfoDialog = true }
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = stringResource(
                    id = R.string.settings_info_button_cd,
                    title
                ),
                tint = MaterialTheme.colorScheme.primary
            )
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
    val tone = when {
        active -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    val bg = when {
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
                    style = MaterialTheme.typography.labelMedium
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
    Surface(
        modifier = modifier,
        shape = SettingsInfoShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun SettingsSectionCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SettingsSectionShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = AppElevation.level1)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            content = content
        )
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

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
                resolvedNightscoutUrl = "https://127.0.0.1:17582",
                insulinProfileId = "NOVORAPID",
                localNightscoutEnabled = true,
                localBroadcastIngestEnabled = true,
                strictBroadcastSenderValidation = false,
                enableUamInference = true,
                enableUamBoost = true,
                enableUamExportToAaps = true,
                uamExportMode = "CONFIRMED_ONLY",
                dryRunExport = false,
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
                isfCrActiveTags = listOf("stress"),
                adaptiveControllerEnabled = true,
                safetyMinTargetMmol = 4.0,
                safetyMaxTargetMmol = 10.0,
                postHypoThresholdMmol = 4.0,
                postHypoTargetMmol = 4.4,
                proModeEnabled = false,
                verboseLogsEnabled = false,
                retentionDays = 365,
                warningText = "Not a medical device. Verify all therapy decisions manually."
            ),
            onVerboseLogsToggle = {},
            onProModeToggle = {}
        )
    }
}
