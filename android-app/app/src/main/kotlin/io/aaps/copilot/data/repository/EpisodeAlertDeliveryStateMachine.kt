package io.aaps.copilot.data.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.aaps.copilot.data.local.entity.AlertDeliveryReceiptEntity
import io.aaps.copilot.data.local.entity.AlertEventEntity
import io.aaps.copilot.domain.alerts.AlertCauseAnalysis
import io.aaps.copilot.domain.alerts.AlertCauseAnalyzer
import io.aaps.copilot.domain.alerts.AlertCauseCode
import io.aaps.copilot.domain.alerts.AlertCauseDirection
import io.aaps.copilot.domain.alerts.AlertCauseSnapshot
import io.aaps.copilot.domain.alerts.AlertCauseSnapshotCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock

interface EpisodeAlertReceiptStore {
    val coordinationIdentity: Any
        get() = this

    suspend fun <T> transaction(block: suspend EpisodeAlertReceiptStore.() -> T): T
    suspend fun latestUnresolvedGlucoseEpisode(): AlertEventEntity?
    suspend fun maxGlucoseEpisodeSequence(): Long
    suspend fun eventById(episodeId: String): AlertEventEntity?
    suspend fun upsertEvent(event: AlertEventEntity)
    suspend fun receipt(episodeId: String, kind: AlertDeliveryKind): AlertDeliveryReceiptEntity?
    suspend fun insertReceipt(receipt: AlertDeliveryReceiptEntity): Boolean
    suspend fun updateReceipt(receipt: AlertDeliveryReceiptEntity)
    suspend fun muteUntil(): Long
    fun observeMuteUntil(): Flow<Long>
    suspend fun writeMuteUntil(until: Long, nowTs: Long)
}

enum class AlertDeliveryKind {
    INITIAL,
    LOW_NOW_ESCALATION,
    SUPPRESSED_SNOOZE
}

enum class AlertReceiptResult {
    CLAIMED,
    DELIVERED,
    FAILED,
    SUPPRESSED
}

enum class AlertEpisodeStatus {
    OPEN,
    SAFE_PENDING,
    RESOLVED
}

data class EpisodeAlertSignal(
    val stage: GlucoseAlertState,
    val direction: GlucoseAlertDirection?,
    val nowTs: Long,
    val causeAnalysis: AlertCauseAnalysis? = null,
    val causeSnapshot: AlertCauseSnapshot? = null
)

data class EpisodeDeliveryClaim(
    val episodeId: String,
    val kind: AlertDeliveryKind,
    val notificationTag: String,
    val notificationId: Int,
    val persistedCause: AlertCauseCode?,
    val claimedStage: GlucoseAlertState
)

data class InitialAlertDelivery(
    val episodeId: String,
    val requestedAt: Long,
    val stage: String,
    val direction: AlertCauseDirection,
    val localCauseSnapshot: AlertCauseSnapshot
) {
    init {
        require(episodeId.length in 1..128)
        require(requestedAt > 0L)
        require(stage.length in 1..64)
    }
}

fun interface EpisodeAlertPostCommitObserver {
    fun onInitialDelivered(delivery: InitialAlertDelivery)
}

data class AlertSideEffectResult(
    val receiptResult: AlertReceiptResult,
    val failureReason: String? = null
) {
    init {
        require(receiptResult in setOf(AlertReceiptResult.DELIVERED, AlertReceiptResult.FAILED))
    }

    companion object {
        fun delivered(): AlertSideEffectResult = AlertSideEffectResult(AlertReceiptResult.DELIVERED)
        fun failed(reason: String): AlertSideEffectResult = AlertSideEffectResult(
            receiptResult = AlertReceiptResult.FAILED,
            failureReason = reason.take(160)
        )
    }
}

data class EpisodeCoordinationResult(
    val episodeId: String?,
    val deliveryKind: AlertDeliveryKind?,
    val receiptResult: AlertReceiptResult?,
    val sideEffectAttempted: Boolean
)

