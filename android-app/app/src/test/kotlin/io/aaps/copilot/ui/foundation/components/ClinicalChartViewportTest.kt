package io.aaps.copilot.ui.foundation.components

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ClinicalChartViewportTest {
    private val hour = 60L * 60_000L
    private val domain = ClinicalChartDomain(0L, 25L * hour, 24L * hour)

    @Test
    fun domain_rejectsReversedBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            ClinicalChartDomain(startTs = 2L, endTs = 1L, nowTs = 1L)
        }
    }

    @Test
    fun domain_rejectsNowOutsideBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            ClinicalChartDomain(startTs = 1L, endTs = 3L, nowTs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClinicalChartDomain(startTs = 1L, endTs = 3L, nowTs = 4L)
        }
    }

    @Test
    fun viewport_rejectsReversedBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            ClinicalChartViewport(2L, 1L, ClinicalChartViewportMode.EXPLORE)
        }
    }

    @Test
    fun equalEndpoints_areValidDegenerateBounds() {
        val degenerateDomain = ClinicalChartDomain(1L, 1L, 1L)
        val degenerateViewport = ClinicalChartViewport(1L, 1L, ClinicalChartViewportMode.EXPLORE)

        assertThat(degenerateDomain.durationMs).isEqualTo(0L)
        assertThat(degenerateViewport.durationMs).isEqualTo(0L)
        assertThat(defaultClinicalChartViewport(degenerateDomain).startTs).isEqualTo(1L)
        assertThat(defaultClinicalChartViewport(degenerateDomain).endTs).isEqualTo(1L)
    }

    @Test
    fun durations_saturateWhenBoundsSpanMoreThanLongMax() {
        val widestDomain = ClinicalChartDomain(Long.MIN_VALUE, Long.MAX_VALUE, 0L)
        val widestViewport = ClinicalChartViewport(
            Long.MIN_VALUE,
            Long.MAX_VALUE,
            ClinicalChartViewportMode.EXPLORE
        )

        assertThat(widestDomain.durationMs).isEqualTo(Long.MAX_VALUE)
        assertThat(widestViewport.durationMs).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun defaultViewport_keepsThreeHoursOfHistoryAndForecast() {
        val viewport = defaultClinicalChartViewport(domain)

        assertThat(viewport.startTs).isEqualTo(21L * hour)
        assertThat(viewport.endTs).isEqualTo(25L * hour)
        assertThat(viewport.mode).isEqualTo(ClinicalChartViewportMode.FOLLOW_LIVE)
    }

    @Test
    fun zoom_preservesTimestampBelowFocalPoint() {
        val current = ClinicalChartViewport(20L * hour, 24L * hour, ClinicalChartViewportMode.FOLLOW_LIVE)
        val zoomed = zoomClinicalChartViewport(current, domain, focalFraction = 0.25, zoomFactor = 2f)

        assertThat(zoomed.durationMs).isEqualTo(2L * hour)
        assertThat(zoomed.startTs).isEqualTo(20L * hour + 30L * 60_000L)
        assertThat(zoomed.mode).isEqualTo(ClinicalChartViewportMode.EXPLORE)
    }

    @Test
    fun zoom_clampsToMinimumDuration() {
        val current = defaultClinicalChartViewport(domain)
        val zoomed = zoomClinicalChartViewport(current, domain, focalFraction = 0.5, zoomFactor = 100f)

        assertThat(zoomed.durationMs).isEqualTo(CLINICAL_CHART_MIN_VIEWPORT_MS)
    }

    @Test
    fun zoom_clampsToWholeAvailableDomain() {
        val current = defaultClinicalChartViewport(domain)
        val zoomed = zoomClinicalChartViewport(current, domain, focalFraction = 0.5, zoomFactor = 0.01f)

        assertThat(zoomed.startTs).isEqualTo(domain.startTs)
        assertThat(zoomed.endTs).isEqualTo(domain.endTs)
    }

    @Test
    fun zoom_shortDomainReturnsWholeDomainInsteadOfThrowing() {
        val shortDomain = ClinicalChartDomain(0L, 10L * 60_000L, 10L * 60_000L)
        val current = defaultClinicalChartViewport(shortDomain)

        val zoomed = zoomClinicalChartViewport(current, shortDomain, focalFraction = 0.5, zoomFactor = 2f)

        assertThat(zoomed.startTs).isEqualTo(shortDomain.startTs)
        assertThat(zoomed.endTs).isEqualTo(shortDomain.endTs)
    }

    @Test
    fun zoom_invalidFactorLeavesViewportUnchanged() {
        val current = ClinicalChartViewport(20L * hour, 24L * hour, ClinicalChartViewportMode.FOLLOW_LIVE)

        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f).forEach { factor ->
            assertThat(zoomClinicalChartViewport(current, domain, focalFraction = 0.5, zoomFactor = factor))
                .isEqualTo(current)
        }
    }

    @Test
    fun zoom_nonFiniteFocalFractionLeavesViewportUnchanged() {
        val current = ClinicalChartViewport(20L * hour, 24L * hour, ClinicalChartViewportMode.FOLLOW_LIVE)

        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { fraction ->
            assertThat(zoomClinicalChartViewport(current, domain, focalFraction = fraction, zoomFactor = 2f))
                .isEqualTo(current)
        }
    }

    @Test
    fun pan_clampsAtOldestAndNewestDomainBounds() {
        val current = ClinicalChartViewport(4L * hour, 8L * hour, ClinicalChartViewportMode.EXPLORE)

        assertThat(panClinicalChartViewport(current, domain, -10L * hour).startTs).isEqualTo(0L)
        assertThat(panClinicalChartViewport(current, domain, 30L * hour).endTs).isEqualTo(25L * hour)
    }

    @Test
    fun pan_saturatesPositiveOverflowAtNewestDomainBound() {
        val current = ClinicalChartViewport(4L * hour, 8L * hour, ClinicalChartViewportMode.EXPLORE)

        val panned = panClinicalChartViewport(current, domain, Long.MAX_VALUE)

        assertThat(panned.startTs).isEqualTo(21L * hour)
        assertThat(panned.endTs).isEqualTo(25L * hour)
    }

    @Test
    fun pan_saturatesNegativeOverflowAtOldestDomainBound() {
        val minimumDomain = ClinicalChartDomain(
            Long.MIN_VALUE,
            Long.MIN_VALUE + 25L * hour,
            Long.MIN_VALUE + 24L * hour
        )
        val current = ClinicalChartViewport(
            Long.MIN_VALUE + 4L * hour,
            Long.MIN_VALUE + 8L * hour,
            ClinicalChartViewportMode.EXPLORE
        )

        val panned = panClinicalChartViewport(current, minimumDomain, Long.MIN_VALUE)

        assertThat(panned.startTs).isEqualTo(minimumDomain.startTs)
        assertThat(panned.endTs).isEqualTo(minimumDomain.startTs + 4L * hour)
    }

    @Test
    fun reconcile_movesOnlyFollowLiveViewport() {
        val live = ClinicalChartViewport(21L * hour, 25L * hour, ClinicalChartViewportMode.FOLLOW_LIVE)
        val explore = live.copy(mode = ClinicalChartViewportMode.EXPLORE)
        val movedDomain = ClinicalChartDomain(1L * hour, 26L * hour, 25L * hour)

        assertThat(reconcileClinicalChartViewport(live, movedDomain).endTs).isEqualTo(26L * hour)
        assertThat(reconcileClinicalChartViewport(explore, movedDomain).startTs).isEqualTo(21L * hour)
    }

    @Test
    fun reconcile_clampsExploreViewportAfterSourceReplacement() {
        val explore = ClinicalChartViewport(1L * hour, 5L * hour, ClinicalChartViewportMode.EXPLORE)
        val replacedDomain = ClinicalChartDomain(10L * hour, 30L * hour, 29L * hour)

        val reconciled = reconcileClinicalChartViewport(explore, replacedDomain)

        assertThat(reconciled.startTs).isEqualTo(10L * hour)
        assertThat(reconciled.endTs).isEqualTo(14L * hour)
        assertThat(reconciled.mode).isEqualTo(ClinicalChartViewportMode.EXPLORE)
    }
}
