package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import kotlin.math.abs

enum class CarbAbsorptionType {
    FAST,
    MEDIUM,
    PROTEIN_SLOW
}

data class FoodCarbCatalogEntry(
    val name: String,
    val type: CarbAbsorptionType,
    val aliases: List<String> = emptyList()
)

data class CarbClassification(
    val type: CarbAbsorptionType,
    val reason: String
)

object CarbAbsorptionProfiles {

    private data class CurvePoint(val minute: Double, val cumulative: Double)

    private val fastCurve = listOf(
        CurvePoint(0.0, 0.0),
        CurvePoint(10.0, 0.20),
        CurvePoint(30.0, 0.50),
        CurvePoint(60.0, 0.80),
        CurvePoint(90.0, 0.95),
        CurvePoint(150.0, 1.0)
    )

    private val mediumCurve = listOf(
        CurvePoint(0.0, 0.0),
        CurvePoint(15.0, 0.08),
        CurvePoint(45.0, 0.28),
        CurvePoint(90.0, 0.55),
        CurvePoint(150.0, 0.80),
        CurvePoint(240.0, 0.95),
        CurvePoint(360.0, 1.0)
    )

    private val proteinSlowCurve = listOf(
        CurvePoint(0.0, 0.0),
        CurvePoint(30.0, 0.03),
        CurvePoint(90.0, 0.12),
        CurvePoint(150.0, 0.30),
        CurvePoint(240.0, 0.55),
        CurvePoint(360.0, 0.80),
        CurvePoint(480.0, 0.95),
        CurvePoint(600.0, 1.0)
    )

    val fastFoodCatalog: List<FoodCarbCatalogEntry> by lazy {
        FAST_FOODS.map {
        FoodCarbCatalogEntry(name = it, type = CarbAbsorptionType.FAST)
        }
    }

    val mediumFoodCatalog: List<FoodCarbCatalogEntry> by lazy {
        MEDIUM_FOODS.map {
        FoodCarbCatalogEntry(name = it, type = CarbAbsorptionType.MEDIUM)
        }
    }

    val proteinFoodCatalog: List<FoodCarbCatalogEntry> by lazy {
        PROTEIN_FOODS.map {
        FoodCarbCatalogEntry(name = it, type = CarbAbsorptionType.PROTEIN_SLOW)
        }
    }

    val allCatalog: List<FoodCarbCatalogEntry> by lazy {
        fastFoodCatalog + mediumFoodCatalog + proteinFoodCatalog
    }

    fun cumulative(type: CarbAbsorptionType, ageMinutes: Double): Double {
        val curve = when (type) {
            CarbAbsorptionType.FAST -> fastCurve
            CarbAbsorptionType.MEDIUM -> mediumCurve
            CarbAbsorptionType.PROTEIN_SLOW -> proteinSlowCurve
        }
        return cumulativeFromCurve(curve, ageMinutes)
    }

    fun classifyCarbEvent(
        event: TherapyEvent,
        glucose: List<GlucosePoint>,
        nowTs: Long
    ): CarbClassification {
        parseExplicitType(event)?.let {
            return CarbClassification(it, "explicit_payload")
        }
        classifyByCatalogText(event)?.let {
            return it
        }
        classifyByPattern(event = event, glucose = glucose, nowTs = nowTs)?.let {
            return it
        }
        return CarbClassification(CarbAbsorptionType.MEDIUM, "default_medium")
    }

    private fun parseExplicitType(event: TherapyEvent): CarbAbsorptionType? {
        val explicit = sequenceOf(
            event.payload["carbType"],
            event.payload["carb_type"],
            event.payload["absorptionType"],
            event.payload["absorption_type"],
            event.payload["mealType"],
            event.payload["meal_type"]
        ).filterNotNull().map { normalize(it) }.firstOrNull() ?: return null

        return when {
            explicit.contains("fast") || explicit.contains("quick") || explicit.contains("rapid") ->
                CarbAbsorptionType.FAST
            explicit.contains("medium") || explicit.contains("normal") || explicit.contains("mixed") ->
                CarbAbsorptionType.MEDIUM
            explicit.contains("protein") || explicit.contains("slow") || explicit.contains("fat") ->
                CarbAbsorptionType.PROTEIN_SLOW
            else -> null
        }
    }

    private fun classifyByCatalogText(event: TherapyEvent): CarbClassification? {
        val text = buildTextBlob(event)
        if (text.isBlank()) return null
        val normalizedText = normalize(text)
        val matched = allCatalog.firstOrNull { entry ->
            val tokens = listOf(entry.name) + entry.aliases + FOOD_ALIASES[entry.name].orEmpty()
            tokens.any { token ->
                val normalizedToken = normalize(token)
                normalizedToken.isNotBlank() && normalizedText.contains(normalizedToken)
            }
        } ?: return null
        return CarbClassification(matched.type, "catalog_text:${matched.name}")
    }

