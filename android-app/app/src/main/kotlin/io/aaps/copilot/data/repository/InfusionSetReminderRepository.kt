package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.pump.InfusionSetCondition
import io.aaps.copilot.domain.pump.InfusionSetEvidence
import io.aaps.copilot.domain.pump.InfusionSetPolicy
import io.aaps.copilot.domain.pump.InfusionSetTiming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class InfusionNotice { NONE, CLAIMED, POSTED, BLOCKED, SUPPRESSED, PREVIOUSLY_ATTEMPTED }
enum class InfusionPostResult { POSTED, BLOCKED, UNKNOWN, STALE }
data class InfusionReminderRecord(
    val generation: String = "",
    val occurrenceTs: Long = 0,
    val dueTs: Long = 0,
    val notice: InfusionNotice = InfusionNotice.NONE,
    val attempts: Int = 0,
    val attemptedOccurrenceWatermark: Long = 0,
    val capabilityUnavailable: Boolean = false
) {
    fun validate() {
        require(attemptedOccurrenceWatermark >= 0 && attempts in 0..2)
        if (generation.isEmpty()) require(occurrenceTs == 0L && dueTs == 0L && notice == InfusionNotice.NONE && attempts == 0)
        else {
            require(occurrenceTs > 0 && generation == "infusion-$occurrenceTs")
            require(dueTs == Math.addExact(occurrenceTs, InfusionSetPolicy.INTERVAL_MS))
            when (notice) {
                InfusionNotice.NONE -> require(attempts == 0)
                InfusionNotice.PREVIOUSLY_ATTEMPTED -> require(attempts == 0 && attemptedOccurrenceWatermark >= occurrenceTs)
                else -> require(attempts in 1..2 && attemptedOccurrenceWatermark >= occurrenceTs)
            }
        }
        require(!capabilityUnavailable || notice == InfusionNotice.BLOCKED)
    }
}
interface InfusionReminderStore {
    suspend fun load(): InfusionReminderRecord
    suspend fun save(record: InfusionReminderRecord)
}
data class InfusionSetUiStatus(
    val timing: InfusionSetTiming = InfusionSetTiming(),
    val notice: InfusionNotice = InfusionNotice.NONE,
    val unavailable: Boolean = false,
    val muted: Boolean = false
)

