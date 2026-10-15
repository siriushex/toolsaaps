package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.data.repository.MealForecastJournalReview
import io.aaps.copilot.domain.meal.MealProspectiveEvaluationStatus
import java.util.Locale

enum class ProspectiveReviewStatus { READY, EMPTY, OVERFLOW, UNAVAILABLE }

data class ProspectiveForecastRowUi(
    val series: Int, val horizonMinutes: Int, val samples: Int, val days: Int,
    val maeMmol: Double?, val missedLows: Int, val actualLows: Int,
    val pending: Int, val unavailable: Int
)

data class ProspectiveForecastReviewUi(
    val status: ProspectiveReviewStatus, val receipts: Int = 0,
    val rejected: Int = 0, val unknownSensor: Int = 0,
    val rows: List<ProspectiveForecastRowUi> = emptyList()
)

internal fun MealForecastJournalReview.toReviewUi(): ProspectiveForecastReviewUi {
    val result = evaluation
    val status = when {
        overflow || result?.status == MealProspectiveEvaluationStatus.OVERFLOW -> ProspectiveReviewStatus.OVERFLOW
        result == null -> ProspectiveReviewStatus.UNAVAILABLE
        result.baselineOnlyHorizons.isEmpty() -> ProspectiveReviewStatus.EMPTY
        else -> ProspectiveReviewStatus.READY
    }
    val groups = result?.baselineOnlyHorizons.orEmpty().groupBy { it.cohort }
    return ProspectiveForecastReviewUi(status, rows, rejectedFrames.values.sum() +
        (result?.conflictingFrames ?: 0), rejectedFrames["MISSING_SESSION"] ?: 0,
        groups.values.flatMapIndexed { index, horizons -> horizons.map {
            ProspectiveForecastRowUi(index + 1, it.horizonMinutes, it.observedSamples, it.observedDays,
                it.baseline?.maeMmol, it.baselineMissedLows, it.actualLowSamples, it.pending,
                it.missingOutcome + it.contextMismatch + it.rejectedOutcome + it.conflictingOutcome)
        } })
}

@Composable
internal fun ProspectiveForecastReviewDialog(
    state: ProspectiveForecastReviewUi?, loading: Boolean,
    onRefresh: () -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(onDismissRequest = onDismiss,
        title = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.prospective_review_title), modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = !loading) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.prospective_review_refresh))
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.prospective_review_basis), style = MaterialTheme.typography.bodySmall)
                when {
                    loading -> Text(stringResource(R.string.prospective_review_loading))
                    state == null || state.status == ProspectiveReviewStatus.UNAVAILABLE -> Text(stringResource(R.string.prospective_review_unavailable))
                    state.status == ProspectiveReviewStatus.OVERFLOW -> Text(stringResource(R.string.prospective_review_overflow))
                    else -> {
                        Text(stringResource(R.string.prospective_review_receipts, state.receipts, state.rejected))
                        if (state.unknownSensor > 0) Text(stringResource(R.string.prospective_review_sensor_unknown, state.unknownSensor))
                        if (state.status == ProspectiveReviewStatus.EMPTY) Text(stringResource(R.string.prospective_review_empty))
                        state.rows.groupBy { it.series }.forEach { (series, rows) ->
                            HorizontalDivider()
                            Text(stringResource(R.string.prospective_review_series, series), style = MaterialTheme.typography.labelLarge)
                            rows.forEach { row ->
                                Text(stringResource(R.string.prospective_review_row, row.horizonMinutes,
                                    row.maeMmol?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "--", row.samples, row.days))
                                Text(stringResource(R.string.prospective_review_support, row.missedLows, row.actualLows,
                                    row.pending, row.unavailable), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.prospective_review_close)) } }
    )
}
