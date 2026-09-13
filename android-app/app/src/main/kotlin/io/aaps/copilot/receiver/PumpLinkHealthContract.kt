package io.aaps.copilot.receiver

import android.os.Bundle
import io.aaps.copilot.domain.pump.PumpLinkAdapterState
import io.aaps.copilot.domain.pump.PumpLinkDriverState
import io.aaps.copilot.domain.pump.PumpLinkSnapshot

object PumpLinkHealthContract {
    const val ACTION = "info.nightscout.androidaps.PUMP_LINK_HEALTH"
    const val PERMISSION = "info.nightscout.androidaps.permission.RELAY_COPILOT_DATA"
    const val VERSION = 1

    private val allowedKeys = setOf("protocolVersion", "bootCount", "sessionStartedElapsedMs", "sequence",
        "sampledElapsedMs", "supported", "adapterState", "driverState", "intentionalDisconnect", "lastVerifiedStatusElapsedMs")

    fun encode(snapshot: PumpLinkSnapshot): Bundle = Bundle().apply {
        putInt("protocolVersion", VERSION)
        putInt("bootCount", snapshot.bootCount)
        putLong("sessionStartedElapsedMs", snapshot.sessionStartedElapsedMs)
        putLong("sequence", snapshot.sequence)
        putLong("sampledElapsedMs", snapshot.sampledElapsedMs)
        putBoolean("supported", snapshot.supported)
        putString("adapterState", snapshot.adapterState.name)
        putString("driverState", snapshot.driverState.name)
        putBoolean("intentionalDisconnect", snapshot.intentionalDisconnect)
        snapshot.lastVerifiedStatusElapsedMs?.let { putLong("lastVerifiedStatusElapsedMs", it) }
    }

    @Suppress("DEPRECATION")
    fun decode(bundle: Bundle?): PumpLinkSnapshot? {
        if (bundle == null) return null
        return try {
            val keys = bundle.keySet()
            if (keys.size !in 9..10 || !allowedKeys.containsAll(keys) || bundle.get("protocolVersion") != VERSION) return null
            val adapter = bundle.get("adapterState") as? String ?: return null
            val driver = bundle.get("driverState") as? String ?: return null
            if (adapter.length > 24 || driver.length > 24) return null
            val response = if (bundle.containsKey("lastVerifiedStatusElapsedMs")) {
                bundle.get("lastVerifiedStatusElapsedMs") as? Long ?: return null
            } else null
            PumpLinkSnapshot(
                bootCount = bundle.get("bootCount") as? Int ?: return null,
                sessionStartedElapsedMs = bundle.get("sessionStartedElapsedMs") as? Long ?: return null,
                sequence = bundle.get("sequence") as? Long ?: return null,
                sampledElapsedMs = bundle.get("sampledElapsedMs") as? Long ?: return null,
                supported = bundle.get("supported") as? Boolean ?: return null,
                adapterState = PumpLinkAdapterState.entries.firstOrNull { it.name == adapter } ?: return null,
                driverState = PumpLinkDriverState.entries.firstOrNull { it.name == driver } ?: return null,
                intentionalDisconnect = bundle.get("intentionalDisconnect") as? Boolean ?: return null,
                lastVerifiedStatusElapsedMs = response
            )
        } catch (_: Exception) {
            null
        }
    }
}
