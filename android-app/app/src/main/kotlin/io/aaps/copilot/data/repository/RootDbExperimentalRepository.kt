package io.aaps.copilot.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.util.UnitConverter
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class RootDbExperimentalRepository(
    private val context: Context,
    private val db: CopilotDatabase,
    private val settingsStore: AppSettingsStore,
    private val auditLogger: AuditLogger
) {

    suspend fun syncIfEnabled() = withContext(Dispatchers.IO) {
        val settings = settingsStore.settings.first()
        if (!settings.rootExperimentalEnabled) return@withContext
        if (!isRootAvailable()) {
            auditLogger.warn("root_db_sync_skipped", mapOf("reason" to "root_unavailable"))
            return@withContext
        }

        val sourceDbPath = findAapsDbPath()
        if (sourceDbPath == null) {
            auditLogger.warn("root_db_sync_skipped", mapOf("reason" to "aaps_db_not_found"))
            return@withContext
        }

        val localDir = File(context.cacheDir, "root-db").apply { mkdirs() }
        val localDb = File(localDir, "androidaps.db")
        val copied = runRootCommand("cp '$sourceDbPath' '${localDb.absolutePath}' && chmod 644 '${localDb.absolutePath}'")
        if (!copied || !localDb.exists()) {
            auditLogger.warn("root_db_sync_failed", mapOf("reason" to "copy_failed", "source" to sourceDbPath))
            return@withContext
        }

        val imported = importGlucose(localDb)
        auditLogger.info(
            "root_db_sync_completed",
            mapOf("source" to sourceDbPath, "importedGlucose" to imported)
        )
    }

    private suspend fun importGlucose(localDb: File): Int {
        val sqlDb = runCatching {
            SQLiteDatabase.openDatabase(localDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrNull() ?: return 0

        return runCatching {
            val maxTs = db.glucoseDao().maxTimestamp() ?: 0L
            val rows = mutableListOf<GlucoseSampleEntity>()
            val tables = mutableListOf<String>()
            sqlDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                while (cursor.moveToNext()) {
                    tables += cursor.getString(0)
                }
            }

            tables.forEach { table ->
                val cols = tableColumns(sqlDb, table)
                val tsCol = cols.firstOrNull {
                    it.equals("date", true) ||
                        it.equals("timestamp", true) ||
                        it.equals("time", true) ||
                        it.equals("created_at", true) ||
                        it.equals("mills", true)
                } ?: return@forEach
                val glucoseCol = cols.firstOrNull {
                    it.equals("sgv", true) ||
                        it.equals("glucose", true) ||
                        it.equals("value", true) ||
                        it.equals("mgdl", true) ||
                        it.equals("bg", true)
                } ?: return@forEach

                val sql = "SELECT $tsCol, $glucoseCol FROM $table WHERE $tsCol > ? ORDER BY $tsCol ASC LIMIT 6000"
                sqlDb.rawQuery(sql, arrayOf(maxTs.toString())).use { cursor ->
                    while (cursor.moveToNext()) {
                        val rawTs = cursor.getLong(0)
                        val normalizedTs = normalizeTimestamp(rawTs)
                        if (normalizedTs <= maxTs) continue
                        val rawGlucose = cursor.getDouble(1)
                        val mmol = if (rawGlucose > 35.0) UnitConverter.mgdlToMmol(rawGlucose) else rawGlucose
                        if (mmol !in 1.0..33.0) continue
                        rows += GlucoseSampleEntity(
                            timestamp = normalizedTs,
                            mmol = mmol,
                            source = "aaps_root_db",
                            quality = "OK"
                        )
                    }
                }
            }

            val deduped = rows.distinctBy { it.timestamp }.sortedBy { it.timestamp }
            if (deduped.isNotEmpty()) {
                db.glucoseDao().upsertAll(deduped)
            }
            deduped.size
        }.getOrDefault(0).also {
            sqlDb.close()
        }
    }

    private fun tableColumns(sqlDb: SQLiteDatabase, table: String): List<String> {
        val cols = mutableListOf<String>()
        runCatching {
            sqlDb.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                while (cursor.moveToNext()) {
                    cols += cursor.getString(1)
                }
            }
        }
        return cols
    }

    private fun normalizeTimestamp(rawTs: Long): Long {
        if (rawTs < 10_000_000_000L) return rawTs * 1000L
        return rawTs
    }

    private fun findAapsDbPath(): String? {
        val candidates = listOf(
            "/data/data/info.nightscout.androidaps/databases/androidaps.db",
            "/data/user/0/info.nightscout.androidaps/databases/androidaps.db",
            "/data/data/info.nightscout.aaps/databases/androidaps.db",
            "/data/user/0/info.nightscout.aaps/databases/androidaps.db"
        )
        return candidates.firstOrNull { runRootCommand("[ -f '$it' ]") }
    }

    private fun isRootAvailable(): Boolean = runRootCommand("id")

    private fun runRootCommand(command: String): Boolean {
        return runCatching {
            val process = ProcessBuilder("sh", "-c", "su -c \"$command\"").start()
            process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }
}
