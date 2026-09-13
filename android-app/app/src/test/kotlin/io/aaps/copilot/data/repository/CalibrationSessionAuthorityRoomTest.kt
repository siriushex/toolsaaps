package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.BloodGlucoseCheckEntity
import io.aaps.copilot.data.local.entity.GlucoseCalibrationModelEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.ui.loadUiCalibrationAuthority
import io.aaps.copilot.domain.predict.SensitivityRuntimeSettingsIdentity
import io.aaps.copilot.domain.predict.SensitivitySourcePreference
import io.aaps.copilot.widget.CopilotGlucoseWidgetRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CalibrationSessionAuthorityRoomTest {
    private lateinit var db: CopilotDatabase
    private lateinit var repository: GlucoseCalibrationRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = GlucoseCalibrationRepository(db, Gson(), AuditLogger(db.auditLogDao(), Gson()) { NOW })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun missingMetadataRetainsPendingBloodCheckSessionForRawWriterAndReader() = runBlocking {
        seed(active = false, anchor = check().copy(status = "OUT_OF_WINDOW", reason = "aligned_glucose_gap"))
        assertWriterReaderAgreement(active = false)
    }

    @Test
    fun missingMetadataRetainsValidBloodCheckSessionAndExactActiveModel() = runBlocking {
        seed(active = true)
        assertWriterReaderAgreement(active = true)
    }

    @Test
    fun uiRejectsAChangedAuthorityTokenEvenWithTheSameSession() = runBlocking {
        seed(active = true)
        val beforeReset = current().authorityToken
        token(CalibrationAuthorityStateCodec.raw("reset", SESSION))
        val ui = loadUiCalibrationAuthority(db, NOW, beforeReset)
        assertThat(ui.contextValid).isFalse()
        assertThat(ui.activeModel).isNull()
    }

    @Test
    fun unrelatedTherapyBackfillDoesNotInvalidatePreparedCalibration() = runBlocking {
        seed(active = false, anchor = check().copy(status = "OUT_OF_WINDOW", reason = "aligned_glucose_gap"))
        val prepared = repository.prepareAcceptedCycleCalibration(raw(), NOW, allowMaintenance = false)
        db.therapyDao().upsertAll(listOf("temp_target", "carbs", "correction_bolus", "note").map { type ->
            TherapyEventEntity("backfill-$type", NOW - 10 * MINUTE, type, "{}")
        })
        var committed = false
        repository.commitPreparedCalibrationAcceptance(prepared, NOW) { committed = true }
        assertThat(committed).isTrue()
        assertThat(repository.reconcilePreparedCalibrationAcceptance(prepared, NOW)).isTrue()
    }

    @Test
    fun backfilledSensorHistoryStillInvalidatesPreparedCalibration() = runBlocking {
        seed(active = false)
        val prepared = repository.prepareAcceptedCycleCalibration(raw(), NOW, allowMaintenance = false)
        // Before the anchor, so the current-session boundary veto cannot replace the input fence.
        db.therapyDao().upsertAll(listOf(TherapyEventEntity(
            "backfill-sensor", NOW - 30 * MINUTE, " CGM_SENSOR_CHANGE ", "{}"
        )))
        var committed = false
        val failure = runCatching {
            repository.commitPreparedCalibrationAcceptance(prepared, NOW) { committed = true }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).isEqualTo("calibration input changed before accepted publication")
        assertThat(committed).isFalse()
    }

    @Test
    fun reclassifyingANonLatestSensorBoundaryInvalidatesPreparedCalibration() = runBlocking {
        assertBoundaryMutationInvalidates {
            db.therapyDao().upsertAll(listOf(TherapyEventEntity(
                "earlier-sensor", NOW - 60 * MINUTE, "note", "{}"
            )))
        }
    }

    @Test
    fun deletingANonLatestSensorBoundaryInvalidatesPreparedCalibration() = runBlocking {
        assertBoundaryMutationInvalidates {
            db.openHelper.writableDatabase.execSQL("DELETE FROM therapy_events WHERE id = 'earlier-sensor'")
        }
    }

    @Test
    fun movingANonLatestSensorBoundaryInvalidatesPreparedCalibration() = runBlocking {
        assertBoundaryMutationInvalidates {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE therapy_events SET timestamp = ? WHERE id = 'earlier-sensor'",
                arrayOf(NOW - 70 * MINUTE)
            )
        }
    }

    private suspend fun assertBoundaryMutationInvalidates(mutate: suspend () -> Unit) {
        seed(active = false)
        db.therapyDao().upsertAll(listOf(
            TherapyEventEntity("earlier-sensor", NOW - 60 * MINUTE, "sensor_start", "{}"),
            TherapyEventEntity("latest-sensor", NOW - 30 * MINUTE, "sensor_change", "{}")
        ))
        val prepared = repository.prepareAcceptedCycleCalibration(raw(), NOW, allowMaintenance = false)
        mutate()
        var committed = false
        val failure = runCatching {
            repository.commitPreparedCalibrationAcceptance(prepared, NOW) { committed = true }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).isEqualTo("calibration input changed before accepted publication")
        assertThat(committed).isFalse()
    }

    @Test
    fun invalidDuplicateCannotHideRemovalOfUsableSensorBoundary() = runBlocking {
        assertBoundaryPayloadMutationInvalidates {
            db.therapyDao().upsertAll(listOf(TherapyEventEntity(
                "usable-sensor", NOW - 30 * MINUTE, "note", "{}"
            )))
        }
    }

    @Test
    fun inPlaceSensorPayloadChangeInvalidatesPreparedCalibration() = runBlocking {
        assertBoundaryPayloadMutationInvalidates {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE therapy_events SET payloadJson = '[]' WHERE id = 'usable-sensor'"
            )
        }
    }

    private suspend fun assertBoundaryPayloadMutationInvalidates(mutate: suspend () -> Unit) {
        seed(active = false)
        db.therapyDao().upsertAll(listOf(
            TherapyEventEntity("usable-sensor", NOW - 30 * MINUTE, "sensor_change", "{}"),
            TherapyEventEntity("invalid-sensor", NOW - 30 * MINUTE, "sensor_change", "[]")
        ))
        val prepared = repository.prepareAcceptedCycleCalibration(raw(), NOW, allowMaintenance = false)
        mutate()
        var committed = false
        val failure = runCatching {
            repository.commitPreparedCalibrationAcceptance(prepared, NOW) { committed = true }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).isEqualTo("calibration input changed before accepted publication")
        assertThat(committed).isFalse()
    }

    @Test
    fun sensorBoundaryByteOverflowCannotAuthorizeCalibration() = runBlocking {
        assertBoundaryOverflow(listOf(TherapyEventEntity(
            "large-sensor", NOW - 30 * MINUTE, "sensor_change",
            "{\"note\":\"${"x".repeat(128 * 1024)}\"}"
        )))
    }

    @Test
    fun sensorBoundaryRowOverflowCannotAuthorizeCalibration() = runBlocking {
        assertBoundaryOverflow((0..512).map {
            TherapyEventEntity("sensor-$it", NOW - 30 * MINUTE, "sensor_change", "{}")
        })
    }

    private suspend fun assertBoundaryOverflow(rows: List<TherapyEventEntity>) {
        seed(active = false)
        db.therapyDao().upsertAll(rows)
        var committed = false
        val failure = runCatching {
            val prepared = repository.prepareAcceptedCycleCalibration(raw(), NOW, allowMaintenance = false)
            repository.commitPreparedCalibrationAcceptance(prepared, NOW) { committed = true }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).isEqualTo("calibration sensor history exceeds safe input budget")
        assertThat(committed).isFalse()
    }

    @Test
    fun newestRejectedCheckDoesNotHideAdmissiblePendingAnchor() = runBlocking {
        seed(active = false, anchor = check().copy(status = "OUT_OF_WINDOW", reason = "aligned_glucose_gap"))
        db.bloodGlucoseCheckDao().upsert(check().copy(
            id = "rejected", timestamp = NOW - MINUTE, status = "REJECTED", reason = "sensor_blocked"
        ))
        assertWriterReaderAgreement(active = false)
    }

    @Test
    fun expiredAnchorCannotAuthorizeItsOldRawSession() = runBlocking {
        seed(active = false, anchor = check().copy(timestamp = NOW - 72 * HOUR - 1))
        assertWriterReaderRejectSession()
    }

    @Test
    fun futureCheckTimestampCannotAuthorizeItsSession() = runBlocking {
        seed(active = false, anchor = check().copy(timestamp = NOW + 1))
        assertWriterReaderRejectSession()
    }

    @Test
    fun futureCheckEntryCannotAuthorizeItsSession() = runBlocking {
        seed(active = false, anchor = check().copy(enteredAt = NOW + 1))
        assertWriterReaderRejectSession()
    }

    @Test
    fun explicitSensorChangeVetoesAnchorEvenWithMatchingFreshAge() = runBlocking {
        seed(active = true)
        db.telemetryDao().upsertAll(listOf(age()))
        db.therapyDao().upsertAll(listOf(TherapyEventEntity(
            id = "sensor-change", timestamp = NOW - 10 * MINUTE,
            type = " SENSOR_CHANGE ", payloadJson = "{}"
        )))
        assertWriterReaderRejectSession()
    }

    @Test
    fun ninetyMinuteGlucoseGapVetoesAnchor() = runBlocking {
        seed(active = true, anchor = check().copy(timestamp = NOW - 3 * HOUR))
        db.glucoseDao().upsertAll(listOf(glucose(NOW - 3 * HOUR)))
        assertWriterReaderRejectSession()
    }

    @Test
    fun gapOnlyAbsencePermitsExplicitRawWithoutAuthorizingOrMutatingOldModel() = runBlocking {
        for (gapMinutes in listOf(90L, 231L)) {
            db.clearAllTables()
            val oldModel = seedGapOnlyRaw(gapMinutes)
            val context = db.withTransaction {
                loadCalibrationSessionContextInTransaction(db, NOW, NOW)
            }
            assertThat(context.contextValid).isFalse()
            assertThat(context.currentSessionKey).isNull()
            assertThat(context.rawWithoutSessionAllowed).isTrue()

            val prepared = repository.prepareAcceptedCycleCalibration(raw(), NOW, allowMaintenance = false)
            assertThat(prepared.model).isNull()
            assertThat(prepared.identity.modelId).isNull()
            assertThat(prepared.identity.sensorSessionKey).isNull()
            assertThat(prepared.resolvedGlucose.all {
                !it.calibrationApplied && it.calibrationModelId == null && it.calibratedMmol == it.rawMmol
            }).isTrue()
            repository.commitPreparedCalibrationAcceptance(prepared, NOW) {}
            assertThat(repository.reconcilePreparedCalibrationAcceptance(prepared, NOW)).isTrue()
            assertGapOnlyReaderParity(rawAllowed = true)
            assertThat(db.glucoseCalibrationModelDao().latestActive()).isEqualTo(oldModel)
        }
    }

    @Test
    fun gapOnlyRawEligibilityRejectsActiveSessionBoundPendingAndUnknownTokens() = runBlocking {
        val oldModel = seedGapOnlyRaw()
        val tokens = listOf(
            CalibrationAuthorityStateCodec.active("old-active", oldModel.toDomain()),
            CalibrationAuthorityStateCodec.raw("old-session", oldModel.sensorSessionKey),
            CalibrationAuthorityStateCodec.pending("pending"),
            "legacy-unknown"
        )
        for (value in tokens) {
            token(value)
            assertGapOnlyReaderParity(rawAllowed = false)
            assertThat(db.glucoseCalibrationModelDao().latestActive()).isEqualTo(oldModel)
        }
    }

    @Test
    fun gapOnlyRawCannotBypassExplicitSensorBoundary() = runBlocking {
        seedGapOnlyRaw()
        db.therapyDao().upsertAll(listOf(TherapyEventEntity(
            "gap-sensor-boundary", NOW - 10 * MINUTE, "sensor_change", "{}"
        )))
        assertGapOnlyReaderParity(rawAllowed = false)
    }

    @Test
    fun gapOnlyRawCannotBypassPresentMalformedConflictingOrTruncatedAgeEvidence() = runBlocking {
        val cases = listOf(
            listOf(age()),
            listOf(age().copy(valueDouble = null, valueText = "invalid")),
            listOf(age(), age().copy(id = "conflict", source = "other", valueDouble = 8.0)),
            (0..64).map { age().copy(id = "overflow-$it") }
        )
        for (rows in cases) {
            db.clearAllTables()
            seedGapOnlyRaw()
            db.telemetryDao().upsertAll(rows)
            assertGapOnlyReaderParity(rawAllowed = false)
        }
    }

    @Test
    fun gapOnlyRawCannotBypassTruncatedCheckEvidence() = runBlocking {
        seedGapOnlyRaw()
        db.bloodGlucoseCheckDao().upsertAll((0..CALIBRATION_SESSION_CHECK_LIMIT).map {
            check().copy(id = "overflow-check-$it", timestamp = NOW - it)
        })
        assertGapOnlyReaderParity(rawAllowed = false)
    }

    @Test
    fun gapOnlyRawCannotBypassTruncatedGlucoseEvidence() = runBlocking {
        seedGapOnlyRaw()
        db.glucoseDao().upsertAll((0..CALIBRATION_SESSION_GLUCOSE_LIMIT).map { glucose(NOW - it) })
        assertGapOnlyReaderParity(rawAllowed = false)
    }

    @Test
    fun gapOnlyRawCannotAuthorizeMissingInvalidOrFutureOnlyCurrentGlucose() = runBlocking {
        seedGapOnlyRaw()
        db.openHelper.writableDatabase.execSQL("DELETE FROM glucose_samples")
        assertGapOnlyReaderParity(rawAllowed = false, expectedRaw = null)
        db.glucoseDao().upsertAll(listOf(
            glucose(NOW).copy(quality = "INVALID"),
            glucose(NOW + 1)
        ))
        assertGapOnlyReaderParity(rawAllowed = false, expectedRaw = null)
    }

    private suspend fun seedGapOnlyRaw(gapMinutes: Long = 231L): GlucoseCalibrationModelEntity {
        val anchorTs = NOW - 6 * HOUR
        val oldModel = model().copy(createdAt = anchorTs + 1, validFromTs = anchorTs + 1)
        seed(
            active = true,
            anchor = check().copy(timestamp = anchorTs, enteredAt = anchorTs + 1),
            activeModel = oldModel
        )
        db.glucoseDao().upsertAll(listOf(glucose(NOW - 40 * MINUTE - gapMinutes * MINUTE)))
        token(CalibrationAuthorityStateCodec.raw("gap-only-raw", null))
        return oldModel
    }

    private suspend fun assertGapOnlyReaderParity(rawAllowed: Boolean, expectedRaw: Double? = 5.0) {
        val authority = current()
        assertThat(authority.context.contextValid).isEqualTo(rawAllowed)
        assertThat(authority.context.currentSessionKey).isNull()
        assertThat(authority.activeModel).isNull()
        val ui = loadUiCalibrationAuthority(db, NOW, authority.authorityToken)
        assertThat(ui.contextValid).isEqualTo(rawAllowed)
        assertThat(ui.currentSessionKey).isNull()
        assertThat(ui.activeModel).isNull()
        val widget = CopilotGlucoseWidgetRepository(db) {
            SensitivityRuntimeSettingsIdentity(2L, SensitivitySourcePreference.AAPS, SensitivitySourcePreference.EVIDENCE)
        }.loadSnapshot(NOW)
        assertThat(widget.rawGlucoseMmol).isEqualTo(expectedRaw)
        assertThat(widget.currentGlucoseMmol).isEqualTo(expectedRaw)
        assertThat(widget.calibrationApplied).isFalse()
        assertThat(widget.predicted30Mmol).isNull()
    }

    @Test
    fun sourceSwitchWithContinuousGlucoseIsNotSensorBoundary() = runBlocking {
        seed(active = true)
        db.glucoseDao().upsertAll(listOf(glucose(NOW - MINUTE).copy(source = "nightscout")))
        assertWriterReaderAgreement(active = true)
    }

    @Test
    fun conflictingAgeTelemetryCannotFallBackToBloodCheck() = runBlocking {
        seed(active = true)
        db.telemetryDao().upsertAll(listOf(age(), age().copy(id = "conflict", source = "other", valueDouble = 8.0)))
        assertWriterReaderRejectSession()
    }

    @Test
    fun conflictingAgeTiesFromTheSameSourceCannotAuthorizeSession() = runBlocking {
        seed(active = true)
        db.telemetryDao().upsertAll(listOf(
            age().copy(id = "z-selected-compatible"),
            age().copy(id = "a-hidden-conflict", valueDouble = 8.0)
        ))
        assertWriterReaderRejectSession()
    }

    @Test
    fun gapPredecessorBeforeTheAnchorLookbackStillVetoesSession() = runBlocking {
        seed(active = true, anchor = check().copy(timestamp = NOW - 3 * HOUR))
        db.glucoseDao().upsertAll(listOf(glucose(NOW - 5 * HOUR)))
        assertWriterReaderRejectSession()
    }

    @Test
    fun invalidAgeTelemetryCannotFallBackToBloodCheck() = runBlocking {
        seed(active = true)
        db.telemetryDao().upsertAll(listOf(age().copy(valueDouble = null, valueText = "invalid")))
        assertWriterReaderRejectSession()
    }

    @Test
    fun truncatedAgeTelemetryCannotFallBackToBloodCheck() = runBlocking {
        seed(active = true)
        db.telemetryDao().upsertAll((0..64).map { age().copy(id = "age-$it") })
        assertWriterReaderRejectSession()
    }

    @Test
    fun futureModelStillFailsClosedWithAdmissibleAnchor() = runBlocking {
        seed(active = true, activeModel = model().copy(createdAt = NOW + 1))
        assertThat(current().context.contextValid).isFalse()
        assertThat(current().activeModel).isNull()
    }

    @Test
    fun expiredModelDoesNotDowngradeActiveTokenToRaw() = runBlocking {
        seed(active = true, activeModel = model().copy(validToTs = NOW - 1))
        assertThat(current().context.contextValid).isFalse()
        assertThat(current().activeModel).isNull()
    }

    @Test
    fun pendingAuthorityStillCannotAuthorizeModelWithAdmissibleAnchor() = runBlocking {
        seed(active = true)
        token(CalibrationAuthorityStateCodec.pending("pending"))
        assertThat(current().context.contextValid).isFalse()
        assertThat(current().activeModel).isNull()
    }

    @Test
    fun boundedAiReaderUsesSameAnchorAuthorityAndAccountsForEveryRead() = runBlocking {
        seed(active = true)
        val observed = mutableListOf<AlertAiDatasetReadObservation>()
        val authority = db.withTransaction {
            loadCurrentGlucoseCalibrationAuthorityInTransaction(
                db, NOW, failOnEvidenceOverflow = true,
                readBudget = AlertAiDaoReadBudget(100, AlertAiDatasetReadProbe(observed::add))
            )
        }
        assertThat(authority.context.contextValid).isTrue()
        assertThat(authority.activeModel?.id).isEqualTo(model().id)
        assertThat(observed.all { it.queriedRows <= it.queryLimit + 1 }).isTrue()
        assertThat(observed.sumOf { it.queriedRows }).isGreaterThan(4)
        assertThat(observed.map { it.source }).containsAtLeast(
            AlertAiDatasetSourceName.CALIBRATION_SESSION_CHECKS,
            AlertAiDatasetSourceName.CALIBRATION_SESSION_BOUNDARY,
            AlertAiDatasetSourceName.CALIBRATION_SESSION_GLUCOSE
        ).inOrder()
    }

    @Test
    fun full72HourAnchorWindowFitsBoundedMinuteCadenceIncludingDuplicateSources() = runBlocking {
        seed(active = false, anchor = check().copy(timestamp = NOW - 72 * HOUR))
        val minuteRows = (0..72 * 60).map { glucose(NOW - it * MINUTE) }
        db.glucoseDao().upsertAll(minuteRows + minuteRows.map { it.copy(source = "nightscout") })
        assertWriterReaderAgreement(active = false)
    }

    @Test
    fun truncatedCheckEvidenceCannotAuthorizeAnAnchor() = runBlocking {
        seed(active = true)
        db.bloodGlucoseCheckDao().upsertAll((0..CALIBRATION_SESSION_CHECK_LIMIT).map {
            check().copy(id = "check-$it", timestamp = NOW - it)
        })
        assertWriterReaderRejectSession()
    }

    @Test
    fun truncatedGlucoseBoundaryEvidenceCannotAuthorizeAnAnchor() = runBlocking {
        seed(active = true)
        db.glucoseDao().upsertAll((0..CALIBRATION_SESSION_GLUCOSE_LIMIT).map { glucose(NOW - it) })
        assertWriterReaderRejectSession()
    }

    @Test
    fun aiBudgetExhaustionBoundsAnchorGlucoseQueryAndFailsClosed() = runBlocking {
        seed(active = true)
        val observed = mutableListOf<AlertAiDatasetReadObservation>()
        val failure = runCatching {
            db.withTransaction {
                loadCurrentGlucoseCalibrationAuthorityInTransaction(
                    db, NOW, failOnEvidenceOverflow = true,
                    readBudget = AlertAiDaoReadBudget(4, AlertAiDatasetReadProbe(observed::add))
                )
            }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(AlertAiContextException.LimitExceeded::class.java)
        assertThat(observed.last().source).isEqualTo(AlertAiDatasetSourceName.CALIBRATION_SESSION_GLUCOSE)
        assertThat(observed.last().queryLimit).isEqualTo(1)
        assertThat(observed.last().queriedRows).isEqualTo(2)
    }

    @Test
    fun futureCheckFloodCannotEvictCausallyAvailableAnchor() = runBlocking {
        seed(active = false)
        db.bloodGlucoseCheckDao().upsertAll((1..300).map {
            check().copy(id = "future-$it", timestamp = NOW + it, enteredAt = NOW + it)
        })
        assertWriterReaderAgreement(active = false)
    }

    @Test
    fun unrelatedAndFutureTherapyCannotVetoAnchor() = runBlocking {
        seed(active = false)
        db.therapyDao().upsertAll(listOf(
            TherapyEventEntity("unrelated", NOW - MINUTE, "carbs", "{\"carbs\":10}"),
            TherapyEventEntity("future-boundary", NOW + 1, "sensor_change", "{}"),
            TherapyEventEntity("old-boundary", check().timestamp, "sensor_change", "{}")
        ))
        assertWriterReaderAgreement(active = false)
    }

    @Test
    fun manualResetCheckCannotRestoreSessionAuthority() = runBlocking {
        seed(active = false, anchor = check().copy(status = "REJECTED", reason = "manual_reset"))
        assertWriterReaderRejectSession()
    }

    private suspend fun assertWriterReaderAgreement(active: Boolean) {
        val prepared = repository.prepareAcceptedCycleCalibration(raw(), NOW, allowMaintenance = false)
        assertThat(prepared.identity.sensorSessionKey).isEqualTo(SESSION)
        assertThat(prepared.model?.id).isEqualTo(if (active) model().id else null)
        repository.commitPreparedCalibrationAcceptance(prepared, NOW) {
            val readback = loadCurrentGlucoseCalibrationAuthorityInTransaction(db, NOW)
            assertThat(readback.context.contextValid).isTrue()
            assertThat(readback.context.currentSessionKey).isEqualTo(prepared.identity.sensorSessionKey)
            assertThat(readback.activeModel).isEqualTo(prepared.model)
            val ui = loadUiCalibrationAuthority(db, NOW, readback.authorityToken)
            assertThat(ui.contextValid).isTrue()
            assertThat(ui.currentSessionKey).isEqualTo(prepared.identity.sensorSessionKey)
            assertThat(ui.activeModel).isEqualTo(prepared.model)
            assertThat(CalibrationAuthorityStateCodec.decode(readback.authorityToken)?.state)
                .isEqualTo(if (active) DurableCalibrationAuthorityStatus.ACTIVE else DurableCalibrationAuthorityStatus.RAW)
        }
        assertThat(current().context.currentSessionKey).isEqualTo(SESSION)
    }

    private suspend fun assertWriterReaderRejectSession() {
        val prepared = repository.prepareAcceptedCycleCalibration(raw(), NOW, allowMaintenance = false)
        assertThat(prepared.identity.sensorSessionKey).isNull()
        assertThat(prepared.model).isNull()
        val readback = current()
        assertThat(readback.context.contextValid).isFalse()
        assertThat(readback.activeModel).isNull()
        val ui = loadUiCalibrationAuthority(db, NOW, readback.authorityToken)
        assertThat(ui.contextValid).isFalse()
        assertThat(ui.activeModel).isNull()
    }

    private suspend fun current() = db.withTransaction {
        loadCurrentGlucoseCalibrationAuthorityInTransaction(db, NOW)
    }

    private fun raw() = (-8..0).map { glucose(NOW + it * 5 * MINUTE).toDomain() }

    private suspend fun seed(
        active: Boolean,
        anchor: BloodGlucoseCheckEntity = check(),
        activeModel: GlucoseCalibrationModelEntity = model()
    ) {
        db.glucoseDao().upsertAll(raw().map { it.toEntity() })
        db.bloodGlucoseCheckDao().upsert(anchor)
        if (active) db.glucoseCalibrationModelDao().upsert(activeModel)
        token(if (active) CalibrationAuthorityStateCodec.active("active", activeModel.toDomain())
            else CalibrationAuthorityStateCodec.raw("raw", SESSION))
    }

    private suspend fun token(value: String) = db.telemetryDao().upsertAll(listOf(TelemetrySampleEntity(
        id = "synthetic-authority", timestamp = NOW, source = CALIBRATION_AUTHORITY_SOURCE,
        key = CALIBRATION_AUTHORITY_TOKEN_KEY, valueDouble = null, valueText = value, unit = null, quality = "OK"
    )))

    private fun check() = BloodGlucoseCheckEntity(
        id = "synthetic-check", timestamp = NOW - 20 * MINUTE, mmol = 6.0,
        units = "mmol/L", source = "MANUAL", note = "", enteredAt = NOW - 19 * MINUTE,
        sensorSessionKey = SESSION, lagAlignedTs = NOW - 10 * MINUTE, matchedRawGlucose = 5.0,
        status = "VALID", reason = "aligned"
    )

    private fun model() = GlucoseCalibrationModelEntity(
        id = "synthetic-model", sensorSessionKey = SESSION, createdAt = NOW - 10 * MINUTE,
        validFromTs = NOW - HOUR, validToTs = NOW + HOUR, modelType = "OFFSET",
        gain = 1.0, offsetMmol = 1.0, confidence = 0.9, checkCount = 1,
        sensorAgeHours = null, lagMinutesAtFit = 10.0, status = "ACTIVE", diagnosticsJson = "{}"
    )

    private fun glucose(ts: Long) = GlucoseSampleEntity(timestamp = ts, mmol = 5.0, source = "aaps_broadcast", quality = "OK")

    private fun age() = TelemetrySampleEntity(
        id = "age", timestamp = NOW, source = "aaps", key = "sensor_age_hours",
        valueDouble = 1.0, valueText = null, unit = "h", quality = "OK"
    )

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val NOW = 1_800_000_000_000L
        private val SESSION = requireNotNull(calibrationSensorSessionKeyFromAgeSample(NOW, 1.0))
    }
}
