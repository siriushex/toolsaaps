package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.entity.MealEnergyOverrideEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MealEnergyOverrideRepositoryTest {

    @Test
    fun savesCaloriesOnlyForTrustedConcreteMealReference() = runTest {
        val stored = mutableListOf<MealEnergyOverrideEntity>()
        val repository = MealEnergyOverrideRepository(
            source = MealEnergyOverrideDataSource(
                therapyById = { id ->
                    TherapyEventEntity(
                        id = id,
                        timestamp = 1_800_000_000_000L,
                        type = "carbs",
                        payloadJson =
                            """{"aapsCarbId":11,"aapsRevisionId":"revision-11","aapsCarbAmount":30,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
                    )
                },
                save = stored::add
            ),
            gson = Gson(),
            clock = { 1_800_000_060_000L }
        )

        val result = repository.saveForMealEvent("therapy-row-1", 540.0)

        assertThat(result).isEqualTo(
            ManualMealEnergySaveResult.Saved(
                canonicalTherapyIdentity = "11",
                therapyRevisionHash = "revision-11"
            )
        )
        assertThat(stored).containsExactly(
            MealEnergyOverrideEntity(
                canonicalTherapyIdentity = "11",
                therapyRevisionHash = "revision-11",
                caloriesKcal = 540.0,
                updatedAtMs = 1_800_000_060_000L
            )
        )
    }

    @Test
    fun rejectsSyntheticUamAndDoesNotPersistCalories() = runTest {
        val stored = mutableListOf<MealEnergyOverrideEntity>()
        val repository = MealEnergyOverrideRepository(
            source = MealEnergyOverrideDataSource(
                therapyById = { id ->
                    TherapyEventEntity(
                        id = id,
                        timestamp = 1_800_000_000_000L,
                        type = "carbs",
                        payloadJson =
                            """{"aapsCarbId":12,"aapsRevisionId":"revision-12","aapsCarbAmount":15,"aapsCarbIsValid":true,"aapsCarbClassification":"UAM_SYNTHETIC","aapsCarbSynthetic":true,"aapsCarbSuperseded":false}"""
                    )
                },
                save = stored::add
            ),
            gson = Gson()
        )

        val result = repository.saveForMealEvent("uam-row", 240.0)

        assertThat(result).isEqualTo(ManualMealEnergySaveResult.UNTRUSTED_MEAL)
        assertThat(stored).isEmpty()
    }
}
