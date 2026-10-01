package io.aaps.copilot.ui.foundation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.domain.nutrition.MealPortion
import io.aaps.copilot.domain.nutrition.MealPortionSettings
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.ui.foundation.screens.FoodProfileSelector
import io.aaps.copilot.ui.foundation.format.UiFormatters
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MealEntryDialog(
    settings: MealPortionSettings,
    maximumGrams: Double,
    onDismiss: () -> Unit,
    onConfirm: (MealEntryConfirmation) -> Unit
) {
    var portion by rememberSaveable { mutableStateOf(MealPortion.MEDIUM) }
    var profile by rememberSaveable { mutableStateOf(MealAbsorptionProfile.MIXED) }
    var eatingSoon by rememberSaveable { mutableStateOf(true) }
    var energyRaw by rememberSaveable { mutableStateOf("") }
    val submissionId = rememberSaveable { UUID.randomUUID().toString() }
    var manualCarbs by rememberSaveable { mutableStateOf(false) }
    var correction by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settings.showCalories) { if (!settings.showCalories) energyRaw = "" }
    if (submitted) return
    val proposedGrams = settings.range(portion).defaultGrams
    val finalMeal = confirmedMealFromInput(
        if (manualCarbs) correction else proposedGrams.toString(), portion, profile,
        energyRaw, settings.showCalories, eatingSoon, submissionId, maximumGrams,
        proposedGrams = proposedGrams
    )
    val energyValid = !settings.showCalories || energyRaw.isBlank() ||
        energyRaw.replace(',', '.').toDoubleOrNull()?.let { it.isFinite() && it in 1.0..10_000.0 } == true
    val enlargedText = LocalDensity.current.fontScale > 1.3f
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.meal_entry_title), modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("mealEntryClose")) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(Modifier.fillMaxWidth().selectableGroup(),
                    maxItemsInEachRow = if (enlargedText) 1 else 3,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MealPortion.entries.forEach { candidate ->
                        val label = when(candidate) {
                            MealPortion.SMALL -> R.string.meal_portion_small
                            MealPortion.MEDIUM -> R.string.meal_portion_medium
                            MealPortion.LARGE -> R.string.meal_portion_large
                        }
                        val icon = when(candidate) {
                            MealPortion.SMALL -> R.drawable.meal_portion_small
                            MealPortion.MEDIUM -> R.drawable.meal_portion_medium
                            MealPortion.LARGE -> R.drawable.meal_portion_large
                        }
                        MealPictureChoice(portion == candidate, stringResource(label), icon,
                            "mealPortion_${candidate.name}", { portion = candidate },
                            Modifier.weight(1f).fillMaxRowHeight(), horizontal = enlargedText)
                    }
                }
                FoodProfileSelector(profile, { profile = it }, compact = true)
                FlowRow(Modifier.fillMaxWidth(),
                    maxItemsInEachRow = if (enlargedText) 1 else 2,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp).testTag("overviewEatingSoon")
                    .toggleable(eatingSoon, role = Role.Checkbox, onValueChange = { eatingSoon = it }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(eatingSoon, onCheckedChange = null)
                    Text(stringResource(R.string.overview_eating_soon))
                }
                Row(Modifier.weight(1f).heightIn(min = 48.dp).testTag("mealManualCarbs")
                    .toggleable(manualCarbs, role = Role.Checkbox, onValueChange = {
                        manualCarbs = it
                        if (it && correction.isBlank()) correction = proposedGrams.toString()
                    }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(manualCarbs, onCheckedChange = null)
                    Text(stringResource(R.string.meal_manual_carbs))
                }
                }
                if (manualCarbs) OutlinedTextField(correction, { correction = it }, singleLine = true,
                    label = { Text(stringResource(R.string.overview_cob_custom)) },
                    isError = finalMeal == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("mealConfirmGrams"))
                if (finalMeal == null && energyValid) Text(stringResource(R.string.meal_amount_limit,
                    UiFormatters.formatGrams(maximumGrams, 1)), color = MaterialTheme.colorScheme.error)
                if (settings.showCalories) OutlinedTextField(energyRaw, { energyRaw = it },
                    label = { Text(stringResource(R.string.overview_cob_manual_energy_optional)) },
                    isError = !energyValid, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.testTag("overviewManualMealEnergyKcal"))
            }
        },
        confirmButton = {
            Button(enabled = settings.isValid() && finalMeal != null,
                modifier = Modifier.fillMaxWidth().testTag("overviewPrepareMeal"), onClick = {
                    if (!submitted && finalMeal != null) {
                        submitted = true
                        onConfirm(finalMeal)
                    }
                }) { Text(stringResource(R.string.meal_prepare_amount,
                    finalMeal?.grams?.let { UiFormatters.formatExactGrams(it, LocalConfiguration.current.locales[0]) }
                        ?: "?")) }
        }
    )
}
