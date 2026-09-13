package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RemoteTherapyBatchWriterTest {

    @Test
    fun transactionTimeCanonicalRowsWinAndIdsAreLoadedInBoundedBatches() = runTest {
        val latest = (1..5).associate { index ->
            val id = "remote-$index"
            id to row(
                id = id,
                payload = mapOf(
                    "aapsCarbId" to index.toString(),
                    "aapsCarbDigest" to "latest-$index",
                    "aapsCarbIsValid" to "false",
                    "aapsCarbAmount" to "0",
                    "notes" to "old notes",
                    "insulin" to "1.0"
                )
            )
        }
        val incoming = (1..5).map { index ->
            row(
                id = "remote-$index",
                timestamp = 2_000L + index,
                type = "meal_bolus",
                payload = mapOf(
                    "aapsCarbId" to index.toString(),
                    "aapsCarbDigest" to "stale-precomputed-$index",
                    "aapsCarbIsValid" to "true",
                    "notes" to "updated notes $index",
                    "insulin" to "2.5"
                )
            )
        }
        var transactionActive = false
        val loadedIdBatches = mutableListOf<List<String>>()
        val upsertCalls = mutableListOf<List<TherapyEventEntity>>()
        val writer = RemoteTherapyBatchWriter(
            transactionRunner = RemoteTherapyWriteTransactionRunner { block ->
                assertThat(transactionActive).isFalse()
                transactionActive = true
                try {
                    block()
                } finally {
                    transactionActive = false
                }
            },
            loadLatestByIds = { ids ->
                assertThat(transactionActive).isTrue()
                loadedIdBatches += ids
                ids.mapNotNull(latest::get)
            },
            upsertAll = { rows ->
                assertThat(transactionActive).isTrue()
                upsertCalls += rows
            },
            decodePayload = ::parseTherapyPayloadJsonObject,
            encodePayload = Gson()::toJson,
            batchSize = 2
        )

        writer.write(incoming)

        assertThat(loadedIdBatches).containsExactly(
            listOf("remote-1", "remote-2"),
            listOf("remote-3", "remote-4"),
            listOf("remote-5")
        ).inOrder()
        assertThat(upsertCalls).hasSize(1)
        assertThat(upsertCalls.single()).hasSize(5)
        val written = upsertCalls.single().first()
        val payload = decodePayload(written.payloadJson)
        assertThat(written.timestamp).isEqualTo(2_001L)
        assertThat(written.type).isEqualTo("meal_bolus")
        assertThat(payload["aapsCarbDigest"].asString).isEqualTo("latest-1")
        assertThat(payload["aapsCarbIsValid"].asBoolean).isFalse()
        assertThat(payload["aapsCarbAmount"].asInt).isEqualTo(0)
        assertThat(payload["notes"].asString).isEqualTo("updated notes 1")
        assertThat(payload["insulin"].asString).isEqualTo("2.5")
    }

    @Test
    fun writeFailurePropagatesAndFakeTransactionRollsBackPartialStore() = runTest {
        val original = row(
            id = "remote-1",
            payload = mapOf(
                "aapsCarbId" to "1",
                "aapsCarbDigest" to "latest",
                "notes" to "old notes"
            )
        )
        val store = linkedMapOf(original.id to original)
        val failure = IllegalStateException("injected write failure")
        var rollbacks = 0
        val writer = RemoteTherapyBatchWriter(
            transactionRunner = RemoteTherapyWriteTransactionRunner { block ->
                val snapshot = LinkedHashMap(store)
                try {
                    block()
                } catch (error: Throwable) {
                    rollbacks += 1
                    store.clear()
                    store.putAll(snapshot)
                    throw error
                }
            },
            loadLatestByIds = { ids -> ids.mapNotNull(store::get) },
            upsertAll = { rows ->
                rows.forEach { store[it.id] = it }
                throw failure
            },
            decodePayload = ::parseTherapyPayloadJsonObject,
            encodePayload = Gson()::toJson
        )
        val incoming = row(
            id = "remote-1",
            timestamp = 2_000L,
            payload = mapOf(
                "aapsCarbId" to "1",
                "aapsCarbDigest" to "stale",
                "notes" to "new notes"
            )
        )

        val observedFailure = runCatching {
            writer.write(listOf(incoming))
        }.exceptionOrNull()

        assertThat(observedFailure).isSameInstanceAs(failure)
        assertThat(rollbacks).isEqualTo(1)
        assertThat(store).containsExactly(original.id, original)
    }

    private fun row(
        id: String,
        timestamp: Long = 1_000L,
        type: String = "carbs",
        payload: Map<String, String>
    ) = TherapyEventEntity(
        id = id,
        timestamp = timestamp,
        type = type,
        payloadJson = encodePayload(payload)
    )

    private fun decodePayload(raw: String): JsonObject = JsonParser.parseString(raw).asJsonObject

    private fun encodePayload(payload: Map<String, String>): String = Gson().toJson(
        JsonObject().apply {
            payload.forEach { (key, value) ->
                when {
                    key == "aapsCarbId" && value.all { it in '0'..'9' } ->
                        addProperty(key, value.toLong())
                    key == "aapsCarbIsValid" -> addProperty(key, value.toBooleanStrict())
                    key == "aapsCarbAmount" -> addProperty(key, value.toInt())
                    else -> addProperty(key, value)
                }
            }
        }
    )
}
