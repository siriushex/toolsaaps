package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.predict.UamExportMode
import io.aaps.copilot.domain.predict.UamInferenceEvent
import io.aaps.copilot.domain.predict.UamInferenceState
import io.aaps.copilot.domain.predict.UamTag
import io.aaps.copilot.domain.predict.UamTagCodec
import kotlin.math.max

class UamExportCoordinator(
    private val gateway: AapsCarbGateway,
    private val auditLogger: AuditLogger? = null
) {

    data class Config(
        val enableUamExportToAaps: Boolean,
        val exportMode: UamExportMode,
        val dryRunExport: Boolean,
        val minSnackG: Int,
        val snackStepG: Int,
        val exportMinIntervalMin: Int,
        val exportMaxBackdateMin: Int
    )

    data class Outcome(
        val events: List<UamInferenceEvent>,
        val remoteEntries: List<AapsCarbEntry>
    )

    suspend fun process(
        nowTs: Long,
        events: List<UamInferenceEvent>,
        config: Config
    ): Outcome {
        if (events.isEmpty()) {
            return Outcome(events = emptyList(), remoteEntries = emptyList())
        }
        val since = nowTs - FETCH_LOOKBACK_MS
        val remote = gateway.fetchCarbEntries(since).getOrDefault(emptyList())
        val remoteTagged = remote.mapNotNull { entry ->
            val tag = UamTagCodec.parseUamTag(entry.note) ?: return@mapNotNull null
            TaggedRemote(entry, tag)
        }

        val remoteById = remoteTagged.groupBy { it.tag.id }
        var updated = events.map { event ->
            val tagged = remoteById[event.id].orEmpty()
            if (tagged.isEmpty()) return@map event
            val exported = tagged.sumOf { it.entry.grams }.coerceAtLeast(event.exportedGrams)
            val maxSeq = max(event.exportSeq, tagged.maxOf { it.tag.seq })
            event.copy(
                exportedGrams = exported,
                exportSeq = maxSeq,
                lastExportTs = tagged.maxOfOrNull { it.entry.tsMs } ?: event.lastExportTs
            )
        }

        if (!config.enableUamExportToAaps || config.exportMode == UamExportMode.OFF) {
            return Outcome(events = updated, remoteEntries = remote)
        }

        val mutable = updated.toMutableList()
        for (idx in mutable.indices) {
            val event = mutable[idx]
            if (event.state != UamInferenceState.CONFIRMED) continue
            when (config.exportMode) {
                UamExportMode.CONFIRMED_ONLY -> {
                    mutable[idx] = exportConfirmedOnly(
                        nowTs = nowTs,
                        event = event,
                        config = config,
                        remoteById = remoteById
                    )
                }
                UamExportMode.INCREMENTAL -> {
                    mutable[idx] = exportIncremental(
                        nowTs = nowTs,
                        event = event,
                        config = config,
                        remoteById = remoteById
                    )
                }
                UamExportMode.OFF -> Unit
            }
        }

        updated = mutable.toList()
        return Outcome(events = updated, remoteEntries = remote)
    }

    private suspend fun exportConfirmedOnly(
        nowTs: Long,
        event: UamInferenceEvent,
        config: Config,
        remoteById: Map<String, List<TaggedRemote>>
    ): UamInferenceEvent {
        if (event.exportedGrams > 0.0 || event.exportSeq > 0) return event
        val seq = 1
        if (remoteById[event.id].orEmpty().any { it.tag.seq == seq }) {
            val synced = remoteById[event.id].orEmpty().filter { it.tag.seq == seq }.sumOf { it.entry.grams }
            return event.copy(exportedGrams = max(event.exportedGrams, synced), exportSeq = max(event.exportSeq, seq))
        }

        val grams = event.carbsDisplayG.coerceAtLeast(config.minSnackG.toDouble())
        val safeTs = clampBackdate(nowTs = nowTs, ingestionTs = event.ingestionTs, maxBackdateMin = config.exportMaxBackdateMin)
        val note = UamTagCodec.buildTag(event.id, seq, event.mode)
        if (config.dryRunExport) {
            auditLogger?.info(
                "uam_export_dry_run",
                mapOf("mode" to "CONFIRMED_ONLY", "eventId" to event.id, "seq" to seq, "grams" to grams, "tsMs" to safeTs)
            )
            return event
        }
        val sent = gateway.postCarbEntry(tsMs = safeTs, grams = grams, note = note).isSuccess
        return if (sent) {
            event.copy(exportedGrams = grams, exportSeq = seq, lastExportTs = nowTs)
        } else {
            event
        }
    }

    private suspend fun exportIncremental(
        nowTs: Long,
        event: UamInferenceEvent,
        config: Config,
        remoteById: Map<String, List<TaggedRemote>>
    ): UamInferenceEvent {
        val minSnack = config.minSnackG.toDouble().coerceAtLeast(1.0)
        val desired = event.carbsDisplayG.coerceAtLeast(minSnack)
        val lastExportTs = event.lastExportTs ?: 0L
        val elapsedMin = ((nowTs - lastExportTs).coerceAtLeast(0L)) / 60_000L
        val eventRemote = remoteById[event.id].orEmpty()

        if (event.exportedGrams <= 0.0 && event.exportSeq == 0) {
            val seq = 1
            if (eventRemote.any { it.tag.seq == seq }) {
                val synced = eventRemote.filter { it.tag.seq == seq }.sumOf { it.entry.grams }
                return event.copy(exportedGrams = max(event.exportedGrams, synced), exportSeq = max(event.exportSeq, seq))
            }
            val safeTs = clampBackdate(nowTs = nowTs, ingestionTs = event.ingestionTs, maxBackdateMin = config.exportMaxBackdateMin)
            val note = UamTagCodec.buildTag(event.id, seq, event.mode)
            if (config.dryRunExport) {
                auditLogger?.info(
                    "uam_export_dry_run",
                    mapOf("mode" to "INCREMENTAL", "eventId" to event.id, "seq" to seq, "grams" to minSnack, "tsMs" to safeTs)
                )
                return event
            }
            val sent = gateway.postCarbEntry(tsMs = safeTs, grams = minSnack, note = note).isSuccess
            return if (sent) {
                event.copy(exportedGrams = minSnack, exportSeq = seq, lastExportTs = nowTs)
            } else {
                event
            }
        }

        if (desired <= event.exportedGrams + config.snackStepG) return event
        if (elapsedMin < config.exportMinIntervalMin) return event
        val seq = event.exportSeq + 1
        if (eventRemote.any { it.tag.seq == seq }) {
            val synced = eventRemote.filter { it.tag.seq == seq }.sumOf { it.entry.grams }
            return event.copy(
                exportedGrams = (event.exportedGrams + synced).coerceAtMost(desired),
                exportSeq = seq
            )
        }
        val delta = (desired - event.exportedGrams).coerceAtLeast(0.0)
        if (delta <= 0.0) return event
        val safeTs = clampBackdate(nowTs = nowTs, ingestionTs = event.ingestionTs, maxBackdateMin = config.exportMaxBackdateMin)
        val note = UamTagCodec.buildTag(event.id, seq, event.mode)
        if (config.dryRunExport) {
            auditLogger?.info(
                "uam_export_dry_run",
                mapOf("mode" to "INCREMENTAL", "eventId" to event.id, "seq" to seq, "grams" to delta, "tsMs" to safeTs)
            )
            return event
        }
        val sent = gateway.postCarbEntry(tsMs = safeTs, grams = delta, note = note).isSuccess
        return if (sent) {
            event.copy(
                exportedGrams = (event.exportedGrams + delta).coerceAtMost(desired),
                exportSeq = seq,
                lastExportTs = nowTs
            )
        } else {
            event
        }
    }

    private fun clampBackdate(nowTs: Long, ingestionTs: Long, maxBackdateMin: Int): Long {
        val minTs = nowTs - maxBackdateMin.coerceAtLeast(10) * 60_000L
        return ingestionTs.coerceIn(minTs, nowTs)
    }

    private data class TaggedRemote(
        val entry: AapsCarbEntry,
        val tag: UamTag
    )

    private companion object {
        const val FETCH_LOOKBACK_MS = 6 * 60 * 60_000L
    }
}
