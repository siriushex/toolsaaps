package io.aaps.copilot.service

import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal class LocalNightscoutApiAuthenticator private constructor(
    expectedSha1Hex: ByteArray,
    expectedRawSha256: ByteArray
) {
    private val expected = expectedSha1Hex.copyOf()
    private val rawDigest = expectedRawSha256.copyOf()
    private var closed = false

    @Synchronized
    fun accepts(candidate: String?): Boolean {
        val canonical = candidate?.takeIf(::isCanonicalSha1Hex)
        val candidateBytes = canonical
            ?.toByteArray(StandardCharsets.US_ASCII)
            ?: ByteArray(SHA1_HEX_LENGTH)
        return try {
            val equal = MessageDigest.isEqual(expected, candidateBytes)
            !closed && canonical != null && equal
        } finally {
            candidateBytes.fill(0)
        }
    }

    @Synchronized
    fun acceptsRawSecret(candidate: String?): Boolean {
        if (closed || candidate == null || candidate.length !in 1..256 ||
            candidate.any { it !in '!'..'~' }) return false
        val bytes = candidate.toByteArray(StandardCharsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return try {
            MessageDigest.isEqual(rawDigest, digest)
        } finally {
            bytes.fill(0)
            digest.fill(0)
        }
    }

    @Synchronized
    fun clear() {
        closed = true
        expected.fill(0)
        rawDigest.fill(0)
    }

    companion object {
        private const val SHA1_HEX_LENGTH = 40

        fun fromRawSecret(rawSecret: ByteArray): LocalNightscoutApiAuthenticator {
            val digest = MessageDigest.getInstance("SHA-1").digest(rawSecret)
            val rawDigest = MessageDigest.getInstance("SHA-256").digest(rawSecret)
            val hex = ByteArray(SHA1_HEX_LENGTH)
            try {
                digest.forEachIndexed { index, value ->
                    val unsigned = value.toInt() and 0xff
                    hex[index * 2] = HEX[unsigned ushr 4]
                    hex[index * 2 + 1] = HEX[unsigned and 0x0f]
                }
                return LocalNightscoutApiAuthenticator(hex, rawDigest)
            } finally {
                digest.fill(0)
                hex.fill(0)
                rawDigest.fill(0)
            }
        }

        private fun isCanonicalSha1Hex(value: String): Boolean =
            value.length == SHA1_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

        private val HEX = "0123456789abcdef".toByteArray(StandardCharsets.US_ASCII)
    }
}

internal data class LocalNightscoutSocketAuthorizationResult(
    val authorized: Boolean,
    val read: Boolean,
    val write: Boolean,
    val writeTreatment: Boolean,
    val fromTs: Long
)

internal object LocalNightscoutSocketAuthorization {
    fun authorize(
        payload: JsonObject?,
        authenticator: LocalNightscoutApiAuthenticator
    ): LocalNightscoutSocketAuthorizationResult {
        val candidate = payload?.get("secret")
            ?.takeIf { !it.isJsonNull }
            ?.let { runCatching { it.asString }.getOrNull() }
        val authorized = authenticator.accepts(candidate)
        val fromTs = payload?.get("from")
            ?.takeIf { !it.isJsonNull }
            ?.let { runCatching { it.asLong }.getOrNull() }
            ?.coerceAtLeast(0L)
            ?: 0L
        return LocalNightscoutSocketAuthorizationResult(
            authorized = authorized,
            read = authorized,
            write = authorized,
            writeTreatment = authorized,
            fromTs = fromTs
        )
    }
}

internal enum class LocalNightscoutApiAccess {
    ALLOW,
    ALLOW_SETUP,
    NOT_READY,
    UNAUTHORIZED
}

internal object LocalNightscoutRequestAuthorization {
    fun evaluate(
        path: String,
        headers: Map<String, String>,
        runtimeReady: Boolean,
        authenticator: LocalNightscoutApiAuthenticator
    ): LocalNightscoutApiAccess {
        val route = path.substringBefore('?')
        if (!route.startsWith("/api/v1/", ignoreCase = true)) {
            return LocalNightscoutApiAccess.ALLOW_SETUP
        }
        val candidate = headers.entries
            .firstOrNull { it.key.equals("api-secret", ignoreCase = true) }
            ?.value
        if (!authenticator.accepts(candidate)) return LocalNightscoutApiAccess.UNAUTHORIZED
        return if (runtimeReady) LocalNightscoutApiAccess.ALLOW else LocalNightscoutApiAccess.NOT_READY
    }
}
