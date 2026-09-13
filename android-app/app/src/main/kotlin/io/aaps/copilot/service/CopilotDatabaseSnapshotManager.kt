package io.aaps.copilot.service

import android.content.Context
import android.util.Log
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.repository.AuditLogger
import java.io.File

object CopilotDatabaseSnapshotManager {
    private const val TAG = "CopilotDbSnapshot"

    internal const val SNAPSHOT_DB_NAME = "copilot_snapshot.db"
    internal const val BACKUP_DB_NAME = "copilot-backup.db"
    internal const val MAX_AUTOMATIC_SNAPSHOT_SOURCE_BYTES = 768L * 1024L * 1024L
    private const val PRIMARY_DB_NAME = "copilot.db"
    private const val SNAPSHOT_STAGE_DB_NAME = "copilot_snapshot_stage.db"

    data class SnapshotRefreshResult(
        val success: Boolean,
        val snapshotPath: String,
        val backupPath: String,
        val snapshotIntegrity: String?,
        val backupRotated: Boolean,
        val snapshotTransport: String?,
        val backupTransport: String?,
        val error: String?
    )

    fun latestRollingCopyTimestamp(context: Context): Long = latestRollingCopyTimestamp(context.filesDir)

    internal fun latestRollingCopyTimestamp(filesDir: File): Long {
        val snapshotTs = File(filesDir, SNAPSHOT_DB_NAME).takeIf { it.exists() }?.lastModified() ?: 0L
        val backupTs = File(filesDir, BACKUP_DB_NAME).takeIf { it.exists() }?.lastModified() ?: 0L
        return maxOf(snapshotTs, backupTs)
    }

    fun isRefreshDue(context: Context, nowTs: Long, intervalMs: Long): Boolean =
        isRefreshDue(context.filesDir, nowTs, intervalMs)

    internal fun isRefreshDue(filesDir: File, nowTs: Long, intervalMs: Long): Boolean {
        if (intervalMs <= 0L) return true
        val lastRefreshTs = latestRollingCopyTimestamp(filesDir)
        if (lastRefreshTs <= 0L) return true
        return nowTs - lastRefreshTs >= intervalMs
    }

    internal fun isAutomaticSnapshotAllowed(sourceDbSizeBytes: Long): Boolean {
        return sourceDbSizeBytes <= MAX_AUTOMATIC_SNAPSHOT_SOURCE_BYTES
    }

