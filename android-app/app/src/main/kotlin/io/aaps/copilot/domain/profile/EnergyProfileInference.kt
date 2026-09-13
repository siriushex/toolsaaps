package io.aaps.copilot.domain.profile

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Immutable, already-sanitised evidence for the nightly energy-profile snapshot.
 * This model is deliberately diagnostic: it has no dependency on prediction or
 * therapy writers and it never turns evidence into a treatment command.
 */
data class EnergyInferenceMeal(
    val occurredAtMs: Long,
    val canonicalIdentity: String,
    val revision: String,
    val carbsG: Double,
    val absorptionMinutes: Int,
    val postMealGlucoseCovered: Boolean,
    val therapyCovered: Boolean,
    val overlapsAnotherMeal: Boolean,
    val syntheticUam: Boolean = false,
    val staleOrSensorBlocked: Boolean = false,
    val superseded: Boolean = false
)

/**
 * Reserved for a future persisted reconciliation schema. Task 9 deliberately
 * ignores these rows because current telemetry cannot prove interval provenance.
 */
data class EnergyInferenceActivity(
    val observedAtMs: Long,
    val source: String,
    val quality: String,
    val activityRatio: Double,
    val reconciled: Boolean
)

data class EnergyProfileInferenceInput(
    val completedLocalDate: LocalDate,
    val zoneId: ZoneId,
    val settings: EnergyProfileSettings,
    val meals: List<EnergyInferenceMeal>,
    val activities: List<EnergyInferenceActivity>
)

data class EnergyProfileInferenceValue<T>(
    val value: T?,
    val source: ResolutionSource
)

data class EnergyInferenceResult(
    val tier: EvidenceTier,
    val qualityDays: Int,
    val food: EnergyProfileInferenceValue<MealAbsorptionProfile>,
    val foodDurationMinutes: Int?,
    val activity: EnergyProfileInferenceValue<ActivityProfile>,
    val confidence: Double,
    val replayPassed: Boolean,
    val sourceHashSha256: String
) {
    val foodProfile: MealAbsorptionProfile?
        get() = food.value
    val activityProfile: ActivityProfile?
        get() = activity.value

    /** There is no canonical horizon replay source in Task 9, so this is false. */
    val forecastInfluenceAllowed: Boolean
        get() = tier >= EvidenceTier.STABLE && replayPassed

    /** Inference must never unlock target management without high-quality replay evidence. */
    val targetManagerInfluenceAllowed: Boolean
        get() = tier == EvidenceTier.HIGH_CONFIDENCE && replayPassed
}

/**
 * Deterministic daily inference over at most fourteen *completed local days*.
 * Inputs that cannot be independently trusted are removed before both hashing
 * and aggregation, so changing an ignored UAM/stale/superseded row is harmless.
 */
class EnergyProfileInference {

    fun infer(input: EnergyProfileInferenceInput): EnergyInferenceResult {
        val windowStart = input.completedLocalDate.minusDays((MAX_ROLLING_COMPLETED_DAYS - 1).toLong())
        val eligibleMeals = input.meals.asSequence()
            .filter { meal -> meal.localDate(input.zoneId) in windowStart..input.completedLocalDate }
            .filter(::isEligibleMeal)
            .sortedWith(CANONICAL_MEAL_ORDER)
            .distinctBy { meal -> meal.canonicalIdentity to meal.revision }
            .toList()
        // No persisted reconciliation marker exists for activity telemetry. Keep this
        // diagnostic inference fail-closed until a future schema can prove provenance.
        val eligibleActivities = emptyList<EnergyInferenceActivity>()
        val qualityDays = eligibleMeals.map { it.localDate(input.zoneId) }.distinct().count()
        val tier = tier(qualityDays)
        val inferredFoodDuration = eligibleMeals
            .takeIf { tier >= EvidenceTier.PROVISIONAL }
            ?.map(EnergyInferenceMeal::absorptionMinutes)
            ?.medianOrNull()
            ?.coerceIn(
                EnergyProfileFoodDurationPolicy.MIN_MINUTES,
                EnergyProfileFoodDurationPolicy.MAX_MINUTES
            )
        val inferredFood = inferredFoodDuration?.let(::profileForDuration)
        val inferredActivity: ActivityProfile? = null
        val manualFood = input.settings.manualFoodProfile.takeIf {
            input.settings.foodProfileMode == FoodProfileMode.MANUAL
        }
        val manualActivity = input.settings.manualActivityProfile.takeIf {
            input.settings.activityProfileMode == ActivityProfileMode.MANUAL
        }
        val food = EnergyProfileInferenceValue(
            value = manualFood ?: inferredFood,
            source = if (manualFood != null) ResolutionSource.MANUAL else if (inferredFood != null) {
                ResolutionSource.AUTO
            } else {
                ResolutionSource.DEFAULT
            }
        )
        val activity = EnergyProfileInferenceValue(
            value = manualActivity ?: inferredActivity,
            source = if (manualActivity != null) ResolutionSource.MANUAL else if (inferredActivity != null) {
                ResolutionSource.AUTO
            } else {
                ResolutionSource.DEFAULT
            }
        )
        return EnergyInferenceResult(
            tier = tier,
            qualityDays = qualityDays,
            food = food,
            foodDurationMinutes = when (food.source) {
                ResolutionSource.MANUAL -> MealAbsorptionCurve(
                    food.value ?: MealAbsorptionProfile.MIXED,
                    requestedDurationMinutes = null
                ).durationMinutes
                else -> inferredFoodDuration
            },
            activity = activity,
            confidence = confidence(tier, eligibleMeals.size, eligibleActivities.size),
            // Task 9 has no canonical replay data source. This must stay fail-closed.
            replayPassed = false,
            sourceHashSha256 = sourceHash(
                completedLocalDate = input.completedLocalDate,
                zoneId = input.zoneId,
                manualFood = manualFood,
                manualActivity = manualActivity,
                meals = eligibleMeals,
                activities = eligibleActivities
            )
        )
    }

