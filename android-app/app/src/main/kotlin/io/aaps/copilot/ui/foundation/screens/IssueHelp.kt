package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.data.local.entity.EnergyProfileSnapshotEntity
import io.aaps.copilot.data.repository.TargetCommandPreflightFailure
import io.aaps.copilot.domain.target.TargetDecisionOutcome
import io.aaps.copilot.domain.profile.EnergyProfilePolicy
import io.aaps.copilot.domain.profile.EnergyProfileSettings
import io.aaps.copilot.domain.profile.ProfileField
import io.aaps.copilot.domain.profile.ProfileResolution
import io.aaps.copilot.domain.profile.ProfileViolation
import java.time.LocalDate

enum class IssueDestination(val labelRes: Int) {
    USER_PROFILE(R.string.energy_activity_user_profile),
    ENERGY_GOAL(R.string.energy_activity_energy_and_ai),
    ENERGY_PROFILE(R.string.energy_activity_title),
    ACTIVITY_SCHEDULE(R.string.energy_activity_and_schedule),
    DATA_SOURCES(R.string.issue_destination_data),
    TARGET_MANAGER(R.string.overview_target_manager_status_title),
    ALERT_SETTINGS(R.string.issue_destination_alert_settings),
    SAFETY(R.string.nav_safety),
    AUDIT(R.string.menu_audit_log),
    ANALYTICS(R.string.menu_analytics),
    SENSOR_ANALYTICS(R.string.issue_destination_sensor),
    ALERTS(R.string.nav_alerts),
    POWER_SAVE(R.string.issue_destination_power)
}

enum class UiIssueKind(val explanationRes: Int, val destination: IssueDestination) {
    BIRTH_DATE_MISSING(R.string.issue_birth_missing, IssueDestination.USER_PROFILE),
    BIRTH_DATE_INVALID(R.string.issue_birth_invalid, IssueDestination.USER_PROFILE),
    HEIGHT_MISSING(R.string.issue_height_missing, IssueDestination.USER_PROFILE),
    HEIGHT_INVALID(R.string.issue_height_invalid, IssueDestination.USER_PROFILE),
    WEIGHT_MISSING(R.string.issue_weight_missing, IssueDestination.USER_PROFILE),
    WEIGHT_INVALID(R.string.issue_weight_invalid, IssueDestination.USER_PROFILE),
    CALORIES_MISSING(R.string.issue_calories_missing, IssueDestination.ENERGY_GOAL),
    CALORIES_INVALID(R.string.issue_calories_invalid, IssueDestination.ENERGY_GOAL),
    CALORIES_RESTRICTED(R.string.issue_calories_restricted, IssueDestination.ENERGY_GOAL),
    PROFILE_SNAPSHOT_MISSING(R.string.issue_snapshot_missing, IssueDestination.ENERGY_PROFILE),
    PROFILE_SNAPSHOT_STALE(R.string.issue_snapshot_stale, IssueDestination.ENERGY_PROFILE),
    DATA_STALE(R.string.issue_data_stale, IssueDestination.DATA_SOURCES),
    DATA_SOURCE_PENDING(R.string.issue_data_source_pending, IssueDestination.DATA_SOURCES),
    SENSOR(R.string.issue_sensor, IssueDestination.SENSOR_ANALYTICS),
    KILL_SWITCH(R.string.issue_kill_switch, IssueDestination.SAFETY),
    POWER_SAVE(R.string.issue_power_save, IssueDestination.POWER_SAVE),
    PUMP_LINK(R.string.pump_link_details, IssueDestination.SAFETY),
    AI_TUNING(R.string.issue_ai_tuning, IssueDestination.AUDIT),
    DELIVERY(R.string.issue_delivery, IssueDestination.DATA_SOURCES),
    FORECAST(R.string.issue_forecast, IssueDestination.ANALYTICS),
    TARGET_MANAGER(R.string.issue_target_manager, IssueDestination.TARGET_MANAGER),
    TARGET_GUARD(R.string.issue_target_guard, IssueDestination.SAFETY),
    TARGET_WAIT(R.string.issue_target_wait, IssueDestination.AUDIT),
    TARGET_RETAINED(R.string.issue_target_retained, IssueDestination.SAFETY),
    NO_PROPOSAL(R.string.issue_no_proposal, IssueDestination.ANALYTICS),
    GLUCOSE_ALERT(R.string.issue_glucose_alert, IssueDestination.ALERTS),
    ALERT_AUDIO(R.string.issue_alert_audio, IssueDestination.ALERT_SETTINGS),
    OPERATION_MESSAGE(R.string.issue_operation_message, IssueDestination.AUDIT),
    GENERIC_ERROR(R.string.issue_generic_error, IssueDestination.AUDIT)
}

