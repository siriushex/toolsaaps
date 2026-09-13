package io.aaps.copilot.receiver

internal object LocalBroadcastTrustPolicy {

    fun isAllowed(action: String): Boolean = action in ALLOWED_ACTIONS

    private val ALLOWED_ACTIONS = setOf("info.nightscout.androidaps.status")
}
