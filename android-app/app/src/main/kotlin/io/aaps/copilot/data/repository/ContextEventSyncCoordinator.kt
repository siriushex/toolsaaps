package io.aaps.copilot.data.repository

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import io.aaps.copilot.data.local.dao.ContextEventSyncDao
import io.aaps.copilot.data.local.dao.PhysioContextTagDao
import io.aaps.copilot.data.local.entity.ContextEventSyncEntity
import io.aaps.copilot.data.local.entity.PhysioContextTagEntity
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventManualPolicy
import io.aaps.copilot.domain.events.CompensationEventStatus
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventSeverity
import io.aaps.copilot.domain.events.EventSource
import io.aaps.copilot.domain.profile.PhysiologicalSex
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ContextEventSyncCoordinator(
    private val contextTagDao: PhysioContextTagDao,
    private val syncDao: ContextEventSyncDao,
    private val transactionRunner: ContextEventTransactionRunner,
    private val gateway: ContextEventGateway,
    private val gson: Gson,
    private val now: () -> Long = System::currentTimeMillis,
    private val physiologicalSex: suspend () -> PhysiologicalSex = {
        PhysiologicalSex.UNSPECIFIED
    },
    private val onDurableMutationApplied: suspend () -> Unit = {}
) {
    private val commandMutex = Mutex()

    suspend fun save(
        event: CompensationEvent,
        expectedRevision: Long?
    ): ContextEventCommandResult = commandMutex.withLock {
        if (event.source != EventSource.USER || !CompensationEventManualPolicy.isContextOnly(event.type)) {
            return@withLock ContextEventCommandResult.REJECTED
        }
        val currentSex = try {
            physiologicalSex()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock ContextEventCommandResult.REJECTED
        }
        val candidate = try {
            CompensationEventManualPolicy.normalizeContextOrNull(
                event = event,
                physiologicalSex = currentSex,
                nowTs = now()
            )
        } catch (_: IllegalArgumentException) {
            null
        } ?: return@withLock ContextEventCommandResult.REJECTED
        val attributesJson = encodeAttributes(candidate.attributes)
            ?: return@withLock ContextEventCommandResult.REJECTED
        val existing = contextTagDao.byId(candidate.localId)
        if (!matchesExpectedRevision(existing, expectedRevision)) {
            return@withLock ContextEventCommandResult.CONFLICT
        }
        val unresolved = syncDao.firstUnresolvedForEvent(candidate.localId)
        if (unresolved != null) {
            return@withLock retryFailedSaveIfExact(candidate, attributesJson, unresolved)
        }
        val latestAudit = syncDao.latestForEvent(candidate.localId)
        if (
            existing != null &&
            existing.matches(candidate, attributesJson) &&
            latestAudit?.revision == existing.revision &&
            latestAudit.operation in SAVE_OPERATIONS &&
            digestsEqual(
                requestHash(existing.toEvent(), latestAudit.operation),
                requestDigest(latestAudit.requestHash)
            )
        ) {
            when (latestAudit.status) {
                STATUS_COMPLETED -> return@withLock ContextEventCommandResult.ACKNOWLEDGED
                STATUS_FAILED -> return@withLock ContextEventCommandResult.FAILED
            }
        }
        val revisionBase = maxOf(existing?.revision ?: 0L, latestAudit?.revision ?: 0L)
        applySave(
            event = candidate,
            attributesJson = attributesJson,
            existing = existing,
            revisionBase = revisionBase,
            operation = if (
                existing == null ||
                latestAudit?.let {
                    it.operation == AapsContextEventGateway.Operation.DELETE.name &&
                        it.status == STATUS_COMPLETED
                } == true
            ) {
                AapsContextEventGateway.Operation.CREATE
            } else {
                AapsContextEventGateway.Operation.UPDATE
            }
        )
    }

    private suspend fun retryFailedSaveIfExact(
        candidate: CompensationEvent,
        attributesJson: String,
        unresolved: ContextEventSyncEntity
    ): ContextEventCommandResult {
        if (unresolved.status == STATUS_PENDING) return ContextEventCommandResult.PENDING
        if (unresolved.status != STATUS_FAILED || unresolved.operation !in SAVE_OPERATIONS) {
            return ContextEventCommandResult.CONFLICT
        }
        val operation = unresolved.operation.toOperationOrNull()
            ?: return ContextEventCommandResult.CONFLICT
        val immutableEvent = eventFromAudit(
            audit = unresolved,
            operation = operation,
            allowedStatuses = setOf(STATUS_FAILED)
        ) ?: return ContextEventCommandResult.CONFLICT
        val retryCandidate = candidate.toEntity(
            storedRevision = unresolved.revision,
            storedUpdatedAt = 1L,
            storedAttributesJson = attributesJson
        ).toEvent()
        if (!digestsEqual(requestHash(retryCandidate, unresolved.operation), requestDigest(unresolved.requestHash))) {
            return ContextEventCommandResult.CONFLICT
        }
        return dispatchPending(immutableEvent, operation, unresolved)
    }

    private suspend fun applySave(
        event: CompensationEvent,
        attributesJson: String,
        existing: PhysioContextTagEntity?,
        revisionBase: Long,
        operation: AapsContextEventGateway.Operation
    ): ContextEventCommandResult {
        val revision = nextRevision(revisionBase)
            ?: return ContextEventCommandResult.REJECTED
        val stored = event.toEntity(
            storedRevision = revision,
            storedUpdatedAt = monotonicTimestamp(existing?.updatedAt),
            storedAttributesJson = attributesJson
        )
        if (!isExportEligible(event)) {
            contextTagDao.upsert(stored)
            notifyDurableMutationApplied()
            return ContextEventCommandResult.LOCAL_ONLY
        }
        val outbound = stored.toEvent()
        val prepared = transactionRunner.run {
            preparePending(outbound, operation)
        }
        return dispatchPrepared(outbound, operation, prepared)
    }

    suspend fun close(
        eventId: String,
        expectedRevision: Long
    ): ContextEventCommandResult = commandMutex.withLock {
        closeAfterReconciliation(eventId, expectedRevision, now())
    }

    suspend fun closeAllActive(): List<ContextEventCommandResult> = commandMutex.withLock {
        val closeTs = now()
        contextTagDao.activeAt(closeTs)
            .filterNot { it.status == CompensationEventStatus.CLOSED.name }
            .map { existing -> closeAfterReconciliation(existing.id, existing.revision, closeTs) }
    }

    private suspend fun closeAfterReconciliation(
        eventId: String,
        expectedRevision: Long,
        closeTs: Long
    ): ContextEventCommandResult {
        val existing = contextTagDao.byId(eventId)
            ?: return ContextEventCommandResult.NOT_FOUND
        if (existing.revision != expectedRevision) return ContextEventCommandResult.CONFLICT
        syncDao.firstUnresolvedForEvent(eventId)?.let { unresolved ->
            return retryFailedLifecycleIfExact(
                existing = existing,
                expectedOperation = AapsContextEventGateway.Operation.CLOSE,
                unresolved = unresolved
            )
        }
        return closeExisting(existing, closeTs)
    }

    private suspend fun closeExisting(
        existing: PhysioContextTagEntity,
        closeTs: Long
    ): ContextEventCommandResult {
        val closed = if (existing.status == CompensationEventStatus.CLOSED.name) {
            existing
        } else {
            val revision = nextRevision(existing.revision)
                ?: return ContextEventCommandResult.REJECTED
            existing.closeAt(closeTs, revision)
        }
        if (!isExportEligible(existing)) {
            if (closed !== existing) {
                contextTagDao.upsert(closed)
                notifyDurableMutationApplied()
            }
            return ContextEventCommandResult.LOCAL_ONLY
        }
        val outbound = CompensationEventManualPolicy.validateCloseOrNull(closed.toEvent(), closeTs)
            ?: return ContextEventCommandResult.REJECTED
        val operation = AapsContextEventGateway.Operation.CLOSE
        val prepared = transactionRunner.run {
            preparePending(outbound, operation)
        }
        return dispatchPrepared(outbound, operation, prepared)
    }

    suspend fun delete(
        eventId: String,
        expectedRevision: Long
    ): ContextEventCommandResult = commandMutex.withLock {
        val existing = contextTagDao.byId(eventId)
        if (existing == null) {
            val latest = syncDao.latestForEvent(eventId)
            return@withLock if (
                latest?.operation == AapsContextEventGateway.Operation.DELETE.name &&
                latest.status == STATUS_COMPLETED
            ) {
                ContextEventCommandResult.ACKNOWLEDGED
            } else {
                ContextEventCommandResult.NOT_FOUND
            }
        }
        if (existing.revision != expectedRevision) return@withLock ContextEventCommandResult.CONFLICT
        syncDao.firstUnresolvedForEvent(eventId)?.let { unresolved ->
            return@withLock retryFailedLifecycleIfExact(
                existing = existing,
                expectedOperation = AapsContextEventGateway.Operation.DELETE,
                unresolved = unresolved
            )
        }
        if (!isExportEligible(existing)) {
            if (contextTagDao.deleteByIdAndRevision(eventId, existing.revision) > 0) {
                notifyDurableMutationApplied()
            }
            return@withLock ContextEventCommandResult.LOCAL_ONLY
        }
        val deleteRevision = nextRevision(existing.revision)
            ?: return@withLock ContextEventCommandResult.REJECTED
        val outbound = CompensationEventManualPolicy.validateDeleteOrNull(
            existing.toEvent().copy(revision = deleteRevision),
            now()
        ) ?: return@withLock ContextEventCommandResult.REJECTED
        val operation = AapsContextEventGateway.Operation.DELETE
        val prepared = transactionRunner.run {
            preparePending(outbound, operation)
        }
        dispatchPrepared(outbound, operation, prepared)
    }

    private suspend fun retryFailedLifecycleIfExact(
        existing: PhysioContextTagEntity,
        expectedOperation: AapsContextEventGateway.Operation,
        unresolved: ContextEventSyncEntity
    ): ContextEventCommandResult {
        if (unresolved.status == STATUS_PENDING) return ContextEventCommandResult.PENDING
        if (
            unresolved.status != STATUS_FAILED ||
            unresolved.operation != expectedOperation.name ||
            unresolved.revision != nextRevision(existing.revision)
        ) {
            return ContextEventCommandResult.CONFLICT
        }
        val immutableEvent = eventFromAudit(
            audit = unresolved,
            operation = expectedOperation,
            allowedStatuses = setOf(STATUS_FAILED)
        ) ?: return ContextEventCommandResult.CONFLICT
        return dispatchPending(immutableEvent, expectedOperation, unresolved)
    }

    suspend fun reconcileAllPending(
        limit: Int = DEFAULT_RECONCILIATION_LIMIT
    ): ContextEventReconciliationSummary = commandMutex.withLock {
        val boundedLimit = limit.coerceIn(1, MAX_RECONCILIATION_LIMIT)
        val pendingCommands = syncDao.orderedBatchByStatus(STATUS_PENDING, boundedLimit)
        reconcileSelected(pendingCommands)
    }

    suspend fun reconcileStartupPending(
        pageSize: Int = DEFAULT_STARTUP_PAGE_SIZE,
        hardCap: Int = MAX_STARTUP_RECONCILIATION_COMMANDS
    ): ContextEventReconciliationSummary = commandMutex.withLock {
        val boundedPageSize = pageSize.coerceIn(1, MAX_RECONCILIATION_LIMIT)
        val boundedHardCap = hardCap.coerceIn(1, MAX_STARTUP_RECONCILIATION_COMMANDS)
        val startupSnapshot = syncDao.orderedBatchByStatus(
            STATUS_PENDING,
            (boundedHardCap + 1).coerceAtMost(MAX_STARTUP_RECONCILIATION_COMMANDS + 1)
        )
        val selected = startupSnapshot.take(boundedHardCap)
        reconcileSelected(
            pendingCommands = selected,
            processingPageSize = boundedPageSize,
            startupSnapshotCount = startupSnapshot.size,
            safetyCapReached = startupSnapshot.size > boundedHardCap
        )
    }

    private suspend fun reconcileSelected(
        pendingCommands: List<ContextEventSyncEntity>,
        processingPageSize: Int = pendingCommands.size.coerceAtLeast(1),
        startupSnapshotCount: Int = 0,
        safetyCapReached: Boolean = false
    ): ContextEventReconciliationSummary {
        var acknowledged = 0
        var applied = 0
        var failed = 0
        var stillPending = 0
        var malformed = 0
        pendingCommands.chunked(processingPageSize.coerceAtLeast(1)).forEach { page ->
            page.forEach command@{ pending ->
                val operation = pending.operation.toOperationOrNull()
                val immutableEvent = operation?.let {
                    try {
                        eventFromAudit(pending, it)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (operation == null || immutableEvent == null) {
                    markMalformedPending(pending)
                    malformed++
                    failed++
                    return@command
                }
                val outcome = dispatchPendingDetailed(
                    event = immutableEvent,
                    operation = operation,
                    durablePending = pending,
                    notifyDurableMutation = false
                )
                when (outcome.result) {
                    ContextEventCommandResult.ACKNOWLEDGED -> {
                        acknowledged++
                        if (outcome.localMutationApplied) applied++
                    }
                    ContextEventCommandResult.FAILED -> failed++
                    ContextEventCommandResult.PENDING -> stillPending++
                    else -> failed++
                }
            }
        }
        return ContextEventReconciliationSummary(
            processedCount = pendingCommands.size,
            acknowledgedCount = acknowledged,
            appliedCount = applied,
            failedCount = failed,
            stillPendingCount = stillPending,
            malformedCount = malformed,
            remainingPendingCount = syncDao.countByStatus(STATUS_PENDING),
            startupSnapshotCount = startupSnapshotCount,
            safetyCapReached = safetyCapReached
        )
    }

    private suspend fun preparePending(
        event: CompensationEvent,
        operation: AapsContextEventGateway.Operation
    ): PendingPreparation {
        val operationName = operation.name
        val existing = syncDao.forOperation(event.localId, event.revision, operationName)
        val commandHash = requestHash(event, operationName)
        if (existing != null && !digestsEqual(requestDigest(existing.requestHash), commandHash)) {
            return PendingPreparation.Terminal(ContextEventCommandResult.FAILED)
        }
        when (existing?.status) {
            STATUS_COMPLETED -> return PendingPreparation.Terminal(ContextEventCommandResult.ACKNOWLEDGED)
            STATUS_FAILED -> return PendingPreparation.Terminal(ContextEventCommandResult.FAILED)
        }
        val attemptedAt = monotonicTimestamp(existing?.attemptedAt)
        val storedCommand = existing?.requestHash
            ?.takeIf { it.contains('.') }
            ?: commandRecord(event, operationName, commandHash)
            ?: return PendingPreparation.Terminal(ContextEventCommandResult.REJECTED)
        val pending = existing?.copy(
            requestHash = storedCommand,
            status = STATUS_PENDING,
            attemptedAt = attemptedAt,
            completedAt = null,
            sanitizedError = null
        ) ?: ContextEventSyncEntity(
            syncId = syncId(event.localId, event.revision, operationName),
            eventId = event.localId,
            revision = event.revision,
            operation = operationName,
            requestHash = storedCommand,
            status = STATUS_PENDING,
            attemptedAt = attemptedAt,
            completedAt = null,
            sanitizedError = null
        )
        if (existing == null) {
            if (syncDao.insert(pending) == -1L) {
                val concurrent = syncDao.forOperation(event.localId, event.revision, operationName)
                return when {
                    concurrent == null -> PendingPreparation.Terminal(ContextEventCommandResult.PENDING)
                    !digestsEqual(requestDigest(concurrent.requestHash), commandHash) ->
                        PendingPreparation.Terminal(ContextEventCommandResult.FAILED)
                    concurrent.status == STATUS_COMPLETED ->
                        PendingPreparation.Terminal(ContextEventCommandResult.ACKNOWLEDGED)
                    concurrent.status == STATUS_FAILED ->
                        PendingPreparation.Terminal(ContextEventCommandResult.FAILED)
                    else -> PendingPreparation.Ready(concurrent)
                }
            }
        } else {
            syncDao.update(pending)
        }
        return PendingPreparation.Ready(pending)
    }

    private suspend fun dispatchPrepared(
        event: CompensationEvent,
        operation: AapsContextEventGateway.Operation,
        preparation: PendingPreparation
    ): ContextEventCommandResult = when (preparation) {
        is PendingPreparation.Terminal -> preparation.result
        is PendingPreparation.Ready -> dispatchPending(event, operation, preparation.pending)
    }

    private suspend fun dispatchPending(
        event: CompensationEvent,
        operation: AapsContextEventGateway.Operation,
        durablePending: ContextEventSyncEntity
    ): ContextEventCommandResult = dispatchPendingDetailed(event, operation, durablePending).result

    private suspend fun dispatchPendingDetailed(
        event: CompensationEvent,
        operation: AapsContextEventGateway.Operation,
        durablePending: ContextEventSyncEntity,
        notifyDurableMutation: Boolean = true
    ): DispatchOutcome {
        val attemptedAt = monotonicTimestamp(durablePending.attemptedAt)
        val pending = durablePending.copy(
            status = STATUS_PENDING,
            attemptedAt = attemptedAt,
            completedAt = null,
            sanitizedError = null
        )
        if (syncDao.update(pending) != 1) {
            return DispatchOutcome(ContextEventCommandResult.PENDING, false)
        }
        return when (val result = gateway.send(event, operation)) {
            is ContextEventGatewayResult.Acknowledged -> {
                val completed = pending.copy(
                    status = STATUS_COMPLETED,
                    completedAt = monotonicTimestamp(attemptedAt),
                    sanitizedError = null
                )
                if (operation == AapsContextEventGateway.Operation.DELETE) {
                    val changed = transactionRunner.run {
                        syncDao.update(completed)
                        contextTagDao.deleteByIdBelowRevision(event.localId, event.revision) > 0
                    }
                    if (changed && notifyDurableMutation) notifyDurableMutationApplied()
                    return DispatchOutcome(ContextEventCommandResult.ACKNOWLEDGED, changed)
                } else if (operation == AapsContextEventGateway.Operation.CLOSE) {
                    val changed = transactionRunner.run {
                        syncDao.update(completed)
                        persistAcknowledgedClose(event)
                    }
                    if (changed && notifyDurableMutation) notifyDurableMutationApplied()
                    return DispatchOutcome(ContextEventCommandResult.ACKNOWLEDGED, changed)
                } else if (
                    operation == AapsContextEventGateway.Operation.CREATE ||
                    operation == AapsContextEventGateway.Operation.UPDATE
                ) {
                    val changed = transactionRunner.run {
                        syncDao.update(completed)
                        persistAcknowledgedSave(event)
                    }
                    if (changed && notifyDurableMutation) notifyDurableMutationApplied()
                    return DispatchOutcome(ContextEventCommandResult.ACKNOWLEDGED, changed)
                } else {
                    syncDao.update(completed)
                }
                DispatchOutcome(ContextEventCommandResult.ACKNOWLEDGED, false)
            }
            is ContextEventGatewayResult.Failed -> {
                syncDao.update(
                    pending.copy(
                        status = STATUS_FAILED,
                        completedAt = monotonicTimestamp(attemptedAt),
                        sanitizedError = sanitizeReason(result.reason)
                    )
                )
                DispatchOutcome(ContextEventCommandResult.FAILED, false)
            }
            is ContextEventGatewayResult.Pending -> {
                syncDao.update(
                    pending.copy(sanitizedError = sanitizeReason(result.reason))
                )
                DispatchOutcome(ContextEventCommandResult.PENDING, false)
            }
        }
    }

    private suspend fun markMalformedPending(pending: ContextEventSyncEntity) {
        transactionRunner.run {
            syncDao.update(
                pending.copy(
                    status = STATUS_FAILED,
                    completedAt = monotonicTimestamp(pending.attemptedAt),
                    sanitizedError = INVALID_PENDING_COMMAND
                )
            )
        }
    }

    private suspend fun notifyDurableMutationApplied() {
        try {
            onDurableMutationApplied()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The durable context mutation remains authoritative; the periodic cycle is the fallback.
        }
    }

    private fun commandRecord(event: CompensationEvent, operation: String, digest: String): String? {
        val payload = pendingPayload(event, operation)
        if (!validPendingPayload(payload)) return null
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(gson.toJson(payload).toByteArray(Charsets.UTF_8))
        return "$digest.$encoded".takeIf { it.length <= MAX_PENDING_COMMAND_CHARS }
    }

    private suspend fun persistAcknowledgedClose(event: CompensationEvent): Boolean {
        val current = contextTagDao.byId(event.localId) ?: return false
        if (current.revision >= event.revision) return false
        val attributesJson = encodeAttributes(event.attributes) ?: return false
        contextTagDao.upsert(
            event.toEntity(
                storedRevision = event.revision,
                storedUpdatedAt = monotonicTimestamp(current.updatedAt),
                storedAttributesJson = attributesJson
            )
        )
        return true
    }

    private suspend fun persistAcknowledgedSave(event: CompensationEvent): Boolean {
        val current = contextTagDao.byId(event.localId)
        if (current != null && current.revision >= event.revision) return false
        val attributesJson = requireNotNull(encodeAttributes(event.attributes)) {
            "Acknowledged context event attributes failed validation"
        }
        contextTagDao.upsert(
            event.toEntity(
                storedRevision = event.revision,
                storedUpdatedAt = monotonicTimestamp(current?.updatedAt),
                storedAttributesJson = attributesJson
            )
        )
        return true
    }

    private fun matchesExpectedRevision(
        existing: PhysioContextTagEntity?,
        expectedRevision: Long?
    ): Boolean = if (expectedRevision == null) {
        existing == null
    } else {
        expectedRevision > 0L && existing?.revision == expectedRevision
    }

    private fun eventFromAudit(
        audit: ContextEventSyncEntity,
        operation: AapsContextEventGateway.Operation,
        allowedStatuses: Set<String> = setOf(STATUS_PENDING)
    ): CompensationEvent? {
        if (audit.status !in allowedStatuses || audit.requestHash.length > MAX_PENDING_COMMAND_CHARS) return null
        if (audit.requestHash.count { it == '.' } != 1) return null
        val storedDigest = requestDigest(audit.requestHash)
        if (!isCanonicalSha256(storedDigest)) return null
        val encoded = audit.requestHash.substringAfter('.', missingDelimiterValue = "")
        if (encoded.isEmpty()) return null
        val decoded = runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrNull() ?: return null
        if (decoded.size > MAX_PENDING_PAYLOAD_BYTES) return null
        val payload = decodePendingPayload(decoded) ?: return null
        if (
            payload.version != PENDING_COMMAND_VERSION ||
            payload.operation != operation.name ||
            payload.localId != audit.eventId ||
            payload.revision != audit.revision ||
            !validPendingPayload(payload) ||
            !digestsEqual(storedDigest, pendingPayloadDigest(payload))
        ) return null
        val event = runCatching {
            CompensationEvent(
                localId = payload.localId,
                startTs = payload.startTs,
                endTs = payload.endTs,
                type = CompensationEventType.valueOf(payload.type),
                subtype = payload.subtype,
                severity = EventSeverity.valueOf(payload.severity),
                source = EventSource.valueOf(payload.source),
                title = payload.title,
                attributes = payload.attributes,
                note = payload.note,
                revision = payload.revision,
                status = CompensationEventStatus.valueOf(payload.status),
                provenance = payload.provenance
            )
        }.getOrNull() ?: return null
        if (!validOperationEvent(event, operation)) return null
        return event
    }

    private fun decodePendingPayload(decoded: ByteArray): PendingCommandPayload? {
        val root = runCatching {
            JsonParser.parseString(String(decoded, Charsets.UTF_8))
        }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (root.keySet() !in PENDING_PAYLOAD_FIELD_SETS) return null
        val wire = PendingCommandWirePayload(
            version = root.strictInt("version"),
            operation = root.strictString("operation"),
            localId = root.strictString("localId"),
            startTs = root.strictLong("startTs"),
            endTs = root.strictLong("endTs"),
            type = root.strictString("type"),
            subtype = root.strictString("subtype"),
            severity = root.strictString("severity"),
            source = root.strictString("source"),
            title = root.strictString("title"),
            attributes = root.strictStringMap("attributes"),
            note = root.strictNullableString("note"),
            noteValid = root.isValidNullableString("note"),
            revision = root.strictLong("revision"),
            status = root.strictString("status"),
            provenance = root.strictString("provenance")
        )
        return wire.toValidatedPayloadOrNull()
    }

    private fun PendingCommandWirePayload.toValidatedPayloadOrNull(): PendingCommandPayload? {
        if (!noteValid) return null
        return PendingCommandPayload(
            version = version ?: return null,
            operation = operation ?: return null,
            localId = localId ?: return null,
            startTs = startTs ?: return null,
            endTs = endTs ?: return null,
            type = type ?: return null,
            subtype = subtype ?: return null,
            severity = severity ?: return null,
            source = source ?: return null,
            title = title ?: return null,
            attributes = attributes ?: return null,
            note = note,
            revision = revision ?: return null,
            status = status ?: return null,
            provenance = provenance ?: return null
        ).takeIf(::validPendingPayload)
    }

    private fun JsonObject.strictString(name: String): String? = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString

    private fun JsonObject.strictNullableString(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }

    private fun JsonObject.isValidNullableString(name: String): Boolean {
        if (!has(name)) return true
        val value = get(name)
        return value.isJsonNull || (value.isJsonPrimitive && value.asJsonPrimitive.isString)
    }

    private fun JsonObject.strictLong(name: String): Long? {
        val value = get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        if (!value.isNumber) return null
        val raw = value.toString()
        if (!INTEGER_JSON_PATTERN.matches(raw)) return null
        return raw.toLongOrNull()
    }

    private fun JsonObject.strictInt(name: String): Int? = strictLong(name)
        ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
        ?.toInt()

    private fun JsonObject.strictStringMap(name: String): Map<String, String>? {
        val objectValue = get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val result = linkedMapOf<String, String>()
        objectValue.entrySet().sortedBy { it.key }.forEach { (key, value) ->
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
            result[key] = value.asString
        }
        return result
    }

    private fun requestDigest(record: String): String = record.substringBefore('.')

    private fun PhysioContextTagEntity.closeAt(closeTs: Long, nextRevision: Long): PhysioContextTagEntity {
        val boundedEnd = closeTs.coerceIn(tsStart, tsEnd)
        return copy(
            tsEnd = boundedEnd,
            revision = nextRevision,
            updatedAt = monotonicTimestamp(updatedAt),
            status = CompensationEventStatus.CLOSED.name
        )
    }

    private fun CompensationEvent.toEntity(
        storedRevision: Long,
        storedUpdatedAt: Long,
        storedAttributesJson: String
    ) = PhysioContextTagEntity(
        id = localId,
        tsStart = startTs,
        tsEnd = endTs,
        tagType = storedPhysioTagType(),
        severity = storedPhysioSeverity(),
        source = source.name,
        note = note.orEmpty(),
        subtype = subtype,
        title = title,
        attributesJson = storedAttributesJson,
        revision = storedRevision,
        updatedAt = storedUpdatedAt,
        status = status.name
    )

    private fun PhysioContextTagEntity.toEvent(): CompensationEvent = CompensationEvent(
        localId = id,
        startTs = tsStart,
        endTs = tsEnd,
        type = compensationTypeForTag(tagType),
        subtype = subtype,
        severity = severity.toEventSeverity(),
        source = runCatching { EventSource.valueOf(source.uppercase()) }.getOrDefault(EventSource.USER),
        title = title,
        attributes = decodeAttributes(attributesJson),
        note = note.takeIf(String::isNotEmpty),
        revision = revision,
        status = runCatching { CompensationEventStatus.valueOf(status.uppercase()) }
            .getOrDefault(CompensationEventStatus.ACTIVE),
        provenance = "physio_context_tags"
    )

    private fun PhysioContextTagEntity.matches(
        event: CompensationEvent,
        encodedAttributes: String
    ): Boolean = this == event.toEntity(
        storedRevision = revision,
        storedUpdatedAt = updatedAt,
        storedAttributesJson = encodedAttributes
    )

    private fun encodeAttributes(attributes: Map<String, String>): String? {
        val encoded = gson.toJson(attributes.toSortedMap())
        return encoded.takeIf { it.length <= MAX_ATTRIBUTES_JSON_CHARS }
    }

    private fun decodeAttributes(attributesJson: String): Map<String, String> {
        if (attributesJson.length > MAX_ATTRIBUTES_JSON_CHARS) return emptyMap()
        val mapType = object : TypeToken<Map<String, String>>() {}.type
        return runCatching { gson.fromJson<Map<String, String>>(attributesJson, mapType).orEmpty() }
            .getOrDefault(emptyMap())
            .takeIf(::validAttributes)
            .orEmpty()
    }

    private fun validAttributes(attributes: Map<String, String>): Boolean =
        attributes.size <= 24 &&
            attributes.keys.all { it.isNotBlank() && it.length <= 48 } &&
            attributes.values.all { it.length <= 240 }

    private fun CompensationEvent.storedPhysioTagType(): String = attributes[PHYSIO_TAG_TYPE_ATTRIBUTE]
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.length <= 48 && it.all { char -> char.isLetterOrDigit() || char == '_' } }
        ?: type.name

    private fun CompensationEvent.storedPhysioSeverity(): Double = attributes[PHYSIO_SEVERITY_ATTRIBUTE]
        ?.toDoubleOrNull()
        ?.takeIf(Double::isFinite)
        ?.coerceIn(0.1, 1.0)
        ?: severity.toStoredSeverity()

    private fun requestHash(event: CompensationEvent, operation: String): String =
        pendingPayloadDigest(pendingPayload(event, operation))

    private fun pendingPayload(event: CompensationEvent, operation: String) = PendingCommandPayload(
        version = PENDING_COMMAND_VERSION,
        operation = operation,
        localId = event.localId,
        startTs = event.startTs,
        endTs = event.endTs,
        type = event.type.name,
        subtype = event.subtype,
        severity = event.severity.name,
        source = event.source.name,
        title = event.title,
        attributes = event.attributes.toSortedMap(),
        note = event.note,
        revision = event.revision,
        status = event.status.name,
        provenance = event.provenance
    )

    private fun pendingPayloadDigest(payload: PendingCommandPayload): String =
        sha256(canonicalPendingPayload(payload))

    private fun canonicalPendingPayload(payload: PendingCommandPayload): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(payload.version)
                output.writeUtf8(payload.operation)
                output.writeUtf8(payload.localId)
                output.writeLong(payload.startTs)
                output.writeLong(payload.endTs)
                output.writeUtf8(payload.type)
                output.writeUtf8(payload.subtype)
                output.writeUtf8(payload.severity)
                output.writeUtf8(payload.source)
                output.writeUtf8(payload.title)
                val sortedAttributes = payload.attributes.toSortedMap()
                output.writeInt(sortedAttributes.size)
                sortedAttributes.forEach { (key, value) ->
                    output.writeUtf8(key)
                    output.writeUtf8(value)
                }
                output.writeNullableUtf8(payload.note)
                output.writeLong(payload.revision)
                output.writeUtf8(payload.status)
                output.writeUtf8(payload.provenance)
            }
            bytes.toByteArray()
        }

    private fun DataOutputStream.writeUtf8(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataOutputStream.writeNullableUtf8(value: String?) {
        if (value == null) {
            writeByte(0)
        } else {
            writeByte(1)
            writeUtf8(value)
        }
    }

    private fun validPendingPayload(payload: PendingCommandPayload): Boolean {
        val operation = payload.operation.toOperationOrNull() ?: return false
        val type = runCatching { CompensationEventType.valueOf(payload.type) }.getOrNull() ?: return false
        runCatching { EventSeverity.valueOf(payload.severity) }.getOrNull() ?: return false
        val source = runCatching { EventSource.valueOf(payload.source) }.getOrNull() ?: return false
        val status = runCatching { CompensationEventStatus.valueOf(payload.status) }.getOrNull() ?: return false
        if (
            payload.version != PENDING_COMMAND_VERSION ||
            payload.revision <= 0L ||
            source != EventSource.USER ||
            !CompensationEventManualPolicy.isContextOnly(type) ||
            !CompensationEventManualPolicy.validLocalEventId(payload.localId) ||
            !CompensationEventManualPolicy.validEnvelope(payload.startTs, payload.endTs) ||
            payload.subtype.length > MAX_SUBTYPE_CHARS ||
            payload.title.length > MAX_TITLE_CHARS ||
            (payload.note?.length ?: 0) > MAX_NOTE_CHARS ||
            payload.provenance.length > MAX_PROVENANCE_CHARS ||
            !validAttributes(payload.attributes)
        ) return false
        return operation != AapsContextEventGateway.Operation.CLOSE ||
            status == CompensationEventStatus.CLOSED
    }

    private fun validOperationEvent(
        event: CompensationEvent,
        operation: AapsContextEventGateway.Operation
    ): Boolean = event.source == EventSource.USER &&
        CompensationEventManualPolicy.isContextOnly(event.type) &&
        CompensationEventManualPolicy.validLocalEventId(event.localId) &&
        CompensationEventManualPolicy.validEnvelope(event.startTs, event.endTs) &&
        (operation != AapsContextEventGateway.Operation.CLOSE ||
            event.status == CompensationEventStatus.CLOSED)

    private fun syncId(eventId: String, revision: Long, operation: String): String =
        sha256("$eventId|$revision|$operation")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun isCanonicalSha256(value: String): Boolean =
        value.length == SHA256_HEX_CHARS && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun digestsEqual(left: String, right: String): Boolean =
        isCanonicalSha256(left) && isCanonicalSha256(right) && MessageDigest.isEqual(
            left.toByteArray(Charsets.US_ASCII),
            right.toByteArray(Charsets.US_ASCII)
        )

    private fun monotonicTimestamp(previous: Long?): Long {
        val wallClock = now().coerceAtLeast(1L)
        if (previous == null) return wallClock
        if (previous == Long.MAX_VALUE) return Long.MAX_VALUE
        return maxOf(wallClock, previous + 1L)
    }

    private fun nextRevision(previous: Long?): Long? = when {
        previous == null -> 1L
        previous == Long.MAX_VALUE -> null
        else -> previous + 1L
    }

    private fun sanitizeReason(reason: String): String = reason
        .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        .take(MAX_SANITIZED_ERROR_CHARS)
        .ifBlank { "delivery_failed" }

    private fun EventSeverity.toStoredSeverity(): Double = when (this) {
        EventSeverity.LOW -> 0.25
        EventSeverity.MEDIUM -> 0.6
        EventSeverity.HIGH -> 0.9
    }

    private fun Double.toEventSeverity(): EventSeverity = when {
        this >= 0.8 -> EventSeverity.HIGH
        this >= 0.4 -> EventSeverity.MEDIUM
        else -> EventSeverity.LOW
    }

    private fun String.toOperationOrNull(): AapsContextEventGateway.Operation? =
        runCatching { AapsContextEventGateway.Operation.valueOf(this) }.getOrNull()

    internal companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        private const val PENDING_COMMAND_VERSION = 2
        private const val PHYSIO_TAG_TYPE_ATTRIBUTE = "physioTagType"
        private const val PHYSIO_SEVERITY_ATTRIBUTE = "physioSeverity"
        private const val MAX_ATTRIBUTES_JSON_CHARS = 8_192
        private const val MAX_SANITIZED_ERROR_CHARS = 80
        private const val MAX_PENDING_COMMAND_CHARS = 16_384
        private const val MAX_PENDING_PAYLOAD_BYTES = 12_288
        private const val MAX_SUBTYPE_CHARS = 240
        private const val MAX_TITLE_CHARS = 60
        private const val MAX_NOTE_CHARS = 500
        private const val MAX_PROVENANCE_CHARS = 240
        private const val SHA256_HEX_CHARS = 64
        private const val DEFAULT_RECONCILIATION_LIMIT = 16
        private const val MAX_RECONCILIATION_LIMIT = 64
        private const val DEFAULT_STARTUP_PAGE_SIZE = 16
        private const val MAX_STARTUP_RECONCILIATION_COMMANDS = 256
        private const val INVALID_PENDING_COMMAND = "invalid_pending_command"
        private val INTEGER_JSON_PATTERN = Regex("-?(0|[1-9][0-9]*)")
        private val PENDING_PAYLOAD_FIELDS = setOf(
            "version",
            "operation",
            "localId",
            "startTs",
            "endTs",
            "type",
            "subtype",
            "severity",
            "source",
            "title",
            "attributes",
            "note",
            "revision",
            "status",
            "provenance"
        )
        private val PENDING_PAYLOAD_FIELD_SETS = setOf(
            PENDING_PAYLOAD_FIELDS,
            PENDING_PAYLOAD_FIELDS - "note"
        )
        private val SAVE_OPERATIONS = setOf(
            AapsContextEventGateway.Operation.CREATE.name,
            AapsContextEventGateway.Operation.UPDATE.name
        )
        internal fun isExportEligible(event: CompensationEvent): Boolean =
            event.source == EventSource.USER && CompensationEventManualPolicy.isContextOnly(event.type)

        private fun isExportEligible(entity: PhysioContextTagEntity): Boolean =
            entity.source.equals(EventSource.USER.name, ignoreCase = true) &&
                CompensationEventManualPolicy.isContextOnly(compensationTypeForTag(entity.tagType))

        private fun compensationTypeForTag(tagType: String): CompensationEventType = when (
            tagType.trim().lowercase()
        ) {
            "hormonal_phase" -> CompensationEventType.HORMONAL
            "steroid", "steroids" -> CompensationEventType.MEDICATION_STEROID
            else -> runCatching { CompensationEventType.valueOf(tagType.uppercase()) }
                .getOrDefault(CompensationEventType.CUSTOM)
        }
    }
}

