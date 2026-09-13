package io.aaps.copilot

import io.aaps.copilot.domain.predict.SensitivityMetricDecision
import io.aaps.copilot.domain.predict.SensitivityResolvedSource
import io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer
import io.aaps.copilot.domain.predict.SensitivityRuntimeConsumerContext
import io.aaps.copilot.domain.predict.SensitivityRuntimeSnapshot
import io.aaps.copilot.domain.predict.SensitivitySourcePreference

internal fun testSensitivityRuntimeSnapshot(
    cycleId: String = "test-sensitivity-cycle",
    settingsRevision: Long = 1L,
    isf: Double = 3.0,
    cr: Double = 10.0,
    timestamp: Long = 1_700_000_000_000L
) = SensitivityRuntimeSnapshot(
    settingsRevision = settingsRevision,
    forecastCycleId = cycleId,
    timestamp = timestamp,
    isf = testSensitivityDecision(isf),
    cr = testSensitivityDecision(cr)
)

internal fun testSensitivityRuntimeContext(
    consumer: SensitivityRuntimeConsumer,
    snapshot: SensitivityRuntimeSnapshot = testSensitivityRuntimeSnapshot()
) = SensitivityRuntimeConsumerContext(consumer = consumer, snapshot = snapshot)

internal fun testTargetSensitivityRuntimeContext() = testSensitivityRuntimeContext(
    consumer = SensitivityRuntimeConsumer.TARGET_MANAGER
)

private fun testSensitivityDecision(value: Double) = SensitivityMetricDecision(
    requested = SensitivitySourcePreference.COPILOT,
    resolved = SensitivityResolvedSource.COPILOT_NATIVE,
    rawAaps = null,
    rawEvidence = null,
    rawCopilot = value,
    blended = null,
    effective = value,
    confidence = 1.0,
    fallbackReason = null
)
