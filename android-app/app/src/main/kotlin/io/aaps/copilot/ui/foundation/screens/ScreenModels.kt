package io.aaps.copilot.ui.foundation.screens

enum class ScreenLoadState {
    LOADING,
    EMPTY,
    ERROR,
    READY
}

data class AppHealthUiState(
    val staleData: Boolean,
    val killSwitchEnabled: Boolean,
    val lastSyncText: String
)

data class HorizonPredictionUi(
    val horizonMinutes: Int,
    val pred: Double?,
    val ciLow: Double?,
    val ciHigh: Double?
)

data class OverviewUiState(
    val loadState: ScreenLoadState,
    val isStale: Boolean,
    val errorText: String? = null,
    val glucose: Double? = null,
    val delta: Double? = null,
    val iobUnits: Double? = null,
    val cobGrams: Double? = null,
    val horizons: List<HorizonPredictionUi> = emptyList()
)

data class ForecastUiState(
    val loadState: ScreenLoadState,
    val isStale: Boolean,
    val errorText: String? = null,
    val horizons: List<HorizonPredictionUi> = emptyList(),
    val qualityLines: List<String> = emptyList()
)

data class UamUiState(
    val loadState: ScreenLoadState,
    val isStale: Boolean,
    val errorText: String? = null,
    val inferredActive: Boolean? = null,
    val inferredCarbsGrams: Double? = null,
    val inferredConfidence: Double? = null,
    val calculatedActive: Boolean? = null,
    val calculatedCarbsGrams: Double? = null,
    val calculatedConfidence: Double? = null
)

data class SafetyUiState(
    val loadState: ScreenLoadState,
    val isStale: Boolean,
    val errorText: String? = null,
    val killSwitchEnabled: Boolean,
    val baseTarget: Double,
    val staleMinutesLimit: Int,
    val maxActionsIn6h: Int,
    val sensorQualitySummary: String
)

data class AuditUiState(
    val loadState: ScreenLoadState,
    val isStale: Boolean,
    val errorText: String? = null,
    val rows: List<String> = emptyList()
)

data class AnalyticsUiState(
    val loadState: ScreenLoadState,
    val isStale: Boolean,
    val errorText: String? = null,
    val qualityLines: List<String> = emptyList(),
    val baselineDeltaLines: List<String> = emptyList()
)

data class SettingsUiState(
    val loadState: ScreenLoadState,
    val isStale: Boolean,
    val errorText: String? = null,
    val baseTarget: Double,
    val nightscoutUrl: String,
    val insulinProfileId: String,
    val localNightscoutEnabled: Boolean,
    val resolvedNightscoutUrl: String
)
