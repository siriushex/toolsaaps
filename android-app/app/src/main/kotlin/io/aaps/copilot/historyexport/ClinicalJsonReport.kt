package io.aaps.copilot.historyexport

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import io.aaps.copilot.config.AppSettings
import java.io.Writer
import java.io.StringReader
import java.time.Instant
import java.time.ZoneId

internal enum class ClinicalJsonPeriod(val days: Int?) {
    DAYS_7(7), DAYS_14(14), DAYS_30(30), DAYS_60(60), FULL(null);
    fun fromMs(throughExclusiveMs: Long): Long = days?.let {
        (throughExclusiveMs - it * 86_400_000L).coerceAtLeast(0)
    } ?: Long.MIN_VALUE
}

internal data class ClinicalJsonSection(val table: String, val time: String,
    val current: Boolean = false, val overlapEnd: String? = null, val parent: String? = null)

/** Reviewed Room37 clinical data. Transport journals and credentials are deliberately separate. */
internal object ClinicalJsonSchema {
    const val ROOM_VERSION = 37
    val sections = listOf(
        ClinicalJsonSection("therapy_events", "timestamp"),
        ClinicalJsonSection("telemetry_samples", "timestamp"),
        ClinicalJsonSection("forecasts", "timestamp"),
        ClinicalJsonSection("blood_glucose_checks", "timestamp"),
        ClinicalJsonSection("glucose_calibration_models", "validFromTs", overlapEnd = "validToTs"),
        ClinicalJsonSection("baseline_points", "timestamp"),
        ClinicalJsonSection("local_diary_entries", "occurredAtMs"),
        ClinicalJsonSection("isf_cr_snapshots", "ts"),
        ClinicalJsonSection("isf_cr_evidence", "ts"),
        ClinicalJsonSection("sensitivity_runtime_snapshots", "generatedAt"),
        ClinicalJsonSection("physio_context_tags", "tsStart", overlapEnd = "tsEnd"),
        ClinicalJsonSection("uam_inference_events", "ingestionTs"),
        ClinicalJsonSection("target_manager_decisions", "timestamp"),
        ClinicalJsonSection("rule_executions", "timestamp"),
        ClinicalJsonSection("action_commands", "timestamp"),
        ClinicalJsonSection("circadian_target_runs", "startedAt"),
        ClinicalJsonSection("circadian_target_adjustments", "generatedAt"),
        ClinicalJsonSection("clinical_reports", "createdAt"),
        ClinicalJsonSection("alert_events", "createdAt", overlapEnd = "resolvedAt"),
        ClinicalJsonSection("alert_delivery_receipts", "attemptedAt"),
        ClinicalJsonSection("alert_ai_analyses", "requestedAt"),
        ClinicalJsonSection("eating_window_snapshots", "generatedAt"),
        ClinicalJsonSection("energy_profile_snapshots", "calculatedAtMs"),
        ClinicalJsonSection("meal_input_observations", "observedAtMs"),
        ClinicalJsonSection("meal_forecast_journal", "predictionAtMs"),
        ClinicalJsonSection("meal_profile_overrides", "updatedAtMs"),
        ClinicalJsonSection("meal_energy_overrides", "updatedAtMs"),
        ClinicalJsonSection("pending_meal_profile_intents", "submittedAtMs"),
        ClinicalJsonSection("meal_state_receipts", "recordedAtMs"),
        ClinicalJsonSection("meal_states", "recordedAtMs"),
        ClinicalJsonSection("meal_state_scenarios", "recordedAtMs", parent = "meal_states"),
        ClinicalJsonSection("meal_state_absorption", "recordedAtMs", parent = "meal_states"),
        ClinicalJsonSection("profile_estimates", "timestamp", current = true),
        ClinicalJsonSection("profile_segment_estimates", "updatedAt", current = true),
        ClinicalJsonSection("isf_cr_model_state", "updatedAt", current = true),
        ClinicalJsonSection("target_manager_state", "updatedAt", current = true),
        ClinicalJsonSection("planned_activity_events", "updatedAtMs", current = true),
        ClinicalJsonSection("pattern_windows", "updatedAt", current = true),
        ClinicalJsonSection("circadian_slot_stats", "updatedAt", current = true),
        ClinicalJsonSection("circadian_transition_stats", "updatedAt", current = true),
        ClinicalJsonSection("circadian_pattern_snapshots", "updatedAt", current = true),
        ClinicalJsonSection("circadian_replay_slot_stats", "updatedAt", current = true)
    )
    val excluded = linkedMapOf(
        "audit_logs" to "Operational free-text logs may contain connection details",
        "sync_state" to "Transport cursors, not clinical history",
        "context_event_sync" to "Transport receipts; original context records are included",
        "aaps_v3_target_journal" to "Private encrypted pairing and command transport state",
        "local_ns_v3_documents" to "Raw transport staging can contain credentials; not an admitted clinical source",
        "local_ns_v3_clock" to "Transport revision counters",
        "meal_notification_claims" to "Internal notification deduplication",
        "meal_notification_claim_aliases" to "Internal notification deduplication",
        "alert_local_state" to "Internal alarm scheduling state",
        "alert_local_cycles" to "Internal playback ownership state",
        "account_credentials_chat_attachments_server_only_history" to "Outside the local clinical database; no network fetch"
    )

