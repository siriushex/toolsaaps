package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventStatus
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventSeverity
import io.aaps.copilot.domain.events.EventSource
import io.aaps.copilot.domain.model.TherapyComponentPolicy
import io.aaps.copilot.domain.profile.EnergyProfileFoodDurationPolicy
import io.aaps.copilot.report.ClinicalReportDocument

internal object ClinicalPdfSnapshotValidator {
    fun requireValid(dataset: ClinicalReportDataset) {
        require(dataset.generatedAt > 0L)
        require(dataset.detail24h.fromTs > 0L)
        require(dataset.detail24h.fromTs <= dataset.detail24h.throughTs)
        val summary24h = requireNotNull(dataset.summary24h)

        requireRowsWithin(
            dataset.detail24h.fromTs,
            dataset.detail24h.throughTs,
            dataset.detail24h.glucose,
            ClinicalGlucosePoint::ts
        )
        requireRowsWithin(
            dataset.detail24h.fromTs,
            dataset.detail24h.throughTs,
            dataset.detail24h.calibratedGlucose,
            ClinicalGlucosePoint::ts
        )
        requireRowsWithin(
            dataset.detail24h.fromTs,
            dataset.detail24h.throughTs,
            dataset.detail24h.therapy,
            ClinicalTherapyPoint::ts
        )
        requireRowsWithin(
            dataset.detail24h.fromTs,
            dataset.detail24h.throughTs,
            dataset.detail24h.targets,
            ClinicalTargetPoint::ts
        )
        requireRowsWithin(
            dataset.detail24h.fromTs,
            dataset.detail24h.throughTs,
            dataset.detail24h.forecasts,
            ClinicalForecastPoint::ts
        )
        requireRowsWithin(
            dataset.detail24h.fromTs,
            dataset.detail24h.throughTs,
            dataset.detail24h.telemetry,
            ClinicalTelemetryPoint::ts
        )
        requireRowsWithin(
            dataset.summary7d.fromTs,
            dataset.summary7d.throughTs,
            dataset.glucose7d,
            ClinicalGlucosePoint::ts
        )
        requireRowsWithin(
            dataset.summary7d.fromTs,
            dataset.summary7d.throughTs,
            dataset.therapy7d,
            ClinicalTherapyPoint::ts
        )
        requireRowsWithin(
            dataset.summary7d.fromTs,
            dataset.summary7d.throughTs,
            dataset.targets7d,
            ClinicalTargetPoint::ts
        )
        requireRowsWithin(
            dataset.summary30d.fromTs,
            dataset.summary30d.throughTs,
            dataset.glucose30d,
            ClinicalGlucosePoint::ts
        )
        requireRowsWithin(
            dataset.summary30d.fromTs,
            dataset.summary30d.throughTs,
            dataset.therapy30d,
            ClinicalTherapyPoint::ts
        )
        requireRowsWithin(
            dataset.summary30d.fromTs,
            dataset.summary30d.throughTs,
            dataset.targets30d,
            ClinicalTargetPoint::ts
        )

        validateGlucose(dataset.detail24h.glucose)
        validateGlucose(dataset.detail24h.calibratedGlucose)
        validateTherapy(dataset.detail24h.therapy)
        validateTargets(dataset.detail24h.targets)
        validateForecasts(dataset.detail24h.forecasts)
        validateTelemetry(dataset.detail24h.telemetry)
        validateForecastQuality(dataset.detail24h.forecastQuality)
        validateGlucose(dataset.glucose7d)
        validateTherapy(dataset.therapy7d)
        validateTargets(dataset.targets7d)
        validateGlucose(dataset.glucose30d)
        validateTherapy(dataset.therapy30d)
        validateTargets(dataset.targets30d)

        validateSummary(summary24h, 1, dataset.generatedAt)
        validateSummary(dataset.summary7d, 7, dataset.generatedAt)
        validateSummary(dataset.summary30d, 30, dataset.generatedAt)
        validateCurrentSnapshot(dataset.currentSnapshot)
        require(ClinicalOpenAiClient.isValidEnergyMetadata(dataset, allowTargetManagerEvidence = true))
        dataset.energyProfile?.foodDurationMinutes?.let { duration ->
            require(EnergyProfileFoodDurationPolicy.isValid(duration))
        }
        require(dataset.eventSummaries.map(ClinicalEventSummary::localId).distinct().size ==
            dataset.eventSummaries.size)
        dataset.eventSummaries.forEach(::validateEvent)
        dataset.eventTypeAssociations.forEach(::validateAssociation)
    }

    private fun validateGlucose(points: List<ClinicalGlucosePoint>) {
        points.forEach { point ->
            require(point.ts > 0L)
            require(point.mmol.isFinite() && point.mmol in CLINICAL_MMOL_RANGE)
        }
    }

    private fun validateTherapy(points: List<ClinicalTherapyPoint>) {
        points.forEach { point ->
            require(point.ts > 0L)
            require(
                TherapyComponentPolicy.isValidCanonicalRow(
                    insulinU = point.insulinU,
                    carbsG = point.carbsG,
                    syntheticUam = point.syntheticUam,
                    hasInsulinEvidence = point.insulinEvidence != null,
                    hasContext = point.contextKind != null
                )
            )
        }
    }

