package io.aaps.copilot.ui.foundation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.aaps.copilot.R
import io.aaps.copilot.service.AapsV3ConnectionState
import io.aaps.copilot.service.ComboTransferPhase
import java.text.DateFormat
import java.util.Date

@Composable
internal fun NativeComboHistorySection(state: AapsV3ConnectionState) {
    var expanded by remember { mutableStateOf(false) }
    val transfer = state.comboTransfer
    val audit = transfer.lastAudit
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.native_combo_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("native_combo_details")) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.native_combo_collapse else R.string.native_combo_expand))
            }
        }
        Text(stringResource(when (transfer.phase) {
            ComboTransferPhase.READING -> R.string.native_combo_reading
            ComboTransferPhase.RETAINED -> R.string.native_combo_retained
            ComboTransferPhase.PARTIAL -> R.string.native_combo_partial
            ComboTransferPhase.UNAVAILABLE -> R.string.native_combo_unavailable
            ComboTransferPhase.IDLE -> if (audit == null) R.string.native_combo_unchecked else R.string.native_combo_waiting
        }), Modifier.testTag("native_combo_status"), style = MaterialTheme.typography.bodySmall)
        if (expanded) {
            if (audit != null) {
                Text(stringResource(R.string.native_combo_checked_at,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(audit.checkedAtMs))),
                    Modifier.testTag("native_combo_checked_at"), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.native_combo_reads, audit.retainedReads, audit.missingReads, audit.conflictingReads),
                    Modifier.testTag("native_combo_reads"), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.native_combo_events, audit.distinctEvents, audit.eventsWithoutReadAcknowledgement),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.native_combo_counters, audit.missingInteriorCounterValues, audit.conflictingEvents),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.native_combo_clocks, audit.clockChangeEvents,
                    audit.backwardsLocalTimePairs, audit.postClockChangeRewinds),
                    Modifier.testTag("native_combo_clocks"), style = MaterialTheme.typography.bodySmall)
                if (audit.readerTimeInconsistent) Text(stringResource(R.string.native_combo_reader_clock),
                    style = MaterialTheme.typography.bodySmall)
                if (audit.unknownEvents > 0) Text(stringResource(R.string.native_combo_unknown, audit.unknownEvents),
                    style = MaterialTheme.typography.bodySmall)
                if (audit.hazardKinds > 0) Text(stringResource(R.string.native_combo_hazards, audit.hazardKinds),
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.native_combo_delivery_unverified), Modifier.testTag("native_combo_delivery"),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
