package io.aaps.copilot.ui.foundation.components

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.ui.foundation.screens.ChartCiPointUi
import io.aaps.copilot.ui.foundation.screens.ChartPointUi
import org.junit.Test

class ClinicalForecastChartMathTest {
    @Test
    fun bounds_includeDisplayRangeAndConfidenceInterval() {
        val bounds = clinicalChartBounds(
            history = listOf(ChartPointUi(1L, 5.6), ChartPointUi(2L, 5.4)),
            future = listOf(ChartPointUi(2L, 5.4), ChartPointUi(3L, 3.5)),
            ci = listOf(ChartCiPointUi(2L, 5.4, 5.4), ChartCiPointUi(3L, 2.2, 6.3)),
            displayLow = 3.9,
            displayHigh = 6.7
        )

        assertThat(bounds).isNotNull()
        assertThat(bounds!!.min).isEqualTo(1.5)
        assertThat(bounds.max).isEqualTo(7.5)
    }

    @Test
    fun bounds_returnNullWithoutTwoSeriesPoints() {
        val bounds = clinicalChartBounds(
            history = listOf(ChartPointUi(1L, 5.6)),
            future = emptyList(),
            ci = emptyList(),
            displayLow = 3.9,
            displayHigh = 6.7
        )

        assertThat(bounds).isNull()
    }

    @Test
    fun bounds_collapseAllHighValuesAtUpperLimit() {
        val bounds = clinicalChartBounds(
            history = listOf(ChartPointUi(1L, 25.0), ChartPointUi(2L, 26.0)),
            future = listOf(ChartPointUi(3L, 27.0)),
            ci = listOf(ChartCiPointUi(3L, 24.0, 28.0)),
            displayLow = 25.0,
            displayHigh = 30.0
        )

        assertThat(bounds).isEqualTo(ClinicalChartBounds(min = 22.0, max = 22.0))
    }

    @Test
    fun bounds_collapseAllLowValuesAtLowerLimit() {
        val bounds = clinicalChartBounds(
            history = listOf(ChartPointUi(1L, -5.0), ChartPointUi(2L, -4.0)),
            future = listOf(ChartPointUi(3L, -3.0)),
            ci = listOf(ChartCiPointUi(3L, -6.0, -2.0)),
            displayLow = -5.0,
            displayHigh = -1.0
        )

        assertThat(bounds).isEqualTo(ClinicalChartBounds(min = 0.0, max = 0.0))
    }

    @Test
    fun viewport_excludesOffscreenExtremeFromVisibleBounds() {
        val selected = selectClinicalChartPoints(
            points = listOf(
                ChartPointUi(0L, 20.0),
                ChartPointUi(10L, 5.0),
                ChartPointUi(20L, 5.5),
                ChartPointUi(30L, 6.0)
            ),
            startTs = 10L,
            endTs = 30L
        ).filter { it.ts in 10L..30L }

        val bounds = clinicalChartBounds(
            history = selected,
            future = emptyList(),
            ci = emptyList(),
            displayLow = 3.9,
            displayHigh = 6.7
        )

        assertThat(bounds!!.max).isLessThan(10.0)
    }

    @Test
    fun startAxisLabel_isHiddenWhenItWouldOverlapNowLabel() {
        assertThat(
            shouldDrawClinicalChartStartLabel(
                startX = 34f,
                startWidth = 42f,
                nowX = 70f,
                minimumGap = 8f
            )
        ).isFalse()
    }

    @Test
    fun startAxisLabel_isShownWhenThereIsEnoughSpaceBeforeNowLabel() {
        assertThat(
            shouldDrawClinicalChartStartLabel(
                startX = 34f,
                startWidth = 42f,
                nowX = 120f,
                minimumGap = 8f
            )
        ).isTrue()
    }

    @Test
    fun plus30AxisLabel_isHiddenWithoutAFullThirtyMinuteDomain() {
        assertThat(
            shouldDrawClinicalChartPlus30Label(
                domainMaxTs = 129L,
                plus30Ts = 130L,
                nowLabelX = 60f,
                nowLabelWidth = 30f,
                plus30LabelX = 110f,
                plus30LabelWidth = 32f,
                plotRight = 150f,
                minimumGap = 8f
            )
        ).isFalse()
    }

    @Test
    fun plus30AxisLabel_isHiddenWhenItWouldOverlapNowLabel() {
        assertThat(
            shouldDrawClinicalChartPlus30Label(
                domainMaxTs = 150L,
                plus30Ts = 130L,
                nowLabelX = 60f,
                nowLabelWidth = 30f,
                plus30LabelX = 85f,
                plus30LabelWidth = 32f,
                plotRight = 150f,
                minimumGap = 8f
            )
        ).isFalse()
    }

    @Test
    fun plus30AxisLabel_isShownForARealSeparatedThirtyMinutePoint() {
        assertThat(
            shouldDrawClinicalChartPlus30Label(
                domainMaxTs = 150L,
                plus30Ts = 130L,
                nowLabelX = 60f,
                nowLabelWidth = 30f,
                plus30LabelX = 110f,
                plus30LabelWidth = 32f,
                plotRight = 150f,
                minimumGap = 8f
            )
        ).isTrue()
    }
}
