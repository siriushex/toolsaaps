package io.aaps.copilot.report

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.data.repository.ClinicalAdvisoryPriority
import io.aaps.copilot.data.repository.ClinicalAssociationMetric
import io.aaps.copilot.data.repository.ClinicalAdvisoryReport
import io.aaps.copilot.data.repository.ClinicalActivitySummary
import io.aaps.copilot.data.repository.ClinicalBasalContextSummary
import io.aaps.copilot.data.repository.ClinicalCareTeamDiscussionTopic
import io.aaps.copilot.data.repository.ClinicalCareTeamQuestion
import io.aaps.copilot.data.repository.ClinicalDataQuality
import io.aaps.copilot.data.repository.ClinicalDataQualityFlag
import io.aaps.copilot.data.repository.ClinicalEvidenceMetric
import io.aaps.copilot.data.repository.ClinicalEvidencePeriod
import io.aaps.copilot.data.repository.ClinicalEventSummary
import io.aaps.copilot.data.repository.ClinicalEventTypeAssociation
import io.aaps.copilot.data.repository.ClinicalFinding
import io.aaps.copilot.data.repository.ClinicalFindingConfidence
import io.aaps.copilot.data.repository.ClinicalHourlyMetric
import io.aaps.copilot.data.repository.ClinicalInsulinTotalSource
import io.aaps.copilot.data.repository.ClinicalLocalReport
import io.aaps.copilot.data.repository.ClinicalOpenAiClient
import io.aaps.copilot.data.repository.ClinicalPlannedActivitySummary
import io.aaps.copilot.data.repository.ClinicalOpenAiMetadata
import io.aaps.copilot.data.repository.ClinicalOpenAiResult
import io.aaps.copilot.data.repository.ClinicalPatternDirection
import io.aaps.copilot.data.repository.ClinicalPatternTopic
import io.aaps.copilot.data.repository.ClinicalPeriodSummary
import io.aaps.copilot.data.repository.ClinicalRecommendation
import io.aaps.copilot.data.repository.ClinicalRange
import io.aaps.copilot.data.repository.ClinicalRejectionCounts
import io.aaps.copilot.data.repository.ClinicalReportDatasetBuilder
import io.aaps.copilot.data.repository.ClinicalSafetyObservation
import io.aaps.copilot.data.repository.ClinicalSourceRowCounts
import io.aaps.copilot.data.repository.ClinicalSummaryStatus
import io.aaps.copilot.data.repository.ClinicalSummaryCalculator
import io.aaps.copilot.data.repository.ClinicalTherapyContextSummary
import io.aaps.copilot.data.repository.ClinicalTimeBand
import io.aaps.copilot.data.repository.AapsContextEventGateway
import io.aaps.copilot.data.repository.CopilotContextNoteMarker
import io.aaps.copilot.data.repository.EventTimelineRepository
import io.aaps.copilot.data.repository.EventTimelineSources
import io.aaps.copilot.data.repository.toDomain
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.eating.ProbableEatingWindow
import java.time.Instant
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClinicalReportDocumentTest {

    @Test
    fun productionNotesMarkerAndSourceIdDoNotReachLocalDocumentOrPdf() {
        val marker = CopilotContextNoteMarker.commandHeader(
            localEventId = "manual:pdf-production-notes",
            revision = 3L,
            operation = AapsContextEventGateway.Operation.UPDATE
        )
        val event = EventTimelineRepository().aggregate(
            EventTimelineSources(
                therapyEvents = listOf(
                    TherapyEventEntity(
                        id = "internal-pdf-aaps-row",
                        timestamp = GENERATED_AT - DAY_MS,
                        type = "note",
                        payloadJson = Gson().toJson(
                            mapOf("notes" to "ordinary PDF text $marker|remains ordinary")
                        )
                    ).toDomain(Gson())
                )
            ),
            nowTs = GENERATED_AT
        ).single()
        val summary = ClinicalEventSummary(
            localId = event.localId,
            type = event.type.name,
            subtype = event.subtype,
            startTs = event.startTs,
            endTs = event.endTs,
            severity = event.severity.name,
            source = event.source.name,
            title = event.title,
            note = event.note,
            status = event.status.name,
            provenance = event.provenance
        )

        val document = ClinicalReportDocument.from(
            localReport().copy(eventSummaries = listOf(summary)),
            complete = null
        )
        val documentText = document.flattenedText()
        val pdfText = captureClinicalPdfText(document)

        listOf(documentText, pdfText).forEach { text ->
            assertThat(text).contains("ordinary PDF text")
            assertThat(text).doesNotContain("internal-pdf-aaps-row")
            assertThat(text).doesNotContain("COPILOT_CONTEXT_V1")
            assertThat(text).doesNotContain("rev=3")
            assertThat(text).doesNotContain("op=UPDATE")
        }
    }

    @Test
    fun historicalCompensationEventsHaveDedicatedLocalReportSection() {
        val local = localReport().copy(
            eventSummaries = listOf(
                ClinicalEventSummary(
                    localId = "local-cycle-id",
                    type = "MENSTRUAL_CYCLE",
                    subtype = "LUTEAL",
                    startTs = 1_700_000_000_000L,
                    endTs = 1_700_086_400_000L,
                    severity = "MEDIUM",
                    source = "USER",
                    title = "Cycle context",
                    note = null,
                    status = "CLOSED",
                    provenance = "physio_context_tags"
                )
            )
        )

        val document = ClinicalReportDocument.from(local, complete = null)

        assertThat(document.sections.map { it.title })
            .contains("Local compensation event timeline (full history)")
        assertThat(document.flattenedText()).contains("MENSTRUAL_CYCLE / LUTEAL")
        assertThat(document.flattenedText()).doesNotContain("local-cycle-id")
        assertThat(document.flattenedText()).doesNotContain("physio_context_tags")
    }

    @Test
    fun localDocumentKeepsMoreThanThirtyTwoEventsWithoutDuplicatingRemotePreview() {
        val note = "local-note-" + "n".repeat(420)
        val events = (0 until 40).map { index ->
            ClinicalEventSummary(
                localId = "internal-$index",
                type = "CUSTOM",
                subtype = "subtype-$index",
                startTs = GENERATED_AT - (40L - index) * 60_000L,
                endTs = GENERATED_AT - (39L - index) * 60_000L,
                severity = "MEDIUM",
                source = "USER",
                title = "local-title-$index",
                note = if (index == 0) note else "note-$index",
                status = "CLOSED",
                provenance = "physio_context_tags"
            )
        }
        val remotePreview = """{"ev24":[{"type":"CUSTOM","subtype":"remote","startOffsetMin":-1,"durationMin":1,"severity":"MEDIUM","source":"USER","title":"Remote exact title","note":"Remote exact note"}],"ev7":[],"ev30":[]}"""

        val document = ClinicalReportDocument.from(
            localReport().copy(
                eventSummaries = events,
                remoteEventPreviewJson = remotePreview
            ),
            complete = null
        )
        val flattened = document.flattenedText()

        (0 until 40).forEach { index -> assertThat(flattened).contains("local-title-$index") }
        assertThat(flattened).contains(note)
        assertThat(flattened).contains("Local compensation event timeline (full history)")
        assertThat(flattened).doesNotContain("Remote bounded event payload preview")
        assertThat(flattened).doesNotContain("Remote exact title")
        assertThat(flattened).doesNotContain("Remote exact note")
        assertThat(flattened).doesNotContain("internal-0")
        assertThat(flattened).doesNotContain("physio_context_tags")

        val pdfText = captureClinicalPdfText(document)
        (0 until 40).forEach { index -> assertThat(pdfText).contains("local-title-$index") }
        assertThat(pdfText).doesNotContain("Remote exact title")
        assertThat(pdfText).doesNotContain("Remote exact note")
    }

    @Test
    fun legacyDocumentRejectsOverBudgetEventTextWithTypedPdfFailure() {
        val oversized = "x".repeat(ClinicalPdfLimits.DEFAULT.maxInputBytes + 1)
        val event = ClinicalEventSummary(
            localId = "private-oversized",
            type = "CUSTOM",
            subtype = "oversized",
            startTs = GENERATED_AT - 60_000L,
            endTs = GENERATED_AT,
            severity = "MEDIUM",
            source = "USER",
            title = oversized,
            note = null,
            status = "CLOSED",
            provenance = "fixture"
        )

        var thrown: Throwable? = null
        try {
            ClinicalReportDocument.from(
                localReport().copy(eventSummaries = listOf(event)),
                complete = null
            )
        } catch (error: Throwable) {
            thrown = error
        }

        assertThat(thrown).isInstanceOf(ClinicalPdfTooLargeException::class.java)
        assertThat((thrown as ClinicalPdfTooLargeException).dimension)
            .isEqualTo(ClinicalPdfLimitDimension.INPUT_BYTES)
    }

    @Test
    fun legacyDocumentRejectsGuaranteedPageOverflowBeforeEventMaterialization() {
        val events = CountOnlyEventList(24_000)

        var thrown: Throwable? = null
        try {
            ClinicalReportDocument.from(
                localReport().copy(eventSummaries = events),
                complete = null
            )
        } catch (error: Throwable) {
            thrown = error
        }

        assertThat(thrown).isInstanceOf(ClinicalPdfTooLargeException::class.java)
        assertThat((thrown as ClinicalPdfTooLargeException).dimension)
            .isEqualTo(ClinicalPdfLimitDimension.PAGES)
        assertThat(events.elementReads).isEqualTo(0)
    }

    @Test
    fun localDocumentRendersTypedEventAssociationsAsNonCausal() {
        val association = ClinicalEventTypeAssociation(
            type = "STRESS",
            eventCount = 3,
            totalDurationMinutes = 120L,
            glucose = ClinicalAssociationMetric(6, 6.4, "mmol/L"),
            trend = ClinicalAssociationMetric(3, 0.2, "mmol/L per event"),
            uam = ClinicalAssociationMetric(0, null, "g"),
            isf = ClinicalAssociationMetric(2, 2.1, "mmol/L/U"),
            cr = ClinicalAssociationMetric(2, 10.0, "g/U"),
            forecastError = ClinicalAssociationMetric(0, null, "mmol/L")
        )

        val flattened = ClinicalReportDocument.from(
            localReport().copy(eventTypeAssociations = listOf(association)),
            complete = null
        ).flattenedText()

        assertThat(flattened).contains("Event associations (non-causal)")
        assertThat(flattened).contains("STRESS")
        assertThat(flattened).contains("events=3")
        assertThat(flattened).contains("UAM samples=0, unavailable")
        assertThat(flattened).contains("forecast error samples=0, unavailable")
    }

    @Test
    fun probableMealWindowsRemainInsideTheirExactPeriodSection() {
        val local = localReport().let { report ->
            report.copy(
                summary30d = report.summary30d.copy(
                    probableMealWindows = listOf(
                        ProbableEatingWindow(
                            medianMinuteOfDay = 510,
                            startMinuteOfDay = 480,
                            endMinuteOfDay = 540,
                            iqrMinutes = 60,
                            supportDays = 9,
                            lookbackDays = 14,
                            episodeCount = 10,
                            enteredEpisodeCount = 8,
                            uamEpisodeCount = 2,
                            confidencePct = 64.2857
                        )
                    ),
                    recentProbableMealWindows = listOf(
                        ProbableEatingWindow(
                            medianMinuteOfDay = 495,
                            startMinuteOfDay = 480,
                            endMinuteOfDay = 525,
                            iqrMinutes = 45,
                            supportDays = 5,
                            lookbackDays = 7,
                            episodeCount = 5,
                            enteredEpisodeCount = 4,
                            uamEpisodeCount = 1,
                            confidencePct = 71.4286
                        )
                    )
                )
            )
        }

        val document = ClinicalReportDocument.from(local, complete = null)
        val section = document.section("30 days")
        val windows = section.items.filter { it.label.contains("meal window") }

        assertThat(windows).hasSize(2)
        assertThat(windows[0].flattenedLine()).contains("Probable meal window")
        assertThat(windows[0].flattenedLine()).contains("08:30")
        assertThat(windows[0].flattenedLine()).contains("08:00-09:00")
        assertThat(windows[0].flattenedLine()).contains("support=9/14 days")
        assertThat(windows[0].flattenedLine()).contains("episodes=10")
        assertThat(windows[0].flattenedLine()).contains("confidence=64.29%")
        assertThat(windows[1].flattenedLine()).contains("Recent probable meal window")
        assertThat(windows[1].flattenedLine()).contains("08:15")
        assertThat(windows[1].flattenedLine()).contains("support=5/7 days")
    }

    @Test
    fun malformedProbableMealWindowIsRejectedBeforePdfRendering() {
        val invalid = ProbableEatingWindow(
            medianMinuteOfDay = 1_500,
            startMinuteOfDay = 480,
            endMinuteOfDay = 540,
            iqrMinutes = 60,
            supportDays = 9,
            lookbackDays = 14,
            episodeCount = 9,
            enteredEpisodeCount = 9,
            uamEpisodeCount = 0,
            confidencePct = 64.3
        )
        val local = localReport().let { report ->
            report.copy(
                summary30d = report.summary30d.copy(probableMealWindows = listOf(invalid))
            )
        }

        assertInvalidLocal(local)
    }

    @Test
    fun localOnlyContainsMetricsCoverageHashSchemaAndDisclaimer() {
        val document = ClinicalReportDocument.from(localReport(), complete = null)

        assertThat(document.title).isEqualTo("AAPS Predictive Copilot clinical report")
        assertThat(document.createdAt).isEqualTo(Instant.ofEpochMilli(GENERATED_AT))
        assertThat(document.ranges.map { it.label })
            .containsExactly("24 hours", "7 days", "30 days").inOrder()
        assertThat(document.sections.map { it.title })
            .containsExactly("24 hours", "7 days", "30 days", "Data quality")
            .inOrder()
        assertThat(document.section("24 hours").items.associate { it.label to it.value })
            .containsAtLeastEntriesIn(
                mapOf(
                    "All delivered insulin" to "42.00 U",
                    "Delivered basal" to "24.00 U",
                    "Delivered bolus" to "18.00 U",
                    "Entered + UAM carbohydrates" to "99.00 g",
                    "AAPS carbohydrate reconciliation" to "99.00 g",
                    "Energy from recorded carbohydrates (includes UAM)" to
                        "396.00 kcal"
                )
            )
        assertThat(document.section("7 days").items.associate { it.label to it.value })
            .containsAtLeastEntriesIn(
                mapOf(
                "Mean glucose" to "6.20 mmol/L",
                "Median glucose" to "6.00 mmol/L",
                "Coefficient of variation" to "24.0%",
                "Time below 4.0" to "1.5%",
                "Time in range" to "82.0%",
                "Time above range" to "16.5%",
                "All delivered insulin" to "140.00 U",
                "Entered + UAM carbohydrates" to "420.00 g",
                "Energy from recorded carbohydrates (includes UAM)" to
                    "1680.00 kcal",
                "Mean target" to "6.10 mmol/L"
                )
            )
        assertThat(document.section("Data quality").items.associate { it.label to it.value })
            .containsAtLeastEntriesIn(
                mapOf(
                "7d coverage" to "96.5%",
                "7d covered buckets" to "1945 / 2016",
                "7d missing buckets" to "71",
                "7d maximum gap" to "15 min",
                "30d coverage" to "91.0%"
                )
            )
        assertThat(document.metadata.datasetHash).isEqualTo(DATASET_HASH)
        assertThat(document.metadata.schemaVersion)
            .isEqualTo(ClinicalReportDatasetBuilder.SCHEMA_VERSION)
        assertThat(document.metadata.provider).isNull()
        assertThat(document.metadata.model).isNull()
        assertThat(document.metadata.requestedModel).isNull()
        assertThat(document.disclaimer).isNotEmpty()
        assertThat(document.flattenedText()).contains("not medical advice")
    }

    @Test
    fun incompleteCopilotEventsAreNotLabelledAsDeliveredTherapyTotals() {
        val local = localReport().let { report ->
            report.copy(
                summary7d = report.summary7d.copy(
                    insulinTotalSource = ClinicalInsulinTotalSource.COPILOT_EVENTS,
                    aapsCarbsG = null
                )
            )
        }

        val metrics = ClinicalReportDocument.from(local, complete = null)
            .section("7 days")
            .items
            .associate { it.label to it.value }

        assertThat(metrics)
            .containsEntry("Insulin in Copilot events (not a full delivered total)", "140.00 U")
        assertThat(metrics)
            .containsEntry("Entered + UAM carbohydrates", "420.00 g")
        assertThat(metrics).doesNotContainKey("All delivered insulin")
        assertThat(metrics)
            .containsEntry("AAPS carbohydrate reconciliation", "Not available")
    }

    @Test
    fun plannedActivityPdfShowsReadOnlyTargetManagerDecisionAndBlockers() {
        val local = localReport().copy(
            plannedActivities = listOf(
                ClinicalPlannedActivitySummary(
                    type = "AEROBIC",
                    intensity = "MEDIUM",
                    plannedStartMs = GENERATED_AT,
                    plannedDurationMinutes = 45,
                    observedMinutes = null,
                    adherence = "NOT_MEASURED",
                    targetDecision = "BLOCK_SAFETY_BOUNDS",
                    targetBlockers = listOf("sensor_blocked", "stale_data")
                )
            )
        )

        val document = ClinicalReportDocument.from(local, complete = null)
        val activity = document.section("Energy and activity profile")

        val lines = activity.items.joinToString("\n", transform = ClinicalDocumentItem::flattenedLine)
        assertThat(lines).contains("BLOCK_SAFETY_BOUNDS")
        assertThat(lines).contains("sensor_blocked, stale_data")
    }

    @Test
    fun manualMealCaloriesAreLabelledAsExactLocalEntriesInPdf() {
        val local = localReport().copy(
            summary24h = checkNotNull(localReport().summary24h).copy(
                mealEnergy = io.aaps.copilot.data.repository.ClinicalMealEnergySummary(
                    carbohydrateEnergyKcal = 120.0,
                    manualMealEnergyKcal = 540.0,
                    estimatedTotalMealEnergyKcal =
                        io.aaps.copilot.data.repository.ClinicalRange(540.0, 540.0),
                    source = io.aaps.copilot.data.repository.ClinicalMealEnergySource.MANUAL_MEAL_ENTRY
                )
            )
        )

        val metrics = ClinicalReportDocument.from(local, complete = null)
            .section("24 hours")
            .items
            .associate { it.label to it.value }

        assertThat(metrics).containsEntry("Manual meal energy", "540.00 kcal")
        assertThat(metrics).containsEntry(
            "Total meal energy from manual meal entries",
            "540.00 kcal"
        )
    }

    @Test
    fun everyAvailableNestedPeriodSummaryFieldIsProjectedWithoutTruncation() {
        fun enriched(summary: ClinicalPeriodSummary, hour: Int) = summary.copy(
            weekdayPattern = listOf(ClinicalHourlyMetric(hour, 11, 6.1, 6.0)),
            weekendPattern = listOf(ClinicalHourlyMetric(hour + 1, 7, 6.4, 6.3)),
            confirmedInsulinU = 12.0,
            estimatedInsulinU = 1.5,
            deliveredBasalInsulinU = 8.0,
            deliveredBolusInsulinU = 5.5,
            enteredCarbsG = 35.0,
            uamCarbsG = 5.0,
            aapsCarbsG = 42.0,
            insulinEventCount = 8,
            estimatedInsulinEventCount = 2,
            enteredCarbEventCount = 3,
            uamCarbEventCount = 1,
            activity = ClinicalActivitySummary(
                coveragePct = 81.0,
                steps = 12_345.0,
                distanceKm = 8.2,
                activeMinutes = 95.0,
                activeCaloriesKcal = 640.0,
                meanActivityRatio = 1.15,
                maxActivityRatio = 1.8
            ),
            mealEnergy = io.aaps.copilot.data.repository.ClinicalMealEnergySummary(
                carbohydrateEnergyKcal = 160.0,
                manualMealEnergyKcal = 540.0,
                estimatedTotalMealEnergyKcal = ClinicalRange(540.0, 620.0),
                source = io.aaps.copilot.data.repository.ClinicalMealEnergySource.MIXED_MANUAL_AND_ESTIMATED,
                netEnergyKcal = ClinicalRange(-100.0, -20.0)
            ),
            basalContext = ClinicalBasalContextSummary(
                coveragePct = 92.0,
                meanProfileRateUph = 0.82,
                minProfileRateUph = 0.55,
                maxProfileRateUph = 1.15,
                meanProfilePercent = 105.0
            ),
            therapyContext = ClinicalTherapyContextSummary(
                infusionSetChanges = 2,
                sensorChanges = 1,
                insulinRefills = 3,
                pumpBatteryChanges = 4,
                exerciseEvents = 5,
                profileSwitches = 6
            ),
            probableMealWindows = listOf(
                ProbableEatingWindow(510, 480, 540, 60, 9, 14, 10, 8, 2, 64.2857)
            ),
            recentProbableMealWindows = listOf(
                ProbableEatingWindow(495, 480, 525, 45, 5, 7, 5, 4, 1, 71.4286)
            )
        )
        val base = localReport()
        val local = base.copy(
            summary24h = enriched(checkNotNull(base.summary24h), 6),
            summary7d = enriched(base.summary7d, 8),
            summary30d = enriched(base.summary30d, 10)
        )

        val document = ClinicalReportDocument.from(local, complete = null)

        listOf("24 hours", "7 days", "30 days").forEach { title ->
            val lines = document.section(title).items
                .joinToString("\n", transform = ClinicalDocumentItem::flattenedLine)
            listOf(
                "Insulin total source",
                "Insulin event count",
                "Estimated insulin event count",
                "Entered carbohydrate event count",
                "UAM carbohydrate event count",
                "Meal carbohydrate energy",
                "Mean activity ratio",
                "Maximum activity ratio",
                "Basal coverage",
                "Minimum profile basal rate",
                "Maximum profile basal rate",
                "Mean profile percent",
                "Pump battery changes",
                "Profile switches",
                "Weekday hour",
                "Weekend hour",
                "Probable meal window",
                "Recent probable meal window",
                "episodes=",
                "confidence="
            ).forEach { expected -> assertThat(lines).contains(expected) }
            assertThat(lines).contains("Meal carbohydrate energy: 160.00 kcal")
            assertThat(lines).contains("Estimated net energy: -100.00 kcal - -20.00 kcal")
        }
        assertThat(document.sections.map { it.title }).doesNotContain("Probable meal windows")
    }

    @Test
    fun completedReportContainsProviderAdvisorySafetyDiscussionAndQuestions() {
        val document = ClinicalReportDocument.from(localReport(), completeReport())

        assertThat(document.metadata.provider).isEqualTo("ANTHROPIC")
        assertThat(document.metadata.model).isEqualTo("claude-sonnet-5-20260715")
        assertThat(document.metadata.requestedModel).isEqualTo("claude-sonnet-5")
        assertThat(document.flattenedText())
            .contains("Requested model: claude-sonnet-5")
        assertThat(document.flattenedText())
            .contains("Actual model: claude-sonnet-5-20260715")
        assertThat(document.flattenedText()).doesNotContain("system-fingerprint-secret")
        assertThat(document.flattenedText()).doesNotContain("ledger-internal-id")
        assertThat(document.sections.map { it.title }).containsAtLeast(
            "AI summary",
            "Advisory findings",
            "Safety observations",
            "Discussion topics",
            "Care team questions"
        )
        assertThat(document.section("AI summary").items.associate { it.label to it.value })
            .containsAtLeastEntriesIn(
                mapOf(
                "7-day status" to "HIGH_VARIABILITY",
                "30-day status" to "STABLE",
                "AI data quality" to "MISSING_INTERVALS, PARTIAL_COVERAGE"
                )
            )
        assertThat(document.section("Advisory findings").items.map { it.value }).containsExactly(
            "LAST_7_DAYS | GLUCOSE_VARIABILITY | MORNING | INCREASING | HIGH | " +
                "COEFFICIENT_OF_VARIATION_PCT=38.50"
        )
        assertThat(document.section("Safety observations").items.map { it.value })
            .containsExactly("RECURRENT_LOW_PATTERN", "SENSOR_RELIABILITY_CONCERN")
            .inOrder()
        assertThat(document.section("Discussion topics").items.map { it.value }).containsExactly(
            "HIGH | LAST_7_DAYS | SENSOR_RELIABILITY",
            "MEDIUM | LAST_30_DAYS | ISF_CR_REVIEW"
        ).inOrder()
        assertThat(document.section("Care team questions").items.map { it.value })
            .containsExactly("ISF_CR_CONTEXT", "SENSOR_RELIABILITY_CONTEXT")
            .inOrder()
    }

    @Test
    fun mappingIsDeterministicForEquivalentAdvisoryOrderings() {
        val first = completeReport()
        val second = first.copy(
            report = first.report.copy(
                dataQuality = first.report.dataQuality.reversed(),
                safetyObservations = first.report.safetyObservations.reversed(),
                recommendations = first.report.recommendations.reversed(),
                careTeamQuestions = first.report.careTeamQuestions.reversed()
            )
        )

        val firstText = ClinicalReportDocument.from(localReport(), first).flattenedText()
        val secondText = ClinicalReportDocument.from(localReport(), second).flattenedText()

        assertThat(firstText).isEqualTo(secondText)
    }

    @Test
    fun completeRejectsCredentialAndUnambiguousEndpointShapedActualModels() {
        listOf(
            "",
            "model id",
            "m".repeat(129),
            "gsk_abcdefghijklmnopqrstuvwxyz0123456789",
            "api_key=top-secret-value",
            "192.168.1.10:11434",
            "localhost:11434/v1",
            "models.attacker.invalid:443/v1",
            "api.example.com/v1",
            "api.openai.com",
            "model.unknown",
            "https://nightscout.example/api"
        ).forEach { invalidModel ->
            assertThrows(IllegalArgumentException::class.java) {
                ClinicalReportDocument.from(
                    localReport(),
                    completeReport(model = invalidModel)
                )
            }
        }
    }

    @Test
    fun completeRejectsInvalidRequestedModelBeforeRendering() {
        listOf(
            "requested model",
            "r".repeat(129),
            "gsk_abcdefghijklmnopqrstuvwxyz0123456789",
            "api_key=top-secret-value",
            "10.0.0.8:8080",
            "[::1]:11434/v1",
            "provider.credentials.invalid",
            "llama.checkpoint",
            "provider.credentials.invalid:443/v1"
        ).forEach { invalidRequestedModel ->
            assertThrows(IllegalArgumentException::class.java) {
                ClinicalReportDocument.from(
                    localReport(),
                    completeReport(requestedModel = invalidRequestedModel)
                )
            }
        }
    }

    @Test
    fun legitimateDottedActualAndRequestedModelIdsArePreserved() {
        val document = ClinicalReportDocument.from(
            localReport(),
            completeReport(
                model = "model.gguf",
                requestedModel = "llama.model"
            )
        )

        assertThat(document.metadata.model).isEqualTo("model.gguf")
        assertThat(document.metadata.requestedModel).isEqualTo("llama.model")
        assertThat(document.flattenedText()).contains("Actual model: model.gguf")
        assertThat(document.flattenedText()).contains("Requested model: llama.model")
    }

    @Test
    fun validOpaqueHostFreeModelsArePreservedWithoutRedaction() {
        val document = ClinicalReportDocument.from(
            localReport(),
            completeReport(
                model = "publisher/model_name:latest",
                requestedModel = "publisher/model_name:stable"
            )
        )

        assertThat(document.metadata.model).isEqualTo("publisher/model_name:latest")
        assertThat(document.metadata.requestedModel)
            .isEqualTo("publisher/model_name:stable")
        assertThat(document.flattenedText()).contains("SENSOR_RELIABILITY_CONCERN")
    }

    @Test
    fun completeRejectsUnknownSchemaOrProviderMismatch() {
        listOf(
            completeReport(schemaName = "clinical_advisory_report_v2"),
            completeReport(schemaVersion = 2),
            completeReport(requestedProviderId = ClinicalAiProviderId.OPENAI)
        ).forEach { invalidComplete ->
            assertThrows(IllegalArgumentException::class.java) {
                ClinicalReportDocument.from(localReport(), invalidComplete)
            }
        }
    }

    @Test
    fun completeRejectsFiniteEvidenceOutsideEveryMetricRuleCategory() {
        val invalid = listOf(
            ClinicalEvidenceMetric.MEAN_GLUCOSE to 0.99,
            ClinicalEvidenceMetric.COEFFICIENT_OF_VARIATION_PCT to 200.01,
            ClinicalEvidenceMetric.TIME_IN_RANGE_PCT to 100.01,
            ClinicalEvidenceMetric.MAX_GAP_MINUTES to
                ClinicalOpenAiClient.MAX_30D_MINUTES + 1.0,
            ClinicalEvidenceMetric.TOTAL_INSULIN_UNITS_RECORDED to
                ClinicalOpenAiClient.MAX_RECORDED_INSULIN_UNITS + 0.01,
            ClinicalEvidenceMetric.TOTAL_CARBS_GRAMS_RECORDED to
                ClinicalOpenAiClient.MAX_RECORDED_CARBS_GRAMS + 0.01,
            ClinicalEvidenceMetric.SAMPLE_COUNT to
                ClinicalOpenAiClient.MAX_CANONICAL_30D_SAMPLE_COUNT + 1.0
        )

        invalid.forEach { (metric, value) ->
            assertInvalidComplete(finding(metric, value))
        }
    }

    @Test
    fun completeRejectsNonIntegralValuesForEveryIntegerEvidenceMetric() {
        listOf(
            ClinicalEvidenceMetric.MAX_GAP_MINUTES,
            ClinicalEvidenceMetric.DURATION_MINUTES,
            ClinicalEvidenceMetric.SAMPLE_COUNT
        ).forEach { metric ->
            assertInvalidComplete(finding(metric, 1.5))
        }
    }

    @Test
    fun completeAcceptsExactEvidenceRuleEdges() {
        val boundaries = mapOf(
            ClinicalEvidenceMetric.MEAN_GLUCOSE to (1.0 to 40.0),
            ClinicalEvidenceMetric.MEDIAN_GLUCOSE to (1.0 to 40.0),
            ClinicalEvidenceMetric.MEAN_TARGET_MMOL to (1.0 to 40.0),
            ClinicalEvidenceMetric.COEFFICIENT_OF_VARIATION_PCT to (0.0 to 200.0),
            ClinicalEvidenceMetric.TIME_BELOW_RANGE_PCT to (0.0 to 100.0),
            ClinicalEvidenceMetric.TIME_IN_RANGE_PCT to (0.0 to 100.0),
            ClinicalEvidenceMetric.TIME_ABOVE_RANGE_PCT to (0.0 to 100.0),
            ClinicalEvidenceMetric.COVERAGE_PCT to (0.0 to 100.0),
            ClinicalEvidenceMetric.MAX_GAP_MINUTES to
                (0.0 to ClinicalOpenAiClient.MAX_30D_MINUTES.toDouble()),
            ClinicalEvidenceMetric.DURATION_MINUTES to
                (0.0 to ClinicalOpenAiClient.MAX_30D_MINUTES.toDouble()),
            ClinicalEvidenceMetric.TOTAL_INSULIN_UNITS_RECORDED to
                (0.0 to ClinicalOpenAiClient.MAX_RECORDED_INSULIN_UNITS),
            ClinicalEvidenceMetric.TOTAL_CARBS_GRAMS_RECORDED to
                (0.0 to ClinicalOpenAiClient.MAX_RECORDED_CARBS_GRAMS),
            ClinicalEvidenceMetric.SAMPLE_COUNT to
                (0.0 to ClinicalOpenAiClient.MAX_CANONICAL_30D_SAMPLE_COUNT.toDouble())
        )

        boundaries.forEach { (metric, range) ->
            listOf(range.first, range.second).forEach { value ->
                val complete = completeReport().let { report ->
                    report.copy(
                        report = report.report.copy(patterns = listOf(finding(metric, value)))
                    )
                }
                assertThat(ClinicalReportDocument.from(localReport(), complete).sections)
                    .isNotEmpty()
            }
        }
    }

    @Test
    fun identicalRequestedAndActualModelIsRenderedOnce() {
        val document = ClinicalReportDocument.from(
            localReport(),
            completeReport(
                model = "claude-sonnet-5",
                requestedModel = "claude-sonnet-5"
            )
        )

        assertThat(document.metadata.model).isEqualTo("claude-sonnet-5")
        assertThat(document.metadata.requestedModel).isNull()
        assertThat(document.flattenedText()).contains("Model: claude-sonnet-5")
        assertThat(document.flattenedText()).doesNotContain("Requested model:")
        assertThat(document.flattenedText()).doesNotContain("Actual model:")
    }

    @Test
    fun localSummaryRejectsWrongPeriodsAndImpossibleWindows() {
        val local = localReport()
        val seven = local.summary7d
        val thirty = local.summary30d

        listOf(
            local.copy(summary7d = seven.copy(days = 8)),
            local.copy(summary30d = thirty.copy(days = 29)),
            local.copy(summary7d = seven.copy(fromTs = seven.throughTs)),
            local.copy(
                summary7d = seven.copy(
                    fromTs = seven.fromTs +
                        ClinicalSummaryCalculator.WINDOW_DURATION_TOLERANCE_MS + 1L
                )
            ),
            local.copy(
                summary7d = seven.copy(
                    fromTs = seven.fromTs + 1L,
                    throughTs = seven.throughTs + 1L
                )
            ),
            local.copy(
                summary30d = thirty.copy(
                    fromTs = thirty.fromTs - ClinicalSummaryCalculator.BUCKET_MS,
                    throughTs = thirty.throughTs - ClinicalSummaryCalculator.BUCKET_MS
                )
            )
        ).forEach(::assertInvalidLocal)
    }

    @Test
    fun localSummaryAcceptsExistingDurationToleranceAndBucketFloorLag() {
        val local = localReport()
        val seven = local.summary7d
        val throughTs = local.generatedAt - ClinicalSummaryCalculator.BUCKET_MS + 1L
        val accepted = local.copy(
            summary7d = seven.copy(
                fromTs = throughTs - 7L * DAY_MS +
                    ClinicalSummaryCalculator.WINDOW_DURATION_TOLERANCE_MS,
                throughTs = throughTs
            )
        )

        assertThat(ClinicalReportDocument.from(accepted, complete = null).ranges)
            .hasSize(3)
    }

    @Test
    fun localSummaryAcceptsSingleInclusiveCoverageBucketOvershoot() {
        val local = localReport()
        val seven = local.summary7d
        val accepted = local.copy(
            summary7d = seven.copy(
                quality = seven.quality.copy(
                    coveredBuckets = seven.quality.expectedBuckets + 1,
                    missingBuckets = 0
                )
            )
        )

        assertThat(ClinicalReportDocument.from(accepted, complete = null).ranges)
            .hasSize(3)
    }

    @Test
    fun localSummaryRejectsInvalidPercentagesAndCoverageCounts() {
        val local = localReport()
        val seven = local.summary7d
        val quality = seven.quality

        listOf(
            local.copy(summary7d = seven.copy(coveragePct = Double.NaN)),
            local.copy(summary7d = seven.copy(coveragePct = 100.01)),
            local.copy(summary7d = seven.copy(timeBelow4Pct = -0.01)),
            local.copy(summary7d = seven.copy(timeInRangePct = Double.POSITIVE_INFINITY)),
            local.copy(summary7d = seven.copy(timeAboveRangePct = 100.01)),
            local.copy(
                summary7d = seven.copy(
                    quality = quality.copy(expectedBuckets = -1)
                )
            ),
            local.copy(
                summary7d = seven.copy(
                    quality = quality.copy(coveredBuckets = -1)
                )
            ),
            local.copy(
                summary7d = seven.copy(
                    quality = quality.copy(missingBuckets = -1)
                )
            ),
            local.copy(
                summary7d = seven.copy(
                    quality = quality.copy(missingBuckets = quality.missingBuckets - 1)
                )
            ),
            local.copy(
                summary7d = seven.copy(
                    quality = quality.copy(
                        coveredBuckets = quality.expectedBuckets + 1,
                        missingBuckets = 1
                    )
                )
            ),
            local.copy(
                summary7d = seven.copy(
                    quality = quality.copy(
                        coveredBuckets = quality.expectedBuckets + 2,
                        missingBuckets = 0
                    )
                )
            ),
            local.copy(
                summary7d = seven.copy(
                    quality = quality.copy(
                        expectedBuckets = quality.expectedBuckets - 1,
                        coveredBuckets = quality.coveredBuckets - 1
                    )
                )
            ),
            local.copy(
                summary7d = seven.copy(
                    quality = quality.copy(
                        rejected = quality.rejected.copy(glucose = -1)
                    )
                )
            )
        ).forEach(::assertInvalidLocal)
    }

    @Test
    fun localSummaryRejectsInvalidClinicalValuesAndTotals() {
        val local = localReport()
        val seven = local.summary7d

        listOf(
            local.copy(summary7d = seven.copy(meanMmol = 0.99)),
            local.copy(summary7d = seven.copy(medianMmol = 40.01)),
            local.copy(summary7d = seven.copy(meanTargetMmol = Double.NaN)),
            local.copy(summary7d = seven.copy(coefficientOfVariationPct = -0.01)),
            local.copy(summary7d = seven.copy(coefficientOfVariationPct = 200.01)),
            local.copy(summary7d = seven.copy(totalInsulinU = -0.01)),
            local.copy(summary7d = seven.copy(totalInsulinU = Double.POSITIVE_INFINITY)),
            local.copy(summary7d = seven.copy(totalInsulinU = 10_000.01)),
            local.copy(summary7d = seven.copy(totalCarbsG = -0.01)),
            local.copy(summary7d = seven.copy(totalCarbsG = 100_000.01)),
            local.copy(
                summary7d = seven.copy(
                    quality = seven.quality.copy(maxGapMinutes = -1)
                )
            ),
            local.copy(
                summary7d = seven.copy(
                    quality = seven.quality.copy(maxGapMinutes = 7 * 24 * 60 + 1)
                )
            )
        ).forEach(::assertInvalidLocal)
    }

    @Test
    fun documentDoesNotSilentlyTruncateItemsOrValues() {
        val manyPatterns = (0 until 80).map { index ->
            finding(
                topic = ClinicalPatternTopic.entries[index % ClinicalPatternTopic.entries.size],
                value = index.toDouble()
            )
        }
        val complete = completeReport().let {
            it.copy(report = it.report.copy(patterns = manyPatterns))
        }

        val document = ClinicalReportDocument.from(
            localReport(),
            complete.copy(metadata = complete.metadata.copy(model = "m".repeat(120)))
        )

        assertThat(document.section("Advisory findings").items).hasSize(80)
        assertThat(
            ClinicalDocumentItem.create("Long value", "x".repeat(400)).value
        ).isEqualTo("x".repeat(400))
        assertThat(document.metadata.model!!.length)
            .isAtMost(ClinicalReportDocument.MAX_METADATA_VALUE_LENGTH)
    }

    @Test
    fun collectionsAreDefensiveImmutableSnapshots() {
        val document = ClinicalReportDocument.from(localReport(), completeReport())
        val originalSectionCount = document.sections.size
        val originalItemCount = document.sections.first().items.size

        assertThat(runCatching {
            @Suppress("UNCHECKED_CAST")
            (document.sections as MutableList<ClinicalDocumentSection>).clear()
        }.isFailure).isTrue()
        assertThat(runCatching {
            @Suppress("UNCHECKED_CAST")
            (document.sections.first().items as MutableList<ClinicalDocumentItem>).clear()
        }.isFailure).isTrue()
        assertThat(document.sections).hasSize(originalSectionCount)
        assertThat(document.sections.first().items).hasSize(originalItemCount)
    }

    private fun ClinicalReportDocument.section(title: String): ClinicalDocumentSection =
        sections.single { it.title == title }

    private class CountOnlyEventList(
        override val size: Int
    ) : AbstractList<ClinicalEventSummary>() {
        var elementReads = 0
            private set

        override fun get(index: Int): ClinicalEventSummary {
            elementReads += 1
            error("Legacy preflight materialized an event")
        }
    }

    private fun assertInvalidLocal(local: ClinicalLocalReport) {
        assertThrows(IllegalArgumentException::class.java) {
            ClinicalReportDocument.from(local, complete = null)
        }
    }

    private fun assertInvalidComplete(finding: ClinicalFinding) {
        val complete = completeReport().let { report ->
            report.copy(report = report.report.copy(patterns = listOf(finding)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClinicalReportDocument.from(localReport(), complete)
        }
    }

    private fun localReport() = ClinicalLocalReport(
        summary24h = summary(
            days = 1,
            fromTs = GENERATED_AT - DAY_MS,
            coverage = 98.0,
            mean = 6.1,
            median = 6.0,
            cv = 20.0,
            below = 1.0,
            inRange = 90.0,
            above = 9.0,
            insulin = 42.0,
            carbs = 99.0,
            target = 6.0,
            expected = 288,
            covered = 282,
            missing = 6,
            maxGap = 10
        ).copy(
            deliveredBasalInsulinU = 24.0,
            deliveredBolusInsulinU = 18.0,
            aapsCarbsG = 99.0
        ),
        summary7d = summary(
            days = 7,
            fromTs = GENERATED_AT - 7 * DAY_MS,
            coverage = 96.5,
            mean = 6.2,
            median = 6.0,
            cv = 24.0,
            below = 1.5,
            inRange = 82.0,
            above = 16.5,
            insulin = 140.0,
            carbs = 420.0,
            target = 6.1,
            expected = 2016,
            covered = 1945,
            missing = 71,
            maxGap = 15
        ),
        summary30d = summary(
            days = 30,
            fromTs = GENERATED_AT - 30 * DAY_MS,
            coverage = 91.0,
            mean = 6.5,
            median = 6.3,
            cv = 29.0,
            below = 2.0,
            inRange = 76.0,
            above = 22.0,
            insulin = 600.0,
            carbs = 1_800.0,
            target = 6.2,
            expected = 8_640,
            covered = 7_862,
            missing = 778,
            maxGap = 45
        ),
        requestHash = DATASET_HASH,
        generatedAt = GENERATED_AT,
        zoneId = "Asia/Tbilisi"
    )

    private fun summary(
        days: Int,
        fromTs: Long,
        coverage: Double,
        mean: Double,
        median: Double,
        cv: Double,
        below: Double,
        inRange: Double,
        above: Double,
        insulin: Double,
        carbs: Double,
        target: Double,
        expected: Int,
        covered: Int,
        missing: Int,
        maxGap: Int
    ) = ClinicalPeriodSummary(
        days = days,
        fromTs = fromTs,
        throughTs = GENERATED_AT,
        coveragePct = coverage,
        meanMmol = mean,
        medianMmol = median,
        coefficientOfVariationPct = cv,
        timeBelow4Pct = below,
        timeInRangePct = inRange,
        timeAboveRangePct = above,
        totalInsulinU = insulin,
        totalCarbsG = carbs,
        meanTargetMmol = target,
        weekdayPattern = emptyList(),
        weekendPattern = emptyList(),
        quality = ClinicalDataQuality(
            expectedBuckets = expected,
            coveredBuckets = covered,
            missingBuckets = missing,
            maxGapMinutes = maxGap,
            rejected = ClinicalRejectionCounts(
                glucose = 1,
                therapy = 2,
                target = 3,
                forecast = 4,
                telemetry = 5
            )
        ),
        insulinTotalSource = ClinicalInsulinTotalSource.AAPS_RAW_HISTORY,
        aapsCarbsG = carbs
    )

    private fun completeReport(
        model: String = "claude-sonnet-5-20260715",
        requestedModel: String = "claude-sonnet-5",
        schemaName: String = "clinical_advisory_report_v3",
        schemaVersion: Int = 3,
        providerId: ClinicalAiProviderId = ClinicalAiProviderId.ANTHROPIC,
        requestedProviderId: ClinicalAiProviderId = providerId
    ) = ClinicalOpenAiResult(
        report = ClinicalAdvisoryReport(
            summary7dStatus = ClinicalSummaryStatus.HIGH_VARIABILITY,
            summary30dStatus = ClinicalSummaryStatus.STABLE,
            dataQuality = listOf(
                ClinicalDataQualityFlag.PARTIAL_COVERAGE,
                ClinicalDataQualityFlag.MISSING_INTERVALS
            ),
            patterns = listOf(finding()),
            safetyObservations = listOf(
                ClinicalSafetyObservation.SENSOR_RELIABILITY_CONCERN,
                ClinicalSafetyObservation.RECURRENT_LOW_PATTERN
            ),
            recommendations = listOf(
                ClinicalRecommendation(
                    careTeamDiscussionTopic = ClinicalCareTeamDiscussionTopic.ISF_CR_REVIEW,
                    priority = ClinicalAdvisoryPriority.MEDIUM,
                    evidenceFindingIndices = listOf(0),
                    period = ClinicalEvidencePeriod.LAST_30_DAYS
                ),
                ClinicalRecommendation(
                    careTeamDiscussionTopic = ClinicalCareTeamDiscussionTopic.SENSOR_RELIABILITY,
                    priority = ClinicalAdvisoryPriority.HIGH,
                    evidenceFindingIndices = listOf(0),
                    period = ClinicalEvidencePeriod.LAST_7_DAYS
                )
            ),
            careTeamQuestions = listOf(
                ClinicalCareTeamQuestion.SENSOR_RELIABILITY_CONTEXT,
                ClinicalCareTeamQuestion.ISF_CR_CONTEXT
            )
        ),
        metadata = ClinicalOpenAiMetadata(
            model = model,
            requestedModel = requestedModel,
            systemFingerprint = "system-fingerprint-secret",
            schemaName = schemaName,
            schemaVersion = schemaVersion,
            datasetSchemaVersion = ClinicalReportDatasetBuilder.SCHEMA_VERSION,
            requestHash = DATASET_HASH,
            chunkCount = 2,
            usedSynthesis = true,
            coverageLedgerHash = "ledger-internal-id",
            maxRequestBytes = 10,
            maxResponseBytes = 10,
            totalRequestBytes = 20,
            totalResponseBytes = 20,
            durationMs = 100,
            sourceRows = ClinicalSourceRowCounts(
                glucose = 100,
                insulin = 20,
                carbs = 10,
                targets = 5
            ),
            providerId = providerId,
            requestedProviderId = requestedProviderId
        )
    )

    private fun finding(
        topic: ClinicalPatternTopic = ClinicalPatternTopic.GLUCOSE_VARIABILITY,
        value: Double = 38.5
    ) = ClinicalFinding(
        topic = topic,
        period = ClinicalEvidencePeriod.LAST_7_DAYS,
        direction = ClinicalPatternDirection.INCREASING,
        confidence = ClinicalFindingConfidence.HIGH,
        timeBand = ClinicalTimeBand.MORNING,
        evidenceMetric = ClinicalEvidenceMetric.COEFFICIENT_OF_VARIATION_PCT,
        evidenceValue = value
    )

    private fun finding(
        metric: ClinicalEvidenceMetric,
        value: Double
    ) = finding(value = value).copy(evidenceMetric = metric)

    private companion object {
        const val GENERATED_AT = 1_790_000_000_000L
        const val DAY_MS = 86_400_000L
        const val DATASET_HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