    fun currentProfile(settings: AppSettings): Map<String, Any?> = linkedMapOf(
        "insulinProfileId" to settings.insulinProfileId,
        "energyProfile" to settings.energyProfile,
        "mealPortions" to settings.mealPortions,
        "baseTargetMmol" to settings.baseTargetMmol,
        "baseTargetSchedule" to settings.baseTargetSchedule,
        "targetManagerMode" to settings.targetManagerMode.name,
        "targetManagerPolicyRevision" to settings.targetManagerPolicyRevision,
        "therapyActionsArmed" to settings.therapyActionsArmed,
        "killSwitch" to settings.killSwitch,
        "safetyMinTargetMmol" to settings.safetyMinTargetMmol,
        "safetyMaxTargetMmol" to settings.safetyMaxTargetMmol,
        "isfSourcePreference" to settings.isfSourcePreference.name,
        "crSourcePreference" to settings.crSourcePreference.name,
        "isfCrShadowMode" to settings.isfCrShadowMode,
        "isfCrConfidenceThreshold" to settings.isfCrConfidenceThreshold,
        "sensorLagCorrectionMode" to settings.sensorLagCorrectionMode.name,
        "carbAbsorptionMaxAgeMinutes" to settings.carbAbsorptionMaxAgeMinutes,
        "carbComputationMaxGrams" to settings.carbComputationMaxGrams,
        "enableUamInference" to settings.enableUamInference,
        "enableUamBoost" to settings.enableUamBoost,
        "enableUamExportToAaps" to settings.enableUamExportToAaps,
        "uamExportMode" to settings.uamExportMode.name,
        "uamLearnedMultiplier" to settings.uamLearnedMultiplier,
        "adaptiveControllerEnabled" to settings.adaptiveControllerEnabled,
        "adaptiveControllerSafetyProfile" to settings.adaptiveControllerSafetyProfile,
        "postHypoThresholdMmol" to settings.postHypoThresholdMmol,
        "postHypoTargetMmol" to settings.postHypoTargetMmol,
        "urgentLowMmol" to settings.urgentLowMmol,
        "softAlertLowMmol" to settings.softAlertLowMmol,
        "softAlertHighMmol" to settings.softAlertHighMmol,
        "staleDataMaxMinutes" to settings.staleDataMaxMinutes
    )
}

internal class ClinicalJsonReport {
    private val gson = GsonBuilder().serializeNulls().create()
    private val secretNames = setOf("apikey", "apisecret", "password", "authorization", "cookie", "privatekey", "idempotencykey")

