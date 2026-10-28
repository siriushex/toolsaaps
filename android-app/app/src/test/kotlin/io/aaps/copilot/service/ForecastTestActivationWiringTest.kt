package io.aaps.copilot.service

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ForecastTestActivationWiringTest {
    @Test fun userIntentIsReservedBeforeAsynchronousValidationAndCleanedOnJobCompletion() {
        val body = source("ui/MainViewModel.kt").substringAfter("fun setForecastTestEnabled(enabled: Boolean)")
            .substringBefore("fun setProModeEnabled(")
        assertThat(body).contains("if (!enabled) {\n            session.stop()\n            return")
        assertThat(body).contains("val request = session.requestStart() ?: return")
        assertThat(body.indexOf("session.requestStart()"))
            .isLessThan(body.indexOf("viewModelScope.launch"))
        assertThat(body.indexOf("settingsStore.settings.first()"))
            .isLessThan(body.indexOf("session.completeStart(request)"))
        assertThat(body).contains("currentCoroutineContext().ensureActive()")
        assertThat(body).contains("val id = session.completeStart(request) ?: return@launch")
        assertThat(body).contains("}.invokeOnCompletion {\n            session.cancelStart(request)")
        assertThat(body).doesNotContain("session.start()")
    }

    @Test fun ordinaryFiveMinuteLoopAndTestMinuteLoopHaveSeparateOwners() {
        val service = source("service/LocalNightscoutForegroundService.kt")
        assertThat(service).contains("PERIODIC_CYCLE_INTERVAL_MS = 5L * 60_000L")
        assertThat(service).contains("PERIODIC_CYCLE_INTERVAL_MS - (System.currentTimeMillis() % PERIODIC_CYCLE_INTERVAL_MS)")
        assertThat(service).contains("val waitMs = msUntilNextCycle.coerceIn(5_000L, PERIODIC_CYCLE_INTERVAL_MS)")
        assertThat(service).contains("delay(waitMs)")
        assertThat(service).contains("private fun ensureForecastTestCycle(app: CopilotApp)")
        val testLoop = service.substringAfter("private fun ensureForecastTestCycle(app: CopilotApp)")
            .substringBefore("private suspend fun runCurrentRuntimeCycle(")
        assertThat(testLoop).contains("state.sessionId.takeIf { state.active }")
        assertThat(testLoop).contains("session.run(")
        assertThat(testLoop).doesNotContain("pendingStart")
        assertThat(ForecastTestSession.INTERVAL_MS).isEqualTo(60_000L)
    }

    private fun source(path: String): String = File(
        checkNotNull(generateSequence(File(System.getProperty("user.dir").orEmpty()).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "src/main/kotlin").isDirectory }),
        "src/main/kotlin/io/aaps/copilot/$path"
    ).readText()
}
