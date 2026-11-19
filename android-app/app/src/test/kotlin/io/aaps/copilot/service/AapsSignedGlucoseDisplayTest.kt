package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.ui.foundation.screens.ForecastUiState
import io.aaps.copilot.ui.foundation.screens.ScreenLoadState
import io.aaps.copilot.ui.foundation.screens.withAapsV3ForecastPreview
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AapsSignedGlucoseDisplayTest {
    private val now = 1_800_000_000_000L

    @Test
    fun sparseSignedSnapshotSuppliesOnlyObservedGlucose() {
        val result = display(sample(now - 60_000), sample(now - 120_000), sample(now - 540_000))
        assertThat(result?.lastSampleAt).isEqualTo(now - 60_000)
        assertThat(result?.glucose?.map { it.ts }).containsExactly(
            now - 540_000, now - 120_000, now - 60_000
        ).inOrder()
        assertThat(result?.forecast).isEmpty()
    }

    @Test
    fun signedHistoryRestoresTheTrendOnTheForecastScreen() {
        val result = display(sample(now - 300_000, sgv = 112), sample(now, sgv = 118),
            sample(now - 600_000, sgv = 108))
        val screen = withAapsV3ForecastPreview(ForecastUiState(ScreenLoadState.ERROR, true), result, now)
        assertThat(screen.aapsV3Observation).isTrue()
        assertThat(screen.futurePath.map { it.ts }).containsExactly(now, now + 300_000,
            now + 1_800_000, now + 3_600_000).inOrder()
        assertThat(screen.horizons).isEmpty()
        assertThat(screen.futureCi).isEmpty()
    }

    @Test
    fun signedTrendDoesNotBridgeLongGaps() {
        val result = display(sample(now), sample(now - 300_000), sample(now - 960_000))
        assertThat(result?.glucose).hasSize(3)
        assertThat(result?.forecast).isEmpty()
    }

    @Test
    fun staleOrFutureMeasurementsAreNotShown() {
        assertThat(display(sample(now - AapsV3ForecastPreview.FRESH_MS - 1))).isNull()
        assertThat(display(sample(now + 1))).isNull()
        assertThat(display(sample(now - 60_000), snapshotAt = now - 10_001)).isNull()
    }

    @Test
    fun conflictingSourcesOrTimestampsAreNotShown() {
        assertThat(display(sample(now - 1000), sample(now - 2000, sensor = "Other"))).isNull()
        assertThat(display(sample(now - 1000), sample(now - 1000))).isNull()
    }

    @Test
    fun unexpectedFieldsOrUnusableValuesAreRejected() {
        assertThat(display("""{"date":${now - 1000},"sensor":"Xdrip","sgv":108,"units":"mmol"}""")).isNull()
        assertThat(display(sample(now - 1000, sgv = 0))).isNull()
        assertThat(AapsLocalObservation.decodeDisplayGlucose("{".encodeToByteArray(), now)).isNull()
    }

    private fun display(vararg samples: String, snapshotAt: Long = now): AapsV3ForecastPreview? {
        val snapshot = """{"version":1,"at":$snapshotAt,"glucose":[${samples.joinToString(",")}],"insulin":[]}"""
        return runBlocking { AapsV3ForecastPreview.displayFromSignedSnapshot(
            AapsLocalObservation.decodeDisplayGlucose(snapshot.encodeToByteArray(), now), now
        ) }
    }

    private fun sample(time: Long, sensor: String = "Xdrip", sgv: Int = 108) =
        """{"date":$time,"sgv":$sgv,"sensor":"$sensor"}"""
}
