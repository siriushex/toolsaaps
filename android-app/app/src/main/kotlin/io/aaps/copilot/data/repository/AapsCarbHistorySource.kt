package io.aaps.copilot.data.repository

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal data class AapsCarbHistoryRequest(
    val nonce: String,
    val fromTs: Long,
    val throughTs: Long,
    val afterTimestamp: Long,
    val afterId: Long,
    val limit: Int
)

internal data class AapsCarbHistoryRow(
    val id: Long,
    val version: Int,
    val dateCreated: Long,
    val isValid: Boolean,
    val referenceId: Long?,
    val timestamp: Long,
    val duration: Long,
    val amount: Double,
    val notes: String?,
    val notesSha256: String?,
    val notesTruncated: Boolean,
    val nightscoutId: String?,
    val nightscoutIdSha256: String?,
    val nightscoutIdTruncated: Boolean
)

internal data class AapsCarbHistoryPage(
    val rows: List<AapsCarbHistoryRow>,
    val nextTimestamp: Long,
    val nextId: Long,
    val hasMore: Boolean,
    val generatedAt: Long,
    val revisionId: Long
)

internal sealed interface AapsCarbHistoryResult {
    data class Page(val value: AapsCarbHistoryPage) : AapsCarbHistoryResult
    data object Busy : AapsCarbHistoryResult
    data object Timeout : AapsCarbHistoryResult
    data object Error : AapsCarbHistoryResult
    data object Unavailable : AapsCarbHistoryResult
    data object Invalid : AapsCarbHistoryResult
    data object TransportTimeout : AapsCarbHistoryResult
}

internal data class AapsCarbHistoryWireRequest(
    val action: String,
    val componentPackage: String,
    val componentClass: String,
    val extras: Map<String, Any>
) {
    fun nonce(): String = extras.getValue(AapsCarbHistoryContract.EXTRA_NONCE) as String
}

internal data class AapsCarbHistoryWireResponse(
    val action: String?,
    val extras: Map<String, Any?>?
)

internal interface AapsCarbHistoryExtrasReader {
    fun keySet(): Set<String>
    fun get(key: String): Any?
}

internal fun extractAapsCarbHistoryWireResponse(
    action: () -> String?,
    extras: () -> AapsCarbHistoryExtrasReader?
): AapsCarbHistoryWireResponse? =
    try {
        val values = extras()?.let { reader ->
            reader.keySet().associateWith(reader::get)
        }
        AapsCarbHistoryWireResponse(action(), values)
    } catch (_: RuntimeException) {
        null
    }

internal fun interface AapsCarbHistoryRegistration {
    fun unregister()
}

internal interface AapsCarbHistoryTransport {
    fun register(
        action: String,
        permission: String,
        receiver: (AapsCarbHistoryWireResponse) -> Unit
    ): AapsCarbHistoryRegistration

    fun send(request: AapsCarbHistoryWireRequest)
}

internal object AapsCarbHistoryContract {
    const val REQUEST_PERMISSION =
        "info.nightscout.androidaps.permission.READ_COPILOT_CLINICAL_SUMMARY"
    const val RESPONSE_PERMISSION =
        "info.nightscout.androidaps.permission.RELAY_COPILOT_DATA"
    const val REQUEST_ACTION =
        "info.nightscout.androidaps.action.READ_COPILOT_CARB_HISTORY"
    const val RESPONSE_ACTION =
        "io.aaps.predictivecopilot.action.AAPS_CARB_HISTORY"
    const val AAPS_PACKAGE = "info.nightscout.androidaps"
    const val AAPS_RECEIVER_CLASS =
        "app.aaps.receivers.CopilotCarbHistoryReceiver"

    const val EXTRA_NONCE = "nonce"
    const val EXTRA_PAYLOAD = "payload"
    const val EXTRA_REVISION_ID = "revisionId"
    const val EXTRA_STATUS = "status"
    private const val EXTRA_FROM_TS = "fromTs"
    private const val EXTRA_THROUGH_TS = "throughTs"
    private const val EXTRA_AFTER_TIMESTAMP = "afterTimestamp"
    private const val EXTRA_AFTER_ID = "afterId"
    private const val EXTRA_LIMIT = "limit"

