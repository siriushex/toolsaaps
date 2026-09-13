package io.aaps.copilot.data.repository

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ClinicalLeafIdentity(
    val id: String,
    val fromTs: Long,
    val throughTsExclusive: Long,
    val depth: Int,
    val canonicalHash: String
)

internal data class ClinicalSizedLeafRequest(
    val bytes: Int
)

internal class ClinicalPartitionLeaf(
    val descriptor: ClinicalLeafDescriptor,
    val canonicalJson: String,
    internal val wireExpectation: ClinicalLeafWireExpectation
)

internal class ClinicalPartitionPlan(
    leaves: List<ClinicalPartitionLeaf>,
    val coverage: ClinicalCoverageLedger,
    val rootWireProjection: ClinicalPartitionRootWireProjection
) {
    val leaves: List<ClinicalPartitionLeaf> =
        Collections.unmodifiableList(ArrayList(leaves))
}

internal class ClinicalPartitionEventWindowBounds private constructor(
    val from24h: Long,
    val through24h: Long,
    val from7d: Long,
    val through7d: Long,
    val from30d: Long,
    val through30d: Long
) {
    companion object {
        fun capture(dataset: ClinicalReportDataset): ClinicalPartitionEventWindowBounds {
            val bounds = ClinicalPartitionEventWindowBounds(
                from24h = dataset.detail24h.fromTs,
                through24h = dataset.detail24h.throughTs,
                from7d = dataset.summary7d.fromTs,
                through7d = dataset.summary7d.throughTs,
                from30d = dataset.summary30d.fromTs,
                through30d = dataset.summary30d.throughTs
            )
            if (
                bounds.from24h > bounds.through24h ||
                bounds.from7d > bounds.through7d ||
                bounds.from30d > bounds.through30d
            ) {
                throw ClinicalOpenAiException.InvalidInput()
            }
            return bounds
        }
    }
}

