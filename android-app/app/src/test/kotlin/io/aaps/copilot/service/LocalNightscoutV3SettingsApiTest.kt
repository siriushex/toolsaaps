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
class LocalNightscoutV3SettingsApiTest {
    private lateinit var db: CopilotDatabase
    private lateinit var api: LocalNightscoutV3EntriesApi
    private val now = 1_800_000_000_000L
    private val root = "/api/v3/settings"
    private val offerId = "aaps_clientcontrol_offer_11111111-2222-4333-8444-555555555555"
    private fun doc(value: String = "one") = """{"date":946684800001,"utcOffset":0,"app":"AAPS","schemaVersion":1,"opaque":{"value":"$value"}}"""
    private suspend fun call(method: String = "GET", suffix: String = "", body: String = "",
                             query: Map<String, List<String>> = emptyMap()) =
        api.handle(method, root + suffix, query) { body.toByteArray() }
    private fun body(result: LocalV3HttpResult) = Json.parseToJsonElement(result.body).jsonObject
    private fun rows(result: LocalV3HttpResult) = body(result).getValue("result").jsonArray.map { it.jsonObject }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CopilotDatabase::class.java)
            .allowMainThreadQueries().build()
        api = LocalNightscoutV3EntriesApi(db) { now }
    }
    @After fun tearDown() = db.close()

    @Test fun namedPutIsAnIdempotentUpsertAndPatchPreservesCommonFields() = runBlocking {
        val first = call("PUT", "/fixture-settings", doc())
        assertEquals(201, first.status)
        assertEquals("fixture-settings", body(first)["identifier"]!!.jsonPrimitive.content)
        assertEquals(200, call("PUT", "/fixture-settings", doc()).status)
        assertEquals(first.revision, call("PUT", "/fixture-settings", doc()).revision)
        val patched = call("PATCH", "/fixture-settings", """{"opaque":{"value":"two"}}""")
        assertEquals(200, patched.status)
        assertTrue(patched.revision!! > first.revision!!)
        val saved = body(call(suffix = "/fixture-settings"))["result"]!!.jsonObject
        assertEquals(946684800001, saved["date"]!!.jsonPrimitive.long)
        assertEquals("two", saved["opaque"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertTrue(db.glucoseDao().since(0).isEmpty())
        assertTrue(db.therapyDao().since(0).isEmpty())
    }

    @Test fun postProducesStableReceiptAndReplayAcceptsMatchingServerMetadata() = runBlocking {
        val first = call("POST", body = doc())
        assertEquals(201, first.status)
        val id = body(first)["identifier"]!!.jsonPrimitive.content
        assertEquals(first.revision, call("POST", body = doc()).revision)
        val read = body(call(suffix = "/$id"))["result"]!!.jsonObject
        assertEquals(200, call("PUT", "/$id", read.toString()).status)
        assertEquals(first.revision, call("PUT", "/$id", read.toString()).revision)
        val forged = JsonObject(read + ("srvModified" to JsonPrimitive(5)))
        assertEquals(400, call("PUT", "/$id", forged.toString()).status)
    }

    @Test fun historyUsesModificationOrderAndSoftDeletionCannotResurrect() = runBlocking {
        val first = call("PUT", "/first-settings", doc())
        val second = call("PUT", "/second-settings", doc("two"))
        assertEquals(1, rows(call(suffix = "/history/0", query = mapOf("limit" to listOf("1")))).size)
        assertEquals(first.revision, call(suffix = "/history/0", query = mapOf("limit" to listOf("1"))).revision)
        val deleted = call("DELETE", "/first-settings")
        assertEquals(200, deleted.status)
        val changes = rows(call(suffix = "/history/${second.revision}"))
        assertEquals(false, changes.single()["isValid"]!!.jsonPrimitive.boolean)
        assertEquals(404, call(suffix = "/first-settings").status)
        assertEquals(410, call("PUT", "/first-settings", doc("new")).status)
        assertEquals(1, rows(call()).size)
    }

    @Test fun searchRefusesTruncationInsteadOfHidingPendingControlDocuments() = runBlocking {
        call("PUT", "/first-settings", doc())
        call("PUT", "/second-settings", doc())
        assertEquals(507, call(query = mapOf("limit" to listOf("1"))).status)
        assertEquals(2, rows(call(query = mapOf("limit" to listOf("2")))).size)
    }

    @Test fun permanentOfferDeletionErasesWrappedPayloadButRetainsNonSecretRevisionReceipt() = runBlocking {
        val input = doc().dropLast(1) + ",\"offer\":{\"wrappedB64\":\"synthetic-ciphertext\"}}"
        val first = call("PUT", "/$offerId", input)
        assertEquals(201, first.status)
        val erased = call("DELETE", "/$offerId", query = mapOf("permanent" to listOf("true")))
        assertEquals(200, erased.status)
        assertEquals(404, call(suffix = "/$offerId").status)
        val receipt = rows(call(suffix = "/history/${first.revision}")).single()
        assertEquals(false, receipt["isValid"]!!.jsonPrimitive.boolean)
        assertFalse(receipt.toString().contains("synthetic-ciphertext"))
        assertFalse(receipt.containsKey("offer"))
        val stored = LocalNightscoutV3Store(db).get(LocalV3Collection.SETTINGS, offerId)!!
        assertEquals("{}", stored.payloadJson)
        assertEquals(erased.revision, call("DELETE", "/$offerId", query = mapOf("permanent" to listOf("true"))).revision)
        assertEquals(410, call("PUT", "/$offerId", input).status)
        call("PUT", "/ordinary-settings", doc())
        assertEquals(501, call("DELETE", "/ordinary-settings", query = mapOf("permanent" to listOf("true"))).status)
    }

    @Test fun invalidCommonFieldsTypesFiltersAndRawPairingSecretsNeverWrite() = runBlocking {
        listOf("{}", "[]", "{\"date\":1,\"date\":2}",
            doc().replace("946684800001", "946684800000"),
            doc().replace("946684800001", "\"946684800001\""),
            doc().replace("\"AAPS\"", "true"), doc().replace("\"utcOffset\":0", "\"utcOffset\":0.5"),
            doc().dropLast(1) + ",\"secretHex\":\"synthetic\"}",
            doc().dropLast(1) + ",\"opaque2\":{\"secretHex\":\"synthetic\"}}",
            doc().dropLast(1) + ",\"isValid\":false}", doc().dropLast(1) + ",\"srvModified\":1}",
            doc().dropLast(1) + ",\"opaque2\":1e999}").forEach {
            assertEquals(400, call("PUT", "/bad-settings", it).status)
        }
        assertEquals(400, call(query = mapOf("limit" to listOf("0"))).status)
        assertEquals(400, call(query = mapOf("unknown" to listOf("1"))).status)
        assertEquals(400, call(query = mapOf("limit" to listOf("1", "2"))).status)
        assertEquals(400, call(suffix = "/history/0/extra").status)
        assertEquals(0, db.localNightscoutV3Dao().count())
    }

    @Test fun immutableCommonFieldsAndControllerOwnedSlotsCannotBeOverwritten() = runBlocking {
        call("PUT", "/fixture-settings", doc())
        assertEquals(400, call("PATCH", "/fixture-settings", """{"date":946684800002}""").status)
        assertEquals(400, call("PATCH", "/fixture-settings", """{"utcOffset":1}""").status)
        assertEquals(404, call("PATCH", "/absent-settings", doc()).status)
        val id = "aaps_clientcontrol_cmd_batch_prepare_11111111-2222-4333-8444-555555555555"
        LocalNightscoutV3Store(db).create(LocalV3DocumentInput(LocalV3Collection.SETTINGS, id,
            "local-v3-target", 946684800001, doc()))
        assertEquals(403, call("PUT", "/$id", doc("forged")).status)
        assertEquals(403, call("DELETE", "/$id").status)
        assertEquals(200, call(suffix = "/$id").status)
    }

    @Test fun parallelUpsertIsSingleRevisionAndSettingsPermissionDoesNotClaimFullSync() = runBlocking {
        val writes = coroutineScope { (1..8).map { async(Dispatchers.Default) { call("PUT", "/fixture-settings", doc()) } }.awaitAll() }
        assertEquals(1, writes.count { it.status == 201 })
        assertEquals(1, writes.map { it.revision }.distinct().size)
        val status = api.handle("GET", "/api/v3/status", emptyMap())
        val permissions = body(status)["result"]!!.jsonObject["apiPermissions"]!!.jsonObject
        assertEquals("crud", permissions["settings"]!!.jsonPrimitive.content)
        assertEquals("crud", permissions["treatments"]!!.jsonPrimitive.content)
        assertEquals("", permissions["profile"]!!.jsonPrimitive.content)
    }
}
