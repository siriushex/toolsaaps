package io.aaps.copilot.tools

import com.google.gson.Gson
import io.aaps.copilot.domain.meal.*
import io.aaps.copilot.domain.model.DataQuality
import org.junit.Assert.*
import org.junit.Test

class MealProspectiveEvaluationReportTest {
    @Test fun jsonRetainsBaselineOnlyScoresAndBetweenCheckpointRisk() {
        val anchor = 1_700_000_000_000L
        val identity = MealForecastIdentity(anchor, "cycle", 1, "digest", "sensor", "session", "basis",
            null, MealForecastBasis.ACCEPTED_CONTROL, "UTC")
        val points = listOf(5, 30, 60).map { MealProspectiveValue(it, anchor + it * 60_000L, 6.0) }
        val frame = requireNotNull(MealProspectiveFrame.register(identity, anchor + 1, points, null, anchor + 2).frame)
        val outcomes = listOf(5, 15, 30, 60).map { minutes ->
            val timestamp = anchor + minutes * 60_000L
            MealProspectiveOutcome(timestamp, timestamp + 1, if (minutes == 15) 3.5 else 6.0,
                DataQuality.OK, "sensor", "session", "basis", null)
        }
        val result = MealProspectiveEvaluator().evaluate(listOf(frame), outcomes, anchor + 61 * 60_000L, 3.9)
        val json = Gson().toJsonTree(MealProspectiveEvaluationToolTest().report(result, 1, emptyMap())).asJsonObject
        assertEquals(1, json["baselineOnlyFrames"].asInt)
        assertEquals(0, json["horizons"].asJsonArray.size())
        val baseline = json["baselineOnlyHorizons"].asJsonArray
        assertEquals(3, baseline.size())
        assertEquals(1, baseline[1].asJsonObject["observedSamples"].asInt)
        assertEquals(0.0, baseline[1].asJsonObject["baseline"].asJsonObject["maeMmol"].asDouble, 0.0)
        assertEquals(1, baseline[1].asJsonObject["windowAudit"].asJsonObject["windowsWithLowOutsideCheckpoints"].asInt)
        for (flag in listOf("receiptCaptureAuthenticatedByTool", "windowObservationCoverageVerified",
            "clinicalInfluenceAllowed", "controlSafetyValidated")) assertFalse(json[flag].asBoolean)
    }
}
