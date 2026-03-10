package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.CircadianPatternSnapshotEntity
import io.aaps.copilot.data.local.entity.CircadianReplaySlotStatEntity
import io.aaps.copilot.data.local.entity.CircadianSlotStatEntity
import io.aaps.copilot.data.local.entity.CircadianTransitionStatEntity
import io.aaps.copilot.data.local.entity.PatternWindowEntity
import io.aaps.copilot.data.local.entity.ProfileEstimateEntity
import io.aaps.copilot.data.local.entity.ProfileSegmentEstimateEntity
import io.aaps.copilot.domain.model.CircadianDayType
import io.aaps.copilot.domain.model.CircadianForecastPrior
import io.aaps.copilot.domain.model.CircadianPatternSnapshot
import io.aaps.copilot.domain.model.CircadianReplaySlotStat
import io.aaps.copilot.domain.model.CircadianSlotStat
import io.aaps.copilot.domain.model.CircadianTransitionStat
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.predict.PatternAnalyzer
import io.aaps.copilot.domain.predict.PatternAnalyzerConfig
import io.aaps.copilot.domain.predict.CircadianPatternConfig
import io.aaps.copilot.domain.predict.CircadianPatternEngine
import io.aaps.copilot.domain.predict.ProfileEstimator
import io.aaps.copilot.domain.predict.ProfileEstimatorConfig
import io.aaps.copilot.domain.predict.TelemetrySignal
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AnalyticsRepository(
    private val db: CopilotDatabase,
    private val patternAnalyzer: PatternAnalyzer,
    private val gson: Gson,
    private val auditLogger: AuditLogger,
    val isfCrRepository: IsfCrRepository
) {

    private val profileStateHealMutex = Mutex()
    private val circadianStateHealMutex = Mutex()
    private val circadianPatternEngine = CircadianPatternEngine()
    private val circadianReplayEvaluator = CircadianReplaySummaryEvaluator()

    suspend fun recalculate(settings: AppSettings) = withContext(Dispatchers.Default) {
        val lookbackDays = settings.analyticsLookbackDays.coerceIn(30, 730)
        val nowTs = System.currentTimeMillis()
        val historyStart = nowTs - lookbackDays * 24L * 60 * 60 * 1000
        val glucose = GlucoseSanitizer.filterEntities(db.glucoseDao().since(historyStart)).map { it.toDomain() }
        val therapy = TherapySanitizer.filterEntities(db.therapyDao().since(historyStart)).map { it.toDomain(gson) }
        val telemetry = db.telemetryDao().since(historyStart).map { sample ->
            TelemetrySignal(
                ts = sample.timestamp,
                key = sample.key,
                valueDouble = sample.valueDouble,
                valueText = sample.valueText
            )
        }
        val forecastHistory = db.forecastDao().since(historyStart).map { it.toDomain() }

        val legacyWindows = patternAnalyzer.analyze(
            glucoseHistory = glucose,
            config = PatternAnalyzerConfig(
                baseTargetMmol = settings.baseTargetMmol,
                minSamplesPerWindow = settings.patternMinSamplesPerWindow,
                minActiveDaysPerWindow = settings.patternMinActiveDaysPerWindow,
                lowRateTrigger = settings.patternLowRateTrigger,
                highRateTrigger = settings.patternHighRateTrigger
            )
        )
        val circadianResult = fitCircadianPatterns(
            glucose = glucose,
            telemetry = telemetry,
            forecasts = forecastHistory,
            settings = settings,
            nowTs = nowTs
        )
        val circadianReplayStats = fitCircadianReplayStats(
            glucose = GlucoseSanitizer.filterEntities(db.glucoseDao().since(historyStart)),
            forecasts = db.forecastDao().since(historyStart),
            telemetry = db.telemetryDao().since(historyStart),
            circadianResult = circadianResult,
            settings = settings,
            nowTs = nowTs
        )
        val windows = if (circadianResult.derivedPatternWindows.isNotEmpty()) {
            circadianResult.derivedPatternWindows
        } else {
            legacyWindows
        }

        db.withTransaction {
            db.circadianPatternDao().clearSlotStats()
            db.circadianPatternDao().clearTransitionStats()
            db.circadianPatternDao().clearSnapshots()
            db.circadianPatternDao().clearReplaySlotStats()
            if (circadianResult.slotStats.isNotEmpty()) {
                db.circadianPatternDao().upsertSlotStats(circadianResult.slotStats.map { it.toEntity() })
            }
            if (circadianResult.transitionStats.isNotEmpty()) {
                db.circadianPatternDao().upsertTransitionStats(circadianResult.transitionStats.map { it.toEntity() })
            }
            if (circadianResult.snapshots.isNotEmpty()) {
                db.circadianPatternDao().upsertSnapshots(circadianResult.snapshots.map { it.toEntity() })
            }
            if (circadianReplayStats.isNotEmpty()) {
                db.circadianPatternDao().upsertReplaySlotStats(circadianReplayStats.map { it.toEntity() })
            }
            db.patternDao().deleteOlderThan(historyStart)
            db.patternDao().upsertAll(
                windows.map {
                    PatternWindowEntity(
                        dayType = it.dayType.name,
                        hour = it.hour,
                        sampleCount = it.sampleCount,
                        activeDays = it.activeDays,
                        lowRate = it.lowRate,
                        highRate = it.highRate,
                        recommendedTargetMmol = it.recommendedTargetMmol,
                        isRiskWindow = it.isRiskWindow,
                        updatedAt = nowTs
                    )
                }
            )
        }
        val rebuiltSegmentCount = rebuildProfileState(
            lookbackDays = lookbackDays,
            nowTs = nowTs,
            historyStart = historyStart,
            glucose = glucose,
            therapy = therapy,
            telemetry = telemetry
        )

        val riskWindows = windows.count { it.isRiskWindow }
        val lowRisk = windows.count { it.isRiskWindow && it.lowRate >= settings.patternLowRateTrigger }
        val highRisk = windows.count { it.isRiskWindow && it.highRate >= settings.patternHighRateTrigger }

        if (circadianResult.slotStats.isNotEmpty()) {
            auditLogger.info(
                "circadian_patterns_recalculated",
                mapOf(
                    "slotStats" to circadianResult.slotStats.size,
                    "transitionStats" to circadianResult.transitionStats.size,
                    "snapshots" to circadianResult.snapshots.size,
                    "replayStats" to circadianReplayStats.size,
                    "stableWeekdayWindow" to circadianResult.snapshots.firstOrNull {
                        it.requestedDayType == CircadianDayType.WEEKDAY
                    }?.stableWindowDays,
                    "stableWeekendWindow" to circadianResult.snapshots.firstOrNull {
                        it.requestedDayType == CircadianDayType.WEEKEND
                    }?.stableWindowDays
                )
            )
        }

        auditLogger.info(
            "pattern_recalculated",
            mapOf(
                "windows" to windows.size,
                "riskWindows" to riskWindows,
                "lowRiskWindows" to lowRisk,
                "highRiskWindows" to highRisk,
                "weekdayWindows" to windows.count { it.dayType == DayType.WEEKDAY },
                "weekendWindows" to windows.count { it.dayType == DayType.WEEKEND },
                "profileSegments" to rebuiltSegmentCount,
                "lookbackDays" to lookbackDays
            )
        )

        if (riskWindows == 0) {
            auditLogger.warn(
                "pattern_recalculated_no_risk_windows",
                mapOf(
                    "minSamples" to settings.patternMinSamplesPerWindow,
                    "minDays" to settings.patternMinActiveDaysPerWindow
                )
            )
        }

        val activeModel = db.isfCrModelStateDao().active()
        if (activeModel == null || nowTs - activeModel.updatedAt >= ISFCR_BASE_REFIT_INTERVAL_MS) {
            isfCrRepository.fitBaseModel(settings = settings, nowTs = nowTs)
        }
    }

    suspend fun ensureProfileStateHealthy(
        settings: AppSettings,
        reasonHint: String
    ): Boolean = profileStateHealMutex.withLock {
        val nowTs = System.currentTimeMillis()
        val active = db.profileEstimateDao().active()
        val segmentCount = db.profileSegmentEstimateDao().countAll()
        val latestSegmentUpdatedAt = db.profileSegmentEstimateDao().latestUpdatedAt()
        val rebuildReason = determineProfileStateRebuildReason(
            active = active,
            segmentCount = segmentCount,
            latestSegmentUpdatedAt = latestSegmentUpdatedAt,
            nowTs = nowTs
        ) ?: return@withLock false

        auditLogger.info(
            "profile_estimate_self_heal_requested",
            mapOf(
                "reason" to rebuildReason,
                "hint" to reasonHint,
                "hasActiveProfile" to (active != null),
                "activeTimestamp" to (active?.timestamp ?: 0L),
                "segmentCount" to segmentCount,
                "latestSegmentUpdatedAt" to (latestSegmentUpdatedAt ?: 0L)
            )
        )
        withContext(Dispatchers.Default) {
            val lookbackDays = settings.analyticsLookbackDays
                .coerceIn(30, 730)
                .coerceAtMost(PROFILE_SELF_HEAL_LOOKBACK_DAYS_MAX)
            val historyStart = nowTs - lookbackDays * 24L * 60 * 60 * 1000
            val glucose = GlucoseSanitizer.filterEntities(db.glucoseDao().since(historyStart)).map { it.toDomain() }
            val therapy = TherapySanitizer.filterEntities(db.therapyDao().since(historyStart)).map { it.toDomain(gson) }
            val telemetry = db.telemetryDao().since(historyStart).map { sample ->
                TelemetrySignal(
                    ts = sample.timestamp,
                    key = sample.key,
                    valueDouble = sample.valueDouble,
                    valueText = sample.valueText
                )
            }
            rebuildProfileState(
                lookbackDays = lookbackDays,
                nowTs = nowTs,
                historyStart = historyStart,
                glucose = glucose,
                therapy = therapy,
                telemetry = telemetry
            )
        }
        true
    }

    suspend fun ensureCircadianStateHealthy(
        settings: AppSettings,
        reasonHint: String
    ): Boolean = circadianStateHealMutex.withLock {
        val nowTs = System.currentTimeMillis()
        val circadianDao = db.circadianPatternDao()
        val rebuildReason = determineCircadianStateRebuildReason(
            slotCount = circadianDao.countSlotStats(),
            transitionCount = circadianDao.countTransitionStats(),
            snapshotCount = circadianDao.countSnapshots(),
            replayCount = circadianDao.countReplaySlotStats(),
            qualifiedReplayCount = circadianDao.countQualifiedReplaySlotStats(),
            pollutedReplayZeroQualityCount = circadianDao.countPollutedZeroQualityReplaySlotStats(),
            latestSnapshotUpdatedAt = circadianDao.latestSnapshotUpdatedAt(),
            latestReplayUpdatedAt = circadianDao.latestReplayUpdatedAt(),
            nowTs = nowTs
        ) ?: return@withLock false

        auditLogger.info(
            "circadian_pattern_self_heal_requested",
            mapOf(
                "reason" to rebuildReason,
                "hint" to reasonHint,
                "slotCount" to circadianDao.countSlotStats(),
                "transitionCount" to circadianDao.countTransitionStats(),
                "snapshotCount" to circadianDao.countSnapshots(),
                "replayCount" to circadianDao.countReplaySlotStats(),
                "qualifiedReplayCount" to circadianDao.countQualifiedReplaySlotStats(),
                "pollutedReplayZeroQualityCount" to circadianDao.countPollutedZeroQualityReplaySlotStats(),
                "latestSnapshotUpdatedAt" to (circadianDao.latestSnapshotUpdatedAt() ?: 0L),
                "latestReplayUpdatedAt" to (circadianDao.latestReplayUpdatedAt() ?: 0L)
            )
        )

        return@withLock try {
            withContext(Dispatchers.Default) {
                val lookbackDays = settings.analyticsLookbackDays
                    .coerceIn(14, 730)
                    .coerceAtMost(CIRCADIAN_SELF_HEAL_LOOKBACK_DAYS_MAX)
                val historyStart = nowTs - lookbackDays * 24L * 60 * 60 * 1000
                val glucoseEntities = GlucoseSanitizer.filterEntities(db.glucoseDao().since(historyStart))
                val telemetryEntities = db.telemetryDao().sinceByKeys(historyStart, CIRCADIAN_SELF_HEAL_TELEMETRY_KEYS)
                val forecastEntities = db.forecastDao().since(historyStart)
                val glucose = glucoseEntities.map { it.toDomain() }
                val telemetry = telemetryEntities.map { sample ->
                    TelemetrySignal(
                        ts = sample.timestamp,
                        key = sample.key,
                        valueDouble = sample.valueDouble,
                        valueText = sample.valueText
                    )
                }
                val forecastHistory = forecastEntities.map { it.toDomain() }
                val replayOnlyRebuild = rebuildReason == "polluted_replay_zero_quality"
                val circadianResult = if (replayOnlyRebuild) {
                    val persistedSnapshots = db.circadianPatternDao().allSnapshots().map { it.toDomain() }
                    val persistedSlotStats = db.circadianPatternDao().allSlotStats().map { it.toDomain() }
                    val persistedTransitionStats = db.circadianPatternDao().allTransitionStats().map { it.toDomain() }
                    io.aaps.copilot.domain.predict.CircadianPatternResult(
                        slotStats = persistedSlotStats,
                        transitionStats = persistedTransitionStats,
                        snapshots = persistedSnapshots,
                        derivedPatternWindows = emptyList()
                    )
                } else {
                    fitCircadianPatterns(
                        glucose = glucose,
                        telemetry = telemetry,
                        forecasts = forecastHistory,
                        settings = settings,
                        nowTs = nowTs
                    )
                }
                val circadianReplayStats = fitCircadianReplayStats(
                    glucose = glucoseEntities,
                    forecasts = forecastEntities,
                    telemetry = telemetryEntities,
                    circadianResult = circadianResult,
                    settings = settings,
                    nowTs = nowTs
                )
                val windows = if (replayOnlyRebuild) {
                    emptyList()
                } else if (circadianResult.derivedPatternWindows.isNotEmpty()) {
                    circadianResult.derivedPatternWindows
                } else {
                    patternAnalyzer.analyze(
                        glucoseHistory = glucose,
                        config = PatternAnalyzerConfig(
                            baseTargetMmol = settings.baseTargetMmol,
                            minSamplesPerWindow = settings.patternMinSamplesPerWindow,
                            minActiveDaysPerWindow = settings.patternMinActiveDaysPerWindow,
                            lowRateTrigger = settings.patternLowRateTrigger,
                            highRateTrigger = settings.patternHighRateTrigger
                        )
                    )
                }
                db.withTransaction {
                    if (!replayOnlyRebuild) {
                        db.circadianPatternDao().clearSlotStats()
                        db.circadianPatternDao().clearTransitionStats()
                        db.circadianPatternDao().clearSnapshots()
                    }
                    db.circadianPatternDao().clearReplaySlotStats()
                    if (!replayOnlyRebuild && circadianResult.slotStats.isNotEmpty()) {
                        db.circadianPatternDao().upsertSlotStats(circadianResult.slotStats.map { it.toEntity() })
                    }
                    if (!replayOnlyRebuild && circadianResult.transitionStats.isNotEmpty()) {
                        db.circadianPatternDao().upsertTransitionStats(circadianResult.transitionStats.map { it.toEntity() })
                    }
                    if (!replayOnlyRebuild && circadianResult.snapshots.isNotEmpty()) {
                        db.circadianPatternDao().upsertSnapshots(circadianResult.snapshots.map { it.toEntity() })
                    }
                    if (circadianReplayStats.isNotEmpty()) {
                        db.circadianPatternDao().upsertReplaySlotStats(circadianReplayStats.map { it.toEntity() })
                    }
                    if (!replayOnlyRebuild) {
                        db.patternDao().deleteOlderThan(historyStart)
                        db.patternDao().upsertAll(
                            windows.map {
                                PatternWindowEntity(
                                    dayType = it.dayType.name,
                                    hour = it.hour,
                                    sampleCount = it.sampleCount,
                                    activeDays = it.activeDays,
                                    lowRate = it.lowRate,
                                    highRate = it.highRate,
                                    recommendedTargetMmol = it.recommendedTargetMmol,
                                    isRiskWindow = it.isRiskWindow,
                                    updatedAt = nowTs
                                )
                            }
                        )
                    }
                }
                auditLogger.info(
                    "circadian_pattern_self_heal_completed",
                    mapOf(
                        "reason" to rebuildReason,
                        "replayOnly" to replayOnlyRebuild,
                        "slotStats" to circadianResult.slotStats.size,
                        "transitionStats" to circadianResult.transitionStats.size,
                        "snapshots" to circadianResult.snapshots.size,
                        "replayStats" to circadianReplayStats.size,
                        "lookbackDays" to lookbackDays,
                        "telemetryRows" to telemetryEntities.size,
                        "forecastRows" to forecastEntities.size,
                        "glucoseRows" to glucoseEntities.size
                    )
                )
            }
            true
        } catch (t: Throwable) {
            auditLogger.warn(
                "circadian_pattern_self_heal_failed",
                mapOf(
                    "reason" to rebuildReason,
                    "hint" to reasonHint,
                    "error" to (t.message ?: t::class.java.simpleName)
                )
            )
            false
        }
    }

    fun observePatterns() = db.patternDao().observeAll()

    fun observeCircadianSlotStats() = db.circadianPatternDao().observeSlotStats()

    fun observeCircadianTransitionStats() = db.circadianPatternDao().observeTransitionStats()

    fun observeCircadianSnapshots() = db.circadianPatternDao().observeSnapshots()

    fun observeCircadianReplaySlotStats() = db.circadianPatternDao().observeReplaySlotStats()

    fun observeProfileEstimate() = db.profileEstimateDao().observeActive()

    fun observeProfileSegments() = db.profileSegmentEstimateDao().observeAll()

    fun observeIsfCrSnapshot() = isfCrRepository.observeLatestSnapshot()

    fun observeIsfCrHistory(limit: Int = 20_000) = isfCrRepository.observeSnapshotHistory(limit = limit)

    suspend fun fitCircadianPatterns(
        glucose: List<io.aaps.copilot.domain.model.GlucosePoint>,
        telemetry: List<TelemetrySignal>,
        forecasts: List<Forecast>,
        settings: AppSettings,
        nowTs: Long
    ) = circadianPatternEngine.fit(
        glucoseHistory = glucose,
        telemetryHistory = telemetry,
        forecastHistory = forecasts,
        nowTs = nowTs,
        config = CircadianPatternConfig(
            baseTargetMmol = settings.baseTargetMmol,
            stableWindowsDays = listOf(
                settings.circadianStableLookbackDays.coerceIn(7, 14),
                10,
                7
            ).distinct(),
            recencyWindowDays = settings.circadianRecencyLookbackDays.coerceIn(5, 7),
            minSlotSamples = maxOf(6, settings.patternMinSamplesPerWindow / 6),
            useWeekendSplit = settings.circadianUseWeekendSplit,
            useReplayResidualBias = settings.circadianUseReplayResidualBias
        )
    )

    suspend fun fitCircadianReplayStats(
        glucose: List<io.aaps.copilot.data.local.entity.GlucoseSampleEntity>,
        forecasts: List<io.aaps.copilot.data.local.entity.ForecastEntity>,
        telemetry: List<io.aaps.copilot.data.local.entity.TelemetrySampleEntity>,
        circadianResult: io.aaps.copilot.domain.predict.CircadianPatternResult,
        settings: AppSettings,
        nowTs: Long
    ): List<CircadianReplaySlotStat> {
        return circadianReplayEvaluator.fitSlotStats(
            forecasts = forecasts,
            glucose = glucose,
            telemetry = telemetry,
            snapshots = circadianResult.snapshots.map { it.toEntity() },
            slotStats = circadianResult.slotStats.map { it.toEntity() },
            transitionStats = circadianResult.transitionStats.map { it.toEntity() },
            settings = settings,
            nowTs = nowTs
        )
    }

    suspend fun resolveCircadianPrior(
        nowTs: Long,
        currentGlucose: Double,
        telemetry: Map<String, Double?>,
        settings: AppSettings
    ): CircadianForecastPrior? = withContext(Dispatchers.Default) {
        if (!settings.circadianPatternsEnabled) return@withContext null
        val snapshotRows = db.circadianPatternDao().allSnapshots()
        if (snapshotRows.isEmpty()) return@withContext null
        val zone = java.time.ZoneId.systemDefault()
        val requestedType = if (!settings.circadianUseWeekendSplit) {
            CircadianDayType.ALL
        } else if (Instant.ofEpochMilli(nowTs).atZone(zone).dayOfWeek.value in setOf(6, 7)) {
            CircadianDayType.WEEKEND
        } else {
            CircadianDayType.WEEKDAY
        }
        val snapshot = snapshotRows.firstOrNull { it.dayType == requestedType.name } ?: return@withContext null
        val effectiveType = snapshot.segmentSource
        val slotIndex = ((Instant.ofEpochMilli(nowTs).atZone(zone).hour * 60 +
            Instant.ofEpochMilli(nowTs).atZone(zone).minute) / 15).coerceIn(0, 95)
        val stableSlotStats = db.circadianPatternDao().slotStats(effectiveType, snapshot.stableWindowDays)
        val recencySlotStats = db.circadianPatternDao().slotStats(effectiveType, snapshot.recencyWindowDays)
        val stableTransitions = db.circadianPatternDao().transitionStats(
            effectiveType,
            snapshot.stableWindowDays,
            slotIndex
        )
        val recencyTransitions = db.circadianPatternDao().transitionStats(
            effectiveType,
            snapshot.recencyWindowDays,
            slotIndex
        )
        val stableReplayStats = db.circadianPatternDao().replaySlotStatsForWindow(
            requestedType.name,
            snapshot.stableWindowDays
        )
        val fallbackReplayStats = if (requestedType != CircadianDayType.ALL) {
            db.circadianPatternDao().replaySlotStatsForWindow(
                CircadianDayType.ALL.name,
                snapshot.stableWindowDays
            )
        } else {
            emptyList()
        }
        val recencyReplayStats = db.circadianPatternDao().replaySlotStatsForWindow(
            requestedType.name,
            snapshot.recencyWindowDays
        )
        circadianPatternEngine.resolvePrior(
            nowTs = nowTs,
            currentGlucoseMmol = currentGlucose,
            telemetry = telemetry,
            snapshots = snapshotRows.map { it.toDomain() },
            slotStats = (stableSlotStats + recencySlotStats).map { it.toDomain() },
            transitionStats = (stableTransitions + recencyTransitions).map { it.toDomain() },
            replayStats = (stableReplayStats + fallbackReplayStats + recencyReplayStats).map { it.toDomain() },
            config = CircadianPatternConfig(
                baseTargetMmol = settings.baseTargetMmol,
                stableWindowsDays = listOf(
                    settings.circadianStableLookbackDays.coerceIn(7, 14),
                    10,
                    7
                ).distinct(),
                recencyWindowDays = settings.circadianRecencyLookbackDays.coerceIn(5, 7),
                useWeekendSplit = settings.circadianUseWeekendSplit,
                useReplayResidualBias = settings.circadianUseReplayResidualBias,
                recencyMaxWeight = 0.30
            )
        )
    }

    suspend fun buildCircadianReplaySummary(
        settings: AppSettings,
        nowTs: Long = System.currentTimeMillis(),
        windowsDays: List<Int> = listOf(1, 7)
    ): CircadianReplaySummary = withContext(Dispatchers.Default) {
        if (!settings.circadianPatternsEnabled) {
            return@withContext CircadianReplaySummary(
                generatedAtTs = nowTs,
                windows = emptyList()
            )
        }
        val maxDays = windowsDays.maxOrNull()?.coerceAtLeast(1) ?: 7
        val sinceTs = nowTs - maxDays * 24L * 60L * 60L * 1000L
        val glucose = db.glucoseDao().since(sinceTs - 2L * 60L * 60L * 1000L)
        val forecasts = db.forecastDao().since(sinceTs - 60L * 60L * 1000L)
        val telemetry = db.telemetryDao().since(sinceTs - 2L * 60L * 60L * 1000L)
        val snapshots = db.circadianPatternDao().allSnapshots()
        val slotStats = db.circadianPatternDao().allSlotStats()
        val transitionStats = db.circadianPatternDao().allTransitionStats()
        val replaySlotStats = db.circadianPatternDao().allReplaySlotStats()
        circadianReplayEvaluator.evaluate(
            forecasts = forecasts,
            glucose = glucose,
            telemetry = telemetry,
            snapshots = snapshots,
            slotStats = slotStats,
            transitionStats = transitionStats,
            replaySlotStats = replaySlotStats,
            settings = settings,
            nowTs = nowTs,
            windowsDays = windowsDays
        )
    }

    private suspend fun rebuildProfileState(
        lookbackDays: Int,
        nowTs: Long,
        historyStart: Long,
        glucose: List<io.aaps.copilot.domain.model.GlucosePoint>,
        therapy: List<io.aaps.copilot.domain.model.TherapyEvent>,
        telemetry: List<TelemetrySignal>
    ): Int {
        val profileEstimator = ProfileEstimator(
            ProfileEstimatorConfig(lookbackDays = lookbackDays)
        )
        val profileEstimate = profileEstimator.estimate(glucose, therapy, telemetry)
        val calculatedEstimate = profileEstimator.estimate(glucose, therapy, emptyList())
        val segmentEstimates = profileEstimator.estimateSegments(glucose, therapy, telemetry)
        val segmentRows = segmentEstimates.map {
            ProfileSegmentEstimateEntity(
                id = "${it.dayType}:${it.timeSlot}:$nowTs",
                dayType = it.dayType.name,
                timeSlot = it.timeSlot.name,
                isfMmolPerUnit = it.isfMmolPerUnit,
                crGramPerUnit = it.crGramPerUnit,
                confidence = it.confidence,
                isfSampleCount = it.isfSampleCount,
                crSampleCount = it.crSampleCount,
                lookbackDays = it.lookbackDays,
                updatedAt = nowTs
            )
        }
        var legacyProfileRowsRemoved = 0
        var clearedSegmentRows = 0
        db.withTransaction {
            legacyProfileRowsRemoved = db.profileEstimateDao().deleteLegacyTelemetryPollutedRows()
            clearedSegmentRows = db.profileSegmentEstimateDao().clear()
            if (segmentRows.isNotEmpty()) {
                db.profileSegmentEstimateDao().upsertAll(segmentRows)
            }
            profileEstimate?.let { estimate ->
                val snapshot = ProfileEstimateEntity(
                    id = "snapshot-$nowTs",
                    timestamp = nowTs,
                    isfMmolPerUnit = estimate.isfMmolPerUnit,
                    crGramPerUnit = estimate.crGramPerUnit,
                    confidence = estimate.confidence,
                    sampleCount = estimate.sampleCount,
                    isfSampleCount = estimate.isfSampleCount,
                    crSampleCount = estimate.crSampleCount,
                    lookbackDays = estimate.lookbackDays,
                    telemetryIsfSampleCount = estimate.telemetryIsfSampleCount,
                    telemetryCrSampleCount = estimate.telemetryCrSampleCount,
                    uamObservedCount = estimate.uamObservedCount,
                    uamFilteredIsfSamples = estimate.uamFilteredIsfSamples,
                    uamEpisodeCount = estimate.uamEpisodeCount,
                    uamEstimatedCarbsGrams = estimate.uamEstimatedCarbsGrams,
                    uamEstimatedRecentCarbsGrams = estimate.uamEstimatedRecentCarbsGrams,
                    calculatedIsfMmolPerUnit = calculatedEstimate?.isfMmolPerUnit,
                    calculatedCrGramPerUnit = calculatedEstimate?.crGramPerUnit,
                    calculatedConfidence = calculatedEstimate?.confidence,
                    calculatedSampleCount = calculatedEstimate?.sampleCount ?: 0,
                    calculatedIsfSampleCount = calculatedEstimate?.isfSampleCount ?: 0,
                    calculatedCrSampleCount = calculatedEstimate?.crSampleCount ?: 0
                )
                db.profileEstimateDao().upsert(snapshot)
                db.profileEstimateDao().upsert(
                    ProfileEstimateEntity(
                        timestamp = nowTs,
                        isfMmolPerUnit = estimate.isfMmolPerUnit,
                        crGramPerUnit = estimate.crGramPerUnit,
                        confidence = estimate.confidence,
                        sampleCount = estimate.sampleCount,
                        isfSampleCount = estimate.isfSampleCount,
                        crSampleCount = estimate.crSampleCount,
                        lookbackDays = estimate.lookbackDays,
                        telemetryIsfSampleCount = estimate.telemetryIsfSampleCount,
                        telemetryCrSampleCount = estimate.telemetryCrSampleCount,
                        uamObservedCount = estimate.uamObservedCount,
                        uamFilteredIsfSamples = estimate.uamFilteredIsfSamples,
                        uamEpisodeCount = estimate.uamEpisodeCount,
                        uamEstimatedCarbsGrams = estimate.uamEstimatedCarbsGrams,
                        uamEstimatedRecentCarbsGrams = estimate.uamEstimatedRecentCarbsGrams,
                        calculatedIsfMmolPerUnit = calculatedEstimate?.isfMmolPerUnit,
                        calculatedCrGramPerUnit = calculatedEstimate?.crGramPerUnit,
                        calculatedConfidence = calculatedEstimate?.confidence,
                        calculatedSampleCount = calculatedEstimate?.sampleCount ?: 0,
                        calculatedIsfSampleCount = calculatedEstimate?.isfSampleCount ?: 0,
                        calculatedCrSampleCount = calculatedEstimate?.crSampleCount ?: 0
                    )
                )
            }
            db.profileEstimateDao().deleteHistoryOlderThan(historyStart)
        }

        if (legacyProfileRowsRemoved > 0 || clearedSegmentRows > 0) {
            auditLogger.info(
                "profile_estimate_rebuild_reset",
                mapOf(
                    "legacyProfileRowsRemoved" to legacyProfileRowsRemoved,
                    "clearedSegmentRows" to clearedSegmentRows,
                    "reinsertedSegments" to segmentRows.size,
                    "lookbackDays" to lookbackDays
                )
            )
        }

        profileEstimate?.let { estimate ->
            auditLogger.info(
                "profile_estimate_updated",
                mapOf(
                    "isf" to estimate.isfMmolPerUnit,
                    "cr" to estimate.crGramPerUnit,
                    "confidence" to estimate.confidence,
                    "samples" to estimate.sampleCount,
                    "isfSamples" to estimate.isfSampleCount,
                    "crSamples" to estimate.crSampleCount,
                    "lookbackDays" to estimate.lookbackDays,
                    "telemetryIsfSamples" to estimate.telemetryIsfSampleCount,
                    "telemetryCrSamples" to estimate.telemetryCrSampleCount,
                    "uamObservedCount" to estimate.uamObservedCount,
                    "uamFilteredIsfSamples" to estimate.uamFilteredIsfSamples,
                    "uamEpisodeCount" to estimate.uamEpisodeCount,
                    "uamEstimatedCarbsGrams" to estimate.uamEstimatedCarbsGrams,
                    "uamEstimatedRecentCarbsGrams" to estimate.uamEstimatedRecentCarbsGrams,
                    "calculatedIsf" to calculatedEstimate?.isfMmolPerUnit,
                    "calculatedCr" to calculatedEstimate?.crGramPerUnit,
                    "calculatedConfidence" to calculatedEstimate?.confidence,
                    "calculatedSamples" to (calculatedEstimate?.sampleCount ?: 0),
                    "snapshotId" to "snapshot-$nowTs"
                )
            )
        } ?: auditLogger.warn(
            "profile_estimate_skipped",
            mapOf("reason" to "insufficient_samples", "lookbackDays" to lookbackDays)
        )
        return segmentRows.size
    }

    companion object {
        private const val ISFCR_BASE_REFIT_INTERVAL_MS = 6L * 60 * 60 * 1000
        private const val PROFILE_REBUILD_MAX_AGE_MS = 12L * 60 * 60 * 1000
        private const val PROFILE_SELF_HEAL_LOOKBACK_DAYS_MAX = 90
        private const val CIRCADIAN_REBUILD_MAX_AGE_MS = 12L * 60 * 60 * 1000
        private const val CIRCADIAN_SELF_HEAL_LOOKBACK_DAYS_MAX = 21
        private val CIRCADIAN_SELF_HEAL_TELEMETRY_KEYS = listOf(
            "sensor_quality_delta5_mmol",
            "delta5_mmol",
            "glucose_delta5_mmol",
            "cob_grams",
            "cob_effective_grams",
            "cob_external_adjusted_grams",
            "uam_value",
            "uam_runtime_carbs_grams",
            "uam_inferred_carbs_grams",
            "uam_calculated_carbs_grams",
            "iob_units",
            "iob_effective_units",
            "iob_real_units",
            "activity_ratio",
            "sensor_quality_blocked",
            "sensor_quality_suspect_false_low"
        )

        internal fun determineProfileStateRebuildReason(
            active: ProfileEstimateEntity?,
            segmentCount: Int,
            latestSegmentUpdatedAt: Long?,
            nowTs: Long
        ): String? {
            if (active == null) {
                return "missing_active_profile"
            }
            if (active.timestamp <= 0L) {
                return "invalid_active_profile_timestamp"
            }
            if (active.telemetryIsfSampleCount > 1 || active.telemetryCrSampleCount > 1) {
                return "legacy_telemetry_pollution"
            }
            if (nowTs - active.timestamp >= PROFILE_REBUILD_MAX_AGE_MS) {
                return "stale_active_profile"
            }
            if (segmentCount <= 0) {
                return "missing_profile_segments"
            }
            val latestSegmentsTs = latestSegmentUpdatedAt ?: return "missing_profile_segment_timestamp"
            if (latestSegmentsTs <= 0L) {
                return "invalid_profile_segment_timestamp"
            }
            if (latestSegmentsTs + 60_000L < active.timestamp) {
                return "segments_older_than_active_profile"
            }
            if (nowTs - latestSegmentsTs >= PROFILE_REBUILD_MAX_AGE_MS) {
                return "stale_profile_segments"
            }
            return null
        }

        internal fun determineCircadianStateRebuildReason(
            slotCount: Int,
            transitionCount: Int,
            snapshotCount: Int,
            replayCount: Int,
            qualifiedReplayCount: Int,
            pollutedReplayZeroQualityCount: Int,
            latestSnapshotUpdatedAt: Long?,
            latestReplayUpdatedAt: Long?,
            nowTs: Long
        ): String? {
            if (slotCount <= 0) {
                return "missing_slot_stats"
            }
            if (transitionCount <= 0) {
                return "missing_transition_stats"
            }
            if (snapshotCount <= 0) {
                return "missing_pattern_snapshots"
            }
            if (replayCount <= 0) {
                return "missing_replay_slot_stats"
            }
            if (qualifiedReplayCount > 0 && pollutedReplayZeroQualityCount > 0) {
                return "polluted_replay_zero_quality"
            }
            val snapshotTs = latestSnapshotUpdatedAt ?: return "missing_snapshot_timestamp"
            if (snapshotTs <= 0L) {
                return "invalid_snapshot_timestamp"
            }
            if (nowTs - snapshotTs >= CIRCADIAN_REBUILD_MAX_AGE_MS) {
                return "stale_pattern_snapshots"
            }
            val replayTs = latestReplayUpdatedAt ?: return "missing_replay_timestamp"
            if (replayTs <= 0L) {
                return "invalid_replay_timestamp"
            }
            if (nowTs - replayTs >= CIRCADIAN_REBUILD_MAX_AGE_MS) {
                return "stale_replay_slot_stats"
            }
            return null
        }
    }
}

