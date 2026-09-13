package io.aaps.copilot.security

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.ClinicalAiProviderId
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertThrows
import org.junit.Test

class KeystoreSecretStorageSourceTest {

    @Test
    fun productionJvmConstructorsCannotInjectDeletionCoordinator() {
        val exposedConstructors = KeystoreSecretStorage::class.java.declaredConstructors
            .filterNot { Modifier.isPrivate(it.modifiers) }
        val exposedSignatures = exposedConstructors.map { constructor ->
            constructor.parameterTypes.toList()
        }
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()
        val secondaryConstructors = source.substringAfter(
            "constructor(context: Context)"
        ).substringBefore("override suspend fun stagePending")

        assertThat(exposedSignatures).containsExactly(
            listOf(Context::class.java),
            listOf(Context::class.java, SecretStorageNamespace::class.java)
        )
        assertThat(exposedConstructors.any { constructor ->
            constructor.parameterTypes.contains(
                DeletionDurabilityCoordinator::class.java
            )
        }).isFalse()
        assertThat(secondaryConstructors).doesNotContain("internal constructor")
        assertThat(secondaryConstructors).doesNotContain(
            "deletionDurabilityCoordinator: DeletionDurabilityCoordinator"
        )
        assertThat(secondaryConstructors).doesNotContain(
            "deletionDurabilityCoordinator = deletionDurabilityCoordinator"
        )
        assertThat(secondaryConstructors).contains(
            "RuntimeSecretDeletionDurabilityCoordinators.coordinatorFor(namespace)"
        )
    }

    @Test
    fun namespacesAreImmutableExactAndDistinct() {
        assertThat(ClinicalAiSecretStorageNamespaces.forProvider(ClinicalAiProviderId.OPENAI))
            .isEqualTo(
                SecretStorageNamespace(
                    preferencesFile = "openai_credentials",
                    keyAlias = "io.aaps.predictivecopilot.openai.aes_gcm.v1"
                )
            )
        assertThat(ClinicalAiSecretStorageNamespaces.forProvider(ClinicalAiProviderId.ANTHROPIC))
            .isEqualTo(
                SecretStorageNamespace(
                    preferencesFile = "clinical_ai_anthropic_credentials",
                    keyAlias = "io.aaps.predictivecopilot.anthropic.aes_gcm.v1"
                )
            )
        assertThat(ClinicalAiSecretStorageNamespaces.forProvider(ClinicalAiProviderId.GEMINI))
            .isEqualTo(
                SecretStorageNamespace(
                    preferencesFile = "clinical_ai_gemini_credentials",
                    keyAlias = "io.aaps.predictivecopilot.gemini.aes_gcm.v1"
                )
            )
        assertThat(
            ClinicalAiSecretStorageNamespaces.forProvider(
                ClinicalAiProviderId.OPENAI_COMPATIBLE
            )
        ).isEqualTo(
            SecretStorageNamespace(
                preferencesFile = "clinical_ai_compatible_credentials",
                keyAlias = "io.aaps.predictivecopilot.compatible.aes_gcm.v1"
            )
        )
        assertThat(
            ClinicalAiProviderId.entries
                .map(ClinicalAiSecretStorageNamespaces::forProvider)
                .map(SecretStorageNamespace::preferencesFile)
        ).containsNoDuplicates()
        assertThat(
            ClinicalAiProviderId.entries
                .map(ClinicalAiSecretStorageNamespaces::forProvider)
                .map(SecretStorageNamespace::keyAlias)
        ).containsNoDuplicates()
    }

    @Test
    fun encryptionLetsAndroidKeystoreCipherGenerateIvAndPersistsCipherIv() {
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()
        val writeBody = source.substringAfter("override suspend fun stagePending")
            .substringBefore("override suspend fun readPending")

        assertThat(writeBody)
            .contains("cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())")
        assertThat(writeBody).contains("val iv = cipher.iv")
        assertThat(writeBody).contains("check(iv.size == IV_SIZE_BYTES)")
        assertThat(writeBody).doesNotContain("GCMParameterSpec")
        assertThat(source).doesNotContain("SecureRandom")
    }

