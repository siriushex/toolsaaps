package io.aaps.copilot.data.repository

import kotlinx.serialization.json.*

/** Every nested object is closed; numeric strings, coerced booleans and unknown enums fail. */
internal object HistoryCodec {
    private fun JsonObject.s(key: String) = HistoryJson.string(getValue(key))
    private fun JsonObject.n(key: String) = HistoryJson.long(getValue(key))
    private fun JsonObject.d(key: String): Double? = get(key)?.takeUnless { it == JsonNull }?.let(HistoryJson::double)
    private fun JsonObject.ns(key: String): String? = get(key)?.takeUnless { it == JsonNull }?.let(HistoryJson::string)
    private fun JsonObject.nt(key: String): Long? = getValue(key).takeUnless { it == JsonNull }?.let(HistoryJson::long)
    private inline fun <reified T : Enum<T>> JsonObject.e(key: String): T = enumValueOf(s(key))
    private inline fun <reified T : Enum<T>> JsonObject.ne(key: String): T? = ns(key)?.let { enumValueOf<T>(it) }
    private fun JsonObject.closed(vararg required: String, optional: Set<String> = emptySet()) =
        HistoryJson.fields(this, required.toSet(), optional)

    fun record(value: JsonElement, now: Long): ServerAiHistoryRecord {
        val obj = HistoryJson.obj(value)
        obj.closed("recordId", "revision", "eventAtMs", "observedAtMs", "kind", "source", "quality", "deleted", "data",
            optional = setOf("context"))
        val kind = obj.e<ServerAiHistoryKind>("kind")
        val revision = obj.n("revision").also { check(it in 1..Int.MAX_VALUE.toLong()) }.toInt()
        val data = obj.getValue("data").takeUnless { it == JsonNull }?.let { payload(kind, HistoryJson.obj(it)) }
        val context = obj["context"]?.takeUnless { it == JsonNull }?.let { element ->
            val c = HistoryJson.obj(element)
            c.closed(optional = setOf("zoneId", "sensorSessionId", "calibrationModelId", "glucoseBasis", "inputModelVersion"))
            ServerAiHistoricalContext(c.ns("zoneId"), c.ns("sensorSessionId"), c.ns("calibrationModelId"),
                c.ne<ServerAiGlucoseBasis>("glucoseBasis"), c.ns("inputModelVersion"))
        }
        return ServerAiHistoryRecord(obj.s("recordId"), revision, obj.n("eventAtMs"), obj.n("observedAtMs"), kind,
            obj.e("source"), obj.e("quality"), HistoryJson.bool(obj.getValue("deleted")), data, context)
            .also { it.toWire(now) }
    }

    private fun payload(kind: ServerAiHistoryKind, d: JsonObject): ServerAiHistoryData = when (kind) {
        ServerAiHistoryKind.GLUCOSE -> {
            d.closed("glucoseMmol"); ServerAiHistoryData.Glucose(HistoryJson.double(d.getValue("glucoseMmol")))
        }
        ServerAiHistoryKind.THERAPY -> {
            d.closed("eventType", optional = setOf("carbsGrams", "insulinUnits", "basalUnitsPerHour", "targetMmol", "durationMinutes", "deliveryState"))
            check(d.values.none { it == JsonNull })
            ServerAiHistoryData.Therapy(d.e("eventType"), d.d("carbsGrams"), d.d("insulinUnits"), d.d("basalUnitsPerHour"),
                d.d("targetMmol"), d.d("durationMinutes"), d.ne<ServerAiDeliveryState>("deliveryState"))
        }
        ServerAiHistoryKind.MEAL -> {
            d.closed("carbsGrams", "foodType", "quantitySource", optional = setOf("glycemicIndex", "giSource"))
            ServerAiHistoryData.Meal(HistoryJson.double(d.getValue("carbsGrams")), d.e("foodType"), d.e("quantitySource"),
                d.d("glycemicIndex"), d.ne<ServerAiGiSource>("giSource"))
        }
        ServerAiHistoryKind.FORECAST -> {
            d.closed("horizonMinutes", "valueMmol", "modelVersion", optional = setOf("ciLowMmol", "ciHighMmol"))
            val horizon = d.n("horizonMinutes").also { check(it in setOf(5L, 30L, 60L)) }.toInt()
            ServerAiHistoryData.Forecast(horizon, HistoryJson.double(d.getValue("valueMmol")), d.s("modelVersion"), d.d("ciLowMmol"), d.d("ciHighMmol"))
        }
        ServerAiHistoryKind.SENSITIVITY -> {
            d.closed(optional = setOf("isfMmolPerUnit", "crGramsPerUnit", "positiveIobUnits", "netIobUnits", "cobGrams"))
            ServerAiHistoryData.Sensitivity(d.d("isfMmolPerUnit"), d.d("crGramsPerUnit"), d.d("positiveIobUnits"), d.d("netIobUnits"), d.d("cobGrams"))
        }
        ServerAiHistoryKind.ACTIVITY -> {
            d.closed(optional = setOf("steps", "activeMinutes", "activeKcal"))
            val steps = d["steps"]?.takeUnless { it == JsonNull }?.let { HistoryJson.long(it).also { n -> check(n <= 1_000_000) }.toInt() }
            ServerAiHistoryData.Activity(steps, d.d("activeMinutes"), d.d("activeKcal"))
        }
        ServerAiHistoryKind.DEVICE -> {
            d.closed(optional = setOf("sensorAgeHours", "setAgeHours", "deliveryState"))
            ServerAiHistoryData.Device(d.d("sensorAgeHours"), d.d("setAgeHours"), d.ne<ServerAiDeliveryState>("deliveryState"))
        }
        ServerAiHistoryKind.ALERT -> {
            d.closed("code", "severity", "deliveryStatus"); ServerAiHistoryData.Alert(d.s("code"), d.e("severity"), d.e("deliveryStatus"))
        }
        ServerAiHistoryKind.DIAGNOSTIC -> {
            d.closed("code", "status"); ServerAiHistoryData.Diagnostic(d.s("code"), d.e("status"))
        }
        ServerAiHistoryKind.CHAT -> {
            d.closed("conversationId", "role", "text", "origin"); check(d.s("origin") == "DEVICE_ARCHIVE")
            ServerAiHistoryData.Chat(d.s("conversationId"), d.e("role"), d.s("text"))
        }
    }.also { it.toWire() }

