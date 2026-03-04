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
}
