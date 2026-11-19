package io.aaps.copilot.service

import io.aaps.copilot.data.repository.HistoryJson
import io.aaps.copilot.util.UnitConverter

/** AAPS observations kept separate from accepted Copilot calculation inputs. */
data class AapsObservedMetrics internal constructor(
    val profileIsfMmol: Double?,
    val profileCr: Double?,
    val cobGrams: Double?
) {
    override fun toString() = "AapsObservedMetrics(redacted)"

    internal companion object {
        fun decode(bytes: ByteArray, now: Long): AapsObservedMetrics? = runCatching {
            val body = HistoryJson.parse(bytes, 1_024)
            HistoryJson.fields(body, setOf("version", "at"), setOf("profileIsfMgdl", "profileCr", "cobGrams", "cobAt"))
            require(HistoryJson.long(body.getValue("version")) == 1L)
            val at = HistoryJson.long(body.getValue("at"))
            require(now in 1..HistoryJson.maxTime && at in maxOf(1L, now - 10_000L)..now)
            val isf = body["profileIsfMgdl"]?.let(HistoryJson::double)?.takeIf { it > 0 && it <= 1000 }
            val cr = body["profileCr"]?.let(HistoryJson::double)?.takeIf { it > 0 && it <= 1000 }
            require(("cobAt" in body) == ("cobGrams" in body))
            val cob = body["cobAt"]?.let(HistoryJson::long)
                ?.takeIf { it in maxOf(1L, now - 600_000L)..at }
                ?.let { HistoryJson.double(body.getValue("cobGrams")).takeIf { it in 0.0..1000.0 } }
            AapsObservedMetrics(isf?.let(UnitConverter::mgdlToMmol), cr, cob)
                .takeIf { isf != null || cr != null || cob != null }
        }.getOrNull()
    }
}
