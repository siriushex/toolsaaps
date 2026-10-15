package io.aaps.copilot.domain.predict

/**
 * Immutable food-only projection derived from the accepted prediction runtime.
 * Values are glucose deltas, not an absolute glucose forecast and not therapy.
 */
class AcceptedMealEffectSnapshot(
    val asOfTs: Long,
    val runtimeGeneration: String,
    val isfRevision: String,
    val crRevision: String,
    val curveRevision: String,
    val announcedCarbCoverage: Double?,
    announcedCarbStep: List<Double>
) {
    val announcedCarbStep: List<Double> = announcedCarbStep.toList()
}

data class MealEffectTimelinePoint(
    val timestamp: Long,
    val cumulativeDeltaMmol: Double,
    val intervalDeltaMmol: Double,
    val coverage: Double
)

data class MealEffectTimeline(
    val asOfTs: Long,
    val runtimeGeneration: String,
    val isfRevision: String,
    val crRevision: String,
    val curveRevision: String,
    val points: List<MealEffectTimelinePoint>
) {
    init {
        require(points.isNotEmpty())
        require(points == points.sortedBy(MealEffectTimelinePoint::timestamp))
    }
}

object MealEffectTimelineBuilder {
    const val STEP_MINUTES = 5
    const val DEFAULT_HORIZON_MINUTES = 30
    private const val MAX_HORIZON_MINUTES = 60

    /**
     * Uses the already accepted `HybridPredictionEngine` announced-carbohydrate
     * steps. It intentionally does not reconstruct a curve from display ISF/CR.
     */
    fun build(
        snapshot: AcceptedMealEffectSnapshot,
        horizonMinutes: Int = DEFAULT_HORIZON_MINUTES
    ): MealEffectTimeline? {
        if (snapshot.asOfTs <= 0L || snapshot.runtimeGeneration.isBlank() ||
            snapshot.isfRevision.isBlank() || snapshot.crRevision.isBlank() ||
            snapshot.curveRevision.isBlank() || horizonMinutes !in STEP_MINUTES..MAX_HORIZON_MINUTES ||
            horizonMinutes % STEP_MINUTES != 0
        ) return null
        val coverage = snapshot.announcedCarbCoverage
            ?.takeIf { it.isFinite() && it in 0.0..1.0 }
            ?: return null
        val requiredSteps = horizonMinutes / STEP_MINUTES
        if (snapshot.announcedCarbStep.size <= requiredSteps) return null
        if (snapshot.announcedCarbStep.take(requiredSteps + 1).any { !it.isFinite() || it < 0.0 }) {
            return null
        }

        var cumulative = 0.0
        val points = buildList {
            add(
                MealEffectTimelinePoint(
                    timestamp = snapshot.asOfTs,
                    cumulativeDeltaMmol = 0.0,
                    intervalDeltaMmol = 0.0,
                    coverage = coverage
                )
            )
            for (step in 1..requiredSteps) {
                val interval = snapshot.announcedCarbStep[step]
                cumulative += interval
                if (!cumulative.isFinite()) return null
                add(
                    MealEffectTimelinePoint(
                        timestamp = snapshot.asOfTs + step * STEP_MINUTES * 60_000L,
                        cumulativeDeltaMmol = cumulative,
                        intervalDeltaMmol = interval,
                        coverage = coverage
                    )
                )
            }
        }
        return MealEffectTimeline(
            asOfTs = snapshot.asOfTs,
            runtimeGeneration = snapshot.runtimeGeneration,
            isfRevision = snapshot.isfRevision,
            crRevision = snapshot.crRevision,
            curveRevision = snapshot.curveRevision,
            points = points
        )
    }
}
