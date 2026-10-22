package io.aaps.copilot.service

import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import io.aaps.copilot.data.repository.LocalV3Collection
import io.aaps.copilot.data.repository.LocalV3RetainedSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AapsV3ObservedProfileStatus { UNCHECKED, OBSERVED, NOT_OBSERVED, INVALIDATED, CONFLICTING, INVALID, LIMIT_REACHED, ERROR }

/** Observational only: a local retained revision is not a source-completeness or therapy admission token. */
internal data class AapsV3EffectiveProfileRead(
    val status: AapsV3ObservedProfileStatus,
    val retainedRevision: Long?,
    val observation: AapsV3EffectiveProfileObservation? = null
) {
    override fun toString() = "AapsV3EffectiveProfileRead(status=$status, redacted)"
}

internal data class AapsV3ObservedClinicalRead(val profile: AapsV3EffectiveProfileRead, val insulin: AapsV3InsulinRead) {
    override fun toString() = "AapsV3ObservedClinicalRead(redacted)"
}

internal class AapsV3EffectiveProfileSource(
    private val store: LocalNightscoutV3Store,
    private val maxRows: Int = 10_000,
    private val maxPayloadBytes: Long = 4L * 1024 * 1024
) {
    private val mutex = Mutex()
    private class Index(snapshot: LocalV3RetainedSnapshot) {
        val revision = snapshot.collectionRevision
        val profile = AapsV3EffectiveProfileTimeline(snapshot)
        val insulin = AapsV3InsulinInventoryTimeline(snapshot)
    }
    private var cached: Index? = null

    suspend fun read(atMs: Long): AapsV3EffectiveProfileRead = readEvidence(1, atMs, atMs).profile

    suspend fun readEvidence(fromMs: Long, atMs: Long, knownAtMs: Long): AapsV3ObservedClinicalRead = mutex.withLock {
        currentCoroutineContext().ensureActive()
        fun unavailable(profile: AapsV3ObservedProfileStatus, insulin: AapsV3ObservedInsulinStatus) =
            AapsV3ObservedClinicalRead(AapsV3EffectiveProfileRead(profile, null), AapsV3InsulinRead(insulin, null))
        if (fromMs !in 1..atMs || atMs > knownAtMs || knownAtMs > LocalNightscoutV3Store.MAX_TIME) {
            return@withLock unavailable(AapsV3ObservedProfileStatus.INVALID, AapsV3ObservedInsulinStatus.INVALID)
        }
        try {
            val head = store.lastModified().getValue(LocalV3Collection.TREATMENTS)
            val index = cached?.takeIf { it.revision == head } ?: Index(
                store.retainedSnapshot(LocalV3Collection.TREATMENTS, maxRows, maxPayloadBytes)
            ).also { cached = it }
            currentCoroutineContext().ensureActive()
            AapsV3ObservedClinicalRead(index.profile.read(atMs), index.insulin.read(fromMs, atMs, knownAtMs))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            cached = null
            unavailable(AapsV3ObservedProfileStatus.ERROR, AapsV3ObservedInsulinStatus.ERROR)
        }
    }
}

/** Index only typed EPS evidence; raw bolus/meal payloads are not retained in the cache. */
internal class AapsV3EffectiveProfileTimeline(snapshot: LocalV3RetainedSnapshot) {
    val revision = snapshot.collectionRevision
    private val evidence: List<Pair<Long, AapsV3ProfileProjection>>
    private val failure: AapsV3ObservedProfileStatus?

    init {
        if (snapshot is LocalV3RetainedSnapshot.OverLimit) {
            evidence = emptyList()
            failure = AapsV3ObservedProfileStatus.LIMIT_REACHED
        } else {
            val rows = (snapshot as LocalV3RetainedSnapshot.Complete).documents
            val invalid = revision !in 0..LocalNightscoutV3Store.MAX_TIME ||
                rows.map { it.identifier }.distinct().size != rows.size ||
                rows.any { it.collection != "treatments" || it.source != "local-v3-owner" ||
                    it.createdRevision !in 1..it.modifiedRevision || it.modifiedRevision > revision ||
                    it.eventAtMs == null || it.eventAtMs !in 1..LocalNightscoutV3Store.MAX_TIME }
            evidence = if (invalid) emptyList() else rows.mapNotNull { row ->
                val projected = AapsV3EffectiveProfileProjector.project(row)
                if (projected == AapsV3ProfileProjection.NotEffectiveProfile) null else checkNotNull(row.eventAtMs) to projected
            }
            failure = if (invalid) AapsV3ObservedProfileStatus.INVALID else null
        }
    }

    fun read(atMs: Long): AapsV3EffectiveProfileRead {
        fun unavailable(status: AapsV3ObservedProfileStatus) = AapsV3EffectiveProfileRead(status, revision)
        if (atMs !in 1..LocalNightscoutV3Store.MAX_TIME) return unavailable(AapsV3ObservedProfileStatus.INVALID)
        failure?.let { return unavailable(it) }
        val latest = evidence.filter { it.first <= atMs }.maxOfOrNull { it.first }
            ?: return unavailable(AapsV3ObservedProfileStatus.NOT_OBSERVED)
        val candidates = evidence.filter { it.first == latest }
        if (candidates.size != 1) return unavailable(AapsV3ObservedProfileStatus.CONFLICTING)
        return when (val projected = candidates.single().second) {
            is AapsV3ProfileProjection.Observed -> AapsV3EffectiveProfileRead(AapsV3ObservedProfileStatus.OBSERVED, revision, projected.observation)
            is AapsV3ProfileProjection.Unavailable -> unavailable(if (projected.reason == AapsV3ProfileUnavailable.INVALIDATED)
                AapsV3ObservedProfileStatus.INVALIDATED else AapsV3ObservedProfileStatus.INVALID)
            AapsV3ProfileProjection.NotEffectiveProfile -> unavailable(AapsV3ObservedProfileStatus.NOT_OBSERVED)
        }
    }
}
