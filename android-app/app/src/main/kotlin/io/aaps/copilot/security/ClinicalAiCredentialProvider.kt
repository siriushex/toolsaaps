package io.aaps.copilot.security

import io.aaps.copilot.config.ClinicalAiProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class ClinicalAiCredentialStatus(
    val configured: Boolean = false,
    val busy: Boolean = true,
    val migrationFailed: Boolean = false,
    val readFailed: Boolean = false,
    val legacyCleanupPending: Boolean = false
)

class ClinicalAiCredentialProvider(
    stores: Map<ClinicalAiProviderId, ClinicalAiCredentialStore>
) {
    private val providerStates = stores.mapValues { (providerId, store) ->
        require(store.providerId == providerId) {
            "Credential store provider mismatch: expected ${providerId.name}, " +
                "actual ${store.providerId.name}"
        }
        ProviderState(store = store)
    }
    private val mutableStatus = MutableStateFlow(
        providerStates.mapValues { (_, state) -> state.status.value }
    )

    val status: StateFlow<Map<ClinicalAiProviderId, ClinicalAiCredentialStatus>> =
        mutableStatus.asStateFlow()

    suspend fun ensureMigrated(
        providerId: ClinicalAiProviderId
    ): CredentialMigrationResult = stateFor(providerId).run {
        mutex.withLock {
            ensureMigratedLocked(
                providerId = providerId,
                state = this,
                allowExplicitMigrationRetry = true
            )
        }
    }

    suspend fun requireCredential(providerId: ClinicalAiProviderId): String =
        stateFor(providerId).run {
            mutex.withLock {
                ensureMigratedLocked(
                    providerId = providerId,
                    state = this,
                    allowExplicitMigrationRetry = false
                )
                if (status.value.migrationFailed) {
                    throw IllegalStateException(
                        "${providerId.name} credential migration failed"
                    )
                }
                val credential = try {
                    store.readSecret()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (fatal: Error) {
                    throw fatal
                } catch (_: Exception) {
                    refreshStatusAfterReadFailure(providerId, this)
                    throw IllegalStateException(
                        "${providerId.name} credential read failed"
                    )
                }
                credential?.also {
                    setStatus(
                        providerId,
                        this,
                        status.value.copy(
                            configured = true,
                            busy = false,
                            migrationFailed = false,
                            readFailed = false
                        )
                    )
                } ?: run {
                    setStatus(
                        providerId,
                        this,
                        ClinicalAiCredentialStatus(configured = false, busy = false)
                    )
                    throw IllegalStateException(
                        "${providerId.name} credential is not configured"
                    )
                }
            }
        }

    suspend fun replace(providerId: ClinicalAiProviderId, value: String) {
        val state = stateFor(providerId)
        state.mutex.withLock {
            var completed = false
            setStatus(providerId, state, state.status.value.copy(busy = true))
            try {
                val replacementResult = state.store.replace(value)
                completeReplacement(providerId, state, replacementResult)
                completed = true
            } catch (cleanupCancellation: CredentialReplacementCleanupCancellation) {
                completeReplacement(
                    providerId,
                    state,
                    CredentialReplacementResult.REPLACED_WITH_LEGACY_CLEANUP_UNCERTAIN
                )
                completed = true
                throw cleanupCancellation.original
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (fatal: Error) {
                throw fatal
            } catch (_: Exception) {
                throw IllegalStateException(
                    "${providerId.name} credential update failed"
                )
            } finally {
                if (!completed) {
                    refreshStatusAfterInterruptedOperation(
                        providerId,
                        state,
                        allowExplicitMigrationRetry = false
                    )
                }
            }
        }
    }

    suspend fun delete(providerId: ClinicalAiProviderId) {
        val state = stateFor(providerId)
        state.mutex.withLock {
            var completed = false
            setStatus(providerId, state, state.status.value.copy(busy = true))
            try {
                state.store.clear()
                state.routineMigrationTerminalized = true
                state.explicitMigrationRetryResult = null
                setStatus(
                    providerId,
                    state,
                    ClinicalAiCredentialStatus(configured = false, busy = false)
                )
                completed = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (fatal: Error) {
                throw fatal
            } catch (_: Exception) {
                throw IllegalStateException(
                    "${providerId.name} credential deletion failed"
                )
            } finally {
                if (!completed) {
                    refreshStatusAfterInterruptedOperation(
                        providerId,
                        state,
                        allowExplicitMigrationRetry = true
                    )
                }
            }
        }
    }

    internal fun statusFor(
        providerId: ClinicalAiProviderId
    ): StateFlow<ClinicalAiCredentialStatus> =
        stateFor(providerId).status.asStateFlow()

    private suspend fun ensureMigratedLocked(
        providerId: ClinicalAiProviderId,
        state: ProviderState,
        allowExplicitMigrationRetry: Boolean
    ): CredentialMigrationResult {
        val explicitRetryResult = state.explicitMigrationRetryResult
        if (
            state.routineMigrationTerminalized &&
            !(explicitRetryResult != null && allowExplicitMigrationRetry)
        ) {
            return if (explicitRetryResult != null) {
                explicitRetryResult
            } else if (state.status.value.migrationFailed) {
                CredentialMigrationResult.FAILED
            } else {
                CredentialMigrationResult.ALREADY_SECURE
            }
        }

        setStatus(providerId, state, state.status.value.copy(busy = true))
        try {
            val result = try {
                state.store.migrateLegacyCredential()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (fatal: Error) {
                throw fatal
            } catch (_: Exception) {
                CredentialMigrationResult.FAILED
            }
            if (result == CredentialMigrationResult.FAILED) {
                terminalizeMigrationFailure(state)
                setStatus(
                    providerId,
                    state,
                    ClinicalAiCredentialStatus(
                        configured = false,
                        busy = false,
                        migrationFailed = true
                    )
                )
                return result
            }

            val configured = try {
                state.store.readConfigured()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (fatal: Error) {
                throw fatal
            } catch (_: Exception) {
                terminalizeMigrationFailure(state)
                setStatus(
                    providerId,
                    state,
                    ClinicalAiCredentialStatus(
                        configured = false,
                        busy = false,
                        migrationFailed = true
                    )
                )
                return CredentialMigrationResult.FAILED
            }
            state.routineMigrationTerminalized = true
            state.explicitMigrationRetryResult = result.takeIf {
                it == CredentialMigrationResult.SOURCE_CHANGED ||
                    it == CredentialMigrationResult.CLEANUP_UNCERTAIN
            }
            setStatus(
                providerId,
                state,
                ClinicalAiCredentialStatus(
                    configured = configured,
                    busy = false,
                    migrationFailed = false,
                    legacyCleanupPending = result.hasPendingLegacyCleanup()
                )
            )
            return result
        } finally {
            if (state.status.value.busy) {
                refreshStatusAfterInterruptedOperation(
                    providerId,
                    state,
                    allowExplicitMigrationRetry = true
                )
            }
        }
    }

    private suspend fun refreshStatusAfterInterruptedOperation(
        providerId: ClinicalAiProviderId,
        state: ProviderState,
        allowExplicitMigrationRetry: Boolean
    ) {
        val preserveLegacyCleanupPending = state.hasPendingLegacyCleanupRetry()
        val (recovered, retryMigration) = withContext(NonCancellable) {
            try {
                withTimeout(RECOVERY_TIMEOUT_MS) {
                    val probe = state.store.probeConfiguredAfterInterruption()
                    ClinicalAiCredentialStatus(
                        configured = probe.configured,
                        busy = false,
                        migrationFailed = false,
                        legacyCleanupPending =
                            probe.legacyCleanupPending || preserveLegacyCleanupPending
                    ) to false
                }
            } catch (_: Throwable) {
                ClinicalAiCredentialStatus(
                    configured = false,
                    busy = false,
                    migrationFailed = true,
                    legacyCleanupPending = state.status.value.legacyCleanupPending
                ) to true
            }
        }
        if (retryMigration) {
            terminalizeRecoveryFailure(state)
        } else {
            terminalizeRecoveredState(
                state,
                recovered,
                allowExplicitMigrationRetry
            )
        }
        setStatus(providerId, state, recovered)
    }

    private fun completeReplacement(
        providerId: ClinicalAiProviderId,
        state: ProviderState,
        result: CredentialReplacementResult
    ) {
        state.routineMigrationTerminalized = true
        state.explicitMigrationRetryResult = when (result) {
            CredentialReplacementResult.REPLACED -> null
            CredentialReplacementResult.REPLACED_WITH_LEGACY_SOURCE_CHANGED ->
                CredentialMigrationResult.SOURCE_CHANGED
            CredentialReplacementResult.REPLACED_WITH_LEGACY_CLEANUP_UNCERTAIN ->
                CredentialMigrationResult.CLEANUP_UNCERTAIN
        }
        setStatus(
            providerId,
            state,
            ClinicalAiCredentialStatus(
                configured = true,
                busy = false,
                legacyCleanupPending = result != CredentialReplacementResult.REPLACED
            )
        )
    }

    private suspend fun refreshStatusAfterReadFailure(
        providerId: ClinicalAiProviderId,
        state: ProviderState
    ) {
        val preserveLegacyCleanupPending = state.hasPendingLegacyCleanupRetry()
        val (recovered, recoverySucceeded) = withContext(NonCancellable) {
            try {
                withTimeout(RECOVERY_TIMEOUT_MS) {
                    val probe = state.store.probeConfiguredAfterReadFailure()
                    ClinicalAiCredentialStatus(
                        configured = probe.configured,
                        busy = false,
                        migrationFailed = false,
                        readFailed = true,
                        legacyCleanupPending =
                            probe.legacyCleanupPending || preserveLegacyCleanupPending
                    ) to true
                }
            } catch (_: Throwable) {
                ClinicalAiCredentialStatus(
                    configured = false,
                    busy = false,
                    migrationFailed = false,
                    readFailed = true,
                    legacyCleanupPending = state.status.value.legacyCleanupPending
                ) to false
            }
        }
        if (recoverySucceeded) {
            terminalizeRecoveredState(
                state,
                recovered,
                allowExplicitMigrationRetry = false
            )
        }
        setStatus(providerId, state, recovered)
    }

    private fun terminalizeRecoveredState(
        state: ProviderState,
        recovered: ClinicalAiCredentialStatus,
        allowExplicitMigrationRetry: Boolean
    ) {
        val priorExplicitRetryResult = state.explicitMigrationRetryResult
        state.routineMigrationTerminalized = true
        state.explicitMigrationRetryResult = when {
            recovered.legacyCleanupPending -> priorExplicitRetryResult?.takeIf {
                it.hasPendingLegacyCleanup()
            } ?: CredentialMigrationResult.CLEANUP_UNCERTAIN
            allowExplicitMigrationRetry -> CredentialMigrationResult.CLEANUP_UNCERTAIN
            else -> null
        }
    }

    private fun terminalizeMigrationFailure(state: ProviderState) {
        state.routineMigrationTerminalized = true
        state.explicitMigrationRetryResult = CredentialMigrationResult.FAILED
    }

    private fun terminalizeRecoveryFailure(state: ProviderState) {
        state.routineMigrationTerminalized = true
        state.explicitMigrationRetryResult = state.explicitMigrationRetryResult
            ?.takeIf { it.hasPendingLegacyCleanup() }
            ?: CredentialMigrationResult.FAILED
    }

    private fun setStatus(
        providerId: ClinicalAiProviderId,
        state: ProviderState,
        value: ClinicalAiCredentialStatus
    ) {
        state.status.value = value
        mutableStatus.update { current -> current + (providerId to value) }
    }

    private fun stateFor(providerId: ClinicalAiProviderId): ProviderState =
        requireNotNull(providerStates[providerId]) {
            "Credential store is unavailable for provider ${providerId.name}"
        }

    private fun CredentialMigrationResult.hasPendingLegacyCleanup(): Boolean =
        this == CredentialMigrationResult.SOURCE_CHANGED ||
            this == CredentialMigrationResult.CLEANUP_UNCERTAIN

    private fun ProviderState.hasPendingLegacyCleanupRetry(): Boolean =
        explicitMigrationRetryResult?.hasPendingLegacyCleanup() == true

    private class ProviderState(
        val store: ClinicalAiCredentialStore,
        val mutex: Mutex = Mutex(),
        val status: MutableStateFlow<ClinicalAiCredentialStatus> =
            MutableStateFlow(ClinicalAiCredentialStatus()),
        var routineMigrationTerminalized: Boolean = false,
        var explicitMigrationRetryResult: CredentialMigrationResult? = null
    )

    private companion object {
        const val RECOVERY_TIMEOUT_MS = 1_000L
    }
}
