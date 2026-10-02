package io.aaps.copilot.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnitConverterTest {

    @Test
    fun mmolToMgdl_andBack_isConsistent() {
        val mmol = 5.5
        val mgdl = UnitConverter.mmolToMgdl(mmol)
        assertThat(mgdl).isEqualTo(99)

        val roundTrip = UnitConverter.mgdlToMmol(mgdl.toDouble())
        assertThat(roundTrip).isWithin(0.1).of(mmol)
    }

    @Test
    fun mgdlToMmol_convertsExpectedRange() {
        val mmol = UnitConverter.mgdlToMmol(180.0)
        assertThat(mmol).isWithin(0.05).of(9.99)
    }

    @Test
    fun tempTargetObservationAcceptsOnlyOriginalOrExactWireRoundTrip() {
        for (step in 80..200) {
            val target = step * 0.05
            val observed = UnitConverter.mgdlToMmol(UnitConverter.mmolToMgdl(target).toDouble())
            assertThat(UnitConverter.matchesTempTargetObservation(target, target)).isTrue()
            assertThat(UnitConverter.matchesTempTargetObservation(target, observed)).isTrue()
            assertThat(UnitConverter.matchesTempTargetObservation(target, observed + 0.00001)).isFalse()
        }
        assertThat(UnitConverter.matchesTempTargetObservation(Double.NaN, 5.0)).isFalse()
        assertThat(UnitConverter.matchesTempTargetObservation(5.0, Double.POSITIVE_INFINITY)).isFalse()
        assertThat(UnitConverter.matchesTempTargetObservation(0.0, 0.0)).isFalse()
    }
}