    private fun <T> requireRowsWithin(
        fromTs: Long,
        throughTs: Long,
        rows: List<T>,
        timestamp: (T) -> Long
    ) {
        require(rows.all { timestamp(it) in fromTs..throughTs })
    }

    private fun validateTargets(points: List<ClinicalTargetPoint>) {
        points.forEach { point ->
            require(point.ts > 0L)
            requireOptionalRange(point.lowMmol, TARGET_MMOL_RANGE)
            requireOptionalRange(point.highMmol, TARGET_MMOL_RANGE)
            require(point.lowMmol == null || point.highMmol == null || point.lowMmol <= point.highMmol)
            point.durationMs?.let {
                require(it in 0L..ClinicalReportDatasetBuilder.MAX_TARGET_DURATION_MS)
                require(it > 0L || point.cancelled == true)
            }
            point.endTs?.let { end ->
                val duration = runCatching { Math.subtractExact(end, point.ts) }
                    .getOrElse { throw IllegalArgumentException("Clinical target timestamp overflow") }
                require(duration in 0L..ClinicalReportDatasetBuilder.MAX_TARGET_DURATION_MS)
                point.durationMs?.let { require(kotlin.math.abs(duration - it) <= 60_000L) }
            }
            require(point.cancelled == true || point.lowMmol != null || point.highMmol != null)
        }
    }

    private fun validateForecasts(points: List<ClinicalForecastPoint>) {
        points.forEach { point ->
            require(point.ts > 0L)
            require(point.horizonMin in 1..180)
            require(point.mmol in CLINICAL_MMOL_RANGE)
            require(point.lower in CLINICAL_MMOL_RANGE)
            require(point.upper in CLINICAL_MMOL_RANGE)
            require(point.lower <= point.mmol && point.mmol <= point.upper)
        }
    }

    private fun validateTelemetry(points: List<ClinicalTelemetryPoint>) {
        points.forEach { point ->
            require(point.ts > 0L)
            require(ClinicalReportDatasetBuilder.isValidCanonicalTelemetryPoint(point))
        }
    }

    private fun validateForecastQuality(points: List<ClinicalForecastQuality>) {
        points.forEach { point ->
            require(point.horizonMin in 1..180)
            require(point.sampleCount >= 0)
            requireOptionalRange(point.meanAbsoluteErrorMmol, 0.0..ClinicalOpenAiClient.MAX_CLINICAL_MMOL)
            point.ciCoveragePct?.let { coverage ->
                require(coverage.isFinite() && coverage in 0.0..100.0)
            }
        }
    }

    private fun validateSummary(summary: ClinicalPeriodSummary, expectedDays: Int, generatedAt: Long) {
        ClinicalReportDocument.requireValidPeriodSummary(summary, expectedDays, generatedAt)
        requireOptionalRange(summary.aapsCarbsG, 0.0..ClinicalOpenAiClient.MAX_RECORDED_CARBS_GRAMS)
        requireNonnegative(
            summary.insulinEventCount,
            summary.estimatedInsulinEventCount,
            summary.enteredCarbEventCount,
            summary.uamCarbEventCount
        )
        require(summary.weekdayPattern.map(ClinicalHourlyMetric::hour).distinct().size == summary.weekdayPattern.size)
        require(summary.weekendPattern.map(ClinicalHourlyMetric::hour).distinct().size == summary.weekendPattern.size)
        summary.weekdayPattern.forEach(::validateHourlyMetric)
        summary.weekendPattern.forEach(::validateHourlyMetric)
        validateQuality(summary.quality)
        validateActivity(summary.activity)
        validateMealEnergy(summary.mealEnergy)
        validateBasal(summary.basalContext)
        validateTherapyContext(summary.therapyContext)
        (summary.probableMealWindows + summary.recentProbableMealWindows).forEach { window ->
            requireNonnegative(
                window.medianMinuteOfDay,
                window.startMinuteOfDay,
                window.endMinuteOfDay,
                window.iqrMinutes,
                window.supportDays,
                window.lookbackDays,
                window.episodeCount,
                window.enteredEpisodeCount,
                window.uamEpisodeCount
            )
            requireFinite(window.confidencePct)
        }
    }

    private fun validateHourlyMetric(metric: ClinicalHourlyMetric) {
        require(metric.hour in 0..23)
        require(metric.sampleCount in 0..ClinicalOpenAiClient.MAX_CANONICAL_30D_SAMPLE_COUNT)
        requireOptionalRange(metric.meanMmol, CLINICAL_MMOL_RANGE)
        requireOptionalRange(metric.medianMmol, CLINICAL_MMOL_RANGE)
    }

    private fun validateQuality(quality: ClinicalDataQuality) {
        requireNonnegative(
            quality.expectedBuckets,
            quality.coveredBuckets,
            quality.missingBuckets
        )
        quality.maxGapMinutes?.let { require(it >= 0) }
        with(quality.rejected) {
            requireNonnegative(glucose, therapy, target, forecast, telemetry)
        }
    }

