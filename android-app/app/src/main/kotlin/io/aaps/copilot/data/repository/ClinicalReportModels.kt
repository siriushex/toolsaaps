package io.aaps.copilot.data.repository

import com.google.gson.annotations.SerializedName
import io.aaps.copilot.domain.eating.ProbableEatingWindow

data class ClinicalGlucosePoint(
    val ts: Long,
    val mmol: Double
)

data class ClinicalTherapyPoint(
    val ts: Long,
    val insulinU: Double? = null,
    val carbsG: Double? = null,
    val syntheticUam: Boolean = false,
    val insulinEvidence: ClinicalInsulinEvidence? = null,
    val contextKind: ClinicalTherapyContextKind? = null
) {
    @field:Transient
    internal var internalEventId: String? = null
}

enum class ClinicalInsulinEvidence(val wireCode: Int) {
    CONFIRMED(1),
    IOB_DERIVED(2)
}

enum class ClinicalTherapyContextKind(val wireCode: Int) {
    INFUSION_SET_CHANGE(1),
    SENSOR_CHANGE(2),
    INSULIN_REFILL(3),
    PUMP_BATTERY_CHANGE(4),
    EXERCISE(5),
    PROFILE_SWITCH(6)
}

data class ClinicalTargetPoint(
    val ts: Long,
    val lowMmol: Double?,
    val highMmol: Double?,
    val durationMs: Long? = null,
    val endTs: Long? = null,
    val cancelled: Boolean? = null
) {
    @field:Transient
    internal var internalEventId: String? = null

    constructor(ts: Long, targetMmol: Double) : this(
        ts = ts,
        lowMmol = targetMmol,
        highMmol = targetMmol
    )

    val targetMmol: Double?
        get() = when {
            lowMmol != null && highMmol != null -> (lowMmol + highMmol) / 2.0
            lowMmol != null -> lowMmol
            else -> highMmol
        }
}

internal data class ClinicalTherapyEvent(
    val rowId: String,
    val point: ClinicalTherapyPoint,
    val canonicalCarbId: Long? = null,
    val canonicalCarbRevision: String? = null
)

internal data class ClinicalTargetEvent(
    val rowId: String,
    val point: ClinicalTargetPoint
)

data class ClinicalTargetInterval(
    val startTs: Long,
    val endTs: Long,
    val lowMmol: Double,
    val highMmol: Double
) {
    val midpointMmol: Double
        get() = (lowMmol + highMmol) / 2.0
}

internal data class ResolvedClinicalTargets(
    val events: List<ClinicalTargetEvent>,
    val intervals: List<ClinicalTargetInterval>
)

data class ClinicalForecastPoint(
    val ts: Long,
    val horizonMin: Int,
    val mmol: Double,
    val lower: Double,
    val upper: Double
)

/** Accuracy is emitted only when at least three canonical outcome pairs exist. */
data class ClinicalForecastQuality(
    val horizonMin: Int,
    val sampleCount: Int,
    val meanAbsoluteErrorMmol: Double? = null,
    val ciCoveragePct: Double? = null
)

data class ClinicalTelemetryPoint(
    val ts: Long,
    val key: String,
    val value: Double,
    val quality: String,
    val origin: ClinicalTelemetryOrigin = ClinicalTelemetryOrigin.OTHER
)

enum class ClinicalTelemetryOrigin(val wireCode: Int) {
    OTHER(0),
    AAPS(1),
    LOCAL_ACTIVITY(2),
    HEALTH_CONNECT(3),
    COPILOT_RUNTIME(4)
}

data class ClinicalDetailWindow(
    val fromTs: Long,
    val throughTs: Long,
    val glucose: List<ClinicalGlucosePoint>,
    val calibratedGlucose: List<ClinicalGlucosePoint>,
    val therapy: List<ClinicalTherapyPoint>,
    val targets: List<ClinicalTargetPoint>,
    val forecasts: List<ClinicalForecastPoint>,
    val telemetry: List<ClinicalTelemetryPoint>,
    val forecastQuality: List<ClinicalForecastQuality> = emptyList()
)

