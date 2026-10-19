package io.aaps.copilot.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import io.aaps.copilot.data.repository.LocalV3Collection
import io.aaps.copilot.data.repository.LocalV3DocumentInput
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
class LocalNightscoutV3EntriesApiTest {
    private lateinit var db: CopilotDatabase
    private lateinit var api: LocalNightscoutV3EntriesApi
    private val now = 1_800_000_000_000L
    private val at = now - 3_600_000
    private fun sample(time: Long = at, device: String = "sensor-a", value: Double = 120.0) =
        """{"type":"sgv","date":$time,"device":"$device","sgv":$value,"units":"mg/dl","utcOffset":0}"""
    private suspend fun call(method: String = "GET", path: String = "entries", body: String = "",
                             query: Map<String, List<String>> = emptyMap()) =
        api.handle(method, "/api/v3/$path", query) { body.toByteArray() }
    private fun body(result: LocalV3HttpResult) = Json.parseToJsonElement(result.body).jsonObject
    private fun id(result: LocalV3HttpResult) = body(result).getValue("identifier").jsonPrimitive.content
    private fun rows(result: LocalV3HttpResult) = body(result).getValue("result").jsonArray.map { it.jsonObject }
    private fun query(vararg values: Pair<String, String>) = values.toMap().mapValues { listOf(it.value) }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
        api = LocalNightscoutV3EntriesApi(db) { now }
    }
    @After fun tearDown() = db.close()

    @Test fun statusIsTruthfulAndOtherCollectionsCannotWrite() = runBlocking {
        val status = body(call(path = "status"))["result"]!!.jsonObject
        val permissions = status["apiPermissions"]!!.jsonObject
        assertEquals("crud", permissions["entries"]!!.jsonPrimitive.content)
        assertEquals("cr", permissions["devicestatus"]!!.jsonPrimitive.content)
        assertEquals("crud", permissions["settings"]!!.jsonPrimitive.content)
        assertTrue(permissions.filterKeys { it !in setOf("entries", "devicestatus", "settings") }.values.all { it.jsonPrimitive.content.isEmpty() })
        assertEquals(405, call("POST", "status").status)
        listOf("treatments", "profile", "food").forEach {
            assertEquals(501, call("POST", it, sample()).status)
        }
        assertEquals(0, db.localNightscoutV3Dao().count())
        assertTrue(db.glucoseDao().since(0).isEmpty())
        assertTrue(db.therapyDao().since(0).isEmpty())
    }

    @Test fun createRetryAndIdentityConflictDoNotAdvanceClock() = runBlocking {
        val first = call("POST", body = sample())
        val retry = call("POST", body = " ${sample()} ")
        assertEquals(201, first.status)
        assertEquals(200, retry.status)
        assertEquals(first.revision, retry.revision)
        assertEquals(id(first), id(retry))
        assertEquals(409, call("POST", body = sample(value = 121.0)).status)
        assertEquals(1, db.localNightscoutV3Dao().count())
        assertEquals(first.revision, db.localNightscoutV3Dao().clock()!!.revision)
        assertEquals(201, call("POST", body = sample(device = "sensor-b")).status)
    }

    @Test fun patchMergesOmittedTimeAndPreservesStableIdentity() = runBlocking {
        val first = call("POST", body = sample())
        val id = id(first)
        val edited = call("PATCH", "entries/$id", """{"identifier":"$id","sgv":125.0,"srvModified":${first.revision}}""")
        assertEquals(200, edited.status)
        val row = body(call(path = "entries/$id"))["result"]!!.jsonObject
        assertEquals(at, row["date"]!!.jsonPrimitive.long)
        assertEquals(0, row["utcOffset"]!!.jsonPrimitive.int)
        assertEquals(125.0, row["sgv"]!!.jsonPrimitive.double, 0.0)
        val retry = call("PATCH", "entries/$id", """{"sgv":125.0,"srvModified":${first.revision}}""")
        assertEquals(200, retry.status)
        assertEquals(edited.revision, retry.revision)
        assertEquals(409, call("PATCH", "entries/$id", """{"sgv":126.0,"srvModified":${first.revision}}""").status)
        listOf("\"date\":${at + 1}", "\"device\":\"other\"", "\"utcOffset\":60", "\"identifier\":\"other\"").forEach {
            assertEquals(400, call("PATCH", "entries/$id", "{$it}").status)
        }
    }

    @Test fun deleteIsIdempotentAndHistoryIncludesTombstone() = runBlocking {
        val original = call("POST", body = sample())
        val id = id(original)
        val deleted = call("DELETE", "entries/$id")
        assertEquals(200, deleted.status)
        assertEquals(deleted.revision, call("DELETE", "entries/$id").revision)
        assertEquals(404, call(path = "entries/$id").status)
        val page = call(path = "entries/history/${original.revision}", query = query("limit" to "1"))
        assertEquals(deleted.revision, page.revision)
        assertFalse(rows(page).single()["isValid"]!!.jsonPrimitive.boolean)
        assertEquals(at, rows(page).single()["date"]!!.jsonPrimitive.long)
        assertEquals(409, call("POST", body = sample()).status)
        assertEquals(409, call("PATCH", "entries/$id", """{"sgv":130.0}""").status)
        assertTrue(rows(call(query = query("sort" to "date", "date\$gt" to "0"))).isEmpty())
        assertEquals(404, call("DELETE", "entries/missing").status)
    }

    @Test fun historyLimitCannotJumpOverUnreturnedEdits() = runBlocking {
        val original = call("POST", body = sample())
        call("POST", body = sample(at + 60_000))
        val firstPage = call(path = "entries/history/0", query = query("limit" to "1"))
        assertEquals(original.revision, firstPage.revision)
        val secondPage = call(path = "entries/history/${firstPage.revision}", query = query("limit" to "1"))
        assertEquals(at + 60_000, rows(secondPage).single()["date"]!!.jsonPrimitive.long)
        call("PATCH", "entries/${id(original)}", """{"sgv":125}""")
        val late = call(path = "entries/history/${secondPage.revision}", query = query("limit" to "1"))
        assertEquals(at, rows(late).single()["date"]!!.jsonPrimitive.long)
        val empty = call(path = "entries/history/${late.revision}")
        assertEquals(late.revision, empty.revision)
        assertTrue(rows(empty).isEmpty())
    }

    @Test fun chronologicalPageFinishesTimeTiesAndLatestFiltersManualMeasurements() = runBlocking {
        call("POST", body = sample(device = "sensor-a"))
        call("POST", body = sample(device = "sensor-b"))
        call("POST", body = """{"type":"mbg","date":${at + 60_000},"device":"meter-a","mbg":130.0,"units":"mg/dl","isCalibration":true,"sensorMgdlAtPairing":120}""")
        val page = call(query = query("sort" to "date", "date\$gt" to "0", "limit" to "1"))
        assertEquals(2, rows(page).size)
        assertNull(page.revision)
        val after = call(query = query("sort" to "date", "date\$gt" to "$at"))
        assertEquals("mbg", rows(after).single()["type"]!!.jsonPrimitive.content)
        val latest = rows(call(query = query("sort\$desc" to "date", "type" to "sgv")))
        assertEquals(2, latest.size)
        assertTrue(latest.all { it["type"] == JsonPrimitive("sgv") })
    }

    @Test fun readOnlyAndServerMetadataAreNotClientOverridable() = runBlocking {
        val first = call("POST", body = sample().dropLast(1) + ",\"isReadOnly\":true}")
        val id = id(first)
        assertEquals(403, call("DELETE", "entries/$id").status)
        assertEquals(403, call("PATCH", "entries/$id", """{"isReadOnly":false}""").status)
        assertEquals(400, call("POST", body = sample(device = "new").dropLast(1) + ",\"srvCreated\":123}").status)
        assertEquals(400, call("PATCH", "entries/$id", """{"subject":"forged"}""").status)
        assertEquals(400, call("PATCH", "entries/$id", """{"isValid":false}""").status)
    }

    @Test fun strictJsonAndIncompleteIdentityAreRejectedWithoutWrites() = runBlocking {
        val invalid = listOf("", "[]", "{}", "{\"date\":1,\"date\":2}", "{\"type\":null}",
            sample().replace("120.0", "NaN"), sample().replace("120.0", "\"120.0\""),
            sample().replace("sensor-a", ""), sample().replace("\"device\":\"sensor-a\",", ""),
            sample().replace("mg/dl", "unknown"), sample().replace("\"sgv\":120.0", "\"sgv\":0"),
            sample().dropLast(1) + ",\"dateString\":\"invalid\"}",
            sample().dropLast(1) + ",\"dateString\":\"2020-01-01T00:00:00Z\"}",
            sample().dropLast(1) + ",\"unknown\":1}", sample().dropLast(1) + ",\"mbg\":100}",
            sample().dropLast(1) + ",\"isReadOnly\":\"true\"}",
            sample().replace("\"utcOffset\":0", "\"utcOffset\":1.2"), " ".repeat(65_537))
        invalid.forEach { assertEquals(400, call("POST", body = it).status) }
        assertEquals(400, api.handle("POST", "/api/v3/entries", emptyMap()) { byteArrayOf(0xc3.toByte(), 0x28) }.status)
        assertEquals(0, db.localNightscoutV3Dao().count())
    }

    @Test fun stockCalibrationWithoutDeviceUsesOwnerTimeIdentityAndDoesNotMergeCorrections() = runBlocking {
        val calibration = """{"type":"mbg","date":$at,"mbg":130.0,"units":"mg/dl","isCalibration":true,"sensorMgdlAtPairing":120.0}"""
        val first = call("POST", body = calibration)
        assertEquals(201, first.status)
        val retry = call("POST", body = calibration)
        assertEquals(200, retry.status)
        assertEquals(id(first), id(retry))
        assertEquals(first.revision, retry.revision)
        assertEquals(409, call("POST", body = calibration.replace("130.0", "135.0")).status)
        assertEquals(400, call("POST", body = calibration.replace("\"isCalibration\":true,", "")).status)
        assertEquals(400, call("POST", body = calibration.replace("\"sensorMgdlAtPairing\":120.0", "\"sensorMgdlAtPairing\":0")).status)
        assertEquals(400, call("POST", body = calibration.replace("mg/dl", "mmol/l")).status)
        assertTrue(db.glucoseDao().since(0).isEmpty())
    }

    @Test fun queryValidationNeverSilentlyDropsFiltersOrOverflowsLimits() = runBlocking {
        listOf(query("sort" to "date", "limit" to "4294967297"), query("sort" to "date", "date\$gt" to "-1"),
            query("sort" to "date", "date\$gt" to "1e3"), query("sort" to "date", "find" to "x"),
            query("sort\$desc" to "date", "type" to "mbg"), query("sort" to "date", "limit" to "0"),
            mapOf("sort" to listOf("date"), "limit" to listOf("1", "2"))).forEach {
            assertEquals(400, call(query = it).status)
        }
        assertEquals(400, call(path = "entries/history/1/extra").status)
        assertEquals(400, call(path = "entries/history/0", query = query("sort" to "date")).status)
        assertEquals(405, call("PUT", body = sample()).status)
    }

    @Test fun concurrentlyRetriedPostIsOneDocumentAndSurvivesApiRestart() = runBlocking {
        val results = coroutineScope { (1..16).map { async(Dispatchers.Default) { call("POST", body = sample()) } }.awaitAll() }
        assertEquals(1, results.count { it.status == 201 })
        assertEquals(1, results.map(::id).distinct().size)
        assertEquals(1, results.map { it.revision }.distinct().size)
        api = LocalNightscoutV3EntriesApi(db) { now - 10_000 }
        assertEquals(200, call("POST", body = sample()).status)
        assertTrue(call("POST", body = sample(at + 1)).revision!! > results.first().revision!!)
    }

    @Test fun oversizedLatestWindowRefusesRatherThanHidingOlderSensorData() = runBlocking {
        val storage = LocalNightscoutV3Store(db, now = { now })
        repeat(1001) { i ->
            storage.create(LocalV3DocumentInput(LocalV3Collection.ENTRIES, "manual-$i", "test-owner", at + i,
                """{"type":"mbg","date":${at + i},"mbg":120}"""))
        }
        assertEquals(507, call(query = query("sort\$desc" to "date", "type" to "sgv")).status)
    }
}
