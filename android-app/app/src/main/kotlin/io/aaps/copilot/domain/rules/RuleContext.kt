package io.aaps.copilot.domain.rules

import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.PatternWindow
import io.aaps.copilot.domain.model.ProfileEstimate
import io.aaps.copilot.domain.model.ProfileSegmentEstimate
import io.aaps.copilot.domain.model.TherapyEvent

data class RuleContext(
    val nowTs: Long,
    val glucose: List<GlucosePoint>,
    val therapyEvents: List<TherapyEvent>,
    val forecasts: List<Forecast>,
    val currentDayPattern: PatternWindow?,
    val baseTargetMmol: Double,
    val postHypoThresholdMmol: Double = 3.0,
    val postHypoDeltaThresholdMmol5m: Double = 0.20,
    val postHypoTargetMmol: Double = 4.4,
    val postHypoDurationMinutes: Int = 60,
    val postHypoLookbackMinutes: Int = 90,
    val dataFresh: Boolean,
    val activeTempTargetMmol: Double?,
    val actionsLast6h: Int,
    val sensorBlocked: Boolean = false,
    val currentProfileEstimate: ProfileEstimate? = null,
    val currentProfileSegment: ProfileSegmentEstimate? = null
)