    fun receipt(value: JsonObject, batchId: String, count: Int, floor: Long): ServerAiHistoryReceipt {
        value.closed("batchId", "sequence", "changedRecords")
        check(value.s("batchId") == batchId)
        HistoryJson.uuid(batchId)
        val changed = value.n("changedRecords").also { check(it <= count) }.toInt()
        val sequence = value.n("sequence")
        check(sequence >= floor && sequence - floor >= changed)
        return ServerAiHistoryReceipt(batchId, sequence, changed)
    }

    fun query(query: ServerAiHistoryQuery, now: Long): JsonObject {
        HistoryJson.time(now); HistoryJson.time(query.fromMs); HistoryJson.time(query.untilMs)
        check(query.fromMs >= maxOf(1, now - HistoryJson.retentionMs) && query.fromMs < query.untilMs && query.untilMs <= now + 1)
        check(query.kinds.size <= 10 && query.kinds.distinct().size == query.kinds.size)
        check(query.afterSequence >= 0 && (query.snapshotSequence == null || query.snapshotSequence >= query.afterSequence))
        check(query.limit in 1..32)
        return buildJsonObject {
            put("fromMs", query.fromMs); put("untilMs", query.untilMs)
            put("kinds", JsonArray(query.kinds.map { JsonPrimitive(it.name) }))
            put("afterSequence", query.afterSequence); put("snapshotSequence", query.snapshotSequence?.let(::JsonPrimitive) ?: JsonNull)
            put("limit", query.limit)
        }
    }

    fun page(value: JsonObject, query: ServerAiHistoryQuery, now: Long): ServerAiHistoryPage {
        value.closed("snapshotSequence", "nextAfterSequence", "hasMore", "provenance", "records")
        check(value.s("provenance") == "DEVICE_DECLARED")
        val snapshot = value.n("snapshotSequence")
        check(snapshot >= query.afterSequence && (query.snapshotSequence == null || snapshot == query.snapshotSequence))
        val next = value.n("nextAfterSequence")
        val more = HistoryJson.bool(value.getValue("hasMore"))
        val rows = HistoryJson.array(value.getValue("records"))
        check(rows.size <= query.limit)
        var prior = query.afterSequence
        val ids = mutableSetOf<String>()
        val records = rows.map { row ->
            val r = HistoryJson.obj(row); r.closed("sequence", "receivedAtMs", "record")
            val sequence = r.n("sequence"); check(sequence > prior && sequence <= snapshot); prior = sequence
            val received = r.n("receivedAtMs"); HistoryJson.time(received)
            val raw = HistoryJson.obj(r.getValue("record"))
            val observed = raw.n("observedAtMs")
            // Use the server receipt for ordering; tolerate only the existing
            // signed-proof clock window on server timestamps. The requested event
            // window still bounds retention, independently of arrival time.
            check(received >= observed && received <= now + 60_000)
            val record = record(raw, observed)
            check(ids.add(record.recordId) && record.eventAtMs >= query.fromMs && record.eventAtMs < query.untilMs)
            check(query.kinds.isEmpty() || record.kind in query.kinds)
            ServerAiHistoryEntry(sequence, received, record)
        }
        check(if (more) records.isNotEmpty() && next == prior && next < snapshot else next == snapshot)
        return ServerAiHistoryPage(snapshot, next, more, records)
    }

