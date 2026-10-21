package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.nutrition.CarbohydrateBasis
import io.aaps.copilot.domain.nutrition.EnergyUnit
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import org.junit.Test

class MealPhotoEstimateParserTest {
    @Test
    fun parsesBoundedFoodEstimateAndRequiresExplicitMassSelection() {
        val result = MealPhotoEstimateParser.parse(validJson())
        assertThat(result).isInstanceOf(MealPhotoParseResult.Valid::class.java)
        val estimate = (result as MealPhotoParseResult.Valid).value

        assertThat(estimate.suggestedProfile).isEqualTo(MealAbsorptionProfile.MIXED)
        assertThat(estimate.suggestedDurationMinutes).isEqualTo(120)
        assertThat(estimate.ingredients.single().massGrams.minimum).isEqualTo(80.0)
        assertThat(estimate.ingredients.single().massGrams.maximum).isEqualTo(120.0)
        assertThat(estimate.toOriginalEstimate(mapOf("rice" to 100.0))!!.ingredients.single()
            .facts?.carbohydrateBasis).isEqualTo(CarbohydrateBasis.TOTAL)
        assertThat(estimate.toOriginalEstimate(emptyMap())).isNull()
        assertThat(estimate.toOriginalEstimate(mapOf("rice" to 200.0))).isNull()
    }

    @Test
    fun rejectsUnknownDuplicateAndTherapyFields() {
        val unknown = validJson().replace("\"mealName\"", "\"dose\": 2, \"mealName\"")
        val duplicate = validJson().replace("\"mealName\"", "\"mealName\": \"x\", \"mealName\"")
        val command = validJson().replace(
            "\"suggestedProfile\"",
            "\"target\": 4.1, \"suggestedProfile\""
        )

        assertThat((MealPhotoEstimateParser.parse(unknown) as MealPhotoParseResult.Invalid).reason)
            .isEqualTo(MealPhotoParseResult.Invalid.Reason.UNKNOWN_FIELD)
        assertThat((MealPhotoEstimateParser.parse(duplicate) as MealPhotoParseResult.Invalid).reason)
            .isEqualTo(MealPhotoParseResult.Invalid.Reason.DUPLICATE_FIELD)
        assertThat((MealPhotoEstimateParser.parse(command) as MealPhotoParseResult.Invalid).reason)
            .isEqualTo(MealPhotoParseResult.Invalid.Reason.UNKNOWN_FIELD)
    }

    @Test
    fun rejectsInvalidProfileDurationAndEnergyUnit() {
        val badDuration = validJson().replace("120", "30")
        val badUnit = validJson().replace("\"unit\": \"KJ\"", "\"unit\": \"watts\"")
        val unknownBasis = validJson().replace("\"TOTAL\"", "\"UNKNOWN\"")

        assertThat(MealPhotoEstimateParser.parse(badDuration))
            .isInstanceOf(MealPhotoParseResult.Invalid::class.java)
        assertThat(MealPhotoEstimateParser.parse(badUnit))
            .isInstanceOf(MealPhotoParseResult.Invalid::class.java)
        val unknownResult = MealPhotoEstimateParser.parse(unknownBasis)
        assertThat(unknownResult).isInstanceOf(MealPhotoParseResult.Valid::class.java)
        assertThat((unknownResult as MealPhotoParseResult.Valid).value.ingredients.single()
            .carbohydrateBasis).isEqualTo(CarbohydrateBasis.UNKNOWN)
    }

    @Test
    fun rejectsOversizedAndOverpopulatedResponses() {
        val oversized = " ".repeat(MealPhotoEstimateParser.MAX_JSON_CHARS + 1)
        val tooMany = validJson().replace(
            ingredientJson("rice"),
            (0..20).joinToString(",") { ingredientJson("extra-$it") }
        )

        assertThat((MealPhotoEstimateParser.parse(oversized) as MealPhotoParseResult.Invalid).reason)
            .isEqualTo(MealPhotoParseResult.Invalid.Reason.TOO_LARGE)
        assertThat(MealPhotoEstimateParser.parse(tooMany))
            .isInstanceOf(MealPhotoParseResult.Invalid::class.java)
    }

