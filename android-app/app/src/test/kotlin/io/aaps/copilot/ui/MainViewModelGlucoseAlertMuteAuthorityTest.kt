package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MainViewModelGlucoseAlertMuteAuthorityTest {

    @Test
    fun overviewDisplayAndBellUseRoomCoordinatorInsteadOfDataStoreMirror() {
        val source = productionSource("io/aaps/copilot/ui/MainViewModel.kt")
        val overviewWiring = source.substringAfter("val overviewUiState:")
            .substringBefore("val forecastUiState:")
        val alertsWiring = source.substringAfter("val alertsUiState:")
            .substringBefore("val alertNavigationRequests")
        val toggleAction = source.substringAfter("fun toggleGlucoseAlerts()")
            .substringBefore("private fun muteGlucoseAlerts(")

        assertThat(source).contains("container.episodeAlertDelivery.mutedUntil")
        assertThat(source).contains(".shareAuthoritativeAlertMuteUntil(viewModelScope)")
        assertThat(overviewWiring).contains("authoritativeGlucoseAlertMuteUntil")
        assertThat(alertsWiring).contains("authoritativeGlucoseAlertMuteUntil")
        assertThat(overviewWiring).doesNotContain("container.glucoseAlertStateStore.state")
        assertThat(toggleAction).contains("container.episodeAlertDelivery.toggleFromOverview(nowTs)")
        assertThat(toggleAction).doesNotContain("container.glucoseAlertStateStore.state")
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun overviewAndAlertsConsumersShareOneUpstreamAndSeeSameTransitions() = runTest {
        val subscriptions = AtomicInteger(0)
        val upstream = MutableSharedFlow<Long>()
        val owner = SupervisorJob()
        val shared = upstream
            .onStart { subscriptions.incrementAndGet() }
            .shareAuthoritativeAlertMuteUntil(
                CoroutineScope(owner + UnconfinedTestDispatcher(testScheduler))
            )
        val overviewTransitions = mutableListOf<Long>()
        val alertsTransitions = mutableListOf<Long>()
        val overviewCollector = launch(UnconfinedTestDispatcher(testScheduler)) {
            shared.take(3).toList(overviewTransitions)
        }
        val alertsCollector = launch(UnconfinedTestDispatcher(testScheduler)) {
            shared.take(3).toList(alertsTransitions)
        }
        advanceUntilIdle()

        upstream.emit(30_000L)
        advanceUntilIdle()
        upstream.emit(0L)
        advanceUntilIdle()

        assertThat(overviewTransitions).containsExactly(0L, 30_000L, 0L).inOrder()
        assertThat(alertsTransitions).containsExactlyElementsIn(overviewTransitions).inOrder()
        assertThat(subscriptions.get()).isEqualTo(1)
        overviewCollector.join()
        alertsCollector.join()
        owner.cancel()
    }

    @Test
    fun notificationReceiverUsesSerializedRoomCoordinatorEntryPoint() {
        val source = productionSource("io/aaps/copilot/receiver/GlucoseAlertActionReceiver.kt")

        assertThat(source).contains("episodeAlertDelivery.muteFromNotification(")
        assertThat(source).doesNotContain("glucoseAlertStateStore")
    }

    private fun productionSource(relativePath: String): String {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        return File(root, "src/main/kotlin/$relativePath").readText()
    }
}
