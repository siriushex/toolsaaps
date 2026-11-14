package io.aaps.copilot.ui.foundation.screens

import io.aaps.copilot.service.AapsV3ForecastPreview

internal fun withAapsV3ObservedGlucose(
    state: OverviewUiState, preview: AapsV3ForecastPreview?, now: Long
): OverviewUiState {
    if (state.glucose != null || state.chart.historyPoints.isNotEmpty() || preview == null) return state
    val age = now - preview.lastSampleAt
    if (age !in 0..AapsV3ForecastPreview.FRESH_MS) return state
    val glucose = preview.glucose.last().valueMmol
    return state.copy(
        loadState = ScreenLoadState.READY, isStale = false, errorText = null,
        glucose = glucose, rawGlucose = glucose, sampleAgeMinutes = age / 60_000L,
        aapsV3Observation = true,
        chart = state.chart.copy(historyPoints = preview.glucose.map { ChartPointUi(it.ts, it.valueMmol) })
    )
}

internal fun withAapsV3ForecastPreview(
    state: ForecastUiState, preview: AapsV3ForecastPreview?, now: Long
): ForecastUiState {
    if (preview == null || state.futurePath.isNotEmpty() ||
        state.historyPoints.lastOrNull()?.ts?.let { it > preview.lastSampleAt } == true) return state
    val age = now - preview.lastSampleAt
    if (age !in 0..AapsV3ForecastPreview.FRESH_MS) return state
    val anchor = preview.glucose.last()
    val path = if (preview.forecast.isEmpty()) emptyList() else
        listOf(ChartPointUi(anchor.ts, anchor.valueMmol)) + preview.forecast.map { ChartPointUi(it.ts, it.valueMmol) }
    return state.copy(
        loadState = ScreenLoadState.READY, isStale = false, errorText = null,
        horizons = emptyList(),
        historyPoints = preview.glucose.map { ChartPointUi(it.ts, it.valueMmol) },
        futurePath = path,
        futureCi = emptyList(),
        decomposition = ForecastDecompositionUi(),
        qualityLines = emptyList(),
        aapsV3Observation = true
    )
}
