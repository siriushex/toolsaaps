package io.aaps.copilot.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
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
import java.util.Locale

private enum class Screen(val title: String) {
    ONBOARDING("Onboarding & Connect"),
    DASHBOARD("Live Dashboard"),
    TELEMETRY("Telemetry"),
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
                Screen.TELEMETRY -> TelemetryScreen(state)
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
    val hasAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
    var exportUri by remember(state.exportUri) { mutableStateOf(state.exportUri.orEmpty()) }
    var localNightscoutEnabled by remember(state.localNightscoutEnabled) {
        mutableStateOf(state.localNightscoutEnabled)
    }
    var localNightscoutPort by remember(state.localNightscoutPort) {
        mutableStateOf(state.localNightscoutPort.toString())
    }
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
        Text("All-files access: ${if (hasAllFilesAccess) "granted" else "required for auto-discovery"}")
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Local xDrip/AAPS broadcast ingest")
            Switch(
                checked = state.localBroadcastIngestEnabled,
                onCheckedChange = vm::setLocalBroadcastIngestEnabled
            )
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Strict sender validation (Android 14+)")
            Switch(
                checked = state.strictBroadcastSenderValidation,
                onCheckedChange = vm::setStrictBroadcastValidation
            )
        }
        Text(
            if (state.localBroadcastIngestEnabled) {
                "Enabled: SGV/treatments can be accepted from local broadcast."
            } else {
                "Disabled: local broadcast data is ignored."
            }
        )
        Text(
            if (state.strictBroadcastSenderValidation) {
                "Strict mode: skip non-test broadcasts when sender package is unavailable/untrusted."
            } else {
                "Permissive mode: accept broadcasts when sender package cannot be resolved."
            }
        )
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Local Nightscout emulator (127.0.0.1)")
            Switch(
                checked = localNightscoutEnabled,
                onCheckedChange = { localNightscoutEnabled = it }
            )
        }
        OutlinedTextField(
            value = localNightscoutPort,
            onValueChange = { localNightscoutPort = it },
            label = { Text("Local Nightscout port") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                vm.setLocalNightscoutConfig(
                    enabled = localNightscoutEnabled,
                    port = localNightscoutPort.toIntOrNull() ?: state.localNightscoutPort
                )
            }
        ) {
            Text("Save local Nightscout")
        }
        Text(
            "Loopback URL for AAPS/Copilot: https://127.0.0.1:${localNightscoutPort.toIntOrNull() ?: state.localNightscoutPort}"
        )
        Text("If AAPS reports TLS errors, install Copilot Root CA in Android and restart NSClient.")
        Button(onClick = vm::installLocalNightscoutCertificate) {
            Text("Install loopback Root CA certificate")
        }
        Button(onClick = vm::exportLocalNightscoutCertificate) {
            Text("Export loopback TLS certificates")
        }
        Button(onClick = vm::openCertificateSettings) {
            Text("Open certificate settings")
        }
        if (!hasAllFilesAccess) {
            Button(onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }) {
                Text("Grant all-files access")
            }
        }
        Button(onClick = { vm.runAutoConnectNow() }) {
            Text("Auto-connect AAPS now")
        }
        HorizontalDivider()
        Text("Auto-connect discovery")
        if (state.autoConnectLines.isEmpty()) {
            Text("No scan results yet")
        } else {
            state.autoConnectLines.forEach { Text(it) }
        }
        OutlinedTextField(value = exportUri, onValueChange = { exportUri = it }, label = { Text("AAPS export path/SAF URI") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { folderPicker.launch(null) }) {
            Text("Pick export folder")
        }
        Button(onClick = { vm.setExportFolderUri(exportUri.ifBlank { null }) }) {
            Text("Save export folder")
        }
    }
}

