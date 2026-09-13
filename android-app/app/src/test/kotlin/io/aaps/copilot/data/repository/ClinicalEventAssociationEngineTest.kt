package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventSource
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Test

class ClinicalEventAssociationEngineTest {

    @Test
    fun productionCalculateDoesNotCreateOrTouchAccounting() {
        var recorderAllocations = 0
        var snapshotsRequested = 0
        var counterOperations = 0
        val engine = ClinicalEventAssociationEngine(
            object : AssociationAccountingProbe {
                override fun onRecorderAllocated() {
                    recorderAllocations++
                }

                override fun onSnapshotRequested() {
                    snapshotsRequested++
                }

                override fun onCounterOperation() {
                    counterOperations++
                }
            }
        )
        val events = listOf(event("stress", 0L, 60_000L, CompensationEventType.STRESS))
        val glucose = listOf(ClinicalGlucosePoint(0L, 5.0))

        val associations = engine.calculate(
            events = events,
            glucose = glucose,
            therapy = emptyList(),
            forecasts = emptyList(),
            telemetry = emptyList()
        )

        assertThat(associations.single().glucose.sampleCount).isEqualTo(1)
        assertThat(recorderAllocations).isEqualTo(0)
        assertThat(snapshotsRequested).isEqualTo(0)
        assertThat(counterOperations).isEqualTo(0)

        engine.calculateWithStats(
            events = events,
            glucose = glucose,
            therapy = emptyList(),
            forecasts = emptyList(),
            telemetry = emptyList()
        )

        assertThat(recorderAllocations).isEqualTo(1)
        assertThat(snapshotsRequested).isEqualTo(1)
        assertThat(counterOperations).isGreaterThan(0)
    }

    @Test
    fun optimizedAssociationsMatchOldImplementationAcrossSeededInputs() {
        repeat(96) { seed ->
            val input = randomizedInput(seed)

            val expected = ReferenceClinicalEventAssociations.calculate(
                input.events,
                input.glucose,
                input.therapy,
                input.forecasts,
                input.telemetry
            )
            val actual = ClinicalEventAssociationEngine.calculate(
                input.events,
                input.glucose,
                input.therapy,
                input.forecasts,
                input.telemetry
            )

            assertThat(actual).isEqualTo(expected)
        }
    }

    @Test
    fun closedEndpointsAndRepeatedLocalIdsPreserveRowsAndUnionMembership() {
        val events = listOf(
            event("same-id", 20L, 30L, CompensationEventType.STRESS),
            event("same-id", 10L, 20L, CompensationEventType.STRESS),
            event("activity", 15L, 15L, CompensationEventType.ACTIVITY)
        )

        val actual = ClinicalEventAssociationEngine.calculate(
            events = events,
            glucose = listOf(
                ClinicalGlucosePoint(9L, 99.0),
                ClinicalGlucosePoint(10L, 1.0),
                ClinicalGlucosePoint(15L, 2.0),
                ClinicalGlucosePoint(20L, 3.0),
                ClinicalGlucosePoint(30L, 4.0),
                ClinicalGlucosePoint(31L, 99.0)
            ),
            therapy = emptyList(),
            forecasts = emptyList(),
            telemetry = emptyList()
        )

        assertThat(actual.map(ClinicalEventTypeAssociation::type))
            .containsExactly("ACTIVITY", "STRESS").inOrder()
        assertThat(actual.last().eventCount).isEqualTo(2)
        assertThat(actual.last().totalDurationMinutes).isEqualTo(0L)
        assertThat(actual.last().glucose.sampleCount).isEqualTo(4)
        assertThat(actual.last().trend.sampleCount).isEqualTo(2)
        assertThat(actual.first().glucose.sampleCount).isEqualTo(1)
        assertThat(actual.all { !it.causal }).isTrue()
    }