data class EpisodeMuteToggleResult(
    val action: GlucoseAlertBellAction,
    val previousMutedUntilTs: Long,
    val mutedUntilTs: Long
)

/**
 * Serializes lifecycle, snooze and notification claims. SQLite commits the
 * claim before Android notify/audio/vibration. A process death in that narrow
 * window leaves CLAIMED; after the lease the same notification tag/id may be
 * reused, so Android updates the same visible notification instead of adding a
 * second one. SQLite and NotificationManager cannot form one atomic commit.
 */
class EpisodeAlertDeliveryStateMachine(
    private val store: EpisodeAlertReceiptStore,
    private val mirrorMuteUntil: suspend (Long) -> Unit = {},
    private val clearRiskSideEffects: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val parseAlertSnapshot: (String) -> JsonObject = { json ->
        JsonParser.parseString(json).asJsonObject
    },
    private val postCommitObserver: EpisodeAlertPostCommitObserver =
        EpisodeAlertPostCommitObserver {}
) {
    private val mutex = EpisodeAlertOperationLocks.forIdentity(store.coordinationIdentity)
    val mutedUntil: Flow<Long> = store.observeMuteUntil().withExactMuteExpiry(clock)

    suspend fun coordinate(
        signal: EpisodeAlertSignal,
        deliver: suspend (EpisodeDeliveryClaim) -> AlertSideEffectResult
    ): EpisodeCoordinationResult = mutex.withLock {
        val prepared = store.transaction { prepare(signal) }
        val claim = prepared.claim ?: return@withLock prepared.result
        val sideEffect = try {
            deliver(claim)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AlertSideEffectResult.failed(error::class.java.simpleName.ifBlank { "delivery_error" })
        }
        val initialDelivery = store.transaction {
            val persisted = receipt(claim.episodeId, claim.kind)
            if (persisted?.result == AlertReceiptResult.CLAIMED.name) {
                updateReceipt(
                    persisted.copy(
                        result = sideEffect.receiptResult.name,
                        deliveredAt = if (sideEffect.receiptResult == AlertReceiptResult.DELIVERED) signal.nowTs else null,
                        sanitizedError = sideEffect.failureReason
                    )
                )
                if (sideEffect.receiptResult == AlertReceiptResult.DELIVERED) {
                    eventById(claim.episodeId)?.let { event ->
                        val deliveredEvent = event.copy(
                            lastNotificationAt = signal.nowTs,
                            updatedAt = maxOf(event.updatedAt, signal.nowTs),
                            revision = event.revision + 1L
                        )
                        upsertEvent(deliveredEvent)
                        return@transaction deliveredEvent.initialDeliveryOrNull(
                            claim = claim,
                            requestedAt = signal.nowTs
                        )
                    }
                }
            }
            null
        }
        initialDelivery?.let(::notifyPostCommitBestEffort)
        prepared.result.copy(
            receiptResult = sideEffect.receiptResult,
            sideEffectAttempted = true
        )
    }

    private fun AlertEventEntity.initialDeliveryOrNull(
        claim: EpisodeDeliveryClaim,
        requestedAt: Long
    ): InitialAlertDelivery? {
        if (claim.kind != AlertDeliveryKind.INITIAL) return null
        val decoded = AlertEpisodeCauseSnapshotCodec.decode(
            localSnapshotJson,
            parseAlertSnapshot
        ) ?: return null
        if (decoded.initialStage != claim.claimedStage.name) return null
        val claimCause = claim.persistedCause ?: return null
        val eventCause = causeCode.toAlertCauseCodeOrNull() ?: return null
        if (decoded.cause != claimCause || decoded.cause != eventCause) return null
        val direction = when (eventType) {
            "${EVENT_TYPE_PREFIX}${GlucoseAlertDirection.LOW.name}" -> AlertCauseDirection.LOW
            "${EVENT_TYPE_PREFIX}${GlucoseAlertDirection.HIGH.name}" -> AlertCauseDirection.HIGH
            else -> return null
        }
        return InitialAlertDelivery(
            episodeId = episodeId,
            requestedAt = requestedAt,
            stage = decoded.initialStage,
            direction = direction,
            localCauseSnapshot = decoded.localCauseSnapshot
        )
    }

    private fun notifyPostCommitBestEffort(delivery: InitialAlertDelivery) {
        try {
            postCommitObserver.onInitialDelivered(delivery)
        } catch (_: Exception) {
            // Receipt, event and visible notification are already committed and remain authoritative.
        }
    }

    suspend fun muteFor(nowTs: Long, durationMs: Long): Long = mutex.withLock {
        muteForLocked(nowTs, durationMs)
    }

    suspend fun resume(nowTs: Long = System.currentTimeMillis()) = mutex.withLock {
        resumeLocked(nowTs)
    }

    suspend fun muteFromNotification(nowTs: Long, durationMs: Long): Long =
        muteFor(nowTs, durationMs)

    suspend fun currentMutedUntil(): Long = mutex.withLock {
        store.transaction { muteUntil() }
    }

    suspend fun coordinateMutedSideEffect(operation: suspend (muted: Boolean) -> Unit) = mutex.withLock {
        val until = store.transaction { muteUntil() }
        operation(until > clock())
    }

    suspend fun toggleFromOverview(nowTs: Long): EpisodeMuteToggleResult = mutex.withLock {
        val transition = store.transaction {
            val previous = muteUntil()
            when (glucoseAlertBellAction(previous, nowTs)) {
                GlucoseAlertBellAction.RESUME -> {
                    writeMuteUntil(0L, nowTs)
                    EpisodeMuteToggleResult(
                        action = GlucoseAlertBellAction.RESUME,
                        previousMutedUntilTs = previous,
                        mutedUntilTs = 0L
                    )
                }
                GlucoseAlertBellAction.MUTE_30_MINUTES -> {
                    val effectiveUntil = maxOf(
                        previous,
                        safeAdd(nowTs, GlucoseAlertMuteOption.MINUTES_30.durationMs)
                    )
                    writeMuteUntil(effectiveUntil, nowTs)
                    EpisodeMuteToggleResult(
                        action = GlucoseAlertBellAction.MUTE_30_MINUTES,
                        previousMutedUntilTs = previous,
                        mutedUntilTs = effectiveUntil
                    )
                }
            }
        }
        clearRiskSideEffects()
        mirrorMuteBestEffort(transition.mutedUntilTs)
        transition
    }

    suspend fun importLegacyIfNeeded(
        legacy: GlucoseAlertRuntimeState,
        nowTs: Long
    ): String? = mutex.withLock {
        store.transaction {
            eventById(LEGACY_IMPORT_MARKER_ID)?.let { marker ->
                return@transaction marker.causeSummary?.takeIf { it.isNotBlank() }
            }
            val existing = latestUnresolvedGlucoseEpisode()
            val importedEpisodeId = when {
                existing != null -> existing.episodeId
                legacy.activeAlertState == GlucoseAlertState.NONE || legacy.activeDirection == null -> null
                else -> {
                    val sequence = nextSequence(nowTs)
                    val episodeId = episodeId(sequence)
                    val event = newEvent(
                        episodeId = episodeId,
                        sequence = sequence,
                        signal = EpisodeAlertSignal(
                            stage = legacy.activeAlertState,
                            direction = legacy.activeDirection,
                            nowTs = nowTs
                        )
                    )
                    upsertEvent(event)
                    insertReceipt(
                        receiptEntity(
                            episodeId = episodeId,
                            kind = AlertDeliveryKind.INITIAL,
                            attemptedAt = nowTs,
                            result = AlertReceiptResult.DELIVERED,
                            deliveredAt = listOf(legacy.lastSoftAlertAtTs, legacy.lastStrongAlertAtTs)
                                .maxOrNull()
                                ?.takeIf { it > 0L }
                                ?: nowTs
                        )
                    )
                    episodeId
                }
            }
            upsertEvent(
                AlertEventEntity(
                    episodeId = LEGACY_IMPORT_MARKER_ID,
                    eventType = "GLUCOSE_ALERT_IMPORT_MARKER",
                    stage = "IMPORTED",
                    status = AlertEpisodeStatus.RESOLVED.name,
                    severity = "SYSTEM",
                    createdAt = nowTs,
                    updatedAt = nowTs,
                    resolvedAt = Long.MAX_VALUE,
                    localSnapshotJson = "{}",
                    causeCode = "V26_LEGACY_IMPORT",
                    causeSummary = importedEpisodeId,
                    suppressionUntil = null,
                    lastNotificationAt = null,
                    revision = 1L
                )
            )
            importedEpisodeId
        }
    }

    private suspend fun muteForLocked(nowTs: Long, durationMs: Long): Long {
        require(durationMs > 0L)
        val requestedUntil = safeAdd(nowTs, durationMs)
        val effectiveUntil = store.transaction {
            maxOf(muteUntil(), requestedUntil).also { writeMuteUntil(it, nowTs) }
        }
        clearRiskSideEffects()
        mirrorMuteBestEffort(effectiveUntil)
        return effectiveUntil
    }

    private suspend fun resumeLocked(nowTs: Long) {
        store.transaction { writeMuteUntil(0L, nowTs) }
        clearRiskSideEffects()
        mirrorMuteBestEffort(0L)
    }

    private suspend fun mirrorMuteBestEffort(untilTs: Long) {
        try {
            mirrorMuteUntil(untilTs)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Room is authoritative; the legacy DataStore mirror must not block mute actions.
        }
    }

    private suspend fun EpisodeAlertReceiptStore.prepare(signal: EpisodeAlertSignal): PreparedDelivery {
        var active = latestUnresolvedGlucoseEpisode()
        if (signal.stage == GlucoseAlertState.NONE || signal.direction == null) {
            if (active == null) return PreparedDelivery(EpisodeCoordinationResult(null, null, null, false))
            val next = when (active.status) {
                AlertEpisodeStatus.SAFE_PENDING.name -> {
                    if (signal.nowTs - active.updatedAt >= SAFE_RESOLUTION_MS) {
                        active.copy(
                            status = AlertEpisodeStatus.RESOLVED.name,
                            updatedAt = signal.nowTs,
                            resolvedAt = signal.nowTs,
                            revision = active.revision + 1L
                        )
                    } else {
                        active
                    }
                }
                else -> active.copy(
                    status = AlertEpisodeStatus.SAFE_PENDING.name,
                    updatedAt = signal.nowTs,
                    revision = active.revision + 1L
                )
            }
            if (next !== active) upsertEvent(next)
            return PreparedDelivery(EpisodeCoordinationResult(active.episodeId, null, null, false))
        }

        val activeDirection = active?.eventType?.removePrefix(EVENT_TYPE_PREFIX)
        if (active != null && activeDirection != signal.direction.name) {
            upsertEvent(
                active.copy(
                    status = AlertEpisodeStatus.RESOLVED.name,
                    updatedAt = signal.nowTs,
                    resolvedAt = signal.nowTs,
                    revision = active.revision + 1L
                )
            )
            active = null
        }

        if (active == null) {
            val sequence = nextSequence(signal.nowTs)
            val event = newEvent(episodeId(sequence), sequence, signal)
            upsertEvent(event)
            val muted = signal.nowTs < muteUntil()
            return if (muted) {
                insertReceipt(
                    receiptEntity(
                        episodeId = event.episodeId,
                        kind = AlertDeliveryKind.SUPPRESSED_SNOOZE,
                        attemptedAt = signal.nowTs,
                        result = AlertReceiptResult.SUPPRESSED,
                        suppressionUntil = muteUntil()
                    )
                )
                PreparedDelivery(
                    EpisodeCoordinationResult(
                        episodeId = event.episodeId,
                        deliveryKind = AlertDeliveryKind.SUPPRESSED_SNOOZE,
                        receiptResult = AlertReceiptResult.SUPPRESSED,
                        sideEffectAttempted = false
                    )
                )
            } else {
                prepareClaim(event.episodeId, AlertDeliveryKind.INITIAL, signal.nowTs)
            }
        }

        val incomingPayload = signal.validCausePayload(
            initialStage = initialStage(active.localSnapshotJson) ?: active.stage
        )
        val initialEpisodeStage = initialStage(active.localSnapshotJson) ?: active.stage
        val decodedPersistedCause = active.causeCode.toAlertCauseCodeOrNull()
        val authoritativeCause = active.causeCode?.let { decodedPersistedCause ?: AlertCauseCode.UNKNOWN }
        val persistedPayload = sanitizedEpisodePayload(active.localSnapshotJson, initialEpisodeStage)
        val fillLegacyCause = active.causeCode == null && incomingPayload != null
        val repairPersistedSnapshot = authoritativeCause != null &&
            (decodedPersistedCause == null || persistedPayload?.cause != authoritativeCause)
        val repairedSnapshotJson = if (repairPersistedSnapshot) {
            requireNotNull(
                AlertEpisodeCauseSnapshotCodec.encode(
                    initialStage = initialEpisodeStage,
                    localCauseSnapshot = AlertCauseSnapshotCodec.repairSnapshot(authoritativeCause)
                )
            ).canonicalEpisodeJson
        } else {
            null
        }
        val updated = active.copy(
            stage = signal.stage.name,
            severity = signal.stage.name,
            status = AlertEpisodeStatus.OPEN.name,
            updatedAt = signal.nowTs,
            resolvedAt = null,
            localSnapshotJson = when {
                fillLegacyCause -> requireNotNull(incomingPayload).episodeSnapshotJson
                repairPersistedSnapshot -> requireNotNull(repairedSnapshotJson)
                else -> active.localSnapshotJson
            },
            causeCode = when {
                fillLegacyCause -> incomingPayload?.cause?.name
                authoritativeCause != null -> authoritativeCause.name
                else -> null
            },
            causeSummary = if (fillLegacyCause) {
                incomingPayload?.cause?.let(AlertCauseAnalyzer::fixedAdvice)
            } else if (repairPersistedSnapshot || active.causeSummary == null) {
                authoritativeCause?.let(AlertCauseAnalyzer::fixedAdvice)
            } else {
                active.causeSummary
            },
            revision = active.revision + 1L
        )
        upsertEvent(updated)
        if (receipt(active.episodeId, AlertDeliveryKind.SUPPRESSED_SNOOZE) != null) {
            return PreparedDelivery(EpisodeCoordinationResult(active.episodeId, null, null, false))
        }

        val initial = receipt(active.episodeId, AlertDeliveryKind.INITIAL)
        if (initial?.result == AlertReceiptResult.CLAIMED.name &&
            initial.attemptedAt <= signal.nowTs - CLAIM_LEASE_MS
        ) {
            return recoverClaim(initial, signal.nowTs)
        }
        val initialStage = initialStage(updated.localSnapshotJson)
        if (
            signal.stage == GlucoseAlertState.LOW_NOW &&
            initialStage != GlucoseAlertState.LOW_NOW.name &&
            initial?.result == AlertReceiptResult.DELIVERED.name &&
            signal.nowTs >= muteUntil()
        ) {
            return prepareClaim(active.episodeId, AlertDeliveryKind.LOW_NOW_ESCALATION, signal.nowTs)
        }
        return PreparedDelivery(EpisodeCoordinationResult(active.episodeId, null, null, false))
    }

    private suspend fun EpisodeAlertReceiptStore.prepareClaim(
        episodeId: String,
        kind: AlertDeliveryKind,
        nowTs: Long
    ): PreparedDelivery {
        val existing = receipt(episodeId, kind)
        if (existing != null) {
            return if (
                existing.result == AlertReceiptResult.CLAIMED.name &&
                existing.attemptedAt <= nowTs - CLAIM_LEASE_MS
            ) {
                recoverClaim(existing, nowTs)
            } else {
                PreparedDelivery(EpisodeCoordinationResult(episodeId, null, null, false))
            }
        }
        val inserted = insertReceipt(
            receiptEntity(
                episodeId = episodeId,
                kind = kind,
                attemptedAt = nowTs,
                result = AlertReceiptResult.CLAIMED
            )
        )
        if (!inserted) return PreparedDelivery(EpisodeCoordinationResult(episodeId, null, null, false))
        val claim = deliveryClaim(episodeId, kind)
        return PreparedDelivery(
            result = EpisodeCoordinationResult(episodeId, kind, AlertReceiptResult.CLAIMED, false),
            claim = claim
        )
    }

    private suspend fun EpisodeAlertReceiptStore.recoverClaim(
        receipt: AlertDeliveryReceiptEntity,
        nowTs: Long
    ): PreparedDelivery {
        val kind = AlertDeliveryKind.valueOf(receipt.kind)
        updateReceipt(receipt.copy(attemptedAt = nowTs, sanitizedError = null))
        return PreparedDelivery(
            result = EpisodeCoordinationResult(receipt.episodeId, kind, AlertReceiptResult.CLAIMED, false),
            claim = deliveryClaim(receipt.episodeId, kind)
        )
    }

    private suspend fun EpisodeAlertReceiptStore.nextSequence(nowTs: Long): Long {
        val previousState = eventById(SEQUENCE_STATE_ID)
        val previous = maxOf(
            maxGlucoseEpisodeSequence(),
            previousState?.createdAt ?: 0L
        )
        val next = maxOf(nowTs.coerceAtLeast(1L), safeAdd(previous, 1L))
        upsertEvent(
            AlertEventEntity(
                episodeId = SEQUENCE_STATE_ID,
                eventType = "GLUCOSE_ALERT_SEQUENCE_STATE",
                stage = "SEQUENCE",
                status = AlertEpisodeStatus.RESOLVED.name,
                severity = "SYSTEM",
                createdAt = next,
                updatedAt = nowTs,
                resolvedAt = Long.MAX_VALUE,
                localSnapshotJson = "{}",
                causeCode = "MONOTONIC_SEQUENCE",
                causeSummary = null,
                suppressionUntil = null,
                lastNotificationAt = null,
                revision = (previousState?.revision ?: 0L) + 1L
            )
        )
        return next
    }

    private fun newEvent(
        episodeId: String,
        sequence: Long,
        signal: EpisodeAlertSignal
    ): AlertEventEntity {
        val payload = signal.validCausePayload(signal.stage.name)
        return AlertEventEntity(
            episodeId = episodeId,
            eventType = EVENT_TYPE_PREFIX + requireNotNull(signal.direction).name,
            stage = signal.stage.name,
            status = AlertEpisodeStatus.OPEN.name,
            severity = signal.stage.name,
            createdAt = sequence,
            updatedAt = signal.nowTs,
            resolvedAt = null,
            localSnapshotJson = payload?.episodeSnapshotJson ?: requireNotNull(
                AlertEpisodeCauseSnapshotCodec.encodeMinimal(signal.stage.name)
            ),
            causeCode = payload?.cause?.name,
            causeSummary = payload?.cause?.let(AlertCauseAnalyzer::fixedAdvice),
            suppressionUntil = null,
            lastNotificationAt = null,
            revision = 1L
        )
    }

    private fun receiptEntity(
        episodeId: String,
        kind: AlertDeliveryKind,
        attemptedAt: Long,
        result: AlertReceiptResult,
        suppressionUntil: Long? = null,
        deliveredAt: Long? = null
    ) = AlertDeliveryReceiptEntity(
        receiptId = "$episodeId|${kind.name}",
        episodeId = episodeId,
        kind = kind.name,
        attemptedAt = attemptedAt,
        result = result.name,
        suppressionUntil = suppressionUntil,
        deliveredAt = deliveredAt,
        sanitizedError = null
    )

    private suspend fun EpisodeAlertReceiptStore.deliveryClaim(
        episodeId: String,
        kind: AlertDeliveryKind
    ): EpisodeDeliveryClaim {
        val event = eventById(episodeId)
        val persistedInitialStage = event?.let {
            initialStage(it.localSnapshotJson)?.toGlucoseAlertStateOrNull()
        }
        return EpisodeDeliveryClaim(
            episodeId = episodeId,
            kind = kind,
            notificationTag = GLUCOSE_NOTIFICATION_TAG,
            notificationId = GLUCOSE_NOTIFICATION_ID,
            persistedCause = event?.causeCode.toAlertCauseCodeOrNull(),
            claimedStage = when (kind) {
                AlertDeliveryKind.LOW_NOW_ESCALATION -> GlucoseAlertState.LOW_NOW
                AlertDeliveryKind.INITIAL -> persistedInitialStage
                    ?: event?.stage.toGlucoseAlertStateOrNull()
                    ?: GlucoseAlertState.NONE
                AlertDeliveryKind.SUPPRESSED_SNOOZE -> GlucoseAlertState.NONE
            }
        )
    }

    private fun String?.toAlertCauseCodeOrNull(): AlertCauseCode? =
        this?.let { code -> AlertCauseCode.entries.firstOrNull { it.name == code } }

    private fun String?.toGlucoseAlertStateOrNull(): GlucoseAlertState? =
        this?.let { stage -> GlucoseAlertState.entries.firstOrNull { it.name == stage } }

    private fun initialStage(snapshotJson: String): String? =
        AlertEpisodeCauseSnapshotCodec.initialStage(snapshotJson, parseAlertSnapshot)

    private fun EpisodeAlertSignal.validCausePayload(
        initialStage: String
    ): ValidCausePayload? {
        val analysis = causeAnalysis ?: return null
        val snapshot = causeSnapshot ?: return null
        val encoded = AlertEpisodeCauseSnapshotCodec.encode(
            initialStage = initialStage,
            localCauseSnapshot = snapshot
        ) ?: return null
        if (encoded.cause != analysis.primary) return null
        return ValidCausePayload(encoded.cause, encoded.canonicalEpisodeJson)
    }

    private fun sanitizedEpisodePayload(
        snapshotJson: String,
        initialStage: String
    ): ValidCausePayload? {
        val decoded = AlertEpisodeCauseSnapshotCodec.decode(
            snapshotJson,
            parseAlertSnapshot
        ) ?: return null
        if (decoded.initialStage != initialStage) return null
        return ValidCausePayload(decoded.cause, decoded.canonicalEpisodeJson)
    }

    private fun episodeId(sequence: Long): String = "glucose-alert-%019d".format(sequence)

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private data class PreparedDelivery(
        val result: EpisodeCoordinationResult,
        val claim: EpisodeDeliveryClaim? = null
    )

    private data class ValidCausePayload(
        val cause: AlertCauseCode,
        val episodeSnapshotJson: String
    )

    companion object {
        internal const val CLAIM_LEASE_MS = 60_000L
        internal const val MUTE_STATE_ID = "glucose-alert-mute-state"
        internal const val LEGACY_IMPORT_MARKER_ID = "glucose-alert-v26-import"
        internal const val SEQUENCE_STATE_ID = "glucose-alert-sequence-state"
        internal const val SAFE_RESOLUTION_MS = 15 * 60_000L
        internal const val NOTIFICATION_TAG_PREFIX = "copilot.glucose."
        internal const val GLUCOSE_NOTIFICATION_TAG = "copilot.glucose.alert"
        internal const val GLUCOSE_NOTIFICATION_ID = 31_100
        private const val EVENT_TYPE_PREFIX = "GLUCOSE_ALERT_"
    }
}
