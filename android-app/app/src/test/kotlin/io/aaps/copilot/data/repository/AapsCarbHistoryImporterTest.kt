package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.aaps.copilot.data.local.dao.TherapyDao
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AapsCarbHistoryImporterTest {

    @Test
    fun classifiesRealUamAndSignedCorrectionRows() = runTest {
        val dao = RecordingTherapyDao()
        val importer = AapsCarbHistoryImporter(dao.proxy)

        importer.importPage(
            page(
                row(id = 1L, amount = 24.5, notes = "lunch"),
                row(
                    id = 2L,
                    timestamp = ROW_TS + 60_000L,
                    amount = 7.25,
                    notes = "UAM_ENGINE|id=episode|seq=1|ver=2|mode=NORMAL|"
                ),
                row(
                    id = 3L,
                    timestamp = ROW_TS + 120_000L,
                    amount = -4.0,
                    notes = "UAM_ENGINE|must-not-win"
                )
            )
        )

        val real = dao.stored.getValue("aaps-carb-1")
        val uam = dao.stored.getValue("aaps-carb-2")
        val correction = dao.stored.getValue("aaps-carb-3")
        assertThat(real.type).isEqualTo("carbs")
        assertThat(payload(real)["classification"].asString).isEqualTo("AAPS_REAL")
        assertThat(payload(real)["source"].asString).isEqualTo("aaps_direct_history")
        assertThat(payload(real)["carbs"].asDouble).isEqualTo(24.5)
        assertThat(payload(uam)["classification"].asString).isEqualTo("UAM_SYNTHETIC")
        assertThat(payload(correction)["classification"].asString)
            .isEqualTo("AAPS_CORRECTION")
        assertThat(payload(correction)["carbs"].asDouble).isEqualTo(-4.0)
        assertThat(payload(correction)["aapsCarbAmount"].asDouble).isEqualTo(-4.0)
        assertThat(dao.upserted).hasSize(1)
        assertThat(dao.upserted.single().map { it.id })
            .containsExactly("aaps-carb-1", "aaps-carb-2", "aaps-carb-3")
            .inOrder()
    }

    @Test
    fun oneTransactionEnclosesCandidateReadsAndFinalWrite() = runTest {
        val dao = RecordingTherapyDao()
        val transaction = RecordingTransactionRunner()
        dao.onDaoCall = { assertThat(transaction.active).isTrue() }
        val importer = AapsCarbHistoryImporter(dao.proxy, transaction.runner)

        importer.importPage(
            page(
                row(id = 40L, amount = 8.0, notes = "first"),
                row(
                    id = 41L,
                    timestamp = ROW_TS + 60_000L,
                    amount = 9.0,
                    notes = "second"
                )
            )
        )

        assertThat(transaction.calls).isEqualTo(1)
        assertThat(dao.daoCalls)
            .containsExactly("carbCandidates", "carbCandidates", "upsertAll")
            .inOrder()
        assertThat(dao.upserted).hasSize(1)
    }

    @Test
    fun candidateFailureBeforeFinalBatchWritesNothing() = runTest {
        val dao = RecordingTherapyDao()
        val transaction = RecordingTransactionRunner()
        dao.failCandidateCall = 2
        val importer = AapsCarbHistoryImporter(dao.proxy, transaction.runner)

        val failure = runCatching {
            importer.importPage(
                page(
                    row(id = 42L, amount = 8.0, notes = "first"),
                    row(
                        id = 43L,
                        timestamp = ROW_TS + 60_000L,
                        amount = 9.0,
                        notes = "second"
                    )
                )
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(transaction.calls).isEqualTo(1)
        assertThat(dao.upserted).isEmpty()
        assertThat(dao.stored).isEmpty()
    }

    @Test
    fun newerRevisionObservedBeforeCommitGuardRollsBackWrites() = runTest {
        val dao = RecordingTherapyDao()
        val transaction = RollbackTransactionRunner(dao)
        val importer = AapsCarbHistoryImporter(dao.proxy, transaction.runner)
        val importingPage = page(row(id = 43L, amount = 9.0))
        val latestObservedRevision = importingPage.revisionId + 1L
        var guardSawPendingWrite = false

        val failure = runCatching {
            importer.importPage(importingPage) {
                guardSawPendingWrite = dao.stored.containsKey("aaps-carb-43")
                latestObservedRevision == importingPage.revisionId
            }
        }.exceptionOrNull()

        assertThat(guardSawPendingWrite).isTrue()
        assertThat(failure).isInstanceOf(AapsCarbImportSupersededException::class.java)
        assertThat(transaction.observedFailure).isSameInstanceAs(failure)
        assertThat(transaction.rollbacks).isEqualTo(1)
        assertThat(dao.stored).isEmpty()
    }

    @Test
    fun newRowUsesStableIdAndRepeatUpdatesSameRowIncludingInvalidation() = runTest {
        val dao = RecordingTherapyDao()
        val importer = AapsCarbHistoryImporter(dao.proxy)

        importer.importPage(page(row(id = 44L, version = 1, amount = 18.0)))
        importer.importPage(
            page(row(id = 44L, version = 2, amount = 18.0, isValid = false))
        )

        assertThat(dao.stored.keys).containsExactly("aaps-carb-44")
        val persisted = payload(dao.stored.getValue("aaps-carb-44"))
        assertThat(persisted["aapsCarbId"].asLong).isEqualTo(44L)
        assertThat(persisted["aapsVersion"].asInt).isEqualTo(2)
        assertThat(persisted["isValid"].asBoolean).isFalse()
        assertThat(persisted["carbs"].asDouble).isEqualTo(0.0)
        assertThat(persisted["aapsCarbAmount"].asDouble).isEqualTo(18.0)
        assertThat(dao.upserted.flatten().map { it.id })
            .containsExactly("aaps-carb-44", "aaps-carb-44")
            .inOrder()
    }

    @Test
    fun sameAapsIdHigherVersionClearsCanonicalIdentityFields() = runTest {
        val dao = RecordingTherapyDao()
        val importer = AapsCarbHistoryImporter(dao.proxy)
        val uamNotes = "UAM_ENGINE|id=clear|seq=1|ver=2|mode=NORMAL|"

        importer.importPage(
            page(
                row(
                    id = 47L,
                    version = 1,
                    amount = 9.0,
                    notes = uamNotes,
                    nightscoutId = "remote-clear-47"
                )
            )
        )
        importer.importPage(
            page(
                row(
                    id = 47L,
                    version = 2,
                    amount = 9.0,
                    notes = null,
                    nightscoutId = null
                )
            )
        )

        assertThat(dao.stored.keys).containsExactly("aaps-carb-47")
        val persisted = payload(dao.stored.getValue("aaps-carb-47"))
        assertThat(persisted["aapsVersion"].asInt).isEqualTo(2)
        assertThat(persisted["classification"].asString).isEqualTo("AAPS_REAL")
        assertThat(persisted.has("notes")).isFalse()
        assertThat(persisted.has("notesSha256")).isFalse()
        assertThat(persisted["notesTruncated"].asBoolean).isFalse()
        assertThat(persisted.has("nightscoutId")).isFalse()
        assertThat(persisted.has("nightscoutIdSha256")).isFalse()
        assertThat(persisted["nightscoutIdTruncated"].asBoolean).isFalse()
    }

    @Test
    fun distinctAapsIdsWithSameStructuralIdentityRemainDistinct() = runTest {
        val dao = RecordingTherapyDao()

        AapsCarbHistoryImporter(dao.proxy).importPage(
            page(
                row(id = 45L, amount = 11.0, notes = "same structural identity"),
                row(id = 46L, amount = 11.0, notes = "same structural identity")
            )
        )

        assertThat(dao.stored.keys).containsExactly("aaps-carb-45", "aaps-carb-46")
        assertThat(payload(dao.stored.getValue("aaps-carb-45"))["aapsCarbId"].asLong)
            .isEqualTo(45L)
        assertThat(payload(dao.stored.getValue("aaps-carb-46"))["aapsCarbId"].asLong)
            .isEqualTo(46L)
    }

    @Test
    fun sameNightscoutIdWithinToleranceEnrichesExistingPrimaryKey() = runTest {
        val existing = entity(
            id = "remote-abc",
            timestamp = ROW_TS - 2_000L,
            type = "meal_bolus",
            payload = """
                {"carbs":"15.005","insulin":"1.2","notes":"other"}
            """.trimIndent()
        )
        val dao = RecordingTherapyDao(existing)

        AapsCarbHistoryImporter(dao.proxy).importPage(
            page(row(id = 51L, amount = 15.0, nightscoutId = "remote-abc"))
        )

        assertThat(dao.stored.keys).containsExactly("remote-abc")
        val enriched = dao.stored.getValue("remote-abc")
        assertThat(enriched.type).isEqualTo("meal_bolus")
        assertThat(payload(enriched)["insulin"].asString).isEqualTo("1.2")
        assertThat(payload(enriched)["aapsCarbId"].asLong).isEqualTo(51L)
    }

    @Test
    fun combinedMealBolusMergePreservesBolusWideFields() = runTest {
        val existing = entity(
            id = "combined-valid",
            timestamp = ROW_TS,
            type = "meal_bolus",
            payload = """
                {
                  "grams":15.0,
                  "enteredCarbs":15.0,
                  "insulin":"1.2",
                  "isValid":true,
                  "classification":"REAL_FETCHED",
                  "source":"nightscout_treatment",
                  "synthetic":false
                }
            """.trimIndent()
        )
        val dao = RecordingTherapyDao(existing)

        AapsCarbHistoryImporter(dao.proxy).importPage(
            page(row(id = 64L, amount = 15.0, nightscoutId = "combined-valid"))
        )

        val persisted = payload(dao.stored.getValue("combined-valid"))
        assertThat(persisted["insulin"].asString).isEqualTo("1.2")
        assertThat(persisted["isValid"].asBoolean).isTrue()
        assertThat(persisted["classification"].asString).isEqualTo("REAL_FETCHED")
        assertThat(persisted["source"].asString).isEqualTo("nightscout_treatment")
        assertThat(persisted["synthetic"].asBoolean).isFalse()
        assertThat(persisted["aapsCarbIsValid"].asBoolean).isTrue()
        assertThat(persisted["aapsCarbClassification"].asString).isEqualTo("AAPS_REAL")
        assertThat(persisted["aapsCarbSource"].asString)
            .isEqualTo("aaps_direct_history")
        assertThat(persisted["aapsCarbSynthetic"].asBoolean).isFalse()
        assertThat(persisted["carbs"].asDouble).isEqualTo(15.0)
        assertThat(persisted["grams"].asDouble).isEqualTo(15.0)
        assertThat(persisted["enteredCarbs"].asDouble).isEqualTo(15.0)
        assertThat(persisted["aapsCarbAmount"].asDouble).isEqualTo(15.0)
    }

    @Test
    fun combinedMealBolusCarbInvalidationPreservesValidBolus() = runTest {
        val existing = entity(
            id = "combined-invalidation",
            timestamp = ROW_TS,
            type = "meal_bolus",
            payload = """
                {
                  "grams":15.0,
                  "enteredCarbs":15.0,
                  "insulin":"1.2",
                  "isValid":true,
                  "classification":"REAL_FETCHED",
                  "source":"nightscout_treatment",
                  "synthetic":false
                }
            """.trimIndent()
        )
        val dao = RecordingTherapyDao(existing)
        val importer = AapsCarbHistoryImporter(dao.proxy)

        importer.importPage(
            page(row(id = 65L, amount = 15.0, nightscoutId = "combined-invalidation"))
        )
        importer.importPage(
            page(
                row(
                    id = 65L,
                    version = 2,
                    amount = 15.0,
                    isValid = false,
                    nightscoutId = "combined-invalidation"
                )
            )
        )

        val persisted = payload(dao.stored.getValue("combined-invalidation"))
        assertThat(persisted["insulin"].asString).isEqualTo("1.2")
        assertThat(persisted["isValid"].asBoolean).isTrue()
        assertThat(persisted["classification"].asString).isEqualTo("REAL_FETCHED")
        assertThat(persisted["source"].asString).isEqualTo("nightscout_treatment")
        assertThat(persisted["synthetic"].asBoolean).isFalse()
        assertThat(persisted["aapsCarbIsValid"].asBoolean).isFalse()
        assertThat(persisted["aapsCarbClassification"].asString).isEqualTo("AAPS_REAL")
        assertThat(persisted["aapsCarbSource"].asString)
            .isEqualTo("aaps_direct_history")
        assertThat(persisted["aapsCarbSynthetic"].asBoolean).isFalse()
        assertThat(persisted["carbs"].asDouble).isEqualTo(0.0)
        assertThat(persisted["grams"].asDouble).isEqualTo(0.0)
        assertThat(persisted["enteredCarbs"].asDouble).isEqualTo(0.0)
        assertThat(persisted["aapsCarbAmount"].asDouble).isEqualTo(15.0)
    }

    @Test
    fun aapsFirstThenRemoteRescanPromotesRemoteAndTombstonesPureCarb() = runTest {
        val imported = row(
            id = 66L,
            amount = 21.0,
            notes = "dinner",
            nightscoutId = "remote-combined-66"
        )
        val dao = RecordingTherapyDao()
        val importer = AapsCarbHistoryImporter(dao.proxy)

        importer.importPage(page(imported))
        dao.stored["remote-combined-66"] = entity(
            id = "remote-combined-66",
            timestamp = ROW_TS,
            type = "meal_bolus",
            payload = """
                {
                  "carbs":21.0,
                  "insulin":"2.1",
                  "isValid":true,
                  "classification":"REAL_FETCHED",
                  "source":"nightscout_treatment",
                  "synthetic":false,
                  "notes":"dinner"
                }
            """.trimIndent()
        )

        importer.importPage(page(imported))

        val survivor = payload(dao.stored.getValue("remote-combined-66"))
        assertThat(survivor["aapsCarbId"].asLong).isEqualTo(66L)
        assertThat(survivor["insulin"].asString).isEqualTo("2.1")
        assertThat(survivor["isValid"].asBoolean).isTrue()
        assertThat(survivor["classification"].asString).isEqualTo("REAL_FETCHED")

        val tombstone = payload(dao.stored.getValue("aaps-carb-66"))
        assertThat(tombstone["isValid"].asBoolean).isFalse()
        assertThat(tombstone["carbs"].asDouble).isEqualTo(0.0)
        assertThat(tombstone["aapsCarbAmount"].asDouble).isEqualTo(21.0)
        assertThat(tombstone["aapsCarbIsValid"].asBoolean).isFalse()
        assertThat(tombstone["aapsCarbSuperseded"].asBoolean).isTrue()
        assertThat(tombstone["aapsCarbSupersededBy"].asString)
            .isEqualTo("remote-combined-66")
        assertThat(dao.upserted.last().map { it.id })
            .containsExactly("remote-combined-66", "aaps-carb-66")
            .inOrder()
    }

    @Test
    fun repeatedRemoteRescansAtomicallyUpsertStableSurvivorAndTombstone() = runTest {
        val imported = row(
            id = 67L,
            amount = 18.0,
            notes = "repeat dinner",
            nightscoutId = "remote-combined-67"
        )
        val dao = RecordingTherapyDao()
        val importer = AapsCarbHistoryImporter(dao.proxy)

        importer.importPage(page(imported))
        dao.stored["remote-combined-67"] = entity(
            id = "remote-combined-67",
            timestamp = ROW_TS,
            type = "meal_bolus",
            payload = """
                {
                  "carbs":18.0,
                  "insulin":"1.8",
                  "isValid":true,
                  "classification":"REAL_FETCHED",
                  "notes":"repeat dinner"
                }
            """.trimIndent()
        )

        importer.importPage(page(imported))
        val firstSurvivor = dao.stored.getValue("remote-combined-67").payloadJson
        val firstTombstone = dao.stored.getValue("aaps-carb-67").payloadJson
        importer.importPage(page(imported))

        assertThat(dao.stored.getValue("remote-combined-67").payloadJson)
            .isEqualTo(firstSurvivor)
        assertThat(dao.stored.getValue("aaps-carb-67").payloadJson)
            .isEqualTo(firstTombstone)
        assertThat(dao.upserted).hasSize(2)
        assertThat(dao.upserted.last().map { it.id })
            .containsExactly("remote-combined-67", "aaps-carb-67")
            .inOrder()
    }

    @Test
    fun plannedEntitiesAreVisibleToLaterRowsInSamePage() = runTest {
        val existing = entity(
            id = "local-shared",
            timestamp = ROW_TS,
            payload = """{"carbs":10.0,"notes":"shared"}"""
        )
        val dao = RecordingTherapyDao(existing)

        AapsCarbHistoryImporter(dao.proxy).importPage(
            page(
                row(id = 80L, amount = 10.0, notes = "shared"),
                row(id = 81L, amount = 10.0, notes = "shared")
            )
        )

        assertThat(dao.stored.keys).containsExactly("local-shared", "aaps-carb-81")
        assertThat(payload(dao.stored.getValue("local-shared"))["aapsCarbId"].asLong)
            .isEqualTo(80L)
        assertThat(payload(dao.stored.getValue("aaps-carb-81"))["aapsCarbId"].asLong)
            .isEqualTo(81L)
        assertThat(dao.upserted).hasSize(1)
    }

    @Test
    fun newRevisionOnlyDoesNotRewriteButClinicalChangeDoes() = runTest {
        val dao = RecordingTherapyDao()
        val importer = AapsCarbHistoryImporter(dao.proxy)
        val valid = row(id = 82L, version = 1, amount = 12.0, notes = "meal")

        importer.importPage(pageAtRevision(valid, revisionId = 100L, generatedAt = 1_000L))
        importer.importPage(pageAtRevision(valid, revisionId = 101L, generatedAt = 2_000L))

        assertThat(dao.upserted).hasSize(1)
        assertThat(payload(dao.stored.getValue("aaps-carb-82"))["aapsRevisionId"].asLong)
            .isEqualTo(100L)

        importer.importPage(
            pageAtRevision(
                valid.copy(version = 2, isValid = false),
                revisionId = 102L,
                generatedAt = 3_000L
            )
        )

        assertThat(dao.upserted).hasSize(2)
        val changed = payload(dao.stored.getValue("aaps-carb-82"))
        assertThat(changed["aapsVersion"].asInt).isEqualTo(2)
        assertThat(changed["aapsCarbIsValid"].asBoolean).isFalse()
        assertThat(changed["carbs"].asDouble).isEqualTo(0.0)
    }

    @Test
    fun sameNonEmptyNotesWithinToleranceEnrichesExistingPrimaryKey() = runTest {
        val existing = entity(
            id = "existing-notes",
            timestamp = ROW_TS + 1_999L,
            payload = """{"carbs":20.0,"notes":"same meal"}"""
        )
        val dao = RecordingTherapyDao(existing)

        AapsCarbHistoryImporter(dao.proxy).importPage(
            page(row(id = 52L, amount = 20.009, notes = "same meal"))
        )

        assertThat(dao.stored.keys).containsExactly("existing-notes")
        assertThat(payload(dao.stored.getValue("existing-notes"))["aapsCarbId"].asLong)
            .isEqualTo(52L)
    }

    @Test
    fun truncatedNightscoutIdDigestReconcilesCandidateWithFullPrimaryKey() = runTest {
        val fullNightscoutId = "remote-nightscout-treatment-identifier-12345"
        val dao = RecordingTherapyDao(
            entity(
                id = fullNightscoutId,
                timestamp = ROW_TS,
                payload = """{"carbs":14.0}"""
            )
        )

        AapsCarbHistoryImporter(dao.proxy).importPage(
            page(
                row(
                    id = 57L,
                    amount = 14.0,
                    nightscoutId = fullNightscoutId.take(12),
                    nightscoutIdSha256 = sha256(fullNightscoutId),
                    nightscoutIdTruncated = true
                )
            )
        )

        assertThat(dao.stored.keys).containsExactly(fullNightscoutId)
        val persisted = payload(dao.stored.getValue(fullNightscoutId))
        assertThat(persisted["aapsCarbId"].asLong).isEqualTo(57L)
        assertThat(persisted["nightscoutId"].asString).isEqualTo(fullNightscoutId)
    }

    @Test
    fun truncatedNotesDigestReconcilesCandidateWithFullNotes() = runTest {
        val fullNotes = "meal imported from a full unbounded Nightscout treatment note"
        val existing = entity(
            id = "full-notes-candidate",
            timestamp = ROW_TS,
            payload = """{"carbs":17.0,"notes":"$fullNotes"}"""
        )
        val dao = RecordingTherapyDao(existing)

        AapsCarbHistoryImporter(dao.proxy).importPage(
            page(
                row(
                    id = 58L,
                    amount = 17.0,
                    notes = fullNotes.take(16),
                    notesSha256 = sha256(fullNotes),
                    notesTruncated = true
                )
            )
        )

        assertThat(dao.stored.keys).containsExactly("full-notes-candidate")
        val persisted = payload(dao.stored.getValue("full-notes-candidate"))
        assertThat(persisted["aapsCarbId"].asLong).isEqualTo(58L)
        assertThat(persisted["notes"].asString).isEqualTo(fullNotes)
    }

    @Test
    fun truncatedIdentityWithWrongDigestDoesNotMatchPrefixOrFullValue() = runTest {
        val fullNightscoutId = "remote-nightscout-treatment-identifier-wrong-digest"
        val dao = RecordingTherapyDao(
            entity(
                id = fullNightscoutId,
                timestamp = ROW_TS,
                payload = """{"carbs":13.0}"""
            )
        )

        AapsCarbHistoryImporter(dao.proxy).importPage(
            page(
                row(
                    id = 59L,
                    amount = 13.0,
                    nightscoutId = fullNightscoutId.take(12),
                    nightscoutIdSha256 = sha256("different-full-identifier"),
                    nightscoutIdTruncated = true
                )
            )
        )

        assertThat(dao.stored.keys).containsExactly(fullNightscoutId, "aaps-carb-59")
    }

    @Test
    fun candidateDigestCannotSubstituteForFullTruncatedIdentity() = runTest {
        val incomingFullNotes = "incoming full notes identity"
        val existing = entity(
            id = "candidate-with-stale-digest",
            timestamp = ROW_TS,
            payload = """
                {
                  "carbs":13.0,
                  "notes":"different full notes",
                  "notesSha256":"${sha256(incomingFullNotes)}"
                }
            """.trimIndent()
        )
        val dao = RecordingTherapyDao(existing)

        AapsCarbHistoryImporter(dao.proxy).importPage(
            page(
                row(
                    id = 62L,
                    amount = 13.0,
                    notes = incomingFullNotes.take(12),
                    notesSha256 = sha256(incomingFullNotes),
                    notesTruncated = true
                )
            )
        )

        assertThat(dao.stored.keys)
            .containsExactly("candidate-with-stale-digest", "aaps-carb-62")
    }

    @Test
    fun truncatedIdentityDoesNotPreserveUnverifiedCandidateFullValue() = runTest {
        val incomingFullNotes = "verified incoming full notes identity"
        val incomingTruncatedNotes = incomingFullNotes.take(12)
        val existing = entity(
            id = "nightscout-match",
            timestamp = ROW_TS,
            payload = """
                {
                  "carbs":13.0,
                  "notes":"different full notes",
                  "notesSha256":"${sha256(incomingFullNotes)}"
                }
            """.trimIndent()
        )
        val dao = RecordingTherapyDao(existing)

        AapsCarbHistoryImporter(dao.proxy).importPage(
            page(
                row(
                    id = 63L,
                    amount = 13.0,
                    notes = incomingTruncatedNotes,
                    notesSha256 = sha256(incomingFullNotes),
                    notesTruncated = true,
                    nightscoutId = "nightscout-match"
                )
            )
        )

        val persisted = payload(dao.stored.getValue("nightscout-match"))
        assertThat(persisted["notes"].asString).isEqualTo(incomingTruncatedNotes)
        assertThat(persisted["notesTruncated"].asBoolean).isTrue()
    }

    @Test
    fun nullIncomingIdentityFieldPreservesExistingEnrichedValue() = runTest {
        val byNightscout = entity(
            id = "remote-preserve-notes",
            timestamp = ROW_TS,
            payload = """{"carbs":16.0,"notes":"existing full notes"}"""
        )
        val byNotes = entity(
            id = "preserve-nightscout-id",
            timestamp = ROW_TS + 60_000L,
            payload = """
                {"carbs":19.0,"notes":"notes identity","nightscoutId":"existing-full-ns-id"}
            """.trimIndent()
        )
        val dao = RecordingTherapyDao(byNightscout, byNotes)

        AapsCarbHistoryImporter(dao.proxy).importPage(
            page(
                row(
                    id = 60L,
                    amount = 16.0,
                    nightscoutId = "remote-preserve-notes"
                ),
                row(
                    id = 61L,
                    timestamp = ROW_TS + 60_000L,
                    amount = 19.0,
                    notes = "notes identity"
                )
            )
        )

        assertThat(payload(dao.stored.getValue("remote-preserve-notes"))["notes"].asString)
            .isEqualTo("existing full notes")
        assertThat(
            payload(dao.stored.getValue("preserve-nightscout-id"))["nightscoutId"].asString
        ).isEqualTo("existing-full-ns-id")
    }

    @Test
    fun uamTagReconcilesExistingUamEngineEvent() = runTest {
        val tag = "UAM_ENGINE|id=episode|seq=2|ver=2|mode=NORMAL|"
        val existing = entity(
            id = "existing-uam",
            timestamp = ROW_TS,
            payload = """
                {"carbs":"8.5","classification":"UAM_SYNTHETIC","notes":"$tag","source":"uam_engine"}
            """.trimIndent()
        )
        val dao = RecordingTherapyDao(existing)

        AapsCarbHistoryImporter(dao.proxy).importPage(
            page(row(id = 53L, amount = 8.5, notes = tag))
        )

        assertThat(dao.stored.keys).containsExactly("existing-uam")
        val reconciled = payload(dao.stored.getValue("existing-uam"))
        assertThat(reconciled["classification"].asString).isEqualTo("UAM_SYNTHETIC")
        assertThat(reconciled["aapsCarbId"].asLong).isEqualTo(53L)
    }

    @Test
    fun sameAmountAndNotesOutsideWindowRemainSeparate() = runTest {
        val existing = entity(
            id = "older-meal",
            timestamp = ROW_TS - 2_001L,
            payload = """{"carbs":12.0,"notes":"repeat meal"}"""
        )
        val dao = RecordingTherapyDao(existing)

        AapsCarbHistoryImporter(dao.proxy).importPage(
            page(row(id = 54L, amount = 12.0, notes = "repeat meal"))
        )

        assertThat(dao.stored.keys).containsExactly("older-meal", "aaps-carb-54")
        assertThat(dao.candidateWindows).containsExactly(ROW_TS - 2_000L to ROW_TS + 2_000L)
    }

    @Test
    fun malformedCandidateJsonDoesNotCrashOrFalselyMatch() = runTest {
        val malformed = entity(
            id = "broken",
            timestamp = ROW_TS,
            payload = """{"carbs":10.0,"notes":"""
        )
        val dao = RecordingTherapyDao(malformed)

        AapsCarbHistoryImporter(dao.proxy).importPage(
            page(row(id = 55L, amount = 10.0, notes = "same"))
        )

        assertThat(dao.stored.keys).containsExactly("broken", "aaps-carb-55")
    }

    @Test
    fun malformedAapsCarbIdsFallBackToGramsAndIdentityMatching() = runTest {
        val scenarios = listOf(
            Triple(70L, "70.5", "fractional"),
            Triple(71L, "7.1e1", "exponent"),
            Triple(72L, "-72", "negative"),
            Triple(73L, "0", "zero"),
            Triple(74L, "9223372036854775808", "overflow"),
            Triple(75L, "\"75x\"", "non-digit")
        )
        val initial = scenarios.mapIndexed { index, (id, persistedId, label) ->
            entity(
                id = "malformed-$label",
                timestamp = ROW_TS + index * 60_000L,
                payload = """
                    {"aapsCarbId":$persistedId,"carbs":10.0,"notes":"identity-$id"}
                """.trimIndent()
            )
        }
        val dao = RecordingTherapyDao(*initial.toTypedArray())

        AapsCarbHistoryImporter(dao.proxy).importPage(
            page(
                *scenarios.mapIndexed { index, (id, _, _) ->
                    row(
                        id = id,
                        timestamp = ROW_TS + index * 60_000L,
                        amount = 10.0,
                        notes = "identity-$id"
                    )
                }.toTypedArray()
            )
        )

        scenarios.forEach { (id, _, label) ->
            assertThat(payload(dao.stored.getValue("malformed-$label"))["aapsCarbId"].asLong)
                .isEqualTo(id)
            assertThat(dao.stored).doesNotContainKey("aaps-carb-$id")
        }
    }

    @Test
    fun fractionalAndExponentAapsCarbIdsCannotBypassGramsAndIdentity() = runTest {
        val fractional = entity(
            id = "fractional-shortcut",
            timestamp = ROW_TS,
            payload = """{"aapsCarbId":76.9,"carbs":999.0,"notes":"wrong"}"""
        )
        val exponent = entity(
            id = "exponent-shortcut",
            timestamp = ROW_TS + 60_000L,
            payload = """{"aapsCarbId":7.7e1,"carbs":999.0,"notes":"wrong"}"""
        )
        val dao = RecordingTherapyDao(fractional, exponent)

        AapsCarbHistoryImporter(dao.proxy).importPage(
            page(
                row(id = 76L, amount = 10.0, notes = "expected"),
                row(
                    id = 77L,
                    timestamp = ROW_TS + 60_000L,
                    amount = 10.0,
                    notes = "expected"
                )
            )
        )

        assertThat(dao.stored.keys).containsAtLeast(
            "fractional-shortcut",
            "exponent-shortcut",
            "aaps-carb-76",
            "aaps-carb-77"
        )
        assertThat(payload(dao.stored.getValue("fractional-shortcut"))["carbs"].asDouble)
            .isEqualTo(999.0)
        assertThat(payload(dao.stored.getValue("exponent-shortcut"))["carbs"].asDouble)
            .isEqualTo(999.0)
    }

    @Test
    fun digitStringAapsCarbIdCannotBypassGramsAndIdentity() = runTest {
        val digitString = entity(
            id = "digit-string-shortcut",
            timestamp = ROW_TS,
            payload = """{"aapsCarbId":"76","carbs":999.0,"notes":"wrong"}"""
        )
        val dao = RecordingTherapyDao(digitString)

        AapsCarbHistoryImporter(dao.proxy).importPage(
            page(row(id = 76L, amount = 10.0, notes = "expected"))
        )

        assertThat(dao.stored.keys)
            .containsAtLeast("digit-string-shortcut", "aaps-carb-76")
        assertThat(payload(dao.stored.getValue("digit-string-shortcut"))["carbs"].asDouble)
            .isEqualTo(999.0)
    }

    @Test
    fun payloadIsDeterministicAndRetainsBoundedAapsAuditMetadata() = runTest {
        val dao = RecordingTherapyDao()
        val importer = AapsCarbHistoryImporter(dao.proxy)
        val imported = row(
            id = 56L,
            version = 7,
            dateCreated = ROW_TS - 5_000L,
            isValid = false,
            referenceId = 41L,
            duration = 30 * 60_000L,
            amount = 9.75,
            notes = "bounded notes",
            nightscoutId = "remote-56"
        )

        importer.importPage(page(imported))
        val first = dao.stored.getValue("aaps-carb-56").payloadJson
        importer.importPage(page(imported))
        val second = dao.stored.getValue("aaps-carb-56").payloadJson

        assertThat(second).isEqualTo(first)
        val persisted = JsonParser.parseString(second).asJsonObject
        assertThat(persisted["aapsCarbId"].asLong).isEqualTo(56L)
        assertThat(persisted["aapsVersion"].asInt).isEqualTo(7)
        assertThat(persisted["aapsDateCreated"].asLong).isEqualTo(ROW_TS - 5_000L)
        assertThat(persisted["aapsReferenceId"].asLong).isEqualTo(41L)
        assertThat(persisted["aapsTimestamp"].asLong).isEqualTo(ROW_TS)
        assertThat(persisted["aapsDuration"].asLong).isEqualTo(30 * 60_000L)
        assertThat(persisted["notesSha256"].asString).isEqualTo(sha256("bounded notes"))
        assertThat(persisted["notesTruncated"].asBoolean).isFalse()
        assertThat(persisted["nightscoutIdSha256"].asString).isEqualTo(sha256("remote-56"))
        assertThat(persisted["nightscoutIdTruncated"].asBoolean).isFalse()
        assertThat(persisted["aapsRevisionId"].asLong).isEqualTo(REVISION_ID)
        assertThat(persisted["aapsPageGeneratedAt"].asLong).isEqualTo(GENERATED_AT)
    }

    @Test
    fun daoCandidateQueryIsBoundedToCarbTypesAndOrderedByTimestampThenId() {
        val source = File(
            "src/main/kotlin/io/aaps/copilot/data/local/dao/TherapyDao.kt"
        ).readText()
        val query = source
            .substringBefore("suspend fun carbCandidates")
            .substringAfterLast("@Query(")

        assertThat(query).contains("type IN ('carbs','meal_bolus')")
        assertThat(query).contains("timestamp BETWEEN :fromTs AND :toTs")
        assertThat(query).contains("ORDER BY timestamp,id")
    }

    @Test
    fun importerDeclaresInjectedTransactionRunnerContract() {
        val source = File(
            "src/main/kotlin/io/aaps/copilot/data/repository/AapsCarbHistoryImporter.kt"
        ).readText()

        assertThat(source).contains("fun interface AapsCarbImportTransactionRunner")
        assertThat(source).contains("private val transactionRunner")
        assertThat(source).contains("DirectAapsCarbImportTransactionRunner")
    }

    private class RecordingTherapyDao(vararg initial: TherapyEventEntity) {
        val stored = linkedMapOf<String, TherapyEventEntity>().apply {
            initial.forEach { put(it.id, it) }
        }
        val upserted = mutableListOf<List<TherapyEventEntity>>()
        val candidateWindows = mutableListOf<Pair<Long, Long>>()
        val daoCalls = mutableListOf<String>()
        var onDaoCall: ((String) -> Unit)? = null
        var failCandidateCall: Int? = null
        private var candidateCalls = 0

        val proxy: TherapyDao = Proxy.newProxyInstance(
            TherapyDao::class.java.classLoader,
            arrayOf(TherapyDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "carbCandidates" -> {
                    daoCalls += method.name
                    onDaoCall?.invoke(method.name)
                    candidateCalls += 1
                    check(candidateCalls != failCandidateCall) {
                        "Injected candidate failure"
                    }
                    val fromTs = args[0] as Long
                    val toTs = args[1] as Long
                    candidateWindows += fromTs to toTs
                    stored.values
                        .filter {
                            it.type in setOf("carbs", "meal_bolus") &&
                                it.timestamp in fromTs..toTs
                        }
                        .sortedWith(compareBy<TherapyEventEntity> { it.timestamp }.thenBy { it.id })
                }

                "upsertAll" -> {
                    daoCalls += method.name
                    onDaoCall?.invoke(method.name)
                    @Suppress("UNCHECKED_CAST")
                    val rows = args[0] as List<TherapyEventEntity>
                    upserted += rows
                    rows.forEach { stored[it.id] = it }
                    Unit
                }

                "toString" -> "RecordingTherapyDao"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> args[0] === this
                else -> error("Unexpected TherapyDao call: ${method.name}")
            }
        } as TherapyDao

        fun snapshot(): Map<String, TherapyEventEntity> = stored.toMap()

        fun restore(snapshot: Map<String, TherapyEventEntity>) {
            stored.clear()
            stored.putAll(snapshot)
        }
    }

    private class RecordingTransactionRunner {
        var calls = 0
        var active = false

        val runner = AapsCarbImportTransactionRunner { block ->
            check(!active)
            calls += 1
            active = true
            try {
                block()
            } finally {
                active = false
            }
        }
    }

    private class RollbackTransactionRunner(
        private val dao: RecordingTherapyDao
    ) {
        var observedFailure: Throwable? = null
        var rollbacks = 0

        val runner = AapsCarbImportTransactionRunner { block ->
            val snapshot = dao.snapshot()
            try {
                block()
            } catch (error: Throwable) {
                observedFailure = error
                rollbacks += 1
                dao.restore(snapshot)
                throw error
            }
        }
    }

    private fun page(vararg rows: AapsCarbHistoryRow) = AapsCarbHistoryPage(
        rows = rows.toList(),
        nextTimestamp = rows.lastOrNull()?.timestamp ?: 0L,
        nextId = rows.lastOrNull()?.id ?: 0L,
        hasMore = false,
        generatedAt = GENERATED_AT,
        revisionId = REVISION_ID
    )

    private fun pageAtRevision(
        vararg rows: AapsCarbHistoryRow,
        generatedAt: Long,
        revisionId: Long
    ) = AapsCarbHistoryPage(
        rows = rows.toList(),
        nextTimestamp = rows.lastOrNull()?.timestamp ?: 0L,
        nextId = rows.lastOrNull()?.id ?: 0L,
        hasMore = false,
        generatedAt = generatedAt,
        revisionId = revisionId
    )

    private fun row(
        id: Long,
        version: Int = 1,
        dateCreated: Long = ROW_TS - 1_000L,
        isValid: Boolean = true,
        referenceId: Long? = null,
        timestamp: Long = ROW_TS,
        duration: Long = 0L,
        amount: Double,
        notes: String? = null,
        notesSha256: String? = notes?.let(::sha256),
        notesTruncated: Boolean = false,
        nightscoutId: String? = null,
        nightscoutIdSha256: String? = nightscoutId?.let(::sha256),
        nightscoutIdTruncated: Boolean = false
    ) = AapsCarbHistoryRow(
        id = id,
        version = version,
        dateCreated = dateCreated,
        isValid = isValid,
        referenceId = referenceId,
        timestamp = timestamp,
        duration = duration,
        amount = amount,
        notes = notes,
        notesSha256 = notesSha256,
        notesTruncated = notesTruncated,
        nightscoutId = nightscoutId,
        nightscoutIdSha256 = nightscoutIdSha256,
        nightscoutIdTruncated = nightscoutIdTruncated
    )

    private fun entity(
        id: String,
        timestamp: Long,
        type: String = "carbs",
        payload: String
    ) = TherapyEventEntity(
        id = id,
        timestamp = timestamp,
        type = type,
        payloadJson = payload
    )

    private fun payload(entity: TherapyEventEntity): JsonObject =
        JsonParser.parseString(entity.payloadJson).asJsonObject

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val ROW_TS = 1_785_283_200_000L
        const val GENERATED_AT = ROW_TS + 10_000L
        const val REVISION_ID = 91L
    }
}
