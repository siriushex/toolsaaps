package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.meal.MealSimulationContext
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucoseCalibrationCycleIdentity
import io.aaps.copilot.domain.model.GlucoseCalibrationModel
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.domain.predict.PredictionEngine
import io.aaps.copilot.domain.predict.SensitivityRuntimeSnapshot
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal enum class MealRuntimeUnavailableReason {
    CYCLE_PENDING,
    UNSUPPORTED_ENGINE,
    INVALID_ACCEPTED_CONTEXT
}

internal sealed interface MealRuntimeUpdate {
    data class Captured(val snapshot: MealAcceptedRuntimeSnapshot) : MealRuntimeUpdate
    data class Unavailable(val reason: MealRuntimeUnavailableReason) : MealRuntimeUpdate
}

/** Research authority only: accepted control output is not scenario calibration. */
internal class MealAcceptedRuntimeSnapshot private constructor(
    val simulation: MealSimulationContext,
    val controlForecasts: List<Forecast>,
    val forecastDigest: String,
    val acceptedAtMs: Long,
    val calibrationIdentity: GlucoseCalibrationCycleIdentity,
    val calibrationModel: GlucoseCalibrationModel?
) {
    companion object {
        suspend fun capture(
            engine: HybridPredictionEngine,
            glucose: List<GlucosePoint>,
            therapy: List<TherapyEvent>,
            localForecasts: List<Forecast>,
            sourceSensitivity: SensitivityRuntimeSnapshot,
            sourceCalibration: GlucoseCalibrationCycleIdentity,
            roomTuple: AcceptedSensitivityRoomTuple,
            capturedAtMs: Long
        ): MealAcceptedRuntimeSnapshot {
            require(sourceSensitivity == roomTuple.snapshot &&
                roomTuple.snapshotEntity == sourceSensitivity.toEntity() &&
                roomTuple.accepted.sensitivity == sourceSensitivity)
            require(roomTuple.acceptedAtTs > 0 && roomTuple.acceptedAtTs <= capturedAtMs &&
                capturedAtMs - roomTuple.acceptedAtTs <= 300_000L)
            require(sourceSensitivity.timestamp > 0 && sourceSensitivity.timestamp <= roomTuple.acceptedAtTs)
            require(sourceCalibration.preparedAtTs > 0 &&
                sourceCalibration.preparedAtTs <= roomTuple.acceptedAtTs)
            require(sourceCalibration.modelId == roomTuple.calibrationModel?.id &&
                sourceCalibration.sensorSessionKey == roomTuple.accepted.calibrationSessionKey &&
                roomTuple.calibrationModel == roomTuple.accepted.calibrationModel)
            roomTuple.calibrationModel?.let {
                require(it.sensorSessionKey == sourceCalibration.sensorSessionKey)
            }
            require(roomTuple.forecasts.size == 3 && roomTuple.accepted.forecastsByHorizon.size == 3 &&
                roomTuple.forecasts.sortedBy { it.horizonMinutes } ==
                roomTuple.accepted.forecastsByHorizon.values.sortedBy { it.horizonMinutes })
            val authority = AutomationRepository.requireAcceptedClinicalForecastsStatic(roomTuple)
            require(authority.generationTimestamp == glucose.lastOrNull()?.ts)
            require(authority.forecasts.all {
                it.ts == authority.generationTimestamp + it.horizonMinutes * 60_000L &&
                    it.valueMmol.isFinite() && it.ciLow.isFinite() && it.ciHigh.isFinite() &&
                    it.ciLow <= it.valueMmol && it.valueMmol <= it.ciHigh
            })
            val simulation = MealSimulationContext.capture(engine, glucose, therapy, localForecasts,
                sourceSensitivity.forecastCycleId, sourceSensitivity.settingsRevision, capturedAtMs)
            return MealAcceptedRuntimeSnapshot(simulation,
                Collections.unmodifiableList(authority.forecasts.toList()), authority.digest,
                roomTuple.acceptedAtTs, sourceCalibration, roomTuple.calibrationModel)
        }
    }
}

/** Called only by the owner of AutomationRepository's cycle lock. */
internal class MealRuntimeCaptureRelay(private val clockMs: () -> Long = System::currentTimeMillis) {
    private val mutableUpdates = MutableSharedFlow<MealRuntimeUpdate>(replay = 0,
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val updates: SharedFlow<MealRuntimeUpdate> = mutableUpdates.asSharedFlow()

    fun invalidate() {
        mutableUpdates.tryEmit(MealRuntimeUpdate.Unavailable(MealRuntimeUnavailableReason.CYCLE_PENDING))
    }

    suspend fun publish(
        engine: PredictionEngine,
        glucose: List<GlucosePoint>,
        therapy: List<TherapyEvent>,
        localForecasts: List<Forecast>,
        sourceSensitivity: SensitivityRuntimeSnapshot,
        sourceCalibration: GlucoseCalibrationCycleIdentity,
        roomTuple: AcceptedSensitivityRoomTuple
    ) {
        if (mutableUpdates.subscriptionCount.value == 0) return
        val update = if (engine !is HybridPredictionEngine) {
            MealRuntimeUpdate.Unavailable(MealRuntimeUnavailableReason.UNSUPPORTED_ENGINE)
        } else try {
            MealRuntimeUpdate.Captured(MealAcceptedRuntimeSnapshot.capture(engine, glucose, therapy,
                localForecasts, sourceSensitivity, sourceCalibration, roomTuple, clockMs()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            MealRuntimeUpdate.Unavailable(MealRuntimeUnavailableReason.INVALID_ACCEPTED_CONTEXT)
        }
        currentCoroutineContext().ensureActive()
        mutableUpdates.tryEmit(update)
    }
}
