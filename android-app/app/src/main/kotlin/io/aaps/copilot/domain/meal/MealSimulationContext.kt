package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.domain.predict.MealFutureInsulinPlan
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Frozen research inputs, not evidence of clinically safe meal timing. */
internal class MealSimulationContext private constructor(
    private val seed: HybridPredictionEngine,
    val glucose: List<GlucosePoint>,
    val therapy: List<TherapyEvent>,
    val baselineForecasts: List<Forecast>,
    val forecastCycleId: String,
    val settingsRevision: Long,
    val capturedAtMs: Long
) {
    fun newEngine(): HybridPredictionEngine = seed.forkForMealSimulation()

    fun insulinProjection(horizonMinutes: Int) =
        seed.projectMealTimingInsulin(glucose, therapy, horizonMinutes)

    fun announcedFoodProjection(horizonMinutes: Int) =
        seed.projectMealTimingAnnouncedFood(glucose, therapy, horizonMinutes)

    fun forwardForecast(canonicalMealId: String, replacement: MealAbsorptionProjection, horizonMinutes: Int = 60,
        futureInsulinPlan: MealFutureInsulinPlan? = null) =
        seed.forecastMealTimingForward(glucose, therapy, canonicalMealId, replacement, horizonMinutes, futureInsulinPlan)

    companion object {
        /**
         * Caller owns the cycle lock and all source collections until capture
         * returns. Pass accepted LOCAL engine output, not calibrated/cloud output.
         * Revision labels must come from that same accepted cycle.
         */
        suspend fun capture(
            engine: HybridPredictionEngine,
            glucose: List<GlucosePoint>,
            therapy: List<TherapyEvent>,
            acceptedLocalForecasts: List<Forecast>,
            forecastCycleId: String,
            settingsRevision: Long,
            capturedAtMs: Long
        ): MealSimulationContext {
            val coroutine = currentCoroutineContext()
            coroutine.ensureActive()
            require(forecastCycleId.isNotBlank() && settingsRevision >= 0 && capturedAtMs > 0)
            // Explicit allocation budgets: never truncate evidence silently.
            require(glucose.size in 1..20_000 && therapy.size <= 50_000)
            require(glucose.all { it.ts > 0 && it.ts <= capturedAtMs && it.valueMmol.isFinite() && it.valueMmol > 0 })
            require(glucose.zipWithNext().all { (a, b) -> a.ts < b.ts })
            val predictionAtMs = glucose.last().ts
            require(capturedAtMs - predictionAtMs <= 300_000L)
            // A future meal is a separate scenario, never a fabricated source event.
            require(therapy.all { it.ts > 0 && it.ts <= predictionAtMs })
            require(acceptedLocalForecasts.size == 3 &&
                acceptedLocalForecasts.map { it.horizonMinutes }.toSet() == setOf(5, 30, 60))
            require(acceptedLocalForecasts.all {
                it.valueMmol.isFinite() && it.ciLow.isFinite() && it.ciHigh.isFinite() &&
                    it.ciLow <= it.valueMmol && it.valueMmol <= it.ciHigh
            })
            val frozenGlucose = Collections.unmodifiableList(glucose.toList())
            val frozenTherapy = Collections.unmodifiableList(therapy.map {
                coroutine.ensureActive()
                it.copy(payload = Collections.unmodifiableMap(LinkedHashMap(it.payload)))
            })
            val frozenForecasts = Collections.unmodifiableList(acceptedLocalForecasts.sortedBy { it.horizonMinutes })
            val seed = engine.forkForMealSimulation()
            coroutine.ensureActive()
            val reproduced = seed.forkForMealSimulation().predict(frozenGlucose, frozenTherapy)
            coroutine.ensureActive()
            require(reproduced == frozenForecasts) { "Meal simulation baseline mismatch" }
            return MealSimulationContext(seed, frozenGlucose, frozenTherapy, frozenForecasts,
                forecastCycleId, settingsRevision, capturedAtMs)
        }
    }
}
