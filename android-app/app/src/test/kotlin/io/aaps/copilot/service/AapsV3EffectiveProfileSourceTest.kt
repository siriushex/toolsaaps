package io.aaps.copilot.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.repository.*
import java.util.UUID
import kotlinx.coroutines.*
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
class AapsV3EffectiveProfileSourceTest {
    private lateinit var context: Context
    private lateinit var db: CopilotDatabase
    private lateinit var name: String
    private val at = 1_800_000_000_000L
    private fun open() = Room.databaseBuilder(context, CopilotDatabase::class.java, name).allowMainThreadQueries().build()
    private fun store() = LocalNightscoutV3Store(db, { at + 100_000 })
    private fun input(id: String, time: Long = at, value: Double = 5.5) = LocalV3DocumentInput(
        LocalV3Collection.TREATMENTS, id, "local-v3-owner", time, buildJsonObject {
            put("date", time); put("utcOffset", 240); put("eventType", "Note")
            put("originalProfileName", "Historical"); put("originalCustomizedName", "Historical 120%")
            put("originalTimeshift", -3_600_000); put("originalPercentage", 120)
            put("originalDuration", 1000); put("originalEnd", time + 1000)
            put("icfg", buildJsonObject {
                put("insulinLabel", "historical insulin"); put("insulinEndTime", 18_000_000)
                put("insulinPeakTime", 4_500_000); put("concentration", 1.0)
            })
            put("profileJson", buildJsonObject {
                put("units", "mmol"); put("timezone", "Asia/Tbilisi")
                listOf("carbratio", "sens", "basal", "target_low", "target_high").forEach { key ->
                    put(key, buildJsonArray {
                        add(buildJsonObject { put("time", "00:00"); put("timeAsSeconds", 0); put("value", value) })
                    })
                }
            }.toString())
        }.toString())
    private fun observed(read: AapsV3EffectiveProfileRead): AapsV3EffectiveProfileObservation {
        assertEquals(AapsV3ObservedProfileStatus.OBSERVED, read.status)
        return checkNotNull(read.observation)
    }
    private fun status(read: AapsV3EffectiveProfileRead, expected: AapsV3ObservedProfileStatus) {
        assertEquals(expected, read.status)
        assertNull(read.observation)
    }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        name = "profile-source-${UUID.randomUUID()}.db"
        db = open()
    }
    @After fun tearDown() { db.close(); context.deleteDatabase(name) }

    @Test fun selectsByEffectiveEventTimeNotUploadRevisionAndNeverRevertsAtOriginalEnd() = runBlocking {
        val later = store().create(input("later", at + 10_000, 6.5)).document
        store().create(input("earlier", at, 5.5))
        val source = AapsV3EffectiveProfileSource(store())
        status(source.read(at - 1), AapsV3ObservedProfileStatus.NOT_OBSERVED)
        assertEquals("earlier", observed(source.read(at)).identifier)
        assertEquals("earlier", observed(source.read(at + 9999)).identifier)
        assertEquals(later, store().get(LocalV3Collection.TREATMENTS, "later"))
        val result = source.read(at + 10_000)
        assertEquals("later", observed(result).identifier)
        assertEquals(6.5, observed(result).profile.sensitivity.single().value, 0.0)
        assertEquals("later", observed(source.read(at + 86_400_000)).identifier)
        assertEquals(store().lastModified()[LocalV3Collection.TREATMENTS], result.retainedRevision)
    }

    @Test fun correctionsAndTimeMovesReplaceTheIdentityAndInvalidateTheCachedProjection() = runBlocking {
        val first = store().create(input("first")).document
        val second = store().create(input("second", at + 10_000)).document
        val source = AapsV3EffectiveProfileSource(store())
        val original = observed(source.read(at + 20_000))
        assertSame(original, observed(source.read(at + 20_001)))
        store().update(input("second", at - 10_000, 7.5), second.modifiedRevision)
        assertEquals(first.identifier, observed(source.read(at + 20_000)).identifier)
        val moved = observed(source.read(at - 1))
        assertEquals("second", moved.identifier)
        assertEquals(7.5, moved.profile.carbRatio.single().value, 0.0)
        assertTrue(moved.modifiedRevision > original.modifiedRevision)
    }

    @Test fun latestDeletionBlocksFallbackAndSurvivesDatabaseReopen() = runBlocking {
        store().create(input("earlier"))
        val last = store().create(input("last", at + 10_000)).document
        val source = AapsV3EffectiveProfileSource(store())
        observed(source.read(at + 20_000))
        val tombstone = store().delete(LocalV3Collection.TREATMENTS, "last", last.modifiedRevision).document
        status(source.read(at + 20_000), AapsV3ObservedProfileStatus.INVALIDATED)
        assertEquals("earlier", observed(source.read(at + 9999)).identifier)
        db.close(); db = open()
        val reopened = AapsV3EffectiveProfileSource(store()).read(at + 20_000)
        status(reopened, AapsV3ObservedProfileStatus.INVALIDATED)
        assertEquals(tombstone.modifiedRevision, reopened.retainedRevision)
    }

    @Test fun conflictingSameTimeProfilesAreNotChosenByIdentityRevisionOrScheduleEquality() = runBlocking {
        store().create(input("first"))
        val last = store().create(input("second")).document
        val source = AapsV3EffectiveProfileSource(store())
        status(source.read(at), AapsV3ObservedProfileStatus.CONFLICTING)
        store().delete(LocalV3Collection.TREATMENTS, "second", last.modifiedRevision)
        status(source.read(at), AapsV3ObservedProfileStatus.CONFLICTING)
        store().create(input("later", at + 10_000))
        assertEquals("later", observed(source.read(at + 10_000)).identifier)
    }

    @Test fun malformedLatestProfileBlocksFallbackButLaterValidEvidenceCanResolve() = runBlocking {
        store().create(input("first"))
        val bad = input("bad", at + 10_000)
        store().create(bad.copy(payloadJson = JsonObject(Json.parseToJsonElement(bad.payloadJson).jsonObject - "icfg").toString()))
        val source = AapsV3EffectiveProfileSource(store())
        status(source.read(at + 10_000), AapsV3ObservedProfileStatus.INVALID)
        assertEquals("first", observed(source.read(at)).identifier)
        store().create(input("later", at + 20_000))
        assertEquals("later", observed(source.read(at + 20_000)).identifier)
    }

    @Test fun catalogRequestedSwitchAndOrdinaryTreatmentsAreNotEffectiveProfileEvidence() = runBlocking {
        store().create(LocalV3DocumentInput(LocalV3Collection.PROFILE, "catalog", "local-v3-owner", at, "{\"defaultProfile\":\"Historical\"}"))
        val sample = input("request")
        val body = Json.parseToJsonElement(sample.payloadJson).jsonObject
        store().create(sample.copy(payloadJson = JsonObject(body + ("eventType" to JsonPrimitive("Profile Switch"))).toString()))
        store().create(input("note").copy(payloadJson = "{\"eventType\":\"Note\",\"date\":$at}"))
        store().create(input("meal").copy(payloadJson = JsonObject(body + ("carbs" to JsonPrimitive(24))).toString()))
        status(AapsV3EffectiveProfileSource(store()).read(at), AapsV3ObservedProfileStatus.NOT_OBSERVED)
        assertTrue(db.glucoseDao().since(0).isEmpty())
        assertTrue(db.therapyDao().since(0).isEmpty())
    }

    @Test fun exceededSnapshotLimitsAreExplicitRatherThanAnApparentlyCompletePartialProfile() = runBlocking {
        store().create(input("first"))
        store().create(input("last", at + 10_000))
        status(AapsV3EffectiveProfileSource(store(), maxRows = 1).read(at + 20_000), AapsV3ObservedProfileStatus.LIMIT_REACHED)
        status(AapsV3EffectiveProfileSource(store(), maxPayloadBytes = 100).read(at + 20_000), AapsV3ObservedProfileStatus.LIMIT_REACHED)
        observed(AapsV3EffectiveProfileSource(store(), maxRows = 2).read(at + 20_000))
        Unit
    }

    @Test fun invalidQueryTimeAndReadFailuresDoNotReturnCachedClinicalValues() = runBlocking {
        store().create(input("first"))
        val source = AapsV3EffectiveProfileSource(store())
        val read = source.read(at)
        observed(read)
        assertFalse(read.toString().contains("Historical"))
        status(source.read(0), AapsV3ObservedProfileStatus.INVALID)
        status(source.read(LocalNightscoutV3Store.MAX_TIME + 1), AapsV3ObservedProfileStatus.INVALID)
        db.close()
        status(source.read(at), AapsV3ObservedProfileStatus.ERROR)
    }

    @Test fun cancelledReadPropagatesCancellationInsteadOfReportingMissingHistory() = runBlocking {
        var cancellationObserved = false
        val job = launch {
            currentCoroutineContext().cancel()
            try { AapsV3EffectiveProfileSource(store()).read(at); fail("Expected cancellation") }
            catch (_: CancellationException) { cancellationObserved = true }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(cancellationObserved)
    }

    @Test fun corruptSnapshotProvenanceCannotSupplyPartialProfileEvidence() = runBlocking {
        val row = store().create(input("first")).document
        val invalid = listOf(row.copy(collection = "profile"), row.copy(source = "other-source"),
            row.copy(eventAtMs = null), row.copy(eventAtMs = 0), row.copy(createdRevision = 0),
            row.copy(modifiedRevision = row.modifiedRevision + 1))
        invalid.forEach {
            status(AapsV3EffectiveProfileTimeline(LocalV3RetainedSnapshot.Complete(row.modifiedRevision, listOf(it))).read(at),
                AapsV3ObservedProfileStatus.INVALID)
        }
        status(AapsV3EffectiveProfileTimeline(LocalV3RetainedSnapshot.Complete(row.modifiedRevision, listOf(row, row))).read(at),
            AapsV3ObservedProfileStatus.INVALID)
    }
}
