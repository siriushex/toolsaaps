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
        assertThat(snap2.distanceSource).isEqualTo(LocalActivityMetricSource.DEFAULT_LOCAL_FALLBACK)
        assertThat(snap2.activeCaloriesSource).isEqualTo(LocalActivityMetricSource.DEFAULT_LOCAL_FALLBACK)
    }

    @Test
    fun healthConnectMetricsRemainAuthoritativeOverLocalFallbacks() {
        val (_, snapshot) = estimator.update(
            counterTotal = 10_000.0,
            timestamp = 1_710_000_000_000L,
            previous = LocalActivityState(),
            zoneId = zone,
            healthConnectMetrics = HealthConnectActivityMetrics(
                distanceKmToday = 3.4,
                activeCaloriesKcalToday = 245.0
            )
        )

        assertThat(snapshot.distanceKmToday).isWithin(0.0001).of(3.4)
        assertThat(snapshot.activeCaloriesKcalToday).isWithin(0.0001).of(245.0)
        assertThat(snapshot.distanceSource).isEqualTo(LocalActivityMetricSource.HEALTH_CONNECT)
        assertThat(snapshot.activeCaloriesSource).isEqualTo(LocalActivityMetricSource.HEALTH_CONNECT)
    }

    @Test
    fun implausibleHealthConnectMetricsFallBackToLocalValues() {
        listOf(
            -1.0,
            100.1,
            Double.MAX_VALUE,
            Double.POSITIVE_INFINITY,
            Double.NaN
        ).forEach { invalidDistanceKm ->
            val (_, snapshot) = estimator.update(
                counterTotal = 10_000.0,
                timestamp = 1_710_000_000_000L,
                previous = LocalActivityState(),
                zoneId = zone,
                healthConnectMetrics = HealthConnectActivityMetrics(
                    distanceKmToday = invalidDistanceKm,
                    activeCaloriesKcalToday = 245.0
                )
            )

            assertThat(snapshot.distanceSource).isEqualTo(LocalActivityMetricSource.DEFAULT_LOCAL_FALLBACK)
            assertThat(snapshot.activeCaloriesSource).isEqualTo(LocalActivityMetricSource.HEALTH_CONNECT)
        }
        listOf(
            -1.0,
            10_000.1,
            Double.MAX_VALUE,
            Double.POSITIVE_INFINITY,
            Double.NaN
        ).forEach { invalidCaloriesKcal ->
            val (_, snapshot) = estimator.update(
                counterTotal = 10_000.0,
                timestamp = 1_710_000_000_000L,
                previous = LocalActivityState(),
                zoneId = zone,
                healthConnectMetrics = HealthConnectActivityMetrics(
                    distanceKmToday = 3.4,
                    activeCaloriesKcalToday = invalidCaloriesKcal
                )
            )

            assertThat(snapshot.distanceSource).isEqualTo(LocalActivityMetricSource.HEALTH_CONNECT)
            assertThat(snapshot.activeCaloriesSource).isEqualTo(LocalActivityMetricSource.DEFAULT_LOCAL_FALLBACK)
        }
    }

    @Test
    fun invalidStepCounterRetainsLastSafeCounterWithoutPropagatingNonFiniteValues() {
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE, 200_000.0)
            .forEach { invalidCounter ->
            val (initialState, _) = estimator.update(
                counterTotal = 10_000.0,
                timestamp = 1_710_000_000_000L,
                previous = LocalActivityState(),
                zoneId = zone
            )
            val (recoveredState, invalidSnapshot) = estimator.update(
                counterTotal = invalidCounter,
                timestamp = 1_710_000_000_000L + 5 * 60_000L,
                previous = initialState,
                zoneId = zone
            )
            val (_, recoveredSnapshot) = estimator.update(
                counterTotal = 10_100.0,
                timestamp = 1_710_000_000_000L + 10 * 60_000L,
                previous = recoveredState,
                zoneId = zone
            )

            assertThat(invalidSnapshot.stepsToday).isEqualTo(0.0)
            assertThat(invalidSnapshot.distanceKmToday).isEqualTo(0.0)
            assertThat(invalidSnapshot.activeCaloriesKcalToday).isEqualTo(0.0)
            assertThat(invalidSnapshot.stepsToday.isFinite()).isTrue()
            assertThat(invalidSnapshot.distanceKmToday.isFinite()).isTrue()
            assertThat(invalidSnapshot.activeCaloriesKcalToday.isFinite()).isTrue()
            assertThat(recoveredSnapshot.stepsToday).isEqualTo(100.0)
        }
    }

    @Test
    fun extremeInitialCounterIsRejectedAndLaterValidReadingsRecover() {
        val timestamp = 1_710_000_000_000L
        val (invalidState, invalidSnapshot) = estimator.update(
            counterTotal = 5_000_001.0,
            timestamp = timestamp,
            previous = LocalActivityState(),
            zoneId = zone
        )
        val (rebasedState, rebasedSnapshot) = estimator.update(
            counterTotal = 100.0,
            timestamp = timestamp + 5 * 60_000L,
            previous = invalidState,
            zoneId = zone
        )
        val (_, recoveredSnapshot) = estimator.update(
            counterTotal = 200.0,
            timestamp = timestamp + 10 * 60_000L,
            previous = rebasedState,
            zoneId = zone
        )

        assertThat(invalidSnapshot.stepsToday).isEqualTo(0.0)
        assertThat(invalidState.lastCounter).isEqualTo(0.0)
        assertThat(rebasedSnapshot.stepsToday).isEqualTo(0.0)
        assertThat(recoveredSnapshot.stepsToday).isEqualTo(100.0)
    }

    @Test
    fun invalidConstructorFallbacksCannotProduceNonFiniteMetrics() {
        val invalidEstimator = LocalActivityMetricsEstimator(
            strideMeters = Double.NaN,
            kcalPerStep = Double.POSITIVE_INFINITY
        )
        val timestamp = 1_710_000_000_000L
        val (state, _) = invalidEstimator.update(
            counterTotal = 10_000.0,
            timestamp = timestamp,
            previous = LocalActivityState(),
            zoneId = zone
        )
        val (_, snapshot) = invalidEstimator.update(
            counterTotal = 11_000.0,
            timestamp = timestamp + 10 * 60_000L,
            previous = state,
            zoneId = zone
        )

        assertThat(snapshot.distanceKmToday).isWithin(0.0001).of(0.78)
        assertThat(snapshot.activeCaloriesKcalToday).isWithin(0.0001).of(40.0)
        assertThat(snapshot.distanceKmToday.isFinite()).isTrue()
        assertThat(snapshot.activeCaloriesKcalToday.isFinite()).isTrue()
    }

    @Test
    fun corruptPreviousStateIsNormalizedBeforeActivityMetricsAreCalculated() {
        val timestamp = 1_710_000_000_000L
        val (initialState, _) = estimator.update(
            counterTotal = 10_000.0,
            timestamp = timestamp,
            previous = LocalActivityState(),
            zoneId = zone
        )
        val corruptState = initialState.copy(
            baselineCounter = Double.NaN,
            lastCounter = Double.POSITIVE_INFINITY,
            activeMinutesToday = Double.NaN
        )

        val (updatedState, snapshot) = estimator.update(
            counterTotal = 10_100.0,
            timestamp = timestamp + 5 * 60_000L,
            previous = corruptState,
            zoneId = zone
        )
        val (_, recoveredSnapshot) = estimator.update(
            counterTotal = 10_200.0,
            timestamp = timestamp + 10 * 60_000L,
            previous = updatedState,
            zoneId = zone
        )

        assertThat(snapshot.stepsToday).isEqualTo(0.0)
        assertThat(snapshot.stepsToday.isFinite()).isTrue()
        assertThat(snapshot.distanceKmToday.isFinite()).isTrue()
        assertThat(snapshot.activeMinutesToday.isFinite()).isTrue()
        assertThat(snapshot.activeCaloriesKcalToday.isFinite()).isTrue()
        assertThat(updatedState.baselineCounter.isFinite()).isTrue()
        assertThat(updatedState.lastCounter.isFinite()).isTrue()
        assertThat(updatedState.activeMinutesToday.isFinite()).isTrue()
        assertThat(recoveredSnapshot.stepsToday).isEqualTo(100.0)
    }

    @Test
    fun validProfilePersonalizesOnlyMissingFallbackMetrics() {
        val personalization = LocalActivityPersonalization(
            heightCm = 180.0,
            weightKg = 80.0,
            ageYears = 40,
            physiologicalSex = io.aaps.copilot.domain.profile.PhysiologicalSex.MALE
        )
        val (state, _) = estimator.update(
            counterTotal = 10_000.0,
            timestamp = 1_710_000_000_000L,
            previous = LocalActivityState(),
            zoneId = zone,
            personalization = personalization
        )
        val (_, snapshot) = estimator.update(
            counterTotal = 11_000.0,
            timestamp = 1_710_000_000_000L + 10 * 60_000L,
            previous = state,
            zoneId = zone,
            personalization = personalization
        )

        assertThat(snapshot.distanceKmToday).isWithin(0.0001).of(0.747)
        assertThat(snapshot.activeCaloriesKcalToday).isWithin(0.0001).of(44.82)
        assertThat(snapshot.distanceSource).isEqualTo(LocalActivityMetricSource.PERSONALIZED_LOCAL_FALLBACK)
        assertThat(snapshot.activeCaloriesSource).isEqualTo(LocalActivityMetricSource.PERSONALIZED_LOCAL_FALLBACK)
    }

    @Test
    fun validHeightPersonalizesStrideWhenWeightIsInvalid() {
        val personalization = LocalActivityPersonalization(heightCm = 180.0, weightKg = Double.NaN)
        val (state, _) = estimator.update(
            counterTotal = 10_000.0,
            timestamp = 1_710_000_000_000L,
            previous = LocalActivityState(),
            zoneId = zone,
            personalization = personalization
        )
        val (_, snapshot) = estimator.update(
            counterTotal = 11_000.0,
            timestamp = 1_710_000_000_000L + 10 * 60_000L,
            previous = state,
            zoneId = zone,
            personalization = personalization
        )

        assertThat(snapshot.distanceKmToday).isWithin(0.0001).of(0.747)
        assertThat(snapshot.distanceSource).isEqualTo(LocalActivityMetricSource.PERSONALIZED_LOCAL_FALLBACK)
        assertThat(snapshot.activeCaloriesKcalToday).isWithin(0.0001).of(40.0)
        assertThat(snapshot.activeCaloriesSource).isEqualTo(LocalActivityMetricSource.DEFAULT_LOCAL_FALLBACK)
    }

    @Test
    fun validWeightPersonalizesCaloriesWhenHeightIsMissing() {
        val personalization = LocalActivityPersonalization(weightKg = 80.0)
        val (state, _) = estimator.update(
            counterTotal = 10_000.0,
            timestamp = 1_710_000_000_000L,
            previous = LocalActivityState(),
            zoneId = zone,
            personalization = personalization
        )
        val (_, snapshot) = estimator.update(
            counterTotal = 11_000.0,
            timestamp = 1_710_000_000_000L + 10 * 60_000L,
            previous = state,
            zoneId = zone,
            personalization = personalization
        )

        assertThat(snapshot.distanceKmToday).isWithin(0.0001).of(0.8)
        assertThat(snapshot.distanceSource).isEqualTo(LocalActivityMetricSource.DEFAULT_LOCAL_FALLBACK)
        assertThat(snapshot.activeCaloriesKcalToday).isWithin(0.0001).of(48.0)
        assertThat(snapshot.activeCaloriesSource).isEqualTo(LocalActivityMetricSource.PERSONALIZED_LOCAL_FALLBACK)
    }

    @Test
    fun invalidProfileMeasurementsUseDefaultFallbacks() {
        listOf(
            LocalActivityPersonalization(heightCm = 79.9),
            LocalActivityPersonalization(heightCm = 230.1),
            LocalActivityPersonalization(heightCm = Double.POSITIVE_INFINITY),
            LocalActivityPersonalization(heightCm = Double.NaN),
            LocalActivityPersonalization(weightKg = 9.9),
            LocalActivityPersonalization(weightKg = 350.1),
            LocalActivityPersonalization(weightKg = Double.POSITIVE_INFINITY),
            LocalActivityPersonalization(weightKg = Double.NaN)
        ).forEach { personalization ->
            val (state, _) = estimator.update(
                counterTotal = 10_000.0,
                timestamp = 1_710_000_000_000L,
                previous = LocalActivityState(),
                zoneId = zone,
                personalization = personalization
            )
            val (_, snapshot) = estimator.update(
                counterTotal = 11_000.0,
                timestamp = 1_710_000_000_000L + 10 * 60_000L,
                previous = state,
                zoneId = zone,
                personalization = personalization
            )

            assertThat(snapshot.distanceKmToday).isWithin(0.0001).of(0.8)
            assertThat(snapshot.activeCaloriesKcalToday).isWithin(0.0001).of(40.0)
            assertThat(snapshot.distanceSource).isEqualTo(LocalActivityMetricSource.DEFAULT_LOCAL_FALLBACK)
            assertThat(snapshot.activeCaloriesSource).isEqualTo(LocalActivityMetricSource.DEFAULT_LOCAL_FALLBACK)
        }
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