    fun tier(qualityDays: Int): EvidenceTier = when {
        qualityDays >= HIGH_CONFIDENCE_MIN_DAYS -> EvidenceTier.HIGH_CONFIDENCE
        qualityDays >= STABLE_MIN_DAYS -> EvidenceTier.STABLE
        qualityDays >= PROVISIONAL_MIN_DAYS -> EvidenceTier.PROVISIONAL
        else -> EvidenceTier.INSUFFICIENT_DATA
    }

    private fun isEligibleMeal(meal: EnergyInferenceMeal): Boolean =
        meal.canonicalIdentity.isNotBlank() &&
            meal.revision.isNotBlank() &&
            meal.carbsG.isFinite() && meal.carbsG in MIN_CARBS_G..MAX_CARBS_G &&
            EnergyProfileFoodDurationPolicy.isValid(meal.absorptionMinutes) &&
            meal.postMealGlucoseCovered && meal.therapyCovered && !meal.overlapsAnotherMeal &&
            !meal.syntheticUam && !meal.staleOrSensorBlocked && !meal.superseded

    private fun sourceHash(
        completedLocalDate: LocalDate,
        zoneId: ZoneId,
        manualFood: MealAbsorptionProfile?,
        manualActivity: ActivityProfile?,
        meals: List<EnergyInferenceMeal>,
        activities: List<EnergyInferenceActivity>
    ): String {
        // Meals are sorted before duplicate filtering, so the canonical winner and
        // this SHA-256 input do not depend on the source/DAO iteration order.
        val canonical = buildList {
            add("v=$SCHEMA_VERSION|completed=$completedLocalDate|zone=${zoneId.id}")
            add("manual_food=${manualFood?.name ?: "AUTO"}|manual_activity=${manualActivity?.name ?: "AUTO"}")
            meals.forEach { meal ->
                add(
                    "meal|${meal.occurredAtMs}|${meal.canonicalIdentity}|${meal.revision}|" +
                        "${meal.carbsG.canonical()}|${meal.absorptionMinutes}"
                )
            }
            activities.forEach { activity ->
                add(
                    "activity|${activity.observedAtMs}|${activity.source}|" +
                        "${activity.quality.uppercase()}|${activity.activityRatio.canonical()}"
                )
            }
        }.joinToString("\n")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun Double.canonical(): String = toString()

    private fun List<Int>.medianOrNull(): Int? =
        sorted().getOrNull((size - 1) / 2)

    private fun profileForDuration(durationMinutes: Int): MealAbsorptionProfile = when {
        durationMinutes <= FAST_MAX_DURATION_MINUTES -> MealAbsorptionProfile.FAST
        durationMinutes <= MIXED_MAX_DURATION_MINUTES -> MealAbsorptionProfile.MIXED
        else -> MealAbsorptionProfile.FAT_PROTEIN
    }

    private fun confidence(
        tier: EvidenceTier,
        meals: Int,
        activities: Int
    ): Double {
        val tierBase = when (tier) {
            EvidenceTier.INSUFFICIENT_DATA -> 0.0
            EvidenceTier.PROVISIONAL -> 0.45
            EvidenceTier.STABLE -> 0.65
            EvidenceTier.HIGH_CONFIDENCE -> 0.80
        }
        return (tierBase + minOf(0.15, (meals + activities) * 0.01)).coerceAtMost(0.95)
    }

    private fun EnergyInferenceMeal.localDate(zoneId: ZoneId): LocalDate =
        Instant.ofEpochMilli(occurredAtMs).atZone(zoneId).toLocalDate()

    private companion object {
        /**
         * All variable fields of an eligible meal are ordered before duplicate
         * filtering. Eligibility fixes its boolean evidence fields, leaving this
         * a deterministic total order for a canonical identity/revision pair.
         */
        val CANONICAL_MEAL_ORDER: Comparator<EnergyInferenceMeal> =
            compareBy<EnergyInferenceMeal> { it.occurredAtMs }
                .thenBy { it.canonicalIdentity }
                .thenBy { it.revision }
                .thenBy { it.absorptionMinutes }
                .thenBy { it.carbsG }

        const val SCHEMA_VERSION = 1
        const val MAX_ROLLING_COMPLETED_DAYS = 14
        const val PROVISIONAL_MIN_DAYS = 7
        const val STABLE_MIN_DAYS = 10
        const val HIGH_CONFIDENCE_MIN_DAYS = 14
        const val MIN_CARBS_G = 1.0
        const val MAX_CARBS_G = 300.0
        const val FAST_MAX_DURATION_MINUTES = 60
        const val MIXED_MAX_DURATION_MINUTES = 180
    }
}

internal object EnergyProfileFoodDurationPolicy {
    const val MIN_MINUTES = 30
    const val MAX_MINUTES = 360

    fun isValid(minutes: Int): Boolean = minutes in MIN_MINUTES..MAX_MINUTES
}
