package io.aaps.copilot.data.repository

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.target.TargetDecisionOutcome
import io.aaps.copilot.domain.target.TargetManagerMode
import java.io.StringReader

internal data class TargetManagerLiveStatus(
    val schemaVersion: Int,
    val timestamp: Long,
    val mode: String,
    val priorityEnabled: Boolean,
    val policyRevision: Long,
    val currentTargetMmol: Double?,
    val proposedTargetMmol: Double?,
    val outcome: String,
    val reason: String
)

internal object TargetManagerLiveStatusCodec {
    const val SOURCE = "copilot_target_manager"
    const val KEY = "target_manager_live_status"
    const val ROW_ID = "copilot_target_manager:target_manager_live_status"
    const val SCHEMA_VERSION = 1
    const val MAX_JSON_BYTES = 2 * 1_024
    const val MAX_AGE_MS = 10L * 60_000L

    val SUPPORTED_REASONS: Set<String> = setOf(
        "eligible",
        "shadow_would_send",
        "no_eligible_proposal",
        "iob_unqualified",
        "mode_off",
        "semantic_duplicate",
        "cadence_blocked",
        "kill_switch",
        "stale_data",
        "sensor_untrusted",
        "delivery_untrusted",
        "protective_direction",
        "forecast_unreliable",
        "external_target_retained",
        "external_target_writer_conflict",
        "therapy_writes_disabled",
        "legacy_target_drain",
        "safety_bounds",
        "delivery_failed"
    ) + TargetCommandPreflightFailure.entries.map(TargetCommandPreflightFailure::reasonCode)

    private val requiredKeys = setOf(
        "schemaVersion",
        "timestamp",
        "mode",
        "priorityEnabled",
        "policyRevision",
        "currentTargetMmol",
        "proposedTargetMmol",
        "outcome",
        "reason"
    )
    private val supportedModes = TargetManagerMode.entries.mapTo(mutableSetOf(), TargetManagerMode::name)
    private val supportedOutcomes = TargetDecisionOutcome.entries
        .mapTo(mutableSetOf("DISPATCH_DISABLED"), TargetDecisionOutcome::name)

