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
import kotlinx.serialization.json.*

/** Authenticated RC1 treatment storage. An upload is not a delivery/application receipt. */
internal class LocalNightscoutV3TreatmentsApi(private val db: CopilotDatabase, now: () -> Long) {
    private val store = LocalNightscoutV3Store(db, now)
    private val collection = LocalV3Collection.TREATMENTS

    suspend fun handle(method: String, path: String, query: Map<String, String>, readBody: () -> ByteArray): LocalV3HttpResult {
        if (path == ROOT) return when (method) {
            "GET" -> {
                require(query["sort"] == "created_at" && query.keys.all { it in setOf("sort", "created_at\$gt", "limit") })
                val after = query["created_at\$gt"]?.let(::isoTime) ?: 0L
                val page = store.chronological(collection, after, limit(query))
                documents(page.documents, null)
            }
            "POST" -> { require(query.isEmpty()); create(parse(readBody())) }
            else -> rejected(405)
        }
        if (path.startsWith("$ROOT/history/")) {
            require(query.keys.all { it == "limit" })
            val after = time(path.removePrefix("$ROOT/history/"))
            if (method != "GET") return rejected(405)
            val page = store.history(collection, after, limit(query))
            return documents(page.documents, page.nextCursor)
        }
        require(query.isEmpty())
        val id = path.removePrefix("$ROOT/")
        require(ID.matches(id))
        return when (method) {
            "GET" -> store.get(collection, id)?.takeUnless { it.deleted }?.let {
                response(200, buildJsonObject { put("result", wire(it)) }, it.modifiedRevision)
            } ?: rejected(404)
            "PATCH" -> patch(id, parse(readBody()))
            "DELETE" -> db.withTransaction {
                val previous = store.get(collection, id) ?: return@withTransaction rejected(404)
                if (previous.source != SOURCE) return@withTransaction rejected(403)
                written(store.delete(collection, id, previous.modifiedRevision), wrapped = true)
            }
            else -> rejected(405)
        }
    }

    private suspend fun create(body: JsonObject): LocalV3HttpResult {
        val payload = format { validated(body) }
        val id = optionalString(body, "identifier") ?: stableIdentifier(payload)
        require(ID.matches(id))
        return db.withTransaction {
            val previous = store.get(collection, id)
            format { metadata(body, id, previous) }
            written(store.create(LocalV3DocumentInput(collection, id, SOURCE, HistoryJson.long(payload.getValue("date")),
                payload.toString(), readOnly(body))), wrapped = false, created = previous == null)
        }
    }

    private suspend fun patch(id: String, body: JsonObject): LocalV3HttpResult = db.withTransaction {
        val previous = store.get(collection, id) ?: return@withTransaction rejected(404)
        if (previous.source != SOURCE) return@withTransaction rejected(403)
        format { metadata(body, id, previous) }
        val old = parse(previous.payloadJson.toByteArray())
        // RC1 deliberately omits date/utcOffset from PATCH. Preserve rather than regenerate them.
        val payload = format { validated(JsonObject(old + body.filterKeys { it !in METADATA })) }
        require(IDENTITY.all { old[it] == payload[it] })
        val expected = optionalLong(body, "srvModified") ?: previous.modifiedRevision
        written(store.update(LocalV3DocumentInput(collection, id, previous.source, previous.eventAtMs,
            payload.toString(), if (body.containsKey("isReadOnly")) readOnly(body) else previous.readOnly), expected), wrapped = true)
    }

    private fun metadata(body: JsonObject, id: String, previous: LocalNightscoutV3DocumentEntity?) {
        require(body.keys.all { it in FIELDS || it in METADATA })
        optionalString(body, "identifier")?.let { require(it == id) }
        body["isValid"]?.takeUnless { it == JsonNull }?.let { require(HistoryJson.bool(it)) }
        optionalLong(body, "srvCreated")?.let { require(it == previous?.createdRevision) }
        optionalLong(body, "srvModified")?.let { require(previous != null && it in 1..previous.modifiedRevision) }
        listOf("subject", "modifiedBy").forEach { field ->
            optionalString(body, field)?.let { require(previous != null && it == SOURCE) }
        }
    }

