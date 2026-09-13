package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.model.TherapyComponentPolicy
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

private const val FORECAST_TOLERANCE_MS = 150_000L
private const val MINUTE_MS = 60_000L
private const val ISF_KEY = "isf_runtime_selected_value"
private const val CR_KEY = "cr_runtime_selected_value"

internal object ClinicalEventAssociationPolicy {
    const val MAX_SAMPLES_PER_TYPE = 4_096
    const val GLUCOSE_UNIT = "mmol/L"
    const val TREND_UNIT = "mmol/L per event"
    const val UAM_UNIT = "g"
    const val ISF_UNIT = "mmol/L/U"
    const val CR_UNIT = "g/U"
    const val FORECAST_ERROR_UNIT = "mmol/L"

    private val validTypes = CompensationEventType.entries.map(CompensationEventType::name).toSet()
    private val glucoseRange =
        ClinicalOpenAiClient.MIN_CLINICAL_MMOL..ClinicalOpenAiClient.MAX_CLINICAL_MMOL
    private val trendRange =
        (ClinicalOpenAiClient.MIN_CLINICAL_MMOL - ClinicalOpenAiClient.MAX_CLINICAL_MMOL)..
            (ClinicalOpenAiClient.MAX_CLINICAL_MMOL - ClinicalOpenAiClient.MIN_CLINICAL_MMOL)
    private val uamRange = TherapyComponentPolicy.CARBS_GRAMS_RANGE
    private val isfRange = 0.2..18.0
    private val crRange = 2.0..60.0
    private val forecastErrorRange = 0.0..
        (ClinicalOpenAiClient.MAX_CLINICAL_MMOL - ClinicalOpenAiClient.MIN_CLINICAL_MMOL)

    fun isValid(association: ClinicalEventTypeAssociation): Boolean {
        if (
            association.type !in validTypes ||
            association.causal ||
            association.eventCount <= 0 ||
            association.totalDurationMinutes < 0L
        ) return false
        val maximumDuration = runCatching {
            Math.multiplyExact(
                association.eventCount.toLong(),
                ClinicalOpenAiClient.MAX_30D_MINUTES.toLong()
            )
        }.getOrNull() ?: return false
        if (association.totalDurationMinutes > maximumDuration) return false
        return validMetric(association.glucose, GLUCOSE_UNIT, glucoseRange) &&
            validMetric(association.trend, TREND_UNIT, trendRange) &&
            validMetric(association.uam, UAM_UNIT, uamRange) &&
            validMetric(association.isf, ISF_UNIT, isfRange) &&
            validMetric(association.cr, CR_UNIT, crRange) &&
            validMetric(association.forecastError, FORECAST_ERROR_UNIT, forecastErrorRange)
    }

    private fun validMetric(
        metric: ClinicalAssociationMetric,
        expectedUnit: String,
        range: ClosedFloatingPointRange<Double>
    ): Boolean {
        if (metric.unit != expectedUnit || metric.sampleCount !in 0..MAX_SAMPLES_PER_TYPE) {
            return false
        }
        if ((metric.sampleCount == 0) != (metric.mean == null)) return false
        val mean = metric.mean ?: return true
        return mean.isFinite() && mean in range
    }
}

