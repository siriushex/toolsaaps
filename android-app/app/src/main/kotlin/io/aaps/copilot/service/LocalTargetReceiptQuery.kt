package io.aaps.copilot.service

import io.aaps.copilot.data.repository.HistoryJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Diagnostic query derived from the exact prepare and the commit acknowledged by the master. */
@Serializable
internal data class LocalTargetReceiptQuery(val marker: String, val issued: Long, val ackAt: Long, val mgdl: Double, val minutes: Int, val reason: String) {
    override fun toString() = "LocalTargetReceiptQuery(redacted)"
    fun fresh(now: Long) = now in 1..HistoryJson.maxTime && issued in maxOf(1L, now - 86_400_000L)..now &&
        ackAt in issued..minOf(now, issued + 13_000L)
    companion object {
        private val keys = Regex("(?:TargetManager\\.v1:[0-9a-f]{32}|manual:meal:[A-Za-z0-9_-]{1,80}:eating-soon)")
        private val reasons = mapOf("Automation" to "AUTOMATION", "Hypo" to "HYPOGLYCEMIA", "Activity" to "ACTIVITY", "Eating Soon" to "EATING_SOON")
        fun from(key: String, prepare: JsonObject?, commit: JsonObject?, ack: JsonObject): LocalTargetReceiptQuery? = try {
            require(keys.matches(key) && prepare != null && commit != null)
            val issued = HistoryJson.long(commit.getValue("timestamp"))
            val ackAt = HistoryJson.long(ack.getValue("timestamp"))
            require(issued in 1..HistoryJson.maxTime && HistoryJson.string(prepare.getValue("type")) == "batch_prepare" &&
                HistoryJson.string(commit.getValue("type")) == "bolus_commit")
            val body = HistoryJson.parse(HistoryJson.string(prepare.getValue("payload")).encodeToByteArray(), 8192)
            HistoryJson.fields(body, setOf("type", "actions"))
            require(HistoryJson.string(body.getValue("type")) == "batch_prepare")
            val action = HistoryJson.obj(HistoryJson.array(body.getValue("actions")).single())
            HistoryJson.fields(action, setOf("type", "reason", "lowMgdl", "highMgdl", "durationMinutes", "startOffsetMinutes", "notes"))
            val marker = HistoryJson.string(action.getValue("notes"))
            val mgdl = HistoryJson.double(action.getValue("lowMgdl"))
            val duration = HistoryJson.long(action.getValue("durationMinutes"))
            require(HistoryJson.string(action.getValue("type")) == "temp_target" && marker == "copilot:$key" &&
                mgdl.isFinite() && mgdl > 0 && mgdl <= 1000 && HistoryJson.double(action.getValue("highMgdl")) == mgdl &&
                duration in 1..1440 && HistoryJson.long(action.getValue("startOffsetMinutes")) == 0L)
            require(ackAt in issued..issued + 13_000L)
            LocalTargetReceiptQuery(marker, issued, ackAt, mgdl, duration.toInt(), reasons.getValue(HistoryJson.string(action.getValue("reason"))))
        } catch (_: RuntimeException) { null }
    }
}
