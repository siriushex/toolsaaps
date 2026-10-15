package io.aaps.copilot.data.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.pump.InfusionSetEvidence
import java.time.Instant
import java.util.Locale

/** Original occurrence only; legacy received-at timestamps and age aliases are not replacement evidence. */
object InfusionSetEvidenceReader {
    const val MAX_CANDIDATES = 32
    val TYPES = listOf("infusion_set_change", "site_change", "cannula_change", "set_change", "pump_site_change")
    private val sources = setOf("nightscout_treatment", "local_nightscout_treatment")

    fun read(rows: List<TherapyEventEntity>, nowTs: Long): InfusionSetEvidence? {
        if (nowTs <= 0 || rows.size > MAX_CANDIDATES) return null
        val relevant = rows.filter { it.type.trim().lowercase(Locale.US) in TYPES }
        val latest = relevant.maxOfOrNull { it.timestamp } ?: return null
        if (latest !in 1..nowTs) return null
        val candidates = relevant.filter { it.timestamp == latest }.map { parse(it, nowTs) }
        if (candidates.any { it == null }) return null
        return candidates.filterNotNull().minByOrNull { it.source }
    }

    private fun parse(row: TherapyEventEntity, nowTs: Long): InfusionSetEvidence? = try {
        require(row.payloadJson.length <= 65_536 && !row.id.lowercase(Locale.US).startsWith("copilot"))
        val root = JsonParser.parseString(row.payloadJson).asJsonObject
        val source = root.string("source")
        require(source in sources)
        require(root.flag("isValid", true) && !root.flag("deleted", false) && !root.flag("isDeleted", false))
        require(!root.flag("inferred", false))
        require(!root.has("classification") || root.string("classification") == "REAL_FETCHED")
        listOf("enteredBy", "notes").forEach { key ->
            require(!root.has(key) || !root.string(key).contains("copilot", ignoreCase = true))
        }
        val dates = listOf("date", "mills", "createdAt").filter(root::has).map { key ->
            val value = root.get(key)
            require(value.isJsonPrimitive && (value.asJsonPrimitive.isString || value.asJsonPrimitive.isNumber))
            val text = value.asString.trim()
            if (key == "createdAt" && text.contains('T')) Instant.parse(text).toEpochMilli() else {
                val raw = text.toBigDecimal().longValueExact()
                require(raw > 0)
                if (raw < 10_000_000_000L) Math.multiplyExact(raw, 1000L) else raw
            }
        }
        require(dates.isNotEmpty() && dates.distinct().size == 1)
        val occurrence = dates.singleOrNull() ?: dates.first()
        require(occurrence in 1..nowTs && occurrence == row.timestamp)
        InfusionSetEvidence(occurrence, "infusion-$occurrence", source)
    } catch (_: RuntimeException) { null }

    private fun JsonObject.string(key: String): String {
        val value = get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }
    private fun JsonObject.flag(key: String, default: Boolean): Boolean {
        if (!has(key)) return default
        val value = get(key)
        require(value.isJsonPrimitive && (value.asJsonPrimitive.isBoolean || value.asJsonPrimitive.isString))
        return when (value.asString) { "true" -> true; "false" -> false; else -> error("Invalid event flag") }
    }
}
