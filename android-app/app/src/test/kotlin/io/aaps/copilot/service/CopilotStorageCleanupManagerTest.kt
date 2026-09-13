package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class CopilotStorageCleanupManagerTest {

    @Test
    fun cleanupNonEssentialFiles_keepsRollingRestorePairAndDeletesArtifacts() {
        val root = createTempDir(prefix = "copilot-cleanup-test")
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }

        File(filesDir, "copilot_snapshot.db").writeText("snapshot")
        File(filesDir, "copilot-backup.db").writeText("backup")
        File(filesDir, "copilot_snapshot.db-wal").writeText("wal")
        File(filesDir, "copilot_snapshot.db-shm").writeText("shm")
        File(filesDir, "copilot_circadian_live.tar").writeText("tar")
        File(filesDir, "copilot-extra.db").writeText("orphan")
        File(filesDir, "profileInstalled").writeText("keep")
        File(filesDir, "datastore").mkdirs()
        File(filesDir, "debug_export").apply {
            mkdirs()
            File(this, "payload.json").writeText("debug")
        }
        File(cacheDir, "copilot_snapshot_stage.db").writeText("stage")
        File(cacheDir, "copilot-restore-stage.db-wal").writeText("stage-wal")

        val result = CopilotStorageCleanupManager.cleanupNonEssentialFiles(
            filesDir = filesDir,
            cacheDir = cacheDir
        )

        assertThat(File(filesDir, "copilot_snapshot.db").exists()).isTrue()
        assertThat(File(filesDir, "copilot-backup.db").exists()).isTrue()
        assertThat(File(filesDir, "profileInstalled").exists()).isTrue()
        assertThat(File(filesDir, "datastore").exists()).isTrue()

        assertThat(File(filesDir, "copilot_circadian_live.tar").exists()).isFalse()
        assertThat(File(filesDir, "copilot-extra.db").exists()).isFalse()
        assertThat(File(filesDir, "debug_export").exists()).isFalse()
        assertThat(File(cacheDir, "copilot_snapshot_stage.db").exists()).isFalse()
        assertThat(File(cacheDir, "copilot-restore-stage.db-wal").exists()).isFalse()

        assertThat(result.removedTarFiles).isEqualTo(1)
        assertThat(result.removedDebugExportFiles).isAtLeast(1)
        assertThat(result.removedOrphanDbFiles).isAtLeast(2)

        root.deleteRecursively()
    }

    @Test
    fun cleanupNonEssentialFiles_removesRollingRestorePairWhenPrimaryDbIsTooLargeForSnapshots() {
        val root = createTempDir(prefix = "copilot-cleanup-large-db-test")
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }

        File(filesDir, "copilot_snapshot.db").writeText("snapshot")
        File(filesDir, "copilot-backup.db").writeText("backup")
        File(filesDir, "copilot_snapshot.db-wal").writeText("wal")
        File(filesDir, "copilot-backup.db-shm").writeText("shm")

        val result = CopilotStorageCleanupManager.cleanupNonEssentialFiles(
            filesDir = filesDir,
            cacheDir = cacheDir,
            primaryDbSizeBytes = CopilotDatabaseSnapshotManager.MAX_AUTOMATIC_SNAPSHOT_SOURCE_BYTES + 1L
        )

        assertThat(File(filesDir, "copilot_snapshot.db").exists()).isFalse()
        assertThat(File(filesDir, "copilot-backup.db").exists()).isFalse()
        assertThat(File(filesDir, "copilot_snapshot.db-wal").exists()).isFalse()
        assertThat(File(filesDir, "copilot-backup.db-shm").exists()).isFalse()
        assertThat(result.removedRollingCopyFiles).isEqualTo(4)
        assertThat(result.deletedFiles).isEqualTo(4)

        root.deleteRecursively()
    }
}
