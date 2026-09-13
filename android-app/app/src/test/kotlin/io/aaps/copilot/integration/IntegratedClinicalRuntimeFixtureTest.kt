package io.aaps.copilot.integration

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test

class IntegratedClinicalRuntimeFixtureTest {

    @Test
    fun syntheticRuntimeSourceFixturePreservesLegacySelectionsAndSignedNetIob() {
        val fixture = requireNotNull(
            javaClass.classLoader?.getResourceAsStream(FIXTURE_PATH)
        ) { "Missing test fixture: $FIXTURE_PATH" }.bufferedReader().use { reader ->
            Gson().fromJson(reader, RuntimeSourceFixture::class.java)
        }

        val expected = RuntimeSourceFixture(
            timestamp = 1_700_000_000_000L,
            aaps = AapsFacts(isf = 2.0, cr = 10.0, netIob = -0.25),
            evidence = EvidenceFacts(isf = 3.0, cr = 12.0, confidence = 0.75, usable = true),
            copilot = CopilotFacts(isf = 4.0, cr = 11.0),
            legacySettings = LegacySettings(isf = "COPILOT", cr = "AUTO")
        )

        assertThat(fixture).isEqualTo(expected)
        assertThat(fixture.aaps.netIob).isLessThan(0.0)
        assertThat(fixture.aaps.netIob).isEqualTo(-0.25)
    }

    private data class RuntimeSourceFixture(
        val timestamp: Long,
        val aaps: AapsFacts,
        val evidence: EvidenceFacts,
        val copilot: CopilotFacts,
        val legacySettings: LegacySettings
    )

    private data class AapsFacts(
        val isf: Double,
        val cr: Double,
        val netIob: Double
    )

    private data class EvidenceFacts(
        val isf: Double,
        val cr: Double,
        val confidence: Double,
        val usable: Boolean
    )

    private data class CopilotFacts(
        val isf: Double,
        val cr: Double
    )

    private data class LegacySettings(
        val isf: String,
        val cr: String
    )

    private companion object {
        const val FIXTURE_PATH = "fixtures/runtime_source_synthetic.json"
    }
}
