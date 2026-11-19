package io.aaps.copilot.service

import io.aaps.copilot.data.repository.HistoryJson
import io.aaps.copilot.util.UnitConverter

/** A signed local readback for display, never an input to target or dosing decisions. */
data class AapsObservedTarget internal constructor(
    val lowMmol: Double,
    val highMmol: Double,
    val kind: Kind,
    val expiresAt: Long?
) {
    enum class Kind { PROFILE, APS_ADJUSTED, TEMPORARY }

    override fun toString(): String = "AapsObservedTarget(redacted)"

    internal companion object {
        fun decode(bytes: ByteArray, now: Long): AapsObservedTarget? = runCatching {
            val body = HistoryJson.parse(bytes, 1_024)
            HistoryJson.fields(body, setOf("version", "at", "kind", "lowMgdl", "highMgdl"), setOf("expiresAt"))
            require(HistoryJson.long(body.getValue("version")) == 1L)
            val at = HistoryJson.long(body.getValue("at"))
            require(now in 1..HistoryJson.maxTime && at in maxOf(1L, now - 10_000L)..now)
            val kind = when (HistoryJson.string(body.getValue("kind"))) {
                "profile" -> Kind.PROFILE
                "aps_adjusted" -> Kind.APS_ADJUSTED
                "temporary" -> Kind.TEMPORARY
                else -> error("unsupported_target_kind")
            }
            val expiresAt = body["expiresAt"]?.let(HistoryJson::long)
            require(expiresAt == null || kind == Kind.TEMPORARY && expiresAt > now &&
                expiresAt <= HistoryJson.maxTime)
            val low = HistoryJson.double(body.getValue("lowMgdl"))
            val high = HistoryJson.double(body.getValue("highMgdl"))
            require(low.isFinite() && high.isFinite() && low > 0.0 && high >= low && high <= 1000.0)
            require(kind != Kind.APS_ADJUSTED || low == high)
            AapsObservedTarget(UnitConverter.mgdlToMmol(low), UnitConverter.mgdlToMmol(high), kind, expiresAt)
        }.getOrNull()
    }
}
