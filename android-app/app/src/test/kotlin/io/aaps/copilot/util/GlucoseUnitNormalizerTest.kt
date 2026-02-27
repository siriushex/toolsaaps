package io.aaps.copilot.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GlucoseUnitNormalizerTest {

    @Test
    fun convertsMgdlByKey() {
        val mmol = GlucoseUnitNormalizer.normalizeToMmol(
            valueRaw = 126.0,
            valueKey = "raw_sgvs_0_mgdl",
            units = null
        )
        assertThat(mmol).isWithin(0.001).of(UnitConverter.mgdlToMmol(126.0))
    }

    @Test
    fun keepsMmolWhenExplicitMmolAndReasonableRange() {
        val mmol = GlucoseUnitNormalizer.normalizeToMmol(
            valueRaw = 8.7,
            valueKey = "BgEstimate",
            units = "mmol/L"
        )
        assertThat(mmol).isWithin(0.001).of(8.7)
    }

    @Test
    fun convertsBgEstimateWithoutUnitsWhenValueLooksLikeMgdl() {
        val mmol = GlucoseUnitNormalizer.normalizeToMmol(
            valueRaw = 33.0,
            valueKey = "BgEstimate",
            units = null
        )
        assertThat(mmol).isWithin(0.001).of(UnitConverter.mgdlToMmol(33.0))
    }

    @Test
    fun convertsWhenUnitsSayMmolButValueIsClearlyMgdl() {
        val mmol = GlucoseUnitNormalizer.normalizeToMmol(
            valueRaw = 126.0,
            valueKey = "BgEstimate",
            units = "mmol/L"
        )
        assertThat(mmol).isWithin(0.001).of(UnitConverter.mgdlToMmol(126.0))
    }
}
