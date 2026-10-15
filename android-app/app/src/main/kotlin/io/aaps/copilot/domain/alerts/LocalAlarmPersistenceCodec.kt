package io.aaps.copilot.domain.alerts

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

enum class LocalAlarmNotificationResult { NOT_REQUESTED, POSTED, BLOCKED, FAILED }
enum class LocalAlarmAudioResult { NOT_REQUESTED, STARTED, FAILED, UNAVAILABLE }
enum class LocalAlarmVibrationResult { NOT_REQUESTED, REQUESTED, FAILED, UNAVAILABLE }

data class LocalAlarmStepResult(
    val index: Int,
    val atElapsedMs: Long,
    val notification: LocalAlarmNotificationResult,
    val audio: LocalAlarmAudioResult,
    val vibration: LocalAlarmVibrationResult,
    val reachedPercent: Int?
)

data class LocalAlarmCycleResult(val steps: List<LocalAlarmStepResult> = emptyList())

object LocalAlarmPersistenceCodec {
    private const val STATE_BYTES = 4096
    private const val RESULT_BYTES = 2048
    private enum class Type { STRING, LONG, BOOLEAN, NULLABLE_STRING, NULLABLE_LONG }
    private val stateFields = mapOf(
        "schemaVersion" to Type.LONG, "sourceKind" to Type.STRING, "sourceId" to Type.STRING,
        "generation" to Type.LONG, "level" to Type.STRING, "bootCount" to Type.LONG,
        "observedElapsedMs" to Type.LONG, "validUntilElapsedMs" to Type.LONG, "ordinal" to Type.LONG,
        "cycleActive" to Type.BOOLEAN, "nextDueElapsedMs" to Type.NULLABLE_LONG, "reachedPercent" to Type.LONG,
        "lastCycleLevel" to Type.NULLABLE_STRING, "lastCycleStartedElapsedMs" to Type.NULLABLE_LONG,
        "lastCycleDeadlineElapsedMs" to Type.NULLABLE_LONG, "pauseLevel" to Type.NULLABLE_STRING,
        "pauseStartedWallMs" to Type.NULLABLE_LONG, "pauseUntilWallMs" to Type.NULLABLE_LONG
    )
    private val stepFields = mapOf("index" to Type.LONG, "atElapsedMs" to Type.LONG,
        "notification" to Type.STRING, "audio" to Type.STRING, "vibration" to Type.STRING,
        "reachedPercent" to Type.NULLABLE_LONG)

    fun encodeState(state: LocalAlarmState): String? {
        if (!LocalAlarmPolicy.validStateShape(state)) return null
        return bounded(flat(mapOf(
            "schemaVersion" to 1, "sourceKind" to state.key.kind.name, "sourceId" to state.key.id,
            "generation" to state.generation, "level" to state.level.name, "bootCount" to state.bootCount,
            "observedElapsedMs" to state.observedElapsedMs, "validUntilElapsedMs" to state.validUntilElapsedMs,
            "ordinal" to state.ordinal, "cycleActive" to state.cycleActive, "nextDueElapsedMs" to state.nextDueElapsedMs,
            "reachedPercent" to state.reachedPercent, "lastCycleLevel" to state.lastCycle?.level?.name,
            "lastCycleStartedElapsedMs" to state.lastCycle?.startedElapsedMs,
            "lastCycleDeadlineElapsedMs" to state.lastCycle?.deadlineElapsedMs,
            "pauseLevel" to state.pause?.level?.name, "pauseStartedWallMs" to state.pause?.startedWallMs,
            "pauseUntilWallMs" to state.pause?.untilWallMs
        )).toString(), STATE_BYTES)
    }

    fun decodeState(json: String): LocalAlarmState? = read(json, STATE_BYTES) { reader ->
        val v = readFlat(reader, stateFields)
        require(v.long("schemaVersion") == 1L)
        val key = LocalAlarmKey(LocalAlarmSourceKind.valueOf(v.string("sourceKind")), v.string("sourceId"))
        val generation = v.long("generation")
        val ordinal = v.long("ordinal")
        val boot = v.int("bootCount")
        val cycleFields = listOf("lastCycleLevel", "lastCycleStartedElapsedMs", "lastCycleDeadlineElapsedMs")
        val cycle = if (cycleFields.all { v[it] == null }) null else {
            require(cycleFields.all { v[it] != null })
            LocalAlarmCycle(key, generation, ordinal, LocalAlarmLevel.valueOf(v.string("lastCycleLevel")),
                boot, v.long("lastCycleStartedElapsedMs"), v.long("lastCycleDeadlineElapsedMs"))
        }
        val pauseFields = listOf("pauseLevel", "pauseStartedWallMs", "pauseUntilWallMs")
        val pause = if (pauseFields.all { v[it] == null }) null else {
            require(pauseFields.all { v[it] != null })
            LocalAlarmPause(v.long("pauseStartedWallMs"), v.long("pauseUntilWallMs"), LocalAlarmLevel.valueOf(v.string("pauseLevel")))
        }
        LocalAlarmState(key, generation, LocalAlarmLevel.valueOf(v.string("level")), boot,
            v.long("observedElapsedMs"), v.long("validUntilElapsedMs"), ordinal, cycle,
            v["cycleActive"] as Boolean, v["nextDueElapsedMs"] as Long?, v.int("reachedPercent"), pause)
            .also { require(LocalAlarmPolicy.validStateShape(it)) }
    }

