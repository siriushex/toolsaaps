package io.aaps.copilot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object CopilotMigrations {

    internal val MIGRATION_9_10_STATEMENTS = listOf(
        """
        CREATE TABLE IF NOT EXISTS `isf_cr_snapshots` (
            `id` TEXT NOT NULL,
            `ts` INTEGER NOT NULL,
            `isfEff` REAL NOT NULL,
            `crEff` REAL NOT NULL,
            `isfBase` REAL NOT NULL,
            `crBase` REAL NOT NULL,
            `ciIsfLow` REAL NOT NULL,
            `ciIsfHigh` REAL NOT NULL,
            `ciCrLow` REAL NOT NULL,
            `ciCrHigh` REAL NOT NULL,
            `confidence` REAL NOT NULL,
            `qualityScore` REAL NOT NULL,
            `factorsJson` TEXT NOT NULL,
            `mode` TEXT NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_isf_cr_snapshots_ts` ON `isf_cr_snapshots` (`ts`)",
        "CREATE INDEX IF NOT EXISTS `index_isf_cr_snapshots_mode` ON `isf_cr_snapshots` (`mode`)",
        """
        CREATE TABLE IF NOT EXISTS `isf_cr_evidence` (
            `id` TEXT NOT NULL,
            `ts` INTEGER NOT NULL,
            `sampleType` TEXT NOT NULL,
            `hourLocal` INTEGER NOT NULL,
            `dayType` TEXT NOT NULL,
            `value` REAL NOT NULL,
            `weight` REAL NOT NULL,
            `qualityScore` REAL NOT NULL,
            `contextJson` TEXT NOT NULL,
            `windowJson` TEXT NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_isf_cr_evidence_ts` ON `isf_cr_evidence` (`ts`)",
        "CREATE INDEX IF NOT EXISTS `index_isf_cr_evidence_sampleType` ON `isf_cr_evidence` (`sampleType`)",
        "CREATE INDEX IF NOT EXISTS `index_isf_cr_evidence_sampleType_hourLocal` ON `isf_cr_evidence` (`sampleType`, `hourLocal`)",
        """
        CREATE TABLE IF NOT EXISTS `isf_cr_model_state` (
            `id` TEXT NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `hourlyIsfJson` TEXT NOT NULL,
            `hourlyCrJson` TEXT NOT NULL,
            `paramsJson` TEXT NOT NULL,
            `fitMetricsJson` TEXT NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `physio_context_tags` (
            `id` TEXT NOT NULL,
            `tsStart` INTEGER NOT NULL,
            `tsEnd` INTEGER NOT NULL,
            `tagType` TEXT NOT NULL,
            `severity` REAL NOT NULL,
            `source` TEXT NOT NULL,
            `note` TEXT NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_physio_context_tags_tsStart` ON `physio_context_tags` (`tsStart`)",
        "CREATE INDEX IF NOT EXISTS `index_physio_context_tags_tsEnd` ON `physio_context_tags` (`tsEnd`)",
        "CREATE INDEX IF NOT EXISTS `index_physio_context_tags_tagType` ON `physio_context_tags` (`tagType`)"
    )

    val MIGRATION_9_10: Migration = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_9_10_STATEMENTS.forEach(db::execSQL)
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_9_10
    )
}
