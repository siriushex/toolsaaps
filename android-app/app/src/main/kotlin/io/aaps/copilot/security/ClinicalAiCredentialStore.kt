package io.aaps.copilot.security

import io.aaps.copilot.config.ClinicalAiProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SecretStorage {
    suspend fun stagePending(value: String, legacyCleanupExpected: String?)
    suspend fun readPending(): String?
    suspend fun readPendingLegacyCleanupIntent(): String?
    suspend fun commitPending()
    suspend fun rollbackPromotion()
    suspend fun discardRollback()
    suspend fun clearPending()
    suspend fun readLegacyCleanupIntent(): String?
    suspend fun clearLegacyCleanupIntent()
    suspend fun markDeletionPending(legacyCleanupExpected: String?)
    suspend fun isDeletionPending(): Boolean
    suspend fun readPendingDeletionLegacyCleanupIntent(): String?
    suspend fun read(): String?
    suspend fun clear()
}

interface LegacyCredentialSource {
    suspend fun read(): String?
    suspend fun clearIfMatches(expected: String): Boolean
}

interface LegacyOpenAiKeySource : LegacyCredentialSource

object EmptyLegacyCredentialSource : LegacyCredentialSource {
    override suspend fun read(): String? = null
    override suspend fun clearIfMatches(expected: String): Boolean = false
}

enum class CredentialMigrationResult {
    NO_LEGACY_KEY,
    ALREADY_SECURE,
    MIGRATED,
    SOURCE_CHANGED,
    CLEANUP_UNCERTAIN,
    FAILED
}

internal enum class CredentialReplacementResult {
    REPLACED,
    REPLACED_WITH_LEGACY_SOURCE_CHANGED,
    REPLACED_WITH_LEGACY_CLEANUP_UNCERTAIN
}

internal class CredentialReplacementCleanupCancellation(
    val original: CancellationException
) : CancellationException()

internal data class CredentialStorageProbe(
    val configured: Boolean,
    val legacyCleanupPending: Boolean
)