    @Test
    fun durationSumCountsEveryRowAndSaturatesLongOverflow() {
        val maximumDuration = event(
            id = "duplicate",
            startTs = 0L,
            endTs = Long.MAX_VALUE,
            type = CompensationEventType.STRESS
        )
        val events = List(60_001) { maximumDuration }

        val actual = ClinicalEventAssociationEngine.calculate(
            events,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList()
        ).single()

        assertThat(actual.eventCount).isEqualTo(60_001)
        assertThat(actual.totalDurationMinutes).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun durationFloorsEveryEventIndependentlyBeforeSumming() {
        val actual = ClinicalEventAssociationEngine.calculate(
            events = listOf(
                event("first", 0L, 59_999L, CompensationEventType.STRESS),
                event("second", 60_000L, 119_999L, CompensationEventType.STRESS)
            ),
            glucose = emptyList(),
            therapy = emptyList(),
            forecasts = emptyList(),
            telemetry = emptyList()
        ).single()

        assertThat(actual.totalDurationMinutes).isEqualTo(0L)
    }

    @Test
    fun stableSameTimestampOrderControlsGlucoseTrendAndNearestTie() {
        val association = ClinicalEventAssociationEngine.calculate(
            events = listOf(event("stress", 0L, 120_000L, CompensationEventType.STRESS)),
            glucose = listOf(
                ClinicalGlucosePoint(60_100L, 8.0),
                ClinicalGlucosePoint(59_900L, 6.0),
                ClinicalGlucosePoint(60_100L, 9.0)
            ),
            therapy = emptyList(),
            forecasts = listOf(ClinicalForecastPoint(0L, 1, 10.0, 0.0, 0.0)),
            telemetry = emptyList()
        ).single()

        assertThat(association.glucose.mean).isEqualTo(7.667)
        assertThat(association.trend.mean).isEqualTo(3.0)
        assertThat(association.forecastError.mean).isEqualTo(2.0)
    }

    @Test
    fun everyMetricUsesItsExactFourThousandNinetySixSampleCapAndOrder() {
        val sampleCount = 4_097
        val timestamps = (0 until sampleCount).map(Int::toLong)
        val glucoseTimestamps = timestamps.map { it + 60_000L }
        val glucoseValues = timestamps.map { 1.0 + it % 40L }
        val uamValues = timestamps.map { (it % 501L).toDouble() }
        val isfValues = timestamps.map { 0.2 + (it % 179L) / 10.0 }
        val crValues = timestamps.map { 2.0 + (it % 581L) / 10.0 }
        val forecastValues = glucoseValues.map { minOf(40.0, it + 1.0) }
        val association = ClinicalEventAssociationEngine.calculate(
            events = List(sampleCount) { index ->
                event(
                    "event-$index",
                    0L,
                    60_000L + sampleCount,
                    CompensationEventType.STRESS
                )
            },
            glucose = glucoseTimestamps.mapIndexed { index, ts ->
                ClinicalGlucosePoint(ts, glucoseValues[index])
            },
            therapy = timestamps.mapIndexed { index, ts ->
                ClinicalTherapyPoint(ts, carbsG = uamValues[index], syntheticUam = true)
            },
            forecasts = timestamps.mapIndexed { index, ts ->
                ClinicalForecastPoint(ts, 1, forecastValues[index], 0.0, 0.0)
            },
            telemetry = timestamps.flatMapIndexed { index, ts ->
                listOf(
                    ClinicalTelemetryPoint(ts, "isf_runtime_selected_value", isfValues[index], "OK"),
                    ClinicalTelemetryPoint(ts, "cr_runtime_selected_value", crValues[index], "OK")
                )
            } + listOf(
                ClinicalTelemetryPoint(0L, "isf_runtime_selected_value", 999_999.0, "STALE"),
                ClinicalTelemetryPoint(0L, "wrong", 999_999.0, "OK")
            )
        ).single()

        assertThat(association.glucose.sampleCount).isEqualTo(4_096)
        assertThat(association.glucose.mean).isEqualTo(canonicalMean(glucoseValues.take(4_096)))
        assertThat(association.trend.sampleCount).isEqualTo(4_096)
        assertThat(association.uam.sampleCount).isEqualTo(4_096)
        assertThat(association.uam.mean).isEqualTo(canonicalMean(uamValues.take(4_096)))
        assertThat(association.isf.sampleCount).isEqualTo(4_096)
        assertThat(association.isf.mean).isEqualTo(canonicalMean(isfValues.take(4_096)))
        assertThat(association.cr.sampleCount).isEqualTo(4_096)
        assertThat(association.cr.mean).isEqualTo(canonicalMean(crValues.take(4_096)))
        assertThat(association.forecastError.sampleCount).isEqualTo(4_096)
        assertThat(association.forecastError.mean).isEqualTo(
            canonicalMean(forecastValues.zip(glucoseValues) { forecast, glucose ->
                abs(forecast - glucose)
            }.take(4_096))
        )
    }

    @Test
    fun glucoseDeduplicatesOnlyExactTimestampValuePairs() {
        val association = ClinicalEventAssociationEngine.calculate(
            events = listOf(event("stress", 0L, 1L, CompensationEventType.STRESS)),
            glucose = listOf(
                ClinicalGlucosePoint(0L, 5.0),
                ClinicalGlucosePoint(0L, 5.0),
                ClinicalGlucosePoint(0L, -0.0),
                ClinicalGlucosePoint(0L, 0.0),
                ClinicalGlucosePoint(1L, 5.0)
            ),
            therapy = emptyList(),
            forecasts = emptyList(),
            telemetry = emptyList()
        ).single()

        assertThat(association.glucose.sampleCount).isEqualTo(4)
        assertThat(association.trend.sampleCount).isEqualTo(1)
        assertThat(association.uam.sampleCount).isEqualTo(0)
        assertThat(association.uam.mean).isNull()
        assertThat(association.uam.unit).isEqualTo("g")
    }

    @Test
    fun invalidNearestGlucoseSuppressesEquidistantFiniteGlucose() {
        val association = ClinicalEventAssociationEngine.calculate(
            events = listOf(event("stress", 0L, 1L, CompensationEventType.STRESS)),
            glucose = listOf(
                ClinicalGlucosePoint(60_100L, Double.NaN),
                ClinicalGlucosePoint(59_900L, 6.0)
            ),
            therapy = emptyList(),
            forecasts = listOf(ClinicalForecastPoint(0L, 1, 10.0, 0.0, 0.0)),
            telemetry = emptyList()
        ).single()

        assertThat(association.forecastError.sampleCount).isEqualTo(0)
        assertThat(association.forecastError.mean).isNull()
        assertThat(association.forecastError.unit).isEqualTo("mmol/L")
    }

    @Test
    fun forecastToleranceIsInclusiveAtOneHundredFiftySeconds() {
        val association = ClinicalEventAssociationEngine.calculate(
            events = listOf(event("stress", 0L, 1L, CompensationEventType.STRESS)),
            glucose = listOf(
                ClinicalGlucosePoint(210_000L, 6.0),
                ClinicalGlucosePoint(210_001L, 5.0)
            ),
            therapy = listOf(
                ClinicalTherapyPoint(0L, insulinU = 2.0, carbsG = 20.0, syntheticUam = false)
            ),
            forecasts = listOf(ClinicalForecastPoint(0L, 1, 7.0, 0.0, 0.0)),
            telemetry = listOf(
                ClinicalTelemetryPoint(0L, "isf_runtime_selected_value", 2.0, "OK"),
                ClinicalTelemetryPoint(0L, "isf_runtime_selected_value", 2.0, "OK"),
                ClinicalTelemetryPoint(0L, "cr_runtime_selected_value", 10.0, "ok")
            )
        ).single()

        assertThat(association.forecastError.sampleCount).isEqualTo(1)
        assertThat(association.forecastError.mean).isEqualTo(1.0)
        assertThat(association.uam.sampleCount).isEqualTo(0)
        assertThat(association.isf.sampleCount).isEqualTo(2)
        assertThat(association.cr.sampleCount).isEqualTo(0)
    }

    @Test
    fun expectedTimestampOverflowSkipsForecastWithoutCrashing() {
        val startTs = Long.MAX_VALUE - 10L
        val association = ClinicalEventAssociationEngine.calculate(
            events = listOf(event("late", startTs, Long.MAX_VALUE, CompensationEventType.STRESS)),
            glucose = listOf(ClinicalGlucosePoint(Long.MAX_VALUE, 6.0)),
            therapy = emptyList(),
            forecasts = listOf(ClinicalForecastPoint(startTs, 1, 7.0, 0.0, 0.0)),
            telemetry = emptyList()
        ).single()

        assertThat(association.forecastError.sampleCount).isEqualTo(0)
    }

    @Test
    fun timestampDistanceOverflowFailsClosed() {
        val association = ClinicalEventAssociationEngine.calculate(
            events = listOf(event("stress", 0L, 0L, CompensationEventType.STRESS)),
            glucose = listOf(ClinicalGlucosePoint(Long.MIN_VALUE + 60_000L, 6.0)),
            therapy = emptyList(),
            forecasts = listOf(ClinicalForecastPoint(0L, 1, 7.0, 0.0, 0.0)),
            telemetry = emptyList()
        ).single()

        assertThat(association.forecastError.sampleCount).isEqualTo(0)
        assertThat(association.forecastError.mean).isNull()
    }

    @Test
    fun derivedForecastErrorOverflowFailsClosed() {
        val result = ClinicalEventAssociationEngine.calculateWithStats(
            events = listOf(event("stress", 0L, 0L, CompensationEventType.STRESS)),
            glucose = listOf(ClinicalGlucosePoint(60_000L, -Double.MAX_VALUE)),
            therapy = emptyList(),
            forecasts = listOf(
                ClinicalForecastPoint(0L, 1, Double.MAX_VALUE, 0.0, 0.0)
            ),
            telemetry = emptyList()
        )
        val association = result.associations.single()

        assertThat(association.forecastError.sampleCount).isEqualTo(0)
        assertThat(association.forecastError.mean).isNull()
        assertThat(result.stats.accumulatorRejectedNonFinite).isEqualTo(1L)
    }

    @Test
    fun denseInputsUseBoundedIndexedWork() {
        val count = 6_000
        val events = (0 until count).map { index ->
            val start = index * 10L
            event("event-$index", start, start + 4L, CompensationEventType.STRESS)
        }.reversed()
        val glucose = (0 until count).map { index ->
            ClinicalGlucosePoint(index * 10L, 5.0 + index % 7)
        }.reversed()
        val therapy = (0 until count).map { index ->
            ClinicalTherapyPoint(index * 10L, carbsG = index.toDouble(), syntheticUam = true)
        }
        val forecasts = (0 until count).map { index ->
            ClinicalForecastPoint(index * 10L, 1, 7.0, 0.0, 0.0)
        }
        val telemetry = (0 until count).flatMap { index ->
            listOf(
                ClinicalTelemetryPoint(
                    index * 10L,
                    "isf_runtime_selected_value",
                    2.0,
                    "OK"
                ),
                ClinicalTelemetryPoint(
                    index * 10L,
                    "cr_runtime_selected_value",
                    10.0,
                    "OK"
                )
            )
        }

        val result = ClinicalEventAssociationEngine.calculateWithStats(
            events,
            glucose,
            therapy,
            forecasts,
            telemetry
        )

        assertThat(result.associations.single().eventCount).isEqualTo(count)
        assertThat(result.stats.inputRowsInspected).isEqualTo(36_000L)
        assertThat(result.stats.indexEntriesCreated).isEqualTo(24_000L)
        assertThat(result.stats.eventSortComparisons).isGreaterThan(0L)
        assertThat(result.stats.groupingRowsVisited).isEqualTo(count.toLong())
        assertThat(result.stats.typeGroupsVisited).isEqualTo(1L)
        assertThat(result.stats.intervalEventsVisited).isEqualTo(count.toLong())
        assertThat(result.stats.intervalMergeComparisons).isEqualTo(count - 1L)
        assertThat(result.stats.nearestTimestampDedupeRowsVisited).isEqualTo(count.toLong())
        assertThat(result.stats.nearestTimestampDedupeComparisons).isEqualTo(count - 1L)
        assertThat(result.stats.queryRowsVisited).isEqualTo(20_480L)
        assertThat(result.stats.trendEventsVisited).isEqualTo(count.toLong())
        assertThat(result.stats.durationRowsVisited).isEqualTo(count.toLong())
        assertThat(result.stats.intervalMembershipQueries).isAtMost(30_000L)
        assertThat(result.stats.forecastNearestCandidateChecks).isAtMost(2L * count)
        // The prior trend and forecast scans alone require more than 72 million row checks here.
        assertThat(result.stats.totalOperations).isLessThan(1_250_000L)
    }

    @Test
    fun denseMostlyDiscardedInputsStillCountEveryInspectionAndScaleBoundedly() {
        val count = 12_000
        val events = listOf(
            event("later", 2L, 3L, CompensationEventType.STRESS),
            event("earlier", 0L, 1L, CompensationEventType.STRESS)
        )
        val glucose = List(count) { index ->
            ClinicalGlucosePoint(1_000_000L + count - index, Double.NaN)
        }
        val therapy = List(count) { index ->
            ClinicalTherapyPoint(index.toLong(), carbsG = Double.NaN, syntheticUam = false)
        }
        val forecasts = List(count) { index ->
            ClinicalForecastPoint(index.toLong(), 0, Double.NaN, 0.0, 0.0)
        }
        val telemetry = List(count) { index ->
            ClinicalTelemetryPoint(index.toLong(), "other", Double.NaN, "STALE")
        }

        val result = ClinicalEventAssociationEngine.calculateWithStats(
            events,
            glucose,
            therapy,
            forecasts,
            telemetry
        )

        assertThat(result.stats.inputRowsInspected).isEqualTo(4L * count)
        assertThat(result.stats.indexEntriesCreated).isEqualTo(count.toLong())
        assertThat(result.stats.queryRowsVisited).isEqualTo(2L * count)
        assertThat(result.stats.nearestTimestampDedupeRowsVisited).isEqualTo(count.toLong())
        assertThat(result.stats.nearestTimestampDedupeComparisons).isEqualTo(count - 1L)
        assertThat(result.stats.accumulatedSamples).isEqualTo(0L)
        // Raw filtering, complete-glucose sort/dedupe and two full query scans stay bounded.
        assertThat(result.stats.totalOperations).isLessThan(250_000L)
    }

    private fun randomizedInput(seed: Int): AssociationInput {
        val random = Random(seed)
        val eventTypes = CompensationEventType.entries
        val events = buildList {
            repeat(random.nextInt(0, 28)) { index ->
                val nearMaximum = random.nextInt(12) == 0
                val start = if (nearMaximum) {
                    Long.MAX_VALUE - random.nextLong(0L, 180_001L)
                } else {
                    random.nextLong(0L, 1_200_001L)
                }
                val duration = listOf(0L, 1L, 60_000L, 180_000L)[random.nextInt(4)]
                val end = runCatching { Math.addExact(start, duration) }.getOrElse { Long.MAX_VALUE }
                add(
                    event(
                        id = "event-${random.nextInt(0, (index / 2 + 2).coerceAtLeast(2))}",
                        startTs = start,
                        endTs = end,
                        type = eventTypes[random.nextInt(eventTypes.size)]
                    )
                )
                if (random.nextInt(10) == 0) add(last())
            }
        }.shuffled(random)
        val timestampPool = buildList {
            repeat(32) { add(random.nextLong(0L, 1_400_001L)) }
            addAll(listOf(0L, 1L, 60_000L, 1_200_000L, Long.MAX_VALUE - 120_000L))
        }
        val finiteValues = listOf(-1.0, -0.0, 0.0, 1.0, 5.5, 7.25, 1_000.0)
        val allValues = finiteValues + listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        val glucose = List(random.nextInt(0, 42)) {
            ClinicalGlucosePoint(timestampPool.random(random), allValues.random(random))
        }.shuffled(random)
        val therapy = List(random.nextInt(0, 38)) {
            ClinicalTherapyPoint(
                ts = timestampPool.random(random),
                insulinU = allValues.random(random),
                carbsG = if (random.nextBoolean()) allValues.random(random) else null,
                syntheticUam = random.nextBoolean()
            )
        }.shuffled(random)
        val forecasts = List(random.nextInt(0, 38)) {
            ClinicalForecastPoint(
                ts = timestampPool.random(random),
                horizonMin = listOf(-1, 0, 1, 2, Int.MAX_VALUE).random(random),
                mmol = allValues.random(random),
                lower = 0.0,
                upper = 0.0
            )
        }.shuffled(random)
        val telemetry = List(random.nextInt(0, 46)) {
            ClinicalTelemetryPoint(
                ts = timestampPool.random(random),
                key = listOf(
                    "isf_runtime_selected_value",
                    "cr_runtime_selected_value",
                    "isf_runtime_selected_value ",
                    "other"
                ).random(random),
                value = allValues.random(random),
                quality = listOf("OK", "ok", "STALE", "").random(random)
            )
        }.shuffled(random)
        return AssociationInput(events, glucose, therapy, forecasts, telemetry)
    }

    private fun event(
        id: String,
        startTs: Long,
        endTs: Long,
        type: CompensationEventType
    ) = CompensationEvent(
        localId = id,
        startTs = startTs,
        endTs = endTs,
        type = type,
        source = EventSource.USER
    )

    private data class AssociationInput(
        val events: List<CompensationEvent>,
        val glucose: List<ClinicalGlucosePoint>,
        val therapy: List<ClinicalTherapyPoint>,
        val forecasts: List<ClinicalForecastPoint>,
        val telemetry: List<ClinicalTelemetryPoint>
    )

    private fun canonicalMean(values: List<Double>): Double = BigDecimal.valueOf(values.average())
        .setScale(3, RoundingMode.HALF_UP)
        .stripTrailingZeros().toDouble()
}

/** Literal test oracle for the association implementation that preceded the indexed engine. */
private object ReferenceClinicalEventAssociations {
    private const val MAX_SAMPLES_PER_TYPE = 4_096

