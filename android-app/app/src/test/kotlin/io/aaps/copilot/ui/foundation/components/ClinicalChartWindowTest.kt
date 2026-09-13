package io.aaps.copilot.ui.foundation.components

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.ui.foundation.screens.ChartCiPointUi
import io.aaps.copilot.ui.foundation.screens.ChartPointUi
import org.junit.Test

class ClinicalChartWindowTest {
    @Test
    fun plotBounds_matchCanvasInsetsUsedByRenderingAndGestures() {
        val bounds = clinicalChartPlotHorizontalBounds(
            totalWidthPx = 400f,
            leftInsetPx = 34f,
            rightInsetPx = 4f
        )

        assertThat(bounds.leftPx).isEqualTo(34f)
        assertThat(bounds.rightPx).isEqualTo(396f)
        assertThat(bounds.widthPx).isEqualTo(362f)
    }

    @Test
    fun visibleWindow_keepsOneBoundaryPointOnEachSide() {
        val points = (0L..5L).map { ChartPointUi(ts = it * 10L, value = it.toDouble()) }

        val visible = selectClinicalChartPoints(points, startTs = 15L, endTs = 35L)

        assertThat(visible.map { it.ts }).containsExactly(10L, 20L, 30L, 40L).inOrder()
    }

    @Test
    fun visibleWindow_usesBinaryAccessForNarrowRange() {
        val points = CountingChartPointList(size = 10_000)

        val visible = selectClinicalChartPoints(points, startTs = 50_000L, endTs = 50_010L)

        assertThat(visible.map { it.ts })
            .containsExactly(49_990L, 50_000L, 50_010L, 50_020L)
            .inOrder()
        assertThat(points.accessCount).isLessThan(100)
    }

    @Test
    fun visibleWindow_boundsBoundaryScansAcrossLongInvalidRuns() {
        val points = CountingSparseFiniteChartPointList(
            size = 10_000,
            finiteIndices = setOf(5_000, 5_001)
        )

        val visible = selectClinicalChartPoints(points, startTs = 50_000L, endTs = 50_010L)

        assertThat(visible.map { it.ts }).containsExactly(50_000L, 50_010L).inOrder()
        assertThat(points.accessCount).isLessThan(150)
    }

    @Test
    fun reduction_keepsBucketMinimumAndMaximumInTimeOrder() {
        val points = listOf(
            ChartPointUi(0L, 5.0),
            ChartPointUi(1L, 3.0),
            ChartPointUi(2L, 8.0),
            ChartPointUi(3L, 6.0)
        )

        val reduced = reduceClinicalChartPoints(
            points,
            startTs = 0L,
            endTs = 4L,
            pixelColumns = 1
        )

        assertThat(reduced.map { it.value }).containsExactly(3.0, 8.0).inOrder()
    }

    @Test
    fun reduction_emitsAtMostTwoExtremaPerColumnInChronologicalOrder() {
        val points = listOf(
            ChartPointUi(-10L, 4.0),
            ChartPointUi(0L, 5.0),
            ChartPointUi(5L, 1.0),
            ChartPointUi(10L, 4.0),
            ChartPointUi(15L, 2.0),
            ChartPointUi(20L, 6.0),
            ChartPointUi(25L, 3.0),
            ChartPointUi(30L, 7.0),
            ChartPointUi(35L, 4.0),
            ChartPointUi(40L, 9.0),
            ChartPointUi(50L, 5.0)
        )

        val reduced = reduceClinicalChartPoints(points, 0L, 40L, pixelColumns = 4)

        assertThat(reduced.map { it.ts })
            .containsExactly(-10L, 0L, 5L, 10L, 15L, 20L, 25L, 35L, 40L, 50L)
            .inOrder()
        assertThat(reduced.size).isAtMost(2 * 4 + 2)
    }

    @Test
    fun reduction_doesNotDuplicateTiedOrRepeatedExtrema() {
        val points = listOf(
            ChartPointUi(0L, 3.0),
            ChartPointUi(0L, 3.0),
            ChartPointUi(1L, 3.0),
            ChartPointUi(2L, 8.0),
            ChartPointUi(2L, 8.0),
            ChartPointUi(3L, 8.0)
        )

        val reduced = reduceClinicalChartPoints(points, 0L, 4L, pixelColumns = 1)

        assertThat(reduced).containsExactly(ChartPointUi(0L, 3.0), ChartPointUi(2L, 8.0)).inOrder()
    }

    @Test
    fun visibleBounds_includeTargetAndQuantizeOutward() {
        val bounds = clinicalChartBounds(
            history = listOf(ChartPointUi(1L, 5.1), ChartPointUi(2L, 5.2)),
            future = listOf(ChartPointUi(3L, 5.8)),
            ci = listOf(ChartCiPointUi(3L, 4.8, 7.1)),
            displayLow = 3.9,
            displayHigh = 6.7
        )

        assertThat(bounds!!.min).isEqualTo(3.5)
        assertThat(bounds.max).isEqualTo(7.5)
    }

    @Test
    fun visibleWindow_filtersNonFinitePointsAndHandlesEmptyRanges() {
        val points = listOf(
            ChartPointUi(0L, Double.NaN),
            ChartPointUi(10L, 1.0),
            ChartPointUi(20L, Double.POSITIVE_INFINITY),
            ChartPointUi(30L, 3.0)
        )

        assertThat(selectClinicalChartPoints(points, 15L, 25L).map { it.ts })
            .containsExactly(10L, 30L)
            .inOrder()
        assertThat(selectClinicalChartPoints(emptyList(), 0L, 10L)).isEmpty()
        assertThat(selectClinicalChartPoints(points, 30L, 20L)).isEmpty()
    }

