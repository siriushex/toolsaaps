package io.aaps.copilot.service

import androidx.room.withTransaction
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity
import io.aaps.copilot.domain.model.GlucosePoint
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate

/** An observed series for the Analytics screen, never published as accepted clinical input. */
internal object AapsObservedCircadian {
    const val WINDOW_MS = 14L * 86_400_000L
    private const val MAX_ROWS = 21_000
    private const val MAX_BYTES = 6L * 1024 * 1024

    suspend fun read(db: CopilotDatabase, now: Long): List<GlucosePoint> {
        if (now <= WINDOW_MS) return emptyList()
        val from = now - WINDOW_MS
        val rows = db.withTransaction {
            val dao = db.localNightscoutV3Dao()
            val size = dao.ownerGlucoseSize(from, now)
            if (size.rowCount !in 1..MAX_ROWS || size.bytes !in 1..MAX_BYTES) return@withTransaction emptyList()
            dao.recentOwnerGlucose(from, now, size.rowCount)
        }
        return decode(rows, now)
    }

    internal suspend fun decode(rows: List<LocalNightscoutV3DocumentEntity>, now: Long): List<GlucosePoint> {
        if (rows.size !in 1..MAX_ROWS || now <= WINDOW_MS) return emptyList()
        val points = rows.mapIndexed { i, row ->
            if (i % 128 == 0) currentCoroutineContext().ensureActive()
            AapsV3ForecastPreview.decodeOwnerGlucose(row, now - WINDOW_MS, now) ?: return emptyList()
        }.sortedBy { it.ts }
        // After a sensor change use only the latest uninterrupted source. Conflicting sources
        // at the newest instant cannot identify a current series.
        val source = points.last().source
        val boundary = points.indexOfLast { it.source != source }
        if (boundary >= 0 && points[boundary].ts == points.last().ts) return emptyList()
        val activeSeries = if (boundary < 0) points else points.dropWhile { it.ts <= points[boundary].ts }
        val zone = ZoneId.systemDefault()
        val slots = mutableMapOf<LocalDate, MutableSet<Long>>()
        for ((index, point) in activeSeries.withIndex()) {
            if (index > 0 && point.ts == activeSeries[index - 1].ts) return emptyList()
            val day = Instant.ofEpochMilli(point.ts).atZone(zone).toLocalDate()
            slots.getOrPut(day) { mutableSetOf() }.add(point.ts / 300_000L)
        }
        // Count observed five-minute intervals rather than raw one-minute samples.
        val coveredDays = slots.filter { (day, observed) ->
            val lengthMs = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() -
                day.atStartOfDay(zone).toInstant().toEpochMilli()
            observed.size * 300_000L >= lengthMs * 0.35
        }.keys
        if (coveredDays.size < 3) return emptyList()
        return activeSeries.filter { Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate() in coveredDays }
    }
}
