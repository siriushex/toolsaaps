package io.aaps.copilot.data.repository

import kotlinx.serialization.json.*

enum class ServerAiHistoryKind { GLUCOSE, THERAPY, MEAL, FORECAST, SENSITIVITY, ACTIVITY, DEVICE, ALERT, DIAGNOSTIC, CHAT }
enum class ServerAiHistorySource { CGM, AAPS, COPILOT, USER, SERVER }
enum class ServerAiHistoryQuality { OK, UNKNOWN, SUSPECT }
enum class ServerAiHistoryRole { USER, ASSISTANT }
enum class ServerAiDeliveryState { CONFIRMED, UNKNOWN, SUSPECT, STOPPED }
enum class ServerAiTherapyEvent { CARBS, BOLUS, BASAL, TEMP_TARGET, CANCEL_TARGET, SENSOR_CHANGE, SET_CHANGE, DELIVERY_STATE }
enum class ServerAiFoodType { FAST, MIXED, FAT_PROTEIN }
enum class ServerAiQuantitySource { WEIGHED, LABEL, RECIPE, APPROXIMATE, UNKNOWN }
enum class ServerAiGiSource { USER, CATALOG, MODEL_ESTIMATE }
enum class ServerAiGlucoseBasis { RAW, RESOLVED, UNKNOWN }
enum class ServerAiAlertSeverity { INFO, WARNING, CRITICAL }
enum class ServerAiAlertDelivery { CONFIRMED, UNKNOWN, FAILED, SUPPRESSED }
enum class ServerAiDiagnosticStatus { OK, UNKNOWN, FAILED, BLOCKED }

/** Device declarations only. No member of this hierarchy performs a therapy write. */
sealed class ServerAiHistoryData(val kind: ServerAiHistoryKind) {
    data class Glucose(val glucoseMmol: Double) : ServerAiHistoryData(ServerAiHistoryKind.GLUCOSE)
    data class Therapy(
        val eventType: ServerAiTherapyEvent,
        val carbsGrams: Double? = null,
        val insulinUnits: Double? = null,
        val basalUnitsPerHour: Double? = null,
        val targetMmol: Double? = null,
        val durationMinutes: Double? = null,
        val deliveryState: ServerAiDeliveryState? = null
    ) : ServerAiHistoryData(ServerAiHistoryKind.THERAPY)
    data class Meal(
        val carbsGrams: Double,
        val foodType: ServerAiFoodType,
        val quantitySource: ServerAiQuantitySource,
        val glycemicIndex: Double? = null,
        val giSource: ServerAiGiSource? = null
    ) : ServerAiHistoryData(ServerAiHistoryKind.MEAL)
    data class Forecast(val horizonMinutes: Int, val valueMmol: Double, val modelVersion: String,
        val ciLowMmol: Double? = null, val ciHighMmol: Double? = null) : ServerAiHistoryData(ServerAiHistoryKind.FORECAST)
    data class Sensitivity(val isfMmolPerUnit: Double? = null, val crGramsPerUnit: Double? = null,
        val positiveIobUnits: Double? = null, val netIobUnits: Double? = null, val cobGrams: Double? = null
    ) : ServerAiHistoryData(ServerAiHistoryKind.SENSITIVITY)
    data class Activity(val steps: Int? = null, val activeMinutes: Double? = null, val activeKcal: Double? = null
    ) : ServerAiHistoryData(ServerAiHistoryKind.ACTIVITY)
    data class Device(val sensorAgeHours: Double? = null, val setAgeHours: Double? = null,
        val deliveryState: ServerAiDeliveryState? = null) : ServerAiHistoryData(ServerAiHistoryKind.DEVICE)
    data class Alert(val code: String, val severity: ServerAiAlertSeverity, val deliveryStatus: ServerAiAlertDelivery
    ) : ServerAiHistoryData(ServerAiHistoryKind.ALERT)
    data class Diagnostic(val code: String, val status: ServerAiDiagnosticStatus) : ServerAiHistoryData(ServerAiHistoryKind.DIAGNOSTIC)
    data class Chat(val conversationId: String, val role: ServerAiHistoryRole, val text: String
    ) : ServerAiHistoryData(ServerAiHistoryKind.CHAT)

    final override fun toString() = "ServerAiHistoryData(redacted)"
}

data class ServerAiHistoricalContext(
    val zoneId: String? = null,
    val sensorSessionId: String? = null,
    val calibrationModelId: String? = null,
    val glucoseBasis: ServerAiGlucoseBasis? = null,
    val inputModelVersion: String? = null
) { override fun toString() = "ServerAiHistoricalContext(redacted)" }

