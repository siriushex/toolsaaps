package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonParser
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SyncRepositoryTherapyReplacementTest {

    @Test
    fun remoteReplacementLoadsAndPersistsCanonicalMetadataInsideTransaction() = runTest {
        val existing = row(
            timestamp = 1_000L,
            payloadJson = """{
                "aapsCarbId":45,
                "aapsCarbDigest":"canonical",
                "aapsCarbIsValid":false,
                "notes":"old notes",
                "insulin":1.0
            }""".trimIndent()
        )
        val incoming = row(
            timestamp = 2_000L,
            type = "meal_bolus",
            payloadJson = """{
                "aapsCarbId":45.0,
                "aapsCarbDigest":"stale",
                "aapsCarbIsValid":true,
                "notes":"updated notes",
                "insulin":2.5,
                "clinical":{"segments":[1,2]}
            }""".trimIndent()
        )
        val store = linkedMapOf(existing.id to existing)
        var transactionActive = false

        writeSyncRepositoryRemoteTherapyRows(
            incomingRows = listOf(incoming),
            transactionRunner = RemoteTherapyWriteTransactionRunner { block ->
                transactionActive = true
                try {
                    block()
                } finally {
                    transactionActive = false
                }
            },
            loadLatestByIds = { ids ->
                assertThat(transactionActive).isTrue()
                ids.mapNotNull(store::get)
            },
            upsertAll = { rows ->
                assertThat(transactionActive).isTrue()
                rows.forEach { store[it.id] = it }
            },
            gson = Gson()
        )

        val persisted = requireNotNull(store[incoming.id])
        val payload = JsonParser.parseString(persisted.payloadJson).asJsonObject
        assertThat(persisted.timestamp).isEqualTo(2_000L)
        assertThat(persisted.type).isEqualTo("meal_bolus")
        assertThat(payload["aapsCarbId"].toString()).isEqualTo("45")
        assertThat(payload["aapsCarbDigest"].asString).isEqualTo("canonical")
        assertThat(payload["aapsCarbIsValid"].asBoolean).isFalse()
        assertThat(payload["notes"].asString).isEqualTo("updated notes")
        assertThat(payload["insulin"].asDouble).isEqualTo(2.5)
        assertThat(payload["clinical"]).isEqualTo(
            JsonParser.parseString("""{"segments":[1,2]}""")
        )
    }

    private fun row(
        timestamp: Long,
        type: String = "carbs",
        payloadJson: String
    ) = TherapyEventEntity(
        id = "remote-treatment",
        timestamp = timestamp,
        type = type,
        payloadJson = payloadJson
    )
}