class ClinicalAiCredentialStore(
    internal val providerId: ClinicalAiProviderId,
    private val secureStorage: SecretStorage,
    legacyCredentialSource: LegacyCredentialSource
) {
    private val legacyCredentialSource =
        if (providerId == ClinicalAiProviderId.OPENAI) {
            legacyCredentialSource
        } else {
            EmptyLegacyCredentialSource
        }
    private val mutex = NAMESPACE_TRANSACTION_MUTEXES.getValue(providerId)

    @Volatile
    private var activeReadsDisabled = false

    suspend fun readConfigured(): Boolean = readSecret() != null

    internal suspend fun readSecret(): String? = mutex.withLock {
        readActiveLocked()
    }

    internal suspend fun probeConfiguredAfterInterruption(): CredentialStorageProbe =
        mutex.withLock {
            try {
                if (finishPendingDeletion()) {
                    return@withLock CredentialStorageProbe(
                        configured = false,
                        legacyCleanupPending = false
                    )
                }
                val configured = normalize(secureStorage.read()) != null
                val cleanupPending = normalize(secureStorage.readLegacyCleanupIntent()) != null
                secureStorage.discardRollback()
                activeReadsDisabled = false
                CredentialStorageProbe(configured, cleanupPending)
            } catch (cancellation: CancellationException) {
                activeReadsDisabled = true
                throw cancellation
            } catch (fatal: Error) {
                activeReadsDisabled = true
                throw fatal
            } catch (_: Exception) {
                activeReadsDisabled = true
                throw IllegalStateException("Credential recovery probe failed")
            }
        }

    internal suspend fun probeConfiguredAfterReadFailure(): CredentialStorageProbe =
        mutex.withLock {
            try {
                if (finishPendingDeletion()) {
                    return@withLock CredentialStorageProbe(
                        configured = false,
                        legacyCleanupPending = false
                    )
                }
                secureStorage.rollbackPromotion()
                val configured = normalize(secureStorage.read()) != null
                val cleanupPending = normalize(secureStorage.readLegacyCleanupIntent()) != null
                activeReadsDisabled = false
                CredentialStorageProbe(configured, cleanupPending)
            } catch (cancellation: CancellationException) {
                activeReadsDisabled = true
                throw cancellation
            } catch (fatal: Error) {
                activeReadsDisabled = true
                throw fatal
            } catch (_: Exception) {
                activeReadsDisabled = true
                throw IllegalStateException("Credential read recovery probe failed")
            }
        }

    internal suspend fun replace(value: String): CredentialReplacementResult {
        val normalized = value.trim()
        require(normalized.isNotBlank()) { "Credential must not be blank" }

        return mutex.withLock {
            val legacyBeforeReplacement = try {
                finishPendingDeletion()
                secureStorage.rollbackPromotion()
                normalize(secureStorage.read())
                normalize(legacyCredentialSource.read())
            } catch (cancellation: CancellationException) {
                activeReadsDisabled = true
                throw cancellation
            } catch (fatal: Error) {
                activeReadsDisabled = true
                throw fatal
            } catch (_: Exception) {
                activeReadsDisabled = true
                throw IllegalStateException("Existing credential verification failed")
            }
            activeReadsDisabled = false

            try {
                secureStorage.stagePending(normalized, legacyBeforeReplacement)
                check(normalize(secureStorage.readPending()) == normalized) {
                    "Pending credential verification failed"
                }
                check(
                    normalize(secureStorage.readPendingLegacyCleanupIntent()) ==
                        legacyBeforeReplacement
                ) {
                    "Pending legacy cleanup intent verification failed"
                }
                secureStorage.commitPending()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (fatal: Error) {
                throw fatal
            } catch (_: Exception) {
                throw IllegalStateException("Secure credential replacement failed")
            }

            val promoted = try {
                normalize(secureStorage.read())
            } catch (cancellation: CancellationException) {
                activeReadsDisabled = true
                throw cancellation
            } catch (fatal: Error) {
                activeReadsDisabled = true
                throw fatal
            } catch (_: Exception) {
                rollbackFailedPromotion()
                throw IllegalStateException("Promoted credential verification failed")
            }
            if (promoted != normalized) {
                rollbackFailedPromotion()
                throw IllegalStateException("Promoted credential verification failed")
            }
            try {
                secureStorage.discardRollback()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (fatal: Error) {
                throw fatal
            } catch (_: Exception) {
                activeReadsDisabled = true
                throw IllegalStateException("Promotion rollback cleanup failed")
            }
            activeReadsDisabled = false

            cleanupAfterReplacement(legacyBeforeReplacement)
        }
    }

    suspend fun clear() {
        mutex.withLock {
            try {
                if (finishPendingDeletion()) {
                    return@withLock
                }
                val legacy = normalize(legacyCredentialSource.read())
                secureStorage.markDeletionPending(legacy)
                check(finishPendingDeletion()) {
                    "Credential deletion marker was not persisted"
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (fatal: Error) {
                throw fatal
            } catch (_: Exception) {
                throw IllegalStateException("Secure credential clear failed")
            }
        }
    }

    suspend fun migrateLegacyCredential(): CredentialMigrationResult = mutex.withLock {
        activeReadsDisabled = false

        val active = try {
            if (finishPendingDeletion()) {
                return@withLock CredentialMigrationResult.NO_LEGACY_KEY
            }
            secureStorage.rollbackPromotion()
            normalize(secureStorage.read())
        } catch (cancellation: CancellationException) {
            activeReadsDisabled = true
            throw cancellation
        } catch (fatal: Error) {
            activeReadsDisabled = true
            throw fatal
        } catch (_: Exception) {
            activeReadsDisabled = true
            return@withLock CredentialMigrationResult.FAILED
        }

        if (active != null) {
            val cleanupIntent = try {
                normalize(secureStorage.readLegacyCleanupIntent())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (fatal: Error) {
                throw fatal
            } catch (_: Exception) {
                return@withLock CredentialMigrationResult.CLEANUP_UNCERTAIN
            }
            if (cleanupIntent != null) {
                if (!clearPendingForMigration()) {
                    return@withLock CredentialMigrationResult.CLEANUP_UNCERTAIN
                }
                return@withLock retryDurableLegacyCleanup(cleanupIntent)
            }
            val legacy = readLegacyForMigration()
                ?: return@withLock CredentialMigrationResult.CLEANUP_UNCERTAIN
            if (!clearPendingForMigration()) {
                return@withLock CredentialMigrationResult.CLEANUP_UNCERTAIN
            }
            if (legacy.isBlank()) {
                return@withLock CredentialMigrationResult.ALREADY_SECURE
            }
            if (legacy != active) {
                return@withLock CredentialMigrationResult.SOURCE_CHANGED
            }
            return@withLock clearLegacyAfterVerifiedActive(legacy)
        }

        val legacy = readLegacyForMigration()
            ?: return@withLock CredentialMigrationResult.FAILED
        if (legacy.isBlank()) {
            return@withLock if (clearPendingForMigration()) {
                CredentialMigrationResult.NO_LEGACY_KEY
            } else {
                CredentialMigrationResult.FAILED
            }
        }

        try {
            secureStorage.stagePending(legacy, legacy)
            check(normalize(secureStorage.readPending()) == legacy) {
                "Pending credential verification failed"
            }
            check(normalize(secureStorage.readPendingLegacyCleanupIntent()) == legacy) {
                "Pending legacy cleanup intent verification failed"
            }
            secureStorage.commitPending()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            return@withLock CredentialMigrationResult.FAILED
        }

        val promoted = try {
            normalize(secureStorage.read())
        } catch (cancellation: CancellationException) {
            activeReadsDisabled = true
            throw cancellation
        } catch (fatal: Error) {
            activeReadsDisabled = true
            throw fatal
        } catch (_: Exception) {
            rollbackFailedPromotion()
            return@withLock CredentialMigrationResult.FAILED
        }
        if (promoted != legacy) {
            rollbackFailedPromotion()
            return@withLock CredentialMigrationResult.FAILED
        }
        try {
            secureStorage.discardRollback()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            activeReadsDisabled = true
            return@withLock CredentialMigrationResult.FAILED
        }
        activeReadsDisabled = false
        clearCapturedLegacy(legacy)
    }

    private suspend fun readActiveLocked(): String? {
        return try {
            if (finishPendingDeletion()) {
                return null
            }
            if (activeReadsDisabled) {
                throw IllegalStateException("Secure credential read failed")
            }
            secureStorage.rollbackPromotion()
            normalize(secureStorage.read())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            activeReadsDisabled = true
            throw IllegalStateException("Secure credential read failed")
        }
    }

    private suspend fun finishPendingDeletion(): Boolean {
        if (!secureStorage.isDeletionPending()) {
            return false
        }
        activeReadsDisabled = true
        val legacyExpected = normalize(
            secureStorage.readPendingDeletionLegacyCleanupIntent()
        )
        if (legacyExpected != null) {
            val currentLegacy = normalize(legacyCredentialSource.read())
            check(currentLegacy == null || currentLegacy == legacyExpected) {
                "Legacy credential changed during deletion"
            }
            if (currentLegacy != null) {
                check(legacyCredentialSource.clearIfMatches(legacyExpected)) {
                    "Legacy credential changed during deletion"
                }
            }
        }
        secureStorage.clear()
        activeReadsDisabled = false
        return true
    }

    private suspend fun rollbackFailedPromotion() {
        try {
            secureStorage.rollbackPromotion()
            activeReadsDisabled = false
        } catch (cancellation: CancellationException) {
            activeReadsDisabled = true
            throw cancellation
        } catch (fatal: Error) {
            activeReadsDisabled = true
            throw fatal
        } catch (_: Exception) {
            activeReadsDisabled = true
        }
    }

    private suspend fun readLegacyForMigration(): String? = try {
        legacyCredentialSource.read()?.trim().orEmpty()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (fatal: Error) {
        throw fatal
    } catch (_: Exception) {
        null
    }

    private suspend fun clearPendingForMigration(): Boolean = try {
        secureStorage.clearPending()
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (fatal: Error) {
        throw fatal
    } catch (_: Exception) {
        false
    }

    private suspend fun clearLegacyAfterVerifiedActive(
        expected: String
    ): CredentialMigrationResult = try {
        if (legacyCredentialSource.clearIfMatches(expected)) {
            CredentialMigrationResult.MIGRATED
        } else {
            CredentialMigrationResult.SOURCE_CHANGED
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (fatal: Error) {
        throw fatal
    } catch (_: Exception) {
        CredentialMigrationResult.CLEANUP_UNCERTAIN
    }

    private suspend fun retryDurableLegacyCleanup(
        expected: String
    ): CredentialMigrationResult {
        val current = readLegacyForMigration()
            ?: return CredentialMigrationResult.CLEANUP_UNCERTAIN
        if (current.isBlank()) {
            return if (clearDurableLegacyCleanupIntent()) {
                CredentialMigrationResult.ALREADY_SECURE
            } else {
                CredentialMigrationResult.CLEANUP_UNCERTAIN
            }
        }
        if (current != expected) {
            return CredentialMigrationResult.SOURCE_CHANGED
        }
        val cleared = try {
            legacyCredentialSource.clearIfMatches(expected)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            return CredentialMigrationResult.CLEANUP_UNCERTAIN
        }
        if (!cleared) {
            return CredentialMigrationResult.SOURCE_CHANGED
        }
        return if (clearDurableLegacyCleanupIntent()) {
            CredentialMigrationResult.MIGRATED
        } else {
            CredentialMigrationResult.CLEANUP_UNCERTAIN
        }
    }

    private suspend fun clearCapturedLegacy(
        expected: String
    ): CredentialMigrationResult {
        val cleared = try {
            legacyCredentialSource.clearIfMatches(expected)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            return CredentialMigrationResult.CLEANUP_UNCERTAIN
        }
        if (!cleared) {
            return CredentialMigrationResult.SOURCE_CHANGED
        }
        return if (clearDurableLegacyCleanupIntent()) {
            CredentialMigrationResult.MIGRATED
        } else {
            CredentialMigrationResult.CLEANUP_UNCERTAIN
        }
    }

    private suspend fun cleanupAfterReplacement(
        expected: String?
    ): CredentialReplacementResult {
        if (expected == null) {
            return CredentialReplacementResult.REPLACED
        }
        return try {
            when (clearCapturedLegacy(expected)) {
                CredentialMigrationResult.MIGRATED,
                CredentialMigrationResult.ALREADY_SECURE ->
                    CredentialReplacementResult.REPLACED
                CredentialMigrationResult.SOURCE_CHANGED ->
                    CredentialReplacementResult.REPLACED_WITH_LEGACY_SOURCE_CHANGED
                CredentialMigrationResult.CLEANUP_UNCERTAIN,
                CredentialMigrationResult.NO_LEGACY_KEY,
                CredentialMigrationResult.FAILED ->
                    CredentialReplacementResult.REPLACED_WITH_LEGACY_CLEANUP_UNCERTAIN
            }
        } catch (cancellation: CancellationException) {
            throw CredentialReplacementCleanupCancellation(cancellation)
        }
    }

    private suspend fun clearDurableLegacyCleanupIntent(): Boolean = try {
        secureStorage.clearLegacyCleanupIntent()
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (fatal: Error) {
        throw fatal
    } catch (_: Exception) {
        false
    }

    private fun normalize(value: String?): String? =
        value?.trim()?.takeIf { it.isNotBlank() }

    private companion object {
        val NAMESPACE_TRANSACTION_MUTEXES =
            ClinicalAiProviderId.entries.associateWith { Mutex() }
    }
}
