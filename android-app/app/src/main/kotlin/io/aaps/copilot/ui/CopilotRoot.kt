package io.aaps.copilot.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class Screen(val title: String) {
    ONBOARDING("Onboarding & Connect"),
    DASHBOARD("Live Dashboard"),
    FORECAST("Forecast Studio"),
    REPLAY("Replay Lab"),
    RULES("Rules & Automation"),
    SAFETY("Safety Center"),
    INSIGHTS("Insights"),
    AUDIT("Audit Log")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopilotRoot(vm: MainViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    var screen by remember { mutableStateOf(Screen.DASHBOARD) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("AAPS Predictive Copilot") }) },
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == screen,
                        onClick = { screen = item },
                        label = { Text(item.title) },
                        icon = { }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.message?.let { Text(it) }
            when (screen) {
                Screen.ONBOARDING -> OnboardingScreen(state, vm)
                Screen.DASHBOARD -> DashboardScreen(state, vm)
                Screen.FORECAST -> ForecastScreen(state)
                Screen.REPLAY -> ReplayLabScreen(state, vm)
                Screen.RULES -> RulesScreen(state, vm)
                Screen.SAFETY -> SafetyScreen(state, vm)
                Screen.INSIGHTS -> InsightsScreen(state, vm)
                Screen.AUDIT -> AuditScreen(state)
            }
        }
    }
}

@Composable
private fun OnboardingScreen(state: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    var nsUrl by remember(state.nightscoutUrl) { mutableStateOf(state.nightscoutUrl) }
    var nsSecret by remember { mutableStateOf("") }
    var cloudUrl by remember(state.cloudUrl) { mutableStateOf(state.cloudUrl) }
    var exportUri by remember(state.exportUri) { mutableStateOf(state.exportUri.orEmpty()) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            exportUri = uri.toString()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = nsUrl, onValueChange = { nsUrl = it }, label = { Text("Nightscout URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = nsSecret, onValueChange = { nsSecret = it }, label = { Text("API Secret") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = cloudUrl, onValueChange = { cloudUrl = it }, label = { Text("Cloud API URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = exportUri, onValueChange = { exportUri = it }, label = { Text("AAPS export SAF URI") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { folderPicker.launch(null) }) {
            Text("Pick export folder")
        }
        Button(onClick = { vm.saveConnections(nsUrl, nsSecret, cloudUrl, exportUri.ifBlank { null }) }) {
            Text("Save")
        }
    }
}

@Composable
private fun DashboardScreen(state: MainUiState, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Current glucose: ${state.latestGlucoseMmol?.let { String.format("%.2f mmol/L", it) } ?: "-"}")
        Text("Delta: ${state.glucoseDelta?.let { String.format("%+.2f", it) } ?: "-"}")
        Text("Forecast 5m: ${state.forecast5m?.let { String.format("%.2f", it) } ?: "-"}")
        Text("Forecast 1h: ${state.forecast60m?.let { String.format("%.2f", it) } ?: "-"}")
        Text("Last rule: ${state.lastRuleId ?: "-"} / ${state.lastRuleState ?: "-"}")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::runAutomationNow) { Text("Run automation") }
            Button(onClick = vm::runDailyAnalysisNow) { Text("Run daily analysis") }
        }

        HorizontalDivider()
        Text("Sync health")
        state.syncStatusLines.forEach { Text(it) }
    }
}

@Composable
private fun ForecastScreen(state: MainUiState) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Forecast vs baseline")
            Text("5m: ${state.forecast5m?.let { String.format("%.2f mmol/L", it) } ?: "-"}")
            Text("1h: ${state.forecast60m?.let { String.format("%.2f mmol/L", it) } ?: "-"}")
            HorizontalDivider()
            Text("Quality metrics")
        }
        items(state.qualityMetrics) { metric ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Text("${metric.horizonMinutes}m | samples=${metric.sampleCount}")
                    Text("MAE=${"%.3f".format(metric.mae)} mmol/L")
                    Text("RMSE=${"%.3f".format(metric.rmse)} mmol/L")
                    Text("MARD=${"%.2f".format(metric.mardPct)}%")
                }
            }
        }
        item {
            HorizontalDivider()
            Text("Delta vs AAPS baseline")
        }
        items(state.baselineDeltaLines) { line ->
            Text(line)
        }
        item {
            HorizontalDivider()
        }
        item {
            Text("Weekday risk windows")
        }
        items(state.weekdayHotHours) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Text("Hour ${it.hour}: low=${"%.0f".format(it.lowRate * 100)}%, high=${"%.0f".format(it.highRate * 100)}%")
                    Text("Evidence: samples=${it.sampleCount}, days=${it.activeDays}")
                    Text("Adaptive target: ${"%.1f".format(it.recommendedTargetMmol)} mmol/L")
                }
            }
        }
        item { Text("Weekend risk windows") }
        items(state.weekendHotHours) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Text("Hour ${it.hour}: low=${"%.0f".format(it.lowRate * 100)}%, high=${"%.0f".format(it.highRate * 100)}%")
                    Text("Evidence: samples=${it.sampleCount}, days=${it.activeDays}")
                    Text("Adaptive target: ${"%.1f".format(it.recommendedTargetMmol)} mmol/L")
                }
            }
        }
    }
}

