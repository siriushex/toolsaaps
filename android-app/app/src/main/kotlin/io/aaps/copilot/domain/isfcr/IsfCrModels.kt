package io.aaps.copilot.domain.isfcr

import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.predict.TelemetrySignal
import java.time.ZoneId

enum class IsfCrSampleType {
    ISF,
    CR
}

enum class IsfCrRuntimeMode {
    ACTIVE,
    SHADOW,
    FALLBACK
}

data class IsfCrSettings(
    val lookbackDays: Int = 365,
    val confidenceThreshold: Double = 0.55,
    val shadowMode: Boolean = true,
    val useActivityFactor: Boolean = true,
    val useManualTags: Boolean = true,
    val snapshotRetentionDays: Int = 365,
    val evidenceRetentionDays: Int = 730,
    val minIsfEvidencePerHour: Int = 2,
    val minCrEvidencePerHour: Int = 2,
    val crGrossGapMinutes: Double = 30.0,
    val crSensorBlockedRateThreshold: Double = 0.25,
    val crUamAmbiguityRateThreshold: Double = 0.60
)

data class IsfCrEvidenceSample(
    val id: String,
    val ts: Long,
    val sampleType: IsfCrSampleType,
    val hourLocal: Int,
    val dayType: DayType,
    val value: Double,
    val weight: Double,
    val qualityScore: Double,
    val context: Map<String, String>,
    val window: Map<String, Double>
)

data class IsfCrModelState(
    val updatedAt: Long,
    val hourlyIsf: List<Double?>,
    val hourlyCr: List<Double?>,
    val params: Map<String, Double>,
    val fitMetrics: Map<String, Double>
)

data class PhysioContextTag(
    val id: String,
    val tsStart: Long,
    val tsEnd: Long,
    val tagType: String,
    val severity: Double,
    val source: String,
    val note: String
)

data class IsfCrRealtimeSnapshot(
    val id: String,
    val ts: Long,
    val isfEff: Double,
    val crEff: Double,
    val isfBase: Double,
    val crBase: Double,
    val ciIsfLow: Double,
    val ciIsfHigh: Double,
    val ciCrLow: Double,
    val ciCrHigh: Double,
    val confidence: Double,
    val qualityScore: Double,
    val factors: Map<String, Double>,
    val mode: IsfCrRuntimeMode,
    val isfEvidenceCount: Int,
    val crEvidenceCount: Int,
    val reasons: List<String>
)

data class IsfCrHistoryBundle(
    val glucose: List<GlucosePoint>,
    val therapy: List<TherapyEvent>,
    val telemetry: List<TelemetrySignal>,
    val tags: List<PhysioContextTag>,
    val zoneId: ZoneId = ZoneId.systemDefault()
)
