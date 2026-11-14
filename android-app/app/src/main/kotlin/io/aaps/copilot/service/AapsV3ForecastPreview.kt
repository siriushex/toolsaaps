package io.aaps.copilot.service

import io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity
import io.aaps.copilot.data.repository.HistoryJson
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.util.UnitConverter

/** Display only. This result never enters accepted forecast storage or a therapy decision. */
internal class AapsV3ForecastPreview(
    val glucose: List<GlucosePoint>,
    val forecast: List<Forecast>
) {
    val lastSampleAt: Long get() = glucose.last().ts
    override fun toString() = "AapsV3ForecastPreview(redacted)"

    companion object {
        const val WINDOW_MS = 30 * 60_000L
        const val FRESH_MS = 5 * 60_000L
        const val MAX_ROWS = 96

        suspend fun from(rows: List<LocalNightscoutV3DocumentEntity>, now: Long): AapsV3ForecastPreview? {
            if (now <= WINDOW_MS || rows.isEmpty() || rows.size > MAX_ROWS) return null
            val points = rows.map { row -> decode(row, now) ?: return null }.sortedBy { it.ts }
            if (points.map { it.ts }.distinct().size != points.size || points.map { it.source }.distinct().size != 1)
                return null
            val latest = points.last()
            if (now - latest.ts > FRESH_MS) return AapsV3ForecastPreview(points, emptyList())
            val trend = ArrayList<GlucosePoint>()
            for (sample in points.asReversed()) {
                val gap = trend.lastOrNull()?.let { it.ts - sample.ts }
                if (gap != null && gap > 10 * 60_000L) break
                if (gap == null || gap >= 2 * 60_000L) trend.add(sample)
            }
            val forecast = if (trend.size >= 3 && latest.ts - trend.last().ts >= 8 * 60_000L)
                HybridPredictionEngine(enableUam = false).predict(trend.asReversed(), emptyList())
            else emptyList()
            return AapsV3ForecastPreview(points, forecast)
        }

        private fun decode(row: LocalNightscoutV3DocumentEntity, now: Long): GlucosePoint? = runCatching {
            require(row.collection == "entries" && row.source == "local-v3-owner" && !row.deleted)
            require(row.createdRevision in 1..row.modifiedRevision && row.modifiedRevision <= now)
            val body = HistoryJson.parse(HistoryJson.encodeUtf8(row.payloadJson), 4_096)
            require(HistoryJson.string(body.getValue("app")) == "AAPS-local")
            require(HistoryJson.string(body.getValue("type")) == "sgv")
            require(HistoryJson.string(body.getValue("units")) == "mg/dl")
            val ts = HistoryJson.long(body.getValue("date"))
            require(ts == row.eventAtMs && ts in (now - WINDOW_MS)..now)
            val device = HistoryJson.string(body.getValue("device")).also { require(HistoryJson.code.matches(it)) }
            val value = UnitConverter.mgdlToMmol(HistoryJson.double(body.getValue("sgv")))
            require(value.isFinite() && value in 1.0..33.0)
            GlucosePoint(ts, value, device)
        }.getOrNull()
    }
}
