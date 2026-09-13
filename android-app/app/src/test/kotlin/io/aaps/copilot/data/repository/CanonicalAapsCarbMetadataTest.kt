package io.aaps.copilot.data.repository

import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import org.junit.Test

class CanonicalAapsCarbMetadataTest {

    @Test
    fun ownershipRequiresNumericPositiveIntegralJsonPrimitive() {
        val incoming = jsonObject(
            """{"notes":"updated","aapsCarbDigest":"incoming"}"""
        )
        listOf(
            """{"aapsCarbId":"45","aapsCarbDigest":"quoted"}""",
            """{"aapsCarbId":0,"aapsCarbDigest":"zero"}""",
            """{"aapsCarbId":-1,"aapsCarbDigest":"negative"}""",
            """{"aapsCarbId":1.5,"aapsCarbDigest":"fraction"}""",
            """{"aapsCarbId":1e2,"aapsCarbDigest":"exponent"}"""
        ).forEach { rawExisting ->
            val merged = preserveCanonicalAapsCarbMetadata(
                incoming = incoming,
                existing = jsonObject(rawExisting)
            )

            assertThat(merged).isEqualTo(incoming)
        }

        val owned = preserveCanonicalAapsCarbMetadata(
            incoming = incoming,
            existing = jsonObject(
                """{
                    "aapsCarbId":45,
                    "aapsCarbDigest":"canonical",
                    "aapsCarbIsValid":false,
                    "aapsCarbAmount":0,
                    "aapsCarbReferenceId":"",
                    "aapsReferenceId":null
                }""".trimIndent()
            )
        )

        assertThat(owned["aapsCarbId"].asLong).isEqualTo(45L)
        assertThat(owned["aapsCarbDigest"].asString).isEqualTo("canonical")
        assertThat(owned["aapsCarbIsValid"].asBoolean).isFalse()
        assertThat(owned["aapsCarbAmount"].asInt).isEqualTo(0)
        assertThat(owned["aapsCarbReferenceId"].asString).isEmpty()
        assertThat(owned["aapsReferenceId"]).isEqualTo(JsonNull.INSTANCE)
        assertThat(owned["notes"].asString).isEqualTo("updated")
    }

    @Test
    fun nestedIncomingFieldsSurviveWhileLatestCanonicalElementsWin() {
        val incoming = TherapyEventEntity(
            id = "nested-treatment",
            timestamp = 2_000L,
            type = "meal_bolus",
            payloadJson = """{
                "aapsCarbId":48,
                "aapsCarbDigest":"stale",
                "notes":"updated notes",
                "clinical":{"segments":[1,{"active":true}]}
            }""".trimIndent()
        )
        val existing = TherapyEventEntity(
            id = "nested-treatment",
            timestamp = 1_000L,
            type = "carbs",
            payloadJson = """{
                "aapsCarbId":48,
                "aapsCarbDigest":"canonical",
                "aapsCarbIsValid":false,
                "notes":"old notes",
                "clinical":{"old":true}
            }""".trimIndent()
        )

        val merged = mergeTherapyEventWithCanonicalAapsCarbMetadata(
            incoming = incoming,
            latestExisting = existing,
            decodePayload = ::parseTherapyPayloadJsonObject,
            encodePayload = Gson()::toJson
        )
        val payload = jsonObject(merged.payloadJson)

        assertThat(payload["aapsCarbDigest"].asString).isEqualTo("canonical")
        assertThat(payload["aapsCarbIsValid"].asBoolean).isFalse()
        assertThat(payload["notes"].asString).isEqualTo("updated notes")
        assertThat(payload["clinical"]).isEqualTo(
            JsonParser.parseString("""{"segments":[1,{"active":true}]}""")
        )
    }

    @Test
    fun canonicalIntegralNumberReplacesStructurallyEqualDecimalAndRemainsOwned() {
        val incoming = TherapyEventEntity(
            id = "numeric-shape",
            timestamp = 2_000L,
            type = "carbs",
            payloadJson = """{"aapsCarbId":45.0,"aapsCarbDigest":"canonical"}"""
        )
        val existing = incoming.copy(
            timestamp = 1_000L,
            payloadJson = """{"aapsCarbId":45,"aapsCarbDigest":"canonical"}"""
        )

        val merged = mergeTherapyEventWithCanonicalAapsCarbMetadata(
            incoming = incoming,
            latestExisting = existing,
            decodePayload = ::parseTherapyPayloadJsonObject,
            encodePayload = Gson()::toJson
        )
        val mergedPayload = jsonObject(merged.payloadJson)

        assertThat(mergedPayload["aapsCarbId"].toString()).isEqualTo("45")
        assertThat(mergedPayload["aapsCarbDigest"].asString).isEqualTo("canonical")

        val stillOwned = preserveCanonicalAapsCarbMetadata(
            incoming = jsonObject("""{"aapsCarbId":45,"aapsCarbDigest":"later"}"""),
            existing = mergedPayload
        )
        assertThat(stillOwned["aapsCarbDigest"].asString).isEqualTo("canonical")
    }