    const val MAX_PAYLOAD_UTF8_BYTES = 192 * 1_024
    const val MAX_PAYLOAD_CHARS = 176 * 1_024
    private const val MAX_ROWS = 200
    private const val MAX_DURATION_MS = 10L * 60L * 60L * 1_000L
    private const val MAX_RESPONSE_SKEW_MS = 10L * 60L * 1_000L
    private const val MAX_WINDOW_MS = 30L * 24L * 60L * 60L * 1_000L
    private const val MAX_NOTES_UTF8_BYTES = 8 * 1_024
    private const val MAX_NIGHTSCOUT_ID_UTF8_BYTES = 1 * 1_024

    private val PAYLOAD_RESPONSE_KEYS =
        setOf(EXTRA_NONCE, EXTRA_PAYLOAD, EXTRA_REVISION_ID)
    private val STATUS_RESPONSE_KEYS = setOf(EXTRA_NONCE, EXTRA_STATUS)
    private val PAGE_KEYS = setOf(
        EXTRA_NONCE,
        EXTRA_FROM_TS,
        EXTRA_THROUGH_TS,
        "generatedAt",
        "rows",
        "nextTimestamp",
        "nextId",
        "hasMore"
    )
    private val ROW_KEYS = setOf(
        "id",
        "version",
        "dateCreated",
        "isValid",
        "referenceId",
        "timestamp",
        "duration",
        "amount",
        "notes",
        "notesSha256",
        "notesTruncated",
        "nightscoutId",
        "nightscoutIdSha256",
        "nightscoutIdTruncated"
    )
    private val INTEGER_PATTERN = Regex("-?(0|[1-9][0-9]*)")
    private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
    private val JSON = Json {
        isLenient = false
        ignoreUnknownKeys = false
        allowSpecialFloatingPointValues = false
    }

    fun request(
        request: AapsCarbHistoryRequest
    ): AapsCarbHistoryWireRequest = AapsCarbHistoryWireRequest(
        action = REQUEST_ACTION,
        componentPackage = AAPS_PACKAGE,
        componentClass = AAPS_RECEIVER_CLASS,
        extras = linkedMapOf(
            EXTRA_NONCE to request.nonce,
            EXTRA_FROM_TS to request.fromTs,
            EXTRA_THROUGH_TS to request.throughTs,
            EXTRA_AFTER_TIMESTAMP to request.afterTimestamp,
            EXTRA_AFTER_ID to request.afterId,
            EXTRA_LIMIT to request.limit
        )
    )

    fun validateRequest(request: AapsCarbHistoryRequest): Boolean {
        if (request.nonce.length !in 16..80 ||
            request.nonce.any { !isSafeNonceCharacter(it) }
        ) {
            return false
        }
        if (request.fromTs <= 0L || request.fromTs >= request.throughTs) return false
        val window = subtractExact(request.throughTs, request.fromTs) ?: return false
        if (window > MAX_WINDOW_MS) return false
        if (request.limit !in 1..MAX_ROWS) return false
        return isValidCursor(
            request.afterTimestamp,
            request.afterId,
            request.fromTs,
            request.throughTs
        )
    }

    fun responseResult(
        request: AapsCarbHistoryRequest,
        response: AapsCarbHistoryWireResponse
    ): AapsCarbHistoryResult? {
        if (response.action != RESPONSE_ACTION) return null
        val extras = response.extras ?: return null
        val nonce = extras[EXTRA_NONCE] as? String ?: return null
        if (nonce != request.nonce) return null
        return when (extras.keys) {
            PAYLOAD_RESPONSE_KEYS -> {
                val payload = extras[EXTRA_PAYLOAD] as? String
                    ?: return AapsCarbHistoryResult.Invalid
                val revisionId = extras[EXTRA_REVISION_ID] as? Long
                    ?: return AapsCarbHistoryResult.Invalid
                if (revisionId < 0L) return AapsCarbHistoryResult.Invalid
                parsePage(request, payload, revisionId)
                    ?.let(AapsCarbHistoryResult::Page)
                    ?: AapsCarbHistoryResult.Invalid
            }

            STATUS_RESPONSE_KEYS -> when (extras[EXTRA_STATUS] as? String) {
                "BUSY" -> AapsCarbHistoryResult.Busy
                "TIMEOUT" -> AapsCarbHistoryResult.Timeout
                "ERROR" -> AapsCarbHistoryResult.Error
                else -> AapsCarbHistoryResult.Invalid
            }

            else -> AapsCarbHistoryResult.Invalid
        }
    }

