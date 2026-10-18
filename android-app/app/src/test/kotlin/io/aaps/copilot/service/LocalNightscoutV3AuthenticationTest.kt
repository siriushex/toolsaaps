package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Test

class LocalNightscoutV3AuthenticationTest {
    private val secret = "local-refresh-fixture-123456789012"
    private var epoch = 1_800_000_000_000L
    private var elapsed = 500_000L
    private val authenticator = LocalNightscoutApiAuthenticator.fromRawSecret(secret.toByteArray())
    private val auth = LocalNightscoutV3Authentication(authenticator, { epoch }, { elapsed })

    private fun issued() = auth.issue(secret) as LocalNightscoutTokenIssue.Issued
    private fun access(token: String) = auth.authorize("Bearer $token", epoch.toString())

    @Test
    fun exactRawCredentialOnlyAndNoPlaintextDiagnosticRendering() {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(secret.toByteArray()).joinToString("") { "%02x".format(it) }
        listOf(null, "", sha1, secret.uppercase(), "$secret ", " $secret", "x".repeat(257), "\u0000", "\u00e9")
            .forEach { assertThat(auth.issue(it)).isEqualTo(LocalNightscoutTokenIssue.Refused.UNAUTHORIZED) }
        val session = issued()
        assertThat(session.token).matches("[A-Za-z0-9_-]{43}")
        assertThat(session.iat).isEqualTo(epoch / 1_000L)
        assertThat(session.exp).isEqualTo(epoch / 1_000L + 900L)
        assertThat(session.toString()).doesNotContain(session.token)
        assertThat(session.toString()).doesNotContain(secret)
        assertThat(access(session.token)).isEqualTo(LocalNightscoutBearerAccess.ALLOW)
        assertThat(authenticator.accepts(sha1)).isTrue()
    }

    @Test
    fun canonicalBearerOnlyAndNoCredentialInterchange() {
        val token = issued().token
        listOf(null, "", "Bearer", "Bearer ", token, "Basic $token", "Bearer $secret", "Bearer  $token",
            "Bearer $token ", "Bearer ${token.dropLast(1)}!", "Bearer ${token.dropLast(1)}\u00e9")
            .forEach { assertThat(auth.authorize(it, epoch.toString())).isEqualTo(LocalNightscoutBearerAccess.UNAUTHORIZED) }
        assertThat(auth.authorize("bearer $token", epoch.toString())).isEqualTo(LocalNightscoutBearerAccess.ALLOW)
        assertThat(auth.issue(token)).isEqualTo(LocalNightscoutTokenIssue.Refused.UNAUTHORIZED)
        assertThat(authenticator.accepts(token)).isFalse()
    }

    @Test
    fun lifetimeIsNotExtendedByUseAndExpiresAtExactBoundary() {
        val token = issued().token
        epoch += 899_999L
        elapsed += 899_999L
        assertThat(access(token)).isEqualTo(LocalNightscoutBearerAccess.ALLOW)
        epoch++
        elapsed++
        assertThat(access(token)).isEqualTo(LocalNightscoutBearerAccess.UNAUTHORIZED)
        assertThat(access(issued().token)).isEqualTo(LocalNightscoutBearerAccess.ALLOW)
    }

    @Test
    fun monotonicDeadlineAppliesEvenIfWallClockStalls() {
        val token = issued().token
        elapsed += 900_000L
        assertThat(access(token)).isEqualTo(LocalNightscoutBearerAccess.UNAUTHORIZED)
    }

    @Test
    fun wallClockForwardJumpExpiresWithoutMonotonicProgress() {
        val token = issued().token
        epoch += 900_000L
        assertThat(access(token)).isEqualTo(LocalNightscoutBearerAccess.UNAUTHORIZED)
    }

    @Test
    fun wallClockRollbackRevokesPermanently() {
        val token = issued().token
        epoch--
        assertThat(access(token)).isEqualTo(LocalNightscoutBearerAccess.UNAUTHORIZED)
        epoch++
        assertThat(access(token)).isEqualTo(LocalNightscoutBearerAccess.UNAUTHORIZED)
    }

    @Test
    fun monotonicRollbackRevokesPermanently() {
        val token = issued().token
        elapsed--
        assertThat(access(token)).isEqualTo(LocalNightscoutBearerAccess.UNAUTHORIZED)
        assertThat(auth.issue(secret)).isEqualTo(LocalNightscoutTokenIssue.Refused.LIMITED)
        elapsed++
        assertThat(access(token)).isEqualTo(LocalNightscoutBearerAccess.UNAUTHORIZED)
    }

