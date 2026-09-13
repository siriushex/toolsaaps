package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.predict.SensitivityCandidate
import io.aaps.copilot.domain.predict.SensitivityCandidates
import io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer
import io.aaps.copilot.domain.predict.SensitivityRuntimeFanOut
import io.aaps.copilot.domain.predict.SensitivitySourcePreference
import io.aaps.copilot.testSensitivityRuntimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SensitivityRuntimeRepositoryTest {

    @Test
    fun recomputePersistsOneCandidateButDoesNotPublishBeforeCycleAcceptance() = runTest {
        val persistence = RecordingPersistence()
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { loaded(9L) },
            persistence = persistence,
            now = { 1_000L }
        )

        val candidate = repository.recompute(SensitivityRuntimeTrigger.OVERVIEW)

        assertThat(repository.current.value).isNull()
        assertThat(candidate.settingsRevision).isEqualTo(9L)
        assertThat(candidate.forecastCycleId).isEqualTo(persistence.saved!!.cycleId)
        assertThat(candidate.isf.effective).isEqualTo(3.0)
        assertThat(candidate.cr.effective).isEqualTo(10.0)
        assertThat(persistence.saved!!.settingsRevision).isEqualTo(candidate.settingsRevision)
    }

    @Test
    fun settingChangeRecomputesFromLoadedDataWithoutWorker() = runTest {
        var loads = 0
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { loads++; loaded(loads.toLong()) },
            persistence = RecordingPersistence(),
            now = { 1_000L }
        )

        repository.recompute(SensitivityRuntimeTrigger.SETTINGS_CHANGED)
        repository.recompute(SensitivityRuntimeTrigger.SETTINGS_CHANGED)

        assertThat(loads).isEqualTo(2)
        assertThat(repository.current.value).isNull()
    }

    @Test
    fun acceptedPublicationIsTheOnlyOperationThatUpdatesCurrent() = runTest {
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { loaded(4L) },
            persistence = RecordingPersistence(),
            settingsRevision = { 4L },
            now = { 1_000L }
        )
        val candidate = repository.recompute(SensitivityRuntimeTrigger.FORECAST)

        val published = accept(
            repository = repository,
            snapshot = candidate,
            acceptedAtTs = 1_100L,
            acceptedCycleId = candidate.forecastCycleId
        )

        assertThat(published).isTrue()
        assertThat(repository.current.value).isSameInstanceAs(candidate)
    }

    @Test
    fun acceptedPublicationExposesOnlyIdentityMatchedCandidateDiagnostics() = runTest {
        val loaded = loaded(4L)
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { loaded },
            persistence = RecordingPersistence(),
            settingsRevision = { 4L },
            now = { 1_000L }
        )

        val candidate = repository.recompute(SensitivityRuntimeTrigger.FORECAST)

        assertThat(repository.acceptedCandidateDiagnostics.value).isNull()
        assertThat(accept(repository, candidate, 1_100L, candidate.forecastCycleId)).isTrue()

        val diagnostics = repository.acceptedCandidateDiagnostics.value
        assertThat(diagnostics?.settingsRevision).isEqualTo(candidate.settingsRevision)
        assertThat(diagnostics?.forecastCycleId).isEqualTo(candidate.forecastCycleId)
        assertThat(diagnostics?.snapshotTimestamp).isEqualTo(candidate.timestamp)
        assertThat(diagnostics?.freshnessMs).isEqualTo(60L * 60L * 1_000L)
        assertThat(diagnostics?.isfCandidates).isEqualTo(loaded.isfCandidates)
        assertThat(diagnostics?.crCandidates).isEqualTo(loaded.crCandidates)
    }

    @Test
    fun acceptingSnapshotWithoutMatchingCandidateDiagnosticsClearsPreviousMetadata() = runTest {
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { loaded(4L) },
            persistence = RecordingPersistence(),
            settingsRevision = { 4L },
            now = { 1_000L }
        )
        val first = repository.recompute(SensitivityRuntimeTrigger.FORECAST)
        assertThat(accept(repository, first, 1_100L, first.forecastCycleId)).isTrue()
        assertThat(repository.acceptedCandidateDiagnostics.value).isNotNull()
        val detached = first.copy(
            forecastCycleId = "detached-cycle",
            timestamp = 1_050L
        )

        assertThat(accept(repository, detached, 1_200L, detached.forecastCycleId)).isTrue()

        assertThat(repository.current.value).isSameInstanceAs(detached)
        assertThat(repository.acceptedCandidateDiagnostics.value).isNull()
    }

    @Test
    fun revisionRaceRejectsAcceptedPublicationAndKeepsLastAcceptedSnapshot() = runTest {
        var revision = 5L
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { loaded(revision) },
            persistence = RecordingPersistence(),
            settingsRevision = { revision },
            now = { 1_000L }
        )
        val accepted = repository.recompute(SensitivityRuntimeTrigger.FORECAST)
        accept(repository, accepted, 1_100L, accepted.forecastCycleId)
        val rejected = accepted.copy(
            forecastCycleId = "rejected-cycle",
            timestamp = 1_050L
        )
        revision = 6L

        val failure = runCatching {
            accept(repository, rejected, 1_200L, rejected.forecastCycleId)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(repository.current.value).isSameInstanceAs(accepted)
    }

    @Test
    fun olderAcceptedCompletionCannotOverwriteNewerAcceptedMarker() = runTest {
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { loaded(7L) },
            persistence = RecordingPersistence(),
            settingsRevision = { 7L },
            now = { 1_000L }
        )
        val older = repository.recompute(SensitivityRuntimeTrigger.FORECAST)
            .copy(forecastCycleId = "cycle-a")
        val newer = older.copy(forecastCycleId = "cycle-z", timestamp = 1_010L)

        assertThat(accept(repository, newer, 1_200L, newer.forecastCycleId)).isTrue()
        assertThat(accept(repository, older, 1_100L, older.forecastCycleId)).isFalse()

        assertThat(repository.current.value).isSameInstanceAs(newer)
    }

    @Test
    fun recomputeIfAbsentReusesOnlyCurrentRevisionCandidate() = runTest {
        var revision = 10L
        var loads = 0
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { loads += 1; loaded(revision) },
            persistence = RecordingPersistence(),
            settingsRevision = { revision },
            now = { 1_000L }
        )

        val first = repository.recomputeIfAbsent(SensitivityRuntimeTrigger.FORECAST)
        val reused = repository.recomputeIfAbsent(SensitivityRuntimeTrigger.FORECAST)
        revision = 11L
        val refreshed = repository.recomputeIfAbsent(SensitivityRuntimeTrigger.FORECAST)

        assertThat(reused).isSameInstanceAs(first)
        assertThat(refreshed.settingsRevision).isEqualTo(11L)
        assertThat(loads).isEqualTo(2)
        assertThat(repository.current.value).isNull()
    }

    @Test
    fun staleCycleCannotOverwriteNewerSettingsRevision() = runTest {
        var revision = 1L
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { loaded(revision) },
            persistence = RecordingPersistence(),
            settingsRevision = { revision++ },
            now = { 1_000L }
        )

        try {
            repository.recompute(SensitivityRuntimeTrigger.SETTINGS_CHANGED)
            error("expected stale cycle rejection")
        } catch (error: IllegalArgumentException) {
            assertThat(error).hasMessageThat().contains("sensitivity settings changed")
        }
        assertThat(repository.current.value).isNull()
    }

    @Test
    fun independentCandidatesKeepDistinctValuesAndProvenance() = runTest {
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = {
                SensitivityRuntimeLoadedCandidates(
                    settingsRevision = 11L,
                    isfPreference = SensitivitySourcePreference.AAPS,
                    crPreference = SensitivitySourcePreference.COPILOT,
                    isfCandidates = SensitivityCandidates(
                        aaps = candidate(2.4),
                        evidence = candidate(4.8),
                        copilot = candidate(3.6, modelRevision = 44L, profileRevision = 55L)
                    ),
                    crCandidates = SensitivityCandidates(
                        aaps = candidate(8.0),
                        evidence = candidate(12.0),
                        copilot = candidate(16.0)
                    )
                )
            },
            persistence = RecordingPersistence(),
            now = { 1_000L }
        )

        val snapshot = repository.recompute(SensitivityRuntimeTrigger.FORECAST)

        assertThat(snapshot.isf.rawAaps).isEqualTo(2.4)
        assertThat(snapshot.isf.rawEvidence).isEqualTo(4.8)
        assertThat(snapshot.isf.rawCopilot).isEqualTo(3.6)
        assertThat(snapshot.isf.effective).isEqualTo(2.4)
        assertThat(snapshot.cr.rawAaps).isEqualTo(8.0)
        assertThat(snapshot.cr.rawEvidence).isEqualTo(12.0)
        assertThat(snapshot.cr.rawCopilot).isEqualTo(16.0)
        assertThat(snapshot.cr.effective).isEqualTo(16.0)
    }

    @Test
    fun evidenceCandidateRetainsItsBaseModelRevisionUntilAcceptedCommit() = runTest {
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = {
                SensitivityRuntimeLoadedCandidates(
                    settingsRevision = 12L,
                    isfPreference = SensitivitySourcePreference.EVIDENCE,
                    crPreference = SensitivitySourcePreference.COPILOT,
                    isfCandidates = SensitivityCandidates(
                        aaps = candidate(2.4),
                        evidence = candidate(2.1, modelRevision = 44L, profileRevision = 55L),
                        copilot = candidate(3.6, modelRevision = 44L, profileRevision = 55L)
                    ),
                    crCandidates = SensitivityCandidates(
                        aaps = candidate(9.0),
                        evidence = candidate(12.0),
                        copilot = candidate(10.0, modelRevision = 44L, profileRevision = 55L)
                    )
                )
            },
            persistence = RecordingPersistence(),
            now = { 1_000L }
        )

        val snapshot = repository.recompute(SensitivityRuntimeTrigger.FORECAST)

        assertThat(repository.isfCrInputGenerationForCandidate(snapshot))
            .isEqualTo(IsfCrInputGeneration(modelRevision = 44L, profileRevision = 55L))
    }

    @Test
    fun evidenceCrFallbackRetainsSelectedAapsGenerationUntilAcceptedCommit() = runTest {
        val acceptedGeneration = IsfCrInputGeneration(modelRevision = 71L, profileRevision = 81L)
        val rejectedNativeGeneration = IsfCrInputGeneration(modelRevision = 72L, profileRevision = 82L)
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = {
                SensitivityRuntimeLoadedCandidates(
                    settingsRevision = 15L,
                    isfPreference = SensitivitySourcePreference.COPILOT,
                    crPreference = SensitivitySourcePreference.EVIDENCE,
                    isfCandidates = SensitivityCandidates(
                        aaps = candidate(2.4),
                        evidence = candidate(2.1),
                        copilot = candidate(
                            3.6,
                            acceptedGeneration.modelRevision,
                            acceptedGeneration.profileRevision
                        )
                    ),
                    crCandidates = SensitivityCandidates(
                        aaps = candidate(
                            10.0,
                            acceptedGeneration.modelRevision,
                            acceptedGeneration.profileRevision
                        ),
                        evidence = candidate(
                            8.59339,
                            acceptedGeneration.modelRevision,
                            acceptedGeneration.profileRevision
                        ).copy(qualityPassed = false),
                        copilot = candidate(
                            22.5416,
                            rejectedNativeGeneration.modelRevision,
                            rejectedNativeGeneration.profileRevision
                        )
                    )
                )
            },
            persistence = RecordingPersistence(),
            now = { 1_000L }
        )

        val snapshot = repository.recompute(SensitivityRuntimeTrigger.FORECAST)

        assertThat(snapshot.cr.resolved.name).isEqualTo("AAPS")
        assertThat(repository.isfCrInputGenerationForCandidate(snapshot)).isEqualTo(acceptedGeneration)
    }

    @Test
    fun copilotNativeCandidateRetainsItsProfileGenerationUntilAcceptedCommit() = runTest {
        val generation = IsfCrInputGeneration(modelRevision = 66L, profileRevision = 77L)
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = {
                SensitivityRuntimeLoadedCandidates(
                    settingsRevision = 13L,
                    isfPreference = SensitivitySourcePreference.COPILOT,
                    crPreference = SensitivitySourcePreference.COPILOT,
                    isfCandidates = SensitivityCandidates(
                        aaps = candidate(2.4),
                        evidence = candidate(2.1),
                        copilot = candidate(3.6, generation.modelRevision, generation.profileRevision)
                    ),
                    crCandidates = SensitivityCandidates(
                        aaps = candidate(9.0),
                        evidence = candidate(9.5),
                        copilot = candidate(10.0, generation.modelRevision, generation.profileRevision)
                    )
                )
            },
            persistence = RecordingPersistence(),
            now = { 1_000L }
        )

        val snapshot = repository.recompute(SensitivityRuntimeTrigger.FORECAST)

        assertThat(repository.isfCrInputGenerationForCandidate(snapshot)).isEqualTo(generation)
    }

    @Test
    fun evidenceBlendRejectsDifferentEvidenceAndNativeGenerations() = runTest {
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = {
                SensitivityRuntimeLoadedCandidates(
                    settingsRevision = 14L,
                    isfPreference = SensitivitySourcePreference.EVIDENCE,
                    crPreference = SensitivitySourcePreference.COPILOT,
                    isfCandidates = SensitivityCandidates(
                        aaps = candidate(2.4),
                        evidence = candidate(2.1, modelRevision = 44L, profileRevision = 55L),
                        copilot = candidate(3.6, modelRevision = 44L, profileRevision = 56L)
                    ),
                    crCandidates = SensitivityCandidates(
                        aaps = candidate(9.0),
                        evidence = candidate(12.0),
                        copilot = candidate(10.0, modelRevision = 44L, profileRevision = 55L)
                    )
                )
            },
            persistence = RecordingPersistence(),
            now = { 1_000L }
        )
        val snapshot = repository.recompute(SensitivityRuntimeTrigger.FORECAST)

        val failure = runCatching {
            repository.isfCrInputGenerationForCandidate(snapshot)
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(failure).hasMessageThat().contains("different ISF/CR input generations")
    }

    @Test
    fun persistenceFailureDoesNotPublishHalfSnapshot() = runTest {
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { loaded(1L) },
            persistence = object : SensitivityRuntimePersistence {
                override suspend fun save(entity: io.aaps.copilot.data.local.entity.SensitivityRuntimeSnapshotEntity) {
                    error("disk failure")
                }
            },
            now = { 1_000L }
        )

        try {
            repository.recompute(SensitivityRuntimeTrigger.FORECAST)
            error("expected persistence failure")
        } catch (error: IllegalStateException) {
            assertThat(error).hasMessageThat().isEqualTo("disk failure")
        }
        assertThat(repository.current.value).isNull()
    }

    @Test
    fun settingsMutationAfterCandidateLoadRejectsBeforePublish() = runTest {
        var revision = 3L
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = {
                val loaded = loaded(revision)
                revision = 4L
                loaded
            },
            persistence = RecordingPersistence(),
            settingsRevision = { revision },
            now = { 1_000L }
        )

        try {
            repository.recompute(SensitivityRuntimeTrigger.SETTINGS_CHANGED)
            error("expected stale cycle rejection")
        } catch (error: IllegalArgumentException) {
            assertThat(error).hasMessageThat().contains("sensitivity settings changed")
        }
        assertThat(repository.current.value).isNull()
    }

    @Test
    fun settingsMutationDuringPersistenceRejectsBeforePublication() = runTest {
        var revision = 8L
        val persistence = RecordingPersistence(onSave = { revision = 9L })
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { loaded(revision) },
            persistence = persistence,
            settingsRevision = { revision },
            now = { 1_000L }
        )

        val failure = runCatching {
            repository.recompute(SensitivityRuntimeTrigger.SETTINGS_CHANGED)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(repository.current.value).isNull()
        assertThat(persistence.saved?.settingsRevision).isEqualTo(8L)
    }

    @Test
    fun startupHydrationPublishesAcceptedSnapshotWithoutCandidateLoadOrPersistence() = runTest {
        val accepted = testSensitivityRuntimeSnapshot(settingsRevision = 2L, cycleId = "accepted-cycle")
        var candidateLoads = 0
        var persistenceWrites = 0
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = {
                candidateLoads += 1
                loaded(2L)
            },
            persistence = SensitivityRuntimePersistence { persistenceWrites += 1 },
            settingsRevision = { 2L },
            acceptedSnapshotLoader = { revision, atTs ->
                AcceptedSensitivityRuntimePublication(
                    snapshot = accepted,
                    acceptedAtTs = accepted.timestamp + 1L,
                    acceptedCycleId = accepted.forecastCycleId
                ).takeIf { revision == 2L && atTs == accepted.timestamp + 2L }
            },
            now = { accepted.timestamp + 2L }
        )

        val hydrated = repository.hydrateFromAcceptedTuple()

        assertThat(hydrated).isSameInstanceAs(accepted)
        assertThat(repository.current.value).isSameInstanceAs(accepted)
        assertThat(repository.acceptedCandidateDiagnostics.value).isNull()
        assertThat(candidateLoads).isEqualTo(0)
        assertThat(persistenceWrites).isEqualTo(0)
    }

    @Test
    fun productionFanOutCarriesTheExactSnapshotInstanceForEveryConsumer() = runTest {
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { loaded(14L) },
            persistence = RecordingPersistence(),
            now = { 1_000L }
        )
        val snapshot = repository.recompute(SensitivityRuntimeTrigger.FORECAST)

        val fanOut = SensitivityRuntimeFanOut(snapshot)
        val references = SensitivityRuntimeConsumer.entries.map(fanOut::contextFor)

        assertThat(references.map { it.snapshot.forecastCycleId }.toSet())
            .containsExactly(snapshot.forecastCycleId)
        assertThat(references.map { it.snapshot.settingsRevision }.toSet())
            .containsExactly(snapshot.settingsRevision)
        assertThat(references.all { it.snapshot === snapshot }).isTrue()
        assertThat(references.map { it.snapshot.isf.effective }.toSet()).containsExactly(3.0)
        assertThat(references.map { it.snapshot.cr.effective }.toSet()).containsExactly(10.0)
    }

    @Test
    fun startupHydrationRejectsSnapshotWhenRevisionChangesAcrossRead() = runTest {
        var revision = 22L
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { loaded(revision) },
            persistence = RecordingPersistence(),
            settingsRevision = { revision },
            acceptedSnapshotLoader = { _, _ ->
                revision = 23L
                val snapshot = testSensitivityRuntimeSnapshot(
                    settingsRevision = 22L,
                    cycleId = "stale-cycle"
                )
                AcceptedSensitivityRuntimePublication(
                    snapshot = snapshot,
                    acceptedAtTs = snapshot.timestamp + 1L,
                    acceptedCycleId = snapshot.forecastCycleId
                )
            },
            now = { 1_700_000_000_002L }
        )

        val hydrated = repository.hydrateFromAcceptedTuple()

        assertThat(hydrated).isNull()
        assertThat(repository.current.value).isNull()
    }

    @Test
    fun hydrationAlwaysReloadsValidatedMarkerInsteadOfUsingSameRevisionCurrentFastPath() = runTest {
        val first = testSensitivityRuntimeSnapshot(settingsRevision = 30L, cycleId = "cycle-a")
        val second = first.copy(forecastCycleId = "cycle-b", timestamp = first.timestamp + 1L)
        var selected = first
        var loads = 0
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { error("hydration must not load candidates") },
            persistence = SensitivityRuntimePersistence { error("hydration must not persist") },
            settingsRevision = { 30L },
            acceptedSnapshotLoader = { _, _ ->
                loads += 1
                AcceptedSensitivityRuntimePublication(
                    snapshot = selected,
                    acceptedAtTs = selected.timestamp + 1L,
                    acceptedCycleId = selected.forecastCycleId
                )
            },
            now = { first.timestamp + 10L }
        )

        assertThat(repository.hydrateFromAcceptedTuple()).isSameInstanceAs(first)
        selected = second
        assertThat(repository.hydrateFromAcceptedTuple()).isSameInstanceAs(second)

        assertThat(loads).isEqualTo(2)
        assertThat(repository.current.value).isSameInstanceAs(second)
    }

    @Test
    fun hydrationFailsClosedWithoutStealingAnActiveAcceptanceReservation() = runTest {
        val candidate = testSensitivityRuntimeSnapshot(settingsRevision = 31L, cycleId = "candidate-cycle")
        val persisted = candidate.copy(forecastCycleId = "persisted-cycle", timestamp = candidate.timestamp - 1L)
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { error("hydration must not load candidates") },
            persistence = SensitivityRuntimePersistence { error("hydration must not persist") },
            settingsRevision = { 31L },
            acceptedSnapshotLoader = { _, _ ->
                AcceptedSensitivityRuntimePublication(
                    snapshot = persisted,
                    acceptedAtTs = persisted.timestamp + 1L,
                    acceptedCycleId = persisted.forecastCycleId
                )
            },
            now = { candidate.timestamp + 2L }
        )
        val reservation = checkNotNull(
            repository.reserveAccepted(candidate, candidate.timestamp + 1L, candidate.forecastCycleId)
        )

        val hydrated = repository.hydrateFromAcceptedTuple()

        assertThat(hydrated).isNull()
        assertThat(repository.current.value).isNull()
        repository.finalizeAccepted(reservation)
        assertThat(repository.current.value).isSameInstanceAs(candidate)
    }

    @Test
    fun activeProductionReservationMakesMatchingCurrentHydrationFailClosed() = runTest {
        val persisted = testSensitivityRuntimeSnapshot(settingsRevision = 32L, cycleId = "persisted-cycle")
        val production = persisted.copy(
            forecastCycleId = "production-cycle",
            timestamp = persisted.timestamp + 1L
        )
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { error("hydration must not load candidates") },
            persistence = SensitivityRuntimePersistence { error("hydration must not persist") },
            settingsRevision = { 32L },
            acceptedSnapshotLoader = { _, _ ->
                AcceptedSensitivityRuntimePublication(
                    snapshot = persisted,
                    acceptedAtTs = persisted.timestamp + 1L,
                    acceptedCycleId = persisted.forecastCycleId
                )
            },
            now = { production.timestamp + 2L }
        )
        assertThat(accept(repository, persisted, persisted.timestamp + 1L, persisted.forecastCycleId))
            .isTrue()
        val productionReservation = checkNotNull(
            repository.reserveAccepted(
                production,
                production.timestamp + 1L,
                production.forecastCycleId
            )
        )

        assertThat(repository.hydrateFromAcceptedTuple()).isNull()
        assertThat(repository.current.value).isSameInstanceAs(persisted)

        repository.finalizeAccepted(productionReservation)
        assertThat(repository.current.value).isSameInstanceAs(production)
    }

    @Test
    fun productionReservationDuringHydrationReadRetainsOwnership() = runTest {
        val persisted = testSensitivityRuntimeSnapshot(settingsRevision = 33L, cycleId = "persisted-cycle")
        val production = persisted.copy(
            forecastCycleId = "production-cycle",
            timestamp = persisted.timestamp + 1L
        )
        val loaderStarted = CompletableDeferred<Unit>()
        val releaseLoader = CompletableDeferred<Unit>()
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { error("hydration must not load candidates") },
            persistence = SensitivityRuntimePersistence { error("hydration must not persist") },
            settingsRevision = { 33L },
            acceptedSnapshotLoader = { _, _ ->
                loaderStarted.complete(Unit)
                releaseLoader.await()
                AcceptedSensitivityRuntimePublication(
                    snapshot = persisted,
                    acceptedAtTs = persisted.timestamp + 1L,
                    acceptedCycleId = persisted.forecastCycleId
                )
            },
            now = { production.timestamp + 2L }
        )

        val hydration = async { repository.hydrateFromAcceptedTuple() }
        loaderStarted.await()
        val productionReservation = checkNotNull(
            repository.reserveAccepted(
                production,
                production.timestamp + 1L,
                production.forecastCycleId
            )
        )
        releaseLoader.complete(Unit)

        assertThat(hydration.await()).isNull()
        assertThat(repository.current.value).isNull()
        repository.finalizeAccepted(productionReservation)
        assertThat(repository.current.value).isSameInstanceAs(production)
    }

    @Test
    fun atomicHydrationFinalizationLeavesNextNewerProductionReserveAvailable() = runTest {
        val persisted = testSensitivityRuntimeSnapshot(settingsRevision = 34L, cycleId = "persisted-cycle")
        val production = persisted.copy(
            forecastCycleId = "production-cycle",
            timestamp = persisted.timestamp + 1L
        )
        val repository = SensitivityRuntimeRepository(
            loadedCandidates = { error("hydration must not load candidates") },
            persistence = SensitivityRuntimePersistence { error("hydration must not persist") },
            settingsRevision = { 34L },
            acceptedSnapshotLoader = { _, _ ->
                AcceptedSensitivityRuntimePublication(
                    snapshot = persisted,
                    acceptedAtTs = persisted.timestamp + 1L,
                    acceptedCycleId = persisted.forecastCycleId
                )
            },
            now = { production.timestamp + 2L }
        )

        assertThat(repository.hydrateFromAcceptedTuple()).isSameInstanceAs(persisted)
        val productionReservation = repository.reserveAccepted(
            production,
            production.timestamp + 1L,
            production.forecastCycleId
        )

        assertThat(productionReservation).isNotNull()
        repository.finalizeAccepted(checkNotNull(productionReservation))
        assertThat(repository.current.value).isSameInstanceAs(production)
    }

    private fun loaded(revision: Long) = SensitivityRuntimeLoadedCandidates(
        settingsRevision = revision,
        isfPreference = SensitivitySourcePreference.COPILOT,
        crPreference = SensitivitySourcePreference.COPILOT,
        isfCandidates = candidates(3.0, 4.0),
        crCandidates = candidates(10.0, 12.0)
    )

    private suspend fun accept(
        repository: SensitivityRuntimeRepository,
        snapshot: io.aaps.copilot.domain.predict.SensitivityRuntimeSnapshot,
        acceptedAtTs: Long,
        acceptedCycleId: String
    ): Boolean {
        val reservation = repository.reserveAccepted(snapshot, acceptedAtTs, acceptedCycleId)
            ?: return false
        repository.finalizeAccepted(reservation)
        return true
    }

    private fun candidates(copilot: Double, evidence: Double) = SensitivityCandidates(
        aaps = candidate(null),
        evidence = candidate(evidence),
        copilot = candidate(copilot)
    )

    private fun candidate(
        value: Double?,
        modelRevision: Long? = null,
        profileRevision: Long? = null
    ) = SensitivityCandidate(
        value = value,
        timestamp = 1_000L,
        confidence = 1.0,
        qualityPassed = true,
        sampleCount = 1,
        coverage = 1.0,
        unavailableReason = null,
        modelRevision = modelRevision,
        profileRevision = profileRevision
    )

    private class RecordingPersistence(
        private val onSave: suspend () -> Unit = {}
    ) : SensitivityRuntimePersistence {
        var saved: io.aaps.copilot.data.local.entity.SensitivityRuntimeSnapshotEntity? = null
        override suspend fun save(entity: io.aaps.copilot.data.local.entity.SensitivityRuntimeSnapshotEntity) {
            saved = entity
            onSave()
        }
    }
}