    @Test
    fun malformedPayloadsFailSafeWithoutReplacingIncomingRow() {
        val validIncoming = TherapyEventEntity(
            id = "malformed-existing",
            timestamp = 2_000L,
            type = "carbs",
            payloadJson = """{"aapsCarbId":49,"notes":"incoming"}"""
        )
        val malformedExisting = validIncoming.copy(
            timestamp = 1_000L,
            payloadJson = "{not-json"
        )
        val malformedIncoming = validIncoming.copy(
            id = "malformed-incoming",
            payloadJson = "[not-an-object]"
        )
        val canonicalExisting = validIncoming.copy(
            id = "malformed-incoming",
            timestamp = 1_000L,
            payloadJson = """{"aapsCarbId":49,"aapsCarbDigest":"canonical"}"""
        )

        assertThat(
            mergeTherapyEventWithCanonicalAapsCarbMetadata(
                validIncoming,
                malformedExisting,
                ::parseTherapyPayloadJsonObject,
                Gson()::toJson
            )
        ).isEqualTo(validIncoming)
        assertThat(
            mergeTherapyEventWithCanonicalAapsCarbMetadata(
                malformedIncoming,
                canonicalExisting,
                ::parseTherapyPayloadJsonObject,
                Gson()::toJson
            )
        ).isEqualTo(malformedIncoming)
    }

    @Test
    fun existingCanonicalValuesOverrideConflictsIncludingFalseZeroAndBlank() {
        val canonical = linkedMapOf(
            "aapsCarbId" to "44",
            "aapsVersion" to "0",
            "aapsDateCreated" to "0",
            "aapsReferenceId" to "",
            "aapsTimestamp" to "0",
            "aapsDuration" to "0",
            "aapsRevisionId" to "0",
            "aapsPageGeneratedAt" to "0",
            "aapsCarbAmount" to "0.0",
            "aapsCarbIsValid" to "false",
            "aapsCarbClassification" to "",
            "aapsCarbSource" to "",
            "aapsCarbSynthetic" to "false",
            "aapsCarbSuperseded" to "false",
            "aapsCarbSupersededBy" to "",
            "notesSha256" to "",
            "notesTruncated" to "false",
            "nightscoutIdSha256" to "",
            "nightscoutIdTruncated" to "false"
        )
        val incoming = canonical.mapValues { "conflict-${it.key}" } + mapOf(
            "insulin" to "2.5",
            "eventType" to "Meal Bolus"
        )

        val merged = preserveCanonicalAapsCarbMetadata(
            incoming = incoming,
            existing = canonical
        )

        canonical.forEach { (key, value) ->
            assertThat(merged[key]).isEqualTo(value)
        }
        assertThat(merged["insulin"]).isEqualTo("2.5")
        assertThat(merged["eventType"]).isEqualTo("Meal Bolus")
    }

    @Test
    fun omittedCanonicalValuesAreRestoredWithoutOverridingClinicalRefreshes() {
        val merged = preserveCanonicalAapsCarbMetadata(
            incoming = mapOf(
                "insulin" to "3.1",
                "createdAt" to "2026-07-30T10:00:00Z"
            ),
            existing = mapOf(
                "aapsCarbId" to "45",
                "aapsCarbAmount" to "-4.0",
                "aapsCarbIsValid" to "false",
                "insulin" to "1.0",
                "createdAt" to "old"
            )
        )

        assertThat(merged["aapsCarbId"]).isEqualTo("45")
        assertThat(merged["aapsCarbAmount"]).isEqualTo("-4.0")
        assertThat(merged["aapsCarbIsValid"]).isEqualTo("false")
        assertThat(merged["insulin"]).isEqualTo("3.1")
        assertThat(merged["createdAt"]).isEqualTo("2026-07-30T10:00:00Z")
    }

    @Test
    fun ownedRowRefreshesRawNotesWhilePreservingCanonicalNotesDigest() {
        val merged = preserveCanonicalAapsCarbMetadata(
            incoming = mapOf(
                "notes" to "updated Nightscout notes",
                "notesSha256" to "incoming-derived-digest",
                "notesTruncated" to "true"
            ),
            existing = mapOf(
                "aapsCarbId" to "45",
                "notes" to "old AAPS notes",
                "notesSha256" to "canonical-notes-digest",
                "notesTruncated" to "false"
            )
        )

        assertThat(merged["notes"]).isEqualTo("updated Nightscout notes")
        assertThat(merged["notesSha256"]).isEqualTo("canonical-notes-digest")
        assertThat(merged["notesTruncated"]).isEqualTo("false")
    }

