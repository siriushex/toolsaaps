package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.dao.PhysicalActivityMetricBucketProjection
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import io.aaps.copilot.domain.activity.PhysicalActivityTelemetryPolicy
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventSource
import java.time.Instant
import org.junit.Assert.assertThrows
import org.junit.Test

class AlertAiDatasetDerivedBudgetTest {
    @Test
    fun activityRatioBucketReservesBothProjectionsBeforeCreatingEither() {
        val exact = AlertAiRetainedDerivedBudget(maxRows = 2)

        val projections = activityRatioBucket().toClinicalTelemetryProjections { count ->
            exact.reserve(AlertAiDatasetDerivedSourceName.ACTIVITY_DETAIL_PROJECTION, count)
        }

        assertThat(projections.map { it.key }).containsExactly(
            PhysicalActivityTelemetryPolicy.ACTIVITY_RATIO_KEY,
            PhysicalActivityTelemetryPolicy.ACTIVITY_RATIO_PEAK_KEY
        ).inOrder()
        assertThrows(AlertAiContextException.LimitExceeded::class.java) {
            activityRatioBucket().toClinicalTelemetryProjections { count ->
                AlertAiRetainedDerivedBudget(maxRows = 1).reserve(
                    AlertAiDatasetDerivedSourceName.ACTIVITY_DETAIL_PROJECTION,
                    count
                )
            }
        }
    }

    @Test
    fun recurringOccurrencesReserveOneByOneWithExactBoundary() {
        val repository = EventTimelineRepository()
        val fromTs = Instant.parse("2030-01-01T00:00:00Z").toEpochMilli()
        val throughTs = Instant.parse("2030-01-02T23:59:59Z").toEpochMilli()
        val exact = AlertAiRetainedDerivedBudget(maxRows = 3)

        val occurrences = repository.plannedActivityEvents(
            rows = listOf(dailyActivity()),
            fromTs = fromTs,
            throughTs = throughTs,
            reserveOccurrence = { count ->
                exact.reserve(AlertAiDatasetDerivedSourceName.PLANNED_ACTIVITY_OCCURRENCE, count)
            }
        )

        assertThat(occurrences).hasSize(2)
        val overflow = AlertAiRetainedDerivedBudget(maxRows = 2)
        assertThrows(AlertAiContextException.LimitExceeded::class.java) {
            repository.plannedActivityEvents(
                rows = listOf(dailyActivity()),
                fromTs = fromTs,
                throughTs = throughTs,
                reserveOccurrence = { count ->
                    overflow.reserve(AlertAiDatasetDerivedSourceName.PLANNED_ACTIVITY_OCCURRENCE, count)
                }
            )
        }
    }

    @Test
    fun timelineAggregationChargesCanonicalOutputNotDuplicateInputs() {
        val repository = EventTimelineRepository()
        val first = event("same", revision = 1L)
        val replacement = event("same", revision = 2L)
        val exact = AlertAiRetainedDerivedBudget(maxRows = 1)

        val result = repository.aggregate(
            sources = EventTimelineSources(plannedActivity = listOf(first, replacement)),
            nowTs = NOW,
            reserveOutput = { count ->
                exact.reserve(AlertAiDatasetDerivedSourceName.TIMELINE_AGGREGATE_OUTPUT, count)
            }
        )

        assertThat(result).containsExactly(replacement)
        assertThrows(AlertAiContextException.LimitExceeded::class.java) {
            repository.aggregate(
                sources = EventTimelineSources(plannedActivity = listOf(first)),
                nowTs = NOW,
                reserveOutput = { count ->
                    AlertAiRetainedDerivedBudget(maxRows = 0).reserve(
                        AlertAiDatasetDerivedSourceName.TIMELINE_AGGREGATE_OUTPUT,
                        count
                    )
                }
            )
        }
    }

    @Test
    fun retainedDerivedBudgetAllowsExactTotalAndRejectsNextReservation() {
        val observations = mutableListOf<AlertAiDatasetDerivedObservation>()
        val budget = AlertAiRetainedDerivedBudget(
            maxRows = 2,
            probe = AlertAiDatasetDerivedProbe(observations::add)
        )

        budget.reserve(AlertAiDatasetDerivedSourceName.CALIBRATED_GLUCOSE_COPY, 1)
        budget.reserve(AlertAiDatasetDerivedSourceName.TIMELINE_AGGREGATE_OUTPUT, 1)

        assertThrows(AlertAiContextException.LimitExceeded::class.java) {
            budget.reserve(AlertAiDatasetDerivedSourceName.SENSITIVITY_FORECAST_PROJECTION, 1)
        }
        assertThat(observations.map { it.totalAfterReservation }).containsExactly(1L, 2L, 3L)
    }

    private fun activityRatioBucket() = PhysicalActivityMetricBucketProjection(
        bucketTs = NOW,
        firstTs = NOW,
        lastTs = NOW + 1L,
        key = PhysicalActivityTelemetryPolicy.ACTIVITY_RATIO_KEY,
        source = "health_connect",
        firstValue = 1.2,
        lastValue = 1.4,
        minValue = 1.2,
        maxValue = 1.4,
        meanValue = 1.3,
        sampleCount = 2,
        qualityEvidence = "OK",
        trustedSampleCount = 2,
        okSampleCount = 2
    )

    private fun dailyActivity() = PlannedActivityEventEntity(
        eventId = "daily",
        enabled = true,
        title = "Daily walk",
        activityType = "WALKING",
        intensity = "MEDIUM",
        localStartIso = "2030-01-01T10:00:00",
        durationMinutes = 30,
        timezoneId = "UTC",
        recurrenceDaysMask = 0b1111111,
        recurrenceEndEpochDay = null,
        revision = 1L,
        createdAtMs = 1L,
        updatedAtMs = 1L
    )

    private fun event(id: String, revision: Long) = CompensationEvent(
        localId = id,
        startTs = NOW,
        endTs = NOW + 60_000L,
        type = CompensationEventType.ACTIVITY,
        source = EventSource.USER,
        title = "Walk",
        revision = revision
    )

    private companion object {
        val NOW: Long = Instant.parse("2030-01-07T12:00:00Z").toEpochMilli()
    }
}
