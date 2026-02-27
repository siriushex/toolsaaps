package io.aaps.copilot.service

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.remote.nightscout.NightscoutSgvEntry
import io.aaps.copilot.data.remote.nightscout.NightscoutTreatment
import io.aaps.copilot.data.remote.nightscout.NightscoutTreatmentRequest
import io.aaps.copilot.data.repository.AuditLogger
import io.aaps.copilot.data.repository.TelemetryMetricMapper
import io.aaps.copilot.util.UnitConverter
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking

class LocalNightscoutServer(
    private val db: CopilotDatabase,
    private val gson: Gson,
    private val auditLogger: AuditLogger
) {

    @Volatile
    private var server: EmbeddedServer? = null

    @Volatile
    private var currentPort: Int? = null

    @Synchronized
    fun update(enabled: Boolean, port: Int) {
        if (!enabled) {
            stopLocked()
            return
        }
        val safePort = port.coerceIn(1_024, 65_535)
        if (server != null && currentPort == safePort) return
        stopLocked()
        val next = EmbeddedServer(
            port = safePort,
            db = db,
            gson = gson,
            auditLogger = auditLogger
        )
        runCatching {
            next.start(SOCKET_TIMEOUT_MS, false)
            server = next
            currentPort = safePort
            runBlocking {
                auditLogger.info(
                    "local_nightscout_started",
                    mapOf("url" to "http://127.0.0.1:$safePort")
                )
            }
        }.onFailure { error ->
            stopLocked()
            runBlocking {
                auditLogger.error(
                    "local_nightscout_start_failed",
                    mapOf("port" to safePort, "error" to (error.message ?: "unknown"))
                )
            }
        }
    }

    @Synchronized
    fun stop() {
        stopLocked()
    }

    @Synchronized
    private fun stopLocked() {
        val active = server ?: return
        runCatching { active.stop() }
        runBlocking {
            auditLogger.info(
                "local_nightscout_stopped",
                mapOf("url" to currentPort?.let { "http://127.0.0.1:$it" }.orEmpty())
            )
        }
        server = null
        currentPort = null
    }

    private class EmbeddedServer(
        port: Int,
        private val db: CopilotDatabase,
        private val gson: Gson,
        private val auditLogger: AuditLogger
    ) : NanoHTTPD(HOST, port) {

        override fun serve(session: IHTTPSession): Response {
            val path = session.uri.trim()
            return runCatching {
                when {
                    path.equals("/api/v1/status.json", ignoreCase = true) -> jsonOk(
                        mapOf(
                            "status" to "ok",
                            "name" to "AAPS Predictive Copilot Local NS",
                            "version" to "local-1",
                            "serverTime" to Instant.now().toString()
                        )
                    )

                    path.equals("/api/v1/entries/sgv.json", ignoreCase = true) ||
                        path.equals("/api/v1/entries.json", ignoreCase = true) ||
                        path.equals("/api/v1/entries", ignoreCase = true) -> handleEntries(session)
                    path.equals("/api/v1/treatments.json", ignoreCase = true) ||
                        path.equals("/api/v1/treatments", ignoreCase = true) -> handleTreatments(session)
                    path.equals("/api/v1/devicestatus.json", ignoreCase = true) ||
                        path.equals("/api/v1/devicestatus", ignoreCase = true) -> handleDeviceStatus(session)
                    else -> jsonNotFound()
                }
            }.getOrElse { error ->
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    CONTENT_TYPE_JSON,
                    gson.toJson(mapOf("status" to "error", "message" to (error.message ?: "internal_error")))
                )
            }
        }

        private fun handleEntries(session: IHTTPSession): Response {
            if (session.method == Method.POST) {
                return handlePostEntries(session)
            }
            return handleGetEntries(session)
        }

        private fun handleGetEntries(session: IHTTPSession): Response {
            val count = firstInt(session, "count", 200).coerceIn(1, 5_000)
            val since = firstLong(session, "find[date][\$gte]", 0L).coerceAtLeast(0L)
            val rows = runBlocking {
                if (since > 0L) {
                    db.glucoseDao().since(since)
                } else {
                    db.glucoseDao().latest(count).reversed()
                }
            }
            val selected = rows
                .asSequence()
                .filter { it.timestamp >= since }
                .sortedByDescending { it.timestamp }
                .take(count)
                .map { sample ->
                    NightscoutSgvEntry(
                        date = sample.timestamp,
                        sgv = UnitConverter.mmolToMgdl(sample.mmol).toDouble(),
                        device = "copilot-local-ns",
                        type = "sgv"
                    )
                }
                .toList()
            return jsonOk(selected)
        }

        private fun handlePostEntries(session: IHTTPSession): Response {
            val parsed = parseJsonObjects(readBody(session))
                ?: return jsonBadRequest("invalid_json")
            if (parsed.objects.isEmpty()) {
                return jsonBadRequest("empty_payload")
            }

            val maxKnownTs = runBlocking { db.glucoseDao().maxTimestamp() ?: 0L }
            val rows = parsed.objects.mapNotNull { entry ->
                val sgvRaw = entry.findNumeric("sgv") ?: entry.findNumeric("glucose") ?: entry.findNumeric("value")
                val ts = parseFlexibleTimestamp(entry.findText("dateString"))
                    ?: parseFlexibleTimestamp(entry.findText("created_at"))
                    ?: parseFlexibleTimestamp(entry.findText("sysTime"))
                    ?: entry.findNumeric("date")?.toLong()
                    ?: entry.findNumeric("mills")?.toLong()
                    ?: System.currentTimeMillis()
                if (sgvRaw == null || ts <= maxKnownTs) return@mapNotNull null
                val mmol = if (sgvRaw > 35.0) UnitConverter.mgdlToMmol(sgvRaw) else sgvRaw
                val source = entry.findText("device")?.ifBlank { SOURCE_LOCAL_NS_ENTRY } ?: SOURCE_LOCAL_NS_ENTRY
                GlucoseSampleEntity(
                    timestamp = ts,
                    mmol = mmol.coerceIn(1.0, 33.0),
                    source = source,
                    quality = "OK"
                )
            }

            if (rows.isNotEmpty()) {
                runBlocking { db.glucoseDao().upsertAll(rows) }
            }
            runBlocking {
                auditLogger.info(
                    "local_nightscout_entries_post",
                    mapOf("received" to parsed.objects.size, "inserted" to rows.size)
                )
            }

            return jsonOk(
                mapOf(
                    "status" to "ok",
                    "received" to parsed.objects.size,
                    "inserted" to rows.size
                )
            )
        }

        private fun handleTreatments(session: IHTTPSession): Response {
            return if (session.method == Method.POST) {
                handlePostTreatment(session)
            } else {
                handleGetTreatments(session)
            }
        }

        private fun handleDeviceStatus(session: IHTTPSession): Response {
            return if (session.method == Method.POST) {
                handlePostDeviceStatus(session)
            } else {
                jsonOk(emptyList<Any>())
            }
        }

        private fun handleGetTreatments(session: IHTTPSession): Response {
            val count = firstInt(session, "count", 200).coerceIn(1, 5_000)
            val sinceRaw = firstParam(session, "find[created_at][\$gte]") ?: firstParam(session, "find[mills][\$gte]")
            val since = parseFlexibleTimestamp(sinceRaw) ?: 0L

            val rows = runBlocking { db.therapyDao().since(since.coerceAtLeast(0L)) }
            val selected = rows
                .asSequence()
                .sortedByDescending { it.timestamp }
                .take(count)
                .map { it.toNightscoutTreatment(gson) }
                .toList()
            return jsonOk(selected)
        }

        private fun handlePostTreatment(session: IHTTPSession): Response {
            val parsed = parseJsonObjects(readBody(session))
                ?: return jsonBadRequest("invalid_json")
            if (parsed.objects.isEmpty()) {
                return jsonBadRequest("empty_payload")
            }

            val responses = mutableListOf<NightscoutTreatment>()
            val therapyRows = mutableListOf<TherapyEventEntity>()
            val telemetryRows = mutableListOf<io.aaps.copilot.data.local.entity.TelemetrySampleEntity>()

            parsed.objects.forEach { item ->
                val request = runCatching {
                    gson.fromJson(item, NightscoutTreatmentRequest::class.java)
                }.getOrNull() ?: return@forEach
                if (request.eventType.isBlank()) return@forEach

                val timestamp = parseFlexibleTimestamp(request.createdAt) ?: System.currentTimeMillis()
                val treatmentId = "local-ns-${timestamp}-${UUID.randomUUID()}"

                val payload = linkedMapOf<String, String>().apply {
                    request.duration?.let { put("duration", it.toString()) }
                    request.targetTop?.let { put("targetTop", it.toString()) }
                    request.targetBottom?.let { put("targetBottom", it.toString()) }
                    request.carbs?.let { put("carbs", it.toString()) }
                    request.insulin?.let { put("insulin", it.toString()) }
                    request.reason?.let { put("reason", it) }
                    request.notes?.let { put("notes", it) }
                }

                therapyRows += TherapyEventEntity(
                    id = treatmentId,
                    timestamp = timestamp,
                    type = normalizeEventType(request.eventType),
                    payloadJson = gson.toJson(payload)
                )

                telemetryRows += TelemetryMetricMapper.fromNightscoutTreatment(
                    timestamp = timestamp,
                    source = SOURCE_LOCAL_NS_TREATMENT,
                    eventType = request.eventType,
                    payload = payload
                )

                responses += NightscoutTreatment(
                    id = treatmentId,
                    createdAt = Instant.ofEpochMilli(timestamp).toString(),
                    eventType = request.eventType,
                    carbs = request.carbs,
                    insulin = request.insulin,
                    duration = request.duration,
                    targetTop = request.targetTop,
                    targetBottom = request.targetBottom,
                    reason = request.reason,
                    notes = request.notes
                )
            }

            if (therapyRows.isEmpty()) {
                return jsonBadRequest("missing_event_type")
            }

            runBlocking {
                db.therapyDao().upsertAll(therapyRows)
                if (telemetryRows.isNotEmpty()) {
                    db.telemetryDao().upsertAll(telemetryRows.distinctBy { it.id })
                }
                auditLogger.info(
                    "local_nightscout_treatments_post",
                    mapOf(
                        "received" to parsed.objects.size,
                        "inserted" to therapyRows.size,
                        "telemetry" to telemetryRows.size
                    )
                )
            }

            return if (parsed.wasArray) jsonOk(responses) else jsonOk(responses.first())
        }

        private fun handlePostDeviceStatus(session: IHTTPSession): Response {
            val parsed = parseJsonObjects(readBody(session))
                ?: return jsonBadRequest("invalid_json")
            if (parsed.objects.isEmpty()) {
                return jsonBadRequest("empty_payload")
            }

            val telemetryRows = mutableListOf<io.aaps.copilot.data.local.entity.TelemetrySampleEntity>()
            parsed.objects.forEach { payload ->
                val payloadMap = payload.toMap(gson)
                val ts = parseFlexibleTimestamp(payload.findText("created_at"))
                    ?: payload.findNumeric("date")?.toLong()
                    ?: payload.findNumeric("mills")?.toLong()
                    ?: System.currentTimeMillis()
                val flattened = linkedMapOf<String, String>()
                TelemetryMetricMapper.flattenAny("openaps", payloadMap["openaps"], flattened)
                TelemetryMetricMapper.flattenAny("pump", payloadMap["pump"], flattened)
                TelemetryMetricMapper.flattenAny("uploader", payloadMap["uploader"], flattened)
                if (flattened.isNotEmpty()) {
                    telemetryRows += TelemetryMetricMapper.fromFlattenedNightscoutDeviceStatus(
                        timestamp = ts,
                        source = SOURCE_LOCAL_NS_DEVICESTATUS,
                        flattened = flattened
                    )
                }
            }
            if (telemetryRows.isNotEmpty()) {
                runBlocking {
                    db.telemetryDao().upsertAll(telemetryRows.distinctBy { it.id })
                    auditLogger.info(
                        "local_nightscout_devicestatus_post",
                        mapOf("received" to parsed.objects.size, "telemetry" to telemetryRows.size)
                    )
                }
            } else {
                runBlocking {
                    auditLogger.warn(
                        "local_nightscout_devicestatus_post",
                        mapOf("received" to parsed.objects.size, "telemetry" to 0)
                    )
                }
            }
            return jsonOk(
                mapOf(
                    "status" to "ok",
                    "received" to parsed.objects.size,
                    "telemetry" to telemetryRows.size
                )
            )
        }

        private fun TherapyEventEntity.toNightscoutTreatment(gson: Gson): NightscoutTreatment {
            val mapType = object : TypeToken<Map<String, String>>() {}.type
            val payload = runCatching {
                gson.fromJson<Map<String, String>>(payloadJson, mapType).orEmpty()
            }.getOrDefault(emptyMap())
            return NightscoutTreatment(
                id = id,
                createdAt = Instant.ofEpochMilli(timestamp).toString(),
                eventType = toNightscoutEventType(type),
                carbs = payload["carbs"]?.toDoubleOrNull(),
                insulin = payload["insulin"]?.toDoubleOrNull(),
                enteredBy = payload["enteredBy"],
                absolute = payload["absolute"]?.toDoubleOrNull(),
                rate = payload["rate"]?.toDoubleOrNull(),
                percentage = payload["percentage"]?.toIntOrNull(),
                duration = payload["duration"]?.toIntOrNull() ?: payload["durationMinutes"]?.toIntOrNull(),
                targetTop = payload["targetTop"]?.toDoubleOrNull(),
                targetBottom = payload["targetBottom"]?.toDoubleOrNull(),
                reason = payload["reason"],
                notes = payload["notes"]
            )
        }

        private fun readBody(session: IHTTPSession): String {
            val files = HashMap<String, String>()
            session.parseBody(files)
            return files["postData"].orEmpty()
        }

        private fun parseJsonObjects(raw: String): ParsedJsonPayload? {
            val text = raw.trim()
            if (text.isBlank()) return ParsedJsonPayload(objects = emptyList(), wasArray = false)
            val root = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return null
            return when {
                root.isJsonObject -> ParsedJsonPayload(
                    objects = listOf(root.asJsonObject),
                    wasArray = false
                )

                root.isJsonArray -> ParsedJsonPayload(
                    objects = root.asJsonArray.mapNotNull { it.asJsonObjectOrNull() },
                    wasArray = true
                )

                else -> null
            }
        }

        private fun firstParam(session: IHTTPSession, key: String): String? =
            session.parameters[key]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        private fun firstInt(session: IHTTPSession, key: String, fallback: Int): Int =
            firstParam(session, key)?.toIntOrNull() ?: fallback

        private fun firstLong(session: IHTTPSession, key: String, fallback: Long): Long =
            firstParam(session, key)?.toLongOrNull() ?: fallback

        private fun parseFlexibleTimestamp(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            val value = raw.trim()
            return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
                ?: value.toLongOrNull()?.let { ts -> if (ts < 10_000_000_000L) ts * 1000L else ts }
        }

        private fun normalizeEventType(eventType: String): String {
            return when (eventType.lowercase()) {
                "temporary target" -> "temp_target"
                "carb correction" -> "carbs"
                "meal bolus" -> "meal_bolus"
                "correction bolus" -> "correction_bolus"
                else -> eventType.lowercase().replace(" ", "_")
            }
        }

        private fun toNightscoutEventType(type: String): String {
            return when (type.lowercase()) {
                "temp_target" -> "Temporary Target"
                "carbs" -> "Carb Correction"
                "meal_bolus" -> "Meal Bolus"
                "correction_bolus" -> "Correction Bolus"
                else -> type.replace('_', ' ')
            }
        }

        private fun jsonOk(payload: Any): Response {
            return newFixedLengthResponse(
                Response.Status.OK,
                CONTENT_TYPE_JSON,
                gson.toJson(payload)
            )
        }

        private fun jsonBadRequest(message: String): Response {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                CONTENT_TYPE_JSON,
                gson.toJson(mapOf("status" to "bad_request", "message" to message))
            )
        }

        private fun jsonNotFound(): Response {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                CONTENT_TYPE_JSON,
                gson.toJson(mapOf("status" to "not_found"))
            )
        }

        private fun JsonElement.asJsonObjectOrNull(): JsonObject? {
            return if (isJsonObject) asJsonObject else null
        }

        private fun JsonObject.findText(field: String): String? {
            return runCatching { get(field) }
                .getOrNull()
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }

        private fun JsonObject.findNumeric(field: String): Double? {
            val direct = runCatching { get(field) }.getOrNull()
            if (direct != null && !direct.isJsonNull) {
                runCatching { return direct.asDouble }
                val text = runCatching { direct.asString }.getOrNull()
                if (!text.isNullOrBlank()) {
                    return text.replace(",", ".").toDoubleOrNull()
                }
            }
            return null
        }

        private fun JsonObject.toMap(gson: Gson): Map<String, Any?> {
            val type = object : TypeToken<Map<String, Any?>>() {}.type
            return runCatching { gson.fromJson<Map<String, Any?>>(this, type).orEmpty() }
                .getOrDefault(emptyMap())
        }

        private data class ParsedJsonPayload(
            val objects: List<JsonObject>,
            val wasArray: Boolean
        )
    }

    private companion object {
        private const val HOST = "127.0.0.1"
        private const val CONTENT_TYPE_JSON = "application/json; charset=utf-8"
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val SOURCE_LOCAL_NS_ENTRY = "local_nightscout_entry"
        private const val SOURCE_LOCAL_NS_TREATMENT = "local_nightscout_treatment"
        private const val SOURCE_LOCAL_NS_DEVICESTATUS = "local_nightscout_devicestatus"
    }
}
