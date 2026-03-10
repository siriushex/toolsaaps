package io.aaps.copilot.service

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.CopilotMigrations
import io.aaps.copilot.data.repository.AapsExportRepository
import io.aaps.copilot.data.repository.AapsAutoConnectRepository
import io.aaps.copilot.data.repository.AnalyticsRepository
import io.aaps.copilot.data.repository.AiChatRepository
import io.aaps.copilot.data.repository.AuditLogger
import io.aaps.copilot.data.repository.AutomationRepository
import io.aaps.copilot.data.repository.BroadcastIngestRepository
import io.aaps.copilot.data.repository.CarbsSendThrottle
import io.aaps.copilot.data.repository.IsfCrRepository
import io.aaps.copilot.data.repository.InsightsRepository
import io.aaps.copilot.data.repository.NightscoutAapsCarbGateway
import io.aaps.copilot.data.repository.NightscoutActionRepository
import io.aaps.copilot.data.repository.RootDbExperimentalRepository
import io.aaps.copilot.data.repository.SyncRepository
import io.aaps.copilot.data.repository.TempTargetSendThrottle
import io.aaps.copilot.data.repository.UamEventStore
import io.aaps.copilot.data.repository.UamExportCoordinator
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.domain.predict.PatternAnalyzer
import io.aaps.copilot.domain.predict.PredictionEngine
import io.aaps.copilot.domain.predict.UamInferenceEngine
import io.aaps.copilot.domain.rules.PatternAdaptiveTargetRule
import io.aaps.copilot.domain.rules.PostHypoReboundGuardRule
import io.aaps.copilot.domain.rules.AdaptiveTargetControllerRule
import io.aaps.copilot.domain.rules.RuleEngine
import io.aaps.copilot.domain.rules.SegmentProfileGuardRule
import io.aaps.copilot.domain.safety.SafetyPolicy
import io.aaps.copilot.scheduler.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AppContainer(context: Context) {

    val gson: Gson = Gson()
    val settingsStore = AppSettingsStore(context)

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

    val apiFactory = ApiFactory()
    val auditLogger = AuditLogger(db.auditLogDao(), gson)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val localNightscoutServer = LocalNightscoutServer(
        context = context.applicationContext,
        db = db,
        gson = gson,
        auditLogger = auditLogger,
        onReactiveDataIngested = {
            WorkScheduler.triggerReactiveAutomation(context.applicationContext)
        }
    )
    private val localActivitySensorCollector = LocalActivitySensorCollector(
        context = context.applicationContext,
        db = db,
        auditLogger = auditLogger
    )
    private val healthConnectActivityCollector = HealthConnectActivityCollector(
        context = context.applicationContext,
        db = db,
        auditLogger = auditLogger
    )
    private val healthConnectEnabled = false
    @Volatile
    private var runtimeControllersActive = false
    private var localNightscoutSettingsJob: Job? = null

    val syncRepository = SyncRepository(
        db = db,
        settingsStore = settingsStore,
        apiFactory = apiFactory,
        gson = gson,
        auditLogger = auditLogger
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

    val rootDbRepository = RootDbExperimentalRepository(
        context = context,
        db = db,
        settingsStore = settingsStore,
        auditLogger = auditLogger
    )

    val broadcastIngestRepository = BroadcastIngestRepository(
        db = db,
        auditLogger = auditLogger
    )

    val analyticsRepository = AnalyticsRepository(
        db = db,
        patternAnalyzer = PatternAnalyzer(),
        gson = gson,
        auditLogger = auditLogger,
        isfCrRepository = IsfCrRepository(
            db = db,
            gson = gson,
            auditLogger = auditLogger
        )
    )

    val isfCrRepository: IsfCrRepository = analyticsRepository.isfCrRepository

    private val carbsSendThrottle = CarbsSendThrottle(
        actionCommandDao = db.actionCommandDao()
    )

    private val tempTargetSendThrottle = TempTargetSendThrottle(
        actionCommandDao = db.actionCommandDao()
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

    private val aapsCarbGateway = NightscoutAapsCarbGateway(
        settingsStore = settingsStore,
        apiFactory = apiFactory,
        carbsSendThrottle = carbsSendThrottle,
        auditLogger = auditLogger
    )

    private val uamEventStore = UamEventStore(db.uamInferenceEventDao())
    private val uamExportCoordinator = UamExportCoordinator(
        gateway = aapsCarbGateway,
        auditLogger = auditLogger
    )
    private val uamInferenceEngine = UamInferenceEngine()

    private val predictionEngine: PredictionEngine = HybridPredictionEngine(
        enableEnhancedPredictionV3 = true,
        enableUam = true,
        enableUamVirtualMealFit = true
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

    val automationRepository = AutomationRepository(
        db = db,
        settingsStore = settingsStore,
        syncRepository = syncRepository,
        exportRepository = exportRepository,
        autoConnectRepository = autoConnectRepository,
        rootDbRepository = rootDbRepository,
        analyticsRepository = analyticsRepository,
        isfCrRepository = isfCrRepository,
        actionRepository = actionRepository,
        predictionEngine = predictionEngine,
        uamInferenceEngine = uamInferenceEngine,
        uamEventStore = uamEventStore,
        uamExportCoordinator = uamExportCoordinator,
        ruleEngine = ruleEngine,
        apiFactory = apiFactory,
        gson = gson,
        auditLogger = auditLogger
    )

    val aiChatRepository = AiChatRepository(
        settingsStore = settingsStore,
        auditLogger = auditLogger
    )

    val insightsRepository = InsightsRepository(
        context = context,
        db = db,
        settingsStore = settingsStore,
        apiFactory = apiFactory,
        auditLogger = auditLogger,
        aiChatRepository = aiChatRepository
    )

    init {
        appScope.launch {
            val adaptiveMigrationEnabled = settingsStore.ensureAdaptiveControllerDefaultEnabled()
            val uamMigrationEnabled = settingsStore.ensureUamExportDefaultsEnabled()
            if (adaptiveMigrationEnabled || uamMigrationEnabled) {
                WorkScheduler.triggerReactiveAutomation(context.applicationContext)
            }
            if (adaptiveMigrationEnabled) {
                auditLogger.info("adaptive_controller_default_enabled", emptyMap<String, Any>())
            }
            if (uamMigrationEnabled) {
                auditLogger.info("uam_export_defaults_enabled", emptyMap<String, Any>())
            }
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
        if (runtimeControllersActive) return
        runtimeControllersActive = true
        startLocalActivitySensors()
        if (healthConnectEnabled) {
            startHealthConnectCollection()
        }
        if (localNightscoutSettingsJob == null) {
            localNightscoutSettingsJob = appScope.launch {
                settingsStore.settings.collectLatest { settings ->
                    val actualPort = localNightscoutServer.update(
                        enabled = settings.localNightscoutEnabled,
                        port = settings.localNightscoutPort
                    )
                    if (
                        settings.localNightscoutEnabled &&
                        actualPort != null &&
                        actualPort != settings.localNightscoutPort
                    ) {
                        settingsStore.update { current ->
                            if (!current.localNightscoutEnabled || current.localNightscoutPort == actualPort) {
                                current
                            } else {
                                current.copy(localNightscoutPort = actualPort)
                            }
                        }
                        auditLogger.warn(
                            "local_nightscout_port_auto_adjusted",
                            mapOf(
                                "requestedPort" to settings.localNightscoutPort,
                                "actualPort" to actualPort
                            )
                        )
                    }
                }
            }
        }
    }

    fun stopRuntimeControllers() {
        if (!runtimeControllersActive) return
        runtimeControllersActive = false
        localNightscoutSettingsJob?.cancel()
        localNightscoutSettingsJob = null
        localNightscoutServer.stop()
        stopLocalActivitySensors()
        if (healthConnectEnabled) {
            stopHealthConnectCollection()
        }
    }
}
