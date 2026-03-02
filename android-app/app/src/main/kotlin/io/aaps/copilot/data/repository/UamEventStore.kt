package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.dao.UamInferenceEventDao
import io.aaps.copilot.data.local.entity.UamInferenceEventEntity
import io.aaps.copilot.domain.predict.UamInferenceEvent
import io.aaps.copilot.domain.predict.UamInferenceState
import io.aaps.copilot.domain.predict.UamMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class UamEventStore(
    private val dao: UamInferenceEventDao
) {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, UamInferenceEvent>()
    private var loaded = false

    suspend fun loadAll(): List<UamInferenceEvent> = mutex.withLock {
        ensureLoaded()
        cache.values.sortedBy { it.createdAt }
    }

    suspend fun loadActive(): List<UamInferenceEvent> = mutex.withLock {
        ensureLoaded()
        cache.values
            .filter { it.state == UamInferenceState.SUSPECTED || it.state == UamInferenceState.CONFIRMED }
            .sortedBy { it.createdAt }
    }

    suspend fun upsert(events: List<UamInferenceEvent>) = mutex.withLock {
        if (events.isEmpty()) return@withLock
        ensureLoaded()
        events.forEach { cache[it.id] = it }
        dao.upsertAll(events.map { it.toEntity() })
    }

    suspend fun prune(olderThanTs: Long) = mutex.withLock {
        ensureLoaded()
        cache.entries.removeIf { (_, event) -> event.updatedAt < olderThanTs }
        dao.deleteOlderThan(olderThanTs)
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        dao.all().forEach { row -> cache[row.id] = row.toDomain() }
        loaded = true
    }

    private fun UamInferenceEvent.toEntity(): UamInferenceEventEntity = UamInferenceEventEntity(
        id = id,
        state = state.name,
        mode = mode.name,
        createdAt = createdAt,
        updatedAt = updatedAt,
        ingestionTs = ingestionTs,
        carbsModelG = carbsModelG,
        carbsDisplayG = carbsDisplayG,
        confidence = confidence,
        exportedGrams = exportedGrams,
        exportSeq = exportSeq,
        lastExportTs = lastExportTs,
        learnedEligible = learnedEligible
    )

    private fun UamInferenceEventEntity.toDomain(): UamInferenceEvent = UamInferenceEvent(
        id = id,
        state = runCatching { UamInferenceState.valueOf(state) }.getOrDefault(UamInferenceState.SUSPECTED),
        mode = runCatching { UamMode.valueOf(mode) }.getOrDefault(UamMode.NORMAL),
        createdAt = createdAt,
        updatedAt = updatedAt,
        ingestionTs = ingestionTs,
        carbsModelG = carbsModelG,
        carbsDisplayG = carbsDisplayG,
        confidence = confidence,
        exportedGrams = exportedGrams,
        exportSeq = exportSeq,
        lastExportTs = lastExportTs,
        learnedEligible = learnedEligible
    )
}
