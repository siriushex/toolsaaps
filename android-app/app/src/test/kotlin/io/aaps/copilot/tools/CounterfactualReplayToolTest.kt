package io.aaps.copilot.tools

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Test

class CounterfactualReplayToolTest {

    @Test
    fun generateCounterfactualReplayFromSqlite() = runBlocking {
        val beforePath = System.getenv("COPILOT_REPLAY_BEFORE_DB")
            ?: "/Users/mac/Andoidaps/tmp_live/copilot_before_importfix_20260305.db"
        val afterPath = System.getenv("COPILOT_REPLAY_AFTER_DB")
            ?: "/Users/mac/Andoidaps/tmp_live/copilot_post_install_20260305.db"
        val outputPath = System.getenv("COPILOT_REPLAY_OUTPUT_MD")
            ?: "/Users/mac/Andoidaps/AAPSPredictiveCopilot/artifacts/replay_24h_counterfactual_importfix_20260305.md"

        val beforeFile = File(beforePath)
        val afterFile = File(afterPath)
        if (!beforeFile.exists() || !afterFile.exists()) {
            println("counterfactual_replay_skipped missing_db before=$beforePath after=$afterPath")
            return@runBlocking
        }

        Class.forName("org.sqlite.JDBC")

        val report = CounterfactualReplayRunner(
            beforeDbPath = beforePath,
            afterDbPath = afterPath
        ).run()

        File(outputPath).parentFile?.mkdirs()
        File(outputPath).writeText(report.toMarkdown(), Charsets.UTF_8)
        println("counterfactual_replay_report=$outputPath")

        assertThat(report.before.metricsByHorizon[30]?.n ?: 0).isGreaterThan(10)
        assertThat(report.after.metricsByHorizon[30]?.n ?: 0).isGreaterThan(10)
    }
}

