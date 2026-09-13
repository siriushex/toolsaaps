package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.Gson
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.model.resolveTherapyComponents
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class TherapyEventMapperCanonicalIdentityTest {

    @Test
    fun entityMappingKeepsOnlyExactPositiveJsonNumberAsCanonicalIdentity() {
        fun mappedPayload(idJson: String) = TherapyEventEntity(
            id = "row-$idJson",
            timestamp = 100_000L,
            type = "meal_bolus",
            payloadJson = """{"aapsCarbId":$idJson,"aapsCarbAmount":24,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL"}"""
        ).toDomain(Gson()).payload

        assertThat(mappedPayload("702")["aapsCarbId"]).isEqualTo("702")
        listOf("\"702\"", "702.0", "7.02e2", "-702", "0").forEach { malformedId ->
            val payload = mappedPayload(malformedId)
            assertThat(payload).doesNotContainKey("aapsCarbId")
            assertThat(payload["aapsCarbAmount"]).isEqualTo("24")
        }
    }

    @Test
    fun entityMappingValidatesNormalizedAliasesBeforeCreatingCanonicalIdentity() {
        fun mappedPayload(payloadJson: String) = TherapyEventEntity(
            id = "row",
            timestamp = 100_000L,
            type = "meal_bolus",
            payloadJson = payloadJson
        ).toDomain(Gson()).payload
        fun canonicalId(payloadJson: String) = mappedPayload(payloadJson).let { payload ->
            resolveTherapyComponents("meal_bolus", payload).canonicalCarbId
        }

        val validAlias = mappedPayload(
            """{"aaps_carb_id":702,"aapsCarbAmount":24,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL"}"""
        )
        assertThat(validAlias["aapsCarbId"]).isEqualTo("702")
        assertThat(validAlias).doesNotContainKey("aaps_carb_id")

        listOf(
            """{"aaps_carb_id":"702","aapsCarbAmount":24}""",
            """{"AAPS-CARB-ID":702.0,"aapsCarbAmount":24}""",
            """{"aaps carb id":7.02e2,"aapsCarbAmount":24}""",
            """{"aaps_carb_id":0,"aapsCarbAmount":24}""",
            """{"aaps_carb_id":-702,"aapsCarbAmount":24}""",
            """{"aapsCarbId":702,"aaps_carb_id":703,"aapsCarbAmount":24}""",
            """{"aapsCarbId":702,"aaps_carb_id":"702","aapsCarbAmount":24}"""
        ).forEach { payloadJson ->
            assertThat(canonicalId(payloadJson)).isNull()
        }
    }

    @Test
    fun entityMappingRejectsEveryDuplicateCanonicalIdentityOccurrence() {
        fun mappedPayload(payloadJson: String) = TherapyEventEntity(
            id = "duplicate-id",
            timestamp = 100_000L,
            type = "meal_bolus",
            payloadJson = payloadJson
        ).toDomain(Gson()).payload

        listOf(
            """{"aapsCarbId":702,"aapsCarbId":702,"aapsCarbAmount":24}""",
            """{"aapsCarbId":702,"aaps_carb_id":702,"aapsCarbAmount":24}""",
            """{"aapsCarbId":"702","aaps_carb_id":702,"aapsCarbAmount":24}""",
            """{"aapsCarbId":702,"aaps_carb_id":"702","aapsCarbAmount":24}"""
        ).forEach { payloadJson ->
            val payload = mappedPayload(payloadJson)
            assertThat(payload).doesNotContainKey("aapsCarbId")
            assertThat(resolveTherapyComponents("meal_bolus", payload).canonicalCarbId).isNull()
            assertThat(resolveTherapyComponents("meal_bolus", payload).canonicalCarbAuthoritative)
                .isTrue()
        }
    }

    @Test
    fun directMapResolverDoesNotTrustNormalizedCanonicalIdAlias() {
        val components = resolveTherapyComponents(
            "meal_bolus",
            mapOf(
                "aaps_carb_id" to "702",
                "aapsCarbAmount" to "24",
                "aapsCarbIsValid" to "true",
                "aapsCarbClassification" to "AAPS_REAL"
            )
        )

        assertThat(components.canonicalCarbId).isNull()
        assertThat(components.canonicalCarbAuthoritative).isTrue()
        assertThat(components.carbsG).isEqualTo(24.0)
    }

    @Test
    fun entityMappingFailsClosedForEveryDuplicateCanonicalSemanticField() {
        val duplicateSemantics = listOf(
            """"aapsCarbAmount":24,"aapsCarbAmount":24,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL"""",
            """"aapsCarbAmount":24,"aaps_carb_amount":30,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL"""",
            """"aaps_carb_amount":30,"aapsCarbAmount":24,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL"""",
            """"aapsCarbAmount":24,"aapsCarbIsValid":true,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL"""",
            """"aapsCarbAmount":24,"aapsCarbIsValid":true,"aaps_carb_is_valid":false,"aapsCarbClassification":"AAPS_REAL"""",
            """"aapsCarbAmount":24,"aaps_carb_is_valid":false,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL"""",
            """"aapsCarbAmount":24,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbClassification":"AAPS_REAL"""",
            """"aapsCarbAmount":24,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aaps_carb_classification":"AAPS_CORRECTION"""",
            """"aapsCarbAmount":24,"aapsCarbIsValid":true,"aaps_carb_classification":"AAPS_CORRECTION","aapsCarbClassification":"AAPS_REAL"""",
            """"aapsCarbAmount":24,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSynthetic":false""",
            """"aapsCarbAmount":24,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSuperseded":false,"aaps_carb_superseded":true"""
        )

        duplicateSemantics.forEachIndexed { index, semantics ->
            val event = TherapyEventEntity(
                id = "semantic-conflict-$index",
                timestamp = 100_000L,
                type = "meal_bolus",
                payloadJson = """{"units":3,"carbs":80,$semantics}"""
            ).toDomain(Gson())
            val components = resolveTherapyComponents(event)

            assertWithMessage("duplicate semantic case $index")
                .that(components.canonicalCarbAuthoritative).isTrue()
            assertWithMessage("duplicate semantic case $index")
                .that(components.insulinU).isEqualTo(3.0)
            assertWithMessage("duplicate semantic case $index")
                .that(components.carbsG).isNull()
        }
    }

    @Test
    fun entityMappingRejectsDuplicateLegacyValidityButCanonicalMetadataKeepsComponentsIndependent() {
        listOf(
            """"isValid":true,"isValid":true""",
            """"isValid":true,"is_valid":false""",
            """"is_valid":false,"isValid":true"""
        ).forEachIndexed { index, validity ->
            val event = TherapyEventEntity(
                id = "legacy-validity-$index",
                timestamp = 100_000L,
                type = "meal_bolus",
                payloadJson = """{"units":3,"carbs":40,$validity}"""
            ).toDomain(Gson())
            val components = resolveTherapyComponents(event)

            assertThat(components.wholeEventValid).isFalse()
            assertThat(components.insulinU).isNull()
            assertThat(components.carbsG).isNull()
        }

        val canonicalEvent = TherapyEventEntity(
            id = "canonical-with-legacy-conflict",
            timestamp = 100_000L,
            type = "meal_bolus",
            payloadJson =
                """{"units":3,"isValid":true,"is_valid":false,"aapsCarbAmount":24,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL"}"""
        ).toDomain(Gson())
        val canonical = resolveTherapyComponents(canonicalEvent)

        assertThat(canonical.wholeEventValid).isFalse()
        assertThat(canonical.insulinU).isEqualTo(3.0)
        assertThat(canonical.carbsG).isEqualTo(24.0)
    }

    @Test
    fun entityMapperRoundTripsArbitraryPrimitiveScalars() {
        val original = TherapyEvent(
            ts = 100_000L,
            type = "insulin",
            payload = mapOf(
                "units" to "3",
                "confidence" to "0.82",
                "deltaIob" to "-0.4",
                "recoverySource" to "nightscout",
                "provenance" to "confirmed"
            )
        )

        val roundTrip = original.toEntity(Gson(), "round-trip").toDomain(Gson())

        assertThat(roundTrip.payload).containsExactlyEntriesIn(original.payload)
    }

    @Test
    fun entityMapperPreservesArbitraryPrimitivesAndSkipsCompositeSiblings() {
        val payload = TherapyEventEntity(
            id = "general-scalars",
            timestamp = 100_000L,
            type = "insulin",
            payloadJson =
                """{"units":3,"confidence":0.82,"deltaIob":-0.4,"recoverySource":"nightscout","provenance":true,"nested":{"value":1},"samples":[1,2],"missing":null}"""
        ).toDomain(Gson()).payload

        assertThat(payload).containsAtLeast(
            "units", "3",
            "confidence", "0.82",
            "deltaIob", "-0.4",
            "recoverySource", "nightscout",
            "provenance", "true"
        )
        assertThat(payload).doesNotContainKey("nested")
        assertThat(payload).doesNotContainKey("samples")
        assertThat(payload).doesNotContainKey("missing")
    }

    @Test
    fun entityMapperDoesNotTrustPayloadSuppliedConflictMarkers() {
        val payload = TherapyEventEntity(
            id = "untrusted-conflict-marker",
            timestamp = 100_000L,
            type = "meal_bolus",
            payloadJson =
                """{"units":3,"carbs":40,"isValid":false,"__copilotCanonicalCarbSemanticConflict":true}"""
        ).toDomain(Gson()).payload

        val components = resolveTherapyComponents("meal_bolus", payload)

        assertThat(payload).doesNotContainKey("__copilotCanonicalCarbSemanticConflict")
        assertThat(components.canonicalCarbAuthoritative).isFalse()
        assertThat(components.insulinU).isNull()
        assertThat(components.carbsG).isNull()
    }

    @Test
    fun entityMappingCarriesTypedConflictAndIdentityAcrossCopyWithoutSerializingTrust() {
        val event = TherapyEventEntity(
            id = "trusted-conflict",
            timestamp = 100_000L,
            type = "meal_bolus",
            payloadJson =
                """{"units":3,"carbs":80,"isValid":false,"aapsCarbId":702,"aapsCarbAmount":24,"aaps_carb_amount":30,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL"}"""
        ).toDomain(Gson())

        val original = resolveTherapyComponents(event)
        val copied = event.copy(payload = event.payload + ("provenance" to "canonicalized"))
        val copiedComponents = resolveTherapyComponents(copied)

        assertThat(event.payload.keys.none { it.startsWith("__copilot") }).isTrue()
        assertThat(original.canonicalCarbId).isEqualTo(702L)
        assertThat(original.insulinU).isEqualTo(3.0)
        assertThat(original.carbsG).isNull()
        assertThat(copiedComponents).isEqualTo(original)

        val gsonJson = Gson().toJson(event)
        val kotlinxJson = Json.encodeToString(event)
        assertThat(gsonJson).doesNotContain("componentTrust")
        assertThat(kotlinxJson).doesNotContain("componentTrust")
        assertThat(gsonJson).doesNotContain("__copilotCanonicalCarbSemanticConflict")
        assertThat(kotlinxJson).doesNotContain("__copilotCanonicalCarbSemanticConflict")
    }

    @Test
    fun entityMappingPreservesTypedSourceRowIdentityWithoutSerializingIt() {
        val event = TherapyEventEntity(
            id = "aaps-source-row-42",
            timestamp = 100_000L,
            type = "note",
            payloadJson = """{"note":"ordinary note"}"""
        ).toDomain(Gson())

        assertThat(event.sourceRowId).isEqualTo("aaps-source-row-42")
        assertThat(event.copy(payload = event.payload + ("other" to "value")).sourceRowId)
            .isEqualTo("aaps-source-row-42")
        assertThat(Gson().toJson(event)).doesNotContain("aaps-source-row-42")
        assertThat(Json.encodeToString(event)).doesNotContain("aaps-source-row-42")
    }
}