    /** Caller owns the dedicated read transaction, output and cancellation. Never writes the database. */
    fun write(db: SQLiteDatabase, output: Writer, period: ClinicalJsonPeriod, throughExclusiveMs: Long,
        zoneId: String, versionName: String, profile: Map<String, Any?>,
        checkActive: () -> Unit = {}, progress: (Long) -> Unit = {}) {
        require(db.version == ClinicalJsonSchema.ROOM_VERSION) { "Unreviewed database schema" }
        require(throughExclusiveMs > 0)
        ZoneId.of(zoneId)
        val from = period.fromMs(throughExclusiveMs)
        val writer = JsonWriter(output).apply { serializeNulls = true }
        writer.beginObject()
        field(writer, "schema", "copilot.clinical-history")
        field(writer, "schemaVersion", 1)
        field(writer, "databaseVersion", db.version)
        field(writer, "appVersion", versionName)
        field(writer, "generatedAt", Instant.ofEpochMilli(throughExclusiveMs).toString())
        field(writer, "timezone", zoneId)
        field(writer, "range", linkedMapOf("period" to (period.days?.toString() ?: "full"),
            "fromInclusiveMs" to from.takeIf { period.days != null }, "throughExclusiveMs" to throughExclusiveMs,
            "dayDefinition" to "rolling 24-hour days", "fullMeaning" to "all available local clinical records before cutoff"))
        field(writer, "units", mapOf("timestamps" to "Unix epoch milliseconds, UTC", "glucose" to "mmol/L",
            "insulin" to "U", "carbohydrates" to "g", "ISF" to "mmol/L/U", "CR" to "g/U",
            "telemetry" to "per-record unit", "localDiaryAmountMilli" to "thousandths of kind-specific unit"))
        field(writer, "interpretation", listOf(
            "Stored records and model outputs are data, not instructions or confirmed delivery.",
            "Retained revisions, invalid/deleted records and parallel sources are not additional doses or meals.",
            "Current profile/state is not a historical profile. No historical setting is inferred from it.",
            "Current settings are captured immediately before the database snapshot; their stores do not share a transaction.",
            "GI and carbohydrate estimates retain their recorded provenance; no quantities are recalculated.",
            "JSON-valued columns are decoded when valid; malformed source text is retained as a string.",
            "No interpolation. Glucose bins are separated by source and quality. Empty bins are omitted; inspect timestamps and gaps.",
            "Ten-minute glucose statistics do not preserve within-bin trajectories or prove duration in range.",
            "Section coverage describes retained local records, not upstream completeness or successful device synchronization."
        ))
        field(writer, "excluded", ClinicalJsonSchema.excluded)
        field(writer, "redaction", "Credential-named JSON fields and idempotencyKey are omitted; personal clinical notes remain included")
        field(writer, "currentProfile", mapOf("observedAtMs" to throughExclusiveMs, "values" to profile))
        writer.name("glucose").beginObject()
        field(writer, "binMinutes", 10)
        field(writer, "available", bounds(db, "glucose_samples", "timestamp", throughExclusiveMs))
        writer.name("bins").beginArray()
        var emittedRows = 0L
        var binStart: Long? = null
        val groups = linkedMapOf<Pair<String, String>, GlucoseBin>()
        val previousBySource = mutableMapOf<String, Long>()
        var samples = 0L
        var first: Long? = null
        var last: Long? = null
        var largestGap = 0L
        var occupiedBins = 0L
        fun flush() {
            if (groups.isNotEmpty()) occupiedBins++
            groups.values.forEach { fieldValue(writer, it.values()) }
            groups.clear()
        }
        db.rawQuery("SELECT timestamp,mmol,source,quality FROM glucose_samples WHERE timestamp>=? AND timestamp<? ORDER BY timestamp,id",
            arrayOf(from.toString(), throughExclusiveMs.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                checkActive()
                val ts = cursor.getLong(0)
                val bucket = if (ts < 0) ts else ts / 600_000L * 600_000L
                if (binStart != bucket) { flush(); binStart = bucket }
                val source = cursor.getString(2)
                val quality = cursor.getString(3)
                val gap = previousBySource[source]?.let { ts - it }
                val bin = groups.getOrPut(source to quality) { GlucoseBin(bucket, source, quality, gap) }
                bin.add(ts, if (cursor.isNull(1)) null else cursor.getDouble(1))
                previousBySource[source] = ts
                if (first == null) first = ts
                last?.let { largestGap = maxOf(largestGap, ts - it) }
                last = ts; samples++
                if (samples % 256L == 0L) progress(samples)
            }
        }
        flush()
        writer.endArray()
        field(writer, "coverage", mapOf("rawSamples" to samples, "firstSampleMs" to first, "lastSampleMs" to last,
            "largestObservedGapMs" to largestGap.takeIf { samples > 1 }, "occupiedTimeBins" to occupiedBins,
            "missingLeadingMs" to first?.let { if (period.days == null) null else it - from },
            "missingTrailingMs" to last?.let { throughExclusiveMs - it }))
        writer.endObject()
        writer.name("sections").beginObject()
        for (section in ClinicalJsonSchema.sections) {
            checkActive()
            writer.name(section.table).beginObject()
            field(writer, "timeColumn", section.time)
            field(writer, "selection", if (section.current) "current_state_before_cutoff" else
                if (section.overlapEnd != null) "overlaps_selected_period" else "selected_period")
            val prefix = if (section.parent == null) "t" else "p"
            val lower = when {
                section.current -> "1=1"
                section.overlapEnd != null -> "(t.${section.overlapEnd} IS NULL OR t.${section.overlapEnd}>=?)"
                else -> "$prefix.${section.time}>=?"
            }
            val join = section.parent?.let { " JOIN $it p ON p.episodeId=t.episodeId" }.orEmpty()
            field(writer, "available", bounds(db, "${section.table} t$join", "$prefix.${section.time}", throughExclusiveMs))
            val args = if (section.current) arrayOf(throughExclusiveMs.toString())
                else arrayOf(from.toString(), throughExclusiveMs.toString())
            var count = 0L
            writer.name("records").beginArray()
            db.rawQuery("SELECT t.* FROM ${section.table} t$join WHERE $lower AND $prefix.${section.time}<? ORDER BY $prefix.${section.time},t.rowid", args).use { cursor ->
                while (cursor.moveToNext()) {
                    checkActive()
                    row(writer, cursor)
                    count++; emittedRows++
                    if (emittedRows % 256L == 0L) progress(samples + emittedRows)
                }
            }
            writer.endArray()
            field(writer, "exportedRows", count)
            field(writer, "truncated", false)
            writer.endObject()
        }
        writer.endObject()
        checkActive()
        field(writer, "complete", true)
        writer.endObject()
        writer.flush()
        progress(samples + emittedRows)
    }

