package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.aaps.copilot.config.AppSettingsStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Test

class ApiFactoryNightscoutTransportTest {
    @Test
    fun sharedOwnedEndpointPredicateKeepsIdentityAndLeaseRoutingAligned() = runBlocking {
        val settings = defaults().copy(localNightscoutEnabled = true, localNightscoutPort = 17582)
        for (url in listOf("https://localhost:17582", "https://127.0.0.1:17582/",
            " HTTPS://LOCALHOST:17582/ ")) {
            assertThat(isOwnedLocalNightscoutEndpoint(url, settings)).isTrue()
        }
        for (url in listOf("http://localhost:17582", "https://localhost:17583",
            "https://user:pass@localhost:17582", "https://localhost:17582/api/",
            "https://localhost:17582/?query=x", "https://localhost:17582/#fragment",
            "https://not-local.example:17582", "https://localhost.evil.example:17582", "")) {
            assertThat(isOwnedLocalNightscoutEndpoint(url, settings)).isFalse()
        }
        assertThat(isOwnedLocalNightscoutEndpoint("https://localhost:17582",
            settings.copy(localNightscoutEnabled = false))).isFalse()
    }

    @Test
    fun missingNightscoutEndpointDoesNotCreateExampleClient() = runBlocking {
        assertThat(runCatching { ApiFactory().nightscoutApi("", "test-secret") }.isFailure).isTrue()
        assertThat(runCatching {
            ApiFactory().nightscoutApi(defaults().copy(nightscoutUrl = "", localNightscoutEnabled = false))
        }.isFailure).isTrue()
    }

    @Test
    fun ownEndpointUsesIdentityNotRemoteSecret() = runBlocking {
        withTlsServer { server, certificate ->
            server.enqueue(MockResponse().setBody("[]"))
            val factory = ApiFactory { LocalNightscoutClientIdentity(certificate.certificate, sha1("local-test-secret")) }
            val settings = settings(server)
            assertThat(factory.nightscoutApi(settings).getTreatments(emptyMap())).isEmpty()
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertThat(request.getHeader("api-secret")).isEqualTo(sha1("local-test-secret"))
            assertThat(request.getHeader("api-secret")).isNotEqualTo(sha1(settings.apiSecret))
        }
    }

    @Test
    fun missingIdentityDoesNotSendRemoteCredentialToOwnEndpoint() = runBlocking {
        withTlsServer { server, _ ->
            val result = runCatching { ApiFactory().nightscoutApi(settings(server)).getTreatments(emptyMap()) }
            assertThat(result.isFailure).isTrue()
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun wrongCaAndWrongHostnameFailBeforeHttp() = runBlocking {
        withTlsServer { server, _ ->
            val other = HeldCertificate.Builder().commonName("other").build()
            val factory = ApiFactory { LocalNightscoutClientIdentity(other.certificate, sha1("local-test-secret")) }
            assertThat(runCatching { factory.nightscoutApi(settings(server)).getTreatments(emptyMap()) }.isFailure).isTrue()
            assertThat(server.requestCount).isEqualTo(0)
        }
        withTlsServer(hostname = "not-local.example") { server, certificate ->
            val factory = ApiFactory { LocalNightscoutClientIdentity(certificate.certificate, sha1("local-test-secret")) }
            assertThat(runCatching { factory.nightscoutApi(settings(server)).getTreatments(emptyMap()) }.isFailure).isTrue()
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun redirectCannotForwardIdentityCredential() = runBlocking {
        withTlsServer { server, certificate ->
            MockWebServer().use { other ->
                other.start()
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/stolen")))
                val factory = ApiFactory { LocalNightscoutClientIdentity(certificate.certificate, sha1("local-test-secret")) }
                assertThat(runCatching { factory.nightscoutApi(settings(server)).getTreatments(emptyMap()) }.isFailure).isTrue()
                assertThat(server.requestCount).isEqualTo(1)
                assertThat(other.requestCount).isEqualTo(0)
            }
        }
    }

    @Test
    fun differentPortSchemeAndDisabledLocalDoNotLoadIdentity() = runBlocking {
        var identityLoads = 0
        val factory = ApiFactory { identityLoads++; error("must not load local credentials") }
        withTlsServer { server, _ ->
            for (settings in listOf(settings(server).copy(localNightscoutPort = server.port + 1),
                settings(server).copy(localNightscoutEnabled = false))) {
                assertThat(runCatching { factory.nightscoutApi(server.url("/").toString(), settings).getTreatments(emptyMap()) }.isFailure).isTrue()
            }
        }
        MockWebServer().use { remote ->
            remote.enqueue(MockResponse().setBody("[]"))
            remote.start()
            val settings = defaults().copy(apiSecret = "remote-test-secret", localNightscoutEnabled = true,
                localNightscoutPort = remote.port)
            factory.nightscoutApi(remote.url("/").toString(), settings).getTreatments(emptyMap())
            assertThat(remote.takeRequest().getHeader("api-secret")).isEqualTo(sha1(settings.apiSecret))
        }
        assertThat(identityLoads).isEqualTo(0)
    }

    @Test
    fun unknownLoopbackCertificateIsRejectedBeforeCredentialsAreSent() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("[]"))
            server.start()
            val result = runCatching {
                ApiFactory().nightscoutApi(server.url("/").toString(), "remote-test-secret")
                    .getTreatments(emptyMap())
            }
            assertThat(result.isFailure).isTrue()
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    private suspend fun withTlsServer(
        hostname: String = "localhost",
        block: suspend (MockWebServer, HeldCertificate) -> Unit
    ) {
        val certificate = HeldCertificate.Builder().commonName(hostname)
            .addSubjectAlternativeName(hostname).addSubjectAlternativeName("127.0.0.1").build()
        // A wrong-name certificate must not accidentally include the requested IP.
        val actual = if (hostname == "localhost") certificate else HeldCertificate.Builder()
            .commonName(hostname).addSubjectAlternativeName(hostname).build()
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(actual).build().sslSocketFactory(), false)
            server.start()
            block(server, actual)
        }
    }

    private suspend fun settings(server: MockWebServer) = defaults().copy(
        apiSecret = "remote-test-secret", localNightscoutEnabled = true,
        localNightscoutPort = server.port
    )

    private suspend fun defaults() = AppSettingsStore(object : DataStore<Preferences> {
        override val data = flowOf(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            error("read-only defaults")
    }).settings.first()

    private fun sha1(value: String) = MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
