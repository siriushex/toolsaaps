package io.aaps.copilot.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.room.withTransaction
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.TelemetrySampleSelector
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.remote.nightscout.NightscoutSgvEntry
import io.aaps.copilot.data.remote.nightscout.NightscoutTreatment
import io.aaps.copilot.data.remote.nightscout.NightscoutTreatmentRequest
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.repository.AuditLogger
import io.aaps.copilot.data.repository.GlucoseSanitizer
import io.aaps.copilot.data.repository.GlucoseValueResolver
import io.aaps.copilot.data.repository.SyncRepository
import io.aaps.copilot.data.repository.TelemetryMetricMapper
import io.aaps.copilot.data.repository.decodeTherapyEventPayload
import io.aaps.copilot.data.repository.mergeTherapyEventWithCanonicalAapsCarbMetadata
import io.aaps.copilot.data.repository.parseTherapyPayloadJsonObject
import io.aaps.copilot.security.TherapyActionTransportGate
import io.aaps.copilot.security.TherapyActionsNotArmedException
import io.aaps.copilot.util.GlucoseUnitNormalizer
import io.aaps.copilot.util.UnitConverter
import java.time.Instant
import java.security.MessageDigest
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Filter
import java.util.logging.Handler
import java.util.logging.Logger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

internal suspend fun upsertLocalNightscoutSocketTherapyReplacement(
    incomingRow: TherapyEventEntity,
    loadLatest: suspend (String) -> TherapyEventEntity?,
    upsertAll: suspend (List<TherapyEventEntity>) -> Unit,
    gson: Gson
): TherapyEventEntity {
    val finalRow = mergeTherapyEventWithCanonicalAapsCarbMetadata(
        incoming = incomingRow,
        latestExisting = loadLatest(incomingRow.id),
        decodePayload = ::parseTherapyPayloadJsonObject,
        encodePayload = gson::toJson
    )
    upsertAll(listOf(finalRow))
    return finalRow
}

internal suspend fun <T> persistLocalNightscoutClinicalInput(
    persistence: suspend () -> T,
    shouldInvalidate: (T) -> Boolean,
    onClinicalInputPersisted: suspend () -> Unit
): T {
    val result = persistence()
    if (shouldInvalidate(result)) onClinicalInputPersisted()
    return result
}

internal data class LocalNightscoutIobRelevanceSignature(
    val fields: Map<String, String>
)

internal data class PreparedLocalNightscoutDeviceStatus(
    val flattened: Map<String, String>,
    val telemetryRows: List<TelemetrySampleEntity>,
    val relevanceSignature: LocalNightscoutIobRelevanceSignature
)

internal fun prepareLocalNightscoutDeviceStatus(
    timestamp: Long,
    source: String,
    payload: Map<String, Any?>
): PreparedLocalNightscoutDeviceStatus {
    val flattened = linkedMapOf<String, String>()
    TelemetryMetricMapper.flattenAny("openaps", payload["openaps"], flattened)
    TelemetryMetricMapper.flattenAny("pump", payload["pump"], flattened)
    TelemetryMetricMapper.flattenAny("uploader", payload["uploader"], flattened)
    return PreparedLocalNightscoutDeviceStatus(
        flattened = flattened,
        telemetryRows = TelemetryMetricMapper.fromFlattenedNightscoutDeviceStatus(
            timestamp = timestamp,
            source = source,
            flattened = flattened
        ),
        relevanceSignature = flattened.toLocalNightscoutIobRelevanceSignature()
    )
}

internal enum class LocalNightscoutRequestBodyDecision {
    ALLOWED,
    MISSING_LENGTH,
    INVALID_LENGTH,
    TOO_LARGE
}

internal object LocalNightscoutRequestBodyPolicy {
    const val MAX_BODY_BYTES = 1_000_000

    fun evaluate(headers: Map<String, String>): LocalNightscoutRequestBodyDecision {
        val raw = headers.entries.firstOrNull { it.key.equals("content-length", ignoreCase = true) }
            ?.value
            ?.trim()
            ?: return LocalNightscoutRequestBodyDecision.MISSING_LENGTH
        val length = raw.toLongOrNull()
            ?: return LocalNightscoutRequestBodyDecision.INVALID_LENGTH
        return when {
            length < 0L -> LocalNightscoutRequestBodyDecision.INVALID_LENGTH
            length > MAX_BODY_BYTES -> LocalNightscoutRequestBodyDecision.TOO_LARGE
            else -> LocalNightscoutRequestBodyDecision.ALLOWED
        }
    }
}

internal data class LocalNightscoutSocketLimits(
    val maxInboundPacketsPerPost: Int = 64,
    val maxOutboundPackets: Int = 64,
    val maxOutboundBytes: Int = 256 * 1024,
    val sessionTtlMs: Long = 3 * 60_000L,
    val pollWaitMs: Long = 5_000L
) {
    init {
        require(maxInboundPacketsPerPost > 0)
        require(maxOutboundPackets > 0)
        require(maxOutboundBytes > 0)
        require(sessionTtlMs > 0L)
        require(pollWaitMs in 1L..5_000L)
    }
}

internal enum class LocalNightscoutSnapshotPhase { BEFORE_CAPTURE, AFTER_CAPTURE, BEFORE_PUBLISH }

private class LocalNightscoutRequestBodyException(
    val status: NanoHTTPD.Response.Status,
    val safeMessage: String
) : IllegalArgumentException(safeMessage)

private fun Map<String, String>.toLocalNightscoutIobRelevanceSignature():
    LocalNightscoutIobRelevanceSignature {
    val normalized = entries.associate { it.key.lowercase(Locale.US) to it.value }
    val fields = linkedMapOf<String, String>()
    LOCAL_NIGHTSCOUT_IOB_RELEVANCE_FIELDS.forEach { field ->
        val entry = normalized.entries.firstOrNull { candidate ->
            field.suffixes.any { suffix -> candidate.key.endsWith(suffix) }
        } ?: return@forEach
        val value = if (field.numeric) {
            entry.value.replace(',', '.').toDoubleOrNull()
                ?.takeIf(Double::isFinite)
                ?.toString()
        } else {
            entry.value.trim().lowercase(Locale.US).take(64).takeIf(String::isNotEmpty)
        }
        if (value != null) fields[field.name] = value
    }
    return LocalNightscoutIobRelevanceSignature(fields)
}

private data class LocalNightscoutIobRelevanceField(
    val name: String,
    val numeric: Boolean,
    val suffixes: Set<String>
)

private val LOCAL_NIGHTSCOUT_IOB_RELEVANCE_FIELDS = listOf(
    LocalNightscoutIobRelevanceField("total", true, setOf(".iob.iob", ".iob.totaliob")),
    LocalNightscoutIobRelevanceField("net", true, setOf(".iob.netiob", ".iob.net_iob")),
    LocalNightscoutIobRelevanceField("bolus", true, setOf(".iob.bolusiob", ".iob.bolus_iob")),
    LocalNightscoutIobRelevanceField("basal", true, setOf(".iob.basaliob", ".iob.basal_iob")),
    LocalNightscoutIobRelevanceField(
        "activity",
        true,
        setOf(".iob.activity", ".iob.insulinactivity", ".iob.insulin_activity")
    ),
    LocalNightscoutIobRelevanceField(
        "effective_positive",
        true,
        setOf(".iob.effectivepositiveiob", ".iob.effective_positive_iob")
    ),
    LocalNightscoutIobRelevanceField("confidence", true, setOf(".iob.confidence", ".iob.runtimeconfidence")),
    LocalNightscoutIobRelevanceField("source", false, setOf(".iob.source", ".iob.runtimesource")),
    LocalNightscoutIobRelevanceField(
        "fallback",
        false,
        setOf(".iob.fallbackreason", ".iob.runtimefallbackreason")
    )
)

internal class LocalNightscoutInvalidationPolicy {
    private var lastDeviceStatusSignature: String? = null

    @Synchronized
    fun shouldInvalidateDeviceStatus(
        glucoseAppliedCount: Int,
        telemetryRows: List<TelemetrySampleEntity>,
        iobRelevanceSignatures: List<LocalNightscoutIobRelevanceSignature> = emptyList()
    ): Boolean {
        val telemetrySignature = telemetryRows
            .asSequence()
            .filter { it.key in REACTIVE_DEVICESTATUS_KEYS }
            .sortedBy { it.key }
            .joinToString(separator = "|") { row ->
                "${row.key}=${row.valueDouble ?: row.valueText.orEmpty()}"
            }
            .takeIf { it.isNotEmpty() }
        val flattenedIobSignature = iobRelevanceSignatures
            .flatMapIndexed { index, signature ->
                signature.fields.entries.map { (key, value) -> "$index.$key=$value" }
            }
            .joinToString(separator = "|")
            .takeIf(String::isNotEmpty)
        val signature = listOfNotNull(telemetrySignature, flattenedIobSignature)
            .joinToString(separator = "|")
            .takeIf(String::isNotEmpty)
        val telemetryChanged = signature != null && signature != lastDeviceStatusSignature
        if (signature != null) lastDeviceStatusSignature = signature
        return glucoseAppliedCount > 0 || telemetryChanged
    }

