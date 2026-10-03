package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.meal.MealUpdateReason

internal enum class MealObservationWriteStatus {
    UPDATED, UNKNOWN_INPUT, UNCONFIRMED, DELETED, MISSING_BELIEF,
    STALE_STORAGE, PENDING_RECONCILIATION, QUARANTINED, INVALID_SAMPLE_TIME,
    ESTIMATOR_REJECTED
}

/** Storage result only, never permission to recommend meal timing. */
internal data class MealObservationWriteResult(
    val status: MealObservationWriteStatus,
    val estimatorReason: MealUpdateReason? = null
)
