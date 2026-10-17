package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClinicalReportRoutePreparationPolicyTest {

    @Test
    fun rapidOverviewClicksRefreshReadModelsOnlyOnFirstAiAnalysisRouteTransition() {
        var activeRoute = "overview"
        var preparationCount = 0
        val rapidClickDestinations = List(3) { "ai_analysis" }

        rapidClickDestinations.forEach {
            if (
                MainViewModel.shouldRefreshAiReadModelsOnRouteTransition(
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
                MainViewModel.shouldRefreshAiReadModelsOnRouteTransition(
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
    fun directDrawerTransitionRefreshesReadModelsOnceAndSameRouteDoesNotRepeat() {
        assertThat(
            MainViewModel.shouldRefreshAiReadModelsOnRouteTransition(
                currentRoute = "forecast",
                nextRoute = "ai_analysis"
            )
        ).isTrue()
        assertThat(
            MainViewModel.shouldRefreshAiReadModelsOnRouteTransition(
                currentRoute = "ai_analysis",
                nextRoute = "ai_analysis"
            )
        ).isFalse()
    }

    @Test
    fun transitionsToOtherRoutesNeverRefreshAiReadModels() {
        listOf("overview", "forecast", "analytics", "settings").forEach { nextRoute ->
            assertThat(
                MainViewModel.shouldRefreshAiReadModelsOnRouteTransition(
                    currentRoute = "overview",
                    nextRoute = nextRoute
                )
            ).isFalse()
        }
    }
}
