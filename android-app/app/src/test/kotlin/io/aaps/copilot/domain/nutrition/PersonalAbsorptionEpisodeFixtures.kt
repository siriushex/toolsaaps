package io.aaps.copilot.domain.nutrition

import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.profile.MealAbsorptionCurve
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import java.time.Instant

internal object PersonalAbsorptionEpisodeFixtures {
    val cutoff = Instant.parse("2026-10-13T12:00:00Z").toEpochMilli()

    fun meal(id: String = "meal-1", timestampMs: Long = cutoff - 86_400_000L) =
        MealPortionObservation(id, Instant.ofEpochMilli(timestampMs), 35.0,
            MealAbsorptionProfile.MIXED, MealPortionProvenance.USER_ENTERED, MealPortion.MEDIUM,
            availableAt = Instant.ofEpochMilli(timestampMs + 1000), therapyRevision = "r1")

    fun evidence(meal: MealPortionObservation = meal(), zone: String = "UTC"): PersonalAbsorptionEpisodeEvidence {
        val ts = meal.timestamp.toEpochMilli()
        val end = personalAbsorptionDurationRange(meal.profile).last
        val curve = MealAbsorptionCurve(meal.profile, null)
        val glucose = (-45..end step 5).map { minute ->
            PersonalAbsorptionGlucose(ts + minute * 60_000L,
                6.0 + meal.grams * 0.3 * curve.absorbedFraction(minute.toDouble()) - minute / 5.0 * 0.03,
                DataQuality.OK, ts + minute * 60_000L + 1000)
        }
        val insulin = (-45 until end step 5).map { minute ->
            PersonalAbsorptionInsulinInterval(ts + minute * 60_000L, ts + (minute + 5) * 60_000L,
                0.01, PersonalAbsorptionInsulinSource.INDEPENDENT_COMPONENTS,
                ts + (minute + 5) * 60_000L + 1000)
        }
        return PersonalAbsorptionEpisodeEvidence(
            PersonalAbsorptionInput(meal, zone, glucose, insulin,
                PersonalAbsorptionContext(3.0, 10.0, "rapid-model", 360, ts - 1000, true),
                PersonalAbsorptionContextCheck.entries.toSet(), emptySet()),
            "sensor-session-1", "calibration-1", "review-1", ts + end * 60_000L + 2000,
            PersonalAbsorptionGlucoseBasis.CONTEMPORANEOUS)
    }
}
