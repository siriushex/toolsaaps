package io.aaps.copilot.ui.foundation.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.util.LocaleFriendlyDecimalParser
import io.aaps.copilot.domain.profile.ActivityProfile
import io.aaps.copilot.domain.profile.ActivityProfileMode
import io.aaps.copilot.domain.profile.ActivityScheduleEngine
import io.aaps.copilot.domain.profile.CalorieGoalMode
import io.aaps.copilot.domain.profile.EnergyProfilePolicy
import io.aaps.copilot.domain.profile.EnergyProfileSettings
import io.aaps.copilot.domain.profile.ProfileResolution
import io.aaps.copilot.domain.profile.FoodProfileMode
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.profile.PlannedActivityIntensity
import io.aaps.copilot.domain.profile.PlannedActivitySchedule
import io.aaps.copilot.domain.profile.PlannedActivityType
import io.aaps.copilot.domain.profile.PhysiologicalSex
import io.aaps.copilot.domain.profile.ScheduleValidation
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** Compact, opt-in settings. It does not invoke a therapy or target writer. */
@Composable
fun EnergyActivitySettingsSection(
    state: EnergyProfileSettingsUiState,
    onEnabledChange: (Boolean) -> Unit,
    onSaveUserProfile: (UserProfileDraftUi, EnergyProfileSaveCompletion) -> Unit,
    onSaveFoodSettings: (FoodProfileSettingsUi, EnergyProfileSaveCompletion) -> Unit,
    onSaveActivitySettings: (ActivityProfileSettingsUi, EnergyProfileSaveCompletion) -> Unit,
    onSaveEvent: (PlannedActivityEventUi, EnergyProfileSaveCompletion) -> Unit,
    onDeleteEvent: (String) -> Unit,
    onSaveEnergySettings: (EnergyGoalSettingsUi, EnergyProfileSaveCompletion) -> Unit,
    onSaveProfileCorrection: ((UserProfileDraftUi, EnergyGoalSettingsUi, EnergyProfileSaveCompletion) -> Unit)? = null,
    openPlannedActivityRequest: Int = 0,
    editorRequest: IssueDestination? = null,
    onEditorRequestHandled: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var dialog by rememberSaveable { mutableStateOf<EnergyActivityDialog?>(null) }
    val calorieEditor = if (onSaveProfileCorrection != null &&
        needsCalorieCorrection(state.energyGoalSettings) && !userProfileDraftComplete(state.userProfile)
    ) EnergyActivityDialog.USER else EnergyActivityDialog.ENERGY
    LaunchedEffect(editorRequest) {
        if (editorRequest != null) {
            dialog = when (editorRequest) {
                IssueDestination.USER_PROFILE -> EnergyActivityDialog.USER
                IssueDestination.ENERGY_GOAL -> calorieEditor
                IssueDestination.ACTIVITY_SCHEDULE -> EnergyActivityDialog.ACTIVITY
                else -> null
            }
            onEditorRequestHandled()
        }
    }
    LaunchedEffect(openPlannedActivityRequest) {
        if (openPlannedActivityRequest > 0) {
            dialog = EnergyActivityDialog.ACTIVITY
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.energy_activity_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = state.completeness,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = state.enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.testTag("energy-profile-enabled")
            )
        }
        EnergyProfileRow(
            title = stringResource(R.string.energy_activity_user_profile),
            detail = state.derivedAgeYears?.let { stringResource(R.string.energy_activity_age_years, it) }
                ?: stringResource(R.string.energy_activity_not_configured),
            onClick = { dialog = EnergyActivityDialog.USER }
        )
        EnergyProfileRow(
            title = stringResource(R.string.energy_activity_food_absorption),
            detail = summaryText(state.foodSummary),
            onClick = { dialog = EnergyActivityDialog.FOOD }
        )
        EnergyProfileRow(
            title = stringResource(R.string.energy_activity_and_schedule),
            detail = summaryText(state.activitySummary),
            onClick = { dialog = EnergyActivityDialog.ACTIVITY }
        )
        EnergyProfileRow(
            title = stringResource(R.string.energy_activity_energy_and_ai),
            detail = state.calorieSummary,
            onClick = { dialog = calorieEditor }
        )
        state.validationMessage?.takeIf { dialog == null }?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp).issueHelp(message, UiIssueKind.GENERIC_ERROR)
            )
        }
    }
    when (dialog) {
        EnergyActivityDialog.USER -> UserProfileDialog(
            initial = state.userProfile,
            calorieCorrection = state.energyGoalSettings.takeIf {
                onSaveProfileCorrection != null && needsCalorieCorrection(it)
            },
            onDismiss = { dialog = null },
            onSave = onSaveUserProfile,
            onSaveCorrection = { draft, calories, done ->
                onSaveProfileCorrection?.invoke(draft, calories, done)
                    ?: done(EnergyProfileSaveResult.Failed())
            }
        )
        EnergyActivityDialog.FOOD -> FoodProfileDialog(
            initial = state.foodSettings,
            summary = state.foodSummary,
            onDismiss = { dialog = null },
            onSave = onSaveFoodSettings
        )
        EnergyActivityDialog.ACTIVITY -> ActivityProfileDialog(
            initial = state.activitySettings,
            summary = state.activitySummary,
            events = state.plannedEvents,
            validationMessage = state.validationMessage,
            onDismiss = { dialog = null },
            onSave = onSaveActivitySettings,
            onSaveEvent = onSaveEvent,
            onDeleteEvent = onDeleteEvent
        )
        EnergyActivityDialog.ENERGY -> EnergyGoalDialog(
            initial = state.energyGoalSettings,
            ageYears = state.derivedAgeYears,
            onDismiss = { dialog = null },
            onSave = onSaveEnergySettings
        )
        null -> Unit
    }
}

