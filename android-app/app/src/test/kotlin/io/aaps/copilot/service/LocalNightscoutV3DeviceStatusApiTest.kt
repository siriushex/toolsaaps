package io.aaps.copilot.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import io.aaps.copilot.data.repository.LocalV3Collection
import io.aaps.copilot.data.repository.LocalV3DocumentInput
import java.time.Instant
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
class LocalNightscoutV3DeviceStatusApiTest {
    private lateinit var db: CopilotDatabase
    private lateinit var api: LocalNightscoutV3EntriesApi
    private val now = 1_800_000_000_000L
    private val at = now - 60_000
    private fun sample(time: Long = at, device: String = "pump-a", iob: Double = -0.2) =
        """{"app":"AAPS","date":$time,"device":"$device","uploaderBattery":99,"isCharging":true,"pump":{"reservoir":60.5,"extended":{"driver":"synthetic","value":1}},"openaps":{"iob":{"iob":$iob},"suggested":{"reason":"synthetic"}}}"""
    private suspend fun call(method: String = "GET", path: String = "devicestatus/history/0", body: String = "",
                             query: Map<String, List<String>> = emptyMap()) =
        api.handle(method, "/api/v3/$path", query) { body.toByteArray() }
    private fun body(result: LocalV3HttpResult) = Json.parseToJsonElement(result.body).jsonObject
    private fun id(result: LocalV3HttpResult) = body(result).getValue("identifier").jsonPrimitive.content
    private fun rows(result: LocalV3HttpResult) = body(result).getValue("result").jsonArray.map { it.jsonObject }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
        api = LocalNightscoutV3EntriesApi(db) { now }
    }
    @After fun tearDown() = db.close()

    @Test fun stockDateOnlyStatusHasStableIdentityAndIsoTimeWithoutClinicalProjection() = runBlocking {
        val first = call("POST", "devicestatus", sample())
        assertEquals(201, first.status)
        val retry = call("POST", "devicestatus", " ${sample()} ")
        assertEquals(200, retry.status)
        assertEquals(id(first), id(retry))
        assertEquals(first.revision, retry.revision)
        val row = rows(call()).single()
        assertEquals(at, row["date"]!!.jsonPrimitive.long)
        assertEquals(Instant.ofEpochMilli(at).toString(), row["created_at"]!!.jsonPrimitive.content)
        assertEquals(-0.2, row["openaps"]!!.jsonObject["iob"]!!.jsonObject["iob"]!!.jsonPrimitive.double, 0.0)
        assertEquals(Json.parseToJsonElement(sample()).jsonObject["pump"], row["pump"])
        assertTrue(db.glucoseDao().since(0).isEmpty())
        assertTrue(db.therapyDao().since(0).isEmpty())
        assertEquals(409, call("POST", "devicestatus", sample(iob = 0.3)).status)
        assertEquals(201, call("POST", "devicestatus", sample(device = "pump-b")).status)
    }

    @Test fun nativeRoundTripMetadataAndNormalizedTimeAreIdempotent() = runBlocking {
        val original = call("POST", "devicestatus", sample())
        val row = rows(call()).single()
        // RC1's DTO omits subject/modifiedBy; use exactly the fields it retains.
        val native = JsonObject(row.filterKeys { it !in setOf("subject", "modifiedBy") }).toString()
        val retry = call("POST", "devicestatus", native)
        assertEquals(200, retry.status)
        assertEquals(original.revision, retry.revision)
        val isoOnly = sample(at + 1).replace("\"date\":${at + 1}", "\"created_at\":\"${Instant.ofEpochMilli(at + 1)}\"")
        assertEquals(201, call("POST", "devicestatus", isoOnly).status)
        assertEquals(2, rows(call()).size)
    }

    @Test fun comboHistoryHazardsAreRetainedWithoutBecomingDeliveryOrClockAuthority() = runBlocking {
        val observation = buildJsonObject {
            put("version", 1); put("scope", "reader_session"); put("coverage", "unverified")
            put("state", "FAILED"); put("readStartedAt", at); put("readFinishedAt", at - 1000)
            put("validatedBlocks", 3); put("confirmedBlocks", 2); put("parsedEvents", 7); put("unmappedEvents", 1)
            put("gapObserved", true); put("clockChangeObserved", true); put("unmappedEventObserved", true)
            put("interruptedReadObserved", true); put("malformedBlockObserved", false)
        }
        fun status(time: Long, history: JsonObject) = buildJsonObject {
            put("date", time); put("device", "pump-a")
            putJsonObject("pump") { putJsonObject("extended") { put("ComboHistoryRead", history) } }
        }.toString()
        val first = call("POST", "devicestatus", status(at, observation))
        assertEquals(201, first.status)
        api = LocalNightscoutV3EntriesApi(db) { now + 1 }
        assertEquals(200, call("POST", "devicestatus", status(at, observation)).status)
        val clean = JsonObject(observation + ("gapObserved" to JsonPrimitive(false)))
        assertEquals(201, call("POST", "devicestatus", status(at + 1000, clean)).status)
        val retained = rows(call()).associateBy { it.getValue("date").jsonPrimitive.long }
        assertEquals(observation, retained.getValue(at).getValue("pump").jsonObject
            .getValue("extended").jsonObject.getValue("ComboHistoryRead"))
        assertEquals(clean, retained.getValue(at + 1000).getValue("pump").jsonObject
            .getValue("extended").jsonObject.getValue("ComboHistoryRead"))
        assertTrue(db.therapyDao().since(0).isEmpty())
        assertTrue(db.glucoseDao().since(0).isEmpty())
    }

    @Test fun journalStorageStatusIsRetainedAsUnverifiedEvidenceOnly() = runBlocking {
        val journal = buildJsonObject {
            put("version", 1); put("coverage", "unverified"); put("state", "STORED")
            put("retainedReads", 3); put("unclosedReads", 1); put("corruptReads", 0)
            put("adverseReads", 2); put("failedReads", 1)
            put("unscopedCorruptReads", 1)
        }
        fun status(time: Long, value: JsonObject) = buildJsonObject {
            put("date", time); put("device", "pump-a")
            putJsonObject("pump") { putJsonObject("extended") { put("ComboHistoryJournal", value) } }
        }.toString()
        assertEquals(201, call("POST", "devicestatus", status(at, journal)).status)
        api = LocalNightscoutV3EntriesApi(db) { now + 1 }
        assertEquals(200, call("POST", "devicestatus", status(at, journal)).status)
        val failed = buildJsonObject { put("version", 1); put("coverage", "unverified"); put("state", "FAILED") }
        assertEquals(201, call("POST", "devicestatus", status(at + 1000, failed)).status)
        val retained = rows(call()).associateBy { it.getValue("date").jsonPrimitive.long }
        for ((time, value) in listOf(at to journal, at + 1000 to failed))
            assertEquals(value, retained.getValue(time).getValue("pump").jsonObject
                .getValue("extended").jsonObject.getValue("ComboHistoryJournal"))
        assertTrue(db.therapyDao().since(0).isEmpty())
        assertTrue(db.glucoseDao().since(0).isEmpty())
    }

    @Test fun historyIsCompleteAndUsesModificationTimeRatherThanEventTime() = runBlocking {
        val first = call("POST", "devicestatus", sample())
        val late = call("POST", "devicestatus", sample(at - 86_400_000, "pump-b"))
        val page = call(path = "devicestatus/history/${first.revision}")
        assertEquals(late.revision, page.revision)
        assertEquals(at - 86_400_000, rows(page).single()["date"]!!.jsonPrimitive.long)
        assertTrue(rows(call(path = "devicestatus/history/${late.revision}")).isEmpty())
        val head = body(call(path = "lastModified"))["result"]!!.jsonObject["collections"]!!.jsonObject
        assertEquals(late.revision, head["devicestatus"]!!.jsonPrimitive.long)
    }

    @Test fun unsupportedMethodsAndFiltersCannotChangeOrHideStatus() = runBlocking {
        val first = call("POST", "devicestatus", sample())
        listOf("PATCH", "PUT", "DELETE").forEach {
            assertEquals(405, call(it, "devicestatus/${id(first)}", sample(iob = 2.0)).status)
        }
        assertEquals(405, call(path = "devicestatus").status)
        assertEquals(400, call(query = mapOf("limit" to listOf("1"))).status)
        assertEquals(400, call(path = "devicestatus/history/0/extra").status)
        assertEquals(400, call(path = "devicestatus/history/-1").status)
        assertEquals(405, call("POST", "devicestatus/history/0", sample()).status)
        assertEquals(1, rows(call()).size)
        val permissions = body(call(path = "status"))["result"]!!.jsonObject["apiPermissions"]!!.jsonObject
        assertEquals("cr", permissions["devicestatus"]!!.jsonPrimitive.content)
        assertEquals("crud", permissions["settings"]!!.jsonPrimitive.content)
        assertEquals(400, call("POST", "settings", "{}").status)
    }

    @Test fun malformedIdentityTimeTypesAndServerFieldsNeverWrite() = runBlocking {
        val invalid = listOf("", "[]", "{}", "{\"date\":1,\"date\":2}",
            sample().replace("pump-a", ""), sample().replace("\"device\":\"pump-a\",", ""),
            sample().replace("\"date\":$at,", ""), sample().replace("$at", "\"$at\""),
            sample().dropLast(1) + ",\"created_at\":\"2020-01-01T00:00:00Z\"}",
            sample().dropLast(1) + ",\"srvModified\":123}", sample().dropLast(1) + ",\"subject\":\"forged\"}",
            sample().dropLast(1) + ",\"isValid\":false}", sample().dropLast(1) + ",\"unknown\":1}",
            sample().dropLast(1) + ",\"identifier\":\"unassigned\"}",
            sample().replace("99", "101"), sample().replace("99", "99.5"),
            sample().replace("true", "\"true\""), sample().replace("60.5", "\"60.5\""),
            sample().replace("\"iob\":-0.2", "\"iob\":1e999"),
            sample().replace("\"driver\":\"synthetic\"", "\"driver\":\"synthetic\",\"driver\":\"duplicate\""),
            " ".repeat(65_537))
        invalid.forEach { assertEquals(400, call("POST", "devicestatus", it).status) }
        assertEquals(0, db.localNightscoutV3Dao().count())
    }

    @Test fun concurrentRetrySurvivesRestartWithoutDuplicatingStatus() = runBlocking {
        val results = coroutineScope { (1..8).map { async(Dispatchers.Default) { call("POST", "devicestatus", sample()) } }.awaitAll() }
        assertEquals(1, results.count { it.status == 201 })
        assertEquals(1, results.map(::id).distinct().size)
        api = LocalNightscoutV3EntriesApi(db) { now - 1 }
        assertEquals(200, call("POST", "devicestatus", sample()).status)
        assertEquals(1, rows(call()).size)
    }

    @Test fun nonPagedSdkHistoryRefusesOverflowInsteadOfSilentlyTruncating() = runBlocking {
        val storage = LocalNightscoutV3Store(db, now = { now })
        repeat(1001) { i ->
            storage.create(LocalV3DocumentInput(LocalV3Collection.DEVICE_STATUS, "status-$i", "local-v3-owner", at + i,
                sample(at + i, "pump-$i")))
        }
        assertEquals(507, call().status)
        assertEquals(1001, db.localNightscoutV3Dao().count())
    }

    @Test fun completeResponseCapacityIncludesServerMetadata() = runBlocking {
        val storage = LocalNightscoutV3Store(db, now = { now })
        repeat(1000) { i ->
            val base = sample(at + i, "pump-$i").replace("synthetic", "")
            val payload = base.replace("\"driver\":\"\"", "\"driver\":\"${"x".repeat(1000 - base.length)}\"")
            assertEquals(1000, payload.toByteArray().size)
            storage.create(LocalV3DocumentInput(LocalV3Collection.DEVICE_STATUS, "status-$i", "local-v3-owner", at + i, payload))
        }
        assertEquals(507, call().status)
    }

    @Test fun unsupportedTombstoneCannotReappearAsLiveStatus() = runBlocking {
        val first = call("POST", "devicestatus", sample())
        val storage = LocalNightscoutV3Store(db, now = { now })
        storage.delete(LocalV3Collection.DEVICE_STATUS, id(first), first.revision!!)
        assertEquals(503, call().status)
        assertEquals(404, call(path = "devicestatus/${id(first)}").status)
        assertEquals(409, call("POST", "devicestatus", sample()).status)
    }
}
