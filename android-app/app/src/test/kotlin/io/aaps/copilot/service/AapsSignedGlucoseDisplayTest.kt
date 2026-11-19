package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AapsSignedGlucoseDisplayTest {
    private val now = 1_800_000_000_000L

    @Test
    fun freshSignedSnapshotSuppliesOnlyObservedGlucose() {
        val result = display(sample(now - 60_000), sample(now - 120_000), sample(now - 540_000))
        assertThat(result?.lastSampleAt).isEqualTo(now - 60_000)
        assertThat(result?.glucose?.map { it.ts }).containsExactly(
            now - 540_000, now - 120_000, now - 60_000
        ).inOrder()
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
        return AapsV3ForecastPreview.displayFromSignedSnapshot(
            AapsLocalObservation.decodeDisplayGlucose(snapshot.encodeToByteArray(), now), now
        )
    }

    private fun sample(time: Long, sensor: String = "Xdrip", sgv: Int = 108) =
        """{"date":$time,"sgv":$sgv,"sensor":"$sensor"}"""
}