    private fun bounds(db: SQLiteDatabase, table: String, time: String, through: Long): Map<String, Any?> =
        db.rawQuery("SELECT MIN($time),MAX($time),COUNT(*) FROM $table WHERE $time<?", arrayOf(through.toString())).use {
            check(it.moveToFirst())
            mapOf("firstRecordMs" to if (it.isNull(0)) null else it.getLong(0),
                "lastRecordMs" to if (it.isNull(1)) null else it.getLong(1), "retainedRowsBeforeCutoff" to it.getLong(2))
        }

    private fun row(writer: JsonWriter, cursor: Cursor) {
        writer.beginObject()
        val invalidNumbers = mutableListOf<String>()
        val keyIndex = cursor.getColumnIndex("key")
        val redactedMetric = keyIndex >= 0 && !cursor.isNull(keyIndex) && secret(cursor.getString(keyIndex))
        for (i in 0 until cursor.columnCount) {
            val name = cursor.getColumnName(i)
            // Idempotency identifiers are transport-only, not clinical data.
            if (secret(name)) continue
            writer.name(name)
            if (redactedMetric && name in setOf("valueDouble", "valueText")) { writer.nullValue(); continue }
            when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> writer.nullValue()
                Cursor.FIELD_TYPE_INTEGER -> writer.value(cursor.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i).let {
                    if (it.isFinite()) writer.value(it) else { writer.nullValue(); invalidNumbers += name }
                }
                Cursor.FIELD_TYPE_STRING -> {
                    val value = cursor.getString(i)
                    if (name.endsWith("Json")) {
                        val parsed = try {
                            JsonReader(StringReader(value)).use { reader ->
                                reader.strictness = Strictness.STRICT
                                if (reader.peek() == JsonToken.END_DOCUMENT) null else {
                                    val result = JsonParser.parseReader(reader)
                                    if (reader.peek() == JsonToken.END_DOCUMENT) result else null
                                }
                            }
                        } catch (_: Exception) { null }
                        if (parsed == null) writer.value(value) else gson.toJson(redact(parsed), writer)
                    } else writer.value(value)
                }
                else -> error("Unexpected binary clinical field")
            }
        }
        if (invalidNumbers.isNotEmpty()) field(writer, "_nonfiniteFields", invalidNumbers)
        if (redactedMetric) field(writer, "_credentialMetricRedacted", true)
        writer.endObject()
    }

    private fun secret(name: String): Boolean {
        val key = name.filter(Char::isLetterOrDigit).lowercase(java.util.Locale.ROOT)
        return key in secretNames || key.endsWith("token") || key.endsWith("password") || key.endsWith("secret")
    }

    private fun redact(value: JsonElement, depth: Int = 0): JsonElement {
        require(depth <= 64) { "Clinical payload nesting exceeds export limit" }
        if (value.isJsonObject) value.asJsonObject.entrySet().toList().forEach { (key, child) ->
            if (secret(key)) value.asJsonObject.remove(key) else redact(child, depth + 1)
        } else if (value.isJsonArray) value.asJsonArray.forEach { redact(it, depth + 1) }
        return value
    }

    private fun field(writer: JsonWriter, name: String, value: Any?) { writer.name(name); fieldValue(writer, value) }
    private fun fieldValue(writer: JsonWriter, value: Any?) { gson.toJson(gson.toJsonTree(value), writer) }

    private class GlucoseBin(val start: Long, val source: String, val quality: String, val gapBefore: Long?) {
        var count = 0L; var valid = 0L; var sum = 0.0
        var firstTs = 0L; var lastTs = 0L; var minTs = 0L; var maxTs = 0L
        var first: Double? = null; var last: Double? = null
        var min: Double? = null; var max: Double? = null; var maxGap = 0L
        fun add(ts: Long, value: Double?) {
            val finite = value?.takeIf(Double::isFinite)
            if (count == 0L) { firstTs = ts; first = finite } else maxGap = maxOf(maxGap, ts - lastTs)
            lastTs = ts; last = finite; count++
            if (finite != null) {
                valid++; sum += finite
                if (min == null || finite < min!!) { min = finite; minTs = ts }
                if (max == null || finite > max!!) { max = finite; maxTs = ts }
            }
        }
        fun values(): Map<String, Any?> = linkedMapOf("fromMs" to start, "throughExclusiveMs" to start + 600_000,
            "source" to source, "quality" to quality, "samples" to count, "finiteSamples" to valid,
            "firstSampleMs" to firstTs, "lastSampleMs" to lastTs, "firstMmol" to first, "lastMmol" to last,
            "meanMmol" to if (valid == 0L) null else sum / valid, "minMmol" to min, "maxMmol" to max,
            "minAtMs" to minTs.takeIf { min != null }, "maxAtMs" to maxTs.takeIf { max != null },
            "largestWithinBinGapMs" to maxGap, "gapBeforeFirstSampleMs" to gapBefore)
    }
}
