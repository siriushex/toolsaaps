package io.aaps.copilot.data.repository

import com.google.gson.Gson
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.PatternWindowEntity
import io.aaps.copilot.data.local.entity.ProfileEstimateEntity
import io.aaps.copilot.data.local.entity.ProfileSegmentEstimateEntity
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.predict.PatternAnalyzer
import io.aaps.copilot.domain.predict.PatternAnalyzerConfig
import io.aaps.copilot.domain.predict.ProfileEstimator
import io.aaps.copilot.domain.predict.ProfileEstimatorConfig
import io.aaps.copilot.domain.predict.TelemetrySignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class AnalyticsRepository(
    private val db: CopilotDatabase,
    private val patternAnalyzer: PatternAnalyzer,
    private val gson: Gson,
    private val auditLogger: AuditLogger
) {

    suspend fun recalculate(settings: AppSettings) = withContext(Dispatchers.Default) {
        val lookbackDays = settings.analyticsLookbackDays.coerceIn(30, 730)
        val historyStart = System.currentTimeMillis() - lookbackDays * 24L * 60 * 60 * 1000
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

        val windows = patternAnalyzer.analyze(
            glucoseHistory = glucose,
            config = PatternAnalyzerConfig(
                baseTargetMmol = settings.baseTargetMmol,
                minSamplesPerWindow = settings.patternMinSamplesPerWindow,
                minActiveDaysPerWindow = settings.patternMinActiveDaysPerWindow,
                lowRateTrigger = settings.patternLowRateTrigger,
                highRateTrigger = settings.patternHighRateTrigger
            )
        )

        db.patternDao().clear()
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
                    updatedAt = System.currentTimeMillis()
                )
            }
        )

        val profileEstimator = ProfileEstimator(
            ProfileEstimatorConfig(lookbackDays = lookbackDays)
        )
        val profileEstimate = profileEstimator.estimate(glucose, therapy, telemetry)
        val calculatedEstimate = profileEstimator.estimate(glucose, therapy, emptyList())
        val segmentEstimates = profileEstimator.estimateSegments(glucose, therapy, telemetry)
        db.profileSegmentEstimateDao().clear()
        db.profileSegmentEstimateDao().upsertAll(
            segmentEstimates.map {
                ProfileSegmentEstimateEntity(
                    id = "${it.dayType}:${it.timeSlot}",
                    dayType = it.dayType.name,
                    timeSlot = it.timeSlot.name,
                    isfMmolPerUnit = it.isfMmolPerUnit,
                    crGramPerUnit = it.crGramPerUnit,
                    confidence = it.confidence,
                    isfSampleCount = it.isfSampleCount,
                    crSampleCount = it.crSampleCount,
                    lookbackDays = it.lookbackDays,
                    updatedAt = System.currentTimeMillis()
                )
            }
        )

        profileEstimate?.let { estimate ->
            db.profileEstimateDao().upsert(
                ProfileEstimateEntity(
                    timestamp = System.currentTimeMillis(),
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
                    "calculatedSamples" to (calculatedEstimate?.sampleCount ?: 0)
                )
            )
        } ?: auditLogger.warn(
            "profile_estimate_skipped",
            mapOf("reason" to "insufficient_samples", "lookbackDays" to lookbackDays)
        )

        val riskWindows = windows.count { it.isRiskWindow }
        val lowRisk = windows.count { it.isRiskWindow && it.lowRate >= settings.patternLowRateTrigger }
        val highRisk = windows.count { it.isRiskWindow && it.highRate >= settings.patternHighRateTrigger }

        auditLogger.info(
            "pattern_recalculated",
            mapOf(
                "windows" to windows.size,
                "riskWindows" to riskWindows,
                "lowRiskWindows" to lowRisk,
                "highRiskWindows" to highRisk,
                "weekdayWindows" to windows.count { it.dayType == DayType.WEEKDAY },
                "weekendWindows" to windows.count { it.dayType == DayType.WEEKEND },
                "profileSegments" to segmentEstimates.size,
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
    }

    fun observePatterns() = db.patternDao().observeAll()

    fun observeProfileEstimate() = db.profileEstimateDao().observeActive()

    fun observeProfileSegments() = db.profileSegmentEstimateDao().observeAll()
}