    fun encodeResult(result: LocalAlarmCycleResult): String? {
        if (!validResult(result)) return null
        val steps = JsonArray()
        result.steps.forEach { step -> steps.add(flat(mapOf("index" to step.index,
            "atElapsedMs" to step.atElapsedMs, "notification" to step.notification.name,
            "audio" to step.audio.name, "vibration" to step.vibration.name, "reachedPercent" to step.reachedPercent))) }
        return bounded(JsonObject().apply { addProperty("schemaVersion", 1); add("steps", steps) }.toString(), RESULT_BYTES)
    }

    fun decodeResult(json: String): LocalAlarmCycleResult? = read(json, RESULT_BYTES) { reader ->
        val seen = mutableSetOf<String>()
        var steps: List<LocalAlarmStepResult>? = null
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(seen.add(name))
            when (name) {
                "schemaVersion" -> require(readLong(reader) == 1L)
                "steps" -> {
                    val items = mutableListOf<LocalAlarmStepResult>()
                    reader.beginArray()
                    while (reader.hasNext()) {
                        require(items.size < 4)
                        val v = readFlat(reader, stepFields)
                        items += LocalAlarmStepResult(v.int("index"), v.long("atElapsedMs"),
                            LocalAlarmNotificationResult.valueOf(v.string("notification")),
                            LocalAlarmAudioResult.valueOf(v.string("audio")),
                            LocalAlarmVibrationResult.valueOf(v.string("vibration")),
                            v["reachedPercent"]?.let { v.int("reachedPercent") })
                    }
                    reader.endArray()
                    steps = items
                }
                else -> error("unknown result field")
            }
        }
        reader.endObject()
        require(seen == setOf("schemaVersion", "steps"))
        LocalAlarmCycleResult(requireNotNull(steps)).also { require(validResult(it)) }
    }

    private fun validResult(result: LocalAlarmCycleResult): Boolean = result.steps.size <= 4 &&
        result.steps.all { it.index in 0..3 && it.atElapsedMs >= 0 && (it.reachedPercent == null || it.reachedPercent in 1..100) } &&
        result.steps.zipWithNext().all { (a, b) -> a.index < b.index && a.atElapsedMs <= b.atElapsedMs }

    // Fixed primitive schemas reject unknown nesting before traversing it.
    private fun readFlat(reader: JsonReader, fields: Map<String, Type>): Map<String, Any?> {
        val values = mutableMapOf<String, Any?>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(!values.containsKey(name))
            val type = requireNotNull(fields[name])
            values[name] = if (reader.peek() == JsonToken.NULL) {
                require(type == Type.NULLABLE_STRING || type == Type.NULLABLE_LONG)
                reader.nextNull(); null
            } else when (type) {
                Type.STRING, Type.NULLABLE_STRING -> { require(reader.peek() == JsonToken.STRING); reader.nextString() }
                Type.LONG, Type.NULLABLE_LONG -> readLong(reader)
                Type.BOOLEAN -> { require(reader.peek() == JsonToken.BOOLEAN); reader.nextBoolean() }
            }
        }
        reader.endObject()
        require(values.keys == fields.keys)
        return values
    }

    private fun readLong(reader: JsonReader): Long {
        require(reader.peek() == JsonToken.NUMBER)
        return requireNotNull(reader.nextString().toLongOrNull())
    }

    private fun Map<String, Any?>.string(name: String) = this[name] as String
    private fun Map<String, Any?>.long(name: String) = this[name] as Long
    private fun Map<String, Any?>.int(name: String): Int = long(name).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()

    private fun flat(values: Map<String, Any?>) = JsonObject().apply {
        values.forEach { (key, value) -> when (value) {
            null -> add(key, JsonNull.INSTANCE)
            is String -> addProperty(key, value)
            is Number -> addProperty(key, value)
            is Boolean -> addProperty(key, value)
            else -> error("unsupported primitive")
        } }
    }

    private fun bounded(json: String, limit: Int): String? =
        json.takeIf { it.length <= limit && it.toByteArray(Charsets.UTF_8).size <= limit }

    private fun <T> read(json: String, limit: Int, decode: (JsonReader) -> T): T? {
        if (bounded(json, limit) == null) return null
        return try {
            JsonReader(StringReader(json)).use { reader ->
                reader.strictness = Strictness.STRICT
                decode(reader).also { require(reader.peek() == JsonToken.END_DOCUMENT) }
            }
        } catch (_: IllegalArgumentException) { null
        } catch (_: IllegalStateException) { null
        } catch (_: java.io.IOException) { null }
    }
}