    @Test
    fun activeAndPendingUseDistinctVersionedRecords() {
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()

        assertThat(source).contains("ACTIVE_PREFIX")
        assertThat(source).contains("PENDING_PREFIX")
        assertThat(source).contains("commitPending")
        assertThat(source).contains("clearPending")
    }

    @Test
    fun legacyCleanupIntentIsEncryptedAndPromotedAtomicallyWithCredential() {
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()
        val stageBody = source.substringAfter("override suspend fun stagePending")
            .substringBefore("override suspend fun readPending")
        val commitBody = source.substringAfter("override suspend fun commitPending")
            .substringBefore("override suspend fun rollbackPromotion")

        assertThat(source).contains("PENDING_LEGACY_CLEANUP_PREFIX")
        assertThat(source).contains("LEGACY_CLEANUP_PREFIX")
        assertThat(stageBody).contains("encryptRecord(legacyCleanupExpected)")
        assertThat(stageBody).doesNotContain("putString(LEGACY_CLEANUP_PREFIX")
        assertThat(commitBody).contains(
            "readEncodedRecord(PENDING_LEGACY_CLEANUP_PREFIX)"
        )
        assertThat(commitBody).contains(
            "putRecord(editor, LEGACY_CLEANUP_PREFIX, pendingLegacyCleanup)"
        )
        val durableCommit = commitBody.indexOf("commitDurably(editor")
        assertThat(durableCommit).isAtLeast(0)
        assertThat(commitBody.indexOf("putRecord(editor, ACTIVE_PREFIX, pending)"))
            .isLessThan(durableCommit)
        assertThat(commitBody.indexOf(
            "putRecord(editor, LEGACY_CLEANUP_PREFIX, pendingLegacyCleanup)"
        )).isLessThan(durableCommit)
    }

    @Test
    fun promotionRollbackRetainsOnlyEncodedActiveRecordOrAbsenceMarker() {
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()
        val commitBody = source.substringAfter("override suspend fun commitPending")
            .substringBefore("override suspend fun rollbackPromotion")

        assertThat(commitBody)
            .contains("val previousActive = readEncodedRecord(ACTIVE_PREFIX)")
        assertThat(commitBody)
            .contains("putRecord(editor, ROLLBACK_PREFIX, previousActive)")
        assertThat(commitBody).contains("ROLLBACK_STATE_ABSENT")
        assertThat(commitBody).contains("putRecord(editor, ACTIVE_PREFIX, pending)")
        assertThat(commitBody).contains("commitDurably(editor")
        assertThat(source).contains("override suspend fun rollbackPromotion")
        assertThat(source).contains("override suspend fun discardRollback")
    }

    @Test
    fun contextConstructorPreservesLegacyOpenAiNamespaceAndClearUsesSelectedAlias() {
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()

        assertThat(source).contains("constructor(context: Context)")
        assertThat(source).contains("ClinicalAiSecretStorageNamespaces.OPENAI")
        assertThat(source).contains("namespace.preferencesFile")
        assertThat(source).contains("namespace.keyAlias")
        assertThat(source).doesNotContain("deleteEntry(KEY_ALIAS)")
    }

    @Test
    fun deletionMarkerEncryptsExactLegacyExpectationAndSurvivesSecureClear() {
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()
        val markerBody = source.substringAfter("override suspend fun markDeletionPending")
            .substringBefore("override suspend fun isDeletionPending")
        val intentReadBody = source.substringAfter(
            "override suspend fun readPendingDeletionLegacyCleanupIntent"
        ).substringBefore("override suspend fun")
        val clearBody = source.substringAfter("override suspend fun clear()")
            .substringBefore("private suspend fun")

        assertThat(markerBody).contains("preferences.edit()")
        assertThat(markerBody).contains("putBoolean(DELETION_PENDING_KEY, true)")
        assertThat(markerBody).contains("encryptRecord(legacyCleanupExpected)")
        assertThat(markerBody).contains("DELETION_LEGACY_CLEANUP_PREFIX")
        val durableCommit = markerBody.indexOf("commitDurablyWithinGuard(")
        assertThat(durableCommit).isAtLeast(0)
        assertThat(markerBody.indexOf("putBoolean(DELETION_PENDING_KEY, true)"))
            .isLessThan(durableCommit)
        assertThat(markerBody.indexOf("DELETION_LEGACY_CLEANUP_PREFIX"))
            .isLessThan(durableCommit)
        assertThat(intentReadBody).contains(
            "readRecord(DELETION_LEGACY_CLEANUP_PREFIX)"
        )
        assertThat(source).contains(
            "const val DELETION_LEGACY_CLEANUP_PREFIX = \"deletion_legacy_cleanup\""
        )
        assertSecureClearKeepsDeletionTombstone(clearBody)
    }

