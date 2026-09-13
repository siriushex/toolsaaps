package io.aaps.copilot.domain.model

enum class BloodGlucoseCheckStatus {
    VALID,
    REJECTED,
    STALE,
    OUT_OF_WINDOW
}

enum class GlucoseCalibrationModelType {
    OFFSET,
    AFFINE
}

enum class GlucoseCalibrationModelStatus {
    ACTIVE,
    SHADOW,
    RETIRED
}

data class BloodGlucoseCheck(
    val id: String,
    val timestamp: Long,
    val mmol: Double,
    val units: String,
    val source: String,
    val note: String?,
    val enteredAt: Long,
    val sensorSessionKey: String?,
    val lagAlignedTs: Long?,
    val matchedRawGlucose: Double?,
    val status: BloodGlucoseCheckStatus,
    val reason: String?
)

data class GlucoseCalibrationModel(
    val id: String,
    val sensorSessionKey: String,
    val createdAt: Long,
    val validFromTs: Long,
    val validToTs: Long,
    val modelType: GlucoseCalibrationModelType,
    val gain: Double,
    val offsetMmol: Double,
    val confidence: Double,
    val checkCount: Int,
    val sensorAgeHours: Double?,
    val lagMinutesAtFit: Double?,
    val status: GlucoseCalibrationModelStatus,
    val diagnosticsJson: String
)

data class GlucoseCalibrationCycleIdentity(
    val modelId: String?,
    val sensorSessionKey: String?,
    val preparedAtTs: Long
)

data class ResolvedGlucosePoint(
    val ts: Long,
    val rawMmol: Double,
    val calibratedMmol: Double,
    val gain: Double,
    val offsetMmolApplied: Double,
    val calibrationApplied: Boolean,
    val source: String,
    val quality: DataQuality,
    val calibrationModelId: String? = null
) {
    fun toDomain(): GlucosePoint = GlucosePoint(
        ts = ts,
        valueMmol = calibratedMmol,
        source = source,
        quality = quality
    )
}

data class CalibrationFitResult(
    val model: GlucoseCalibrationModel?,
    val validChecks: List<BloodGlucoseCheck>,
    val rejectedChecks: List<BloodGlucoseCheck>,
    val diagnostics: Map<String, Any?>
)
