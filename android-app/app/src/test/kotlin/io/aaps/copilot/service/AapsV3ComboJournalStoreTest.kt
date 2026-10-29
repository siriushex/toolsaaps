package io.aaps.copilot.service

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.ComboJournalDatabase
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AapsV3ComboJournalStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var db: ComboJournalDatabase
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val id = "33333333-3333-4333-8333-333333333333"
    private val binding = "a".repeat(64)
    private val otherBinding = "b".repeat(64)
    private val flags = listOf("gapObserved", "clockChangeObserved", "unmappedEventObserved",
        "interruptedReadObserved", "malformedBlockObserved", "journalWriteFailed")
    private val databaseFile get() = File(context.noBackupFilesDir, ComboJournalDatabase.FILE_NAME)

    @Before fun setup() {
        val directory = temporary.newFolder("private")
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir() = directory
            override fun getApplicationContext(): Context = this
        }
        db = ComboJournalDatabase.create(context)
    }
    @After fun teardown() { db.close() }
    private fun store(maximumReads: Int = 4096, maximumBytes: Long = 32L * 1024 * 1024) =
        AapsV3ComboJournalStore(db, maximumReads, maximumBytes)
    private fun hash(bytes: ByteArray) = AapsV3ComboJournalAssembly.digest(bytes)
    private suspend fun retain(bytes: ByteArray, readId: String = id) = store().retain(owner, binding, readId, hash(bytes), bytes)
    private suspend fun rejected(reason: ComboRetentionError, block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected bounded retention failure, got ${error?.javaClass?.simpleName}", error is ComboRetentionFailure)
        assertEquals(reason, (error as ComboRetentionFailure).reason)
        assertNull(error.cause)
        assertEquals("combo_retention_${reason.name.lowercase()}", error.message)
    }
    private fun frame(phase: String, scope: String = binding, events: List<ByteArray>? = null): ByteArray {
        val details = events?.map { ComboJournalEvent.decode(it).detail }
        val unknown = details?.count { it is ComboEventDetail.Unknown } ?: 0
        val parsed = details?.size?.minus(unknown) ?: 1
        val raw = buildJsonObject {
            put("version", 1); put("binding", scope); put("phase", phase)
            put("observation", buildJsonObject {
                put("version", 1); put("scope", "reader_session"); put("coverage", "unverified")
                put("state", if (phase == "END") "RETURNED" else "READING")
                put("readStartedAt", 1000); if (phase == "END") put("readFinishedAt", 2000)
                put("validatedBlocks", if (phase == "BEGIN") 0 else 1)
                put("confirmedBlocks", if (phase in setOf("CONFIRMED", "END")) 1 else 0)
                put("parsedEvents", if (phase == "BEGIN") 0 else parsed)
                put("unmappedEvents", if (phase == "BEGIN") 0 else unknown)
                flags.forEach { flag -> put(flag, phase != "BEGIN" && when (flag) {
                    "clockChangeObserved" -> details?.any { it is ComboEventDetail.ClockChange } == true
                    "unmappedEventObserved" -> unknown > 0
                    else -> false
                }) }
            })
            // Unchanged public upstream synthetic vector, not a device record.
            if (phase == "BLOCK") put("payloadHex", if (events == null) "00000100b7b701540e93541d000000070073490f3b01000807" else
                (byteArrayOf(0, 0, 0, 0, 0xb7.toByte(), 0xb7.toByte(), events.size.toByte()) +
                    events.fold(byteArrayOf()) { bytes, event -> bytes + event }).joinToString("") { "%02x".format(it.toInt() and 255) })
        }.toString().toByteArray()
        return ByteArrayOutputStream().also { out -> DataOutputStream(out).use {
            it.writeInt(raw.size); it.write(raw); it.writeInt(CRC32().apply { update(raw) }.value.toInt())
        } }.toByteArray()
    }
    private fun readBytes(scope: String = binding, events: List<ByteArray>? = null, acknowledged: Boolean = true) =
        (if (acknowledged) listOf("BEGIN", "BLOCK", "CONFIRMED", "END") else listOf("BEGIN", "BLOCK"))
            .fold(byteArrayOf()) { bytes, phase -> bytes + frame(phase, scope, events) }
    private fun largeRead(size: Int = 150_000): ByteArray = frame("BEGIN").let { it + ByteArray(size - it.size) }
    private fun entry(bytes: ByteArray = readBytes(), readId: String = id) =
        AapsV3ComboInventoryEntry(readId, bytes.size, hash(bytes), true, false, false, false)
    private fun catalog(entries: List<AapsV3ComboInventoryEntry> = listOf(entry()),
                        scope: String = binding, unscoped: Int = 0, writerFailed: Boolean = false) =
        AapsV3ComboInventory.create("44444444-4444-4444-8444-444444444444", scope,
            entries.sortedBy { it.readId }, unscoped, writerFailed)

    private fun event(counter: Long, hour: Int = 12, type: Int = 7, amount: Int = 22, replacementHour: Int = 9): ByteArray =
        ByteArray(18).also { bytes ->
            fun time(offset: Int, h: Int) {
                // 2026-10-24 at hh:30:15; packed native local clock, without a zone.
                bytes[offset] = (15 or (2 shl 6)).toByte()
                bytes[offset + 1] = (7 or ((h and 15) shl 4)).toByte()
                bytes[offset + 2] = ((h ushr 4) or (24 shl 1) or (2 shl 6)).toByte()
                bytes[offset + 3] = (2 or (26 shl 2)).toByte()
            }
            time(0, hour)
            if (type == 24) time(4, replacementHour) else { bytes[4] = amount.toByte(); bytes[5] = (amount ushr 8).toByte() }
            bytes[8] = type.toByte(); bytes[9] = (type ushr 8).toByte()
            repeat(4) { bytes[12 + it] = (counter ushr (8 * it)).toByte() }
            for ((start, end, dest) in listOf(Triple(0, 10, 10), Triple(12, 16, 16))) {
                // Forward-polynomial reference with reflected input and output.
                var crc = 0xffff
                for (i in start until end) {
                    crc = crc xor ((Integer.reverse(bytes[i].toInt() and 255) ushr 24) shl 8)
                    repeat(8) { crc = ((crc shl 1) xor if (crc and 0x8000 != 0) 0x1021 else 0) and 0xffff }
                }
                crc = Integer.reverse(crc) ushr 16
                bytes[dest] = crc.toByte(); bytes[dest + 1] = (crc ushr 8).toByte()
            }
        }
    private fun eventAudit(vararg reads: ByteArray): ComboRetainedEventAudit = ComboRetainedEventAuditBuilder().run {
        reads.forEach { accept(AapsV3ComboJournalRead.read(it, binding)) }
        finish()
    }

    @Test fun exactCrossReadRepeatsCountOnceWithoutInventingDeliveryOrUtcCoverage() {
        val raw = readBytes(events = listOf(event(10)))
        val audit = eventAudit(raw, raw)
        assertEquals(2, audit.observations); assertEquals(1, audit.distinctCounters)
        assertEquals(1, audit.exactRepeatObservations); assertEquals(0, audit.conflictingCounters)
        assertEquals(1, audit.bolusPhaseCounts.getValue(ComboRecordedBolusPhase.INFUSED))
        assertEquals(0, audit.interiorCounterGaps); assertTrue(audit.hazards.isEmpty())
        assertFalse(audit.pumpBindingVerified); assertFalse(audit.clockMappingVerified); assertFalse(audit.deliveryCompletenessVerified)
        assertFalse(audit.toString().contains("10"))
        val empty = eventAudit()
        assertEquals(0, empty.observations); assertFalse(empty.deliveryCompletenessVerified)
    }

    @Test fun conflictingCountersAreOrderIndependentAndExcludedFromBolusPhaseCounts() {
        val first = readBytes(events = listOf(event(10, amount = 10)))
        val second = readBytes(events = listOf(event(10, amount = 20)))
        for (audit in listOf(eventAudit(first, second, second), eventAudit(second, second, first))) {
            assertEquals(3, audit.observations); assertEquals(1, audit.distinctCounters)
            assertEquals(1, audit.conflictingCounters); assertEquals(0, audit.exactRepeatObservations)
            assertEquals(0, audit.bolusPhaseCounts.values.sum())
            assertTrue(AapsV3ComboHistoryHazard.EVENT_COUNTER_REUSED in audit.hazards)
        }
    }

    @Test fun counterGapsAcrossSeparateCleanReadsCannotBeHiddenByExactCatalogMatch() = runBlocking {
        val first = readBytes(events = listOf(event(10)))
        val last = readBytes(events = listOf(event(13)))
        retain(first); retain(last, other)
        val audit = store().reconcileInventory(owner, catalog(listOf(entry(first), entry(last, other))))
        assertTrue(audit.declaredInventoryRetained)
        assertEquals(1, audit.events.interiorCounterGaps); assertEquals(2L, audit.events.missingInteriorCounterValues)
        assertTrue(AapsV3ComboHistoryHazard.EVENT_COUNTER_GAP in audit.hazards)
        assertFalse(audit.deliveryCompletenessVerified)
    }

    @Test fun localTimeRewindIsDetectedBetweenReadsButEqualLocalTimesRemainValid() {
        val laterCounter = readBytes(events = listOf(event(11, hour = 11)))
        val earlierCounter = readBytes(events = listOf(event(10, hour = 12)))
        for (audit in listOf(eventAudit(laterCounter, earlierCounter), eventAudit(earlierCounter, laterCounter))) {
            assertEquals(1, audit.backwardsLocalTimePairs)
            assertTrue(AapsV3ComboHistoryHazard.EVENT_TIME_REWIND in audit.hazards)
            assertFalse(audit.clockMappingVerified)
        }
        val equal = eventAudit(earlierCounter, readBytes(events = listOf(event(11))))
        assertEquals(0, equal.backwardsLocalTimePairs); assertTrue(equal.hazards.isEmpty())
    }

    @Test fun clockChangeIsRetainedWithoutShiftingTimesOrInferringNewEpoch() {
        val audit = eventAudit(readBytes(events = listOf(event(10, hour = 13, type = 24))),
            readBytes(events = listOf(event(11, hour = 9))))
        assertEquals(1, audit.clockChangeCounters); assertEquals(1, audit.backwardsLocalTimePairs)
        assertTrue(AapsV3ComboHistoryHazard.CLOCK_CHANGE in audit.hazards)
        assertFalse(audit.clockMappingVerified)
    }

    @Test fun conflictingClockCannotBeUsedAsAnchorForAnAdjacentTimeComparison() {
        val audit = eventAudit(readBytes(events = listOf(event(10, hour = 13))),
            readBytes(events = listOf(event(11, hour = 12))), readBytes(events = listOf(event(11, hour = 10))),
            readBytes(events = listOf(event(12, hour = 11))))
        assertEquals(1, audit.conflictingCounters); assertEquals(0, audit.backwardsLocalTimePairs)
        assertFalse(audit.clockMappingVerified)
    }

    @Test fun forwardClockSettingCannotHideSubsequentRewindWithinOrBetweenReads() {
        val setting = event(10, hour = 12, type = 24, replacementHour = 15)
        val following = event(11, hour = 14)
        val together = AapsV3ComboJournalRead.read(readBytes(events = listOf(setting, following)), binding)
        assertEquals(ComboJournalIssue.NONE, together.issue)
        assertTrue(AapsV3ComboHistoryHazard.POST_CLOCK_CHANGE_REWIND in together.hazards)
        val first = readBytes(events = listOf(setting))
        val second = readBytes(events = listOf(following))
        for (audit in listOf(eventAudit(first, second), eventAudit(second, first), eventAudit(first, first, second))) {
            assertEquals(0, audit.backwardsLocalTimePairs)
            assertEquals(1, audit.postClockChangeRewinds)
            assertTrue(AapsV3ComboHistoryHazard.POST_CLOCK_CHANGE_REWIND in audit.hazards)
            assertFalse(audit.clockMappingVerified); assertFalse(audit.deliveryCompletenessVerified)
        }
    }

    @Test fun nextClockBoundaryUsesReplacementWithoutRepairingRawTimes() {
        val setting = event(10, hour = 13, type = 24, replacementHour = 9)
        for (hour in listOf(9, 10)) {
            val following = event(11, hour = hour)
            val raw = readBytes(events = listOf(setting, following))
            val parsed = AapsV3ComboJournalRead.read(raw, binding)
            assertArrayEquals(raw, parsed.rawBytes())
            assertEquals(hour, parsed.events.last().event.localTime.hour)
            val audit = eventAudit(raw)
            assertEquals(0, audit.postClockChangeRewinds)
            assertTrue(AapsV3ComboHistoryHazard.CLOCK_CHANGE in audit.hazards)
            assertTrue(AapsV3ComboHistoryHazard.EVENT_TIME_REWIND in audit.hazards)
            assertFalse(audit.clockMappingVerified)
        }
    }

    @Test fun gapsAndConflictingSettingsCannotSupplyAnAdjacentClockBoundary() {
        val first = readBytes(events = listOf(event(10, type = 24, replacementHour = 15)))
        val conflicting = readBytes(events = listOf(event(10, type = 24, replacementHour = 16)))
        val next = readBytes(events = listOf(event(11, hour = 14)))
        assertEquals(0, eventAudit(first, conflicting, next).postClockChangeRewinds)
        assertEquals(0, eventAudit(first, readBytes(events = listOf(event(12, hour = 14)))).postClockChangeRewinds)
        assertEquals(0, eventAudit(readBytes(events = listOf(event(0xffff_ffffL, type = 24, replacementHour = 15))),
            readBytes(events = listOf(event(0, hour = 14)))).postClockChangeRewinds)
    }

    @Test fun consecutiveClockSettingsCheckTheIncomingClockButDoNotGrantAnEpoch() {
        val audit = eventAudit(readBytes(events = listOf(event(10, type = 24, replacementHour = 15))),
            readBytes(events = listOf(event(11, hour = 14, type = 24, replacementHour = 9))),
            readBytes(events = listOf(event(12, hour = 8))))
        assertEquals(2, audit.postClockChangeRewinds)
        assertEquals(2, audit.clockChangeCounters)
        assertFalse(audit.clockMappingVerified)
    }

    @Test fun unknownEventsAndUnsignedCounterWrapCannotLookLikeCompleteHistory() {
        val unknown = eventAudit(readBytes(events = listOf(event(10))), readBytes(events = listOf(event(11, type = 99))),
            readBytes(events = listOf(event(12))))
        assertEquals(1, unknown.unknownEventCounters); assertEquals(0, unknown.interiorCounterGaps)
        assertTrue(AapsV3ComboHistoryHazard.UNMAPPED_EVENT in unknown.hazards)
        val wrap = eventAudit(readBytes(events = listOf(event(0xffff_ffffL))), readBytes(events = listOf(event(0))))
        assertEquals(1, wrap.interiorCounterGaps); assertEquals(0xffff_fffeL, wrap.missingInteriorCounterValues)
        assertFalse(wrap.deliveryCompletenessVerified); assertFalse(wrap.clockMappingVerified)
    }

    @Test fun laterAcknowledgementDoesNotEraseEarlierUnconfirmedEvidence() {
        val unconfirmed = readBytes(events = listOf(event(10)), acknowledged = false)
        val confirmed = readBytes(events = listOf(event(10)))
        val first = eventAudit(unconfirmed)
        assertEquals(1, first.unacknowledgedObservations); assertEquals(1, first.countersWithoutAcknowledgedCopy)
        val audit = eventAudit(unconfirmed, confirmed)
        assertEquals(1, audit.unacknowledgedObservations); assertEquals(0, audit.countersWithoutAcknowledgedCopy)
        assertEquals(1, audit.exactRepeatObservations)
        assertTrue(AapsV3ComboHistoryHazard.UNCONFIRMED_BLOCK in audit.hazards)
        assertFalse(audit.deliveryCompletenessVerified)
    }

    @Test fun requestsAndRecordedInfusionAreSeparateWithoutInventingBolusMatches() {
        val audit = eventAudit(readBytes(events = listOf(event(10, type = 6))),
            readBytes(events = listOf(event(11, type = 7))), readBytes(events = listOf(event(12, type = 8))),
            readBytes(events = listOf(event(13, type = 9))))
        ComboRecordedBolusPhase.entries.forEach { assertEquals(1, audit.bolusPhaseCounts.getValue(it)) }
        assertFalse(audit.deliveryCompletenessVerified)
    }

    @Test fun auditCollectionsAreImmutableAndCapacityNeverReturnsPartialSuccess() {
        val builder = ComboRetainedEventAuditBuilder(maximumCounters = 1)
        builder.accept(AapsV3ComboJournalRead.read(readBytes(events = listOf(event(10))), binding))
        val failure = assertThrows(ComboRetentionFailure::class.java) {
            builder.accept(AapsV3ComboJournalRead.read(readBytes(events = listOf(event(11))), binding))
        }
        assertEquals(ComboRetentionError.CAPACITY, failure.reason)
        assertThrows(IllegalStateException::class.java) { builder.finish() }
        val audit = eventAudit(readBytes(events = listOf(event(10))))
        assertThrows(UnsupportedOperationException::class.java) { (audit.hazards as MutableSet).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (audit.bolusPhaseCounts as MutableMap).clear() }
    }

    @Test fun omittedOlderConflictSurvivesReopenAndNeverChangesRawRetainedBytes() = runBlocking {
        val first = readBytes(events = listOf(event(10, amount = 10)))
        val second = readBytes(events = listOf(event(10, amount = 20)))
        retain(first); retain(second, other)
        db.close(); db = ComboJournalDatabase.create(context)
        val audit = store().reconcileInventory(owner, catalog(listOf(entry(second, other))))
        assertTrue(audit.declaredInventoryRetained); assertEquals(1, audit.retainedOutsideInventory)
        assertEquals(1, audit.events.conflictingCounters)
        assertArrayEquals(first, store().read(owner, binding, id)!!.rawBytes())
        assertArrayEquals(second, store().read(owner, binding, other)!!.rawBytes())
    }

    @Test fun otherOwnerOrPumpCannotFillInteriorCounterGap() = runBlocking {
        val first = readBytes(events = listOf(event(10)))
        val last = readBytes(events = listOf(event(12)))
        val middle = readBytes(events = listOf(event(11)))
        retain(first); retain(last, other)
        store().retain(other, binding, id, hash(middle), middle)
        val alien = readBytes(otherBinding, listOf(event(11)))
        store().retain(owner, otherBinding, id, hash(alien), alien)
        val audit = store().reconcileInventory(owner, catalog(listOf(entry(first), entry(last, other))))
        assertEquals(2, audit.events.distinctCounters); assertEquals(1L, audit.events.missingInteriorCounterValues)
    }

    @Test fun completeDeclaredInventorySurvivesReopenWithoutGrantingDeliveryAuthority() = runBlocking {
        val raw = readBytes(); retain(raw)
        val expected = catalog()
        db.close(); db = ComboJournalDatabase.create(context)
        val audit = store().reconcileInventory(owner, expected)
        assertEquals(expected.snapshotId, audit.snapshotId); assertEquals(expected.sha256, audit.inventorySha256)
        assertEquals(1, audit.expectedReadCount); assertEquals(1, audit.matchedReadCount)
        assertEquals(0, audit.retainedOutsideInventory)
        assertTrue(audit.declaredInventoryRetained); assertTrue(audit.hazards.isEmpty())
        assertFalse(audit.pumpBindingVerified); assertFalse(audit.deliveryCompletenessVerified)
        assertEquals(1L, db.journalDao().totals().reads)
        assertArrayEquals(raw, store().read(owner, binding, id)!!.rawBytes())
        assertFalse(audit.toString().contains(id)); assertFalse(audit.toString().contains(binding))
    }

    @Test fun missingReadIsNotFilledByAnotherOwnerOrBinding() = runBlocking {
        val raw = readBytes(); val otherRaw = readBytes(otherBinding)
        store().retain(other, binding, id, hash(raw), raw)
        store().retain(owner, otherBinding, id, hash(otherRaw), otherRaw)
        val audit = store().reconcileInventory(owner, catalog())
        assertEquals(listOf(id), audit.missingReadIds)
        assertEquals(0, audit.matchedReadCount); assertFalse(audit.declaredInventoryRetained)
        assertTrue(audit.conflictingReadIds.isEmpty()); assertEquals(2L, db.journalDao().totals().reads)
    }

    @Test fun partialInventoryReportsExactMissingReadsAndCannotMutateItsResult() = runBlocking<Unit> {
        retain(readBytes())
        val expected = catalog(listOf(entry(), entry(readId = other)))
        val audit = store().reconcileInventory(owner, expected)
        assertEquals(2, audit.expectedReadCount); assertEquals(1, audit.matchedReadCount)
        assertEquals(listOf(other), audit.missingReadIds); assertFalse(audit.declaredInventoryRetained)
        assertThrows(UnsupportedOperationException::class.java) { (audit.missingReadIds as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (audit.conflictingReadIds as MutableList).add(id) }
        assertThrows(UnsupportedOperationException::class.java) {
            (audit.hazards as MutableSet).add(AapsV3ComboHistoryHazard.STORAGE_FAILED)
        }
    }

    @Test fun conflictingHashOrSizeDoesNotOverwriteStoredIdentity() = runBlocking {
        retain(readBytes())
        for (changed in listOf(entry().copy(sha256 = "0".repeat(64)), entry().copy(byteCount = readBytes().size + 1))) {
            val audit = store().reconcileInventory(owner, catalog(listOf(changed)))
            assertEquals(listOf(id), audit.conflictingReadIds); assertTrue(audit.missingReadIds.isEmpty())
            assertEquals(0, audit.matchedReadCount); assertFalse(audit.declaredInventoryRetained)
            assertArrayEquals(readBytes(), store().read(owner, binding, id)!!.rawBytes())
        }
    }

    @Test fun sourceAdverseFlagsRemainVisibleEvenForMissingOrFullyStoredReads() = runBlocking {
        val declared = entry().copy(closed = false, corrupt = true, adverse = true, readFailed = true)
        val expected = catalog(listOf(declared), unscoped = 1, writerFailed = true)
        val before = store().reconcileInventory(owner, expected)
        retain(readBytes())
        val after = store().reconcileInventory(owner, expected)
        assertFalse(before.declaredInventoryRetained); assertTrue(after.declaredInventoryRetained)
        val hazards = setOf(AapsV3ComboHistoryHazard.UNCLOSED_READ, AapsV3ComboHistoryHazard.CORRUPT_READ,
            AapsV3ComboHistoryHazard.ADVERSE_READ, AapsV3ComboHistoryHazard.FAILED_READ,
            AapsV3ComboHistoryHazard.UNSCOPED_CORRUPTION, AapsV3ComboHistoryHazard.STORAGE_FAILED)
        assertEquals(hazards, before.hazards); assertEquals(hazards, after.hazards)
        assertFalse(after.deliveryCompletenessVerified)
    }

    @Test fun decoderHazardsAndOlderOmittedReadsSurviveCleanSourceCatalog() = runBlocking {
        val partial = frame("BEGIN") + frame("BLOCK")
        retain(partial); retain(readBytes(), other)
        val audit = store().reconcileInventory(owner, catalog(listOf(entry(readId = other))))
        assertTrue(audit.declaredInventoryRetained); assertEquals(1, audit.retainedOutsideInventory)
        assertTrue(audit.hazards.containsAll(setOf(AapsV3ComboHistoryHazard.UNCLOSED_READ,
            AapsV3ComboHistoryHazard.UNCONFIRMED_BLOCK)))
        assertFalse(audit.deliveryCompletenessVerified)
        val sameRead = store().reconcileInventory(owner, catalog(listOf(entry(partial))))
        assertTrue(sameRead.declaredInventoryRetained); assertEquals(audit.hazards, sameRead.hazards)
    }

    @Test fun emptyCatalogIsNotEvidenceOfNoInsulinOrOfACompleteSource() = runBlocking {
        val expected = catalog(emptyList())
        val audit = store().reconcileInventory(owner, expected)
        assertTrue(audit.declaredInventoryRetained); assertEquals(0, audit.expectedReadCount)
        assertEquals(0, audit.matchedReadCount); assertFalse(audit.deliveryCompletenessVerified)
        retain(frame("BEGIN"))
        val later = store().reconcileInventory(owner, expected)
        assertTrue(later.declaredInventoryRetained); assertEquals(1, later.retainedOutsideInventory)
        assertTrue(AapsV3ComboHistoryHazard.UNCLOSED_READ in later.hazards)
    }

    @Test fun damagedStoredBytesRefuseWholeReconciliationEvenOutsideCurrentCatalog() = runBlocking {
        retain(readBytes()); retain(readBytes(), other)
        db.openHelper.writableDatabase.execSQL("UPDATE combo_chunks SET bytes = zeroblob(length(bytes)) WHERE readId = ?", arrayOf(id))
        rejected(ComboRetentionError.CORRUPT) { store().reconcileInventory(owner, catalog(listOf(entry(readId = other)))) }
    }

    @Test fun reconciliationCannotEscapeAnExternalOrInheritedTransaction() = runBlocking {
        retain(readBytes())
        db.withTransaction {
            rejected(ComboRetentionError.STORAGE) { store().reconcileInventory(owner, catalog()) }
            withContext(Dispatchers.IO) {
                rejected(ComboRetentionError.STORAGE) { store().reconcileInventory(owner, catalog()) }
            }
        }
        assertTrue(store().reconcileInventory(owner, catalog()).declaredInventoryRetained)
    }

    @Test fun reconciliationRespectsGlobalCapacityWithoutPruningOtherScopes() = runBlocking {
        retain(readBytes())
        val raw = readBytes(); store().retain(other, binding, id, hash(raw), raw)
        rejected(ComboRetentionError.CAPACITY) { store(1).reconcileInventory(owner, catalog()) }
        rejected(ComboRetentionError.CAPACITY) { store(maximumBytes = raw.size.toLong()).reconcileInventory(owner, catalog()) }
        assertEquals(2L, db.journalDao().totals().reads)
    }

    @Test fun exactBytesAndTypedEventsSurviveDatabaseCloseAndReopen() = runBlocking {
        val raw = readBytes()
        val receipt = retain(raw)
        assertTrue(receipt.newlyStored); assertEquals(hash(raw), receipt.sha256)
        assertEquals(raw.size, receipt.byteCount)
        assertEquals(AapsV3ComboHistoryStatus.RETAINED_UNVERIFIED, receipt.status)
        assertFalse(receipt.pumpBindingVerified); assertFalse(receipt.deliveryCompletenessVerified)
        raw.fill(0)
        db.close(); db = ComboJournalDatabase.create(context)
        val retained = store().read(owner, binding, id)!!
        assertArrayEquals(readBytes(), retained.rawBytes())
        val bolus = retained.events.single().event.detail as ComboEventDetail.Bolus
        assertEquals(29, bolus.recordedAmountTenths)
        assertEquals(ComboRecordedBolusPhase.INFUSED, bolus.phase)
        assertTrue(retained.events.single().historyBlockAcknowledged)
        assertFalse(retained.deliveryCompletenessVerified)
    }

    @Test fun matchingReplayReturnsOneReceiptWithoutAdditionalRowsIncludingAtCapacity() = runBlocking {
        val raw = readBytes(); val s = store(1, raw.size.toLong())
        assertTrue(s.retain(owner, binding, id, hash(raw), raw).newlyStored)
        assertFalse(s.retain(owner, binding, id, hash(raw), raw).newlyStored)
        db.close(); db = ComboJournalDatabase.create(context)
        assertFalse(store(1, raw.size.toLong()).retain(owner, binding, id, hash(raw), raw).newlyStored)
        assertEquals(1L, db.journalDao().totals().reads)
        assertEquals(raw.size.toLong(), db.journalDao().totals().storedBytes)
    }

    @Test fun externalTransactionCannotReceiveANewReceiptBeforeItsCommit() = runBlocking {
        db.withTransaction {
            rejected(ComboRetentionError.STORAGE) { retain(readBytes()) }
            assertEquals(0L, db.journalDao().totals().reads)
        }
        assertTrue(store().inventory(owner, binding).isEmpty())
        assertTrue(retain(readBytes()).newlyStored)
    }

    @Test fun externalTransactionCannotReceiveReplayReceipt() = runBlocking {
        retain(readBytes())
        db.withTransaction {
            rejected(ComboRetentionError.STORAGE) { retain(readBytes()) }
        }
        assertFalse(retain(readBytes()).newlyStored)
    }

    @Test fun externalTransactionCannotExposeUncommittedRead() = runBlocking {
        retain(readBytes())
        db.withTransaction {
            rejected(ComboRetentionError.STORAGE) { store().read(owner, binding, id) }
        }
        assertArrayEquals(readBytes(), store().read(owner, binding, id)!!.rawBytes())
    }

    @Test fun externalTransactionCannotExposeInventoryReceipts() = runBlocking {
        retain(readBytes())
        db.withTransaction {
            rejected(ComboRetentionError.STORAGE) { store().inventory(owner, binding) }
        }
        assertEquals(1, store().inventory(owner, binding).size)
    }

    @Test fun changingDispatcherDoesNotEscapeExternalTransactionBoundary() = runBlocking {
        retain(readBytes())
        db.withTransaction {
            withContext(Dispatchers.IO) {
                rejected(ComboRetentionError.STORAGE) { retain(readBytes(), other) }
                rejected(ComboRetentionError.STORAGE) { store().read(owner, binding, id) }
                rejected(ComboRetentionError.STORAGE) { store().inventory(owner, binding) }
            }
        }
        assertNull(store().read(owner, binding, other))
        assertEquals(1, store().inventory(owner, binding).size)
    }

    @Test fun changedReplayCannotReplaceEvenAnUnclosedRead() = runBlocking {
        val partial = frame("BEGIN"); retain(partial)
        rejected(ComboRetentionError.CONFLICT) { retain(readBytes()) }
        assertArrayEquals(partial, store().read(owner, binding, id)!!.rawBytes())
        assertFalse(store().read(owner, binding, id)!!.closed)
    }

    @Test fun ownerAndPumpBindingScopeSameReadIdentifierIndependently() = runBlocking {
        val a = readBytes(); val b = readBytes(otherBinding)
        retain(a)
        assertNull(store().read(other, binding, id)); assertTrue(store().inventory(other, binding).isEmpty())
        assertNull(store().read(owner, otherBinding, id))
        store().retain(other, binding, id, hash(a), a)
        store().retain(owner, otherBinding, id, hash(b), b)
        assertEquals(3L, db.journalDao().totals().reads)
        assertEquals(1, store().inventory(owner, binding).size)
        assertArrayEquals(b, store().read(owner, otherBinding, id)!!.rawBytes())
    }

    @Test fun malformedManifestAndUnscopedOrMixedSourceBytesNeverWrite() = runBlocking {
        val raw = readBytes()
        rejected(ComboRetentionError.INVALID) { store().retain("../owner", binding, id, hash(raw), raw) }
        rejected(ComboRetentionError.INVALID) { store().retain(owner, binding, "../read", hash(raw), raw) }
        rejected(ComboRetentionError.INVALID) { store().retain(owner, "", id, hash(raw), raw) }
        rejected(ComboRetentionError.INVALID) { store().retain(owner, binding, id, "0".repeat(64), raw) }
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0, 1), readBytes(otherBinding),
            frame("BEGIN") + frame("BLOCK", otherBinding), ByteArray(AapsV3ComboJournalRead.MAX_READ_BYTES + 1))) {
            rejected(ComboRetentionError.INVALID) { retain(bytes) }
        }
        assertEquals(0L, db.journalDao().totals().reads)
    }

    @Test fun scopedCorruptionAndUnconfirmedEvidenceRemainDiagnosticAfterReopen() = runBlocking {
        val raw = frame("BEGIN") + frame("BLOCK") + byteArrayOf(0, 1)
        assertEquals(AapsV3ComboHistoryStatus.INVALID, retain(raw).status)
        db.close(); db = ComboJournalDatabase.create(context)
        val stored = store().read(owner, binding, id)!!
        assertArrayEquals(raw, stored.rawBytes())
        assertFalse(stored.events.single().historyBlockAcknowledged)
        assertEquals(AapsV3ComboHistoryStatus.INVALID, store().inventory(owner, binding).single().status)
        assertFalse(stored.deliveryCompletenessVerified)
    }

    @Test fun maximumReadUsesBoundedChunksAndReconstructsExactly() = runBlocking {
        val raw = largeRead(AapsV3ComboJournalRead.MAX_READ_BYTES)
        retain(raw)
        val positions = db.journalDao().positions(owner, binding, id, 33)
        assertEquals(32, positions.size); assertTrue(positions.all { it.byteCount == 65_536 })
        db.close(); db = ComboJournalDatabase.create(context)
        assertArrayEquals(raw, store().read(owner, binding, id)!!.rawBytes())
    }

    @Test fun midChunkWriteFailureRollsBackManifestAndChunksWithoutRemovingEarlierRead() = runBlocking {
        val first = readBytes(); retain(first)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER refuse_chunk BEFORE INSERT ON combo_chunks " +
            "WHEN NEW.part = 1 BEGIN SELECT RAISE(ABORT, 'synthetic_storage_refusal'); END")
        rejected(ComboRetentionError.STORAGE) { retain(largeRead(), other) }
        assertEquals(1L, db.journalDao().totals().reads)
        assertEquals(first.size.toLong(), db.journalDao().totals().storedBytes)
        assertNull(store().read(owner, binding, other))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER refuse_chunk")
        assertTrue(retain(largeRead(), other).newlyStored)
    }

    @Test fun capacityIsGlobalAcrossOwnersAndNeverPrunesOrPartiallyAcknowledges() = runBlocking {
        val raw = readBytes(); retain(raw)
        rejected(ComboRetentionError.CAPACITY) {
            store(1).retain(other, binding, id, hash(raw), raw)
        }
        rejected(ComboRetentionError.CAPACITY) {
            store(maximumBytes = raw.size * 2L - 1).retain(owner, binding, other, hash(raw), raw)
        }
        assertEquals(1L, db.journalDao().totals().reads)
        assertArrayEquals(raw, store().read(owner, binding, id)!!.rawBytes())
    }

    @Test fun equalConcurrentRetainsSerializeToOneNewReceipt() = runBlocking {
        val raw = readBytes()
        val receipts = (1..8).map { async { store().retain(owner, binding, id, hash(raw), raw) } }.awaitAll()
        assertEquals(1, receipts.count { it.newlyStored }); assertEquals(1L, db.journalDao().totals().reads)
    }

    @Test fun conflictingConcurrentRetainsKeepOnlyTheWinningOriginalBytes() = runBlocking {
        val candidates = listOf(readBytes(), frame("BEGIN"))
        val results = candidates.map { raw -> async { runCatching { retain(raw) } } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(ComboRetentionError.CONFLICT,
            (results.single { it.isFailure }.exceptionOrNull() as ComboRetentionFailure).reason)
        assertArrayEquals(candidates[results.indexOfFirst { it.isSuccess }], store().read(owner, binding, id)!!.rawBytes())
        assertEquals(1L, db.journalDao().totals().reads)
    }

    @Test fun readbackCorruptionInsideWriteTransactionReturnsNoReceiptAndRollsBack() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER alter_chunk AFTER INSERT ON combo_chunks BEGIN " +
            "UPDATE combo_chunks SET bytes = zeroblob(LENGTH(bytes)); END")
        rejected(ComboRetentionError.CORRUPT) { retain(readBytes()) }
        assertEquals(0L, db.journalDao().totals().reads)
        assertEquals(0L, db.journalDao().totals().storedBytes)
    }

    @Test fun alteredStoredBytesRefuseReadInventoryAndReplayRatherThanRepair() = runBlocking {
        val raw = readBytes(); retain(raw)
        db.openHelper.writableDatabase.execSQL("UPDATE combo_chunks SET bytes = zeroblob(LENGTH(bytes))")
        rejected(ComboRetentionError.CORRUPT) { store().read(owner, binding, id) }
        rejected(ComboRetentionError.CORRUPT) { store().inventory(owner, binding) }
        rejected(ComboRetentionError.CORRUPT) { retain(raw) }
        assertEquals(1L, db.journalDao().totals().reads)
    }

    @Test fun missingChunkRefusesEntireInventoryWithoutReturningOtherGoodRead() = runBlocking {
        retain(readBytes()); retain(largeRead(), other)
        db.openHelper.writableDatabase.execSQL("DELETE FROM combo_chunks WHERE readId = ? AND part = 1", arrayOf(other))
        rejected(ComboRetentionError.CORRUPT) { store().read(owner, binding, other) }
        rejected(ComboRetentionError.CORRUPT) { store().inventory(owner, binding) }
        rejected(ComboRetentionError.CORRUPT) { retain(readBytes(), owner) }
        assertArrayEquals(readBytes(), store().read(owner, binding, id)!!.rawBytes())
    }

    @Test fun outOfOrderChunkPositionsAndImpossibleMetadataCannotReturnBytes() = runBlocking {
        retain(readBytes())
        db.openHelper.writableDatabase.execSQL("UPDATE combo_chunks SET part = 1")
        rejected(ComboRetentionError.CORRUPT) { store().read(owner, binding, id) }
        db.openHelper.writableDatabase.execSQL("UPDATE combo_chunks SET part = 0")
        db.openHelper.writableDatabase.execSQL("UPDATE combo_reads SET byteCount = 2147483647")
        rejected(ComboRetentionError.CORRUPT) { store().read(owner, binding, id) }
    }

    @Test fun substitutedDigestCannotTurnUnscopedBytesIntoAStoredRead() = runBlocking {
        val raw = readBytes(); retain(raw)
        val damaged = ByteArray(raw.size)
        db.openHelper.writableDatabase.execSQL("UPDATE combo_chunks SET bytes = ?", arrayOf(damaged))
        db.openHelper.writableDatabase.execSQL("UPDATE combo_reads SET sha256 = ?", arrayOf(hash(damaged)))
        rejected(ComboRetentionError.CORRUPT) { store().read(owner, binding, id) }
    }

    @Test fun receiptsAndReturnedCollectionsDoNotExposeMutableStoredBytesOrPayloadStrings() = runBlocking {
        val receipt = retain(readBytes())
        store().read(owner, binding, id)!!.rawBytes().fill(0)
        assertArrayEquals(readBytes(), store().read(owner, binding, id)!!.rawBytes())
        val inventory = store().inventory(owner, binding)
        assertThrows(UnsupportedOperationException::class.java) { (inventory as MutableList).clear() }
        assertEquals("ComboRetentionReceipt(redacted)", receipt.toString())
        assertEquals("AapsV3ComboJournalStore(redacted)", store().toString())
        assertEquals("ComboReadEntity(redacted)", db.journalDao().find(owner, binding, id).toString())
    }

    @Test fun productionFactoryUsesPrivateDatabaseAndFullSynchronousRollbackJournal() = runBlocking {
        retain(readBytes())
        assertTrue(databaseFile.isFile)
        assertEquals(context.noBackupFilesDir.canonicalFile, databaseFile.parentFile!!.canonicalFile)
        db.openHelper.writableDatabase.query("PRAGMA synchronous").use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
        db.openHelper.writableDatabase.query("PRAGMA journal_mode").use { assertTrue(it.moveToFirst()); assertEquals("truncate", it.getString(0)) }
    }

    @Test fun factoryRefusesDatabaseOrSidecarSymlinksWithoutTouchingDestination() {
        db.close()
        val destination = temporary.newFile("untouched")
        destination.writeText("synthetic")
        for (suffix in listOf("", "-journal", "-wal", "-shm")) {
            val link = File(databaseFile.path + suffix).toPath()
            Files.createSymbolicLink(link, destination.toPath())
            assertThrows(IllegalStateException::class.java) { ComboJournalDatabase.create(context) }
            assertEquals("synthetic", destination.readText())
            Files.delete(link)
        }
    }

    @Test fun unsupportedDatabaseVersionFailsWithoutDestructiveRecreation() = runBlocking {
        retain(readBytes())
        db.openHelper.writableDatabase.execSQL("PRAGMA user_version=99")
        db.close(); db = ComboJournalDatabase.create(context)
        rejected(ComboRetentionError.STORAGE) { store().read(owner, binding, id) }
        db.close()
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { retained ->
            assertEquals(99, retained.version)
            retained.rawQuery("SELECT COUNT(*) FROM combo_reads", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        }
    }
}
