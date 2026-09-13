package io.aaps.copilot.ui.foundation

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class AlertsArchitectureTest {
    @Test
    fun alertsUiHasEnglishRussianResourcesAndNotebookPrecedesBell() {
        val root = source("src/main/kotlin/io/aaps/copilot/ui/foundation/CopilotFoundationRoot.kt")
        val actions = root.substringAfter("internal fun EventAlertActions(")
            .substringBefore("private const val MAX_VISIBLE_EVENT_BADGE_COUNT")
        val required = listOf(
            "nav_alerts",
            "alerts_open_history",
            "alerts_muted_until",
            "alerts_not_shown_off",
            "alerts_history_30d_title",
            "alerts_local_cause_title",
            "alerts_local_advice_title",
            "alerts_evidence_current_glucose",
            "alerts_evidence_forecast_5",
            "alerts_evidence_forecast_30",
            "alerts_evidence_forecast_60",
            "alerts_ai_unavailable"
        )

        assertThat(actions.indexOf("eventsNotebookAction"))
            .isLessThan(actions.indexOf("glucoseAlertBellAction"))
        assertThat(actions).contains("glucoseAlertMute30Action")
        assertThat(actions).contains("glucoseAlertMute60Action")
        required.forEach { name ->
            assertThat(source("src/main/res/values/strings.xml")).contains("name=\"$name\"")
            assertThat(source("src/main/res/values-ru/strings.xml")).contains("name=\"$name\"")
        }
    }

    @Test
    fun alertsReadPathIntroducesNoNetworkWorkerOrSchemaChange() {
        val repository = source("src/main/kotlin/io/aaps/copilot/data/repository/AlertsRepository.kt")
        val database = source("src/main/kotlin/io/aaps/copilot/data/local/CopilotDatabase.kt")

        assertThat(repository).doesNotContain("okhttp")
        assertThat(repository).doesNotContain("retrofit")
        assertThat(repository).doesNotContain("WorkManager")
        assertThat(database).contains("version = 26")
    }

    @Test
    fun notificationUsesNonExportedEntryAndMainActivityNeverReadsEpisodeExtras() {
        val manifest = source("src/main/AndroidManifest.xml")
        val activity = source("src/main/kotlin/io/aaps/copilot/MainActivity.kt")
        val entry = source("src/main/kotlin/io/aaps/copilot/AlertNotificationEntryActivity.kt")
        val root = source("src/main/kotlin/io/aaps/copilot/ui/foundation/CopilotFoundationRoot.kt")
        val entryDeclaration = manifest.substringAfter("android:name=\".AlertNotificationEntryActivity\"")
            .substringBefore("/>")
        assertThat(manifest).contains("android:name=\".MainActivity\"")
        assertThat(manifest).contains("android:name=\".AlertNotificationEntryActivity\"")
        assertThat(entryDeclaration).contains("android:exported=\"false\"")
        assertThat(entryDeclaration).contains("android:noHistory=\"true\"")
        assertThat(entryDeclaration).contains("@android:style/Theme.NoDisplay")
        assertThat(manifest).doesNotContain("android:launchMode=\"singleTop\"")
        assertThat(activity).doesNotContain("EXTRA_OPEN_ALERT_EPISODE_ID")
        assertThat(activity).doesNotContain("ACTION_OPEN_ALERTS")
        assertThat(entry).contains("enqueueAlertNavigation(candidate)")
        assertThat(root).contains("alertNavigationRequests.filterNotNull().collect")
        assertThat(root).contains("try {")
        assertThat(root).contains("finally {")
        assertThat(root).contains("acknowledgeAlertNavigation(request.token)")
        assertThat(root).contains("navController.navigate(Destinations.Alerts.route)")
    }

    @Test
    fun ordinaryAlertsNavigationRefreshesTheExactWindowOnScreenEntry() {
        val root = source("src/main/kotlin/io/aaps/copilot/ui/foundation/CopilotFoundationRoot.kt")
        val alertsDestination = root.substringAfter("composable(Destinations.Alerts.route)")
            .substringBefore("composable(Destinations.Analytics.route)")

        assertThat(alertsDestination).contains("LaunchedEffect(Unit) { viewModel.refreshAlertsWindow() }")
    }

    @Test
    fun topBarAndAlertsScreenTreatTheDeliveredMuteStateAsAuthoritative() {
        val root = source("src/main/kotlin/io/aaps/copilot/ui/foundation/CopilotFoundationRoot.kt")
        val screen = source("src/main/kotlin/io/aaps/copilot/ui/foundation/screens/AlertsScreen.kt")

        assertThat(root).contains("isAuthoritativelyMuted(")
        assertThat(screen).contains("val muted = isAuthoritativelyMuted(mutedUntilTs)")
        assertThat(screen).doesNotContain("mutedUntilTs > System.currentTimeMillis()")
    }

    private fun source(path: String): String = File(path).readText()
}
