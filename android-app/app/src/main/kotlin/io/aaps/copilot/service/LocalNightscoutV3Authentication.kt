package io.aaps.copilot.service

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal sealed interface LocalNightscoutTokenIssue {
    // Not a data class: default diagnostics must not stringify the credential.
    class Issued(val token: String, val iat: Long, val exp: Long) : LocalNightscoutTokenIssue
    enum class Refused : LocalNightscoutTokenIssue { UNAUTHORIZED, LIMITED, UNAVAILABLE }
}

internal enum class LocalNightscoutBearerAccess { ALLOW, UPLOAD_ONLY, UNAUTHORIZED, CLOCK_SKEW }

internal class LocalNightscoutV3Authentication(
    private val authenticator: LocalNightscoutApiAuthenticator,
    private val epochMs: () -> Long = System::currentTimeMillis,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val random: SecureRandom = SecureRandom()
) {
    private class Session(
        val digest: ByteArray,
        val issuedEpochMs: Long,
        val issuedElapsedMs: Long,
        val expiresEpochSeconds: Long,
        val lifetimeMs: Long,
        val uploadOnly: Boolean
    )

    private val sessions = mutableListOf<Session>()
    private var closed = false
    private var windowStart: Long? = null
    private var attempts = 0

    @Synchronized
    fun issue(candidate: String?): LocalNightscoutTokenIssue = issueSession(candidate, uploadOnly = false)

    @Synchronized
    fun issueLocalUpload(): LocalNightscoutTokenIssue = issueSession(null, uploadOnly = true)

    @Synchronized
    fun revokeLocalUpload(token: String) {
        val key = digest(token)
        try {
            sessions.removeAll {
                (it.uploadOnly && MessageDigest.isEqual(it.digest, key)).also { remove ->
                    if (remove) it.digest.fill(0)
                }
            }
        } finally {
            key.fill(0)
        }
    }

    private fun issueSession(candidate: String?, uploadOnly: Boolean): LocalNightscoutTokenIssue {
        if (closed) return LocalNightscoutTokenIssue.Refused.UNAVAILABLE
        val now = epochMs()
        val elapsed = elapsedMs()
        if (now <= 0L || now > Long.MAX_VALUE - LIFETIME_MS) {
            return LocalNightscoutTokenIssue.Refused.UNAVAILABLE
        }
        prune(now, elapsed)
        if (!admitAttempt(elapsed)) return LocalNightscoutTokenIssue.Refused.LIMITED
        if (!uploadOnly && !authenticator.acceptsRawSecret(candidate)) return LocalNightscoutTokenIssue.Refused.UNAUTHORIZED
        if (sessions.size >= MAX_SESSIONS) return LocalNightscoutTokenIssue.Refused.LIMITED
        val bytes = ByteArray(32)
        return try {
            random.nextBytes(bytes)
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            val digest = digest(token)
            if (sessions.any { MessageDigest.isEqual(it.digest, digest) }) {
                digest.fill(0)
                LocalNightscoutTokenIssue.Refused.UNAVAILABLE
            } else {
                val issued = now / 1_000L
                val lifetime = if (uploadOnly) LOCAL_UPLOAD_LIFETIME_MS else LIFETIME_MS
                val expires = issued + lifetime / 1_000L
                sessions.add(Session(digest, now, elapsed, expires, lifetime, uploadOnly))
                LocalNightscoutTokenIssue.Issued(token, issued, expires)
            }
        } finally {
            bytes.fill(0)
        }
    }

    @Synchronized
    fun authorize(header: String?, date: String?): LocalNightscoutBearerAccess {
        if (closed) return LocalNightscoutBearerAccess.UNAUTHORIZED
        val now = epochMs()
        val elapsed = elapsedMs()
        prune(now, elapsed)
        if (date != null) {
            val clientTime = date.takeIf { it.length in 1..19 && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
            if (now <= 0L || clientTime == null || clientTime <= 0L ||
                clientTime - now !in -CLOCK_TOLERANCE_MS..CLOCK_TOLERANCE_MS) {
                return LocalNightscoutBearerAccess.CLOCK_SKEW
            }
        }
        val token = header?.takeIf { it.length == 50 && it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.takeIf { value -> value.all { it.isAsciiTokenCharacter() } }
            ?: return LocalNightscoutBearerAccess.UNAUTHORIZED
        val candidate = digest(token)
        return try {
            var accepted = false
            var uploadOnly = false
            sessions.forEach {
                val matches = MessageDigest.isEqual(it.digest, candidate)
                accepted = matches or accepted
                uploadOnly = (matches and it.uploadOnly) or uploadOnly
            }
            when {
                !accepted -> LocalNightscoutBearerAccess.UNAUTHORIZED
                uploadOnly -> LocalNightscoutBearerAccess.UPLOAD_ONLY
                else -> LocalNightscoutBearerAccess.ALLOW
            }
        } finally {
            candidate.fill(0)
        }
    }

    @Synchronized
    fun close() {
        closed = true
        sessions.forEach { it.digest.fill(0) }
        sessions.clear()
    }

    private fun admitAttempt(now: Long): Boolean {
        val start = windowStart
        if (start == null || now - start >= WINDOW_MS) {
            windowStart = now
            attempts = 0
        } else if (now - start < 0L) {
            return false
        }
        if (attempts >= MAX_ATTEMPTS) return false
        attempts++
        return true
    }

    private fun prune(now: Long, elapsed: Long) {
        val iterator = sessions.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            if (now < session.issuedEpochMs || now / 1_000L >= session.expiresEpochSeconds ||
                elapsed - session.issuedElapsedMs !in 0L until session.lifetimeMs) {
                session.digest.fill(0)
                iterator.remove()
            }
        }
    }

    private fun digest(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        return try {
            MessageDigest.getInstance("SHA-256").digest(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    companion object {
        const val REFRESH_PREFIX = "/api/v2/authorization/request/"
        private const val LIFETIME_MS = 15 * 60_000L
        private const val LOCAL_UPLOAD_LIFETIME_MS = 45_000L
        private const val CLOCK_TOLERANCE_MS = 5 * 60_000L
        private const val WINDOW_MS = 60_000L
        private const val MAX_ATTEMPTS = 60
        private const val MAX_SESSIONS = 32

        private fun Char.isAsciiTokenCharacter() = this in 'A'..'Z' || this in 'a'..'z' ||
            this in '0'..'9' || this == '-' || this == '_'
    }
}
