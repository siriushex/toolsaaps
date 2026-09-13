package io.aaps.copilot.ui.foundation.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.domain.profile.ActivityProfile
import io.aaps.copilot.domain.profile.ActivityProfileMode
import io.aaps.copilot.domain.profile.ActivityScheduleEngine
import io.aaps.copilot.domain.profile.CalorieGoalMode
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
    onSaveUserProfile: (UserProfileDraftUi) -> Unit,
    onSaveFoodSettings: (FoodProfileSettingsUi) -> Unit,
    onSaveActivitySettings: (ActivityProfileSettingsUi) -> Unit,
    onSaveEvent: (PlannedActivityEventUi) -> Unit,
    onDeleteEvent: (String) -> Unit,
    onSaveEnergySettings: (EnergyGoalSettingsUi) -> Unit,
    openPlannedActivityRequest: Int = 0,
    modifier: Modifier = Modifier
) {
    var dialog by remember { mutableStateOf<EnergyActivityDialog?>(null) }
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
            onClick = { dialog = EnergyActivityDialog.ENERGY }
        )
        state.validationMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
    when (dialog) {
        EnergyActivityDialog.USER -> UserProfileDialog(
            initial = state.userProfile,
            onDismiss = { dialog = null },
            onSave = { onSaveUserProfile(it); dialog = null }
        )
        EnergyActivityDialog.FOOD -> FoodProfileDialog(
            initial = state.foodSettings,
            summary = state.foodSummary,
            onDismiss = { dialog = null },
            onSave = { onSaveFoodSettings(it); dialog = null }
        )
        EnergyActivityDialog.ACTIVITY -> ActivityProfileDialog(
            initial = state.activitySettings,
            summary = state.activitySummary,
            events = state.plannedEvents,
            validationMessage = state.validationMessage,
            onDismiss = { dialog = null },
            onSave = { onSaveActivitySettings(it) },
            onSaveEvent = onSaveEvent,
            onDeleteEvent = onDeleteEvent
        )
        EnergyActivityDialog.ENERGY -> EnergyGoalDialog(
            initial = state.energyGoalSettings,
            ageYears = state.derivedAgeYears,
            onDismiss = { dialog = null },
            onSave = { onSaveEnergySettings(it); dialog = null }
        )
        null -> Unit
    }
}

