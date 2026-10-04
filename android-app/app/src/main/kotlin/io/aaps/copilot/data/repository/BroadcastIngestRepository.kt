package io.aaps.copilot.data.repository

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.room.withTransaction
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.SyncStateEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.service.PowerSaveRuntimeState
import io.aaps.copilot.service.TherapyActionRuntimeState
import io.aaps.copilot.service.CopilotDatabaseIntegrityManager
import io.aaps.copilot.service.CopilotDatabaseSnapshotManager
import io.aaps.copilot.service.CopilotStorageCleanupManager
import io.aaps.copilot.scheduler.ClinicalInputInvalidationSource
import io.aaps.copilot.util.GlucoseUnitNormalizer
import io.aaps.copilot.widget.CopilotGlucoseWidgetUpdater
import kotlin.math.abs
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal const val BROADCAST_INVALIDATION_OUTBOX_SOURCE =
    "broadcast_ingest_invalidation_pending"
private const val BROADCAST_INVALIDATION_OUTBOX_MARKER = 1L

internal fun selectBroadcastGlucoseTimestamp(
    action: String,
    extras: Map<String, String>,
    fallbackNowMs: Long
): Long? {
    val isStatus = (
        action == "info.nightscout.androidaps.status" ||
            action == "app.aaps.status"
        )
    if (isStatus) {
        // AAPS timestamp is relay creation time; date is the actual CGM sample time.
        val sampleDate = extras.entries
            .firstOrNull { it.key.equals("date", ignoreCase = true) }
            ?.value
        return parseBroadcastTimestamp(sampleDate)
    }
    val preferredKeys = listOf("timestamp", "time", "date", "mills", "created_at")
    preferredKeys.forEach { preferredKey ->
        val raw = extras.entries
            .firstOrNull { it.key.equals(preferredKey, ignoreCase = true) }
            ?.value
        parseBroadcastTimestamp(raw)?.let { return it }
    }
    return fallbackNowMs
}

private fun parseBroadcastTimestamp(raw: String?): Long? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val numeric = trimmed.toLongOrNull()
        ?: trimmed.toDoubleOrNull()?.toLong()
    if (numeric != null) {
        return (if (numeric < 10_000_000_000L) numeric * 1_000L else numeric)
            .takeIf { it > 0L }
    }
    return runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrNull()
}

internal data class AapsCarbStatusSignal(
    val revisionId: Long?,
    val hasExplicitTherapyPayload: Boolean
)

internal fun parseAapsCarbStatusSignal(
    action: String,
    extras: Map<String, String>
): AapsCarbStatusSignal {
    val isAapsStatus = action == "info.nightscout.androidaps.status" ||
        action == "app.aaps.status"
    val revisionId = if (isAapsStatus) {
        extras["aapsCarbRevisionId"]
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
    } else {
        null
    }
    val hasExplicitTherapyPayload = action.startsWith("info.nightscout.androidaps.") &&
        (
            exactBroadcastValue(extras, "eventType", "event_type", "type") != null ||
                exactBroadcastValue(extras, "enteredCarbs", "mealCarbs", "grams") != null ||
                exactBroadcastValue(
                    extras,
                    "enteredInsulin",
                    "bolusUnits",
                    "insulinUnits",
                    "units"
                ) != null ||
                exactBroadcastValue(extras, "duration", "durationInMinutes") != null ||
                exactBroadcastValue(
                    extras,
                    "targetBottom",
                    "target_bottom",
                    "targetLow"
                ) != null ||
                exactBroadcastValue(
                    extras,
                    "targetTop",
                    "target_top",
                    "targetHigh"
                ) != null ||
                exactBroadcastValue(extras, "reason", "notes", "enteredBy")
                    ?.contains("uam_engine", ignoreCase = true) == true
            )
    return AapsCarbStatusSignal(
        revisionId = revisionId,
        hasExplicitTherapyPayload = hasExplicitTherapyPayload
    )
}

