package io.aaps.copilot.domain.activity

import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import org.junit.Test

class LocalActivityMetricsEstimatorTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val estimator = LocalActivityMetricsEstimator(
        strideMeters = 0.8,
        kcalPerStep = 0.04
    )

    @Test
    fun computesStepsDistanceAndCaloriesFromStepCounter() {
        val t0 = 1_710_000_000_000L
        val (s1, snap1) = estimator.update(
            counterTotal = 10_000.0,
            timestamp = t0,
            previous = LocalActivityState(),
            zoneId = zone
        )
        val (_, snap2) = estimator.update(
            counterTotal = 10_250.0,
            timestamp = t0 + 10 * 60_000L,
            previous = s1,
            zoneId = zone
        )

        assertThat(snap1.stepsToday).isEqualTo(0.0)
        assertThat(snap2.stepsToday).isEqualTo(250.0)
        assertThat(snap2.distanceKmToday).isWithin(0.0001).of(0.2)
        assertThat(snap2.activeCaloriesKcalToday).isWithin(0.0001).of(10.0)
    }

    @Test
    fun accumulatesActiveMinutesOnSustainedPace() {
        val t0 = 1_710_000_000_000L
        val (s1, _) = estimator.update(
            counterTotal = 5_000.0,
            timestamp = t0,
            previous = LocalActivityState(),
            zoneId = zone
        )
        val (s2, snap2) = estimator.update(
            counterTotal = 5_140.0,
            timestamp = t0 + 5 * 60_000L,
            previous = s1,
            zoneId = zone
        )
        val (_, snap3) = estimator.update(
            counterTotal = 5_280.0,
            timestamp = t0 + 10 * 60_000L,
            previous = s2,
            zoneId = zone
        )

        assertThat(snap2.activeMinutesToday).isWithin(0.001).of(5.0)
        assertThat(snap3.activeMinutesToday).isWithin(0.001).of(10.0)
        assertThat(snap3.activityRatio).isAtLeast(1.1)
    }

    @Test
    fun resetsBaselineOnNextDay() {
        val t0 = 1_710_000_000_000L
        val (s1, _) = estimator.update(
            counterTotal = 12_000.0,
            timestamp = t0,
            previous = LocalActivityState(),
            zoneId = zone
        )
        val (s2, snap2) = estimator.update(
            counterTotal = 12_300.0,
            timestamp = t0 + 60 * 60_000L,
            previous = s1,
            zoneId = zone
        )
        val nextDay = t0 + 24 * 60 * 60_000L
        val (_, snap3) = estimator.update(
            counterTotal = 13_000.0,
            timestamp = nextDay,
            previous = s2,
            zoneId = zone
        )

        assertThat(snap2.stepsToday).isEqualTo(300.0)
        assertThat(snap3.stepsToday).isEqualTo(0.0)
        assertThat(snap3.activeMinutesToday).isAtMost(5.0)
    }
}

