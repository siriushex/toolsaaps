package io.aaps.copilot.domain.isfcr

import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.TelemetrySignal
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IsfCrContextModelTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val model = IsfCrContextModel(zoneId = zone)

    @Test
    fun manualTagsToggle_controlsManualTagInfluence() {
        val nowTs = ZonedDateTime.of(2026, 3, 3, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val tags = listOf(
            PhysioContextTag(
                id = "stress-1",
                tsStart = nowTs - 60 * 60_000L,
                tsEnd = nowTs + 60 * 60_000L,
                tagType = "stress",
                severity = 1.0,
                source = "manual",
                note = "high stress"
            )
        )

        val disabled = model.apply(
            nowTs = nowTs,
            isfBase = 2.5,
            crBase = 12.0,
            therapy = emptyList(),
            telemetry = emptyList(),
            tags = tags,
            previous = null,
            settings = IsfCrSettings(useManualTags = false)
        )
        val enabled = model.apply(
            nowTs = nowTs,
            isfBase = 2.5,
            crBase = 12.0,
            therapy = emptyList(),
            telemetry = emptyList(),
            tags = tags,
            previous = null,
            settings = IsfCrSettings(useManualTags = true)
        )

        assertEquals(0.0, disabled.factors["manual_tags_enabled"] ?: -1.0, 1e-9)
        assertEquals(1.0, disabled.factors["stress_factor"] ?: 0.0, 1e-9)
        assertEquals(0.0, disabled.factors["manual_stress_tag"] ?: -1.0, 1e-9)
        assertEquals(1.0, enabled.factors["manual_tags_enabled"] ?: -1.0, 1e-9)
        assertTrue((enabled.factors["stress_factor"] ?: 1.0) < 1.0)
        assertTrue(enabled.isfEff < disabled.isfEff)
    }

    @Test
    fun sensorAgeFactor_decreasesForLongSensorWear() {
        val nowTs = ZonedDateTime.of(2026, 3, 3, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val oldSensorTherapy = listOf(
            TherapyEvent(
                ts = nowTs - 9L * 24L * 60L * 60L * 1000L,
                type = "sensor_change",
                payload = emptyMap()
            )
        )
        val freshSensorTherapy = listOf(
            TherapyEvent(
                ts = nowTs - 24L * 60L * 60L * 1000L,
                type = "sensor_change",
                payload = emptyMap()
            )
        )

        val oldOutput = model.apply(
            nowTs = nowTs,
            isfBase = 2.5,
            crBase = 12.0,
            therapy = oldSensorTherapy,
            telemetry = emptyList(),
            tags = emptyList(),
            previous = null,
            settings = IsfCrSettings()
        )
        val freshOutput = model.apply(
            nowTs = nowTs,
            isfBase = 2.5,
            crBase = 12.0,
            therapy = freshSensorTherapy,
            telemetry = emptyList(),
            tags = emptyList(),
            previous = null,
            settings = IsfCrSettings()
        )

        assertTrue((oldOutput.factors["sensor_age_hours"] ?: 0.0) > 120.0)
        assertTrue((oldOutput.factors["sensor_age_factor"] ?: 1.0) < 1.0)
        assertEquals(1.0, freshOutput.factors["sensor_age_factor"] ?: 0.0, 1e-9)
        assertTrue(oldOutput.isfEff < freshOutput.isfEff)
    }

    @Test
    fun activityFactor_usesStepsRateFallbackWhenDirectRateMissing() {
        val nowTs = ZonedDateTime.of(2026, 3, 3, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val telemetry = listOf(
            TelemetrySignal(ts = nowTs - 15 * 60_000L, key = "steps_count", valueDouble = 10_000.0, valueText = null),
            TelemetrySignal(ts = nowTs, key = "steps_count", valueDouble = 10_240.0, valueText = null)
        )

        val activeOutput = model.apply(
            nowTs = nowTs,
            isfBase = 2.5,
            crBase = 12.0,
            therapy = emptyList(),
            telemetry = telemetry,
            tags = emptyList(),
            previous = null,
            settings = IsfCrSettings(useActivityFactor = true)
        )
        val disabledOutput = model.apply(
            nowTs = nowTs,
            isfBase = 2.5,
            crBase = 12.0,
            therapy = emptyList(),
            telemetry = telemetry,
            tags = emptyList(),
            previous = null,
            settings = IsfCrSettings(useActivityFactor = false)
        )

        assertTrue((activeOutput.factors["steps_rate_15m"] ?: 0.0) > 0.0)
        assertTrue((activeOutput.factors["activity_factor"] ?: 1.0) > 1.0)
        assertEquals(0.0, disabledOutput.factors["steps_rate_15m"] ?: -1.0, 1e-9)
        assertEquals(1.0, disabledOutput.factors["activity_factor"] ?: 0.0, 1e-9)
        assertTrue(activeOutput.isfEff > disabledOutput.isfEff)
    }

    @Test
    fun steroidAndDawnTags_reduceSensitivityFactorsWhenManualTagsEnabled() {
        val nowTs = ZonedDateTime.of(2026, 3, 3, 6, 30, 0, 0, zone).toInstant().toEpochMilli()
        val tags = listOf(
            PhysioContextTag(
                id = "steroid-1",
                tsStart = nowTs - 2 * 60 * 60_000L,
                tsEnd = nowTs + 2 * 60 * 60_000L,
                tagType = "steroids",
                severity = 0.9,
                source = "manual",
                note = "prednisone"
            ),
            PhysioContextTag(
                id = "dawn-1",
                tsStart = nowTs - 2 * 60 * 60_000L,
                tsEnd = nowTs + 2 * 60 * 60_000L,
                tagType = "dawn",
                severity = 0.8,
                source = "manual",
                note = "morning resistance"
            )
        )

        val enabled = model.apply(
            nowTs = nowTs,
            isfBase = 2.5,
            crBase = 12.0,
            therapy = emptyList(),
            telemetry = emptyList(),
            tags = tags,
            previous = null,
            settings = IsfCrSettings(useManualTags = true)
        )
        val disabled = model.apply(
            nowTs = nowTs,
            isfBase = 2.5,
            crBase = 12.0,
            therapy = emptyList(),
            telemetry = emptyList(),
            tags = tags,
            previous = null,
            settings = IsfCrSettings(useManualTags = false)
        )

        assertTrue((enabled.factors["manual_steroid_tag"] ?: 0.0) > 0.0)
        assertTrue((enabled.factors["manual_dawn_tag"] ?: 0.0) > 0.0)
        assertTrue((enabled.factors["steroid_factor"] ?: 1.0) < 1.0)
        assertTrue((enabled.factors["dawn_tag_factor"] ?: 1.0) < 1.0)
        assertTrue(enabled.isfEff < disabled.isfEff)
        assertTrue(enabled.crEff < disabled.crEff)
    }
}
