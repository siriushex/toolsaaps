package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.meal.MealCarbRange
import io.aaps.copilot.domain.meal.MealInput
import io.aaps.copilot.domain.meal.MealRecordRevision
import io.aaps.copilot.domain.model.ActionCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.Collections

internal data class MealAapsConfirmation(val inputId: String?, val record: MealRecordRevision)
internal sealed interface MealIngestionEvent {
    data class Input(val value: MealInput) : MealIngestionEvent
    class Confirmed(rows: List<MealAapsConfirmation>) : MealIngestionEvent {
        val rows: List<MealAapsConfirmation> = Collections.unmodifiableList(rows.toList())
    }
}
internal data class MealIngestionHealth(
    val processedBatches: Long = 0,
    val failedBatches: Long = 0,
    val deferredSignals: Long = 0,
    val rejectedBatches: Long = 0,
    val quarantinedReceipts: Int = 0
)

/** Persist before signaling. Signals may coalesce; durable receipts never live in the channel. */
internal class MealStateIngestionQueue(
    scope: CoroutineScope,
    private val process: suspend (MealIngestionEvent) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val drain: suspend () -> MealInboxDrainResult = { MealInboxDrainResult() }
) {
    private val channel = Channel<Unit>(Channel.CONFLATED)
    private val mutableHealth = MutableStateFlow(MealIngestionHealth())
    val health = mutableHealth.asStateFlow()

    init {
        scope.launch {
            for (signal in channel) {
                try {
                    do {
                        val result = drain()
                        mutableHealth.update { it.copy(quarantinedReceipts = result.conflictsTotal) }
                        yield()
                    } while (result.applied + result.quarantined > 0)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mutableHealth.update { it.copy(failedBatches = increment(it.failedBatches)) }
                }
            }
        }.invokeOnCompletion { channel.cancel() }
        wake()
    }

    suspend fun offerInput(command: ActionCommand): Boolean {
        val grams = command.params["carbsGrams"]?.toDoubleOrNull()
        val now = clock()
        if (command.type != "carbs" || !INPUT_ID.matches(command.idempotencyKey) ||
            grams == null || !grams.isFinite() || grams <= 0 || now <= 0) return reject()
        return offer(MealIngestionEvent.Input(MealInput(command.idempotencyKey, now, MealCarbRange(grams, grams))))
    }

    /** The protected importer persists within its transaction, then wakes after commit. */
    suspend fun offerPage(page: AapsCarbHistoryPage, wakeAfterPersist: Boolean = true): Boolean {
        if (page.rows.size > 200) return reject()
        val current = page.rows.filter { it.referenceId == null }
        if (current.any { it.id <= 0 || it.version < 0 || it.timestamp <= 0 ||
                !it.amount.isFinite() || it.amount < 0 }) return reject()
        val rows = current.map { row ->
            val inputId = row.notes?.takeIf { !row.notesTruncated && it.length <= 128 }
                ?.removePrefix("copilot:")?.takeIf { row.notes == "copilot:$it" && INPUT_ID.matches(it) }
            MealAapsConfirmation(inputId, MealRecordRevision(row.id.toString(), row.version.toLong(),
                row.timestamp, row.amount, !row.isValid))
        }
        return rows.isEmpty() || offer(MealIngestionEvent.Confirmed(rows), wakeAfterPersist)
    }

    fun wake() {
        if (channel.trySend(Unit).isFailure) {
            mutableHealth.update { it.copy(deferredSignals = increment(it.deferredSignals)) }
        }
    }

    private suspend fun offer(event: MealIngestionEvent, wakeAfterPersist: Boolean = true): Boolean {
        try { process(event) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            mutableHealth.update { it.copy(failedBatches = increment(it.failedBatches)) }
            throw failure
        }
        mutableHealth.update { it.copy(processedBatches = increment(it.processedBatches)) }
        if (wakeAfterPersist) wake()
        return true
    }
    private fun reject(): Boolean {
        mutableHealth.update { it.copy(rejectedBatches = increment(it.rejectedBatches)) }
        return false
    }
    private fun increment(value: Long): Long = value.coerceAtMost(Long.MAX_VALUE - 1) + 1

    companion object { private val INPUT_ID = Regex("manual:meal:[A-Za-z0-9_-]{1,80}") }
}

internal class MealStateIngestionProcessor(private val repository: MealStateRepository) {
    suspend fun process(event: MealIngestionEvent) {
        when (event) {
            is MealIngestionEvent.Input -> {
                val existing = repository.get(event.value.id)
                if (existing == null) repository.recordInput(event.value)
                else require(existing.identity.input.carbs == event.value.carbs) { "Conflicting input replay" }
            }
            is MealIngestionEvent.Confirmed -> {
                val rows = event.rows.distinct()
                require(rows.size <= 200)
                require(rows.groupBy { it.record.canonicalId }.values.all { it.size == 1 })
                require(rows.filter { it.inputId != null }.groupBy { it.inputId }.values.all { it.size == 1 }) {
                    "Ambiguous meal acknowledgement"
                }
                val owners = repository.canonicalOwners(rows.map { it.record.canonicalId }.toSet())
                for (row in rows) {
                    val linkedId = owners[row.record.canonicalId]
                    if (linkedId != null && row.inputId != null) {
                        require(linkedId == row.inputId) { "Conflicting meal acknowledgement" }
                    }
                    val inputId = linkedId ?: row.inputId ?: continue
                    if (repository.get(inputId) != null) repository.reconcile(inputId, row.record)
                }
            }
        }
    }
}
