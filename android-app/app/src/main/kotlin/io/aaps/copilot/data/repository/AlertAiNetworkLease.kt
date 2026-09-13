package io.aaps.copilot.data.repository

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

data class AlertAiValidatedNetworkSnapshot(val identity: Long) {
    init {
        require(identity > 0L)
    }
}

fun interface AlertAiBoundCallFactory {
    fun newCall(client: OkHttpClient, request: Request): Call
}

class AlertAiValidatedNetworkLease internal constructor(
    val snapshot: AlertAiValidatedNetworkSnapshot,
    private val callFactory: AlertAiBoundCallFactory
) {
    val identity: Long
        get() = snapshot.identity

    internal fun newCall(client: OkHttpClient, request: Request): Call =
        callFactory.newCall(client, request)
}

class AlertAiNetworkLeaseUnavailableException : Exception(
    "Alert AI validated network lease is unavailable"
)

fun interface AlertAiNetworkGate {
    suspend fun currentValidatedNetwork(): AlertAiValidatedNetworkLease?
}
