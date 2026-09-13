package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import io.aaps.copilot.data.local.entity.PhysioContextTagEntity

@Dao
interface AlertAiDatasetDao {
    @Query(
        "SELECT * FROM physio_context_tags " +
            "WHERE tsEnd >= :since AND tsStart <= :through " +
            "ORDER BY tsStart ASC, tsEnd ASC, id ASC LIMIT :limit"
    )
    suspend fun contextEvents(
        since: Long,
        through: Long,
        limit: Int
    ): List<PhysioContextTagEntity>

    @Query(
        "SELECT * FROM planned_activity_events WHERE enabled = 1 " +
            "AND localStartIso <= :latestCandidateLocalIso " +
            "AND ((recurrenceDaysMask != 0 AND " +
            "(recurrenceEndEpochDay IS NULL OR recurrenceEndEpochDay >= :earliestCandidateEpochDay)) " +
            "OR (recurrenceDaysMask = 0 AND localStartIso >= :earliestCandidateLocalIso)) " +
            "ORDER BY localStartIso ASC, eventId ASC LIMIT :limit"
    )
    suspend fun plannedActivityCandidates(
        earliestCandidateLocalIso: String,
        latestCandidateLocalIso: String,
        earliestCandidateEpochDay: Long,
        limit: Int
    ): List<PlannedActivityEventEntity>
}