    private fun classifyByPattern(
        event: TherapyEvent,
        glucose: List<GlucosePoint>,
        nowTs: Long
    ): CarbClassification? {
        if (glucose.size < 4) return null
        val baseline = glucose
            .filter { it.ts <= event.ts - 2 * MINUTE_MS && it.ts >= event.ts - 25 * MINUTE_MS }
            .maxByOrNull { it.ts }
            ?: return null

        val cutoffTs = minOf(nowTs, event.ts + 3 * HOUR_MS)
        if (cutoffTs <= event.ts + 10 * MINUTE_MS) return null

        fun riseAt(targetMinutes: Long, toleranceMinutes: Long): Double? {
            val targetTs = event.ts + targetMinutes * MINUTE_MS
            val point = glucose
                .filter { it.ts in (targetTs - toleranceMinutes * MINUTE_MS)..(targetTs + toleranceMinutes * MINUTE_MS) }
                .minByOrNull { abs(it.ts - targetTs) }
                ?: return null
            return point.valueMmol - baseline.valueMmol
        }

        val rise15 = riseAt(15, 8) ?: return null
        val rise30 = riseAt(30, 10) ?: rise15
        val rise60 = riseAt(60, 12) ?: rise30
        val rise120 = riseAt(120, 15) ?: rise60

        val post = glucose.filter { it.ts in event.ts..cutoffTs }.sortedBy { it.ts }
        var delta5Max = 0.0
        post.zipWithNext().forEach { (a, b) ->
            val dtMin = (b.ts - a.ts) / 60_000.0
            if (dtMin !in 2.0..15.0) return@forEach
            val delta5 = (b.valueMmol - a.valueMmol) / (dtMin / 5.0)
            if (delta5 > delta5Max) delta5Max = delta5
        }

        return when {
            rise15 >= 0.70 || delta5Max >= 0.30 ->
                CarbClassification(CarbAbsorptionType.FAST, "pattern_fast")
            rise60 >= 1.00 && rise30 >= 0.45 ->
                CarbClassification(CarbAbsorptionType.MEDIUM, "pattern_medium")
            rise120 >= 0.70 && rise30 < 0.35 ->
                CarbClassification(CarbAbsorptionType.PROTEIN_SLOW, "pattern_protein")
            rise60 >= 0.55 ->
                CarbClassification(CarbAbsorptionType.MEDIUM, "pattern_medium_fallback")
            else ->
                CarbClassification(CarbAbsorptionType.PROTEIN_SLOW, "pattern_protein_fallback")
        }
    }

    private fun cumulativeFromCurve(curve: List<CurvePoint>, ageMinutes: Double): Double {
        if (ageMinutes <= 0.0) return 0.0
        if (ageMinutes >= curve.last().minute) return 1.0
        for (index in 1 until curve.size) {
            val left = curve[index - 1]
            val right = curve[index]
            if (ageMinutes <= right.minute) {
                val span = (right.minute - left.minute).coerceAtLeast(1e-6)
                val t = (ageMinutes - left.minute) / span
                return (left.cumulative + (right.cumulative - left.cumulative) * t).coerceIn(0.0, 1.0)
            }
        }
        return 1.0
    }

    private fun buildTextBlob(event: TherapyEvent): String {
        val textKeys = listOf(
            "food",
            "product",
            "meal",
            "dish",
            "description",
            "note",
            "notes",
            "title",
            "label",
            "comment",
            "carbSource",
            "carb_source"
        )
        val fromKeys = textKeys.mapNotNull { key -> event.payload[key] }
        val allValues = event.payload.values
        return (fromKeys + allValues).joinToString(" ")
    }

    private fun normalize(value: String): String {
        return value
            .lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^a-z0-9а-я]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS

    // 100 fast carbohydrate positions.
    private val FAST_FOODS = listOf(
        "white_sugar", "brown_sugar", "powdered_sugar", "dextrose", "glucose_tablet",
        "honey", "jam", "fruit_jam", "jelly", "marmalade",
        "hard_candy", "gummy_candy", "chewy_candy", "caramel", "lollipop",
        "marshmallow", "nougat_bar", "milk_chocolate", "candy_bar", "wafer_bar",
        "sweet_soda", "cola", "orange_soda", "lemon_soda", "energy_drink",
        "sports_drink", "sweet_tea", "sweet_coffee", "milkshake", "bubble_tea",
        "apple_juice", "orange_juice", "grape_juice", "pineapple_juice", "multi_fruit_juice",
        "banana_ripe", "grapes", "watermelon", "melon", "mango_ripe",
        "papaya", "persimmon", "dates", "raisins", "dried_figs",
        "sweet_apricot_dried", "lychee", "pineapple_sweet", "fruit_puree_sweet", "fruit_snack",
        "white_bread", "toast_white", "baguette", "burger_bun", "hotdog_bun",
        "croissant", "sweet_roll", "cinnamon_roll", "donut", "muffin_sweet",
        "sponge_cake", "waffle_syrup", "pancake_syrup", "sweet_biscuit", "cookies_sugar",
        "cornflakes", "rice_flakes", "rice_cakes", "pretzels", "salt_crackers",
        "instant_noodles", "rice_noodles", "white_rice_overcooked", "mashed_potato", "boiled_potato",
        "baked_potato", "french_fries", "potato_flakes", "potato_puree", "sweet_popcorn",
        "ice_cream_sweet", "frozen_yogurt_sweet", "sweetened_yogurt", "condensed_milk", "chocolate_spread",
        "granola_bar_sweet", "cereal_bar_sugar", "carb_gel", "glucose_gel", "isotonic_gel",
        "maple_syrup", "agave_syrup", "molasses", "ketchup_sweet", "sweet_corn_canned",
        "semolina_sweet", "rice_porridge_sweet", "pumpkin_porridge_sweet", "sweet_compote", "kvass_sweet"
    )

