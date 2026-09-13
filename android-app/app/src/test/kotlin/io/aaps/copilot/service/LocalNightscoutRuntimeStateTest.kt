package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LocalNightscoutRuntimeStateTest {

    @Test
    fun exactHandshakeThenAuthenticatedSocketIsRequiredForReady() {
        val state = MutableLocalNightscoutRuntimeState()

        state.starting(17_580)
        assertThat(state.value.status).isEqualTo(LocalNightscoutRuntimeStatus.STARTING)
        assertThat(state.value.port).isEqualTo(17_580)

        state.setup(17_580, "AA:BB", LocalNightscoutRuntimeReason.AWAITING_AAPS_AUTH)
        assertThat(state.value.status).isEqualTo(LocalNightscoutRuntimeStatus.SETUP)

        state.authenticatedSocketObserved(17_580, "AA:BB")
        assertThat(state.value.status).isEqualTo(LocalNightscoutRuntimeStatus.READY)
        assertThat(state.value.reason).isNull()
    }

    @Test
    fun failureReasonIsBoundedAndNeverChangesConfiguredPort() {
        val state = MutableLocalNightscoutRuntimeState()

        state.failed(17_580, "X".repeat(500))

        assertThat(state.value.status).isEqualTo(LocalNightscoutRuntimeStatus.FAILED)
        assertThat(state.value.port).isEqualTo(17_580)
        assertThat(state.value.reason).hasLength(80)
    }

    @Test
    fun resetCancelMutatesNothingAndConfirmedFailureStaysDisabledAndFailed() = runTest {
        val calls = mutableListOf<String>()
        val state = MutableLocalNightscoutRuntimeState()
        state.authenticatedSocketObserved(17_580, "AA:BB")
        var failReset = false
        val coordinator = LocalNightscoutIdentityResetCoordinator(
            stopServer = { calls += "stop" },
            disableIntegration = { calls += "disable" },
            resetIdentity = {
                calls += "reset"
                if (failReset) error("reset_failed")
            },
            runtimeState = state
        )

        assertThat(coordinator.reset(confirmed = false))
            .isEqualTo(LocalNightscoutIdentityResetResult.CANCELLED)
        assertThat(calls).isEmpty()
        assertThat(state.value.status).isEqualTo(LocalNightscoutRuntimeStatus.READY)

        assertThat(coordinator.reset(confirmed = true))
            .isEqualTo(LocalNightscoutIdentityResetResult.RESET)
        assertThat(calls).containsExactly("stop", "disable", "reset").inOrder()
        assertThat(state.value.status).isEqualTo(LocalNightscoutRuntimeStatus.SETUP)
        assertThat(state.value.reason)
            .isEqualTo(LocalNightscoutRuntimeReason.IDENTITY_RESET_REQUIRED.name)

        calls.clear()
        state.authenticatedSocketObserved(17_580, "CC:DD")
        failReset = true
        assertThat(coordinator.reset(confirmed = true))
            .isEqualTo(LocalNightscoutIdentityResetResult.FAILED)
        assertThat(calls).containsExactly("stop", "disable", "reset").inOrder()
        assertThat(state.value.status).isEqualTo(LocalNightscoutRuntimeStatus.FAILED)
        assertThat(state.value.reason)
            .isEqualTo(LocalNightscoutRuntimeReason.IDENTITY_RESET_FAILED.name)
    }

    @Test
    fun confirmedResetPropagatesCancellationAndNeverReportsCancelled() = runTest {
        val calls = mutableListOf<String>()
        val state = MutableLocalNightscoutRuntimeState()
        state.authenticatedSocketObserved(17_580, "AA:BB")
        val coordinator = LocalNightscoutIdentityResetCoordinator(
            stopServer = { calls += "stop" },
            disableIntegration = {
                calls += "disable"
                throw CancellationException("cancelled_during_reset")
            },
            resetIdentity = { calls += "reset" },
            runtimeState = state
        )

        val failure = runCatching { coordinator.reset(confirmed = true) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(calls).containsExactly("stop", "disable").inOrder()
        assertThat(state.value.status).isNotEqualTo(LocalNightscoutRuntimeStatus.READY)
    }

    @Test
    fun parentCancellationAfterMutationStartsCannotInterruptResetSequence() = runTest {
        val calls = mutableListOf<String>()
        val state = MutableLocalNightscoutRuntimeState()
        state.authenticatedSocketObserved(17_580, "AA:BB")
        val mutationStarted = CompletableDeferred<Unit>()
        val allowDisableToFinish = CompletableDeferred<Unit>()
        val coordinator = LocalNightscoutIdentityResetCoordinator(
            stopServer = { calls += "stop" },
            disableIntegration = {
                calls += "disable"
                mutationStarted.complete(Unit)
                allowDisableToFinish.await()
            },
            resetIdentity = { calls += "reset" },
            runtimeState = state
        )
        val job = launch { coordinator.reset(confirmed = true) }
        mutationStarted.await()

        job.cancel()
        allowDisableToFinish.complete(Unit)
        job.join()

        assertThat(calls).containsExactly("stop", "disable", "reset").inOrder()
        assertThat(state.value.status).isEqualTo(LocalNightscoutRuntimeStatus.SETUP)
        assertThat(state.value.reason)
            .isEqualTo(LocalNightscoutRuntimeReason.IDENTITY_RESET_REQUIRED.name)
    }

    @Test
    fun legacyMigrationGateBlocksEnableUntilExplicitAcknowledgment() {
        assertThat(LocalNightscoutUpgradeMigrationGate.LEGACY_CA_SHA256)
            .isEqualTo(
                "C9:EF:07:C9:05:B6:56:23:0D:50:CB:F6:1D:85:A3:C3:" +
                    "60:6B:B7:B4:8F:9C:E9:00:2B:AD:96:D3:BE:FF:71:41"
            )
        assertThat(
            LocalNightscoutUpgradeMigrationGate.canEnable(
                requestedEnabled = true,
                migrationAcknowledged = false
            )
        ).isFalse()
        assertThat(
            LocalNightscoutUpgradeMigrationGate.canEnable(
                requestedEnabled = true,
                migrationAcknowledged = true
            )
        ).isTrue()
    }

    @Test
    fun mainViewModelDoesNotStopServiceForCancelledReset() {
        val source = File(
            requireNotNull(System.getProperty("user.dir")),
            "src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt"
        ).readText()
        val resetBody = source.substringAfter("fun resetLocalNightscoutIdentity(confirmed: Boolean)")
            .substringBefore("fun installLocalNightscoutCertificate()")

        assertThat(resetBody).contains(
            "if (result != LocalNightscoutIdentityResetResult.CANCELLED)"
        )
        assertThat(resetBody.indexOf("LocalNightscoutServiceController.stop"))
            .isGreaterThan(resetBody.indexOf("LocalNightscoutIdentityResetResult.CANCELLED"))
    }

    @Test
    fun uiNeverTreatsConfigurationToggleAsReadyRuntime() {
        val module = File(requireNotNull(System.getProperty("user.dir")))
        val viewModel = File(module, "src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt").readText()
        val mappers = File(
            module,
            "src/main/kotlin/io/aaps/copilot/ui/foundation/screens/MainUiStateMappers.kt"
        ).readText()

        assertThat(viewModel).doesNotContain("Local Nightscout enabled at")
        assertThat(viewModel).contains("Local Nightscout requested on exact port")
        assertThat(viewModel).contains("configured; runtime=")
        val checklist = mappers.substringAfter("title = \"Nightscout local runtime\"")
            .substringBefore("return SafetyUiState")
        assertThat(checklist).doesNotContain("ok = localNightscoutEnabled")
    }

    @Test
    fun productionEnableFlowUsesPersistedMigrationGate() {
        val module = File(requireNotNull(System.getProperty("user.dir")))
        val viewModel = File(module, "src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt").readText()
        val settings = File(
            module,
            "src/main/kotlin/io/aaps/copilot/ui/foundation/screens/SettingsScreen.kt"
        ).readText()

        assertThat(viewModel).contains("LocalNightscoutUpgradeMigrationGate.canEnable")
        assertThat(viewModel).contains("setLocalNightscoutLegacyMigrationAcknowledged")
        assertThat(settings).contains("settings_local_ns_legacy_migration_fingerprint")
        assertThat(settings).contains("onLocalNightscoutLegacyMigrationAcknowledged")
    }
}
