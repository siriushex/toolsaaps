package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.domain.nutrition.*
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.ui.foundation.format.UiFormatters
import kotlin.math.roundToInt

@Composable
internal fun LentaMealEstimateView(estimate: LentaMealEstimate?, profile: MealAbsorptionProfile,
    portion: MealPortion, onProfile: (MealAbsorptionProfile) -> Unit, onPortion: (MealPortion) -> Unit,
    loading: Boolean, chartHeight: Dp, inspectionRequest: HybridMealResponseRequest) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("lentaEstimateBody"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            TextButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("lentaEstimateType")) {
                Text(stringResource(lentaTypeLabel(profile)), Modifier.weight(1f))
                Icon(Icons.Default.ExpandMore, stringResource(R.string.lenta_estimate_type))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MealAbsorptionProfile.entries.forEach { type ->
                    DropdownMenuItem(text = { Text(stringResource(lentaTypeLabel(type))) },
                        onClick = { menu = false; onProfile(type) },
                        modifier = Modifier.testTag("lentaEstimateType_${type.name}").semantics { selected = type == profile })
                }
            }
        }
        LentaPortionSelector(portion, onPortion)
        if (estimate == null) {
            Text(stringResource(R.string.lenta_estimate_invalid), Modifier.testTag("lentaEstimateUnavailable"))
        } else {
            Text(stringResource(R.string.lenta_estimate_summary,
                UiFormatters.formatGrams(estimate.modeledGrams, 1), estimate.durationMinutes),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("lentaEstimateSummary"))
            Text(stringResource(when {
                loading -> R.string.lenta_history_loading
                estimate.historyStatus != LentaMealHistoryStatus.READY -> R.string.lenta_history_unavailable
                estimate.supportMeals > 0 -> R.string.lenta_history_personal
                else -> R.string.lenta_history_default
            }, estimate.supportMeals, estimate.supportDays), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("lentaEstimateSource"))
            Text(estimate.historyGi?.let { stringResource(R.string.lenta_history_gi,
                UiFormatters.formatGrams(it, 0), estimate.giSupportMeals) }
                ?: stringResource(R.string.lenta_history_gi_unknown),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("lentaEstimateGi"))
            LentaEstimateChart(estimate, chartHeight, inspectionRequest)
        }
    }
}

internal fun lentaTypeLabel(profile: MealAbsorptionProfile): Int = when (profile) {
    MealAbsorptionProfile.FAST -> R.string.food_profile_fast_label
    MealAbsorptionProfile.MIXED -> R.string.food_profile_mixed_label
    MealAbsorptionProfile.FAT_PROTEIN -> R.string.food_profile_slow_label
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LentaPortionSelector(portion: MealPortion, onPortion: (MealPortion) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    if (maxWidth < 288.dp * LocalDensity.current.fontScale) {
        TextButton(onClick = { menu = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("lentaEstimatePortionMenu")) {
            Text(stringResource(R.string.lenta_estimate_portion, stringResource(lentaPortionLabel(portion))),
                Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, null)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MealPortion.entries.forEach { choice ->
                DropdownMenuItem(text = { Text(stringResource(lentaPortionLabel(choice))) },
                    onClick = { menu = false; onPortion(choice) },
                    modifier = Modifier.testTag("lentaEstimatePortion_${choice.name}")
                        .semantics { selected = choice == portion })
            }
        }
    } else {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        MealPortion.entries.forEachIndexed { index, choice ->
            SegmentedButton(selected = portion == choice, onClick = { onPortion(choice) },
                icon = {},
                shape = SegmentedButtonDefaults.itemShape(index, 3),
                modifier = Modifier.heightIn(min = 48.dp).testTag("lentaEstimatePortion_${choice.name}")) {
                Text(stringResource(lentaPortionLabel(choice)),
                    fontWeight = if (portion == choice) androidx.compose.ui.text.font.FontWeight.SemiBold
                    else androidx.compose.ui.text.font.FontWeight.Normal)
            }
        }
    }
    }
    }
}

private fun lentaPortionLabel(portion: MealPortion): Int = when (portion) {
    MealPortion.SMALL -> R.string.meal_portion_small
    MealPortion.MEDIUM -> R.string.meal_portion_medium
    MealPortion.LARGE -> R.string.meal_portion_large
}

@Composable
private fun LentaEstimateChart(estimate: LentaMealEstimate, height: Dp, request: HybridMealResponseRequest) {
    var selectedIndex by rememberSaveable(request) { mutableStateOf(0) }
    val index = selectedIndex.coerceIn(estimate.curve.indices)
    val point = estimate.curve[index]
    val readout = stringResource(R.string.lenta_estimate_point, point.ageMinutes,
        UiFormatters.formatGrams(point.modeledRemainingGrams, 1), UiFormatters.formatGrams(point.modeledAbsorbedGrams, 1))
    val previous = stringResource(R.string.lenta_estimate_previous)
    val next = stringResource(R.string.lenta_estimate_next)
    val line = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${UiFormatters.formatGrams(estimate.modeledGrams, 1)} ${stringResource(R.string.unit_g)}",
                style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.lenta_estimate_remaining), style = MaterialTheme.typography.labelSmall)
        }
        Canvas(Modifier.fillMaxWidth().height(height).testTag("lentaEstimateCurve")
            .semantics {
                contentDescription = readout
                stateDescription = "0 - ${estimate.durationMinutes}"
                customActions = listOf(
                    CustomAccessibilityAction(previous) { selectedIndex = (index - 1).coerceAtLeast(0); true },
                    CustomAccessibilityAction(next) { selectedIndex = (index + 1).coerceAtMost(estimate.curve.lastIndex); true })
            }
            .pointerInput(estimate) { detectTapGestures { position ->
                selectedIndex = ((position.x / size.width).coerceIn(0f, 1f) * estimate.curve.lastIndex).roundToInt()
            } }
            .pointerInput(estimate) { detectDragGestures { change, _ ->
                change.consume()
                selectedIndex = ((change.position.x / size.width).coerceIn(0f, 1f) * estimate.curve.lastIndex).roundToInt()
            } }) {
            val top = 4.dp.toPx()
            val bottom = size.height - top
            fun coordinate(i: Int): Offset = Offset(i.toFloat() / estimate.curve.lastIndex * size.width,
                bottom - (estimate.curve[i].modeledRemainingGrams / estimate.modeledGrams).toFloat() * (bottom - top))
            for (fraction in listOf(0f, 0.5f, 1f)) {
                val y = top + fraction * (bottom - top)
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            val path = Path()
            estimate.curve.indices.forEach { i -> val p = coordinate(i)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            drawPath(path, line, style = Stroke(2.dp.toPx()))
            val selected = coordinate(index)
            drawLine(grid, Offset(selected.x, top), Offset(selected.x, bottom), 1.dp.toPx())
            drawCircle(line, 4.dp.toPx(), selected)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0", style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.lenta_estimate_minutes, estimate.durationMinutes), style = MaterialTheme.typography.labelSmall)
        }
        Text(readout, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("lentaEstimatePoint"))
    }
}