    @Test
    fun explicitPromotionAtomicallyRemovesDeletionMarkerAndEncryptedExpectation() {
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()
        val commitBody = source.substringAfter("override suspend fun commitPending")
            .substringBefore("override suspend fun rollbackPromotion")

        val markerRemoval = commitBody.indexOf("editor.remove(DELETION_PENDING_KEY)")
        val intentRemoval = commitBody.indexOf(
            "removeRecord(editor, DELETION_LEGACY_CLEANUP_PREFIX)"
        )
        val commit = commitBody.indexOf("commitDurably(editor")

        assertThat(markerRemoval).isAtLeast(0)
        assertThat(intentRemoval).isAtLeast(0)
        assertThat(commit).isAtLeast(0)
        assertThat(markerRemoval).isLessThan(commit)
        assertThat(intentRemoval).isLessThan(commit)
    }

    @Test
    fun secureClearGuardRejectsMissingAliasDeletionOrPreferencesClear() {
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()
        val clearBody = source.substringAfter("override suspend fun clear()")
            .substringBefore("private suspend fun")

        assertThrows(AssertionError::class.java) {
            assertSecureClearKeepsDeletionTombstone(
                clearBody.replaceFirst("keyStore.deleteEntry(namespace.keyAlias)", "")
            )
        }
        assertThrows(AssertionError::class.java) {
            assertSecureClearKeepsDeletionTombstone(
                clearBody.replaceFirst(".clear()", "")
            )
        }
        val aliasDeletion = "keyStore.deleteEntry(namespace.keyAlias)"
        val aliasFirstMutation = clearBody
            .replaceFirst(aliasDeletion, "")
            .replaceFirst(
                "val editor = preferences.edit()",
                "$aliasDeletion\n            val editor = preferences.edit()"
            )
        assertThrows(AssertionError::class.java) {
            assertSecureClearKeepsDeletionTombstone(aliasFirstMutation)
        }
    }

    @Test
    fun deletionPersistenceFailureQuarantinesProcessVisibleSharedPreferencesState() {
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()
        val markerBody = source.substringAfter("override suspend fun markDeletionPending")
            .substringBefore("override suspend fun isDeletionPending")
        val markerReadBody = source.substringAfter("override suspend fun isDeletionPending")
            .substringBefore("override suspend fun readPendingDeletionLegacyCleanupIntent")
        val intentReadBody = source.substringAfter(
            "override suspend fun readPendingDeletionLegacyCleanupIntent"
        ).substringBefore("override suspend fun read()")
        val clearBody = source.substringAfter("override suspend fun clear()")
            .substringBefore("private suspend fun")
        val commitHelper = source.substringAfter("private fun commitDurablyWithinGuard")
            .substringBefore("private fun quarantineDeletionState")

        assertThat(markerBody).contains("withDeletionDurabilityGuard")
        assertThat(markerBody).contains("commitDurablyWithinGuard")
        assertThat(markerReadBody).contains("withDeletionDurabilityGuard")
        assertThat(intentReadBody).contains("withDeletionDurabilityGuard")
        assertThat(clearBody).contains("withDeletionDurabilityGuard")
        assertThat(clearBody).contains("commitDurablyWithinGuard")
        assertThat(commitHelper).contains("if (!committed) {")
        assertThat(commitHelper).contains("catch (failure: Throwable)")
        assertThat(commitHelper).contains("quarantineDeletionState()")
        assertThat(commitHelper.indexOf("editor.commit()"))
            .isLessThan(commitHelper.indexOf("quarantineDeletionState()"))
    }

