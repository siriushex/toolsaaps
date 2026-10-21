package io.aaps.copilot.ui.foundation.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.aaps.copilot.R
import io.aaps.copilot.ui.foundation.format.UiFormatters

@Composable
internal fun MealPreparationLabel(preparation: MealPreparationPresentation) {
    if (preparation.status == MealPreparationStatus.IDLE) return
    Text(if (preparation.status == MealPreparationStatus.IOB_PRESENT && preparation.plannedMealAtMs != null)
        stringResource(R.string.meal_preparation_time_short, UiFormatters.formatTimestamp(preparation.plannedMealAtMs))
    else stringResource(when (preparation.status) {
        MealPreparationStatus.IOB_PRESENT -> R.string.meal_preparation_iob
        MealPreparationStatus.WAITING_FOR_IOB -> R.string.meal_preparation_wait
        MealPreparationStatus.CHECK_GLUCOSE -> R.string.meal_preparation_low
        else -> R.string.meal_preparation_check
    }), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("mealPreparationStatus"))
}

@Composable
internal fun MealPreparationDetails(preparation: MealPreparationPresentation) {
    if (preparation.status == MealPreparationStatus.IDLE) return
    MealPreparationLabel(preparation)
    val plan = preparation.plannedMealAtMs
    if (plan != null) {
        Text(stringResource(R.string.meal_preparation_planned, UiFormatters.formatTimestamp(plan),
            preparation.minutesUntilPlan ?: 0), modifier = Modifier.testTag("mealPreparationPlan"))
        Text(stringResource(R.string.meal_preparation_planned_detail), style = MaterialTheme.typography.bodySmall)
    } else {
        Text(stringResource(R.string.meal_preparation_timing_unknown), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("mealPreparationTimingUnknown"))
    }
}
