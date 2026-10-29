package io.aaps.copilot.service

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

internal class ComboRetainedCatalog(
    val inventory: AapsV3ComboInventory, val audit: ComboInventoryRetentionAudit,
    val clientId: String, val masterId: String, val rosterRevision: Long, val sessionStartedAt: Long
) {
    override fun toString() = "ComboRetainedCatalog(redacted)"
}

/** One authenticated catalog, not current-source authority or a permission to delete history. */
internal class AapsV3ComboCatalogRetention(private val store: AapsV3ComboJournalStore) : AutoCloseable {
    private val busy = Mutex()
    private val closed = AtomicBoolean(false)
    private val assembly = AapsV3ComboInventoryAssembly()
    private var rosterRevision = 0L
    private var sessionStartedAt = 0L
    private var previous: AapsV3ComboInventoryBinding? = null
    private var observedAt = 0L

    suspend fun accept(handoff: ComboResponseHandoff, clock: () -> Long): ComboInventoryRetentionAudit? =
        acceptSnapshot(handoff, clock)?.audit

    suspend fun acceptSnapshot(handoff: ComboResponseHandoff, clock: () -> Long): ComboRetainedCatalog? {
        if (!busy.tryLock()) {
            handoff.close()
            currentCoroutineContext().ensureActive()
            throw AapsV3ComboInventoryFailure()
        }
        try {
            currentCoroutineContext().ensureActive()
            check(!closed.get())
            val content = handoff.content as? ComboResponseContent.Inventory ?: error("inventory_required")
            val binding = content.binding
            val now = clock()
            binding.requireCurrent(now)
            check(handoff.rosterRevision > 0 && handoff.sessionStartedAt in 1..binding.issuedAt &&
                handoff.admittedAt in binding.issuedAt..now && now >= observedAt)
            val old = previous
            if (old == null) {
                rosterRevision = handoff.rosterRevision
                sessionStartedAt = handoff.sessionStartedAt
            } else {
                check(handoff.rosterRevision == rosterRevision && handoff.sessionStartedAt == sessionStartedAt &&
                    binding.issuedAt >= old.issuedAt && handoff.admittedAt >= observedAt && binding.counter > old.counter)
            }
            assembly.accept(binding, content.page(), now)
            currentCoroutineContext().ensureActive()
            previous = binding
            observedAt = now
            check(!closed.get())
            val inventory = assembly.completed() ?: return null
            // This read-only transaction must never run inside the paired journal's transaction.
            val audit = store.reconcileInventory(binding.masterInstallId, inventory)
            currentCoroutineContext().ensureActive()
            check(clock() in now until binding.validUntil)
            check(closed.compareAndSet(false, true))
            assembly.close()
            return ComboRetainedCatalog(inventory, audit, binding.clientId, binding.masterInstallId,
                rosterRevision, sessionStartedAt)
        } catch (cancelled: CancellationException) {
            close(); throw cancelled
        } catch (failure: ComboRetentionFailure) {
            close(); throw failure
        } catch (_: Exception) {
            close(); throw AapsV3ComboInventoryFailure()
        } finally {
            handoff.close()
            busy.unlock()
        }
    }

    override fun close() { closed.set(true); assembly.close() }
    override fun toString() = "ComboCatalogRetention(redacted)"
}
