package io.aaps.copilot.data.local

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

@Entity(tableName = "combo_reads", primaryKeys = ["ownerId", "binding", "readId"])
internal data class ComboReadEntity(
    val ownerId: String, val binding: String, val readId: String,
    val sha256: String, val byteCount: Int
) { override fun toString() = "ComboReadEntity(redacted)" }

@Entity(tableName = "combo_chunks", primaryKeys = ["ownerId", "binding", "readId", "part"],
    foreignKeys = [ForeignKey(entity = ComboReadEntity::class,
        parentColumns = ["ownerId", "binding", "readId"], childColumns = ["ownerId", "binding", "readId"])])
internal data class ComboChunkEntity(
    val ownerId: String, val binding: String, val readId: String, val part: Int, val bytes: ByteArray
) { override fun toString() = "ComboChunkEntity(redacted)" }

internal data class ComboChunkPosition(val part: Int, val byteCount: Int)
internal data class ComboStorageTotals(val reads: Long, val bytes: Long, val storedBytes: Long)

@Dao
internal interface ComboJournalDao {
    @Query("SELECT COUNT(*) AS reads, COALESCE(SUM(byteCount), 0) AS bytes, " +
        "(SELECT COALESCE(SUM(LENGTH(bytes)), 0) FROM combo_chunks) AS storedBytes FROM combo_reads")
    suspend fun totals(): ComboStorageTotals
    @Query("SELECT * FROM combo_reads WHERE ownerId = :owner AND binding = :binding AND readId = :id")
    suspend fun find(owner: String, binding: String, id: String): ComboReadEntity?
    @Query("SELECT * FROM combo_reads WHERE ownerId = :owner AND binding = :binding ORDER BY readId LIMIT :limit")
    suspend fun inventory(owner: String, binding: String, limit: Int): List<ComboReadEntity>
    @Query("SELECT part, LENGTH(bytes) AS byteCount FROM combo_chunks " +
        "WHERE ownerId = :owner AND binding = :binding AND readId = :id ORDER BY part LIMIT :limit")
    suspend fun positions(owner: String, binding: String, id: String, limit: Int): List<ComboChunkPosition>
    @Query("SELECT bytes FROM combo_chunks WHERE ownerId = :owner AND binding = :binding AND readId = :id AND part = :part")
    suspend fun chunk(owner: String, binding: String, id: String, part: Int): ByteArray?
    @Insert suspend fun insertRead(row: ComboReadEntity)
    @Insert suspend fun insertChunk(row: ComboChunkEntity)
}

/** Separate from clinical/AI export databases. No destructive fallback, pruning or runtime owner. */
@Database(entities = [ComboReadEntity::class, ComboChunkEntity::class], version = 1, exportSchema = false)
internal abstract class ComboJournalDatabase : RoomDatabase() {
    abstract fun journalDao(): ComboJournalDao

    companion object {
        const val FILE_NAME = "combo_retained_history_v1.db"

        fun create(context: Context): ComboJournalDatabase {
            val file = File(context.noBackupFilesDir, FILE_NAME)
            for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                val path = File(file.path + suffix).toPath()
                check(!Files.exists(path, NOFOLLOW_LINKS) || Files.isRegularFile(path, NOFOLLOW_LINKS))
            }
            return Room.databaseBuilder(context.applicationContext, ComboJournalDatabase::class.java, file.absolutePath)
                .setJournalMode(JournalMode.TRUNCATE)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.query("PRAGMA journal_mode=TRUNCATE").use {
                            check(it.moveToFirst() && it.getString(0) == "truncate")
                        }
                        db.execSQL("PRAGMA synchronous=FULL")
                        db.query("PRAGMA synchronous").use { check(it.moveToFirst() && it.getInt(0) == 2) }
                    }
                }).build()
        }
    }
}
