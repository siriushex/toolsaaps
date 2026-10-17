package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.domain.model.TherapyCarbComponentKind
import io.aaps.copilot.domain.model.resolveTherapyComponents
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class RoomAiObservation(
    val key: String, val eventAtMs: Long, val source: ServerAiHistorySource,
    val quality: ServerAiHistoryQuality, val data: ServerAiHistoryData,
    val context: ServerAiHistoricalContext? = null
) { override fun toString() = "RoomAiObservation(redacted)" }

internal data class RoomAiHistoryPage(val afterRowId: Long, val afterEventAtMs: Long, val scanned: Int,
    val observations: List<RoomAiObservation>, val omitted: Int) {
    override fun toString() = "RoomAiHistoryPage(redacted)"
}

/** Read-only, fixed SQL/columns, bounded source pages. No arbitrary payload/text export. */
internal class RoomAiHistorySource(private val db: CopilotDatabase) {
    val streamCount = 5

    suspend fun read(stream: Int, afterRowId: Long, fromMs: Long, throughMs: Long, limit: Int,
        afterEventAtMs: Long = fromMs - 1): RoomAiHistoryPage =
        withContext(Dispatchers.IO) {
            require(stream in 0 until streamCount && afterRowId >= 0 && limit in 1..64)
            require(fromMs > 0 && throughMs >= fromMs)
            val (table, time) = TABLES[stream]
            val telemetryFilter = if (stream == 4) "AND key IN ('iob_effective_positive_units','iob_net_units','cob_grams'," +
                "'steps_count','active_minutes','calories_active_kcal','sensor_age_hours') " else ""
            fun bounded(column: String, limit: Int) = "CASE WHEN length(CAST($column AS BLOB)) <= $limit THEN $column ELSE NULL END AS $column"
            val idColumn = if (stream == 3) "cycleId" else "id"
            val columns = when (stream) {
                0 -> "mmol,${bounded("source", 64)},${bounded("quality", 64)}"
                1 -> "${bounded("type", 64)},${bounded("payloadJson", BoundedClinicalPayloadParser.MAX_PAYLOAD_BYTES)}"
                2 -> "horizonMinutes,valueMmol,ciLow,ciHigh,${bounded("modelVersion", 64)}"
                3 -> "isfEffective,crEffective"
                else -> "${bounded("source", 64)},${bounded("quality", 64)},${bounded("key", 64)},valueDouble,${bounded("unit", 32)}"
            }
            val sql = "SELECT rowid AS export_rowid,${bounded(idColumn, 256)},$time,$columns FROM $table INDEXED BY index_${table}_$time " +
                "WHERE $time BETWEEN ? AND ? AND ($time > ? OR rowid > ?) " + telemetryFilter +
                "ORDER BY $time ASC, rowid ASC LIMIT ?"
            val output = mutableListOf<RoomAiObservation>()
            val accepted = mutableSetOf<Long>()
            var last = afterRowId
            var lastTime = afterEventAtMs
            var scanned = 0
            db.openHelper.readableDatabase.query(sql,
                arrayOf<Any>(maxOf(fromMs, afterEventAtMs), throughMs, afterEventAtMs, afterRowId, limit)).use { c ->
                fun text(name: String): String? = c.getString(c.getColumnIndexOrThrow(name))
                fun number(name: String): Double? = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getDouble(it) }
                while (c.moveToNext()) {
                    last = c.getLong(c.getColumnIndexOrThrow("export_rowid"))
                    scanned++
                    val timestamp = c.getLong(c.getColumnIndexOrThrow(time))
                    lastTime = timestamp
                    val source = when (stream) {
                        0, 4 -> origin(text("source").orEmpty())
                        else -> ServerAiHistorySource.COPILOT
                    }
                    val quality = if (stream == 0 || stream == 4) quality(text("quality").orEmpty()) else ServerAiHistoryQuality.UNKNOWN
                    val id = text(idColumn) ?: continue
                    fun add(component: String, data: ServerAiHistoryData, context: ServerAiHistoricalContext? = null) {
                        // Kind/source/time are part of identity because server revisions cannot change them.
                        val identity = buildJsonArray {
                            add("room-core-v1"); add(table); add(id); add(timestamp); add(component); add(data.kind.name); add(source.name)
                        }
                        val key = roomAiDigest(HistoryJson.decodeUtf8(HistoryJson.bytes(identity)))
                        val item = RoomAiObservation(key, timestamp, source, quality, data, context)
                        // A malformed legacy row is an explicit omission, not an invented default.
                        if (runCatching { data.toWire(); context?.toWire() }.isSuccess) {
                            output += item
                            accepted += last
                        }
                    }
                    when (stream) {
                        0 -> number("mmol")?.takeIf { it.isFinite() && it in 0.1..50.0 }?.let {
                            add("glucose", ServerAiHistoryData.Glucose(it),
                                ServerAiHistoricalContext(glucoseBasis = ServerAiGlucoseBasis.UNKNOWN))
                        }
                        1 -> {
                            val type = text("type")?.trim()?.lowercase(Locale.US) ?: continue
                            if (TherapySanitizer.isLocalBroadcastArtifact(id, type)) continue
                            val payload = text("payloadJson")?.let(BoundedClinicalPayloadParser::parse) ?: continue
                            val parts = resolveTherapyComponents(type, payload.scalarValues(), payload.componentTrust())
                            parts.insulinU?.let { add("insulin", ServerAiHistoryData.Therapy(ServerAiTherapyEvent.BOLUS, insulinUnits = it)) }
                            // AAPS aliases must not duplicate the importer's authoritative stable carb row.
                            val canonicalRow = parts.canonicalCarbId?.let { id == "aaps-carb-$it" } ?: true
                            if (canonicalRow && parts.carbKind == TherapyCarbComponentKind.REAL) parts.carbsG?.let {
                                add("carbs", ServerAiHistoryData.Therapy(ServerAiTherapyEvent.CARBS, carbsGrams = it))
                            }
                            if (parts.wholeEventValid && type in setOf("sensor_change", "infusion_set_change")) {
                                add("device-change", ServerAiHistoryData.Therapy(if (type == "sensor_change")
                                    ServerAiTherapyEvent.SENSOR_CHANGE else ServerAiTherapyEvent.SET_CHANGE))
                            }
                        }
                        2 -> {
                            val horizon = c.getInt(c.getColumnIndexOrThrow("horizonMinutes"))
                            val value = number("valueMmol") ?: continue
                            add("forecast", ServerAiHistoryData.Forecast(horizon, value, text("modelVersion") ?: continue, number("ciLow"), number("ciHigh")))
                        }
                        3 -> add("effective", ServerAiHistoryData.Sensitivity(number("isfEffective"), number("crEffective")))
                        4 -> {
                            val value = number("valueDouble")?.takeIf { it.isFinite() } ?: continue
                            val unit = text("unit")
                            val data = when (text("key") to unit) {
                                "iob_effective_positive_units" to "U" -> ServerAiHistoryData.Sensitivity(positiveIobUnits = value)
                                "iob_net_units" to "U" -> ServerAiHistoryData.Sensitivity(netIobUnits = value)
                                "cob_grams" to "g" -> ServerAiHistoryData.Sensitivity(cobGrams = value)
                                "steps_count" to "steps" -> if (value in 0.0..1_000_000.0 && value % 1.0 == 0.0)
                                    ServerAiHistoryData.Activity(steps = value.toInt()) else null
                                "active_minutes" to "min" -> ServerAiHistoryData.Activity(activeMinutes = value)
                                "calories_active_kcal" to "kcal" -> ServerAiHistoryData.Activity(activeKcal = value)
                                "sensor_age_hours" to "h" -> ServerAiHistoryData.Device(sensorAgeHours = value)
                                else -> null
                            }
                            if (data != null) add(text("key") ?: continue, data)
                        }
                    }
                }
            }
            RoomAiHistoryPage(last, lastTime, scanned, output, scanned - accepted.size)
        }

    private fun origin(value: String) = when (value) {
        "aaps_broadcast" -> ServerAiHistorySource.AAPS
        "xdrip_broadcast" -> ServerAiHistorySource.CGM
        else -> ServerAiHistorySource.COPILOT
    }

    private fun quality(value: String) = when (value) {
        "OK" -> ServerAiHistoryQuality.OK
        "SUSPECT", "INVALID", "BAD" -> ServerAiHistoryQuality.SUSPECT
        else -> ServerAiHistoryQuality.UNKNOWN
    }

    private companion object {
        val TABLES = listOf("glucose_samples" to "timestamp", "therapy_events" to "timestamp",
            "forecasts" to "timestamp", "sensitivity_runtime_snapshots" to "generatedAt", "telemetry_samples" to "timestamp")
    }
}

internal fun roomAiDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(HistoryJson.encodeUtf8(value)).joinToString("") { "%02x".format(it) }
