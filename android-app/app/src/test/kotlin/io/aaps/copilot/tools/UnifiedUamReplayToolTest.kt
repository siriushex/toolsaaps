package io.aaps.copilot.tools

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.aaps.copilot.data.repository.AutomationRepository
import io.aaps.copilot.data.repository.UamExportDecision
import io.aaps.copilot.data.repository.UamExportLedgerEntry
import io.aaps.copilot.data.repository.UamExportPolicy
import io.aaps.copilot.data.repository.UamExportPolicyInput
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.domain.predict.UamTagCodec
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Test

class UnifiedUamReplayToolTest {

    @Test
    fun generateUnifiedUamReplay() = runBlocking {
        val dbPath = System.getenv("COPILOT_REPLAY_DB")?.trim().orEmpty()
        val outputPath = System.getenv("COPILOT_REPLAY_OUT")?.trim().orEmpty()
        if (dbPath.isEmpty() || outputPath.isEmpty() || !File(dbPath).isFile) {
            println("unified_uam_replay_skipped missing COPILOT_REPLAY_DB/COPILOT_REPLAY_OUT or DB file")
            return@runBlocking
        }

        Locale.setDefault(Locale.US)
        Class.forName("org.sqlite.JDBC")
        val data = loadReplayData(dbPath)
        val report = UnifiedReplayRunner(data).run()
        val outputDir = File(outputPath).apply { mkdirs() }
        writeArtifacts(outputDir, dbPath, report)
        println("unified_uam_replay_report=${File(outputDir, SUMMARY_FILE).absolutePath}")

        check(report.nonCausalOnsetCount == 0) { "non-causal onset detected" }
        check(report.doubleCountCount == 0) { "meal pressure double count detected" }
        check(report.futureInputCount == 0) { "future replay input detected" }
        check(report.maxWriteGrams <= UamExportPolicy.MAX_INCREMENT_G + EPSILON)
        check(report.writeCount < 2 || report.minWriteIntervalMinutes >= UamExportPolicy.MIN_INTERVAL_MIN)
        check(report.maxRolling30Grams <= UamExportPolicy.MAX_ROLLING_30_G + EPSILON)
        check(report.maxRolling60Grams <= UamExportPolicy.MAX_EPISODE_60_G + EPSILON)
        check(report.maxEpisodeGrams <= UamExportPolicy.MAX_EPISODE_60_G + EPSILON)
        check(report.maxEpisodeWindowMinutes <= UamExportPolicy.EPISODE_WINDOW_MIN + EPSILON)
        check(report.sensorBlockedExportCount == 0)
        check(report.staleSourceExportCount == 0)
        check(report.determinismMismatchCount == 0)
    }

    private fun loadReplayData(path: String): ReplayData {
        DriverManager.getConnection("jdbc:sqlite:file:$path?mode=ro").use { connection ->
            return ReplayData(
                glucose = loadGlucose(connection),
                therapy = loadTherapy(connection),
                forecasts = loadStoredForecasts(connection),
                telemetry = loadTelemetry(connection)
            )
        }
    }