private class CounterfactualReplayRunner(
    private val beforeDbPath: String,
    private val afterDbPath: String
) {
    private val gson = Gson()

    suspend fun run(): CounterfactualReplayReport {
        val before = loadDb(beforeDbPath)
        val after = loadDb(afterDbPath)

        val windowEnd = minOf(before.glucose.lastOrNull()?.ts ?: 0L, after.glucose.lastOrNull()?.ts ?: 0L)
        val rawWindowStart = windowEnd - DAY_MS
        val windowStart = maxOf(
            rawWindowStart,
            before.glucose.firstOrNull()?.ts ?: rawWindowStart,
            after.glucose.firstOrNull()?.ts ?: rawWindowStart
        )

        val beforeResult = runCounterfactual(before, windowStart, windowEnd)
        val afterResult = runCounterfactual(after, windowStart, windowEnd)

        return CounterfactualReplayReport(
            generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            windowStart = windowStart,
            windowEnd = windowEnd,
            beforeDbPath = beforeDbPath,
            afterDbPath = afterDbPath,
            before = beforeResult,
            after = afterResult
        )
    }

    private fun loadDb(path: String): ReplayDbData {
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            val glucose = loadGlucose(conn)
            val therapy = loadTherapy(conn)
            val telemetry = loadTelemetry(conn)
            val therapyCounts = loadTherapyCounts(conn)
            return ReplayDbData(
                glucose = glucose,
                therapy = therapy,
                telemetryByKey = telemetry,
                therapyCounts = therapyCounts
            )
        }
    }

    private fun loadGlucose(conn: Connection): List<GlucosePoint> =
        conn.prepareStatement(
            """
            SELECT timestamp, mmol, source, quality
            FROM glucose_samples
            ORDER BY timestamp
            """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val quality = runCatching {
                            DataQuality.valueOf(rs.getString("quality")?.trim().orEmpty())
                        }.getOrDefault(DataQuality.OK)
                        add(
                            GlucosePoint(
                                ts = rs.getLong("timestamp"),
                                valueMmol = rs.getDouble("mmol"),
                                source = rs.getString("source") ?: "unknown",
                                quality = quality
                            )
                        )
                    }
                }
            }
        }

    private fun loadTherapy(conn: Connection): List<TherapyEvent> =
        conn.prepareStatement(
            """
            SELECT timestamp, type, payloadJson
            FROM therapy_events
            ORDER BY timestamp
            """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val payloadJson = rs.getString("payloadJson") ?: "{}"
                        val rawMap = runCatching {
                            gson.fromJson(payloadJson, Map::class.java)
                        }.getOrNull()
                        val payload = rawMap
                            ?.mapNotNull { (k, v) ->
                                val key = k?.toString()?.trim().orEmpty()
                                if (key.isEmpty()) null else key to (v?.toString() ?: "")
                            }
                            ?.toMap()
                            .orEmpty()
                        add(
                            TherapyEvent(
                                ts = rs.getLong("timestamp"),
                                type = rs.getString("type") ?: "unknown",
                                payload = payload
                            )
                        )
                    }
                }
            }
        }

    private fun loadTelemetry(conn: Connection): Map<String, List<TelemetryPoint>> =
        conn.prepareStatement(
            """
            SELECT timestamp, key, valueDouble
            FROM telemetry_samples
            WHERE valueDouble IS NOT NULL
            ORDER BY key, timestamp
            """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs ->
                val byKey = linkedMapOf<String, MutableList<TelemetryPoint>>()
                while (rs.next()) {
                    val key = rs.getString("key") ?: continue
                    val value = rs.getDouble("valueDouble")
                    byKey.getOrPut(key) { mutableListOf() }.add(
                        TelemetryPoint(
                            ts = rs.getLong("timestamp"),
                            value = value
                        )
                    )
                }
                byKey
            }
        }

    private fun loadTherapyCounts(conn: Connection): Map<String, Int> =
        conn.prepareStatement(
            """
            SELECT type, COUNT(*) AS cnt
            FROM therapy_events
            GROUP BY type
            """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        put(rs.getString("type") ?: "unknown", rs.getInt("cnt"))
                    }
                }
            }
        }

    private suspend fun runCounterfactual(
        data: ReplayDbData,
        windowStart: Long,
        windowEnd: Long
    ): CounterfactualReplayRunResult {
        val engine = HybridPredictionEngine(
            enableEnhancedPredictionV3 = true,
            enableUam = true,
            enableUamVirtualMealFit = true
        )
        val glucose = data.glucose
        val therapy = data.therapy
        if (glucose.isEmpty()) {
            return CounterfactualReplayRunResult(
                metricsByHorizon = emptyMap(),
                factorByHorizon = emptyMap(),
                therapyCountsInWindow = emptyMap()
            )
        }

        val glucoseTs = glucose.map { it.ts }
        val matched = mutableListOf<MatchedSample>()

        val alignedStart = (windowStart / FIVE_MIN_MS) * FIVE_MIN_MS
        val alignedEnd = (windowEnd / FIVE_MIN_MS) * FIVE_MIN_MS
        var bucketTs = alignedStart
        while (bucketTs <= alignedEnd) {
            val nowPoint = nearestPastGlucose(glucose, glucoseTs, bucketTs) ?: run {
                bucketTs += FIVE_MIN_MS
                continue
            }
            if (bucketTs - nowPoint.ts > STALE_MAX_MS) {
                bucketTs += FIVE_MIN_MS
                continue
            }

            val historyStart = nowPoint.ts - HISTORY_WINDOW_MS
            val therapyStart = nowPoint.ts - THERAPY_WINDOW_MS
            val glucoseWindow = glucose.filter { it.ts in historyStart..nowPoint.ts }
            if (glucoseWindow.size < 24) {
                bucketTs += FIVE_MIN_MS
                continue
            }
            val therapyWindow = therapy.filter { it.ts in therapyStart..nowPoint.ts }
            val forecasts = engine.predict(glucoseWindow, therapyWindow)
            forecasts.forEach { fc ->
                if (fc.horizonMinutes !in HORIZONS) return@forEach
                val actual = nearestGlucose(glucose, glucoseTs, fc.ts, forecastTolerance(fc.horizonMinutes)) ?: return@forEach
                val absError = abs(fc.valueMmol - actual.valueMmol)
                val ciWidth = (fc.ciHigh - fc.ciLow).coerceAtLeast(0.0)
                matched += MatchedSample(
                    generationTs = nowPoint.ts,
                    horizon = fc.horizonMinutes,
                    pred = fc.valueMmol,
                    actual = actual.valueMmol,
                    absError = absError,
                    ciWidth = ciWidth
                )
            }
            bucketTs += FIVE_MIN_MS
        }

        val metricsByH = HORIZONS.associateWith { h ->
            val rows = matched.filter { it.horizon == h }
            if (rows.isEmpty()) {
                CounterfactualMetrics.empty()
            } else {
                val n = rows.size
                val mae = rows.sumOf { it.absError } / n.toDouble()
                val rmse = sqrt(rows.sumOf { it.absError.pow(2) } / n.toDouble())
                val mard = rows.sumOf { it.absError / it.actual.coerceAtLeast(1e-6) * 100.0 } / n.toDouble()
                val bias = rows.sumOf { it.pred - it.actual } / n.toDouble()
                CounterfactualMetrics(n = n, mae = mae, rmse = rmse, mardPct = mard, bias = bias)
            }
        }

        val factorByH = HORIZONS.associateWith { h ->
            val rows = matched.filter { it.horizon == h }
            val iob = buildFactorContribution(rows) { sample ->
                nearestTelemetryMulti(
                    telemetryByKey = data.telemetryByKey,
                    keys = IOB_KEYS,
                    ts = sample.generationTs
                )
            }
            val uam = buildFactorContribution(rows) { sample ->
                val direct = nearestTelemetryMulti(
                    telemetryByKey = data.telemetryByKey,
                    keys = UAM_KEYS,
                    ts = sample.generationTs
                )
                if (direct != null && direct > 0.0) direct else null
            }
            val ci = buildFactorContribution(rows) { sample -> sample.ciWidth }
            mapOf(
                "IOB" to iob,
                "UAM" to uam,
                "CI" to ci
            )
        }

        val therapyCountsInWindow = data.therapy
            .asSequence()
            .filter { it.ts in windowStart..windowEnd }
            .groupingBy { it.type }
            .eachCount()

        return CounterfactualReplayRunResult(
            metricsByHorizon = metricsByH,
            factorByHorizon = factorByH,
            therapyCountsInWindow = therapyCountsInWindow
        )
    }

    private fun forecastTolerance(horizon: Int): Long = if (horizon <= 10) 5 * 60_000L else 15 * 60_000L

    private fun nearestPastGlucose(
        glucose: List<GlucosePoint>,
        glucoseTs: List<Long>,
        ts: Long
    ): GlucosePoint? {
        val idx = upperBound(glucoseTs, ts) - 1
        return if (idx in glucose.indices) glucose[idx] else null
    }

    private fun nearestGlucose(
        glucose: List<GlucosePoint>,
        glucoseTs: List<Long>,
        ts: Long,
        toleranceMs: Long
    ): GlucosePoint? {
        if (glucose.isEmpty()) return null
        val idx = lowerBound(glucoseTs, ts)
        var best: GlucosePoint? = null
        var bestGap = Long.MAX_VALUE
        if (idx in glucose.indices) {
            val g = glucose[idx]
            val gap = abs(g.ts - ts)
            if (gap < bestGap) {
                best = g
                bestGap = gap
            }
        }
        if (idx - 1 in glucose.indices) {
            val g = glucose[idx - 1]
            val gap = abs(g.ts - ts)
            if (gap < bestGap) {
                best = g
                bestGap = gap
            }
        }
        return best?.takeIf { bestGap <= toleranceMs }
    }

    private fun buildFactorContribution(
        rows: List<MatchedSample>,
        valueExtractor: (MatchedSample) -> Double?
    ): FactorContribution? {
        val pairs = rows.mapNotNull { row ->
            valueExtractor(row)?.let { value ->
                value to row.absError
            }
        }
        if (pairs.size < MIN_FACTOR_SAMPLES) return null

        val sortedValues = pairs.map { it.first }.sorted()
        val median = sortedValues[sortedValues.size / 2]
        val low = pairs.filter { it.first <= median }.map { it.second }
        val high = pairs.filter { it.first > median }.map { it.second }
        if (low.isEmpty() || high.isEmpty()) return null

        val maeLow = low.average()
        val maeHigh = high.average()
        val upliftPct = ((maeHigh - maeLow) / maeLow.coerceAtLeast(1e-9)) * 100.0
        val corr = pearson(pairs.map { it.first }, pairs.map { it.second })
        val score = abs(corr ?: 0.0) * ln(1.0 + abs(upliftPct))
        return FactorContribution(
            n = pairs.size,
            score = score,
            corr = corr,
            upliftPct = upliftPct
        )
    }

    private fun pearson(x: List<Double>, y: List<Double>): Double? {
        if (x.size != y.size || x.size < 3) return null
        val meanX = x.average()
        val meanY = y.average()
        var num = 0.0
        var denX = 0.0
        var denY = 0.0
        for (i in x.indices) {
            val dx = x[i] - meanX
            val dy = y[i] - meanY
            num += dx * dy
            denX += dx * dx
            denY += dy * dy
        }
        if (denX <= 1e-12 || denY <= 1e-12) return null
        return num / sqrt(denX * denY)
    }

    private fun nearestTelemetryMulti(
        telemetryByKey: Map<String, List<TelemetryPoint>>,
        keys: List<String>,
        ts: Long
    ): Double? {
        keys.forEach { key ->
            val value = nearestTelemetry(telemetryByKey[key].orEmpty(), ts)
            if (value != null) return value
        }
        return null
    }

    private fun nearestTelemetry(points: List<TelemetryPoint>, ts: Long): Double? {
        if (points.isEmpty()) return null
        val idx = lowerBound(points.map { it.ts }, ts)
        var best: TelemetryPoint? = null
        var bestGap = Long.MAX_VALUE
        fun check(i: Int) {
            if (i !in points.indices) return
            val p = points[i]
            val gap = abs(p.ts - ts)
            if (gap < bestGap) {
                bestGap = gap
                best = p
            }
        }
        check(idx)
        check(idx - 1)
        return best?.takeIf { bestGap <= FACTOR_MAX_GAP_MS }?.value
    }

    private fun lowerBound(arr: List<Long>, target: Long): Int {
        var lo = 0
        var hi = arr.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun upperBound(arr: List<Long>, target: Long): Int {
        var lo = 0
        var hi = arr.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid] <= target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        private const val FIVE_MIN_MS = 5 * 60_000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val HISTORY_WINDOW_MS = 6 * 60 * 60 * 1000L
        private const val THERAPY_WINDOW_MS = 8 * 60 * 60 * 1000L
        private const val STALE_MAX_MS = 10 * 60 * 1000L
        private const val FACTOR_MAX_GAP_MS = 10 * 60 * 1000L
        private const val MIN_FACTOR_SAMPLES = 20
        private val HORIZONS = listOf(5, 30, 60)
        private val IOB_KEYS = listOf("iob_units", "iob_real_units", "iob_effective_units", "raw_iob")
        private val UAM_KEYS = listOf(
            "uam_value",
            "uam_uci0_mmol5",
            "uam_calculated_delta5_mmol",
            "uam_calculated_flag",
            "uam_inferred_flag",
            "uam_inferred_carbs_grams",
            "uam_calculated_carbs_grams",
            "forecast_uam_60_mmol"
        )
    }
}

