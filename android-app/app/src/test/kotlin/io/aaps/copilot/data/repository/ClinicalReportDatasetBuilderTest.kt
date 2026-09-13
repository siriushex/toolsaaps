package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonParser
import io.aaps.copilot.data.local.dao.ClinicalForecastProjection
import io.aaps.copilot.data.local.dao.ClinicalGlucoseProjection
import io.aaps.copilot.data.local.dao.ClinicalTelemetryProjection
import io.aaps.copilot.data.local.dao.ClinicalTherapyProjection
import io.aaps.copilot.data.local.entity.SensitivityRuntimeSnapshotEntity
import io.aaps.copilot.data.local.entity.MealEnergyOverrideEntity
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventSource
import io.aaps.copilot.domain.model.ResolvedGlucosePoint
import io.aaps.copilot.domain.profile.CalorieGoalMode
import io.aaps.copilot.domain.profile.EnergyProfileSettings
import io.aaps.copilot.domain.profile.FoodProfileMode
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.AbstractList
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ClinicalReportDatasetBuilderTest {

    @Test
    fun alertDerivedBudgetContinuesThroughFinalContextRowsAtExactBoundary() = runTest {
        val now = 40L * DAY_MS
        val raw = ClinicalGlucoseProjection(now - MINUTE_MS, 5.8, "nightscout", "OK")
        fun builder(derivedRows: Int) = ClinicalReportDatasetBuilder(
            source = emptySource(),
            resolveCalibratedGlucose = { _, _ -> emptyList() },
            alertAiSource = AlertAiDatasetSource {
                AlertAiDatasetSourceSnapshot(
                    report = ClinicalReportSnapshot(
                        glucose = listOf(raw),
                        therapy = emptyList(),
                        forecasts = emptyList(),
                        telemetry = emptyList(),
                        summaryTelemetry = emptyList()
                    ),
                    calibratedGlucose = listOf(raw),
                    timelineEvents = emptyList(),
                    derivedBudget = AlertAiRetainedDerivedBudget(maxRows = derivedRows)
                )
            }
        )

        val exact = builder(5).buildAlertAiDataset(now)
        val overflow = runCatching { builder(4).buildAlertAiDataset(now) }.exceptionOrNull()

        assertThat(exact.glucose14d).hasSize(1)
        assertThat(exact.detail24h.glucose).hasSize(1)
        assertThat(exact.detail24h.calibratedGlucose).hasSize(1)
        assertThat(overflow).isInstanceOf(AlertAiContextException.LimitExceeded::class.java)
    }

    @Test
    fun alertDatasetReadsOnlyBoundedFourteenDaysAndSkipsManualReportAssembly() = runTest {
        val now = 40L * DAY_MS + 2L * MINUTE_MS
        var requestedAt: Long? = null
        val source = object : ClinicalReportDataSource {
            override suspend fun glucose(fromTs: Long, toTs: Long) = emptyList<ClinicalGlucoseProjection>()
            override suspend fun therapy(fromTs: Long, toTs: Long, types: List<String>) =
                emptyList<ClinicalTherapyProjection>()
            override suspend fun forecasts(fromTs: Long, toTs: Long) =
                emptyList<ClinicalForecastProjection>()
            override suspend fun telemetry(fromTs: Long, toTs: Long, keys: List<String>) =
                emptyList<ClinicalTelemetryProjection>()
        }
        val builder = ClinicalReportDatasetBuilder(
            source = source,
            resolveCalibratedGlucose = { _, _ -> emptyList() },
            alertAiSource = AlertAiDatasetSource { triggerTs ->
                requestedAt = triggerTs
                AlertAiDatasetSourceSnapshot(
                    report = ClinicalReportSnapshot(
                        glucose = emptyList(),
                        therapy = emptyList(),
                        forecasts = emptyList(),
                        telemetry = emptyList(),
                        summaryTelemetry = emptyList()
                    ),
                    calibratedGlucose = emptyList(),
                    timelineEvents = emptyList()
                )
            }
        )

        val dataset = builder.buildAlertAiDataset(now)

        assertThat(requestedAt).isEqualTo(now)
        assertThat(dataset.generatedAt).isEqualTo(now)
        assertThat(dataset::class.java.declaredFields.map { it.name })
            .containsNoneOf("summary30d", "glucose30d", "compactJson", "sha256")
    }

    @Test
    fun compactSchemaVersionIsFourteenForCanonicalAiEventContext() {
        assertThat(ClinicalReportDatasetBuilder.SCHEMA_VERSION).isEqualTo(14)
    }

    @Test
    fun canonicalAiEventWireUsesStrictDtoAndPreservesFullCustomText() {
        val generatedAt = 40L * DAY_MS
        val title = "T".repeat(60)
        val note = "N".repeat(500)
        val startTs = generatedAt - 2L * MINUTE_MS
        val endTs = generatedAt - MINUTE_MS
        val dataset = minimalDataset().withEventPeriods(
            generatedAt = generatedAt,
            events = listOf(
                ClinicalEventSummary(
                    localId = "internal-event-id",
                    type = "CUSTOM",
                    subtype = "MANUAL",
                    startTs = startTs,
                    endTs = endTs,
                    severity = "MEDIUM",
                    source = "USER",
                    title = title,
                    note = note,
                    status = "CLOSED",
                    provenance = "physio_context_tags:rowId=private"
                )
            )
        )

        val root = JsonParser.parseString(ClinicalReportDatasetBuilder.serialize(dataset)).asJsonObject
        val event = root.getAsJsonArray("ev24").single().asJsonObject

        assertThat(event.keySet()).containsExactly(
            "type",
            "subtype",
            "startTs",
            "endTs",
            "severity",
            "source",
            "title",
            "note"
        ).inOrder()
        assertThat(event.get("startTs").asLong).isEqualTo(startTs)
        assertThat(event.get("endTs").asLong).isEqualTo(endTs)
        assertThat(event.get("title").asString).isEqualTo(title)
        assertThat(event.get("note").asString).isEqualTo(note)
        assertThat(event.toString()).doesNotContain("internal-event-id")
        assertThat(event.toString()).doesNotContain("rowId")
        assertThat(event.toString()).doesNotContain("startOffsetMin")
        assertThat(event.toString()).doesNotContain("durationMin")
    }

    @Test
    fun productionNotesThreeDayEventKeepsWireDurationWithoutIdOrMarkerLeakage() {
        val day = 24L * 60L * 60L * 1_000L
        val start = 40L * day
        val generatedAt = start + 2L * day
        val marker = CopilotContextNoteMarker.commandHeader(
            localEventId = "manual:report-production-notes",
            revision = 2L,
            operation = AapsContextEventGateway.Operation.UPDATE
        )
        val projected = EventTimelineRepository().aggregate(
            EventTimelineSources(
                therapyEvents = listOf(
                    TherapyEventEntity(
                        id = "internal-aaps-row-id",
                        timestamp = start,
                        type = "note",
                        payloadJson = Gson().toJson(
                            mapOf(
                                "notes" to "ordinary production text $marker|remains ordinary",
                                "endTs" to (start + 3L * day).toString()
                            )
                        )
                    ).toDomain(Gson())
                )
            ),
            nowTs = generatedAt
        ).single()
        val dataset = minimalDataset().withEventPeriods(
            generatedAt = generatedAt,
            events = listOf(
                ClinicalEventSummary(
                    localId = projected.localId,
                    type = projected.type.name,
                    subtype = projected.subtype,
                    startTs = projected.startTs,
                    endTs = projected.endTs,
                    severity = projected.severity.name,
                    source = projected.source.name,
                    title = projected.title,
                    note = projected.note,
                    status = projected.status.name,
                    provenance = projected.provenance
                )
            )
        )

        val compact = ClinicalReportDatasetBuilder.serialize(dataset)
        val root = JsonParser.parseString(compact).asJsonObject

        assertThat(root.getAsJsonArray("ev24").single().asJsonObject["startTs"].asLong)
            .isEqualTo(start)
        assertThat(root.getAsJsonArray("ev24").single().asJsonObject["endTs"].asLong)
            .isEqualTo(start + 3L * day)
        assertThat(compact).contains("ordinary production text")
        assertThat(compact).doesNotContain("internal-aaps-row-id")
        assertThat(compact).doesNotContain("COPILOT_CONTEXT_V1")
        assertThat(compact).doesNotContain(AapsContextEventGateway.localEventHash("manual:report-production-notes").take(16))
        assertThat(compact).doesNotContain("rev=2")
        assertThat(compact).doesNotContain("op=UPDATE")
    }

    @Test
    fun reportContainsProbableMealWindowsFromLastFourteenCompletedDays() = runTest {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 8, 1, 12, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val therapy = buildList {
            repeat(4) { dayOffset ->
                val ts = ZonedDateTime.of(2026, 7, 31 - dayOffset, 8, 30, 0, 0, zone)
                    .toInstant()
                    .toEpochMilli()
                add(
                    therapyProjection(
                        id = "meal-$dayOffset",
                        ts = ts,
                        type = "carbs",
                        payload =
                            """{"aapsCarbId":${900 + dayOffset},"aapsCarbAmount":30,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
                    )
                )
            }
        }
        val builder = ClinicalReportDatasetBuilder(
            source = FakeClinicalReportDataSource(
                glucose = emptyList(),
                therapy = therapy,
                forecasts = emptyList(),
                telemetry = emptyList()
            ),
            resolveCalibratedGlucose = { _, _ -> emptyList() }
        )

        val payload = builder.build(now, zone)
        val windows = payload.dataset.summary30d.probableMealWindows
        val recentWindows = payload.dataset.summary30d.recentProbableMealWindows
        val compactWindows = JsonParser.parseString(payload.compactJson).asJsonObject
            .getAsJsonObject("s30")
            .getAsJsonArray("mealWindows")

        assertThat(payload.dataset.summary7d.probableMealWindows).isEmpty()
        assertThat(windows).hasSize(1)
        assertThat(windows.single().medianMinuteOfDay).isEqualTo(8 * 60 + 30)
        assertThat(windows.single().supportDays).isEqualTo(4)
        assertThat(recentWindows).hasSize(1)
        assertThat(recentWindows.single().lookbackDays).isEqualTo(7)
        assertThat(compactWindows).hasSize(1)
    }

    @Test
    fun detail24hSummaryKeepsCanonicalCarbsWhenAapsAggregateDiffers() = runTest {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 7, 31, 12, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val source = FakeClinicalReportDataSource(
            glucose = emptyList(),
            therapy = listOf(
                therapyProjection(
                    id = "entered",
                    ts = now - 2 * MINUTE_MS,
                    type = "meal_bolus",
                    payload =
                        """{"units":3,"aapsCarbId":901,"aapsCarbAmount":20,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
                ),
                therapyProjection(
                    id = "uam",
                    ts = now - MINUTE_MS,
                    type = "carbs",
                    payload =
                        """{"aapsCarbId":902,"aapsCarbAmount":15,"aapsCarbIsValid":true,"aapsCarbClassification":"UAM_SYNTHETIC","aapsCarbSynthetic":true,"aapsCarbSuperseded":false}"""
                )
            ),
            forecasts = emptyList(),
            telemetry = emptyList()
        )
        val builder = ClinicalReportDatasetBuilder(
            source = source,
            resolveCalibratedGlucose = { _, _ -> emptyList() },
            aapsTddSource = ClinicalAapsTddSource { detailFromTs, _, _, throughTs ->
                ClinicalAapsTddSnapshot(
                    generatedAt = now,
                    detail24h = ClinicalAapsTddPeriod(
                        fromTs = detailFromTs,
                        throughTs = throughTs,
                        basalInsulinU = 24.0,
                        bolusInsulinU = 18.0,
                        totalInsulinU = 42.0,
                        carbsG = 99.0
                    ),
                    period7d = null,
                    period30d = null
                )
            }
        )

        val payload = builder.build(now, zone)
        val summary = checkNotNull(payload.dataset.summary24h)
        val compact = JsonParser.parseString(payload.compactJson).asJsonObject
            .getAsJsonObject("s24")

        assertThat(payload.dataset.detail24h.therapy).hasSize(2)
        assertThat(summary.days).isEqualTo(1)
        assertThat(summary.totalInsulinU).isEqualTo(42.0)
        assertThat(summary.deliveredBasalInsulinU).isEqualTo(24.0)
        assertThat(summary.deliveredBolusInsulinU).isEqualTo(18.0)
        assertThat(summary.totalCarbsG).isEqualTo(35.0)
        assertThat(summary.enteredCarbsG).isEqualTo(20.0)
        assertThat(summary.uamCarbsG).isEqualTo(15.0)
        assertThat(summary.aapsCarbsG).isEqualTo(99.0)
        assertThat(compact.get("insulin").asDouble).isEqualTo(42.0)
        assertThat(compact.get("insulinBasal").asDouble).isEqualTo(24.0)
        assertThat(compact.get("insulinBolus").asDouble).isEqualTo(18.0)
        assertThat(compact.get("carbs").asDouble).isEqualTo(35.0)
        assertThat(compact.get("carbsEnergyKcal").asDouble).isEqualTo(140.0)
        assertThat(compact.get("carbsSource").asString).isEqualTo("COPILOT_CANONICAL_EVENTS")
        assertThat(compact.get("carbsAaps").asDouble).isEqualTo(99.0)
    }

    @Test
    fun nonBucketAlignedBuilderPayloadIsReadyWithExactPeriodMembership() = runTest {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 7, 31, 12, 2, 3, 456_000_000, zone)
            .toInstant()
            .toEpochMilli()
        val summaryThrough = now - Math.floorMod(now, ClinicalSummaryCalculator.BUCKET_MS)
        val detailFrom = now - DAY_MS
        val summaryFrom = summaryThrough - DAY_MS
        val events = listOf(
            CompensationEvent(
                localId = "summary-start",
                startTs = summaryFrom,
                endTs = summaryFrom,
                type = CompensationEventType.CUSTOM,
                source = EventSource.USER,
                title = "Summary start"
            ),
            CompensationEvent(
                localId = "detail-start",
                startTs = detailFrom,
                endTs = detailFrom,
                type = CompensationEventType.CUSTOM,
                source = EventSource.USER,
                title = "Detail start"
            ),
            CompensationEvent(
                localId = "summary-through",
                startTs = summaryThrough,
                endTs = summaryThrough,
                type = CompensationEventType.CUSTOM,
                source = EventSource.USER,
                title = "Summary through"
            )
        )
        val builder = ClinicalReportDatasetBuilder(
            source = FakeClinicalReportDataSource(
                glucose = emptyList(),
                therapy = emptyList(),
                forecasts = emptyList(),
                telemetry = emptyList()
            ),
            resolveCalibratedGlucose = { _, _ -> emptyList() },
            eventTimelineSource = ClinicalEventTimelineSource { _, _ -> events }
        )

        val payload = builder.build(now, zone)
        val dataset = payload.dataset
        val summary24h = checkNotNull(dataset.summary24h)
        val local = ClinicalLocalReport(
            summary24h = summary24h,
            summary7d = dataset.summary7d,
            summary30d = dataset.summary30d,
            requestHash = payload.sha256,
            generatedAt = dataset.generatedAt,
            zoneId = dataset.zoneId,
            energyProfile = dataset.energyProfile,
            plannedActivities = dataset.plannedActivities,
            forecastQuality = dataset.detail24h.forecastQuality,
            eventSummaries = dataset.eventSummaries,
            eventTypeAssociations = dataset.eventTypeAssociations,
            remoteEventPreviewJson = ClinicalReportDatasetBuilder.remoteEventPreviewJson(dataset)
        )

        val result = ClinicalPdfContentSourceFactory.create(payload, local, complete = null)

        assertThat(dataset.detail24h.fromTs).isEqualTo(detailFrom)
        assertThat(dataset.detail24h.throughTs).isEqualTo(now)
        assertThat(summary24h.fromTs).isEqualTo(summaryFrom)
        assertThat(summary24h.throughTs).isEqualTo(summaryThrough)
        assertThat(summary24h.fromTs).isNotEqualTo(dataset.detail24h.fromTs)
        assertThat(summary24h.throughTs).isNotEqualTo(dataset.detail24h.throughTs)
        assertThat(result).isInstanceOf(ClinicalPdfContentSourceResult.Ready::class.java)

        val stableKeys = buildList {
            (result as ClinicalPdfContentSourceResult.Ready).source.open().use { cursor ->
                while (true) {
                    val block = cursor.next() ?: break
                    block.stableKey?.let(::add)
                }
            }
        }
        assertThat(stableKeys).doesNotContain("24h/event/summary-start")
        assertThat(stableKeys).contains("24h/event/detail-start")
        assertThat(stableKeys).contains("24h/event/summary-through")
        listOf("7d", "30d").forEach { period ->
            assertThat(stableKeys).contains("$period/event/summary-start")
            assertThat(stableKeys).contains("$period/event/detail-start")
            assertThat(stableKeys).contains("$period/event/summary-through")
        }
    }

    @Test
    fun manualMealCaloriesOverrideProfileEstimateForMatchingCanonicalMeal() = runTest {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 7, 31, 12, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val builder = ClinicalReportDatasetBuilder(
            source = FakeClinicalReportDataSource(
                glucose = emptyList(),
                therapy = listOf(
                    therapyProjection(
                        id = "aaps-row-11",
                        ts = now - MINUTE_MS,
                        type = "carbs",
                        payload =
                            """{"aapsCarbId":11,"aapsRevisionId":"rev-11","aapsCarbAmount":30,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
                    )
                ),
                forecasts = emptyList(),
                telemetry = emptyList()
            ),
            resolveCalibratedGlucose = { _, _ -> emptyList() },
            energyProfileSource = ClinicalEnergyProfileSource { _, _ ->
                ClinicalEnergyProfileSourceSnapshot(
                    settings = EnergyProfileSettings(
                        foodProfileMode = FoodProfileMode.MANUAL,
                        manualFoodProfile = MealAbsorptionProfile.MIXED,
                        calorieGoalMode = CalorieGoalMode.MANUAL_CLINICIAN,
                        manualCalorieTargetKcal = 2_400
                    ),
                    latestInference = null,
                    plannedActivities = emptyList(),
                    manualMealEnergyOverrides = listOf(
                        MealEnergyOverrideEntity(
                            canonicalTherapyIdentity = "11",
                            therapyRevisionHash = "rev-11",
                            caloriesKcal = 540.0,
                            updatedAtMs = now
                        )
                    )
                )
            }
        )

        val summary = checkNotNull(builder.build(now, zone).dataset.summary24h)

        assertThat(summary.mealEnergy.carbohydrateEnergyKcal).isEqualTo(120.0)
        assertThat(summary.mealEnergy.manualMealEnergyKcal).isEqualTo(540.0)
        assertThat(summary.mealEnergy.source).isEqualTo(ClinicalMealEnergySource.MANUAL_MEAL_ENTRY)
        assertThat(summary.mealEnergy.estimatedTotalMealEnergyKcal)
            .isEqualTo(ClinicalRange(540.0, 540.0))
    }

    @Test
    fun manualMealCaloriesDoNotApplyWhenCanonicalMealRevisionWasReplaced() = runTest {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 7, 31, 12, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val builder = ClinicalReportDatasetBuilder(
            source = FakeClinicalReportDataSource(
                glucose = emptyList(),
                therapy = listOf(
                    therapyProjection(
                        id = "aaps-row-11-replaced",
                        ts = now - MINUTE_MS,
                        type = "carbs",
                        payload =
                            """{"aapsCarbId":11,"aapsRevisionId":"revision-new","aapsCarbAmount":30,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
                    )
                ),
                forecasts = emptyList(),
                telemetry = emptyList()
            ),
            resolveCalibratedGlucose = { _, _ -> emptyList() },
            energyProfileSource = ClinicalEnergyProfileSource { _, _ ->
                ClinicalEnergyProfileSourceSnapshot(
                    settings = EnergyProfileSettings(),
                    latestInference = null,
                    plannedActivities = emptyList(),
                    manualMealEnergyOverrides = listOf(
                        MealEnergyOverrideEntity(
                            canonicalTherapyIdentity = "11",
                            therapyRevisionHash = "revision-old",
                            caloriesKcal = 540.0,
                            updatedAtMs = now
                        )
                    )
                )
            }
        )

        val summary = checkNotNull(builder.build(now, zone).dataset.summary24h)

        assertThat(summary.mealEnergy.manualMealEnergyKcal).isNull()
        assertThat(summary.mealEnergy.source).isEqualTo(ClinicalMealEnergySource.NOT_AVAILABLE)
    }

    @Test
    fun plannedActivitiesReadMatchingTargetDecisionLocallyButNeverSerializeItForAi() = runTest {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 7, 31, 12, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val start = ZonedDateTime.of(2026, 7, 31, 9, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val builder = ClinicalReportDatasetBuilder(
            source = FakeClinicalReportDataSource(
                glucose = emptyList(),
                therapy = emptyList(),
                forecasts = emptyList(),
                telemetry = listOf(
                    ClinicalTelemetryProjection(
                        start,
                        "active_minutes",
                        10.0,
                        "TRUSTED",
                        source = "health_connect",
                        unit = "min"
                    ),
                    ClinicalTelemetryProjection(
                        start + 25L * MINUTE_MS,
                        "active_minutes",
                        35.0,
                        "TRUSTED",
                        source = "health_connect",
                        unit = "min"
                    )
                )
            ),
            resolveCalibratedGlucose = { _, _ -> emptyList() },
            energyProfileSource = ClinicalEnergyProfileSource { _, _ ->
                ClinicalEnergyProfileSourceSnapshot(
                    settings = EnergyProfileSettings(),
                    latestInference = null,
                    plannedActivities = listOf(
                        PlannedActivityEventEntity(
                            eventId = "activity-1",
                            enabled = true,
                            title = "Private title",
                            activityType = "AEROBIC",
                            intensity = "MEDIUM",
                            localStartIso = "2026-07-31T09:00",
                            durationMinutes = 45,
                            timezoneId = zone.id,
                            recurrenceDaysMask = 0,
                            recurrenceEndEpochDay = null,
                            revision = 2L,
                            createdAtMs = start,
                            updatedAtMs = start
                        ),
                        PlannedActivityEventEntity(
                            eventId = "activity-2",
                            enabled = true,
                            title = "Another private title",
                            activityType = "WALKING",
                            intensity = "LIGHT",
                            localStartIso = "2026-07-31T10:00",
                            durationMinutes = 30,
                            timezoneId = zone.id,
                            recurrenceDaysMask = 0,
                            recurrenceEndEpochDay = null,
                            revision = 1L,
                            createdAtMs = start,
                            updatedAtMs = start
                        ),
                        PlannedActivityEventEntity(
                            eventId = "activity-before-period",
                            enabled = true,
                            title = "Boundary activity",
                            activityType = "STRENGTH",
                            intensity = "HIGH",
                            localStartIso = "2026-07-01T11:30",
                            durationMinutes = 30,
                            timezoneId = zone.id,
                            recurrenceDaysMask = 0,
                            recurrenceEndEpochDay = null,
                            revision = 1L,
                            createdAtMs = start,
                            updatedAtMs = start
                        )
                    ),
                    targetManagerEvidence = ClinicalTargetManagerEvidenceSnapshot(
                        complete = true,
                        rows = listOf(
                            ClinicalTargetManagerEvidence(
                                id = "target-decision-1",
                                timestamp = start - 75 * MINUTE_MS,
                                outcome = "BLOCK_SENSOR_TRUST",
                                winnerJson =
                                    """{"activityProposal":{"occurrenceId":"activity-1","occurrenceRevision":2}}""",
                                reasonCodesJson = "[\"sensor_untrusted\"]"
                            )
                        )
                    )
                )
            }
        )

        val payload = builder.build(now, zone)
        val occurrence = payload.dataset.plannedActivities.first { it.type == "AEROBIC" }
        val noDecision = payload.dataset.plannedActivities.first { it.type == "WALKING" }

        assertThat(occurrence.targetDecision).isEqualTo("BLOCK_SENSOR_TRUST")
        assertThat(occurrence.targetBlockers).containsExactly("sensor_untrusted")
        assertThat(occurrence.observedMinutes).isEqualTo(25)
        assertThat(noDecision.targetDecision).isEqualTo("NO_DECISION")
        assertThat(noDecision.targetBlockers).isEmpty()
        assertThat(payload.dataset.plannedActivities.map(ClinicalPlannedActivitySummary::type))
            .doesNotContain("STRENGTH")
        assertThat(payload.compactJson).doesNotContain("BLOCK_SENSOR_TRUST")
        assertThat(payload.compactJson).doesNotContain("sensor_untrusted")
        assertThat(payload.compactJson).doesNotContain("Private title")
    }

    @Test
    fun plannedActivitiesRetainExactTailAndCarryInButExcludeNonOverlappingEdges() = runTest {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 7, 31, 12, 2, 3, 456_000_000, zone)
            .toInstant()
            .toEpochMilli()
        val summaryThrough = now - Math.floorMod(now, ClinicalSummaryCalculator.BUCKET_MS)
        val from30d = summaryThrough - THIRTY_DAYS_MS
        val carryIn = from30d - 30L * MINUTE_MS
        val endingAtFrom = from30d - 60L * MINUTE_MS
        val tail = summaryThrough + MINUTE_MS
        var loadedWindow: Pair<Long, Long>? = null
        fun event(
            id: String,
            type: String,
            startTs: Long,
            durationMinutes: Int
        ) = PlannedActivityEventEntity(
            eventId = id,
            enabled = true,
            title = "private-$id",
            activityType = type,
            intensity = "MEDIUM",
            localStartIso = Instant.ofEpochMilli(startTs).atZone(zone).toLocalDateTime().toString(),
            durationMinutes = durationMinutes,
            timezoneId = zone.id,
            recurrenceDaysMask = 0,
            recurrenceEndEpochDay = null,
            revision = 1L,
            createdAtMs = startTs,
            updatedAtMs = startTs
        )
        val source = FakeClinicalReportDataSource(
                glucose = emptyList(),
                therapy = emptyList(),
                forecasts = emptyList(),
                telemetry = listOf(
                    ClinicalTelemetryProjection(
                        carryIn,
                        "active_minutes",
                        10.0,
                        "TRUSTED",
                        source = "health_connect",
                        unit = "min"
                    ),
                    ClinicalTelemetryProjection(
                        carryIn + 25L * MINUTE_MS,
                        "active_minutes",
                        35.0,
                        "TRUSTED",
                        source = "health_connect",
                        unit = "min"
                    )
                )
            )
        val builder = ClinicalReportDatasetBuilder(
            source = source,
            resolveCalibratedGlucose = { _, _ -> emptyList() },
            energyProfileSource = ClinicalEnergyProfileSource { fromTs, throughTs ->
                loadedWindow = fromTs to throughTs
                ClinicalEnergyProfileSourceSnapshot(
                    settings = EnergyProfileSettings(),
                    latestInference = null,
                    plannedActivities = listOf(
                        event("carry-in", "AEROBIC", carryIn, 60),
                        event("ends-at-from", "STRENGTH", endingAtFrom, 60),
                        event("tail", "WALKING", tail, 30),
                        event("exact-through", "MIXED", now, 20),
                        event("after-through", "STRENGTH", now + 1L, 20)
                    ),
                    targetManagerEvidence = ClinicalTargetManagerEvidenceSnapshot(
                        complete = true,
                        rows = listOf(
                            ClinicalTargetManagerEvidence(
                                id = "carry-in-decision",
                                timestamp = carryIn,
                                outcome = "BLOCK_SENSOR_TRUST",
                                winnerJson =
                                    """{"activityProposal":{"occurrenceId":"carry-in","occurrenceRevision":1}}""",
                                reasonCodesJson = "[\"carry_in_evidence\"]"
                            )
                        )
                    )
                )
            }
        )

        val payload = builder.build(now, zone)

        val evidenceFrom = from30d -
            ClinicalPlannedActivityPeriodPolicy.MAX_SUPPORTED_DURATION_MS
        assertThat(source.summaryTelemetryWindow).isEqualTo(evidenceFrom to now)
        assertThat(loadedWindow).isEqualTo(from30d to now)
        assertThat(payload.dataset.plannedActivities.map { it.type to it.plannedStartMs })
            .containsExactly(
                "AEROBIC" to carryIn,
                "WALKING" to tail,
                "MIXED" to now
            ).inOrder()
        val carryInSummary = payload.dataset.plannedActivities.first { it.type == "AEROBIC" }
        assertThat(carryInSummary.observedMinutes).isEqualTo(25)
        assertThat(carryInSummary.targetDecision).isEqualTo("BLOCK_SENSOR_TRUST")
        assertThat(carryInSummary.targetBlockers).containsExactly("carry_in_evidence")
        val wireStarts = JsonParser.parseString(payload.compactJson).asJsonObject
            .getAsJsonArray("pa")
            .map { it.asJsonObject.get("start").asLong }
        assertThat(wireStarts).hasSize(3)
        assertThat(JsonParser.parseString(payload.compactJson).asJsonObject
            .getAsJsonObject("d24").getAsJsonArray("m")).isEmpty()
        assertThat(payload.compactJson).doesNotContain("carry_in_evidence")
    }

    @Test
    fun reportWhitelistIncludesCompletePhysicalActivityAndBasalContext() {
        assertThat(ClinicalReportDatasetBuilder.TELEMETRY_KEYS).containsAtLeast(
            "activity_ratio",
            "steps_count",
            "distance_km",
            "active_minutes",
            "calories_active_kcal",
            "basal_rate_u_h",
            "profile_percent"
        )
    }

    @Test
    fun schemaFiveEmitsNumericCurrentSnapshotFromPersistedClinicalSignals() = runTest {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 7, 31, 12, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val glucoseTs = now - 5 * MINUTE_MS
        val signalTs = now - 2 * MINUTE_MS
        val forecastTs = now - 14 * MINUTE_MS
        val source = FakeClinicalReportDataSource(
            glucose = listOf(
                ClinicalGlucoseProjection(glucoseTs, 6.123_6, "nightscout", "OK")
            ),
            therapy = listOf(
                therapyProjection(
                    id = "active-target",
                    ts = now - 10 * MINUTE_MS,
                    type = "temp_target",
                    payload = """{"targetBottomMmol":5.2,"targetTopMmol":6.1,"duration":30}"""
                )
            ),
            forecasts = listOf(
                ClinicalForecastProjection(forecastTs, 30, 6.789_6, 6.0, 7.2)
            ),
            telemetry = listOf(
                ClinicalTelemetryProjection(signalTs, "iob_effective_units", 1.234_6, "OK", unit = "U"),
                ClinicalTelemetryProjection(signalTs, "cob_effective_grams", 18.456_7, "OK", unit = "g"),
                ClinicalTelemetryProjection(signalTs, "isf_runtime_selected_value", 2.345_6, "OK", unit = "mmol/L/U"),
                ClinicalTelemetryProjection(signalTs, "isf_runtime_source_resolved", 2.0, "OK"),
                ClinicalTelemetryProjection(signalTs, "cr_runtime_selected_value", 9.876_5, "OK", unit = "g/U"),
                ClinicalTelemetryProjection(signalTs, "cr_runtime_source_resolved", 1.0, "OK"),
                ClinicalTelemetryProjection(signalTs, "uam_runtime_control_flag", 1.0, "OK"),
                ClinicalTelemetryProjection(
                    signalTs,
                    "uam_runtime_equivalent_carbs_grams",
                    12.345_6,
                    "OK",
                    unit = "g"
                ),
                ClinicalTelemetryProjection(signalTs, "uam_runtime_confidence", 0.876_5, "OK"),
                ClinicalTelemetryProjection(signalTs, "sensor_quality_score", 0.912_6, "OK"),
                ClinicalTelemetryProjection(signalTs, "sensor_quality_blocked", 0.0, "OK"),
                ClinicalTelemetryProjection(signalTs, "sensor_age_hours", 73.456_7, "OK", unit = "h"),
                ClinicalTelemetryProjection(signalTs, "sensor_lag_minutes", 4.567_8, "OK", unit = "min"),
                ClinicalTelemetryProjection(
                    signalTs,
                    "activity_ratio",
                    1.234_6,
                    "OK",
                    source = "local_sensor"
                ),
                ClinicalTelemetryProjection(
                    signalTs,
                    "steps_count",
                    4_321.0,
                    "OK",
                    source = "local_sensor",
                    unit = "steps"
                )
            )
        )
        val builder = ClinicalReportDatasetBuilder(
            source = source,
            resolveCalibratedGlucose = { raw, _ ->
                raw.map { point ->
                    ResolvedGlucosePoint(
                        ts = point.ts,
                        rawMmol = point.valueMmol,
                        calibratedMmol = point.valueMmol + 0.2,
                        gain = 1.0,
                        offsetMmolApplied = 0.2,
                        calibrationApplied = true,
                        source = point.source,
                        quality = point.quality
                    )
                }
            }
        )

        val payload = builder.build(now, zone)
        val root = JsonParser.parseString(payload.compactJson).asJsonObject

        assertThat(root.has("c")).isTrue()
        assertThat(payload.dataset.schemaVersion)
            .isEqualTo(ClinicalReportDatasetBuilder.SCHEMA_VERSION)
        assertThat(
            ClinicalReportDataset::class.java.declaredFields.map { it.name }
        ).contains("currentSnapshot")
        assertThat(root.getAsJsonObject("c").entrySet().associate { (key, value) ->
            key to value.takeUnless { it.isJsonNull }?.asDouble
        }).containsExactly(
            "g", 6.124,
            "gc", 6.324,
            "age", 5.0,
            "p30", 6.79,
            "p30Age", 14.0,
            "iob", 1.235,
            "cob", 18.457,
            "isf", 2.346,
            "isfSrc", 2.0,
            "cr", 9.877,
            "crSrc", 1.0,
            "uam", 1.0,
            "uamCarbs", 12.346,
            "uamConf", 0.877,
            "sensorQ", 0.913,
            "sensorBlocked", 0.0,
            "sensorAge", 73.457,
            "sensorLag", 4.568,
            "activity", 1.235,
            "steps", 4_321.0,
            "targetLow", 5.2,
            "targetHigh", 6.1
        )
    }

    @Test
    fun currentReportUsesAtomicSensitivitySnapshotInsteadOfConflictingLooseTelemetry() = runTest {
        val now = 1_785_556_800_000L
        val signalTs = now - MINUTE_MS
        val atomic = SensitivityRuntimeSnapshotEntity(
            cycleId = "cycle-atomic-report",
            settingsRevision = 77L,
            generatedAt = signalTs,
            isfRequestedSource = "AAPS",
            isfResolvedSource = "AAPS",
            isfRawAaps = 4.4,
            isfRawEvidence = 5.1,
            isfRawCopilot = 4.9,
            isfBlended = null,
            isfEffective = 4.4,
            isfConfidence = 1.0,
            isfFallbackReason = null,
            crRequestedSource = "COPILOT",
            crResolvedSource = "COPILOT_NATIVE",
            crRawAaps = 9.0,
            crRawEvidence = 12.0,
            crRawCopilot = 13.0,
            crBlended = null,
            crEffective = 13.0,
            crConfidence = 0.8,
            crFallbackReason = null
        )
        val source = FakeClinicalReportDataSource(
            glucose = listOf(ClinicalGlucoseProjection(signalTs, 6.0, "aaps", "OK")),
            therapy = emptyList(),
            forecasts = emptyList(),
            telemetry = listOf(
                ClinicalTelemetryProjection(signalTs, "isf_runtime_selected_value", 1.1, "OK", unit = "mmol/L/U"),
                ClinicalTelemetryProjection(signalTs, "isf_runtime_source_resolved", 3.0, "OK"),
                ClinicalTelemetryProjection(signalTs, "cr_runtime_selected_value", 59.0, "OK", unit = "g/U"),
                ClinicalTelemetryProjection(signalTs, "cr_runtime_source_resolved", 2.0, "OK")
            ),
            sensitivityRuntime = atomic
        )

        val dataset = ClinicalReportDatasetBuilder(source).build(now, ZoneId.of("UTC")).dataset

        assertThat(dataset.currentSnapshot.sensitivityCycleId).isEqualTo("cycle-atomic-report")
        assertThat(dataset.currentSnapshot.sensitivitySettingsRevision).isEqualTo(77L)
        assertThat(dataset.currentSnapshot.selectedIsfMmolPerUnit).isEqualTo(4.4)
        assertThat(dataset.currentSnapshot.selectedIsfSource).isEqualTo("AAPS")
        assertThat(dataset.currentSnapshot.selectedCrGramsPerUnit).isEqualTo(13.0)
        assertThat(dataset.currentSnapshot.selectedCrSource).isEqualTo("COPILOT_NATIVE")
    }

    @Test
    fun currentSnapshotUsesNewestFallbackAndExpiresRuntimeTelemetryAndForecast() {
        val now = 10_000_000L
        val detail = ClinicalDetailWindow(
            fromTs = now - DAY_MS,
            throughTs = now,
            glucose = emptyList(),
            calibratedGlucose = emptyList(),
            therapy = emptyList(),
            targets = emptyList(),
            forecasts = listOf(
                ClinicalForecastPoint(
                    now - 15 * MINUTE_MS - 1L,
                    30,
                    8.0,
                    7.0,
                    9.0
                )
            ),
            telemetry = listOf(
                ClinicalTelemetryPoint(
                    now - 10 * MINUTE_MS,
                    "iob_effective_units",
                    9.0,
                    "OK"
                ),
                ClinicalTelemetryPoint(now - 2 * MINUTE_MS, "iob_units", 2.0, "OK"),
                ClinicalTelemetryPoint(
                    now - 3 * MINUTE_MS,
                    "cob_effective_grams",
                    11.0,
                    "OK"
                ),
                ClinicalTelemetryPoint(now - 3 * MINUTE_MS, "cob_grams", 22.0, "OK"),
                ClinicalTelemetryPoint(
                    now - 15 * MINUTE_MS - 1L,
                    "activity_ratio",
                    1.4,
                    "OK"
                )
            )
        )

        val snapshot = ClinicalReportDatasetBuilder.currentSnapshot(now, detail)

        assertThat(snapshot.effectiveIobUnits).isEqualTo(2.0)
        assertThat(snapshot.effectiveCobGrams).isEqualTo(11.0)
        assertThat(snapshot.activityRatio).isNull()
        assertThat(snapshot.prediction30mMmol).isNull()
    }

    @Test
    fun currentSnapshotPairsIsfAndCrValuesWithLatestSourceTimestamp() {
        val now = 20_000_000L
        val detail = ClinicalDetailWindow(
            fromTs = now - DAY_MS,
            throughTs = now,
            glucose = emptyList(),
            calibratedGlucose = emptyList(),
            therapy = emptyList(),
            targets = emptyList(),
            forecasts = emptyList(),
            telemetry = listOf(
                ClinicalTelemetryPoint(
                    now - MINUTE_MS,
                    "isf_runtime_source_resolved",
                    0.0,
                    "OK"
                ),
                ClinicalTelemetryPoint(
                    now - 2 * MINUTE_MS,
                    "isf_runtime_selected_value",
                    2.5,
                    "OK"
                ),
                ClinicalTelemetryPoint(
                    now - 3 * MINUTE_MS,
                    "cr_runtime_source_resolved",
                    2.0,
                    "OK"
                ),
                ClinicalTelemetryPoint(
                    now - 3 * MINUTE_MS,
                    "cr_runtime_selected_value",
                    9.0,
                    "OK"
                )
            )
        )

        val snapshot = ClinicalReportDatasetBuilder.currentSnapshot(now, detail)

        assertThat(snapshot.selectedIsfSourceCode).isEqualTo(0.0)
        assertThat(snapshot.selectedIsfMmolPerUnit).isNull()
        assertThat(snapshot.selectedCrSourceCode).isEqualTo(2.0)
        assertThat(snapshot.selectedCrGramsPerUnit).isEqualTo(9.0)
    }

    @Test
    fun activeCalibrationKeepsRawDetailAndUsesCalibratedGlucoseForReportSeries() = runTest {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 7, 31, 12, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val sampleTs = now - 5 * MINUTE_MS
        val source = FakeClinicalReportDataSource(
            glucose = listOf(ClinicalGlucoseProjection(sampleTs, 6.0, "nightscout", "OK")),
            therapy = emptyList(),
            forecasts = emptyList(),
            telemetry = emptyList()
        )
        val builder = ClinicalReportDatasetBuilder(
            source = source,
            resolveCalibratedGlucose = { raw, _ ->
                raw.map { point ->
                    ResolvedGlucosePoint(
                        ts = point.ts,
                        rawMmol = point.valueMmol,
                        calibratedMmol = point.valueMmol + 1.0,
                        gain = 1.0,
                        offsetMmolApplied = 1.0,
                        calibrationApplied = true,
                        source = point.source,
                        quality = point.quality
                    )
                }
            }
        )

        val payload = builder.build(now, zone)
        val dataset = payload.dataset
        val detailJson = JsonParser.parseString(payload.compactJson)
            .asJsonObject
            .getAsJsonObject("d24")

        assertThat(dataset.detail24h.glucose).containsExactly(
            ClinicalGlucosePoint(sampleTs, 6.0)
        )
        assertThat(dataset.detail24h.calibratedGlucose).containsExactly(
            ClinicalGlucosePoint(sampleTs, 7.0)
        )
        assertThat(dataset.glucose7d.map(ClinicalGlucosePoint::mmol)).containsExactly(7.0)
        assertThat(dataset.glucose30d.map(ClinicalGlucosePoint::mmol)).containsExactly(7.0)
        assertThat(dataset.summary7d.meanMmol).isEqualTo(7.0)
        assertThat(dataset.summary30d.meanMmol).isEqualTo(7.0)
        assertThat(detailJson.getAsJsonArray("g").single().asJsonArray[1].asDouble).isEqualTo(6.0)
        assertThat(detailJson.getAsJsonArray("gc").single().asJsonArray[1].asDouble).isEqualTo(7.0)
    }

    @Test
    fun therapySanitizationEmitsOnlyNumericFieldsAndSyntheticClassification() {
        val secret = "sk-forbidden-secret"
        val row = ClinicalTherapyProjection(
            ts = 100L,
            type = "carbs",
            payloadJson = """
                {
                  "carbs": 15,
                  "source": "uam_engine",
                  "note": "UAM_ENGINE|$secret|name@example.com|+995555123456",
                  "androidId": "device-123",
                  "nightscoutUrl": "https://private.example"
                }
            """.trimIndent()
        )

        val point = ClinicalReportDatasetBuilder.sanitizeTherapy(row)
        val json = Gson().toJson(point)

        assertThat(point?.carbsG).isEqualTo(15.0)
        assertThat(point?.syntheticUam).isTrue()
        assertThat(json).doesNotContain(secret)
        assertThat(json).doesNotContain("name@example.com")
        assertThat(json).doesNotContain("+995555123456")
        assertThat(json).doesNotContain("private.example")
        assertThat(json).doesNotContain("device-123")
    }

    @Test
    fun canonicalAapsCarbsExcludeInvalidRowsDeduplicateReconciledRowsAndRetainUam() {
        val validReal = ClinicalTherapyProjection(
            rowId = "aaps-carb-101",
            ts = 100_000L,
            type = "carbs",
            payloadJson =
                """{"aapsCarbId":101,"carbs":20,"classification":"AAPS_REAL","isValid":true}"""
        )
        val invalid = ClinicalTherapyProjection(
            rowId = "aaps-carb-102",
            ts = 110_000L,
            type = "carbs",
            payloadJson =
                """{"aapsCarbId":102,"carbs":40,"classification":"AAPS_REAL","isValid":false}"""
        )
        val syntheticUam = ClinicalTherapyProjection(
            rowId = "aaps-carb-103",
            ts = 120_000L,
            type = "carbs",
            payloadJson =
                """{"aapsCarbId":103,"carbs":12,"classification":"UAM_SYNTHETIC","isValid":true,"source":"uam_engine","synthetic":true}"""
        )

        val canonical = ClinicalReportDatasetBuilder.canonicalTherapyEvents(
            listOf(validReal, validReal.copy(), invalid, syntheticUam)
                .mapNotNull(ClinicalReportDatasetBuilder::sanitizeTherapyEvent)
        ).map(ClinicalTherapyEvent::point)

        assertThat(canonical).hasSize(2)
        assertThat(canonical.filterNot(ClinicalTherapyPoint::syntheticUam).sumOf {
            it.carbsG ?: 0.0
        }).isEqualTo(20.0)
        assertThat(canonical.filter(ClinicalTherapyPoint::syntheticUam).sumOf {
            it.carbsG ?: 0.0
        }).isEqualTo(12.0)
    }

    @Test
    fun canonicalCarbComponentsDoNotDiscardValidInsulinAndOverrideLegacyCarbs() {
        fun sanitize(rowId: String, payload: String) = requireNotNull(
            ClinicalReportDatasetBuilder.sanitizeTherapyEvent(
                ClinicalTherapyProjection(
                    rowId = rowId,
                    ts = 100_000L,
                    type = "meal_bolus",
                    payloadJson = payload
                )
            )
        ).point

        val invalid = sanitize(
            "invalid-carb",
            """{"insulin":3,"carbs":40,"isValid":false,"aapsCarbId":201,"aapsCarbAmount":40,"aapsCarbIsValid":false,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
        )
        val superseded = sanitize(
            "superseded-carb",
            """{"insulin":3,"carbs":40,"aapsCarbId":202,"aapsCarbAmount":40,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":true}"""
        )
        val real = sanitize(
            "real-carb",
            """{"insulin":3,"carbs":99,"synthetic":true,"aapsCarbId":203,"aapsCarbAmount":24,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
        )
        val uam = sanitize(
            "uam-carb",
            """{"insulin":3,"carbs":99,"synthetic":false,"aapsCarbId":204,"aapsCarbAmount":12,"aapsCarbIsValid":true,"aapsCarbClassification":"UAM_SYNTHETIC","aapsCarbSynthetic":true,"aapsCarbSuperseded":false}"""
        )
        val correction = sanitize(
            "correction-carb",
            """{"insulin":3,"carbs":40,"aapsCarbId":205,"aapsCarbAmount":-10,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_CORRECTION","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
        )

        assertThat(invalid.insulinU).isEqualTo(3.0)
        assertThat(invalid.carbsG).isNull()
        assertThat(superseded.insulinU).isEqualTo(3.0)
        assertThat(superseded.carbsG).isNull()
        assertThat(real.carbsG).isEqualTo(24.0)
        assertThat(real.syntheticUam).isFalse()
        assertThat(uam.carbsG).isEqualTo(12.0)
        assertThat(uam.syntheticUam).isTrue()
        assertThat(correction.insulinU).isEqualTo(3.0)
        assertThat(correction.carbsG).isNull()
    }

    @Test
    fun canonicalAuthorityIsIndependentFromMissingMalformedAndQuotedIdentity() {
        fun sanitize(rowId: String, payload: String) = requireNotNull(
            ClinicalReportDatasetBuilder.sanitizeTherapyEvent(
                ClinicalTherapyProjection(
                    rowId = rowId,
                    ts = 100_000L,
                    type = "meal_bolus",
                    payloadJson = payload
                )
            )
        )

        val missingId = sanitize(
            "missing-id",
            """{"units":3,"carbs":90,"isValid":false,"aapsCarbAmount":24,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
        )
        val malformedId = sanitize(
            "malformed-id",
            """{"units":2,"carbs":80,"isValid":false,"aapsCarbId":"bad","aapsCarbAmount":"bad","aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
        )
        val quotedIdRows = listOf("quoted-a", "quoted-b").map { rowId ->
            sanitize(
                rowId,
                """{"units":1,"carbs":70,"isValid":false,"aapsCarbId":"702","aapsCarbAmount":18,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
            )
        }

        assertThat(missingId.canonicalCarbId).isNull()
        assertThat(missingId.point.insulinU).isEqualTo(3.0)
        assertThat(missingId.point.carbsG).isEqualTo(24.0)
        assertThat(malformedId.canonicalCarbId).isNull()
        assertThat(malformedId.point.insulinU).isEqualTo(2.0)
        assertThat(malformedId.point.carbsG).isNull()
        assertThat(quotedIdRows.map(ClinicalTherapyEvent::canonicalCarbId)).containsExactly(null, null)

        val canonicalQuotedRows = ClinicalReportDatasetBuilder.canonicalTherapyEvents(quotedIdRows)
        assertThat(canonicalQuotedRows.sumOf { it.point.insulinU ?: 0.0 }).isEqualTo(2.0)
        assertThat(canonicalQuotedRows.sumOf { it.point.carbsG ?: 0.0 }).isEqualTo(36.0)
    }

    @Test
    fun therapyComponentsSurviveMalformedSiblingFieldsIndependently() {
        val pureCarb = ClinicalReportDatasetBuilder.sanitizeTherapy(
            ClinicalTherapyProjection(
                rowId = "pure-carb",
                ts = 100_000L,
                type = "carbs",
                payloadJson = """{"units":"bad","carbs":90,"aapsCarbAmount":22,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
            )
        )
        val combinedCarb = ClinicalReportDatasetBuilder.sanitizeTherapy(
            ClinicalTherapyProjection(
                rowId = "combined-carb",
                ts = 110_000L,
                type = "meal_bolus",
                payloadJson = """{"units":"bad","carbs":90,"aapsCarbAmount":24,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
            )
        )
        val combinedInsulin = ClinicalReportDatasetBuilder.sanitizeTherapy(
            ClinicalTherapyProjection(
                rowId = "combined-insulin",
                ts = 120_000L,
                type = "meal_bolus",
                payloadJson = """{"units":3,"carbs":"bad"}"""
            )
        )
        val malformedLegacy = ClinicalReportDatasetBuilder.sanitizeTherapy(
            ClinicalTherapyProjection(
                rowId = "malformed-legacy",
                ts = 130_000L,
                type = "meal_bolus",
                payloadJson = """{"units":"bad","carbs":"bad"}"""
            )
        )

        assertThat(pureCarb?.carbsG).isEqualTo(22.0)
        assertThat(combinedCarb?.insulinU).isNull()
        assertThat(combinedCarb?.carbsG).isEqualTo(24.0)
        assertThat(combinedInsulin?.insulinU).isEqualTo(3.0)
        assertThat(combinedInsulin?.carbsG).isNull()
        assertThat(malformedLegacy).isNull()
    }

    @Test
    fun canonicalSurvivorAndTombstoneCountCarbsOnceWhileLegacyInvalidEventStaysRejected() {
        val rows = listOf(
            ClinicalTherapyProjection(
                rowId = "aaps-carb-301-survivor",
                ts = 100_000L,
                type = "carbs",
                payloadJson = """{"aapsCarbId":301,"aapsCarbAmount":30,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
            ),
            ClinicalTherapyProjection(
                rowId = "aaps-carb-301-tombstone",
                ts = 100_000L,
                type = "carbs",
                payloadJson = """{"carbs":30,"aapsCarbId":301,"aapsCarbAmount":30,"aapsCarbIsValid":false,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":true}"""
            ),
            ClinicalTherapyProjection(
                rowId = "aaps-carb-301-reconciled",
                ts = 100_000L,
                type = "carbs",
                payloadJson = """{"carbs":99,"aapsCarbId":301,"aapsCarbAmount":30,"aapsCarbIsValid":true,"aapsCarbClassification":"AAPS_REAL","aapsCarbSynthetic":false,"aapsCarbSuperseded":false}"""
            )
        )

        val therapy = ClinicalReportDatasetBuilder.canonicalTherapyEvents(
            rows.mapNotNull(ClinicalReportDatasetBuilder::sanitizeTherapyEvent)
        ).map(ClinicalTherapyEvent::point)

        assertThat(therapy.sumOf { it.carbsG ?: 0.0 }).isEqualTo(30.0)
        assertThat(
            ClinicalReportDatasetBuilder.sanitizeTherapy(
                ClinicalTherapyProjection(
                    rowId = "legacy-invalid",
                    ts = 100_000L,
                    type = "meal_bolus",
                    payloadJson = """{"insulin":3,"carbs":40,"isValid":false}"""
                )
            )
        ).isNull()
    }

    @Test
    fun canonicalInsulinUnitsAliasIsRetainedInTotalsAndCompactSerialization() = runTest {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 7, 31, 12, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val eventTs = now - 30_001L
        val source = FakeClinicalReportDataSource(
            glucose = emptyList(),
            therapy = listOf(
                ClinicalTherapyProjection(
                    ts = eventTs,
                    type = "correction_bolus",
                    payloadJson = """{"insulinUnits":2,"note":"must not escape"}"""
                )
            ),
            forecasts = emptyList(),
            telemetry = emptyList()
        )

        val payload = ClinicalReportDatasetBuilder(source, Gson()).build(now, zone)

        assertThat(payload.dataset.detail24h.therapy).containsExactly(
            ClinicalTherapyPoint(
                ts = eventTs,
                insulinU = 2.0,
                insulinEvidence = ClinicalInsulinEvidence.CONFIRMED
            )
        )
        assertThat(payload.dataset.summary7d.totalInsulinU).isEqualTo(2.0)
        assertThat(payload.dataset.summary30d.totalInsulinU).isEqualTo(2.0)
        val compactTherapy = JsonParser.parseString(payload.compactJson)
            .asJsonObject
            .getAsJsonObject("d24")
            .getAsJsonArray("e")
            .single()
            .asJsonArray
        assertThat(compactTherapy.map { it.takeUnless { value -> value.isJsonNull }?.asString })
            .containsExactly("-1", "2.0", null, "0", "1", null)
            .inOrder()
        assertThat(payload.compactJson).doesNotContain("must not escape")
    }

    @Test
    fun targetSanitizationNormalizesMgDlAndRejectsNotes() {
        val point = ClinicalReportDatasetBuilder.sanitizeTarget(
            ClinicalTherapyProjection(
                ts = 200L,
                type = "temp_target",
                payloadJson = """{"targetBottom":100,"targetTop":110,"notes":"private"}"""
            )
        )

        assertThat(point?.targetMmol).isWithin(0.001).of(5.833)
        assertThat(Gson().toJson(point)).doesNotContain("private")
    }

    @Test
    fun targetSanitizationPreservesExactRangeLifetimeAndExplicitCancellation() {
        val startTs = 1_726_000_123_456L
        val point = checkNotNull(
            ClinicalReportDatasetBuilder.sanitizeTarget(
                ClinicalTherapyProjection(
                    ts = startTs,
                    type = "temp_target",
                    payloadJson = """
                        {
                          "targetBottom": 100,
                          "targetTop": 110,
                          "durationInMilliseconds": 1800000,
                          "isValid": true,
                          "_id": "db-target-991",
                          "notes": "private target note"
                        }
                    """.trimIndent()
                )
            )
        )
        val json = Gson().toJsonTree(point).asJsonObject

        assertThat(json["ts"].asLong).isEqualTo(startTs)
        assertThat(json["lowMmol"].asDouble).isWithin(0.001).of(5.556)
        assertThat(json["highMmol"].asDouble).isWithin(0.001).of(6.111)
        assertThat(json["durationMs"].asLong).isEqualTo(1_800_000L)
        assertThat(json["endTs"].asLong).isEqualTo(startTs + 1_800_000L)
        assertThat(json["cancelled"].asBoolean).isFalse()
        assertThat(json.toString()).doesNotContain("db-target-991")
        assertThat(json.toString()).doesNotContain("private target note")

        val generatedAt = startTs + 90_000L
        val dataset = minimalDataset().copy(
            schemaVersion = ClinicalReportDatasetBuilder.SCHEMA_VERSION,
            generatedAt = generatedAt,
            targets7d = listOf(point)
        )
        val compactTarget = JsonParser.parseString(
            ClinicalReportDatasetBuilder.serialize(dataset, Gson())
        ).asJsonObject.getAsJsonArray("t7").single().asJsonArray
        assertThat(compactTarget.map { it.takeUnless { value -> value.isJsonNull }?.asString })
            .containsExactly(
                "-2",
                "5.556",
                "6.111",
                "30",
                "28",
                "0"
            )
            .inOrder()
    }

    @Test
    fun targetCancellationWithoutTargetValuesRemainsInHistory() {
        val startTs = 1_726_000_123_789L
        val point = checkNotNull(
            ClinicalReportDatasetBuilder.sanitizeTarget(
                ClinicalTherapyProjection(
                    ts = startTs,
                    type = "temp_target",
                    payloadJson = """{"duration":0,"isValid":false,"notes":"cancel private"}"""
                )
            )
        )
        val json = Gson().toJsonTree(point).asJsonObject

        assertThat(point.lowMmol).isNull()
        assertThat(point.highMmol).isNull()
        assertThat(json["durationMs"].asLong).isEqualTo(0L)
        assertThat(json["endTs"].asLong).isEqualTo(startTs)
        assertThat(json["cancelled"].asBoolean).isTrue()
        assertThat(json.toString()).doesNotContain("cancel private")
    }

    @Test
    fun canonicalGlucoseIsSortedDeduplicatedAndBounded() {
        val bucket = ClinicalSummaryCalculator.BUCKET_MS
        val buckets = ClinicalReportDatasetBuilder.canonicalizeGlucoseBuckets(
            rows = listOf(
                ClinicalGlucoseProjection(bucket + 1_000, 7.0, "package.private", "OK"),
                ClinicalGlucoseProjection(0, 5.0, "package.private", "OK"),
                ClinicalGlucoseProjection(1_000, 9.0, "package.private", "OK"),
                ClinicalGlucoseProjection(2 * bucket, 45.0, "package.private", "OK")
            ),
            fromTs = 0,
            throughTs = 2 * bucket
        )
        val points = ClinicalReportDatasetBuilder.reportGlucose(buckets)

        assertThat(points).containsExactly(
            ClinicalGlucosePoint(1_000, 9.0),
            ClinicalGlucosePoint(bucket + 1_000, 7.0)
        ).inOrder()
        assertThat(Gson().toJson(points)).doesNotContain("package.private")
    }

    @Test
    fun glucoseRejectsSensorErrorsAndUsesStableSourcePriorityBeforeCanonicalization() {
        val ts = 1_000L
        val rows = listOf(
            ClinicalGlucoseProjection(ts, 9.0, "local_broadcast", "OK"),
            ClinicalGlucoseProjection(ts, 8.0, "nightscout", "OK"),
            ClinicalGlucoseProjection(ts, 2.5, "aaps_broadcast", "SENSOR_ERROR"),
            ClinicalGlucoseProjection(ts, 7.0, "aaps_broadcast", "OK")
        )

        val forward = ClinicalReportDatasetBuilder.canonicalizeGlucoseBuckets(
            rows = rows,
            fromTs = 0L,
            throughTs = ClinicalSummaryCalculator.BUCKET_MS
        )
        val reverse = ClinicalReportDatasetBuilder.canonicalizeGlucoseBuckets(
            rows = rows.reversed(),
            fromTs = 0L,
            throughTs = ClinicalSummaryCalculator.BUCKET_MS
        )

        assertThat(forward).isEqualTo(reverse)
        assertThat(forward.single().medianMmol).isEqualTo(7.0)
        assertThat(forward.single().representativeTs).isEqualTo(ts)
    }

    @Test
    fun canonicalGlucoseBucketsDeriveBucketedReportAndExactSummaryRows() {
        val from = 1_000L
        val buckets = ClinicalReportDatasetBuilder.canonicalizeGlucoseBuckets(
            rows = listOf(
                ClinicalGlucoseProjection(from + 3 * MINUTE_MS, 5.0, "test", "OK"),
                ClinicalGlucoseProjection(from + 4 * MINUTE_MS, 7.0, "test", "OK"),
                ClinicalGlucoseProjection(from + 11 * MINUTE_MS, 9.0, "test", "OK")
            ),
            fromTs = from,
            throughTs = from + SEVEN_DAYS_MS
        )

        assertThat(buckets).containsExactly(
            ClinicalGlucoseCanonicalBucket(
                bucketTs = from + 6 * MINUTE_MS,
                representativeTs = from + 4 * MINUTE_MS,
                medianMmol = 7.0
            ),
            ClinicalGlucoseCanonicalBucket(
                bucketTs = from + 11 * MINUTE_MS,
                representativeTs = from + 11 * MINUTE_MS,
                medianMmol = 9.0
            )
        ).inOrder()
        assertThat(ClinicalReportDatasetBuilder.reportGlucose(buckets)).containsExactly(
            ClinicalGlucosePoint(from + 6 * MINUTE_MS, 7.0),
            ClinicalGlucosePoint(from + 11 * MINUTE_MS, 9.0)
        ).inOrder()
        assertThat(ClinicalReportDatasetBuilder.summaryGlucose(buckets)).containsExactly(
            ClinicalGlucosePoint(from + 4 * MINUTE_MS, 7.0),
            ClinicalGlucosePoint(from + 11 * MINUTE_MS, 9.0)
        ).inOrder()
    }

    @Test
    fun summaryUsesActualGlucoseTimesAcrossFridaySaturdayBoundary() {
        val zone = ZoneId.of("UTC")
        val from = ZonedDateTime.of(2026, 7, 24, 23, 58, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val through = from + SEVEN_DAYS_MS
        val buckets = ClinicalReportDatasetBuilder.canonicalizeGlucoseBuckets(
            rows = listOf(
                ClinicalGlucoseProjection(
                    ts = from + 3 * MINUTE_MS,
                    mmol = 5.0,
                    source = "test",
                    quality = "OK"
                ),
                ClinicalGlucoseProjection(
                    ts = from + 4 * MINUTE_MS,
                    mmol = 7.0,
                    source = "test",
                    quality = "OK"
                ),
                ClinicalGlucoseProjection(
                    ts = from + 11 * MINUTE_MS,
                    mmol = 9.0,
                    source = "test",
                    quality = "OK"
                )
            ),
            fromTs = from,
            throughTs = through
        )
        val glucose = ClinicalReportDatasetBuilder.summaryGlucose(buckets)

        val summary = ClinicalSummaryCalculator.calculate(
            days = 7,
            fromTs = from,
            throughTs = through,
            zoneId = zone,
            glucose = glucose,
            therapy = emptyList(),
            targets = emptyList()
        )

        assertThat(summary.weekdayPattern[23].sampleCount).isEqualTo(0)
        assertThat(summary.weekendPattern[0].sampleCount).isEqualTo(2)
        assertThat(summary.coveragePct).isWithin(0.000_001)
            .of(12.0 / SEVEN_DAYS_MINUTES * 100.0)
        assertThat(summary.quality.maxGapMinutes)
            .isEqualTo(SEVEN_DAYS_MINUTES.toInt() - 11)
    }

    @Test
    fun syntheticTypeSnakeCaseIsExcludedFromRealCarbTotalWithoutOtherMarkers() {
        assertSyntheticCarbKeyIsExcluded("""{"carbs":15,"synthetic_type":"uam"}""")
    }

    @Test
    fun syntheticTypeCamelCaseIsExcludedFromRealCarbTotalWithoutOtherMarkers() {
        assertSyntheticCarbKeyIsExcluded("""{"carbs":15,"syntheticType":"uam"}""")
    }

    @Test
    fun syntheticUamNotesFieldIsExcludedFromRealCarbTotalWithoutOtherMarkers() {
        assertSyntheticCarbKeyIsExcluded(
            """{"carbs":15,"notes":"UAM_ENGINE|id=test|seq=1|ver=1|mode=NORMAL|"}"""
        )
    }

    @Test
    fun buildBoundsSummaryRowsAndRejectsArtifactsFutureAndNonFiniteData() = runTest {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 7, 31, 12, 3, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val through = ZonedDateTime.of(2026, 7, 31, 12, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val source = FakeClinicalReportDataSource(
            glucose = listOf(
                ClinicalGlucoseProjection(through - MINUTE_MS, 6.0, "test", "OK"),
                ClinicalGlucoseProjection(through + MINUTE_MS, 7.0, "test", "OK"),
                ClinicalGlucoseProjection(now + MINUTE_MS, 8.0, "test", "OK"),
                ClinicalGlucoseProjection(through - 2 * MINUTE_MS, Double.NaN, "test", "OK")
            ),
            therapy = listOf(
                therapyProjection(
                    id = "canonical-meal",
                    ts = through - MINUTE_MS,
                    type = "meal_bolus",
                    payload = """{"carbs":20,"units":2}"""
                ),
                therapyProjection(
                    id = "br-local_broadcast-meal_bolus-duplicate",
                    ts = through - MINUTE_MS,
                    type = "meal_bolus",
                    payload = """{"carbs":20,"units":2}"""
                ),
                therapyProjection(
                    id = "canonical-target",
                    ts = through - 2 * MINUTE_MS,
                    type = "temp_target",
                    payload = """{"target":100}"""
                ),
                therapyProjection(
                    id = "br-local_broadcast-temp_target-duplicate",
                    ts = through - 2 * MINUTE_MS,
                    type = "temp_target",
                    payload = """{"target":100}"""
                ),
                therapyProjection(
                    id = "detail-meal",
                    ts = through + MINUTE_MS,
                    type = "meal_bolus",
                    payload = """{"carbs":9,"units":1}"""
                ),
                therapyProjection(
                    id = "detail-target",
                    ts = through + MINUTE_MS,
                    type = "temp_target",
                    payload = """{"target":108}"""
                ),
                therapyProjection(
                    id = "future-meal",
                    ts = now + MINUTE_MS,
                    type = "meal_bolus",
                    payload = """{"carbs":30,"units":3}"""
                ),
                therapyProjection(
                    id = "non-finite-insulin",
                    ts = through - 3 * MINUTE_MS,
                    type = "correction_bolus",
                    payload = """{"units":"NaN"}"""
                ),
                therapyProjection(
                    id = "non-finite-target",
                    ts = through - 3 * MINUTE_MS,
                    type = "temp_target",
                    payload = """{"target":"Infinity"}"""
                )
            ),
            forecasts = listOf(
                ClinicalForecastProjection(through + MINUTE_MS, 30, 6.5, 5.5, 7.5),
                ClinicalForecastProjection(now + MINUTE_MS, 30, 8.0, 7.0, 9.0),
                ClinicalForecastProjection(through, 30, Double.NaN, 5.0, 7.0)
            ),
            telemetry = listOf(
                ClinicalTelemetryProjection(through + MINUTE_MS, "iob_units", 1.5, "OK", unit = "U"),
                ClinicalTelemetryProjection(now + MINUTE_MS, "iob_units", 2.0, "OK", unit = "U"),
                ClinicalTelemetryProjection(through, "iob_units", Double.NaN, "OK", unit = "U")
            )
        )

        val payload = ClinicalReportDatasetBuilder(source, Gson()).build(now, zone)
        val dataset = payload.dataset

        assertThat(source.glucoseWindow).isEqualTo(through - THIRTY_DAYS_MS to now)
        assertThat(source.therapyWindow)
            .isEqualTo(through - THIRTY_DAYS_MS - SEVEN_DAYS_MS to now)
        assertThat(source.forecastWindow).isEqualTo(now - DAY_MS to now)
        assertThat(source.telemetryWindow).isEqualTo(now - DAY_MS to now)
        assertThat(dataset.summary7d.throughTs).isEqualTo(through)
        assertThat(dataset.summary30d.throughTs).isEqualTo(through)
        assertThat(dataset.therapy30d).containsExactly(
            ClinicalTherapyPoint(
                through - MINUTE_MS,
                insulinU = 2.0,
                carbsG = 20.0,
                insulinEvidence = ClinicalInsulinEvidence.CONFIRMED
            )
        )
        assertThat(dataset.therapy7d).isEqualTo(dataset.therapy30d)
        assertThat(dataset.targets30d).containsExactly(
            ClinicalTargetPoint(through - 2 * MINUTE_MS, 5.556)
        )
        assertThat(dataset.targets7d).isEqualTo(dataset.targets30d)
        assertThat(dataset.summary7d.totalInsulinU).isEqualTo(2.0)
        assertThat(dataset.summary7d.totalCarbsG).isEqualTo(20.0)
        assertThat(dataset.summary7d.meanTargetMmol).isEqualTo(5.556)
        assertThat(dataset.summary30d.totalInsulinU).isEqualTo(2.0)
        assertThat(dataset.summary30d.totalCarbsG).isEqualTo(20.0)
        assertThat(dataset.summary30d.meanTargetMmol).isEqualTo(5.556)
        assertThat(dataset.detail24h.glucose.map { it.ts }).containsExactly(
            through - MINUTE_MS,
            through + MINUTE_MS
        ).inOrder()
        assertThat(dataset.detail24h.therapy).containsExactly(
            ClinicalTherapyPoint(
                through - MINUTE_MS,
                insulinU = 2.0,
                carbsG = 20.0,
                insulinEvidence = ClinicalInsulinEvidence.CONFIRMED
            ),
            ClinicalTherapyPoint(
                through + MINUTE_MS,
                insulinU = 1.0,
                carbsG = 9.0,
                insulinEvidence = ClinicalInsulinEvidence.CONFIRMED
            )
        ).inOrder()
        assertThat(dataset.detail24h.targets).containsExactly(
            ClinicalTargetPoint(through - 2 * MINUTE_MS, 5.556),
            ClinicalTargetPoint(through + MINUTE_MS, 6.0)
        ).inOrder()
        assertThat(dataset.detail24h.forecasts).containsExactly(
            ClinicalForecastPoint(through + MINUTE_MS, 30, 6.5, 5.5, 7.5)
        )
        assertThat(dataset.detail24h.telemetry).containsExactly(
            ClinicalTelemetryPoint(through + MINUTE_MS, "iob_units", 1.5, "OK")
        )
        assertThat(payload.compactJson).doesNotContain("canonical-meal")
        assertThat(payload.compactJson).doesNotContain("br-local_broadcast")
    }

    @Test
    fun buildDeduplicatesEveryLogicalSeriesBeforeSummariesAndSerialization() = runTest {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 7, 31, 12, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val eventTs = now - MINUTE_MS
        val glucose = ClinicalGlucoseProjection(eventTs, 6.0, "nightscout", "OK")
        val therapyA = therapyProjection(
            id = "meal-a",
            ts = eventTs,
            type = "meal_bolus",
            payload = """{"carbs":20,"units":2}"""
        )
        val therapyB = therapyProjection(
            id = "meal-b",
            ts = eventTs,
            type = "meal_bolus",
            payload = """{"enteredCarbs":20,"enteredInsulin":2}"""
        )
        val targetA = therapyProjection(
            id = "target-a",
            ts = eventTs,
            type = "temp_target",
            payload = """{"targetBottom":100,"targetTop":110,"duration":30}"""
        )
        val targetB = therapyProjection(
            id = "target-b",
            ts = eventTs,
            type = "temp_target",
            payload = """{"target_bottom":100,"target_high":110,"durationInMinutes":30}"""
        )
        val forecast = ClinicalForecastProjection(eventTs, 30, 6.5, 5.5, 7.5)
        val telemetry = ClinicalTelemetryProjection(eventTs, "iob_units", 1.5, "OK", unit = "U")
        val source = FakeClinicalReportDataSource(
            glucose = listOf(glucose, glucose),
            therapy = listOf(therapyA, therapyA, therapyB, targetA, targetA, targetB),
            forecasts = listOf(forecast, forecast),
            telemetry = listOf(telemetry, telemetry)
        )

        val payload = ClinicalReportDatasetBuilder(source, Gson()).build(now, zone)

        assertThat(payload.dataset.detail24h.glucose).hasSize(1)
        assertThat(payload.dataset.detail24h.therapy).hasSize(2)
        assertThat(payload.dataset.detail24h.targets).hasSize(2)
        assertThat(payload.dataset.detail24h.forecasts).hasSize(1)
        assertThat(payload.dataset.detail24h.telemetry).hasSize(1)
        assertThat(payload.dataset.glucose7d).hasSize(1)
        assertThat(payload.dataset.glucose30d).hasSize(1)
        assertThat(payload.dataset.summary7d.totalInsulinU).isEqualTo(4.0)
        assertThat(payload.dataset.summary7d.totalCarbsG).isEqualTo(40.0)
        assertThat(payload.dataset.summary7d.meanTargetMmol).isWithin(0.000_001).of(5.8335)
    }

    @Test
    fun compactSerializationIsByteIdenticalAcrossPermutationsAndRankedLogicalRows() {
        val gson = Gson()
        val targetA = checkNotNull(
            ClinicalReportDatasetBuilder.sanitizeTarget(
                therapyProjection(
                    ts = 120_123L,
                    type = "temp_target",
                    payload = """{"targetBottom":90,"targetTop":108,"duration":30}"""
                )
            )
        )
        val targetB = checkNotNull(
            ClinicalReportDatasetBuilder.sanitizeTarget(
                therapyProjection(
                    ts = 120_123L,
                    type = "temp_target",
                    payload = """{"targetBottom":92,"targetTop":106,"duration":30}"""
                )
            )
        )
        val detail = ClinicalDetailWindow(
            fromTs = 0L,
            throughTs = 600_000L,
            glucose = listOf(
                ClinicalGlucosePoint(60_000L, 6.2),
                ClinicalGlucosePoint(60_000L, 6.1),
                ClinicalGlucosePoint(60_000L, 6.1)
            ),
            calibratedGlucose = listOf(
                ClinicalGlucosePoint(60_000L, 6.4),
                ClinicalGlucosePoint(60_000L, 6.3),
                ClinicalGlucosePoint(60_000L, 6.3)
            ),
            therapy = listOf(
                ClinicalTherapyPoint(90_000L, insulinU = 1.0),
                ClinicalTherapyPoint(90_000L, carbsG = 10.0),
                ClinicalTherapyPoint(90_000L, insulinU = 1.0)
            ),
            targets = listOf(targetA, targetB, targetA),
            forecasts = listOf(
                ClinicalForecastPoint(180_000L, 30, 6.5, 5.4, 7.6),
                ClinicalForecastPoint(180_000L, 30, 6.5, 5.5, 7.5),
                ClinicalForecastPoint(180_000L, 30, 6.5, 5.4, 7.6)
            ),
            telemetry = listOf(
                ClinicalTelemetryPoint(240_000L, "iob_units", 1.0, "STALE"),
                ClinicalTelemetryPoint(240_000L, "iob_units", 1.0, "OK"),
                ClinicalTelemetryPoint(240_000L, "iob_units", 1.0, "STALE")
            )
        )
        val base = minimalDataset().copy(detail24h = detail)
        val expected = ClinicalReportDatasetBuilder.serialize(base, gson)
        val expectedHash = ClinicalReportDatasetBuilder.sha256(expected)

        repeat(12) { seed ->
            val random = Random(seed)
            val permutedDetail = detail.copy(
                glucose = detail.glucose.shuffled(random),
                calibratedGlucose = detail.calibratedGlucose.shuffled(random),
                therapy = detail.therapy.shuffled(random),
                targets = detail.targets.shuffled(random),
                forecasts = detail.forecasts.shuffled(random),
                telemetry = detail.telemetry.shuffled(random)
            )
            val actual = ClinicalReportDatasetBuilder.serialize(
                base.copy(detail24h = permutedDetail),
                gson
            )

            assertThat(actual).isEqualTo(expected)
            assertThat(ClinicalReportDatasetBuilder.sha256(actual)).isEqualTo(expectedHash)
        }

        val json = JsonParser.parseString(expected).asJsonObject.getAsJsonObject("d24")
        assertThat(json.getAsJsonArray("g")).hasSize(1)
        assertThat(json.getAsJsonArray("gc")).hasSize(1)
        assertThat(json.getAsJsonArray("e")).hasSize(2)
        assertThat(json.getAsJsonArray("t")).hasSize(2)
        assertThat(json.getAsJsonArray("f")).hasSize(1)
        assertThat(json.getAsJsonArray("fq")).isEmpty()
        assertThat(json.getAsJsonArray("m")).hasSize(1)
        assertThat(json.keySet()).containsExactly(
            "from",
            "through",
            "g",
            "gc",
            "e",
            "t",
            "f",
            "fq",
            "m"
        ).inOrder()
    }

    @Test
    fun completeCompactPayloadExcludesForbiddenValuesFromEveryInputBoundary() = runTest {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 7, 31, 12, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val ts = now - MINUTE_MS
        val forbidden = listOf(
            "Ada Patient",
            "+995555010101",
            "ada.patient@example.com",
            "android-id-a1b2c3",
            "sensor-serial-7788",
            "https://nightscout.private.example",
            "nightscout-secret-xyz",
            "db-row-id-424242",
            "com.private.cgm.package",
            "free form note private",
            "free form notes private"
        )
        val payloadJson = """
            {
              "carbs": 20,
              "units": 2,
              "name": "${forbidden[0]}",
              "phone": "${forbidden[1]}",
              "email": "${forbidden[2]}",
              "androidId": "${forbidden[3]}",
              "serial": "${forbidden[4]}",
              "nightscoutUrl": "${forbidden[5]}",
              "nightscoutSecret": "${forbidden[6]}",
              "_id": "${forbidden[7]}",
              "packageName": "${forbidden[8]}",
              "note": "${forbidden[9]}",
              "notes": "${forbidden[10]}"
            }
        """.trimIndent()
        val source = FakeClinicalReportDataSource(
            glucose = listOf(
                ClinicalGlucoseProjection(ts, 6.0, forbidden[8], forbidden[7])
            ),
            therapy = listOf(
                ClinicalTherapyProjection(
                    ts = ts,
                    type = "meal_bolus",
                    payloadJson = payloadJson,
                    isBroadcastArtifact = false
                )
            ),
            forecasts = listOf(
                ClinicalForecastProjection(ts, 30, 6.5, 5.5, 7.5)
            ),
            telemetry = listOf(
                ClinicalTelemetryProjection(ts, "iob_units", 1.5, forbidden[4]),
                ClinicalTelemetryProjection(ts, forbidden[8], 9.9, forbidden[7])
            )
        )

        val compactJson = ClinicalReportDatasetBuilder(source, Gson())
            .build(now, zone)
            .compactJson

        forbidden.forEach { value ->
            assertThat(compactJson).doesNotContain(value)
        }
    }

    @Test
    fun deterministicSerializationProducesStableBytesAndHash() {
        val base = minimalDataset()
        val dataset = base.copy(
            summary24h = base.summary7d.copy(
                days = 1,
                fromTs = base.summary7d.throughTs - DAY_MS
            )
        )
        val gson = Gson()

        val first = ClinicalReportDatasetBuilder.serialize(dataset, gson)
        val second = ClinicalReportDatasetBuilder.serialize(dataset.copy(), gson)

        assertThat(second).isEqualTo(first)
        assertThat(ClinicalReportDatasetBuilder.sha256(second))
            .isEqualTo(ClinicalReportDatasetBuilder.sha256(first))
        val json = JsonParser.parseString(first).asJsonObject
        val glucoseRow = json.getAsJsonArray("g7")[0].asJsonArray
        assertThat(glucoseRow[0].asLong).isEqualTo(-10L)
        assertThat(glucoseRow[1].asDouble).isEqualTo(6.0)
        assertThat(json.keySet()).containsExactly(
            "v",
            "generatedAt",
            "zone",
            "c",
            "d24",
            "g7",
            "e7",
            "t7",
            "g30",
            "e30",
            "t30",
            "s24",
            "s7",
            "s30",
            "ev24",
            "ev7",
            "ev30"
        ).inOrder()
        assertThat(first).doesNotContain("payloadJson")
        assertThat(first).doesNotContain("note")
        assertThat(first).doesNotContain("payloadJson")
        assertThat(first).doesNotContain("eventId")
        assertThat(first).doesNotContain("androidId")
        assertThat(first).doesNotContain("requestId")
    }

    @Test
    fun consentOffRemovesEnergyProfileAndTypedActivityOccurrences() {
        val base = minimalDataset()
        val activity = ClinicalPlannedActivitySummary(
            type = "AEROBIC",
            intensity = "MEDIUM",
            plannedStartMs = 0L,
            plannedDurationMinutes = 45,
            adherence = "NOT_MEASURED"
        )
        val profile = ClinicalEnergyProfileSummary(
            derivedAgeYears = 34,
            sex = "FEMALE",
            foodProfile = "MIXED",
            foodProfileSource = "MANUAL",
            activityProfile = "MODERATE",
            activityProfileSource = "MANUAL",
            confidence = "STABLE",
            evidenceDays = 10,
            calorieGoalMode = "OFF",
            shareProfileWithAi = false
        )

        val withoutConsent = ClinicalReportDatasetBuilder.serialize(
            base.copy(energyProfile = profile, plannedActivities = listOf(activity))
        )
        val withConsent = ClinicalReportDatasetBuilder.serialize(
            base.copy(
                energyProfile = profile.copy(shareProfileWithAi = true),
                plannedActivities = listOf(activity)
            )
        )

        assertThat(withoutConsent).doesNotContain("\"ep\"")
        assertThat(withoutConsent).doesNotContain("\"pa\"")
        assertThat(withConsent).contains("\"derivedAge\":34")
        assertThat(withConsent).contains("\"type\":\"AEROBIC\"")
        assertThat(withConsent).doesNotContain("birthDate")
        assertThat(withConsent).doesNotContain("eventId")
        assertThat(withConsent).doesNotContain("title")
    }

    @Test
    fun eventTimelineSerializesThreeWindowsWithoutLocalOrProvenanceIdentifiers() {
        val generatedAt = 40L * DAY_MS
        val events = listOf(
            ClinicalEventSummary(
                localId = "local-30d",
                type = "CUSTOM",
                subtype = "DELIVERY_DIAGNOSTIC",
                startTs = generatedAt - 25L * DAY_MS,
                endTs = generatedAt - 25L * DAY_MS + 60_000L,
                severity = "LOW",
                source = "AAPS",
                title = "Pump maintenance",
                note = "bounded note",
                status = "CLOSED",
                provenance = "therapy_events:aapsDbId=42:syncHash=secret"
            ),
            ClinicalEventSummary(
                localId = "local-7d",
                type = "MENSTRUAL_CYCLE",
                subtype = "LUTEAL",
                startTs = generatedAt - 5L * DAY_MS,
                endTs = generatedAt - 4L * DAY_MS,
                severity = "MEDIUM",
                source = "USER",
                title = "Cycle",
                note = null,
                status = "CLOSED",
                provenance = "physio_context_tags:rowId=9"
            ),
            ClinicalEventSummary(
                localId = "local-24h",
                type = "CUSTOM",
                subtype = "UAM",
                startTs = generatedAt - 60L * 60_000L,
                endTs = generatedAt,
                severity = "MEDIUM",
                source = "AUTOMATIC",
                title = "UAM context",
                note = null,
                status = "CLOSED",
                provenance = "therapy_events:eventId=uam-secret",
                syntheticUam = true
            )
        )

        val compact = ClinicalReportDatasetBuilder.serialize(
            minimalDataset().withEventPeriods(generatedAt, events)
        )
        val root = JsonParser.parseString(compact).asJsonObject

        assertThat(root.getAsJsonArray("ev24")).hasSize(1)
        assertThat(root.getAsJsonArray("ev7")).hasSize(2)
        assertThat(root.getAsJsonArray("ev30")).hasSize(3)
        assertThat(compact).contains("\"subtype\":\"UAM\"")
        assertThat(compact).contains("\"source\":\"AUTOMATIC\"")
        listOf(
            "local-30d", "local-7d", "local-24h", "aapsDbId", "rowId",
            "syncHash", "eventId", "provenance", "syntheticUam"
        ).forEach { forbidden -> assertThat(compact).doesNotContain(forbidden) }
    }

    @Test
    fun eventSerializationPreservesExact512And513RowsWithoutLocalIdentifiers() {
        val generatedAt = 40L * DAY_MS

        listOf(512, 513).forEach { count ->
            val events = (0 until count).map { index ->
                ClinicalEventSummary(
                    localId = "local-secret-$index",
                    type = "CUSTOM",
                    subtype = "BOUNDARY",
                    startTs = generatedAt - DAY_MS + index * 1_000L,
                    endTs = generatedAt - DAY_MS + index * 1_000L,
                    severity = "LOW",
                    source = "USER",
                    title = "wire-event-$index",
                    note = null,
                    status = "CLOSED",
                    provenance = "private-provenance-$index"
                )
            }
            val compact = ClinicalReportDatasetBuilder.serialize(
                minimalDataset().withEventPeriods(generatedAt, events)
            )
            val root = JsonParser.parseString(compact).asJsonObject

            listOf("ev24", "ev7", "ev30").forEach { key ->
                val rows = root.getAsJsonArray(key)
                assertThat(rows).hasSize(count)
                assertThat(rows.first().asJsonObject.get("title").asString)
                    .isEqualTo("wire-event-0")
                assertThat(rows.last().asJsonObject.get("title").asString)
                    .isEqualTo("wire-event-${count - 1}")
            }
            assertThat(compact).doesNotContain("local-secret-")
            assertThat(compact).doesNotContain("private-provenance-")
        }
    }

    @Test
    fun eventEncoderUsesOneCanonicalWindowPlanAndIsDeterministicBeyond512Rows() {
        val generatedAt = 40L * DAY_MS
        val events = (0 until 600).map { index ->
            ClinicalEventSummary(
                localId = "local-$index",
                type = "CUSTOM",
                subtype = "ENCODER",
                startTs = generatedAt - DAY_MS + index * 1_000L,
                endTs = generatedAt - DAY_MS + index * 1_000L,
                severity = "LOW",
                source = "USER",
                title = "event-${index.toString().padStart(3, '0')}",
                note = null,
                status = "CLOSED",
                provenance = "private-$index"
            )
        }
        val source = minimalDataset().withEventPeriods(generatedAt, events.shuffled(Random(17)))
        val orderingPasses = AtomicInteger()
        val probe = object : ClinicalDatasetSerializationProbe {
            override fun onCanonicalEventWindowsComputed() {
                orderingPasses.incrementAndGet()
            }
        }

        val first = ClinicalReportDatasetBuilder.serializeForTest(
            source,
            ClinicalOpenAiClient.MAX_COMPACT_DATASET_BYTES,
            probe
        )
        val second = ClinicalReportDatasetBuilder.serialize(
            source.copy(eventSummaries = source.eventSummaries.reversed())
        )
        val root = JsonParser.parseString(first).asJsonObject

        assertThat(orderingPasses.get()).isEqualTo(1)
        listOf("ev24", "ev7", "ev30").forEach { key ->
            assertThat(root.getAsJsonArray(key)).hasSize(600)
        }
        assertThat(first).isEqualTo(second)
        assertThat(first.toByteArray(Charsets.UTF_8).size)
            .isAtMost(ClinicalOpenAiClient.MAX_COMPACT_DATASET_BYTES)
    }

    @Test
    fun canonicalEncoderAcceptsExactEightMiBAndRejectsAdjacentByteBeforeFinalAssembly() {
        val source = exactEightMiBDataset(extraAsciiBytes = 0)
        val acceptedAssemblies = AtomicInteger()
        val rejectedAssemblies = AtomicInteger()

        val exact = ClinicalReportDatasetBuilder.serializeForTest(
            source,
            ClinicalOpenAiClient.MAX_COMPACT_DATASET_BYTES,
            object : ClinicalDatasetSerializationProbe {
                override fun onFullDatasetAssemblyStarted() {
                    acceptedAssemblies.incrementAndGet()
                }
            }
        )
        val failure = runCatching {
            ClinicalReportDatasetBuilder.serializeForTest(
                exactEightMiBDataset(extraAsciiBytes = 1),
                ClinicalOpenAiClient.MAX_COMPACT_DATASET_BYTES,
                object : ClinicalDatasetSerializationProbe {
                    override fun onFullDatasetAssemblyStarted() {
                        rejectedAssemblies.incrementAndGet()
                    }
                }
            )
        }.exceptionOrNull()

        assertThat(exact.toByteArray(Charsets.UTF_8)).hasLength(8 * 1_024 * 1_024)
        assertThat(exact).contains("é😀")
        assertThat(exact).contains("\\\"escaped\\\\line\\n")
        assertThat(acceptedAssemblies.get()).isEqualTo(1)
        assertThat(failure).isInstanceOf(ClinicalOpenAiException.DatasetTooLarge::class.java)
        assertThat(rejectedAssemblies.get()).isEqualTo(0)
        assertThat(ClinicalOpenAiClient.MAX_COMPACT_DATASET_BYTES).isEqualTo(8 * 1_024 * 1_024)
    }

    @Test
    fun oversizedNonEventSeriesRejectsDuringEmissionBeforeFinalAssembly() {
        val base = minimalDataset()
        val rows = (0 until 2_000).map { index ->
            ClinicalTelemetryPoint(
                ts = index * MINUTE_MS,
                key = "series-${index.toString().padStart(4, '0')}-" + "x".repeat(256),
                value = 1.0,
                quality = "OK"
            )
        }
        val source = base.copy(detail24h = base.detail24h.copy(telemetry = rows))
        val emittedRows = AtomicInteger()
        val finalAssemblies = AtomicInteger()

        val failure = runCatching {
            ClinicalReportDatasetBuilder.serializeForTest(
                source,
                maxBytes = 8_000,
                probe = object : ClinicalDatasetSerializationProbe {
                    override fun onSeriesRowEmitted() {
                        emittedRows.incrementAndGet()
                    }

                    override fun onFullDatasetAssemblyStarted() {
                        finalAssemblies.incrementAndGet()
                    }
                }
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ClinicalOpenAiException.DatasetTooLarge::class.java)
        assertThat(emittedRows.get()).isGreaterThan(0)
        assertThat(emittedRows.get()).isLessThan(rows.size)
        assertThat(finalAssemblies.get()).isEqualTo(0)
    }

    @Test
    fun canonicalComparisonValidatesWithoutMaterializingAnotherString() {
        val source = minimalDataset().copy(
            currentSnapshot = frozenCurrentSnapshot(includeMetadata = true)
        )
        val finalAssemblies = AtomicInteger()
        val probe = object : ClinicalDatasetSerializationProbe {
            override fun onFullDatasetAssemblyStarted() {
                finalAssemblies.incrementAndGet()
            }
        }
        val compact = ClinicalReportDatasetBuilder.serializeForTest(
            source,
            ClinicalOpenAiClient.MAX_COMPACT_DATASET_BYTES,
            probe
        )

        ClinicalReportDatasetBuilder.validateCanonicalForTest(
            source,
            expected = compact,
            maxBytes = ClinicalOpenAiClient.MAX_COMPACT_DATASET_BYTES,
            probe = probe
        )
        val mismatch = runCatching {
            ClinicalReportDatasetBuilder.validateCanonicalForTest(
                source,
                expected = "$compact ",
                maxBytes = ClinicalOpenAiClient.MAX_COMPACT_DATASET_BYTES,
                probe = probe
            )
        }.exceptionOrNull()
        val overflow = runCatching {
            ClinicalReportDatasetBuilder.validateCanonicalForTest(
                source,
                expected = compact,
                maxBytes = compact.toByteArray(Charsets.UTF_8).size - 1,
                probe = probe
            )
        }.exceptionOrNull()

        assertThat(finalAssemblies.get()).isEqualTo(1)
        assertThat(mismatch).isInstanceOf(ClinicalOpenAiException.InvalidInput::class.java)
        assertThat(mismatch?.message).isEqualTo("Clinical analysis input is invalid")
        assertThat(overflow).isInstanceOf(ClinicalOpenAiException.DatasetTooLarge::class.java)
    }

    @Test
    fun serializeRejectsNonFiniteCurrentValueWithoutEcho() {
        assertInvalidCurrentSnapshot(
            ClinicalCurrentSnapshot(rawGlucoseMmol = Double.NaN),
            forbiddenValue = "NaN"
        )
    }

    @Test
    fun serializeRejectsOutOfRangeCurrentValueWithoutEcho() {
        assertInvalidCurrentSnapshot(
            ClinicalCurrentSnapshot(rawGlucoseMmol = 40.001),
            forbiddenValue = "40.001"
        )
    }

    @Test
    fun serializeRejectsNegativeCurrentRevisionWithoutEcho() {
        assertInvalidCurrentSnapshot(
            ClinicalCurrentSnapshot(sensitivitySettingsRevision = -7L),
            forbiddenValue = "-7"
        )
    }

    @Test
    fun serializeRejectsInvalidCurrentProvenanceWithoutEcho() {
        val privateValue = "UNKNOWN_PRIVATE_SOURCE"

        assertInvalidCurrentSnapshot(
            ClinicalCurrentSnapshot(
                selectedIsfSourceCode = 1.0,
                selectedIsfSource = privateValue
            ),
            forbiddenValue = privateValue
        )
    }

    @Test
    fun serializePreservesUnavailableCurrentFieldsAsNull() {
        val current = JsonParser.parseString(
            ClinicalReportDatasetBuilder.serialize(
                minimalDataset().copy(currentSnapshot = ClinicalCurrentSnapshot())
            )
        ).asJsonObject.getAsJsonObject("c")

        assertThat(current.get("g").isJsonNull).isTrue()
        assertThat(current.get("isfSrc").isJsonNull).isTrue()
        assertThat(current.get("crSrc").isJsonNull).isTrue()
        assertThat(current.has("sensitivityRev")).isFalse()
        assertThat(current.has("isfProvenance")).isFalse()
        assertThat(current.has("crProvenance")).isFalse()
    }

    @Test
    fun currentWireBytesMatchFrozenA9OrderWithOptionalMetadata() {
        val compact = ClinicalReportDatasetBuilder.serialize(
            minimalDataset().copy(currentSnapshot = frozenCurrentSnapshot(includeMetadata = true))
        )

        assertThat(rawCurrentObject(compact)).isEqualTo(FROZEN_CURRENT_WITH_METADATA_JSON)
        assertThat(ClinicalReportDatasetBuilder.sha256(compact)).isEqualTo(FROZEN_CURRENT_COMPACT_SHA256)
    }

    @Test
    fun currentWireBytesJoinPrefixAndSuffixWithoutOptionalMetadata() {
        val compact = ClinicalReportDatasetBuilder.serialize(
            minimalDataset().copy(currentSnapshot = frozenCurrentSnapshot(includeMetadata = false))
        )

        assertThat(rawCurrentObject(compact)).isEqualTo(FROZEN_CURRENT_WITHOUT_METADATA_JSON)
    }

    @Test
    fun hugeEventCollectionIsRejectedBeforeAnyEventRowIsRead() {
        val reads = AtomicInteger()
        val event = ClinicalEventSummary(
            localId = "unread",
            type = "CUSTOM",
            subtype = "",
            startTs = 1L,
            endTs = 1L,
            severity = "LOW",
            source = "USER",
            title = "unread",
            note = null,
            status = "CLOSED",
            provenance = "private"
        )
        val hugeEvents = object : AbstractList<ClinicalEventSummary>() {
            override val size: Int = ClinicalOpenAiClient.MAX_TOTAL_SERIES_ROWS + 1

            override fun get(index: Int): ClinicalEventSummary {
                reads.incrementAndGet()
                return event.copy(localId = "unread-$index")
            }
        }

        val failure = runCatching {
            ClinicalReportDatasetBuilder.serialize(
                minimalDataset().copy(eventSummaries = hugeEvents)
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ClinicalOpenAiException.DatasetTooLarge::class.java)
        assertThat(reads.get()).isEqualTo(0)
    }

    @Test
    fun cancellationDuringLargeEventEncodingStopsBeforeReadingEveryEvent() = runTest {
        lateinit var operation: Deferred<String>
        val lastReadIndex = AtomicInteger(-1)
        val event = ClinicalEventSummary(
            localId = "event",
            type = "CUSTOM",
            subtype = "",
            startTs = 1L,
            endTs = 1L,
            severity = "LOW",
            source = "USER",
            title = "event",
            note = null,
            status = "CLOSED",
            provenance = "private"
        )
        val cancellingEvents = object : AbstractList<ClinicalEventSummary>() {
            override val size: Int = 2_000

            override fun get(index: Int): ClinicalEventSummary {
                lastReadIndex.accumulateAndGet(index) { current, next -> maxOf(current, next) }
                if (index == 300) operation.cancel(CancellationException("event cancellation"))
                return event.copy(localId = "event-$index", startTs = index.toLong(), endTs = index.toLong())
            }
        }
        val source = minimalDataset().copy(eventSummaries = cancellingEvents)
        operation = async(start = CoroutineStart.LAZY) {
            ClinicalReportDatasetBuilder.serializeCancellable(source)
        }

        operation.start()
        val failure = runCatching { operation.await() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(lastReadIndex.get()).isLessThan(cancellingEvents.lastIndex)
    }

    @Test
    fun cancellationDuringLargeNonEventEncodingStopsBeforeReadingEveryRow() = runTest {
        lateinit var operation: Deferred<String>
        val lastReadIndex = AtomicInteger(-1)
        val cancellingRows = object : AbstractList<ClinicalGlucosePoint>() {
            override val size: Int = 2_000

            override fun get(index: Int): ClinicalGlucosePoint {
                lastReadIndex.accumulateAndGet(index) { current, next -> maxOf(current, next) }
                if (index == 300) operation.cancel(CancellationException("series cancellation"))
                return ClinicalGlucosePoint(index * MINUTE_MS, 6.0)
            }
        }
        val base = minimalDataset()
        val source = base.copy(detail24h = base.detail24h.copy(glucose = cancellingRows))
        operation = async(start = CoroutineStart.LAZY) {
            ClinicalReportDatasetBuilder.serializeCancellable(source)
        }

        operation.start()
        val failure = runCatching { operation.await() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(lastReadIndex.get()).isLessThan(cancellingRows.lastIndex)
    }

    @Test
    fun cancellationDuringCanonicalComparisonStopsBeforeReadingEveryRow() = runTest {
        lateinit var operation: Deferred<Unit>
        val concreteRows = List(2_000) { index ->
            ClinicalGlucosePoint(index * MINUTE_MS, 6.0)
        }
        val base = minimalDataset()
        val concrete = base.copy(detail24h = base.detail24h.copy(glucose = concreteRows))
        val expected = ClinicalReportDatasetBuilder.serialize(concrete)
        val lastReadIndex = AtomicInteger(-1)
        val cancellingRows = object : AbstractList<ClinicalGlucosePoint>() {
            override val size: Int = concreteRows.size

            override fun get(index: Int): ClinicalGlucosePoint {
                lastReadIndex.accumulateAndGet(index) { current, next -> maxOf(current, next) }
                if (index == 300) operation.cancel(CancellationException("comparison cancellation"))
                return concreteRows[index]
            }
        }
        val source = base.copy(detail24h = base.detail24h.copy(glucose = cancellingRows))
        operation = async(start = CoroutineStart.LAZY) {
            ClinicalReportDatasetBuilder.validateCanonicalCancellable(source, expected)
        }

        operation.start()
        val failure = runCatching { operation.await() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(lastReadIndex.get()).isLessThan(cancellingRows.lastIndex)
    }

    @Test
    fun eventSerializationDropsProtectedContextBridgeMarkersAtBoundary() {
        val marker = CopilotContextNoteMarker.commandHeader(
            localEventId = "manual:report-marker",
            revision = 3L,
            operation = AapsContextEventGateway.Operation.UPDATE
        )
        val dataset = minimalDataset().withEventPeriods(
            generatedAt = DAY_MS,
            events = listOf(
                ClinicalEventSummary(
                    localId = "local",
                    type = "CUSTOM",
                    subtype = "",
                    startTs = DAY_MS - 60_000L,
                    endTs = DAY_MS,
                    severity = "LOW",
                    source = "AAPS",
                    title = "Protected bridge echo $marker",
                    note = "$marker|title|note",
                    status = "CLOSED",
                    provenance = "therapy_events"
                ),
                ClinicalEventSummary(
                    localId = "base-only",
                    type = "CUSTOM",
                    subtype = "",
                    startTs = DAY_MS - 120_000L,
                    endTs = DAY_MS - 60_000L,
                    severity = "LOW",
                    source = "AAPS",
                    title = "Ordinary note",
                    note = CopilotContextNoteMarker.baseMarker("manual:base-only-marker"),
                    status = "CLOSED",
                    provenance = "therapy_events"
                )
            )
        )

        val compact = ClinicalReportDatasetBuilder.serialize(dataset)

        assertThat(compact).doesNotContain("COPILOT_CONTEXT_V1")
        assertThat(compact).doesNotContain(AapsContextEventGateway.localEventHash("manual:report-marker").take(16))
    }

    private fun assertInvalidCurrentSnapshot(
        snapshot: ClinicalCurrentSnapshot,
        forbiddenValue: String
    ) {
        val failure = runCatching {
            ClinicalReportDatasetBuilder.serialize(
                minimalDataset().copy(currentSnapshot = snapshot)
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ClinicalOpenAiException.InvalidInput::class.java)
        assertThat(failure?.message).isEqualTo("Clinical analysis input is invalid")
        assertThat(failure?.message).doesNotContain(forbiddenValue)
    }

    @Test
    fun remoteEventPreviewIsExactWireTextAndContainsNoInternalMarkerOrId() {
        val marker = CopilotContextNoteMarker.commandHeader(
            localEventId = "manual:preview",
            revision = 2L,
            operation = AapsContextEventGateway.Operation.UPDATE
        )
        val dataset = minimalDataset().withEventPeriods(
            generatedAt = DAY_MS,
            events = listOf(
                ClinicalEventSummary(
                    localId = "aaps-row-secret",
                    type = "CUSTOM",
                    subtype = "ordinary",
                    startTs = DAY_MS - MINUTE_MS,
                    endTs = DAY_MS,
                    severity = "HIGH",
                    source = "AAPS",
                    title = "Visible title",
                    note = "Visible note",
                    status = "CLOSED",
                    provenance = "therapy_events:aapsId=42"
                ),
                ClinicalEventSummary(
                    localId = "protected-row",
                    type = "CUSTOM",
                    subtype = "",
                    startTs = DAY_MS - 2L * MINUTE_MS,
                    endTs = DAY_MS - MINUTE_MS,
                    severity = "LOW",
                    source = "AAPS",
                    title = marker,
                    note = "$marker|hidden",
                    status = "CLOSED",
                    provenance = "therapy_events"
                )
            )
        )

        val compactRoot = JsonParser.parseString(ClinicalReportDatasetBuilder.serialize(dataset)).asJsonObject
        val previewRoot = JsonParser.parseString(
            ClinicalReportDatasetBuilder.remoteEventPreviewJson(dataset)
        ).asJsonObject

        assertThat(previewRoot.get("ev24")).isEqualTo(compactRoot.get("ev24"))
        assertThat(previewRoot.get("ev7")).isEqualTo(compactRoot.get("ev7"))
        assertThat(previewRoot.get("ev30")).isEqualTo(compactRoot.get("ev30"))
        assertThat(previewRoot.toString()).contains("Visible title")
        assertThat(previewRoot.toString()).contains("Visible note")
        assertThat(previewRoot.toString()).doesNotContain("aaps-row-secret")
        assertThat(previewRoot.toString()).doesNotContain("aapsId")
        assertThat(previewRoot.toString()).doesNotContain("COPILOT_CONTEXT_V1")
    }

    @Test
    fun localDatasetRetainsMoreThanThirtyTwoTimelineEventsAndFullDomainNote() = runTest {
        val now = 40L * MINUTE_MS
        val note = "full-local-note-" + "x".repeat(480)
        val events = (0 until 40).map { index ->
            CompensationEvent(
                localId = "local-$index",
                startTs = (index + 1L) * MINUTE_MS,
                endTs = (index + 2L) * MINUTE_MS,
                type = CompensationEventType.CUSTOM,
                source = EventSource.USER,
                title = "title-$index",
                note = if (index == 0) note else "note-$index"
            )
        }
        val builder = ClinicalReportDatasetBuilder(
            source = FakeClinicalReportDataSource(
                glucose = emptyList(),
                therapy = emptyList(),
                forecasts = emptyList(),
                telemetry = emptyList()
            ),
            resolveCalibratedGlucose = { _, _ -> emptyList() },
            eventTimelineSource = ClinicalEventTimelineSource { _, _ -> events }
        )

        val payload = builder.build(now, ZoneId.of("UTC"))

        assertThat(payload.dataset.eventSummaries).hasSize(40)
        assertThat(payload.dataset.eventSummaries.first().note).isEqualTo(note)
        assertThat(payload.dataset.eventTypeAssociations.single().eventCount).isEqualTo(40)
        assertThat(JsonParser.parseString(payload.compactJson).asJsonObject.getAsJsonArray("ev30"))
            .hasSize(40)
    }

    @Test
    fun reportUsesSameRetainedOverlapEventsForSummariesAndAssociations() = runTest {
        val now = 40L * DAY_MS
        val from30d = now - 30L * DAY_MS
        val overlapping = CompensationEvent(
            localId = "boundary-overlap",
            startTs = from30d - 3L * DAY_MS,
            endTs = from30d + DAY_MS,
            type = CompensationEventType.STRESS,
            source = EventSource.USER,
            title = "Spans report boundary"
        )
        val expiredLookback = CompensationEvent(
            localId = "expired-lookback",
            startTs = from30d - 6L * DAY_MS,
            endTs = from30d - DAY_MS,
            type = CompensationEventType.STRESS,
            source = EventSource.USER,
            title = "Expired before report boundary"
        )
        var requestedWindow: Pair<Long, Long>? = null
        val builder = ClinicalReportDatasetBuilder(
            source = FakeClinicalReportDataSource(emptyList(), emptyList(), emptyList(), emptyList()),
            resolveCalibratedGlucose = { _, _ -> emptyList() },
            eventTimelineSource = ClinicalEventTimelineSource { fromTs, throughTs ->
                requestedWindow = fromTs to throughTs
                listOf(overlapping, overlapping.copy(title = "duplicate must not win"), expiredLookback)
            }
        )

        val payload = builder.build(now, ZoneId.of("UTC"))

        assertThat(requestedWindow).isEqualTo(from30d to now)
        assertThat(payload.dataset.eventSummaries.map { it.localId })
            .containsExactly("boundary-overlap")
        assertThat(payload.dataset.eventTypeAssociations.single().eventCount).isEqualTo(1)
        assertThat(payload.dataset.eventTypeAssociations.single().totalDurationMinutes)
            .isEqualTo(4L * 24L * 60L)
        assertThat(JsonParser.parseString(payload.compactJson).asJsonObject.getAsJsonArray("ev30"))
            .hasSize(1)
    }

    @Test
    fun duplicateLocalIdWinnerIsIndependentOfTimelineSourceOrder() = runTest {
        val now = 40L * DAY_MS
        val lowerRanked = CompensationEvent(
            localId = "conflicting-id",
            startTs = now - 2L * DAY_MS,
            endTs = now - DAY_MS,
            type = CompensationEventType.ILLNESS,
            subtype = "A",
            severity = io.aaps.copilot.domain.events.EventSeverity.LOW,
            source = EventSource.USER,
            title = "A conflict",
            attributes = mapOf("context" to "a"),
            note = "a note",
            revision = 2L,
            status = io.aaps.copilot.domain.events.CompensationEventStatus.CLOSED,
            provenance = "a-source"
        )
        val higherRanked = lowerRanked.copy(
            startTs = now - DAY_MS,
            endTs = now,
            type = CompensationEventType.STRESS,
            subtype = "Z",
            severity = io.aaps.copilot.domain.events.EventSeverity.HIGH,
            source = EventSource.AAPS,
            title = "Z conflict",
            attributes = mapOf("context" to "z", "source" to "uam_engine"),
            note = "z note",
            revision = 3L,
            status = io.aaps.copilot.domain.events.CompensationEventStatus.ACTIVE,
            provenance = "z-source"
        )
        suspend fun build(events: List<CompensationEvent>) = ClinicalReportDatasetBuilder(
            source = FakeClinicalReportDataSource(emptyList(), emptyList(), emptyList(), emptyList()),
            resolveCalibratedGlucose = { _, _ -> emptyList() },
            eventTimelineSource = ClinicalEventTimelineSource { _, _ -> events }
        ).build(now, ZoneId.of("UTC"))

        val forward = build(listOf(lowerRanked, higherRanked))
        val reverse = build(listOf(higherRanked, lowerRanked))

        assertThat(forward.compactJson).isEqualTo(reverse.compactJson)
        assertThat(forward.dataset.eventSummaries).isEqualTo(reverse.dataset.eventSummaries)
        assertThat(forward.dataset.eventTypeAssociations)
            .isEqualTo(reverse.dataset.eventTypeAssociations)
        assertThat(forward.dataset.eventSummaries.single().type).isEqualTo("STRESS")
        assertThat(forward.dataset.eventTypeAssociations.single().type).isEqualTo("STRESS")
        assertThat(forward.dataset.eventTypeAssociations.single().eventCount).isEqualTo(1)
    }

    @Test
    fun nonAlignedNowRetainsExactDetailTailEventOnlyIn24hWindow() = runTest {
        val summaryThrough = 40L * DAY_MS
        val now = summaryThrough + 2L * MINUTE_MS
        val from30d = summaryThrough - 30L * DAY_MS
        val boundaryOverlap = CompensationEvent(
            localId = "boundary-overlap",
            startTs = from30d - MINUTE_MS,
            endTs = from30d,
            type = CompensationEventType.STRESS,
            source = EventSource.USER,
            title = "Exact 30-day boundary"
        )
        val exactDetailTail = CompensationEvent(
            localId = "exact-detail-tail",
            startTs = summaryThrough + MINUTE_MS,
            endTs = summaryThrough + MINUTE_MS,
            type = CompensationEventType.STRESS,
            source = EventSource.USER,
            title = "Exact detail tail"
        )
        val expiredLookback = CompensationEvent(
            localId = "expired-lookback",
            startTs = from30d - 2L * MINUTE_MS,
            endTs = from30d - 1L,
            type = CompensationEventType.STRESS,
            source = EventSource.USER,
            title = "Expired before exact boundary"
        )
        var requestedWindow: Pair<Long, Long>? = null
        val builder = ClinicalReportDatasetBuilder(
            source = FakeClinicalReportDataSource(emptyList(), emptyList(), emptyList(), emptyList()),
            resolveCalibratedGlucose = { _, _ -> emptyList() },
            eventTimelineSource = ClinicalEventTimelineSource { fromTs, throughTs ->
                requestedWindow = fromTs to throughTs
                listOf(
                    boundaryOverlap,
                    boundaryOverlap.copy(title = "A lower-ranked boundary duplicate"),
                    exactDetailTail,
                    exactDetailTail.copy(title = "A lower-ranked tail duplicate"),
                    expiredLookback
                )
            }
        )

        val payload = builder.build(now, ZoneId.of("UTC"))
        val root = JsonParser.parseString(payload.compactJson).asJsonObject
        fun eventTitles(window: String) = root.getAsJsonArray(window)
            .mapNotNull { it.asJsonObject.get("title")?.asString }

        assertThat(requestedWindow).isEqualTo(from30d to now)
        assertThat(payload.dataset.detail24h.throughTs).isEqualTo(now)
        assertThat(payload.dataset.summary7d.throughTs).isEqualTo(summaryThrough)
        assertThat(payload.dataset.summary30d.throughTs).isEqualTo(summaryThrough)
        assertThat(payload.dataset.eventSummaries.map { it.localId })
            .containsExactly("boundary-overlap", "exact-detail-tail")
            .inOrder()
        assertThat(payload.dataset.eventTypeAssociations.single().eventCount).isEqualTo(2)
        assertThat(eventTitles("ev24")).containsExactly("Exact detail tail")
        assertThat(eventTitles("ev7")).isEmpty()
        assertThat(eventTitles("ev30")).containsExactly("Exact 30-day boundary")
        assertThat(eventTitles("ev24") + eventTitles("ev7") + eventTitles("ev30"))
            .containsExactly("Exact detail tail", "Exact 30-day boundary")
    }

    @Test
    fun localPerTypeAssociationsUseCanonicalSamplesAndLabelUnavailableMetrics() {
        val events = listOf(
            CompensationEvent(
                localId = "stress-1",
                startTs = 1L,
                endTs = 10L * MINUTE_MS + 1L,
                type = CompensationEventType.STRESS,
                source = EventSource.USER
            ),
            CompensationEvent(
                localId = "stress-2",
                startTs = 20L * MINUTE_MS,
                endTs = 30L * MINUTE_MS,
                type = CompensationEventType.STRESS,
                source = EventSource.USER
            )
        )
        val associations = ClinicalReportDatasetBuilder.eventTypeAssociations(
            events = events,
            glucose = listOf(
                ClinicalGlucosePoint(1L, 5.0),
                ClinicalGlucosePoint(10L * MINUTE_MS, 7.0),
                ClinicalGlucosePoint(20L * MINUTE_MS, 6.0),
                ClinicalGlucosePoint(30L * MINUTE_MS, 5.0)
            ),
            therapy = listOf(
                ClinicalTherapyPoint(5L * MINUTE_MS, carbsG = 10.0, syntheticUam = true)
            ),
            forecasts = listOf(
                ClinicalForecastPoint(1L, 10, 6.0, 5.0, 7.0)
            ),
            telemetry = listOf(
                ClinicalTelemetryPoint(5L * MINUTE_MS, "isf_runtime_selected_value", 2.0, "OK"),
                ClinicalTelemetryPoint(5L * MINUTE_MS, "cr_runtime_selected_value", 10.0, "OK")
            )
        ).single()

        assertThat(associations.type).isEqualTo("STRESS")
        assertThat(associations.eventCount).isEqualTo(2)
        assertThat(associations.totalDurationMinutes).isEqualTo(20L)
        assertThat(associations.glucose.sampleCount).isEqualTo(4)
        assertThat(associations.glucose.mean).isEqualTo(5.75)
        assertThat(associations.trend.sampleCount).isEqualTo(2)
        assertThat(associations.trend.mean).isEqualTo(0.5)
        assertThat(associations.uam.sampleCount).isEqualTo(1)
        assertThat(associations.isf.sampleCount).isEqualTo(1)
        assertThat(associations.cr.sampleCount).isEqualTo(1)
        assertThat(associations.forecastError.sampleCount).isEqualTo(1)
        assertThat(associations.forecastError.mean).isEqualTo(1.0)
        assertThat(associations.causal).isFalse()
    }

    private fun minimalDataset(): ClinicalReportDataset {
        val quality = ClinicalDataQuality(1, 1, 0, null)
        val hourly = (0..23).map { ClinicalHourlyMetric(it, 0, null, null) }
        val summary = ClinicalPeriodSummary(
            days = 7,
            fromTs = 0,
            throughTs = 0,
            coveragePct = 100.0,
            meanMmol = 6.0,
            medianMmol = 6.0,
            coefficientOfVariationPct = 0.0,
            timeBelow4Pct = 0.0,
            timeInRangePct = 100.0,
            timeAboveRangePct = 0.0,
            totalInsulinU = 0.0,
            totalCarbsG = 0.0,
            meanTargetMmol = 6.0,
            weekdayPattern = hourly,
            weekendPattern = hourly,
            quality = quality
        )
        return ClinicalReportDataset(
            schemaVersion = ClinicalReportDatasetBuilder.SCHEMA_VERSION,
            generatedAt = 10 * 60_000L,
            zoneId = "UTC",
            detail24h = ClinicalDetailWindow(
                fromTs = 0,
                throughTs = 10 * 60_000L,
                glucose = emptyList(),
                calibratedGlucose = emptyList(),
                therapy = emptyList(),
                targets = emptyList(),
                forecasts = emptyList(),
                telemetry = emptyList()
            ),
            glucose7d = listOf(ClinicalGlucosePoint(0, 6.0)),
            therapy7d = emptyList(),
            targets7d = emptyList(),
            glucose30d = listOf(ClinicalGlucosePoint(0, 6.0)),
            therapy30d = emptyList(),
            targets30d = emptyList(),
            summary7d = summary,
            summary30d = summary.copy(days = 30)
        )
    }

    private fun ClinicalReportDataset.withEventPeriods(
        generatedAt: Long,
        events: List<ClinicalEventSummary>
    ): ClinicalReportDataset = copy(
        generatedAt = generatedAt,
        detail24h = detail24h.copy(
            fromTs = generatedAt - DAY_MS,
            throughTs = generatedAt
        ),
        summary7d = summary7d.copy(
            fromTs = generatedAt - 7L * DAY_MS,
            throughTs = generatedAt
        ),
        summary30d = summary30d.copy(
            fromTs = generatedAt - 30L * DAY_MS,
            throughTs = generatedAt
        ),
        eventSummaries = events
    )

    private fun exactEightMiBDataset(extraAsciiBytes: Int): ClinicalReportDataset {
        val base = minimalDataset()
        val independentlySizedKey = "é😀\"escaped\\line\n" +
            "x".repeat(
                ClinicalOpenAiClient.MAX_COMPACT_DATASET_BYTES -
                    EXACT_EIGHT_MIB_NON_FILLER_BYTES +
                    extraAsciiBytes
            )
        return base.copy(
            detail24h = base.detail24h.copy(
                telemetry = listOf(
                    ClinicalTelemetryPoint(
                        ts = 0L,
                        key = independentlySizedKey,
                        value = 1.0,
                        quality = "OK"
                    )
                )
            )
        )
    }

    private fun frozenCurrentSnapshot(includeMetadata: Boolean) = ClinicalCurrentSnapshot(
        rawGlucoseMmol = 6.1,
        calibratedGlucoseMmol = 6.2,
        glucoseSampleAgeMinutes = 3.0,
        prediction30mMmol = 6.3,
        prediction30mAgeMinutes = 2.0,
        effectiveIobUnits = 1.2,
        effectiveCobGrams = 12.0,
        selectedIsfMmolPerUnit = 2.5,
        selectedIsfSourceCode = 1.0,
        selectedCrGramsPerUnit = 10.0,
        selectedCrSourceCode = 3.0,
        sensitivitySettingsRevision = 42L.takeIf { includeMetadata },
        selectedIsfSource = "AAPS".takeIf { includeMetadata },
        selectedCrSource = "COPILOT_NATIVE".takeIf { includeMetadata },
        uamActiveFlag = 1.0,
        uamEquivalentCarbsGrams = 8.0,
        uamConfidence = 0.75,
        sensorQualityScore = 0.9,
        sensorBlockedFlag = 0.0,
        sensorAgeHours = 24.0,
        sensorLagMinutes = 3.0,
        activityRatio = 1.1,
        stepsCount = 1_234.0,
        activeTargetLowMmol = 4.5,
        activeTargetHighMmol = 6.0
    )

    private fun rawCurrentObject(compact: String): String {
        val start = compact.indexOf("\"c\":") + "\"c\":".length
        val end = compact.indexOf(",\"d24\":", start)
        check(start >= "\"c\":".length && end > start)
        return compact.substring(start, end)
    }

    private fun assertSyntheticCarbKeyIsExcluded(syntheticPayload: String) {
        val from = ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli()
        val therapy = listOf(
            ClinicalReportDatasetBuilder.sanitizeTherapy(
                ClinicalTherapyProjection(
                    ts = from,
                    type = "carbs",
                    payloadJson = """{"carbs":20}"""
                )
            ),
            ClinicalReportDatasetBuilder.sanitizeTherapy(
                ClinicalTherapyProjection(
                    ts = from + MINUTE_MS,
                    type = "carbs",
                    payloadJson = syntheticPayload
                )
            )
        ).filterNotNull()

        val summary = ClinicalSummaryCalculator.calculate(
            days = 7,
            fromTs = from,
            throughTs = from + SEVEN_DAYS_MS,
            zoneId = ZoneId.of("UTC"),
            glucose = emptyList(),
            therapy = therapy,
            targets = emptyList()
        )

        assertThat(summary.totalCarbsG).isEqualTo(35.0)
        assertThat(summary.enteredCarbsG).isEqualTo(20.0)
        assertThat(summary.uamCarbsG).isEqualTo(15.0)
        assertThat(therapy.last().syntheticUam).isTrue()
    }

    private fun therapyProjection(
        id: String = "",
        ts: Long,
        type: String,
        payload: String
    ): ClinicalTherapyProjection = ClinicalTherapyProjection(
        ts = ts,
        type = type,
        payloadJson = payload,
        isBroadcastArtifact = TherapySanitizer.isLocalBroadcastArtifact(id, type),
        rowId = id
    )

    private class FakeClinicalReportDataSource(
        private val glucose: List<ClinicalGlucoseProjection>,
        private val therapy: List<ClinicalTherapyProjection>,
        private val forecasts: List<ClinicalForecastProjection>,
        private val telemetry: List<ClinicalTelemetryProjection>,
        private val sensitivityRuntime: SensitivityRuntimeSnapshotEntity? = null
    ) : ClinicalReportDataSource {
        var glucoseWindow: Pair<Long, Long>? = null
        var therapyWindow: Pair<Long, Long>? = null
        var forecastWindow: Pair<Long, Long>? = null
        var telemetryWindow: Pair<Long, Long>? = null
        var summaryTelemetryWindow: Pair<Long, Long>? = null

        override suspend fun readSnapshot(
            request: ClinicalReportReadRequest
        ): ClinicalReportSnapshot {
            glucoseWindow = request.glucoseFromTs to request.toTs
            therapyWindow = request.therapyFromTs to request.toTs
            forecastWindow = request.detailFromTs to request.toTs
            telemetryWindow = request.detailFromTs to request.toTs
            summaryTelemetryWindow = request.summaryTelemetryFromTs to request.toTs
            assertThat(request.therapyTypes)
                .containsExactlyElementsIn(ClinicalReportDatasetBuilder.THERAPY_TYPES)
            assertThat(request.telemetryKeys)
                .containsExactlyElementsIn(ClinicalReportDatasetBuilder.TELEMETRY_KEYS)
            assertThat(request.summaryTelemetryKeys)
                .containsExactlyElementsIn(ClinicalReportDatasetBuilder.SUMMARY_TELEMETRY_KEYS)
            return ClinicalReportSnapshot(
                glucose = glucose,
                therapy = therapy,
                forecasts = forecasts,
                telemetry = telemetry.filter { it.key in request.telemetryKeys },
                summaryTelemetry = telemetry.filter { it.key in request.summaryTelemetryKeys },
                sensitivityRuntime = sensitivityRuntime
            )
        }

        override suspend fun glucose(fromTs: Long, toTs: Long): List<ClinicalGlucoseProjection> {
            glucoseWindow = fromTs to toTs
            return glucose
        }

        override suspend fun therapy(
            fromTs: Long,
            toTs: Long,
            types: List<String>
        ): List<ClinicalTherapyProjection> {
            therapyWindow = fromTs to toTs
            assertThat(types).containsExactlyElementsIn(ClinicalReportDatasetBuilder.THERAPY_TYPES)
            return therapy
        }

        override suspend fun forecasts(
            fromTs: Long,
            toTs: Long
        ): List<ClinicalForecastProjection> {
            forecastWindow = fromTs to toTs
            return forecasts
        }

        override suspend fun telemetry(
            fromTs: Long,
            toTs: Long,
            keys: List<String>
        ): List<ClinicalTelemetryProjection> {
            telemetryWindow = fromTs to toTs
            assertThat(keys).containsExactlyElementsIn(ClinicalReportDatasetBuilder.TELEMETRY_KEYS)
            return telemetry
        }
    }

    private fun emptySource() = object : ClinicalReportDataSource {
        override suspend fun glucose(fromTs: Long, toTs: Long) =
            emptyList<ClinicalGlucoseProjection>()

        override suspend fun therapy(fromTs: Long, toTs: Long, types: List<String>) =
            emptyList<ClinicalTherapyProjection>()

        override suspend fun forecasts(fromTs: Long, toTs: Long) =
            emptyList<ClinicalForecastProjection>()

        override suspend fun telemetry(fromTs: Long, toTs: Long, keys: List<String>) =
            emptyList<ClinicalTelemetryProjection>()
    }

    private companion object {
        const val FROZEN_CURRENT_WITH_METADATA_JSON =
            "{\"g\":6.1,\"gc\":6.2,\"age\":3.0,\"p30\":6.3,\"p30Age\":2.0," +
                "\"iob\":1.2,\"cob\":12.0,\"isf\":2.5,\"isfSrc\":1.0,\"cr\":10.0," +
                "\"crSrc\":3.0,\"sensitivityRev\":42,\"isfProvenance\":\"AAPS\"," +
                "\"crProvenance\":\"COPILOT_NATIVE\",\"uam\":1.0,\"uamCarbs\":8.0," +
                "\"uamConf\":0.75,\"sensorQ\":0.9,\"sensorBlocked\":0.0,\"sensorAge\":24.0," +
                "\"sensorLag\":3.0,\"activity\":1.1,\"steps\":1234.0,\"targetLow\":4.5," +
                "\"targetHigh\":6.0}"
        const val FROZEN_CURRENT_WITHOUT_METADATA_JSON =
            "{\"g\":6.1,\"gc\":6.2,\"age\":3.0,\"p30\":6.3,\"p30Age\":2.0," +
                "\"iob\":1.2,\"cob\":12.0,\"isf\":2.5,\"isfSrc\":1.0,\"cr\":10.0," +
                "\"crSrc\":3.0,\"uam\":1.0,\"uamCarbs\":8.0,\"uamConf\":0.75," +
                "\"sensorQ\":0.9,\"sensorBlocked\":0.0,\"sensorAge\":24.0,\"sensorLag\":3.0," +
                "\"activity\":1.1,\"steps\":1234.0,\"targetLow\":4.5,\"targetHigh\":6.0}"
        const val FROZEN_CURRENT_COMPACT_SHA256 =
            "e69a19b133fb8028b5395e88343d9b3f299c02f0eabd185816eda79ffabf7e7b"
        // Independently counted canonical bytes outside the ASCII filler in exactEightMiBDataset.
        const val EXACT_EIGHT_MIB_NON_FILLER_BYTES = 4_559
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 24L * 60L * MINUTE_MS
        const val SEVEN_DAYS_MS = 7L * 24L * 60L * MINUTE_MS
        const val THIRTY_DAYS_MS = 30L * DAY_MS
        const val SEVEN_DAYS_MINUTES = 7L * 24L * 60L
    }
}
