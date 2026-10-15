package io.aaps.copilot.domain.model

import org.junit.Assert.*
import org.junit.Test

class GlucoseMeasurementBasisTest {
    private val point = ResolvedGlucosePoint(100, 6.0, 6.0, 1.0, 0.0, false, "sensor", DataQuality.OK)
    private val identity = GlucoseCalibrationCycleIdentity(null, "session-a", 110)

    @Test fun rawNeedsAnUnchangedMeasurementAndNoCalibrationModel() {
        assertEquals(GlucoseMeasurementBasis.RAW, GlucoseMeasurementBasis.fromResolved(point, identity))
        assertEquals(GlucoseMeasurementBasis.UNKNOWN, GlucoseMeasurementBasis.fromResolved(point.copy(calibratedMmol = 6.1), identity))
        assertEquals(GlucoseMeasurementBasis.UNKNOWN, GlucoseMeasurementBasis.fromResolved(point, identity.copy(modelId = "model-a")))
    }

    @Test fun calibratedBasisNeedsTheActualAppliedModelNotJustAnIdentityLabel() {
        val calibrated = point.copy(calibratedMmol = 7.0, offsetMmolApplied = 1.0,
            calibrationApplied = true, calibrationModelId = "model-a")
        assertEquals(GlucoseMeasurementBasis.CONTEMPORANEOUS_CALIBRATED,
            GlucoseMeasurementBasis.fromResolved(calibrated, identity.copy(modelId = "model-a")))
        for (id in listOf(null, "", "model-b")) assertEquals(GlucoseMeasurementBasis.UNKNOWN,
            GlucoseMeasurementBasis.fromResolved(calibrated, identity.copy(modelId = id)))
    }

    @Test fun invalidOrAbsentResolvedEvidenceCannotAcquireAKnownBasis() {
        assertEquals(GlucoseMeasurementBasis.UNKNOWN, GlucoseMeasurementBasis.fromResolved(null, identity))
        for (invalid in listOf(point.copy(ts = 0), point.copy(rawMmol = Double.NaN), point.copy(gain = 0.0),
            point.copy(calibratedMmol = Double.POSITIVE_INFINITY), point.copy(offsetMmolApplied = Double.NaN)))
            assertEquals(GlucoseMeasurementBasis.UNKNOWN, GlucoseMeasurementBasis.fromResolved(invalid, identity))
    }
}
