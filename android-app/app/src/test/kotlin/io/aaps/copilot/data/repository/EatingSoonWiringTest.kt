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

    @Test fun uiSendsOnceFromMealDialogAndForwardsExplicitMealOption() {
        val overview = source("ui/foundation/screens/OverviewScreen.kt")
        val dialog = source("ui/foundation/components/MealEntryDialog.kt")
        assertThat(overview).contains("MealEntryDialog(")
        assertThat(overview).contains("meal.energyKcal, meal.eatingSoon, meal.submissionId")
        assertThat(dialog).contains("overviewEatingSoon")
        assertThat(dialog).contains("var eatingSoon by rememberSaveable { mutableStateOf(true) }")
        assertThat(dialog).doesNotContain("overviewConfirmMeal")
        assertThat(dialog).contains("var manualCarbs by rememberSaveable { mutableStateOf(false) }")
        assertThat(dialog).contains("onConfirm(finalMeal)")
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
