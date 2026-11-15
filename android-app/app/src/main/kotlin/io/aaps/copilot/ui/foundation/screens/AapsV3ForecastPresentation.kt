package io.aaps.copilot.ui.foundation.screens

import io.aaps.copilot.service.AapsV3ForecastPreview

internal fun withAapsV3ObservedGlucose(
    state: OverviewUiState, preview: AapsV3ForecastPreview?, now: Long
): OverviewUiState {
    if (preview == null) return state
    val clinicalAnchor = state.chart.historyPoints.maxOfOrNull { it.ts }
    if (!shouldShowObservation(state.isStale, state.glucose != null || state.forecastsCurrent ||
            state.chart.futurePath.isNotEmpty(), clinicalAnchor, preview.lastSampleAt)) return state
    val age = now - preview.lastSampleAt
    if (age !in 0..AapsV3ForecastPreview.FRESH_MS) return state
    val glucose = preview.glucose.last().valueMmol
    return state.copy(
        loadState = ScreenLoadState.READY, isStale = false, errorText = null,
        glucose = glucose, rawGlucose = glucose, calibratedGlucose = null, correctedGlucose = null,
        delta = null, sampleAgeMinutes = age / 60_000L, forecastsCurrent = false, horizons = emptyList(),
        aapsV3Observation = true,
        currentIobUnits = null, iobDetails = IobRuntimeDetailsUi(),
        currentCobGrams = null, externalCobGrams = null, externalCobTimestamp = null, foodCobTimestamp = null,
        acceptedFoodDisplay = null, foodDisplayStatus = FoodDisplayStatus.UNAVAILABLE,
        currentIsfMmolPerUnit = null, currentCrGramsPerUnit = null,
        isfRuntime = MetricRuntimeSourceUi(), crRuntime = MetricRuntimeSourceUi(),
        calculatedUamCarbsGrams = null, calculatedUamConfidence = null, telemetryChips = emptyList(),
        chart = state.chart.copy(
            historyPoints = preview.glucose.map { ChartPointUi(it.ts, it.valueMmol) },
            futurePath = emptyList(), futureCi = emptyList(), mealImpactPoints = emptyList(),
            mealImpactGiAdjusted = false, mealImpactComplete = null
        )
    )
}

internal fun withAapsV3ForecastPreview(
    state: ForecastUiState, preview: AapsV3ForecastPreview?, now: Long
): ForecastUiState {
    if (preview == null) return state
    val clinicalAnchor = state.historyPoints.maxOfOrNull { it.ts }
    if (!shouldShowObservation(state.isStale, state.futurePath.isNotEmpty() || state.horizons.isNotEmpty(),
            clinicalAnchor, preview.lastSampleAt)) return state
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

// Keep a current accepted view. A stale view can be replaced only by a fresh, strictly newer observation.
private fun shouldShowObservation(stale: Boolean, hasClinicalValues: Boolean, clinicalAnchor: Long?,
                                  observationAt: Long): Boolean =
    if (stale) clinicalAnchor == null || observationAt > clinicalAnchor
    else !hasClinicalValues && (clinicalAnchor == null || observationAt >= clinicalAnchor)