    fun shouldInvalidateDeviceStatus(
        glucoseAppliedCount: Int,
        telemetryRows: List<TelemetrySampleEntity>,
        iobRelevanceSignature: LocalNightscoutIobRelevanceSignature
    ): Boolean = shouldInvalidateDeviceStatus(
        glucoseAppliedCount,
        telemetryRows,
        listOf(iobRelevanceSignature)
    )

    private companion object {
        val REACTIVE_DEVICESTATUS_KEYS = setOf(
            "iob_units",
            "iob_net_units",
            "iob_bolus_units",
            "iob_basal_units",
            "insulin_activity",
            "iob_effective_positive_units",
            "iob_runtime_confidence",
            "iob_runtime_source",
            "iob_runtime_source_code",
            "iob_runtime_fallback_reason",
            "cob_grams",
            "activity_ratio",
            "distance_km",
            "active_minutes",
            "calories_active_kcal",
            "heart_rate_bpm",
            "dia_hours",
            "steps_count",
            "insulin_units",
            "carbs_grams",
            "uam_value",
            "isf_value",
            "cr_value",
            "basal_rate_u_h",
            "insulin_req_units",
            "temp_target_low_mmol",
            "temp_target_high_mmol",
            "temp_target_duration_min",
            "profile_percent"
        )
    }
}

