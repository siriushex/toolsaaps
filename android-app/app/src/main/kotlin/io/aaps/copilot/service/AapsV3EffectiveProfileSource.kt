package io.aaps.copilot.service

import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import io.aaps.copilot.data.repository.LocalV3Collection
import io.aaps.copilot.data.repository.LocalV3RetainedSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AapsV3ObservedProfileStatus { UNCHECKED, OBSERVED, NOT_OBSERVED, INVALIDATED, CONFLICTING, INVALID, LATE_EVIDENCE, LIMIT_REACHED, ERROR }

/** Observational only: a local retained revision is not a source-completeness or therapy admission token. */
internal data class AapsV3EffectiveProfileRead(
    val status: AapsV3ObservedProfileStatus,
    val retainedRevision: Long?,
    val observation: AapsV3EffectiveProfileObservation? = null
) {
    override fun toString() = "AapsV3EffectiveProfileRead(status=$status, redacted)"
}

internal data class AapsV3ObservedClinicalRead(val profile: AapsV3EffectiveProfileRead, val insulin: AapsV3InsulinRead,
    val profileHistory: AapsV3ProfileHistoryRead, val reportedBasal: AapsV3ReportedBasalRead) {
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

    /** Latest retained projection at event time; this compatibility view is not an as-of archive. */
    suspend fun read(atMs: Long): AapsV3EffectiveProfileRead = readEvidence(1, atMs, LocalNightscoutV3Store.MAX_TIME).profile

    suspend fun readEvidence(fromMs: Long, atMs: Long, knownAtMs: Long): AapsV3ObservedClinicalRead = mutex.withLock {
        currentCoroutineContext().ensureActive()
        fun unavailable(profile: AapsV3ObservedProfileStatus, insulin: AapsV3ObservedInsulinStatus) =
            AapsV3ObservedClinicalRead(AapsV3EffectiveProfileRead(profile, null), AapsV3InsulinRead(insulin, null),
                AapsV3ProfileHistoryRead(profile, null), AapsV3ReportedBasalRead(AapsV3ReportedBasalStatus.INPUT_UNAVAILABLE, null))
        if (fromMs !in 1..atMs || atMs > knownAtMs || knownAtMs > LocalNightscoutV3Store.MAX_TIME) {
            return@withLock unavailable(AapsV3ObservedProfileStatus.INVALID, AapsV3ObservedInsulinStatus.INVALID)
        }
        try {
            val head = store.lastModified().getValue(LocalV3Collection.TREATMENTS)
            val index = cached?.takeIf { it.revision == head } ?: Index(
                store.retainedSnapshot(LocalV3Collection.TREATMENTS, maxRows, maxPayloadBytes)
            ).also { cached = it }
            currentCoroutineContext().ensureActive()
            val insulin = index.insulin.read(fromMs, atMs, knownAtMs)
            val history = index.profile.readWindow(fromMs, atMs, knownAtMs)
            AapsV3ObservedClinicalRead(index.profile.read(atMs, knownAtMs), insulin, history,
                AapsV3ReportedBasalProjector.read(fromMs, atMs, knownAtMs, history, insulin))
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

    fun read(atMs: Long, knownAtMs: Long = LocalNightscoutV3Store.MAX_TIME): AapsV3EffectiveProfileRead {
        fun unavailable(status: AapsV3ObservedProfileStatus) = AapsV3EffectiveProfileRead(status, revision)
        if (atMs !in 1..knownAtMs || knownAtMs > LocalNightscoutV3Store.MAX_TIME)
            return unavailable(AapsV3ObservedProfileStatus.INVALID)
        failure?.let { return unavailable(it) }
        // Latest retained versions cannot reconstruct an earlier database after a moved/deleted revision.
        if (revision > knownAtMs) return unavailable(AapsV3ObservedProfileStatus.LATE_EVIDENCE)
        val latest = evidence.filter { it.first <= atMs }.maxOfOrNull { it.first }
            ?: return unavailable(AapsV3ObservedProfileStatus.NOT_OBSERVED)
        val candidates = evidence.filter { it.first == latest }
        return resolve(candidates.map { it.second })
    }

    fun readWindow(fromMs: Long, untilMs: Long, knownAtMs: Long): AapsV3ProfileHistoryRead {
        fun unavailable(status: AapsV3ObservedProfileStatus) = AapsV3ProfileHistoryRead(status, revision)
        if (fromMs !in 1 until untilMs || untilMs > knownAtMs || knownAtMs > LocalNightscoutV3Store.MAX_TIME)
            return unavailable(AapsV3ObservedProfileStatus.INVALID)
        failure?.let { return unavailable(it) }
        if (revision > knownAtMs) return unavailable(AapsV3ObservedProfileStatus.LATE_EVIDENCE)
        if (untilMs - fromMs > 172_800_000L) return unavailable(AapsV3ObservedProfileStatus.LIMIT_REACHED)
        val anchor = evidence.filter { it.first <= fromMs }.maxOfOrNull { it.first }
            ?: return unavailable(AapsV3ObservedProfileStatus.NOT_OBSERVED)
        val changes = evidence.filter { it.first >= anchor && it.first < untilMs }.groupBy { it.first }.toSortedMap()
        if (changes.size > 2_880) return unavailable(AapsV3ObservedProfileStatus.LIMIT_REACHED)
        val resolved = changes.map { (at, candidates) -> at to resolve(candidates.map { it.second }) }
        resolved.firstOrNull { it.second.status != AapsV3ObservedProfileStatus.OBSERVED }?.let {
            return unavailable(it.second.status)
        }
        val intervals = resolved.mapIndexed { index, (at, read) -> AapsV3ProfileHistoryInterval(
            maxOf(fromMs, at), resolved.getOrNull(index + 1)?.first ?: untilMs, checkNotNull(read.observation)) }
        return AapsV3ProfileHistoryRead(AapsV3ObservedProfileStatus.OBSERVED, revision, intervals)
    }

    private fun resolve(candidates: List<AapsV3ProfileProjection>): AapsV3EffectiveProfileRead {
        fun unavailable(status: AapsV3ObservedProfileStatus) = AapsV3EffectiveProfileRead(status, revision)
        if (candidates.size != 1) return unavailable(AapsV3ObservedProfileStatus.CONFLICTING)
        return when (val projected = candidates.single()) {
            is AapsV3ProfileProjection.Observed -> AapsV3EffectiveProfileRead(AapsV3ObservedProfileStatus.OBSERVED, revision, projected.observation)
            is AapsV3ProfileProjection.Unavailable -> unavailable(if (projected.reason == AapsV3ProfileUnavailable.INVALIDATED)
                AapsV3ObservedProfileStatus.INVALIDATED else AapsV3ObservedProfileStatus.INVALID)
            AapsV3ProfileProjection.NotEffectiveProfile -> unavailable(AapsV3ObservedProfileStatus.NOT_OBSERVED)
        }
    }
}
