package io.aaps.copilot.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.room.Room
import androidx.room.withTransaction
import com.google.gson.Gson
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.config.AppSettingsLegacyOpenAiKeySource
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.config.ClinicalAiProviderConfig
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.config.TherapyActionBootstrapMigrationResult
import io.aaps.copilot.config.sensitivityRuntimeIdentity
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.CopilotMigrations
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.data.local.entity.SensitivityRuntimeSnapshotEntity
import io.aaps.copilot.data.repository.ActionCommandUamExportReservationStore
import io.aaps.copilot.data.repository.AapsBolusLauncher
import io.aaps.copilot.data.repository.AapsCarbImportTransactionRunner
import io.aaps.copilot.data.repository.AapsCarbHistoryImporter
import io.aaps.copilot.data.repository.AapsCarbHistorySource
import io.aaps.copilot.data.repository.AapsCarbHistorySyncRepository
import io.aaps.copilot.data.repository.AapsClinicalSummarySource
import io.aaps.copilot.data.repository.AapsExportRepository
import io.aaps.copilot.data.repository.AapsAutoConnectRepository
import io.aaps.copilot.data.repository.AnalyticsRepository
import io.aaps.copilot.data.repository.AlertsRepository
import io.aaps.copilot.data.repository.AlertAiAnalysisSettingsSource
import io.aaps.copilot.data.repository.AlertAiCredentialSource
import io.aaps.copilot.data.repository.AlertAiProductionFactory
import io.aaps.copilot.data.repository.AlertAiReportDatasetSource
import io.aaps.copilot.data.repository.AndroidValidatedNetworkGate
import io.aaps.copilot.data.repository.AiChatRepository
import io.aaps.copilot.data.repository.AuditLogger
import io.aaps.copilot.data.repository.AutomationRepository
import io.aaps.copilot.data.repository.BroadcastIngestRepository
import io.aaps.copilot.data.repository.CarbsSendThrottle
import io.aaps.copilot.data.repository.AnthropicClinicalAiGateway
import io.aaps.copilot.data.repository.ClinicalAiConfigSource
import io.aaps.copilot.data.repository.ClinicalAiConnectionStatus
import io.aaps.copilot.data.repository.ClinicalAiConnectionTest
import io.aaps.copilot.data.repository.ClinicalAiCredentialSource
import io.aaps.copilot.data.repository.ClinicalAiGatewayBuilder
import io.aaps.copilot.data.repository.ClinicalAiGatewayException
import io.aaps.copilot.data.repository.ClinicalAiGatewayFactory
import io.aaps.copilot.data.repository.ClinicalOpenAiClient
import io.aaps.copilot.data.repository.ClinicalReportDatasetBuilder
import io.aaps.copilot.data.repository.ClinicalEventTimelineSource
import io.aaps.copilot.data.repository.ClinicalReportDatasetFactory
import io.aaps.copilot.data.repository.ClinicalEnergyProfileSource
import io.aaps.copilot.data.repository.ClinicalEnergyProfileSourceSnapshot
import io.aaps.copilot.data.repository.ClinicalTargetManagerEvidenceSource
import io.aaps.copilot.data.repository.ClinicalReportRepository
import io.aaps.copilot.data.repository.CircadianTargetRepository
import io.aaps.copilot.data.repository.EatingWindowSnapshotRepository
import io.aaps.copilot.data.repository.EatingSoonResult
import io.aaps.copilot.data.repository.MealDeliveryStatus
import io.aaps.copilot.data.repository.CalibrationModelAuthority
import io.aaps.copilot.domain.target.EatingSoonEvidence
import io.aaps.copilot.domain.target.EatingSoonForecast
import io.aaps.copilot.domain.target.EatingSoonPolicy
import io.aaps.copilot.data.repository.EnergyProfileInferenceDataSource
import io.aaps.copilot.data.repository.EnergyProfileRepository
import io.aaps.copilot.data.repository.GlucoseCalibrationRepository
import io.aaps.copilot.data.repository.EventTimelineRepository
import io.aaps.copilot.data.repository.EventTimelineSources
import io.aaps.copilot.data.repository.DELIVERY_DIAGNOSTIC_TELEMETRY_KEYS
import io.aaps.copilot.data.repository.DeliveryTrustTelemetryValue
import io.aaps.copilot.data.repository.deliveryDiagnosticEventsForWindow
import io.aaps.copilot.data.repository.deliveryDiagnosticLookbackFrom
import io.aaps.copilot.data.repository.AapsContextEventGateway
import io.aaps.copilot.data.repository.ContextEventSyncCoordinator
import io.aaps.copilot.data.repository.RoomContextEventTransactionRunner
import io.aaps.copilot.data.repository.TherapySanitizer
import io.aaps.copilot.data.repository.toDomain
import io.aaps.copilot.data.repository.resolveAlertAiAnalysisSettings
import io.aaps.copilot.domain.activity.PhysicalActivityBucket
import io.aaps.copilot.domain.activity.PhysicalActivityTelemetryPolicy
import io.aaps.copilot.data.repository.GlucoseAlertAudioController
import io.aaps.copilot.data.repository.GlucoseAlertNotifier
import io.aaps.copilot.data.repository.DataStorePumpLinkRecordStore
import io.aaps.copilot.data.repository.PumpLinkHealthMonitor
import io.aaps.copilot.data.repository.PumpLinkMuteCoordinator
import io.aaps.copilot.data.repository.PumpLinkNotifier
import io.aaps.copilot.domain.pump.PumpLinkSnapshot
import io.aaps.copilot.data.repository.GlucoseAlertStateStore
import io.aaps.copilot.data.repository.EpisodeAlertDeliveryStateMachine
import io.aaps.copilot.data.repository.RoomEpisodeAlertReceiptStore
import io.aaps.copilot.data.repository.GeminiClinicalAiGateway
import io.aaps.copilot.data.repository.IsfCrRepository
import io.aaps.copilot.data.repository.isfCrInputGenerationOrNull
import io.aaps.copilot.data.repository.realtimeSnapshotInputGeneration
import io.aaps.copilot.data.repository.SensitivityRuntimeRepository
import io.aaps.copilot.data.repository.SensitivityRuntimeLoadedCandidates
import io.aaps.copilot.data.repository.AcceptedSensitivityRuntimePublication
import io.aaps.copilot.data.repository.AcceptedSensitivityTupleRoomLoader
import io.aaps.copilot.data.repository.InsightsRepository
import io.aaps.copilot.data.repository.MealEnergyOverrideRepository
import io.aaps.copilot.data.repository.NightscoutActionRepository
import io.aaps.copilot.data.repository.OpenAiCompatibleClinicalAiGateway
import io.aaps.copilot.data.repository.RootDbExperimentalRepository
import io.aaps.copilot.data.repository.SyncRepository
import io.aaps.copilot.data.repository.TargetCommandDispatcher
import io.aaps.copilot.data.repository.TargetCommandPreflightBlockedException
import io.aaps.copilot.data.repository.TargetCommandPreflightFailure
import io.aaps.copilot.data.repository.TargetDeliveryStatusProvider
import io.aaps.copilot.data.repository.TargetManagerRepository
import io.aaps.copilot.data.repository.TempTargetSendThrottle
import io.aaps.copilot.data.repository.UamEventStore
import io.aaps.copilot.data.repository.UamExportCoordinator
import io.aaps.copilot.data.repository.toDomain
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.domain.predict.SensitivityCandidate
import io.aaps.copilot.domain.predict.SensitivityCandidates
import io.aaps.copilot.domain.predict.SensitivityRuntimeContract
import io.aaps.copilot.domain.predict.PatternAnalyzer
import io.aaps.copilot.domain.predict.PredictionEngine
import io.aaps.copilot.domain.predict.ProfileEstimatorConfig
import io.aaps.copilot.domain.predict.UamInferenceEngine
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.model.SafetySnapshot
import io.aaps.copilot.domain.target.ActiveTargetOwnership
import io.aaps.copilot.domain.target.BaseTargetSchedulePolicy
import io.aaps.copilot.domain.target.CircadianAutoState
import io.aaps.copilot.domain.target.TargetCommandCandidate
import io.aaps.copilot.domain.target.TargetCommandObservation
import io.aaps.copilot.domain.target.TargetManagerMode
import io.aaps.copilot.domain.target.TargetOwnershipPolicy
import io.aaps.copilot.domain.rules.PatternAdaptiveTargetRule
import io.aaps.copilot.domain.rules.PostHypoReboundGuardRule
import io.aaps.copilot.domain.rules.AdaptiveTargetControllerRule
import io.aaps.copilot.domain.rules.RuleEngine
import io.aaps.copilot.domain.rules.SegmentProfileGuardRule
import io.aaps.copilot.domain.safety.SafetyPolicy
import io.aaps.copilot.report.ClinicalReportPdfRenderer
import io.aaps.copilot.storage.ClinicalPdfShareRepository
import io.aaps.copilot.storage.ClinicalPdfStorageRepository
import io.aaps.copilot.scheduler.WorkScheduler
import io.aaps.copilot.scheduler.CircadianTargetAudit
import io.aaps.copilot.scheduler.CircadianTargetSchedule
import io.aaps.copilot.scheduler.CircadianTargetStartup
import io.aaps.copilot.scheduler.CircadianTargetWorkerPolicy
import io.aaps.copilot.scheduler.ClinicalInputInvalidationCoordinator
import io.aaps.copilot.scheduler.DataStoreClinicalInputInvalidationLedger
import io.aaps.copilot.scheduler.DataStoreClinicalInvalidationExecutionEvidenceStore
import io.aaps.copilot.scheduler.ClinicalInputInvalidationSource
import io.aaps.copilot.scheduler.resolveClinicalInputInvalidationMode
import io.aaps.copilot.ui.foundation.AlertNavigationCoordinator
import io.aaps.copilot.ui.foundation.AlertNavigationFailureCode
import io.aaps.copilot.security.ClinicalAiCredentialStatus
import io.aaps.copilot.security.ClinicalAiCredentialProvider
import io.aaps.copilot.security.ClinicalAiCredentialStore
import io.aaps.copilot.security.ClinicalAiSecretStorageNamespaces
import io.aaps.copilot.security.EmptyLegacyCredentialSource
import io.aaps.copilot.security.KeystoreSecretStorage
import io.aaps.copilot.security.OpenAiCredentialProvider
import io.aaps.copilot.security.OpenAiCredentialStore
import io.aaps.copilot.security.ServerAiConnectionManager
import io.aaps.copilot.security.TherapyActionTransportGate
import io.aaps.copilot.security.TherapyActionBootstrapMigrationInput
import io.aaps.copilot.security.TherapyActionBootstrapMigrationPolicy
import io.aaps.copilot.widget.CopilotGlucoseWidgetUpdater
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class ClinicalAiProviderCredentialState(
    val configured: Boolean,
    val busy: Boolean,
    val migrationFailed: Boolean = false,
    val readFailed: Boolean = false,
    val legacyCleanupPending: Boolean = false
)