    fun encode(status: TargetManagerLiveStatus): String {
        requireValid(status)
        val encoded = JsonObject().apply {
            addProperty("schemaVersion", status.schemaVersion)
            addProperty("timestamp", status.timestamp)
            addProperty("mode", status.mode)
            addProperty("priorityEnabled", status.priorityEnabled)
            addProperty("policyRevision", status.policyRevision)
            addNullableDouble("currentTargetMmol", status.currentTargetMmol)
            addNullableDouble("proposedTargetMmol", status.proposedTargetMmol)
            addProperty("outcome", status.outcome)
            addProperty("reason", status.reason)
        }.toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) {
            "Target Manager live status JSON exceeds 2 KiB"
        }
        return encoded
    }

    fun decode(raw: String?): TargetManagerLiveStatus? {
        if (raw.isNullOrBlank() || raw.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) return null
        return try {
            JsonReader(StringReader(raw)).use { reader ->
                reader.strictness = Strictness.STRICT
                if (reader.peek() != JsonToken.BEGIN_OBJECT) return null
                reader.beginObject()
                val seen = mutableSetOf<String>()
                var schemaVersion: Int? = null
                var timestamp: Long? = null
                var mode: String? = null
                var priorityEnabled: Boolean? = null
                var policyRevision: Long? = null
                var currentTargetMmol: Double? = null
                var currentTargetSeen = false
                var proposedTargetMmol: Double? = null
                var proposedTargetSeen = false
                var outcome: String? = null
                var reason: String? = null

                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (name !in requiredKeys || !seen.add(name)) return null
                    when (name) {
                        "schemaVersion" -> schemaVersion = reader.nextExactIntOrNull() ?: return null
                        "timestamp" -> timestamp = reader.nextExactLongOrNull() ?: return null
                        "mode" -> mode = reader.nextStringOrNull() ?: return null
                        "priorityEnabled" -> priorityEnabled = reader.nextBooleanOrNull() ?: return null
                        "policyRevision" -> policyRevision = reader.nextExactLongOrNull() ?: return null
                        "currentTargetMmol" -> {
                            currentTargetSeen = true
                            currentTargetMmol = when (val parsed = reader.nextNullableFiniteDoubleOrInvalid()) {
                                ParsedNullableDouble.Null -> null
                                is ParsedNullableDouble.Value -> parsed.value
                                null -> return null
                            }
                        }
                        "proposedTargetMmol" -> {
                            proposedTargetSeen = true
                            proposedTargetMmol = when (val parsed = reader.nextNullableFiniteDoubleOrInvalid()) {
                                ParsedNullableDouble.Null -> null
                                is ParsedNullableDouble.Value -> parsed.value
                                null -> return null
                            }
                        }
                        "outcome" -> outcome = reader.nextStringOrNull() ?: return null
                        "reason" -> reason = reader.nextStringOrNull() ?: return null
                    }
                }
                reader.endObject()
                if (
                    reader.peek() != JsonToken.END_DOCUMENT ||
                    seen != requiredKeys ||
                    !currentTargetSeen ||
                    !proposedTargetSeen
                ) {
                    return null
                }
                TargetManagerLiveStatus(
                    schemaVersion = schemaVersion ?: return null,
                    timestamp = timestamp ?: return null,
                    mode = mode ?: return null,
                    priorityEnabled = priorityEnabled ?: return null,
                    policyRevision = policyRevision ?: return null,
                    currentTargetMmol = currentTargetMmol,
                    proposedTargetMmol = proposedTargetMmol,
                    outcome = outcome ?: return null,
                    reason = reason ?: return null
                ).also(::requireValid)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun toTelemetryRow(status: TargetManagerLiveStatus): TelemetrySampleEntity = TelemetrySampleEntity(
        id = ROW_ID,
        timestamp = status.timestamp,
        source = SOURCE,
        key = KEY,
        valueDouble = null,
        valueText = encode(status),
        unit = null,
        quality = "OK"
    )

    fun decodeTelemetryRow(row: TelemetrySampleEntity?): TargetManagerLiveStatus? {
        if (
            row == null ||
            row.id != ROW_ID ||
            row.source != SOURCE ||
            row.key != KEY ||
            row.valueDouble != null ||
            row.unit != null ||
            row.quality != "OK"
        ) {
            return null
        }
        return decode(row.valueText)?.takeIf { it.timestamp == row.timestamp }
    }

    fun isFresh(timestamp: Long, nowTs: Long): Boolean {
        val ageMs = try {
            Math.subtractExact(nowTs, timestamp)
        } catch (_: ArithmeticException) {
            return false
        }
        return ageMs in 0L..MAX_AGE_MS
    }

    private fun requireValid(status: TargetManagerLiveStatus) {
        require(status.schemaVersion == SCHEMA_VERSION) { "Unsupported Target Manager live status schema" }
        require(status.timestamp > 0L) { "Target Manager live status timestamp must be positive" }
        require(status.mode in supportedModes) { "Unsupported Target Manager mode" }
        require(status.policyRevision >= 0L) { "Target Manager policy revision must be nonnegative" }
        require(status.currentTargetMmol?.let { it.isFinite() && it > 0.0 } != false) {
            "Current target must be positive and finite"
        }
        require(status.proposedTargetMmol?.let { it.isFinite() && it > 0.0 } != false) {
            "Proposed target must be positive and finite"
        }
        require(status.outcome in supportedOutcomes) { "Unsupported Target Manager outcome" }
        require(status.reason in SUPPORTED_REASONS) { "Unsupported Target Manager reason" }
    }

    private fun JsonObject.addNullableDouble(name: String, value: Double?) {
        if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)
    }

    private fun JsonReader.nextExactIntOrNull(): Int? {
        if (peek() != JsonToken.NUMBER) return null
        return nextString().toIntOrNull()
    }

    private fun JsonReader.nextExactLongOrNull(): Long? {
        if (peek() != JsonToken.NUMBER) return null
        return nextString().toLongOrNull()
    }

    private fun JsonReader.nextStringOrNull(): String? {
        if (peek() != JsonToken.STRING) return null
        return nextString()
    }

    private fun JsonReader.nextBooleanOrNull(): Boolean? {
        if (peek() != JsonToken.BOOLEAN) return null
        return nextBoolean()
    }

    private sealed interface ParsedNullableDouble {
        data object Null : ParsedNullableDouble
        data class Value(val value: Double) : ParsedNullableDouble
    }

    private fun JsonReader.nextNullableFiniteDoubleOrInvalid(): ParsedNullableDouble? {
        if (peek() == JsonToken.NULL) {
            nextNull()
            return ParsedNullableDouble.Null
        }
        if (peek() != JsonToken.NUMBER) return null
        return nextString()
            .toDoubleOrNull()
            ?.takeIf(Double::isFinite)
            ?.let(ParsedNullableDouble::Value)
    }
}
