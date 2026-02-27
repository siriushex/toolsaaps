package io.aaps.copilot.data.repository

import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.PatternWindowEntity
import io.aaps.copilot.data.local.entity.ProfileEstimateEntity
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.predict.PatternAnalyzer
import io.aaps.copilot.domain.predict.ProfileEstimator
import kotlinx.coroutines.flow.Flow

class AnalyticsRepository(
    private val db: CopilotDatabase,
    private val patternAnalyzer: PatternAnalyzer,
    private val profileEstimator: ProfileEstimator,
    private val gson: Gson,
    private val auditLogger: AuditLogger
) {

    suspend fun recalculate(baseTargetMmol: Double) {
        val historyStart = System.currentTimeMillis() - 180L * 24 * 60 * 60 * 1000
        val glucose = db.glucoseDao().since(historyStart).map { it.toDomain() }
        val therapy = db.therapyDao().since(historyStart).map { it.toDomain(gson) }

        val windows = patternAnalyzer.analyze(
            glucoseHistory = glucose,
            baseTargetMmol = baseTargetMmol
        )

        db.patternDao().clear()
        db.patternDao().upsertAll(
            windows.map {
                PatternWindowEntity(
                    dayType = it.dayType.name,
                    hour = it.hour,
                    lowRate = it.lowRate,
                    highRate = it.highRate,
                    recommendedTargetMmol = it.recommendedTargetMmol,
                    updatedAt = System.currentTimeMillis()
                )
            }
        )

        profileEstimator.estimate(glucose, therapy)?.let { estimate ->
            db.profileEstimateDao().upsert(
                ProfileEstimateEntity(
                    timestamp = System.currentTimeMillis(),
                    isfMmolPerUnit = estimate.isfMmolPerUnit,
                    crGramPerUnit = estimate.crGramPerUnit,
                    confidence = estimate.confidence,
                    sampleCount = estimate.sampleCount
                )
            )
            auditLogger.info(
                "profile_estimate_updated",
                mapOf(
                    "isf" to estimate.isfMmolPerUnit,
                    "cr" to estimate.crGramPerUnit,
                    "confidence" to estimate.confidence,
                    "samples" to estimate.sampleCount
                )
            )
        }

        auditLogger.info(
            "pattern_recalculated",
            mapOf(
                "windows" to windows.size,
                "weekdayWindows" to windows.count { it.dayType == DayType.WEEKDAY },
                "weekendWindows" to windows.count { it.dayType == DayType.WEEKEND }
            )
        )
    }

    fun observePatterns() = db.patternDao().observeAll()

    fun observeProfileEstimate() = db.profileEstimateDao().observeActive()
}
