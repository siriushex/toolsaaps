package io.aaps.copilot.data.repository

import com.google.gson.JsonParser
import io.aaps.copilot.data.local.dao.AlertAiAnalysisDao
import io.aaps.copilot.data.local.dao.AlertEventDao
import io.aaps.copilot.data.local.dao.GlucoseAlertEpisodeProjection
import io.aaps.copilot.data.local.entity.AlertAiAnalysisEntity
import io.aaps.copilot.data.local.entity.AlertEventEntity
import io.aaps.copilot.domain.alerts.AlertCauseSnapshot
import io.aaps.copilot.domain.alerts.AlertCauseSnapshotCodec
import io.aaps.copilot.domain.alerts.AlertCauseCode
import io.aaps.copilot.util.ordinaryExceptionOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class AlertEpisodeSummary(
    val episodeId: String,
    val direction: String,
    val stage: String,
    val status: String,
    val severity: String,
    val createdAt: Long,
    val updatedAt: Long,
    val resolvedAt: Long?,
    val suppressionUntil: Long?,
    val notShownOff: Boolean,
    val localCauseCode: String?
)

enum class AlertEvidenceKind {
    CURRENT_GLUCOSE,
    FORECAST_5,
    FORECAST_30,
    FORECAST_60,
    SENSOR_QUALITY,
    UAM,
    DELIVERY,
    INSULIN,
    TARGET,
    CIRCADIAN,
    EVENT_CONTEXT,
    DATA_QUALITY
}

data class AlertEvidenceItem(
    val kind: AlertEvidenceKind,
    val glucoseMmol: Double? = null,
    val timestamp: Long? = null
)

data class AlertHistoryWindow(
    val fromTsInclusive: Long,
    val throughTsExclusive: Long
)

data class AlertEpisodeDetail(
    val summary: AlertEpisodeSummary,
    val localAdviceCode: String?,
    val evidence: List<AlertEvidenceItem>,
    val aiPresentation: AlertAiPresentation?
)

