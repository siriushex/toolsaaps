package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.data.repository.AlertEpisodeDetail
import io.aaps.copilot.data.repository.AlertEpisodeSummary
import io.aaps.copilot.data.repository.AlertEvidenceItem
import io.aaps.copilot.data.repository.AlertEvidenceKind
import io.aaps.copilot.domain.alerts.CauseConfidence
import io.aaps.copilot.ui.foundation.design.Spacing
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
fun AlertsScreen(
    state: AlertsUiState,
    onSelectEpisode: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("alertsScreen"),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        item { MuteStatus(state.mutedUntilTs) }
        state.active?.let { active ->
            item {
                SectionLabel(stringResource(R.string.alerts_active_title))
                EpisodeRow(active, active = true, onClick = { onSelectEpisode(active.episodeId) })
            }
        }
        state.selected?.let { detail ->
            item { EpisodeDetail(detail) }
        }
        item { SectionLabel(stringResource(R.string.alerts_history_30d_title)) }
        when {
            state.loadState == ScreenLoadState.LOADING -> item {
                Text(
                    text = stringResource(R.string.state_loading),
                    modifier = Modifier.padding(Spacing.sm),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            state.history.isEmpty() -> item {
                Text(
                    text = stringResource(R.string.alerts_history_empty),
                    modifier = Modifier.padding(Spacing.sm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> items(state.history, key = { it.episodeId }) { episode ->
                EpisodeRow(episode, active = false, onClick = { onSelectEpisode(episode.episodeId) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun MuteStatus(mutedUntilTs: Long) {
    val muted = isAuthoritativelyMuted(mutedUntilTs)
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("alertsMuteStatus"),
        color = if (muted) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)) {
            Text(
                text = if (muted) {
                    stringResource(R.string.alerts_muted_until, formatAlertTime(mutedUntilTs))
                } else {
                    stringResource(R.string.alerts_enabled)
                },
                style = MaterialTheme.typography.titleSmall
            )
            if (muted) {
                Text(
                    text = stringResource(R.string.alerts_not_shown_off),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xs, start = Spacing.xs)
    )
}

@Composable
private fun EpisodeRow(
    episode: AlertEpisodeSummary,
    active: Boolean,
    onClick: () -> Unit
) {
    val direction = if (episode.direction == "LOW") {
        stringResource(R.string.alerts_direction_low)
    } else {
        stringResource(R.string.alerts_direction_high)
    }
    val stage = localizedAlertStage(episode.stage)
    val status = localizedAlertStatus(episode.status)
    val displayTimestamp = if (active) episode.updatedAt else episode.createdAt
    val displayTime = formatAlertTime(displayTimestamp)
    val description = stringResource(
        R.string.alerts_episode_accessibility,
        direction,
        stage,
        status,
        displayTime
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { role = Role.Button; contentDescription = description }
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (active) stringResource(R.string.alerts_active_episode, direction) else direction,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "$stage · $status",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (episode.notShownOff) {
                Text(
                    text = stringResource(R.string.alerts_not_shown_off),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        Text(
            text = displayTime,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun isAuthoritativelyMuted(mutedUntilTs: Long): Boolean = mutedUntilTs > 0L

@Composable
private fun EpisodeDetail(detail: AlertEpisodeDetail) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("alertEpisodeDetail"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier.padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(stringResource(R.string.alerts_local_cause_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = detail.summary.localCauseCode?.let { localizedAlertCause(it) }
                    ?: stringResource(R.string.alerts_local_cause_unavailable),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(stringResource(R.string.alerts_local_advice_title), style = MaterialTheme.typography.labelLarge)
            Text(
                text = detail.localAdviceCode?.let { localizedAlertAdvice(it) }
                    ?: stringResource(R.string.alerts_local_advice_unavailable),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(
                    R.string.alerts_episode_state,
                    localizedAlertStage(detail.summary.stage),
                    localizedAlertStatus(detail.summary.status)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    R.string.alerts_episode_timestamps,
                    formatAlertTime(detail.summary.createdAt),
                    formatAlertTime(detail.summary.updatedAt)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (detail.evidence.isNotEmpty()) {
                Text(stringResource(R.string.alerts_evidence_title), style = MaterialTheme.typography.labelLarge)
                detail.evidence.forEach { item ->
                    ClinicalEvidenceRow(item)
                }
            }
            Text(stringResource(R.string.alerts_ai_title), style = MaterialTheme.typography.labelLarge)
            val ai = detail.aiPresentation
            if (ai == null) {
                Text(
                    text = stringResource(R.string.alerts_ai_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.alerts_ai_cause,
                        localizedAlertCause(ai.primaryCause.name)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(
                        R.string.alerts_ai_confidence,
                        localizedAiConfidence(ai.confidence)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                if (ai.evidence.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.alerts_ai_evidence),
                        style = MaterialTheme.typography.labelMedium
                    )
                    ai.evidence.forEach { kind ->
                        Text(
                            text = localizedEvidenceLabel(kind),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Text(
                    text = stringResource(
                        R.string.alerts_ai_advice,
                        localizedAlertAdvice(ai.advice.name)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ClinicalEvidenceRow(item: AlertEvidenceItem) {
    val label = localizedEvidenceLabel(item.kind)
    val timestamp = item.timestamp?.let(::formatAlertTime)
    val text = when {
        item.glucoseMmol != null && timestamp != null -> stringResource(
            R.string.alerts_evidence_glucose_timed,
            label,
            formatGlucose(item.glucoseMmol),
            timestamp
        )
        item.glucoseMmol != null -> stringResource(
            R.string.alerts_evidence_glucose,
            label,
            formatGlucose(item.glucoseMmol)
        )
        timestamp != null -> stringResource(R.string.alerts_evidence_observed, label, timestamp)
        else -> label
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall)
}

private fun formatAlertTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

private fun formatGlucose(value: Double): String = NumberFormat.getNumberInstance().apply {
    minimumFractionDigits = 1
    maximumFractionDigits = 1
}.format(value)

@Composable
private fun localizedAlertCause(code: String): String = when (code) {
    "SENSOR_QUALITY" -> stringResource(R.string.glucose_alert_cause_sensor_quality)
    "MEAL_UAM" -> stringResource(R.string.glucose_alert_cause_meal_uam)
    "INSULIN_ACTIVITY_LOW" -> stringResource(R.string.glucose_alert_cause_insulin_activity_low)
    "DELIVERY_NONRESPONSE" -> stringResource(R.string.glucose_alert_cause_delivery_nonresponse)
    "CIRCADIAN_PATTERN" -> stringResource(R.string.glucose_alert_cause_circadian_pattern)
    "TARGET_RESPONSE" -> stringResource(R.string.glucose_alert_cause_target_response)
    "EVENT_CONTEXT" -> stringResource(R.string.glucose_alert_cause_event_context)
    "DATA_INCOMPLETE" -> stringResource(R.string.glucose_alert_cause_data_incomplete)
    else -> stringResource(R.string.glucose_alert_cause_unknown)
}

@Composable
private fun localizedAlertAdvice(code: String): String = when (code) {
    "SENSOR_QUALITY" -> stringResource(R.string.alerts_advice_sensor_quality)
    "MEAL_UAM" -> stringResource(R.string.alerts_advice_meal_uam)
    "INSULIN_ACTIVITY_LOW" -> stringResource(R.string.alerts_advice_insulin_activity_low)
    "DELIVERY_NONRESPONSE" -> stringResource(R.string.alerts_advice_delivery_nonresponse)
    "CIRCADIAN_PATTERN" -> stringResource(R.string.alerts_advice_circadian_pattern)
    "TARGET_RESPONSE" -> stringResource(R.string.alerts_advice_target_response)
    "EVENT_CONTEXT" -> stringResource(R.string.alerts_advice_event_context)
    "DATA_INCOMPLETE" -> stringResource(R.string.alerts_advice_data_incomplete)
    else -> stringResource(R.string.alerts_advice_unknown)
}

@Composable
private fun localizedEvidenceLabel(kind: AlertEvidenceKind): String = when (kind) {
    AlertEvidenceKind.CURRENT_GLUCOSE -> stringResource(R.string.alerts_evidence_current_glucose)
    AlertEvidenceKind.FORECAST_5 -> stringResource(R.string.alerts_evidence_forecast_5)
    AlertEvidenceKind.FORECAST_30 -> stringResource(R.string.alerts_evidence_forecast_30)
    AlertEvidenceKind.FORECAST_60 -> stringResource(R.string.alerts_evidence_forecast_60)
    AlertEvidenceKind.SENSOR_QUALITY -> stringResource(R.string.alerts_evidence_sensor_quality)
    AlertEvidenceKind.UAM -> stringResource(R.string.alerts_evidence_uam)
    AlertEvidenceKind.DELIVERY -> stringResource(R.string.alerts_evidence_delivery)
    AlertEvidenceKind.INSULIN -> stringResource(R.string.alerts_evidence_insulin)
    AlertEvidenceKind.TARGET -> stringResource(R.string.alerts_evidence_target)
    AlertEvidenceKind.CIRCADIAN -> stringResource(R.string.alerts_evidence_circadian)
    AlertEvidenceKind.EVENT_CONTEXT -> stringResource(R.string.alerts_evidence_event_context)
    AlertEvidenceKind.DATA_QUALITY -> stringResource(R.string.alerts_evidence_data_quality)
}

@Composable
private fun localizedAiConfidence(confidence: CauseConfidence): String =
    when (confidence) {
        CauseConfidence.LOW -> stringResource(R.string.clinical_text_confidence_low)
        CauseConfidence.MEDIUM -> stringResource(R.string.clinical_text_confidence_medium)
        CauseConfidence.HIGH -> stringResource(R.string.clinical_text_confidence_high)
    }

@Composable
private fun localizedAlertStage(stage: String): String = when (stage) {
    "WATCH_60" -> stringResource(R.string.alerts_stage_watch_60)
    "WARNING_30" -> stringResource(R.string.alerts_stage_warning_30)
    "SOFT_HIGH_RISK" -> stringResource(R.string.alerts_stage_soft_high)
    "CRITICAL_5" -> stringResource(R.string.alerts_stage_critical_5)
    "LOW_NOW" -> stringResource(R.string.alerts_stage_low_now)
    else -> stringResource(R.string.alerts_stage_unknown)
}

@Composable
private fun localizedAlertStatus(status: String): String = when (status) {
    "OPEN" -> stringResource(R.string.alerts_status_open)
    "SAFE_PENDING" -> stringResource(R.string.alerts_status_safe_pending)
    "RESOLVED" -> stringResource(R.string.alerts_status_resolved)
    else -> stringResource(R.string.alerts_status_unknown)
}
