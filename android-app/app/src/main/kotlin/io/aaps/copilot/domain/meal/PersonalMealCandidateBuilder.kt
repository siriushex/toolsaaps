package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.nutrition.MealPortionObservation
import io.aaps.copilot.domain.nutrition.PersonalAbsorptionResearchModel
import io.aaps.copilot.domain.nutrition.PersonalGiEvidence
import io.aaps.copilot.domain.nutrition.personalAbsorptionComparisonCohort
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.min

internal enum class PersonalMealCandidateStatus {
    CREATED, INVALID_CONTEXT, MODEL_UNAVAILABLE, MEAL_MISMATCH, GI_UNAVAILABLE, LATE, ENGINE_UNAVAILABLE
}

internal data class PersonalMealCandidateResult(val status: PersonalMealCandidateStatus,
    val candidate: MealProspectiveCandidate? = null)

/** On-demand LOCAL_ENGINE comparison. Caller registers against the same accepted runtime receipt. */
internal object PersonalMealCandidateBuilder {
    suspend fun build(simulation: MealSimulationContext, identity: MealForecastIdentity,
        model: PersonalAbsorptionResearchModel, meal: MealPortionObservation,
        knownGi: PersonalGiEvidence?, clockMs: () -> Long): PersonalMealCandidateResult {
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        fun reject(status: PersonalMealCandidateStatus) = PersonalMealCandidateResult(status)
        val anchor = simulation.glucose.last().ts
        if (anchor > Long.MAX_VALUE - 3_600_000L || identity.basis != MealForecastBasis.LOCAL_ENGINE ||
            identity.glucoseBasisId != "raw-cgm-v1" || identity.predictionAtMs != anchor ||
            identity.forecastCycleId != simulation.forecastCycleId || identity.settingsRevision != simulation.settingsRevision ||
            identity.baselineModelVersion != simulation.baselineForecasts.map { it.modelVersion }.distinct().singleOrNull() ||
            identity.recordedZoneId != model.request.recordedZoneId ||
            simulation.glucose.any { it.quality != DataQuality.OK || it.source != identity.source } ||
            identity.sensorSessionKey.isBlank() || identity.sensorSessionKey.length > 512 ||
            identity.calibrationModelId?.let { it.isBlank() || it.length > 512 } == true)
            return reject(PersonalMealCandidateStatus.INVALID_CONTEXT)
        val kernel = simulation.insulinKernelIdentity()
        val cohort = personalAbsorptionComparisonCohort(identity.sensorSessionKey,
            identity.calibrationModelId ?: "raw-cgm-v1", kernel.id, kernel.durationMinutes)
        val started = clockMs()
        if (started < simulation.capturedAtMs || started >= anchor + 300_000L)
            return reject(PersonalMealCandidateStatus.LATE)
        if (model.comparisonCohort != cohort || model.request.cutoffMs > anchor || model.availableAtMs > started)
            return reject(PersonalMealCandidateStatus.MODEL_UNAVAILABLE)
        val minute = meal.timestamp.atZone(ZoneId.of(identity.recordedZoneId)).toLocalTime().toSecondOfDay() / 60
        val distance = abs(minute - model.request.minuteOfDay)
        if (meal.profile != model.request.profile || meal.portion != model.request.portion ||
            !meal.grams.isFinite() || meal.grams !in model.minimumTrainingGrams..model.maximumTrainingGrams ||
            min(distance, 1440 - distance) > 120) return reject(PersonalMealCandidateStatus.MEAL_MISMATCH)
        val giValue = if (model.giModel != null) {
            val gi = knownGi ?: return reject(PersonalMealCandidateStatus.GI_UNAVAILABLE)
            if (gi.canonicalId != meal.canonicalId || gi.therapyRevision != meal.therapyRevision ||
                gi.availableAtMs !in meal.timestamp.toEpochMilli()..anchor || !gi.value.isFinite() ||
                gi.value !in 0.0..200.0 || gi.source !in setOf("USER", "CATALOG") ||
                (gi.source == "CATALOG" && gi.reference.isNullOrBlank()) ||
                (gi.reference?.length ?: 0) > 256) return reject(PersonalMealCandidateStatus.GI_UNAVAILABLE)
            gi.value
        } else null
        val shape = model.shapeFor(giValue) ?: return reject(PersonalMealCandidateStatus.GI_UNAVAILABLE)
        coroutine.ensureActive()
        val forecast = simulation.personalForecast(meal, shape)
            ?: return reject(PersonalMealCandidateStatus.ENGINE_UNAVAILABLE)
        coroutine.ensureActive()
        val completed = clockMs()
        if (completed < started || completed >= anchor + 300_000L) return reject(PersonalMealCandidateStatus.LATE)
        val values = forecast.forecasts.map { MealProspectiveValue(it.horizonMinutes, it.ts, it.valueMmol) }
        val candidate = MealProspectiveCandidate(identity,
            MealProspectiveModel(model.version, model.evidenceDigest, model.request.cutoffMs, model.availableAtMs),
            anchor, simulation.capturedAtMs, started, completed, values)
        // Use the existing receipt gate; do not inherit heuristic candidate intervals.
        val registration = MealProspectiveFrame.register(identity, simulation.capturedAtMs,
            simulation.baselineForecasts.map { MealProspectiveValue(it.horizonMinutes, it.ts, it.valueMmol, it.ciLow, it.ciHigh) },
            candidate, completed)
        return if (registration.status == MealProspectiveRegistrationStatus.PAIRED)
            PersonalMealCandidateResult(PersonalMealCandidateStatus.CREATED, candidate)
        else reject(PersonalMealCandidateStatus.INVALID_CONTEXT)
    }
}
