package io.aaps.copilot.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.aaps.copilot.data.local.entity.LocalNightscoutV3ClockEntity
import io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity

data class LocalV3DocumentPosition(val eventAtMs: Long?, val modifiedRevision: Long, val payloadBytes: Int)

/** Raw persistence only. Mutations and reads with cursors belong to the transactional store. */
@Dao
interface LocalNightscoutV3Dao {
    @Query("SELECT * FROM local_ns_v3_clock WHERE singleton = 1")
    suspend fun clock(): LocalNightscoutV3ClockEntity?

    @Query("SELECT * FROM local_ns_v3_documents WHERE collection = :collection AND identifier = :id")
    suspend fun get(collection: String, id: String): LocalNightscoutV3DocumentEntity?

    @Query("SELECT COUNT(*) FROM local_ns_v3_documents")
    suspend fun count(): Long

    @Query("SELECT COALESCE(MAX(modifiedRevision), 0) FROM local_ns_v3_documents WHERE collection = :collection")
    suspend fun head(collection: String): Long

    @Query("""SELECT * FROM local_ns_v3_documents
        WHERE collection = :collection AND modifiedRevision > :after AND modifiedRevision <= :through
        ORDER BY modifiedRevision ASC LIMIT :limit""")
    suspend fun history(collection: String, after: Long, through: Long, limit: Int): List<LocalNightscoutV3DocumentEntity>

    @Query("""SELECT eventAtMs, modifiedRevision, payloadBytes FROM local_ns_v3_documents
        WHERE collection = :collection AND modifiedRevision > :after AND modifiedRevision <= :through
        ORDER BY modifiedRevision ASC LIMIT :limit""")
    suspend fun historyPositions(collection: String, after: Long, through: Long, limit: Int): List<LocalV3DocumentPosition>

    @Query("""SELECT eventAtMs, modifiedRevision, payloadBytes FROM local_ns_v3_documents
        WHERE collection = :collection AND deleted = 0 AND eventAtMs > :after
        ORDER BY eventAtMs ASC, identifier ASC LIMIT :limit""")
    suspend fun chronologicalPositions(collection: String, after: Long, limit: Int): List<LocalV3DocumentPosition>

    @Query("""SELECT eventAtMs, modifiedRevision, payloadBytes FROM local_ns_v3_documents
        WHERE collection = :collection AND deleted = 0 AND eventAtMs = :at
        ORDER BY identifier ASC LIMIT :limit""")
    suspend fun atTimePositions(collection: String, at: Long, limit: Int): List<LocalV3DocumentPosition>

    @Query("""SELECT * FROM local_ns_v3_documents
        WHERE collection = :collection AND deleted = 0 AND eventAtMs > :after AND eventAtMs <= :through
        ORDER BY eventAtMs ASC, identifier ASC LIMIT :limit""")
    suspend fun chronological(collection: String, after: Long, through: Long, limit: Int): List<LocalNightscoutV3DocumentEntity>

    @Query("""SELECT EXISTS(SELECT 1 FROM local_ns_v3_documents
        WHERE collection = :collection AND deleted = 0 AND eventAtMs > :after)""")
    suspend fun hasAfterTime(collection: String, after: Long): Boolean

    @Query("""SELECT eventAtMs, modifiedRevision, payloadBytes FROM local_ns_v3_documents
        WHERE collection = :collection AND deleted = 0 AND eventAtMs IS NOT NULL
        ORDER BY eventAtMs DESC, identifier DESC LIMIT :limit""")
    suspend fun latestPositions(collection: String, limit: Int): List<LocalV3DocumentPosition>

    @Query("""SELECT * FROM local_ns_v3_documents
        WHERE collection = :collection AND deleted = 0 AND eventAtMs IS NOT NULL
        ORDER BY eventAtMs DESC, identifier DESC LIMIT :limit""")
    suspend fun latest(collection: String, limit: Int): List<LocalNightscoutV3DocumentEntity>

    @Upsert suspend fun put(document: LocalNightscoutV3DocumentEntity)
    @Upsert suspend fun putClock(clock: LocalNightscoutV3ClockEntity)
}
