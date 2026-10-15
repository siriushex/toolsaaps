package io.aaps.copilot.ui

import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.repository.AutomationRepository
import io.aaps.copilot.domain.nutrition.MealCarbLimits

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

internal fun selectEffectiveCobForUi(
    samples: List<TelemetrySampleEntity>,
    nowTs: Long,
    freshnessMs: Long
): TelemetrySampleEntity? {
    val runtime = samples.filter { it.key == "cob_effective_grams" && it.source == "copilot_runtime_cob_iob" }
    val latestTs = runtime.maxOfOrNull { it.timestamp } ?: return null
    if (latestTs <= 0L || latestTs > nowTs || nowTs - latestTs > freshnessMs) return null
    val latest = runtime.filter { it.timestamp == latestTs }
    // A rejected latest result must not resurrect an earlier estimate or substitute AAPS COB.
    if (latest.any { it.quality != "OK" || it.unit != "g" || it.valueDouble?.let { value ->
            value.isFinite() && value in 0.0..MealCarbLimits.MAX_MANUAL_MEAL_GRAMS
        } != true } || latest.map { it.valueDouble }.distinct().size != 1) return null
    return latest.minByOrNull { it.id }
}
