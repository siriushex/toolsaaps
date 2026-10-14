package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.meal.MealForecastBasis
import io.aaps.copilot.domain.meal.MealForecastIdentity
import io.aaps.copilot.domain.meal.MealProspectiveCandidate
import io.aaps.copilot.domain.meal.MealProspectiveFrame
import io.aaps.copilot.domain.meal.MealProspectiveRegistration
import io.aaps.copilot.domain.meal.MealProspectiveRegistrationStatus
import io.aaps.copilot.domain.meal.MealProspectiveValue
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucoseMeasurementBasis
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** On-demand research receipt. No DAO, control state, dispatcher or app subscription. */
internal object MealProspectiveCapture {
    suspend fun register(snapshot: MealAcceptedRuntimeSnapshot, currentIdentity: MealForecastIdentity,
        candidate: MealProspectiveCandidate?, clockMs: () -> Long): MealProspectiveRegistration {
        currentCoroutineContext().ensureActive()
        val simulation = snapshot.simulation
        val glucose = simulation.glucose
        val basisMatches = when (currentIdentity.basis) {
            MealForecastBasis.LOCAL_ENGINE -> simulation.glucoseBasis != GlucoseMeasurementBasis.UNKNOWN &&
                currentIdentity.glucoseBasisId == simulation.glucoseBasis.id
            MealForecastBasis.ACCEPTED_CONTROL -> currentIdentity.glucoseBasisId == GlucoseMeasurementBasis.CONTEMPORANEOUS_CALIBRATED.id
        }
        val identityMatches = basisMatches && currentIdentity.predictionAtMs == glucose.last().ts &&
            currentIdentity.forecastCycleId == simulation.forecastCycleId &&
            currentIdentity.settingsRevision == simulation.settingsRevision &&
            currentIdentity.acceptedForecastDigest == snapshot.forecastDigest &&
            currentIdentity.sensorSessionKey == snapshot.calibrationIdentity.sensorSessionKey &&
            currentIdentity.calibrationModelId == snapshot.calibrationIdentity.modelId &&
            glucose.all { it.source == currentIdentity.source && it.quality == DataQuality.OK }
        if (!identityMatches) return MealProspectiveRegistration(MealProspectiveRegistrationStatus.CONTEXT_MISMATCH)
        val baseline = when (currentIdentity.basis) {
            MealForecastBasis.LOCAL_ENGINE -> simulation.baselineForecasts
            MealForecastBasis.ACCEPTED_CONTROL -> snapshot.controlForecasts
        }.map { MealProspectiveValue(it.horizonMinutes, it.ts, it.valueMmol, it.ciLow, it.ciHigh) }
        currentCoroutineContext().ensureActive()
        return MealProspectiveFrame.register(currentIdentity, simulation.capturedAtMs, baseline, candidate, clockMs())
    }
}
