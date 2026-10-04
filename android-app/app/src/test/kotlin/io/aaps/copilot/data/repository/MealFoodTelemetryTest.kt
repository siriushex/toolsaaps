package io.aaps.copilot.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.predict.HybridPredictionEngine
import io.aaps.copilot.domain.predict.MealFoodDisplayInput
import io.aaps.copilot.domain.predict.MealFoodDisplayProjection
import io.aaps.copilot.domain.profile.MealGlycemicIndex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MealFoodTelemetryTest {
    private val now = 1_700_000_000_000L
    private val gson = Gson()
    private fun projection() = MealFoodDisplayProjection.build(now, 0.1, listOf(
        MealFoodDisplayInput(20.0, { (it / 120.0).coerceAtMost(1.0) },
            MealGlycemicIndex.fromManualInput("80"))))!!

    @Test fun acceptedDecompositionRetainsSeparateDisplayResult() = runBlocking {
        val engine = HybridPredictionEngine(enableEnhancedPredictionV3 = true)
        val forecasts = engine.predict((0..20).map {
            GlucosePoint(now - (20 - it) * 300_000L, 6.0, "sensor") }, emptyList())
        val original = engine.diagnosticsSnapshot()!!
        val food = projection()
        val snapshot = AutomationRepository.extractForecastDecompositionSnapshotStatic(
            original.copy(foodDisplayProjection = food), forecasts)!!
        assertSame(food, snapshot.foodDisplayProjection)
        assertEquals(original.announcedCarbStep, snapshot.announcedCarbSteps)
    }

    @Test fun telemetryPublishesFullTailAndOnlyItsAcceptedIdentity() {
        val food = projection()
        val payload = AutomationRepository.foodDisplayTelemetryPayloadStatic(food, "accepted-7", gson)!!
        val json = JsonParser.parseString(payload).asJsonObject
        assertEquals(2, json.get("schemaVersion").asInt)
        assertEquals("accepted-7", json.get("cycle").asString)
        assertEquals(now, json.get("predictionAtMs").asLong)
        assertTrue(json.get("complete").asBoolean)
        assertEquals("food_gi_shape_v1", json.get("modelVersion").asString)
        assertEquals(1, json.get("giAdjustedMeals").asInt)
        assertEquals(25, json.getAsJsonArray("steps").size())
        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= 16_384)
    }

    @Test fun unavailableOrInvalidIdentityDoesNotPublishATruncatedFallback() {
        assertNull(AutomationRepository.foodDisplayTelemetryPayloadStatic(null, "accepted-7", gson))
        assertNull(AutomationRepository.foodDisplayTelemetryPayloadStatic(projection(), "", gson))
        assertNull(AutomationRepository.foodDisplayTelemetryPayloadStatic(projection(), "a".repeat(257), gson))
    }
}
