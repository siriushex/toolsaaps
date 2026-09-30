package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.MealStateEntity
import io.aaps.copilot.data.local.entity.MealStateScenarioEntity
import io.aaps.copilot.data.local.entity.MealStateAbsorptionEntity
import io.aaps.copilot.domain.meal.*
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import kotlin.math.abs

internal data class StoredMealState(
    val identity: MealEpisodeIdentity,
    val belief: MealBelief?,
    val storageRevision: Long
)

/** Local research state only. No dependencies on therapy writers or notification delivery. */
internal class MealStateRepository(private val db: CopilotDatabase) {
    private val dao get() = db.mealStateDao()

    suspend fun get(id: String): StoredMealState? = db.withTransaction { load(id) }

    suspend fun canonicalOwners(ids: Set<String>): Map<String, String> {
        require(ids.size <= 200)
        if (ids.isEmpty()) return emptyMap()
        return dao.canonicalOwners(ids.toList()).associate { requireNotNull(it.canonicalMealId) to it.episodeId }
    }

    suspend fun recordInput(input: MealInput, initialBelief: MealBelief? = null): StoredMealState {
        require(input.id.length <= 256)
        initialBelief?.let { validate(it, MealEpisodeIdentity(input)) }
        return db.withTransaction {
            load(input.id)?.let {
                require(it.identity.input == input) { "Conflicting original meal input" }
                return@withTransaction it
            }
            val row = MealStateEntity(input.id, input.recordedAtMs, input.carbs.minimumGrams,
                input.carbs.maximumGrams, 0, null, null, null, null, null, null, null, null, null, null)
            dao.insert(row)
            if (initialBelief != null) writeBelief(row, initialBelief)
            requireNotNull(load(input.id))
        }
    }

    /** Explicit acknowledgement of this input only; never link by timestamp or glucose response. */
    suspend fun reconcile(inputId: String, record: MealRecordRevision): StoredMealState = db.withTransaction {
        require(record.canonicalId.length <= 256)
        val state = requireNotNull(load(inputId)) { "Unknown meal input" }
        val identity = state.identity.reconcile(record)
        if (identity === state.identity) return@withTransaction state
        val owner = dao.owner(record.canonicalId)
        require(owner == null || owner == inputId) { "Canonical meal already linked to a different input" }
        val row = requireNotNull(dao.get(inputId))
        dao.clearBelief(inputId)
        dao.update(row.copy(storageRevision = Math.addExact(row.storageRevision, 1),
            canonicalMealId = record.canonicalId, aapsRevision = record.revision,
            aapsRecordedAtMs = record.recordedAtMs, aapsGrams = record.grams, aapsDeleted = record.deleted,
            modelVersion = null, beliefRevision = null, lastSampleAtMs = null, lastSampleId = null,
            runtimeIdentity = null))
        requireNotNull(load(inputId))
    }

    /** CAS covers both posterior updates and AAPS corrections, not just estimator revision. */
    suspend fun saveBelief(inputId: String, expectedStorageRevision: Long, belief: MealBelief): Boolean =
        db.withTransaction {
            val state = load(inputId) ?: return@withTransaction false
            if (state.storageRevision != expectedStorageRevision || state.identity.aapsRecord?.deleted == true) {
                return@withTransaction false
            }
            if (receiptGate(state, requireReceipt = false) != null) return@withTransaction false
            validate(belief, state.identity)
            val previous = state.belief
            if (previous != null && (belief.revision <= previous.revision ||
                    (previous.lastSampleAtMs != null && (belief.lastSampleAtMs ?: 0) <= previous.lastSampleAtMs) ||
                    (previous.lastSampleId != null && belief.lastSampleId == previous.lastSampleId))) {
                return@withTransaction false
            }
            val row = requireNotNull(dao.get(inputId))
            writeBelief(row.copy(storageRevision = Math.addExact(row.storageRevision, 1)), belief)
            true
        }

