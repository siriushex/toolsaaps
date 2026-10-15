package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.MealInputObservationEntity
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucoseCalibrationCycleIdentity
import io.aaps.copilot.domain.model.GlucoseMeasurementBasis
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.ResolvedGlucosePoint
import io.aaps.copilot.domain.predict.InsulinRuntimeSnapshot
import io.aaps.copilot.domain.predict.InsulinRuntimeSnapshotResolver
import io.aaps.copilot.domain.predict.InsulinRuntimeSource
import io.aaps.copilot.domain.predict.MealInsulinKernelIdentity
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Original accepted-cycle observations, not complete delivery or absorption episodes. */
internal data class MealInputObservationPayload(
    val version: Int,
    val rawGlucose: GlucosePoint,
    val resolvedGlucose: ResolvedGlucosePoint,
    val calibration: GlucoseCalibrationCycleIdentity,
    val sensorTrusted: Boolean,
    val originalInsulin: InsulinRuntimeSnapshot?,
    val alignedInsulinTimestampMs: Long?,
    val alignmentReason: String,
    val kernel: MealInsulinKernelIdentity?,
    val isfMmolPerUnit: Double,
    val crGramsPerUnit: Double,
    val isfConfidence: Double,
    val crConfidence: Double,
    val isfSource: String,
    val crSource: String,
    val sensitivityKnownAtMs: Long
)

internal data class MealInputObservationReview(val rows: Int, val completeReceipts: Int,
    val usableGlucoseSamples: Int, val independentInsulinSamples: Int,
    val issues: Map<String, Int>, val overflow: Boolean = false) {
    val qualifiedEpisodes: Int get() = 0
    val clinicalInfluenceAllowed: Boolean get() = false
}