/** IDs/revisions and original times must come from the exporting adapter, never guessed here. */
data class ServerAiHistoryRecord(
    val recordId: String,
    val revision: Int,
    val eventAtMs: Long,
    val observedAtMs: Long,
    val kind: ServerAiHistoryKind,
    val source: ServerAiHistorySource,
    val quality: ServerAiHistoryQuality,
    val deleted: Boolean = false,
    val data: ServerAiHistoryData? = null,
    val context: ServerAiHistoricalContext? = null
) { override fun toString() = "ServerAiHistoryRecord(redacted)" }

data class ServerAiHistoryReceipt(val batchId: String, val sequence: Long, val changedRecords: Int) {
    override fun toString() = "ServerAiHistoryReceipt(redacted)"
}
data class ServerAiHistoryQuery(
    val fromMs: Long,
    val untilMs: Long,
    val kinds: List<ServerAiHistoryKind> = emptyList(),
    val afterSequence: Long = 0,
    val snapshotSequence: Long? = null,
    val limit: Int = 32
) { override fun toString() = "ServerAiHistoryQuery(redacted)" }
data class ServerAiHistoryEntry(val sequence: Long, val receivedAtMs: Long, val record: ServerAiHistoryRecord) {
    override fun toString() = "ServerAiHistoryEntry(redacted)"
}
data class ServerAiHistoryPage(val snapshotSequence: Long, val nextAfterSequence: Long, val hasMore: Boolean,
    val records: List<ServerAiHistoryEntry>, val provenance: String = "DEVICE_DECLARED") {
    override fun toString() = "ServerAiHistoryPage(redacted)"
}
data class ServerAiHistoryStatus(val sequence: Long) {
    override fun toString() = "ServerAiHistoryStatus(redacted)"
}
data class ServerAiConversationQuery(val fromMs: Long, val untilMs: Long, val cursor: String? = null, val limit: Int = 20) {
    override fun toString() = "ServerAiConversationQuery(redacted)"
}
enum class ServerAiConversationState { OUTCOME_NOT_RECORDED, SUCCEEDED, FAILED, CANCELLED, EXPIRED, UNKNOWN }
data class ServerAiConversationEntry(
    val jobId: String, val requestId: String, val acceptedAtMs: Long, val deadlineMs: Long,
    val startedAtMs: Long?, val finishedAtMs: Long?, val state: ServerAiConversationState,
    val question: String, val answer: String?, val model: String, val promptRevision: String, val updatedAtMs: Long
) { override fun toString() = "ServerAiConversationEntry(redacted)" }
data class ServerAiConversationPage(
    val generatedAtMs: Long, val records: List<ServerAiConversationEntry>, val hasMore: Boolean, val nextCursor: String?,
    val revision: String = "server-chat-archive-v1", val origin: String = "SERVER_ARCHIVE",
    val snapshotScope: String = "MEMBERSHIP_ONLY"
) { override fun toString() = "ServerAiConversationPage(redacted)" }

