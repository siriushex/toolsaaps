package io.aaps.copilot.service

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.repository.AapsExportRepository
import io.aaps.copilot.data.repository.AapsAutoConnectRepository
import io.aaps.copilot.data.repository.AnalyticsRepository
import io.aaps.copilot.data.repository.AuditLogger
import io.aaps.copilot.data.repository.AutomationRepository
import io.aaps.copilot.data.repository.BroadcastIngestRepository
import io.aaps.copilot.data.repository.InsightsRepository
import io.aaps.copilot.data.repository.NightscoutActionRepository
import io.aaps.copilot.data.repository.RootDbExperimentalRepository
import io.aaps.copilot.data.repository.SyncRepository
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.domain.predict.PatternAnalyzer
import io.aaps.copilot.domain.predict.PredictionEngine
import io.aaps.copilot.domain.rules.PatternAdaptiveTargetRule
import io.aaps.copilot.domain.rules.PostHypoReboundGuardRule
import io.aaps.copilot.domain.rules.RuleEngine
import io.aaps.copilot.domain.rules.SegmentProfileGuardRule
import io.aaps.copilot.domain.safety.SafetyPolicy
import io.aaps.copilot.scheduler.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    val apiFactory = ApiFactory()
    val auditLogger = AuditLogger(db.auditLogDao(), gson)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val localNightscoutServer = LocalNightscoutServer(
        db = db,
        gson = gson,
        auditLogger = auditLogger,
        onReactiveDataIngested = {
            WorkScheduler.triggerReactiveAutomation(context.applicationContext)
        }
    )

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
        auditLogger = auditLogger
    )

    val actionRepository = NightscoutActionRepository(
        context = context,
        db = db,
        settingsStore = settingsStore,
        apiFactory = apiFactory,
        gson = gson,
        auditLogger = auditLogger
    )

    private val predictionEngine: PredictionEngine = HybridPredictionEngine()

    private val ruleEngine = RuleEngine(
        rules = listOf(
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
        actionRepository = actionRepository,
        predictionEngine = predictionEngine,
        ruleEngine = ruleEngine,
        apiFactory = apiFactory,
        gson = gson,
        auditLogger = auditLogger
    )

    val insightsRepository = InsightsRepository(
        context = context,
        settingsStore = settingsStore,
        apiFactory = apiFactory,
        auditLogger = auditLogger
    )

    init {
        appScope.launch {
            settingsStore.settings.collectLatest { settings ->
                localNightscoutServer.update(
                    enabled = settings.localNightscoutEnabled,
                    port = settings.localNightscoutPort
                )
            }
        }
    }
}