class LocalNightscoutServer internal constructor(
    private val context: Context,
    private val db: CopilotDatabase,
    private val settingsStore: AppSettingsStore,
    private val gson: Gson,
    private val auditLogger: AuditLogger,
    private val onClinicalInputPersisted: suspend () -> Unit = {},
    private val tlsMaterialProvider: () -> LocalNightscoutTlsServerMaterial = {
        LocalNightscoutTls.createServerMaterial(context)
    },
    private val selfHandshake: (Int, LocalNightscoutTlsServerMaterial) -> Unit =
        LocalNightscoutTls::pinnedSelfHandshake,
    private val runtimeState: MutableLocalNightscoutRuntimeState = LocalNightscoutRuntimeState,
    private val socketNowMs: () -> Long = System::currentTimeMillis,
    private val socketLimits: LocalNightscoutSocketLimits = LocalNightscoutSocketLimits(),
    private val socketPostBeforePacketProcessing: () -> Unit = {},
    private val socketSnapshotCheckpoint: (LocalNightscoutSnapshotPhase) -> Unit = {}
) {

    init {
        suppressKnownNanoHttpdNoise()
    }

    @Volatile
    private var server: EmbeddedServer? = null

    @Volatile
    private var currentPort: Int? = null

    @Volatile
    private var currentFingerprint: String? = null

    @Volatile
    private var authenticatedSocketObserved = false

    @Synchronized
    fun update(enabled: Boolean, port: Int): Int? {
        if (!enabled) {
            stopLocked()
            runtimeState.disabled(port)
            return null
        }
        if (port !in MIN_PORT..MAX_PORT) {
            stopLocked()
            runtimeState.failed(port, LocalNightscoutRuntimeReason.PORT_INVALID)
            return null
        }
        val safePort = port
        if (server != null && currentPort == safePort) return safePort
        val alreadyRunningPort = currentPort
        stopLocked()
        runtimeState.starting(safePort)
        authenticatedSocketObserved = false
        val tlsMaterial = runCatching {
            tlsMaterialProvider()
        }.getOrElse { error ->
            runtimeState.failed(
                safePort,
                LocalNightscoutRuntimeReason.TLS_IDENTITY_UNAVAILABLE
            )
            runBlocking {
                auditLogger.error(
                    "local_nightscout_tls_init_failed",
                    mapOf("errorType" to error::class.java.simpleName.take(80))
                )
            }
            return null
        }
        val fingerprint = LocalNightscoutTls.fingerprintSha256(tlsMaterial.caCertificate)
        val next = EmbeddedServer(
            port = safePort,
            context = context,
            db = db,
            settingsStore = settingsStore,
            gson = gson,
            auditLogger = auditLogger,
            onClinicalInputPersisted = onClinicalInputPersisted,
            authenticator = tlsMaterial.authenticator,
            runtimeReady = { runtimeState.value.status == LocalNightscoutRuntimeStatus.READY },
            onAuthenticatedSocket = ::onAuthenticatedSocket,
            socketNowMs = socketNowMs,
            socketLimits = socketLimits,
            socketPostBeforePacketProcessing = socketPostBeforePacketProcessing,
            socketSnapshotCheckpoint = socketSnapshotCheckpoint
        )
        val startFailure = runCatching {
            next.makeSecure(tlsMaterial.socketFactory, null)
            next.start(SOCKET_TIMEOUT_MS, false)
        }.exceptionOrNull()
        if (startFailure != null) {
            runCatching { next.stopAndClear() }
            runtimeState.failed(safePort, LocalNightscoutRuntimeReason.PORT_UNAVAILABLE)
            runBlocking {
                auditLogger.error(
                    "local_nightscout_start_failed",
                    mapOf("port" to safePort, "reason" to LocalNightscoutRuntimeReason.PORT_UNAVAILABLE.name)
                )
            }
            return null
        }

        val handshakeFailure = runCatching { selfHandshake(safePort, tlsMaterial) }.exceptionOrNull()
        if (handshakeFailure != null) {
            runCatching { next.stopAndClear() }
            runtimeState.failed(safePort, LocalNightscoutRuntimeReason.PINNED_HANDSHAKE_FAILED)
            runBlocking {
                auditLogger.error(
                    "local_nightscout_start_failed",
                    mapOf(
                        "port" to safePort,
                        "reason" to LocalNightscoutRuntimeReason.PINNED_HANDSHAKE_FAILED.name
                    )
                )
            }
            return null
        }

        server = next
        currentPort = safePort
        currentFingerprint = fingerprint
        runtimeState.setup(
            safePort,
            fingerprint,
            LocalNightscoutRuntimeReason.AWAITING_AAPS_AUTH
        )
        if (authenticatedSocketObserved) {
            runtimeState.authenticatedSocketObserved(safePort, fingerprint)
        }
        runBlocking {
            auditLogger.info(
                "local_nightscout_started",
                mapOf(
                    "url" to "https://127.0.0.1:$safePort",
                    "requestedPort" to safePort,
                    "actualPort" to safePort,
                    "reusedPortAfterRestart" to (alreadyRunningPort == safePort),
                    "state" to runtimeState.value.status.name
                )
            )
        }
        return safePort
    }

    @Synchronized
    fun stop(
        reason: LocalNightscoutRuntimeReason = LocalNightscoutRuntimeReason.SERVICE_STOPPED
    ) {
        val port = currentPort ?: runtimeState.value.port
        val fingerprint = currentFingerprint ?: runtimeState.value.caFingerprint
        stopLocked()
        runtimeState.setup(port, fingerprint, reason)
    }

    @Synchronized
    private fun stopLocked() {
        val active = server ?: return
        runCatching { active.stopAndClear() }
        runBlocking {
            auditLogger.info(
                "local_nightscout_stopped",
                mapOf("url" to currentPort?.let { "https://127.0.0.1:$it" }.orEmpty())
            )
        }
        server = null
        currentPort = null
        currentFingerprint = null
        authenticatedSocketObserved = false
    }

    @Synchronized
    private fun onAuthenticatedSocket(origin: EmbeddedServer, publish: () -> Boolean): Boolean {
        if (server !== origin) return false
        val port = currentPort ?: return false
        val fingerprint = currentFingerprint ?: return false
        if (!publish()) return false
        authenticatedSocketObserved = true
        runtimeState.authenticatedSocketObserved(port, fingerprint)
        return true
    }

    private class EmbeddedServer(
        port: Int,
        private val context: Context,
        private val db: CopilotDatabase,
        private val settingsStore: AppSettingsStore,
        private val gson: Gson,
        private val auditLogger: AuditLogger,
        private val onClinicalInputPersisted: suspend () -> Unit,
        private val authenticator: LocalNightscoutApiAuthenticator,
        private val runtimeReady: () -> Boolean,
        private val onAuthenticatedSocket: (EmbeddedServer, () -> Boolean) -> Boolean,
        private val socketNowMs: () -> Long,
        private val socketLimits: LocalNightscoutSocketLimits,
        private val socketPostBeforePacketProcessing: () -> Unit,
        private val socketSnapshotCheckpoint: (LocalNightscoutSnapshotPhase) -> Unit
    ) : NanoHTTPD(HOST, port) {

        @Volatile
        private var lastExternalRequestLogTimestamp = 0L

        @Volatile
        private var lastExternalRequestSignature = ""

        private val socketSessions = ConcurrentHashMap<String, SocketSession>()
        private val responseCache = ConcurrentHashMap<String, CachedJsonResponse>()
        private val socketSessionCounter = AtomicLong(1L)
        private val dataRevision = AtomicLong(0L)
        private val lastHotPathStatsLogTimestamp = AtomicLong(0L)
        private val hotPathHttpCacheHits = AtomicLong(0L)
        private val hotPathHttpCacheMisses = AtomicLong(0L)
        private val hotPathExternalRequests = AtomicLong(0L)
        private val hotPathSocketAuthorizations = AtomicLong(0L)
        private val invalidationPolicy = LocalNightscoutInvalidationPolicy()

        fun stopAndClear() {
            try {
                socketSessions.values.forEach(::closeSocketSession)
                stop()
            } finally {
                authenticator.clear()
            }
        }

        override fun serve(session: IHTTPSession): Response {
            val path = session.uri.trim().substringBefore('?')
            maybeAuditExternalRequest(session, path)
            return runCatching {
                when (
                    LocalNightscoutRequestAuthorization.evaluate(
                        path = path,
                        headers = session.headers,
                        runtimeReady = runtimeReady(),
                        authenticator = authenticator
                    )
                ) {
                    LocalNightscoutApiAccess.NOT_READY ->
                        return@runCatching jsonServiceUnavailable("local_nightscout_not_ready")
                    LocalNightscoutApiAccess.UNAUTHORIZED ->
                        return@runCatching jsonUnauthorized("invalid_api_secret")
                    else -> Unit
                }
                when {
                    path.isBlank() || path == "/" -> htmlOk(
                        """
                        <html lang="en">
                        <head><meta charset="utf-8"><title>AAPS Predictive Copilot Local Nightscout</title></head>
                        <body>
                        <h2>AAPS Predictive Copilot Local Nightscout</h2>
                        <p>Loopback TLS setup endpoint is running.</p>
                        </body>
                        </html>
                        """.trimIndent()
                    )
                    path.equals("/api/v1/status.json", ignoreCase = true) -> cachedJsonOk(
                        cacheKey = "status",
                        ttlMs = STATUS_RESPONSE_CACHE_TTL_MS,
                        varyOnData = false
                    ) {
                        mapOf(
                            "status" to "ok",
                            "name" to "AAPS Predictive Copilot Local NS",
                            "version" to "local-1",
                            "serverTime" to Instant.now().toString()
                        )
                    }

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
                if (error is LocalNightscoutRequestBodyException) {
                    textResponse(error.status, error.safeMessage)
                } else {
                    newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        CONTENT_TYPE_JSON,
                        gson.toJson(mapOf("status" to "error", "message" to "internal_error"))
                    )
                }
            }
        }

        private fun maybeAuditExternalRequest(session: IHTTPSession, path: String) {
            val isApiPath = path.startsWith("/api/v1/", ignoreCase = true)
            val isSocketPath = path.startsWith("/socket.io", ignoreCase = true)
            if (!isApiPath && !isSocketPath) return
            val copilotHeader = session.headers[HEADER_COPILOT_CLIENT]?.trim().orEmpty()
            if (copilotHeader.isNotBlank()) return
            hotPathExternalRequests.incrementAndGet()
            maybeLogHotPathStats()

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
                        "remote" to session.remoteIpAddress.orEmpty()
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
                    ?: return textResponse(
                        Response.Status.TOO_MANY_REQUESTS,
                        "socket session capacity reached"
                    )
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
            val packets = synchronized(active.monitor) {
                if (!socketUsableLocked(active)) {
                    return textResponse(Response.Status.BAD_REQUEST, "unknown sid")
                }
                if (active.pollInProgress) {
                    closeSocketSession(active)
                    return textResponse(Response.Status.BAD_REQUEST, "concurrent polling")
                }
                active.pollInProgress = true
                try {
                    val startedNs = System.nanoTime()
                    while (true) {
                        if (!socketUsableLocked(active)) {
                            return textResponse(Response.Status.BAD_REQUEST, "unknown sid")
                        }
                        val now = socketNowMs()
                        val pendingPong = active.awaitingPongSince
                        if (active.authorized) active.lastSeenAt = now
                        if (pendingPong == null && now - active.lastPingAt >= SOCKET_PING_INTERVAL_MS) {
                            active.lastPingAt = now
                            active.awaitingPongSince = now
                            return@synchronized drainSocketPackets(active, heartbeat = true)
                        }
                        if (active.outboundPackets.any { !it.startsWith("42") } ||
                            (active.authorized && (active.outboundPackets.isNotEmpty() || active.initialPackets.isNotEmpty()))) {
                            return@synchronized drainSocketPackets(active)
                        }
                        val remainingMs = socketLimits.pollWaitMs - (System.nanoTime() - startedNs) / 1_000_000L
                        if (remainingMs <= 0L) return@synchronized listOf(ENGINE_PACKET_NOOP.toString())
                        val heartbeatMs = if (pendingPong == null) {
                            SOCKET_PING_INTERVAL_MS - (now - active.lastPingAt)
                        } else {
                            SOCKET_PING_TIMEOUT_MS - (now - pendingPong)
                        }
                        active.monitor.wait(minOf(remainingMs, heartbeatMs.coerceAtLeast(1L)))
                    }
                    @Suppress("UNREACHABLE_CODE")
                    emptyList<String>()
                } finally {
                    active.pollInProgress = false
                }
            }
            return socketIoPayloadResponse(packets)
        }

        private fun drainSocketPackets(session: SocketSession, heartbeat: Boolean = false): List<String> {
            val packets = ArrayList<String>()
            if (heartbeat) packets += ENGINE_PACKET_PING.toString()
            var bytes = if (heartbeat) 1 else 0
            fun drain(queue: MutableList<String>, controlOnly: Boolean = false) {
                while (queue.isNotEmpty() && packets.size < socketLimits.maxOutboundPackets) {
                    val index = if (controlOnly) queue.indexOfFirst { !it.startsWith("42") } else 0
                    if (index < 0) break
                    val next = queue[index]
                    val addedBytes = next.toByteArray(Charsets.UTF_8).size + if (packets.isEmpty()) 0 else 1
                    if (addedBytes > socketLimits.maxOutboundBytes - bytes) break
                    packets += queue.removeAt(index)
                    bytes += addedBytes
                }
            }
            drain(session.outboundPackets, controlOnly = true)
            if (session.authorized) {
                drain(session.initialPackets)
                // Live deltas must not advance the client's resume cursor ahead of initial history.
                if (session.initialPackets.isEmpty()) drain(session.outboundPackets)
            }
            session.outboundBytes = session.outboundPackets.sumOf { it.toByteArray(Charsets.UTF_8).size } +
                (session.outboundPackets.size - 1).coerceAtLeast(0)
            return packets
        }

        private fun handleSocketIoPost(session: IHTTPSession): Response {
            val sid = firstParam(session, "sid")
                ?: return textResponse(Response.Status.BAD_REQUEST, "missing sid")
            val active = socketSessions[sid]
                ?: return textResponse(Response.Status.BAD_REQUEST, "unknown sid")
            synchronized(active.monitor) {
                if (!socketUsableLocked(active)) return textResponse(Response.Status.BAD_REQUEST, "unknown sid")
                if (active.postInProgress) {
                    closeSocketSession(active)
                    return textResponse(Response.Status.BAD_REQUEST, "concurrent post")
                }
                active.postInProgress = true
            }
            try {
                val packets = decodeEnginePayload(readBody(session)) ?: run {
                    closeSocketSession(active, Response.Status.PAYLOAD_TOO_LARGE)
                    return textResponse(Response.Status.PAYLOAD_TOO_LARGE, "socket packet limit exceeded")
                }
                socketPostBeforePacketProcessing()
                packets.forEach { packet ->
                    if (!beginSocketPacketProcessing(active)) {
                        return textResponse(Response.Status.BAD_REQUEST, "unknown sid")
                    }
                    var closedStatus: Response.Status?
                    try {
                        runCatching { handleEnginePacket(active, packet) }
                            .onFailure { error ->
                                closeSocketSession(active)
                                runBlocking {
                                    auditLogger.warnThrottled(
                                        throttleKey = "local_nightscout_socket_packet_parse_failed",
                                        intervalMs = SOCKET_EVENT_AUDIT_INTERVAL_MS,
                                        message = "local_nightscout_socket_packet_parse_failed",
                                        metadata = mapOf("sid" to sid, "errorType" to error::class.java.simpleName)
                                    )
                                }
                            }
                    } finally {
                        closedStatus = finishSocketPacketProcessing(active)
                    }
                    if (closedStatus != null) {
                        return textResponse(closedStatus, if (closedStatus == Response.Status.OK) "ok" else "socket closed")
                    }
                }
                synchronized(active.monitor) {
                    if (!socketUsableLocked(active)) return textResponse(Response.Status.BAD_REQUEST, "unknown sid")
                    if (active.authorized) active.lastSeenAt = socketNowMs()
                }
                return textResponse(Response.Status.OK, "ok")
            } finally {
                synchronized(active.monitor) { active.postInProgress = false }
            }
        }

        // Called under the session monitor on GET, POST and each packet reservation.
        private fun socketUsableLocked(session: SocketSession): Boolean {
            if (session.closedStatus != null || socketSessions[session.sid] !== session) return false
            val pingAt = session.awaitingPongSince
            if (pingAt != null && socketNowMs() - pingAt >= SOCKET_PING_TIMEOUT_MS) {
                closeSocketSession(session)
                return false
            }
            return true
        }

        private fun beginSocketPacketProcessing(session: SocketSession): Boolean = synchronized(session.monitor) {
            // This reservation is the packet/overflow linearization point. The handler itself
            // runs unlocked so a treatment broadcast cannot form a cross-session lock cycle.
            if (!socketUsableLocked(session)) {
                false
            } else {
                session.activePacketHandlers += 1
                true
            }
        }

        private fun finishSocketPacketProcessing(session: SocketSession): Response.Status? = synchronized(session.monitor) {
            check(session.activePacketHandlers > 0)
            session.activePacketHandlers -= 1
            val closedStatus = session.closedStatus
            if (closedStatus != null && session.activePacketHandlers == 0) {
                socketSessions.remove(session.sid, session)
            }
            closedStatus
        }

        private fun createSocketSession(httpSession: IHTTPSession): SocketSession? {
            val now = socketNowMs()
            val nextId = socketSessionCounter.getAndIncrement()
            val sid = "copilot-eio-$nextId-${UUID.randomUUID().toString().take(8)}"
            val socketSid = "copilot-sio-$nextId-${UUID.randomUUID().toString().take(8)}"
            val userAgent = httpSession.headers["user-agent"]?.trim()?.take(MAX_USER_AGENT_LENGTH).orEmpty()
            val source = classifyExternalSource(userAgent)
            val created = SocketSession(
                sid = sid,
                socketSid = socketSid,
                createdAt = now,
                lastSeenAt = now,
                lastPingAt = now,
                source = source
            )
            synchronized(socketSessions) {
                pruneStaleSocketSessionsLocked(now)
                if (socketSessions.size >= MAX_ACTIVE_SOCKET_SESSIONS) return null
                socketSessions[sid] = created
            }
            runBlocking {
                auditLogger.infoThrottled(
                    throttleKey = "local_nightscout_socket_session_created:$source",
                    intervalMs = SOCKET_EVENT_AUDIT_INTERVAL_MS,
                    message = "local_nightscout_socket_session_created",
                    metadata = mapOf(
                        "sid" to sid,
                        "socketSid" to socketSid,
                        "source" to source,
                        "remote" to httpSession.remoteIpAddress.orEmpty()
                    )
                )
            }
            return created
        }

        private fun pruneStaleSocketSessions() {
            synchronized(socketSessions) {
                pruneStaleSocketSessionsLocked(socketNowMs())
            }
        }

        private fun pruneStaleSocketSessionsLocked(now: Long) {
            socketSessions.entries.forEach { entry ->
                val session = entry.value
                val expiryBase = if (session.authorized) session.lastSeenAt else session.createdAt
                if (now - expiryBase > socketLimits.sessionTtlMs) {
                    closeSocketSession(session)
                }
            }
        }

        private fun decodeEnginePayload(raw: String): List<String>? {
            val text = raw.trim()
            if (text.isBlank()) return emptyList()
            val packets = ArrayList<String>(minOf(socketLimits.maxInboundPacketsPerPost, 8))
            var start = 0
            for (index in text.indices) {
                if (text[index] != ENGINE_PACKET_SEPARATOR) continue
                val packet = text.substring(start, index).trim()
                if (packet.isNotEmpty()) {
                    if (packets.size == socketLimits.maxInboundPacketsPerPost) return null
                    packets += packet
                }
                start = index + 1
            }
            val packet = text.substring(start).trim()
            if (packet.isNotEmpty()) {
                if (packets.size == socketLimits.maxInboundPacketsPerPost) return null
                packets += packet
            }
            return packets
        }

        private fun handleEnginePacket(session: SocketSession, rawPacket: String) {
            if (rawPacket.isEmpty()) return
            when (rawPacket.first()) {
                ENGINE_PACKET_PING -> enqueueEnginePacket(session, ENGINE_PACKET_PONG.toString())
                ENGINE_PACKET_PONG -> synchronized(session.monitor) {
                    if (!socketUsableLocked(session)) return
                    session.awaitingPongSince = null
                    session.monitor.notifyAll()
                }
                ENGINE_PACKET_MESSAGE -> handleSocketPacket(session, rawPacket.drop(1))
                ENGINE_PACKET_CLOSE -> closeSocketSession(session, Response.Status.OK)
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
                    closeSocketSession(session, Response.Status.OK)
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
            val authorization = LocalNightscoutSocketAuthorization.authorize(payload, authenticator)
            synchronized(session.monitor) {
                if (!socketUsableLocked(session)) return
                session.authorized = false
                session.initializing = authorization.authorized
                session.initialPackets.clear()
                session.outboundPackets.clear()
                session.outboundBytes = 0
            }

            fun acknowledge() {
                packetId?.let { ackId ->
                    val auth = JsonObject().apply {
                        addProperty("read", authorization.read)
                        addProperty("write", authorization.write)
                        addProperty("write_treatment", authorization.writeTreatment)
                    }
                    enqueueSocketAck(session, ackId, listOf(auth))
                }
            }

            if (!authorization.authorized) {
                acknowledge()
                runBlocking {
                    auditLogger.warnThrottled(
                        throttleKey = "local_nightscout_socket_authorize_rejected:${session.source}",
                        intervalMs = SOCKET_EVENT_AUDIT_INTERVAL_MS,
                        message = "local_nightscout_socket_authorize_rejected",
                        metadata = mapOf("source" to session.source)
                    )
                }
                return
            }
            socketSnapshotCheckpoint(LocalNightscoutSnapshotPhase.BEFORE_CAPTURE)
            val initialPayload = captureInitialSnapshot(session, authorization.fromTs)
            socketSnapshotCheckpoint(LocalNightscoutSnapshotPhase.AFTER_CAPTURE)
            val initialPackets = LocalNightscoutInitialSnapshot.packets(
                gson = gson,
                source = initialPayload,
                packetByteLimit = minOf(64 * 1024, socketLimits.maxOutboundBytes)
            )
            if (initialPackets == null) {
                closeSocketSession(session, Response.Status.PAYLOAD_TOO_LARGE)
                runBlocking {
                    auditLogger.warnThrottled(
                        throttleKey = "local_nightscout_initial_snapshot_rejected",
                        intervalMs = SOCKET_EVENT_AUDIT_INTERVAL_MS,
                        message = "local_nightscout_initial_snapshot_rejected",
                        metadata = mapOf("reason" to "snapshot_budget")
                    )
                }
                return
            }
            socketSnapshotCheckpoint(LocalNightscoutSnapshotPhase.BEFORE_PUBLISH)
            // Lifecycle -> session, matching stop/update. The callback revalidates this server
            // instance before committing the session and READY under the same lifecycle lease.
            val published = onAuthenticatedSocket(this) {
                synchronized(session.monitor) {
                    if (!socketUsableLocked(session)) return@synchronized false
                    removeSnapshotCoveredDeltas(session, initialPayload)
                    session.initialPackets.addAll(initialPackets)
                    session.fromTs = authorization.fromTs
                    session.authorized = true
                    session.initializing = false
                    acknowledge()
                    if (!socketUsableLocked(session)) return@synchronized false
                    session.monitor.notifyAll()
                    true
                }
            }
            if (!published) return
            runBlocking {
                auditLogger.infoThrottled(
                    throttleKey = "local_nightscout_socket_authorize:${session.source}",
                    intervalMs = SOCKET_EVENT_AUDIT_INTERVAL_MS,
                    message = "local_nightscout_socket_authorize",
                    metadata = mapOf(
                        "sid" to session.sid,
                        "socketSid" to session.socketSid,
                        "source" to session.source,
                        "fromTs" to authorization.fromTs,
                        "authenticated" to true
                    )
                )
            }
        }

        private fun handleSocketDbAdd(
            session: SocketSession,
            packetId: Int?,
            payload: JsonObject?
        ) {
            if (!session.authorized) {
                enqueueUnauthorizedSocketAck(session, packetId)
                return
            }
            val collection = payload?.findText("collection")?.lowercase().orEmpty()
            val data = payload?.get("data")?.asJsonObjectOrNull()
            val generatedId = data?.findText("_id")
                ?: data?.findText("identifier")
                ?: "local-ns-${System.currentTimeMillis()}-${UUID.randomUUID()}"

            if (collection == "treatments" && data != null) {
                upsertTreatmentFromSocketPayload(data, preferredId = generatedId, sourceSession = session)
            }
            runBlocking {
                auditLogger.infoThrottled(
                    throttleKey = "local_nightscout_socket_dbadd:${session.source}:$collection",
                    intervalMs = SOCKET_EVENT_AUDIT_INTERVAL_MS,
                    message = "local_nightscout_socket_dbadd",
                    metadata = mapOf(
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
            if (!session.authorized) {
                enqueueUnauthorizedSocketAck(session, packetId)
                return
            }
            val collection = payload?.findText("collection")?.lowercase().orEmpty()
            val id = payload?.findText("_id")
            val data = payload?.get("data")?.asJsonObjectOrNull()

            if (collection == "treatments" && data != null) {
                upsertTreatmentFromSocketPayload(data, preferredId = id, sourceSession = session)
            }
            runBlocking {
                auditLogger.infoThrottled(
                    throttleKey = "local_nightscout_socket_dbupdate:${session.source}:$collection",
                    intervalMs = SOCKET_EVENT_AUDIT_INTERVAL_MS,
                    message = "local_nightscout_socket_dbupdate",
                    metadata = mapOf(
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

        private fun enqueueUnauthorizedSocketAck(session: SocketSession, packetId: Int?) {
            packetId?.let { ackId ->
                val response = JsonObject().apply {
                    addProperty("result", "unauthorized")
                    addProperty("read", false)
                    addProperty("write", false)
                }
                enqueueSocketAck(session, ackId, listOf(response))
            }
        }

        private fun upsertTreatmentFromSocketPayload(
            payload: JsonObject,
            preferredId: String?,
            sourceSession: SocketSession
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

            val incomingPayload = SyncRepository.buildNightscoutTreatmentPayloadStatic(
                request = request,
                source = SOURCE_LOCAL_NS_TREATMENT
            )
            val appliedTherapyCount = runBlocking {
                persistLocalNightscoutClinicalInput(
                    persistence = {
                        db.withTransaction {
                            val incomingRow = TherapyEventEntity(
                                id = treatmentId,
                                timestamp = timestamp,
                                type = SyncRepository.normalizeTreatmentTypeStatic(
                                    sourceEventType,
                                    incomingPayload
                                ),
                                payloadJson = gson.toJson(incomingPayload)
                            )
                            val existing = db.therapyDao().byId(treatmentId)
                            val finalRow = upsertLocalNightscoutSocketTherapyReplacement(
                                incomingRow = incomingRow,
                                loadLatest = { existing },
                                upsertAll = { rows -> db.therapyDao().upsertAll(rows) },
                                gson = gson
                            )
                            val payloadMap = therapyPayloadFromJson(finalRow.payloadJson)
                            val telemetryRows = TelemetryMetricMapper.fromNightscoutTreatment(
                                timestamp = timestamp,
                                source = SOURCE_LOCAL_NS_TREATMENT,
                                eventType = sourceEventType,
                                payload = payloadMap
                            )
                            if (telemetryRows.isNotEmpty()) {
                                db.telemetryDao().upsertAll(telemetryRows)
                            }
                            if (existing != finalRow) 1 else 0
                        }
                    },
                    shouldInvalidate = { it > 0 },
                    onClinicalInputPersisted = onClinicalInputPersisted
                )
            }
            invalidateHotPathCaches()
            if (appliedTherapyCount > 0) {
                auditClinicalInputInvalidation("socket_treatment", appliedTherapyCount, 0)
            }

            val treatment = NightscoutTreatment(
                id = treatmentId,
                date = timestamp,
                createdAt = Instant.ofEpochMilli(timestamp).toString(),
                eventType = sourceEventType,
                carbs = request.carbs,
                insulin = request.insulin,
                enteredCarbs = request.enteredCarbs,
                enteredInsulin = request.enteredInsulin,
                duration = durationMinutes,
                durationInMilliseconds = request.durationInMilliseconds,
                targetTop = request.targetTop,
                targetBottom = request.targetBottom,
                units = request.units,
                isValid = request.isValid ?: true,
                reason = request.reason,
                notes = request.notes
            )
            // The uploader receives only its ACK. Peers may need a retry even when persistence
            // is unchanged: an earlier attempt can commit and then fail before publication.
            emitTreatmentDeltaToSockets(listOf(treatment), excludedSession = sourceSession)
        }

        private fun emitTreatmentDeltaToSockets(
            treatments: List<NightscoutTreatment>,
            excludedSession: SocketSession? = null
        ) {
            if (treatments.isEmpty()) return
            runBlocking {
                // Use the same database -> session lock order as initial capture. A delayed
                // sender may publish the latest committed revision, never its stale request.
                db.withTransaction {
                    val ids = treatments.mapNotNull { it.id }.distinct()
                    val currentRows = ids.chunked(900).flatMap { db.therapyDao().byIds(it) }
                    if (currentRows.isEmpty()) return@withTransaction
                    val payload = JsonObject().apply {
                        addProperty("delta", true)
                        add("treatments", JsonArray().apply {
                            currentRows.sortedWith(compareBy({ it.timestamp }, { it.id })).forEach { row ->
                                add(row.toNightscoutTreatment(gson).toSocketTreatmentJson())
                            }
                        })
                    }
                    broadcastSocketEvent("dataUpdate", payload, excludedSession)
                }
            }
        }

        private fun captureInitialSnapshot(session: SocketSession, fromTs: Long): JsonObject {
            val since = fromTs.coerceAtLeast(0L)
            hotPathSocketAuthorizations.incrementAndGet()
            val payload = runBlocking {
                db.withTransaction {
                    val recentRows = if (since > 0L) {
                        db.glucoseDao().sinceDescLimit(since, SOCKET_DATAUPDATE_MAX_ROWS)
                    } else {
                        db.glucoseDao().latest(SOCKET_DATAUPDATE_MAX_ROWS)
                    }
                    val glucoseRows = GlucoseSanitizer.filterEntities(recentRows).takeLast(SOCKET_DATAUPDATE_MAX_ROWS)
                    val treatmentRows = db.therapyDao().sinceDescLimit(since, SOCKET_DATAUPDATE_MAX_ROWS).asReversed()
                    val captured = JsonObject().apply {
                        add("status", buildSocketStatusPayload())
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
                    // No later database write can commit before this snapshot transaction ends.
                    // Covered queued identities therefore belong to this or an older revision.
                    synchronized(session.monitor) {
                        removeSnapshotCoveredDeltas(session, captured, byIdentity = true)
                    }
                    captured
                }
            }
            maybeLogHotPathStats()
            return payload
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

        private fun broadcastSocketEvent(
            eventName: String,
            payload: JsonObject,
            excludedSession: SocketSession? = null
        ) {
            socketSessions.values.forEach { session ->
                if (session === excludedSession) return@forEach
                synchronized(session.monitor) {
                    if (!session.connected || (!session.authorized && !session.initializing) ||
                        !socketUsableLocked(session)) return@forEach
                    enqueueSocketEvent(
                        session = session,
                        eventName = eventName,
                        payload = payload
                    )
                }
            }
        }

        private fun removeSnapshotCoveredDeltas(session: SocketSession, snapshot: JsonObject, byIdentity: Boolean = false) {
            // After capture, exact equality only: newer corrections of the same row must survive.
            fun key(name: String, row: JsonElement): JsonElement? = if (!byIdentity) row else
                row.asJsonObject[if (name == "sgvs") "date" else "_id"]
            val covered = listOf("sgvs", "treatments").associateWith { name ->
                snapshot.getAsJsonArray(name)?.mapNotNull { key(name, it) }?.toSet().orEmpty()
            }
            val changedIdentities = mutableSetOf<Pair<String, JsonElement>>()
            val iterator = session.outboundPackets.listIterator()
            while (iterator.hasNext()) {
                val packet = iterator.next()
                if (!packet.startsWith("42[")) continue
                val event = JsonParser.parseString(packet.drop(2)).asJsonArray
                if (event.size() != 2 || event[0].asString != "dataUpdate") continue
                val payload = event[1].asJsonObject
                covered.forEach { (name, rows) ->
                    payload.getAsJsonArray(name)?.let { entries ->
                        val remaining = JsonArray()
                        entries.forEach { entry ->
                            val matches = key(name, entry)?.let(rows::contains) == true
                            val identity = entry.asJsonObject[if (name == "sgvs") "date" else "_id"]
                                ?.let { name to it }
                            val redundant = if (byIdentity) matches else
                                matches && identity != null && identity !in changedIdentities
                            if (!redundant) {
                                remaining.add(entry)
                                if (!byIdentity && identity != null) changedIdentities += identity
                            }
                        }
                        if (remaining.isEmpty) payload.remove(name) else payload.add(name, remaining)
                    }
                }
                if (payload.keySet().all { it == "delta" }) iterator.remove()
                else iterator.set("42${gson.toJson(event)}")
            }
            session.outboundBytes = session.outboundPackets.sumOf { it.toByteArray(Charsets.UTF_8).size } +
                (session.outboundPackets.size - 1).coerceAtLeast(0)
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
            val packetBytes = packet.toByteArray(Charsets.UTF_8).size
            synchronized(session.monitor) {
                if (!socketUsableLocked(session)) return
                val queuedBytes = packetBytes + if (session.outboundPackets.isEmpty()) 0 else 1
                val countOverflow = session.outboundPackets.size >= socketLimits.maxOutboundPackets
                val byteOverflow = queuedBytes > socketLimits.maxOutboundBytes ||
                    session.outboundBytes > socketLimits.maxOutboundBytes - queuedBytes
                if (countOverflow || byteOverflow) {
                    closeSocketSession(session, Response.Status.PAYLOAD_TOO_LARGE)
                    return
                }
                session.outboundPackets += packet
                session.outboundBytes += queuedBytes
                session.monitor.notifyAll()
            }
        }

        private fun closeSocketSession(session: SocketSession, status: Response.Status = Response.Status.BAD_REQUEST) {
            synchronized(session.monitor) {
                session.outboundPackets.clear()
                session.initialPackets.clear()
                session.outboundBytes = 0
                if (session.closedStatus == null) session.closedStatus = status
                session.authorized = false
                session.initializing = false
                session.monitor.notifyAll()
                if (session.activePacketHandlers == 0) {
                    socketSessions.remove(session.sid, session)
                }
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
            ).withStandardApiHeaders()
        }

        private fun textResponse(status: Response.Status, text: String): Response {
            return newFixedLengthResponse(status, CONTENT_TYPE_TEXT, text).withStandardApiHeaders()
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
            return cachedJsonOk(
                cacheKey = "entries:$count:$since",
                ttlMs = API_GET_RESPONSE_CACHE_TTL_MS
            ) {
                val fetchLimit = bufferedFetchLimit(count, 5_000)
                val rows = runBlocking {
                    val recentRows = if (since > 0L) {
                        db.glucoseDao().sinceDescLimit(since, fetchLimit)
                    } else {
                        db.glucoseDao().latest(fetchLimit)
                    }
                    GlucoseSanitizer.filterEntities(recentRows)
                }
                rows.asReversed()
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
            }
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
                runBlocking {
                    persistLocalNightscoutClinicalInput(
                        persistence = {
                            db.glucoseDao().upsertAll(rows)
                            rows.size
                        },
                        shouldInvalidate = { it > 0 },
                        onClinicalInputPersisted = onClinicalInputPersisted
                    )
                }
                invalidateHotPathCaches()
            }
            runBlocking {
                auditLogger.info(
                    "local_nightscout_entries_post",
                    mapOf("received" to parsed.objects.size, "inserted" to rows.size)
                )
            }
            if (rows.isNotEmpty()) {
                auditClinicalInputInvalidation("entries", rows.size, 0)
                runBlocking {
                    db.withTransaction {
                        val currentRows = rows.distinctBy { it.timestamp }.mapNotNull { row ->
                            db.glucoseDao().latestValidDistinctAtOrBefore(row.timestamp, 1)
                                .singleOrNull()?.takeIf { it.timestamp == row.timestamp }
                        }
                        if (currentRows.isEmpty()) return@withTransaction
                        val deltaPayload = JsonObject().apply {
                            addProperty("delta", true)
                            val sgvs = JsonArray()
                            currentRows.sortedBy { it.timestamp }.forEach { row -> sgvs.add(row.toSocketSgvJson()) }
                            add("sgvs", sgvs)
                        }
                        broadcastSocketEvent("dataUpdate", deltaPayload)
                    }
                }
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
                handleGetDeviceStatus(session)
            }
        }

        private fun handleGetDeviceStatus(session: IHTTPSession): Response {
            val count = firstInt(session, "count", 1).coerceIn(1, 100)
            val sinceRaw = firstParam(session, "find[created_at][\$gte]")
                ?: firstParam(session, "find[date][\$gte]")
                ?: firstParam(session, "find[mills][\$gte]")
            val since = parseFlexibleTimestamp(sinceRaw)
                ?: sinceRaw?.toLongOrNull()
                ?: 0L
            return cachedJsonOk(
                cacheKey = "devicestatus:$count:$since",
                ttlMs = DEVICESTATUS_RESPONSE_CACHE_TTL_MS
            ) {
                val recentRows = runBlocking {
                    db.telemetryDao().sinceByKeysDescLimit(
                        since = since.coerceAtLeast(0L),
                        keys = DEVICESTATUS_SYNTH_KEYS,
                        limit = DEVICESTATUS_RECENT_LIMIT
                    )
                }
                val latestGlucose = runBlocking { db.glucoseDao().latestOne() }
                if (recentRows.isEmpty() && latestGlucose == null) {
                    return@cachedJsonOk emptyList<Any>()
                }

                val nowTs = System.currentTimeMillis()
                val anchorTs = LocalNightscoutDeviceStatusComposer.resolveAnchorTimestamp(
                    recentRows = recentRows,
                    latestGlucose = latestGlucose,
                    nowTs = nowTs
                )
                if (anchorTs < since) {
                    return@cachedJsonOk emptyList<Any>()
                }

                val supportSince = (anchorTs - DEVICESTATUS_SUPPORT_LOOKBACK_MS).coerceAtLeast(0L)
                val latestByKey = runBlocking {
                    db.telemetryDao()
                        .latestByKeysSince(supportSince, DEVICESTATUS_SYNTH_KEYS)
                        .let(TelemetrySampleSelector::selectLatestByKey)
                }
                val payload = LocalNightscoutDeviceStatusComposer.compose(
                    timestamp = anchorTs,
                    latestByKey = latestByKey,
                    latestGlucose = latestGlucose
                )
                runBlocking {
                    auditLogger.infoThrottled(
                        throttleKey = "local_nightscout_devicestatus_get",
                        intervalMs = DEVICESTATUS_GET_AUDIT_INTERVAL_MS,
                        message = "local_nightscout_devicestatus_get",
                        metadata = mapOf(
                            "since" to since,
                            "countRequested" to count,
                            "recentRows" to recentRows.size,
                            "supportRows" to latestByKey.size,
                            "anchorTs" to anchorTs
                        )
                    )
                }
                listOf(payload).take(count)
            }
        }

        private fun handleGetTreatments(session: IHTTPSession): Response {
            val count = firstInt(session, "count", 200).coerceIn(1, 5_000)
            val sinceRaw = firstParam(session, "find[created_at][\$gte]") ?: firstParam(session, "find[mills][\$gte]")
            val since = parseFlexibleTimestamp(sinceRaw) ?: 0L

            val note = firstParam(session, "find[notes]")
            if (note != null) {
                if (note.length !in 1..512) return jsonBadRequest("invalid_notes_filter")
                return try {
                    val matches = LocalNightscoutNoteLookup(db).find(note, since.coerceAtLeast(0L), count)
                        .map { it.toNightscoutTreatment(gson) }
                    check(matches.all { it.notes == note }) { "Treatment note projection incomplete" }
                    jsonOk(matches)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    jsonServiceUnavailable("treatment_note_lookup_incomplete")
                }
            }

            return cachedJsonOk(
                cacheKey = "treatments:$count:$since",
                ttlMs = API_GET_RESPONSE_CACHE_TTL_MS
            ) {
                val fetchLimit = bufferedFetchLimit(count, 5_000)
                runBlocking {
                    db.therapyDao()
                        .sinceDescLimit(since.coerceAtLeast(0L), fetchLimit)
                        .take(count)
                        .map { it.toNightscoutTreatment(gson) }
                }
            }
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
            if (fromCopilotClient && parsed.objects.size != 1) {
                return jsonBadRequest("copilot_treatment_batch_not_supported")
            }

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
                val treatmentId = if (fromCopilotClient) {
                    stableCopilotTreatmentIdStatic(request.eventType, request.notes)
                        ?: return@forEach
                } else {
                    "local-ns-${timestamp}-${UUID.randomUUID()}"
                }
                val durationMinutes = request.duration
                    ?: request.durationInMilliseconds?.let { (it / 60_000L).toInt() }

                val payload = linkedMapOf<String, String>().apply {
                    putAll(
                        SyncRepository.buildNightscoutTreatmentPayloadStatic(
                            request = request,
                            source = SOURCE_LOCAL_NS_TREATMENT
                        )
                    )
                }
                val normalizedType = SyncRepository.normalizeTreatmentTypeStatic(request.eventType, payload)

                therapyRows += TherapyEventEntity(
                    id = treatmentId,
                    timestamp = timestamp,
                    type = normalizedType,
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
                    enteredCarbs = request.enteredCarbs,
                    enteredInsulin = request.enteredInsulin,
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
            if (fromCopilotClient) {
                if (responses.size != 1) return jsonBadRequest("missing_stable_idempotency_note")
                if (!relayTreatmentsToAaps(responses)) {
                    return jsonServiceUnavailable("therapy_relay_blocked")
                }
            }

            val appliedTherapyCount = runBlocking {
                persistLocalNightscoutClinicalInput(
                    persistence = {
                        val distinctRows = therapyRows.distinctBy { it.id }
                        val existingById = db.therapyDao().byIds(distinctRows.map { it.id })
                            .associateBy { it.id }
                        val appliedCount = distinctRows.count { existingById[it.id] != it }
                        db.therapyDao().upsertAll(therapyRows)
                        if (telemetryRows.isNotEmpty()) {
                            db.telemetryDao().upsertAll(telemetryRows.distinctBy { it.id })
                        }
                        auditLogger.info(
                            "local_nightscout_treatments_post",
                            mapOf(
                                "received" to parsed.objects.size,
                                "inserted" to appliedCount,
                                "telemetry" to telemetryRows.size
                            )
                        )
                        appliedCount
                    },
                    shouldInvalidate = { it > 0 },
                    onClinicalInputPersisted = onClinicalInputPersisted
                )
            }
            invalidateHotPathCaches()
            if (appliedTherapyCount > 0) {
                auditClinicalInputInvalidation("treatments", appliedTherapyCount, telemetryRows.size)
            }
            emitTreatmentDeltaToSockets(responses)

            return if (parsed.wasArray) jsonOk(responses) else jsonOk(responses.first())
        }

        private fun relayTreatmentsToAaps(treatments: List<NightscoutTreatment>): Boolean {
            if (treatments.isEmpty()) return true
            return try {
                runBlocking {
                    TherapyActionTransportGate.withArmedLease(
                        verifyPersistedState = {
                            settingsStore.settings.first().therapyActionsArmed
                        }
                    ) {
                        relayTreatmentsToAapsWithLease(treatments)
                    }
                }
            } catch (_: TherapyActionsNotArmedException) {
                runBlocking {
                    auditLogger.warn(
                        "local_nightscout_relay_skipped",
                        mapOf("reason" to "therapy_actions_not_armed", "count" to treatments.size)
                    )
                }
                false
            }
        }

        private fun relayTreatmentsToAapsWithLease(treatments: List<NightscoutTreatment>): Boolean {
            val targetPackage = resolveInstalledAapsPackage() ?: run {
                runBlocking {
                    auditLogger.warn(
                        "local_nightscout_relay_skipped",
                        mapOf("reason" to "aaps_package_not_found", "count" to treatments.size)
                    )
                }
                return false
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
            return delivered == treatments.size
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
            val iobRelevanceSignatures = mutableListOf<LocalNightscoutIobRelevanceSignature>()
            parsed.objects.forEach { payload ->
                val payloadMap = payload.toMap(gson)
                val ts = parseFlexibleTimestamp(payload.findText("created_at"))
                    ?: normalizeEpochMillis(payload.findNumeric("date")?.toLong())
                    ?: normalizeEpochMillis(payload.findNumeric("mills")?.toLong())
                    ?: System.currentTimeMillis()
                val prepared = prepareLocalNightscoutDeviceStatus(
                    timestamp = ts,
                    source = SOURCE_LOCAL_NS_DEVICESTATUS,
                    payload = payloadMap
                )
                telemetryRows += prepared.telemetryRows
                iobRelevanceSignatures += prepared.relevanceSignature
                val flattened = prepared.flattened
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
                var invalidated = false
                val appliedGlucoseCount = runBlocking {
                    persistLocalNightscoutClinicalInput(
                        persistence = {
                            if (telemetryRows.isNotEmpty()) {
                                db.telemetryDao().upsertAll(telemetryRows.distinctBy { it.id })
                            }
                            var appliedCount = 0
                            if (glucoseRows.isNotEmpty()) {
                                glucoseRows.forEach { row ->
                                    val existing = db.glucoseDao().bySourceAndTimestamp(row.source, row.timestamp)
                                    if (existing != null) {
                                        val differs = kotlin.math.abs(existing.mmol - row.mmol) > 0.01
                                        if (differs || existing.quality != row.quality) {
                                            db.glucoseDao().deleteBySourceAndTimestamp(row.source, row.timestamp)
                                            db.glucoseDao().upsertAll(listOf(row))
                                            appliedCount += 1
                                        }
                                    } else {
                                        db.glucoseDao().upsertAll(listOf(row))
                                        appliedCount += 1
                                    }
                                }
                            }
                            auditLogger.info(
                                "local_nightscout_devicestatus_post",
                                mapOf(
                                    "received" to parsed.objects.size,
                                    "telemetry" to telemetryRows.size,
                                    "glucose" to appliedCount
                                )
                            )
                            appliedCount
                        },
                        shouldInvalidate = { appliedCount ->
                            invalidationPolicy.shouldInvalidateDeviceStatus(
                                appliedCount,
                                telemetryRows,
                                iobRelevanceSignatures
                            )
                        },
                        onClinicalInputPersisted = {
                            onClinicalInputPersisted()
                            invalidated = true
                        }
                    )
                }
                invalidateHotPathCaches()
                if (invalidated) {
                    auditClinicalInputInvalidation(
                        "devicestatus",
                        appliedGlucoseCount,
                        telemetryRows.size
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
            val payload = readLocalNightscoutTransportPayload(payloadJson)
            return NightscoutTreatment(
                id = id,
                date = timestamp,
                createdAt = Instant.ofEpochMilli(timestamp).toString(),
                eventType = toNightscoutEventType(type),
                carbs = payload["carbs"]?.toDoubleOrNull(),
                insulin = payload["insulin"]?.toDoubleOrNull(),
                enteredCarbs = payload["enteredCarbs"]?.toDoubleOrNull()
                    ?: payload["mealCarbs"]?.toDoubleOrNull(),
                enteredInsulin = payload["enteredInsulin"]?.toDoubleOrNull()
                    ?: payload["bolusUnits"]?.toDoubleOrNull(),
                enteredBy = payload["enteredBy"],
                absolute = payload["absolute"]?.toDoubleOrNull(),
                rate = payload["rate"]?.toDoubleOrNull(),
                percentage = payload["percentage"]?.toIntOrNull(),
                duration = payload["duration"]?.toIntOrNull() ?: payload["durationMinutes"]?.toIntOrNull(),
                durationInMilliseconds = payload["durationInMilliseconds"]?.toLongOrNull(),
                targetTop = payload["targetTop"]?.toDoubleOrNull(),
                targetBottom = payload["targetBottom"]?.toDoubleOrNull(),
                units = payload["units"],
                isValid = when (payload["isValid"]?.trim()?.lowercase(java.util.Locale.ROOT)) {
                    null, "true", "1" -> true
                    "false", "0" -> false
                    else -> error("Invalid treatment validity")
                },
                reason = payload["reason"],
                notes = payload["notes"]
            )
        }

        private fun therapyPayloadFromJson(payloadJson: String): Map<String, String> {
            return decodeTherapyEventPayload(gson, payloadJson)
        }

        private fun readBody(session: IHTTPSession): String {
            when (LocalNightscoutRequestBodyPolicy.evaluate(session.headers)) {
                LocalNightscoutRequestBodyDecision.ALLOWED -> Unit
                LocalNightscoutRequestBodyDecision.MISSING_LENGTH ->
                    throw LocalNightscoutRequestBodyException(
                        Response.Status.LENGTH_REQUIRED,
                        "content length required"
                    )
                LocalNightscoutRequestBodyDecision.INVALID_LENGTH ->
                    throw LocalNightscoutRequestBodyException(
                        Response.Status.BAD_REQUEST,
                        "invalid content length"
                    )
                LocalNightscoutRequestBodyDecision.TOO_LARGE ->
                    throw LocalNightscoutRequestBodyException(
                        Response.Status.PAYLOAD_TOO_LARGE,
                        "request body too large"
                    )
            }
            val files = HashMap<String, String>()
            session.parseBody(files)
            val body = files["postData"].orEmpty()
            if (body.toByteArray(Charsets.UTF_8).size > LocalNightscoutRequestBodyPolicy.MAX_BODY_BYTES) {
                throw LocalNightscoutRequestBodyException(
                    Response.Status.PAYLOAD_TOO_LARGE,
                    "request body too large"
                )
            }
            return body
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
            val normalized = eventType
                .trim()
                .lowercase()
                .replace('-', ' ')
                .replace('_', ' ')
                .replace(Regex("\\s+"), " ")
            return when (normalized) {
                "temporary target" -> "temp_target"
                "carb correction" -> "carbs"
                "meal bolus" -> "meal_bolus"
                "correction bolus" -> "correction_bolus"
                "site change", "cannula change", "infusion set change", "set change", "pump site change" ->
                    "infusion_set_change"
                "sensor change", "cgm sensor change", "sensor start" -> "sensor_change"
                "insulin change", "reservoir change", "cartridge change", "pump refill", "insulin refill" ->
                    "insulin_refill"
                "pump battery change", "battery change", "battery replacement", "pump battery replacement" ->
                    "pump_battery_change"
                else -> normalized.replace(" ", "_")
            }
        }

        private fun toNightscoutEventType(type: String): String {
            return when (type.lowercase()) {
                "temp_target" -> "Temporary Target"
                "carbs" -> "Carb Correction"
                "meal_bolus" -> "Meal Bolus"
                "correction_bolus" -> "Correction Bolus"
                "infusion_set_change" -> "Site Change"
                "sensor_change" -> "Sensor Change"
                "insulin_refill" -> "Insulin Change"
                "pump_battery_change" -> "Pump Battery Change"
                else -> type.replace('_', ' ')
            }
        }

        private fun jsonOk(payload: Any): Response {
            return newFixedLengthResponse(
                Response.Status.OK,
                CONTENT_TYPE_JSON,
                gson.toJson(payload)
            ).withStandardApiHeaders()
        }

        private fun jsonBadRequest(message: String): Response {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                CONTENT_TYPE_JSON,
                gson.toJson(mapOf("status" to "bad_request", "message" to message))
            ).withStandardApiHeaders()
        }

        private fun jsonUnauthorized(message: String): Response {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                CONTENT_TYPE_JSON,
                gson.toJson(mapOf("status" to "unauthorized", "message" to message))
            ).withStandardApiHeaders()
        }

        private fun jsonServiceUnavailable(message: String): Response {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                CONTENT_TYPE_JSON,
                gson.toJson(mapOf("status" to "unavailable", "message" to message))
            ).withStandardApiHeaders()
        }

        private fun jsonNotFound(): Response {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                CONTENT_TYPE_JSON,
                gson.toJson(mapOf("status" to "not_found"))
            ).withStandardApiHeaders()
        }

        private fun htmlOk(body: String): Response {
            return newFixedLengthResponse(
                Response.Status.OK,
                CONTENT_TYPE_HTML,
                body
            ).withStandardApiHeaders()
        }

        private fun jsonStringOk(body: String): Response {
            return newFixedLengthResponse(
                Response.Status.OK,
                CONTENT_TYPE_JSON,
                body
            ).withStandardApiHeaders()
        }

        private fun cachedJsonOk(
            cacheKey: String,
            ttlMs: Long,
            varyOnData: Boolean = true,
            payloadBuilder: () -> Any
        ): Response {
            val now = System.currentTimeMillis()
            val revision = if (varyOnData) dataRevision.get() else STATIC_CACHE_REVISION
            val cached = responseCache[cacheKey]
            if (cached != null && cached.revision == revision && now < cached.expiresAtMs) {
                hotPathHttpCacheHits.incrementAndGet()
                maybeLogHotPathStats()
                return jsonStringOk(cached.body)
            }
            hotPathHttpCacheMisses.incrementAndGet()
            val body = gson.toJson(payloadBuilder())
            responseCache[cacheKey] = CachedJsonResponse(
                body = body,
                revision = revision,
                expiresAtMs = now + ttlMs.coerceAtLeast(0L)
            )
            maybeLogHotPathStats()
            return jsonStringOk(body)
        }

        private fun invalidateHotPathCaches() {
            dataRevision.incrementAndGet()
            responseCache.clear()
        }

        private fun bufferedFetchLimit(count: Int, hardCap: Int): Int {
            val capped = count.coerceIn(1, hardCap)
            val buffered = when {
                capped <= 24 -> capped * 8
                capped <= 100 -> capped * 4
                capped <= 500 -> capped * 2
                else -> capped
            }
            return buffered.coerceAtMost(hardCap)
        }

        private fun maybeLogHotPathStats() {
            val now = System.currentTimeMillis()
            val last = lastHotPathStatsLogTimestamp.get()
            if (now - last < HOT_PATH_STATS_LOG_INTERVAL_MS) return
            if (!lastHotPathStatsLogTimestamp.compareAndSet(last, now)) return
            val httpHits = hotPathHttpCacheHits.getAndSet(0L)
            val httpMisses = hotPathHttpCacheMisses.getAndSet(0L)
            val externalRequests = hotPathExternalRequests.getAndSet(0L)
            val authorizations = hotPathSocketAuthorizations.getAndSet(0L)
            if (
                httpHits == 0L &&
                httpMisses == 0L &&
                externalRequests == 0L &&
                authorizations == 0L
            ) {
                return
            }
            Log.i(
                TAG,
                "hotPathStats " +
                    "externalRequests=$externalRequests " +
                    "httpCacheHits=$httpHits httpCacheMisses=$httpMisses " +
                    "socketAuthorizations=$authorizations " +
                    "cacheRevision=${dataRevision.get()}"
            )
        }

        private fun Response.withStandardApiHeaders(): Response = apply {
            addHeader("Connection", "close")
            addHeader("Cache-Control", "no-store")
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
            var lastPingAt: Long,
            val source: String,
            @Volatile var connected: Boolean = false,
            @Volatile var authorized: Boolean = false,
            @Volatile var fromTs: Long = 0L,
            @Volatile var outboundBytes: Int = 0,
            var closedStatus: Response.Status? = null,
            var initializing: Boolean = false,
            var activePacketHandlers: Int = 0,
            val monitor: Object = Object(),
            var pollInProgress: Boolean = false,
            var postInProgress: Boolean = false,
            var awaitingPongSince: Long? = null,
            val initialPackets: MutableList<String> = mutableListOf(),
            val outboundPackets: MutableList<String> = mutableListOf()
        )

        private data class CachedJsonResponse(
            val body: String,
            val revision: Long,
            val expiresAtMs: Long
        )

        private fun auditClinicalInputInvalidation(
            channel: String,
            inserted: Int,
            telemetry: Int
        ) {
            runBlocking {
                auditLogger.infoThrottled(
                    throttleKey = "local_nightscout_clinical_input_persisted:$channel",
                    intervalMs = REACTIVE_AUTOMATION_AUDIT_INTERVAL_MS,
                    message = "local_nightscout_clinical_input_persisted",
                    metadata = mapOf(
                        "channel" to channel,
                        "inserted" to inserted,
                        "telemetry" to telemetry
                    )
                )
            }
        }
    }

    private companion object {
        @Volatile
        private var nanoHttpdNoiseFilterInstalled = false
        private const val HOST = "127.0.0.1"
        private const val TAG = "CopilotLocalNs"
        private const val CONTENT_TYPE_JSON = "application/json; charset=utf-8"
        private const val CONTENT_TYPE_HTML = "text/html; charset=utf-8"
        private const val CONTENT_TYPE_TEXT = "text/plain; charset=utf-8"
        private const val MIN_PORT = 1_024
        private const val MAX_PORT = 65_535
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val HEADER_COPILOT_CLIENT = "x-aaps-copilot-client"
        private const val EXTERNAL_REQUEST_LOG_DEBOUNCE_MS = 2_000L
        private const val STATUS_RESPONSE_CACHE_TTL_MS = 1_000L
        private const val API_GET_RESPONSE_CACHE_TTL_MS = 5_000L
        private const val DEVICESTATUS_RESPONSE_CACHE_TTL_MS = 10_000L
        private const val DEVICESTATUS_GET_AUDIT_INTERVAL_MS = 30_000L
        private const val SOCKET_EVENT_AUDIT_INTERVAL_MS = 30_000L
        private const val REACTIVE_AUTOMATION_AUDIT_INTERVAL_MS = 30_000L
        private const val HOT_PATH_STATS_LOG_INTERVAL_MS = 60_000L
        private const val STATIC_CACHE_REVISION = -1L
        private const val MAX_USER_AGENT_LENGTH = 160
        private const val SOURCE_LOCAL_NS_ENTRY = "local_nightscout_entry"
        private const val SOURCE_LOCAL_NS_TREATMENT = "local_nightscout_treatment"
        private const val SOURCE_LOCAL_NS_DEVICESTATUS = "local_nightscout_devicestatus"
        private val DEVICESTATUS_SYNTH_KEYS = listOf(
            "iob_units",
            "cob_grams",
            "activity_ratio",
            "distance_km",
            "active_minutes",
            "calories_active_kcal",
            "heart_rate_bpm",
            "dia_hours",
            "steps_count",
            "insulin_units",
            "carbs_grams",
            "uam_value",
            "isf_value",
            "cr_value",
            "sensor_age_days",
            "sensor_age_hours",
            "sensor_age_source_raw",
            "sage_days",
            "cage_days",
            "basal_rate_u_h",
            "insulin_req_units",
            "temp_target_low_mmol",
            "temp_target_high_mmol",
            "temp_target_duration_min",
            "profile_percent",
            "raw_com_eveningoutpost_dexdrip_extras_sensorstartedat"
        )
        private const val DEVICESTATUS_RECENT_LIMIT = 256
        private const val DEVICESTATUS_SUPPORT_LOOKBACK_MS = 72L * 60L * 60L * 1000L
        private const val ACTION_NS_EMULATOR = "com.eveningoutpost.dexdrip.NS_EMULATOR"
        private const val AAPS_PACKAGE_LEGACY = "info.nightscout.androidaps"
        private const val AAPS_PACKAGE_MODERN = "app.aaps"
        private const val SOCKET_ENGINE_PROTOCOL_VERSION = 4
        private const val SOCKET_PING_INTERVAL_MS = 25_000
        private const val SOCKET_PING_TIMEOUT_MS = 20_000
        private const val SOCKET_MAX_PAYLOAD_BYTES = LocalNightscoutRequestBodyPolicy.MAX_BODY_BYTES
        private const val MAX_ACTIVE_SOCKET_SESSIONS = 32
        private const val SOCKET_DATAUPDATE_MAX_ROWS = 1_200
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

        private fun suppressKnownNanoHttpdNoise() {
            if (nanoHttpdNoiseFilterInstalled) return
            synchronized(LocalNightscoutServer::class.java) {
                if (nanoHttpdNoiseFilterInstalled) return
                val suppressionFilter = buildNanoHttpdNoiseFilter()
                var logger: Logger? = Logger.getLogger(NanoHTTPD::class.java.name)
                while (logger != null) {
                    val existingFilter = logger.filter
                    logger.filter = Filter { record ->
                        if (!suppressionFilter.isLoggable(record)) {
                            false
                        } else {
                            existingFilter?.isLoggable(record) ?: true
                        }
                    }
                    installHandlerFilters(logger.handlers, suppressionFilter)
                    logger = logger.parent
                }
                installHandlerFilters(Logger.getLogger("").handlers, suppressionFilter)
                nanoHttpdNoiseFilterInstalled = true
            }
        }

        private fun buildNanoHttpdNoiseFilter(): Filter = Filter { record ->
            val message = record.message.orEmpty()
            val throwable = record.thrown
            val isKnownSocketCloseNoise = message.contains(
                "Could not send response to the client",
                ignoreCase = true
            ) && (
                throwable?.message?.contains("Socket is closed", ignoreCase = true) == true ||
                    throwable is java.net.SocketException
                )
            !isKnownSocketCloseNoise
        }

        private fun installHandlerFilters(handlers: Array<Handler>, suppressionFilter: Filter) {
            handlers.forEach { handler ->
                val existingFilter = handler.filter
                handler.filter = Filter { record ->
                    if (!suppressionFilter.isLoggable(record)) {
                        false
                    } else {
                        existingFilter?.isLoggable(record) ?: true
                    }
                }
            }
        }
    }
}

internal fun stableCopilotTreatmentIdStatic(
    eventType: String,
    notes: String?
): String? {
    val stableNote = notes?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val material = "${eventType.trim().lowercase()}|$stableNote"
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "local-ns-copilot-$digest"
}
