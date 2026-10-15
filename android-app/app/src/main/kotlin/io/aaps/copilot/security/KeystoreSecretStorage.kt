package io.aaps.copilot.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.aaps.copilot.config.ClinicalAiProviderId
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

data class SecretStorageNamespace(
    val preferencesFile: String,
    val keyAlias: String
)

internal class DeletionDurabilityCoordinatorRegistry {
    private val openAiCoordinator = DeletionDurabilityCoordinator()
    private val anthropicCoordinator = DeletionDurabilityCoordinator()
    private val geminiCoordinator = DeletionDurabilityCoordinator()
    private val openAiCompatibleCoordinator = DeletionDurabilityCoordinator()
    private val localNightscoutTlsCoordinator = DeletionDurabilityCoordinator()
    private val serverAiConnectionCoordinator = DeletionDurabilityCoordinator()
    private val telegramCoordinator = DeletionDurabilityCoordinator()

    fun coordinatorFor(
        namespace: SecretStorageNamespace
    ): DeletionDurabilityCoordinator = when (namespace) {
        ClinicalAiSecretStorageNamespaces.OPENAI -> openAiCoordinator
        ClinicalAiSecretStorageNamespaces.ANTHROPIC -> anthropicCoordinator
        ClinicalAiSecretStorageNamespaces.GEMINI -> geminiCoordinator
        ClinicalAiSecretStorageNamespaces.OPENAI_COMPATIBLE ->
            openAiCompatibleCoordinator
        RuntimeSecretStorageNamespaces.LOCAL_NIGHTSCOUT_TLS ->
            localNightscoutTlsCoordinator
        RuntimeSecretStorageNamespaces.SERVER_AI_CONNECTION -> serverAiConnectionCoordinator
        RuntimeSecretStorageNamespaces.TELEGRAM -> telegramCoordinator
        else -> error("Unsupported encrypted secret namespace")
    }
}

internal object RuntimeSecretDeletionDurabilityCoordinators {
    private val registry = DeletionDurabilityCoordinatorRegistry()

    fun coordinatorFor(namespace: SecretStorageNamespace): DeletionDurabilityCoordinator =
        registry.coordinatorFor(namespace)
}

internal class DeletionDurabilityCoordinator {
    private val lock = ReentrantLock()
    private var quarantined = false

    fun <T> withGuard(block: () -> T): T {
        lock.lockInterruptibly()
        return try {
            check(!quarantined) {
                "Credential deletion durability is unavailable"
            }
            block()
        } finally {
            lock.unlock()
        }
    }

    fun quarantine() {
        check(lock.isHeldByCurrentThread) {
            "Credential deletion durability guard is not held"
        }
        quarantined = true
    }

    internal fun hasQueuedThreadsForTest(): Boolean = lock.hasQueuedThreads()
}

internal object RuntimeSecretStorageNamespaces {
    val TELEGRAM = SecretStorageNamespace(
        preferencesFile = "telegram_trusted_delivery",
        keyAlias = "io.aaps.predictivecopilot.telegram.aes_gcm.v1"
    )
    val SERVER_AI_CONNECTION = SecretStorageNamespace(
        preferencesFile = "server_ai_connection",
        keyAlias = "io.aaps.predictivecopilot.server_ai.connection.aes_gcm.v1"
    )
    val LOCAL_NIGHTSCOUT_TLS = SecretStorageNamespace(
        preferencesFile = "local_nightscout_tls_identity",
        keyAlias = "io.aaps.predictivecopilot.local_nightscout_tls.aes_gcm.v1"
    )
}

