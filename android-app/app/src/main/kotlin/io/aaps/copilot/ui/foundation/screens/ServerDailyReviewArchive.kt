package io.aaps.copilot.ui.foundation.screens

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.R
import io.aaps.copilot.data.repository.*
import io.aaps.copilot.scheduler.observeDailyArchiveCutoffs
import io.aaps.copilot.security.ServerAiConnectionPhase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

internal class ServerDailyReviewViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as CopilotApp).container
    private val repository = container.serverDailyReviewRepository
    val state = repository.state
    val connection = container.serverAiConnectionManager.state
    fun now() = container.serverAiConnectionManager.currentServerTimeMs()
    fun open() { viewModelScope.launch { repository.open() } }
    fun refresh() { viewModelScope.launch { repository.refresh() } }
    fun select(id: String) { viewModelScope.launch { repository.select(id) } }
    fun closeSelection() = repository.closeSelection()
    fun refreshSelection() { viewModelScope.launch { repository.refreshSelection() } }
}

@Composable
internal fun ServerDailyReviewArchive(query: String, onOpenConnection: () -> Unit,
    model: ServerDailyReviewViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val latestState by rememberUpdatedState(state)
    val expiry = state.records.minOfOrNull { it.expiresAtMs }
    LaunchedEffect(connection.revision, expiry, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.open()
            observeDailyArchiveCutoffs(model::now, { latestState.records.minOfOrNull { it.expiresAtMs } },
                { ZoneId.systemDefault() }, model::open)
        }
    }
    val visible = if (connection.phase == ServerAiConnectionPhase.DISCONNECTED && !connection.hasStoredSession)
        ServerDailyArchiveState(failure = ServerAiHistoryError.UNAUTHORIZED) else state
    ServerDailyReviewArchiveContent(visible, model::refresh, model::select, model::closeSelection,
        model::refreshSelection, onOpenConnection, query)
}

