package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class EatingSoonWiringTest {
    private fun source(path: String) = File("src/main/kotlin/io/aaps/copilot/$path").readText()

    @Test fun eatingSoonUnknownDeliveryMustNotFallBackAndResend() {
        assertThat(NightscoutActionRepository.tempTargetFailurePolicyStatic("manual:meal:dialog:eating-soon"))
            .isEqualTo(NightscoutActionRepository.TempTargetFailurePolicy.RECONCILE_UNKNOWN)
        assertThat(NightscoutActionRepository.tempTargetFailurePolicyStatic("manual:target-editor:123"))
            .isEqualTo(NightscoutActionRepository.TempTargetFailurePolicy.LEGACY_FALLBACK_ALLOWED)
    }

    @Test fun uiRequiresConfirmationAndForwardsExplicitMealOption() {
        val overview = source("ui/foundation/screens/OverviewScreen.kt")
        assertThat(overview).contains("overviewEatingSoon")
        assertThat(overview).contains("var eatingSoon by rememberSaveable { mutableStateOf(true) }")
        assertThat(overview).contains("overview_confirm_carbs_eating_soon")
        assertThat(source("ui/foundation/CopilotFoundationRoot.kt")).contains("eatingSoon, submissionId")
    }

    @Test fun explicitManualTargetUsesExistingTransportGuardWithoutAutoKeepalive() {
        val container = source("service/AppContainer.kt")
        assertThat(container).contains("suspend fun submitManualEatingSoon")
        assertThat(container).contains("manualEatingSoonPreflightFailure")
        assertThat(container).contains("EatingSoonPolicy.TARGET_MMOL")
        assertThat(container).contains("EatingSoonPolicy.DURATION_MINUTES")
        assertThat(source("data/repository/NightscoutActionRepository.kt"))
            .contains("return deliverRegisteredTempTarget(command, deliveryGuard)")
    }
}
