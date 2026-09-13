package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClinicalReportRoutePreparationPolicyTest {

    @Test
    fun rapidOverviewClicksDoNotPrepareUntilFirstAiAnalysisRouteTransition() {
        var activeRoute = "overview"
        var preparationCount = 0
        val rapidClickDestinations = List(3) { "ai_analysis" }

        rapidClickDestinations.forEach {
            if (
                MainViewModel.shouldPrepareClinicalReportOnRouteTransition(
                    currentRoute = activeRoute,
                    nextRoute = activeRoute
                )
            ) {
                preparationCount += 1
            }
        }
        assertThat(preparationCount).isEqualTo(0)

        listOf(rapidClickDestinations.last(), "ai_analysis", "ai_analysis").forEach { observedRoute ->
            if (
                MainViewModel.shouldPrepareClinicalReportOnRouteTransition(
                    currentRoute = activeRoute,
                    nextRoute = observedRoute
                )
            ) {
                preparationCount += 1
            }
            activeRoute = observedRoute
        }

        assertThat(preparationCount).isEqualTo(1)
    }

    @Test
    fun directDrawerTransitionAlsoPreparesOnceAndSameRouteDoesNotRepeat() {
        assertThat(
            MainViewModel.shouldPrepareClinicalReportOnRouteTransition(
                currentRoute = "forecast",
                nextRoute = "ai_analysis"
            )
        ).isTrue()
        assertThat(
            MainViewModel.shouldPrepareClinicalReportOnRouteTransition(
                currentRoute = "ai_analysis",
                nextRoute = "ai_analysis"
            )
        ).isFalse()
    }

    @Test
    fun transitionsToOtherRoutesNeverPrepareClinicalReport() {
        listOf("overview", "forecast", "analytics", "settings").forEach { nextRoute ->
            assertThat(
                MainViewModel.shouldPrepareClinicalReportOnRouteTransition(
                    currentRoute = "overview",
                    nextRoute = nextRoute
                )
            ).isFalse()
        }
    }
}