private data class ReplayDbData(
    val glucose: List<GlucosePoint>,
    val therapy: List<TherapyEvent>,
    val telemetryByKey: Map<String, List<TelemetryPoint>>,
    val therapyCounts: Map<String, Int>
)

private data class TelemetryPoint(
    val ts: Long,
    val value: Double
)

private data class MatchedSample(
    val generationTs: Long,
    val horizon: Int,
    val pred: Double,
    val actual: Double,
    val absError: Double,
    val ciWidth: Double
)

private data class CounterfactualMetrics(
    val n: Int,
    val mae: Double,
    val rmse: Double,
    val mardPct: Double,
    val bias: Double
) {
    companion object {
        fun empty(): CounterfactualMetrics = CounterfactualMetrics(
            n = 0,
            mae = 0.0,
            rmse = 0.0,
            mardPct = 0.0,
            bias = 0.0
        )
    }
}

private data class FactorContribution(
    val n: Int,
    val score: Double,
    val corr: Double?,
    val upliftPct: Double
)

private data class CounterfactualReplayRunResult(
    val metricsByHorizon: Map<Int, CounterfactualMetrics>,
    val factorByHorizon: Map<Int, Map<String, FactorContribution?>>,
    val therapyCountsInWindow: Map<String, Int>
)

private data class CounterfactualReplayReport(
    val generatedAt: String,
    val windowStart: Long,
    val windowEnd: Long,
    val beforeDbPath: String,
    val afterDbPath: String,
    val before: CounterfactualReplayRunResult,
    val after: CounterfactualReplayRunResult
) {
    fun toMarkdown(): String {
        val lines = mutableListOf<String>()
        lines += "# Counterfactual replay 24h (engine recompute) — IOB/UAM/CI"
        lines += ""
        lines += "- Generated at: $generatedAt"
        lines += "- Window: $windowStart .. $windowEnd (24h)"
        lines += "- Before DB: `$beforeDbPath`"
        lines += "- After DB: `$afterDbPath`"
        lines += ""
        lines += "## Therapy events in window"
        lines += "- Before: ${therapyLine(before.therapyCountsInWindow)}"
        lines += "- After: ${therapyLine(after.therapyCountsInWindow)}"
        lines += ""
        appendRun(lines, "Before", before)
        appendRun(lines, "After", after)
        lines += "## Delta (After - Before)"
        listOf(5, 30, 60).forEach { h ->
            val b = before.metricsByHorizon[h] ?: CounterfactualMetrics.empty()
            val a = after.metricsByHorizon[h] ?: CounterfactualMetrics.empty()
            lines += "- ${h}m: ΔMAE=${fmtSigned(a.mae - b.mae)}, ΔMARD=${fmtSigned(a.mardPct - b.mardPct)} pp, ΔBias=${fmtSigned(a.bias - b.bias)}, Δn=${a.n - b.n}"
            listOf("IOB", "UAM", "CI").forEach { factor ->
                val bf = before.factorByHorizon[h]?.get(factor)
                val af = after.factorByHorizon[h]?.get(factor)
                if (bf == null || af == null) {
                    lines += "  - $factor: n/a"
                } else {
                    val bCorr = bf.corr ?: 0.0
                    val aCorr = af.corr ?: 0.0
                    lines += "  - $factor: Δscore=${fmtSigned(af.score - bf.score)}, Δcorr=${fmtSigned(aCorr - bCorr)}, Δuplift=${fmtSigned(af.upliftPct - bf.upliftPct)}%"
                }
            }
        }
        lines += ""
        lines += "_Counterfactual replay note: forecasts were recomputed offline with HybridPredictionEngine from glucose+therapy history on each 5m cycle._"
        return lines.joinToString("\n")
    }

    private fun appendRun(
        lines: MutableList<String>,
        label: String,
        run: CounterfactualReplayRunResult
    ) {
        lines += "## $label"
        listOf(5, 30, 60).forEach { h ->
            val metrics = run.metricsByHorizon[h] ?: CounterfactualMetrics.empty()
            lines += "- ${h}m: n=${metrics.n}, MAE=${fmt(metrics.mae)}, RMSE=${fmt(metrics.rmse)}, MARD=${fmt(metrics.mardPct)}%, Bias=${fmt(metrics.bias)}"
            listOf("IOB", "UAM", "CI").forEach { factor ->
                val fc = run.factorByHorizon[h]?.get(factor)
                if (fc == null) {
                    lines += "  - $factor: n/a"
                } else {
                    lines += "  - $factor: score=${fmt(fc.score)}, corr=${fc.corr?.let(::fmt) ?: "n/a"}, uplift=${fmt(fc.upliftPct)}%, n=${fc.n}"
                }
            }
        }
        lines += ""
    }

    private fun therapyLine(map: Map<String, Int>): String {
        val carbs = map["carbs"] ?: 0
        val bolus = map["correction_bolus"] ?: 0
        val tempTarget = map["temp_target"] ?: 0
        return "carbs=$carbs, correction_bolus=$bolus, temp_target=$tempTarget"
    }

    private fun fmt(v: Double): String = ((v * 1000.0).roundToLong() / 1000.0).toString()
    private fun fmtSigned(v: Double): String = if (v >= 0) "+${fmt(v)}" else fmt(v)
}
