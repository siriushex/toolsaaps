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
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal data class LocalV3HttpResult(val status: Int, val body: String, val revision: Long? = null) {
    override fun toString() = "LocalV3HttpResult(status=$status)"
}

/** Authenticated wire boundary. Stored entries are not yet promoted into clinical input. */
internal class LocalNightscoutV3EntriesApi(
    private val db: CopilotDatabase,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val store = LocalNightscoutV3Store(db, now)
    private val entries = LocalV3Collection.ENTRIES
    private val deviceStatus = LocalNightscoutV3DeviceStatusApi(db, now)
    private val settings = LocalNightscoutV3SettingsApi(db, now)
    private val treatments = LocalNightscoutV3TreatmentsApi(db, now)
    private val profiles = LocalNightscoutV3ProfileApi(db, now)

    suspend fun handle(method: String, path: String, parameters: Map<String, List<String>>,
                       readBody: () -> ByteArray = { byteArrayOf() }): LocalV3HttpResult = try {
        val query = parameters.mapValues { (_, values) ->
            require(values.size == 1 && values.single().length <= 128)
            values.single()
        }
        require(query.size <= 8 && query.keys.all { it.length <= 64 })
        when {
            path == "/api/v3/profile" || path.startsWith("/api/v3/profile/") ->
                profiles.handle(method, path, query, readBody)
            path == "/api/v3/treatments" || path.startsWith("/api/v3/treatments/") ->
                treatments.handle(method, path, query, readBody)
            path == "/api/v3/settings" || path.startsWith("/api/v3/settings/") ->
                settings.handle(method, path, query, readBody)
            path == "/api/v3/devicestatus" || path.startsWith("/api/v3/devicestatus/") ->
                deviceStatus.handle(method, path, query, readBody)
            path == "/api/v3/status" -> {
                require(query.isEmpty())
                if (method != "GET") errorResponse(405) else status()
            }
            path == "/api/v3/lastModified" -> {
                require(query.isEmpty())
                if (method != "GET") errorResponse(405) else response(200, buildJsonObject {
                    put("result", buildJsonObject {
                        put("collections", buildJsonObject {
                            store.lastModified().forEach { (collection, revision) ->
                                put(if (collection == LocalV3Collection.FOOD) "foods" else collection.wireName, revision)
                            }
                        })
                    })
                })
            }
            path == "/api/v3/entries" -> when (method) {
                "GET" -> list(query)
                "POST" -> { require(query.isEmpty()); create(parse(readBody())) }
                else -> errorResponse(405)
            }
            path.startsWith("/api/v3/entries/history/") -> {
                require(query.keys.all { it == "limit" })
                val after = time(path.removePrefix("/api/v3/entries/history/"))
                if (method != "GET") errorResponse(405) else {
                    val page = store.history(entries, after, limit(query))
                    documents(page.documents, page.nextCursor)
                }
            }
            path.startsWith("/api/v3/entries/") -> {
                require(query.isEmpty())
                val id = path.removePrefix("/api/v3/entries/")
                require(ID.matches(id))
                when (method) {
                    "GET" -> store.get(entries, id)?.takeUnless { it.deleted }?.let {
                        response(200, buildJsonObject { put("result", wire(it)) }, it.modifiedRevision)
                    } ?: errorResponse(404)
                    "PATCH" -> patch(id, parse(readBody()))
                    "DELETE" -> db.withTransaction {
                        val previous = store.get(entries, id) ?: return@withTransaction errorResponse(404)
                        written(store.delete(entries, id, previous.modifiedRevision), wrapped = true)
                    }
                    else -> errorResponse(405)
                }
            }
            else -> errorResponse(501)
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: LocalV3StoreException) {
        errorResponse(when (failure.reason) {
            LocalV3StoreError.INVALID -> 400
            LocalV3StoreError.NOT_FOUND -> 404
            LocalV3StoreError.CONFLICT -> 409
            LocalV3StoreError.READ_ONLY -> 403
            LocalV3StoreError.CAPACITY, LocalV3StoreError.PAGE_CAPACITY -> 507
            LocalV3StoreError.CLOCK, LocalV3StoreError.STATE -> 503
        })
    } catch (_: IllegalArgumentException) {
        errorResponse(400)
    }

    private fun status() = response(200, buildJsonObject {
        put("result", buildJsonObject {
            put("version", "local-v3-wire-2")
            put("apiVersion", "3.0.0")
            put("srvDate", now())
            put("storage", buildJsonObject { put("storage", "sqlite"); put("version", "36") })
            put("apiPermissions", buildJsonObject {
                LocalV3Collection.entries.forEach {
                    put(it.wireName, when (it) {
                        entries -> "crud"
                        LocalV3Collection.DEVICE_STATUS -> "cr"
                        LocalV3Collection.SETTINGS -> "crud"
                        LocalV3Collection.TREATMENTS -> "crud"
                        LocalV3Collection.PROFILE -> "cr"
                        else -> ""
                    })
                }
            })
        })
    })

    private suspend fun list(query: Map<String, String>): LocalV3HttpResult {
        val count = limit(query)
        return when {
            query["sort"] == "date" -> {
                require(query.keys.all { it in setOf("sort", "date\$gt", "limit") })
                val page = store.chronological(entries, time(query["date\$gt"] ?: "0"), count)
                // Time paging is not a modification watermark. Do not skip unreturned late edits.
                documents(page.documents, null)
            }
            query["sort\$desc"] == "date" && query["type"] == "sgv" -> {
                require(query.keys.all { it in setOf("sort\$desc", "type", "limit") })
                val page = store.latest(entries, 1000)
                val matches = page.documents.filter { parse(it.payloadJson.toByteArray())["type"] == JsonPrimitive("sgv") }
                if (matches.size < count && page.hasMore) throw LocalV3StoreException(LocalV3StoreError.PAGE_CAPACITY)
                documents(matches.take(count), null)
            }
            else -> errorResponse(400)
        }
    }

    private suspend fun create(body: JsonObject): LocalV3HttpResult {
        val payload = format { validatedPayload(body) }
        val at = HistoryJson.long(payload.getValue("date"))
        val id = optionalString(body, "identifier") ?: stableIdentifier(payload)
        require(ID.matches(id))
        return db.withTransaction {
            val previous = store.get(entries, id)
            format { checkMetadata(body, previous) }
            val write = store.create(LocalV3DocumentInput(entries, id, SOURCE, at, payload.toString(), readOnly(body)))
            written(write, wrapped = false, created = write.changed)
        }
    }

    private suspend fun patch(id: String, body: JsonObject): LocalV3HttpResult = db.withTransaction {
        val previous = store.get(entries, id) ?: return@withTransaction errorResponse(404)
        format { checkMetadata(body, previous) }
        require(optionalString(body, "identifier")?.let { it == id } != false)
        val old = parse(previous.payloadJson.toByteArray())
        // RC1 updateSvg deliberately omits date and utcOffset; PATCH must retain them.
        val payload = format { validatedPayload(JsonObject(old + body.filterKeys { it !in METADATA })) }
        require(listOf("date", "type", "device", "utcOffset").all { old[it] == payload[it] })
        val expected = optionalLong(body, "srvModified") ?: previous.modifiedRevision
        written(store.update(LocalV3DocumentInput(entries, id, previous.source, previous.eventAtMs,
            payload.toString(), if (body.containsKey("isReadOnly")) readOnly(body) else previous.readOnly), expected), true)
    }

    private fun checkMetadata(body: JsonObject, previous: LocalNightscoutV3DocumentEntity?) {
        require(body.keys.all { it in FIELDS || it in METADATA })
        require(body["isValid"]?.takeUnless { it == JsonNull }?.let(HistoryJson::bool) != false)
        optionalString(body, "subject")?.let { require(it == SOURCE && previous != null) }
        optionalString(body, "modifiedBy")?.let { require(it == SOURCE && previous != null) }
        optionalLong(body, "srvCreated")?.let { require(it == previous?.createdRevision) }
        optionalLong(body, "srvModified")?.let { require(previous != null && it in 1..previous.modifiedRevision) }
    }

    private fun validatedPayload(body: JsonObject): JsonObject {
        require(body.keys.all { it in FIELDS || it in METADATA })
        val payload = JsonObject(body.filterKeys { it !in METADATA })
        val type = HistoryJson.string(payload.getValue("type"))
        require(type == "sgv" || type == "mbg")
        val at = HistoryJson.long(payload.getValue("date"))
        require(at in 1..LocalNightscoutV3Store.MAX_TIME)
        require(HistoryJson.double(payload.getValue(type)) in Double.MIN_VALUE..1000.0)
        require(if (type == "sgv") payload["mbg"] == null else payload["sgv"] == null)
        listOf("device", "direction", "app", "dateString", "units").forEach { name ->
            optionalString(payload, name)?.let { require(it.length <= 256) }
        }
        optionalString(payload, "units")?.let { require(it == "mg/dl" || it == "mmol/l") }
        optionalString(payload, "dateString")?.let { require(runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() == at) }
        payload["utcOffset"]?.takeUnless { it == JsonNull }?.let {
            val number = it as? JsonPrimitive ?: error("invalid_offset")
            require(!number.isString && number.longOrNull in -840L..840L)
        }
        listOf("noise", "filtered", "unfiltered", "sensorMgdlAtPairing").forEach { name ->
            payload[name]?.takeUnless { it == JsonNull }?.let { require(HistoryJson.double(it) >= 0) }
        }
        payload["isCalibration"]?.takeUnless { it == JsonNull }?.let {
            val calibration = HistoryJson.bool(it)
            require(type == "mbg")
            if (calibration) {
                require(optionalString(payload, "units") == "mg/dl")
                require(HistoryJson.double(payload.getValue("sensorMgdlAtPairing")) > 0)
            }
        }
        if (payload.containsKey("sensorMgdlAtPairing")) require(type == "mbg")
        return payload
    }

    private fun stableIdentifier(payload: JsonObject): String {
        // A CGM sample is keyed by producer, type and acquisition time, never its glucose value.
        val device = optionalString(payload, "device")
        // RC1 CAL.toNSMbgV3 omits device and reconciles its own calibration by timestamp.
        val ownerCalibration = device == null && payload["type"] == JsonPrimitive("mbg") &&
            payload["isCalibration"] == JsonPrimitive(true)
        require(!device.isNullOrBlank() || ownerCalibration)
        val identity = buildJsonObject {
            put("owner", SOURCE)
            put("device", payload["device"] ?: JsonNull)
            put("type", payload.getValue("type"))
            put("date", payload.getValue("date"))
            if (ownerCalibration) put("calibration", true)
        }
        return MessageDigest.getInstance("SHA-256").digest(HistoryJson.bytes(identity))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun wire(row: LocalNightscoutV3DocumentEntity) = JsonObject(parse(row.payloadJson.toByteArray()) + buildJsonObject {
        put("identifier", row.identifier)
        put("srvCreated", row.createdRevision)
        put("srvModified", row.modifiedRevision)
        put("subject", SOURCE)
        put("modifiedBy", SOURCE)
        put("isReadOnly", row.readOnly)
        if (row.deleted) put("isValid", false)
    })
    private fun documents(rows: List<LocalNightscoutV3DocumentEntity>, revision: Long?) =
        response(200, buildJsonObject { put("result", JsonArray(rows.map(::wire))) }, revision)
    private fun written(write: LocalV3Write, wrapped: Boolean, created: Boolean = false): LocalV3HttpResult {
        val body = buildJsonObject {
            put("identifier", write.document.identifier)
            put("lastModified", write.document.modifiedRevision)
            put("isDeduplication", !write.changed)
            if (!write.changed) put("deduplicatedIdentifier", write.document.identifier)
        }
        return response(if (created) 201 else 200,
            if (wrapped) buildJsonObject { put("result", body) } else body, write.document.modifiedRevision)
    }
    private fun parse(bytes: ByteArray) = format { HistoryJson.parse(bytes, LocalNightscoutV3Store.MAX_PAYLOAD_BYTES) }
    private fun optionalString(body: JsonObject, key: String) = format { body[key]?.takeUnless { it == JsonNull }?.let(HistoryJson::string) }
    private fun optionalLong(body: JsonObject, key: String) = format { body[key]?.takeUnless { it == JsonNull }?.let(HistoryJson::long) }
    private fun readOnly(body: JsonObject) = format { body["isReadOnly"]?.takeUnless { it == JsonNull }?.let(HistoryJson::bool) ?: false }
    private inline fun <T> format(block: () -> T): T = try { block() } catch (_: Exception) {
        throw IllegalArgumentException("invalid_v3_document")
    }
    private fun time(value: String): Long {
        require(DECIMAL.matches(value))
        return value.toLong().also { require(it in 0..LocalNightscoutV3Store.MAX_TIME) }
    }
    private fun limit(query: Map<String, String>) = time(query["limit"] ?: "100").also { require(it in 1..1000) }.toInt()
    private fun response(status: Int, body: JsonObject, revision: Long? = null) = LocalV3HttpResult(status, body.toString(), revision)
    private fun errorResponse(status: Int) = response(status, buildJsonObject { put("status", status); put("message", "v3_request_rejected_$status") })

    companion object {
        private const val SOURCE = "local-v3-owner"
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
        private val DECIMAL = Regex("0|[1-9][0-9]{0,14}")
        private val METADATA = setOf("identifier", "srvCreated", "srvModified", "subject", "modifiedBy", "isValid", "isReadOnly")
        private val FIELDS = setOf("type", "date", "sgv", "mbg", "dateString", "device", "direction", "app", "utcOffset",
            "noise", "filtered", "unfiltered", "units", "sensorMgdlAtPairing", "isCalibration")
    }
}