    @Test
    fun everyDurabilityCriticalPreferenceCommitUsesNamespaceQuarantinePrimitive() {
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()
        val criticalOperationSignatures = listOf(
            "stagePending(",
            "commitPending()",
            "rollbackPromotion()",
            "discardRollback()",
            "clearPending()",
            "clearLegacyCleanupIntent()",
            "markDeletionPending(",
            "clear()"
        )

        criticalOperationSignatures.forEachIndexed { index, signature ->
            val operationStart = source.indexOf("override suspend fun $signature")
            val operationEnd = if (index < criticalOperationSignatures.lastIndex) {
                source.indexOf(
                    "override suspend fun ${criticalOperationSignatures[index + 1]}",
                    operationStart + 1
                )
            } else {
                source.indexOf("private suspend fun", operationStart + 1)
            }
            assertThat(operationStart).isAtLeast(0)
            assertThat(operationEnd).isGreaterThan(operationStart)
            val body = source.substring(operationStart, operationEnd)
            assertThat(body).contains("commitDurably")
            assertThat(body).doesNotContain(".commit()")
        }

        val commitHelper = source.substringAfter("private fun commitDurablyWithinGuard")
            .substringBefore("private fun readRecord")
        assertThat(Regex("\\.commit\\(\\)").findAll(source).count()).isEqualTo(1)
        assertThat(commitHelper).contains("editor.commit()")
        assertThat(commitHelper).contains("catch (failure: Throwable)")
        assertThat(commitHelper).contains("quarantineDeletionState()")
        assertThat(commitHelper).contains("if (!committed)")
    }

    @Test
    fun deletionDurabilityCoordinationIsNamespaceScopedInterruptibleAndBounded() {
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()
        val storageBody = source.substringAfter("class KeystoreSecretStorage")
        val durabilityHelper = storageBody
            .substringAfter("private fun <T> withDeletionDurabilityGuard")
            .substringBefore("private fun quarantineDeletionState")

        assertThat(source).contains("class DeletionDurabilityCoordinatorRegistry")
        assertThat(source).contains(
            "ClinicalAiSecretStorageNamespaces.OPENAI -> openAiCoordinator"
        )
        assertThat(source).contains(
            "ClinicalAiSecretStorageNamespaces.ANTHROPIC -> anthropicCoordinator"
        )
        assertThat(source).contains(
            "ClinicalAiSecretStorageNamespaces.GEMINI -> geminiCoordinator"
        )
        assertThat(source).contains("ClinicalAiSecretStorageNamespaces.OPENAI_COMPATIBLE ->")
        assertThat(source).contains("openAiCompatibleCoordinator")
        assertThat(source).contains("object RuntimeSecretDeletionDurabilityCoordinators")
        assertThat(source).contains("class DeletionDurabilityCoordinator")
        assertThat(source).contains("ReentrantLock")
        assertThat(source).contains("lockInterruptibly()")
        assertThat(storageBody).contains(
            "RuntimeSecretDeletionDurabilityCoordinators.coordinatorFor(namespace)"
        )
        assertThat(durabilityHelper).contains(
            "deletionDurabilityCoordinator.withGuard(block)"
        )
        assertThat(source).doesNotContain("WeakHashMap")
        assertThat(source).doesNotContain("synchronized(DELETION_DURABILITY_LOCK)")
        assertThat(source).doesNotContain("QUARANTINED_DELETION_NAMESPACES")
    }

