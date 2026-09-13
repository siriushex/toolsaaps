package io.aaps.copilot.domain.profile

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MealAbsorptionCurveTest {

    @Test
    fun curvesUseExactProfilesAndConserveFiniteCarbs() {
        val cases = listOf(
            MealAbsorptionProfile.FAST to 45,
            MealAbsorptionProfile.MIXED to 120,
            MealAbsorptionProfile.FAT_PROTEIN to 270
        )

        cases.forEach { (profile, duration) ->
            val curve = MealAbsorptionCurve(profile, duration)
            val fractions = (0..duration).map { minute -> curve.absorbedFraction(minute.toDouble()) }

            assertThat(fractions.first()).isEqualTo(0.0)
            assertThat(fractions.last()).isEqualTo(1.0)
            fractions.zipWithNext().forEach { (left, right) ->
                assertThat(right).isAtLeast(left)
                assertThat(right).isAtMost(1.0)
            }

            listOf(0.0, 0.25, 37.5, Double.MAX_VALUE).forEach { grams ->
                val absorbed = curve.absorbedGrams(grams, duration / 2.0)
                val remaining = curve.remainingGrams(grams, duration / 2.0)
                assertThat(absorbed.isFinite()).isTrue()
                assertThat(remaining.isFinite()).isTrue()
                assertThat(absorbed).isAtLeast(0.0)
                assertThat(remaining).isAtLeast(0.0)
                assertThat(absorbed + remaining).isWithin(maxOf(1e-9, grams * 1e-12)).of(grams)
            }
        }

        assertThat(MealAbsorptionCurve(MealAbsorptionProfile.FAST, 45).absorbedFraction(22.5))
            .isWithin(1e-9).of(1.0 - (1.0 - 0.5) * (1.0 - 0.5))
        assertThat(MealAbsorptionCurve(MealAbsorptionProfile.MIXED, 120).absorbedFraction(60.0))
            .isWithin(1e-9).of(0.5)
        assertThat(MealAbsorptionCurve(MealAbsorptionProfile.FAT_PROTEIN, 270).absorbedFraction(135.0))
            .isWithin(1e-9).of(0.25)
    }

    @Test
    fun invalidDurationsFallBackToProfileDefaults() {
        assertThat(MealAbsorptionCurve(MealAbsorptionProfile.FAST, 5).durationMinutes).isEqualTo(45)
        assertThat(MealAbsorptionCurve(MealAbsorptionProfile.MIXED, 181).durationMinutes).isEqualTo(120)
        assertThat(MealAbsorptionCurve(MealAbsorptionProfile.FAT_PROTEIN, 0).durationMinutes).isEqualTo(270)
    }

    @Test
    fun resolverUsesPerMealThenManualThenAutoThenMixedDefault() {
        val resolver = MealAbsorptionProfileResolver()
        val perMeal = MealAbsorptionSelection(MealAbsorptionProfile.FAST, 60)
        val manual = MealAbsorptionSelection(MealAbsorptionProfile.FAT_PROTEIN, 360)
        val auto = MealAbsorptionSelection(MealAbsorptionProfile.MIXED, 180)

        assertThat(resolver.resolve(perMeal, manual, auto)).isEqualTo(
            ResolvedMealAbsorption(MealAbsorptionProfile.FAST, 60, ResolutionSource.PER_MEAL)
        )
        assertThat(resolver.resolve(null, manual, auto)).isEqualTo(
            ResolvedMealAbsorption(MealAbsorptionProfile.FAT_PROTEIN, 360, ResolutionSource.MANUAL)
        )
        assertThat(resolver.resolve(null, null, auto)).isEqualTo(
            ResolvedMealAbsorption(MealAbsorptionProfile.MIXED, 180, ResolutionSource.AUTO)
        )
        assertThat(resolver.resolve(null, null, null)).isEqualTo(
            ResolvedMealAbsorption(MealAbsorptionProfile.MIXED, 120, ResolutionSource.DEFAULT)
        )
    }

    @Test
    fun contextRejectsStalePerMealRevisionAndFallsBackInPriorityOrder() {
        val stale = MealAbsorptionSelection(
            profile = MealAbsorptionProfile.FAST,
            durationMinutes = 60,
            therapyRevision = "revision-a"
        )
        val context = MealAbsorptionContext(
            enabled = true,
            perMealOverrides = mapOf("meal-1" to stale),
            manual = MealAbsorptionSelection(MealAbsorptionProfile.FAT_PROTEIN, 360),
            auto = MealAbsorptionSelection(MealAbsorptionProfile.FAST, 45)
        )
        val resolver = MealAbsorptionProfileResolver()
        val staleReference = MealTherapyReference(
            "meal-1",
            "revision-b",
            MealTherapyReferenceTrust.TRUSTED
        )

        assertThat(context.perMealOverride(staleReference)).isNull()
        assertThat(resolver.resolve(context.perMealOverride(staleReference), context.manual, context.auto))
            .isEqualTo(ResolvedMealAbsorption(MealAbsorptionProfile.FAT_PROTEIN, 360, ResolutionSource.MANUAL))
        assertThat(resolver.resolve(context.perMealOverride(staleReference), null, context.auto))
            .isEqualTo(ResolvedMealAbsorption(MealAbsorptionProfile.FAST, 45, ResolutionSource.AUTO))
        assertThat(resolver.resolve(context.perMealOverride(staleReference), null, null))
            .isEqualTo(ResolvedMealAbsorption(MealAbsorptionProfile.MIXED, 120, ResolutionSource.DEFAULT))
    }

    @Test
    fun untrustedReferencesNormalizeIdentityAndRevisionToAbsent() {
        MealTherapyReference("spoofed", "revision", MealTherapyReferenceTrust.MISSING).let { missing ->
            assertThat(missing.identity).isEqualTo("absent")
            assertThat(missing.revision).isNull()
        }
        MealTherapyReference("conflicting", "revision", MealTherapyReferenceTrust.CONFLICT).let { conflict ->
            assertThat(conflict.identity).isEqualTo("absent")
            assertThat(conflict.revision).isNull()
        }
    }
}
