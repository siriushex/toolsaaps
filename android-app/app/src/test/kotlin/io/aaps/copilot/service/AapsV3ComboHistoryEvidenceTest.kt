package io.aaps.copilot.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.repository.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AapsV3ComboHistoryEvidenceTest {
    private lateinit var context: Context
    private lateinit var db: CopilotDatabase
    private lateinit var name: String
    private val at = 1_800_000_000_000L
    private var now = at + 100_000
    private fun open() = Room.databaseBuilder(context, CopilotDatabase::class.java, name).allowMainThreadQueries().build()
    private fun store() = LocalNightscoutV3Store(db, { now++ })
    private fun journal(vararg changes: Pair<String, JsonElement>) = JsonObject(buildJsonObject {
        put("version", 1); put("coverage", "unverified"); put("state", "STORED")
        put("retainedReads", 2); put("unclosedReads", 0); put("corruptReads", 0)
        put("adverseReads", 0); put("failedReads", 0); put("unscopedCorruptReads", 0)
    } + changes)
    private fun observation(vararg changes: Pair<String, JsonElement>) = JsonObject(buildJsonObject {
        put("version", 1); put("scope", "reader_session"); put("coverage", "unverified"); put("state", "RETURNED")
        put("readStartedAt", at - 1000); put("readFinishedAt", at)
        put("validatedBlocks", 1); put("confirmedBlocks", 1); put("parsedEvents", 3); put("unmappedEvents", 0)
        listOf("gapObserved", "clockChangeObserved", "unmappedEventObserved", "interruptedReadObserved", "malformedBlockObserved",
            "journalWriteFailed").forEach { put(it, false) }
    } + changes)
    private fun status(time: Long, value: JsonElement, key: String, device: String = "fixture-device") = buildJsonObject {
        put("date", time); put("device", device)
        putJsonObject("pump") { putJsonObject("extended") { put(key, value) } }
    }
    private suspend fun upload(time: Long = at, value: JsonElement = journal(), key: String = "ComboHistoryJournal", device: String = "fixture-device"): Long {
        val result = LocalNightscoutV3EntriesApi(db) { now++ }.handle("POST", "/api/v3/devicestatus", emptyMap()) {
            status(time, value, key, device).toString().toByteArray()
        }
        assertEquals(201, result.status)
        return checkNotNull(result.revision)
    }
    private suspend fun read(time: Long = at + 10_000, known: Long = now) =
        AapsV3ComboHistoryTimeline(store().retainedSnapshot(LocalV3Collection.DEVICE_STATUS, 10_000, 4L * 1024 * 1024)).read(time, known)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        name = "history-evidence-${UUID.randomUUID()}.db"
        db = open()
    }
    @After fun tearDown() { db.close(); context.deleteDatabase(name) }

    @Test fun cleanJournalNeverProvesDeliveryOrCurrentPumpBinding() = runBlocking {
        assertEquals(AapsV3ComboHistoryStatus.NOT_OBSERVED, read().status)
        upload()
        val result = read()
        assertEquals(AapsV3ComboHistoryStatus.RETAINED_UNVERIFIED, result.status)
        assertEquals(1, result.observedSnapshots)
        assertFalse(result.deliveryCompletenessVerified)
        assertFalse(result.pumpBindingVerified)
        assertTrue(result.hazards.isEmpty())
        assertFalse(result.toString().contains("fixture-device"))
    }

    @Test fun adverseHistorySurvivesCleanSnapshotsAndReopenWithoutSummingCumulativeCounts() = runBlocking {
        val gap = observation("gapObserved" to JsonPrimitive(true))
        upload(at, gap, "ComboHistoryRead")
        upload(at + 1, gap, "ComboHistoryRead")
        upload(at + 2, observation(), "ComboHistoryRead", "another-producer")
        db.close(); db = open()
        val result = read()
        assertEquals(AapsV3ComboHistoryStatus.ADVERSE, result.status)
        assertEquals(3, result.observedSnapshots)
        assertEquals(setOf(AapsV3ComboHistoryHazard.FIFO_GAP), result.hazards)
        assertThrows(UnsupportedOperationException::class.java) { (result.hazards as MutableSet).clear() }
        assertFalse(result.pumpBindingVerified)
    }

    @Test fun minimalStorageFailureAndUnscopedCorruptionRemainDistinct() = runBlocking {
        upload(value = buildJsonObject { put("version", 1); put("coverage", "unverified"); put("state", "FAILED") })
        upload(at + 1, journal("unscopedCorruptReads" to JsonPrimitive(1)))
        assertEquals(setOf(AapsV3ComboHistoryHazard.STORAGE_FAILED, AapsV3ComboHistoryHazard.UNSCOPED_CORRUPTION), read().hazards)
    }

    @Test fun normalInProgressSnapshotIsNotLatchedAsAFailedRead() = runBlocking {
        upload(value = journal("state" to JsonPrimitive("WRITING"), "unclosedReads" to JsonPrimitive(1)))
        upload(at + 1, JsonObject(observation("state" to JsonPrimitive("READING"), "confirmedBlocks" to JsonPrimitive(0)) - "readFinishedAt"), "ComboHistoryRead")
        upload(at + 2)
        assertEquals(AapsV3ComboHistoryStatus.RETAINED_UNVERIFIED, read().status)
        upload(at + 3, journal("state" to JsonPrimitive("WRITING"), "unclosedReads" to JsonPrimitive(2)))
        assertEquals(setOf(AapsV3ComboHistoryHazard.UNCLOSED_READ), read().hazards)
    }

    @Test fun unmappedEventsAreIndependentOfParsedEventsAndBackwardsClockIsRetained() = runBlocking {
        upload(value = observation("parsedEvents" to JsonPrimitive(0), "unmappedEvents" to JsonPrimitive(5),
            "readFinishedAt" to JsonPrimitive(at - 2000)), key = "ComboHistoryRead")
        assertEquals(setOf(AapsV3ComboHistoryHazard.UNMAPPED_EVENT, AapsV3ComboHistoryHazard.CLOCK_CHANGE), read().hazards)
    }

    @Test fun lateRevisionAndBoundsDoNotReturnPartialEvidence() = runBlocking {
        val revision = upload()
        assertEquals(AapsV3ComboHistoryStatus.LATE_EVIDENCE, read(known = revision - 1).status)
        upload(at + 1)
        val limited = AapsV3ComboHistoryTimeline(store().retainedSnapshot(LocalV3Collection.DEVICE_STATUS, 1, 4L * 1024 * 1024)).read(at + 100, now)
        assertEquals(AapsV3ComboHistoryStatus.LIMIT_REACHED, limited.status)
        assertEquals(0, limited.observedSnapshots)
    }

    @Test fun malformedFutureDiagnosticsCannotDisappearOutsideWindow() = runBlocking {
        upload()
        upload(at + 86_400_000, journal("coverage" to JsonPrimitive("complete")))
        assertEquals(AapsV3ComboHistoryStatus.INVALID, read().status)
        assertEquals(0, read().observedSnapshots)
    }

    @Test fun versionTypesMissingFieldsAndContradictoryCountsAreNotInterpretedAsClean() = runBlocking {
        val original = store().create(LocalV3DocumentInput(LocalV3Collection.DEVICE_STATUS, "invalid-test", "local-v3-owner", at,
            status(at, journal(), "ComboHistoryJournal").toString())).document
        var revision = original.modifiedRevision
        val invalid = listOf(
            journal("version" to JsonPrimitive(2)), journal("retainedReads" to JsonPrimitive(-1)),
            journal("retainedReads" to JsonPrimitive("2")), journal("failedReads" to JsonPrimitive(3)),
            journal("corruptReads" to JsonPrimitive(1)), journal("state" to JsonPrimitive("WRITING")),
            JsonObject(journal() - "unscopedCorruptReads"), journal("unknown" to JsonPrimitive(true)), JsonNull
        )
        for (value in invalid) {
            revision = store().update(LocalV3DocumentInput(LocalV3Collection.DEVICE_STATUS, original.identifier, "local-v3-owner", at,
                status(at, value, "ComboHistoryJournal").toString()), revision).document.modifiedRevision
            assertEquals(AapsV3ComboHistoryStatus.INVALID, read().status)
        }
    }

    @Test fun clinicalOwnerRefreshesJournalWithoutTreatmentChangesAndRetainsFailureAfterReopen() = runBlocking {
        val source = AapsV3EffectiveProfileSource(store())
        assertEquals(AapsV3ComboHistoryStatus.NOT_OBSERVED, source.readEvidence(at - 1000, at + 10_000, now).comboHistory.status)
        upload(value = journal("failedReads" to JsonPrimitive(1)))
        val before = source.readEvidence(at - 1000, at + 10_000, now)
        assertEquals(AapsV3ComboHistoryStatus.ADVERSE, before.comboHistory.status)
        upload(at + 1)
        assertEquals(AapsV3ComboHistoryStatus.ADVERSE, source.readEvidence(at - 1000, at + 10_000, now).comboHistory.status)
        db.close(); db = open()
        val after = AapsV3EffectiveProfileSource(store()).readEvidence(at - 1000, at + 10_000, now)
        assertEquals(before.comboHistory.hazards, after.comboHistory.hazards)
        assertFalse(after.comboHistory.deliveryCompletenessVerified)
        assertTrue(db.therapyDao().since(0).isEmpty())
        assertTrue(db.actionCommandDao().latest(10).isEmpty())
    }

    @Test fun allNativeAdverseFlagsAndTerminalConfirmationMismatchArePreserved() = runBlocking {
        upload(value = observation("state" to JsonPrimitive("FAILED"), "confirmedBlocks" to JsonPrimitive(0),
            "gapObserved" to JsonPrimitive(true), "clockChangeObserved" to JsonPrimitive(true),
            "unmappedEventObserved" to JsonPrimitive(true), "interruptedReadObserved" to JsonPrimitive(true),
            "malformedBlockObserved" to JsonPrimitive(true), "journalWriteFailed" to JsonPrimitive(true)), key = "ComboHistoryRead")
        assertEquals(setOf(AapsV3ComboHistoryHazard.FAILED_READ, AapsV3ComboHistoryHazard.FIFO_GAP,
            AapsV3ComboHistoryHazard.CLOCK_CHANGE, AapsV3ComboHistoryHazard.UNMAPPED_EVENT,
            AapsV3ComboHistoryHazard.INTERRUPTED_READ, AapsV3ComboHistoryHazard.MALFORMED_BLOCK,
            AapsV3ComboHistoryHazard.STORAGE_FAILED, AapsV3ComboHistoryHazard.UNCONFIRMED_BLOCK), read().hazards)
    }

    @Test fun readSchemaTypesAndTimeAliasesCannotManufactureCleanEvidence() = runBlocking {
        val invalid = listOf(
            observation("scope" to JsonPrimitive("complete_history")), observation("gapObserved" to JsonPrimitive("false")),
            observation("confirmedBlocks" to JsonPrimitive(2)), observation("unmappedEvents" to JsonPrimitive(-1)),
            observation("readStartedAt" to JsonPrimitive("1")), observation("state" to JsonPrimitive("READY")),
            JsonObject(observation() - "readStartedAt"), JsonObject(observation() - "readFinishedAt"),
            JsonObject(observation() - "journalWriteFailed"), observation("unknown" to JsonPrimitive(false))
        )
        for ((i, value) in invalid.withIndex()) {
            val document = store().create(LocalV3DocumentInput(LocalV3Collection.DEVICE_STATUS, "read-$i", "local-v3-owner", at,
                status(at, value, "ComboHistoryRead").toString())).document
            val timeline = AapsV3ComboHistoryTimeline(LocalV3RetainedSnapshot.Complete(document.modifiedRevision, listOf(document)))
            assertEquals(AapsV3ComboHistoryStatus.INVALID, timeline.read(at, now).status)
        }
        val valid = store().create(LocalV3DocumentInput(LocalV3Collection.DEVICE_STATUS, "valid", "local-v3-owner", at,
            status(at, journal(), "ComboHistoryJournal").toString())).document
        val rows = listOf(valid.copy(source = "another-owner"), valid.copy(eventAtMs = at - 1), valid.copy(deleted = true),
            valid.copy(payloadBytes = 1), valid.copy(createdRevision = valid.modifiedRevision + 1),
            valid.copy(payloadJson = valid.payloadJson.dropLast(1) + ",\"date\":$at}"))
        for (row in rows) assertEquals(AapsV3ComboHistoryStatus.INVALID,
            AapsV3ComboHistoryTimeline(LocalV3RetainedSnapshot.Complete(row.modifiedRevision, listOf(row))).read(at, now).status)
    }

    @Test fun validFutureStatusIsExcludedAndUnrelatedStatusIsNotComboEvidence() = runBlocking {
        upload(at + 20_000, journal("failedReads" to JsonPrimitive(1)))
        upload(value = buildJsonObject { put("enabled", true) }, key = "UnrelatedDiagnostic")
        assertEquals(AapsV3ComboHistoryStatus.NOT_OBSERVED, read().status)
        assertEquals(AapsV3ComboHistoryStatus.ADVERSE, read(at + 20_000).status)
    }

    @Test fun ownerLimitsAndStorageFailureDoNotReuseCachedCleanEvidence() = runBlocking {
        val source = AapsV3EffectiveProfileSource(store(), maxRows = 1)
        upload()
        assertEquals(AapsV3ComboHistoryStatus.RETAINED_UNVERIFIED, source.readEvidence(at - 1, at + 10_000, now).comboHistory.status)
        upload(at + 1)
        assertEquals(AapsV3ComboHistoryStatus.LIMIT_REACHED, source.readEvidence(at - 1, at + 10_000, now).comboHistory.status)
        db.close()
        assertEquals(AapsV3ComboHistoryStatus.ERROR, source.readEvidence(at - 1, at + 10_000, now).comboHistory.status)
        db = open()
    }
}
