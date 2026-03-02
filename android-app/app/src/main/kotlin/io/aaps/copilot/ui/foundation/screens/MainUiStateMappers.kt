package io.aaps.copilot.ui.foundation.screens

import io.aaps.copilot.ui.MainUiState
import java.util.Locale

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

internal fun MainUiState.toOverviewUiState(): OverviewUiState {
    val error = inferErrorText()
    val horizons = listOf(
        HorizonPredictionUi(5, forecast5m, forecast5mCiLow, forecast5mCiHigh),
        HorizonPredictionUi(30, forecast30m, forecast30mCiLow, forecast30mCiHigh),
        HorizonPredictionUi(60, forecast60m, forecast60mCiLow, forecast60mCiHigh)
    )
    val hasData = latestGlucoseMmol != null || horizons.any { it.pred != null }
    return OverviewUiState(
        loadState = resolveLoadState(hasData = hasData, errorText = error),
        isStale = isDataStale(),
        errorText = error,
        glucose = latestGlucoseMmol,
        delta = glucoseDelta,
        iobUnits = latestIobUnits,
        cobGrams = latestCobGrams,
        horizons = horizons
    )
}

internal fun MainUiState.toForecastUiState(): ForecastUiState {
    val error = inferErrorText()
    val horizons = listOf(
        HorizonPredictionUi(5, forecast5m, forecast5mCiLow, forecast5mCiHigh),
        HorizonPredictionUi(30, forecast30m, forecast30mCiLow, forecast30mCiHigh),
        HorizonPredictionUi(60, forecast60m, forecast60mCiLow, forecast60mCiHigh)
    )
    val hasData = horizons.any { it.pred != null }
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
    return ForecastUiState(
        loadState = resolveLoadState(hasData = hasData, errorText = error),
        isStale = isDataStale(),
        errorText = error,
        horizons = horizons,
        qualityLines = quality + baselineDeltaLines
    )
}

internal fun MainUiState.toUamUiState(): UamUiState {
    val hasData = inferredUamActive != null || calculatedUamActive != null
    return UamUiState(
        loadState = resolveLoadState(hasData = hasData, errorText = null),
        isStale = isDataStale(),
        inferredActive = inferredUamActive,
        inferredCarbsGrams = inferredUamCarbsGrams,
        inferredConfidence = inferredUamConfidence,
        calculatedActive = calculatedUamActive,
        calculatedCarbsGrams = calculatedUamCarbsGrams,
        calculatedConfidence = calculatedUamConfidence
    )
}

internal fun MainUiState.toSafetyUiState(): SafetyUiState {
    val sensorSummary = buildString {
        val scoreText = sensorQualityScore?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
        append("score=")
        append(scoreText)
        sensorQualityBlocked?.let {
            append(", blocked=")
            append(if (it) "yes" else "no")
        }
        sensorQualityReason?.takeIf { it.isNotBlank() }?.let {
            append(", reason=")
            append(it)
        }
    }
    return SafetyUiState(
        loadState = ScreenLoadState.READY,
        isStale = isDataStale(),
        killSwitchEnabled = killSwitch,
        baseTarget = baseTargetMmol,
        staleMinutesLimit = staleDataMaxMinutes,
        maxActionsIn6h = maxActionsIn6Hours,
        sensorQualitySummary = sensorSummary
    )
}

internal fun MainUiState.toAuditUiState(): AuditUiState {
    val error = inferErrorText()
    val rows = auditLines.take(150)
    return AuditUiState(
        loadState = resolveLoadState(hasData = rows.isNotEmpty(), errorText = error),
        isStale = isDataStale(),
        errorText = error,
        rows = rows
    )
}

internal fun MainUiState.toAnalyticsUiState(): AnalyticsUiState {
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
    return AnalyticsUiState(
        loadState = resolveLoadState(hasData = all.isNotEmpty(), errorText = null),
        isStale = isDataStale(),
        qualityLines = rows,
        baselineDeltaLines = baselineDeltaLines
    )
}

internal fun MainUiState.toSettingsUiState(): SettingsUiState {
    return SettingsUiState(
        loadState = ScreenLoadState.READY,
        isStale = isDataStale(),
        baseTarget = baseTargetMmol,
        nightscoutUrl = nightscoutUrl,
        insulinProfileId = insulinProfileId,
        localNightscoutEnabled = localNightscoutEnabled,
        resolvedNightscoutUrl = resolvedNightscoutUrl
    )
}

private fun MainUiState.resolveLoadState(hasData: Boolean, errorText: String?): ScreenLoadState {
    return when {
        !errorText.isNullOrBlank() && !hasData -> ScreenLoadState.ERROR
        hasData -> ScreenLoadState.READY
        else -> ScreenLoadState.EMPTY
    }
}

private fun MainUiState.isDataStale(): Boolean {
    return latestDataAgeMinutes?.let { it > staleDataMaxMinutes } ?: true
}

private fun MainUiState.inferErrorText(): String? {
    return syncStatusLines.firstOrNull { it.startsWith("Last sync issue", ignoreCase = true) }
}
