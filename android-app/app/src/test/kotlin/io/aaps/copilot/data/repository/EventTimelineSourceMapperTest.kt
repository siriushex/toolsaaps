package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventSource
import io.aaps.copilot.domain.activity.PhysicalActivityBucket
import io.aaps.copilot.domain.model.BloodGlucoseCheck
import io.aaps.copilot.domain.model.BloodGlucoseCheckStatus
import java.time.Instant
import org.junit.Test

class EventTimelineSourceMapperTest {
    private val repository = EventTimelineRepository()

    @Test
    fun plannedScheduleMaterializesFutureOccurrenceFromAuthoritativeRow() {
        val event = PlannedActivityEventEntity(
            eventId = "activity-1",
            enabled = true,
            title = "Run",
            activityType = "AEROBIC",
            intensity = "HIGH",
            localStartIso = "2026-08-14T10:00:00",
            durationMinutes = 45,
            timezoneId = "UTC",
            recurrenceDaysMask = 0,
            recurrenceEndEpochDay = null,
            revision = 3,
            createdAtMs = 1L,
            updatedAtMs = 2L
        )
        val fromTs = Instant.parse("2026-08-13T00:00:00Z").toEpochMilli()
        val throughTs = Instant.parse("2026-08-15T00:00:00Z").toEpochMilli()

        val result = repository.plannedActivityEvents(listOf(event), fromTs, throughTs)

        assertThat(result).hasSize(1)
        assertThat(result.single().type).isEqualTo(CompensationEventType.ACTIVITY)
        assertThat(result.single().subtype).isEqualTo("PLANNED")
        assertThat(result.single().source).isEqualTo(EventSource.USER)
        assertThat(result.single().startTs).isGreaterThan(fromTs)
        assertThat(result.single().endTs - result.single().startTs).isEqualTo(45L * 60_000L)
    }

    @Test
    fun manualBloodCheckMapsToCalibrationWithoutCopyingDatabaseIdentityIntoProvenance() {
        val result = repository.calibrationEvents(
            listOf(
                BloodGlucoseCheck(
                    id = "blood-db-id",
                    timestamp = 10_000L,
                    mmol = 6.2,
                    units = "mmol/L",
                    source = "MANUAL",
                    note = "meter check",
                    enteredAt = 10_001L,
                    sensorSessionKey = "sensor-secret",
                    lagAlignedTs = null,
                    matchedRawGlucose = null,
                    status = BloodGlucoseCheckStatus.VALID,
                    reason = null
                )
            )
        )

        assertThat(result).hasSize(1)
        assertThat(result.single().type).isEqualTo(CompensationEventType.SENSOR_CALIBRATION)
        assertThat(result.single().source).isEqualTo(EventSource.USER)
        assertThat(result.single().provenance).isEqualTo("blood_glucose_checks")
        assertThat(result.single().attributes).doesNotContainKey("sensorSessionKey")
    }

    @Test
    fun actualActivitySamplesBecomeAutomaticContextWithoutTherapyRows() {
        val result = repository.actualActivityEvents(
            listOf(
                ActualActivityTimelineSample(
                    timestamp = 20_000L,
                    source = "health_connect",
                    activityRatio = 1.4,
                    label = "walking"
                )
            )
        )

        assertThat(result).hasSize(1)
        assertThat(result.single().type).isEqualTo(CompensationEventType.ACTIVITY)
        assertThat(result.single().subtype).isEqualTo("ACTUAL")
        assertThat(result.single().source).isEqualTo(EventSource.AUTOMATIC)
    }

    @Test
    fun actualActivityBucketsBecomeOneStableEpisodeInsteadOfPointEvents() {
        val minute = 60_000L
        val buckets = listOf(
            PhysicalActivityBucket(0L, 1L, minute, 1.3, 1.2, 4, "local_sensor", "OK"),
            PhysicalActivityBucket(5L * minute, 5L * minute, 6L * minute, 1.7, 1.6, 2, "health_connect", "TRUSTED")
        )

        val first = repository.actualActivityEventsFromBuckets(buckets)
        val reordered = repository.actualActivityEventsFromBuckets(buckets.reversed())

        assertThat(first).hasSize(1)
        assertThat(first).isEqualTo(reordered)
        assertThat(first.single().startTs).isEqualTo(1L)
        assertThat(first.single().endTs).isEqualTo(6L * minute)
        assertThat(first.single().attributes["sources"]).isEqualTo("health_connect,local_sensor")
        assertThat(first.single().attributes["sampleCount"]).isEqualTo("6")
        assertThat(first.single().attributes["intensityTransitions"]).contains("HIGH")
    }
}