private sealed interface PendingPreparation {
    data class Ready(val pending: ContextEventSyncEntity) : PendingPreparation
    data class Terminal(val result: ContextEventCommandResult) : PendingPreparation
}

private data class DispatchOutcome(
    val result: ContextEventCommandResult,
    val localMutationApplied: Boolean
)

internal data class ContextEventReconciliationSummary(
    val processedCount: Int,
    val acknowledgedCount: Int,
    val appliedCount: Int,
    val failedCount: Int,
    val stillPendingCount: Int,
    val malformedCount: Int,
    val remainingPendingCount: Int,
    val startupSnapshotCount: Int = 0,
    val safetyCapReached: Boolean = false
)

private data class PendingCommandPayload(
    val version: Int,
    val operation: String,
    val localId: String,
    val startTs: Long,
    val endTs: Long,
    val type: String,
    val subtype: String,
    val severity: String,
    val source: String,
    val title: String,
    val attributes: Map<String, String>,
    val note: String?,
    val revision: Long,
    val status: String,
    val provenance: String
)

private data class PendingCommandWirePayload(
    val version: Int?,
    val operation: String?,
    val localId: String?,
    val startTs: Long?,
    val endTs: Long?,
    val type: String?,
    val subtype: String?,
    val severity: String?,
    val source: String?,
    val title: String?,
    val attributes: Map<String, String>?,
    val note: String?,
    val noteValid: Boolean,
    val revision: Long?,
    val status: String?,
    val provenance: String?
)

internal enum class ContextEventCommandResult {
    ACKNOWLEDGED,
    FAILED,
    PENDING,
    LOCAL_ONLY,
    REJECTED,
    NOT_FOUND,
    CONFLICT
}
