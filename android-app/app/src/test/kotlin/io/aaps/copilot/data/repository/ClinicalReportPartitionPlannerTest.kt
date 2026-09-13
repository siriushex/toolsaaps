package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.AbstractList
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.Locale
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class ClinicalReportPartitionPlannerTest {

    @Test
    fun createAcceptsExactContiguousCoverageInIntervalOrder() {
        val later = leaf(
            id = "leaf-b",
            fromTs = 200L,
            throughTsExclusive = 300L,
            rows = ClinicalSeriesRowCounts(glucose = 2, carbs = 1),
            hash = "hash-b"
        )
        val earlier = leaf(
            id = "leaf-a",
            fromTs = 100L,
            throughTsExclusive = 200L,
            rows = ClinicalSeriesRowCounts(glucose = 3, insulin = 1, targets = 1),
            hash = "hash-a"
        )

        val ledger = ClinicalCoverageLedger.create(
            expectedFromTs = 100L,
            expectedThroughTsExclusive = 300L,
            expectedRows = ClinicalSeriesRowCounts(
                glucose = 5,
                insulin = 1,
                carbs = 1,
                targets = 1
            ),
            leaves = listOf(later, earlier)
        )
        val sameLedger = ClinicalCoverageLedger.create(
            expectedFromTs = 100L,
            expectedThroughTsExclusive = 300L,
            expectedRows = ledger.sourceRows,
            leaves = listOf(earlier, later)
        )

        assertThat(ledger.expectedFromTs).isEqualTo(100L)
        assertThat(ledger.expectedThroughTsExclusive).isEqualTo(300L)
        assertThat(ledger.sourceRows).isEqualTo(
            ClinicalSeriesRowCounts(glucose = 5, insulin = 1, carbs = 1, targets = 1)
        )
        assertThat(ledger.leafCount).isEqualTo(2)
        assertThat(ledger.leafHashes).containsExactly("hash-a", "hash-b").inOrder()
        assertThat(ledger.ledgerHash).matches("[0-9a-f]{64}")
        assertThat(ledger.ledgerHash)
            .isEqualTo("b3b9ec4732b9c6b31ef79c637167c7dd2dda07140f3c14db460ee2fad3b921be")
        assertThat(ledger.ledgerHash).isEqualTo(sameLedger.ledgerHash)
    }

    @Test
    fun createDefensivelyCopiesAndExposesUnmodifiableLeafHashes() {
        val sourceLeaves = mutableListOf(
            leaf("leaf-a", 100L, 200L, hash = "hash-a"),
            leaf("leaf-b", 200L, 300L, hash = "hash-b")
        )
        val ledger = ClinicalCoverageLedger.create(
            expectedFromTs = 100L,
            expectedThroughTsExclusive = 300L,
            expectedRows = ClinicalSeriesRowCounts(glucose = 2),
            leaves = sourceLeaves
        )

        sourceLeaves.clear()

        assertThat(ledger.leafCount).isEqualTo(2)
        assertThat(ledger.leafHashes).containsExactly("hash-a", "hash-b").inOrder()
        assertThrows(UnsupportedOperationException::class.java) {
            (ledger.leafHashes as MutableList<String>).add("forged-hash")
        }
        assertThat(ledger.leafHashes).containsExactly("hash-a", "hash-b").inOrder()
    }

    @Test
    fun createRejectsUnpairedSurrogatesInCanonicalStrings() {
        listOf(
            leaf(id = "\uD800", fromTs = 100L, throughTsExclusive = 200L, hash = "hash-a"),
            leaf(id = "leaf-a", fromTs = 100L, throughTsExclusive = 200L, hash = "\uDFFF")
        ).forEach { malformed ->
            assertThrows(ClinicalPartitionException.CoverageMismatch::class.java) {
                ClinicalCoverageLedger.create(
                    expectedFromTs = 100L,
                    expectedThroughTsExclusive = 200L,
                    expectedRows = ClinicalSeriesRowCounts(glucose = 1),
                    leaves = listOf(malformed)
                )
            }
        }
    }

    @Test
    fun changingDescriptorFieldChangesLedgerHash() {
        val original = leaf("leaf-a", 100L, 200L, hash = "hash-a", requestBytes = 128)
        val changed = original.copy(requestBytes = 129)

        val originalLedger = singleLeafLedger(original)
        val changedLedger = singleLeafLedger(changed)

        assertThat(changedLedger.ledgerHash).isNotEqualTo(originalLedger.ledgerHash)
    }

    @Test
    fun nonAsciiCanonicalStringsRemainDeterministicAcrossDefaultLocales() {
        val originalLocale = Locale.getDefault()
        val unicodeLeaf = leaf(
            id = "\u043B\u0438\u0441\u0442-\u00E9",
            fromTs = 100L,
            throughTsExclusive = 200L,
            hash = "\u0445\u044D\u0448-\u00DF"
        )

        try {
            Locale.setDefault(Locale.US)
            val usHash = singleLeafLedger(unicodeLeaf).ledgerHash
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val turkishHash = singleLeafLedger(unicodeLeaf).ledgerHash

            assertThat(turkishHash).isEqualTo(usHash)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun createRejectsCoverageGap() {
        val failure = assertThrows(ClinicalPartitionException.CoverageMismatch::class.java) {
            ClinicalCoverageLedger.create(
                expectedFromTs = 100L,
                expectedThroughTsExclusive = 300L,
                expectedRows = ClinicalSeriesRowCounts(glucose = 2),
                leaves = listOf(
                    leaf("leaf-a", 100L, 190L, hash = "hash-a"),
                    leaf("leaf-b", 200L, 300L, hash = "hash-b")
                )
            )
        }

        assertThat(failure).hasMessageThat().isEqualTo("Clinical report coverage mismatch")
    }

    @Test
    fun createRejectsCoverageOverlap() {
        assertThrows(ClinicalPartitionException.CoverageMismatch::class.java) {
            ClinicalCoverageLedger.create(
                expectedFromTs = 100L,
                expectedThroughTsExclusive = 300L,
                expectedRows = ClinicalSeriesRowCounts(glucose = 2),
                leaves = listOf(
                    leaf("leaf-a", 100L, 210L, hash = "hash-a"),
                    leaf("leaf-b", 200L, 300L, hash = "hash-b")
                )
            )
        }
    }

    @Test
    fun createRejectsDuplicateLeafIds() {
        assertThrows(ClinicalPartitionException.CoverageMismatch::class.java) {
            ClinicalCoverageLedger.create(
                expectedFromTs = 100L,
                expectedThroughTsExclusive = 300L,
                expectedRows = ClinicalSeriesRowCounts(glucose = 2),
                leaves = listOf(
                    leaf("leaf-a", 100L, 200L, hash = "hash-a"),
                    leaf("leaf-a", 200L, 300L, hash = "hash-b")
                )
            )
        }
    }

    @Test
    fun createRejectsDuplicateCanonicalHashes() {
        assertThrows(ClinicalPartitionException.CoverageMismatch::class.java) {
            ClinicalCoverageLedger.create(
                expectedFromTs = 100L,
                expectedThroughTsExclusive = 300L,
                expectedRows = ClinicalSeriesRowCounts(glucose = 2),
                leaves = listOf(
                    leaf("leaf-a", 100L, 200L, hash = "hash-a"),
                    leaf("leaf-b", 200L, 300L, hash = "hash-a")
                )
            )
        }
    }

    @Test
    fun createRejectsMismatchedAggregateRowTotals() {
        assertThrows(ClinicalPartitionException.CoverageMismatch::class.java) {
            ClinicalCoverageLedger.create(
                expectedFromTs = 100L,
                expectedThroughTsExclusive = 300L,
                expectedRows = ClinicalSeriesRowCounts(glucose = 3),
                leaves = listOf(
                    leaf("leaf-a", 100L, 200L, hash = "hash-a"),
                    leaf("leaf-b", 200L, 300L, hash = "hash-b")
                )
            )
        }
    }

    @Test
    fun rowCountAdditionOverflowBecomesCoverageMismatch() {
        assertThrows(ClinicalPartitionException.CoverageMismatch::class.java) {
            ClinicalCoverageLedger.create(
                expectedFromTs = 100L,
                expectedThroughTsExclusive = 300L,
                expectedRows = ClinicalSeriesRowCounts(glucose = Int.MAX_VALUE),
                leaves = listOf(
                    leaf(
                        id = "leaf-a",
                        fromTs = 100L,
                        throughTsExclusive = 200L,
                        rows = ClinicalSeriesRowCounts(glucose = Int.MAX_VALUE),
                        hash = "hash-a"
                    ),
                    leaf("leaf-b", 200L, 300L, hash = "hash-b")
                )
            )
        }
    }

    @Test
    fun rowCountOverflowCannotWrapToMatchingExpectedRows() {
        assertThrows(ClinicalPartitionException.CoverageMismatch::class.java) {
            ClinicalCoverageLedger.create(
                expectedFromTs = 100L,
                expectedThroughTsExclusive = 300L,
                expectedRows = ClinicalSeriesRowCounts(glucose = Int.MIN_VALUE),
                leaves = listOf(
                    leaf(
                        id = "leaf-a",
                        fromTs = 100L,
                        throughTsExclusive = 200L,
                        rows = ClinicalSeriesRowCounts(glucose = Int.MAX_VALUE),
                        hash = "hash-a"
                    ),
                    leaf("leaf-b", 200L, 300L, hash = "hash-b")
                )
            )
        }
    }

    @Test
    fun factoryRejectsMatchingNegativeExpectedAndLeafRows() {
        assertThrows(ClinicalPartitionException.CoverageMismatch::class.java) {
            ClinicalCoverageLedger.create(
                expectedFromTs = 100L,
                expectedThroughTsExclusive = 200L,
                expectedRows = ClinicalSeriesRowCounts(glucose = -1),
                leaves = listOf(
                    leaf(
                        id = "leaf-a",
                        fromTs = 100L,
                        throughTsExclusive = 200L,
                        rows = ClinicalSeriesRowCounts(glucose = -1),
                        hash = "hash-a"
                    )
                )
            )
        }
    }

    @Test
    fun createRejectsMalformedBoundsAndLeafDescriptors() {
        val valid = leaf("leaf-a", 100L, 200L, hash = "hash-a")
        val malformedInputs = listOf(
            Triple(200L, 200L, listOf(valid)),
            Triple(100L, 200L, emptyList()),
            Triple(100L, 200L, listOf(valid.copy(id = " "))),
            Triple(100L, 200L, listOf(valid.copy(canonicalHash = ""))),
            Triple(100L, 200L, listOf(valid.copy(fromTs = 200L))),
            Triple(100L, 200L, listOf(valid.copy(depth = -1))),
            Triple(100L, 200L, listOf(valid.copy(requestBytes = 0))),
            Triple(
                100L,
                200L,
                listOf(valid.copy(sourceRows = ClinicalSeriesRowCounts(insulin = -1)))
            )
        )

        malformedInputs.forEach { (fromTs, throughTsExclusive, leaves) ->
            assertThrows(ClinicalPartitionException.CoverageMismatch::class.java) {
                ClinicalCoverageLedger.create(
                    expectedFromTs = fromTs,
                    expectedThroughTsExclusive = throughTsExclusive,
                    expectedRows = ClinicalSeriesRowCounts(glucose = 1),
                    leaves = leaves
                )
            }
        }
    }

    @Test
    fun constructorRejectsNonPositiveBudgetAndMinimumInterval() {
        assertThrows(IllegalArgumentException::class.java) {
            planner(requestBudgetBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            planner(requestBudgetBytes = 1, minimumIntervalMillis = 0L)
        }
    }

    @Test
    fun overlongPreparedUnionFailsBeforeCanonicalizationOrRequestSizing() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val canonicalizations = AtomicInteger()
        val requests = AtomicInteger()
        val source = dataset(
            zone.id,
            from,
            from + ClinicalPlannedActivityPeriodPolicy.MAX_PREPARED_REPORT_SPAN_MS + 1L,
            emptyList()
        )

        val failure = runCatching {
            planner(
                canonicalizer = { _, _ ->
                    canonicalizations.incrementAndGet()
                    "{}"
                },
                requestFactory = { _, _, _ ->
                    requests.incrementAndGet()
                    sizedRequest("unexpected", 1)
                }
            ).plan(source, "{}")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ClinicalOpenAiException.DatasetTooLarge::class.java)
        assertThat(canonicalizations.get()).isEqualTo(0)
        assertThat(requests.get()).isEqualTo(0)
    }

    @Test
    fun maximalThirtyDayReportWithExactTailRemainsValid() = runTest {
        val zone = ZoneId.of("UTC")
        val from30d = local(zone, 2026, 7, 1)
        val summaryThrough = from30d + 30L * DAY_MS
        val exactThrough = summaryThrough + ClinicalSummaryCalculator.BUCKET_MS - 1L
        val source = dataset(zone.id, exactThrough - DAY_MS, exactThrough, emptyList()).let { base ->
            base.copy(
                generatedAt = exactThrough,
                summary7d = base.summary7d.copy(
                    fromTs = summaryThrough - 7L * DAY_MS,
                    throughTs = summaryThrough,
                    days = 7
                ),
                summary30d = base.summary30d.copy(
                    fromTs = from30d,
                    throughTs = summaryThrough,
                    days = 30
                )
            )
        }

        val plan = planner().planForTest(source)

        assertThat(plan.coverage.expectedFromTs).isEqualTo(from30d)
        assertThat(plan.coverage.expectedThroughTsExclusive).isEqualTo(exactThrough + 1L)
        assertThat(plan.leaves).hasSize(31)
    }

    @Test
    fun preparedUnionSpanLimitAcceptsExactBoundary() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 1) + DAY_MS - 60_000L
        val through = from + ClinicalPlannedActivityPeriodPolicy.MAX_PREPARED_REPORT_SPAN_MS

        val plan = planner().planForTest(dataset(zone.id, from, through, emptyList()))

        assertThat(plan.coverage.expectedFromTs).isEqualTo(from)
        assertThat(plan.coverage.expectedThroughTsExclusive).isEqualTo(through + 1L)
        assertThat(plan.leaves).hasSize(33)
    }

    @Test
    fun leafLimitAcceptsBoundaryAndRejectsPlusOneBeforeThirdSerialization() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val exactBoundary = dataset(
            zone.id,
            from,
            from + 2L * DAY_MS - 1L,
            emptyList()
        )
        val overBoundary = dataset(
            zone.id,
            from,
            from + 3L * DAY_MS - 1L,
            emptyList()
        )
        val canonicalizations = AtomicInteger()
        val requests = AtomicInteger()
        val limited = planner(
            maximumLeafCount = 2,
            canonicalizer = { leaf, bounds ->
                canonicalizations.incrementAndGet()
                ClinicalReportDatasetBuilder.serializePartitionCancellable(leaf, bounds)
            },
            requestFactory = { _, _, _ ->
                requests.incrementAndGet()
                sizedRequest("fits", 1)
            }
        )

        val accepted = limited.planForTest(exactBoundary)
        assertThat(accepted.leaves).hasSize(2)
        assertThat(canonicalizations.get()).isEqualTo(2)
        assertThat(requests.get()).isEqualTo(2)

        canonicalizations.set(0)
        requests.set(0)
        val failure = runCatching { limited.planForTest(overBoundary) }.exceptionOrNull()

        assertThat(failure)
            .isInstanceOf(ClinicalPartitionException.MinimumIntervalTooLarge::class.java)
        assertThat(canonicalizations.get()).isEqualTo(2)
        assertThat(requests.get()).isEqualTo(2)
    }

    @Test
    fun oversizedDayRecursivelySplitsUntilEveryRequestFitsAndRowsArePreserved() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val through = from + DAY_MS - 1L
        val rows = (0 until 8).map { from + it * 3L * HOUR_MS }
        val dataset = dataset(zone.id, from, through, rows)
        val planner = planner(requestBudgetBytes = 250) { leafDataset, identity, _ ->
            sizedRequest(
                input = "leaf:${identity.id}",
                bytes = maxOf(1, leafDataset.glucose30d.size * 100)
            )
        }

        val plan = planner.planForTest(dataset)

        assertThat(plan.leaves).hasSize(4)
        assertThat(plan.leaves.map { it.descriptor.requestBytes })
            .containsExactly(200, 200, 200, 200)
        assertThat(plan.leaves.map { it.descriptor.depth }).containsExactly(2, 2, 2, 2)
        assertThat(plan.coverage.sourceRows).isEqualTo(semanticRows(dataset))
        assertThat(plan.leaves.fold(ClinicalSeriesRowCounts()) { total, leaf ->
            total + leaf.descriptor.sourceRows
        }).isEqualTo(semanticRows(dataset))
    }

    @Test
    fun exactFitStaysOneLeafForOneDayDataset() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val through = from + DAY_MS - 1L
        val dataset = dataset(zone.id, from, through, listOf(from + HOUR_MS))

        val plan = planner(requestBudgetBytes = 100) { _, _, _ ->
            sizedRequest("exact-fit", 100)
        }.planForTest(dataset)

        assertThat(plan.leaves).hasSize(1)
        assertThat(plan.leaves.single().descriptor.depth).isEqualTo(0)
        assertThat(plan.leaves.single().descriptor.requestBytes).isEqualTo(100)
    }

    @Test
    fun singleMinimumIntervalOversizedThrowsMinimumIntervalTooLarge() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val through = from + MINIMUM_INTERVAL_MS - 1L
        val dataset = dataset(zone.id, from, through, listOf(from))

        val failure = runCatching {
            planner(requestBudgetBytes = 100) { _, _, _ ->
                sizedRequest("oversized", 101)
            }.planForTest(dataset)
        }.exceptionOrNull()

        assertThat(failure)
            .isInstanceOf(ClinicalPartitionException.MinimumIntervalTooLarge::class.java)
        assertThat(failure).hasMessageThat()
            .isEqualTo("Clinical report minimum interval exceeds request budget")
    }

    @Test
    fun berlinDstTransitionsProduceContiguousMultiDayCalendarIntervals() = runTest {
        val zone = ZoneId.of("Europe/Berlin")
        val cases = listOf(
            Triple(local(zone, 2026, 3, 28), listOf(24L, 23L, 24L), "spring"),
            Triple(local(zone, 2026, 10, 24), listOf(24L, 25L, 24L), "fall")
        )

        cases.forEach { (from, expectedHours, label) ->
            val through = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(from),
                zone
            ).plusDays(3L).toInstant().toEpochMilli() - 1L
            val plan = planner(requestBudgetBytes = 1) { _, _, _ ->
                sizedRequest(label, 1)
            }.planForTest(
                dataset(
                    zone.id,
                    from,
                    through,
                    expectedHours.indices.map { day ->
                        ZonedDateTime.ofInstant(
                            java.time.Instant.ofEpochMilli(from),
                            zone
                        ).plusDays(day.toLong()).toInstant().toEpochMilli()
                    }
                ).let { source ->
                    source.copy(
                        detail24h = source.detail24h.copy(
                            glucose = emptyList(),
                            calibratedGlucose = emptyList(),
                            therapy = emptyList(),
                            targets = emptyList(),
                            forecasts = emptyList(),
                            telemetry = emptyList()
                        )
                    )
                }
            )

            assertThat(plan.leaves).hasSize(3)
            assertThat(plan.leaves.map {
                (it.descriptor.throughTsExclusive - it.descriptor.fromTs) / HOUR_MS
            }).containsExactlyElementsIn(expectedHours).inOrder()
            assertContiguous(plan)
        }
    }

    @Test
    fun splitBoundaryRowsBelongOnlyToRightLeafAcrossEverySeries() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val through = from + DAY_MS - 1L
        val split = from + 12L * HOUR_MS
        val dataset = dataset(zone.id, from, through, listOf(split))
        val plan = planner(requestBudgetBytes = 100) { _, identity, _ ->
            sizedRequest(
                input = "depth:${identity.depth}",
                bytes = if (identity.depth == 0) 101 else 100
            )
        }.planForTest(dataset)

        assertThat(plan.leaves).hasSize(2)
        val left = plan.leaves[0]
        val right = plan.leaves[1]
        assertThat(left.descriptor.throughTsExclusive).isEqualTo(split)
        assertThat(right.descriptor.fromTs).isEqualTo(split)
        WIRE_ROW_PATHS.forEach { path ->
            assertThat(canonicalRows(left.canonicalJson, path)).isEmpty()
            assertThat(canonicalRows(right.canonicalJson, path)).hasSize(1)
        }
    }

    @Test
    fun invalidZoneOverflowMalformedDatasetAndSchemaFailWithSanitizedTypes() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val through = from + HOUR_MS
        val valid = dataset(zone.id, from, through, listOf(from))
        val malformed = valid.copy(
            summary30d = valid.summary30d.copy(
                fromTs = valid.summary30d.throughTs + 1L
            )
        )
        val planner = planner()

        val invalidZone = runCatching {
            planner.plan(valid.copy(zoneId = "Not/A_Zone"), "{}")
        }.exceptionOrNull()
        val invalidSchema = runCatching {
            planner.plan(valid.copy(schemaVersion = -1), "{}")
        }.exceptionOrNull()
        val overflow = runCatching {
            planner.plan(
                valid.copy(summary30d = valid.summary30d.copy(throughTs = Long.MAX_VALUE)),
                "{}"
            )
        }.exceptionOrNull()
        val malformedFailure = runCatching {
            planner.plan(malformed, "{}")
        }.exceptionOrNull()

        assertThat(invalidZone).isInstanceOf(ClinicalOpenAiException.InvalidInput::class.java)
        assertThat(invalidSchema).isInstanceOf(ClinicalOpenAiException.InvalidInput::class.java)
        assertThat(overflow)
            .isInstanceOf(ClinicalOpenAiException.DatasetTooLarge::class.java)
        assertThat(malformedFailure)
            .isInstanceOf(ClinicalPartitionException.CoverageMismatch::class.java)
        assertThat(overflow).hasMessageThat().doesNotContain("overflow")
        assertThat(malformedFailure).hasMessageThat().doesNotContain("fromTs")
    }

    @Test
    fun invalidFactoryResultsFailClosed() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val dataset = dataset(zone.id, from, from + HOUR_MS, listOf(from))
        val invalidResults = listOf(ClinicalSizedLeafRequest(0), ClinicalSizedLeafRequest(-1))

        invalidResults.forEach { invalid ->
            val failure = runCatching {
                planner(requestFactory = { _, _, _ -> invalid }).planForTest(dataset)
            }.exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(ClinicalPartitionException.CoverageMismatch::class.java)
        }
    }

    @Test
    fun cancellationDuringRowScanIsNotConvertedOrIgnored() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val through = from + HOUR_MS
        lateinit var operation: Deferred<ClinicalPartitionPlan>
        val lastReadIndex = AtomicInteger(-1)
        val factoryCalls = AtomicInteger()
        val cancellingRows = object : AbstractList<ClinicalGlucosePoint>() {
            override val size: Int = 600

            override fun get(index: Int): ClinicalGlucosePoint {
                lastReadIndex.accumulateAndGet(index) { current, next -> maxOf(current, next) }
                if (index == 300) operation.cancel(CancellationException("test cancellation"))
                return ClinicalGlucosePoint(from, 6.0)
            }
        }
        val source = dataset(zone.id, from, through, listOf(from))
            .copy(glucose30d = cancellingRows)
        operation = async(start = CoroutineStart.LAZY) {
            planner(requestFactory = { _, _, _ ->
                factoryCalls.incrementAndGet()
                sizedRequest("unexpected", 1)
            }).planForTest(source)
        }

        operation.start()
        val failure = runCatching { operation.await() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(lastReadIndex.get()).isLessThan(cancellingRows.lastIndex)
        assertThat(factoryCalls.get()).isEqualTo(0)
    }

    @Test
    fun identicalInputsProduceStableLeafIdsHashesAndLedgerHash() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val through = from + DAY_MS - 1L
        val dataset = dataset(
            zone.id,
            from,
            through,
            listOf(from + HOUR_MS, from + 13L * HOUR_MS)
        )
        val planner = planner(requestBudgetBytes = 100) { _, identity, hash ->
            sizedRequest(
                input = "${identity.fromTs}:${identity.throughTsExclusive}:$hash",
                bytes = if (identity.depth == 0) 101 else 100
            )
        }

        val first = planner.planForTest(dataset)
        val second = planner.planForTest(dataset)

        assertThat(first.leaves.map { it.descriptor.id })
            .containsExactlyElementsIn(second.leaves.map { it.descriptor.id }).inOrder()
        assertThat(first.leaves.map { it.descriptor.canonicalHash })
            .containsExactlyElementsIn(second.leaves.map { it.descriptor.canonicalHash }).inOrder()
        assertThat(first.leaves.map { it.descriptor.id })
            .containsNoDuplicates()
        first.leaves.forEach {
            assertThat(it.descriptor.id).matches("[0-9a-f]{64}")
            assertThat(it.descriptor.canonicalHash).matches("[0-9a-f]{64}")
        }
        assertThat(first.coverage.ledgerHash).isEqualTo(second.coverage.ledgerHash)
    }

    @Test
    fun fortyDayPreparedWindowFailsClosedAsDatasetTooLarge() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 1, 1)
        val through = from + 40L * DAY_MS - 1L
        val timestamps = (0 until 40).map { from + it * DAY_MS }

        val failure = runCatching {
            planner(requestBudgetBytes = 1) { _, _, _ ->
                sizedRequest("fits", 1)
            }.planForTest(dataset(zone.id, from, through, timestamps))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ClinicalOpenAiException.DatasetTooLarge::class.java)
    }

    @Test
    fun wireVerificationRejectsMissingForecastAndDuplicatedTelemetryRows() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val source = dataset(zone.id, from, from + HOUR_MS, listOf(from))
        val fullCanonical = ClinicalReportDatasetBuilder.serialize(source)
        val mutations = listOf<(com.google.gson.JsonObject) -> Unit>(
            { root ->
                root.getAsJsonObject("d24").getAsJsonArray("f").remove(0)
            },
            { root ->
                val telemetry = root.getAsJsonObject("d24").getAsJsonArray("m")
                telemetry.add(telemetry[0].deepCopy())
            }
        )

        mutations.forEach { mutate ->
            val tampered = JsonParser.parseString(fullCanonical).asJsonObject
            mutate(tampered)
            val failure = runCatching {
                verifyRowCoverageForTest(
                    fullCanonical,
                    listOf(tampered.toString())
                )
            }.exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(ClinicalPartitionException.CoverageMismatch::class.java)
        }
    }

    @Test
    fun plannerRejectsCanonicalizerChangesToEveryAuthenticatedNonRowBranch() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val through = from + HOUR_MS
        val base = dataset(zone.id, from, through, listOf(from)).let { source ->
            source.copy(
                currentSnapshot = ClinicalCurrentSnapshot(rawGlucoseMmol = 6.0),
                detail24h = source.detail24h.copy(
                    forecastQuality = listOf(ClinicalForecastQuality(30, 4, 0.5, 75.0))
                ),
                summary24h = source.summary7d.copy(days = 1),
                energyProfile = ClinicalEnergyProfileSummary(
                    evidenceDays = 10,
                    shareProfileWithAi = true
                )
            )
        }
        val mutations = listOf<(com.google.gson.JsonObject) -> Unit>(
            { root -> root.addProperty("generatedAt", root.get("generatedAt").asLong + 1L) },
            { root -> root.addProperty("zone", "Etc/GMT") },
            { root -> root.getAsJsonObject("c").addProperty("g", 6.1) },
            {
                root -> root.getAsJsonObject("d24").getAsJsonArray("fq")
                    .get(0).asJsonObject.addProperty("samples", 5)
            },
            { root -> root.getAsJsonObject("s24").addProperty("mean", 6.1) },
            { root -> root.getAsJsonObject("s7").addProperty("median", 6.1) },
            { root -> root.getAsJsonObject("s30").addProperty("coverage", 99.0) },
            { root -> root.getAsJsonObject("ep").addProperty("evidenceDays", 11) }
        )

        mutations.forEach { mutate ->
            val failure = runCatching {
                planner(canonicalizer = { leaf, bounds ->
                    JsonParser.parseString(
                        ClinicalReportDatasetBuilder.serializePartitionCancellable(leaf, bounds)
                    ).asJsonObject.apply(mutate).toString()
                }).planForTest(base)
            }.exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(ClinicalPartitionException.CoverageMismatch::class.java)
        }
    }

    @Test
    fun plannerSnapshotDetachesForecastAndSummaryListsBeforeCanonicalization() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val through = from + HOUR_MS
        val forecastQuality = mutableListOf(ClinicalForecastQuality(30, 4, 0.5, 75.0))
        val weekday = mutableListOf(ClinicalHourlyMetric(12, 4, 6.0, 6.0))
        val source = dataset(zone.id, from, through, listOf(from)).let { base ->
            base.copy(
                detail24h = base.detail24h.copy(forecastQuality = forecastQuality),
                summary24h = base.summary7d.copy(days = 1, weekdayPattern = weekday),
                summary7d = base.summary7d.copy(weekdayPattern = weekday),
                summary30d = base.summary30d.copy(weekdayPattern = weekday)
            )
        }
        val rootCanonical = ClinicalReportDatasetBuilder.serialize(source)
        var mutated = false
        val plan = planner(canonicalizer = { leaf, bounds ->
            if (!mutated) {
                forecastQuality.clear()
                weekday.clear()
                mutated = true
            }
            ClinicalReportDatasetBuilder.serializePartitionCancellable(leaf, bounds)
        }).plan(source, rootCanonical)

        val root = JsonParser.parseString(rootCanonical).asJsonObject
        plan.leaves.forEach { leaf ->
            val canonical = JsonParser.parseString(leaf.canonicalJson).asJsonObject
            assertThat(canonical.getAsJsonObject("d24").get("fq"))
                .isEqualTo(root.getAsJsonObject("d24").get("fq"))
            listOf("s24", "s7", "s30").forEach { key ->
                assertThat(canonical.get(key)).isEqualTo(root.get(key))
            }
        }
    }

    @Test
    fun eventBeginningAtEpochCannotExpandPlanningBeyondPreparedReportUnion() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val through = from + HOUR_MS
        val source = dataset(zone.id, from, through, listOf(from)).copy(
            eventSummaries = listOf(
                ClinicalEventSummary(
                    localId = "long-overlap",
                    type = "CUSTOM",
                    subtype = "",
                    startTs = 0L,
                    endTs = through,
                    severity = "LOW",
                    source = "USER",
                    title = "long overlap",
                    note = null,
                    status = "CLOSED",
                    provenance = "test"
                )
            )
        )
        val factoryCalls = AtomicInteger()

        val plan = planner(requestFactory = { _, _, _ ->
            if (factoryCalls.incrementAndGet() > 4) {
                throw AssertionError("event expanded partition calendar")
            }
            sizedRequest("fits", 1)
        }).planForTest(source)

        assertThat(plan.coverage.expectedFromTs).isEqualTo(from)
        assertThat(plan.coverage.expectedThroughTsExclusive).isEqualTo(through + 1L)
        assertThat(plan.leaves).hasSize(1)
        listOf("ev24", "ev7", "ev30").forEach { key ->
            assertThat(eventTitles(plan.leaves.single().canonicalJson, key))
                .containsExactly("long overlap")
        }
    }

    @Test
    fun sizeOnlyRequestFactoryDrivesRecursiveBudgeting() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val source = dataset(zone.id, from, from + HOUR_MS, listOf(from))
        val splitPlan = planner(
            requestBudgetBytes = 3,
            minimumIntervalMillis = 1L
        ) { _, identity, _ ->
            ClinicalSizedLeafRequest(if (identity.depth == 0) 4 else 3)
        }.planForTest(source)
        val escapedPlan = planner(requestBudgetBytes = 4) { _, _, _ ->
            ClinicalSizedLeafRequest(4)
        }.planForTest(source)

        assertThat(splitPlan.leaves).hasSize(2)
        splitPlan.leaves.forEach { leaf ->
            assertThat(leaf.descriptor.requestBytes).isEqualTo(3)
        }
        assertThat(escapedPlan.leaves.single().descriptor.requestBytes).isEqualTo(4)
    }

    @Test
    fun detachedEventCopiesPreserveCanonicalIdentityWithoutEscapingDatasets() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val base = dataset(zone.id, from, from + HOUR_MS, listOf(from))
        val therapy = listOf(
            ClinicalTherapyPoint(from, insulinU = 1.0, carbsG = null)
                .also { it.internalEventId = "shared-therapy-id" },
            ClinicalTherapyPoint(from, insulinU = 2.0, carbsG = 15.0)
                .also { it.internalEventId = "shared-therapy-id" }
        )
        val targets = listOf(
            ClinicalTargetPoint(from, 5.0, 6.0)
                .also { it.internalEventId = "shared-target-id" },
            ClinicalTargetPoint(from, 6.0, 7.0)
                .also { it.internalEventId = "shared-target-id" }
        )
        val source = base.copy(
            detail24h = base.detail24h.copy(
                therapy = therapy,
                targets = targets
            ),
            therapy7d = therapy,
            targets7d = targets,
            therapy30d = therapy,
            targets30d = targets,
            summary7d = base.summary7d,
            summary30d = base.summary30d
        )
        val originalCanonical = ClinicalReportDatasetBuilder.serialize(source)
        val canonicalDatasets = mutableListOf<ClinicalReportDataset>()
        val requestDatasets = mutableListOf<ClinicalReportDataset>()
        val plan = planner(
            requestBudgetBytes = 1_000_000,
            requestFactory = { leafDataset, _, _ ->
                requestDatasets += leafDataset
                ClinicalSizedLeafRequest(
                    ClinicalReportDatasetBuilder.serialize(leafDataset)
                        .toByteArray(Charsets.UTF_8).size
                )
            },
            canonicalizer = { leafDataset, eventWindowBounds ->
                canonicalDatasets += leafDataset
                ClinicalReportDatasetBuilder.serializePartitionCancellable(
                    leafDataset,
                    eventWindowBounds
                )
            }
        ).planForTest(source)
        val leaf = plan.leaves.single()
        val returnedCanonical = leaf.canonicalJson
        val originalHash = leaf.descriptor.canonicalHash
        val originalBytes = leaf.descriptor.requestBytes
        val originalLedger = plan.coverage.ledgerHash

        assertThat(canonicalDatasets).hasSize(1)
        assertThat(canonicalDatasets.first()).isNotSameInstanceAs(source)
        assertThat(ClinicalReportDatasetBuilder.serialize(source)).isEqualTo(originalCanonical)
        WIRE_ROW_PATHS.forEach { path ->
            assertThat(plan.leaves.flatMap { canonicalRows(it.canonicalJson, path) })
                .containsExactlyElementsIn(canonicalRows(originalCanonical, path))
        }
        listOf("d24/e", "e7", "e30", "d24/t", "t7", "t30").forEach { path ->
            assertThat(canonicalRows(originalCanonical, path)).hasSize(1)
        }
        val capturedDatasets = canonicalDatasets + requestDatasets
        capturedDatasets.forEach { captured ->
            captured.detail24h.therapy.forEach {
                assertThat(it.internalEventId).isEqualTo("shared-therapy-id")
            }
            captured.detail24h.targets.forEach {
                assertThat(it.internalEventId).isEqualTo("shared-target-id")
            }
        }
        capturedDatasets.forEach { captured ->
            captured.detail24h.therapy.forEach {
                it.internalEventId = "mutated-after-plan"
            }
            captured.detail24h.targets.forEach {
                it.internalEventId = "mutated-after-plan"
            }
            captured.therapy7d.forEach { it.internalEventId = null }
            captured.therapy30d.forEach { it.internalEventId = null }
            captured.targets7d.forEach { it.internalEventId = null }
            captured.targets30d.forEach { it.internalEventId = null }
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (plan.leaves as MutableList<ClinicalPartitionLeaf>).clear()
        }
        assertThat(ClinicalPartitionLeaf::class.java.declaredMethods.map { it.name })
            .doesNotContain("copy")
        assertThat(ClinicalPartitionPlan::class.java.declaredMethods.map { it.name })
            .doesNotContain("copy")
        assertThat(ClinicalPartitionLeaf::class.java.declaredFields.map { it.name })
            .doesNotContain("dataset")
        assertThat(leaf.canonicalJson).isEqualTo(returnedCanonical)
        assertThat(leaf.descriptor.canonicalHash).isEqualTo(originalHash)
        assertThat(leaf.descriptor.requestBytes).isEqualTo(originalBytes)
        assertThat(plan.coverage.ledgerHash).isEqualTo(originalLedger)
    }

    @Test
    fun forcedPartitionAssignsPlannedActivitiesOnceByHalfOpenStartInCanonicalLeaves() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val through = from + HOUR_MS - 1L
        val split = from + HOUR_MS / 2L
        val activities = listOf(
            plannedActivity("AEROBIC", split + 5L * 60_000L),
            plannedActivity("WALKING", split),
            plannedActivity("STRENGTH", split - 1L),
            plannedActivity("MIXED", split),
            plannedActivity("AEROBIC", from + 5L * 60_000L)
        )
        val base = dataset(zone.id, from, through, listOf(from))
        val source = base.copy(
            energyProfile = ClinicalEnergyProfileSummary(shareProfileWithAi = true),
            plannedActivities = activities
        )
        val expectedRows = canonicalRows(
            ClinicalReportDatasetBuilder.serialize(source),
            "pa"
        )
        val subject = planner(
            requestBudgetBytes = 1,
            minimumIntervalMillis = 1L,
            requestFactory = { _, identity, _ ->
                sizedRequest(
                    input = "leaf:${identity.id}",
                    bytes = if (identity.depth == 0) 2 else 1
                )
            }
        )

        val first = subject.planForTest(source)
        val permuted = subject.planForTest(source.copy(plannedActivities = activities.reversed()))

        assertThat(first.leaves).hasSize(2)
        assertThat(first.leaves.first().descriptor.throughTsExclusive).isEqualTo(split)
        assertThat(plannedActivityTypes(first.leaves.first().canonicalJson))
            .containsExactly("AEROBIC", "STRENGTH").inOrder()
        assertThat(plannedActivityTypes(first.leaves.last().canonicalJson))
            .containsExactly("MIXED", "WALKING", "AEROBIC").inOrder()
        assertThat(first.leaves.flatMap { canonicalRows(it.canonicalJson, "pa") })
            .containsExactlyElementsIn(expectedRows).inOrder()
        assertThat(permuted.leaves.map(ClinicalPartitionLeaf::canonicalJson))
            .containsExactlyElementsIn(first.leaves.map(ClinicalPartitionLeaf::canonicalJson))
            .inOrder()
    }

    @Test
    fun forcedPartitionClampsCarryInOwnershipToFirstLeafWithoutLossOrDuplication() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val through = from + HOUR_MS - 1L
        val split = from + HOUR_MS / 2L
        val activities = listOf(
            plannedActivity("STRENGTH", from - 30L * 60_000L, durationMinutes = 60),
            plannedActivity("AEROBIC", split - 1L),
            plannedActivity("WALKING", split),
            plannedActivity("MIXED", through)
        )
        val base = dataset(zone.id, from, through, listOf(from))
        val source = base.copy(
            energyProfile = ClinicalEnergyProfileSummary(shareProfileWithAi = true),
            plannedActivities = activities.reversed()
        )
        val expectedRows = canonicalRows(
            ClinicalReportDatasetBuilder.serialize(source),
            "pa"
        )
        val subject = planner(
            requestBudgetBytes = 1,
            minimumIntervalMillis = 1L,
            requestFactory = { _, identity, _ ->
                sizedRequest(
                    input = "leaf:${identity.id}",
                    bytes = if (identity.depth == 0) 2 else 1
                )
            }
        )

        val plan = subject.planForTest(source)

        assertThat(plan.leaves).hasSize(2)
        assertThat(plan.leaves.first().descriptor.throughTsExclusive).isEqualTo(split)
        assertThat(plannedActivityTypes(plan.leaves.first().canonicalJson))
            .containsExactly("STRENGTH", "AEROBIC").inOrder()
        assertThat(plannedActivityTypes(plan.leaves.last().canonicalJson))
            .containsExactly("WALKING", "MIXED").inOrder()
        assertThat(plan.leaves.flatMap { canonicalRows(it.canonicalJson, "pa") })
            .containsExactlyElementsIn(expectedRows).inOrder()
    }

    @Test
    fun wireVerificationRejectsPlannedActivityOmissionAndDuplication() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val base = dataset(zone.id, from, from + HOUR_MS - 1L, listOf(from))
        val source = base.copy(
            energyProfile = ClinicalEnergyProfileSummary(shareProfileWithAi = true),
            plannedActivities = listOf(plannedActivity("AEROBIC", from + 60_000L))
        )
        val fullCanonical = ClinicalReportDatasetBuilder.serialize(source)
        val omitted = JsonParser.parseString(fullCanonical).asJsonObject.apply {
            getAsJsonArray("pa").remove(0)
        }.toString()
        val duplicated = JsonParser.parseString(fullCanonical).asJsonObject.apply {
            val rows = getAsJsonArray("pa")
            rows.add(rows[0].deepCopy())
        }.toString()

        listOf(omitted, duplicated).forEach { tampered ->
            val failure = runCatching {
                verifyRowCoverageForTest(fullCanonical, listOf(tampered))
            }.exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(ClinicalPartitionException.CoverageMismatch::class.java)
        }
    }

    @Test
    fun forcedPartitionPreservesExactCanonicalTherapyUnion() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val through = from + HOUR_MS
        val therapy = listOf(
            ClinicalTherapyPoint(
                ts = from + HOUR_MS / 12L,
                insulinU = 1.0,
                insulinEvidence = ClinicalInsulinEvidence.CONFIRMED
            ).also { it.internalEventId = "confirmed-insulin" },
            ClinicalTherapyPoint(
                ts = from + HOUR_MS / 2L,
                insulinU = 2.0,
                insulinEvidence = ClinicalInsulinEvidence.IOB_DERIVED
            ).also { it.internalEventId = "iob-derived-insulin" },
            ClinicalTherapyPoint(
                ts = from + 11L * HOUR_MS / 12L,
                contextKind = ClinicalTherapyContextKind.PROFILE_SWITCH
            ).also { it.internalEventId = "context-only" }
        )
        val base = dataset(zone.id, from, through, listOf(from))
        val source = base.copy(
            detail24h = base.detail24h.copy(therapy = therapy),
            therapy7d = therapy,
            therapy30d = therapy
        )
        val originalCanonical = ClinicalReportDatasetBuilder.serialize(source)

        val plan = planner(
            requestBudgetBytes = 1,
            minimumIntervalMillis = 1L,
            requestFactory = { _, identity, _ ->
                sizedRequest(
                    input = "leaf:${identity.id}",
                    bytes = if (identity.depth == 0) 2 else 1
                )
            }
        ).planForTest(source)

        assertThat(plan.leaves).hasSize(2)
        listOf("d24/e", "e7", "e30").forEach { path ->
            assertThat(plan.leaves.flatMap { canonicalRows(it.canonicalJson, path) })
                .containsExactlyElementsIn(canonicalRows(originalCanonical, path))
                .inOrder()
        }
    }

    @Test
    fun forcedPartitionRecomputesLeafCurrentWithoutFullSnapshotMetadata() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val through = from + HOUR_MS
        val source = dataset(zone.id, from, through, listOf(from)).copy(
            currentSnapshot = ClinicalCurrentSnapshot(
                selectedIsfMmolPerUnit = 2.0,
                selectedIsfSourceCode = 1.0,
                selectedCrGramsPerUnit = 10.0,
                selectedCrSourceCode = 3.0,
                sensitivitySettingsRevision = 42L,
                selectedIsfSource = "AAPS",
                selectedCrSource = "COPILOT_NATIVE"
            )
        )

        val plan = planner(
            requestBudgetBytes = 1,
            minimumIntervalMillis = 1L,
            requestFactory = { _, identity, _ ->
                sizedRequest(
                    input = "leaf:${identity.id}",
                    bytes = if (identity.depth == 0) 2 else 1
                )
            }
        ).planForTest(source)
        assertThat(plan.leaves).hasSize(2)
        plan.leaves.forEach { leaf ->
            val current = JsonParser.parseString(leaf.canonicalJson)
                .asJsonObject
                .getAsJsonObject("c")

            assertThat(ClinicalCurrentWireSchema.isValidWireObject(current)).isTrue()
            assertThat(current.get("isfSrc").isJsonNull).isTrue()
            assertThat(current.get("crSrc").isJsonNull).isTrue()
            assertThat(current.has("sensitivityRev")).isFalse()
            assertThat(current.has("isfProvenance")).isFalse()
            assertThat(current.has("crProvenance")).isFalse()
        }
    }

    @Test
    fun currentSchemaRejectsUnknownBoundedProvenanceWithoutSourceCodes() {
        val unknownIsf = ClinicalCurrentSnapshot(selectedIsfSource = "UNKNOWN_ISF")
        val unknownCr = ClinicalCurrentSnapshot(selectedCrSource = "UNKNOWN_CR")
        val validWire = ClinicalCurrentWireSchema.encode(ClinicalCurrentSnapshot())
        val unknownIsfWire = validWire.deepCopy().apply {
            addProperty("isfProvenance", "UNKNOWN_ISF")
        }
        val unknownCrWire = validWire.deepCopy().apply {
            addProperty("crProvenance", "UNKNOWN_CR")
        }

        assertThat(ClinicalCurrentWireSchema.isValid(unknownIsf)).isFalse()
        assertThat(ClinicalCurrentWireSchema.isValid(unknownCr)).isFalse()
        assertThat(ClinicalCurrentWireSchema.isValidWireObject(unknownIsfWire)).isFalse()
        assertThat(ClinicalCurrentWireSchema.isValidWireObject(unknownCrWire)).isFalse()
    }

    @Test
    fun currentWireSchemaRejectsUnknownMissingWrongTypeAndOutOfBoundsFields() {
        val snapshot = ClinicalCurrentSnapshot(
            rawGlucoseMmol = 6.0,
            selectedIsfMmolPerUnit = 2.0,
            selectedIsfSourceCode = 1.0,
            selectedCrGramsPerUnit = 10.0,
            selectedCrSourceCode = 3.0,
            sensitivitySettingsRevision = 42L,
            selectedIsfSource = "AAPS",
            selectedCrSource = "COPILOT_NATIVE"
        )
        val valid = ClinicalCurrentWireSchema.encode(snapshot)
        val invalid = listOf(
            valid.deepCopy().apply { addProperty("unknown", 1) },
            valid.deepCopy().apply { remove("g") },
            valid.deepCopy().apply { addProperty("g", "6.0") },
            valid.deepCopy().apply { addProperty("g", 40.001) },
            valid.deepCopy().apply { addProperty("sensitivityRev", "42") },
            valid.deepCopy().apply { addProperty("isfProvenance", 1) },
            valid.deepCopy().apply { addProperty("isfProvenance", "COPILOT_NATIVE") }
        )

        assertThat(ClinicalCurrentWireSchema.isValidWireObject(valid)).isTrue()
        invalid.forEach { current ->
            assertThat(ClinicalCurrentWireSchema.isValidWireObject(current)).isFalse()
        }
    }

    @Test
    fun requestFactoryProgrammingDefectPropagatesUnchanged() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val defect = IllegalStateException("factory defect")
        val failure = runCatching {
            planner(requestFactory = { _, _, _ -> throw defect })
                .planForTest(dataset(zone.id, from, from + HOUR_MS, listOf(from)))
        }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(defect)
    }

    @Test
    fun canonicalizerProgrammingDefectPropagatesUnchanged() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 20)
        val defect = IllegalStateException("canonicalizer defect")
        val failure = runCatching {
            planner(canonicalizer = { _, _ -> throw defect })
                .planForTest(dataset(zone.id, from, from + HOUR_MS, listOf(from)))
        }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(defect)
    }

    @Test
    fun suppliedRootIsNotCanonicalizedAndStillAuthenticatesLeafCoverage() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 7, 16)
        val through = from + 4L * DAY_MS - 1L
        val source = dataset(
            zone.id,
            from,
            through,
            (0 until 4).map { day -> from + day * DAY_MS }
        )
        val rootCanonical = ClinicalReportDatasetBuilder.serialize(source)
        val canonicalizerCalls = AtomicInteger()
        val subject = planner(
            requestBudgetBytes = 1_000_000,
            canonicalizer = { leaf, bounds ->
                canonicalizerCalls.incrementAndGet()
                ClinicalReportDatasetBuilder.serializePartitionCancellable(leaf, bounds)
            }
        )

        val plan = subject.plan(source, rootCanonical)

        assertThat(canonicalizerCalls.get()).isEqualTo(plan.leaves.size)
        assertThat(plan.leaves).isNotEmpty()

        val mutatedRoot = JsonParser.parseString(rootCanonical).asJsonObject.apply {
            getAsJsonArray("g30").remove(0)
        }.toString()
        val mismatch = runCatching {
            subject.plan(source, mutatedRoot)
        }.exceptionOrNull()

        assertThat(mismatch)
            .isInstanceOf(ClinicalPartitionException.CoverageMismatch::class.java)
    }

    @Test
    fun denseThirtyDayDatasetRecursivelyProducesExactVerifiedLeaves() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 1, 1)
        val through = from + 30L * DAY_MS - 1L
        val timestamps = (0 until 30 * 24).map { from + it * HOUR_MS }
        val plan = planner(
            requestBudgetBytes = 100,
            requestFactory = { _, identity, _ ->
                sizedRequest(
                    input = "depth:${identity.depth}",
                    bytes = if (identity.depth == 0) 101 else 100
                )
            }
        ).planForTest(dataset(zone.id, from, through, timestamps))

        assertThat(plan.leaves).hasSize(60)
        assertThat(plan.coverage.leafCount).isEqualTo(60)
        assertThat(plan.leaves.fold(ClinicalSeriesRowCounts()) { total, item ->
            total + item.descriptor.sourceRows
        }).isEqualTo(plan.coverage.sourceRows)
        assertThat(plan.leaves.map { it.descriptor.requestBytes }.distinct())
            .containsExactly(100)
        assertContiguous(plan)
    }

    @Test
    fun completeEventSelectionIsCanonicalBeforeDayPartitioning() = runTest {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 1, 1)
        val through = from + 30L * DAY_MS - 1L
        val spanning = ClinicalEventSummary(
            localId = "spanning-boundary",
            type = "CUSTOM",
            subtype = "",
            startTs = from + DAY_MS - 60_000L,
            endTs = from + DAY_MS + 60_000L,
            severity = "LOW",
            source = "USER",
            title = "spanning-boundary",
            note = null,
            status = "CLOSED",
            provenance = "test"
        )
        val events = (0 until 520).map { index ->
            spanning.copy(
                localId = "event-$index",
                startTs = from + (index % 29) * DAY_MS + index * 1_000L,
                endTs = from + (index % 29) * DAY_MS + index * 1_000L + 60_000L,
                title = "event-$index"
            )
        } + spanning
        val source = dataset(zone.id, from, through, listOf(from)).copy(eventSummaries = events)
        val plan = planner(requestBudgetBytes = 1_000_000).planForTest(source)
        val fullCanonical = ClinicalReportDatasetBuilder.serialize(source)
        listOf("ev24", "ev7", "ev30").forEach { key ->
            val fullTitles = eventTitles(fullCanonical, key)
            val leafTitles = plan.leaves.flatMap { eventTitles(it.canonicalJson, key) }

            assertThat(fullTitles).hasSize(521)
            assertThat(leafTitles).containsExactlyElementsIn(fullTitles)
            assertThat(leafTitles.count { it == "spanning-boundary" }).isEqualTo(1)
        }
    }

    @Test
    fun forcedPartitionPreservesDistinctPreparedEventWindowsWithoutPromotion() = runTest {
        val zone = ZoneId.of("UTC")
        val from30d = local(zone, 2026, 1, 1)
        val from7d = from30d + 23L * DAY_MS
        val from24h = from30d + 29L * DAY_MS
        val through = from30d + 30L * DAY_MS - 1L

        fun event(
            id: String,
            startTs: Long,
            endTs: Long = startTs
        ) = ClinicalEventSummary(
            localId = id,
            type = "CUSTOM",
            subtype = "PARTITION",
            startTs = startTs,
            endTs = endTs,
            severity = "LOW",
            source = "USER",
            title = id,
            note = null,
            status = "CLOSED",
            provenance = "test"
        )

        val events = listOf(
            event("30d-only", from7d - 2L * DAY_MS),
            event("exact-7d-boundary", from7d - DAY_MS, from7d),
            event("7d-only", from24h - DAY_MS),
            event("24h", from24h + HOUR_MS),
            event("spanning-split", from24h + 6L * HOUR_MS, from24h + 18L * HOUR_MS)
        )
        val base = dataset(
            zone.id,
            from30d,
            through,
            listOf(from24h + HOUR_MS)
        )
        val source = base.copy(
            detail24h = base.detail24h.copy(fromTs = from24h, throughTs = through),
            summary24h = base.summary24h?.copy(fromTs = from24h, throughTs = through),
            summary7d = base.summary7d.copy(fromTs = from7d, throughTs = through),
            summary30d = base.summary30d.copy(fromTs = from30d, throughTs = through),
            eventSummaries = events
        )
        val originalCanonical = ClinicalReportDatasetBuilder.serialize(source)
        val canonicalizerBounds = mutableListOf<ClinicalPartitionEventWindowBounds>()
        val plan = planner(
            requestBudgetBytes = 1,
            minimumIntervalMillis = 1L,
            requestFactory = { _, identity, canonicalJson ->
                sizedRequest(
                    input = "leaf:${identity.id}",
                    bytes = if (identity.depth == 0) 2 else 1
                )
            },
            canonicalizer = { leafDataset, eventWindowBounds ->
                canonicalizerBounds += eventWindowBounds
                ClinicalReportDatasetBuilder.serializePartitionCancellable(
                    leafDataset,
                    eventWindowBounds
                )
            }
        ).planForTest(source)

        assertThat(canonicalizerBounds).isNotEmpty()
        assertThat(canonicalizerBounds.all { it === canonicalizerBounds.first() }).isTrue()

        val expectedByWindow = mapOf(
            "ev24" to listOf("24h", "spanning-split"),
            "ev7" to listOf(
                "exact-7d-boundary",
                "7d-only",
                "24h",
                "spanning-split"
            ),
            "ev30" to events.map(ClinicalEventSummary::title)
        )
        expectedByWindow.forEach { (key, expected) ->
            val originalTitles = eventTitles(originalCanonical, key)
            val originalRows = canonicalRows(originalCanonical, key)
            val leafRows = plan.leaves.flatMap { canonicalRows(it.canonicalJson, key) }

            assertThat(originalTitles).containsExactlyElementsIn(expected).inOrder()
            assertThat(leafRows).containsExactlyElementsIn(originalRows).inOrder()
        }
        val leafEventTitles = plan.leaves.map { leaf ->
            listOf("ev24", "ev7", "ev30")
                .flatMap { key -> eventTitles(leaf.canonicalJson, key) }
                .toSet()
        }
        events.forEach { expected ->
            assertThat(leafEventTitles.count { expected.title in it }).isEqualTo(1)
        }
    }

    @Test
    fun ordinaryDatasetCopyHasNoPartitionWindowStateAndPreservesCanonicalBytes() {
        val zone = ZoneId.of("UTC")
        val from = local(zone, 2026, 1, 1)
        val source = dataset(zone.id, from, from + DAY_MS - 1L, listOf(from))
        val copied = source.copy()

        assertThat(ClinicalReportDataset::class.java.declaredFields.map { it.name })
            .doesNotContain("preparedEventWindows")
        assertThat(
            GsonBuilder().serializeNulls().create().toJson(copied)
        ).doesNotContain("\"preparedEventWindows\"")
        assertThat(copied).isEqualTo(source)
        assertThat(copied.hashCode()).isEqualTo(source.hashCode())
        assertThat(ClinicalReportDatasetBuilder.serialize(copied))
            .isEqualTo(ClinicalReportDatasetBuilder.serialize(source))
    }

    @Test
    fun exactMidpointIsUsedWhenMinuteRoundingWouldViolateMargins() = runTest {
        val from = 10_000L
        val throughExclusive = 50_000L
        val plan = planner(
            requestBudgetBytes = 100,
            minimumIntervalMillis = 1L,
            requestFactory = { _, identity, _ ->
                sizedRequest(
                    input = "depth:${identity.depth}",
                    bytes = if (identity.depth == 0) 101 else 100
                )
            }
        ).planForTest(dataset("UTC", from, throughExclusive - 1L, listOf(from)))

        assertThat(plan.leaves).hasSize(2)
        assertThat(plan.leaves.first().descriptor.throughTsExclusive).isEqualTo(30_000L)
        assertThat(plan.leaves.last().descriptor.fromTs).isEqualTo(30_000L)
    }

    private fun planner(
        requestBudgetBytes: Int = 1_024,
        minimumIntervalMillis: Long = MINIMUM_INTERVAL_MS,
        maximumLeafCount: Int = ClinicalReportPartitionPlanner.MAX_PARTITION_LEAVES,
        canonicalizer: suspend (
            ClinicalReportDataset,
            ClinicalPartitionEventWindowBounds
        ) -> String = ClinicalReportDatasetBuilder::serializePartitionCancellable,
        requestFactory: suspend (
            ClinicalReportDataset,
            ClinicalLeafIdentity,
            String
        ) -> ClinicalSizedLeafRequest = { _, identity, _ ->
            sizedRequest("leaf:${identity.id}", 1)
        }
    ): ClinicalReportPartitionPlanner = ClinicalReportPartitionPlanner(
        requestBudgetBytes = requestBudgetBytes,
        minimumIntervalMillis = minimumIntervalMillis,
        maximumLeafCount = maximumLeafCount,
        requestFactory = requestFactory,
        canonicalizer = canonicalizer
    )

    private suspend fun ClinicalReportPartitionPlanner.planForTest(
        dataset: ClinicalReportDataset
    ): ClinicalPartitionPlan = plan(
        dataset,
        ClinicalReportDatasetBuilder.serializeCancellable(dataset)
    )

    private fun sizedRequest(@Suppress("UNUSED_PARAMETER") input: String, bytes: Int): ClinicalSizedLeafRequest {
        require(bytes > 0)
        return ClinicalSizedLeafRequest(bytes)
    }

    private fun eventTitles(canonicalJson: String, key: String): List<String> {
        val root = JsonParser.parseString(canonicalJson).asJsonObject
        return root.getAsJsonArray(key)
            .mapNotNull { it.asJsonObject.get("title")?.asString }
    }

    private fun plannedActivityTypes(canonicalJson: String): List<String> =
        JsonParser.parseString(canonicalJson).asJsonObject
            .getAsJsonArray("pa")
            .map { it.asJsonObject.get("type").asString }

    private fun plannedActivity(
        type: String,
        startTs: Long,
        durationMinutes: Int = 30
    ): ClinicalPlannedActivitySummary = ClinicalPlannedActivitySummary(
        type = type,
        intensity = "MEDIUM",
        plannedStartMs = startTs,
        plannedDurationMinutes = durationMinutes
    )

    private fun dataset(
        zoneId: String,
        fromTs: Long,
        throughTs: Long,
        timestamps: List<Long>
    ): ClinicalReportDataset {
        val glucose = timestamps.mapIndexed { index, ts ->
            ClinicalGlucosePoint(ts, 5.5 + index * 0.1)
        }
        val therapy = timestamps.map {
            ClinicalTherapyPoint(ts = it, insulinU = 1.0, carbsG = 10.0)
        }
        val targets = timestamps.map {
            ClinicalTargetPoint(ts = it, lowMmol = 5.0, highMmol = 6.0)
        }
        val detail = ClinicalDetailWindow(
            fromTs = fromTs,
            throughTs = throughTs,
            glucose = glucose,
            calibratedGlucose = glucose.map { it.copy(mmol = it.mmol + 0.1) },
            therapy = therapy,
            targets = targets,
            forecasts = timestamps.map {
                ClinicalForecastPoint(it, 30, 6.0, 5.0, 7.0)
            },
            telemetry = timestamps.map {
                ClinicalTelemetryPoint(it, "iob_units", 1.0, "OK")
            }
        )
        val summary = summary(fromTs, throughTs)
        return ClinicalReportDataset(
            schemaVersion = ClinicalReportDatasetBuilder.SCHEMA_VERSION,
            generatedAt = throughTs,
            zoneId = zoneId,
            detail24h = detail,
            glucose7d = glucose,
            therapy7d = therapy,
            targets7d = targets,
            glucose30d = glucose,
            therapy30d = therapy,
            targets30d = targets,
            summary7d = summary.copy(days = 7),
            summary30d = summary.copy(days = 30)
        )
    }

    private fun summary(fromTs: Long, throughTs: Long): ClinicalPeriodSummary =
        ClinicalPeriodSummary(
            days = 30,
            fromTs = fromTs,
            throughTs = throughTs,
            coveragePct = 100.0,
            meanMmol = 6.0,
            medianMmol = 6.0,
            coefficientOfVariationPct = 10.0,
            timeBelow4Pct = 0.0,
            timeInRangePct = 100.0,
            timeAboveRangePct = 0.0,
            totalInsulinU = 1.0,
            totalCarbsG = 10.0,
            meanTargetMmol = 5.5,
            weekdayPattern = emptyList(),
            weekendPattern = emptyList(),
            quality = ClinicalDataQuality(1, 1, 0, null)
        )

    private fun semanticRows(dataset: ClinicalReportDataset): ClinicalSeriesRowCounts =
        ClinicalSeriesRowCounts(
            glucose = dataset.detail24h.glucose.size +
                dataset.detail24h.calibratedGlucose.size +
                dataset.glucose7d.size +
                dataset.glucose30d.size,
            insulin = listOf(
                dataset.detail24h.therapy,
                dataset.therapy7d,
                dataset.therapy30d
            ).sumOf { rows -> rows.count { it.insulinU != null } },
            carbs = listOf(
                dataset.detail24h.therapy,
                dataset.therapy7d,
                dataset.therapy30d
            ).sumOf { rows -> rows.count { it.carbsG != null } },
            targets = dataset.detail24h.targets.size +
                dataset.targets7d.size +
                dataset.targets30d.size
        )

    private fun canonicalRows(canonicalJson: String, path: String): List<String> {
        val root = JsonParser.parseString(canonicalJson).asJsonObject
        val rows = if (path.startsWith("d24/")) {
            root.getAsJsonObject("d24").getAsJsonArray(path.substringAfter('/'))
        } else {
            root.getAsJsonArray(path)
        }
        return rows.map { it.toString() }
    }

    private fun verifyRowCoverageForTest(
        rootCanonicalJson: String,
        leafCanonicalJsons: List<String>
    ) {
        try {
            ClinicalWireAccounting.verify(
                rootCanonicalJson,
                leafCanonicalJsons.map { canonicalJson ->
                    """{"dataset":$canonicalJson}"""
                }
            )
        } catch (_: ClinicalOpenAiException.InvalidInput) {
            throw ClinicalPartitionException.CoverageMismatch()
        }
    }

    private fun assertContiguous(plan: ClinicalPartitionPlan) {
        plan.leaves.zipWithNext().forEach { (left, right) ->
            assertThat(left.descriptor.throughTsExclusive)
                .isEqualTo(right.descriptor.fromTs)
        }
    }

    private fun local(
        zone: ZoneId,
        year: Int,
        month: Int,
        day: Int
    ): Long = ZonedDateTime.of(year, month, day, 0, 0, 0, 0, zone)
        .toInstant()
        .toEpochMilli()

    private fun leaf(
        id: String,
        fromTs: Long,
        throughTsExclusive: Long,
        rows: ClinicalSeriesRowCounts = ClinicalSeriesRowCounts(glucose = 1),
        hash: String,
        depth: Int = 0,
        requestBytes: Int = 128
    ): ClinicalLeafDescriptor = ClinicalLeafDescriptor(
        id = id,
        fromTs = fromTs,
        throughTsExclusive = throughTsExclusive,
        depth = depth,
        sourceRows = rows,
        canonicalHash = hash,
        requestBytes = requestBytes
    )

    private fun singleLeafLedger(leaf: ClinicalLeafDescriptor): ClinicalCoverageLedger =
        ClinicalCoverageLedger.create(
            expectedFromTs = leaf.fromTs,
            expectedThroughTsExclusive = leaf.throughTsExclusive,
            expectedRows = leaf.sourceRows,
            leaves = listOf(leaf)
        )

    private companion object {
        const val MINIMUM_INTERVAL_MS = 15L * 60_000L
        const val HOUR_MS = 60L * 60L * 1_000L
        const val DAY_MS = 24L * HOUR_MS
        val WIRE_ROW_PATHS = listOf(
            "d24/g",
            "d24/gc",
            "d24/e",
            "d24/t",
            "d24/f",
            "d24/m",
            "g7",
            "e7",
            "t7",
            "g30",
            "e30",
            "t30"
        )
    }
}
