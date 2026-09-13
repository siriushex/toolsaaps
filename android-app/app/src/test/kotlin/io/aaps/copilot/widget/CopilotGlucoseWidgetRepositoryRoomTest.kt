package io.aaps.copilot.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.dao.AuditLogDao
import io.aaps.copilot.data.local.entity.AuditLogEntity
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseCalibrationModelEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.SensitivityRuntimeSnapshotEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_MODEL_FINGERPRINT_KEY
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_MODEL_ID_KEY
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_PREPARED_AT_KEY
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_SESSION_KEY
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_SOURCE
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_AUTHORITY_TOKEN_KEY
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_NONE
import io.aaps.copilot.data.repository.AcceptedSensitivityTupleRoomLoader
import io.aaps.copilot.data.repository.AuditLogger
import io.aaps.copilot.data.repository.CALIBRATION_AUTHORITY_SOURCE
import io.aaps.copilot.data.repository.CALIBRATION_AUTHORITY_TOKEN_KEY
import io.aaps.copilot.data.repository.CalibrationAuthorityStateCodec
import io.aaps.copilot.data.repository.calibrationSensorSessionKeyFromAgeSample
import io.aaps.copilot.data.repository.GlucoseCalibrationRepository
import io.aaps.copilot.data.repository.GlucoseSanitizer
import io.aaps.copilot.data.repository.ManualCalibrationDurableMutation
import io.aaps.copilot.data.repository.acceptedCalibrationModelFingerprint
import io.aaps.copilot.data.repository.toDomain
import io.aaps.copilot.domain.model.GlucoseCalibrationModel
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_CYCLE_ID_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_SOURCE
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDecomposition
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDecompositionCodec
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDigest
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastRow
import io.aaps.copilot.domain.predict.SensitivityRuntimeSettingsIdentity
import io.aaps.copilot.domain.predict.SensitivitySourcePreference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CopilotGlucoseWidgetRepositoryRoomTest {
    private lateinit var db: CopilotDatabase
    private lateinit var calibrationRepository: GlucoseCalibrationRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        calibrationRepository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(db.auditLogDao(), Gson()) { NOW }
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun widgetReadsForecast30OnlyFromExactAcceptedTuple() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)

        val snapshot = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(snapshot.predicted30Mmol).isEqualTo(6.3)
    }

    @Test
    fun sessionlessRawWidgetDisplaysOnlyTheAuthenticatedForecast() = runBlocking {
        persistAccepted(
            base = 6.0,
            revision = 7L,
            calibrationModel = null,
            includeSensorEvidence = false
        )
        val accepted = requireNotNull(AcceptedSensitivityTupleRoomLoader(db).load(identity(7L), NOW))

        val snapshot = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(accepted.accepted.calibrationSessionKey).isNull()
        assertThat(snapshot.predicted30Mmol)
            .isEqualTo(accepted.accepted.forecastsByHorizon.getValue(30).valueMmol)
        assertThat(snapshot.forecastAgeMinutes).isEqualTo((NOW - GENERATION_TS) / 60_000L)
        assertThat(snapshot.rawGlucoseMmol).isEqualTo(5.0)
        assertThat(snapshot.currentGlucoseMmol).isEqualTo(5.0)
        assertThat(snapshot.calibrationApplied).isFalse()
    }

    @Test
    fun sessionlessRawWidgetRejectsUntrustedAuthorityTokens() = runBlocking {
        persistAccepted(
            base = 6.0,
            revision = 7L,
            calibrationModel = null,
            includeSensorEvidence = false
        )
        val tokens = listOf(
            "",
            ACCEPTED_CALIBRATION_NONE,
            "legacy-opaque-authority",
            "{\"version\":2,\"state\":\"RAW\",\"nonce\":\"unsupported\"}",
            CalibrationAuthorityStateCodec.pending("manual-pending"),
            CalibrationAuthorityStateCodec.raw("session-bound", "different-session"),
            CalibrationAuthorityStateCodec.active("calibrated", acceptedCalibrationModel().toDomain())
        )
        tokens.forEach { token ->
            db.telemetryDao().upsertAll(
                listOf(
                    calibrationAuthorityRow(token, NOW),
                    calibrationMarker(ACCEPTED_CALIBRATION_AUTHORITY_TOKEN_KEY, token)
                )
            )

            val snapshot = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

            assertThat(snapshot.predicted30Mmol).isNull()
            assertThat(snapshot.forecastAgeMinutes).isNull()
            assertThat(snapshot.currentGlucoseMmol).isEqualTo(5.0)
            assertThat(snapshot.calibrationApplied).isFalse()
        }
    }

    @Test
    fun sessionlessRawWidgetRejectsMissingOrChangedCurrentAuthority() = runBlocking {
        persistAccepted(
            base = 6.0,
            revision = 7L,
            calibrationModel = null,
            includeSensorEvidence = false
        )
        db.telemetryDao().deleteBySourceAndTimestamp(CALIBRATION_AUTHORITY_SOURCE, MARKER_TS)
        val repository = CopilotGlucoseWidgetRepository(db) { identity(7L) }
        val missing = repository.loadSnapshot(NOW)
        db.telemetryDao().upsertAll(
            listOf(calibrationAuthorityRow(CalibrationAuthorityStateCodec.raw("changed", null), NOW))
        )
        val changed = repository.loadSnapshot(NOW)

        listOf(missing, changed).forEach { snapshot ->
            assertThat(snapshot.predicted30Mmol).isNull()
            assertThat(snapshot.forecastAgeMinutes).isNull()
            assertThat(snapshot.currentGlucoseMmol).isEqualTo(5.0)
        }
    }

    @Test
    fun sessionlessRawWidgetRequiresValidCurrentCausalGlucose() = runBlocking {
        persistAccepted(
            base = 6.0,
            revision = 7L,
            calibrationModel = null,
            includeGlucose = false,
            includeSensorEvidence = false
        )
        val repository = CopilotGlucoseWidgetRepository(db) { identity(7L) }
        val missing = repository.loadSnapshot(NOW)
        db.glucoseDao().upsertAll(
            listOf(glucose(NOW, 7.0).copy(quality = "INVALID"), glucose(NOW + 1L, 7.0))
        )
        val invalidOrFutureOnly = repository.loadSnapshot(NOW)

        listOf(missing, invalidOrFutureOnly).forEach { snapshot ->
            assertThat(snapshot.rawGlucoseMmol).isNull()
            assertThat(snapshot.currentGlucoseMmol).isNull()
            assertThat(snapshot.predicted30Mmol).isNull()
        }
    }

    @Test
    fun sessionlessRawWidgetRejectsPresentMalformedConflictingOrTruncatedSessionEvidence() = runBlocking {
        persistAccepted(
            base = 6.0,
            revision = 7L,
            calibrationModel = null,
            includeSensorEvidence = false
        )
        val cases = listOf(
            listOf(sensorAgeTelemetry("valid-session", ageHours = 1.0)),
            listOf(sensorAgeTelemetry("malformed", ageHours = 1.0).copy(
                valueDouble = null,
                valueText = "unknown"
            )),
            listOf(
                sensorAgeTelemetry("conflict-a", ageHours = 1.0),
                sensorAgeTelemetry("conflict-b", ageHours = 8.0)
            ),
            (0 until 65).map { sensorAgeTelemetry("overflow-$it", ageHours = 1.0) }
        )
        cases.forEach { evidence ->
            db.telemetryDao().upsertAll(evidence)

            val snapshot = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

            assertThat(snapshot.predicted30Mmol).isNull()
            assertThat(snapshot.currentGlucoseMmol).isEqualTo(5.0)
            evidence.forEach { db.telemetryDao().deleteBySourceAndTimestamp(it.source, it.timestamp) }
        }
    }

    @Test
    fun widgetResetBeforeAcceptedReadCannotRetainRetiredDisplayCalibration() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        var identityReads = 0
        val repository = CopilotGlucoseWidgetRepository(db) {
            if (++identityReads == 1) {
                calibrationRepository.resetManualCalibration(resetAt = NOW - 1L)
            }
            identity(7L)
        }

        val snapshot = repository.loadSnapshot(NOW)

        assertThat(identityReads).isEqualTo(2)
        assertThat(db.glucoseCalibrationModelDao().latestActive()).isNull()
        assertThat(snapshot.rawGlucoseMmol).isEqualTo(5.0)
        assertThat(snapshot.currentGlucoseMmol).isEqualTo(5.0)
        assertThat(snapshot.calibrationApplied).isFalse()
        assertThat(snapshot.predicted30Mmol).isNull()
    }

    @Test
    fun sessionlessRawWidgetRejectsAuthorityRotationDuringSettingsRead() = runBlocking {
        persistAccepted(
            base = 6.0,
            revision = 7L,
            calibrationModel = null,
            includeSensorEvidence = false
        )
        var identityReads = 0
        val repository = CopilotGlucoseWidgetRepository(db) {
            if (++identityReads == 1) {
                db.telemetryDao().upsertAll(
                    listOf(calibrationAuthorityRow(CalibrationAuthorityStateCodec.raw("rotated", null), NOW))
                )
            }
            identity(7L)
        }

        val snapshot = repository.loadSnapshot(NOW)

        assertThat(identityReads).isEqualTo(2)
        assertThat(snapshot.predicted30Mmol).isNull()
        assertThat(snapshot.currentGlucoseMmol).isEqualTo(5.0)
        assertThat(snapshot.calibrationApplied).isFalse()
    }

    @Test
    fun widgetSettingsRevisionChangeStillHidesOnlyPrediction() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        var identityReads = 0
        val repository = CopilotGlucoseWidgetRepository(db) {
            identity(if (++identityReads == 1) 7L else 8L)
        }

        val snapshot = repository.loadSnapshot(NOW)

        assertThat(identityReads).isEqualTo(2)
        assertThat(snapshot.predicted30Mmol).isNull()
        assertThat(snapshot.forecastAgeMinutes).isNull()
        assertThat(snapshot.currentGlucoseMmol).isWithin(0.0001).of(5.8)
    }

    @Test
    fun directUpdaterWithEmptyIdsCompletesWithoutLoadingApplicationState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        withTimeout(2_000L) {
            CopilotGlucoseWidgetUpdater.updateWidgets(
                context = context,
                manager = AppWidgetManager.getInstance(context),
                ids = intArrayOf()
            )
        }
    }

    @Test
    fun modelFreeWidgetCoherenceStillRequiresExactSensorSession() {
        val token = CalibrationAuthorityStateCodec.raw("session-bound", "session-a")
        assertThat(
            isWidgetForecastCalibrationCoherent(
                acceptedModel = null,
                acceptedSessionKey = "session-a",
                acceptedAuthorityToken = token,
                currentModel = null,
                currentSessionKey = "session-a",
                currentAuthorityToken = token,
                currentContextValid = true
            )
        ).isTrue()
        assertThat(
            isWidgetForecastCalibrationCoherent(
                acceptedModel = null,
                acceptedSessionKey = "session-a",
                acceptedAuthorityToken = token,
                currentModel = null,
                currentSessionKey = "session-b",
                currentAuthorityToken = token,
                currentContextValid = true
            )
        ).isFalse()
        assertThat(
            isWidgetForecastCalibrationCoherent(
                acceptedModel = null,
                acceptedSessionKey = " ",
                acceptedAuthorityToken = token,
                currentModel = null,
                currentSessionKey = " ",
                currentAuthorityToken = token,
                currentContextValid = true
            )
        ).isFalse()
    }

    @Test
    fun sessionlessWidgetCoherenceRequiresExactValidatedRawIdentity() {
        val token = CalibrationAuthorityStateCodec.raw("sessionless", null)
        assertThat(isWidgetForecastCalibrationCoherent(
            acceptedModel = null,
            acceptedSessionKey = null,
            acceptedAuthorityToken = token,
            currentModel = null,
            currentSessionKey = null,
            currentAuthorityToken = token,
            currentContextValid = true
        )).isTrue()
        listOf(
            null,
            "opaque",
            CalibrationAuthorityStateCodec.pending("pending"),
            CalibrationAuthorityStateCodec.raw("session-bound", "session-a"),
            CalibrationAuthorityStateCodec.active("active", acceptedCalibrationModel().toDomain())
        ).forEach { invalidToken ->
            assertThat(isWidgetForecastCalibrationCoherent(
                acceptedModel = null,
                acceptedSessionKey = null,
                acceptedAuthorityToken = invalidToken,
                currentModel = null,
                currentSessionKey = null,
                currentAuthorityToken = invalidToken,
                currentContextValid = true
            )).isFalse()
        }
    }

    @Test
    fun widgetCoherenceRejectsChangedTokenOrInvalidContextEvenWhenModelsAndSessionsMatch() {
        listOf(null, acceptedCalibrationModel().toDomain()).forEach { model ->
            val token = model?.let { CalibrationAuthorityStateCodec.active("accepted", it) }
                ?: CalibrationAuthorityStateCodec.raw("accepted", null)
            val changedToken = model?.let { CalibrationAuthorityStateCodec.active("changed", it) }
                ?: CalibrationAuthorityStateCodec.raw("changed", null)
            assertThat(isWidgetForecastCalibrationCoherent(
                acceptedModel = model,
                acceptedSessionKey = model?.sensorSessionKey,
                acceptedAuthorityToken = token,
                currentModel = model,
                currentSessionKey = model?.sensorSessionKey,
                currentAuthorityToken = changedToken,
                currentContextValid = true
            )).isFalse()
            assertThat(isWidgetForecastCalibrationCoherent(
                acceptedModel = model,
                acceptedSessionKey = model?.sensorSessionKey,
                acceptedAuthorityToken = token,
                currentModel = model,
                currentSessionKey = model?.sensorSessionKey,
                currentAuthorityToken = token,
                currentContextValid = false
            )).isFalse()
        }
    }

    @Test
    fun widgetCoherenceRejectsModelChangesAndSessionlessTokensWithModels() {
        val model = acceptedCalibrationModel().toDomain()
        val token = CalibrationAuthorityStateCodec.active("accepted", model)
        assertThat(isWidgetForecastCalibrationCoherent(
            acceptedModel = model,
            acceptedSessionKey = model.sensorSessionKey,
            acceptedAuthorityToken = token,
            currentModel = model.copy(offsetMmol = model.offsetMmol + 0.2),
            currentSessionKey = model.sensorSessionKey,
            currentAuthorityToken = token,
            currentContextValid = true
        )).isFalse()
        val rawToken = CalibrationAuthorityStateCodec.raw("raw", null)
        assertThat(isWidgetForecastCalibrationCoherent(
            acceptedModel = model,
            acceptedSessionKey = null,
            acceptedAuthorityToken = rawToken,
            currentModel = model,
            currentSessionKey = null,
            currentAuthorityToken = rawToken,
            currentContextValid = true
        )).isFalse()
    }

    @Test
    fun widgetIgnoresFutureGlucoseAndUsesNewestCausalRow() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        db.glucoseDao().upsertAll(listOf(glucose(NOW + 1L, 9.9)))

        val snapshot = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(snapshot.rawGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(snapshot.currentGlucoseMmol).isWithin(0.0001).of(5.8)
        assertThat(snapshot.predicted30Mmol).isEqualTo(6.3)
    }

    @Test
    fun invalidArtifactsDoNotHideOlderValidCausalGlucose() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        db.glucoseDao().upsertAll(
            listOf(glucose(NOW - 2L, 5.4)) +
                (0 until 8).map {
                    glucose(NOW - 1L, 7.0).copy(quality = "INVALID")
                }
        )

        val restarted = AcceptedSensitivityTupleRoomLoader(db).load(identity(7L), NOW)
        val snapshot = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(restarted).isNotNull()
        assertThat(snapshot.predicted30Mmol).isEqualTo(6.3)
        assertThat(snapshot.rawGlucoseMmol).isWithin(0.0001).of(5.4)
        assertThat(snapshot.currentGlucoseMmol).isWithin(0.0001).of(6.2)
        assertThat(snapshot.calibrationApplied).isTrue()
    }

    @Test
    fun normalRetainedGlucoseHistoryDoesNotInvalidatePrediction() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        db.glucoseDao().upsertAll(
            (0L until 1_000L).map { offset -> glucose(NOW - offset, 6.0 + offset / 10_000.0) }
        )

        val snapshot = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(snapshot.predicted30Mmol).isEqualTo(6.3)
        assertThat(snapshot.rawGlucoseMmol).isWithin(0.0001).of(6.0)
        assertThat(snapshot.currentGlucoseMmol).isWithin(0.0001).of(6.8)
        assertThat(snapshot.calibrationApplied).isTrue()
    }

    @Test
    fun duplicateLatestTimestampUsesClinicalSourcePriority() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        db.glucoseDao().upsertAll(
            listOf(
                glucose(NOW, 9.0).copy(source = "xdrip_broadcast"),
                glucose(NOW, 6.0).copy(source = "nightscout")
            )
        )

        val snapshot = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(snapshot.predicted30Mmol).isEqualTo(6.3)
        assertThat(snapshot.rawGlucoseMmol).isWithin(0.0001).of(6.0)
        assertThat(snapshot.currentGlucoseMmol).isWithin(0.0001).of(6.8)
        assertThat(snapshot.calibrationApplied).isTrue()
    }

    @Test
    fun glucoseDaoPrioritySqlMatchesSanitizerAcrossSourceAndQualityBuckets() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        val sourceCases = listOf(
            "AaPs_BrOaDcAsT" to "nightscout",
            "NIGHTSCOUT" to "xdrip_broadcast",
            "XDRIP_BROADCAST" to "local_nightscout_entry",
            "LOCAL_NIGHTSCOUT_ENTRY" to "local_nightscout_remote",
            "LoCaL_NiGhTsCoUt_remote" to "localXnightscout_remote",
            "localXnightscout_remote" to "local_broadcast"
        )
        val qualityCases = listOf(
            "oK" to "stale",
            "GoOd" to "STALE",
            "vAlId" to "stale",
            "sTaLe" to "unknown",
            "SeNsOr_ErRoR" to "unknown",
            "eRrOr" to "unknown",
            "InVaLiD" to "unknown",
            "mystery" to "other"
        )
        val sourcePairs = sourceCases.mapIndexed { index, (preferred, lowerPriority) ->
            val timestamp = NOW - 100L - index
            listOf(
                glucose(timestamp, 6.0 + index / 10.0).copy(source = preferred, quality = "OK"),
                glucose(timestamp, 9.0 + index / 10.0).copy(source = lowerPriority, quality = "OK")
            )
        }
        val qualityPairs = qualityCases.mapIndexed { index, (preferred, lowerPriority) ->
            val timestamp = NOW - 200L - index
            listOf(
                glucose(timestamp, 7.0 + index / 10.0).copy(source = "matrix_source", quality = preferred),
                glucose(timestamp, 8.0 + index / 10.0).copy(source = "matrix_source", quality = lowerPriority)
            )
        }
        val invalidSafetyPairs = listOf(
            listOf(
                glucose(NOW - 300L, 6.2).copy(source = "local_broadcast", quality = "OK"),
                glucose(NOW - 300L, 9.2).copy(source = "aaps_broadcast", quality = "INVALID")
            ),
            listOf(
                glucose(NOW - 301L, 6.3).copy(source = "local_broadcast", quality = "OK"),
                glucose(NOW - 301L, 30.0).copy(source = "local_broadcast", quality = "OK")
            )
        )
        val invalidOnlyPairs = listOf(
            listOf(
                glucose(NOW - 302L, 7.1).copy(source = "matrix_source", quality = "ERROR"),
                glucose(NOW - 302L, 7.2).copy(source = "matrix_source", quality = "INVALID")
            )
        )
        val pairs = sourcePairs + qualityPairs + invalidSafetyPairs + invalidOnlyPairs
        val caseTimestamps = pairs.map { it.first().timestamp }.toSet()
        db.glucoseDao().upsertAll(pairs.flatten())
        val persistedByTimestamp = db.glucoseDao().latest(128)
            .filter { it.timestamp in caseTimestamps }
            .groupBy { it.timestamp }
        val expectedByTimestamp = persistedByTimestamp.mapValues { (_, candidates) ->
            GlucoseSanitizer.filterEntities(candidates).single()
        }
        invalidSafetyPairs.forEach { pair ->
            assertThat(expectedByTimestamp.getValue(pair.first().timestamp).quality).isEqualTo("OK")
            assertThat(expectedByTimestamp.getValue(pair.first().timestamp).mmol).isLessThan(30.0)
        }

        val causalByTimestamp = db.glucoseDao()
            .latestValidDistinctAtOrBefore(NOW, limit = pairs.size)
            .associateBy { it.timestamp }
        sourcePairs.forEach { pair ->
            val timestamp = pair.first().timestamp
            assertThat(causalByTimestamp.getValue(timestamp).id)
                .isEqualTo(expectedByTimestamp.getValue(timestamp).id)
        }

        db.glucoseDao().deleteDuplicateByTimestampWithPriority()
        val survivorsByTimestamp = db.glucoseDao().latest(128)
            .filter { it.timestamp in caseTimestamps }
            .groupBy { it.timestamp }

        assertThat(survivorsByTimestamp).hasSize(pairs.size)
        expectedByTimestamp.forEach { (timestamp, expected) ->
            assertThat(survivorsByTimestamp.getValue(timestamp)).hasSize(1)
            assertThat(survivorsByTimestamp.getValue(timestamp).single().id).isEqualTo(expected.id)
        }
    }

    @Test
    fun modelFreeAcceptedTupleRequiresValidCurrentCausalGlucose() = runBlocking {
        persistAccepted(
            base = 6.0,
            revision = 7L,
            calibrationModel = null,
            includeGlucose = false
        )
        val withoutGlucose = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        db.glucoseDao().upsertAll(
            listOf(glucose(NOW, 7.0).copy(quality = "INVALID"))
        )
        val invalidOnly = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(withoutGlucose.rawGlucoseMmol).isNull()
        assertThat(withoutGlucose.currentGlucoseMmol).isNull()
        assertThat(withoutGlucose.predicted30Mmol).isNull()
        assertThat(invalidOnly.rawGlucoseMmol).isNull()
        assertThat(invalidOnly.currentGlucoseMmol).isNull()
        assertThat(invalidOnly.predicted30Mmol).isNull()
    }

    @Test
    fun glucoseExactlyAtAuthoritativeNowRemainsEligible() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        db.glucoseDao().upsertAll(listOf(glucose(NOW, 6.0)))

        val snapshot = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(snapshot.rawGlucoseMmol).isWithin(0.0001).of(6.0)
        assertThat(snapshot.currentGlucoseMmol).isWithin(0.0001).of(6.8)
        assertThat(snapshot.predicted30Mmol).isEqualTo(6.3)
    }

    @Test
    fun calibrationSessionEvidenceObservableIsSensorOnlyBoundedAndNewestFirst() = runBlocking {
        db.telemetryDao().upsertAll(
            (0 until 100).map { index ->
                telemetry("general-$index", index.toDouble(), NOW + index + 1L)
            } + (0 until 66).map { index ->
                sensorAgeTelemetry(
                    source = "sensor-$index",
                    timestamp = NOW - index,
                    ageHours = 1.0
                )
            }
        )

        val rows = db.telemetryDao().observeCalibrationSessionEvidence(
            keys = listOf("sensor_age_hours", "isf_factor_sensor_age_hours"),
            limit = 65
        ).first()

        assertThat(rows).hasSize(65)
        assertThat(rows.map { it.key }.distinct()).containsExactly("sensor_age_hours")
        assertThat(rows.map { it.timestamp })
            .containsExactlyElementsIn((0 until 65).map { NOW - it })
            .inOrder()
    }

    @Test
    fun sameTimestampOverwriteOrCurrentRevisionMismatchHidesOnlyPrediction() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        forecastSet(base = 8.0).forEach { row ->
            db.forecastDao().deleteByTimestampAndHorizon(row.timestamp, row.horizonMinutes)
        }
        db.forecastDao().insertAll(forecastSet(base = 8.0))

        val overwritten = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)
        val wrongRevision = CopilotGlucoseWidgetRepository(db) { identity(8L) }.loadSnapshot(NOW)

        assertThat(overwritten.predicted30Mmol).isNull()
        assertThat(wrongRevision.predicted30Mmol).isNull()
    }

    @Test
    fun badAcceptedDigestHidesPrediction() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        val rows = db.telemetryDao().atTimestampBySourceAndKeys(
            SENSITIVITY_ACCEPTED_SOURCE,
            MARKER_TS,
            ACCEPTED_KEYS
        ).map { row ->
            if (row.key == SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY) {
                row.copy(id = "bad-digest", valueText = "0".repeat(64))
            } else {
                row
            }
        }
        db.telemetryDao().upsertAcceptedSensitivityTuple(rows)

        val snapshot = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(snapshot.predicted30Mmol).isNull()
    }

    @Test
    fun manualCalibrationAddInvalidatesOlderWidgetPrediction() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        assertThat(CopilotGlucoseWidgetRepository(db) { identity(7L) }
            .loadSnapshot(NOW).predicted30Mmol).isEqualTo(6.3)

        val check = calibrationRepository.addManualBloodGlucoseCheck(
            value = 6.4,
            units = "mmol/L",
            timestamp = GLUCOSE_TS,
            enteredAt = NOW
        )
        val snapshot = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(db.bloodGlucoseCheckDao().latest(1).single().id).isEqualTo(check.id)
        assertThat(snapshot.predicted30Mmol).isNull()
        assertThat(snapshot.rawGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(snapshot.currentGlucoseMmol).isWithin(0.0001).of(6.4)
        assertThat(snapshot.calibrationApplied).isTrue()
    }

    @Test
    fun manualAddCallbackRendersFinalCalibratedStateExactlyOnce() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        var refreshCount = 0
        var callbackSnapshot: CopilotGlucoseWidgetSnapshot? = null
        val hookedRepository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(db.auditLogDao(), Gson()) { NOW },
            onManualCalibrationDurableMutation = { mutation ->
                assertThat(mutation).isEqualTo(ManualCalibrationDurableMutation.ADD)
                refreshCount += 1
                callbackSnapshot = CopilotGlucoseWidgetRepository(db) { identity(7L) }
                    .loadSnapshot(NOW)
            }
        )

        hookedRepository.addManualBloodGlucoseCheck(
            value = 6.4,
            units = "mmol/L",
            timestamp = GLUCOSE_TS,
            enteredAt = NOW
        )

        assertThat(refreshCount).isEqualTo(1)
        assertThat(callbackSnapshot?.currentGlucoseMmol).isWithin(0.0001).of(6.4)
        assertThat(callbackSnapshot?.rawGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(callbackSnapshot?.predicted30Mmol).isNull()
        assertThat(callbackSnapshot?.calibrationApplied).isTrue()
        assertThat(currentCalibrationAuthorityToken()).contains("\"state\":\"ACTIVE\"")
    }

    @Test
    fun failedManualAddTransactionKeepsPriorDurableAuthorityForAllConsumers() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        val priorModel = requireNotNull(db.glucoseCalibrationModelDao().latestActive())
        val priorAuthorityToken = currentCalibrationAuthorityToken()
        val priorChecks = db.bloodGlucoseCheckDao().latest(10)
        installPendingAuthorityWriteFailureTrigger()
        var callbackCount = 0
        val failingRepository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(db.auditLogDao(), Gson()) { NOW },
            onManualCalibrationDurableMutation = { callbackCount += 1 }
        )

        val failure = try {
            failingRepository.addManualBloodGlucoseCheck(
                value = 6.4,
                units = "mmol/L",
                timestamp = GLUCOSE_TS,
                enteredAt = NOW
            )
            null
        } catch (error: Throwable) {
            error
        }
        val repositoryModel = failingRepository.latestActiveModel(NOW)
        val observedModel = failingRepository.observeLatestActiveModel().first()
        val resolved = failingRepository.resolveGlucoseHistory(
            rawGlucose = listOf(glucose(GLUCOSE_TS, 5.0)),
            nowTs = NOW
        ).single()
        val independentWidget = CopilotGlucoseWidgetRepository(db) { identity(7L) }
            .loadSnapshot(NOW)

        assertThat(failure).hasMessageThat().contains("pending calibration authority unavailable")
        assertThat(callbackCount).isEqualTo(0)
        assertThat(db.bloodGlucoseCheckDao().latest(10)).isEqualTo(priorChecks)
        assertThat(db.glucoseCalibrationModelDao().latestActive()).isEqualTo(priorModel)
        assertThat(currentCalibrationAuthorityToken()).isEqualTo(priorAuthorityToken)
        assertThat(repositoryModel).isEqualTo(priorModel.toDomain())
        assertThat(observedModel).isEqualTo(priorModel.toDomain())
        assertThat(resolved.calibrationModelId).isEqualTo(priorModel.id)
        assertThat(resolved.calibratedMmol).isWithin(0.0001).of(5.8)
        assertThat(independentWidget.currentGlucoseMmol).isWithin(0.0001).of(5.8)
        assertThat(independentWidget.rawGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(independentWidget.predicted30Mmol).isEqualTo(6.3)
        assertThat(independentWidget.calibrationApplied).isTrue()
    }

    @Test
    fun failedManualAddWithoutPriorAuthorityDoesNotPoisonLaterDurableAuthority() = runBlocking {
        db.glucoseDao().upsertAll(
            listOf(
                glucose(GLUCOSE_TS - 15L * 60_000L, 4.4),
                glucose(GLUCOSE_TS - 10L * 60_000L, 4.6),
                glucose(GLUCOSE_TS - 5L * 60_000L, 4.8),
                glucose(GLUCOSE_TS, 5.0)
            )
        )
        db.telemetryDao().upsertAll(trustTelemetry())
        installPendingAuthorityWriteFailureTrigger()
        var callbackCount = 0
        val failingRepository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(db.auditLogDao(), Gson()) { NOW },
            onManualCalibrationDurableMutation = { callbackCount += 1 }
        )

        val failure = try {
            failingRepository.addManualBloodGlucoseCheck(
                value = 6.4,
                units = "mmol/L",
                timestamp = GLUCOSE_TS,
                enteredAt = NOW
            )
            null
        } catch (error: Throwable) {
            error
        }

        assertThat(failure).hasMessageThat().contains("pending calibration authority unavailable")
        assertThat(callbackCount).isEqualTo(0)
        assertThat(db.bloodGlucoseCheckDao().latest(10)).isEmpty()
        assertThat(db.glucoseCalibrationModelDao().latestActive()).isNull()
        assertThat(
            db.telemetryDao().currentBySourceAndKey(
                source = CALIBRATION_AUTHORITY_SOURCE,
                key = CALIBRATION_AUTHORITY_TOKEN_KEY
            )
        ).isNull()
        assertThat(
            failingRepository.resolveGlucoseHistory(
                rawGlucose = listOf(glucose(GLUCOSE_TS, 5.0)),
                nowTs = NOW
            ).single().calibratedMmol
        ).isWithin(0.0001).of(5.0)
        assertThat(
            db.auditLogDao().recentByMessage(
                message = "glucose_calibration_refresh_failed_closed",
                sinceTs = 0L,
                limit = 10
            )
        ).isEmpty()

        persistAccepted(base = 6.0, revision = 7L)

        assertThat(failingRepository.latestActiveModel(NOW))
            .isEqualTo(acceptedCalibrationModel().toDomain())
    }

    @Test
    fun modelAuditAndRetirementFailuresCannotDivergeOverviewAndWidgetAtRefresh() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L, calibrationModel = null)
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_manual_model_audit
            BEFORE INSERT ON audit_logs
            WHEN NEW.message = 'glucose_calibration_model_updated'
            BEGIN
                SELECT RAISE(FAIL, 'model audit unavailable');
            END
            """.trimIndent()
        )
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_manual_model_retirement
            BEFORE UPDATE ON glucose_calibration_models
            WHEN OLD.status IN ('ACTIVE', 'SHADOW')
            BEGIN
                SELECT RAISE(FAIL, 'model retirement unavailable');
            END
            """.trimIndent()
        )
        var refreshCount = 0
        var overviewModelAtRefresh: GlucoseCalibrationModel? = null
        var widgetAtRefresh: CopilotGlucoseWidgetSnapshot? = null
        lateinit var hookedRepository: GlucoseCalibrationRepository
        hookedRepository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(db.auditLogDao(), Gson()) { NOW },
            onManualCalibrationDurableMutation = { mutation ->
                assertThat(mutation).isEqualTo(ManualCalibrationDurableMutation.ADD)
                refreshCount += 1
                overviewModelAtRefresh = hookedRepository.observeLatestActiveModel().first()
                widgetAtRefresh = CopilotGlucoseWidgetRepository(db) { identity(7L) }
                    .loadSnapshot(NOW)
            }
        )

        val check = hookedRepository.addManualBloodGlucoseCheck(
            value = 6.4,
            units = "mmol/L",
            timestamp = GLUCOSE_TS,
            enteredAt = NOW
        )

        assertThat(check.mmol).isWithin(0.0001).of(6.4)
        assertThat(refreshCount).isEqualTo(1)
        assertThat(overviewModelAtRefresh).isNotNull()
        assertThat(overviewModelAtRefresh).isEqualTo(
            db.glucoseCalibrationModelDao().latestActive()?.toDomain()
        )
        assertThat(widgetAtRefresh?.currentGlucoseMmol).isWithin(0.0001).of(6.4)
        assertThat(widgetAtRefresh?.rawGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(widgetAtRefresh?.predicted30Mmol).isNull()
        assertThat(widgetAtRefresh?.calibrationApplied).isTrue()
    }

    @Test
    fun independentWidgetRefreshAfterAmbiguousManualFinalizationStaysRaw() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L, calibrationModel = null)
        val sessionKey = requireNotNull(
            calibrationSensorSessionKeyFromAgeSample(
                DEFAULT_SENSOR_AGE_TS,
                DEFAULT_SENSOR_AGE_HOURS
            )
        )
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_unproven_model_retirement
            BEFORE UPDATE ON glucose_calibration_models
            WHEN OLD.status IN ('ACTIVE', 'SHADOW')
            BEGIN
                SELECT RAISE(FAIL, 'unproven model retirement unavailable');
            END
            """.trimIndent()
        )
        var callbackCount = 0
        val hookedRepository = repositoryWithAuditHook(
            beforeAuditInsert = { row ->
                if (row.message == "blood_glucose_check_added") {
                    db.glucoseCalibrationModelDao().upsert(
                        GlucoseCalibrationModelEntity(
                            id = "unproven-widget-model",
                            sensorSessionKey = sessionKey,
                            createdAt = NOW,
                            validFromTs = GLUCOSE_TS,
                            validToTs = NOW + 60L * 60L * 1000L,
                            modelType = "OFFSET",
                            gain = 1.0,
                            offsetMmol = 1.5,
                            confidence = 0.9,
                            checkCount = 1,
                            sensorAgeHours = 1.0,
                            lagMinutesAtFit = 0.0,
                            status = "ACTIVE",
                            diagnosticsJson = "{}"
                        )
                    )
                    throw IllegalStateException("manual audit unavailable after orphan model")
                }
            },
            onDurableMutation = { callbackCount += 1 }
        )

        val failure = try {
            hookedRepository.addManualBloodGlucoseCheck(
                value = 6.4,
                units = "mmol/L",
                timestamp = GLUCOSE_TS,
                enteredAt = NOW
            )
            null
        } catch (error: Throwable) {
            error
        }
        val restartedRepository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(db.auditLogDao(), Gson()) { NOW }
        )
        val futureOverviewModel = restartedRepository.observeLatestActiveModel().first()
        val futureWidget = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(callbackCount).isEqualTo(0)
        assertThat(db.glucoseCalibrationModelDao().latestActive()?.id)
            .isEqualTo("unproven-widget-model")
        assertThat(currentCalibrationAuthorityToken()).contains("\"state\":\"PENDING\"")
        assertThat(futureOverviewModel).isNull()
        assertThat(futureWidget.currentGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(futureWidget.rawGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(futureWidget.predicted30Mmol).isNull()
        assertThat(futureWidget.calibrationApplied).isFalse()
    }

    @Test
    fun failedFinalActiveMarkerWriteLeavesPendingForIndependentWidgetRefresh() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L, calibrationModel = null)
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_final_active_authority_marker
            BEFORE INSERT ON telemetry_samples
            WHEN NEW.id = 'glucose-calibration-authority-current'
                AND instr(NEW.valueText, '"state":"ACTIVE"') > 0
            BEGIN
                SELECT RAISE(FAIL, 'final active authority unavailable');
            END
            """.trimIndent()
        )
        var callbackCount = 0
        val hookedRepository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(db.auditLogDao(), Gson()) { NOW },
            onManualCalibrationDurableMutation = { callbackCount += 1 }
        )

        val failure = try {
            hookedRepository.addManualBloodGlucoseCheck(
                value = 6.4,
                units = "mmol/L",
                timestamp = GLUCOSE_TS,
                enteredAt = NOW
            )
            null
        } catch (error: Throwable) {
            error
        }
        val restartedRepository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(db.auditLogDao(), Gson()) { NOW }
        )
        val futureWidget = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(callbackCount).isEqualTo(0)
        assertThat(db.glucoseCalibrationModelDao().latestActive()).isNotNull()
        assertThat(currentCalibrationAuthorityToken()).contains("\"state\":\"PENDING\"")
        assertThat(restartedRepository.observeLatestActiveModel().first()).isNull()
        assertThat(futureWidget.currentGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(futureWidget.rawGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(futureWidget.predicted30Mmol).isNull()
        assertThat(futureWidget.calibrationApplied).isFalse()
    }

    @Test
    fun semanticFailureAfterActiveTokenWriteRollsBackToExactPendingAuthority() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L, calibrationModel = null)
        var callbackCount = 0
        var pendingTokenBeforeReconciliation: String? = null
        val hookedRepository = repositoryWithAuditHook(
            beforeAuditInsert = { row ->
                if (row.message == "glucose_calibration_model_updated") {
                    pendingTokenBeforeReconciliation = currentCalibrationAuthorityToken()
                    db.openHelper.writableDatabase.execSQL(
                        "UPDATE telemetry_samples SET valueDouble = -1.0, valueText = NULL " +
                            "WHERE key IN ('sensor_age_hours', 'isf_factor_sensor_age_hours')"
                    )
                }
            },
            onDurableMutation = { callbackCount += 1 }
        )

        val failure = try {
            hookedRepository.addManualBloodGlucoseCheck(
                value = 6.4,
                units = "mmol/L",
                timestamp = GLUCOSE_TS,
                enteredAt = NOW
            )
            null
        } catch (error: Throwable) {
            error
        }
        val persistedModel = requireNotNull(db.glucoseCalibrationModelDao().latestActive())

        assertThat(failure).hasMessageThat().contains(
            "could not prove coherent durable calibration state"
        )
        assertThat(callbackCount).isEqualTo(0)
        assertThat(pendingTokenBeforeReconciliation).isNotNull()
        assertThat(currentCalibrationAuthorityToken()).isEqualTo(pendingTokenBeforeReconciliation)
        assertThat(CalibrationAuthorityStateCodec.decode(currentCalibrationAuthorityToken())?.state)
            .isEqualTo(io.aaps.copilot.data.repository.DurableCalibrationAuthorityStatus.PENDING)
        assertThat(persistedModel.status).isEqualTo("ACTIVE")

        db.telemetryDao().upsertAll(trustTelemetry())
        val restartedRepository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(db.auditLogDao(), Gson()) { NOW }
        )
        val futureWidget = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(restartedRepository.observeLatestActiveModel().first()).isNull()
        assertThat(futureWidget.currentGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(futureWidget.rawGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(futureWidget.predicted30Mmol).isNull()
        assertThat(futureWidget.calibrationApplied).isFalse()
    }

    @Test
    fun widgetDisplayRejectsActiveModelWhoseDurableFingerprintNoLongerMatches() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        val accepted = acceptedCalibrationModel()
        assertThat(CopilotGlucoseWidgetRepository(db) { identity(7L) }
            .loadSnapshot(NOW).currentGlucoseMmol).isWithin(0.0001).of(5.8)

        db.glucoseCalibrationModelDao().upsert(
            accepted.copy(offsetMmol = accepted.offsetMmol + 0.4)
        )
        val futureWidget = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(futureWidget.currentGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(futureWidget.rawGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(futureWidget.predicted30Mmol).isNull()
        assertThat(futureWidget.calibrationApplied).isFalse()
    }

    @Test
    fun futureCreatedModelFailsClosedForStartupAndWidgetAuthority() = runBlocking {
        val futureModel = acceptedCalibrationModel().copy(
            createdAt = NOW + 1L,
            validFromTs = GLUCOSE_TS - 60_000L
        )
        persistAccepted(base = 6.0, revision = 7L, calibrationModel = futureModel)

        val restarted = AcceptedSensitivityTupleRoomLoader(db).load(identity(7L), NOW)
        val widget = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(restarted).isNull()
        assertThat(widget.currentGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(widget.rawGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(widget.predicted30Mmol).isNull()
        assertThat(widget.calibrationApplied).isFalse()
    }

    @Test
    fun modelCreatedExactlyNowAppliesRetrospectivelyToCurrentHistoryPoint() = runBlocking {
        val model = acceptedCalibrationModel().copy(
            createdAt = NOW,
            validFromTs = GLUCOSE_TS - 60_000L
        )
        val token = CalibrationAuthorityStateCodec.active("created-exactly-now", model.toDomain())
        db.glucoseDao().upsertAll(listOf(glucose(GLUCOSE_TS, 5.0)))
        db.glucoseCalibrationModelDao().upsert(model)
        db.telemetryDao().upsertAll(
            trustTelemetry() + calibrationAuthorityRow(token, NOW)
        )

        val repositoryHistory = calibrationRepository.resolveGlucoseHistory(
            rawGlucose = listOf(glucose(GLUCOSE_TS, 5.0)),
            nowTs = NOW
        ).single()
        val widget = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(repositoryHistory.calibrationModelId).isEqualTo(model.id)
        assertThat(repositoryHistory.calibratedMmol).isWithin(0.0001).of(5.8)
        assertThat(widget.currentGlucoseMmol).isWithin(0.0001).of(5.8)
        assertThat(widget.rawGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(widget.predicted30Mmol).isNull()
        assertThat(widget.calibrationApplied).isTrue()
    }

    @Test
    fun manualCalibrationResetInvalidatesOlderWidgetPredictionAndLeavesRawGlucose() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)

        calibrationRepository.resetManualCalibration(resetAt = NOW - 1L)
        val snapshot = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(snapshot.predicted30Mmol).isNull()
        assertThat(snapshot.currentGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(snapshot.rawGlucoseMmol).isWithin(0.0001).of(5.0)
    }

    @Test
    fun manualResetCallbackRendersRawStateExactlyOnce() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        var refreshCount = 0
        var callbackSnapshot: CopilotGlucoseWidgetSnapshot? = null
        val hookedRepository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(db.auditLogDao(), Gson()) { NOW },
            onManualCalibrationDurableMutation = { mutation ->
                assertThat(mutation).isEqualTo(ManualCalibrationDurableMutation.RESET)
                refreshCount += 1
                callbackSnapshot = CopilotGlucoseWidgetRepository(db) { identity(7L) }
                    .loadSnapshot(NOW)
            }
        )

        hookedRepository.resetManualCalibration(resetAt = NOW - 1L)

        assertThat(refreshCount).isEqualTo(1)
        assertThat(callbackSnapshot?.currentGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(callbackSnapshot?.rawGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(callbackSnapshot?.predicted30Mmol).isNull()
        assertThat(callbackSnapshot?.calibrationApplied).isFalse()
        assertThat(currentCalibrationAuthorityToken()).contains("\"state\":\"RAW\"")
    }

    @Test
    fun manualResetCallbackTimeoutLeavesAcceptedRawGenerationToPublish() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = CopilotGlucoseWidgetPublicationCoordinator(coordinatorScope)
            val oldLoaded = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val published = mutableListOf<CopilotGlucoseWidgetSnapshot>()
            val firstPublished = CompletableDeferred<CopilotGlucoseWidgetSnapshot>()
            val oldReceipt = coordinator.enqueue(
                load = {
                    CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW).also {
                        oldLoaded.complete(Unit)
                        releaseOld.await()
                    }
                },
                publish = {
                    published += it
                    firstPublished.complete(it)
                }
            )
            oldLoaded.await()
            var callbackCount = 0
            val resettingRepository = GlucoseCalibrationRepository(
                db = db,
                gson = Gson(),
                auditLogger = AuditLogger(db.auditLogDao(), Gson()) { NOW },
                onManualCalibrationDurableMutation = { mutation ->
                    assertThat(mutation).isEqualTo(ManualCalibrationDurableMutation.RESET)
                    callbackCount += 1
                    val receipt = coordinator.enqueue(
                        load = {
                            CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)
                        },
                        publish = {
                            published += it
                            firstPublished.complete(it)
                        }
                    )
                    when (val result = receipt.await()) {
                        CopilotGlucoseWidgetPublicationResult.Published,
                        CopilotGlucoseWidgetPublicationResult.Superseded -> Unit
                        is CopilotGlucoseWidgetPublicationResult.Failed -> {
                            throw result.failure.toAwaitedException()
                        }
                    }
                },
                manualWidgetRefreshTimeoutMs = 25L
            )

            resettingRepository.resetManualCalibration(resetAt = NOW - 1L)

            assertThat(callbackCount).isEqualTo(1)
            assertThat(published).isEmpty()
            releaseOld.complete(Unit)
            assertThat(withTimeout(2_000L) { oldReceipt.await() })
                .isEqualTo(CopilotGlucoseWidgetPublicationResult.Superseded)
            val finalSnapshot = withTimeout(2_000L) { firstPublished.await() }
            assertThat(published).containsExactly(finalSnapshot)
            assertThat(finalSnapshot.currentGlucoseMmol).isWithin(0.0001).of(5.0)
            assertThat(finalSnapshot.rawGlucoseMmol).isWithin(0.0001).of(5.0)
            assertThat(finalSnapshot.predicted30Mmol).isNull()
            assertThat(finalSnapshot.calibrationApplied).isFalse()
            Unit
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun failedManualResetTransactionKeepsPriorDurableAuthorityAndSkipsCallback() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        val priorModel = requireNotNull(db.glucoseCalibrationModelDao().latestActive())
        val priorAuthorityToken = currentCalibrationAuthorityToken()
        val priorChecks = db.bloodGlucoseCheckDao().latest(10)
        installActiveModelRetirementFailureTrigger()
        var callbackCount = 0
        val failingRepository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(db.auditLogDao(), Gson()) { NOW },
            onManualCalibrationDurableMutation = { callbackCount += 1 }
        )

        val failure = try {
            failingRepository.resetManualCalibration(resetAt = NOW - 1L)
            null
        } catch (error: Throwable) {
            error
        }
        val independentWidget = CopilotGlucoseWidgetRepository(db) { identity(7L) }
            .loadSnapshot(NOW)

        assertThat(failure).hasMessageThat().contains("active calibration model retirement unavailable")
        assertThat(callbackCount).isEqualTo(0)
        assertThat(db.bloodGlucoseCheckDao().latest(10)).isEqualTo(priorChecks)
        assertThat(db.glucoseCalibrationModelDao().latestActive()).isEqualTo(priorModel)
        assertThat(currentCalibrationAuthorityToken()).isEqualTo(priorAuthorityToken)
        assertThat(failingRepository.latestActiveModel(NOW)).isEqualTo(priorModel.toDomain())
        assertThat(independentWidget.currentGlucoseMmol).isWithin(0.0001).of(5.8)
        assertThat(independentWidget.predicted30Mmol).isEqualTo(6.3)
        assertThat(independentWidget.calibrationApplied).isTrue()
    }

    @Test
    fun exactAcceptedCalibrationModelContentIsRequiredByWidget() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        val accepted = acceptedCalibrationModel()
        val coherent = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)
        assertThat(coherent.predicted30Mmol).isEqualTo(6.3)
        assertThat(coherent.currentGlucoseMmol).isWithin(0.0001).of(5.8)

        db.glucoseCalibrationModelDao().upsert(
            accepted.copy(offsetMmol = accepted.offsetMmol + 0.2)
        )
        val changed = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(changed.predicted30Mmol).isNull()
    }

    @Test
    fun restartLoaderRejectsAcceptedModelAfterSensorSessionRollover() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        db.telemetryDao().upsertAll(listOf(sensorAgeTelemetry("aaps", ageHours = 0.1)))

        val restarted = AcceptedSensitivityTupleRoomLoader(db).load(identity(7L), NOW)

        assertThat(restarted).isNull()
    }

    @Test
    fun widgetUsesRawGlucoseAndNoPredictionAfterSensorSessionRollover() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        db.telemetryDao().upsertAll(listOf(sensorAgeTelemetry("aaps", ageHours = 0.1)))

        val snapshot = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(snapshot.predicted30Mmol).isNull()
        assertThat(snapshot.currentGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(snapshot.rawGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(snapshot.calibrationApplied).isFalse()
    }

    @Test
    fun widgetNeverAppliesExpiredCurrentCalibrationModel() = runBlocking {
        persistAccepted(
            base = 6.0,
            revision = 7L,
            calibrationModel = acceptedCalibrationModel().copy(validToTs = GLUCOSE_TS - 1L)
        )

        val snapshot = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(snapshot.predicted30Mmol).isNull()
        assertThat(snapshot.rawGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(snapshot.currentGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(snapshot.calibrationApplied).isFalse()
    }

    @Test
    fun roomAndWidgetFailClosedWhenFreshSensorConflictIsAtBoundedSentinel() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L)
        val sessionStartTs = DEFAULT_SENSOR_AGE_TS - 3_600_000L
        val matchingRows = (0 until 64).map { index ->
            val timestamp = NOW - index
            sensorAgeTelemetry(
                source = "source-$index",
                timestamp = timestamp,
                ageHours = (timestamp - sessionStartTs).toDouble() / 3_600_000.0
            )
        }
        db.telemetryDao().upsertAll(
            matchingRows + sensorAgeTelemetry(
                source = "conflict",
                timestamp = NOW - 64L,
                ageHours = 0.1
            )
        )

        val restarted = AcceptedSensitivityTupleRoomLoader(db).load(identity(7L), NOW)
        val snapshot = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(restarted).isNull()
        assertThat(snapshot.predicted30Mmol).isNull()
        assertThat(snapshot.rawGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(snapshot.currentGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(snapshot.calibrationApplied).isFalse()
    }

    @Test
    fun sensorEvidenceOverflowRejectsAcceptedRawCalibrationTuple() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L, calibrationModel = null)
        val sessionStartTs = DEFAULT_SENSOR_AGE_TS - 3_600_000L
        db.telemetryDao().upsertAll(
            (0 until 65).map { index ->
                val timestamp = NOW - index
                sensorAgeTelemetry(
                    source = "overflow-$index",
                    timestamp = timestamp,
                    ageHours = (timestamp - sessionStartTs).toDouble() / 3_600_000.0
                )
            }
        )

        val restarted = AcceptedSensitivityTupleRoomLoader(db).load(identity(7L), NOW)
        val snapshot = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(restarted).isNull()
        assertThat(snapshot.predicted30Mmol).isNull()
        assertThat(snapshot.rawGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(snapshot.currentGlucoseMmol).isWithin(0.0001).of(5.0)
    }

    @Test
    fun modelFreeWidgetPredictionRequiresMatchingTrustworthySessionEvidence() = runBlocking {
        persistAccepted(base = 6.0, revision = 7L, calibrationModel = null)
        val repository = CopilotGlucoseWidgetRepository(db) { identity(7L) }

        val matching = repository.loadSnapshot(NOW)
        db.telemetryDao().deleteBySourceAndTimestamp("aaps", DEFAULT_SENSOR_AGE_TS)
        val missing = repository.loadSnapshot(NOW)
        db.telemetryDao().upsertAll(listOf(sensorAgeTelemetry("aaps", ageHours = 0.1)))
        val rollover = repository.loadSnapshot(NOW)
        db.telemetryDao().upsertAll(listOf(sensorAgeTelemetry("xdrip", ageHours = 8.0)))
        val conflicting = repository.loadSnapshot(NOW)

        assertThat(matching.predicted30Mmol).isEqualTo(6.3)
        assertThat(matching.currentGlucoseMmol).isWithin(0.0001).of(5.0)
        listOf(missing, rollover, conflicting).forEach { snapshot ->
            assertThat(snapshot.predicted30Mmol).isNull()
            assertThat(snapshot.rawGlucoseMmol).isWithin(0.0001).of(5.0)
            assertThat(snapshot.currentGlucoseMmol).isWithin(0.0001).of(5.0)
        }
    }

    @Test
    fun authorityTokenOlderThanOverviewWindowAuthenticatesAcceptedTupleAfterRestart() = runBlocking {
        val authorityToken = CalibrationAuthorityStateCodec.active(
            nonce = "manual-authority-before-restart",
            model = acceptedCalibrationModel().toDomain()
        )
        persistAccepted(base = 6.0, revision = 7L, authorityToken = authorityToken)
        db.telemetryDao().upsertAll(
            listOf(
                TelemetrySampleEntity(
                    id = "glucose-calibration-authority-current",
                    timestamp = NOW - 24L * 60L * 60L * 1000L - 1L,
                    source = CALIBRATION_AUTHORITY_SOURCE,
                    key = CALIBRATION_AUTHORITY_TOKEN_KEY,
                    valueDouble = null,
                    valueText = authorityToken,
                    unit = null,
                    quality = "OK"
                )
            )
        )

        val observedToken = db.telemetryDao().observeCurrentBySourceAndKey(
            source = CALIBRATION_AUTHORITY_SOURCE,
            key = CALIBRATION_AUTHORITY_TOKEN_KEY
        ).first()
        val restartedTuple = AcceptedSensitivityTupleRoomLoader(db).load(identity(7L), NOW)
        val restartedWidget = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(observedToken?.valueText).isEqualTo(authorityToken)
        assertThat(restartedTuple?.snapshot?.forecastCycleId).isEqualTo(CYCLE_ID)
        assertThat(restartedWidget.predicted30Mmol).isEqualTo(6.3)
    }

    @Test
    fun legacyOpaqueAuthorityFailsClosedForRestartedWidgetDisplayAndPrediction() = runBlocking {
        persistAccepted(
            base = 6.0,
            revision = 7L,
            authorityToken = "legacy-opaque-manual-authority"
        )
        val restartedRepository = GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(db.auditLogDao(), Gson()) { NOW }
        )

        val widget = CopilotGlucoseWidgetRepository(db) { identity(7L) }.loadSnapshot(NOW)

        assertThat(restartedRepository.observeLatestActiveModel().first()).isNull()
        assertThat(widget.currentGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(widget.rawGlucoseMmol).isWithin(0.0001).of(5.0)
        assertThat(widget.predicted30Mmol).isNull()
        assertThat(widget.calibrationApplied).isFalse()
    }

    @Test
    fun preFeatureMarkerlessTupleStaysNonAuthoritativeAcrossManualResetAndAdd() = runBlocking {
        persistAccepted(
            base = 6.0,
            revision = 7L,
            calibrationModel = null,
            includeCalibrationMarkers = false
        )
        val repositoryAfterUpgrade = CopilotGlucoseWidgetRepository(db) { identity(7L) }

        assertThat(repositoryAfterUpgrade.loadSnapshot(NOW).predicted30Mmol).isNull()

        calibrationRepository.resetManualCalibration(resetAt = NOW - 1L)
        assertThat(repositoryAfterUpgrade.loadSnapshot(NOW).predicted30Mmol).isNull()

        calibrationRepository.addManualBloodGlucoseCheck(
            value = 6.4,
            units = "mmol/L",
            timestamp = GLUCOSE_TS,
            enteredAt = NOW
        )
        assertThat(repositoryAfterUpgrade.loadSnapshot(NOW).predicted30Mmol).isNull()
    }

    private suspend fun persistAccepted(
        base: Double,
        revision: Long,
        authorityToken: String? = null,
        calibrationModel: GlucoseCalibrationModelEntity? = acceptedCalibrationModel(),
        includeCalibrationMarkers: Boolean = true,
        includeGlucose: Boolean = true,
        includeSensorEvidence: Boolean = true
    ) {
        val forecasts = forecastSet(base)
        val sessionKey = calibrationModel?.sensorSessionKey ?: if (includeSensorEvidence) {
            requireNotNull(
                calibrationSensorSessionKeyFromAgeSample(DEFAULT_SENSOR_AGE_TS, DEFAULT_SENSOR_AGE_HOURS)
            )
        } else {
            null
        }
        val effectiveAuthorityToken = authorityToken ?: calibrationModel?.let { model ->
            CalibrationAuthorityStateCodec.active(
                nonce = "widget-accepted-authority",
                model = model.toDomain()
            )
        } ?: CalibrationAuthorityStateCodec.raw(
            nonce = "widget-accepted-authority",
            sessionKey = sessionKey
        )
        if (includeGlucose) {
            db.glucoseDao().upsertAll(
                listOf(
                    glucose(GLUCOSE_TS - 15L * 60_000L, 4.4),
                    glucose(GLUCOSE_TS - 10L * 60_000L, 4.6),
                    glucose(GLUCOSE_TS - 5L * 60_000L, 4.8),
                    glucose(GLUCOSE_TS, 5.0)
                )
            )
        }
        calibrationModel?.let { db.glucoseCalibrationModelDao().upsert(it) }
        db.telemetryDao().upsertAll(
            trustTelemetry().filter { includeSensorEvidence || it.key != "sensor_age_hours" } +
                if (includeCalibrationMarkers) {
                    acceptedCalibrationMarkers(calibrationModel, effectiveAuthorityToken, sessionKey) +
                        calibrationAuthorityRow(effectiveAuthorityToken, MARKER_TS)
                } else {
                    emptyList()
                }
        )
        db.forecastDao().insertAll(forecasts)
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot(revision))
        val decomposition = decomposition()
        db.telemetryDao().upsertAcceptedSensitivityTuple(
            listOf(
                marker(SENSITIVITY_ACCEPTED_CYCLE_ID_KEY, valueText = CYCLE_ID),
                marker(SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY, valueDouble = revision.toDouble()),
                marker(SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY, valueDouble = GENERATION_TS.toDouble()),
                marker(
                    SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY,
                    valueText = SensitivityAcceptedForecastDecompositionCodec.encode(decomposition)
                ),
                marker(
                    SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY,
                    valueText = SensitivityAcceptedForecastDigest.compute(
                        cycleId = CYCLE_ID,
                        settingsRevision = revision,
                        forecasts = forecasts.map { it.digestRow() },
                        decomposition = decomposition
                    )
                ),
                marker(
                    SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY,
                    valueText = SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED
                )
            )
        )
    }

    private fun repositoryWithAuditHook(
        beforeAuditInsert: suspend (AuditLogEntity) -> Unit,
        onDurableMutation: suspend (ManualCalibrationDurableMutation) -> Unit
    ): GlucoseCalibrationRepository {
        val delegate = db.auditLogDao()
        val auditDao = object : AuditLogDao {
            override suspend fun insert(entity: AuditLogEntity) {
                beforeAuditInsert(entity)
                delegate.insert(entity)
            }

            override fun observeLatest(limit: Int): Flow<List<AuditLogEntity>> =
                delegate.observeLatest(limit)

            override suspend fun recentByMessage(
                message: String,
                sinceTs: Long,
                limit: Int
            ): List<AuditLogEntity> = delegate.recentByMessage(message, sinceTs, limit)

            override suspend fun deleteOlderThan(olderThan: Long): Int =
                delegate.deleteOlderThan(olderThan)

            override suspend fun deleteOlderThanInfoMessages(
                olderThan: Long,
                messages: List<String>
            ): Int = delegate.deleteOlderThanInfoMessages(olderThan, messages)
        }
        return GlucoseCalibrationRepository(
            db = db,
            gson = Gson(),
            auditLogger = AuditLogger(auditDao, Gson()) { NOW },
            onManualCalibrationDurableMutation = onDurableMutation
        )
    }

    private fun installPendingAuthorityWriteFailureTrigger() {
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_pending_calibration_authority
            BEFORE INSERT ON telemetry_samples
            WHEN NEW.id = 'glucose-calibration-authority-current'
                AND instr(NEW.valueText, '"state":"PENDING"') > 0
            BEGIN
                SELECT RAISE(ABORT, 'pending calibration authority unavailable');
            END
            """.trimIndent()
        )
    }

    private fun installActiveModelRetirementFailureTrigger() {
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_active_calibration_model_retirement
            BEFORE UPDATE ON glucose_calibration_models
            WHEN OLD.status IN ('ACTIVE', 'SHADOW')
            BEGIN
                SELECT RAISE(ABORT, 'active calibration model retirement unavailable');
            END
            """.trimIndent()
        )
    }

    private fun acceptedCalibrationModel() = GlucoseCalibrationModelEntity(
        id = "accepted-calibration-model",
        sensorSessionKey = requireNotNull(
            calibrationSensorSessionKeyFromAgeSample(DEFAULT_SENSOR_AGE_TS, DEFAULT_SENSOR_AGE_HOURS)
        ),
        createdAt = GENERATION_TS - 60_000L,
        validFromTs = GENERATION_TS - 60_000L,
        validToTs = NOW + 72L * 60L * 60L * 1000L,
        modelType = "OFFSET",
        gain = 1.0,
        offsetMmol = 0.8,
        confidence = 0.9,
        checkCount = 2,
        sensorAgeHours = 4.0,
        lagMinutesAtFit = 10.0,
        status = "ACTIVE",
        diagnosticsJson = "{\"source\":\"accepted\"}"
    )

    private fun acceptedCalibrationMarkers(
        model: GlucoseCalibrationModelEntity?,
        authorityToken: String = ACCEPTED_CALIBRATION_NONE,
        sessionKey: String? = model?.sensorSessionKey ?: requireNotNull(
            calibrationSensorSessionKeyFromAgeSample(DEFAULT_SENSOR_AGE_TS, DEFAULT_SENSOR_AGE_HOURS)
        )
    ): List<TelemetrySampleEntity> {
        return listOf(
        calibrationMarker(ACCEPTED_CALIBRATION_MODEL_ID_KEY, model?.id ?: ACCEPTED_CALIBRATION_NONE),
        calibrationMarker(ACCEPTED_CALIBRATION_SESSION_KEY, sessionKey ?: ACCEPTED_CALIBRATION_NONE),
        calibrationMarker(ACCEPTED_CALIBRATION_PREPARED_AT_KEY, GENERATION_TS.toString()),
        calibrationMarker(
            ACCEPTED_CALIBRATION_MODEL_FINGERPRINT_KEY,
            model?.toDomain()?.let(::acceptedCalibrationModelFingerprint) ?: ACCEPTED_CALIBRATION_NONE
        ),
        calibrationMarker(ACCEPTED_CALIBRATION_AUTHORITY_TOKEN_KEY, authorityToken)
    )
    }

    private fun calibrationAuthorityRow(value: String, timestamp: Long) = TelemetrySampleEntity(
        id = "glucose-calibration-authority-current",
        timestamp = timestamp,
        source = CALIBRATION_AUTHORITY_SOURCE,
        key = CALIBRATION_AUTHORITY_TOKEN_KEY,
        valueDouble = null,
        valueText = value,
        unit = null,
        quality = "OK"
    )

    private suspend fun currentCalibrationAuthorityToken(): String = requireNotNull(
        db.telemetryDao().currentBySourceAndKey(
            source = CALIBRATION_AUTHORITY_SOURCE,
            key = CALIBRATION_AUTHORITY_TOKEN_KEY
        )?.valueText
    )

    private fun calibrationMarker(key: String, value: String) = TelemetrySampleEntity(
        id = "accepted-calibration:$key",
        timestamp = MARKER_TS,
        source = ACCEPTED_CALIBRATION_SOURCE,
        key = key,
        valueDouble = null,
        valueText = value,
        unit = null,
        quality = "OK"
    )

    private fun trustTelemetry() = listOf(
        telemetry("sensor_lag_minutes", 10.0, GLUCOSE_TS),
        telemetry("sensor_quality_score", 1.0, GLUCOSE_TS),
        telemetry("sensor_quality_blocked", 0.0, GLUCOSE_TS),
        telemetry("sensor_quality_suspect_false_low", 0.0, GLUCOSE_TS),
        sensorAgeTelemetry("aaps", DEFAULT_SENSOR_AGE_TS, DEFAULT_SENSOR_AGE_HOURS)
    )

    private fun sensorAgeTelemetry(
        source: String,
        timestamp: Long = NOW,
        ageHours: Double
    ) = TelemetrySampleEntity(
        id = "widget:sensor-age:$source:$timestamp:$ageHours",
        timestamp = timestamp,
        source = source,
        key = "sensor_age_hours",
        valueDouble = ageHours,
        valueText = null,
        unit = "h",
        quality = "OK"
    )

    private fun telemetry(key: String, value: Double, timestamp: Long) = TelemetrySampleEntity(
        id = "widget:$key:$timestamp",
        timestamp = timestamp,
        source = "test",
        key = key,
        valueDouble = value,
        valueText = null,
        unit = null,
        quality = "OK"
    )

    private fun glucose(timestamp: Long, mmol: Double) = GlucoseSampleEntity(
        timestamp = timestamp,
        mmol = mmol,
        source = "test",
        quality = "OK"
    )

    private fun identity(revision: Long) = SensitivityRuntimeSettingsIdentity(
        revision = revision,
        isfSource = SensitivitySourcePreference.COPILOT,
        crSource = SensitivitySourcePreference.COPILOT
    )

    private fun forecastSet(base: Double) = listOf(5, 30, 60).map { horizon ->
        ForecastEntity(
            timestamp = GENERATION_TS + horizon * 60_000L,
            horizonMinutes = horizon,
            valueMmol = base + horizon / 100.0,
            ciLow = base - 0.5,
            ciHigh = base + 0.5,
            modelVersion = "local-v3"
        )
    }

    private fun ForecastEntity.digestRow() = SensitivityAcceptedForecastRow(
        horizonMinutes = horizonMinutes,
        targetTimestamp = timestamp,
        valueMmol = valueMmol,
        ciLow = ciLow,
        ciHigh = ciHigh,
        modelVersion = modelVersion
    )

    private fun decomposition() = SensitivityAcceptedForecastDecomposition(
        trend60Mmol = 0.7,
        therapy60Mmol = -0.4,
        uam60Mmol = 0.2,
        residualRoc0Mmol5 = 0.05,
        sigmaEMmol5 = 0.11,
        kfSigmaGMmol = 0.09,
        modelVersion = "local-v3"
    )

    private fun snapshot(revision: Long) = SensitivityRuntimeSnapshotEntity(
        cycleId = CYCLE_ID,
        settingsRevision = revision,
        generatedAt = GENERATION_TS,
        isfRequestedSource = "COPILOT",
        isfResolvedSource = "COPILOT_NATIVE",
        isfRawAaps = null,
        isfRawEvidence = null,
        isfRawCopilot = 3.0,
        isfBlended = null,
        isfEffective = 3.0,
        isfConfidence = 1.0,
        isfFallbackReason = null,
        crRequestedSource = "COPILOT",
        crResolvedSource = "COPILOT_NATIVE",
        crRawAaps = null,
        crRawEvidence = null,
        crRawCopilot = 10.0,
        crBlended = null,
        crEffective = 10.0,
        crConfidence = 1.0,
        crFallbackReason = null
    )

    private fun marker(key: String, valueDouble: Double? = null, valueText: String? = null) =
        TelemetrySampleEntity(
            id = key,
            timestamp = MARKER_TS,
            source = SENSITIVITY_ACCEPTED_SOURCE,
            key = key,
            valueDouble = valueDouble,
            valueText = valueText,
            unit = null,
            quality = "OK"
        )

    private companion object {
        const val CYCLE_ID = "cycle-widget"
        const val GENERATION_TS = 1_800_000_000_000L
        const val MARKER_TS = GENERATION_TS + 60_000L
        const val NOW = MARKER_TS + 1_000L
        const val GLUCOSE_TS = NOW - 10_000L
        const val DEFAULT_SENSOR_AGE_TS = NOW - 60_000L
        const val DEFAULT_SENSOR_AGE_HOURS = 1.0
        val ACCEPTED_KEYS = listOf(
            SENSITIVITY_ACCEPTED_CYCLE_ID_KEY,
            SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY,
            SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY,
            SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY,
            SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY,
            SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
        )
    }
}