    @Test
    fun nonAapsExistingPayloadDoesNotFreezeNotesOrPseudoMetadata() {
        val incoming = mapOf(
            "notes" to "updated Nightscout notes",
            "aapsCarbIdentity" to "incoming-pseudo",
            "source" to "nightscout_treatment"
        )

        val merged = preserveCanonicalAapsCarbMetadata(
            incoming = incoming,
            existing = mapOf(
                "notes" to "old local notes",
                "aapsCarbIdentity" to "old-pseudo",
                "aapsCarbDigest" to "old-pseudo-digest",
                "notesTruncated" to "false"
            )
        )

        assertThat(merged).isEqualTo(incoming)
        assertThat(merged["notes"]).isEqualTo("updated Nightscout notes")
        assertThat(merged).doesNotContainKey("aapsCarbDigest")
        assertThat(merged).doesNotContainKey("notesTruncated")
    }

    @Test
    fun malformedOrNonPositiveAapsCarbIdDoesNotEstablishOwnership() {
        listOf("", "0", "-1", "1.5", "1e2", "not-a-number", "9223372036854775808")
            .forEach { invalidId ->
                val incoming = mapOf("notes" to "updated")

                val merged = preserveCanonicalAapsCarbMetadata(
                    incoming = incoming,
                    existing = mapOf(
                        "aapsCarbId" to invalidId,
                        "notes" to "old"
                    )
                )

                assertThat(merged).isEqualTo(incoming)
            }
    }

    @Test
    fun compatibilityCanonicalAliasesAreAlsoAuthoritativeWhenPresent() {
        val aliases = linkedMapOf(
            "aapsCarbId" to "46",
            "aapsCarbVersion" to "0",
            "aapsCarbDateCreated" to "0",
            "aapsCarbReferenceId" to "",
            "aapsCarbRevisionId" to "0",
            "aapsCarbIdentity" to "",
            "aapsCarbDigest" to "",
            "aapsCarbNotesSha256" to "",
            "aapsCarbNightscoutIdSha256" to ""
        )

        val merged = preserveCanonicalAapsCarbMetadata(
            incoming = aliases.mapValues { "incoming" },
            existing = aliases
        )

        assertThat(merged).containsAtLeastEntriesIn(aliases)
    }

    @Test
    fun finalRowMergeUsesLatestCanonicalMetadataAndIncomingClinicalFields() {
        val incoming = TherapyEventEntity(
            id = "shared-treatment",
            timestamp = 2_000L,
            type = "meal_bolus",
            payloadJson = encodePayload(
                mapOf(
                    "aapsCarbId" to "48",
                    "aapsCarbDigest" to "stale-precomputed-digest",
                    "aapsCarbIsValid" to "true",
                    "notes" to "updated Nightscout notes",
                    "insulin" to "2.5"
                )
            )
        )
        val latestExisting = TherapyEventEntity(
            id = "shared-treatment",
            timestamp = 1_000L,
            type = "carbs",
            payloadJson = encodePayload(
                mapOf(
                    "aapsCarbId" to "48",
                    "aapsCarbDigest" to "newer-importer-digest",
                    "aapsCarbIsValid" to "false",
                    "aapsCarbAmount" to "0",
                    "aapsCarbReferenceId" to "",
                    "notes" to "older raw notes",
                    "insulin" to "1.0"
                )
            )
        )

        val merged = mergeTherapyEventWithCanonicalAapsCarbMetadata(
            incoming = incoming,
            latestExisting = latestExisting,
            decodePayload = ::parseTherapyPayloadJsonObject,
            encodePayload = Gson()::toJson
        )
        val payload = jsonObject(merged.payloadJson)

        assertThat(merged.timestamp).isEqualTo(2_000L)
        assertThat(merged.type).isEqualTo("meal_bolus")
        assertThat(payload["aapsCarbDigest"].asString).isEqualTo("newer-importer-digest")
        assertThat(payload["aapsCarbIsValid"].asString).isEqualTo("false")
        assertThat(payload["aapsCarbAmount"].asString).isEqualTo("0")
        assertThat(payload["aapsCarbReferenceId"].asString).isEmpty()
        assertThat(payload["notes"].asString).isEqualTo("updated Nightscout notes")
        assertThat(payload["insulin"].asString).isEqualTo("2.5")
    }

    private fun preserveCanonicalAapsCarbMetadata(
        incoming: Map<String, String>,
        existing: Map<String, String>
    ): Map<String, String> {
        val merged = io.aaps.copilot.data.repository.preserveCanonicalAapsCarbMetadata(
            incoming = stringMapJson(incoming),
            existing = stringMapJson(existing)
        )
        return merged.entrySet().associate { (key, value) -> key to value.asString }
    }

    private fun encodePayload(payload: Map<String, String>): String = Gson().toJson(stringMapJson(payload))

    private fun stringMapJson(payload: Map<String, String>): JsonObject = JsonObject().apply {
        payload.forEach { (key, value) ->
            if (key == "aapsCarbId" && value.all { it in '0'..'9' }) {
                value.toLongOrNull()?.let { addProperty(key, it) } ?: addProperty(key, value)
            } else {
                addProperty(key, value)
            }
        }
    }

    private fun jsonObject(raw: String): JsonObject = JsonParser.parseString(raw).asJsonObject
}
