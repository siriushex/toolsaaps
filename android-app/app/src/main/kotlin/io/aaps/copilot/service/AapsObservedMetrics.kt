package io.aaps.copilot.service

import io.aaps.copilot.data.repository.HistoryJson
import io.aaps.copilot.util.UnitConverter

/** AAPS observations kept separate from accepted Copilot calculation inputs. */
data class AapsObservedMetrics internal constructor(
    val profileIsfMmol: Double?,
    val profileCr: Double?,
    val cobGrams: Double?,
    val netIobUnits: Double? = null,
    val iobAt: Long? = null
) {
    override fun toString() = "AapsObservedMetrics(redacted)"

    internal companion object {
        fun decode(bytes: ByteArray, now: Long, version: Long = 1): AapsObservedMetrics? = runCatching {
            val body = HistoryJson.parse(bytes, 1_024)
            require(version in 1L..2L && HistoryJson.long(body.getValue("version")) == version)
            val extra = if (version == 2L) setOf("iobAt", "netIobUnits") else emptySet()
            HistoryJson.fields(body, setOf("version", "at"), setOf("profileIsfMgdl", "profileCr", "cobGrams", "cobAt") + extra)
            val at = HistoryJson.long(body.getValue("at"))
            require(now in 1..HistoryJson.maxTime && at in maxOf(1L, now - 10_000L)..now)
            val isf = body["profileIsfMgdl"]?.let(HistoryJson::double)?.takeIf { it > 0 && it <= 1000 }
            val cr = body["profileCr"]?.let(HistoryJson::double)?.takeIf { it > 0 && it <= 1000 }
            require(("cobAt" in body) == ("cobGrams" in body))
            val cob = body["cobAt"]?.let(HistoryJson::long)
                ?.takeIf { it in maxOf(1L, now - 600_000L)..at }
                ?.let { HistoryJson.double(body.getValue("cobGrams")).takeIf { it in 0.0..1000.0 } }
            require(("iobAt" in body) == ("netIobUnits" in body))
            val iobAt = body["iobAt"]?.let(HistoryJson::long)?.takeIf { it in maxOf(1L, now - 300_000L)..at }
            val net = iobAt?.let { HistoryJson.double(body.getValue("netIobUnits")).takeIf(Double::isFinite) }
            AapsObservedMetrics(isf?.let(UnitConverter::mgdlToMmol), cr, cob, net, iobAt?.takeIf { net != null })
                .takeIf { isf != null || cr != null || cob != null || net != null }
        }.getOrNull()
    }
}
