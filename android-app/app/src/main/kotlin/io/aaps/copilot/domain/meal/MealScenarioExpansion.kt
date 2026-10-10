package io.aaps.copilot.domain.meal

import java.util.Collections
import kotlin.math.abs

/** Equal endpoint support is an explicit research quadrature, not an inferred distribution. */
internal class ExpandedMealScenarios(val belief: MealBelief, parentScenarioIds: Map<String, String>) {
    val parentScenarioIds: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(parentScenarioIds))
    val policyVersion: String = "equal-boundary-support-v1"
}

internal object MealScenarioExpansion {
    fun expand(source: MealBelief, maximumScenarios: Int = 24): ExpandedMealScenarios {
        require(maximumScenarios in 6..96 && source.scenarios.size in 6..maximumScenarios)
        require(source.scenarios.map { it.id }.distinct().size == source.scenarios.size)
        require(source.scenarios.map { it.kind }.toSet() == MealHypothesisKind.entries.toSet())
        require(abs(source.scenarios.sumOf { it.probability } - 1.0) < 1e-9)
        val expanded = ArrayList<MealScenario>()
        val parents = LinkedHashMap<String, String>()
        for (parent in source.scenarios.sortedBy { it.id }) {
            require(parent.absorption.size <= maximumScenarios)
            val absent = parent.kind in setOf(MealHypothesisKind.NOT_HAPPENING, MealHypothesisKind.NO_NEW_MEAL)
            if (absent) require(parent.inferredStart == null)
            val grams = if (absent) listOf(0.0) else
                listOf(parent.carbs.minimumGrams, parent.carbs.maximumGrams).distinct()
            val onset = parent.inferredStart
            val starts: List<Long?> = if (onset == null) listOf(null) else listOf(onset.earliestMs, onset.latestMs).distinct()
            val profiles = parent.absorption.sortedWith(compareBy({ it.profile.ordinal }, { it.durationMinutes }, { it.probability }))
            val count = grams.size * starts.size * profiles.size
            require(expanded.size + count <= maximumScenarios) { "Meal scenario expansion budget exceeded" }
            var index = 0
            for (g in grams) for (start in starts) for (profile in profiles) {
                val id = if (count == 1) parent.id else "boundary:${parent.id.length}:${parent.id}:${index++}"
                require(id !in parents) { "Expanded scenario identity collision" }
                val mass = parent.probability * profile.probability / (grams.size * starts.size)
                require(mass.isFinite() && mass > 0.0) { "Unrepresentable scenario probability" }
                expanded.add(MealScenario(id, parent.kind, start?.let { MealStartInterval(it, it) },
                    MealCarbRange(g, g), listOf(profile.copy(probability = 1.0)), mass, parent.canonicalMealId))
                parents[id] = parent.id
            }
        }
        return ExpandedMealScenarios(MealBelief(source.input, expanded.sortedBy { it.id }, source.revision,
            source.lastSampleAtMs, source.lastSampleId, source.runtimeIdentity), parents)
    }
}
