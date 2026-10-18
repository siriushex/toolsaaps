package io.aaps.copilot.data.repository

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import io.aaps.copilot.domain.nutrition.MealTimingHistoryReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** First existing maintenance cycle after 05:00 local; catches up after sleep/offline. */
internal fun insulinModelDay(timestampMs: Long, zone: ZoneId): LocalDate {
    val local = Instant.ofEpochMilli(timestampMs).atZone(zone)
    return local.toLocalDate().minusDays(if (local.hour < 5) 1 else 0)
}

internal class AiInsulinContextSync(private val send: suspend (String) -> Unit) {
    private var lastKey: List<Any?>? = null

    suspend fun sync(deviceBinding: String, model: AiInsulinContext, nowMs: Long, zone: ZoneId,
        loadTimingHistory: (suspend () -> MealTimingHistoryReport)? = null) {
        val key = listOf(deviceBinding, zone.id, insulinModelDay(nowMs, zone), model.configuredProfileId, model.updatedAtMs)
        if (key == lastKey) return
        val timing = try {
            loadTimingHistory?.invoke()?.takeIf {
                it.cutoffMs == nowMs && it.recordedZoneId == zone.id
            }?.contextSummary() ?: "meal_timing_history_status=UNAVAILABLE"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "meal_timing_history_status=UNAVAILABLE"
        }
        currentCoroutineContext().ensureActive()
        send("context_kind=OBSERVED_INSULIN_SETTINGS\ncontext_generated_at_ms=$nowMs\n" +
            "snapshot_is_complete_history=false\n" + model.summary() + "\n" + timing)
        lastKey = key
    }
}
