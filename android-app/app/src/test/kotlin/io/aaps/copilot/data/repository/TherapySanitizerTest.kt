package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import org.junit.Test

class TherapySanitizerTest {

    @Test
    fun removesBroadcastStatusArtifacts() {
        val events = listOf(
            TherapyEventEntity(
                id = "br-aaps_broadcast-correction_bolus-1",
                timestamp = 1L,
                type = "correction_bolus",
                payloadJson = """{"units":"7.2"}"""
            ),
            TherapyEventEntity(
                id = "local-ns-1",
                timestamp = 2L,
                type = "carbs",
                payloadJson = """{"carbs":"13"}"""
            )
        )

        val filtered = TherapySanitizer.filterEntities(events)
        assertThat(filtered).hasSize(1)
        assertThat(filtered.first().id).isEqualTo("local-ns-1")
    }

    @Test
    fun keepsPlausibleMealAndCorrectionEvents() {
        val events = listOf(
            TherapyEventEntity(
                id = "ns-meal-1",
                timestamp = 1L,
                type = "meal_bolus",
                payloadJson = """{"grams":"36","bolusUnits":"4.0"}"""
            ),
            TherapyEventEntity(
                id = "ns-corr-1",
                timestamp = 2L,
                type = "correction_bolus",
                payloadJson = """{"units":"1.5"}"""
            )
        )

        val filtered = TherapySanitizer.filterEntities(events)
        assertThat(filtered).hasSize(2)
    }
}
