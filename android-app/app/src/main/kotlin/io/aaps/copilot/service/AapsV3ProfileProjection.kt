package io.aaps.copilot.service

import io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity
import io.aaps.copilot.data.repository.HistoryJson
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import java.time.ZoneId
import kotlinx.serialization.json.*

internal data class AapsV3SchedulePoint(val label: String, val seconds: Int, val value: Double)

internal data class AapsV3InsulinConfiguration(
    val label: String,
    val endTimeMs: Long,
    val peakTimeMs: Long,
    val concentrationRatio: Double
) {
    override fun toString() = "AapsV3InsulinConfiguration(redacted)"
}

internal data class AapsV3ProfileSnapshot(
    val units: String,
    val timezone: String,
    val carbRatio: List<AapsV3SchedulePoint>,
    val sensitivity: List<AapsV3SchedulePoint>,
    val basal: List<AapsV3SchedulePoint>,
    val targetLow: List<AapsV3SchedulePoint>,
    val targetHigh: List<AapsV3SchedulePoint>,
    val insulin: AapsV3InsulinConfiguration?,
    val insulinNickname: String?
) {
    override fun toString() = "AapsV3ProfileSnapshot(redacted)"
}

/** Shared structural decoding. Native schedule values are already effective, not a new prescription. */
internal object AapsV3ProfileJson {
    private val schedules = setOf("carbratio", "sens", "basal", "target_low", "target_high")

    fun profile(body: JsonObject, effective: Boolean = false): AapsV3ProfileSnapshot {
        HistoryJson.fields(body, schedules + setOf("units", "timezone"), if (effective) setOf("iCfg") else emptySet())
        val units = HistoryJson.string(body.getValue("units")).also { require(it in setOf("mg/dl", "mmol")) }
        val zone = HistoryJson.string(body.getValue("timezone")).also { require(it.length in 1..128); ZoneId.of(it) }
        val rows = schedules.associateWith { schedule(body.getValue(it), it == "basal") }
        val low = rows.getValue("target_low")
        val high = rows.getValue("target_high")
        require(low.size == high.size && low.zip(high).all { (a, b) -> a.seconds == b.seconds && a.value <= b.value })
        val config = body["iCfg"]?.takeUnless { it == JsonNull }?.let(HistoryJson::obj)
        val nickname = config?.get("insulinNickname")?.takeUnless { it == JsonNull }?.let(HistoryJson::string)
            ?.also { require(it.length <= 256 && it.none(Char::isISOControl)) }
        return AapsV3ProfileSnapshot(units, zone, rows.getValue("carbratio"), rows.getValue("sens"),
            rows.getValue("basal"), low, high, config?.let { insulin(it, allowNickname = true) }, nickname)
    }

    fun insulin(body: JsonObject, allowNickname: Boolean = false): AapsV3InsulinConfiguration {
        HistoryJson.fields(body, setOf("insulinLabel", "insulinEndTime", "insulinPeakTime", "concentration"),
            if (allowNickname) setOf("insulinNickname") else emptySet())
        val label = HistoryJson.string(body.getValue("insulinLabel"))
        require(label.isNotBlank() && label.length <= 256 && label.none(Char::isISOControl))
        val end = HistoryJson.long(body.getValue("insulinEndTime"))
        val peak = HistoryJson.long(body.getValue("insulinPeakTime"))
        val ratio = HistoryJson.double(body.getValue("concentration"))
        require(end in 1..604_800_000 && peak in 1..end && ratio > 0 && ratio <= 1000)
        return AapsV3InsulinConfiguration(label, end, peak, ratio)
    }

    private fun schedule(value: JsonElement, allowZero: Boolean): List<AapsV3SchedulePoint> {
        val rows = HistoryJson.array(value)
        require(rows.size in 1..1440)
        val result = rows.map { row ->
            val item = HistoryJson.obj(row)
            HistoryJson.fields(item, setOf("time", "timeAsSeconds", "value"))
            val seconds = HistoryJson.long(item.getValue("timeAsSeconds")).also { require(it in 0..86399) }.toInt()
            val label = HistoryJson.string(item.getValue("time"))
            require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(label))
            val labelSeconds = label.take(2).toInt() * 3600 + label.takeLast(2).toInt() * 60
            // RC1 writes HH:00 labels even for non-hour boundaries. Preserve the exact seconds.
            require(labelSeconds == seconds || labelSeconds == seconds / 3600 * 3600)
            val amount = HistoryJson.double(item.getValue("value"))
            require(if (allowZero) amount >= 0 else amount > 0)
            AapsV3SchedulePoint(label, seconds, amount)
        }
        require(result.first().seconds == 0 && result.zipWithNext().all { (a, b) -> a.seconds < b.seconds })
        return result
    }
}

