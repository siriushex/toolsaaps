package io.aaps.copilot.config

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.predict.UamExportMode
import org.junit.Test

class UamExportSettingsPolicyTest {

    @Test
    fun legacyConfirmedOnlyLiveExportBecomesBoundedIncrementalDryRun() {
        val decision = decideBoundedUamExportV2Migration(
            enabled = true,
            mode = UamExportMode.CONFIRMED_ONLY,
            dryRun = false,
            maxBackdateMinutes = 180
        )

        assertThat(decision).isEqualTo(
            UamExportMigrationDecision(
                enabled = true,
                mode = UamExportMode.INCREMENTAL,
                dryRun = true,
                maxBackdateMinutes = 5,
                changed = true
            )
        )
    }

    @Test
    fun offModeRemainsOffWithoutChangingExportSettings() {
        val decision = decideBoundedUamExportV2Migration(
            enabled = true,
            mode = UamExportMode.OFF,
            dryRun = false,
            maxBackdateMinutes = 180
        )

        assertThat(decision).isEqualTo(
            UamExportMigrationDecision(
                enabled = true,
                mode = UamExportMode.OFF,
                dryRun = false,
                maxBackdateMinutes = 180,
                changed = false
            )
        )
    }

    @Test
    fun alreadyMigratedSettingsAreIdempotent() {
        val decision = decideBoundedUamExportV2Migration(
            enabled = true,
            mode = UamExportMode.INCREMENTAL,
            dryRun = true,
            maxBackdateMinutes = 5
        )

        assertThat(decision).isEqualTo(
            UamExportMigrationDecision(
                enabled = true,
                mode = UamExportMode.INCREMENTAL,
                dryRun = true,
                maxBackdateMinutes = 5,
                changed = false
            )
        )
    }

    @Test
    fun everyEnabledNonOffMigrationEndsInObserveAndNeverAuto() {
        UamExportMode.entries
            .filterNot { it == UamExportMode.OFF }
            .forEach { mode ->
                listOf(false, true).forEach { dryRun ->
                    val decision = decideBoundedUamExportV2Migration(
                        enabled = true,
                        mode = mode,
                        dryRun = dryRun,
                        maxBackdateMinutes = 180
                    )

                    assertThat(decision.enabled).isTrue()
                    assertThat(decision.mode).isEqualTo(UamExportMode.INCREMENTAL)
                    assertThat(decision.dryRun).isTrue()
                }
            }
    }

    @Test
    fun disabledExportRemainsDisabledForEveryInputMode() {
        UamExportMode.entries.forEach { mode ->
            val decision = decideBoundedUamExportV2Migration(
                enabled = false,
                mode = mode,
                dryRun = false,
                maxBackdateMinutes = 180
            )

            assertThat(decision).isEqualTo(
                UamExportMigrationDecision(
                    enabled = false,
                    mode = mode,
                    dryRun = false,
                    maxBackdateMinutes = 180,
                    changed = false
                )
            )
        }
    }
}
