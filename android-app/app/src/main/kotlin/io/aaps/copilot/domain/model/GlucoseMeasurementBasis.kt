package io.aaps.copilot.domain.model

/** Measurement transform, separate from when a historical observation was available. */
enum class GlucoseMeasurementBasis(val id: String) {
    UNKNOWN("unknown"),
    RAW("raw-cgm-v1"),
    CONTEMPORANEOUS_CALIBRATED("contemporaneous-calibrated-cgm-v1");

    companion object {
        fun fromResolved(point: ResolvedGlucosePoint?, identity: GlucoseCalibrationCycleIdentity): GlucoseMeasurementBasis {
            point ?: return UNKNOWN
            if (point.ts <= 0 || identity.preparedAtTs <= 0 || !point.rawMmol.isFinite() || !point.calibratedMmol.isFinite() ||
                point.rawMmol !in 0.1..100.0 || point.calibratedMmol !in 0.1..100.0 ||
                !point.gain.isFinite() || point.gain <= 0 || !point.offsetMmolApplied.isFinite()) return UNKNOWN
            return when {
                !point.calibrationApplied && identity.modelId == null && point.calibrationModelId == null &&
                    point.rawMmol == point.calibratedMmol -> RAW
                point.calibrationApplied && !identity.modelId.isNullOrBlank() &&
                    point.calibrationModelId == identity.modelId -> CONTEMPORANEOUS_CALIBRATED
                else -> UNKNOWN
            }
        }
    }
}