    fun conversationQuery(query: ServerAiConversationQuery, now: Long): JsonObject {
        HistoryJson.time(now); HistoryJson.time(query.fromMs); HistoryJson.time(query.untilMs)
        val grace = if (query.cursor == null) 0L else 900_000L
        check(query.fromMs >= maxOf(1, now - HistoryJson.retentionMs - grace) && query.untilMs <= now + 1)
        check(query.untilMs > query.fromMs && query.untilMs - query.fromMs <= HistoryJson.retentionMs + 1)
        check(query.limit in 1..20)
        query.cursor?.let(::cursor)
        return buildJsonObject {
            put("fromMs", query.fromMs); put("untilMs", query.untilMs)
            put("cursor", query.cursor?.let(::JsonPrimitive) ?: JsonNull); put("limit", query.limit)
        }
    }

    private fun cursor(value: String) { check(value.length in 40..1024 && Regex("[A-Za-z0-9_-]+={0,2}").matches(value)) }
    private fun boundedText(value: String, chars: Int, bytes: Int) {
        check(value.codePointCount(0, value.length) in 1..chars && HistoryJson.encodeUtf8(value).size <= bytes)
    }

    fun conversationPage(value: JsonObject, query: ServerAiConversationQuery, now: Long): ServerAiConversationPage {
        value.closed("revision", "origin", "generatedAtMs", "snapshotScope", "records", "hasMore", "nextCursor")
        check(value.s("revision") == "server-chat-archive-v1" && value.s("origin") == "SERVER_ARCHIVE" && value.s("snapshotScope") == "MEMBERSHIP_ONLY")
        val generated = value.n("generatedAtMs"); HistoryJson.time(generated); check(generated <= now + 60_000)
        val rows = HistoryJson.array(value.getValue("records")); check(rows.size <= query.limit)
        val jobs = mutableSetOf<String>(); val requests = mutableSetOf<String>()
        val entries = rows.map { item ->
            val r = HistoryJson.obj(item)
            r.closed("jobId", "requestId", "acceptedAtMs", "deadlineMs", "startedAtMs", "finishedAtMs", "state",
                "question", "answer", "model", "promptRevision", "updatedAtMs")
            val job = r.s("jobId"); val request = r.s("requestId"); HistoryJson.uuid(job); HistoryJson.uuid(request)
            check(jobs.add(job) && requests.add(request))
            val accepted = r.n("acceptedAtMs"); val deadline = r.n("deadlineMs"); val updated = r.n("updatedAtMs")
            listOf(accepted, deadline, updated).forEach(HistoryJson::time)
            check(accepted >= query.fromMs && accepted < query.untilMs && accepted > generated - HistoryJson.retentionMs)
            check(deadline > accepted && deadline - accepted <= 900_000 && updated >= accepted && updated <= generated)
            val started = r.nt("startedAtMs"); val finished = r.nt("finishedAtMs")
            started?.let { HistoryJson.time(it); check(it >= accepted && it <= updated) }
            finished?.let { HistoryJson.time(it); check(it >= (started ?: accepted) && it <= updated) }
            val state = r.e<ServerAiConversationState>("state")
            val answer = r.ns("answer"); val question = r.s("question")
            boundedText(question, 4096, 8192)
            if (state == ServerAiConversationState.OUTCOME_NOT_RECORDED) {
                check(answer == null && started == null && finished == null)
            } else {
                check(finished != null)
                if (state == ServerAiConversationState.SUCCEEDED) {
                    check(started != null && answer != null && finished < deadline)
                    boundedText(answer, 8192, 16384)
                } else check(answer == null)
            }
            val model = r.s("model"); val revision = r.s("promptRevision")
            check(model.isNotEmpty() && revision.isNotEmpty())
            ServerAiConversationEntry(job, request, accepted, deadline, started, finished, state, question, answer, model, revision, updated)
        }
        val more = HistoryJson.bool(value.getValue("hasMore")); val next = value.ns("nextCursor")
        check(if (more) entries.isNotEmpty() && next != null && next != query.cursor else next == null)
        next?.let(::cursor)
        return ServerAiConversationPage(generated, entries, more, next)
    }
}
