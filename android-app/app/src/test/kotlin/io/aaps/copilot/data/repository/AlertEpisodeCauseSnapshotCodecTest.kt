package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.alerts.AlertCauseCode
import java.io.File
import org.junit.Test

class AlertEpisodeCauseSnapshotCodecTest {
    @Test
    fun decodesBoundedPersistedWrapperToInitialStageAndSanitizedCause() {
        val canonical =
            """{"initialStage":"WARNING_30","version":1,"cause":"SENSOR_QUALITY","confidence":"MEDIUM","factors":["SENSOR_QUALITY"],"evidence":["SENSOR_BLOCKED"],"identityStatus":"MATCHED"}"""
        val decoded = AlertEpisodeCauseSnapshotCodec.decode(canonical)

        assertThat(decoded?.initialStage).isEqualTo("WARNING_30")
        assertThat(decoded?.cause).isEqualTo(AlertCauseCode.SENSOR_QUALITY)
        assertThat(requireNotNull(decoded).canonicalEpisodeJson).isEqualTo(canonical)
    }

    @Test
    fun noncanonicalWrapperEncodingFailsClosedInsteadOfBeingNormalized() {
        val canonical =
            """{"initialStage":"WARNING_30","version":1,"cause":"SENSOR_QUALITY","confidence":"MEDIUM","factors":["SENSOR_QUALITY"],"evidence":["SENSOR_BLOCKED"],"identityStatus":"MATCHED"}"""
        val noncanonical = listOf(
            " $canonical",
            canonical.replace(",\"version\":1", ", \"version\":1"),
            canonical.dropLast(1) + ",\"unexpected\":true}"
        )

        noncanonical.forEach { json ->
            assertThat(AlertEpisodeCauseSnapshotCodec.decode(json)).isNull()
        }
    }

    @Test
    fun missingInvalidOrOversizedCauseFailsClosed() {
        val invalid = listOf(
            "{}",
            """{"initialStage":"NOT_A_STAGE","version":1,"cause":"UNKNOWN"}""",
            """{"initialStage":"WARNING_30","version":1}""",
            "x".repeat(AlertEpisodeCauseSnapshotCodec.MAX_EPISODE_JSON_CHARS + 1)
        )

        invalid.forEach { json ->
            assertThat(AlertEpisodeCauseSnapshotCodec.decode(json)).isNull()
        }
    }

    @Test
    fun deliveryAndProductionDispatchShareTheSameDecoderBoundary() {
        val stateMachine = File(
            "src/main/kotlin/io/aaps/copilot/data/repository/EpisodeAlertDeliveryStateMachine.kt"
        ).readText()
        val dispatcher = File(
            "src/main/kotlin/io/aaps/copilot/data/repository/AlertAiProductionDispatcher.kt"
        ).readText()

        assertThat(stateMachine).contains("AlertEpisodeCauseSnapshotCodec.decode")
        assertThat(stateMachine).contains("AlertEpisodeCauseSnapshotCodec.encode")
        assertThat(stateMachine).doesNotContain("private fun boundedEpisodeSnapshot(")
        assertThat(dispatcher).doesNotContain("JsonParser")
        assertThat(dispatcher).doesNotContain("localSnapshotJson")
    }
}
