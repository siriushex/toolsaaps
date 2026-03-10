package io.aaps.copilot.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CopilotMigrationsTest {

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
}
