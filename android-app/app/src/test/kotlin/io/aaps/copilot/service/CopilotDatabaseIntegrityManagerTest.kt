package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class CopilotDatabaseIntegrityManagerTest {

    @Test
    fun prefersHealthyBackupOverHealthySnapshot() {
        val selected = CopilotDatabaseIntegrityManager.choosePreferredRestoreSource(
            listOf(
                candidate(CopilotDatabaseIntegrityManager.RestoreSourceKind.SNAPSHOT, ok = true),
                candidate(CopilotDatabaseIntegrityManager.RestoreSourceKind.BACKUP, ok = true)
            )
        )

        assertThat(selected?.sourceKind).isEqualTo(CopilotDatabaseIntegrityManager.RestoreSourceKind.BACKUP)
    }

    @Test
    fun fallsBackToSnapshotWhenBackupIsBroken() {
        val selected = CopilotDatabaseIntegrityManager.choosePreferredRestoreSource(
            listOf(
                candidate(CopilotDatabaseIntegrityManager.RestoreSourceKind.BACKUP, ok = false),
                candidate(CopilotDatabaseIntegrityManager.RestoreSourceKind.SNAPSHOT, ok = true)
            )
        )

        assertThat(selected?.sourceKind).isEqualTo(CopilotDatabaseIntegrityManager.RestoreSourceKind.SNAPSHOT)
    }

    @Test
    fun returnsNullWhenNoHealthyRestoreSourceExists() {
        val selected = CopilotDatabaseIntegrityManager.choosePreferredRestoreSource(
            listOf(
                candidate(CopilotDatabaseIntegrityManager.RestoreSourceKind.BACKUP, ok = false),
                candidate(CopilotDatabaseIntegrityManager.RestoreSourceKind.SNAPSHOT, ok = false)
            )
        )

        assertThat(selected).isNull()
    }

    private fun candidate(
        sourceKind: CopilotDatabaseIntegrityManager.RestoreSourceKind,
        ok: Boolean
    ): CopilotDatabaseIntegrityManager.RestoreCandidate {
        val base = File("/tmp/${sourceKind.name.lowercase()}")
        return CopilotDatabaseIntegrityManager.RestoreCandidate(
            sourceKind = sourceKind,
            files = CopilotDatabaseIntegrityManager.FileSet(
                db = File(base.parentFile ?: File("/tmp"), "${base.name}.db"),
                wal = File(base.parentFile ?: File("/tmp"), "${base.name}.db-wal"),
                shm = File(base.parentFile ?: File("/tmp"), "${base.name}.db-shm")
            ),
            quickCheck = CopilotDatabaseIntegrityManager.QuickCheckResult(
                ok = ok,
                detail = if (ok) "ok" else "malformed"
            )
        )
    }
}
