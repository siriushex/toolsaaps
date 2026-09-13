package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.predict.UamExportMode
import io.aaps.copilot.domain.predict.UamInferenceEvent
import io.aaps.copilot.domain.predict.UamMode
import io.aaps.copilot.domain.predict.UamTag
import io.aaps.copilot.domain.predict.UamTagCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max

interface UamExportReservationStore {
    suspend fun reserve(key: String): Boolean

    suspend fun markSent(key: String, remoteId: String)

    suspend fun markPendingUnknown(key: String, detail: String? = null)

    suspend fun release(key: String)
}

class UamExportCoordinator(
    private val gateway: AapsCarbGateway,
    private val auditLogger: AuditLogger? = null,
    private val reservationStore: UamExportReservationStore? = null,
    private val wallClockMs: () -> Long = { System.currentTimeMillis() }
) {

    data class Config(
        val enableUamExportToAaps: Boolean,
        val sensorBlocked: Boolean,
        val exportMode: UamExportMode,
        val dryRunExport: Boolean,
        val minSnackG: Int,
        val maxSnackG: Int,
        val snackStepG: Int,
        val exportMinIntervalMin: Int,
        val exportMaxBackdateMin: Int,
        val calculatedCarbsGrams: Double?,
        val calculatedToOriginalMultiplier: Double
    )

    data class Outcome(
        val events: List<UamInferenceEvent>,
        val remoteEntries: List<AapsCarbEntry>,
        val decision: Any? = null,
        val delivered: Boolean = false,
        val reason: String? = null,
        val reconciledGrams: Double? = null,
        val reconciledSeq: Int? = null,
        val reconciliationValid: Boolean = false,
        val reserved: Boolean = false,
        val reservationKey: String? = null,
        val remoteId: String? = null,
        val postAttempted: Boolean = false,
        val postOutcomeUnknown: Boolean = false
    )

    /**
     * Legacy compatibility path. It may reconcile persisted export fields, but it never delivers carbs.
     */
    @Deprecated(
        message = "Legacy reconciliation only; production uses processUnified",
        level = DeprecationLevel.WARNING
    )
    @Suppress("UNUSED_PARAMETER")
    suspend fun process(
        nowTs: Long,
        events: List<UamInferenceEvent>,
        config: Config
    ): Outcome {
        if (events.isEmpty()) {
            return Outcome(events = emptyList(), remoteEntries = emptyList())
        }
        val remoteResult = fetchRemote(reconciliationSince(nowTs))
        if (remoteResult.isFailure) {
            auditWarnBestEffort(
                "uam_export_reconciliation_failed",
                mapOf("reason" to "fetch_failed")
            )
            return Outcome(events = events, remoteEntries = emptyList())
        }
        val remote = remoteResult.getOrThrow()
        val remoteTagged = deduplicateLegacyRemoteEntries(remote).mapNotNull { entry ->
            val tag = UamTagCodec.parseUamTag(entry.note) ?: return@mapNotNull null
            TaggedRemote(entry, tag)
        }
        val remoteById = remoteTagged.groupBy { it.tag.id }
        val updated = events.map { event ->
            val tagged = remoteById[event.id].orEmpty()
            if (tagged.isEmpty()) return@map event
            event.copy(
                exportedGrams = tagged.sumOf { it.entry.grams }.coerceAtLeast(event.exportedGrams),
                exportSeq = max(event.exportSeq, tagged.maxOf { it.tag.seq }),
                lastExportTs = tagged.maxOfOrNull { it.entry.tsMs } ?: event.lastExportTs
            )
        }

        return Outcome(events = updated, remoteEntries = remote)
    }

    internal suspend fun processUnified(
        candidate: UamExportPolicyInput,
        enabled: Boolean,
        dryRun: Boolean
    ): Outcome = unifiedProcessMutex.withLock {
        processUnifiedLocked(candidate = candidate, enabled = enabled, dryRun = dryRun)
    }

    private suspend fun processUnifiedLocked(
        candidate: UamExportPolicyInput,
        enabled: Boolean,
        dryRun: Boolean
    ): Outcome {
        val preflightBlock = preflight(candidate) ?: wallClockFreshnessBlock(candidate)
        if (preflightBlock != null) {
            auditWarnBestEffort(
                "uam_export_policy_blocked",
                mapOf("episodeId" to candidate.episodeId, "reason" to preflightBlock.reason)
            )
            return unifiedOutcome(decision = preflightBlock, reason = preflightBlock.reason)
        }

        val remoteResult = fetchRemote(reconciliationSince(candidate.nowTs))
        if (remoteResult.isFailure) {
            return reconciliationFailure(
                candidate = candidate,
                remoteEntries = emptyList(),
                detail = "fetch_failed"
            )
        }
        val remoteEntries = remoteResult.getOrThrow()
        val uniqueEntries = ArrayList<AapsCarbEntry>(remoteEntries.size)
        val entriesByRemoteId = LinkedHashMap<String, AapsCarbEntry>()
        for (entry in remoteEntries) {
            val remoteId = entry.remoteId
            if (remoteId.isNullOrBlank()) {
                uniqueEntries += entry
                continue
            }
            val previous = entriesByRemoteId[remoteId]
            if (previous == null) {
                entriesByRemoteId[remoteId] = entry
                uniqueEntries += entry
            } else if (previous != entry) {
                return reconciliationFailure(
                    candidate = candidate,
                    remoteEntries = remoteEntries,
                    detail = "remote_identity_conflict"
                )
            }
        }

        val ledger = ArrayList<UamExportLedgerEntry>()
        val globalLedger = ArrayList<UamExportLedgerEntry>()
        for (entry in uniqueEntries) {
            val parsed = UamTagCodec.parseUamTag(entry.note)
            if (isApparentV2UamTag(entry.note) && parsed?.ver != UNIFIED_TAG_VERSION) {
                return reconciliationFailure(
                    candidate = candidate,
                    remoteEntries = remoteEntries,
                    detail = "malformed_v2_tag"
                )
            }
            val referencesCandidate = UamTagCodec.referencesEpisode(entry.note, candidate.episodeId)
            if (referencesCandidate && (parsed == null || parsed.id != candidate.episodeId)) {
                return reconciliationFailure(
                    candidate = candidate,
                    remoteEntries = remoteEntries,
                    detail = "malformed_matching_tag"
                )
            }
            if (parsed?.id == candidate.episodeId) {
                ledger += UamExportLedgerEntry(
                    tsMs = entry.tsMs,
                    grams = entry.grams,
                    seq = parsed.seq,
                    episodeId = parsed.id
                )
            }
            if (parsed?.ver == UNIFIED_TAG_VERSION) {
                globalLedger += UamExportLedgerEntry(
                    tsMs = entry.tsMs,
                    grams = entry.grams,
                    seq = parsed.seq,
                    episodeId = parsed.id
                )
            }
        }

        val decision = UamExportPolicy.decide(
            candidate.copy(
                remoteLedger = ledger,
                globalRemoteLedger = globalLedger
            )
        )
        val reconciledGrams = ledger.sumOf { it.grams }
        val reconciledSeq = ledger.maxOfOrNull { it.seq } ?: 0
        if (!enabled) {
            auditInfoBestEffort(
                "uam_export_disabled",
                decisionMetadata(candidate, decision, "disabled")
            )
            return unifiedOutcome(
                remoteEntries = remoteEntries,
                decision = decision,
                reason = "disabled",
                reconciledGrams = reconciledGrams,
                reconciledSeq = reconciledSeq,
                reconciliationValid = true
            )
        }
        if (decision is UamExportDecision.Block) {
            auditInfoBestEffort(
                "uam_export_policy_blocked",
                decisionMetadata(candidate, decision, decision.reason)
            )
            return unifiedOutcome(
                remoteEntries = remoteEntries,
                decision = decision,
                reason = decision.reason,
                reconciledGrams = reconciledGrams,
                reconciledSeq = reconciledSeq,
                reconciliationValid = true
            )
        }
        decision as UamExportDecision.Send
        if (dryRun) {
            auditInfoBestEffort(
                "uam_export_dry_run",
                decisionMetadata(candidate, decision, "dry_run")
            )
            return unifiedOutcome(
                remoteEntries = remoteEntries,
                decision = decision,
                reason = "dry_run",
                reconciledGrams = reconciledGrams,
                reconciledSeq = reconciledSeq,
                reconciliationValid = true
            )
        }

        wallClockFreshnessBlock(candidate)?.let { freshnessBlock ->
            auditInfoBestEffort(
                "uam_export_policy_blocked",
                decisionMetadata(candidate, freshnessBlock, freshnessBlock.reason)
            )
            return unifiedOutcome(
                remoteEntries = remoteEntries,
                decision = freshnessBlock,
                reason = freshnessBlock.reason,
                reconciledGrams = reconciledGrams,
                reconciledSeq = reconciledSeq,
                reconciliationValid = true
            )
        }

        val note = UamTagCodec.buildTag(
            eventId = candidate.episodeId,
            seq = decision.seq,
            mode = UamMode.NORMAL,
            version = UNIFIED_TAG_VERSION
        )
        val reservationKey = note
        val store = reservationStore
        if (store == null) {
            auditWarnBestEffort(
                "uam_export_reservation_blocked",
                decisionMetadata(candidate, decision, "reservation_store_unavailable", reservationKey = reservationKey)
            )
            return unifiedOutcome(
                remoteEntries = remoteEntries,
                decision = decision,
                reason = "reservation_store_unavailable",
                reconciledGrams = reconciledGrams,
                reconciledSeq = reconciledSeq,
                reconciliationValid = true,
                reservationKey = reservationKey
            )
        }
        val reserved = try {
            store.reserve(reservationKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Throwable) {
            auditWarnBestEffort(
                "uam_export_reservation_blocked",
                decisionMetadata(candidate, decision, "reservation_failed", reservationKey = reservationKey)
            )
            return unifiedOutcome(
                remoteEntries = remoteEntries,
                decision = decision,
                reason = "reservation_failed",
                reconciledGrams = reconciledGrams,
                reconciledSeq = reconciledSeq,
                reconciliationValid = true,
                reservationKey = reservationKey
            )
        }
        if (!reserved) {
            auditInfoBestEffort(
                "uam_export_already_reserved",
                decisionMetadata(candidate, decision, "already_reserved", reservationKey = reservationKey)
            )
            return unifiedOutcome(
                remoteEntries = remoteEntries,
                decision = decision,
                reason = "already_reserved",
                reconciledGrams = reconciledGrams,
                reconciledSeq = reconciledSeq,
                reconciliationValid = true,
                reserved = true,
                reservationKey = reservationKey
            )
        }

        wallClockFreshnessBlock(candidate)?.let { freshnessBlock ->
            val released = releaseReservationBestEffort(store, reservationKey)
            auditInfoBestEffort(
                "uam_export_policy_blocked",
                decisionMetadata(
                    candidate = candidate,
                    decision = freshnessBlock,
                    reason = freshnessBlock.reason,
                    reservationKey = reservationKey
                )
            )
            return unifiedOutcome(
                remoteEntries = remoteEntries,
                decision = freshnessBlock,
                reason = freshnessBlock.reason,
                reconciledGrams = reconciledGrams,
                reconciledSeq = reconciledSeq,
                reconciliationValid = true,
                reserved = !released,
                reservationKey = reservationKey
            )
        }

        val postResult = try {
            gateway.postCarbEntry(
                tsMs = decision.treatmentTs,
                grams = decision.grams,
                note = note
            )
        } catch (cancellation: CancellationException) {
            recordPendingUnknownPreservingPrimaryFailure(
                candidate = candidate,
                decision = decision,
                store = store,
                reservationKey = reservationKey,
                detail = failureDetail("cancelled", cancellation),
                auditMessage = "uam_export_post_outcome_unknown",
                auditReason = "post_outcome_unknown",
                postOutcomeUnknown = true
            )
            throw cancellation
        } catch (fatal: Error) {
            recordPendingUnknownPreservingPrimaryFailure(
                candidate = candidate,
                decision = decision,
                store = store,
                reservationKey = reservationKey,
                detail = failureDetail("fatal", fatal),
                auditMessage = "uam_export_post_outcome_unknown",
                auditReason = "post_outcome_unknown",
                postOutcomeUnknown = true
            )
            throw fatal
        } catch (error: Throwable) {
            return postOutcomeUnknown(
                candidate = candidate,
                decision = decision,
                remoteEntries = remoteEntries,
                reconciledGrams = reconciledGrams,
                reconciledSeq = reconciledSeq,
                store = store,
                reservationKey = reservationKey,
                detail = failureDetail("thrown", error)
            )
        }
        postResult.exceptionOrNull()?.let { postFailure ->
            when (postFailure) {
                is CancellationException -> {
                    recordPendingUnknownPreservingPrimaryFailure(
                        candidate = candidate,
                        decision = decision,
                        store = store,
                        reservationKey = reservationKey,
                        detail = failureDetail("result_cancelled", postFailure),
                        auditMessage = "uam_export_post_outcome_unknown",
                        auditReason = "post_outcome_unknown",
                        postOutcomeUnknown = true
                    )
                    throw postFailure
                }

                is Error -> {
                    recordPendingUnknownPreservingPrimaryFailure(
                        candidate = candidate,
                        decision = decision,
                        store = store,
                        reservationKey = reservationKey,
                        detail = failureDetail("result_fatal", postFailure),
                        auditMessage = "uam_export_post_outcome_unknown",
                        auditReason = "post_outcome_unknown",
                        postOutcomeUnknown = true
                    )
                    throw postFailure
                }

                else -> {
                    return postOutcomeUnknown(
                        candidate = candidate,
                        decision = decision,
                        remoteEntries = remoteEntries,
                        reconciledGrams = reconciledGrams,
                        reconciledSeq = reconciledSeq,
                        store = store,
                        reservationKey = reservationKey,
                        detail = failureDetail("result_failure", postFailure)
                    )
                }
            }
        }

        val remoteId = postResult.getOrThrow()
        val marked = try {
            store.markSent(reservationKey, remoteId)
            true
        } catch (cancelled: CancellationException) {
            recordPendingUnknownPreservingPrimaryFailure(
                candidate = candidate,
                decision = decision,
                store = store,
                reservationKey = reservationKey,
                remoteId = remoteId,
                detail = failureDetail("mark_sent_cancelled", cancelled, remoteId),
                auditMessage = "uam_export_reservation_mark_failed",
                auditReason = "delivered_reservation_mark_interrupted",
                postOutcomeUnknown = false
            )
            throw cancelled
        } catch (fatal: Error) {
            recordPendingUnknownPreservingPrimaryFailure(
                candidate = candidate,
                decision = decision,
                store = store,
                reservationKey = reservationKey,
                remoteId = remoteId,
                detail = failureDetail("mark_sent_fatal", fatal, remoteId),
                auditMessage = "uam_export_reservation_mark_failed",
                auditReason = "delivered_reservation_mark_interrupted",
                postOutcomeUnknown = false
            )
            throw fatal
        } catch (_: Throwable) {
            false
        }
        val deliveryReason = if (marked) "delivered" else "delivered_reservation_mark_failed"
        if (marked) {
            auditInfoBestEffort(
                "uam_export_post_success",
                decisionMetadata(
                    candidate,
                    decision,
                    deliveryReason,
                    reservationKey = reservationKey,
                    remoteId = remoteId,
                    postAttempted = true
                )
            )
        } else {
            auditWarnBestEffort(
                "uam_export_reservation_mark_failed",
                decisionMetadata(
                    candidate,
                    decision,
                    deliveryReason,
                    reservationKey = reservationKey,
                    remoteId = remoteId,
                    postAttempted = true
                )
            )
        }
        return unifiedOutcome(
            remoteEntries = remoteEntries,
            decision = decision,
            delivered = true,
            reason = deliveryReason,
            reconciledGrams = reconciledGrams,
            reconciledSeq = reconciledSeq,
            reconciliationValid = true,
            reserved = true,
            reservationKey = reservationKey,
            remoteId = remoteId,
            postAttempted = true
        )
    }

    private suspend fun postOutcomeUnknown(
        candidate: UamExportPolicyInput,
        decision: UamExportDecision.Send,
        remoteEntries: List<AapsCarbEntry>,
        reconciledGrams: Double,
        reconciledSeq: Int,
        store: UamExportReservationStore,
        reservationKey: String,
        detail: String
    ): Outcome {
        val pendingMarked = markPendingUnknown(
            store = store,
            reservationKey = reservationKey,
            detail = detail
        )
        auditWarnBestEffort(
            "uam_export_post_outcome_unknown",
            decisionMetadata(
                candidate = candidate,
                decision = decision,
                reason = "post_outcome_unknown",
                reservationKey = reservationKey,
                postAttempted = true,
                postOutcomeUnknown = true,
                detail = detail,
                pendingUnknownMarked = pendingMarked
            )
        )
        return unifiedOutcome(
            remoteEntries = remoteEntries,
            decision = decision,
            reason = "post_outcome_unknown",
            reconciledGrams = reconciledGrams,
            reconciledSeq = reconciledSeq,
            reconciliationValid = true,
            reserved = true,
            reservationKey = reservationKey,
            postAttempted = true,
            postOutcomeUnknown = true
        )
    }

    private suspend fun markPendingUnknown(
        store: UamExportReservationStore,
        reservationKey: String,
        detail: String
    ): Boolean = try {
        store.markPendingUnknown(reservationKey, detail)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (fatal: Error) {
        throw fatal
    } catch (_: Throwable) {
        false
    }

    private suspend fun recordPendingUnknownPreservingPrimaryFailure(
        candidate: UamExportPolicyInput,
        decision: UamExportDecision.Send,
        store: UamExportReservationStore,
        reservationKey: String,
        detail: String,
        auditMessage: String,
        auditReason: String,
        postOutcomeUnknown: Boolean,
        remoteId: String? = null
    ) {
        withContext(NonCancellable) {
            val pendingMarked = try {
                markPendingUnknown(
                    store = store,
                    reservationKey = reservationKey,
                    detail = detail
                )
            } catch (_: Throwable) {
                false
            }
            auditWarnBestEffort(
                auditMessage,
                decisionMetadata(
                    candidate = candidate,
                    decision = decision,
                    reason = auditReason,
                    reservationKey = reservationKey,
                    remoteId = remoteId,
                    postAttempted = true,
                    postOutcomeUnknown = postOutcomeUnknown,
                    detail = detail,
                    pendingUnknownMarked = pendingMarked
                )
            )
        }
    }

    private fun preflight(candidate: UamExportPolicyInput): UamExportDecision.Block? {
        if (candidate.episodeId.isBlank() || !UamTagCodec.isWellFormedEpisodeId(candidate.episodeId)) {
            return UamExportDecision.Block("invalid_episode_id")
        }
        if (candidate.nowTs < 0L) return UamExportDecision.Block("invalid_now_timestamp")
        if (candidate.sourceSnapshotTs < 0L) {
            return UamExportDecision.Block("invalid_source_snapshot_timestamp")
        }
        if (candidate.sourceSnapshotTs > candidate.nowTs) {
            return UamExportDecision.Block("source_snapshot_in_future")
        }
        if (candidate.nowTs - candidate.sourceSnapshotTs > MAX_SOURCE_SNAPSHOT_AGE_MS) {
            return UamExportDecision.Block("source_snapshot_stale")
        }
        if (!candidate.confidence.isNormalized()) return UamExportDecision.Block("invalid_confidence")
        if (!candidate.sensorTrust.isNormalized()) return UamExportDecision.Block("invalid_sensor_trust")
        if (!candidate.signedResidualMmol5.isFinite()) return UamExportDecision.Block("invalid_signed_residual")
        if (!candidate.shortAverageDeltaMmol5.isFinite()) return UamExportDecision.Block("invalid_short_average")
        if (!candidate.effectiveCobGrams.isFinite() || candidate.effectiveCobGrams < 0.0) {
            return UamExportDecision.Block("invalid_effective_cob")
        }
        if (!candidate.therapyCoverage.isNormalized()) return UamExportDecision.Block("invalid_therapy_coverage")
        if (
            !candidate.maximumIncrementGrams.isFinite() ||
            candidate.maximumIncrementGrams < UamExportPolicy.MIN_SEND_QUANTUM_G ||
            candidate.maximumIncrementGrams > UamExportPolicy.MAX_INCREMENT_G
        ) {
            return UamExportDecision.Block("invalid_maximum_increment")
        }
        val lowerBound = candidate.supportedLowerBoundGrams
        if (lowerBound == null || !lowerBound.isFinite() || lowerBound <= 0.0) {
            return UamExportDecision.Block("invalid_supported_lower_bound")
        }
        val activeSinceTs = candidate.activeSinceTs ?: return UamExportDecision.Block("active_time_missing")
        if (activeSinceTs < 0L) return UamExportDecision.Block("invalid_active_timestamp")
        if (activeSinceTs > candidate.nowTs) return UamExportDecision.Block("active_time_in_future")
        return null
    }

    private fun wallClockFreshnessBlock(candidate: UamExportPolicyInput): UamExportDecision.Block? {
        val wallNow = wallClockMs()
        if (wallNow < 0L) return UamExportDecision.Block("invalid_wall_clock_timestamp")
        if (candidate.sourceSnapshotTs > wallNow) {
            return UamExportDecision.Block("source_snapshot_in_future")
        }
        if (wallNow - candidate.sourceSnapshotTs > MAX_SOURCE_SNAPSHOT_AGE_MS) {
            return UamExportDecision.Block("source_snapshot_stale")
        }
        return null
    }

    private suspend fun releaseReservationBestEffort(
        store: UamExportReservationStore,
        reservationKey: String
    ): Boolean = withContext(NonCancellable) {
        runCatching {
            store.release(reservationKey)
            true
        }.getOrDefault(false)
    }

    private suspend fun fetchRemote(sinceTs: Long): Result<List<AapsCarbEntry>> = try {
        gateway.fetchCarbEntries(sinceTs)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (fatal: Error) {
        throw fatal
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private fun deduplicateLegacyRemoteEntries(entries: List<AapsCarbEntry>): List<AapsCarbEntry> {
        val byRemoteId = LinkedHashMap<String, AapsCarbEntry>()
        val conflictingIds = HashSet<String>()
        entries.forEach { entry ->
            val remoteId = entry.remoteId
            if (remoteId.isNullOrBlank()) return@forEach
            val previous = byRemoteId.putIfAbsent(remoteId, entry)
            if (previous != null && previous != entry) conflictingIds += remoteId
        }
        val emittedIds = HashSet<String>()
        return entries.filter { entry ->
            val remoteId = entry.remoteId
            remoteId.isNullOrBlank() ||
                (remoteId !in conflictingIds && emittedIds.add(remoteId))
        }
    }

    private suspend fun reconciliationFailure(
        candidate: UamExportPolicyInput,
        remoteEntries: List<AapsCarbEntry>,
        detail: String
    ): Outcome {
        auditWarnBestEffort(
            "uam_export_reconciliation_failed",
            mapOf(
                "episodeId" to candidate.episodeId,
                "reason" to "reconciliation_failed",
                "detail" to detail
            )
        )
        return unifiedOutcome(
            remoteEntries = remoteEntries,
            decision = UamExportDecision.Block("reconciliation_failed"),
            reason = "reconciliation_failed"
        )
    }

    private fun unifiedOutcome(
        remoteEntries: List<AapsCarbEntry> = emptyList(),
        decision: UamExportDecision,
        delivered: Boolean = false,
        reason: String,
        reconciledGrams: Double? = null,
        reconciledSeq: Int? = null,
        reconciliationValid: Boolean = false,
        reserved: Boolean = false,
        reservationKey: String? = null,
        remoteId: String? = null,
        postAttempted: Boolean = false,
        postOutcomeUnknown: Boolean = false
    ) = Outcome(
        events = emptyList(),
        remoteEntries = remoteEntries,
        decision = decision,
        delivered = delivered,
        reason = reason,
        reconciledGrams = reconciledGrams,
        reconciledSeq = reconciledSeq,
        reconciliationValid = reconciliationValid,
        reserved = reserved,
        reservationKey = reservationKey,
        remoteId = remoteId,
        postAttempted = postAttempted,
        postOutcomeUnknown = postOutcomeUnknown
    )

    private fun decisionMetadata(
        candidate: UamExportPolicyInput,
        decision: UamExportDecision,
        reason: String,
        reservationKey: String? = null,
        remoteId: String? = null,
        postAttempted: Boolean = false,
        postOutcomeUnknown: Boolean = false,
        detail: String? = null,
        pendingUnknownMarked: Boolean? = null
    ): Map<String, Any?> {
        val send = decision as? UamExportDecision.Send
        return mapOf(
            "episodeId" to candidate.episodeId,
            "reason" to reason,
            "grams" to send?.grams,
            "seq" to send?.seq,
            "tsMs" to send?.treatmentTs,
            "reservationKey" to reservationKey,
            "remoteId" to remoteId,
            "postAttempted" to postAttempted,
            "postOutcomeUnknown" to postOutcomeUnknown,
            "detail" to detail,
            "pendingUnknownMarked" to pendingUnknownMarked
        )
    }

    private fun failureDetail(kind: String, error: Throwable?, remoteId: String? = null): String {
        val type = error?.javaClass?.simpleName ?: "unknown"
        val message = error?.message.orEmpty().take(MAX_FAILURE_DETAIL_LENGTH)
        val remotePart = remoteId?.let { ":remoteId=${it.take(MAX_REMOTE_ID_DETAIL_LENGTH)}" }.orEmpty()
        return "$kind$remotePart:$type:$message".take(MAX_FAILURE_DETAIL_LENGTH)
    }

    private suspend fun auditInfoBestEffort(message: String, metadata: Map<String, Any?>) {
        try {
            auditLogger?.info(message, metadata)
        } catch (_: Throwable) {
            // Audit must never alter the export control outcome.
        }
    }

    private suspend fun auditWarnBestEffort(message: String, metadata: Map<String, Any?>) {
        try {
            auditLogger?.warn(message, metadata)
        } catch (_: Throwable) {
            // Audit must never alter the export control outcome.
        }
    }

    private fun Double.isNormalized(): Boolean = isFinite() && this in 0.0..1.0

    private fun isApparentV2UamTag(note: String?): Boolean {
        val source = note ?: return false
        if (!source.startsWith(UAM_TAG_PREFIX)) return false
        return source.split('|').any { token ->
            token.startsWith("id64=") || token == "ver=2"
        }
    }

    private fun reconciliationSince(nowTs: Long): Long {
        return if (nowTs <= FETCH_LOOKBACK_MS) 0L else nowTs - FETCH_LOOKBACK_MS
    }

    private data class TaggedRemote(
        val entry: AapsCarbEntry,
        val tag: UamTag
    )

    private companion object {
        const val FETCH_LOOKBACK_MS = 6 * 60 * 60_000L
        const val UNIFIED_TAG_VERSION = 2
        const val MAX_FAILURE_DETAIL_LENGTH = 160
        const val MAX_REMOTE_ID_DETAIL_LENGTH = 80
        const val UAM_TAG_PREFIX = "UAM_ENGINE|"
        const val MAX_SOURCE_SNAPSHOT_AGE_MS =
            UamExportPolicy.MAX_SOURCE_SNAPSHOT_AGE_MIN * 60_000L
        val unifiedProcessMutex = Mutex()
    }
}