internal data class AssociationWorkStats(
    val inputRowsInspected: Long,
    val indexEntriesCreated: Long,
    val eventSortComparisons: Long,
    val indexSortComparisons: Long,
    val groupingRowsVisited: Long,
    val typeSortComparisons: Long,
    val typeGroupsVisited: Long,
    val intervalEventsVisited: Long,
    val intervalMergeComparisons: Long,
    val nearestTimestampDedupeRowsVisited: Long,
    val nearestTimestampDedupeComparisons: Long,
    val queryRowsVisited: Long,
    val trendEventsVisited: Long,
    val durationRowsVisited: Long,
    val intervalMembershipQueries: Long,
    val intervalSearchComparisons: Long,
    val timestampRangeQueries: Long,
    val timestampRangeSearchComparisons: Long,
    val forecastNearestQueries: Long,
    val forecastNearestSearchComparisons: Long,
    val forecastNearestCandidateChecks: Long,
    val dedupeChecks: Long,
    val accumulatorAttempts: Long,
    val accumulatorRejectedNonFinite: Long,
    val accumulatedSamples: Long
) {
    val totalOperations: Long
        get() = inputRowsInspected + indexEntriesCreated + eventSortComparisons +
            indexSortComparisons + groupingRowsVisited + typeSortComparisons +
            typeGroupsVisited + intervalEventsVisited + intervalMergeComparisons +
            nearestTimestampDedupeRowsVisited + nearestTimestampDedupeComparisons +
            queryRowsVisited + trendEventsVisited + durationRowsVisited +
            intervalMembershipQueries + intervalSearchComparisons + timestampRangeQueries +
            timestampRangeSearchComparisons + forecastNearestQueries +
            forecastNearestSearchComparisons + forecastNearestCandidateChecks + dedupeChecks +
            accumulatorAttempts + accumulatorRejectedNonFinite + accumulatedSamples
}

internal data class ClinicalEventAssociationCalculation(
    val associations: List<ClinicalEventTypeAssociation>,
    val stats: AssociationWorkStats
)

internal interface AssociationAccountingProbe {
    fun onRecorderAllocated()
    fun onSnapshotRequested()
    fun onCounterOperation()
}

