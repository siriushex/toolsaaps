package io.aaps.copilot.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class LocalNightscoutRuntimeStatus {
    STARTING,
    SETUP,
    READY,
    FAILED
}

enum class LocalNightscoutRuntimeReason {
    DISABLED,
    AWAITING_AAPS_AUTH,
    PORT_INVALID,
    PORT_UNAVAILABLE,
    TLS_IDENTITY_UNAVAILABLE,
    PINNED_HANDSHAKE_FAILED,
    LEGACY_MIGRATION_ACK_REQUIRED,
    SERVICE_STOPPED,
    FOREGROUND_START_NOT_ALLOWED,
    POWER_SAVE_ACTIVE,
    THERAPY_ACTIONS_DISARMED,
    IDENTITY_RESET_IN_PROGRESS,
    IDENTITY_RESET_REQUIRED,
    IDENTITY_RESET_FAILED
}

data class LocalNightscoutRuntimeSnapshot(
    val status: LocalNightscoutRuntimeStatus = LocalNightscoutRuntimeStatus.SETUP,
    val port: Int? = null,
    val caFingerprint: String? = null,
    val reason: String? = LocalNightscoutRuntimeReason.DISABLED.name
)

internal open class MutableLocalNightscoutRuntimeState {
    private val mutable = MutableStateFlow(LocalNightscoutRuntimeSnapshot())
    val snapshot: StateFlow<LocalNightscoutRuntimeSnapshot> = mutable.asStateFlow()
    val value: LocalNightscoutRuntimeSnapshot get() = mutable.value

    fun starting(port: Int) {
        mutable.value = LocalNightscoutRuntimeSnapshot(
            status = LocalNightscoutRuntimeStatus.STARTING,
            port = port
        )
    }

    fun setup(port: Int?, fingerprint: String?, reason: LocalNightscoutRuntimeReason) {
        mutable.value = LocalNightscoutRuntimeSnapshot(
            status = LocalNightscoutRuntimeStatus.SETUP,
            port = port,
            caFingerprint = fingerprint,
            reason = reason.name
        )
    }

    fun authenticatedSocketObserved(port: Int, fingerprint: String) {
        mutable.value = LocalNightscoutRuntimeSnapshot(
            status = LocalNightscoutRuntimeStatus.READY,
            port = port,
            caFingerprint = fingerprint,
            reason = null
        )
    }

    fun failed(port: Int?, reason: LocalNightscoutRuntimeReason) = failed(port, reason.name)

    fun failed(port: Int?, reason: String) {
        mutable.value = LocalNightscoutRuntimeSnapshot(
            status = LocalNightscoutRuntimeStatus.FAILED,
            port = port,
            reason = reason.take(MAX_REASON_LENGTH)
        )
    }

    fun disabled(port: Int?) {
        setup(port, value.caFingerprint, LocalNightscoutRuntimeReason.DISABLED)
    }

    fun stopped(reason: LocalNightscoutRuntimeReason) {
        setup(value.port, value.caFingerprint, reason)
    }

    fun fingerprintAvailable(fingerprint: String) {
        mutable.value = value.copy(caFingerprint = fingerprint)
    }

    private companion object {
        const val MAX_REASON_LENGTH = 80
    }
}

internal object LocalNightscoutRuntimeState : MutableLocalNightscoutRuntimeState()

internal object LocalNightscoutUpgradeMigrationGate {
    const val LEGACY_CA_SHA256 =
        "C9:EF:07:C9:05:B6:56:23:0D:50:CB:F6:1D:85:A3:C3:" +
            "60:6B:B7:B4:8F:9C:E9:00:2B:AD:96:D3:BE:FF:71:41"

    fun canEnable(requestedEnabled: Boolean, migrationAcknowledged: Boolean): Boolean =
        requestedEnabled && migrationAcknowledged
}

internal enum class LocalNightscoutIdentityResetResult {
    CANCELLED,
    RESET,
    FAILED
}

internal class LocalNightscoutIdentityResetCoordinator(
    private val stopServer: () -> Unit,
    private val disableIntegration: suspend () -> Unit,
    private val resetIdentity: () -> Unit,
    private val runtimeState: MutableLocalNightscoutRuntimeState
) {
    suspend fun reset(confirmed: Boolean): LocalNightscoutIdentityResetResult {
        if (!confirmed) return LocalNightscoutIdentityResetResult.CANCELLED
        val port = runtimeState.value.port
        val fingerprint = runtimeState.value.caFingerprint
        return withContext(NonCancellable) {
            runtimeState.setup(
                port = port,
                fingerprint = fingerprint,
                reason = LocalNightscoutRuntimeReason.IDENTITY_RESET_IN_PROGRESS
            )
            try {
                stopServer()
                disableIntegration()
                resetIdentity()
                runtimeState.setup(
                    port = port,
                    fingerprint = null,
                    reason = LocalNightscoutRuntimeReason.IDENTITY_RESET_REQUIRED
                )
                LocalNightscoutIdentityResetResult.RESET
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Error) {
                throw fatal
            } catch (_: Exception) {
                runtimeState.failed(port, LocalNightscoutRuntimeReason.IDENTITY_RESET_FAILED)
                LocalNightscoutIdentityResetResult.FAILED
            }
        }
    }
}
