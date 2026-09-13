package io.aaps.copilot.ui.foundation.screens

import io.aaps.copilot.config.ClinicalAiEndpointPolicy
import io.aaps.copilot.config.ClinicalAiProviderConfig
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.config.executionIdentity
import io.aaps.copilot.config.UamExportUiModeCommand
import io.aaps.copilot.config.UamExportUiMode
import io.aaps.copilot.config.isCopilotCloudBackendEndpoint
import io.aaps.copilot.data.repository.ClinicalAdvisoryPriority
import io.aaps.copilot.data.repository.ClinicalCareTeamDiscussionTopic
import io.aaps.copilot.data.repository.ClinicalCareTeamQuestion
import io.aaps.copilot.data.repository.ClinicalDataQualityFlag
import io.aaps.copilot.data.repository.ClinicalEvidenceMetric
import io.aaps.copilot.data.repository.ClinicalEvidencePeriod
import io.aaps.copilot.data.repository.ClinicalFinding
import io.aaps.copilot.data.repository.ClinicalFindingConfidence
import io.aaps.copilot.data.repository.GlucoseAlertAudioProfiles
import io.aaps.copilot.data.repository.GlucoseAlertAudioSlot
import io.aaps.copilot.data.repository.TargetManagerLiveStatus
import io.aaps.copilot.data.repository.TargetManagerLiveStatusCodec
import io.aaps.copilot.data.repository.ClinicalPatternDirection
import io.aaps.copilot.data.repository.ClinicalPatternTopic
import io.aaps.copilot.data.repository.ClinicalPeriodSummary
import io.aaps.copilot.data.repository.ClinicalInsulinTotalSource
import io.aaps.copilot.data.repository.ClinicalRecommendation
import io.aaps.copilot.data.repository.ClinicalSafetyObservation
import io.aaps.copilot.data.repository.ClinicalSummaryStatus
import io.aaps.copilot.data.repository.ClinicalTimeBand
import io.aaps.copilot.data.repository.AcceptedSensitivityCandidateDiagnostics
import io.aaps.copilot.domain.eating.ProbableEatingWindow
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.domain.predict.InsulinActionProfiles
import io.aaps.copilot.domain.predict.AcceptedSensitivityTupleFreshness
import io.aaps.copilot.domain.predict.SensitivityCandidate
import io.aaps.copilot.domain.predict.SensitivityCandidates
import io.aaps.copilot.domain.predict.SensitivityMetricDecision
import io.aaps.copilot.domain.predict.SensitivityResolvedSource
import io.aaps.copilot.domain.predict.SensitivityRuntimeSnapshot
import io.aaps.copilot.domain.target.BaseTargetSchedule
import io.aaps.copilot.domain.target.CircadianAutoState
import io.aaps.copilot.service.PowerSaveController
import io.aaps.copilot.ui.LastActionRowUi
import io.aaps.copilot.ui.MainUiState
import io.aaps.copilot.data.repository.ClinicalReportFailureReason
import io.aaps.copilot.data.repository.ClinicalReportState
import io.aaps.copilot.data.repository.ClinicalOpenAiProgressStage
import io.aaps.copilot.data.local.entity.EnergyProfileSnapshotEntity
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import io.aaps.copilot.domain.profile.EnergyProfilePolicy
import io.aaps.copilot.domain.profile.EnergyProfileSettings
import io.aaps.copilot.domain.profile.EvidenceTier
import io.aaps.copilot.domain.profile.ActivityScheduleEngine
import io.aaps.copilot.domain.profile.PlannedActivityIntensity
import io.aaps.copilot.domain.profile.PlannedActivitySchedule
import io.aaps.copilot.domain.profile.PlannedActivityType
import io.aaps.copilot.domain.profile.PhysiologicalSex
import io.aaps.copilot.domain.profile.ProfileResolution
import io.aaps.copilot.ui.UamEventRowUi
import io.aaps.copilot.ui.foundation.format.UiFormatters
import java.util.Locale
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

private const val OVERVIEW_INTERACTIVE_HISTORY_WINDOW_MS = 24L * 60L * 60L * 1_000L
internal const val UAM_EXPORT_REASON_UI_TTL_MS = 10L * 60L * 1_000L
private const val CLINICAL_SUMMARY_MIN_COVERAGE_PCT = 70.0
private const val CLINICAL_SUMMARY_GOOD_COVERAGE_PCT = 90.0
private const val CLINICAL_SUMMARY_INSUFFICIENT_COVERAGE_PCT = 30.0
private const val CLINICAL_MAX_GAP_MINUTES = 43_200
private const val CLINICAL_MAX_TIMESTAMP_MS = 253_402_300_799_999L

internal fun MainUiState.toUamExportControlUi(
    referenceTs: Long = System.currentTimeMillis()
): UamExportControlUi {
    val currentUam = resolveCurrentUam()
    val mode = if (!enableUamInference) {
        UamExportUiMode.OFF.name
    } else {
        UamExportUiModeCommand.resolve(
            enabled = enableUamExportToAaps,
            persistedModeRaw = uamExportMode,
            dryRun = dryRunExport
        ).name
    }
    val runtimeStatus = resolveUamRuntimeStatus(
        mode = mode,
        rawState = currentUam.state,
        rawReason = currentUam.reason
    )
    val runtimeReasonStatus = mapKnownUamRuntimeReason(currentUam.reason)
        ?.takeUnless { it == runtimeStatus }
    val relevantExportReason = currentRelevantUamExportBlockReason(
        currentUamActive = currentUam.active,
        events = uamEventRows,
        referenceTs = referenceTs
    )
    return UamExportControlUi(
        available = enableUamInference,
        mode = mode,
        runtimeStatus = runtimeStatus,
        runtimeReasonStatus = runtimeReasonStatus,
        estimatedCarbsGrams = currentUam.carbsGrams,
        confidence = currentUam.confidence,
        active = currentUam.active,
        exportBlockedStatus = mapKnownUamRuntimeReason(relevantExportReason)
    )
}

