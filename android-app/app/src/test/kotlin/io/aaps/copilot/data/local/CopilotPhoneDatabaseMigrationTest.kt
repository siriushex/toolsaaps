package io.aaps.copilot.data.local

import androidx.room.migration.Migration
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assume
import org.junit.Test

class CopilotPhoneDatabaseMigrationTest {

    @Test
    fun migrationFromSupportedVersionTo26_preservesDisposablePhoneCopy() {
        val copyPath = System.getenv("COPILOT_PHONE_DB_COPY")?.takeIf(String::isNotBlank)
        Assume.assumeTrue(
            "COPILOT_PHONE_DB_COPY must point to a disposable SQLite database copy",
            copyPath != null
        )
        val path = Paths.get(requireNotNull(copyPath)).toAbsolutePath().normalize()
        require(Files.isRegularFile(path) && Files.isWritable(path) && !Files.isSymbolicLink(path)) {
            "COPILOT_PHONE_DB_COPY must be a writable, non-symlink disposable database copy"
        }

        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            val sourceVersion = singleLong(connection, "PRAGMA user_version").toInt()
            val migrationTail = requiredMigrationTail(sourceVersion)
            val protectedCounts = existingTableCounts(connection)

            connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            migrationTail.forEach { migrateAsRoom(connection, it) }

            assertThat(existingTableCounts(connection)).containsAtLeastEntriesIn(protectedCounts)
            assertThat(allStrings(connection, "PRAGMA integrity_check")).containsExactly("ok")
            assertThat(queryHasRows(connection, "PRAGMA foreign_key_check")).isFalse()
            assertThat(singleLong(connection, "PRAGMA user_version")).isEqualTo(CURRENT_VERSION.toLong())
            assertThat(schemaObjectNames(connection, "table"))
                .containsAtLeastElementsIn(KEY_V26_TABLES)
            assertThat(schemaObjectNames(connection, "index"))
                .containsAtLeastElementsIn(KEY_V26_INDEXES)
        }
    }

    @Test
    fun officialMigrationTailSupportsEverySourceVersionFrom21Through26() {
        for (sourceVersion in MIN_SUPPORTED_VERSION..CURRENT_VERSION) {
            val expectedStarts = (sourceVersion until CURRENT_VERSION).toList()
            val tail = requiredMigrationTail(sourceVersion)

            assertThat(tail.map(Migration::startVersion))
                .containsExactlyElementsIn(expectedStarts)
                .inOrder()
            assertThat(tail.map(Migration::endVersion))
                .containsExactlyElementsIn(expectedStarts.map { it + 1 })
                .inOrder()
        }
    }

    private fun requiredMigrationTail(sourceVersion: Int): List<Migration> {
        require(sourceVersion in MIN_SUPPORTED_VERSION..CURRENT_VERSION) {
            "Unsupported disposable copy schema version: $sourceVersion"
        }
        val migrationsByStart = CopilotMigrations.ALL
            .filter { it.startVersion in MIN_SUPPORTED_VERSION until CURRENT_VERSION }
            .groupBy { it.startVersion }
        var version = sourceVersion
        return buildList {
            while (version < CURRENT_VERSION) {
                val candidates = checkNotNull(migrationsByStart[version]) {
                    "Missing official migration starting at v$version"
                }
                check(candidates.size == 1) { "Ambiguous official migration starting at v$version" }
                val migration = candidates.single()
                check(migration.endVersion == version + 1) {
                    "Migration v$version must advance exactly one schema version"
                }
                add(migration)
                version = migration.endVersion
            }
        }
    }

    private fun existingTableCounts(connection: Connection): Map<String, Long> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT name FROM sqlite_master " +
                    "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                    "AND name NOT IN ('android_metadata', 'room_master_table') ORDER BY name"
            ).use { rows ->
                buildMap {
                    while (rows.next()) {
                        val table = rows.getString("name")
                        val quotedTable = table.replace("\"", "\"\"")
                        put(table, singleLong(connection, "SELECT COUNT(*) FROM \"$quotedTable\""))
                    }
                }
            }
        }

    private fun schemaObjectNames(connection: Connection, type: String): Set<String> =
        connection.prepareStatement(
            "SELECT name FROM sqlite_master WHERE type = ? ORDER BY name"
        ).use { statement ->
            statement.setString(1, type)
            statement.executeQuery().use { rows ->
                buildSet {
                    while (rows.next()) add(rows.getString("name"))
                }
            }
        }

    private fun allStrings(connection: Connection, sql: String): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString(1))
                }
            }
        }

    private fun queryHasRows(connection: Connection, sql: String): Boolean =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> rows.next() }
        }

    private fun singleLong(connection: Connection, sql: String): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }

    private companion object {
        const val MIN_SUPPORTED_VERSION = 21
        const val CURRENT_VERSION = 26

        val KEY_V26_TABLES = setOf(
            "sensitivity_runtime_snapshots",
            "alert_events",
            "alert_delivery_receipts",
            "alert_ai_analyses",
            "context_event_sync"
        )
        val KEY_V26_INDEXES = setOf(
            "index_sensitivity_runtime_snapshots_settingsRevision_generatedAt",
            "index_alert_events_status_updatedAt",
            "index_alert_delivery_receipts_episodeId_kind",
            "index_alert_ai_analyses_episodeId",
            "index_context_event_sync_eventId_revision_operation"
        )
    }
}