// Navigation only: no writer, retry, setting mutation or clinical authorization.
val LocalIssueNavigator = staticCompositionLocalOf<((IssueDestination) -> Unit)?> { null }

data class IssueSettingsRequest(val token: Int, val destination: IssueDestination)
data class IssueSettingsLocation(val advanced: Boolean, val itemIndex: Int, val editor: IssueDestination? = null)

fun issueSettingsLocation(destination: IssueDestination): IssueSettingsLocation? = when (destination) {
    IssueDestination.USER_PROFILE, IssueDestination.ENERGY_GOAL, IssueDestination.ACTIVITY_SCHEDULE ->
        IssueSettingsLocation(advanced = true, itemIndex = 2, editor = destination)
    IssueDestination.ENERGY_PROFILE -> IssueSettingsLocation(advanced = true, itemIndex = 2)
    IssueDestination.TARGET_MANAGER -> IssueSettingsLocation(advanced = true, itemIndex = 4)
    IssueDestination.DATA_SOURCES -> IssueSettingsLocation(advanced = false, itemIndex = 1)
    IssueDestination.ALERT_SETTINGS -> IssueSettingsLocation(advanced = false, itemIndex = 4)
    else -> null
}

internal fun energyProfileIssues(
    settings: EnergyProfileSettings,
    snapshot: EnergyProfileSnapshotEntity?,
    today: LocalDate,
    policy: EnergyProfilePolicy = EnergyProfilePolicy()
): List<UiIssueKind> {
    if (!settings.enabled) return emptyList()
    val fields = when (val resolution = policy.resolve(settings, today)) {
        is ProfileResolution.Complete -> emptyList()
        is ProfileResolution.Incomplete -> resolution.missing.mapNotNull { field ->
            when (field) {
                ProfileField.BIRTH_DATE -> UiIssueKind.BIRTH_DATE_MISSING
                ProfileField.HEIGHT_CM -> UiIssueKind.HEIGHT_MISSING
                ProfileField.WEIGHT_KG -> UiIssueKind.WEIGHT_MISSING
                ProfileField.MANUAL_CALORIE_TARGET_KCAL -> UiIssueKind.CALORIES_MISSING
                ProfileField.PHYSIOLOGICAL_SEX -> null
            }
        }
        is ProfileResolution.Invalid -> resolution.violations.map { violation ->
            when (violation) {
                ProfileViolation.AGE_OUT_OF_RANGE -> UiIssueKind.BIRTH_DATE_INVALID
                ProfileViolation.HEIGHT_OUT_OF_RANGE -> UiIssueKind.HEIGHT_INVALID
                ProfileViolation.WEIGHT_OUT_OF_RANGE -> UiIssueKind.WEIGHT_INVALID
                ProfileViolation.MANUAL_CALORIE_TARGET_OUT_OF_RANGE -> UiIssueKind.CALORIES_INVALID
                ProfileViolation.PEDIATRIC_CALORIE_GOAL_RESTRICTED -> UiIssueKind.CALORIES_RESTRICTED
            }
        }
    }
    // Resolve editable fields first. A missing snapshot must not hide them or
    // suggest changing valid personal data merely to make a diagnostic disappear.
    if (fields.isNotEmpty()) return fields
    return when {
        snapshot == null -> listOf(UiIssueKind.PROFILE_SNAPSHOT_MISSING)
        snapshot.stale -> listOf(UiIssueKind.PROFILE_SNAPSHOT_STALE)
        else -> emptyList()
    }
}

