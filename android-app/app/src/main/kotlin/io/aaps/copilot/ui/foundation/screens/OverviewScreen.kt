package io.aaps.copilot.ui.foundation.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import java.util.UUID
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bluetooth
import io.aaps.copilot.data.repository.PumpLinkUiStatus
import io.aaps.copilot.data.repository.TargetCommandPreflightFailure
import io.aaps.copilot.data.repository.pumpLinkMessageRes
import io.aaps.copilot.domain.pump.PumpLinkCondition
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import io.aaps.copilot.R
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.target.BaseTargetSchedule
import io.aaps.copilot.ui.foundation.components.BaseTargetAutoLabel
import io.aaps.copilot.ui.foundation.components.BaseTargetScheduleDialog
import io.aaps.copilot.ui.foundation.components.baseTargetAutoLabel
import io.aaps.copilot.ui.foundation.design.AppElevation
import io.aaps.copilot.ui.foundation.design.Spacing
import io.aaps.copilot.ui.foundation.format.UiFormatters
import io.aaps.copilot.ui.foundation.components.ClinicalForecastChart
import io.aaps.copilot.ui.foundation.components.ClinicalChartViewportMode
import io.aaps.copilot.ui.foundation.components.InteractiveClinicalForecastChart
import io.aaps.copilot.ui.foundation.components.rememberInteractiveClinicalChartState
import io.aaps.copilot.ui.foundation.theme.AapsCopilotTheme
import io.aaps.copilot.ui.foundation.theme.LocalUiStyle
import io.aaps.copilot.ui.foundation.theme.LocalNumericTypography
import io.aaps.copilot.util.LocaleFriendlyDecimalParser
import io.aaps.copilot.util.UnitConverter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val SectionShape = RoundedCornerShape(18.dp)
private val InfoShape = RoundedCornerShape(12.dp)
private val PillShape = RoundedCornerShape(999.dp)
private val BloodCheckTimestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private enum class MetricSourceSheetKind {
    ISF,
    CR
}

enum class OverviewAuthoritativeEditor {
    MEAL,
    BLOOD_CHECK
}

internal enum class TargetManagerOutcomeUiKind {
    SUBMITTED_PENDING_READBACK,
    DELIVERY_FAILED,
    SHADOW,
    BLOCKED,
    NO_CHANGE,
    NO_PROPOSAL
}

internal enum class TargetManagerReasonUiKind {
    ELIGIBLE,
    IOB_UNQUALIFIED,
    PAUSED,
    DUPLICATE,
    CADENCE,
    KILL_SWITCH,
    STALE_DATA,
    SENSOR,
    DELIVERY,
    PROTECTIVE,
    FORECAST,
    EXTERNAL_TARGET,
    EXTERNAL_WRITER_CONFLICT,
    EATING_SOON_ACTIVE,
    THERAPY_WRITES_DISABLED,
    LEGACY_DRAIN,
    SAFETY_BOUNDS,
    MANUAL_TARGET_RETAINED,
    CURRENT_GLUCOSE_NEWER_THAN_CANDIDATE,
    PREFLIGHT_BLOCKED,
    DELIVERY_FAILED,
    NO_PROPOSAL
}

internal fun targetManagerOutcomeUiKind(outcome: String?): TargetManagerOutcomeUiKind = when (outcome) {
    "SEND", "RENEW_SAME_TARGET" -> TargetManagerOutcomeUiKind.SUBMITTED_PENDING_READBACK
    "DELIVERY_FAILED" -> TargetManagerOutcomeUiKind.DELIVERY_FAILED
    "SHADOW_WOULD_SEND" -> TargetManagerOutcomeUiKind.SHADOW
    "SUPPRESS_SEMANTIC_DUPLICATE" -> TargetManagerOutcomeUiKind.NO_CHANGE
    "NO_PROPOSAL" -> TargetManagerOutcomeUiKind.NO_PROPOSAL
    else -> TargetManagerOutcomeUiKind.BLOCKED
}

internal fun targetManagerReasonUiKind(reason: String?): TargetManagerReasonUiKind = when (reason) {
    "eligible", "shadow_would_send" -> TargetManagerReasonUiKind.ELIGIBLE
    "iob_unqualified" -> TargetManagerReasonUiKind.IOB_UNQUALIFIED
    "mode_off" -> TargetManagerReasonUiKind.PAUSED
    "semantic_duplicate" -> TargetManagerReasonUiKind.DUPLICATE
    "cadence_blocked" -> TargetManagerReasonUiKind.CADENCE
    "kill_switch" -> TargetManagerReasonUiKind.KILL_SWITCH
    "stale_data" -> TargetManagerReasonUiKind.STALE_DATA
    "sensor_untrusted" -> TargetManagerReasonUiKind.SENSOR
    "delivery_untrusted" -> TargetManagerReasonUiKind.DELIVERY
    "protective_direction" -> TargetManagerReasonUiKind.PROTECTIVE
    "forecast_unreliable" -> TargetManagerReasonUiKind.FORECAST
    "external_target_retained" -> TargetManagerReasonUiKind.EXTERNAL_TARGET
    "external_target_writer_conflict" -> TargetManagerReasonUiKind.EXTERNAL_WRITER_CONFLICT
    "eating_soon_target_active" -> TargetManagerReasonUiKind.EATING_SOON_ACTIVE
    "therapy_writes_disabled" -> TargetManagerReasonUiKind.THERAPY_WRITES_DISABLED
    "legacy_target_drain" -> TargetManagerReasonUiKind.LEGACY_DRAIN
    "safety_bounds" -> TargetManagerReasonUiKind.SAFETY_BOUNDS
    "manual_target_active_or_pending" -> TargetManagerReasonUiKind.MANUAL_TARGET_RETAINED
    "current_glucose_newer_than_candidate" ->
        TargetManagerReasonUiKind.CURRENT_GLUCOSE_NEWER_THAN_CANDIDATE
    "delivery_failed" -> TargetManagerReasonUiKind.DELIVERY_FAILED
    else -> if (reason?.let(TargetCommandPreflightFailure::fromReason) != null) {
        TargetManagerReasonUiKind.PREFLIGHT_BLOCKED
    } else {
        TargetManagerReasonUiKind.NO_PROPOSAL
    }
}

