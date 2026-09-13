package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Test

class LocalNightscoutInitialSnapshotTest {
    private val gson = Gson()

    @Test
    fun packetsKeepGlobalChronologyDuplicatesAndExactUtf8WithoutMutatingSource() {
        val input = source(80, "\\\"\n" + "\u044f\uD83D\uDE80".repeat(12))
        val before = input.deepCopy()
        val packets = requireNotNull(LocalNightscoutInitialSnapshot.packets(gson, input, 700))
        assertThat(packets.size).isGreaterThan(1)
        val all = mutableListOf<Long>()
        var statusCount = 0
        packets.forEach { packet ->
            assertThat(packet.toByteArray(Charsets.UTF_8).size).isAtMost(700)
            val payload = JsonParser.parseString(packet.drop(2)).asJsonArray[1].asJsonObject
            if (payload.has("status")) statusCount++
            else assertThat(payload["delta"].asBoolean).isTrue()
            val times = listOf("treatments", "sgvs").flatMap { key ->
                payload.getAsJsonArray(key).map { it.asJsonObject["date"].asLong }
            }.sorted()
            all.addAll(times)
        }
        assertThat(all).containsExactlyElementsIn((0 until 80).map { it.toLong() }).inOrder()
        assertThat(statusCount).isEqualTo(1)
        assertThat(input).isEqualTo(before)
    }

    @Test
    fun oversizeRowAndRetainedSnapshotBudgetFailInsteadOfDroppingRecords() {
        assertThat(LocalNightscoutInitialSnapshot.packets(gson, source(1, "x".repeat(2_000)), 500)).isNull()
        assertThat(LocalNightscoutInitialSnapshot.packets(gson, source(1_200, "x".repeat(900)), 64 * 1024)).isNull()
        assertThat(LocalNightscoutInitialSnapshot.packets(gson, source(2_401, ""), 64 * 1024)).isNull()
        assertThat(LocalNightscoutInitialSnapshot.packets(gson, source(1, ""), 0)).isNull()
    }

    @Test
    fun emptySnapshotHasOneStatusPacketAndEqualTimestampsAreNotDeduplicated() {
        val empty = requireNotNull(LocalNightscoutInitialSnapshot.packets(gson, source(0, ""), 700))
        assertThat(empty).hasSize(1)
        val input = source(2, "same")
        input.getAsJsonArray("sgvs")[0].asJsonObject.addProperty("date", 0L)
        val encoded = requireNotNull(LocalNightscoutInitialSnapshot.packets(gson, input, 700))
        val payload = JsonParser.parseString(encoded.single().drop(2)).asJsonArray[1].asJsonObject
        assertThat(payload.getAsJsonArray("sgvs").size()).isEqualTo(1)
        assertThat(payload.getAsJsonArray("treatments").size()).isEqualTo(1)
    }

    @Test
    fun exactPacketCountLimitSucceedsAndNextPacketFailsWithoutPartialHistory() {
        val packets = requireNotNull(LocalNightscoutInitialSnapshot.packets(gson, source(64, "x".repeat(400)), 600))
        assertThat(packets).hasSize(64)
        assertThat(LocalNightscoutInitialSnapshot.packets(gson, source(65, "x".repeat(400)), 600)).isNull()
    }

    @Test
    fun exactRetainedByteLimitSucceedsAndOneExtraByteFails() {
        val emptyNote = source(1, "")
        val event = JsonArray().apply { add("dataUpdate"); add(emptyNote) }
        val overhead = ("42" + gson.toJson(event)).toByteArray(Charsets.UTF_8).size
        val limit = LocalNightscoutInitialSnapshot.MAX_RETAINED_BYTES
        val exact = source(1, "x".repeat(limit - overhead))
        val packets = requireNotNull(LocalNightscoutInitialSnapshot.packets(gson, exact, limit))
        assertThat(packets.single().toByteArray(Charsets.UTF_8).size).isEqualTo(limit)
        assertThat(LocalNightscoutInitialSnapshot.packets(gson, source(1, "x".repeat(limit - overhead + 1)), limit)).isNull()
    }

    private fun source(count: Int, note: String) = JsonObject().apply {
        add("status", JsonObject().apply { addProperty("status", "ok") })
        val therapy = JsonArray()
        val glucose = JsonArray()
        for (index in (0 until count).reversed()) {
            val row = JsonObject().apply {
                addProperty("date", index.toLong())
                addProperty("notes", note)
            }
            if (index % 2 == 0) therapy.add(row) else glucose.add(row)
        }
        add("treatments", therapy)
        add("sgvs", glucose)
    }
}
