package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test

class KalmanGlucoseFilterV3Test {

    @Test
    fun knownInputPreventsExplainedRiseFromBecomingResidualVelocity() {
        val withoutInput = KalmanGlucoseFilterV3()
        val withInput = KalmanGlucoseFilterV3()
        val startTs = 1_000_000_000L
        val values = listOf(6.0, 6.2, 6.4, 6.6, 6.8, 7.0, 7.2, 7.4)

        var plainSnapshot: KalmanSnapshotV3? = null
        var inputSnapshot: KalmanSnapshotV3? = null
        values.forEachIndexed { index, value ->
            val ts = startTs + index * 5 * 60_000L
            plainSnapshot = withoutInput.update(
                zMmol = value,
                ts = ts,
                volNorm = 0.1
            )
            inputSnapshot = withInput.update(
                zMmol = value,
                ts = ts,
                volNorm = 0.1,
                uRocPerMin = 0.2 / 5.0
            )
        }

        val plain = requireNotNull(plainSnapshot)
        val knownInput = requireNotNull(inputSnapshot)

        assertThat(plain.updatesCount).isAtLeast(3)
        assertThat(knownInput.updatesCount).isAtLeast(3)
        assertThat(abs(plain.rocPer5Mmol)).isGreaterThan(0.08)
        assertThat(abs(knownInput.rocPer5Mmol)).isLessThan(abs(plain.rocPer5Mmol))
        assertThat(abs(knownInput.rocPer5Mmol)).isLessThan(0.08)
    }
}
