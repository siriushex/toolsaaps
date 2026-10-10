package io.aaps.copilot.data.repository

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.aaps.copilot.domain.nutrition.CarbohydrateBasis
import io.aaps.copilot.domain.nutrition.CatalogReference
import io.aaps.copilot.domain.nutrition.DeclaredEnergy
import io.aaps.copilot.domain.nutrition.EnergyUnit
import io.aaps.copilot.domain.nutrition.IngredientAmount
import io.aaps.copilot.domain.nutrition.KnownNutrient
import io.aaps.copilot.domain.nutrition.MealIngredient
import io.aaps.copilot.domain.nutrition.MealNutritionBounds
import io.aaps.copilot.domain.nutrition.NutrientFacts
import io.aaps.copilot.domain.nutrition.NutrientReferenceType
import io.aaps.copilot.domain.nutrition.NutrientSource
import io.aaps.copilot.domain.nutrition.NutritionRange
import io.aaps.copilot.domain.nutrition.NutritionReference
import io.aaps.copilot.domain.nutrition.OriginalAiMealEstimate
import io.aaps.copilot.domain.nutrition.PreparationState
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.profile.MealAbsorptionSelection
import java.io.StringReader
import java.nio.charset.StandardCharsets

sealed interface MealPhotoParseResult {
    data class Valid(val value: MealPhotoNutritionEstimate) : MealPhotoParseResult

    data class Invalid(val reason: Reason) : MealPhotoParseResult {
        enum class Reason {
            EMPTY,
            TOO_LARGE,
            MALFORMED,
            UNKNOWN_FIELD,
            DUPLICATE_FIELD,
            INVALID_VALUE,
            TOO_MANY_INGREDIENTS
        }
    }
}

data class MealPhotoNutritionEstimate(
    val schemaVersion: Int,
    val estimateId: String,
    val mealName: String,
    val ingredients: List<MealPhotoIngredientEstimate>,
    val suggestedProfile: MealAbsorptionProfile?,
    val suggestedDurationMinutes: Int?
) {
    fun absorptionSelection(): MealAbsorptionSelection? = suggestedProfile?.let {
        MealAbsorptionSelection(it, suggestedDurationMinutes)
    }

    /**
     * The editor must provide one mass per ingredient. A photo estimate never
     * becomes a domain ingredient by silently choosing the midpoint.
     */
    fun toOriginalEstimate(confirmedMasses: Map<String, Double>): OriginalAiMealEstimate? {
        if (confirmedMasses.keys != ingredients.map { it.id }.toSet()) return null
        val converted = ingredients.map { estimate ->
            val grams = confirmedMasses[estimate.id] ?: return null
            estimate.toMealIngredient(grams) ?: return null
        }
        return OriginalAiMealEstimate(estimateId, mealName, converted)
    }
}

data class MealPhotoIngredientEstimate(
    val id: String,
    val name: String,
    val massGrams: NutritionRange,
    val preparationState: PreparationState,
    val carbohydrateBasis: CarbohydrateBasis,
    val carbohydrates: NutritionRange?,
    val protein: NutritionRange?,
    val fat: NutritionRange?,
    val fiber: NutritionRange?,
    val sugar: NutritionRange?,
    val polyols: NutritionRange?,
    val energy: PhotoEnergyEstimate?
) {
    fun toMealIngredient(confirmedMassGrams: Double): MealIngredient? {
        if (!confirmedMassGrams.isFinite() || confirmedMassGrams !in massGrams.minimum..massGrams.maximum) {
            return null
        }
        return MealIngredient(
            id = id,
            name = name,
            amount = IngredientAmount(confirmedMassGrams, 1.0, preparationState),
            facts = NutrientFacts(
                reference = NutritionReference(NutrientReferenceType.PER_100G, 100.0),
                preparationState = preparationState,
                carbohydrateBasis = carbohydrateBasis,
                carbohydrates = carbohydrates?.aiNutrient(),
                protein = protein?.aiNutrient(),
                fat = fat?.aiNutrient(),
                fiber = fiber?.aiNutrient(),
                sugar = sugar?.aiNutrient(),
                polyols = polyols?.aiNutrient(),
                energy = energy?.toDeclaredEnergy()
            )
        )
    }

    private fun NutritionRange.aiNutrient() = KnownNutrient(this, NutrientSource.AI_ESTIMATE)
}

