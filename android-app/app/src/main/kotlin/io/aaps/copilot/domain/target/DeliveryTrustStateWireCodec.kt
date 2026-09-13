package io.aaps.copilot.domain.target

/** Stable telemetry contract. Codes are explicit and must never follow enum declaration order. */
object DeliveryTrustStateWireCodec {
    const val STABLE_TELEMETRY_KEY = "target_delivery_trust_state_v2"
    const val LEGACY_TELEMETRY_KEY = "target_delivery_trust_state"
    const val LEGACY_SOURCE = "copilot_runtime_isfcr"

    private const val NORMAL_CODE = 100
    private const val WATCH_CODE = 110
    private const val SUSPECTED_NONRESPONSE_CODE = 120
    private const val UNKNOWN_CODE = 190

    fun encode(state: DeliveryTrustState): Double = when (state) {
        DeliveryTrustState.NORMAL -> NORMAL_CODE.toDouble()
        DeliveryTrustState.WATCH -> WATCH_CODE.toDouble()
        DeliveryTrustState.SUSPECTED_NONRESPONSE -> SUSPECTED_NONRESPONSE_CODE.toDouble()
        DeliveryTrustState.UNKNOWN -> UNKNOWN_CODE.toDouble()
    }

    fun decodeStable(value: Double?): DeliveryTrustState? = when (strictInteger(value)) {
        NORMAL_CODE -> DeliveryTrustState.NORMAL
        WATCH_CODE -> DeliveryTrustState.WATCH
        SUSPECTED_NONRESPONSE_CODE -> DeliveryTrustState.SUSPECTED_NONRESPONSE
        UNKNOWN_CODE -> DeliveryTrustState.UNKNOWN
        else -> null
    }

    /** Compatibility for telemetry written by the deployed ordinal writer under the legacy key. */
    private fun decodeLegacy(value: Double?): DeliveryTrustState? = when (strictInteger(value)) {
        0 -> DeliveryTrustState.NORMAL
        1 -> DeliveryTrustState.WATCH
        2 -> DeliveryTrustState.SUSPECTED_NONRESPONSE
        3 -> DeliveryTrustState.UNKNOWN
        else -> null
    }

    fun decodeTelemetry(key: String, source: String, value: Double?): DeliveryTrustState? = when (key) {
        STABLE_TELEMETRY_KEY -> decodeStable(value)
        LEGACY_TELEMETRY_KEY -> decodeLegacy(value).takeIf { source == LEGACY_SOURCE }
        else -> null
    }

    private fun strictInteger(value: Double?): Int? {
        val finite = value?.takeIf(Double::isFinite) ?: return null
        if (finite < Int.MIN_VALUE.toDouble() || finite > Int.MAX_VALUE.toDouble()) return null
        val integer = finite.toInt()
        return integer.takeIf { finite == it.toDouble() }
    }
}
