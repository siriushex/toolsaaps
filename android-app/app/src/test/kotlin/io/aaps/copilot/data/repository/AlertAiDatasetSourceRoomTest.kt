package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.config.ClinicalAiProviderConfig
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.AlertEventEntity
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseCalibrationModelEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import io.aaps.copilot.data.local.entity.SensitivityRuntimeSnapshotEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.alerts.AlertCauseDirection
import io.aaps.copilot.domain.alerts.AlertCauseSnapshotCodec
import io.aaps.copilot.domain.predict.SensitivityRuntimeSettingsIdentity
import io.aaps.copilot.domain.predict.SensitivitySourcePreference
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_CYCLE_ID_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_SOURCE
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AlertAiDatasetSourceRoomTest {
    private lateinit var db: CopilotDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            CopilotDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun exactlyAtPerSourceAndTotalLimitSucceedsWithoutExtraRetention() = runBlocking {
        db.glucoseDao().upsertAll(
            listOf(
                glucose("outside-alert-window", NOW - 20L * DAY_MS),
                glucose("first", NOW - 2L * MINUTE_MS),
                glucose("second", NOW - MINUTE_MS)
            )
        )
        val observed = mutableListOf<AlertAiDatasetReadObservation>()
        val source = roomSource(
            limits = testLimits(glucoseRows = 2, totalRows = 4),
            probe = AlertAiDatasetReadProbe(observed::add)
        )

        val snapshot = source.load(NOW)

        assertThat(snapshot.report.glucose).hasSize(2)
        assertThat(snapshot.report.glucose.map { it.ts }).isInStrictOrder()
        assertThat(observed.single { it.source == AlertAiDatasetSourceName.GLUCOSE }.queriedRows)
            .isEqualTo(2)
        assertThat(observed.all { it.queriedRows <= it.queryLimit + 1 }).isTrue()
    }

    @Test
    fun emptyNormalReadTouchesEveryTopLevelSourceThroughOneBoundedQuery() = runBlocking {
        val observed = mutableListOf<AlertAiDatasetReadObservation>()
        val source = roomSource(
            limits = testLimits(totalRows = 100),
            probe = AlertAiDatasetReadProbe(observed::add),
            sensitivityIdentity = SensitivityRuntimeSettingsIdentity(
                revision = 7L,
                isfSource = SensitivitySourcePreference.COPILOT,
                crSource = SensitivitySourcePreference.COPILOT
            )
        )

        source.load(NOW)

        assertThat(observed.map { it.source }).containsExactly(
            AlertAiDatasetSourceName.GLUCOSE,
            AlertAiDatasetSourceName.THERAPY,
            AlertAiDatasetSourceName.FORECAST,
            AlertAiDatasetSourceName.TELEMETRY,
            AlertAiDatasetSourceName.ACTIVITY_DETAIL_BUCKETS,
            AlertAiDatasetSourceName.CALIBRATION_CAUSAL_GLUCOSE,
            AlertAiDatasetSourceName.CALIBRATION_EVIDENCE,
            AlertAiDatasetSourceName.CALIBRATION_ACTIVE_MODEL,
            AlertAiDatasetSourceName.CALIBRATION_AUTHORITY_TOKEN,
            AlertAiDatasetSourceName.SENSITIVITY_MARKERS,
            AlertAiDatasetSourceName.TIMELINE_THERAPY,
            AlertAiDatasetSourceName.PLANNED_ACTIVITY,
            AlertAiDatasetSourceName.ACTIVITY_TIMELINE_BUCKETS,
            AlertAiDatasetSourceName.DELIVERY_DIAGNOSTICS,
            AlertAiDatasetSourceName.CALIBRATION_EVENTS,
            AlertAiDatasetSourceName.CONTEXT_EVENTS
        ).inOrder()
        assertThat(observed.all { it.queriedRows <= it.queryLimit + 1 }).isTrue()
    }

    @Test
    fun totalLimitOverflowFailsEvenWhenEachSourceIsWithinItsOwnLimit() = runBlocking {
        db.glucoseDao().upsertAll(listOf(glucose("total-glucose", NOW - MINUTE_MS)))
        db.energyProfileDao().upsertEvent(recurringActivity("total-planned"))
        val observed = mutableListOf<AlertAiDatasetReadObservation>()
        val source = roomSource(
            limits = testLimits(glucoseRows = 2, plannedActivityRows = 2, totalRows = 2),
            probe = AlertAiDatasetReadProbe(observed::add)
        )

        assertThrows(AlertAiContextException.LimitExceeded::class.java) {
            runBlocking { source.load(NOW) }
        }

        assertThat(observed.single {
            it.source == AlertAiDatasetSourceName.GLUCOSE
        }.queriedRows).isEqualTo(1)
        val plannedRead = observed.single {
            it.source == AlertAiDatasetSourceName.PLANNED_ACTIVITY
        }
        assertThat(plannedRead.queriedRows).isEqualTo(1)
        assertThat(plannedRead.queryLimit).isEqualTo(0)
        assertThat(observed.all { it.queriedRows <= it.limit }).isTrue()
    }

    @Test
    fun sourceLimitPlusOneFailsBeforeRetainingUnboundedRows() = runBlocking {
        db.glucoseDao().upsertAll(
            (0..2).map { index -> glucose("overflow-$index", NOW - index * MINUTE_MS) }
        )
        val observed = mutableListOf<AlertAiDatasetReadObservation>()
        val source = roomSource(
            limits = testLimits(glucoseRows = 2, totalRows = 20),
            probe = AlertAiDatasetReadProbe(observed::add)
        )

        assertThrows(AlertAiContextException.LimitExceeded::class.java) {
            runBlocking { source.load(NOW) }
        }

        val glucoseRead = observed.single { it.source == AlertAiDatasetSourceName.GLUCOSE }
        assertThat(glucoseRead.queriedRows).isEqualTo(3)
        assertThat(glucoseRead.limit).isEqualTo(2)
        assertThat(observed.all { it.queriedRows <= it.queryLimit + 1 }).isTrue()
    }

    @Test
    fun acceptedSensitivityMarkerLimitPlusOneFailsBeforeTupleScanning() = runBlocking {
        db.telemetryDao().upsertAll(
            (0..10).map { index ->
                TelemetrySampleEntity(
                    id = "accepted-marker-$index",
                    timestamp = NOW - index,
                    source = SENSITIVITY_ACCEPTED_SOURCE,
                    key = SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY,
                    valueDouble = null,
                    valueText = SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED,
                    unit = null,
                    quality = "OK"
                )
            }
        )
        val observed = mutableListOf<AlertAiDatasetReadObservation>()
        val source = roomSource(
            limits = testLimits(totalRows = 100),
            probe = AlertAiDatasetReadProbe(observed::add),
            sensitivityIdentity = SensitivityRuntimeSettingsIdentity(
                revision = 7L,
                isfSource = SensitivitySourcePreference.COPILOT,
                crSource = SensitivitySourcePreference.COPILOT
            )
        )

        assertThrows(AlertAiContextException.LimitExceeded::class.java) {
            runBlocking { source.load(NOW) }
        }

        val markerRead = observed.single {
            it.source == AlertAiDatasetSourceName.SENSITIVITY_MARKERS
        }
        assertThat(markerRead.queriedRows).isEqualTo(11)
        assertThat(markerRead.queryLimit).isEqualTo(10)
        assertThat(observed.none {
            it.source == AlertAiDatasetSourceName.SENSITIVITY_MARKER_DETAIL
        }).isTrue()
    }

    @Test
    fun recurringScheduleStartedYearsEarlierStillOverlapsFourteenDayWindow() = runBlocking {
        db.energyProfileDao().upsertEvent(recurringActivity("old-recurring"))
        val observed = mutableListOf<AlertAiDatasetReadObservation>()
        val source = roomSource(
            limits = testLimits(plannedActivityRows = 1, totalRows = 1),
            probe = AlertAiDatasetReadProbe(observed::add)
        )

        val snapshot = source.load(NOW)

        assertThat(snapshot.timelineEvents.any {
            it.localId.startsWith("planned:old-recurring:") &&
                it.startTs in (NOW - FOURTEEN_DAYS_MS)..NOW
        }).isTrue()
        assertThat(observed.single {
            it.source == AlertAiDatasetSourceName.PLANNED_ACTIVITY
        }.queriedRows).isEqualTo(1)
    }

    @Test
    fun calibratedGlucoseCopiesSucceedExactlyAtDerivedLimitAndNextRowFails() = runBlocking {
        db.glucoseDao().upsertAll(
            listOf(
                glucose("derived-first", NOW - 2L * MINUTE_MS),
                glucose("derived-second", NOW - MINUTE_MS)
            )
        )
        val observed = mutableListOf<AlertAiDatasetDerivedObservation>()

        val exact = roomSource(
            limits = testLimits(glucoseRows = 3, totalRows = 100),
            derivedRows = 2,
            derivedProbe = AlertAiDatasetDerivedProbe(observed::add)
        ).load(NOW)

        assertThat(exact.calibratedGlucose).hasSize(2)
        assertThat(observed.single {
            it.source == AlertAiDatasetDerivedSourceName.CALIBRATED_GLUCOSE_COPY
        }.reservedRows).isEqualTo(2)
        db.glucoseDao().upsertAll(listOf(glucose("derived-third", NOW - 30_000L)))
        assertThrows(AlertAiContextException.LimitExceeded::class.java) {
            runBlocking {
                roomSource(
                    limits = testLimits(glucoseRows = 3, totalRows = 100),
                    derivedRows = 2
                ).load(NOW)
            }
        }
        Unit
    }

    @Test
    fun daoReadBudgetChargesAuthorityAndAcceptedTupleLookupRows() = runBlocking {
        val cycleId = "bounded-accepted-cycle"
        val markerTs = NOW - MINUTE_MS
        val generationTs = markerTs - MINUTE_MS
        db.glucoseDao().upsertAll(listOf(glucose("authority-glucose", markerTs)))
        db.telemetryDao().upsertAll(
            listOf(
                TelemetrySampleEntity(
                    id = "bounded-authority-token",
                    timestamp = markerTs,
                    source = CALIBRATION_AUTHORITY_SOURCE,
                    key = CALIBRATION_AUTHORITY_TOKEN_KEY,
                    valueDouble = null,
                    valueText = "bounded-test-token",
                    unit = null,
                    quality = "OK"
                ),
                TelemetrySampleEntity(
                    id = "bounded-accepted-calibration",
                    timestamp = markerTs,
                    source = ACCEPTED_CALIBRATION_SOURCE,
                    key = ACCEPTED_CALIBRATION_KEYS.first(),
                    valueDouble = null,
                    valueText = "bounded-calibration-value",
                    unit = null,
                    quality = "OK"
                ),
                TelemetrySampleEntity(
                    id = "bounded-calibration-evidence",
                    timestamp = markerTs,
                    source = "test_sensor",
                    key = CalibrationModelAuthority.SESSION_EVIDENCE_KEYS.first(),
                    valueDouble = 1.0,
                    valueText = null,
                    unit = "h",
                    quality = "OK"
                ),
                acceptedMarker(cycleId, markerTs, SENSITIVITY_ACCEPTED_CYCLE_ID_KEY, valueText = cycleId),
                acceptedMarker(
                    cycleId,
                    markerTs,
                    SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY,
                    valueDouble = 7.0
                ),
                acceptedMarker(
                    cycleId,
                    markerTs,
                    SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY,
                    valueDouble = generationTs.toDouble()
                ),
                acceptedMarker(
                    cycleId,
                    markerTs,
                    SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY,
                    valueText = SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED
                )
            )
        )
        db.glucoseCalibrationModelDao().upsert(
            GlucoseCalibrationModelEntity(
                id = "bounded-active-model",
                sensorSessionKey = "bounded-session",
                createdAt = markerTs,
                validFromTs = markerTs - DAY_MS,
                validToTs = NOW + DAY_MS,
                modelType = "OFFSET",
                gain = 1.0,
                offsetMmol = 0.2,
                confidence = 0.8,
                checkCount = 2,
                sensorAgeHours = 1.0,
                lagMinutesAtFit = 0.0,
                status = "ACTIVE",
                diagnosticsJson = "{}"
            )
        )
        db.sensitivityRuntimeSnapshotDao().upsert(snapshot(cycleId, markerTs))
        db.forecastDao().insertAll(
            listOf(
                ForecastEntity(
                    timestamp = generationTs + 5L * MINUTE_MS,
                    horizonMinutes = 5,
                    valueMmol = 5.8,
                    ciLow = 5.3,
                    ciHigh = 6.3,
                    modelVersion = "bounded-test"
                )
            )
        )
        val observed = mutableListOf<AlertAiDatasetReadObservation>()

        roomSource(
            limits = testLimits(totalRows = 100),
            probe = AlertAiDatasetReadProbe(observed::add),
            sensitivityIdentity = SensitivityRuntimeSettingsIdentity(
                revision = 7L,
                isfSource = SensitivitySourcePreference.COPILOT,
                crSource = SensitivitySourcePreference.COPILOT
            )
        ).load(NOW)

        assertThat(observed.associate { it.source to it.queriedRows }).containsAtLeast(
            AlertAiDatasetSourceName.CALIBRATION_CAUSAL_GLUCOSE, 1,
            AlertAiDatasetSourceName.CALIBRATION_EVIDENCE, 1,
            AlertAiDatasetSourceName.CALIBRATION_ACTIVE_MODEL, 1,
            AlertAiDatasetSourceName.CALIBRATION_AUTHORITY_TOKEN, 1,
            AlertAiDatasetSourceName.SENSITIVITY_MARKERS, 1,
            AlertAiDatasetSourceName.SENSITIVITY_MARKER_DETAIL, 4,
            AlertAiDatasetSourceName.SENSITIVITY_RUNTIME_SNAPSHOT, 1,
            AlertAiDatasetSourceName.SENSITIVITY_FORECASTS, 1,
            AlertAiDatasetSourceName.SENSITIVITY_CALIBRATION_MARKERS, 1
        )
        Unit
    }

    @Test
    fun oversizedRoomDatasetTerminalizesOwnedClaimAndNeverCallsGateway() = runBlocking {
        val episodeId = "oversized-room-dataset"
        db.glucoseDao().upsertAll(
            (0..2).map { index -> glucose("claim-overflow-$index", NOW - index * MINUTE_MS) }
        )
        db.alertEventDao().insert(alertEvent(episodeId))
        val observed = mutableListOf<AlertAiDatasetReadObservation>()
        val builder = ClinicalReportDatasetBuilder(
            source = emptyReportSource(),
            resolveCalibratedGlucose = { raw, _ ->
                raw.map { point ->
                    io.aaps.copilot.domain.model.ResolvedGlucosePoint(
                        ts = point.ts,
                        rawMmol = point.valueMmol,
                        calibratedMmol = point.valueMmol,
                        gain = 1.0,
                        offsetMmolApplied = 0.0,
                        calibrationApplied = false,
                        source = point.source,
                        quality = point.quality
                    )
                }
            },
            alertAiSource = roomSource(
                limits = testLimits(glucoseRows = 2, totalRows = 20),
                probe = AlertAiDatasetReadProbe(observed::add)
            )
        )
        val gatewayCalls = AtomicInteger()
        val coordinator = coordinator(builder, gatewayCalls)

        val outcome = coordinator.run(trigger(episodeId))

        assertThat(outcome).isEqualTo(AlertAiAnalysisOutcome.CONTEXT_UNAVAILABLE)
        assertThat(gatewayCalls.get()).isEqualTo(0)
        val glucoseRead = observed.single { it.source == AlertAiDatasetSourceName.GLUCOSE }
        assertThat(glucoseRead.queriedRows).isEqualTo(3)
        assertThat(glucoseRead.queryLimit).isEqualTo(2)
        assertThat(observed).hasSize(1)
        val claims = db.alertAiAnalysisDao().byStatus(AlertAiAnalysisStatus.FAILED.name)
        assertThat(claims).hasSize(1)
        assertThat(claims.single().episodeId).isEqualTo(episodeId)
        assertThat(claims.single().sanitizedError)
            .isEqualTo(AlertAiAnalysisErrorCode.CONTEXT_UNAVAILABLE.name)
    }

    @Test
    fun derivedOverflowTerminalizesOwnedClaimAndNeverCallsGateway() = runBlocking {
        val episodeId = "derived-room-dataset-overflow"
        db.glucoseDao().upsertAll(
            listOf(
                glucose("derived-claim-first", NOW - 2L * MINUTE_MS),
                glucose("derived-claim-second", NOW - MINUTE_MS)
            )
        )
        db.alertEventDao().insert(alertEvent(episodeId))
        val builder = ClinicalReportDatasetBuilder(
            source = emptyReportSource(),
            resolveCalibratedGlucose = { raw, _ ->
                raw.map { point ->
                    io.aaps.copilot.domain.model.ResolvedGlucosePoint(
                        ts = point.ts,
                        rawMmol = point.valueMmol,
                        calibratedMmol = point.valueMmol,
                        gain = 1.0,
                        offsetMmolApplied = 0.0,
                        calibrationApplied = false,
                        source = point.source,
                        quality = point.quality
                    )
                }
            },
            alertAiSource = roomSource(
                limits = testLimits(glucoseRows = 2, totalRows = 100),
                derivedRows = 1
            )
        )
        val gatewayCalls = AtomicInteger()

        val outcome = coordinator(builder, gatewayCalls).run(trigger(episodeId))

        assertThat(outcome).isEqualTo(AlertAiAnalysisOutcome.CONTEXT_UNAVAILABLE)
        assertThat(gatewayCalls.get()).isEqualTo(0)
        val claims = db.alertAiAnalysisDao().byStatus(AlertAiAnalysisStatus.FAILED.name)
        assertThat(claims).hasSize(1)
        assertThat(claims.single().episodeId).isEqualTo(episodeId)
        assertThat(claims.single().sanitizedError)
            .isEqualTo(AlertAiAnalysisErrorCode.CONTEXT_UNAVAILABLE.name)
    }

    @Test
    fun contextMaterializationOverflowTerminalizesOwnedRoomClaimWithoutGateway() = runBlocking {
        val episodeId = "context-materialization-overflow"
        db.alertEventDao().insert(alertEvent(episodeId))
        val derivedObservations = mutableListOf<AlertAiDatasetDerivedObservation>()
        val builder = ClinicalReportDatasetBuilder(
            source = emptyReportSource(),
            resolveCalibratedGlucose = { _, _ -> emptyList() },
            alertAiSource = roomSource(
                limits = testLimits(totalRows = 100),
                derivedRows = 24,
                derivedProbe = AlertAiDatasetDerivedProbe(derivedObservations::add)
            )
        )
        val gatewayCalls = AtomicInteger()

        val outcome = coordinator(builder, gatewayCalls).run(trigger(episodeId))

        assertThat(outcome).isEqualTo(AlertAiAnalysisOutcome.CONTEXT_UNAVAILABLE)
        assertThat(gatewayCalls.get()).isEqualTo(0)
        assertThat(derivedObservations.any {
            it.source == AlertAiDatasetDerivedSourceName.ALERT_CONTEXT_DAILY_AGGREGATE
        }).isTrue()
        val claim = db.alertAiAnalysisDao().byEpisodeId(episodeId)
        assertThat(claim?.status).isEqualTo(AlertAiAnalysisStatus.FAILED.name)
        assertThat(claim?.sanitizedError)
            .isEqualTo(AlertAiAnalysisErrorCode.CONTEXT_UNAVAILABLE.name)
        assertThat(db.alertAiAnalysisDao().byStatus(AlertAiAnalysisStatus.FAILED.name)).hasSize(1)
    }

    @Test
    fun roomRetainedWorkLedgerReachesFinalProductionContext() = runBlocking {
        val observations = mutableListOf<AlertAiDatasetDerivedObservation>()
        val builder = ClinicalReportDatasetBuilder(
            source = emptyReportSource(),
            resolveCalibratedGlucose = { _, _ -> emptyList() },
            alertAiSource = roomSource(
                limits = testLimits(totalRows = 100),
                derivedProbe = AlertAiDatasetDerivedProbe(observations::add)
            )
        )

        val context = AlertAiProductionContextSource(
            AlertAiReportDatasetSource(builder::buildAlertAiDataset)
        ).build(trigger("room-ledger-success"))

        assertThat(AlertAiContextValidator.isValid(context, trigger("room-ledger-success"))).isTrue()
        assertThat(observations.last().source)
            .isEqualTo(AlertAiDatasetDerivedSourceName.ALERT_CANONICAL_CONTEXT)
        assertThat(observations.any {
            it.source == AlertAiDatasetDerivedSourceName.ALERT_CONTEXT_DATASET
        }).isTrue()
        assertThat(observations.count {
            it.source == AlertAiDatasetDerivedSourceName.ALERT_CONTEXT_DAILY_AGGREGATE
        }).isEqualTo(14)
    }

    @Test
    fun contextByteOverflowTerminalizesOwnedRoomClaimWithoutGateway() = runBlocking {
        val episodeId = "context-byte-overflow"
        db.alertEventDao().insert(alertEvent(episodeId))
        val builder = ClinicalReportDatasetBuilder(
            source = emptyReportSource(),
            resolveCalibratedGlucose = { _, _ -> emptyList() },
            alertAiSource = roomSource(limits = testLimits(totalRows = 100))
        )
        val gatewayCalls = AtomicInteger()

        val outcome = coordinator(
            builder = builder,
            gatewayCalls = gatewayCalls,
            contextBuilder = AlertAiContextBuilder(maxBytes = 256)
        ).run(trigger(episodeId))

        assertThat(outcome).isEqualTo(AlertAiAnalysisOutcome.CONTEXT_UNAVAILABLE)
        assertThat(gatewayCalls.get()).isEqualTo(0)
        val claim = db.alertAiAnalysisDao().byEpisodeId(episodeId)
        assertThat(claim?.status).isEqualTo(AlertAiAnalysisStatus.FAILED.name)
        assertThat(claim?.sanitizedError)
            .isEqualTo(AlertAiAnalysisErrorCode.CONTEXT_UNAVAILABLE.name)
        assertThat(db.alertAiAnalysisDao().byStatus(AlertAiAnalysisStatus.FAILED.name)).hasSize(1)
    }

    private fun roomSource(
        limits: AlertAiDatasetReadLimits,
        probe: AlertAiDatasetReadProbe = AlertAiDatasetReadProbe.NONE,
        sensitivityIdentity: SensitivityRuntimeSettingsIdentity? = null,
        derivedRows: Int = 12_000,
        derivedProbe: AlertAiDatasetDerivedProbe = AlertAiDatasetDerivedProbe.NONE
    ) = RoomAlertAiDatasetSource(
        db = db,
        gson = Gson(),
        glucoseCalibrationRepository = calibrationRepository(),
        sensitivitySettingsIdentity = sensitivityIdentity?.let { identity -> { identity } },
        limits = limits,
        probe = probe,
        derivedRowLimit = derivedRows,
        derivedProbe = derivedProbe
    )

    private fun calibrationRepository() = GlucoseCalibrationRepository(
        db = db,
        gson = Gson(),
        auditLogger = AuditLogger(db.auditLogDao(), Gson()) { NOW }
    )

    private fun coordinator(
        builder: ClinicalReportDatasetBuilder,
        gatewayCalls: AtomicInteger,
        contextBuilder: AlertAiContextBuilder = AlertAiContextBuilder()
    ): AlertAiAnalysisCoordinator {
        val config = ClinicalAiProviderConfig.defaultOpenAi()
        return AlertAiAnalysisCoordinator(
            settingsSource = AlertAiAnalysisSettingsSource {
                AlertAiAnalysisSettings(enabled = true, config = config)
            },
            credentialSource = AlertAiCredentialSource { "synthetic-credential" },
            networkGate = AlertAiNetworkGate { alertAiTestNetworkLease(515L) },
            contextSource = AlertAiProductionContextSource(
                AlertAiReportDatasetSource(builder::buildAlertAiDataset),
                contextBuilder
            ),
            dao = db.alertAiAnalysisDao(),
            gatewayFactory = ClinicalAiGatewayFactory(
                mapOf(
                    ClinicalAiProviderId.OPENAI to ClinicalAiGatewayBuilder { selected ->
                        object : ClinicalAiGateway {
                            override val providerId = selected.providerId
                            override val modelId = selected.modelId

                            override fun capabilities() =
                                ClinicalAiCapabilities(262_144, 65_536, 1_024, true)

                            override suspend fun testConnection(credential: String) =
                                throw UnsupportedOperationException()

                            override suspend fun analyze(
                                payload: ClinicalReportPayload,
                                credential: suspend () -> String,
                                progress: ClinicalOpenAiProgressCallback
                            ) = throw UnsupportedOperationException()

                            override suspend fun analyzeAlert(
                                context: AlertAiCanonicalContext,
                                credential: suspend () -> String,
                                networkLease: AlertAiValidatedNetworkLease
                            ): AlertAiGatewayResult {
                                gatewayCalls.incrementAndGet()
                                error("gateway must not run")
                            }
                        }
                    }
                )
            ),
            analysisIdFactory = { "analysis-$it" },
            clock = { NOW + 1_000L }
        )
    }

    private fun alertEvent(episodeId: String) = AlertEventEntity(
        episodeId = episodeId,
        eventType = "GLUCOSE_ALERT_LOW",
        stage = STAGE,
        status = "OPEN",
        severity = "WARNING_30",
        createdAt = NOW,
        updatedAt = NOW,
        resolvedAt = null,
        localSnapshotJson = AlertCauseSnapshotCodec.failureSnapshot().canonicalJson,
        causeCode = "DATA_INCOMPLETE",
        causeSummary = null,
        suppressionUntil = null,
        lastNotificationAt = null,
        revision = 1L
    )

    private fun trigger(episodeId: String) = AlertAiAnalysisTrigger(
        episodeId = episodeId,
        requestedAt = NOW,
        stage = STAGE,
        direction = AlertCauseDirection.LOW,
        localCauseSnapshot = AlertCauseSnapshotCodec.failureSnapshot()
    )

    private fun glucose(id: String, ts: Long) = GlucoseSampleEntity(
        id = id.hashCode().toLong(),
        timestamp = ts,
        mmol = 5.5,
        source = "nightscout",
        quality = "OK"
    )

    private fun recurringActivity(id: String) = PlannedActivityEventEntity(
        eventId = id,
        enabled = true,
        title = "Recurring walk",
        activityType = "WALKING",
        intensity = "MEDIUM",
        localStartIso = "2020-01-06T11:30:00",
        durationMinutes = 90,
        timezoneId = "UTC",
        recurrenceDaysMask = MONDAY_MASK,
        recurrenceEndEpochDay = null,
        revision = 1L,
        createdAtMs = 1L,
        updatedAtMs = 1L
    )

    private fun acceptedMarker(
        cycleId: String,
        timestamp: Long,
        key: String,
        valueDouble: Double? = null,
        valueText: String? = null
    ) = TelemetrySampleEntity(
        id = "$cycleId:$key",
        timestamp = timestamp,
        source = SENSITIVITY_ACCEPTED_SOURCE,
        key = key,
        valueDouble = valueDouble,
        valueText = valueText,
        unit = null,
        quality = "OK"
    )

    private fun snapshot(cycleId: String, generatedAt: Long) = SensitivityRuntimeSnapshotEntity(
        cycleId = cycleId,
        settingsRevision = 7L,
        generatedAt = generatedAt,
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

    private fun testLimits(
        glucoseRows: Int = 10,
        plannedActivityRows: Int = 10,
        totalRows: Int = 100
    ) = AlertAiDatasetReadLimits.testing(
        defaultPerSourceRows = 10,
        glucoseRows = glucoseRows,
        plannedActivityRows = plannedActivityRows,
        totalDaoRows = totalRows
    )

    private fun emptyReportSource() = object : ClinicalReportDataSource {
        override suspend fun glucose(fromTs: Long, toTs: Long) = emptyList<io.aaps.copilot.data.local.dao.ClinicalGlucoseProjection>()
        override suspend fun therapy(fromTs: Long, toTs: Long, types: List<String>) = emptyList<io.aaps.copilot.data.local.dao.ClinicalTherapyProjection>()
        override suspend fun forecasts(fromTs: Long, toTs: Long) = emptyList<io.aaps.copilot.data.local.dao.ClinicalForecastProjection>()
        override suspend fun telemetry(fromTs: Long, toTs: Long, keys: List<String>) = emptyList<io.aaps.copilot.data.local.dao.ClinicalTelemetryProjection>()
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24L * 60L * MINUTE_MS
        const val FOURTEEN_DAYS_MS = 14L * DAY_MS
        const val MONDAY_MASK = 1
        const val STAGE = "LOW_PREDICTED_30"
        val NOW: Long = Instant.parse("2030-01-07T12:00:00Z").toEpochMilli()
        val WINDOW_LOCAL_START: LocalDateTime = Instant.ofEpochMilli(NOW - FOURTEEN_DAYS_MS)
            .atZone(ZoneOffset.UTC)
            .toLocalDateTime()
    }
}