@Composable
fun IssueHelpDialog(
    title: String,
    issues: List<UiIssueKind>,
    navigateOverride: ((IssueDestination) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val rootNavigate = LocalIssueNavigator.current
    val navigate = rootNavigate ?: navigateOverride
    val reasons = issues.ifEmpty { listOf(UiIssueKind.GENERIC_ERROR) }.distinct()
    val rawMessage = reasons.any { it == UiIssueKind.GENERIC_ERROR || it == UiIssueKind.OPERATION_MESSAGE }
    AlertDialog(
        modifier = Modifier.testTag("issue_help_dialog"),
        onDismissRequest = onDismiss,
        title = { Text(if (rawMessage) stringResource(R.string.issue_details) else title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (rawMessage) {
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                }
                reasons.forEach { issue ->
                    Text(stringResource(issue.explanationRes), style = MaterialTheme.typography.bodyMedium)
                }
                if (navigate != null) {
                    val destinations = reasons.map { it.destination } + if (rootNavigate == null) emptyList() else listOf(IssueDestination.AUDIT)
                    destinations.distinct().forEach { destination ->
                        TextButton(
                            modifier = Modifier.testTag("issue_open_${destination.name}"),
                            onClick = { onDismiss(); navigate(destination) }
                        ) {
                            Text(stringResource(R.string.issue_open_destination, stringResource(destination.labelRes)))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

@Composable
internal fun Modifier.issueHelp(title: String, issue: UiIssueKind): Modifier {
    var showHelp by remember { mutableStateOf(false) }
    if (showHelp) IssueHelpDialog(title, listOf(issue)) { showHelp = false }
    return clickable(onClickLabel = stringResource(R.string.issue_how_to_resolve)) { showHelp = true }
}

internal fun targetManagerIssue(status: TargetManagerLiveStatusUi): UiIssueKind = when (status.availability) {
    TargetManagerLiveStatusAvailabilityUi.PAUSED -> UiIssueKind.TARGET_MANAGER
    TargetManagerLiveStatusAvailabilityUi.WAITING -> UiIssueKind.TARGET_WAIT
    TargetManagerLiveStatusAvailabilityUi.STALE -> UiIssueKind.DATA_STALE
    TargetManagerLiveStatusAvailabilityUi.UNAVAILABLE -> UiIssueKind.GENERIC_ERROR
    TargetManagerLiveStatusAvailabilityUi.CURRENT -> when (targetManagerReasonUiKind(status.reason)) {
        TargetManagerReasonUiKind.STALE_DATA -> UiIssueKind.DATA_STALE
        TargetManagerReasonUiKind.SENSOR -> UiIssueKind.SENSOR
        TargetManagerReasonUiKind.DELIVERY, TargetManagerReasonUiKind.DELIVERY_FAILED -> UiIssueKind.DELIVERY
        TargetManagerReasonUiKind.KILL_SWITCH -> UiIssueKind.KILL_SWITCH
        TargetManagerReasonUiKind.PAUSED, TargetManagerReasonUiKind.THERAPY_WRITES_DISABLED -> UiIssueKind.TARGET_MANAGER
        TargetManagerReasonUiKind.FORECAST, TargetManagerReasonUiKind.IOB_UNQUALIFIED -> UiIssueKind.FORECAST
        TargetManagerReasonUiKind.PROTECTIVE, TargetManagerReasonUiKind.SAFETY_BOUNDS -> UiIssueKind.TARGET_GUARD
        TargetManagerReasonUiKind.PREFLIGHT_BLOCKED -> when (status.reason?.let(TargetCommandPreflightFailure::fromReason)?.outcome) {
            TargetDecisionOutcome.BLOCK_FORECAST_RELIABILITY -> UiIssueKind.FORECAST
            TargetDecisionOutcome.BLOCK_STALE_DATA -> UiIssueKind.DATA_STALE
            TargetDecisionOutcome.BLOCK_DELIVERY_TRUST -> UiIssueKind.TARGET_MANAGER
            TargetDecisionOutcome.BLOCK_MANUAL_TARGET -> UiIssueKind.TARGET_RETAINED
            else -> UiIssueKind.TARGET_GUARD
        }
        TargetManagerReasonUiKind.EXTERNAL_TARGET, TargetManagerReasonUiKind.EXTERNAL_WRITER_CONFLICT,
        TargetManagerReasonUiKind.EATING_SOON_ACTIVE, TargetManagerReasonUiKind.MANUAL_TARGET_RETAINED -> UiIssueKind.TARGET_RETAINED
        TargetManagerReasonUiKind.NO_PROPOSAL -> if (status.outcome == "NO_PROPOSAL" || status.reason in setOf("no_proposal", "no_eligible_proposal")) {
            UiIssueKind.NO_PROPOSAL
        } else UiIssueKind.GENERIC_ERROR
        else -> UiIssueKind.TARGET_WAIT
    }
}
