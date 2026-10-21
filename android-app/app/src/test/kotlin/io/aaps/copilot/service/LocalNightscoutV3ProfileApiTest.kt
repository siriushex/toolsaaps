package io.aaps.copilot.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import io.aaps.copilot.data.repository.LocalV3Collection
import io.aaps.copilot.data.repository.LocalV3DocumentInput
import java.time.Instant
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LocalNightscoutV3ProfileApiTest {
    private lateinit var db: CopilotDatabase
    private lateinit var api: LocalNightscoutV3EntriesApi
    private val now = 1_800_000_000_000L
    private val at = now - 60_000
    private fun sample(time: Long = at, units: String = "mmol", zone: String = "Europe/Berlin"): String {
        val schedule = """[{"time":"00:00","timeAsSeconds":0,"value":5.5},{"time":"06:00","timeAsSeconds":23400,"value":6.25}]"""
        val profile = """{"units":"$units","timezone":"$zone","carbratio":$schedule,"sens":$schedule,"basal":$schedule,"target_low":$schedule,"target_high":$schedule}"""
        return """{"date":$time,"created_at":"${Instant.ofEpochMilli(time)}","startDate":"${Instant.ofEpochMilli(time)}","defaultProfile":"First","app":"AAPS","store":{"First":$profile,"Second":$profile}}"""
    }
    private suspend fun call(method: String = "GET", path: String = "profile/history/0", body: String = "",
                             query: Map<String, List<String>> = emptyMap()) =
        api.handle(method, "/api/v3/$path", query) { body.toByteArray() }
    private fun body(result: LocalV3HttpResult) = Json.parseToJsonElement(result.body).jsonObject
    private fun id(result: LocalV3HttpResult) = body(result).getValue("identifier").jsonPrimitive.content
    private fun rows(result: LocalV3HttpResult) = body(result).getValue("result").jsonArray.map { it.jsonObject }
    private fun query(vararg values: Pair<String, String>) = values.toMap().mapValues { listOf(it.value) }
    private suspend fun latest() = call(path = "profile", query = query("sort\$desc" to "date", "limit" to "1"))

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
        api = LocalNightscoutV3EntriesApi(db) { now }
    }
    @After fun tearDown() = db.close()

    @Test fun nativeCatalogRoundTripsWithoutSelectingDefaultOrWritingClinicalData() = runBlocking {
        val first = call("POST", "profile", sample())
        assertEquals(201, first.status)
        val result = latest()
        val row = rows(result).single()
        assertEquals(first.revision, result.revision)
        assertEquals(Json.parseToJsonElement(sample()).jsonObject["store"], row["store"])
        assertEquals("First", row["defaultProfile"]!!.jsonPrimitive.content)
        assertEquals(23400, row["store"]!!.jsonObject["Second"]!!.jsonObject["basal"]!!.jsonArray[1].jsonObject["timeAsSeconds"]!!.jsonPrimitive.int)
        assertEquals("mmol", row["store"]!!.jsonObject["Second"]!!.jsonObject["units"]!!.jsonPrimitive.content)
        assertEquals(id(first), row["identifier"]!!.jsonPrimitive.content)
        assertEquals(row, body(call(path = "profile/${id(first)}"))["result"]!!.jsonObject)
        assertTrue(db.glucoseDao().since(0).isEmpty())
        assertTrue(db.therapyDao().since(0).isEmpty())
    }

    @Test fun repeatedCatalogAndReadbackPreserveIdentifierAndRevision() = runBlocking {
        val first = call("POST", "profile", sample())
        val retry = call("POST", "profile", " ${sample()} ")
        assertEquals(200, retry.status)
        assertEquals(first.revision, retry.revision)
        assertEquals(id(first), id(retry))
        assertTrue(body(retry)["isDeduplication"]!!.jsonPrimitive.boolean)
        val readback = rows(latest()).single()
        assertEquals(first.revision, call("POST", "profile", readback.toString()).revision)
        assertEquals(409, call("POST", "profile", sample().replace("6.25", "7.25")).status)
        assertEquals(1, db.localNightscoutV3Dao().count())
    }

    @Test fun latestUsesCatalogDateWhileHistoryUsesModificationCursor() = runBlocking {
        val first = call("POST", "profile", sample())
        val late = call("POST", "profile", sample(at - 86_400_000, "mg/dl", "Asia/Tbilisi"))
        assertEquals(at, rows(latest()).single()["date"]!!.jsonPrimitive.long)
        // A newer storage revision for an old catalog cannot be claimed by a latest-only read.
        assertEquals(first.revision, latest().revision)
        val page = call(path = "profile/history/${first.revision}", query = query("limit" to "10"))
        assertEquals(late.revision, page.revision)
        assertEquals(at - 86_400_000, rows(page).single()["date"]!!.jsonPrimitive.long)
        assertEquals(late.revision, body(call(path = "lastModified"))["result"]!!.jsonObject["collections"]!!.jsonObject["profile"]!!.jsonPrimitive.long)
    }

    @Test fun emptyAndMultipleHistoryPagesHaveExactContinuation() = runBlocking {
        assertTrue(rows(latest()).isEmpty())
        assertNull(latest().revision)
        repeat(23) { assertEquals(201, call("POST", "profile", sample(at + it)).status) }
        var cursor = 0L
        val identifiers = mutableListOf<String>()
        repeat(3) {
            val page = call(path = "profile/history/$cursor", query = query("limit" to "10"))
            identifiers += rows(page).map { it["identifier"]!!.jsonPrimitive.content }
            assertTrue(page.revision!! > cursor)
            cursor = page.revision!!
        }
        assertEquals(23, identifiers.distinct().size)
        val end = call(path = "profile/history/$cursor", query = query("limit" to "10"))
        assertTrue(rows(end).isEmpty())
        assertEquals(cursor, end.revision)
    }

    @Test fun catalogWithNoProfilesCannotInventDefaultOrInsulinConfiguration() = runBlocking {
        val empty = """{"date":$at,"store":{},"app":"AAPS"}"""
        assertEquals(201, call("POST", "profile", empty).status)
        val row = rows(latest()).single()
        assertEquals(JsonObject(emptyMap()), row["store"])
        assertFalse(row.containsKey("defaultProfile"))
        assertFalse(row.containsKey("iCfg"))
        assertEquals(400, call("POST", "profile", empty.replace("\"store\":{}", "\"store\":{},\"defaultProfile\":\"First\"")).status)
    }

    @Test fun malformedCatalogsNeverWriteOrAdvanceRevision() = runBlocking {
        val invalid = listOf("", "[]", "{}", "{\"date\":1,\"date\":2}",
            sample().replace("\"date\":$at", "\"date\":\"$at\""),
            sample().replace("\"date\":$at,", ""),
            sample().replace("\"date\":$at", "\"date\":0"),
            sample().replace("\"startDate\":\"${Instant.ofEpochMilli(at)}\"", "\"startDate\":\"2000-01-01T00:00:00Z\""),
            sample().replace("\"defaultProfile\":\"First\"", "\"defaultProfile\":\"Missing\""),
            sample().replace("\"mmol\"", "\"mmol/l\""),
            sample().replace("Europe/Berlin", "Unknown/Region"),
            sample().replace("\"timeAsSeconds\":0", "\"timeAsSeconds\":60"),
            sample().replace("\"timeAsSeconds\":23400", "\"timeAsSeconds\":0"),
            sample().replace("\"timeAsSeconds\":23400", "\"timeAsSeconds\":86400"),
            sample().replace("\"timeAsSeconds\":23400", "\"timeAsSeconds\":23400.5"),
            sample().replace("\"time\":\"06:00\"", "\"time\":\"08:00\""),
            sample().replace("\"value\":5.5", "\"value\":-1"),
            sample().replace("\"value\":5.5", "\"value\":1e999"),
            sample().replace("\"value\":5.5", "\"value\":\"5.5\""),
            sample().replace("\"value\":5.5", "\"value\":true"),
            sample().replace("\"value\":5.5", "\"value\":5.5,\"value\":6.0"),
            sample().dropLast(1) + ",\"secret\":\"fixture-value\"}",
            sample().dropLast(1) + ",\"isValid\":false}",
            sample().dropLast(1) + ",\"iCfg\":{}}",
            " ".repeat(65_537))
        invalid.forEachIndexed { i, value -> assertEquals("invalid sample $i", 400, call("POST", "profile", value).status) }
        assertEquals(0, db.localNightscoutV3Dao().count())
        assertEquals(0L, LocalNightscoutV3Store(db).lastModified().getValue(LocalV3Collection.PROFILE))
    }

    @Test fun targetSchedulesAndProfileShapesMustBeConsistent() = runBlocking {
        val original = Json.parseToJsonElement(sample()).jsonObject
        val profile = original["store"]!!.jsonObject["First"]!!.jsonObject
        val schedule = profile["target_low"]!!.jsonArray
        val invalidProfiles = listOf(
            JsonObject(profile - "sens"), JsonObject(profile + ("dia" to JsonPrimitive(5))),
            JsonObject(profile + ("basal" to JsonArray(emptyList()))),
            JsonObject(profile + ("target_high" to JsonArray(schedule.take(1)))),
            JsonObject(profile + ("target_high" to JsonArray(schedule.map { JsonObject(it.jsonObject + ("value" to JsonPrimitive(1.0))) })))
        )
        invalidProfiles.forEach {
            val value = JsonObject(original + ("store" to JsonObject(mapOf("First" to it))))
            assertEquals(400, call("POST", "profile", value.toString()).status)
        }
        assertEquals(0, db.localNightscoutV3Dao().count())
    }

    @Test fun metadataCannotForgeAnExistingOrNewCatalog() = runBlocking {
        val first = call("POST", "profile", sample())
        val row = rows(latest()).single()
        mapOf("identifier" to JsonPrimitive("another-catalog"), "srvModified" to JsonPrimitive(1),
            "srvCreated" to JsonPrimitive(1), "subject" to JsonPrimitive("different-owner"),
            "modifiedBy" to JsonPrimitive("different-owner")).forEach { (field, value) ->
            assertEquals(400, call("POST", "profile", JsonObject(row + (field to value)).toString()).status)
        }
        val newRecord = Json.parseToJsonElement(sample(at + 1)).jsonObject
        assertEquals(400, call("POST", "profile", JsonObject(newRecord + ("identifier" to JsonPrimitive(id(first)))).toString()).status)
        assertEquals(first.revision, latest().revision)
    }

    @Test fun methodsAndQueriesDoNotSilentlyBroadenPermissions() = runBlocking {
        val first = call("POST", "profile", sample())
        listOf("PATCH", "DELETE", "PUT").forEach { method ->
            assertEquals(405, call(method, "profile/${id(first)}", sample()).status)
            assertEquals(405, call(method, "profile", sample()).status)
        }
        listOf(query("limit" to "0"), query("limit" to "1001"), query("limit" to "1.5"),
            query("unknown" to "1"), mapOf("limit" to listOf("1", "2"))).forEach {
            assertEquals(400, call(query = it).status)
        }
        assertEquals(400, call(path = "profile").status)
        assertEquals(400, call(path = "profile/history/-1").status)
        assertEquals(400, call(path = "profile/history/1/extra").status)
        assertEquals(404, call(path = "profile/missing").status)
        assertEquals(405, call("POST", "profile/history/0", sample()).status)
        val permissions = body(call(path = "status"))["result"]!!.jsonObject["apiPermissions"]!!.jsonObject
        assertEquals("cr", permissions["profile"]!!.jsonPrimitive.content)
        assertEquals("", permissions["food"]!!.jsonPrimitive.content)
        assertEquals(1, db.localNightscoutV3Dao().count())
    }

    @Test fun concurrentRetryAndRestartCannotDuplicateCatalog() = runBlocking {
        val results = coroutineScope { (1..8).map { async(Dispatchers.Default) { call("POST", "profile", sample()) } }.awaitAll() }
        assertEquals(1, results.count { it.status == 201 })
        assertEquals(1, results.map(::id).distinct().size)
        api = LocalNightscoutV3EntriesApi(db) { now - 1 }
        assertEquals(200, call("POST", "profile", sample()).status)
        assertEquals(1, rows(latest()).size)
    }

    @Test fun databaseReopenPreservesCatalogAndMonotonicCursorAfterClockRollback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "profile-wire-${UUID.randomUUID()}"
        db.close()
        db = Room.databaseBuilder(context, CopilotDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            api = LocalNightscoutV3EntriesApi(db) { now }
            val first = call("POST", "profile", sample())
            val original = rows(latest()).single()
            db.close()
            db = Room.databaseBuilder(context, CopilotDatabase::class.java, name).allowMainThreadQueries().build()
            api = LocalNightscoutV3EntriesApi(db) { now - 86_400_000 }
            assertEquals(original, rows(latest()).single())
            val retry = call("POST", "profile", original.toString())
            assertEquals(200, retry.status)
            assertEquals(id(first), id(retry))
            assertEquals(first.revision, retry.revision)
            val next = call("POST", "profile", sample(at + 1))
            assertEquals(201, next.status)
            assertEquals(first.revision!! + 1, next.revision)
            assertEquals(id(next), rows(call(path = "profile/history/${first.revision}")).single()["identifier"]!!.jsonPrimitive.content)
            assertTrue(db.therapyDao().since(0).isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun zeroBasalAndExactMinuteLabelsAreValidWithoutChangingUnits() = runBlocking {
        val original = Json.parseToJsonElement(sample().replace("06:00", "06:30")).jsonObject
        val profile = original["store"]!!.jsonObject["First"]!!.jsonObject
        val zeroBasal = JsonArray(profile["basal"]!!.jsonArray.map { JsonObject(it.jsonObject + ("value" to JsonPrimitive(0.0))) })
        val store = JsonObject(mapOf("First" to JsonObject(profile + ("basal" to zeroBasal))))
        assertEquals(201, call("POST", "profile", JsonObject(original + ("store" to store)).toString()).status)
        assertEquals(store, rows(latest()).single()["store"])
    }

    @Test fun effectiveProfileObservationTracksCorrectionDeletionAndDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "effective-profile-${UUID.randomUUID()}"
        db.close()
        db = Room.databaseBuilder(context, CopilotDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            api = LocalNightscoutV3EntriesApi(db) { now }
            assertEquals(201, call("POST", "profile", sample()).status)
            val profile = Json.parseToJsonElement(sample()).jsonObject.getValue("store").jsonObject.getValue("Second")
            val effective = buildJsonObject {
                put("app", "AAPS"); put("date", at); put("utcOffset", -300); put("eventType", "Note")
                put("originalProfileName", "Second"); put("originalCustomizedName", "Second 120%")
                put("originalTimeshift", -3600000); put("originalPercentage", 120)
                put("originalDuration", 0); put("originalEnd", 0); put("profileJson", profile.toString())
                put("icfg", buildJsonObject {
                    put("insulinLabel", "historical insulin"); put("insulinEndTime", 18000000)
                    put("insulinPeakTime", 4500000); put("concentration", 1.0)
                })
            }
            val created = call("POST", "treatments", effective.toString())
            assertEquals(201, created.status)
            val identifier = id(created)
            suspend fun observation() = AapsV3EffectiveProfileProjector.project(
                LocalNightscoutV3Store(db).get(LocalV3Collection.TREATMENTS, identifier)!!)
            val initial = observation() as AapsV3ProfileProjection.Observed
            assertEquals("Second", initial.observation.originalName)
            assertEquals(created.revision, initial.observation.modifiedRevision)
            db.close()
            db = Room.databaseBuilder(context, CopilotDatabase::class.java, name).allowMainThreadQueries().build()
            api = LocalNightscoutV3EntriesApi(db) { now - 86400000 }
            assertEquals(initial, observation())
            val corrected = call("PATCH", "treatments/$identifier", """{"originalCustomizedName":"Corrected name"}""")
            assertEquals(200, corrected.status)
            val updated = observation() as AapsV3ProfileProjection.Observed
            assertEquals("Corrected name", updated.observation.customizedName)
            assertEquals(created.revision, updated.observation.createdRevision)
            assertTrue(updated.observation.modifiedRevision > initial.observation.modifiedRevision)
            assertEquals(200, call("DELETE", "treatments/$identifier").status)
            assertEquals(AapsV3ProfileProjection.Unavailable(AapsV3ProfileUnavailable.INVALIDATED), observation())
            assertTrue(db.therapyDao().since(0).isEmpty())
            assertTrue(db.glucoseDao().since(0).isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun historyDoesNotTurnInternalDeletionIntoLiveProfile() = runBlocking {
        val first = call("POST", "profile", sample())
        LocalNightscoutV3Store(db).delete(LocalV3Collection.PROFILE, id(first), first.revision!!)
        assertEquals(503, call().status)
        assertEquals(404, call(path = "profile/${id(first)}").status)
        assertEquals(409, call("POST", "profile", sample()).status)
    }

    @Test fun serializedPageBoundsIncludeMetadataAndRefuseRatherThanTruncate() = runBlocking {
        val storage = LocalNightscoutV3Store(db, now = { now })
        repeat(1000) { i ->
            val payload = """{"date":${at + i},"padding":"${"x".repeat(980)}"}"""
            storage.create(LocalV3DocumentInput(LocalV3Collection.PROFILE, "profile-$i", "local-v3-owner", at + i, payload))
        }
        assertEquals(507, call(query = query("limit" to "1000")).status)
    }

    @Test fun cancellationIsNotReclassifiedAsMalformedInput() = runBlocking {
        try {
            api.handle("POST", "/api/v3/profile", emptyMap()) { throw CancellationException("synthetic_cancel") }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(0, db.localNightscoutV3Dao().count())
        }
    }
}
