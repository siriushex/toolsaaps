package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/** Supplements actual mapper/Room/fan-out tests: deleting or moving the production hook must fail. */
class DeliveryDiagnosticWiringTest {
    private val automation = File("src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt").readText()
    private val container = File("src/main/kotlin/io/aaps/copilot/service/AppContainer.kt").readText()

    private fun validWiring(automation: String, container: String): Boolean {
        val start = automation.indexOf("publishAlerts = { alertSensitivityRuntime, unifiedUam ->")
        val end = automation.indexOf("publishAcceptedForecast =", start)
        if (start < 0 || end <= start) return false
        val body = automation.substring(start, end)
        val hook = "publishDeliveryDiagnostic("
        return body.contains(hook) && body.indexOf(hook) > body.indexOf("evaluateAndPublishGlucoseAlerts(") &&
            Regex("\\bpublishDeliveryDiagnostic\\(").findAll(automation).count() == 2 &&
            container.contains("deliveryDiagnostic = deliveryDiagnostic") &&
            container.contains("db, episodeAlertDelivery, deliveryDiagnosticNotifier::post") &&
            container.substringAfter("clearRiskSideEffects = {").substringBefore("postCommitObserver =")
                .contains("DeliveryDiagnosticNotifier.clear")
    }

    @Test fun productionHookDiAndGlobalMuteRemainConnected() {
        assertThat(validWiring(automation, container)).isTrue()
    }

    @Test fun guardDetectsMissingDiAndMovedOrDeletedHook() {
        assertThat(validWiring(automation, container.replace("deliveryDiagnostic = deliveryDiagnostic", ""))).isFalse()
        val moved = automation.replaceFirst("publishDeliveryDiagnostic(", "removedDiagnosticHook(")
        assertThat(validWiring(moved, container)).isFalse()
        assertThat(validWiring(moved + "\npublishDeliveryDiagnostic()", container)).isFalse()
    }
}
