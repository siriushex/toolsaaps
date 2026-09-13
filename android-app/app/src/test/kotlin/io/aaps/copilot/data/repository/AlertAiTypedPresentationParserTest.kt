package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.alerts.AlertCauseCode
import io.aaps.copilot.domain.alerts.CauseConfidence
import org.junit.Test

class AlertAiTypedPresentationParserTest {
    @Test
    fun validCodeOnlyEnvelopeMapsToTypedPresentation() {
        val result = AlertAiTypedPresentationParser.parse(
            """{
                "schemaVersion":1,
                "primaryCauseCode":"SENSOR_QUALITY",
                "confidence":"HIGH",
                "evidenceCodes":["SENSOR_BLOCKED","CURRENT_EVIDENCE_STALE"],
                "adviceCode":"DATA_INCOMPLETE"
            }""".trimIndent()
        )

        assertThat(result).isEqualTo(
            AlertAiPresentation(
                primaryCause = AlertCauseCode.SENSOR_QUALITY,
                confidence = CauseConfidence.HIGH,
                evidence = listOf(AlertEvidenceKind.SENSOR_QUALITY, AlertEvidenceKind.DATA_QUALITY),
                advice = AlertCauseCode.DATA_INCOMPLETE,
                canonicalEvidenceCodes = listOf("SENSOR_BLOCKED", "CURRENT_EVIDENCE_STALE")
            )
        )
    }

    @Test
    fun everyLegacyFreeFormPayloadIsPresentationIneligible() {
        listOf(
            "{\"summary\":\"Glucose rose after the morning window.\"}",
            "{\"observation\":\"Use insulin now.\"}",
            "{\"recommendation\":\"<b>Review the trend</b>\"}",
            "{\"summary\":\"Review **sensor** trend.\"}",
            "{\"summary\":\"Increase in\\u3164sulin now.\"}",
            "{\"summary\":\"Increase insulιn now.\"}"
        ).forEach { payload ->
            assertThat(AlertAiTypedPresentationParser.parse(payload)).isNull()
        }
    }

    @Test
    fun duplicateUnknownMissingInvalidAndNestedFieldsRejectWholeEnvelope() {
        listOf(
            validJson().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            validJson().dropLast(1) + ",\"summary\":\"text\"}",
            validJson().replace(",\"adviceCode\":\"DATA_INCOMPLETE\"", ""),
            validJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            validJson().replace("\"SENSOR_QUALITY\"", "\"NOT_A_CAUSE\""),
            validJson().replace("\"HIGH\"", "\"CERTAIN\""),
            validJson().replace("\"SENSOR_BLOCKED\"", "\"PRIVATE_INTERNAL_TOKEN\""),
            validJson().replace(
                "\"evidenceCodes\":[\"SENSOR_BLOCKED\"]",
                "\"evidenceCodes\":{\"items\":[\"SENSOR_BLOCKED\"]}"
            ),
            validJson() + " true"
        ).forEach { payload ->
            assertThat(AlertAiTypedPresentationParser.parse(payload)).isNull()
        }
    }

    @Test
    fun evidenceArrayIsBoundedAndAnyInvalidEntryRejectsEverything() {
        val maximum = List(8) { "SENSOR_BLOCKED" }.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        val over = List(9) { "SENSOR_BLOCKED" }.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

        assertThat(AlertAiTypedPresentationParser.parse(validJson(evidence = maximum))).isNotNull()
        assertThat(AlertAiTypedPresentationParser.parse(validJson(evidence = over))).isNull()
        assertThat(
            AlertAiTypedPresentationParser.parse(
                validJson(evidence = "[\"SENSOR_BLOCKED\",\"arbitrary free text\"]")
            )
        ).isNull()
    }

    @Test
    fun allRequiredFieldsMustHaveExactPrimitiveTypes() {
        listOf(
            validJson().replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            validJson().replace("\"primaryCauseCode\":\"SENSOR_QUALITY\"", "\"primaryCauseCode\":null"),
            validJson().replace("\"confidence\":\"HIGH\"", "\"confidence\":true"),
            validJson().replace("\"adviceCode\":\"DATA_INCOMPLETE\"", "\"adviceCode\":[]"),
            validJson().replace("\"evidenceCodes\":[\"SENSOR_BLOCKED\"]", "\"evidenceCodes\":\"SENSOR_BLOCKED\"")
        ).forEach { payload ->
            assertThat(AlertAiTypedPresentationParser.parse(payload)).isNull()
        }
    }

    @Test
    fun validEnvelopeIsReencodedCanonicallyWithFixedFieldOrder() {
        val parsed = requireNotNull(
            AlertAiTypedPresentationParser.parse(
                """{"adviceCode":"DATA_INCOMPLETE","evidenceCodes":["CURRENT_EVIDENCE_STALE","SENSOR_BLOCKED"],"confidence":"HIGH","primaryCauseCode":"SENSOR_QUALITY","schemaVersion":1}"""
            )
        )

        assertThat(AlertAiTypedPresentationParser.encodeCanonical(parsed)).isEqualTo(
            """{"schemaVersion":1,"primaryCauseCode":"SENSOR_QUALITY","confidence":"HIGH","evidenceCodes":["CURRENT_EVIDENCE_STALE","SENSOR_BLOCKED"],"adviceCode":"DATA_INCOMPLETE"}"""
        )
    }

    @Test
    fun therapyAndUnknownFieldsAreRejectedInsteadOfBeingDiscarded() {
        listOf(
            "insulinDoseUnits" to "1.0",
            "carbohydrateAmountGrams" to "15",
            "targetMmol" to "4.2",
            "calibrationCommand" to "\"apply\"",
            "therapyAction" to "\"change_target\""
        ).forEach { (name, value) ->
            val unsafe = validJson().dropLast(1) + ",\"$name\":$value}"
            assertThat(AlertAiTypedPresentationParser.parse(unsafe)).isNull()
        }
    }

    private fun validJson(evidence: String = "[\"SENSOR_BLOCKED\"]"): String =
        """{"schemaVersion":1,"primaryCauseCode":"SENSOR_QUALITY","confidence":"HIGH","evidenceCodes":$evidence,"adviceCode":"DATA_INCOMPLETE"}"""
}