    @Test
    fun promotionAtomicallySupersedesAndRollbackRestoresDeletionTombstone() {
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()
        val commitBody = source.substringAfter("override suspend fun commitPending")
            .substringBefore("override suspend fun rollbackPromotion")
        val rollbackBody = source.substringAfter("override suspend fun rollbackPromotion")
            .substringBefore("override suspend fun discardRollback")
        val removeRollbackBody = source.substringAfter("private fun removeRollback")
            .substringBefore("private fun getOrCreateKey")

        assertThat(commitBody).contains(
            "val previousDeletionPending = preferences.getBoolean(DELETION_PENDING_KEY, false)"
        )
        assertThat(commitBody).contains(
            "editor.putBoolean(ROLLBACK_DELETION_PENDING_KEY, previousDeletionPending)"
        )
        assertThat(commitBody.indexOf("putRecord(editor, ACTIVE_PREFIX, pending)"))
            .isLessThan(commitBody.indexOf("editor.remove(DELETION_PENDING_KEY)"))
        assertThat(commitBody.indexOf("editor.remove(DELETION_PENDING_KEY)"))
            .isLessThan(commitBody.indexOf("commitDurably(editor"))
        assertThat(rollbackBody).contains(
            "preferences.getBoolean(ROLLBACK_DELETION_PENDING_KEY, false)"
        )
        assertRollbackBranchesRestoreDeletionMarker(rollbackBody)
        assertThat(removeRollbackBody).contains("editor.remove(ROLLBACK_DELETION_PENDING_KEY)")
    }

    @Test
    fun promotionRollbackRestoresPreviousEncryptedCleanupIntent() {
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()
        val commitBody = source.substringAfter("override suspend fun commitPending")
            .substringBefore("override suspend fun rollbackPromotion")
        val rollbackBody = source.substringAfter("override suspend fun rollbackPromotion")
            .substringBefore("override suspend fun discardRollback")
        val removeRollbackBody = source.substringAfter("private fun removeRollback")
            .substringBefore("private fun restoreLegacyCleanupIntent")

        assertThat(commitBody).contains(
            "val previousLegacyCleanup = readEncodedRecord(LEGACY_CLEANUP_PREFIX)"
        )
        assertThat(commitBody).contains("ROLLBACK_LEGACY_CLEANUP_STATE_KEY")
        assertThat(commitBody).contains("ROLLBACK_LEGACY_CLEANUP_PREFIX")
        assertThat(
            Regex("restoreLegacyCleanupIntent\\(editor\\)")
                .findAll(rollbackBody)
                .count()
        ).isEqualTo(2)
        assertThat(removeRollbackBody).contains("ROLLBACK_LEGACY_CLEANUP_STATE_KEY")
        assertThat(removeRollbackBody).contains("ROLLBACK_LEGACY_CLEANUP_PREFIX")
    }

    @Test
    fun promotionRollbackRestoresEncryptedDeletionExpectationWithTombstone() {
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()
        val commitBody = source.substringAfter("override suspend fun commitPending")
            .substringBefore("override suspend fun rollbackPromotion")
        val rollbackBody = source.substringAfter("override suspend fun rollbackPromotion")
            .substringBefore("override suspend fun discardRollback")
        val removeRollbackBody = source.substringAfter("private fun removeRollback")
            .substringBefore("private fun restoreLegacyCleanupIntent")

        assertThat(commitBody).contains(
            "val previousDeletionLegacyCleanup = " +
                "readEncodedRecord(DELETION_LEGACY_CLEANUP_PREFIX)"
        )
        assertThat(commitBody).contains("ROLLBACK_DELETION_LEGACY_CLEANUP_STATE_KEY")
        assertThat(commitBody).contains("ROLLBACK_DELETION_LEGACY_CLEANUP_PREFIX")
        assertThat(
            Regex("restoreDeletionLegacyCleanupIntent\\(editor\\)")
                .findAll(rollbackBody)
                .count()
        ).isEqualTo(2)
        assertThat(removeRollbackBody)
            .contains("ROLLBACK_DELETION_LEGACY_CLEANUP_STATE_KEY")
        assertThat(removeRollbackBody).contains("ROLLBACK_DELETION_LEGACY_CLEANUP_PREFIX")
    }

    @Test
    fun rollbackGuardRejectsMissingRestoreInEitherStateBranch() {
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()
        val rollbackBody = source.substringAfter("override suspend fun rollbackPromotion")
            .substringBefore("override suspend fun discardRollback")
        val restoreCall = "restoreDeletionMarker(editor, previousDeletionPending)"
        val activeRestoreIndex = rollbackBody.lastIndexOf(restoreCall)

        assertThrows(AssertionError::class.java) {
            assertRollbackBranchesRestoreDeletionMarker(
                rollbackBody.replaceFirst(restoreCall, "")
            )
        }
        assertThat(activeRestoreIndex).isAtLeast(0)
        assertThrows(AssertionError::class.java) {
            assertRollbackBranchesRestoreDeletionMarker(
                rollbackBody.removeRange(
                    activeRestoreIndex,
                    activeRestoreIndex + restoreCall.length
                )
            )
        }
    }