object ClinicalAiSecretStorageNamespaces {
    val OPENAI = SecretStorageNamespace(
        preferencesFile = "openai_credentials",
        keyAlias = "io.aaps.predictivecopilot.openai.aes_gcm.v1"
    )
    val ANTHROPIC = SecretStorageNamespace(
        preferencesFile = "clinical_ai_anthropic_credentials",
        keyAlias = "io.aaps.predictivecopilot.anthropic.aes_gcm.v1"
    )
    val GEMINI = SecretStorageNamespace(
        preferencesFile = "clinical_ai_gemini_credentials",
        keyAlias = "io.aaps.predictivecopilot.gemini.aes_gcm.v1"
    )
    val OPENAI_COMPATIBLE = SecretStorageNamespace(
        preferencesFile = "clinical_ai_compatible_credentials",
        keyAlias = "io.aaps.predictivecopilot.compatible.aes_gcm.v1"
    )

    fun forProvider(providerId: ClinicalAiProviderId): SecretStorageNamespace =
        when (providerId) {
            ClinicalAiProviderId.OPENAI -> OPENAI
            ClinicalAiProviderId.ANTHROPIC -> ANTHROPIC
            ClinicalAiProviderId.GEMINI -> GEMINI
            ClinicalAiProviderId.OPENAI_COMPATIBLE -> OPENAI_COMPATIBLE
        }
}

