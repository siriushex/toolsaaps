package io.aaps.copilot.service

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/** Authentication is supplied by the paired journal, never by constructing this value. */
internal sealed class ComboResponseContent(val phase: AapsV3ComboReadPhase) : AutoCloseable {
    class Inventory(val binding: AapsV3ComboInventoryBinding, private var page: AapsV3ComboInventoryPage?) :
        ComboResponseContent(AapsV3ComboReadPhase.RECEIVED) {
        @Synchronized fun page(): AapsV3ComboInventoryPage = checkNotNull(page)
        @Synchronized override fun close() { page = null }
    }
    class Chunk(val binding: AapsV3ComboChunkBinding, private var bytes: ByteArray?) :
        ComboResponseContent(AapsV3ComboReadPhase.RECEIVED) {
        @Synchronized fun copyBytes(): ByteArray = checkNotNull(bytes).copyOf()
        @Synchronized override fun close() { bytes?.fill(0); bytes = null }
    }
    class Refused(phase: AapsV3ComboReadPhase) : ComboResponseContent(phase) {
        override fun close() = Unit
    }
    final override fun toString() = "ComboResponseContent(redacted)"
}

/** A one-time local handoff, not a durable receipt, live permission or source acknowledgement. */
internal class ComboResponseHandoff(
    val rosterRevision: Long, val content: ComboResponseContent
) : AutoCloseable {
    override fun close() = content.close()
    override fun toString() = "ComboResponseHandoff(redacted)"
}

/** One bounded read. No main-journal transaction is held while private storage performs I/O. */
internal class AapsV3ComboReadRetention(private val store: AapsV3ComboJournalStore) : AutoCloseable {
    private val busy = Mutex()
    private val closed = AtomicBoolean(false)
    private var first: AapsV3ComboChunkBinding? = null
    private var rosterRevision = 0L
    private var lastCounter = 0L
    @Volatile private var assembly: AapsV3ComboJournalAssembly? = null

    suspend fun accept(handoff: ComboResponseHandoff, now: Long): ComboRetentionReceipt? {
        if (!busy.tryLock()) {
            handoff.close()
            throw AapsV3ComboChunkFailure()
        }
        try {
            check(!closed.get())
            val chunk = handoff.content as? ComboResponseContent.Chunk ?: error("chunk_required")
            val binding = chunk.binding
            binding.requireCurrent(now)
            check(handoff.rosterRevision > 0 && binding.counter > lastCounter)
            val start = first
            if (start == null) {
                check(binding.offset == 0)
                first = binding
                rosterRevision = handoff.rosterRevision
                assembly = AapsV3ComboJournalAssembly(binding.readId, binding.totalBytes, binding.sha256, binding.pumpBinding)
            } else {
                check(binding.clientId == start.clientId && binding.masterInstallId == start.masterInstallId &&
                    binding.snapshotId == start.snapshotId && binding.readId == start.readId &&
                    binding.pumpBinding == start.pumpBinding && binding.sha256 == start.sha256 &&
                    binding.totalBytes == start.totalBytes && handoff.rosterRevision == rosterRevision &&
                    binding.issuedAt >= start.issuedAt)
            }
            val bytes = chunk.copyBytes()
            val read = try {
                check(bytes.size == binding.chunkBytes)
                checkNotNull(assembly).append(binding.readId, binding.offset, binding.totalBytes, binding.sha256,
                    bytes, binding.offset + bytes.size == binding.totalBytes)
            } finally { bytes.fill(0) }
            lastCounter = binding.counter
            check(!closed.get())
            if (read == null) return null
            val raw = read.rawBytes()
            val receipt = try { store.retain(binding.masterInstallId, binding.pumpBinding, binding.readId, binding.sha256, raw) }
            finally { raw.fill(0) }
            check(closed.compareAndSet(false, true))
            assembly?.close()
            return receipt
        } catch (cancelled: CancellationException) {
            close(); throw cancelled
        } catch (failure: ComboRetentionFailure) {
            close(); throw failure
        } catch (_: Exception) {
            close(); throw AapsV3ComboChunkFailure()
        } finally {
            handoff.close()
            busy.unlock()
        }
    }

    override fun close() { closed.set(true); assembly?.close() }
    override fun toString() = "ComboReadRetention(redacted)"
}