    suspend fun refreshRollingCopies(
        context: Context,
        db: CopilotDatabase,
        auditLogger: AuditLogger,
        allowLargeSource: Boolean = false
    ): SnapshotRefreshResult {
        val filesDir = context.filesDir
        val cacheDir = context.cacheDir
        val snapshot = fileSet(filesDir, SNAPSHOT_DB_NAME)
        val backup = fileSet(filesDir, BACKUP_DB_NAME)
        val stage = fileSet(cacheDir, SNAPSHOT_STAGE_DB_NAME)
        val sourceDb = context.getDatabasePath(PRIMARY_DB_NAME)
        val sourceDbSizeBytes = sourceDb.takeIf { it.exists() }?.length() ?: 0L

        if (!allowLargeSource && !isAutomaticSnapshotAllowed(sourceDbSizeBytes)) {
            auditLogger.info(
                "db_snapshot_refresh_skipped",
                mapOf(
                    "reason" to "source_db_too_large",
                    "sourceSizeBytes" to sourceDbSizeBytes,
                    "maxAutomaticSnapshotSourceBytes" to MAX_AUTOMATIC_SNAPSHOT_SOURCE_BYTES
                )
            )
            return SnapshotRefreshResult(
                success = false,
                snapshotPath = snapshot.db.absolutePath,
                backupPath = backup.db.absolutePath,
                snapshotIntegrity = null,
                backupRotated = false,
                snapshotTransport = null,
                backupTransport = null,
                error = "source_db_too_large"
            )
        }

        return runCatching {
            Log.i(TAG, "refreshRollingCopies:start snapshot=${snapshot.db.absolutePath}")
            deleteFileSet(stage)
            val checkpointResult = runWalCheckpoint(db)
            db.openHelper.writableDatabase.execSQL("VACUUM INTO '${stage.db.absolutePath.escapeForSqlLiteral()}'")
            val stagedIntegrity = CopilotDatabaseIntegrityManager.integrityCheck(stage.db)
            check(stagedIntegrity.ok) { "stage_integrity_failed:${stagedIntegrity.detail}" }

            val previousSnapshotIntegrity = CopilotDatabaseIntegrityManager.integrityCheck(snapshot.db)
            val backupTransport = if (previousSnapshotIntegrity.ok) {
                replaceFileSet(snapshot, backup)
            } else {
                "skipped"
            }
            val snapshotTransport = replaceFileSet(stage, snapshot)
            val snapshotIntegrity = CopilotDatabaseIntegrityManager.integrityCheck(snapshot.db)
            check(snapshotIntegrity.ok) { "snapshot_integrity_failed:${snapshotIntegrity.detail}" }
            deleteFileSet(stage)

            val result = SnapshotRefreshResult(
                success = true,
                snapshotPath = snapshot.db.absolutePath,
                backupPath = backup.db.absolutePath,
                snapshotIntegrity = snapshotIntegrity.detail,
                backupRotated = previousSnapshotIntegrity.ok,
                snapshotTransport = snapshotTransport,
                backupTransport = backupTransport,
                error = null
            )
            auditLogger.info(
                "db_snapshot_refresh_completed",
                mapOf(
                    "snapshotPath" to result.snapshotPath,
                    "snapshotSizeBytes" to snapshot.db.length(),
                    "snapshotIntegrity" to result.snapshotIntegrity,
                    "checkpointResult" to checkpointResult,
                    "backupRotated" to result.backupRotated,
                    "snapshotTransport" to result.snapshotTransport,
                    "backupTransport" to result.backupTransport,
                    "backupPath" to result.backupPath
                )
            )
            Log.i(
                TAG,
                "refreshRollingCopies:completed snapshot=${result.snapshotPath} integrity=${result.snapshotIntegrity} checkpoint=$checkpointResult backupRotated=${result.backupRotated} snapshotTransport=${result.snapshotTransport} backupTransport=${result.backupTransport}"
            )
            result
        }.onFailure { error ->
            deleteFileSet(stage)
            auditLogger.warn(
                "db_snapshot_refresh_failed",
                mapOf(
                    "snapshotPath" to snapshot.db.absolutePath,
                    "backupPath" to backup.db.absolutePath,
                    "error" to (error.message ?: "unknown")
                )
            )
            Log.e(TAG, "refreshRollingCopies:failed ${error.message}", error)
        }.getOrElse { error ->
            SnapshotRefreshResult(
                success = false,
                snapshotPath = snapshot.db.absolutePath,
                backupPath = backup.db.absolutePath,
                snapshotIntegrity = null,
                backupRotated = false,
                snapshotTransport = null,
                backupTransport = null,
                error = error.message ?: "unknown"
            )
        }
    }

    private data class FileSet(
        val db: File,
        val wal: File,
        val shm: File
    )

    private fun fileSet(parent: File, dbName: String): FileSet {
        return FileSet(
            db = File(parent, dbName),
            wal = File(parent, "$dbName-wal"),
            shm = File(parent, "$dbName-shm")
        )
    }

    private fun replaceFileSet(source: FileSet, target: FileSet): String {
        if (moveFileSet(source, target)) return "move"
        copyFileSet(source, target)
        deleteFileSet(source)
        return "copy"
    }

    private fun moveFileSet(source: FileSet, target: FileSet): Boolean {
        if (!source.db.exists()) return false
        target.db.parentFile?.mkdirs()
        deleteFileSet(target)
        if (!source.db.renameTo(target.db)) return false
        moveOptional(source.wal, target.wal)
        moveOptional(source.shm, target.shm)
        return true
    }

    private fun copyFileSet(source: FileSet, target: FileSet) {
        source.db.inputStream().use { input ->
            target.db.outputStream().use { output -> input.copyTo(output) }
        }
        copyOptional(source.wal, target.wal)
        copyOptional(source.shm, target.shm)
    }

    private fun moveOptional(source: File, target: File) {
        if (!source.exists()) {
            if (target.exists()) target.delete()
            return
        }
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (!source.renameTo(target)) {
            copyOptional(source, target)
            source.delete()
        }
    }

    private fun copyOptional(source: File, target: File) {
        if (!source.exists()) {
            if (target.exists()) target.delete()
            return
        }
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun deleteFileSet(fileSet: FileSet) {
        if (fileSet.db.exists()) fileSet.db.delete()
        if (fileSet.wal.exists()) fileSet.wal.delete()
        if (fileSet.shm.exists()) fileSet.shm.delete()
    }

    private fun runWalCheckpoint(db: CopilotDatabase): String {
        return db.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(FULL)")
            .use { cursor ->
                if (!cursor.moveToFirst()) return@use "no_rows"
                val busy = cursor.getLong(0)
                val logFrames = cursor.getLong(1)
                val checkpointedFrames = cursor.getLong(2)
                "busy=$busy,log=$logFrames,checkpointed=$checkpointedFrames"
            }
    }

    private fun String.escapeForSqlLiteral(): String = replace("'", "''")
}
