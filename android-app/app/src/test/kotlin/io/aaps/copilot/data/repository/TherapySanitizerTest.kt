package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.model.resolveTherapyComponents
import org.junit.Test

class TherapySanitizerTest {

    @Test
    fun removesOnlyLocalBroadcastArtifacts() {
        val events = listOf(
            TherapyEventEntity(
                id = "br-aaps_broadcast-correction_bolus-1",
                timestamp = 1L,
                type = "correction_bolus",
                payloadJson = """{"units":"7.2"}"""
            ),
            TherapyEventEntity(
                id = "br-local_broadcast-correction_bolus-2",
                timestamp = 2L,
                type = "correction_bolus",
                payloadJson = """{"units":"2.1"}"""
            ),
            TherapyEventEntity(
                id = "local-ns-1",
                timestamp = 3L,
                type = "carbs",
                payloadJson = """{"carbs":"13"}"""
            )
        )

        val filtered = TherapySanitizer.filterEntities(events)
        assertThat(filtered).hasSize(2)
        assertThat(filtered.map { it.id }).containsExactly(
            "br-aaps_broadcast-correction_bolus-1",
            "local-ns-1"
        )
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

    @Test
    fun preservesIndependentlyValidCanonicalComponentsAndRejectsInvalidLegacyWholeEvent() {
        val canonicalCarb = therapy(
            id = "canonical-carb",
            payload =
                """{"units":"bad","carbs":90,"aapsCarbAmount":24,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
        )
        val canonicalInsulin = therapy(
            id = "canonical-insulin",
            payload =
                """{"units":3,"carbs":999,"isValid":false,"aapsCarbAmount":40,"aapsCarbIsValid":false,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":true}"""
        )
        val invalidLegacy = therapy(
            id = "invalid-legacy",
            payload = """{"units":3,"carbs":40,"isValid":false}"""
        )
        val invalidLegacyBolus = therapy(
            id = "invalid-legacy-bolus",
            payload = """{"units":3,"isValid":false}""",
            type = "bolus"
        )
        val malformedLegacy = therapy(
            id = "malformed-legacy",
            payload = """{"units":"bad","carbs":"bad"}"""
        )
        val validPureInsulin = therapy(
            id = "valid-pure-insulin",
            payload = """{"units":2}""",
            type = "insulin"
        )

        val filtered = TherapySanitizer.filterEntities(
            listOf(
                canonicalCarb,
                canonicalInsulin,
                invalidLegacy,
                invalidLegacyBolus,
                malformedLegacy,
                validPureInsulin
            )
        )

        assertThat(filtered.map(TherapyEventEntity::id))
            .containsExactly("canonical-carb", "canonical-insulin", "valid-pure-insulin")
    }

    @Test
    fun repositoryDomainPathRetainsResolvedCanonicalComponentValues() {
        val events = listOf(
            therapy(
                id = "canonical-carb",
                payload =
                    """{"units":"bad","carbs":90,"aapsCarbAmount":24,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
            ),
            therapy(
                id = "canonical-insulin",
                payload =
                    """{"units":3,"carbs":999,"isValid":false,"aapsCarbAmount":40,"aapsCarbIsValid":false,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":true}"""
            )
        )

        val resolved = TherapySanitizer.toDomainEvents(events, Gson())
            .map(::resolveTherapyComponents)

        assertThat(resolved[0].insulinU).isNull()
        assertThat(resolved[0].carbsG).isEqualTo(24.0)
        assertThat(resolved[1].insulinU).isEqualTo(3.0)
        assertThat(resolved[1].carbsG).isNull()
    }

    @Test
    fun repositoryDomainPathSkipsNestedSiblingAndKeepsIndependentComponent() {
        val events = listOf(
            therapy(
                id = "nested-insulin-valid-carb",
                payload =
                    """{"units":{"value":3},"carbs":90,"aapsCarbAmount":24,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
            ),
            therapy(
                id = "valid-insulin-nested-carb",
                payload =
                    """{"units":3,"carbs":90,"aapsCarbAmount":[24],"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
            ),
            therapy(
                id = "nested-neither",
                payload = """{"units":{"value":3},"carbs":[24]}"""
            )
        )

        val resolved = TherapySanitizer.toDomainEvents(events, Gson())
            .map(::resolveTherapyComponents)

        assertThat(resolved).hasSize(2)
        assertThat(resolved[0].insulinU).isNull()
        assertThat(resolved[0].carbsG).isEqualTo(24.0)
        assertThat(resolved[1].insulinU).isEqualTo(3.0)
        assertThat(resolved[1].carbsG).isNull()
    }

    @Test
    fun repositoryDomainPathKeepsInsulinOnCanonicalConflictAndRejectsLegacyValidityConflict() {
        val events = listOf(
            therapy(
                id = "canonical-conflict",
                payload =
                    """{"units":3,"carbs":80,"aapsCarbAmount":24,"aaps_carb_amount":30,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","confidence":0.82,"deltaIob":-0.4,"recoverySource":"nightscout","nested":{"value":1}}"""
            ),
            therapy(
                id = "legacy-conflict",
                payload = """{"units":3,"carbs":40,"isValid":true,"is_valid":false}"""
            )
        )

        val domain = TherapySanitizer.toDomainEvents(events, Gson())

        assertThat(domain.map { it.payload }).hasSize(1)
        val retained = domain.single()
        val components = resolveTherapyComponents(retained)
        assertThat(components.insulinU).isEqualTo(3.0)
        assertThat(components.carbsG).isNull()
        assertThat(retained.payload).containsAtLeast(
            "confidence", "0.82",
            "deltaIob", "-0.4",
            "recoverySource", "nightscout"
        )
        assertThat(retained.payload).doesNotContainKey("nested")
    }

    private fun therapy(
        id: String,
        payload: String,
        type: String = "meal_bolus"
    ) = TherapyEventEntity(
        id = id,
        timestamp = 100_000L,
        type = type,
        payloadJson = payload
    )
}
