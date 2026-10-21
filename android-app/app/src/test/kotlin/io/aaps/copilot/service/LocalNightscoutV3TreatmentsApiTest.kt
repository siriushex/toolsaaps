package io.aaps.copilot.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
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
class LocalNightscoutV3TreatmentsApiTest {
    @Test fun signedNativeOffsetsRoundTripWithoutChangingIdentity() = runBlocking {
        val record = sample().replace("\"utcOffset\":0", "\"utcOffset\":-300")
        val created = call("POST", "treatments", record)
        assertEquals(201, created.status)
        val readback = rows(call(path = "treatments/history/0")).single()
        assertEquals(-300, readback.getValue("utcOffset").jsonPrimitive.int)
        assertEquals(created.revision, call("POST", "treatments", readback.toString()).revision)
        assertEquals(400, call("POST", "treatments", record.replace("-300", "-1441")).status)
    }

    @Test fun negativeEffectiveProfileTimeshiftIsNotAnUnsignedTimestamp() = runBlocking {
        val record = """{"app":"AAPS","date":$at,"utcOffset":-300,"eventType":"Note","originalProfileName":"Evening","originalCustomizedName":"Evening 120%","originalTimeshift":-3600000,"originalPercentage":120,"originalDuration":0,"originalEnd":0,"profileJson":"{}"}"""
        val created = call("POST", "treatments", record)
        assertEquals(201, created.status)
        val readback = rows(call(path = "treatments/history/0")).single()
        assertEquals(-3600000L, readback.getValue("originalTimeshift").jsonPrimitive.long)
        assertEquals(created.revision, call("POST", "treatments", readback.toString()).revision)
        assertEquals(400, call("POST", "treatments", record.replace("\"date\":$at", "\"date\":-1")).status)
        assertEquals(400, call("POST", "treatments", record.replace("\"originalDuration\":0", "\"originalDuration\":-1")).status)
    }
    private lateinit var db: CopilotDatabase
    private lateinit var api: LocalNightscoutV3EntriesApi
    private val now = 1_800_000_000_000L
    private val at = now - 3_600_000
    private fun sample(time: Long = at, amount: Double = 1.2) =
        """{"app":"AAPS","date":$time,"utcOffset":0,"eventType":"Meal Bolus","insulin":$amount,"type":"NORMAL","pumpId":12,"pumpType":"synthetic","pumpSerial":"pump-a","icfg":{"insulinLabel":"synthetic insulin","insulinEndTime":18000000,"insulinPeakTime":4500000,"concentration":100.0}}"""
    private suspend fun call(method: String = "GET", path: String = "treatments/history/0", body: String = "",
                             query: Map<String, List<String>> = emptyMap()) =
        api.handle(method, "/api/v3/$path", query) { body.toByteArray() }
    private fun body(result: LocalV3HttpResult) = Json.parseToJsonElement(result.body).jsonObject
    private fun id(result: LocalV3HttpResult) = body(result).getValue("identifier").jsonPrimitive.content
    private fun rows(result: LocalV3HttpResult) = body(result).getValue("result").jsonArray.map { it.jsonObject }
    private fun query(vararg pairs: Pair<String, String>) = pairs.toMap().mapValues { listOf(it.value) }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
        api = LocalNightscoutV3EntriesApi(db) { now }
    }
    @After fun tearDown() = db.close()

    @Test fun authenticWireRecordKeepsInsulinConfigurationWithoutClaimingClinicalDelivery() = runBlocking {
        val first = call("POST", "treatments", sample())
        assertEquals(201, first.status)
        val retry = call("POST", "treatments", " ${sample()} ")
        assertEquals(200, retry.status)
        assertEquals(first.revision, retry.revision)
        assertEquals(id(first), id(retry))
        val row = rows(call()).single()
        assertEquals(Json.parseToJsonElement(sample()).jsonObject["icfg"], row["icfg"])
        assertEquals(at, row["date"]!!.jsonPrimitive.long)
        assertEquals("mg/dl", row["units"]!!.jsonPrimitive.content)
        assertEquals("2027-01-15T07:00:00Z", row["created_at"]!!.jsonPrimitive.content)
        assertEquals(409, call("POST", "treatments", sample(amount = 1.3)).status)
        assertEquals(409, call("POST", "treatments", sample().replace("\"utcOffset\":0", "\"utcOffset\":60")).status)
        assertTrue(db.glucoseDao().since(0).isEmpty())
        assertTrue(db.therapyDao().since(0).isEmpty())
        assertTrue(db.actionCommandDao().latest(10).isEmpty())
    }

    @Test fun stockPatchRetainsOmittedImmutableAcquisitionAndSupportsCasRetry() = runBlocking {
        val first = call("POST", "treatments", sample())
        val identifier = id(first)
        val patch = """{"identifier":"$identifier","insulin":1.4,"srvModified":${first.revision}}"""
        val updated = call("PATCH", "treatments/$identifier", patch)
        assertEquals(200, updated.status)
        val row = body(call(path = "treatments/$identifier"))["result"]!!.jsonObject
        assertEquals(at, row["date"]!!.jsonPrimitive.long)
        assertEquals(0, row["utcOffset"]!!.jsonPrimitive.int)
        assertEquals(1.4, row["insulin"]!!.jsonPrimitive.double, 0.0)
        assertEquals(updated.revision, call("PATCH", "treatments/$identifier", patch).revision)
        assertEquals(409, call("PATCH", "treatments/$identifier", patch.replace("1.4", "1.5")).status)
        listOf("\"date\":${at + 1}", "\"pumpId\":13", "\"eventType\":\"Correction Bolus\"", "\"utcOffset\":60").forEach {
            assertEquals(400, call("PATCH", "treatments/$identifier", "{$it}").status)
        }
    }

    @Test fun invalidationKeepsTombstoneAndCannotResurrectOrApplyTarget() = runBlocking {
        val first = call("POST", "treatments", sample())
        val identifier = id(first)
        val removed = call("DELETE", "treatments/$identifier")
        assertEquals(200, removed.status)
        assertEquals(removed.revision, call("DELETE", "treatments/$identifier").revision)
        assertEquals(404, call(path = "treatments/$identifier").status)
        val history = call(path = "treatments/history/${first.revision}")
        assertFalse(rows(history).single()["isValid"]!!.jsonPrimitive.boolean)
        assertEquals(removed.revision, history.revision)
        assertEquals(409, call("POST", "treatments", sample()).status)
        assertEquals(409, call("PATCH", "treatments/$identifier", """{"insulin":1.4}""").status)
        assertTrue(rows(call(path = "treatments", query = query("sort" to "created_at", "created_at\$gt" to "1970-01-01T00:00:00Z"))).isEmpty())
        assertTrue(db.therapyDao().since(0).isEmpty())
    }

    @Test fun firstLoadCompletesEqualTimeBoundaryAndHistoryFindsLateEdits() = runBlocking {
        val first = call("POST", "treatments", sample())
        val carbs = """{"app":"AAPS","date":$at,"utcOffset":0,"eventType":"Carb Correction","carbs":24.0,"duration":30,"durationInMilliseconds":1800000}"""
        assertEquals(201, call("POST", "treatments", carbs).status)
        val page = call(path = "treatments", query = query("sort" to "created_at", "created_at\$gt" to "1970-01-01T00:00:00Z", "limit" to "1"))
        assertEquals(2, rows(page).size)
        assertNull(page.revision)
        val initialHistory = call(query = query("limit" to "1"))
        assertEquals(first.revision, initialHistory.revision)
        val next = call(path = "treatments/history/${first.revision}", query = query("limit" to "1"))
        val edited = call("PATCH", "treatments/${id(first)}", """{"insulin":1.3}""")
        val late = call(path = "treatments/history/${next.revision}")
        assertEquals(edited.revision, late.revision)
        assertEquals(at, rows(late).single()["date"]!!.jsonPrimitive.long)
    }

    @Test fun targetAndCancelKeepUnitsAndDurationsWithoutTreatingReceiptAsApplication() = runBlocking {
        val target = """{"app":"AAPS","date":$at,"utcOffset":240,"eventType":"Temporary Target","units":"mmol/l","duration":30,"durationInMilliseconds":1800000,"targetBottom":5.5,"targetTop":5.5,"reason":"Eating Soon"}"""
        assertEquals(201, call("POST", "treatments", target).status)
        assertEquals("mmol", rows(call()).single()["units"]!!.jsonPrimitive.content)
        val cancel = target.replace("\"date\":$at", "\"date\":${at + 1}")
            .replace("\"duration\":30", "\"duration\":0").replace("1800000", "0")
            .replace("5.5", "0.0")
        assertEquals(201, call("POST", "treatments", cancel).status)
        assertEquals(2, rows(call()).size)
        assertTrue(db.therapyDao().since(0).isEmpty())
    }

    @Test fun therapyBranchesAndNestedExtendedBasalRemainLossless() = runBlocking {
        val common = "\"app\":\"AAPS\",\"date\":$at,\"utcOffset\":0"
        val records = listOf(
            "{$common,\"eventType\":\"Carb Correction\",\"carbs\":-10.0}",
            "{$common,\"eventType\":\"Site Change\",\"notes\":\"synthetic\"}",
            "{$common,\"eventType\":\"BG Check\",\"glucose\":100.0,\"units\":\"mg/dl\",\"glucoseType\":\"Finger\"}",
            "{$common,\"eventType\":\"OpenAPS Offline\",\"duration\":30,\"durationInMilliseconds\":1800000,\"mode\":\"SUSPEND\",\"autoForced\":false}",
            "{$common,\"eventType\":\"Temp Basal\",\"absolute\":0.5,\"duration\":30,\"durationInMilliseconds\":1800000,\"extendedEmulated\":{\"eventType\":\"Combo Bolus\",\"enteredinsulin\":0.25,\"duration\":30,\"durationInMilliseconds\":1800000}}"
        )
        records.forEach { assertEquals(201, call("POST", "treatments", it).status) }
        assertEquals(records.size, rows(call()).size)
        val original = Json.parseToJsonElement(records.last()).jsonObject["extendedEmulated"]!!.jsonObject
        val stored = rows(call()).last()["extendedEmulated"]!!.jsonObject
        original.forEach { (key, value) -> assertEquals(value, stored[key]) }
        assertEquals("mg/dl", stored["units"]!!.jsonPrimitive.content)
    }

    @Test fun malformedTypesContradictoryTimesAndSilentSdkDropsAreRejected() = runBlocking {
        val invalid = listOf("", "[]", "{}", "{\"date\":1,\"date\":2}",
            sample().replace("1.2", "\"1.2\""), sample().replace("1.2", "1e999"),
            sample().replace("Meal Bolus", "unknown event"), sample().replace("\"date\":$at", "\"date\":\"$at\""),
            sample().dropLast(1) + ",\"created_at\":\"2020-01-01T00:00:00Z\"}",
            sample().dropLast(1) + ",\"created_at\":\"2027-01-15T07:00:00.000001Z\"}",
            sample().dropLast(1) + ",\"carbs\":24.0}",
            sample().dropLast(1) + ",\"isValid\":false}",
            sample().dropLast(1) + ",\"subject\":\"forged\"}",
            sample().dropLast(1) + ",\"unexpected\":1}",
            sample().dropLast(1) + ",\"identifier\":[]}",
            sample().dropLast(1) + ",\"isReadOnly\":\"true\"}",
            sample().dropLast(1) + ",\"protein\":2147483648}",
            sample().replace("NORMAL", "UNRECOGNIZED"),
            sample().replace("18000000", "\"18000000\""),
            sample().replace("4500000", "19000000"),
            sample().replace("100.0", "-1.0"),
            """{"app":"AAPS","date":$at,"eventType":"Temporary Target","duration":30}""",
            """{"app":"AAPS","date":$at,"eventType":"Carb Correction","carbs":0.0}""",
            """{"app":"AAPS","date":$at,"eventType":"Carb Correction","carbs":24.0,"duration":30,"durationInMilliseconds":1000}""",
            """{"app":"AAPS","date":$at,"eventType":"Meal Bolus"}""",
            """{"app":"AAPS","date":$at,"eventType":"BG Check","glucose":6.0}""",
            " ".repeat(65_537))
        invalid.forEach { assertEquals("expected rejection", 400, call("POST", "treatments", it).status) }
        assertEquals(0, db.localNightscoutV3Dao().count())
    }

    @Test fun readOnlyAndServerMetadataCannotBeOverwritten() = runBlocking {
        val first = call("POST", "treatments", sample().dropLast(1) + ",\"isReadOnly\":true}")
        val identifier = id(first)
        assertEquals(403, call("PATCH", "treatments/$identifier", """{"isReadOnly":false}""").status)
        assertEquals(403, call("DELETE", "treatments/$identifier").status)
        assertEquals(400, call("PATCH", "treatments/$identifier", """{"srvCreated":1}""").status)
        assertEquals(400, call("PATCH", "treatments/$identifier", """{"identifier":"different"}""").status)
    }

    @Test fun duplicateConcurrentUploadAndRestartAllocateOneIdentity() = runBlocking {
        val results = coroutineScope { (1..8).map { async(Dispatchers.Default) { call("POST", "treatments", sample()) } }.awaitAll() }
        assertEquals(1, results.count { it.status == 201 })
        assertEquals(1, results.map(::id).distinct().size)
        api = LocalNightscoutV3EntriesApi(db) { now - 1000 }
        assertEquals(200, call("POST", "treatments", sample()).status)
        assertEquals(1, rows(call()).size)
    }

    @Test fun queryValidationAndUnsupportedMethodsDoNotHideOrMutateData() = runBlocking {
        val first = call("POST", "treatments", sample())
        assertEquals(400, call(path = "treatments", query = query("sort" to "created_at", "created_at\$gt" to "2027-01-15T07:00:00")).status)
        assertEquals(400, call(path = "treatments", query = query("sort" to "date")).status)
        assertEquals(400, call(path = "treatments/history/0", query = query("limit" to "0")).status)
        assertEquals(400, call(path = "treatments/history/-1").status)
        assertEquals(400, call(path = "treatments/history/0/extra").status)
        assertEquals(405, call("PUT", "treatments/${id(first)}", sample()).status)
        val permissions = body(call(path = "status"))["result"]!!.jsonObject["apiPermissions"]!!.jsonObject
        assertEquals("crud", permissions["treatments"]!!.jsonPrimitive.content)
        assertEquals("cr", permissions["profile"]!!.jsonPrimitive.content)
        assertEquals("", permissions["food"]!!.jsonPrimitive.content)
        assertEquals(1, rows(call()).size)
    }

    @Test fun actualDatabaseReopenRetainsIdentityEditsAndTombstone() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "treatment-wire-${UUID.randomUUID()}"
        db.close()
        db = Room.databaseBuilder(context, CopilotDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            api = LocalNightscoutV3EntriesApi(db) { now }
            val first = call("POST", "treatments", sample())
            val identifier = id(first)
            db.close()
            db = Room.databaseBuilder(context, CopilotDatabase::class.java, name).allowMainThreadQueries().build()
            api = LocalNightscoutV3EntriesApi(db) { now - 1000 }
            assertEquals(first.revision, call("POST", "treatments", sample()).revision)
            val edited = call("PATCH", "treatments/$identifier", """{"insulin":1.4}""")
            assertTrue(edited.revision!! > first.revision!!)
            val removed = call("DELETE", "treatments/$identifier")
            db.close()
            db = Room.databaseBuilder(context, CopilotDatabase::class.java, name).allowMainThreadQueries().build()
            api = LocalNightscoutV3EntriesApi(db) { now - 2000 }
            assertEquals(404, call(path = "treatments/$identifier").status)
            assertEquals(409, call("POST", "treatments", sample()).status)
            assertEquals(removed.revision, call().revision)
            assertFalse(rows(call()).single()["isValid"]!!.jsonPrimitive.boolean)
            assertTrue(db.therapyDao().since(0).isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun nestedNativeMetadataAndUnitsStayScopedToExtendedRecord() = runBlocking {
        val record = """{"app":"AAPS","date":$at,"utcOffset":0,"eventType":"Temp Basal","absolute":0.5,"duration":30,"durationInMilliseconds":1800000,"extendedEmulated":{"date":$at,"utcOffset":0,"eventType":"Combo Bolus","enteredinsulin":0.25,"duration":30,"durationInMilliseconds":1800000,"units":"mmol/l","isValid":true,"isReadOnly":false,"identifier":"nested-original","subject":"historical-owner"}}"""
        assertEquals(201, call("POST", "treatments", record).status)
        val row = rows(call()).single()
        val nested = row["extendedEmulated"]!!.jsonObject
        assertEquals("mmol", nested["units"]!!.jsonPrimitive.content)
        assertEquals("nested-original", nested["identifier"]!!.jsonPrimitive.content)
        assertEquals("historical-owner", nested["subject"]!!.jsonPrimitive.content)
        assertEquals("local-v3-owner", row["subject"]!!.jsonPrimitive.content)
        assertEquals(0.25, nested["enteredinsulin"]!!.jsonPrimitive.double, 0.0)
        assertEquals(400, call("POST", "treatments", record.replace("\"isValid\":true", "\"isValid\":\"true\"")).status)
    }
}