data class PhotoEnergyEstimate(
    val amount: NutritionRange,
    val unit: EnergyUnit
) {
    fun toDeclaredEnergy() = DeclaredEnergy(amount, unit, NutrientSource.AI_ESTIMATE)
}

/** Strict, bounded parser for food-only AI output. It cannot parse commands. */
object MealPhotoEstimateParser {
    const val MAX_JSON_CHARS = 64 * 1024
    const val MAX_JSON_BYTES = 64 * 1024
    private const val MAX_TEXT_LENGTH = 120
    private const val MAX_ID_LENGTH = 128
    private const val MAX_MASS_GRAMS = 5_000.0
    private const val MAX_NUTRIENT_GRAMS = 5_000.0
    private const val MAX_ENERGY = 100_000.0

    private val topLevelFields = setOf(
        "schemaVersion",
        "estimateId",
        "mealName",
        "ingredients",
        "suggestedProfile",
        "suggestedDurationMinutes"
    )
    private val ingredientFields = setOf(
        "id",
        "name",
        "massGrams",
        "preparationState",
        "carbohydrateBasis",
        "carbohydrates",
        "protein",
        "fat",
        "fiber",
        "sugar",
        "polyols",
        "energy"
    )
    private val rangeFields = setOf("min", "max")
    private val energyFields = setOf("min", "max", "unit")

    fun parse(json: String): MealPhotoParseResult {
        if (json.length > MAX_JSON_CHARS || json.toByteArray(StandardCharsets.UTF_8).size > MAX_JSON_BYTES) {
            return MealPhotoParseResult.Invalid(MealPhotoParseResult.Invalid.Reason.TOO_LARGE)
        }
        if (json.isBlank()) return MealPhotoParseResult.Invalid(MealPhotoParseResult.Invalid.Reason.EMPTY)
        return try {
            JsonReader(StringReader(json)).use { reader ->
                reader.strictness = Strictness.STRICT
                val value = readTopLevel(reader)
                if (reader.peek() != JsonToken.END_DOCUMENT) throw ParseException(Reason.MALFORMED)
                MealPhotoParseResult.Valid(value)
            }
        } catch (exception: ParseException) {
            MealPhotoParseResult.Invalid(exception.reason)
        } catch (_: Exception) {
            MealPhotoParseResult.Invalid(MealPhotoParseResult.Invalid.Reason.MALFORMED)
        }
    }

    private fun readTopLevel(reader: JsonReader): MealPhotoNutritionEstimate {
        reader.beginObject()
        val seen = mutableSetOf<String>()
        var schemaVersion: Int? = null
        var estimateId: String? = null
        var mealName: String? = null
        var ingredients: List<MealPhotoIngredientEstimate>? = null
        var suggestedProfile: MealAbsorptionProfile? = null
        var duration: Int? = null
        var profileWasPresent = false
        var durationWasPresent = false
        while (reader.hasNext()) {
            val name = nextName(reader, seen, topLevelFields)
            when (name) {
                "schemaVersion" -> schemaVersion = nextInt(reader)
                "estimateId" -> estimateId = boundedString(reader, MAX_ID_LENGTH)
                "mealName" -> mealName = boundedString(reader, MAX_TEXT_LENGTH)
                "ingredients" -> {
                    ingredients = readIngredients(reader)
                }
                "suggestedProfile" -> {
                    suggestedProfile = nextNullableEnum<MealAbsorptionProfile>(reader)
                    profileWasPresent = true
                }
                "suggestedDurationMinutes" -> {
                    duration = nextNullableInt(reader)
                    durationWasPresent = true
                }
            }
        }
        reader.endObject()
        if (seen != topLevelFields || schemaVersion != 1 || estimateId == null || mealName == null ||
            ingredients == null || ingredients!!.isEmpty() || !profileWasPresent || !durationWasPresent
        ) {
            throw ParseException(Reason.INVALID_VALUE)
        }
        if (suggestedProfile == null && duration != null) throw ParseException(Reason.INVALID_VALUE)
        if (suggestedProfile != null && duration != null && duration !in durationRange(suggestedProfile!!)) {
            throw ParseException(Reason.INVALID_VALUE)
        }
        return MealPhotoNutritionEstimate(
            schemaVersion = schemaVersion!!,
            estimateId = estimateId!!,
            mealName = mealName!!,
            ingredients = ingredients!!,
            suggestedProfile = suggestedProfile,
            suggestedDurationMinutes = duration
        )
    }