data class ClinicalHourlyMetric(
    val hour: Int,
    val sampleCount: Int,
    val meanMmol: Double?,
    val medianMmol: Double?
)

data class ClinicalDataQuality(
    val expectedBuckets: Int,
    val coveredBuckets: Int,
    val missingBuckets: Int,
    val maxGapMinutes: Int?,
    val rejected: ClinicalRejectionCounts = ClinicalRejectionCounts()
)

data class ClinicalRejectionCounts(
    val glucose: Int = 0,
    val therapy: Int = 0,
    val target: Int = 0,
    val forecast: Int = 0,
    val telemetry: Int = 0
)

enum class ClinicalInsulinTotalSource {
    COPILOT_EVENTS,
    @SerializedName(value = "AAPS_RAW_HISTORY", alternate = ["AAPS_TDD"])
    AAPS_RAW_HISTORY
}

data class ClinicalActivitySummary(
    val coveragePct: Double = 0.0,
    val steps: Double? = null,
    val distanceKm: Double? = null,
    val activeMinutes: Double? = null,
    val activeCaloriesKcal: Double? = null,
    val meanActivityRatio: Double? = null,
    val maxActivityRatio: Double? = null
)

/** An inclusive advisory range; it is not a nutrition or therapy prescription. */
data class ClinicalRange(
    val minimum: Double,
    val maximum: Double
) {
    init {
        require(minimum.isFinite() && maximum.isFinite() && minimum <= maximum)
    }
}

enum class ClinicalMealEnergySource {
    MANUAL_MEAL_ENTRY,
    MIXED_MANUAL_AND_ESTIMATED,
    ESTIMATED_FROM_CARB_SHARE,
    NOT_AVAILABLE
}

data class ClinicalMealEnergySummary(
    val carbohydrateEnergyKcal: Double,
    /** Exact calories the user recorded for trusted concrete meals in this period. */
    val manualMealEnergyKcal: Double? = null,
    val estimatedTotalMealEnergyKcal: ClinicalRange? = null,
    val source: ClinicalMealEnergySource = ClinicalMealEnergySource.NOT_AVAILABLE,
    val netEnergyKcal: ClinicalRange? = null
)

/**
 * Local profile metadata for the report. Its fields are stripped from the
 * remote payload unless the user has separately consented to profile sharing.
 */
data class ClinicalEnergyProfileSummary(
    val derivedAgeYears: Int? = null,
    val sex: String? = null,
    val foodProfile: String? = null,
    val foodProfileSource: String = "DEFAULT",
    val foodDurationMinutes: Int? = null,
    val activityProfile: String? = null,
    val activityProfileSource: String = "DEFAULT",
    val confidence: String = "INSUFFICIENT_DATA",
    val evidenceDays: Int = 0,
    val maintenanceEnergyKcal: ClinicalRange? = null,
    val calorieGoalMode: String = "OFF",
    val shareProfileWithAi: Boolean = true
)

/** Typed schedule data only. Event titles and identifiers are deliberately absent. */
data class ClinicalPlannedActivitySummary(
    val type: String,
    val intensity: String,
    val plannedStartMs: Long,
    val plannedDurationMinutes: Int,
    val observedMinutes: Int? = null,
    val adherence: String = "NOT_MEASURED",
    val targetDecision: String? = null,
    val targetBlockers: List<String> = emptyList()
)

internal val CLINICAL_PLANNED_ACTIVITY_ORDER = Comparator<ClinicalPlannedActivitySummary> { left, right ->
    val scalarOrder = compareValuesBy(
        left,
        right,
        ClinicalPlannedActivitySummary::plannedStartMs,
        ClinicalPlannedActivitySummary::type,
        ClinicalPlannedActivitySummary::intensity,
        ClinicalPlannedActivitySummary::plannedDurationMinutes,
        ClinicalPlannedActivitySummary::observedMinutes,
        ClinicalPlannedActivitySummary::adherence,
        ClinicalPlannedActivitySummary::targetDecision
    )
    if (scalarOrder != 0) {
        scalarOrder
    } else {
        compareStringLists(left.targetBlockers, right.targetBlockers)
    }
}

