package io.aaps.copilot.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EnergyProfileTelemetryRoomTest {
    @Test fun onlySensorEvidenceInsideTheCompletedWindowIsMaterialized() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java).build()
        try {
            val rows = List(2_000) { sample("noise-$it", 100L + it % 101, "forecast_factor", 1.0) } +
                listOf(sample("before", 99, "sensor_block", 1.0),
                    sample("start", 100, "SENSOR_BLOCK", 1.0),
                    sample("text", 150, "SeNsOr_BlOcK", null, "true"),
                    sample("clear", 150, "sensor_block", 0.0),
                    sample("end", 200, "sensor_block", 1.0),
                    sample("after", 201, "sensor_block", 1.0))
            db.telemetryDao().upsertAll(rows)
            val selected = db.telemetryDao().betweenForEnergyProfileInference(100, 200)
            assertThat(selected).containsExactlyElementsIn(rows.filter {
                it.timestamp in 100..200 && it.key.equals("sensor_block", true)
            }.sortedWith(compareBy<TelemetrySampleEntity> { it.timestamp }.thenBy { it.id })).inOrder()
            assertThat(selected).hasSize(4)
            db.openHelper.readableDatabase.query(
                "EXPLAIN QUERY PLAN SELECT * FROM telemetry_samples WHERE timestamp BETWEEN 100 AND 200 " +
                    "AND key IN (SELECT DISTINCT key FROM telemetry_samples " +
                    "WHERE key = 'sensor_block' COLLATE NOCASE) ORDER BY timestamp ASC, id ASC"
            ).use { cursor ->
                val plan = buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
                assertThat(plan.any { it.contains("index_telemetry_samples_key_timestamp") }).isTrue()
                assertThat(plan.any { it.contains("COVERING INDEX index_telemetry_samples_key") }).isTrue()
            }
            assertThat(db.telemetryDao().betweenForEnergyProfileInference(201, 200)).isEmpty()
            assertThat(db.telemetryDao().since(0)).hasSize(rows.size)
        } finally { db.close() }
    }

    private fun sample(id: String, timestamp: Long, key: String, value: Double?, text: String? = null) =
        TelemetrySampleEntity(id, timestamp, "fixture", key, value, text, null, "OK")
}