@Composable
private fun DashboardScreen(state: MainUiState, vm: MainViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Current glucose: ${state.latestGlucoseMmol?.let { String.format("%.2f mmol/L", it) } ?: "-"}")
            Text("Delta: ${state.glucoseDelta?.let { String.format("%+.2f", it) } ?: "-"}")
            Text("Forecast 5m: ${state.forecast5m?.let { String.format("%.2f", it) } ?: "-"}")
            Text("Forecast 30m: ${state.forecast30m?.let { String.format("%.2f", it) } ?: "-"}")
            Text("Forecast 1h: ${state.forecast60m?.let { String.format("%.2f", it) } ?: "-"}")
            Text("Last rule: ${state.lastRuleId ?: "-"} / ${state.lastRuleState ?: "-"}")
            Text(
                "Adaptive controller: ${state.controllerState ?: "-"}" +
                    (state.controllerNextTarget?.let {
                        ", next=${String.format("%.2f", it)} mmol/L"
                    } ?: "")
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::runAutomationNow) { Text("Run automation") }
                Button(onClick = vm::runDailyAnalysisNow) { Text("Run daily analysis") }
            }
        }

        item {
            HorizontalDivider()
            Text("Sync health")
        }
        items(state.syncStatusLines) { Text(it) }

        item {
            HorizontalDivider()
            Text("Action delivery")
        }
        items(state.actionLines) { Text(it) }

        item {
            HorizontalDivider()
            Text("Telemetry coverage")
        }
        items(state.telemetryCoverageLines) { Text(it) }

        item {
            HorizontalDivider()
            Text("Telemetry snapshot (top 12)")
        }
        if (state.telemetryLines.isEmpty()) {
            item { Text("No telemetry yet") }
        } else {
            items(state.telemetryLines.take(12)) { Text(it) }
            item { Text("Open 'Telemetry' screen for full list") }
        }
    }
}

@Composable
private fun TelemetryScreen(state: MainUiState) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Telemetry coverage")
        }
        items(state.telemetryCoverageLines) { line ->
            Text(line)
        }
        item {
            HorizontalDivider()
            Text("All incoming parameters (latest by key)")
        }
        if (state.telemetryLines.isEmpty()) {
            item { Text("No telemetry yet") }
        } else {
            items(state.telemetryLines) { line ->
                Text(line)
            }
        }
    }
}