    fun calculate(
        events: List<CompensationEvent>,
        glucose: List<ClinicalGlucosePoint>,
        therapy: List<ClinicalTherapyPoint>,
        forecasts: List<ClinicalForecastPoint>,
        telemetry: List<ClinicalTelemetryPoint>
    ): List<ClinicalEventTypeAssociation> = events
        .sortedWith(compareBy<CompensationEvent> { it.startTs }.thenBy { it.localId })
        .groupBy { it.type.name }
        .toSortedMap()
        .map { (type, typeEvents) ->
            fun inside(ts: Long): Boolean = typeEvents.any { ts in it.startTs..it.endTs }
            val glucoseSamples = glucose.asSequence()
                .filter { it.mmol.isFinite() && inside(it.ts) }
                .distinctBy { it.ts to it.mmol }
                .sortedBy(ClinicalGlucosePoint::ts)
                .take(MAX_SAMPLES_PER_TYPE)
                .map(ClinicalGlucosePoint::mmol)
                .toList()
            val trendSamples = typeEvents.asSequence()
                .mapNotNull { event ->
                    val samples = glucose.asSequence()
                        .filter { it.mmol.isFinite() && it.ts in event.startTs..event.endTs }
                        .sortedBy(ClinicalGlucosePoint::ts)
                        .toList()
                    if (samples.size < 2) null else samples.last().mmol - samples.first().mmol
                }
                .take(MAX_SAMPLES_PER_TYPE)
                .toList()
            val uamSamples = therapy.asSequence()
                .filter { it.syntheticUam && inside(it.ts) }
                .mapNotNull { it.carbsG?.takeIf(Double::isFinite) }
                .take(MAX_SAMPLES_PER_TYPE)
                .toList()
            fun telemetrySamples(key: String): List<Double> = telemetry.asSequence()
                .filter {
                    it.key == key && it.quality == "OK" && it.value.isFinite() && inside(it.ts)
                }
                .sortedBy(ClinicalTelemetryPoint::ts)
                .take(MAX_SAMPLES_PER_TYPE)
                .map(ClinicalTelemetryPoint::value)
                .toList()
            val forecastErrors = forecasts.asSequence()
                .filter { inside(it.ts) && it.mmol.isFinite() && it.horizonMin > 0 }
                .mapNotNull { forecast ->
                    val expectedTs = safeTimestampOffset(
                        forecast.ts,
                        forecast.horizonMin.toLong() * 60_000L
                    ) ?: return@mapNotNull null
                    glucose.minByOrNull { abs(it.ts - expectedTs) }
                        ?.takeIf { abs(it.ts - expectedTs) <= 150_000L && it.mmol.isFinite() }
                        ?.let { actual -> abs(forecast.mmol - actual.mmol) }
                }
                .take(MAX_SAMPLES_PER_TYPE)
                .toList()
            ClinicalEventTypeAssociation(
                type = type,
                eventCount = typeEvents.size,
                totalDurationMinutes = typeEvents.fold(0L) { total, event ->
                    val minutes = (event.endTs - event.startTs).coerceAtLeast(0L) / 60_000L
                    if (total > Long.MAX_VALUE - minutes) Long.MAX_VALUE else total + minutes
                },
                glucose = metric(glucoseSamples, "mmol/L"),
                trend = metric(trendSamples, "mmol/L per event"),
                uam = metric(uamSamples, "g"),
                isf = metric(telemetrySamples("isf_runtime_selected_value"), "mmol/L/U"),
                cr = metric(telemetrySamples("cr_runtime_selected_value"), "g/U"),
                forecastError = metric(forecastErrors, "mmol/L")
            )
        }

    private fun metric(values: List<Double>, unit: String) = ClinicalAssociationMetric(
        sampleCount = values.size,
        mean = values.takeIf { it.isNotEmpty() }?.average()?.let(::canonicalDouble),
        unit = unit
    )

    private fun canonicalDouble(value: Double) =
        BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP)
            .stripTrailingZeros().toDouble()

    private fun safeTimestampOffset(timestamp: Long, offset: Long): Long? = try {
        Math.addExact(timestamp, offset)
    } catch (_: ArithmeticException) {
        null
    }
}
