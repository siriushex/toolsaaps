package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.predict.InsulinRuntimeSnapshot
import io.aaps.copilot.domain.predict.InsulinRuntimeSource
import io.aaps.copilot.domain.predict.InsulinRuntimeSnapshotResolver
import io.aaps.copilot.domain.predict.InsulinComponentTelemetry
import io.aaps.copilot.domain.predict.TimedInsulinValue
import io.aaps.copilot.domain.predict.MealInsulinKernelIdentity
import io.aaps.copilot.domain.predict.MealInsulinProjection
import io.aaps.copilot.domain.predict.MealBasalProjection

internal enum class MealInsulinAccountingStatus {
    COMPARABLE_BOLUS_ONLY, COMPARABLE_COMPONENTS, BASAL_PROJECTION_MISMATCH,
    SNAPSHOT_UNAVAILABLE, COMPONENTS_UNAVAILABLE, SNAPSHOT_NOT_ALIGNED,
    REFERENCE_KERNEL_UNAVAILABLE, KERNEL_MISMATCH, INFERRED_INVENTORY, INVENTORY_UNAVAILABLE, INVENTORY_UNSUPPORTED
}

/** Numeric accounting only. Agreement does not establish upstream delivery completeness or safety. */
internal class MealInsulinAccounting private constructor(
    val status: MealInsulinAccountingStatus,
    val projection: MealInsulinProjection,
    val kernel: MealInsulinKernelIdentity,
    val snapshot: InsulinRuntimeSnapshot?,
    val bolusDifferenceUnits: Double?,
    val basalProjection: MealBasalProjection?,
    val basalDifferenceUnits: Double?,
    val netDifferenceUnits: Double?
) {
    val signedBasalUnits: Double? get() = snapshot?.basalIobUnits?.takeIf { it.isFinite() }
    /** Positive part of the reported net scalar, not the sum of positive historical deviations. */
    val positiveBasalUnits: Double? get() = signedBasalUnits?.coerceAtLeast(0.0)
    val fullIobCoverage: Boolean get() = false

    companion object {
        fun compare(projection: MealInsulinProjection, kernel: MealInsulinKernelIdentity,
            snapshot: InsulinRuntimeSnapshot?, referenceKernel: MealInsulinKernelIdentity?,
            basal: MealBasalProjection? = null): MealInsulinAccounting {
            val resolved = snapshot?.takeIf { it.source == InsulinRuntimeSource.AAPS_COMPONENTS }?.let { input ->
                fun timed(value: Double?) = value?.let { TimedInsulinValue(it, input.timestamp) }
                InsulinRuntimeSnapshotResolver.resolve(projection.predictionAtMs, components = InsulinComponentTelemetry(
                    timed(input.netIobUnits), timed(input.bolusIobUnits), timed(input.basalIobUnits), timed(input.insulinActivity))).snapshot
            }
            val status = when {
                snapshot == null -> MealInsulinAccountingStatus.SNAPSHOT_UNAVAILABLE
                referenceKernel == null -> MealInsulinAccountingStatus.REFERENCE_KERNEL_UNAVAILABLE
                referenceKernel != kernel -> MealInsulinAccountingStatus.KERNEL_MISMATCH
                snapshot.timestamp != projection.predictionAtMs -> MealInsulinAccountingStatus.SNAPSHOT_NOT_ALIGNED
                resolved == null || resolved != snapshot ->
                    MealInsulinAccountingStatus.COMPONENTS_UNAVAILABLE
                projection.modeledEventCount == 0 -> MealInsulinAccountingStatus.INVENTORY_UNAVAILABLE
                projection.inferredEventCount > 0 -> MealInsulinAccountingStatus.INFERRED_INVENTORY
                projection.unsupportedEventCount > 0 -> MealInsulinAccountingStatus.INVENTORY_UNSUPPORTED
                basal != null && (basal.kernel != kernel || basal.predictionAtMs != projection.predictionAtMs ||
                    basal.isfMmolPerUnit != projection.isfMmolPerUnit ||
                    basal.delivered.stepUnits.size != projection.stepEffectsMmol.size) ->
                    MealInsulinAccountingStatus.BASAL_PROJECTION_MISMATCH
                basal != null -> MealInsulinAccountingStatus.COMPARABLE_COMPONENTS
                else -> MealInsulinAccountingStatus.COMPARABLE_BOLUS_ONLY
            }
            val comparable = status in setOf(MealInsulinAccountingStatus.COMPARABLE_BOLUS_ONLY,
                MealInsulinAccountingStatus.COMPARABLE_COMPONENTS)
            val difference = if (comparable)
                requireNotNull(snapshot?.bolusIobUnits) - projection.activeModeledUnits else null
            val basalDifference = if (status == MealInsulinAccountingStatus.COMPARABLE_COMPONENTS)
                requireNotNull(snapshot?.basalIobUnits) - requireNotNull(basal).signedDeviationActiveUnits else null
            val netDifference = if (status == MealInsulinAccountingStatus.COMPARABLE_COMPONENTS)
                requireNotNull(snapshot?.netIobUnits) - (projection.activeModeledUnits + requireNotNull(basal).signedDeviationActiveUnits) else null
            return MealInsulinAccounting(status, projection, kernel, snapshot, difference, basal, basalDifference, netDifference)
        }
    }
}
