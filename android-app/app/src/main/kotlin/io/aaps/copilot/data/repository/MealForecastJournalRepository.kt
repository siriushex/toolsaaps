package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.MealForecastJournalEntity
import io.aaps.copilot.domain.meal.*
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucoseCalibrationCycleIdentity
import io.aaps.copilot.domain.model.GlucosePoint
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class MealForecastJournalReview(
    val rows: Int, val rejectedFrames: Map<String, Int>,
    val evaluation: MealProspectiveEvaluation?, val overflow: Boolean = false
)

/** Observational writes only, after the cycle's clinical fan-out has completed. */
internal class MealForecastJournalRepository(
    private val db: CopilotDatabase,
    private val gson: Gson,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault
) {
    suspend fun record(tuple: AcceptedSensitivityRoomTuple, observation: GlucosePoint,
        calibration: GlucoseCalibrationCycleIdentity, sensorTrusted: Boolean): Boolean = withContext(Dispatchers.IO) {
        val at = clockMs()
        val row = prepare(tuple, observation, calibration, sensorTrusted, at, zone(), gson)
        val inserted = db.withTransaction {
            db.mealForecastJournalDao().deleteOlderThan(at - RETENTION_MS)
            db.mealForecastJournalDao().insert(row)
        }
        if (inserted == -1L) return@withContext false
        // A crash before this post-commit marker leaves an explicitly unfinished receipt.
        currentCoroutineContext().ensureActive()
        val committedAt = clockMs()
        if (committedAt < at) return@withContext false
        db.mealForecastJournalDao().finalize(row.cycleId, committedAt) == 1
    }

    suspend fun review(atMs: Long): MealForecastJournalReview = withContext(Dispatchers.IO) {
        require(atMs > 0)
        val rows = db.withTransaction {
            db.mealForecastJournalDao().between(maxOf(0, atMs - RETENTION_MS), atMs, MAX_ROWS + 1)
        }
        if (rows.size > MAX_ROWS) return@withContext MealForecastJournalReview(rows.size, emptyMap(), null, true)
        val rejected = linkedMapOf<String, Int>()
        fun reject(reason: String) { rejected[reason] = (rejected[reason] ?: 0) + 1 }
        val frames = mutableListOf<MealProspectiveFrame>()
        val outcomes = mutableListOf<MealProspectiveOutcome>()
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            val committed = row.finalizedAtMs
            if (committed == null || committed < row.observedAtMs || committed > atMs) {
                reject("UNFINISHED"); continue
            }
            val session = row.sensorSessionKey
            if (session.isNullOrBlank()) { reject("MISSING_SESSION"); continue }
            val quality = DataQuality.entries.firstOrNull { it.name == row.quality }
            if (quality == null) { reject("INVALID_ROW"); continue }
            outcomes += MealProspectiveOutcome(row.predictionAtMs, committed, row.glucoseMmol,
                quality, row.source, session, GLUCOSE_BASIS, row.calibrationModelId)
            if (quality != DataQuality.OK) { reject("SENSOR_QUALITY"); continue }
            val registration = try {
                require(row.forecastsJson.length <= 8_192 && row.acceptedAtMs in row.predictionAtMs..row.observedAtMs)
                val identity = MealForecastIdentity(row.predictionAtMs, row.cycleId, row.settingsRevision,
                    row.forecastDigest, row.source, session, GLUCOSE_BASIS, row.calibrationModelId,
                    MealForecastBasis.ACCEPTED_CONTROL, row.recordedZoneId, row.modelVersion)
                val values = gson.fromJson(row.forecastsJson, Array<MealProspectiveValue>::class.java).toList()
                MealProspectiveFrame.register(identity, row.observedAtMs, values, null, committed)
            } catch (_: IllegalArgumentException) {
                reject("INVALID_ROW"); continue
            } catch (_: com.google.gson.JsonParseException) {
                reject("INVALID_ROW"); continue
            } catch (_: NullPointerException) {
                reject("INVALID_ROW"); continue
            }
            registration.frame?.let(frames::add) ?: reject(registration.status.name)
        }
        MealForecastJournalReview(rows.size, rejected.toMap(), MealProspectiveEvaluator().evaluate(
            frames, outcomes, atMs, 3.9))
    }

    companion object {
        const val RETENTION_MS = 21 * 86_400_000L
        const val MAX_ROWS = 7_000
        private const val GLUCOSE_BASIS = "contemporaneous-calibrated-cgm-v1"

        internal fun prepare(tuple: AcceptedSensitivityRoomTuple, observation: GlucosePoint,
            calibration: GlucoseCalibrationCycleIdentity, sensorTrusted: Boolean, atMs: Long,
            zone: ZoneId, gson: Gson): MealForecastJournalEntity {
            require(tuple.snapshotEntity == tuple.snapshot.toEntity() && tuple.accepted.sensitivity == tuple.snapshot)
            require(calibration.preparedAtTs > 0 && calibration.preparedAtTs <= tuple.acceptedAtTs)
            require(calibration.modelId == tuple.calibrationModel?.id &&
                calibration.sensorSessionKey == tuple.accepted.calibrationSessionKey &&
                tuple.calibrationModel == tuple.accepted.calibrationModel)
            val accepted = AutomationRepository.requireAcceptedClinicalForecastsStatic(tuple)
            require(accepted.generationTimestamp == observation.ts && tuple.acceptedAtTs in observation.ts..atMs)
            require(observation.source.isNotBlank() && observation.source.length <= 512 &&
                observation.valueMmol.isFinite() && observation.valueMmol in 0.1..100.0)
            val quality = if (sensorTrusted) observation.quality else DataQuality.SENSOR_ERROR
            return MealForecastJournalEntity(tuple.snapshot.forecastCycleId, observation.ts,
                tuple.acceptedAtTs, atMs, null, tuple.snapshot.settingsRevision, accepted.digest,
                observation.source, calibration.sensorSessionKey, calibration.modelId, zone.id,
                accepted.forecasts.sortedBy { it.horizonMinutes }.joinToString("|") { it.modelVersion },
                observation.valueMmol, quality.name, gson.toJson(accepted.forecasts.map {
                    MealProspectiveValue(it.horizonMinutes, it.ts, it.valueMmol, it.ciLow, it.ciHigh)
                }))
        }
    }
}
