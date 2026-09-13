package io.aaps.copilot.domain.pump

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PumpLinkHealthPolicyTest {
    private val now = 3_000_000L
    private val boot = 12
    private val sample = PumpLinkSnapshot(
        bootCount = boot,
        sessionStartedElapsedMs = 1_000L,
        sequence = 10L,
        sampledElapsedMs = now,
        supported = true,
        adapterState = PumpLinkAdapterState.ON,
        driverState = PumpLinkDriverState.IDLE,
        intentionalDisconnect = false,
        lastVerifiedStatusElapsedMs = now - 60_000L
    )

    @Test fun normalDisconnectedIdleWithRecentResponseIsHealthy() {
        assertThat(PumpLinkHealthPolicy.condition(sample, now, boot)).isEqualTo(PumpLinkCondition.HEALTHY)
    }

    @Test fun absentUnsupportedAndPreviousBootNeverClaimHealthy() {
        for (input in listOf(null, sample.copy(supported = false), sample.copy(bootCount = boot - 1))) {
            assertThat(PumpLinkHealthPolicy.condition(input, now, boot)).isEqualTo(PumpLinkCondition.UNKNOWN)
        }
    }

    @Test fun freshHeartbeatDoesNotRefreshPumpResponse() {
        assertThat(PumpLinkHealthPolicy.condition(sample.copy(lastVerifiedStatusElapsedMs = now - 1_200_000L), now, boot))
            .isEqualTo(PumpLinkCondition.STATUS_STALE)
    }

    @Test fun missingHeartbeatIsDifferentFromMissingPumpResponse() {
        assertThat(PumpLinkHealthPolicy.condition(sample, now + 450_000L, boot))
            .isEqualTo(PumpLinkCondition.AAPS_UNREACHABLE)
    }

    @Test fun missingEvidenceGetsGraceButDoesNotClaimHealthy() {
        val starting = sample.copy(sessionStartedElapsedMs = now, lastVerifiedStatusElapsedMs = null)
        assertThat(PumpLinkHealthPolicy.condition(starting, now, boot)).isEqualTo(PumpLinkCondition.VERIFYING)
        val stillNoReply = starting.copy(sampledElapsedMs = now + 1_200_000L)
        assertThat(PumpLinkHealthPolicy.condition(stillNoReply, now + 1_200_000L, boot))
            .isEqualTo(PumpLinkCondition.STATUS_STALE)
    }

    @Test fun intentionalDisconnectionDoesNotCreateRadioAlarm() {
        assertThat(PumpLinkHealthPolicy.condition(sample.copy(intentionalDisconnect = true, adapterState = PumpLinkAdapterState.OFF), now, boot))
            .isEqualTo(PumpLinkCondition.INTENTIONALLY_DISCONNECTED)
    }

    @Test fun offPermissionAndDriverErrorRemainDistinct() {
        assertThat(PumpLinkHealthPolicy.condition(sample.copy(adapterState = PumpLinkAdapterState.OFF), now, boot))
            .isEqualTo(PumpLinkCondition.BLUETOOTH_OFF)
        assertThat(PumpLinkHealthPolicy.condition(sample.copy(adapterState = PumpLinkAdapterState.PERMISSION_DENIED), now, boot))
            .isEqualTo(PumpLinkCondition.PERMISSION_MISSING)
        assertThat(PumpLinkHealthPolicy.condition(sample.copy(driverState = PumpLinkDriverState.ERROR), now, boot))
            .isEqualTo(PumpLinkCondition.DRIVER_ERROR)
    }

    @Test fun validPacketAcceptedWithoutPreviousState() {
        assertThat(PumpLinkHealthPolicy.accepts(null, sample, now, boot)).isTrue()
    }

    @Test fun reorderedDuplicateAndOldSessionRejected() {
        for (candidate in listOf(sample, sample.copy(sequence = 9), sample.copy(sessionStartedElapsedMs = 0, sequence = 999))) {
            assertThat(PumpLinkHealthPolicy.accepts(sample, candidate, now, boot)).isFalse()
        }
        assertThat(PumpLinkHealthPolicy.accepts(sample, sample.copy(sequence = 11), now, boot)).isTrue()
        assertThat(PumpLinkHealthPolicy.accepts(sample, sample.copy(sessionStartedElapsedMs = 2_000L, sequence = 1), now, boot)).isTrue()
    }

    @Test fun previousBootDoesNotRejectNewBootSession() {
        assertThat(PumpLinkHealthPolicy.accepts(sample.copy(bootCount = 11), sample.copy(sequence = 1), now, boot)).isTrue()
    }

    @Test fun invalidFutureAndStalePacketTimesRejected() {
        val invalid = listOf(
            sample.copy(bootCount = boot - 1), sample.copy(sequence = 0), sample.copy(sessionStartedElapsedMs = -1),
            sample.copy(sampledElapsedMs = now + 1), sample.copy(sessionStartedElapsedMs = now + 1),
            sample.copy(lastVerifiedStatusElapsedMs = now + 1), sample.copy(lastVerifiedStatusElapsedMs = -1),
            sample.copy(sampledElapsedMs = now - 120_001L, lastVerifiedStatusElapsedMs = null)
        )
        invalid.forEach { assertThat(PumpLinkHealthPolicy.accepts(null, it, now, boot)).isFalse() }
    }

    @Test fun nextBoundaryIsTheEarlierOfHeartbeatAndReplyExpiry() {
        assertThat(PumpLinkHealthPolicy.nextDeadline(sample, now, boot)).isEqualTo(now + 450_000L)
        assertThat(PumpLinkHealthPolicy.nextDeadline(sample.copy(lastVerifiedStatusElapsedMs = now - 1_100_000L), now, boot))
            .isEqualTo(now + 100_000L)
        assertThat(PumpLinkHealthPolicy.nextDeadline(sample, now + 450_000L, boot)).isNull()
    }

    @Test fun deadlineArithmeticDoesNotOverflow() {
        val nearLimit = sample.copy(sampledElapsedMs = Long.MAX_VALUE - 1, lastVerifiedStatusElapsedMs = Long.MAX_VALUE - 1)
        assertThat(PumpLinkHealthPolicy.nextDeadline(nearLimit, Long.MAX_VALUE - 1, boot)).isEqualTo(Long.MAX_VALUE)
    }

    @Test fun unpairedDriverDoesNotStartHeartbeatAlarm() {
        val unpaired = sample.copy(driverState = PumpLinkDriverState.UNPAIRED, lastVerifiedStatusElapsedMs = null)
        assertThat(PumpLinkHealthPolicy.condition(unpaired, now + 450_000L, boot)).isEqualTo(PumpLinkCondition.UNKNOWN)
        assertThat(PumpLinkHealthPolicy.nextDeadline(unpaired, now, boot)).isNull()
    }

    @Test fun zeroResponseTimestampIsNotVerifiedEvidence() {
        assertThat(PumpLinkHealthPolicy.accepts(null, sample.copy(lastVerifiedStatusElapsedMs = 0L), now, boot)).isFalse()
        assertThat(PumpLinkHealthPolicy.condition(sample.copy(lastVerifiedStatusElapsedMs = 0L), now, boot)).isEqualTo(PumpLinkCondition.UNKNOWN)
    }

    @Test fun responseBeforeCurrentSessionCannotBeAcceptedOrHealthy() {
        val invalid = sample.copy(sessionStartedElapsedMs = now - 30_000L)
        assertThat(PumpLinkHealthPolicy.accepts(null, invalid, now, boot)).isFalse()
        assertThat(PumpLinkHealthPolicy.accepts(sample, invalid.copy(sequence = 1), now, boot)).isFalse()
        assertThat(PumpLinkHealthPolicy.condition(invalid, now, boot)).isEqualTo(PumpLinkCondition.UNKNOWN)
        assertThat(PumpLinkHealthPolicy.nextDeadline(invalid, now, boot)).isNull()
    }

    @Test fun responseAtSessionStartIsValidEvidence() {
        val starting = sample.copy(sessionStartedElapsedMs = now, lastVerifiedStatusElapsedMs = now)
        assertThat(PumpLinkHealthPolicy.accepts(null, starting, now, boot)).isTrue()
        assertThat(PumpLinkHealthPolicy.condition(starting, now, boot)).isEqualTo(PumpLinkCondition.HEALTHY)
    }
}
