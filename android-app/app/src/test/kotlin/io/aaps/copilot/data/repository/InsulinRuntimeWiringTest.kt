package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.predict.InsulinRuntimeSnapshot
import io.aaps.copilot.domain.predict.InsulinRuntimeSource
import org.junit.Test

class InsulinRuntimeWiringTest {

    @Test
    fun oneCycleFanOutDeliversTheSameImmutableContextAndRevisionToEveryConsumer() {
        val cycleTimestamp = 1_760_001_200_000L
        val snapshot = InsulinRuntimeSnapshot(
            timestamp = cycleTimestamp - 60_000L,
            source = InsulinRuntimeSource.AAPS_COMPONENTS,
            netIobUnits = 0.8,
            bolusIobUnits = 0.8,
            basalIobUnits = 0.0,
            insulinActivity = 0.01,
            effectivePositiveIobUnits = 0.8,
            confidence = 1.0,
            fallbackReason = null,
            evidenceTimestamp = cycleTimestamp - 60_000L,
            therapyCoverage = 1.0
        )
        val context = AutomationRepository.buildInsulinCycleContextStatic(
            cycleTimestamp = cycleTimestamp,
            causalReferenceTimestamp = snapshot.timestamp,
            insulinSnapshot = snapshot,
            modeledActiveInsulinUnits = 0.8,
            therapyModelAvailable = true,
            freshnessMs = 15 * 60_000L
        )
        val fanOut = AutomationRepository.buildInsulinCycleFanOutStatic(context)
        val consumers = AutomationRepository.InsulinCycleConsumer.values()
            .associateWith { RecordingConsumer() }

        consumers.forEach { (consumer, fake) ->
            fake.accept(fanOut.contextFor(consumer), fanOut.revision)
        }

        assertThat(consumers.keys).containsExactly(
            AutomationRepository.InsulinCycleConsumer.FORECAST,
            AutomationRepository.InsulinCycleConsumer.UAM,
            AutomationRepository.InsulinCycleConsumer.TARGET,
            AutomationRepository.InsulinCycleConsumer.ALERTS
        )
        consumers.values.forEach { fake ->
            assertThat(fake.context).isSameInstanceAs(context)
            assertThat(fake.revision).isEqualTo(cycleTimestamp)
        }
    }

    private class RecordingConsumer {
        var context: AutomationRepository.InsulinCycleContext? = null
            private set
        var revision: Long? = null
            private set

        fun accept(value: AutomationRepository.InsulinCycleContext, cycleRevision: Long) {
            context = value
            revision = cycleRevision
        }
    }
}
