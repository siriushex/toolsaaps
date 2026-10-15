package io.aaps.copilot.data.repository

import android.content.Context
import androidx.room.*
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "export_progress")
internal data class RoomAiExportProgress(
    @PrimaryKey val id: Int = 1,
    val device: String,
    val run: String,
    val fromMs: Long,
    val throughMs: Long,
    val stream: Int = 0,
    val afterRowId: Long = 0,
    val afterEventAtMs: Long = 0,
    val scannedRows: Long = 0,
    val omittedRows: Long = 0,
    val complete: Boolean = false,
    val fullScan: Boolean = false,
    val lastFullThroughMs: Long = 0,
    val deletionAfterTimeMs: Long = 0,
    val deletionAfterKey: String = "",
    val lastPlan: String = ""
) { override fun toString() = "RoomAiExportProgress(redacted)" }

@Serializable
@Entity(tableName = "export_index", indices = [Index(value = ["eventAtMs", "key"])])
internal data class RoomAiExportEntry(
    @PrimaryKey val key: String,
    val recordId: String,
    val revision: Int,
    val eventAtMs: Long,
    val kind: String,
    val source: String,
    val quality: String,
    val fingerprint: String,
    val seenRun: String,
    val deleted: Boolean = false
) { override fun toString() = "RoomAiExportEntry(redacted)" }

@Dao
internal interface RoomAiExportDao {
    @Query("SELECT * FROM export_progress WHERE id = 1") suspend fun progress(): RoomAiExportProgress?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveProgress(progress: RoomAiExportProgress)
    @Query("SELECT * FROM export_index WHERE `key` = :key") suspend fun find(key: String): RoomAiExportEntry?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveEntries(rows: List<RoomAiExportEntry>)
    @Query("SELECT * FROM export_index INDEXED BY index_export_index_eventAtMs_key WHERE seenRun != :run AND deleted = 0 " +
        "AND (eventAtMs, `key`) > (:afterTimeMs, :afterKey) AND eventAtMs <= :throughMs " +
        "ORDER BY eventAtMs, `key` LIMIT :limit")
    suspend fun stale(run: String, throughMs: Long, afterTimeMs: Long, afterKey: String, limit: Int): List<RoomAiExportEntry>
    @Query("DELETE FROM export_index WHERE eventAtMs <= :beforeMs") suspend fun prune(beforeMs: Long)
    @Query("SELECT * FROM export_index ORDER BY `key` LIMIT :limit") suspend fun entries(limit: Int): List<RoomAiExportEntry>
}

/** Metadata only. Clinical source database/schema are never written by this journal. */
@Database(entities = [RoomAiExportEntry::class, RoomAiExportProgress::class], version = 1, exportSchema = false)
internal abstract class RoomAiExportDatabase : RoomDatabase() {
    abstract fun exportDao(): RoomAiExportDao

    companion object {
        fun create(context: Context): RoomAiExportDatabase = Room.databaseBuilder(context.applicationContext,
            RoomAiExportDatabase::class.java, java.io.File(context.noBackupFilesDir, "server_ai_export_index_v1.db").absolutePath).build()
    }
}

internal class RoomAiExportIndex(private val db: RoomAiExportDatabase) {
    suspend fun progress() = db.exportDao().progress()
    suspend fun find(key: String) = db.exportDao().find(key)
    suspend fun stale(progress: RoomAiExportProgress, limit: Int): List<RoomAiExportEntry> {
        val time = maxOf(progress.fromMs, progress.deletionAfterTimeMs)
        val key = if (time == progress.deletionAfterTimeMs) progress.deletionAfterKey else ""
        return db.exportDao().stale(progress.run, progress.throughMs, time, key, limit)
    }
    suspend fun entries(limit: Int = 64): List<RoomAiExportEntry> {
        require(limit in 1..256)
        return db.exportDao().entries(limit)
    }

    suspend fun commit(progress: RoomAiExportProgress, rows: List<RoomAiExportEntry>) = db.withTransaction {
        val prior = db.exportDao().progress()
        check(prior == null || prior.device == progress.device)
        if (prior?.lastPlan != progress.lastPlan) {
            db.exportDao().saveEntries(rows)
            db.exportDao().saveProgress(progress)
        }
    }

    suspend fun prune(beforeMs: Long) = db.exportDao().prune(beforeMs)
}
