package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_CYCLE_ID_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_SOURCE
import io.aaps.copilot.domain.predict.SensitivityRuntimeSettingsIdentity
import io.aaps.copilot.domain.predict.SensitivitySourcePreference
import io.aaps.copilot.ui.loadUiCalibrationAuthority
import io.aaps.copilot.widget.CopilotGlucoseWidgetRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CopilotPhoneAcceptedTupleTest {

    @Test
    fun latestCommittedPhoneTuplePassesRealRoomReadback() = runBlocking {
        val path = System.getenv("COPILOT_PHONE_ACCEPTED_DB_COPY")?.takeIf(String::isNotBlank)
        Assume.assumeTrue("Provide a standalone disposable phone database copy", path != null)
        val source = File(requireNotNull(path)).absoluteFile
        require(source.isFile && source.canRead())
        require(!File("${source.path}-wal").exists() && !File("${source.path}-shm").exists()) {
            "Use a standalone SQLite backup, never a hot database file"
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "phone-accepted-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, CopilotDatabase::class.java, databaseName)
            .createFromFile(source)
            .build()
        try {
            val marker = requireNotNull(db.telemetryDao().committedMarkerTimestampsPageAtOrBefore(
                source = SENSITIVITY_ACCEPTED_SOURCE,
                publicationStateKey = SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY,
                committedState = SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED,
                atTs = Long.MAX_VALUE,
                beforeTimestamp = null,
                limit = 1
            ).singleOrNull()) { "The copy has no committed sensitivity tuple" }
            val cycleId = requireNotNull(db.telemetryDao().atTimestampBySourceAndKeys(
                source = SENSITIVITY_ACCEPTED_SOURCE,
                timestamp = marker,
                keys = listOf(SENSITIVITY_ACCEPTED_CYCLE_ID_KEY)
            ).single().valueText)
            val snapshot = requireNotNull(db.sensitivityRuntimeSnapshotDao().byCycleId(cycleId))
            val identity = SensitivityRuntimeSettingsIdentity(
                revision = snapshot.settingsRevision,
                isfSource = SensitivitySourcePreference.valueOf(snapshot.isfRequestedSource),
                crSource = SensitivitySourcePreference.valueOf(snapshot.crRequestedSource)
            )

            val accepted = AcceptedSensitivityTupleRoomLoader(db).loadExact(identity, marker, marker)

            var calibrationDiagnostic = ""
            val diagnostic = db.withTransaction {
                val markers = db.telemetryDao().atTimestampBySourceAndKeys(
                    SENSITIVITY_ACCEPTED_SOURCE, marker, ACCEPTED_SENSITIVITY_MARKER_KEYS
                )
                val generation = requireNotNull(markers.single {
                    it.key == SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY
                }.valueDouble).toLong()
                val authority = loadCurrentGlucoseCalibrationAuthorityInTransaction(db, marker)
                val calibrationRows = db.telemetryDao().atTimestampBySourceAndKeys(
                    ACCEPTED_CALIBRATION_SOURCE, marker, ACCEPTED_CALIBRATION_KEYS
                )
                fun calibrationValue(key: String) = calibrationRows.singleOrNull { it.key == key }?.valueText
                val sessionContext = loadCalibrationSessionContextInTransaction(
                    db, marker, authority.causalGlucose.authorityPointTs
                )
                val ageEvidence = db.telemetryDao().latestByKeysInWindow(
                    CalibrationModelAuthority.SESSION_EVIDENCE_KEYS,
                    marker - CalibrationModelAuthority.SESSION_EVIDENCE_MAX_AGE_MS,
                    marker,
                    CalibrationModelAuthority.SESSION_EVIDENCE_QUERY_LIMIT + 1
                )
                val checks = db.bloodGlucoseCheckDao().recentForCalibrationSession(
                    marker - CALIBRATION_SESSION_CHECK_MAX_AGE_MS,
                    marker,
                    CALIBRATION_SESSION_CHECK_LIMIT + 1
                )
                val anchor = selectLatestCalibrationSessionAnchor(checks.map { it.toDomain() })
                val sensorBoundary = anchor?.let {
                    db.therapyDao().sensorBoundaryAfterForCalibrationSession(it.timestamp, marker)
                }
                val timestamps = anchor?.let {
                    db.glucoseDao().validTimestampsForCalibrationSession(
                        it.timestamp - CALIBRATION_SESSION_GAP_MS,
                        marker,
                        CALIBRATION_SESSION_GLUCOSE_LIMIT + 1
                    )
                }.orEmpty()
                val gaps = timestamps.zipWithNext().filter { (previous, next) ->
                    anchor != null && next > anchor.timestamp && next - previous >= CALIBRATION_SESSION_GAP_MS
                }
                val durable = CalibrationAuthorityStateCodec.decode(authority.authorityToken)
                val preparedAt = calibrationValue(ACCEPTED_CALIBRATION_PREPARED_AT_KEY)?.toLongOrNull()
                val forecasts = db.forecastDao().atGenerationTimestamp(generation)
                val withoutCalibration = ForecastSnapshotResolver.resolveAcceptedTuple(
                    forecasts = forecasts,
                    telemetry = markers,
                    snapshot = snapshot.toSensitivityRuntimeSnapshotOrNull(),
                    currentSettings = identity,
                    authoritativeNowTs = marker
                )
                assertWithMessage("Independent sensitivity, freshness, horizons and digest checks")
                    .that(withoutCalibration.error).isNull()
                // Report gate results only, never authority tokens, session IDs or model payloads.
                calibrationDiagnostic = listOf(
                    "generationAgeMs=${marker - generation}",
                    "snapshotAgeMs=${marker - snapshot.generatedAt}",
                    "markerRows=${markers.size}",
                    "calibrationRows=${calibrationRows.size}",
                    "preparedAtValid=${preparedAt != null && preparedAt in 1L..marker}",
                    "authorityTokenMatches=${calibrationValue(ACCEPTED_CALIBRATION_AUTHORITY_TOKEN_KEY) == authority.authorityToken}",
                    "sessionContextValid=${sessionContext.contextValid}",
                    "rawWithoutSessionAllowed=${sessionContext.rawWithoutSessionAllowed}",
                    "authorityContextValid=${authority.context.contextValid}",
                    "ageEvidenceRows=${ageEvidence.size}",
                    "checkRows=${checks.size}",
                    "anchorTimestamp=${anchor?.timestamp}",
                    "sensorBoundaryPresent=${sensorBoundary != null}",
                    "glucoseTimestampRows=${timestamps.size}",
                    "gapCount=${gaps.size}",
                    "firstGap=${gaps.firstOrNull()}",
                    "durableState=${durable?.state}",
                    "durableSessionless=${durable?.sessionKey == null}",
                    "markerSessionless=${calibrationValue(ACCEPTED_CALIBRATION_SESSION_KEY) == ACCEPTED_CALIBRATION_NONE}",
                    "markerModelAbsent=${calibrationValue(ACCEPTED_CALIBRATION_MODEL_ID_KEY) == ACCEPTED_CALIBRATION_NONE}",
                    "markerFingerprintAbsent=${calibrationValue(ACCEPTED_CALIBRATION_MODEL_FINGERPRINT_KEY) == ACCEPTED_CALIBRATION_NONE}",
                    "readerModelAbsent=${authority.activeModel == null}",
                    "sessionMatches=${calibrationValue(ACCEPTED_CALIBRATION_SESSION_KEY) == (authority.context.currentSessionKey ?: ACCEPTED_CALIBRATION_NONE)}"
                ).joinToString(", ")
                ForecastSnapshotResolver.resolveAcceptedTuple(
                    forecasts = forecasts,
                    telemetry = markers + calibrationRows,
                    snapshot = snapshot.toSensitivityRuntimeSnapshotOrNull(),
                    currentSettings = identity,
                    authoritativeNowTs = marker,
                    calibrationAuthority = AcceptedCalibrationAuthority(
                        activeModel = authority.activeModel,
                        authorityToken = authority.authorityToken ?: ACCEPTED_CALIBRATION_NONE,
                        currentSessionKey = authority.context.currentSessionKey,
                        contextValid = authority.context.contextValid
                    )
                )
            }
            assertWithMessage(calibrationDiagnostic).that(diagnostic.error).isNull()
            val token = db.telemetryDao().currentBySourceAndKey(
                CALIBRATION_AUTHORITY_SOURCE, CALIBRATION_AUTHORITY_TOKEN_KEY
            )?.valueText
            val uiAuthority = loadUiCalibrationAuthority(db, marker, token)
            assertThat(uiAuthority.contextValid).isTrue()
            assertThat(uiAuthority.currentSessionKey).isEqualTo(accepted?.accepted?.calibrationSessionKey)
            assertThat(uiAuthority.activeModel).isEqualTo(accepted?.calibrationModel)
            assertThat(accepted).isNotNull()
            assertThat(accepted?.snapshotEntity).isEqualTo(snapshot)
            assertThat(accepted?.acceptedAtTs).isEqualTo(marker)
            assertThat(accepted?.accepted?.error).isNull()
            assertThat(accepted?.forecasts?.map { it.horizonMinutes })
                .containsExactly(5, 30, 60)
            val widget = CopilotGlucoseWidgetRepository(db) { identity }.loadSnapshot(marker)
            assertThat(widget.predicted30Mmol)
                .isEqualTo(accepted?.accepted?.forecastsByHorizon?.get(30)?.valueMmol)
        } finally {
            db.close()
            context.deleteDatabase(databaseName)
        }
        Unit
    }
}
