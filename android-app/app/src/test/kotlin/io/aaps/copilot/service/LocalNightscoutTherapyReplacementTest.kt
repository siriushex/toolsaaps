package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonParser
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LocalNightscoutTherapyReplacementTest {

    @Test
    fun socketReplacementPersistsCanonicalMetadataAndRefreshesClinicalFields() = runTest {
        val existing = row(
            timestamp = 1_000L,
            payloadJson = """{
                "aapsCarbId":46,
                "aapsCarbDigest":"canonical",
                "notesSha256":"canonical-notes-digest",
                "notes":"old notes",
                "carbs":10
            }""".trimIndent()
        )
        val incoming = row(
            timestamp = 2_000L,
            payloadJson = """{
                "aapsCarbId":46.0,
                "aapsCarbDigest":"stale",
                "notesSha256":"stale-notes-digest",
                "notes":"updated notes",
                "carbs":18,
                "nested":{"source":"socket"}
            }""".trimIndent()
        )
        val store = linkedMapOf(existing.id to existing)

        val persisted = upsertLocalNightscoutSocketTherapyReplacement(
            incomingRow = incoming,
            loadLatest = { id -> store[id] },
            upsertAll = { rows -> rows.forEach { store[it.id] = it } },
            gson = Gson()
        )

        assertThat(store[incoming.id]).isEqualTo(persisted)
        val payload = JsonParser.parseString(persisted.payloadJson).asJsonObject
        assertThat(persisted.timestamp).isEqualTo(2_000L)
        assertThat(payload["aapsCarbId"].toString()).isEqualTo("46")
        assertThat(payload["aapsCarbDigest"].asString).isEqualTo("canonical")
        assertThat(payload["notesSha256"].asString).isEqualTo("canonical-notes-digest")
        assertThat(payload["notes"].asString).isEqualTo("updated notes")
        assertThat(payload["carbs"].asInt).isEqualTo(18)
        assertThat(payload["nested"]).isEqualTo(
            JsonParser.parseString("""{"source":"socket"}""")
        )
    }

    private fun row(
        timestamp: Long,
        payloadJson: String
    ) = TherapyEventEntity(
        id = "socket-treatment",
        timestamp = timestamp,
        type = "carbs",
        payloadJson = payloadJson
    )
}