    @Test
    fun rejectsMassRangesThatCannotBeReviewedByTheNutritionCalculator() {
        listOf("{\"min\": 0, \"max\": 0}", "{\"min\": 0, \"max\": 0.05}").forEach { mass ->
            val json = validJson().replace("{\"min\": 80, \"max\": 120}", mass)
            assertThat(MealPhotoEstimateParser.parse(json)).isEqualTo(
                MealPhotoParseResult.Invalid(MealPhotoParseResult.Invalid.Reason.INVALID_VALUE)
            )
        }
    }

    @Test
    fun rejectsImpossiblePer100GramNutrientsAndCombinedPhysicalMass() {
        val excessiveNutrient = validJson().replace("{\"min\": 28, \"max\": 34}",
            "{\"min\": 28, \"max\": 102}")
        val excessiveCombinedMass = validJson().replace("{\"min\": 2, \"max\": 4}",
            "{\"min\": 2, \"max\": 70}")
        val excessiveSugar = validJson().replace("{\"min\": 0, \"max\": 1}",
            "{\"min\": 0, \"max\": 102}")
        listOf(excessiveNutrient, excessiveCombinedMass, excessiveSugar).forEach { json ->
            assertThat(MealPhotoEstimateParser.parse(json)).isEqualTo(
                MealPhotoParseResult.Invalid(MealPhotoParseResult.Invalid.Reason.INVALID_VALUE)
            )
        }
    }

    @Test
    fun usesExistingCarbohydrateBasisAndRoundingRulesWithoutDoubleCountingSubfractions() {
        val total = validJson().replace("{\"min\": 28, \"max\": 34}",
            "{\"min\": 90, \"max\": 90}")
            .replace("\"fiber\": null", "\"fiber\": {\"min\": 10, \"max\": 10}")
            .replace("\"polyols\": null", "\"polyols\": {\"min\": 20, \"max\": 20}")
        val available = total.replace("\"TOTAL\"", "\"AVAILABLE\"")
        assertThat(MealPhotoEstimateParser.parse(total)).isInstanceOf(MealPhotoParseResult.Valid::class.java)
        assertThat(MealPhotoEstimateParser.parse(total.replace("\"TOTAL\"", "\"UNKNOWN\"")))
            .isInstanceOf(MealPhotoParseResult.Valid::class.java)
        assertThat(MealPhotoEstimateParser.parse(available)).isEqualTo(
            MealPhotoParseResult.Invalid(MealPhotoParseResult.Invalid.Reason.INVALID_VALUE)
        )
        val roundingLimit = validJson().replace("{\"min\": 28, \"max\": 34}",
            "{\"min\": 95, \"max\": 95}")
        assertThat(MealPhotoEstimateParser.parse(roundingLimit))
            .isInstanceOf(MealPhotoParseResult.Valid::class.java)
        assertThat(MealPhotoEstimateParser.parse(roundingLimit.replace("95", "95.01")))
            .isInstanceOf(MealPhotoParseResult.Invalid::class.java)
        val minimumMass = validJson().replace("{\"min\": 80, \"max\": 120}",
            "{\"min\": 0.1, \"max\": 0.1}")
        assertThat(MealPhotoEstimateParser.parse(minimumMass))
            .isInstanceOf(MealPhotoParseResult.Valid::class.java)
        val includesZero = validJson().replace("{\"min\": 80, \"max\": 120}",
            "{\"min\": 0, \"max\": 120}")
        assertThat(MealPhotoEstimateParser.parse(includesZero))
            .isInstanceOf(MealPhotoParseResult.Valid::class.java)
    }

    private fun validJson(): String = """
        {
          "schemaVersion": 1,
          "estimateId": "estimate-1",
          "mealName": "Rice bowl",
          "ingredients": [${ingredientJson("rice")}],
          "suggestedProfile": "MIXED",
          "suggestedDurationMinutes": 120
        }
    """.trimIndent()

    private fun ingredientJson(id: String): String = """
        {
          "id": "$id",
          "name": "Rice",
          "massGrams": {"min": 80, "max": 120},
          "preparationState": "COOKED",
          "carbohydrateBasis": "TOTAL",
          "carbohydrates": {"min": 28, "max": 34},
          "protein": {"min": 2, "max": 4},
          "fat": {"min": 0, "max": 2},
          "fiber": null,
          "sugar": {"min": 0, "max": 1},
          "polyols": null,
          "energy": {"min": 540, "max": 650, "unit": "KJ"}
        }
    """.trimIndent()
}
