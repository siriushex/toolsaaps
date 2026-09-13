package io.aaps.copilot.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TherapyComponentSemanticsTest {

    @Test
    fun canonicalComponentAuthorityDoesNotDependOnCanonicalIdentity() {
        val components = resolveTherapyComponents(
            type = "meal_bolus",
            payload = mapOf(
                "units" to "3",
                "carbs" to "90",
                "isValid" to "false",
                "aapsCarbAmount" to "24",
                "aapsCarbIsValid" to "true",
                "aapsCarbClassification" to "AAPS_REAL",
                "aapsCarbSynthetic" to "false",
                "aapsCarbSuperseded" to "false"
            )
        )

        assertThat(components.canonicalCarbId).isNull()
        assertThat(components.insulinU).isEqualTo(3.0)
        assertThat(components.carbsG).isEqualTo(24.0)
    }

    @Test
    fun malformedCanonicalCarbFailsClosedWithoutDiscardingValidInsulin() {
        val components = resolveTherapyComponents(
            type = "meal_bolus",
            payload = mapOf(
                "units" to "3",
                "carbs" to "90",
                "isValid" to "false",
                "aapsCarbId" to "malformed",
                "aapsCarbAmount" to "malformed",
                "aapsCarbIsValid" to "true",
                "aapsCarbClassification" to "AAPS_REAL",
                "aapsCarbSynthetic" to "false",
                "aapsCarbSuperseded" to "false"
            )
        )

        assertThat(components.canonicalCarbId).isNull()
        assertThat(components.insulinU).isEqualTo(3.0)
        assertThat(components.carbsG).isNull()
    }

    @Test
    fun malformedInsulinDoesNotDiscardValidCanonicalCarb() {
        val components = resolveTherapyComponents(
            type = "meal_bolus",
            payload = mapOf(
                "units" to "malformed",
                "carbs" to "90",
                "aapsCarbId" to "701",
                "aapsCarbAmount" to "18",
                "aapsCarbIsValid" to "true",
                "aapsCarbClassification" to "AAPS_REAL",
                "aapsCarbSynthetic" to "false",
                "aapsCarbSuperseded" to "false"
            )
        )

        assertThat(components.insulinU).isNull()
        assertThat(components.carbsG).isEqualTo(18.0)
        assertThat(components.keepEvent).isTrue()
    }

    @Test
    fun malformedLegacyEventWithNoUsableComponentStaysRejected() {
        val components = resolveTherapyComponents(
            type = "meal_bolus",
            payload = mapOf("units" to "malformed", "carbs" to "malformed")
        )

        assertThat(components.keepEvent).isFalse()
    }

    @Test
    fun canonicalIdentityRequiresPositiveDigitsOnly() {
        assertThat(
            resolveTherapyComponents("carbs", mapOf("aapsCarbId" to "702")).canonicalCarbId
        ).isNull()
        listOf("702.0", "7.02e2", "-702", "0", "bad").forEach { malformedId ->
            assertThat(
                resolveTherapyComponents("carbs", mapOf("aapsCarbId" to malformedId)).canonicalCarbId
            ).isNull()
        }
    }

    @Test
    fun rawPayloadCannotSpoofParserConflictTrust() {
        val components = resolveTherapyComponents(
            type = "meal_bolus",
            payload = mapOf(
                "units" to "3",
                "carbs" to "40",
                "isValid" to "false",
                "__copilotCanonicalCarbSemanticConflict" to "true",
                "__copilotLegacyValidityConflict" to "false"
            )
        )

        assertThat(components.canonicalCarbAuthoritative).isFalse()
        assertThat(components.wholeEventValid).isFalse()
        assertThat(components.insulinU).isNull()
        assertThat(components.carbsG).isNull()
    }
}