private fun CircadianSlotStat.toEntity(): CircadianSlotStatEntity =
    CircadianSlotStatEntity(
        dayType = dayType.name,
        windowDays = windowDays,
        slotIndex = slotIndex,
        sampleCount = sampleCount,
        activeDays = activeDays,
        medianBg = medianBg,
        p10 = p10,
        p25 = p25,
        p75 = p75,
        p90 = p90,
        pLow = pLow,
        pHigh = pHigh,
        pInRange = pInRange,
        fastRiseRate = fastRiseRate,
        fastDropRate = fastDropRate,
        meanCob = meanCob,
        meanIob = meanIob,
        meanUam = meanUam,
        meanActivity = meanActivity,
        confidence = confidence,
        qualityScore = qualityScore,
        updatedAt = updatedAt
    )

private fun CircadianTransitionStat.toEntity(): CircadianTransitionStatEntity =
    CircadianTransitionStatEntity(
        dayType = dayType.name,
        windowDays = windowDays,
        slotIndex = slotIndex,
        horizonMinutes = horizonMinutes,
        sampleCount = sampleCount,
        deltaMedian = deltaMedian,
        deltaP25 = deltaP25,
        deltaP75 = deltaP75,
        residualBiasMmol = residualBiasMmol,
        confidence = confidence,
        updatedAt = updatedAt
    )