internal class ClinicalReportPartitionPlanner(
    private val requestBudgetBytes: Int,
    private val minimumIntervalMillis: Long = 15 * 60_000L,
    private val maximumLeafCount: Int = MAX_PARTITION_LEAVES,
    private val requestFactory: suspend (
        ClinicalReportDataset,
        ClinicalLeafIdentity,
        String
    ) -> ClinicalSizedLeafRequest,
    private val canonicalizer: suspend (
        ClinicalReportDataset,
        ClinicalPartitionEventWindowBounds
    ) -> String = ClinicalReportDatasetBuilder::serializePartitionCancellable,
    private val probe: ClinicalDatasetSerializationProbe? = null
) {
    init {
        require(requestBudgetBytes > 0)
        require(minimumIntervalMillis > 0L)
        require(maximumLeafCount in 1..MAX_PARTITION_LEAVES)
    }

    suspend fun plan(
        dataset: ClinicalReportDataset,
        fullCanonicalJson: String
    ): ClinicalPartitionPlan {
        validateDataset(dataset)
        val eventWindowBounds = ClinicalPartitionEventWindowBounds.capture(dataset)
        val plannedActivityWindow = ClinicalPlannedActivityPeriodPolicy
            .requireBoundedPreparedWindow(dataset)
        val zone = parseZone(dataset.zoneId)
        val immutableSource = immutableDataset(dataset, eventWindowBounds)
        val (overallFrom, overallThroughInclusive) = overallBounds(plannedActivityWindow)
        val overallThroughExclusive = try {
            Math.addExact(overallThroughInclusive, 1L)
        } catch (_: ArithmeticException) {
            coverageMismatch()
        }
        if (overallFrom >= overallThroughExclusive) coverageMismatch()

        val expectedRows = semanticRows(immutableSource)
        val leaves = ArrayList<ClinicalPartitionLeaf>()
        val partitionBudget = PartitionBudget(maximumLeafCount)
        var intervalFrom = overallFrom
        while (intervalFrom < overallThroughExclusive) {
            currentCoroutineContext().ensureActive()
            val intervalThrough = nextLocalDayBoundary(
                fromTs = intervalFrom,
                overallThroughTsExclusive = overallThroughExclusive,
                zone = zone
            )
            partition(
                source = immutableSource,
                fromTs = intervalFrom,
                throughTsExclusive = intervalThrough,
                depth = 0,
                eventWindowBounds = eventWindowBounds,
                plannedActivityWindow = plannedActivityWindow,
                output = leaves,
                partitionBudget = partitionBudget
            )
            intervalFrom = intervalThrough
        }

        val ordered = leaves.sortedWith(
            compareBy<ClinicalPartitionLeaf>(
                { it.descriptor.fromTs },
                { it.descriptor.throughTsExclusive },
                { it.descriptor.id }
            )
        )
        val coverage = ClinicalCoverageLedger.create(
            expectedFromTs = overallFrom,
            expectedThroughTsExclusive = overallThroughExclusive,
            expectedRows = expectedRows,
            leaves = ordered.map(ClinicalPartitionLeaf::descriptor)
        )
        val rootWireProjection = ClinicalPartitionWireAuthenticator.verify(
            rootCanonicalJson = fullCanonicalJson,
            leaves = ordered,
            probe = probe
        )
        return ClinicalPartitionPlan(ordered, coverage, rootWireProjection)
    }

    private suspend fun partition(
        source: ClinicalReportDataset,
        fromTs: Long,
        throughTsExclusive: Long,
        depth: Int,
        eventWindowBounds: ClinicalPartitionEventWindowBounds,
        plannedActivityWindow: ClinicalPreparedPeriodWindow,
        output: MutableList<ClinicalPartitionLeaf>,
        partitionBudget: PartitionBudget
    ) {
        currentCoroutineContext().ensureActive()
        if (fromTs >= throughTsExclusive || depth < 0) coverageMismatch()
        if (output.size >= maximumLeafCount) {
            throw ClinicalPartitionException.MinimumIntervalTooLarge()
        }
        partitionBudget.claimNode()

        val filtered = filteredDataset(
            source,
            fromTs,
            throughTsExclusive,
            plannedActivityWindow
        )
        val accepted = acceptedLeafOrNull(
            filtered = filtered,
            fromTs = fromTs,
            throughTsExclusive = throughTsExclusive,
            depth = depth,
            eventWindowBounds = eventWindowBounds
        )
        if (accepted != null) {
            output += accepted
            probe?.onPartitionLeafRetained(retainedLargeArtifacts = 1)
            return
        }

        val split = splitPoint(fromTs, throughTsExclusive)
        val childDepth = try {
            Math.addExact(depth, 1)
        } catch (_: ArithmeticException) {
            throw ClinicalPartitionException.MinimumIntervalTooLarge()
        }
        partition(
            filtered,
            fromTs,
            split,
            childDepth,
            eventWindowBounds,
            plannedActivityWindow,
            output,
            partitionBudget
        )
        partition(
            filtered,
            split,
            throughTsExclusive,
            childDepth,
            eventWindowBounds,
            plannedActivityWindow,
            output,
            partitionBudget
        )
    }

    private suspend fun acceptedLeafOrNull(
        filtered: ClinicalReportDataset,
        fromTs: Long,
        throughTsExclusive: Long,
        depth: Int,
        eventWindowBounds: ClinicalPartitionEventWindowBounds
    ): ClinicalPartitionLeaf? {
        val canonicalJson = canonicalizer(filtered, eventWindowBounds)
        val canonicalHash = ClinicalReportDatasetBuilder.sha256Cancellable(canonicalJson)
        val identity = ClinicalLeafIdentity(
            id = leafId(fromTs, throughTsExclusive, depth, canonicalHash),
            fromTs = fromTs,
            throughTsExclusive = throughTsExclusive,
            depth = depth,
            canonicalHash = canonicalHash
        )
        val sizedRequest = requestFactory(filtered, identity, canonicalJson)
        if (sizedRequest.bytes <= 0) coverageMismatch()
        if (sizedRequest.bytes > requestBudgetBytes) return null
        return ClinicalPartitionLeaf(
            descriptor = ClinicalLeafDescriptor(
                id = identity.id,
                fromTs = fromTs,
                throughTsExclusive = throughTsExclusive,
                depth = depth,
                sourceRows = semanticRows(filtered),
                canonicalHash = canonicalHash,
                requestBytes = sizedRequest.bytes
            ),
            canonicalJson = canonicalJson,
            wireExpectation = ClinicalLeafWireExpectation.from(filtered)
        )
    }

    private suspend fun immutableDataset(
        source: ClinicalReportDataset,
        eventWindowBounds: ClinicalPartitionEventWindowBounds
    ): ClinicalReportDataset {
        val context = currentCoroutineContext()
        return source.copy(
            detail24h = source.detail24h.copy(
                glucose = copyChecked(source.detail24h.glucose) { it.copy() },
                calibratedGlucose = copyChecked(source.detail24h.calibratedGlucose) {
                    it.copy()
                },
                therapy = copyChecked(source.detail24h.therapy) { it.detachedCopy() },
                targets = copyChecked(source.detail24h.targets) { it.detachedCopy() },
                forecasts = copyChecked(source.detail24h.forecasts) { it.copy() },
                telemetry = copyChecked(source.detail24h.telemetry) { it.copy() },
                forecastQuality = copyChecked(source.detail24h.forecastQuality) { it.copy() }
            ),
            glucose7d = copyChecked(source.glucose7d) { it.copy() },
            therapy7d = copyChecked(source.therapy7d) { it.detachedCopy() },
            targets7d = copyChecked(source.targets7d) { it.detachedCopy() },
            glucose30d = copyChecked(source.glucose30d) { it.copy() },
            therapy30d = copyChecked(source.therapy30d) { it.detachedCopy() },
            targets30d = copyChecked(source.targets30d) { it.detachedCopy() },
            summary24h = source.summary24h?.let { immutableSummary(it) },
            summary7d = immutableSummary(source.summary7d),
            summary30d = immutableSummary(source.summary30d),
            currentSnapshot = source.currentSnapshot.copy(),
            energyProfile = source.energyProfile?.detachedCopy(),
            plannedActivities = copyChecked(source.plannedActivities) { activity ->
                activity.copy(targetBlockers = unmodifiableCopy(activity.targetBlockers))
            },
            eventSummaries = copyChecked(
                ClinicalReportDatasetBuilder.canonicalSelectedEventUnionForPartition(
                    source,
                    eventWindowBounds,
                    context::ensureActive
                )
            ) { it.copy() },
            eventTypeAssociations = copyChecked(source.eventTypeAssociations) { association ->
                association.detachedCopy()
            }
        )
    }

    private suspend fun filteredDataset(
        source: ClinicalReportDataset,
        fromTs: Long,
        throughTsExclusive: Long,
        plannedActivityWindow: ClinicalPreparedPeriodWindow
    ): ClinicalReportDataset {
        fun contains(ts: Long): Boolean = ts >= fromTs && ts < throughTsExclusive

        val filteredDetail = source.detail24h.copy(
            fromTs = fromTs,
            throughTs = throughTsExclusive - 1L,
            glucose = filterChecked(source.detail24h.glucose, { contains(it.ts) }) {
                it.copy()
            },
            calibratedGlucose = filterChecked(
                source.detail24h.calibratedGlucose,
                { contains(it.ts) }
            ) {
                it.copy()
            },
            therapy = filterChecked(source.detail24h.therapy, { contains(it.ts) }) {
                it.detachedCopy()
            },
            targets = filterChecked(source.detail24h.targets, { contains(it.ts) }) {
                it.detachedCopy()
            },
            forecasts = filterChecked(source.detail24h.forecasts, { contains(it.ts) }) {
                it.copy()
            },
            telemetry = filterChecked(source.detail24h.telemetry, { contains(it.ts) }) {
                it.copy()
            }
        )
        val snapshot = ClinicalReportDatasetBuilder.currentSnapshot(
            throughTsExclusive - 1L,
            filteredDetail
        )
        return source.copy(
            detail24h = filteredDetail,
            glucose7d = filterChecked(source.glucose7d, { contains(it.ts) }) { it.copy() },
            therapy7d = filterChecked(source.therapy7d, { contains(it.ts) }) {
                it.detachedCopy()
            },
            targets7d = filterChecked(source.targets7d, { contains(it.ts) }) {
                it.detachedCopy()
            },
            glucose30d = filterChecked(source.glucose30d, { contains(it.ts) }) { it.copy() },
            therapy30d = filterChecked(source.therapy30d, { contains(it.ts) }) {
                it.detachedCopy()
            },
            targets30d = filterChecked(source.targets30d, { contains(it.ts) }) {
                it.detachedCopy()
            },
            summary24h = source.summary24h,
            summary7d = source.summary7d,
            summary30d = source.summary30d,
            currentSnapshot = snapshot.copy(),
            plannedActivities = filterChecked(
                source.plannedActivities,
                { activity ->
                    ClinicalPlannedActivityPeriodPolicy
                        .ownershipTimestamp(activity, plannedActivityWindow)
                        ?.let(::contains) == true
                }
            ) { activity ->
                activity.copy(targetBlockers = activity.targetBlockers.toList())
            },
            eventSummaries = filterChecked(
                source.eventSummaries,
                { event ->
                    eventOwnershipTimestamp(event, plannedActivityWindow)?.let(::contains) == true
                }
            ) { it.copy() }
        )
    }

    private fun overallBounds(
        plannedActivityWindow: ClinicalPreparedPeriodWindow
    ): Pair<Long, Long> = plannedActivityWindow.fromTs to plannedActivityWindow.throughTs

    private fun eventOwnershipTimestamp(
        event: ClinicalEventSummary,
        window: ClinicalPreparedPeriodWindow
    ): Long? = maxOf(event.startTs, window.fromTs).takeIf {
        event.startTs <= window.throughTs && event.endTs >= window.fromTs
    }

    private suspend fun semanticRows(dataset: ClinicalReportDataset): ClinicalSeriesRowCounts {
        val glucose = addCounts(
            dataset.detail24h.glucose.size,
            dataset.detail24h.calibratedGlucose.size,
            dataset.glucose7d.size,
            dataset.glucose30d.size
        )
        val insulin = addCounts(
            countChecked(dataset.detail24h.therapy) { it.insulinU != null },
            countChecked(dataset.therapy7d) { it.insulinU != null },
            countChecked(dataset.therapy30d) { it.insulinU != null }
        )
        val carbs = addCounts(
            countChecked(dataset.detail24h.therapy) { it.carbsG != null },
            countChecked(dataset.therapy7d) { it.carbsG != null },
            countChecked(dataset.therapy30d) { it.carbsG != null }
        )
        val targets = addCounts(
            dataset.detail24h.targets.size,
            dataset.targets7d.size,
            dataset.targets30d.size
        )
        return ClinicalSeriesRowCounts(glucose, insulin, carbs, targets)
    }

    private suspend fun <T, R> copyChecked(
        rows: List<T>,
        copy: (T) -> R
    ): List<R> {
        val output = ArrayList<R>()
        rows.forEachIndexed { index, row ->
            if (index % CANCELLATION_CHECKPOINT_ROWS == 0) {
                currentCoroutineContext().ensureActive()
            }
            output += copy(row)
        }
        return unmodifiableCopy(output)
    }

    private suspend fun <T, R> filterChecked(
        rows: List<T>,
        predicate: (T) -> Boolean,
        copy: (T) -> R
    ): List<R> {
        val output = ArrayList<R>()
        rows.forEachIndexed { index, row ->
            if (index % CANCELLATION_CHECKPOINT_ROWS == 0) {
                currentCoroutineContext().ensureActive()
            }
            if (predicate(row)) output += copy(row)
        }
        return unmodifiableCopy(output)
    }

    private suspend fun <T> countChecked(
        rows: List<T>,
        predicate: (T) -> Boolean
    ): Int {
        var count = 0
        rows.forEachIndexed { index, row ->
            if (index % CANCELLATION_CHECKPOINT_ROWS == 0) {
                currentCoroutineContext().ensureActive()
            }
            if (predicate(row)) {
                count = try {
                    Math.addExact(count, 1)
                } catch (_: ArithmeticException) {
                    coverageMismatch()
                }
            }
        }
        return count
    }

    private suspend fun immutableSummary(summary: ClinicalPeriodSummary): ClinicalPeriodSummary =
        summary.copy(
            weekdayPattern = copyChecked(summary.weekdayPattern) { it.copy() },
            weekendPattern = copyChecked(summary.weekendPattern) { it.copy() },
            quality = summary.quality.copy(
                rejected = summary.quality.rejected.copy()
            ),
            activity = summary.activity.copy(),
            mealEnergy = summary.mealEnergy.detachedCopy(),
            basalContext = summary.basalContext.copy(),
            therapyContext = summary.therapyContext.copy(),
            probableMealWindows = copyChecked(summary.probableMealWindows) { it.copy() },
            recentProbableMealWindows = copyChecked(summary.recentProbableMealWindows) { it.copy() }
        )

    private fun splitPoint(fromTs: Long, throughTsExclusive: Long): Long {
        val minimumSplit = try {
            Math.addExact(fromTs, minimumIntervalMillis)
        } catch (_: ArithmeticException) {
            throw ClinicalPartitionException.MinimumIntervalTooLarge()
        }
        val maximumSplit = try {
            Math.subtractExact(throughTsExclusive, minimumIntervalMillis)
        } catch (_: ArithmeticException) {
            throw ClinicalPartitionException.MinimumIntervalTooLarge()
        }
        if (minimumSplit > maximumSplit) {
            throw ClinicalPartitionException.MinimumIntervalTooLarge()
        }

        val midpoint = BigInteger.valueOf(fromTs)
            .add(BigInteger.valueOf(throughTsExclusive))
            .divide(TWO)
        val exactMidpoint = midpoint.longValueExact()
        val quotientAndRemainder = midpoint.divideAndRemainder(EPOCH_MINUTE)
        var roundedMinute = quotientAndRemainder[0]
        if (quotientAndRemainder[1].abs() >= HALF_MINUTE) {
            roundedMinute += BigInteger.valueOf(midpoint.signum().toLong())
        }
        val rounded = try {
            roundedMinute.multiply(EPOCH_MINUTE).longValueExact()
        } catch (_: ArithmeticException) {
            return exactMidpoint
        }
        return if (rounded in minimumSplit..maximumSplit) rounded else exactMidpoint
    }

    private fun nextLocalDayBoundary(
        fromTs: Long,
        overallThroughTsExclusive: Long,
        zone: ZoneId
    ): Long {
        val nextDayStart = try {
            Instant.ofEpochMilli(fromTs)
                .atZone(zone)
                .toLocalDate()
                .plusDays(1L)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        } catch (_: DateTimeException) {
            coverageMismatch()
        } catch (_: ArithmeticException) {
            coverageMismatch()
        }
        val throughTsExclusive = minOf(overallThroughTsExclusive, nextDayStart)
        if (throughTsExclusive <= fromTs) coverageMismatch()
        return throughTsExclusive
    }

    private fun validateDataset(dataset: ClinicalReportDataset) {
        if (dataset.schemaVersion != ClinicalReportDatasetBuilder.SCHEMA_VERSION) {
            throw ClinicalOpenAiException.InvalidInput()
        }
        if (
            dataset.detail24h.fromTs > dataset.detail24h.throughTs ||
            dataset.summary7d.fromTs > dataset.summary7d.throughTs ||
            dataset.summary30d.fromTs > dataset.summary30d.throughTs
        ) {
            coverageMismatch()
        }
    }

    private fun parseZone(zoneId: String): ZoneId = try {
        ZoneId.of(zoneId)
    } catch (_: DateTimeException) {
        throw ClinicalOpenAiException.InvalidInput()
    }

    private fun leafId(
        fromTs: Long,
        throughTsExclusive: Long,
        depth: Int,
        canonicalHash: String
    ): String {
        val canonicalIdentity = buildString {
            appendLengthPrefixed(fromTs.toString())
            appendLengthPrefixed(throughTsExclusive.toString())
            appendLengthPrefixed(depth.toString())
            appendLengthPrefixed(canonicalHash)
        }
        return ClinicalReportDatasetBuilder.sha256(canonicalIdentity)
    }

    private fun StringBuilder.appendLengthPrefixed(value: String) {
        append(value.toByteArray(StandardCharsets.UTF_8).size)
        append(':')
        append(value)
    }

    private fun addCounts(vararg counts: Int): Int = try {
        counts.fold(0, Math::addExact)
    } catch (_: ArithmeticException) {
        coverageMismatch()
    }

    private fun coverageMismatch(): Nothing {
        throw ClinicalPartitionException.CoverageMismatch()
    }

    companion object {
        // A bounded report can touch at most 33 local dates. Binary 15-minute splitting
        // produces at most 64 leaves per date before the minimum interval stops recursion.
        const val MAX_PARTITION_LEAVES = 33 * 64
        private const val CANCELLATION_CHECKPOINT_ROWS = 256
        private val EPOCH_MINUTE: BigInteger = BigInteger.valueOf(60_000L)
        private val HALF_MINUTE: BigInteger = BigInteger.valueOf(30_000L)
        private val TWO: BigInteger = BigInteger.valueOf(2L)
    }
}

