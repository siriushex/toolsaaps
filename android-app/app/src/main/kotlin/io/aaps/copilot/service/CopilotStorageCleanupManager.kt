package io.aaps.copilot.service

import android.content.Context
import io.aaps.copilot.data.repository.AuditLogger
import java.io.File
import java.util.Locale

object CopilotStorageCleanupManager {

    private const val SNAPSHOT_DB_NAME = "copilot_snapshot.db"
    private const val BACKUP_DB_NAME = "copilot-backup.db"

    data class CleanupResult(
        val deletedFiles: Int,
        val freedBytes: Long,
        val removedTarFiles: Int,
        val removedDebugExportFiles: Int,
        val removedOrphanDbFiles: Int,
        val removedRollingCopyFiles: Int = 0
    )

    suspend fun cleanupNonEssentialFiles(
        context: Context,
        auditLogger: AuditLogger
    ): CleanupResult {
        val result = cleanupNonEssentialFiles(
            filesDir = context.filesDir,
            cacheDir = context.cacheDir,
            primaryDbSizeBytes = context.getDatabasePath("copilot.db")
                .takeIf { it.exists() }
                ?.length()
                ?: 0L
        )
        auditLogger.info(
            "db_file_cleanup_completed",
            mapOf(
                "deletedFiles" to result.deletedFiles,
                "freedBytes" to result.freedBytes,
                "removedCategories" to listOf("tar", "debug_export", "orphan_db", "rolling_copy"),
                "removedTarFiles" to result.removedTarFiles,
                "removedDebugExportFiles" to result.removedDebugExportFiles,
                "removedOrphanDbFiles" to result.removedOrphanDbFiles,
                "removedRollingCopyFiles" to result.removedRollingCopyFiles
            )
        )
        return result
    }

    internal fun cleanupNonEssentialFiles(
        filesDir: File,
        cacheDir: File,
        primaryDbSizeBytes: Long? = null
    ): CleanupResult {
        val accumulator = CleanupAccumulator()
        val removeRollingCopies = (primaryDbSizeBytes ?: 0L) >
            CopilotDatabaseSnapshotManager.MAX_AUTOMATIC_SNAPSHOT_SOURCE_BYTES

        filesDir.listFiles().orEmpty().forEach { file ->
            when {
                removeRollingCopies && isRollingCopy(file) -> {
                    accumulator.deleteFileOrDirectory(file, Category.ROLLING_COPY)
                }

                file.name.startsWith("copilot_") && file.name.endsWith(".tar") -> {
                    accumulator.deleteFileOrDirectory(file, Category.TAR)
                }

                file.name == "debug_export" && file.isDirectory -> {
                    accumulator.deleteFileOrDirectory(file, Category.DEBUG_EXPORT)
                }

                isOrphanCopilotDb(file) -> {
                    accumulator.deleteFileOrDirectory(file, Category.ORPHAN_DB)
                }
            }
        }

        cacheDir.listFiles().orEmpty()
            .filter { file ->
                val name = file.name.lowercase(Locale.US)
                name.startsWith("copilot_snapshot_stage.db") || name.startsWith("copilot-restore-stage.db")
            }
            .forEach { file ->
                accumulator.deleteFileOrDirectory(file, Category.ORPHAN_DB)
            }

        return accumulator.toResult()
    }

    private fun isOrphanCopilotDb(file: File): Boolean {
        val name = file.name.lowercase(Locale.US)
        if (!name.startsWith("copilot")) return false
        val protectedNames = setOf(
            SNAPSHOT_DB_NAME,
            "$SNAPSHOT_DB_NAME-wal",
            "$SNAPSHOT_DB_NAME-shm",
            BACKUP_DB_NAME,
            "$BACKUP_DB_NAME-wal",
            "$BACKUP_DB_NAME-shm"
        )
        if (name in protectedNames) return false
        return name.endsWith(".db") || name.endsWith(".db-wal") || name.endsWith(".db-shm")
    }

    private fun isRollingCopy(file: File): Boolean {
        val name = file.name.lowercase(Locale.US)
        return name == SNAPSHOT_DB_NAME ||
            name == "$SNAPSHOT_DB_NAME-wal" ||
            name == "$SNAPSHOT_DB_NAME-shm" ||
            name == BACKUP_DB_NAME ||
            name == "$BACKUP_DB_NAME-wal" ||
            name == "$BACKUP_DB_NAME-shm"
    }

    private enum class Category {
        TAR,
        DEBUG_EXPORT,
        ORPHAN_DB,
        ROLLING_COPY
    }

    private class CleanupAccumulator {
        private var deletedFiles = 0
        private var freedBytes = 0L
        private var removedTarFiles = 0
        private var removedDebugExportFiles = 0
        private var removedOrphanDbFiles = 0
        private var removedRollingCopyFiles = 0

        fun deleteFileOrDirectory(file: File, category: Category) {
            if (!file.exists()) return
            if (file.isDirectory) {
                file.listFiles().orEmpty().forEach { child -> deleteFileOrDirectory(child, category) }
            }
            val size = file.length().coerceAtLeast(0L)
            if (file.delete()) {
                deletedFiles += 1
                freedBytes += size
                when (category) {
                    Category.TAR -> removedTarFiles += 1
                    Category.DEBUG_EXPORT -> removedDebugExportFiles += 1
                    Category.ORPHAN_DB -> removedOrphanDbFiles += 1
                    Category.ROLLING_COPY -> removedRollingCopyFiles += 1
                }
            }
        }

        fun toResult(): CleanupResult = CleanupResult(
            deletedFiles = deletedFiles,
            freedBytes = freedBytes,
            removedTarFiles = removedTarFiles,
            removedDebugExportFiles = removedDebugExportFiles,
            removedOrphanDbFiles = removedOrphanDbFiles,
            removedRollingCopyFiles = removedRollingCopyFiles
        )
    }
}