/** Informational maintenance only. Mute coordination always precedes the reminder mutex. */
class InfusionSetReminderRepository(
    private val store: InfusionReminderStore,
    private val readEvidence: suspend () -> InfusionSetEvidence?,
    private val clock: () -> Long,
    private val enabled: suspend () -> Boolean,
    private val capable: () -> Boolean,
    private val postIfCurrent: suspend (String, Long) -> InfusionPostResult,
    private val clearNotification: () -> Unit,
    private val schedule: suspend (String, Long, Boolean) -> Unit,
    private val cancelWork: suspend () -> Unit,
    private val coordinateMute: suspend (suspend (Boolean) -> Unit) -> Unit
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(InfusionSetUiStatus())
    val state = mutableState.asStateFlow()

    suspend fun refresh() = guarded { muted -> reconcile(clock(), muted, true) }

    suspend fun runDue(expectedGeneration: String) = guarded { muted ->
        val now = clock()
        var record = reconcile(now, muted, false)
        if (record.generation.isEmpty() || record.generation != expectedGeneration) return@guarded
        if (now < record.dueTs) {
            schedule(record.generation, record.dueTs, true)
            return@guarded
        }
        if (record.notice !in setOf(InfusionNotice.NONE, InfusionNotice.BLOCKED)) return@guarded
        if (record.notice == InfusionNotice.NONE && record.occurrenceTs <= record.attemptedOccurrenceWatermark) {
            record = record.copy(notice = InfusionNotice.PREVIOUSLY_ATTEMPTED)
            store.save(record); publish(record, now, muted)
            return@guarded
        }
        if (muted || !enabled()) {
            record = record.copy(notice = InfusionNotice.SUPPRESSED, attempts = maxOf(1, record.attempts),
                attemptedOccurrenceWatermark = maxOf(record.attemptedOccurrenceWatermark, record.occurrenceTs),
                capabilityUnavailable = false)
            store.save(record); clearNotification(); publish(record, now, muted)
            return@guarded
        }
        val capability = capable()
        if (record.notice == InfusionNotice.BLOCKED &&
            (record.attempts >= 2 || !record.capabilityUnavailable || !capability)) return@guarded
        record = record.copy(notice = if (capability) InfusionNotice.CLAIMED else InfusionNotice.BLOCKED,
            attempts = record.attempts + 1,
            attemptedOccurrenceWatermark = maxOf(record.attemptedOccurrenceWatermark, record.occurrenceTs),
            capabilityUnavailable = !capability)
        store.save(record); publish(record, now, muted)
        if (!capability) return@guarded
        val result = try { postIfCurrent(record.generation, record.dueTs) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { InfusionPostResult.UNKNOWN }
        record = when (result) {
            InfusionPostResult.POSTED -> record.copy(notice = InfusionNotice.POSTED)
            InfusionPostResult.BLOCKED -> record.copy(notice = InfusionNotice.BLOCKED, capabilityUnavailable = !capable())
            InfusionPostResult.UNKNOWN, InfusionPostResult.STALE -> record
        }
        store.save(record)
        if (result == InfusionPostResult.STALE) clearNotification()
        publish(record, clock(), muted)
    }

    private suspend fun reconcile(now: Long, muted: Boolean, restoreWork: Boolean): InfusionReminderRecord {
        var record = store.load().also { it.validate() }
        val timing = InfusionSetPolicy.evaluate(now, readEvidence())
        val generation = timing.evidence?.generation.orEmpty()
        val changed = record.generation != generation
        if (changed) {
            record = InfusionReminderRecord(generation, timing.evidence?.occurrenceTs ?: 0,
                timing.dueTs ?: 0, attemptedOccurrenceWatermark = record.attemptedOccurrenceWatermark)
            store.save(record)
            clearNotification()
        }
        if (muted || !enabled()) clearNotification()
        if (generation.isEmpty()) {
            cancelWork()
            publish(record, now, muted)
            return record
        }
        if (record.notice == InfusionNotice.NONE && record.occurrenceTs <= record.attemptedOccurrenceWatermark) {
            record = record.copy(notice = InfusionNotice.PREVIOUSLY_ATTEMPTED)
            store.save(record)
        }
        if (record.notice == InfusionNotice.BLOCKED && !capable() && !record.capabilityUnavailable) {
            record = record.copy(capabilityUnavailable = true)
            store.save(record)
        }
        if (changed || restoreWork) {
            val eligible = record.notice == InfusionNotice.NONE || (record.notice == InfusionNotice.BLOCKED &&
                record.attempts < 2 && record.capabilityUnavailable && capable())
            // A finishing blocked worker must not swallow the one restored-capability retry via KEEP.
            if (eligible) schedule(generation, record.dueTs, changed || record.notice == InfusionNotice.BLOCKED) else cancelWork()
        }
        mutableState.value = InfusionSetUiStatus(timing, record.notice, muted = muted)
        return record
    }

    private fun publish(record: InfusionReminderRecord, now: Long, muted: Boolean) {
        val evidence = mutableState.value.timing.evidence?.takeIf { it.generation == record.generation }
        mutableState.value = InfusionSetUiStatus(InfusionSetPolicy.evaluate(now, evidence), record.notice, muted = muted)
    }

    private suspend fun guarded(action: suspend (Boolean) -> Unit) {
        try { coordinateMute { muted -> mutex.withLock { action(muted) } } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            mutableState.value = InfusionSetUiStatus(unavailable = true)
            clearNotification()
            try { cancelWork() } catch (_: Exception) { /* Preserve the original state failure. */ }
            throw failure
        }
    }
}
