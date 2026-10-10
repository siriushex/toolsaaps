package io.aaps.copilot.domain.predict

/** Cumulative display-only food effect from now; no insulin or extra inferred UAM. */
object MealRollingImpact {
    fun fullDisplayTail(steps: List<Double>): List<Double> {
        if (steps.size !in 2..145 || steps.first() != 0.0 ||
            steps.any { !it.isFinite() || it < 0.0 }) return emptyList()
        var cumulative = 0.0
        return steps.map { step -> cumulative += step; cumulative }
            .takeIf { values -> values.all(Double::isFinite) } ?: emptyList()
    }

    fun nextThirtyMinutes(steps: List<Double>): List<Double> {
        if (steps.size < 13 || steps.take(13).any { !it.isFinite() || it < 0.0 }) return emptyList()
        return (0..6).map { end -> steps.subList(1, end + 1).sum() }
            .takeIf { values -> values.all(Double::isFinite) } ?: emptyList()
    }
}
