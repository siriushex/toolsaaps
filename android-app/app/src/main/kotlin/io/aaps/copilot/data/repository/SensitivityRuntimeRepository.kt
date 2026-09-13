package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.entity.SensitivityRuntimeSnapshotEntity
import io.aaps.copilot.domain.predict.AcceptedSensitivityTupleFreshness
import io.aaps.copilot.domain.predict.SensitivityCandidates
import io.aaps.copilot.domain.predict.SensitivityRuntimeResolver
import io.aaps.copilot.domain.predict.SensitivityRuntimeSnapshot
import io.aaps.copilot.domain.predict.SensitivityResolvedSource
import io.aaps.copilot.domain.predict.SensitivitySourcePreference
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SensitivityRuntimeTrigger {
    OVERVIEW,
    FORECAST,
    SETTINGS_CHANGED,
    THERAPY_UPDATED
}

data class SensitivityRuntimeLoadedCandidates(
    val settingsRevision: Long,
    val isfPreference: SensitivitySourcePreference,
    val crPreference: SensitivitySourcePreference,
    val isfCandidates: SensitivityCandidates,
    val crCandidates: SensitivityCandidates
)

fun interface SensitivityRuntimeCandidateLoader {
    suspend fun load(): SensitivityRuntimeLoadedCandidates
}

fun interface SensitivityRuntimePersistence {
    suspend fun save(entity: SensitivityRuntimeSnapshotEntity)
}

data class AcceptedSensitivityRuntimePublication(
    val snapshot: SensitivityRuntimeSnapshot,
    val acceptedAtTs: Long,
    val acceptedCycleId: String
)

data class AcceptedSensitivityCandidateDiagnostics(
    val settingsRevision: Long,
    val forecastCycleId: String,
    val snapshotTimestamp: Long,
    val freshnessMs: Long,
    val isfCandidates: SensitivityCandidates,
    val crCandidates: SensitivityCandidates
) {
    fun matches(snapshot: SensitivityRuntimeSnapshot): Boolean =
        settingsRevision == snapshot.settingsRevision &&
            forecastCycleId == snapshot.forecastCycleId &&
            snapshotTimestamp == snapshot.timestamp
}

internal class SensitivityRuntimeAcceptanceReservation internal constructor(
    internal val snapshot: SensitivityRuntimeSnapshot,
    internal val acceptedAtTs: Long,
    internal val acceptedCycleId: String
)