private fun compareStringLists(left: List<String>, right: List<String>): Int {
    for (index in 0 until minOf(left.size, right.size)) {
        val order = left[index].compareTo(right[index])
        if (order != 0) return order
    }
    return left.size.compareTo(right.size)
}

data class ClinicalBasalContextSummary(
    val coveragePct: Double = 0.0,
    val meanProfileRateUph: Double? = null,
    val minProfileRateUph: Double? = null,
    val maxProfileRateUph: Double? = null,
    val meanProfilePercent: Double? = null
)

data class ClinicalTherapyContextSummary(
    val infusionSetChanges: Int = 0,
    val sensorChanges: Int = 0,
    val insulinRefills: Int = 0,
    val pumpBatteryChanges: Int = 0,
    val exerciseEvents: Int = 0,
    val profileSwitches: Int = 0
)

data class ClinicalAapsTddPeriod(
    val fromTs: Long,
    val throughTs: Long,
    val basalInsulinU: Double,
    val bolusInsulinU: Double,
    val totalInsulinU: Double,
    val carbsG: Double
)

data class ClinicalAapsTddSnapshot(
    val generatedAt: Long,
    val detail24h: ClinicalAapsTddPeriod?,
    val period7d: ClinicalAapsTddPeriod?,
    val period30d: ClinicalAapsTddPeriod?
)

data class ClinicalPeriodSummary(
    val days: Int,
    val fromTs: Long,
    val throughTs: Long,
    val coveragePct: Double,
    val meanMmol: Double?,
    val medianMmol: Double?,
    val coefficientOfVariationPct: Double?,
    val timeBelow4Pct: Double?,
    val timeInRangePct: Double?,
    val timeAboveRangePct: Double?,
    val totalInsulinU: Double,
    val totalCarbsG: Double,
    val meanTargetMmol: Double?,
    val weekdayPattern: List<ClinicalHourlyMetric>,
    val weekendPattern: List<ClinicalHourlyMetric>,
    val quality: ClinicalDataQuality,
    val confirmedInsulinU: Double = totalInsulinU,
    val estimatedInsulinU: Double = 0.0,
    val deliveredBasalInsulinU: Double? = null,
    val deliveredBolusInsulinU: Double? = null,
    val insulinTotalSource: ClinicalInsulinTotalSource =
        ClinicalInsulinTotalSource.COPILOT_EVENTS,
    val enteredCarbsG: Double = totalCarbsG,
    val uamCarbsG: Double = 0.0,
    val aapsCarbsG: Double? = null,
    val insulinEventCount: Int = 0,
    val estimatedInsulinEventCount: Int = 0,
    val enteredCarbEventCount: Int = 0,
    val uamCarbEventCount: Int = 0,
    val activity: ClinicalActivitySummary = ClinicalActivitySummary(),
    val mealEnergy: ClinicalMealEnergySummary = ClinicalMealEnergySummary(
        carbohydrateEnergyKcal = 0.0
    ),
    val basalContext: ClinicalBasalContextSummary = ClinicalBasalContextSummary(),
    val therapyContext: ClinicalTherapyContextSummary = ClinicalTherapyContextSummary(),
    val probableMealWindows: List<ProbableEatingWindow> = emptyList(),
    val recentProbableMealWindows: List<ProbableEatingWindow> = emptyList()
) {
    /** AAPS records carbohydrate grams, not complete meal energy from fat and protein. */
    val carbohydrateEnergyKcal: Double
        get() = totalCarbsG
            .takeIf { it.isFinite() && it >= 0.0 }
            ?.times(CARBOHYDRATE_KCAL_PER_GRAM)
            ?: 0.0

    companion object {
        const val CARBOHYDRATE_KCAL_PER_GRAM = 4.0
    }
}

