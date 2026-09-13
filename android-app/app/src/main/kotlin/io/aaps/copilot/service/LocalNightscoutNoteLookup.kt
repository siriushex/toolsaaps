package io.aaps.copilot.service

import androidx.sqlite.db.SimpleSQLiteQuery
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.Callable

/** Exact-note queries must filter before count; an incomplete scan is never absence evidence. */
internal class LocalNightscoutNoteLookup(
    private val db: CopilotDatabase,
    private val maxRows: Int = 100_000,
    private val maxBytes: Long = 64L * 1024 * 1024,
    private val nanoTime: () -> Long = System::nanoTime
) {
    fun find(note: String, since: Long, count: Int): List<TherapyEventEntity> {
        require(note.length in 1..512 && count in 1..5_000 && maxRows in 1..100_000 && maxBytes > 0)
        return db.runInTransaction(Callable {
            val result = mutableListOf<TherapyEventEntity>()
            var rows = 0
            var bytes = 0L
            val startedAt = nanoTime()
            db.query(SimpleSQLiteQuery(
                "SELECT id,timestamp,type,payloadJson FROM therapy_events WHERE timestamp >= ? " +
                    "ORDER BY timestamp DESC,id DESC LIMIT ?",
                arrayOf<Any>(since, maxRows + 1)
            )).use { cursor ->
                while (cursor.moveToNext()) {
                    if (++rows > maxRows || nanoTime() - startedAt > 3_000_000_000L) {
                        throw IOException("Treatment note lookup incomplete")
                    }
                    val payload = cursor.getString(3)
                    if (payload.length > 64 * 1024) throw IOException("Treatment payload exceeds lookup budget")
                    bytes += payload.toByteArray(Charsets.UTF_8).size
                    if (bytes > maxBytes) throw IOException("Treatment note lookup incomplete")
                    if (exactNote(payload) == note) {
                        result += TherapyEventEntity(cursor.getString(0), cursor.getLong(1), cursor.getString(2), payload)
                        if (result.size == count) break
                    }
                }
            }
            if (nanoTime() - startedAt > 3_000_000_000L) throw IOException("Treatment note lookup incomplete")
            result
        })
    }

    private fun exactNote(payload: String): String? = JsonReader(StringReader(payload)).use { reader ->
        reader.strictness = Strictness.STRICT
        reader.beginObject()
        var note: String? = null
        var seen = false
        while (reader.hasNext()) {
            if (reader.nextName() == "notes") {
                if (seen) throw IOException("Ambiguous treatment notes")
                seen = true
                when (reader.peek()) {
                    JsonToken.NULL -> reader.nextNull()
                    JsonToken.STRING -> note = reader.nextString()
                    else -> throw IOException("Invalid treatment notes")
                }
            } else reader.skipValue()
        }
        reader.endObject()
        if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Invalid treatment payload")
        note
    }
}
