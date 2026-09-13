package io.aaps.copilot.security

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class TherapyActionBootstrapArchitectureTest {

    @Test
    fun foregroundStartupWaitsForResumeAndRetriesAfterUnlock() {
        val main = source("src/main/kotlin/io/aaps/copilot/MainActivity.kt")
        val resume = main.substringAfter("override fun onResume() {", "").substringBefore("\n    }")
        assertThat(resume).contains("enqueuePostDrawStartup()")
        val preparation = main.substringAfter("private fun enqueuePostDrawStartup()")
            .substringBefore("private fun continueStartupAfterTherapyBootstrap()")
        val continuation = main.substringAfter("private fun continueStartupAfterTherapyBootstrap()")
            .substringBefore("private fun showTherapyActionBootstrapDialog")
        assertThat(preparation).contains("Lifecycle.State.RESUMED")
        assertThat(continuation).contains("Lifecycle.State.RESUMED")
    }

    @Test
    fun onlyForegroundActivityCanArmTherapyActions() {
        val mainActivity = source("src/main/kotlin/io/aaps/copilot/MainActivity.kt")
        val backgroundSources = listOf(
            source("src/main/kotlin/io/aaps/copilot/CopilotApp.kt"),
            source("src/main/kotlin/io/aaps/copilot/receiver/BootCompletedReceiver.kt"),
            source("src/main/kotlin/io/aaps/copilot/receiver/LocalDataBroadcastReceiver.kt")
        )

        assertThat(mainActivity).contains("setTherapyActionsArmed(true)")
        assertThat(mainActivity).contains("continueStartupAfterTherapyBootstrap()")
        assertThat(source("src/main/kotlin/io/aaps/copilot/service/AppContainer.kt"))
            .contains("TherapyActionBootstrapMigrationPolicy.shouldAutoArm")
        backgroundSources.forEach { background ->
            assertThat(background).doesNotContain("setTherapyActionsArmed(true)")
        }
    }

    @Test
    fun initialBootstrapDialogCannotArmWithoutASecondConfirmation() {
        val mainActivity = source("src/main/kotlin/io/aaps/copilot/MainActivity.kt")
        val initialDialog = mainActivity
            .substringAfter("private fun showTherapyActionBootstrapDialog")
            .substringBefore("private fun showTherapyActionBootstrapFinalConfirmation")
        val finalConfirmation = mainActivity
            .substringAfter("private fun showTherapyActionBootstrapFinalConfirmation")
            .substringBefore("private fun continuePermissionStartup")

        assertThat(initialDialog).contains("showTherapyActionBootstrapFinalConfirmation(app)")
        assertThat(initialDialog).doesNotContain("setTherapyActionsArmed(true)")
        assertThat(finalConfirmation).contains("setTherapyActionsArmed(true)")
        assertThat(finalConfirmation).contains("setTherapyActionsArmed(false)")
    }

    @Test
    fun inPlaceMigrationRunsSeriallyBeforeForegroundBootstrapDecision() {
        val mainActivity = source("src/main/kotlin/io/aaps/copilot/MainActivity.kt")
        val startup = mainActivity
            .substringAfter("private fun enqueuePostDrawStartup")
            .substringBefore("private fun continueStartupAfterTherapyBootstrap")
        val migrationCallSites = productionKotlinSources()
            .filterNot { (_, source) ->
                source.contains(
                    "fun migrateTherapyActionBootstrapForInPlaceUpdate()"
                )
            }
            .filter { (_, source) ->
                source.contains("migrateTherapyActionBootstrapForInPlaceUpdate()")
            }
            .map { (path, _) -> path }

        assertThat(startup.indexOf("migrateTherapyActionBootstrapForInPlaceUpdate()"))
            .isAtLeast(0)
        assertThat(startup.indexOf("migrateTherapyActionBootstrapForInPlaceUpdate()"))
            .isLessThan(startup.indexOf("settingsStore.settings.first()"))
        assertThat(migrationCallSites)
            .containsExactly("src/main/kotlin/io/aaps/copilot/MainActivity.kt")
    }

    @Test
    fun automationAndTransportBothCheckTheBootstrapLatch() {
        val automation = source(
            "src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt"
        )
        val transport = source(
            "src/main/kotlin/io/aaps/copilot/data/repository/NightscoutActionRepository.kt"
        )

        assertThat(automation).contains("therapyActionsArmed")
        assertThat(automation).contains("automation_cycle_skipped")
        assertThat(transport).contains("therapyActionBootstrapBlockReasonStatic")
        assertThat(transport).contains("therapy_actions_not_armed")
    }

    @Test
    fun backgroundEntryPointsRemainInertUntilTherapyActionsAreArmed() {
        val guardedSources = listOf(
            source("src/main/kotlin/io/aaps/copilot/receiver/LocalDataBroadcastReceiver.kt"),
            source("src/main/kotlin/io/aaps/copilot/receiver/BootCompletedReceiver.kt"),
            source("src/main/kotlin/io/aaps/copilot/scheduler/SyncAndAutomateWorker.kt"),
            source("src/main/kotlin/io/aaps/copilot/scheduler/CircadianTargetWorker.kt"),
            source("src/main/kotlin/io/aaps/copilot/scheduler/PowerSaveResumeWorker.kt"),
            source("src/main/kotlin/io/aaps/copilot/service/LocalNightscoutForegroundService.kt"),
            source("src/main/kotlin/io/aaps/copilot/service/AppContainer.kt")
        )

        guardedSources.forEach { source ->
            assertThat(source).contains("therapyActionsArmed")
        }
        assertThat(source("src/main/kotlin/io/aaps/copilot/scheduler/WorkScheduler.kt"))
            .contains("TherapyActionRuntimeState.isArmed()")
    }

    @Test
    fun armedPreferenceIsBoundToNoBackupInstallIdentity() {
        val settingsStore = source("src/main/kotlin/io/aaps/copilot/config/AppSettingsStore.kt")

        assertThat(settingsStore).contains("context.noBackupFilesDir")
        assertThat(settingsStore).contains("KEY_THERAPY_ACTIONS_ARMED_INSTALL_ID")
        assertThat(settingsStore).doesNotContain(
            "prefs[KEY_THERAPY_ACTIONS_ARMED] ?: false"
        )
    }

    @Test
    fun directTreatmentRelaysUseBarrierAndSelfTestIsReadOnly() {
        val localServer = source(
            "src/main/kotlin/io/aaps/copilot/service/LocalNightscoutServer.kt"
        )
        val mainViewModel = source(
            "src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt"
        )
        val selfTest = mainViewModel.substringAfter("fun runNightscoutSelfTest()")
            .substringBefore("private fun isLoopbackUrl")

        assertThat(localServer).contains("TherapyActionTransportGate.withArmedLease")
        assertThat(localServer).contains("settingsStore.settings.first().therapyActionsArmed")
        assertThat(localServer.indexOf("if (!relayTreatmentsToAaps(responses))"))
            .isLessThan(localServer.indexOf("db.therapyDao().upsertAll(therapyRows)"))
        assertThat(localServer).contains("copilot_treatment_batch_not_supported")
        assertThat(
            source("src/main/kotlin/io/aaps/copilot/data/repository/NightscoutActionRepository.kt")
        ).contains("if (isLocalNightscoutTarget(targetUrl")
        assertThat(selfTest).doesNotContain("postTreatment(")
        assertThat(selfTest).doesNotContain("postSgvEntries(")
        assertThat(selfTest).doesNotContain("postDeviceStatus(")
    }

    private fun source(relativePath: String): String {
        val direct = File(relativePath)
        return (if (direct.exists()) direct else File("app/$relativePath")).readText()
    }

    private fun productionKotlinSources(): List<Pair<String, String>> {
        val root = File("src/main/kotlin").takeIf(File::exists)
            ?: File("app/src/main/kotlin")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { file ->
                "src/main/kotlin/${file.relativeTo(root).invariantSeparatorsPath}" to file.readText()
            }
            .toList()
    }
}