@Composable
internal fun ServerDailyReviewArchiveContent(state: ServerDailyArchiveState, onRefresh: () -> Unit,
    onSelect: (String) -> Unit, onClose: () -> Unit, onRefreshSelection: () -> Unit,
    onOpenConnection: () -> Unit, query: String = "") {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val displayZone = ZoneId.systemDefault()
    val date = remember(locale) { DateTimeFormatter.ofPattern("d MMM yyyy", locale) }
    val time = remember(locale, displayZone) { DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale).withZone(displayZone) }
    fun timestamp(value: Long) = time.format(Instant.ofEpochMilli(value))
    var visibleCount by rememberSaveable(query) { mutableIntStateOf(20) }
    val matches = remember(state.records, query) {
        state.records.filter { row -> query.isBlank() || listOf(row.reportDate, row.zoneId, row.summary, row.model)
            .any { it.contains(query, ignoreCase = true) } }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("daily_archive"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ai_server_archive_title), Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleSmall)
            AiFocusAction(Icons.Default.Refresh, stringResource(R.string.ai_server_archive_refresh), onRefresh,
                Modifier.testTag("daily_archive_refresh"), enabled = !state.refreshing && !state.detailRefreshing)
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("daily_archive_refreshing"))
        }
        state.fetchedAtMs?.let { Text(stringResource(R.string.ai_server_archive_updated, timestamp(it)),
            Modifier.testTag("daily_archive_updated"), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        state.failure?.let { error ->
            Text(stringResource(archiveError(error)), Modifier.testTag("daily_archive_error"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (error == ServerAiHistoryError.UNAUTHORIZED || error == ServerAiHistoryError.FORBIDDEN) {
                TextButton(onOpenConnection, Modifier.testTag("daily_archive_connection")) {
                    Icon(Icons.Default.Settings, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ai_server_archive_connection))
                }
            }
        }
        if (matches.isEmpty() && !state.refreshing && state.failure == null) {
            Text(stringResource(if (query.isBlank()) R.string.ai_server_archive_empty else R.string.ai_server_archive_no_match),
                style = MaterialTheme.typography.bodySmall)
        }
        matches.take(visibleCount).forEach { row ->
            Column(Modifier.fillMaxWidth().clickable(enabled = !state.refreshing && !state.detailRefreshing) { onSelect(row.jobId) }
                .testTag("daily_archive_row_${row.jobId}").padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${date.format(LocalDate.parse(row.reportDate))} / ${row.zoneId}", style = MaterialTheme.typography.labelMedium)
                Text(row.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.ai_server_archive_unreviewed), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
            }
        }
        if (matches.size > visibleCount) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            AiFocusAction(Icons.Default.ExpandMore, stringResource(R.string.ai_server_archive_more),
                { visibleCount += 20 }, Modifier.testTag("daily_archive_more"))
        }
    }
    val selected = state.records.singleOrNull { it.jobId == state.selectedJobId }
    if (selected != null) {
        val detail = state.detail?.takeIf { it.jobId == selected.jobId }
        AlertDialog(onDismissRequest = onClose, modifier = Modifier.testTag("daily_archive_detail"),
            title = { Text("${date.format(LocalDate.parse(selected.reportDate))} / ${selected.zoneId}", Modifier.testTag("daily_archive_date")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.ai_server_archive_unreviewed), Modifier.testTag("daily_archive_unreviewed"),
                        style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.ai_server_archive_created, timestamp(selected.storedAtMs)),
                        style = MaterialTheme.typography.bodySmall)
                    if (state.detailRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.detailFailure?.let { Text(stringResource(archiveError(it)), color = MaterialTheme.colorScheme.error) }
                    if (detail != null) {
                        val sourceText = stringResource(when (detail.sourceState) {
                            DailyReviewSourceState.SOURCE_MATCHED -> R.string.ai_server_archive_matched
                            DailyReviewSourceState.SOURCE_CHANGED -> R.string.ai_server_archive_changed
                            DailyReviewSourceState.SOURCE_UNAVAILABLE -> R.string.ai_server_archive_source_unavailable
                            DailyReviewSourceState.SUPERSEDED -> R.string.ai_server_archive_superseded
                        })
                        Text(listOfNotNull(sourceText, detail.checkedAtMs?.let { timestamp(it) }).joinToString("\n"),
                            Modifier.testTag("daily_archive_source"), style = MaterialTheme.typography.bodySmall,
                            color = if (detail.sourceState == DailyReviewSourceState.SOURCE_MATCHED)
                                MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                        Text(stringResource(when (detail.status) {
                            "FINDINGS" -> R.string.ai_server_archive_findings
                            "LIMITED_DATA" -> R.string.ai_server_archive_limited
                            else -> R.string.ai_server_archive_no_pattern
                        }), style = MaterialTheme.typography.labelMedium)
                    } else Text(stringResource(R.string.ai_server_archive_unchecked), Modifier.testTag("daily_archive_source"),
                        style = MaterialTheme.typography.bodySmall)
                    Text(detail?.summary ?: selected.summary, Modifier.testTag("daily_archive_summary"))
                    detail?.findings?.forEach { Text(it) }
                    Text(stringResource(R.string.ai_server_archive_scope), Modifier.testTag("daily_archive_scope"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClose) { Text(stringResource(R.string.action_close)) } },
            dismissButton = { AiFocusAction(Icons.Default.Refresh, stringResource(R.string.ai_server_archive_check_source),
                onRefreshSelection, enabled = !state.refreshing && !state.detailRefreshing) })
    }
}

private fun archiveError(error: ServerAiHistoryError) = when (error) {
    ServerAiHistoryError.UNAUTHORIZED, ServerAiHistoryError.FORBIDDEN -> R.string.ai_server_archive_no_access
    ServerAiHistoryError.NOT_FOUND -> R.string.ai_server_archive_unavailable
    ServerAiHistoryError.DEVICE_MISMATCH -> R.string.ai_server_archive_device_changed
    ServerAiHistoryError.TOO_LARGE -> R.string.ai_server_archive_too_large
    ServerAiHistoryError.STORAGE -> R.string.ai_server_archive_storage
    ServerAiHistoryError.INVALID_RESPONSE, ServerAiHistoryError.CONFLICT, ServerAiHistoryError.CURSOR_EXPIRED,
    ServerAiHistoryError.INVALID_REQUEST -> R.string.ai_server_archive_invalid
    else -> R.string.ai_server_archive_retry
}
