package io.aaps.copilot.ui.foundation.screens

import io.aaps.copilot.config.isCopilotCloudBackendEndpoint
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.domain.predict.InsulinActionProfiles
import io.aaps.copilot.ui.LastActionRowUi
import io.aaps.copilot.ui.MainUiState
import io.aaps.copilot.ui.foundation.format.UiFormatters
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

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

internal fun MainUiState.toOverviewUiState(
    isProMode: Boolean
): OverviewUiState {
    val error = inferErrorText()
    val horizons = buildHorizonPredictions()

    val telemetryChips = buildList {
        add(TelemetryChipUi("IOB", UiFormatters.formatUnits(latestIobUnits), "U"))
        if (latestIobRealUnits != null) {
            add(TelemetryChipUi("IOB real", UiFormatters.formatUnits(latestIobRealUnits), "U"))
        }
        add(TelemetryChipUi("COB", UiFormatters.formatGrams(latestCobGrams), "g"))
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
        glucose = latestGlucoseMmol,
        delta = glucoseDelta,
        sampleAgeMinutes = latestDataAgeMinutes,
        horizons = horizons,
        uamActive = inferredUamActive ?: calculatedUamActive,
        uci0Mmol5m = calculatedUci0Mmol5m,
        inferredCarbsLast60g = inferredUamCarbsGrams,
        uamModeLabel = if (enableUamBoost) "BOOST" else "NORMAL",
        telemetryChips = telemetryChips,
        lastAction = lastAction?.toUi(),
        canRunCycleNow = true,
        killSwitchEnabled = killSwitch
    )
}

internal fun MainUiState.toForecastUiState(
    range: ForecastRangeUi,
    layers: ForecastLayerState,
    isProMode: Boolean
): ForecastUiState {
    val error = inferErrorText()
    val horizons = buildHorizonPredictions()

    val nowTs = glucoseHistoryPoints.lastOrNull()?.timestamp ?: System.currentTimeMillis()
    val historyCutoff = nowTs - range.hours * 60 * 60_000L
    val history = glucoseHistoryPoints
        .filter { it.timestamp >= historyCutoff }
        .map { ChartPointUi(ts = it.timestamp, value = it.valueMmol) }

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
        decomposition = ForecastDecompositionUi(
            trend60 = trend60ComponentMmol,
            therapy60 = therapy60ComponentMmol,
            uam60 = uam60ComponentMmol,
            residualRoc0 = residualRoc0Mmol5m,
            sigmaE = sigmaEMmol5m,
            kfSigmaG = kfSigmaGMmol
        ),
        qualityLines = quality + baselineDeltaLines
    )
}

internal fun MainUiState.toUamUiState(): UamUiState {
    val hasData = inferredUamActive != null || calculatedUamActive != null || uamEventRows.isNotEmpty()
    return UamUiState(
        loadState = resolveLoadState(hasData = hasData, errorText = null),
        isStale = isDataStale(),
        inferredActive = inferredUamActive,
        inferredCarbsGrams = inferredUamCarbsGrams,
        inferredConfidence = inferredUamConfidence,
        calculatedActive = calculatedUamActive,
        calculatedCarbsGrams = calculatedUamCarbsGrams,
        calculatedConfidence = calculatedUamConfidence,
        events = uamEventRows.map { row ->
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
        enableUamExportToAaps = enableUamExportToAaps,
        dryRunExport = dryRunExport
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
            ok = localNightscoutEnabled,
            details = if (localNightscoutEnabled) "port=$localNightscoutPort" else "disabled"
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

internal fun MainUiState.toAnalyticsUiState(): AnalyticsUiState {
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
    val latestRealHistoryPoint = isfCrHistoryPoints
        .asSequence()
        .filter { it.isfCalculated != null || it.crCalculated != null }
        .maxByOrNull { it.timestamp }
    val latestAapsHistoryPoint = isfCrHistoryPoints
        .asSequence()
        .filter { it.isfAaps != null || it.crAaps != null }
        .maxByOrNull { it.timestamp }
    val currentIsfReal = isfCrRealtimeIsfEff
        ?: latestRealHistoryPoint?.isfCalculated
        ?: profileCalculatedIsf
        ?: profileIsf
    val currentCrReal = isfCrRealtimeCrEff
        ?: latestRealHistoryPoint?.crCalculated
        ?: profileCalculatedCr
        ?: profileCr
    val currentIsfMerged = latestAapsHistoryPoint?.isfAaps
        ?: isfCrRealtimeIsfBase
        ?: latestHistoryPoint?.isfMerged
        ?: profileIsf
    val currentCrMerged = latestAapsHistoryPoint?.crAaps
        ?: isfCrRealtimeCrBase
        ?: latestHistoryPoint?.crMerged
        ?: profileCr
    val hasData = all.isNotEmpty() ||
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
        dailyReportMatchedSamples != null ||
        isfCrDroppedReasons24hLines.isNotEmpty() ||
        isfCrDroppedReasons7dLines.isNotEmpty() ||
        isfCrHistoryPoints.isNotEmpty() ||
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
        historyLastUpdatedTs = isfCrHistoryLastUpdatedTs,
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

internal fun MainUiState.toAiAnalysisUiState(
    chatMessages: List<AiChatMessageUi> = emptyList(),
    chatInProgress: Boolean = false
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
        localRecommendations = dailyReportRecommendations,
        rollingLines = rollingReportLines,
        aiTuningStatus = toAiTuningStatusUi(),
        chatMessages = chatMessages,
        chatInProgress = chatInProgress
    )
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
    proModeEnabled: Boolean
): SettingsUiState {
    return SettingsUiState(
        loadState = ScreenLoadState.READY,
        isStale = isDataStale(),
        proModeEnabled = proModeEnabled,
        baseTarget = baseTargetMmol,
        nightscoutUrl = nightscoutUrl,
        aiApiUrl = cloudUrl,
        aiApiKey = openAiApiKey,
        uiStyle = uiStyle,
        resolvedNightscoutUrl = resolvedNightscoutUrl,
        insulinProfileId = insulinProfileId,
        localNightscoutEnabled = localNightscoutEnabled,
        localBroadcastIngestEnabled = localBroadcastIngestEnabled,
        strictBroadcastSenderValidation = strictBroadcastSenderValidation,
        enableUamInference = enableUamInference,
        enableUamBoost = enableUamBoost,
        enableUamExportToAaps = enableUamExportToAaps,
        uamExportMode = uamExportMode,
        dryRunExport = dryRunExport,
        uamMinSnackG = uamMinSnackG,
        uamMaxSnackG = uamMaxSnackG,
        uamSnackStepG = uamSnackStepG,
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

private fun MainUiState.buildHorizonPredictions(): List<HorizonPredictionUi> {
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
    val current = latestGlucoseMmol ?: return emptyList()
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
    val current = latestGlucoseMmol ?: return emptyList()
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
    return syncStatusLines.firstOrNull { it.startsWith("Last sync issue", ignoreCase = true) }
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