@Composable
fun OverviewScreen(
    state: OverviewUiState,
    onRunCycleNow: () -> Unit,
    onSetKillSwitch: (Boolean) -> Unit,
    onDisablePowerSave: () -> Unit,
    onAddBloodCheck: (String, String, Long, String) -> Unit,
    onResetBloodCalibration: () -> Unit = {},
    onOpenSensorLagAnalytics: (() -> Unit)? = null,
    onOpenClinicalReport: () -> Unit,
    onBaseTargetScheduleSave: (BaseTargetSchedule) -> Unit = {},
    onManualCarbs: (String, String, MealAbsorptionProfile, Double?, Boolean, String, io.aaps.copilot.domain.nutrition.MealPortionMetadata, io.aaps.copilot.domain.profile.MealGlycemicIndex?) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onOpenAapsBolus: () -> Unit = {},
    onUamExportUiModeChange: (String) -> Unit = {},
    onIsfRuntimeSourceChange: (String) -> Unit = {},
    onCrRuntimeSourceChange: (String) -> Unit = {},
    onOpenIsfCrAnalytics: () -> Unit = {},
    authoritativeEditorRequest: OverviewAuthoritativeEditor? = null,
    onAuthoritativeEditorRequestHandled: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showKillSwitchConfirm by remember { mutableStateOf(false) }
    var showBloodCheckDialog by rememberSaveable { mutableStateOf(false) }
    var showTargetDialog by remember { mutableStateOf(false) }
    var showCobDialog by remember { mutableStateOf(false) }
    var showIobDialog by rememberSaveable { mutableStateOf(false) }
    var showUamDialog by rememberSaveable { mutableStateOf(false) }
    var sourceSheet by rememberSaveable { mutableStateOf<MetricSourceSheetKind?>(null) }
    var valueAnimationsReady by remember { mutableStateOf(false) }
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    val openCobDialog = {
        showCobDialog = true
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        valueAnimationsReady = true
    }

    LaunchedEffect(authoritativeEditorRequest) {
        when (authoritativeEditorRequest) {
            OverviewAuthoritativeEditor.MEAL -> openCobDialog()
            OverviewAuthoritativeEditor.BLOOD_CHECK -> showBloodCheckDialog = true
            null -> return@LaunchedEffect
        }
        onAuthoritativeEditorRequestHandled()
    }

    if (showKillSwitchConfirm) {
        AlertDialog(
            onDismissRequest = { showKillSwitchConfirm = false },
            title = { Text(text = stringResource(id = R.string.overview_kill_switch_title)) },
            text = {
                Text(
                    text = if (state.killSwitchEnabled) {
                        stringResource(id = R.string.overview_kill_switch_disable_confirm)
                    } else {
                        stringResource(id = R.string.overview_kill_switch_enable_confirm)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showKillSwitchConfirm = false
                        onSetKillSwitch(!state.killSwitchEnabled)
                    }
                ) {
                    Text(stringResource(id = R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showKillSwitchConfirm = false }) {
                    Text(stringResource(id = R.string.action_cancel))
                }
            }
        )
    }

    if (showBloodCheckDialog) {
        BloodCheckDialog(
            onDismiss = { showBloodCheckDialog = false },
            onConfirm = { valueRaw, units, timestamp, note ->
                showBloodCheckDialog = false
                onAddBloodCheck(valueRaw, units, timestamp, note)
            },
            resetEnabled = true,
            onResetCalibration = {
                showBloodCheckDialog = false
                onResetBloodCalibration()
            }
        )
    }

    if (showUamDialog) {
        AlertDialog(
            modifier = Modifier.testTag("uamExportModeDialog"),
            onDismissRequest = { showUamDialog = false },
            title = { Text(text = stringResource(id = R.string.overview_uam_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    UamExportRuntimeSummary(control = state.uamExport)
                    UamExportModeSelector(
                        mode = state.uamExport.mode,
                        onModeSelected = onUamExportUiModeChange,
                        enabled = state.uamExport.available
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showUamDialog = false }) {
                    Text(text = stringResource(id = R.string.overview_uam_dialog_close))
                }
            }
        )
    }

    if (showTargetDialog) {
        BaseTargetScheduleDialog(
            schedule = state.baseTargetSchedule,
            effectiveTargetMmol = state.effectiveBaseTargetMmol,
            autoDeltaMmol = state.baseTargetAutoDeltaMmol,
            autoState = state.baseTargetAutoState,
            autoReason = state.baseTargetAutoReason,
            minTargetMmol = state.targetEditMinMmol,
            maxTargetMmol = state.targetEditMaxMmol,
            onDismiss = { showTargetDialog = false },
            onSave = { schedule ->
                showTargetDialog = false
                onBaseTargetScheduleSave(schedule)
            }
        )
    }

    if (showCobDialog) {
        io.aaps.copilot.ui.foundation.components.MealEntryDialog(
            settings = state.mealPortions,
            maximumGrams = io.aaps.copilot.domain.nutrition.MealCarbLimits.MAX_MANUAL_MEAL_GRAMS,
            onDismiss = { showCobDialog = false },
            onConfirm = { meal ->
                showCobDialog = false
                onManualCarbs(
                    meal.grams.toString(), "overview_cob", meal.profile,
                    meal.energyKcal, meal.eatingSoon, meal.submissionId,
                    io.aaps.copilot.domain.nutrition.MealPortionMetadata(meal.portion, meal.provenance),
                    meal.glycemicIndex
                )
            }
        )
    }

    if (showIobDialog) {
        AlertDialog(
            onDismissRequest = { showIobDialog = false },
            title = { Text(stringResource(R.string.overview_iob_dialog_title)) },
            text = {
                val unavailable = stringResource(R.string.overview_value_unavailable)
                val details = state.iobDetails
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        stringResource(
                            R.string.overview_iob_current_value,
                            UiFormatters.formatUnits(state.currentIobUnits, 1),
                            stringResource(R.string.unit_u)
                        )
                    )
                    Text(
                        stringResource(
                            R.string.overview_iob_net_value,
                            details.signedNetIobUnits?.let { UiFormatters.formatUnits(it, 1) } ?: unavailable
                        )
                    )
                    Text(
                        stringResource(
                            R.string.overview_iob_bolus_value,
                            details.bolusIobUnits?.let { UiFormatters.formatUnits(it, 1) } ?: unavailable
                        )
                    )
                    Text(
                        stringResource(
                            R.string.overview_iob_basal_value,
                            details.basalIobUnits?.let { UiFormatters.formatUnits(it, 1) } ?: unavailable
                        )
                    )
                    Text(
                        stringResource(
                            R.string.overview_iob_activity_value,
                            details.insulinActivity?.let {
                                UiFormatters.formatDecimalOrPlaceholder(it, 3)
                            } ?: unavailable
                        )
                    )
                    Text(
                        stringResource(
                            R.string.overview_iob_source_value,
                            details.actualSource ?: unavailable
                        )
                    )
                    Text(
                        stringResource(
                            R.string.overview_iob_sample_timestamp_value,
                            details.sampleTimestamp?.let { UiFormatters.formatTimestamp(it) } ?: unavailable
                        )
                    )
                    Text(
                        stringResource(
                            R.string.overview_iob_sample_age_value,
                            details.sampleAgeMinutes?.toString() ?: unavailable
                        )
                    )
                    Text(
                        stringResource(
                            R.string.overview_iob_confidence_value,
                            details.confidence?.let { UiFormatters.formatPercent(it) } ?: unavailable
                        )
                    )
                    Text(
                        stringResource(
                            R.string.overview_iob_evidence_timestamp_value,
                            details.evidenceTimestamp?.let { UiFormatters.formatTimestamp(it) } ?: unavailable
                        )
                    )
                    Text(
                        stringResource(
                            R.string.overview_iob_coverage_value,
                            details.therapyCoverage?.let { UiFormatters.formatPercent(it) } ?: unavailable
                        )
                    )
                    details.fallbackReason?.let { reason ->
                        Text(stringResource(R.string.overview_iob_fallback_reason_value, reason))
                    }
                    Text(stringResource(R.string.overview_iob_dialog_empty_notice))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showIobDialog = false
                        onOpenAapsBolus()
                    }
                ) {
                    Text(stringResource(R.string.overview_iob_open_aaps))
                }
            },
            dismissButton = {
                TextButton(onClick = { showIobDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    sourceSheet?.let { sheet ->
        MetricSourceDialog(
            title = stringResource(
                if (sheet == MetricSourceSheetKind.ISF) R.string.overview_isf_source_title else R.string.overview_cr_source_title
            ),
            unit = stringResource(
                if (sheet == MetricSourceSheetKind.ISF) R.string.unit_mmol_u else R.string.unit_g_u
            ),
            runtime = if (sheet == MetricSourceSheetKind.ISF) state.isfRuntime else state.crRuntime,
            globalSensitivityApply = state.sensitivitySourceApplying,
            onSourceChange = { source ->
                if (sheet == MetricSourceSheetKind.ISF) onIsfRuntimeSourceChange(source) else onCrRuntimeSourceChange(source)
                sourceSheet = null
            },
            onOpenAnalytics = {
                sourceSheet = null
                onOpenIsfCrAnalytics()
            },
            onDismiss = { sourceSheet = null }
        )
    }

    ScreenStateLayout(
        loadState = state.loadState,
        isStale = state.isStale,
        errorText = state.errorText,
        emptyText = stringResource(id = R.string.overview_empty)
    ) {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            state.warning?.let { warning ->
                item {
                    OverviewWarningBanner(
                        warning = warning,
                        state = state,
                        midnightGlass = midnightGlass,
                        onDisablePowerSave = onDisablePowerSave,
                        onOpenSensorLagAnalytics = onOpenSensorLagAnalytics
                    )
                }
            }
            state.energyActivityStatus?.let { status ->
                item {
                    EnergyActivityStatusRow(status = status, midnightGlass = midnightGlass)
                }
            }
            if (state.pumpLinkStatus.condition != PumpLinkCondition.HEALTHY &&
                state.pumpLinkStatus.condition != PumpLinkCondition.INTENTIONALLY_DISCONNECTED) {
                item {
                    PumpLinkStatusRow(state.pumpLinkStatus, midnightGlass)
                }
            }
            item {
                OverviewCompactGlucoseHero(
                    state = state,
                    animationsEnabled = valueAnimationsReady,
                    midnightGlass = midnightGlass,
                    onAddBloodCheck = { showBloodCheckDialog = true }
                )
            }
            item {
                OverviewClinicalMatrix(
                    state = state,
                    midnightGlass = midnightGlass,
                    onIob = { showIobDialog = true },
                    onCob = openCobDialog,
                    onBaseTarget = { showTargetDialog = true },
                    onSampleAge = { showBloodCheckDialog = true },
                    onIsf = { sourceSheet = MetricSourceSheetKind.ISF },
                    onCr = { sourceSheet = MetricSourceSheetKind.CR },
                    onUam = { showUamDialog = true },
                    onOpenClinicalReport = onOpenClinicalReport
                )
            }
            item {
                TargetManagerLiveStatusSection(
                    status = state.targetManagerLiveStatus,
                    midnightGlass = midnightGlass
                )
            }
            item {
                OverviewForecastSection(
                    state = state,
                    midnightGlass = midnightGlass
                )
            }
            item {
                OverviewClinicalActions(
                    state = state,
                    onRunCycleNow = onRunCycleNow,
                    onEditTarget = { showTargetDialog = true },
                    onKillSwitch = { showKillSwitchConfirm = true }
                )
            }
        }
    }
}

@Composable
internal fun TargetManagerLiveStatusSection(
    status: TargetManagerLiveStatusUi,
    midnightGlass: Boolean,
    framed: Boolean = true
) {
    val secondaryColor = if (midnightGlass) Color(0xFFB8C5DC) else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("overview_target_manager_live_status"),
        shape = RoundedCornerShape(8.dp),
        color = if (!framed) Color.Transparent else if (midnightGlass) Color(0xAA101D38) else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (!framed) null else BorderStroke(
            1.dp,
            if (midnightGlass) Color(0x263A4A66) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Text(
                text = stringResource(id = R.string.overview_target_manager_status_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            when (status.availability) {
                TargetManagerLiveStatusAvailabilityUi.PAUSED -> Text(
                    text = stringResource(id = R.string.overview_target_manager_reason_paused),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryColor
                )
                TargetManagerLiveStatusAvailabilityUi.WAITING -> Text(
                    text = stringResource(id = R.string.overview_target_manager_status_waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryColor
                )
                TargetManagerLiveStatusAvailabilityUi.STALE -> Text(
                    text = stringResource(id = R.string.overview_target_manager_status_stale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                TargetManagerLiveStatusAvailabilityUi.UNAVAILABLE -> Text(
                    text = stringResource(id = R.string.overview_target_manager_status_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryColor
                )
                TargetManagerLiveStatusAvailabilityUi.CURRENT -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        TargetManagerTargetValue(
                            label = stringResource(id = R.string.overview_target_manager_current_target),
                            targetMmol = status.currentTargetMmol,
                            emptyLabel = stringResource(id = R.string.overview_target_manager_no_current_target),
                            secondaryColor = secondaryColor,
                            modifier = Modifier.weight(1f)
                        )
                        TargetManagerTargetValue(
                            label = stringResource(id = R.string.overview_target_manager_proposed_target),
                            targetMmol = status.proposedTargetMmol,
                            emptyLabel = stringResource(id = R.string.overview_target_manager_no_proposal),
                            secondaryColor = secondaryColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = targetManagerOutcomeLabel(targetManagerOutcomeUiKind(status.outcome)),
                        style = MaterialTheme.typography.bodySmall,
                        color = targetManagerOutcomeColor(
                            targetManagerOutcomeUiKind(status.outcome),
                            midnightGlass
                        )
                    )
                    Text(
                        text = stringResource(
                            id = R.string.overview_target_manager_reason,
                            targetManagerReasonLabel(targetManagerReasonUiKind(status.reason))
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryColor
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetManagerTargetValue(
    label: String,
    targetMmol: Double?,
    emptyLabel: String,
    secondaryColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = secondaryColor
        )
        Text(
            text = targetMmol?.let {
                "${UiFormatters.formatMmol(it, 1)} ${stringResource(id = R.string.unit_mmol_l)}"
            } ?: emptyLabel,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun targetManagerOutcomeLabel(kind: TargetManagerOutcomeUiKind): String = stringResource(
    id = when (kind) {
        TargetManagerOutcomeUiKind.SUBMITTED_PENDING_READBACK ->
            R.string.overview_target_manager_outcome_submitted
        TargetManagerOutcomeUiKind.DELIVERY_FAILED -> R.string.overview_target_manager_outcome_delivery_failed
        TargetManagerOutcomeUiKind.SHADOW -> R.string.overview_target_manager_outcome_shadow
        TargetManagerOutcomeUiKind.BLOCKED -> R.string.overview_target_manager_outcome_blocked
        TargetManagerOutcomeUiKind.NO_CHANGE -> R.string.overview_target_manager_outcome_no_change
        TargetManagerOutcomeUiKind.NO_PROPOSAL -> R.string.overview_target_manager_outcome_no_proposal
    }
)

@Composable
internal fun targetManagerReasonLabel(kind: TargetManagerReasonUiKind): String = stringResource(
    id = when (kind) {
        TargetManagerReasonUiKind.ELIGIBLE -> R.string.overview_target_manager_reason_eligible
        TargetManagerReasonUiKind.IOB_UNQUALIFIED -> R.string.overview_target_manager_reason_iob
        TargetManagerReasonUiKind.PAUSED -> R.string.overview_target_manager_reason_paused
        TargetManagerReasonUiKind.DUPLICATE -> R.string.overview_target_manager_reason_duplicate
        TargetManagerReasonUiKind.CADENCE -> R.string.overview_target_manager_reason_cadence
        TargetManagerReasonUiKind.KILL_SWITCH -> R.string.overview_target_manager_reason_kill_switch
        TargetManagerReasonUiKind.STALE_DATA -> R.string.overview_target_manager_reason_stale_data
        TargetManagerReasonUiKind.SENSOR -> R.string.overview_target_manager_reason_sensor
        TargetManagerReasonUiKind.DELIVERY -> R.string.overview_target_manager_reason_delivery
        TargetManagerReasonUiKind.PROTECTIVE -> R.string.overview_target_manager_reason_protective
        TargetManagerReasonUiKind.FORECAST -> R.string.overview_target_manager_reason_forecast
        TargetManagerReasonUiKind.EXTERNAL_TARGET -> R.string.overview_target_manager_reason_external_target
        TargetManagerReasonUiKind.EXTERNAL_WRITER_CONFLICT -> R.string.overview_target_manager_reason_writer_conflict
        TargetManagerReasonUiKind.EATING_SOON_ACTIVE -> R.string.overview_target_manager_reason_eating_soon
        TargetManagerReasonUiKind.THERAPY_WRITES_DISABLED -> R.string.overview_target_manager_reason_writes_disabled
        TargetManagerReasonUiKind.LEGACY_DRAIN -> R.string.overview_target_manager_reason_legacy_drain
        TargetManagerReasonUiKind.SAFETY_BOUNDS -> R.string.overview_target_manager_reason_safety_bounds
        TargetManagerReasonUiKind.MANUAL_TARGET_RETAINED ->
            R.string.overview_target_manager_reason_manual_target_or_pending
        TargetManagerReasonUiKind.CURRENT_GLUCOSE_NEWER_THAN_CANDIDATE ->
            R.string.overview_target_manager_reason_current_glucose_newer
        TargetManagerReasonUiKind.PREFLIGHT_BLOCKED ->
            R.string.overview_target_manager_reason_preflight_blocked
        TargetManagerReasonUiKind.DELIVERY_FAILED -> R.string.overview_target_manager_reason_delivery_failed
        TargetManagerReasonUiKind.NO_PROPOSAL -> R.string.overview_target_manager_reason_no_proposal
    }
)

@Composable
private fun targetManagerOutcomeColor(
    kind: TargetManagerOutcomeUiKind,
    midnightGlass: Boolean
): Color = when (kind) {
    TargetManagerOutcomeUiKind.DELIVERY_FAILED -> MaterialTheme.colorScheme.error
    TargetManagerOutcomeUiKind.SUBMITTED_PENDING_READBACK ->
        if (midnightGlass) Color(0xFF8DD7B5) else MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun PumpLinkStatusRow(status: PumpLinkUiStatus, midnightGlass: Boolean) {
    var showInfo by rememberSaveable { mutableStateOf(false) }
    val message = stringResource(status.condition.pumpLinkMessageRes())
    val foreground = if (midnightGlass) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth().testTag("pump_link_status")
            .padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Icon(Icons.Default.Bluetooth, contentDescription = null, tint = foreground)
        Text(message, modifier = Modifier.weight(1f), color = foreground,
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        IconButton(onClick = { showInfo = true }) {
            Icon(Icons.Default.Info, contentDescription = stringResource(R.string.pump_link_channel), tint = foreground)
        }
    }
    if (showInfo) {
        AlertDialog(onDismissRequest = { showInfo = false },
            title = { Text(message) },
            text = { Text(stringResource(R.string.pump_link_details)) },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text(stringResource(R.string.action_close)) } })
    }
}

@Composable
private fun EnergyActivityStatusRow(
    status: EnergyActivityStatusUi,
    midnightGlass: Boolean
) {
    val text = when (status.kind) {
        EnergyActivityStatusKind.PROFILE_ISSUE -> stringResource(R.string.overview_activity_profile_issue)
        EnergyActivityStatusKind.ACTIVE -> stringResource(
            R.string.overview_activity_active,
            status.title ?: stringResource(R.string.energy_activity_default_title)
        )
        EnergyActivityStatusKind.APPROACHING -> stringResource(
            R.string.overview_activity_approaching,
            status.title ?: stringResource(R.string.energy_activity_default_title),
            status.minutesUntilStart ?: 0L
        )
    }
    val tone = if (status.kind == EnergyActivityStatusKind.PROFILE_ISSUE) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("energy_activity_status")
            .semantics { contentDescription = text },
        shape = RoundedCornerShape(8.dp),
        color = tone.copy(alpha = if (midnightGlass) 0.18f else 0.10f)
            .compositeOver(MaterialTheme.colorScheme.surface),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, tone.copy(alpha = 0.34f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Icon(Icons.Default.Bolt, contentDescription = null, tint = tone)
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (midnightGlass) Color.White else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun OverviewWarningBanner(
    warning: OverviewWarningUi,
    state: OverviewUiState,
    midnightGlass: Boolean,
    onDisablePowerSave: () -> Unit,
    onOpenSensorLagAnalytics: (() -> Unit)?
) {
    val displayedKind = resolveOverviewBannerKind(
        warningKind = warning.kind,
        powerSaveActive = state.powerSaveActive
    )
    if (
        displayedKind == OverviewWarningKind.STRONG_GLUCOSE_ALERT ||
        displayedKind == OverviewWarningKind.SOFT_GLUCOSE_ALERT
    ) {
        return
    }

    val text = when (displayedKind) {
        OverviewWarningKind.SENSOR_OR_STALE -> stringResource(id = R.string.overview_warning_sensor)
        OverviewWarningKind.KILL_SWITCH -> stringResource(id = R.string.safety_kill_switch_on)
        OverviewWarningKind.POWER_SAVE -> if (state.powerSaveIndefinite) {
            stringResource(id = R.string.overview_power_save_status_on_indefinite)
        } else if (state.powerSaveRemainingText.isNotBlank()) {
            stringResource(id = R.string.overview_power_save_status_on_for, state.powerSaveRemainingText)
        } else {
            stringResource(id = R.string.overview_power_save_status_on)
        }
        else -> return
    }
    val tone = when (displayedKind) {
        OverviewWarningKind.KILL_SWITCH -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    val clickableModifier = if (
        displayedKind == OverviewWarningKind.SENSOR_OR_STALE && onOpenSensorLagAnalytics != null
    ) {
        Modifier.clickable(onClick = onOpenSensorLagAnalytics)
    } else {
        Modifier
    }

    Surface(
        modifier = clickableModifier
            .fillMaxWidth()
            .semantics { contentDescription = text },
        shape = RoundedCornerShape(8.dp),
        color = tone.copy(alpha = if (midnightGlass) 0.18f else 0.10f)
            .compositeOver(MaterialTheme.colorScheme.surface),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, tone.copy(alpha = 0.34f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = tone)
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (midnightGlass) Color.White else MaterialTheme.colorScheme.onSurface
            )
            if (displayedKind == OverviewWarningKind.POWER_SAVE) {
                TextButton(onClick = onDisablePowerSave) {
                    Text(text = stringResource(id = R.string.overview_power_save_turn_on))
                }
            }
        }
    }
}

internal fun resolveOverviewBannerKind(
    warningKind: OverviewWarningKind,
    powerSaveActive: Boolean
): OverviewWarningKind = if (powerSaveActive) {
    OverviewWarningKind.POWER_SAVE
} else {
    warningKind
}

@Composable
private fun OverviewCompactGlucoseHero(
    state: OverviewUiState,
    animationsEnabled: Boolean,
    midnightGlass: Boolean,
    onAddBloodCheck: () -> Unit
) {
    var showInfo by rememberSaveable { mutableStateOf(false) }
    val forecast30 = state.horizons.firstOrNull { it.horizonMinutes == 30 }
    val forecastStatus = resolveForecastGlucoseStatus(
        forecastMmol = forecast30?.pred,
        displayLowMmol = state.chart.displayRangeLowMmol,
        displayHighMmol = state.chart.displayRangeHighMmol
    )
    val forecastBadge = forecastBadgePresentation(forecastStatus)
    val secondaryTextColor = if (midnightGlass) {
        Color(0xFFB8C8DB)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(text = stringResource(id = R.string.overview_glucose_now)) },
            text = { Text(text = stringResource(id = R.string.overview_info_current_glucose_section)) },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(text = stringResource(id = R.string.action_close))
                }
            }
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("overviewCompactHero"),
        shape = RoundedCornerShape(8.dp),
        color = if (midnightGlass) Color(0xF51D2D49) else MaterialTheme.colorScheme.surface,
        contentColor = if (midnightGlass) Color.White else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.overview_glucose_now),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = onAddBloodCheck) {
                    Icon(
                        Icons.Default.Bloodtype,
                        contentDescription = stringResource(id = R.string.overview_calibration_action),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                IconButton(onClick = { showInfo = true }) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = stringResource(
                            id = R.string.settings_info_button_cd,
                            stringResource(id = R.string.overview_glucose_now)
                        )
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(1.15f)) {
                    if (animationsEnabled) {
                        AnimatedContent(
                            targetState = UiFormatters.formatMmol(state.glucose, 1),
                            transitionSpec = {
                                fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
                            },
                            label = "overviewClinicalGlucose"
                        ) { value ->
                            Text(
                                text = value,
                                style = LocalNumericTypography.current.valueLarge.copy(
                                    fontSize = 46.sp,
                                    lineHeight = 48.sp,
                                    letterSpacing = 0.sp
                                ),
                                color = if (midnightGlass) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        Text(
                            text = UiFormatters.formatMmol(state.glucose, 1),
                            style = LocalNumericTypography.current.valueLarge.copy(
                                fontSize = 46.sp,
                                lineHeight = 48.sp,
                                letterSpacing = 0.sp
                            )
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(id = R.string.unit_mmol_l),
                            style = MaterialTheme.typography.labelSmall,
                            color = secondaryTextColor
                        )
                        if (shouldShowRawGlucoseCompanion(state.glucose, state.rawGlucose)) {
                            Text(
                                text = stringResource(
                                    id = R.string.overview_raw_glucose_compact,
                                    UiFormatters.formatMmol(state.rawGlucose, 1)
                                ),
                                modifier = Modifier.testTag("overviewRawGlucoseCompanion"),
                                style = MaterialTheme.typography.labelSmall,
                                color = secondaryTextColor
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(0.85f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(id = R.string.overview_trend_short),
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryTextColor
                    )
                    Text(
                        text = UiFormatters.formatSignedDelta(state.delta, 1),
                        style = LocalNumericTypography.current.valueMedium.copy(
                            fontSize = 22.sp,
                            letterSpacing = 0.sp
                        ),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(id = R.string.unit_mmol_l_5m),
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryTextColor,
                        textAlign = TextAlign.Center
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = stringResource(id = R.string.overview_forecast_30_short),
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryTextColor
                    )
                    Text(
                        text = UiFormatters.formatMmol(forecast30?.pred, 1),
                        style = LocalNumericTypography.current.valueMedium.copy(
                            fontSize = 30.sp,
                            letterSpacing = 0.sp
                        ),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(
                            id = R.string.overview_ci_template,
                            UiFormatters.formatMmol(forecast30?.ciLow, 1),
                            UiFormatters.formatMmol(forecast30?.ciHigh, 1)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryTextColor,
                        textAlign = TextAlign.End
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                forecastBadge?.let { badge ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badge.bgColor,
                        border = BorderStroke(1.dp, badge.tone.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)
                        ) {
                            Icon(
                                imageVector = badge.icon,
                                contentDescription = null,
                                tint = badge.tone,
                                modifier = Modifier.width(16.dp)
                            )
                            Text(
                                text = stringResource(id = badge.labelRes),
                                style = MaterialTheme.typography.labelSmall,
                                color = badge.tone
                            )
                        }
                    }
                }
                Text(
                    text = compactAgeLabel(state.sampleAgeMinutes),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryTextColor,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

internal fun shouldShowRawGlucoseCompanion(
    primaryGlucoseMmol: Double?,
    rawGlucoseMmol: Double?
): Boolean {
    if (primaryGlucoseMmol?.isFinite() != true || rawGlucoseMmol?.isFinite() != true) return false
    return kotlin.math.abs(primaryGlucoseMmol - rawGlucoseMmol) >= 0.05
}

private data class OverviewClinicalMetric(
    val label: String,
    val value: String,
    val unit: String,
    val valueColor: Color? = null,
    val onClick: (() -> Unit)? = null,
    val contentDescription: String? = null,
    val testTag: String? = null
)

@Composable
private fun OverviewClinicalMatrix(
    state: OverviewUiState,
    midnightGlass: Boolean,
    onIob: () -> Unit,
    onCob: () -> Unit,
    onBaseTarget: () -> Unit,
    onSampleAge: () -> Unit,
    onIsf: () -> Unit,
    onCr: () -> Unit,
    onUam: () -> Unit,
    onOpenClinicalReport: () -> Unit
) {
    val currentStatus = resolveCurrentGlucoseStatus(
        glucose = state.glucose,
        stale = state.isStale,
        displayLowMmol = state.chart.displayRangeLowMmol,
        displayHighMmol = state.chart.displayRangeHighMmol
    )
    val metrics = listOf(
        OverviewClinicalMetric(
            label = stringResource(id = R.string.metric_iob),
            value = UiFormatters.formatUnits(state.currentIobUnits, 1),
            unit = stringResource(id = R.string.unit_u),
            onClick = onIob,
            contentDescription = stringResource(R.string.overview_iob_action_description)
        ),
        OverviewClinicalMetric(
            label = stringResource(id = R.string.metric_cob),
            value = UiFormatters.formatGrams(state.currentCobGrams, 1),
            unit = stringResource(id = R.string.unit_g),
            onClick = onCob,
            contentDescription = stringResource(R.string.overview_edit_cob)
        ),
        OverviewClinicalMetric(
            label = stringResource(id = R.string.metric_base_target),
            value = UiFormatters.formatMmol(state.effectiveBaseTargetMmol, 1),
            unit = compactTargetAutoStatus(state),
            onClick = onBaseTarget,
            contentDescription = stringResource(R.string.overview_edit_base_target)
        ),
        OverviewClinicalMetric(
            label = stringResource(id = R.string.overview_metric_sample_age),
            value = compactAgeLabel(state.sampleAgeMinutes),
            unit = "",
            onClick = onSampleAge,
            contentDescription = stringResource(R.string.overview_sample_age_action_description),
            testTag = "overviewSampleAgeMetric"
        ),
        OverviewClinicalMetric(
            label = "${stringResource(id = R.string.overview_model_isf_now)} · " +
                metricActualSourceLabel(state.isfRuntime),
            value = UiFormatters.formatMmol(state.currentIsfMmolPerUnit, 1),
            unit = stringResource(id = R.string.unit_mmol_u),
            onClick = onIsf,
            contentDescription = stringResource(R.string.overview_edit_isf)
        ),
        OverviewClinicalMetric(
            label = "${stringResource(id = R.string.overview_model_cr_now)} · " +
                metricActualSourceLabel(state.crRuntime),
            value = UiFormatters.formatGrams(state.currentCrGramsPerUnit, 1),
            unit = stringResource(id = R.string.unit_g_u),
            onClick = onCr,
            contentDescription = stringResource(R.string.overview_edit_cr)
        ),
        OverviewClinicalMetric(
            label = stringResource(id = R.string.overview_model_uam),
            value = UiFormatters.formatGrams(state.calculatedUamCarbsGrams, 1),
            unit = state.calculatedUamConfidence?.let { confidence ->
                stringResource(
                    id = R.string.overview_uam_mode_confidence,
                    uamExportModeShortLabel(state.uamExport.mode),
                    UiFormatters.formatPercent(confidence)
                )
            } ?: uamExportModeShortLabel(state.uamExport.mode),
            onClick = onUam,
            contentDescription = stringResource(id = R.string.overview_uam_action_description),
            testTag = "overviewUamMetric"
        ),
        OverviewClinicalMetric(
            label = stringResource(id = R.string.overview_current_status),
            value = currentStatusLabel(currentStatus),
            unit = stringResource(id = R.string.overview_status_analysis),
            valueColor = currentStatusTone(currentStatus),
            onClick = onOpenClinicalReport,
            contentDescription = stringResource(id = R.string.overview_open_compensation_analysis),
            testTag = "overviewCurrentStatusMetric"
        )
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("overviewClinicalMatrix"),
        shape = RoundedCornerShape(8.dp),
        color = if (midnightGlass) Color(0xE6192943) else MaterialTheme.colorScheme.surface,
        contentColor = if (midnightGlass) Color.White else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        BoxWithConstraints {
            val fontScale = LocalDensity.current.fontScale
            val useTwoColumns = maxWidth < 360.dp || fontScale >= 1.3f
            val columns = if (useTwoColumns) 2 else 4
            val rowHeightDp = overviewClinicalMatrixRowHeightDp(useTwoColumns, fontScale)
            Column {
                metrics.chunked(columns).forEachIndexed { rowIndex, rowMetrics ->
                    if (rowIndex > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    OverviewClinicalMetricRow(metrics = rowMetrics, rowHeightDp = rowHeightDp)
                }
                val sensitivityStatus = when {
                    state.sensitivitySourceApplying ->
                        "${state.sensitivitySourcePendingMetric.orEmpty()}: applying " +
                            state.sensitivitySourcePendingValue.orEmpty()
                    !state.sensitivitySourceApplyError.isNullOrBlank() ->
                        state.sensitivitySourceApplyError
                    else -> null
                }
                sensitivityStatus?.let { status ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = status,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .testTag("overviewSensitivitySourceStatus"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.sensitivitySourceApplying) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            }
        }
    }
}

internal fun metricSourceSelectionShortLabel(sourceRaw: String): String = when (sourceRaw.trim().uppercase()) {
    "AAPS" -> "AAPS"
    "COPILOT" -> "Copilot"
    "EVIDENCE" -> "Evidence"
    else -> "--"
}

internal enum class MetricActualSourceLabelKind {
    AAPS,
    EVIDENCE,
    COPILOT,
    APPLYING,
    UNAVAILABLE
}

internal fun metricActualSourceLabelKind(
    resolvedSourceRaw: String?,
    availability: MetricRuntimeAvailabilityUi
): MetricActualSourceLabelKind = when (availability) {
    MetricRuntimeAvailabilityUi.APPLYING -> MetricActualSourceLabelKind.APPLYING
    MetricRuntimeAvailabilityUi.UNAVAILABLE -> MetricActualSourceLabelKind.UNAVAILABLE
    MetricRuntimeAvailabilityUi.AVAILABLE -> when (resolvedSourceRaw?.trim()?.uppercase(Locale.US)) {
        "AAPS" -> MetricActualSourceLabelKind.AAPS
        "EVIDENCE", "EVIDENCE_BLEND" -> MetricActualSourceLabelKind.EVIDENCE
        "COPILOT", "COPILOT_NATIVE" -> MetricActualSourceLabelKind.COPILOT
        else -> MetricActualSourceLabelKind.UNAVAILABLE
    }
}

internal fun metricActualSourceShortLabel(
    resolvedSourceRaw: String?,
    availability: MetricRuntimeAvailabilityUi
): String = when (metricActualSourceLabelKind(resolvedSourceRaw, availability)) {
    MetricActualSourceLabelKind.AAPS -> "AAPS"
    MetricActualSourceLabelKind.EVIDENCE -> "Evidence"
    MetricActualSourceLabelKind.COPILOT -> "Copilot"
    MetricActualSourceLabelKind.APPLYING -> "Applying"
    MetricActualSourceLabelKind.UNAVAILABLE -> "Unavailable"
}

@Composable
private fun metricActualSourceLabel(runtime: MetricRuntimeSourceUi): String = when (
    metricActualSourceLabelKind(runtime.actualResolvedSource, runtime.availability)
) {
    MetricActualSourceLabelKind.AAPS -> stringResource(R.string.overview_source_aaps)
    MetricActualSourceLabelKind.EVIDENCE -> stringResource(R.string.overview_source_evidence)
    MetricActualSourceLabelKind.COPILOT -> stringResource(R.string.overview_source_copilot)
    MetricActualSourceLabelKind.APPLYING -> stringResource(R.string.overview_source_applying_short)
    MetricActualSourceLabelKind.UNAVAILABLE -> stringResource(R.string.overview_source_unavailable_short)
}

@Composable
private fun sourceDisplayLabel(sourceRaw: String?): String = when (sourceRaw?.trim()?.uppercase(Locale.US)) {
    "AAPS" -> stringResource(R.string.overview_source_aaps)
    "EVIDENCE", "EVIDENCE_BLEND" -> stringResource(R.string.overview_source_evidence)
    "COPILOT", "COPILOT_NATIVE" -> stringResource(R.string.overview_source_copilot)
    else -> stringResource(R.string.overview_source_unavailable_short)
}

internal fun overviewClinicalMatrixRowHeightDp(
    useTwoColumns: Boolean,
    fontScale: Float
): Float {
    val baseHeight = if (useTwoColumns) 78f else 72f
    val accessibilityAllowance = (fontScale.coerceIn(1f, 2f) - 1f) * 48f
    return baseHeight + accessibilityAllowance
}

@Composable
private fun OverviewClinicalMetricRow(
    metrics: List<OverviewClinicalMetric>,
    rowHeightDp: Float
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeightDp.dp)
    ) {
        metrics.forEachIndexed { index, metric ->
            if (index > 0) {
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
            OverviewMetricCell(
                label = metric.label,
                value = metric.value,
                unit = metric.unit,
                valueColor = metric.valueColor,
                onClick = metric.onClick,
                contentDescription = metric.contentDescription,
                testTag = metric.testTag,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun OverviewMetricCell(
    label: String,
    value: String,
    unit: String,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
    testTag: String? = null,
    modifier: Modifier = Modifier
) {
    val taggedModifier = testTag?.let(modifier::testTag) ?: modifier
    val interactiveModifier = if (onClick != null) {
        taggedModifier
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription.orEmpty() }
    } else {
        taggedModifier
    }
    Column(
        modifier = interactiveModifier
            .fillMaxHeight()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
        Text(
            text = value,
            style = LocalNumericTypography.current.valueSmall.copy(letterSpacing = 0.sp),
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
        if (unit.isNotBlank()) {
            Text(
                text = unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun MetricSourceDialog(
    title: String,
    unit: String,
    runtime: MetricRuntimeSourceUi,
    globalSensitivityApply: Boolean,
    onSourceChange: (String) -> Unit,
    onOpenAnalytics: () -> Unit,
    onDismiss: () -> Unit
) {
    val unavailable = stringResource(R.string.overview_value_unavailable)
    val requestedLabel = sourceDisplayLabel(runtime.requested)
    val actualLabel = when (runtime.availability) {
        MetricRuntimeAvailabilityUi.AVAILABLE -> sourceDisplayLabel(runtime.actualResolvedSource)
        MetricRuntimeAvailabilityUi.APPLYING -> stringResource(R.string.overview_source_applying_short)
        MetricRuntimeAvailabilityUi.UNAVAILABLE -> stringResource(R.string.overview_source_unavailable_short)
    }
    val fallbackUnavailableWord = stringResource(R.string.overview_source_unavailable_word)
    val fallbackChain = runtime.fallbackPath.mapIndexed { index, source ->
        val label = metricSourceSelectionShortLabel(source)
        if (index < runtime.fallbackPath.lastIndex) "$label $fallbackUnavailableWord" else label
    }.joinToString(" -> ")
    val sourceChoices = listOf(
        "EVIDENCE" to stringResource(R.string.overview_source_evidence),
        "COPILOT" to stringResource(R.string.overview_source_copilot),
        "AAPS" to stringResource(R.string.overview_source_aaps)
    )
    val candidates = runtime.candidates.ifEmpty {
        listOf(
            MetricCandidateDiagnosticsUi("AAPS", runtime.aapsValue),
            MetricCandidateDiagnosticsUi("EVIDENCE", runtime.evidenceValue),
            MetricCandidateDiagnosticsUi("COPILOT", runtime.copilotValue)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    stringResource(
                        R.string.overview_source_effective,
                        runtime.selectedValue?.let {
                            "${UiFormatters.formatDecimalOrPlaceholder(it, 1)} $unit"
                        } ?: unavailable
                    )
                )
                Text(stringResource(R.string.overview_source_requested, requestedLabel))
                Text(stringResource(R.string.overview_source_actual, actualLabel))
                Text(
                    stringResource(
                        R.string.overview_source_confidence,
                        runtime.confidence?.let { UiFormatters.formatPercent(it) } ?: unavailable
                    )
                )
                if (runtime.acceptedSettingsRevision != null && runtime.acceptedCycleId != null) {
                    Text(stringResource(R.string.overview_source_revision, runtime.acceptedSettingsRevision))
                    Text(stringResource(R.string.overview_source_cycle, runtime.acceptedCycleId))
                    Text(
                        stringResource(
                            R.string.overview_source_timestamp,
                            runtime.acceptedTimestamp?.let { UiFormatters.formatTimestamp(it) } ?: unavailable
                        )
                    )
                    Text(
                        stringResource(
                            R.string.overview_source_freshness,
                            runtime.acceptedAgeMinutes?.toString() ?: unavailable,
                            when (runtime.acceptedFresh) {
                                true -> stringResource(R.string.overview_source_fresh)
                                false -> stringResource(R.string.overview_source_stale)
                                null -> unavailable
                            }
                        )
                    )
                } else {
                    Text(stringResource(R.string.overview_source_identity_unavailable))
                }
                if (fallbackChain.isNotBlank()) {
                    Text(stringResource(R.string.overview_source_fallback_chain, fallbackChain))
                }
                runtime.fallbackReason?.let { reason ->
                    Text(stringResource(R.string.overview_source_fallback_reason, reason))
                }
                HorizontalDivider()
                candidates.forEach { candidate ->
                    MetricCandidateDetails(candidate = candidate, unit = unit)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    sourceChoices.forEach { (source, label) ->
                        OutlinedButton(
                            onClick = { onSourceChange(source) },
                            enabled = metricSourceChoiceEnabled(
                                globalSensitivityApply = globalSensitivityApply,
                                runtimeAvailability = runtime.availability,
                                requestedSource = runtime.requested,
                                choiceSource = source
                            )
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenAnalytics) {
                Text(stringResource(R.string.overview_source_analytics))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

internal fun metricSourceChoiceEnabled(
    globalSensitivityApply: Boolean,
    runtimeAvailability: MetricRuntimeAvailabilityUi,
    requestedSource: String,
    choiceSource: String
): Boolean = !globalSensitivityApply &&
    runtimeAvailability != MetricRuntimeAvailabilityUi.APPLYING &&
    !requestedSource.equals(choiceSource, ignoreCase = true)

@Composable
private fun MetricCandidateDetails(
    candidate: MetricCandidateDiagnosticsUi,
    unit: String
) {
    val unavailable = stringResource(R.string.overview_value_unavailable)
    val source = metricSourceSelectionShortLabel(candidate.source)
    Text(
        text = stringResource(
            R.string.overview_source_candidate_value,
            source,
            candidate.value?.let { UiFormatters.formatDecimalOrPlaceholder(it, 2) } ?: unavailable,
            unit
        ),
        fontWeight = FontWeight.SemiBold
    )
    if (!candidate.diagnosticsAvailable) {
        Text(
            text = stringResource(R.string.overview_source_candidate_diagnostics_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Text(
        stringResource(
            R.string.overview_source_candidate_timestamp,
            candidate.timestamp?.let { UiFormatters.formatTimestamp(it) } ?: unavailable
        ),
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        stringResource(
            R.string.overview_source_candidate_freshness,
            candidate.ageMinutesAtDecision?.toString() ?: unavailable,
            when (candidate.freshAtDecision) {
                true -> stringResource(R.string.overview_source_fresh)
                false -> stringResource(R.string.overview_source_stale)
                null -> unavailable
            }
        ),
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        stringResource(
            R.string.overview_source_candidate_confidence,
            candidate.confidence?.let { UiFormatters.formatPercent(it) } ?: unavailable
        ),
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        stringResource(
            R.string.overview_source_candidate_samples,
            candidate.sampleCount?.toString() ?: unavailable
        ),
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        stringResource(
            R.string.overview_source_candidate_coverage,
            candidate.coverage?.let { UiFormatters.formatPercent(it) } ?: unavailable
        ),
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        stringResource(
            R.string.overview_source_candidate_quality,
            when (candidate.qualityPassed) {
                true -> stringResource(R.string.overview_source_quality_passed)
                false -> stringResource(R.string.overview_source_quality_failed)
                null -> unavailable
            }
        ),
        style = MaterialTheme.typography.bodySmall
    )
    candidate.unavailableReason?.let { reason ->
        Text(
            stringResource(R.string.overview_source_candidate_unavailable_reason, reason),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun OverviewForecastSection(
    state: OverviewUiState,
    midnightGlass: Boolean
) {
    val forecast30 = state.horizons.firstOrNull { it.horizonMinutes == 30 }
    val chartDescription = stringResource(
        id = R.string.overview_chart_accessibility,
        UiFormatters.formatMmol(state.glucose, 1),
        UiFormatters.formatMmol(forecast30?.pred, 1),
        UiFormatters.formatMmol(forecast30?.ciLow, 1),
        UiFormatters.formatMmol(forecast30?.ciHigh, 1),
        UiFormatters.formatMmol(state.chart.displayRangeLowMmol, 1),
        UiFormatters.formatMmol(state.chart.displayRangeHighMmol, 1)
    )
    val interactionState = rememberInteractiveClinicalChartState(state.chart)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (midnightGlass) Color(0xF51D2D49) else MaterialTheme.colorScheme.surface,
        contentColor = if (midnightGlass) Color.White else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.overview_chart_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (interactionState != null) {
                    IconButton(onClick = interactionState::reset) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(id = R.string.overview_chart_reset_view)
                        )
                    }
                }
            }
            if (interactionState == null) {
                ClinicalForecastChart(
                    state = state.chart,
                    contentDescription = chartDescription,
                    emptyText = stringResource(id = R.string.overview_chart_empty),
                    nowLabel = stringResource(id = R.string.overview_chart_now),
                    plus30Label = stringResource(id = R.string.overview_chart_plus_30)
                )
            } else {
                val formatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
                val zone = ZoneId.systemDefault()
                val viewportMode = stringResource(
                    id = if (interactionState.viewport.mode == ClinicalChartViewportMode.FOLLOW_LIVE) {
                        R.string.overview_chart_viewport_live
                    } else {
                        R.string.overview_chart_viewport_explore
                    }
                )
                val viewportText = stringResource(
                    id = R.string.overview_chart_viewport_state,
                    Instant.ofEpochMilli(interactionState.viewport.startTs).atZone(zone).format(formatter),
                    Instant.ofEpochMilli(interactionState.viewport.endTs).atZone(zone).format(formatter),
                    viewportMode
                )
                InteractiveClinicalForecastChart(
                    state = state.chart,
                    interactionState = interactionState,
                    contentDescription = chartDescription,
                    viewportStateDescription = viewportText,
                    emptyText = stringResource(id = R.string.overview_chart_empty),
                    nowLabel = stringResource(id = R.string.overview_chart_now),
                    plus30Label = stringResource(id = R.string.overview_chart_plus_30),
                    zoomInLabel = stringResource(id = R.string.overview_chart_zoom_in),
                    zoomOutLabel = stringResource(id = R.string.overview_chart_zoom_out),
                    olderLabel = stringResource(id = R.string.overview_chart_older),
                    newerLabel = stringResource(id = R.string.overview_chart_newer),
                    resetLabel = stringResource(id = R.string.overview_chart_reset_view)
                )
            }
            Text(
                text = stringResource(when {
                    state.chart.mealImpactPoints.isEmpty() -> R.string.meal_impact_unavailable
                    state.chart.mealImpactGiAdjusted -> R.string.meal_impact_gi_legend
                    state.chart.mealImpactComplete == null -> R.string.meal_impact_legacy_legend
                    else -> R.string.meal_impact_legend
                }),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            if (state.chart.mealImpactComplete == false && state.chart.mealImpactPoints.isNotEmpty()) Text(
                text = stringResource(R.string.meal_impact_incomplete),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun OverviewClinicalActions(
    state: OverviewUiState,
    onRunCycleNow: () -> Unit,
    onEditTarget: () -> Unit,
    onKillSwitch: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalButton(
            onClick = onRunCycleNow,
            enabled = state.canRunCycleNow,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.Bolt, contentDescription = null)
            Text(
                text = stringResource(id = R.string.overview_run_cycle_now),
                modifier = Modifier.padding(start = Spacing.xxs)
            )
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            IconButton(onClick = onEditTarget) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(id = R.string.overview_target_edit)
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            IconButton(onClick = onKillSwitch) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = stringResource(id = R.string.overview_kill_switch_shortcut),
                    tint = if (state.killSwitchEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun OverviewHeader(midnightGlass: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Text(
            text = stringResource(id = R.string.overview_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = if (midnightGlass) Color(0xFF93A5C3) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OverviewHighlightsRow(state: OverviewUiState, midnightGlass: Boolean) {
    val iobChip = state.telemetryChips.firstOrNull { it.label.equals("IOB", ignoreCase = true) }
    val cobChip = state.telemetryChips.firstOrNull { it.label.equals("COB", ignoreCase = true) }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        OverviewHighlightCard(
            label = stringResource(id = R.string.metric_current_glucose),
            value = UiFormatters.formatMmol(state.glucose),
            unit = stringResource(id = R.string.unit_mmol_l),
            icon = if ((state.delta ?: 0.0) < 0.0) Icons.Default.TrendingDown else Icons.Default.TrendingUp,
            statusColor = if ((state.glucose ?: 0.0) < 4.2) Color(0xFFEF4444) else Color(0xFF3B82F6),
            midnightGlass = midnightGlass,
            modifier = Modifier.width(168.dp)
        )
        OverviewHighlightCard(
            label = stringResource(id = R.string.metric_iob),
            value = iobChip?.value ?: stringResource(id = R.string.placeholder_missing),
            unit = iobChip?.unit ?: stringResource(id = R.string.unit_u),
            icon = Icons.Default.Bolt,
            statusColor = Color(0xFF22C55E),
            midnightGlass = midnightGlass,
            modifier = Modifier.width(168.dp)
        )
        OverviewHighlightCard(
            label = stringResource(id = R.string.metric_cob),
            value = cobChip?.value ?: stringResource(id = R.string.placeholder_missing),
            unit = cobChip?.unit ?: stringResource(id = R.string.unit_g),
            icon = Icons.Default.CheckCircle,
            statusColor = Color(0xFFF59E0B),
            midnightGlass = midnightGlass,
            modifier = Modifier.width(168.dp)
        )
        OverviewHighlightCard(
            label = stringResource(id = R.string.metric_last_update),
            value = compactAgeLabel(state.sampleAgeMinutes),
            unit = "",
            icon = Icons.Default.Info,
            statusColor = Color(0xFF60A5FA),
            midnightGlass = midnightGlass,
            modifier = Modifier.width(168.dp)
        )
    }
}

@Composable
private fun OverviewHighlightCard(
    label: String,
    value: String,
    unit: String,
    icon: ImageVector,
    statusColor: Color,
    midnightGlass: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = SectionShape,
        colors = CardDefaults.cardColors(
            containerColor = if (midnightGlass) Color(0xAA0C1730) else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.dp,
            if (midnightGlass) statusColor.copy(alpha = 0.28f) else MaterialTheme.colorScheme.outlineVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = AppElevation.level1)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = statusColor
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (midnightGlass) Color(0xFF93A5C3) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = value,
                    style = LocalNumericTypography.current.valueMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (unit.isNotBlank()) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (midnightGlass) Color(0xFF93A5C3) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentGlucoseSection(
    state: OverviewUiState,
    animationsEnabled: Boolean,
    midnightGlass: Boolean,
    onOpenSensorLagAnalytics: (() -> Unit)?,
    onAddBloodCheck: () -> Unit
) {
    val risk = riskBadge(state.glucose, state.isStale)
    val warningHorizon = state.horizons
        .filter { it.warningWideCi }
        .maxByOrNull { it.horizonMinutes }

    SectionCard {
        SectionLabel(
            text = stringResource(id = R.string.metric_current_glucose),
            infoText = stringResource(id = R.string.overview_info_current_glucose_section)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = if (midnightGlass) Color(0xAA101D38) else Color.Transparent,
                    shape = RoundedCornerShape(22.dp)
                )
                .padding(horizontal = if (midnightGlass) 18.dp else 0.dp, vertical = if (midnightGlass) 18.dp else 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                if (animationsEnabled) {
                    AnimatedContent(
                        targetState = UiFormatters.formatMmol(state.glucose, 2),
                        transitionSpec = {
                            fadeIn(animationSpec = tween(220)) togetherWith
                                fadeOut(animationSpec = tween(180))
                        },
                        label = "overviewGlucoseValue"
                    ) { glucoseValue ->
                        Text(
                            text = glucoseValue,
                            style = LocalNumericTypography.current.valueLarge.copy(
                                fontSize = 56.sp,
                                lineHeight = 56.sp,
                                letterSpacing = 0.sp
                            ),
                            color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    Text(
                        text = UiFormatters.formatMmol(state.glucose, 2),
                        style = LocalNumericTypography.current.valueLarge.copy(
                            fontSize = 56.sp,
                            lineHeight = 56.sp,
                            letterSpacing = 0.sp
                        ),
                        color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = stringResource(id = R.string.unit_mmol_l),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (midnightGlass) Color(0xFF93A5C3) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.correctedGlucose != null && abs((state.correctedGlucose ?: 0.0) - (state.glucose ?: 0.0)) >= 0.01) {
                    Text(
                        text = buildString {
                            append(
                                if (state.sensorLagMode?.equals("ACTIVE", ignoreCase = true) == true) {
                                    "Control glucose "
                                } else {
                                    "Estimated now "
                                }
                            )
                            append(UiFormatters.formatMmol(state.correctedGlucose, 2))
                            append(" mmol/L")
                            state.sensorLagMinutes?.let {
                                append(" · lag ")
                                append(UiFormatters.formatDecimalOrPlaceholder(it, decimals = 0))
                                append("m")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (midnightGlass) Color(0xFF8DB6FF) else MaterialTheme.colorScheme.primary
                    )
                }
                state.sensorLagRolloutVerdict?.let { verdict ->
                    val verdictText = stringResource(
                        id = R.string.overview_sensor_lag_rollout_template,
                        stringResource(id = overviewSensorLagRolloutStatusLabelResId(verdict.status)),
                        stringResource(id = overviewSensorLagBucketLabelResId(verdict.bucket))
                    )
                    val verdictTone = overviewSensorLagRolloutTone(
                        status = verdict.status,
                        midnightGlass = midnightGlass
                    )
                    if (onOpenSensorLagAnalytics != null) {
                        Surface(
                            shape = InfoShape,
                            color = if (midnightGlass) {
                                Color(0x1A7FB3FF)
                            } else {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            },
                            border = BorderStroke(1.dp, verdictTone.copy(alpha = if (midnightGlass) 0.45f else 0.35f)),
                            modifier = Modifier
                                .clickable(onClick = onOpenSensorLagAnalytics)
                                .semantics { contentDescription = verdictText }
                        ) {
                            Text(
                                text = verdictText,
                                style = MaterialTheme.typography.bodySmall,
                                color = verdictTone,
                                modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xxs)
                            )
                        }
                    } else {
                        Text(
                            text = verdictText,
                            style = MaterialTheme.typography.bodySmall,
                            color = verdictTone
                        )
                    }
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
            ) {
                if (animationsEnabled) {
                    AnimatedContent(
                        targetState = UiFormatters.formatSignedDelta(state.delta, 2),
                        transitionSpec = {
                            fadeIn(animationSpec = tween(220)) togetherWith
                                fadeOut(animationSpec = tween(180))
                        },
                        label = "overviewDeltaValue"
                    ) { deltaValue ->
                        Text(
                            text = deltaValue,
                            style = LocalNumericTypography.current.valueMedium.copy(fontSize = 28.sp),
                            color = deltaTone(state.delta)
                        )
                    }
                } else {
                    Text(
                        text = UiFormatters.formatSignedDelta(state.delta, 2),
                        style = LocalNumericTypography.current.valueMedium.copy(fontSize = 28.sp),
                        color = deltaTone(state.delta)
                    )
                }
                Text(
                    text = stringResource(id = R.string.overview_roc_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (midnightGlass) Color(0xFF93A5C3) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!state.sensorLagDisableReason.isNullOrBlank()) {
                    Text(
                        text = state.sensorLagDisableReason.replace('_', ' '),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (midnightGlass) Color(0xFFFFD180) else MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    id = R.string.overview_sample_age,
                    UiFormatters.formatMinutes(state.sampleAgeMinutes)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                shape = PillShape,
                color = risk.bgColor,
                border = BorderStroke(1.dp, risk.tone.copy(alpha = if (midnightGlass) 0.25f else 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)
                ) {
                    Icon(
                        imageVector = risk.icon,
                        contentDescription = stringResource(id = risk.labelRes),
                        tint = risk.tone,
                        modifier = Modifier.width(14.dp)
                    )
                    Text(
                        text = stringResource(id = risk.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = risk.tone
                    )
                }
            }
        }

        TreatmentMiniChips(chips = state.telemetryChips, midnightGlass = midnightGlass)

        CalibrationStatusCard(
            state = state,
            midnightGlass = midnightGlass,
            onAddBloodCheck = onAddBloodCheck
        )

        if (warningHorizon != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (midnightGlass) Color(0x33FFB020) else MaterialTheme.colorScheme.tertiaryContainer,
                border = BorderStroke(1.dp, if (midnightGlass) Color(0x33FFB020) else MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = stringResource(id = R.string.status_hypo_risk),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(
                        text = stringResource(
                            id = R.string.overview_hypo_risk_window_detected,
                            warningHorizon.horizonMinutes
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (midnightGlass) Color(0xFFFFD180) else MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun TreatmentMiniChips(
    chips: List<TelemetryChipUi>,
    midnightGlass: Boolean
) {
    val visible = chips.filter { chip ->
        chip.label.equals("IOB", ignoreCase = true) || chip.label.equals("COB", ignoreCase = true)
    }
    if (visible.isEmpty()) return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        visible.forEach { chip ->
            val tone = if (chip.label.equals("IOB", ignoreCase = true)) {
                if (midnightGlass) Color(0xFF9FFFB0) else MaterialTheme.colorScheme.secondary
            } else {
                if (midnightGlass) Color(0xFFFFC94A) else MaterialTheme.colorScheme.tertiary
            }
            Surface(
                shape = PillShape,
                color = if (midnightGlass) Color(0xFF152641) else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, tone.copy(alpha = 0.28f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = chip.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (midnightGlass) Color(0xFFBFD0EA) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = buildString {
                            append(chip.value)
                            chip.unit?.takeIf { it.isNotBlank() }?.let { unit ->
                                append(' ')
                                append(unit)
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = tone
                    )
                }
            }
        }
    }
}

@Composable
private fun CalibrationStatusCard(
    state: OverviewUiState,
    midnightGlass: Boolean,
    onAddBloodCheck: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (midnightGlass) Color(0xAA0E1730) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.overview_calibration_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
                )
                OutlinedButton(onClick = onAddBloodCheck) {
                    Text(text = stringResource(id = R.string.overview_action_blood_check))
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
            ) {
                AnalyticsLikeChip(
                    label = stringResource(id = R.string.overview_calibration_raw),
                    value = UiFormatters.formatMmol(state.rawGlucose, 2)
                )
                AnalyticsLikeChip(
                    label = stringResource(id = R.string.overview_calibration_calibrated),
                    value = UiFormatters.formatMmol(state.calibratedGlucose ?: state.glucose, 2)
                )
                AnalyticsLikeChip(
                    label = stringResource(id = R.string.overview_calibration_model),
                    value = state.calibrationModelType ?: stringResource(id = R.string.placeholder_missing)
                )
                AnalyticsLikeChip(
                    label = stringResource(id = R.string.overview_calibration_gain),
                    value = state.calibrationGain?.let { String.format(Locale.US, "%.3f", it) }
                        ?: stringResource(id = R.string.placeholder_missing)
                )
                AnalyticsLikeChip(
                    label = stringResource(id = R.string.overview_calibration_offset),
                    value = UiFormatters.formatSignedDelta(state.calibrationOffsetMmol, 2)
                )
                AnalyticsLikeChip(
                    label = stringResource(id = R.string.overview_calibration_confidence),
                    value = UiFormatters.formatPercent(state.calibrationConfidence)
                )
            }
            Text(
                text = stringResource(
                    id = R.string.overview_calibration_last_check_template,
                    state.calibrationLastCheckAgeMinutes?.let { UiFormatters.formatMinutes(it.toLong()) }
                        ?: stringResource(id = R.string.placeholder_missing),
                    state.calibrationStatus ?: stringResource(id = R.string.placeholder_missing)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(id = R.string.overview_calibration_info),
                style = MaterialTheme.typography.bodySmall,
                color = if (midnightGlass) Color(0xFF8DB6FF) else MaterialTheme.colorScheme.primary
            )
        }
    }
}

internal fun remainingGlucoseAlertMuteMinutes(mutedUntilTs: Long, nowTs: Long): Int {
    val remainingMs = (mutedUntilTs - nowTs).coerceAtLeast(0L)
    return ((remainingMs + 59_999L) / 60_000L).toInt()
}

@Composable
private fun AnalyticsLikeChip(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
internal fun BloodCheckDialog(
    onDismiss: () -> Unit,
    onConfirm: (valueRaw: String, units: String, timestamp: Long, note: String) -> Unit,
    resetEnabled: Boolean = false,
    onResetCalibration: () -> Unit = {}
) {
    var valueRaw by rememberSaveable { mutableStateOf("") }
    var units by rememberSaveable { mutableStateOf("mmol/L") }
    var timestampText by rememberSaveable {
        mutableStateOf(
            BloodCheckTimestampFormatter.format(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(System.currentTimeMillis()), ZoneId.systemDefault())
            )
        )
    }
    var noteRaw by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    val timestampMillis = remember(timestampText) { parseBloodCheckTimestamp(timestampText) }
    val valueValid = remember(valueRaw, units) { isSupportedBloodCheckValue(valueRaw, units) }
    val canSave = valueValid && timestampMillis != null && !submitted

    if (showResetConfirmation) {
        AlertDialog(
            modifier = Modifier.testTag("bloodCheckResetConfirmation"),
            onDismissRequest = { showResetConfirmation = false },
            title = { Text(text = stringResource(id = R.string.overview_blood_check_reset_title)) },
            text = { Text(text = stringResource(id = R.string.overview_blood_check_reset_message)) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("bloodCheckResetConfirm"),
                    onClick = {
                        showResetConfirmation = false
                        onResetCalibration()
                    }
                ) {
                    Text(
                        text = stringResource(id = R.string.overview_blood_check_reset_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text(text = stringResource(id = R.string.action_cancel))
                }
            }
        )
        return
    }

    AlertDialog(
        modifier = Modifier.testTag("bloodCheckDialog"),
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.overview_blood_check_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(id = R.string.overview_blood_check_dialog_info),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        modifier = Modifier.testTag("bloodCheckReset"),
                        enabled = resetEnabled && !submitted,
                        onClick = { showResetConfirmation = true }
                    ) {
                        Text(
                            text = stringResource(id = R.string.overview_blood_check_reset_action),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                OutlinedTextField(
                    value = valueRaw,
                    onValueChange = { valueRaw = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bloodCheckValue"),
                    label = { Text(text = stringResource(id = R.string.overview_blood_check_value_label)) },
                    singleLine = true,
                    isError = valueRaw.isNotBlank() && !valueValid
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
                ) {
                    listOf("mmol/L", "mg/dL").forEach { option ->
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (units == option) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (units == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .testTag(if (option == "mg/dL") "bloodCheckUnitsMgdl" else "bloodCheckUnitsMmol")
                                .selectable(
                                    selected = units == option,
                                    role = Role.RadioButton,
                                    onClick = { units = option }
                                )
                        ) {
                            Text(
                                text = option,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (units == option) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs)
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = timestampText,
                    onValueChange = { timestampText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bloodCheckTimestamp"),
                    label = { Text(text = stringResource(id = R.string.overview_blood_check_timestamp_label)) },
                    supportingText = {
                        Text(text = stringResource(id = R.string.overview_blood_check_timestamp_hint))
                    },
                    singleLine = true,
                    isError = timestampText.isNotBlank() && timestampMillis == null
                )
                OutlinedTextField(
                    value = noteRaw,
                    onValueChange = { noteRaw = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bloodCheckNote"),
                    label = { Text(text = stringResource(id = R.string.overview_blood_check_note_label)) },
                    singleLine = false,
                    maxLines = 2
                )
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("bloodCheckSave"),
                enabled = canSave,
                onClick = {
                    if (submitted) return@TextButton
                    val parsedTs = timestampMillis ?: return@TextButton
                    val parsedValue = LocaleFriendlyDecimalParser.parse(valueRaw) ?: return@TextButton
                    submitted = true
                    onConfirm(parsedValue.canonical, units, parsedTs, noteRaw.trim())
                }
            ) {
                Text(text = stringResource(id = R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag("bloodCheckCancel"),
                onClick = onDismiss
            ) {
                Text(text = stringResource(id = R.string.action_cancel))
            }
        }
    )
}

internal fun isSupportedBloodCheckValue(valueRaw: String, units: String): Boolean {
    val value = LocaleFriendlyDecimalParser.parse(valueRaw)?.value ?: return false
    val mmol = when (units) {
        "mmol/L" -> value
        "mg/dL" -> UnitConverter.mgdlToMmol(value)
        else -> return false
    }
    return mmol in 2.2..22.0
}

private fun parseBloodCheckTimestamp(text: String): Long? {
    return runCatching {
        LocalDateTime.parse(text.trim(), BloodCheckTimestampFormatter)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
}

@Composable
private fun PredictionsSection(
    horizons: List<HorizonPredictionUi>,
    midnightGlass: Boolean
) {
    val sorted = horizons.sortedBy { it.horizonMinutes }
    SectionCard {
        SectionLabel(
            text = stringResource(id = R.string.section_overview_predictions),
            infoText = stringResource(id = R.string.overview_info_predictions_section)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            sorted.forEach { horizon ->
                PredictionCell(
                    horizon = horizon,
                    modifier = Modifier.weight(1f),
                    midnightGlass = midnightGlass
                )
            }
        }
    }
}

@Composable
private fun PredictionCell(
    horizon: HorizonPredictionUi,
    modifier: Modifier = Modifier,
    midnightGlass: Boolean
) {
    val (container, content, ciTone) = when {
        !midnightGlass -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onSurface,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        horizon.pred != null && horizon.pred < 4.4 -> Triple(
            Color(0x55312735),
            Color(0xFFF8FAFC),
            Color(0xFFFFD180)
        )
        else -> Triple(
            Color(0xFF10275A),
            Color(0xFFF8FAFC),
            Color(0xFFB5C0D8)
        )
    }
    Surface(
        modifier = modifier,
        shape = InfoShape,
        color = container,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "${horizon.horizonMinutes}m",
                style = MaterialTheme.typography.labelSmall,
                color = ciTone
            )
            Text(
                text = UiFormatters.formatMmol(horizon.pred, 2),
                style = LocalNumericTypography.current.valueMedium.copy(fontSize = 29.sp),
                color = content
            )
            Text(
                text = stringResource(
                    id = R.string.overview_ci_template,
                    UiFormatters.formatMmol(horizon.ciLow, 2),
                    UiFormatters.formatMmol(horizon.ciHigh, 2)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = ciTone
            )
        }
    }
}

@Composable
private fun UamSection(
    active: Boolean?,
    uci0Mmol5m: Double?,
    inferredCarbsLast60g: Double?,
    mode: String?,
    midnightGlass: Boolean
) {
    SectionCard {
        SectionLabel(
            text = stringResource(id = R.string.section_overview_uam_status),
            infoText = stringResource(id = R.string.overview_info_uam_section)
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                UamInfoCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(id = R.string.label_active_state),
                    value = when (active) {
                        true -> stringResource(id = R.string.status_on_short)
                        false -> stringResource(id = R.string.status_off_short)
                        null -> stringResource(id = R.string.placeholder_missing)
                    },
                    midnightGlass = midnightGlass
                )
                UamInfoCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(id = R.string.overview_uam_uci0),
                    value = uci0Mmol5m?.let { "${UiFormatters.formatMmol(it, 2)} mmol/5m" }
                        ?: stringResource(id = R.string.placeholder_missing),
                    midnightGlass = midnightGlass
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                UamInfoCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(id = R.string.overview_label_inferred_60m),
                    value = inferredCarbsLast60g?.let { UiFormatters.formatGrams(it, 0) + " g" }
                        ?: stringResource(id = R.string.placeholder_missing),
                    midnightGlass = midnightGlass
                )
                UamInfoCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(id = R.string.overview_label_mode),
                    value = mode ?: stringResource(id = R.string.placeholder_missing),
                    midnightGlass = midnightGlass
                )
            }
        }
    }
}

@Composable
private fun UamInfoCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    midnightGlass: Boolean
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (midnightGlass) Color(0xAA101D38) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TelemetrySection(chips: List<TelemetryChipUi>, midnightGlass: Boolean) {
    SectionCard {
        SectionLabel(
            text = stringResource(id = R.string.section_overview_telemetry),
            infoText = stringResource(id = R.string.overview_info_telemetry_section)
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            chips.forEach { chip ->
                val line = buildString {
                    append(chip.label)
                    append(' ')
                    append(chip.value)
                    chip.unit?.takeIf { it.isNotBlank() }?.let {
                        append(' ')
                        append(it)
                    }
                }
                Surface(
                    shape = PillShape,
                    color = if (midnightGlass) Color(0xFF162544) else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, if (midnightGlass) Color(0x1FFFFFFF) else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.semantics {
                        contentDescription = line
                    }
                ) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (midnightGlass) Color(0xFFDCEBFF) else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LastActionSection(action: LastActionUi?, midnightGlass: Boolean) {
    SectionCard {
        SectionLabel(
            text = stringResource(id = R.string.section_overview_last_action),
            infoText = stringResource(id = R.string.overview_info_last_action_section)
        )
        if (action == null) {
            Text(
                text = stringResource(id = R.string.overview_no_actions),
                style = MaterialTheme.typography.bodyMedium
            )
            return@SectionCard
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = actionSummary(action = action),
                style = MaterialTheme.typography.bodySmall,
                color = if (midnightGlass) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.onSurface
            )
            StatusPill(status = action.status, midnightGlass = midnightGlass)
        }

        val idempotency = action.idempotencyKey ?: stringResource(id = R.string.placeholder_missing)
        Text(
            text = "${UiFormatters.formatTimestamp(action.timestamp)} · ${stringResource(id = R.string.overview_action_meta, idempotency)}",
            style = MaterialTheme.typography.labelSmall,
            color = if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusPill(status: String, midnightGlass: Boolean) {
    val normalized = status.uppercase(Locale.US)
    val isSent = normalized.contains("SENT")
    val isBlocked = normalized.contains("BLOCKED")
    val tone = if (midnightGlass) {
        when {
            isSent -> Color(0xFF9FFFB0)
            isBlocked -> Color(0xFFFFD37A)
            else -> Color(0xFFFF8A80)
        }
    } else {
        when {
            isSent -> MaterialTheme.colorScheme.onSecondaryContainer
            isBlocked -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.error
        }
    }
    val bg = if (midnightGlass) {
        when {
            isSent -> Color(0x2200E676)
            isBlocked -> Color(0x33F59E0B)
            else -> Color(0x66B42318)
        }
    } else {
        when {
            isSent -> MaterialTheme.colorScheme.secondaryContainer
            isBlocked -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.errorContainer
        }
    }

    Surface(
        shape = PillShape,
        color = bg
    ) {
        Text(
            text = "● ${status.uppercase()}",
            style = MaterialTheme.typography.labelMedium,
            color = tone,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun ActionButtonsRow(
    canRunCycleNow: Boolean,
    onRunCycleNow: () -> Unit,
    onKillSwitch: () -> Unit,
    midnightGlass: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        FilledTonalButton(
            onClick = onRunCycleNow,
            enabled = canRunCycleNow,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Bolt, contentDescription = stringResource(id = R.string.overview_run_cycle_now))
            Text(
                text = stringResource(id = R.string.overview_run_cycle_now),
                modifier = Modifier.padding(start = Spacing.xxs)
            )
        }

        OutlinedButton(
            onClick = onKillSwitch,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, if (midnightGlass) Color(0x33FFB020) else MaterialTheme.colorScheme.outlineVariant)
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = stringResource(id = R.string.overview_kill_switch_shortcut),
                tint = if (midnightGlass) Color(0xFFFFD180) else MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = stringResource(id = R.string.overview_kill_switch_shortcut),
                modifier = Modifier.padding(start = Spacing.xxs),
                color = if (midnightGlass) Color(0xFFFFD180) else MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = SectionShape,
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
private fun SectionLabel(
    text: String,
    infoText: String? = null
) {
    var showInfo by rememberSaveable(text, infoText) { mutableStateOf(false) }
    val midnightGlass = LocalUiStyle.current == UiStyle.MIDNIGHT_GLASS
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
            color = if (midnightGlass) Color(0xFFD0D7E8) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!infoText.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (midnightGlass) Color(0x221D4ED8) else Color.Transparent
            ) {
                IconButton(onClick = { showInfo = true }) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = stringResource(id = R.string.settings_info_button_cd, text),
                        tint = if (midnightGlass) Color(0xFF5CA9FF) else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
    if (showInfo && !infoText.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(text = text) },
            text = { Text(text = infoText) },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(text = stringResource(id = R.string.action_close))
                }
            }
        )
    }
}

private fun compactAgeLabel(minutes: Long?): String {
    val value = minutes ?: return "--"
    return if (value < 1L) "<1m" else "${value}m"
}

@Composable
private fun actionSummary(action: LastActionUi): String {
    return when {
        action.tempTargetMmol != null && action.durationMinutes != null -> {
            val value = UiFormatters.formatMmol(action.tempTargetMmol, 1)
            stringResource(
                id = R.string.overview_action_temp_target_summary,
                value,
                action.durationMinutes
            )
        }
        action.carbsGrams != null -> {
            stringResource(
                id = R.string.overview_action_carbs_summary,
                UiFormatters.formatGrams(action.carbsGrams, 1)
            )
        }
        !action.payloadSummary.isNullOrBlank() -> action.payloadSummary
        else -> stringResource(id = R.string.overview_action_fallback_summary, action.type)
    }
}

internal enum class OverviewCurrentGlucoseStatus {
    NO_DATA,
    STALE,
    BELOW_TARGET,
    IN_TARGET,
    ABOVE_TARGET
}

internal enum class OverviewForecastGlucoseStatus {
    MISSING,
    LOW,
    IN_RANGE,
    HIGH
}

internal fun resolveCurrentGlucoseStatus(
    glucose: Double?,
    stale: Boolean,
    displayLowMmol: Double,
    displayHighMmol: Double
): OverviewCurrentGlucoseStatus {
    if (
        glucose == null || !glucose.isFinite() ||
        !displayLowMmol.isFinite() || !displayHighMmol.isFinite() ||
        displayLowMmol >= displayHighMmol
    ) {
        return OverviewCurrentGlucoseStatus.NO_DATA
    }
    if (stale) return OverviewCurrentGlucoseStatus.STALE
    return when {
        glucose < displayLowMmol -> OverviewCurrentGlucoseStatus.BELOW_TARGET
        glucose > displayHighMmol -> OverviewCurrentGlucoseStatus.ABOVE_TARGET
        else -> OverviewCurrentGlucoseStatus.IN_TARGET
    }
}

internal fun resolveForecastGlucoseStatus(
    forecastMmol: Double?,
    displayLowMmol: Double,
    displayHighMmol: Double
): OverviewForecastGlucoseStatus {
    if (
        forecastMmol == null || !forecastMmol.isFinite() ||
        !displayLowMmol.isFinite() || !displayHighMmol.isFinite() ||
        displayLowMmol >= displayHighMmol
    ) {
        return OverviewForecastGlucoseStatus.MISSING
    }
    return when {
        forecastMmol < displayLowMmol -> OverviewForecastGlucoseStatus.LOW
        forecastMmol > displayHighMmol -> OverviewForecastGlucoseStatus.HIGH
        else -> OverviewForecastGlucoseStatus.IN_RANGE
    }
}

private data class RiskBadge(
    val labelRes: Int,
    val icon: ImageVector,
    val tone: Color,
    val bgColor: Color
)

@Composable
private fun forecastBadgePresentation(status: OverviewForecastGlucoseStatus): RiskBadge? {
    return when (status) {
        OverviewForecastGlucoseStatus.MISSING -> null
        OverviewForecastGlucoseStatus.LOW -> RiskBadge(
            labelRes = R.string.overview_forecast_low,
            icon = Icons.Default.Error,
            tone = MaterialTheme.colorScheme.onErrorContainer,
            bgColor = MaterialTheme.colorScheme.errorContainer
        )
        OverviewForecastGlucoseStatus.IN_RANGE -> RiskBadge(
            labelRes = R.string.overview_forecast_in_range,
            icon = Icons.Default.CheckCircle,
            tone = MaterialTheme.colorScheme.onSecondaryContainer,
            bgColor = MaterialTheme.colorScheme.secondaryContainer
        )
        OverviewForecastGlucoseStatus.HIGH -> RiskBadge(
            labelRes = R.string.overview_forecast_high,
            icon = Icons.Default.TrendingUp,
            tone = MaterialTheme.colorScheme.onTertiaryContainer,
            bgColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    }
}

@Composable
private fun riskBadge(glucose: Double?, stale: Boolean): RiskBadge {
    return when {
        stale -> RiskBadge(
            labelRes = R.string.status_stale_data,
            icon = Icons.Default.Warning,
            tone = MaterialTheme.colorScheme.onTertiaryContainer,
            bgColor = MaterialTheme.colorScheme.tertiaryContainer
        )
        glucose == null -> RiskBadge(
            labelRes = R.string.status_info,
            icon = Icons.Default.CheckCircle,
            tone = Color(0xFF5F6B7A),
            bgColor = MaterialTheme.colorScheme.surfaceVariant
        )
        glucose < 3.9 -> RiskBadge(
            labelRes = R.string.overview_glucose_low,
            icon = Icons.Default.Error,
            tone = MaterialTheme.colorScheme.onErrorContainer,
            bgColor = MaterialTheme.colorScheme.errorContainer
        )
        glucose > 13.9 -> RiskBadge(
            labelRes = R.string.overview_glucose_very_high,
            icon = Icons.Default.TrendingUp,
            tone = MaterialTheme.colorScheme.onErrorContainer,
            bgColor = MaterialTheme.colorScheme.errorContainer
        )
        glucose > 10.0 -> RiskBadge(
            labelRes = R.string.overview_glucose_high,
            icon = Icons.Default.TrendingUp,
            tone = MaterialTheme.colorScheme.onTertiaryContainer,
            bgColor = MaterialTheme.colorScheme.tertiaryContainer
        )
        else -> RiskBadge(
            labelRes = R.string.overview_glucose_target,
            icon = Icons.Default.TrendingDown,
            tone = MaterialTheme.colorScheme.onSecondaryContainer,
            bgColor = MaterialTheme.colorScheme.secondaryContainer
        )
    }
}

@Composable
private fun compactTargetAutoStatus(state: OverviewUiState): String {
    return when (baseTargetAutoLabel(state.baseTargetAutoState)) {
        BaseTargetAutoLabel.ACTIVE -> String.format(
            Locale.getDefault(),
            "AUTO %+.1f",
            state.baseTargetAutoDeltaMmol
        )
        BaseTargetAutoLabel.WAIT -> stringResource(id = R.string.base_target_auto_wait)
        BaseTargetAutoLabel.SENSOR -> stringResource(id = R.string.base_target_auto_sensor)
        BaseTargetAutoLabel.LOW_GUARD -> stringResource(id = R.string.base_target_auto_low_guard)
        BaseTargetAutoLabel.OFF -> ""
    }
}

@Composable
private fun currentStatusLabel(status: OverviewCurrentGlucoseStatus): String {
    return when (status) {
        OverviewCurrentGlucoseStatus.NO_DATA -> stringResource(id = R.string.placeholder_missing)
        OverviewCurrentGlucoseStatus.STALE -> stringResource(id = R.string.status_stale_short)
        OverviewCurrentGlucoseStatus.BELOW_TARGET -> stringResource(id = R.string.overview_current_below_target)
        OverviewCurrentGlucoseStatus.IN_TARGET -> stringResource(id = R.string.overview_current_in_target)
        OverviewCurrentGlucoseStatus.ABOVE_TARGET -> stringResource(id = R.string.overview_current_above_target)
    }
}

@Composable
private fun currentStatusTone(status: OverviewCurrentGlucoseStatus): Color {
    return when (status) {
        OverviewCurrentGlucoseStatus.BELOW_TARGET -> MaterialTheme.colorScheme.error
        OverviewCurrentGlucoseStatus.ABOVE_TARGET -> MaterialTheme.colorScheme.tertiary
        OverviewCurrentGlucoseStatus.IN_TARGET -> MaterialTheme.colorScheme.primary
        OverviewCurrentGlucoseStatus.NO_DATA,
        OverviewCurrentGlucoseStatus.STALE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun deltaTone(delta: Double?): Color {
    return when {
        delta == null -> MaterialTheme.colorScheme.onSurfaceVariant
        delta > 0.0 -> MaterialTheme.colorScheme.onTertiaryContainer
        delta < 0.0 -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
}

@Composable
private fun overviewSensorLagRolloutTone(status: String, midnightGlass: Boolean): Color {
    return when (status.uppercase(Locale.US)) {
        "ACTIVE_CANDIDATE" -> if (midnightGlass) Color(0xFF9ED9B0) else MaterialTheme.colorScheme.secondary
        "HOLD" -> if (midnightGlass) Color(0xFFFFD180) else MaterialTheme.colorScheme.tertiary
        else -> if (midnightGlass) Color(0xFFB5C0D8) else MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun overviewSensorLagRolloutStatusLabelResId(status: String): Int {
    return when (status.uppercase(Locale.US)) {
        "ACTIVE_CANDIDATE" -> R.string.analytics_daily_report_sensor_lag_rollout_active
        "SHADOW_FIRST" -> R.string.analytics_daily_report_sensor_lag_rollout_shadow
        "HOLD" -> R.string.analytics_daily_report_sensor_lag_rollout_hold
        else -> R.string.analytics_daily_report_sensor_lag_rollout_insufficient
    }
}

private fun overviewSensorLagBucketLabelResId(bucket: String): Int {
    return when (bucket) {
        "<24h" -> R.string.analytics_daily_report_sensor_lag_bucket_lt24h
        "1-10d" -> R.string.analytics_daily_report_sensor_lag_bucket_1_10d
        "10-12d" -> R.string.analytics_daily_report_sensor_lag_bucket_10_12d
        "12-14d" -> R.string.analytics_daily_report_sensor_lag_bucket_12_14d
        else -> R.string.analytics_daily_report_sensor_lag_bucket_gt14d
    }
}

@Preview(showBackground = true)
@Composable
private fun OverviewScreenPreview() {
    AapsCopilotTheme {
        OverviewScreen(
            state = OverviewUiState(
                loadState = ScreenLoadState.READY,
                isStale = true,
                glucose = 8.7,
                rawGlucose = 8.5,
                calibratedGlucose = 8.7,
                correctedGlucose = 9.0,
                delta = 0.24,
                sampleAgeMinutes = 7,
                isProMode = true,
                calibrationGain = 1.012,
                calibrationOffsetMmol = 0.22,
                calibrationConfidence = 0.74,
                calibrationModelType = "OFFSET",
                calibrationStatus = "ACTIVE",
                calibrationLastCheckAgeMinutes = 42.0,
                sensorLagRolloutVerdict = SensorLagRolloutVerdictUi(
                    status = "SHADOW_FIRST",
                    bucket = "10-12d"
                ),
                horizons = listOf(
                    HorizonPredictionUi(5, 8.92, 8.60, 9.22),
                    HorizonPredictionUi(30, 9.78, 8.10, 10.95),
                    HorizonPredictionUi(60, 10.26, 8.05, 12.12, warningWideCi = true)
                ),
                uamActive = true,
                uci0Mmol5m = 0.18,
                inferredCarbsLast60g = 24.0,
                uamModeLabel = "BOOST",
                telemetryChips = listOf(
                    TelemetryChipUi("IOB", "1.8", "U"),
                    TelemetryChipUi("COB", "22", "g"),
                    TelemetryChipUi("Activity", "1.16", null),
                    TelemetryChipUi("Steps", "1234", null)
                ),
                lastAction = LastActionUi(
                    type = "temp_target",
                    status = "SENT",
                    timestamp = System.currentTimeMillis(),
                    tempTargetMmol = 5.2,
                    durationMinutes = 30,
                    idempotencyKey = "tt:bucket:2026-03-02T12:40"
                )
            ),
            onRunCycleNow = {},
            onSetKillSwitch = {},
            onDisablePowerSave = {},
            onAddBloodCheck = { _, _, _, _ -> },
            onOpenClinicalReport = {}
        )
    }
}
