package io.aaps.copilot.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.ConnectionPool
import okhttp3.Dns

internal data class AndroidValidatedNetworkCandidate(
    val snapshot: AlertAiValidatedNetworkSnapshot,
    val socketFactory: SocketFactory,
    val dns: Dns,
    val isStillActiveValidated: () -> Boolean
)

class AndroidValidatedNetworkGate internal constructor(
    private val candidateReader: () -> AndroidValidatedNetworkCandidate?
) : AlertAiNetworkGate {
    constructor(context: Context) : this(
        candidateReader = reader@{
            val connectivity = context.applicationContext.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as? ConnectivityManager ?: return@reader null
            val network = connectivity.activeNetwork ?: return@reader null
            if (!connectivity.isValidatedInternet(network)) return@reader null
            val identity = network.networkHandle.takeIf { it > 0L } ?: return@reader null
            AndroidValidatedNetworkCandidate(
                snapshot = AlertAiValidatedNetworkSnapshot(identity),
                socketFactory = network.socketFactory,
                dns = Dns { hostname -> network.getAllByName(hostname).toList() },
                isStillActiveValidated = {
                    connectivity.activeNetwork
                        ?.takeIf { active -> active.networkHandle == identity }
                        ?.let(connectivity::isValidatedInternet) == true
                }
            )
        }
    )

    override suspend fun currentValidatedNetwork(): AlertAiValidatedNetworkLease? = try {
        candidateReader()?.toLease()
    } catch (_: Exception) {
        null
    }

    private fun AndroidValidatedNetworkCandidate.toLease() = AlertAiValidatedNetworkLease(
        snapshot = snapshot,
        callFactory = AlertAiBoundCallFactory { client, request ->
            val stillValid = try {
                isStillActiveValidated()
            } catch (_: Exception) {
                false
            }
            if (!stillValid) throw AlertAiNetworkLeaseUnavailableException()
            client.newBuilder()
                .socketFactory(socketFactory)
                .dns(dns)
                .connectionPool(ConnectionPool(0, 1L, TimeUnit.MILLISECONDS))
                .build()
                .newCall(request)
        }
    )
}

private fun ConnectivityManager.isValidatedInternet(network: Network): Boolean {
    val capabilities = getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