    private fun readIngredients(reader: JsonReader): List<MealPhotoIngredientEstimate> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) throw ParseException(Reason.INVALID_VALUE)
        reader.beginArray()
        val values = mutableListOf<MealPhotoIngredientEstimate>()
        val ids = mutableSetOf<String>()
        while (reader.hasNext()) {
            if (values.size >= MealNutritionBounds.MAX_INGREDIENTS) throw ParseException(Reason.TOO_MANY_INGREDIENTS)
            val value = readIngredient(reader)
            if (!ids.add(value.id)) throw ParseException(Reason.INVALID_VALUE)
            values += value
        }
        reader.endArray()
        return values
    }

    private fun readIngredient(reader: JsonReader): MealPhotoIngredientEstimate {
        reader.beginObject()
        val seen = mutableSetOf<String>()
        var id: String? = null
        var name: String? = null
        var mass: NutritionRange? = null
        var preparation: PreparationState? = null
        var basis: CarbohydrateBasis? = null
        var carbohydrates: NutritionRange? = null
        var protein: NutritionRange? = null
        var fat: NutritionRange? = null
        var fiber: NutritionRange? = null
        var sugar: NutritionRange? = null
        var polyols: NutritionRange? = null
        var energy: PhotoEnergyEstimate? = null
        while (reader.hasNext()) {
            when (val field = nextName(reader, seen, ingredientFields)) {
                "id" -> id = boundedString(reader, MAX_ID_LENGTH)
                "name" -> name = boundedString(reader, MAX_TEXT_LENGTH)
                "massGrams" -> mass = readRange(reader, MAX_MASS_GRAMS)
                "preparationState" -> preparation = nextEnum<PreparationState>(reader)
                "carbohydrateBasis" -> basis = nextEnum<CarbohydrateBasis>(reader)
                "carbohydrates" -> carbohydrates = readNullableRange(reader, MAX_NUTRIENT_GRAMS)
                "protein" -> protein = readNullableRange(reader, MAX_NUTRIENT_GRAMS)
                "fat" -> fat = readNullableRange(reader, MAX_NUTRIENT_GRAMS)
                "fiber" -> fiber = readNullableRange(reader, MAX_NUTRIENT_GRAMS)
                "sugar" -> sugar = readNullableRange(reader, MAX_NUTRIENT_GRAMS)
                "polyols" -> polyols = readNullableRange(reader, MAX_NUTRIENT_GRAMS)
                "energy" -> energy = readNullableEnergy(reader)
                else -> throw ParseException(Reason.UNKNOWN_FIELD)
            }
        }
        reader.endObject()
        val resolvedId = id ?: throw ParseException(Reason.INVALID_VALUE)
        val resolvedName = name ?: throw ParseException(Reason.INVALID_VALUE)
        val resolvedMass = mass ?: throw ParseException(Reason.INVALID_VALUE)
        val resolvedPreparation = preparation ?: throw ParseException(Reason.INVALID_VALUE)
        val resolvedBasis = basis ?: throw ParseException(Reason.INVALID_VALUE)
        if (seen.size < 5) throw ParseException(Reason.INVALID_VALUE)
        return MealPhotoIngredientEstimate(
            resolvedId,
            resolvedName,
            resolvedMass,
            resolvedPreparation,
            resolvedBasis,
            carbohydrates,
            protein,
            fat,
            fiber,
            sugar,
            polyols,
            energy
        )
    }

    private fun readNullableRange(reader: JsonReader, maximum: Double): NutritionRange? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        return readRange(reader, maximum)
    }

    private fun readRange(reader: JsonReader, maximum: Double): NutritionRange {
        reader.beginObject()
        val seen = mutableSetOf<String>()
        var minimum: Double? = null
        var maximumValue: Double? = null
        while (reader.hasNext()) {
            when (nextName(reader, seen, rangeFields)) {
                "min" -> minimum = reader.nextDouble()
                "max" -> maximumValue = reader.nextDouble()
            }
        }
        reader.endObject()
        if (seen != rangeFields || minimum == null || maximumValue == null ||
            !minimum!!.isFinite() || !maximumValue!!.isFinite() || minimum!! < 0.0 ||
            maximumValue!! < minimum!! || maximumValue!! > maximum
        ) throw ParseException(Reason.INVALID_VALUE)
        return NutritionRange(minimum!!, maximumValue!!)
    }

    private fun readNullableEnergy(reader: JsonReader): PhotoEnergyEstimate? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        reader.beginObject()
        val seen = mutableSetOf<String>()
        var minimum: Double? = null
        var maximum: Double? = null
        var unit: EnergyUnit? = null
        while (reader.hasNext()) {
            when (nextName(reader, seen, energyFields)) {
                "min" -> minimum = reader.nextDouble()
                "max" -> maximum = reader.nextDouble()
                "unit" -> unit = nextEnum<EnergyUnit>(reader)
            }
        }
        reader.endObject()
        if (seen != energyFields || minimum == null || maximum == null || unit == null ||
            !minimum!!.isFinite() || !maximum!!.isFinite() || minimum!! < 0.0 ||
            maximum!! < minimum!! || maximum!! > MAX_ENERGY
        ) {
            throw ParseException(Reason.INVALID_VALUE)
        }
        return PhotoEnergyEstimate(NutritionRange(minimum!!, maximum!!), unit!!)
    }

    private fun nextName(reader: JsonReader, seen: MutableSet<String>, allowed: Set<String>): String {
        val name = reader.nextName()
        if (name !in allowed) throw ParseException(Reason.UNKNOWN_FIELD)
        if (!seen.add(name)) throw ParseException(Reason.DUPLICATE_FIELD)
        return name
    }

    private fun boundedString(reader: JsonReader, maxLength: Int): String {
        if (reader.peek() != JsonToken.STRING) throw ParseException(Reason.INVALID_VALUE)
        return reader.nextString().takeIf { it.isNotBlank() && it.length <= maxLength }
            ?: throw ParseException(Reason.INVALID_VALUE)
    }

    private fun nextInt(reader: JsonReader): Int {
        if (reader.peek() != JsonToken.NUMBER) throw ParseException(Reason.INVALID_VALUE)
        return reader.nextString().toIntOrNull() ?: throw ParseException(Reason.INVALID_VALUE)
    }

    private fun nextNullableInt(reader: JsonReader): Int? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        return nextInt(reader)
    }

    private inline fun <reified T : Enum<T>> nextEnum(reader: JsonReader): T {
        if (reader.peek() != JsonToken.STRING) throw ParseException(Reason.INVALID_VALUE)
        val value = reader.nextString()
        return enumValues<T>().firstOrNull { it.name == value }
            ?: throw ParseException(Reason.INVALID_VALUE)
    }

    private inline fun <reified T : Enum<T>> nextNullableEnum(reader: JsonReader): T? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        return nextEnum<T>(reader)
    }

    private fun durationRange(profile: MealAbsorptionProfile): IntRange = when (profile) {
        MealAbsorptionProfile.FAST -> 30..60
        MealAbsorptionProfile.MIXED -> 60..180
        MealAbsorptionProfile.FAT_PROTEIN -> 180..360
    }

    private object Reason {
        val MALFORMED = MealPhotoParseResult.Invalid.Reason.MALFORMED
        val UNKNOWN_FIELD = MealPhotoParseResult.Invalid.Reason.UNKNOWN_FIELD
        val DUPLICATE_FIELD = MealPhotoParseResult.Invalid.Reason.DUPLICATE_FIELD
        val INVALID_VALUE = MealPhotoParseResult.Invalid.Reason.INVALID_VALUE
        val TOO_MANY_INGREDIENTS = MealPhotoParseResult.Invalid.Reason.TOO_MANY_INGREDIENTS
    }

    private class ParseException(val reason: MealPhotoParseResult.Invalid.Reason) : Exception()
}
