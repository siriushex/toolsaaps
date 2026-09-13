package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.isSyntheticUamCarbEvent

internal class InsulinOnsetMealExclusion(
    private val therapy: List<TherapyEvent>,
    private val exclusionMs: Long
) {
    // One estimator invocation owns this memo; duplicate identities remain distinct rows.
    private var mealByIndex: ByteArray? = null

    fun hasNearbyMeal(bolusTs: Long): Boolean {
        val states = mealByIndex ?: ByteArray(therapy.size).also { mealByIndex = it }
        for ((index, other) in therapy.withIndex()) {
            if (states[index] == UNKNOWN) {
                val grams = if (isSyntheticUamCarbEvent(other)) {
                    0.0
                } else {
                    TherapyPayloadLookup.number(other.payload, CARB_KEYS) ?: 0.0
                }
                states[index] = if (grams >= 5.0) MEAL else NOT_MEAL
            }
            if (states[index] == MEAL && kotlin.math.abs(other.ts - bolusTs) <= exclusionMs) return true
        }
        return false
    }

    private companion object {
        const val UNKNOWN: Byte = 0
        const val NOT_MEAL: Byte = 1
        const val MEAL: Byte = 2
        val CARB_KEYS = arrayOf("grams", "carbs", "enteredCarbs", "mealCarbs")
    }
}
