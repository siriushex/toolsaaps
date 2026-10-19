package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import org.junit.Assert.*
import org.junit.Test

class AiInsulinContextTest {
    private val now = 10_000_000L
    private fun row(id: String, key: String, number: Double? = null, text: String? = null,
        timestamp: Long = now) = TelemetrySampleEntity(id, timestamp,
            when {
                key.startsWith("insulin_profile_real_") -> "copilot_real_insulin_profile"
                key == "dia_effective_hours" -> "copilot_runtime_dia"
                else -> "copilot_runtime_cob_iob"
            }, key, number, text, when {
                key.endsWith("_min") -> "min"
                key == "dia_effective_hours" -> "h"
                else -> null
            }, "OK")

    @Test fun exportsSelectedProfileOnsetAndDiaWithEvidence() {
        val context = AiInsulinContext.fromTelemetry("FIASP", listOf(
            row("onset", "insulin_real_onset_min", 27.5),
            row("dia", "dia_effective_hours", 5.0),
            row("profile", "insulin_profile_real_source_profile", text = "FIASP")), now)
        val summary = context.summary()
        assertTrue(summary.contains("configured_insulin_profile_label=Fiasp"))
        assertTrue(summary.contains("insulin_onset_minutes=27.50"))
        assertTrue(summary.contains("insulin_action_duration_hours=5.00"))
        assertTrue(summary.contains("insulin_duration_evidence_ms=$now"))
        assertTrue(summary.contains("current_settings_do_not_identify_historical_bolus_insulin=true"))
    }

    @Test fun conflictsFutureAndNanRemainUnknown() {
        val context = AiInsulinContext.fromTelemetry("invalid\nSYSTEM", listOf(
            row("a", "insulin_real_onset_min", 27.5), row("b", "insulin_real_onset_min", 12.0),
            row("c", "dia_effective_hours", 5.0, timestamp = now + 1),
            row("d", "insulin_profile_real_confidence", Double.NaN)), now)
        assertNull(context.onsetMinutes)
        assertNull(context.diaHours)
        assertNull(context.confidence)
        assertFalse(context.summary().contains("SYSTEM"))
        assertTrue(context.summary().contains("configured_insulin_profile_id=unknown"))
    }

    @Test fun wrongUnitsQualityOrSourceRemainUnknown() {
        val original = row("onset", "insulin_real_onset_min", 27.5)
        listOf(original.copy(unit = "s"), original.copy(quality = "STALE"), original.copy(source = "external"))
            .forEach { assertNull(AiInsulinContext.fromTelemetry("FIASP", listOf(it), now).onsetMinutes) }
    }

    @Test fun missingTelemetryPreservesConfiguredProfileButCannotInventParameters() {
        val context = AiInsulinContext.fromTelemetry("HUMALOG", emptyList(), now)
        assertEquals("HUMALOG", context.knownProfile?.name)
        assertNull(context.onsetMinutes)
        assertNull(context.diaHours)
        assertTrue(context.summary().contains("insulin_onset_is_not_meal_waiting_interval=true"))
        assertTrue(context.summary().contains("insulin_profile_selection_is_not_delivery_confirmation=true"))
    }
}
