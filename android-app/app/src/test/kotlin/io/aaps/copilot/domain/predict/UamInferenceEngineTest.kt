package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import org.junit.Test

class UamInferenceEngineTest {

    private val engine = UamInferenceEngine()

    @Test
    fun boostMultiplierDoublesEstimation() {
        val nowTs = 1_760_000_000_000L
        val glucose = mildlyRisingGlucose(nowTs)
        val settings = UamUserSettings(
            minSnackG = 15,
            maxSnackG = 60,
            snackStepG = 15,
            gAbsThreshold_Normal = 1.0,
            gAbsThreshold_Boost = 0.8,
            mOfN_Normal = 1 to 1,
            mOfN_Boost = 1 to 1,
            confirmConf_Normal = 0.0,
            confirmConf_Boost = 0.0,
            minConfirmAgeMin = 0
        )

        val normal = engine.infer(
            UamInferenceEngine.Input(
                nowTs = nowTs,
                glucose = glucose,
                therapyEvents = emptyList(),
                existingEvents = emptyList(),
                isfMmolPerUnit = 2.3,
                crGramPerUnit = 10.0,
                insulinProfileId = "NOVORAPID",
                enableUamInference = true,
                enableUamBoost = false,
                learnedMultiplier = 1.0,
                userSettings = settings
            )
        )
        val boost = engine.infer(
            UamInferenceEngine.Input(
                nowTs = nowTs,
                glucose = glucose,
                therapyEvents = emptyList(),
                existingEvents = emptyList(),
                isfMmolPerUnit = 2.3,
                crGramPerUnit = 10.0,
                insulinProfileId = "NOVORAPID",
                enableUamInference = true,
                enableUamBoost = true,
                learnedMultiplier = 1.0,
                userSettings = settings
            )
        )

        val normalCarbs = normal.inferredCarbsGrams
        val boostCarbs = boost.inferredCarbsGrams
        assertThat(normalCarbs).isNotNull()
        assertThat(boostCarbs).isNotNull()
        assertThat(boostCarbs!!).isAtLeast(normalCarbs!! + settings.snackStepG)
    }

    @Test
    fun noDuplicatesWithManualCarbsNearby() {
        val nowTs = 1_760_000_000_000L
        val glucose = mildlyRisingGlucose(nowTs)
        val manualCarbs = TherapyEvent(
            ts = nowTs - 20 * 60_000L,
            type = "carbs",
            payload = mapOf("carbs" to "20", "notes" to "manual entry")
        )

        val out = engine.infer(
            UamInferenceEngine.Input(
                nowTs = nowTs,
                glucose = glucose,
                therapyEvents = listOf(manualCarbs),
                existingEvents = emptyList(),
                isfMmolPerUnit = 2.3,
                crGramPerUnit = 10.0,
                insulinProfileId = "NOVORAPID",
                enableUamInference = true,
                enableUamBoost = false,
                learnedMultiplier = 1.0,
                userSettings = UamUserSettings(
                    disableUamWhenManualCobActive = true,
                    manualCobThresholdG = 5.0,
                    disableUamIfManualCarbsNearby = true,
                    manualMergeWindowMinutes = 45,
                    gAbsThreshold_Normal = 1.0,
                    mOfN_Normal = 1 to 1
                )
            )
        )

        assertThat(out.createdNewEvent).isFalse()
        assertThat(out.activeEvent).isNull()
        assertThat(out.events).isEmpty()
    }

    private fun mildlyRisingGlucose(nowTs: Long): List<GlucosePoint> {
        val start = nowTs - 120 * 60_000L
        return (0..120).map { index ->
            val ts = start + index * 60_000L
            val mmol = if (index < 80) {
                6.0
            } else {
                6.0 + (index - 80) * 0.05
            }
            GlucosePoint(ts = ts, valueMmol = mmol, source = "test", quality = DataQuality.OK)
        }
    }
}