    // 100 medium carbohydrate positions.
    private val MEDIUM_FOODS = listOf(
        "whole_wheat_bread", "rye_bread", "sourdough_bread", "bran_bread", "grain_bread",
        "buckwheat", "oatmeal", "steel_cut_oats", "barley", "pearl_barley",
        "brown_rice", "basmati_rice", "jasmine_rice", "wild_rice", "quinoa",
        "bulgur", "couscous", "millet", "spelt", "amaranth",
        "whole_wheat_pasta", "durum_pasta", "al_dente_pasta", "udon_noodles", "soba_noodles",
        "lentils_brown", "lentils_green", "lentils_red", "chickpeas", "black_beans",
        "kidney_beans", "pinto_beans", "white_beans", "peas_green", "split_peas",
        "boiled_corn", "corn_on_cob", "sweet_potato", "pumpkin", "beetroot",
        "carrot_boiled", "parsnip", "boiled_turnip", "yam", "cassava_boiled",
        "apple_fresh", "pear_fresh", "orange_fresh", "mandarin", "kiwi",
        "plum", "peach", "nectarine", "cherry", "strawberry",
        "blueberry", "raspberry", "blackberry", "currant", "pomegranate",
        "grapefruit", "apricot", "fig_fresh", "pineapple_fresh", "mango_fresh",
        "beet_salad", "carrot_salad", "vinaigrette", "hummus", "falafel_baked",
        "granola_unsweetened", "muesli", "oat_bar", "protein_bar_with_oats", "rice_milk",
        "soy_milk_unsweetened", "kefir_low_fat", "yogurt_plain", "cottage_cheese_with_fruit", "borsch",
        "bean_soup", "lentil_soup", "pea_soup", "vegetable_stew", "minestrone",
        "sushi_rice_roll", "pizza_thin_crust", "lasagna_moderate", "dumplings_boiled", "pelmeni_boiled",
        "vareniki_potato", "pita_bread", "lavash", "tortilla_corn", "tortilla_wheat",
        "boiled_noodles", "macaroni_al_dente", "risotto", "paella", "tabbouleh"
    )

    // 50 protein/slow positions.
    private val PROTEIN_FOODS = listOf(
        "chicken_breast", "turkey_breast", "lean_beef", "veal", "pork_lean",
        "lamb_lean", "salmon", "tuna", "cod", "hake",
        "sardines", "mackerel", "shrimp", "mussels", "octopus",
        "eggs_boiled", "egg_omelet", "egg_whites", "cottage_cheese", "greek_yogurt_plain",
        "quark", "ricotta", "hard_cheese", "mozzarella", "tofu",
        "tempeh", "seitan", "soy_protein", "casein_shake", "whey_shake",
        "beef_jerky", "ham_low_fat", "roast_beef", "chicken_thigh_no_skin", "duck_breast",
        "liver_beef", "kidney_beef", "heart_beef", "bone_broth", "fish_soup_clear",
        "meat_stew_low_carb", "grilled_kebab", "steak", "meatballs_low_carb", "cabbage_rolls_meat",
        "tvorog_5pct", "tvorog_9pct", "protein_pancake_low_carb", "protein_pudding", "edamame"
    )

    private val FOOD_ALIASES = mapOf(
        "white_sugar" to listOf("sugar", "sahar"),
        "honey" to listOf("med", "honey"),
        "banana_ripe" to listOf("banana", "banan"),
        "boiled_potato" to listOf("potato", "kartoshka"),
        "white_bread" to listOf("bread", "baton"),
        "buckwheat" to listOf("grechka", "buckwheat"),
        "oatmeal" to listOf("ovsyanka", "oatmeal"),
        "whole_wheat_pasta" to listOf("pasta", "makarony"),
        "chicken_breast" to listOf("chicken", "kurica"),
        "cottage_cheese" to listOf("tvorog", "cottage cheese"),
        "rice_porridge_sweet" to listOf("risovaya kasha", "rice porridge"),
        "semolina_sweet" to listOf("mannaya kasha", "semolina")
    )
}