class KeystoreSecretStorage private constructor(
    preferencesProvider: () -> SharedPreferences,
    keyStoreProvider: () -> KeyStore,
    private val namespace: SecretStorageNamespace,
    private val deletionDurabilityCoordinator: DeletionDurabilityCoordinator
) : SecretStorage {
    private val preferences: SharedPreferences by lazy {
        preferencesProvider()
    }
    private val keyStore: KeyStore by lazy {
        keyStoreProvider()
    }
    constructor(context: Context) : this(context, ClinicalAiSecretStorageNamespaces.OPENAI)

    constructor(context: Context, namespace: SecretStorageNamespace) : this(
        preferencesProvider = {
            context.getSharedPreferences(
                namespace.preferencesFile,
                Context.MODE_PRIVATE
            )
        },
        keyStoreProvider = {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        },
        namespace = namespace,
        deletionDurabilityCoordinator =
            RuntimeSecretDeletionDurabilityCoordinators.coordinatorFor(namespace)
    )

    override suspend fun stagePending(
        value: String,
        legacyCleanupExpected: String?
    ) = interruptibleIo {
        val pending = encryptRecord(value)
        val pendingLegacyCleanup = legacyCleanupExpected?.let {
            encryptRecord(legacyCleanupExpected)
        }
        val editor = preferences.edit()
        putRecord(editor, PENDING_PREFIX, pending)
        if (pendingLegacyCleanup == null) {
            removeRecord(editor, PENDING_LEGACY_CLEANUP_PREFIX)
        } else {
            putRecord(editor, PENDING_LEGACY_CLEANUP_PREFIX, pendingLegacyCleanup)
        }
        commitDurably(editor, "Unable to persist pending encrypted credential")
    }

    private fun encryptRecord(value: String): EncodedRecord {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        check(iv.size == IV_SIZE_BYTES) { "Generated credential IV is invalid" }
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return EncodedRecord(
            version = RECORD_VERSION,
            iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        )
    }

    override suspend fun readPending(): String? = interruptibleIo {
        readRecord(PENDING_PREFIX)
    }

    override suspend fun readPendingLegacyCleanupIntent(): String? = interruptibleIo {
        readRecord(PENDING_LEGACY_CLEANUP_PREFIX)
    }

    override suspend fun commitPending() = interruptibleIo {
        check(!readRecord(PENDING_PREFIX).isNullOrBlank()) {
            "Pending encrypted credential is invalid"
        }
        val pending = requireNotNull(readEncodedRecord(PENDING_PREFIX)) {
            "Pending encrypted credential is missing"
        }
        val pendingLegacyCleanup = readEncodedRecord(PENDING_LEGACY_CLEANUP_PREFIX)
        if (pendingLegacyCleanup != null) {
            check(!readRecord(PENDING_LEGACY_CLEANUP_PREFIX).isNullOrBlank()) {
                "Pending encrypted legacy cleanup intent is invalid"
            }
        }
        val previousActive = readEncodedRecord(ACTIVE_PREFIX)
        val previousLegacyCleanup = readEncodedRecord(LEGACY_CLEANUP_PREFIX)
        val previousDeletionPending = preferences.getBoolean(DELETION_PENDING_KEY, false)
        val previousDeletionLegacyCleanup = readEncodedRecord(DELETION_LEGACY_CLEANUP_PREFIX)
        val editor = preferences.edit()
        if (previousActive == null) {
            editor.putInt(ROLLBACK_STATE_KEY, ROLLBACK_STATE_ABSENT)
            removeRecord(editor, ROLLBACK_PREFIX)
        } else {
            editor.putInt(ROLLBACK_STATE_KEY, ROLLBACK_STATE_ACTIVE)
            putRecord(editor, ROLLBACK_PREFIX, previousActive)
        }
        if (previousLegacyCleanup == null) {
            editor.putInt(
                ROLLBACK_LEGACY_CLEANUP_STATE_KEY,
                ROLLBACK_STATE_ABSENT
            )
            removeRecord(editor, ROLLBACK_LEGACY_CLEANUP_PREFIX)
        } else {
            editor.putInt(
                ROLLBACK_LEGACY_CLEANUP_STATE_KEY,
                ROLLBACK_STATE_ACTIVE
            )
            putRecord(editor, ROLLBACK_LEGACY_CLEANUP_PREFIX, previousLegacyCleanup)
        }
        editor.putBoolean(ROLLBACK_DELETION_PENDING_KEY, previousDeletionPending)
        if (previousDeletionLegacyCleanup == null) {
            editor.putInt(
                ROLLBACK_DELETION_LEGACY_CLEANUP_STATE_KEY,
                ROLLBACK_STATE_ABSENT
            )
            removeRecord(editor, ROLLBACK_DELETION_LEGACY_CLEANUP_PREFIX)
        } else {
            editor.putInt(
                ROLLBACK_DELETION_LEGACY_CLEANUP_STATE_KEY,
                ROLLBACK_STATE_ACTIVE
            )
            putRecord(
                editor,
                ROLLBACK_DELETION_LEGACY_CLEANUP_PREFIX,
                previousDeletionLegacyCleanup
            )
        }
        putRecord(editor, ACTIVE_PREFIX, pending)
        if (pendingLegacyCleanup == null) {
            removeRecord(editor, LEGACY_CLEANUP_PREFIX)
        } else {
            putRecord(editor, LEGACY_CLEANUP_PREFIX, pendingLegacyCleanup)
        }
        removeRecord(editor, PENDING_PREFIX)
        removeRecord(editor, PENDING_LEGACY_CLEANUP_PREFIX)
        editor.remove(DELETION_PENDING_KEY)
        removeRecord(editor, DELETION_LEGACY_CLEANUP_PREFIX)
        commitDurably(editor, "Unable to promote encrypted credential")
    }

    override suspend fun rollbackPromotion() = interruptibleIo {
        val previousDeletionPending =
            preferences.getBoolean(ROLLBACK_DELETION_PENDING_KEY, false)
        when (val state = preferences.getInt(ROLLBACK_STATE_KEY, ROLLBACK_STATE_NONE)) {
            ROLLBACK_STATE_NONE -> return@interruptibleIo
            ROLLBACK_STATE_ABSENT -> {
                val editor = preferences.edit()
                removeRecord(editor, ACTIVE_PREFIX)
                restoreLegacyCleanupIntent(editor)
                restoreDeletionLegacyCleanupIntent(editor)
                removeRollback(editor)
                restoreDeletionMarker(editor, previousDeletionPending)
                commitDurably(
                    editor,
                    "Unable to restore encrypted credential absence"
                )
            }
            ROLLBACK_STATE_ACTIVE -> {
                val rollback = requireNotNull(readEncodedRecord(ROLLBACK_PREFIX)) {
                    "Encrypted credential rollback is missing"
                }
                val editor = preferences.edit()
                putRecord(editor, ACTIVE_PREFIX, rollback)
                restoreLegacyCleanupIntent(editor)
                restoreDeletionLegacyCleanupIntent(editor)
                removeRollback(editor)
                restoreDeletionMarker(editor, previousDeletionPending)
                commitDurably(editor, "Unable to restore encrypted credential")
            }
            else -> error("Encrypted credential rollback state is invalid: $state")
        }
    }

    override suspend fun discardRollback() = interruptibleIo {
        val editor = preferences.edit()
        removeRollback(editor)
        commitDurably(editor, "Unable to discard encrypted credential rollback")
    }

    override suspend fun clearPending() = interruptibleIo {
        val editor = preferences.edit()
        removeRecord(editor, PENDING_PREFIX)
        removeRecord(editor, PENDING_LEGACY_CLEANUP_PREFIX)
        commitDurably(editor, "Unable to clear pending encrypted credential")
    }

    override suspend fun readLegacyCleanupIntent(): String? = interruptibleIo {
        readRecord(LEGACY_CLEANUP_PREFIX)
    }

    override suspend fun clearLegacyCleanupIntent() = interruptibleIo {
        val editor = preferences.edit()
        removeRecord(editor, LEGACY_CLEANUP_PREFIX)
        commitDurably(editor, "Unable to clear encrypted legacy cleanup intent")
    }

    override suspend fun markDeletionPending(legacyCleanupExpected: String?) = interruptibleIo {
        withDeletionDurabilityGuard {
            val encryptedExpected = legacyCleanupExpected?.let {
                encryptRecord(legacyCleanupExpected)
            }
            val editor = preferences.edit()
                .putBoolean(DELETION_PENDING_KEY, true)
            if (encryptedExpected == null) {
                removeRecord(editor, DELETION_LEGACY_CLEANUP_PREFIX)
            } else {
                putRecord(editor, DELETION_LEGACY_CLEANUP_PREFIX, encryptedExpected)
            }
            commitDurablyWithinGuard(
                editor,
                "Unable to persist credential deletion marker"
            )
        }
    }

    override suspend fun isDeletionPending(): Boolean = interruptibleIo {
        withDeletionDurabilityGuard {
            preferences.getBoolean(DELETION_PENDING_KEY, false)
        }
    }

    override suspend fun readPendingDeletionLegacyCleanupIntent(): String? = interruptibleIo {
        withDeletionDurabilityGuard {
            readRecord(DELETION_LEGACY_CLEANUP_PREFIX)
        }
    }

    override suspend fun read(): String? = interruptibleIo {
        readRecord(ACTIVE_PREFIX)
    }

    override suspend fun clear() = interruptibleIo {
        withDeletionDurabilityGuard {
            val editor = preferences.edit()
                .clear()
                .putBoolean(DELETION_PENDING_KEY, true)
            commitDurablyWithinGuard(editor, "Unable to clear encrypted credential")
            if (keyStore.containsAlias(namespace.keyAlias)) {
                keyStore.deleteEntry(namespace.keyAlias)
            }
        }
    }

    private suspend fun <T> interruptibleIo(block: () -> T): T =
        runInterruptible(Dispatchers.IO) {
            block()
        }

    private fun <T> withDeletionDurabilityGuard(block: () -> T): T =
        deletionDurabilityCoordinator.withGuard(block)

    private fun commitDurably(
        editor: SharedPreferences.Editor,
        failureMessage: String
    ) {
        withDeletionDurabilityGuard {
            commitDurablyWithinGuard(editor, failureMessage)
        }
    }

    private fun commitDurablyWithinGuard(
        editor: SharedPreferences.Editor,
        failureMessage: String
    ) {
        val committed = try {
            editor.commit()
        } catch (failure: Throwable) {
            quarantineDeletionState()
            throw failure
        }
        if (!committed) {
            quarantineDeletionState()
            error(failureMessage)
        }
    }

    private fun quarantineDeletionState() {
        deletionDurabilityCoordinator.quarantine()
    }

    private fun readRecord(prefix: String): String? {
        val record = readEncodedRecord(prefix) ?: return null
        val iv = decode(record.iv)
        val ciphertext = decode(record.ciphertext)
        check(iv.size == IV_SIZE_BYTES) { "Encrypted credential IV is invalid" }
        check(ciphertext.isNotEmpty()) { "Encrypted credential ciphertext is invalid" }

        val key = keyStore.getKey(namespace.keyAlias, null) as? SecretKey
            ?: error("Encrypted credential key is unavailable")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun readEncodedRecord(prefix: String): EncodedRecord? {
        val versionKey = key(prefix, VERSION_SUFFIX)
        val ivKey = key(prefix, IV_SUFFIX)
        val ciphertextKey = key(prefix, CIPHERTEXT_SUFFIX)
        val presentCount = listOf(versionKey, ivKey, ciphertextKey).count(preferences::contains)
        if (presentCount == 0) return null
        check(presentCount == RECORD_FIELD_COUNT) {
            "Encrypted credential record is incomplete"
        }

        val version = preferences.getInt(versionKey, INVALID_VERSION)
        check(version == RECORD_VERSION) { "Unsupported encrypted credential record" }
        return EncodedRecord(
            version = version,
            iv = preferences.getString(ivKey, null)
                ?: error("Encrypted credential IV is missing"),
            ciphertext = preferences.getString(ciphertextKey, null)
                ?: error("Encrypted credential ciphertext is missing")
        )
    }

    private fun putRecord(
        editor: SharedPreferences.Editor,
        prefix: String,
        record: EncodedRecord
    ) {
        editor
            .putInt(key(prefix, VERSION_SUFFIX), record.version)
            .putString(key(prefix, IV_SUFFIX), record.iv)
            .putString(key(prefix, CIPHERTEXT_SUFFIX), record.ciphertext)
    }

    private fun removeRecord(editor: SharedPreferences.Editor, prefix: String) {
        editor
            .remove(key(prefix, VERSION_SUFFIX))
            .remove(key(prefix, IV_SUFFIX))
            .remove(key(prefix, CIPHERTEXT_SUFFIX))
    }

    private fun removeRollback(editor: SharedPreferences.Editor) {
        editor.remove(ROLLBACK_STATE_KEY)
        editor.remove(ROLLBACK_DELETION_PENDING_KEY)
        editor.remove(ROLLBACK_LEGACY_CLEANUP_STATE_KEY)
        editor.remove(ROLLBACK_DELETION_LEGACY_CLEANUP_STATE_KEY)
        removeRecord(editor, ROLLBACK_PREFIX)
        removeRecord(editor, ROLLBACK_LEGACY_CLEANUP_PREFIX)
        removeRecord(editor, ROLLBACK_DELETION_LEGACY_CLEANUP_PREFIX)
    }

    private fun restoreLegacyCleanupIntent(editor: SharedPreferences.Editor) {
        when (
            val state = preferences.getInt(
                ROLLBACK_LEGACY_CLEANUP_STATE_KEY,
                ROLLBACK_STATE_ABSENT
            )
        ) {
            ROLLBACK_STATE_ABSENT -> removeRecord(editor, LEGACY_CLEANUP_PREFIX)
            ROLLBACK_STATE_ACTIVE -> {
                val rollback = requireNotNull(
                    readEncodedRecord(ROLLBACK_LEGACY_CLEANUP_PREFIX)
                ) { "Encrypted legacy cleanup intent rollback is missing" }
                putRecord(editor, LEGACY_CLEANUP_PREFIX, rollback)
            }
            else -> error("Encrypted legacy cleanup rollback state is invalid: $state")
        }
    }

    private fun restoreDeletionLegacyCleanupIntent(editor: SharedPreferences.Editor) {
        when (
            val state = preferences.getInt(
                ROLLBACK_DELETION_LEGACY_CLEANUP_STATE_KEY,
                ROLLBACK_STATE_ABSENT
            )
        ) {
            ROLLBACK_STATE_ABSENT ->
                removeRecord(editor, DELETION_LEGACY_CLEANUP_PREFIX)
            ROLLBACK_STATE_ACTIVE -> {
                val rollback = requireNotNull(
                    readEncodedRecord(ROLLBACK_DELETION_LEGACY_CLEANUP_PREFIX)
                ) { "Encrypted deletion legacy cleanup rollback is missing" }
                putRecord(editor, DELETION_LEGACY_CLEANUP_PREFIX, rollback)
            }
            else -> error(
                "Encrypted deletion legacy cleanup rollback state is invalid: $state"
            )
        }
    }

    private fun restoreDeletionMarker(
        editor: SharedPreferences.Editor,
        deletionPending: Boolean
    ) {
        if (deletionPending) {
            editor.putBoolean(DELETION_PENDING_KEY, true)
        } else {
            editor.remove(DELETION_PENDING_KEY)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(namespace.keyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val parameters = KeyGenParameterSpec.Builder(
            namespace.keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(parameters)
        return generator.generateKey()
    }

    private fun decode(value: String): ByteArray = try {
        Base64.decode(value, Base64.NO_WRAP)
    } catch (failure: IllegalArgumentException) {
        throw IllegalStateException("Encrypted credential record is corrupt", failure)
    }

    private fun key(prefix: String, suffix: String): String = "${prefix}_$suffix"

    private data class EncodedRecord(
        val version: Int,
        val iv: String,
        val ciphertext: String
    )

    internal companion object {
        const val PREFERENCES_FILE = "openai_credentials"
        const val KEY_ALIAS = "io.aaps.predictivecopilot.openai.aes_gcm.v1"
        const val ACTIVE_PREFIX = "active"
        const val PENDING_PREFIX = "pending"
        const val ROLLBACK_PREFIX = "rollback"
        const val PENDING_LEGACY_CLEANUP_PREFIX = "pending_legacy_cleanup"
        const val LEGACY_CLEANUP_PREFIX = "legacy_cleanup"
        const val ROLLBACK_LEGACY_CLEANUP_PREFIX = "rollback_legacy_cleanup"
        const val DELETION_LEGACY_CLEANUP_PREFIX = "deletion_legacy_cleanup"
        const val ROLLBACK_DELETION_LEGACY_CLEANUP_PREFIX =
            "rollback_deletion_legacy_cleanup"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION_SUFFIX = "version"
        private const val IV_SUFFIX = "iv"
        private const val CIPHERTEXT_SUFFIX = "ciphertext"
        private const val ROLLBACK_STATE_KEY = "rollback_state"
        private const val ROLLBACK_DELETION_PENDING_KEY = "rollback_deletion_pending"
        private const val ROLLBACK_LEGACY_CLEANUP_STATE_KEY =
            "rollback_legacy_cleanup_state"
        private const val ROLLBACK_DELETION_LEGACY_CLEANUP_STATE_KEY =
            "rollback_deletion_legacy_cleanup_state"
        private const val DELETION_PENDING_KEY = "deletion_pending"
        private const val RECORD_VERSION = 1
        private const val INVALID_VERSION = -1
        private const val ROLLBACK_STATE_NONE = 0
        private const val ROLLBACK_STATE_ABSENT = 1
        private const val ROLLBACK_STATE_ACTIVE = 2
        private const val RECORD_FIELD_COUNT = 3
        private const val IV_SIZE_BYTES = 12
        private const val TAG_SIZE_BITS = 128
        private const val KEY_SIZE_BITS = 256
    }
}
