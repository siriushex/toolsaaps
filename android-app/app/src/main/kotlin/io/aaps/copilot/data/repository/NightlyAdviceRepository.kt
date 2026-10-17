package io.aaps.copilot.data.repository

import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.dao.ClinicalGlucoseProjection
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.scheduler.NightlyAdviceSchedule
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class NightlyAdviceRepository(
    private val gson: Gson,
    private val loadRows: suspend (Long, Long) -> List<ClinicalGlucoseProjection>,
    private val loadSnapshot: suspend () -> TelemetrySampleEntity?,
    private val storeSnapshot: suspend (TelemetrySampleEntity) -> Unit,
    observeSnapshot: Flow<TelemetrySampleEntity?> = emptyFlow()
) {
    constructor(db: CopilotDatabase, gson: Gson) : this(gson,
        { from, through -> db.glucoseDao().betweenForAlertAi(from, through, MAX_ROWS + 1) },
        { db.telemetryDao().byId(KEY) },
        { db.telemetryDao().upsertAll(listOf(it)) },
        db.telemetryDao().observeCurrentBySourceAndKey(SOURCE, KEY))

    private val mutex = Mutex()
    val snapshots: Flow<NightlyAdviceSnapshot?> = observeSnapshot.distinctUntilChanged().map { row ->
        row?.takeIf { it.id == KEY && it.key == KEY && it.source == SOURCE && it.quality == "OK" }
            ?.valueText?.let(::decode)
    }

    suspend fun refreshIfDue(now: Long, zoneId: ZoneId): NightlyAdviceSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val end = NightlyAdviceSchedule.windowEnd(Instant.ofEpochMilli(now).atZone(zoneId)).toInstant().toEpochMilli()
            val saved = loadSnapshot()?.takeIf { it.id == KEY && it.key == KEY && it.source == SOURCE && it.quality == "OK" }
                ?.valueText?.let(::decode)
            check(saved == null || saved.generatedAt <= now) { "advice_clock_changed" }
            if (saved != null && saved.zoneId == zoneId.id) {
                check(saved.throughTs <= end) { "advice_clock_changed" }
                if (saved.throughTs == end) return@withLock saved
            }
            val rows = loadRows(end - 14 * NightlyAdviceCalculator.DAY_MS, end)
            check(rows.size <= MAX_ROWS) { "advice_source_limit" }
            val report = NightlyAdviceCalculator.calculate(end, now, zoneId, rows)
            val json = gson.toJson(report)
            check(json.length <= MAX_JSON_CHARS)
            storeSnapshot(TelemetrySampleEntity(KEY, now, SOURCE, KEY, null, json, null, "OK"))
            report
        }
    }

    internal fun decode(json: String): NightlyAdviceSnapshot? = try {
        if (json.length > MAX_JSON_CHARS) null else gson.fromJson(json, NightlyAdviceSnapshot::class.java)?.takeIf { report ->
            val zone = ZoneId.of(report.zoneId)
            val end = Instant.ofEpochMilli(report.throughTs).atZone(zone)
            report.version == 1 && report.throughTs > 14 * NightlyAdviceCalculator.DAY_MS &&
                report.generatedAt >= report.throughTs && end.hour == 5 && end.minute == 0 && end.second == 0 && end.nano == 0 &&
                report.windows.map { it.days } == listOf(3, 7, 14) && report.windows.all { window ->
                    val metrics = listOf(window.inRangePct, window.belowPct, window.abovePct)
                    window.fromTs == report.throughTs - window.days * NightlyAdviceCalculator.DAY_MS &&
                        window.coveragePct.isFinite() && window.coveragePct in 0.0..100.0 &&
                        window.qualityDays in 0..window.days && window.lowDays in 0..window.qualityDays &&
                        window.highDays in 0..window.qualityDays &&
                        metrics.all { it == null || it.isFinite() && it in 0.0..100.0 } &&
                        (if (window.coveragePct == 0.0) metrics.all { it == null } && window.qualityDays == 0
                        else metrics.all { it != null } && kotlin.math.abs(metrics.filterNotNull().sum() - 100.0) < 0.01) &&
                        (window.recentDeltaPp == null || window.days >= 7 && window.usable && window.qualityDays >= 6 &&
                            window.recentDeltaPp.isFinite() && window.recentDeltaPp in -100.0..100.0)
                }
        }
    } catch (_: RuntimeException) { null }

    companion object {
        const val MAX_ROWS = 100_000
        private const val MAX_JSON_CHARS = 65_536
        private const val KEY = "nightly_advice_snapshot_v1"
        private const val SOURCE = "local_advice"
    }
}
