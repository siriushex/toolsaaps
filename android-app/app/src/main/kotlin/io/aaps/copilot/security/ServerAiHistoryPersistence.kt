package io.aaps.copilot.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Separate limits: observational upload/query 128/64 KiB; archive read 4/128 KiB. */
internal enum class ServerAiHistoryRoute(val path: String, val requestLimit: Int, val responseLimit: Int) {
    BATCHES("/api/ai/v1/history/batches", 131_072, 65_536),
    QUERY("/api/ai/v1/history/query", 131_072, 65_536),
    CONVERSATIONS("/api/ai/v1/history/conversations", 4_096, 131_072)
}

internal interface ServerAiHistoryPersistence {
    suspend fun read(): String?
    suspend fun write(value: String)
}

internal object UnavailableHistoryPersistence : ServerAiHistoryPersistence {
    override suspend fun read(): String? = throw ServerAiConnectionFailure(ServerAiConnectionError.STORAGE)
    override suspend fun write(value: String): Unit = throw ServerAiConnectionFailure(ServerAiConnectionError.STORAGE)
}

/** Atomic encrypted checkpoint, separate from credential storage and its unchanged limits. */
internal class EncryptedServerAiHistoryPersistence(context: Context) : ServerAiHistoryPersistence {
    private val file by lazy { AtomicFile(File(context.noBackupFilesDir, "server_ai_history_pending_v1.bin")) }

    override suspend fun read(): String? = guarded {
        val raw = readSealed() ?: return@guarded null
        check(raw.size in 29..MAX_SEALED_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Never generate a replacement key to read existing pending observations.
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        cipher.updateAAD(AAD)
        val plain = cipher.doFinal(raw.copyOfRange(12, raw.size))
        check(plain.size <= MAX_PLAIN_BYTES)
        io.aaps.copilot.data.repository.HistoryJson.decodeUtf8(plain)
    }

    override suspend fun write(value: String) = guarded {
        val plain = io.aaps.copilot.data.repository.HistoryJson.encodeUtf8(value)
        check(plain.size <= MAX_PLAIN_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        check(cipher.iv.size == 12)
        cipher.updateAAD(AAD)
        val sealed = cipher.iv + cipher.doFinal(plain)
        val stream = file.startWrite()
        try {
            stream.write(sealed)
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
        check(readSealed()?.contentEquals(sealed) == true)
    }

    private fun readSealed(): ByteArray? {
        val stream = try { file.openRead() }
        catch (failure: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw failure
            return null
        }
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= MAX_SEALED_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) return store.getKey(ALIAS, null) as SecretKey
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build())
        }.generateKey()
    }

    private suspend fun <T> guarded(block: () -> T): T = withContext(Dispatchers.IO) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw ServerAiConnectionFailure(ServerAiConnectionError.STORAGE) }
    }

    override fun toString() = "EncryptedServerAiHistoryPersistence(redacted)"

    private companion object {
        const val ALIAS = "io.aaps.predictivecopilot.server_ai.history_pending.aes_gcm.v1"
        const val MAX_PLAIN_BYTES = 1_048_576
        const val MAX_SEALED_BYTES = MAX_PLAIN_BYTES + 28
        val AAD = "server-ai-history-pending-v1".toByteArray(Charsets.US_ASCII)
    }
}
