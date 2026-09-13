package io.aaps.copilot.service

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.util.Locale

object CopilotDatabaseIntegrityManager {

    private const val TAG = "CopilotDbIntegrity"
    private const val MAIN_DB_NAME = "copilot.db"
    private const val BACKUP_DB_NAME = "copilot-backup.db"
    private const val SNAPSHOT_DB_NAME = "copilot_snapshot.db"
    private const val STAGE_DB_NAME = "copilot-restore-stage.db"

    data class StartupRecoveryResult(
        val startupProbe: String,
        val quickCheck: String,
        val restored: Boolean,
        val restoredFrom: String?,
        val backupQuickCheck: String?,
        val snapshotQuickCheck: String?,
        val error: String?
    )

    data class RestoreSourceStatus(
        val sourceKind: String,
        val quickCheck: String,
        val path: String
    )

    internal enum class RestoreSourceKind {
        BACKUP,
        SNAPSHOT
    }

    internal data class QuickCheckResult(
        val ok: Boolean,
        val detail: String
    )

    internal data class RestoreCandidate(
        val sourceKind: RestoreSourceKind,
        val files: FileSet,
        val quickCheck: QuickCheckResult
    )

    internal data class FileSet(
        val db: File,
        val wal: File,
        val shm: File
    )

    fun restoreIfNeeded(context: Context): StartupRecoveryResult? {
        val main = mainFileSet(context)
        if (!main.db.exists()) return null

        val startupProbe = startupProbe(main.db)
        if (startupProbe.ok) return null

        val mainQuickCheck = quickCheck(main.db)
        if (mainQuickCheck.ok) return null

        val candidates = buildRestoreCandidates(context)
        val backupQuickCheck = candidates.firstOrNull { it.sourceKind == RestoreSourceKind.BACKUP }?.quickCheck?.detail
        val snapshotQuickCheck = candidates.firstOrNull { it.sourceKind == RestoreSourceKind.SNAPSHOT }?.quickCheck?.detail
        val selected = choosePreferredRestoreSource(candidates)
            ?: return StartupRecoveryResult(
                startupProbe = startupProbe.detail,
                quickCheck = mainQuickCheck.detail,
                restored = false,
                restoredFrom = null,
                backupQuickCheck = backupQuickCheck,
                snapshotQuickCheck = snapshotQuickCheck,
                error = "no_healthy_restore_source"
            ).also {
                Log.e(TAG, "Main DB quick_check failed and no healthy restore source found: ${mainQuickCheck.detail}")
            }

        return runCatching {
            val stage = stageFileSet(context)
            deleteFileSet(stage)
            copyFileSet(selected.files, stage)
            val stagedQuickCheck = quickCheck(stage.db)
            check(stagedQuickCheck.ok) { "staged_restore_invalid:${stagedQuickCheck.detail}" }

            deleteFileSet(main)
            copyFileSet(stage, main)
            deleteFileSet(stage)

            val restoredQuickCheck = quickCheck(main.db)
            check(restoredQuickCheck.ok) { "restored_main_invalid:${restoredQuickCheck.detail}" }

            StartupRecoveryResult(
                startupProbe = startupProbe.detail,
                quickCheck = mainQuickCheck.detail,
                restored = true,
                restoredFrom = selected.sourceKind.name.lowercase(Locale.US),
                backupQuickCheck = backupQuickCheck,
                snapshotQuickCheck = snapshotQuickCheck,
                error = null
            )
        }.getOrElse { error ->
            Log.e(TAG, "DB restore failed", error)
            StartupRecoveryResult(
                startupProbe = startupProbe.detail,
                quickCheck = mainQuickCheck.detail,
                restored = false,
                restoredFrom = selected.sourceKind.name.lowercase(Locale.US),
                backupQuickCheck = backupQuickCheck,
                snapshotQuickCheck = snapshotQuickCheck,
                error = error.message ?: "restore_failed"
            )
        }
    }

    fun bestRestoreSource(context: Context): RestoreSourceStatus? {
        val selected = choosePreferredRestoreSource(buildRestoreCandidates(context)) ?: return null
        return RestoreSourceStatus(
            sourceKind = selected.sourceKind.name,
            quickCheck = selected.quickCheck.detail,
            path = selected.files.db.absolutePath
        )
    }