@Composable
private fun RulesScreen(state: MainUiState, vm: MainViewModel) {
    var postEnabled by remember(state.rulePostHypoEnabled) { mutableStateOf(state.rulePostHypoEnabled) }
    var patternEnabled by remember(state.rulePatternEnabled) { mutableStateOf(state.rulePatternEnabled) }
    var segmentEnabled by remember(state.ruleSegmentEnabled) { mutableStateOf(state.ruleSegmentEnabled) }
    var postPriority by remember(state.rulePostHypoPriority) { mutableStateOf(state.rulePostHypoPriority.toString()) }
    var patternPriority by remember(state.rulePatternPriority) { mutableStateOf(state.rulePatternPriority.toString()) }
    var segmentPriority by remember(state.ruleSegmentPriority) { mutableStateOf(state.ruleSegmentPriority.toString()) }
    var dryRunDays by remember { mutableStateOf("14") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Rule toggles and priorities")
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("PostHypoReboundGuard")
            Switch(checked = postEnabled, onCheckedChange = { postEnabled = it })
        }
        OutlinedTextField(
            value = postPriority,
            onValueChange = { postPriority = it },
            label = { Text("PostHypo priority (0..200)") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("PatternAdaptiveTarget")
            Switch(checked = patternEnabled, onCheckedChange = { patternEnabled = it })
        }
        OutlinedTextField(
            value = patternPriority,
            onValueChange = { patternPriority = it },
            label = { Text("Pattern priority (0..200)") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("SegmentProfileGuard")
            Switch(checked = segmentEnabled, onCheckedChange = { segmentEnabled = it })
        }
        OutlinedTextField(
            value = segmentPriority,
            onValueChange = { segmentPriority = it },
            label = { Text("Segment priority (0..200)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            vm.setRuleConfig(
                postHypoEnabled = postEnabled,
                patternEnabled = patternEnabled,
                segmentEnabled = segmentEnabled,
                postHypoPriority = postPriority.toIntOrNull() ?: state.rulePostHypoPriority,
                patternPriority = patternPriority.toIntOrNull() ?: state.rulePatternPriority,
                segmentPriority = segmentPriority.toIntOrNull() ?: state.ruleSegmentPriority
            )
        }) {
            Text("Save rule config")
        }
        HorizontalDivider()
        Text("Dry-run simulation")
        OutlinedTextField(
            value = dryRunDays,
            onValueChange = { dryRunDays = it },
            label = { Text("Days (1..60)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { vm.runDryRun(dryRunDays.toIntOrNull() ?: 14) }) {
            Text("Run dry-run")
        }
        Text("Last execution: ${state.lastRuleId ?: "-"} / ${state.lastRuleState ?: "-"}")
        state.dryRun?.let { report ->
            Text("Dry-run period: ${report.periodDays}d, samples: ${report.samplePoints}")
            report.lines.forEach { line -> Text(line) }
        }
    }
}

@Composable
private fun SafetyScreen(state: MainUiState, vm: MainViewModel) {
    var targetInput by remember(state.baseTargetMmol) { mutableStateOf(String.format("%.1f", state.baseTargetMmol)) }
    var maxActions by remember(state.maxActionsIn6Hours) { mutableStateOf(state.maxActionsIn6Hours.toString()) }
    var staleMax by remember(state.staleDataMaxMinutes) { mutableStateOf(state.staleDataMaxMinutes.toString()) }
    var patternMinSamples by remember(state.patternMinSamplesPerWindow) { mutableStateOf(state.patternMinSamplesPerWindow.toString()) }
    var patternMinDays by remember(state.patternMinActiveDaysPerWindow) { mutableStateOf(state.patternMinActiveDaysPerWindow.toString()) }
    var patternLowTrigger by remember(state.patternLowRateTrigger) { mutableStateOf(String.format("%.2f", state.patternLowRateTrigger)) }
    var patternHighTrigger by remember(state.patternHighRateTrigger) { mutableStateOf(String.format("%.2f", state.patternHighRateTrigger)) }
    var lookbackDays by remember(state.analyticsLookbackDays) { mutableStateOf(state.analyticsLookbackDays.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Kill switch")
            Switch(checked = state.killSwitch, onCheckedChange = vm::setKillSwitch)
        }
        OutlinedTextField(
            value = targetInput,
            onValueChange = { targetInput = it },
            label = { Text("Base target mmol/L") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            targetInput.toDoubleOrNull()?.let(vm::setBaseTarget)
        }) {
            Text("Apply base target")
        }
        OutlinedTextField(
            value = maxActions,
            onValueChange = { maxActions = it },
            label = { Text("Max auto-actions in 6h") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = staleMax,
            onValueChange = { staleMax = it },
            label = { Text("Stale data max (minutes)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            vm.setSafetyLimits(
                maxActionsIn6h = maxActions.toIntOrNull() ?: state.maxActionsIn6Hours,
                staleDataMaxMinutes = staleMax.toIntOrNull() ?: state.staleDataMaxMinutes
            )
        }) {
            Text("Apply safety limits")
        }
        HorizontalDivider()
        Text("Pattern reliability tuning")
        OutlinedTextField(
            value = patternMinSamples,
            onValueChange = { patternMinSamples = it },
            label = { Text("Min samples per hour window") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = patternMinDays,
            onValueChange = { patternMinDays = it },
            label = { Text("Min active days per window") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = patternLowTrigger,
            onValueChange = { patternLowTrigger = it },
            label = { Text("Low-rate trigger (0..1)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = patternHighTrigger,
            onValueChange = { patternHighTrigger = it },
            label = { Text("High-rate trigger (0..1)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = lookbackDays,
            onValueChange = { lookbackDays = it },
            label = { Text("Analytics lookback days") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            vm.setPatternTuning(
                minSamplesPerWindow = patternMinSamples.toIntOrNull() ?: state.patternMinSamplesPerWindow,
                minActiveDaysPerWindow = patternMinDays.toIntOrNull() ?: state.patternMinActiveDaysPerWindow,
                lowRateTrigger = patternLowTrigger.toDoubleOrNull() ?: state.patternLowRateTrigger,
                highRateTrigger = patternHighTrigger.toDoubleOrNull() ?: state.patternHighRateTrigger,
                lookbackDays = lookbackDays.toIntOrNull() ?: state.analyticsLookbackDays
            )
        }) {
            Text("Apply pattern tuning")
        }
        HorizontalDivider()
        Text("ISF estimate: ${state.profileIsf?.let { String.format("%.2f mmol/L/U", it) } ?: "-"}")
        Text("CR estimate: ${state.profileCr?.let { String.format("%.2f g/U", it) } ?: "-"}")
        Text("Confidence: ${state.profileConfidence?.let { String.format("%.0f%%", it * 100) } ?: "-"}")
        Text("Samples: total=${state.profileSamples ?: "-"}, ISF=${state.profileIsfSamples ?: "-"}, CR=${state.profileCrSamples ?: "-"}")
        Text("Lookback: ${state.profileLookbackDays ?: "-"} days")
        HorizontalDivider()
        Text("Segmented ISF/CR")
        if (state.profileSegmentLines.isEmpty()) {
            Text("No segment estimates yet")
        } else {
            state.profileSegmentLines.forEach { Text(it) }
        }
    }
}

@Composable
private fun InsightsScreen(state: MainUiState, vm: MainViewModel) {
    var sourceFilter by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("") }
    var daysFilter by remember { mutableStateOf("60") }
    var weeksFilter by remember { mutableStateOf("8") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("OpenAI/cloud insights run daily via worker")
        Text(state.insightsFilterLabel)
        OutlinedTextField(
            value = sourceFilter,
            onValueChange = { sourceFilter = it },
            label = { Text("Source filter (all/manual/scheduler)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = statusFilter,
            onValueChange = { statusFilter = it },
            label = { Text("Status filter (all/SUCCESS/FAILED)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = daysFilter,
            onValueChange = { daysFilter = it },
            label = { Text("History days (1..365)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = weeksFilter,
            onValueChange = { weeksFilter = it },
            label = { Text("Trend weeks (1..52)") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.applyInsightsFilters(sourceFilter, statusFilter, daysFilter, weeksFilter) }) {
                Text("Apply filters")
            }
            Button(onClick = vm::runDailyAnalysisNow) { Text("Run now") }
            Button(onClick = { vm.refreshCloudJobs() }) { Text("Refresh jobs") }
            Button(onClick = { vm.refreshAnalysisHistory() }) { Text("Refresh history") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::exportInsightsCsv) { Text("Export CSV") }
            Button(onClick = vm::exportInsightsPdf) { Text("Export PDF") }
        }
        if (state.jobStatusLines.isEmpty()) {
            Text("No scheduler status yet")
        } else {
            state.jobStatusLines.forEach { Text(it) }
        }
        HorizontalDivider()
        Text("Recent daily analysis")
        if (state.analysisHistoryLines.isEmpty()) {
            Text("No history yet")
        } else {
            state.analysisHistoryLines.forEach { Text(it) }
        }
        HorizontalDivider()
        Text("Weekly trend")
        if (state.analysisTrendLines.isEmpty()) {
            Text("No trend yet")
        } else {
            state.analysisTrendLines.forEach { Text(it) }
        }
        Text(state.message ?: "No insights yet")
    }
}

@Composable
private fun ReplayLabScreen(state: MainUiState, vm: MainViewModel) {
    var replayDays by remember { mutableStateOf("14") }
    var stepMinutes by remember { mutableStateOf("5") }
    var horizonFilter by remember { mutableStateOf("") }

    val horizon = horizonFilter.toIntOrNull()
    val replay = state.cloudReplay

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Replay Lab")
            OutlinedTextField(
                value = replayDays,
                onValueChange = { replayDays = it },
                label = { Text("Days (1..60)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = stepMinutes,
                onValueChange = { stepMinutes = it },
                label = { Text("Step minutes (5..60)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = horizonFilter,
                onValueChange = { horizonFilter = it },
                label = { Text("Horizon filter (blank=all)") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.runCloudReplayNow(replayDays.toIntOrNull() ?: 14, stepMinutes.toIntOrNull() ?: 5) }) {
                    Text("Run replay")
                }
                Button(onClick = vm::exportReplayCsv) { Text("Export CSV") }
                Button(onClick = { vm.exportReplayPdf(horizon) }) { Text("Export PDF") }
            }
        }

        replay?.let { report ->
            item {
                HorizontalDivider()
                Text("Period: ${report.days}d | points=${report.points} | step=${report.stepMinutes}m")
            }
            item { Text("Forecast") }
            items(report.forecastStats.filter { horizon == null || it.horizon == horizon }) { stat ->
                Text("${stat.horizon}m n=${stat.sampleCount}, MAE=${"%.3f".format(stat.mae)}, RMSE=${"%.3f".format(stat.rmse)}, MARD=${"%.2f".format(stat.mardPct)}%")
            }
            item { Text("Weekday/Weekend") }
            items(report.dayTypeStats) { day ->
                Text(day.dayType)
                day.forecastStats.filter { horizon == null || it.horizon == horizon }.forEach { stat ->
                    Text("  ${stat.horizon}m n=${stat.sampleCount}, MAE=${"%.3f".format(stat.mae)}")
                }
            }
            item { Text("Hourly MAE") }
            items(report.hourlyStats) { stat ->
                Text("h${stat.hour.toString().padStart(2, '0')} n=${stat.sampleCount}, MAE=${"%.3f".format(stat.mae)}, RMSE=${"%.3f".format(stat.rmse)}")
            }
            item { Text("MAE drift") }
            items(report.driftStats.filter { horizon == null || it.horizon == horizon }) { drift ->
                Text("${drift.horizon}m prev=${"%.3f".format(drift.previousMae)} recent=${"%.3f".format(drift.recentMae)} delta=${if (drift.deltaMae >= 0) "+" else ""}${"%.3f".format(drift.deltaMae)}")
            }
            item { Text("Rules") }
            items(report.ruleStats) { rule ->
                Text("${rule.ruleId}: TRG=${rule.triggered}, BLK=${rule.blocked}, NO=${rule.noMatch}")
            }
        }
    }
}

@Composable
private fun AuditScreen(state: MainUiState) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(state.auditLines) { line ->
            Text(line)
        }
    }
}