class SensitivityRuntimeRepository(
    private val loadedCandidates: suspend () -> SensitivityRuntimeLoadedCandidates,
    private val persistence: SensitivityRuntimePersistence,
    private val settingsRevision: (suspend () -> Long)? = null,
    private val acceptedSnapshotLoader: (
        suspend (settingsRevision: Long, atTs: Long) -> AcceptedSensitivityRuntimePublication?
    )? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val freshnessMs: Long = 60L * 60L * 1_000L
) {
    private val mutex = Mutex()
    private val acceptedStateLock = Any()
    private val _current = MutableStateFlow<SensitivityRuntimeSnapshot?>(null)
    private val _acceptedCandidateDiagnostics =
        MutableStateFlow<AcceptedSensitivityCandidateDiagnostics?>(null)
    private val candidateDiagnosticsByIdentity =
        LinkedHashMap<SensitivitySnapshotIdentity, AcceptedSensitivityCandidateDiagnostics>()
    private var latestCandidate: SensitivityRuntimeSnapshot? = null
    private var acceptedOrder: AcceptedPublicationOrder? = null
    private var acceptedReservation: SensitivityRuntimeAcceptanceReservation? = null
    val current: StateFlow<SensitivityRuntimeSnapshot?> = _current.asStateFlow()
    val acceptedCandidateDiagnostics: StateFlow<AcceptedSensitivityCandidateDiagnostics?> =
        _acceptedCandidateDiagnostics.asStateFlow()

    internal fun isfCrInputGenerationForCandidate(snapshot: SensitivityRuntimeSnapshot): IsfCrInputGeneration =
        synchronized(acceptedStateLock) {
            val diagnostics = requireNotNull(candidateDiagnosticsByIdentity[snapshot.identity()]) {
                "accepted sensitivity candidate diagnostics are unavailable"
            }
            val generations = listOf(
                diagnostics.isfCandidates
                    .contributingCandidates(snapshot.isf.resolved)
                    .map { it.requireInputGeneration("ISF") },
                diagnostics.crCandidates
                    .contributingCandidates(snapshot.cr.resolved)
                    .map { it.requireInputGeneration("CR") }
            ).flatten()
            require(generations.distinct().size == 1) {
                "accepted sensitivity candidates use different ISF/CR input generations"
            }
            generations.first()
        }

    private fun SensitivityCandidates.contributingCandidates(
        resolved: SensitivityResolvedSource
    ) = when (resolved) {
        SensitivityResolvedSource.AAPS -> listOf(aaps)
        SensitivityResolvedSource.EVIDENCE_BLEND -> listOf(evidence, copilot)
        SensitivityResolvedSource.COPILOT_NATIVE -> listOf(copilot)
    }

    private fun io.aaps.copilot.domain.predict.SensitivityCandidate.requireInputGeneration(
        metric: String
    ) = IsfCrInputGeneration(
        modelRevision = requireNotNull(modelRevision) {
            "accepted $metric evidence candidate has no base model revision"
        },
        profileRevision = requireNotNull(profileRevision) {
            "accepted $metric evidence candidate has no profile revision"
        }
    )

    suspend fun recompute(trigger: SensitivityRuntimeTrigger): SensitivityRuntimeSnapshot = mutex.withLock {
        recomputeLocked(trigger)
    }

    suspend fun recomputeIfAbsent(trigger: SensitivityRuntimeTrigger): SensitivityRuntimeSnapshot = mutex.withLock {
        val revisionBefore = settingsRevision?.invoke()
        val reusable = latestCandidate?.takeIf { candidate ->
            revisionBefore == null || candidate.settingsRevision == revisionBefore
        }
        if (reusable != null) {
            val revisionAfter = settingsRevision?.invoke()
            require(revisionAfter == null || revisionAfter == reusable.settingsRevision) {
                "sensitivity settings changed while reusing candidate"
            }
            reusable
        } else {
            recomputeLocked(trigger)
        }
    }

    internal suspend fun reserveAccepted(
        snapshot: SensitivityRuntimeSnapshot,
        acceptedAtTs: Long,
        acceptedCycleId: String
    ): SensitivityRuntimeAcceptanceReservation? {
        val incoming = validateAcceptedPublicationLocked(snapshot, acceptedAtTs, acceptedCycleId)
        return synchronized(acceptedStateLock) {
            if (acceptedReservation != null) return@synchronized null
            val existing = acceptedOrder
            if (existing != null && incoming < existing) return@synchronized null
            if (existing == incoming && _current.value != null) {
                require(_current.value == snapshot) {
                    "accepted sensitivity cycle identity conflicts with current snapshot"
                }
                return@synchronized null
            }
            SensitivityRuntimeAcceptanceReservation(snapshot, acceptedAtTs, acceptedCycleId).also {
                acceptedReservation = it
            }
        }
    }

    internal fun finalizeAccepted(
        reservation: SensitivityRuntimeAcceptanceReservation
    ) = synchronized(acceptedStateLock) {
        require(acceptedReservation === reservation) {
            "accepted sensitivity reservation ownership was lost"
        }
        applyAcceptedStateLocked(
            snapshot = reservation.snapshot,
            order = AcceptedPublicationOrder(
                settingsRevision = reservation.snapshot.settingsRevision,
                acceptedAtTs = reservation.acceptedAtTs,
                cycleId = reservation.acceptedCycleId
            )
        )
        acceptedReservation = null
    }

    internal fun abortAcceptedReservation(
        reservation: SensitivityRuntimeAcceptanceReservation
    ) = synchronized(acceptedStateLock) {
        if (acceptedReservation === reservation) acceptedReservation = null
    }

    suspend fun hydrateFromAcceptedTuple(): SensitivityRuntimeSnapshot? = mutex.withLock {
        val revisionBefore = settingsRevision?.invoke() ?: return@withLock null
        val accepted = acceptedSnapshotLoader?.invoke(revisionBefore, now()) ?: return@withLock null
        val revisionAfter = settingsRevision.invoke()
        if (
            revisionBefore != revisionAfter ||
            accepted.snapshot.settingsRevision != revisionBefore
        ) return@withLock null
        val incoming = validateAcceptedPublicationLocked(
            snapshot = accepted.snapshot,
            acceptedAtTs = accepted.acceptedAtTs,
            acceptedCycleId = accepted.acceptedCycleId
        )
        synchronized(acceptedStateLock) {
            when (reserveAcceptedHydrationLocked(accepted.snapshot, incoming)) {
                HydrationReservationResult.REJECTED -> null
                HydrationReservationResult.ALREADY_CURRENT -> _current.value
                HydrationReservationResult.RESERVED -> {
                    finalizeAcceptedHydrationLocked(accepted.snapshot, incoming)
                    _current.value
                }
            }
        }
    }

    private fun reserveAcceptedHydrationLocked(
        snapshot: SensitivityRuntimeSnapshot,
        incoming: AcceptedPublicationOrder
    ): HydrationReservationResult {
        if (acceptedReservation != null) return HydrationReservationResult.REJECTED
        val existing = acceptedOrder
        if (existing != null && incoming < existing) return HydrationReservationResult.REJECTED
        if (existing == incoming) {
            require(_current.value == snapshot) {
                "accepted sensitivity cycle identity conflicts with current snapshot"
            }
            return HydrationReservationResult.ALREADY_CURRENT
        }
        return HydrationReservationResult.RESERVED
    }

    private fun finalizeAcceptedHydrationLocked(
        snapshot: SensitivityRuntimeSnapshot,
        order: AcceptedPublicationOrder
    ) = applyAcceptedStateLocked(snapshot, order)

    private fun applyAcceptedStateLocked(
        snapshot: SensitivityRuntimeSnapshot,
        order: AcceptedPublicationOrder
    ) {
        _acceptedCandidateDiagnostics.value = candidateDiagnosticsByIdentity[snapshot.identity()]
        _current.value = snapshot
        acceptedOrder = order
    }

    private enum class HydrationReservationResult {
        REJECTED,
        ALREADY_CURRENT,
        RESERVED
    }

    private suspend fun recomputeLocked(trigger: SensitivityRuntimeTrigger): SensitivityRuntimeSnapshot {
        val loaded = loadedCandidates()
        val before = settingsRevision?.invoke() ?: loaded.settingsRevision
        require(before == loaded.settingsRevision) {
            "sensitivity settings changed during load: loaded=${loaded.settingsRevision}, current=$before"
        }
        val timestamp = now()
        val snapshot = SensitivityRuntimeResolver.resolve(
            settingsRevision = loaded.settingsRevision,
            forecastCycleId = newCycleId(timestamp, trigger),
            timestamp = timestamp,
            isfPreference = loaded.isfPreference,
            crPreference = loaded.crPreference,
            isfCandidates = loaded.isfCandidates,
            crCandidates = loaded.crCandidates,
            freshnessMs = freshnessMs
        )
        val after = settingsRevision?.invoke() ?: loaded.settingsRevision
        require(after == loaded.settingsRevision) {
            "sensitivity settings changed before publish: loaded=${loaded.settingsRevision}, current=$after"
        }
        persistence.save(snapshot.toEntity())
        val publishedRevision = settingsRevision?.invoke() ?: loaded.settingsRevision
        require(publishedRevision == loaded.settingsRevision) {
            "sensitivity settings changed during persistence: loaded=${loaded.settingsRevision}, current=$publishedRevision"
        }
        synchronized(acceptedStateLock) {
            candidateDiagnosticsByIdentity[snapshot.identity()] = AcceptedSensitivityCandidateDiagnostics(
                settingsRevision = snapshot.settingsRevision,
                forecastCycleId = snapshot.forecastCycleId,
                snapshotTimestamp = snapshot.timestamp,
                freshnessMs = freshnessMs,
                isfCandidates = loaded.isfCandidates,
                crCandidates = loaded.crCandidates
            )
            while (candidateDiagnosticsByIdentity.size > MAX_CANDIDATE_DIAGNOSTIC_IDENTITIES) {
                candidateDiagnosticsByIdentity.entries.iterator().run {
                    next()
                    remove()
                }
            }
        }
        latestCandidate = snapshot
        return snapshot
    }

    private suspend fun validateAcceptedPublicationLocked(
        snapshot: SensitivityRuntimeSnapshot,
        acceptedAtTs: Long,
        acceptedCycleId: String
    ): AcceptedPublicationOrder {
        require(acceptedCycleId.isNotBlank() && acceptedCycleId == snapshot.forecastCycleId) {
            "accepted sensitivity cycle identity mismatch"
        }
        require(acceptedAtTs > 0L && snapshot.timestamp > 0L) {
            "accepted sensitivity timestamps must be positive"
        }
        require(snapshot.timestamp <= acceptedAtTs) {
            "accepted sensitivity snapshot cannot follow its marker"
        }
        require(AcceptedSensitivityTupleFreshness.isFresh(snapshot.timestamp, acceptedAtTs)) {
            "accepted sensitivity snapshot is stale at publication"
        }
        val revisionBefore = settingsRevision?.invoke() ?: snapshot.settingsRevision
        require(revisionBefore == snapshot.settingsRevision) {
            "accepted sensitivity settings revision mismatch"
        }
        val incoming = AcceptedPublicationOrder(
            settingsRevision = snapshot.settingsRevision,
            acceptedAtTs = acceptedAtTs,
            cycleId = acceptedCycleId
        )
        val revisionAfter = settingsRevision?.invoke() ?: snapshot.settingsRevision
        require(revisionAfter == revisionBefore) {
            "accepted sensitivity settings changed during publication"
        }
        return incoming
    }

    private fun newCycleId(timestamp: Long, trigger: SensitivityRuntimeTrigger): String =
        "sensitivity-${timestamp}-${trigger.name.lowercase()}-${UUID.randomUUID()}"

    private data class AcceptedPublicationOrder(
        val settingsRevision: Long,
        val acceptedAtTs: Long,
        val cycleId: String
    ) : Comparable<AcceptedPublicationOrder> {
        override fun compareTo(other: AcceptedPublicationOrder): Int =
            compareValuesBy(this, other, AcceptedPublicationOrder::settingsRevision)
                .takeIf { it != 0 }
                ?: compareValuesBy(this, other, AcceptedPublicationOrder::acceptedAtTs)
                    .takeIf { it != 0 }
                ?: cycleId.compareTo(other.cycleId)
    }

    private data class SensitivitySnapshotIdentity(
        val settingsRevision: Long,
        val cycleId: String,
        val timestamp: Long
    )

    private fun SensitivityRuntimeSnapshot.identity() = SensitivitySnapshotIdentity(
        settingsRevision = settingsRevision,
        cycleId = forecastCycleId,
        timestamp = timestamp
    )

    private companion object {
        const val MAX_CANDIDATE_DIAGNOSTIC_IDENTITIES = 8
    }

}