internal fun currentRelevantUamExportBlockReason(
    currentUamActive: Boolean,
    events: List<UamEventRowUi>,
    referenceTs: Long,
    ttlMs: Long = UAM_EXPORT_REASON_UI_TTL_MS
): String? {
    if (!currentUamActive || referenceTs < 0L || ttlMs < 0L) return null
    val latest = events.maxWithOrNull(
        compareBy<UamEventRowUi> { maxOf(it.updatedAt, it.ingestionTs, it.createdAt) }
            .thenBy { it.id }
    ) ?: return null
    val state = latest.state.trim().uppercase(Locale.US)
    if (state !in setOf("CONFIRMED", "ACTIVE")) return null
    val eventTs = maxOf(latest.updatedAt, latest.ingestionTs, latest.createdAt)
    val ageMs = referenceTs - eventTs
    if (eventTs < 0L || ageMs !in 0L..ttlMs) return null
    return latest.exportBlockedReason?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun resolveUamRuntimeStatus(
    mode: String,
    rawState: String?,
    rawReason: String?
): UamRuntimeStatusUi {
    val state = rawState?.trim()?.uppercase(Locale.US)
    val knownReason = mapKnownUamRuntimeReason(rawReason)
    return when (state) {
        "ACTIVE" -> UamRuntimeStatusUi.ACTIVE
        "DECAYING" -> UamRuntimeStatusUi.DECAYING
        "SUSPECTED" -> UamRuntimeStatusUi.SIGNAL_UNSTABLE
        "BLOCKED" -> knownReason
            ?: if (mode == "OBSERVE") {
                UamRuntimeStatusUi.OBSERVATION_ACTIVE
            } else {
                UamRuntimeStatusUi.INACTIVE
            }
        "INACTIVE", "DISABLED", "NONE", null, "" -> {
            if (mode == "OBSERVE" && containsDryRunCode(rawReason)) {
                UamRuntimeStatusUi.OBSERVATION_ACTIVE
            } else {
                UamRuntimeStatusUi.INACTIVE
            }
        }
        else -> knownReason ?: UamRuntimeStatusUi.INACTIVE
    }
}

internal fun mapKnownUamRuntimeReason(rawReason: String?): UamRuntimeStatusUi? {
    val statuses = rawReason
        ?.split('|')
        ?.asSequence()
        ?.map { it.trim().lowercase(Locale.US) }
        ?.mapNotNull(UAM_REASON_STATUS_BY_CODE::get)
        ?.toSet()
        .orEmpty()
    return UAM_REASON_STATUS_PRIORITY.firstOrNull(statuses::contains)
}

private fun containsDryRunCode(rawReason: String?): Boolean = rawReason
    ?.split('|')
    ?.any { token ->
        token.trim().lowercase(Locale.US) in setOf("dry_run", "dry run", "dry-run")
    } == true

private val UAM_REASON_STATUS_PRIORITY = listOf(
    UamRuntimeStatusUi.LOW_GLUCOSE_RISK,
    UamRuntimeStatusUi.SENSOR_BLOCKED,
    UamRuntimeStatusUi.DATA_STALE,
    UamRuntimeStatusUi.FORECAST_UNAVAILABLE,
    UamRuntimeStatusUi.MANUAL_CARBS,
    UamRuntimeStatusUi.COB_ACTIVE,
    UamRuntimeStatusUi.THERAPY_COVERAGE_LOW,
    UamRuntimeStatusUi.CONFIDENCE_LOW,
    UamRuntimeStatusUi.SIGNAL_UNSTABLE,
    UamRuntimeStatusUi.RECONCILIATION_REQUIRED,
    UamRuntimeStatusUi.OUTCOME_UNKNOWN,
    UamRuntimeStatusUi.RESERVATION_PENDING,
    UamRuntimeStatusUi.RATE_LIMITED,
    UamRuntimeStatusUi.INTERVAL_WAIT,
    UamRuntimeStatusUi.CAPACITY_REACHED
)

private val UAM_REASON_STATUS_BY_CODE = buildMap {
    listOf(
        "sensor_blocked",
        "invalid_sensor_trust",
        "sensor_trust_below_min",
        "sensor_trust_below_control_threshold",
        "invalid_sensor_quality",
        "invalid_latest_causal_glucose"
    ).forEach { put(it, UamRuntimeStatusUi.SENSOR_BLOCKED) }
    listOf(
        "stale_canonical_glucose",
        "source_snapshot_stale",
        "runtime_snapshot_stale",
        "iob_sample_missing_or_stale"
    ).forEach { put(it, UamRuntimeStatusUi.DATA_STALE) }
    listOf(
        "invalid_confidence",
        "confidence_below_initial_min",
        "confidence_below_continuation_min",
        "confidence_below_control_threshold"
    ).forEach { put(it, UamRuntimeStatusUi.CONFIDENCE_LOW) }
    listOf(
        "lower_bound_not_stable",
        "insufficient_canonical_glucose",
        "implausible_canonical_delta",
        "invalid_signed_residual",
        "signed_residual_not_positive",
        "invalid_short_average",
        "short_average_not_positive",
        "falling_short_trend",
        "active_time_missing",
        "event_not_confirmed",
        "csf_unavailable",
        "invalid_supported_lower_bound"
    ).forEach { put(it, UamRuntimeStatusUi.SIGNAL_UNSTABLE) }
    listOf(
        "forecast_diagnostics_missing",
        "zero_forecast_contour",
        "invalid_forecast_minimum"
    ).forEach { put(it, UamRuntimeStatusUi.FORECAST_UNAVAILABLE) }
    listOf(
        "forecast_below_4",
        "current_glucose_below_4"
    ).forEach { put(it, UamRuntimeStatusUi.LOW_GLUCOSE_RISK) }
    listOf(
        "effective_cob_above_max",
        "invalid_effective_cob",
        "manual_cob_active",
        "cob_without_causal_carbs",
        "external_cob_without_causal_carbs"
    ).forEach { put(it, UamRuntimeStatusUi.COB_ACTIVE) }
    put("manual_carbs_nearby", UamRuntimeStatusUi.MANUAL_CARBS)
    listOf(
        "therapy_coverage_below_min",
        "therapy_coverage_below_control_threshold",
        "invalid_therapy_coverage",
        "iob_without_causal_insulin",
        "iob_without_recent_dia_insulin",
        "partial_causal_insulin_coverage",
        "invalid_insulin_evidence_window",
        "invalid_iob"
    ).forEach { put(it, UamRuntimeStatusUi.THERAPY_COVERAGE_LOW) }
    listOf(
        "reconciliation_failed",
        "invalid_ledger",
        "invalid_global_ledger",
        "ledger_entry_in_future",
        "global_ledger_entry_in_future"
    ).forEach { put(it, UamRuntimeStatusUi.RECONCILIATION_REQUIRED) }
    put("post_outcome_unknown", UamRuntimeStatusUi.OUTCOME_UNKNOWN)
    listOf(
        "reservation_failed",
        "reservation_store_unavailable",
        "already_reserved",
        "another_live_episode",
        "delivered_reservation_mark_failed",
        "delivered_reservation_mark_interrupted"
    ).forEach { put(it, UamRuntimeStatusUi.RESERVATION_PENDING) }
    listOf(
        "rate_limited",
        "write_rate_limited",
        "rolling_rate_limited"
    ).forEach { put(it, UamRuntimeStatusUi.RATE_LIMITED) }
    listOf(
        "write_interval_under_10m",
        "active_under_10m"
    ).forEach { put(it, UamRuntimeStatusUi.INTERVAL_WAIT) }
    listOf(
        "episode_capacity_exhausted",
        "rolling_30m_capacity_exhausted",
        "global_rolling_30m_capacity_exhausted",
        "global_rolling_60m_capacity_exhausted",
        "episode_window_closed",
        "supported_lower_bound_fulfilled",
        "ledger_sequence_exhausted"
    ).forEach { put(it, UamRuntimeStatusUi.CAPACITY_REACHED) }
}

private fun MainUiState.chartGlucoseHistory(): List<ChartPointUi> {
    val resolved = glucoseCalibrationResolvedHistoryPoints
        .asSequence()
        .filter { it.value.isFinite() }
        .sortedBy { it.ts }
        .toList()
    if (resolved.isNotEmpty()) return resolved

    return glucoseHistoryPoints
        .asSequence()
        .filter { it.valueMmol.isFinite() }
        .sortedBy { it.timestamp }
        .map { ChartPointUi(ts = it.timestamp, value = it.valueMmol) }
        .toList()
}

private fun MainUiState.metricRuntimeSourceUi(
    requested: String,
    acceptedSnapshot: SensitivityRuntimeSnapshot?,
    acceptedDecision: SensitivityMetricDecision?,
    acceptedCandidates: SensitivityCandidates?,
    acceptedCandidateFreshnessMs: Long?,
    acceptedTimestamp: Long?,
    authoritativeNowTs: Long,
    acceptedIdentityMatches: Boolean = true
): MetricRuntimeSourceUi {
    if (acceptedSnapshot == null || acceptedDecision == null || !acceptedIdentityMatches) {
        return MetricRuntimeSourceUi(requested = requested)
    }

    val actualSource = acceptedDecision.resolved.actualSourceUiName()
    val tupleTimestamp = acceptedTimestamp ?: acceptedSnapshot.timestamp
    return MetricRuntimeSourceUi(
        requested = requested,
        resolved = acceptedDecision.resolved.name,
        selectedValue = acceptedDecision.effective,
        aapsValue = acceptedDecision.rawAaps,
        evidenceValue = acceptedDecision.rawEvidence,
        copilotValue = acceptedDecision.rawCopilot,
        confidence = acceptedDecision.confidence,
        fallbackActive = acceptedDecision.fallbackReason != null,
        availability = MetricRuntimeAvailabilityUi.AVAILABLE,
        actualResolvedSource = actualSource,
        fallbackPath = fallbackPath(
            requested = acceptedDecision.requested.name,
            actual = actualSource,
            fallbackReason = acceptedDecision.fallbackReason
        ),
        fallbackReason = acceptedDecision.fallbackReason,
        acceptedSettingsRevision = acceptedSnapshot.settingsRevision,
        acceptedCycleId = acceptedSnapshot.forecastCycleId,
        acceptedTimestamp = tupleTimestamp,
        acceptedSnapshotTimestamp = acceptedSnapshot.timestamp,
        acceptedAgeMinutes = ageMinutes(tupleTimestamp, authoritativeNowTs),
        acceptedFresh = AcceptedSensitivityTupleFreshness.isFresh(
            tupleTimestamp,
            authoritativeNowTs
        ),
        candidates = acceptedCandidateModels(
            decision = acceptedDecision,
            candidates = acceptedCandidates,
            decisionTimestamp = acceptedSnapshot.timestamp,
            freshnessMs = acceptedCandidateFreshnessMs
        )
    )
}

private fun SensitivityResolvedSource.actualSourceUiName(): String = when (this) {
    SensitivityResolvedSource.AAPS -> "AAPS"
    SensitivityResolvedSource.EVIDENCE_BLEND -> "EVIDENCE"
    SensitivityResolvedSource.COPILOT_NATIVE -> "COPILOT"
}

private fun fallbackPath(
    requested: String,
    actual: String?,
    fallbackReason: String?
): List<String> {
    if (fallbackReason == null || actual == null) return emptyList()
    return when (requested.trim().uppercase(Locale.US)) {
        "AAPS" -> if (actual == "EVIDENCE") {
            listOf("AAPS", "EVIDENCE")
        } else {
            listOf("AAPS", "EVIDENCE", "COPILOT")
        }
        "EVIDENCE" -> when (actual) {
            "AAPS" -> listOf("EVIDENCE", "AAPS")
            "COPILOT" -> if (fallbackReason.contains(";aaps_")) {
                listOf("EVIDENCE", "AAPS", "COPILOT")
            } else {
                listOf("EVIDENCE", "COPILOT")
            }
            else -> emptyList()
        }
        else -> emptyList()
    }
}

private fun acceptedCandidateModels(
    decision: SensitivityMetricDecision,
    candidates: SensitivityCandidates?,
    decisionTimestamp: Long,
    freshnessMs: Long?
): List<MetricCandidateDiagnosticsUi> = listOf(
    acceptedCandidateModel("AAPS", decision.rawAaps, candidates?.aaps, decisionTimestamp, freshnessMs),
    acceptedCandidateModel("EVIDENCE", decision.rawEvidence, candidates?.evidence, decisionTimestamp, freshnessMs),
    acceptedCandidateModel("COPILOT", decision.rawCopilot, candidates?.copilot, decisionTimestamp, freshnessMs)
)

private fun acceptedCandidateModel(
    source: String,
    acceptedRawValue: Double?,
    candidate: SensitivityCandidate?,
    decisionTimestamp: Long,
    freshnessMs: Long?
): MetricCandidateDiagnosticsUi {
    val candidateAgeMs = candidate?.timestamp?.let { timestamp ->
        runCatching { Math.subtractExact(decisionTimestamp, timestamp) }.getOrNull()
    }
    return MetricCandidateDiagnosticsUi(
        source = source,
        value = acceptedRawValue,
        diagnosticsAvailable = candidate != null,
        timestamp = candidate?.timestamp,
        ageMinutesAtDecision = candidateAgeMs?.takeIf { it >= 0L }?.div(60_000L),
        freshAtDecision = candidate?.timestamp?.let {
            if (candidateAgeMs == null || freshnessMs == null) null else candidateAgeMs in 0L..freshnessMs
        },
        confidence = candidate?.confidence,
        sampleCount = candidate?.sampleCount,
        coverage = candidate?.coverage,
        qualityPassed = candidate?.qualityPassed,
        unavailableReason = candidate?.unavailableReason
    )
}

private fun ageMinutes(timestamp: Long?, nowTs: Long): Long? = timestamp?.let {
    runCatching { Math.subtractExact(nowTs, it) }.getOrNull()
        ?.takeIf { ageMs -> ageMs >= 0L }
        ?.div(60_000L)
}

private fun formatDurationCompact(durationMs: Long): String {
    val totalMinutes = (durationMs / 60_000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        else -> "${minutes}m"
    }
}

internal data class CurrentIsfCrUi(
    val calculatedIsfMmolPerUnit: Double?,
    val calculatedCrGramsPerUnit: Double?,
    val runtimeIsfMmolPerUnit: Double?,
    val runtimeCrGramsPerUnit: Double?
)

internal data class CurrentUamUi(
    val active: Boolean,
    val carbsGrams: Double?,
    val confidence: Double?,
    val state: String?,
    val reason: String?,
    val source: String?
)

internal fun MainUiState.resolveCurrentIsfCr(): CurrentIsfCrUi {
    val latestCalculatedIsf = isfCrHistoryPoints
        .asSequence()
        .filter { it.isfCalculated?.isFinite() == true }
        .maxByOrNull { it.timestamp }
        ?.isfCalculated
        ?: profileCalculatedIsf
    val latestCalculatedCr = isfCrHistoryPoints
        .asSequence()
        .filter { it.crCalculated?.isFinite() == true }
        .maxByOrNull { it.timestamp }
        ?.crCalculated
        ?: profileCalculatedCr
    val latestFallbackIsf = isfCrHistoryPoints
        .asSequence()
        .filter { it.isfMerged.isFinite() }
        .maxByOrNull { it.timestamp }
        ?.isfMerged
    val latestFallbackCr = isfCrHistoryPoints
        .asSequence()
        .filter { it.crMerged.isFinite() }
        .maxByOrNull { it.timestamp }
        ?.crMerged
    val latestAapsIsf = isfCrHistoryPoints
        .asSequence()
        .filter { it.isfAaps?.isFinite() == true }
        .maxByOrNull { it.timestamp }
        ?.isfAaps
    val latestAapsCr = isfCrHistoryPoints
        .asSequence()
        .filter { it.crAaps?.isFinite() == true }
        .maxByOrNull { it.timestamp }
        ?.crAaps
    val realtimeOverride =
        isfCrRealtimeMode.equals("FALLBACK", ignoreCase = true) ||
            isfCrRealtimeMode.equals("SPARSE_REAL_FETCHED", ignoreCase = true)

    return CurrentIsfCrUi(
        calculatedIsfMmolPerUnit = latestCalculatedIsf,
        calculatedCrGramsPerUnit = latestCalculatedCr,
        runtimeIsfMmolPerUnit = when {
            realtimeOverride && isfCrRealtimeIsfEff != null -> isfCrRealtimeIsfEff
            isfCrRealtimeIsfBase != null -> isfCrRealtimeIsfBase
            latestFallbackIsf != null -> latestFallbackIsf
            else -> profileIsf ?: latestAapsIsf
        },
        runtimeCrGramsPerUnit = when {
            realtimeOverride && isfCrRealtimeCrEff != null -> isfCrRealtimeCrEff
            isfCrRealtimeCrBase != null -> isfCrRealtimeCrBase
            latestFallbackCr != null -> latestFallbackCr
            else -> profileCr ?: latestAapsCr
        }
    )
}

internal fun MainUiState.resolveCurrentUam(): CurrentUamUi {
    val state = uamRuntimeState
        ?.trim()
        ?.uppercase(Locale.US)
        ?.takeIf { it.isNotBlank() }
    val active = uamRuntimeActive == true && state in setOf("ACTIVE", "DECAYING")
    return CurrentUamUi(
        active = active,
        carbsGrams = uamRuntimeEquivalentCarbsGrams
            ?.takeIf { active && it.isFinite() && it >= 0.0 },
        confidence = uamRuntimeConfidence
            ?.takeIf { it.isFinite() }
            ?.coerceIn(0.0, 1.0),
        state = state,
        reason = uamRuntimeReason,
        source = uamRuntimeSource
    )
}

internal fun MainUiState.toAppHealthUiState(): AppHealthUiState {
    val syncLine = syncStatusLines.firstOrNull { it.startsWith("Nightscout last sync", ignoreCase = true) }
        ?: syncStatusLines.firstOrNull()
        ?: "--"
    return AppHealthUiState(
        staleData = isDataStale(),
        killSwitchEnabled = killSwitch,
        lastSyncText = syncLine
    )
}

/**
 * Derives an Overview-only indicator. It is deliberately read-only: the status
 * does not evaluate a target, trigger automation, or expose a writer.
 */
internal fun energyActivityOverviewStatus(
    settings: EnergyProfileSettings,
    snapshot: EnergyProfileSnapshotEntity?,
    events: List<PlannedActivityEventEntity>,
    now: Instant = Instant.now(),
    policy: EnergyProfilePolicy = EnergyProfilePolicy()
): EnergyActivityStatusUi? {
    if (!settings.enabled) return null
    if (policy.resolve(settings, now.atZone(ZoneId.systemDefault()).toLocalDate()) !is ProfileResolution.Complete ||
        snapshot == null || snapshot.stale
    ) {
        return EnergyActivityStatusUi(EnergyActivityStatusKind.PROFILE_ISSUE)
    }

    val engine = ActivityScheduleEngine()
    val eventTitles = events.associate { event -> event.eventId to event.title }
    val occurrences = events.asSequence()
        .filter(PlannedActivityEventEntity::enabled)
        .mapNotNull(PlannedActivityEventEntity::toPlannedActivityScheduleOrNull)
        .flatMap { schedule ->
            val zone = ZoneId.of(schedule.timezoneId)
            val eventDate = now.atZone(zone).toLocalDate()
            sequenceOf(eventDate, eventDate.plusDays(1))
                .mapNotNull { date -> engine.materialize(schedule, date) }
        }
        .sortedBy { it.start }
        .toList()

    occurrences.firstOrNull { occurrence ->
        !now.isBefore(occurrence.start) && now.isBefore(occurrence.end)
    }?.let { occurrence ->
        return EnergyActivityStatusUi(
            kind = EnergyActivityStatusKind.ACTIVE,
            title = eventTitles[occurrence.eventId]
        )
    }
    occurrences.firstOrNull { occurrence ->
        !now.isBefore(occurrence.evaluationStarts) && now.isBefore(occurrence.start)
    }?.let { occurrence ->
        return EnergyActivityStatusUi(
            kind = EnergyActivityStatusKind.APPROACHING,
            title = eventTitles[occurrence.eventId],
            minutesUntilStart = java.time.Duration.between(now, occurrence.start).toMinutes().coerceAtLeast(0L)
        )
    }
    return null
}

private fun PlannedActivityEventEntity.toPlannedActivityScheduleOrNull(): PlannedActivitySchedule? {
    val type = runCatching { PlannedActivityType.valueOf(activityType) }.getOrNull() ?: return null
    val intensity = runCatching { PlannedActivityIntensity.valueOf(intensity) }.getOrNull() ?: return null
    val start = runCatching { LocalDateTime.parse(localStartIso) }.getOrNull() ?: return null
    val zone = runCatching { ZoneId.of(timezoneId) }.getOrNull() ?: return null
    val recurrenceEnd = recurrenceEndEpochDay?.let { epochDay ->
        runCatching { LocalDate.ofEpochDay(epochDay) }.getOrNull() ?: return null
    }
    val recurrenceDays = java.time.DayOfWeek.entries.filter { day ->
        recurrenceDaysMask and (1 shl (day.value - 1)) != 0
    }.toSet()
    return PlannedActivitySchedule(
        eventId = eventId,
        enabled = enabled,
        title = title,
        type = type,
        intensity = intensity,
        localStart = start,
        durationMinutes = durationMinutes,
        timezoneId = zone.id,
        recurrenceDays = recurrenceDays,
        recurrenceEndEpochDay = recurrenceEnd?.toEpochDay(),
        revision = revision,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs
    )
}

internal fun MainUiState.toOverviewUiState(
    isProMode: Boolean,
    baseTargetPresentation: BaseTargetSchedulePresentation? = null,
    authoritativeIsfSource: String = isfRuntimeSourcePreference,
    authoritativeCrSource: String = crRuntimeSourcePreference,
    authoritativeSensitivitySettingsRevision: Long = Long.MIN_VALUE,
    authoritativeTargetManagerMode: String = targetManagerMode,
    authoritativeTargetManagerPriorityEnabled: Boolean = targetManagerCopilotPriorityEnabled,
    authoritativeTargetManagerPolicyRevision: Long = targetManagerPolicyRevision,
    acceptedCandidateDiagnostics: AcceptedSensitivityCandidateDiagnostics? = null,
    authoritativeNowTs: Long = System.currentTimeMillis(),
    eventTimeline: List<CompensationEvent> = events,
    eventTimelineNowTs: Long = System.currentTimeMillis(),
    profileSex: PhysiologicalSex = physiologicalSex,
    showEventsOnGraph: Boolean = true
): OverviewUiState {
    val displayedCob = latestExternalCobGrams?.takeIf { value ->
        val timestamp = latestExternalCobTimestamp
        value.isFinite() && value in 0.0..400.0 && timestamp != null && timestamp > 0L &&
            timestamp <= authoritativeNowTs &&
            authoritativeNowTs - timestamp <= staleDataMaxMinutes.coerceIn(5, 60) * 60_000L
    }
    val targetPresentation = baseTargetPresentation ?: BaseTargetSchedulePresentation(
        schedule = BaseTargetSchedule.legacy(baseTargetMmol),
        effectiveTargetMmol = baseTargetMmol,
        autoDeltaMmol = 0.0,
        autoState = CircadianAutoState.OFF
    )
    val targetManagerStatus = mapTargetManagerLiveStatusForUi(
        status = targetManagerLiveStatus,
        authoritativeMode = authoritativeTargetManagerMode,
        authoritativePriorityEnabled = authoritativeTargetManagerPriorityEnabled,
        authoritativePolicyRevision = authoritativeTargetManagerPolicyRevision,
        authoritativeNowTs = authoritativeNowTs
    )
    val error = inferErrorText()
    val acceptedSnapshot = acceptedSensitivityRuntimeSnapshot
    val acceptedIdentityMatches = acceptedSnapshot != null &&
        forecastAcceptedGenerationTs?.let { generationTs ->
            AcceptedSensitivityTupleFreshness.isFresh(generationTs, authoritativeNowTs)
        } == true &&
        acceptedSnapshot.settingsRevision == authoritativeSensitivitySettingsRevision &&
        acceptedSnapshot.isf.requested.name.equals(authoritativeIsfSource, ignoreCase = true) &&
            acceptedSnapshot.cr.requested.name.equals(authoritativeCrSource, ignoreCase = true)
    val uamRuntimeIdentityMatches = if (acceptedSnapshot != null) {
        acceptedIdentityMatches
    } else {
        !isfRuntimeSourcePreference.equals("UNAVAILABLE", ignoreCase = true) &&
            !crRuntimeSourcePreference.equals("UNAVAILABLE", ignoreCase = true) &&
            isfRuntimeSourcePreference.equals(authoritativeIsfSource, ignoreCase = true) &&
            crRuntimeSourcePreference.equals(authoritativeCrSource, ignoreCase = true)
    }
    val matchedCandidateDiagnostics = acceptedCandidateDiagnostics
        ?.takeIf { diagnostics ->
            acceptedSnapshot != null && diagnostics.matches(acceptedSnapshot)
        }
    val horizons = if (acceptedIdentityMatches) buildHorizonPredictions() else emptyList()
    val sensorLagRolloutVerdict = buildOverviewSensorLagRolloutVerdict()
    val now = authoritativeNowTs
    val powerSaveActive = PowerSaveController.isActiveUntil(powerSaveUntilMs, now)
    val powerSaveIndefinite = PowerSaveController.isIndefinite(powerSaveUntilMs)
    val powerSaveRemainingText = when {
        !powerSaveActive -> ""
        powerSaveIndefinite -> ""
        else -> formatDurationCompact((powerSaveUntilMs - now).coerceAtLeast(0L))
    }
    val chartSource = chartGlucoseHistory()
    val chartNowTs = chartSource.maxOfOrNull { it.ts } ?: now
    val chartHistoryCutoffTs = chartNowTs - OVERVIEW_INTERACTIVE_HISTORY_WINDOW_MS
    val history = chartSource
        .asSequence()
        .filter { point -> point.ts in chartHistoryCutoffTs..chartNowTs }
        .toList()
    val currentUam = if (uamRuntimeIdentityMatches) {
        resolveCurrentUam()
    } else {
        CurrentUamUi(false, null, null, "INACTIVE", "accepted_identity_mismatch", null)
    }
    val displayedIsfSource = authoritativeIsfSource
    val displayedCrSource = authoritativeCrSource
    val warning = when {
        glucoseAlertStrongActive == true -> OverviewWarningUi(
            kind = OverviewWarningKind.STRONG_GLUCOSE_ALERT,
            detail = glucoseAlertDirection
        )
        isDataStale() || sensorQualityBlocked == true -> OverviewWarningUi(
            kind = OverviewWarningKind.SENSOR_OR_STALE,
            detail = sensorQualityReason ?: sensorLagDisableReason
        )
        killSwitch -> OverviewWarningUi(OverviewWarningKind.KILL_SWITCH)
        powerSaveActive -> OverviewWarningUi(OverviewWarningKind.POWER_SAVE)
        glucoseAlertSoftActive == true -> OverviewWarningUi(
            kind = OverviewWarningKind.SOFT_GLUCOSE_ALERT,
            detail = glucoseAlertDirection
        )
        else -> null
    }

    val telemetryChips = buildList {
        add(TelemetryChipUi("IOB", UiFormatters.formatUnits(latestIobUnits), "U"))
        if (isProMode && latestIobRealUnits != null) {
            add(TelemetryChipUi("IOB net", UiFormatters.formatUnits(latestIobRealUnits), "U"))
        }
        if (isProMode && latestIobBolusUnits != null) {
            add(TelemetryChipUi("IOB bolus", UiFormatters.formatUnits(latestIobBolusUnits), "U"))
        }
        if (isProMode && latestIobBasalUnits != null) {
            add(TelemetryChipUi("IOB basal", UiFormatters.formatUnits(latestIobBasalUnits), "U"))
        }
        if (isProMode && latestInsulinActivity != null) {
            add(TelemetryChipUi("Insulin activity", UiFormatters.formatDecimalOrPlaceholder(latestInsulinActivity, 3), "U/min"))
        }
        if (isProMode && !latestIobRuntimeSource.isNullOrBlank()) {
            val confidence = latestIobRuntimeConfidence
                ?.let { " ${UiFormatters.formatDecimalOrPlaceholder(it * 100.0, 0)}%" }
                .orEmpty()
            add(TelemetryChipUi("IOB source", latestIobRuntimeSource.orEmpty() + confidence, null))
        }
        add(TelemetryChipUi("COB", UiFormatters.formatGrams(displayedCob), "g"))
        if (isProMode && latestCobGrams != null) {
            add(TelemetryChipUi("COB effective", UiFormatters.formatGrams(latestCobGrams), "g"))
        }
        if (sensorLagMinutes != null && sensorLagMode?.equals("OFF", ignoreCase = true) != true && !sensorLagMode.isNullOrBlank()) {
            add(
                TelemetryChipUi(
                    "Lag",
                    UiFormatters.formatDecimalOrPlaceholder(sensorLagMinutes, decimals = 0),
                    "min"
                )
            )
        }
        if (insulinRealOnsetMinutes != null) {
            add(
                TelemetryChipUi(
                    "Onset real",
                    UiFormatters.formatDecimalOrPlaceholder(insulinRealOnsetMinutes, decimals = 0),
                    "min"
                )
            )
        }
        add(TelemetryChipUi("Activity", UiFormatters.formatDecimalOrPlaceholder(latestActivityRatio, decimals = 2), "ratio"))
        add(TelemetryChipUi("Steps", UiFormatters.formatDecimalOrPlaceholder(latestStepsCount, decimals = 0), null))
    }

    val hasData = latestGlucoseMmol != null || horizons.any { it.pred != null }
    return OverviewUiState(
        loadState = resolveLoadState(hasData = hasData, errorText = error),
        isStale = isDataStale(),
        errorText = error,
        isProMode = isProMode,
        glucose = calibratedGlucoseMmol ?: latestGlucoseMmol,
        rawGlucose = rawGlucoseMmol ?: latestGlucoseMmol,
        calibratedGlucose = calibratedGlucoseMmol,
        correctedGlucose = correctedGlucoseMmol,
        delta = glucoseDelta,
        sampleAgeMinutes = latestDataAgeMinutes,
        calibrationGain = glucoseCalibrationGain,
        calibrationOffsetMmol = glucoseCalibrationOffsetMmol,
        calibrationConfidence = glucoseCalibrationConfidence,
        calibrationModelType = glucoseCalibrationModelType,
        calibrationStatus = glucoseCalibrationStatus,
        calibrationLastCheckAgeMinutes = glucoseCalibrationLastCheckAgeMinutes,
        glucoseAlertState = glucoseAlertState,
        glucoseAlertDirection = glucoseAlertDirection,
        glucoseAlertDisableReason = glucoseAlertDisableReason,
        glucoseAlertSoftActive = glucoseAlertSoftActive == true,
        glucoseAlertStrongActive = glucoseAlertStrongActive == true,
        sensorLagMode = sensorLagMode,
        sensorLagMinutes = sensorLagMinutes,
        sensorLagDisableReason = sensorLagDisableReason,
        sensorLagRolloutVerdict = sensorLagRolloutVerdict,
        events = eventTimeline,
        eventTimelineNowTs = eventTimelineNowTs,
        physiologicalSex = profileSex,
        chart = ClinicalForecastChartUiState(
            historyPoints = history,
            futurePath = if (acceptedIdentityMatches) buildInterpolatedFuturePath(nowTs = chartNowTs) else emptyList(),
            futureCi = if (acceptedIdentityMatches) buildInterpolatedFutureCi(nowTs = chartNowTs) else emptyList(),
            events = eventTimeline,
            eventTimelineNowTs = eventTimelineNowTs,
            showEvents = showEventsOnGraph
        ),
        baseTargetMmol = baseTargetMmol,
        baseTargetSchedule = targetPresentation.schedule,
        effectiveBaseTargetMmol = targetPresentation.effectiveTargetMmol,
        baseTargetAutoDeltaMmol = targetPresentation.autoDeltaMmol,
        baseTargetAutoState = targetPresentation.autoState,
        baseTargetAutoReason = targetPresentation.autoReason,
        targetManagerLiveStatus = targetManagerStatus,
        targetEditMinMmol = safetyMinTargetMmol,
        targetEditMaxMmol = safetyMaxTargetMmol,
        currentIobUnits = latestIobUnits,
        iobDetails = IobRuntimeDetailsUi(
            effectivePositiveIobUnits = latestIobUnits,
            signedNetIobUnits = latestIobRealUnits,
            bolusIobUnits = latestIobBolusUnits,
            basalIobUnits = latestIobBasalUnits,
            insulinActivity = latestInsulinActivity,
            actualSource = latestIobRuntimeSource,
            sampleTimestamp = latestIobRuntimeTimestamp,
            sampleAgeMinutes = ageMinutes(latestIobRuntimeTimestamp, authoritativeNowTs),
            confidence = latestIobRuntimeConfidence,
            evidenceTimestamp = latestIobEvidenceTimestamp,
            therapyCoverage = latestIobTherapyCoverage,
            fallbackReason = latestIobRuntimeFallbackReason
        ),
        currentCobGrams = displayedCob,
        currentIsfMmolPerUnit = acceptedSnapshot
            ?.takeIf { acceptedIdentityMatches }
            ?.isf
            ?.effective,
        currentCrGramsPerUnit = acceptedSnapshot
            ?.takeIf { acceptedIdentityMatches }
            ?.cr
            ?.effective,
        sensitivitySourceApplying = sensitivitySourceApplying,
        sensitivitySourcePendingMetric = sensitivitySourcePendingMetric,
        sensitivitySourcePendingValue = sensitivitySourcePendingValue,
        sensitivitySourceApplyError = sensitivitySourceApplyError,
        carbComputationMaxGrams = carbComputationMaxGrams,
        isfRuntime = metricRuntimeSourceUi(
            requested = displayedIsfSource,
            acceptedSnapshot = acceptedSnapshot,
            acceptedDecision = acceptedSnapshot?.isf,
            acceptedCandidates = matchedCandidateDiagnostics?.isfCandidates,
            acceptedCandidateFreshnessMs = matchedCandidateDiagnostics?.freshnessMs,
            acceptedTimestamp = forecastAcceptedGenerationTs,
            authoritativeNowTs = authoritativeNowTs,
            acceptedIdentityMatches = acceptedIdentityMatches
        ),
        crRuntime = metricRuntimeSourceUi(
            requested = displayedCrSource,
            acceptedSnapshot = acceptedSnapshot,
            acceptedDecision = acceptedSnapshot?.cr,
            acceptedCandidates = matchedCandidateDiagnostics?.crCandidates,
            acceptedCandidateFreshnessMs = matchedCandidateDiagnostics?.freshnessMs,
            acceptedTimestamp = forecastAcceptedGenerationTs,
            authoritativeNowTs = authoritativeNowTs,
            acceptedIdentityMatches = acceptedIdentityMatches
        ),
        calculatedUamCarbsGrams = currentUam.carbsGrams,
        calculatedUamConfidence = currentUam.confidence,
        uamExport = toUamExportControlUi(),
        warning = warning,
        horizons = horizons,
        uamActive = currentUam.active,
        uci0Mmol5m = calculatedUci0Mmol5m.takeIf { uamRuntimeIdentityMatches },
        inferredCarbsLast60g = currentUam.carbsGrams,
        uamModeLabel = if (enableUamBoost) "BOOST" else "NORMAL",
        telemetryChips = telemetryChips,
        lastAction = lastAction?.toUi(),
        canRunCycleNow = !powerSaveActive,
        powerSaveActive = powerSaveActive,
        powerSaveUntilMs = powerSaveUntilMs,
        powerSaveIndefinite = powerSaveIndefinite,
        powerSaveRemainingText = powerSaveRemainingText,
        killSwitchEnabled = killSwitch
    )
}

internal fun mapTargetManagerLiveStatusForUi(
    status: TargetManagerLiveStatus?,
    authoritativeMode: String,
    authoritativePriorityEnabled: Boolean,
    authoritativePolicyRevision: Long,
    authoritativeNowTs: Long
): TargetManagerLiveStatusUi {
    if (authoritativeMode.equals("OFF", ignoreCase = true)) {
        return TargetManagerLiveStatusUi(availability = TargetManagerLiveStatusAvailabilityUi.PAUSED)
    }
    status ?: return TargetManagerLiveStatusUi(
        availability = TargetManagerLiveStatusAvailabilityUi.UNAVAILABLE
    )
    if (!TargetManagerLiveStatusCodec.isFresh(status.timestamp, authoritativeNowTs)) {
        return TargetManagerLiveStatusUi(
            availability = TargetManagerLiveStatusAvailabilityUi.STALE
        )
    }
    if (
        !status.mode.equals(authoritativeMode, ignoreCase = true) ||
        status.priorityEnabled != authoritativePriorityEnabled ||
        status.policyRevision != authoritativePolicyRevision
    ) {
        return TargetManagerLiveStatusUi(
            availability = TargetManagerLiveStatusAvailabilityUi.WAITING
        )
    }
    return TargetManagerLiveStatusUi(
        availability = TargetManagerLiveStatusAvailabilityUi.CURRENT,
        timestamp = status.timestamp,
        mode = status.mode,
        priorityEnabled = status.priorityEnabled,
        policyRevision = status.policyRevision,
        currentTargetMmol = status.currentTargetMmol,
        proposedTargetMmol = status.proposedTargetMmol,
        outcome = status.outcome,
        reason = status.reason
    )
}

internal fun OverviewUiState.withSensitivityApplyPresentation(
    applying: Boolean,
    pendingMetric: String?,
    pendingValue: String?,
    error: String?
): OverviewUiState {
    if (!applying) {
        return copy(
            sensitivitySourceApplying = false,
            sensitivitySourcePendingMetric = pendingMetric,
            sensitivitySourcePendingValue = pendingValue,
            sensitivitySourceApplyError = error
        )
    }
    val normalizedPendingMetric = pendingMetric?.trim()?.uppercase(Locale.US)
    fun applyingRuntime(metric: String, previous: MetricRuntimeSourceUi): MetricRuntimeSourceUi {
        val isPending = normalizedPendingMetric == metric
        return MetricRuntimeSourceUi(
            requested = if (isPending) {
                pendingValue?.trim()?.uppercase(Locale.US) ?: previous.requested
            } else {
                previous.requested
            },
            availability = if (isPending) {
                MetricRuntimeAvailabilityUi.APPLYING
            } else {
                MetricRuntimeAvailabilityUi.UNAVAILABLE
            }
        )
    }
    return copy(
        currentIsfMmolPerUnit = null,
        currentCrGramsPerUnit = null,
        isfRuntime = applyingRuntime("ISF", isfRuntime),
        crRuntime = applyingRuntime("CR", crRuntime),
        horizons = emptyList(),
        chart = chart.copy(futurePath = emptyList(), futureCi = emptyList()),
        sensitivitySourceApplying = true,
        sensitivitySourcePendingMetric = pendingMetric,
        sensitivitySourcePendingValue = pendingValue,
        sensitivitySourceApplyError = error
    )
}

internal fun MainUiState.toForecastUiState(
    range: ForecastRangeUi,
    layers: ForecastLayerState,
    isProMode: Boolean
): ForecastUiState {
    val error = inferErrorText()
    val horizons = buildHorizonPredictions()

    val chartSource = chartGlucoseHistory()
    val nowTs = chartSource.lastOrNull()?.ts ?: System.currentTimeMillis()
    val historyCutoff = nowTs - range.hours * 60 * 60_000L
    val history = chartSource.filter { it.ts >= historyCutoff }

    val futurePath = buildInterpolatedFuturePath(nowTs = nowTs)
    val futureCi = buildInterpolatedFutureCi(nowTs = nowTs)

    val quality = qualityMetrics.map {
        String.format(
            Locale.US,
            "%dm MAE %.2f | RMSE %.2f | MARD %.1f%% | n=%d",
            it.horizonMinutes,
            it.mae,
            it.rmse,
            it.mardPct,
            it.sampleCount
        )
    }
    val sensorLagModeValue = sensorLagMode
    val sensorLagDisableReasonValue = sensorLagDisableReason
    val sensorLagLines = buildList {
        if (!sensorLagModeValue.isNullOrBlank() && sensorLagModeValue.equals("OFF", ignoreCase = true).not()) {
            add(
                "Sensor lag ${sensorLagModeValue.uppercase(Locale.US)}: corrected " +
                    "${UiFormatters.formatMmol(correctedGlucoseMmol, 2)} mmol/L, lag " +
                    "${UiFormatters.formatDecimalOrPlaceholder(sensorLagMinutes, 0)} min, age " +
                    "${UiFormatters.formatDecimalOrPlaceholder(sensorAgeHours, 0)} h (${sensorAgeSource ?: "--"})"
            )
        }
        if (!sensorLagDisableReasonValue.isNullOrBlank()) {
            add("Sensor lag gate: ${sensorLagDisableReasonValue.replace('_', ' ')}")
        }
    }
    val patternLines = if (isProMode) {
        buildList {
            if (patternPrior30Mmol != null || patternPrior60Mmol != null || patternPriorConfidence != null) {
                add(
                    "Pattern prior Δ30/Δ60 ${UiFormatters.formatMmol(patternPrior30Mmol, 2)}/" +
                        "${UiFormatters.formatMmol(patternPrior60Mmol, 2)} mmol · conf " +
                        UiFormatters.formatPercent(patternPriorConfidence)
                )
            }
            if (patternPriorBgMedianMmol != null || patternPriorSegmentSource != null) {
                add(
                    "Pattern median ${UiFormatters.formatMmol(patternPriorBgMedianMmol, 2)} mmol/L · source " +
                        (patternPriorSegmentSource ?: "--")
                )
            }
            if (patternPriorResidualBias30Mmol != null || patternPriorResidualBias60Mmol != null) {
                add(
                    "Pattern residual 30/60 ${UiFormatters.formatMmol(patternPriorResidualBias30Mmol, 2)}/" +
                        "${UiFormatters.formatMmol(patternPriorResidualBias60Mmol, 2)} mmol"
                )
            }
            if (patternPriorAcuteAttenuation != null || patternPriorStaleBlocked != null) {
                add(
                    "Pattern attenuation ${UiFormatters.formatPercent(patternPriorAcuteAttenuation)} · stale " +
                        (patternPriorStaleBlocked?.let { if (it) "blocked" else "ok" } ?: "--")
                )
            }
        }
    } else {
        emptyList()
    }

    val hasData = history.isNotEmpty() || futurePath.isNotEmpty()
    return ForecastUiState(
        loadState = resolveLoadState(hasData = hasData, errorText = error),
        isStale = isDataStale(),
        errorText = error,
        isProMode = isProMode,
        range = range,
        layers = layers,
        horizons = horizons,
        historyPoints = history,
        futurePath = futurePath,
        futureCi = futureCi,
        decomposition = if (forecastTupleError == null) {
            ForecastDecompositionUi(
                trend60 = trend60ComponentMmol,
                therapy60 = therapy60ComponentMmol,
                uam60 = uam60ComponentMmol,
                residualRoc0 = residualRoc0Mmol5m,
                sigmaE = sigmaEMmol5m,
                kfSigmaG = kfSigmaGMmol
            )
        } else {
            ForecastDecompositionUi()
        },
        qualityLines = sensorLagLines + quality + baselineDeltaLines + patternLines
    )
}

internal fun MainUiState.toUamUiState(isProMode: Boolean = false): UamUiState {
    val currentUam = resolveCurrentUam()
    val visibleLegacyEvents = if (isProMode) uamEventRows else emptyList()
    val hasData = uamRuntimeState != null || uamRuntimeActive != null || visibleLegacyEvents.isNotEmpty()
    return UamUiState(
        loadState = resolveLoadState(hasData = hasData, errorText = null),
        isStale = isDataStale(),
        inferredActive = null,
        inferredCarbsGrams = null,
        inferredConfidence = null,
        calculatedActive = currentUam.active,
        calculatedCarbsGrams = currentUam.carbsGrams,
        calculatedConfidence = currentUam.confidence,
        events = visibleLegacyEvents.map { row ->
            UamEventUi(
                id = row.id,
                state = row.state,
                mode = row.mode,
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
                ingestionTs = row.ingestionTs,
                carbsDisplayG = row.carbsDisplayG,
                confidence = row.confidence,
                exportSeq = row.exportSeq,
                exportedGrams = row.exportedGrams,
                tag = row.tag,
                manualCarbsNearby = row.manualCarbsNearby,
                manualCobActive = row.manualCobActive,
                exportBlockedReason = row.exportBlockedReason
            )
        },
        uamExport = toUamExportControlUi()
    )
}

internal fun MainUiState.toSafetyUiState(): SafetyUiState {
    val tlsLine = transportStatusLines.firstOrNull { it.contains("TLS", ignoreCase = true) }
    val tlsOk = when {
        tlsLine == null -> null
        tlsLine.contains("ok", ignoreCase = true) -> true
        tlsLine.contains("error", ignoreCase = true) || tlsLine.contains("fail", ignoreCase = true) -> false
        else -> null
    }

    val adaptiveMinBound = max(4.0, safetyMinTargetMmol)
    val adaptiveMaxBound = min(10.0, safetyMaxTargetMmol)
    val hardBoundsLabel = "${UiFormatters.formatMmol(safetyMinTargetMmol, 1)}..${UiFormatters.formatMmol(safetyMaxTargetMmol, 1)}"
    val adaptiveBoundsLabel = if (adaptiveMinBound <= adaptiveMaxBound) {
        "${UiFormatters.formatMmol(adaptiveMinBound, 1)}..${UiFormatters.formatMmol(adaptiveMaxBound, 1)}"
    } else {
        "--"
    }

    val checklist = listOf(
        SafetyChecklistItemUi(
            title = "Data freshness",
            ok = !isDataStale(),
            details = "age=${UiFormatters.formatMinutes(latestDataAgeMinutes)} limit=${staleDataMaxMinutes}m"
        ),
        SafetyChecklistItemUi(
            title = "Kill switch",
            ok = !killSwitch,
            details = if (killSwitch) "Automatic actions blocked" else "Automatic actions allowed"
        ),
        SafetyChecklistItemUi(
            title = "Sensor quality",
            ok = sensorQualityBlocked != true,
            details = sensorQualityReason ?: "No sensor block flags"
        ),
        SafetyChecklistItemUi(
            title = "Nightscout local runtime",
            ok = false,
            details = if (localNightscoutEnabled) {
                "runtime state pending (configured port=$localNightscoutPort)"
            } else {
                "disabled"
            }
        )
    )

    return SafetyUiState(
        loadState = ScreenLoadState.READY,
        isStale = isDataStale(),
        killSwitchEnabled = killSwitch,
        staleMinutesLimit = staleDataMaxMinutes,
        hardBounds = hardBoundsLabel,
        hardMinTargetMmol = safetyMinTargetMmol,
        hardMaxTargetMmol = safetyMaxTargetMmol,
        adaptiveBounds = adaptiveBoundsLabel,
        baseTarget = baseTargetMmol,
        maxActionsIn6h = maxActionsIn6Hours,
        glucoseAlertState = glucoseAlertState,
        glucoseAlertDirection = glucoseAlertDirection,
        glucoseAlertDisableReason = glucoseAlertDisableReason,
        glucoseAlertLowThreshold = glucoseAlertLowThreshold,
        glucoseAlertHighThreshold = glucoseAlertHighThreshold,
        glucoseAlertUrgentLowThreshold = glucoseAlertUrgentLowThreshold,
        glucoseAlertSoftLastTs = glucoseAlertSoftLastTs,
        glucoseAlertStrongLastTs = glucoseAlertStrongLastTs,
        glucoseAlertSoftClipLabel = softAlertAudioDisplayName ?: GlucoseAlertAudioProfiles.label(GlucoseAlertAudioSlot.SOFT),
        glucoseAlertCriticalClip1Label = criticalAlertAudio1DisplayName ?: GlucoseAlertAudioProfiles.label(GlucoseAlertAudioSlot.CRITICAL_PRIMARY),
        glucoseAlertCriticalClip2Label = criticalAlertAudio2DisplayName ?: GlucoseAlertAudioProfiles.label(GlucoseAlertAudioSlot.CRITICAL_SECONDARY),
        glucoseAlertSoftClipValid = GlucoseAlertAudioProfiles.isValid(
            slot = GlucoseAlertAudioSlot.SOFT,
            startMs = softAlertAudioStartMs,
            durationMs = softAlertAudioDurationMs
        ),
        glucoseAlertCriticalClip1Valid = GlucoseAlertAudioProfiles.isValid(
            slot = GlucoseAlertAudioSlot.CRITICAL_PRIMARY,
            startMs = criticalAlertAudio1StartMs,
            durationMs = criticalAlertAudio1DurationMs
        ),
        glucoseAlertCriticalClip2Valid = GlucoseAlertAudioProfiles.isValid(
            slot = GlucoseAlertAudioSlot.CRITICAL_SECONDARY,
            startMs = criticalAlertAudio2StartMs,
            durationMs = criticalAlertAudio2DurationMs
        ),
        therapyHistorySourceMode = therapyHistorySourceMode,
        therapyHistoryRawInsulin30d = therapyHistoryRawInsulin30d,
        therapyHistoryInferredInsulin30d = therapyHistoryInferredInsulin30d,
        therapyHistoryRealFetchedInsulin30d = therapyHistoryRealFetchedInsulin30d,
        therapyHistoryRecoveredInsulin30d = therapyHistoryRecoveredInsulin30d,
        therapyHistoryUsableInsulin30d = therapyHistoryUsableInsulin30d,
        therapyHistoryBootstrapNeeded = therapyHistoryBootstrapNeeded,
        therapyHistoryPlateauOnly = therapyHistoryPlateauOnly,
        therapyHistorySyntheticRatioPct = therapyHistorySyntheticRatioPct,
        therapyHistoryLastSyncTreatmentCount = therapyHistoryLastSyncTreatmentCount,
        therapyHistoryLastSyncInsulinLikeCount = therapyHistoryLastSyncInsulinLikeCount,
        therapyHistoryLastSyncCarbLikeCount = therapyHistoryLastSyncCarbLikeCount,
        therapyHistoryLastSyncLocalActionCount = therapyHistoryLastSyncLocalActionCount,
        therapyHistoryUpstreamTempTargetOnly = therapyHistoryUpstreamTempTargetOnly,
        cooldownStatusLines = ruleCooldownLines,
        localNightscoutEnabled = localNightscoutEnabled,
        localNightscoutPort = localNightscoutPort,
        localNightscoutTlsOk = tlsOk,
        localNightscoutTlsStatusText = tlsLine ?: "No TLS diagnostics yet",
        aiTuningStatus = toAiTuningStatusUi(),
        checklist = checklist
    )
}

internal fun MainUiState.toAuditUiState(
    window: AuditWindowUi,
    onlyErrors: Boolean
): AuditUiState {
    val cutoff = System.currentTimeMillis() - window.durationMs
    val filtered = auditRecords
        .asSequence()
        .filter { it.ts >= cutoff }
        .filter {
            if (!onlyErrors) return@filter true
            val lvl = it.level.uppercase(Locale.US)
            lvl == "ERROR" || lvl == "WARN" || it.summary.contains("failed", ignoreCase = true)
        }
        .map {
            AuditItemUi(
                id = it.id,
                ts = it.ts,
                source = it.source,
                level = it.level,
                summary = it.summary,
                context = it.context,
                idempotencyKey = it.idempotencyKey,
                payloadSummary = it.payloadSummary
            )
        }
        .toList()

    return AuditUiState(
        loadState = resolveLoadState(hasData = filtered.isNotEmpty(), errorText = inferErrorText()),
        isStale = isDataStale(),
        errorText = inferErrorText(),
        window = window,
        onlyErrors = onlyErrors,
        rows = filtered
    )
}

internal fun MainUiState.toAnalyticsUiState(
    circadianReplaySummary: CircadianReplaySummaryUi? = null,
    probableMealWindows: List<ProbableEatingWindow> = emptyList(),
    recentProbableMealWindows: List<ProbableEatingWindow> = emptyList()
): AnalyticsUiState {
    val circadianStateStatus = buildCircadianStateStatus()

    fun parseReasonCodes(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { token ->
                val parts = token.split("=", limit = 2)
                if (parts.size == 2 && parts[1].trim().toIntOrNull() != null) {
                    parts[0].trim()
                } else {
                    token
                }
            }
            .distinct()
    }

    val rows = qualityMetrics.map {
        String.format(
            Locale.US,
            "%dm MAE %.2f | RMSE %.2f | MARD %.1f%%",
            it.horizonMinutes,
            it.mae,
            it.rmse,
            it.mardPct
        )
    }
    val all = rows + baselineDeltaLines
    val error = inferErrorText()
    val latestHistoryPoint = isfCrHistoryPoints.maxByOrNull { it.timestamp }
    val latestAapsHistoryPoint = isfCrHistoryPoints
        .asSequence()
        .filter { it.isfAaps != null || it.crAaps != null }
        .maxByOrNull { it.timestamp }
    val currentIsfCr = resolveCurrentIsfCr()
    val currentIsfReal = currentIsfCr.calculatedIsfMmolPerUnit
    val currentCrReal = currentIsfCr.calculatedCrGramsPerUnit
    val currentIsfMerged = currentIsfCr.runtimeIsfMmolPerUnit
    val currentCrMerged = currentIsfCr.runtimeCrGramsPerUnit
    val currentIsfAapsRaw = latestAapsHistoryPoint?.isfAaps
    val currentCrAapsRaw = latestAapsHistoryPoint?.crAaps
    val rawGlucoseMmol = latestGlucoseMmol
    val correctedLagGlucoseMmol = correctedGlucoseMmol
    val sensorLagCorrectionMmol = if (rawGlucoseMmol != null && correctedLagGlucoseMmol != null) {
        correctedLagGlucoseMmol - rawGlucoseMmol
    } else {
        null
    }
    val sensorLagDiagnostics = if (
        sensorLagCorrectionMode.equals("OFF", ignoreCase = true).not() ||
        !sensorLagMode.isNullOrBlank() ||
        correctedGlucoseMmol != null ||
        sensorLagMinutes != null ||
        sensorAgeHours != null ||
        !sensorLagDisableReason.isNullOrBlank()
    ) {
        SensorLagDiagnosticsUi(
            configuredMode = sensorLagCorrectionMode,
            runtimeMode = sensorLagMode,
            rawGlucoseMmol = rawGlucoseMmol,
            correctedGlucoseMmol = correctedLagGlucoseMmol,
            correctionMmol = sensorLagCorrectionMmol,
            lagMinutes = sensorLagMinutes,
            ageHours = sensorAgeHours,
            ageSource = sensorAgeSource,
            confidence = sensorLagConfidence,
            wearBucket = sensorLagWearBucket,
            sourceConfidence = sensorLagSourceConfidence,
            trendConsistency = sensorLagTrendConsistency,
            replayMultiplier = sensorLagReplayMultiplier,
            effectiveLagMinutes = sensorLagEffectiveLagMinutes,
            effectiveCorrectionCap = sensorLagEffectiveCorrectionCap,
            disableReason = sensorLagDisableReason,
            sensorQualityScore = sensorQualityScore,
            sensorQualityBlocked = sensorQualityBlocked,
            sensorQualitySuspectFalseLow = sensorQualitySuspectFalseLow,
            sensorQualityReason = sensorQualityReason,
            lagTrendPoints = sensorLagTrendLagPoints,
            correctionTrendPoints = sensorLagTrendCorrectionPoints,
            modeSegments = sensorLagModeSegments,
            bucketSegments = sensorLagBucketSegments,
            trendStartAgeHours = sensorLagTrendStartAgeHours,
            trendEndAgeHours = sensorLagTrendEndAgeHours
        )
    } else {
        null
    }
    val hasData = all.isNotEmpty() ||
        sensorLagDiagnostics != null ||
        circadianStateStatus != null ||
        dailyReportGeneratedAtTs != null ||
        dailyReportMetrics.isNotEmpty() ||
        dailyReportRecommendations.isNotEmpty() ||
        dailyReportIsfCrQualityLines.isNotEmpty() ||
        dailyReportReplayHotspots.isNotEmpty() ||
        dailyReportReplayFactors.isNotEmpty() ||
        dailyReportReplayCoverage.isNotEmpty() ||
        dailyReportReplayRegimes.isNotEmpty() ||
        dailyReportReplayPairs.isNotEmpty() ||
        dailyReportReplayTopMisses.isNotEmpty() ||
        dailyReportReplayErrorClusters.isNotEmpty() ||
        dailyReportReplayDayTypeGaps.isNotEmpty() ||
        dailyReportSensorLagReplayBuckets.isNotEmpty() ||
        dailyReportSensorLagShadowBuckets.isNotEmpty() ||
        dailyReportMatchedSamples != null ||
        isfCrDroppedReasons24hLines.isNotEmpty() ||
        isfCrDroppedReasons7dLines.isNotEmpty() ||
        isfCrHistoryPoints.isNotEmpty() ||
        circadianPatternSections.isNotEmpty() ||
        currentIsfReal != null ||
        currentCrReal != null ||
        profileIsf != null ||
        profileCr != null

    val selectedProfile = InsulinActionProfileId.fromRaw(insulinProfileId)
    val realInsulinCurve = parseInsulinCurveCompact(insulinRealProfileCurveCompact)
    val hasRealInsulinCurve = realInsulinCurve.size >= 2
    val insulinCurves = InsulinActionProfiles.supportedIds().map { id ->
        val profile = InsulinActionProfiles.profile(id)
        InsulinProfileCurveUi(
            id = id.name,
            label = id.label,
            isUltraRapid = id.isUltraRapid,
            isSelected = id == selectedProfile,
            points = profile.points.map { point ->
                InsulinProfilePointUi(
                    minute = point.minute,
                    cumulative = point.cumulative
                )
            }
        )
    }

    return AnalyticsUiState(
        loadState = resolveLoadState(hasData = hasData, errorText = error),
        isStale = isDataStale(),
        errorText = error,
        calibrationRawCurrentMmol = rawGlucoseMmol ?: latestGlucoseMmol,
        calibrationCalibratedCurrentMmol = calibratedGlucoseMmol,
        calibrationGain = glucoseCalibrationGain,
        calibrationOffsetMmol = glucoseCalibrationOffsetMmol,
        calibrationConfidence = glucoseCalibrationConfidence,
        calibrationModelType = glucoseCalibrationModelType,
        calibrationStatus = glucoseCalibrationStatus,
        calibrationLastCheckAgeMinutes = glucoseCalibrationLastCheckAgeMinutes,
        calibrationRawHistoryPoints = glucoseCalibrationRawHistoryPoints,
        calibrationResolvedHistoryPoints = glucoseCalibrationResolvedHistoryPoints,
        calibrationCheckPoints = glucoseCalibrationCheckPoints,
        sensorLagDiagnostics = sensorLagDiagnostics,
        circadianStateStatus = circadianStateStatus,
        qualityLines = rows,
        baselineDeltaLines = baselineDeltaLines,
        dailyReportGeneratedAtTs = dailyReportGeneratedAtTs,
        dailyReportMatchedSamples = dailyReportMatchedSamples,
        dailyReportForecastRows = dailyReportForecastRows,
        dailyReportPeriodStartUtc = dailyReportPeriodStartUtc,
        dailyReportPeriodEndUtc = dailyReportPeriodEndUtc,
        dailyReportMarkdownPath = dailyReportMarkdownPath,
        dailyReportRecommendations = dailyReportRecommendations,
        dailyReportIsfCrQualityLines = dailyReportIsfCrQualityLines,
        dailyReportReplayHotspots = dailyReportReplayHotspots.map {
            DailyReportReplayHotspotUi(
                horizonMinutes = it.horizonMinutes,
                hour = it.hour,
                sampleCount = it.sampleCount,
                mae = it.mae,
                mardPct = it.mardPct,
                bias = it.bias
            )
        },
        dailyReportReplayFactorContributions = dailyReportReplayFactors.map {
            DailyReportReplayFactorUi(
                horizonMinutes = it.horizonMinutes,
                factor = it.factor,
                sampleCount = it.sampleCount,
                corrAbsError = it.corrAbsError,
                maeHigh = it.maeHigh,
                maeLow = it.maeLow,
                upliftPct = it.upliftPct,
                contributionScore = it.contributionScore
            )
        },
        dailyReportReplayFactorCoverage = dailyReportReplayCoverage.map {
            DailyReportReplayCoverageUi(
                horizonMinutes = it.horizonMinutes,
                factor = it.factor,
                sampleCount = it.sampleCount,
                coveragePct = it.coveragePct
            )
        },
        dailyReportReplayFactorRegimes = dailyReportReplayRegimes.map {
            DailyReportReplayRegimeUi(
                horizonMinutes = it.horizonMinutes,
                factor = it.factor,
                bucket = it.bucket,
                sampleCount = it.sampleCount,
                meanFactorValue = it.meanFactorValue,
                mae = it.mae,
                mardPct = it.mardPct,
                bias = it.bias
            )
        },
        dailyReportReplayFactorPairs = dailyReportReplayPairs.map {
            DailyReportReplayPairUi(
                horizonMinutes = it.horizonMinutes,
                factorA = it.factorA,
                factorB = it.factorB,
                bucketA = it.bucketA,
                bucketB = it.bucketB,
                sampleCount = it.sampleCount,
                meanFactorA = it.meanFactorA,
                meanFactorB = it.meanFactorB,
                mae = it.mae,
                mardPct = it.mardPct,
                bias = it.bias
            )
        },
        dailyReportReplayTopMisses = dailyReportReplayTopMisses.map {
            DailyReportReplayTopMissUi(
                horizonMinutes = it.horizonMinutes,
                ts = it.ts,
                absError = it.absError,
                pred = it.pred,
                actual = it.actual,
                cob = it.cob,
                iob = it.iob,
                uam = it.uam,
                ciWidth = it.ciWidth,
                diaHours = it.diaHours,
                activity = it.activity,
                sensorQuality = it.sensorQuality
            )
        },
        dailyReportReplayErrorClusters = dailyReportReplayErrorClusters.map {
            DailyReportReplayErrorClusterUi(
                horizonMinutes = it.horizonMinutes,
                hour = it.hour,
                dayType = it.dayType,
                sampleCount = it.sampleCount,
                mae = it.mae,
                mardPct = it.mardPct,
                bias = it.bias,
                meanCob = it.meanCob,
                meanIob = it.meanIob,
                meanUam = it.meanUam,
                meanCiWidth = it.meanCiWidth,
                dominantFactor = it.dominantFactor,
                dominantScore = it.dominantScore
            )
        },
        dailyReportReplayDayTypeGaps = dailyReportReplayDayTypeGaps.map {
            DailyReportReplayDayTypeGapUi(
                horizonMinutes = it.horizonMinutes,
                hour = it.hour,
                worseDayType = it.worseDayType,
                weekdaySampleCount = it.weekdaySampleCount,
                weekendSampleCount = it.weekendSampleCount,
                weekdayMae = it.weekdayMae,
                weekendMae = it.weekendMae,
                weekdayMardPct = it.weekdayMardPct,
                weekendMardPct = it.weekendMardPct,
                maeGapMmol = it.maeGapMmol,
                mardGapPct = it.mardGapPct,
                worseMeanCob = it.worseMeanCob,
                worseMeanIob = it.worseMeanIob,
                worseMeanUam = it.worseMeanUam,
                worseMeanCiWidth = it.worseMeanCiWidth,
                dominantFactor = it.dominantFactor,
                dominantScore = it.dominantScore
            )
        },
        dailyReportReplayTopFactorsOverall = dailyReportReplayTopFactorsOverall,
        dailyReportSensorLagReplayBuckets = dailyReportSensorLagReplayBuckets.map {
            DailyReportSensorLagReplayUi(
                horizonMinutes = it.horizonMinutes,
                bucket = it.bucket,
                sampleCount = it.sampleCount,
                rawMae = it.rawMae,
                lagMae = it.lagMae,
                maeImprovementMmol = it.maeImprovementMmol,
                rawBias = it.rawBias,
                lagBias = it.lagBias
            )
        },
        dailyReportSensorLagShadowBuckets = dailyReportSensorLagShadowBuckets.map {
            DailyReportSensorLagShadowUi(
                bucket = it.bucket,
                sampleCount = it.sampleCount,
                ruleChangedRatePct = it.ruleChangedRatePct,
                meanAbsTargetDeltaMmol = it.meanAbsTargetDeltaMmol
            )
        },
        circadianReplaySummary = circadianReplaySummary,
        probableMealWindows = probableMealWindows.mapNotNull(ProbableEatingWindow::toUi),
        recentProbableMealWindows = recentProbableMealWindows.mapNotNull(ProbableEatingWindow::toUi),
        dailyReportHorizonStats = dailyReportMetrics.map {
            DailyReportHorizonUi(
                horizonMinutes = it.horizonMinutes,
                sampleCount = it.sampleCount,
                mae = it.mae,
                rmse = it.rmse,
                mardPct = it.mardPct,
                bias = it.bias,
                ciCoveragePct = it.ciCoveragePct,
                ciMeanWidth = it.ciMeanWidth
            )
        },
        rollingReportLines = rollingReportLines,
        currentIsfReal = currentIsfReal,
        currentCrReal = currentCrReal,
        currentIsfMerged = currentIsfMerged,
        currentCrMerged = currentCrMerged,
        currentIsfAapsRaw = currentIsfAapsRaw,
        currentCrAapsRaw = currentCrAapsRaw,
        realtimeMode = isfCrRealtimeMode,
        realtimeConfidence = isfCrRealtimeConfidence,
        realtimeQualityScore = isfCrRealtimeQualityScore,
        realtimeIsfEff = isfCrRealtimeIsfEff,
        realtimeCrEff = isfCrRealtimeCrEff,
        realtimeIsfBase = isfCrRealtimeIsfBase,
        realtimeCrBase = isfCrRealtimeCrBase,
        realtimeCiIsfLow = isfCrRealtimeCiIsfLow,
        realtimeCiIsfHigh = isfCrRealtimeCiIsfHigh,
        realtimeCiCrLow = isfCrRealtimeCiCrLow,
        realtimeCiCrHigh = isfCrRealtimeCiCrHigh,
        realtimeFactorLines = isfCrRealtimeFactors,
        runtimeDiagnostics = if (
            isfCrRuntimeDiagTs == null &&
            isfCrRuntimeDiagLowConfidenceTs == null &&
            isfCrRuntimeDiagFallbackTs == null
        ) {
            null
        } else {
            IsfCrRuntimeDiagnosticsUi(
                ts = isfCrRuntimeDiagTs,
                mode = isfCrRuntimeDiagMode,
                confidence = isfCrRuntimeDiagConfidence,
                confidenceThreshold = isfCrRuntimeDiagConfidenceThreshold,
                qualityScore = isfCrRuntimeDiagQualityScore,
                usedEvidence = isfCrRuntimeDiagUsedEvidence,
                droppedEvidence = isfCrRuntimeDiagDroppedEvidence,
                droppedReasons = isfCrRuntimeDiagDroppedReasons,
                droppedReasonCodes = parseReasonCodes(isfCrRuntimeDiagDroppedReasons),
                currentDayType = isfCrRuntimeDiagCurrentDayType,
                isfBaseSource = isfCrRuntimeDiagIsfBaseSource,
                crBaseSource = isfCrRuntimeDiagCrBaseSource,
                isfDayTypeBaseAvailable = isfCrRuntimeDiagIsfDayTypeBaseAvailable,
                crDayTypeBaseAvailable = isfCrRuntimeDiagCrDayTypeBaseAvailable,
                hourWindowIsfEvidence = isfCrRuntimeDiagHourWindowIsfEvidence,
                hourWindowCrEvidence = isfCrRuntimeDiagHourWindowCrEvidence,
                hourWindowIsfSameDayType = isfCrRuntimeDiagHourWindowIsfSameDayType,
                hourWindowCrSameDayType = isfCrRuntimeDiagHourWindowCrSameDayType,
                minIsfEvidencePerHour = isfCrRuntimeDiagMinIsfEvidencePerHour,
                minCrEvidencePerHour = isfCrRuntimeDiagMinCrEvidencePerHour,
                crMaxGapMinutes = isfCrRuntimeDiagCrMaxGapMinutes,
                crMaxSensorBlockedRatePct = isfCrRuntimeDiagCrMaxSensorBlockedRatePct,
                crMaxUamAmbiguityRatePct = isfCrRuntimeDiagCrMaxUamAmbiguityRatePct,
                coverageHoursIsf = isfCrRuntimeDiagCoverageHoursIsf,
                coverageHoursCr = isfCrRuntimeDiagCoverageHoursCr,
                reasons = isfCrRuntimeDiagReasons,
                reasonCodes = parseReasonCodes(isfCrRuntimeDiagReasons),
                lowConfidenceTs = isfCrRuntimeDiagLowConfidenceTs,
                lowConfidenceReasons = isfCrRuntimeDiagLowConfidenceReasons,
                lowConfidenceReasonCodes = parseReasonCodes(isfCrRuntimeDiagLowConfidenceReasons),
                fallbackTs = isfCrRuntimeDiagFallbackTs,
                fallbackReasons = isfCrRuntimeDiagFallbackReasons,
                fallbackReasonCodes = parseReasonCodes(isfCrRuntimeDiagFallbackReasons)
            )
        },
        activationGateLines = isfCrActivationGateLines,
        droppedReasons24hLines = isfCrDroppedReasons24hLines,
        droppedReasons7dLines = isfCrDroppedReasons7dLines,
        wearImpact24hLines = isfCrWearImpact24hLines,
        wearImpact7dLines = isfCrWearImpact7dLines,
        activeTagLines = isfCrActiveTags,
        historyPoints = isfCrHistoryPoints,
        historyOverlayPoints = isfCrHistoryOverlayPoints,
        historyLastUpdatedTs = isfCrHistoryLastUpdatedTs,
        circadianSections = circadianPatternSections,
        deepLines = isfCrDeepLines,
        selectedInsulinProfileId = selectedProfile.name,
        insulinProfileCurves = insulinCurves,
        insulinRealProfileCurvePoints = if (hasRealInsulinCurve) realInsulinCurve else emptyList(),
        insulinRealProfileAvailable = hasRealInsulinCurve,
        insulinRealProfileUpdatedTs = insulinRealProfileUpdatedTs,
        insulinRealProfileConfidence = insulinRealProfileConfidence,
        insulinRealProfileSamples = insulinRealProfileSamples,
        insulinRealProfileOnsetMinutes = insulinRealProfileOnsetMinutes,
        insulinRealProfilePeakMinutes = insulinRealProfilePeakMinutes,
        insulinRealProfileScale = insulinRealProfileScale,
        insulinRealProfileStatus = insulinRealProfileStatus
    )
}

private fun MainUiState.buildCircadianStateStatus(): CircadianStateStatusUi? {
    val slotCount = circadianSlotStatCount
    val transitionCount = circadianTransitionStatCount
    val snapshotCount = circadianSnapshotCount
    val replayCount = circadianReplayStatCount
    val latestSnapshotTs = circadianLatestSnapshotUpdatedTs
    val latestReplayTs = circadianLatestReplayUpdatedTs
    val sectionCount = circadianPatternSections.size
    val nowTs = System.currentTimeMillis()
    val staleCutoffMs = 12 * 60 * 60_000L
    val state = when {
        slotCount == 0 && snapshotCount == 0 && replayCount == 0 -> "EMPTY"
        slotCount == 0 || snapshotCount == 0 || replayCount == 0 -> "PARTIAL"
        latestSnapshotTs != null && nowTs - latestSnapshotTs > staleCutoffMs -> "STALE"
        latestReplayTs != null && nowTs - latestReplayTs > staleCutoffMs -> "STALE"
        sectionCount == 0 -> "PARTIAL"
        else -> "READY"
    }
    val reason = when {
        slotCount == 0 && snapshotCount == 0 && replayCount == 0 -> "no circadian rows yet"
        slotCount == 0 -> "slot stats missing"
        transitionCount == 0 -> "transition stats missing"
        snapshotCount == 0 -> "pattern snapshots missing"
        replayCount == 0 -> "replay stats missing"
        latestSnapshotTs != null && nowTs - latestSnapshotTs > staleCutoffMs -> "pattern snapshots older than 12h"
        latestReplayTs != null && nowTs - latestReplayTs > staleCutoffMs -> "replay stats older than 12h"
        sectionCount == 0 -> "ui sections not resolved"
        else -> "circadian pattern state ready"
    }
    val sourceSummary = circadianPatternSections
        .joinToString(separator = " · ") {
            "${it.requestedDayType.lowercase(Locale.US)}→${it.segmentSource.lowercase(Locale.US)}:${it.stableWindowDays}d"
        }
        .ifBlank { null }
    return CircadianStateStatusUi(
        state = state,
        reason = reason,
        slotCount = slotCount,
        transitionCount = transitionCount,
        snapshotCount = snapshotCount,
        replayCount = replayCount,
        sectionCount = sectionCount,
        latestSnapshotTs = latestSnapshotTs,
        latestReplayTs = latestReplayTs,
        sourceSummary = sourceSummary
    )
}

internal fun MainUiState.toAiAnalysisUiState(
    circadianReplaySummary: CircadianReplaySummaryUi? = null,
    chatMessages: List<AiChatMessageUi> = emptyList(),
    chatInProgress: Boolean = false,
    chatDraft: String = "",
    chatPendingAttachments: List<AiChatAttachmentUi> = emptyList(),
    chatVoiceRepliesEnabled: Boolean = false,
    chatRecording: Boolean = false,
    chatVoiceBusy: Boolean = false,
    chatSpeaking: Boolean = false
): AiAnalysisUiState {
    val syncWarning = inferErrorText()
    val blockingError = syncWarning?.takeIf {
        val normalized = it.uppercase(Locale.US)
        normalized.startsWith("LAST SYNC ISSUE: ERROR") ||
            normalized.startsWith("LAST SYNC ISSUE: FATAL")
    }
    val horizonScores = dailyReportMetrics
        .sortedBy { it.horizonMinutes }
        .map { metric ->
            AiHorizonScoreUi(
                horizonMinutes = metric.horizonMinutes,
                sampleCount = metric.sampleCount,
                mae = metric.mae,
                mardPct = metric.mardPct,
                scoreBand = classifyMardBand(metric.mardPct)
            )
        }
    val topFactors = dailyReportReplayFactors
        .sortedByDescending { it.contributionScore }
        .take(12)
        .map { factor ->
            AiTopFactorUi(
                horizonMinutes = factor.horizonMinutes,
                factor = factor.factor,
                contributionScore = factor.contributionScore,
                upliftPct = factor.upliftPct,
                sampleCount = factor.sampleCount
            )
        }
    val topHotspots = dailyReportReplayHotspots
        .sortedByDescending { it.mae }
        .take(12)
        .map { hotspot ->
            AiHotspotUi(
                horizonMinutes = hotspot.horizonMinutes,
                hour = hotspot.hour,
                sampleCount = hotspot.sampleCount,
                mae = hotspot.mae,
                mardPct = hotspot.mardPct,
                bias = hotspot.bias
            )
        }
    val topMisses = dailyReportReplayTopMisses
        .sortedByDescending { it.absError }
        .take(12)
        .map { miss ->
            AiTopMissUi(
                horizonMinutes = miss.horizonMinutes,
                ts = miss.ts,
                absError = miss.absError,
                pred = miss.pred,
                actual = miss.actual,
                cob = miss.cob,
                iob = miss.iob,
                uam = miss.uam,
                ciWidth = miss.ciWidth,
                activity = miss.activity
            )
        }
    val dayTypeGaps = dailyReportReplayDayTypeGaps
        .sortedByDescending { kotlin.math.abs(it.maeGapMmol) }
        .take(8)
        .map { gap ->
            AiDayTypeGapUi(
                horizonMinutes = gap.horizonMinutes,
                hour = gap.hour,
                worseDayType = gap.worseDayType,
                maeGapMmol = gap.maeGapMmol,
                mardGapPct = gap.mardGapPct,
                dominantFactor = gap.dominantFactor,
                sampleCount = gap.weekdaySampleCount + gap.weekendSampleCount
            )
        }
    val replayState = cloudReplay?.let { replay ->
        AiReplayUi(
            days = replay.days,
            points = replay.points,
            stepMinutes = replay.stepMinutes,
            forecastStats = replay.forecastStats.map { stat ->
                AiReplayForecastStatUi(
                    horizonMinutes = stat.horizon,
                    sampleCount = stat.sampleCount,
                    mae = stat.mae,
                    rmse = stat.rmse,
                    mardPct = stat.mardPct
                )
            },
            ruleStats = replay.ruleStats.map { stat ->
                AiReplayRuleStatUi(
                    ruleId = stat.ruleId,
                    triggered = stat.triggered,
                    blocked = stat.blocked,
                    noMatch = stat.noMatch
                )
            },
            dayTypeStats = replay.dayTypeStats.map { stat ->
                AiReplayDayTypeStatUi(
                    dayType = stat.dayType,
                    metrics = stat.forecastStats.map { forecast ->
                        AiReplayForecastStatUi(
                            horizonMinutes = forecast.horizon,
                            sampleCount = forecast.sampleCount,
                            mae = forecast.mae,
                            rmse = forecast.rmse,
                            mardPct = forecast.mardPct
                        )
                    }
                )
            },
            hourlyTop = replay.hourlyStats
                .sortedByDescending { it.mae }
                .take(8)
                .map { stat ->
                    AiReplayHourStatUi(
                        hour = stat.hour,
                        sampleCount = stat.sampleCount,
                        mae = stat.mae,
                        mardPct = stat.mardPct
                    )
                },
            driftStats = replay.driftStats.map { stat ->
                AiReplayDriftStatUi(
                    horizonMinutes = stat.horizon,
                    previousMae = stat.previousMae,
                    recentMae = stat.recentMae,
                    deltaMae = stat.deltaMae
                )
            }
        )
    }

    val hasData = analysisHistoryItems.isNotEmpty() ||
        analysisTrendItems.isNotEmpty() ||
        cloudJobRows.isNotEmpty() ||
        aiAnalysisReady ||
        replayState != null ||
        dailyReportGeneratedAtTs != null ||
        dailyReportMetrics.isNotEmpty() ||
        horizonScores.isNotEmpty() ||
        topFactors.isNotEmpty() ||
        topHotspots.isNotEmpty() ||
        topMisses.isNotEmpty() ||
        dayTypeGaps.isNotEmpty() ||
        dailyReportRecommendations.isNotEmpty() ||
        rollingReportLines.isNotEmpty()

    return AiAnalysisUiState(
        loadState = resolveLoadState(hasData = hasData, errorText = blockingError),
        isStale = isDataStale(),
        errorText = blockingError,
        minDataHours = aiMinDataHours,
        dataCoverageHours = aiDataCoverageHours,
        analysisReady = aiAnalysisReady,
        cloudConfigured = isCopilotCloudBackendEndpoint(cloudUrl),
        windowDays = insightsWindowDays,
        filterLabel = insightsFilterLabel,
        jobs = cloudJobRows.map { row ->
            AiCloudJobUi(
                jobId = row.jobId,
                lastStatus = row.lastStatus,
                lastRunTs = row.lastRunTs,
                nextRunTs = row.nextRunTs,
                lastMessage = row.lastMessage
            )
        },
        historyItems = analysisHistoryItems
            .sortedByDescending { it.runTs }
            .map { row ->
                AiAnalysisHistoryItemUi(
                    runTs = row.runTs,
                    date = row.date,
                    source = row.source,
                    status = row.status,
                    summary = row.summary,
                    anomalies = row.anomalies,
                    recommendations = row.recommendations,
                    errorMessage = row.errorMessage
                )
            },
        trendItems = analysisTrendItems
            .sortedByDescending { it.weekStart }
            .map { row ->
                AiAnalysisTrendItemUi(
                    weekStart = row.weekStart,
                    totalRuns = row.totalRuns,
                    successRuns = row.successRuns,
                    failedRuns = row.failedRuns,
                    anomaliesCount = row.anomaliesCount,
                    recommendationsCount = row.recommendationsCount
                )
            },
        replay = replayState,
        localDailyGeneratedAtTs = dailyReportGeneratedAtTs,
        localDailyPeriodStartUtc = dailyReportPeriodStartUtc,
        localDailyPeriodEndUtc = dailyReportPeriodEndUtc,
        localDailyMetrics = dailyReportMetrics
            .sortedBy { it.horizonMinutes }
            .map { metric ->
                DailyReportHorizonUi(
                    horizonMinutes = metric.horizonMinutes,
                    sampleCount = metric.sampleCount,
                    mae = metric.mae,
                    rmse = metric.rmse,
                    mardPct = metric.mardPct,
                    bias = metric.bias,
                    ciCoveragePct = metric.ciCoveragePct,
                    ciMeanWidth = metric.ciMeanWidth
                )
            },
        localHorizonScores = horizonScores,
        localTopFactorsOverall = dailyReportReplayTopFactorsOverall,
        localTopFactors = topFactors,
        localHotspots = topHotspots,
        localTopMisses = topMisses,
        localDayTypeGaps = dayTypeGaps,
        circadianReplaySummary = circadianReplaySummary,
        localRecommendations = dailyReportRecommendations,
        rollingLines = rollingReportLines,
        aiTuningStatus = toAiTuningStatusUi(),
        chatMessages = chatMessages,
        chatInProgress = chatInProgress,
        chatDraft = chatDraft,
        chatPendingAttachments = chatPendingAttachments,
        chatVoiceRepliesEnabled = chatVoiceRepliesEnabled,
        chatRecording = chatRecording,
        chatVoiceBusy = chatVoiceBusy,
        chatSpeaking = chatSpeaking
    )
}

internal fun mapClinicalReportState(
    state: ClinicalReportState,
    retryConfirmationRequested: Boolean,
    retryFeedback: ClinicalReportRetryFeedbackUi? = null,
    disclosure: ClinicalReportDisclosureUi? = null
): ClinicalReportUiState {
    val local = when (state) {
        ClinicalReportState.Idle,
        is ClinicalReportState.Building -> null

        is ClinicalReportState.LocalReady -> state.local
        is ClinicalReportState.Uploading -> state.local
        is ClinicalReportState.Complete -> state.local
        is ClinicalReportState.Failed -> state.local
        is ClinicalReportState.Cancelled -> state.local
    }
    val failureReason = (state as? ClinicalReportState.Failed)?.reason
    val unknownOutcome = failureReason == ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME
    val phase = when (state) {
        ClinicalReportState.Idle -> ClinicalReportPhaseUi.IDLE
        is ClinicalReportState.Building -> ClinicalReportPhaseUi.BUILDING
        is ClinicalReportState.LocalReady -> ClinicalReportPhaseUi.LOCAL_READY
        is ClinicalReportState.Uploading -> ClinicalReportPhaseUi.UPLOADING
        is ClinicalReportState.Complete -> ClinicalReportPhaseUi.COMPLETE
        is ClinicalReportState.Failed -> if (unknownOutcome) {
            ClinicalReportPhaseUi.UNKNOWN_OUTCOME
        } else {
            ClinicalReportPhaseUi.FAILED
        }
        is ClinicalReportState.Cancelled -> ClinicalReportPhaseUi.CANCELLED
    }
    val upload = state as? ClinicalReportState.Uploading
    val completedChunks = upload?.completed?.coerceAtLeast(0) ?: 0
    val totalChunks = upload?.total?.coerceAtLeast(0) ?: 0
    val progress = if (totalChunks > 0) {
        (completedChunks.toFloat() / totalChunks.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val canSend = local != null && (
        state is ClinicalReportState.LocalReady ||
            state is ClinicalReportState.Cancelled ||
            (state is ClinicalReportState.Failed && !unknownOutcome)
        )
    return ClinicalReportUiState(
        phase = phase,
        summary24h = local?.summary24h?.toUi(),
        summary7d = local?.summary7d?.toUi(),
        summary30d = local?.summary30d?.toUi(),
        failure = when (state) {
            is ClinicalReportState.Failed -> state.reason.toUi()
            is ClinicalReportState.Cancelled -> ClinicalReportFailureUi.CANCELLED
            else -> null
        },
        completedChunks = completedChunks,
        totalChunks = totalChunks,
        progress = progress,
        progressStage = upload?.stage?.toUi()
            ?: ClinicalReportProgressStageUi.PREPARING,
        progressLevel = upload?.level?.coerceAtLeast(0) ?: 0,
        complete = (state as? ClinicalReportState.Complete)?.toUi(),
        canSend = canSend,
        canCancel = state is ClinicalReportState.Uploading,
        canRequestGuardedRetry = unknownOutcome && local != null,
        canRetryLocalPreparation = local == null && (
            state is ClinicalReportState.Failed ||
                state is ClinicalReportState.Cancelled
            ),
        showRetryConfirmation =
            unknownOutcome && local != null && retryConfirmationRequested,
        retryFeedback = retryFeedback.takeIf {
            unknownOutcome && local != null && retryConfirmationRequested
        },
        pointsToSettings = failureReason == ClinicalReportFailureReason.CREDENTIAL_UNAVAILABLE,
        disclosure = disclosure.takeIf { canSend },
        remoteEventPreviewText = local?.remoteEventPreviewJson
    )
}

internal fun ClinicalAiProviderConfig.toClinicalReportDisclosureUi(): ClinicalReportDisclosureUi =
    ClinicalReportDisclosureUi(
        providerId = providerId,
        model = modelId,
        endpointHost = if (providerId == ClinicalAiProviderId.OPENAI_COMPATIBLE) {
            ClinicalAiEndpointPolicy.requireValid(requireNotNull(endpoint)).host
        } else {
            null
        },
        configIdentity = executionIdentity()
    )

internal fun AiAnalysisUiState.withClinicalReport(
    clinicalReport: ClinicalReportUiState
): AiAnalysisUiState = copy(
    loadState = if (clinicalReport.phase != ClinicalReportPhaseUi.IDLE) {
        ScreenLoadState.READY
    } else {
        loadState
    },
    clinicalReport = clinicalReport
)

private fun ClinicalPeriodSummary.toUi(): ClinicalPeriodSummaryUi {
    val validCoverage = coveragePct.validIn(0.0, 100.0)
    val glucoseCoverageSupported =
        validCoverage != null &&
            validCoverage >= CLINICAL_SUMMARY_MIN_COVERAGE_PCT &&
            quality.coveredBuckets > 0
    val validMaxGap = quality.maxGapMinutes
        ?.takeIf { it in 0..CLINICAL_MAX_GAP_MINUTES }
    val localQuality = when {
        validCoverage == null ||
            validCoverage < CLINICAL_SUMMARY_INSUFFICIENT_COVERAGE_PCT ||
            quality.coveredBuckets <= 0 -> ClinicalLocalDataQualityUi.INSUFFICIENT
        validCoverage < CLINICAL_SUMMARY_GOOD_COVERAGE_PCT ||
            validMaxGap == null ||
            validMaxGap > 30 -> ClinicalLocalDataQualityUi.LIMITED
        else -> ClinicalLocalDataQualityUi.GOOD
    }
    return ClinicalPeriodSummaryUi(
        days = days,
        coveragePct = validCoverage,
        coverageProgress = ((validCoverage ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f),
        meanGlucose = meanMmol
            .takeIf { glucoseCoverageSupported }
            .validIn(1.0, 40.0),
        medianGlucose = medianMmol
            .takeIf { glucoseCoverageSupported }
            .validIn(1.0, 40.0),
        variability = coefficientOfVariationPct
            .takeIf { glucoseCoverageSupported }
            .validIn(0.0, 200.0),
        belowRange = timeBelow4Pct
            .takeIf { glucoseCoverageSupported }
            .validIn(0.0, 100.0),
        inRange = timeInRangePct
            .takeIf { glucoseCoverageSupported }
            .validIn(0.0, 100.0),
        aboveRange = timeAboveRangePct
            .takeIf { glucoseCoverageSupported }
            .validIn(0.0, 100.0),
        recordedInsulin = totalInsulinU
            .validIn(0.0, 10_000.0),
        realCarbs = totalCarbsG
            .validIn(0.0, 100_000.0),
        maxGapMinutes = validMaxGap,
        quality = localQuality,
        deliveredBasalInsulin = deliveredBasalInsulinU.validIn(0.0, 10_000.0),
        deliveredBolusInsulin = deliveredBolusInsulinU.validIn(0.0, 10_000.0),
        iobDerivedInsulin = estimatedInsulinU.validIn(0.0, 10_000.0),
        enteredCarbs = enteredCarbsG.validIn(0.0, 100_000.0),
        uamCarbs = uamCarbsG.validIn(0.0, 100_000.0),
        aapsCarbs = aapsCarbsG.validIn(0.0, 100_000.0),
        steps = activity.steps.validIn(0.0, 10_000_000.0),
        activeMinutes = activity.activeMinutes.validIn(0.0, 100_000.0),
        carbohydrateEnergyKcal = carbohydrateEnergyKcal.validIn(0.0, 400_000.0),
        activeCaloriesKcal = activity.activeCaloriesKcal.validIn(0.0, 400_000.0),
        activityCoveragePct = activity.coveragePct.validIn(0.0, 100.0),
        therapyTotalsAuthoritative =
            insulinTotalSource == ClinicalInsulinTotalSource.AAPS_RAW_HISTORY &&
                aapsCarbsG != null,
        probableMealWindows = probableMealWindows.mapNotNull(ProbableEatingWindow::toUi),
        recentProbableMealWindows = recentProbableMealWindows.mapNotNull(ProbableEatingWindow::toUi)
    )
}

private fun ProbableEatingWindow.toUi(): ProbableMealWindowUi? {
    if (
        medianMinuteOfDay !in 0 until 24 * 60 ||
        startMinuteOfDay !in 0 until 24 * 60 ||
        endMinuteOfDay !in 0 until 24 * 60 ||
        iqrMinutes !in 0..24 * 60 ||
        supportDays !in 1..lookbackDays ||
        lookbackDays !in 1..30 ||
        episodeCount < supportDays ||
        enteredEpisodeCount !in 0..episodeCount ||
        uamEpisodeCount !in 0..episodeCount ||
        !confidencePct.isFinite() || confidencePct !in 0.0..100.0
    ) return null
    return ProbableMealWindowUi(
        medianMinuteOfDay = medianMinuteOfDay,
        startMinuteOfDay = startMinuteOfDay,
        endMinuteOfDay = endMinuteOfDay,
        iqrMinutes = iqrMinutes,
        supportDays = supportDays,
        lookbackDays = lookbackDays,
        episodeCount = episodeCount,
        enteredEpisodeCount = enteredEpisodeCount,
        uamEpisodeCount = uamEpisodeCount,
        confidencePct = confidencePct
    )
}

private fun ClinicalReportState.Complete.toUi(): ClinicalCompleteReportUi {
    val indexedPatterns = report.patterns.mapIndexedNotNull { index, finding ->
        finding.toUi()?.let { index to it }
    }
    val patternsBySourceIndex = indexedPatterns.toMap()
    return ClinicalCompleteReportUi(
        summary7dStatus = report.summary7dStatus.toTextKey(),
        summary30dStatus = report.summary30dStatus.toTextKey(),
        dataQuality = report.dataQuality.map(ClinicalDataQualityFlag::toTextKey),
        patterns = indexedPatterns.map(Pair<Int, ClinicalFindingUi>::second),
        safetyObservations = report.safetyObservations.map(ClinicalSafetyObservation::toTextKey),
        recommendations = report.recommendations.mapNotNull { recommendation ->
            recommendation.toUi(patternsBySourceIndex)
        },
        careTeamQuestions = report.careTeamQuestions.map(ClinicalCareTeamQuestion::toTextKey),
        metadata = ClinicalReportMetadataUi(
            generatedAtTs = local.generatedAt.takeIf {
                it in 1L..CLINICAL_MAX_TIMESTAMP_MS
            },
            providerId = metadata.providerId,
            model = metadata.model.safeClinicalMetadata(),
            schemaName = metadata.schemaName.safeClinicalMetadata(),
            schemaVersion = metadata.schemaVersion.takeIf { it > 0 }
        )
    )
}

private fun ClinicalFinding.toUi(): ClinicalFindingUi? {
    val topicUi = mapClinicalPatternTopic(topic.name) ?: return null
    val evidenceUi = evidenceMetric.toUi(evidenceValue) ?: return null
    return ClinicalFindingUi(
        topic = topicUi,
        period = period.toTextKey(),
        direction = direction.toTextKey(),
        confidence = confidence.toTextKey(),
        timeBand = timeBand.toTextKey(),
        evidence = evidenceUi
    )
}

private fun ClinicalRecommendation.toUi(
    patternsBySourceIndex: Map<Int, ClinicalFindingUi>
): ClinicalRecommendationUi? {
    if (evidenceFindingIndices.isEmpty()) return null
    val linkedEvidence = evidenceFindingIndices.map { index ->
        patternsBySourceIndex[index] ?: return null
    }
    return ClinicalRecommendationUi(
        topic = careTeamDiscussionTopic.toTextKey(),
        priority = priority.toTextKey(),
        period = period.toTextKey(),
        linkedEvidence = linkedEvidence
    )
}

private fun ClinicalEvidenceMetric.toUi(value: Double): ClinicalEvidenceUi? {
    val numeric = when (this) {
        ClinicalEvidenceMetric.MEAN_GLUCOSE,
        ClinicalEvidenceMetric.MEDIAN_GLUCOSE,
        ClinicalEvidenceMetric.MEAN_TARGET_MMOL ->
            value.validIn(1.0, 40.0)
        ClinicalEvidenceMetric.COEFFICIENT_OF_VARIATION_PCT ->
            value.validIn(0.0, 200.0)
        ClinicalEvidenceMetric.TIME_BELOW_RANGE_PCT,
        ClinicalEvidenceMetric.TIME_IN_RANGE_PCT,
        ClinicalEvidenceMetric.TIME_ABOVE_RANGE_PCT,
        ClinicalEvidenceMetric.COVERAGE_PCT ->
            value.validIn(0.0, 100.0)
        ClinicalEvidenceMetric.MAX_GAP_MINUTES,
        ClinicalEvidenceMetric.DURATION_MINUTES ->
            value.validWholeNumber(0, CLINICAL_MAX_GAP_MINUTES)?.toDouble()
        ClinicalEvidenceMetric.TOTAL_INSULIN_UNITS_RECORDED ->
            value.validIn(0.0, 10_000.0)
        ClinicalEvidenceMetric.TOTAL_CARBS_GRAMS_RECORDED ->
            value.validIn(0.0, 100_000.0)
        ClinicalEvidenceMetric.SAMPLE_COUNT ->
            value.validWholeNumber(0, 8_641)?.toDouble()
    } ?: return null
    val unit = when (this) {
        ClinicalEvidenceMetric.MEAN_GLUCOSE,
        ClinicalEvidenceMetric.MEDIAN_GLUCOSE,
        ClinicalEvidenceMetric.MEAN_TARGET_MMOL -> ClinicalNumericUnitUi.MMOL_L
        ClinicalEvidenceMetric.COEFFICIENT_OF_VARIATION_PCT,
        ClinicalEvidenceMetric.TIME_BELOW_RANGE_PCT,
        ClinicalEvidenceMetric.TIME_IN_RANGE_PCT,
        ClinicalEvidenceMetric.TIME_ABOVE_RANGE_PCT,
        ClinicalEvidenceMetric.COVERAGE_PCT -> ClinicalNumericUnitUi.PERCENT
        ClinicalEvidenceMetric.MAX_GAP_MINUTES,
        ClinicalEvidenceMetric.DURATION_MINUTES -> ClinicalNumericUnitUi.MINUTES
        ClinicalEvidenceMetric.TOTAL_INSULIN_UNITS_RECORDED ->
            ClinicalNumericUnitUi.INSULIN_UNITS
        ClinicalEvidenceMetric.TOTAL_CARBS_GRAMS_RECORDED -> ClinicalNumericUnitUi.GRAMS
        ClinicalEvidenceMetric.SAMPLE_COUNT -> ClinicalNumericUnitUi.COUNT
    }
    val decimals = when (unit) {
        ClinicalNumericUnitUi.MINUTES,
        ClinicalNumericUnitUi.COUNT -> 0
        else -> 1
    }
    return ClinicalEvidenceUi(
        metric = toTextKey(),
        value = numeric,
        decimals = decimals,
        unit = unit
    )
}

internal fun mapClinicalPatternTopic(raw: String): ClinicalReportTextKey? = when (raw) {
    ClinicalPatternTopic.GLUCOSE_STABILITY.name ->
        ClinicalReportTextKey.OBSERVATION_GLUCOSE_STABILITY
    ClinicalPatternTopic.GLUCOSE_VARIABILITY.name ->
        ClinicalReportTextKey.OBSERVATION_GLUCOSE_VARIABILITY
    ClinicalPatternTopic.LOW_EXPOSURE.name ->
        ClinicalReportTextKey.OBSERVATION_LOW_EXPOSURE
    ClinicalPatternTopic.HIGH_EXPOSURE.name ->
        ClinicalReportTextKey.OBSERVATION_HIGH_EXPOSURE
    ClinicalPatternTopic.MEAL_ASSOCIATION.name ->
        ClinicalReportTextKey.OBSERVATION_MEAL_ASSOCIATION
    ClinicalPatternTopic.OVERNIGHT_PATTERN.name ->
        ClinicalReportTextKey.OBSERVATION_OVERNIGHT_PATTERN
    ClinicalPatternTopic.TARGET_ALIGNMENT.name ->
        ClinicalReportTextKey.OBSERVATION_TARGET_ALIGNMENT
    ClinicalPatternTopic.SENSOR_RELIABILITY.name ->
        ClinicalReportTextKey.OBSERVATION_SENSOR_RELIABILITY
    ClinicalPatternTopic.INFUSION_SET_SIGNAL.name ->
        ClinicalReportTextKey.OBSERVATION_INFUSION_SET_SIGNAL
    ClinicalPatternTopic.DATA_COVERAGE.name ->
        ClinicalReportTextKey.OBSERVATION_DATA_COVERAGE
    else -> null
}

internal fun mapClinicalFailureReason(raw: String): ClinicalReportFailureUi = when (raw) {
    ClinicalReportFailureReason.LOCAL_BUILD.name -> ClinicalReportFailureUi.LOCAL_BUILD
    ClinicalReportFailureReason.CREDENTIAL_UNAVAILABLE.name ->
        ClinicalReportFailureUi.CREDENTIAL_UNAVAILABLE
    ClinicalReportFailureReason.UNAUTHORIZED.name -> ClinicalReportFailureUi.UNAUTHORIZED
    ClinicalReportFailureReason.RATE_LIMITED.name -> ClinicalReportFailureUi.RATE_LIMITED
    ClinicalReportFailureReason.SERVER.name -> ClinicalReportFailureUi.SERVER
    ClinicalReportFailureReason.HTTP.name -> ClinicalReportFailureUi.HTTP
    ClinicalReportFailureReason.TIMEOUT.name -> ClinicalReportFailureUi.TIMEOUT
    ClinicalReportFailureReason.NETWORK.name -> ClinicalReportFailureUi.NETWORK
    ClinicalReportFailureReason.REFUSAL.name -> ClinicalReportFailureUi.REFUSAL
    ClinicalReportFailureReason.INCOMPLETE.name -> ClinicalReportFailureUi.INCOMPLETE
    ClinicalReportFailureReason.INVALID_RESPONSE.name -> ClinicalReportFailureUi.INVALID_RESPONSE
    ClinicalReportFailureReason.OVERSIZED_RESPONSE.name ->
        ClinicalReportFailureUi.OVERSIZED_RESPONSE
    ClinicalReportFailureReason.REQUEST_TOO_LARGE.name ->
        ClinicalReportFailureUi.REQUEST_TOO_LARGE
    ClinicalReportFailureReason.DATASET_TOO_LARGE.name ->
        ClinicalReportFailureUi.DATASET_TOO_LARGE
    ClinicalReportFailureReason.INVALID_INPUT.name -> ClinicalReportFailureUi.INVALID_INPUT
    ClinicalReportFailureReason.PARTIAL_CHUNK.name -> ClinicalReportFailureUi.PARTIAL_CHUNK
    ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME.name ->
        ClinicalReportFailureUi.UNKNOWN_REMOTE_OUTCOME
    ClinicalReportFailureReason.PROCESS_INTERRUPTED_PRE_REQUEST.name ->
        ClinicalReportFailureUi.PROCESS_INTERRUPTED_PRE_REQUEST
    ClinicalReportFailureReason.CANCELLED.name -> ClinicalReportFailureUi.CANCELLED
    else -> ClinicalReportFailureUi.OTHER
}

private fun ClinicalReportFailureReason.toUi() = mapClinicalFailureReason(name)

private fun ClinicalOpenAiProgressStage.toUi(): ClinicalReportProgressStageUi = when (this) {
    ClinicalOpenAiProgressStage.PREPARING -> ClinicalReportProgressStageUi.PREPARING
    ClinicalOpenAiProgressStage.ANALYZING_CHUNK -> ClinicalReportProgressStageUi.ANALYZING
    ClinicalOpenAiProgressStage.REDUCING -> ClinicalReportProgressStageUi.REDUCING
    ClinicalOpenAiProgressStage.SYNTHESIZING -> ClinicalReportProgressStageUi.SYNTHESIZING
    ClinicalOpenAiProgressStage.VALIDATING -> ClinicalReportProgressStageUi.VALIDATING
    ClinicalOpenAiProgressStage.COMPLETED -> ClinicalReportProgressStageUi.COMPLETED
}

private fun ClinicalSummaryStatus.toTextKey() = when (this) {
    ClinicalSummaryStatus.STABLE -> ClinicalReportTextKey.SUMMARY_STABLE
    ClinicalSummaryStatus.HIGH_VARIABILITY -> ClinicalReportTextKey.SUMMARY_HIGH_VARIABILITY
    ClinicalSummaryStatus.LOW_EXPOSURE -> ClinicalReportTextKey.SUMMARY_LOW_EXPOSURE
    ClinicalSummaryStatus.HIGH_EXPOSURE -> ClinicalReportTextKey.SUMMARY_HIGH_EXPOSURE
    ClinicalSummaryStatus.MIXED -> ClinicalReportTextKey.SUMMARY_MIXED
    ClinicalSummaryStatus.INSUFFICIENT_DATA ->
        ClinicalReportTextKey.SUMMARY_INSUFFICIENT_DATA
}

private fun ClinicalDataQualityFlag.toTextKey() = when (this) {
    ClinicalDataQualityFlag.COMPLETE -> ClinicalReportTextKey.QUALITY_COMPLETE
    ClinicalDataQualityFlag.PARTIAL_COVERAGE ->
        ClinicalReportTextKey.QUALITY_PARTIAL_COVERAGE
    ClinicalDataQualityFlag.MISSING_INTERVALS ->
        ClinicalReportTextKey.QUALITY_MISSING_INTERVALS
    ClinicalDataQualityFlag.SENSOR_GAPS -> ClinicalReportTextKey.QUALITY_SENSOR_GAPS
    ClinicalDataQualityFlag.THERAPY_GAPS -> ClinicalReportTextKey.QUALITY_THERAPY_GAPS
    ClinicalDataQualityFlag.TARGET_GAPS -> ClinicalReportTextKey.QUALITY_TARGET_GAPS
    ClinicalDataQualityFlag.FORECAST_GAPS -> ClinicalReportTextKey.QUALITY_FORECAST_GAPS
    ClinicalDataQualityFlag.TELEMETRY_GAPS -> ClinicalReportTextKey.QUALITY_TELEMETRY_GAPS
    ClinicalDataQualityFlag.INSUFFICIENT_DATA ->
        ClinicalReportTextKey.QUALITY_INSUFFICIENT_DATA
}

private fun ClinicalPatternDirection.toTextKey() = when (this) {
    ClinicalPatternDirection.STABLE -> ClinicalReportTextKey.DIRECTION_STABLE
    ClinicalPatternDirection.INCREASING -> ClinicalReportTextKey.DIRECTION_INCREASING
    ClinicalPatternDirection.DECREASING -> ClinicalReportTextKey.DIRECTION_DECREASING
    ClinicalPatternDirection.INTERMITTENT -> ClinicalReportTextKey.DIRECTION_INTERMITTENT
    ClinicalPatternDirection.MIXED -> ClinicalReportTextKey.DIRECTION_MIXED
    ClinicalPatternDirection.NOT_APPLICABLE -> ClinicalReportTextKey.DIRECTION_NOT_APPLICABLE
}

private fun ClinicalTimeBand.toTextKey() = when (this) {
    ClinicalTimeBand.ALL_DAY -> ClinicalReportTextKey.TIME_ALL_DAY
    ClinicalTimeBand.OVERNIGHT -> ClinicalReportTextKey.TIME_OVERNIGHT
    ClinicalTimeBand.MORNING -> ClinicalReportTextKey.TIME_MORNING
    ClinicalTimeBand.AFTERNOON -> ClinicalReportTextKey.TIME_AFTERNOON
    ClinicalTimeBand.EVENING -> ClinicalReportTextKey.TIME_EVENING
}

private fun ClinicalFindingConfidence.toTextKey() = when (this) {
    ClinicalFindingConfidence.LOW -> ClinicalReportTextKey.CONFIDENCE_LOW
    ClinicalFindingConfidence.MEDIUM -> ClinicalReportTextKey.CONFIDENCE_MEDIUM
    ClinicalFindingConfidence.HIGH -> ClinicalReportTextKey.CONFIDENCE_HIGH
}

private fun ClinicalEvidenceMetric.toTextKey() = when (this) {
    ClinicalEvidenceMetric.MEAN_GLUCOSE -> ClinicalReportTextKey.EVIDENCE_MEAN_GLUCOSE
    ClinicalEvidenceMetric.MEDIAN_GLUCOSE -> ClinicalReportTextKey.EVIDENCE_MEDIAN_GLUCOSE
    ClinicalEvidenceMetric.MEAN_TARGET_MMOL -> ClinicalReportTextKey.EVIDENCE_MEAN_TARGET
    ClinicalEvidenceMetric.COEFFICIENT_OF_VARIATION_PCT ->
        ClinicalReportTextKey.EVIDENCE_VARIABILITY
    ClinicalEvidenceMetric.TIME_BELOW_RANGE_PCT -> ClinicalReportTextKey.EVIDENCE_BELOW_RANGE
    ClinicalEvidenceMetric.TIME_IN_RANGE_PCT -> ClinicalReportTextKey.EVIDENCE_IN_RANGE
    ClinicalEvidenceMetric.TIME_ABOVE_RANGE_PCT -> ClinicalReportTextKey.EVIDENCE_ABOVE_RANGE
    ClinicalEvidenceMetric.COVERAGE_PCT -> ClinicalReportTextKey.EVIDENCE_COVERAGE
    ClinicalEvidenceMetric.MAX_GAP_MINUTES -> ClinicalReportTextKey.EVIDENCE_MAX_GAP
    ClinicalEvidenceMetric.DURATION_MINUTES -> ClinicalReportTextKey.EVIDENCE_DURATION
    ClinicalEvidenceMetric.TOTAL_INSULIN_UNITS_RECORDED ->
        ClinicalReportTextKey.EVIDENCE_RECORDED_INSULIN
    ClinicalEvidenceMetric.TOTAL_CARBS_GRAMS_RECORDED ->
        ClinicalReportTextKey.EVIDENCE_RECORDED_CARBS
    ClinicalEvidenceMetric.SAMPLE_COUNT -> ClinicalReportTextKey.EVIDENCE_SAMPLE_COUNT
}

private fun ClinicalCareTeamDiscussionTopic.toTextKey() = when (this) {
    ClinicalCareTeamDiscussionTopic.SENSOR_RELIABILITY ->
        ClinicalReportTextKey.DISCUSSION_SENSOR_RELIABILITY
    ClinicalCareTeamDiscussionTopic.INFUSION_SET_REVIEW ->
        ClinicalReportTextKey.DISCUSSION_INFUSION_SET_REVIEW
    ClinicalCareTeamDiscussionTopic.MEAL_TIMING_REVIEW ->
        ClinicalReportTextKey.DISCUSSION_MEAL_TIMING_REVIEW
    ClinicalCareTeamDiscussionTopic.ISF_CR_REVIEW ->
        ClinicalReportTextKey.DISCUSSION_ISF_CR_REVIEW
    ClinicalCareTeamDiscussionTopic.TARGET_PATTERN_REVIEW ->
        ClinicalReportTextKey.DISCUSSION_TARGET_PATTERN_REVIEW
    ClinicalCareTeamDiscussionTopic.DATA_QUALITY_REVIEW ->
        ClinicalReportTextKey.DISCUSSION_DATA_QUALITY_REVIEW
    ClinicalCareTeamDiscussionTopic.LOW_RISK_REVIEW ->
        ClinicalReportTextKey.DISCUSSION_LOW_RISK_REVIEW
    ClinicalCareTeamDiscussionTopic.OTHER_CLINICAL_REVIEW ->
        ClinicalReportTextKey.DISCUSSION_OTHER_CLINICAL_REVIEW
}

private fun ClinicalAdvisoryPriority.toTextKey() = when (this) {
    ClinicalAdvisoryPriority.LOW -> ClinicalReportTextKey.PRIORITY_LOW
    ClinicalAdvisoryPriority.MEDIUM -> ClinicalReportTextKey.PRIORITY_MEDIUM
    ClinicalAdvisoryPriority.HIGH -> ClinicalReportTextKey.PRIORITY_HIGH
}

private fun ClinicalEvidencePeriod.toTextKey() = when (this) {
    ClinicalEvidencePeriod.LAST_24_HOURS -> ClinicalReportTextKey.PERIOD_LAST_24_HOURS
    ClinicalEvidencePeriod.LAST_7_DAYS -> ClinicalReportTextKey.PERIOD_LAST_7_DAYS
    ClinicalEvidencePeriod.LAST_30_DAYS -> ClinicalReportTextKey.PERIOD_LAST_30_DAYS
    ClinicalEvidencePeriod.COMPARATIVE_7D_30D ->
        ClinicalReportTextKey.PERIOD_COMPARATIVE_7D_30D
}

private fun ClinicalSafetyObservation.toTextKey() = when (this) {
    ClinicalSafetyObservation.RECURRENT_LOW_PATTERN ->
        ClinicalReportTextKey.SAFETY_RECURRENT_LOW_PATTERN
    ClinicalSafetyObservation.PROLONGED_LOW_PATTERN ->
        ClinicalReportTextKey.SAFETY_PROLONGED_LOW_PATTERN
    ClinicalSafetyObservation.HIGH_EXPOSURE_PATTERN ->
        ClinicalReportTextKey.SAFETY_HIGH_EXPOSURE_PATTERN
    ClinicalSafetyObservation.HIGH_VARIABILITY_PATTERN ->
        ClinicalReportTextKey.SAFETY_HIGH_VARIABILITY_PATTERN
    ClinicalSafetyObservation.SENSOR_RELIABILITY_CONCERN ->
        ClinicalReportTextKey.SAFETY_SENSOR_RELIABILITY_CONCERN
    ClinicalSafetyObservation.INFUSION_SET_REVIEW_SIGNAL ->
        ClinicalReportTextKey.SAFETY_INFUSION_SET_REVIEW_SIGNAL
    ClinicalSafetyObservation.INSUFFICIENT_DATA ->
        ClinicalReportTextKey.SAFETY_INSUFFICIENT_DATA
    ClinicalSafetyObservation.NONE_IDENTIFIED ->
        ClinicalReportTextKey.SAFETY_NONE_IDENTIFIED
}

private fun ClinicalCareTeamQuestion.toTextKey() = when (this) {
    ClinicalCareTeamQuestion.SENSOR_RELIABILITY_CONTEXT ->
        ClinicalReportTextKey.QUESTION_SENSOR_RELIABILITY_CONTEXT
    ClinicalCareTeamQuestion.INFUSION_SET_CONTEXT ->
        ClinicalReportTextKey.QUESTION_INFUSION_SET_CONTEXT
    ClinicalCareTeamQuestion.MEAL_TIMING_CONTEXT ->
        ClinicalReportTextKey.QUESTION_MEAL_TIMING_CONTEXT
    ClinicalCareTeamQuestion.ISF_CR_CONTEXT -> ClinicalReportTextKey.QUESTION_ISF_CR_CONTEXT
    ClinicalCareTeamQuestion.TARGET_PATTERN_CONTEXT ->
        ClinicalReportTextKey.QUESTION_TARGET_PATTERN_CONTEXT
    ClinicalCareTeamQuestion.LOW_PATTERN_CONTEXT ->
        ClinicalReportTextKey.QUESTION_LOW_PATTERN_CONTEXT
    ClinicalCareTeamQuestion.HIGH_PATTERN_CONTEXT ->
        ClinicalReportTextKey.QUESTION_HIGH_PATTERN_CONTEXT
    ClinicalCareTeamQuestion.DATA_COMPLETENESS_CONTEXT ->
        ClinicalReportTextKey.QUESTION_DATA_COMPLETENESS_CONTEXT
}

private fun Double?.validIn(minimum: Double, maximum: Double): Double? =
    this?.takeIf { it.isFinite() && it in minimum..maximum }

private fun Double.validWholeNumber(minimum: Int, maximum: Int): Int? =
    takeIf { it.isFinite() && it % 1.0 == 0.0 && it in minimum.toDouble()..maximum.toDouble() }
        ?.toInt()

private fun String.safeClinicalMetadata(): String? =
    trim()
        .takeIf { it == this && it.length in 1..100 }
        ?.takeIf { value ->
            value.all { character ->
                character.isLetterOrDigit() ||
                    character == '-' ||
                    character == '_' ||
                    character == '.'
            }
        }

private fun MainUiState.toAiTuningStatusUi(): AiTuningStatusUi? {
    val state = aiTuningState.trim().uppercase(Locale.US).ifBlank { "BLOCKED" }
    val reason = aiTuningReason.trim().ifBlank { "n/a" }
    if (state.isBlank() && aiTuningGeneratedTs == null && aiTuningStatusRaw.isNullOrBlank()) return null
    return AiTuningStatusUi(
        state = state,
        reason = reason,
        generatedTs = aiTuningGeneratedTs,
        confidence = aiTuningConfidence,
        statusRaw = aiTuningStatusRaw
    )
}

internal fun MainUiState.toSettingsUiState(
    verboseLogsEnabled: Boolean,
    proModeEnabled: Boolean,
    baseTargetPresentation: BaseTargetSchedulePresentation? = null
): SettingsUiState {
    val targetPresentation = baseTargetPresentation ?: BaseTargetSchedulePresentation(
        schedule = BaseTargetSchedule.legacy(baseTargetMmol),
        effectiveTargetMmol = baseTargetMmol,
        autoDeltaMmol = 0.0,
        autoState = CircadianAutoState.OFF
    )
    return SettingsUiState(
        loadState = ScreenLoadState.READY,
        isStale = isDataStale(),
        proModeEnabled = proModeEnabled,
        baseTarget = baseTargetMmol,
        baseTargetSchedule = targetPresentation.schedule,
        effectiveBaseTargetMmol = targetPresentation.effectiveTargetMmol,
        baseTargetAutoDeltaMmol = targetPresentation.autoDeltaMmol,
        baseTargetAutoState = targetPresentation.autoState,
        baseTargetAutoReason = targetPresentation.autoReason,
        nightscoutUrl = nightscoutUrl,
        aiApiUrl = cloudUrl,
        uiStyle = uiStyle,
        resolvedNightscoutUrl = resolvedNightscoutUrl,
        insulinProfileId = insulinProfileId,
        localNightscoutEnabled = localNightscoutEnabled,
        localNightscoutLegacyMigrationAcknowledged =
            localNightscoutLegacyMigrationAcknowledged,
        localBroadcastIngestEnabled = localBroadcastIngestEnabled,
        strictBroadcastSenderValidation = strictBroadcastSenderValidation,
        enableUamInference = enableUamInference,
        enableUamBoost = enableUamBoost,
        uamExport = toUamExportControlUi(),
        enableUamAutoExportCap = enableUamAutoExportCap,
        uamAutoExportCapGrams = uamAutoExportCapGrams,
        uamMinSnackG = uamMinSnackG,
        uamMaxSnackG = uamMaxSnackG,
        uamSnackStepG = uamSnackStepG,
        sensorLagCorrectionMode = sensorLagCorrectionMode,
        targetManagerMode = targetManagerMode,
        targetManagerModeManualOverride = targetManagerModeManualOverride,
        targetManagerCopilotPriorityEnabled = targetManagerCopilotPriorityEnabled,
        targetManagerPolicyRevision = targetManagerPolicyRevision,
        circadianPatternsEnabled = circadianPatternsEnabled,
        adaptiveControllerRetargetMinutes = adaptiveControllerRetargetMinutes,
        rulePostHypoCooldownMinutes = rulePostHypoCooldownMinutes,
        rulePatternCooldownMinutes = rulePatternCooldownMinutes,
        ruleSegmentCooldownMinutes = ruleSegmentCooldownMinutes,
        circadianStableLookbackDays = circadianStableLookbackDays,
        circadianRecencyLookbackDays = circadianRecencyLookbackDays,
        circadianUseWeekendSplit = circadianUseWeekendSplit,
        circadianUseReplayResidualBias = circadianUseReplayResidualBias,
        circadianForecastWeight30 = circadianForecastWeight30,
        circadianForecastWeight60 = circadianForecastWeight60,
        softAlertEnabled = softAlertEnabled,
        watch60AlertEnabled = watch60AlertEnabled,
        warning30AlertEnabled = warning30AlertEnabled,
        softHighAlertEnabled = softHighAlertEnabled,
        critical5AlertEnabled = critical5AlertEnabled,
        lowNowAlertEnabled = lowNowAlertEnabled,
        softAlertLowMmol = softAlertLowMmol,
        softAlertHighMmol = softAlertHighMmol,
        urgentLowMmol = urgentLowMmol,
        softAlertClipLabel = softAlertAudioDisplayName ?: GlucoseAlertAudioProfiles.label(GlucoseAlertAudioSlot.SOFT),
        criticalAlertClip1Label = criticalAlertAudio1DisplayName ?: GlucoseAlertAudioProfiles.label(GlucoseAlertAudioSlot.CRITICAL_PRIMARY),
        criticalAlertClip2Label = criticalAlertAudio2DisplayName ?: GlucoseAlertAudioProfiles.label(GlucoseAlertAudioSlot.CRITICAL_SECONDARY),
        softAlertAudioStartSeconds = softAlertAudioStartMs / 1_000,
        softAlertAudioDurationSeconds = softAlertAudioDurationMs / 1_000,
        criticalAlertAudio1StartSeconds = criticalAlertAudio1StartMs / 1_000,
        criticalAlertAudio1DurationSeconds = criticalAlertAudio1DurationMs / 1_000,
        criticalAlertAudio2StartSeconds = criticalAlertAudio2StartMs / 1_000,
        criticalAlertAudio2DurationSeconds = criticalAlertAudio2DurationMs / 1_000,
        softAlertAudioValid = GlucoseAlertAudioProfiles.isValid(
            slot = GlucoseAlertAudioSlot.SOFT,
            startMs = softAlertAudioStartMs,
            durationMs = softAlertAudioDurationMs
        ),
        criticalAlertAudio1Valid = GlucoseAlertAudioProfiles.isValid(
            slot = GlucoseAlertAudioSlot.CRITICAL_PRIMARY,
            startMs = criticalAlertAudio1StartMs,
            durationMs = criticalAlertAudio1DurationMs
        ),
        criticalAlertAudio2Valid = GlucoseAlertAudioProfiles.isValid(
            slot = GlucoseAlertAudioSlot.CRITICAL_SECONDARY,
            startMs = criticalAlertAudio2StartMs,
            durationMs = criticalAlertAudio2DurationMs
        ),
        softAlertAudioPreviewing = false,
        criticalAlertAudio1Previewing = false,
        criticalAlertAudio2Previewing = false,
        isfRuntimeSourcePreference = isfRuntimeSourcePreference,
        crRuntimeSourcePreference = crRuntimeSourcePreference,
        sensitivitySourceApplying = sensitivitySourceApplying,
        sensitivitySourcePendingMetric = sensitivitySourcePendingMetric,
        sensitivitySourcePendingValue = sensitivitySourcePendingValue,
        sensitivitySourceApplyError = sensitivitySourceApplyError,
        isfCrShadowMode = isfCrShadowMode,
        isfCrConfidenceThreshold = isfCrConfidenceThreshold,
        isfCrUseActivity = isfCrUseActivity,
        isfCrUseManualTags = isfCrUseManualTags,
        isfCrMinIsfEvidencePerHour = isfCrMinIsfEvidencePerHour,
        isfCrMinCrEvidencePerHour = isfCrMinCrEvidencePerHour,
        isfCrCrMaxGapMinutes = isfCrCrMaxGapMinutes,
        isfCrCrMaxSensorBlockedRatePct = isfCrCrMaxSensorBlockedRatePct,
        isfCrCrMaxUamAmbiguityRatePct = isfCrCrMaxUamAmbiguityRatePct,
        isfCrSnapshotRetentionDays = isfCrSnapshotRetentionDays,
        isfCrEvidenceRetentionDays = isfCrEvidenceRetentionDays,
        isfCrAutoActivationEnabled = isfCrAutoActivationEnabled,
        isfCrAutoActivationLookbackHours = isfCrAutoActivationLookbackHours,
        isfCrAutoActivationMinSamples = isfCrAutoActivationMinSamples,
        isfCrAutoActivationMinMeanConfidence = isfCrAutoActivationMinMeanConfidence,
        isfCrAutoActivationMaxMeanAbsIsfDeltaPct = isfCrAutoActivationMaxMeanAbsIsfDeltaPct,
        isfCrAutoActivationMaxMeanAbsCrDeltaPct = isfCrAutoActivationMaxMeanAbsCrDeltaPct,
        isfCrAutoActivationMinSensorQualityScore = isfCrAutoActivationMinSensorQualityScore,
        isfCrAutoActivationMinSensorFactor = isfCrAutoActivationMinSensorFactor,
        isfCrAutoActivationMaxWearConfidencePenalty = isfCrAutoActivationMaxWearConfidencePenalty,
        isfCrAutoActivationMaxSensorAgeHighRatePct = isfCrAutoActivationMaxSensorAgeHighRatePct,
        isfCrAutoActivationMaxSuspectFalseLowRatePct = isfCrAutoActivationMaxSuspectFalseLowRatePct,
        isfCrAutoActivationMinDayTypeRatio = isfCrAutoActivationMinDayTypeRatio,
        isfCrAutoActivationMaxDayTypeSparseRatePct = isfCrAutoActivationMaxDayTypeSparseRatePct,
        isfCrAutoActivationRequireDailyQualityGate = isfCrAutoActivationRequireDailyQualityGate,
        isfCrAutoActivationDailyRiskBlockLevel = isfCrAutoActivationDailyRiskBlockLevel,
        isfCrAutoActivationMinDailyMatchedSamples = isfCrAutoActivationMinDailyMatchedSamples,
        isfCrAutoActivationMaxDailyMae30Mmol = isfCrAutoActivationMaxDailyMae30Mmol,
        isfCrAutoActivationMaxDailyMae60Mmol = isfCrAutoActivationMaxDailyMae60Mmol,
        isfCrAutoActivationMaxHypoRatePct = isfCrAutoActivationMaxHypoRatePct,
        isfCrAutoActivationMinDailyCiCoverage30Pct = isfCrAutoActivationMinDailyCiCoverage30Pct,
        isfCrAutoActivationMinDailyCiCoverage60Pct = isfCrAutoActivationMinDailyCiCoverage60Pct,
        isfCrAutoActivationMaxDailyCiWidth30Mmol = isfCrAutoActivationMaxDailyCiWidth30Mmol,
        isfCrAutoActivationMaxDailyCiWidth60Mmol = isfCrAutoActivationMaxDailyCiWidth60Mmol,
        isfCrAutoActivationRollingMinRequiredWindows = isfCrAutoActivationRollingMinRequiredWindows,
        isfCrAutoActivationRollingMaeRelaxFactor = isfCrAutoActivationRollingMaeRelaxFactor,
        isfCrAutoActivationRollingCiCoverageRelaxFactor = isfCrAutoActivationRollingCiCoverageRelaxFactor,
        isfCrAutoActivationRollingCiWidthRelaxFactor = isfCrAutoActivationRollingCiWidthRelaxFactor,
        isfCrActiveTags = isfCrActiveTags,
        isfCrTagJournal = emptyList(),
        adaptiveControllerEnabled = adaptiveControllerEnabled,
        safetyMinTargetMmol = safetyMinTargetMmol,
        safetyMaxTargetMmol = safetyMaxTargetMmol,
        postHypoThresholdMmol = postHypoThresholdMmol,
        postHypoTargetMmol = postHypoTargetMmol,
        verboseLogsEnabled = verboseLogsEnabled,
        retentionDays = analyticsLookbackDays,
        warningText = "Not a medical device. Verify all therapy decisions manually."
    )
}

internal fun energyProfileSettingsUiState(
    settings: EnergyProfileSettings,
    snapshot: EnergyProfileSnapshotEntity?,
    events: List<PlannedActivityEventEntity>,
    today: LocalDate = LocalDate.now(),
    policy: EnergyProfilePolicy = EnergyProfilePolicy()
): EnergyProfileSettingsUiState {
    val resolution = policy.resolve(settings, today)
    val tier = snapshot?.tier
        ?.let { raw -> runCatching { EvidenceTier.valueOf(raw) }.getOrNull() }
        ?: EvidenceTier.INSUFFICIENT_DATA
    fun summary(value: String?, manualValue: String, manual: Boolean) = ResolvedProfileSummaryUi(
        value = if (manual) manualValue else value ?: "Default",
        source = if (manual) "Manual" else if (value != null) "Auto" else "Default",
        confidence = tier,
        qualityDays = snapshot?.qualityDays ?: 0,
        calculatedAtMs = snapshot?.calculatedAtMs
    )
    val completeness = when (resolution) {
        is ProfileResolution.Complete -> "Ready"
        is ProfileResolution.Incomplete -> "Incomplete"
        is ProfileResolution.Invalid -> "Needs review"
    }
    val age = (resolution as? ProfileResolution.Complete)?.ageYears
    return EnergyProfileSettingsUiState(
        enabled = settings.enabled,
        derivedAgeYears = age,
        completeness = completeness,
        foodSummary = summary(
            value = snapshot?.foodProfile,
            manualValue = settings.manualFoodProfile.name,
            manual = settings.foodProfileMode.name == "MANUAL"
        ),
        activitySummary = summary(
            value = snapshot?.activityProfile,
            manualValue = settings.manualActivityProfile.name,
            manual = settings.activityProfileMode.name == "MANUAL"
        ),
        calorieSummary = when {
            settings.calorieGoalMode.name == "MANUAL_CLINICIAN" && settings.manualCalorieTargetKcal != null ->
                "${settings.manualCalorieTargetKcal} kcal"
            else -> settings.calorieGoalMode.name.lowercase(Locale.US).replaceFirstChar(Char::titlecase)
        },
        shareProfileWithAi = settings.shareProfileWithAi,
        userProfile = UserProfileDraftUi(
            birthDateEpochDay = settings.birthDateEpochDay,
            physiologicalSex = settings.physiologicalSex,
            heightCm = settings.heightCm,
            weightKg = settings.weightKg
        ),
        foodSettings = FoodProfileSettingsUi(
            mode = settings.foodProfileMode,
            manualProfile = settings.manualFoodProfile
        ),
        activitySettings = ActivityProfileSettingsUi(
            mode = settings.activityProfileMode,
            manualProfile = settings.manualActivityProfile,
            forecastInfluenceEnabled = settings.forecastActivityInfluenceEnabled
        ),
        energyGoalSettings = EnergyGoalSettingsUi(
            mode = settings.calorieGoalMode,
            manualTargetKcal = settings.manualCalorieTargetKcal,
            shareProfileWithAi = settings.shareProfileWithAi
        ),
        plannedEvents = events.map { event ->
            PlannedActivityEventUi(
                eventId = event.eventId,
                enabled = event.enabled,
                title = event.title,
                activityType = event.activityType,
                intensity = event.intensity,
                localStartIso = event.localStartIso,
                durationMinutes = event.durationMinutes,
                timezoneId = event.timezoneId,
                recurrenceDaysMask = event.recurrenceDaysMask,
                recurrenceEndEpochDay = event.recurrenceEndEpochDay,
                revision = event.revision,
                createdAtMs = event.createdAtMs,
                updatedAtMs = event.updatedAtMs
            )
        }
    )
}

private fun MainUiState.buildHorizonPredictions(): List<HorizonPredictionUi> {
    if (forecastTupleError != null) return emptyList()
    return listOf(
        HorizonPredictionUi(5, forecast5m, forecast5mCiLow, forecast5mCiHigh, UiFormatters.hasWideCi(forecast5mCiLow, forecast5mCiHigh)),
        HorizonPredictionUi(30, forecast30m, forecast30mCiLow, forecast30mCiHigh, UiFormatters.hasWideCi(forecast30mCiLow, forecast30mCiHigh)),
        HorizonPredictionUi(60, forecast60m, forecast60mCiLow, forecast60mCiHigh, UiFormatters.hasWideCi(forecast60mCiLow, forecast60mCiHigh))
    )
}

private fun parseInsulinCurveCompact(raw: String?): List<InsulinProfilePointUi> {
    if (raw.isNullOrBlank()) return emptyList()
    val parsed = raw
        .split(';')
        .asSequence()
        .map { token -> token.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { token ->
            val parts = token.split(':', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val minute = parts[0].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            val cumulative = parts[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            InsulinProfilePointUi(
                minute = minute.coerceAtLeast(0.0),
                cumulative = cumulative.coerceIn(0.0, 1.0)
            )
        }
        .sortedBy { it.minute }
        .toList()
    if (parsed.size < 2) return emptyList()
    val deduped = mutableListOf<InsulinProfilePointUi>()
    parsed.forEach { point ->
        if (deduped.isNotEmpty() && kotlin.math.abs(deduped.last().minute - point.minute) < 1e-6) {
            deduped[deduped.lastIndex] = point
        } else {
            deduped += point
        }
    }
    var lastCumulative = 0.0
    return deduped.map { point ->
        val cumulative = if (point.cumulative >= lastCumulative) point.cumulative else lastCumulative
        lastCumulative = cumulative
        point.copy(cumulative = cumulative.coerceIn(0.0, 1.0))
    }
}

private fun MainUiState.buildInterpolatedFuturePath(nowTs: Long): List<ChartPointUi> {
    if (forecastTupleError != null) return emptyList()
    val current = calibratedGlucoseMmol ?: latestGlucoseMmol ?: return emptyList()
    val anchors = listOfNotNull(
        0 to current,
        forecast5m?.let { 5 to it },
        forecast30m?.let { 30 to it },
        forecast60m?.let { 60 to it }
    ).sortedBy { it.first }
    if (anchors.size < 2) return emptyList()

    return (0..60 step 5).mapNotNull { minute ->
        val interpolated = interpolate(anchors, minute.toDouble()) ?: return@mapNotNull null
        ChartPointUi(ts = nowTs + minute * 60_000L, value = interpolated)
    }
}

private fun MainUiState.buildInterpolatedFutureCi(nowTs: Long): List<ChartCiPointUi> {
    if (forecastTupleError != null) return emptyList()
    val current = calibratedGlucoseMmol ?: latestGlucoseMmol ?: return emptyList()
    val lowAnchors = listOfNotNull(
        0 to current,
        forecast5mCiLow?.let { 5 to it },
        forecast30mCiLow?.let { 30 to it },
        forecast60mCiLow?.let { 60 to it }
    ).sortedBy { it.first }
    val highAnchors = listOfNotNull(
        0 to current,
        forecast5mCiHigh?.let { 5 to it },
        forecast30mCiHigh?.let { 30 to it },
        forecast60mCiHigh?.let { 60 to it }
    ).sortedBy { it.first }
    if (lowAnchors.size < 2 || highAnchors.size < 2) return emptyList()

    return (0..60 step 5).mapNotNull { minute ->
        val low = interpolate(lowAnchors, minute.toDouble()) ?: return@mapNotNull null
        val high = interpolate(highAnchors, minute.toDouble()) ?: return@mapNotNull null
        ChartCiPointUi(ts = nowTs + minute * 60_000L, low = low, high = high)
    }
}

private fun interpolate(anchors: List<Pair<Int, Double>>, x: Double): Double? {
    val leftIndex = anchors.indexOfLast { it.first.toDouble() <= x }
    val rightIndex = anchors.indexOfFirst { it.first.toDouble() >= x }
    if (leftIndex < 0 || rightIndex < 0) return null
    val left = anchors[leftIndex]
    val right = anchors[rightIndex]
    if (left.first == right.first) return left.second

    val ratio = (x - left.first) / (right.first - left.first)
    return left.second + (right.second - left.second) * ratio
}

private fun MainUiState.resolveLoadState(hasData: Boolean, errorText: String?): ScreenLoadState {
    return when {
        !errorText.isNullOrBlank() && !hasData -> ScreenLoadState.ERROR
        hasData -> ScreenLoadState.READY
        else -> ScreenLoadState.EMPTY
    }
}

private fun classifyMardBand(mardPct: Double?): String {
    val value = mardPct ?: return "NO_DATA"
    return when {
        value <= 10.0 -> "EXCELLENT"
        value <= 15.0 -> "GOOD"
        value <= 25.0 -> "WARNING"
        else -> "CRITICAL"
    }
}

private fun MainUiState.isDataStale(): Boolean {
    return latestDataAgeMinutes?.let { it > staleDataMaxMinutes } ?: true
}

private fun MainUiState.inferErrorText(): String? {
    return forecastTupleError
        ?: syncStatusLines.firstOrNull { it.startsWith("Last sync issue", ignoreCase = true) }
}

private fun MainUiState.buildOverviewSensorLagRolloutVerdict(): SensorLagRolloutVerdictUi? {
    val ageHours = sensorAgeHours ?: return null
    if (dailyReportSensorLagReplayBuckets.isEmpty() && dailyReportSensorLagShadowBuckets.isEmpty()) {
        return null
    }
    val guidance = buildSensorLagRolloutGuidance(
        replayBuckets = dailyReportSensorLagReplayBuckets.map {
            DailyReportSensorLagReplayUi(
                horizonMinutes = it.horizonMinutes,
                bucket = it.bucket,
                sampleCount = it.sampleCount,
                rawMae = it.rawMae,
                lagMae = it.lagMae,
                maeImprovementMmol = it.maeImprovementMmol,
                rawBias = it.rawBias,
                lagBias = it.lagBias
            )
        },
        shadowBuckets = dailyReportSensorLagShadowBuckets.map { source ->
            val shadowBucket = DailyReportSensorLagShadowUi(
                bucket = source.bucket,
                sampleCount = source.sampleCount,
                ruleChangedRatePct = source.ruleChangedRatePct,
                meanAbsTargetDeltaMmol = source.meanAbsTargetDeltaMmol
            )
            shadowBucket
        }
    )
    val currentBucket = sensorLagAgeBucket(ageHours)
    val currentGuidance = guidance.firstOrNull { it.bucket == currentBucket } ?: return null
    return SensorLagRolloutVerdictUi(
        status = currentGuidance.status.name,
        bucket = currentGuidance.bucket
    )
}

private fun LastActionRowUi.toUi(): LastActionUi {
    return LastActionUi(
        type = type,
        status = status,
        timestamp = timestamp,
        tempTargetMmol = tempTargetMmol,
        durationMinutes = durationMinutes,
        carbsGrams = carbsGrams,
        idempotencyKey = idempotencyKey,
        payloadSummary = payloadSummary
    )
}
