package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.domain.model.*
import io.aaps.copilot.domain.predict.*
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MealInputObservationRepositoryTest {
    private val now = 1_800_000_000_000L
    private val gson = Gson()
    private val zone = ZoneId.of("Asia/Tbilisi")
    private val raw = GlucosePoint(now, 6.0, "sensor")
    private val resolved = ResolvedGlucosePoint(now, 6.0, 6.0, 1.0, 0.0, false, "sensor", DataQuality.OK)
    private val calibration = GlucoseCalibrationCycleIdentity(null, "session-a", now)
    private val kernel = MealInsulinKernelIdentity("a".repeat(64), 360)
    private val original = InsulinRuntimeSnapshotResolver.resolve(now,
        InsulinComponentTelemetry(TimedInsulinValue(1.8, now), TimedInsulinValue(2.0, now),
            TimedInsulinValue(-0.2, now), TimedInsulinValue(0.01, now))).snapshot!!
    private fun context(snapshot: InsulinRuntimeSnapshot? = original) = AutomationRepository.InsulinCycleContext(
        now, now, snapshot, snapshot?.copy(timestamp = now - 1000, netIobUnits = 1.7), 2.0, 1.8, 0.0, false, "aligned-for-control")

    private fun tuple(anchor: Long = now, cycle: String = "cycle-a"): AcceptedSensitivityRoomTuple {
        fun metric(value: Double) = SensitivityMetricDecision(SensitivitySourcePreference.COPILOT,
            SensitivityResolvedSource.COPILOT_NATIVE, null, null, value, null, value, 1.0, null)
        val snapshot = SensitivityRuntimeSnapshot(7, cycle, anchor, metric(3.0), metric(10.0))
        val rows = listOf(5, 30, 60).map { ForecastEntity(timestamp = anchor + it * 60_000L,
            horizonMinutes = it, valueMmol = 6.0, ciLow = 5.0, ciHigh = 7.0, modelVersion = "accepted-v1") }
        val decomposition = SensitivityAcceptedForecastDecomposition.unavailable()
        val digest = requireNotNull(SensitivityAcceptedForecastDigest.compute(cycle, 7, rows.map {
            SensitivityAcceptedForecastRow(it.horizonMinutes, it.timestamp, it.valueMmol, it.ciLow, it.ciHigh, it.modelVersion)
        }, decomposition))
        return AcceptedSensitivityRoomTuple(snapshot, snapshot.toEntity(), rows,
            AcceptedForecastTuple(snapshot, rows.associateBy { it.horizonMinutes }, anchor, digest,
                decomposition, calibrationSessionKey = "session-a"), null, anchor + 10)
    }
    private fun row(cycle: String = "cycle-a", insulin: InsulinRuntimeSnapshot? = original) =
        MealInputObservationRepository.prepare(tuple(cycle = cycle), raw, resolved, context(insulin), kernel,
            calibration, true, now + 20, zone, gson).copy(finalizedAtMs = now + 30)
    private suspend fun withDb(block: suspend (CopilotDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
        try { block(db) } finally { db.close() }
    }

    @Test fun capturesOriginalNotAlignedInsulinAndExplicitMeasurementBasis() {
        val row = row()
        val payload = gson.fromJson(row.payloadJson, MealInputObservationPayload::class.java)
        assertEquals(GlucoseMeasurementBasis.RAW.id, row.glucoseBasisId)
        assertEquals(original, payload.originalInsulin)
        assertEquals(now - 1000, payload.alignedInsulinTimestampMs)
        assertEquals(raw, payload.rawGlucose)
        assertEquals(resolved, payload.resolvedGlucose)
        assertEquals(3.0, payload.isfMmolPerUnit, 0.0)
        assertEquals(kernel, payload.kernel)
    }

    @Test fun calibratedRawAndResolvedValuesRemainSeparateWithTheAppliedIdentity() = runBlocking { withDb { db ->
        val model = GlucoseCalibrationModel("model-a", "session-a", now, now - 60_000, now + 60_000,
            GlucoseCalibrationModelType.OFFSET, 1.0, 1.0, 0.9, 3, null, null, GlucoseCalibrationModelStatus.ACTIVE, "{}")
        val base = tuple()
        val accepted = base.copy(calibrationModel = model,
            accepted = base.accepted.copy(calibrationModel = model))
        val identity = calibration.copy(modelId = model.id)
        val point = resolved.copy(calibratedMmol = 7.0, offsetMmolApplied = 1.0,
            calibrationApplied = true, calibrationModelId = model.id)
        val receipt = MealInputObservationRepository.prepare(accepted, raw, point, context(), kernel, identity,
            true, now + 20, zone, gson).copy(finalizedAtMs = now + 30)
        assertEquals(GlucoseMeasurementBasis.CONTEMPORANEOUS_CALIBRATED.id, receipt.glucoseBasisId)
        val payload = gson.fromJson(receipt.payloadJson, MealInputObservationPayload::class.java)
        assertEquals(6.0, payload.rawGlucose.valueMmol, 0.0)
        assertEquals(7.0, payload.resolvedGlucose.calibratedMmol, 0.0)
        db.mealInputObservationDao().insert(receipt)
        assertEquals(1, MealInputObservationRepository(db, gson).review(now + 100).usableGlucoseSamples)
        db.mealInputObservationDao().insert(receipt.copy(cycleId = "wrong-basis", glucoseBasisId = GlucoseMeasurementBasis.RAW.id))
        assertEquals(1, MealInputObservationRepository(db, gson).review(now + 100).issues["INVALID_ROW"])
    } }

    @Test fun acceptedIdentityGenerationAndRawObservationMustMatch() {
        for (tuple in listOf(tuple().copy(accepted = tuple().accepted.copy(forecastDigest = "wrong")),
            tuple().copy(snapshotEntity = tuple().snapshotEntity.copy(settingsRevision = 8)))) assertThrows(IllegalArgumentException::class.java) {
            MealInputObservationRepository.prepare(tuple, raw, resolved, context(), kernel, calibration, true, now + 20, zone, gson)
        }
        for (bad in listOf(resolved.copy(ts = now - 1), resolved.copy(rawMmol = 5.0), resolved.copy(source = "other")))
            assertThrows(IllegalArgumentException::class.java) {
                MealInputObservationRepository.prepare(tuple(), raw, bad, context(), kernel, calibration, true, now + 20, zone, gson)
            }
    }

    @Test fun fallbackProjectedMissingAndMalformedSnapshotsCannotBeIndependentSamples() {
        assertTrue(MealInputObservationRepository.independentInsulin(original, now + 20))
        for (bad in listOf(null, original.copy(source = InsulinRuntimeSource.LOCAL_ESTIMATE),
            original.copy(timestamp = now - 1000), original.copy(timestamp = now + 21),
            original.copy(insulinActivity = null), original.copy(insulinActivity = Double.NaN),
            original.copy(therapyCoverage = 0.5), original.copy(evidenceTimestamp = null),
            original.copy(netIobUnits = 1.0), original.copy(effectivePositiveIobUnits = 1.0)))
            assertFalse(MealInputObservationRepository.independentInsulin(bad, now + 20))
        assertFalse(MealInputObservationRepository.independentInsulin(original, now + 300_001))
    }

    @Test fun duplicateCyclesNeverOverwriteReceiptOrClinicalRows() = runBlocking { withDb { db ->
        var reads = 0
        val repo = MealInputObservationRepository(db, gson, { now + 20 + ++reads }, { zone })
        assertTrue(repo.record(tuple(), raw, resolved, context(), kernel, calibration, true))
        val before = db.mealInputObservationDao().between(0, now + 100, 10).single()
        assertTrue(before.finalizedAtMs!! >= before.observedAtMs)
        assertFalse(repo.record(tuple(), raw, resolved, context(null), kernel, calibration, false))
        assertEquals(before, db.mealInputObservationDao().between(0, now + 100, 10).single())
        val review = repo.review(now + 100)
        assertEquals(1, review.usableGlucoseSamples)
        assertEquals(1, review.independentInsulinSamples)
        assertEquals(0, review.qualifiedEpisodes)
        assertFalse(review.clinicalInfluenceAllowed)
        assertTrue(db.glucoseDao().since(0).isEmpty())
        assertTrue(db.forecastDao().since(0).isEmpty())
        assertTrue(db.therapyDao().since(0).isEmpty())
        assertTrue(db.mealForecastJournalDao().between(0, now + 100, 10).isEmpty())
    } }

    @Test fun clockRollbackAndInterruptedCommitCannotBePromotedByRetry() = runBlocking { withDb { db ->
        var reads = 0
        val repo = MealInputObservationRepository(db, gson, { if (++reads == 1) now + 20 else now + 19 }, { zone })
        assertFalse(repo.record(tuple(), raw, resolved, context(), kernel, calibration, true))
        assertNull(db.mealInputObservationDao().between(0, now + 100, 10).single().finalizedAtMs)
        val later = MealInputObservationRepository(db, gson, { now + 100 }, { zone })
        assertFalse(later.record(tuple(), raw, resolved, context(), kernel, calibration, true))
        assertEquals(1, later.review(now + 100).issues["UNFINISHED"])
        var clockReads = 0
        val cancelled = MealInputObservationRepository(db, gson, { clockReads++; now + 100 }, { zone })
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Job().apply { cancel() }) {
                cancelled.record(tuple(cycle = "cancelled-cycle"), raw, resolved, context(), kernel, calibration, true)
            }
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
        assertEquals(0, clockReads)
    } }

    @Test fun repeatedSamplesHaveOneVoteAndConflictsChooseNoWinner() = runBlocking { withDb { db ->
        val dao = db.mealInputObservationDao()
        dao.insert(row())
        dao.insert(row("cycle-b"))
        val repo = MealInputObservationRepository(db, gson)
        val duplicate = repo.review(now + 100)
        assertEquals(2, duplicate.completeReceipts)
        assertEquals(1, duplicate.usableGlucoseSamples)
        assertEquals(1, duplicate.independentInsulinSamples)
        assertEquals(1, duplicate.issues["DUPLICATE_GLUCOSE"])
        val changed = row("cycle-c", original.copy(insulinActivity = 0.02))
        val payload = gson.fromJson(changed.payloadJson, MealInputObservationPayload::class.java)
        dao.insert(changed.copy(payloadJson = gson.toJson(payload.copy(rawGlucose = raw.copy(valueMmol = 7.0),
            resolvedGlucose = resolved.copy(rawMmol = 7.0, calibratedMmol = 7.0)))))
        val conflict = repo.review(now + 100)
        assertEquals(0, conflict.usableGlucoseSamples)
        assertEquals(0, conflict.independentInsulinSamples)
        assertEquals(1, conflict.issues["CONFLICTING_GLUCOSE"])
        assertEquals(1, conflict.issues["CONFLICTING_INSULIN"])
    } }

    @Test fun staleUnknownQualityAndCorruptedRowsStayExplicitlyUnusable() = runBlocking { withDb { db ->
        val dao = db.mealInputObservationDao()
        val valid = row()
        val payload = gson.fromJson(valid.payloadJson, MealInputObservationPayload::class.java)
        dao.insert(valid.copy(payloadJson = gson.toJson(payload.copy(sensorTrusted = false))))
        dao.insert(row("cycle-b").copy(payloadJson = "{}"))
        dao.insert(row("cycle-c").copy(glucoseBasisId = "invented-basis"))
        val result = MealInputObservationRepository(db, gson).review(now + 100)
        assertEquals(0, result.usableGlucoseSamples)
        assertEquals(2, result.issues["INVALID_ROW"])
        assertEquals(1, result.issues["UNQUALIFIED_GLUCOSE"])
    } }

    @Test fun staleAndMissingSessionOrModelContextAreNotUpgraded() = runBlocking { withDb { db ->
        val base = row()
        val payload = gson.fromJson(base.payloadJson, MealInputObservationPayload::class.java)
        db.mealInputObservationDao().insert(base.copy(observedAtMs = now + 300_001, finalizedAtMs = now + 300_002,
            payloadJson = gson.toJson(payload.copy(kernel = null, isfConfidence = 0.0))))
        val review = MealInputObservationRepository(db, gson).review(now + 400_000)
        assertEquals(0, review.usableGlucoseSamples)
        assertEquals(0, review.independentInsulinSamples)
        assertEquals(1, review.issues["UNQUALIFIED_MODEL_CONTEXT"])
        assertEquals(0, review.qualifiedEpisodes)
    } }

    @Test fun laterStaleReplayCannotEraseAnEarlierContemporaneouslyUsableObservation() = runBlocking { withDb { db ->
        val first = row()
        val payload = gson.fromJson(first.payloadJson, MealInputObservationPayload::class.java)
        db.mealInputObservationDao().insert(first)
        db.mealInputObservationDao().insert(first.copy(cycleId = "stale-replay", observedAtMs = now + 600_000,
            finalizedAtMs = now + 600_001, payloadJson = gson.toJson(payload.copy(sensorTrusted = false))))
        val review = MealInputObservationRepository(db, gson).review(now + 700_000)
        assertEquals(1, review.usableGlucoseSamples)
        assertEquals(1, review.independentInsulinSamples)
        assertEquals(1, review.issues["UNQUALIFIED_GLUCOSE"])
        assertNull(review.issues["CONFLICTING_GLUCOSE"])
    } }

    @Test fun missingSessionAndUnknownTransformStayUnknownEvenWithACompleteReceipt() = runBlocking { withDb { db ->
        val receipt = row()
        val payload = gson.fromJson(receipt.payloadJson, MealInputObservationPayload::class.java)
        val missing = receipt.copy(sensorSessionKey = null, calibrationModelId = "unapplied-model",
            glucoseBasisId = GlucoseMeasurementBasis.UNKNOWN.id, payloadJson = gson.toJson(payload.copy(
                calibration = calibration.copy(sensorSessionKey = null, modelId = "unapplied-model"))))
        db.mealInputObservationDao().insert(missing)
        val review = MealInputObservationRepository(db, gson).review(now + 100)
        assertEquals(1, review.completeReceipts)
        assertEquals(0, review.usableGlucoseSamples)
        assertEquals(1, review.issues["MISSING_SENSOR_SESSION"])
        assertEquals(1, review.issues["UNKNOWN_MEASUREMENT_BASIS"])
        assertEquals(0, review.qualifiedEpisodes)
    } }

    @Test fun retentionDeletesOnlyItsOwnRowsAndOverflowRefusesPartialCoverage() = runBlocking { withDb { db ->
        val dao = db.mealInputObservationDao()
        db.openHelper.writableDatabase.execSQL("INSERT INTO glucose_samples (timestamp,mmol,source,quality) VALUES (100,6.0,'sensor','OK')")
        dao.insert(row().copy(cycleId = "expired", observedAtMs = now - MealInputObservationRepository.RETENTION_MS - 1))
        val repo = MealInputObservationRepository(db, gson, { now + 100 }, { zone })
        assertTrue(repo.record(tuple(), raw, resolved, context(), kernel, calibration, true))
        assertEquals(listOf("cycle-a"), dao.between(0, now + 200, 10).map { it.cycleId })
        assertEquals(6.0, db.glucoseDao().since(0).single().mmol, 0.0)
        for (i in 1..MealInputObservationRepository.MAX_ROWS) dao.insert(row("overflow-$i"))
        val review = repo.review(now + 200)
        assertTrue(review.overflow)
        assertEquals(0, review.usableGlucoseSamples)
        assertEquals(0, review.qualifiedEpisodes)
    } }
}
