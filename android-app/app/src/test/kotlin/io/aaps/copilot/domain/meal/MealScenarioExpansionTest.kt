package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import org.junit.Assert.*
import org.junit.Test

class MealScenarioExpansionTest {
    private val now = 1_700_000_000_000L
    private fun belief(wide: Boolean = true): MealBelief {
        val cases = MealHypothesisKind.entries.map { kind ->
            val absent = kind == MealHypothesisKind.NOT_HAPPENING || kind == MealHypothesisKind.NO_NEW_MEAL
            val expand = wide && kind == MealHypothesisKind.STARTED_EARLIER
            MealScenario(kind.name, kind,
                if (absent) null else MealStartInterval(now - 600_000, if (expand) now else now - 600_000),
                if (absent) MealCarbRange(0.0, 0.0) else MealCarbRange(10.0, if (expand) 30.0 else 10.0),
                if (expand) listOf(MealAbsorptionAlternative(MealAbsorptionProfile.FAST, 60, 0.75),
                    MealAbsorptionAlternative(MealAbsorptionProfile.MIXED, 120, 0.25))
                else listOf(MealAbsorptionAlternative(MealAbsorptionProfile.FAST, 60, 1.0)),
                1.0 / 6, "12")
        }
        return MealBelief(MealInput("input", now, MealCarbRange(10.0, 30.0)), cases, 7, now + 300_000, "sample", "runtime")
    }

    @Test fun expandsAllBoundariesAndPreservesParentAndProfileMass() {
        val source = belief()
        val result = MealScenarioExpansion.expand(source)
        val expanded = result.belief.scenarios.filter { result.parentScenarioIds.getValue(it.id) == "STARTED_EARLIER" }
        assertEquals(8, expanded.size)
        assertEquals(setOf(10.0, 30.0), expanded.map { it.carbs.minimumGrams }.toSet())
        assertEquals(setOf(now - 600_000, now), expanded.map { it.inferredStart!!.earliestMs }.toSet())
        assertEquals(1.0 / 6, expanded.sumOf { it.probability }, 1e-12)
        assertEquals(0.75 / 6, expanded.filter { it.absorption.single().profile == MealAbsorptionProfile.FAST }.sumOf { it.probability }, 1e-12)
        assertEquals(source.stageProbabilities.keys, result.belief.stageProbabilities.keys)
        source.stageProbabilities.forEach { (kind, mass) -> assertEquals(mass, result.belief.stageProbabilities.getValue(kind), 1e-12) }
        assertSame(source.input, result.belief.input)
        assertEquals(source.revision, result.belief.revision)
        assertEquals(source.lastSampleAtMs, result.belief.lastSampleAtMs)
        assertEquals(source.runtimeIdentity, result.belief.runtimeIdentity)
        assertEquals(2, source.scenarios.first { it.kind == MealHypothesisKind.STARTED_EARLIER }.absorption.size)
    }

    @Test fun concreteCasesAreIdempotentAndResultsImmutable() {
        val source = belief(false)
        val result = MealScenarioExpansion.expand(source)
        assertEquals(source.scenarios.map { it.id }.toSet(), result.belief.scenarios.map { it.id }.toSet())
        val again = MealScenarioExpansion.expand(result.belief)
        assertEquals(result.belief.scenarios.map { it.id }, again.belief.scenarios.map { it.id })
        assertThrows(UnsupportedOperationException::class.java) { (result.parentScenarioIds as MutableMap).clear() }
    }

    @Test fun budgetRejectsWithoutDroppingAnyHypothesisOrExtreme() {
        assertThrows(IllegalArgumentException::class.java) { MealScenarioExpansion.expand(belief(), maximumScenarios = 6) }
        assertEquals(13, MealScenarioExpansion.expand(belief(), maximumScenarios = 13).belief.scenarios.size)
    }

    @Test fun sourceOrderingCannotChangeExpandedIdsOrWeights() {
        val source = belief()
        val reversed = MealBelief(source.input, source.scenarios.reversed(), source.revision,
            source.lastSampleAtMs, source.lastSampleId, source.runtimeIdentity)
        val a = MealScenarioExpansion.expand(source).belief.scenarios
        val b = MealScenarioExpansion.expand(reversed).belief.scenarios
        assertEquals(a.map { it.id to it.probability }, b.map { it.id to it.probability })
    }

    @Test fun expandedGridIsAlsoIdempotent() {
        val first = MealScenarioExpansion.expand(belief()).belief
        val second = MealScenarioExpansion.expand(first).belief
        assertEquals(first.scenarios.map { it.id to it.probability }, second.scenarios.map { it.id to it.probability })
    }

    @Test fun profileOrderingDoesNotChangeGridIdentity() {
        val source = belief()
        val reversedProfiles = MealBelief(source.input, source.scenarios.map {
            MealScenario(it.id, it.kind, it.inferredStart, it.carbs, it.absorption.reversed(), it.probability, it.canonicalMealId)
        }, source.revision)
        val first = MealScenarioExpansion.expand(source).belief.scenarios
        val second = MealScenarioExpansion.expand(reversedProfiles).belief.scenarios
        assertEquals(first.map { it.id to it.probability }, second.map { it.id to it.probability })
    }

    @Test fun zeroProbabilityUnderflowIsRejectedNotRemoved() {
        val source = belief()
        val cases = source.scenarios.map {
            it.weighted(if (it.kind == MealHypothesisKind.STARTED_EARLIER) Double.MIN_VALUE else 0.2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MealScenarioExpansion.expand(MealBelief(source.input, cases, source.revision))
        }
    }
}
