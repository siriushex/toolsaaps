package io.aaps.copilot.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClinicalReportDatasetBuilderRoomTest {

    private lateinit var db: CopilotDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, CopilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun roomProjectionsBuildBoundedDeduplicatedAndRedactedPayload() = runBlocking {
        val ts = NOW_TS - MINUTE_MS
        db.glucoseDao().upsertAll(
            listOf(
                glucose(NOW_TS - 30L * DAY_MS - 1L, 4.0, "nightscout", "OK"),
                glucose(ts, 9.0, "com.private.cgm.package", "OK"),
                glucose(ts, 6.0, "nightscout", "OK"),
                glucose(ts, 2.5, "aaps_broadcast", "SENSOR_ERROR"),
                glucose(NOW_TS + 1L, 20.0, "nightscout", "OK")
            )
        )
        db.therapyDao().upsertAll(
            listOf(
                therapy(
                    id = "db-row-id-424242",
                    ts = ts,
                    type = "meal_bolus",
                    payload = PRIVATE_THERAPY_PAYLOAD
                ),
                therapy(
                    id = "duplicate-private-row",
                    ts = ts,
                    type = "meal_bolus",
                    payload = """{"enteredCarbs":20,"enteredInsulin":2}"""
                ),
                therapy(
                    id = "target-private-row",
                    ts = ts + 123L,
                    type = "temp_target",
                    payload = """
                        {
                          "targetBottom":100,
                          "targetTop":110,
                          "duration":30,
                          "isValid":true,
                          "notes":"free form target notes private"
                        }
                    """.trimIndent()
                ),
                therapy(
                    id = "br-local_broadcast-meal_bolus-artifact",
                    ts = ts,
                    type = "meal_bolus",
                    payload = """{"carbs":99,"units":9}"""
                ),
                therapy(
                    id = "unlisted-therapy",
                    ts = ts,
                    type = "profile_switch",
                    payload = """{"units":99}"""
                ),
                therapy(
                    id = "before-therapy-lookback",
                    ts = NOW_TS - 37L * DAY_MS - 1L,
                    type = "meal_bolus",
                    payload = """{"carbs":99,"units":9}"""
                ),
                therapy(
                    id = "after-now-therapy",
                    ts = NOW_TS + 1L,
                    type = "meal_bolus",
                    payload = """{"carbs":99,"units":9}"""
                )
            )
        )
        db.forecastDao().insertAll(
            listOf(
                forecast(ts),
                forecast(ts),
                forecast(NOW_TS + 1L)
            )
        )
        db.telemetryDao().upsertAll(
            listOf(
                telemetry("telemetry-private-row", ts),
                telemetry("telemetry-duplicate-row", ts),
                producerTelemetry(
                    "isf_runtime_selected_value",
                    ts + 1L,
                    2.5,
                    "mmol/L/U"
                ),
                producerTelemetry(
                    "cr_runtime_selected_value",
                    ts + 2L,
                    10.0,
                    "g/U"
                ),
                producerTelemetry(
                    "uam_calculated_carbs_grams",
                    ts + 3L,
                    18.0,
                    "g"
                ),
                producerTelemetry(
                    "uam_calculated_confidence",
                    ts + 4L,
                    0.75,
                    null
                ),
                telemetry("telemetry-after", NOW_TS + 1L),
                TelemetrySampleEntity(
                    id = "unlisted-telemetry",
                    timestamp = ts,
                    source = "nightscout",
                    key = "private_key",
                    valueDouble = 99.0,
                    valueText = null,
                    unit = "private",
                    quality = "OK"
                )
            )
        )

        val gson = Gson()
        val calibrationRepository = GlucoseCalibrationRepository(
            db = db,
            gson = gson,
            auditLogger = AuditLogger(db.auditLogDao(), gson) { NOW_TS }
        )
        val payload = ClinicalReportDatasetBuilder(
            db = db,
            glucoseCalibrationRepository = calibrationRepository
        ).build(NOW_TS, ZoneId.of("UTC"))

        assertEquals(1, payload.dataset.detail24h.glucose.size)
        assertEquals(6.0, payload.dataset.detail24h.glucose.single().mmol, 0.0)
        assertEquals(2, payload.dataset.detail24h.therapy.size)
        assertEquals(1, payload.dataset.detail24h.targets.size)
        assertEquals(1, payload.dataset.detail24h.forecasts.size)
        assertEquals(5, payload.dataset.detail24h.telemetry.size)
        assertEquals(
            listOf(
                "iob_units",
                "isf_runtime_selected_value",
                "cr_runtime_selected_value",
                "uam_calculated_carbs_grams",
                "uam_calculated_confidence"
            ),
            payload.dataset.detail24h.telemetry.map { it.key }
        )
        assertEquals(4.0, payload.dataset.summary7d.totalInsulinU, 0.0)
        assertEquals(40.0, payload.dataset.summary7d.totalCarbsG, 0.0)
        val target = payload.dataset.detail24h.targets.single()
        assertEquals(ts + 123L, target.ts)
        assertEquals(5.556, target.lowMmol ?: 0.0, 0.001)
        assertEquals(6.111, target.highMmol ?: 0.0, 0.001)
        assertEquals(30L * MINUTE_MS, target.durationMs)
        assertEquals(target.ts + 30L * MINUTE_MS, target.endTs)
        assertFalse(target.cancelled ?: true)
        FORBIDDEN_VALUES.forEach { value ->
            assertTrue("Leaked forbidden value: $value", value !in payload.compactJson)
        }
    }

    @Test
    fun eventTimelineHistoryReadIncludesTherapyStartingBeforeRequestedWindow() = runBlocking {
        val fromTs = NOW_TS - DAY_MS
        val throughTs = NOW_TS
        val overlappingStart = fromTs - 60L * MINUTE_MS
        db.therapyDao().upsertAll(
            listOf(
                therapy(
                    id = "overlapping-event",
                    ts = overlappingStart,
                    type = "illness",
                    payload = """{"eventId":"overlapping-event","endTs":${fromTs + MINUTE_MS}}"""
                )
            )
        )

        val rows = db.therapyDao().between(
            (fromTs - 24L * 60L * 60L * 1_000L).coerceAtLeast(0L),
            throughTs
        )

        assertTrue(rows.any { it.id == "overlapping-event" })
    }

    private fun glucose(
        ts: Long,
        mmol: Double,
        source: String,
        quality: String
    ) = GlucoseSampleEntity(
        timestamp = ts,
        mmol = mmol,
        source = source,
        quality = quality
    )

    private fun therapy(
        id: String,
        ts: Long,
        type: String,
        payload: String
    ) = TherapyEventEntity(
        id = id,
        timestamp = ts,
        type = type,
        payloadJson = payload
    )

    private fun forecast(ts: Long) = ForecastEntity(
        timestamp = ts,
        horizonMinutes = 30,
        valueMmol = 6.5,
        ciLow = 5.5,
        ciHigh = 7.5,
        modelVersion = "com.private.forecast.package"
    )

    private fun telemetry(id: String, ts: Long) = TelemetrySampleEntity(
        id = id,
        timestamp = ts,
        source = "com.private.telemetry.package",
        key = "iob_units",
        valueDouble = 1.5,
        valueText = "free form telemetry note private",
        unit = "U",
        quality = "OK"
    )

    private fun producerTelemetry(
        key: String,
        ts: Long,
        value: Double,
        unit: String?
    ) = TelemetrySampleEntity(
        id = "tm-producer-$key-$ts",
        timestamp = ts,
        source = "copilot_runtime",
        key = key,
        valueDouble = value,
        valueText = null,
        unit = unit,
        quality = "OK"
    )

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24L * 60L * MINUTE_MS
        const val NOW_TS = 1_753_963_200_000L
        const val PRIVATE_THERAPY_PAYLOAD = """
            {
              "carbs":20,
              "units":2,
              "name":"Ada Patient",
              "phone":"+995555010101",
              "email":"ada.patient@example.com",
              "androidId":"android-id-a1b2c3",
              "serial":"sensor-serial-7788",
              "nightscoutUrl":"https://nightscout.private.example",
              "nightscoutSecret":"nightscout-secret-xyz",
              "_id":"db-row-id-424242",
              "packageName":"com.private.cgm.package",
              "note":"free form note private",
              "notes":"free form notes private"
            }
        """
        val FORBIDDEN_VALUES = listOf(
            "Ada Patient",
            "+995555010101",
            "ada.patient@example.com",
            "android-id-a1b2c3",
            "sensor-serial-7788",
            "https://nightscout.private.example",
            "nightscout-secret-xyz",
            "db-row-id-424242",
            "com.private.cgm.package",
            "com.private.forecast.package",
            "com.private.telemetry.package",
            "free form note private",
            "free form notes private",
            "free form target notes private",
            "free form telemetry note private"
        )
    }
}