/** Exact field construction mirrors history_contract.py; null optional fields are omitted. */
internal fun ServerAiHistoryData.toWire(): JsonObject = buildJsonObject {
    fun number(name: String, value: Double?, positive: Boolean = false, signed: Boolean = false) {
        if (value != null) {
            check(value.isFinite() && value <= 1_000_000 &&
                if (signed) value >= -1_000_000 else if (positive) value > 0 else value >= 0)
            put(name, value)
        }
    }
    fun code(name: String, value: String) { check(HistoryJson.code.matches(value)); put(name, value) }
    when (val data = this@toWire) {
        is ServerAiHistoryData.Glucose -> number("glucoseMmol", data.glucoseMmol, positive = true)
        is ServerAiHistoryData.Therapy -> {
            put("eventType", data.eventType.name)
            number("carbsGrams", data.carbsGrams)
            number("insulinUnits", data.insulinUnits)
            number("basalUnitsPerHour", data.basalUnitsPerHour)
            number("targetMmol", data.targetMmol, positive = true)
            number("durationMinutes", data.durationMinutes)
            data.deliveryState?.let { put("deliveryState", it.name) }
            val expected = when (data.eventType) {
                ServerAiTherapyEvent.CARBS -> setOf("carbsGrams")
                ServerAiTherapyEvent.BOLUS -> setOf("insulinUnits")
                ServerAiTherapyEvent.BASAL -> setOf("basalUnitsPerHour", "durationMinutes")
                ServerAiTherapyEvent.TEMP_TARGET -> setOf("targetMmol", "durationMinutes")
                ServerAiTherapyEvent.DELIVERY_STATE -> setOf("deliveryState")
                else -> emptySet()
            }
            check(listOfNotNull(data.carbsGrams?.let { "carbsGrams" }, data.insulinUnits?.let { "insulinUnits" },
                data.basalUnitsPerHour?.let { "basalUnitsPerHour" }, data.targetMmol?.let { "targetMmol" },
                data.durationMinutes?.let { "durationMinutes" }, data.deliveryState?.let { "deliveryState" }).toSet() == expected)
        }
        is ServerAiHistoryData.Meal -> {
            number("carbsGrams", data.carbsGrams)
            put("foodType", data.foodType.name); put("quantitySource", data.quantitySource.name)
            check((data.glycemicIndex == null) == (data.giSource == null))
            data.glycemicIndex?.let { check(it.isFinite() && it in 0.0..200.0); put("glycemicIndex", it) }
            data.giSource?.let { put("giSource", it.name) }
        }
        is ServerAiHistoryData.Forecast -> {
            check(data.horizonMinutes in setOf(5, 30, 60))
            put("horizonMinutes", data.horizonMinutes); number("valueMmol", data.valueMmol, positive = true)
            code("modelVersion", data.modelVersion)
            check((data.ciLowMmol == null) == (data.ciHighMmol == null))
            if (data.ciLowMmol != null && data.ciHighMmol != null) {
                check(data.ciLowMmol <= data.valueMmol && data.valueMmol <= data.ciHighMmol)
            }
            number("ciLowMmol", data.ciLowMmol, positive = true); number("ciHighMmol", data.ciHighMmol, positive = true)
        }
        is ServerAiHistoryData.Sensitivity -> {
            number("isfMmolPerUnit", data.isfMmolPerUnit, positive = true)
            number("crGramsPerUnit", data.crGramsPerUnit, positive = true)
            number("positiveIobUnits", data.positiveIobUnits); number("netIobUnits", data.netIobUnits, signed = true)
            number("cobGrams", data.cobGrams)
        }
        is ServerAiHistoryData.Activity -> {
            data.steps?.let { check(it in 0..1_000_000); put("steps", it) }
            number("activeMinutes", data.activeMinutes); number("activeKcal", data.activeKcal)
        }
        is ServerAiHistoryData.Device -> {
            number("sensorAgeHours", data.sensorAgeHours); number("setAgeHours", data.setAgeHours)
            data.deliveryState?.let { put("deliveryState", it.name) }
        }
        is ServerAiHistoryData.Alert -> {
            code("code", data.code); put("severity", data.severity.name); put("deliveryStatus", data.deliveryStatus.name)
        }
        is ServerAiHistoryData.Diagnostic -> { code("code", data.code); put("status", data.status.name) }
        is ServerAiHistoryData.Chat -> {
            HistoryJson.uuid(data.conversationId)
            check(data.text.length <= 16_000 && data.text.codePointCount(0, data.text.length) in 1..8000)
            HistoryJson.encodeUtf8(data.text)
            put("conversationId", data.conversationId); put("role", data.role.name)
            put("text", data.text); put("origin", "DEVICE_ARCHIVE")
        }
    }
}.also { check(it.isNotEmpty()) }

internal fun ServerAiHistoricalContext.toWire(): JsonObject = buildJsonObject {
    zoneId?.let {
        check(it.length in 1..64 && it in java.time.ZoneId.getAvailableZoneIds())
        put("zoneId", it)
    }
    sensorSessionId?.let { check(HistoryJson.code.matches(it)); put("sensorSessionId", it) }
    calibrationModelId?.let { check(HistoryJson.code.matches(it)); put("calibrationModelId", it) }
    glucoseBasis?.let { put("glucoseBasis", it.name) }
    inputModelVersion?.let { check(HistoryJson.code.matches(it)); put("inputModelVersion", it) }
}.also { check(it.isNotEmpty()) }

internal fun ServerAiHistoryRecord.toWire(now: Long): JsonObject {
    HistoryJson.uuid(recordId)
    check(revision > 0)
    HistoryJson.time(eventAtMs); HistoryJson.time(observedAtMs)
    check(now - HistoryJson.retentionMs < eventAtMs && eventAtMs <= observedAtMs && observedAtMs <= now)
    check(if (deleted) data == null else data != null && data.kind == kind)
    val wire = buildJsonObject {
        put("recordId", recordId); put("revision", revision); put("eventAtMs", eventAtMs); put("observedAtMs", observedAtMs)
        put("kind", kind.name); put("source", source.name); put("quality", quality.name); put("deleted", deleted)
        put("data", data?.toWire() ?: JsonNull)
        context?.let { put("context", it.toWire()) }
    }
    // Backend model_dump includes all historical-context defaults for record_limit,
    // even when the original wire object omits them.
    val fullContext = context?.toWire()?.let { c -> JsonObject(
        setOf("zoneId", "sensorSessionId", "calibrationModelId", "glucoseBasis", "inputModelVersion")
            .associateWith { c[it] ?: JsonNull }
    ) } ?: JsonNull
    check(HistoryJson.bytes(JsonObject(wire + ("context" to fullContext))).size <= 16_384)
    return wire
}