class AlertsRepository(
    private val alertDao: AlertEventDao,
    private val aiDao: AlertAiAnalysisDao
) {
    fun observeHistory(
        fromTs: Long,
        throughTs: Long,
        limit: Int = MAX_HISTORY_EPISODES
    ): Flow<List<AlertEpisodeSummary>> = alertDao
        .observeGlucoseHistory(fromTs, throughTs, limit.coerceIn(1, MAX_HISTORY_EPISODES))
        .map { rows -> rows.map { row -> row.toSummary() } }

    fun observeActive(): Flow<AlertEpisodeSummary?> = alertDao
        .observeLatestUnresolvedGlucoseEpisode()
        .map { it?.toSummary() }

    fun observeDetail(episodeId: String): Flow<AlertEpisodeDetail?> = combine(
        alertDao.observeGlucoseEpisode(episodeId),
        aiDao.observeByEpisodeId(episodeId)
    ) { projection, ai ->
        projection?.toDetail(ai)
    }

    suspend fun validatedGlucoseEpisodeId(candidate: String?): String? {
        val id = candidate?.takeIf(::isSafeEpisodeId) ?: return null
        return alertDao.byEpisodeId(id)?.takeIf { it.isGlucoseEpisode() }?.episodeId
    }

    private fun GlucoseAlertEpisodeProjection.toSummary() =
        event.toSummary(receiptSuppressionUntil, hasDeliveredReceipt)

    private fun AlertEventEntity.toSummary(
        receiptSuppressionUntil: Long?,
        hasDeliveredReceipt: Boolean
    ) = AlertEpisodeSummary(
        episodeId = episodeId,
        direction = eventType.removePrefix("GLUCOSE_ALERT_"),
        stage = stage.takeIf { value -> GlucoseAlertState.entries.any { it.name == value } } ?: "UNKNOWN",
        status = status.takeIf { value -> AlertEpisodeStatus.entries.any { it.name == value } } ?: "UNKNOWN",
        severity = severity.takeIf { value -> GlucoseAlertState.entries.any { it.name == value } } ?: "UNKNOWN",
        createdAt = createdAt,
        updatedAt = updatedAt,
        resolvedAt = resolvedAt,
        suppressionUntil = receiptSuppressionUntil,
        notShownOff = receiptSuppressionUntil != null && !hasDeliveredReceipt,
        localCauseCode = trustedCauseCode()?.name
    )

    private fun GlucoseAlertEpisodeProjection.toDetail(ai: AlertAiAnalysisEntity?): AlertEpisodeDetail {
        val safeSnapshot = AlertCauseSnapshotCodec.sanitize(AlertCauseSnapshot(event.localSnapshotJson))
        val evidence = safeSnapshot?.canonicalJson?.let(::evidenceFromSnapshot).orEmpty()
        val aiPresentation = ai?.takeIf { it.status == AI_COMPLETED }
            ?.resultJson
            ?.let(AlertAiTypedPresentationParser::parse)
        return AlertEpisodeDetail(
            summary = toSummary(),
            localAdviceCode = event.trustedCauseCode()?.name,
            evidence = evidence,
            aiPresentation = aiPresentation
        )
    }

    private fun evidenceFromSnapshot(json: String): List<AlertEvidenceItem> = ordinaryExceptionOrNull {
        val root = JsonParser.parseString(json).asJsonObject
        val glucose = root.getAsJsonObject("glucose")
        val snapshotTimestamp = glucose?.nonNegativeLong("currentTimestamp")
            ?: root.nonNegativeLong("nowTs")
            ?: root.nonNegativeLong("cycleTimestamp")
        buildList {
            glucose?.finiteDouble("currentMmol")?.let {
                add(AlertEvidenceItem(AlertEvidenceKind.CURRENT_GLUCOSE, it, snapshotTimestamp))
            }
            glucose?.finiteDouble("forecast5Mmol")?.let {
                add(AlertEvidenceItem(AlertEvidenceKind.FORECAST_5, it, snapshotTimestamp))
            }
            glucose?.finiteDouble("forecast30Mmol")?.let {
                add(AlertEvidenceItem(AlertEvidenceKind.FORECAST_30, it, snapshotTimestamp))
            }
            glucose?.finiteDouble("forecast60Mmol")?.let {
                add(AlertEvidenceItem(AlertEvidenceKind.FORECAST_60, it, snapshotTimestamp))
            }
            root.getAsJsonArray("evidence")?.mapNotNull { item ->
                item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
                    ?.toEvidenceKind()
            }?.distinct()?.forEach { kind ->
                add(AlertEvidenceItem(kind = kind, timestamp = snapshotTimestamp))
            }
        }.take(MAX_EVIDENCE_ITEMS)
    }.orEmpty()

    private fun AlertEventEntity.isGlucoseEpisode(): Boolean =
        eventType == "GLUCOSE_ALERT_LOW" || eventType == "GLUCOSE_ALERT_HIGH"

    private fun AlertEventEntity.trustedCauseCode(): AlertCauseCode? =
        causeCode?.let { value -> AlertCauseCode.entries.firstOrNull { it.name == value } }

    companion object {
        const val HISTORY_WINDOW_MS = 30L * 24L * 60L * 60L * 1_000L
        const val MAX_HISTORY_EPISODES = 512
        private const val MAX_EVIDENCE_ITEMS = 12
        private const val AI_COMPLETED = "COMPLETED"
        private val SAFE_EPISODE_ID = Regex("glucose-alert-[A-Za-z0-9_-]{1,80}")

        fun isSafeEpisodeId(value: String): Boolean = SAFE_EPISODE_ID.matches(value)

        fun historyWindowAt(nowTs: Long): AlertHistoryWindow {
            val safeNow = nowTs.coerceAtLeast(0L)
            return AlertHistoryWindow(
                fromTsInclusive = (safeNow - HISTORY_WINDOW_MS).coerceAtLeast(0L),
                throughTsExclusive = safeNow
            )
        }
    }
}

private fun com.google.gson.JsonObject.finiteDouble(key: String): Double? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asDouble?.takeIf(Double::isFinite)

private fun com.google.gson.JsonObject.nonNegativeLong(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asLong?.takeIf { it >= 0L }

private fun String.toEvidenceKind(): AlertEvidenceKind? = when {
    startsWith("SENSOR_") -> AlertEvidenceKind.SENSOR_QUALITY
    startsWith("UAM_") -> AlertEvidenceKind.UAM
    startsWith("DELIVERY_") -> AlertEvidenceKind.DELIVERY
    startsWith("INSULIN_") || this == "POSITIVE_IOB_LOW" -> AlertEvidenceKind.INSULIN
    startsWith("TARGET_") -> AlertEvidenceKind.TARGET
    startsWith("CIRCADIAN_") -> AlertEvidenceKind.CIRCADIAN
    startsWith("EVENT_") && this != "EVENT_CONTEXT_UNAVAILABLE" -> AlertEvidenceKind.EVENT_CONTEXT
    this in DATA_QUALITY_EVIDENCE -> AlertEvidenceKind.DATA_QUALITY
    else -> null
}

private val DATA_QUALITY_EVIDENCE = setOf(
    "CURRENT_EVIDENCE_MISSING",
    "CURRENT_EVIDENCE_STALE",
    "CYCLE_IDENTITY_MISMATCH",
    "EVENT_CONTEXT_UNAVAILABLE",
    "CAUSE_AMBIGUOUS"
)
