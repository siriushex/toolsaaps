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

    internal val MIGRATION_10_11_STATEMENTS = listOf(
        """
        CREATE TABLE IF NOT EXISTS `circadian_slot_stats` (
            `dayType` TEXT NOT NULL,
            `windowDays` INTEGER NOT NULL,
            `slotIndex` INTEGER NOT NULL,
            `sampleCount` INTEGER NOT NULL,
            `activeDays` INTEGER NOT NULL,
            `medianBg` REAL NOT NULL,
            `p10` REAL NOT NULL,
            `p25` REAL NOT NULL,
            `p75` REAL NOT NULL,
            `p90` REAL NOT NULL,
            `pLow` REAL NOT NULL,
            `pHigh` REAL NOT NULL,
            `pInRange` REAL NOT NULL,
            `fastRiseRate` REAL NOT NULL,
            `fastDropRate` REAL NOT NULL,
            `meanCob` REAL,
            `meanIob` REAL,
            `meanUam` REAL,
            `meanActivity` REAL,
            `confidence` REAL NOT NULL,
            `qualityScore` REAL NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`dayType`, `windowDays`, `slotIndex`)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_circadian_slot_stats_dayType` ON `circadian_slot_stats` (`dayType`)",
        "CREATE INDEX IF NOT EXISTS `index_circadian_slot_stats_windowDays` ON `circadian_slot_stats` (`windowDays`)",
        "CREATE INDEX IF NOT EXISTS `index_circadian_slot_stats_slotIndex` ON `circadian_slot_stats` (`slotIndex`)",
        "CREATE INDEX IF NOT EXISTS `index_circadian_slot_stats_dayType_windowDays` ON `circadian_slot_stats` (`dayType`, `windowDays`)",
        """
        CREATE TABLE IF NOT EXISTS `circadian_transition_stats` (
            `dayType` TEXT NOT NULL,
            `windowDays` INTEGER NOT NULL,
            `slotIndex` INTEGER NOT NULL,
            `horizonMinutes` INTEGER NOT NULL,
            `sampleCount` INTEGER NOT NULL,
            `deltaMedian` REAL NOT NULL,
            `deltaP25` REAL NOT NULL,
            `deltaP75` REAL NOT NULL,
            `residualBiasMmol` REAL NOT NULL,
            `confidence` REAL NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`dayType`, `windowDays`, `slotIndex`, `horizonMinutes`)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_circadian_transition_stats_dayType` ON `circadian_transition_stats` (`dayType`)",
        "CREATE INDEX IF NOT EXISTS `index_circadian_transition_stats_windowDays` ON `circadian_transition_stats` (`windowDays`)",
        "CREATE INDEX IF NOT EXISTS `index_circadian_transition_stats_slotIndex` ON `circadian_transition_stats` (`slotIndex`)",
        "CREATE INDEX IF NOT EXISTS `index_circadian_transition_stats_horizonMinutes` ON `circadian_transition_stats` (`horizonMinutes`)",
        "CREATE INDEX IF NOT EXISTS `index_circadian_transition_stats_dayType_windowDays_horizonMinutes` ON `circadian_transition_stats` (`dayType`, `windowDays`, `horizonMinutes`)",
        """
        CREATE TABLE IF NOT EXISTS `circadian_pattern_snapshots` (
            `dayType` TEXT NOT NULL,
            `segmentSource` TEXT NOT NULL,
            `stableWindowDays` INTEGER NOT NULL,
            `recencyWindowDays` INTEGER NOT NULL,
            `recencyWeight` REAL NOT NULL,
            `coverageDays` INTEGER NOT NULL,
            `sampleCount` INTEGER NOT NULL,
            `segmentFallback` INTEGER NOT NULL,
            `fallbackReason` TEXT,
            `confidence` REAL NOT NULL,
            `qualityScore` REAL NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`dayType`)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_circadian_pattern_snapshots_segmentSource` ON `circadian_pattern_snapshots` (`segmentSource`)",
        "CREATE INDEX IF NOT EXISTS `index_circadian_pattern_snapshots_stableWindowDays` ON `circadian_pattern_snapshots` (`stableWindowDays`)",
        "CREATE INDEX IF NOT EXISTS `index_circadian_pattern_snapshots_updatedAt` ON `circadian_pattern_snapshots` (`updatedAt`)"
    )

    val MIGRATION_10_11: Migration = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_10_11_STATEMENTS.forEach(db::execSQL)
        }
    }

    internal val MIGRATION_11_12_STATEMENTS = listOf(
        """
        CREATE TABLE IF NOT EXISTS `circadian_replay_slot_stats` (
            `dayType` TEXT NOT NULL,
            `windowDays` INTEGER NOT NULL,
            `slotIndex` INTEGER NOT NULL,
            `horizonMinutes` INTEGER NOT NULL,
            `sampleCount` INTEGER NOT NULL,
            `coverageDays` INTEGER NOT NULL,
            `maeBaseline` REAL NOT NULL,
            `maeCircadian` REAL NOT NULL,
            `maeImprovementMmol` REAL NOT NULL,
            `medianSignedErrorBaseline` REAL NOT NULL,
            `medianSignedErrorCircadian` REAL NOT NULL,
            `winRate` REAL NOT NULL,
            `qualityScore` REAL NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`dayType`, `windowDays`, `slotIndex`, `horizonMinutes`)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_circadian_replay_slot_stats_dayType` ON `circadian_replay_slot_stats` (`dayType`)",
        "CREATE INDEX IF NOT EXISTS `index_circadian_replay_slot_stats_windowDays` ON `circadian_replay_slot_stats` (`windowDays`)",
        "CREATE INDEX IF NOT EXISTS `index_circadian_replay_slot_stats_slotIndex` ON `circadian_replay_slot_stats` (`slotIndex`)",
        "CREATE INDEX IF NOT EXISTS `index_circadian_replay_slot_stats_horizonMinutes` ON `circadian_replay_slot_stats` (`horizonMinutes`)",
        "CREATE INDEX IF NOT EXISTS `index_circadian_replay_slot_stats_dayType_windowDays` ON `circadian_replay_slot_stats` (`dayType`, `windowDays`)",
        "CREATE INDEX IF NOT EXISTS `index_circadian_replay_slot_stats_dayType_slotIndex_horizonMinutes` ON `circadian_replay_slot_stats` (`dayType`, `slotIndex`, `horizonMinutes`)"
    )

    val MIGRATION_11_12: Migration = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_11_12_STATEMENTS.forEach(db::execSQL)
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12
    )
}
