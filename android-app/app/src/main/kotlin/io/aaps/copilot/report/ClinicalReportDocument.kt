package io.aaps.copilot.report

import io.aaps.copilot.config.ClinicalAiModelIdPolicy
import io.aaps.copilot.data.repository.ClinicalAdvisoryPriority
import io.aaps.copilot.data.repository.ClinicalFinding
import io.aaps.copilot.data.repository.ClinicalEventSummary
import io.aaps.copilot.data.repository.ClinicalEventTypeAssociation
import io.aaps.copilot.data.repository.ClinicalEvidenceValuePolicy
import io.aaps.copilot.data.repository.ClinicalInsulinTotalSource
import io.aaps.copilot.data.repository.ClinicalLocalReport
import io.aaps.copilot.data.repository.ClinicalOpenAiClient
import io.aaps.copilot.data.repository.ClinicalOpenAiResult
import io.aaps.copilot.data.repository.ClinicalPeriodSummary
import io.aaps.copilot.data.repository.ClinicalRecommendation
import io.aaps.copilot.data.repository.ClinicalReportDatasetBuilder
import io.aaps.copilot.data.repository.ClinicalSummaryCalculator
import io.aaps.copilot.data.repository.CopilotContextNoteMarker
import io.aaps.copilot.domain.eating.ProbableEatingWindow
import java.time.Instant
import java.util.Collections
import java.util.Locale