    private fun validated(body: JsonObject, nested: Boolean = false): JsonObject {
        require(body.keys.all { it in FIELDS || it in METADATA })
        val payload = body.filter { (nested || it.key !in METADATA) && it.value != JsonNull }.toMutableMap()
        if (nested) {
            // Historical child metadata is scoped to that child, never server-owner authority.
            optionalString(body, "identifier")?.let { require(ID.matches(it)) }
            listOf("subject", "modifiedBy").forEach { key -> optionalString(body, key)?.let { require(it.length <= 256) } }
            listOf("srvCreated", "srvModified").forEach { key ->
                optionalLong(body, key)?.let { require(it in 0..LocalNightscoutV3Store.MAX_TIME) }
            }
            listOf("isValid" to true, "isReadOnly" to false).forEach { (key, default) ->
                payload[key] = JsonPrimitive(body[key]?.takeUnless { it == JsonNull }?.let(HistoryJson::bool) ?: default)
            }
        }
        val at = optionalLong(body, "date")
            ?: optionalString(body, "created_at")?.let(::isoTime)
        if (!nested) require(at != null && at in 1..LocalNightscoutV3Store.MAX_TIME)
        at?.let { timestamp ->
            require(timestamp in 1..LocalNightscoutV3Store.MAX_TIME)
            listOf("mills", "timestamp").forEach { field -> optionalLong(body, field)?.let { require(it == timestamp) } }
            optionalString(body, "created_at")?.let { require(isoTime(it) == timestamp) }
            payload.remove("mills"); payload.remove("timestamp")
            payload["date"] = JsonPrimitive(timestamp)
            payload["created_at"] = JsonPrimitive(Instant.ofEpochMilli(timestamp).toString())
        }
        val event = optionalString(body, "eventType") ?: throw IllegalArgumentException("missing_treatment_type")
        require(event in EVENTS)
        STRING_FIELDS.forEach { key -> optionalString(body, key)?.let {
            require(it.length <= if (key in setOf("profileJson", "bolusCalculatorResult")) 48_000 else 4096)
        } }
        optionalString(body, "app")?.let { require(it.isNotBlank() && it.length <= 128) }
        optionalString(body, "units")?.let {
            require(it in setOf("mg/dl", "mmol", "mmol/l"))
            // Native NsUnits recognizes "mmol"; an unknown alias silently defaults to mg/dl.
            if (it == "mmol/l") payload["units"] = JsonPrimitive("mmol")
        }
        if (number(body, "glucose") != null) require(optionalString(body, "units") != null)
        // The native reader supplies this default even for nonglycemic bolus/carb records.
        if ("units" !in payload) payload["units"] = JsonPrimitive("mg/dl")
        INTEGER_FIELDS.forEach { optionalLong(body, it) }
        INT_FIELDS.forEach { key -> optionalLong(body, key)?.let { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) } }
        optionalLong(body, "utcOffset")?.let { require(it in -1440..1440) }
        listOf("duration", "durationInMilliseconds", "originalDuration").forEach { key ->
            optionalLong(body, key)?.let { require(it >= 0) }
        }
        val duration = optionalLong(body, "durationInMilliseconds")
            ?: optionalLong(body, "duration")?.let { Math.multiplyExact(it, 60_000) }
        duration?.let { require(it in 0..31_536_000_000L) }
        if (body["duration"] != null && body["durationInMilliseconds"] != null &&
            body["duration"] != JsonNull && body["durationInMilliseconds"] != JsonNull) {
            require(optionalLong(body, "duration") == duration!! / 60_000)
        }
        NUMBER_FIELDS.forEach { number(body, it) }
        BOOLEAN_FIELDS.forEach { key -> body[key]?.takeUnless { it == JsonNull }?.let(HistoryJson::bool) }
        body["icfg"]?.takeUnless { it == JsonNull }?.let {
            val config = it as? JsonObject ?: throw IllegalArgumentException("invalid_insulin_context")
            require(config.keys == setOf("insulinLabel", "insulinEndTime", "insulinPeakTime", "concentration"))
            require(HistoryJson.string(config.getValue("insulinLabel")).length <= 256)
            val end = HistoryJson.long(config.getValue("insulinEndTime"))
            val peak = HistoryJson.long(config.getValue("insulinPeakTime"))
            require(end in 0..604_800_000 && peak in 0..end)
            require(HistoryJson.double(config.getValue("concentration")) in 0.0..1000.0)
        }
        body["extendedEmulated"]?.takeUnless { it == JsonNull }?.let {
            require(!nested)
            payload["extendedEmulated"] = validated(it as? JsonObject
                ?: throw IllegalArgumentException("invalid_extended_treatment"), nested = true)
        }
        // Mirror the native mapper's branches: otherwise it silently drops accepted records/fields.
        val insulin = number(body, "insulin")
        val carbs = number(body, "carbs")
        require(insulin == null || insulin >= 0.0)
        require(!(insulin != null && insulin > 0 && carbs != null && carbs != 0.0))
        when {
            insulin != null && insulin > 0 -> {
                optionalString(body, "type")?.let { require(it in setOf("NORMAL", "SMB", "PRIMING")) }
            }
            carbs != null && carbs != 0.0 -> Unit
            event == "Temporary Target" -> {
                require(duration != null)
                if (duration > 0) {
                    val bottom = number(body, "targetBottom")
                    val top = number(body, "targetTop")
                    require(bottom != null && top != null && bottom > 0 && bottom <= top)
                    require(optionalString(body, "units") != null)
                }
            }
            event == "Temp Basal" -> {
                require(duration != null && duration > 0)
                require(number(body, "absolute") != null || number(body, "percent") != null)
            }
            event == "Combo Bolus" -> { require(duration != null && number(body, "enteredinsulin") != null) }
            event == "Profile Switch" -> require(!optionalString(body, "profile").isNullOrBlank())
            event == "Bolus Wizard" -> require(!optionalString(body, "bolusCalculatorResult").isNullOrBlank())
            event in THERAPY_EVENTS -> Unit
            else -> throw IllegalArgumentException("unsupported_treatment_shape")
        }
        if (event == "Note" && optionalString(body, "originalProfileName") != null) {
            listOf("profileJson", "originalCustomizedName").forEach { require(optionalString(body, it) != null) }
            listOf("originalTimeshift", "originalPercentage", "originalDuration", "originalEnd").forEach { require(optionalLong(body, it) != null) }
        }
        return JsonObject(payload)
    }

    private fun stableIdentifier(body: JsonObject): String {
        require(!optionalString(body, "app").isNullOrBlank())
        // Quantity and mutable notes must not turn an edited/retried delivery into a second event.
        val identity = buildJsonObject {
            put("owner", SOURCE)
            IDENTITY.filterNot { it == "utcOffset" }.forEach { key -> put(key, body[key] ?: JsonNull) }
        }
        return MessageDigest.getInstance("SHA-256").digest(HistoryJson.bytes(identity))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun wire(row: LocalNightscoutV3DocumentEntity) = JsonObject(parse(row.payloadJson.toByteArray()) + buildJsonObject {
        put("identifier", row.identifier); put("srvCreated", row.createdRevision); put("srvModified", row.modifiedRevision)
        put("subject", row.source); put("modifiedBy", row.source); put("isReadOnly", row.readOnly); put("isValid", !row.deleted)
    })
    private fun documents(rows: List<LocalNightscoutV3DocumentEntity>, revision: Long?): LocalV3HttpResult {
        val body = buildJsonObject { put("result", JsonArray(rows.map(::wire))) }
        if (HistoryJson.bytes(body).size > 1024 * 1024) throw LocalV3StoreException(LocalV3StoreError.PAGE_CAPACITY)
        return response(200, body, revision)
    }
    private fun written(write: LocalV3Write, wrapped: Boolean, created: Boolean = false): LocalV3HttpResult {
        val result = buildJsonObject {
            put("identifier", write.document.identifier); put("lastModified", write.document.modifiedRevision)
            put("isDeduplication", !write.changed)
            if (!write.changed) put("deduplicatedIdentifier", write.document.identifier)
        }
        return response(if (created && write.changed) 201 else 200,
            if (wrapped) buildJsonObject { put("result", result) } else result, write.document.modifiedRevision)
    }
    private fun parse(bytes: ByteArray) = format { HistoryJson.parse(bytes, LocalNightscoutV3Store.MAX_PAYLOAD_BYTES) }
    private fun optionalString(body: JsonObject, key: String) = format { body[key]?.takeUnless { it == JsonNull }?.let(HistoryJson::string) }
    private fun optionalLong(body: JsonObject, key: String) = format {
        body[key]?.takeUnless { it == JsonNull }?.let {
            if (key in SIGNED_FIELDS) HistoryJson.signedLong(it) else HistoryJson.long(it)
        }
    }
    private fun number(body: JsonObject, key: String) = format { body[key]?.takeUnless { it == JsonNull }?.let(HistoryJson::double) }
    private fun readOnly(body: JsonObject) = format { body["isReadOnly"]?.takeUnless { it == JsonNull }?.let(HistoryJson::bool) ?: false }
    private inline fun <T> format(block: () -> T): T = try { block() } catch (_: Exception) {
        throw IllegalArgumentException("invalid_v3_treatment")
    }
    private fun isoTime(value: String) = format {
        val instant = Instant.parse(value)
        require(instant.nano % 1_000_000 == 0)
        instant.toEpochMilli().also { require(it in 0..LocalNightscoutV3Store.MAX_TIME) }
    }
    private fun time(value: String): Long {
        require(Regex("0|[1-9][0-9]{0,14}").matches(value))
        return value.toLong().also { require(it in 0..LocalNightscoutV3Store.MAX_TIME) }
    }
    private fun limit(query: Map<String, String>) = time(query["limit"] ?: "100").also { require(it in 1..1000) }.toInt()
    private fun response(status: Int, body: JsonObject, revision: Long? = null) = LocalV3HttpResult(status, body.toString(), revision)
    private fun rejected(status: Int) = response(status, buildJsonObject { put("status", status); put("message", "v3_request_rejected_$status") })

    companion object {
        private const val ROOT = "/api/v3/treatments"
        private const val SOURCE = "local-v3-owner"
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
        private val METADATA = setOf("identifier", "srvCreated", "srvModified", "subject", "modifiedBy", "isValid", "isReadOnly")
        private val IDENTITY = listOf("date", "utcOffset", "app", "device", "eventType", "pumpId", "pumpType", "pumpSerial")
        private val STRING_FIELDS = setOf("created_at", "app", "device", "eventType", "glucoseType", "units", "profile", "reason", "mode", "location", "arrow",
            "reasons", "notes", "enteredBy", "pumpType", "pumpSerial", "profileJson", "originalProfileName", "originalCustomizedName", "bolusCalculatorResult", "type")
        private val INTEGER_FIELDS = setOf("date", "mills", "timestamp", "utcOffset", "protein", "fat", "duration", "durationInMilliseconds", "preBolus", "splitNow", "splitExt",
            "endId", "pumpId", "originalTimeshift", "originalPercentage", "originalDuration", "originalEnd", "timeshift", "percentage")
        private val SIGNED_FIELDS = setOf("utcOffset", "originalTimeshift", "timeshift")
        private val NUMBER_FIELDS = setOf("glucose", "carbs", "insulin", "percent", "absolute", "targetTop", "targetBottom", "enteredinsulin", "relative", "rate")
        private val INT_FIELDS = setOf("protein", "fat", "preBolus", "splitNow", "splitExt", "originalPercentage", "percentage")
        private val BOOLEAN_FIELDS = setOf("autoForced", "isSMB", "isEmulatingTempBasal", "isAnnouncement", "isBasalInsulin")
        private val FIELDS = STRING_FIELDS + INTEGER_FIELDS + NUMBER_FIELDS + BOOLEAN_FIELDS + setOf("icfg", "extendedEmulated")
        private val THERAPY_EVENTS = setOf("Site Change", "Insulin Change", "Pump Battery Change", "Sensor Change", "Sensor Start", "Sensor Stop", "BG Check",
            "Exercise", "Announcement", "SettingsExport", "Question", "Note", "OpenAPS Offline", "D.A.D. Alert", "Mbg")
        private val EVENTS = THERAPY_EVENTS + setOf("Carb Correction", "Bolus Wizard", "Correction Bolus", "Meal Bolus", "Combo Bolus", "Temporary Target",
            "Profile Switch", "Snack Bolus", "Temp Basal")
    }
}
