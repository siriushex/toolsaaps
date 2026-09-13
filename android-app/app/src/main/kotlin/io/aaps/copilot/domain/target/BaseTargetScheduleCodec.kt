package io.aaps.copilot.domain.target

import kotlin.math.round
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull

sealed interface BaseTargetScheduleDecodeResult {
    data class Valid(val schedule: BaseTargetSchedule) : BaseTargetScheduleDecodeResult

    data class Fallback(
        val schedule: BaseTargetSchedule,
        val reason: String
    ) : BaseTargetScheduleDecodeResult
}

class BaseTargetScheduleCodec(
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
) {
    fun encode(schedule: BaseTargetSchedule): String {
        val encoded = json.encodeToString(schedule)
        require(encoded.length <= MAX_RAW_SCHEDULE_BYTES &&
            encoded.toByteArray(Charsets.UTF_8).size <= MAX_RAW_SCHEDULE_BYTES
        ) {
            "Base target schedule JSON exceeds 64 KiB"
        }
        return encoded
    }

    fun decode(
        raw: String?,
        legacyTarget: Double
    ): BaseTargetScheduleDecodeResult {
        if (raw.isNullOrBlank()) {
            return fallback(legacyTarget, LEGACY_SCHEDULE_MIGRATED)
        }
        if (raw.length > MAX_RAW_SCHEDULE_BYTES ||
            raw.toByteArray(Charsets.UTF_8).size > MAX_RAW_SCHEDULE_BYTES
        ) {
            return fallback(legacyTarget, MALFORMED_SCHEDULE_JSON)
        }

        val root = try {
            json.parseToJsonElement(raw)
        } catch (_: SerializationException) {
            return fallback(legacyTarget, MALFORMED_SCHEDULE_JSON)
        } catch (_: IllegalArgumentException) {
            return fallback(legacyTarget, MALFORMED_SCHEDULE_JSON)
        }
        val schemaVersion = readSchemaVersion(root)
            ?: return fallback(legacyTarget, MALFORMED_SCHEDULE_JSON)
        if (schemaVersion != BaseTargetSchedule.CURRENT_SCHEMA_VERSION.toDouble()) {
            return fallback(legacyTarget, UNSUPPORTED_SCHEDULE_SCHEMA)
        }

        val schedule = try {
            json.decodeFromJsonElement<BaseTargetSchedule>(root)
        } catch (_: SerializationException) {
            return fallback(legacyTarget, MALFORMED_SCHEDULE_JSON)
        } catch (_: IllegalArgumentException) {
            return fallback(legacyTarget, MALFORMED_SCHEDULE_JSON)
        }

        val validationErrors = BaseTargetSchedulePolicy.validate(
            schedule = schedule,
            minTarget = -Double.MAX_VALUE,
            maxTarget = Double.MAX_VALUE
        )
        if (validationErrors.isNotEmpty()) {
            return fallback(legacyTarget, INVALID_SCHEDULE_PAYLOAD)
        }

        return BaseTargetScheduleDecodeResult.Valid(schedule)
    }

    private fun readSchemaVersion(element: JsonElement): Double? {
        val root = element as? JsonObject ?: return null
        val schema = root["schemaVersion"] as? JsonPrimitive ?: return null
        if (schema.isString) return null
        return schema.doubleOrNull?.takeIf(Double::isFinite)
    }

    private fun fallback(
        legacyTarget: Double,
        reason: String
    ): BaseTargetScheduleDecodeResult.Fallback {
        val finiteTarget = legacyTarget.takeIf(Double::isFinite) ?: DEFAULT_LEGACY_TARGET_MMOL
        val safeTarget = (round(finiteTarget.coerceIn(
            MIN_LEGACY_TARGET_MMOL,
            MAX_LEGACY_TARGET_MMOL
        ) * 10.0) / 10.0).coerceIn(
            MIN_LEGACY_TARGET_MMOL,
            MAX_LEGACY_TARGET_MMOL
        )
        return BaseTargetScheduleDecodeResult.Fallback(
            schedule = BaseTargetSchedule.legacy(safeTarget),
            reason = reason
        )
    }

    private companion object {
        const val LEGACY_SCHEDULE_MIGRATED = "legacy_schedule_migrated"
        const val MALFORMED_SCHEDULE_JSON = "malformed_schedule_json"
        const val UNSUPPORTED_SCHEDULE_SCHEMA = "unsupported_schedule_schema"
        const val INVALID_SCHEDULE_PAYLOAD = "invalid_schedule_payload"
        const val MAX_RAW_SCHEDULE_BYTES = 64 * 1024

        const val MIN_LEGACY_TARGET_MMOL = 4.0
        const val MAX_LEGACY_TARGET_MMOL = 10.0
        const val DEFAULT_LEGACY_TARGET_MMOL = 5.5
    }
}