    internal fun choosePreferredRestoreSource(
        candidates: List<RestoreCandidate>
    ): RestoreCandidate? {
        return candidates
            .filter { it.quickCheck.ok }
            .sortedBy {
                when (it.sourceKind) {
                    RestoreSourceKind.BACKUP -> 0
                    RestoreSourceKind.SNAPSHOT -> 1
                }
            }
            .firstOrNull()
    }

    private fun buildRestoreCandidates(context: Context): List<RestoreCandidate> {
        val fileDir = context.filesDir
        val dbDir = context.getDatabasePath(MAIN_DB_NAME).parentFile ?: return emptyList()
        val rawCandidates = listOf(
            RestoreSourceKind.BACKUP to fileSet(fileDir, BACKUP_DB_NAME),
            RestoreSourceKind.SNAPSHOT to fileSet(fileDir, SNAPSHOT_DB_NAME),
            RestoreSourceKind.BACKUP to fileSet(dbDir, BACKUP_DB_NAME),
            RestoreSourceKind.SNAPSHOT to fileSet(dbDir, SNAPSHOT_DB_NAME)
        )

        return rawCandidates
            .distinctBy { (_, files) -> files.db.absolutePath }
            .mapNotNull { (sourceKind, files) ->
                if (!files.db.exists()) return@mapNotNull null
                RestoreCandidate(
                    sourceKind = sourceKind,
                    files = files,
                    quickCheck = integrityCheck(files.db)
                )
            }
    }

    private fun mainFileSet(context: Context): FileSet {
        val mainDb = context.getDatabasePath(MAIN_DB_NAME)
        val dbDir = mainDb.parentFile ?: context.filesDir
        return fileSet(dbDir, MAIN_DB_NAME)
    }

    private fun stageFileSet(context: Context): FileSet = fileSet(context.cacheDir, STAGE_DB_NAME)

    private fun fileSet(parent: File, dbName: String): FileSet {
        return FileSet(
            db = File(parent, dbName),
            wal = File(parent, "$dbName-wal"),
            shm = File(parent, "$dbName-shm")
        )
    }

    private fun quickCheck(dbFile: File): QuickCheckResult {
        return pragmaCheck(dbFile = dbFile, pragma = "quick_check")
    }

    private fun startupProbe(dbFile: File): QuickCheckResult {
        if (!dbFile.exists()) return QuickCheckResult(ok = false, detail = "missing")
        return runCatching {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                database.rawQuery("PRAGMA schema_version", null).use { cursor ->
                    val result = if (cursor.moveToFirst()) cursor.getLong(0) else -1L
                    QuickCheckResult(ok = result >= 0L, detail = "schema_version=$result")
                }
            }
        }.getOrElse { error ->
            QuickCheckResult(ok = false, detail = error.message ?: "open_failed")
        }
    }

    internal fun integrityCheck(dbFile: File): QuickCheckResult {
        return pragmaCheck(dbFile = dbFile, pragma = "integrity_check")
    }

    private fun pragmaCheck(dbFile: File, pragma: String): QuickCheckResult {
        if (!dbFile.exists()) return QuickCheckResult(ok = false, detail = "missing")
        return runCatching {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                database.rawQuery("PRAGMA $pragma", null).use { cursor ->
                    val result = if (cursor.moveToFirst()) cursor.getString(0) else "unknown"
                    QuickCheckResult(ok = result.equals("ok", ignoreCase = true), detail = result)
                }
            }
        }.getOrElse { error ->
            QuickCheckResult(ok = false, detail = error.message ?: "open_failed")
        }
    }

    private fun copyFileSet(source: FileSet, target: FileSet) {
        source.db.inputStream().use { input ->
            target.db.outputStream().use { output -> input.copyTo(output) }
        }
        copyOptionalFile(source.wal, target.wal)
        copyOptionalFile(source.shm, target.shm)
    }

    private fun copyOptionalFile(source: File, target: File) {
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
}
