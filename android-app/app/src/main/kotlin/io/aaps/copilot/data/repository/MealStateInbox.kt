package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.MealReceiptEntity
import io.aaps.copilot.domain.meal.MealRecordRevision

internal data class MealInboxDrainResult(val applied: Int = 0, val quarantined: Int = 0, val conflictsTotal: Int = 0)

/** Minimal receipt journal, never a source of therapeutic actions. */
internal class MealStateInbox(private val db: CopilotDatabase) {
    private val dao = db.mealReceiptDao()
    private val state = MealStateRepository(db)
    private val processor = MealStateIngestionProcessor(state)

    suspend fun persist(event: MealIngestionEvent) = db.withTransaction {
        when (event) {
            is MealIngestionEvent.Input -> processor.process(event)
            is MealIngestionEvent.Confirmed -> {
                require(event.rows.size <= 200)
                val ids = event.rows.map { it.record.canonicalId }.toSet()
                val owners = state.canonicalOwners(ids)
                val receipts = if (ids.isEmpty()) mutableMapOf() else
                    dao.getAll(ids.toList()).associateBy { it.canonicalId }.toMutableMap()
                for ((inputId, record) in event.rows) {
                    val old = receipts[record.canonicalId]
                    val knownOwner = owners[record.canonicalId]
                    val links = listOfNotNull(old?.inputId, inputId, knownOwner).distinct()
                    val effective = if (old != null && old.revision >= record.revision) old.record() else record
                    val conflict = old?.conflicted == true || links.size > 1 ||
                        (old != null && old.revision == record.revision && old.record() != record)
                    val next = MealReceiptEntity(record.canonicalId, links.firstOrNull(), effective.revision,
                        effective.recordedAtMs, effective.grams, effective.deleted, conflict, old?.appliedRevision)
                    if (next != old) {
                        dao.upsert(next)
                        receipts[record.canonicalId] = next
                        for (link in links) {
                            if (dao.ownerCount(link) > 1 || links.size > 1) {
                                dao.quarantineInput(link)
                                receipts.replaceAll { _, value ->
                                    if (value.inputId == link) value.copy(conflicted = true) else value
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun drainBatch(): MealInboxDrainResult = db.withTransaction {
        var applied = 0
        var quarantined = 0
        for (row in dao.pending()) {
            val inputId = requireNotNull(row.inputId)
            val current = requireNotNull(state.get(inputId))
            val owner = db.mealStateDao().owner(row.canonicalId)
            // Reject domain conflicts before nested Room transactions: a caught
            // transaction failure would still roll back the outer quarantine.
            val valid = try {
                require(owner == null || owner == inputId)
                current.identity.reconcile(row.record())
                true
            } catch (_: IllegalArgumentException) { false }
            if (!valid) {
                dao.upsert(row.copy(conflicted = true))
                quarantined++
            } else {
                state.reconcile(inputId, row.record())
                dao.upsert(row.copy(appliedRevision = row.revision))
                applied++
            }
        }
        MealInboxDrainResult(applied, quarantined, dao.conflictCount())
    }

    private fun MealReceiptEntity.record() = MealRecordRevision(canonicalId, revision, recordedAtMs, grams, deleted)
}