internal class ClinicalEventAssociationEngine(
    private val accountingProbe: AssociationAccountingProbe? = null
) {
    companion object {
        private val productionEngine = ClinicalEventAssociationEngine()

        fun calculate(
            events: List<CompensationEvent>,
            glucose: List<ClinicalGlucosePoint>,
            therapy: List<ClinicalTherapyPoint>,
            forecasts: List<ClinicalForecastPoint>,
            telemetry: List<ClinicalTelemetryPoint>
        ): List<ClinicalEventTypeAssociation> = productionEngine.calculate(
            events,
            glucose,
            therapy,
            forecasts,
            telemetry
        )

        fun calculateWithStats(
            events: List<CompensationEvent>,
            glucose: List<ClinicalGlucosePoint>,
            therapy: List<ClinicalTherapyPoint>,
            forecasts: List<ClinicalForecastPoint>,
            telemetry: List<ClinicalTelemetryPoint>
        ): ClinicalEventAssociationCalculation = productionEngine.calculateWithStats(
            events,
            glucose,
            therapy,
            forecasts,
            telemetry
        )
    }

    fun calculate(
        events: List<CompensationEvent>,
        glucose: List<ClinicalGlucosePoint>,
        therapy: List<ClinicalTherapyPoint>,
        forecasts: List<ClinicalForecastPoint>,
        telemetry: List<ClinicalTelemetryPoint>
    ): List<ClinicalEventTypeAssociation> = calculateAssociations(
        events,
        glucose,
        therapy,
        forecasts,
        telemetry,
        work = null
    )

    fun calculateWithStats(
        events: List<CompensationEvent>,
        glucose: List<ClinicalGlucosePoint>,
        therapy: List<ClinicalTherapyPoint>,
        forecasts: List<ClinicalForecastPoint>,
        telemetry: List<ClinicalTelemetryPoint>
    ): ClinicalEventAssociationCalculation {
        val work = AssociationWorkRecorder(accountingProbe)
        val associations = calculateAssociations(events, glucose, therapy, forecasts, telemetry, work)
        return ClinicalEventAssociationCalculation(associations, work.snapshot())
    }

    private fun calculateAssociations(
        events: List<CompensationEvent>,
        glucose: List<ClinicalGlucosePoint>,
        therapy: List<ClinicalTherapyPoint>,
        forecasts: List<ClinicalForecastPoint>,
        telemetry: List<ClinicalTelemetryPoint>,
        work: AssociationWorkRecorder?
    ): List<ClinicalEventTypeAssociation> {
        if (events.isEmpty()) return emptyList()
        val sortedEvents = events.sortedWith { left, right ->
            work?.record { eventSortComparisons++ }
            left.startTs.compareTo(right.startTs)
                .takeIf { it != 0 }
                ?: left.localId.compareTo(right.localId)
        }
        val finiteGlucose = StableTimestampIndex(
            glucose.mapIndexedNotNull { index, point ->
                work?.record { inputRowsInspected++ }
                point.takeIf { it.mmol.isFinite() }?.let { IndexedValue(index, it) }
            },
            ClinicalGlucosePoint::ts,
            work
        )
        val nearestGlucose = NearestGlucoseIndex(glucose, work)
        val isfTelemetry = StableTimestampIndex(
            telemetry.mapIndexedNotNull { index, point ->
                work?.record { inputRowsInspected++ }
                point.takeIf {
                    it.key == ISF_KEY && it.quality == "OK" && it.value.isFinite()
                }?.let { IndexedValue(index, it) }
            },
            ClinicalTelemetryPoint::ts,
            work
        )
        val crTelemetry = StableTimestampIndex(
            telemetry.mapIndexedNotNull { index, point ->
                work?.record { inputRowsInspected++ }
                point.takeIf {
                    it.key == CR_KEY && it.quality == "OK" && it.value.isFinite()
                }?.let { IndexedValue(index, it) }
            },
            ClinicalTelemetryPoint::ts,
            work
        )

        val eventsByType = linkedMapOf<String, MutableList<CompensationEvent>>()
        sortedEvents.forEach { event ->
            work?.record { groupingRowsVisited++ }
            eventsByType.getOrPut(event.type.name, ::mutableListOf).add(event)
        }
        val sortedTypeGroups = eventsByType.entries.sortedWith { left, right ->
            work?.record { typeSortComparisons++ }
            left.key.compareTo(right.key)
        }
        val associations = sortedTypeGroups.map { (type, typeEvents) ->
            work?.record { typeGroupsVisited++ }
            associationForType(
                type = type,
                events = typeEvents,
                intervals = InclusiveIntervalIndex(typeEvents, work),
                finiteGlucose = finiteGlucose,
                nearestGlucose = nearestGlucose,
                therapy = therapy,
                forecasts = forecasts,
                isfTelemetry = isfTelemetry,
                crTelemetry = crTelemetry,
                work = work
            )
        }
        return associations
    }

    private fun associationForType(
        type: String,
        events: List<CompensationEvent>,
        intervals: InclusiveIntervalIndex,
        finiteGlucose: StableTimestampIndex<ClinicalGlucosePoint>,
        nearestGlucose: NearestGlucoseIndex,
        therapy: List<ClinicalTherapyPoint>,
        forecasts: List<ClinicalForecastPoint>,
        isfTelemetry: StableTimestampIndex<ClinicalTelemetryPoint>,
        crTelemetry: StableTimestampIndex<ClinicalTelemetryPoint>,
        work: AssociationWorkRecorder?
    ): ClinicalEventTypeAssociation {
        val glucoseMean = CappedMeanAccumulator(ClinicalEventAssociationPolicy.MAX_SAMPLES_PER_TYPE, work)
        val glucoseIdentities = HashSet<GlucoseIdentity>()
        for (entry in finiteGlucose.entries) {
            work?.record { queryRowsVisited++ }
            if (!intervals.contains(entry.value.ts)) continue
            work?.record { dedupeChecks++ }
            if (glucoseIdentities.add(GlucoseIdentity(entry.value.ts, entry.value.mmol))) {
                glucoseMean.add(entry.value.mmol)
                if (glucoseMean.isFull) break
            }
        }

        val trendMean = CappedMeanAccumulator(ClinicalEventAssociationPolicy.MAX_SAMPLES_PER_TYPE, work)
        for (event in events) {
            if (trendMean.isFull) break
            work?.record { trendEventsVisited++ }
            val range = finiteGlucose.closedRange(event.startTs, event.endTs)
            if (range.first < range.last) {
                trendMean.add(
                    finiteGlucose.entries[range.last].value.mmol -
                        finiteGlucose.entries[range.first].value.mmol
                )
            }
        }

        val uamMean = CappedMeanAccumulator(ClinicalEventAssociationPolicy.MAX_SAMPLES_PER_TYPE, work)
        for (point in therapy) {
            work?.record { queryRowsVisited++ }
            if (!point.syntheticUam) continue
            val carbs = point.carbsG?.takeIf(Double::isFinite) ?: continue
            if (intervals.contains(point.ts)) uamMean.add(carbs)
            if (uamMean.isFull) break
        }

        val isfMean = telemetryMean(isfTelemetry, intervals, work)
        val crMean = telemetryMean(crTelemetry, intervals, work)

        val forecastErrorMean = CappedMeanAccumulator(ClinicalEventAssociationPolicy.MAX_SAMPLES_PER_TYPE, work)
        for (forecast in forecasts) {
            work?.record { queryRowsVisited++ }
            if (!forecast.mmol.isFinite() || forecast.horizonMin <= 0) continue
            if (!intervals.contains(forecast.ts)) continue
            val expectedTs = addExactOrNull(
                forecast.ts,
                forecast.horizonMin.toLong() * MINUTE_MS
            ) ?: continue
            val actual = nearestGlucose.nearest(expectedTs) ?: continue
            val distance = safeDistance(actual.ts, expectedTs) ?: continue
            if (distance <= FORECAST_TOLERANCE_MS && actual.mmol.isFinite()) {
                forecastErrorMean.add(abs(forecast.mmol - actual.mmol))
            }
            if (forecastErrorMean.isFull) break
        }

        return ClinicalEventTypeAssociation(
            type = type,
            eventCount = events.size,
            totalDurationMinutes = totalDurationMinutes(events, work),
            glucose = glucoseMean.metric(ClinicalEventAssociationPolicy.GLUCOSE_UNIT),
            trend = trendMean.metric(ClinicalEventAssociationPolicy.TREND_UNIT),
            uam = uamMean.metric(ClinicalEventAssociationPolicy.UAM_UNIT),
            isf = isfMean.metric(ClinicalEventAssociationPolicy.ISF_UNIT),
            cr = crMean.metric(ClinicalEventAssociationPolicy.CR_UNIT),
            forecastError = forecastErrorMean.metric(
                ClinicalEventAssociationPolicy.FORECAST_ERROR_UNIT
            )
        )
    }

    private fun telemetryMean(
        index: StableTimestampIndex<ClinicalTelemetryPoint>,
        intervals: InclusiveIntervalIndex,
        work: AssociationWorkRecorder?
    ): CappedMeanAccumulator {
        val mean = CappedMeanAccumulator(ClinicalEventAssociationPolicy.MAX_SAMPLES_PER_TYPE, work)
        for (entry in index.entries) {
            work?.record { queryRowsVisited++ }
            if (intervals.contains(entry.value.ts)) mean.add(entry.value.value)
            if (mean.isFull) break
        }
        return mean
    }

    private fun totalDurationMinutes(
        events: List<CompensationEvent>,
        work: AssociationWorkRecorder?
    ): Long =
        events.fold(0L) { total, event ->
            work?.record { durationRowsVisited++ }
            val minutes = (event.endTs - event.startTs).coerceAtLeast(0L) / MINUTE_MS
            if (total > Long.MAX_VALUE - minutes) Long.MAX_VALUE else total + minutes
        }

    private fun addExactOrNull(value: Long, offset: Long): Long? = try {
        Math.addExact(value, offset)
    } catch (_: ArithmeticException) {
        null
    }

    private data class GlucoseIdentity(val ts: Long, val mmol: Double)

    private data class IndexedValue<T>(val originalIndex: Int, val value: T)

    private class InclusiveIntervalIndex(
        events: List<CompensationEvent>,
        private val work: AssociationWorkRecorder?
    ) {
        private val intervals: List<LongRange> = buildList {
            events.forEach { event ->
                work?.record { intervalEventsVisited++ }
                val previous = lastOrNull()
                if (previous == null) {
                    add(event.startTs..event.endTs)
                } else {
                    work?.record { intervalMergeComparisons++ }
                    if (event.startTs > previous.last) {
                        add(event.startTs..event.endTs)
                    } else {
                        work?.record { intervalMergeComparisons++ }
                        if (event.endTs > previous.last) {
                            this[lastIndex] = previous.first..event.endTs
                        }
                    }
                }
            }
        }

        fun contains(timestamp: Long): Boolean {
            work?.record { intervalMembershipQueries++ }
            var low = 0
            var high = intervals.size
            while (low < high) {
                val middle = (low + high) ushr 1
                work?.record { intervalSearchComparisons++ }
                if (intervals[middle].first <= timestamp) low = middle + 1 else high = middle
            }
            if (low == 0) return false
            work?.record { intervalSearchComparisons++ }
            return timestamp <= intervals[low - 1].last
        }
    }

    private class StableTimestampIndex<T>(
        source: List<IndexedValue<T>>,
        private val timestamp: (T) -> Long,
        private val work: AssociationWorkRecorder?
    ) {
        val entries: List<IndexedValue<T>> = source
            .also { entries -> work?.record { indexEntriesCreated += entries.size } }
            .sortedWith { left, right ->
                work?.record { indexSortComparisons++ }
                timestamp(left.value).compareTo(timestamp(right.value))
                    .takeIf { it != 0 }
                    ?: left.originalIndex.compareTo(right.originalIndex)
            }

        fun closedRange(startTs: Long, endTs: Long): IntRange {
            work?.record { timestampRangeQueries++ }
            val first = lowerBound(startTs)
            val endExclusive = upperBound(endTs)
            return first until endExclusive
        }

        private fun lowerBound(target: Long): Int {
            var low = 0
            var high = entries.size
            while (low < high) {
                val middle = (low + high) ushr 1
                work?.record { timestampRangeSearchComparisons++ }
                if (timestamp(entries[middle].value) < target) low = middle + 1 else high = middle
            }
            return low
        }

        private fun upperBound(target: Long): Int {
            var low = 0
            var high = entries.size
            while (low < high) {
                val middle = (low + high) ushr 1
                work?.record { timestampRangeSearchComparisons++ }
                if (timestamp(entries[middle].value) <= target) low = middle + 1 else high = middle
            }
            return low
        }
    }

    private class NearestGlucoseIndex(
        glucose: List<ClinicalGlucosePoint>,
        private val work: AssociationWorkRecorder?
    ) {
        private val entries: List<IndexedValue<ClinicalGlucosePoint>> = run {
            val sorted = glucose
                .mapIndexed { index, point ->
                    work?.record { inputRowsInspected++ }
                    IndexedValue(index, point)
                }
                .also { entries -> work?.record { indexEntriesCreated += entries.size } }
                .sortedWith { left, right ->
                    work?.record { indexSortComparisons++ }
                    left.value.ts.compareTo(right.value.ts)
                        .takeIf { it != 0 }
                        ?: left.originalIndex.compareTo(right.originalIndex)
                }
            buildList {
                sorted.forEach { entry ->
                    work?.record { nearestTimestampDedupeRowsVisited++ }
                    val previous = lastOrNull()
                    if (previous != null) {
                        work?.record { nearestTimestampDedupeComparisons++ }
                        if (previous.value.ts == entry.value.ts) return@forEach
                    }
                    add(entry)
                }
            }
        }

        fun nearest(targetTs: Long): ClinicalGlucosePoint? {
            work?.record { forecastNearestQueries++ }
            if (entries.isEmpty()) return null
            var low = 0
            var high = entries.size
            while (low < high) {
                val middle = (low + high) ushr 1
                work?.record { forecastNearestSearchComparisons++ }
                if (entries[middle].value.ts < targetTs) low = middle + 1 else high = middle
            }
            var best: IndexedValue<ClinicalGlucosePoint>? = null
            var bestDistance: Long? = null
            fun consider(candidate: IndexedValue<ClinicalGlucosePoint>) {
                work?.record { forecastNearestCandidateChecks++ }
                val distance = safeDistance(candidate.value.ts, targetTs) ?: return
                if (
                    best == null || distance < requireNotNull(bestDistance) ||
                    (distance == bestDistance && candidate.originalIndex < requireNotNull(best).originalIndex)
                ) {
                    best = candidate
                    bestDistance = distance
                }
            }
            if (low < entries.size) consider(entries[low])
            if (low > 0) consider(entries[low - 1])
            return best?.value
        }
    }

    private class CappedMeanAccumulator(
        private val limit: Int,
        private val work: AssociationWorkRecorder?
    ) {
        private var sum = 0.0
        private var count = 0

        val isFull: Boolean
            get() = count >= limit

        fun add(value: Double) {
            work?.record { accumulatorAttempts++ }
            if (isFull) return
            val nextSum = sum + value
            if (!value.isFinite() || !nextSum.isFinite()) {
                work?.record { accumulatorRejectedNonFinite++ }
                return
            }
            sum = nextSum
            count++
            work?.record { accumulatedSamples++ }
        }

        fun metric(unit: String) = ClinicalAssociationMetric(
            sampleCount = count,
            mean = if (count == 0) null else canonicalDouble(sum / count),
            unit = unit
        )
    }

    private class AssociationWorkRecorder(
        private val probe: AssociationAccountingProbe?
    ) {
        init {
            probe?.onRecorderAllocated()
        }

        var inputRowsInspected = 0L
        var indexEntriesCreated = 0L
        var eventSortComparisons = 0L
        var indexSortComparisons = 0L
        var groupingRowsVisited = 0L
        var typeSortComparisons = 0L
        var typeGroupsVisited = 0L
        var intervalEventsVisited = 0L
        var intervalMergeComparisons = 0L
        var nearestTimestampDedupeRowsVisited = 0L
        var nearestTimestampDedupeComparisons = 0L
        var queryRowsVisited = 0L
        var trendEventsVisited = 0L
        var durationRowsVisited = 0L
        var intervalMembershipQueries = 0L
        var intervalSearchComparisons = 0L
        var timestampRangeQueries = 0L
        var timestampRangeSearchComparisons = 0L
        var forecastNearestQueries = 0L
        var forecastNearestSearchComparisons = 0L
        var forecastNearestCandidateChecks = 0L
        var dedupeChecks = 0L
        var accumulatorAttempts = 0L
        var accumulatorRejectedNonFinite = 0L
        var accumulatedSamples = 0L

        inline fun record(operation: AssociationWorkRecorder.() -> Unit) {
            probe?.onCounterOperation()
            operation()
        }

        fun snapshot(): AssociationWorkStats {
            probe?.onSnapshotRequested()
            return AssociationWorkStats(
                inputRowsInspected = inputRowsInspected,
                indexEntriesCreated = indexEntriesCreated,
                eventSortComparisons = eventSortComparisons,
                indexSortComparisons = indexSortComparisons,
                groupingRowsVisited = groupingRowsVisited,
                typeSortComparisons = typeSortComparisons,
                typeGroupsVisited = typeGroupsVisited,
                intervalEventsVisited = intervalEventsVisited,
                intervalMergeComparisons = intervalMergeComparisons,
                nearestTimestampDedupeRowsVisited = nearestTimestampDedupeRowsVisited,
                nearestTimestampDedupeComparisons = nearestTimestampDedupeComparisons,
                queryRowsVisited = queryRowsVisited,
                trendEventsVisited = trendEventsVisited,
                durationRowsVisited = durationRowsVisited,
                intervalMembershipQueries = intervalMembershipQueries,
                intervalSearchComparisons = intervalSearchComparisons,
                timestampRangeQueries = timestampRangeQueries,
                timestampRangeSearchComparisons = timestampRangeSearchComparisons,
                forecastNearestQueries = forecastNearestQueries,
                forecastNearestSearchComparisons = forecastNearestSearchComparisons,
                forecastNearestCandidateChecks = forecastNearestCandidateChecks,
                dedupeChecks = dedupeChecks,
                accumulatorAttempts = accumulatorAttempts,
                accumulatorRejectedNonFinite = accumulatorRejectedNonFinite,
                accumulatedSamples = accumulatedSamples
            )
        }
    }

}

private fun safeDistance(left: Long, right: Long): Long? {
    val difference = try {
        Math.subtractExact(left, right)
    } catch (_: ArithmeticException) {
        return null
    }
    return if (difference == Long.MIN_VALUE) null else abs(difference)
}

private fun canonicalDouble(value: Double) =
    BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP)
        .stripTrailingZeros().toDouble()
