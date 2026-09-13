package io.aaps.copilot.data.repository

import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonParser
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.config.SensorLagCorrectionMode
import io.aaps.copilot.config.sensitivityRuntimeFingerprint
import io.aaps.copilot.config.sensitivityRuntimeIdentity
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.IsfCrModelStateEntity
import io.aaps.copilot.data.local.entity.ProfileEstimateEntity
import io.aaps.copilot.data.local.entity.SyncStateEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.model.ActionProposal
import io.aaps.copilot.domain.predict.PredictionEngine
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.RuleDecision
import io.aaps.copilot.domain.model.RuleState
import io.aaps.copilot.domain.predict.AcceptedSensitivityTupleFreshness
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_SOURCE
import io.aaps.copilot.domain.predict.SensitivityCandidate
import io.aaps.copilot.domain.predict.SensitivityCandidates
import io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer
import io.aaps.copilot.domain.predict.SensitivitySourcePreference
import io.aaps.copilot.domain.rules.AdaptiveTargetControllerRule
import io.aaps.copilot.domain.rules.RuleEngine
import io.aaps.copilot.domain.rules.RuleContext
import io.aaps.copilot.domain.rules.TargetRule
import io.aaps.copilot.domain.safety.SafetyPolicy
import io.aaps.copilot.domain.target.TargetDecisionOutcome
import io.aaps.copilot.domain.target.TargetManagerMode
import io.aaps.copilot.service.ApiFactory
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AutomationRepositorySourceChangeRoomIntegrationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: CopilotDatabase
    private lateinit var settingsStore: AppSettingsStore
    private lateinit var alertStateStore: GlucoseAlertStateStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var server: MockWebServer
    private val targetDispatches = AtomicInteger()
    private val uamGatewayCalls = AtomicInteger()
    private val widgetRefreshes = AtomicInteger()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settingsFile = File(temporaryFolder.newFolder(), "settings.preferences_pb")
        settingsStore = AppSettingsStore(
            PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { settingsFile }
            ),
            "source-change-integration-install"
        )
        alertStateStore = GlucoseAlertStateStore(context)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dataStoreScope.cancel()
        db.close()
    }

    @Test
    fun activeLagSourceChangePublishesOneExactSafeReadOnlyClinicalRevision() = runBlocking {
        val now = System.currentTimeMillis()
        seedRisingGlucose(now)
        seedLocalSafetyEvidence(now)
        db.actionCommandDao().upsert(
            ActionCommandEntity(
                id = "future-automatic-action",
                timestamp = now + 10 * MINUTE_MS,
                type = "temp_target",
                payloadJson = """{"targetMmol":"6.0"}""",
                safetyJson = "{}",
                idempotencyKey = "TargetManager.v1:future-cadence",
                status = NightscoutActionRepository.STATUS_SENT
            )
        )
        configureActiveSourceChange()
        alertStateStore.update {
            GlucoseAlertRuntimeState(
                pendingRiskKey = "SOFT_HIGH_RISK:HIGH:10.0",
                pendingRiskCount = 1
            )
        }
        val repository = repository(now)
        val actionsBefore = db.actionCommandDao().latest(100)

        val acceptedSnapshot = repository.applySensitivitySettings { current ->
            current.copy(
                isfSourcePreference = SensitivitySourcePreference.COPILOT,
                crSourcePreference = SensitivitySourcePreference.COPILOT
            )
        }

        val acceptedAt = requireNotNull(
            db.telemetryDao().latestTimestampBySourceAndKey(
                source = io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_SOURCE,
                key = io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
            )
        )
        val roomTuple = requireNotNull(
            AcceptedSensitivityTupleRoomLoader(db).loadExact(
                currentSettings = settingsStore.settings.first().sensitivityRuntimeIdentity(),
                acceptedAtTs = acceptedAt,
                atTs = System.currentTimeMillis()
            )
        )
        val authority = AutomationRepository.requireAcceptedClinicalForecastsStatic(roomTuple)
        val targetDiagnostic = db.targetManagerDao().latestDecisions(10).single()
        val ruleRows = db.ruleExecutionDao().observeLatest(20).first()
        val forecastState = db.forecastDao().observeLatest(3).first().sortedBy { it.horizonMinutes }
        val uamRows = db.telemetryDao().atTimestampBySourceAndKeys(
            source = "copilot_uam_runtime",
            timestamp = acceptedAt,
            keys = listOf(
                "uam_runtime_sensitivity_cycle_id",
                "uam_runtime_sensitivity_settings_revision"
            ) + acceptedAuthorityKeys("uam_runtime_accepted_forecast")
        ).associateBy { it.key }
        val overviewAcceptedRows = acceptedAuthorityRows(
            source = AutomationRepository.ACCEPTED_RUNTIME_TELEMETRY_SOURCE,
            timestamp = acceptedAt,
            keyPrefix = "overview_accepted_forecast"
        )
        val overviewForecastRows = db.telemetryDao().atTimestampBySourceAndKeys(
            source = "copilot_sensor_lag",
            timestamp = acceptedAt,
            keys = listOf(
                "sensor_lag_control_forecast_5m",
                "sensor_lag_control_forecast_30m",
                "sensor_lag_control_forecast_60m"
            )
        ).associateBy { it.key }
        val targetInputRows = acceptedAuthorityRows(
            source = AutomationRepository.TARGET_MANAGER_DELIVERY_TRUST_SOURCE,
            timestamp = acceptedAt,
            keyPrefix = "target_manager_accepted_forecast"
        )
        val alertCauseRows = db.telemetryDao().atTimestampBySourceAndKeys(
            source = AutomationRepository.ALERT_CAUSE_DIAGNOSTIC_SOURCE,
            timestamp = acceptedAt,
            keys = listOf(
                "alert_cause_identity_status",
                "alert_cause_sensitivity_cycle_id",
                "alert_cause_sensitivity_settings_revision",
                "alert_cause_input_snapshot"
            ) + acceptedAuthorityKeys("alert_cause_accepted_forecast")
        ).associateBy { it.key }

        assertThat(roomTuple.snapshot).isEqualTo(acceptedSnapshot)
        assertThat(authority.forecasts.map { it.horizonMinutes }).containsExactly(5, 30, 60).inOrder()
        assertThat(authority.forecasts.map { it.modelVersion })
            .containsExactly("fixture|sensor_lag_v2", "fixture|sensor_lag_v2", "fixture|sensor_lag_v2")
            .inOrder()
        assertThat(authority.forecasts.map { it.valueMmol })
            .isNotEqualTo(fixtureForecasts(roomTuple.accepted.generationTimestamp!!).map { it.valueMmol })
        assertThat(roomTuple.accepted.forecastDigest).isEqualTo(authority.digest)
        assertThat(roomTuple.accepted.forecastsByHorizon.values.sortedBy { it.horizonMinutes }.map { row ->
            listOf(row.timestamp, row.valueMmol, row.ciLow, row.ciHigh, row.modelVersion)
        }).containsExactlyElementsIn(authority.forecasts.map { forecast ->
            listOf(forecast.ts, forecast.valueMmol, forecast.ciLow, forecast.ciHigh, forecast.modelVersion)
        }).inOrder()
        assertThat(forecastState.map { row ->
            listOf(
                row.horizonMinutes,
                row.timestamp,
                row.valueMmol,
                row.ciLow,
                row.ciHigh,
                row.modelVersion
            )
        }).containsExactlyElementsIn(authority.forecasts.map { forecast ->
            listOf(
                forecast.horizonMinutes,
                forecast.ts,
                forecast.valueMmol,
                forecast.ciLow,
                forecast.ciHigh,
                forecast.modelVersion
            )
        }).inOrder()
        assertAcceptedAuthorityRows(
            rows = overviewAcceptedRows,
            keyPrefix = "overview_accepted_forecast",
            authority = authority
        )
        authority.forecasts.forEach { forecast ->
            assertThat(
                overviewForecastRows.getValue(
                    "sensor_lag_control_forecast_${forecast.horizonMinutes}m"
                ).valueDouble
            ).isEqualTo(forecast.valueMmol)
        }
        assertThat(uamRows.getValue("uam_runtime_sensitivity_cycle_id").valueText)
            .isEqualTo(acceptedSnapshot.forecastCycleId)
        assertThat(uamRows.getValue("uam_runtime_sensitivity_settings_revision").valueDouble)
            .isEqualTo(acceptedSnapshot.settingsRevision.toDouble())
        assertAcceptedAuthorityRows(
            rows = uamRows,
            keyPrefix = "uam_runtime_accepted_forecast",
            authority = authority
        )
        assertThat(targetDiagnostic.deliveryStatus).isEqualTo("read_only")
        assertThat(targetDiagnostic.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_MANUAL_TARGET.name)
        assertThat(targetDiagnostic.reasonCodesJson).contains("manual_or_foreign_target_active")
        assertThat(targetDiagnostic.lastSentTimestamp).isEqualTo(now - MINUTE_MS)
        assertThat(targetDiagnostic.lastSentTargetMmol).isEqualTo(5.8)
        assertAcceptedAuthorityRows(
            rows = targetInputRows,
            keyPrefix = "target_manager_accepted_forecast",
            authority = authority
        )
        assertThat(alertCauseRows.getValue("alert_cause_identity_status").valueText).isEqualTo("MATCHED")
        assertThat(alertCauseRows.getValue("alert_cause_sensitivity_cycle_id").valueText)
            .isEqualTo(acceptedSnapshot.forecastCycleId)
        assertThat(alertCauseRows.getValue("alert_cause_sensitivity_settings_revision").valueDouble)
            .isEqualTo(acceptedSnapshot.settingsRevision.toDouble())
        assertAcceptedAuthorityRows(
            rows = alertCauseRows,
            keyPrefix = "alert_cause_accepted_forecast",
            authority = authority
        )
        val alertInput = JsonParser.parseString(
            alertCauseRows.getValue("alert_cause_input_snapshot").valueText
        ).asJsonObject.getAsJsonObject("glucose")
        authority.forecasts.forEach { forecast ->
            assertThat(alertInput.get("forecast${forecast.horizonMinutes}Mmol").asDouble)
                .isEqualTo(forecast.valueMmol)
            assertThat(alertInput.get("lowerCi${forecast.horizonMinutes}Mmol").asDouble)
                .isEqualTo(forecast.ciLow)
        }
        assertThat(ruleRows).isNotEmpty()
        assertThat(ruleRows.map { it.state }.distinct())
            .containsExactly("DIAGNOSTIC_BLOCKED")
        assertThat(
            db.ruleExecutionDao().findByStateSince(
                ruleId = AdaptiveTargetControllerRule.RULE_ID,
                state = RuleState.TRIGGERED.name,
                since = 0L
            )
        ).isEmpty()
        assertThat(ruleRows.flatMap { Gson().fromJson(it.reasonsJson, Array<String>::class.java).toList() })
            .contains("rate_limit_6h")
        assertThat(
            db.telemetryDao().atTimestampBySourceAndKeys(
                source = "copilot_target_safety",
                timestamp = acceptedAt,
                keys = listOf(
                    "target_low_risk_active",
                    "target_low_risk_latched",
                    "target_low_risk_safe_cycles",
                    "target_low_risk_protected_target_mmol"
                )
            )
        ).isEmpty()
        assertThat(db.actionCommandDao().latest(100)).containsExactlyElementsIn(actionsBefore)
        assertThat(targetDispatches.get()).isEqualTo(0)
        assertThat(uamGatewayCalls.get()).isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(db.alertEventDao().maxGlucoseEpisodeCreatedAt()).isEqualTo(0L)
        assertThat(tableCount("alert_delivery_receipts")).isEqualTo(0)
        assertThat(tableCount("alert_ai_analyses")).isEqualTo(0)
        assertThat(context.getSystemService(NotificationManager::class.java).activeNotifications).isEmpty()
        assertThat(widgetRefreshes.get()).isEqualTo(1)
    }

    @Test
    fun sourceChangeThenNormalMatchesControlTriggeredDecisionWithoutDiagnosticCooldown() = runBlocking {
        val now = System.currentTimeMillis()
        seedRisingGlucose(now)
        seedLocalSafetyEvidence(now, recentActionCount = 0)
        configureActiveSourceChange()
        settingsStore.update { current ->
            current.copy(
                nightscoutUrl = "",
                apiSecret = "",
                enableUamExportToAaps = false
            )
        }
        val repository = repository(now)

        repository.runAutomationCycle()
        val control = db.ruleExecutionDao().findByStateSince(
            ruleId = AdaptiveTargetControllerRule.RULE_ID,
            state = RuleState.TRIGGERED.name,
            since = 0L
        ).single()
        db.openHelper.writableDatabase.execSQL("DELETE FROM rule_executions")

        repository.applySensitivitySettings { current ->
            current.copy(
                isfSourcePreference = SensitivitySourcePreference.COPILOT,
                crSourcePreference = SensitivitySourcePreference.COPILOT
            )
        }
        assertThat(
            db.ruleExecutionDao().findByStateSince(
                ruleId = AdaptiveTargetControllerRule.RULE_ID,
                state = RuleState.TRIGGERED.name,
                since = 0L
            )
        ).isEmpty()

        repository.runAutomationCycle()

        val normalAfterDiagnostic = db.ruleExecutionDao().findByStateSince(
            ruleId = AdaptiveTargetControllerRule.RULE_ID,
            state = RuleState.TRIGGERED.name,
            since = 0L
        ).single()
        assertThat(normalAfterDiagnostic.reasonsJson).isEqualTo(control.reasonsJson)
        assertThat(normalAfterDiagnostic.actionJson).isEqualTo(control.actionJson)
    }

    @Test
    fun localReadOnlyThenNormalMatchesControlTriggeredDecisionWithoutDiagnosticCooldown() = runBlocking {
        val now = System.currentTimeMillis()
        seedRisingGlucose(now)
        seedLocalSafetyEvidence(now, recentActionCount = 0)
        configureActiveSourceChange()
        settingsStore.update { current ->
            current.copy(
                nightscoutUrl = "",
                apiSecret = "",
                enableUamExportToAaps = false
            )
        }
        val repository = repository(now)

        repository.runAutomationCycle()
        val control = db.ruleExecutionDao().findByStateSince(
            ruleId = AdaptiveTargetControllerRule.RULE_ID,
            state = RuleState.TRIGGERED.name,
            since = 0L
        ).single()
        db.openHelper.writableDatabase.execSQL("DELETE FROM rule_executions")

        repository.runLocalReadOnlyCycle()
        val diagnosticRows = db.ruleExecutionDao().observeLatest(20).first()
        assertThat(diagnosticRows).isNotEmpty()
        assertThat(diagnosticRows.map { it.state }.distinct())
            .containsExactly("DIAGNOSTIC_BLOCKED")
        assertThat(
            db.ruleExecutionDao().findByStateSince(
                ruleId = AdaptiveTargetControllerRule.RULE_ID,
                state = RuleState.TRIGGERED.name,
                since = 0L
            )
        ).isEmpty()

        repository.runAutomationCycle()

        val normalAfterDiagnostic = db.ruleExecutionDao().findByStateSince(
            ruleId = AdaptiveTargetControllerRule.RULE_ID,
            state = RuleState.TRIGGERED.name,
            since = 0L
        ).single()
        assertThat(normalAfterDiagnostic.reasonsJson).isEqualTo(control.reasonsJson)
        assertThat(normalAfterDiagnostic.actionJson).isEqualTo(control.actionJson)
    }

    @Test
    fun sourceChangeRebuildsLegacyProfileBeforePublishingAcceptedSnapshot() = runBlocking {
        val now = System.currentTimeMillis()
        seedRisingGlucose(now, seedSensitivityGeneration = false)
        seedLocalSafetyEvidence(now)
        db.profileEstimateDao().upsert(
            ProfileEstimateEntity(
                timestamp = now - 60 * MINUTE_MS,
                isfMmolPerUnit = 6.91,
                crGramPerUnit = 10.0,
                confidence = 0.55,
                sampleCount = 450,
                isfSampleCount = 450,
                crSampleCount = 0,
                lookbackDays = 90,
                telemetryIsfSampleCount = 0,
                telemetryCrSampleCount = 0,
                uamObservedCount = 0,
                uamFilteredIsfSamples = 0,
                uamEpisodeCount = 0,
                uamEstimatedCarbsGrams = 0.0,
                uamEstimatedRecentCarbsGrams = 0.0,
                calculatedIsfMmolPerUnit = 6.91,
                calculatedCrGramPerUnit = null,
                calculatedConfidence = 0.55,
                calculatedSampleCount = 450,
                calculatedIsfSampleCount = 450,
                calculatedCrSampleCount = 0
            )
        )
        db.telemetryDao().upsertAll(
            listOf(
                TelemetrySampleEntity(
                    id = "aaps-isf-for-profile-revision",
                    timestamp = now,
                    source = "aaps_broadcast",
                    key = "isf_value",
                    valueDouble = 2.03,
                    valueText = null,
                    unit = "mmol/L/U",
                    quality = "OK"
                ),
                TelemetrySampleEntity(
                    id = "aaps-cr-for-profile-revision",
                    timestamp = now,
                    source = "aaps_broadcast",
                    key = "cr_value",
                    valueDouble = 10.0,
                    valueText = null,
                    unit = "g/U",
                    quality = "OK"
                )
            )
        )
        configureActiveSourceChange()

        repository(now).applySensitivitySettings { current ->
            current.copy(
                isfSourcePreference = SensitivitySourcePreference.COPILOT,
                crSourcePreference = SensitivitySourcePreference.COPILOT
            )
        }

        assertThat(
            db.syncStateDao().bySource("profile_estimator_algorithm_revision")?.lastSyncedTimestamp
        ).isEqualTo(AnalyticsRepository.PROFILE_ESTIMATOR_ALGORITHM_REVISION)
        assertThat(requireNotNull(db.profileEstimateDao().active()).isfMmolPerUnit)
            .isWithin(0.001)
            .of(2.03)
    }

    @Test
    fun failedRevisionRebuildDoesNotCrashReadOnlyCycleOrMutateSourceSettings() = runBlocking {
        val now = System.currentTimeMillis()
        seedRisingGlucose(now, seedSensitivityGeneration = false)
        db.profileEstimateDao().upsert(
            ProfileEstimateEntity(
                timestamp = now - 60 * MINUTE_MS,
                isfMmolPerUnit = 6.91,
                crGramPerUnit = 10.0,
                confidence = 0.55,
                sampleCount = 1,
                isfSampleCount = 1,
                crSampleCount = 0,
                lookbackDays = 90,
                telemetryIsfSampleCount = 0,
                telemetryCrSampleCount = 0,
                uamObservedCount = 0,
                uamFilteredIsfSamples = 0,
                uamEpisodeCount = 0,
                uamEstimatedCarbsGrams = 0.0,
                uamEstimatedRecentCarbsGrams = 0.0,
                calculatedIsfMmolPerUnit = 6.91,
                calculatedCrGramPerUnit = null,
                calculatedConfidence = 0.55,
                calculatedSampleCount = 1,
                calculatedIsfSampleCount = 1,
                calculatedCrSampleCount = 0
            )
        )
        configureActiveSourceChange()
        val before = settingsStore.settings.first()
        val repository = repository(now)

        assertThat(repository.runLocalReadOnlyCycle()).isNull()
        val failure = runCatching {
            repository.applySensitivitySettings { current ->
                current.copy(isfSourcePreference = SensitivitySourcePreference.COPILOT)
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(settingsStore.settings.first().sensitivityRuntimeFingerprint())
            .isEqualTo(before.sensitivityRuntimeFingerprint())
        assertThat(
            db.syncStateDao().bySource("profile_estimator_algorithm_revision")
        ).isNull()
    }

    @Test
    fun unexpectedSourceChangeFailureRestoresTentativeSettings() = runBlocking {
        val now = System.currentTimeMillis()
        seedRisingGlucose(now)
        seedLocalSafetyEvidence(now)
        db.syncStateDao().upsert(
            io.aaps.copilot.data.local.entity.SyncStateEntity(
                source = "profile_estimator_algorithm_revision",
                lastSyncedTimestamp = AnalyticsRepository.PROFILE_ESTIMATOR_ALGORITHM_REVISION
            )
        )
        configureActiveSourceChange()
        val before = settingsStore.settings.first()
        val repository = repository(
            now = now,
            predictForecasts = { error("unexpected prediction failure") }
        )

        val failure = runCatching {
            repository.applySensitivitySettings { current ->
                current.copy(isfCrUseActivity = !current.isfCrUseActivity)
            }
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("unexpected prediction failure")
        assertThat(settingsStore.settings.first().sensitivityRuntimeFingerprint())
            .isEqualTo(before.sensitivityRuntimeFingerprint())
    }

    @Test
    fun postAcceptanceUiFailureKeepsAcceptedSourceSettings() = runBlocking {
        val now = System.currentTimeMillis()
        seedRisingGlucose(now)
        seedLocalSafetyEvidence(now)
        db.syncStateDao().upsert(
            io.aaps.copilot.data.local.entity.SyncStateEntity(
                source = "profile_estimator_algorithm_revision",
                lastSyncedTimestamp = AnalyticsRepository.PROFILE_ESTIMATOR_ALGORITHM_REVISION
            )
        )
        configureActiveSourceChange()
        val before = settingsStore.settings.first()
        val repository = repository(
            now = now,
            onWidgetRefresh = { error("widget publication failed") }
        )

        val failure = runCatching {
            repository.applySensitivitySettings { current ->
                current.copy(isfSourcePreference = SensitivitySourcePreference.COPILOT)
            }
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("widget publication failed")
        val after = settingsStore.settings.first()
        assertThat(after.sensitivitySettingsRevision).isGreaterThan(before.sensitivitySettingsRevision)
        assertThat(after.isfSourcePreference).isEqualTo(SensitivitySourcePreference.COPILOT)
        val accepted = requireNotNull(db.sensitivityRuntimeSnapshotDao().latest())
        assertThat(accepted.settingsRevision).isEqualTo(after.sensitivitySettingsRevision)
    }

    @Test
    fun quarantinedCausalRowsCannotHideForeignTargetFromRealSourceChangeOwnershipGate() = runBlocking {
        val now = System.currentTimeMillis()
        seedRisingGlucose(now)
        seedLocalSafetyEvidence(now)
        val quarantinedRows = (1..3).map { index ->
                TherapyEventEntity(
                    id = "crossed-quarantine-$index",
                    timestamp = now - index * MINUTE_MS,
                    type = "temp_target",
                    payloadJson = """{"targetBottom":6.0,"duration":30,"notes":"future artifact"}"""
                )
            }
        db.therapyDao().upsertAll(quarantinedRows)
        db.telemetryDao().upsertAll(
            quarantinedRows.map { row ->
                AutomationRepository.localFutureTargetMarkerStatic(row, now - 15 * MINUTE_MS)
            }
        )
        val firstDetection = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db = db,
            gson = Gson(),
            nowTs = now
        )
        assertThat(firstDetection.activeAapsTarget?.evidenceResolved).isTrue()
        assertThat(firstDetection.activeAapsTarget?.targetMmol).isEqualTo(7.2)
        configureActiveSourceChange()

        repository(now).applySensitivitySettings { current ->
            current.copy(
                isfSourcePreference = SensitivitySourcePreference.COPILOT,
                crSourcePreference = SensitivitySourcePreference.COPILOT
            )
        }

        val targetDiagnostic = db.targetManagerDao().latestDecisions(10).single()
        assertThat(targetDiagnostic.deliveryStatus).isEqualTo("read_only")
        assertThat(targetDiagnostic.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_MANUAL_TARGET.name)
        assertThat(targetDiagnostic.reasonCodesJson).contains("manual_or_foreign_target_active")
        assertThat(targetDispatches.get()).isEqualTo(0)
        assertThat(uamGatewayCalls.get()).isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun futureAutomaticRowCannotAffectRealSourceChangeActionCountOrCadence() = runBlocking {
        val now = System.currentTimeMillis()
        seedRisingGlucose(now)
        seedLocalSafetyEvidence(now, recentActionCount = 2)
        db.actionCommandDao().upsert(
            ActionCommandEntity(
                id = "future-source-change-action",
                timestamp = now + 10 * MINUTE_MS,
                type = "temp_target",
                payloadJson = """{"targetMmol":"6.0"}""",
                safetyJson = "{}",
                idempotencyKey = "TargetManager.v1:future-source-change",
                status = NightscoutActionRepository.STATUS_SENT
            )
        )
        configureActiveSourceChange()

        repository(now).applySensitivitySettings { current ->
            current.copy(
                isfSourcePreference = SensitivitySourcePreference.COPILOT,
                crSourcePreference = SensitivitySourcePreference.COPILOT
            )
        }

        val ruleReasons = db.ruleExecutionDao().observeLatest(20).first()
            .flatMap { Gson().fromJson(it.reasonsJson, Array<String>::class.java).toList() }
        val targetDiagnostic = db.targetManagerDao().latestDecisions(10).single()
        assertThat(ruleReasons).doesNotContain("rate_limit_6h")
        assertThat(targetDiagnostic.lastSentTimestamp).isEqualTo(now - MINUTE_MS)
        assertThat(targetDiagnostic.lastSentTargetMmol).isEqualTo(5.8)
        assertThat(targetDispatches.get()).isEqualTo(0)
        assertThat(uamGatewayCalls.get()).isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun realSourceChangeClockRollbackKeepsLocalChronologyAndOwnershipFailClosed() = runBlocking {
        val cycleNow = System.currentTimeMillis()
        val priorHighWater = cycleNow + 30 * MINUTE_MS
        seedRisingGlucose(cycleNow)
        seedLocalSafetyEvidence(cycleNow, recentActionCount = 2)
        val highWaterSentTs = priorHighWater - 10 * MINUTE_MS
        db.actionCommandDao().upsert(
            ActionCommandEntity(
                id = "legitimate-action-before-clock-rollback",
                timestamp = highWaterSentTs,
                type = "temp_target",
                payloadJson = """{"targetMmol":"5.9"}""",
                safetyJson = "{}",
                idempotencyKey = "TargetManager.v1:before-clock-rollback",
                status = NightscoutActionRepository.STATUS_SENT
            )
        )
        val atHighWater = AutomationRepository.loadLocalSafetyEvidenceStatic(
            db,
            Gson(),
            priorHighWater
        )
        assertThat(atHighWater.chronologyResolved).isTrue()
        configureActiveSourceChange()

        repository(cycleNow).applySensitivitySettings { current ->
            current.copy(
                isfSourcePreference = SensitivitySourcePreference.COPILOT,
                crSourcePreference = SensitivitySourcePreference.COPILOT
            )
        }

        val rolledBack = AutomationRepository.loadLocalSafetyEvidenceStatic(db, Gson(), cycleNow)
        val ruleReasons = db.ruleExecutionDao().observeLatest(20).first()
            .flatMap { Gson().fromJson(it.reasonsJson, Array<String>::class.java).toList() }
        val targetDiagnostic = db.targetManagerDao().latestDecisions(10).single()
        assertThat(rolledBack.chronologyResolved).isTrue()
        assertThat(rolledBack.causalThroughTs).isEqualTo(cycleNow)
        assertThat(rolledBack.latestAutomaticSent?.timestamp).isEqualTo(cycleNow - MINUTE_MS)
        assertThat(rolledBack.activeAapsTarget?.targetMmol).isEqualTo(7.2)
        assertThat(ruleReasons).contains("action_chronology_unresolved")
        assertThat(targetDiagnostic.deliveryStatus).isEqualTo("read_only")
        assertThat(targetDiagnostic.reasonCodesJson).contains("local_safety_chronology_unresolved")
        assertThat(targetDiagnostic.lastSentTimestamp).isEqualTo(highWaterSentTs)
        assertThat(targetDiagnostic.lastSentTargetMmol).isEqualTo(5.9)
        assertThat(targetDispatches.get()).isEqualTo(0)
        assertThat(uamGatewayCalls.get()).isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun staleNormalCycleIsBlockedBeforePersistenceAndFreshGlucoseRecovers() = runBlocking {
        val now = System.currentTimeMillis()
        val staleSampleTs = now - 129L * MINUTE_MS
        seedRisingGlucose(staleSampleTs)
        seedLocalSafetyEvidence(now)
        configureActiveSourceChange()
        settingsStore.update { current ->
            current.copy(nightscoutUrl = "", apiSecret = "", enableUamExportToAaps = false)
        }
        val repository = repository(now, anchorForecastsToGlucose = true)
        val actionsBefore = db.actionCommandDao().latest(100)
        db.forecastDao().insertAll(fixtureForecasts(staleSampleTs).map { forecast ->
            ForecastEntity(
                timestamp = forecast.ts, horizonMinutes = forecast.horizonMinutes,
                valueMmol = forecast.valueMmol, ciLow = forecast.ciLow, ciHigh = forecast.ciHigh,
                modelVersion = "prior-history"
            )
        })
        val forecastsBefore = db.forecastDao().atGenerationTimestamp(staleSampleTs)
        val authorityBefore = db.telemetryDao().currentBySourceAndKey(
            CALIBRATION_AUTHORITY_SOURCE, CALIBRATION_AUTHORITY_TOKEN_KEY
        )
        val modelsBefore = db.glucoseCalibrationModelDao().modelsSince(0L)

        assertThat(repository.runAutomationCycle()).isNull()

        assertNoAcceptedPersistenceOrEffects()
        assertThat(db.forecastDao().atGenerationTimestamp(staleSampleTs)).isEqualTo(forecastsBefore)
        assertThat(db.glucoseCalibrationModelDao().modelsSince(0L)).isEqualTo(modelsBefore)
        assertThat(db.telemetryDao().currentBySourceAndKey(
            CALIBRATION_AUTHORITY_SOURCE, CALIBRATION_AUTHORITY_TOKEN_KEY
        )).isEqualTo(authorityBefore)
        assertThat(db.actionCommandDao().latest(100)).containsExactlyElementsIn(actionsBefore)
        assertStaleBlockedAudit()

        seedRisingGlucose(System.currentTimeMillis())
        assertThat(repository.runAutomationCycle()).isNotNull()
        val freshMarkerTs = requireNotNull(db.telemetryDao().latestTimestampBySourceAndKey(
            SENSITIVITY_ACCEPTED_SOURCE, SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
        ))
        assertThat(AcceptedSensitivityTupleRoomLoader(db).loadExact(
            settingsStore.settings.first().sensitivityRuntimeIdentity(), freshMarkerTs, freshMarkerTs
        )).isNotNull()
        Unit
    }

    @Test
    fun staleCandidateGenerationIsBlockedEvenWithFreshInput() = runBlocking {
        val now = System.currentTimeMillis()
        seedRisingGlucose(now)
        seedLocalSafetyEvidence(now)
        val repository = repository(
            now, anchorForecastsToGlucose = true,
            forecastGenerationOffsetMs = -AcceptedSensitivityTupleFreshness.MAX_AGE_MS - MINUTE_MS
        )

        assertThat(repository.runAutomationCycle()).isNull()

        assertNoAcceptedPersistenceOrEffects()
        assertThat(db.forecastDao().latest(10)).isEmpty()
        assertStaleBlockedAudit()
        Unit
    }

    @Test
    fun generationExpiringDuringPredictionIsBlockedBeforeAnyAcceptedPersistence() = runBlocking {
        val now = System.currentTimeMillis()
        seedRisingGlucose(now)
        seedLocalSafetyEvidence(now)
        val repository = repository(now, predictForecasts = {
            forecastsAgingDuringPrediction(initialAgeMs = 899_000L)
        })
        val modelsBefore = db.glucoseCalibrationModelDao().modelsSince(0L)
        val actionsBefore = db.actionCommandDao().latest(100)

        assertThat(repository.runAutomationCycle()).isNull()

        assertNoAcceptedPersistenceOrEffects()
        assertThat(db.forecastDao().latest(10)).isEmpty()
        assertThat(db.glucoseCalibrationModelDao().modelsSince(0L)).isEqualTo(modelsBefore)
        assertThat(db.actionCommandDao().latest(100)).containsExactlyElementsIn(actionsBefore)
        assertStaleBlockedAudit()
        Unit
    }

    @Test
    fun sourceChangeGenerationExpiringDuringPredictionRestoresSettingsWithoutAcceptedEffects() = runBlocking {
        val now = System.currentTimeMillis()
        seedRisingGlucose(now)
        seedLocalSafetyEvidence(now)
        val before = settingsStore.settings.first()
        var attemptedRevision = before.sensitivitySettingsRevision
        val repository = repository(now, predictForecasts = {
            attemptedRevision = settingsStore.settings.first().sensitivitySettingsRevision
            forecastsAgingDuringPrediction(initialAgeMs = 899_000L)
        })

        val failure = runCatching {
            repository.applySensitivitySettings { current ->
                current.copy(isfCrUseActivity = !current.isfCrUseActivity)
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("forecast generation is not fresh")
        val restored = settingsStore.settings.first()
        assertThat(restored.sensitivityRuntimeFingerprint()).isEqualTo(before.sensitivityRuntimeFingerprint())
        assertThat(restored.sensitivitySettingsRevision).isGreaterThan(attemptedRevision)
        assertNoAcceptedPersistenceOrEffects()
        assertThat(db.forecastDao().latest(10)).isEmpty()
        assertStaleBlockedAudit()
        Unit
    }

    @Test
    fun freshDelayedPredictionKeepsOriginalGenerationAndPassesCurrentReadback() = runBlocking {
        val now = System.currentTimeMillis()
        seedRisingGlucose(now)
        seedLocalSafetyEvidence(now)
        var candidateGenerationTs = 0L
        val repository = repository(now, predictForecasts = {
            forecastsAgingDuringPrediction(initialAgeMs = 0L).also { forecasts ->
                candidateGenerationTs = requireNotNull(
                    AutomationRepository.resolveAcceptedForecastTimestampStatic(forecasts)
                )
            }
        })

        assertThat(repository.runAutomationCycle()).isNotNull()

        val markerTs = requireNotNull(db.telemetryDao().latestTimestampBySourceAndKey(
            SENSITIVITY_ACCEPTED_SOURCE, SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
        ))
        val tuple = requireNotNull(AcceptedSensitivityTupleRoomLoader(db).loadExact(
            settingsStore.settings.first().sensitivityRuntimeIdentity(), markerTs, System.currentTimeMillis()
        ))
        assertThat(tuple.accepted.generationTimestamp).isEqualTo(candidateGenerationTs)
        assertThat(tuple.accepted.forecastsByHorizon.values.map { row ->
            row.timestamp - row.horizonMinutes * MINUTE_MS
        }.toSet()).containsExactly(candidateGenerationTs)
        Unit
    }

    private suspend fun forecastsAgingDuringPrediction(initialAgeMs: Long): List<Forecast> {
        val predictionStartedAt = System.currentTimeMillis()
        val snapshotTs = requireNotNull(db.sensitivityRuntimeSnapshotDao().latest()).generatedAt
        val generationTs = if (initialAgeMs == 0L) snapshotTs else predictionStartedAt - initialAgeMs
        val forecasts = fixtureForecasts(generationTs)
        assertThat(AcceptedSensitivityTupleFreshness.isFresh(generationTs, predictionStartedAt)).isTrue()
        if (initialAgeMs > 0L) {
            // The production cycle captures its calculation clock after this snapshot and before predict.
            assertThat(snapshotTs).isAtMost(predictionStartedAt)
            assertThat(AcceptedSensitivityTupleFreshness.isFresh(generationTs, snapshotTs)).isTrue()
        }
        delay(2_000L)
        assertThat(System.currentTimeMillis() - generationTs).isAtLeast(initialAgeMs + 2_000L)
        assertThat(AcceptedSensitivityTupleFreshness.isFresh(generationTs, System.currentTimeMillis()))
            .isEqualTo(initialAgeMs == 0L)
        return forecasts
    }

    @Test
    fun staleSourceChangeRestoresPriorValuesWithForwardRevisionAndKeepsUnrelatedSettings() = runBlocking {
        val now = System.currentTimeMillis()
        seedRisingGlucose(now - 129L * MINUTE_MS)
        seedLocalSafetyEvidence(now)
        val before = settingsStore.settings.first()
        var attemptedRevision = before.sensitivitySettingsRevision
        val repository = repository(now, anchorForecastsToGlucose = true, beforePredict = {
            attemptedRevision = settingsStore.settings.first().sensitivitySettingsRevision
            settingsStore.update { it.copy(killSwitch = !before.killSwitch) }
        })

        val failure = runCatching {
            repository.applySensitivitySettings { current ->
                current.copy(
                    isfSourcePreference = if (current.isfSourcePreference == SensitivitySourcePreference.AAPS) {
                        SensitivitySourcePreference.COPILOT
                    } else SensitivitySourcePreference.AAPS,
                    isfCrUseActivity = !current.isfCrUseActivity
                )
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("forecast generation is not fresh")
        val restored = settingsStore.settings.first()
        assertThat(restored.sensitivityRuntimeFingerprint()).isEqualTo(before.sensitivityRuntimeFingerprint())
        assertThat(restored.sensitivitySettingsRevision).isGreaterThan(attemptedRevision)
        assertThat(restored.killSwitch).isEqualTo(!before.killSwitch)
        assertNoAcceptedPersistenceOrEffects()
        assertThat(db.forecastDao().latest(10)).isEmpty()
        assertThat(db.auditLogDao().recentByMessage("sensitivity_source_change_cycle_completed", 0L, 10))
            .isEmpty()
        assertStaleBlockedAudit()
        Unit
    }

    @Test
    fun acceptedGenerationGuardKeepsInclusiveBoundaryAndRejectsFutureAndOverflow() = runBlocking {
        val acceptedAtTs = 2_000_000L
        for (ageMs in listOf(0L, AcceptedSensitivityTupleFreshness.MAX_AGE_MS)) {
            AutomationRepository.requireFreshAcceptedForecastGenerationStatic(acceptedAtTs - ageMs, acceptedAtTs)
        }
        for ((generationTs, atTs) in listOf(
            acceptedAtTs - AcceptedSensitivityTupleFreshness.MAX_AGE_MS - 1L to acceptedAtTs,
            acceptedAtTs + 1L to acceptedAtTs,
            Long.MIN_VALUE to Long.MAX_VALUE
        )) {
            val failure = runCatching {
                AutomationRepository.requireFreshAcceptedForecastGenerationStatic(generationTs, atTs)
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure).hasMessageThat().contains("forecast generation is not fresh")
        }
        Unit
    }

    @Test
    fun cancelledGenerationGuardPropagatesCancellationBeforeStaleClassification() = runBlocking {
        val observed = CompletableDeferred<Throwable?>()
        val job = launch {
            currentCoroutineContext().cancel(CancellationException("cancel-generation-guard"))
            observed.complete(runCatching {
                AutomationRepository.requireFreshAcceptedForecastGenerationStatic(1L, 2_000_000L)
            }.exceptionOrNull())
        }
        job.join()
        val failure = observed.await()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(failure).hasMessageThat().isEqualTo("cancel-generation-guard")
        assertNoAcceptedPersistenceOrEffects()
        Unit
    }

    @Test
    fun cancellationBeforeAcceptanceIsNotConvertedToStaleOrPublished() = runBlocking {
        val now = System.currentTimeMillis()
        seedRisingGlucose(now - 129L * MINUTE_MS)
        seedLocalSafetyEvidence(now)
        val cancelled = CancellationException("cancel-before-forecast-acceptance")
        val repository = repository(now, anchorForecastsToGlucose = true, beforePredict = {
            throw cancelled
        })

        val failure = runCatching { repository.runAutomationCycle() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(failure).hasMessageThat().isEqualTo(cancelled.message)
        assertNoAcceptedPersistenceOrEffects()
        assertThat(db.auditLogDao().recentByMessage("automation_cycle_finished", 0L, 10)).isEmpty()
        Unit
    }

    private suspend fun assertNoAcceptedPersistenceOrEffects() {
        assertThat(db.telemetryDao().latestTimestampBySourceAndKey(
            SENSITIVITY_ACCEPTED_SOURCE, SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
        )).isNull()
        assertThat(db.telemetryDao().latestTimestampBySourceAndKey(
            ACCEPTED_CALIBRATION_SOURCE, ACCEPTED_CALIBRATION_PREPARED_AT_KEY
        )).isNull()
        assertThat(db.telemetryDao().latestTimestampBySourceAndKey(
            AutomationRepository.ACCEPTED_RUNTIME_TELEMETRY_SOURCE, "overview_accepted_forecast_digest"
        )).isNull()
        assertThat(db.targetManagerDao().latestDecisions(10)).isEmpty()
        assertThat(db.ruleExecutionDao().observeLatest(20).first()).isEmpty()
        assertThat(db.alertEventDao().maxGlucoseEpisodeCreatedAt()).isEqualTo(0L)
        assertThat(targetDispatches.get()).isEqualTo(0)
        assertThat(uamGatewayCalls.get()).isEqualTo(0)
        assertThat(widgetRefreshes.get()).isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(0)
    }

    private suspend fun assertStaleBlockedAudit() {
        val finished = db.auditLogDao().recentByMessage("automation_cycle_finished", 0L, 10).single()
        val metadata = JsonParser.parseString(finished.metadataJson).asJsonObject
        assertThat(metadata.get("status").asString).isEqualTo("blocked")
        assertThat(metadata.get("reason").asString).isEqualTo("stale_forecast_generation")
        assertThat(metadata.get("cycleAccepted").asBoolean).isFalse()
        assertThat(db.auditLogDao().recentByMessage("automation_cycle_failed", 0L, 10)).isEmpty()
    }

    @Test
    fun missingAcceptedHorizonReadBackFailsBeforeAllClinicalFanOut() = runBlocking {
        val now = System.currentTimeMillis()
        seedRisingGlucose(now)
        seedLocalSafetyEvidence(now)
        configureActiveSourceChange()
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER delete_accepted_horizon_after_commit
            AFTER INSERT ON telemetry_samples
            WHEN NEW.source = '${io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_SOURCE}'
              AND NEW.key = '${io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY}'
              AND NEW.valueText = '${io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED}'
            BEGIN
              DELETE FROM forecasts WHERE horizonMinutes = 60;
            END
            """.trimIndent()
        )
        val actionsBefore = db.actionCommandDao().latest(100)

        val failure = runCatching {
            repository(now).applySensitivitySettings { current ->
                current.copy(
                    isfSourcePreference = SensitivitySourcePreference.COPILOT,
                    crSourcePreference = SensitivitySourcePreference.COPILOT
                )
            }
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("accepted sensitivity Room tuple was not committed")
        assertThat(db.telemetryDao().latestTimestampBySourceAndKey(
            source = AutomationRepository.ACCEPTED_RUNTIME_TELEMETRY_SOURCE,
            key = "overview_accepted_forecast_digest"
        )).isNull()
        assertThat(db.targetManagerDao().latestDecisions(10)).isEmpty()
        assertThat(db.actionCommandDao().latest(100)).containsExactlyElementsIn(actionsBefore)
        assertThat(targetDispatches.get()).isEqualTo(0)
        assertThat(uamGatewayCalls.get()).isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(db.alertEventDao().maxGlucoseEpisodeCreatedAt()).isEqualTo(0L)
        assertThat(tableCount("alert_delivery_receipts")).isEqualTo(0)
        assertThat(tableCount("alert_ai_analyses")).isEqualTo(0)
        assertThat(context.getSystemService(NotificationManager::class.java).activeNotifications).isEmpty()
        assertThat(widgetRefreshes.get()).isEqualTo(0)
    }

    @Test
    fun malformedLatestLocalTargetEvidenceKeepsReadOnlyTargetManagerBlocked() = runBlocking {
        val now = System.currentTimeMillis()
        seedRisingGlucose(now)
        seedLocalSafetyEvidence(now)
        db.therapyDao().upsertAll(
            listOf(
                TherapyEventEntity(
                    id = "latest-malformed-target",
                    timestamp = now - MINUTE_MS,
                    type = "temp_target",
                    payloadJson = "{malformed"
                )
            )
        )
        configureActiveSourceChange()
        val actionsBefore = db.actionCommandDao().latest(100)

        repository(now).applySensitivitySettings { current ->
            current.copy(
                isfSourcePreference = SensitivitySourcePreference.COPILOT,
                crSourcePreference = SensitivitySourcePreference.COPILOT
            )
        }

        val targetDiagnostic = db.targetManagerDao().latestDecisions(10).single()
        assertThat(targetDiagnostic.deliveryStatus).isEqualTo("read_only")
        assertThat(targetDiagnostic.outcome).isEqualTo(TargetDecisionOutcome.BLOCK_MANUAL_TARGET.name)
        assertThat(targetDiagnostic.reasonCodesJson).contains("active_target_evidence_unresolved")
        assertThat(db.actionCommandDao().latest(100)).containsExactlyElementsIn(actionsBefore)
        assertThat(targetDispatches.get()).isEqualTo(0)
        assertThat(uamGatewayCalls.get()).isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(widgetRefreshes.get()).isEqualTo(1)
    }

    private suspend fun configureActiveSourceChange() {
        settingsStore.update { current ->
            current.copy(
                nightscoutUrl = server.url("/").toString(),
                apiSecret = "test-secret",
                therapyActionsArmed = true,
                killSwitch = false,
                sensorLagCorrectionMode = SensorLagCorrectionMode.ACTIVE,
                targetManagerMode = TargetManagerMode.ACTIVE,
                targetManagerModeManualOverride = true,
                adaptiveControllerEnabled = true,
                maxActionsIn6Hours = 3,
                adaptiveControllerMaxActions6h = 3,
                enableUamExportToAaps = true,
                dryRunExport = false
            )
        }
    }

    private suspend fun acceptedAuthorityRows(
        source: String,
        timestamp: Long,
        keyPrefix: String
    ) = db.telemetryDao().atTimestampBySourceAndKeys(
        source = source,
        timestamp = timestamp,
        keys = acceptedAuthorityKeys(keyPrefix)
    ).associateBy { it.key }

    private fun acceptedAuthorityKeys(keyPrefix: String) = buildList {
        add("${keyPrefix}_generation_timestamp")
        add("${keyPrefix}_digest")
        listOf(5, 30, 60).forEach { horizon ->
            val horizonPrefix = "${keyPrefix}_${horizon}m"
            add("${horizonPrefix}_target_timestamp")
            add("${horizonPrefix}_value_mmol")
            add("${horizonPrefix}_ci_low_mmol")
            add("${horizonPrefix}_ci_high_mmol")
            add("${horizonPrefix}_model_version")
        }
    }

    private fun assertAcceptedAuthorityRows(
        rows: Map<String, TelemetrySampleEntity>,
        keyPrefix: String,
        authority: AutomationRepository.AcceptedClinicalForecasts
    ) {
        assertThat(rows.getValue("${keyPrefix}_generation_timestamp").valueDouble)
            .isEqualTo(authority.generationTimestamp.toDouble())
        assertThat(rows.getValue("${keyPrefix}_digest").valueText).isEqualTo(authority.digest)
        authority.forecasts.forEach { forecast ->
            val horizonPrefix = "${keyPrefix}_${forecast.horizonMinutes}m"
            assertThat(rows.getValue("${horizonPrefix}_target_timestamp").valueDouble)
                .isEqualTo(forecast.ts.toDouble())
            assertThat(rows.getValue("${horizonPrefix}_value_mmol").valueDouble)
                .isEqualTo(forecast.valueMmol)
            assertThat(rows.getValue("${horizonPrefix}_ci_low_mmol").valueDouble)
                .isEqualTo(forecast.ciLow)
            assertThat(rows.getValue("${horizonPrefix}_ci_high_mmol").valueDouble)
                .isEqualTo(forecast.ciHigh)
            assertThat(rows.getValue("${horizonPrefix}_model_version").valueText)
                .isEqualTo(forecast.modelVersion)
        }
    }

    private fun tableCount(table: String): Int {
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            check(cursor.moveToFirst())
            return cursor.getInt(0)
        }
    }

    @Test
    fun telemetryReadQualifiesPacketReceivedAfterReadStartedButBeforeSnapshotCapture() = runBlocking {
        val observedNow = System.currentTimeMillis()
        val packetTime = observedNow - 5_000L
        seedAtomicInsulinPacket(packetTime)
        val resolved = readRuntimeTelemetry(repository(observedNow), observedNow - 60_000L)
        assertThat(resolved["iob_runtime_source_code"]).isEqualTo(1.0)
        assertThat(resolved["iob_bolus_units"]).isEqualTo(3.4)
        assertThat(resolved["iob_basal_units"]).isEqualTo(0.1)
        assertThat(resolved["iob_relay_timestamp_ms"]).isEqualTo(packetTime.toDouble())
    }

    @Test
    fun telemetryReadStillRejectsActuallyFutureInsulinPacket() = runBlocking {
        val observedNow = System.currentTimeMillis()
        seedAtomicInsulinPacket(observedNow + 120_000L)
        val resolved = readRuntimeTelemetry(repository(observedNow), observedNow - 60_000L)
        assertThat(resolved["iob_runtime_source_code"]).isNull()
        assertThat(resolved["iob_bolus_units"]).isNull()
    }

    private suspend fun seedAtomicInsulinPacket(packetTime: Long) {
        val values = mapOf(
            "iob_units" to 3.5, "iob_net_units" to 3.5, "iob_bolus_units" to 3.4,
            "iob_basal_units" to 0.1, "insulin_activity" to 0.016,
            "iob_effective_positive_units" to 3.5, "iob_relay_timestamp_ms" to packetTime.toDouble(),
            "iob_runtime_confidence" to 1.0, "iob_runtime_source_code" to 1.0
        )
        db.telemetryDao().upsertAll(values.map { (key, value) ->
            TelemetrySampleEntity("packet-$key", packetTime, "aaps_broadcast", key, value, null, null, "OK")
        })
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun readRuntimeTelemetry(repo: AutomationRepository, readStartedAt: Long): Map<String, Double?> {
        val settings = settingsStore.settings.first()
        val method = AutomationRepository::class.java.getDeclaredMethod(
            "resolveLatestTelemetry", Long::class.javaPrimitiveType,
            io.aaps.copilot.config.AppSettings::class.java, kotlin.coroutines.Continuation::class.java
        ).apply { isAccessible = true }
        val result = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any> { continuation ->
            method.invoke(repo, readStartedAt, settings, continuation)
        }
        return result.javaClass.getDeclaredField("values").apply { isAccessible = true }.get(result)
            as Map<String, Double?>
    }

    private fun repository(
        now: Long,
        anchorForecastsToGlucose: Boolean = false,
        forecastGenerationOffsetMs: Long = 0L,
        beforePredict: suspend () -> Unit = {},
        predictForecasts: (suspend () -> List<Forecast>)? = null,
        onWidgetRefresh: suspend () -> Unit = { widgetRefreshes.incrementAndGet() }
    ): AutomationRepository {
        val gson = Gson()
        val audit = AuditLogger(db.auditLogDao(), gson)
        val apiFactory = ApiFactory()
        val calibration = GlucoseCalibrationRepository(db, gson, audit)
        val isfCr = IsfCrRepository(db, gson, audit, calibration)
        val acceptedLoader = AcceptedSensitivityTupleRoomLoader(db)
        val sensitivity = SensitivityRuntimeRepository(
            loadedCandidates = {
                val settings = settingsStore.settings.first()
                val modelRevision = db.isfCrModelStateDao().active()?.updatedAt ?: 0L
                val profileRevision = db.profileEstimateDao().active()?.timestamp ?: 0L
                fun candidate(
                    value: Double,
                    candidateModelRevision: Long? = null,
                    candidateProfileRevision: Long? = null
                ) = SensitivityCandidate(
                    value = value,
                    timestamp = System.currentTimeMillis(),
                    confidence = 1.0,
                    qualityPassed = true,
                    sampleCount = 100,
                    coverage = 1.0,
                    unavailableReason = null,
                    modelRevision = candidateModelRevision,
                    profileRevision = candidateProfileRevision
                )
                SensitivityRuntimeLoadedCandidates(
                    settingsRevision = settings.sensitivitySettingsRevision,
                    isfPreference = settings.isfSourcePreference,
                    crPreference = settings.crSourcePreference,
                    isfCandidates = SensitivityCandidates(
                        candidate(3.0, modelRevision, profileRevision),
                        candidate(3.1, modelRevision, profileRevision),
                        candidate(3.2, modelRevision, profileRevision)
                    ),
                    crCandidates = SensitivityCandidates(
                        candidate(10.0, modelRevision, profileRevision),
                        candidate(10.5, modelRevision, profileRevision),
                        candidate(11.0, modelRevision, profileRevision)
                    )
                )
            },
            persistence = SensitivityRuntimePersistence(db.sensitivityRuntimeSnapshotDao()::upsert),
            settingsRevision = { settingsStore.settings.first().sensitivitySettingsRevision },
            acceptedSnapshotLoader = { revision, atTs ->
                val settings = settingsStore.settings.first()
                if (settings.sensitivitySettingsRevision != revision) null else {
                    acceptedLoader.load(settings.sensitivityRuntimeIdentity(), atTs)?.let { accepted ->
                        AcceptedSensitivityRuntimePublication(
                            accepted.snapshot,
                            accepted.acceptedAtTs,
                            accepted.snapshot.forecastCycleId
                        )
                    }
                }
            }
        )
        val actionRepository = NightscoutActionRepository(
            context = context,
            db = db,
            settingsStore = settingsStore,
            apiFactory = apiFactory,
            carbsSendThrottle = CarbsSendThrottle(db.actionCommandDao()),
            tempTargetSendThrottle = TempTargetSendThrottle(db.actionCommandDao()),
            gson = gson,
            auditLogger = audit
        )
        val targetManager = TargetManagerRepository(
            dao = db.targetManagerDao(),
            gson = gson,
            dispatcher = TargetCommandDispatcher {
                targetDispatches.incrementAndGet()
                true
            }
        )
        val uamGateway = object : AapsCarbGateway {
            override suspend fun postCarbEntry(tsMs: Long, grams: Double, note: String): Result<String> {
                uamGatewayCalls.incrementAndGet()
                return Result.success("unexpected")
            }

            override suspend fun fetchCarbEntries(sinceTsMs: Long): Result<List<AapsCarbEntry>> {
                uamGatewayCalls.incrementAndGet()
                return Result.success(emptyList())
            }
        }
        val episodeDelivery = EpisodeAlertDeliveryStateMachine(RoomEpisodeAlertReceiptStore(db))
        return AutomationRepository(
            db = db,
            settingsStore = settingsStore,
            syncRepository = SyncRepository(context, db, settingsStore, apiFactory, gson, audit),
            exportRepository = AapsExportRepository(context, db, settingsStore, audit),
            autoConnectRepository = AapsAutoConnectRepository(context, settingsStore, audit),
            rootDbRepository = RootDbExperimentalRepository(context, db, settingsStore, audit),
            analyticsRepository = AnalyticsRepository(db, io.aaps.copilot.domain.predict.PatternAnalyzer(), gson, audit, isfCr, calibration),
            isfCrRepository = isfCr,
            sensitivityRuntimeRepository = sensitivity,
            glucoseCalibrationRepository = calibration,
            glucoseAlertStateStore = alertStateStore,
            episodeAlertDelivery = episodeDelivery,
            glucoseAlertNotifier = GlucoseAlertNotifier(
                context,
                GlucoseAlertAudioController(context),
                episodeDelivery
            ),
            actionRepository = actionRepository,
            targetManagerRepository = targetManager,
            circadianTargetRepository = CircadianTargetRepository(db, gson, calibration),
            energyProfileRepository = EnergyProfileRepository(db.energyProfileDao()),
            predictionEngine = object : PredictionEngine {
                override suspend fun predict(
                    glucose: List<io.aaps.copilot.domain.model.GlucosePoint>,
                    therapyEvents: List<io.aaps.copilot.domain.model.TherapyEvent>
                ): List<Forecast> {
                    beforePredict()
                    predictForecasts?.let { return it() }
                    val generationTs = if (anchorForecastsToGlucose) glucose.maxOf { it.ts }
                    else requireNotNull(db.sensitivityRuntimeSnapshotDao().latest()).generatedAt
                    return fixtureForecasts(generationTs + forecastGenerationOffsetMs)
                }
            },
            uamInferenceEngine = io.aaps.copilot.domain.predict.UamInferenceEngine(),
            uamEventStore = UamEventStore(db.uamInferenceEventDao()),
            uamExportCoordinator = UamExportCoordinator(
                gateway = uamGateway,
                auditLogger = audit,
                reservationStore = ActionCommandUamExportReservationStore(db.actionCommandDao(), gson = gson)
            ),
            ruleEngine = RuleEngine(
                listOf(
                    object : TargetRule {
                        override val id: String = AdaptiveTargetControllerRule.RULE_ID

                        override fun evaluate(context: RuleContext) = RuleDecision(
                            ruleId = id,
                            state = RuleState.TRIGGERED,
                            reasons = listOf("integration_action_candidate"),
                            actionProposal = ActionProposal(
                                type = "integration_action",
                                targetMmol = 6.0,
                                durationMinutes = 30,
                                reason = "integration_action_candidate"
                            )
                        )
                    }
                ),
                SafetyPolicy()
            ),
            gson = gson,
            auditLogger = audit,
            onWidgetDataChanged = onWidgetRefresh
        )
    }

    private suspend fun seedRisingGlucose(
        now: Long,
        seedSensitivityGeneration: Boolean = true
    ) {
        db.glucoseDao().upsertAll(
            (0 until 72).map { index ->
                val oldestFirst = 71 - index
                GlucoseSampleEntity(
                    timestamp = now - oldestFirst * 5L * MINUTE_MS,
                    mmol = 6.0 + index * 0.08,
                    source = "integration_sensor",
                    quality = "OK"
                )
            }
        )
        if (seedSensitivityGeneration) {
            val generationRevision = (now - 1L).coerceAtLeast(1L)
            db.isfCrModelStateDao().upsert(
                IsfCrModelStateEntity(
                    updatedAt = generationRevision,
                    hourlyIsfJson = "[]",
                    hourlyCrJson = "[]",
                    paramsJson = "{}",
                    fitMetricsJson = "{}"
                )
            )
            db.profileEstimateDao().upsert(validSensitivityProfile(generationRevision))
            db.syncStateDao().upsert(
                SyncStateEntity(
                    source = "profile_estimator_algorithm_revision",
                    lastSyncedTimestamp = AnalyticsRepository.PROFILE_ESTIMATOR_ALGORITHM_REVISION
                )
            )
        }
    }

    private fun validSensitivityProfile(timestamp: Long) = ProfileEstimateEntity(
        timestamp = timestamp,
        isfMmolPerUnit = 3.0,
        crGramPerUnit = 10.0,
        confidence = 0.8,
        sampleCount = 20,
        isfSampleCount = 10,
        crSampleCount = 10,
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
        calculatedConfidence = 0.8,
        calculatedSampleCount = 20,
        calculatedIsfSampleCount = 10,
        calculatedCrSampleCount = 10
    )

    private suspend fun seedLocalSafetyEvidence(now: Long, recentActionCount: Int = 3) {
        val sensorSessionKey = requireNotNull(calibrationSensorSessionKeyFromAgeSample(now, 48.0))
        db.telemetryDao().upsertAll(
            listOf(
                TelemetrySampleEntity(
                    id = "sensor-age",
                    timestamp = now,
                    source = "integration_sensor",
                    key = "sensor_age_hours",
                    valueDouble = 48.0,
                    valueText = null,
                    unit = "h",
                    quality = "OK"
                ),
                TelemetrySampleEntity(
                    id = "calibration-authority",
                    timestamp = now,
                    source = CALIBRATION_AUTHORITY_SOURCE,
                    key = CALIBRATION_AUTHORITY_TOKEN_KEY,
                    valueDouble = null,
                    valueText = CalibrationAuthorityStateCodec.raw(
                        nonce = "integration",
                        sessionKey = sensorSessionKey
                    ),
                    unit = null,
                    quality = "OK"
                )
            )
        )
        db.therapyDao().upsertAll(
            listOf(
                TherapyEventEntity(
                    "sensor-change",
                    now - 2L * 24L * 60L * MINUTE_MS,
                    "sensor_change",
                    "{}"
                ),
                TherapyEventEntity(
                    "foreign-target",
                    now - 4L * 60L * MINUTE_MS,
                    "temp_target",
                    """{"targetBottom":7.2,"duration":720,"notes":"AAPS manual"}"""
                )
            )
        )
        (1..recentActionCount).forEach { index ->
            db.actionCommandDao().upsert(
                ActionCommandEntity(
                    id = "recent-action-$index",
                    timestamp = now - index * MINUTE_MS,
                    type = "temp_target",
                    payloadJson = """{"targetMmol":"5.8"}""",
                    safetyJson = "{}",
                    idempotencyKey = "TargetManager.v1:history-$index",
                    status = NightscoutActionRepository.STATUS_SENT
                )
            )
        }
    }

    private fun fixtureForecasts(generationTs: Long) = listOf(5, 30, 60).map { horizon ->
        Forecast(
            ts = generationTs + horizon * MINUTE_MS,
            horizonMinutes = horizon,
            valueMmol = 11.0 + horizon / 100.0,
            ciLow = 10.2 + horizon / 100.0,
            ciHigh = 11.8 + horizon / 100.0,
            modelVersion = "fixture"
        )
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
