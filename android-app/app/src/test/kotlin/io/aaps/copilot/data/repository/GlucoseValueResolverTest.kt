package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GlucoseValueResolverTest {

    @Test
    fun picksMgdLValue_andIgnoresTimestampFromSgvArray() {
        val candidate = GlucoseValueResolver.resolve(
            mapOf(
                "sgvs[0].mills" to "1740693700000",
                "sgvs[0].mgdl" to "157",
                "sgvs[0].direction" to "Flat"
            )
        )

        assertThat(candidate).isNotNull()
        assertThat(candidate!!.valueRaw).isEqualTo(157.0)
        assertThat(candidate.key).contains("mgdl")
    }

    @Test
    fun prefersExactBgEstimateWhenPresent() {
        val candidate = GlucoseValueResolver.resolve(
            mapOf(
                "raw_glucose" to "149",
                "com.eveningoutpost.dexdrip.Extras.BgEstimate" to "8.7"
            )
        )

        assertThat(candidate).isNotNull()
        assertThat(candidate!!.valueRaw).isEqualTo(8.7)
        assertThat(candidate.key).isEqualTo("com.eveningoutpost.dexdrip.Extras.BgEstimate")
    }

    @Test
    fun returnsNullWhenNoGlucoseCandidateExists() {
        val candidate = GlucoseValueResolver.resolve(
            mapOf(
                "mills" to "1740693700000",
                "iob" to "2.1",
                "cob" to "18"
            )
        )

        assertThat(candidate).isNull()
    }

    @Test
    fun ignoresPredictedBgKeys_whenResolvingCurrentGlucose() {
        val candidate = GlucoseValueResolver.resolve(
            mapOf(
                "raw_predBGs_IOB_0_mgdl" to "605",
                "raw_predBGs_UAM_0_mgdl" to "590"
            )
        )

        assertThat(candidate).isNull()
    }

    @Test
    fun prefersRealGlucose_overPredictedBg() {
        val candidate = GlucoseValueResolver.resolve(
            mapOf(
                "raw_predBGs_IOB_0_mgdl" to "605",
                "raw_glucosemgdl" to "158"
            )
        )

        assertThat(candidate).isNotNull()
        assertThat(candidate!!.valueRaw).isEqualTo(158.0)
        assertThat(candidate.key).isEqualTo("raw_glucosemgdl")
    }
}
