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

    internal val MIGRATION_12_13_STATEMENTS = listOf(
        """
        CREATE TABLE IF NOT EXISTS `blood_glucose_checks` (
            `id` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `mmol` REAL NOT NULL,
            `units` TEXT NOT NULL,
            `source` TEXT NOT NULL,
            `note` TEXT NOT NULL,
            `enteredAt` INTEGER NOT NULL,
            `sensorSessionKey` TEXT,
            `lagAlignedTs` INTEGER,
            `matchedRawGlucose` REAL,
            `status` TEXT NOT NULL,
            `reason` TEXT NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_blood_glucose_checks_timestamp` ON `blood_glucose_checks` (`timestamp`)",
        "CREATE INDEX IF NOT EXISTS `index_blood_glucose_checks_sensorSessionKey` ON `blood_glucose_checks` (`sensorSessionKey`)",
        "CREATE INDEX IF NOT EXISTS `index_blood_glucose_checks_status` ON `blood_glucose_checks` (`status`)",
        "CREATE INDEX IF NOT EXISTS `index_blood_glucose_checks_sensorSessionKey_timestamp` ON `blood_glucose_checks` (`sensorSessionKey`, `timestamp`)",
        """
        CREATE TABLE IF NOT EXISTS `glucose_calibration_models` (
            `id` TEXT NOT NULL,
            `sensorSessionKey` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `validFromTs` INTEGER NOT NULL,
            `validToTs` INTEGER NOT NULL,
            `modelType` TEXT NOT NULL,
            `gain` REAL NOT NULL,
            `offsetMmol` REAL NOT NULL,
            `confidence` REAL NOT NULL,
            `checkCount` INTEGER NOT NULL,
            `sensorAgeHours` REAL,
            `lagMinutesAtFit` REAL,
            `status` TEXT NOT NULL,
            `diagnosticsJson` TEXT NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_glucose_calibration_models_sensorSessionKey` ON `glucose_calibration_models` (`sensorSessionKey`)",
        "CREATE INDEX IF NOT EXISTS `index_glucose_calibration_models_status` ON `glucose_calibration_models` (`status`)",
        "CREATE INDEX IF NOT EXISTS `index_glucose_calibration_models_createdAt` ON `glucose_calibration_models` (`createdAt`)",
        "CREATE INDEX IF NOT EXISTS `index_glucose_calibration_models_status_createdAt` ON `glucose_calibration_models` (`status`, `createdAt`)"
    )

    val MIGRATION_12_13: Migration = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_12_13_STATEMENTS.forEach(db::execSQL)
        }
    }

    internal val MIGRATION_13_14_STATEMENTS = listOf(
        "CREATE INDEX IF NOT EXISTS `index_telemetry_samples_timestamp_source_key` ON `telemetry_samples` (`timestamp`, `source`, `key`)"
    )

    val MIGRATION_13_14: Migration = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_13_14_STATEMENTS.forEach(db::execSQL)
        }
    }

    internal val MIGRATION_14_15_STATEMENTS = listOf(
        """
        CREATE TABLE IF NOT EXISTS `target_manager_state` (
            `mode` TEXT NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `acceptedTargetJson` TEXT,
            `lastDecisionFingerprint` TEXT,
            `lastSafetyBypassFingerprint` TEXT,
            `reconciliationStatus` TEXT NOT NULL,
            PRIMARY KEY(`mode`)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `target_manager_decisions` (
            `id` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `mode` TEXT NOT NULL,
            `semanticFingerprint` TEXT NOT NULL,
            `outcome` TEXT NOT NULL,
            `winnerJson` TEXT,
            `commandJson` TEXT,
            `cadenceOutcome` TEXT,
            `cadenceReason` TEXT,
            `lastSentTargetMmol` REAL,
            `lastSentTimestamp` INTEGER,
            `deliveryStatus` TEXT NOT NULL,
            `reasonCodesJson` TEXT NOT NULL,
            `rejectedProposalReasonsJson` TEXT NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_target_manager_decisions_timestamp` " +
            "ON `target_manager_decisions` (`timestamp`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_target_manager_decisions_mode_semanticFingerprint` " +
            "ON `target_manager_decisions` (`mode`, `semanticFingerprint`)",
        "CREATE INDEX IF NOT EXISTS `index_target_manager_decisions_deliveryStatus_timestamp_id` " +
            "ON `target_manager_decisions` (`deliveryStatus`, `timestamp`, `id`)"
    )

    val MIGRATION_14_15: Migration = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_14_15_STATEMENTS.forEach(db::execSQL)
        }
    }

    internal val MIGRATION_15_16_STATEMENTS = listOf(
        """
        CREATE TABLE IF NOT EXISTS `circadian_target_runs` (
            `runId` TEXT NOT NULL,
            `scheduleRevision` INTEGER NOT NULL,
            `localRunDate` TEXT NOT NULL,
            `startedAt` INTEGER NOT NULL,
            `completedAt` INTEGER NOT NULL,
            `lookbackStart` INTEGER NOT NULL,
            `lookbackEnd` INTEGER NOT NULL,
            `status` TEXT NOT NULL,
            `validDays` INTEGER NOT NULL,
            `trustedShare` REAL NOT NULL,
            `lowRiskPassed` INTEGER NOT NULL,
            `reasonCodesJson` TEXT NOT NULL,
            PRIMARY KEY(`runId`)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_circadian_target_runs_completedAt` " +
            "ON `circadian_target_runs` (`completedAt`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_circadian_target_runs_scheduleRevision_localRunDate` " +
            "ON `circadian_target_runs` (`scheduleRevision`, `localRunDate`)",
        """
        CREATE TABLE IF NOT EXISTS `circadian_target_adjustments` (
            `id` TEXT NOT NULL,
            `runId` TEXT NOT NULL,
            `scheduleRevision` INTEGER NOT NULL,
            `dayType` TEXT NOT NULL,
            `hour` INTEGER NOT NULL,
            `manualTargetMmol` REAL NOT NULL,
            `desiredDeltaMmol` REAL NOT NULL,
            `appliedDeltaMmol` REAL NOT NULL,
            `medianMmol` REAL NOT NULL,
            `p25` REAL NOT NULL,
            `p75` REAL NOT NULL,
            `trustedLowCount` INTEGER NOT NULL,
            `sampleCount` INTEGER NOT NULL,
            `activeDays` INTEGER NOT NULL,
            `qualityScore` REAL NOT NULL,
            `sensorTrustedShare` REAL NOT NULL,
            `generatedAt` INTEGER NOT NULL,
            `validUntil` INTEGER NOT NULL,
            `status` TEXT NOT NULL,
            `reasonCodesJson` TEXT NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`runId`) REFERENCES `circadian_target_runs`(`runId`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_circadian_target_adjustments_runId` " +
            "ON `circadian_target_adjustments` (`runId`)",
        "CREATE INDEX IF NOT EXISTS `index_circadian_target_adjustments_scheduleRevision_dayType_hour` " +
            "ON `circadian_target_adjustments` (`scheduleRevision`, `dayType`, `hour`)",
        "CREATE INDEX IF NOT EXISTS `index_circadian_target_adjustments_validUntil` " +
            "ON `circadian_target_adjustments` (`validUntil`)"
    )

    val MIGRATION_15_16: Migration = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_15_16_STATEMENTS.forEach(db::execSQL)
        }
    }

    // Some v16 builds declared these entities without advancing the Room schema.
    // Re-applying their idempotent DDL repairs those databases without touching rows.
    internal val MIGRATION_16_17_STATEMENTS = MIGRATION_12_13_STATEMENTS

    val MIGRATION_16_17: Migration = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_16_17_STATEMENTS.forEach(db::execSQL)
        }
    }

    internal val MIGRATION_17_18_STATEMENTS = listOf(
        "ALTER TABLE `circadian_target_adjustments` ADD COLUMN `lowerTailReplayErrorMmol` REAL",
        "ALTER TABLE `circadian_target_adjustments` ADD COLUMN `stepBaselineTrustedLowCount` INTEGER",
        "ALTER TABLE `circadian_target_adjustments` ADD COLUMN `stepBaselineVariabilityIqrMmol` REAL",
        "ALTER TABLE `circadian_target_adjustments` ADD COLUMN `stepBaselineLowerTailReplayErrorMmol` REAL"
    )

    val MIGRATION_17_18: Migration = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_17_18_STATEMENTS.forEach(db::execSQL)
        }
    }

    internal val MIGRATION_18_19_STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS `clinical_reports` (" +
            "`requestId` TEXT NOT NULL, " +
            "`status` TEXT NOT NULL, " +
            "`requestedFromTs` INTEGER NOT NULL, " +
            "`requestedThroughTs` INTEGER NOT NULL, " +
            "`requestHash` TEXT NOT NULL, " +
            "`coverageJson` TEXT NOT NULL, " +
            "`localSummaryJson` TEXT NOT NULL, " +
            "`responseJson` TEXT, " +
            "`renderedText` TEXT, " +
            "`model` TEXT, " +
            "`schemaVersion` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, " +
            "`completedAt` INTEGER, " +
            "`sanitizedError` TEXT, " +
            "PRIMARY KEY(`requestId`))",
        "CREATE INDEX IF NOT EXISTS `index_clinical_reports_createdAt` " +
            "ON `clinical_reports` (`createdAt`)",
        "CREATE INDEX IF NOT EXISTS `index_clinical_reports_status` " +
            "ON `clinical_reports` (`status`)"
    )

    val MIGRATION_18_19: Migration = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_18_19_STATEMENTS.forEach(db::execSQL)
        }
    }

    internal val MIGRATION_19_20_STATEMENTS = listOf(
        "ALTER TABLE clinical_reports ADD COLUMN provider TEXT NOT NULL DEFAULT 'OPENAI'"
    )

    val MIGRATION_19_20: Migration = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_19_20_STATEMENTS.forEach(db::execSQL)
        }
    }

    internal val MIGRATION_20_21_STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS `eating_window_snapshots` (" +
            "`localCompletedDate` TEXT NOT NULL, " +
            "`generatedAt` INTEGER NOT NULL, " +
            "`sourceFingerprint` TEXT NOT NULL, " +
            "`recentWindowsJson` TEXT NOT NULL, " +
            "`stableWindowsJson` TEXT NOT NULL, " +
            "PRIMARY KEY(`localCompletedDate`))",
        "CREATE INDEX IF NOT EXISTS `index_eating_window_snapshots_generatedAt_localCompletedDate` " +
            "ON `eating_window_snapshots` (`generatedAt`, `localCompletedDate`)"
    )

    val MIGRATION_20_21: Migration = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_20_21_STATEMENTS.forEach(db::execSQL)
        }
    }

    internal val MIGRATION_21_22_STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS `planned_activity_events` (" +
            "`eventId` TEXT NOT NULL, " +
            "`enabled` INTEGER NOT NULL, " +
            "`title` TEXT NOT NULL, " +
            "`activityType` TEXT NOT NULL, " +
            "`intensity` TEXT NOT NULL, " +
            "`localStartIso` TEXT NOT NULL, " +
            "`durationMinutes` INTEGER NOT NULL, " +
            "`timezoneId` TEXT NOT NULL, " +
            "`recurrenceDaysMask` INTEGER NOT NULL, " +
            "`recurrenceEndEpochDay` INTEGER, " +
            "`revision` INTEGER NOT NULL, " +
            "`createdAtMs` INTEGER NOT NULL, " +
            "`updatedAtMs` INTEGER NOT NULL, " +
            "PRIMARY KEY(`eventId`))",
        "CREATE INDEX IF NOT EXISTS `index_planned_activity_events_enabled` " +
            "ON `planned_activity_events` (`enabled`)",
        "CREATE INDEX IF NOT EXISTS `index_planned_activity_events_updatedAtMs` " +
            "ON `planned_activity_events` (`updatedAtMs`)",
        "CREATE TABLE IF NOT EXISTS `meal_profile_overrides` (" +
            "`canonicalTherapyIdentity` TEXT NOT NULL, " +
            "`therapyRevisionHash` TEXT NOT NULL, " +
            "`profile` TEXT NOT NULL, " +
            "`durationMinutes` INTEGER NOT NULL, " +
            "`source` TEXT NOT NULL, " +
            "`revision` INTEGER NOT NULL, " +
            "`updatedAtMs` INTEGER NOT NULL, " +
            "PRIMARY KEY(`canonicalTherapyIdentity`))",
        "CREATE TABLE IF NOT EXISTS `energy_profile_snapshots` (" +
            "`snapshotId` TEXT NOT NULL, " +
            "`schemaVersion` INTEGER NOT NULL, " +
            "`evidenceStartMs` INTEGER NOT NULL, " +
            "`evidenceEndMs` INTEGER NOT NULL, " +
            "`qualityDays` INTEGER NOT NULL, " +
            "`tier` TEXT NOT NULL, " +
            "`foodProfile` TEXT, " +
            "`foodDurationMinutes` INTEGER, " +
            "`activityProfile` TEXT, " +
            "`confidence` REAL NOT NULL, " +
            "`replayPassed` INTEGER NOT NULL, " +
            "`sourceHashSha256` TEXT NOT NULL, " +
            "`calculatedAtMs` INTEGER NOT NULL, " +
            "`stale` INTEGER NOT NULL, " +
            "PRIMARY KEY(`snapshotId`))",
        "CREATE INDEX IF NOT EXISTS `index_energy_profile_snapshots_calculatedAtMs` " +
            "ON `energy_profile_snapshots` (`calculatedAtMs`)"
    )

    val MIGRATION_21_22: Migration = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_21_22_STATEMENTS.forEach(db::execSQL)
        }
    }

    internal val MIGRATION_22_23_STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS `pending_meal_profile_intents` (" +
            "`idempotencyKey` TEXT NOT NULL, " +
            "`copilotNote` TEXT NOT NULL, " +
            "`profile` TEXT NOT NULL, " +
            "`durationMinutes` INTEGER NOT NULL, " +
            "`expectedCarbsGrams` REAL NOT NULL, " +
            "`submittedAtMs` INTEGER NOT NULL, " +
            "`expiresAtMs` INTEGER NOT NULL, " +
            "PRIMARY KEY(`idempotencyKey`))",
        "CREATE INDEX IF NOT EXISTS `index_pending_meal_profile_intents_expiresAtMs` " +
            "ON `pending_meal_profile_intents` (`expiresAtMs`)"
    )

    val MIGRATION_22_23: Migration = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_22_23_STATEMENTS.forEach(db::execSQL)
        }
    }

    internal val MIGRATION_23_24_STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS `meal_energy_overrides` (" +
            "`canonicalTherapyIdentity` TEXT NOT NULL, " +
            "`therapyRevisionHash` TEXT NOT NULL, " +
            "`caloriesKcal` REAL NOT NULL, " +
            "`updatedAtMs` INTEGER NOT NULL, " +
            "PRIMARY KEY(`canonicalTherapyIdentity`))",
        "CREATE INDEX IF NOT EXISTS `index_meal_energy_overrides_updatedAtMs` " +
            "ON `meal_energy_overrides` (`updatedAtMs`)"
    )

    val MIGRATION_23_24: Migration = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_23_24_STATEMENTS.forEach(db::execSQL)
        }
    }

    internal val MIGRATION_24_25_STATEMENTS = listOf(
        "ALTER TABLE `pending_meal_profile_intents` ADD COLUMN `manualMealEnergyKcal` REAL"
    )

    val MIGRATION_24_25: Migration = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_24_25_STATEMENTS.forEach(db::execSQL)
        }
    }

    internal val MIGRATION_25_26_STATEMENTS = listOf(
        "ALTER TABLE `physio_context_tags` ADD COLUMN `subtype` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `physio_context_tags` ADD COLUMN `title` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `physio_context_tags` ADD COLUMN `attributesJson` TEXT NOT NULL DEFAULT '{}'",
        "ALTER TABLE `physio_context_tags` ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE `physio_context_tags` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `physio_context_tags` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'ACTIVE'",
        "CREATE INDEX IF NOT EXISTS `index_physio_context_tags_status_tsStart` " +
            "ON `physio_context_tags` (`status`, `tsStart`)",
        "CREATE INDEX IF NOT EXISTS `index_physio_context_tags_updatedAt` " +
            "ON `physio_context_tags` (`updatedAt`)",
        """
        CREATE TABLE IF NOT EXISTS `sensitivity_runtime_snapshots` (
            `cycleId` TEXT NOT NULL,
            `settingsRevision` INTEGER NOT NULL,
            `generatedAt` INTEGER NOT NULL,
            `isfRequestedSource` TEXT NOT NULL,
            `isfResolvedSource` TEXT NOT NULL,
            `isfRawAaps` REAL,
            `isfRawEvidence` REAL,
            `isfRawCopilot` REAL NOT NULL,
            `isfBlended` REAL,
            `isfEffective` REAL NOT NULL,
            `isfConfidence` REAL NOT NULL,
            `isfFallbackReason` TEXT,
            `crRequestedSource` TEXT NOT NULL,
            `crResolvedSource` TEXT NOT NULL,
            `crRawAaps` REAL,
            `crRawEvidence` REAL,
            `crRawCopilot` REAL NOT NULL,
            `crBlended` REAL,
            `crEffective` REAL NOT NULL,
            `crConfidence` REAL NOT NULL,
            `crFallbackReason` TEXT,
            PRIMARY KEY(`cycleId`)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_sensitivity_runtime_snapshots_generatedAt` " +
            "ON `sensitivity_runtime_snapshots` (`generatedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_sensitivity_runtime_snapshots_settingsRevision_generatedAt` " +
            "ON `sensitivity_runtime_snapshots` (`settingsRevision`, `generatedAt`)",
        """
        CREATE TABLE IF NOT EXISTS `alert_events` (
            `episodeId` TEXT NOT NULL,
            `eventType` TEXT NOT NULL,
            `stage` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `severity` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            `resolvedAt` INTEGER,
            `localSnapshotJson` TEXT NOT NULL,
            `causeCode` TEXT,
            `causeSummary` TEXT,
            `suppressionUntil` INTEGER,
            `lastNotificationAt` INTEGER,
            `revision` INTEGER NOT NULL,
            PRIMARY KEY(`episodeId`)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_alert_events_status_updatedAt` " +
            "ON `alert_events` (`status`, `updatedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_alert_events_eventType_updatedAt` " +
            "ON `alert_events` (`eventType`, `updatedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_alert_events_resolvedAt` " +
            "ON `alert_events` (`resolvedAt`)",
        """
        CREATE TABLE IF NOT EXISTS `alert_delivery_receipts` (
            `receiptId` TEXT NOT NULL,
            `episodeId` TEXT NOT NULL,
            `kind` TEXT NOT NULL,
            `attemptedAt` INTEGER NOT NULL,
            `result` TEXT NOT NULL,
            `suppressionUntil` INTEGER,
            `deliveredAt` INTEGER,
            `sanitizedError` TEXT,
            PRIMARY KEY(`receiptId`),
            FOREIGN KEY(`episodeId`) REFERENCES `alert_events`(`episodeId`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_alert_delivery_receipts_episodeId_kind` " +
            "ON `alert_delivery_receipts` (`episodeId`, `kind`)",
        "CREATE INDEX IF NOT EXISTS `index_alert_delivery_receipts_attemptedAt` " +
            "ON `alert_delivery_receipts` (`attemptedAt`)",
        """
        CREATE TABLE IF NOT EXISTS `alert_ai_analyses` (
            `analysisId` TEXT NOT NULL,
            `episodeId` TEXT NOT NULL,
            `provider` TEXT NOT NULL,
            `model` TEXT NOT NULL,
            `requestHash` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `resultJson` TEXT,
            `requestedAt` INTEGER NOT NULL,
            `completedAt` INTEGER,
            `sanitizedError` TEXT,
            PRIMARY KEY(`analysisId`),
            FOREIGN KEY(`episodeId`) REFERENCES `alert_events`(`episodeId`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_alert_ai_analyses_episodeId` " +
            "ON `alert_ai_analyses` (`episodeId`)",
        "CREATE INDEX IF NOT EXISTS `index_alert_ai_analyses_status_requestedAt` " +
            "ON `alert_ai_analyses` (`status`, `requestedAt`)",
        """
        CREATE TABLE IF NOT EXISTS `context_event_sync` (
            `syncId` TEXT NOT NULL,
            `eventId` TEXT NOT NULL,
            `revision` INTEGER NOT NULL,
            `operation` TEXT NOT NULL,
            `requestHash` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `attemptedAt` INTEGER NOT NULL,
            `completedAt` INTEGER,
            `sanitizedError` TEXT,
            PRIMARY KEY(`syncId`)
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_context_event_sync_eventId_revision_operation` " +
            "ON `context_event_sync` (`eventId`, `revision`, `operation`)",
        "CREATE INDEX IF NOT EXISTS `index_context_event_sync_status_attemptedAt` " +
            "ON `context_event_sync` (`status`, `attemptedAt`)"
    )

    val MIGRATION_25_26: Migration = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_25_26_STATEMENTS.forEach(db::execSQL)
        }
    }

    val MIGRATION_26_27: Migration = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `pending_meal_profile_intents` ADD COLUMN `portion` TEXT")
            db.execSQL("ALTER TABLE `pending_meal_profile_intents` ADD COLUMN `portionProvenance` TEXT")
            db.execSQL("ALTER TABLE `meal_profile_overrides` ADD COLUMN `portion` TEXT")
            db.execSQL("ALTER TABLE `meal_profile_overrides` ADD COLUMN `portionProvenance` TEXT")
            db.execSQL("ALTER TABLE `meal_profile_overrides` ADD COLUMN `confirmedCarbsGrams` REAL")
        }
    }

    val MIGRATION_27_28: Migration = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `meal_notification_claims` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `episodeId` TEXT NOT NULL, " +
                "`bootId` TEXT NOT NULL, `elapsedAtMs` INTEGER NOT NULL, `wallAtMs` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `meal_notification_claim_aliases` (" +
                "`alias` TEXT NOT NULL PRIMARY KEY, `claimId` INTEGER NOT NULL)")
        }
    }

    val MIGRATION_28_29: Migration = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `meal_states` (" +
                "`episodeId` TEXT NOT NULL PRIMARY KEY, `recordedAtMs` INTEGER NOT NULL, " +
                "`minimumGrams` REAL NOT NULL, `maximumGrams` REAL NOT NULL, `storageRevision` INTEGER NOT NULL, " +
                "`canonicalMealId` TEXT, `aapsRevision` INTEGER, `aapsRecordedAtMs` INTEGER, `aapsGrams` REAL, " +
                "`aapsDeleted` INTEGER, `modelVersion` TEXT, `beliefRevision` INTEGER, " +
                "`lastSampleAtMs` INTEGER, `lastSampleId` TEXT, `runtimeIdentity` TEXT)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_meal_states_canonicalMealId` ON `meal_states` (`canonicalMealId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `meal_state_scenarios` (" +
                "`episodeId` TEXT NOT NULL, `scenarioId` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, `earliestStartMs` INTEGER, `latestStartMs` INTEGER, " +
                "`minimumGrams` REAL NOT NULL, `maximumGrams` REAL NOT NULL, `probability` REAL NOT NULL, " +
                "`canonicalMealId` TEXT, PRIMARY KEY (`episodeId`, `scenarioId`), " +
                "FOREIGN KEY (`episodeId`) REFERENCES `meal_states` (`episodeId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_meal_state_scenarios_episodeId` ON `meal_state_scenarios` (`episodeId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `meal_state_absorption` (" +
                "`episodeId` TEXT NOT NULL, `scenarioId` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                "`profile` TEXT NOT NULL, `durationMinutes` INTEGER NOT NULL, `probability` REAL NOT NULL, " +
                "PRIMARY KEY (`episodeId`, `scenarioId`, `position`), " +
                "FOREIGN KEY (`episodeId`, `scenarioId`) REFERENCES `meal_state_scenarios` (`episodeId`, `scenarioId`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_meal_state_absorption_episodeId_scenarioId` " +
                "ON `meal_state_absorption` (`episodeId`, `scenarioId`)")
        }
    }

    val MIGRATION_29_30: Migration = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `meal_state_receipts` (" +
                "`canonicalId` TEXT NOT NULL PRIMARY KEY, `inputId` TEXT, `revision` INTEGER NOT NULL, " +
                "`recordedAtMs` INTEGER NOT NULL, `grams` REAL NOT NULL, `deleted` INTEGER NOT NULL, " +
                "`conflicted` INTEGER NOT NULL, `appliedRevision` INTEGER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_meal_state_receipts_inputId` ON `meal_state_receipts` (`inputId`)")
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18,
        MIGRATION_18_19,
        MIGRATION_19_20,
        MIGRATION_20_21,
        MIGRATION_21_22,
        MIGRATION_22_23,
        MIGRATION_23_24,
        MIGRATION_24_25,
        MIGRATION_25_26,
        MIGRATION_26_27,
        MIGRATION_27_28,
        MIGRATION_28_29,
        MIGRATION_29_30
    )
}