    fun parsePage(
        request: AapsCarbHistoryRequest,
        payload: String,
        revisionId: Long
    ): AapsCarbHistoryPage? {
        if (revisionId < 0L) return null
        if (payload.length > MAX_PAYLOAD_CHARS ||
            payload.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_UTF8_BYTES
        ) {
            return null
        }
        return try {
            val root = JSON.parseToJsonElement(payload) as? JsonObject ?: return null
            if (root.keys != PAGE_KEYS) return null
            if (root.strictString(EXTRA_NONCE) != request.nonce) return null
            if (root.strictLong(EXTRA_FROM_TS) != request.fromTs) return null
            if (root.strictLong(EXTRA_THROUGH_TS) != request.throughTs) return null
            val generatedAt = root.strictLong("generatedAt") ?: return null
            val latestGeneratedAt = addExact(request.throughTs, MAX_RESPONSE_SKEW_MS)
                ?: return null
            if (generatedAt !in request.throughTs..latestGeneratedAt) return null

            val rowsJson = root["rows"] as? JsonArray ?: return null
            if (rowsJson.size > MAX_ROWS || rowsJson.size > request.limit) return null
            val rows = rowsJson.map { parseRow(it) ?: return null }
            if (!validateRows(request, rows)) return null

            val nextTimestamp = root.strictLong("nextTimestamp") ?: return null
            val nextId = root.strictLong("nextId") ?: return null
            val hasMore = root.strictBoolean("hasMore") ?: return null
            val last = rows.lastOrNull()
            if (last == null) {
                if (hasMore ||
                    nextTimestamp != request.afterTimestamp ||
                    nextId != request.afterId
                ) {
                    return null
                }
            } else if (nextTimestamp != last.timestamp || nextId != last.id) {
                return null
            }

            val page = AapsCarbHistoryPage(
                rows = rows,
                nextTimestamp = nextTimestamp,
                nextId = nextId,
                hasMore = hasMore,
                generatedAt = generatedAt,
                revisionId = revisionId
            )
            if (canonicalPayload(request, page) != payload) return null
            page
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRow(element: JsonElement): AapsCarbHistoryRow? {
        val row = element as? JsonObject ?: return null
        if (row.keys != ROW_KEYS) return null
        val referenceId = row.strictNullableLong("referenceId") ?: return null
        val notes = row.strictNullableString("notes") ?: return null
        val notesSha256 = row.strictNullableString("notesSha256") ?: return null
        val nightscoutId = row.strictNullableString("nightscoutId") ?: return null
        val nightscoutIdSha256 =
            row.strictNullableString("nightscoutIdSha256") ?: return null
        return AapsCarbHistoryRow(
            id = row.strictLong("id") ?: return null,
            version = row.strictInt("version") ?: return null,
            dateCreated = row.strictLong("dateCreated") ?: return null,
            isValid = row.strictBoolean("isValid") ?: return null,
            referenceId = referenceId.value,
            timestamp = row.strictLong("timestamp") ?: return null,
            duration = row.strictLong("duration") ?: return null,
            amount = row.strictDouble("amount") ?: return null,
            notes = notes.value,
            notesSha256 = notesSha256.value,
            notesTruncated = row.strictBoolean("notesTruncated") ?: return null,
            nightscoutId = nightscoutId.value,
            nightscoutIdSha256 = nightscoutIdSha256.value,
            nightscoutIdTruncated =
                row.strictBoolean("nightscoutIdTruncated") ?: return null
        )
    }

    private fun validateRows(
        request: AapsCarbHistoryRequest,
        rows: List<AapsCarbHistoryRow>
    ): Boolean {
        var previousTimestamp = request.afterTimestamp
        var previousId = request.afterId
        for (row in rows) {
            if (row.id <= 0L ||
                row.version < 0 ||
                row.dateCreated <= 0L ||
                (row.referenceId != null && row.referenceId <= 0L) ||
                row.timestamp !in request.fromTs..request.throughTs ||
                row.duration !in 0L..MAX_DURATION_MS ||
                !row.amount.isFinite() ||
                row.amount !in -400.0..400.0 ||
                !isAfter(row.timestamp, row.id, previousTimestamp, previousId) ||
                !isValidMetadata(
                    row.notes,
                    row.notesSha256,
                    row.notesTruncated,
                    MAX_NOTES_UTF8_BYTES
                ) ||
                !isValidMetadata(
                    row.nightscoutId,
                    row.nightscoutIdSha256,
                    row.nightscoutIdTruncated,
                    MAX_NIGHTSCOUT_ID_UTF8_BYTES
                )
            ) {
                return false
            }
            previousTimestamp = row.timestamp
            previousId = row.id
        }
        return true
    }

    private fun canonicalPayload(
        request: AapsCarbHistoryRequest,
        page: AapsCarbHistoryPage
    ): String = JsonObject(
        linkedMapOf(
            EXTRA_NONCE to JsonPrimitive(request.nonce),
            EXTRA_FROM_TS to JsonPrimitive(request.fromTs),
            EXTRA_THROUGH_TS to JsonPrimitive(request.throughTs),
            "generatedAt" to JsonPrimitive(page.generatedAt),
            "rows" to JsonArray(page.rows.map(::canonicalRow)),
            "nextTimestamp" to JsonPrimitive(page.nextTimestamp),
            "nextId" to JsonPrimitive(page.nextId),
            "hasMore" to JsonPrimitive(page.hasMore)
        )
    ).toString()
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

    private fun canonicalRow(row: AapsCarbHistoryRow): JsonObject = JsonObject(
        linkedMapOf(
            "id" to JsonPrimitive(row.id),
            "version" to JsonPrimitive(row.version),
            "dateCreated" to JsonPrimitive(row.dateCreated),
            "isValid" to JsonPrimitive(row.isValid),
            "referenceId" to row.referenceId.toJsonElement(),
            "timestamp" to JsonPrimitive(row.timestamp),
            "duration" to JsonPrimitive(row.duration),
            "amount" to JsonPrimitive(row.amount),
            "notes" to row.notes.toJsonElement(),
            "notesSha256" to row.notesSha256.toJsonElement(),
            "notesTruncated" to JsonPrimitive(row.notesTruncated),
            "nightscoutId" to row.nightscoutId.toJsonElement(),
            "nightscoutIdSha256" to row.nightscoutIdSha256.toJsonElement(),
            "nightscoutIdTruncated" to JsonPrimitive(row.nightscoutIdTruncated)
        )
    )

    private fun isValidMetadata(
        value: String?,
        digest: String?,
        truncated: Boolean,
        maxUtf8Bytes: Int
    ): Boolean {
        if (value == null) return digest == null && !truncated
        if (digest == null || !SHA_256_PATTERN.matches(digest)) return false
        val valueBytes = value.toByteArray(Charsets.UTF_8).size
        if (valueBytes > maxUtf8Bytes) return false
        return if (truncated) {
            valueBytes >= maxUtf8Bytes - 3 && digest != sha256(value)
        } else {
            digest == sha256(value)
        }
    }

    private fun isValidCursor(
        timestamp: Long,
        id: Long,
        fromTs: Long,
        throughTs: Long
    ): Boolean {
        if (timestamp < 0L || id < 0L) return false
        if (timestamp == 0L || id == 0L) return timestamp == 0L && id == 0L
        return timestamp in fromTs..throughTs
    }

    private fun isAfter(
        timestamp: Long,
        id: Long,
        previousTimestamp: Long,
        previousId: Long
    ): Boolean =
        timestamp > previousTimestamp ||
            (timestamp == previousTimestamp && id > previousId)

    private fun isSafeNonceCharacter(value: Char): Boolean =
        value in 'a'..'z' ||
            value in 'A'..'Z' ||
            value in '0'..'9' ||
            value == '-'

    private fun JsonObject.strictLong(key: String): Long? {
        val primitive = get(key) as? JsonPrimitive ?: return null
        if (primitive.isString || !INTEGER_PATTERN.matches(primitive.content)) return null
        return primitive.content.toLongOrNull()
    }

    private fun JsonObject.strictInt(key: String): Int? =
        strictLong(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    private fun JsonObject.strictDouble(key: String): Double? {
        val primitive = get(key) as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.content.toDoubleOrNull()?.takeIf(Double::isFinite)
    }

    private fun JsonObject.strictBoolean(key: String): Boolean? {
        val primitive = get(key) as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.booleanOrNull
    }

    private fun JsonObject.strictString(key: String): String? {
        val primitive = get(key) as? JsonPrimitive ?: return null
        return primitive.takeIf(JsonPrimitive::isString)?.content
    }

    private fun JsonObject.strictNullableLong(key: String): NullableValue<Long>? =
        when (val value = get(key)) {
            JsonNull -> NullableValue(null)
            is JsonPrimitive -> {
                if (value.isString || !INTEGER_PATTERN.matches(value.content)) {
                    null
                } else {
                    value.content.toLongOrNull()?.let(::NullableValue)
                }
            }
            else -> null
        }

    private fun JsonObject.strictNullableString(key: String): NullableValue<String>? =
        when (val value = get(key)) {
            JsonNull -> NullableValue(null)
            is JsonPrimitive ->
                value.takeIf(JsonPrimitive::isString)?.content?.let(::NullableValue)
            else -> null
        }

    private fun Long?.toJsonElement(): JsonElement =
        this?.let(::JsonPrimitive) ?: JsonNull

    private fun String?.toJsonElement(): JsonElement =
        this?.let(::JsonPrimitive) ?: JsonNull

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun subtractExact(left: Long, right: Long): Long? =
        try {
            Math.subtractExact(left, right)
        } catch (_: ArithmeticException) {
            null
        }

    private fun addExact(left: Long, right: Long): Long? =
        try {
            Math.addExact(left, right)
        } catch (_: ArithmeticException) {
            null
        }

    private data class NullableValue<T>(val value: T?)
}

internal class AapsCarbHistoryLifecycle(
    private val disposeRegistration: (AapsCarbHistoryRegistration) -> Unit
) {
    private val state = AtomicReference<State>(State.Registering)

    fun publish(registration: AapsCarbHistoryRegistration): Boolean {
        while (true) {
            when (val current = state.get()) {
                State.Registering -> {
                    if (state.compareAndSet(current, State.Registered(registration))) {
                        return true
                    }
                }

                State.Finished -> {
                    disposeRegistration(registration)
                    return false
                }

                is State.Registered,
                is State.Sending -> error("AAPS carbohydrate registration already published")
            }
        }
    }

    fun tryStartSending(): Boolean {
        while (true) {
            when (val current = state.get()) {
                is State.Registered -> {
                    if (state.compareAndSet(current, State.Sending(current.registration))) {
                        return true
                    }
                }

                State.Registering,
                is State.Sending,
                State.Finished -> return false
            }
        }
    }

    fun finish(): Boolean {
        while (true) {
            val current = state.get()
            if (current === State.Finished) return false
            if (!state.compareAndSet(current, State.Finished)) continue
            when (current) {
                is State.Registered -> disposeRegistration(current.registration)
                is State.Sending -> disposeRegistration(current.registration)
                State.Registering,
                State.Finished -> Unit
            }
            return true
        }
    }

    private sealed interface State {
        data object Registering : State
        data class Registered(
            val registration: AapsCarbHistoryRegistration
        ) : State
        data class Sending(
            val registration: AapsCarbHistoryRegistration
        ) : State
        data object Finished : State
    }
}

internal class AapsCarbHistorySource(
    private val transport: AapsCarbHistoryTransport,
    private val timeoutMs: Long = RESPONSE_TIMEOUT_MS,
    private val nonceFactory: () -> String = { UUID.randomUUID().toString() }
) {
    constructor(context: Context) : this(AndroidAapsCarbHistoryTransport(context))

    suspend fun load(
        fromTs: Long,
        throughTs: Long,
        afterTimestamp: Long,
        afterId: Long,
        limit: Int
    ): AapsCarbHistoryResult {
        val request = AapsCarbHistoryRequest(
            nonce = nonceFactory(),
            fromTs = fromTs,
            throughTs = throughTs,
            afterTimestamp = afterTimestamp,
            afterId = afterId,
            limit = limit
        )
        if (!AapsCarbHistoryContract.validateRequest(request)) {
            return AapsCarbHistoryResult.Invalid
        }
        return try {
            withTimeout(timeoutMs) {
                awaitResponse(request)
            }
        } catch (_: TimeoutCancellationException) {
            AapsCarbHistoryResult.TransportTimeout
        }
    }

    private suspend fun awaitResponse(
        request: AapsCarbHistoryRequest
    ): AapsCarbHistoryResult = suspendCancellableCoroutine { continuation ->
        val lifecycle = AapsCarbHistoryLifecycle { registration ->
            registration.unregisterSafely()
        }

        fun finish(result: AapsCarbHistoryResult) {
            if (!lifecycle.finish()) return
            if (continuation.isActive) continuation.resume(result)
        }

        continuation.invokeOnCancellation {
            lifecycle.finish()
        }

        try {
            val registered = transport.register(
                action = AapsCarbHistoryContract.RESPONSE_ACTION,
                permission = AapsCarbHistoryContract.RESPONSE_PERMISSION
            ) { response ->
                AapsCarbHistoryContract.responseResult(request, response)?.let(::finish)
            }
            if (!lifecycle.publish(registered)) return@suspendCancellableCoroutine
            if (!continuation.isActive) {
                lifecycle.finish()
                return@suspendCancellableCoroutine
            }
            if (!lifecycle.tryStartSending()) return@suspendCancellableCoroutine
            transport.send(AapsCarbHistoryContract.request(request))
        } catch (error: Throwable) {
            if (error is CancellationException || error is Error) {
                lifecycle.finish()
                throw error
            } else {
                finish(AapsCarbHistoryResult.Unavailable)
            }
        }
    }

    private fun AapsCarbHistoryRegistration.unregisterSafely() {
        try {
            unregister()
        } catch (error: Throwable) {
            error.rethrowIfCancellationOrFatal()
        }
    }

    private fun Throwable.rethrowIfCancellationOrFatal() {
        if (this is CancellationException || this is Error) throw this
    }

    private companion object {
        const val RESPONSE_TIMEOUT_MS = 30_000L
    }
}

private class AndroidAapsCarbHistoryTransport(
    context: Context
) : AapsCarbHistoryTransport {
    private val appContext = context.applicationContext

    override fun register(
        action: String,
        permission: String,
        receiver: (AapsCarbHistoryWireResponse) -> Unit
    ): AapsCarbHistoryRegistration {
        val broadcastReceiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(context: Context, intent: Intent) {
                extractAapsCarbHistoryWireResponse(
                    action = { intent.action },
                    extras = {
                        intent.extras?.let { bundle ->
                            object : AapsCarbHistoryExtrasReader {
                                override fun keySet(): Set<String> = bundle.keySet()
                                override fun get(key: String): Any? = bundle.get(key)
                            }
                        }
                    }
                )?.let(receiver)
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            broadcastReceiver,
            IntentFilter(action),
            permission,
            null,
            ContextCompat.RECEIVER_EXPORTED
        )
        val unregistered = AtomicBoolean(false)
        return AapsCarbHistoryRegistration {
            if (unregistered.compareAndSet(false, true)) {
                appContext.unregisterReceiver(broadcastReceiver)
            }
        }
    }

    override fun send(request: AapsCarbHistoryWireRequest) {
        val intent = Intent(request.action)
            .setComponent(ComponentName(request.componentPackage, request.componentClass))
        request.extras.forEach { (key, value) ->
            when (value) {
                is String -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                else -> error("Unsupported AAPS carbohydrate request extra: $key")
            }
        }
        appContext.sendBroadcast(intent)
    }
}
