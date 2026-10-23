package io.aaps.copilot.service

import io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity
import io.aaps.copilot.data.repository.HistoryJson
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import io.aaps.copilot.data.repository.LocalV3RetainedSnapshot
import java.security.MessageDigest
import java.util.Collections
import kotlinx.serialization.json.*

enum class AapsV3ObservedInsulinStatus { UNCHECKED, OBSERVED, PARTIAL_CONTEXT, NOT_OBSERVED, INVALID, CONFLICTING, LATE_EVIDENCE, LIMIT_REACHED, ERROR }

internal enum class AapsV3TempBasalType { NORMAL, EMULATED_PUMP_SUSPEND, PUMP_SUSPEND, SUPERBOLUS, FAKE_EXTENDED }

internal sealed class AapsV3InsulinDose {
    data class Bolus(val units: Double, val type: String, val basalInsulin: Boolean,
        val configuration: AapsV3InsulinConfiguration?) : AapsV3InsulinDose()
    data class TempBasal(val durationMs: Long, val absoluteUnitsPerHour: Double?, val percentAdjustment: Double?,
        val type: AapsV3TempBasalType = AapsV3TempBasalType.NORMAL, val reportedRateAtStart: Double? = null) : AapsV3InsulinDose()
    data class Extended(val units: Double, val durationMs: Long, val emulatingTempBasal: Boolean) : AapsV3InsulinDose()
    final override fun toString() = "AapsV3InsulinDose(redacted)"
}

/** A retained uploader observation, not a pump delivery receipt. */
internal data class AapsV3InsulinObservation(
    val identifier: String, val eventAtMs: Long, val availableAtMs: Long,
    val createdRevision: Long, val modifiedRevision: Long, val invalidated: Boolean,
    val pumpRecordIdentity: String?, val pumpEndId: Long?, val dose: AapsV3InsulinDose
) {
    val endAtMs: Long get() = eventAtMs + when (dose) {
        is AapsV3InsulinDose.Bolus -> 0
        is AapsV3InsulinDose.TempBasal -> dose.durationMs
        is AapsV3InsulinDose.Extended -> dose.durationMs
    }
    override fun toString() = "AapsV3InsulinObservation(redacted)"
}

internal class AapsV3InsulinRead(val status: AapsV3ObservedInsulinStatus, val retainedRevision: Long?,
    records: List<AapsV3InsulinObservation> = emptyList()) {
    val records: List<AapsV3InsulinObservation> = Collections.unmodifiableList(records.toList())
    val upstreamCompletenessVerified: Boolean get() = false
    override fun toString() = "AapsV3InsulinRead(status=$status, redacted)"
}

internal class AapsV3InsulinInventoryTimeline(snapshot: LocalV3RetainedSnapshot) {
    val revision = snapshot.collectionRevision
    private data class Entry(val atMs: Long, val observation: AapsV3InsulinObservation?)
    private val entries: List<Entry>
    private val failure: AapsV3ObservedInsulinStatus?

    init {
        val rows = (snapshot as? LocalV3RetainedSnapshot.Complete)?.documents.orEmpty()
        failure = when {
            snapshot is LocalV3RetainedSnapshot.OverLimit -> AapsV3ObservedInsulinStatus.LIMIT_REACHED
            revision !in 0..LocalNightscoutV3Store.MAX_TIME ||
                rows.map { it.identifier }.distinct().size != rows.size || rows.any {
                    it.collection != "treatments" || it.source != "local-v3-owner" || it.identifier.isBlank() ||
                        it.createdRevision !in 1..it.modifiedRevision || it.modifiedRevision > revision ||
                        it.eventAtMs == null || it.eventAtMs !in 1..LocalNightscoutV3Store.MAX_TIME
                } -> AapsV3ObservedInsulinStatus.INVALID
            else -> null
        }
        entries = if (failure != null) emptyList() else rows.mapNotNull { row ->
            try {
                val bytes = HistoryJson.encodeUtf8(row.payloadJson)
                require(bytes.size == row.payloadBytes)
                val body = HistoryJson.parse(bytes, LocalNightscoutV3Store.MAX_PAYLOAD_BYTES)
                val dose = dose(body) ?: return@mapNotNull null
                val identityBody = if (dose is AapsV3InsulinDose.Extended && string(body, "eventType") == "Temp Basal")
                    HistoryJson.obj(body.getValue("extendedEmulated")) else body
                val at = HistoryJson.long(body.getValue("date"))
                require(at == row.eventAtMs && at in 1..LocalNightscoutV3Store.MAX_TIME)
                val duration = when (dose) {
                    is AapsV3InsulinDose.Bolus -> 0
                    is AapsV3InsulinDose.TempBasal -> dose.durationMs
                    is AapsV3InsulinDose.Extended -> dose.durationMs
                }
                require(at <= LocalNightscoutV3Store.MAX_TIME - duration)
                Entry(at, AapsV3InsulinObservation(row.identifier, at, row.modifiedRevision,
                    row.createdRevision, row.modifiedRevision, row.deleted || boolean(body, "isValid") == false ||
                        boolean(identityBody, "isValid") == false,
                    pumpIdentity(identityBody, dose), identityBody["endId"]?.takeUnless { it == JsonNull }?.let(HistoryJson::long), dose))
            } catch (_: Exception) {
                Entry(checkNotNull(row.eventAtMs), null)
            }
        }
    }

