package io.aaps.copilot.data.repository

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.json.*

/** Strict UTF-8, duplicate rejection, closed objects, and bounded nesting on every response/checkpoint. */
internal object HistoryJson {
    const val retentionMs = 365L * 86_400_000
    const val maxTime = Long.MAX_VALUE - retentionMs
    val code = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")
    private val decimal = Regex("0|[1-9][0-9]*")
    private val signedDecimal = Regex("0|-?[1-9][0-9]*")

    fun uuid(value: String) { check(UUID.fromString(value).toString() == value) }
    fun time(value: Long) { check(value in 1..maxTime) }
    fun encodeUtf8(value: String): ByteArray {
        val buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        return ByteArray(buffer.remaining()).also(buffer::get)
    }
    fun decodeUtf8(value: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(value)).toString()

    fun bytes(value: JsonElement): ByteArray = encodeUtf8(canonical(value).toString())
    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }

    fun parse(body: ByteArray, limit: Int): JsonObject {
        check(body.size in 1..limit)
        val decoded = decodeUtf8(body)
        check(decoded.firstOrNull() != '\uFEFF')
        val reader = JsonReader(StringReader(decoded)).apply { strictness = Strictness.STRICT }
        return reader.use {
            val result = read(reader, 0) as? JsonObject ?: error("invalid_history_json")
            check(reader.peek() == JsonToken.END_DOCUMENT)
            result
        }
    }

    private fun read(reader: JsonReader, depth: Int): JsonElement {
        check(depth <= 16)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val fields = linkedMapOf<String, JsonElement>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    check(!fields.containsKey(name))
                    encodeUtf8(name)
                    fields[name] = read(reader, depth + 1)
                }
                reader.endObject(); JsonObject(fields)
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                val fields = mutableListOf<JsonElement>()
                while (reader.hasNext()) fields += read(reader, depth + 1)
                reader.endArray(); JsonArray(fields)
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString().also { encodeUtf8(it) })
            JsonToken.NUMBER -> Json.parseToJsonElement(reader.nextString().also { check(it.length <= 64) })
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull }
            else -> error("invalid_history_json")
        }
    }

    fun fields(value: JsonObject, required: Set<String>, optional: Set<String> = emptySet()) {
        check(value.keys.containsAll(required) && (value.keys - required - optional).isEmpty())
    }
    fun obj(value: JsonElement): JsonObject = value as? JsonObject ?: error("invalid_history_object")
    fun array(value: JsonElement): JsonArray = value as? JsonArray ?: error("invalid_history_array")
    fun string(value: JsonElement): String {
        val primitive = value as? JsonPrimitive ?: error("invalid_history_string")
        check(primitive.isString)
        return primitive.content
    }
    fun long(value: JsonElement): Long {
        val primitive = value as? JsonPrimitive ?: error("invalid_history_number")
        check(!primitive.isString && decimal.matches(primitive.content))
        return primitive.content.toLong()
    }
    fun signedLong(value: JsonElement): Long {
        val primitive = value as? JsonPrimitive ?: error("invalid_history_number")
        check(!primitive.isString && signedDecimal.matches(primitive.content))
        return primitive.content.toLong()
    }
    fun bool(value: JsonElement): Boolean {
        val primitive = value as? JsonPrimitive ?: error("invalid_history_boolean")
        check(!primitive.isString && primitive.content in setOf("true", "false"))
        return primitive.boolean
    }
    fun double(value: JsonElement): Double {
        val primitive = value as? JsonPrimitive ?: error("invalid_history_number")
        check(!primitive.isString)
        return primitive.content.toDouble().also { check(it.isFinite()) }
    }
}