    @Test
    fun visibleWindow_scansPastNonFinitePointsForUsableBoundaries() {
        val points = listOf(
            ChartPointUi(0L, 1.0),
            ChartPointUi(10L, Double.NaN),
            ChartPointUi(30L, Double.POSITIVE_INFINITY),
            ChartPointUi(40L, 4.0)
        )

        val visible = selectClinicalChartPoints(points, startTs = 15L, endTs = 25L)

        assertThat(visible.map { it.ts }).containsExactly(0L, 40L).inOrder()
    }

    @Test
    fun visibleCiWindow_filtersNonFiniteBounds() {
        val points = listOf(
            ChartCiPointUi(0L, Double.NaN, 5.0),
            ChartCiPointUi(10L, 4.0, 6.0),
            ChartCiPointUi(20L, 5.0, Double.POSITIVE_INFINITY),
            ChartCiPointUi(30L, 6.0, 8.0)
        )

        val visible = selectClinicalChartCiPoints(points, startTs = 15L, endTs = 25L)

        assertThat(visible.map { it.ts }).containsExactly(10L, 30L).inOrder()
    }

    @Test
    fun reduction_treatsNonPositivePixelColumnsAsOne() {
        val points = listOf(
            ChartPointUi(0L, 5.0),
            ChartPointUi(1L, 3.0),
            ChartPointUi(2L, 8.0),
            ChartPointUi(3L, 6.0)
        )

        val reduced = reduceClinicalChartPoints(points, 0L, 4L, pixelColumns = 0)

        assertThat(reduced.map { it.value }).containsExactly(3.0, 8.0).inOrder()
    }

    @Test
    fun reduction_handlesExtremeTimestampsWithoutCorruptingOrder() {
        val points = listOf(
            ChartPointUi(Long.MIN_VALUE + 1L, 1.0),
            ChartPointUi(0L, 2.0),
            ChartPointUi(Long.MAX_VALUE - 1L, 3.0)
        )

        val reduced = reduceClinicalChartPoints(
            points,
            startTs = Long.MIN_VALUE,
            endTs = Long.MAX_VALUE,
            pixelColumns = 2
        )

        assertThat(reduced.map { it.ts }).isInOrder()
    }

    @Test
    fun pixelColumn_mapsFullLongDomainQuarterPointsToDistinctColumns() {
        assertThat(clinicalChartPixelColumn(Long.MIN_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, 4))
            .isEqualTo(0)
        assertThat(clinicalChartPixelColumn(Long.MIN_VALUE / 2L, Long.MIN_VALUE, Long.MAX_VALUE, 4))
            .isEqualTo(1)
        assertThat(clinicalChartPixelColumn(0L, Long.MIN_VALUE, Long.MAX_VALUE, 4))
            .isEqualTo(2)
        assertThat(clinicalChartPixelColumn(Long.MAX_VALUE / 2L, Long.MIN_VALUE, Long.MAX_VALUE, 4))
            .isEqualTo(3)
    }

    @Test
    fun pixelColumn_preservesNarrowOffsetsNearLongMax() {
        val startTs = Long.MAX_VALUE - 100L
        val endTs = Long.MAX_VALUE

        assertThat(clinicalChartPixelColumn(startTs + 25L, startTs, endTs, 4)).isEqualTo(1)
        assertThat(clinicalChartPixelColumn(startTs + 50L, startTs, endTs, 4)).isEqualTo(2)
        assertThat(clinicalChartPixelColumn(startTs + 75L, startTs, endTs, 4)).isEqualTo(3)
    }

    @Test
    fun pixelColumn_preservesNarrowOffsetsNearLongMin() {
        val startTs = Long.MIN_VALUE
        val endTs = Long.MIN_VALUE + 100L

        assertThat(clinicalChartPixelColumn(startTs + 25L, startTs, endTs, 4)).isEqualTo(1)
        assertThat(clinicalChartPixelColumn(startTs + 50L, startTs, endTs, 4)).isEqualTo(2)
        assertThat(clinicalChartPixelColumn(startTs + 75L, startTs, endTs, 4)).isEqualTo(3)
    }

    @Test
    fun pixelColumn_handlesInvalidRangesAndColumnCounts() {
        assertThat(clinicalChartPixelColumn(5L, startTs = 10L, endTs = 0L, pixelColumns = 4))
            .isEqualTo(0)
        assertThat(clinicalChartPixelColumn(10L, startTs = 10L, endTs = 10L, pixelColumns = 4))
            .isEqualTo(0)
        assertThat(clinicalChartPixelColumn(5L, startTs = 0L, endTs = 10L, pixelColumns = 0))
            .isEqualTo(0)
    }
}

private class CountingChartPointList(
    override val size: Int
) : AbstractList<ChartPointUi>() {
    var accessCount: Int = 0
        private set

    override fun get(index: Int): ChartPointUi {
        accessCount += 1
        return ChartPointUi(ts = index * 10L, value = index.toDouble())
    }
}

private class CountingSparseFiniteChartPointList(
    override val size: Int,
    private val finiteIndices: Set<Int>
) : AbstractList<ChartPointUi>() {
    var accessCount: Int = 0
        private set

    override fun get(index: Int): ChartPointUi {
        accessCount += 1
        val value = if (index in finiteIndices) index.toDouble() else Double.NaN
        return ChartPointUi(ts = index * 10L, value = value)
    }
}