    fun read(fromMs: Long, atMs: Long, knownAtMs: Long): AapsV3InsulinRead {
        fun unavailable(status: AapsV3ObservedInsulinStatus) = AapsV3InsulinRead(status, revision)
        if (fromMs !in 1..atMs || atMs > knownAtMs || knownAtMs > LocalNightscoutV3Store.MAX_TIME)
            return unavailable(AapsV3ObservedInsulinStatus.INVALID)
        failure?.let { return unavailable(it) }
        val relevant = entries.filter { it.atMs <= atMs &&
            (it.observation == null || it.atMs >= fromMs || it.observation.endAtMs > fromMs) }
        if (relevant.any { it.observation == null }) return unavailable(AapsV3ObservedInsulinStatus.INVALID)
        val records = relevant.map { checkNotNull(it.observation) }.sortedWith(compareBy({ it.eventAtMs }, { it.identifier }))
        if (records.any { it.availableAtMs > knownAtMs }) return unavailable(AapsV3ObservedInsulinStatus.LATE_EVIDENCE)
        val keys = records.mapNotNull { it.pumpRecordIdentity }
        if (keys.distinct().size != keys.size) return unavailable(AapsV3ObservedInsulinStatus.CONFLICTING)
        val status = when {
            records.isEmpty() -> AapsV3ObservedInsulinStatus.NOT_OBSERVED
            records.any { !it.invalidated && (it.dose as? AapsV3InsulinDose.Bolus)?.configuration == null &&
                it.dose is AapsV3InsulinDose.Bolus } -> AapsV3ObservedInsulinStatus.PARTIAL_CONTEXT
            else -> AapsV3ObservedInsulinStatus.OBSERVED
        }
        return AapsV3InsulinRead(status, revision, records)
    }

    private fun dose(body: JsonObject): AapsV3InsulinDose? {
        val insulin = number(body, "insulin")
        require(insulin == null || insulin >= 0)
        // The native mapper chooses insulin before eventType and before carbohydrate fields.
        if (insulin != null && insulin > 0) {
            require((number(body, "carbs") ?: 0.0) == 0.0)
            require((number(body, "duration") ?: 0.0) == 0.0 && (number(body, "durationInMilliseconds") ?: 0.0) == 0.0)
            require(body["extendedEmulated"] == null || body["extendedEmulated"] == JsonNull)
            val type = string(body, "type") ?: "NORMAL"
            require(type in setOf("NORMAL", "SMB", "PRIMING"))
            val config = body["icfg"]?.takeUnless { it == JsonNull }?.let { AapsV3ProfileJson.insulin(HistoryJson.obj(it)) }
            return AapsV3InsulinDose.Bolus(insulin, type, boolean(body, "isBasalInsulin") ?: false, config)
        }
        if ((number(body, "carbs") ?: 0.0) != 0.0) return null
        return when (string(body, "eventType")) {
            "Temp Basal" -> {
                body["extendedEmulated"]?.takeUnless { it == JsonNull }?.let { return extended(HistoryJson.obj(it)) }
                val duration = duration(body)
                val absolute = number(body, "absolute")?.also { require(it >= 0) }
                val percent = number(body, "percent")?.also { require(it >= -100) }
                require((absolute != null) xor (percent != null))
                val type = AapsV3TempBasalType.valueOf(string(body, "type") ?: "NORMAL")
                val startRate = number(body, "rate")?.also { require(it >= 0) }
                require(absolute == null || startRate == null || absolute == startRate)
                AapsV3InsulinDose.TempBasal(duration, absolute, percent, type, startRate)
            }
            "Combo Bolus" -> extended(body)
            "Correction Bolus", "Meal Bolus", "Snack Bolus" -> throw IllegalArgumentException("missing_native_insulin")
            else -> null
        }
    }

    private fun extended(body: JsonObject): AapsV3InsulinDose.Extended {
        val units = checkNotNull(number(body, "enteredinsulin")).also { require(it >= 0) }
        return AapsV3InsulinDose.Extended(units, duration(body), boolean(body, "isEmulatingTempBasal") ?: false)
    }

    private fun duration(body: JsonObject): Long {
        fun integer(key: String) = body[key]?.takeUnless { it == JsonNull }?.let(HistoryJson::long)
        val minutes = integer("duration")
        val milliseconds = integer("durationInMilliseconds")
        require(minutes != null || milliseconds != null)
        val result = milliseconds ?: Math.multiplyExact(checkNotNull(minutes), 60_000L)
        require(result in 0..31_536_000_000L)
        if (minutes != null) require(minutes == result / 60_000L)
        return result
    }

    private fun pumpIdentity(body: JsonObject, dose: AapsV3InsulinDose): String? {
        val id = body["pumpId"]?.takeUnless { it == JsonNull }?.let(HistoryJson::long)
        val type = string(body, "pumpType")
        val serial = string(body, "pumpSerial")
        if (id == null && type == null && serial == null) return null
        require(id != null && !type.isNullOrBlank() && !serial.isNullOrBlank())
        require(type.length <= 256 && serial.length <= 256 && (type + serial).none(Char::isISOControl))
        val kind = when (dose) {
            is AapsV3InsulinDose.Bolus -> "bolus"
            is AapsV3InsulinDose.TempBasal -> "temp-basal"
            is AapsV3InsulinDose.Extended -> "extended"
        }
        val identity = buildJsonArray { add(kind); add(type); add(serial); add(id) }.toString()
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun number(body: JsonObject, key: String) = body[key]?.takeUnless { it == JsonNull }?.let(HistoryJson::double)
    private fun string(body: JsonObject, key: String) = body[key]?.takeUnless { it == JsonNull }?.let(HistoryJson::string)
    private fun boolean(body: JsonObject, key: String) = body[key]?.takeUnless { it == JsonNull }?.let(HistoryJson::bool)
}
