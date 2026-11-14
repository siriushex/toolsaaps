package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity
import io.aaps.copilot.ui.foundation.screens.ChartPointUi
import io.aaps.copilot.ui.foundation.screens.ForecastUiState
import io.aaps.copilot.ui.foundation.screens.OverviewUiState
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

    private fun row(minutes: Long, mgdl: Int, sensor: String = "fixture", units: String = "mg/dl"): LocalNightscoutV3DocumentEntity {
        val at = now - minutes * 60_000
        val payload = """{"date":$at,"app":"AAPS-local","type":"sgv","units":"$units","sgv":$mgdl,"device":"$sensor"}"""
        return LocalNightscoutV3DocumentEntity("entries", "event-$minutes", "local-v3-owner", at, payload,
            payload.length, now, now, false, false)
    }
}
