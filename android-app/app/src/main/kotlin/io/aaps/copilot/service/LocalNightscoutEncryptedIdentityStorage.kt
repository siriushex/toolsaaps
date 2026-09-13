package io.aaps.copilot.service

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.aaps.copilot.security.DeletionDurabilityCoordinator
import io.aaps.copilot.security.RuntimeSecretDeletionDurabilityCoordinators
import io.aaps.copilot.security.RuntimeSecretStorageNamespaces
import io.aaps.copilot.security.SecretStorageNamespace
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class LocalNightscoutEncryptedIdentityStorage internal constructor(
    private val preferences: SharedPreferences,
    private val resetTombstonePreferences: SharedPreferences,
    private val keyStore: KeyStore,
    private val namespace: SecretStorageNamespace,
    private val coordinator: DeletionDurabilityCoordinator,
    private val createKey: () -> SecretKey,
    private val deleteKey: () -> Unit
) : AtomicLocalNightscoutTlsIdentityStorage {

    constructor(context: Context) : this(contextDependencies(context.applicationContext))

    private constructor(dependencies: ContextDependencies) : this(
        preferences = dependencies.preferences,
        resetTombstonePreferences = dependencies.resetTombstonePreferences,
        keyStore = dependencies.keyStore,
        namespace = dependencies.namespace,
        coordinator = RuntimeSecretDeletionDurabilityCoordinators.coordinatorFor(
            dependencies.namespace
        ),
        createKey = dependencies.createKey,
        deleteKey = dependencies.deleteKey
    )

    override fun readOrCreate(create: () -> ByteArray): ByteArray = coordinator.withGuard {
        check(!resetTombstonePreferences.getBoolean(RESET_TOMBSTONE_KEY, false)) {
            "Local Nightscout identity reset is incomplete"
        }
        check(!preferences.getBoolean(RESET_PENDING_KEY, false)) {
            "Local Nightscout identity reset is incomplete"
        }
        check(!preferences.getBoolean(CREATION_PENDING_KEY, false)) {
            "Local Nightscout identity creation is incomplete"
        }
        readActiveRecord()?.let { return@withGuard it }
        check(preferences.all.isEmpty() && !keyStore.containsAlias(namespace.keyAlias)) {
            "Encrypted Local Nightscout identity state is incomplete"
        }

        val creationMarked = preferences.edit()
            .putBoolean(CREATION_PENDING_KEY, true)
            .commit()
        if (!creationMarked) {
            coordinator.quarantine()
            error("Unable to mark Local Nightscout identity creation")
        }

        val created = create()
        try {
            check(created.isNotEmpty()) { "Local Nightscout identity record is empty" }
            val encrypted = encrypt(created)
            val committed = preferences.edit()
                .putInt(ACTIVE_VERSION_KEY, RECORD_VERSION)
                .putString(ACTIVE_IV_KEY, encrypted.iv)
                .putString(ACTIVE_CIPHERTEXT_KEY, encrypted.ciphertext)
                .remove(CREATION_PENDING_KEY)
                .commit()
            if (!committed) {
                quarantineFailedIdentityWrite()
                error("Unable to persist encrypted Local Nightscout identity")
            }
            val persisted = try {
                requireNotNull(readActiveRecord()) {
                    "Encrypted Local Nightscout identity was not persisted"
                }.also { accepted ->
                    check(MessageDigest.isEqual(created, accepted)) {
                        "Encrypted Local Nightscout identity changed during persistence"
                    }
                }
            } catch (failure: Exception) {
                quarantineFailedIdentityWrite()
                throw failure
            }
            persisted
        } finally {
            created.fill(0)
        }
    }

    override fun reset() = coordinator.withGuard {
        try {
            val tombstoneMarked = resetTombstonePreferences.edit()
                .putBoolean(RESET_TOMBSTONE_KEY, true)
                .commit()
            if (!tombstoneMarked) {
                destroyIdentityKeyAfterTombstoneFailure()
                error("Unable to durably mark Local Nightscout identity reset")
            }
            val marked = preferences.edit().putBoolean(RESET_PENDING_KEY, true).commit()
            check(marked) { "Unable to mark Local Nightscout identity reset" }
            if (keyStore.containsAlias(namespace.keyAlias)) {
                deleteKey()
            }
            val cleared = preferences.edit().clear().commit()
            check(cleared) { "Unable to finish Local Nightscout identity reset" }
            check(!keyStore.containsAlias(namespace.keyAlias)) {
                "Local Nightscout identity key still exists after reset"
            }
            val tombstoneCleared = resetTombstonePreferences.edit().clear().commit()
            check(tombstoneCleared) { "Unable to clear Local Nightscout identity reset tombstone" }
        } catch (failure: Exception) {
            coordinator.quarantine()
            throw failure
        }
    }

    private fun destroyIdentityKeyAfterTombstoneFailure() {
        if (keyStore.containsAlias(namespace.keyAlias)) deleteKey()
        check(!keyStore.containsAlias(namespace.keyAlias)) {
            "Local Nightscout identity reset could not be quarantined"
        }
    }

    private fun quarantineFailedIdentityWrite() {
        coordinator.quarantine()
        if (keyStore.containsAlias(namespace.keyAlias)) {
            runCatching(deleteKey)
        }
    }

    private fun readActiveRecord(): ByteArray? {
        val keys = preferences.all.keys
        val present = ACTIVE_KEYS.count(preferences::contains)
        if (present == 0) {
            check(
                keys.isEmpty() ||
                    keys == setOf(RESET_PENDING_KEY) ||
                    keys == setOf(CREATION_PENDING_KEY)
            ) {
                "Encrypted Local Nightscout identity has unexpected state"
            }
            return null
        }
        check(keys == ACTIVE_KEYS) {
            "Encrypted Local Nightscout identity record is incomplete"
        }
        check(preferences.getInt(ACTIVE_VERSION_KEY, -1) == RECORD_VERSION) {
            "Encrypted Local Nightscout identity version is unsupported"
        }
        val iv = decode(
            preferences.getString(ACTIVE_IV_KEY, null)
                ?: error("Encrypted Local Nightscout identity IV is missing")
        )
        val ciphertext = decode(
            preferences.getString(ACTIVE_CIPHERTEXT_KEY, null)
                ?: error("Encrypted Local Nightscout identity ciphertext is missing")
        )
        try {
            check(iv.size == IV_SIZE_BYTES) {
                "Encrypted Local Nightscout identity IV is invalid"
            }
            check(ciphertext.isNotEmpty()) {
                "Encrypted Local Nightscout identity ciphertext is invalid"
            }
            val key = keyStore.getKey(namespace.keyAlias, null) as? SecretKey
                ?: error("Encrypted Local Nightscout identity key is unavailable")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, iv))
            return cipher.doFinal(ciphertext)
        } catch (failure: Exception) {
            throw IllegalStateException("Encrypted Local Nightscout identity is corrupt", failure)
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun encrypt(plaintext: ByteArray): EncodedRecord {
        val key = (keyStore.getKey(namespace.keyAlias, null) as? SecretKey) ?: createKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return try {
            EncodedRecord(
                iv = Base64.encodeToString(iv, Base64.NO_WRAP),
                ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            )
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun decode(value: String): ByteArray = try {
        Base64.decode(value, Base64.NO_WRAP)
    } catch (failure: IllegalArgumentException) {
        throw IllegalStateException("Encrypted Local Nightscout identity is corrupt", failure)
    }

    private data class EncodedRecord(val iv: String, val ciphertext: String)

    private data class ContextDependencies(
        val preferences: SharedPreferences,
        val resetTombstonePreferences: SharedPreferences,
        val keyStore: KeyStore,
        val namespace: SecretStorageNamespace,
        val createKey: () -> SecretKey,
        val deleteKey: () -> Unit
    )

    private companion object {
        const val ACTIVE_VERSION_KEY = "active_version"
        const val ACTIVE_IV_KEY = "active_iv"
        const val ACTIVE_CIPHERTEXT_KEY = "active_ciphertext"
        const val RESET_PENDING_KEY = "reset_pending"
        const val RESET_TOMBSTONE_KEY = "reset_tombstone"
        const val CREATION_PENDING_KEY = "creation_pending"
        const val RECORD_VERSION = 1
        const val IV_SIZE_BYTES = 12
        const val TAG_SIZE_BITS = 128
        const val KEY_SIZE_BITS = 256
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val ACTIVE_KEYS = setOf(ACTIVE_VERSION_KEY, ACTIVE_IV_KEY, ACTIVE_CIPHERTEXT_KEY)
        fun contextDependencies(context: Context): ContextDependencies {
            val namespace = RuntimeSecretStorageNamespaces.LOCAL_NIGHTSCOUT_TLS
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            return ContextDependencies(
                preferences = context.getSharedPreferences(
                    namespace.preferencesFile,
                    Context.MODE_PRIVATE
                ),
                resetTombstonePreferences = context.getSharedPreferences(
                    "${namespace.preferencesFile}_reset_tombstone",
                    Context.MODE_PRIVATE
                ),
                keyStore = keyStore,
                namespace = namespace,
                createKey = {
                    val generator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        ANDROID_KEYSTORE
                    )
                    generator.init(
                        KeyGenParameterSpec.Builder(
                            namespace.keyAlias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(KEY_SIZE_BITS)
                            .setRandomizedEncryptionRequired(true)
                            .setUserAuthenticationRequired(false)
                            .build()
                    )
                    generator.generateKey()
                },
                deleteKey = { keyStore.deleteEntry(namespace.keyAlias) }
            )
        }
    }
}