class ClinicalReportDocument private constructor(
    val title: String,
    val createdAt: Instant,
    ranges: List<ClinicalDocumentRange>,
    sections: List<ClinicalDocumentSection>,
    val metadata: ClinicalDocumentMetadata,
    val disclaimer: String
) {
    val ranges: List<ClinicalDocumentRange> =
        Collections.unmodifiableList(ArrayList(ranges))
    val sections: List<ClinicalDocumentSection> =
        Collections.unmodifiableList(ArrayList(sections))

    fun flattenedText(): String = buildString {
        appendLine(title)
        appendLine(createdAt)
        ranges.forEach { range ->
            appendLine("${range.label}: ${range.from} - ${range.through}")
        }
        sections.forEach { section ->
            appendLine(section.title)
            section.items.forEach { appendLine(it.flattenedLine()) }
        }
        appendLine("Dataset SHA-256: ${metadata.datasetHash}")
        appendLine("Dataset schema: ${metadata.schemaVersion}")
        metadata.provider?.let { appendLine("Provider: $it") }
        if (metadata.requestedModel != null && metadata.model != null) {
            appendLine("Requested model: ${metadata.requestedModel}")
            appendLine("Actual model: ${metadata.model}")
        } else {
            metadata.model?.let { appendLine("Model: $it") }
        }
        append(disclaimer)
    }

    companion object {
        const val MAX_METADATA_VALUE_LENGTH = 220

        private const val MAX_TITLE_LENGTH = 80
        private const val MAX_DISCLAIMER_LENGTH = 400
        private const val DAY_MS = 24L * 60L * 60L * 1_000L
        private const val DOCUMENT_TITLE = "AAPS Predictive Copilot clinical report"
        private const val DISCLAIMER =
            "This advisory report is for clinician discussion only, is not medical advice, " +
                "and must not be used to command insulin, carbohydrates, glucose targets, " +
                "or calibration."
        private val SHA_256 = Regex("[0-9a-f]{64}")

        fun from(
            local: ClinicalLocalReport,
            complete: ClinicalOpenAiResult?
        ): ClinicalReportDocument {
            require(SHA_256.matches(local.requestHash)) {
                "A validated dataset SHA-256 is required"
            }
            validateLocalReport(local)
            preflightLegacyDocument(local, complete)
            val validatedModels = complete?.let {
                validateCompleteProvenance(local, it)
            }

            val ranges = buildList {
                local.summary24h?.let { add(it.toRange("24 hours")) }
                add(local.summary7d.toRange("7 days"))
                add(local.summary30d.toRange("30 days"))
            }
            val sections = buildList {
                local.summary24h?.let { add(it.toMetricsSection("24 hours")) }
                add(local.summary7d.toMetricsSection("7 days"))
                add(local.summary30d.toMetricsSection("30 days"))
                if (local.energyProfile != null || local.plannedActivities.isNotEmpty()) {
                    add(energyProfileSection(local.energyProfile, local.plannedActivities))
                }
                local.forecastQuality.takeIf { it.isNotEmpty() }?.let(::forecastQualitySection)?.let(::add)
                local.eventSummaries.takeIf { it.isNotEmpty() }
                    ?.let(::eventTimelineSection)
                    ?.let(::add)
                local.eventTypeAssociations.takeIf { it.isNotEmpty() }
                    ?.let(::eventAssociationsSection)
                    ?.let(::add)
                add(dataQualitySection(local))
                complete?.let { addAll(advisorySections(it)) }
            }

            return ClinicalReportDocument(
                title = bounded(DOCUMENT_TITLE, MAX_TITLE_LENGTH),
                createdAt = safeInstant(local.generatedAt),
                ranges = ranges,
                sections = sections,
                metadata = ClinicalDocumentMetadata(
                    provider = complete?.metadata?.providerId?.name,
                    model = validatedModels?.actual,
                    requestedModel = validatedModels?.requested,
                    datasetHash = local.requestHash,
                    schemaVersion = ClinicalReportDatasetBuilder.SCHEMA_VERSION
                ),
                disclaimer = bounded(DISCLAIMER, MAX_DISCLAIMER_LENGTH)
            )
        }

        private fun validateLocalReport(local: ClinicalLocalReport) {
            require(local.generatedAt > 0L) {
                "Clinical report generation time is invalid"
            }
            local.summary24h?.let { requireValidPeriodSummary(it, expectedDays = 1, local.generatedAt) }
            requireValidPeriodSummary(local.summary7d, expectedDays = 7, local.generatedAt)
            requireValidPeriodSummary(local.summary30d, expectedDays = 30, local.generatedAt)
        }

        private fun preflightLegacyDocument(
            local: ClinicalLocalReport,
            complete: ClinicalOpenAiResult?
        ) {
            val periodCount = if (local.summary24h == null) 2L else 3L
            var sectionCount = periodCount + 1L
            val summaries = listOfNotNull(local.summary24h, local.summary7d, local.summary30d)
            var itemCount = periodCount * LEGACY_PERIOD_ITEM_COUNT + summaries.sumOf { summary ->
                summary.weekdayPattern.size.toLong() + summary.weekendPattern.size.toLong() +
                    summary.probableMealWindows.size.toLong() +
                    summary.recentProbableMealWindows.size.toLong()
            }

            if (local.energyProfile != null || local.plannedActivities.isNotEmpty()) {
                sectionCount += 1L
                itemCount += LEGACY_ENERGY_FIXED_ITEMS + local.plannedActivities.size
            }
            if (local.forecastQuality.isNotEmpty()) {
                sectionCount += 1L
                itemCount += local.forecastQuality.size
            }
            if (local.eventSummaries.isNotEmpty()) {
                sectionCount += 1L
                itemCount += local.eventSummaries.size
            }
            if (local.eventTypeAssociations.isNotEmpty()) {
                sectionCount += 1L
                itemCount += local.eventTypeAssociations.size
            }
            itemCount += periodCount * LEGACY_QUALITY_ITEMS_PER_PERIOD
            complete?.report?.let { report ->
                sectionCount += LEGACY_ADVISORY_SECTIONS
                itemCount += LEGACY_ADVISORY_SUMMARY_ITEMS
                itemCount += maxOf(1, report.patterns.size)
                itemCount += maxOf(1, report.safetyObservations.size)
                itemCount += maxOf(1, report.recommendations.size)
                itemCount += maxOf(1, report.careTeamQuestions.size)
            }
            val layoutLowerBound = ClinicalPdfLayoutBudget.legacyLowerBound(
                itemCount = itemCount,
                sectionHeadingCount = sectionCount,
                rangeCount = periodCount
            )
            if (layoutLowerBound.pageCount > ClinicalPdfLimits.DEFAULT.maxPages.toLong()) {
                throw ClinicalPdfTooLargeException(ClinicalPdfLimitDimension.PAGES)
            }
            if (sectionCount > MAX_LEGACY_SECTIONS || itemCount > MAX_LEGACY_ITEMS) {
                throw ClinicalPdfTooLargeException(ClinicalPdfLimitDimension.PAGES)
            }

            val reservedBytes = LEGACY_FIXED_UTF8_ALLOWANCE +
                itemCount * LEGACY_ITEM_UTF8_ALLOWANCE +
                sectionCount * LEGACY_SECTION_UTF8_ALLOWANCE
            val textBudget = LegacyUtf8Budget(
                ClinicalPdfLimits.DEFAULT.maxInputBytes.toLong() - reservedBytes
            )
            textBudget.consume(DOCUMENT_TITLE)
            textBudget.consume(DISCLAIMER)
            local.energyProfile?.let { profile ->
                textBudget.consume(profile.sex)
                textBudget.consume(profile.foodProfile)
                textBudget.consume(profile.foodProfileSource)
                textBudget.consume(profile.activityProfile)
                textBudget.consume(profile.activityProfileSource)
                textBudget.consume(profile.confidence)
                textBudget.consume(profile.calorieGoalMode)
            }
            local.plannedActivities.forEach { activity ->
                textBudget.consume(activity.type)
                textBudget.consume(activity.intensity)
                textBudget.consume(activity.adherence)
                textBudget.consume(activity.targetDecision)
                activity.targetBlockers.forEach(textBudget::consume)
            }
            local.eventSummaries.forEach { event ->
                textBudget.consume(event.type)
                textBudget.consume(event.subtype)
                textBudget.consume(event.severity)
                textBudget.consume(event.source)
                textBudget.consume(event.title)
                textBudget.consume(event.note)
                textBudget.consume(event.status)
            }
            local.eventTypeAssociations.forEach { association ->
                textBudget.consume(association.type)
                textBudget.consume(association.glucose.unit)
                textBudget.consume(association.trend.unit)
                textBudget.consume(association.uam.unit)
                textBudget.consume(association.isf.unit)
                textBudget.consume(association.cr.unit)
                textBudget.consume(association.forecastError.unit)
            }
            complete?.metadata?.let { metadata ->
                textBudget.consume(metadata.model)
                textBudget.consume(metadata.requestedModel)
            }
        }

        private class LegacyUtf8Budget(limit: Long) {
            private var remaining = limit

            fun consume(value: String?) {
                value ?: return
                var index = 0
                while (index < value.length) {
                    val character = value[index]
                    val bytes = when {
                        character.code <= 0x7f -> 1
                        character.code <= 0x7ff -> 2
                        character.isHighSurrogate() &&
                            value.getOrNull(index + 1)?.isLowSurrogate() == true -> {
                            index += 1
                            4
                        }
                        else -> 3
                    }
                    remaining -= bytes
                    if (remaining < 0L) {
                        throw ClinicalPdfTooLargeException(ClinicalPdfLimitDimension.INPUT_BYTES)
                    }
                    index += 1
                }
            }
        }

        internal fun requireValidPeriodSummary(
            summary: ClinicalPeriodSummary,
            expectedDays: Int,
            generatedAt: Long
        ) {
            require(summary.days == expectedDays) {
                "Clinical summary period does not match its report range"
            }
            require(summary.fromTs < summary.throughTs) {
                "Clinical summary window must have positive duration"
            }
            val duration = subtractExact(summary.throughTs, summary.fromTs)
            val expectedDuration = multiplyExact(expectedDays.toLong(), DAY_MS)
            require(
                absoluteDifference(duration, expectedDuration) <=
                    ClinicalSummaryCalculator.WINDOW_DURATION_TOLERANCE_MS
            ) {
                "Clinical summary duration is inconsistent with its period"
            }
            require(summary.throughTs <= generatedAt) {
                "Clinical summary cannot end after report generation"
            }
            val generatedAtLag = subtractExact(generatedAt, summary.throughTs)
            require(generatedAtLag < ClinicalSummaryCalculator.BUCKET_MS) {
                "Clinical summary end is not aligned with report generation"
            }

            requirePercent(summary.coveragePct, "coverage")
            requireOptionalPercent(summary.timeBelow4Pct, "time below range")
            requireOptionalPercent(summary.timeInRangePct, "time in range")
            requireOptionalPercent(summary.timeAboveRangePct, "time above range")
            requireOptionalMmol(summary.meanMmol, "mean glucose")
            requireOptionalMmol(summary.medianMmol, "median glucose")
            requireOptionalMmol(summary.meanTargetMmol, "mean target")
            summary.coefficientOfVariationPct?.let { cv ->
                require(cv.isFinite() && cv in 0.0..ClinicalOpenAiClient.MAX_CV_PCT) {
                    "Clinical summary coefficient of variation is invalid"
                }
            }
            require(
                summary.totalInsulinU.isFinite() &&
                    summary.totalInsulinU in
                    0.0..ClinicalOpenAiClient.MAX_RECORDED_INSULIN_UNITS
            ) {
                "Clinical summary insulin total is invalid"
            }
            require(
                summary.totalCarbsG.isFinite() &&
                    summary.totalCarbsG in
                    0.0..ClinicalOpenAiClient.MAX_RECORDED_CARBS_GRAMS
            ) {
                "Clinical summary carbohydrate total is invalid"
            }
            listOf(
                summary.confirmedInsulinU,
                summary.estimatedInsulinU
            ).forEach { insulin ->
                require(
                    insulin.isFinite() &&
                        insulin in 0.0..ClinicalOpenAiClient.MAX_RECORDED_INSULIN_UNITS
                ) {
                    "Clinical summary insulin evidence total is invalid"
                }
            }
            listOf(
                summary.deliveredBasalInsulinU,
                summary.deliveredBolusInsulinU
            ).forEach { insulin ->
                insulin?.let {
                    require(
                        it.isFinite() &&
                            it in 0.0..ClinicalOpenAiClient.MAX_RECORDED_INSULIN_UNITS
                    ) {
                        "Clinical summary delivered insulin total is invalid"
                    }
                }
            }
            listOf(summary.enteredCarbsG, summary.uamCarbsG).forEach { carbs ->
                require(
                    carbs.isFinite() &&
                        carbs in 0.0..ClinicalOpenAiClient.MAX_RECORDED_CARBS_GRAMS
                ) {
                    "Clinical summary carbohydrate evidence total is invalid"
                }
            }
            require(
                summary.enteredCarbsG + summary.uamCarbsG <= summary.totalCarbsG + 0.05
            ) {
                "Clinical summary carbohydrate breakdown exceeds its total"
            }
            requirePercent(summary.activity.coveragePct, "activity coverage")
            listOf(summary.probableMealWindows, summary.recentProbableMealWindows).forEach { windows ->
                require(windows.size <= 3) {
                    "Clinical summary contains too many probable meal windows"
                }
                windows.forEach { window ->
                require(
                    window.medianMinuteOfDay in 0 until 24 * 60 &&
                        window.startMinuteOfDay in 0 until 24 * 60 &&
                        window.endMinuteOfDay in 0 until 24 * 60 &&
                        window.startMinuteOfDay <= window.medianMinuteOfDay &&
                        window.medianMinuteOfDay <= window.endMinuteOfDay &&
                        window.iqrMinutes == window.endMinuteOfDay - window.startMinuteOfDay &&
                        window.lookbackDays in 1..30 &&
                        window.supportDays in 1..window.lookbackDays &&
                        window.episodeCount >= window.supportDays &&
                        window.enteredEpisodeCount in 0..window.episodeCount &&
                        window.uamEpisodeCount in 0..window.episodeCount &&
                        window.confidencePct.isFinite() &&
                        window.confidencePct in 0.0..100.0
                ) {
                    "Clinical summary probable meal window is invalid"
                }
                }
            }

            val quality = summary.quality
            val expectedBuckets = (expectedDuration / ClinicalSummaryCalculator.BUCKET_MS).toInt()
            require(quality.expectedBuckets == expectedBuckets) {
                "Clinical summary expected bucket count is invalid"
            }
            require(quality.coveredBuckets >= 0 && quality.missingBuckets >= 0) {
                "Clinical summary coverage counts must be nonnegative"
            }
            require(
                quality.coveredBuckets.toLong() <= quality.expectedBuckets.toLong() + 1L
            ) {
                "Clinical summary covered bucket count exceeds the inclusive boundary"
            }
            val expectedMissingBuckets =
                (quality.expectedBuckets - quality.coveredBuckets).coerceAtLeast(0)
            require(
                quality.missingBuckets == expectedMissingBuckets
            ) {
                "Clinical summary coverage counts are inconsistent"
            }
            quality.maxGapMinutes?.let { maxGap ->
                val maximumWindowMinutes = expectedDuration / 60_000L
                require(maxGap >= 0 && maxGap.toLong() <= maximumWindowMinutes) {
                    "Clinical summary maximum gap is invalid"
                }
            }
            with(quality.rejected) {
                require(
                    glucose >= 0 &&
                        therapy >= 0 &&
                        target >= 0 &&
                        forecast >= 0 &&
                        telemetry >= 0
                ) {
                    "Clinical summary rejection counts must be nonnegative"
                }
            }
        }

        private fun requirePercent(value: Double, label: String) {
            require(value.isFinite() && value in 0.0..100.0) {
                "Clinical summary $label percentage is invalid"
            }
        }

        private fun requireOptionalPercent(value: Double?, label: String) {
            value?.let { requirePercent(it, label) }
        }

        private fun requireOptionalMmol(value: Double?, label: String) {
            value?.let {
                require(
                    it.isFinite() &&
                        it in ClinicalOpenAiClient.MIN_CLINICAL_MMOL..
                        ClinicalOpenAiClient.MAX_CLINICAL_MMOL
                ) {
                    "Clinical summary $label is outside clinical bounds"
                }
            }
        }

        private fun subtractExact(left: Long, right: Long): Long =
            try {
                Math.subtractExact(left, right)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("Clinical summary timestamp overflow")
            }

        private fun multiplyExact(left: Long, right: Long): Long =
            try {
                Math.multiplyExact(left, right)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("Clinical summary duration overflow")
            }

        private fun absoluteDifference(left: Long, right: Long): Long =
            if (left >= right) left - right else right - left

        private fun validateCompleteProvenance(
            local: ClinicalLocalReport,
            complete: ClinicalOpenAiResult
        ): ValidatedModels {
            val metadata = complete.metadata
            require(metadata.schemaName == ClinicalOpenAiClient.SCHEMA_NAME) {
                "Unsupported clinical advisory schema"
            }
            require(metadata.schemaVersion == ClinicalOpenAiClient.SCHEMA_VERSION) {
                "Unsupported clinical advisory schema version"
            }
            require(metadata.datasetSchemaVersion == ClinicalReportDatasetBuilder.SCHEMA_VERSION) {
                "AI report dataset schema does not match the local report schema"
            }
            require(metadata.requestHash == local.requestHash) {
                "AI report does not match the local dataset"
            }
            require(metadata.providerId == metadata.requestedProviderId) {
                "AI report provider does not match the requested provider"
            }
            require(
                complete.report.patterns.all { finding ->
                    ClinicalEvidenceValuePolicy.isValid(
                        finding.evidenceMetric,
                        finding.evidenceValue
                    )
                }
            ) {
                "Clinical advisory contains an invalid evidence value"
            }

            val actual = requirePrintableModel(metadata.model)
            val requested = requirePrintableModel(metadata.requestedModel)
                .takeUnless { it == actual }
            return ValidatedModels(actual = actual, requested = requested)
        }

        private fun requirePrintableModel(raw: String): String {
            val model = ClinicalAiModelIdPolicy.requireValid(raw)
            require(model.length <= MAX_METADATA_VALUE_LENGTH) {
                "Model ID is too long for a bounded printable report"
            }
            require(!CREDENTIAL_SHAPED_MODEL.containsMatchIn(model)) {
                "Credential-shaped model ID is not printable"
            }
            require(!URL_SHAPED_MODEL.containsMatchIn(model)) {
                "URL-shaped model ID is not printable"
            }
            require(!IPV4_SHAPED_MODEL.containsMatchIn(model)) {
                "IP-shaped model ID is not printable"
            }
            require(!ENDPOINT_SHAPED_MODEL.matches(model)) {
                "Endpoint-shaped model ID is not printable"
            }
            require(
                !BARE_HOST_SHAPED_MODEL.matches(model) ||
                    LOCAL_MODEL_ARTIFACT.matches(model)
            ) {
                "Bare host-shaped model ID is not printable"
            }
            require(!LOOPBACK_HOST_SHAPED_MODEL.matches(model)) {
                "Host-shaped model ID is not printable"
            }
            return model
        }

        private fun ClinicalPeriodSummary.toRange(label: String) =
            ClinicalDocumentRange(
                label = label,
                from = safeInstant(fromTs),
                through = safeInstant(throughTs)
            )

        private fun ClinicalPeriodSummary.toMetricsSection(title: String) =
            ClinicalDocumentSection.create(
                title = title,
                items = buildList {
                    add(item("Coverage", percent(coveragePct)))
                    add(item("Mean glucose", mmol(meanMmol)))
                    add(item("Median glucose", mmol(medianMmol)))
                    add(item("Coefficient of variation", percent(coefficientOfVariationPct)))
                    add(item("Time below 4.0", percent(timeBelow4Pct)))
                    add(item("Time in range", percent(timeInRangePct)))
                    add(item("Time above range", percent(timeAboveRangePct)))
                    add(item(
                        if (insulinTotalSource == ClinicalInsulinTotalSource.AAPS_RAW_HISTORY) {
                            "All delivered insulin"
                        } else {
                            "Insulin in Copilot events (not a full delivered total)"
                        },
                        quantity(totalInsulinU, "U")
                    ))
                    add(item("Insulin total source", insulinTotalSource.name))
                    add(item("Delivered basal", quantity(deliveredBasalInsulinU, "U")))
                    add(item("Delivered bolus", quantity(deliveredBolusInsulinU, "U")))
                    add(item("Confirmed insulin events", quantity(confirmedInsulinU, "U")))
                    add(item("IOB-derived insulin signals", quantity(estimatedInsulinU, "U")))
                    add(item("Insulin event count", insulinEventCount.toString()))
                    add(item("Estimated insulin event count", estimatedInsulinEventCount.toString()))
                    add(item("Entered carbohydrate event count", enteredCarbEventCount.toString()))
                    add(item("UAM carbohydrate event count", uamCarbEventCount.toString()))
                    add(item("Entered + UAM carbohydrates", quantity(totalCarbsG, "g")))
                    add(item("Entered carbohydrates", quantity(enteredCarbsG, "g")))
                    add(item("UAM carbohydrates", quantity(uamCarbsG, "g")))
                    add(item("AAPS carbohydrate reconciliation", quantity(aapsCarbsG, "g")))
                    add(item(
                        "Energy from recorded carbohydrates (includes UAM)",
                        quantity(carbohydrateEnergyKcal, "kcal")
                    ))
                    add(item(
                        "Meal carbohydrate energy",
                        quantity(mealEnergy.carbohydrateEnergyKcal, "kcal")
                    ))
                    add(item("Manual meal energy", quantity(mealEnergy.manualMealEnergyKcal, "kcal")))
                    add(item(
                        mealEnergyTotalLabel(mealEnergy.source),
                        energyRange(mealEnergy.estimatedTotalMealEnergyKcal)
                    ))
                    add(item("Estimated net energy", energyRange(mealEnergy.netEnergyKcal)))
                    add(item("Steps", quantity(activity.steps, "")))
                    add(item("Active minutes", quantity(activity.activeMinutes, "min")))
                    add(item("Active distance", quantity(activity.distanceKm, "km")))
                    add(item("Active calories", quantity(activity.activeCaloriesKcal, "kcal")))
                    add(item("Activity coverage", percent(activity.coveragePct)))
                    add(item("Mean activity ratio", quantity(activity.meanActivityRatio, "ratio")))
                    add(item("Maximum activity ratio", quantity(activity.maxActivityRatio, "ratio")))
                    add(item("Basal coverage", percent(basalContext.coveragePct)))
                    add(item("Profile basal rate", quantity(basalContext.meanProfileRateUph, "U/h")))
                    add(item(
                        "Minimum profile basal rate",
                        quantity(basalContext.minProfileRateUph, "U/h")
                    ))
                    add(item(
                        "Maximum profile basal rate",
                        quantity(basalContext.maxProfileRateUph, "U/h")
                    ))
                    add(item("Mean profile percent", quantity(basalContext.meanProfilePercent, "%")))
                    add(item("Infusion set changes", therapyContext.infusionSetChanges.toString()))
                    add(item("Sensor changes", therapyContext.sensorChanges.toString()))
                    add(item("Insulin refills", therapyContext.insulinRefills.toString()))
                    add(item("Exercise events", therapyContext.exerciseEvents.toString()))
                    add(item("Pump battery changes", therapyContext.pumpBatteryChanges.toString()))
                    add(item("Profile switches", therapyContext.profileSwitches.toString()))
                    add(item("Mean target", mmol(meanTargetMmol)))
                    addAll(hourlyMetricItems("Weekday", weekdayPattern))
                    addAll(hourlyMetricItems("Weekend", weekendPattern))
                    addAll(mealWindowItems("Probable meal window", probableMealWindows))
                    addAll(mealWindowItems("Recent probable meal window", recentProbableMealWindows))
                }
            )

        private fun hourlyMetricItems(
            label: String,
            metrics: List<io.aaps.copilot.data.repository.ClinicalHourlyMetric>
        ): List<ClinicalDocumentItem> = metrics.sortedBy { it.hour }.map { metric ->
            item(
                "$label hour ${String.format(Locale.ROOT, "%02d", metric.hour)}",
                "samples=${metric.sampleCount}; mean=${mmol(metric.meanMmol)}; " +
                    "median=${mmol(metric.medianMmol)}"
            )
        }

        private fun mealWindowItems(
            label: String,
            windows: List<ProbableEatingWindow>
        ): List<ClinicalDocumentItem> = windows.sortedBy(ProbableEatingWindow::medianMinuteOfDay)
            .mapIndexed { index, window ->
                item(
                    "$label ${index + 1}",
                    "median=${clock(window.medianMinuteOfDay)}; " +
                        "range=${clock(window.startMinuteOfDay)}-${clock(window.endMinuteOfDay)}; " +
                        "iqr=${window.iqrMinutes} min; support=${window.supportDays}/${window.lookbackDays} days; " +
                        "episodes=${window.episodeCount}; entered=${window.enteredEpisodeCount}; " +
                        "UAM=${window.uamEpisodeCount}; confidence=${fixed(window.confidencePct, 2)}%"
                )
            }

        private fun mealEnergyTotalLabel(
            source: io.aaps.copilot.data.repository.ClinicalMealEnergySource
        ): String = when (source) {
            io.aaps.copilot.data.repository.ClinicalMealEnergySource.MANUAL_MEAL_ENTRY ->
                "Total meal energy from manual meal entries"
            io.aaps.copilot.data.repository.ClinicalMealEnergySource.MIXED_MANUAL_AND_ESTIMATED ->
                "Total meal energy (manual entries + profile estimate)"
            io.aaps.copilot.data.repository.ClinicalMealEnergySource.ESTIMATED_FROM_CARB_SHARE ->
                "Estimated total meal energy"
            io.aaps.copilot.data.repository.ClinicalMealEnergySource.NOT_AVAILABLE ->
                "Total meal energy"
        }

        private fun energyProfileSection(
            profile: io.aaps.copilot.data.repository.ClinicalEnergyProfileSummary?,
            activities: List<io.aaps.copilot.data.repository.ClinicalPlannedActivitySummary>
        ) = ClinicalDocumentSection.create(
            title = "Energy and activity profile",
            items = buildList {
                add(item("Food profile", profile?.foodProfile ?: "Not available"))
                add(item("Food profile source", profile?.foodProfileSource ?: "Not available"))
                add(item("Food duration", profile?.foodDurationMinutes?.let { "$it min" } ?: "Not available"))
                add(item("Activity profile", profile?.activityProfile ?: "Not available"))
                add(item("Activity profile source", profile?.activityProfileSource ?: "Not available"))
                add(item("Evidence", profile?.let { "${it.confidence}, ${it.evidenceDays} days" } ?: "Not available"))
                add(item("Maintenance energy", energyRange(profile?.maintenanceEnergyKcal)))
                add(item("Calorie goal", profile?.calorieGoalMode ?: "Not available"))
                activities.forEachIndexed { index, activity ->
                    add(item(
                        "Planned activity ${index + 1}",
                        "${activity.type} ${activity.intensity.lowercase(Locale.US)} | " +
                            "${activity.plannedDurationMinutes} min | ${activity.adherence} | " +
                            "${activity.targetDecision ?: "NO_DECISION"}" +
                            activity.targetBlockers.takeIf { it.isNotEmpty() }
                                ?.joinToString(prefix = " | blockers: ")
                                .orEmpty()
                    ))
                }
            }
        )

        private fun forecastQualitySection(
            quality: List<io.aaps.copilot.data.repository.ClinicalForecastQuality>
        ) = ClinicalDocumentSection.create(
            title = "Forecast quality",
            items = quality.sortedBy { it.horizonMin }.map { point ->
                item(
                    "${point.horizonMin} min",
                    if (point.meanAbsoluteErrorMmol == null || point.ciCoveragePct == null) {
                        "Not available (${point.sampleCount} matched samples)"
                    } else {
                        "MAE ${quantity(point.meanAbsoluteErrorMmol, "mmol/L")}; " +
                            "CI coverage ${quantity(point.ciCoveragePct, "%")} " +
                            "(${point.sampleCount} samples)"
                    }
                )
            }
        )

        private fun clock(minuteOfDay: Int): String {
            val bounded = minuteOfDay.coerceIn(0, 24 * 60 - 1)
            return String.format(Locale.US, "%02d:%02d", bounded / 60, bounded % 60)
        }

        private fun eventTimelineSection(events: List<ClinicalEventSummary>) =
            ClinicalDocumentSection.createFullHistory(
                title = "Local compensation event timeline (full history)",
                items = events
                    .sortedWith(
                        compareBy<ClinicalEventSummary> { it.startTs }
                            .thenBy { it.endTs }
                            .thenBy { it.type }
                            .thenBy { it.subtype }
                    )
                    .map { event ->
                        exactItem(
                            label = listOf(event.type, event.subtype)
                                .filter(String::isNotBlank)
                                .joinToString(" / "),
                            value = buildString {
                                append("start=").append(safeInstant(event.startTs))
                                append("; end=").append(safeInstant(event.endTs))
                                append("; durationMin=")
                                    .append((event.endTs - event.startTs).coerceAtLeast(0L) / 60_000L)
                                append("; severity=").append(event.severity)
                                append("; source=").append(coarseSource(event.source))
                                append("; status=").append(event.status)
                                sanitizedLocalEventText(event.title)?.let { title ->
                                    append("; title=").append(title)
                                }
                                event.note?.let(::sanitizedLocalEventText)?.let { note ->
                                    append("; note=").append(note)
                                }
                            }
                        )
                    }
            )

        private fun eventAssociationsSection(
            associations: List<ClinicalEventTypeAssociation>
        ) = ClinicalDocumentSection.create(
            title = "Event associations (non-causal)",
            items = associations.sortedBy(ClinicalEventTypeAssociation::type).map { association ->
                exactItem(
                    association.type,
                    buildString {
                        append("events=${association.eventCount}; durationMin=${association.totalDurationMinutes}")
                        append("; ").append(association.glucose.associationText("glucose"))
                        append("; ").append(association.trend.associationText("trend"))
                        append("; ").append(association.uam.associationText("UAM"))
                        append("; ").append(association.isf.associationText("ISF"))
                        append("; ").append(association.cr.associationText("CR"))
                        append("; ").append(association.forecastError.associationText("forecast error"))
                    }
                )
            }
        )

        private fun io.aaps.copilot.data.repository.ClinicalAssociationMetric.associationText(
            label: String
        ): String = if (sampleCount == 0 || mean == null) {
            "$label samples=$sampleCount, unavailable"
        } else {
            "$label samples=$sampleCount, mean=${fixed(mean, 3)} $unit"
        }

        private fun sanitizedLocalEventText(value: String): String? =
            CopilotContextNoteMarker.stripProtectedMarkers(value)
                .trim('|', ' ')
                .takeIf(String::isNotBlank)

        private fun coarseSource(source: String): String = when (source.uppercase(Locale.ROOT)) {
            "USER" -> "USER"
            "AAPS" -> "AAPS"
            else -> "AUTOMATIC"
        }

        private fun energyRange(
            range: io.aaps.copilot.data.repository.ClinicalRange?
        ): String = range?.let {
            if (it.minimum == it.maximum) quantity(it.minimum, "kcal")
            else "${quantity(it.minimum, "kcal")} - ${quantity(it.maximum, "kcal")}"
        } ?: "Not available"

        private fun dataQualitySection(local: ClinicalLocalReport) =
            ClinicalDocumentSection.create(
                title = "Data quality",
                items = local.summary24h?.let { qualityItems("24h", it) }.orEmpty() +
                    qualityItems("7d", local.summary7d) +
                    qualityItems("30d", local.summary30d)
            )

        private fun qualityItems(
            prefix: String,
            summary: ClinicalPeriodSummary
        ): List<ClinicalDocumentItem> {
            val quality = summary.quality
            val rejected = quality.rejected
            return listOf(
                item("$prefix coverage", percent(summary.coveragePct)),
                item(
                    "$prefix covered buckets",
                    "${quality.coveredBuckets} / ${quality.expectedBuckets}"
                ),
                item("$prefix missing buckets", quality.missingBuckets.toString()),
                item(
                    "$prefix maximum gap",
                    quality.maxGapMinutes?.let { "$it min" } ?: "Not available"
                ),
                item(
                    "$prefix rejected rows",
                    "glucose=${rejected.glucose}, therapy=${rejected.therapy}, " +
                        "target=${rejected.target}, forecast=${rejected.forecast}, " +
                        "telemetry=${rejected.telemetry}"
                )
            )
        }

        private fun advisorySections(
            complete: ClinicalOpenAiResult
        ): List<ClinicalDocumentSection> {
            val report = complete.report
            return listOf(
                ClinicalDocumentSection.create(
                    title = "AI summary",
                    items = listOf(
                        item("7-day status", report.summary7dStatus.name),
                        item("30-day status", report.summary30dStatus.name),
                        item(
                            "AI data quality",
                            report.dataQuality.map { it.name }
                                .distinct()
                                .sorted()
                                .joinToString()
                                .ifBlank { "None reported" }
                        )
                    )
                ),
                ClinicalDocumentSection.create(
                    title = "Advisory findings",
                    items = report.patterns
                        .distinct()
                        .sortedWith(findingComparator())
                        .mapIndexed { index, finding ->
                            item("Finding ${index + 1}", finding.printableValue())
                        }
                        .ifEmpty { listOf(item("Status", "None reported")) }
                ),
                ClinicalDocumentSection.create(
                    title = "Safety observations",
                    items = report.safetyObservations
                        .map { it.name }
                        .distinct()
                        .sorted()
                        .mapIndexed { index, value ->
                            item("Observation ${index + 1}", value)
                        }
                        .ifEmpty { listOf(item("Status", "None reported")) }
                ),
                ClinicalDocumentSection.create(
                    title = "Discussion topics",
                    items = report.recommendations
                        .distinct()
                        .sortedWith(
                            compareByDescending<ClinicalRecommendation> {
                                it.priority.priorityRank()
                            }
                                .thenBy { it.period.name }
                                .thenBy { it.careTeamDiscussionTopic.name }
                        )
                        .mapIndexed { index, recommendation ->
                            item(
                                "Topic ${index + 1}",
                                "${recommendation.priority.name} | " +
                                    "${recommendation.period.name} | " +
                                    recommendation.careTeamDiscussionTopic.name
                            )
                        }
                        .ifEmpty { listOf(item("Status", "None reported")) }
                ),
                ClinicalDocumentSection.create(
                    title = "Care team questions",
                    items = report.careTeamQuestions
                        .map { it.name }
                        .distinct()
                        .sorted()
                        .mapIndexed { index, value ->
                            item("Question ${index + 1}", value)
                        }
                        .ifEmpty { listOf(item("Status", "None reported")) }
                )
            )
        }

        private fun findingComparator() = compareBy<ClinicalFinding>(
            { it.period.name },
            { it.topic.name },
            { it.timeBand.name },
            { it.direction.name },
            { it.confidence.name },
            { it.evidenceMetric.name },
            { it.evidenceValue }
        )

        private fun ClinicalFinding.printableValue(): String =
            "${
                period.name
            } | ${topic.name} | ${timeBand.name} | ${direction.name} | " +
                "${confidence.name} | ${evidenceMetric.name}=${fixed(evidenceValue, 2)}"

        private fun ClinicalAdvisoryPriority.priorityRank(): Int = when (this) {
            ClinicalAdvisoryPriority.HIGH -> 3
            ClinicalAdvisoryPriority.MEDIUM -> 2
            ClinicalAdvisoryPriority.LOW -> 1
        }

        private fun item(label: String, value: String) =
            ClinicalDocumentItem.create(label, value)

        private fun exactItem(label: String, value: String) =
            ClinicalDocumentItem.createExact(label, value)

        private fun mmol(value: Double?): String =
            value?.takeIf(Double::isFinite)?.let { "${fixed(it, 2)} mmol/L" }
                ?: "Not available"

        private fun percent(value: Double?): String =
            value?.takeIf(Double::isFinite)?.let { "${fixed(it, 1)}%" }
                ?: "Not available"

        private fun quantity(value: Double?, unit: String): String =
            value?.takeIf(Double::isFinite)?.let {
                listOf(fixed(it, 2), unit).filter(String::isNotBlank).joinToString(" ")
            }
                ?: "Not available"

        private fun fixed(value: Double, decimals: Int): String =
            String.format(Locale.ROOT, "%.${decimals}f", value)

        private fun safeInstant(epochMillis: Long): Instant =
            runCatching { Instant.ofEpochMilli(epochMillis) }.getOrDefault(Instant.EPOCH)

        private fun bounded(value: String, maxLength: Int): String {
            require(maxLength > 0)
            if (value.length <= maxLength) return value
            if (maxLength <= 3) return value.take(maxLength)
            return value.take(maxLength - 3).trimEnd() + "..."
        }

        private val CREDENTIAL_SHAPED_MODEL = Regex(
            "(?i)(?:^(?:sk-(?:proj-)?|gsk_|xai-|AIza|hf_|ghp_)[a-z0-9._-]{8,}$|" +
                "(?:^|[?&;,])(?:api[-_]?key|access[-_]?token|secret)=)"
        )
        private val URL_SHAPED_MODEL = Regex("(?i)(?:https?|wss?)://")
        private val IPV4_SHAPED_MODEL = Regex(
            "(?:^|[^0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?(?:$|[^0-9])"
        )
        private val ENDPOINT_SHAPED_MODEL = Regex(
            "(?i)^(?:[a-z0-9-]+\\.)+[a-z]{2,}" +
                "(?:(?::[0-9]{1,5})(?:/.*)?|/.+)$"
        )
        private val BARE_HOST_SHAPED_MODEL = Regex(
            "(?i)^(?:[a-z0-9-]+\\.)+[a-z]{2,}$"
        )
        private val LOCAL_MODEL_ARTIFACT = Regex(
            "(?i)^.+\\.(?:gguf|model)$"
        )
        private val LOOPBACK_HOST_SHAPED_MODEL = Regex(
            "(?i)^(?:localhost|::1|\\[::1])(?::[0-9]{1,5})?(?:/.*)?$"
        )
        private const val MAX_LEGACY_SECTIONS = 64L
        private const val MAX_LEGACY_ITEMS = 32_768L
        private const val LEGACY_PERIOD_ITEM_COUNT = 45L
        private const val LEGACY_ENERGY_FIXED_ITEMS = 8L
        private const val LEGACY_QUALITY_ITEMS_PER_PERIOD = 5L
        private const val LEGACY_ADVISORY_SECTIONS = 5L
        private const val LEGACY_ADVISORY_SUMMARY_ITEMS = 3L
        private const val LEGACY_FIXED_UTF8_ALLOWANCE = 4_096L
        private const val LEGACY_ITEM_UTF8_ALLOWANCE = 128L
        private const val LEGACY_SECTION_UTF8_ALLOWANCE = 256L
    }

    private data class ValidatedModels(
        val actual: String,
        val requested: String?
    )
}

