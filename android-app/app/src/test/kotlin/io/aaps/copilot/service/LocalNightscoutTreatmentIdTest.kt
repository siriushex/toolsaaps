package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalNightscoutTreatmentIdTest {

    @Test
    fun retriesReuseStableTreatmentIdAndDifferentCommandsDoNotCollide() {
        val first = stableCopilotTreatmentIdStatic(
            eventType = "Temporary Target",
            notes = "copilot:AdaptiveTargetController.v1:123:5.50"
        )
        val retry = stableCopilotTreatmentIdStatic(
            eventType = "Temporary Target",
            notes = "copilot:AdaptiveTargetController.v1:123:5.50"
        )
        val different = stableCopilotTreatmentIdStatic(
            eventType = "Temporary Target",
            notes = "copilot:AdaptiveTargetController.v1:124:5.50"
        )

        assertThat(first).isEqualTo(retry)
        assertThat(first).isNotEqualTo(different)
        assertThat(first).startsWith("local-ns-copilot-")
    }

    @Test
    fun missingIdempotencyNoteIsRejected() {
        assertThat(stableCopilotTreatmentIdStatic("Carb Correction", null)).isNull()
        assertThat(stableCopilotTreatmentIdStatic("Carb Correction", "  ")).isNull()
    }
}
