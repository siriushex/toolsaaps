package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.dao.AuditLogDao
import io.aaps.copilot.data.local.entity.AuditLogEntity
import io.aaps.copilot.domain.predict.UamExportMode
import io.aaps.copilot.domain.predict.UamInferenceEvent
import io.aaps.copilot.domain.predict.UamInferenceState
import io.aaps.copilot.domain.predict.UamMode
import io.aaps.copilot.domain.predict.UamTagCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class UamExportCoordinatorTest {

    @Test
    fun confirmedOnlyLegacyProcessReconcilesButCannotPost() = runBlocking {
        val remoteEntry = remoteEntry(episodeId = "legacy", minutesAgo = 20, grams = 18.0, seq = 1)
        val gateway = FakeGateway(fetched = listOf(remoteEntry))
        val event = confirmedEvent(id = "legacy", nowTs = NOW_TS, carbsDisplay = 25.0)

        val outcome = UamExportCoordinator(gateway).process(
            nowTs = NOW_TS,
            events = listOf(event),
            config = defaultConfig(exportMode = UamExportMode.CONFIRMED_ONLY)
        )

        assertThat(gateway.posts).isEmpty()
        assertThat(outcome.remoteEntries).containsExactly(remoteEntry)
        assertThat(outcome.events.single().exportedGrams).isEqualTo(18.0)
        assertThat(outcome.events.single().exportSeq).isEqualTo(1)
    }

    @Test
    fun incrementalLegacyProcessIsAlsoReadOnly() = runBlocking {
        val gateway = FakeGateway()

        UamExportCoordinator(gateway).process(
            nowTs = NOW_TS,
            events = listOf(confirmedEvent(id = "legacy-incremental", nowTs = NOW_TS, carbsDisplay = 30.0)),
            config = defaultConfig(exportMode = UamExportMode.INCREMENTAL)
        )

        assertThat(gateway.posts).isEmpty()
    }

    @Test
    fun legacyProcessDeduplicatesRemoteIdAndSequence() = runBlocking {
        val remoteEntry = remoteEntry(episodeId = "evt-1", minutesAgo = 20, grams = 18.0, seq = 1)
        val gateway = FakeGateway(fetched = listOf(remoteEntry, remoteEntry))
        val event = confirmedEvent(id = "evt-1", nowTs = NOW_TS, carbsDisplay = 25.0)

        val outcome = UamExportCoordinator(gateway).process(
            nowTs = NOW_TS,
            events = listOf(event),
            config = defaultConfig(exportMode = UamExportMode.CONFIRMED_ONLY)
        )

        assertThat(gateway.posts).isEmpty()
        assertThat(outcome.events.single().exportSeq).isEqualTo(1)
        assertThat(outcome.events.single().exportedGrams).isEqualTo(18.0)
    }

    @Test
    fun unifiedIgnoresCallerForgedLedgerWhenRemoteIsEmpty() = runBlocking {
        val gateway = FakeGateway()
        val forged = listOf(UamExportLedgerEntry(tsMs = NOW_TS - 10 * MINUTE_MS, grams = 45.0, seq = 9))

        val outcome = coordinator(gateway).processUnified(
            candidate = defaultCandidate(remoteLedger = forged),
            enabled = true,
            dryRun = false
        )

        assertSend(outcome, grams = 15.0, seq = 1)
        assertThat(outcome.reconciliationValid).isTrue()
        assertThat(outcome.reconciledGrams).isEqualTo(0.0)
        assertThat(outcome.reconciledSeq).isEqualTo(0)
        assertThat(gateway.posts).hasSize(1)
    }

    @Test
    fun unifiedConvertsMatchingRemoteTagsToLedgerBeforePolicy() = runBlocking {
        val matching = remoteEntry(episodeId = EPISODE_ID, minutesAgo = 10, grams = 15.0, seq = 1, version = 2)
        val gateway = FakeGateway(fetched = listOf(matching))

        val outcome = coordinator(gateway).processUnified(
            candidate = defaultCandidate(supportedLowerBoundGrams = 30.0),
            enabled = true,
            dryRun = false
        )

        assertSend(outcome, grams = 15.0, seq = 2)
        assertThat(outcome.reconciliationValid).isTrue()
        assertThat(outcome.reconciledGrams).isEqualTo(15.0)
        assertThat(outcome.reconciledSeq).isEqualTo(1)
        assertThat(outcome.remoteEntries).containsExactly(matching)
        assertThat(gateway.posts).hasSize(1)
    }

    @Test
    fun unifiedFetchFailureReturnsReconciliationFailureAndNeverPosts() = runBlocking {
        val auditDao = FakeAuditLogDao()
        val gateway = FakeGateway(fetchError = IllegalStateException("fetch failed"))
        val coordinator = coordinator(gateway, auditLogger(auditDao))

        val outcome = coordinator.processUnified(defaultCandidate(), enabled = true, dryRun = false)

        assertThat(gateway.fetchCalls).isEqualTo(1)
        assertThat(gateway.posts).isEmpty()
        assertThat(outcome.decision).isEqualTo(UamExportDecision.Block("reconciliation_failed"))
        assertThat(outcome.delivered).isFalse()
        assertThat(outcome.reason).isEqualTo("reconciliation_failed")
        assertThat(outcome.reconciliationValid).isFalse()
        assertThat(outcome.reconciledGrams).isNull()
        assertThat(outcome.reconciledSeq).isNull()
        assertThat(auditDao.rows.map { it.message }).containsExactly("uam_export_reconciliation_failed")
        Unit
    }

    @Test
    fun eligibleFirstUnifiedWritePostsPolicyAmountTimestampAndV2Tag() = runBlocking {
        val gateway = FakeGateway()

        val reservationStore = FakeReservationStore()
        val outcome = coordinator(gateway, reservationStore = reservationStore).processUnified(
            candidate = defaultCandidate(),
            enabled = true,
            dryRun = false
        )

        val expectedTs = (NOW_TS / FIVE_MINUTE_MS) * FIVE_MINUTE_MS
        assertSend(outcome, grams = 15.0, seq = 1)
        assertThat(outcome.delivered).isTrue()
        assertThat(outcome.reason).isEqualTo("delivered")
        assertThat(outcome.remoteId).isEqualTo("remote-1")
        assertThat(outcome.reserved).isTrue()
        assertThat(reservationStore.sent.values).containsExactly("remote-1")
        assertThat(gateway.posts).containsExactly(
            PostCall(
                tsMs = expectedTs,
                grams = 15.0,
                note = UamTagCodec.buildTag(EPISODE_ID, 1, UamMode.NORMAL, version = 2)
            )
        )
        val parsed = UamTagCodec.parseUamTag(gateway.posts.single().note)
        assertThat(parsed?.id).isEqualTo(EPISODE_ID)
        assertThat(parsed?.seq).isEqualTo(1)
        assertThat(parsed?.ver).isEqualTo(2)
    }

    @Test
    fun episodeSequenceAllowsFinalQuantumAtFiftyAndBlocksTheNextWrite() = runBlocking {
        val pointOneEpisode = "point-one"
        val pointOneGateway = FakeGateway(
            fetched = listOf(remoteEntry(pointOneEpisode, minutesAgo = 31, grams = 49.81, seq = 1))
        )
        val pointOneOutcome = coordinator(pointOneGateway).processUnified(
            candidate = defaultCandidate(episodeId = pointOneEpisode, supportedLowerBoundGrams = 80.0),
            enabled = true,
            dryRun = false
        )

        assertSend(pointOneOutcome, grams = 0.1, seq = 2)
        assertThat(pointOneGateway.posts).hasSize(1)

        val finalFiveEpisode = "final-five"
        val finalFiveGateway = FakeGateway(
            fetched = listOf(
                remoteEntry(finalFiveEpisode, minutesAgo = 50, grams = 15.0, seq = 1),
                remoteEntry(finalFiveEpisode, minutesAgo = 35, grams = 15.0, seq = 2),
                remoteEntry(finalFiveEpisode, minutesAgo = 10, grams = 15.0, seq = 3)
            )
        )
        val finalFiveOutcome = coordinator(finalFiveGateway).processUnified(
            candidate = defaultCandidate(episodeId = finalFiveEpisode, supportedLowerBoundGrams = 80.0),
            enabled = true,
            dryRun = false
        )

        assertSend(finalFiveOutcome, grams = 5.0, seq = 4)
        assertThat(finalFiveGateway.posts).hasSize(1)

        val fullEpisode = "full-episode"
        val fullGateway = FakeGateway(
            fetched = listOf(
                remoteEntry(fullEpisode, minutesAgo = 50, grams = 15.0, seq = 1),
                remoteEntry(fullEpisode, minutesAgo = 40, grams = 15.0, seq = 2),
                remoteEntry(fullEpisode, minutesAgo = 20, grams = 15.0, seq = 3),
                remoteEntry(fullEpisode, minutesAgo = 10, grams = 5.0, seq = 4)
            )
        )
        val fullOutcome = coordinator(fullGateway).processUnified(
            candidate = defaultCandidate(episodeId = fullEpisode, supportedLowerBoundGrams = 50.1),
            enabled = true,
            dryRun = false
        )

        assertBlocked(fullOutcome, "episode_capacity_exhausted")
        assertThat(fullGateway.posts).isEmpty()
    }

    @Test
    fun coordinatorEnforcesTenMinuteAndThirtyMinuteBoundariesWithUniqueTags() = runBlocking {
        val underTenGateway = FakeGateway(
            fetched = listOf(
                remoteEntry(
                    EPISODE_ID,
                    tsMs = NOW_TS - 10 * MINUTE_MS + 1L,
                    grams = 15.0,
                    seq = 1,
                    version = 2
                )
            )
        )
        val underTen = coordinator(underTenGateway).processUnified(
            candidate = defaultCandidate(supportedLowerBoundGrams = 45.0),
            enabled = true,
            dryRun = false
        )
        assertBlocked(underTen, "write_interval_under_10m")
        assertThat(underTenGateway.posts).isEmpty()

        val exactTenRemote = remoteEntry(
            EPISODE_ID,
            minutesAgo = 10,
            grams = 15.0,
            seq = 1,
            version = 2
        )
        val exactTenGateway = FakeGateway(fetched = listOf(exactTenRemote))
        val exactTen = coordinator(exactTenGateway).processUnified(
            candidate = defaultCandidate(supportedLowerBoundGrams = 45.0),
            enabled = true,
            dryRun = false
        )
        assertSend(exactTen, grams = 15.0, seq = 2)
        assertThat(exactTenGateway.posts).hasSize(1)
        assertThat(exactTenGateway.posts.single().note).isNotEqualTo(exactTenRemote.note)

        val fullRollingGateway = FakeGateway(
            fetched = listOf(
                remoteEntry(EPISODE_ID, minutesAgo = 20, grams = 15.0, seq = 1, version = 2),
                remoteEntry(EPISODE_ID, minutesAgo = 10, grams = 15.0, seq = 2, version = 2)
            )
        )
        val fullRolling = coordinator(fullRollingGateway).processUnified(
            candidate = defaultCandidate(supportedLowerBoundGrams = 50.0),
            enabled = true,
            dryRun = false
        )
        assertBlocked(fullRolling, "rolling_30m_capacity_exhausted")
        assertThat(fullRollingGateway.posts).isEmpty()

        val exactThirtyGateway = FakeGateway(
            fetched = listOf(
                remoteEntry(EPISODE_ID, minutesAgo = 30, grams = 15.0, seq = 1, version = 2),
                remoteEntry(EPISODE_ID, minutesAgo = 10, grams = 15.0, seq = 2, version = 2)
            )
        )
        val exactThirty = coordinator(exactThirtyGateway).processUnified(
            candidate = defaultCandidate(supportedLowerBoundGrams = 50.0),
            enabled = true,
            dryRun = false
        )
        assertSend(exactThirty, grams = 15.0, seq = 3)
        assertThat(exactThirtyGateway.posts).hasSize(1)
    }

    @Test
    fun duplicateRemoteSequenceBlocksWithoutCoordinatorDedupe() = runBlocking {
        val gateway = FakeGateway(
            fetched = listOf(
                remoteEntry(EPISODE_ID, minutesAgo = 20, grams = 5.0, seq = 1),
                remoteEntry(EPISODE_ID, minutesAgo = 10, grams = 5.0, seq = 1)
            )
        )

        val outcome = coordinator(gateway).processUnified(
            candidate = defaultCandidate(supportedLowerBoundGrams = 30.0),
            enabled = true,
            dryRun = false
        )

        assertBlocked(outcome, "invalid_ledger")
        assertThat(gateway.posts).isEmpty()
    }

    @Test
    fun dryRunReturnsSameSendDecisionWithoutPosting() = runBlocking {
        val dryGateway = FakeGateway()
        val liveGateway = FakeGateway()

        val dryOutcome = coordinator(dryGateway).processUnified(
            candidate = defaultCandidate(),
            enabled = true,
            dryRun = true
        )
        val liveOutcome = coordinator(liveGateway).processUnified(
            candidate = defaultCandidate(),
            enabled = true,
            dryRun = false
        )

        assertThat(dryOutcome.decision).isEqualTo(liveOutcome.decision)
        assertSend(dryOutcome, grams = 15.0, seq = 1)
        assertThat(dryOutcome.delivered).isFalse()
        assertThat(dryOutcome.reason).isEqualTo("dry_run")
        assertThat(dryGateway.posts).isEmpty()
    }

    @Test
    fun disabledReportsReconciledPolicyDecisionWithoutPosting() = runBlocking {
        val matching = remoteEntry(EPISODE_ID, minutesAgo = 10, grams = 15.0, seq = 1)
        val gateway = FakeGateway(fetched = listOf(matching))

        val outcome = coordinator(gateway).processUnified(
            candidate = defaultCandidate(supportedLowerBoundGrams = 30.0),
            enabled = false,
            dryRun = false
        )

        assertThat(outcome.decision).isEqualTo(
            UamExportDecision.Send(
                grams = 15.0,
                treatmentTs = (NOW_TS / FIVE_MINUTE_MS) * FIVE_MINUTE_MS,
                seq = 2
            )
        )
        assertThat(outcome.delivered).isFalse()
        assertThat(outcome.reason).isEqualTo("disabled")
        assertThat(outcome.reconciledGrams).isEqualTo(15.0)
        assertThat(gateway.posts).isEmpty()
    }

    @Test
    fun policyBlockPreservesReasonAndDoesNotPost() = runBlocking {
        val gateway = FakeGateway()

        val outcome = coordinator(gateway).processUnified(
            candidate = defaultCandidate(confidence = 0.50),
            enabled = true,
            dryRun = false
        )

        assertBlocked(outcome, "confidence_below_initial_min")
        assertThat(gateway.posts).isEmpty()
    }

    @Test
    fun resultFailureMarksPostOutcomeUnknownAndRetainsReservation() = runBlocking {
        val gateway = FakeGateway(postError = IllegalStateException("post failed"))
        val store = FakeReservationStore()

        val outcome = coordinator(gateway, reservationStore = store).processUnified(
            candidate = defaultCandidate(),
            enabled = true,
            dryRun = false
        )

        assertSend(outcome, grams = 15.0, seq = 1)
        assertThat(gateway.posts).hasSize(1)
        assertThat(outcome.delivered).isFalse()
        assertThat(outcome.reason).isEqualTo("post_outcome_unknown")
        assertThat(outcome.postAttempted).isTrue()
        assertThat(outcome.postOutcomeUnknown).isTrue()
        assertThat(outcome.reserved).isTrue()
        assertThat(store.reservedKeys).containsExactly(outcome.reservationKey)
        assertThat(store.pendingUnknown.keys).containsExactly(outcome.reservationKey)
        assertThat(outcome.reconciledGrams).isEqualTo(0.0)
        assertThat(outcome.reconciledSeq).isEqualTo(0)
    }

    @Test
    fun unrelatedAndOtherEpisodeV1TagsAreIgnoredWhileMatchingV1Counts() = runBlocking {
        val matchingV1 = remoteEntry(EPISODE_ID, minutesAgo = 10, grams = 15.0, seq = 1, version = 1)
        val otherV1 = remoteEntry("other-episode", minutesAgo = 10, grams = 45.0, seq = 8, version = 1)
        val unrelated = AapsCarbEntry("manual", NOW_TS - 5 * MINUTE_MS, 90.0, "manual carbs")
        val gateway = FakeGateway(fetched = listOf(otherV1, unrelated, matchingV1))

        val outcome = coordinator(gateway).processUnified(
            candidate = defaultCandidate(supportedLowerBoundGrams = 30.0),
            enabled = true,
            dryRun = false
        )

        assertSend(outcome, grams = 15.0, seq = 2)
        assertThat(outcome.reconciledGrams).isEqualTo(15.0)
        assertThat(outcome.reconciledSeq).isEqualTo(1)
        assertThat(outcome.remoteEntries).containsExactly(otherV1, unrelated, matchingV1).inOrder()
        assertThat(gateway.posts).hasSize(1)
    }

    @Test
    fun malformedTagTargetingCandidateEpisodeFailsClosed() = runBlocking {
        val auditDao = FakeAuditLogDao()
        val malformed = AapsCarbEntry(
            remoteId = "malformed",
            tsMs = NOW_TS - 10 * MINUTE_MS,
            grams = 15.0,
            note = "UAM_ENGINE|id=$EPISODE_ID|seq=not-an-int|ver=2|mode=NORMAL|"
        )
        val gateway = FakeGateway(fetched = listOf(malformed))

        val outcome = coordinator(gateway, auditLogger(auditDao)).processUnified(
            candidate = defaultCandidate(),
            enabled = true,
            dryRun = false
        )

        assertBlocked(outcome, "reconciliation_failed")
        assertThat(outcome.reconciliationValid).isFalse()
        assertThat(outcome.reconciledGrams).isNull()
        assertThat(outcome.reconciledSeq).isNull()
        assertThat(gateway.posts).isEmpty()
        assertThat(auditDao.rows.map { it.message }).containsExactly("uam_export_reconciliation_failed")
        Unit
    }

    @Test
    fun futureAndInvalidMatchingRemoteEntriesFailClosedThroughPolicy() = runBlocking {
        val cases = listOf(
            remoteEntry(EPISODE_ID, tsMs = NOW_TS + 1L, grams = 15.0, seq = 1) to "ledger_entry_in_future",
            remoteEntry(EPISODE_ID, minutesAgo = 10, grams = 0.0, seq = 1) to "invalid_ledger"
        )

        cases.forEach { (remote, expectedReason) ->
            val gateway = FakeGateway(fetched = listOf(remote))
            val outcome = coordinator(gateway).processUnified(
                candidate = defaultCandidate(),
                enabled = true,
                dryRun = false
            )

            assertBlocked(outcome, expectedReason)
            assertThat(gateway.posts).isEmpty()
        }
    }

    @Test
    fun unifiedPostsAtMostOnceWithMultipleRemoteAndUnrelatedEntries() = runBlocking {
        val gateway = FakeGateway(
            fetched = listOf(
                remoteEntry("other-1", minutesAgo = 1, grams = 5.0, seq = 1),
                AapsCarbEntry("manual", NOW_TS - MINUTE_MS, 10.0, null),
                remoteEntry("other-2", minutesAgo = 2, grams = 15.0, seq = 2)
            )
        )

        val outcome = coordinator(gateway).processUnified(
            candidate = defaultCandidate(),
            enabled = true,
            dryRun = false
        )

        assertSend(outcome, grams = 15.0, seq = 1)
        assertThat(gateway.posts).hasSize(1)
    }

    @Test
    fun candidateLedgerIsReplacedRatherThanAppended() = runBlocking {
        val remote = remoteEntry(EPISODE_ID, minutesAgo = 10, grams = 15.0, seq = 1)
        val forged = listOf(UamExportLedgerEntry(NOW_TS - 20 * MINUTE_MS, 45.0, 99))
        val gateway = FakeGateway(fetched = listOf(remote))

        val outcome = coordinator(gateway).processUnified(
            candidate = defaultCandidate(supportedLowerBoundGrams = 40.0, remoteLedger = forged),
            enabled = true,
            dryRun = false
        )

        assertSend(outcome, grams = 15.0, seq = 2)
        assertThat(outcome.reconciledGrams).isEqualTo(15.0)
        assertThat(outcome.reconciledSeq).isEqualTo(1)
        assertThat(gateway.posts).hasSize(1)
    }

    @Test
    fun reconciliationWindowIsSixHoursAndClampedAtEpoch() = runBlocking {
        val gateway = FakeGateway()
        val nowTs = 5 * MINUTE_MS

        coordinator(gateway, wallClockMs = { nowTs }).processUnified(
            candidate = defaultCandidate(nowTs = nowTs, activeSinceTs = 0L),
            enabled = false,
            dryRun = false
        )

        assertThat(gateway.fetchSinceCalls).containsExactly(0L)
        Unit
    }

    @Test
    fun liveSendWithoutReservationStoreFailsClosedButDryRunStillReturnsSend() = runBlocking {
        val liveGateway = FakeGateway()
        val dryGateway = FakeGateway()

        val liveOutcome = UamExportCoordinator(liveGateway, wallClockMs = { NOW_TS }).processUnified(
            candidate = defaultCandidate(),
            enabled = true,
            dryRun = false
        )
        val dryOutcome = UamExportCoordinator(dryGateway, wallClockMs = { NOW_TS }).processUnified(
            candidate = defaultCandidate(),
            enabled = true,
            dryRun = true
        )

        assertSend(liveOutcome, grams = 15.0, seq = 1)
        assertThat(liveOutcome.reason).isEqualTo("reservation_store_unavailable")
        assertThat(liveOutcome.delivered).isFalse()
        assertThat(liveOutcome.reconciliationValid).isTrue()
        assertThat(liveOutcome.reconciledGrams).isEqualTo(0.0)
        assertThat(liveGateway.posts).isEmpty()
        assertSend(dryOutcome, grams = 15.0, seq = 1)
        assertThat(dryOutcome.reason).isEqualTo("dry_run")
        assertThat(dryGateway.posts).isEmpty()
    }

    @Test
    fun globalMutexAndDurableReservationAllowOnlyOneConcurrentPost() = runBlocking {
        val gateway = FakeGateway()
        val store = FakeReservationStore()
        val first = coordinator(gateway, reservationStore = store)
        val second = coordinator(gateway, reservationStore = store)
        val start = CompletableDeferred<Unit>()
        val outcomes = listOf(first, second).map { subject ->
            async(Dispatchers.Default) {
                start.await()
                subject.processUnified(defaultCandidate(), enabled = true, dryRun = false)
            }
        }

        start.complete(Unit)
        val completed = outcomes.awaitAll()

        assertThat(gateway.posts).hasSize(1)
        assertThat(store.reserveCalls).hasSize(2)
        assertThat(completed.map { it.reason }).containsExactly("delivered", "already_reserved")
        assertThat(completed.count { it.delivered }).isEqualTo(1)
        assertThat(completed.all { it.reserved }).isTrue()
    }

    @Test
    fun strictV2TagRoundTripsArbitraryEpisodeId() {
        val episodeId = "  meal|episode=\u03b1 \u0431\u0435\u0442\u0430\nline  "

        val note = UamTagCodec.buildTag(episodeId, 7, UamMode.BOOST, version = 2)
        val parsed = UamTagCodec.parseUamTag(note)

        assertThat(note).startsWith("UAM_ENGINE|id64=")
        assertThat(note).doesNotContain(episodeId)
        assertThat(parsed?.id).isEqualTo(episodeId)
        assertThat(parsed?.seq).isEqualTo(7)
        assertThat(parsed?.ver).isEqualTo(2)
        assertThat(parsed?.mode).isEqualTo("BOOST")
        assertThat(UamTagCodec.referencesEpisode(note, episodeId)).isTrue()
    }

    @Test
    fun strictTagParserRejectsUnanchoredDuplicateMalformedAndUnsupportedTags() {
        val validV2 = UamTagCodec.buildTag("episode", 1, UamMode.NORMAL, version = 2)
        val malformed = listOf(
            "prefix$validV2",
            "${validV2}suffix",
            validV2.replace("|seq=1|", "|seq=1|seq=2|"),
            "UAM_ENGINE|id=episode|seq=1|ver=x|mode=NORMAL|",
            "UAM_ENGINE|id=episode|seq=1|ver=3|mode=NORMAL|",
            "UAM_ENGINE|id=episode|seq=0|ver=1|mode=NORMAL|",
            "UAM_ENGINE|id=episode|seq=+1|ver=1|mode=NORMAL|",
            "UAM_ENGINE|id=episode|seq=1|ver=1|mode=UNKNOWN|",
            "UAM_ENGINE|id=episode|seq=1|ver=2|mode=NORMAL|",
            "UAM_ENGINE|id64=***|seq=1|ver=2|mode=NORMAL|",
            "UAM_ENGINE|id=episode|seq=1|mode=NORMAL|extra=value|"
        )

        malformed.forEach { note -> assertThat(UamTagCodec.parseUamTag(note)).isNull() }
    }

    @Test
    fun strictV1CompatibilitySupportsOmittedVersionAndRejectsUnsafeBuildIds() {
        val omittedVersion = UamTagCodec.parseUamTag("UAM_ENGINE|id=abc-123|seq=2|mode=BOOST|")
        val built = UamTagCodec.buildTag("abc-123", 2, UamMode.BOOST)

        assertThat(omittedVersion?.id).isEqualTo("abc-123")
        assertThat(omittedVersion?.ver).isEqualTo(1)
        assertThat(UamTagCodec.parseUamTag(built)?.ver).isEqualTo(1)
        assertThrows(IllegalArgumentException::class.java) {
            UamTagCodec.buildTag("unsafe|id=value", 1, UamMode.NORMAL)
        }
        Unit
    }

    @Test
    fun v2BuildRejectsUnpairedSurrogateEpisodeId() {
        val malformedUtf16 = "episode-\uD800-invalid"

        assertThrows(IllegalArgumentException::class.java) {
            UamTagCodec.buildTag(malformedUtf16, 1, UamMode.NORMAL, version = 2)
        }
        Unit
    }

    @Test
    fun referencesEpisodeRecognizesMalformedMatchingV2Tag() {
        val episodeId = "episode|with=unsafe id"
        val malformed = UamTagCodec.buildTag(episodeId, 1, UamMode.NORMAL, version = 2)
            .replace("|seq=1|", "|seq=invalid|")
        val paddedId = "UAM_ENGINE|id64=YQ==|seq=1|ver=2|mode=NORMAL|"

        assertThat(UamTagCodec.parseUamTag(malformed)).isNull()
        assertThat(UamTagCodec.referencesEpisode(malformed, episodeId)).isTrue()
        assertThat(UamTagCodec.referencesEpisode(malformed, "other")).isFalse()
        assertThat(UamTagCodec.parseUamTag(paddedId)).isNull()
        assertThat(UamTagCodec.referencesEpisode(paddedId, "a")).isTrue()
    }

    @Test
    fun throwingFetchFailsReconciliationAndThrowingPostCannotRetry() = runBlocking {
        val fetchGateway = FakeGateway(fetchThrows = IllegalStateException("fetch throw"))
        val fetchOutcome = coordinator(fetchGateway).processUnified(
            defaultCandidate(),
            enabled = true,
            dryRun = false
        )

        assertBlocked(fetchOutcome, "reconciliation_failed")
        assertThat(fetchOutcome.reconciliationValid).isFalse()
        assertThat(fetchOutcome.reconciledGrams).isNull()

        val postGateway = FakeGateway(postThrows = IllegalStateException("post throw"))
        val store = FakeReservationStore()
        val postOutcome = coordinator(postGateway, reservationStore = store).processUnified(
            defaultCandidate(),
            enabled = true,
            dryRun = false
        )

        assertSend(postOutcome, grams = 15.0, seq = 1)
        assertThat(postOutcome.reason).isEqualTo("post_outcome_unknown")
        assertThat(postOutcome.delivered).isFalse()
        assertThat(postOutcome.postAttempted).isTrue()
        assertThat(postOutcome.postOutcomeUnknown).isTrue()
        assertThat(postOutcome.reserved).isTrue()
        assertThat(store.reservedKeys).containsExactly(postOutcome.reservationKey)
        assertThat(store.pendingUnknown.keys).containsExactly(postOutcome.reservationKey)

        val secondOutcome = coordinator(postGateway, reservationStore = store).processUnified(
            defaultCandidate(),
            enabled = true,
            dryRun = false
        )

        assertThat(secondOutcome.reason).isEqualTo("already_reserved")
        assertThat(postGateway.posts).hasSize(1)
    }

    @Test
    fun fetchCancellationPropagatesWithoutPost() {
        val gateway = FakeGateway(fetchThrows = CancellationException("fetch cancelled"))

        assertThrows(CancellationException::class.java) {
            runBlocking {
                coordinator(gateway).processUnified(
                    defaultCandidate(),
                    enabled = true,
                    dryRun = false
                )
            }
        }

        assertThat(gateway.fetchCalls).isEqualTo(1)
        assertThat(gateway.posts).isEmpty()
    }

    @Test
    fun fetchFatalErrorPropagatesWithoutPost() {
        val gateway = FakeGateway(fetchThrows = LinkageError("fatal fetch"))

        assertThrows(LinkageError::class.java) {
            runBlocking {
                coordinator(gateway).processUnified(
                    defaultCandidate(),
                    enabled = true,
                    dryRun = false
                )
            }
        }

        assertThat(gateway.fetchCalls).isEqualTo(1)
        assertThat(gateway.posts).isEmpty()
    }

    @Test
    fun successfulPostSurvivesThrowingAuditAndRetainsMarkedReservation() = runBlocking {
        val gateway = FakeGateway()
        val store = FakeReservationStore()
        val throwingAudit = auditLogger(FakeAuditLogDao(throwOnInsert = true))

        val outcome = coordinator(gateway, throwingAudit, store).processUnified(
            defaultCandidate(),
            enabled = true,
            dryRun = false
        )

        assertThat(outcome.delivered).isTrue()
        assertThat(outcome.reason).isEqualTo("delivered")
        assertThat(outcome.remoteId).isEqualTo("remote-1")
        assertThat(outcome.reserved).isTrue()
        assertThat(store.reservedKeys).containsExactly(outcome.reservationKey)
        assertThat(store.sent[outcome.reservationKey]).isEqualTo("remote-1")
    }

    @Test
    fun reservationExceptionFailsClosedBeforePost() = runBlocking {
        val reserveGateway = FakeGateway()
        val reserveStore = FakeReservationStore(reserveError = IllegalStateException("reserve"))
        val reserveOutcome = coordinator(reserveGateway, reservationStore = reserveStore).processUnified(
            defaultCandidate(),
            enabled = true,
            dryRun = false
        )

        assertThat(reserveOutcome.reason).isEqualTo("reservation_failed")
        assertThat(reserveOutcome.delivered).isFalse()
        assertThat(reserveGateway.posts).isEmpty()
    }

    @Test
    fun reservationCancellationPropagatesBeforePost() {
        val cancellation = CancellationException("reserve cancelled")
        val gateway = FakeGateway()
        val store = FakeReservationStore(reserveError = cancellation)

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                coordinator(gateway, reservationStore = store).processUnified(
                    defaultCandidate(),
                    enabled = true,
                    dryRun = false
                )
            }
        }

        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(store.reserveCalls).hasSize(1)
        assertThat(store.reservedKeys).isEmpty()
        assertThat(gateway.posts).isEmpty()
    }

    @Test
    fun reservationFatalErrorPropagatesBeforePost() {
        val fatal = LinkageError("reserve fatal")
        val gateway = FakeGateway()
        val store = FakeReservationStore(reserveError = fatal)

        val thrown = assertThrows(LinkageError::class.java) {
            runBlocking {
                coordinator(gateway, reservationStore = store).processUnified(
                    defaultCandidate(),
                    enabled = true,
                    dryRun = false
                )
            }
        }

        assertThat(thrown).isSameInstanceAs(fatal)
        assertThat(store.reserveCalls).hasSize(1)
        assertThat(store.reservedKeys).isEmpty()
        assertThat(gateway.posts).isEmpty()
    }

    @Test
    fun pendingUnknownMarkFailureStillRetainsReservationAndUnknownOutcome() = runBlocking {
        val gateway = FakeGateway(postError = IllegalStateException("ambiguous"))
        val store = FakeReservationStore(pendingError = IllegalStateException("pending mark"))

        val outcome = coordinator(gateway, reservationStore = store).processUnified(
            defaultCandidate(),
            enabled = true,
            dryRun = false
        )

        assertThat(outcome.reason).isEqualTo("post_outcome_unknown")
        assertThat(outcome.postAttempted).isTrue()
        assertThat(outcome.postOutcomeUnknown).isTrue()
        assertThat(outcome.reserved).isTrue()
        assertThat(store.reservedKeys).containsExactly(outcome.reservationKey)
        assertThat(store.pendingUnknown).isEmpty()
    }

    @Test
    fun cancellationAfterPostInvocationMarksUnknownAndRethrows() {
        val cancellation = CancellationException("cancelled after invocation")
        val gateway = FakeGateway(postThrows = cancellation)
        val store = FakeReservationStore()

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                coordinator(gateway, reservationStore = store).processUnified(
                    defaultCandidate(),
                    enabled = true,
                    dryRun = false
                )
            }
        }

        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(gateway.posts).hasSize(1)
        assertThat(store.reservedKeys).hasSize(1)
        assertThat(store.pendingUnknown.keys).containsExactlyElementsIn(store.reservedKeys)
        assertThat(store.pendingUnknown.values.single()).contains("cancelled:CancellationException")
        assertRetryBlocked(gateway, store)
    }

    @Test
    fun fatalErrorAfterPostInvocationMarksUnknownAndRethrows() {
        val fatal = LinkageError("post fatal")
        val gateway = FakeGateway(postThrows = fatal)
        val store = FakeReservationStore()

        val thrown = assertThrows(LinkageError::class.java) {
            runBlocking {
                coordinator(gateway, reservationStore = store).processUnified(
                    defaultCandidate(),
                    enabled = true,
                    dryRun = false
                )
            }
        }

        assertThat(thrown).isSameInstanceAs(fatal)
        assertThat(gateway.posts).hasSize(1)
        assertThat(store.reservedKeys).hasSize(1)
        assertThat(store.pendingUnknown.keys).containsExactlyElementsIn(store.reservedKeys)
        assertThat(store.pendingUnknown.values.single()).contains("fatal:LinkageError")
        assertRetryBlocked(gateway, store)
    }

    @Test
    fun cancellationResultAfterPostInvocationMarksUnknownAndRethrows() {
        val cancellation = CancellationException("post result cancelled")
        val gateway = FakeGateway(postError = cancellation)
        val store = FakeReservationStore()

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                coordinator(gateway, reservationStore = store).processUnified(
                    defaultCandidate(),
                    enabled = true,
                    dryRun = false
                )
            }
        }

        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(gateway.posts).hasSize(1)
        assertThat(store.reservedKeys).hasSize(1)
        assertThat(store.pendingUnknown.keys).containsExactlyElementsIn(store.reservedKeys)
        assertThat(store.pendingUnknown.values.single()).contains("result_cancelled:CancellationException")
        assertRetryBlocked(gateway, store)
    }

    @Test
    fun fatalErrorResultAfterPostInvocationMarksUnknownAndRethrows() {
        val fatal = LinkageError("post result fatal")
        val gateway = FakeGateway(postError = fatal)
        val store = FakeReservationStore()

        val thrown = assertThrows(LinkageError::class.java) {
            runBlocking {
                coordinator(gateway, reservationStore = store).processUnified(
                    defaultCandidate(),
                    enabled = true,
                    dryRun = false
                )
            }
        }

        assertThat(thrown).isSameInstanceAs(fatal)
        assertThat(gateway.posts).hasSize(1)
        assertThat(store.reservedKeys).hasSize(1)
        assertThat(store.pendingUnknown.keys).containsExactlyElementsIn(store.reservedKeys)
        assertThat(store.pendingUnknown.values.single()).contains("result_fatal:LinkageError")
        assertRetryBlocked(gateway, store)
    }

    @Test
    fun markSentFailureKeepsIrreversibleDeliveryTruthAndReservation() = runBlocking {
        val gateway = FakeGateway()
        val store = FakeReservationStore(markError = IllegalStateException("mark"))

        val outcome = coordinator(gateway, reservationStore = store).processUnified(
            defaultCandidate(),
            enabled = true,
            dryRun = false
        )

        assertThat(outcome.delivered).isTrue()
        assertThat(outcome.reason).isEqualTo("delivered_reservation_mark_failed")
        assertThat(outcome.remoteId).isEqualTo("remote-1")
        assertThat(outcome.reserved).isTrue()
        assertThat(store.reservedKeys).containsExactly(outcome.reservationKey)
        assertThat(store.sent).isEmpty()
    }

    @Test
    fun markSentCancellationMarksUnknownWithRemoteIdAndRethrows() {
        val cancellation = CancellationException("mark sent cancelled")
        val gateway = FakeGateway()
        val store = FakeReservationStore(markError = cancellation)

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                coordinator(gateway, reservationStore = store).processUnified(
                    defaultCandidate(),
                    enabled = true,
                    dryRun = false
                )
            }
        }

        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(gateway.posts).hasSize(1)
        assertThat(store.sent).isEmpty()
        assertThat(store.pendingUnknown).hasSize(1)
        assertThat(store.pendingUnknown.values.single()).contains("mark_sent_cancelled")
        assertThat(store.pendingUnknown.values.single()).contains("remoteId=remote-1")
        assertRetryBlocked(gateway, store)
    }

    @Test
    fun markSentFatalErrorMarksUnknownWithRemoteIdAndRethrows() {
        val fatal = LinkageError("mark sent fatal")
        val gateway = FakeGateway()
        val store = FakeReservationStore(markError = fatal)

        val thrown = assertThrows(LinkageError::class.java) {
            runBlocking {
                coordinator(gateway, reservationStore = store).processUnified(
                    defaultCandidate(),
                    enabled = true,
                    dryRun = false
                )
            }
        }

        assertThat(thrown).isSameInstanceAs(fatal)
        assertThat(gateway.posts).hasSize(1)
        assertThat(store.sent).isEmpty()
        assertThat(store.pendingUnknown).hasSize(1)
        assertThat(store.pendingUnknown.values.single()).contains("mark_sent_fatal")
        assertThat(store.pendingUnknown.values.single()).contains("remoteId=remote-1")
        assertRetryBlocked(gateway, store)
    }

    @Test
    fun pendingUnknownCancellationPropagatesAndReservationBlocksRetry() {
        val cancellation = CancellationException("pending cancelled")
        val gateway = FakeGateway(postError = IllegalStateException("ambiguous"))
        val store = FakeReservationStore(pendingError = cancellation)

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                coordinator(gateway, reservationStore = store).processUnified(
                    defaultCandidate(),
                    enabled = true,
                    dryRun = false
                )
            }
        }

        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(store.reservedKeys).hasSize(1)
        assertThat(store.pendingUnknown).isEmpty()
        assertRetryBlocked(gateway, store)
    }

    @Test
    fun pendingUnknownFatalErrorPropagatesAndReservationBlocksRetry() {
        val fatal = LinkageError("pending fatal")
        val gateway = FakeGateway(postError = IllegalStateException("ambiguous"))
        val store = FakeReservationStore(pendingError = fatal)

        val thrown = assertThrows(LinkageError::class.java) {
            runBlocking {
                coordinator(gateway, reservationStore = store).processUnified(
                    defaultCandidate(),
                    enabled = true,
                    dryRun = false
                )
            }
        }

        assertThat(thrown).isSameInstanceAs(fatal)
        assertThat(store.reservedKeys).hasSize(1)
        assertThat(store.pendingUnknown).isEmpty()
        assertRetryBlocked(gateway, store)
    }

    @Test
    fun postCancellationRemainsPrimaryWhenPendingUnknownMarkFailsFatally() {
        val cancellation = CancellationException("post cancelled")
        val gateway = FakeGateway(postThrows = cancellation)
        val store = FakeReservationStore(pendingError = LinkageError("pending fatal"))

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                coordinator(gateway, reservationStore = store).processUnified(
                    defaultCandidate(),
                    enabled = true,
                    dryRun = false
                )
            }
        }

        assertThat(thrown).isSameInstanceAs(cancellation)
        assertThat(store.reservedKeys).hasSize(1)
        assertThat(store.pendingUnknown).isEmpty()
        assertRetryBlocked(gateway, store)
    }

    @Test
    fun postFatalErrorRemainsPrimaryWhenPendingUnknownMarkIsCancelled() {
        val fatal = LinkageError("post fatal")
        val gateway = FakeGateway(postThrows = fatal)
        val store = FakeReservationStore(pendingError = CancellationException("pending cancelled"))

        val thrown = assertThrows(LinkageError::class.java) {
            runBlocking {
                coordinator(gateway, reservationStore = store).processUnified(
                    defaultCandidate(),
                    enabled = true,
                    dryRun = false
                )
            }
        }

        assertThat(thrown).isSameInstanceAs(fatal)
        assertThat(store.reservedKeys).hasSize(1)
        assertThat(store.pendingUnknown).isEmpty()
        assertRetryBlocked(gateway, store)
    }

    @Test
    fun exactRepeatedRemoteIdIsDeduplicatedBeforeLedgerConversion() = runBlocking {
        val repeated = remoteEntry(EPISODE_ID, minutesAgo = 10, grams = 15.0, seq = 1)
        val gateway = FakeGateway(fetched = listOf(repeated, repeated))

        val outcome = coordinator(gateway).processUnified(
            defaultCandidate(supportedLowerBoundGrams = 30.0),
            enabled = true,
            dryRun = false
        )

        assertSend(outcome, grams = 15.0, seq = 2)
        assertThat(outcome.reconciledGrams).isEqualTo(15.0)
        assertThat(gateway.posts).hasSize(1)
    }

    @Test
    fun conflictingRowsWithSameRemoteIdFailClosedWithUnknownReconciliation() = runBlocking {
        val first = remoteEntry(EPISODE_ID, minutesAgo = 10, grams = 15.0, seq = 1)
        val conflict = first.copy(grams = 16.0)
        val gateway = FakeGateway(fetched = listOf(first, conflict))

        val outcome = coordinator(gateway).processUnified(
            defaultCandidate(supportedLowerBoundGrams = 30.0),
            enabled = true,
            dryRun = false
        )

        assertBlocked(outcome, "reconciliation_failed")
        assertThat(outcome.reconciliationValid).isFalse()
        assertThat(outcome.reconciledGrams).isNull()
        assertThat(gateway.posts).isEmpty()
    }

    @Test
    fun candidatePreflightReturnsExactStructuralReasonWithoutFetching() = runBlocking {
        val cases = listOf(
            defaultCandidate().copy(episodeId = "") to "invalid_episode_id",
            defaultCandidate().copy(nowTs = -1L) to "invalid_now_timestamp",
            defaultCandidate().copy(confidence = Double.NaN) to "invalid_confidence",
            defaultCandidate().copy(sensorTrust = 1.1) to "invalid_sensor_trust",
            defaultCandidate().copy(activeSinceTs = null) to "active_time_missing",
            defaultCandidate().copy(episodeId = "episode-\uD800-invalid") to "invalid_episode_id"
        )

        cases.forEach { (candidate, expectedReason) ->
            val gateway = FakeGateway()
            val outcome = coordinator(gateway).processUnified(candidate, enabled = true, dryRun = false)

            assertBlocked(outcome, expectedReason)
            assertThat(gateway.fetchCalls).isEqualTo(0)
            assertThat(outcome.reconciliationValid).isFalse()
        }
    }

    @Test
    fun preflightDoesNotApplyInitialConfidenceThresholdBeforeRemoteContinuationIsKnown() = runBlocking {
        val remote = remoteEntry(EPISODE_ID, minutesAgo = 10, grams = 15.0, seq = 1)
        val gateway = FakeGateway(fetched = listOf(remote))

        val outcome = coordinator(gateway).processUnified(
            defaultCandidate(confidence = 0.60, supportedLowerBoundGrams = 30.0),
            enabled = true,
            dryRun = true
        )

        assertSend(outcome, grams = 15.0, seq = 2)
        assertThat(gateway.fetchCalls).isEqualTo(1)
        assertThat(gateway.posts).isEmpty()
    }

    @Test
    fun legacyFetchFailureDoesNotExportOrMutateEvent() = runBlocking {
        val auditDao = FakeAuditLogDao()
        val gateway = FakeGateway(fetchError = IllegalStateException("fetch failed"))
        val event = confirmedEvent(id = "legacy-fetch-failure", nowTs = NOW_TS, carbsDisplay = 20.0)

        val outcome = UamExportCoordinator(gateway, auditLogger(auditDao)).process(
            nowTs = NOW_TS,
            events = listOf(event),
            config = defaultConfig(exportMode = UamExportMode.CONFIRMED_ONLY)
        )

        assertThat(gateway.posts).isEmpty()
        assertThat(outcome).isEqualTo(
            UamExportCoordinator.Outcome(events = listOf(event), remoteEntries = emptyList())
        )
        assertThat(auditDao.rows.map { it.message }).containsExactly("uam_export_reconciliation_failed")
        Unit
    }

    @Test
    fun unifiedGlobalThirtyMinuteCapSurvivesEpisodeIdSwitch() = runBlocking {
        val gateway = FakeGateway(
            fetched = listOf(
                remoteEntry("old-episode", minutesAgo = 20, grams = 15.0, seq = 1, version = 2),
                remoteEntry("old-episode", minutesAgo = 10, grams = 15.0, seq = 2, version = 2)
            )
        )

        val outcome = coordinator(gateway).processUnified(
            candidate = defaultCandidate(episodeId = "new-episode"),
            enabled = true,
            dryRun = false
        )

        assertBlocked(outcome, "global_rolling_30m_capacity_exhausted")
        assertThat(gateway.posts).isEmpty()
    }

    @Test
    fun unifiedGlobalSixtyMinuteCapSurvivesEpisodeIdSwitch() = runBlocking {
        val gateway = FakeGateway(
            fetched = listOf(
                remoteEntry("old-episode", minutesAgo = 55, grams = 15.0, seq = 1, version = 2),
                remoteEntry("old-episode", minutesAgo = 40, grams = 15.0, seq = 2, version = 2),
                remoteEntry("old-episode", minutesAgo = 31, grams = 15.0, seq = 3, version = 2),
                remoteEntry("old-episode", minutesAgo = 10, grams = 5.0, seq = 4, version = 2)
            )
        )

        val outcome = coordinator(gateway).processUnified(
            candidate = defaultCandidate(episodeId = "new-episode"),
            enabled = true,
            dryRun = false
        )

        assertBlocked(outcome, "global_rolling_60m_capacity_exhausted")
        assertThat(gateway.posts).isEmpty()
    }

    @Test
    fun unifiedAllowsSameSequenceInDistinctOldEpisodes() = runBlocking {
        val gateway = FakeGateway(
            fetched = listOf(
                remoteEntry("episode-a", minutesAgo = 70, grams = 5.0, seq = 1, version = 2),
                remoteEntry("episode-b", minutesAgo = 80, grams = 5.0, seq = 1, version = 2)
            )
        )

        val outcome = coordinator(gateway).processUnified(
            candidate = defaultCandidate(episodeId = "new-episode"),
            enabled = true,
            dryRun = false
        )

        assertSend(outcome, grams = 15.0, seq = 1)
        assertThat(gateway.posts).hasSize(1)
    }

    @Test
    fun unifiedMalformedApparentV2TagFailsClosedForAnotherEpisode() = runBlocking {
        val gateway = FakeGateway(
            fetched = listOf(
                AapsCarbEntry(
                    remoteId = "malformed-v2",
                    tsMs = NOW_TS - 10 * MINUTE_MS,
                    grams = 5.0,
                    note = "UAM_ENGINE|id64=%%%|seq=1|ver=2|mode=NORMAL|"
                )
            )
        )

        val outcome = coordinator(gateway).processUnified(
            candidate = defaultCandidate(),
            enabled = true,
            dryRun = false
        )

        assertBlocked(outcome, "reconciliation_failed")
        assertThat(gateway.posts).isEmpty()
        assertThat(outcome.reconciliationValid).isFalse()
    }

    @Test
    fun unifiedSourceUsesTenMinuteWallClockLimitBeforeNetwork() = runBlocking {
        val boundaryGateway = FakeGateway()
        val boundaryOutcome = coordinator(boundaryGateway).processUnified(
            candidate = defaultCandidate(sourceSnapshotTs = NOW_TS - 10 * MINUTE_MS),
            enabled = true,
            dryRun = true
        )
        assertSend(boundaryOutcome, grams = 15.0, seq = 1)
        assertThat(boundaryGateway.fetchCalls).isEqualTo(1)
        assertThat(boundaryGateway.posts).isEmpty()

        listOf(
            NOW_TS - 10 * MINUTE_MS - 1L to "source_snapshot_stale",
            NOW_TS + 1L to "source_snapshot_in_future"
        ).forEach { (sourceSnapshotTs, expectedReason) ->
            val gateway = FakeGateway()

            val outcome = coordinator(gateway).processUnified(
                candidate = defaultCandidate(sourceSnapshotTs = sourceSnapshotTs),
                enabled = true,
                dryRun = false
            )

            assertBlocked(outcome, expectedReason)
            assertThat(gateway.fetchCalls).isEqualTo(0)
            assertThat(gateway.posts).isEmpty()
        }
    }

    @Test
    fun sourceExpiringDuringReconciliationBlocksBeforeReservation() = runBlocking {
        var wallNow = NOW_TS
        val store = FakeReservationStore()
        val gateway = FakeGateway(
            onFetch = { wallNow = NOW_TS + 10 * MINUTE_MS + 1L }
        )

        val outcome = coordinator(
            gateway = gateway,
            reservationStore = store,
            wallClockMs = { wallNow }
        ).processUnified(
            candidate = defaultCandidate(sourceSnapshotTs = NOW_TS),
            enabled = true,
            dryRun = false
        )

        assertBlocked(outcome, "source_snapshot_stale")
        assertThat(gateway.fetchCalls).isEqualTo(1)
        assertThat(gateway.posts).isEmpty()
        assertThat(store.reserveCalls).isEmpty()
    }

    @Test
    fun sourceExpiringDuringReservationIsReleasedBeforePost() = runBlocking {
        var wallNow = NOW_TS
        val store = FakeReservationStore(
            onReserve = { wallNow = NOW_TS + 10 * MINUTE_MS + 1L }
        )
        val gateway = FakeGateway()

        val outcome = coordinator(
            gateway = gateway,
            reservationStore = store,
            wallClockMs = { wallNow }
        ).processUnified(
            candidate = defaultCandidate(sourceSnapshotTs = NOW_TS),
            enabled = true,
            dryRun = false
        )

        assertBlocked(outcome, "source_snapshot_stale")
        assertThat(gateway.posts).isEmpty()
        assertThat(store.reserveCalls).hasSize(1)
        assertThat(store.reservedKeys).isEmpty()
    }

    private fun coordinator(
        gateway: AapsCarbGateway,
        auditLogger: AuditLogger? = null,
        reservationStore: UamExportReservationStore = FakeReservationStore(),
        wallClockMs: () -> Long = { NOW_TS }
    ) = UamExportCoordinator(
        gateway = gateway,
        auditLogger = auditLogger,
        reservationStore = reservationStore,
        wallClockMs = wallClockMs
    )

    private fun defaultCandidate(
        nowTs: Long = NOW_TS,
        episodeId: String = EPISODE_ID,
        activeSinceTs: Long? = nowTs - 10 * MINUTE_MS,
        confidence: Double = 0.80,
        supportedLowerBoundGrams: Double? = 15.0,
        remoteLedger: List<UamExportLedgerEntry> = emptyList(),
        sourceSnapshotTs: Long = nowTs
    ) = UamExportPolicyInput(
        nowTs = nowTs,
        episodeId = episodeId,
        activeSinceTs = activeSinceTs,
        confidence = confidence,
        supportedLowerBoundGrams = supportedLowerBoundGrams,
        lowerBoundStableBuckets = 2,
        sensorTrust = 0.90,
        sensorBlocked = false,
        signedResidualMmol5 = 0.10,
        shortAverageDeltaMmol5 = 0.10,
        currentGlucoseMmol = 8.0,
        forecastMinimumMmol = 8.0,
        effectiveCobGrams = 0.0,
        therapyCoverage = 0.90,
        remoteLedger = remoteLedger,
        sourceSnapshotTs = sourceSnapshotTs
    )

    private fun defaultConfig(exportMode: UamExportMode) = UamExportCoordinator.Config(
        enableUamExportToAaps = true,
        sensorBlocked = false,
        exportMode = exportMode,
        dryRunExport = false,
        minSnackG = 15,
        maxSnackG = 60,
        snackStepG = 5,
        exportMinIntervalMin = 10,
        exportMaxBackdateMin = 180,
        calculatedCarbsGrams = null,
        calculatedToOriginalMultiplier = 2.4
    )

    private fun confirmedEvent(
        id: String,
        nowTs: Long,
        carbsDisplay: Double
    ) = UamInferenceEvent(
        id = id,
        state = UamInferenceState.CONFIRMED,
        mode = UamMode.NORMAL,
        createdAt = nowTs - 30 * MINUTE_MS,
        updatedAt = nowTs,
        ingestionTs = nowTs - 25 * MINUTE_MS,
        carbsModelG = carbsDisplay,
        carbsDisplayG = carbsDisplay,
        confidence = 0.8
    )

    private fun remoteEntry(
        episodeId: String,
        minutesAgo: Long? = null,
        tsMs: Long = NOW_TS - requireNotNull(minutesAgo) * MINUTE_MS,
        grams: Double,
        seq: Int,
        version: Int = 1
    ) = AapsCarbEntry(
        remoteId = "$episodeId-$seq-$tsMs",
        tsMs = tsMs,
        grams = grams,
        note = UamTagCodec.buildTag(episodeId, seq, UamMode.NORMAL, version = version)
    )

    private fun auditLogger(dao: FakeAuditLogDao) = AuditLogger(
        auditLogDao = dao,
        gson = Gson(),
        clock = { NOW_TS }
    )

    private fun assertSend(outcome: UamExportCoordinator.Outcome, grams: Double, seq: Int) {
        assertThat(outcome.decision).isEqualTo(
            UamExportDecision.Send(
                grams = grams,
                treatmentTs = (NOW_TS / FIVE_MINUTE_MS) * FIVE_MINUTE_MS,
                seq = seq
            )
        )
    }

    private fun assertBlocked(outcome: UamExportCoordinator.Outcome, reason: String) {
        assertThat(outcome.decision).isEqualTo(UamExportDecision.Block(reason))
        assertThat(outcome.delivered).isFalse()
        assertThat(outcome.reason).isEqualTo(reason)
    }

    private fun assertRetryBlocked(gateway: FakeGateway, store: FakeReservationStore) {
        val postsBeforeRetry = gateway.posts.size
        val retry = runBlocking {
            coordinator(gateway, reservationStore = store).processUnified(
                defaultCandidate(),
                enabled = true,
                dryRun = false
            )
        }
        assertThat(retry.reason).isEqualTo("already_reserved")
        assertThat(gateway.posts).hasSize(postsBeforeRetry)
    }

    private class FakeGateway(
        var fetched: List<AapsCarbEntry> = emptyList(),
        var fetchError: Throwable? = null,
        var postError: Throwable? = null,
        var fetchThrows: Throwable? = null,
        var postThrows: Throwable? = null,
        var onFetch: (() -> Unit)? = null
    ) : AapsCarbGateway {
        val posts = mutableListOf<PostCall>()
        val fetchSinceCalls = mutableListOf<Long>()
        var fetchCalls = 0

        override suspend fun postCarbEntry(tsMs: Long, grams: Double, note: String): Result<String> {
            posts += PostCall(tsMs = tsMs, grams = grams, note = note)
            postThrows?.let { throw it }
            val error = postError
            return if (error == null) Result.success("remote-${posts.size}") else Result.failure(error)
        }

        override suspend fun fetchCarbEntries(sinceTsMs: Long): Result<List<AapsCarbEntry>> {
            fetchCalls += 1
            fetchSinceCalls += sinceTsMs
            onFetch?.invoke()
            fetchThrows?.let { throw it }
            val error = fetchError
            return if (error == null) Result.success(fetched) else Result.failure(error)
        }
    }

    private class FakeReservationStore(
        private val reserveError: Throwable? = null,
        private val markError: Throwable? = null,
        private val pendingError: Throwable? = null,
        private val releaseError: Throwable? = null,
        private val onReserve: (() -> Unit)? = null
    ) : UamExportReservationStore {
        val reserveCalls = mutableListOf<String>()
        val reservedKeys = linkedSetOf<String>()
        val sent = linkedMapOf<String, String>()
        val pendingUnknown = linkedMapOf<String, String?>()

        override suspend fun reserve(key: String): Boolean {
            reserveCalls += key
            reserveError?.let { throw it }
            val added = reservedKeys.add(key)
            onReserve?.invoke()
            return added
        }

        override suspend fun markSent(key: String, remoteId: String) {
            markError?.let { throw it }
            check(key in reservedKeys)
            sent[key] = remoteId
        }

        override suspend fun markPendingUnknown(key: String, detail: String?) {
            pendingError?.let { throw it }
            check(key in reservedKeys)
            pendingUnknown[key] = detail
        }

        override suspend fun release(key: String) {
            releaseError?.let { throw it }
            reservedKeys.remove(key)
        }
    }

    private class FakeAuditLogDao(
        private val throwOnInsert: Boolean = false
    ) : AuditLogDao {
        val rows = mutableListOf<AuditLogEntity>()

        override suspend fun insert(entity: AuditLogEntity) {
            if (throwOnInsert) throw IllegalStateException("audit failed")
            rows += entity
        }

        override fun observeLatest(limit: Int): Flow<List<AuditLogEntity>> = emptyFlow()

        override suspend fun recentByMessage(message: String, sinceTs: Long, limit: Int): List<AuditLogEntity> = rows
            .filter { it.message == message && it.timestamp >= sinceTs }
            .sortedByDescending { it.timestamp }
            .take(limit)

        override suspend fun deleteOlderThan(olderThan: Long): Int = 0

        override suspend fun deleteOlderThanInfoMessages(olderThan: Long, messages: List<String>): Int = 0
    }

    private data class PostCall(val tsMs: Long, val grams: Double, val note: String)

    private companion object {
        const val MINUTE_MS = 60_000L
        const val FIVE_MINUTE_MS = 5 * MINUTE_MS
        const val NOW_TS = 1_800_000_123_456L
        const val EPISODE_ID = "episode-1"
    }
}