    private fun validateActivity(activity: ClinicalActivitySummary) {
        require(activity.coveragePct in PERCENT_RANGE)
        requireOptionalRange(activity.steps, 0.0..10_000_000.0)
        requireOptionalRange(activity.distanceKm, 0.0..7_500.0)
        requireOptionalRange(activity.activeMinutes, 0.0..100_000.0)
        requireOptionalRange(activity.activeCaloriesKcal, 0.0..400_000.0)
        requireOptionalRange(activity.meanActivityRatio, ACTIVITY_RATIO_RANGE)
        requireOptionalRange(activity.maxActivityRatio, ACTIVITY_RATIO_RANGE)
        require(
            activity.meanActivityRatio == null || activity.maxActivityRatio == null ||
                activity.meanActivityRatio <= activity.maxActivityRatio
        )
    }

    private fun validateMealEnergy(meal: ClinicalMealEnergySummary) {
        require(meal.carbohydrateEnergyKcal in ENERGY_RANGE)
        requireOptionalRange(meal.manualMealEnergyKcal, ENERGY_RANGE)
        meal.estimatedTotalMealEnergyKcal?.let { validateRange(it, allowSigned = false) }
        meal.netEnergyKcal?.let { validateRange(it, allowSigned = true) }
    }

    private fun validateBasal(basal: ClinicalBasalContextSummary) {
        require(basal.coveragePct in PERCENT_RANGE)
        requireOptionalRange(basal.meanProfileRateUph, BASAL_RATE_RANGE)
        requireOptionalRange(basal.minProfileRateUph, BASAL_RATE_RANGE)
        requireOptionalRange(basal.maxProfileRateUph, BASAL_RATE_RANGE)
        requireOptionalRange(basal.meanProfilePercent, PROFILE_PERCENT_RANGE)
        require(basal.minProfileRateUph == null || basal.maxProfileRateUph == null || basal.minProfileRateUph <= basal.maxProfileRateUph)
        require(basal.meanProfileRateUph == null || basal.minProfileRateUph == null || basal.meanProfileRateUph >= basal.minProfileRateUph)
        require(basal.meanProfileRateUph == null || basal.maxProfileRateUph == null || basal.meanProfileRateUph <= basal.maxProfileRateUph)
    }

    private fun validateTherapyContext(context: ClinicalTherapyContextSummary) {
        requireNonnegative(
            context.infusionSetChanges,
            context.sensorChanges,
            context.insulinRefills,
            context.pumpBatteryChanges,
            context.exerciseEvents,
            context.profileSwitches
        )
    }

    private fun validateCurrentSnapshot(snapshot: ClinicalCurrentSnapshot) {
        require(ClinicalOpenAiClient.isValidCurrentSnapshot(snapshot))
    }

    private fun validateEvent(event: ClinicalEventSummary) {
        val type = CompensationEventType.entries.singleOrNull { it.name == event.type }
        val severity = EventSeverity.entries.singleOrNull { it.name == event.severity }
        val source = EventSource.entries.singleOrNull { it.name == event.source }
        val status = CompensationEventStatus.entries.singleOrNull { it.name == event.status }
        requireNotNull(type)
        requireNotNull(severity)
        requireNotNull(source)
        requireNotNull(status)
        CompensationEvent(
            localId = event.localId,
            startTs = event.startTs,
            endTs = event.endTs,
            type = type,
            subtype = event.subtype,
            severity = severity,
            source = source,
            title = event.title,
            note = event.note,
            status = status,
            provenance = event.provenance
        )
    }

    private fun validateAssociation(association: ClinicalEventTypeAssociation) {
        require(ClinicalEventAssociationPolicy.isValid(association))
    }

    private fun validateRange(range: ClinicalRange, allowSigned: Boolean) {
        requireFinite(range.minimum)
        requireFinite(range.maximum)
        require(range.minimum <= range.maximum)
        if (!allowSigned) require(range.minimum in ENERGY_RANGE && range.maximum in ENERGY_RANGE)
    }

    private fun requireFinite(value: Double?) {
        require(value == null || value.isFinite())
    }

    private fun requireOptionalRange(value: Double?, range: ClosedFloatingPointRange<Double>) {
        require(value == null || value.isFinite() && value in range)
    }

    private fun requireNonnegative(vararg values: Int) {
        require(values.all { it >= 0 })
    }

    private val CLINICAL_MMOL_RANGE =
        ClinicalOpenAiClient.MIN_CLINICAL_MMOL..ClinicalOpenAiClient.MAX_CLINICAL_MMOL
    private val TARGET_MMOL_RANGE = 2.2..15.0
    private val PERCENT_RANGE = 0.0..100.0
    private val ACTIVITY_RATIO_RANGE = 0.2..3.0
    private val BASAL_RATE_RANGE = 0.0..15.0
    private val PROFILE_PERCENT_RANGE = 1.0..500.0
    private val ENERGY_RANGE = 0.0..400_000.0
}
