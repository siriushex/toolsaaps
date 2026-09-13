package io.aaps.copilot.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.repository.AuditLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CopilotDatabaseCompactor {
    private const val TAG = "CopilotDbCompactor"
    private const val PRIMARY_DB_NAME = "copilot.db"
    private const val MIN_FREE_SPACE_AFTER_VACUUM_BYTES = 512L * 1024L * 1024L

    data class VacuumResult(
        val skipped: Boolean,
        val reason: String?,
        val beforeBytes: Long,
        val afterBytes: Long,
        val durationMs: Long,
        val checkpointBefore: String?,
        val checkpointAfter: String?
    )

    suspend fun vacuumNowForDebug(
        context: Context,
        db: CopilotDatabase,
        auditLogger: AuditLogger
    ): VacuumResult = withContext(Dispatchers.IO) {
        val dbFile = context.getDatabasePath(PRIMARY_DB_NAME)
        val beforeBytes = dbFile.takeIf { it.exists() }?.length() ?: 0L
        val freeBytes = dbFile.parentFile?.usableSpace ?: 0L
        val minRequiredFreeBytes = beforeBytes + MIN_FREE_SPACE_AFTER_VACUUM_BYTES
        if (beforeBytes <= 0L) {
            auditLogger.warn(
                "db_vacuum_debug_skipped",
                mapOf("reason" to "missing_primary_db", "path" to dbFile.absolutePath)
            )
            Log.w(TAG, "skipped missing db path=${dbFile.absolutePath}")
            return@withContext VacuumResult(
                skipped = true,
                reason = "missing_primary_db",
                beforeBytes = beforeBytes,
                afterBytes = beforeBytes,
                durationMs = 0L,
                checkpointBefore = null,
                checkpointAfter = null
            )
        }
        if (freeBytes in 1 until minRequiredFreeBytes) {
            auditLogger.warn(
                "db_vacuum_debug_skipped",
                mapOf(
                    "reason" to "insufficient_free_space",
                    "beforeBytes" to beforeBytes,
                    "freeBytes" to freeBytes,
                    "minRequiredFreeBytes" to minRequiredFreeBytes
                )
            )
            Log.w(
                TAG,
                "skipped insufficient_free_space beforeBytes=$beforeBytes freeBytes=$freeBytes required=$minRequiredFreeBytes"
            )
            return@withContext VacuumResult(
                skipped = true,
                reason = "insufficient_free_space",
                beforeBytes = beforeBytes,
                afterBytes = beforeBytes,
                durationMs = 0L,
                checkpointBefore = null,
                checkpointAfter = null
            )
        }

        val startedAt = SystemClock.elapsedRealtime()
        val writableDb = db.openHelper.writableDatabase
        val checkpointBefore = runWalCheckpoint(writableDb)
        writableDb.execSQL("VACUUM")
        val checkpointAfter = runWalCheckpoint(writableDb)
        val durationMs = SystemClock.elapsedRealtime() - startedAt
        val afterBytes = dbFile.takeIf { it.exists() }?.length() ?: 0L
        auditLogger.info(
            "db_vacuum_debug_completed",
            mapOf(
                "beforeBytes" to beforeBytes,
                "afterBytes" to afterBytes,
                "reclaimedBytes" to (beforeBytes - afterBytes).coerceAtLeast(0L),
                "durationMs" to durationMs,
                "checkpointBefore" to checkpointBefore,
                "checkpointAfter" to checkpointAfter,
                "freeBytesBefore" to freeBytes
            )
        )
        Log.i(
            TAG,
            "completed beforeBytes=$beforeBytes afterBytes=$afterBytes durationMs=$durationMs checkpointBefore=$checkpointBefore checkpointAfter=$checkpointAfter"
        )
        VacuumResult(
            skipped = false,
            reason = null,
            beforeBytes = beforeBytes,
            afterBytes = afterBytes,
            durationMs = durationMs,
            checkpointBefore = checkpointBefore,
            checkpointAfter = checkpointAfter
        )
    }

    private fun runWalCheckpoint(db: androidx.sqlite.db.SupportSQLiteDatabase): String {
        return db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            if (!cursor.moveToFirst()) return@use "no_rows"
            "busy=${cursor.getLong(0)},log=${cursor.getLong(1)},checkpointed=${cursor.getLong(2)}"
        }
    }
}
