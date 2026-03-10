package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import org.junit.Test

class Glucose5mCanonicalizerTest {

    @Test
    fun buildsFiveMinuteCadenceFromMinuteLevelSeries() {
        val start = 1_000_000L
        val raw = (0 until 21).map { minute ->
            GlucosePoint(
                ts = start + minute * 60_000L,
                valueMmol = 6.0 + minute * 0.05,
                source = "sensor",
                quality = DataQuality.OK
            )
        }

        val canonical = Glucose5mCanonicalizer.build(raw)

        assertThat(canonical.points).hasSize(5)
        canonical.points.zipWithNext().forEach { (a, b) ->
            assertThat(b.ts - a.ts).isEqualTo(5 * 60_000L)
        }
        assertThat(canonical.observedCount).isAtLeast(4)
        assertThat(canonical.points.last().valueMmol).isWithin(0.15).of(raw.last().valueMmol)
    }
}
