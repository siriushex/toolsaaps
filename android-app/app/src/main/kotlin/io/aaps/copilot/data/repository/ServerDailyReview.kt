package io.aaps.copilot.data.repository

import kotlinx.serialization.json.JsonObject

internal data class DailyReviewQuery(val afterSequence: Long = 0, val snapshotSequence: Long? = null)

internal data class DailyReviewSummary(
    val sequence: Long, val jobId: String, val reportDate: String, val zoneId: String,
    val model: String, val storedAtMs: Long, val expiresAtMs: Long, val summary: String,
    val wire: JsonObject
) { override fun toString() = "DailyReviewSummary(redacted)" }

internal data class DailyReviewPage(
    val snapshotSequence: Long, val records: List<DailyReviewSummary>,
    val hasMore: Boolean, val nextAfterSequence: Long?
) { override fun toString() = "DailyReviewPage(redacted)" }

internal enum class DailyReviewSourceState { SOURCE_MATCHED, SOURCE_CHANGED, SOURCE_UNAVAILABLE, SUPERSEDED }

/** Audit text only. There is deliberately no clinical recommendation or action DTO. */
internal data class DailyReviewDetail(
    val jobId: String, val reportDate: String, val zoneId: String, val reportRevision: Long,
    val model: String, val storedAtMs: Long, val expiresAtMs: Long,
    val sourceState: DailyReviewSourceState, val checkedAtMs: Long?, val replyCheckedAtMs: Long,
    val status: String, val summary: String, val findings: List<String>, val missingInputCodes: List<String>,
    val wire: JsonObject
) { override fun toString() = "DailyReviewDetail(redacted)" }