private enum class EnergyActivityDialog { USER, FOOD, ACTIVITY, ENERGY }

internal fun needsCalorieCorrection(goal: EnergyGoalSettingsUi): Boolean =
    (goal.mode == CalorieGoalMode.MANUAL_CLINICIAN && goal.manualTargetKcal == null) ||
        (goal.manualTargetKcal != null && goal.manualTargetKcal !in 500..10_000)

internal fun userProfileDraftComplete(draft: UserProfileDraftUi, today: LocalDate = LocalDate.now()): Boolean =
    EnergyProfilePolicy().resolve(EnergyProfileSettings(
        birthDateEpochDay = draft.birthDateEpochDay,
        physiologicalSex = draft.physiologicalSex,
        heightCm = draft.heightCm,
        weightKg = draft.weightKg
    ), today) is ProfileResolution.Complete

@Composable
private fun EnergyProfileRow(title: String, detail: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

@Composable
private fun UserProfileDialog(
    initial: UserProfileDraftUi,
    onDismiss: () -> Unit,
    onSave: (UserProfileDraftUi, EnergyProfileSaveCompletion) -> Unit,
    calorieCorrection: EnergyGoalSettingsUi? = null,
    onSaveCorrection: (UserProfileDraftUi, EnergyGoalSettingsUi, EnergyProfileSaveCompletion) -> Unit = { _, _, done -> done(EnergyProfileSaveResult.Failed()) }
) {
    val context = LocalContext.current
    val save = rememberProfileFormSaveState(onDismiss)
    var birthDate by rememberSaveable { mutableStateOf(initial.birthDateEpochDay?.let { runCatching { LocalDate.ofEpochDay(it) }.getOrNull() }) }
    var sex by rememberSaveable { mutableStateOf(initial.physiologicalSex) }
    var height by rememberSaveable { mutableStateOf(initial.heightCm?.toString().orEmpty()) }
    var weight by rememberSaveable { mutableStateOf(initial.weightKg?.toString().orEmpty()) }
    var calories by rememberSaveable { mutableStateOf(calorieCorrection?.manualTargetKcal?.toString().orEmpty()) }
    val heightValue = LocaleFriendlyDecimalParser.parse(height)?.value
    val weightValue = LocaleFriendlyDecimalParser.parse(weight)?.value
    val birthValid = birthDate?.let { ageOn(it) in 3..120 } == true
    val heightValid = heightValue?.let { it in 80.0..230.0 } == true
    val weightValid = weightValue?.let { it in 10.0..350.0 } == true
    val calorieValid = calorieCorrection == null ||
        (calories.isBlank() && calorieCorrection.mode != CalorieGoalMode.MANUAL_CLINICIAN) ||
        calories.toIntOrNull()?.let { it in 500..10_000 } == true
    val valid = birthValid && heightValid && weightValid && calorieValid
    AlertDialog(
        onDismissRequest = { if (!save.busy) onDismiss() },
        title = { Text(stringResource(R.string.energy_activity_user_profile)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).testTag("issue_user_profile_form"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !save.busy, modifier = Modifier.testTag("issue_profile_birth_date"), onClick = {
                    val base = birthDate ?: LocalDate.now().minusYears(30)
                    DatePickerDialog(context, { _, year, month, day ->
                        birthDate = LocalDate.of(year, month + 1, day)
                    }, base.year, base.monthValue - 1, base.dayOfMonth).show()
                }) {
                    Text(birthDate?.toString() ?: stringResource(R.string.energy_activity_birth_date))
                }
                birthDate?.let { Text(stringResource(R.string.energy_activity_age_years, ageOn(it))) }
                if (!birthValid) Text(stringResource(if (birthDate == null) R.string.issue_birth_missing else R.string.issue_birth_invalid), color = MaterialTheme.colorScheme.error)
                EnumSelector(
                    options = PhysiologicalSex.entries.toList(),
                    selected = sex,
                    label = { stringResource(when (it) {
                        PhysiologicalSex.MALE -> R.string.issue_sex_male
                        PhysiologicalSex.FEMALE -> R.string.issue_sex_female
                        PhysiologicalSex.UNSPECIFIED -> R.string.issue_sex_unspecified
                    }) },
                    useMenu = true,
                    enabled = !save.busy,
                    onSelected = { sex = it }
                )
                OutlinedTextField(height, { height = it }, label = { Text(stringResource(R.string.energy_activity_height_cm)) },
                    enabled = !save.busy, singleLine = true, isError = !heightValid, modifier = Modifier.testTag("issue_profile_height"),
                    supportingText = { if (!heightValid) Text(stringResource(if (height.isBlank()) R.string.issue_height_missing else R.string.issue_height_invalid)) })
                OutlinedTextField(weight, { weight = it }, label = { Text(stringResource(R.string.energy_activity_weight_kg)) },
                    enabled = !save.busy, singleLine = true, isError = !weightValid, modifier = Modifier.testTag("issue_profile_weight"),
                    supportingText = { if (!weightValid) Text(stringResource(if (weight.isBlank()) R.string.issue_weight_missing else R.string.issue_weight_invalid)) })
                if (calorieCorrection != null) {
                    Text(stringResource(R.string.issue_joint_profile_correction), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(calories, { calories = it }, label = { Text(stringResource(R.string.energy_activity_kcal)) },
                        enabled = !save.busy, singleLine = true, isError = !calorieValid, modifier = Modifier.testTag("issue_joint_calories"),
                        supportingText = { if (!calorieValid) Text(stringResource(if (calories.isBlank()) R.string.issue_calories_missing else R.string.issue_calories_invalid)) })
                }
                ProfileSaveError(save)
            }
        },
        confirmButton = {
            Button(modifier = Modifier.testTag("profile_save_confirm"), enabled = valid && !save.busy, onClick = {
                val draft = UserProfileDraftUi(birthDate?.toEpochDay(), sex, heightValue, weightValue)
                save.submit { done ->
                    if (calorieCorrection == null) onSave(draft, done)
                    else onSaveCorrection(draft, calorieCorrection.copy(manualTargetKcal = calories.toIntOrNull()), done)
                }
            }) { ProfileSaveButtonContent(save.busy) }
        },
        dismissButton = { TextButton(enabled = !save.busy, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

@Composable
private fun FoodProfileDialog(
    initial: FoodProfileSettingsUi,
    summary: ResolvedProfileSummaryUi,
    onDismiss: () -> Unit,
    onSave: (FoodProfileSettingsUi, EnergyProfileSaveCompletion) -> Unit
) {
    val save = rememberProfileFormSaveState(onDismiss)
    var mode by rememberSaveable { mutableStateOf(initial.mode) }
    var profile by rememberSaveable { mutableStateOf(initial.manualProfile) }
    AlertDialog(
        onDismissRequest = { if (!save.busy) onDismiss() },
        title = { Text(stringResource(R.string.energy_activity_food_absorption)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).testTag("issue_food_profile_form"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EvidenceSummary(summary)
                EnumSelector(FoodProfileMode.entries.toList(), mode, { it.name.lowercase().replaceFirstChar(Char::titlecase) }, enabled = !save.busy) { mode = it }
                if (mode == FoodProfileMode.MANUAL) FoodProfileSelector(profile, { if (!save.busy) profile = it })
                ProfileSaveError(save)
            }
        },
        confirmButton = { Button(modifier = Modifier.testTag("profile_save_confirm"), enabled = !save.busy, onClick = { save.submit { done -> onSave(FoodProfileSettingsUi(mode, profile), done) } }) { ProfileSaveButtonContent(save.busy) } },
        dismissButton = { TextButton(enabled = !save.busy, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

@Composable
private fun ActivityProfileDialog(
    initial: ActivityProfileSettingsUi,
    summary: ResolvedProfileSummaryUi,
    events: List<PlannedActivityEventUi>,
    validationMessage: String?,
    onDismiss: () -> Unit,
    onSave: (ActivityProfileSettingsUi, EnergyProfileSaveCompletion) -> Unit,
    onSaveEvent: (PlannedActivityEventUi, EnergyProfileSaveCompletion) -> Unit,
    onDeleteEvent: (String) -> Unit
) {
    val save = rememberProfileFormSaveState(onDismiss)
    var mode by rememberSaveable { mutableStateOf(initial.mode) }
    var profile by rememberSaveable { mutableStateOf(initial.manualProfile) }
    var forecastEnabled by rememberSaveable { mutableStateOf(initial.forecastInfluenceEnabled) }
    var editing by remember { mutableStateOf<PlannedActivityEventUi?>(null) }
    AlertDialog(
        onDismissRequest = { if (!save.busy) onDismiss() },
        title = { Text(stringResource(R.string.energy_activity_and_schedule)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).testTag("issue_activity_profile_form"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EvidenceSummary(summary)
                EnumSelector(ActivityProfileMode.entries.toList(), mode, { it.name.lowercase().replaceFirstChar(Char::titlecase) }, enabled = !save.busy) { mode = it }
                if (mode == ActivityProfileMode.MANUAL) {
                    EnumSelector(ActivityProfile.entries.toList(), profile, { it.name.lowercase().replaceFirstChar(Char::titlecase) }, enabled = !save.busy) { profile = it }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.energy_activity_forecast_influence), modifier = Modifier.weight(1f))
                    Switch(forecastEnabled, { forecastEnabled = it }, enabled = !save.busy)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.energy_activity_schedule), modifier = Modifier.weight(1f))
                    val defaultTitle = stringResource(R.string.energy_activity_default_title)
                    ActivityScheduleIconButton(
                        icon = Icons.Default.Add,
                        label = stringResource(R.string.energy_activity_add_event),
                        onClick = { if (!save.busy) editing = newPlannedEvent(defaultTitle) }
                    )
                }
                events.forEach { event ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${event.title} · ${event.localStartIso}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        ActivityScheduleIconButton(
                            icon = Icons.Default.Edit,
                            label = stringResource(R.string.energy_activity_edit_event),
                            onClick = { if (!save.busy) editing = event }
                        )
                        ActivityScheduleIconButton(
                            icon = Icons.Default.Delete,
                            label = stringResource(R.string.energy_activity_delete_event),
                            onClick = { if (!save.busy) onDeleteEvent(event.eventId) }
                        )
                    }
                }
                validationMessage?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.issueHelp(message, UiIssueKind.GENERIC_ERROR))
                }
                ProfileSaveError(save)
            }
        },
        confirmButton = { Button(modifier = Modifier.testTag("profile_save_confirm"), enabled = !save.busy, onClick = { save.submit { done -> onSave(ActivityProfileSettingsUi(mode, profile, forecastEnabled), done) } }) { ProfileSaveButtonContent(save.busy) } },
        dismissButton = { TextButton(enabled = !save.busy, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
    editing?.let { event ->
        PlannedEventDialog(
            event = event,
            existingEvents = events,
            onDismiss = { editing = null },
            onSave = onSaveEvent
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityScheduleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)
        ) {
            Icon(icon, label)
        }
    }
}

@Composable
private fun PlannedEventDialog(
    event: PlannedActivityEventUi,
    existingEvents: List<PlannedActivityEventUi>,
    onDismiss: () -> Unit,
    onSave: (PlannedActivityEventUi, EnergyProfileSaveCompletion) -> Unit
) {
    val save = rememberProfileFormSaveState(onDismiss)
    var enabled by rememberSaveable { mutableStateOf(event.enabled) }
    var title by rememberSaveable { mutableStateOf(event.title) }
    var type by rememberSaveable { mutableStateOf(event.activityType) }
    var intensity by rememberSaveable { mutableStateOf(event.intensity) }
    var start by rememberSaveable { mutableStateOf(event.localStartIso) }
    var duration by rememberSaveable { mutableStateOf(event.durationMinutes.toString()) }
    var timezoneId by rememberSaveable { mutableStateOf(event.timezoneId) }
    var recurrenceMask by rememberSaveable { mutableStateOf(event.recurrenceDaysMask) }
    var recurrenceEnd by rememberSaveable {
        mutableStateOf(event.recurrenceEndEpochDay?.let(LocalDate::ofEpochDay)?.toString().orEmpty())
    }
    val selectedType = runCatching { PlannedActivityType.valueOf(type) }.getOrDefault(PlannedActivityType.AEROBIC)
    val selectedIntensity = runCatching { PlannedActivityIntensity.valueOf(intensity) }
        .getOrDefault(PlannedActivityIntensity.MEDIUM)
    val recurrenceEndEpochDay = recurrenceEnd.trim().takeIf(String::isNotEmpty)
        ?.let { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() }
    val recurrenceEndIsValid = recurrenceEnd.isBlank() || recurrenceEndEpochDay != null
    val edited = event.copy(
        enabled = enabled,
        title = title.trim(),
        activityType = type,
        intensity = intensity,
        localStartIso = start,
        durationMinutes = duration.toIntOrNull() ?: 0,
        timezoneId = timezoneId.trim(),
        recurrenceDaysMask = recurrenceMask,
        recurrenceEndEpochDay = recurrenceEndEpochDay
    )
    val validation = edited.toScheduleOrNull()?.let { candidate ->
        ActivityScheduleEngine().validate(
            existingEvents.filterNot { it.eventId == event.eventId }
                .mapNotNull(PlannedActivityEventUi::toScheduleOrNull) + candidate
        )
    }
    val valid = recurrenceEndIsValid && validation == ScheduleValidation.Valid
    AlertDialog(
        onDismissRequest = { if (!save.busy) onDismiss() },
        title = { Text(stringResource(R.string.energy_activity_schedule)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).testTag("planned_activity_form"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.energy_activity_event_enabled), modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !save.busy)
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.energy_activity_event_title)) },
                    singleLine = true,
                    enabled = !save.busy,
                    modifier = Modifier.testTag("planned_activity_title")
                )
                EnumSelector(
                    PlannedActivityType.entries.toList(),
                    selectedType,
                    ::plannedActivityTypeLabel, useMenu = true, enabled = !save.busy
                ) { type = it.name }
                EnumSelector(
                    PlannedActivityIntensity.entries.toList(),
                    selectedIntensity,
                    ::plannedActivityIntensityLabel, useMenu = true, enabled = !save.busy
                ) { intensity = it.name }
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text(stringResource(R.string.energy_activity_start_iso)) },
                    singleLine = true,
                    enabled = !save.busy,
                    modifier = Modifier.testTag("planned_activity_start")
                )
                OutlinedTextField(
                    value = duration,
                    onValueChange = { duration = it },
                    label = { Text(stringResource(R.string.energy_activity_duration_minutes)) },
                    singleLine = true,
                    enabled = !save.busy,
                    modifier = Modifier.testTag("planned_activity_duration")
                )
                OutlinedTextField(
                    value = timezoneId,
                    onValueChange = { timezoneId = it },
                    label = { Text(stringResource(R.string.energy_activity_timezone)) },
                    singleLine = true,
                    enabled = !save.busy,
                    modifier = Modifier.testTag("planned_activity_timezone")
                )
                Text(stringResource(R.string.energy_activity_repeat_days), style = MaterialTheme.typography.labelLarge)
                DayOfWeek.entries.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { day ->
                            val bit = 1 shl (day.value - 1)
                            FilterChip(
                                enabled = !save.busy,
                                selected = recurrenceMask and bit != 0,
                                onClick = { recurrenceMask = recurrenceMask xor bit },
                                label = { Text(dayOfWeekLabel(day)) },
                                modifier = Modifier
                                    .heightIn(min = 44.dp)
                                    .testTag("planned_activity_day_${day.name}")
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = recurrenceEnd,
                    onValueChange = { recurrenceEnd = it },
                    label = { Text(stringResource(R.string.energy_activity_repeat_until)) },
                    singleLine = true,
                    enabled = !save.busy,
                    modifier = Modifier.testTag("planned_activity_repeat_until")
                )
                if (!valid) {
                    Text(
                        text = if (validation is ScheduleValidation.Overlap) {
                            stringResource(R.string.energy_activity_events_overlap)
                        } else {
                            stringResource(R.string.energy_activity_event_validation)
                        },
                        color = MaterialTheme.colorScheme.error
                    )
                }
                ProfileSaveError(save)
            }
        },
        confirmButton = {
            Button(
                enabled = valid && !save.busy,
                modifier = Modifier.testTag("planned_activity_save"),
                onClick = {
                    save.submit { done ->
                        onSave(
                            edited.copy(
                                revision = event.revision + 1,
                                updatedAtMs = System.currentTimeMillis()
                            ), done
                        )
                    }
                }
            ) { ProfileSaveButtonContent(save.busy) }
        },
        dismissButton = { TextButton(enabled = !save.busy, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

@Composable
private fun EnergyGoalDialog(initial: EnergyGoalSettingsUi, ageYears: Int?, onDismiss: () -> Unit, onSave: (EnergyGoalSettingsUi, EnergyProfileSaveCompletion) -> Unit) {
    val save = rememberProfileFormSaveState(onDismiss)
    var mode by rememberSaveable { mutableStateOf(initial.mode) }
    var kcal by rememberSaveable { mutableStateOf(initial.manualTargetKcal?.toString().orEmpty()) }
    var share by rememberSaveable { mutableStateOf(initial.shareProfileWithAi) }
    val pediatricRestricted = ageYears != null && ageYears < 19 && mode in setOf(CalorieGoalMode.LOSS, CalorieGoalMode.GAIN)
    val validCalories = (kcal.isBlank() && mode != CalorieGoalMode.MANUAL_CLINICIAN) || kcal.toIntOrNull() in 500..10_000
    AlertDialog(
        onDismissRequest = { if (!save.busy) onDismiss() },
        title = { Text(stringResource(R.string.energy_activity_energy_and_ai)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).testTag("issue_energy_goal_form"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EnumSelector(CalorieGoalMode.entries.toList(), mode, { stringResource(when (it) {
                    CalorieGoalMode.OFF -> R.string.issue_goal_off
                    CalorieGoalMode.MAINTENANCE -> R.string.issue_goal_maintenance
                    CalorieGoalMode.LOSS -> R.string.issue_goal_loss
                    CalorieGoalMode.GAIN -> R.string.issue_goal_gain
                    CalorieGoalMode.MANUAL_CLINICIAN -> R.string.issue_goal_manual
                }) }, useMenu = true, enabled = !save.busy) { mode = it }
                if (mode == CalorieGoalMode.MANUAL_CLINICIAN || kcal.isNotBlank()) OutlinedTextField(kcal, { kcal = it }, label = { Text(stringResource(R.string.energy_activity_kcal)) },
                    enabled = !save.busy, singleLine = true, isError = !validCalories, modifier = Modifier.testTag("issue_profile_calories"),
                    supportingText = { if (!validCalories) Text(stringResource(if (kcal.isBlank()) R.string.issue_calories_missing else R.string.issue_calories_invalid)) })
                if (pediatricRestricted) Text(stringResource(R.string.energy_activity_pediatric_restriction), color = MaterialTheme.colorScheme.error)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.energy_activity_share_ai), modifier = Modifier.weight(1f))
                    Switch(share, { share = it }, enabled = !save.busy)
                }
                ProfileSaveError(save)
            }
        },
        confirmButton = { Button(modifier = Modifier.testTag("profile_save_confirm"), enabled = !pediatricRestricted && validCalories && !save.busy, onClick = { save.submit { done -> onSave(EnergyGoalSettingsUi(mode, kcal.toIntOrNull(), share), done) } }) { ProfileSaveButtonContent(save.busy) } },
        dismissButton = { TextButton(enabled = !save.busy, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

@Composable
private fun EvidenceSummary(summary: ResolvedProfileSummaryUi) {
    val calculated = summary.calculatedAtMs?.let { timestamp ->
        java.time.Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
    } ?: stringResource(R.string.energy_activity_not_calculated)
    Text(
        stringResource(
            R.string.energy_activity_evidence,
            summary.source,
            summary.confidence.name,
            summary.qualityDays,
            calculated
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun <T> EnumSelector(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    useMenu: Boolean = false,
    enabled: Boolean = true,
    onSelected: (T) -> Unit
) {
    if (useMenu) {
        var expanded by remember { mutableStateOf(false) }
        Column {
            OutlinedButton(enabled = enabled, onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(label(selected), modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(enabled = enabled, text = { Text(label(option)) }, onClick = { expanded = false; onSelected(option) })
                }
            }
        }
        return
    }
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                enabled = enabled,
                selected = selected == option,
                onClick = { onSelected(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.heightIn(min = 44.dp),
                label = { Text(label(option), maxLines = 1) }
            )
        }
    }
}

@Composable
private fun ProfileSaveError(save: ProfileFormSaveState) {
    save.error?.let { failure ->
        val message = failure.message ?: stringResource(R.string.energy_activity_save_failed)
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("profile_save_error").issueHelp(message, UiIssueKind.GENERIC_ERROR))
    }
}

@Composable
private fun ProfileSaveButtonContent(busy: Boolean) {
    val saving = stringResource(R.string.energy_activity_saving)
    Box(contentAlignment = Alignment.Center) {
        // Keep the localized label's dimensions while showing progress, including enlarged fonts.
        Text(stringResource(android.R.string.ok), modifier = if (busy)
            Modifier.alpha(0f).clearAndSetSemantics {} else Modifier)
        if (busy) CircularProgressIndicator(
            modifier = Modifier.size(18.dp).semantics { contentDescription = saving }, strokeWidth = 2.dp)
    }
}

@Composable
private fun summaryText(summary: ResolvedProfileSummaryUi): String =
    "${summary.value} · ${summary.source} · ${summary.qualityDays}d"

private fun ageOn(birthDate: LocalDate): Int = java.time.Period.between(birthDate, LocalDate.now()).years

@Composable
private fun plannedActivityTypeLabel(type: PlannedActivityType): String = stringResource(
    when (type) {
        PlannedActivityType.WALKING -> R.string.energy_activity_type_walking
        PlannedActivityType.AEROBIC -> R.string.energy_activity_type_aerobic
        PlannedActivityType.STRENGTH -> R.string.energy_activity_type_strength
        PlannedActivityType.MIXED -> R.string.energy_activity_type_mixed
    }
)

@Composable
private fun plannedActivityIntensityLabel(intensity: PlannedActivityIntensity): String = stringResource(
    when (intensity) {
        PlannedActivityIntensity.LIGHT -> R.string.energy_activity_intensity_light
        PlannedActivityIntensity.MEDIUM -> R.string.energy_activity_intensity_medium
        PlannedActivityIntensity.HIGH -> R.string.energy_activity_intensity_high
    }
)

@Composable
private fun dayOfWeekLabel(day: DayOfWeek): String = stringResource(
    when (day) {
        DayOfWeek.MONDAY -> R.string.energy_activity_day_mon
        DayOfWeek.TUESDAY -> R.string.energy_activity_day_tue
        DayOfWeek.WEDNESDAY -> R.string.energy_activity_day_wed
        DayOfWeek.THURSDAY -> R.string.energy_activity_day_thu
        DayOfWeek.FRIDAY -> R.string.energy_activity_day_fri
        DayOfWeek.SATURDAY -> R.string.energy_activity_day_sat
        DayOfWeek.SUNDAY -> R.string.energy_activity_day_sun
    }
)

internal fun PlannedActivityEventUi.toScheduleOrNull(): PlannedActivitySchedule? {
    val parsedType = runCatching { PlannedActivityType.valueOf(activityType) }.getOrNull() ?: return null
    val parsedIntensity = runCatching { PlannedActivityIntensity.valueOf(intensity) }.getOrNull() ?: return null
    val parsedStart = runCatching { LocalDateTime.parse(localStartIso) }.getOrNull() ?: return null
    val parsedZone = runCatching { ZoneId.of(timezoneId) }.getOrNull() ?: return null
    val parsedEnd = recurrenceEndEpochDay?.let { epochDay ->
        runCatching { LocalDate.ofEpochDay(epochDay) }.getOrNull() ?: return null
    }
    val recurrenceDays = DayOfWeek.entries.filter { day ->
        recurrenceDaysMask and (1 shl (day.value - 1)) != 0
    }.toSet()
    if (
        eventId.isBlank() ||
        title.isBlank() ||
        durationMinutes <= 0 ||
        revision < 0L ||
        parsedEnd?.isBefore(parsedStart.toLocalDate()) == true
    ) {
        return null
    }
    return PlannedActivitySchedule(
        eventId = eventId.trim(),
        enabled = enabled,
        title = title.trim(),
        type = parsedType,
        intensity = parsedIntensity,
        localStart = parsedStart,
        durationMinutes = durationMinutes,
        timezoneId = parsedZone.id,
        recurrenceDays = recurrenceDays,
        recurrenceEndEpochDay = parsedEnd?.toEpochDay(),
        revision = revision,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs
    )
}

private fun newPlannedEvent(defaultTitle: String): PlannedActivityEventUi = PlannedActivityEventUi(
    eventId = UUID.randomUUID().toString(),
    enabled = true,
    title = defaultTitle,
    activityType = "AEROBIC",
    intensity = "MEDIUM",
    localStartIso = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0).toString(),
    durationMinutes = 60,
    timezoneId = ZoneId.systemDefault().id,
    recurrenceDaysMask = 0,
    recurrenceEndEpochDay = null,
    revision = 0,
    createdAtMs = System.currentTimeMillis(),
    updatedAtMs = System.currentTimeMillis()
)