private fun CircadianPatternSnapshot.toEntity(): CircadianPatternSnapshotEntity =
    CircadianPatternSnapshotEntity(
        dayType = requestedDayType.name,
        segmentSource = segmentSource.name,
        stableWindowDays = stableWindowDays,
        recencyWindowDays = recencyWindowDays,
        recencyWeight = recencyWeight,
        coverageDays = coverageDays,
        sampleCount = sampleCount,
        segmentFallback = segmentFallback,
        fallbackReason = fallbackReason,
        confidence = confidence,
        qualityScore = qualityScore,
        updatedAt = updatedAt
    )

private fun CircadianReplaySlotStat.toEntity(): CircadianReplaySlotStatEntity =
    CircadianReplaySlotStatEntity(
        dayType = dayType.name,
        windowDays = windowDays,
        slotIndex = slotIndex,
        horizonMinutes = horizonMinutes,
        sampleCount = sampleCount,
        coverageDays = coverageDays,
        maeBaseline = maeBaseline,
        maeCircadian = maeCircadian,
        maeImprovementMmol = maeImprovementMmol,
        medianSignedErrorBaseline = medianSignedErrorBaseline,
        medianSignedErrorCircadian = medianSignedErrorCircadian,
        winRate = winRate,
        qualityScore = qualityScore,
        updatedAt = updatedAt
    )