data class ClinicalCurrentSnapshot(
    val rawGlucoseMmol: Double? = null,
    val calibratedGlucoseMmol: Double? = null,
    val glucoseSampleAgeMinutes: Double? = null,
    val prediction30mMmol: Double? = null,
    val prediction30mAgeMinutes: Double? = null,
    val effectiveIobUnits: Double? = null,
    val effectiveCobGrams: Double? = null,
    val selectedIsfMmolPerUnit: Double? = null,
    val selectedIsfSourceCode: Double? = null,
    val selectedCrGramsPerUnit: Double? = null,
    val selectedCrSourceCode: Double? = null,
    val sensitivityCycleId: String? = null,
    val sensitivitySettingsRevision: Long? = null,
    val selectedIsfSource: String? = null,
    val selectedCrSource: String? = null,
    val uamActiveFlag: Double? = null,
    val uamEquivalentCarbsGrams: Double? = null,
    val uamConfidence: Double? = null,
    val sensorQualityScore: Double? = null,
    val sensorBlockedFlag: Double? = null,
    val sensorAgeHours: Double? = null,
    val sensorLagMinutes: Double? = null,
    val activityRatio: Double? = null,
    val stepsCount: Double? = null,
    val activeTargetLowMmol: Double? = null,
    val activeTargetHighMmol: Double? = null
)

data class ClinicalReportDataset(
    val schemaVersion: Int,
    val generatedAt: Long,
    val zoneId: String,
    val detail24h: ClinicalDetailWindow,
    val glucose7d: List<ClinicalGlucosePoint>,
    val therapy7d: List<ClinicalTherapyPoint>,
    val targets7d: List<ClinicalTargetPoint>,
    val glucose30d: List<ClinicalGlucosePoint>,
    val therapy30d: List<ClinicalTherapyPoint>,
    val targets30d: List<ClinicalTargetPoint>,
    val summary7d: ClinicalPeriodSummary,
    val summary30d: ClinicalPeriodSummary,
    val summary24h: ClinicalPeriodSummary? = null,
    val currentSnapshot: ClinicalCurrentSnapshot = ClinicalCurrentSnapshot(),
    val energyProfile: ClinicalEnergyProfileSummary? = null,
    val plannedActivities: List<ClinicalPlannedActivitySummary> = emptyList(),
    val eventSummaries: List<ClinicalEventSummary> = emptyList(),
    val eventTypeAssociations: List<ClinicalEventTypeAssociation> = emptyList()
)

data class AlertAiContextDataset(
    val generatedAt: Long,
    val detail24h: ClinicalDetailWindow,
    val glucose14d: List<ClinicalGlucosePoint>,
    val therapy14d: List<ClinicalTherapyPoint>,
    val events14d: List<ClinicalEventSummary>,
    @Transient
    val retainedWorkBudget: AlertAiRetainedDerivedBudget? = null
)

data class ClinicalEventSummary(
    val localId: String,
    val type: String,
    val subtype: String,
    val startTs: Long,
    val endTs: Long,
    val severity: String,
    val source: String,
    val title: String,
    val note: String?,
    val status: String,
    val provenance: String,
    val syntheticUam: Boolean = false
)

data class ClinicalContextEvent(
    val type: String,
    val subtype: String?,
    val startTs: Long,
    val endTs: Long?,
    val severity: String,
    val source: String,
    val title: String?,
    val note: String?
)

data class ClinicalAssociationMetric(
    val sampleCount: Int,
    val mean: Double?,
    val unit: String
) {
    init {
        require(sampleCount >= 0)
        require(mean == null || mean.isFinite())
        require(unit.isNotBlank())
    }
}

data class ClinicalEventTypeAssociation(
    val type: String,
    val eventCount: Int,
    val totalDurationMinutes: Long,
    val glucose: ClinicalAssociationMetric,
    val trend: ClinicalAssociationMetric,
    val uam: ClinicalAssociationMetric,
    val isf: ClinicalAssociationMetric,
    val cr: ClinicalAssociationMetric,
    val forecastError: ClinicalAssociationMetric,
    val causal: Boolean = false
) {
    init {
        require(type.isNotBlank())
        require(eventCount > 0)
        require(totalDurationMinutes >= 0L)
        require(!causal) { "Event relationships are associations, not causal claims" }
    }
}

data class ClinicalReportPayload(
    val dataset: ClinicalReportDataset,
    val compactJson: String,
    val sha256: String
)
