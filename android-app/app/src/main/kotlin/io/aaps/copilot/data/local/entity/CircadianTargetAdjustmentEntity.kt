package io.aaps.copilot.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "circadian_target_adjustments",
    foreignKeys = [
        ForeignKey(
            entity = CircadianTargetRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("runId"),
        Index(value = ["scheduleRevision", "dayType", "hour"]),
        Index("validUntil")
    ]
)
data class CircadianTargetAdjustmentEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val scheduleRevision: Long,
    val dayType: String,
    val hour: Int,
    val manualTargetMmol: Double,
    val desiredDeltaMmol: Double,
    val appliedDeltaMmol: Double,
    val medianMmol: Double,
    val p25: Double,
    val p75: Double,
    val trustedLowCount: Int,
    val sampleCount: Int,
    val activeDays: Int,
    val qualityScore: Double,
    val sensorTrustedShare: Double,
    val generatedAt: Long,
    val validUntil: Long,
    val status: String,
    val reasonCodesJson: String,
    val lowerTailReplayErrorMmol: Double? = null,
    val stepBaselineTrustedLowCount: Int? = null,
    val stepBaselineVariabilityIqrMmol: Double? = null,
    val stepBaselineLowerTailReplayErrorMmol: Double? = null
)