class ClinicalDocumentRange internal constructor(
    val label: String,
    val from: Instant,
    val through: Instant
)

class ClinicalDocumentSection private constructor(
    val title: String,
    items: List<ClinicalDocumentItem>
) {
    val items: List<ClinicalDocumentItem> =
        Collections.unmodifiableList(ArrayList(items))

    companion object {
        internal fun create(
            title: String,
            items: List<ClinicalDocumentItem>
        ): ClinicalDocumentSection = ClinicalDocumentSection(
            title = title,
            items = items
        )

        internal fun createFullHistory(
            title: String,
            items: List<ClinicalDocumentItem>
        ): ClinicalDocumentSection = ClinicalDocumentSection(
            title = title,
            items = items
        )
    }
}

class ClinicalDocumentItem private constructor(
    val label: String,
    val value: String
) {
    fun flattenedLine(): String = "$label: $value"

    companion object {
        internal fun create(label: String, value: String): ClinicalDocumentItem {
            val normalizedLabel = label
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { "Item" }
            val normalizedValue = value
                .replace(Regex("[\\u0000-\\u001f\\u007f]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { "Not available" }
            return ClinicalDocumentItem(normalizedLabel, normalizedValue)
        }

        internal fun createExact(label: String, value: String): ClinicalDocumentItem {
            val normalizedLabel = label
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { "Item" }
            require(value.none { it == '\u0000' || it == '\u007f' }) {
                "Clinical document exact text contains unsupported control characters"
            }
            return ClinicalDocumentItem(normalizedLabel, value)
        }
    }
}

class ClinicalDocumentMetadata internal constructor(
    val provider: String?,
    val model: String?,
    val requestedModel: String?,
    val datasetHash: String,
    val schemaVersion: Int
)
