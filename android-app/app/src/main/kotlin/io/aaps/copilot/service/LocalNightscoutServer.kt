package io.aaps.copilot.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.gson.JsonArray
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
import io.aaps.copilot.data.repository.GlucoseValueResolver
import io.aaps.copilot.data.repository.TelemetryMetricMapper
import io.aaps.copilot.util.GlucoseUnitNormalizer
import io.aaps.copilot.util.UnitConverter
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking

class LocalNightscoutServer(
    private val context: Context,
    private val db: CopilotDatabase,
    private val gson: Gson,
    private val auditLogger: AuditLogger,
    private val onReactiveDataIngested: (() -> Unit)? = null
) {

    @Volatile
    private var server: EmbeddedServer? = null

    @Volatile
    private var currentPort: Int? = null

    @Synchronized
    fun update(enabled: Boolean, port: Int): Int? {
        if (!enabled) {
            stopLocked()
            return null
        }
        val safePort = port.coerceIn(1_024, 65_535)
        if (server != null && currentPort == safePort) return safePort
        val alreadyRunningPort = currentPort
        stopLocked()
        val candidatePorts = buildCandidatePorts(safePort)
        val sslSocketFactory = runCatching {
            LocalNightscoutTls.createServerSocketFactory(context)
        }.getOrElse { error ->
            runBlocking {
                auditLogger.error(
                    "local_nightscout_tls_init_failed",
                    mapOf("error" to (error.message ?: "unknown"))
                )
            }
            return null
        }
        var lastError: Throwable? = null

        for (candidatePort in candidatePorts) {
            val next = EmbeddedServer(
                port = candidatePort,
                context = context,
                db = db,
                gson = gson,
                auditLogger = auditLogger,
                onReactiveDataIngested = onReactiveDataIngested
            )
            val started = runCatching {
                next.makeSecure(sslSocketFactory, null)
                next.start(SOCKET_TIMEOUT_MS, false)
                true
            }.getOrElse { error ->
                lastError = error
                runCatching { next.stop() }
                false
            }
            if (!started) continue

            server = next
            currentPort = candidatePort
            runBlocking {
                auditLogger.info(
                    "local_nightscout_started",
                    mapOf(
                        "url" to "https://127.0.0.1:$candidatePort",
                        "requestedPort" to safePort,
                        "actualPort" to candidatePort,
                        "reusedPortAfterRestart" to (alreadyRunningPort == candidatePort)
                    )
                )
                if (candidatePort != safePort) {
                    auditLogger.warn(
                        "local_nightscout_port_reassigned",
                        mapOf(
                            "requestedPort" to safePort,
                            "actualPort" to candidatePort
                        )
                    )
                }
            }
            return candidatePort
        }

        stopLocked()
        runBlocking {
            auditLogger.error(
                "local_nightscout_start_failed",
                mapOf("port" to safePort, "error" to (lastError?.message ?: "unknown"))
            )
        }
        return null
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
                mapOf("url" to currentPort?.let { "https://127.0.0.1:$it" }.orEmpty())
            )
        }
        server = null
        currentPort = null
    }

    private fun buildCandidatePorts(requestedPort: Int): List<Int> {
        val alternatives = (requestedPort + 1..(requestedPort + PORT_SCAN_WINDOW))
            .map { it.coerceAtMost(65_535) }
            .distinct()
            .filter { it != requestedPort }
        return listOf(requestedPort) + alternatives
    }

    private class EmbeddedServer(
        port: Int,
        private val context: Context,
        private val db: CopilotDatabase,
        private val gson: Gson,
        private val auditLogger: AuditLogger,
        private val onReactiveDataIngested: (() -> Unit)?
    ) : NanoHTTPD(HOST, port) {

        @Volatile
        private var lastExternalRequestLogTimestamp = 0L

        @Volatile
        private var lastExternalRequestSignature = ""

        private val socketSessions = ConcurrentHashMap<String, SocketSession>()
        private val socketSessionCounter = AtomicLong(1L)
        private val lastReactiveAutomationEnqueueTs = AtomicLong(0L)

        override fun serve(session: IHTTPSession): Response {
            val path = session.uri.trim()
            maybeAuditExternalRequest(session, path)
            return runCatching {
                when {
                    path.isBlank() || path == "/" -> htmlOk(
                        """
                        <html lang="en">
                        <head><meta charset="utf-8"><title>AAPS Predictive Copilot Local Nightscout</title></head>
                        <body>
                        <h2>AAPS Predictive Copilot Local Nightscout</h2>
                        <p>Loopback API is running.</p>
                        <ul>
                        <li><a href="/api/v1/status.json">/api/v1/status.json</a></li>
                        <li><a href="/api/v1/entries/sgv.json?count=1">/api/v1/entries/sgv.json?count=1</a></li>
                        <li><a href="/api/v1/treatments.json?count=1">/api/v1/treatments.json?count=1</a></li>
                        </ul>
                        </body>
                        </html>
                        """.trimIndent()
                    )
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
                    path.equals("/socket.io/", ignoreCase = true) ||
                        path.equals("/socket.io", ignoreCase = true) -> handleSocketIo(session)
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

        private fun maybeAuditExternalRequest(session: IHTTPSession, path: String) {
            val isApiPath = path.startsWith("/api/v1/", ignoreCase = true)
            val isSocketPath = path.startsWith("/socket.io", ignoreCase = true)
            if (!isApiPath && !isSocketPath) return
            val copilotHeader = session.headers[HEADER_COPILOT_CLIENT]?.trim().orEmpty()
            if (copilotHeader.isNotBlank()) return

            val method = session.method.name
            val userAgentRaw = session.headers["user-agent"]?.trim().orEmpty()
            val userAgent = userAgentRaw.take(MAX_USER_AGENT_LENGTH)
            val source = classifyExternalSource(userAgent)
            val signature = "$source|$method|$path"
            val nowTs = System.currentTimeMillis()

            synchronized(this) {
                if (
                    lastExternalRequestSignature == signature &&
                    nowTs - lastExternalRequestLogTimestamp < EXTERNAL_REQUEST_LOG_DEBOUNCE_MS
                ) {
                    return
                }
                lastExternalRequestSignature = signature
                lastExternalRequestLogTimestamp = nowTs
            }

            runBlocking {
                auditLogger.info(
                    "local_nightscout_external_request",
                    mapOf(
                        "method" to method,
                        "path" to path,
                        "source" to source,
                        "remote" to session.remoteIpAddress.orEmpty(),
                        "userAgent" to userAgent.ifBlank { "unknown" }
                    )
                )
            }
        }

        private fun classifyExternalSource(userAgent: String): String {
            return when {
                userAgent.contains("okhttp", ignoreCase = true) -> "okhttp"
                userAgent.contains("mozilla", ignoreCase = true) -> "browser"
                userAgent.contains("dalvik", ignoreCase = true) -> "dalvik"
                else -> "unknown"
            }
        }

        private fun handleSocketIo(session: IHTTPSession): Response {
            val transport = firstParam(session, "transport")?.lowercase()
            val protocolVersion = firstInt(session, "EIO", -1)
            if (transport != "polling" || protocolVersion != SOCKET_ENGINE_PROTOCOL_VERSION) {
                return textResponse(Response.Status.BAD_REQUEST, "invalid socket transport")
            }
            pruneStaleSocketSessions()
            return when (session.method) {
                Method.GET -> handleSocketIoGet(session)
                Method.POST -> handleSocketIoPost(session)
                else -> textResponse(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
            }
        }

        private fun handleSocketIoGet(session: IHTTPSession): Response {
            val sid = firstParam(session, "sid")
            if (sid.isNullOrBlank()) {
                val created = createSocketSession(session)
                val handshake = JsonObject().apply {
                    addProperty("sid", created.sid)
                    add("upgrades", JsonArray())
                    addProperty("pingInterval", SOCKET_PING_INTERVAL_MS)
                    addProperty("pingTimeout", SOCKET_PING_TIMEOUT_MS)
                    addProperty("maxPayload", SOCKET_MAX_PAYLOAD_BYTES)
                }
                return socketIoPayloadResponse(listOf("$ENGINE_PACKET_OPEN${gson.toJson(handshake)}"))
            }

            val active = socketSessions[sid]
                ?: return textResponse(Response.Status.BAD_REQUEST, "unknown sid")
            active.lastSeenAt = System.currentTimeMillis()
            val packets = synchronized(active) {
                if (active.outboundPackets.isEmpty()) {
                    listOf(ENGINE_PACKET_NOOP.toString())
                } else {
                    val next = active.outboundPackets.toList()
                    active.outboundPackets.clear()
                    next
                }
            }
            return socketIoPayloadResponse(packets)
        }

        private fun handleSocketIoPost(session: IHTTPSession): Response {
            val sid = firstParam(session, "sid")
                ?: return textResponse(Response.Status.BAD_REQUEST, "missing sid")
            val active = socketSessions[sid]
                ?: return textResponse(Response.Status.BAD_REQUEST, "unknown sid")
            active.lastSeenAt = System.currentTimeMillis()

            val packets = decodeEnginePayload(readBody(session))
            packets.forEach { packet ->
                runCatching { handleEnginePacket(active, packet) }
                    .onFailure { error ->
                        runBlocking {
                            auditLogger.warn(
                                "local_nightscout_socket_packet_parse_failed",
                                mapOf("sid" to sid, "error" to (error.message ?: "unknown"))
                            )
                        }
                    }
            }
            return textResponse(Response.Status.OK, "ok")
        }

        private fun createSocketSession(httpSession: IHTTPSession): SocketSession {
            val now = System.currentTimeMillis()
            val nextId = socketSessionCounter.getAndIncrement()
            val sid = "copilot-eio-$nextId-${UUID.randomUUID().toString().take(8)}"
            val socketSid = "copilot-sio-$nextId-${UUID.randomUUID().toString().take(8)}"
            val userAgent = httpSession.headers["user-agent"]?.trim()?.take(MAX_USER_AGENT_LENGTH).orEmpty()
            val source = classifyExternalSource(userAgent)
            return SocketSession(
                sid = sid,
                socketSid = socketSid,
                createdAt = now,
                lastSeenAt = now,
                source = source,
                userAgent = userAgent
            ).also { session ->
                socketSessions[sid] = session
                runBlocking {
                    auditLogger.info(
                        "local_nightscout_socket_session_created",
                        mapOf(
                            "sid" to sid,
                            "socketSid" to socketSid,
                            "source" to source,
                            "remote" to httpSession.remoteIpAddress.orEmpty(),
                            "userAgent" to userAgent.ifBlank { "unknown" }
                        )
                    )
                }
            }
        }

        private fun pruneStaleSocketSessions() {
            val now = System.currentTimeMillis()
            val iterator = socketSessions.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val session = entry.value
                if (now - session.lastSeenAt <= SOCKET_SESSION_TTL_MS) continue
                iterator.remove()
            }
        }

        private fun decodeEnginePayload(raw: String): List<String> {
            val text = raw.trim()
            if (text.isBlank()) return emptyList()
            return text.split(ENGINE_PACKET_SEPARATOR)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        private fun handleEnginePacket(session: SocketSession, rawPacket: String) {
            if (rawPacket.isEmpty()) return
            when (rawPacket.first()) {
                ENGINE_PACKET_PING -> enqueueEnginePacket(session, ENGINE_PACKET_PONG.toString())
                ENGINE_PACKET_MESSAGE -> handleSocketPacket(session, rawPacket.drop(1))
                ENGINE_PACKET_CLOSE -> socketSessions.remove(session.sid)
                else -> Unit
            }
        }

        private fun handleSocketPacket(session: SocketSession, raw: String) {
            val packet = parseSocketPacket(raw) ?: return
            if (packet.namespace != "/" && packet.namespace.isNotBlank()) return

            when (packet.type) {
                SOCKET_PACKET_CONNECT -> {
                    session.connected = true
                    val payload = JsonObject().apply { addProperty("sid", session.socketSid) }
                    enqueueSocketPacket(
                        session = session,
                        packetType = SOCKET_PACKET_CONNECT,
                        packetId = null,
                        payload = payload
                    )
                }

                SOCKET_PACKET_DISCONNECT -> {
                    socketSessions.remove(session.sid)
                }

                SOCKET_PACKET_EVENT -> handleSocketEvent(session, packet)
            }
        }

        private fun parseSocketPacket(raw: String): ParsedSocketPacket? {
            if (raw.isBlank()) return null
            var index = 0
            val type = raw[index].digitToIntOrNull() ?: return null
            index += 1

            if (type == SOCKET_PACKET_BINARY_EVENT || type == SOCKET_PACKET_BINARY_ACK) {
                val attachmentsSeparator = raw.indexOf('-', startIndex = index)
                if (attachmentsSeparator < 0) return null
                index = attachmentsSeparator + 1
            }

            var namespace = "/"
            if (index < raw.length && raw[index] == '/') {
                val namespaceEnd = raw.indexOf(',', startIndex = index)
                if (namespaceEnd >= 0) {
                    namespace = raw.substring(index, namespaceEnd)
                    index = namespaceEnd + 1
                } else {
                    namespace = raw.substring(index)
                    index = raw.length
                }
            }

            val idStart = index
            while (index < raw.length && raw[index].isDigit()) {
                index += 1
            }
            val packetId = if (index > idStart) {
                raw.substring(idStart, index).toIntOrNull()
            } else {
                null
            }

            val payload = if (index < raw.length) {
                runCatching { JsonParser.parseString(raw.substring(index)) }.getOrNull()
            } else {
                null
            }

            return ParsedSocketPacket(
                type = type,
                namespace = namespace,
                packetId = packetId,
                payload = payload
            )
        }

        private fun handleSocketEvent(session: SocketSession, packet: ParsedSocketPacket) {
            val payloadArray = packet.payload?.asJsonArrayOrNull() ?: return
            if (payloadArray.size() == 0) return
            val eventName = payloadArray[0].asStringOrNull()?.trim().orEmpty()
            if (eventName.isBlank()) return
            val args = payloadArray.getOrNull(1)

            when (eventName) {
                "authorize" -> handleSocketAuthorize(session, packet.packetId, args?.asJsonObjectOrNull())
                "dbAdd" -> handleSocketDbAdd(session, packet.packetId, args?.asJsonObjectOrNull())
                "dbUpdate" -> handleSocketDbUpdate(session, packet.packetId, args?.asJsonObjectOrNull())
            }
        }

        private fun handleSocketAuthorize(
            session: SocketSession,
            packetId: Int?,
            payload: JsonObject?
        ) {
            session.authorized = true
            val fromTs = payload?.get("from").asLongOrNull()?.coerceAtLeast(0L) ?: 0L
            session.fromTs = fromTs
            runBlocking {
                auditLogger.info(
                    "local_nightscout_socket_authorize",
                    mapOf(
                        "sid" to session.sid,
                        "socketSid" to session.socketSid,
                        "source" to session.source,
                        "fromTs" to fromTs
                    )
                )
            }

            packetId?.let { ackId ->
                val auth = JsonObject().apply {
                    addProperty("read", true)
                    addProperty("write", true)
                    addProperty("write_treatment", true)
                }
                enqueueSocketAck(session, ackId, listOf(auth))
            }

            enqueueSocketEvent(
                session = session,
                eventName = "dataUpdate",
                payload = buildDataUpdatePayload(fromTs = fromTs, delta = false)
            )
        }

        private fun handleSocketDbAdd(
            session: SocketSession,
            packetId: Int?,
            payload: JsonObject?
        ) {
            val collection = payload?.findText("collection")?.lowercase().orEmpty()
            val data = payload?.get("data")?.asJsonObjectOrNull()
            val generatedId = data?.findText("_id")
                ?: data?.findText("identifier")
                ?: "local-ns-${System.currentTimeMillis()}-${UUID.randomUUID()}"

            if (collection == "treatments" && data != null) {
                upsertTreatmentFromSocketPayload(data, preferredId = generatedId)
            }
            runBlocking {
                auditLogger.info(
                    "local_nightscout_socket_dbadd",
                    mapOf(
                        "sid" to session.sid,
                        "socketSid" to session.socketSid,
                        "source" to session.source,
                        "collection" to collection,
                        "id" to generatedId
                    )
                )
            }

            packetId?.let { ackId ->
                val response = JsonObject().apply { addProperty("_id", generatedId) }
                val nested = JsonArray().apply { add(response) }
                enqueueSocketAck(session, ackId, listOf(nested))
            }
        }

        private fun handleSocketDbUpdate(
            session: SocketSession,
            packetId: Int?,
            payload: JsonObject?
        ) {
            val collection = payload?.findText("collection")?.lowercase().orEmpty()
            val id = payload?.findText("_id")
            val data = payload?.get("data")?.asJsonObjectOrNull()

            if (collection == "treatments" && data != null) {
                upsertTreatmentFromSocketPayload(data, preferredId = id)
            }
            runBlocking {
                auditLogger.info(
                    "local_nightscout_socket_dbupdate",
                    mapOf(
                        "sid" to session.sid,
                        "socketSid" to session.socketSid,
                        "source" to session.source,
                        "collection" to collection,
                        "id" to id.orEmpty()
                    )
                )
            }

            packetId?.let { ackId ->
                val response = JsonObject().apply { addProperty("result", "success") }
                enqueueSocketAck(session, ackId, listOf(response))
            }
        }

        private fun upsertTreatmentFromSocketPayload(
            payload: JsonObject,
            preferredId: String?
        ) {
            val request = runCatching {
                gson.fromJson(payload, NightscoutTreatmentRequest::class.java)
            }.getOrNull() ?: return
            if (request.eventType.isBlank()) return

            val timestamp = parseFlexibleTimestamp(request.createdAt)
                ?: parseFlexibleTimestamp(payload.findText("timestamp"))
                ?: parseFlexibleTimestamp(payload.findText("date"))
                ?: parseFlexibleTimestamp(payload.findText("mills"))
                ?: System.currentTimeMillis()
            val treatmentId = preferredId
                ?: payload.findText("_id")
                ?: payload.findText("identifier")
                ?: "local-ns-${timestamp}-${UUID.randomUUID()}"
            val durationMinutes = request.duration
                ?: request.durationInMilliseconds?.let { (it / 60_000L).toInt() }
            val sourceEventType = request.eventType
            val normalizedType = normalizeEventType(sourceEventType)

            val payloadMap = linkedMapOf<String, String>().apply {
                durationMinutes?.let { put("duration", it.toString()) }
                request.durationInMilliseconds?.let { put("durationInMilliseconds", it.toString()) }
                request.targetTop?.let { put("targetTop", it.toString()) }
                request.targetBottom?.let { put("targetBottom", it.toString()) }
                request.carbs?.let { put("carbs", it.toString()) }
                request.insulin?.let { put("insulin", it.toString()) }
                request.units?.let { put("units", it) }
                request.isValid?.let { put("isValid", it.toString()) }
                request.reason?.let { put("reason", it) }
                request.notes?.let { put("notes", it) }
            }

            val therapyRow = TherapyEventEntity(
                id = treatmentId,
                timestamp = timestamp,
                type = normalizedType,
                payloadJson = gson.toJson(payloadMap)
            )
            val telemetryRows = TelemetryMetricMapper.fromNightscoutTreatment(
                timestamp = timestamp,
                source = SOURCE_LOCAL_NS_TREATMENT,
                eventType = sourceEventType,
                payload = payloadMap
            )

            runBlocking {
                db.therapyDao().upsertAll(listOf(therapyRow))
                if (telemetryRows.isNotEmpty()) {
                    db.telemetryDao().upsertAll(telemetryRows)
                }
            }

            val treatment = NightscoutTreatment(
                id = treatmentId,
                date = timestamp,
                createdAt = Instant.ofEpochMilli(timestamp).toString(),
                eventType = sourceEventType,
                carbs = request.carbs,
                insulin = request.insulin,
                duration = durationMinutes,
                durationInMilliseconds = request.durationInMilliseconds,
                targetTop = request.targetTop,
                targetBottom = request.targetBottom,
                units = request.units,
                isValid = request.isValid ?: true,
                reason = request.reason,
                notes = request.notes
            )
            emitTreatmentDeltaToSockets(listOf(treatment))
        }

        private fun emitTreatmentDeltaToSockets(treatments: List<NightscoutTreatment>) {
            if (treatments.isEmpty()) return
            val payload = JsonObject().apply {
                addProperty("delta", true)
                val treatmentsJson = JsonArray()
                treatments.forEach { treatment ->
                    treatmentsJson.add(treatment.toSocketTreatmentJson())
                }
                add("treatments", treatmentsJson)
            }
            broadcastSocketEvent("dataUpdate", payload)
        }

        private fun buildDataUpdatePayload(fromTs: Long, delta: Boolean): JsonObject {
            val since = fromTs.coerceAtLeast(0L)
            val glucoseRows = runBlocking { db.glucoseDao().since(since).takeLast(SOCKET_DATAUPDATE_MAX_ROWS) }
            val treatmentRows = runBlocking { db.therapyDao().since(since).takeLast(SOCKET_DATAUPDATE_MAX_ROWS) }

            return JsonObject().apply {
                if (delta) {
                    addProperty("delta", true)
                } else {
                    add("status", buildSocketStatusPayload())
                }
                if (treatmentRows.isNotEmpty()) {
                    val treatments = JsonArray()
                    treatmentRows.forEach { row ->
                        treatments.add(row.toNightscoutTreatment(gson).toSocketTreatmentJson())
                    }
                    add("treatments", treatments)
                }
                if (glucoseRows.isNotEmpty()) {
                    val sgvs = JsonArray()
                    glucoseRows.forEach { sample -> sgvs.add(sample.toSocketSgvJson()) }
                    add("sgvs", sgvs)
                }
            }
        }

        private fun buildSocketStatusPayload(): JsonObject {
            return JsonObject().apply {
                addProperty("status", "ok")
                addProperty("name", "AAPS Predictive Copilot Local NS")
                addProperty("version", "15.0.0-local")
                addProperty("versionNum", 150_000)
                addProperty("serverTime", Instant.now().toString())
                addProperty("apiEnabled", true)
                addProperty("careportalEnabled", true)
                addProperty("boluscalcEnabled", true)
                addProperty("head", "copilot-local")
                add("settings", JsonObject().apply {
                    addProperty("units", "mmol")
                    addProperty("timeFormat", 24)
                })
                add("extendedSettings", JsonObject())
            }
        }

        private fun NightscoutTreatment.toSocketTreatmentJson(): JsonObject {
            val objectJson = gson.toJsonTree(this).asJsonObject
            val ts = date ?: parseFlexibleTimestamp(createdAt) ?: System.currentTimeMillis()
            objectJson.addProperty("date", ts)
            objectJson.addProperty("mills", ts)
            return objectJson
        }

        private fun GlucoseSampleEntity.toSocketSgvJson(): JsonObject {
            return JsonObject().apply {
                addProperty("date", timestamp)
                addProperty("mills", timestamp)
                addProperty("sgv", UnitConverter.mmolToMgdl(mmol))
                addProperty("device", "copilot-local-ns")
                addProperty("type", "sgv")
            }
        }

        private fun broadcastSocketEvent(eventName: String, payload: JsonObject) {
            socketSessions.values
                .asSequence()
                .filter { it.connected && it.authorized }
                .forEach { session ->
                    enqueueSocketEvent(
                        session = session,
                        eventName = eventName,
                        payload = payload
                    )
                }
        }

        private fun enqueueSocketEvent(
            session: SocketSession,
            eventName: String,
            payload: JsonObject
        ) {
            val data = JsonArray().apply {
                add(eventName)
                add(payload)
            }
            enqueueSocketPacket(
                session = session,
                packetType = SOCKET_PACKET_EVENT,
                packetId = null,
                payload = data
            )
        }

        private fun enqueueSocketAck(
            session: SocketSession,
            ackId: Int,
            args: List<JsonElement>
        ) {
            val payload = JsonArray().apply { args.forEach { add(it) } }
            enqueueSocketPacket(
                session = session,
                packetType = SOCKET_PACKET_ACK,
                packetId = ackId,
                payload = payload
            )
        }

        private fun enqueueSocketPacket(
            session: SocketSession,
            packetType: Int,
            packetId: Int?,
            payload: JsonElement?
        ) {
            val builder = StringBuilder().append(packetType)
            packetId?.let { builder.append(it) }
            payload?.let { builder.append(gson.toJson(it)) }
            enqueueEnginePacket(session, "$ENGINE_PACKET_MESSAGE${builder}")
        }

        private fun enqueueEnginePacket(session: SocketSession, packet: String) {
            synchronized(session) {
                session.outboundPackets += packet
            }
        }

        private fun socketIoPayloadResponse(packets: List<String>): Response {
            val payload = if (packets.isEmpty()) {
                ENGINE_PACKET_NOOP.toString()
            } else {
                packets.joinToString(ENGINE_PACKET_SEPARATOR.toString())
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                CONTENT_TYPE_TEXT,
                payload
            ).apply {
                addHeader("Cache-Control", "no-store")
            }
        }

        private fun textResponse(status: Response.Status, text: String): Response {
            return newFixedLengthResponse(status, CONTENT_TYPE_TEXT, text)
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
            if (rows.isNotEmpty()) {
                triggerReactiveAutomation("entries", rows.size, 0)
                val deltaPayload = JsonObject().apply {
                    addProperty("delta", true)
                    val sgvs = JsonArray()
                    rows.forEach { row -> sgvs.add(row.toSocketSgvJson()) }
                    add("sgvs", sgvs)
                }
                broadcastSocketEvent("dataUpdate", deltaPayload)
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
            val fromCopilotClient = session.headers[HEADER_COPILOT_CLIENT]
                ?.trim()
                ?.isNotEmpty() == true

            val responses = mutableListOf<NightscoutTreatment>()
            val therapyRows = mutableListOf<TherapyEventEntity>()
            val telemetryRows = mutableListOf<io.aaps.copilot.data.local.entity.TelemetrySampleEntity>()

            parsed.objects.forEach { item ->
                val request = runCatching {
                    gson.fromJson(item, NightscoutTreatmentRequest::class.java)
                }.getOrNull() ?: return@forEach
                if (request.eventType.isBlank()) return@forEach

                val timestamp = parseFlexibleTimestamp(request.createdAt)
                    ?: normalizeEpochMillis(request.date)
                    ?: normalizeEpochMillis(request.mills)
                    ?: System.currentTimeMillis()
                val treatmentId = "local-ns-${timestamp}-${UUID.randomUUID()}"
                val durationMinutes = request.duration
                    ?: request.durationInMilliseconds?.let { (it / 60_000L).toInt() }

                val payload = linkedMapOf<String, String>().apply {
                    durationMinutes?.let { put("duration", it.toString()) }
                    request.durationInMilliseconds?.let { put("durationInMilliseconds", it.toString()) }
                    request.targetTop?.let { put("targetTop", it.toString()) }
                    request.targetBottom?.let { put("targetBottom", it.toString()) }
                    request.carbs?.let { put("carbs", it.toString()) }
                    request.insulin?.let { put("insulin", it.toString()) }
                    request.units?.let { put("units", it) }
                    request.isValid?.let { put("isValid", it.toString()) }
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
                    date = timestamp,
                    createdAt = Instant.ofEpochMilli(timestamp).toString(),
                    eventType = request.eventType,
                    carbs = request.carbs,
                    insulin = request.insulin,
                    duration = durationMinutes,
                    durationInMilliseconds = request.durationInMilliseconds,
                    targetTop = request.targetTop,
                    targetBottom = request.targetBottom,
                    units = request.units,
                    isValid = request.isValid ?: true,
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
            triggerReactiveAutomation("treatments", therapyRows.size, telemetryRows.size)
            emitTreatmentDeltaToSockets(responses)
            if (fromCopilotClient) {
                relayTreatmentsToAaps(responses)
            }

            return if (parsed.wasArray) jsonOk(responses) else jsonOk(responses.first())
        }

        private fun relayTreatmentsToAaps(treatments: List<NightscoutTreatment>) {
            if (treatments.isEmpty()) return
            val targetPackage = resolveInstalledAapsPackage() ?: run {
                runBlocking {
                    auditLogger.warn(
                        "local_nightscout_relay_skipped",
                        mapOf("reason" to "aaps_package_not_found", "count" to treatments.size)
                    )
                }
                return
            }

            var delivered = 0
            treatments.forEach { treatment ->
                val mills = treatment.date ?: parseFlexibleTimestamp(treatment.createdAt) ?: System.currentTimeMillis()
                val json = JsonObject().apply {
                    addProperty("eventType", treatment.eventType)
                    addProperty("mills", mills)
                    treatment.id?.let { addProperty("_id", it) }
                    treatment.carbs?.let { addProperty("carbs", it) }
                    treatment.insulin?.let { addProperty("insulin", it) }
                    treatment.duration?.let { addProperty("duration", it) }
                    treatment.durationInMilliseconds?.let { addProperty("durationInMilliseconds", it) }
                    treatment.targetTop?.let { addProperty("targetTop", it) }
                    treatment.targetBottom?.let { addProperty("targetBottom", it) }
                    treatment.units?.let { addProperty("units", normalizeUnitsForAaps(it)) }
                    treatment.reason?.let { addProperty("reason", it) }
                    treatment.notes?.let { addProperty("notes", it) }
                    treatment.isValid.let { addProperty("isValid", it) }
                }
                val payload = JsonArray().apply { add(json) }.toString()
                val intent = Intent(ACTION_NS_EMULATOR).apply {
                    setPackage(targetPackage)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra("collection", "treatments")
                    putExtra("data", payload)
                }

                if (resolveReceiverCount(intent) <= 0) return@forEach
                runCatching {
                    context.sendBroadcast(intent)
                    delivered++
                }
            }

            runBlocking {
                auditLogger.info(
                    "local_nightscout_relay_to_aaps",
                    mapOf(
                        "targetPackage" to targetPackage,
                        "received" to treatments.size,
                        "delivered" to delivered
                    )
                )
            }
        }

        private fun normalizeUnitsForAaps(units: String): String {
            return when (units.trim().lowercase()) {
                "mmol/l", "mmol\\l", "mmol l", "mmol" -> "mmol"
                "mgdl", "mg dl", "mg/dl" -> "mg/dl"
                else -> units
            }
        }

        private fun resolveInstalledAapsPackage(): String? {
            val candidates = listOf(AAPS_PACKAGE_LEGACY, AAPS_PACKAGE_MODERN)
            return candidates.firstOrNull { packageName ->
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.packageManager.getPackageInfo(
                            packageName,
                            PackageManager.PackageInfoFlags.of(0)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(packageName, 0)
                    }
                }.isSuccess
            }
        }

        private fun resolveReceiverCount(intent: Intent): Int {
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.queryBroadcastReceivers(
                        intent,
                        PackageManager.ResolveInfoFlags.of(0)
                    ).size
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.queryBroadcastReceivers(intent, 0).size
                }
            }.getOrDefault(0)
        }

        private fun handlePostDeviceStatus(session: IHTTPSession): Response {
            val parsed = parseJsonObjects(readBody(session))
                ?: return jsonBadRequest("invalid_json")
            if (parsed.objects.isEmpty()) {
                return jsonBadRequest("empty_payload")
            }

            val telemetryRows = mutableListOf<io.aaps.copilot.data.local.entity.TelemetrySampleEntity>()
            val glucoseRows = mutableListOf<GlucoseSampleEntity>()
            parsed.objects.forEach { payload ->
                val payloadMap = payload.toMap(gson)
                val ts = parseFlexibleTimestamp(payload.findText("created_at"))
                    ?: normalizeEpochMillis(payload.findNumeric("date")?.toLong())
                    ?: normalizeEpochMillis(payload.findNumeric("mills")?.toLong())
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
                val glucose = GlucoseValueResolver.resolve(flattened)?.let { candidate ->
                    val units = if (candidate.key.lowercase().contains("mgdl")) "mgdl" else null
                    val mmol = GlucoseUnitNormalizer.normalizeToMmol(
                        valueRaw = candidate.valueRaw,
                        valueKey = candidate.key,
                        units = units
                    )
                    if (mmol in 1.0..33.0) {
                        GlucoseSampleEntity(
                            timestamp = ts,
                            mmol = mmol,
                            source = SOURCE_LOCAL_NS_DEVICESTATUS,
                            quality = "OK"
                        )
                    } else {
                        null
                    }
                }
                if (glucose != null) {
                    glucoseRows += glucose
                }
            }
            if (telemetryRows.isNotEmpty() || glucoseRows.isNotEmpty()) {
                runBlocking {
                    if (telemetryRows.isNotEmpty()) {
                        db.telemetryDao().upsertAll(telemetryRows.distinctBy { it.id })
                    }
                    if (glucoseRows.isNotEmpty()) {
                        glucoseRows.forEach { row ->
                            val existing = db.glucoseDao().bySourceAndTimestamp(row.source, row.timestamp)
                            if (existing != null) {
                                val differs = kotlin.math.abs(existing.mmol - row.mmol) > 0.01
                                if (differs || existing.quality != row.quality) {
                                    db.glucoseDao().deleteBySourceAndTimestamp(row.source, row.timestamp)
                                    db.glucoseDao().upsertAll(listOf(row))
                                }
                            } else {
                                db.glucoseDao().upsertAll(listOf(row))
                            }
                        }
                    }
                    auditLogger.info(
                        "local_nightscout_devicestatus_post",
                        mapOf(
                            "received" to parsed.objects.size,
                            "telemetry" to telemetryRows.size,
                            "glucose" to glucoseRows.size
                        )
                    )
                }
            } else {
                runBlocking {
                    auditLogger.warn(
                        "local_nightscout_devicestatus_post",
                        mapOf("received" to parsed.objects.size, "telemetry" to 0, "glucose" to 0)
                    )
                }
            }
            if (telemetryRows.isNotEmpty() || glucoseRows.isNotEmpty()) {
                triggerReactiveAutomation(
                    "devicestatus",
                    glucoseRows.size.coerceAtLeast(parsed.objects.size),
                    telemetryRows.size
                )
            }
            return jsonOk(
                mapOf(
                    "status" to "ok",
                    "received" to parsed.objects.size,
                    "telemetry" to telemetryRows.size,
                    "glucose" to glucoseRows.size
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
                date = timestamp,
                createdAt = Instant.ofEpochMilli(timestamp).toString(),
                eventType = toNightscoutEventType(type),
                carbs = payload["carbs"]?.toDoubleOrNull(),
                insulin = payload["insulin"]?.toDoubleOrNull(),
                enteredBy = payload["enteredBy"],
                absolute = payload["absolute"]?.toDoubleOrNull(),
                rate = payload["rate"]?.toDoubleOrNull(),
                percentage = payload["percentage"]?.toIntOrNull(),
                duration = payload["duration"]?.toIntOrNull() ?: payload["durationMinutes"]?.toIntOrNull(),
                durationInMilliseconds = payload["durationInMilliseconds"]?.toLongOrNull(),
                targetTop = payload["targetTop"]?.toDoubleOrNull(),
                targetBottom = payload["targetBottom"]?.toDoubleOrNull(),
                units = payload["units"],
                isValid = payload["isValid"]?.toBooleanStrictOrNull() ?: true,
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

        private fun normalizeEpochMillis(raw: Long?): Long? {
            val value = raw ?: return null
            return if (value < 10_000_000_000L) value * 1000L else value
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

        private fun htmlOk(body: String): Response {
            return newFixedLengthResponse(
                Response.Status.OK,
                CONTENT_TYPE_HTML,
                body
            )
        }

        private fun JsonElement.asJsonObjectOrNull(): JsonObject? {
            return if (isJsonObject) asJsonObject else null
        }

        private fun JsonElement.asJsonArrayOrNull(): JsonArray? {
            return if (isJsonArray) asJsonArray else null
        }

        private fun JsonElement.asStringOrNull(): String? {
            return runCatching { asString }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }

        private fun JsonElement?.asLongOrNull(): Long? {
            val element = this ?: return null
            return runCatching { element.asLong }.getOrNull()
                ?: runCatching { element.asString }.getOrNull()?.toLongOrNull()
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

        private fun JsonArray.getOrNull(index: Int): JsonElement? {
            if (index < 0 || index >= size()) return null
            return get(index)
        }

        private data class ParsedJsonPayload(
            val objects: List<JsonObject>,
            val wasArray: Boolean
        )

        private data class ParsedSocketPacket(
            val type: Int,
            val namespace: String,
            val packetId: Int?,
            val payload: JsonElement?
        )

        private data class SocketSession(
            val sid: String,
            val socketSid: String,
            val createdAt: Long,
            @Volatile var lastSeenAt: Long,
            val source: String,
            val userAgent: String,
            @Volatile var connected: Boolean = false,
            @Volatile var authorized: Boolean = false,
            @Volatile var fromTs: Long = 0L,
            val outboundPackets: MutableList<String> = mutableListOf()
        )

        private fun triggerReactiveAutomation(
            channel: String,
            inserted: Int,
            telemetry: Int
        ) {
            val now = System.currentTimeMillis()
            val last = lastReactiveAutomationEnqueueTs.get()
            val scheduled = if (now - last >= REACTIVE_AUTOMATION_DEBOUNCE_MS) {
                lastReactiveAutomationEnqueueTs.compareAndSet(last, now)
            } else {
                false
            }
            if (scheduled) {
                onReactiveDataIngested?.invoke()
            }
            runBlocking {
                auditLogger.info(
                    if (scheduled) "local_nightscout_reactive_automation_enqueued" else "local_nightscout_reactive_automation_skipped",
                    mapOf(
                        "reason" to if (scheduled) "scheduled" else "debounced",
                        "channel" to channel,
                        "inserted" to inserted,
                        "telemetry" to telemetry
                    )
                )
            }
        }
    }

    private companion object {
        private const val HOST = "127.0.0.1"
        private const val CONTENT_TYPE_JSON = "application/json; charset=utf-8"
        private const val CONTENT_TYPE_HTML = "text/html; charset=utf-8"
        private const val CONTENT_TYPE_TEXT = "text/plain; charset=utf-8"
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val HEADER_COPILOT_CLIENT = "x-aaps-copilot-client"
        private const val EXTERNAL_REQUEST_LOG_DEBOUNCE_MS = 2_000L
        private const val MAX_USER_AGENT_LENGTH = 160
        private const val SOURCE_LOCAL_NS_ENTRY = "local_nightscout_entry"
        private const val SOURCE_LOCAL_NS_TREATMENT = "local_nightscout_treatment"
        private const val SOURCE_LOCAL_NS_DEVICESTATUS = "local_nightscout_devicestatus"
        private const val ACTION_NS_EMULATOR = "com.eveningoutpost.dexdrip.NS_EMULATOR"
        private const val AAPS_PACKAGE_LEGACY = "info.nightscout.androidaps"
        private const val AAPS_PACKAGE_MODERN = "app.aaps"
        private const val PORT_SCAN_WINDOW = 30
        private const val SOCKET_ENGINE_PROTOCOL_VERSION = 4
        private const val SOCKET_PING_INTERVAL_MS = 25_000
        private const val SOCKET_PING_TIMEOUT_MS = 20_000
        private const val SOCKET_MAX_PAYLOAD_BYTES = 1_000_000
        private const val SOCKET_SESSION_TTL_MS = 3 * 60_000L
        private const val SOCKET_DATAUPDATE_MAX_ROWS = 1_200
        private const val REACTIVE_AUTOMATION_DEBOUNCE_MS = 45_000L
        private const val SOCKET_PACKET_CONNECT = 0
        private const val SOCKET_PACKET_DISCONNECT = 1
        private const val SOCKET_PACKET_EVENT = 2
        private const val SOCKET_PACKET_ACK = 3
        private const val SOCKET_PACKET_BINARY_EVENT = 5
        private const val SOCKET_PACKET_BINARY_ACK = 6
        private const val ENGINE_PACKET_OPEN = '0'
        private const val ENGINE_PACKET_CLOSE = '1'
        private const val ENGINE_PACKET_PING = '2'
        private const val ENGINE_PACKET_PONG = '3'
        private const val ENGINE_PACKET_MESSAGE = '4'
        private const val ENGINE_PACKET_NOOP = '6'
        private const val ENGINE_PACKET_SEPARATOR = '\u001e'
    }
}
