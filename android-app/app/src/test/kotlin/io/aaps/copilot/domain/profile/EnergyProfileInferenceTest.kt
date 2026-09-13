package io.aaps.copilot.domain.profile

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Test

class EnergyProfileInferenceTest {

    private val inference = EnergyProfileInference()
    private val zone = ZoneOffset.UTC
    private val completedDate = LocalDate.of(2026, 8, 7)

    @Test
    fun qualityDaysResolveToApprovedTiers() {
        assertThat(inference.tier(6)).isEqualTo(EvidenceTier.INSUFFICIENT_DATA)
        assertThat(inference.tier(7)).isEqualTo(EvidenceTier.PROVISIONAL)
        assertThat(inference.tier(9)).isEqualTo(EvidenceTier.PROVISIONAL)
        assertThat(inference.tier(10)).isEqualTo(EvidenceTier.STABLE)
        assertThat(inference.tier(13)).isEqualTo(EvidenceTier.STABLE)
        assertThat(inference.tier(14)).isEqualTo(EvidenceTier.HIGH_CONFIDENCE)
        assertThat(inference.tier(99)).isEqualTo(EvidenceTier.HIGH_CONFIDENCE)
    }

    @Test
    fun manualValuesAlwaysWinNightlyInference() {
        val result = inference.infer(
            input(
                settings = EnergyProfileSettings(
                    foodProfileMode = FoodProfileMode.MANUAL,
                    manualFoodProfile = MealAbsorptionProfile.FAST,
                    activityProfileMode = ActivityProfileMode.MANUAL,
                    manualActivityProfile = ActivityProfile.LOW
                ),
                meals = listOf(meal(absorptionMinutes = 280)),
                activities = listOf(activity(1.8))
            )
        )

        assertThat(result.food.value).isEqualTo(MealAbsorptionProfile.FAST)
        assertThat(result.food.source).isEqualTo(ResolutionSource.MANUAL)
        assertThat(result.activity.value).isEqualTo(ActivityProfile.LOW)
        assertThat(result.activity.source).isEqualTo(ResolutionSource.MANUAL)
        assertThat(result.replayPassed).isFalse()
        assertThat(result.forecastInfluenceAllowed).isFalse()
        assertThat(result.targetManagerInfluenceAllowed).isFalse()
    }

    @Test
    fun sourceHashIsCanonicalAndIgnoresExcludedRows() {
        val included = meal(occurredAtMs = dayTimestamp(2), canonicalIdentity = "a")
        val excludedSynthetic = meal(occurredAtMs = dayTimestamp(3), canonicalIdentity = "uam").copy(syntheticUam = true)
        val excludedStale = meal(occurredAtMs = dayTimestamp(4), canonicalIdentity = "stale").copy(staleOrSensorBlocked = true)
        val includedActivity = activity(1.3, occurredAtMs = dayTimestamp(2) + 1)
        val first = inference.infer(input(meals = listOf(included, excludedSynthetic, excludedStale), activities = listOf(includedActivity)))
        val reordered = inference.infer(input(meals = listOf(excludedStale, included, excludedSynthetic), activities = listOf(includedActivity)))

        assertThat(first.sourceHashSha256).isEqualTo(reordered.sourceHashSha256)
        assertThat(first.qualityDays).isEqualTo(1)
        assertThat(first.food.value).isNull()
    }

    @Test
    fun autoFoodProfileWaitsForSevenQualityDays() {
        val sixDays = (1L..6L).map { day ->
            meal(occurredAtMs = dayTimestamp(day), canonicalIdentity = "meal-$day")
        }
        val sevenDays = sixDays + meal(
            occurredAtMs = dayTimestamp(7),
            canonicalIdentity = "meal-7"
        )

        val insufficient = inference.infer(input(meals = sixDays))
        val provisional = inference.infer(input(meals = sevenDays))

        assertThat(insufficient.tier).isEqualTo(EvidenceTier.INSUFFICIENT_DATA)
        assertThat(insufficient.food.value).isNull()
        assertThat(insufficient.food.source).isEqualTo(ResolutionSource.DEFAULT)
        assertThat(insufficient.foodDurationMinutes).isNull()
        assertThat(provisional.tier).isEqualTo(EvidenceTier.PROVISIONAL)
        assertThat(provisional.food.value).isEqualTo(MealAbsorptionProfile.MIXED)
        assertThat(provisional.food.source).isEqualTo(ResolutionSource.AUTO)
        assertThat(provisional.foodDurationMinutes).isEqualTo(120)
    }

