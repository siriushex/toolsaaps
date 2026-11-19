package io.aaps.copilot.service

import io.aaps.copilot.data.repository.HistoryJson
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import kotlinx.serialization.json.*

private val localProfileFields = setOf("app", "date", "utcOffset", "created_at", "units", "eventType", "originalProfileName",
    "originalCustomizedName", "originalTimeshift", "originalPercentage", "originalDuration", "originalEnd", "icfg", "profileJson")

/** Only correct the known whole-hour export of this very same local effective profile. */
internal fun isExactLocalScheduleRepair(previous: String, incoming: JsonObject): Boolean {
    if (incoming["app"] != JsonPrimitive("AAPS-local") || incoming["eventType"] != JsonPrimitive("Note") ||
        incoming.keys != localProfileFields) return false
    return try {
        val old = HistoryJson.parse(HistoryJson.encodeUtf8(previous), LocalNightscoutV3Store.MAX_PAYLOAD_BYTES)
        if (old - "profileJson" != incoming - "profileJson") return false
        fun profile(row: JsonObject) = HistoryJson.parse(HistoryJson.encodeUtf8(HistoryJson.string(row.getValue("profileJson"))), 48_000)
        val exact = profile(incoming)
        val parsed = AapsV3ProfileJson.profile(exact, effective = true)
        if (parsed.insulin != AapsV3ProfileJson.insulin(HistoryJson.obj(incoming.getValue("icfg")))) return false
        val legacy = profile(old)
        if (legacy == exact) return false
        val fields = setOf("basal", "sens", "carbratio", "target_low", "target_high")
        if (legacy - fields != exact - fields) return false
        fields.all { field ->
            val blocks = exact.getValue(field).jsonArray.map { it.jsonObject }
            val starts = blocks.map { it.getValue("timeAsSeconds").jsonPrimitive.int }
            var hour = 0
            val rounded = buildJsonArray {
                for ((i, start) in starts.withIndex()) {
                    val cursor = starts.binarySearch(hour * 3600).let { if (it >= 0) it else -it - 2 }
                    add(buildJsonObject {
                        put("time", hour.toString().padStart(2, '0') + ":00")
                        put("timeAsSeconds", hour * 3600)
                        put("value", blocks[cursor].getValue("value"))
                    })
                    hour += ((starts.getOrNull(i + 1) ?: 86_400) - start) / 3600
                }
            }
            legacy[field] == rounded
        }
    } catch (_: Exception) {
        false
    }
}
