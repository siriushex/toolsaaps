package io.aaps.copilot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.ClinicalReportEntity
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.Assert.assertThrows
import org.junit.Test

class CopilotMigrationsTest {

    @Test
    fun migration25To26_addsIntegratedRuntimeSchemaWithoutChangingClinicalRows() {
        assertThat(CopilotMigrations.MIGRATION_25_26.startVersion).isEqualTo(25)
        assertThat(CopilotMigrations.MIGRATION_25_26.endVersion).isEqualTo(26)

        val path = Files.createTempFile("copilot-room-v25-v26-", ".sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                createSchema25Fixture(connection)
                val therapyCount = singleLong(connection, "SELECT COUNT(*) FROM therapy_events")
                val contextCount = singleLong(connection, "SELECT COUNT(*) FROM physio_context_tags")

                connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
                migrateAsRoom(connection, CopilotMigrations.MIGRATION_25_26)

                assertThat(singleLong(connection, "SELECT COUNT(*) FROM therapy_events"))
                    .isEqualTo(therapyCount)
                assertThat(singleLong(connection, "SELECT COUNT(*) FROM physio_context_tags"))
                    .isEqualTo(contextCount)
                assertSingleRow(
                    connection,
                    "SELECT subtype, title, attributesJson, revision, updatedAt, status " +
                        "FROM physio_context_tags WHERE id = 'context-keep'",
                    listOf("", "", "{}", 1L, 0L, "ACTIVE")
                )
                assertV26IntegrityAndIndexes(connection)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun migration21To26_preservesRowsAndProducesSameIntegratedRuntimeSchema() {
        val path = Files.createTempFile("copilot-room-v21-v26-", ".sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                createSchema25Fixture(connection, userVersion = 21, includeV22ToV25Tables = false)
                val therapyCount = singleLong(connection, "SELECT COUNT(*) FROM therapy_events")
                val contextCount = singleLong(connection, "SELECT COUNT(*) FROM physio_context_tags")

                connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
                listOf(
                    CopilotMigrations.MIGRATION_21_22,
                    CopilotMigrations.MIGRATION_22_23,
                    CopilotMigrations.MIGRATION_23_24,
                    CopilotMigrations.MIGRATION_24_25,
                    CopilotMigrations.MIGRATION_25_26
                ).forEach { migrateAsRoom(connection, it) }

                assertThat(singleLong(connection, "SELECT COUNT(*) FROM therapy_events"))
                    .isEqualTo(therapyCount)
                assertThat(singleLong(connection, "SELECT COUNT(*) FROM physio_context_tags"))
                    .isEqualTo(contextCount)
                assertThat(singleLong(connection, "SELECT COUNT(*) FROM legacy_marker"))
                    .isEqualTo(1L)
                assertV26IntegrityAndIndexes(connection)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun migration25To26_isAdditiveAndUpdatingAlertParentKeepsChildren() {
        val statements = CopilotMigrations.MIGRATION_25_26_STATEMENTS.joinToString("\n")
        assertThat(statements.uppercase()).doesNotContain("DROP TABLE")
        assertThat(statements.uppercase()).doesNotContain("REPLACE INTO")
        assertThat(statements.uppercase()).doesNotContain("VACUUM")

        val path = Files.createTempFile("copilot-room-alert-children-", ".sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                createSchema25Fixture(connection)
                migrateAsRoom(connection, CopilotMigrations.MIGRATION_25_26)
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO alert_events VALUES " +
                            "('episode-1','LOW','ACTIVE','OPEN','MEDIUM',1,1,NULL,'{}',NULL,NULL,NULL,NULL,1)"
                    )
                    statement.execute(
                        "INSERT INTO alert_delivery_receipts VALUES " +
                            "('receipt-1','episode-1','ORDINARY',1,'DELIVERED',NULL,1,NULL)"
                    )
                    statement.execute(
                        "INSERT INTO alert_ai_analyses VALUES " +
                            "('analysis-1','episode-1','OPENAI','test','hash','DONE','{}',1,2,NULL)"
                    )
                    statement.execute(
                        "UPDATE alert_events SET status = 'RESOLVED', resolvedAt = 3, revision = 2 " +
                            "WHERE episodeId = 'episode-1'"
                    )
                }
                assertThat(singleLong(connection, "SELECT COUNT(*) FROM alert_delivery_receipts"))
                    .isEqualTo(1L)
                assertThat(singleLong(connection, "SELECT COUNT(*) FROM alert_ai_analyses"))
                    .isEqualTo(1L)
                assertThat(singleString(connection, "SELECT status FROM alert_events WHERE episodeId = 'episode-1'"))
                    .isEqualTo("RESOLVED")
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun migration24To25_addsOptionalPendingMealEnergyWithoutChangingExistingIntent() {
        assertThat(CopilotMigrations.MIGRATION_24_25.startVersion).isEqualTo(24)
        assertThat(CopilotMigrations.MIGRATION_24_25.endVersion).isEqualTo(25)

        val statements = CopilotMigrations.MIGRATION_24_25_STATEMENTS.joinToString("\n")
        assertThat(statements).contains("ADD COLUMN `manualMealEnergyKcal` REAL")
        assertThat(statements).doesNotContain("DROP TABLE")
        assertThat(CopilotMigrations.ALL.toList()).contains(CopilotMigrations.MIGRATION_24_25)

        val path = Files.createTempFile("copilot-room-v24-v25-", ".sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA user_version = 24")
                    statement.execute(
                        "CREATE TABLE pending_meal_profile_intents (" +
                            "idempotencyKey TEXT NOT NULL PRIMARY KEY, copilotNote TEXT NOT NULL, " +
                            "profile TEXT NOT NULL, durationMinutes INTEGER NOT NULL, " +
                            "expectedCarbsGrams REAL NOT NULL, submittedAtMs INTEGER NOT NULL, " +
                            "expiresAtMs INTEGER NOT NULL)"
                    )
                    statement.execute(
                        "INSERT INTO pending_meal_profile_intents VALUES " +
                            "('intent-1', 'copilot:intent-1', 'FAST', 45, 20.0, 1, 2)"
                    )

                    migrateAsRoom(connection, CopilotMigrations.MIGRATION_24_25)

                    statement.executeQuery(
                        "SELECT copilotNote, manualMealEnergyKcal FROM pending_meal_profile_intents " +
                            "WHERE idempotencyKey = 'intent-1'"
                    ).use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getString(1)).isEqualTo("copilot:intent-1")
                        assertThat(rows.getObject(2)).isNull()
                        assertThat(rows.next()).isFalse()
                    }
                    statement.execute(
                        "UPDATE pending_meal_profile_intents SET manualMealEnergyKcal = 540.0 " +
                            "WHERE idempotencyKey = 'intent-1'"
                    )
                    assertThat(singleLong(
                        connection,
                        "SELECT CAST(manualMealEnergyKcal AS INTEGER) FROM pending_meal_profile_intents " +
                            "WHERE idempotencyKey = 'intent-1'"
                    )).isEqualTo(540L)
                    assertThat(singleString(connection, "PRAGMA integrity_check")).isEqualTo("ok")
                    assertThat(singleLong(connection, "PRAGMA user_version")).isEqualTo(25L)
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun migration23To24_addsMealEnergyOverridesWithoutChangingTherapyHistory() {
        assertThat(CopilotMigrations.MIGRATION_23_24.startVersion).isEqualTo(23)
        assertThat(CopilotMigrations.MIGRATION_23_24.endVersion).isEqualTo(24)

        val statements = CopilotMigrations.MIGRATION_23_24_STATEMENTS.joinToString("\n")
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `meal_energy_overrides`")
        assertThat(statements).contains("PRIMARY KEY(`canonicalTherapyIdentity`)")
        assertThat(statements).contains("index_meal_energy_overrides_updatedAtMs")
        assertThat(statements).doesNotContain("DROP TABLE")
        assertThat(CopilotMigrations.ALL.toList()).contains(CopilotMigrations.MIGRATION_23_24)

        val path = Files.createTempFile("copilot-room-v23-v24-", ".sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA user_version = 23")
                    statement.execute(
                        "CREATE TABLE therapy_events (id TEXT NOT NULL PRIMARY KEY, " +
                            "timestamp INTEGER NOT NULL, type TEXT NOT NULL, payloadJson TEXT NOT NULL)"
                    )
                    statement.execute(
                        "INSERT INTO therapy_events VALUES " +
                            "('meal-1', 1, 'carbs', '{\"aapsCarbId\":11}')"
                    )

                    migrateAsRoom(connection, CopilotMigrations.MIGRATION_23_24)

                    statement.execute(
                        "INSERT INTO meal_energy_overrides " +
                            "(canonicalTherapyIdentity, therapyRevisionHash, caloriesKcal, updatedAtMs) " +
                            "VALUES ('11', 'revision-11', 540.0, 2)"
                    )
                    assertThat(singleString(
                        connection,
                        "SELECT payloadJson FROM therapy_events WHERE id = 'meal-1'"
                    )).isEqualTo("{\"aapsCarbId\":11}")
                    assertThat(singleLong(
                        connection,
                        "SELECT CAST(caloriesKcal AS INTEGER) FROM meal_energy_overrides " +
                            "WHERE canonicalTherapyIdentity = '11'"
                    )).isEqualTo(540L)
                    assertThat(singleString(connection, "PRAGMA integrity_check")).isEqualTo("ok")
                    assertThat(singleLong(connection, "PRAGMA user_version")).isEqualTo(24L)
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun migration22To23_addsOnlyPendingMealProfileIntents() {
        assertThat(CopilotMigrations.MIGRATION_22_23.startVersion).isEqualTo(22)
        assertThat(CopilotMigrations.MIGRATION_22_23.endVersion).isEqualTo(23)

        val statements = CopilotMigrations.MIGRATION_22_23_STATEMENTS.joinToString("\n")
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `pending_meal_profile_intents`")
        assertThat(statements).contains("PRIMARY KEY(`idempotencyKey`)")
        assertThat(statements).contains("index_pending_meal_profile_intents_expiresAtMs")
        assertThat(statements).doesNotContain("DROP TABLE")
        assertThat(CopilotMigrations.ALL.toList()).contains(CopilotMigrations.MIGRATION_22_23)
    }

    @Test
    fun migration22To23_preservesExistingOverridesAndStoresPendingIntent() {
        val path = Files.createTempFile("copilot-room-v22-v23-", ".sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA user_version = 22")
                    statement.execute(
                        "CREATE TABLE meal_profile_overrides (" +
                            "canonicalTherapyIdentity TEXT NOT NULL PRIMARY KEY, " +
                            "therapyRevisionHash TEXT NOT NULL, profile TEXT NOT NULL, " +
                            "durationMinutes INTEGER NOT NULL, source TEXT NOT NULL, " +
                            "revision INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL)"
                    )
                    statement.execute(
                        "INSERT INTO meal_profile_overrides VALUES " +
                            "('meal-1', 'revision-a', 'MIXED', 120, 'COPILOT_UI', 1, 1)"
                    )

                    migrateAsRoom(connection, CopilotMigrations.MIGRATION_22_23)

                    assertThat(singleString(
                        connection,
                        "SELECT profile FROM meal_profile_overrides WHERE canonicalTherapyIdentity = 'meal-1'"
                    )).isEqualTo("MIXED")
                    statement.execute(
                        "INSERT INTO pending_meal_profile_intents " +
                            "(idempotencyKey, copilotNote, profile, durationMinutes, expectedCarbsGrams, " +
                            "submittedAtMs, expiresAtMs) VALUES " +
                            "('idempotency-1', 'copilot:idempotency-1', 'FAST', 45, 20.0, 1, 2)"
                    )
                    assertThat(singleString(
                        connection,
                        "SELECT copilotNote FROM pending_meal_profile_intents WHERE idempotencyKey = 'idempotency-1'"
                    )).isEqualTo("copilot:idempotency-1")
                    assertThat(singleString(connection, "PRAGMA integrity_check")).isEqualTo("ok")
                    assertThat(singleLong(connection, "PRAGMA user_version")).isEqualTo(23L)
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun migration21To22_addsOnlyEnergyProfileTables() {
        assertThat(CopilotMigrations.MIGRATION_21_22.startVersion).isEqualTo(21)
        assertThat(CopilotMigrations.MIGRATION_21_22.endVersion).isEqualTo(22)

        val statements = CopilotMigrations.MIGRATION_21_22_STATEMENTS.joinToString("\n")
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `planned_activity_events`")
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `meal_profile_overrides`")
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `energy_profile_snapshots`")
        assertThat(statements).contains("index_planned_activity_events_enabled")
        assertThat(statements).contains("index_planned_activity_events_updatedAtMs")
        assertThat(statements).contains("index_energy_profile_snapshots_calculatedAtMs")
        assertThat(statements).doesNotContain("DROP TABLE")
        assertThat(CopilotMigrations.ALL.toList()).contains(CopilotMigrations.MIGRATION_21_22)
    }

    @Test
    fun migration21To22_executesInDisposableDatabaseAndPreservesTherapyRows() {
        val path = Files.createTempFile("copilot-room-v21-v22-", ".sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA user_version = 21")
                    statement.execute("CREATE TABLE legacy_marker (id TEXT PRIMARY KEY, value TEXT NOT NULL)")
                    statement.execute("INSERT INTO legacy_marker (id, value) VALUES ('keep', 'preserved')")
                    statement.execute(
                        "CREATE TABLE therapy_events (" +
                            "id TEXT NOT NULL PRIMARY KEY, timestamp INTEGER NOT NULL, " +
                            "type TEXT NOT NULL, payloadJson TEXT NOT NULL)"
                    )
                    statement.execute(
                        "INSERT INTO therapy_events (id, timestamp, type, payloadJson) " +
                            "VALUES ('therapy-1', 1, 'CARBS', '{\"grams\":12}')"
                    )

                    migrateAsRoom(connection, CopilotMigrations.MIGRATION_21_22)

                    assertThat(singleString(connection, "SELECT value FROM legacy_marker WHERE id = 'keep'"))
                        .isEqualTo("preserved")
                    assertThat(singleLong(connection, "SELECT COUNT(*) FROM therapy_events")).isEqualTo(1L)
                    statement.executeUpdate(
                        "INSERT INTO planned_activity_events (" +
                            "eventId, enabled, title, activityType, intensity, localStartIso, " +
                            "durationMinutes, timezoneId, recurrenceDaysMask, recurrenceEndEpochDay, " +
                            "revision, createdAtMs, updatedAtMs) VALUES (" +
                            "'event-1', 1, 'Run', 'RUNNING', 'MODERATE', '2026-08-02T07:00', " +
                            "30, 'Asia/Tbilisi', 0, NULL, 1, 1, 2)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO meal_profile_overrides (" +
                            "canonicalTherapyIdentity, therapyRevisionHash, profile, durationMinutes, " +
                            "source, revision, updatedAtMs) VALUES (" +
                            "'meal-1', 'hash', 'LOWER', 60, 'MANUAL', 1, 2)"
                    )
                    statement.executeUpdate(
                        "INSERT INTO energy_profile_snapshots (" +
                            "snapshotId, schemaVersion, evidenceStartMs, evidenceEndMs, qualityDays, " +
                            "tier, foodProfile, foodDurationMinutes, activityProfile, confidence, " +
                            "replayPassed, sourceHashSha256, calculatedAtMs, stale) VALUES (" +
                            "'snapshot-1', 1, 1, 2, 7, 'HIGH', NULL, NULL, NULL, 0.9, 1, 'hash', 3, 0)"
                    )
                    assertThat(singleString(connection, "SELECT title FROM planned_activity_events WHERE eventId = 'event-1'"))
                        .isEqualTo("Run")
                    assertThat(singleString(connection, "SELECT profile FROM meal_profile_overrides WHERE canonicalTherapyIdentity = 'meal-1'"))
                        .isEqualTo("LOWER")
                    assertThat(singleString(connection, "SELECT tier FROM energy_profile_snapshots WHERE snapshotId = 'snapshot-1'"))
                        .isEqualTo("HIGH")
                    assertThat(singleString(connection, "PRAGMA integrity_check")).isEqualTo("ok")
                    assertThat(singleLong(connection, "PRAGMA user_version")).isEqualTo(22L)
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun migration21To22_retainsNewestNinetySnapshotsWithStableIdTieBreak() {
        val path = Files.createTempFile("copilot-room-v21-v22-retention-", ".sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    migrateAsRoom(connection, CopilotMigrations.MIGRATION_21_22)
                    repeat(91) { index ->
                        statement.executeUpdate(
                            "INSERT INTO energy_profile_snapshots (" +
                                "snapshotId, schemaVersion, evidenceStartMs, evidenceEndMs, qualityDays, " +
                                "tier, foodProfile, foodDurationMinutes, activityProfile, confidence, " +
                                "replayPassed, sourceHashSha256, calculatedAtMs, stale) VALUES (" +
                                "'snapshot-${index.toString().padStart(3, '0')}', 1, 1, 2, 7, 'HIGH', " +
                                "NULL, NULL, NULL, 0.9, 1, 'hash', 3, 0)"
                        )
                    }
                    statement.executeUpdate(
                        "DELETE FROM energy_profile_snapshots WHERE snapshotId NOT IN (" +
                            "SELECT snapshotId FROM energy_profile_snapshots " +
                            "ORDER BY calculatedAtMs DESC, snapshotId DESC LIMIT 90)"
                    )

                    assertThat(singleLong(connection, "SELECT COUNT(*) FROM energy_profile_snapshots"))
                        .isEqualTo(90L)
                    assertThat(
                        singleLong(
                            connection,
                            "SELECT COUNT(*) FROM energy_profile_snapshots WHERE snapshotId = 'snapshot-000'"
                        )
                    ).isEqualTo(0L)
                    assertThat(
                        singleLong(
                            connection,
                            "SELECT COUNT(*) FROM energy_profile_snapshots WHERE snapshotId = 'snapshot-090'"
                        )
                    ).isEqualTo(1L)
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun migration20To21_addsOnlyEatingWindowSnapshots() {
        assertThat(CopilotMigrations.MIGRATION_20_21.startVersion).isEqualTo(20)
        assertThat(CopilotMigrations.MIGRATION_20_21.endVersion).isEqualTo(21)

        val statements = CopilotMigrations.MIGRATION_20_21_STATEMENTS.joinToString("\n")
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `eating_window_snapshots`")
        assertThat(statements).contains("PRIMARY KEY(`localCompletedDate`)")
        assertThat(statements).contains("index_eating_window_snapshots_generatedAt_localCompletedDate")
        assertThat(statements).doesNotContain("DROP TABLE")
        assertThat(CopilotMigrations.ALL.toList()).contains(CopilotMigrations.MIGRATION_20_21)
    }

    @Test
    fun migration20To21_executesInDisposableDatabaseAndPreservesExistingRows() {
        val path = Files.createTempFile("copilot-room-v20-v21-", ".sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA user_version = 20")
                    statement.execute("CREATE TABLE legacy_marker (id TEXT PRIMARY KEY, value TEXT NOT NULL)")
                    statement.execute("INSERT INTO legacy_marker (id, value) VALUES ('keep', 'preserved')")

                    migrateAsRoom(connection, CopilotMigrations.MIGRATION_20_21)

                    assertThat(singleString(connection, "SELECT value FROM legacy_marker WHERE id = 'keep'"))
                        .isEqualTo("preserved")
                    statement.executeUpdate(
                        "INSERT INTO eating_window_snapshots " +
                            "(localCompletedDate, generatedAt, sourceFingerprint, recentWindowsJson, stableWindowsJson) " +
                            "VALUES ('2026-08-01', 1, 'fingerprint', '[]', '[]')"
                    )
                    assertThat(
                        singleString(
                            connection,
                            "SELECT sourceFingerprint FROM eating_window_snapshots " +
                                "WHERE localCompletedDate = '2026-08-01'"
                        )
                    ).isEqualTo("fingerprint")
                    val plan = buildString {
                        statement.executeQuery(
                            "EXPLAIN QUERY PLAN SELECT * FROM eating_window_snapshots " +
                                "ORDER BY generatedAt DESC, localCompletedDate DESC LIMIT 1"
                        ).use { rows ->
                            while (rows.next()) append(rows.getString("detail")).append('\n')
                        }
                    }
                    assertThat(plan).contains("index_eating_window_snapshots_generatedAt_localCompletedDate")
                    assertThat(singleString(connection, "PRAGMA integrity_check")).isEqualTo("ok")
                    assertThat(singleLong(connection, "PRAGMA user_version")).isEqualTo(21L)
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun migration9To10_hasExpectedVersionRange() {
        assertThat(CopilotMigrations.MIGRATION_9_10.startVersion).isEqualTo(9)
        assertThat(CopilotMigrations.MIGRATION_9_10.endVersion).isEqualTo(10)
    }

    @Test
    fun migration9To10_coversNewPhysiologyTables() {
        val statements = CopilotMigrations.MIGRATION_9_10_STATEMENTS.joinToString(separator = "\n")
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `isf_cr_snapshots`")
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `isf_cr_evidence`")
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `isf_cr_model_state`")
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `physio_context_tags`")
        assertThat(statements).contains("index_isf_cr_snapshots_ts")
        assertThat(statements).contains("index_isf_cr_evidence_sampleType_hourLocal")
        assertThat(statements).contains("index_physio_context_tags_tagType")
    }

    @Test
    fun migrationPackage_contains9To10Migration() {
        assertThat(CopilotMigrations.ALL.toList()).contains(CopilotMigrations.MIGRATION_9_10)
    }

    @Test
    fun migration10To11_hasExpectedVersionRange() {
        assertThat(CopilotMigrations.MIGRATION_10_11.startVersion).isEqualTo(10)
        assertThat(CopilotMigrations.MIGRATION_10_11.endVersion).isEqualTo(11)
    }

    @Test
    fun migration10To11_coversCircadianPatternTables() {
        val statements = CopilotMigrations.MIGRATION_10_11_STATEMENTS.joinToString(separator = "\n")
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `circadian_slot_stats`")
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `circadian_transition_stats`")
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `circadian_pattern_snapshots`")
        assertThat(statements).contains("index_circadian_slot_stats_dayType_windowDays")
        assertThat(statements).contains("index_circadian_transition_stats_dayType_windowDays_horizonMinutes")
        assertThat(statements).contains("index_circadian_pattern_snapshots_updatedAt")
    }

    @Test
    fun migrationPackage_contains10To11Migration() {
        assertThat(CopilotMigrations.ALL.toList()).contains(CopilotMigrations.MIGRATION_10_11)
    }

    @Test
    fun migration11To12_hasExpectedVersionRange() {
        assertThat(CopilotMigrations.MIGRATION_11_12.startVersion).isEqualTo(11)
        assertThat(CopilotMigrations.MIGRATION_11_12.endVersion).isEqualTo(12)
    }

    @Test
    fun migration11To12_coversCircadianReplayTables() {
        val statements = CopilotMigrations.MIGRATION_11_12_STATEMENTS.joinToString(separator = "\n")
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `circadian_replay_slot_stats`")
        assertThat(statements).contains("index_circadian_replay_slot_stats_dayType_windowDays")
        assertThat(statements).contains("index_circadian_replay_slot_stats_dayType_slotIndex_horizonMinutes")
    }

    @Test
    fun migrationPackage_contains11To12Migration() {
        assertThat(CopilotMigrations.ALL.toList()).contains(CopilotMigrations.MIGRATION_11_12)
    }

    @Test
    fun migration12To13_hasExpectedVersionRange() {
        assertThat(CopilotMigrations.MIGRATION_12_13.startVersion).isEqualTo(12)
        assertThat(CopilotMigrations.MIGRATION_12_13.endVersion).isEqualTo(13)
    }

    @Test
    fun migration12To13_coversCalibrationTables() {
        val statements = CopilotMigrations.MIGRATION_12_13_STATEMENTS.joinToString(separator = "\n")
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `blood_glucose_checks`")
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `glucose_calibration_models`")
        assertThat(statements).contains("index_blood_glucose_checks_sensorSessionKey_timestamp")
        assertThat(statements).contains("index_glucose_calibration_models_status_createdAt")
    }

    @Test
    fun migrationPackage_contains12To13Migration() {
        assertThat(CopilotMigrations.ALL.toList()).contains(CopilotMigrations.MIGRATION_12_13)
    }

    @Test
    fun migration13To14_hasExpectedVersionRange() {
        assertThat(CopilotMigrations.MIGRATION_13_14.startVersion).isEqualTo(13)
        assertThat(CopilotMigrations.MIGRATION_13_14.endVersion).isEqualTo(14)
    }

    @Test
    fun migration13To14_addsTelemetryPerformanceIndexes() {
        val statements = CopilotMigrations.MIGRATION_13_14_STATEMENTS.joinToString(separator = "\n")
        assertThat(statements).contains("index_telemetry_samples_timestamp_source_key")
    }

    @Test
    fun migrationPackage_contains13To14Migration() {
        assertThat(CopilotMigrations.ALL.toList()).contains(CopilotMigrations.MIGRATION_13_14)
    }

    @Test
    fun migration14To15_hasExactTargetManagerTablesAndUniqueSemanticIndex() {
        assertThat(CopilotMigrations.MIGRATION_14_15.startVersion).isEqualTo(14)
        assertThat(CopilotMigrations.MIGRATION_14_15.endVersion).isEqualTo(15)

        val statements = CopilotMigrations.MIGRATION_14_15_STATEMENTS.joinToString("\n")
        assertThat(
            CopilotMigrations.MIGRATION_14_15_STATEMENTS.count {
                it.trimStart().startsWith("CREATE TABLE IF NOT EXISTS")
            }
        ).isEqualTo(2)
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `target_manager_state`")
        assertThat(statements).contains("PRIMARY KEY(`mode`)")
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `target_manager_decisions`")
        assertThat(statements).contains("PRIMARY KEY(`id`)")
        assertThat(statements).contains("index_target_manager_decisions_timestamp")
        assertThat(statements).contains(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_target_manager_decisions_mode_semanticFingerprint`"
        )
        assertThat(statements).doesNotContain("DROP TABLE")
        assertThat(CopilotMigrations.ALL.toList()).contains(CopilotMigrations.MIGRATION_14_15)
    }

    @Test
    fun migration14To15_executesInDisposableDatabaseAndPreservesExistingRows() {
        val path = Files.createTempFile("copilot-room-v14-v15-", ".sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA user_version = 14")
                    statement.execute("CREATE TABLE legacy_marker (id TEXT PRIMARY KEY, value TEXT NOT NULL)")
                    statement.execute("INSERT INTO legacy_marker (id, value) VALUES ('keep', 'preserved')")

                    CopilotMigrations.MIGRATION_14_15_STATEMENTS.forEach(statement::execute)

                    statement.executeQuery("SELECT value FROM legacy_marker WHERE id = 'keep'").use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getString(1)).isEqualTo("preserved")
                    }
                    statement.executeUpdate(
                        "INSERT INTO target_manager_state " +
                            "(mode, updatedAt, acceptedTargetJson, lastDecisionFingerprint, " +
                            "lastSafetyBypassFingerprint, reconciliationStatus) " +
                            "VALUES ('ACTIVE', 1, NULL, NULL, NULL, 'confirmed')"
                    )
                    assertThrows(SQLException::class.java) {
                        statement.executeUpdate(
                            "INSERT INTO target_manager_state " +
                                "(mode, updatedAt, acceptedTargetJson, lastDecisionFingerprint, " +
                                "lastSafetyBypassFingerprint, reconciliationStatus) " +
                                "VALUES ('ACTIVE', 2, NULL, NULL, NULL, 'confirmed')"
                        )
                    }

                    statement.executeUpdate(decisionInsertSql("decision-a", "ACTIVE", "fingerprint-a"))
                    assertThrows(SQLException::class.java) {
                        statement.executeUpdate(decisionInsertSql("decision-a", "SHADOW", "fingerprint-b"))
                    }
                    assertThrows(SQLException::class.java) {
                        statement.executeUpdate(decisionInsertSql("decision-b", "ACTIVE", "fingerprint-a"))
                    }

                    val plan = buildString {
                        statement.executeQuery(
                            "EXPLAIN QUERY PLAN SELECT * FROM target_manager_decisions " +
                                "WHERE deliveryStatus = 'pending' ORDER BY timestamp ASC, id ASC"
                        ).use { rows ->
                            while (rows.next()) append(rows.getString("detail")).append('\n')
                        }
                    }
                    assertThat(plan).contains("index_target_manager_decisions_deliveryStatus_timestamp_id")
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun migration15To16_hasExactCircadianTablesAndIndexes() {
        assertThat(CopilotMigrations.MIGRATION_15_16.startVersion).isEqualTo(15)
        assertThat(CopilotMigrations.MIGRATION_15_16.endVersion).isEqualTo(16)

        val statements = CopilotMigrations.MIGRATION_15_16_STATEMENTS.joinToString("\n")
        assertThat(
            CopilotMigrations.MIGRATION_15_16_STATEMENTS.count {
                it.trimStart().startsWith("CREATE TABLE IF NOT EXISTS")
            }
        ).isEqualTo(2)
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `circadian_target_runs`")
        assertThat(statements).contains("`localRunDate` TEXT NOT NULL")
        assertThat(statements).contains(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_circadian_target_runs_scheduleRevision_localRunDate`"
        )
        assertThat(statements).contains("CREATE TABLE IF NOT EXISTS `circadian_target_adjustments`")
        assertThat(statements).contains(
            "FOREIGN KEY(`runId`) REFERENCES `circadian_target_runs`(`runId`)"
        )
        assertThat(statements).contains("ON DELETE CASCADE")
        assertThat(statements).doesNotContain("DROP TABLE")
        assertThat(CopilotMigrations.ALL.toList()).contains(CopilotMigrations.MIGRATION_15_16)
    }

    @Test
    fun migration15To16_enforcesUniqueLocalRunAndCascadesAdjustments() {
        val path = Files.createTempFile("copilot-room-v15-v16-", ".sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA foreign_keys = ON")
                    statement.execute("CREATE TABLE legacy_marker (id TEXT PRIMARY KEY, value TEXT NOT NULL)")
                    statement.execute("INSERT INTO legacy_marker (id, value) VALUES ('keep', 'preserved')")

                    CopilotMigrations.MIGRATION_15_16_STATEMENTS.forEach(statement::execute)

                    statement.executeUpdate(circadianRunInsertSql("run-a", 7L, "2026-07-20"))
                    assertThrows(SQLException::class.java) {
                        statement.executeUpdate(circadianRunInsertSql("run-b", 7L, "2026-07-20"))
                    }
                    statement.executeUpdate(circadianAdjustmentInsertSql("adjustment-a", "run-a", 7L))
                    statement.executeUpdate("DELETE FROM circadian_target_runs WHERE runId = 'run-a'")
                    statement.executeQuery(
                        "SELECT COUNT(*) FROM circadian_target_adjustments WHERE runId = 'run-a'"
                    ).use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getInt(1)).isEqualTo(0)
                    }
                    statement.executeQuery("SELECT value FROM legacy_marker WHERE id = 'keep'").use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getString(1)).isEqualTo("preserved")
                    }
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun migration15To16_failedPublicationTransactionLeavesNoVisibleRows() {
        val path = Files.createTempFile("copilot-room-v16-rollback-", ".sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA foreign_keys = ON")
                    CopilotMigrations.MIGRATION_15_16_STATEMENTS.forEach(statement::execute)
                }

                connection.autoCommit = false
                try {
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(circadianRunInsertSql("run-rollback", 11L, "2026-07-20"))
                        statement.executeUpdate(
                            circadianAdjustmentInsertSql("adjustment-duplicate", "run-rollback", 11L)
                        )
                        statement.executeUpdate(
                            circadianAdjustmentInsertSql("adjustment-duplicate", "run-rollback", 11L)
                        )
                    }
                    connection.commit()
                } catch (_: SQLException) {
                    connection.rollback()
                } finally {
                    connection.autoCommit = true
                }

                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM circadian_target_runs").use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getInt(1)).isEqualTo(0)
                    }
                    statement.executeQuery("SELECT COUNT(*) FROM circadian_target_adjustments").use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getInt(1)).isEqualTo(0)
                    }
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun migration14To16_executesSequentiallyAndPreservesTargetManagerRows() {
        val path = Files.createTempFile("copilot-room-v14-v16-", ".sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA foreign_keys = ON")
                    CopilotMigrations.MIGRATION_14_15_STATEMENTS.forEach(statement::execute)
                    statement.executeUpdate(decisionInsertSql("decision-keep", "ACTIVE", "fingerprint-keep"))
                    CopilotMigrations.MIGRATION_15_16_STATEMENTS.forEach(statement::execute)
                    statement.executeUpdate(circadianRunInsertSql("run-full", 9L, "2026-07-20"))

                    statement.executeQuery(
                        "SELECT semanticFingerprint FROM target_manager_decisions WHERE id = 'decision-keep'"
                    ).use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getString(1)).isEqualTo("fingerprint-keep")
                    }
                    statement.executeQuery(
                        "SELECT scheduleRevision FROM circadian_target_runs WHERE runId = 'run-full'"
                    ).use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getLong(1)).isEqualTo(9L)
                    }
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun migration16To17_repairsPreviouslyPublishedV16SchemaWithoutDeletingRows() {
        assertThat(CopilotMigrations.MIGRATION_16_17.startVersion).isEqualTo(16)
        assertThat(CopilotMigrations.MIGRATION_16_17.endVersion).isEqualTo(17)

        val path = Files.createTempFile("copilot-room-v16-v17-", ".sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA user_version = 16")
                    statement.execute("CREATE TABLE legacy_marker (id TEXT PRIMARY KEY, value TEXT NOT NULL)")
                    statement.execute("INSERT INTO legacy_marker (id, value) VALUES ('keep', 'preserved')")

                    CopilotMigrations.MIGRATION_16_17_STATEMENTS.forEach(statement::execute)

                    statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'table' " +
                            "AND name IN ('blood_glucose_checks', 'glucose_calibration_models') ORDER BY name"
                    ).use { rows ->
                        val names = buildList {
                            while (rows.next()) add(rows.getString(1))
                        }
                        assertThat(names).containsExactly(
                            "blood_glucose_checks",
                            "glucose_calibration_models"
                        )
                    }
                    statement.executeQuery("SELECT value FROM legacy_marker WHERE id = 'keep'").use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getString(1)).isEqualTo("preserved")
                    }
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }

        assertThat(CopilotMigrations.ALL.toList()).contains(CopilotMigrations.MIGRATION_16_17)
    }

    @Test
    fun migration17To18_addsCausalStepEvidenceWithoutDeletingAdjustments() {
        assertThat(CopilotMigrations.MIGRATION_17_18.startVersion).isEqualTo(17)
        assertThat(CopilotMigrations.MIGRATION_17_18.endVersion).isEqualTo(18)

        val path = Files.createTempFile("copilot-room-v17-v18-", ".sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA foreign_keys = ON")
                    CopilotMigrations.MIGRATION_15_16_STATEMENTS.forEach(statement::execute)
                    statement.executeUpdate(circadianRunInsertSql("run-step", 11L, "2026-07-20"))
                    statement.executeUpdate(circadianAdjustmentInsertSql("step-keep", "run-step", 11L))

                    CopilotMigrations.MIGRATION_17_18_STATEMENTS.forEach(statement::execute)

                    statement.executeQuery(
                        "SELECT appliedDeltaMmol, lowerTailReplayErrorMmol, " +
                            "stepBaselineTrustedLowCount, stepBaselineVariabilityIqrMmol, " +
                            "stepBaselineLowerTailReplayErrorMmol " +
                            "FROM circadian_target_adjustments WHERE id = 'step-keep'"
                    ).use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getDouble(1)).isWithin(1e-9).of(-0.1)
                        assertThat(rows.getObject(2)).isNull()
                        assertThat(rows.getObject(3)).isNull()
                        assertThat(rows.getObject(4)).isNull()
                        assertThat(rows.getObject(5)).isNull()
                    }
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }

        assertThat(CopilotMigrations.ALL.toList()).contains(CopilotMigrations.MIGRATION_17_18)
    }

    @Test
    fun migration18To19_addsClinicalReportsWithoutDeletingExistingRows() {
        assertThat(CopilotMigrations.MIGRATION_18_19.startVersion).isEqualTo(18)
        assertThat(CopilotMigrations.MIGRATION_18_19.endVersion).isEqualTo(19)

        val path = Files.createTempFile("copilot-room-v18-v19-", ".sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA user_version = 18")
                    createRequiredV18Schema(connection)
                    insertRepresentativeV18Rows(connection)
                }

                migrateAsRoom(connection, CopilotMigrations.MIGRATION_18_19)

                assertSingleRow(
                    connection = connection,
                    sql = "SELECT timestamp, mmol, source, quality FROM glucose_samples WHERE id = 1",
                    expected = listOf(1_000L, 6.2, "AAPS", "GOOD")
                )
                assertSingleRow(
                    connection = connection,
                    sql = "SELECT timestamp, type, payloadJson FROM therapy_events WHERE id = 'therapy-1'",
                    expected = listOf(1_100L, "CARBS", """{"grams":12}""")
                )
                assertSingleRow(
                    connection = connection,
                    sql = "SELECT timestamp, type, payloadJson, safetyJson, idempotencyKey, status " +
                        "FROM action_commands WHERE id = 'command-1'",
                    expected = listOf(
                        1_200L,
                        "TEMP_TARGET",
                        """{"target":6.0}""",
                        """{"allowed":true}""",
                        "idem-1",
                        "CONFIRMED"
                    )
                )

                assertThat(tableColumns(connection, "clinical_reports")).containsExactly(
                    ColumnInfo("requestId", "TEXT", notNull = true, primaryKeyPosition = 1),
                    ColumnInfo("status", "TEXT", notNull = true),
                    ColumnInfo("requestedFromTs", "INTEGER", notNull = true),
                    ColumnInfo("requestedThroughTs", "INTEGER", notNull = true),
                    ColumnInfo("requestHash", "TEXT", notNull = true),
                    ColumnInfo("coverageJson", "TEXT", notNull = true),
                    ColumnInfo("localSummaryJson", "TEXT", notNull = true),
                    ColumnInfo("responseJson", "TEXT", notNull = false),
                    ColumnInfo("renderedText", "TEXT", notNull = false),
                    ColumnInfo("model", "TEXT", notNull = false),
                    ColumnInfo("schemaVersion", "INTEGER", notNull = true),
                    ColumnInfo("createdAt", "INTEGER", notNull = true),
                    ColumnInfo("completedAt", "INTEGER", notNull = false),
                    ColumnInfo("sanitizedError", "TEXT", notNull = false)
                ).inOrder()
                assertThat(tableColumns(connection, "clinical_reports").map { it.name })
                    .doesNotContain("rawPayload")
                assertThat(tableIndexes(connection, "clinical_reports")).containsExactly(
                    "index_clinical_reports_createdAt",
                    "index_clinical_reports_status",
                    "sqlite_autoindex_clinical_reports_1"
                )
                assertThat(tableIndexes(connection, "glucose_samples"))
                    .contains("index_glucose_samples_timestamp")
                assertThat(tableIndexes(connection, "therapy_events")).containsAtLeast(
                    "index_therapy_events_timestamp",
                    "index_therapy_events_type"
                )
                assertThat(tableIndexes(connection, "action_commands")).containsAtLeast(
                    "index_action_commands_timestamp",
                    "index_action_commands_status"
                )
                assertThat(singleString(connection, "PRAGMA integrity_check")).isEqualTo("ok")
                assertThat(singleLong(connection, "PRAGMA user_version")).isEqualTo(19L)
                assertThat(singleLong(connection, "SELECT COUNT(*) FROM clinical_reports")).isEqualTo(0L)
            }
        } finally {
            Files.deleteIfExists(path)
        }

        assertThat(CopilotMigrations.ALL.toList()).contains(CopilotMigrations.MIGRATION_18_19)
    }

    @Test
    fun migration19To20_addsOpenAiProviderToExistingClinicalReports() {
        val migration = CopilotMigrations.ALL.singleOrNull {
            it.startVersion == 19 && it.endVersion == 20
        }
        assertThat(migration).isNotNull()
        assertThat(executedStatements(checkNotNull(migration))).containsExactly(
            "ALTER TABLE clinical_reports ADD COLUMN provider TEXT NOT NULL DEFAULT 'OPENAI'"
        )

        val path = Files.createTempFile("copilot-room-v19-v20-", ".sqlite")
        try {
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA user_version = 19")
                    CopilotMigrations.MIGRATION_18_19_STATEMENTS.forEach(statement::execute)
                    statement.execute(
                        "INSERT INTO clinical_reports (" +
                            "requestId, status, requestedFromTs, requestedThroughTs, requestHash, " +
                            "coverageJson, localSummaryJson, responseJson, renderedText, model, " +
                            "schemaVersion, createdAt, completedAt, sanitizedError" +
                            ") VALUES (" +
                            "'existing', 'COMPLETE', 1, 2, 'hash', '{}', '{}', NULL, NULL, " +
                            "'gpt-5.6-terra', 3, 4, 5, NULL)"
                    )
                }

                migrateAsRoom(connection, migration)

                assertThat(
                    singleString(
                        connection,
                        "SELECT provider FROM clinical_reports WHERE requestId = 'existing'"
                    )
                ).isEqualTo("OPENAI")
                assertThat(tableColumns(connection, "clinical_reports")).contains(
                    ColumnInfo("provider", "TEXT", notNull = true)
                )
                assertThat(singleString(connection, "PRAGMA integrity_check")).isEqualTo("ok")
                assertThat(singleLong(connection, "PRAGMA user_version")).isEqualTo(20L)
            }
        } finally {
            Files.deleteIfExists(path)
        }

        val entityFields = ClinicalReportEntity::class.java.declaredFields.map { it.name }
        assertThat(entityFields).contains("provider")
        assertThat(entityFields).containsNoneOf(
            "rawPayload",
            "payload",
            "endpoint",
            "apiKey",
            "credential"
        )
    }

    private fun createRequiredV18Schema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE glucose_samples (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "timestamp INTEGER NOT NULL, mmol REAL NOT NULL, " +
                    "source TEXT NOT NULL, quality TEXT NOT NULL)"
            )
            statement.execute(
                "CREATE INDEX index_glucose_samples_timestamp ON glucose_samples (timestamp)"
            )
            statement.execute(
                "CREATE TABLE therapy_events (" +
                    "id TEXT NOT NULL PRIMARY KEY, timestamp INTEGER NOT NULL, " +
                    "type TEXT NOT NULL, payloadJson TEXT NOT NULL)"
            )
            statement.execute(
                "CREATE INDEX index_therapy_events_timestamp ON therapy_events (timestamp)"
            )
            statement.execute("CREATE INDEX index_therapy_events_type ON therapy_events (type)")
            statement.execute(
                "CREATE TABLE action_commands (" +
                    "id TEXT NOT NULL PRIMARY KEY, timestamp INTEGER NOT NULL, " +
                    "type TEXT NOT NULL, payloadJson TEXT NOT NULL, safetyJson TEXT NOT NULL, " +
                    "idempotencyKey TEXT NOT NULL, status TEXT NOT NULL)"
            )
            statement.execute(
                "CREATE INDEX index_action_commands_timestamp ON action_commands (timestamp)"
            )
            statement.execute(
                "CREATE INDEX index_action_commands_status ON action_commands (status)"
            )
        }
    }

    private fun insertRepresentativeV18Rows(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                "INSERT INTO glucose_samples " +
                    "(id, timestamp, mmol, source, quality) VALUES (1, 1000, 6.2, 'AAPS', 'GOOD')"
            )
            statement.execute(
                "INSERT INTO therapy_events " +
                    "(id, timestamp, type, payloadJson) " +
                    "VALUES ('therapy-1', 1100, 'CARBS', '{\"grams\":12}')"
            )
            statement.execute(
                "INSERT INTO action_commands " +
                    "(id, timestamp, type, payloadJson, safetyJson, idempotencyKey, status) " +
                    "VALUES ('command-1', 1200, 'TEMP_TARGET', '{\"target\":6.0}', " +
                    "'{\"allowed\":true}', 'idem-1', 'CONFIRMED')"
            )
        }
    }

    private fun createSchema25Fixture(
        connection: Connection,
        userVersion: Int = 25,
        includeV22ToV25Tables: Boolean = true
    ) {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA user_version = $userVersion")
            statement.execute("CREATE TABLE legacy_marker (id TEXT PRIMARY KEY, value TEXT NOT NULL)")
            statement.execute("INSERT INTO legacy_marker VALUES ('keep', 'preserved')")
            statement.execute(
                "CREATE TABLE therapy_events (id TEXT NOT NULL PRIMARY KEY, " +
                    "timestamp INTEGER NOT NULL, type TEXT NOT NULL, payloadJson TEXT NOT NULL)"
            )
            statement.execute(
                "INSERT INTO therapy_events VALUES " +
                    "('therapy-keep', 1, 'CARBS', '{\"grams\":20}')"
            )
            statement.execute(
                "CREATE TABLE physio_context_tags (" +
                    "id TEXT NOT NULL PRIMARY KEY, tsStart INTEGER NOT NULL, tsEnd INTEGER NOT NULL, " +
                    "tagType TEXT NOT NULL, severity REAL NOT NULL, source TEXT NOT NULL, note TEXT NOT NULL)"
            )
            statement.execute(
                "INSERT INTO physio_context_tags VALUES " +
                    "('context-keep', 1, 2, 'ILLNESS', 0.5, 'MANUAL', 'keep')"
            )
            if (includeV22ToV25Tables) {
                statement.execute(
                    "CREATE TABLE pending_meal_profile_intents (" +
                        "idempotencyKey TEXT NOT NULL PRIMARY KEY, copilotNote TEXT NOT NULL, " +
                        "profile TEXT NOT NULL, durationMinutes INTEGER NOT NULL, " +
                        "expectedCarbsGrams REAL NOT NULL, submittedAtMs INTEGER NOT NULL, " +
                        "expiresAtMs INTEGER NOT NULL, manualMealEnergyKcal REAL)"
                )
            }
        }
    }

    private fun assertV26IntegrityAndIndexes(connection: Connection) {
        val expectedTables = setOf(
            "sensitivity_runtime_snapshots",
            "alert_events",
            "alert_delivery_receipts",
            "alert_ai_analyses",
            "context_event_sync"
        )
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name IN (${expectedTables.joinToString { "'$it'" }})"
            ).use { rows ->
                val actual = buildSet { while (rows.next()) add(rows.getString(1)) }
                assertThat(actual).containsExactlyElementsIn(expectedTables)
            }
            val expectedIndexes = setOf(
                "index_sensitivity_runtime_snapshots_generatedAt",
                "index_sensitivity_runtime_snapshots_settingsRevision_generatedAt",
                "index_alert_events_status_updatedAt",
                "index_alert_events_eventType_updatedAt",
                "index_alert_events_resolvedAt",
                "index_alert_delivery_receipts_episodeId_kind",
                "index_alert_delivery_receipts_attemptedAt",
                "index_alert_ai_analyses_episodeId",
                "index_alert_ai_analyses_status_requestedAt",
                "index_context_event_sync_eventId_revision_operation",
                "index_context_event_sync_status_attemptedAt",
                "index_physio_context_tags_status_tsStart",
                "index_physio_context_tags_updatedAt"
            )
            statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index'").use { rows ->
                val actual = buildSet { while (rows.next()) add(rows.getString(1)) }
                assertThat(actual).containsAtLeastElementsIn(expectedIndexes)
            }
            statement.executeQuery("PRAGMA foreign_key_check").use { rows ->
                assertThat(rows.next()).isFalse()
            }
        }
        assertThat(singleString(connection, "PRAGMA integrity_check")).isEqualTo("ok")
        assertThat(singleLong(connection, "PRAGMA user_version")).isEqualTo(26L)
    }

    private fun executedStatements(migration: Migration): List<String> {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "execSQL" -> {
                    statements += args.single() as String
                    null
                }
                "toString" -> "RecordingSupportSQLiteDatabase"
                "hashCode" -> System.identityHashCode(statements)
                "equals" -> args.single() === proxy
                else -> error("Unexpected SupportSQLiteDatabase call: ${method.name}")
            }
        } as SupportSQLiteDatabase

        migration.migrate(database)
        return statements
    }

    private fun tableColumns(connection: Connection, table: String): List<ColumnInfo> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(`$table`)").use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            ColumnInfo(
                                name = rows.getString("name"),
                                type = rows.getString("type"),
                                notNull = rows.getInt("notnull") == 1,
                                primaryKeyPosition = rows.getInt("pk")
                            )
                        )
                    }
                }
            }
        }

    private fun tableIndexes(connection: Connection, table: String): Set<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table'"
            ).use { rows ->
                buildSet {
                    while (rows.next()) add(rows.getString("name"))
                }
            }
        }

    private fun assertSingleRow(connection: Connection, sql: String, expected: List<Any>) {
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                assertThat(rows.next()).isTrue()
                expected.forEachIndexed { index, value ->
                    assertThat(rows.getObject(index + 1)).isEqualTo(value)
                }
                assertThat(rows.next()).isFalse()
            }
        }
    }

    private fun singleString(connection: Connection, sql: String): String =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }

    private fun singleLong(connection: Connection, sql: String): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }

    private data class ColumnInfo(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val primaryKeyPosition: Int = 0
    )

    private fun decisionInsertSql(id: String, mode: String, fingerprint: String): String =
        "INSERT INTO target_manager_decisions " +
            "(id, timestamp, mode, semanticFingerprint, outcome, winnerJson, commandJson, " +
            "cadenceOutcome, cadenceReason, lastSentTargetMmol, lastSentTimestamp, deliveryStatus, " +
            "reasonCodesJson, rejectedProposalReasonsJson) VALUES " +
            "('$id', 1, '$mode', '$fingerprint', 'SEND', NULL, NULL, NULL, NULL, NULL, NULL, " +
            "'pending', '[]', '{}')"

    private fun circadianRunInsertSql(runId: String, revision: Long, localDate: String): String =
        "INSERT INTO circadian_target_runs " +
            "(runId, scheduleRevision, localRunDate, startedAt, completedAt, lookbackStart, " +
            "lookbackEnd, status, validDays, trustedShare, lowRiskPassed, reasonCodesJson) VALUES " +
            "('$runId', $revision, '$localDate', 1, 2, 0, 1, 'ACTIVE', 7, 1.0, 1, '[]')"

    private fun circadianAdjustmentInsertSql(id: String, runId: String, revision: Long): String =
        "INSERT INTO circadian_target_adjustments " +
            "(id, runId, scheduleRevision, dayType, hour, manualTargetMmol, desiredDeltaMmol, " +
            "appliedDeltaMmol, medianMmol, p25, p75, trustedLowCount, sampleCount, activeDays, " +
            "qualityScore, sensorTrustedShare, generatedAt, validUntil, status, reasonCodesJson) VALUES " +
            "('$id', '$runId', $revision, 'ALL', 9, 6.0, -0.1, -0.1, 7.0, 6.4, 7.6, " +
            "0, 40, 7, 0.9, 1.0, 2, 3, 'ACTIVE', '[]')"
}

internal fun migrateAsRoom(connection: Connection, migration: Migration) {
    val database = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "execSQL" -> {
                connection.createStatement().use { it.execute(args.single() as String) }
                null
            }
            "toString" -> "JdbcSupportSQLiteDatabase"
            "hashCode" -> System.identityHashCode(connection)
            "equals" -> args.single() === proxy
            else -> error("Unexpected SupportSQLiteDatabase call: ${method.name}")
        }
    } as SupportSQLiteDatabase

    migration.migrate(database)
    connection.createStatement().use {
        it.execute("PRAGMA user_version = ${migration.endVersion}")
    }
}