private class PartitionBudget(maximumLeafCount: Int) {
    private val maximumNodes = Math.subtractExact(Math.multiplyExact(maximumLeafCount, 2), 1)
    private var nodes = 0

    fun claimNode() {
        if (nodes >= maximumNodes) {
            throw ClinicalPartitionException.MinimumIntervalTooLarge()
        }
        nodes++
    }
}

private fun ClinicalTherapyPoint.detachedCopy(): ClinicalTherapyPoint =
    ClinicalTherapyPoint(
        ts = ts,
        insulinU = insulinU,
        carbsG = carbsG,
        syntheticUam = syntheticUam,
        insulinEvidence = insulinEvidence,
        contextKind = contextKind
    ).also { copy ->
        copy.internalEventId = internalEventId
    }

private fun ClinicalTargetPoint.detachedCopy(): ClinicalTargetPoint =
    ClinicalTargetPoint(
        ts = ts,
        lowMmol = lowMmol,
        highMmol = highMmol,
        durationMs = durationMs,
        endTs = endTs,
        cancelled = cancelled
    ).also { copy ->
        copy.internalEventId = internalEventId
    }

private fun ClinicalMealEnergySummary.detachedCopy(): ClinicalMealEnergySummary = copy(
    estimatedTotalMealEnergyKcal = estimatedTotalMealEnergyKcal?.copy(),
    netEnergyKcal = netEnergyKcal?.copy()
)

private fun ClinicalEnergyProfileSummary.detachedCopy(): ClinicalEnergyProfileSummary = copy(
    maintenanceEnergyKcal = maintenanceEnergyKcal?.copy()
)

private fun ClinicalEventTypeAssociation.detachedCopy(): ClinicalEventTypeAssociation = copy(
    glucose = glucose.copy(),
    trend = trend.copy(),
    uam = uam.copy(),
    isf = isf.copy(),
    cr = cr.copy(),
    forecastError = forecastError.copy()
)

private fun <T> unmodifiableCopy(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