internal data class AapsV3EffectiveProfileObservation(
    val identifier: String,
    val createdRevision: Long,
    val modifiedRevision: Long,
    val eventAtMs: Long,
    val utcOffsetMinutes: Long,
    val originalName: String,
    val customizedName: String,
    val originalTimeshiftMs: Long,
    val originalPercentage: Int,
    val originalDurationMs: Long,
    val originalEndMs: Long,
    val profile: AapsV3ProfileSnapshot,
    val insulin: AapsV3InsulinConfiguration
) {
    override fun toString() = "AapsV3EffectiveProfileObservation(redacted)"
}

internal enum class AapsV3ProfileUnavailable { INVALID_SOURCE, INVALIDATED, MALFORMED, MISSING_INSULIN, CONFLICTING_INSULIN }
internal sealed interface AapsV3ProfileProjection {
    data object NotEffectiveProfile : AapsV3ProfileProjection
    data class Observed(val observation: AapsV3EffectiveProfileObservation) : AapsV3ProfileProjection
    data class Unavailable(val reason: AapsV3ProfileUnavailable) : AapsV3ProfileProjection
}

/** Decodes one stored EPS. It does not resolve history coverage, select an active profile or grant readiness. */
internal object AapsV3EffectiveProfileProjector {
    fun project(row: LocalNightscoutV3DocumentEntity): AapsV3ProfileProjection {
        if (row.collection != "treatments" || row.source != "local-v3-owner" || row.identifier.isBlank() ||
            row.createdRevision <= 0 || row.modifiedRevision < row.createdRevision) {
            return AapsV3ProfileProjection.Unavailable(AapsV3ProfileUnavailable.INVALID_SOURCE)
        }
        return try {
            val bytes = HistoryJson.encodeUtf8(row.payloadJson)
            require(bytes.size == row.payloadBytes)
            val body = HistoryJson.parse(bytes, LocalNightscoutV3Store.MAX_PAYLOAD_BYTES)
            if (body["eventType"] != JsonPrimitive("Note") || body["originalProfileName"] == null) {
                return AapsV3ProfileProjection.NotEffectiveProfile
            }
            // The native mapper selects bolus/carbs before inspecting the EPS-shaped note fields.
            fun number(key: String) = body[key]?.takeUnless { it == JsonNull }?.let(HistoryJson::double)
            if ((number("insulin") ?: 0.0) > 0.0 || (number("carbs") ?: 0.0) != 0.0) {
                return AapsV3ProfileProjection.NotEffectiveProfile
            }
            val valid = body["isValid"]?.takeUnless { it == JsonNull }?.let(HistoryJson::bool) ?: true
            if (row.deleted || !valid) {
                return AapsV3ProfileProjection.Unavailable(AapsV3ProfileUnavailable.INVALIDATED)
            }
            val at = HistoryJson.long(body.getValue("date"))
            require(at in 1..LocalNightscoutV3Store.MAX_TIME && row.eventAtMs == at)
            val offset = HistoryJson.signedLong(body.getValue("utcOffset")).also { require(it in -1440..1440) }
            fun name(key: String) = HistoryJson.string(body.getValue(key)).also {
                require(it.isNotBlank() && it.length <= 4096 && it.none(Char::isISOControl))
            }
            val originalName = name("originalProfileName")
            val customizedName = name("originalCustomizedName")
            val shift = HistoryJson.signedLong(body.getValue("originalTimeshift"))
            val percentage = HistoryJson.long(body.getValue("originalPercentage"))
                .also { require(it in 1..Int.MAX_VALUE.toLong()) }.toInt()
            val duration = HistoryJson.long(body.getValue("originalDuration"))
            val end = HistoryJson.long(body.getValue("originalEnd"))
            require(duration <= LocalNightscoutV3Store.MAX_TIME && end <= LocalNightscoutV3Store.MAX_TIME)
            val profileBytes = HistoryJson.encodeUtf8(HistoryJson.string(body.getValue("profileJson")))
            val profile = AapsV3ProfileJson.profile(HistoryJson.parse(profileBytes, 48_000), effective = true)
            val config = body["icfg"]?.takeUnless { it == JsonNull }
                ?: return AapsV3ProfileProjection.Unavailable(AapsV3ProfileUnavailable.MISSING_INSULIN)
            val insulin = AapsV3ProfileJson.insulin(HistoryJson.obj(config))
            if (profile.insulin != null && profile.insulin != insulin) {
                return AapsV3ProfileProjection.Unavailable(AapsV3ProfileUnavailable.CONFLICTING_INSULIN)
            }
            AapsV3ProfileProjection.Observed(AapsV3EffectiveProfileObservation(row.identifier, row.createdRevision,
                row.modifiedRevision, at, offset, originalName, customizedName, shift, percentage, duration, end, profile, insulin))
        } catch (_: Exception) {
            AapsV3ProfileProjection.Unavailable(AapsV3ProfileUnavailable.MALFORMED)
        }
    }
}
