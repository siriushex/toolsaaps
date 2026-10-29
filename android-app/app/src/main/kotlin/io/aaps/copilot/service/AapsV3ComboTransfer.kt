package io.aaps.copilot.service

import io.aaps.copilot.data.local.CopilotDatabase
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

enum class ComboTransferPhase { IDLE, READING, RETAINED, PARTIAL, UNAVAILABLE }
data class ComboTransferState(
    val phase: ComboTransferPhase = ComboTransferPhase.IDLE,
    val retainedReads: Int = 0, val missingReads: Int = 0, val conflictingReads: Int = 0,
    val hazardKinds: Int = 0,
    val lastAudit: ComboTransferAudit? = null
)
internal class ComboTransferFailure : IllegalStateException("combo_transfer_unavailable")

/** One bounded cycle. A valid encrypted inventory negotiates read support, not clinical authority. */
internal class AapsV3ComboTransfer(
    private val db: CopilotDatabase,
    private val journal: AapsV3TargetJournal,
    private val store: AapsV3ComboJournalStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val busy = Mutex()

    suspend fun run(session: Long, probeCounter: Long, admit: suspend () -> Unit): ComboTransferState {
        if (!busy.tryLock()) throw ComboTransferFailure()
        try {
            return withTimeoutOrNull(CYCLE_MS) {
                val startedAt = clock()
                var observedAt = startedAt
                var requests = 0
                var responseValidUntil = 0L
                suspend fun runtimeGuard() {
                    currentCoroutineContext().ensureActive()
                    admit()
                    currentCoroutineContext().ensureActive()
                    val now = clock()
                    check(now >= observedAt && now - startedAt in 0 until CYCLE_MS)
                    observedAt = now
                }
                suspend fun guard() {
                    runtimeGuard()
                    journal.requireLivePairing(clock(), session, probeCounter)
                    check(journal.pending() == null && !db.actionCommandDao().hasPendingTempTarget())
                    runtimeGuard()
                }
                suspend fun exchange(spec: AapsV3ComboReadSpec): ComboResponseHandoff {
                    guard()
                    check(requests++ < MAX_REQUESTS)
                    val id = UUID.randomUUID().toString()
                    val request = checkNotNull(journal.requestComboRead(id, spec, session, probeCounter,
                        clock, ::runtimeGuard))
                    check(request.phase == AapsV3ComboReadPhase.ISSUED && request.publication != null)
                    // Only observe the committed document. Never republish after an uncertain result.
                    val response = withTimeoutOrNull(AapsV3ComboReadProtocol.TTL_MS) {
                        db.localNightscoutV3Dao().observeRevision().map {
                            guard()
                            checkNotNull(journal.receiveComboResponse(session, probeCounter, clock))
                        }.first { it.phase != AapsV3ComboReadPhase.ISSUED }
                    }
                    check(response?.requestId == id && response.phase == AapsV3ComboReadPhase.RECEIVED)
                    guard()
                    val handoff = checkNotNull(journal.takeComboResponse(session, probeCounter, clock))
                    try {
                        guard()
                        val identity = when (val content = handoff.content) {
                            is ComboResponseContent.Inventory -> Triple(content.binding.requestId,
                                content.binding.counter, content.binding.validUntil)
                            is ComboResponseContent.Chunk -> Triple(content.binding.requestId,
                                content.binding.counter, content.binding.validUntil)
                            else -> error("response_required")
                        }
                        check(identity.first == id && identity.second == request.counter && handoff.sessionStartedAt == session)
                        responseValidUntil = identity.third
                        return handoff
                    } catch (error: Throwable) {
                        handoff.close()
                        throw error
                    }
                }

                val catalog = AapsV3ComboCatalogRetention(store).use { receiver ->
                    var query = AapsV3ComboReadSpec.Inventory()
                    var complete: ComboRetainedCatalog? = null
                    while (complete == null) {
                        val handoff = exchange(query)
                        handoff.use {
                            val page = (it.content as ComboResponseContent.Inventory).page()
                            val next = if (page.hasMore) AapsV3ComboReadSpec.Inventory(
                                page.snapshotId, page.reads.last().readId) else null
                            complete = receiver.acceptSnapshot(it, clock)
                            guard()
                            if (complete == null) query = checkNotNull(next)
                        }
                    }
                    checkNotNull(complete)
                }
                var bytes = 0
                val missing = catalog.audit.missingReadIds.toSet()
                // Conflicts remain evidence. Never overwrite a retained read or repair source history.
                if (catalog.audit.conflictingReadIds.isEmpty()) {
                    for (read in catalog.inventory.reads.filter { it.readId in missing }) {
                        val chunks = (read.byteCount - 1) / CHUNK_BYTES + 1
                        if (read.byteCount > MAX_BYTES - bytes || chunks > MAX_REQUESTS - requests) break
                        AapsV3ComboReadRetention(store).use { receiver ->
                            for (offset in 0 until read.byteCount step CHUNK_BYTES) {
                                val handoff = exchange(AapsV3ComboReadSpec.Chunk(catalog.inventory.snapshotId,
                                    read.readId, catalog.inventory.pumpBinding, read.sha256, read.byteCount, offset))
                                handoff.use {
                                    val binding = (it.content as ComboResponseContent.Chunk).binding
                                    check(it.rosterRevision == catalog.rosterRevision &&
                                        it.sessionStartedAt == catalog.sessionStartedAt &&
                                        binding.clientId == catalog.clientId && binding.masterInstallId == catalog.masterId)
                                    val receipt = receiver.accept(it, clock())
                                    binding.requireCurrent(clock())
                                    guard()
                                    if (offset + CHUNK_BYTES >= read.byteCount) check(receipt != null)
                                    else check(receipt == null)
                                }
                            }
                        }
                        bytes += read.byteCount
                    }
                }
                guard()
                val audit = store.reconcileInventory(catalog.masterId, catalog.inventory)
                guard()
                val completedAt = clock()
                check(completedAt in observedAt until responseValidUntil)
                ComboTransferState(if (audit.declaredInventoryRetained) ComboTransferPhase.RETAINED else ComboTransferPhase.PARTIAL,
                    audit.matchedReadCount, audit.missingReadIds.size, audit.conflictingReadIds.size, audit.hazards.size,
                    ComboTransferAudit.from(audit, completedAt))
            } ?: throw ComboTransferFailure()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw ComboTransferFailure()
        } finally { busy.unlock() }
    }

    override fun toString() = "ComboTransfer(redacted)"
    companion object {
        const val CYCLE_MS = 270_000L
        const val INTERVAL_MS = 15 * 60_000L
        private const val MAX_REQUESTS = 192
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val CHUNK_BYTES = 32768
    }
}
