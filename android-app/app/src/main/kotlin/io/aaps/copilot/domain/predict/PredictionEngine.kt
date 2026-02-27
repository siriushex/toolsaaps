package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent

interface PredictionEngine {
    suspend fun predict(
        glucose: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>
    ): List<Forecast>
}
