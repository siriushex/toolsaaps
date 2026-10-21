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
import io.aaps.copilot.data.repository.LocalV3Write
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Authenticated settings wire storage. Envelopes and ACKs remain opaque, never therapy evidence. */
internal class LocalNightscoutV3SettingsApi(private val db: CopilotDatabase, now: () -> Long) {
    private val store = LocalNightscoutV3Store(db, now)
    private val collection = LocalV3Collection.SETTINGS

    suspend fun handle(method: String, path: String, query: Map<String, String>, readBody: () -> ByteArray): LocalV3HttpResult {
        if (path == ROOT) {
            return when (method) {
                "GET" -> {
                    require(query.keys.all { it == "limit" })
                    val page = store.latest(collection, limit(query))
                    // Native pending-command/offer search has no continuation loop.
                    if (page.hasMore) throw LocalV3StoreException(LocalV3StoreError.PAGE_CAPACITY)
                    documents(page.documents, page.collectionRevision)
                }
                "POST" -> {
                    require(query.isEmpty())
                    val body = parse(readBody())
                    val payload = format { validated(body) }
                    val id = body["identifier"]?.let { format { HistoryJson.string(it) } }
                        ?: MessageDigest.getInstance("SHA-256").digest(HistoryJson.bytes(payload))
                            .joinToString("") { "%02x".format(it.toInt() and 255) }
                    write(id, body, patch = false, createOnly = true)
                }
                else -> rejected(405)
            }
        }
        if (path.startsWith("$ROOT/history/")) {
            require(query.keys.all { it == "limit" })
            val raw = path.removePrefix("$ROOT/history/")
            require(Regex("0|[1-9][0-9]{0,14}").matches(raw))
            val after = raw.toLong().also { require(it in 0..LocalNightscoutV3Store.MAX_TIME) }
            if (method != "GET") return rejected(405)
            val page = store.history(collection, after, limit(query))
            return documents(page.documents, page.nextCursor)
        }
        val id = path.removePrefix("$ROOT/")
        require(ID.matches(id))
        return when (method) {
            "GET" -> {
                require(query.isEmpty())
                store.get(collection, id)?.takeUnless { it.deleted }?.let {
                    response(200, buildJsonObject { put("result", wire(it)) }, it.modifiedRevision)
                } ?: rejected(404)
            }
            "PUT", "PATCH" -> {
                require(query.isEmpty())
                write(id, parse(readBody()), patch = method == "PATCH", createOnly = false)
            }
            "DELETE" -> {
                require(query.isEmpty() || query == mapOf("permanent" to "true"))
                if (query.isNotEmpty() && !OFFER_ID.matches(id)) return rejected(501)
                db.withTransaction {
                    val previous = store.get(collection, id) ?: return@withTransaction rejected(404)
                    if (previous.source != SOURCE) return@withTransaction rejected(403)
                    val write = if (query.isEmpty()) store.delete(collection, id, previous.modifiedRevision)
                        else store.erasePairingOfferPayload(id)
                    written(write, false)
                }
            }
            else -> rejected(405)
        }
    }

    private suspend fun write(id: String, body: JsonObject, patch: Boolean, createOnly: Boolean): LocalV3HttpResult = db.withTransaction {
        require(ID.matches(id))
        val previous = store.get(collection, id)
        if (previous?.source?.let { it != SOURCE } == true) return@withTransaction rejected(403)
        if (previous?.deleted == true) return@withTransaction rejected(410)
        if (patch && previous == null) return@withTransaction rejected(404)
        format { metadata(body, id, previous) }
        val payload = format {
            val merged = if (patch && previous != null) {
                JsonObject(parse(previous.payloadJson.toByteArray()) + body.filterKeys { it !in METADATA })
            } else body
            validated(merged).also { next ->
                if (previous != null) {
                    val old = parse(previous.payloadJson.toByteArray())
                    require(COMMON.all { next[it] == old[it] })
                }
            }
        }
        val readOnly = format { body["isReadOnly"]?.let(HistoryJson::bool) ?: previous?.readOnly ?: false }
        val input = LocalV3DocumentInput(collection, id, SOURCE, HistoryJson.long(payload.getValue("date")), payload.toString(), readOnly)
        val write = if (previous == null || createOnly) store.create(input)
            else store.update(input, previous.modifiedRevision)
        written(write, previous == null)
    }

