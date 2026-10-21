package io.aaps.copilot.service

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface AapsV3JournalCipher {
    fun seal(row: String, revision: Long, plain: ByteArray): ByteArray
    fun open(row: String, revision: Long, sealed: ByteArray): ByteArray
}

/** Separate non-exportable device key. Existing ciphertext never creates a replacement key. */
internal class KeystoreAapsV3JournalCipher(
    private val keyProvider: (Boolean) -> SecretKey = ::androidKey
) : AapsV3JournalCipher {
    override fun seal(row: String, revision: Long, plain: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, keyProvider(true))
            check(iv.size == 12)
            updateAAD(aad(row, revision))
            byteArrayOf(1) + iv + doFinal(plain)
        }

    override fun open(row: String, revision: Long, sealed: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            check(sealed.size >= 30 && sealed[0] == 1.toByte())
            init(Cipher.DECRYPT_MODE, keyProvider(false), GCMParameterSpec(128, sealed.copyOfRange(1, 13)))
            updateAAD(aad(row, revision))
            doFinal(sealed.copyOfRange(13, sealed.size))
        }

    private fun aad(row: String, revision: Long) = "aaps-v3-target-journal-v1|$row|$revision".toByteArray(Charsets.UTF_8)
    override fun toString() = "KeystoreAapsV3JournalCipher(redacted)"
    private companion object {
        const val ALIAS = "io.aaps.predictivecopilot.native_target_journal.aes_gcm.v1"
        fun androidKey(create: Boolean): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(ALIAS)) return store.getKey(ALIAS, null) as SecretKey
            check(create)
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).setKeySize(256).build())
            }.generateKey()
        }
    }
}
