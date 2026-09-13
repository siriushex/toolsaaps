package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import org.junit.Test

class LocalNightscoutAuthenticationTest {

    private val rawSecret = "aaps-local-secret".toByteArray(Charsets.US_ASCII)
    private val expectedSha1 = "60886d144b64f597b43d008596a37c037e767aab"

    @Test
    fun androidApsNsClientAuthorizeShapeAcceptsExactLowercaseSha1() {
        val authenticator = LocalNightscoutApiAuthenticator.fromRawSecret(rawSecret)
        val payload = JsonObject().apply {
            addProperty("client", "AndroidAPS")
            addProperty("secret", expectedSha1)
            addProperty("history", 48)
            addProperty("status", true)
            addProperty("from", 1_780_000_000_000L)
        }

        val result = LocalNightscoutSocketAuthorization.authorize(payload, authenticator)

        assertThat(result.authorized).isTrue()
        assertThat(result.read).isTrue()
        assertThat(result.write).isTrue()
        assertThat(result.writeTreatment).isTrue()
        assertThat(result.fromTs).isEqualTo(1_780_000_000_000L)
    }

    @Test
    fun missingInvalidAndNonCanonicalSocketSecretsHaveNoPermissions() {
        val authenticator = LocalNightscoutApiAuthenticator.fromRawSecret(rawSecret)
        val candidates = listOf<String?>(
            null,
            "",
            "not-a-hash",
            expectedSha1.uppercase(),
            expectedSha1.dropLast(1) + "0"
        )

        candidates.forEach { candidate ->
            val payload = JsonObject().apply {
                candidate?.let { addProperty("secret", it) }
                addProperty("from", 123L)
            }
            val result = LocalNightscoutSocketAuthorization.authorize(payload, authenticator)

            assertThat(result.authorized).isFalse()
            assertThat(result.read).isFalse()
            assertThat(result.write).isFalse()
            assertThat(result.writeTreatment).isFalse()
        }
    }

    @Test
    fun apiRoutesRequireReadyStateAndStandardHeaderOnly() {
        val authenticator = LocalNightscoutApiAuthenticator.fromRawSecret(rawSecret)

        assertThat(
            LocalNightscoutRequestAuthorization.evaluate(
                path = "/api/v1/entries.json",
                headers = mapOf("api-secret" to expectedSha1),
                runtimeReady = false,
                authenticator = authenticator
            )
        ).isEqualTo(LocalNightscoutApiAccess.NOT_READY)
        assertThat(
            LocalNightscoutRequestAuthorization.evaluate(
                path = "/api/v1/entries.json",
                headers = emptyMap(),
                runtimeReady = false,
                authenticator = authenticator
            )
        ).isEqualTo(LocalNightscoutApiAccess.UNAUTHORIZED)

        listOf(
            emptyMap(),
            mapOf("api_secret" to expectedSha1),
            mapOf("authorization" to expectedSha1),
            mapOf("user-agent" to expectedSha1),
            mapOf("x-aaps-copilot-client" to expectedSha1)
        ).forEach { headers ->
            assertThat(
                LocalNightscoutRequestAuthorization.evaluate(
                    path = "/api/v1/treatments.json?api-secret=$expectedSha1",
                    headers = headers,
                    runtimeReady = true,
                    authenticator = authenticator
                )
            ).isEqualTo(LocalNightscoutApiAccess.UNAUTHORIZED)
        }

        assertThat(
            LocalNightscoutRequestAuthorization.evaluate(
                path = "/api/v1/status.json",
                headers = mapOf("api-secret" to expectedSha1),
                runtimeReady = true,
                authenticator = authenticator
            )
        ).isEqualTo(LocalNightscoutApiAccess.ALLOW)
        assertThat(
            LocalNightscoutRequestAuthorization.evaluate(
                path = "/socket.io/",
                headers = emptyMap(),
                runtimeReady = false,
                authenticator = authenticator
            )
        ).isEqualTo(LocalNightscoutApiAccess.ALLOW_SETUP)
    }
}