private fun exactBroadcastValue(
    extras: Map<String, String>,
    vararg keys: String
): String? = keys.firstNotNullOfOrNull { key ->
    extras.entries
        .firstOrNull { it.key.equals(key, ignoreCase = true) }
        ?.value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

internal class BroadcastReactiveInvalidationPolicy(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val telemetryIntervalMs: Long = 5 * 60_000L
) {
    private val lastTelemetryOnlyReactiveAtMs = AtomicLong(UNCLAIMED)

    fun shouldInvalidate(
        result: BroadcastIngestRepository.IngestResult,
        telemetryOnlyCoalescedAction: Boolean
    ): Boolean {
        if (!shouldPersistOutbox(result, telemetryOnlyCoalescedAction)) return false
        recordSuccessfulInvalidation(result, telemetryOnlyCoalescedAction)
        return true
    }

    fun shouldPersistOutbox(
        result: BroadcastIngestRepository.IngestResult,
        telemetryOnlyCoalescedAction: Boolean
    ): Boolean {
        if (result.therapyImported > 0) return true
        if (result.glucoseImported > 0) return result.currentGlucoseChanged
        if (result.telemetryImported <= 0) return false
        if (!telemetryOnlyCoalescedAction) return true
        return slotAvailable(lastTelemetryOnlyReactiveAtMs, telemetryIntervalMs)
    }

    fun recordSuccessfulInvalidation(
        result: BroadcastIngestRepository.IngestResult,
        telemetryOnlyCoalescedAction: Boolean
    ) {
        if (result.therapyImported > 0 || result.currentGlucoseChanged) return
        if (result.telemetryImported > 0 && telemetryOnlyCoalescedAction) {
            lastTelemetryOnlyReactiveAtMs.set(nowMs())
        }
    }

    private fun slotAvailable(slot: AtomicLong, intervalMs: Long): Boolean {
        val now = nowMs()
        val previous = slot.get()
        return previous == UNCLAIMED || now - previous >= intervalMs
    }

    private companion object {
        const val UNCLAIMED = -1L
    }
}

internal suspend fun completeBroadcastIngestAfterPersistence(
    result: BroadcastIngestRepository.IngestResult,
    shouldInvalidate: Boolean,
    onClinicalInputPersisted: suspend () -> Unit
): BroadcastIngestRepository.IngestResult {
    val hasDurableMutation = result.glucoseImported > 0 ||
        result.therapyImported > 0 ||
        result.telemetryImported > 0
    if (!hasDurableMutation || !shouldInvalidate) return result
    onClinicalInputPersisted()
    return result.copy(reactiveInvalidationRequested = true)
}

internal suspend fun persistBroadcastIngestAndComplete(
    persistence: suspend () -> BroadcastIngestRepository.IngestResult,
    shouldInvalidate: (BroadcastIngestRepository.IngestResult) -> Boolean,
    onClinicalInputPersisted: suspend () -> Unit
): BroadcastIngestRepository.IngestResult {
    val persisted = persistence()
    return completeBroadcastIngestAfterPersistence(
        result = persisted,
        shouldInvalidate = shouldInvalidate(persisted),
        onClinicalInputPersisted = onClinicalInputPersisted
    )
}

class BroadcastIngestRepository internal constructor(
    private val context: Context,
    private val db: CopilotDatabase,
    private val auditLogger: AuditLogger,
    private val aapsCarbHistorySyncRepository: AapsCarbHistorySyncRepository? = null,
    private val onClinicalInputPersisted: suspend (ClinicalInputInvalidationSource) -> Boolean = { true },
    private val beforeOutboxPersist: suspend () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val logTag = "BroadcastIngestDb"
    private val ingestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ingestQueue = Channel<Intent>(
        capacity = INGEST_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val reactiveInvalidationPolicy = BroadcastReactiveInvalidationPolicy()
    private val aapsCarbRevisionSignals = AapsCarbRevisionSignalCoordinator()
    private val ingestMutex = Mutex()

    init {
        ingestScope.launch {
            ingestMutex.withLock {
                runCatching { dispatchPendingInvalidation(null, false) }
                    .onFailure { error ->
                        auditLogger.warn(
                            "broadcast_invalidation_recovery_failed",
                            mapOf("error" to (error.message ?: error::class.java.simpleName))
                        )
                    }
            }
        }
        ingestScope.launch {
            for (intent in ingestQueue) {
                processQueuedIngest(intent)
            }
        }
    }

    fun enqueue(intent: Intent): Boolean {
        val queuedIntent = Intent(intent)
        val queued = ingestQueue.trySend(queuedIntent).isSuccess
        if (!queued) {
            Log.w(logTag, "enqueue:rejected action=${intent.action.orEmpty()}")
            ingestScope.launch {
                auditLogger.warn(
                    "broadcast_ingest_queue_failed",
                    mapOf("action" to intent.action.orEmpty(), "reason" to "queue_rejected")
                )
            }
        }
        return queued
    }

    suspend fun ingest(intent: Intent): IngestResult {
        return ingestMutex.withLock {
            val action = intent.action.orEmpty()
            val telemetryOnlyCoalescedAction = isTelemetryOnlyCoalescedAction(action)
            val persisted = persistIngest(intent, telemetryOnlyCoalescedAction)
            val dispatched = dispatchPendingInvalidation(persisted, telemetryOnlyCoalescedAction)
            persisted.copy(reactiveInvalidationRequested = dispatched)
        }
    }

    private suspend fun persistIngest(
        intent: Intent,
        telemetryOnlyCoalescedAction: Boolean
    ): IngestResult {
        val action = intent.action.orEmpty()
        if (action.isBlank()) {
            auditLogger.warn("broadcast_ingest_skipped", mapOf("reason" to "missing_action"))
            return IngestResult(0, 0, 0, "missing_action")
        }

        runPeriodicMaintenanceIfNeeded()

        val extras = flattenExtras(intent)
        val source = resolveSource(action)
        val aapsCarbStatusSignal = parseAapsCarbStatusSignal(action, extras)
        val glucose = parseGlucose(action, extras)
        val therapy = parseTherapy(action, extras)
        val parsedTelemetry = parseTelemetry(action, source, extras) +
            buildAapsCarbRevisionTelemetry(
                action = action,
                source = source,
                revisionId = aapsCarbStatusSignal.revisionId
            )
        val glucoseInputTelemetry = mutableListOf<TelemetrySampleEntity>()

        val result = db.withTransaction {
            val ingestNow = clock()
            val currentGlucoseBefore = if (glucose != null) {
                db.glucoseDao().latestValidDistinctAtOrBefore(ingestNow, 1).firstOrNull()
            } else {
                null
            }
            var importedGlucose = 0
            var importedGlucoseMmol: Double? = null
            var importedTherapy = 0
            var importedTelemetry = 0

            glucose?.let { parsed ->
                val sample = parsed.sample
                val outlier = isGlucoseOutlier(sample)
                if (outlier) {
                    auditLogger.warn(
                        "broadcast_glucose_outlier_skipped",
                        mapOf(
                            "action" to action,
                            "source" to sample.source,
                            "timestamp" to sample.timestamp,
                            "mmol" to sample.mmol
                        )
                    )
                } else {
                    val existing = db.glucoseDao()
                        .bySourceAndTimestamp(sample.source, sample.timestamp)
                    if (existing != null) {
                        val differs = abs(existing.mmol - sample.mmol) > GLUCOSE_REPLACE_EPSILON
                        if (differs || existing.quality != sample.quality) {
                            db.glucoseDao()
                                .deleteBySourceAndTimestamp(sample.source, sample.timestamp)
                            db.glucoseDao().upsertAll(listOf(sample))
                            importedGlucose = 1
                            importedGlucoseMmol = sample.mmol
                            glucoseInputTelemetry += buildGlucoseInputTelemetry(parsed)
                        }
                    } else {
                        db.glucoseDao().upsertAll(listOf(sample))
                        importedGlucose = 1
                        importedGlucoseMmol = sample.mmol
                        glucoseInputTelemetry += buildGlucoseInputTelemetry(parsed)
                    }
                }
            }
            if (therapy.isNotEmpty()) {
                val distinctTherapy = therapy.distinctBy { it.id }
                val existingById = db.therapyDao().byIds(distinctTherapy.map { it.id })
                    .associateBy { it.id }
                val changedTherapy = distinctTherapy.filter { existingById[it.id] != it }
                if (changedTherapy.isNotEmpty()) {
                    db.therapyDao().upsertAll(changedTherapy)
                    importedTherapy = changedTherapy.size
                }
            }
            val telemetry = if (glucoseInputTelemetry.isEmpty()) {
                parsedTelemetry
            } else {
                parsedTelemetry + glucoseInputTelemetry
            }
            if (telemetry.isNotEmpty()) {
                val distinctTelemetry = telemetry.distinctBy { it.id }
                val existingById = db.telemetryDao().byIds(distinctTelemetry.map { it.id })
                    .associateBy { it.id }
                val changedTelemetry = distinctTelemetry.filter { existingById[it.id] != it }
                if (changedTelemetry.isNotEmpty()) {
                    db.telemetryDao().upsertAll(changedTelemetry)
                    importedTelemetry = changedTelemetry.size
                }
            }

            val currentGlucoseAfter = if (importedGlucose > 0) {
                db.glucoseDao().latestValidDistinctAtOrBefore(ingestNow, 1).firstOrNull()
            } else {
                null
            }
            val currentGlucoseChanged = currentGlucoseAfter != null &&
                currentGlucoseAfter.copy(id = 0L) != currentGlucoseBefore?.copy(id = 0L)
            val persisted = if (
                importedGlucose == 0 && importedTherapy == 0 && importedTelemetry == 0
            ) {
                IngestResult(0, 0, 0, "no_supported_payload")
            } else {
                IngestResult(
                    importedGlucose,
                    importedTherapy,
                    importedTelemetry,
                    null,
                    importedGlucoseMmol,
                    currentGlucoseChanged = currentGlucoseChanged
                )
            }
            if (importedGlucose > 0 || importedTherapy > 0 || importedTelemetry > 0) {
                beforeOutboxPersist()
                if (
                    reactiveInvalidationPolicy.shouldPersistOutbox(
                        result = persisted,
                        telemetryOnlyCoalescedAction = telemetryOnlyCoalescedAction
                    )
                ) {
                    db.syncStateDao().upsert(
                        SyncStateEntity(
                            source = BROADCAST_INVALIDATION_OUTBOX_SOURCE,
                            lastSyncedTimestamp = BROADCAST_INVALIDATION_OUTBOX_MARKER
                        )
                    )
                }
            }
            persisted
        }
        aapsCarbStatusSignal.revisionId?.let(::signalAapsCarbRevision)

        if (
            result.glucoseImported == 0 &&
            result.therapyImported == 0 &&
            result.telemetryImported == 0
        ) {
            auditLogger.warn(
                "broadcast_ingest_no_data",
                mapOf("action" to action, "keys" to extras.keys.take(20))
            )
            return result
        }

        logIngestCompleted(
            action = action,
            glucoseImported = result.glucoseImported,
            therapyImported = result.therapyImported,
            telemetryImported = result.telemetryImported
        )
        return result
    }

    private suspend fun dispatchPendingInvalidation(
        persisted: IngestResult?,
        telemetryOnlyCoalescedAction: Boolean
    ): Boolean {
        if (db.syncStateDao().bySource(BROADCAST_INVALIDATION_OUTBOX_SOURCE) == null) {
            return false
        }
        val accepted = onClinicalInputPersisted(ClinicalInputInvalidationSource.BROADCAST_INGEST)
        if (!accepted) return false
        db.withTransaction {
            db.syncStateDao().deleteBySource(BROADCAST_INVALIDATION_OUTBOX_SOURCE)
        }
        persisted?.let {
            reactiveInvalidationPolicy.recordSuccessfulInvalidation(
                result = it,
                telemetryOnlyCoalescedAction = telemetryOnlyCoalescedAction
            )
        }
        return true
    }

    private suspend fun processQueuedIngest(intent: Intent) {
        runCatching {
            val result = ingest(intent)
            if (result.glucoseImported > 0 || result.therapyImported > 0 || result.telemetryImported > 0) {
                val action = intent.action.orEmpty()
                val shouldRunReactiveAutomation = result.reactiveInvalidationRequested
                if (
                    TherapyActionRuntimeState.isArmed() &&
                    !PowerSaveRuntimeState.isActive() &&
                    (
                        result.glucoseImported > 0 ||
                            result.therapyImported > 0 ||
                            shouldRunReactiveAutomation
                        )
                ) {
                    CopilotGlucoseWidgetUpdater.requestUpdate(context.applicationContext)
                }
            }
        }.onFailure { error ->
            auditLogger.warn(
                "broadcast_ingest_async_failed",
                mapOf(
                    "action" to intent.action.orEmpty(),
                    "message" to (error.message ?: error::class.java.simpleName)
                )
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun flattenExtras(intent: Intent): Map<String, String> {
        val bundle = intent.extras ?: return emptyMap()
        val raw = linkedMapOf<String, String>()
        bundle.keySet().forEach { key ->
            flattenExtraValue(prefix = key, value = bundle.get(key), out = raw)
        }
        return raw
    }

    private fun flattenExtraValue(
        prefix: String,
        value: Any?,
        out: MutableMap<String, String>
    ) {
        when (value) {
            null -> return
            is Bundle -> {
                value.keySet().forEach { key ->
                    val nextPrefix = if (prefix.isBlank()) key else "$prefix.$key"
                    @Suppress("DEPRECATION")
                    flattenExtraValue(nextPrefix, value.get(key), out)
                }
            }
            is Map<*, *> -> {
                value.forEach { (k, v) ->
                    val mapKey = k?.toString()?.trim().orEmpty()
                    if (mapKey.isEmpty()) return@forEach
                    val nextPrefix = if (prefix.isBlank()) mapKey else "$prefix.$mapKey"
                    flattenExtraValue(nextPrefix, v, out)
                }
            }
            is Array<*> -> value.forEachIndexed { index, item ->
                val nextPrefix = "$prefix[$index]"
                flattenExtraValue(nextPrefix, item, out)
            }
            is Iterable<*> -> value.forEachIndexed { index, item ->
                val nextPrefix = "$prefix[$index]"
                flattenExtraValue(nextPrefix, item, out)
            }
            is String -> {
                val text = value.trim()
                if (text.isNotEmpty()) {
                    out[prefix] = text
                    parseJsonPayload(text).forEach { (k, v) ->
                        out.putIfAbsent(k, v)
                        if (prefix.isNotBlank()) out.putIfAbsent("$prefix.$k", v)
                    }
                }
            }
            else -> if (prefix.isNotBlank()) {
                out[prefix] = value.toString()
            }
        }
    }

    private fun parseJsonPayload(raw: String): Map<String, String> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyMap()
        return runCatching {
            when {
                trimmed.startsWith("{") -> {
                    val out = linkedMapOf<String, String>()
                    flattenJsonValue(prefix = "", value = JSONObject(trimmed), out = out)
                    out
                }
                trimmed.startsWith("[") -> {
                    val array = JSONArray(trimmed)
                    if (array.length() == 0) {
                        emptyMap()
                    } else {
                        val out = linkedMapOf<String, String>()
                        flattenJsonValue(prefix = "", value = array, out = out)
                        out
                    }
                }
                else -> emptyMap()
            }
        }.getOrDefault(emptyMap())
    }

    private fun flattenJsonValue(prefix: String, value: Any?, out: MutableMap<String, String>) {
        when (value) {
            null -> return
            is JSONObject -> {
                val iterator = value.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    val nextPrefix = if (prefix.isBlank()) key else "$prefix.$key"
                    flattenJsonValue(nextPrefix, value.opt(key), out)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val nextPrefix = if (prefix.isBlank()) "[$index]" else "$prefix[$index]"
                    flattenJsonValue(nextPrefix, value.opt(index), out)
                }
            }
            else -> if (prefix.isNotBlank()) {
                out[prefix] = value.toString()
            }
        }
    }

    private fun parseGlucose(action: String, extras: Map<String, String>): ParsedGlucose? {
        if (!isSupportedGlucoseAction(action)) return null
        if (action.endsWith("BgEstimateNoData")) return null
        if (action == ACTION_NS_EMULATOR && !isNsEmulatorGlucoseCollection(extras)) return null

        val nsEmulatorCandidate = parseNsEmulatorGlucoseFallback(action, extras)
        val glucose = when (action) {
            ACTION_NS_EMULATOR -> nsEmulatorCandidate ?: GlucoseValueResolver.resolve(extras)
            else -> GlucoseValueResolver.resolve(extras) ?: nsEmulatorCandidate
        } ?: return null

        val units = findStringExact(
            extras,
            listOf("com.eveningoutpost.dexdrip.Extras.Display.Units", "display.units", "display_units", "units", "unit")
        )?.lowercase(Locale.US)
            ?: when {
                glucose.key.lowercase(Locale.US).contains("mgdl") -> "mgdl"
                action == ACTION_NS_EMULATOR -> "mgdl"
                else -> null
            }
        val mmol = GlucoseUnitNormalizer.normalizeToMmol(
            valueRaw = glucose.valueRaw,
            valueKey = glucose.key,
            units = units
        )
        if (mmol !in 1.0..33.0) return null

        val rawTimestamp = when (action) {
                ACTION_NS_EMULATOR -> parseNsEmulatorTimestampFallback(action, extras)
                    ?: findTimestamp(
                        extras,
                        listOf(
                            "com.eveningoutpost.dexdrip.Extras.Time",
                            "timestamp",
                            "time",
                            "date",
                            "mills",
                            "created_at"
                        )
                    )
                else -> if (isHighFrequencyStatusAction(action)) {
                    selectBroadcastGlucoseTimestamp(
                        action = action,
                        extras = extras,
                        fallbackNowMs = System.currentTimeMillis()
                    ) ?: return null
                } else {
                    findTimestamp(
                        extras,
                        listOf(
                            "com.eveningoutpost.dexdrip.Extras.Time",
                            "timestamp",
                            "time",
                            "date",
                            "mills",
                            "created_at"
                        )
                    ) ?: parseNsEmulatorTimestampFallback(action, extras)
                }
            }
        val ts = normalizeTimestamp(rawTimestamp ?: System.currentTimeMillis())

        val source = resolveSource(action)

        return ParsedGlucose(
            sample = GlucoseSampleEntity(
                timestamp = ts,
                mmol = mmol,
                source = source,
                quality = "OK"
            ),
            inputKey = glucose.key,
            inputKind = resolveGlucoseInputKind(glucose.key)
        )
    }

    private fun buildGlucoseInputTelemetry(parsed: ParsedGlucose): List<TelemetrySampleEntity> {
        return listOf(
            TelemetrySampleEntity(
                id = "tm-${parsed.sample.source}-glucose_input_key-${parsed.sample.timestamp}",
                timestamp = parsed.sample.timestamp,
                source = parsed.sample.source,
                key = "glucose_input_key",
                valueDouble = null,
                valueText = parsed.inputKey,
                unit = null,
                quality = "OK"
            ),
            TelemetrySampleEntity(
                id = "tm-${parsed.sample.source}-glucose_input_kind-${parsed.sample.timestamp}",
                timestamp = parsed.sample.timestamp,
                source = parsed.sample.source,
                key = "glucose_input_kind",
                valueDouble = null,
                valueText = parsed.inputKind,
                unit = null,
                quality = "OK"
            )
        )
    }

    private fun resolveGlucoseInputKind(key: String): String {
        val normalized = key.trim().lowercase(Locale.US)
        return when {
            normalized.contains("raw_") || normalized.startsWith("raw") -> "raw"
            normalized.contains("bgestimate") -> "estimate"
            else -> "sgv"
        }
    }

    private fun parseTherapy(action: String, extras: Map<String, String>): List<TherapyEventEntity> {
        val ts = extractTimestamp(extras)
        val source = resolveSource(action)
        val isAapsStatusAction = action.startsWith("info.nightscout.androidaps.")

        if (action.endsWith("BgEstimateNoData")) {
            return listOf(
                TherapyEventEntity(
                    id = "br-$source-sensor_state-$ts-${action.hashCode()}",
                    timestamp = ts,
                    type = "sensor_state",
                    payloadJson = JSONObject(
                        mapOf(
                            "blocked" to "true",
                            "reason" to "no_data_broadcast",
                            "action" to action
                        )
                    ).toString()
                )
            )
        }

        if (action.endsWith("NEW_SGV") || action.endsWith("BgEstimate")) {
            // Glucose-only broadcast.
            return emptyList()
        }

        val carbs = findDoubleExact(extras, listOf("carbs", "grams", "enteredCarbs", "mealCarbs"))
        val insulin = findDoubleExact(extras, listOf("insulin", "insulinUnits", "bolus", "enteredInsulin", "bolusUnits"))
        val duration = findLongExact(extras, listOf("duration", "durationInMinutes"))?.toInt()
        val targetBottom = findDoubleExact(extras, listOf("targetBottom", "target_bottom", "targetLow"))
        val targetTop = findDoubleExact(extras, listOf("targetTop", "target_top", "targetHigh"))
        val eventTypeRaw = findStringExact(extras, listOf("eventType", "event_type", "type"))
        val eventType = normalizeTherapyEventType(eventTypeRaw)

        if (isAapsStatusAction) {
            if (!parseAapsCarbStatusSignal(action, extras).hasExplicitTherapyPayload) {
                // Most androidaps.* status broadcasts are telemetry-only; avoid synthetic therapy noise.
                return emptyList()
            }
        }

        val typeAndPayload = when {
            eventType?.contains("temp") == true && duration != null && (targetBottom != null || targetTop != null) -> {
                "temp_target" to buildMap {
                    put("duration", duration.toString())
                    targetBottom?.let { put("targetBottom", it.toString()) }
                    targetTop?.let { put("targetTop", it.toString()) }
                }
            }
            carbs != null && insulin != null && carbs > 0.0 && insulin > 0.0 -> {
                "meal_bolus" to mapOf("grams" to carbs.toString(), "bolusUnits" to insulin.toString())
            }
            insulin != null && insulin > 0.0 -> {
                "correction_bolus" to mapOf("units" to insulin.toString())
            }
            carbs != null && carbs > 0.0 -> {
                "carbs" to mapOf("grams" to carbs.toString())
            }
            eventType in setOf("infusion_set_change", "sensor_change", "insulin_refill", "pump_battery_change") -> {
                eventType!! to buildMap {
                    put("source", source)
                    eventTypeRaw?.trim()?.takeIf { it.isNotEmpty() }?.let { put("eventTypeRaw", it) }
                    findStringExact(extras, listOf("notes", "note", "reason"))?.let { put("notes", it) }
                }
            }
            else -> null
        } ?: return emptyList()

        val id = "br-$source-${typeAndPayload.first}-$ts-${typeAndPayload.second.hashCode()}"
        return listOf(
            TherapyEventEntity(
                id = id,
                timestamp = ts,
                type = typeAndPayload.first,
                payloadJson = JSONObject(typeAndPayload.second).toString()
            )
        )
    }

    private fun normalizeTherapyEventType(raw: String?): String? {
        val normalized = raw
            ?.trim()
            ?.lowercase(Locale.US)
            ?.replace('-', ' ')
            ?.replace('_', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return when (normalized) {
            "temporary target" -> "temp_target"
            "carb correction" -> "carbs"
            "meal bolus" -> "meal_bolus"
            "correction bolus" -> "correction_bolus"
            "site change", "cannula change", "infusion set change", "set change", "pump site change" ->
                "infusion_set_change"
            "sensor change", "cgm sensor change", "sensor start" -> "sensor_change"
            "insulin change", "reservoir change", "cartridge change", "pump refill", "insulin refill" ->
                "insulin_refill"
            "pump battery change", "battery change", "battery replacement", "pump battery replacement" ->
                "pump_battery_change"
            else -> normalized.replace(" ", "_")
        }
    }

    private fun findString(extras: Map<String, String>, keys: List<String>): String? {
        keys.firstNotNullOfOrNull { key ->
            extras.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }?.let {
                return it
            }
        }
        return findStringByToken(extras, keys)
    }

    private fun findStringExact(extras: Map<String, String>, keys: List<String>): String? {
        return keys.firstNotNullOfOrNull { key ->
            extras.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
        }
    }

    private fun findDouble(extras: Map<String, String>, keys: List<String>): Double? {
        return findString(extras, keys)?.replace(",", ".")?.toDoubleOrNull()
    }

    private fun findDoubleExact(extras: Map<String, String>, keys: List<String>): Double? {
        return findStringExact(extras, keys)?.replace(",", ".")?.toDoubleOrNull()
    }

    private fun findLong(extras: Map<String, String>, keys: List<String>): Long? {
        val raw = findString(extras, keys) ?: return null
        return raw.toLongOrNull()
            ?: raw.toDoubleOrNull()?.toLong()
    }

    private fun findLongExact(extras: Map<String, String>, keys: List<String>): Long? {
        val raw = findStringExact(extras, keys) ?: return null
        return raw.toLongOrNull()
            ?: raw.toDoubleOrNull()?.toLong()
    }

    private fun findTimestamp(extras: Map<String, String>, keys: List<String>): Long? {
        keys.forEach { key ->
            val exact = extras.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
            val parsedExact = parseTimestampValue(exact)
            if (parsedExact != null) return parsedExact
        }
        keys.forEach { key ->
            val byToken = findStringByToken(extras, listOf(key))
            val parsedToken = parseTimestampValue(byToken)
            if (parsedToken != null) return parsedToken
        }
        return null
    }

    private fun parseTimestampValue(raw: String?): Long? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val numeric = trimmed.toLongOrNull()
            ?: trimmed.toDoubleOrNull()?.toLong()
        if (numeric != null) {
            val millis = if (numeric < 10_000_000_000L) numeric * 1000L else numeric
            return millis.takeIf { it > 0L }
        }

        val iso = runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrNull()
        if (iso != null) return iso

        val normalized = if (trimmed.endsWith("Z") || trimmed.contains("+")) {
            trimmed
        } else {
            trimmed.replace(' ', 'T') + "Z"
        }
        return runCatching { Instant.parse(normalized).toEpochMilli() }.getOrNull()
    }

    private fun parseTelemetry(
        action: String,
        source: String,
        extras: Map<String, String>
    ): List<TelemetrySampleEntity> {
        val isStatusAction = isHighFrequencyStatusAction(action)
        val receivedAtMs = System.currentTimeMillis()
        if (isStatusAction) {
            return mapHighFrequencyStatusTelemetry(
                source = source,
                extras = extras,
                receivedAtMs = receivedAtMs
            )
        }
        return TelemetryMetricMapper.fromKeyValueMap(
            timestamp = extractTimestamp(extras),
            source = source,
            values = extras,
            observedAtTimestamp = receivedAtMs
        )
    }

    private fun buildAapsCarbRevisionTelemetry(
        action: String,
        source: String,
        revisionId: Long?
    ): List<TelemetrySampleEntity> {
        if (revisionId == null || !isHighFrequencyStatusAction(action)) return emptyList()
        val timestamp = normalizeHighFrequencyStatusTimestamp(System.currentTimeMillis())
        return listOf(
            TelemetrySampleEntity(
                id = "tm-$source-$AAPS_CARB_REVISION_TELEMETRY_KEY-$timestamp",
                timestamp = timestamp,
                source = source,
                key = AAPS_CARB_REVISION_TELEMETRY_KEY,
                valueDouble = null,
                valueText = revisionId.toString(),
                unit = null,
                quality = "OK"
            )
        )
    }

    private fun signalAapsCarbRevision(revisionId: Long) {
        val repository = aapsCarbHistorySyncRepository ?: return
        if (!aapsCarbRevisionSignals.tryStart(revisionId)) return
        launchAapsCarbRevisionWorker(repository)
    }

    private fun launchAapsCarbRevisionWorker(repository: AapsCarbHistorySyncRepository) {
        ingestScope.launch {
            runAapsCarbRevisionSignalWorker(
                coordinator = aapsCarbRevisionSignals,
                syncForRevision = repository::syncForRevision,
                relaunch = { launchAapsCarbRevisionWorker(repository) }
            )
        }
    }

    private fun extractTimestamp(extras: Map<String, String>): Long = normalizeTimestamp(
        findTimestamp(
            extras,
            listOf(
                "com.eveningoutpost.dexdrip.Extras.Time",
                "timestamp",
                "time",
                "date",
                "mills",
                "created_at"
            )
        ) ?: System.currentTimeMillis()
    )

    private fun resolveSource(action: String): String = when {
        action.startsWith("info.nightscout.androidaps.") -> "aaps_broadcast"
        action.startsWith("info.nightscout.client.") -> "aaps_broadcast"
        action.startsWith("com.microtechmd.cgms.aidex.") -> "xdrip_broadcast"
        action.startsWith("com.fanqies.tomatofn.") -> "xdrip_broadcast"
        action.startsWith("com.eveningoutpost.dexdrip.") -> "xdrip_broadcast"
        else -> "local_broadcast"
    }

    private suspend fun pruneLegacyInvalidBroadcastGlucose() {
        val removedLocal = db.glucoseDao().deleteBySourceAndThreshold(
            source = "local_broadcast",
            thresholdMmol = 30.0
        )
        val removedXdrip = db.glucoseDao().deleteBySourceAndThreshold(
            source = "xdrip_broadcast",
            thresholdMmol = 30.0
        )
        if (removedLocal > 0 || removedXdrip > 0) {
            auditLogger.info(
                "broadcast_glucose_cleanup",
                mapOf(
                    "removedLocal" to removedLocal,
                    "removedXdrip" to removedXdrip,
                    "thresholdMmol" to 30.0
                )
            )
        }
    }

    private data class ParsedGlucose(
        val sample: GlucoseSampleEntity,
        val inputKey: String,
        val inputKind: String
    )

    private suspend fun pruneLegacyBroadcastArtifacts() {
        val removedTherapy = db.therapyDao().deleteLegacyBroadcastArtifacts()
        val removedInvalidTimestamp = db.telemetryDao().deleteByTimestampAtOrBelow(0L)
        val removedUam = db.telemetryDao().deleteByKeyAboveThreshold(
            key = "uam_value",
            threshold = 1.5
        )
        val removedStatusCarbsNoise = db.telemetryDao().deleteBySourceAndKeyAtOrBelow(
            source = "aaps_broadcast",
            key = "carbs_grams",
            threshold = 0.0
        )
        val removedStatusInsulinNoise = db.telemetryDao().deleteBySourceAndKeyAtOrBelow(
            source = "aaps_broadcast",
            key = "insulin_units",
            threshold = 0.0
        )
        val removedIobOutsideRange = db.telemetryDao().deleteByKeyOutsideRange(
            key = "iob_units",
            minValue = -30.0,
            maxValue = 30.0
        )
        val removedCobOutsideRange = db.telemetryDao().deleteByKeyOutsideRange(
            key = "cob_grams",
            minValue = 0.0,
            maxValue = 400.0
        )
        val removedTempTargetLowOutsideRange = db.telemetryDao().deleteByKeyOutsideRange(
            key = "temp_target_low_mmol",
            minValue = 3.0,
            maxValue = 15.0
        )
        val removedTempTargetHighOutsideRange = db.telemetryDao().deleteByKeyOutsideRange(
            key = "temp_target_high_mmol",
            minValue = 3.0,
            maxValue = 15.0
        )
        val dedupNightscout = db.glucoseDao().deleteDuplicateBySourceAndTimestamp(source = "nightscout")
        val dedupAaps = db.glucoseDao().deleteDuplicateBySourceAndTimestamp(source = "aaps_broadcast")
        val dedupLocal = db.glucoseDao().deleteDuplicateBySourceAndTimestamp(source = "local_broadcast")
        val dedupByTimestamp = db.glucoseDao().deleteDuplicateByTimestampWithPriority()
        if (
            removedTherapy > 0 ||
            removedInvalidTimestamp > 0 ||
            removedUam > 0 ||
            removedStatusCarbsNoise > 0 ||
            removedStatusInsulinNoise > 0 ||
            removedIobOutsideRange > 0 ||
            removedCobOutsideRange > 0 ||
            removedTempTargetLowOutsideRange > 0 ||
            removedTempTargetHighOutsideRange > 0 ||
            dedupNightscout > 0 ||
            dedupAaps > 0 ||
            dedupLocal > 0 ||
            dedupByTimestamp > 0
        ) {
            auditLogger.info(
                "broadcast_legacy_cleanup",
                mapOf(
                    "therapyRemoved" to removedTherapy,
                    "invalidTimestampRemoved" to removedInvalidTimestamp,
                    "telemetryRemoved" to removedUam,
                    "statusCarbsNoiseRemoved" to removedStatusCarbsNoise,
                    "statusInsulinNoiseRemoved" to removedStatusInsulinNoise,
                    "iobOutsideRangeRemoved" to removedIobOutsideRange,
                    "cobOutsideRangeRemoved" to removedCobOutsideRange,
                    "tempTargetLowOutsideRangeRemoved" to removedTempTargetLowOutsideRange,
                    "tempTargetHighOutsideRangeRemoved" to removedTempTargetHighOutsideRange,
                    "glucoseDedupNightscout" to dedupNightscout,
                    "glucoseDedupAaps" to dedupAaps,
                    "glucoseDedupLocal" to dedupLocal,
                    "glucoseDedupByTimestamp" to dedupByTimestamp
                )
            )
        }
    }

    suspend fun runPeriodicMaintenanceNowForDebug() {
        runPeriodicMaintenance(force = true)
    }

    private suspend fun runPeriodicMaintenanceIfNeeded() {
        runPeriodicMaintenance(force = false)
    }

    private suspend fun runPeriodicMaintenance(force: Boolean) {
        val now = System.currentTimeMillis()
        val lastAtomic = lastMaintenanceAtMs.get()
        val last = maxOf(
            lastAtomic,
            CopilotDatabaseSnapshotManager.latestRollingCopyTimestamp(context)
        )
        if (!force && now - last < MAINTENANCE_INTERVAL_MS) return
        if (!lastMaintenanceAtMs.compareAndSet(lastAtomic, now)) return
        runCatching {
            if (!checkDatabaseIntegrity()) return@runCatching
            val snapshotResult = CopilotDatabaseSnapshotManager.refreshRollingCopies(
                context = context,
                db = db,
                auditLogger = auditLogger
            )
            CopilotStorageCleanupManager.cleanupNonEssentialFiles(
                context = context,
                auditLogger = auditLogger
            )
            pruneLegacyInvalidBroadcastGlucose()
            pruneLegacyBroadcastArtifacts()
        }.onFailure {
            auditLogger.warn(
                "broadcast_maintenance_failed",
                mapOf("error" to (it.message ?: "unknown"))
            )
        }
    }

    private suspend fun checkDatabaseIntegrity(): Boolean {
        val quickCheck = runCatching {
            db.openHelper.writableDatabase
                .query("PRAGMA quick_check")
                .use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else "unknown"
                }
        }.getOrElse { error ->
            auditLogger.warn(
                "db_integrity_check_failed",
                mapOf("reason" to (error.message ?: "unknown"))
            )
            return false
        }
        if (quickCheck.equals("ok", ignoreCase = true)) {
            auditLogger.info("db_integrity_ok", emptyMap<String, Any>())
            Log.i(logTag, "checkDatabaseIntegrity:ok")
            return true
        }
        val restoreSource = CopilotDatabaseIntegrityManager.bestRestoreSource(context)
        val salvageSnapshot = CopilotDatabaseSnapshotManager.refreshRollingCopies(
            context = context,
            db = db,
            auditLogger = auditLogger,
            allowLargeSource = true
        )
        Log.w(
            logTag,
            "checkDatabaseIntegrity:issue quickCheck=$quickCheck restoreSource=${restoreSource?.sourceKind} salvage=${salvageSnapshot.success} salvageError=${salvageSnapshot.error}"
        )
        auditLogger.warn(
            "db_integrity_issue_detected",
            buildMap<String, Any?> {
                put("quickCheck", quickCheck)
                put("restartRequired", restoreSource != null)
                put("restoreSource", restoreSource?.sourceKind)
                put("restoreSourceQuickCheck", restoreSource?.quickCheck)
                put("restoreSourcePath", restoreSource?.path)
                put("salvageSnapshotCreated", salvageSnapshot.success)
                put("salvageSnapshotPath", salvageSnapshot.snapshotPath)
                put("salvageSnapshotIntegrity", salvageSnapshot.snapshotIntegrity)
                put("salvageSnapshotError", salvageSnapshot.error)
            }
        )
        runCatching {
            db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)")
        }.onFailure { error ->
            auditLogger.warn(
                "db_integrity_checkpoint_failed",
                mapOf("reason" to (error.message ?: "unknown"))
            )
        }
        return false
    }

    private fun isHighFrequencyStatusAction(action: String): Boolean {
        return action == "info.nightscout.androidaps.status" || action == "app.aaps.status"
    }

    private fun isTelemetryOnlyCoalescedAction(action: String): Boolean {
        return isHighFrequencyStatusAction(action) || isSupportedGlucoseAction(action)
    }

    private suspend fun logIngestCompleted(
        action: String,
        glucoseImported: Int,
        therapyImported: Int,
        telemetryImported: Int
    ) {
        val metadata = mapOf(
            "action" to action,
            "glucose" to glucoseImported,
            "therapy" to therapyImported,
            "telemetry" to telemetryImported
        )
        if (isTelemetryOnlyCoalescedAction(action)) {
            auditLogger.infoThrottled(
                throttleKey = "broadcast_ingest_completed:$action",
                intervalMs = HIGH_FREQUENCY_INGEST_AUDIT_INTERVAL_MS,
                message = "broadcast_ingest_completed",
                metadata = metadata
            )
        } else {
            auditLogger.info("broadcast_ingest_completed", metadata)
        }
    }

    private fun normalizeTimestamp(ts: Long): Long {
        val now = System.currentTimeMillis()
        val millis = when {
            ts <= 0L -> now
            ts < 10_000_000_000L -> ts * 1000L
            else -> ts
        }
        return if (millis > now + MAX_FUTURE_TIMESTAMP_SKEW_MS) now else millis
    }

    private fun isSupportedGlucoseAction(action: String): Boolean {
        return action == "info.nightscout.client.NEW_SGV" ||
            action == "info.nightscout.client.NEW_DEVICESTATUS" ||
            action == "com.eveningoutpost.dexdrip.BgEstimate" ||
            action == "com.microtechmd.cgms.aidex.action.BgEstimate" ||
            action == "com.fanqies.tomatofn.BgEstimate" ||
            action == ACTION_NS_EMULATOR ||
            action == "info.nightscout.androidaps.status" ||
            action == "app.aaps.status"
    }

    private fun isNsEmulatorGlucoseCollection(extras: Map<String, String>): Boolean {
        val collection = findStringExact(extras, listOf("collection"))
            ?: findStringByToken(extras, listOf("collection"))
            ?: return true
        return isNsEmulatorCollectionValue(collection)
    }

    private fun parseNsEmulatorGlucoseFallback(
        action: String,
        extras: Map<String, String>
    ): GlucoseValueResolver.Candidate? {
        if (action != ACTION_NS_EMULATOR) return null
        val numeric = nsEmulatorRawDataCandidates(extras)
            .firstNotNullOfOrNull { parseNsEmulatorGlucoseRaw(it) }
            ?: return null
        return GlucoseValueResolver.Candidate(valueRaw = numeric, key = "data.sgv")
    }

    private fun parseNsEmulatorTimestampFallback(action: String, extras: Map<String, String>): Long? {
        if (action != ACTION_NS_EMULATOR) return null
        val numeric = nsEmulatorRawDataCandidates(extras)
            .firstNotNullOfOrNull { parseNsEmulatorTimestampRaw(it) }
            ?: return null
        return normalizeTimestamp(numeric)
    }

    private fun nsEmulatorRawDataCandidates(extras: Map<String, String>): List<String> {
        val values = mutableListOf<String>()
        findStringExact(extras, listOf("data"))?.let(values::add)

        val tokenKeys = listOf("data", "entries", "sgv")
        extras.entries.forEach { entry ->
            val key = normalizeKey(entry.key)
            val keyParts = key.split('_').filter { it.isNotBlank() }
            val matches = tokenKeys.any { token ->
                key == token || key.endsWith("_$token") || keyParts.contains(token)
            }
            if (matches && entry.value.isNotBlank()) values += entry.value
        }
        return values.distinct()
    }

    private suspend fun isGlucoseOutlier(sample: GlucoseSampleEntity): Boolean {
        val latest = db.glucoseDao().latestOne() ?: return false
        val deltaTs = abs(sample.timestamp - latest.timestamp)
        if (deltaTs > GLUCOSE_OUTLIER_WINDOW_MS) return false
        val deltaMmol = abs(sample.mmol - latest.mmol)
        return deltaMmol >= GLUCOSE_OUTLIER_DELTA_MMOL
    }

    private fun findStringByToken(extras: Map<String, String>, keys: List<String>): String? {
        return findEntryByToken(extras, keys)?.value?.takeIf { it.isNotBlank() }
    }

    private fun findEntryByToken(extras: Map<String, String>, keys: List<String>): Map.Entry<String, String>? {
        val tokens = keys.map { normalizeKey(it) }.filter { it.isNotBlank() }
        return extras.entries.firstOrNull { entry ->
            val key = normalizeKey(entry.key)
            val keyParts = key.split('_').filter { it.isNotBlank() }
            tokens.any { token ->
                key == token || key.endsWith("_$token") || keyParts.contains(token)
            }
        }
    }

    private fun normalizeKey(value: String): String {
        return value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    data class IngestResult(
        val glucoseImported: Int,
        val therapyImported: Int,
        val telemetryImported: Int,
        val warning: String?,
        val latestGlucoseMmol: Double? = null,
        val reactiveInvalidationRequested: Boolean = false,
        val currentGlucoseChanged: Boolean = false
    )

    companion object {
        private const val AAPS_CARB_REVISION_TELEMETRY_KEY =
            "aaps_carb_history_revision_id"
        private const val INGEST_QUEUE_CAPACITY = 256
        private const val HIGH_FREQUENCY_INGEST_AUDIT_INTERVAL_MS = 5 * 60_000L
        private const val HIGH_FREQUENCY_STATUS_TELEMETRY_BUCKET_MS = 5 * 60_000L
        private const val ACTION_NS_EMULATOR = "com.eveningoutpost.dexdrip.NS_EMULATOR"
        private val NS_EMULATOR_JSON_GLUCOSE_KEYS = listOf("sgv", "glucose", "mgdl", "bgestimate", "bg")
        private val NS_EMULATOR_JSON_TS_KEYS = listOf("date", "mills", "timestamp", "time")
        private val NS_EMULATOR_REGEX_GLUCOSE = listOf(
            Regex(""""sgv"\s*:\s*([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE),
            Regex("""\bsgv\b\s*[:=]\s*([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE),
            Regex(""""(?:glucose|mgdl|bgestimate|bg)"\s*:\s*([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:glucose|mgdl|bgestimate|bg)\b\s*[:=]\s*([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE)
        )
        private val NS_EMULATOR_REGEX_TS = listOf(
            Regex(""""(?:date|mills|timestamp|time)"\s*:\s*([0-9]{10,13})""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:date|mills|timestamp|time)\b\s*[:=]\s*([0-9]{10,13})""", RegexOption.IGNORE_CASE)
        )
        private const val MAX_FUTURE_TIMESTAMP_SKEW_MS = 24 * 60 * 60 * 1000L

        internal fun normalizeStatusTelemetry(
            mapped: List<TelemetrySampleEntity>
        ): List<TelemetrySampleEntity> {
            if (mapped.isEmpty()) return emptyList()
            val canonical = mapped.filterNot {
                it.key.startsWith("raw_") || it.key.startsWith("ns_") || it.key in STATUS_EXCLUDED_CANONICAL_KEYS
            }
            // Keep excluded canonical keys under status_* namespace for observability,
            // while preventing them from replacing authoritative treatment-derived metrics.
            val statusProjected = mapped
                .filter { it.key in STATUS_EXCLUDED_CANONICAL_KEYS }
                .map { sample ->
                    val statusKey = "status_${sample.key}"
                    val statusId = "tm-${sample.source}-$statusKey-${sample.timestamp}"
                    sample.copy(
                        id = statusId,
                        key = statusKey
                    )
                }
            val raw = mapped.filter { it.key.startsWith("raw_") || it.key.startsWith("ns_") }
            return (canonical + statusProjected + raw).distinctBy { it.id }
        }

        internal fun normalizeHighFrequencyStatusTimestamp(nowMs: Long): Long {
            if (nowMs <= 0L) return 0L
            return (nowMs / HIGH_FREQUENCY_STATUS_TELEMETRY_BUCKET_MS) *
                HIGH_FREQUENCY_STATUS_TELEMETRY_BUCKET_MS
        }

        internal fun mapHighFrequencyStatusTelemetry(
            source: String,
            extras: Map<String, String>,
            receivedAtMs: Long
        ): List<TelemetrySampleEntity> {
            // The row timestamp is an ingest bucket. iob_relay_timestamp_ms remains the
            // independent AAPS calculation-cycle timestamp carried by the relay.
            val mapped = TelemetryMetricMapper.fromKeyValueMap(
                timestamp = normalizeHighFrequencyStatusTimestamp(receivedAtMs),
                source = source,
                values = extras,
                observedAtTimestamp = receivedAtMs
            )
            return normalizeStatusTelemetry(mapped)
        }

        internal fun parseNsEmulatorGlucoseRaw(rawData: String): Double? {
            val trimmed = rawData.trim()
            if (trimmed.isBlank()) return null

            parseNsJsonNumeric(trimmed, NS_EMULATOR_JSON_GLUCOSE_KEYS)?.let { return it }

            NS_EMULATOR_REGEX_GLUCOSE.forEach { regex ->
                val value = regex.find(trimmed)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.replace(",", ".")
                    ?.toDoubleOrNull()
                if (value != null) return value
            }
            return null
        }

        internal fun parseNsEmulatorTimestampRaw(rawData: String): Long? {
            val trimmed = rawData.trim()
            if (trimmed.isBlank()) return null

            parseNsJsonNumeric(trimmed, NS_EMULATOR_JSON_TS_KEYS)?.toLong()?.let { return it }

            NS_EMULATOR_REGEX_TS.forEach { regex ->
                val value = regex.find(trimmed)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toLongOrNull()
                if (value != null) return value
            }
            return null
        }

        private fun parseNsJsonNumeric(rawData: String, keys: List<String>): Double? {
            return runCatching {
                if (rawData.startsWith("[")) {
                    findNumericInJsonArray(JSONArray(rawData), keys)
                } else if (rawData.startsWith("{")) {
                    findNumericInJsonObject(JSONObject(rawData), keys)
                } else {
                    null
                }
            }.getOrNull()
        }

        private fun findNumericInJsonArray(array: JSONArray, keys: List<String>): Double? {
            for (index in 0 until array.length()) {
                when (val value = array.opt(index)) {
                    is JSONObject -> findNumericInJsonObject(value, keys)?.let { return it }
                    is JSONArray -> findNumericInJsonArray(value, keys)?.let { return it }
                    else -> Unit
                }
            }
            return null
        }

        private fun findNumericInJsonObject(obj: JSONObject, keys: List<String>): Double? {
            keys.forEach { key ->
                when (val raw = obj.opt(key)) {
                    is Number -> return raw.toDouble()
                    is String -> raw.replace(",", ".").toDoubleOrNull()?.let { return it }
                    else -> Unit
                }
            }

            val iterator = obj.keys()
            while (iterator.hasNext()) {
                val nested = obj.opt(iterator.next())
                when (nested) {
                    is JSONObject -> findNumericInJsonObject(nested, keys)?.let { return it }
                    is JSONArray -> findNumericInJsonArray(nested, keys)?.let { return it }
                    else -> Unit
                }
            }
            return null
        }

        internal fun isNsEmulatorCollectionValue(rawCollection: String?): Boolean {
            val normalized = rawCollection?.trim()?.lowercase(Locale.US).orEmpty()
            if (normalized.isBlank()) return true
            return normalized.contains("entry") ||
                normalized.contains("entries") ||
                normalized.contains("entri") ||
                normalized.contains("sgv") ||
                normalized.contains("glucose") ||
                normalized.contains("bg")
        }

        private val lastMaintenanceAtMs = AtomicLong(0L)
        private const val MAINTENANCE_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val GLUCOSE_OUTLIER_WINDOW_MS = 10 * 60_000L
        private const val GLUCOSE_OUTLIER_DELTA_MMOL = 8.0
        private const val GLUCOSE_REPLACE_EPSILON = 0.01

        private val STATUS_EXCLUDED_CANONICAL_KEYS = setOf(
            // Status payloads may carry these as predictive/placeholder values (often zero),
            // not confirmed therapy entries. They are stored as status_* keys.
            "carbs_grams",
            "insulin_units"
        )
    }
}
