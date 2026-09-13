package io.aaps.copilot.service

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.Writer

internal object LocalNightscoutInitialSnapshot {
    const val MAX_RETAINED_BYTES = 1_000_000
    private const val MAX_ROWS = 2_400
    private const val MAX_PACKETS = 64

    fun packets(gson: Gson, source: JsonObject, packetByteLimit: Int): List<String>? = try {
        encode(gson, source, packetByteLimit)
    } catch (_: SnapshotLimitExceeded) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun encode(gson: Gson, source: JsonObject, limit: Int): List<String> {
        if (limit <= 0) throw SnapshotLimitExceeded()
        val rows = ArrayList<Row>()
        for (key in listOf("treatments", "sgvs")) {
            val values = source.getAsJsonArray(key) ?: continue
            if (values.size() > MAX_ROWS - rows.size) throw SnapshotLimitExceeded()
            values.forEach { value ->
                rows += Row(key, value, value.asJsonObject["date"].asLong)
            }
        }
        // Global chronological order preserves the client's resume high-water between packets.
        rows.sortBy(Row::timestamp)
        val result = ArrayList<String>()
        var retainedBytes = 0
        var first = true
        fun newPayload() = JsonObject().apply {
            if (first && source.has("status")) add("status", source["status"])
            else addProperty("delta", true)
            add("treatments", JsonArray())
            add("sgvs", JsonArray())
        }
        fun packet(payload: JsonObject): String {
            val event = JsonArray().apply { add("dataUpdate"); add(payload) }
            return "42" + render(gson, event, limit - 2)
        }
        var payload = newPayload()
        var bytes = packet(payload).utf8Size()
        var rowCount = 0
        fun flush() {
            val encoded = packet(payload)
            val actualBytes = encoded.utf8Size()
            if (actualBytes > limit || result.size == MAX_PACKETS ||
                actualBytes > MAX_RETAINED_BYTES - retainedBytes
            ) throw SnapshotLimitExceeded()
            result += encoded
            retainedBytes += actualBytes
            first = false
            payload = newPayload()
            bytes = packet(payload).utf8Size()
            rowCount = 0
        }
        for (row in rows) {
            val rowBytes = render(gson, row.value, limit).utf8Size()
            var array = payload.getAsJsonArray(row.key)
            var extra = rowBytes + if (array.size() == 0) 0 else 1
            if (extra > limit - bytes && (rowCount > 0 || first)) {
                flush()
                array = payload.getAsJsonArray(row.key)
                extra = rowBytes
            }
            if (extra > limit - bytes) throw SnapshotLimitExceeded()
            array.add(row.value)
            bytes += extra
            rowCount++
        }
        if (rowCount > 0 || first) flush()
        return result
    }

    private fun render(gson: Gson, value: JsonElement, limit: Int): String {
        val writer = LimitedWriter(limit)
        gson.toJson(value, writer)
        return writer.result()
    }

    private fun String.utf8Size() = toByteArray(Charsets.UTF_8).size
    private data class Row(val key: String, val value: JsonElement, val timestamp: Long)
    private class SnapshotLimitExceeded : RuntimeException()

    // UTF-16 length bounds the intermediate buffer; the exact UTF-8 cap is checked before retention.
    private class LimitedWriter(private val limit: Int) : Writer() {
        private val buffer = StringBuilder()
        override fun write(chars: CharArray, offset: Int, length: Int) {
            if (length > limit - buffer.length) throw SnapshotLimitExceeded()
            buffer.append(chars, offset, length)
        }
        override fun write(value: String, offset: Int, length: Int) {
            if (length > limit - buffer.length) throw SnapshotLimitExceeded()
            buffer.append(value, offset, offset + length)
        }
        override fun flush() = Unit
        override fun close() = Unit
        fun result(): String = buffer.toString()
    }
}