private fun CircadianSlotStatEntity.toDomain(): CircadianSlotStat =
    CircadianSlotStat(
        dayType = CircadianDayType.valueOf(dayType),
        windowDays = windowDays,
        slotIndex = slotIndex,
        sampleCount = sampleCount,
        activeDays = activeDays,
        medianBg = medianBg,
        p10 = p10,
        p25 = p25,
        p75 = p75,
        p90 = p90,
        pLow = pLow,
        pHigh = pHigh,
        pInRange = pInRange,
        fastRiseRate = fastRiseRate,
        fastDropRate = fastDropRate,
        meanCob = meanCob,
        meanIob = meanIob,
        meanUam = meanUam,
        meanActivity = meanActivity,
        confidence = confidence,
        qualityScore = qualityScore,
        updatedAt = updatedAt
    )

private fun CircadianTransitionStatEntity.toDomain(): CircadianTransitionStat =
    CircadianTransitionStat(
        dayType = CircadianDayType.valueOf(dayType),
        windowDays = windowDays,
        slotIndex = slotIndex,
        horizonMinutes = horizonMinutes,
        sampleCount = sampleCount,
        deltaMedian = deltaMedian,
        deltaP25 = deltaP25,
        deltaP75 = deltaP75,
        residualBiasMmol = residualBiasMmol,
        confidence = confidence,
        updatedAt = updatedAt
    )