    private fun loadGlucose(connection: Connection): List<GlucosePoint> {
        val byTimestamp = linkedMapOf<Long, GlucosePoint>()
        connection.prepareStatement(
            "SELECT timestamp, mmol, source, quality FROM glucose_samples ORDER BY timestamp, id"
        ).use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val quality = runCatching {
                        DataQuality.valueOf(rows.getString("quality")?.trim().orEmpty())
                    }.getOrDefault(DataQuality.OK)
                    val point = GlucosePoint(
                        ts = rows.getLong("timestamp"),
                        valueMmol = rows.getDouble("mmol"),
                        source = rows.getString("source") ?: "unknown",
                        quality = quality
                    )
                    val existing = byTimestamp[point.ts]
                    if (existing == null || qualityRank(point.quality) >= qualityRank(existing.quality)) {
                        byTimestamp[point.ts] = point
                    }
                }
            }
        }
        return byTimestamp.values.sortedBy { it.ts }
    }

    private fun loadTherapy(connection: Connection): TherapyLoadResult {
        val gson = Gson()
        val truth = mutableListOf<TherapyEvent>()
        val synthetic = mutableListOf<HistoricalSyntheticWrite>()
        connection.prepareStatement(
            "SELECT timestamp, type, payloadJson FROM therapy_events ORDER BY timestamp, id"
        ).use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val timestamp = rows.getLong("timestamp")
                    val type = rows.getString("type") ?: "unknown"
                    val json = rows.getString("payloadJson") ?: "{}"
                    val payload = parsePayload(gson, json)
                    val note = payload["notes"] ?: payload["note"]
                    val tag = UamTagCodec.parseUamTag(note)
                    val syntheticUam = tag != null ||
                        note?.startsWith("UAM_ENGINE|") == true ||
                        payload["source"]?.equals("uam_engine", true) == true ||
                        payload["reason"]?.equals("uam_engine", true) == true
                    if (syntheticUam) {
                        synthetic += HistoricalSyntheticWrite(
                            ts = timestamp,
                            grams = payload.numeric("carbs", "carbsGrams", "grams") ?: 0.0,
                            note = note.orEmpty(),
                            version = tag?.ver,
                            episodeId = tag?.id
                        )
                    } else {
                        truth += TherapyEvent(ts = timestamp, type = type, payload = payload)
                    }
                }
            }
        }
        return TherapyLoadResult(truth.sortedBy { it.ts }, synthetic.sortedBy { it.ts })
    }

    private fun loadStoredForecasts(connection: Connection): Map<ForecastKey, StoredForecast> {
        val result = linkedMapOf<ForecastKey, StoredForecast>()
        connection.prepareStatement(
            "SELECT id, timestamp, horizonMinutes, valueMmol, ciLow, ciHigh, modelVersion " +
                "FROM forecasts WHERE horizonMinutes IN (5,30,60) ORDER BY id"
        ).use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val horizon = rows.getInt("horizonMinutes")
                    val targetTs = rows.getLong("timestamp")
                    val generationTs = targetTs - horizon * MINUTE_MS
                    result[ForecastKey(generationTs.bucket5(), horizon)] = StoredForecast(
                        generationTs = generationTs,
                        targetTs = targetTs,
                        horizon = horizon,
                        value = rows.getDouble("valueMmol"),
                        ciLow = rows.getDouble("ciLow"),
                        ciHigh = rows.getDouble("ciHigh"),
                        modelVersion = rows.getString("modelVersion") ?: "unknown"
                    )
                }
            }
        }
        return result
    }

    private fun loadTelemetry(connection: Connection): Map<String, List<TelemetryPoint>> {
        val relevant = setOf(
            "sensor_quality_score",
            "sensor_quality_blocked",
            "cob_effective_grams",
            "cob_grams",
            "iob_effective_units",
            "iob_units",
            "uam_runtime_flag",
            "uam_inferred_flag",
            "uam_calculated_flag"
        )
        val byKey = linkedMapOf<String, MutableList<TelemetryPoint>>()
        connection.prepareStatement(
            "SELECT timestamp, key, valueDouble FROM telemetry_samples " +
                "WHERE valueDouble IS NOT NULL ORDER BY key, timestamp"
        ).use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val key = rows.getString("key") ?: continue
                    if (key !in relevant) continue
                    byKey.getOrPut(key) { mutableListOf() } += TelemetryPoint(
                        ts = rows.getLong("timestamp"),
                        value = rows.getDouble("valueDouble")
                    )
                }
            }
        }
        return byKey.mapValues { (_, values) -> values.sortedBy { it.ts } }
    }

    private fun parsePayload(gson: Gson, json: String): Map<String, String> {
        val raw = runCatching { gson.fromJson(json, Map::class.java) }.getOrNull() ?: return emptyMap()
        return raw.entries.mapNotNull { entry ->
            val key = entry.key?.toString()?.trim().orEmpty()
            if (key.isEmpty()) null else key to (entry.value?.toString() ?: "")
        }.toMap()
    }

    private fun qualityRank(quality: DataQuality): Int = when (quality) {
        DataQuality.OK -> 3
        DataQuality.STALE -> 2
        DataQuality.SENSOR_ERROR -> 1
    }

    private fun writeArtifacts(outputDir: File, dbPath: String, report: ReplayReport) {
        File(outputDir, ROWS_FILE).writeText(report.rowsCsv(), Charsets.UTF_8)
        File(outputDir, METRICS_FILE).writeText(report.metricsCsv(), Charsets.UTF_8)
        File(outputDir, SAFETY_FILE).writeText(report.safetyCsv(), Charsets.UTF_8)
        File(outputDir, ACTIVATION_FILE).writeText(report.activationCsv(), Charsets.UTF_8)
        File(outputDir, SUMMARY_FILE).writeText(report.summaryMarkdown(dbPath), Charsets.UTF_8)
        val manifest = linkedMapOf<String, Any?>(
            "databasePath" to dbPath,
            "databaseSha256" to sha256(File(dbPath)),
            "rangeStartTs" to report.rangeStartTs,
            "rangeEndTs" to report.rangeEndTs,
            "rangeStart" to formatTs(report.rangeStartTs),
            "rangeEnd" to formatTs(report.rangeEndTs),
            "timezone" to ZoneId.systemDefault().id,
            "algorithmVersion" to report.algorithmVersion,
            "gitHead" to commandOutput("git", "rev-parse", "HEAD"),
            "gitDirty" to commandOutput("git", "status", "--porcelain").isNotBlank(),
            "constants" to mapOf(
                "maxIncrementG" to UamExportPolicy.MAX_INCREMENT_G,
                "minIntervalMin" to UamExportPolicy.MIN_INTERVAL_MIN,
                "maxRolling30G" to UamExportPolicy.MAX_ROLLING_30_G,
                "maxRolling60G" to UamExportPolicy.MAX_EPISODE_60_G,
                "maxSourceAgeMin" to UamExportPolicy.MAX_SOURCE_SNAPSHOT_AGE_MIN
            )
        )
        File(outputDir, MANIFEST_FILE).writeText(GsonBuilder().setPrettyPrinting().create().toJson(manifest))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun commandOutput(vararg command: String): String = runCatching {
        ProcessBuilder(*command).directory(File(System.getProperty("user.dir") ?: ".")).start().let { process ->
            process.inputStream.bufferedReader().readText().trim().also { process.waitFor() }
        }
    }.getOrDefault("")

    private data class ReplayData(
        val glucose: List<GlucosePoint>,
        val therapy: TherapyLoadResult,
        val forecasts: Map<ForecastKey, StoredForecast>,
        val telemetry: Map<String, List<TelemetryPoint>>
    )

    private data class TherapyLoadResult(
        val truth: List<TherapyEvent>,
        val synthetic: List<HistoricalSyntheticWrite>
    )

    private data class HistoricalSyntheticWrite(
        val ts: Long,
        val grams: Double,
        val note: String,
        val version: Int?,
        val episodeId: String?
    )

    private data class TelemetryPoint(val ts: Long, val value: Double)
    private data class ForecastKey(val generationBucketTs: Long, val horizon: Int)
    private data class StoredForecast(
        val generationTs: Long,
        val targetTs: Long,
        val horizon: Int,
        val value: Double,
        val ciLow: Double,
        val ciHigh: Double,
        val modelVersion: String
    )

    private inner class UnifiedReplayRunner(private val data: ReplayData) {
        private val glucoseTs = data.glucose.map { it.ts }
        private val therapyTs = data.therapy.truth.map { it.ts }
        private val candidateEngine = HybridPredictionEngine(
            enableEnhancedPredictionV3 = true,
            enableUam = true,
            enableUamVirtualMealFit = false
        )
        private val baselineEngine = HybridPredictionEngine(
            enableEnhancedPredictionV3 = true,
            enableUam = false,
            enableUamVirtualMealFit = false
        )

        suspend fun run(): ReplayReport {
            check(data.glucose.isNotEmpty()) { "Replay DB has no glucose" }
            val rows = mutableListOf<ReplayRow>()
            val predictions = mutableListOf<PredictionObservation>()
            val ledger = mutableListOf<UamExportLedgerEntry>()
            val candidateStates = mutableListOf<Pair<Long, Boolean>>()
            val storedStates = mutableListOf<Pair<Long, Boolean>>()
            var nonCausalOnsetCount = 0
            var doubleCountCount = 0
            var futureInputCount = 0
            var sensorBlockedExportCount = 0
            var staleSourceExportCount = 0
            var determinismMismatchCount = 0
            var deterministicChecks = 0

            val start = data.glucose.first().ts.bucket5Ceil() + HISTORY_MS
            val end = data.glucose.last().ts.bucket5()
            var bucket = start
            while (bucket <= end) {
                val glucoseEnd = upperBound(glucoseTs, bucket)
                val latest = data.glucose.getOrNull(glucoseEnd - 1)
                if (latest == null || bucket - latest.ts > MAX_REPLAY_GLUCOSE_AGE_MS) {
                    bucket += FIVE_MIN_MS
                    continue
                }
                val glucoseStart = lowerBound(glucoseTs, bucket - HISTORY_MS)
                val glucoseWindow = data.glucose.subList(glucoseStart, glucoseEnd)
                if (glucoseWindow.size < MIN_HISTORY_POINTS) {
                    bucket += FIVE_MIN_MS
                    continue
                }
                val therapyStart = lowerBound(therapyTs, bucket - THERAPY_HISTORY_MS)
                val therapyEnd = upperBound(therapyTs, bucket)
                val therapyWindow = data.therapy.truth.subList(therapyStart, therapyEnd)
                if (glucoseWindow.any { it.ts > bucket } || therapyWindow.any { it.ts > bucket }) {
                    futureInputCount += 1
                }

                val quality = qualityAt(bucket, therapyWindow)
                candidateEngine.setUamRuntimeQualityContext(
                    sensorTrust = quality.sensorTrust,
                    therapyCoverage = quality.therapyCoverage,
                    announcedCarbCoverage = quality.announcedCarbCoverage,
                    sensorBlocked = quality.sensorBlocked
                )
                baselineEngine.setUamRuntimeQualityContext(
                    sensorTrust = quality.sensorTrust,
                    therapyCoverage = quality.therapyCoverage,
                    announcedCarbCoverage = quality.announcedCarbCoverage,
                    sensorBlocked = quality.sensorBlocked
                )
                val candidateForecasts = candidateEngine.predict(glucoseWindow, therapyWindow)
                val diagnostics = candidateEngine.lastDiagnosticsForTest()
                val baselineForecasts = baselineEngine.predict(glucoseWindow, therapyWindow)
                if (diagnostics == null) {
                    bucket += FIVE_MIN_MS
                    continue
                }

                if (diagnostics.unifiedUamOnsetTs?.let { it > diagnostics.unifiedUamTimestamp } == true) {
                    nonCausalOnsetCount += 1
                }
                if (diagnostics.announcedMealPressureWeight + diagnostics.uamMealPressureWeight > 1.0 + EPSILON) {
                    doubleCountCount += 1
                }
                if (deterministicChecks < DETERMINISM_CHECKS) {
                    val first = diagnostics.fingerprint()
                    candidateEngine.predict(glucoseWindow, therapyWindow)
                    val second = candidateEngine.lastDiagnosticsForTest()?.fingerprint()
                    if (first != second) determinismMismatchCount += 1
                    deterministicChecks += 1
                }

                val episodeId = AutomationRepository.buildUnifiedUamEpisodeIdStatic(
                    diagnostics.unifiedUamAlgorithmVersion,
                    diagnostics.unifiedUamOnsetTs
                )
                val currentLedger = ledger.filter { it.episodeId == episodeId }
                val policyInput = UamExportPolicyInput(
                    nowTs = bucket,
                    episodeId = episodeId,
                    activeSinceTs = diagnostics.unifiedUamActiveSinceTs,
                    confidence = diagnostics.unifiedUamConfidence,
                    supportedLowerBoundGrams = diagnostics.unifiedUamLowerBoundCarbsGrams,
                    lowerBoundStableBuckets = diagnostics.unifiedUamLowerBoundStableBuckets,
                    sensorTrust = diagnostics.unifiedUamSensorTrust,
                    sensorBlocked = quality.sensorBlocked,
                    signedResidualMmol5 = diagnostics.unifiedUamSignedResidualMmol5,
                    shortAverageDeltaMmol5 = diagnostics.unifiedUamShortAverageDeltaMmol5,
                    currentGlucoseMmol = glucoseWindow.last().valueMmol,
                    forecastMinimumMmol = candidateForecasts.minOf { forecast ->
                        minOf(forecast.valueMmol, forecast.ciLow)
                    },
                    effectiveCobGrams = quality.effectiveCob,
                    therapyCoverage = diagnostics.unifiedUamTherapyCoverage,
                    remoteLedger = currentLedger,
                    sourceSnapshotTs = diagnostics.unifiedUamTimestamp,
                    globalRemoteLedger = ledger.toList()
                )
                val decision = UamExportPolicy.decide(policyInput)
                if (decision is UamExportDecision.Send) {
                    ledger += UamExportLedgerEntry(
                        tsMs = decision.treatmentTs,
                        grams = decision.grams,
                        seq = decision.seq,
                        episodeId = episodeId
                    )
                    if (quality.sensorBlocked) sensorBlockedExportCount += 1
                    if (bucket - diagnostics.unifiedUamTimestamp > UamExportPolicy.MAX_SOURCE_SNAPSHOT_AGE_MIN * MINUTE_MS) {
                        staleSourceExportCount += 1
                    }
                }

                addPredictionObservations(bucket, candidateForecasts, "candidate", predictions)
                addPredictionObservations(bucket, baselineForecasts, "disabled_baseline", predictions)
                HORIZONS.forEach { horizon ->
                    data.forecasts[ForecastKey(bucket, horizon)]?.let { stored ->
                        addPredictionObservation(
                            generationTs = bucket,
                            targetTs = stored.targetTs,
                            horizon = horizon,
                            value = stored.value,
                            ciLow = stored.ciLow,
                            ciHigh = stored.ciHigh,
                            role = "stored_production",
                            predictions = predictions
                        )
                    }
                }

                val candidateActive = diagnostics.unifiedUamActiveForForecast
                candidateStates += bucket to candidateActive
                nearestTelemetry("uam_runtime_flag", bucket, STORED_STATE_MAX_AGE_MS)?.let {
                    storedStates += bucket to (it.value >= 0.5)
                }
                rows += ReplayRow(
                    bucketTs = bucket,
                    glucoseTs = diagnostics.unifiedUamTimestamp,
                    sensorTrust = diagnostics.unifiedUamSensorTrust,
                    therapyCoverage = diagnostics.unifiedUamTherapyCoverage,
                    effectiveCob = quality.effectiveCob,
                    state = diagnostics.unifiedUamState,
                    confidence = diagnostics.unifiedUamConfidence,
                    equivalentGrams = diagnostics.unifiedUamEquivalentCarbsGrams,
                    lowerBoundGrams = diagnostics.unifiedUamLowerBoundCarbsGrams,
                    episodeId = episodeId,
                    decision = if (decision is UamExportDecision.Send) "SEND" else "BLOCK",
                    reason = (decision as? UamExportDecision.Block)?.reason ?: "send",
                    sendGrams = (decision as? UamExportDecision.Send)?.grams ?: 0.0,
                    cumulativeGrams = ledger.filter { it.episodeId == episodeId }.sumOf { it.grams },
                    rolling30Grams = ledger.filter { it.tsMs > bucket - 30 * MINUTE_MS }.sumOf { it.grams },
                    rolling60Grams = ledger.filter { it.tsMs > bucket - 60 * MINUTE_MS }.sumOf { it.grams }
                )
                bucket += FIVE_MIN_MS
            }

            val metrics = buildMetrics(predictions)
            val writeStats = buildWriteStats(ledger)
            val activationGates = buildActivationGates(metrics, candidateStates, storedStates)
            return ReplayReport(
                rangeStartTs = start,
                rangeEndTs = end,
                rows = rows,
                metrics = metrics,
                activationGates = activationGates,
                historicalSyntheticWrites = data.therapy.synthetic,
                algorithmVersion = rows.firstOrNull { it.episodeId.isNotBlank() }
                    ?.episodeId?.substringBefore(':') ?: "unified-uam-v1",
                nonCausalOnsetCount = nonCausalOnsetCount,
                doubleCountCount = doubleCountCount,
                futureInputCount = futureInputCount,
                sensorBlockedExportCount = sensorBlockedExportCount,
                staleSourceExportCount = staleSourceExportCount,
                determinismMismatchCount = determinismMismatchCount,
                writeCount = ledger.size,
                maxWriteGrams = writeStats.maxWrite,
                minWriteIntervalMinutes = writeStats.minIntervalMinutes,
                maxRolling30Grams = writeStats.maxRolling30,
                maxRolling60Grams = writeStats.maxRolling60,
                maxEpisodeGrams = writeStats.maxEpisode,
                maxEpisodeWindowMinutes = writeStats.maxEpisodeWindowMinutes,
                candidateStateFlipRate = flipRate(candidateStates),
                storedStateFlipRate = flipRate(storedStates),
                storedStateOverlapCount = storedStates.size
            )
        }

        private fun qualityAt(nowTs: Long, therapy: List<TherapyEvent>): ReplayQuality {
            val sensorScore = nearestTelemetry("sensor_quality_score", nowTs, QUALITY_MAX_AGE_MS)?.value ?: 0.0
            val sensorBlocked = nearestTelemetry("sensor_quality_blocked", nowTs, QUALITY_MAX_AGE_MS)
                ?.value?.let { it >= 0.5 } ?: true
            val iobPoint = nearestTelemetryMulti(listOf("iob_effective_units", "iob_units"), nowTs, IOB_MAX_AGE_MS)
            val cobPoint = nearestTelemetryMulti(listOf("cob_effective_grams", "cob_grams"), nowTs, COB_MAX_AGE_MS)
            val iob = iobPoint?.value ?: 0.0
            val cob = cobPoint?.value ?: 0.0
            val insulinTimestamps = therapy.asSequence()
                .filter { event -> event.type.contains("bolus", true) || event.payload.numeric("insulin", "units") != null }
                .map { it.ts }
                .toList()
            val carbTherapyAvailable = therapy.any { event ->
                event.type.contains("carb", true) && nowTs - event.ts <= CARB_EVIDENCE_MS
            }
            val quality = AutomationRepository.resolveUamRuntimeQualityStatic(
                sensorQualityScore = sensorScore,
                sensorBlocked = sensorBlocked,
                nowTs = nowTs,
                effectiveDiaHours = REPLAY_DIA_HOURS,
                insulinEvidenceTimestamps = insulinTimestamps,
                iobSampleTs = iobPoint?.ts,
                iobUnits = iob,
                effectiveCobGrams = cob,
                externalCobGrams = cobPoint?.value,
                carbTherapyAvailable = carbTherapyAvailable
            )
            return ReplayQuality(
                sensorTrust = quality.sensorTrust,
                therapyCoverage = quality.therapyCoverage,
                announcedCarbCoverage = quality.announcedCarbCoverage,
                sensorBlocked = quality.sensorBlocked,
                effectiveCob = cob
            )
        }

        private fun addPredictionObservations(
            generationTs: Long,
            forecasts: List<Forecast>,
            role: String,
            predictions: MutableList<PredictionObservation>
        ) {
            forecasts.filter { it.horizonMinutes in HORIZONS }.forEach { forecast ->
                addPredictionObservation(
                    generationTs = generationTs,
                    targetTs = forecast.ts,
                    horizon = forecast.horizonMinutes,
                    value = forecast.valueMmol,
                    ciLow = forecast.ciLow,
                    ciHigh = forecast.ciHigh,
                    role = role,
                    predictions = predictions
                )
            }
        }

        private fun addPredictionObservation(
            generationTs: Long,
            targetTs: Long,
            horizon: Int,
            value: Double,
            ciLow: Double,
            ciHigh: Double,
            role: String,
            predictions: MutableList<PredictionObservation>
        ) {
            val actual = nearestGlucose(targetTs, ACTUAL_TOLERANCE_MS[horizon] ?: 5 * MINUTE_MS) ?: return
            predictions += PredictionObservation(
                generationTs = generationTs,
                horizon = horizon,
                role = role,
                predicted = value,
                actual = actual.valueMmol,
                ciLow = ciLow,
                ciHigh = ciHigh
            )
        }

        private fun nearestGlucose(ts: Long, toleranceMs: Long): GlucosePoint? {
            val index = lowerBound(glucoseTs, ts)
            val candidates = listOfNotNull(data.glucose.getOrNull(index), data.glucose.getOrNull(index - 1))
            return candidates.minByOrNull { abs(it.ts - ts) }?.takeIf { abs(it.ts - ts) <= toleranceMs }
        }

        private fun nearestTelemetryMulti(keys: List<String>, ts: Long, maxAgeMs: Long): TelemetryPoint? =
            keys.mapNotNull { nearestTelemetry(it, ts, maxAgeMs) }.maxByOrNull { it.ts }

        private fun nearestTelemetry(key: String, ts: Long, maxAgeMs: Long): TelemetryPoint? {
            val points = data.telemetry[key].orEmpty()
            if (points.isEmpty()) return null
            val index = upperBound(points.map { it.ts }, ts) - 1
            return points.getOrNull(index)?.takeIf { ts - it.ts <= maxAgeMs }
        }
    }

    private data class ReplayQuality(
        val sensorTrust: Double,
        val therapyCoverage: Double,
        val announcedCarbCoverage: Double,
        val sensorBlocked: Boolean,
        val effectiveCob: Double
    )

    private data class ReplayRow(
        val bucketTs: Long,
        val glucoseTs: Long,
        val sensorTrust: Double,
        val therapyCoverage: Double,
        val effectiveCob: Double,
        val state: String,
        val confidence: Double,
        val equivalentGrams: Double?,
        val lowerBoundGrams: Double?,
        val episodeId: String,
        val decision: String,
        val reason: String,
        val sendGrams: Double,
        val cumulativeGrams: Double,
        val rolling30Grams: Double,
        val rolling60Grams: Double
    )

    private data class PredictionObservation(
        val generationTs: Long,
        val horizon: Int,
        val role: String,
        val predicted: Double,
        val actual: Double,
        val ciLow: Double,
        val ciHigh: Double
    )

    private data class Metric(
        val role: String,
        val horizon: Int,
        val n: Int,
        val mae: Double,
        val rmse: Double,
        val bias: Double,
        val ciCoverage: Double,
        val ciWidth: Double,
        val lowActualCount: Int,
        val lowMissRate: Double
    )

    private data class Gate(val name: String, val status: String, val detail: String)
    private data class WriteStats(
        val maxWrite: Double,
        val minIntervalMinutes: Double,
        val maxRolling30: Double,
        val maxRolling60: Double,
        val maxEpisode: Double,
        val maxEpisodeWindowMinutes: Double
    )

    private data class ReplayReport(
        val rangeStartTs: Long,
        val rangeEndTs: Long,
        val rows: List<ReplayRow>,
        val metrics: List<Metric>,
        val activationGates: List<Gate>,
        val historicalSyntheticWrites: List<HistoricalSyntheticWrite>,
        val algorithmVersion: String,
        val nonCausalOnsetCount: Int,
        val doubleCountCount: Int,
        val futureInputCount: Int,
        val sensorBlockedExportCount: Int,
        val staleSourceExportCount: Int,
        val determinismMismatchCount: Int,
        val writeCount: Int,
        val maxWriteGrams: Double,
        val minWriteIntervalMinutes: Double,
        val maxRolling30Grams: Double,
        val maxRolling60Grams: Double,
        val maxEpisodeGrams: Double,
        val maxEpisodeWindowMinutes: Double,
        val candidateStateFlipRate: Double?,
        val storedStateFlipRate: Double?,
        val storedStateOverlapCount: Int
    ) {
        fun hardGates(): List<Gate> = listOf(
            gate("non_causal_onset", nonCausalOnsetCount == 0, nonCausalOnsetCount),
            gate("double_count", doubleCountCount == 0, doubleCountCount),
            gate("future_input", futureInputCount == 0, futureInputCount),
            gate("max_write_15g", maxWriteGrams <= 15.0 + EPSILON, maxWriteGrams),
            gate("min_interval_10m", writeCount < 2 || minWriteIntervalMinutes >= 10.0, minWriteIntervalMinutes),
            gate("rolling_30m_30g", maxRolling30Grams <= 30.0 + EPSILON, maxRolling30Grams),
            gate("rolling_60m_50g", maxRolling60Grams <= 50.0 + EPSILON, maxRolling60Grams),
            gate("episode_50g", maxEpisodeGrams <= 50.0 + EPSILON, maxEpisodeGrams),
            gate("episode_window_60m", maxEpisodeWindowMinutes <= 60.0 + EPSILON, maxEpisodeWindowMinutes),
            gate("sensor_blocked_export", sensorBlockedExportCount == 0, sensorBlockedExportCount),
            gate("stale_source_export", staleSourceExportCount == 0, staleSourceExportCount),
            gate("determinism", determinismMismatchCount == 0, determinismMismatchCount)
        )

        fun rowsCsv(): String = buildString {
            appendLine("bucket_ts,bucket_local,glucose_ts,sensor_trust,therapy_coverage,effective_cob_g,state,confidence,equivalent_g,lower_bound_g,episode_id,decision,reason,send_g,cumulative_g,rolling_30m_g,rolling_60m_g")
            rows.forEach { row ->
                appendLine(
                    listOf(
                        row.bucketTs,
                        formatTs(row.bucketTs),
                        row.glucoseTs,
                        fmt(row.sensorTrust),
                        fmt(row.therapyCoverage),
                        fmt(row.effectiveCob),
                        row.state,
                        fmt(row.confidence),
                        fmt(row.equivalentGrams),
                        fmt(row.lowerBoundGrams),
                        row.episodeId,
                        row.decision,
                        row.reason,
                        fmt(row.sendGrams),
                        fmt(row.cumulativeGrams),
                        fmt(row.rolling30Grams),
                        fmt(row.rolling60Grams)
                    ).joinToString(",")
                )
            }
        }

        fun metricsCsv(): String = buildString {
            appendLine("role,horizon_min,n,mae,rmse,bias,ci_coverage,ci_width,low_actual_n,low_miss_rate")
            metrics.forEach { metric ->
                appendLine(
                    listOf(
                        metric.role,
                        metric.horizon,
                        metric.n,
                        fmt(metric.mae),
                        fmt(metric.rmse),
                        fmt(metric.bias),
                        fmt(metric.ciCoverage),
                        fmt(metric.ciWidth),
                        metric.lowActualCount,
                        fmt(metric.lowMissRate)
                    ).joinToString(",")
                )
            }
        }

        fun safetyCsv(): String = buildString {
            appendLine("gate,status,detail")
            hardGates().forEach { appendLine("${it.name},${it.status},${it.detail}") }
        }

        fun activationCsv(): String = buildString {
            appendLine("gate,status,detail")
            activationGates.forEach { appendLine("${it.name},${it.status},${it.detail}") }
        }

        fun summaryMarkdown(dbPath: String): String = buildString {
            appendLine("# Unified UAM 30-day causal replay")
            appendLine()
            appendLine("- DB: `$dbPath`")
            appendLine("- Range: ${formatTs(rangeStartTs)} - ${formatTs(rangeEndTs)}")
            appendLine("- Buckets evaluated: ${rows.size}")
            appendLine("- Simulated writes: $writeCount")
            appendLine("- Historical synthetic UAM writes: ${historicalSyntheticWrites.size}")
            appendLine()
            appendLine("## Hard safety gates")
            hardGates().forEach { appendLine("- ${it.status}: ${it.name} (${it.detail})") }
            appendLine()
            appendLine("## Activation gates")
            activationGates.forEach { appendLine("- ${it.status}: ${it.name} (${it.detail})") }
            appendLine()
            appendLine("## State stability")
            appendLine("- Candidate flip rate: ${fmt(candidateStateFlipRate)}")
            appendLine("- Stored legacy flip rate: ${fmt(storedStateFlipRate)}")
            appendLine("- Stored overlap buckets: $storedStateOverlapCount")
            appendLine()
            appendLine("## Incident cohorts")
            listOf("2026-07-03" to "2026-07-06", "2026-07-13" to "2026-07-15").forEach { (from, to) ->
                val cohort = rows.filter { formatDate(it.bucketTs) in from..to }
                appendLine("- $from..$to: buckets=${cohort.size}, active=${cohort.count { it.state == "ACTIVE" }}, writes=${cohort.count { it.sendGrams > 0.0 }}, maxWrite=${fmt(cohort.maxOfOrNull { it.sendGrams } ?: 0.0)} g")
            }
            appendLine()
            appendLine("## Historical synthetic writes")
            if (historicalSyntheticWrites.isEmpty()) appendLine("- None")
            historicalSyntheticWrites.forEach { write ->
                appendLine("- ${formatTs(write.ts)}: ${fmt(write.grams)} g, ver=${write.version ?: 0}, episode=${write.episodeId ?: "unknown"}")
            }
            val large = historicalSyntheticWrites.filter { it.grams > UamExportPolicy.MAX_INCREMENT_G }
            appendLine("- Historical writes above current 15 g cap: ${large.size}")
            appendLine("- Candidate writes above current 15 g cap: ${rows.count { it.sendGrams > UamExportPolicy.MAX_INCREMENT_G + EPSILON }}")
            appendLine()
            appendLine("This is a retrospective decision-quality replay, not a causal treatment-outcome claim.")
        }

        private fun gate(name: String, pass: Boolean, value: Any): Gate =
            Gate(name, if (pass) "PASS" else "FAIL", value.toString())
    }

    private fun buildMetrics(predictions: List<PredictionObservation>): List<Metric> =
        predictions.groupBy { it.role to it.horizon }
            .toSortedMap(compareBy<Pair<String, Int>> { it.first }.thenBy { it.second })
            .map { (key, rows) ->
                val errors = rows.map { it.predicted - it.actual }
                val lowRows = rows.filter { it.actual < LOW_GLUCOSE_MMOL }
                Metric(
                    role = key.first,
                    horizon = key.second,
                    n = rows.size,
                    mae = errors.map(::abs).averageOrZero(),
                    rmse = sqrt(errors.map { it.pow(2) }.averageOrZero()),
                    bias = errors.averageOrZero(),
                    ciCoverage = rows.map { if (it.actual in it.ciLow..it.ciHigh) 1.0 else 0.0 }.averageOrZero(),
                    ciWidth = rows.map { (it.ciHigh - it.ciLow).coerceAtLeast(0.0) }.averageOrZero(),
                    lowActualCount = lowRows.size,
                    lowMissRate = lowRows.map { if (it.predicted >= LOW_GLUCOSE_MMOL) 1.0 else 0.0 }.averageOrZero()
                )
            }

    private fun buildActivationGates(
        metrics: List<Metric>,
        candidateStates: List<Pair<Long, Boolean>>,
        storedStates: List<Pair<Long, Boolean>>
    ): List<Gate> {
        val result = mutableListOf<Gate>()
        HORIZONS.forEach { horizon ->
            val candidate = metrics.firstOrNull { it.role == "candidate" && it.horizon == horizon }
            val baseline = metrics.firstOrNull { it.role == "disabled_baseline" && it.horizon == horizon }
            if (candidate == null || baseline == null || minOf(candidate.n, baseline.n) < MIN_METRIC_SAMPLES) {
                result += Gate("mae_${horizon}m", "INSUFFICIENT", "missing matched samples")
            } else {
                val delta = candidate.mae - baseline.mae
                result += Gate("mae_${horizon}m", if (delta <= MAE_TOLERANCE_MMOL) "PASS" else "FAIL", "delta=${fmt(delta)}")
                val lowDelta = candidate.lowMissRate - baseline.lowMissRate
                val lowStatus = if (minOf(candidate.lowActualCount, baseline.lowActualCount) < MIN_LOW_SAMPLES) {
                    "INSUFFICIENT"
                } else if (lowDelta <= EPSILON) {
                    "PASS"
                } else {
                    "FAIL"
                }
                result += Gate("low_miss_${horizon}m", lowStatus, "delta=${fmt(lowDelta)},n=${candidate.lowActualCount}")
                val coverageDelta = candidate.ciCoverage - baseline.ciCoverage
                result += Gate(
                    "ci_coverage_${horizon}m",
                    if (coverageDelta >= -MAX_CI_COVERAGE_DROP) "PASS" else "FAIL",
                    "delta=${fmt(coverageDelta)}"
                )
            }
        }
        val candidate60 = metrics.firstOrNull { it.role == "candidate" && it.horizon == 60 }
        val baseline60 = metrics.firstOrNull { it.role == "disabled_baseline" && it.horizon == 60 }
        if (candidate60 == null || baseline60 == null) {
            result += Gate("positive_bias_60m", "INSUFFICIENT", "missing metrics")
        } else {
            val limit = maxOf(0.50, baseline60.bias + 0.20)
            result += Gate("positive_bias_60m", if (candidate60.bias <= limit) "PASS" else "FAIL", "candidate=${fmt(candidate60.bias)},limit=${fmt(limit)}")
        }
        val candidateFlip = flipRate(candidateStates)
        val storedFlip = flipRate(storedStates)
        val flipGate = when {
            storedStates.size < MIN_STATE_OVERLAP || candidateFlip == null || storedFlip == null ->
                Gate("state_flip_rate", "INSUFFICIENT", "overlap=${storedStates.size}")
            storedFlip <= EPSILON ->
                Gate("state_flip_rate", "INSUFFICIENT", "stored flip rate is zero")
            candidateFlip <= storedFlip * STATE_FLIP_MAX_RATIO ->
                Gate("state_flip_rate", "PASS", "candidate=${fmt(candidateFlip)},stored=${fmt(storedFlip)}")
            else -> Gate("state_flip_rate", "FAIL", "candidate=${fmt(candidateFlip)},stored=${fmt(storedFlip)}")
        }
        result += flipGate
        return result
    }

    private fun buildWriteStats(ledger: List<UamExportLedgerEntry>): WriteStats {
        val sorted = ledger.sortedBy { it.tsMs }
        val minInterval = sorted.zipWithNext().minOfOrNull { (a, b) -> (b.tsMs - a.tsMs) / MINUTE_MS.toDouble() }
            ?: Double.POSITIVE_INFINITY
        var max30 = 0.0
        var max60 = 0.0
        sorted.forEach { entry ->
            max30 = maxOf(max30, sorted.filter { it.tsMs > entry.tsMs - 30 * MINUTE_MS && it.tsMs <= entry.tsMs }.sumOf { it.grams })
            max60 = maxOf(max60, sorted.filter { it.tsMs > entry.tsMs - 60 * MINUTE_MS && it.tsMs <= entry.tsMs }.sumOf { it.grams })
        }
        val episodes = sorted.groupBy { it.episodeId.orEmpty() }
        val maxEpisode = episodes.values.maxOfOrNull { rows -> rows.sumOf { it.grams } } ?: 0.0
        val maxWindow = episodes.values.maxOfOrNull { rows ->
            if (rows.size < 2) 0.0 else (rows.maxOf { it.tsMs } - rows.minOf { it.tsMs }) / MINUTE_MS.toDouble()
        } ?: 0.0
        return WriteStats(
            maxWrite = sorted.maxOfOrNull { it.grams } ?: 0.0,
            minIntervalMinutes = minInterval,
            maxRolling30 = max30,
            maxRolling60 = max60,
            maxEpisode = maxEpisode,
            maxEpisodeWindowMinutes = maxWindow
        )
    }

    private fun flipRate(states: List<Pair<Long, Boolean>>): Double? {
        if (states.size < 2) return null
        val sorted = states.distinctBy { it.first }.sortedBy { it.first }
        if (sorted.size < 2) return null
        return sorted.zipWithNext().count { (a, b) -> a.second != b.second }.toDouble() / (sorted.size - 1)
    }

    private fun HybridPredictionEngine.V3Diagnostics.fingerprint(): String = listOf(
        unifiedUamTimestamp,
        unifiedUamState,
        unifiedUamConfidence.toBits(),
        unifiedUamLowerBoundCarbsGrams?.toBits(),
        unifiedUamOnsetTs,
        unifiedUamActiveSinceTs,
        unifiedUamSensorTrust.toBits(),
        unifiedUamTherapyCoverage.toBits(),
        rawUnifiedUamStep.joinToString(";") { it.toBits().toString() }
    ).joinToString("|")

    private fun Map<String, String>.numeric(vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { key -> this[key]?.toDoubleOrNull() }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
    private fun Long.bucket5(): Long = (this / FIVE_MIN_MS) * FIVE_MIN_MS
    private fun Long.bucket5Ceil(): Long = ((this + FIVE_MIN_MS - 1L) / FIVE_MIN_MS) * FIVE_MIN_MS

    private fun lowerBound(values: List<Long>, target: Long): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[middle] < target) low = middle + 1 else high = middle
        }
        return low
    }

    private fun upperBound(values: List<Long>, target: Long): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[middle] <= target) low = middle + 1 else high = middle
        }
        return low
    }

    companion object {
        private const val FIVE_MIN_MS = 5 * 60_000L
        private const val MINUTE_MS = 60_000L
        private const val HISTORY_MS = 6 * 60 * 60_000L
        private const val THERAPY_HISTORY_MS = 24 * 60 * 60_000L
        private const val CARB_EVIDENCE_MS = 3 * 60 * 60_000L
        private const val MAX_REPLAY_GLUCOSE_AGE_MS = 7 * 60_000L
        private const val QUALITY_MAX_AGE_MS = 15 * 60_000L
        private const val IOB_MAX_AGE_MS = 15 * 60_000L
        private const val COB_MAX_AGE_MS = 15 * 60_000L
        private const val STORED_STATE_MAX_AGE_MS = 10 * 60_000L
        private const val REPLAY_DIA_HOURS = 6.0
        private const val MIN_HISTORY_POINTS = 24
        private const val DETERMINISM_CHECKS = 24
        private const val MIN_METRIC_SAMPLES = 100
        private const val MIN_LOW_SAMPLES = 10
        private const val MIN_STATE_OVERLAP = 100
        private const val LOW_GLUCOSE_MMOL = 3.9
        private const val MAE_TOLERANCE_MMOL = 0.02
        private const val MAX_CI_COVERAGE_DROP = 0.02
        private const val STATE_FLIP_MAX_RATIO = 0.90
        private const val EPSILON = 1e-9
        private val HORIZONS = listOf(5, 30, 60)
        private val ACTUAL_TOLERANCE_MS = mapOf(5 to 3 * MINUTE_MS, 30 to 5 * MINUTE_MS, 60 to 5 * MINUTE_MS)
        private const val ROWS_FILE = "replay_rows.csv"
        private const val METRICS_FILE = "metrics_by_horizon.csv"
        private const val SAFETY_FILE = "safety_gates.csv"
        private const val ACTIVATION_FILE = "activation_gates.csv"
        private const val SUMMARY_FILE = "summary.md"
        private const val MANIFEST_FILE = "manifest.json"
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")

        private fun fmt(value: Double?): String = when {
            value == null -> ""
            value.isInfinite() -> "N/A"
            !value.isFinite() -> ""
            else -> String.format(Locale.US, "%.6f", value)
        }

        private fun formatTs(ts: Long): String = TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))
        private fun formatDate(ts: Long): String = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }
}
