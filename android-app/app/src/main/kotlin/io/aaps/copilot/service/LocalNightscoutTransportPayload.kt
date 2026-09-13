package io.aaps.copilot.service

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.aaps.copilot.data.repository.normalizeClinicalKey
import java.io.StringReader

internal class LocalNightscoutTransportPayload(private val values: Map<String, String>) {
    operator fun get(key: String): String? = values[normalizeClinicalKey(key)]
}

/** Transport must preserve diagnostic strings, not reuse fail-closed clinical interpretation. */
internal fun readLocalNightscoutTransportPayload(raw: String): LocalNightscoutTransportPayload {
    require(raw.length <= 16 * 1024 && raw.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
    return JsonReader(StringReader(raw)).use { reader ->
        reader.strictness = Strictness.STRICT
        var tokens = 0
        fun token() { require(++tokens <= 160) }
        fun skip(depth: Int) {
            token()
            require(depth <= 4)
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    val names = mutableSetOf<String>()
                    while (reader.hasNext()) {
                        token()
                        val name = reader.nextName()
                        require(name.length <= 80 && names.add(name))
                        skip(depth + 1)
                    }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    while (reader.hasNext()) skip(depth + 1)
                    reader.endArray()
                }
                JsonToken.STRING, JsonToken.NUMBER, JsonToken.BOOLEAN, JsonToken.NULL -> reader.skipValue()
                else -> error("Invalid treatment payload")
            }
        }
        reader.beginObject()
        val values = linkedMapOf<String, String>()
        val names = mutableSetOf<String>()
        while (reader.hasNext()) {
            token()
            val rawName = reader.nextName()
            val name = normalizeClinicalKey(rawName)
            require(rawName.length <= 80 && names.add(name))
            if (name == "isvalid") {
                require(reader.peek() == JsonToken.STRING || reader.peek() == JsonToken.BOOLEAN ||
                    reader.peek() == JsonToken.NUMBER)
            }
            when (reader.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> values[name] = reader.nextString()
                JsonToken.BOOLEAN -> values[name] = reader.nextBoolean().toString()
                JsonToken.NULL -> reader.nextNull()
                else -> skip(1)
            }
        }
        reader.endObject()
        require(reader.peek() == JsonToken.END_DOCUMENT)
        LocalNightscoutTransportPayload(values)
    }
}
