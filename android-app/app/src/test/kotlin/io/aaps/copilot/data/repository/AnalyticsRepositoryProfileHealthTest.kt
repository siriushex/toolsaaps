package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.ProfileEstimateEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AnalyticsRepositoryProfileHealthTest {

    @Test
    fun revisionFailureNeverDefersInputRecovery() {
        assertThat(AnalyticsRepository.shouldDeferProfileRevisionRetryStatic()).isFalse()
    }

    @Test
    fun profileStateMutationCoordinatorSerializesPublishers() = runTest {
        val coordinator = ProfileStateMutationCoordinator()
        val ownerEntered = CompletableDeferred<Unit>()
        val releaseOwner = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runExclusive {
                order += "revision"
                ownerEntered.complete(Unit)
                releaseOwner.await()
            }
        }
        ownerEntered.await()
        val recalculation = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runExclusive {
                order += "recalculate"
            }
        }

        assertThat(order).containsExactly("revision").inOrder()
        assertThat(recalculation.isCompleted).isFalse()

        releaseOwner.complete(Unit)
        owner.await()
        recalculation.await()
        assertThat(order).containsExactly("revision", "recalculate").inOrder()
    }

    @Test
    fun profilePublicationRevisionIsStrictlyMonotonicAtTheSameClockMillisecond() {
        assertThat(
            nextStrictlyMonotonicPublicationRevision(
                requestedRevision = 1_000L,
                currentRevisions = listOf(999L, 1_000L)
            )
        ).isEqualTo(1_001L)
        assertThat(
            nextStrictlyMonotonicPublicationRevision(
                requestedRevision = 900L,
                currentRevisions = listOf(1_000L)
            )
        ).isEqualTo(1_001L)
        assertThat(
            nextStrictlyMonotonicPublicationRevision(
                requestedRevision = 1_100L,
                currentRevisions = listOf(1_000L)
            )
        ).isEqualTo(1_100L)
    }

    @Test
    fun publicationRevisionFailsClosedAtLongOverflow() {
        val failure = runCatching {
            nextStrictlyMonotonicPublicationRevision(
                requestedRevision = Long.MAX_VALUE,
                currentRevisions = listOf(Long.MAX_VALUE)
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ArithmeticException::class.java)
    }

    @Test
    fun recalculationProfilePublicationWaitsForRevisionMarker() {
        assertThat(AnalyticsRepository.profileRecalculationPublicationAllowedStatic(null)).isFalse()
        assertThat(AnalyticsRepository.profileRecalculationPublicationAllowedStatic(1L)).isFalse()
        assertThat(
            AnalyticsRepository.profileRecalculationPublicationAllowedStatic(
                AnalyticsRepository.PROFILE_ESTIMATOR_ALGORITHM_REVISION
            )
        ).isTrue()
    }

    @Test
    fun segmentOnlyDerivedStateStillRequiresEstimatorRevision() {
        assertThat(
            AnalyticsRepository.hasLegacyProfileDerivedStateStatic(
                hasProfile = false,
                hasModel = false,
                hasRealtimeSnapshot = false,
                segmentCount = 1
            )
        ).isTrue()
        assertThat(
            AnalyticsRepository.hasLegacyProfileDerivedStateStatic(
                hasProfile = false,
                hasModel = false,
                hasRealtimeSnapshot = false,
                segmentCount = 0
            )
        ).isFalse()
    }

    @Test
    fun rebuildRequestedWhenActiveProfileMissing() {
        val reason = AnalyticsRepository.determineProfileStateRebuildReason(
            active = null,
            segmentCount = 0,
            latestSegmentUpdatedAt = null,
            nowTs = NOW_TS
        )

        assertThat(reason).isEqualTo("missing_active_profile")
    }

    @Test
    fun rebuildRequestedWhenTelemetryPollutionDetected() {
        val reason = AnalyticsRepository.determineProfileStateRebuildReason(
            active = activeProfile(telemetryIsfSamples = 12),
            segmentCount = 4,
            latestSegmentUpdatedAt = NOW_TS,
            nowTs = NOW_TS
        )

        assertThat(reason).isEqualTo("legacy_telemetry_pollution")
    }

    @Test
    fun rebuildRequestedWhenSegmentsAreStale() {
        val reason = AnalyticsRepository.determineProfileStateRebuildReason(
            active = activeProfile(timestamp = NOW_TS - 30 * 60_000L),
            segmentCount = 8,
            latestSegmentUpdatedAt = NOW_TS - 13 * 60 * 60_000L,
            nowTs = NOW_TS
        )

        assertThat(reason).isEqualTo("segments_older_than_active_profile")
    }

    @Test
    fun noRebuildWhenActiveProfileAndSegmentsAreFresh() {
        val reason = AnalyticsRepository.determineProfileStateRebuildReason(
            active = activeProfile(timestamp = NOW_TS - 60 * 60_000L),
            segmentCount = 8,
            latestSegmentUpdatedAt = NOW_TS - 30 * 60_000L,
            nowTs = NOW_TS,
            storedEstimatorRevision = AnalyticsRepository.PROFILE_ESTIMATOR_ALGORITHM_REVISION
        )

        assertThat(reason).isNull()
    }

    @Test
    fun rebuildRequestedWhenEstimatorRevisionWasNotApplied() {
        val reason = AnalyticsRepository.determineProfileStateRebuildReason(
            active = activeProfile(timestamp = NOW_TS - 60 * 60_000L),
            segmentCount = 8,
            latestSegmentUpdatedAt = NOW_TS - 30 * 60_000L,
            nowTs = NOW_TS,
            storedEstimatorRevision = null
        )

        assertThat(reason).isEqualTo("profile_estimator_revision_changed")
    }

    @Test
    fun circadianRebuildRequestedWhenReplayStatsMissing() {
        val reason = AnalyticsRepository.determineCircadianStateRebuildReason(
            slotCount = 96,
            transitionCount = 288,
            snapshotCount = 3,
            replayCount = 0,
            qualifiedReplayCount = 0,
            pollutedReplayZeroQualityCount = 0,
            latestSnapshotUpdatedAt = NOW_TS,
            latestReplayUpdatedAt = null,
            nowTs = NOW_TS
        )

        assertThat(reason).isEqualTo("missing_replay_slot_stats")
    }

    @Test
    fun circadianRebuildRequestedWhenSnapshotsAreStale() {
        val reason = AnalyticsRepository.determineCircadianStateRebuildReason(
            slotCount = 96,
            transitionCount = 288,
            snapshotCount = 3,
            replayCount = 288,
            qualifiedReplayCount = 288,
            pollutedReplayZeroQualityCount = 0,
            latestSnapshotUpdatedAt = NOW_TS - 13 * 60 * 60_000L,
            latestReplayUpdatedAt = NOW_TS - 30 * 60_000L,
            nowTs = NOW_TS
        )

        assertThat(reason).isEqualTo("stale_pattern_snapshots")
    }

    @Test
    fun circadianNoRebuildWhenAllArtifactsFresh() {
        val reason = AnalyticsRepository.determineCircadianStateRebuildReason(
            slotCount = 96,
            transitionCount = 288,
            snapshotCount = 3,
            replayCount = 288,
            qualifiedReplayCount = 288,
            pollutedReplayZeroQualityCount = 0,
            latestSnapshotUpdatedAt = NOW_TS - 30 * 60_000L,
            latestReplayUpdatedAt = NOW_TS - 15 * 60_000L,
            nowTs = NOW_TS
        )

        assertThat(reason).isNull()
    }

    @Test
    fun circadianRebuildRequestedWhenReplayStatsShowLegacyZeroQualityPollution() {
        val reason = AnalyticsRepository.determineCircadianStateRebuildReason(
            slotCount = 96,
            transitionCount = 288,
            snapshotCount = 3,
            replayCount = 288,
            qualifiedReplayCount = 96,
            pollutedReplayZeroQualityCount = 12,
            latestSnapshotUpdatedAt = NOW_TS - 30 * 60_000L,
            latestReplayUpdatedAt = NOW_TS - 15 * 60_000L,
            nowTs = NOW_TS
        )

        assertThat(reason).isEqualTo("polluted_replay_zero_quality")
    }

    private fun activeProfile(
        timestamp: Long = NOW_TS,
        telemetryIsfSamples: Int = 1,
        telemetryCrSamples: Int = 1
    ) = ProfileEstimateEntity(
        id = "active",
        timestamp = timestamp,
        isfMmolPerUnit = 3.1,
        crGramPerUnit = 10.0,
        confidence = 0.35,
        sampleCount = 12,
        isfSampleCount = 3,
        crSampleCount = 9,
        lookbackDays = 365,
        telemetryIsfSampleCount = telemetryIsfSamples,
        telemetryCrSampleCount = telemetryCrSamples,
        uamObservedCount = 0,
        uamFilteredIsfSamples = 0,
        uamEpisodeCount = 0,
        uamEstimatedCarbsGrams = 0.0,
        uamEstimatedRecentCarbsGrams = 0.0,
        calculatedIsfMmolPerUnit = 2.7,
        calculatedCrGramPerUnit = 15.5,
        calculatedConfidence = 0.62,
        calculatedSampleCount = 9,
        calculatedIsfSampleCount = 3,
        calculatedCrSampleCount = 6
    )

    private companion object {
        private const val NOW_TS = 1_762_904_400_000L
    }
}
