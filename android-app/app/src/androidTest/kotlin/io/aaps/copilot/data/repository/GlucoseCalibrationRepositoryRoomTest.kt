package io.aaps.copilot.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.dao.AuditLogDao
import io.aaps.copilot.data.local.entity.AuditLogEntity
import io.aaps.copilot.data.local.entity.BloodGlucoseCheckEntity
import io.aaps.copilot.data.local.entity.GlucoseCalibrationModelEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.model.BloodGlucoseCheckStatus
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.ResolvedGlucosePoint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlucoseCalibrationRepositoryRoomTest {

    private lateinit var db: CopilotDatabase
    private lateinit var repository: GlucoseCalibrationRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(db.auditLogDao(), Gson()) { NOW_TS }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun modelUpdateFailureRollsBackReassessmentAndSuppressesCalibration() = runBlocking {
        seedRawGlucoseAndTrust(sensorBlocked = true)
        db.bloodGlucoseCheckDao().upsert(check(status = "VALID", reason = "aligned"))
        db.glucoseCalibrationModelDao().upsert(model(status = "ACTIVE"))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_calibration_model_update " +
                "BEFORE UPDATE ON glucose_calibration_models " +
                "BEGIN SELECT RAISE(FAIL, 'forced model update failure'); END"
        )

        val refreshed = repository.refreshCalibrationModel(nowTs = NOW_TS, force = true)

        assertNull(refreshed)
        assertEquals("VALID", db.bloodGlucoseCheckDao().latest(limit = 1).single().status)
        val resolved = repository.resolveGlucosePoints(
            rawGlucose = listOf(GlucosePoint(NOW_TS, 8.0, "test")),
            nowTs = NOW_TS
        ).single()
        assertFalse(resolved.calibrationApplied)
        assertEquals(8.0, resolved.calibratedMmol, 0.0)
        assertNull(repository.latestActiveModel(nowTs = NOW_TS))
        assertNull(repository.observeLatestActiveModel().first())
    }

    @Test
    fun manualResetRetiresModelsAndPreventsOldChecksFromRebuildingCalibration() = runBlocking {
        seedRawGlucoseAndTrust(sensorBlocked = false)
        db.bloodGlucoseCheckDao().upsert(check(status = "VALID", reason = "aligned"))
        db.bloodGlucoseCheckDao().upsert(
            check(
                id = "bgc-future-measurement",
                timestamp = NOW_TS + 24L * 60L * 60L * 1000L,
                enteredAt = NOW_TS - 1L,
                status = "VALID",
                reason = "aligned"
            )
        )
        db.glucoseCalibrationModelDao().upsert(model(status = "ACTIVE"))

        val result = repository.resetManualCalibration(resetAt = NOW_TS)

        assertEquals(1, result.retiredModelCount)
        assertEquals(2, result.resetCheckCount)
        val storedChecks = db.bloodGlucoseCheckDao().latest(limit = 10)
        assertTrue(storedChecks.all { it.status == "REJECTED" })
        assertTrue(storedChecks.all { it.reason == "manual_reset" })
        assertNull(repository.latestActiveModel(nowTs = NOW_TS))
        assertNull(repository.refreshCalibrationModel(nowTs = NOW_TS, force = true))
        assertTrue(
            db.glucoseCalibrationModelDao().modelsSince(0L)
                .none { it.status == "ACTIVE" || it.status == "SHADOW" }
        )
    }

    @Test
    fun currentManualCheckCreatesImmediateActiveOffsetWithoutSensorAgeTelemetry() = runBlocking {
        val bloodTs = NOW_TS
        val enteredAt = bloodTs + 30_000L
        db.glucoseDao().upsertAll(
            listOf(
                GlucoseSampleEntity(timestamp = bloodTs - 15L * 60_000L, mmol = 4.0, source = "test", quality = "OK"),
                GlucoseSampleEntity(timestamp = bloodTs - 10L * 60_000L, mmol = 4.1, source = "test", quality = "OK"),
                GlucoseSampleEntity(timestamp = bloodTs - 5L * 60_000L, mmol = 4.15, source = "test", quality = "OK"),
                GlucoseSampleEntity(timestamp = bloodTs, mmol = 4.2, source = "test", quality = "OK")
            )
        )
        db.telemetryDao().upsertAll(
            listOf(
                telemetry("sensor_lag_minutes", 10.0, bloodTs),
                telemetry("sensor_quality_score", 1.0, bloodTs),
                telemetry("sensor_quality_blocked", 0.0, bloodTs),
                telemetry("sensor_quality_suspect_false_low", 0.0, bloodTs)
            )
        )

        val check = repository.addManualBloodGlucoseCheck(
            value = 6.2,
            units = "mmol/L",
            timestamp = bloodTs,
            enteredAt = enteredAt
        )
        val model = repository.latestActiveModel(nowTs = enteredAt)
        val resolved = repository.resolveGlucosePoints(
            rawGlucose = listOf(GlucosePoint(bloodTs, 4.2, "test")),
            nowTs = enteredAt
        ).single()

        assertEquals(BloodGlucoseCheckStatus.VALID, check.status)
        assertEquals(PROVISIONAL_CURRENT_RAW_REASON, check.reason)
        assertNotNull(model)
        assertEquals(2.0, model?.offsetMmol ?: 0.0, 0.0001)
        assertTrue(resolved.calibrationApplied)
        assertEquals(6.2, resolved.calibratedMmol, 0.0001)
    }

    @Test
    fun provisionalModelSurvivesTransientBlockOnlyUntilAlignedTime() = runBlocking {
        val bloodTs = NOW_TS
        val alignedTs = bloodTs + 10L * 60_000L
        db.glucoseDao().upsertAll(
            listOf(
                GlucoseSampleEntity(timestamp = bloodTs - 15L * 60_000L, mmol = 4.0, source = "test", quality = "OK"),
                GlucoseSampleEntity(timestamp = bloodTs - 10L * 60_000L, mmol = 4.1, source = "test", quality = "OK"),
                GlucoseSampleEntity(timestamp = bloodTs - 5L * 60_000L, mmol = 4.15, source = "test", quality = "OK"),
                GlucoseSampleEntity(timestamp = bloodTs, mmol = 4.2, source = "test", quality = "OK")
            )
        )
        db.telemetryDao().upsertAll(
            listOf(
                telemetry("sensor_lag_minutes", 10.0, bloodTs),
                telemetry("sensor_quality_score", 1.0, bloodTs),
                telemetry("sensor_quality_blocked", 0.0, bloodTs),
                telemetry("sensor_quality_suspect_false_low", 0.0, bloodTs)
            )
        )
        repository.addManualBloodGlucoseCheck(
            value = 6.2,
            units = "mmol/L",
            timestamp = bloodTs,
            enteredAt = bloodTs + 30_000L
        )
        db.telemetryDao().upsertAll(
            listOf(
                telemetry("sensor_quality_score", 0.7, bloodTs + 60_000L),
                telemetry("sensor_quality_blocked", 1.0, bloodTs + 60_000L),
                telemetry("sensor_quality_suspect_false_low", 0.0, bloodTs + 60_000L)
            )
        )

        val beforeAligned = repository.refreshCalibrationModel(
            nowTs = bloodTs + 60_000L,
            force = true
        )

        assertNotNull(beforeAligned)
        assertEquals(
            PROVISIONAL_CURRENT_RAW_REASON,
            db.bloodGlucoseCheckDao().latest(limit = 1).single().reason
        )

        val atAligned = repository.refreshCalibrationModel(
            nowTs = alignedTs,
            force = false
        )

        assertNull(atAligned)
        assertEquals(
            "REJECTED",
            db.bloodGlucoseCheckDao().latest(limit = 1).single().status
        )
        assertNull(repository.latestActiveModel(nowTs = alignedTs))
    }

    @Test
    fun provisionalManualCheckBecomesLagAlignedModelWhenFuturePointArrives() = runBlocking {
        val bloodTs = NOW_TS
        val alignedTs = bloodTs + 10L * 60_000L
        db.glucoseDao().upsertAll(
            listOf(
                GlucoseSampleEntity(timestamp = bloodTs - 15L * 60_000L, mmol = 4.0, source = "test", quality = "OK"),
                GlucoseSampleEntity(timestamp = bloodTs - 10L * 60_000L, mmol = 4.1, source = "test", quality = "OK"),
                GlucoseSampleEntity(timestamp = bloodTs - 5L * 60_000L, mmol = 4.15, source = "test", quality = "OK"),
                GlucoseSampleEntity(timestamp = bloodTs, mmol = 4.2, source = "test", quality = "OK")
            )
        )
        db.telemetryDao().upsertAll(
            listOf(
                telemetry("sensor_lag_minutes", 10.0, bloodTs),
                telemetry("sensor_quality_score", 1.0, bloodTs),
                telemetry("sensor_quality_blocked", 0.0, bloodTs),
                telemetry("sensor_quality_suspect_false_low", 0.0, bloodTs)
            )
        )
        repository.addManualBloodGlucoseCheck(
            value = 6.2,
            units = "mmol/L",
            timestamp = bloodTs,
            enteredAt = bloodTs + 30_000L
        )
        db.glucoseDao().upsertAll(
            listOf(
                GlucoseSampleEntity(timestamp = bloodTs + 5L * 60_000L, mmol = 4.5, source = "test", quality = "OK"),
                GlucoseSampleEntity(timestamp = alignedTs, mmol = 4.8, source = "test", quality = "OK")
            )
        )
        db.telemetryDao().upsertAll(
            listOf(
                telemetry("sensor_lag_minutes", 10.0, alignedTs),
                telemetry("sensor_quality_score", 1.0, alignedTs),
                telemetry("sensor_quality_blocked", 0.0, alignedTs),
                telemetry("sensor_quality_suspect_false_low", 0.0, alignedTs)
            )
        )

        val refreshed = repository.refreshCalibrationModel(
            nowTs = alignedTs + 30_000L,
            force = true
        )
        val persistedCheck = db.bloodGlucoseCheckDao().latest(limit = 1).single()
        val resolved = repository.resolveGlucosePoints(
            rawGlucose = listOf(GlucosePoint(alignedTs, 4.8, "test")),
            nowTs = alignedTs + 30_000L
        ).single()

        assertNotNull(refreshed)
        assertEquals("VALID", persistedCheck.status)
        assertEquals("aligned", persistedCheck.reason)
        assertEquals(1.4, refreshed?.offsetMmol ?: 0.0, 0.0001)
        assertTrue(resolved.calibrationApplied)
        assertEquals(6.2, resolved.calibratedMmol, 0.0001)
    }

    @Test
    fun importSourceRolloverKeepsCalibrationInSameSensorSession() = runBlocking {
        val bloodTs = NOW_TS
        db.glucoseDao().upsertAll(
            listOf(
                GlucoseSampleEntity(timestamp = bloodTs - 15L * 60_000L, mmol = 4.0, source = "old", quality = "OK"),
                GlucoseSampleEntity(timestamp = bloodTs - 10L * 60_000L, mmol = 4.1, source = "old", quality = "OK"),
                GlucoseSampleEntity(timestamp = bloodTs - 5L * 60_000L, mmol = 4.15, source = "old", quality = "OK"),
                GlucoseSampleEntity(timestamp = bloodTs, mmol = 4.2, source = "old", quality = "OK")
            )
        )
        db.telemetryDao().upsertAll(
            listOf(
                telemetry("sensor_lag_minutes", 10.0, bloodTs),
                telemetry("sensor_quality_score", 1.0, bloodTs),
                telemetry("sensor_quality_blocked", 0.0, bloodTs),
                telemetry("sensor_quality_suspect_false_low", 0.0, bloodTs)
            )
        )
        repository.addManualBloodGlucoseCheck(
            value = 6.2,
            units = "mmol/L",
            timestamp = bloodTs,
            enteredAt = bloodTs + 30_000L
        )
        val rolloverTs = bloodTs + 5L * 60_000L
        db.glucoseDao().upsertAll(
            listOf(
                GlucoseSampleEntity(timestamp = rolloverTs, mmol = 5.0, source = "new", quality = "OK")
            )
        )

        val model = repository.latestActiveModel(nowTs = rolloverTs)
        val resolved = repository.resolveGlucosePoints(
            rawGlucose = listOf(GlucosePoint(rolloverTs, 5.0, "new")),
            nowTs = rolloverTs
        ).single()

        assertNotNull(model)
        assertTrue(resolved.calibrationApplied)
        assertEquals(7.0, resolved.calibratedMmol, 0.0001)

        db.telemetryDao().upsertAll(
            listOf(
                telemetry("sensor_lag_minutes", 10.0, rolloverTs),
                telemetry("sensor_quality_score", 1.0, rolloverTs),
                telemetry("sensor_quality_blocked", 0.0, rolloverTs),
                telemetry("sensor_quality_suspect_false_low", 0.0, rolloverTs)
            )
        )
        repository.addManualBloodGlucoseCheck(
            value = 7.0,
            units = "mmol/L",
            timestamp = rolloverTs,
            enteredAt = rolloverTs + 30_000L
        )

        val sessionKeys = db.bloodGlucoseCheckDao()
            .latest(limit = 2)
            .map { it.sensorSessionKey }
            .distinct()
        assertEquals(1, sessionKeys.size)
        assertNotNull(repository.latestActiveModel(nowTs = rolloverTs + 30_000L))
    }

    @Test
    fun modelReadWaitsForFailedRefreshToFinishSuppressingCalibration() = runBlocking {
        seedRawGlucoseAndTrust(sensorBlocked = true)
        db.bloodGlucoseCheckDao().upsert(check(status = "VALID", reason = "aligned"))
        db.glucoseCalibrationModelDao().upsert(model(status = "ACTIVE"))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_serialized_model_update " +
                "BEFORE UPDATE ON glucose_calibration_models " +
                "BEGIN SELECT RAISE(FAIL, 'forced serialized update failure'); END"
        )
        val failureAuditEntered = CountDownLatch(1)
        val releaseFailureAudit = CountDownLatch(1)
        val blockingRepository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(db.auditLogDao(), Gson()) {
                failureAuditEntered.countDown()
                releaseFailureAudit.await(5L, TimeUnit.SECONDS)
                NOW_TS
            }
        )
        val refresh = async(Dispatchers.IO) {
            blockingRepository.refreshCalibrationModel(nowTs = NOW_TS, force = true)
        }

        var resolution: Deferred<List<ResolvedGlucosePoint>>? = null
        try {
            assertTrue(failureAuditEntered.await(5L, TimeUnit.SECONDS))
            resolution = async(
                context = Dispatchers.IO,
                start = CoroutineStart.UNDISPATCHED
            ) {
                blockingRepository.resolveGlucosePoints(
                    rawGlucose = listOf(GlucosePoint(NOW_TS, 8.0, "test")),
                    nowTs = NOW_TS
                )
            }
            assertFalse(resolution.isCompleted)
        } finally {
            releaseFailureAudit.countDown()
        }

        assertNull(refresh.await())
        val resolved = requireNotNull(resolution).await().single()
        assertFalse(resolved.calibrationApplied)
        assertEquals(8.0, resolved.calibratedMmol, 0.0)
    }

    @Test
    fun manualCheckAndModelResolutionAreSerializedAcrossPersistedCheckWindow() = runBlocking {
        seedRawGlucoseAndTrust(sensorBlocked = true)
        db.telemetryDao().upsertAll(
            listOf(telemetry("sensor_age_hours", 24.0, BLOOD_TS))
        )
        db.glucoseCalibrationModelDao().upsert(model(status = "ACTIVE"))
        val checkAuditEntered = CompletableDeferred<Unit>()
        val releaseCheckAudit = CompletableDeferred<Unit>()
        val blockingAuditDao = blockingBloodCheckAuditDao(
            checkAuditEntered = checkAuditEntered,
            releaseCheckAudit = releaseCheckAudit
        )
        val blockingRepository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(blockingAuditDao, Gson()) { NOW_TS }
        )
        val add = async(Dispatchers.IO) {
            blockingRepository.addManualBloodGlucoseCheck(
                value = 8.2,
                units = "mmol/L",
                timestamp = BLOOD_TS,
                enteredAt = NOW_TS
            )
        }
        var resolution: Deferred<List<ResolvedGlucosePoint>>? = null

        try {
            withTimeout(5_000L) { checkAuditEntered.await() }
            resolution = async(
                context = Dispatchers.IO,
                start = CoroutineStart.UNDISPATCHED
            ) {
                blockingRepository.resolveGlucosePoints(
                    rawGlucose = listOf(GlucosePoint(NOW_TS, 8.0, "test")),
                    nowTs = NOW_TS
                )
            }
            assertFalse(resolution.isCompleted)
        } finally {
            releaseCheckAudit.complete(Unit)
        }

        assertEquals(BloodGlucoseCheckStatus.REJECTED, add.await().status)
        val resolved = requireNotNull(resolution).await().single()
        assertFalse(resolved.calibrationApplied)
        assertEquals(8.0, resolved.calibratedMmol, 0.0)
        assertTrue(
            db.glucoseCalibrationModelDao().modelsSince(0L)
                .none { it.status == "ACTIVE" || it.status == "SHADOW" }
        )
    }

    @Test
    fun cancellingManualCheckAfterPersistenceSuppressesCalibrationAndReleasesMutex() = runBlocking {
        seedRawGlucoseAndTrust(sensorBlocked = true)
        db.telemetryDao().upsertAll(
            listOf(telemetry("sensor_age_hours", 24.0, BLOOD_TS))
        )
        db.glucoseCalibrationModelDao().upsert(model(status = "ACTIVE"))
        val checkAuditEntered = CompletableDeferred<Unit>()
        val releaseCheckAudit = CompletableDeferred<Unit>()
        val blockingRepository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(
                blockingBloodCheckAuditDao(
                    checkAuditEntered = checkAuditEntered,
                    releaseCheckAudit = releaseCheckAudit
                ),
                Gson()
            ) { NOW_TS }
        )
        val add = async(Dispatchers.IO) {
            blockingRepository.addManualBloodGlucoseCheck(
                value = 8.2,
                units = "mmol/L",
                timestamp = BLOOD_TS,
                enteredAt = NOW_TS
            )
        }

        withTimeout(5_000L) { checkAuditEntered.await() }
        assertEquals(1, db.bloodGlucoseCheckDao().latest(limit = 10).size)
        add.cancel()
        releaseCheckAudit.complete(Unit)
        withTimeout(5_000L) { add.join() }
        assertTrue(add.isCancelled)

        assertNull(
            withTimeout(5_000L) {
                blockingRepository.latestActiveModel(nowTs = NOW_TS)
            }
        )
        val resolved = withTimeout(5_000L) {
            blockingRepository.resolveGlucosePoints(
                rawGlucose = listOf(GlucosePoint(NOW_TS, 8.0, "test")),
                nowTs = NOW_TS
            )
        }.single()
        assertFalse(resolved.calibrationApplied)
        assertEquals(8.0, resolved.calibratedMmol, 0.0)

        assertTrue(
            db.glucoseCalibrationModelDao().modelsSince(0L)
                .none { it.status == "ACTIVE" || it.status == "SHADOW" }
        )
        val recreatedRepository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(db.auditLogDao(), Gson()) { NOW_TS }
        )
        assertNull(recreatedRepository.latestActiveModel(nowTs = NOW_TS))
        val recreatedResolved = recreatedRepository.resolveGlucosePoints(
            rawGlucose = listOf(GlucosePoint(NOW_TS, 8.0, "test")),
            nowTs = NOW_TS
        ).single()
        assertFalse(recreatedResolved.calibrationApplied)
        assertEquals(8.0, recreatedResolved.calibratedMmol, 0.0)
    }

    @Test
    fun sensorRolloverRetiresPreviousSessionModelBeforeReuse() = runBlocking {
        seedRawGlucoseAndTrust(sensorBlocked = false)
        db.telemetryDao().upsertAll(
            listOf(
                telemetry("sensor_age_hours", 48.0, BLOOD_TS),
                telemetry("sensor_age_hours", 1.0, NOW_TS)
            )
        )
        db.bloodGlucoseCheckDao().upsert(
            check(
                sensorSessionKey = "sensor-old",
                status = "VALID",
                reason = "aligned"
            )
        )
        db.glucoseCalibrationModelDao().upsert(
            model(
                id = "gcm-old-session",
                sensorSessionKey = "sensor-old",
                status = "ACTIVE"
            )
        )

        val refreshed = repository.refreshCalibrationModel(nowTs = NOW_TS, force = true)

        assertNull(refreshed)
        assertTrue(
            db.glucoseCalibrationModelDao().modelsSince(0L)
                .none { it.status == "ACTIVE" || it.status == "SHADOW" }
        )
        assertNull(repository.latestActiveModel(nowTs = NOW_TS))
    }

    @Test
    fun nonForcedRefreshInvalidatesMemoAcrossSensorRollover() = runBlocking {
        seedRawGlucoseAndTrust(sensorBlocked = false)
        db.bloodGlucoseCheckDao().upsert(check(status = "VALID", reason = "aligned"))
        val first = repository.refreshCalibrationModel(nowTs = NOW_TS, force = true)
        assertNotNull(first)
        val afterRolloverTs = NOW_TS + 5L * 60L * 1000L
        db.telemetryDao().upsertAll(
            listOf(telemetry("sensor_age_hours", 0.5, afterRolloverTs))
        )

        val afterRollover = repository.refreshCalibrationModel(
            nowTs = afterRolloverTs,
            force = false
        )

        assertNull(afterRollover)
        assertTrue(
            db.glucoseCalibrationModelDao().modelsSince(0L)
                .none { it.status == "ACTIVE" || it.status == "SHADOW" }
        )
    }

    @Test
    fun unresolvedCurrentSessionRetiresResolvableHistoricalModel() = runBlocking {
        seedRawGlucoseAndTrust(sensorBlocked = false)
        db.bloodGlucoseCheckDao().upsert(check(status = "VALID", reason = "aligned"))
        db.glucoseCalibrationModelDao().upsert(model(status = "ACTIVE"))
        val staleCurrentSessionTs = NOW_TS + 31L * 60L * 1000L

        val refreshed = repository.refreshCalibrationModel(
            nowTs = staleCurrentSessionTs,
            force = true
        )

        assertNull(refreshed)
        assertTrue(
            db.glucoseCalibrationModelDao().modelsSince(0L)
                .none { it.status == "ACTIVE" || it.status == "SHADOW" }
        )
    }

    @Test
    fun oldSessionModelCannotChangeRecentGlucoseWithoutRefresh() = runBlocking {
        db.telemetryDao().upsertAll(
            listOf(telemetry("sensor_age_hours", 1.0, NOW_TS))
        )
        db.glucoseCalibrationModelDao().upsert(
            model(
                id = "gcm-old-runtime-session",
                sensorSessionKey = "sensor-old",
                status = "ACTIVE"
            )
        )

        val resolved = repository.resolveGlucosePoints(
            rawGlucose = listOf(GlucosePoint(NOW_TS, 8.0, "test")),
            nowTs = NOW_TS
        ).single()

        assertFalse(resolved.calibrationApplied)
        assertEquals(8.0, resolved.calibratedMmol, 0.0)
        assertNull(repository.latestActiveModel(nowTs = NOW_TS))
    }

    @Test
    fun conflictingFreshAgeSourcesKeepRecentGlucoseRaw() = runBlocking {
        db.telemetryDao().upsertAll(
            listOf(
                telemetry("sensor_age_hours", 1.0, NOW_TS),
                telemetry("sensor_lag_age_hours", 48.0, NOW_TS)
            )
        )
        db.glucoseCalibrationModelDao().upsert(
            model(
                id = "gcm-conflicting-age",
                sensorSessionKey = calibrationSensorSessionKeyFromAgeSample(NOW_TS, 48.0)!!,
                status = "ACTIVE"
            )
        )

        val resolved = repository.resolveGlucosePoints(
            rawGlucose = listOf(GlucosePoint(NOW_TS, 8.0, "test")),
            nowTs = NOW_TS
        ).single()

        assertFalse(resolved.calibrationApplied)
        assertEquals(8.0, resolved.calibratedMmol, 0.0)
        assertNull(repository.latestActiveModel(nowTs = NOW_TS))
    }

    @Test
    fun skewedSameKeyAgeSourcesKeepRecentGlucoseRaw() = runBlocking {
        db.telemetryDao().upsertAll(
            listOf(
                telemetry(
                    key = "sensor_age_hours",
                    value = 48.0,
                    timestamp = NOW_TS,
                    source = "old-source"
                ),
                telemetry(
                    key = "sensor_age_hours",
                    value = 1.0,
                    timestamp = NOW_TS - 1_000L,
                    source = "new-source"
                )
            )
        )
        db.glucoseCalibrationModelDao().upsert(
            model(
                id = "gcm-skewed-age",
                sensorSessionKey = calibrationSensorSessionKeyFromAgeSample(NOW_TS, 48.0)!!,
                status = "ACTIVE"
            )
        )

        val resolved = repository.resolveGlucosePoints(
            rawGlucose = listOf(GlucosePoint(NOW_TS, 8.0, "test")),
            nowTs = NOW_TS
        ).single()

        assertFalse(resolved.calibrationApplied)
        assertEquals(8.0, resolved.calibratedMmol, 0.0)
        assertNull(repository.latestActiveModel(nowTs = NOW_TS))
    }

    @Test
    fun futureAgeRowCannotHideConflictingFreshSource() = runBlocking {
        db.telemetryDao().upsertAll(
            listOf(
                telemetry(
                    key = "sensor_age_hours",
                    value = 48.0,
                    timestamp = NOW_TS,
                    source = "old-source"
                ),
                telemetry(
                    key = "sensor_age_hours",
                    value = 1.0,
                    timestamp = NOW_TS - 1_000L,
                    source = "new-source"
                ),
                telemetry(
                    key = "sensor_age_hours",
                    value = 49.0,
                    timestamp = NOW_TS + 60L * 60L * 1000L,
                    source = "new-source"
                )
            )
        )
        db.glucoseCalibrationModelDao().upsert(
            model(
                id = "gcm-future-age-mask",
                sensorSessionKey = calibrationSensorSessionKeyFromAgeSample(NOW_TS, 48.0)!!,
                status = "ACTIVE"
            )
        )

        val resolved = repository.resolveGlucosePoints(
            rawGlucose = listOf(GlucosePoint(NOW_TS, 8.0, "test")),
            nowTs = NOW_TS
        ).single()

        assertFalse(resolved.calibrationApplied)
        assertEquals(8.0, resolved.calibratedMmol, 0.0)
        assertNull(repository.latestActiveModel(nowTs = NOW_TS))
    }

    @Test
    fun cancellationAfterModelCommitDurablyRetiresCommittedModel() = runBlocking {
        seedRawGlucoseAndTrust(sensorBlocked = false)
        val modelAuditEntered = CompletableDeferred<Unit>()
        val releaseModelAudit = CompletableDeferred<Unit>()
        val originalCancellation = CancellationException("post-commit-cancellation")
        val observedCancellation = AtomicReference<CancellationException?>()
        val blockingRepository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(
                blockingAuditDao(
                    blockOnMessages = setOf(
                        "glucose_calibration_model_updated",
                        "glucose_calibration_model_reused"
                    ),
                    auditEntered = modelAuditEntered,
                    releaseAudit = releaseModelAudit,
                    failOnMessages = setOf("glucose_calibration_refresh_failed_closed")
                ),
                Gson()
            ) { NOW_TS }
        )
        val add = async(Dispatchers.IO) {
            try {
                blockingRepository.addManualBloodGlucoseCheck(
                    value = 8.2,
                    units = "mmol/L",
                    timestamp = BLOOD_TS,
                    enteredAt = NOW_TS
                )
            } catch (cancellation: CancellationException) {
                observedCancellation.set(cancellation)
                throw cancellation
            }
        }

        withTimeout(5_000L) { modelAuditEntered.await() }
        assertNotNull(db.glucoseCalibrationModelDao().latestActive())
        add.cancel(originalCancellation)
        releaseModelAudit.complete(Unit)
        withTimeout(5_000L) { add.join() }
        assertTrue(add.isCancelled)
        assertSame(originalCancellation, observedCancellation.get())
        assertTrue(originalCancellation.suppressed.any { it is CancellationException })

        assertTrue(
            db.glucoseCalibrationModelDao().modelsSince(0L)
                .none { it.status == "ACTIVE" || it.status == "SHADOW" }
        )
        val recreatedRepository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(db.auditLogDao(), Gson()) { NOW_TS }
        )
        assertNull(recreatedRepository.latestActiveModel(nowTs = NOW_TS))
        val resolved = recreatedRepository.resolveGlucosePoints(
            rawGlucose = listOf(GlucosePoint(NOW_TS, 8.0, "test")),
            nowTs = NOW_TS
        ).single()
        assertFalse(resolved.calibrationApplied)
        assertEquals(8.0, resolved.calibratedMmol, 0.0)
    }

    @Test
    fun unsafeAlignedTrustRejectsCheckAndRetiresModelAtomically() = runBlocking {
        seedRawGlucoseAndTrust(sensorBlocked = true)
        db.bloodGlucoseCheckDao().upsert(check(status = "VALID", reason = "aligned"))
        db.glucoseCalibrationModelDao().upsert(model(status = "ACTIVE"))

        val refreshed = repository.refreshCalibrationModel(nowTs = NOW_TS, force = true)

        assertNull(refreshed)
        assertEquals("REJECTED", db.bloodGlucoseCheckDao().latest(limit = 1).single().status)
        assertTrue(
            db.glucoseCalibrationModelDao().modelsSince(0L)
                .none { it.status == "ACTIVE" || it.status == "SHADOW" }
        )
    }

    @Test
    fun rejectedLatestDiagnosticDoesNotRetireModelBuiltFromEarlierValidCheck() = runBlocking {
        val olderBloodTs = BLOOD_TS - 20L * 60L * 1000L
        val inferredStartTs = olderBloodTs - 15L * 60L * 1000L
        val roundedStartTs = ((inferredStartTs + 150_000L) / 300_000L) * 300_000L
        val sessionKey = "sensor-$roundedStartTs"
        db.glucoseDao().upsertAll(
            listOf(
                glucose(timestamp = inferredStartTs),
                glucose(timestamp = inferredStartTs + 5L * 60L * 1000L),
                glucose(timestamp = inferredStartTs + 10L * 60L * 1000L),
                glucose(timestamp = inferredStartTs + 15L * 60L * 1000L)
            )
        )
        seedAlignedContext(
            bloodTs = olderBloodTs,
            sensorBlocked = false,
            includeSensorAge = false
        )
        seedAlignedContext(
            bloodTs = BLOOD_TS,
            sensorBlocked = true,
            includeSensorAge = false
        )
        db.bloodGlucoseCheckDao().upsertAll(
            listOf(
                check(
                    id = "bgc-valid-anchor",
                    timestamp = olderBloodTs,
                    sensorSessionKey = sessionKey,
                    status = "VALID",
                    reason = "aligned"
                ),
                check(
                    id = "bgc-blocked-latest",
                    timestamp = BLOOD_TS,
                    sensorSessionKey = sessionKey,
                    status = "VALID",
                    reason = "aligned"
                )
            )
        )
        db.glucoseCalibrationModelDao().upsert(
            model(sensorSessionKey = sessionKey, status = "ACTIVE")
        )

        val firstRefresh = repository.refreshCalibrationModel(nowTs = NOW_TS, force = true)
        val secondRefresh = repository.refreshCalibrationModel(nowTs = NOW_TS, force = true)

        assertNotNull(firstRefresh)
        assertNotNull(secondRefresh)
        assertEquals(
            "REJECTED",
            db.bloodGlucoseCheckDao().latest(limit = 1).single().status
        )
        assertNotNull(repository.latestActiveModel(nowTs = NOW_TS))
        val resolved = repository.resolveGlucosePoints(
            rawGlucose = listOf(GlucosePoint(NOW_TS, 8.0, "test")),
            nowTs = NOW_TS
        ).single()
        assertTrue(resolved.calibrationApplied)
    }

    @Test
    fun unresolvedLatestCheckRetiresAllNonRetiredModels() = runBlocking {
        val olderBloodTs = NOW_TS - 20L * 60L * 1000L
        seedAlignedContext(
            bloodTs = olderBloodTs,
            sensorBlocked = false,
            includeSensorAge = false
        )
        seedAlignedContext(
            bloodTs = BLOOD_TS,
            sensorBlocked = false,
            includeSensorAge = false
        )
        db.bloodGlucoseCheckDao().upsertAll(
            listOf(
                check(
                    id = "bgc-older",
                    timestamp = olderBloodTs,
                    sensorSessionKey = "sensor-1",
                    status = "VALID",
                    reason = "aligned"
                ),
                check(
                    id = "bgc-latest",
                    timestamp = BLOOD_TS,
                    sensorSessionKey = null,
                    status = "OUT_OF_WINDOW",
                    reason = "sensor_session_unresolved"
                )
            )
        )
        db.glucoseCalibrationModelDao().upsert(
            model(id = "gcm-active", sensorSessionKey = "sensor-1", status = "ACTIVE")
        )
        db.glucoseCalibrationModelDao().upsert(
            model(id = "gcm-shadow", sensorSessionKey = "sensor-2", status = "SHADOW")
        )

        val refreshed = repository.refreshCalibrationModel(nowTs = NOW_TS, force = true)

        assertNull(refreshed)
        val latest = db.bloodGlucoseCheckDao().latest(limit = 1).single()
        assertNull(latest.sensorSessionKey)
        assertEquals("OUT_OF_WINDOW", latest.status)
        assertTrue(
            db.glucoseCalibrationModelDao().modelsSince(0L)
                .none { it.status == "ACTIVE" || it.status == "SHADOW" }
        )
    }

    @Test
    fun concurrentRefreshesLeaveOneConsistentNonRetiredModel() = runBlocking {
        seedRawGlucoseAndTrust(sensorBlocked = false)
        db.bloodGlucoseCheckDao().upsert(check(status = "VALID", reason = "aligned"))

        val results = coroutineScope {
            listOf(
                async { repository.refreshCalibrationModel(nowTs = NOW_TS, force = true) },
                async { repository.refreshCalibrationModel(nowTs = NOW_TS, force = true) }
            ).awaitAll()
        }

        assertNotNull(results[0])
        assertNotNull(results[1])
        assertEquals(results[0]?.id, results[1]?.id)
        assertEquals(
            1,
            db.glucoseCalibrationModelDao().modelsSince(0L)
                .count { it.status == "ACTIVE" || it.status == "SHADOW" }
        )
    }

    @Test
    fun retirementSqlClampsPreActivationAndAlreadyExpiredIntervals() = runBlocking {
        db.glucoseCalibrationModelDao().upsert(
            model(
                id = "pre-activation",
                sensorSessionKey = "sensor-interval",
                status = "ACTIVE",
                validFromTs = 10_000L,
                validToTs = 20_000L
            )
        )
        db.glucoseCalibrationModelDao().upsert(
            model(
                id = "already-expired",
                sensorSessionKey = "sensor-interval",
                status = "SHADOW",
                validFromTs = 1_000L,
                validToTs = 4_000L
            )
        )

        val retired = db.glucoseCalibrationModelDao().retireNonRetiredInSession(
            sensorSessionKey = "sensor-interval",
            retiredAt = 5_000L
        )

        assertEquals(2, retired)
        val byId = db.glucoseCalibrationModelDao().modelsSince(0L).associateBy { it.id }
        assertEquals(10_000L, byId.getValue("pre-activation").validToTs)
        assertEquals(4_000L, byId.getValue("already-expired").validToTs)
        assertEquals("RETIRED", byId.getValue("pre-activation").status)
        assertEquals("RETIRED", byId.getValue("already-expired").status)
    }

    @Test
    fun shadowModelNeverChangesRuntimeGlucose() = runBlocking {
        db.glucoseCalibrationModelDao().upsert(model(id = "gcm-shadow", status = "SHADOW"))

        val resolved = repository.resolveGlucosePoints(
            rawGlucose = listOf(GlucosePoint(NOW_TS, 8.0, "test")),
            nowTs = NOW_TS
        ).single()

        assertFalse(resolved.calibrationApplied)
        assertEquals(8.0, resolved.calibratedMmol, 0.0)
    }

    @Test
    fun successfulRefreshRetiresOtherActiveAndShadowModelsInSession() = runBlocking {
        seedRawGlucoseAndTrust(sensorBlocked = false)
        db.bloodGlucoseCheckDao().upsert(check(status = "VALID", reason = "aligned"))
        db.glucoseCalibrationModelDao().upsert(model(id = "old-active", status = "ACTIVE"))
        db.glucoseCalibrationModelDao().upsert(model(id = "old-shadow", status = "SHADOW"))

        val refreshed = repository.refreshCalibrationModel(nowTs = NOW_TS, force = true)

        assertNotNull(refreshed)
        val byId = db.glucoseCalibrationModelDao().modelsSince(0L).associateBy { it.id }
        assertEquals("RETIRED", byId.getValue("old-active").status)
        assertEquals("RETIRED", byId.getValue("old-shadow").status)
        assertEquals(
            listOf(refreshed?.id),
            byId.values
                .filter { it.status == "ACTIVE" || it.status == "SHADOW" }
                .map { it.id }
        )
    }

    @Test
    fun scopedWatermarkIgnoresIrrelevantRowsAndDetectsRelevantLateInsert() = runBlocking {
        val historySinceTs = BLOOD_TS - 60L * 60L * 1000L
        val relevantThroughTs = BLOOD_TS
        db.glucoseDao().upsertAll(
            listOf(glucose(timestamp = BLOOD_TS - 2_000L))
        )
        val before = calibrationInputWatermark(
            historySinceTs = historySinceTs,
            relevantThroughTs = relevantThroughTs
        )

        db.glucoseDao().upsertAll(
            listOf(glucose(timestamp = BLOOD_TS + 1_000L))
        )
        db.telemetryDao().upsertAll(
            listOf(
                telemetry("unrelated_metric", 1.0, BLOOD_TS - 1_000L),
                telemetry("sensor_quality_score", 0.8, BLOOD_TS + 1_000L)
            )
        )
        val afterPreWindowCgm = calibrationInputWatermark(
            historySinceTs = historySinceTs,
            relevantThroughTs = relevantThroughTs
        )

        db.glucoseDao().upsertAll(
            listOf(glucose(timestamp = BLOOD_TS - 1_000L))
        )
        db.telemetryDao().upsertAll(
            listOf(telemetry("sensor_quality_score", 0.8, BLOOD_TS - 500L))
        )
        val afterRelevantLateInsert = calibrationInputWatermark(
            historySinceTs = historySinceTs,
            relevantThroughTs = relevantThroughTs
        )

        assertEquals(before.glucoseRowId, afterPreWindowCgm.glucoseRowId)
        assertEquals(before.telemetryRowId, afterPreWindowCgm.telemetryRowId)
        assertTrue(afterRelevantLateInsert.glucoseRowId > afterPreWindowCgm.glucoseRowId)
        assertTrue(afterRelevantLateInsert.telemetryRowId > afterPreWindowCgm.telemetryRowId)
    }

    @Test
    fun boundedFitQueriesExcludeRowsAfterRelevantHorizon() = runBlocking {
        val since = BLOOD_TS - 60_000L
        val through = BLOOD_TS
        db.glucoseDao().upsertAll(
            listOf(
                glucose(timestamp = since),
                glucose(timestamp = through),
                glucose(timestamp = through + 1L)
            )
        )
        db.telemetryDao().upsertAll(
            listOf(
                telemetry("sensor_age_hours", 24.0, since),
                telemetry("sensor_age_hours", 24.0, through),
                telemetry("sensor_age_hours", 24.0, through + 1L)
            )
        )

        val glucoseRows = db.glucoseDao().between(since = since, through = through)
        val telemetryRows = db.telemetryDao().betweenByKeysPage(
            since = since,
            through = through,
            keys = listOf("sensor_age_hours"),
            afterTimestamp = since - 1L,
            afterId = "",
            limit = 100
        )

        assertEquals(listOf(since, through), glucoseRows.map { it.timestamp })
        assertEquals(listOf(since, through), telemetryRows.map { it.timestamp })
    }

    private suspend fun seedRawGlucoseAndTrust(sensorBlocked: Boolean) {
        seedAlignedContext(bloodTs = BLOOD_TS, sensorBlocked = sensorBlocked)
    }

    private fun blockingBloodCheckAuditDao(
        checkAuditEntered: CompletableDeferred<Unit>,
        releaseCheckAudit: CompletableDeferred<Unit>
    ): AuditLogDao = blockingAuditDao(
        blockOnMessages = setOf(
            "blood_glucose_check_added",
            "blood_glucose_check_rejected"
        ),
        auditEntered = checkAuditEntered,
        releaseAudit = releaseCheckAudit
    )

    private fun blockingAuditDao(
        blockOnMessages: Set<String>,
        auditEntered: CompletableDeferred<Unit>,
        releaseAudit: CompletableDeferred<Unit>,
        failOnMessages: Set<String> = emptySet()
    ): AuditLogDao = object : AuditLogDao {
        override suspend fun insert(entity: AuditLogEntity) {
            if (entity.message in failOnMessages) {
                throw CancellationException("forced cleanup audit cancellation")
            }
            if (entity.message in blockOnMessages) {
                auditEntered.complete(Unit)
                releaseAudit.await()
            }
        }

        override fun observeLatest(limit: Int): Flow<List<AuditLogEntity>> = flowOf(emptyList())

        override suspend fun recentByMessage(
            message: String,
            sinceTs: Long,
            limit: Int
        ): List<AuditLogEntity> = emptyList()

        override suspend fun deleteOlderThan(olderThan: Long): Int = 0

        override suspend fun deleteOlderThanInfoMessages(
            olderThan: Long,
            messages: List<String>
        ): Int = 0
    }

    private suspend fun seedAlignedContext(
        bloodTs: Long,
        sensorBlocked: Boolean,
        includeSensorAge: Boolean = true
    ) {
        val alignedTs = bloodTs + 10L * 60L * 1000L
        db.glucoseDao().upsertAll(
            listOf(
                GlucoseSampleEntity(
                    timestamp = alignedTs,
                    mmol = 8.0,
                    source = "test",
                    quality = "OK"
                )
            )
        )
        db.telemetryDao().upsertAll(
            buildList {
                addAll(
                    listOf(
                telemetry("sensor_quality_score", 0.8, alignedTs),
                telemetry("sensor_quality_blocked", if (sensorBlocked) 1.0 else 0.0, alignedTs),
                telemetry("sensor_quality_suspect_false_low", 0.0, alignedTs)
                    )
                )
                if (includeSensorAge) {
                    add(
                        telemetry(
                            "sensor_age_hours",
                            (bloodTs - SENSOR_START_TS) / 3_600_000.0,
                            bloodTs
                        )
                    )
                }
            }
        )
    }

    private fun telemetry(
        key: String,
        value: Double,
        timestamp: Long,
        source: String = "test"
    ): TelemetrySampleEntity = TelemetrySampleEntity(
        id = "telemetry-$source-$key-$timestamp",
        timestamp = timestamp,
        source = source,
        key = key,
        valueDouble = value,
        valueText = null,
        unit = null,
        quality = "OK"
    )

    private fun glucose(timestamp: Long): GlucoseSampleEntity = GlucoseSampleEntity(
        timestamp = timestamp,
        mmol = 8.0,
        source = "test",
        quality = "OK"
    )

    private suspend fun calibrationInputWatermark(
        historySinceTs: Long,
        relevantThroughTs: Long
    ) = db.glucoseCalibrationModelDao().calibrationInputWatermark(
        historySinceTs = historySinceTs,
        relevantThroughTs = relevantThroughTs,
        telemetryKeys = listOf(
            "sensor_quality_score",
            "sensor_quality_blocked",
            "sensor_quality_suspect_false_low"
        )
    )

    private fun check(
        id: String = "bgc-1",
        timestamp: Long = BLOOD_TS,
        enteredAt: Long = timestamp,
        sensorSessionKey: String? = "sensor-1",
        status: String,
        reason: String
    ): BloodGlucoseCheckEntity = BloodGlucoseCheckEntity(
        id = id,
        timestamp = timestamp,
        mmol = 8.2,
        units = "mmol/L",
        source = "MANUAL",
        note = "",
        enteredAt = enteredAt,
        sensorSessionKey = sensorSessionKey,
        lagAlignedTs = timestamp + 10L * 60L * 1000L,
        matchedRawGlucose = 8.0,
        status = status,
        reason = reason
    )

    private fun model(
        id: String = "gcm-1",
        sensorSessionKey: String = "sensor-1",
        status: String,
        validFromTs: Long = NOW_TS - 2L * 60L * 60L * 1000L,
        validToTs: Long = NOW_TS + 72L * 60L * 60L * 1000L
    ): GlucoseCalibrationModelEntity = GlucoseCalibrationModelEntity(
        id = id,
        sensorSessionKey = sensorSessionKey,
        createdAt = NOW_TS - 60L * 60L * 1000L,
        validFromTs = validFromTs,
        validToTs = validToTs,
        modelType = "OFFSET",
        gain = 1.0,
        offsetMmol = 1.0,
        confidence = 0.8,
        checkCount = 1,
        sensorAgeHours = 24.0,
        lagMinutesAtFit = 10.0,
        status = status,
        diagnosticsJson = "{}"
    )

    private companion object {
        const val NOW_TS = 2_000_000_000_000L
        const val BLOOD_TS = NOW_TS - 10L * 60L * 1000L
        const val SENSOR_START_TS = NOW_TS - 24L * 60L * 60L * 1000L
    }
}
