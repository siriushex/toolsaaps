package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.config.UamExportUiMode
import io.aaps.copilot.ui.foundation.format.UiFormatters

@Composable
internal fun UamExportModeSelector(
    mode: String,
    onModeSelected: (String) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var autoConfirmationVisible by rememberSaveable { mutableStateOf(false) }
    val normalized = UamExportUiMode.entries.firstOrNull { it.name == mode } ?: UamExportUiMode.OFF

    if (autoConfirmationVisible) {
        AlertDialog(
            modifier = Modifier.testTag("uamAutoConfirmDialog"),
            onDismissRequest = { autoConfirmationVisible = false },
            title = { Text(text = stringResource(id = R.string.uam_auto_confirm_title)) },
            text = {
                Text(
                    text = stringResource(id = R.string.uam_auto_confirm_limits),
                    modifier = Modifier.testTag("uamAutoConfirmLimits")
                )
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("uamAutoConfirmAccept"),
                    onClick = {
                        autoConfirmationVisible = false
                        onModeSelected(UamExportUiMode.AUTO.name)
                    }
                ) {
                    Text(text = stringResource(id = R.string.uam_auto_confirm_enable))
                }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.testTag("uamAutoConfirmCancel"),
                    onClick = { autoConfirmationVisible = false }
                ) {
                    Text(text = stringResource(id = R.string.action_cancel))
                }
            }
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        ) {
            UamExportUiMode.entries.forEachIndexed { index, candidate ->
                SegmentedButton(
                    enabled = enabled,
                    selected = candidate == normalized,
                    onClick = {
                        when {
                            candidate == normalized -> Unit
                            candidate == UamExportUiMode.AUTO -> autoConfirmationVisible = true
                            else -> onModeSelected(candidate.name)
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = UamExportUiMode.entries.size
                    ),
                    modifier = Modifier.testTag("uamExportMode_${candidate.name}")
                ) {
                    Text(text = uamExportModeLabel(candidate.name))
                }
            }
        }
        if (!enabled) {
            Text(
                text = stringResource(id = R.string.uam_mode_inference_required),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun uamExportModeLabel(mode: String): String = when (mode) {
    UamExportUiMode.OBSERVE.name -> stringResource(id = R.string.uam_mode_observe)
    UamExportUiMode.AUTO.name -> stringResource(id = R.string.uam_mode_auto)
    else -> stringResource(id = R.string.uam_mode_off)
}

@Composable
internal fun uamExportModeShortLabel(mode: String): String = when (mode) {
    UamExportUiMode.OBSERVE.name -> stringResource(id = R.string.uam_mode_observe_short)
    UamExportUiMode.AUTO.name -> stringResource(id = R.string.uam_mode_auto_short)
    else -> stringResource(id = R.string.uam_mode_off_short)
}

@Composable
internal fun UamExportRuntimeSummary(
    control: UamExportControlUi,
    modifier: Modifier = Modifier
) {
    val grams = UiFormatters.formatGrams(control.estimatedCarbsGrams, 1)
    val confidence = UiFormatters.formatPercent(control.confidence)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = stringResource(
                id = R.string.uam_mode_runtime_status,
                uamRuntimeStatusLabel(control.runtimeStatus)
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("uamRuntimeStatus")
        )
        Text(
            text = stringResource(
                id = R.string.uam_mode_runtime_metrics,
                "$grams ${stringResource(id = R.string.unit_g)}",
                confidence
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("uamRuntimeMetrics")
        )
        control.runtimeReasonStatus?.let { status ->
            Text(
                text = stringResource(
                    id = R.string.uam_mode_runtime_reason,
                    uamRuntimeStatusLabel(status)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("uamRuntimeReason")
            )
        }
        control.exportBlockedStatus?.let { status ->
            Text(
                text = stringResource(
                    id = R.string.uam_mode_export_restriction,
                    uamRuntimeStatusLabel(status)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("uamExportRestriction")
            )
        }
    }
}

@Composable
internal fun uamRuntimeStatusLabel(status: UamRuntimeStatusUi): String = stringResource(
    id = when (status) {
        UamRuntimeStatusUi.OBSERVATION_ACTIVE -> R.string.uam_runtime_observation_active
        UamRuntimeStatusUi.ACTIVE -> R.string.uam_runtime_active
        UamRuntimeStatusUi.DECAYING -> R.string.uam_runtime_decaying
        UamRuntimeStatusUi.INACTIVE -> R.string.uam_mode_no_runtime
        UamRuntimeStatusUi.SENSOR_BLOCKED -> R.string.uam_runtime_sensor_blocked
        UamRuntimeStatusUi.DATA_STALE -> R.string.uam_runtime_data_stale
        UamRuntimeStatusUi.CONFIDENCE_LOW -> R.string.uam_runtime_confidence_low
        UamRuntimeStatusUi.SIGNAL_UNSTABLE -> R.string.uam_runtime_signal_unstable
        UamRuntimeStatusUi.FORECAST_UNAVAILABLE -> R.string.uam_runtime_forecast_unavailable
        UamRuntimeStatusUi.LOW_GLUCOSE_RISK -> R.string.uam_runtime_low_glucose_risk
        UamRuntimeStatusUi.COB_ACTIVE -> R.string.uam_runtime_cob_active
        UamRuntimeStatusUi.MANUAL_CARBS -> R.string.uam_runtime_manual_carbs
        UamRuntimeStatusUi.THERAPY_COVERAGE_LOW -> R.string.uam_runtime_therapy_coverage_low
        UamRuntimeStatusUi.RECONCILIATION_REQUIRED -> R.string.uam_runtime_reconciliation_required
        UamRuntimeStatusUi.OUTCOME_UNKNOWN -> R.string.uam_runtime_outcome_unknown
        UamRuntimeStatusUi.RESERVATION_PENDING -> R.string.uam_runtime_reservation_pending
        UamRuntimeStatusUi.RATE_LIMITED -> R.string.uam_runtime_rate_limited
        UamRuntimeStatusUi.INTERVAL_WAIT -> R.string.uam_runtime_interval_wait
        UamRuntimeStatusUi.CAPACITY_REACHED -> R.string.uam_runtime_capacity_reached
    }
)
