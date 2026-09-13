package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.aaps.copilot.data.local.entity.ClinicalReportEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ClinicalReportDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInitial(entity: ClinicalReportEntity): Long

    @Query("SELECT * FROM clinical_reports ORDER BY createdAt DESC, requestId DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<ClinicalReportEntity>

    @Query("SELECT * FROM clinical_reports WHERE requestId = :requestId LIMIT 1")
    suspend fun byId(requestId: String): ClinicalReportEntity?

    @Query(
        "SELECT * FROM clinical_reports WHERE status IN (:statuses) " +
            "ORDER BY createdAt DESC, requestId DESC LIMIT :limit"
    )
    suspend fun activeForRecovery(
        statuses: List<String>,
        limit: Int
    ): List<ClinicalReportEntity>

    @Query(
        "SELECT * FROM clinical_reports WHERE status IN (:statuses) " +
            "AND sanitizedError = :sanitizedError " +
            "ORDER BY createdAt DESC, requestId DESC LIMIT 1"
    )
    suspend fun latestUnknownOutcomeGuard(
        statuses: List<String>,
        sanitizedError: String
    ): ClinicalReportEntity?

    @Query(
        "UPDATE clinical_reports SET requestedFromTs = :requestedFromTs, " +
            "requestedThroughTs = :requestedThroughTs, requestHash = :requestHash, " +
            "coverageJson = :coverageJson, localSummaryJson = :localSummaryJson, " +
            "schemaVersion = :schemaVersion " +
            "WHERE requestId = :requestId AND status IN (:expectedStatuses) " +
            "AND sanitizedError = :sanitizedError"
    )
    suspend fun refreshUnknownOutcomeLocalIfStatusIn(
        requestId: String,
        expectedStatuses: List<String>,
        sanitizedError: String,
        requestedFromTs: Long,
        requestedThroughTs: Long,
        requestHash: String,
        coverageJson: String,
        localSummaryJson: String,
        schemaVersion: Int
    ): Int

    @Query(
        "UPDATE clinical_reports SET status = :acknowledgedStatus " +
            "WHERE requestId = :requestId AND status IN (:expectedStatuses) " +
            "AND sanitizedError = :sanitizedError"
    )
    suspend fun acknowledgeUnknownOutcomeIfStatusIn(
        requestId: String,
        expectedStatuses: List<String>,
        sanitizedError: String,
        acknowledgedStatus: String
    ): Int

    @Query(
        "DELETE FROM clinical_reports WHERE requestId = :requestId " +
            "AND status = :acknowledgedStatus AND sanitizedError = :sanitizedError"
    )
    suspend fun deleteAcknowledgedUnknownOutcome(
        requestId: String,
        acknowledgedStatus: String,
        sanitizedError: String
    ): Int

    @Transaction
    suspend fun acknowledgeAndDeleteUnknownOutcome(
        requestId: String,
        expectedStatuses: List<String>,
        sanitizedError: String,
        acknowledgedStatus: String
    ): Int {
        val acknowledged = acknowledgeUnknownOutcomeIfStatusIn(
            requestId = requestId,
            expectedStatuses = expectedStatuses,
            sanitizedError = sanitizedError,
            acknowledgedStatus = acknowledgedStatus
        )
        if (acknowledged != 1) return acknowledged
        check(
            deleteAcknowledgedUnknownOutcome(
                requestId = requestId,
                acknowledgedStatus = acknowledgedStatus,
                sanitizedError = sanitizedError
            ) == 1
        ) {
            "Acknowledged clinical report guard deletion conflict"
        }
        return acknowledged
    }

    @Query("SELECT * FROM clinical_reports ORDER BY createdAt DESC, requestId DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<ClinicalReportEntity>>

    @Query(
        "UPDATE clinical_reports SET requestedFromTs = 0, requestedThroughTs = 0, " +
            "requestHash = '', coverageJson = '{}', localSummaryJson = '{}', " +
            "responseJson = NULL, renderedText = NULL, model = NULL " +
            "WHERE createdAt < :olderThan AND status IN (:protectedStatuses) " +
            "AND sanitizedError = :protectedSanitizedError"
    )
    suspend fun redactUnknownOutcomeGuardsOlderThan(
        olderThan: Long,
        protectedStatuses: List<String>,
        protectedSanitizedError: String
    ): Int

    @Query(
        "DELETE FROM clinical_reports WHERE createdAt < :olderThan " +
            "AND (status NOT IN (:protectedStatuses) OR sanitizedError IS NULL " +
            "OR sanitizedError != :protectedSanitizedError)"
    )
    suspend fun deleteOlderThan(
        olderThan: Long,
        protectedStatuses: List<String>,
        protectedSanitizedError: String
    ): Int

    suspend fun updateIfStatusIn(
        entity: ClinicalReportEntity,
        expectedStatuses: List<String>
    ): Int = updateRowIfStatusIn(
        requestId = entity.requestId,
        status = entity.status,
        requestedFromTs = entity.requestedFromTs,
        requestedThroughTs = entity.requestedThroughTs,
        requestHash = entity.requestHash,
        coverageJson = entity.coverageJson,
        localSummaryJson = entity.localSummaryJson,
        responseJson = entity.responseJson,
        renderedText = entity.renderedText,
        model = entity.model,
        provider = entity.provider,
        schemaVersion = entity.schemaVersion,
        createdAt = entity.createdAt,
        completedAt = entity.completedAt,
        sanitizedError = entity.sanitizedError,
        expectedStatuses = expectedStatuses
    )

    @Query(
        "UPDATE clinical_reports SET status = :status, requestedFromTs = :requestedFromTs, " +
            "requestedThroughTs = :requestedThroughTs, requestHash = :requestHash, " +
            "coverageJson = :coverageJson, localSummaryJson = :localSummaryJson, " +
            "responseJson = :responseJson, renderedText = :renderedText, model = :model, " +
            "provider = :provider, schemaVersion = :schemaVersion, createdAt = :createdAt, " +
            "completedAt = :completedAt, " +
            "sanitizedError = :sanitizedError WHERE requestId = :requestId " +
            "AND status IN (:expectedStatuses)"
    )
    suspend fun updateRowIfStatusIn(
        requestId: String,
        status: String,
        requestedFromTs: Long,
        requestedThroughTs: Long,
        requestHash: String,
        coverageJson: String,
        localSummaryJson: String,
        responseJson: String?,
        renderedText: String?,
        model: String?,
        provider: String,
        schemaVersion: Int,
        createdAt: Long,
        completedAt: Long?,
        sanitizedError: String?,
        expectedStatuses: List<String>
    ): Int

    @Query(
        "UPDATE clinical_reports SET status = :interruptedStatus, " +
            "completedAt = :completedAt, sanitizedError = :sanitizedError " +
            "WHERE requestId = :requestId AND status IN (:expectedStatuses)"
    )
    suspend fun markInterruptedIfStatusIn(
        requestId: String,
        expectedStatuses: List<String>,
        interruptedStatus: String,
        completedAt: Long,
        sanitizedError: String
    ): Int
}
