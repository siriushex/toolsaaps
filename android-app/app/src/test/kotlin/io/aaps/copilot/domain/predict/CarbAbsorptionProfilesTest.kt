package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import org.junit.Test

class CarbAbsorptionProfilesTest {

    @Test
    fun catalogHasExpectedSizes() {
        assertThat(CarbAbsorptionProfiles.fastFoodCatalog).hasSize(100)
        assertThat(CarbAbsorptionProfiles.mediumFoodCatalog).hasSize(100)
        assertThat(CarbAbsorptionProfiles.proteinFoodCatalog).hasSize(50)
        assertThat(CarbAbsorptionProfiles.allCatalog).hasSize(250)
    }

    @Test
    fun classifyByCatalogTextFast() {
        val event = TherapyEvent(
            ts = NOW_TS,
            type = "carbs",
            payload = mapOf("grams" to "24", "food" to "banana and honey")
        )
        val classified = CarbAbsorptionProfiles.classifyCarbEvent(
            event = event,
            glucose = baselineGlucose(),
            nowTs = NOW_TS + 30 * MINUTE_MS
        )
        assertThat(classified.type).isEqualTo(CarbAbsorptionType.FAST)
    }

    @Test
    fun classifyByPatternProteinFallback() {
        val eventTs = NOW_TS
        val event = TherapyEvent(
            ts = eventTs,
            type = "carbs",
            payload = mapOf("grams" to "25")
        )
        val glucose = listOf(
            point(eventTs - 20 * MINUTE_MS, 6.0),
            point(eventTs - 10 * MINUTE_MS, 6.0),
            point(eventTs + 15 * MINUTE_MS, 6.05),
            point(eventTs + 30 * MINUTE_MS, 6.10),
            point(eventTs + 60 * MINUTE_MS, 6.25),
            point(eventTs + 120 * MINUTE_MS, 6.95)
        )
        val classified = CarbAbsorptionProfiles.classifyCarbEvent(
            event = event,
            glucose = glucose,
            nowTs = eventTs + 130 * MINUTE_MS
        )
        assertThat(classified.type).isEqualTo(CarbAbsorptionType.PROTEIN_SLOW)
    }

    @Test
    fun fastAbsorptionGivesHigherEarlyCumulativeThanProtein() {
        val age30 = 30.0
        val fast = CarbAbsorptionProfiles.cumulative(CarbAbsorptionType.FAST, age30)
        val protein = CarbAbsorptionProfiles.cumulative(CarbAbsorptionType.PROTEIN_SLOW, age30)
        assertThat(fast).isGreaterThan(protein)
    }

    private fun baselineGlucose(): List<GlucosePoint> {
        return listOf(
            point(NOW_TS - 15 * MINUTE_MS, 6.2),
            point(NOW_TS - 10 * MINUTE_MS, 6.2),
            point(NOW_TS - 5 * MINUTE_MS, 6.2),
            point(NOW_TS, 6.2)
        )
    }

    private fun point(ts: Long, value: Double): GlucosePoint {
        return GlucosePoint(
            ts = ts,
            valueMmol = value,
            source = "test",
            quality = DataQuality.OK
        )
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val NOW_TS = 1_700_000_000_000L
    }
}

