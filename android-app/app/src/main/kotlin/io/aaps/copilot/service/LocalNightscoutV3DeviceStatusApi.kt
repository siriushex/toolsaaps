package io.aaps.copilot.service

import androidx.room.withTransaction
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity
import io.aaps.copilot.data.repository.HistoryJson
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import io.aaps.copilot.data.repository.LocalV3Collection
import io.aaps.copilot.data.repository.LocalV3DocumentInput
import io.aaps.copilot.data.repository.LocalV3StoreError
import io.aaps.copilot.data.repository.LocalV3StoreException
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.*

/** Immutable status wire records; neither upload nor read grants clinical or command authority. */
internal class LocalNightscoutV3DeviceStatusApi(
    private val db: CopilotDatabase,
    now: () -> Long
) {
    private val store = LocalNightscoutV3Store(db, now)
    private val collection = LocalV3Collection.DEVICE_STATUS

    suspend fun handle(method: String, path: String, query: Map<String, String>, readBody: () -> ByteArray): LocalV3HttpResult {
        require(query.isEmpty())
        return when {
            path == ROOT -> if (method == "POST") create(parse(readBody())) else rejected(405)
            path.startsWith("$ROOT/history/") -> {
                val value = path.removePrefix("$ROOT/history/")
                require(Regex("0|[1-9][0-9]{0,14}").matches(value))
                val after = value.toLong().also { require(it in 0..LocalNightscoutV3Store.MAX_TIME) }
                if (method != "GET") return rejected(405)
                val page = store.history(collection, after, 1000)
                // RC1 returns one list, without a continuation cursor. Never silently truncate it.
                if (page.hasMore) throw LocalV3StoreException(LocalV3StoreError.PAGE_CAPACITY)
                if (page.documents.any { it.deleted }) throw LocalV3StoreException(LocalV3StoreError.STATE)
                val result = buildJsonObject { put("result", JsonArray(page.documents.map(::wire))) }
                if (HistoryJson.bytes(result).size > 1024 * 1024) throw LocalV3StoreException(LocalV3StoreError.PAGE_CAPACITY)
                response(200, result, page.nextCursor)
            }
            else -> {
                val id = path.removePrefix("$ROOT/")
                require(ID.matches(id))
                if (method != "GET") rejected(405) else store.get(collection, id)?.takeUnless { it.deleted }?.let {
                    response(200, buildJsonObject { put("result", wire(it)) }, it.modifiedRevision)
                } ?: rejected(404)
            }
        }
    }

    private suspend fun create(body: JsonObject): LocalV3HttpResult {
        val payload = format { validated(body) }
        val at = HistoryJson.long(payload.getValue("date"))
        // Status identity belongs to this owner and producer/time, not IOB or reservoir values.
        val identity = buildJsonObject {
            put("owner", SOURCE); put("device", payload.getValue("device")); put("date", at)
        }
        val id = MessageDigest.getInstance("SHA-256").digest(HistoryJson.bytes(identity))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return db.withTransaction {
            val previous = store.get(collection, id)
            format {
                body["identifier"]?.takeUnless { it == JsonNull }?.let {
                    require(HistoryJson.string(it) == id && previous != null)
                }
                listOf("srvCreated" to previous?.createdRevision, "srvModified" to previous?.modifiedRevision).forEach { (key, expected) ->
                    body[key]?.takeUnless { it == JsonNull }?.let { require(HistoryJson.long(it) == expected) }
                }
                listOf("subject", "modifiedBy").forEach { key ->
                    body[key]?.takeUnless { it == JsonNull }?.let { require(HistoryJson.string(it) == SOURCE && previous != null) }
                }
            }
            val write = store.create(LocalV3DocumentInput(collection, id, SOURCE, at, payload.toString()))
            response(if (write.changed) 201 else 200, buildJsonObject {
                put("identifier", id); put("lastModified", write.document.modifiedRevision)
                put("isDeduplication", !write.changed)
                if (!write.changed) put("deduplicatedIdentifier", id)
            }, write.document.modifiedRevision)
        }
    }

    private fun validated(body: JsonObject): JsonObject {
        require(body.keys.all { it in FIELDS || it in METADATA })
        finite(body)
        val values = body.filterKeys { it !in METADATA }.filterValues { it != JsonNull }.toMutableMap()
        val date = values["date"]?.let(HistoryJson::long)
        val created = values["created_at"]?.let {
            val instant = Instant.parse(HistoryJson.string(it))
            require(instant.nano % 1_000_000 == 0)
            instant.toEpochMilli()
        }
        val at = date ?: created ?: throw IllegalArgumentException("missing_status_time")
        require(at in 1..LocalNightscoutV3Store.MAX_TIME && (created == null || created == at))
        require(HistoryJson.string(values.getValue("device")).let { it.isNotBlank() && it.length <= 256 })
        values["app"]?.let { require(HistoryJson.string(it).length in 1..256) }
        values["uploaderBattery"]?.let(::percent)
        values["isCharging"]?.let(HistoryJson::bool)
        values["date"] = JsonPrimitive(at)
        values["created_at"] = JsonPrimitive(Instant.ofEpochMilli(at).toString())
        normalizeObject(values, "uploader", setOf("battery")) { obj -> obj["battery"]?.let(::percent) }
        normalizeObject(values, "openaps", setOf("suggested", "enacted", "iob")) { obj -> obj.values.forEach(HistoryJson::obj) }
        normalizeObject(values, "pump", setOf("clock", "reservoir", "reservoir_display_override", "battery", "status", "extended")) { pump ->
            listOf("clock", "reservoir_display_override").forEach { key -> pump[key]?.let { require(HistoryJson.string(it).length <= 256) } }
            pump["reservoir"]?.let(HistoryJson::double)
            pump["extended"]?.let(HistoryJson::obj)
            normalizeObject(pump, "battery", setOf("percent", "voltage")) { battery ->
                battery["percent"]?.let(::percent); battery["voltage"]?.let(HistoryJson::double)
            }
            normalizeObject(pump, "status", setOf("status", "timestamp")) { obj ->
                obj.values.forEach { require(HistoryJson.string(it).length <= 256) }
            }
        }
        return JsonObject(values)
    }

    private fun normalizeObject(parent: MutableMap<String, JsonElement>, key: String, fields: Set<String>,
                                validate: (MutableMap<String, JsonElement>) -> Unit) {
        val value = parent[key] ?: return
        val obj = HistoryJson.obj(value)
        require(obj.keys.all { it in fields })
        val normalized = obj.filterValues { it != JsonNull }.toMutableMap()
        validate(normalized)
        // The stock SDK adds empty optional wrappers on reconstruction. Opaque subtrees stay exact.
        if (normalized.isEmpty()) parent.remove(key) else parent[key] = JsonObject(normalized)
    }

    private fun finite(value: JsonElement) {
        when (value) {
            is JsonObject -> value.values.forEach(::finite)
            is JsonArray -> value.forEach(::finite)
            is JsonPrimitive -> if (value != JsonNull && !value.isString && value.booleanOrNull == null) HistoryJson.double(value)
        }
    }
    private fun percent(value: JsonElement) { require(HistoryJson.long(value) in 0..100) }
    private fun parse(bytes: ByteArray) = format { HistoryJson.parse(bytes, LocalNightscoutV3Store.MAX_PAYLOAD_BYTES) }
    private inline fun <T> format(block: () -> T): T = try { block() } catch (_: Exception) {
        throw IllegalArgumentException("invalid_v3_device_status")
    }
    private fun wire(row: LocalNightscoutV3DocumentEntity) = JsonObject(parse(row.payloadJson.toByteArray()) + buildJsonObject {
        put("identifier", row.identifier); put("srvCreated", row.createdRevision); put("srvModified", row.modifiedRevision)
        put("subject", SOURCE); put("modifiedBy", SOURCE)
    })
    private fun response(status: Int, body: JsonObject, revision: Long? = null) = LocalV3HttpResult(status, body.toString(), revision)
    private fun rejected(status: Int) = response(status, buildJsonObject { put("status", status); put("message", "v3_request_rejected_$status") })

    companion object {
        private const val ROOT = "/api/v3/devicestatus"
        private const val SOURCE = "local-v3-owner"
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
        private val METADATA = setOf("identifier", "srvCreated", "srvModified", "subject", "modifiedBy")
        private val FIELDS = setOf("app", "created_at", "date", "uploaderBattery", "isCharging", "device", "uploader", "pump", "openaps")
    }
}
