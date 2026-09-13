package io.aaps.copilot.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.annotation.WorkerThread
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

internal interface ServerAiKeyBackend {
    fun exists(): Boolean
    fun create(challenge: ByteArray)
    fun hardwareBacked(): Boolean
    fun certificateChain(): List<String>
    fun sign(message: ByteArray): String
    fun delete()
}

internal class ServerAiDeviceKey(private val backend: ServerAiKeyBackend = AndroidServerAiKeyBackend()) {
    companion object { private val lock = Any() }

    @WorkerThread
    fun create(challenge: ByteArray): List<String> = synchronized(lock) {
        require(challenge.size == 32) { "invalid_attestation_challenge" }
        check(!backend.exists()) { "device_key_already_exists" }
        try {
            backend.create(challenge.copyOf())
            check(backend.hardwareBacked()) { "hardware_key_required" }
            backend.certificateChain().also { check(it.isNotEmpty()) { "attestation_unavailable" } }
        } catch (error: Exception) {
            // Only a key created by this failed attempt may be removed. An
            // existing enrolled key was rejected before entering this block.
            backend.delete()
            throw error
        }
    }

    @WorkerThread
    fun existingChain(): List<String>? = synchronized(lock) {
        if (!backend.exists()) return@synchronized null
        check(backend.hardwareBacked()) { "hardware_key_required" }
        backend.certificateChain()
    }

    @WorkerThread
    fun sign(message: ByteArray): String = synchronized(lock) {
        require(message.size in 1..2048) { "invalid_proof_message" }
        check(backend.exists()) { "device_key_missing" }
        check(backend.hardwareBacked()) { "hardware_key_required" }
        backend.sign(message.copyOf())
    }
}

private class AndroidServerAiKeyBackend : ServerAiKeyBackend {
    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "io.aaps.predictivecopilot.server_ai.device_key.v1"
    }
    private fun store() = KeyStore.getInstance(PROVIDER).apply { load(null) }
    private fun key() = store().getKey(ALIAS, null) as? PrivateKey ?: error("device_key_missing")
    override fun exists() = store().containsAlias(ALIAS)

    override fun create(challenge: ByteArray) {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        generator.initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .build())
        generator.generateKeyPair()
    }

    override fun hardwareBacked(): Boolean {
        val info = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
            .getKeySpec(key(), KeyInfo::class.java)
        return info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
            info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
    }

    override fun certificateChain(): List<String> {
        val chain = store().getCertificateChain(ALIAS) ?: error("attestation_unavailable")
        check(chain.size in 1..8) { "attestation_size_limit" }
        val encoded = chain.map { it.encoded }
        check(encoded.all { it.size <= 16384 } && encoded.sumOf { it.size } <= 65536) { "attestation_size_limit" }
        return encoded.map { Base64.getEncoder().encodeToString(it) }
    }

    override fun sign(message: ByteArray) = ServerAiRequestProof.sign(key(), message)
    override fun delete() = store().deleteEntry(ALIAS)
}
