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
}