    private fun validated(body: JsonObject): JsonObject {
        finiteAndNoPairingSecret(body)
        val result = JsonObject(body.filterKeys { it !in METADATA })
        require(HistoryJson.long(result.getValue("date")) in 946_684_800_001..LocalNightscoutV3Store.MAX_TIME)
        require(HistoryJson.long(result.getValue("utcOffset")) in -1440..1440)
        require(HistoryJson.string(result.getValue("app")).let { it.isNotBlank() && it.length <= 128 })
        result["schemaVersion"]?.let { require(HistoryJson.long(it) in 1..1000) }
        return result
    }

    private fun metadata(body: JsonObject, id: String, previous: LocalNightscoutV3DocumentEntity?) {
        body["identifier"]?.let { require(HistoryJson.string(it) == id) }
        listOf("srvCreated" to previous?.createdRevision, "srvModified" to previous?.modifiedRevision).forEach { (field, expected) ->
            body[field]?.let { require(HistoryJson.long(it) == expected && previous != null) }
        }
        listOf("subject", "modifiedBy").forEach { field ->
            body[field]?.let { require(HistoryJson.string(it) == SOURCE && previous != null) }
        }
        body["isValid"]?.let { require(HistoryJson.bool(it)) }
        require("_id" !in body)
    }

    private fun finiteAndNoPairingSecret(value: JsonElement) {
        when (value) {
            is JsonObject -> {
                require(value.keys.none { it.equals("secretHex", ignoreCase = true) })
                value.values.forEach(::finiteAndNoPairingSecret)
            }
            is JsonArray -> value.forEach(::finiteAndNoPairingSecret)
            is JsonPrimitive -> if (value != JsonNull && !value.isString && value.booleanOrNull == null) HistoryJson.double(value)
        }
    }
    private fun limit(query: Map<String, String>): Int {
        val raw = query["limit"] ?: "100"
        require(Regex("[1-9][0-9]{0,3}").matches(raw))
        return raw.toInt().also { require(it in 1..1000) }
    }
    private fun documents(rows: List<LocalNightscoutV3DocumentEntity>, cursor: Long): LocalV3HttpResult {
        val body = buildJsonObject { put("result", JsonArray(rows.map(::wire))) }
        if (HistoryJson.bytes(body).size > 1024 * 1024) throw LocalV3StoreException(LocalV3StoreError.PAGE_CAPACITY)
        return response(200, body, cursor)
    }
    private fun wire(row: LocalNightscoutV3DocumentEntity) = JsonObject(parse(row.payloadJson.toByteArray()) + buildJsonObject {
        put("identifier", row.identifier); put("srvCreated", row.createdRevision); put("srvModified", row.modifiedRevision)
        put("subject", row.source); put("modifiedBy", row.source); put("isReadOnly", row.readOnly); put("isValid", !row.deleted)
    })
    private fun written(write: LocalV3Write, created: Boolean) = response(if (created) 201 else 200, buildJsonObject {
        put("identifier", write.document.identifier); put("lastModified", write.document.modifiedRevision)
        put("isDeduplication", !write.changed)
        if (!write.changed) put("deduplicatedIdentifier", write.document.identifier)
    }, write.document.modifiedRevision)
    private fun parse(bytes: ByteArray) = format { HistoryJson.parse(bytes, LocalNightscoutV3Store.MAX_PAYLOAD_BYTES) }
    private inline fun <T> format(block: () -> T): T = try { block() } catch (_: Exception) {
        throw IllegalArgumentException("invalid_v3_settings")
    }
    private fun response(status: Int, body: JsonObject, revision: Long? = null) = LocalV3HttpResult(status, body.toString(), revision)
    private fun rejected(status: Int) = response(status, buildJsonObject { put("status", status); put("message", "v3_request_rejected_$status") })

    companion object {
        private const val ROOT = "/api/v3/settings"
        private const val SOURCE = "local-v3-owner"
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
        private val OFFER_ID = Regex("aaps_clientcontrol_offer_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val COMMON = setOf("date", "utcOffset", "app")
        private val METADATA = setOf("identifier", "_id", "srvCreated", "srvModified", "subject", "modifiedBy", "isReadOnly", "isValid")
    }
}
