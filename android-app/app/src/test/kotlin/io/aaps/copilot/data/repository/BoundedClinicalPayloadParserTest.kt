package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BoundedClinicalPayloadParserTest {

    @Test
    fun canonicalIdentityRequiresExactlyOneStrictNumericOccurrence() {
        fun identity(payload: String) =
            BoundedClinicalPayloadParser.parse(payload)
                ?.exactPositiveJsonLong("aapsCarbId")

        assertThat(identity("""{"aapsCarbId":702}""")).isEqualTo(702L)
        listOf(
            """{"aapsCarbId":702,"aapsCarbId":702}""",
            """{"aapsCarbId":702,"aaps_carb_id":702}""",
            """{"aapsCarbId":"702","aaps_carb_id":702}""",
            """{"aapsCarbId":702,"aaps_carb_id":"702"}"""
        ).forEach { payload ->
            assertThat(identity(payload)).isNull()
        }
    }

    @Test
    fun scalarValuesSkipNestedSiblingsButRetainCanonicalAuthority() {
        val parsed = checkNotNull(
            BoundedClinicalPayloadParser.parse(
                """{"units":{"value":3},"carbs":90,"absolute":0.8,"enteredBy":"nightscout","aapsCarbAmount":[24],"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL"}"""
            )
        )

        val values = parsed.scalarValues()

        assertThat(values).doesNotContainKey("units")
        assertThat(values["carbs"]).isEqualTo("90")
        assertThat(values["absolute"]).isEqualTo("0.8")
        assertThat(values["enteredBy"]).isEqualTo("nightscout")
        assertThat(values).containsKey("aapsCarbAmount")
        assertThat(values["aapsCarbAmount"]?.toDoubleOrNull()).isNull()
    }

    @Test
    fun scalarValuesPreserveArbitraryBoundedPrimitivesAndSkipCompositeValues() {
        val values = checkNotNull(
            BoundedClinicalPayloadParser.parse(
                """{"units":3,"confidence":0.82,"deltaIob":-0.4,"recoverySource":"nightscout","provenance":true,"nested":{"value":1},"samples":[1,2],"missing":null}"""
            )
        ).scalarValues()

        assertThat(values).containsAtLeast(
            "units", "3",
            "confidence", "0.82",
            "deltaIob", "-0.4",
            "recoverySource", "nightscout",
            "provenance", "true"
        )
        assertThat(values).doesNotContainKey("nested")
        assertThat(values).doesNotContainKey("samples")
        assertThat(values).doesNotContainKey("missing")
    }
}
