package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.domain.nutrition.MealPortion
import io.aaps.copilot.domain.nutrition.MealPortionRange
import io.aaps.copilot.domain.nutrition.MealPortionSettings

@Composable
internal fun MealPortionSettingsEditor(
    value: MealPortionSettings,
    onSave: (MealPortionSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    var fields by rememberSaveable(value) {
        mutableStateOf(MealPortion.entries.flatMap { portion ->
            value.range(portion).let { listOf(it.minGrams, it.maxGrams, it.defaultGrams) }
                .map { it.toString() }
        })
    }
    var showCalories by rememberSaveable(value) { mutableStateOf(value.showCalories) }
    fun range(index: Int) = fields.drop(index * 3).take(3)
        .map { it.trim().replace(',', '.').toDoubleOrNull() ?: Double.NaN }
        .let { MealPortionRange(it[0], it[1], it[2]) }
    val draft = MealPortionSettings(range(0), range(1), range(2), showCalories)
    val labels = listOf(R.string.meal_portion_min, R.string.meal_portion_max, R.string.meal_portion_default)
    val names = listOf(R.string.meal_portion_small, R.string.meal_portion_medium, R.string.meal_portion_large)
    val tags = listOf("min", "max", "default")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.meal_portions_title), style = MaterialTheme.typography.titleMedium)
        MealPortion.entries.forEachIndexed { index, portion ->
            Text(stringResource(names[index]), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { field ->
                    val position = index * 3 + field
                    OutlinedTextField(
                        value = fields[position],
                        onValueChange = { changed ->
                            fields = fields.mapIndexed { i, old -> if (i == position) changed else old }
                        },
                        label = { Text(stringResource(labels[field])) },
                        singleLine = true,
                        isError = !draft.range(portion).isValid(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).testTag("mealPortion_${portion.name}_${tags[field]}")
                    )
                }
            }
        }
        if (!draft.isValid()) {
            Text(
                stringResource(R.string.meal_portion_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("mealPortionsError")
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.meal_portion_calories), modifier = Modifier.weight(1f))
            Switch(checked = showCalories, onCheckedChange = { showCalories = it },
                modifier = Modifier.testTag("mealPortionsCalories"))
        }
        TextButton(
            enabled = draft.isValid() && draft != value,
            onClick = { if (draft.isValid()) onSave(draft) },
            modifier = Modifier.align(Alignment.End).testTag("mealPortionsSave")
        ) {
            Text(stringResource(if (draft == value) R.string.meal_portions_saved else R.string.meal_portions_save))
        }
    }
}
