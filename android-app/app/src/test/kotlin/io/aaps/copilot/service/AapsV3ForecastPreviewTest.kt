package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity
import io.aaps.copilot.ui.foundation.screens.ChartPointUi
import io.aaps.copilot.ui.foundation.screens.ClinicalForecastChartUiState
import io.aaps.copilot.ui.foundation.screens.ForecastUiState
import io.aaps.copilot.ui.foundation.screens.HorizonPredictionUi
import io.aaps.copilot.ui.foundation.screens.OverviewUiState
import io.aaps.copilot.ui.foundation.screens.OverviewWarningKind
import io.aaps.copilot.ui.foundation.screens.OverviewWarningUi
import io.aaps.copilot.ui.foundation.screens.ScreenLoadState
import io.aaps.copilot.ui.foundation.screens.withAapsV3ForecastPreview
import io.aaps.copilot.ui.foundation.screens.withAapsV3ObservedGlucose
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AapsV3ForecastPreviewTest {
    private val now = 1_800_000_000_000L
    private val forecastError = "forecast_tuple_snapshot_unavailable"

    @Test
    fun localWireHistoryRestoresObservationAndOnlyPreviewCurve() = runBlocking {
        val preview = checkNotNull(AapsV3ForecastPreview.from(
            listOf(row(10, 110), row(0, 124), row(5, 117)), now
        ))
        val overview = withAapsV3ObservedGlucose(OverviewUiState(ScreenLoadState.ERROR, true, forecastError), preview, now)
        assertThat(overview.aapsV3Observation).isTrue()
        assertThat(overview.loadState).isEqualTo(ScreenLoadState.READY)
        assertThat(overview.errorText).isNull()
        assertThat(overview.forecastsCurrent).isFalse()
        assertThat(overview.chart.historyPoints).hasSize(3)
        assertThat(overview.chart.futurePath).isEmpty()
        val state = withAapsV3ForecastPreview(ForecastUiState(ScreenLoadState.ERROR, true, forecastError), preview, now)
        assertThat(state.aapsV3Observation).isTrue()
        assertThat(state.futurePath.map { it.ts }).containsExactly(now, now + 300_000, now + 1_800_000, now + 3_600_000).inOrder()
        assertThat(state.horizons).isEmpty()
        assertThat(state.futureCi).isEmpty()
    }

    @Test
    fun refusesMixedUnauthenticatedOrConflictingEvidence() = runBlocking {
        val first = row(10, 110)
        for (bad in listOf(row(10, 124), row(5, 118, "second"),
            row(0, 124).copy(source = "other"), row(0, 124).copy(modifiedRevision = now + 1),
            row(-1, 124), row(5, 118, units = "mmol/l"))) {
            assertThat(AapsV3ForecastPreview.from(listOf(first, bad), now)).isNull()
        }
    }

    @Test
    fun doesNotInventTrendForStaleSparseOrDiscontinuousSeries() = runBlocking {
        for (rows in listOf(listOf(row(7, 110), row(4, 115), row(0, 120)),
            listOf(row(20, 110), row(3, 115), row(0, 120)),
            listOf(row(16, 110), row(11, 115), row(6, 120)))) {
            assertThat(checkNotNull(AapsV3ForecastPreview.from(rows, now)).forecast).isEmpty()
        }
    }

    @Test
    fun neverReplacesExistingForecastAndExpiresWithoutNewRows() = runBlocking {
        val preview = checkNotNull(AapsV3ForecastPreview.from(listOf(row(0, 120)), now))
        val accepted = ForecastUiState(ScreenLoadState.READY, false, futurePath = listOf(ChartPointUi(now + 300_000, 6.4)))
        assertThat(withAapsV3ForecastPreview(accepted, preview, now)).isSameInstanceAs(accepted)
        val empty = OverviewUiState(ScreenLoadState.ERROR, true, forecastError)
        assertThat(withAapsV3ObservedGlucose(empty, preview, now + 300_001)).isSameInstanceAs(empty)
    }

    @Test
    fun staleRestoredHistoryCannotHideNewAapsObservationOrSupplyItsMetrics() = runBlocking {
        val preview = checkNotNull(AapsV3ForecastPreview.from(
            listOf(row(10, 110), row(5, 117), row(0, 124)), now
        ))
        val history = listOf(ChartPointUi(now - 40 * 60_000, 8.0))
        val oldForecast = listOf(ChartPointUi(now + 300_000, 9.1))
        val horizons = listOf(HorizonPredictionUi(5, 9.1, 7.0, 11.0))
        val warning = OverviewWarningUi(OverviewWarningKind.STRONG_GLUCOSE_ALERT)
        val stale = OverviewUiState(ScreenLoadState.READY, true, forecastError, glucose = 8.0, delta = 0.8,
            chart = ClinicalForecastChartUiState(historyPoints = history, futurePath = oldForecast,
                mealImpactPoints = oldForecast), horizons = horizons, forecastsCurrent = true,
            currentIobUnits = 2.0, currentCobGrams = 40.0, currentIsfMmolPerUnit = 3.0, currentCrGramsPerUnit = 10.0,
            warning = warning, killSwitchEnabled = true)
        val overview = withAapsV3ObservedGlucose(stale, preview, now)
        assertThat(overview.aapsV3Observation).isTrue()
        assertThat(overview.chart.historyPoints.last().ts).isEqualTo(now)
        assertThat(overview.glucose).isEqualTo(preview.glucose.last().valueMmol)
        assertThat(overview.delta).isNull()
        assertThat(overview.currentIobUnits).isNull()
        assertThat(overview.currentCobGrams).isNull()
        assertThat(overview.currentIsfMmolPerUnit).isNull()
        assertThat(overview.currentCrGramsPerUnit).isNull()
        assertThat(overview.forecastsCurrent).isFalse()
        assertThat(overview.chart.futurePath).isEmpty()
        assertThat(overview.chart.mealImpactPoints).isEmpty()
        assertThat(overview.warning).isEqualTo(warning)
        assertThat(overview.killSwitchEnabled).isTrue()

        val forecast = withAapsV3ForecastPreview(ForecastUiState(ScreenLoadState.READY, true,
            historyPoints = history, futurePath = oldForecast, horizons = horizons), preview, now)
        assertThat(forecast.aapsV3Observation).isTrue()
        assertThat(forecast.futurePath.first().ts).isEqualTo(now)
        assertThat(forecast.futurePath).isNotEqualTo(oldForecast)
        assertThat(forecast.horizons).isEmpty()
    }

    @Test
    fun doesNotReplaceActiveClinicalViewOrRewindItsNewestPoint() = runBlocking {
        val preview = checkNotNull(AapsV3ForecastPreview.from(listOf(row(0, 120)), now))
        val fresh = OverviewUiState(ScreenLoadState.READY, false, glucose = 6.0,
            chart = ClinicalForecastChartUiState(historyPoints = listOf(ChartPointUi(now - 1, 6.0))))
        assertThat(withAapsV3ObservedGlucose(fresh, preview, now)).isSameInstanceAs(fresh)
        val unsortedHistory = listOf(ChartPointUi(now, 6.0), ChartPointUi(now - 60_000, 6.0))
        val notOlder = fresh.copy(isStale = true, chart = ClinicalForecastChartUiState(historyPoints = unsortedHistory))
        assertThat(withAapsV3ObservedGlucose(notOlder, preview, now)).isSameInstanceAs(notOlder)
        val forecast = ForecastUiState(ScreenLoadState.READY, true, historyPoints = unsortedHistory)
        assertThat(withAapsV3ForecastPreview(forecast, preview, now)).isSameInstanceAs(forecast)
    }

    private fun row(minutes: Long, mgdl: Int, sensor: String = "fixture", units: String = "mg/dl"): LocalNightscoutV3DocumentEntity {
        val at = now - minutes * 60_000
        val payload = """{"date":$at,"app":"AAPS-local","type":"sgv","units":"$units","sgv":$mgdl,"device":"$sensor"}"""
        return LocalNightscoutV3DocumentEntity("entries", "event-$minutes", "local-v3-owner", at, payload,
            payload.length, now, now, false, false)
    }
}