    @Test
    fun everyKeystoreOperationUsesInterruptibleIoWithoutCustomThreads() {
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()

        assertThat(source).contains("runInterruptible(Dispatchers.IO)")
        listOf(
            "stagePending",
            "readPending",
            "readPendingLegacyCleanupIntent",
            "commitPending",
            "rollbackPromotion",
            "discardRollback",
            "clearPending",
            "readLegacyCleanupIntent",
            "clearLegacyCleanupIntent",
            "markDeletionPending",
            "isDeletionPending",
            "readPendingDeletionLegacyCleanupIntent",
            "read",
            "clear"
        ).forEach { operation ->
            val body = source.substringAfter("override suspend fun $operation")
                .substringBefore("override suspend fun")
            assertThat(body).contains("interruptibleIo")
        }
        assertThat(source).doesNotContain("Executors.")
        assertThat(source).doesNotContain("newSingleThreadContext")
        assertThat(source).doesNotContain("Thread(")
    }

    @Test
    fun contextConstructorDefersPreferencesAndKeystoreInitialization() {
        val source = sourceFile(
            "src/main/kotlin/io/aaps/copilot/security/KeystoreSecretStorage.kt"
        ).readText()
        val contextConstructor = source.substringAfter(
            "constructor(context: Context, namespace: SecretStorageNamespace)"
        ).substringBefore("internal constructor")

        assertThat(contextConstructor).contains("preferencesProvider = {")
        assertThat(contextConstructor).contains("keyStoreProvider = {")
        assertThat(source).contains("private val preferences: SharedPreferences by lazy")
        assertThat(source).contains("private val keyStore: KeyStore by lazy")
    }

    private fun assertSecureClearKeepsDeletionTombstone(clearBody: String) {
        val aliasDeletionIndex = clearBody.indexOf(
            "keyStore.deleteEntry(namespace.keyAlias)"
        )
        val preferencesClearIndex = clearBody.indexOf(".clear()")
        val tombstoneWriteIndex = clearBody.indexOf(
            "putBoolean(DELETION_PENDING_KEY, true)"
        )
        val commitIndex = clearBody.indexOf("commitDurablyWithinGuard")

        assertThat(aliasDeletionIndex).isAtLeast(0)
        assertThat(preferencesClearIndex).isAtLeast(0)
        assertThat(tombstoneWriteIndex).isAtLeast(0)
        assertThat(commitIndex).isAtLeast(0)
        assertThat(preferencesClearIndex).isLessThan(tombstoneWriteIndex)
        assertThat(tombstoneWriteIndex).isLessThan(commitIndex)
        assertThat(commitIndex).isLessThan(aliasDeletionIndex)
    }

    private fun assertRollbackBranchesRestoreDeletionMarker(rollbackBody: String) {
        val absentBranchStart = rollbackBody.indexOf("ROLLBACK_STATE_ABSENT -> {")
        val activeBranchStart = rollbackBody.indexOf("ROLLBACK_STATE_ACTIVE -> {")
        val activeBranchEnd = rollbackBody.indexOf("else -> error", activeBranchStart)

        assertThat(absentBranchStart).isAtLeast(0)
        assertThat(activeBranchStart).isGreaterThan(absentBranchStart)
        assertThat(activeBranchEnd).isGreaterThan(activeBranchStart)

        val absentBranch = rollbackBody.substring(absentBranchStart, activeBranchStart)
        val activeBranch = rollbackBody.substring(activeBranchStart, activeBranchEnd)
        val restoreCall = "restoreDeletionMarker(editor, previousDeletionPending)"

        assertThat(absentBranch).contains(restoreCall)
        assertThat(activeBranch).contains(restoreCall)
    }

    private fun sourceFile(relativePath: String): File =
        File(requireNotNull(System.getProperty("user.dir")), relativePath)
}
