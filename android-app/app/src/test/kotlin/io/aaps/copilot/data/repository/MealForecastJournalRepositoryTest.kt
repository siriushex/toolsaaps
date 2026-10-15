package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.domain.meal.MealProspectiveValue
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
class MealForecastJournalRepositoryTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val gson = Gson()
    private val zone = ZoneId.of("Asia/Tbilisi")

    private fun tuple(anchor: Long = now, cycle: String = "cycle-$anchor"): AcceptedSensitivityRoomTuple {
        fun metric(value: Double) = SensitivityMetricDecision(SensitivitySourcePreference.COPILOT,
            SensitivityResolvedSource.COPILOT_NATIVE, null, null, value, null, value, 1.0, null)
        val snapshot = SensitivityRuntimeSnapshot(7, cycle, anchor, metric(3.0), metric(10.0))
        val rows = listOf(5, 30, 60).map { ForecastEntity(timestamp = anchor + it * minute,
            horizonMinutes = it, valueMmol = 6.0, ciLow = 5.0, ciHigh = 7.0, modelVersion = "accepted-v1") }
        val decomposition = SensitivityAcceptedForecastDecomposition.unavailable()
        val digest = requireNotNull(SensitivityAcceptedForecastDigest.compute(cycle, 7, rows.map {
            SensitivityAcceptedForecastRow(it.horizonMinutes, it.timestamp, it.valueMmol, it.ciLow, it.ciHigh, it.modelVersion)
        }, decomposition))
        return AcceptedSensitivityRoomTuple(snapshot, snapshot.toEntity(), rows,
            AcceptedForecastTuple(snapshot, rows.associateBy { it.horizonMinutes }, anchor, digest,
                decomposition, calibrationSessionKey = "session-a"), null, anchor + 10)
    }

    private fun row(anchor: Long = now, cycle: String = "cycle-$anchor", value: Double = 5.0) =
        MealForecastJournalRepository.prepare(tuple(anchor, cycle), GlucosePoint(anchor, value, "sensor"),
            GlucoseCalibrationCycleIdentity(null, "session-a", anchor), true, anchor + 20, zone, gson)
            .copy(finalizedAtMs = anchor + 30)

    private suspend fun withDb(block: suspend (CopilotDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            CopilotDatabase::class.java).allowMainThreadQueries().build()
        try { block(db) } finally { db.close() }
    }

    @Test fun trustedAcceptedTupleFreezesForecastAndContextWithoutPredictionRerun() {
        val row = row()
        assertEquals(now, row.predictionAtMs)
        assertEquals("session-a", row.sensorSessionKey)
        assertEquals("Asia/Tbilisi", row.recordedZoneId)
        assertEquals(3, gson.fromJson(row.forecastsJson, Array<MealProspectiveValue>::class.java).size)
        assertThrows(IllegalArgumentException::class.java) {
            MealForecastJournalRepository.prepare(tuple().copy(accepted = tuple().accepted.copy(forecastDigest = "wrong")),
                GlucosePoint(now, 5.0, "sensor"), GlucoseCalibrationCycleIdentity(null, "session-a", now),
                true, now + 20, zone, gson)
        }
    }

    @Test fun mismatchedCalibrationOrGenerationCannotWriteJournal() {
        for (calibration in listOf(GlucoseCalibrationCycleIdentity(null, "other", now),
            GlucoseCalibrationCycleIdentity("other-model", "session-a", now))) {
            assertThrows(IllegalArgumentException::class.java) {
                MealForecastJournalRepository.prepare(tuple(), GlucosePoint(now, 5.0, "sensor"),
                    calibration, true, now + 20, zone, gson)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            MealForecastJournalRepository.prepare(tuple(), GlucosePoint(now - minute, 5.0, "sensor"),
                GlucoseCalibrationCycleIdentity(null, "session-a", now), true, now + 20, zone, gson)
        }
    }

    @Test fun recordHasPostCommitMarkerAndRepeatsCannotOverwrite() = runBlocking { withDb { db ->
        var clockReads = 0
        val repository = MealForecastJournalRepository(db, gson, { now + 20 + (++clockReads) }, { zone })
        val calibration = GlucoseCalibrationCycleIdentity(null, "session-a", now)
        assertTrue(repository.record(tuple(), GlucosePoint(now, 5.0, "sensor"), calibration, true))
        val original = db.mealForecastJournalDao().between(0, now + minute, 10).single()
        assertTrue(original.finalizedAtMs!! > original.observedAtMs)
        assertFalse(repository.record(tuple(), GlucosePoint(now, 9.0, "sensor"), calibration, true))
        assertEquals(original, db.mealForecastJournalDao().between(0, now + minute, 10).single())
        assertTrue(db.forecastDao().since(0).isEmpty())
        assertTrue(db.glucoseDao().since(0).isEmpty())
        assertTrue(db.therapyDao().since(0).isEmpty())
    } }

    @Test fun clockRollbackLeavesUnfinishedReceiptNotEligibleAccuracy() = runBlocking { withDb { db ->
        var read = 0
        val repository = MealForecastJournalRepository(db, gson, { if (++read == 1) now + 20 else now + 19 }, { zone })
        assertFalse(repository.record(tuple(), GlucosePoint(now, 5.0, "sensor"),
            GlucoseCalibrationCycleIdentity(null, "session-a", now), true))
        val review = repository.review(now + 61 * minute)
        assertEquals(1, review.rejectedFrames["UNFINISHED"])
        assertTrue(review.evaluation!!.baselineOnlyHorizons.isEmpty())
    } }

    @Test fun cancellationAfterInitialCommitCannotPromoteAnUnfinishedReceipt() = runBlocking { withDb { db ->
        var read = 0
        val repository = MealForecastJournalRepository(db, gson, {
            if (++read == 2) throw CancellationException("interrupted") else now + 20
        }, { zone })
        try {
            repository.record(tuple(), GlucosePoint(now, 5.0, "sensor"),
                GlucoseCalibrationCycleIdentity(null, "session-a", now), true)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertNull(db.mealForecastJournalDao().between(0, now + minute, 10).single().finalizedAtMs)
    } }

    @Test fun onlyFutureExactQualifiedObservationsScoreIncludingLowValues() = runBlocking { withDb { db ->
        listOf(0, 5, 30, 60).forEach { db.mealForecastJournalDao().insert(row(now + it * minute, value = 3.5)) }
        val review = MealForecastJournalRepository(db, gson).review(now + 61 * minute)
        val horizons = review.evaluation!!.baselineOnlyHorizons
        assertEquals(3, horizons.size)
        assertTrue(horizons.all { it.observedSamples > 0 && it.actualLowSamples > 0 &&
            it.baselineMissedLows == it.actualLowSamples && it.candidate == null })
        assertEquals(2.5, horizons.first().baseline!!.maeMmol, 0.0)
        assertFalse(review.evaluation.clinicalInfluenceAllowed)
    } }

    @Test fun lateUnknownSessionBrokenQualityAndCorruptRowsRemainExplicit() = runBlocking { withDb { db ->
        db.mealForecastJournalDao().insert(row().copy(finalizedAtMs = now + 5 * minute))
        db.mealForecastJournalDao().insert(row(now + minute).copy(sensorSessionKey = null))
        db.mealForecastJournalDao().insert(row(now + 2 * minute).copy(quality = "SENSOR_ERROR"))
        db.mealForecastJournalDao().insert(row(now + 3 * minute).copy(forecastsJson = "invalid"))
        db.mealForecastJournalDao().insert(row(now + 4 * minute).copy(forecastsJson = "null"))
        val result = MealForecastJournalRepository(db, gson).review(now + 61 * minute)
        assertEquals(1, result.rejectedFrames["LATE_REGISTRATION"])
        assertEquals(1, result.rejectedFrames["MISSING_SESSION"])
        assertEquals(1, result.rejectedFrames["SENSOR_QUALITY"])
        assertEquals(2, result.rejectedFrames["INVALID_ROW"])
        assertTrue(result.evaluation!!.baselineOnlyHorizons.isEmpty())
    } }

    @Test fun reprocessingConflictingAnchorCannotCherryPickAForecast() = runBlocking { withDb { db ->
        db.mealForecastJournalDao().insert(row())
        db.mealForecastJournalDao().insert(row(cycle = "another-cycle"))
        db.mealForecastJournalDao().insert(row(now + 5 * minute))
        val result = MealForecastJournalRepository(db, gson).review(now + 61 * minute)
        assertEquals(2, result.evaluation!!.conflictingFrames)
        assertTrue(result.evaluation.baselineOnlyHorizons.all { it.observedSamples == 0 })
    } }

    @Test fun retentionIsOnlyJournalAndReviewIsReadOnly() = runBlocking { withDb { db ->
        val old = row(now - MealForecastJournalRepository.RETENTION_MS - minute)
        db.mealForecastJournalDao().insert(old)
        val repository = MealForecastJournalRepository(db, gson, { now + 30 }, { zone })
        repository.review(now + 61 * minute)
        assertEquals(1, db.mealForecastJournalDao().between(0, now + minute, 10).size)
        repository.record(tuple(), GlucosePoint(now, 5.0, "sensor"), GlucoseCalibrationCycleIdentity(null, "session-a", now), true)
        assertEquals(1, db.mealForecastJournalDao().between(0, now + minute, 10).size)
    } }

    @Test fun overflowCannotReturnPartialAccuracy() = runBlocking { withDb { db ->
        for (i in 0..MealForecastJournalRepository.MAX_ROWS) db.mealForecastJournalDao().insert(row(cycle = "overflow-$i"))
        val result = MealForecastJournalRepository(db, gson).review(now + 61 * minute)
        assertTrue(result.overflow)
        assertNull(result.evaluation)
    } }
}