/** Passive bounded collection after clinical fan-out; no model fit or action dependency. */
internal class MealInputObservationRepository(
    private val db: CopilotDatabase, private val gson: Gson,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault
) {
    suspend fun record(tuple: AcceptedSensitivityRoomTuple, rawGlucose: GlucosePoint,
        resolvedGlucose: ResolvedGlucosePoint, insulin: AutomationRepository.InsulinCycleContext,
        kernel: MealInsulinKernelIdentity?, calibration: GlucoseCalibrationCycleIdentity,
        sensorTrusted: Boolean): Boolean = withContext(Dispatchers.IO) {
        val at = clockMs()
        val row = prepare(tuple, rawGlucose, resolvedGlucose, insulin, kernel, calibration, sensorTrusted, at, zone(), gson)
        val inserted = db.withTransaction {
            db.mealInputObservationDao().deleteOlderThan(maxOf(0, at - RETENTION_MS))
            db.mealInputObservationDao().insert(row)
        }
        if (inserted == -1L) return@withContext false
        currentCoroutineContext().ensureActive()
        val committed = clockMs()
        if (committed < at) return@withContext false
        db.mealInputObservationDao().finalize(row.cycleId, committed) == 1
    }

    suspend fun review(atMs: Long): MealInputObservationReview = withContext(Dispatchers.IO) {
        require(atMs > 0)
        val rows = db.withTransaction {
            db.mealInputObservationDao().between(maxOf(0, atMs - RETENTION_MS), atMs, MAX_ROWS + 1)
        }
        if (rows.size > MAX_ROWS) return@withContext MealInputObservationReview(rows.size, 0, 0, 0, emptyMap(), true)
        var complete = 0
        val glucoseVersions = linkedMapOf<List<Any?>, MutableSet<List<Any?>>>()
        val usableGlucoseKeys = mutableSetOf<List<Any?>>()
        val insulinVersions = linkedMapOf<Long, MutableSet<InsulinRuntimeSnapshot>>()
        val usableInsulinTimes = mutableSetOf<Long>()
        val issues = sortedMapOf<String, Int>()
        fun issue(reason: String) { issues[reason] = (issues[reason] ?: 0) + 1 }
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            val committed = row.finalizedAtMs
            if (committed == null || committed < row.observedAtMs || committed > atMs) { issue("UNFINISHED"); continue }
            val payload = try {
                require(row.payloadJson.length <= MAX_PAYLOAD)
                gson.fromJson(row.payloadJson, MealInputObservationPayload::class.java).also {
                    require(it.version == 1 && it.rawGlucose.ts == row.predictionAtMs &&
                        row.predictionAtMs > 0 && row.source.isNotBlank() && row.source.length <= 512 &&
                        it.resolvedGlucose.ts == row.predictionAtMs && it.rawGlucose.source == row.source &&
                        it.resolvedGlucose.source == row.source && it.rawGlucose.valueMmol == it.resolvedGlucose.rawMmol &&
                        it.rawGlucose.valueMmol.isFinite() && it.rawGlucose.valueMmol in 0.1..100.0 &&
                        it.resolvedGlucose.calibratedMmol.isFinite() && it.resolvedGlucose.calibratedMmol in 0.1..100.0 &&
                        it.calibration.modelId == row.calibrationModelId &&
                        it.calibration.sensorSessionKey == row.sensorSessionKey &&
                        GlucoseMeasurementBasis.fromResolved(it.resolvedGlucose, it.calibration).id == row.glucoseBasisId)
                }
            } catch (_: IllegalArgumentException) { null
            } catch (_: com.google.gson.JsonParseException) { null
            } catch (_: NullPointerException) { null }
            if (payload == null) { issue("INVALID_ROW"); continue }
            complete++
            val basis = GlucoseMeasurementBasis.fromResolved(payload.resolvedGlucose, payload.calibration)
            if (basis == GlucoseMeasurementBasis.UNKNOWN) issue("UNKNOWN_MEASUREMENT_BASIS")
            if (row.sensorSessionKey.isNullOrBlank()) issue("MISSING_SENSOR_SESSION")
            val glucoseKey = listOf(row.predictionAtMs, row.source, row.sensorSessionKey, row.calibrationModelId, row.glucoseBasisId)
            // Context trust can age on a replay without revising the original measurement.
            val signature = listOf(payload.rawGlucose.valueMmol, payload.rawGlucose.quality,
                payload.resolvedGlucose.calibratedMmol, payload.resolvedGlucose.quality,
                payload.resolvedGlucose.gain, payload.resolvedGlucose.offsetMmolApplied)
            val versions = glucoseVersions.getOrPut(glucoseKey) { mutableSetOf() }
            if (!versions.add(signature)) issue("DUPLICATE_GLUCOSE")
            if (payload.sensorTrusted && payload.rawGlucose.quality == DataQuality.OK &&
                payload.resolvedGlucose.quality == DataQuality.OK && basis != GlucoseMeasurementBasis.UNKNOWN &&
                !row.sensorSessionKey.isNullOrBlank() && row.predictionAtMs > 0 &&
                row.observedAtMs - row.predictionAtMs in 0..300_000L) usableGlucoseKeys += glucoseKey
            else issue("UNQUALIFIED_GLUCOSE")
            val original = payload.originalInsulin
            if (original?.source == InsulinRuntimeSource.AAPS_COMPONENTS && original.timestamp > 0)
                insulinVersions.getOrPut(original.timestamp) { mutableSetOf() }.add(original)
            if (independentInsulin(original, row.observedAtMs)) usableInsulinTimes += original!!.timestamp
            else issue("MISSING_OR_DERIVED_INSULIN")
            if (!KERNEL_ID.matches(payload.kernel?.id.orEmpty()) ||
                payload.kernel?.durationMinutes?.let { it in 30..720 } != true ||
                payload.isfSource.isNullOrBlank() || payload.crSource.isNullOrBlank() ||
                payload.isfConfidence !in 0.7..1.0 || payload.crConfidence !in 0.7..1.0 ||
                !payload.isfMmolPerUnit.isFinite() || payload.isfMmolPerUnit <= 0 ||
                !payload.crGramsPerUnit.isFinite() || payload.crGramsPerUnit <= 0 ||
                payload.sensitivityKnownAtMs !in 1..row.observedAtMs)
                issue("UNQUALIFIED_MODEL_CONTEXT")
        }
        val glucose = glucoseVersions.count { (key, versions) ->
            if (versions.size > 1) issue("CONFLICTING_GLUCOSE")
            versions.size == 1 && key in usableGlucoseKeys
        }
        val independent = insulinVersions.count { (time, versions) ->
            if (versions.size > 1) issue("CONFLICTING_INSULIN")
            versions.size == 1 && time in usableInsulinTimes
        }
        MealInputObservationReview(rows.size, complete, glucose, independent, issues.toMap())
    }

    companion object {
        const val RETENTION_MS = 21 * 86_400_000L
        const val MAX_ROWS = 7_000
        private const val MAX_PAYLOAD = 16_384
        private val KERNEL_ID = Regex("[a-f0-9]{64}")

        internal fun independentInsulin(snapshot: InsulinRuntimeSnapshot?, observedAtMs: Long): Boolean {
            snapshot ?: return false
            if (snapshot.source != InsulinRuntimeSource.AAPS_COMPONENTS || snapshot.evidenceTimestamp != snapshot.timestamp ||
                snapshot.timestamp <= 0 || snapshot.timestamp > observedAtMs || observedAtMs - snapshot.timestamp > 300_000L ||
                snapshot.confidence != 1.0 || snapshot.therapyCoverage != 1.0 || snapshot.fallbackReason != null) return false
            val bolus = snapshot.bolusIobUnits ?: return false
            val basal = snapshot.basalIobUnits ?: return false
            val activity = snapshot.insulinActivity ?: return false
            val resolved = InsulinRuntimeSnapshotResolver.resolve(observedAtMs,
                io.aaps.copilot.domain.predict.InsulinComponentTelemetry(
                    io.aaps.copilot.domain.predict.TimedInsulinValue(snapshot.netIobUnits, snapshot.timestamp),
                    io.aaps.copilot.domain.predict.TimedInsulinValue(bolus, snapshot.timestamp),
                    io.aaps.copilot.domain.predict.TimedInsulinValue(basal, snapshot.timestamp),
                    io.aaps.copilot.domain.predict.TimedInsulinValue(activity, snapshot.timestamp))).snapshot
            return resolved == snapshot
        }

        internal fun prepare(tuple: AcceptedSensitivityRoomTuple, rawGlucose: GlucosePoint,
            resolvedGlucose: ResolvedGlucosePoint, insulin: AutomationRepository.InsulinCycleContext,
            kernel: MealInsulinKernelIdentity?, calibration: GlucoseCalibrationCycleIdentity,
            sensorTrusted: Boolean, atMs: Long, zone: ZoneId, gson: Gson): MealInputObservationEntity {
            require(tuple.snapshotEntity == tuple.snapshot.toEntity() && tuple.accepted.sensitivity == tuple.snapshot)
            require(calibration.preparedAtTs in 1..tuple.acceptedAtTs && calibration.modelId == tuple.calibrationModel?.id &&
                calibration.sensorSessionKey == tuple.accepted.calibrationSessionKey && tuple.calibrationModel == tuple.accepted.calibrationModel)
            val accepted = AutomationRepository.requireAcceptedClinicalForecastsStatic(tuple)
            require(accepted.generationTimestamp == rawGlucose.ts && resolvedGlucose.ts == rawGlucose.ts &&
                tuple.acceptedAtTs in rawGlucose.ts..atMs && insulin.cycleTimestamp in rawGlucose.ts..atMs &&
                insulin.causalReferenceTimestamp == rawGlucose.ts)
            require(rawGlucose.valueMmol.isFinite() && rawGlucose.valueMmol in 0.1..100.0 &&
                rawGlucose.source.isNotBlank() && rawGlucose.source.length <= 512 &&
                resolvedGlucose.source == rawGlucose.source && resolvedGlucose.rawMmol == rawGlucose.valueMmol &&
                resolvedGlucose.calibratedMmol.isFinite() && resolvedGlucose.calibratedMmol in 0.1..100.0)
            val payload = MealInputObservationPayload(1, rawGlucose, resolvedGlucose, calibration, sensorTrusted,
                insulin.snapshot, insulin.alignedSnapshot?.timestamp, insulin.residualComparisonReason.take(128), kernel,
                tuple.snapshot.isf.effective, tuple.snapshot.cr.effective, tuple.snapshot.isf.confidence,
                tuple.snapshot.cr.confidence, tuple.snapshot.isf.resolved.name, tuple.snapshot.cr.resolved.name, tuple.snapshot.timestamp)
            val encoded = gson.toJson(payload)
            require(encoded.length <= MAX_PAYLOAD)
            return MealInputObservationEntity(tuple.snapshot.forecastCycleId, rawGlucose.ts, atMs, null,
                tuple.snapshot.settingsRevision, accepted.digest, rawGlucose.source, calibration.sensorSessionKey,
                calibration.modelId, GlucoseMeasurementBasis.fromResolved(resolvedGlucose, calibration).id, zone.id, encoded)
        }
    }
}
