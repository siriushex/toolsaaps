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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Immutable catalog transport. Neither defaultProfile nor upload success selects an active profile. */
internal class LocalNightscoutV3ProfileApi(
    private val db: CopilotDatabase,
    now: () -> Long
) {
    private val store = LocalNightscoutV3Store(db, now)
    private val collection = LocalV3Collection.PROFILE

    suspend fun handle(method: String, path: String, query: Map<String, String>, readBody: () -> ByteArray): LocalV3HttpResult = when {
        path == ROOT -> when (method) {
            "POST" -> { require(query.isEmpty()); create(parse(readBody())) }
            "GET" -> {
                require(query == mapOf("sort\$desc" to "date", "limit" to "1"))
                val page = store.latest(collection, 1)
                // A late upload of an older catalog may have a newer revision. Do not skip it.
                documents(page.documents, page.documents.singleOrNull()?.modifiedRevision)
            }
            else -> rejected(405)
        }
        path.startsWith("$ROOT/history/") -> {
            require(query.keys.all { it == "limit" })
            val after = time(path.removePrefix("$ROOT/history/"))
            val limit = (query["limit"] ?: "10").also { require(Regex("[1-9][0-9]{0,3}").matches(it)) }
                .toInt().also { require(it in 1..1000) }
            if (method != "GET") rejected(405) else {
                val page = store.history(collection, after, limit)
                // The RC1 profile consumer has no invalidation contract. Never revive a tombstone.
                if (page.documents.any { it.deleted }) throw LocalV3StoreException(LocalV3StoreError.STATE)
                documents(page.documents, page.nextCursor)
            }
        }
        else -> {
            require(query.isEmpty())
            val id = path.removePrefix("$ROOT/").also { require(ID.matches(it)) }
            if (method != "GET") rejected(405) else store.get(collection, id)?.takeUnless { it.deleted }?.let {
                response(200, buildJsonObject { put("result", wire(it)) }, it.modifiedRevision)
            } ?: rejected(404)
        }
    }

    private suspend fun create(body: JsonObject): LocalV3HttpResult {
        val payload = format { validated(body) }
        val at = HistoryJson.long(payload.getValue("date"))
        val identity = buildJsonObject { put("owner", SOURCE); put("date", at) }
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
        val values = body.filterKeys { it !in METADATA }.filterValues { it != JsonNull }.toMutableMap()
        val at = HistoryJson.long(values.getValue("date"))
        require(at in 1..LocalNightscoutV3Store.MAX_TIME)
        listOf("created_at", "startDate").forEach { key ->
            values[key]?.let {
                val instant = Instant.parse(HistoryJson.string(it))
                require(instant.nano % 1_000_000 == 0 && instant.toEpochMilli() == at)
            }
            values[key] = JsonPrimitive(Instant.ofEpochMilli(at).toString())
        }
        values["app"]?.let { require(HistoryJson.string(it).length in 1..256) }
        val profiles = HistoryJson.obj(values.getValue("store"))
        require(profiles.size <= 100)
        if (profiles.isEmpty()) require("defaultProfile" !in values) else {
            require(HistoryJson.string(values.getValue("defaultProfile")) in profiles)
        }
        profiles.forEach { (name, value) ->
            require(name.isNotBlank() && name.length <= 256 && name.none { it.isISOControl() })
            val profile = HistoryJson.obj(value)
            AapsV3ProfileJson.profile(profile)
        }
        return JsonObject(values)
    }

    private fun documents(rows: List<LocalNightscoutV3DocumentEntity>, revision: Long?): LocalV3HttpResult {
        val result = buildJsonObject { put("result", JsonArray(rows.map(::wire))) }
        if (HistoryJson.bytes(result).size > 1024 * 1024) throw LocalV3StoreException(LocalV3StoreError.PAGE_CAPACITY)
        return response(200, result, revision)
    }
    private fun wire(row: LocalNightscoutV3DocumentEntity) = JsonObject(parse(row.payloadJson.toByteArray()) + buildJsonObject {
        put("identifier", row.identifier); put("srvCreated", row.createdRevision); put("srvModified", row.modifiedRevision)
        put("subject", row.source); put("modifiedBy", row.source)
    })
    private fun time(value: String): Long {
        require(Regex("0|[1-9][0-9]{0,14}").matches(value))
        return value.toLong().also { require(it in 0..LocalNightscoutV3Store.MAX_TIME) }
    }
    private fun parse(bytes: ByteArray) = format { HistoryJson.parse(bytes, LocalNightscoutV3Store.MAX_PAYLOAD_BYTES) }
    private inline fun <T> format(block: () -> T): T = try { block() } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) { throw IllegalArgumentException("invalid_v3_profile") }
    private fun response(status: Int, body: JsonObject, revision: Long? = null) = LocalV3HttpResult(status, body.toString(), revision)
    private fun rejected(status: Int) = response(status, buildJsonObject { put("status", status); put("message", "v3_request_rejected_$status") })

    companion object {
        private const val ROOT = "/api/v3/profile"
        private const val SOURCE = "local-v3-owner"
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
        private val METADATA = setOf("identifier", "srvCreated", "srvModified", "subject", "modifiedBy")
        private val FIELDS = setOf("date", "created_at", "startDate", "defaultProfile", "store", "app")
    }
}
