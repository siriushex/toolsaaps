package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TargetManagerLiveStatusCodecTest {

    @Test
    fun roundTripRetainsExactBoundedStatusWithoutIdentifiersOrNotes() {
        val status = status()

        val encoded = TargetManagerLiveStatusCodec.encode(status)
        val decoded = TargetManagerLiveStatusCodec.decode(encoded)

        assertThat(decoded).isEqualTo(status)
        assertThat(encoded.toByteArray(Charsets.UTF_8).size)
            .isAtMost(TargetManagerLiveStatusCodec.MAX_JSON_BYTES)
        assertThat(encoded).doesNotContain("idempotency")
        assertThat(encoded).doesNotContain("semanticFingerprint")
        assertThat(encoded).doesNotContain("note")
    }

    @Test
    fun telemetryRowUsesStableIdentityAndRejectsEnvelopeMismatch() {
        val first = TargetManagerLiveStatusCodec.toTelemetryRow(status(timestamp = 10_000L))
        val second = TargetManagerLiveStatusCodec.toTelemetryRow(status(timestamp = 20_000L))

        assertThat(first.id).isEqualTo(TargetManagerLiveStatusCodec.ROW_ID)
        assertThat(second.id).isEqualTo(TargetManagerLiveStatusCodec.ROW_ID)
        assertThat(second.source).isEqualTo(TargetManagerLiveStatusCodec.SOURCE)
        assertThat(second.key).isEqualTo(TargetManagerLiveStatusCodec.KEY)
        assertThat(TargetManagerLiveStatusCodec.decodeTelemetryRow(second))
            .isEqualTo(status(timestamp = 20_000L))
        assertThat(
            TargetManagerLiveStatusCodec.decodeTelemetryRow(
                second.copy(timestamp = second.timestamp + 1L)
            )
        ).isNull()
        assertThat(
            TargetManagerLiveStatusCodec.decodeTelemetryRow(
                second.copy(source = "other")
            )
        ).isNull()
    }

    @Test
    fun strictDecoderRejectsUnknownDuplicateMissingNonFiniteAndUnsafeFields() {
        val valid = TargetManagerLiveStatusCodec.encode(status())

        assertThat(
            TargetManagerLiveStatusCodec.decode(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        ).isNull()
        assertThat(
            TargetManagerLiveStatusCodec.decode(valid.dropLast(1) + ",\"extra\":true}")
        ).isNull()
        assertThat(
            TargetManagerLiveStatusCodec.decode(
                valid.replace("\"timestamp\":10000", "\"timestamp\":10000,\"timestamp\":10000")
            )
        ).isNull()
        assertThat(
            TargetManagerLiveStatusCodec.decode(valid.replace(",\"reason\":\"eligible\"", ""))
        ).isNull()
        assertThat(
            TargetManagerLiveStatusCodec.decode(valid.replace("\"proposedTargetMmol\":5.8", "\"proposedTargetMmol\":1e309"))
        ).isNull()
        assertThat(
            TargetManagerLiveStatusCodec.decode(valid.replace("\"proposedTargetMmol\":5.8", "\"proposedTargetMmol\":0.0"))
        ).isNull()
        assertThat(
            TargetManagerLiveStatusCodec.decode(valid.replace("\"reason\":\"eligible\"", "\"reason\":\"free form note\""))
        ).isNull()
    }

    @Test
    fun decoderRejectsPayloadOverTwoKilobytesBeforeParsing() {
        assertThat(TargetManagerLiveStatusCodec.decode("x".repeat(2_049))).isNull()
    }

    @Test
    fun encoderRejectsNonPositiveTargetsAndUnlistedReasons() {
        assertThrows(IllegalArgumentException::class.java) {
            TargetManagerLiveStatusCodec.encode(status().copy(proposedTargetMmol = 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetManagerLiveStatusCodec.encode(status(reason = "free_form_note"))
        }
    }

    @Test
    fun everyTypedPreflightReasonIsAllowlistedAndRoundTrips() {
        TargetCommandPreflightFailure.entries.forEach { failure ->
            val status = status(reason = failure.reasonCode).copy(outcome = failure.outcome.name)

            assertThat(TargetManagerLiveStatusCodec.decode(TargetManagerLiveStatusCodec.encode(status)))
                .isEqualTo(status)
        }
    }

    private fun status(
        timestamp: Long = 10_000L,
        reason: String = "eligible"
    ) = TargetManagerLiveStatus(
        schemaVersion = TargetManagerLiveStatusCodec.SCHEMA_VERSION,
        timestamp = timestamp,
        mode = "ACTIVE",
        priorityEnabled = true,
        policyRevision = 7L,
        currentTargetMmol = null,
        proposedTargetMmol = 5.8,
        outcome = "SEND",
        reason = reason
    )
}