    /**
     * Apply a previously prepared causal scenario forecast to a newly received sample.
     * Caller must authenticate runtime/calibration identity and forecast provenance.
     * This API does not build priors, simulate scenarios or authorize notifications.
     */
    suspend fun observe(
        inputId: String,
        expectedStorageRevision: Long,
        observation: MealObservation,
        receivedAtMs: Long
    ): MealObservationWriteResult {
        val frozen = observation.copy(expected = observation.expected.toMap())
        if (frozen.sampleAtMs <= 0 || receivedAtMs < frozen.sampleAtMs ||
            receivedAtMs - frozen.sampleAtMs > 300_000L) {
            return MealObservationWriteResult(MealObservationWriteStatus.INVALID_SAMPLE_TIME)
        }
        return db.withTransaction {
            fun result(status: MealObservationWriteStatus) = MealObservationWriteResult(status)
            val state = load(inputId) ?: return@withTransaction result(MealObservationWriteStatus.UNKNOWN_INPUT)
            if (state.storageRevision != expectedStorageRevision) return@withTransaction result(MealObservationWriteStatus.STALE_STORAGE)
            val record = state.identity.aapsRecord ?: return@withTransaction result(MealObservationWriteStatus.UNCONFIRMED)
            if (record.deleted) return@withTransaction result(MealObservationWriteStatus.DELETED)
            receiptGate(state, requireReceipt = true)?.let { return@withTransaction result(it) }
            val prior = state.belief ?: return@withTransaction result(MealObservationWriteStatus.MISSING_BELIEF)
            val updated = MealStateEstimator().observe(prior, frozen)
            if (updated.reason != MealUpdateReason.UPDATED) return@withTransaction MealObservationWriteResult(
                MealObservationWriteStatus.ESTIMATOR_REJECTED, updated.reason)
            validate(updated.belief, state.identity)
            val row = requireNotNull(dao.get(inputId))
            writeBelief(row.copy(storageRevision = Math.addExact(row.storageRevision, 1)), updated.belief)
            MealObservationWriteResult(MealObservationWriteStatus.UPDATED, updated.reason)
        }
    }

    private suspend fun receiptGate(state: StoredMealState, requireReceipt: Boolean): MealObservationWriteStatus? {
        val record = state.identity.aapsRecord
        val rows = db.mealReceiptDao().relevant(state.identity.input.id, record?.canonicalId)
        if (rows.size > 1 || rows.any { it.conflicted }) return MealObservationWriteStatus.QUARANTINED
        val row = rows.singleOrNull() ?: return if (requireReceipt) MealObservationWriteStatus.PENDING_RECONCILIATION else null
        if (record == null || row.inputId != state.identity.input.id || row.canonicalId != record.canonicalId ||
            row.revision != record.revision || row.appliedRevision != record.revision ||
            row.recordedAtMs != record.recordedAtMs || row.grams != record.grams || row.deleted != record.deleted) {
            return MealObservationWriteStatus.PENDING_RECONCILIATION
        }
        return null
    }

    private suspend fun writeBelief(row: MealStateEntity, belief: MealBelief) {
        dao.clearBelief(row.episodeId)
        dao.update(row.copy(modelVersion = belief.modelVersion, beliefRevision = belief.revision,
            lastSampleAtMs = belief.lastSampleAtMs, lastSampleId = belief.lastSampleId,
            runtimeIdentity = belief.runtimeIdentity))
        dao.insertScenarios(belief.scenarios.mapIndexed { index, s -> MealStateScenarioEntity(
            row.episodeId, s.id, index, s.kind.name, s.inferredStart?.earliestMs, s.inferredStart?.latestMs,
            s.carbs.minimumGrams, s.carbs.maximumGrams, s.probability, s.canonicalMealId) })
        dao.insertAbsorption(belief.scenarios.flatMap { s -> s.absorption.mapIndexed { index, a ->
            MealStateAbsorptionEntity(row.episodeId, s.id, index, a.profile.name, a.durationMinutes, a.probability)
        } })
    }

