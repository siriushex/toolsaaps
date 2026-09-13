package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.target.TargetManagerMode
import org.junit.Test

class NightscoutActionRepositoryTargetOwnershipTest {

    @Test
    fun transportBlocksEveryTherapyWriterBeforeForegroundArm() {
        assertThat(
            NightscoutActionRepository.therapyActionBootstrapBlockReasonStatic(
                therapyActionsArmed = false
            )
        ).isEqualTo("therapy_actions_not_armed")
        assertThat(
            NightscoutActionRepository.therapyActionBootstrapBlockReasonStatic(
                therapyActionsArmed = true
            )
        ).isNull()
    }

    @Test
    fun transportBlocksLegacyAutomaticPrefixWhenManagerIsActive() {
        val reason = NightscoutActionRepository.automaticTargetOwnershipBlockReasonStatic(
            mode = TargetManagerMode.ACTIVE,
            idempotencyKey = "AdaptiveTargetController.v1:123:5.5:30"
        )

        assertThat(reason).isEqualTo("legacy_automatic_writer_blocked_active_manager")
    }

    @Test
    fun transportStillAllowsManualPrefixWhenManagerIsActive() {
        val reason = NightscoutActionRepository.automaticTargetOwnershipBlockReasonStatic(
            mode = TargetManagerMode.ACTIVE,
            idempotencyKey = "manual:target-editor:123"
        )

        assertThat(reason).isNull()
    }

    @Test
    fun transportAllowsManagerPrefixWhenManagerIsActive() {
        val reason = NightscoutActionRepository.automaticTargetOwnershipBlockReasonStatic(
            mode = TargetManagerMode.ACTIVE,
            idempotencyKey = "TargetManager.v1:semantic-fingerprint"
        )

        assertThat(reason).isNull()
    }

    @Test
    fun transportPreservesLegacyWriterBeforeActivation() {
        val reason = NightscoutActionRepository.automaticTargetOwnershipBlockReasonStatic(
            mode = TargetManagerMode.SHADOW,
            idempotencyKey = "PatternAdaptiveTarget.v1:123:5.5:60"
        )

        assertThat(reason).isNull()
    }

    @Test
    fun managerTransportFailureRequiresReconciliationWithoutBroadcastFallback() {
        val policy = NightscoutActionRepository.tempTargetFailurePolicyStatic(
            "TargetManager.v1:semantic-fingerprint"
        )

        assertThat(policy).isEqualTo(
            NightscoutActionRepository.TempTargetFailurePolicy.RECONCILE_UNKNOWN
        )
    }

    @Test
    fun legacyTransportFailureKeepsPrePromotionFallbackBehavior() {
        val policy = NightscoutActionRepository.tempTargetFailurePolicyStatic(
            "AdaptiveTargetController.v1:123:5.5:30"
        )

        assertThat(policy).isEqualTo(
            NightscoutActionRepository.TempTargetFailurePolicy.LEGACY_FALLBACK_ALLOWED
        )
    }
}
