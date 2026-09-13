package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class CopilotDatabaseSnapshotManagerTest {

    @Test
    fun latestRollingCopyTimestampUsesNewestSnapshotOrBackup() {
        val filesDir = createTempDir(prefix = "copilot-snapshot-test")
        val snapshot = File(filesDir, CopilotDatabaseSnapshotManager.SNAPSHOT_DB_NAME)
        val backup = File(filesDir, CopilotDatabaseSnapshotManager.BACKUP_DB_NAME)
        snapshot.writeText("snapshot")
        backup.writeText("backup")
        snapshot.setLastModified(1_000L)
        backup.setLastModified(2_000L)

        try {
            assertThat(CopilotDatabaseSnapshotManager.latestRollingCopyTimestamp(filesDir)).isEqualTo(2_000L)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun refreshDueReturnsFalseWhenRollingCopyIsFresh() {
        val filesDir = createTempDir(prefix = "copilot-snapshot-test")
        val snapshot = File(filesDir, CopilotDatabaseSnapshotManager.SNAPSHOT_DB_NAME)
        snapshot.writeText("snapshot")
        snapshot.setLastModified(10_000L)

        try {
            assertThat(CopilotDatabaseSnapshotManager.isRefreshDue(filesDir, nowTs = 12_000L, intervalMs = 5_000L)).isFalse()
            assertThat(CopilotDatabaseSnapshotManager.isRefreshDue(filesDir, nowTs = 20_500L, intervalMs = 5_000L)).isTrue()
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun automaticSnapshotRefreshIsSkippedForOversizedDatabase() {
        assertThat(
            CopilotDatabaseSnapshotManager.isAutomaticSnapshotAllowed(
                sourceDbSizeBytes = CopilotDatabaseSnapshotManager.MAX_AUTOMATIC_SNAPSHOT_SOURCE_BYTES
            )
        ).isTrue()
        assertThat(
            CopilotDatabaseSnapshotManager.isAutomaticSnapshotAllowed(
                sourceDbSizeBytes = CopilotDatabaseSnapshotManager.MAX_AUTOMATIC_SNAPSHOT_SOURCE_BYTES + 1L
            )
        ).isFalse()
    }
}