    // Called only inside a transaction, so parent metadata and child distributions agree.
    private suspend fun load(id: String): StoredMealState? {
        val row = dao.get(id) ?: return null
        require(row.storageRevision >= 0 && row.episodeId.length <= 256)
        val input = MealInput(row.episodeId, row.recordedAtMs, MealCarbRange(row.minimumGrams, row.maximumGrams))
        val record = row.canonicalMealId?.let { canonical ->
            require(canonical.length <= 256)
            MealRecordRevision(canonical, requireNotNull(row.aapsRevision), requireNotNull(row.aapsRecordedAtMs),
                requireNotNull(row.aapsGrams), requireNotNull(row.aapsDeleted))
        }
        if (record == null) require(row.aapsRevision == null && row.aapsRecordedAtMs == null &&
            row.aapsGrams == null && row.aapsDeleted == null)
        val identity = MealEpisodeIdentity(input, record)
        val cases = dao.scenarios(id)
        val alternatives = dao.absorption(id)
        if (row.beliefRevision == null) {
            require(row.modelVersion == null && row.lastSampleAtMs == null && row.lastSampleId == null &&
                row.runtimeIdentity == null && cases.isEmpty() && alternatives.isEmpty())
            return StoredMealState(identity, null, row.storageRevision)
        }
        require(cases.size in 6..96 && cases.map { it.position } == cases.indices.toList())
        require(alternatives.size <= 1536 && alternatives.all { a -> cases.any { it.scenarioId == a.scenarioId } })
        val byCase = alternatives.groupBy { it.scenarioId }
        val scenarios = cases.map { s ->
            require((s.earliestStartMs == null) == (s.latestStartMs == null))
            val profiles = byCase[s.scenarioId].orEmpty()
            require(profiles.size in 1..16 && profiles.map { it.position } == profiles.indices.toList())
            MealScenario(s.scenarioId, MealHypothesisKind.valueOf(s.kind),
                s.earliestStartMs?.let { MealStartInterval(it, requireNotNull(s.latestStartMs)) },
                MealCarbRange(s.minimumGrams, s.maximumGrams), profiles.map {
                    MealAbsorptionAlternative(MealAbsorptionProfile.valueOf(it.profile), it.durationMinutes, it.probability)
                }, s.probability, s.canonicalMealId)
        }
        val belief = MealBelief(input, scenarios, row.beliefRevision, row.lastSampleAtMs,
            row.lastSampleId, row.runtimeIdentity)
        require(row.modelVersion == belief.modelVersion) { "Unsupported meal belief model" }
        require(record?.deleted != true)
        validate(belief, identity)
        return StoredMealState(identity, belief, row.storageRevision)
    }

    private fun validate(belief: MealBelief, identity: MealEpisodeIdentity) {
        require(belief.input == identity.input && belief.revision >= 0)
        require(belief.scenarios.size in 6..96)
        require(belief.scenarios.map { it.id }.distinct().size == belief.scenarios.size)
        require(belief.scenarios.map { it.kind }.toSet() == MealHypothesisKind.entries.toSet())
        require(abs(belief.scenarios.sumOf { it.probability } - 1.0) < 1e-9)
        require(belief.scenarios.all { it.id.length <= 512 && it.absorption.size <= 16 &&
            it.canonicalMealId == identity.aapsRecord?.canonicalId })
        require((belief.lastSampleAtMs == null) == (belief.lastSampleId == null))
        require(belief.lastSampleId == null || (belief.lastSampleId.isNotBlank() && belief.lastSampleId.length <= 512))
        require(belief.runtimeIdentity == null || (belief.runtimeIdentity.isNotBlank() && belief.runtimeIdentity.length <= 512))
        require(belief.lastSampleAtMs == null || (belief.lastSampleAtMs > belief.input.recordedAtMs &&
            belief.runtimeIdentity != null))
    }
}