@Composable
private fun ForecastScreen(state: MainUiState) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Forecast vs baseline")
            Text("5m: ${state.forecast5m?.let { String.format("%.2f mmol/L", it) } ?: "-"}")
            Text("30m: ${state.forecast30m?.let { String.format("%.2f mmol/L", it) } ?: "-"}")
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
    var postCooldown by remember(state.rulePostHypoCooldownMinutes) { mutableStateOf(state.rulePostHypoCooldownMinutes.toString()) }
    var patternCooldown by remember(state.rulePatternCooldownMinutes) { mutableStateOf(state.rulePatternCooldownMinutes.toString()) }
    var segmentCooldown by remember(state.ruleSegmentCooldownMinutes) { mutableStateOf(state.ruleSegmentCooldownMinutes.toString()) }
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
        OutlinedTextField(
            value = postCooldown,
            onValueChange = { postCooldown = it },
            label = { Text("PostHypo cooldown minutes (0..240)") },
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
        OutlinedTextField(
            value = patternCooldown,
            onValueChange = { patternCooldown = it },
            label = { Text("Pattern cooldown minutes (0..240)") },
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
        OutlinedTextField(
            value = segmentCooldown,
            onValueChange = { segmentCooldown = it },
            label = { Text("Segment cooldown minutes (0..240)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            vm.setRuleConfig(
                postHypoEnabled = postEnabled,
                patternEnabled = patternEnabled,
                segmentEnabled = segmentEnabled,
                postHypoPriority = postPriority.toIntOrNull() ?: state.rulePostHypoPriority,
                patternPriority = patternPriority.toIntOrNull() ?: state.rulePatternPriority,
                segmentPriority = segmentPriority.toIntOrNull() ?: state.ruleSegmentPriority,
                postHypoCooldownMinutes = postCooldown.toIntOrNull() ?: state.rulePostHypoCooldownMinutes,
                patternCooldownMinutes = patternCooldown.toIntOrNull() ?: state.rulePatternCooldownMinutes,
                segmentCooldownMinutes = segmentCooldown.toIntOrNull() ?: state.ruleSegmentCooldownMinutes
            )
        }) {
            Text("Save rule config")
        }
        Text("Cooldown status")
        if (state.ruleCooldownLines.isEmpty()) {
            Text("No cooldown state yet")
        } else {
            state.ruleCooldownLines.forEach { Text(it) }
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
    var postHypoThreshold by remember(state.postHypoThresholdMmol) { mutableStateOf(String.format("%.2f", state.postHypoThresholdMmol)) }
    var postHypoDelta by remember(state.postHypoDeltaThresholdMmol5m) { mutableStateOf(String.format("%.2f", state.postHypoDeltaThresholdMmol5m)) }
    var postHypoTarget by remember(state.postHypoTargetMmol) { mutableStateOf(String.format("%.1f", state.postHypoTargetMmol)) }
    var postHypoDuration by remember(state.postHypoDurationMinutes) { mutableStateOf(state.postHypoDurationMinutes.toString()) }
    var postHypoLookback by remember(state.postHypoLookbackMinutes) { mutableStateOf(state.postHypoLookbackMinutes.toString()) }
    var maxActions by remember(state.maxActionsIn6Hours) { mutableStateOf(state.maxActionsIn6Hours.toString()) }
    var staleMax by remember(state.staleDataMaxMinutes) { mutableStateOf(state.staleDataMaxMinutes.toString()) }
    var patternMinSamples by remember(state.patternMinSamplesPerWindow) { mutableStateOf(state.patternMinSamplesPerWindow.toString()) }
    var patternMinDays by remember(state.patternMinActiveDaysPerWindow) { mutableStateOf(state.patternMinActiveDaysPerWindow.toString()) }
    var patternLowTrigger by remember(state.patternLowRateTrigger) { mutableStateOf(String.format("%.2f", state.patternLowRateTrigger)) }
    var patternHighTrigger by remember(state.patternHighRateTrigger) { mutableStateOf(String.format("%.2f", state.patternHighRateTrigger)) }
    var lookbackDays by remember(state.analyticsLookbackDays) { mutableStateOf(state.analyticsLookbackDays.toString()) }
    var adaptiveEnabled by remember(state.adaptiveControllerEnabled) { mutableStateOf(state.adaptiveControllerEnabled) }
    var adaptivePriority by remember(state.adaptiveControllerPriority) { mutableStateOf(state.adaptiveControllerPriority.toString()) }
    var adaptiveRetarget by remember(state.adaptiveControllerRetargetMinutes) { mutableStateOf(state.adaptiveControllerRetargetMinutes.toString()) }
    var adaptiveProfile by remember(state.adaptiveControllerSafetyProfile) { mutableStateOf(state.adaptiveControllerSafetyProfile) }
    var adaptiveStaleMax by remember(state.adaptiveControllerStaleMaxMinutes) { mutableStateOf(state.adaptiveControllerStaleMaxMinutes.toString()) }
    var adaptiveMaxActions by remember(state.adaptiveControllerMaxActions6h) { mutableStateOf(state.adaptiveControllerMaxActions6h.toString()) }
    var adaptiveMaxStep by remember(state.adaptiveControllerMaxStepMmol) { mutableStateOf(String.format("%.2f", state.adaptiveControllerMaxStepMmol)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Kill switch")
            Switch(checked = state.killSwitch, onCheckedChange = vm::setKillSwitch)
        }
        Text("Kill switch affects auto actions only (manual temp target/carbs remain available).")
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
        Text("Global hard safety limits")
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
        Text("Adaptive 30–60m Target Controller")
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Enable adaptive controller")
            Switch(
                checked = adaptiveEnabled,
                onCheckedChange = {
                    adaptiveEnabled = it
                    vm.setAdaptiveControllerEnabled(it)
                }
            )
        }
        OutlinedTextField(
            value = adaptiveProfile,
            onValueChange = { adaptiveProfile = it.uppercase(Locale.US) },
            label = { Text("Safety profile (STRICT/BALANCED/AGGRESSIVE)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = adaptiveRetarget,
            onValueChange = { adaptiveRetarget = it },
            label = { Text("Retarget frequency minutes (5/15/30)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = adaptivePriority,
            onValueChange = { adaptivePriority = it },
            label = { Text("Adaptive rule priority (0..200)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = adaptiveStaleMax,
            onValueChange = { adaptiveStaleMax = it },
            label = { Text("Adaptive stale max minutes") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = adaptiveMaxActions,
            onValueChange = { adaptiveMaxActions = it },
            label = { Text("Adaptive max actions in 6h") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = adaptiveMaxStep,
            onValueChange = { adaptiveMaxStep = it },
            label = { Text("Max step per action (mmol/L)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            vm.setAdaptiveControllerConfig(
                enabled = adaptiveEnabled,
                priority = adaptivePriority.toIntOrNull() ?: state.adaptiveControllerPriority,
                retargetMinutes = adaptiveRetarget.toIntOrNull() ?: state.adaptiveControllerRetargetMinutes,
                safetyProfile = adaptiveProfile,
                staleMaxMinutes = adaptiveStaleMax.toIntOrNull() ?: state.adaptiveControllerStaleMaxMinutes,
                maxActions6h = adaptiveMaxActions.toIntOrNull() ?: state.adaptiveControllerMaxActions6h,
                maxStepMmol = adaptiveMaxStep.toDoubleOrNull() ?: state.adaptiveControllerMaxStepMmol
            )
        }) {
            Text("Apply adaptive settings")
        }
        Text("Preview: state=${state.controllerState ?: "-"}, reason=${state.controllerReason ?: "-"}")
        Text(
            "Preview: f30=${state.controllerForecast30?.let { String.format("%.2f", it) } ?: "-"}, " +
                "f60=${state.controllerForecast60?.let { String.format("%.2f", it) } ?: "-"}, " +
                "weightedError=${state.controllerWeightedError?.let { String.format("%.2f", it) } ?: "-"}"
        )
        Text(
            "Preview: next=${state.controllerNextTarget?.let { String.format("%.2f mmol/L", it) } ?: "-"}, " +
                "duration=${state.controllerDurationMinutes?.let { "${it}m" } ?: "-"}, " +
                "confidence=${state.controllerConfidence?.let { String.format("%.2f", it) } ?: "-"}"
        )
        HorizontalDivider()
        Text("Post-hypo rebound rule tuning")
        OutlinedTextField(
            value = postHypoThreshold,
            onValueChange = { postHypoThreshold = it },
            label = { Text("Hypo threshold mmol/L") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = postHypoDelta,
            onValueChange = { postHypoDelta = it },
            label = { Text("Delta threshold mmol/5m") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = postHypoTarget,
            onValueChange = { postHypoTarget = it },
            label = { Text("Temp target mmol/L") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = postHypoDuration,
            onValueChange = { postHypoDuration = it },
            label = { Text("Temp target duration minutes") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = postHypoLookback,
            onValueChange = { postHypoLookback = it },
            label = { Text("Hypo lookback minutes") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            vm.setPostHypoTuning(
                thresholdMmol = postHypoThreshold.toDoubleOrNull() ?: state.postHypoThresholdMmol,
                deltaThresholdMmol5m = postHypoDelta.toDoubleOrNull() ?: state.postHypoDeltaThresholdMmol5m,
                targetMmol = postHypoTarget.toDoubleOrNull() ?: state.postHypoTargetMmol,
                durationMinutes = postHypoDuration.toIntOrNull() ?: state.postHypoDurationMinutes,
                lookbackMinutes = postHypoLookback.toIntOrNull() ?: state.postHypoLookbackMinutes
            )
        }) {
            Text("Apply post-hypo tuning")
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
        Text("ISF estimate (merged/OpenAPS+history): ${state.profileIsf?.let { String.format("%.2f mmol/L/U", it) } ?: "-"}")
        Text("CR estimate (merged/OpenAPS+history): ${state.profileCr?.let { String.format("%.2f g/U", it) } ?: "-"}")
        Text("Confidence: ${state.profileConfidence?.let { String.format("%.0f%%", it * 100) } ?: "-"}")
        Text("Samples (merged): total=${state.profileSamples ?: "-"}, ISF=${state.profileIsfSamples ?: "-"}, CR=${state.profileCrSamples ?: "-"}")
        Text("Telemetry samples: ISF=${state.profileTelemetryIsfSamples ?: "-"}, CR=${state.profileTelemetryCrSamples ?: "-"}")
        Text("ISF calculated (history-only): ${state.profileCalculatedIsf?.let { String.format("%.2f mmol/L/U", it) } ?: "-"}")
        Text("CR calculated (history-only): ${state.profileCalculatedCr?.let { String.format("%.2f g/U", it) } ?: "-"}")
        Text(
            "Samples (history-only): total=${state.profileCalculatedSamples ?: "-"}, " +
                "ISF=${state.profileCalculatedIsfSamples ?: "-"}, CR=${state.profileCalculatedCrSamples ?: "-"}"
        )
        Text("Confidence (history-only): ${state.profileCalculatedConfidence?.let { String.format("%.0f%%", it * 100) } ?: "-"}")
        Text("UAM: observed=${state.profileUamObservedCount ?: "-"}, filtered ISF samples=${state.profileUamFilteredIsfSamples ?: "-"}")
        Text(
            "UAM carbs estimate: total=${state.profileUamCarbsGrams?.let { String.format("%.1f g", it) } ?: "-"}, " +
                "recent=${state.profileUamRecentCarbsGrams?.let { String.format("%.1f g", it) } ?: "-"}, " +
                "episodes=${state.profileUamEpisodes ?: "-"}"
        )
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
        item { Text("Adaptive controller trace") }
        if (state.adaptiveAuditLines.isEmpty()) {
            item { Text("No adaptive controller events yet") }
        } else {
            items(state.adaptiveAuditLines) { line ->
                Text(line)
            }
        }
        item { HorizontalDivider() }
        item { Text("All audit events") }
        items(state.auditLines) { line ->
            Text(line)
        }
    }
}