internal fun mapClinicalAiProviderCredentialStates(
    openAi: ClinicalAiCredentialStatus,
    providers: Map<ClinicalAiProviderId, ClinicalAiCredentialStatus>
): Map<ClinicalAiProviderId, ClinicalAiProviderCredentialState> =
    providers.mapValues { (_, value) ->
        ClinicalAiProviderCredentialState(
            configured = value.configured,
            busy = value.busy,
            migrationFailed = value.migrationFailed,
            readFailed = value.readFailed,
            legacyCleanupPending = value.legacyCleanupPending
        )
    } + (
        ClinicalAiProviderId.OPENAI to ClinicalAiProviderCredentialState(
            configured = openAi.configured,
            busy = openAi.busy,
            migrationFailed = openAi.migrationFailed,
            readFailed = openAi.readFailed,
            legacyCleanupPending = openAi.legacyCleanupPending
        )
    )

class ClinicalAiProviderManager internal constructor(
    private val openAiCredentialProvider: OpenAiCredentialProvider,
    private val clinicalAiCredentialProvider: ClinicalAiCredentialProvider,
    private val gatewayFactory: ClinicalAiGatewayFactory
) {
    val status: Flow<Map<ClinicalAiProviderId, ClinicalAiProviderCredentialState>> =
        combine(
            openAiCredentialProvider.status,
            clinicalAiCredentialProvider.status
        ) { openAi, providers ->
            mapClinicalAiProviderCredentialStates(openAi, providers)
        }.distinctUntilChanged()

    suspend fun replace(providerId: ClinicalAiProviderId, value: String) {
        when (providerId) {
            ClinicalAiProviderId.OPENAI -> openAiCredentialProvider.replace(value)
            else -> clinicalAiCredentialProvider.replace(providerId, value)
        }
    }

    suspend fun delete(providerId: ClinicalAiProviderId) {
        when (providerId) {
            ClinicalAiProviderId.OPENAI -> openAiCredentialProvider.delete()
            else -> clinicalAiCredentialProvider.delete(providerId)
        }
    }

    suspend fun testConnection(
        config: ClinicalAiProviderConfig
    ): ClinicalAiConnectionTest {
        val gateway = try {
            gatewayFactory.create(config)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: ClinicalAiGatewayException.IdentityMismatch) {
            return connectionFailure(config, ClinicalAiConnectionStatus.IDENTITY_MISMATCH)
        } catch (_: ClinicalAiGatewayException.UnsupportedProvider) {
            return connectionFailure(config, ClinicalAiConnectionStatus.SERVICE_UNAVAILABLE)
        } catch (_: Exception) {
            return connectionFailure(config, ClinicalAiConnectionStatus.INVALID_RESPONSE)
        }
        val credential = try {
            when (config.providerId) {
                ClinicalAiProviderId.OPENAI ->
                    openAiCredentialProvider.requireCredential()
                else ->
                    clinicalAiCredentialProvider.requireCredential(config.providerId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            return connectionFailure(
                config,
                ClinicalAiConnectionStatus.CREDENTIAL_UNAVAILABLE
            )
        }
        if (credential.isBlank()) {
            return connectionFailure(
                config,
                ClinicalAiConnectionStatus.CREDENTIAL_UNAVAILABLE
            )
        }
        return try {
            gateway.testConnection(credential)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            connectionFailure(config, ClinicalAiConnectionStatus.INVALID_RESPONSE)
        }
    }

    private fun connectionFailure(
        config: ClinicalAiProviderConfig,
        status: ClinicalAiConnectionStatus
    ) = ClinicalAiConnectionTest(
        providerId = config.providerId,
        modelId = config.modelId,
        status = status
    )
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val gson: Gson = Gson()
    val settingsStore = AppSettingsStore(context)
    val serverAiConnectionManager = ServerAiConnectionManager.create(appContext)

    val db: CopilotDatabase = Room.databaseBuilder(
        context,
        CopilotDatabase::class.java,
        "copilot.db"
    )
        .addMigrations(*CopilotMigrations.ALL)
        .fallbackToDestructiveMigrationFrom(
            dropAllTables = true,
            1, 2, 3, 4, 5, 6, 7, 8
        )
        .build()

    val apiFactory = ApiFactory { LocalNightscoutTls.loadClientIdentity(appContext) }
    val auditLogger = AuditLogger(db.auditLogDao(), gson)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mealStateInbox = io.aaps.copilot.data.repository.MealStateInbox(db)
    internal val mealStateIngestion = io.aaps.copilot.data.repository.MealStateIngestionQueue(
        scope = appScope,
        process = mealStateInbox::persist,
        drain = mealStateInbox::drainBatch
    )
    internal val clinicalInvalidationExecutionEvidenceStore =
        DataStoreClinicalInvalidationExecutionEvidenceStore(appContext)
    internal val clinicalInputInvalidationCoordinator = ClinicalInputInvalidationCoordinator(
        scope = appScope,
        ledger = DataStoreClinicalInputInvalidationLedger(appContext),
        ownershipStatusSource = { request ->
            WorkScheduler.clinicalInputInvalidationOwnershipStatus(
                appContext,
                request,
                clinicalInvalidationExecutionEvidenceStore
            )
        },
        failureReporter = {
            auditLogger.warn(
                "clinical_input_invalidation_ledger_failure",
                mapOf("type" to it::class.java.simpleName)
            )
        },
        modeSource = {
            val settings = settingsStore.settings.first()
            resolveClinicalInputInvalidationMode(
                therapyActionsArmed = settings.therapyActionsArmed,
                powerSaveActive = PowerSaveController.isActive(settings)
            )
        },
        sink = { request ->
            WorkScheduler.dispatchClinicalInputInvalidation(appContext, request)
        }
    )
    init {
        appScope.launch {
            mealStateIngestion.health.map { Triple(it.failedBatches, it.quarantinedReceipts, it.rejectedBatches) }
                .distinctUntilChanged().collect { (failed, quarantined, rejected) ->
                    if (failed == 0L && quarantined == 0 && rejected == 0L) return@collect
                    try {
                        auditLogger.warnThrottled("meal_state_ingestion", 300_000L,
                            "meal_state_ingestion_incomplete", mapOf(
                                "failedBatches" to failed, "quarantinedReceipts" to quarantined,
                                "rejectedBatches" to rejected))
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { }
                }
        }
        appScope.launch {
            clinicalInputInvalidationCoordinator.hydrateAndRedriveSafely()
        }
    }
    private val physiologicalSexSource = suspend {
        settingsStore.settings.first().energyProfile.physiologicalSex
    }
    internal val contextEventSyncCoordinator = ContextEventSyncCoordinator(
        contextTagDao = db.physioContextTagDao(),
        syncDao = db.contextEventSyncDao(),
        transactionRunner = RoomContextEventTransactionRunner(db),
        gateway = AapsContextEventGateway(
            context = appContext,
            physiologicalSex = physiologicalSexSource
        ),
        gson = gson,
        physiologicalSex = physiologicalSexSource,
        onDurableMutationApplied = {
            clinicalInputInvalidationCoordinator.invalidatePersistedInput(
                ClinicalInputInvalidationSource.CONTEXT_EVENT
            )
        }
    )
    private val openAiCredentialStore = OpenAiCredentialStore(
        secureStorage = KeystoreSecretStorage(
            context.applicationContext,
            ClinicalAiSecretStorageNamespaces.OPENAI
        ),
        legacyKeySource = AppSettingsLegacyOpenAiKeySource(settingsStore)
    )
    val openAiCredentialProvider = OpenAiCredentialProvider(openAiCredentialStore)
    private val clinicalAiCredentialProvider = ClinicalAiCredentialProvider(
        ClinicalAiProviderId.values()
            .filterNot { it == ClinicalAiProviderId.OPENAI }
            .associateWith { providerId ->
                ClinicalAiCredentialStore(
                    providerId = providerId,
                    secureStorage = KeystoreSecretStorage(
                        context.applicationContext,
                        ClinicalAiSecretStorageNamespaces.forProvider(providerId)
                    ),
                    legacyCredentialSource = EmptyLegacyCredentialSource
                )
            }
    )
    private val clinicalAiCredentialSource = ClinicalAiCredentialSource { providerId ->
        when (providerId) {
            ClinicalAiProviderId.OPENAI -> openAiCredentialProvider.requireCredential()
            else -> clinicalAiCredentialProvider.requireCredential(providerId)
        }
    }
    private val clinicalAiGatewayFactory = ClinicalAiGatewayFactory(
        mapOf(
            ClinicalAiProviderId.OPENAI to ClinicalAiGatewayBuilder { config ->
                ClinicalOpenAiClient(modelId = config.modelId)
            },
            ClinicalAiProviderId.ANTHROPIC to ClinicalAiGatewayBuilder { config ->
                AnthropicClinicalAiGateway(modelId = config.modelId)
            },
            ClinicalAiProviderId.GEMINI to ClinicalAiGatewayBuilder { config ->
                GeminiClinicalAiGateway(modelId = config.modelId)
            },
            ClinicalAiProviderId.OPENAI_COMPATIBLE to ClinicalAiGatewayBuilder { config ->
                OpenAiCompatibleClinicalAiGateway(config)
            }
        )
    )
    val clinicalAiProviderManager = ClinicalAiProviderManager(
        openAiCredentialProvider = openAiCredentialProvider,
        clinicalAiCredentialProvider = clinicalAiCredentialProvider,
        gatewayFactory = clinicalAiGatewayFactory
    )
    private val localNightscoutServer = LocalNightscoutServer(
        context = context.applicationContext,
        db = db,
        settingsStore = settingsStore,
        gson = gson,
        auditLogger = auditLogger,
        onClinicalInputPersisted = {
            clinicalInputInvalidationCoordinator.invalidatePersistedInput(
                ClinicalInputInvalidationSource.LOCAL_NIGHTSCOUT
            )
        }
    )
    private val localActivitySensorCollector = LocalActivitySensorCollector(
        context = context.applicationContext,
        db = db,
        auditLogger = auditLogger,
        onClinicalInputPersisted = {
            clinicalInputInvalidationCoordinator.invalidatePersistedInput(
                ClinicalInputInvalidationSource.LOCAL_ACTIVITY
            )
        }
    )
    private val healthConnectActivityCollector = HealthConnectActivityCollector(
        context = context.applicationContext,
        db = db,
        auditLogger = auditLogger,
        onClinicalInputPersisted = {
            clinicalInputInvalidationCoordinator.invalidatePersistedInput(
                ClinicalInputInvalidationSource.HEALTH_CONNECT_ACTIVITY
            )
        }
    )
    private val healthConnectEnabled = false
    @Volatile
    private var runtimeControllersActive = false
    private var localNightscoutSettingsJob: Job? = null
    private var powerSaveSettingsJob: Job? = null

    val syncRepository = SyncRepository(
        context = context.applicationContext,
        db = db,
        settingsStore = settingsStore,
        apiFactory = apiFactory,
        gson = gson,
        auditLogger = auditLogger,
        onSuccessfulNightscoutSync = {
            reconcilePendingContextEvents("nightscout_sync")
        }
    )

    val exportRepository = AapsExportRepository(
        context = context,
        db = db,
        settingsStore = settingsStore,
        auditLogger = auditLogger
    )

    val autoConnectRepository = AapsAutoConnectRepository(
        context = context,
        settingsStore = settingsStore,
        auditLogger = auditLogger
    )

    val aapsBolusLauncher = AapsBolusLauncher(context.applicationContext)

    val rootDbRepository = RootDbExperimentalRepository(
        context = context,
        db = db,
        settingsStore = settingsStore,
        auditLogger = auditLogger
    )

    val aapsCarbHistorySyncRepository = AapsCarbHistorySyncRepository(
        source = AapsCarbHistorySource(context.applicationContext),
        importer = AapsCarbHistoryImporter(
            therapyDao = db.therapyDao(),
            transactionRunner = AapsCarbImportTransactionRunner { block ->
                db.withTransaction { block() }
            },
            persistMealPage = { mealStateIngestion.offerPage(it, wakeAfterPersist = false) },
            onCommittedPage = { mealStateIngestion.wake() }
        ),
        syncStateDao = db.syncStateDao(),
        auditLogger = auditLogger
    )

    val broadcastIngestRepository = BroadcastIngestRepository(
        context = context.applicationContext,
        db = db,
        auditLogger = auditLogger,
        aapsCarbHistorySyncRepository = aapsCarbHistorySyncRepository,
        onClinicalInputPersisted = { source ->
            clinicalInputInvalidationCoordinator.invalidatePersistedInput(source)
        }
    )

    val glucoseCalibrationRepository = GlucoseCalibrationRepository(
        db = db,
        gson = gson,
        auditLogger = auditLogger,
        onManualCalibrationDurableMutation = { refreshGlucoseWidget() }
    )
    private val clinicalReportDatasetBuilder = ClinicalReportDatasetBuilder(
        db,
        glucoseCalibrationRepository,
        AapsClinicalSummarySource(context.applicationContext),
        sensitivitySettingsIdentity = {
            settingsStore.settings.first().sensitivityRuntimeIdentity()
        },
        energyProfileSource = ClinicalEnergyProfileSource { fromTs, throughTs ->
            val energyProfileDao = db.energyProfileDao()
            ClinicalEnergyProfileSourceSnapshot(
                settings = settingsStore.settings.first().energyProfile,
                latestInference = energyProfileDao.observeLatestSnapshot().first(),
                plannedActivities = energyProfileDao.allEvents(),
                targetManagerEvidence = ClinicalTargetManagerEvidenceSource.load(
                    preparedFromTs = fromTs,
                    throughTs = throughTs,
                    query = db.targetManagerDao()::clinicalEvidenceBetween
                ),
                manualMealEnergyOverrides = db.mealEnergyOverrideDao().all()
            )
        },
        eventTimelineSource = ClinicalEventTimelineSource { fromTs, throughTs ->
            val timelineRepository = EventTimelineRepository(gson)
            val therapy = TherapySanitizer.toDomainEvents(
                db.therapyDao().between(
                    clinicalEventTherapyLookbackFrom(fromTs),
                    throughTs
                ),
                gson
            )
            val planned = timelineRepository.plannedActivityEvents(
                rows = db.energyProfileDao().allEvents(),
                fromTs = fromTs,
                throughTs = throughTs,
                statusAtTs = throughTs
            )
            val actual = timelineRepository.actualActivityEventsFromBuckets(
                PhysicalActivityTelemetryPolicy.reportBucketWindow(fromTs, throughTs).let { window ->
                    db.telemetryDao().physicalActivityMetric5MinuteBuckets(
                        fromTs = window.fromTs,
                        toTsExclusive = window.toTsExclusive,
                        firstBucketTs = window.firstBucketTs,
                        keys = listOf(PhysicalActivityTelemetryPolicy.ACTIVITY_RATIO_KEY),
                        sources = PhysicalActivityTelemetryPolicy.TRUSTED_SOURCES.sorted(),
                        qualities = PhysicalActivityTelemetryPolicy.ACCEPTABLE_QUALITIES.sorted(),
                        bucketMs = PhysicalActivityTelemetryPolicy.REPORT_BUCKET_MS
                    )
                }.map { row ->
                    PhysicalActivityBucket(
                        bucketTs = row.bucketTs,
                        firstTs = row.firstTs,
                        lastTs = row.lastTs,
                        peakRatio = row.maxValue,
                        meanRatio = row.meanValue,
                        sampleCount = row.sampleCount,
                        source = row.source,
                        qualityEvidence = row.qualityEvidence
                    )
                }
            )
            val delivery = deliveryDiagnosticEventsForWindow(
                rows = db.telemetryDao().betweenForClinicalReport(
                    fromTs = deliveryDiagnosticLookbackFrom(fromTs),
                    toTs = throughTs,
                    keys = DELIVERY_DIAGNOSTIC_TELEMETRY_KEYS
                ).map { row ->
                    DeliveryTrustTelemetryValue(row.ts, row.source, row.key, row.value)
                },
                fromTs = fromTs,
                throughTs = throughTs
            )
            val calibrations = timelineRepository.calibrationEvents(
                db.bloodGlucoseCheckDao().between(fromTs, throughTs).map { it.toDomain() }
            )
            timelineRepository.aggregate(
                EventTimelineSources(
                    therapyEvents = therapy,
                    contextTags = db.physioContextTagDao().between(fromTs, throughTs),
                    plannedActivity = planned,
                    actualActivity = actual,
                    calibrationEvents = calibrations,
                    deliveryDiagnostics = delivery
                ),
                throughTs
            )
        },
        gson = gson
    )
    val clinicalReportRepository = ClinicalReportRepository(
        datasetFactory = ClinicalReportDatasetFactory(clinicalReportDatasetBuilder::build),
        configSource = ClinicalAiConfigSource {
            settingsStore.settings.first().clinicalAiConfigState
        },
        gatewayFactory = clinicalAiGatewayFactory,
        credentialSource = clinicalAiCredentialSource,
        reportDao = db.clinicalReportDao(),
        auditLogger = auditLogger
    )
    private val clinicalReportPdfRenderer = ClinicalReportPdfRenderer()
    internal val clinicalPdfStorageRepository = ClinicalPdfStorageRepository(
        context = appContext,
        renderer = clinicalReportPdfRenderer,
        cleanupScope = appScope
    )
    internal val clinicalPdfShareRepository = ClinicalPdfShareRepository(appContext)
    val circadianTargetRepository = CircadianTargetRepository(
        db = db,
        gson = gson,
        glucoseCalibrationRepository = glucoseCalibrationRepository
    )
    val eatingWindowSnapshotRepository = EatingWindowSnapshotRepository(
        db = db,
        gson = gson
    )
    val glucoseAlertStateStore = GlucoseAlertStateStore(context.applicationContext)
    val alertsRepository = AlertsRepository(db.alertEventDao(), db.alertAiAnalysisDao())
    val alertNavigationCoordinator = AlertNavigationCoordinator(
        scope = appScope,
        onValidationFailure = { code -> recordAlertNavigationFailure(code) },
        validateEpisodeId = alertsRepository::validatedGlucoseEpisodeId
    )

    private fun recordAlertNavigationFailure(code: AlertNavigationFailureCode) {
        appScope.launch {
            try {
                auditLogger.warnThrottled(
                    throttleKey = "alert_navigation_validation_failure",
                    intervalMs = 5L * 60L * 1_000L,
                    message = "alert_navigation_validation_failure",
                    metadata = mapOf("code" to code.name)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Error) {
                throw fatal
            } catch (_: Exception) {
                // The bounded local diagnostic is best effort and cannot block navigation.
            }
        }
    }

    val glucoseAlertAudioController = GlucoseAlertAudioController(context.applicationContext)
    private val alertAiProductionDispatcher = AlertAiProductionFactory.create(
        scope = appScope,
        settingsSource = AlertAiAnalysisSettingsSource {
            resolveAlertAiAnalysisSettings(settingsStore.settings.first())
                ?: throw IllegalStateException("invalid clinical AI configuration")
        },
        credentialSource = AlertAiCredentialSource { providerId ->
            clinicalAiCredentialSource.requireCredential(providerId)
        },
        networkGate = AndroidValidatedNetworkGate(appContext),
        datasetSource = AlertAiReportDatasetSource { requestedAt ->
            clinicalReportDatasetBuilder.buildAlertAiDataset(requestedAt)
        },
        dao = db.alertAiAnalysisDao(),
        gatewayFactory = clinicalAiGatewayFactory
    )
    val episodeAlertDelivery = EpisodeAlertDeliveryStateMachine(
        store = RoomEpisodeAlertReceiptStore(db),
        mirrorMuteUntil = { until ->
            glucoseAlertStateStore.update { current -> current.copy(mutedUntilTs = until) }
        },
        clearRiskSideEffects = {
            pumpLinkMonitor.invalidateAlarmSource()
            glucoseAlertAudioController.stop()
            GlucoseAlertNotifier.cancelGlucoseAlertVibration(context.applicationContext)
            GlucoseAlertNotifier.clearPostedNotifications(context.applicationContext)
            PumpLinkNotifier.clear(context.applicationContext)
            io.aaps.copilot.data.repository.DeliveryDiagnosticNotifier.clear(context.applicationContext)
        },
        postCommitObserver = alertAiProductionDispatcher
    )
    private val glucoseAlertNotifier = GlucoseAlertNotifier(
        context = context.applicationContext,
        audioController = glucoseAlertAudioController,
        episodeDelivery = episodeAlertDelivery
    )

    val telegramRepository = io.aaps.copilot.telegram.TelegramRepository(
        io.aaps.copilot.telegram.EncryptedTelegramPersistence(
            io.aaps.copilot.security.KeystoreSecretStorage(appContext,
                io.aaps.copilot.security.RuntimeSecretStorageNamespaces.TELEGRAM)
        ),
        io.aaps.copilot.telegram.TelegramHttpApi()
    )
    val telegramDeliveryController = io.aaps.copilot.telegram.TelegramDeliveryController(
        appContext, telegramRepository, db.alertEventDao(), episodeAlertDelivery, clinicalReportRepository
    )

    private val pumpLinkNotifier = PumpLinkNotifier(appContext)
    private val deliveryDiagnosticNotifier = io.aaps.copilot.data.repository.DeliveryDiagnosticNotifier(appContext)
    private val deliveryDiagnostic = io.aaps.copilot.data.repository.DeliveryDiagnosticRepository(
        db, episodeAlertDelivery, deliveryDiagnosticNotifier::post,
        { io.aaps.copilot.data.repository.DeliveryDiagnosticNotifier.clear(appContext) }
    )
    val pumpLinkMonitor: PumpLinkHealthMonitor = PumpLinkHealthMonitor(
        scope = appScope,
        store = DataStorePumpLinkRecordStore.create(appContext, appScope),
        elapsedMs = SystemClock::elapsedRealtime,
        bootCount = { android.provider.Settings.Global.getInt(appContext.contentResolver,
            android.provider.Settings.Global.BOOT_COUNT, -1) },
        notify = pumpLinkNotifier::post,
        clearNotification = { PumpLinkNotifier.clear(appContext) },
        muteCoordinator = PumpLinkMuteCoordinator(episodeAlertDelivery::coordinateMutedSideEffect),
        onFailure = { android.util.Log.w("PumpLinkMonitor", "Monitor state unavailable: ${it.javaClass.simpleName}") }
    )

    fun acceptPumpLinkHealth(snapshot: PumpLinkSnapshot, finished: () -> Unit) {
        appScope.launch {
            try {
                pumpLinkMonitor.accept(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The monitor already exposes and reports the failure.
            } finally { finished() }
        }
    }

    val isfCrRepository = IsfCrRepository(
        db = db,
        gson = gson,
        auditLogger = auditLogger,
        glucoseCalibrationRepository = glucoseCalibrationRepository
    )

    private val acceptedSensitivityTupleRoomLoader = AcceptedSensitivityTupleRoomLoader(db)

    val sensitivityRuntimeRepository = SensitivityRuntimeRepository(
        loadedCandidates = {
            val settings = settingsStore.settings.first()
            val now = System.currentTimeMillis()
            val telemetry = db.telemetryDao().latestBySourceAndKeySinceForKeys(
                since = (now - 72L * 60L * 60L * 1_000L).coerceAtLeast(1L),
                keys = setOf(
                    "isf_value", "raw_isf", "aaps_isf", "isf",
                    "cr_value", "raw_cr", "aaps_cr", "cr"
                ).toList()
            )
            val acceptedTelemetryQualities = setOf("TRUSTED", "GOOD", "OK")
            fun latestMetric(
                source: String,
                keys: Set<String>,
                minimum: Double,
                maximum: Double
            ) = telemetry
                .asSequence()
                .filter {
                        it.source.equals(source, ignoreCase = true) &&
                        it.key in keys &&
                        it.quality.trim().uppercase() in acceptedTelemetryQualities
                }
                .mapNotNull { row ->
                    val value = row.valueDouble?.takeIf { it.isFinite() && it in minimum..maximum }
                        ?: return@mapNotNull null
                    row to value
                }
                .maxWithOrNull(
                    compareBy<Pair<io.aaps.copilot.data.local.entity.TelemetrySampleEntity, Double>> { it.first.timestamp }
                        .thenBy { it.first.id }
                )

            fun telemetryCandidate(
                source: String,
                keys: Set<String>,
                minimum: Double,
                maximum: Double,
                missingReason: String
            ): SensitivityCandidate {
                val selected = latestMetric(source, keys, minimum, maximum)
                return SensitivityCandidate(
                    value = selected?.second,
                    timestamp = selected?.first?.timestamp ?: 0L,
                    confidence = if (selected == null) 0.0 else 1.0,
                    qualityPassed = selected != null,
                    sampleCount = if (selected == null) 0 else 1,
                    coverage = if (selected == null) 0.0 else 1.0,
                    unavailableReason = if (selected == null) missingReason else null
                )
            }

            val candidateInput = db.withTransaction {
                val activeProfile = db.profileEstimateDao().active()
                Triple(
                    isfCrRepository.latestSnapshot(),
                    activeProfile,
                    requireNotNull(
                        isfCrInputGenerationOrNull(
                            modelUpdatedAt = db.isfCrModelStateDao().active()?.updatedAt,
                            profileTimestamp = activeProfile?.timestamp
                        )
                    ) { "sensitivity candidates require active ISF/CR model and profile revisions" }
                )
            }
            val evidence = candidateInput.first
            val profile = candidateInput.second
            val currentInputGeneration = candidateInput.third
            evidence?.let { snapshot ->
                requireNotNull(realtimeSnapshotInputGeneration(snapshot)) {
                    "validated ISF/CR snapshot has no input generation"
                }.also { generation ->
                    require(generation == currentInputGeneration) {
                        "validated ISF/CR snapshot does not match candidate input generation"
                    }
                }
            }
            val evidenceGatePassed = evidence?.let { snapshot ->
                snapshot.mode == io.aaps.copilot.domain.isfcr.IsfCrRuntimeMode.ACTIVE &&
                    snapshot.confidence >= settings.isfCrConfidenceThreshold.coerceIn(0.2, 0.95)
            } == true
            val evidenceIsf = evidence?.let {
                SensitivityCandidate(
                    it.isfEff,
                    it.ts,
                    it.confidence,
                    evidenceGatePassed && it.isfEvidenceCount >= settings.isfCrMinIsfEvidencePerHour,
                    it.isfEvidenceCount,
                    if (it.isfEvidenceCount > 0) 1.0 else 0.0,
                    if (evidenceGatePassed) null else "evidence_runtime_gate_${it.mode.name.lowercase()}",
                    modelRevision = currentInputGeneration.modelRevision,
                    profileRevision = currentInputGeneration.profileRevision
                )
            } ?: SensitivityCandidate(null, 0L, 0.0, false, 0, 0.0, "evidence_snapshot_missing")
            val evidenceCr = evidence?.let {
                SensitivityCandidate(
                    it.crEff,
                    it.ts,
                    it.confidence,
                    evidenceGatePassed && it.crEvidenceCount >= settings.isfCrMinCrEvidencePerHour,
                    it.crEvidenceCount,
                    if (it.crEvidenceCount > 0) 1.0 else 0.0,
                    if (evidenceGatePassed) null else "evidence_runtime_gate_${it.mode.name.lowercase()}",
                    modelRevision = currentInputGeneration.modelRevision,
                    profileRevision = currentInputGeneration.profileRevision
                )
            } ?: SensitivityCandidate(null, 0L, 0.0, false, 0, 0.0, "evidence_snapshot_missing")
            val aapsIsf = telemetryCandidate(
                "aaps_broadcast",
                setOf("isf_value", "raw_isf", "aaps_isf", "isf"),
                SensitivityRuntimeContract.isf.diagnosticMin,
                SensitivityRuntimeContract.isf.maximum,
                "aaps_candidate_missing"
            ).copy(
                modelRevision = currentInputGeneration.modelRevision,
                profileRevision = currentInputGeneration.profileRevision
            )
            val aapsCr = telemetryCandidate(
                "aaps_broadcast",
                setOf("cr_value", "raw_cr", "aaps_cr", "cr"),
                SensitivityRuntimeContract.cr.diagnosticMin,
                SensitivityRuntimeContract.cr.maximum,
                "aaps_candidate_missing"
            ).copy(
                modelRevision = currentInputGeneration.modelRevision,
                profileRevision = currentInputGeneration.profileRevision
            )
            val productionProfileThresholds = ProfileEstimatorConfig()
            val useCalculatedIsf = profile?.calculatedIsfMmolPerUnit != null &&
                profile.calculatedIsfSampleCount >= productionProfileThresholds.minIsfSamples
            val useCalculatedCr = profile?.calculatedCrGramPerUnit != null &&
                profile.calculatedCrSampleCount >= productionProfileThresholds.minCrSamples
            val nativeIsf = profile?.calculatedIsfMmolPerUnit?.takeIf { useCalculatedIsf }
                ?: profile?.isfMmolPerUnit
                ?: evidence?.isfBase
                ?: 3.0
            val nativeCr = profile?.calculatedCrGramPerUnit?.takeIf { useCalculatedCr }
                ?: profile?.crGramPerUnit
                ?: evidence?.crBase
                ?: 10.0
            val nativeIsfConfidence = profile?.calculatedConfidence?.takeIf { useCalculatedIsf }
                ?: profile?.confidence
                ?: evidence?.confidence
                ?: 0.25
            val nativeCrConfidence = profile?.calculatedConfidence?.takeIf { useCalculatedCr }
                ?: profile?.confidence
                ?: evidence?.confidence
                ?: 0.25
            val copilotIsf = SensitivityCandidate(
                value = nativeIsf.coerceIn(
                    SensitivityRuntimeContract.isf.effectiveMin,
                    SensitivityRuntimeContract.isf.maximum
                ),
                timestamp = now,
                confidence = nativeIsfConfidence.coerceIn(0.0, 1.0),
                qualityPassed = true,
                sampleCount = if (useCalculatedIsf) {
                    profile?.calculatedIsfSampleCount ?: 0
                } else {
                    profile?.isfSampleCount ?: 0
                },
                coverage = 1.0,
                unavailableReason = null,
                modelRevision = currentInputGeneration.modelRevision,
                profileRevision = currentInputGeneration.profileRevision
            )
            val copilotCr = SensitivityCandidate(
                value = nativeCr.coerceIn(
                    SensitivityRuntimeContract.cr.effectiveMin,
                    SensitivityRuntimeContract.cr.maximum
                ),
                timestamp = now,
                confidence = nativeCrConfidence.coerceIn(0.0, 1.0),
                qualityPassed = true,
                sampleCount = if (useCalculatedCr) {
                    profile?.calculatedCrSampleCount ?: 0
                } else {
                    profile?.crSampleCount ?: 0
                },
                coverage = 1.0,
                unavailableReason = null,
                modelRevision = currentInputGeneration.modelRevision,
                profileRevision = currentInputGeneration.profileRevision
            )
            SensitivityRuntimeLoadedCandidates(
                settingsRevision = settings.sensitivitySettingsRevision,
                isfPreference = settings.isfSourcePreference,
                crPreference = settings.crSourcePreference,
                isfCandidates = SensitivityCandidates(
                    aaps = aapsIsf,
                    evidence = evidenceIsf,
                    copilot = copilotIsf
                ),
                crCandidates = SensitivityCandidates(
                    aaps = aapsCr,
                    evidence = evidenceCr,
                    copilot = copilotCr
                )
            )
        },
        persistence = { entity: SensitivityRuntimeSnapshotEntity ->
            db.sensitivityRuntimeSnapshotDao().upsert(entity)
        },
        settingsRevision = { settingsStore.settings.first().sensitivitySettingsRevision },
        acceptedSnapshotLoader = { revision, atTs ->
            val settings = settingsStore.settings.first()
            if (settings.sensitivitySettingsRevision != revision) {
                null
            } else {
                acceptedSensitivityTupleRoomLoader.load(settings.sensitivityRuntimeIdentity(), atTs)?.let { accepted ->
                    AcceptedSensitivityRuntimePublication(
                        snapshot = accepted.snapshot,
                        acceptedAtTs = accepted.acceptedAtTs,
                        acceptedCycleId = accepted.snapshot.forecastCycleId
                    )
                }
            }
        }
    )

    val analyticsRepository = AnalyticsRepository(
        db = db,
        patternAnalyzer = PatternAnalyzer(),
        gson = gson,
        auditLogger = auditLogger,
        isfCrRepository = isfCrRepository,
        glucoseCalibrationRepository = glucoseCalibrationRepository
    )

    private val carbsSendThrottle = CarbsSendThrottle(
        actionCommandDao = db.actionCommandDao()
    )

    private val tempTargetSendThrottle = TempTargetSendThrottle(
        actionCommandDao = db.actionCommandDao(),
        causalClockReader = io.aaps.copilot.data.repository.CausalSafetyClockReader { nowTs ->
            val evidence = AutomationRepository.loadLocalSafetyEvidenceStatic(db, gson, nowTs)
            io.aaps.copilot.data.repository.CausalSafetyClock(
                throughTs = evidence.causalThroughTs,
                evidenceResolved = evidence.chronologyResolved
            )
        },
        managedReleaseReader = { key ->
            val fingerprint = key.removePrefix(NightscoutActionRepository.TARGET_MANAGER_IDEMPOTENCY_PREFIX)
            TempTargetSendThrottle.managedReleaseFromJournal(
                entity = db.targetManagerDao().decisionByFingerprint(TargetManagerMode.ACTIVE.name, fingerprint),
                idempotencyKey = key,
                gson = gson
            )
        }
    )

    val actionRepository = NightscoutActionRepository(
        context = context,
        db = db,
        settingsStore = settingsStore,
        apiFactory = apiFactory,
        carbsSendThrottle = carbsSendThrottle,
        tempTargetSendThrottle = tempTargetSendThrottle,
        gson = gson,
        auditLogger = auditLogger
    )

    suspend fun submitManualEatingSoon(mealKey: String): EatingSoonResult {
        if (!mealKey.startsWith("manual:meal:") || mealKey.length > 180) {
            return EatingSoonResult(MealDeliveryStatus.BLOCKED, "invalid_meal_identity")
        }
        val settings = settingsStore.settings.first()
        val command = ActionCommand(
            id = "$mealKey:eating-soon",
            type = "temp_target",
            params = mapOf(
                "targetMmol" to EatingSoonPolicy.TARGET_MMOL.toString(),
                "durationMinutes" to EatingSoonPolicy.DURATION_MINUTES.toString(),
                "reason" to "Eating Soon"
            ),
            safetySnapshot = SafetySnapshot(settings.killSwitch, false, null, 0),
            idempotencyKey = "$mealKey:eating-soon"
        )
        return actionRepository.submitManualMealTarget(command) {
            manualEatingSoonPreflightFailure()
        }
    }

    private suspend fun manualEatingSoonPreflightFailure(): String? = try {
        withManagedTargetAuthorityProof(db) {
            val started = captureLocalSafetyReadClock()
            val now = started.wallNowTs
            val settings = settingsStore.settings.first()
            val tuple = acceptedSensitivityTupleRoomLoader
                .loadLatestCommitted(settings.sensitivityRuntimeIdentity(), now)
                ?: return@withManagedTargetAuthorityProof "accepted_forecast_unavailable"
            val sample = db.glucoseDao().latestValidDistinctAtOrBefore(now, 1).singleOrNull()
            val glucose = sample?.let { point ->
                tuple.calibrationModel?.let { model ->
                    CalibrationModelAuthority.applyToGlucose(point.timestamp, point.mmol, model)
                } ?: point.mmol
            }
            val keys = listOf("sensor_quality_score", "sensor_quality_blocked", "sensor_quality_suspect_false_low")
            val sensorRows = keys.mapNotNull { key ->
                db.telemetryDao().latestBySourceAndKeyAtOrBefore("copilot_sensor_quality", key, now)
            }
            val sensorValues = sensorRows.associate { it.key to it.valueDouble }
            val sensorTrusted = sensorRows.size == keys.size &&
                sensorRows.map { it.timestamp }.distinct().size == 1 &&
                sensorRows.all { row ->
                    row.timestamp > 0 && now - row.timestamp in 0..EatingSoonPolicy.MAX_DATA_AGE_MS &&
                        row.quality in setOf("OK", "TRUSTED") && row.valueDouble?.isFinite() == true
                } && (sensorValues["sensor_quality_score"] ?: 0.0) >= 0.65 &&
                (sensorValues["sensor_quality_blocked"] ?: 1.0) < 0.5 &&
                (sensorValues["sensor_quality_suspect_false_low"] ?: 1.0) < 0.5
            val localSafety = loadLocalSafetyEvidenceAt(started)
            val finalSettings = settingsStore.settings.first()
            val finished = captureLocalSafetyReadClock()
            if (settings != finalSettings) return@withManagedTargetAuthorityProof "settings_changed"
            if (finished.wallNowTs < now || finished.monotonicNowTs - started.monotonicNowTs !in 0..5_000L) {
                return@withManagedTargetAuthorityProof "safety_read_expired"
            }
            val blockReason = EatingSoonPolicy.blockReason(EatingSoonEvidence(
                nowTs = finished.wallNowTs,
                killSwitch = finalSettings.killSwitch,
                actionsArmed = finalSettings.therapyActionsArmed,
                minTargetMmol = finalSettings.safetyMinTargetMmol,
                maxTargetMmol = finalSettings.safetyMaxTargetMmol,
                glucoseTs = sample?.timestamp,
                glucoseMmol = glucose,
                forecastGeneratedAt = tuple.accepted.generationTimestamp,
                forecasts = tuple.accepted.forecastsByHorizon.mapValues { (_, forecast) ->
                    EatingSoonForecast(forecast.valueMmol, forecast.ciLow, forecast.ciHigh)
                },
                sensorTrusted = sensorTrusted,
                chronologyResolved = localSafety.chronologyResolved
            ))
            if (blockReason != null) auditLogger.info(
                "manual_meal_target_preflight_evidence",
                mapOf(
                    "reason" to blockReason,
                    "glucoseAgeMs" to sample?.let { finished.wallNowTs - it.timestamp },
                    "forecastAgeMs" to tuple.accepted.generationTimestamp?.let { finished.wallNowTs - it }
                )
            )
            blockReason
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        "safety_lookup_failed"
    }

    val targetManagerRepository = TargetManagerRepository(
        dao = db.targetManagerDao(),
        gson = gson,
        dispatcher = TargetCommandDispatcher { candidate ->
            val currentSettings = settingsStore.settings.first()
            dispatchManagedTargetAfterPreflightStatic(
                preflight = { managedTargetPreflightFailure(candidate, currentSettings) },
                onPreflightBlocked = { preflightFailure ->
                    auditLogger.warn(
                        "target_manager_dispatch_preflight_blocked",
                        mapOf(
                            "reason" to preflightFailure,
                            "semanticFingerprint" to candidate.semanticFingerprint
                        )
                    )
                },
                deliver = {
                    val localSafetyEvidence = loadLocalSafetyEvidenceAt(
                        captureLocalSafetyReadClock()
                    )
                    val command = ActionCommand(
                        id = UUID.randomUUID().toString(),
                        type = "temp_target",
                        params = mapOf(
                            "targetMmol" to candidate.targetMmol.toString(),
                            "durationMinutes" to candidate.durationMinutes.toString(),
                            "reason" to candidate.reason,
                            "targetIntent" to candidate.intent.name,
                            "ownerRuleId" to candidate.ownerRuleId,
                            "semanticFingerprint" to candidate.semanticFingerprint
                        ),
                        safetySnapshot = SafetySnapshot(
                            killSwitch = currentSettings.killSwitch,
                            dataFresh = true,
                            activeTempTargetMmol = null,
                            actionsLast6h = localSafetyEvidence.actionsLast6h
                        ),
                        idempotencyKey = candidate.idempotencyKey
                    )
                    actionRepository.submitOrRetryTempTarget(command) {
                        managedTargetPreflightFailure(
                            candidate = candidate,
                            settings = settingsStore.settings.first()
                        )
                    }
                }
            )
        },
        deliveryStatusProvider = TargetDeliveryStatusProvider { idempotencyKey ->
            when (actionRepository.reconcileTempTargetDelivery(idempotencyKey)) {
                NightscoutActionRepository.TempTargetDeliveryReconciliation.SENT -> "sent"
                NightscoutActionRepository.TempTargetDeliveryReconciliation.CONFIRMED_ABSENT -> "absent"
                NightscoutActionRepository.TempTargetDeliveryReconciliation.UNKNOWN -> "unknown"
            }
        }
    )

    private suspend fun managedTargetPreflightFailure(
        candidate: TargetCommandCandidate,
        settings: AppSettings
    ): String? = withManagedTargetAuthorityProof(db) {
        managedTargetPreflightFailureInTransaction(candidate, settings)
    }

    private suspend fun managedTargetPreflightFailureInTransaction(
        candidate: TargetCommandCandidate,
        settings: AppSettings
    ): String? {
        val safetyReadClock = captureLocalSafetyReadClock()
        val now = safetyReadClock.wallNowTs
        if (settings.targetManagerMode != TargetManagerMode.ACTIVE) return "manager_not_active"
        if (settings.killSwitch) return "kill_switch"
        managedTargetBaselineArmPreflightFailureStatic(settings.therapyActionsArmed)?.let { return it }
        if (
            !candidate.targetMmol.isFinite() ||
            candidate.targetMmol !in settings.safetyMinTargetMmol..settings.safetyMaxTargetMmol ||
            candidate.durationMinutes !in 15..120
        ) return "current_safety_bounds_changed"

        val acceptedTuple = try {
            acceptedSensitivityTupleRoomLoader
                .loadLatestCommitted(settings.sensitivityRuntimeIdentity(), now)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            return "accepted_forecast_lookup_failed"
        }
        val acceptedSensitivityCycleId = acceptedTuple?.snapshot?.forecastCycleId
        val latestGlucoseTs = try {
            db.glucoseDao().latestValidDistinctAtOrBefore(now, 1).singleOrNull()?.timestamp
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            return "current_glucose_lookup_failed"
        }
        val glucoseFreshnessMs = try {
            Math.multiplyExact(
                AutomationRepository.resolveEffectiveStaleMaxMinutesStatic(settings).toLong(),
                60_000L
            )
        } catch (_: ArithmeticException) {
            return "current_glucose_freshness_invalid"
        }
        managedTargetFreshnessPreflightFailureStatic(
            candidate = candidate,
            acceptedSensitivityCycleId = acceptedSensitivityCycleId,
            latestGlucoseTs = latestGlucoseTs,
            nowTs = now,
            glucoseFreshnessMs = glucoseFreshnessMs,
            candidateMaxAgeMs = MANAGED_TARGET_MAX_DISPATCH_AGE_MS
        )?.let { return it }

        val provenance = candidate.baseProvenance ?: return "base_provenance_missing"
        val schedule = settings.baseTargetSchedule
        if (provenance.scheduleRevision != schedule.revision) return "schedule_revision_changed"
        val currentInterval = try {
            BaseTargetSchedulePolicy.resolveManual(
                schedule = schedule,
                instant = Instant.ofEpochMilli(now),
                zoneId = ZoneId.systemDefault()
            )
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Throwable) {
            return "schedule_resolution_failed"
        }
        if (provenance.intervalId != currentInterval.intervalId) return "schedule_interval_changed"

        if (schedule.autoEnabled) {
            val latestRun = try {
                circadianTargetRepository.latestCompletedRun(schedule.revision)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Error) {
                throw fatal
            } catch (_: Throwable) {
                return "adjustment_run_lookup_failed"
            }
            managedAdjustmentRunPreflightFailureStatic(
                autoEnabled = true,
                latestRunId = latestRun?.runId,
                latestRunStatus = latestRun?.status,
                candidateRunId = provenance.adjustmentRunId
            )?.let { return it }
        } else {
            managedAdjustmentRunPreflightFailureStatic(
                autoEnabled = false,
                latestRunId = null,
                latestRunStatus = null,
                candidateRunId = provenance.adjustmentRunId
            )?.let { return it }
        }

        val observationClock = captureLocalSafetyReadClock()
        val observationNow = observationClock.wallNowTs
        val localSafetyEvidence = try {
            loadLocalSafetyEvidenceAt(observationClock)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Throwable) {
            return "active_target_lookup_failed"
        }
        if (!localSafetyEvidence.chronologyResolved) {
            return "local_safety_chronology_unresolved"
        }
        val currentObservation = TargetOwnershipPolicy.capture(
            activeAapsTarget = localSafetyEvidence.activeAapsTarget,
            copilotPriorityEnabled = settings.targetManagerCopilotPriorityEnabled,
            priorityRevision = settings.targetManagerPolicyRevision
        )
        managedTargetOwnershipPreflightFailureStatic(
            candidate = candidate,
            currentObservation = currentObservation,
            nowTs = observationNow
        )?.let { return it }

        val replaceableSentIdempotencyKey = replaceableObservedManualSentIdempotencyKeyStatic(
            candidate = candidate,
            currentObservation = currentObservation,
            nowTs = observationNow
        )
        val manualTargetConflict = try {
            val manualCommands = db.actionCommandDao().byTypeAndIdempotencyPrefixSince(
                type = "temp_target",
                idempotencyPrefix = "${NightscoutActionRepository.MANUAL_IDEMPOTENCY_PREFIX}%",
                since = observationNow - MANAGED_TARGET_ACTIVE_LOOKBACK_MS
            )
            val observed = currentObservation.activeAapsTarget
            val observedCommand = observed?.idempotencyKey?.takeIf {
                (observed.ownership == ActiveTargetOwnership.TARGET_MANAGER ||
                    EatingSoonPolicy.isActiveConfirmedTarget(observed, observationNow)) && manualCommands.any { command ->
                    command.status == NightscoutActionRepository.STATUS_SENT &&
                        io.aaps.copilot.data.repository.isCanonicalEatingSoonCommand(command, gson)
                }
            }?.let { db.actionCommandDao().forTargetObservationProof(it).singleOrNull() }
            val confirmedSupersedingTarget = io.aaps.copilot.data.repository.ConfirmedSupersedingTarget
                .fromObservation(observed, observedCommand, observationNow, gson)
            hasActiveManualTempTargetStatic(
                commands = manualCommands,
                now = observationNow,
                gson = gson,
                replaceableSentIdempotencyKey = replaceableSentIdempotencyKey,
                confirmedSupersedingTarget = confirmedSupersedingTarget
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Throwable) {
            return "manual_command_lookup_failed"
        }
        if (manualTargetConflict) return "manual_target_active_or_pending"

        val finalSettings = try {
            settingsStore.settings.first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            return "target_manager_settings_recheck_failed"
        }
        if (!managedTargetSettingsIdentityMatchesStatic(settings, finalSettings)) {
            return "target_manager_settings_changed"
        }
        return managedTargetFinalAuthorityFailureStatic(
            candidate = candidate,
            schedule = finalSettings.baseTargetSchedule,
            acceptedCycleId = acceptedSensitivityCycleId,
            forecastGenerationTs = acceptedTuple?.accepted?.generationTimestamp,
            latestGlucoseTs = latestGlucoseTs,
            observation = currentObservation,
            started = safetyReadClock,
            finished = captureLocalSafetyReadClock(),
            glucoseFreshnessMs = glucoseFreshnessMs
        )
    }

    private fun captureLocalSafetyReadClock() = ManagedTargetReadClock(
        wallNowTs = System.currentTimeMillis(),
        monotonicNowTs = SystemClock.elapsedRealtime()
    )

    private suspend fun loadLocalSafetyEvidenceAt(
        clock: ManagedTargetReadClock
    ): AutomationRepository.LocalSafetyEvidence = AutomationRepository.loadLocalSafetyEvidenceStatic(
        db = db,
        gson = gson,
        nowTs = clock.wallNowTs,
        monotonicNowTs = clock.monotonicNowTs
    )

    private val uamEventStore = UamEventStore(db.uamInferenceEventDao())
    private val uamExportReservationStore = ActionCommandUamExportReservationStore(
        actionCommandDao = db.actionCommandDao(),
        gson = gson
    )
    private val uamExportCoordinator = UamExportCoordinator(
        gateway = actionRepository,
        auditLogger = auditLogger,
        reservationStore = uamExportReservationStore
    )
    private val uamInferenceEngine = UamInferenceEngine()

    private val predictionEngine: PredictionEngine = HybridPredictionEngine(
        enableEnhancedPredictionV3 = true,
        enableUam = true,
        enableUamVirtualMealFit = true
    )

    val mealEnergyOverrideRepository = MealEnergyOverrideRepository(
        therapyDao = db.therapyDao(),
        mealEnergyOverrideDao = db.mealEnergyOverrideDao(),
        gson = gson
    )

    val energyProfileRepository = EnergyProfileRepository(
        energyProfileDao = db.energyProfileDao(),
        mealEnergyOverrideRepository = mealEnergyOverrideRepository,
        inferenceDataSource = EnergyProfileInferenceDataSource(
            loadSettings = { settingsStore.settings.first().energyProfile },
            loadTherapy = db.therapyDao()::between,
            loadGlucose = db.glucoseDao()::between,
            loadTelemetry = { fromTs, throughTs ->
                db.telemetryDao().since(fromTs).filter { it.timestamp <= throughTs }
            },
            decodeTherapy = { row -> row.toDomain(gson) }
        )
    )

    private val ruleEngine = RuleEngine(
        rules = listOf(
            AdaptiveTargetControllerRule(),
            PostHypoReboundGuardRule(),
            PatternAdaptiveTargetRule(),
            SegmentProfileGuardRule()
        ),
        safetyPolicy = SafetyPolicy()
    )

    suspend fun refreshGlucoseWidget() {
        CopilotGlucoseWidgetUpdater.updateAll(appContext)
    }

    val automationRepository = AutomationRepository(
        db = db,
        settingsStore = settingsStore,
        syncRepository = syncRepository,
        exportRepository = exportRepository,
        autoConnectRepository = autoConnectRepository,
        rootDbRepository = rootDbRepository,
        analyticsRepository = analyticsRepository,
        isfCrRepository = isfCrRepository,
        sensitivityRuntimeRepository = sensitivityRuntimeRepository,
        glucoseCalibrationRepository = glucoseCalibrationRepository,
        glucoseAlertStateStore = glucoseAlertStateStore,
        episodeAlertDelivery = episodeAlertDelivery,
        glucoseAlertNotifier = glucoseAlertNotifier,
        actionRepository = actionRepository,
        targetManagerRepository = targetManagerRepository,
        circadianTargetRepository = circadianTargetRepository,
        energyProfileRepository = energyProfileRepository,
        predictionEngine = predictionEngine,
        uamInferenceEngine = uamInferenceEngine,
        uamEventStore = uamEventStore,
        uamExportCoordinator = uamExportCoordinator,
        ruleEngine = ruleEngine,
        gson = gson,
        auditLogger = auditLogger,
        onWidgetDataChanged = ::refreshGlucoseWidget,
        deliveryDiagnostic = deliveryDiagnostic
    )

    val aiChatRepository = AiChatRepository(
        settingsStore = settingsStore,
        credentialProvider = openAiCredentialProvider,
        auditLogger = auditLogger
    )

    val insightsRepository = InsightsRepository(
        context = context,
        db = db,
        settingsStore = settingsStore,
        apiFactory = apiFactory,
        auditLogger = auditLogger,
        aiChatRepository = aiChatRepository,
        glucoseCalibrationRepository = glucoseCalibrationRepository
    )

    init {
        clinicalReportRepository.initialize()
        telegramDeliveryController.start(appScope)

        appScope.launch {
            try {
                pumpLinkMonitor.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the clinical pipeline independent of technical monitor storage.
            }
        }

        appScope.launch {
            settingsStore.settings.first()
            reconcilePendingContextEvents("startup")
        }

        appScope.launch {
            try {
                val initialized = withTimeoutOrNull(SENSITIVITY_STARTUP_TIMEOUT_MS) {
                    sensitivityRuntimeRepository.hydrateFromAcceptedTuple()
                    true
                } ?: false
                if (!initialized) {
                    auditLogger.warn(
                        "sensitivity_runtime_startup_timed_out",
                        mapOf("timeoutMs" to SENSITIVITY_STARTUP_TIMEOUT_MS)
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                auditLogger.warn(
                    "sensitivity_runtime_startup_failed",
                    mapOf("error" to (error.message ?: error.javaClass.simpleName))
                )
            }
        }

        appScope.launch {
            openAiCredentialProvider.ensureMigrated()
            ClinicalAiProviderId.values()
                .filterNot { it == ClinicalAiProviderId.OPENAI }
                .forEach { providerId ->
                    clinicalAiCredentialProvider.ensureMigrated(providerId)
                }
        }

        powerSaveSettingsJob = appScope.launch {
            settingsStore.ensureUamThreeModeConsentV1()
            settingsStore.settings.collectLatest { settings ->
                PowerSaveRuntimeState.update(settings)
                TherapyActionRuntimeState.update(settings)
                TherapyActionTransportGate.publishState(settings.therapyActionsArmed)
                if (!settings.therapyActionsArmed) {
                    WorkScheduler.cancelRuntimeWork(context.applicationContext)
                    stopRuntimeControllers(LocalNightscoutRuntimeReason.THERAPY_ACTIONS_DISARMED)
                    LocalNightscoutServiceController.stop(
                        context.applicationContext,
                        LocalNightscoutRuntimeReason.THERAPY_ACTIONS_DISARMED
                    )
                    return@collectLatest
                }
                if (PowerSaveController.isActive(settings)) {
                    WorkScheduler.cancelRuntimeWork(context.applicationContext)
                    WorkScheduler.schedulePowerSaveResume(
                        context = context.applicationContext,
                        untilMs = settings.powerSaveUntilMs
                    )
                    stopRuntimeControllers(LocalNightscoutRuntimeReason.POWER_SAVE_ACTIVE)
                    LocalNightscoutServiceController.stop(
                        context.applicationContext,
                        LocalNightscoutRuntimeReason.POWER_SAVE_ACTIVE
                    )
                } else {
                    WorkScheduler.cancelPowerSaveResume(context.applicationContext)
                    WorkScheduler.schedule(context.applicationContext)
                }
            }
        }

        appScope.launch {
            var previousAutoEnabled: Boolean? = null
            settingsStore.settings.collectLatest { settings ->
                val schedule = settings.baseTargetSchedule
                val priorAutoEnabled = previousAutoEnabled
                previousAutoEnabled = schedule.autoEnabled
                if (!settings.therapyActionsArmed) {
                    return@collectLatest
                }

                settings.baseTargetScheduleRecoveryReason?.let { reason ->
                    CircadianTargetAudit.runBestEffort {
                        auditLogger.warnThrottled(
                            throttleKey = "base_target_schedule_recovery",
                            intervalMs = BASE_TARGET_RECOVERY_AUDIT_INTERVAL_MS,
                            message = "base_target_schedule_recovered",
                            metadata = mapOf(
                                "reason" to reason,
                                "scheduleRevision" to schedule.revision
                            )
                        )
                    }
                }

                if (!schedule.autoEnabled || PowerSaveController.isActive(settings)) {
                    return@collectLatest
                }
                val now = ZonedDateTime.now(ZoneId.systemDefault())
                val lastCompletedDate = CircadianTargetStartup.loadLastCompletedDateOrNull(
                    load = {
                        circadianTargetRepository.latestCompletedLocalRunDate(schedule.revision)
                    },
                    onFailure = { error ->
                        auditLogger.warn(
                            "circadian_target_catch_up_lookup_failed",
                            mapOf(
                                "scheduleRevision" to schedule.revision,
                                "failureType" to error.javaClass.simpleName
                            )
                        )
                    }
                )
                val shouldTrigger = CircadianTargetWorkerPolicy.becameEnabled(
                    previous = priorAutoEnabled,
                    current = schedule.autoEnabled
                ) || CircadianTargetSchedule.needsCatchUp(
                    autoEnabled = schedule.autoEnabled,
                    now = now,
                    lastCompletedDate = lastCompletedDate
                )
                if (shouldTrigger) {
                    WorkScheduler.triggerCircadianTargetEvaluation(context.applicationContext)
                }
            }
        }

        appScope.launch {
            val adaptiveMigrationEnabled = settingsStore.ensureAdaptiveControllerDefaultEnabled()
            val uamMigration = settingsStore.ensureBoundedUamExportV2()
            val uamConsentMigration = settingsStore.ensureUamThreeModeConsentV1()
            val analyticsRetentionMigrated = runCatching {
                automationRepository.ensureAnalyticsRetentionDefault30Days()
            }.onFailure { error ->
                auditLogger.warn(
                    "analytics_retention_default_migration_failed",
                    mapOf("failureType" to error.javaClass.simpleName)
                )
            }.getOrDefault(false)
            val uiStyleMigrated = settingsStore.ensureUiStyleDefaultMidnightGlass()
            val uiStyleRedesignMigratedFrom = settingsStore.ensureLegacyUiStylesPromotedToMidnightGlass()
            if (
                adaptiveMigrationEnabled ||
                uamConsentMigration != null ||
                analyticsRetentionMigrated ||
                uiStyleMigrated ||
                uiStyleRedesignMigratedFrom != null
            ) {
                if (
                    TherapyActionRuntimeState.isArmed() &&
                    !PowerSaveRuntimeState.isActive()
                ) {
                    WorkScheduler.triggerReactiveAutomation(context.applicationContext)
                }
            }
            if (adaptiveMigrationEnabled) {
                auditLogger.info("adaptive_controller_default_enabled", emptyMap<String, Any>())
            }
            if (uamMigration != null) {
                auditLogger.info(
                    "uam_export_v2_bounded_migrated",
                    mapOf(
                        "oldMode" to uamMigration.oldMode.name,
                        "newMode" to uamMigration.newMode.name,
                        "oldDryRun" to uamMigration.oldDryRun,
                        "newDryRun" to uamMigration.newDryRun,
                        "oldMaxBackdateMinutes" to uamMigration.oldMaxBackdateMinutes,
                        "newMaxBackdateMinutes" to uamMigration.newMaxBackdateMinutes
                    )
                )
            }
            if (uamConsentMigration != null) {
                auditLogger.warn(
                    "uam_export_three_mode_consent_migrated",
                    mapOf(
                        "oldMode" to uamConsentMigration.oldMode.name,
                        "newMode" to uamConsentMigration.newMode.name,
                        "oldDryRun" to uamConsentMigration.oldDryRun,
                        "newDryRun" to uamConsentMigration.newDryRun
                    )
                )
            }
            if (analyticsRetentionMigrated) {
                auditLogger.info(
                    "analytics_retention_default_migrated",
                    mapOf("retentionDays" to 30)
                )
            }
            if (uiStyleMigrated) {
                auditLogger.info(
                    "ui_style_default_migrated",
                    mapOf("uiStyle" to "MIDNIGHT_GLASS")
                )
            }
            if (uiStyleRedesignMigratedFrom != null) {
                auditLogger.info(
                    "ui_style_redesign_migrated",
                    mapOf(
                        "fromUiStyle" to uiStyleRedesignMigratedFrom,
                        "uiStyle" to "MIDNIGHT_GLASS"
                    )
                )
            }
        }
    }

    private suspend fun reconcilePendingContextEvents(trigger: String) {
        try {
            val startup = trigger == "startup"
            val summary = reconcileContextEventsAndInvalidate(
                startup = startup,
                reconcile = {
                    if (startup) {
                        contextEventSyncCoordinator.reconcileStartupPending()
                    } else {
                        contextEventSyncCoordinator.reconcileAllPending()
                    }
                },
                invalidate = { source ->
                    clinicalInputInvalidationCoordinator.invalidatePersistedInput(source)
                }
            )
            if (summary.processedCount > 0 || summary.remainingPendingCount > 0) {
                auditLogger.info(
                    "context_event_pending_reconciliation",
                    mapOf(
                        "trigger" to trigger,
                        "processed" to summary.processedCount,
                        "acknowledged" to summary.acknowledgedCount,
                        "applied" to summary.appliedCount,
                        "failed" to summary.failedCount,
                        "stillPending" to summary.stillPendingCount,
                        "malformed" to summary.malformedCount,
                        "remaining" to summary.remainingPendingCount,
                        "startupSnapshot" to summary.startupSnapshotCount,
                        "safetyCapReached" to summary.safetyCapReached
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            auditLogger.warn(
                "context_event_pending_reconciliation_failed",
                mapOf(
                    "trigger" to trigger,
                    "error" to error.javaClass.simpleName.take(80)
                )
            )
        }
    }

    fun startLocalActivitySensors() {
        localActivitySensorCollector.start()
    }

    fun stopLocalActivitySensors() {
        localActivitySensorCollector.stop()
    }

    fun startHealthConnectCollection() {
        if (!healthConnectEnabled) return
        healthConnectActivityCollector.start()
    }

    fun stopHealthConnectCollection() {
        if (!healthConnectEnabled) return
        healthConnectActivityCollector.stop()
    }

    fun startRuntimeControllers() {
        if (PowerSaveRuntimeState.isActive() || !TherapyActionRuntimeState.isArmed()) return
        if (runtimeControllersActive) return
        runtimeControllersActive = true
        startLocalActivitySensors()
        if (healthConnectEnabled) {
            startHealthConnectCollection()
        }
        if (localNightscoutSettingsJob == null) {
            localNightscoutSettingsJob = appScope.launch {
                settingsStore.settings.collectLatest { settings ->
                    localNightscoutServer.update(
                        enabled = LocalNightscoutUpgradeMigrationGate.canEnable(
                            requestedEnabled = settings.localNightscoutEnabled,
                            migrationAcknowledged =
                                settings.localNightscoutLegacyMigrationAcknowledged
                        ),
                        port = settings.localNightscoutPort
                    )
                }
            }
        }
    }

    internal suspend fun resetLocalNightscoutIdentity(
        confirmed: Boolean
    ): LocalNightscoutIdentityResetResult = LocalNightscoutIdentityResetCoordinator(
        stopServer = localNightscoutServer::stop,
        disableIntegration = {
            settingsStore.update { current -> current.copy(localNightscoutEnabled = false) }
        },
        resetIdentity = { LocalNightscoutTls.resetIdentity(appContext) },
        runtimeState = LocalNightscoutRuntimeState
    ).reset(confirmed)

    fun stopRuntimeControllers(
        reason: LocalNightscoutRuntimeReason = LocalNightscoutRuntimeReason.SERVICE_STOPPED
    ) {
        localNightscoutServer.stop(reason)
        if (!runtimeControllersActive) return
        runtimeControllersActive = false
        localNightscoutSettingsJob?.cancel()
        localNightscoutSettingsJob = null
        stopLocalActivitySensors()
        if (healthConnectEnabled) {
            stopHealthConnectCollection()
        }
    }

    internal suspend fun migrateTherapyActionBootstrapForInPlaceUpdate() {
        if (settingsStore.isTherapyActionBootstrapEvaluated()) return
        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.packageManager.getPackageInfo(
                    appContext.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            }
        }.getOrNull() ?: return
        val evidence = runCatching {
            db.actionCommandDao().automaticSentTargetEvidenceBefore(packageInfo.lastUpdateTime)
        }.getOrNull() ?: return
        val shouldAutoArm = TherapyActionBootstrapMigrationPolicy.shouldAutoArm(
            TherapyActionBootstrapMigrationInput(
                alreadyEvaluatedForInstall = false,
                firstInstallTimeMs = packageInfo.firstInstallTime,
                lastUpdateTimeMs = packageInfo.lastUpdateTime,
                sentCommandCount = evidence.commandCount,
                earliestSentCommandMs = evidence.earliestTimestamp,
                latestSentCommandMs = evidence.latestTimestamp
            )
        )
        if (!shouldAutoArm) return

        val migrationResult = settingsStore.tryAutoArmTherapyActionsMigration()
        if (migrationResult != TherapyActionBootstrapMigrationResult.ARMED) return
        TherapyActionRuntimeState.setArmed(true)
        auditLogger.warn(
            "therapy_actions_bootstrap_migrated_in_place",
            mapOf(
                "sentCommandCount" to evidence.commandCount,
                "earliestSentCommandMs" to evidence.earliestTimestamp,
                "latestSentCommandMs" to evidence.latestTimestamp,
                "firstInstallTimeMs" to packageInfo.firstInstallTime,
                "lastUpdateTimeMs" to packageInfo.lastUpdateTime
            )
        )
        WorkScheduler.schedule(appContext)
        WorkScheduler.triggerReactiveAutomation(appContext)
    }

    private companion object {
        const val BASE_TARGET_RECOVERY_AUDIT_INTERVAL_MS = 60L * 60L * 1_000L
        const val MANAGED_TARGET_MAX_DISPATCH_AGE_MS = 2L * 60L * 1_000L
        const val MANAGED_TARGET_ACTIVE_LOOKBACK_MS = 12L * 60L * 60L * 1_000L
        const val SENSITIVITY_STARTUP_TIMEOUT_MS = 5_000L
    }
}

internal suspend fun dispatchManagedTargetAfterPreflightStatic(
    preflight: suspend () -> String?,
    onPreflightBlocked: suspend (String) -> Unit,
    deliver: suspend () -> Boolean
): Boolean {
    val reason = preflight() ?: return deliver()
    onPreflightBlocked(reason)
    throw TargetCommandPreflightBlockedException(
        TargetCommandPreflightFailure.requireReason(reason)
    )
}

internal fun hasActiveManualTempTargetStatic(
    commands: List<ActionCommandEntity>,
    now: Long,
    gson: Gson,
    pendingLookbackMs: Long = 12L * 60L * 60L * 1_000L,
    replaceableSentIdempotencyKey: String? = null,
    confirmedSupersedingTarget: io.aaps.copilot.data.repository.ConfirmedSupersedingTarget? = null
): Boolean = commands.any { command ->
    if (
        !command.type.equals("temp_target", ignoreCase = true) ||
        !command.idempotencyKey.startsWith(NightscoutActionRepository.MANUAL_IDEMPOTENCY_PREFIX) ||
        now - command.timestamp !in 0L..pendingLookbackMs
    ) {
        return@any false
    }
    when (command.status) {
        NightscoutActionRepository.STATUS_PENDING -> true
        NightscoutActionRepository.STATUS_SENT -> {
            if (io.aaps.copilot.data.repository.isSupersededEatingSoonCommand(
                    command, confirmedSupersedingTarget, now, gson)) return@any false
            val payload = runCatching {
                gson.fromJson(command.payloadJson, com.google.gson.JsonObject::class.java)
            }.getOrNull() ?: return@any true
            val durationMinutes = listOf("durationMinutes", "duration")
                .firstNotNullOfOrNull { key ->
                    payload.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()
                }
                ?: return@any true
            if (durationMinutes <= 0) return@any false
            val expiresAt = runCatching {
                Math.addExact(
                    command.timestamp,
                    Math.multiplyExact(durationMinutes.toLong(), 60_000L)
                )
            }.getOrNull() ?: return@any true
            expiresAt > now && command.idempotencyKey != replaceableSentIdempotencyKey
        }
        NightscoutActionRepository.STATUS_BLOCKED -> false
        else -> true
    }
}

internal fun managedTargetOwnershipPreflightFailureStatic(
    candidate: TargetCommandCandidate,
    currentObservation: TargetCommandObservation,
    nowTs: Long
): String? {
    TargetOwnershipPolicy.preflightFailure(candidate, currentObservation, nowTs)?.let { return it }
    val active = currentObservation.activeAapsTarget ?: return null
    EatingSoonPolicy.replacementFailure(active, nowTs, candidate.intent, candidate.targetMmol)?.let { return it }
    val ownership: ActiveTargetOwnership? = active.ownership
    return when {
        ownership == null -> TargetOwnershipPolicy.TARGET_OBSERVATION_INVALID
        ownership == ActiveTargetOwnership.TARGET_MANAGER -> null
        currentObservation.copilotPriorityEnabled -> null
        ownership == ActiveTargetOwnership.LEGACY_COPILOT -> "legacy_target_active"
        else -> "manual_or_foreign_target_active"
    }
}

internal fun replaceableObservedManualSentIdempotencyKeyStatic(
    candidate: TargetCommandCandidate,
    currentObservation: TargetCommandObservation,
    nowTs: Long
): String? {
    if (!currentObservation.copilotPriorityEnabled) return null
    if (TargetOwnershipPolicy.preflightFailure(candidate, currentObservation, nowTs) != null) return null
    val active = currentObservation.activeAapsTarget ?: return null
    if (active.ownership != ActiveTargetOwnership.MANUAL_OR_FOREIGN) return null
    return active.idempotencyKey?.takeIf {
        it.startsWith(NightscoutActionRepository.MANUAL_IDEMPOTENCY_PREFIX)
    }
}

internal fun managedTargetFreshnessPreflightFailureStatic(
    candidate: TargetCommandCandidate,
    acceptedSensitivityCycleId: String?,
    latestGlucoseTs: Long?,
    nowTs: Long,
    glucoseFreshnessMs: Long,
    candidateMaxAgeMs: Long = 2L * 60L * 1_000L
): String? {
    val candidateAge = try {
        Math.subtractExact(nowTs, candidate.generatedAt)
    } catch (_: ArithmeticException) {
        return "candidate_stale"
    }
    if (candidate.generatedAt <= 0L || candidateAge !in 0L..candidateMaxAgeMs) {
        return "candidate_stale"
    }
    val candidateCycleId = candidate.sensitivityCycleId?.takeIf(String::isNotBlank)
        ?: return "candidate_sensitivity_cycle_missing"
    val currentCycleId = acceptedSensitivityCycleId?.takeIf(String::isNotBlank)
        ?: return "accepted_forecast_missing_or_stale"
    if (candidateCycleId != currentCycleId) return "accepted_forecast_cycle_changed"
    val glucoseTs = latestGlucoseTs ?: return "current_glucose_missing"
    if (glucoseFreshnessMs < 0L) return "current_glucose_freshness_invalid"
    val glucoseAge = try {
        Math.subtractExact(nowTs, glucoseTs)
    } catch (_: ArithmeticException) {
        return "current_glucose_stale"
    }
    if (glucoseAge !in 0L..glucoseFreshnessMs) return "current_glucose_stale"
    if (glucoseTs > candidate.generatedAt) return "current_glucose_newer_than_candidate"
    return null
}

internal fun managedTargetBaselineArmPreflightFailureStatic(
    therapyActionsArmed: Boolean
): String? = if (therapyActionsArmed) null else "therapy_actions_not_armed"

internal fun managedTargetSettingsIdentityMatchesStatic(
    expected: AppSettings,
    current: AppSettings
): Boolean = expected.targetManagerMode == current.targetManagerMode &&
    expected.killSwitch == current.killSwitch &&
    expected.therapyActionsArmed == current.therapyActionsArmed &&
    expected.safetyMinTargetMmol.toRawBits() == current.safetyMinTargetMmol.toRawBits() &&
    expected.safetyMaxTargetMmol.toRawBits() == current.safetyMaxTargetMmol.toRawBits() &&
    expected.targetManagerCopilotPriorityEnabled == current.targetManagerCopilotPriorityEnabled &&
    expected.targetManagerPolicyRevision == current.targetManagerPolicyRevision &&
    expected.baseTargetSchedule == current.baseTargetSchedule &&
    expected.staleDataMaxMinutes == current.staleDataMaxMinutes &&
    expected.adaptiveControllerEnabled == current.adaptiveControllerEnabled &&
    expected.adaptiveControllerSafetyProfile == current.adaptiveControllerSafetyProfile &&
    expected.adaptiveControllerStaleMaxMinutes == current.adaptiveControllerStaleMaxMinutes &&
    expected.sensitivityRuntimeIdentity() == current.sensitivityRuntimeIdentity()

internal fun clinicalEventTherapyLookbackFrom(fromTs: Long): Long =
    (fromTs - CLINICAL_EVENT_MAX_LOOKBACK_MS).takeIf { fromTs >= CLINICAL_EVENT_MAX_LOOKBACK_MS } ?: 0L

private const val CLINICAL_EVENT_MAX_LOOKBACK_MS = 7L * 24L * 60L * 60L * 1_000L

internal fun managedAdjustmentRunPreflightFailureStatic(
    autoEnabled: Boolean,
    latestRunId: String?,
    latestRunStatus: String?,
    candidateRunId: String?
): String? {
    if (!autoEnabled) {
        return if (candidateRunId == null) null else "unexpected_adjustment_run"
    }
    if (latestRunId.isNullOrBlank()) {
        return if (candidateRunId == null) null else "adjustment_run_changed"
    }
    val runState = latestRunStatus
        ?.let { raw -> runCatching { CircadianAutoState.valueOf(raw) }.getOrNull() }
        ?: return "adjustment_run_changed"
    if (runState == CircadianAutoState.ACTIVE) {
        return if (candidateRunId == latestRunId) null else "adjustment_run_changed"
    }
    return if (candidateRunId == null || candidateRunId == latestRunId) {
        null
    } else {
        "adjustment_run_changed"
    }
}
