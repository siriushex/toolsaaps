package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.predict.InsulinRuntimeSnapshot
import io.aaps.copilot.domain.predict.InsulinRuntimeSource
import io.aaps.copilot.domain.predict.InsulinRuntimeSnapshotResolver
import io.aaps.copilot.domain.predict.InsulinComponentTelemetry
import io.aaps.copilot.domain.predict.TimedInsulinValue
import io.aaps.copilot.domain.predict.MealInsulinKernelIdentity
import io.aaps.copilot.domain.predict.MealInsulinProjection

internal enum class MealInsulinAccountingStatus {
    COMPARABLE_BOLUS_ONLY, SNAPSHOT_UNAVAILABLE, COMPONENTS_UNAVAILABLE, SNAPSHOT_NOT_ALIGNED,
    REFERENCE_KERNEL_UNAVAILABLE, KERNEL_MISMATCH, INFERRED_INVENTORY, INVENTORY_UNAVAILABLE, INVENTORY_UNSUPPORTED
}

/** Numeric accounting only. Zero bolus difference does not establish basal coverage or safety. */
internal class MealInsulinAccounting private constructor(
    val status: MealInsulinAccountingStatus,
    val projection: MealInsulinProjection,
    val kernel: MealInsulinKernelIdentity,
    val snapshot: InsulinRuntimeSnapshot?,
    val bolusDifferenceUnits: Double?
) {
    val signedBasalUnits: Double? get() = snapshot?.basalIobUnits?.takeIf { it.isFinite() }
    val positiveBasalUnits: Double? get() = signedBasalUnits?.coerceAtLeast(0.0)
    val fullIobCoverage: Boolean get() = false

    companion object {
        fun compare(projection: MealInsulinProjection, kernel: MealInsulinKernelIdentity,
            snapshot: InsulinRuntimeSnapshot?, referenceKernel: MealInsulinKernelIdentity?): MealInsulinAccounting {
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
                else -> MealInsulinAccountingStatus.COMPARABLE_BOLUS_ONLY
            }
            val difference = if (status == MealInsulinAccountingStatus.COMPARABLE_BOLUS_ONLY)
                requireNotNull(snapshot?.bolusIobUnits) - projection.activeModeledUnits else null
            return MealInsulinAccounting(status, projection, kernel, snapshot, difference)
        }
    }
}