    @Test
    fun manualFoodProfileRemainsAvailableWithoutEvidence() {
        val result = inference.infer(
            input(
                settings = EnergyProfileSettings(
                    foodProfileMode = FoodProfileMode.MANUAL,
                    manualFoodProfile = MealAbsorptionProfile.FAST
                )
            )
        )

        assertThat(result.tier).isEqualTo(EvidenceTier.INSUFFICIENT_DATA)
        assertThat(result.qualityDays).isEqualTo(0)
        assertThat(result.food.value).isEqualTo(MealAbsorptionProfile.FAST)
        assertThat(result.food.source).isEqualTo(ResolutionSource.MANUAL)
        assertThat(result.foodDurationMinutes).isEqualTo(45)
    }

    @Test
    fun canonicalDuplicateWinnerAndHashAreIndependentOfInputOrder() {
        val slowDuplicates = (1L..7L).map { day ->
            meal(
                occurredAtMs = dayTimestamp(day),
                canonicalIdentity = "meal-$day",
                absorptionMinutes = 300
            ).copy(carbsG = 40.0)
        }
        val fastDuplicates = slowDuplicates.map { duplicate ->
            duplicate.copy(absorptionMinutes = 60, carbsG = 10.0)
        }

        val first = inference.infer(input(meals = slowDuplicates + fastDuplicates))
        val reordered = inference.infer(input(meals = fastDuplicates + slowDuplicates))

        assertThat(first.sourceHashSha256).isEqualTo(reordered.sourceHashSha256)
        assertThat(first.foodDurationMinutes).isEqualTo(60)
        assertThat(first.food.value).isEqualTo(MealAbsorptionProfile.FAST)
        assertThat(reordered.foodDurationMinutes).isEqualTo(60)
        assertThat(reordered.food.value).isEqualTo(MealAbsorptionProfile.FAST)
    }

    @Test
    fun demographicsAreNeverInferredOrIncludedInEvidenceHash() {
        val evidence = listOf(meal())
        val baseline = inference.infer(input(meals = evidence))
        val withDemographics = inference.infer(
            input(
                settings = EnergyProfileSettings(
                    birthDateEpochDay = LocalDate.of(2008, 2, 1).toEpochDay(),
                    heightCm = 160.0,
                    weightKg = 55.0,
                    physiologicalSex = PhysiologicalSex.FEMALE
                ),
                meals = evidence
            )
        )

        assertThat(withDemographics.sourceHashSha256).isEqualTo(baseline.sourceHashSha256)
        assertThat(withDemographics.food).isEqualTo(baseline.food)
        assertThat(withDemographics.activity).isEqualTo(baseline.activity)
    }

    @Test
    fun incompleteOrOverlappingMealsAndUnreconciledActivityAreExcluded() {
        val accepted = meal(occurredAtMs = dayTimestamp(1), canonicalIdentity = "accepted")
        val rejectedOverlap = meal(occurredAtMs = dayTimestamp(1) + 60_000L, canonicalIdentity = "overlap")
            .copy(overlapsAnotherMeal = true)
        val rejectedCoverage = meal(occurredAtMs = dayTimestamp(3), canonicalIdentity = "coverage")
            .copy(postMealGlucoseCovered = false)
        val result = inference.infer(
            input(
                meals = listOf(accepted, rejectedOverlap, rejectedCoverage),
                activities = listOf(activity(1.8).copy(reconciled = false))
            )
        )

        assertThat(result.qualityDays).isEqualTo(1)
        assertThat(result.activity.value).isNull()
    }

    @Test
    fun activityRatioNeverInfersActivityWithoutAnExplicitPersistedReconciliationMarker() {
        val result = inference.infer(
            input(
                activities = listOf(activity(1.8))
            )
        )

        assertThat(result.activity.value).isNull()
        assertThat(result.activity.source).isEqualTo(ResolutionSource.DEFAULT)
    }

    private fun input(
        settings: EnergyProfileSettings = EnergyProfileSettings(),
        meals: List<EnergyInferenceMeal> = emptyList(),
        activities: List<EnergyInferenceActivity> = emptyList()
    ) = EnergyProfileInferenceInput(
        completedLocalDate = completedDate,
        zoneId = zone,
        settings = settings,
        meals = meals,
        activities = activities
    )

    private fun meal(
        occurredAtMs: Long = dayTimestamp(1),
        canonicalIdentity: String = "meal",
        absorptionMinutes: Int = 120
    ) = EnergyInferenceMeal(
        occurredAtMs = occurredAtMs,
        canonicalIdentity = canonicalIdentity,
        revision = "r1",
        carbsG = 20.0,
        absorptionMinutes = absorptionMinutes,
        postMealGlucoseCovered = true,
        therapyCovered = true,
        overlapsAnotherMeal = false
    )

    private fun activity(
        ratio: Double,
        occurredAtMs: Long = dayTimestamp(1)
    ) = EnergyInferenceActivity(
        observedAtMs = occurredAtMs,
        source = "health_connect",
        quality = "OK",
        activityRatio = ratio,
        reconciled = true
    )

    private fun dayTimestamp(daysBeforeCompleted: Long): Long = completedDate.minusDays(daysBeforeCompleted)
        .atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli()
}
