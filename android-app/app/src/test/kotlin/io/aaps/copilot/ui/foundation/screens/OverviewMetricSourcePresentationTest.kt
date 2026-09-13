package io.aaps.copilot.ui.foundation.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OverviewMetricSourcePresentationTest {

    @Test
    fun selectedSourceHasCompactVisibleLabel() {
        assertThat(metricSourceSelectionShortLabel("EVIDENCE")).isEqualTo("Evidence")
        assertThat(metricSourceSelectionShortLabel("COPILOT")).isEqualTo("Copilot")
        assertThat(metricSourceSelectionShortLabel("AAPS")).isEqualTo("AAPS")
        assertThat(metricSourceSelectionShortLabel("UNAVAILABLE")).isEqualTo("--")
    }

    @Test
    fun matrixUsesActualResolvedSourceAndKeepsApplyingUnavailableExplicit() {
        assertThat(metricActualSourceLabelKind("EVIDENCE_BLEND", MetricRuntimeAvailabilityUi.AVAILABLE))
            .isEqualTo(MetricActualSourceLabelKind.EVIDENCE)
        assertThat(metricActualSourceLabelKind("COPILOT_NATIVE", MetricRuntimeAvailabilityUi.AVAILABLE))
            .isEqualTo(MetricActualSourceLabelKind.COPILOT)
        assertThat(metricActualSourceLabelKind("AAPS", MetricRuntimeAvailabilityUi.APPLYING))
            .isEqualTo(MetricActualSourceLabelKind.APPLYING)
        assertThat(metricActualSourceLabelKind("AAPS", MetricRuntimeAvailabilityUi.UNAVAILABLE))
            .isEqualTo(MetricActualSourceLabelKind.UNAVAILABLE)
        assertThat(metricActualSourceShortLabel("EVIDENCE_BLEND", MetricRuntimeAvailabilityUi.AVAILABLE))
            .isEqualTo("Evidence")
        assertThat(metricActualSourceShortLabel("COPILOT_NATIVE", MetricRuntimeAvailabilityUi.AVAILABLE))
            .isEqualTo("Copilot")
        assertThat(metricActualSourceShortLabel("AAPS", MetricRuntimeAvailabilityUi.APPLYING))
            .isEqualTo("Applying")
        assertThat(metricActualSourceShortLabel("AAPS", MetricRuntimeAvailabilityUi.UNAVAILABLE))
            .isEqualTo("Unavailable")
    }

    @Test
    fun globalSensitivityApplyDisablesEveryIsfAndCrSourceChoice() {
        listOf(
            "AAPS" to "EVIDENCE",
            "EVIDENCE" to "COPILOT"
        ).forEach { (requested, choice) ->
            assertThat(
                metricSourceChoiceEnabled(
                    globalSensitivityApply = true,
                    runtimeAvailability = MetricRuntimeAvailabilityUi.AVAILABLE,
                    requestedSource = requested,
                    choiceSource = choice
                )
            ).isFalse()
        }
        assertThat(
            metricSourceChoiceEnabled(
                globalSensitivityApply = false,
                runtimeAvailability = MetricRuntimeAvailabilityUi.AVAILABLE,
                requestedSource = "AAPS",
                choiceSource = "EVIDENCE"
            )
        ).isTrue()
    }
}