    @Test
    fun closeIsTerminalAndNewInstanceCannotReadOldToken() {
        val token = issued().token
        auth.close()
        auth.close()
        assertThat(access(token)).isEqualTo(LocalNightscoutBearerAccess.UNAUTHORIZED)
        assertThat(auth.issue(secret)).isEqualTo(LocalNightscoutTokenIssue.Refused.UNAVAILABLE)
        val restarted = LocalNightscoutV3Authentication(authenticator, { epoch }, { elapsed })
        assertThat(restarted.authorize("Bearer $token", epoch.toString())).isEqualTo(LocalNightscoutBearerAccess.UNAUTHORIZED)
    }

    @Test
    fun clearingIdentityDisablesBothCredentialFormats() {
        authenticator.clear()
        assertThat(authenticator.acceptsRawSecret(secret)).isFalse()
        assertThat(auth.issue(secret)).isEqualTo(LocalNightscoutTokenIssue.Refused.UNAUTHORIZED)
        assertThat(authenticator.accepts("0".repeat(40))).isFalse()
    }

    @Test
    fun rejectsBadDateAndAcceptsToleranceBoundaries() {
        val token = issued().token
        listOf("", "invalid", "0", "-1", " 1800000000000", Long.MAX_VALUE.toString(), "9".repeat(100),
            (epoch - 300_001L).toString(), (epoch + 300_001L).toString()).forEach {
            assertThat(auth.authorize("Bearer $token", it)).isEqualTo(LocalNightscoutBearerAccess.CLOCK_SKEW)
        }
        listOf(null, epoch.toString(), (epoch - 300_000L).toString(), (epoch + 300_000L).toString()).forEach {
            assertThat(auth.authorize("Bearer $token", it)).isEqualTo(LocalNightscoutBearerAccess.ALLOW)
        }
    }

    @Test
    fun invalidServerClockCannotIssueSessions() {
        listOf(0L, -1L, Long.MAX_VALUE).forEach {
            epoch = it
            assertThat(auth.issue(secret)).isEqualTo(LocalNightscoutTokenIssue.Refused.UNAVAILABLE)
        }
    }

    @Test
    fun invalidRefreshFloodCannotEvictSessionOrResetBudgetWithWallClock() {
        val token = issued().token
        repeat(59) { assertThat(auth.issue("invalid")).isEqualTo(LocalNightscoutTokenIssue.Refused.UNAUTHORIZED) }
        assertThat(auth.issue(secret)).isEqualTo(LocalNightscoutTokenIssue.Refused.LIMITED)
        epoch += 60_000L
        assertThat(auth.issue(secret)).isEqualTo(LocalNightscoutTokenIssue.Refused.LIMITED)
        assertThat(access(token)).isEqualTo(LocalNightscoutBearerAccess.ALLOW)
        elapsed += 60_000L
        assertThat(auth.issue(secret)).isInstanceOf(LocalNightscoutTokenIssue.Issued::class.java)
    }

    @Test
    fun sessionCapacityDoesNotEvictExistingTokensAndReclaimsExpiredSlots() {
        val tokens = (1..32).map { issued().token }
        assertThat(tokens.toSet()).hasSize(32)
        assertThat(auth.issue(secret)).isEqualTo(LocalNightscoutTokenIssue.Refused.LIMITED)
        tokens.forEach { assertThat(access(it)).isEqualTo(LocalNightscoutBearerAccess.ALLOW) }
        epoch += 900_000L
        elapsed += 900_000L
        assertThat(access(issued().token)).isEqualTo(LocalNightscoutBearerAccess.ALLOW)
    }

    @Test
    fun concurrentRefreshCannotExceedSessionCapacity() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val jobs = (1..48).map { pool.submit<LocalNightscoutTokenIssue> { start.await(); auth.issue(secret) } }
            start.countDown()
            val results = jobs.map { it.get(5, TimeUnit.SECONDS) }
            val issued = results.filterIsInstance<LocalNightscoutTokenIssue.Issued>()
            assertThat(issued).hasSize(32)
            assertThat(issued.map { it.token }.toSet()).hasSize(32)
            assertThat(results.count { it == LocalNightscoutTokenIssue.Refused.LIMITED }).isEqualTo(16)
        } finally {
            pool.shutdownNow()
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun entropyCollisionRefusesRatherThanReplacingSession() {
        val fixed = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) = bytes.fill(7)
        }
        val service = LocalNightscoutV3Authentication(authenticator, { epoch }, { elapsed }, fixed)
        val first = service.issue(secret) as LocalNightscoutTokenIssue.Issued
        assertThat(service.issue(secret)).isEqualTo(LocalNightscoutTokenIssue.Refused.UNAVAILABLE)
        assertThat(service.authorize("Bearer ${first.token}", null)).isEqualTo(LocalNightscoutBearerAccess.ALLOW)
    }
}
