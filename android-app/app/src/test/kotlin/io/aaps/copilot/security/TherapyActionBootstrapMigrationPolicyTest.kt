package io.aaps.copilot.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TherapyActionBootstrapMigrationPolicyTest {

    @Test
    fun trueInPlaceUpdateWithStableRecentAutomaticHistoryCanAutoArm() {
        val firstInstall = 1_700_000_000_000L
        val lastUpdate = firstInstall + 30L * DAY_MS

        assertThat(
            TherapyActionBootstrapMigrationPolicy.shouldAutoArm(
                TherapyActionBootstrapMigrationInput(
                    alreadyEvaluatedForInstall = false,
                    firstInstallTimeMs = firstInstall,
                    lastUpdateTimeMs = lastUpdate,
                    sentCommandCount = 20,
                    earliestSentCommandMs = firstInstall + DAY_MS,
                    latestSentCommandMs = lastUpdate - DAY_MS
                )
            )
        ).isTrue()
    }

    @Test
    fun freshInstallRestoredDatabaseAndSingleIncidentCommandRemainBlocked() {
        val installedAt = 1_800_000_000_000L
        val restoredHistory = TherapyActionBootstrapMigrationInput(
            alreadyEvaluatedForInstall = false,
            firstInstallTimeMs = installedAt,
            lastUpdateTimeMs = installedAt,
            sentCommandCount = 20,
            earliestSentCommandMs = installedAt - 30L * DAY_MS,
            latestSentCommandMs = installedAt - DAY_MS
        )
        val singleIncident = restoredHistory.copy(
            lastUpdateTimeMs = installedAt + DAY_MS,
            sentCommandCount = 1,
            earliestSentCommandMs = installedAt + 1_000L,
            latestSentCommandMs = installedAt + 1_000L
        )

        assertThat(TherapyActionBootstrapMigrationPolicy.shouldAutoArm(restoredHistory)).isFalse()
        assertThat(TherapyActionBootstrapMigrationPolicy.shouldAutoArm(singleIncident)).isFalse()
    }

    @Test
    fun staleHistoryOrExplicitDecisionRemainBlocked() {
        val firstInstall = 1_700_000_000_000L
        val lastUpdate = firstInstall + 30L * DAY_MS
        val base = TherapyActionBootstrapMigrationInput(
            alreadyEvaluatedForInstall = false,
            firstInstallTimeMs = firstInstall,
            lastUpdateTimeMs = lastUpdate,
            sentCommandCount = 10,
            earliestSentCommandMs = firstInstall + DAY_MS,
            latestSentCommandMs = lastUpdate - 8L * DAY_MS
        )

        assertThat(TherapyActionBootstrapMigrationPolicy.shouldAutoArm(base)).isFalse()
        assertThat(
            TherapyActionBootstrapMigrationPolicy.shouldAutoArm(
                base.copy(
                    alreadyEvaluatedForInstall = true,
                    latestSentCommandMs = lastUpdate - DAY_MS
                )
            )
        ).isFalse()
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60_000L
    }
}
