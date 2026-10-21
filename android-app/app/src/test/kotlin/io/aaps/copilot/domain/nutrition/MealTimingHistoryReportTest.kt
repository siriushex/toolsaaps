package io.aaps.copilot.domain.nutrition

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MealTimingHistoryReportTest {
    private val cutoff = PersonalAbsorptionEpisodeFixtures.cutoff
    private val day = 86_400_000L
    private fun meal(days: Int) = PersonalAbsorptionEpisodeFixtures.meal("meal-$days", cutoff - days * day)
    private fun report(days: Int = 14, prepared: Boolean = true): MealTimingHistoryReport {
        val meals = (1..days).map(::meal)
        val source = PersonalAbsorptionEpisodeProducer().produce(cutoff, "UTC", meals,
            if (prepared) meals.map { PersonalAbsorptionEpisodeFixtures.evidence(it) } else emptyList())
        return MealTimingHistoryReport.calculate(source)
    }

    @Test fun completedThreeSevenAndFourteenDayWindowsDoNotAuthorizeTiming() {
        val result = report()
        assertEquals(listOf(3, 7, 14), result.windows.map { it.days })
        result.windows.forEach { window ->
            assertEquals(window.days, window.candidateMeals)
            assertEquals(window.days, window.qualifiedAbsorptionEpisodes)
            assertEquals(window.days, window.qualifiedDays)
            assertEquals(window.days, window.largestCohortDays)
            assertEquals(1, window.comparableCohorts)
            assertEquals(0, window.independentGiEpisodes)
        }
        assertNull(result.recommendedWaitMinutes)
        assertFalse(result.notificationAllowed)
        assertTrue(result.contextSummary().contains("observed_response_is_not_causal_insulin_onset=true"))
        assertTrue(result.contextSummary().contains("confirmed_bolus_to_actual_meal_link=not_available"))
        assertFalse(result.contextSummary().contains("meal-1"))
    }

    @Test fun legacyMealsReportRealMissingInputsInsteadOfInventedLearning() {
        val result = report(prepared = false)
        result.windows.forEach { window ->
            assertEquals(window.days, window.candidateMeals)
            assertEquals(0, window.qualifiedDays)
            assertEquals(0, window.qualifiedAbsorptionEpisodes)
            assertEquals(window.days, window.rejections["SOURCE_HISTORICAL_MODEL_CONTEXT"])
            assertEquals(window.days, window.rejections["SOURCE_INDEPENDENT_INSULIN_INTERVALS"])
        }
    }

    @Test fun differentInsulinModelsCannotCountAsOneComparableFourteenDayCohort() {
        val meals = (1..14).map(::meal)
        val frames = meals.mapIndexed { index, label ->
            val frame = PersonalAbsorptionEpisodeFixtures.evidence(label)
            frame.copy(input = frame.input.copy(context = frame.input.context!!.copy(
                insulinModelId = if (index < 7) "profile-a" else "profile-b")))
        }
        val source = PersonalAbsorptionEpisodeProducer().produce(cutoff, "UTC", meals, frames)
        val window = MealTimingHistoryReport.calculate(source).windows.last()
        assertEquals(14, window.qualifiedDays)
        assertEquals(2, window.comparableCohorts)
        assertEquals(7, window.largestCohortDays)
    }

    @Test fun giMustBeIndependentAvailableAndBoundToExactMealRevision() {
        val meals = (1..7).map(::meal)
        val source = PersonalAbsorptionEpisodeProducer().produce(cutoff, "UTC", meals,
            meals.map { PersonalAbsorptionEpisodeFixtures.evidence(it) })
        fun gi(id: Int) = PersonalGiEvidence("meal-$id", "r1", 55.0, "USER", null, cutoff - day)
        val evidence = listOf(gi(1), gi(1), gi(2).copy(source = "MODEL_ESTIMATE"),
            gi(3).copy(therapyRevision = "old"), gi(4).copy(availableAtMs = cutoff + 1),
            gi(5), gi(5).copy(value = 70.0), gi(6).copy(value = Double.NaN))
        val result = MealTimingHistoryReport.calculate(source, evidence).windows[1]
        assertEquals(1, result.independentGiEpisodes)
        assertEquals(6, result.rejections["INDEPENDENT_GI_UNAVAILABLE"])
    }

    @Test fun futureAvailableEvidenceAndConfoundsNeverCountAsQualifiedEpisodes() {
        val meals = (1..3).map(::meal)
        val frames = meals.mapIndexed { index, label ->
            val frame = PersonalAbsorptionEpisodeFixtures.evidence(label)
            when (index) {
                0 -> frame.copy(preparedAtMs = cutoff + 1)
                1 -> frame.copy(input = frame.input.copy(confounds = setOf(PersonalAbsorptionConfound.ILLNESS)))
                else -> frame.copy(input = frame.input.copy(context = frame.input.context!!.copy(
                    knownAtMs = label.timestamp.toEpochMilli() + 1)))
            }
        }
        val source = PersonalAbsorptionEpisodeProducer().produce(cutoff, "UTC", meals, frames)
        val result = MealTimingHistoryReport.calculate(source).windows.first()
        assertEquals(3, result.candidateMeals)
        assertEquals(0, result.qualifiedAbsorptionEpisodes)
        assertEquals(1, result.rejections["SOURCE_PREPARATION_AVAILABILITY"])
        assertEquals(1, result.rejections["EPISODE_ILLNESS"])
        assertEquals(1, result.rejections["EPISODE_INVALID_CONTEXT"])
    }

    @Test fun sourceFailureOrOverflowIsNotAnEmptySuccessfulHistory() {
        listOf(PersonalAbsorptionSourceStatus.UNAVAILABLE, PersonalAbsorptionSourceStatus.OVERFLOW).forEach { status ->
            val source = PersonalAbsorptionEpisodeProducer().produce(cutoff, "UTC", emptyList(), emptyList(), status)
            val result = MealTimingHistoryReport.calculate(source)
            assertEquals(status, result.status)
            assertTrue(result.windows.isEmpty())
        }
        val source = PersonalAbsorptionEpisodeProducer().produce(cutoff, "UTC", emptyList(), emptyList())
        val gi = PersonalGiEvidence("m", "r", 50.0, "USER", null, cutoff)
        assertEquals(PersonalAbsorptionSourceStatus.OVERFLOW,
            MealTimingHistoryReport.calculate(source, List(5001) { gi }).status)
    }

    @Test fun calendarWindowsUseLocalCompletedDaysAcrossDstWithoutToday() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = Instant.parse("2026-10-26T11:00:00Z").toEpochMilli()
        val source = PersonalAbsorptionEpisodeProducer().produce(now, zone.id, emptyList(), emptyList())
        val result = MealTimingHistoryReport.calculate(source).windows.first()
        assertEquals(Instant.parse("2026-10-22T22:00:00Z").toEpochMilli(), result.fromMs)
        assertEquals(Instant.parse("2026-10-25T23:00:00Z").toEpochMilli(), result.throughExclusiveMs)
        assertEquals(73, (result.throughExclusiveMs - result.fromMs) / 3_600_000)
    }

    @Test fun manyMealsOnOneDayDoNotSatisfyThreeDaysOfSupport() {
        val meals = listOf(6, 12, 18).map { hour -> PersonalAbsorptionEpisodeFixtures.meal(
            "meal-hour-$hour", Instant.parse("2026-10-12T00:00:00Z").toEpochMilli() + hour * 3_600_000) }
        val source = PersonalAbsorptionEpisodeProducer().produce(cutoff, "UTC", meals,
            meals.map { PersonalAbsorptionEpisodeFixtures.evidence(it) })
        val result = MealTimingHistoryReport.calculate(source).windows.first()
        assertEquals(3, result.qualifiedAbsorptionEpisodes)
        assertEquals(1, result.qualifiedDays)
        assertEquals(1, result.largestCohortDays)
    }

    @Test fun maximumFixedReasonSummaryFitsTheExistingContextBudget() {
        val outcomes = (1..5000).map { index -> PersonalAbsorptionEpisodeOutcome(
            "meal-$index", "r1", cutoff - day, null,
            PersonalAbsorptionSourceProblem.entries.toSet(), PersonalAbsorptionRejection.entries.toSet()) }
        val source = PersonalAbsorptionSourceSnapshot(cutoff, "UTC", PersonalAbsorptionSourceStatus.READY, outcomes)
        val summary = MealTimingHistoryReport.calculate(source).contextSummary()
        assertTrue("leave at least2000 code points for insulin settings", summary.length <= 6000)
        assertFalse(summary.contains("meal-1"))
        assertTrue(summary.contains("SOURCE_HISTORICAL_MODEL_CONTEXT:5000"))
    }
}
