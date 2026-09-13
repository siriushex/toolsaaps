package io.aaps.copilot.ui

import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.repository.AutomationRepository

internal fun resolveExternalCobForUi(
    samples: List<TelemetrySampleEntity>,
    nowTs: Long,
    freshnessMs: Long
): Double? = selectExternalCobForUi(samples, nowTs, freshnessMs)?.valueDouble

internal fun selectExternalCobForUi(
    samples: List<TelemetrySampleEntity>,
    nowTs: Long,
    freshnessMs: Long
): TelemetrySampleEntity? = AutomationRepository.selectStrictAapsCobStatic(
    samples.filter { it.timestamp > 0L && it.timestamp <= nowTs && nowTs - it.timestamp <= freshnessMs }
)
