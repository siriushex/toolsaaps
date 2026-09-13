package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidValidatedNetworkGateTest {
    @Test
    fun returnsStablePositiveNetworkHandleAndFailsClosedForUnavailableOrException() = runTest {
        val available = AndroidValidatedNetworkGate(
            candidateReader = { candidate(4_242L) }
        )
        val unavailable = AndroidValidatedNetworkGate(candidateReader = { null })
        val failed = AndroidValidatedNetworkGate(
            candidateReader = { throw SecurityException("network state unavailable") }
        )

        assertThat(available.currentValidatedNetwork()?.snapshot)
            .isEqualTo(AlertAiValidatedNetworkSnapshot(4_242L))
        assertThat(unavailable.currentValidatedNetwork()).isNull()
        assertThat(failed.currentValidatedNetwork()).isNull()
    }

    @Test
    fun leaseUsesBoundDnsAndSocketFactoryAndRejectsStaleNetworkBeforeHttp() = runTest {
        MockWebServer().use { server ->
            val loopback = InetAddress.getByName("127.0.0.1")
            server.start(loopback, 0)
            server.enqueue(MockResponse().setBody("ok"))
            // Keep the local fixture independent of host proxy and localhost IPv6 settings.
            val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
            val socketCalls = AtomicInteger()
            val dnsCalls = AtomicInteger()
            var valid = true
            val socketFactory = CountingSocketFactory(
                delegate = SocketFactory.getDefault(),
                calls = socketCalls
            )
            val gate = AndroidValidatedNetworkGate(
                candidateReader = {
                    AndroidValidatedNetworkCandidate(
                        snapshot = AlertAiValidatedNetworkSnapshot(77L),
                        socketFactory = socketFactory,
                        dns = Dns {
                            dnsCalls.incrementAndGet()
                            listOf(loopback)
                        },
                        isStillActiveValidated = { valid }
                    )
                }
            )
            val lease = requireNotNull(gate.currentValidatedNetwork())
            val url = "http://alert-ai.test:${server.port}/lease".toHttpUrl()
            val request = Request.Builder().url(url).build()

            lease.newCall(client, request).execute().use { response ->
                assertThat(response.body.string()).isEqualTo("ok")
            }

            assertThat(dnsCalls.get()).isEqualTo(1)
            assertThat(socketCalls.get()).isGreaterThan(0)
            assertThat(server.requestCount).isEqualTo(1)

            valid = false
            assertThrows(AlertAiNetworkLeaseUnavailableException::class.java) {
                lease.newCall(client, request)
            }
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun productionGateUsesOnlyImmediateValidatedActiveNetworkSnapshot() {
        val source = File(
            "src/main/kotlin/io/aaps/copilot/data/repository/AndroidValidatedNetworkGate.kt"
        ).readText()

        assertThat(source).contains("activeNetwork")
        assertThat(source).contains("getNetworkCapabilities")
        assertThat(source).contains("NET_CAPABILITY_INTERNET")
        assertThat(source).contains("NET_CAPABILITY_VALIDATED")
        assertThat(source).contains("networkHandle")
        listOf(
            "registerNetworkCallback",
            "registerDefaultNetworkCallback",
            "ConnectivityManager.NetworkCallback",
            "delay(",
            "while (",
            "WorkManager",
            "Worker",
            "JobService"
        ).forEach { forbidden -> assertThat(source).doesNotContain(forbidden) }
    }

    private fun candidate(identity: Long) = AndroidValidatedNetworkCandidate(
        snapshot = AlertAiValidatedNetworkSnapshot(identity),
        socketFactory = SocketFactory.getDefault(),
        dns = Dns.SYSTEM,
        isStillActiveValidated = { true }
    )

    private class CountingSocketFactory(
        private val delegate: SocketFactory,
        private val calls: AtomicInteger
    ) : SocketFactory() {
        override fun createSocket(): Socket = delegate.createSocket().also { calls.incrementAndGet() }

        override fun createSocket(host: String, port: Int): Socket =
            delegate.createSocket(host, port).also { calls.incrementAndGet() }

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int
        ): Socket = delegate.createSocket(host, port, localHost, localPort)
            .also { calls.incrementAndGet() }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            delegate.createSocket(host, port).also { calls.incrementAndGet() }

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int
        ): Socket = delegate.createSocket(address, port, localAddress, localPort)
            .also { calls.incrementAndGet() }
    }
}