private fun CircadianPatternSnapshotEntity.toDomain(): CircadianPatternSnapshot =
    CircadianPatternSnapshot(
        requestedDayType = CircadianDayType.valueOf(dayType),
        segmentSource = CircadianDayType.valueOf(segmentSource),
        stableWindowDays = stableWindowDays,
        recencyWindowDays = recencyWindowDays,
        recencyWeight = recencyWeight,
        coverageDays = coverageDays,
        sampleCount = sampleCount,
        segmentFallback = segmentFallback,
        fallbackReason = fallbackReason,
        confidence = confidence,
        qualityScore = qualityScore,
        updatedAt = updatedAt
    )

private fun CircadianReplaySlotStatEntity.toDomain(): CircadianReplaySlotStat =
    CircadianReplaySlotStat(
        dayType = CircadianDayType.valueOf(dayType),
        windowDays = windowDays,
        slotIndex = slotIndex,
        horizonMinutes = horizonMinutes,
        sampleCount = sampleCount,
        coverageDays = coverageDays,
        maeBaseline = maeBaseline,
        maeCircadian = maeCircadian,
        maeImprovementMmol = maeImprovementMmol,
        medianSignedErrorBaseline = medianSignedErrorBaseline,
        medianSignedErrorCircadian = medianSignedErrorCircadian,
        winRate = winRate,
        qualityScore = qualityScore,
        updatedAt = updatedAt
    )