private enum class EnergyActivityDialog { USER, FOOD, ACTIVITY, ENERGY }

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
    onSave: (UserProfileDraftUi) -> Unit
) {
    val context = LocalContext.current
    var birthDate by remember { mutableStateOf(initial.birthDateEpochDay?.let(LocalDate::ofEpochDay)) }
    var sex by remember { mutableStateOf(initial.physiologicalSex) }
    var height by remember { mutableStateOf(initial.heightCm?.toString().orEmpty()) }
    var weight by remember { mutableStateOf(initial.weightKg?.toString().orEmpty()) }
    val valid = birthDate != null && ageOn(requireNotNull(birthDate)) in 3..120 &&
        height.toDoubleOrNull()?.let { it in 80.0..230.0 } == true &&
        weight.toDoubleOrNull()?.let { it in 10.0..350.0 } == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.energy_activity_user_profile)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val base = birthDate ?: LocalDate.now().minusYears(30)
                    DatePickerDialog(context, { _, year, month, day ->
                        birthDate = LocalDate.of(year, month + 1, day)
                    }, base.year, base.monthValue - 1, base.dayOfMonth).show()
                }) {
                    Text(birthDate?.toString() ?: stringResource(R.string.energy_activity_birth_date))
                }
                birthDate?.let { Text(stringResource(R.string.energy_activity_age_years, ageOn(it))) }
                EnumSelector(
                    options = PhysiologicalSex.entries.toList(),
                    selected = sex,
                    label = { it.name.lowercase().replaceFirstChar(Char::titlecase) },
                    onSelected = { sex = it }
                )
                OutlinedTextField(height, { height = it }, label = { Text(stringResource(R.string.energy_activity_height_cm)) }, singleLine = true)
                OutlinedTextField(weight, { weight = it }, label = { Text(stringResource(R.string.energy_activity_weight_kg)) }, singleLine = true)
                if (!valid) Text(stringResource(R.string.energy_activity_profile_validation), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                onSave(UserProfileDraftUi(birthDate?.toEpochDay(), sex, height.toDoubleOrNull(), weight.toDoubleOrNull()))
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

@Composable
private fun FoodProfileDialog(
    initial: FoodProfileSettingsUi,
    summary: ResolvedProfileSummaryUi,
    onDismiss: () -> Unit,
    onSave: (FoodProfileSettingsUi) -> Unit
) {
    var mode by remember { mutableStateOf(initial.mode) }
    var profile by remember { mutableStateOf(initial.manualProfile) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.energy_activity_food_absorption)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EvidenceSummary(summary)
                EnumSelector(FoodProfileMode.entries.toList(), mode, { it.name.lowercase().replaceFirstChar(Char::titlecase) }) { mode = it }
                if (mode == FoodProfileMode.MANUAL) FoodProfileSelector(profile, { profile = it })
            }
        },
        confirmButton = { Button(onClick = { onSave(FoodProfileSettingsUi(mode, profile)) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

@Composable
private fun ActivityProfileDialog(
    initial: ActivityProfileSettingsUi,
    summary: ResolvedProfileSummaryUi,
    events: List<PlannedActivityEventUi>,
    validationMessage: String?,
    onDismiss: () -> Unit,
    onSave: (ActivityProfileSettingsUi) -> Unit,
    onSaveEvent: (PlannedActivityEventUi) -> Unit,
    onDeleteEvent: (String) -> Unit
) {
    var mode by remember { mutableStateOf(initial.mode) }
    var profile by remember { mutableStateOf(initial.manualProfile) }
    var forecastEnabled by remember { mutableStateOf(initial.forecastInfluenceEnabled) }
    var editing by remember { mutableStateOf<PlannedActivityEventUi?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.energy_activity_and_schedule)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EvidenceSummary(summary)
                EnumSelector(ActivityProfileMode.entries.toList(), mode, { it.name.lowercase().replaceFirstChar(Char::titlecase) }) { mode = it }
                if (mode == ActivityProfileMode.MANUAL) {
                    EnumSelector(ActivityProfile.entries.toList(), profile, { it.name.lowercase().replaceFirstChar(Char::titlecase) }) { profile = it }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.energy_activity_forecast_influence), modifier = Modifier.weight(1f))
                    Switch(forecastEnabled, { forecastEnabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.energy_activity_schedule), modifier = Modifier.weight(1f))
                    val defaultTitle = stringResource(R.string.energy_activity_default_title)
                    ActivityScheduleIconButton(
                        icon = Icons.Default.Add,
                        label = stringResource(R.string.energy_activity_add_event),
                        onClick = { editing = newPlannedEvent(defaultTitle) }
                    )
                }
                events.forEach { event ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${event.title} · ${event.localStartIso}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        ActivityScheduleIconButton(
                            icon = Icons.Default.Edit,
                            label = stringResource(R.string.energy_activity_edit_event),
                            onClick = { editing = event }
                        )
                        ActivityScheduleIconButton(
                            icon = Icons.Default.Delete,
                            label = stringResource(R.string.energy_activity_delete_event),
                            onClick = { onDeleteEvent(event.eventId) }
                        )
                    }
                }
                validationMessage?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(ActivityProfileSettingsUi(mode, profile, forecastEnabled)); onDismiss() }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
    editing?.let { event ->
        PlannedEventDialog(
            event = event,
            existingEvents = events,
            onDismiss = { editing = null },
            onSave = { edited -> onSaveEvent(edited); editing = null }
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
    onSave: (PlannedActivityEventUi) -> Unit
) {
    var enabled by remember { mutableStateOf(event.enabled) }
    var title by remember { mutableStateOf(event.title) }
    var type by remember { mutableStateOf(event.activityType) }
    var intensity by remember { mutableStateOf(event.intensity) }
    var start by remember { mutableStateOf(event.localStartIso) }
    var duration by remember { mutableStateOf(event.durationMinutes.toString()) }
    var timezoneId by remember { mutableStateOf(event.timezoneId) }
    var recurrenceMask by remember { mutableStateOf(event.recurrenceDaysMask) }
    var recurrenceEnd by remember {
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
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.energy_activity_schedule)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.energy_activity_event_enabled), modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.energy_activity_event_title)) },
                    singleLine = true,
                    modifier = Modifier.testTag("planned_activity_title")
                )
                EnumSelector(
                    PlannedActivityType.entries.toList(),
                    selectedType,
                    ::plannedActivityTypeLabel
                ) { type = it.name }
                EnumSelector(
                    PlannedActivityIntensity.entries.toList(),
                    selectedIntensity,
                    ::plannedActivityIntensityLabel
                ) { intensity = it.name }
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text(stringResource(R.string.energy_activity_start_iso)) },
                    singleLine = true,
                    modifier = Modifier.testTag("planned_activity_start")
                )
                OutlinedTextField(
                    value = duration,
                    onValueChange = { duration = it },
                    label = { Text(stringResource(R.string.energy_activity_duration_minutes)) },
                    singleLine = true,
                    modifier = Modifier.testTag("planned_activity_duration")
                )
                OutlinedTextField(
                    value = timezoneId,
                    onValueChange = { timezoneId = it },
                    label = { Text(stringResource(R.string.energy_activity_timezone)) },
                    singleLine = true,
                    modifier = Modifier.testTag("planned_activity_timezone")
                )
                Text(stringResource(R.string.energy_activity_repeat_days), style = MaterialTheme.typography.labelLarge)
                DayOfWeek.entries.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { day ->
                            val bit = 1 shl (day.value - 1)
                            FilterChip(
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
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                modifier = Modifier.testTag("planned_activity_save"),
                onClick = {
                    onSave(
                        edited.copy(
                            revision = event.revision + 1,
                            updatedAtMs = System.currentTimeMillis()
                        )
                    )
                }
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

@Composable
private fun EnergyGoalDialog(initial: EnergyGoalSettingsUi, ageYears: Int?, onDismiss: () -> Unit, onSave: (EnergyGoalSettingsUi) -> Unit) {
    var mode by remember { mutableStateOf(initial.mode) }
    var kcal by remember { mutableStateOf(initial.manualTargetKcal?.toString().orEmpty()) }
    var share by remember { mutableStateOf(initial.shareProfileWithAi) }
    val pediatricRestricted = ageYears != null && ageYears < 19 && mode in setOf(CalorieGoalMode.LOSS, CalorieGoalMode.GAIN)
    val validCalories = mode != CalorieGoalMode.MANUAL_CLINICIAN || kcal.toIntOrNull() in 500..10_000
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.energy_activity_energy_and_ai)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EnumSelector(CalorieGoalMode.entries.toList(), mode, { it.name.lowercase().replaceFirstChar(Char::titlecase) }) { mode = it }
                if (mode == CalorieGoalMode.MANUAL_CLINICIAN) OutlinedTextField(kcal, { kcal = it }, label = { Text(stringResource(R.string.energy_activity_kcal)) }, singleLine = true)
                if (pediatricRestricted) Text(stringResource(R.string.energy_activity_pediatric_restriction), color = MaterialTheme.colorScheme.error)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.energy_activity_share_ai), modifier = Modifier.weight(1f))
                    Switch(share, { share = it })
                }
            }
        },
        confirmButton = { Button(enabled = !pediatricRestricted && validCalories, onClick = { onSave(EnergyGoalSettingsUi(mode, kcal.toIntOrNull(), share)) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
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
    onSelected: (T) -> Unit
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
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
