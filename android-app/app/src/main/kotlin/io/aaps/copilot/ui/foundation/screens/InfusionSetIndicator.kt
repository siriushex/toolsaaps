package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aaps.copilot.R
import io.aaps.copilot.data.repository.DeliveryDiagnosticUiEvidence
import io.aaps.copilot.data.repository.InfusionNotice
import io.aaps.copilot.data.repository.InfusionSetUiStatus
import io.aaps.copilot.domain.pump.InfusionSetCondition
import io.aaps.copilot.domain.pump.InfusionSetPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InfusionSetIndicator(status: InfusionSetUiStatus, delivery: DeliveryDiagnosticUiEvidence, nowTs: Long) {
    var details by rememberSaveable { mutableStateOf(false) }
    val now = nowTs.takeIf { it > 0 } ?: System.currentTimeMillis()
    val timing = InfusionSetPolicy.evaluate(now, status.timing.evidence.takeUnless { status.unavailable })
    val currentDelivery = delivery.isCurrent(now)
    val ageTitle = stringResource(if (status.unavailable) R.string.infusion_set_state_unavailable else when (timing.condition) {
        InfusionSetCondition.UNKNOWN -> R.string.infusion_set_unknown
        InfusionSetCondition.DUE -> R.string.infusion_set_due
        InfusionSetCondition.NOT_DUE -> R.string.infusion_set_recorded
    })
    val deliveryTitle = stringResource(when {
        currentDelivery -> R.string.infusion_set_delivery_current
        delivery.episodeId.isNotEmpty() -> R.string.infusion_set_delivery_history
        else -> R.string.infusion_set_delivery_none
    })
    val tone = if (timing.condition == InfusionSetCondition.DUE || currentDelivery) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.onSurfaceVariant
    val caption = when {
        timing.condition == InfusionSetCondition.DUE || currentDelivery -> "!"
        timing.condition == InfusionSetCondition.UNKNOWN -> "?"
        else -> stringResource(R.string.infusion_set_age_short, (timing.ageMs!! / 3_600_000).toInt())
    }
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(ageTitle) } }, state = rememberTooltipState()) {
    IconButton(onClick = { details = true }, modifier = Modifier.size(48.dp).testTag("infusionSetIndicator")
        .semantics { contentDescription = "$ageTitle. $deliveryTitle" }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.event_infusion_pump_insulin), null, Modifier.size(18.dp), tint = tone)
            Text(caption, color = tone, fontSize = 11.sp, lineHeight = 12.sp, maxLines = 1, softWrap = false)
        }
    }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text(stringResource(R.string.infusion_set_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ageTitle, style = MaterialTheme.typography.titleSmall)
                timing.evidence?.let { evidence ->
                    val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
                    Text(stringResource(R.string.infusion_set_dates, formatter.format(Instant.ofEpochMilli(evidence.occurrenceTs)),
                        formatter.format(Instant.ofEpochMilli(timing.dueTs!!))))
                    Text(stringResource(R.string.infusion_set_source, if (evidence.source == "local_nightscout_treatment") "AAPS / local NS" else "Nightscout"))
                    Text(stringResource(R.string.infusion_set_last_record))
                } ?: Text(stringResource(if (status.unavailable) R.string.infusion_set_state_help else R.string.infusion_set_record_help))
                Text(stringResource(R.string.infusion_set_interval))
                Text(deliveryTitle, style = MaterialTheme.typography.titleSmall)
                if (delivery.episodeId.isNotEmpty()) {
                    Text(stringResource(if (delivery.foodConfounded) R.string.delivery_diagnostic_food_detail else R.string.delivery_diagnostic_detail))
                    delivery.recordedAt?.let { Text(DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))) }
                }
                Text(stringResource(when (status.notice) {
                    InfusionNotice.NONE -> R.string.infusion_set_notice_pending
                    InfusionNotice.CLAIMED -> R.string.infusion_set_notice_unknown
                    InfusionNotice.POSTED -> R.string.infusion_set_notice_posted
                    InfusionNotice.BLOCKED -> R.string.infusion_set_notice_blocked
                    InfusionNotice.SUPPRESSED -> R.string.infusion_set_notice_suppressed
                    InfusionNotice.PREVIOUSLY_ATTEMPTED -> R.string.infusion_set_notice_previous
                }))
                Text(stringResource(R.string.infusion_set_notice_limit))
            }
        }, confirmButton = { TextButton(onClick = { details = false }) { Text(stringResource(R.string.action_cancel)) } })
}
