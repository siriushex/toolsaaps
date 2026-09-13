package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.IsfCrModelStateEntity
import io.aaps.copilot.data.local.entity.IsfCrSnapshotEntity
import io.aaps.copilot.data.local.entity.ProfileEstimateEntity
import io.aaps.copilot.data.local.entity.SensitivityRuntimeSnapshotEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_CYCLE_ID_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_PENDING
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_SOURCE
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDigest
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDecomposition
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDecompositionCodec
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastRow
import io.aaps.copilot.domain.predict.SensitivityCandidate
import io.aaps.copilot.domain.predict.SensitivityCandidates
import io.aaps.copilot.domain.predict.SensitivityResolvedSource
import io.aaps.copilot.domain.predict.SensitivityRuntimeResolver
import io.aaps.copilot.domain.predict.SensitivityRuntimeSettingsIdentity
import io.aaps.copilot.domain.predict.SensitivitySourcePreference
import io.aaps.copilot.widget.CopilotGlucoseWidgetRepository
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SensitivityRuntimeSnapshotDaoRoomTest {

    private lateinit var db: CopilotDatabase
    private var testSensorSessionStartTs: Long? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun exactReadbackAcceptsSensitivityCalculatedAfterTheForecastSampleOrigin() = runBlocking {
        val publicationTs = 1_788_070_201_788L
        val sampleTs = publicationTs - 59_493L
        val sensitivityTs = publicationTs - 55L
        val cycleId = "delayed-sample-cycle"
        val runtime = snapshot(cycleId, 9L, sensitivityTs)
        val forecasts = forecastSet(sampleTs, base = 6.0)
        db.sensitivityRuntimeSnapshotDao().upsert(runtime)
        db.forecastDao().insertAll(forecasts)
        persistAcceptedTuple(
            acceptedTuple(publicationTs, cycleId, 9L, sampleTs, forecasts)
        )

        val accepted = AcceptedSensitivityTupleRoomLoader(db).loadExact(
            currentSettings = identity(9L),
            acceptedAtTs = publicationTs,
            atTs = publicationTs
        )

        assertThat(accepted).isNotNull()
        assertThat(accepted?.snapshotEntity).isEqualTo(runtime)
        assertThat(accepted?.accepted?.generationTimestamp).isEqualTo(sampleTs)
        assertThat(accepted?.forecasts?.map { it.timestamp })
            .containsExactlyElementsIn(forecasts.map { it.timestamp })
        Unit
    }

    @Test
    fun exactReadbackSupportsExplicitRawGlucoseWhenSensorAgeIsNotProvided() = runBlocking {
        val now = 1_788_070_201_788L
        val cycleId = "raw-no-sensor-age-cycle"
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot(cycleId, 9L, now))
        val forecasts = forecastSet(now, base = 6.0)
        db.forecastDao().insertAll(forecasts)
        persistAcceptedTuple(
            rows = acceptedTuple(now, cycleId, 9L, now, forecasts),
            includeSensorEvidence = false
        )

        val accepted = AcceptedSensitivityTupleRoomLoader(db).loadExact(identity(9L), now, now)

        assertThat(accepted).isNotNull()
        assertThat(accepted?.calibrationModel).isNull()
        assertThat(accepted?.accepted?.calibrationSessionKey).isNull()
        assertThat(accepted?.forecasts).hasSize(3)
    }

    @Test
    fun staleSavedRowIsHistoryOnlyAndExcludedFromCurrentRevisionQuery() = runBlocking {
        val dao = db.sensitivityRuntimeSnapshotDao()
        dao.upsert(snapshot(cycleId = "stale-cycle", revision = 8L, generatedAt = 1_000L))

        assertThat(dao.latest()).isNotNull()
        assertThat(
            dao.latestAuthoritativeAtOrBefore(atTs = 2_000L, currentRevision = 9L)
        ).isNull()

        dao.upsert(snapshot(cycleId = "current-cycle", revision = 9L, generatedAt = 1_500L))

        assertThat(
            dao.latestAuthoritativeAtOrBefore(atTs = 2_000L, currentRevision = 9L)?.cycleId
        ).isEqualTo("current-cycle")
    }

    @Test
    fun concurrentRevisionChangeKeepsReportFailClosedAgainstLooseTelemetry() = runBlocking {
        val now = 1_785_556_800_000L
        val signalTs = now - 60_000L
        db.telemetryDao().upsertAll(
            listOf(
                telemetry(signalTs, "isf_runtime_source_resolved", 3.0),
                telemetry(signalTs, "isf_runtime_selected_value", 1.1),
                telemetry(signalTs, "cr_runtime_source_resolved", 3.0),
                telemetry(signalTs, "cr_runtime_selected_value", 59.0)
            )
        )
        var revisionReads = 0
        val gson = Gson()
        val builder = ClinicalReportDatasetBuilder(
            db = db,
            glucoseCalibrationRepository = GlucoseCalibrationRepository(
                db = db,
                gson = gson,
                auditLogger = AuditLogger(db.auditLogDao(), gson) { now }
            ),
            aapsTddSource = ClinicalAapsTddSource { _, _, _, _ -> null },
            sensitivitySettingsIdentity = {
                revisionReads += 1
                identity(if (revisionReads == 1) 9L else 10L)
            }
        )

        val current = builder.build(now, ZoneId.of("UTC")).dataset.currentSnapshot

        assertThat(current.selectedIsfMmolPerUnit).isNull()
        assertThat(current.selectedCrGramsPerUnit).isNull()
        assertThat(current.sensitivitySettingsRevision).isNull()
    }

    @Test
    fun reportUsesOlderAcceptedTupleAndExcludesNewerUnacceptedRowAtSameRevision() = runBlocking {
        val now = 1_785_556_800_000L
        val acceptedForecastTs = now - 60_000L
        db.sensitivityRuntimeSnapshotDao().upsert(
            snapshot("accepted-cycle", 9L, acceptedForecastTs, isf = 3.2, cr = 11.0)
        )
        val acceptedForecasts = forecastSet(acceptedForecastTs, base = 6.0)
        db.forecastDao().insertAll(acceptedForecasts)
        persistAcceptedTuple(
            acceptedTuple(
                markerTs = now - 30_000L,
                cycleId = "accepted-cycle",
                revision = 9L,
                forecastTs = acceptedForecastTs,
                forecasts = acceptedForecasts
            )
        )
        db.sensitivityRuntimeSnapshotDao().upsert(
            snapshot("failed-cycle", 9L, now - 10_000L, isf = 1.1, cr = 59.0)
        )

        val current = reportCurrent(now, revision = 9L)

        assertThat(current.sensitivityCycleId).isEqualTo("accepted-cycle")
        assertThat(current.selectedIsfMmolPerUnit).isEqualTo(3.2)
        assertThat(current.selectedCrGramsPerUnit).isEqualTo(11.0)
        assertThat(current.prediction30mMmol).isEqualTo(6.3)
        assertThat(current.prediction30mAgeMinutes).isEqualTo(1.0)
    }

    @Test
    fun authenticatedClinicalForecastAuthorityPreservesEveryCommittedFieldAndDigest() = runBlocking {
        val now = 1_785_556_800_000L
        val generationTs = now - 60_000L
        val cycleId = "exact-clinical-authority"
        val revision = 109L
        val forecasts = forecastSet(generationTs, base = 7.0).mapIndexed { index, row ->
            row.copy(
                valueMmol = row.valueMmol + index * 0.17,
                ciLow = row.ciLow - index * 0.11,
                ciHigh = row.ciHigh + index * 0.13,
                modelVersion = "exact-model-${row.horizonMinutes}"
            )
        }
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot(cycleId, revision, generationTs))
        db.forecastDao().insertAll(forecasts)
        persistAcceptedTuple(
            acceptedTuple(now - 30_000L, cycleId, revision, generationTs, forecasts)
        )
        val roomTuple = requireNotNull(
            AcceptedSensitivityTupleRoomLoader(db).loadExact(
                currentSettings = identity(revision),
                acceptedAtTs = now - 30_000L,
                atTs = now
            )
        )

        val authority = AutomationRepository.requireAcceptedClinicalForecastsStatic(roomTuple)
        val expectedDigest = SensitivityAcceptedForecastDigest.compute(
            cycleId = cycleId,
            settingsRevision = revision,
            forecasts = forecasts.map { row ->
                SensitivityAcceptedForecastRow(
                    horizonMinutes = row.horizonMinutes,
                    targetTimestamp = row.timestamp,
                    valueMmol = row.valueMmol,
                    ciLow = row.ciLow,
                    ciHigh = row.ciHigh,
                    modelVersion = row.modelVersion
                )
            },
            decomposition = SensitivityAcceptedForecastDecomposition.unavailable()
        )

        assertThat(authority.generationTimestamp).isEqualTo(generationTs)
        assertThat(authority.digest).isEqualTo(expectedDigest)
        assertThat(authority.forecasts.map { it.horizonMinutes }).containsExactly(5, 30, 60).inOrder()
        assertThat(authority.forecasts.map { it.ts }).containsExactlyElementsIn(
            forecasts.map { it.timestamp }
        ).inOrder()
        assertThat(authority.forecasts.map { it.valueMmol }).containsExactlyElementsIn(
            forecasts.map { it.valueMmol }
        ).inOrder()
        assertThat(authority.forecasts.map { it.ciLow }).containsExactlyElementsIn(
            forecasts.map { it.ciLow }
        ).inOrder()
        assertThat(authority.forecasts.map { it.ciHigh }).containsExactlyElementsIn(
            forecasts.map { it.ciHigh }
        ).inOrder()
        assertThat(authority.forecasts.map { it.modelVersion }).containsExactlyElementsIn(
            forecasts.map { it.modelVersion }
        ).inOrder()
    }

    @Test
    fun reportAcceptsSnapshotOnlyWithMatchingTupleAndExactFiveThirtySixtyForecastSet() = runBlocking {
        val now = 1_785_556_800_000L
        val forecastTs = now - 60_000L
        db.sensitivityRuntimeSnapshotDao().upsert(
            snapshot("complete-cycle", 12L, forecastTs, isf = 2.7, cr = 9.5)
        )
        val completeForecasts = forecastSet(forecastTs, base = 5.0)
        db.forecastDao().insertAll(completeForecasts)
        persistAcceptedTuple(
            acceptedTuple(now - 30_000L, "complete-cycle", 12L, forecastTs, completeForecasts)
        )

        val current = reportCurrent(now, revision = 12L)

        assertThat(current.sensitivityCycleId).isEqualTo("complete-cycle")
        assertThat(current.sensitivitySettingsRevision).isEqualTo(12L)
        assertThat(current.prediction30mMmol).isEqualTo(5.3)
        assertThat(current.prediction30mAgeMinutes).isEqualTo(1.0)
    }

    @Test
    fun reportRejectsAcceptedMarkerWhenForecastHorizonsAreMixedOrMissing() = runBlocking {
        val now = 1_785_556_800_000L
        val forecastTs = now - 60_000L
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot("mixed-cycle", 15L, forecastTs))
        val mixedForecasts = listOf(
                forecast(forecastTs + 5L * 60_000L, 5, 5.1),
                forecast(forecastTs + 30L * 60_000L, 30, 5.3),
                forecast(forecastTs + 60L * 60_000L + 1L, 60, 5.6)
            )
        db.forecastDao().insertAll(mixedForecasts)
        db.telemetryDao().upsertAll(
            acceptedTuple(now - 30_000L, "mixed-cycle", 15L, forecastTs, mixedForecasts)
        )

        val current = reportCurrent(now, revision = 15L)

        assertThat(current.sensitivityCycleId).isNull()
        assertThat(current.sensitivitySettingsRevision).isNull()
        assertThat(current.selectedIsfMmolPerUnit).isNull()
        assertThat(current.selectedCrGramsPerUnit).isNull()
        assertThat(current.prediction30mMmol).isNull()
    }

    @Test
    fun reportRejectsAcceptedTupleAfterSameTimestampForecastValuesAreOverwritten() = runBlocking {
        val now = 1_785_556_800_000L
        val forecastTs = now - 60_000L
        val accepted = forecastSet(forecastTs, base = 5.0)
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot("accepted-cycle", 16L, forecastTs))
        db.forecastDao().insertAll(accepted)
        db.telemetryDao().upsertAll(
            acceptedTuple(now - 30_000L, "accepted-cycle", 16L, forecastTs, accepted)
        )
        accepted.forEach { row ->
            db.forecastDao().deleteByTimestampAndHorizon(row.timestamp, row.horizonMinutes)
        }
        db.forecastDao().insertAll(forecastSet(forecastTs, base = 8.0))

        val current = reportCurrent(now, revision = 16L)

        assertThat(current.sensitivityCycleId).isNull()
        assertThat(current.selectedIsfMmolPerUnit).isNull()
        assertThat(current.prediction30mMmol).isNull()
    }

    @Test
    fun reportRejectsChangedDecompositionWithOldAcceptedDigest() = runBlocking {
        val now = 1_785_556_800_000L
        val forecastTs = now - 60_000L
        val forecasts = forecastSet(forecastTs, base = 5.0)
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot("accepted-cycle", 17L, forecastTs))
        db.forecastDao().insertAll(forecasts)
        val changedMarkers = acceptedTuple(
            now - 30_000L,
            "accepted-cycle",
            17L,
            forecastTs,
            forecasts
        ).map { row ->
            if (row.key == SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY) {
                row.copy(
                    valueText = SensitivityAcceptedForecastDecompositionCodec.encode(
                        SensitivityAcceptedForecastDecomposition.unavailable().copy(therapy60Mmol = -0.9)
                    )
                )
            } else {
                row
            }
        }
        db.telemetryDao().upsertAll(changedMarkers)

        val current = reportCurrent(now, revision = 17L)

        assertThat(current.sensitivityCycleId).isNull()
        assertThat(current.selectedIsfMmolPerUnit).isNull()
        assertThat(current.prediction30mMmol).isNull()
    }

    @Test
    fun validAcceptedTupleHydratesRecreatedRepositoryAndWidgetWithoutStartupWrite() = runBlocking {
        val now = 1_785_556_800_000L
        val generationTs = now - 60_000L
        val forecasts = forecastSet(generationTs, base = 5.0)
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot("restart-cycle", 24L, generationTs))
        db.forecastDao().insertAll(forecasts)
        persistAcceptedTuple(
            acceptedTuple(now - 30_000L, "restart-cycle", 24L, generationTs, forecasts)
        )
        var candidateLoads = 0
        var persistenceWrites = 0
        val loader = AcceptedSensitivityTupleRoomLoader(db)
        val recreated = SensitivityRuntimeRepository(
            loadedCandidates = {
                candidateLoads += 1
                error("startup hydration must not load candidates")
            },
            persistence = SensitivityRuntimePersistence { persistenceWrites += 1 },
            settingsRevision = { 24L },
            acceptedSnapshotLoader = { revision, atTs ->
                loader.load(identity(revision), atTs)?.toRuntimePublication()
            },
            now = { now }
        )

        val hydrated = recreated.hydrateFromAcceptedTuple()
        val widget = CopilotGlucoseWidgetRepository(db) { identity(24L) }.loadSnapshot(now)

        assertThat(hydrated?.forecastCycleId).isEqualTo("restart-cycle")
        assertThat(recreated.current.value?.forecastCycleId).isEqualTo("restart-cycle")
        assertThat(widget.predicted30Mmol).isEqualTo(5.3)
        assertThat(candidateLoads).isEqualTo(0)
        assertThat(persistenceWrites).isEqualTo(0)
    }

    @Test
    fun startupLoaderRejectsPreFeatureMarkerlessTupleWithoutModelOrAuthorityToken() = runBlocking {
        val now = 1_785_556_800_000L
        val generationTs = now - 60_000L
        val forecasts = forecastSet(generationTs, base = 5.0)
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot("legacy-markerless", 94L, generationTs))
        db.forecastDao().insertAll(forecasts)
        db.telemetryDao().upsertAcceptedSensitivityTuple(
            acceptedTuple(now - 30_000L, "legacy-markerless", 94L, generationTs, forecasts)
                .filter { it.source == SENSITIVITY_ACCEPTED_SOURCE }
        )

        val loaded = AcceptedSensitivityTupleRoomLoader(db).load(identity(94L), now)

        assertThat(loaded).isNull()
    }

    @Test
    fun missingOrStaleAcceptedTupleLeavesRestartUnavailableWithoutOrphanSnapshot() = runBlocking {
        val now = 1_785_556_800_000L
        val loader = AcceptedSensitivityTupleRoomLoader(db)
        var candidateLoads = 0
        var persistenceWrites = 0
        val recreated = SensitivityRuntimeRepository(
            loadedCandidates = {
                candidateLoads += 1
                error("startup hydration must not load candidates")
            },
            persistence = SensitivityRuntimePersistence { persistenceWrites += 1 },
            settingsRevision = { 25L },
            acceptedSnapshotLoader = { revision, atTs ->
                loader.load(identity(revision), atTs)?.toRuntimePublication()
            },
            now = { now }
        )

        assertThat(recreated.hydrateFromAcceptedTuple()).isNull()
        assertThat(recreated.current.value).isNull()
        assertThat(db.sensitivityRuntimeSnapshotDao().latest()).isNull()
        assertThat(candidateLoads).isEqualTo(0)
        assertThat(persistenceWrites).isEqualTo(0)

        val staleGeneration = now - 15L * 60_000L - 1L
        val staleForecasts = forecastSet(staleGeneration, base = 6.0)
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot("stale-restart", 25L, staleGeneration))
        db.forecastDao().insertAll(staleForecasts)
        persistAcceptedTuple(
            acceptedTuple(now - 1L, "stale-restart", 25L, staleGeneration, staleForecasts)
        )

        assertThat(recreated.hydrateFromAcceptedTuple()).isNull()
        assertThat(recreated.current.value).isNull()
    }

    @Test
    fun badDigestAndRevisionMismatchCannotHydrateRecreatedRepository() = runBlocking {
        val now = 1_785_556_800_000L
        val generationTs = now - 60_000L
        val forecasts = forecastSet(generationTs, base = 6.0)
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot("restart-invalid", 26L, generationTs))
        db.forecastDao().insertAll(forecasts)
        val markerTs = now - 30_000L
        val accepted = acceptedTuple(markerTs, "restart-invalid", 26L, generationTs, forecasts)
        persistAcceptedTuple(
            accepted.map { row ->
                if (row.key == SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY) {
                    row.copy(valueText = "0".repeat(64))
                } else {
                    row
                }
            }
        )
        val loader = AcceptedSensitivityTupleRoomLoader(db)

        val badDigestRestart = sensitivityRepositoryForHydration(loader, revision = 26L, now = now)
        assertThat(badDigestRestart.hydrateFromAcceptedTuple()).isNull()
        assertThat(badDigestRestart.current.value).isNull()

        persistAcceptedTuple(accepted)
        val wrongRevisionRestart = sensitivityRepositoryForHydration(loader, revision = 27L, now = now)
        assertThat(wrongRevisionRestart.hydrateFromAcceptedTuple()).isNull()
        assertThat(wrongRevisionRestart.current.value).isNull()
    }

    @Test
    fun currentClinicalSnapshotUsesSharedFifteenMinuteInclusiveFreshness() = runBlocking {
        val now = 1_785_556_800_000L
        val generationTs = now - 15L * 60_000L
        val forecasts = forecastSet(generationTs, base = 5.0)
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot("boundary-cycle", 28L, generationTs))
        db.forecastDao().insertAll(forecasts)
        persistAcceptedTuple(
            acceptedTuple(now - 1L, "boundary-cycle", 28L, generationTs, forecasts)
        )

        assertThat(reportCurrent(now, revision = 28L).sensitivityCycleId).isEqualTo("boundary-cycle")
        assertThat(reportCurrent(now + 1L, revision = 28L).sensitivityCycleId).isNull()
    }

    @Test
    fun acceptedTuplePersistenceRejectsIncompleteMarkerSetWithoutPartialRows() = runBlocking {
        val markerTs = 1_785_556_700_000L
        val complete = acceptedTuple(
            markerTs = markerTs,
            cycleId = "cycle-complete",
            revision = 4L,
            forecastTs = markerTs - 60_000L
        ).filter { it.source == SENSITIVITY_ACCEPTED_SOURCE }

        val error = runCatching {
            db.telemetryDao().upsertAcceptedSensitivityTuple(complete.dropLast(1))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            db.telemetryDao().atTimestampBySourceAndKeys(
                SENSITIVITY_ACCEPTED_SOURCE,
                markerTs,
                complete.map { it.key }
            )
        ).isEmpty()
    }

    @Test
    fun acceptedTuplePersistenceReplacesPriorMarkerSetAtSameTimestamp() = runBlocking {
        val markerTs = 1_785_556_700_000L
        val first = acceptedTuple(
            markerTs = markerTs,
            cycleId = "cycle-first",
            revision = 4L,
            forecastTs = markerTs - 60_000L
        ).filter { it.source == SENSITIVITY_ACCEPTED_SOURCE }
        val replacement = acceptedTuple(
            markerTs = markerTs,
            cycleId = "cycle-replacement",
            revision = 5L,
            forecastTs = markerTs - 30_000L
        ).filter { it.source == SENSITIVITY_ACCEPTED_SOURCE }

        db.telemetryDao().upsertAcceptedSensitivityTuple(first)
        db.telemetryDao().upsertAcceptedSensitivityTuple(replacement)

        val persisted = db.telemetryDao().atTimestampBySourceAndKeys(
            SENSITIVITY_ACCEPTED_SOURCE,
            markerTs,
            replacement.map { it.key }
        )
        assertThat(persisted).hasSize(6)
        assertThat(
            persisted.single { it.key == SENSITIVITY_ACCEPTED_CYCLE_ID_KEY }.valueText
        ).isEqualTo("cycle-replacement")
    }

    @Test
    fun persistedSnapshotDecoderRejectsEveryCorruptFieldClass() {
        val valid = snapshot("valid-cycle", 40L, 1_785_556_700_000L)
        val corrupt = listOf(
            valid.copy(cycleId = ""),
            valid.copy(cycleId = "\uD800"),
            valid.copy(settingsRevision = -1L),
            valid.copy(generatedAt = 0L),
            valid.copy(isfRequestedSource = "UNKNOWN"),
            valid.copy(isfResolvedSource = "UNKNOWN"),
            valid.copy(crRequestedSource = "UNKNOWN"),
            valid.copy(crResolvedSource = "UNKNOWN"),
            valid.copy(isfRawAaps = Double.NaN),
            valid.copy(isfRawEvidence = Double.POSITIVE_INFINITY),
            valid.copy(isfRawCopilot = 0.79),
            valid.copy(isfBlended = 18.01),
            valid.copy(isfEffective = -1.0),
            valid.copy(isfConfidence = 1.01),
            valid.copy(crRawAaps = Double.NEGATIVE_INFINITY),
            valid.copy(crRawEvidence = 1.99),
            valid.copy(crRawCopilot = 60.01),
            valid.copy(crBlended = 0.0),
            valid.copy(crEffective = Double.NaN),
            valid.copy(crConfidence = -0.01)
        )

        corrupt.forEach { entity ->
            assertThat(entity.toSensitivityRuntimeSnapshotOrNull()).isNull()
        }
        assertThat(valid.toSensitivityRuntimeSnapshotOrNull()).isNotNull()
    }

    @Test
    fun everyResolverDecisionPathRoundTripsThroughPersistedSnapshotValidation() {
        val ts = 1_785_556_700_000L
        val scenarios = listOf(
            resolvedSnapshot(
                cycleId = "aaps-direct",
                ts = ts,
                isfPreference = SensitivitySourcePreference.AAPS,
                isfAaps = runtimeCandidate(3.2, ts),
                isfEvidence = runtimeCandidate(6.0, ts),
                isfCopilot = runtimeCandidate(4.0, ts)
            ),
            resolvedSnapshot(
                cycleId = "aaps-evidence-fallback",
                ts = ts,
                isfPreference = SensitivitySourcePreference.AAPS,
                isfAaps = runtimeCandidate(3.2, ts - 3_600_001L),
                isfEvidence = runtimeCandidate(6.0, ts, confidence = 0.5),
                isfCopilot = runtimeCandidate(4.0, ts)
            ),
            resolvedSnapshot(
                cycleId = "aaps-copilot-fallback",
                ts = ts,
                isfPreference = SensitivitySourcePreference.AAPS,
                isfAaps = runtimeCandidate(0.5, ts),
                isfEvidence = runtimeCandidate(null, ts, unavailableReason = "missing"),
                isfCopilot = runtimeCandidate(4.0, ts)
            ),
            resolvedSnapshot(
                cycleId = "evidence-blend",
                ts = ts,
                isfPreference = SensitivitySourcePreference.EVIDENCE,
                isfEvidence = runtimeCandidate(6.0, ts, confidence = 0.25),
                isfCopilot = runtimeCandidate(4.0, ts)
            ),
            resolvedSnapshot(
                cycleId = "evidence-copilot-fallback",
                ts = ts,
                isfPreference = SensitivitySourcePreference.EVIDENCE,
                isfEvidence = runtimeCandidate(6.0, ts, qualityPassed = false),
                isfCopilot = runtimeCandidate(4.0, ts)
            ),
            resolvedSnapshot(
                cycleId = "evidence-confidence-fallback",
                ts = ts,
                isfPreference = SensitivitySourcePreference.EVIDENCE,
                isfEvidence = runtimeCandidate(6.0, ts, confidence = 0.0),
                isfCopilot = runtimeCandidate(4.0, ts)
            ),
            resolvedSnapshot(
                cycleId = "copilot-direct",
                ts = ts,
                isfPreference = SensitivitySourcePreference.COPILOT,
                isfCopilot = runtimeCandidate(4.0, ts)
            ),
            resolvedSnapshot(
                cycleId = "copilot-diagnostic-floor",
                ts = ts,
                isfPreference = SensitivitySourcePreference.COPILOT,
                isfCopilot = runtimeCandidate(0.5, ts)
            )
        )

        scenarios.forEach { expected ->
            assertThat(expected.toEntity().toSensitivityRuntimeSnapshotOrNull()).isEqualTo(expected)
        }
    }

    @Test
    fun corruptResolvedEffectiveBlendAndFallbackRelationshipsAreRejected() {
        val ts = 1_785_556_700_000L
        val copilot = resolvedSnapshot(
            cycleId = "copilot-valid",
            ts = ts,
            isfPreference = SensitivitySourcePreference.COPILOT,
            isfCopilot = runtimeCandidate(4.0, ts)
        ).toEntity()
        val aaps = resolvedSnapshot(
            cycleId = "aaps-valid",
            ts = ts,
            isfPreference = SensitivitySourcePreference.AAPS,
            isfAaps = runtimeCandidate(3.0, ts),
            isfCopilot = runtimeCandidate(4.0, ts)
        ).toEntity()
        val evidence = resolvedSnapshot(
            cycleId = "evidence-valid",
            ts = ts,
            isfPreference = SensitivitySourcePreference.EVIDENCE,
            isfEvidence = runtimeCandidate(6.0, ts, confidence = 0.5),
            isfCopilot = runtimeCandidate(4.0, ts)
        ).toEntity()
        val corrupt = listOf(
            copilot.copy(isfResolvedSource = SensitivityResolvedSource.AAPS.name, isfRawAaps = 3.0),
            copilot.copy(isfFallbackReason = "evidence_missing"),
            copilot.copy(
                isfRequestedSource = SensitivitySourcePreference.EVIDENCE.name,
                isfFallbackReason = "evidence_missing;unexpected"
            ),
            copilot.copy(
                isfRequestedSource = SensitivitySourcePreference.AAPS.name,
                isfFallbackReason = "aaps_missing;evidence_missing;unexpected"
            ),
            copilot.copy(isfBlended = 4.0),
            aaps.copy(isfEffective = 3.1),
            aaps.copy(isfFallbackReason = "unexpected"),
            evidence.copy(isfRawEvidence = null),
            evidence.copy(isfBlended = null),
            evidence.copy(isfBlended = 5.1, isfEffective = 5.1),
            evidence.copy(isfEffective = 5.1),
            evidence.copy(isfConfidence = 0.0, isfBlended = 4.0, isfEffective = 4.0),
            evidence.copy(isfFallbackReason = "evidence_missing")
        )

        corrupt.forEach { entity ->
            assertThat(entity.toSensitivityRuntimeSnapshotOrNull()).isNull()
        }
    }

    @Test
    fun roomLoaderRejectsZeroConfidenceEvidenceBlend() = runBlocking {
        val now = 1_785_556_800_000L
        val generationTs = now - 60_000L
        val runtime = resolvedSnapshot(
            cycleId = "invalid-zero-confidence-blend",
            ts = generationTs,
            isfPreference = SensitivitySourcePreference.EVIDENCE,
            isfEvidence = runtimeCandidate(6.0, generationTs, confidence = 0.5),
            isfCopilot = runtimeCandidate(4.0, generationTs)
        )
        val corrupt = runtime.toEntity().copy(
            isfConfidence = 0.0,
            isfBlended = 4.0,
            isfEffective = 4.0
        )
        val forecasts = forecastSet(generationTs, base = 5.0)
        db.sensitivityRuntimeSnapshotDao().upsert(corrupt)
        db.forecastDao().insertAll(forecasts)
        persistAcceptedTuple(
            acceptedTuple(now - 30_000L, runtime.forecastCycleId, 43L, generationTs, forecasts)
        )

        assertThat(AcceptedSensitivityTupleRoomLoader(db).load(identity(43L), now)).isNull()
    }

    @Test
    fun roomLoaderRoundTripsZeroConfidenceEvidenceFallbackToCopilot() = runBlocking {
        val now = 1_785_556_800_000L
        val generationTs = now - 60_000L
        val runtime = resolvedSnapshot(
            cycleId = "zero-confidence-fallback",
            ts = generationTs,
            isfPreference = SensitivitySourcePreference.EVIDENCE,
            isfEvidence = runtimeCandidate(6.0, generationTs, confidence = 0.0),
            isfCopilot = runtimeCandidate(4.0, generationTs)
        )
        val forecasts = forecastSet(generationTs, base = 5.0)
        db.sensitivityRuntimeSnapshotDao().upsert(runtime.toEntity())
        db.forecastDao().insertAll(forecasts)
        persistAcceptedTuple(
            acceptedTuple(now - 30_000L, runtime.forecastCycleId, 43L, generationTs, forecasts)
        )

        val loaded = AcceptedSensitivityTupleRoomLoader(db).load(
            identity(43L, isfSource = SensitivitySourcePreference.EVIDENCE),
            now
        )?.snapshot

        assertThat(loaded).isEqualTo(runtime)
        assertThat(loaded?.isf?.resolved).isEqualTo(SensitivityResolvedSource.COPILOT_NATIVE)
        assertThat(loaded?.isf?.fallbackReason).isEqualTo("evidence_confidence_gate_failed")
    }

    @Test
    fun crashAfterForwardSettingsPersistRejectsOldTupleUntilNewRevisionIsAccepted() = runBlocking {
        val now = 1_785_556_800_000L
        val generationTs = now - 60_000L
        val runtime = resolvedSnapshot(
            cycleId = "aborted-publication-cycle",
            ts = generationTs,
            isfPreference = SensitivitySourcePreference.AAPS,
            isfAaps = runtimeCandidate(4.0, generationTs),
            isfCopilot = runtimeCandidate(5.0, generationTs),
            revision = 71L
        )
        val forecasts = forecastSet(generationTs, base = 5.0)
        db.sensitivityRuntimeSnapshotDao().upsert(runtime.toEntity())
        db.forecastDao().insertAll(forecasts)
        persistAcceptedTuple(
            acceptedTuple(now - 30_000L, runtime.forecastCycleId, 71L, generationTs, forecasts)
        )

        val authoritativeAfterSettingsWrite = SensitivityRuntimeSettingsIdentity(
            revision = 72L,
            isfSource = SensitivitySourcePreference.EVIDENCE,
            crSource = SensitivitySourcePreference.COPILOT
        )

        val restarted = SensitivityRuntimeRepository(
            loadedCandidates = { error("not used") },
            persistence = SensitivityRuntimePersistence { error("not used") },
            settingsRevision = { authoritativeAfterSettingsWrite.revision },
            acceptedSnapshotLoader = { _, atTs ->
                AcceptedSensitivityTupleRoomLoader(db)
                    .load(authoritativeAfterSettingsWrite, atTs)
                    ?.toRuntimePublication()
            },
            now = { now }
        )
        assertThat(
            AcceptedSensitivityTupleRoomLoader(db).load(authoritativeAfterSettingsWrite, now)
        ).isNull()
        assertThat(restarted.hydrateFromAcceptedTuple()).isNull()

        val newRuntime = resolvedSnapshot(
            cycleId = "forward-recovery-cycle",
            ts = generationTs,
            isfPreference = SensitivitySourcePreference.EVIDENCE,
            isfEvidence = runtimeCandidate(4.5, generationTs),
            isfCopilot = runtimeCandidate(5.0, generationTs),
            revision = 72L
        )
        db.sensitivityRuntimeSnapshotDao().upsert(newRuntime.toEntity())
        persistAcceptedTuple(
            acceptedTuple(now - 1L, newRuntime.forecastCycleId, 72L, generationTs, forecasts)
        )

        val recoveredMarkers = db.telemetryDao().atTimestampBySourceAndKeys(
            SENSITIVITY_ACCEPTED_SOURCE,
            now - 1L,
            ACCEPTED_SENSITIVITY_MARKER_KEYS
        )
        val recoveredSnapshot = db.sensitivityRuntimeSnapshotDao()
            .byCycleId(newRuntime.forecastCycleId)
            ?.toSensitivityRuntimeSnapshotOrNull()
        val recoveredForecasts = db.forecastDao().atGenerationTimestamp(generationTs)
        assertThat(recoveredMarkers).hasSize(6)
        assertThat(recoveredSnapshot).isEqualTo(newRuntime)
        assertThat(
            ForecastSnapshotResolver.resolveAcceptedTuple(
                forecasts = recoveredForecasts,
                telemetry = recoveredMarkers,
                snapshot = recoveredSnapshot,
                currentSettings = authoritativeAfterSettingsWrite,
                authoritativeNowTs = now
            ).error
        ).isNull()
        assertThat(
            AcceptedSensitivityTupleRoomLoader(db)
                .load(authoritativeAfterSettingsWrite, now)
                ?.snapshot
                ?.forecastCycleId
        ).isEqualTo("forward-recovery-cycle")
        assertThat(restarted.hydrateFromAcceptedTuple()?.forecastCycleId)
            .isEqualTo("forward-recovery-cycle")
    }

    @Test
    fun pendingPublicationMarkerIsNeverAuthoritativeAfterRestart() = runBlocking {
        val now = 1_785_556_800_000L
        val generationTs = now - 60_000L
        val runtime = resolvedSnapshot(
            cycleId = "pending-publication-cycle",
            ts = generationTs,
            isfPreference = SensitivitySourcePreference.AAPS,
            isfAaps = runtimeCandidate(4.0, generationTs),
            isfCopilot = runtimeCandidate(5.0, generationTs),
            revision = 81L
        )
        val forecasts = forecastSet(generationTs, base = 5.0)
        db.sensitivityRuntimeSnapshotDao().upsert(runtime.toEntity())
        db.forecastDao().insertAll(forecasts)
        persistAcceptedTuple(
            acceptedTuple(
                now - 30_000L,
                runtime.forecastCycleId,
                81L,
                generationTs,
                forecasts,
                publicationState = SENSITIVITY_ACCEPTED_PUBLICATION_PENDING
            )
        )

        val identity = SensitivityRuntimeSettingsIdentity(
            revision = 81L,
            isfSource = SensitivitySourcePreference.AAPS,
            crSource = SensitivitySourcePreference.COPILOT
        )
        assertThat(AcceptedSensitivityTupleRoomLoader(db).load(identity, now)).isNull()
    }

    @Test
    fun modelFreeRestartRequiresMatchingTrustworthySensorSession() = runBlocking {
        val now = 1_785_556_800_000L
        val generationTs = now - 60_000L
        val cycleId = "model-free-session-cycle"
        val forecasts = forecastSet(generationTs, base = 5.0)
        val currentSession = requireNotNull(calibrationSensorSessionKeyFromAgeSample(now, 1.0))
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot(cycleId, 85L, generationTs))
        db.forecastDao().insertAll(forecasts)
        persistAcceptedTuple(
            acceptedTuple(now - 30_000L, cycleId, 85L, generationTs, forecasts)
                .map { row ->
                    if (row.key == ACCEPTED_CALIBRATION_SESSION_KEY) {
                        row.copy(valueText = currentSession)
                    } else {
                        row
                    }
                }
        )
        db.telemetryDao().deleteBySourceAndTimestamp("test_sensor", now - 30_000L)
        db.telemetryDao().upsertAll(
            listOf(sensorAgeTelemetry("same-session", "aaps", now, 1.0))
        )

        val matching = AcceptedSensitivityTupleRoomLoader(db).load(identity(85L), now)
        db.telemetryDao().deleteBySourceAndTimestamp("aaps", now)
        val missing = AcceptedSensitivityTupleRoomLoader(db).load(identity(85L), now)
        db.telemetryDao().upsertAll(
            listOf(sensorAgeTelemetry("rollover", "aaps", now, 0.1))
        )
        val rollover = AcceptedSensitivityTupleRoomLoader(db).load(identity(85L), now)
        db.telemetryDao().upsertAll(
            listOf(sensorAgeTelemetry("conflict", "xdrip", now, 8.0))
        )
        val conflicting = AcceptedSensitivityTupleRoomLoader(db).load(identity(85L), now)

        assertThat(matching?.accepted?.calibrationModel).isNull()
        assertThat(matching?.accepted?.forecastsByHorizon?.keys).containsExactly(5, 30, 60)
        assertThat(missing).isNull()
        assertThat(rollover).isNull()
        assertThat(conflicting).isNull()
    }

    @Test
    fun newerPendingMarkerDoesNotHidePreviousMatchingCommittedTuple() = runBlocking {
        val now = 1_785_556_800_000L
        val oldGeneration = now - 120_000L
        val oldForecasts = forecastSet(oldGeneration, base = 5.0)
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot("old-committed", 83L, oldGeneration))
        db.forecastDao().insertAll(oldForecasts)
        persistAcceptedTuple(
            acceptedTuple(now - 90_000L, "old-committed", 83L, oldGeneration, oldForecasts)
        )
        val pendingGeneration = now - 30_000L
        val pendingForecasts = forecastSet(pendingGeneration, base = 8.0)
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot("new-pending", 84L, pendingGeneration))
        db.forecastDao().insertAll(pendingForecasts)
        persistAcceptedTuple(
            acceptedTuple(
                now - 1L,
                "new-pending",
                84L,
                pendingGeneration,
                pendingForecasts,
                publicationState = SENSITIVITY_ACCEPTED_PUBLICATION_PENDING
            )
        )

        val loaded = AcceptedSensitivityTupleRoomLoader(db).load(identity(83L), now)

        assertThat(loaded?.snapshot?.forecastCycleId).isEqualTo("old-committed")
        assertThat(CopilotGlucoseWidgetRepository(db) { identity(83L) }.loadSnapshot(now).predicted30Mmol)
            .isEqualTo(oldForecasts.single { it.horizonMinutes == 30 }.valueMmol)
        assertThat(reportCurrent(now, 83L).sensitivityCycleId).isEqualTo("old-committed")
        val restarted = SensitivityRuntimeRepository(
            loadedCandidates = { error("not used") },
            persistence = SensitivityRuntimePersistence { error("not used") },
            settingsRevision = { 83L },
            acceptedSnapshotLoader = { _, atTs ->
                AcceptedSensitivityTupleRoomLoader(db).load(identity(83L), atTs)?.toRuntimePublication()
            },
            now = { now }
        )
        assertThat(restarted.hydrateFromAcceptedTuple()?.forecastCycleId).isEqualTo("old-committed")
    }

    @Test
    fun durableRoomCommitFollowedByCommitExceptionIsReconciledAndPublished() = runBlocking {
        val now = 1_785_556_800_000L
        val candidateGeneration = now - 30_000L
        val candidateForecasts = forecastSet(candidateGeneration, base = 8.0)
        val candidate = snapshot("reconciled-commit", 86L, candidateGeneration)
            .toSensitivityRuntimeSnapshotOrNull()!!
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { error("not used") },
            persistence = SensitivityRuntimePersistence { error("not used") },
            settingsRevision = { 86L },
            now = { now }
        )
        var effects = 0

        val result = AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
            persistPendingRoomTuple = {
                db.sensitivityRuntimeSnapshotDao().upsert(candidate.toEntity())
                db.forecastDao().insertAll(candidateForecasts)
                persistAcceptedTuple(
                    acceptedTuple(
                        now - 1L,
                        candidate.forecastCycleId,
                        86L,
                        candidateGeneration,
                        candidateForecasts,
                        publicationState = SENSITIVITY_ACCEPTED_PUBLICATION_PENDING
                    )
                )
            },
            reserveAccepted = {
                repository.reserveAccepted(candidate, now - 1L, candidate.forecastCycleId)
            },
            commitAcceptedRoomTuple = {
                check(db.telemetryDao().commitAcceptedSensitivityTuple(
                    now - 1L,
                    candidate.forecastCycleId,
                    86L
                ))
                error("callback failed after durable write")
            },
            reconcileAcceptedRoomTuple = {
                AcceptedSensitivityTupleRoomLoader(db).load(identity(86L), now)
                    ?.snapshot
                    ?.forecastCycleId == candidate.forecastCycleId
            },
            finalizeAccepted = repository::finalizeAccepted,
            abortReservation = repository::abortAcceptedReservation,
            clinicalSideEffects = { effects += 1; "accepted" }
        )

        assertThat(result).isEqualTo("accepted")
        assertThat(effects).isEqualTo(1)
        assertThat(repository.current.value).isEqualTo(candidate)
    }

    @Test
    fun roomCommitFailureAfterReservationKeepsCurrentAndAllReadSurfacesFailClosed() = runBlocking {
        val now = 1_785_556_800_000L
        val previousGeneration = now - 120_000L
        val candidateGeneration = now - 60_000L
        var currentRevision = 90L
        val previous = snapshot("previous-live-cycle", 90L, previousGeneration)
            .toSensitivityRuntimeSnapshotOrNull()!!
        val candidate = snapshot("candidate-live-cycle", 91L, candidateGeneration)
            .toSensitivityRuntimeSnapshotOrNull()!!
        val candidateForecasts = forecastSet(candidateGeneration, base = 7.0)
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { error("not used") },
            persistence = SensitivityRuntimePersistence { error("not used") },
            settingsRevision = { currentRevision },
            now = { now }
        )
        val previousReservation = repository.reserveAccepted(
            previous,
            now - 90_000L,
            previous.forecastCycleId
        )
        assertThat(previousReservation).isNotNull()
        repository.finalizeAccepted(checkNotNull(previousReservation))
        currentRevision = 91L

        val failure = runCatching {
            AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                persistPendingRoomTuple = {
                    db.sensitivityRuntimeSnapshotDao().upsert(candidate.toEntity())
                    db.forecastDao().insertAll(candidateForecasts)
                    persistAcceptedTuple(
                        acceptedTuple(
                            now - 30_000L,
                            candidate.forecastCycleId,
                            91L,
                            candidateGeneration,
                            candidateForecasts,
                            publicationState = SENSITIVITY_ACCEPTED_PUBLICATION_PENDING
                        )
                    )
                },
                reserveAccepted = {
                    repository.reserveAccepted(candidate, now - 30_000L, candidate.forecastCycleId)
                },
                commitAcceptedRoomTuple = { error("Room commit failed") },
                reconcileAcceptedRoomTuple = { false },
                finalizeAccepted = repository::finalizeAccepted,
                abortReservation = repository::abortAcceptedReservation,
                clinicalSideEffects = { error("clinical effects must not run") }
            )
        }.exceptionOrNull()

        val tentativeIdentity = identity(91L)
        val markerRows = db.telemetryDao().atTimestampBySourceAndKeys(
            SENSITIVITY_ACCEPTED_SOURCE,
            now - 30_000L,
            ACCEPTED_SENSITIVITY_MARKER_KEYS
        )
        val overview = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = candidateForecasts,
            telemetry = markerRows,
            snapshot = repository.current.value,
            currentSettings = tentativeIdentity,
            authoritativeNowTs = now
        )

        assertThat(failure).hasMessageThat().contains("Room commit failed")
        assertThat(repository.current.value).isSameInstanceAs(previous)
        assertThat(overview.sensitivity).isNull()
        assertThat(AcceptedSensitivityTupleRoomLoader(db).load(tentativeIdentity, now)).isNull()
        assertThat(CopilotGlucoseWidgetRepository(db) { tentativeIdentity }.loadSnapshot(now).predicted30Mmol)
            .isNull()
        assertThat(reportCurrent(now, 91L).selectedIsfMmolPerUnit).isNull()
    }

    @Test
    fun unavailableReadbackSuppressesLivePublicationButRestartHydratesDurableCommit() = runBlocking {
        val now = 1_785_556_800_000L
        val generationTs = now - 60_000L
        val runtime = resolvedSnapshot(
            cycleId = "hung-recovery-cycle",
            ts = generationTs,
            isfPreference = SensitivitySourcePreference.EVIDENCE,
            isfEvidence = runtimeCandidate(4.5, generationTs),
            isfCopilot = runtimeCandidate(5.0, generationTs),
            revision = 82L
        )
        val forecasts = forecastSet(generationTs, base = 5.0)

        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { error("not used") },
            persistence = SensitivityRuntimePersistence { error("not used") },
            settingsRevision = { 82L },
            now = { now }
        )
        var effects = 0
        val failure = runCatching {
            AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
                persistPendingRoomTuple = {
                    db.sensitivityRuntimeSnapshotDao().upsert(runtime.toEntity())
                    db.forecastDao().insertAll(forecasts)
                    persistAcceptedTuple(
                        acceptedTuple(
                            now - 30_000L,
                            runtime.forecastCycleId,
                            82L,
                            generationTs,
                            forecasts,
                            publicationState = SENSITIVITY_ACCEPTED_PUBLICATION_PENDING
                        )
                    )
                },
                reserveAccepted = {
                    repository.reserveAccepted(runtime, now - 30_000L, runtime.forecastCycleId)
                },
                commitAcceptedRoomTuple = {
                    check(db.telemetryDao().commitAcceptedSensitivityTuple(
                        now - 30_000L,
                        runtime.forecastCycleId,
                        82L
                    ))
                },
                reconcileAcceptedRoomTuple = { error("Room readback unavailable") },
                finalizeAccepted = repository::finalizeAccepted,
                abortReservation = repository::abortAcceptedReservation,
                clinicalSideEffects = { effects += 1 }
            )
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(failure!!.message).contains("ambiguous")
        assertThat(repository.current.value).isNull()
        assertThat(effects).isEqualTo(0)
        val authoritativeIdentity = SensitivityRuntimeSettingsIdentity(
            revision = 82L,
            isfSource = SensitivitySourcePreference.EVIDENCE,
            crSource = SensitivitySourcePreference.COPILOT
        )
        val restarted = SensitivityRuntimeRepository(
            loadedCandidates = { error("not used") },
            persistence = SensitivityRuntimePersistence { error("not used") },
            settingsRevision = { 82L },
            acceptedSnapshotLoader = { _, atTs ->
                AcceptedSensitivityTupleRoomLoader(db)
                    .load(authoritativeIdentity, atTs)
                    ?.toRuntimePublication()
            },
            now = { now }
        )
        assertThat(restarted.hydrateFromAcceptedTuple()).isEqualTo(runtime)
    }

    @Test
    fun roomLoaderRejectsEitherRequestedSourceMismatchAtTheSameRevision() = runBlocking {
        val now = 1_785_556_800_000L
        val generationTs = now - 60_000L
        val runtime = resolvedSnapshot(
            cycleId = "source-identity-cycle",
            ts = generationTs,
            isfPreference = SensitivitySourcePreference.AAPS,
            isfAaps = runtimeCandidate(4.0, generationTs),
            isfCopilot = runtimeCandidate(5.0, generationTs),
            revision = 73L
        )
        val forecasts = forecastSet(generationTs, base = 5.0)
        db.sensitivityRuntimeSnapshotDao().upsert(runtime.toEntity())
        db.forecastDao().insertAll(forecasts)
        persistAcceptedTuple(
            acceptedTuple(now - 30_000L, runtime.forecastCycleId, 73L, generationTs, forecasts)
        )

        val loader = AcceptedSensitivityTupleRoomLoader(db)
        assertThat(
            loader.load(
                SensitivityRuntimeSettingsIdentity(
                    revision = 73L,
                    isfSource = SensitivitySourcePreference.EVIDENCE,
                    crSource = SensitivitySourcePreference.COPILOT
                ),
                now
            )
        ).isNull()
        assertThat(
            loader.load(
                SensitivityRuntimeSettingsIdentity(
                    revision = 73L,
                    isfSource = SensitivitySourcePreference.AAPS,
                    crSource = SensitivitySourcePreference.EVIDENCE
                ),
                now
            )
        ).isNull()
    }

    @Test
    fun diagnosticAapsIsfBelowEffectiveBoundSurvivesAcceptedRestartFallback() = runBlocking {
        val now = 1_785_556_800_000L
        val generationTs = now - 60_000L
        val runtime = resolvedSnapshot(
            cycleId = "raw-aaps-fallback",
            ts = generationTs,
            isfPreference = SensitivitySourcePreference.AAPS,
            isfAaps = runtimeCandidate(0.5, generationTs),
            isfEvidence = runtimeCandidate(null, generationTs, unavailableReason = "missing"),
            isfCopilot = runtimeCandidate(3.0, generationTs)
        )
        assertThat(runtime.isf.resolved).isEqualTo(SensitivityResolvedSource.COPILOT_NATIVE)
        assertThat(runtime.isf.rawAaps).isEqualTo(0.5)
        assertThat(runtime.isf.effective).isEqualTo(3.0)
        val forecasts = forecastSet(generationTs, base = 5.0)
        db.sensitivityRuntimeSnapshotDao().upsert(runtime.toEntity())
        db.forecastDao().insertAll(forecasts)
        persistAcceptedTuple(
            acceptedTuple(now - 30_000L, runtime.forecastCycleId, 43L, generationTs, forecasts)
        )

        val restarted = AcceptedSensitivityTupleRoomLoader(db).load(
            identity(43L, isfSource = SensitivitySourcePreference.AAPS),
            now
        )?.snapshot

        assertThat(restarted?.isf?.rawAaps).isEqualTo(0.5)
        assertThat(restarted?.isf?.effective).isEqualTo(3.0)
        assertThat(restarted?.isf?.resolved).isEqualTo(SensitivityResolvedSource.COPILOT_NATIVE)
    }

    @Test
    fun evidenceCrAapsFallbackSurvivesPersistedRoundTrip() {
        val ts = 1_785_556_700_000L
        val runtime = SensitivityRuntimeResolver.resolve(
            settingsRevision = 144L,
            forecastCycleId = "evidence-cr-aaps-roundtrip",
            timestamp = ts,
            isfPreference = SensitivitySourcePreference.COPILOT,
            crPreference = SensitivitySourcePreference.EVIDENCE,
            isfCandidates = SensitivityCandidates(
                runtimeCandidate(null, ts, unavailableReason = "missing"),
                runtimeCandidate(null, ts, unavailableReason = "missing"),
                runtimeCandidate(3.0, ts)
            ),
            crCandidates = SensitivityCandidates(
                runtimeCandidate(10.0, ts),
                runtimeCandidate(8.59339, ts, confidence = 0.435, qualityPassed = false),
                runtimeCandidate(22.5416, ts, confidence = 0.435)
            ),
            freshnessMs = 3_600_000L
        )

        val restored = runtime.toEntity().toSensitivityRuntimeSnapshotOrNull()

        assertThat(restored).isEqualTo(runtime)
        assertThat(restored?.cr?.resolved).isEqualTo(SensitivityResolvedSource.AAPS)
        assertThat(restored?.cr?.effective).isEqualTo(10.0)
        assertThat(restored?.cr?.fallbackReason)
            .isEqualTo("evidence_quality_gate_failed;aaps_fallback_selected")
    }

    @Test
    fun persistedValidatorRejectsForgedEvidenceToAapsFallback() {
        val ts = 1_785_556_700_000L
        val runtime = SensitivityRuntimeResolver.resolve(
            settingsRevision = 145L,
            forecastCycleId = "evidence-cr-aaps-forgery",
            timestamp = ts,
            isfPreference = SensitivitySourcePreference.COPILOT,
            crPreference = SensitivitySourcePreference.EVIDENCE,
            isfCandidates = SensitivityCandidates(
                runtimeCandidate(null, ts, unavailableReason = "missing"),
                runtimeCandidate(null, ts, unavailableReason = "missing"),
                runtimeCandidate(3.0, ts)
            ),
            crCandidates = SensitivityCandidates(
                runtimeCandidate(10.0, ts),
                runtimeCandidate(8.59339, ts, confidence = 0.435, qualityPassed = false),
                runtimeCandidate(22.5416, ts, confidence = 0.435)
            ),
            freshnessMs = 3_600_000L
        )
        val persisted = runtime.toEntity()

        assertThat(persisted.copy(crConfidence = 0.0).toSensitivityRuntimeSnapshotOrNull()).isNull()
        assertThat(
            persisted.copy(
                crFallbackReason = "evidence_quality_gate_failed",
                crResolvedSource = SensitivityResolvedSource.AAPS.name
            ).toSensitivityRuntimeSnapshotOrNull()
        ).isNull()
        assertThat(
            persisted.copy(
                crFallbackReason = "evidence_quality_gate_failed;aaps_fallback_selected",
                crEffective = 22.5416
            ).toSensitivityRuntimeSnapshotOrNull()
        ).isNull()
    }

    @Test
    fun persistedValidatorKeepsLegacyEvidenceToNativeFallbackCompatible() {
        val persisted = snapshot(
            cycleId = "legacy-evidence-cr-native",
            revision = 146L,
            generatedAt = 1_785_556_700_000L
        ).copy(
            crRequestedSource = SensitivitySourcePreference.EVIDENCE.name,
            crResolvedSource = SensitivityResolvedSource.COPILOT_NATIVE.name,
            crRawAaps = 10.0,
            crRawEvidence = 8.59339,
            crRawCopilot = 22.5416,
            crEffective = 22.5416,
            crConfidence = 0.435,
            crFallbackReason = "evidence_quality_gate_failed"
        )

        val restored = persisted.toSensitivityRuntimeSnapshotOrNull()

        assertThat(restored?.cr?.requested).isEqualTo(SensitivitySourcePreference.EVIDENCE)
        assertThat(restored?.cr?.resolved).isEqualTo(SensitivityResolvedSource.COPILOT_NATIVE)
        assertThat(restored?.cr?.effective).isEqualTo(22.5416)
        assertThat(restored?.cr?.fallbackReason).isEqualTo("evidence_quality_gate_failed")
    }

    @Test
    fun rawDiagnosticsOutsideIngressBoundsAreRejected() {
        val valid = snapshot("raw-bounds", 44L, 1_785_556_700_000L)

        assertThat(valid.copy(isfRawAaps = 0.19).toSensitivityRuntimeSnapshotOrNull()).isNull()
        assertThat(valid.copy(isfRawEvidence = 0.0).toSensitivityRuntimeSnapshotOrNull()).isNull()
        assertThat(valid.copy(isfRawCopilot = 0.19).toSensitivityRuntimeSnapshotOrNull()).isNull()
        assertThat(valid.copy(crRawAaps = 1.99).toSensitivityRuntimeSnapshotOrNull()).isNull()
    }

    @Test
    fun roomLoaderRejectsCorruptRowsAndImpossibleSnapshotMarkerTimeRelationships() = runBlocking {
        val now = 1_785_556_800_000L
        val generationTs = now - 60_000L
        val forecasts = forecastSet(generationTs, base = 5.0)
        val markerTs = now - 30_000L
        val corruptions = listOf<(SensitivityRuntimeSnapshotEntity) -> SensitivityRuntimeSnapshotEntity>(
            { it.copy(isfRequestedSource = "UNKNOWN") },
            { it.copy(crResolvedSource = "UNKNOWN") },
            { it.copy(isfEffective = 18.01) },
            { it.copy(crConfidence = 1.01) },
            { it.copy(generatedAt = markerTs + 1L) },
            { it.copy(generatedAt = markerTs - 15L * 60_000L - 1L) }
        )

        corruptions.forEachIndexed { index, corrupt ->
            db.clearAllTables()
            val cycleId = "corrupt-cycle-$index"
            db.sensitivityRuntimeSnapshotDao().upsert(
                corrupt(snapshot(cycleId, 41L, generationTs))
            )
            db.forecastDao().insertAll(forecasts)
            persistAcceptedTuple(
                acceptedTuple(markerTs, cycleId, 41L, generationTs, forecasts)
            )

            assertThat(AcceptedSensitivityTupleRoomLoader(db).load(identity(41L), now)).isNull()
        }
    }

    @Test
    fun atomicLoaderCannotObserveMarkerSnapshotAndForecastsFromDifferentTransactions() = runBlocking {
        val now = 1_785_556_800_000L
        val oldGeneration = now - 120_000L
        val oldForecasts = forecastSet(oldGeneration, base = 5.0)
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot("old-cycle", 42L, oldGeneration))
        db.forecastDao().insertAll(oldForecasts)
        persistAcceptedTuple(
            acceptedTuple(now - 90_000L, "old-cycle", 42L, oldGeneration, oldForecasts)
        )
        val markerRead = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val loader = AcceptedSensitivityTupleRoomLoader(
            db = db,
            afterMarkerLookup = {
                markerRead.complete(Unit)
                releaseRead.await()
            }
        )

        val loaded = async(Dispatchers.IO) { loader.load(identity(42L), now) }
        markerRead.await()
        val replacement = async(Dispatchers.IO) {
            db.withTransaction {
                oldForecasts.forEach { row ->
                    db.forecastDao().deleteByTimestampAndHorizon(row.timestamp, row.horizonMinutes)
                }
                val newGeneration = now - 30_000L
                val newForecasts = forecastSet(newGeneration, base = 8.0)
                db.sensitivityRuntimeSnapshotDao().upsert(
                    snapshot("new-cycle", 42L, newGeneration)
                )
                db.forecastDao().insertAll(newForecasts)
                persistAcceptedTuple(
                    acceptedTuple(now - 1L, "new-cycle", 42L, newGeneration, newForecasts)
                )
            }
            true
        }

        assertThat(withTimeoutOrNull(100L) { replacement.await() }).isNull()
        releaseRead.complete(Unit)

        assertThat(loaded.await()?.snapshot?.forecastCycleId).isEqualTo("old-cycle")
        assertThat(replacement.await()).isTrue()
        assertThat(AcceptedSensitivityTupleRoomLoader(db).load(identity(42L), now)?.snapshot?.forecastCycleId)
            .isEqualTo("new-cycle")
    }

    @Test
    fun liveExactMarkerLoadDoesNotEnumerateOrFallbackToOlderCommittedHistory() = runBlocking {
        val now = 1_785_556_800_000L
        val oldGeneration = now - 120_000L
        val oldMarker = now - 90_000L
        val oldForecasts = forecastSet(oldGeneration, base = 5.0)
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot("old-valid-cycle", 92L, oldGeneration))
        db.forecastDao().insertAll(oldForecasts)
        persistAcceptedTuple(
            acceptedTuple(oldMarker, "old-valid-cycle", 92L, oldGeneration, oldForecasts)
        )
        val exactMarker = now - 1L
        val missingForecasts = forecastSet(now - 60_000L, base = 8.0)
        persistAcceptedTuple(
            acceptedTuple(exactMarker, "missing-live-cycle", 92L, now - 60_000L, missingForecasts)
        )
        val markerQueries = mutableListOf<List<Long>>()
        val loader = AcceptedSensitivityTupleRoomLoader(
            db = db,
            onMarkerPageLoaded = { markerQueries += it }
        )

        val exact = loader.loadExact(identity(92L), exactMarker, now)

        assertThat(exact).isNull()
        assertThat(markerQueries).containsExactly(listOf(exactMarker))
        assertThat(loader.load(identity(92L), now)?.snapshot?.forecastCycleId)
            .isEqualTo("old-valid-cycle")
    }

    @Test
    fun dispatchLoaderChecksOnlyNewestCommittedMarkerAndNeverFallsBack(): Unit = runBlocking {
        val now = 1_785_556_800_000L
        val generation = now - 120_000L
        val validMarker = now - 100_000L
        val forecasts = forecastSet(generation, base = 6.0)
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot("dispatch-old-valid", 93L, generation))
        db.forecastDao().insertAll(forecasts)
        persistAcceptedTuple(acceptedTuple(validMarker, "dispatch-old-valid", 93L, generation, forecasts))
        repeat(ACCEPTED_SENSITIVITY_MARKER_PAGE_SIZE + 5) { index ->
            persistAcceptedTuple(acceptedTuple(validMarker + index + 1L,
                "dispatch-unusable-$index", 93L, generation, forecasts))
        }
        val pages = mutableListOf<List<Long>>()
        val loader = AcceptedSensitivityTupleRoomLoader(db, onMarkerPageLoaded = { pages += it })

        assertThat(loader.loadLatestCommitted(identity(93L), now)).isNull()
        assertThat(pages).containsExactly(listOf(validMarker + ACCEPTED_SENSITIVITY_MARKER_PAGE_SIZE + 5L))

        val newGeneration = now - 30_000L
        val newForecasts = forecastSet(newGeneration, base = 8.0)
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot("dispatch-current", 93L, newGeneration))
        db.forecastDao().insertAll(newForecasts)
        persistAcceptedTuple(acceptedTuple(now - 1L, "dispatch-current", 93L, newGeneration, newForecasts))
        pages.clear()

        assertThat(loader.loadLatestCommitted(identity(93L), now)?.snapshot?.forecastCycleId)
            .isEqualTo("dispatch-current")
        assertThat(pages).containsExactly(listOf(now - 1L))
    }

    @Test
    fun startupLoaderPagesNewestFirstAcrossCorruptAndOtherRevisionMarkers() = runBlocking {
        val now = 1_785_556_800_000L
        val validGeneration = now - 120_000L
        val validMarker = now - 100_000L
        val validForecasts = forecastSet(validGeneration, base = 6.0)
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot("paged-valid-cycle", 93L, validGeneration))
        db.forecastDao().insertAll(validForecasts)
        persistAcceptedTuple(
            acceptedTuple(validMarker, "paged-valid-cycle", 93L, validGeneration, validForecasts)
        )
        repeat(ACCEPTED_SENSITIVITY_MARKER_PAGE_SIZE + 5) { index ->
            val marker = validMarker + index + 1L
            val revision = if (index == ACCEPTED_SENSITIVITY_MARKER_PAGE_SIZE + 4) 93L else 1_000L + index
            persistAcceptedTuple(
                acceptedTuple(
                    markerTs = marker,
                    cycleId = "newer-unusable-$index",
                    revision = revision,
                    forecastTs = validGeneration,
                    forecasts = validForecasts
                )
            )
        }
        val pages = mutableListOf<List<Long>>()
        val loader = AcceptedSensitivityTupleRoomLoader(
            db = db,
            onMarkerPageLoaded = { page -> pages += page }
        )

        val loaded = loader.load(identity(93L), now)

        assertThat(loaded?.snapshot?.forecastCycleId).isEqualTo("paged-valid-cycle")
        assertThat(pages.size).isAtLeast(2)
        assertThat(pages.all { it.size <= ACCEPTED_SENSITIVITY_MARKER_PAGE_SIZE }).isTrue()
        assertThat(pages.flatten()).isInOrder(compareByDescending<Long> { it })
        assertThat(pages.flatten()).contains(validMarker)
    }

    @Test
    fun rejectedAcceptedCommitRollsBackForecastRowsVisibleToHistoricalReaders() = runBlocking {
        val markerTs = 1_785_556_800_000L
        val generationTs = markerTs - 60_000L
        val oldRows = forecastSet(generationTs, base = 6.0)
        val candidateRows = forecastSet(generationTs, base = 9.0)
        val candidate = snapshot("candidate-cycle", 55L, generationTs)
            .toSensitivityRuntimeSnapshotOrNull()!!
        db.forecastDao().insertAll(oldRows)
        val inputGeneration = seedValidInputGeneration()

        val failure = runCatching {
            AutomationRepository.commitAcceptedSensitivityCycleStatic(
                db = db,
                acceptedAtTs = markerTs,
                snapshot = candidate,
                forecastRows = candidateRows,
                expectedIsfCrInputGeneration = inputGeneration
            )
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(db.forecastDao().atGenerationTimestamp(generationTs).map { it.valueMmol })
            .containsExactlyElementsIn(oldRows.map { it.valueMmol }).inOrder()
    }

    @Test
    fun acceptedCommitRejectsARealtimeSnapshotFromAnObsoleteBaseModel() = runBlocking {
        val markerTs = 1_785_556_800_000L
        val generationTs = markerTs - 60_000L
        val oldRows = forecastSet(generationTs, base = 6.0)
        val candidateRows = forecastSet(generationTs, base = 9.0)
        val candidate = snapshot("obsolete-model-cycle", 55L, generationTs)
            .toSensitivityRuntimeSnapshotOrNull()!!
        db.forecastDao().insertAll(oldRows)
        db.isfCrModelStateDao().upsert(
            IsfCrModelStateEntity(
                updatedAt = 2_000L,
                hourlyIsfJson = "[]",
                hourlyCrJson = "[]",
                paramsJson = "{}",
                fitMetricsJson = "{}"
            )
        )
        db.profileEstimateDao().upsert(activeProfile(timestamp = 3_000L))

        val failure = runCatching {
            AutomationRepository.commitAcceptedSensitivityCycleStatic(
                db = db,
                acceptedAtTs = markerTs,
                snapshot = candidate,
                forecastRows = candidateRows,
                expectedIsfCrInputGeneration = IsfCrInputGeneration(
                    modelRevision = 1_000L,
                    profileRevision = 3_000L
                )
            )
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(failure).hasMessageThat().contains("input generation changed")
        assertThat(db.forecastDao().atGenerationTimestamp(generationTs).map { it.valueMmol })
            .containsExactlyElementsIn(oldRows.map { it.valueMmol }).inOrder()

        val profileFailure = runCatching {
            AutomationRepository.commitAcceptedSensitivityCycleStatic(
                db = db,
                acceptedAtTs = markerTs,
                snapshot = candidate,
                forecastRows = candidateRows,
                expectedIsfCrInputGeneration = IsfCrInputGeneration(
                    modelRevision = 2_000L,
                    profileRevision = 2_999L
                )
            )
        }.exceptionOrNull()

        assertThat(profileFailure).isNotNull()
        assertThat(profileFailure).hasMessageThat().contains("input generation changed")
        assertThat(db.forecastDao().atGenerationTimestamp(generationTs).map { it.valueMmol })
            .containsExactlyElementsIn(oldRows.map { it.valueMmol }).inOrder()
    }

    @Test
    fun acceptedCommitRejectsMissingAndZeroInputGenerationsBeforeForecastReplacement() = runBlocking {
        val markerTs = 1_785_556_800_000L
        val generationTs = markerTs - 60_000L
        val oldRows = forecastSet(generationTs, base = 6.0)
        val candidateRows = forecastSet(generationTs, base = 9.0)
        val candidate = snapshot("missing-generation-cycle", 55L, generationTs)
            .toSensitivityRuntimeSnapshotOrNull()!!
        db.forecastDao().insertAll(oldRows)

        val missingFailure = runCatching {
            AutomationRepository.commitAcceptedSensitivityCycleStatic(
                db = db,
                acceptedAtTs = markerTs,
                snapshot = candidate,
                forecastRows = candidateRows,
                expectedIsfCrInputGeneration = IsfCrInputGeneration(1_000L, 2_000L)
            )
        }.exceptionOrNull()
        val zeroFailure = runCatching {
            AutomationRepository.commitAcceptedSensitivityCycleStatic(
                db = db,
                acceptedAtTs = markerTs,
                snapshot = candidate,
                forecastRows = candidateRows,
                expectedIsfCrInputGeneration = IsfCrInputGeneration(0L, 0L)
            )
        }.exceptionOrNull()

        assertThat(missingFailure).hasMessageThat().contains("input generation changed")
        assertThat(zeroFailure).hasMessageThat().contains("revision is invalid")
        assertThat(db.forecastDao().atGenerationTimestamp(generationTs).map { it.valueMmol })
            .containsExactlyElementsIn(oldRows.map { it.valueMmol }).inOrder()
    }

    @Test
    fun currentRealtimeSnapshotIsInvalidatedWhenItsProfileGenerationChanges() = runBlocking {
        val gson = Gson()
        val audit = AuditLogger(db.auditLogDao(), gson)
        val repository = IsfCrRepository(
            db = db,
            gson = gson,
            auditLogger = audit,
            glucoseCalibrationRepository = GlucoseCalibrationRepository(db, gson, audit)
        )
        db.isfCrModelStateDao().upsert(
            IsfCrModelStateEntity(
                updatedAt = 2_000L,
                hourlyIsfJson = "[]",
                hourlyCrJson = "[]",
                paramsJson = "{}",
                fitMetricsJson = "{}"
            )
        )
        db.profileEstimateDao().upsert(activeProfile(timestamp = 3_000L))
        db.isfCrSnapshotDao().upsert(
            IsfCrSnapshotEntity(
                id = "generation-bound-snapshot",
                ts = 4_000L,
                isfEff = 2.03,
                crEff = 10.0,
                isfBase = 2.03,
                crBase = 10.0,
                ciIsfLow = 1.8,
                ciIsfHigh = 2.3,
                ciCrLow = 8.0,
                ciCrHigh = 12.0,
                confidence = 0.8,
                qualityScore = 0.9,
                factorsJson = gson.toJson(
                    mapOf(
                        ISFCR_BASE_MODEL_REVISION_FACTOR to 2_000.0,
                        ISFCR_PROFILE_REVISION_FACTOR to 3_000.0
                    )
                ),
                mode = "ACTIVE"
            )
        )

        assertThat(repository.latestSnapshot()).isNotNull()

        db.profileEstimateDao().upsert(activeProfile(timestamp = 3_001L))

        assertThat(repository.latestSnapshot()).isNull()
    }

    @Test
    fun acceptedCommitPublishesForecastRowsAndCommittedMarkerInOneRoomTransaction() = runBlocking {
        val markerTs = 1_785_556_800_000L
        val generationTs = markerTs - 60_000L
        val candidateRows = forecastSet(generationTs, base = 8.0)
        val candidate = snapshot("candidate-cycle", 56L, generationTs)
            .toSensitivityRuntimeSnapshotOrNull()!!
        val inputGeneration = seedValidInputGeneration()
        persistAcceptedTuple(
            acceptedTuple(
                markerTs = markerTs,
                cycleId = candidate.forecastCycleId,
                revision = candidate.settingsRevision,
                forecastTs = generationTs,
                forecasts = candidateRows,
                publicationState = SENSITIVITY_ACCEPTED_PUBLICATION_PENDING
            )
        )

        AutomationRepository.commitAcceptedSensitivityCycleStatic(
            db = db,
            acceptedAtTs = markerTs,
            snapshot = candidate,
            forecastRows = candidateRows,
            expectedIsfCrInputGeneration = inputGeneration
        )

        assertThat(db.forecastDao().atGenerationTimestamp(generationTs).map { it.valueMmol })
            .containsExactlyElementsIn(candidateRows.map { it.valueMmol }).inOrder()
        val marker = db.telemetryDao().atTimestampBySourceAndKeys(
            SENSITIVITY_ACCEPTED_SOURCE,
            markerTs,
            ACCEPTED_SENSITIVITY_MARKER_KEYS.toList()
        )
        assertThat(marker.single { it.key == SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY }.valueText)
            .isEqualTo(SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED)
    }

    @Test
    fun markerCollisionPendingFailurePreservesCommittedTupleAndNextMarkerIsAccepted() = runBlocking {
        val wallClockTs = 1_785_556_800_000L
        val oldGenerationTs = wallClockTs - 120_000L
        val failedGenerationTs = wallClockTs - 60_000L
        val nextGenerationTs = wallClockTs
        val oldForecasts = forecastSet(oldGenerationTs, base = 5.0)
        val failedForecasts = forecastSet(failedGenerationTs, base = 7.0)
        val nextForecasts = forecastSet(nextGenerationTs, base = 8.0)
        val inputGeneration = seedValidInputGeneration()
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot("old-cycle", 57L, oldGenerationTs))
        db.forecastDao().insertAll(oldForecasts)
        persistAcceptedTuple(
            acceptedTuple(wallClockTs, "old-cycle", 57L, oldGenerationTs, oldForecasts)
        )

        val failedMarker = AutomationRepository.allocateAcceptedSensitivityMarkerStatic(
            telemetryDao = db.telemetryDao(),
            wallClockTs = wallClockTs
        )
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot("failed-cycle", 57L, failedGenerationTs))
        val pendingFailure = runCatching {
            persistAcceptedTuple(
                acceptedTuple(
                    failedMarker,
                    "failed-cycle",
                    57L,
                    failedGenerationTs,
                    failedForecasts,
                    publicationState = SENSITIVITY_ACCEPTED_PUBLICATION_PENDING
                )
            )
            error("forced pending publication failure")
        }.exceptionOrNull()

        assertThat(pendingFailure).hasMessageThat().contains("forced pending publication failure")
        assertThat(failedMarker).isEqualTo(wallClockTs + 1L)
        assertThat(AcceptedSensitivityTupleRoomLoader(db).load(identity(57L), failedMarker)
            ?.snapshot?.forecastCycleId).isEqualTo("old-cycle")
        assertThat(db.forecastDao().atGenerationTimestamp(oldGenerationTs).map { it.valueMmol })
            .containsExactlyElementsIn(oldForecasts.map { it.valueMmol }).inOrder()

        val nextMarker = AutomationRepository.allocateAcceptedSensitivityMarkerStatic(
            telemetryDao = db.telemetryDao(),
            wallClockTs = wallClockTs
        )
        val next = snapshot("next-cycle", 57L, nextGenerationTs).toSensitivityRuntimeSnapshotOrNull()!!
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { error("not used") },
            persistence = SensitivityRuntimePersistence { error("not used") },
            settingsRevision = { 57L },
            now = { wallClockTs }
        )
        val acceptedCycleId = AutomationRepository.runAfterAcceptedSensitivityCycleStatic(
            persistPendingRoomTuple = {
                db.sensitivityRuntimeSnapshotDao().upsert(next.toEntity())
                persistAcceptedTuple(
                    acceptedTuple(
                        nextMarker,
                        next.forecastCycleId,
                        next.settingsRevision,
                        nextGenerationTs,
                        nextForecasts,
                        publicationState = SENSITIVITY_ACCEPTED_PUBLICATION_PENDING
                    )
                )
            },
            reserveAccepted = {
                repository.reserveAccepted(next, nextMarker, next.forecastCycleId)
            },
            commitAcceptedRoomTuple = {
                AutomationRepository.commitAcceptedSensitivityCycleStatic(
                    db = db,
                    acceptedAtTs = nextMarker,
                    snapshot = next,
                    forecastRows = nextForecasts,
                    expectedIsfCrInputGeneration = inputGeneration
                )
            },
            reconcileAcceptedRoomTuple = {
                AcceptedSensitivityTupleRoomLoader(db).load(identity(57L), nextMarker)
                    ?.let { it.acceptedAtTs == nextMarker && it.snapshot == next } == true
            },
            finalizeAccepted = repository::finalizeAccepted,
            abortReservation = repository::abortAcceptedReservation,
            clinicalSideEffects = { repository.current.value?.forecastCycleId }
        )

        assertThat(nextMarker).isEqualTo(wallClockTs + 2L)
        assertThat(nextMarker).isNotEqualTo(failedMarker)
        assertThat(acceptedCycleId).isEqualTo("next-cycle")
        assertThat(repository.current.value).isSameInstanceAs(next)
        assertThat(AcceptedSensitivityTupleRoomLoader(db).load(identity(57L), nextMarker)
            ?.snapshot?.forecastCycleId).isEqualTo("next-cycle")
        assertThat(db.forecastDao().atGenerationTimestamp(oldGenerationTs).map { it.valueMmol })
            .containsExactlyElementsIn(oldForecasts.map { it.valueMmol }).inOrder()
    }

    @Test
    fun markerAllocationFailsClosedForOverflowAndUnboundedFutureSequence() = runBlocking {
        val wallClockTs = 1_785_556_800_000L
        db.telemetryDao().upsertAll(
            listOf(
                acceptedTelemetry(
                    id = "overflow-marker",
                    timestamp = Long.MAX_VALUE,
                    key = SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY,
                    valueText = SENSITIVITY_ACCEPTED_PUBLICATION_PENDING
                )
            )
        )

        val overflow = runCatching {
            AutomationRepository.allocateAcceptedSensitivityMarkerStatic(
                telemetryDao = db.telemetryDao(),
                wallClockTs = wallClockTs
            )
        }.exceptionOrNull()
        assertThat(overflow).isNotNull()

        db.clearAllTables()
        db.telemetryDao().upsertAll(
            listOf(
                acceptedTelemetry(
                    id = "far-future-marker",
                    timestamp = wallClockTs + 60_000L,
                    key = SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY,
                    valueText = SENSITIVITY_ACCEPTED_PUBLICATION_PENDING
                )
            )
        )
        val future = runCatching {
            AutomationRepository.allocateAcceptedSensitivityMarkerStatic(
                telemetryDao = db.telemetryDao(),
                wallClockTs = wallClockTs
            )
        }.exceptionOrNull()

        assertThat(future).isNotNull()
        assertThat(future).hasMessageThat().contains("future")
    }

    @Test
    fun collidingMarkersWaitOnceAndAreImmediatelyVisibleToEveryAcceptedConsumer() = runBlocking {
        listOf(1L, 1_000L).forEach { requiredAdvanceMs ->
            db.clearAllTables()
            val inputGeneration = seedValidInputGeneration()
            val initialNow = 1_785_556_800_000L
            val previousMarker = initialNow + requiredAdvanceMs - 1L
            val previousGeneration = initialNow - 120_000L
            val previousForecasts = forecastSet(previousGeneration, base = 5.0)
            db.sensitivityRuntimeSnapshotDao().upsert(
                snapshot("previous-$requiredAdvanceMs", 58L, previousGeneration)
            )
            db.forecastDao().insertAll(previousForecasts)
            persistAcceptedTuple(
                acceptedTuple(
                    previousMarker,
                    "previous-$requiredAdvanceMs",
                    58L,
                    previousGeneration,
                    previousForecasts
                )
            )
            var consumerNow = initialNow
            val delays = mutableListOf<Long>()

            val marker = AutomationRepository.allocateAcceptedSensitivityMarkerStatic(
                telemetryDao = db.telemetryDao(),
                wallClockTs = initialNow,
                authoritativeNow = { consumerNow },
                suspendForMs = { delayMs ->
                    delays += delayMs
                    consumerNow = initialNow + delayMs
                }
            )

            val generationTs = initialNow
            val forecasts = forecastSet(generationTs, base = 8.0)
            val runtime = snapshot("accepted-$requiredAdvanceMs", 58L, generationTs)
                .toSensitivityRuntimeSnapshotOrNull()!!
            db.sensitivityRuntimeSnapshotDao().upsert(runtime.toEntity())
            persistAcceptedTuple(
                acceptedTuple(
                    marker,
                    runtime.forecastCycleId,
                    runtime.settingsRevision,
                    generationTs,
                    forecasts,
                    publicationState = SENSITIVITY_ACCEPTED_PUBLICATION_PENDING
                )
            )
            AutomationRepository.commitAcceptedSensitivityCycleStatic(
                db = db,
                acceptedAtTs = marker,
                snapshot = runtime,
                forecastRows = forecasts,
                expectedIsfCrInputGeneration = inputGeneration
            )

            val markerRows = db.telemetryDao().atTimestampBySourceAndKeys(
                SENSITIVITY_ACCEPTED_SOURCE,
                marker,
                ACCEPTED_SENSITIVITY_MARKER_KEYS.toList()
            )
            val forecastResolved = ForecastSnapshotResolver.resolveAcceptedTuple(
                forecasts = forecasts,
                telemetry = markerRows,
                snapshot = runtime,
                currentSettings = identity(58L),
                authoritativeNowTs = consumerNow
            )

            assertThat(delays).containsExactly(requiredAdvanceMs)
            assertThat(marker).isEqualTo(initialNow + requiredAdvanceMs)
            assertThat(marker).isAtMost(consumerNow)
            assertThat(AcceptedSensitivityTupleRoomLoader(db).load(identity(58L), consumerNow)
                ?.snapshot?.forecastCycleId).isEqualTo(runtime.forecastCycleId)
            assertThat(forecastResolved.sensitivity?.forecastCycleId).isEqualTo(runtime.forecastCycleId)
            assertThat(CopilotGlucoseWidgetRepository(db) { identity(58L) }
                .loadSnapshot(consumerNow).predicted30Mmol).isNotNull()
        }
    }

    @Test
    fun markerMoreThanOneSecondAheadFailsClosedWithoutDelayOrWrite() = runBlocking {
        val initialNow = 1_785_556_800_000L
        val latestMarker = initialNow + 1_000L
        db.telemetryDao().upsertAll(
            listOf(
                acceptedTelemetry(
                    id = "latest-marker",
                    timestamp = latestMarker,
                    key = SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY,
                    valueText = SENSITIVITY_ACCEPTED_PUBLICATION_PENDING
                )
            )
        )
        var delayCalls = 0

        val failure = runCatching {
            AutomationRepository.allocateAcceptedSensitivityMarkerStatic(
                telemetryDao = db.telemetryDao(),
                wallClockTs = initialNow,
                authoritativeNow = { initialNow + 1_001L },
                suspendForMs = { delayCalls += 1 }
            )
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("future")
        assertThat(delayCalls).isEqualTo(0)
        assertThat(db.telemetryDao().latestTimestampBySourceAndKey(
            SENSITIVITY_ACCEPTED_SOURCE,
            SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
        )).isEqualTo(latestMarker)
        assertThat(db.telemetryDao().atTimestampBySourceAndKeys(
            SENSITIVITY_ACCEPTED_SOURCE,
            initialNow + 1_001L,
            ACCEPTED_SENSITIVITY_MARKER_KEYS.toList()
        )).isEmpty()
    }

    @Test
    fun clockNotAdvancedOrRolledBackAfterDelayFailsClosedWithoutWrite() = runBlocking {
        val initialNow = 1_785_556_800_000L
        db.telemetryDao().upsertAll(
            listOf(
                acceptedTelemetry(
                    id = "colliding-marker",
                    timestamp = initialNow,
                    key = SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY,
                    valueText = SENSITIVITY_ACCEPTED_PUBLICATION_PENDING
                )
            )
        )

        listOf(initialNow, initialNow - 1L).forEach { actualNow ->
            val delays = mutableListOf<Long>()
            val failure = runCatching {
                AutomationRepository.allocateAcceptedSensitivityMarkerStatic(
                    telemetryDao = db.telemetryDao(),
                    wallClockTs = initialNow,
                    authoritativeNow = { actualNow },
                    suspendForMs = { delays += it }
                )
            }.exceptionOrNull()

            assertThat(failure).hasMessageThat().contains("did not reach")
            assertThat(delays).containsExactly(1L)
            assertThat(db.telemetryDao().atTimestampBySourceAndKeys(
                SENSITIVITY_ACCEPTED_SOURCE,
                initialNow + 1L,
                ACCEPTED_SENSITIVITY_MARKER_KEYS.toList()
            )).isEmpty()
        }
    }

    @Test
    fun noCollisionClockRollbackFailsClosedWithoutDelayOrPendingWrite() = runBlocking {
        val initialNow = 1_785_556_800_000L
        listOf(
            null to 1L,
            initialNow - 10L to 1_001L
        ).forEach { (latestMarker, rollbackMs) ->
            db.clearAllTables()
            latestMarker?.let { markerTs ->
                db.telemetryDao().upsertAll(
                    listOf(
                        acceptedTelemetry(
                            id = "older-marker",
                            timestamp = markerTs,
                            key = SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY,
                            valueText = SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED
                        )
                    )
                )
            }
            var authoritativeReads = 0
            var delayCalls = 0

            val failure = runCatching {
                AutomationRepository.allocateAcceptedSensitivityMarkerStatic(
                    telemetryDao = db.telemetryDao(),
                    wallClockTs = initialNow,
                    authoritativeNow = {
                        authoritativeReads += 1
                        initialNow - rollbackMs
                    },
                    suspendForMs = { delayCalls += 1 }
                )
            }.exceptionOrNull()

            assertThat(failure).hasMessageThat().contains("did not reach")
            assertThat(authoritativeReads).isEqualTo(1)
            assertThat(delayCalls).isEqualTo(0)
            assertThat(db.telemetryDao().atTimestampBySourceAndKeys(
                SENSITIVITY_ACCEPTED_SOURCE,
                initialNow,
                ACCEPTED_SENSITIVITY_MARKER_KEYS.toList()
            )).isEmpty()
            assertThat(db.telemetryDao().latestTimestampBySourceAndKey(
                SENSITIVITY_ACCEPTED_SOURCE,
                SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
            )).isEqualTo(latestMarker)
        }
    }

    private fun snapshot(
        cycleId: String,
        revision: Long,
        generatedAt: Long,
        isf: Double = 3.0,
        cr: Double = 10.0
    ) = SensitivityRuntimeSnapshotEntity(
        cycleId = cycleId,
        settingsRevision = revision,
        generatedAt = generatedAt,
        isfRequestedSource = "COPILOT",
        isfResolvedSource = "COPILOT_NATIVE",
        isfRawAaps = null,
        isfRawEvidence = null,
        isfRawCopilot = isf,
        isfBlended = null,
        isfEffective = isf,
        isfConfidence = 1.0,
        isfFallbackReason = null,
        crRequestedSource = "COPILOT",
        crResolvedSource = "COPILOT_NATIVE",
        crRawAaps = null,
        crRawEvidence = null,
        crRawCopilot = cr,
        crBlended = null,
        crEffective = cr,
        crConfidence = 1.0,
        crFallbackReason = null
    )

    private fun resolvedSnapshot(
        cycleId: String,
        ts: Long,
        isfPreference: SensitivitySourcePreference,
        isfAaps: SensitivityCandidate = runtimeCandidate(null, ts, unavailableReason = "missing"),
        isfEvidence: SensitivityCandidate = runtimeCandidate(null, ts, unavailableReason = "missing"),
        isfCopilot: SensitivityCandidate,
        revision: Long = 43L
    ) = SensitivityRuntimeResolver.resolve(
        settingsRevision = revision,
        forecastCycleId = cycleId,
        timestamp = ts,
        isfPreference = isfPreference,
        crPreference = SensitivitySourcePreference.COPILOT,
        isfCandidates = SensitivityCandidates(isfAaps, isfEvidence, isfCopilot),
        crCandidates = SensitivityCandidates(
            runtimeCandidate(null, ts, unavailableReason = "missing"),
            runtimeCandidate(null, ts, unavailableReason = "missing"),
            runtimeCandidate(10.0, ts)
        ),
        freshnessMs = 3_600_000L
    )

    private fun runtimeCandidate(
        value: Double?,
        ts: Long,
        confidence: Double = 1.0,
        qualityPassed: Boolean = true,
        unavailableReason: String? = null
    ) = SensitivityCandidate(
        value = value,
        timestamp = ts,
        confidence = confidence,
        qualityPassed = qualityPassed,
        sampleCount = if (value == null) 0 else 8,
        coverage = if (value == null) 0.0 else 1.0,
        unavailableReason = unavailableReason
    )

    private fun identity(
        revision: Long,
        isfSource: SensitivitySourcePreference = SensitivitySourcePreference.COPILOT,
        crSource: SensitivitySourcePreference = SensitivitySourcePreference.COPILOT
    ) = SensitivityRuntimeSettingsIdentity(revision, isfSource, crSource)

    private suspend fun reportCurrent(now: Long, revision: Long) =
        ClinicalReportDatasetBuilder(
            db = db,
            glucoseCalibrationRepository = calibrationRepository(now),
            aapsTddSource = ClinicalAapsTddSource { _, _, _, _ -> null },
            sensitivitySettingsIdentity = { identity(revision) }
        ).build(now, ZoneId.of("UTC")).dataset.currentSnapshot

    private fun calibrationRepository(now: Long): GlucoseCalibrationRepository {
        val gson = Gson()
        return GlucoseCalibrationRepository(
            db = db,
            gson = gson,
            auditLogger = AuditLogger(db.auditLogDao(), gson) { now }
        )
    }

    private fun sensitivityRepositoryForHydration(
        loader: AcceptedSensitivityTupleRoomLoader,
        revision: Long,
        now: Long
    ) = SensitivityRuntimeRepository(
        loadedCandidates = { error("startup hydration must not load candidates") },
        persistence = SensitivityRuntimePersistence { error("startup hydration must not persist") },
        settingsRevision = { revision },
        acceptedSnapshotLoader = { currentRevision, atTs ->
            loader.load(identity(currentRevision), atTs)?.toRuntimePublication()
        },
        now = { now }
    )

    private fun AcceptedSensitivityRoomTuple.toRuntimePublication() =
        AcceptedSensitivityRuntimePublication(
            snapshot = snapshot,
            acceptedAtTs = acceptedAtTs,
            acceptedCycleId = snapshot.forecastCycleId
        )

    private fun forecastSet(timestamp: Long, base: Double): List<ForecastEntity> =
        listOf(5, 30, 60).map { horizon ->
            forecast(timestamp + horizon * 60_000L, horizon, base + horizon / 100.0)
        }

    private fun forecast(timestamp: Long, horizon: Int, value: Double) = ForecastEntity(
        timestamp = timestamp,
        horizonMinutes = horizon,
        valueMmol = value,
        ciLow = value - 0.5,
        ciHigh = value + 0.5,
        modelVersion = "test"
    )

    private suspend fun persistAcceptedTuple(
        rows: List<TelemetrySampleEntity>,
        includeSensorEvidence: Boolean = true
    ) {
        val markerTs = rows.firstOrNull { it.source == SENSITIVITY_ACCEPTED_SOURCE }?.timestamp
        val sessionStartTs = markerTs?.takeIf { includeSensorEvidence }?.let { timestamp ->
            testSensorSessionStartTs ?: (timestamp - 60L * 60L * 1000L).also {
                testSensorSessionStartTs = it
            }
        }
        val sensorAgeHours = if (markerTs != null && sessionStartTs != null) {
            (markerTs - sessionStartTs).toDouble() / 3_600_000.0
        } else {
            null
        }
        val sessionKey = if (markerTs != null && sensorAgeHours != null) {
            calibrationSensorSessionKeyFromAgeSample(markerTs, sensorAgeHours)
        } else {
            null
        }
        val authorityToken = markerTs?.let {
            CalibrationAuthorityStateCodec.raw(
                nonce = "room-accepted-$sessionKey",
                sessionKey = sessionKey
            )
        }
        val effectiveRows = rows.map { row ->
            when {
                row.source == ACCEPTED_CALIBRATION_SOURCE &&
                row.key == ACCEPTED_CALIBRATION_SESSION_KEY &&
                row.valueText == ACCEPTED_CALIBRATION_NONE &&
                sessionKey != null -> row.copy(valueText = sessionKey)
                row.source == ACCEPTED_CALIBRATION_SOURCE &&
                    row.key == ACCEPTED_CALIBRATION_AUTHORITY_TOKEN_KEY &&
                    row.valueText == ACCEPTED_CALIBRATION_NONE &&
                    authorityToken != null -> row.copy(valueText = authorityToken)
                else -> row
            }
        }
        if (markerTs != null) {
            db.glucoseDao().upsertAll(
                listOf(
                    GlucoseSampleEntity(
                        timestamp = markerTs,
                        mmol = 5.5,
                        source = "test",
                        quality = "OK"
                    )
                )
            )
            if (sensorAgeHours != null) {
                db.telemetryDao().upsertAll(
                    listOf(sensorAgeTelemetry("accepted-$markerTs", "test_sensor", markerTs, sensorAgeHours))
                )
            }
            if (authorityToken != null && effectiveRows.any {
                    it.source == ACCEPTED_CALIBRATION_SOURCE &&
                        it.key == ACCEPTED_CALIBRATION_AUTHORITY_TOKEN_KEY
                }
            ) {
                db.telemetryDao().upsertAll(
                    listOf(
                        TelemetrySampleEntity(
                            id = "glucose-calibration-authority-current",
                            timestamp = markerTs,
                            source = CALIBRATION_AUTHORITY_SOURCE,
                            key = CALIBRATION_AUTHORITY_TOKEN_KEY,
                            valueDouble = null,
                            valueText = authorityToken,
                            unit = null,
                            quality = "OK"
                        )
                    )
                )
            }
        }
        db.telemetryDao().upsertAcceptedSensitivityTuple(
            effectiveRows.filter { it.source == SENSITIVITY_ACCEPTED_SOURCE }
        )
        db.telemetryDao().upsertAll(
            effectiveRows.filter { it.source == ACCEPTED_CALIBRATION_SOURCE }
        )
    }

    private fun acceptedTuple(
        markerTs: Long,
        cycleId: String,
        revision: Long,
        forecastTs: Long,
        forecasts: List<ForecastEntity> = forecastSet(forecastTs, base = 6.0),
        publicationState: String = SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED
    ) = listOf(
        acceptedTelemetry(
            id = "$cycleId:id",
            timestamp = markerTs,
            key = SENSITIVITY_ACCEPTED_CYCLE_ID_KEY,
            valueText = cycleId
        ),
        acceptedTelemetry(
            id = "$cycleId:revision",
            timestamp = markerTs,
            key = SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY,
            valueDouble = revision.toDouble()
        ),
        acceptedTelemetry(
            id = "$cycleId:forecast",
            timestamp = markerTs,
            key = SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY,
            valueDouble = forecastTs.toDouble()
        ),
        acceptedTelemetry(
            id = "$cycleId:decomposition",
            timestamp = markerTs,
            key = SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY,
            valueText = SensitivityAcceptedForecastDecompositionCodec.encode(
                SensitivityAcceptedForecastDecomposition.unavailable()
            )
        ),
        acceptedTelemetry(
            id = "$cycleId:digest",
            timestamp = markerTs,
            key = SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY,
            valueText = SensitivityAcceptedForecastDigest.compute(
                cycleId = cycleId,
                settingsRevision = revision,
                forecasts = forecasts.map { row ->
                    SensitivityAcceptedForecastRow(
                        horizonMinutes = row.horizonMinutes,
                        targetTimestamp = row.timestamp,
                        valueMmol = row.valueMmol,
                        ciLow = row.ciLow,
                        ciHigh = row.ciHigh,
                        modelVersion = row.modelVersion
                    )
                },
                decomposition = SensitivityAcceptedForecastDecomposition.unavailable()
            )
        ),
        acceptedTelemetry(
            id = "$cycleId:publication",
            timestamp = markerTs,
            key = SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY,
            valueText = publicationState
        )
    ) + acceptedCalibrationTuple(markerTs, cycleId)

    private fun acceptedCalibrationTuple(
        markerTs: Long,
        cycleId: String
    ) = listOf(
        acceptedCalibrationTelemetry(markerTs, cycleId, ACCEPTED_CALIBRATION_MODEL_ID_KEY),
        acceptedCalibrationTelemetry(markerTs, cycleId, ACCEPTED_CALIBRATION_SESSION_KEY),
        acceptedCalibrationTelemetry(
            markerTs,
            cycleId,
            ACCEPTED_CALIBRATION_PREPARED_AT_KEY,
            value = markerTs.toString()
        ),
        acceptedCalibrationTelemetry(markerTs, cycleId, ACCEPTED_CALIBRATION_MODEL_FINGERPRINT_KEY),
        acceptedCalibrationTelemetry(markerTs, cycleId, ACCEPTED_CALIBRATION_AUTHORITY_TOKEN_KEY)
    )

    private fun acceptedCalibrationTelemetry(
        markerTs: Long,
        cycleId: String,
        key: String,
        value: String = ACCEPTED_CALIBRATION_NONE
    ) = TelemetrySampleEntity(
        id = "$cycleId:calibration:$key",
        timestamp = markerTs,
        source = ACCEPTED_CALIBRATION_SOURCE,
        key = key,
        valueDouble = null,
        valueText = value,
        unit = null,
        quality = "OK"
    )

    private fun sensorAgeTelemetry(
        id: String,
        source: String,
        timestamp: Long,
        ageHours: Double
    ) = TelemetrySampleEntity(
        id = id,
        timestamp = timestamp,
        source = source,
        key = "sensor_age_hours",
        valueDouble = ageHours,
        valueText = null,
        unit = "h",
        quality = "OK"
    )

    private fun acceptedTelemetry(
        id: String,
        timestamp: Long,
        key: String,
        valueDouble: Double? = null,
        valueText: String? = null
    ) = TelemetrySampleEntity(
        id = id,
        timestamp = timestamp,
        source = SENSITIVITY_ACCEPTED_SOURCE,
        key = key,
        valueDouble = valueDouble,
        valueText = valueText,
        unit = null,
        quality = "OK"
    )

    private fun activeProfile(timestamp: Long) = ProfileEstimateEntity(
        id = "active",
        timestamp = timestamp,
        isfMmolPerUnit = 3.1,
        crGramPerUnit = 10.0,
        confidence = 0.5,
        sampleCount = 12,
        isfSampleCount = 6,
        crSampleCount = 6,
        lookbackDays = 30,
        telemetryIsfSampleCount = 1,
        telemetryCrSampleCount = 1,
        uamObservedCount = 0,
        uamFilteredIsfSamples = 0,
        uamEpisodeCount = 0,
        uamEstimatedCarbsGrams = 0.0,
        uamEstimatedRecentCarbsGrams = 0.0,
        calculatedIsfMmolPerUnit = 3.0,
        calculatedCrGramPerUnit = 10.0,
        calculatedConfidence = 0.5,
        calculatedSampleCount = 12,
        calculatedIsfSampleCount = 6,
        calculatedCrSampleCount = 6
    )

    private suspend fun seedValidInputGeneration(): IsfCrInputGeneration {
        val generation = IsfCrInputGeneration(modelRevision = 1_000L, profileRevision = 2_000L)
        db.isfCrModelStateDao().upsert(
            IsfCrModelStateEntity(
                updatedAt = generation.modelRevision,
                hourlyIsfJson = "[]",
                hourlyCrJson = "[]",
                paramsJson = "{}",
                fitMetricsJson = "{}"
            )
        )
        db.profileEstimateDao().upsert(activeProfile(generation.profileRevision))
        return generation
    }

    private fun telemetry(
        timestamp: Long,
        key: String,
        value: Double
    ) = TelemetrySampleEntity(
        id = "$timestamp:$key",
        timestamp = timestamp,
        source = "copilot",
        key = key,
        valueDouble = value,
        valueText = null,
        unit = null,
        quality = "OK"
    )
}
