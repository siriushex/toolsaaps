package io.aaps.copilot.security

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
import java.util.UUID

internal object ServerAiRequestProof {
    private fun hash(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it) }

    fun signingBytes(method: String, path: String, body: ByteArray, credential: String,
                     issuedMs: Long, nonce: String): ByteArray {
        require(method in setOf("GET", "POST", "DELETE")) { "invalid_request" }
        require(Regex("/api/ai/v1/[a-zA-Z0-9/-]{1,180}").matches(path)) { "invalid_request" }
        require(body.size <= 8_388_608) { "invalid_request" }
        require(credential.length in 1..256 && credential.all { it.code < 128 }) { "invalid_request" }
        require(issuedMs > 60_000 && issuedMs < Long.MAX_VALUE - 60_000) { "invalid_request" }
        require(runCatching { UUID.fromString(nonce).toString() == nonce }.getOrDefault(false)) { "invalid_request" }
        // Fixed ASCII fields in Python sort_keys order; credentials/body appear only as hashes.
        return ("{\"aud\":\"https://diai.centv.ru\",\"body_sha256\":\"${hash(body)}\"," +
            "\"credential_sha256\":\"${hash(credential.toByteArray(Charsets.US_ASCII))}\"," +
            "\"issued_ms\":$issuedMs,\"method\":\"$method\",\"nonce\":\"$nonce\"," +
            "\"path\":\"$path\",\"v\":1}").toByteArray(Charsets.US_ASCII)
    }

    fun sign(key: PrivateKey, message: ByteArray): String {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(key)
        signature.update(message)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign())
    }
}
