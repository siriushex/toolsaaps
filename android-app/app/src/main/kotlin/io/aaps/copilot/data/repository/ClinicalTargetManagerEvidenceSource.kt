package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.dao.ClinicalTargetManagerEvidenceProjection
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ClinicalTargetManagerEvidence(
    val id: String,
    val timestamp: Long,
    val outcome: String,
    val winnerJson: String?,
    val reasonCodesJson: String
)

internal data class ClinicalTargetManagerEvidenceSnapshot(
    val complete: Boolean,
    val rows: List<ClinicalTargetManagerEvidence>
) {
    init {
        require(complete || rows.isEmpty())
    }

    companion object {
        val EMPTY = ClinicalTargetManagerEvidenceSnapshot(true, emptyList())
        val INCOMPLETE = ClinicalTargetManagerEvidenceSnapshot(false, emptyList())
    }
}

internal object ClinicalTargetManagerEvidenceSource {
    // Thirty-two days at the report's five-minute evidence cadence. MAX_ROWS + 1 is
    // queried as a sentinel so an over-limit result is omitted, never truncated as complete.
    const val MAX_ROWS = 32 * 24 * 12
    private const val MAX_EVALUATION_LEAD_MS = 90L * 60L * 1_000L
    private const val CHECKPOINT_ROWS = 256
    private val ORDER = compareBy<ClinicalTargetManagerEvidenceProjection> { it.timestamp }
        .thenBy { it.id }

    suspend fun load(
        preparedFromTs: Long,
        throughTs: Long,
        query: suspend (
            fromTs: Long,
            throughTs: Long,
            limit: Int
        ) -> List<ClinicalTargetManagerEvidenceProjection>
    ): ClinicalTargetManagerEvidenceSnapshot {
        if (preparedFromTs > throughTs) return ClinicalTargetManagerEvidenceSnapshot.INCOMPLETE
        val activityFrom = ClinicalPlannedActivityPeriodPolicy
            .earliestPotentialStart(preparedFromTs)
            .coerceAtLeast(0L)
        val queryFrom = runCatching {
            Math.subtractExact(activityFrom, MAX_EVALUATION_LEAD_MS)
        }.getOrDefault(Long.MIN_VALUE).coerceAtLeast(0L)
        val projections = query(queryFrom, throughTs, MAX_ROWS + 1)
        if (projections.size > MAX_ROWS) return ClinicalTargetManagerEvidenceSnapshot.INCOMPLETE

        val output = ArrayList<ClinicalTargetManagerEvidence>(projections.size)
        var previous: ClinicalTargetManagerEvidenceProjection? = null
        projections.forEachIndexed { index, row ->
            if (index % CHECKPOINT_ROWS == 0) currentCoroutineContext().ensureActive()
            if (previous?.let { ORDER.compare(it, row) > 0 } == true) {
                return ClinicalTargetManagerEvidenceSnapshot.INCOMPLETE
            }
            output += ClinicalTargetManagerEvidence(
                id = row.id,
                timestamp = row.timestamp,
                outcome = row.outcome,
                winnerJson = row.winnerJson,
                reasonCodesJson = row.reasonCodesJson
            )
            previous = row
        }
        currentCoroutineContext().ensureActive()
        return ClinicalTargetManagerEvidenceSnapshot(
            complete = true,
            rows = Collections.unmodifiableList(output)
        )
    }
}
