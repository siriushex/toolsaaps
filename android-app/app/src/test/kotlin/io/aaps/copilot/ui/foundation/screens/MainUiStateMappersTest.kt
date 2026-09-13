package io.aaps.copilot.ui.foundation.screens

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.ClinicalAiProviderConfig
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.config.OpenAiCompatibleProtocol
import io.aaps.copilot.config.executionIdentity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.EnergyProfileSnapshotEntity
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import io.aaps.copilot.data.repository.ClinicalDataQuality
import io.aaps.copilot.data.repository.ClinicalDataQualityFlag
import io.aaps.copilot.data.repository.ClinicalAdvisoryPriority
import io.aaps.copilot.data.repository.ClinicalAdvisoryReport
import io.aaps.copilot.data.repository.ClinicalActivitySummary
import io.aaps.copilot.data.repository.ClinicalCareTeamDiscussionTopic
import io.aaps.copilot.data.repository.ClinicalCareTeamQuestion
import io.aaps.copilot.data.repository.ClinicalEvidenceMetric
import io.aaps.copilot.data.repository.ClinicalEvidencePeriod
import io.aaps.copilot.data.repository.ClinicalFinding
import io.aaps.copilot.data.repository.ClinicalFindingConfidence
import io.aaps.copilot.data.repository.ClinicalLocalReport
import io.aaps.copilot.data.repository.ClinicalOpenAiMetadata
import io.aaps.copilot.data.repository.ClinicalOpenAiProgressStage
import io.aaps.copilot.data.repository.ClinicalPatternDirection
import io.aaps.copilot.data.repository.ClinicalPatternTopic
import io.aaps.copilot.data.repository.ClinicalPeriodSummary
import io.aaps.copilot.data.repository.ClinicalRecommendation
import io.aaps.copilot.data.repository.ClinicalReportFailureReason
import io.aaps.copilot.data.repository.ClinicalReportState
import io.aaps.copilot.data.repository.ClinicalSafetyObservation
import io.aaps.copilot.data.repository.ClinicalSummaryStatus
import io.aaps.copilot.data.repository.ClinicalTimeBand
import io.aaps.copilot.data.repository.AcceptedSensitivityCandidateDiagnostics
import io.aaps.copilot.data.repository.TargetManagerLiveStatus
import io.aaps.copilot.data.repository.TargetManagerLiveStatusCodec
import io.aaps.copilot.domain.target.BaseTargetInterval
import io.aaps.copilot.domain.target.BaseTargetSchedule
import io.aaps.copilot.domain.target.CircadianAutoState
import io.aaps.copilot.domain.eating.ProbableEatingWindow
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.profile.ActivityScheduleEngine
import io.aaps.copilot.domain.profile.EnergyProfileSettings
import io.aaps.copilot.domain.profile.PlannedActivityIntensity
import io.aaps.copilot.domain.profile.PlannedActivitySchedule
import io.aaps.copilot.domain.profile.PlannedActivityType
import io.aaps.copilot.domain.profile.PhysiologicalSex
import io.aaps.copilot.domain.predict.SensitivityCandidate
import io.aaps.copilot.domain.predict.SensitivityCandidates
import io.aaps.copilot.domain.predict.AcceptedSensitivityTupleFreshness
import io.aaps.copilot.domain.predict.SensitivityMetricDecision
import io.aaps.copilot.domain.predict.SensitivityResolvedSource
import io.aaps.copilot.domain.predict.SensitivityRuntimeSnapshot
import io.aaps.copilot.domain.predict.SensitivitySourcePreference
import io.aaps.copilot.domain.profile.ScheduleValidation
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.Instant
import java.time.DayOfWeek
import io.aaps.copilot.ui.AuditRecordRowUi
import io.aaps.copilot.ui.AnalysisHistoryRowUi
import io.aaps.copilot.ui.AnalysisTrendRowUi
import io.aaps.copilot.ui.CloudJobRowUi
import io.aaps.copilot.ui.GlucoseHistoryRowUi
import io.aaps.copilot.ui.IsfCrHistoryPointUi
import io.aaps.copilot.ui.IsfCrOverlayPointUi
import io.aaps.copilot.ui.LastActionRowUi
import io.aaps.copilot.ui.MainUiState
import io.aaps.copilot.ui.MainViewModel
import io.aaps.copilot.ui.UamEventRowUi
import io.aaps.copilot.ui.buildIsfCrOverlayPoints
import io.aaps.copilot.ui.foundation.screens.ChartPointUi
import io.aaps.copilot.ui.foundation.screens.SensorLagTimelineSegmentUi
import io.aaps.copilot.service.PowerSaveController
import org.junit.Test

class MainUiStateMappersTest {
    @Test
    fun overviewChartUsesPersistedEventVisibilitySetting() {
        val hidden = MainUiState().toOverviewUiState(
            isProMode = false,
            showEventsOnGraph = false
        )
        val visible = MainUiState().toOverviewUiState(
            isProMode = false,
            showEventsOnGraph = true
        )

        assertThat(hidden.chart.showEvents).isFalse()
        assertThat(visible.chart.showEvents).isTrue()
    }

    private fun state(block: MainUiState.() -> Unit): MainUiState = MainUiState().apply(block)

    @Test
    fun overviewEventListAndGraphUseTheSameAcceptedTimeline() {
        val accepted = listOf(
            CompensationEvent(
                localId = "historical-cycle",
                startTs = 1_000L,
                endTs = 61_000L,
                type = CompensationEventType.MENSTRUAL_CYCLE
            )
        )

        val overview = MainUiState().toOverviewUiState(
            isProMode = false,
            eventTimeline = accepted,
            profileSex = PhysiologicalSex.MALE
        )

        assertThat(overview.events).isEqualTo(accepted)
        assertThat(overview.chart.events).isEqualTo(accepted)
    }

    private fun uamEvent(
        state: String = "CONFIRMED",
        updatedAt: Long,
        reason: String?
    ) = UamEventRowUi(
        id = "event-$updatedAt",
        state = state,
        mode = "NORMAL",
        createdAt = updatedAt - 2_000L,
        updatedAt = updatedAt,
        ingestionTs = updatedAt - 1_000L,
        carbsDisplayG = 10.0,
        confidence = 0.80,
        exportSeq = 0,
        exportedGrams = 0.0,
        tag = "audit",
        manualCarbsNearby = false,
        manualCobActive = false,
        exportBlockedReason = reason
    )

    private fun uamRuntimeTelemetry(
        timestamp: Long,
        sensitivityCycleId: String = "accepted-cycle"
    ): Map<String, TelemetrySampleEntity> {
        fun sample(key: String, value: Double? = null, text: String? = null) = TelemetrySampleEntity(
            id = "$key-$timestamp",
            timestamp = timestamp,
            source = "test",
            key = key,
            valueDouble = value,
            valueText = text,
            unit = null,
            quality = "OK"
        )
        return listOf(
            sample("uam_runtime_flag", value = 1.0),
            sample("uam_runtime_state", text = "ACTIVE"),
            sample("uam_runtime_equivalent_carbs_grams", value = 15.0),
            sample("uam_runtime_confidence", value = 0.82),
            sample("uam_runtime_sensitivity_cycle_id", text = sensitivityCycleId)
        ).associateBy { it.key }
    }

    @Test
    fun energyProfileDefaultsOffAndAiSharingStaysIndependent() {
        val ui = energyProfileSettingsUiState(
            settings = EnergyProfileSettings(),
            snapshot = null,
            events = emptyList(),
            today = LocalDate.of(2026, 8, 8)
        )

        assertThat(ui.enabled).isFalse()
        assertThat(ui.shareProfileWithAi).isTrue()
        assertThat(ui.foodSummary.source).isEqualTo("Default")
    }

    @Test
    fun energyProfileMapsDerivedAgeAndAutoEvidence() {
        val ui = energyProfileSettingsUiState(
            settings = EnergyProfileSettings(
                birthDateEpochDay = LocalDate.of(2009, 8, 8).toEpochDay(),
                heightCm = 165.0,
                weightKg = 55.0
            ),
            snapshot = EnergyProfileSnapshotEntity(
                snapshotId = "snapshot",
                schemaVersion = 1,
                evidenceStartMs = 1L,
                evidenceEndMs = 2L,
                qualityDays = 7,
                tier = "PROVISIONAL",
                foodProfile = "MIXED",
                foodDurationMinutes = 120,
                activityProfile = null,
                confidence = 0.7,
                replayPassed = false,
                sourceHashSha256 = "hash",
                calculatedAtMs = 3L,
                stale = false
            ),
            events = listOf(plannedEventEntity()),
            today = LocalDate.of(2026, 8, 8)
        )

        assertThat(ui.derivedAgeYears).isEqualTo(17)
        assertThat(ui.foodSummary.source).isEqualTo("Auto")
        assertThat(ui.foodSummary.confidence.name).isEqualTo("PROVISIONAL")
        assertThat(ui.foodSummary.calculatedAtMs).isEqualTo(3L)
        assertThat(ui.plannedEvents).hasSize(1)
    }

    @Test
    fun overlappingPlannedActivityIsRejected() {
        val engine = ActivityScheduleEngine()
        val first = plannedSchedule("first", LocalDateTime.of(2026, 8, 10, 10, 0), 60)
        val second = plannedSchedule("second", LocalDateTime.of(2026, 8, 10, 10, 30), 60)

        assertThat(engine.validate(listOf(first, second))).isInstanceOf(ScheduleValidation.Overlap::class.java)
    }

    @Test
    fun plannedActivitySerializationRetainsAllScheduleContractFields() {
        val event = plannedEventUi().copy(
            enabled = false,
            title = "Strength session",
            activityType = "STRENGTH",
            intensity = "HIGH",
            localStartIso = "2026-08-10T17:45",
            durationMinutes = 75,
            timezoneId = "Asia/Tbilisi",
            recurrenceDaysMask = (1 shl (DayOfWeek.MONDAY.value - 1)) or
                (1 shl (DayOfWeek.THURSDAY.value - 1)),
            recurrenceEndEpochDay = LocalDate.of(2026, 9, 1).toEpochDay(),
            revision = 4L
        )

        val schedule = checkNotNull(event.toScheduleOrNull())

        assertThat(schedule.enabled).isFalse()
        assertThat(schedule.title).isEqualTo("Strength session")
        assertThat(schedule.type.name).isEqualTo("STRENGTH")
        assertThat(schedule.intensity.name).isEqualTo("HIGH")
        assertThat(schedule.localStart).isEqualTo(LocalDateTime.of(2026, 8, 10, 17, 45))
        assertThat(schedule.durationMinutes).isEqualTo(75)
        assertThat(schedule.timezoneId).isEqualTo("Asia/Tbilisi")
        assertThat(schedule.recurrenceDays).containsExactly(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
        assertThat(schedule.recurrenceEndEpochDay).isEqualTo(LocalDate.of(2026, 9, 1).toEpochDay())
        assertThat(schedule.revision).isEqualTo(4L)
        assertThat(event.copy(timezoneId = "invalid/timezone").toScheduleOrNull()).isNull()
        assertThat(event.copy(durationMinutes = 0).toScheduleOrNull()).isNull()
        assertThat(
            event.copy(recurrenceEndEpochDay = LocalDate.of(2026, 8, 9).toEpochDay())
                .toScheduleOrNull()
        ).isNull()
    }

    @Test
    fun energyActivityOverviewStatusIsAbsentNormallyAndShowsProfileIssueOrApproaching() {
        val now = Instant.parse("2026-08-10T09:00:00Z")
        val settings = completeEnergySettings()
        val snapshot = energySnapshot()

        assertThat(
            energyActivityOverviewStatus(
                settings = settings,
                snapshot = snapshot,
                events = emptyList(),
                now = now
            )
        ).isNull()

        assertThat(
            energyActivityOverviewStatus(
                settings = settings.copy(heightCm = null),
                snapshot = snapshot,
                events = emptyList(),
                now = now
            )?.kind
        ).isEqualTo(EnergyActivityStatusKind.PROFILE_ISSUE)

        val approaching = plannedEventEntity().copy(
            localStartIso = "2026-08-10T09:30",
            timezoneId = "UTC",
            intensity = "LIGHT"
        )
        val status = energyActivityOverviewStatus(
            settings = settings,
            snapshot = snapshot,
            events = listOf(approaching),
            now = now
        )
        assertThat(status?.kind).isEqualTo(EnergyActivityStatusKind.APPROACHING)
        assertThat(status?.minutesUntilStart).isEqualTo(30L)
    }

    private fun completeEnergySettings() = EnergyProfileSettings(
        enabled = true,
        birthDateEpochDay = LocalDate.of(1990, 1, 1).toEpochDay(),
        heightCm = 175.0,
        weightKg = 70.0
    )

    private fun energySnapshot() = EnergyProfileSnapshotEntity(
        snapshotId = "energy",
        schemaVersion = 1,
        evidenceStartMs = 1L,
        evidenceEndMs = 2L,
        qualityDays = 7,
        tier = "PROVISIONAL",
        foodProfile = "MIXED",
        foodDurationMinutes = 120,
        activityProfile = null,
        confidence = 0.7,
        replayPassed = false,
        sourceHashSha256 = "hash",
        calculatedAtMs = 3L,
        stale = false
    )

    private fun plannedEventEntity() = PlannedActivityEventEntity(
        eventId = "event",
        enabled = true,
        title = "Walk",
        activityType = "WALKING",
        intensity = "LIGHT",
        localStartIso = "2026-08-09T10:00",
        durationMinutes = 30,
        timezoneId = "UTC",
        recurrenceDaysMask = 0,
        recurrenceEndEpochDay = null,
        revision = 0L,
        createdAtMs = 1L,
        updatedAtMs = 1L
    )

    private fun plannedEventUi() = PlannedActivityEventUi(
        eventId = "event",
        enabled = true,
        title = "Walk",
        activityType = "WALKING",
        intensity = "LIGHT",
        localStartIso = "2026-08-09T10:00",
        durationMinutes = 30,
        timezoneId = "UTC",
        recurrenceDaysMask = 0,
        recurrenceEndEpochDay = null,
        revision = 0L,
        createdAtMs = 1L,
        updatedAtMs = 1L
    )

    private fun plannedSchedule(id: String, start: LocalDateTime, durationMinutes: Int) =
        PlannedActivitySchedule(
            eventId = id,
            enabled = true,
            title = id,
            type = PlannedActivityType.WALKING,
            intensity = PlannedActivityIntensity.LIGHT,
            localStart = start,
            durationMinutes = durationMinutes,
            timezoneId = ZoneId.of("UTC").id,
            recurrenceDays = emptySet(),
            recurrenceEndEpochDay = null,
            revision = 0L,
            createdAtMs = 1L,
            updatedAtMs = 1L
        )

    @Test
    fun targetManagerManualModeIsVisibleAndActiveRequiresConfirmation() {
        val ui = state {
            targetManagerMode = "ACTIVE"
            targetManagerModeManualOverride = true
            targetManagerCopilotPriorityEnabled = true
            targetManagerPolicyRevision = 9L
        }.toSettingsUiState(
            verboseLogsEnabled = false,
            proModeEnabled = false
        )

        assertThat(ui.targetManagerMode).isEqualTo("ACTIVE")
        assertThat(ui.targetManagerModeManualOverride).isTrue()
        assertThat(ui.targetManagerCopilotPriorityEnabled).isTrue()
        assertThat(ui.targetManagerPolicyRevision).isEqualTo(9L)
        assertThat(targetManagerModeChangeNeedsConfirmation("SHADOW", "ACTIVE")).isTrue()
        assertThat(targetManagerModeChangeNeedsConfirmation("ACTIVE", "ACTIVE")).isFalse()
        assertThat(targetManagerModeChangeNeedsConfirmation("ACTIVE", "SHADOW")).isFalse()
        assertThat(targetManagerModeChangeNeedsConfirmation("SHADOW", "AUTO")).isFalse()
        assertThat(targetManagerPriorityChangeNeedsConfirmation(false, true)).isTrue()
        assertThat(targetManagerPriorityChangeNeedsConfirmation(true, false)).isFalse()
        assertThat(targetManagerPriorityChangeNeedsConfirmation(true, true)).isFalse()
    }

    @Test
    fun targetManagerStatusRequiresFreshExactAuthoritativeSettingsIdentity() {
        val nowTs = 1_800_000_000_000L
        val status = TargetManagerLiveStatus(
            schemaVersion = TargetManagerLiveStatusCodec.SCHEMA_VERSION,
            timestamp = nowTs,
            mode = "ACTIVE",
            priorityEnabled = true,
            policyRevision = 8L,
            currentTargetMmol = 6.4,
            proposedTargetMmol = 5.8,
            outcome = "SEND",
            reason = "eligible"
        )

        val current = mapTargetManagerLiveStatusForUi(status, "ACTIVE", true, 8L, nowTs)
        val waiting = mapTargetManagerLiveStatusForUi(status, "ACTIVE", false, 9L, nowTs)
        val stale = mapTargetManagerLiveStatusForUi(
            status,
            "ACTIVE",
            true,
            8L,
            nowTs + TargetManagerLiveStatusCodec.MAX_AGE_MS + 1L
        )
        val unavailable = mapTargetManagerLiveStatusForUi(null, "ACTIVE", true, 8L, nowTs)

        assertThat(current.availability).isEqualTo(TargetManagerLiveStatusAvailabilityUi.CURRENT)
        assertThat(current.currentTargetMmol).isEqualTo(6.4)
        assertThat(current.proposedTargetMmol).isEqualTo(5.8)
        assertThat(waiting.availability).isEqualTo(TargetManagerLiveStatusAvailabilityUi.WAITING)
        assertThat(waiting.currentTargetMmol).isNull()
        assertThat(stale.availability).isEqualTo(TargetManagerLiveStatusAvailabilityUi.STALE)
        assertThat(stale.proposedTargetMmol).isNull()
        assertThat(unavailable.availability).isEqualTo(TargetManagerLiveStatusAvailabilityUi.UNAVAILABLE)
    }

    @Test
    fun targetManagerOffShowsAuthoritativePauseWithoutWaitingForLiveStatus() {
        val paused = mapTargetManagerLiveStatusForUi(null, "OFF", true, 9L, 1_800_000_000_000L)
        assertThat(paused.availability.name).isEqualTo("PAUSED")
        assertThat(paused.timestamp).isNull()
        assertThat(paused.currentTargetMmol).isNull()
        assertThat(paused.proposedTargetMmol).isNull()
    }

    @Test
    fun targetSchedulePresentation_isSharedByOverviewAndSettings() {
        val schedule = BaseTargetSchedule(
            revision = 7L,
            defaultTargetMmol = 6.0,
            autoEnabled = true,
            intervals = listOf(
                BaseTargetInterval(
                    id = "morning",
                    startMinuteOfDay = 6 * 60,
                    endMinuteOfDay = 9 * 60,
                    targetMmol = 6.2
                )
            )
        )
        val presentation = BaseTargetSchedulePresentation(
            schedule = schedule,
            effectiveTargetMmol = 5.8,
            autoDeltaMmol = -0.4,
            autoState = CircadianAutoState.ACTIVE,
            autoReason = "circadian_history"
        )
        val state = state {
            baseTargetMmol = 6.0
            latestGlucoseMmol = 6.1
            latestDataAgeMinutes = 1
        }

        val overview = state.toOverviewUiState(
            isProMode = false,
            baseTargetPresentation = presentation
        )
        val settings = state.toSettingsUiState(
            verboseLogsEnabled = false,
            proModeEnabled = false,
            baseTargetPresentation = presentation
        )

        assertThat(overview.baseTargetSchedule).isSameInstanceAs(schedule)
        assertThat(settings.baseTargetSchedule).isSameInstanceAs(schedule)
        assertThat(overview.effectiveBaseTargetMmol).isEqualTo(5.8)
        assertThat(settings.effectiveBaseTargetMmol).isEqualTo(5.8)
        assertThat(overview.baseTargetAutoDeltaMmol).isEqualTo(-0.4)
        assertThat(settings.baseTargetAutoDeltaMmol).isEqualTo(-0.4)
        assertThat(overview.baseTargetAutoState).isEqualTo(CircadianAutoState.ACTIVE)
        assertThat(settings.baseTargetAutoState).isEqualTo(CircadianAutoState.ACTIVE)
        assertThat(overview.baseTargetAutoReason).isEqualTo("circadian_history")
        assertThat(settings.baseTargetAutoReason).isEqualTo("circadian_history")
    }

    @Test
    fun settingsMapping_preservesRuntimeIsfSourceSelection() {
        val state = state {
            isfRuntimeSourcePreference = "AAPS"
        }

        val settings = state.toSettingsUiState(
            verboseLogsEnabled = false,
            proModeEnabled = false
        )

        assertThat(settings.isfRuntimeSourcePreference).isEqualTo("AAPS")
    }

    @Test
    fun settingsMappingKeepsAcceptedSourceWhileApplyIsPendingAndDisablesSourceControls() {
        val settings = state {
            isfRuntimeSourcePreference = "AAPS"
            crRuntimeSourcePreference = "EVIDENCE"
            sensitivitySourceApplying = true
            sensitivitySourceApplyError = null
        }.toSettingsUiState(verboseLogsEnabled = false, proModeEnabled = false)

        assertThat(settings.isfRuntimeSourcePreference).isEqualTo("AAPS")
        assertThat(settings.crRuntimeSourcePreference).isEqualTo("EVIDENCE")
        assertThat(settings.sensitivitySourceApplying).isTrue()
        assertThat(sensitivitySourceControlsEnabled(settings)).isFalse()
    }

    @Test
    fun overviewNeverUsesLooseCalculatedSensitivityWhenAcceptedTupleIsUnavailable() {
        val ui = state {
            latestGlucoseMmol = 6.0
            latestDataAgeMinutes = 1
            profileCalculatedIsf = 4.5
            profileCalculatedCr = 26.0
            isfRuntimeSourcePreference = "UNAVAILABLE"
            crRuntimeSourcePreference = "UNAVAILABLE"
            isfRuntimeSelected = null
            crRuntimeSelected = null
            sensitivitySourceApplyError = "accepted cycle failed"
        }.toOverviewUiState(isProMode = false)

        assertThat(ui.currentIsfMmolPerUnit).isNull()
        assertThat(ui.currentCrGramsPerUnit).isNull()
        assertThat(ui.sensitivitySourceApplyError).isEqualTo("accepted cycle failed")
    }

    @Test
    fun overviewRejectsLooseRuntimeTelemetryWhenAcceptedSnapshotIsAbsent() {
        val ui = state {
            latestGlucoseMmol = 6.0
            latestDataAgeMinutes = 1
            isfRuntimeSourcePreference = "AAPS"
            crRuntimeSourcePreference = "EVIDENCE"
            isfRuntimeResolved = 1.0
            crRuntimeResolved = 2.0
            isfRuntimeSelected = 2.4
            crRuntimeSelected = 10.8
            isfRuntimeAaps = 2.4
            crRuntimeAaps = 9.0
            isfRuntimeEvidence = 4.2
            crRuntimeEvidence = 11.0
            isfCrRealtimeConfidence = 0.91
            forecast5m = 6.1
            forecast30m = 6.5
            forecast60m = 6.8
        }.toOverviewUiState(
            isProMode = false,
            authoritativeIsfSource = "AAPS",
            authoritativeCrSource = "EVIDENCE"
        )

        assertThat(ui.currentIsfMmolPerUnit).isNull()
        assertThat(ui.currentCrGramsPerUnit).isNull()
        assertThat(ui.isfRuntime.availability).isEqualTo(MetricRuntimeAvailabilityUi.UNAVAILABLE)
        assertThat(ui.crRuntime.availability).isEqualTo(MetricRuntimeAvailabilityUi.UNAVAILABLE)
        assertThat(ui.isfRuntime.actualResolvedSource).isNull()
        assertThat(ui.crRuntime.actualResolvedSource).isNull()
        assertThat(ui.isfRuntime.candidates).isEmpty()
        assertThat(ui.crRuntime.candidates).isEmpty()
        assertThat(ui.horizons).isEmpty()
        assertThat(ui.chart.futurePath).isEmpty()
        assertThat(ui.chart.futureCi).isEmpty()
    }

    @Test
    fun overviewShowsAuthoritativeSelectionsButNoValuesWhileAcceptedTupleIsUnavailable() {
        val ui = state {
            latestGlucoseMmol = 6.0
            latestDataAgeMinutes = 1
            isfRuntimeSourcePreference = "UNAVAILABLE"
            crRuntimeSourcePreference = "UNAVAILABLE"
            isfRuntimeSelected = null
            crRuntimeSelected = null
            sensitivitySourceApplying = true
            sensitivitySourcePendingMetric = "ISF"
            sensitivitySourcePendingValue = "AAPS"
            sensitivitySourceApplyError = "accepted forecast unavailable"
        }.toOverviewUiState(
            isProMode = false,
            authoritativeIsfSource = "AAPS",
            authoritativeCrSource = "COPILOT"
        )

        assertThat(ui.isfRuntime.requested).isEqualTo("AAPS")
        assertThat(ui.crRuntime.requested).isEqualTo("COPILOT")
        assertThat(ui.currentIsfMmolPerUnit).isNull()
        assertThat(ui.currentCrGramsPerUnit).isNull()
        assertThat(ui.sensitivitySourceApplying).isTrue()
        assertThat(ui.sensitivitySourcePendingValue).isEqualTo("AAPS")
        assertThat(ui.sensitivitySourceApplyError).isEqualTo("accepted forecast unavailable")
    }

    @Test
    fun overviewHidesOldAcceptedValuesWhenAuthoritativeSourcesAdvanceFirst() {
        val ui = state {
            latestGlucoseMmol = 6.0
            latestDataAgeMinutes = 1
            isfRuntimeSourcePreference = "COPILOT"
            crRuntimeSourcePreference = "COPILOT"
            isfRuntimeResolved = 3.0
            crRuntimeResolved = 3.0
            isfRuntimeSelected = 3.4
            crRuntimeSelected = 11.0
            isfRuntimeAaps = 3.1
            crRuntimeAaps = 10.0
            isfRuntimeEvidence = 3.2
            crRuntimeEvidence = 10.5
            isfCrRealtimeConfidence = 0.8
            forecast5m = 6.1
            forecast30m = 6.5
            forecast60m = 6.8
            uamRuntimeActive = true
            uamRuntimeState = "ACTIVE"
            uamRuntimeEquivalentCarbsGrams = 14.0
            uamRuntimeConfidence = 0.75
        }.toOverviewUiState(
            isProMode = false,
            authoritativeIsfSource = "AAPS",
            authoritativeCrSource = "EVIDENCE"
        )

        assertThat(ui.isfRuntime.requested).isEqualTo("AAPS")
        assertThat(ui.crRuntime.requested).isEqualTo("EVIDENCE")
        assertThat(ui.currentIsfMmolPerUnit).isNull()
        assertThat(ui.currentCrGramsPerUnit).isNull()
        assertThat(ui.isfRuntime.resolved).isEqualTo("NO_OVERRIDE")
        assertThat(ui.crRuntime.resolved).isEqualTo("NO_OVERRIDE")
        assertThat(ui.isfRuntime.aapsValue).isNull()
        assertThat(ui.crRuntime.evidenceValue).isNull()
        assertThat(ui.isfRuntime.confidence).isNull()
        assertThat(ui.crRuntime.confidence).isNull()
        assertThat(ui.horizons).isEmpty()
        assertThat(ui.uamActive).isFalse()
        assertThat(ui.inferredCarbsLast60g).isNull()
    }

    @Test
    fun settingsMapping_preservesAutoUamExportCap() {
        val settings = state {
            enableUamAutoExportCap = true
            uamAutoExportCapGrams = 9
        }.toSettingsUiState(
            verboseLogsEnabled = false,
            proModeEnabled = false
        )

        assertThat(settings.enableUamAutoExportCap).isTrue()
        assertThat(settings.uamAutoExportCapGrams).isEqualTo(9)
    }

    @Test
    fun overviewCobShowsImportedValueWithoutReplacingEffectiveClinicalInput() {
        val state = state {
            latestCobGrams = 18.8
            latestExternalCobGrams = 75.0
            latestExternalCobTimestamp = System.currentTimeMillis()
        }
        val ui = state.toOverviewUiState(isProMode = true)
        assertThat(ui.currentCobGrams).isEqualTo(75.0)
        assertThat(ui.telemetryChips.first { it.label == "COB" }.value).isEqualTo("75.0")
        assertThat(ui.telemetryChips.first { it.label == "COB effective" }.value).isEqualTo("18.8")
        assertThat(state.latestCobGrams).isEqualTo(18.8)
        val unavailable = state { latestCobGrams = 18.8 }.toOverviewUiState(isProMode = false)
        assertThat(unavailable.currentCobGrams).isNull()
    }

    @Test
    fun overviewDoesNotShowCobAfterItsFreshnessDeadline() {
        val now = 1_800_000_000_000L
        val state = state {
            latestExternalCobGrams = 20.0
            latestExternalCobTimestamp = now
            staleDataMaxMinutes = 10
        }
        assertThat(state.toOverviewUiState(isProMode = false, authoritativeNowTs = now + 600_000L).currentCobGrams)
            .isEqualTo(20.0)
        assertThat(state.toOverviewUiState(isProMode = false, authoritativeNowTs = now + 600_001L).currentCobGrams)
            .isNull()
    }

    @Test
    fun overviewMapping_mapsCoreMetricsTelemetryAndWarnings() {
        val accepted = acceptedCopilotSnapshot(isf = 3.4, cr = 22.0)
        val state = state {
            latestGlucoseMmol = 8.7
            rawGlucoseMmol = 8.5
            calibratedGlucoseMmol = 8.7
            correctedGlucoseMmol = 9.0
            glucoseDelta = 0.24
            latestDataAgeMinutes = 2
            staleDataMaxMinutes = 10
            glucoseCalibrationGain = 1.012
            glucoseCalibrationOffsetMmol = 0.22
            glucoseCalibrationConfidence = 0.74
            glucoseCalibrationModelType = "OFFSET"
            glucoseCalibrationStatus = "ACTIVE"
            glucoseCalibrationLastCheckAgeMinutes = 42.0
            latestIobUnits = 1.76
            latestCobGrams = 22.4
            latestExternalCobGrams = 22.4
            latestExternalCobTimestamp = accepted.timestamp
            latestActivityRatio = 1.16
            latestStepsCount = 1234.0
            sensorAgeHours = 278.0
            dailyReportSensorLagReplayBuckets = listOf(
                io.aaps.copilot.ui.DailyReportSensorLagReplayUi(
                    horizonMinutes = 30,
                    bucket = "10-12d",
                    sampleCount = 10,
                    rawMae = 0.62,
                    lagMae = 0.44,
                    maeImprovementMmol = 0.18,
                    rawBias = 0.05,
                    lagBias = 0.01
                ),
                io.aaps.copilot.ui.DailyReportSensorLagReplayUi(
                    horizonMinutes = 60,
                    bucket = "10-12d",
                    sampleCount = 12,
                    rawMae = 0.88,
                    lagMae = 0.70,
                    maeImprovementMmol = 0.18,
                    rawBias = -0.08,
                    lagBias = -0.03
                )
            )
            dailyReportSensorLagShadowBuckets = listOf(
                io.aaps.copilot.ui.DailyReportSensorLagShadowUi(
                    bucket = "10-12d",
                    sampleCount = 14,
                    ruleChangedRatePct = 12.0,
                    meanAbsTargetDeltaMmol = 0.24
                )
            )
            forecast5m = 8.9
            forecast5mCiLow = 8.5
            forecast5mCiHigh = 9.2
            forecast30m = 9.8
            forecast30mCiLow = 8.0
            forecast30mCiHigh = 10.7
            forecast60m = 10.2
            forecast60mCiLow = 9.0
            forecast60mCiHigh = 11.4
            isfRuntimeSourcePreference = "COPILOT"
            crRuntimeSourcePreference = "COPILOT"
            acceptedSensitivityRuntimeSnapshot = accepted
            forecastAcceptedGenerationTs = accepted.timestamp
            uamRuntimeActive = true
            uamRuntimeEquivalentCarbsGrams = 18.0
            uamRuntimeConfidence = 0.8
            uamRuntimeState = "ACTIVE"
            calculatedUci0Mmol5m = 0.19
            inferredUamCarbsGrams = 18.0
            lastAction = LastActionRowUi(
                type = "temp_target",
                status = "SENT",
                timestamp = 1_800_000_000_000L,
                tempTargetMmol = 5.2,
                durationMinutes = 30
            )
        }

        val ui = state.toOverviewUiState(
            isProMode = true,
            authoritativeSensitivitySettingsRevision = accepted.settingsRevision,
            authoritativeNowTs = accepted.timestamp
        )

        assertThat(ui.loadState).isEqualTo(ScreenLoadState.READY)
        assertThat(ui.isStale).isFalse()
        assertThat(ui.glucose).isEqualTo(8.7)
        assertThat(ui.rawGlucose).isEqualTo(8.5)
        assertThat(ui.calibratedGlucose).isEqualTo(8.7)
        assertThat(ui.calibrationGain).isEqualTo(1.012)
        assertThat(ui.calibrationOffsetMmol).isEqualTo(0.22)
        assertThat(ui.calibrationStatus).isEqualTo("ACTIVE")
        assertThat(ui.sensorLagRolloutVerdict).isEqualTo(
            SensorLagRolloutVerdictUi(
                status = "ACTIVE_CANDIDATE",
                bucket = "10-12d"
            )
        )
        assertThat(ui.horizons).hasSize(3)
        assertThat(ui.horizons.first { it.horizonMinutes == 30 }.warningWideCi).isTrue()
        assertThat(ui.telemetryChips).hasSize(5)
        assertThat(ui.telemetryChips.first { it.label == "IOB" }.value).isEqualTo("1.8")
        assertThat(ui.telemetryChips.first { it.label == "COB" }.value).isEqualTo("22.4")
        assertThat(ui.lastAction?.type).isEqualTo("temp_target")
        assertThat(ui.uamActive).isTrue()
        assertThat(ui.uci0Mmol5m).isEqualTo(0.19)
        assertThat(ui.uamModeLabel).isEqualTo("BOOST")
    }

    @Test
    fun overviewUsesEffectiveIobAsPrimaryAndShowsSignedComponentsOnlyInProDetails() {
        val state = state {
            latestGlucoseMmol = 6.0
            latestDataAgeMinutes = 1
            latestIobUnits = 0.4
            latestIobRealUnits = -0.5
            latestIobBolusUnits = 0.4
            latestIobBasalUnits = -0.9
            latestInsulinActivity = 0.012
            latestIobRuntimeSource = "AAPS_COMPONENTS"
            latestIobRuntimeConfidence = 1.0
        }

        val compact = state.toOverviewUiState(isProMode = false)
        val pro = state.toOverviewUiState(isProMode = true)

        assertThat(compact.currentIobUnits).isEqualTo(0.4)
        assertThat(compact.telemetryChips.first { it.label == "IOB" }.value).isEqualTo("0.4")
        assertThat(compact.telemetryChips.map { it.label }).doesNotContain("IOB net")
        assertThat(pro.telemetryChips.first { it.label == "IOB net" }.value).isEqualTo("-0.5")
        assertThat(pro.telemetryChips.first { it.label == "IOB bolus" }.value).isEqualTo("0.4")
        assertThat(pro.telemetryChips.first { it.label == "IOB basal" }.value).isEqualTo("-0.9")
        assertThat(pro.telemetryChips.first { it.label == "Insulin activity" }.value).isEqualTo("0.012")
        assertThat(pro.telemetryChips.first { it.label == "IOB source" }.value).contains("AAPS_COMPONENTS")
    }

    @Test
    fun overviewIobDetailsKeepFullSignedAtomicPacketAndDoNotInventMissingComponents() {
        val now = 1_800_000_120_000L
        val complete = state {
            latestIobUnits = 0.4
            latestIobRealUnits = -0.5
            latestIobBolusUnits = 0.4
            latestIobBasalUnits = -0.9
            latestInsulinActivity = 0.012
            latestIobRuntimeSource = "AAPS_COMPONENTS"
            latestIobRuntimeTimestamp = 1_800_000_000_000L
            latestIobRuntimeConfidence = 0.93
            latestIobEvidenceTimestamp = 1_799_999_940_000L
            latestIobTherapyCoverage = 0.87
            latestIobRuntimeFallbackReason = "aaps_components_partial"
        }.toOverviewUiState(isProMode = false, authoritativeNowTs = now)

        assertThat(complete.currentIobUnits).isEqualTo(0.4)
        assertThat(complete.iobDetails.effectivePositiveIobUnits).isEqualTo(0.4)
        assertThat(complete.iobDetails.signedNetIobUnits).isEqualTo(-0.5)
        assertThat(complete.iobDetails.bolusIobUnits).isEqualTo(0.4)
        assertThat(complete.iobDetails.basalIobUnits).isEqualTo(-0.9)
        assertThat(complete.iobDetails.insulinActivity).isEqualTo(0.012)
        assertThat(complete.iobDetails.actualSource).isEqualTo("AAPS_COMPONENTS")
        assertThat(complete.iobDetails.sampleTimestamp).isEqualTo(1_800_000_000_000L)
        assertThat(complete.iobDetails.sampleAgeMinutes).isEqualTo(2L)
        assertThat(complete.iobDetails.confidence).isEqualTo(0.93)
        assertThat(complete.iobDetails.evidenceTimestamp).isEqualTo(1_799_999_940_000L)
        assertThat(complete.iobDetails.therapyCoverage).isEqualTo(0.87)
        assertThat(complete.iobDetails.fallbackReason).isEqualTo("aaps_components_partial")

        val partial = state {
            latestIobUnits = 0.6
            latestIobRealUnits = 0.6
            latestIobRuntimeSource = "LOCAL_ESTIMATE"
        }.toOverviewUiState(isProMode = false, authoritativeNowTs = now)

        assertThat(partial.iobDetails.bolusIobUnits).isNull()
        assertThat(partial.iobDetails.basalIobUnits).isNull()
        assertThat(partial.iobDetails.insulinActivity).isNull()
        assertThat(partial.iobDetails.confidence).isNull()
        assertThat(partial.iobDetails.therapyCoverage).isNull()
    }

    @Test
    fun overviewUsesAcceptedSnapshotForEffectiveValuesActualSourcesAndFallbackIdentity() {
        val snapshot = acceptedFallbackSnapshot()
        val diagnostics = acceptedDiagnostics(snapshot)
        val now = snapshot.timestamp + 3 * 60_000L
        val ui = state {
            acceptedSensitivityRuntimeSnapshot = snapshot
            forecastAcceptedGenerationTs = snapshot.timestamp + 60_000L
            isfRuntimeSourcePreference = "AAPS"
            crRuntimeSourcePreference = "EVIDENCE"
            isfRuntimeSelected = 17.0
            crRuntimeSelected = 55.0
            isfRuntimeAaps = 9.9
            crRuntimeAaps = 44.0
            isfRuntimeEvidence = 8.8
            crRuntimeEvidence = 33.0
            isfCrRealtimeConfidence = 0.01
        }.toOverviewUiState(
            isProMode = false,
            authoritativeIsfSource = "AAPS",
            authoritativeCrSource = "EVIDENCE",
            authoritativeSensitivitySettingsRevision = snapshot.settingsRevision,
            acceptedCandidateDiagnostics = diagnostics,
            authoritativeNowTs = now
        )

        assertThat(ui.currentIsfMmolPerUnit).isEqualTo(snapshot.isf.effective)
        assertThat(ui.currentCrGramsPerUnit).isEqualTo(snapshot.cr.effective)
        assertThat(ui.isfRuntime.actualResolvedSource).isEqualTo("COPILOT")
        assertThat(ui.crRuntime.actualResolvedSource).isEqualTo("EVIDENCE")
        assertThat(ui.isfRuntime.fallbackPath).containsExactly("AAPS", "EVIDENCE", "COPILOT").inOrder()
        assertThat(ui.isfRuntime.fallbackReason)
            .isEqualTo("aaps_missing;evidence_quality_failed")
        assertThat(ui.isfRuntime.acceptedSettingsRevision).isEqualTo(42L)
        assertThat(ui.isfRuntime.acceptedCycleId).isEqualTo("accepted-overview-cycle")
        assertThat(ui.isfRuntime.acceptedTimestamp).isEqualTo(snapshot.timestamp + 60_000L)
        assertThat(ui.isfRuntime.acceptedAgeMinutes).isEqualTo(2L)
        assertThat(ui.isfRuntime.acceptedFresh).isTrue()
        assertThat(ui.isfRuntime.confidence).isEqualTo(snapshot.isf.confidence)
        assertThat(ui.isfRuntime.candidates.single { it.source == "AAPS" }.value).isNull()
        assertThat(ui.isfRuntime.candidates.single { it.source == "AAPS" }.diagnosticsAvailable).isTrue()
        assertThat(ui.isfRuntime.candidates.single { it.source == "AAPS" }.freshAtDecision).isNull()
        assertThat(ui.isfRuntime.candidates.single { it.source == "AAPS" }.unavailableReason)
            .isEqualTo("missing")
        assertThat(ui.isfRuntime.candidates.single { it.source == "EVIDENCE" }.sampleCount)
            .isEqualTo(18)
        assertThat(ui.isfRuntime.candidates.single { it.source == "EVIDENCE" }.coverage)
            .isEqualTo(0.72)
        assertThat(ui.isfRuntime.candidates.single { it.source == "COPILOT" }.value)
            .isEqualTo(snapshot.isf.rawCopilot)
    }

    @Test
    fun overviewNeverBorrowsLooseCandidateMetadataIntoAnAcceptedIdentity() {
        val snapshot = acceptedFallbackSnapshot()
        val mismatched = acceptedDiagnostics(snapshot).copy(forecastCycleId = "newer-loose-cycle")
        val ui = state {
            acceptedSensitivityRuntimeSnapshot = snapshot
            forecastAcceptedGenerationTs = snapshot.timestamp
            isfRuntimeSourcePreference = "AAPS"
            crRuntimeSourcePreference = "EVIDENCE"
            isfRuntimeAaps = 2.2
            isfRuntimeEvidence = 4.4
            isfCrRealtimeConfidence = 0.99
        }.toOverviewUiState(
            isProMode = false,
            authoritativeIsfSource = "AAPS",
            authoritativeCrSource = "EVIDENCE",
            authoritativeSensitivitySettingsRevision = snapshot.settingsRevision,
            acceptedCandidateDiagnostics = mismatched,
            authoritativeNowTs = snapshot.timestamp + 60_000L
        )

        assertThat(ui.isfRuntime.aapsValue).isNull()
        assertThat(ui.isfRuntime.evidenceValue).isEqualTo(snapshot.isf.rawEvidence)
        assertThat(ui.isfRuntime.confidence).isEqualTo(snapshot.isf.confidence)
        assertThat(ui.isfRuntime.candidates.all { !it.diagnosticsAvailable }).isTrue()
        assertThat(ui.isfRuntime.candidates.all { it.sampleCount == null }).isTrue()
        assertThat(ui.isfRuntime.candidates.all { it.unavailableReason == null }).isTrue()
    }

    @Test
    fun sameSourcesWithNewerAuthoritativeRevisionHideTheEntireAcceptedOverviewTuple() {
        val snapshot = acceptedFallbackSnapshot()
        val diagnostics = acceptedDiagnostics(snapshot)
        val state = state {
            latestGlucoseMmol = 5.4
            latestDataAgeMinutes = 1
            acceptedSensitivityRuntimeSnapshot = snapshot
            forecastAcceptedGenerationTs = snapshot.timestamp
            isfRuntimeSourcePreference = "AAPS"
            crRuntimeSourcePreference = "EVIDENCE"
            forecast5m = 5.5
            forecast5mCiLow = 5.2
            forecast5mCiHigh = 5.8
            forecast30m = 6.0
            forecast30mCiLow = 5.4
            forecast30mCiHigh = 6.6
            forecast60m = 6.5
            forecast60mCiLow = 5.5
            forecast60mCiHigh = 7.5
            glucoseHistoryPoints = listOf(
                GlucoseHistoryRowUi(timestamp = snapshot.timestamp, valueMmol = 5.4)
            )
        }
        val accepted = state.toOverviewUiState(
            isProMode = false,
            authoritativeIsfSource = "AAPS",
            authoritativeCrSource = "EVIDENCE",
            authoritativeSensitivitySettingsRevision = snapshot.settingsRevision,
            acceptedCandidateDiagnostics = diagnostics,
            authoritativeNowTs = snapshot.timestamp + 60_000L
        )
        val revisionMismatch = state.toOverviewUiState(
            isProMode = false,
            authoritativeIsfSource = "AAPS",
            authoritativeCrSource = "EVIDENCE",
            authoritativeSensitivitySettingsRevision = snapshot.settingsRevision + 1L,
            acceptedCandidateDiagnostics = diagnostics,
            authoritativeNowTs = snapshot.timestamp + 60_000L
        )

        assertThat(accepted.currentIsfMmolPerUnit).isEqualTo(snapshot.isf.effective)
        assertThat(accepted.currentCrGramsPerUnit).isEqualTo(snapshot.cr.effective)
        assertThat(accepted.horizons).hasSize(3)
        assertThat(accepted.chart.futurePath).isNotEmpty()
        assertThat(accepted.chart.futureCi).isNotEmpty()
        assertThat(accepted.isfRuntime.candidates).hasSize(3)

        assertThat(revisionMismatch.currentIsfMmolPerUnit).isNull()
        assertThat(revisionMismatch.currentCrGramsPerUnit).isNull()
        assertThat(revisionMismatch.isfRuntime.availability)
            .isEqualTo(MetricRuntimeAvailabilityUi.UNAVAILABLE)
        assertThat(revisionMismatch.crRuntime.availability)
            .isEqualTo(MetricRuntimeAvailabilityUi.UNAVAILABLE)
        assertThat(revisionMismatch.isfRuntime.candidates).isEmpty()
        assertThat(revisionMismatch.crRuntime.candidates).isEmpty()
        assertThat(revisionMismatch.horizons).isEmpty()
        assertThat(revisionMismatch.chart.futurePath).isEmpty()
        assertThat(revisionMismatch.chart.futureCi).isEmpty()
    }

    @Test
    fun acceptedOverviewTupleExpiresOneMillisecondAfterTheInclusiveFreshnessBoundary() {
        val snapshot = acceptedFallbackSnapshot()
        val diagnostics = acceptedDiagnostics(snapshot)
        val generationTs = snapshot.timestamp
        val state = state {
            latestGlucoseMmol = 5.4
            acceptedSensitivityRuntimeSnapshot = snapshot
            forecastAcceptedGenerationTs = generationTs
            isfRuntimeSourcePreference = "AAPS"
            crRuntimeSourcePreference = "EVIDENCE"
            forecast5m = 5.5
            forecast5mCiLow = 5.2
            forecast5mCiHigh = 5.8
            forecast30m = 6.0
            forecast30mCiLow = 5.4
            forecast30mCiHigh = 6.6
            forecast60m = 6.5
            forecast60mCiLow = 5.5
            forecast60mCiHigh = 7.5
            glucoseHistoryPoints = listOf(
                GlucoseHistoryRowUi(timestamp = generationTs, valueMmol = 5.4)
            )
        }
        fun overviewAt(nowTs: Long) = state.toOverviewUiState(
            isProMode = false,
            authoritativeIsfSource = "AAPS",
            authoritativeCrSource = "EVIDENCE",
            authoritativeSensitivitySettingsRevision = snapshot.settingsRevision,
            acceptedCandidateDiagnostics = diagnostics,
            authoritativeNowTs = nowTs
        )

        val inclusive = overviewAt(generationTs + AcceptedSensitivityTupleFreshness.MAX_AGE_MS)
        val expired = overviewAt(generationTs + AcceptedSensitivityTupleFreshness.MAX_AGE_MS + 1L)

        assertThat(inclusive.currentIsfMmolPerUnit).isEqualTo(snapshot.isf.effective)
        assertThat(inclusive.currentCrGramsPerUnit).isEqualTo(snapshot.cr.effective)
        assertThat(inclusive.isfRuntime.availability).isEqualTo(MetricRuntimeAvailabilityUi.AVAILABLE)
        assertThat(inclusive.crRuntime.availability).isEqualTo(MetricRuntimeAvailabilityUi.AVAILABLE)
        assertThat(inclusive.isfRuntime.candidates).hasSize(3)
        assertThat(inclusive.crRuntime.candidates).hasSize(3)
        assertThat(inclusive.horizons).hasSize(3)
        assertThat(inclusive.chart.futurePath).isNotEmpty()
        assertThat(inclusive.chart.futureCi).isNotEmpty()

        assertThat(expired.currentIsfMmolPerUnit).isNull()
        assertThat(expired.currentCrGramsPerUnit).isNull()
        assertThat(expired.isfRuntime.availability).isEqualTo(MetricRuntimeAvailabilityUi.UNAVAILABLE)
        assertThat(expired.crRuntime.availability).isEqualTo(MetricRuntimeAvailabilityUi.UNAVAILABLE)
        assertThat(expired.isfRuntime.candidates).isEmpty()
        assertThat(expired.crRuntime.candidates).isEmpty()
        assertThat(expired.horizons).isEmpty()
        assertThat(expired.chart.futurePath).isEmpty()
        assertThat(expired.chart.futureCi).isEmpty()
    }

    @Test
    fun applyingAndRevisionMismatchNeverShowOrRelabelOldAcceptedValues() {
        val snapshot = acceptedFallbackSnapshot()
        val accepted = state {
            acceptedSensitivityRuntimeSnapshot = snapshot
            forecastAcceptedGenerationTs = snapshot.timestamp
            isfRuntimeSourcePreference = "AAPS"
            crRuntimeSourcePreference = "EVIDENCE"
            forecast5m = 5.5
            forecast30m = 6.0
            forecast60m = 6.5
        }.toOverviewUiState(
            isProMode = false,
            authoritativeIsfSource = "AAPS",
            authoritativeCrSource = "EVIDENCE",
            authoritativeSensitivitySettingsRevision = snapshot.settingsRevision,
            authoritativeNowTs = snapshot.timestamp + 60_000L
        )
        val applying = accepted.withSensitivityApplyPresentation(
            applying = true,
            pendingMetric = "ISF",
            pendingValue = "EVIDENCE",
            error = null
        )

        assertThat(applying.currentIsfMmolPerUnit).isNull()
        assertThat(applying.currentCrGramsPerUnit).isNull()
        assertThat(applying.horizons).isEmpty()
        assertThat(applying.chart.futurePath).isEmpty()
        assertThat(applying.isfRuntime.requested).isEqualTo("EVIDENCE")
        assertThat(applying.isfRuntime.availability).isEqualTo(MetricRuntimeAvailabilityUi.APPLYING)
        assertThat(applying.crRuntime.availability).isEqualTo(MetricRuntimeAvailabilityUi.UNAVAILABLE)
        assertThat(applying.isfRuntime.actualResolvedSource).isNull()
        assertThat(applying.crRuntime.actualResolvedSource).isNull()

        val revisionMismatch = state {
            acceptedSensitivityRuntimeSnapshot = snapshot
            forecastAcceptedGenerationTs = snapshot.timestamp
            isfRuntimeSourcePreference = "AAPS"
            crRuntimeSourcePreference = "EVIDENCE"
            isfRuntimeSelected = snapshot.isf.effective
            crRuntimeSelected = snapshot.cr.effective
        }.toOverviewUiState(
            isProMode = false,
            authoritativeIsfSource = "COPILOT",
            authoritativeCrSource = "EVIDENCE",
            authoritativeSensitivitySettingsRevision = snapshot.settingsRevision,
            authoritativeNowTs = snapshot.timestamp + 60_000L
        )

        assertThat(revisionMismatch.currentIsfMmolPerUnit).isNull()
        assertThat(revisionMismatch.currentCrGramsPerUnit).isNull()
        assertThat(revisionMismatch.isfRuntime.availability)
            .isEqualTo(MetricRuntimeAvailabilityUi.UNAVAILABLE)
        assertThat(revisionMismatch.isfRuntime.actualResolvedSource).isNull()
    }

    @Test
    fun overviewMapping_buildsClinicalChartAndCurrentModelValues() {
        val now = 1_800_000_000_000L
        val accepted = acceptedCopilotSnapshot(isf = 3.3, cr = 24.5, timestamp = now)
        val state = state {
            latestGlucoseMmol = 5.6
            latestDataAgeMinutes = 1
            staleDataMaxMinutes = 10
            glucoseDelta = 0.0
            latestIobUnits = 2.1
            latestCobGrams = 43.5
            latestExternalCobGrams = 43.5
            latestExternalCobTimestamp = now
            baseTargetMmol = 5.5
            safetyMinTargetMmol = 4.0
            safetyMaxTargetMmol = 10.0
            forecast5m = 5.3
            forecast5mCiLow = 4.9
            forecast5mCiHigh = 5.7
            forecast30m = 3.5
            forecast30mCiLow = 2.2
            forecast30mCiHigh = 6.3
            forecast60m = 2.8
            forecast60mCiLow = 1.9
            forecast60mCiHigh = 6.5
            isfRuntimeSourcePreference = "COPILOT"
            crRuntimeSourcePreference = "COPILOT"
            acceptedSensitivityRuntimeSnapshot = accepted
            forecastAcceptedGenerationTs = accepted.timestamp
            uamRuntimeActive = true
            uamRuntimeEquivalentCarbsGrams = 0.0
            uamRuntimeConfidence = 0.8
            uamRuntimeState = "ACTIVE"
            profileCalculatedIsf = 3.3
            profileCalculatedCr = 24.5
            glucoseHistoryPoints = listOf(
                GlucoseHistoryRowUi(timestamp = now - 4 * 60 * 60_000L, valueMmol = 5.8),
                GlucoseHistoryRowUi(timestamp = now - 2 * 60 * 60_000L, valueMmol = 5.7),
                GlucoseHistoryRowUi(timestamp = now, valueMmol = 5.6)
            )
        }

        val ui = state.toOverviewUiState(
            isProMode = false,
            authoritativeSensitivitySettingsRevision = accepted.settingsRevision,
            authoritativeNowTs = accepted.timestamp
        )

        assertThat(ui.chart.historyPoints).hasSize(3)
        assertThat(ui.chart.futurePath).isNotEmpty()
        assertThat(ui.chart.futureCi).isNotEmpty()
        assertThat(ui.chart.displayRangeLowMmol).isEqualTo(3.9)
        assertThat(ui.chart.displayRangeHighMmol).isEqualTo(6.7)
        assertThat(ui.currentIobUnits).isEqualTo(2.1)
        assertThat(ui.currentCobGrams).isEqualTo(43.5)
        assertThat(ui.currentIsfMmolPerUnit).isEqualTo(accepted.isf.effective)
        assertThat(ui.currentCrGramsPerUnit).isEqualTo(accepted.cr.effective)
        assertThat(ui.calculatedUamCarbsGrams).isEqualTo(0.0)
        assertThat(ui.baseTargetMmol).isEqualTo(5.5)
    }

    @Test
    fun overviewMapping_selectedAapsValuesWinOverCalculatedCopilotValues() {
        val accepted = acceptedAapsSnapshot(isf = 2.47, cr = 10.0)
        val state = state {
            latestGlucoseMmol = 5.6
            latestDataAgeMinutes = 1
            profileCalculatedIsf = 4.5
            profileCalculatedCr = 26.1
            isfRuntimeSourcePreference = "AAPS"
            crRuntimeSourcePreference = "AAPS"
            isfRuntimeSelected = 2.47
            crRuntimeSelected = 10.0
            isfRuntimeAaps = 2.47
            crRuntimeAaps = 10.0
            isfRuntimeResolved = 1.0
            crRuntimeResolved = 1.0
            acceptedSensitivityRuntimeSnapshot = accepted
            forecastAcceptedGenerationTs = accepted.timestamp
        }

        val ui = state.toOverviewUiState(
            isProMode = false,
            authoritativeSensitivitySettingsRevision = accepted.settingsRevision,
            authoritativeNowTs = accepted.timestamp
        )

        assertThat(ui.currentIsfMmolPerUnit).isEqualTo(2.47)
        assertThat(ui.currentCrGramsPerUnit).isEqualTo(10.0)
        assertThat(ui.isfRuntime.requested).isEqualTo("AAPS")
        assertThat(ui.crRuntime.requested).isEqualTo("AAPS")
    }

    @Test
    fun overviewMapping_copilotResolutionIgnoresStalePersistedAapsOverride() {
        val accepted = acceptedCopilotSnapshot(isf = 3.6, cr = 12.0)
        val state = state {
            latestGlucoseMmol = 5.6
            latestDataAgeMinutes = 1
            profileCalculatedIsf = 4.5
            profileCalculatedCr = 26.1
            isfRuntimeSourcePreference = "COPILOT"
            crRuntimeSourcePreference = "COPILOT"
            isfRuntimeResolved = 3.0
            crRuntimeResolved = 3.0
            isfRuntimeSelected = 2.47
            crRuntimeSelected = 10.0
            isfRuntimeAaps = 2.47
            crRuntimeAaps = 10.0
            isfRuntimeFallbackActive = true
            crRuntimeFallbackActive = true
            acceptedSensitivityRuntimeSnapshot = accepted
            forecastAcceptedGenerationTs = accepted.timestamp
        }

        val ui = state.toOverviewUiState(
            isProMode = false,
            authoritativeSensitivitySettingsRevision = accepted.settingsRevision,
            authoritativeNowTs = accepted.timestamp
        )

        assertThat(ui.currentIsfMmolPerUnit).isEqualTo(accepted.isf.effective)
        assertThat(ui.currentCrGramsPerUnit).isEqualTo(accepted.cr.effective)
        assertThat(ui.isfRuntime.selectedValue).isEqualTo(accepted.isf.effective)
        assertThat(ui.crRuntime.selectedValue).isEqualTo(accepted.cr.effective)
        assertThat(ui.isfRuntime.resolved).isEqualTo("COPILOT_NATIVE")
        assertThat(ui.crRuntime.resolved).isEqualTo("COPILOT_NATIVE")
        assertThat(ui.isfRuntime.fallbackActive).isFalse()
        assertThat(ui.crRuntime.fallbackActive).isFalse()
    }

    @Test
    fun overviewMapping_fallbackResolutionMatchesRequestedSourceContract() {
        val accepted = acceptedFallbackSnapshot().copy(
            isf = acceptedFallbackSnapshot().isf.copy(
                resolved = SensitivityResolvedSource.EVIDENCE_BLEND,
                blended = 3.8,
                effective = 3.8,
                fallbackReason = "aaps_missing"
            ),
            cr = acceptedFallbackSnapshot().cr.copy(
                resolved = SensitivityResolvedSource.AAPS,
                rawAaps = 10.0,
                effective = 10.0,
                fallbackReason = "evidence_quality_gate_failed;aaps_fallback_selected"
            )
        )
        val state = state {
            latestGlucoseMmol = 5.6
            latestDataAgeMinutes = 1
            profileCalculatedIsf = 4.5
            profileCalculatedCr = 26.1
            isfRuntimeSourcePreference = "AAPS"
            crRuntimeSourcePreference = "EVIDENCE"
            isfRuntimeResolved = 2.0
            crRuntimeResolved = 3.0
            isfRuntimeSelected = 3.8
            crRuntimeSelected = 10.0
            isfRuntimeFallbackActive = true
            crRuntimeFallbackActive = true
            acceptedSensitivityRuntimeSnapshot = accepted
            forecastAcceptedGenerationTs = accepted.timestamp
        }

        val ui = state.toOverviewUiState(
            isProMode = false,
            authoritativeSensitivitySettingsRevision = accepted.settingsRevision,
            authoritativeNowTs = accepted.timestamp
        )

        assertThat(ui.currentIsfMmolPerUnit).isEqualTo(3.8)
        assertThat(ui.currentCrGramsPerUnit).isEqualTo(10.0)
        assertThat(ui.isfRuntime.selectedValue).isEqualTo(3.8)
        assertThat(ui.crRuntime.selectedValue).isEqualTo(10.0)
        assertThat(ui.isfRuntime.resolved).isEqualTo("EVIDENCE_BLEND")
        assertThat(ui.crRuntime.resolved).isEqualTo("AAPS")
        assertThat(ui.isfRuntime.actualResolvedSource).isEqualTo("EVIDENCE")
        assertThat(ui.crRuntime.actualResolvedSource).isEqualTo("AAPS")
        assertThat(ui.crRuntime.fallbackPath).containsExactly("EVIDENCE", "AAPS").inOrder()
        assertThat(ui.crRuntime.fallbackReason)
            .isEqualTo("evidence_quality_gate_failed;aaps_fallback_selected")
        assertThat(ui.isfRuntime.fallbackActive).isTrue()
        assertThat(ui.crRuntime.fallbackActive).isTrue()
    }

    @Test
    fun overviewMapping_copilotPreferenceImmediatelyIgnoresPreviousAapsResolution() {
        val state = state {
            latestGlucoseMmol = 5.6
            latestDataAgeMinutes = 1
            profileCalculatedIsf = 4.5
            profileCalculatedCr = 26.1
            isfRuntimeSourcePreference = "COPILOT"
            crRuntimeSourcePreference = "COPILOT"
            isfRuntimeResolved = 1.0
            crRuntimeResolved = 1.0
            isfRuntimeSelected = 2.47
            crRuntimeSelected = 10.0
            isfRuntimeAaps = 2.47
            crRuntimeAaps = 10.0
            isfRuntimeFallbackActive = true
            crRuntimeFallbackActive = true
        }

        val ui = state.toOverviewUiState(isProMode = false)

        assertThat(ui.currentIsfMmolPerUnit).isNull()
        assertThat(ui.currentCrGramsPerUnit).isNull()
        assertThat(ui.isfRuntime.selectedValue).isNull()
        assertThat(ui.crRuntime.selectedValue).isNull()
        assertThat(ui.isfRuntime.resolved).isEqualTo("NO_OVERRIDE")
        assertThat(ui.crRuntime.resolved).isEqualTo("NO_OVERRIDE")
        assertThat(ui.isfRuntime.fallbackActive).isFalse()
        assertThat(ui.crRuntime.fallbackActive).isFalse()
    }

    @Test
    fun overviewMapping_doesNotConvertMissingMetricsToZero() {
        val state = state {
            latestGlucoseMmol = 5.6
            latestDataAgeMinutes = 1
        }

        val ui = state.toOverviewUiState(isProMode = false)

        assertThat(ui.currentIobUnits).isNull()
        assertThat(ui.currentCobGrams).isNull()
        assertThat(ui.currentIsfMmolPerUnit).isNull()
        assertThat(ui.currentCrGramsPerUnit).isNull()
        assertThat(ui.calculatedUamCarbsGrams).isNull()
    }

    @Test
    fun overviewMapping_activeRuntime15gOverridesStaleLegacy60g() {
        val state = state {
            latestGlucoseMmol = 5.6
            latestDataAgeMinutes = 1
            isfCrRealtimeIsfBase = 2.52
            isfCrRealtimeCrBase = 10.0
            isfRuntimeSourcePreference = "COPILOT"
            crRuntimeSourcePreference = "COPILOT"
            uamRuntimeActive = true
            uamRuntimeEquivalentCarbsGrams = 15.0
            uamRuntimeConfidence = 0.82
            uamRuntimeState = "ACTIVE"
            calculatedUamActive = false
            calculatedUamCarbsGrams = 0.0
            calculatedUamConfidence = 0.0
            inferredUamActive = true
            inferredUamCarbsGrams = 60.0
            inferredUamConfidence = 0.42
        }

        val ui = state.toOverviewUiState(isProMode = false)

        assertThat(ui.currentIsfMmolPerUnit).isNull()
        assertThat(ui.currentCrGramsPerUnit).isNull()
        assertThat(ui.calculatedUamCarbsGrams).isEqualTo(15.0)
        assertThat(ui.calculatedUamConfidence).isEqualTo(0.82)
        assertThat(ui.uamActive).isTrue()
    }

    @Test
    fun overviewMapping_inactiveRuntimeClearsStaleLegacy60g() {
        val state = state {
            latestGlucoseMmol = 5.6
            latestDataAgeMinutes = 1
            uamRuntimeActive = false
            uamRuntimeEquivalentCarbsGrams = 0.0
            uamRuntimeConfidence = 0.9
            uamRuntimeState = "INACTIVE"
            calculatedUamActive = true
            calculatedUamCarbsGrams = 5.77
            calculatedUamConfidence = 0.805
            inferredUamActive = true
            inferredUamCarbsGrams = 60.0
            inferredUamConfidence = 0.0
        }

        val ui = state.toOverviewUiState(isProMode = false)

        assertThat(ui.calculatedUamCarbsGrams).isNull()
        assertThat(ui.uamActive).isFalse()
    }

    @Test
    fun overviewMapping_missingRuntimeFailsClosedWithoutLegacyFallback() {
        val state = state {
            latestGlucoseMmol = 5.6
            latestDataAgeMinutes = 1
            calculatedUamActive = true
            calculatedUamCarbsGrams = 5.77
            calculatedUamConfidence = 0.805
            inferredUamActive = false
            inferredUamCarbsGrams = 0.0
            inferredUamConfidence = 0.0
        }

        val ui = state.toOverviewUiState(isProMode = false)

        assertThat(ui.calculatedUamCarbsGrams).isNull()
        assertThat(ui.uamActive).isFalse()
    }

    @Test
    fun uamMapping_activeRuntime15gOverridesStaleLegacy60g() {
        val ui = state {
            latestDataAgeMinutes = 1
            enableUamExportToAaps = true
            uamExportMode = "CONFIRMED_ONLY"
            dryRunExport = false
            uamRuntimeActive = true
            uamRuntimeEquivalentCarbsGrams = 15.0
            uamRuntimeConfidence = 0.82
            uamRuntimeState = "DECAYING"
            calculatedUamActive = true
            calculatedUamCarbsGrams = 60.0
            calculatedUamConfidence = 0.99
            inferredUamActive = true
            inferredUamCarbsGrams = 60.0
            inferredUamConfidence = 0.99
        }.toUamUiState()

        assertThat(ui.calculatedActive).isTrue()
        assertThat(ui.calculatedCarbsGrams).isEqualTo(15.0)
        assertThat(ui.calculatedConfidence).isEqualTo(0.82)
        assertThat(ui.inferredActive).isNull()
        assertThat(ui.inferredCarbsGrams).isNull()
        assertThat(ui.uamExport.mode).isEqualTo("OBSERVE")
    }

    @Test
    fun uamExportControl_hidesDryRunAsObserveImplementationDetail() {
        val referenceTs = 1_800_000_000_000L
        val ui = state {
            enableUamExportToAaps = true
            uamExportMode = "INCREMENTAL"
            dryRunExport = true
            uamRuntimeActive = false
            uamRuntimeState = "BLOCKED"
            uamRuntimeReason = "dry_run"
            uamEventRows = listOf(uamEvent(updatedAt = referenceTs - 60_000L, reason = "dry run"))
        }.toUamExportControlUi(referenceTs = referenceTs)

        assertThat(ui.mode).isEqualTo("OBSERVE")
        assertThat(ui.runtimeStatus).isEqualTo(UamRuntimeStatusUi.OBSERVATION_ACTIVE)
        assertThat(ui.runtimeReasonStatus).isNull()
        assertThat(ui.exportBlockedStatus).isNull()
    }

    @Test
    fun uamExportControl_inferenceOffMakesPersistedAutoUnavailableAndEffectivelyOff() {
        val ui = state {
            enableUamInference = false
            enableUamExportToAaps = true
            uamExportMode = "INCREMENTAL"
            dryRunExport = false
        }.toUamExportControlUi()

        assertThat(ui.available).isFalse()
        assertThat(ui.mode).isEqualTo("OFF")
    }

    @Test
    fun uamExportControl_hidesStaleHistoricalEventReason() {
        val referenceTs = 1_800_000_000_000L
        val ui = state {
            enableUamExportToAaps = true
            uamExportMode = "INCREMENTAL"
            dryRunExport = false
            uamRuntimeActive = true
            uamRuntimeState = "ACTIVE"
            uamEventRows = listOf(
                uamEvent(
                    updatedAt = referenceTs - UAM_EXPORT_REASON_UI_TTL_MS - 1L,
                    reason = "manual_carbs_nearby"
                )
            )
        }.toUamExportControlUi(referenceTs = referenceTs)

        assertThat(ui.exportBlockedStatus).isNull()
    }

    @Test
    fun uamExportControl_showsFreshReasonFromLatestActiveEvent() {
        val referenceTs = 1_800_000_000_000L
        val ui = state {
            enableUamExportToAaps = true
            uamExportMode = "INCREMENTAL"
            dryRunExport = false
            uamRuntimeActive = true
            uamRuntimeState = "ACTIVE"
            uamEventRows = listOf(
                uamEvent(updatedAt = referenceTs - 2 * 60_000L, reason = "sensor_blocked"),
                uamEvent(updatedAt = referenceTs - 60_000L, reason = "manual_carbs_nearby")
            )
        }.toUamExportControlUi(referenceTs = referenceTs)

        assertThat(ui.exportBlockedStatus).isEqualTo(UamRuntimeStatusUi.MANUAL_CARBS)
    }

    @Test
    fun uamExportControl_hidesFreshEventReasonWhenCurrentUamIsInactive() {
        val referenceTs = 1_800_000_000_000L
        val ui = state {
            enableUamExportToAaps = true
            uamExportMode = "INCREMENTAL"
            dryRunExport = false
            uamRuntimeActive = false
            uamRuntimeState = "INACTIVE"
            uamEventRows = listOf(
                uamEvent(updatedAt = referenceTs - 60_000L, reason = "manual_carbs_nearby")
            )
        }.toUamExportControlUi(referenceTs = referenceTs)

        assertThat(ui.exportBlockedStatus).isNull()
    }

    @Test
    fun uamRuntimeStatusMapping_coversKnownClinicalAndDeliveryCategories() {
        val expected = mapOf(
            "sensor_blocked" to UamRuntimeStatusUi.SENSOR_BLOCKED,
            "source_snapshot_stale" to UamRuntimeStatusUi.DATA_STALE,
            "confidence_below_initial_min" to UamRuntimeStatusUi.CONFIDENCE_LOW,
            "lower_bound_not_stable" to UamRuntimeStatusUi.SIGNAL_UNSTABLE,
            "forecast_diagnostics_missing" to UamRuntimeStatusUi.FORECAST_UNAVAILABLE,
            "forecast_below_4" to UamRuntimeStatusUi.LOW_GLUCOSE_RISK,
            "effective_cob_above_max" to UamRuntimeStatusUi.COB_ACTIVE,
            "manual_carbs_nearby" to UamRuntimeStatusUi.MANUAL_CARBS,
            "therapy_coverage_below_min" to UamRuntimeStatusUi.THERAPY_COVERAGE_LOW,
            "reconciliation_failed" to UamRuntimeStatusUi.RECONCILIATION_REQUIRED,
            "post_outcome_unknown" to UamRuntimeStatusUi.OUTCOME_UNKNOWN,
            "reservation_failed" to UamRuntimeStatusUi.RESERVATION_PENDING,
            "rate_limited" to UamRuntimeStatusUi.RATE_LIMITED,
            "write_interval_under_10m" to UamRuntimeStatusUi.INTERVAL_WAIT,
            "episode_capacity_exhausted" to UamRuntimeStatusUi.CAPACITY_REACHED
        )

        expected.forEach { (code, status) ->
            assertThat(mapKnownUamRuntimeReason(code)).isEqualTo(status)
        }
        assertThat(mapKnownUamRuntimeReason("new_unknown_internal_code")).isNull()
        assertThat(resolveUamRuntimeStatus("AUTO", "ACTIVE", null))
            .isEqualTo(UamRuntimeStatusUi.ACTIVE)
        assertThat(resolveUamRuntimeStatus("AUTO", "DECAYING", null))
            .isEqualTo(UamRuntimeStatusUi.DECAYING)
        assertThat(resolveUamRuntimeStatus("AUTO", "INACTIVE", "none"))
            .isEqualTo(UamRuntimeStatusUi.INACTIVE)
    }

    @Test
    fun uamMapping_blockedRuntimeClearsStaleLegacy60g() {
        val ui = state {
            latestDataAgeMinutes = 1
            uamRuntimeActive = false
            uamRuntimeEquivalentCarbsGrams = 0.0
            uamRuntimeConfidence = 0.9
            uamRuntimeState = "BLOCKED"
            calculatedUamActive = true
            calculatedUamCarbsGrams = 60.0
            inferredUamActive = true
            inferredUamCarbsGrams = 60.0
        }.toUamUiState()

        assertThat(ui.calculatedActive).isFalse()
        assertThat(ui.calculatedCarbsGrams).isNull()
        assertThat(ui.inferredActive).isNull()
        assertThat(ui.inferredCarbsGrams).isNull()
    }

    @Test
    fun fullAndPrimaryBuilders_hideFifteenMinuteOldActiveRuntimeSnapshot() {
        val now = 1_800_000_000_000L
        val telemetry = uamRuntimeTelemetry(timestamp = now - 15 * 60_000L)
        val snapshots = listOf(
            MainViewModel.resolveFullUiUamRuntimeSnapshotStatic(
                telemetryByKey = telemetry,
                nowTs = now,
                acceptedCycleId = "accepted-cycle"
            ),
            MainViewModel.resolvePrimaryUiUamRuntimeSnapshotStatic(
                telemetryByKey = telemetry,
                nowTs = now,
                acceptedCycleId = "accepted-cycle"
            )
        )

        snapshots.forEach { snapshot ->
            val ui = state {
                latestDataAgeMinutes = 1
                uamRuntimeActive = snapshot.active
                uamRuntimeEquivalentCarbsGrams = snapshot.equivalentCarbsGrams
                uamRuntimeConfidence = snapshot.confidence
                uamRuntimeState = snapshot.state
                uamRuntimeReason = snapshot.reason
            }.toUamUiState()

            assertThat(ui.calculatedActive).isFalse()
            assertThat(ui.calculatedCarbsGrams).isNull()
            assertThat(ui.calculatedConfidence).isNull()
            assertThat(snapshot.state).isEqualTo("INACTIVE")
            assertThat(snapshot.reason).isEqualTo("runtime_snapshot_stale")
        }
    }

    @Test
    fun fullAndPrimaryBuilders_keepFreshActiveRuntimeSnapshot() {
        val now = 1_800_000_000_000L
        val telemetry = uamRuntimeTelemetry(timestamp = now - 5 * 60_000L)
        val snapshots = listOf(
            MainViewModel.resolveFullUiUamRuntimeSnapshotStatic(
                telemetryByKey = telemetry,
                nowTs = now,
                acceptedCycleId = "accepted-cycle"
            ),
            MainViewModel.resolvePrimaryUiUamRuntimeSnapshotStatic(
                telemetryByKey = telemetry,
                nowTs = now,
                acceptedCycleId = "accepted-cycle"
            )
        )

        snapshots.forEach { snapshot ->
            val ui = state {
                latestDataAgeMinutes = 1
                uamRuntimeActive = snapshot.active
                uamRuntimeEquivalentCarbsGrams = snapshot.equivalentCarbsGrams
                uamRuntimeConfidence = snapshot.confidence
                uamRuntimeState = snapshot.state
                uamRuntimeReason = snapshot.reason
            }.toUamUiState()

            assertThat(ui.calculatedActive).isTrue()
            assertThat(ui.calculatedCarbsGrams).isEqualTo(15.0)
            assertThat(ui.calculatedConfidence).isEqualTo(0.82)
            assertThat(snapshot.state).isEqualTo("ACTIVE")
        }
    }

    @Test
    fun fullAndPrimaryBuilders_rejectUamTelemetryFromAnotherAcceptedCycle() {
        val now = 1_800_000_000_000L
        val telemetry = uamRuntimeTelemetry(
            timestamp = now - 60_000L,
            sensitivityCycleId = "old-cycle"
        )

        val snapshots = listOf(
            MainViewModel.resolveFullUiUamRuntimeSnapshotStatic(
                telemetryByKey = telemetry,
                nowTs = now,
                acceptedCycleId = "new-cycle"
            ),
            MainViewModel.resolvePrimaryUiUamRuntimeSnapshotStatic(
                telemetryByKey = telemetry,
                nowTs = now,
                acceptedCycleId = "new-cycle"
            )
        )

        snapshots.forEach { snapshot ->
            assertThat(snapshot.active).isFalse()
            assertThat(snapshot.equivalentCarbsGrams).isNull()
            assertThat(snapshot.confidence).isNull()
            assertThat(snapshot.state).isEqualTo("INACTIVE")
            assertThat(snapshot.reason).isEqualTo("accepted_cycle_mismatch")
        }
    }

    @Test
    fun uamMapping_hidesLegacyEventsNormallyAndKeepsThemForProAudit() {
        val state = state {
            latestDataAgeMinutes = 1
            uamRuntimeState = "ACTIVE"
            uamRuntimeActive = true
            uamEventRows = listOf(
                UamEventRowUi(
                    id = "legacy-event",
                    state = "CONFIRMED",
                    mode = "LEGACY",
                    createdAt = 1L,
                    updatedAt = 2L,
                    ingestionTs = 3L,
                    carbsDisplayG = 60.0,
                    confidence = 0.99,
                    exportSeq = 1,
                    exportedGrams = 15.0,
                    tag = "audit",
                    manualCarbsNearby = false,
                    manualCobActive = false,
                    exportBlockedReason = null
                )
            )
        }

        assertThat(state.toUamUiState(isProMode = false).events).isEmpty()
        assertThat(state.toUamUiState(isProMode = true).events.single().id)
            .isEqualTo("legacy-event")
    }

    @Test
    fun currentRuntimeImpactNeverFallsBackToLegacyCalculatedDelta() {
        assertThat(
            MainViewModel.resolveUiUamImpactStatic(
                runtimeImpactMmol5 = 0.22
            )
        ).isEqualTo(0.22)
        assertThat(
            MainViewModel.resolveUiUamImpactStatic(
                runtimeImpactMmol5 = null
            )
        ).isNull()
    }

    @Test
    fun analyticsOverlayUsesOnlyUnifiedRuntimeEquivalentCarbs() {
        val ts = 1_800_000_000_000L
        val history = listOf(
            IsfCrHistoryPointUi(
                timestamp = ts,
                isfMerged = 3.0,
                crMerged = 10.0,
                isfCalculated = 3.1,
                crCalculated = 9.8
            )
        )
        fun telemetry(key: String, value: Double) = TelemetrySampleEntity(
            id = key,
            timestamp = ts,
            source = "test",
            key = key,
            valueDouble = value,
            valueText = null,
            unit = "g",
            quality = "OK"
        )

        val legacyOnly = buildIsfCrOverlayPoints(
            history,
            listOf(
                telemetry("uam_runtime_carbs_grams", 40.0),
                telemetry("uam_inferred_carbs_grams", 60.0),
                telemetry("uam_calculated_carbs_grams", 55.0)
            )
        )
        val unified = buildIsfCrOverlayPoints(
            history,
            listOf(
                telemetry("uam_runtime_equivalent_carbs_grams", 12.5),
                telemetry("uam_inferred_carbs_grams", 60.0)
            )
        )

        assertThat(legacyOnly.single().uamGrams).isNull()
        assertThat(unified.single().uamGrams).isEqualTo(12.5)
    }

    @Test
    fun overviewWarning_usesRequiredPriority() {
        val ui = state {
            latestGlucoseMmol = 5.6
            latestDataAgeMinutes = 30
            staleDataMaxMinutes = 10
            glucoseAlertStrongActive = true
            glucoseAlertSoftActive = true
            sensorQualityBlocked = true
            killSwitch = true
            powerSaveUntilMs = PowerSaveController.INDEFINITE_UNTIL_MS
        }.toOverviewUiState(isProMode = false)

        assertThat(ui.warning?.kind).isEqualTo(OverviewWarningKind.STRONG_GLUCOSE_ALERT)
    }

    @Test
    fun overviewMapping_keepsOnly24HoursOfRetainedHistoryForInteractiveViewport() {
        val now = 1_800_000_000_000L
        val state = state {
            latestGlucoseMmol = 5.6
            latestDataAgeMinutes = 1
            glucoseHistoryPoints = listOf(
                GlucoseHistoryRowUi(timestamp = now - 25 * 60 * 60_000L, valueMmol = 5.3),
                GlucoseHistoryRowUi(timestamp = now - 13 * 60 * 60_000L, valueMmol = 5.4),
                GlucoseHistoryRowUi(timestamp = now - 11 * 60 * 60_000L, valueMmol = 5.5),
                GlucoseHistoryRowUi(timestamp = now, valueMmol = 5.6)
            )
        }

        val ui = state.toOverviewUiState(isProMode = false)

        assertThat(ui.chart.historyPoints.map { it.ts }).containsExactly(
            now - 13 * 60 * 60_000L,
            now - 11 * 60 * 60_000L,
            now
        ).inOrder()
    }

    @Test
    fun forecastMapping_appliesRangeFilterAndBuildsFuturePath() {
        val now = 1_800_000_000_000L
        val state = state {
            latestGlucoseMmol = 6.4
            latestDataAgeMinutes = 1
            staleDataMaxMinutes = 10
            forecast5m = 6.6
            forecast30m = 7.0
            forecast60m = 7.5
            forecast5mCiLow = 6.3
            forecast5mCiHigh = 6.9
            forecast30mCiLow = 6.4
            forecast30mCiHigh = 7.6
            forecast60mCiLow = 6.7
            forecast60mCiHigh = 8.3
            trend60ComponentMmol = 0.5
            therapy60ComponentMmol = 0.3
            uam60ComponentMmol = 0.2
            residualRoc0Mmol5m = 0.08
            sigmaEMmol5m = 0.11
            kfSigmaGMmol = 0.09
            glucoseHistoryPoints = listOf(
                GlucoseHistoryRowUi(timestamp = now - 4 * 60 * 60_000L, valueMmol = 6.0),
                GlucoseHistoryRowUi(timestamp = now - 2 * 60 * 60_000L, valueMmol = 6.2),
                GlucoseHistoryRowUi(timestamp = now, valueMmol = 6.4)
            )
        }

        val ui = state.toForecastUiState(range = ForecastRangeUi.H3, layers = ForecastLayerState(), isProMode = true)

        assertThat(ui.loadState).isEqualTo(ScreenLoadState.READY)
        assertThat(ui.historyPoints).hasSize(2)
        assertThat(ui.futurePath).hasSize(13)
        assertThat(ui.futureCi).hasSize(13)
        assertThat(ui.decomposition.trend60).isEqualTo(0.5)
        assertThat(ui.decomposition.therapy60).isEqualTo(0.3)
        assertThat(ui.decomposition.uam60).isEqualTo(0.2)
    }

    @Test
    fun invalidAcceptedForecastTupleHidesFutureInOverviewAndForecastButKeepsHistory() {
        val now = 1_800_000_000_000L
        val state = state {
            latestGlucoseMmol = 6.4
            latestDataAgeMinutes = 1
            staleDataMaxMinutes = 10
            forecast5m = 6.6
            forecast30m = 7.0
            forecast60m = 7.5
            forecastTupleError = "forecast_tuple_digest_mismatch"
            trend60ComponentMmol = 0.7
            glucoseHistoryPoints = listOf(
                GlucoseHistoryRowUi(timestamp = now - 5 * 60_000L, valueMmol = 6.2),
                GlucoseHistoryRowUi(timestamp = now, valueMmol = 6.4)
            )
            glucoseCalibrationResolvedHistoryPoints = listOf(
                ChartPointUi(ts = now - 5 * 60_000L, value = 6.3),
                ChartPointUi(ts = now, value = 6.5)
            )
        }

        val overview = state.toOverviewUiState(isProMode = false)
        val forecast = state.toForecastUiState(
            range = ForecastRangeUi.H3,
            layers = ForecastLayerState(),
            isProMode = false
        )

        assertThat(overview.chart.historyPoints.map { it.value }).containsExactly(6.3, 6.5).inOrder()
        assertThat(forecast.historyPoints.map { it.value }).containsExactly(6.3, 6.5).inOrder()
        assertThat(overview.chart.futurePath).isEmpty()
        assertThat(forecast.futurePath).isEmpty()
        assertThat(forecast.decomposition.trend60).isNull()
        assertThat(overview.errorText).contains("forecast_tuple_digest_mismatch")
        assertThat(forecast.errorText).contains("forecast_tuple_digest_mismatch")
    }

    @Test
    fun glucoseChartsPreferCalibrationResolvedHistory() {
        val now = 1_800_000_000_000L
        val accepted = acceptedCopilotSnapshot(timestamp = now)
        val state = state {
            latestGlucoseMmol = 5.5
            calibratedGlucoseMmol = 7.1
            latestDataAgeMinutes = 1
            forecast5m = 7.2
            forecast30m = 7.6
            forecast60m = 7.4
            forecast5mCiLow = 6.8
            forecast5mCiHigh = 7.6
            forecast30mCiLow = 6.5
            forecast30mCiHigh = 8.7
            forecast60mCiLow = 5.9
            forecast60mCiHigh = 8.9
            isfRuntimeSourcePreference = "COPILOT"
            crRuntimeSourcePreference = "COPILOT"
            acceptedSensitivityRuntimeSnapshot = accepted
            forecastAcceptedGenerationTs = accepted.timestamp
            glucoseHistoryPoints = listOf(
                GlucoseHistoryRowUi(timestamp = now - 5 * 60_000L, valueMmol = 5.4),
                GlucoseHistoryRowUi(timestamp = now, valueMmol = 5.5)
            )
            glucoseCalibrationResolvedHistoryPoints = listOf(
                ChartPointUi(ts = now - 5 * 60_000L, value = 7.0),
                ChartPointUi(ts = now, value = 7.1)
            )
        }

        val overview = state.toOverviewUiState(
            isProMode = false,
            authoritativeSensitivitySettingsRevision = accepted.settingsRevision,
            authoritativeNowTs = accepted.timestamp
        )
        val forecast = state.toForecastUiState(
            range = ForecastRangeUi.H3,
            layers = ForecastLayerState(),
            isProMode = false
        )

        assertThat(overview.chart.historyPoints.map { it.value }).containsExactly(7.0, 7.1).inOrder()
        assertThat(forecast.historyPoints.map { it.value }).containsExactly(7.0, 7.1).inOrder()
        assertThat(overview.chart.futurePath.first().value).isEqualTo(7.1)
        assertThat(forecast.futurePath.first().value).isEqualTo(7.1)
        assertThat(overview.chart.futureCi.first().low).isEqualTo(7.1)
        assertThat(overview.chart.futureCi.first().high).isEqualTo(7.1)
    }

    @Test
    fun safetyMapping_usesConfiguredHardBounds() {
        val state = state {
            latestDataAgeMinutes = 1
            staleDataMaxMinutes = 10
            killSwitch = false
            maxActionsIn6Hours = 4
            baseTargetMmol = 5.5
            safetyMinTargetMmol = 4.6
            safetyMaxTargetMmol = 9.4
            localNightscoutEnabled = true
            localNightscoutPort = 17582
            glucoseAlertState = "WARNING_30"
            glucoseAlertDirection = "LOW"
            glucoseAlertDisableReason = "stale_data"
            glucoseAlertLowThreshold = 4.4
            glucoseAlertHighThreshold = 10.0
            glucoseAlertUrgentLowThreshold = 3.9
            glucoseAlertSoftLastTs = 1_800_000_000_000L
            glucoseAlertStrongLastTs = 1_800_000_100_000L
            therapyHistorySourceMode = "SYNTHETIC_ONLY"
            therapyHistoryRealFetchedInsulin30d = 0
            therapyHistoryRecoveredInsulin30d = 4
            therapyHistoryInferredInsulin30d = 41
            therapyHistoryUsableInsulin30d = 4
            therapyHistoryLastSyncTreatmentCount = 2643
            therapyHistoryLastSyncInsulinLikeCount = 0
            therapyHistoryLastSyncCarbLikeCount = 1
            therapyHistoryUpstreamTempTargetOnly = true
            therapyHistoryBootstrapNeeded = true
            therapyHistoryPlateauOnly = true
            therapyHistorySyntheticRatioPct = 100.0
        }

        val ui = state.toSafetyUiState()

        assertThat(ui.hardMinTargetMmol).isEqualTo(4.6)
        assertThat(ui.hardMaxTargetMmol).isEqualTo(9.4)
        assertThat(ui.hardBounds).isEqualTo("4.6..9.4")
        assertThat(ui.adaptiveBounds).isEqualTo("4.6..9.4")
        assertThat(ui.glucoseAlertState).isEqualTo("WARNING_30")
        assertThat(ui.glucoseAlertDirection).isEqualTo("LOW")
        assertThat(ui.glucoseAlertDisableReason).isEqualTo("stale_data")
        assertThat(ui.glucoseAlertLowThreshold).isEqualTo(4.4)
        assertThat(ui.therapyHistorySourceMode).isEqualTo("SYNTHETIC_ONLY")
        assertThat(ui.therapyHistoryRecoveredInsulin30d).isEqualTo(4)
        assertThat(ui.therapyHistoryLastSyncTreatmentCount).isEqualTo(2643)
        assertThat(ui.therapyHistoryLastSyncInsulinLikeCount).isEqualTo(0)
        assertThat(ui.therapyHistoryLastSyncCarbLikeCount).isEqualTo(1)
        assertThat(ui.therapyHistoryUpstreamTempTargetOnly).isTrue()
        assertThat(ui.therapyHistoryBootstrapNeeded).isTrue()
    }

    @Test
    fun settingsMapping_includesGlucoseAlertThresholds() {
        val state = state {
            baseTargetMmol = 5.5
            nightscoutUrl = "https://example"
            cloudUrl = "https://api.openai.com/v1"
            uiStyle = "CLASSIC"
            insulinProfileId = "FIASP"
            localNightscoutEnabled = true
            localBroadcastIngestEnabled = true
            strictBroadcastSenderValidation = false
            enableUamInference = true
            enableUamBoost = true
            enableUamExportToAaps = false
            uamExportMode = "OFF"
            dryRunExport = true
            uamMinSnackG = 15
            uamMaxSnackG = 60
            uamSnackStepG = 5
            sensorLagCorrectionMode = "OFF"
            circadianPatternsEnabled = true
            circadianStableLookbackDays = 14
            circadianRecencyLookbackDays = 5
            circadianUseWeekendSplit = true
            circadianUseReplayResidualBias = true
            circadianForecastWeight30 = 0.25
            circadianForecastWeight60 = 0.35
            softAlertEnabled = true
            watch60AlertEnabled = false
            warning30AlertEnabled = true
            softHighAlertEnabled = false
            critical5AlertEnabled = true
            lowNowAlertEnabled = false
            softAlertLowMmol = 4.4
            softAlertHighMmol = 10.0
            urgentLowMmol = 3.9
            isfCrShadowMode = true
            isfCrConfidenceThreshold = 0.55
            isfCrUseActivity = true
            isfCrUseManualTags = true
            isfCrMinIsfEvidencePerHour = 2
            isfCrMinCrEvidencePerHour = 2
            isfCrCrMaxGapMinutes = 30
            isfCrCrMaxSensorBlockedRatePct = 25.0
            isfCrCrMaxUamAmbiguityRatePct = 60.0
            isfCrSnapshotRetentionDays = 365
            isfCrEvidenceRetentionDays = 730
            isfCrAutoActivationEnabled = false
            isfCrAutoActivationLookbackHours = 24
            isfCrAutoActivationMinSamples = 72
            isfCrAutoActivationMinMeanConfidence = 0.65
            isfCrAutoActivationMaxMeanAbsIsfDeltaPct = 25.0
            isfCrAutoActivationMaxMeanAbsCrDeltaPct = 25.0
            isfCrAutoActivationMinSensorQualityScore = 0.46
            isfCrAutoActivationMinSensorFactor = 0.90
            isfCrAutoActivationMaxWearConfidencePenalty = 0.12
            isfCrAutoActivationMaxSensorAgeHighRatePct = 70.0
            isfCrAutoActivationMaxSuspectFalseLowRatePct = 35.0
            isfCrAutoActivationMinDayTypeRatio = 0.30
            isfCrAutoActivationMaxDayTypeSparseRatePct = 75.0
            isfCrAutoActivationRequireDailyQualityGate = true
            isfCrAutoActivationDailyRiskBlockLevel = 3
            isfCrAutoActivationMinDailyMatchedSamples = 120
            isfCrAutoActivationMaxDailyMae30Mmol = 0.90
            isfCrAutoActivationMaxDailyMae60Mmol = 1.40
            isfCrAutoActivationMaxHypoRatePct = 6.0
            isfCrAutoActivationMinDailyCiCoverage30Pct = 55.0
            isfCrAutoActivationMinDailyCiCoverage60Pct = 55.0
            isfCrAutoActivationMaxDailyCiWidth30Mmol = 1.80
            isfCrAutoActivationMaxDailyCiWidth60Mmol = 2.60
            isfCrAutoActivationRollingMinRequiredWindows = 2
            isfCrAutoActivationRollingMaeRelaxFactor = 1.15
            isfCrAutoActivationRollingCiCoverageRelaxFactor = 0.90
            isfCrAutoActivationRollingCiWidthRelaxFactor = 1.25
            adaptiveControllerEnabled = true
            safetyMinTargetMmol = 4.0
            safetyMaxTargetMmol = 10.0
            postHypoThresholdMmol = 4.0
            postHypoTargetMmol = 4.4
            analyticsLookbackDays = 365
        }

        val ui = state.toSettingsUiState(
            verboseLogsEnabled = false,
            proModeEnabled = false
        )

        assertThat(ui.softAlertEnabled).isTrue()
        assertThat(ui.watch60AlertEnabled).isFalse()
        assertThat(ui.warning30AlertEnabled).isTrue()
        assertThat(ui.softHighAlertEnabled).isFalse()
        assertThat(ui.critical5AlertEnabled).isTrue()
        assertThat(ui.lowNowAlertEnabled).isFalse()
        assertThat(ui.softAlertLowMmol).isEqualTo(4.4)
        assertThat(ui.softAlertHighMmol).isEqualTo(10.0)
        assertThat(ui.urgentLowMmol).isEqualTo(3.9)
    }

    @Test
    fun auditMapping_appliesWindowAndErrorFilters() {
        val now = System.currentTimeMillis()
        val state = state {
            latestDataAgeMinutes = 1
            staleDataMaxMinutes = 10
            auditRecords = listOf(
                AuditRecordRowUi(
                    id = "info_recent",
                    ts = now - 60 * 60_000L,
                    source = "audit",
                    level = "INFO",
                    summary = "all good",
                    context = "ctx"
                ),
                AuditRecordRowUi(
                    id = "error_recent",
                    ts = now - 30 * 60_000L,
                    source = "action",
                    level = "ERROR",
                    summary = "delivery failed",
                    context = "ctx"
                ),
                AuditRecordRowUi(
                    id = "error_old",
                    ts = now - 8 * 60 * 60_000L,
                    source = "rule",
                    level = "ERROR",
                    summary = "old error",
                    context = "ctx"
                )
            )
        }

        val errors6h = state.toAuditUiState(window = AuditWindowUi.H6, onlyErrors = true)
        val all24h = state.toAuditUiState(window = AuditWindowUi.H24, onlyErrors = false)

        assertThat(errors6h.rows.map { it.id }).containsExactly("error_recent")
        assertThat(all24h.rows.map { it.id }).containsExactly("info_recent", "error_recent", "error_old")
    }

    @Test
    fun analyticsMapping_includesIsfCrSummaryAndHistory() {
        val now = System.currentTimeMillis()
        val state = state {
            latestDataAgeMinutes = 2
            staleDataMaxMinutes = 10
            profileIsf = 2.31
            profileCr = 11.4
            profileCalculatedIsf = 2.45
            profileCalculatedCr = 10.8
            qualityMetrics = listOf(
                io.aaps.copilot.ui.QualityMetricUi(
                    horizonMinutes = 30,
                    sampleCount = 120,
                    mae = 0.42,
                    rmse = 0.71,
                    mardPct = 6.8
                )
            )
            dailyReportGeneratedAtTs = now
            dailyReportMatchedSamples = 246
            dailyReportForecastRows = 1200
            dailyReportPeriodStartUtc = "2026-03-02T00:00:00Z"
            dailyReportPeriodEndUtc = "2026-03-03T00:00:00Z"
            dailyReportMarkdownPath = "/storage/emulated/0/Documents/forecast-reports/forecast-report-2026-03-03.md"
            sensorLagCorrectionMode = "SHADOW"
            rawGlucoseMmol = 7.0
            calibratedGlucoseMmol = 7.2
            glucoseCalibrationGain = 1.008
            glucoseCalibrationOffsetMmol = 0.18
            glucoseCalibrationConfidence = 0.71
            glucoseCalibrationModelType = "OFFSET"
            glucoseCalibrationStatus = "ACTIVE"
            glucoseCalibrationLastCheckAgeMinutes = 37.0
            glucoseCalibrationRawHistoryPoints = listOf(
                ChartPointUi(ts = now - 60 * 60_000L, value = 7.0),
                ChartPointUi(ts = now, value = 7.1)
            )
            glucoseCalibrationResolvedHistoryPoints = listOf(
                ChartPointUi(ts = now - 60 * 60_000L, value = 7.2),
                ChartPointUi(ts = now, value = 7.3)
            )
            glucoseCalibrationCheckPoints = listOf(
                ChartPointUi(ts = now - 30 * 60_000L, value = 7.4)
            )
            latestGlucoseMmol = 7.1
            correctedGlucoseMmol = 7.4
            sensorLagMinutes = 12.0
            sensorAgeHours = 278.0
            sensorAgeSource = "inferred"
            sensorLagConfidence = 0.68
            sensorLagMode = "SHADOW"
            sensorLagDisableReason = "raw_input_detected"
            sensorQualityScore = 0.74
            sensorQualityBlocked = false
            sensorQualitySuspectFalseLow = false
            sensorLagTrendLagPoints = listOf(
                ChartPointUi(ts = now - 24 * 60 * 60_000L, value = 10.0),
                ChartPointUi(ts = now - 12 * 60 * 60_000L, value = 11.0),
                ChartPointUi(ts = now, value = 12.0)
            )
            sensorLagTrendCorrectionPoints = listOf(
                ChartPointUi(ts = now - 24 * 60 * 60_000L, value = 0.12),
                ChartPointUi(ts = now, value = 0.30)
            )
            sensorLagModeSegments = listOf(
                SensorLagTimelineSegmentUi(
                    startTs = now - 24 * 60 * 60_000L,
                    endTs = now - 8 * 60 * 60_000L,
                    label = "SHADOW"
                ),
                SensorLagTimelineSegmentUi(
                    startTs = now - 8 * 60 * 60_000L,
                    endTs = now,
                    label = "ACTIVE"
                )
            )
            sensorLagBucketSegments = listOf(
                SensorLagTimelineSegmentUi(
                    startTs = now - 24 * 60 * 60_000L,
                    endTs = now - 6 * 60 * 60_000L,
                    label = "10-12d"
                ),
                SensorLagTimelineSegmentUi(
                    startTs = now - 6 * 60 * 60_000L,
                    endTs = now,
                    label = "12-14d"
                )
            )
            sensorLagTrendStartAgeHours = 254.0
            sensorLagTrendEndAgeHours = 278.0
            dailyReportMetrics = listOf(
                io.aaps.copilot.ui.DailyReportMetricUi(
                    horizonMinutes = 5,
                    sampleCount = 80,
                    mae = 0.24,
                    rmse = 0.35,
                    mardPct = 4.8,
                    bias = 0.03,
                    ciCoveragePct = 82.5,
                    ciMeanWidth = 0.54
                ),
                io.aaps.copilot.ui.DailyReportMetricUi(
                    horizonMinutes = 60,
                    sampleCount = 80,
                    mae = 0.74,
                    rmse = 0.98,
                    mardPct = 10.1,
                    bias = -0.09,
                    ciCoveragePct = 68.0,
                    ciMeanWidth = 1.42
                )
            )
            dailyReportRecommendations = listOf(
                "CR extraction has many gross CGM gaps (24.0% of dropped windows).",
                "ISF/CR extraction is often blocked by sensor quality (22.0% of dropped windows)."
            )
            dailyReportIsfCrQualityLines = listOf(
                "source=isfcr_evidence_extracted, events=24, dropped=88",
                "CR integrity drop-rate: gap=26.1%, sensorBlocked=24.0%, uamAmbiguity=20.5%"
            )
            dailyReportReplayHotspots = listOf(
                io.aaps.copilot.ui.DailyReportReplayHotspotUi(
                    horizonMinutes = 5,
                    hour = 8,
                    sampleCount = 18,
                    mae = 0.31,
                    mardPct = 4.7,
                    bias = 0.02
                ),
                io.aaps.copilot.ui.DailyReportReplayHotspotUi(
                    horizonMinutes = 60,
                    hour = 19,
                    sampleCount = 22,
                    mae = 1.14,
                    mardPct = 13.2,
                    bias = -0.28
                )
            )
            dailyReportReplayFactors = listOf(
                io.aaps.copilot.ui.DailyReportReplayFactorUi(
                    horizonMinutes = 60,
                    factor = "COB",
                    sampleCount = 120,
                    corrAbsError = 0.52,
                    maeHigh = 1.35,
                    maeLow = 0.78,
                    upliftPct = 73.1,
                    contributionScore = 0.61
                ),
                io.aaps.copilot.ui.DailyReportReplayFactorUi(
                    horizonMinutes = 30,
                    factor = "CI",
                    sampleCount = 120,
                    corrAbsError = 0.44,
                    maeHigh = 0.88,
                    maeLow = 0.54,
                    upliftPct = 62.9,
                    contributionScore = 0.52
                )
            )
            dailyReportReplayCoverage = listOf(
                io.aaps.copilot.ui.DailyReportReplayCoverageUi(
                    horizonMinutes = 60,
                    factor = "COB",
                    sampleCount = 118,
                    coveragePct = 95.2
                ),
                io.aaps.copilot.ui.DailyReportReplayCoverageUi(
                    horizonMinutes = 30,
                    factor = "UAM",
                    sampleCount = 87,
                    coveragePct = 70.8
                )
            )
            dailyReportReplayRegimes = listOf(
                io.aaps.copilot.ui.DailyReportReplayRegimeUi(
                    horizonMinutes = 60,
                    factor = "COB",
                    bucket = "HIGH",
                    sampleCount = 40,
                    meanFactorValue = 31.5,
                    mae = 1.28,
                    mardPct = 12.4,
                    bias = -0.22
                ),
                io.aaps.copilot.ui.DailyReportReplayRegimeUi(
                    horizonMinutes = 60,
                    factor = "COB",
                    bucket = "LOW",
                    sampleCount = 38,
                    meanFactorValue = 7.1,
                    mae = 0.76,
                    mardPct = 8.5,
                    bias = -0.06
                )
            )
            dailyReportReplayPairs = listOf(
                io.aaps.copilot.ui.DailyReportReplayPairUi(
                    horizonMinutes = 60,
                    factorA = "COB",
                    factorB = "IOB",
                    bucketA = "HIGH",
                    bucketB = "HIGH",
                    sampleCount = 18,
                    meanFactorA = 30.8,
                    meanFactorB = 2.0,
                    mae = 1.22,
                    mardPct = 12.1,
                    bias = -0.20
                ),
                io.aaps.copilot.ui.DailyReportReplayPairUi(
                    horizonMinutes = 60,
                    factorA = "COB",
                    factorB = "IOB",
                    bucketA = "LOW",
                    bucketB = "LOW",
                    sampleCount = 17,
                    meanFactorA = 7.2,
                    meanFactorB = 0.8,
                    mae = 0.71,
                    mardPct = 8.2,
                    bias = -0.05
                )
            )
            dailyReportReplayTopMisses = listOf(
                io.aaps.copilot.ui.DailyReportReplayTopMissUi(
                    horizonMinutes = 5,
                    ts = now - 15 * 60_000L,
                    absError = 0.42,
                    pred = 7.2,
                    actual = 6.8,
                    cob = 14.0,
                    iob = 1.2,
                    uam = 0.10,
                    ciWidth = 0.9,
                    diaHours = 4.0,
                    activity = 1.05,
                    sensorQuality = 0.92
                ),
                io.aaps.copilot.ui.DailyReportReplayTopMissUi(
                    horizonMinutes = 60,
                    ts = now - 40 * 60_000L,
                    absError = 1.32,
                    pred = 10.4,
                    actual = 9.1,
                    cob = 32.0,
                    iob = 1.4,
                    uam = 0.28,
                    ciWidth = 1.6,
                    diaHours = 4.2,
                    activity = 0.98,
                    sensorQuality = 0.88
                )
            )
            dailyReportReplayErrorClusters = listOf(
                io.aaps.copilot.ui.DailyReportReplayErrorClusterUi(
                    horizonMinutes = 60,
                    hour = 19,
                    dayType = "WEEKDAY",
                    sampleCount = 20,
                    mae = 1.18,
                    mardPct = 12.6,
                    bias = -0.21,
                    meanCob = 31.2,
                    meanIob = 1.9,
                    meanUam = 0.24,
                    meanCiWidth = 1.55,
                    dominantFactor = "COB",
                    dominantScore = 1.56
                )
            )
            dailyReportReplayDayTypeGaps = listOf(
                io.aaps.copilot.ui.DailyReportReplayDayTypeGapUi(
                    horizonMinutes = 60,
                    hour = 19,
                    worseDayType = "WEEKEND",
                    weekdaySampleCount = 12,
                    weekendSampleCount = 10,
                    weekdayMae = 0.86,
                    weekendMae = 1.24,
                    weekdayMardPct = 9.8,
                    weekendMardPct = 12.9,
                    maeGapMmol = 0.38,
                    mardGapPct = 3.1,
                    worseMeanCob = 27.4,
                    worseMeanIob = 1.7,
                    worseMeanUam = 0.22,
                    worseMeanCiWidth = 1.49,
                    dominantFactor = "COB",
                    dominantScore = 1.37
                )
            )
            dailyReportReplayTopFactorsOverall = "COB=0.612;CI=0.521;IOB=0.344"
            rollingReportLines = listOf(
                "14d: n=840, MAE30=0.52, MAE60=0.81, MARD30=7.4%, MARD60=10.9%, CI60=63.0%, W60=1.39",
                "30d: n=1740, MAE30=0.49, MAE60=0.78, MARD30=7.1%, MARD60=10.5%, CI60=64.2%, W60=1.34"
            )
            baselineDeltaLines = listOf("60m UAM: +0.09 mmol/L")
            isfCrHistoryPoints = listOf(
                IsfCrHistoryPointUi(
                    timestamp = now - 60 * 60_000L,
                    isfMerged = 2.30,
                    crMerged = 11.3,
                    isfCalculated = 2.42,
                    crCalculated = 10.9,
                    isfAaps = 2.18,
                    crAaps = 9.7
                ),
                IsfCrHistoryPointUi(
                    timestamp = now,
                    isfMerged = 2.31,
                    crMerged = 11.4,
                    isfCalculated = 2.45,
                    crCalculated = 10.8,
                    isfAaps = 2.16,
                    crAaps = 9.6
                )
            )
            isfCrHistoryOverlayPoints = listOf(
                IsfCrOverlayPointUi(
                    timestamp = now - 60 * 60_000L,
                    cobGrams = 18.0,
                    uamGrams = 6.0,
                    activityRatio = 1.18
                ),
                IsfCrOverlayPointUi(
                    timestamp = now,
                    cobGrams = 8.0,
                    uamGrams = 2.0,
                    activityRatio = 0.96
                )
            )
            isfCrHistoryLastUpdatedTs = now
            isfCrDeepLines = listOf("day/06:00-09:00 ISF=2.4 CR=10.9 conf=74%")
            circadianSlotStatCount = 288
            circadianTransitionStatCount = 864
            circadianSnapshotCount = 3
            circadianReplayStatCount = 576
            circadianLatestSnapshotUpdatedTs = now - 15 * 60_000L
            circadianLatestReplayUpdatedTs = now - 10 * 60_000L
            circadianPatternSections = listOf(
                CircadianPatternSectionUi(
                    requestedDayType = "WEEKDAY",
                    segmentSource = "WEEKDAY",
                    stableWindowDays = 14,
                    recencyWindowDays = 5,
                    recencyWeight = 0.3,
                    coverageDays = 10,
                    sampleCount = 480,
                    segmentFallback = false,
                    confidence = 0.72,
                    qualityScore = 0.81
                )
            )
            isfCrActivationGateLines = listOf(
                "KPI gate (03 Mar 12:00): eligible=true, reason=ok, n=360, conf=72%",
                "Daily gate (03 Mar 12:00): eligible=true, reason=ok, n=288"
            )
            isfCrDroppedReasons24hLines = listOf(
                "Events=12, dropped total=21",
                "isf_small_units=9"
            )
            isfCrDroppedReasons7dLines = listOf(
                "Events=70, dropped total=88",
                "cr_no_bolus_nearby=24"
            )
            isfCrWearImpact24hLines = listOf(
                "Events=12, mean set/sensor age=48.0h/60.0h",
                "High wear set>72h=20%, sensor>120h=10%"
            )
            isfCrWearImpact7dLines = listOf(
                "Events=70, mean set/sensor age=54.0h/84.0h",
                "Mean factors set/sensor=0.93/0.96"
            )
            isfCrRuntimeDiagTs = now - 5 * 60_000L
            isfCrRuntimeDiagMode = "SHADOW"
            isfCrRuntimeDiagConfidence = 0.71
            isfCrRuntimeDiagConfidenceThreshold = 0.55
            isfCrRuntimeDiagQualityScore = 0.83
            isfCrRuntimeDiagUsedEvidence = 18
            isfCrRuntimeDiagDroppedEvidence = 4
            isfCrRuntimeDiagDroppedReasons = "isf_small_units=2;cr_no_bolus_nearby=2"
            isfCrRuntimeDiagCurrentDayType = "WEEKDAY"
            isfCrRuntimeDiagIsfBaseSource = "day_type"
            isfCrRuntimeDiagCrBaseSource = "hourly"
            isfCrRuntimeDiagIsfDayTypeBaseAvailable = true
            isfCrRuntimeDiagCrDayTypeBaseAvailable = false
            isfCrRuntimeDiagHourWindowIsfEvidence = 1
            isfCrRuntimeDiagHourWindowCrEvidence = 0
            isfCrRuntimeDiagHourWindowIsfSameDayType = 1
            isfCrRuntimeDiagHourWindowCrSameDayType = 0
            isfCrRuntimeDiagMinIsfEvidencePerHour = 2
            isfCrRuntimeDiagMinCrEvidencePerHour = 2
            isfCrRuntimeDiagCrMaxGapMinutes = 30.0
            isfCrRuntimeDiagCrMaxSensorBlockedRatePct = 25.0
            isfCrRuntimeDiagCrMaxUamAmbiguityRatePct = 60.0
            isfCrRuntimeDiagCoverageHoursIsf = 6
            isfCrRuntimeDiagCoverageHoursCr = 5
            isfCrRuntimeDiagReasons = "isf_evidence_sparse,isf_hourly_evidence_below_min,cr_hourly_evidence_below_min,isf_day_type_evidence_sparse"
            isfCrRuntimeDiagLowConfidenceTs = now - 2 * 60 * 60_000L
            isfCrRuntimeDiagLowConfidenceReasons = "low_quality"
            isfCrRuntimeDiagFallbackTs = now - 90 * 60_000L
            isfCrRuntimeDiagFallbackReasons = "low_confidence_fallback"
        }

        val ui = state.toAnalyticsUiState()

        assertThat(ui.loadState).isEqualTo(ScreenLoadState.READY)
        assertThat(ui.sensorLagDiagnostics).isNotNull()
        assertThat(ui.sensorLagDiagnostics!!.configuredMode).isEqualTo("SHADOW")
        assertThat(ui.sensorLagDiagnostics!!.runtimeMode).isEqualTo("SHADOW")
        assertThat(ui.sensorLagDiagnostics!!.correctionMmol).isWithin(1e-6).of(0.3)
        assertThat(ui.sensorLagDiagnostics!!.ageSource).isEqualTo("inferred")
        assertThat(ui.sensorLagDiagnostics!!.lagTrendPoints).hasSize(3)
        assertThat(ui.sensorLagDiagnostics!!.modeSegments).hasSize(2)
        assertThat(ui.sensorLagDiagnostics!!.bucketSegments).hasSize(2)
        assertThat(ui.sensorLagDiagnostics!!.trendStartAgeHours).isEqualTo(254.0)
        assertThat(ui.sensorLagDiagnostics!!.trendEndAgeHours).isEqualTo(278.0)
        assertThat(ui.currentIsfReal).isEqualTo(2.45)
        assertThat(ui.currentCrReal).isEqualTo(10.8)
        assertThat(ui.calibrationRawCurrentMmol).isEqualTo(7.1)
        assertThat(ui.calibrationCalibratedCurrentMmol).isEqualTo(7.2)
        assertThat(ui.calibrationModelType).isEqualTo("OFFSET")
        assertThat(ui.calibrationStatus).isEqualTo("ACTIVE")
        assertThat(ui.calibrationRawHistoryPoints).hasSize(2)
        assertThat(ui.calibrationResolvedHistoryPoints).hasSize(2)
        assertThat(ui.calibrationCheckPoints).hasSize(1)
        assertThat(ui.currentIsfMerged).isEqualTo(2.31)
        assertThat(ui.currentCrMerged).isEqualTo(11.4)
        assertThat(ui.currentIsfAapsRaw).isEqualTo(2.16)
        assertThat(ui.currentCrAapsRaw).isEqualTo(9.6)
        assertThat(ui.historyPoints).hasSize(2)
        assertThat(ui.historyOverlayPoints).hasSize(2)
        assertThat(ui.historyOverlayPoints.last().cobGrams).isEqualTo(8.0)
        assertThat(ui.historyLastUpdatedTs).isEqualTo(now)
        assertThat(ui.deepLines).containsExactly("day/06:00-09:00 ISF=2.4 CR=10.9 conf=74%")
        assertThat(ui.circadianStateStatus).isNotNull()
        assertThat(ui.circadianStateStatus?.state).isEqualTo("READY")
        assertThat(ui.circadianStateStatus?.slotCount).isEqualTo(288)
        assertThat(ui.circadianStateStatus?.replayCount).isEqualTo(576)
        assertThat(ui.circadianStateStatus?.sectionCount).isEqualTo(1)
        assertThat(ui.circadianStateStatus?.sourceSummary).contains("weekday→weekday:14d")
        assertThat(ui.qualityLines).hasSize(1)
        assertThat(ui.baselineDeltaLines).containsExactly("60m UAM: +0.09 mmol/L")
        assertThat(ui.dailyReportGeneratedAtTs).isEqualTo(now)
        assertThat(ui.dailyReportMatchedSamples).isEqualTo(246)
        assertThat(ui.dailyReportForecastRows).isEqualTo(1200)
        assertThat(ui.dailyReportPeriodStartUtc).isEqualTo("2026-03-02T00:00:00Z")
        assertThat(ui.dailyReportPeriodEndUtc).isEqualTo("2026-03-03T00:00:00Z")
        assertThat(ui.dailyReportMarkdownPath).contains("forecast-report-2026-03-03.md")
        assertThat(ui.dailyReportHorizonStats).hasSize(2)
        assertThat(ui.dailyReportRecommendations).hasSize(2)
        assertThat(ui.dailyReportRecommendations.first()).contains("gross CGM gaps")
        assertThat(ui.dailyReportIsfCrQualityLines).hasSize(2)
        assertThat(ui.dailyReportIsfCrQualityLines.first()).contains("source=isfcr_evidence_extracted")
        assertThat(ui.dailyReportReplayHotspots).hasSize(2)
        assertThat(ui.dailyReportReplayHotspots.first { it.horizonMinutes == 60 }.hour).isEqualTo(19)
        assertThat(ui.dailyReportReplayFactorContributions).hasSize(2)
        assertThat(ui.dailyReportReplayFactorContributions.first { it.horizonMinutes == 60 }.factor).isEqualTo("COB")
        assertThat(ui.dailyReportReplayFactorCoverage).hasSize(2)
        assertThat(ui.dailyReportReplayFactorCoverage.first { it.horizonMinutes == 60 }.coveragePct).isEqualTo(95.2)
        assertThat(ui.dailyReportReplayFactorRegimes).hasSize(2)
        assertThat(ui.dailyReportReplayFactorRegimes.first { it.bucket == "HIGH" }.mae).isEqualTo(1.28)
        assertThat(ui.dailyReportReplayFactorPairs).hasSize(2)
        assertThat(ui.dailyReportReplayFactorPairs.first { it.bucketA == "HIGH" && it.bucketB == "HIGH" }.mae)
            .isEqualTo(1.22)
        assertThat(ui.dailyReportReplayTopMisses).hasSize(2)
        assertThat(ui.dailyReportReplayTopMisses.first { it.horizonMinutes == 60 }.absError).isEqualTo(1.32)
        assertThat(ui.dailyReportReplayErrorClusters).hasSize(1)
        assertThat(ui.dailyReportReplayErrorClusters.first().dominantFactor).isEqualTo("COB")
        assertThat(ui.dailyReportReplayErrorClusters.first().dayType).isEqualTo("WEEKDAY")
        assertThat(ui.dailyReportReplayDayTypeGaps).hasSize(1)
        assertThat(ui.dailyReportReplayDayTypeGaps.first().worseDayType).isEqualTo("WEEKEND")
        assertThat(ui.dailyReportReplayDayTypeGaps.first().maeGapMmol).isEqualTo(0.38)
        assertThat(ui.dailyReportReplayTopFactorsOverall).contains("COB")
        assertThat(ui.dailyReportHorizonStats.first { it.horizonMinutes == 5 }.mardPct).isEqualTo(4.8)
        assertThat(ui.dailyReportHorizonStats.first { it.horizonMinutes == 5 }.ciCoveragePct).isEqualTo(82.5)
        assertThat(ui.dailyReportHorizonStats.first { it.horizonMinutes == 60 }.ciMeanWidth).isEqualTo(1.42)
        assertThat(ui.rollingReportLines).hasSize(2)
        assertThat(ui.rollingReportLines.first()).contains("14d")
        assertThat(ui.activationGateLines).hasSize(2)
        assertThat(ui.activationGateLines.first()).contains("KPI gate")
        assertThat(ui.droppedReasons24hLines).containsExactly("Events=12, dropped total=21", "isf_small_units=9")
        assertThat(ui.droppedReasons7dLines).containsExactly("Events=70, dropped total=88", "cr_no_bolus_nearby=24")
        assertThat(ui.wearImpact24hLines).containsExactly(
            "Events=12, mean set/sensor age=48.0h/60.0h",
            "High wear set>72h=20%, sensor>120h=10%"
        )
        assertThat(ui.wearImpact7dLines).containsExactly(
            "Events=70, mean set/sensor age=54.0h/84.0h",
            "Mean factors set/sensor=0.93/0.96"
        )
        assertThat(ui.runtimeDiagnostics).isNotNull()
        assertThat(ui.runtimeDiagnostics?.mode).isEqualTo("SHADOW")
        assertThat(ui.runtimeDiagnostics?.usedEvidence).isEqualTo(18)
        assertThat(ui.runtimeDiagnostics?.currentDayType).isEqualTo("WEEKDAY")
        assertThat(ui.runtimeDiagnostics?.isfBaseSource).isEqualTo("day_type")
        assertThat(ui.runtimeDiagnostics?.crBaseSource).isEqualTo("hourly")
        assertThat(ui.runtimeDiagnostics?.isfDayTypeBaseAvailable).isTrue()
        assertThat(ui.runtimeDiagnostics?.crDayTypeBaseAvailable).isFalse()
        assertThat(ui.runtimeDiagnostics?.hourWindowIsfEvidence).isEqualTo(1)
        assertThat(ui.runtimeDiagnostics?.hourWindowCrEvidence).isEqualTo(0)
        assertThat(ui.runtimeDiagnostics?.hourWindowIsfSameDayType).isEqualTo(1)
        assertThat(ui.runtimeDiagnostics?.hourWindowCrSameDayType).isEqualTo(0)
        assertThat(ui.runtimeDiagnostics?.minIsfEvidencePerHour).isEqualTo(2)
        assertThat(ui.runtimeDiagnostics?.minCrEvidencePerHour).isEqualTo(2)
        assertThat(ui.runtimeDiagnostics?.crMaxGapMinutes).isEqualTo(30.0)
        assertThat(ui.runtimeDiagnostics?.crMaxSensorBlockedRatePct).isEqualTo(25.0)
        assertThat(ui.runtimeDiagnostics?.crMaxUamAmbiguityRatePct).isEqualTo(60.0)
        assertThat(ui.runtimeDiagnostics?.fallbackReasons).contains("low_confidence_fallback")
        assertThat(ui.runtimeDiagnostics?.droppedReasonCodes).contains("isf_small_units")
        assertThat(ui.runtimeDiagnostics?.droppedReasonCodes).contains("cr_no_bolus_nearby")
        assertThat(ui.runtimeDiagnostics?.reasonCodes).contains("isf_evidence_sparse")
        assertThat(ui.runtimeDiagnostics?.reasonCodes).contains("isf_hourly_evidence_below_min")
        assertThat(ui.runtimeDiagnostics?.reasonCodes).contains("cr_hourly_evidence_below_min")
        assertThat(ui.runtimeDiagnostics?.reasonCodes).contains("isf_day_type_evidence_sparse")
        assertThat(ui.runtimeDiagnostics?.lowConfidenceReasonCodes).contains("low_quality")
        assertThat(ui.runtimeDiagnostics?.fallbackReasonCodes).contains("low_confidence_fallback")
        assertThat(ui.selectedInsulinProfileId).isEqualTo("NOVORAPID")
        assertThat(ui.insulinProfileCurves).isNotEmpty()
        assertThat(ui.insulinProfileCurves.any { it.id == "NOVORAPID" && it.isSelected }).isTrue()
        assertThat(ui.insulinProfileCurves.all { it.points.size >= 2 }).isTrue()
    }

    @Test
    fun analyticsMapping_keepsReferenceCurveAndAddsRealDailyOverlay() {
        val state = state {
            latestDataAgeMinutes = 2
            staleDataMaxMinutes = 10
            insulinProfileId = "NOVORAPID"
            insulinRealProfileCurveCompact = "0:0.0000;15:0.0600;30:0.2100;60:0.5600;90:0.7900;120:1.0000"
            insulinRealProfileUpdatedTs = 1_800_000_000_000L
            insulinRealProfileConfidence = 0.74
            insulinRealProfileSamples = 6
            insulinRealProfileOnsetMinutes = 20.0
            insulinRealProfilePeakMinutes = 72.0
            insulinRealProfileScale = 1.18
            insulinRealProfileStatus = "estimated_daily"
        }

        val ui = state.toAnalyticsUiState()

        assertThat(ui.insulinRealProfileAvailable).isTrue()
        assertThat(ui.selectedInsulinProfileId).isEqualTo("NOVORAPID")
        val selectedCurve = ui.insulinProfileCurves.first { it.id == "NOVORAPID" && it.isSelected }
        assertThat(selectedCurve.label).doesNotContain("REAL daily")
        assertThat(selectedCurve.points).isNotEmpty()
        assertThat(ui.insulinRealProfileCurvePoints).hasSize(6)
        assertThat(selectedCurve.points).isNotEqualTo(ui.insulinRealProfileCurvePoints)
        assertThat(ui.insulinRealProfileCurvePoints.first().minute).isEqualTo(0.0)
        assertThat(ui.insulinRealProfileCurvePoints.first().cumulative).isEqualTo(0.0)
        assertThat(ui.insulinRealProfileCurvePoints.last().minute).isEqualTo(120.0)
        assertThat(ui.insulinRealProfileCurvePoints.last().cumulative).isEqualTo(1.0)
    }

    @Test
    fun settingsMapping_includesUamSnackParameters() {
        val state = state {
            baseTargetMmol = 5.6
            nightscoutUrl = "https://example.ns"
            cloudUrl = "https://ai.example"
            resolvedNightscoutUrl = "https://127.0.0.1:17582"
            insulinProfileId = "NOVORAPID"
            localNightscoutEnabled = true
            localBroadcastIngestEnabled = true
            strictBroadcastSenderValidation = false
            enableUamInference = true
            enableUamBoost = false
            enableUamExportToAaps = true
            uamExportMode = "CONFIRMED_ONLY"
            dryRunExport = false
            uamMinSnackG = 20
            uamMaxSnackG = 70
            uamSnackStepG = 10
            isfCrMinIsfEvidencePerHour = 3
            isfCrMinCrEvidencePerHour = 4
            isfCrCrMaxGapMinutes = 35
            isfCrCrMaxSensorBlockedRatePct = 28.0
            isfCrCrMaxUamAmbiguityRatePct = 66.0
            isfCrAutoActivationRequireDailyQualityGate = true
            isfCrAutoActivationMinDailyMatchedSamples = 144
            isfCrAutoActivationMaxDailyMae30Mmol = 0.85
            isfCrAutoActivationMaxDailyMae60Mmol = 1.35
            isfCrAutoActivationMaxHypoRatePct = 5.5
            isfCrAutoActivationMinDailyCiCoverage30Pct = 57.0
            isfCrAutoActivationMinDailyCiCoverage60Pct = 54.0
            isfCrAutoActivationMaxDailyCiWidth30Mmol = 1.75
            isfCrAutoActivationMaxDailyCiWidth60Mmol = 2.45
            isfCrAutoActivationRollingMinRequiredWindows = 3
            isfCrAutoActivationRollingMaeRelaxFactor = 1.22
            isfCrAutoActivationRollingCiCoverageRelaxFactor = 0.88
            isfCrAutoActivationRollingCiWidthRelaxFactor = 1.31
            isfCrAutoActivationMinSensorQualityScore = 0.48
            isfCrAutoActivationMinSensorFactor = 0.92
            isfCrAutoActivationMaxWearConfidencePenalty = 0.11
            isfCrAutoActivationMaxSensorAgeHighRatePct = 65.0
            isfCrAutoActivationMaxSuspectFalseLowRatePct = 22.0
            isfCrAutoActivationMinDayTypeRatio = 0.34
            isfCrAutoActivationMaxDayTypeSparseRatePct = 61.0
            isfCrAutoActivationDailyRiskBlockLevel = 2
            adaptiveControllerEnabled = true
            safetyMinTargetMmol = 4.3
            safetyMaxTargetMmol = 9.2
            postHypoThresholdMmol = 3.2
            postHypoTargetMmol = 4.6
            analyticsLookbackDays = 400
        }

        val ui = state.toSettingsUiState(verboseLogsEnabled = true, proModeEnabled = false)

        assertThat(ui.uamMinSnackG).isEqualTo(20)
        assertThat(ui.uamMaxSnackG).isEqualTo(70)
        assertThat(ui.uamSnackStepG).isEqualTo(10)
        assertThat(ui.uamExport.mode).isEqualTo("OBSERVE")
        assertThat(ui.nightscoutUrl).isEqualTo("https://example.ns")
        assertThat(ui.aiApiUrl).isEqualTo("https://ai.example")
        assertThat(ui.aiCredential.configured).isFalse()
        assertThat(ui.isfCrMinIsfEvidencePerHour).isEqualTo(3)
        assertThat(ui.isfCrMinCrEvidencePerHour).isEqualTo(4)
        assertThat(ui.isfCrCrMaxGapMinutes).isEqualTo(35)
        assertThat(ui.isfCrCrMaxSensorBlockedRatePct).isEqualTo(28.0)
        assertThat(ui.isfCrCrMaxUamAmbiguityRatePct).isEqualTo(66.0)
        assertThat(ui.isfCrAutoActivationRequireDailyQualityGate).isTrue()
        assertThat(ui.isfCrAutoActivationMinDailyMatchedSamples).isEqualTo(144)
        assertThat(ui.isfCrAutoActivationMaxDailyMae30Mmol).isEqualTo(0.85)
        assertThat(ui.isfCrAutoActivationMaxDailyMae60Mmol).isEqualTo(1.35)
        assertThat(ui.isfCrAutoActivationMaxHypoRatePct).isEqualTo(5.5)
        assertThat(ui.isfCrAutoActivationMinDailyCiCoverage30Pct).isEqualTo(57.0)
        assertThat(ui.isfCrAutoActivationMinDailyCiCoverage60Pct).isEqualTo(54.0)
        assertThat(ui.isfCrAutoActivationMaxDailyCiWidth30Mmol).isEqualTo(1.75)
        assertThat(ui.isfCrAutoActivationMaxDailyCiWidth60Mmol).isEqualTo(2.45)
        assertThat(ui.isfCrAutoActivationRollingMinRequiredWindows).isEqualTo(3)
        assertThat(ui.isfCrAutoActivationRollingMaeRelaxFactor).isEqualTo(1.22)
        assertThat(ui.isfCrAutoActivationRollingCiCoverageRelaxFactor).isEqualTo(0.88)
        assertThat(ui.isfCrAutoActivationRollingCiWidthRelaxFactor).isEqualTo(1.31)
        assertThat(ui.isfCrAutoActivationMinSensorQualityScore).isEqualTo(0.48)
        assertThat(ui.isfCrAutoActivationMinSensorFactor).isEqualTo(0.92)
        assertThat(ui.isfCrAutoActivationMaxWearConfidencePenalty).isEqualTo(0.11)
        assertThat(ui.isfCrAutoActivationMaxSensorAgeHighRatePct).isEqualTo(65.0)
        assertThat(ui.isfCrAutoActivationMaxSuspectFalseLowRatePct).isEqualTo(22.0)
        assertThat(ui.isfCrAutoActivationMinDayTypeRatio).isEqualTo(0.34)
        assertThat(ui.isfCrAutoActivationMaxDayTypeSparseRatePct).isEqualTo(61.0)
        assertThat(ui.isfCrAutoActivationDailyRiskBlockLevel).isEqualTo(2)
        assertThat(ui.safetyMinTargetMmol).isEqualTo(4.3)
        assertThat(ui.safetyMaxTargetMmol).isEqualTo(9.2)
    }

    @Test
    fun aiAnalysisMapping_exposesCloudHistoryTrendAndLocalReport() {
        val state = state {
            latestDataAgeMinutes = 3
            staleDataMaxMinutes = 10
            cloudUrl = "https://cloud.example"
            insightsFilterLabel = "Filters: source=scheduler, status=SUCCESS, days=90, weeks=12"
            cloudJobRows = listOf(
                CloudJobRowUi(
                    jobId = "daily_analysis",
                    lastStatus = "SUCCESS",
                    lastRunTs = 1_800_000_000_000L,
                    nextRunTs = 1_800_000_360_000L,
                    lastMessage = "ok"
                )
            )
            analysisHistoryItems = listOf(
                AnalysisHistoryRowUi(
                    runTs = 1_800_000_000_000L,
                    date = "2026-03-04",
                    source = "scheduler",
                    status = "SUCCESS",
                    summary = "Summary line",
                    anomalies = listOf("Anomaly #1"),
                    recommendations = listOf("Recommendation #1"),
                    errorMessage = null
                )
            )
            analysisTrendItems = listOf(
                AnalysisTrendRowUi(
                    weekStart = "2026-02-29",
                    totalRuns = 14,
                    successRuns = 13,
                    failedRuns = 1,
                    anomaliesCount = 5,
                    recommendationsCount = 8
                )
            )
            dailyReportGeneratedAtTs = 1_800_000_000_000L
            dailyReportPeriodStartUtc = "2026-03-03T00:00:00Z"
            dailyReportPeriodEndUtc = "2026-03-04T00:00:00Z"
            dailyReportMetrics = listOf(
                io.aaps.copilot.ui.DailyReportMetricUi(
                    horizonMinutes = 30,
                    sampleCount = 100,
                    mae = 0.52,
                    rmse = 0.72,
                    mardPct = 7.1,
                    bias = -0.04
                )
            )
            dailyReportReplayTopFactorsOverall = "COB=0.61;CI=0.52"
            dailyReportReplayFactors = listOf(
                io.aaps.copilot.ui.DailyReportReplayFactorUi(
                    horizonMinutes = 60,
                    factor = "COB",
                    sampleCount = 88,
                    corrAbsError = 0.48,
                    maeHigh = 1.10,
                    maeLow = 0.74,
                    upliftPct = 64.2,
                    contributionScore = 0.61
                )
            )
            dailyReportReplayHotspots = listOf(
                io.aaps.copilot.ui.DailyReportReplayHotspotUi(
                    horizonMinutes = 60,
                    hour = 19,
                    sampleCount = 17,
                    mae = 1.12,
                    mardPct = 12.8,
                    bias = -0.22
                )
            )
            dailyReportReplayTopMisses = listOf(
                io.aaps.copilot.ui.DailyReportReplayTopMissUi(
                    horizonMinutes = 60,
                    ts = 1_800_000_000_000L,
                    absError = 1.34,
                    pred = 10.2,
                    actual = 8.9,
                    cob = 31.0,
                    iob = 1.5,
                    uam = 0.21,
                    ciWidth = 1.42,
                    diaHours = 4.2,
                    activity = 1.03,
                    sensorQuality = 0.90
                )
            )
            dailyReportReplayDayTypeGaps = listOf(
                io.aaps.copilot.ui.DailyReportReplayDayTypeGapUi(
                    horizonMinutes = 60,
                    hour = 18,
                    worseDayType = "WEEKEND",
                    weekdaySampleCount = 10,
                    weekendSampleCount = 9,
                    weekdayMae = 0.82,
                    weekendMae = 1.19,
                    weekdayMardPct = 9.2,
                    weekendMardPct = 12.3,
                    maeGapMmol = 0.37,
                    mardGapPct = 3.1,
                    worseMeanCob = 28.4,
                    worseMeanIob = 1.4,
                    worseMeanUam = 0.19,
                    worseMeanCiWidth = 1.33,
                    dominantFactor = "COB",
                    dominantScore = 1.24
                )
            )
            dailyReportRecommendations = listOf("Tune evening profile")
            rollingReportLines = listOf("30d: n=1020, MAE30=0.55")
        }

        val ui = state.toAiAnalysisUiState()

        assertThat(ui.loadState).isEqualTo(ScreenLoadState.READY)
        assertThat(ui.cloudConfigured).isTrue()
        assertThat(ui.filterLabel).contains("scheduler")
        assertThat(ui.jobs).hasSize(1)
        assertThat(ui.jobs.first().jobId).isEqualTo("daily_analysis")
        assertThat(ui.historyItems).hasSize(1)
        assertThat(ui.historyItems.first().anomalies).containsExactly("Anomaly #1")
        assertThat(ui.trendItems).hasSize(1)
        assertThat(ui.trendItems.first().successRuns).isEqualTo(13)
        assertThat(ui.localDailyMetrics).hasSize(1)
        assertThat(ui.localDailyMetrics.first().mae).isEqualTo(0.52)
        assertThat(ui.localHorizonScores).hasSize(1)
        assertThat(ui.localHorizonScores.first().scoreBand).isEqualTo("EXCELLENT")
        assertThat(ui.localTopFactorsOverall).contains("COB")
        assertThat(ui.localTopFactors).hasSize(1)
        assertThat(ui.localTopFactors.first().factor).isEqualTo("COB")
        assertThat(ui.localHotspots).hasSize(1)
        assertThat(ui.localHotspots.first().hour).isEqualTo(19)
        assertThat(ui.localTopMisses).hasSize(1)
        assertThat(ui.localTopMisses.first().absError).isEqualTo(1.34)
        assertThat(ui.localDayTypeGaps).hasSize(1)
        assertThat(ui.localDayTypeGaps.first().worseDayType).isEqualTo("WEEKEND")
        assertThat(ui.localRecommendations).containsExactly("Tune evening profile")
        assertThat(ui.rollingLines).containsExactly("30d: n=1020, MAE30=0.55")
    }

    @Test
    fun aiAnalysisMapping_doesNotBlockOnWarnOnlySyncIssue() {
        val state = state {
            latestDataAgeMinutes = 2
            staleDataMaxMinutes = 10
            syncStatusLines = listOf(
                "Nightscout last sync: 2026-03-04 12:00",
                "Last sync issue: WARN: isfcr_realtime_refresh_sync_failed"
            )
        }

        val ui = state.toAiAnalysisUiState()

        assertThat(ui.loadState).isEqualTo(ScreenLoadState.EMPTY)
        assertThat(ui.errorText).isNull()
    }

    @Test
    fun aiAnalysisMapping_includesCoverageReadinessAndChatState() {
        val state = state {
            aiMinDataHours = 24
            aiDataCoverageHours = 26.4
            aiAnalysisReady = true
        }
        val chatMessages = listOf(
            AiChatMessageUi(
                id = "u1",
                role = "user",
                text = "Why is MARD high?",
                ts = 1_800_000_000_000L
            ),
            AiChatMessageUi(
                id = "a1",
                role = "assistant",
                text = "COB and CI are dominant contributors.",
                ts = 1_800_000_000_050L
            )
        )

        val ui = state.toAiAnalysisUiState(
            chatMessages = chatMessages,
            chatInProgress = true
        )

        assertThat(ui.minDataHours).isEqualTo(24)
        assertThat(ui.dataCoverageHours).isWithin(0.001).of(26.4)
        assertThat(ui.analysisReady).isTrue()
        assertThat(ui.chatMessages).hasSize(2)
        assertThat(ui.chatMessages.last().role).isEqualTo("assistant")
        assertThat(ui.chatInProgress).isTrue()
    }

    @Test
    fun aiAnalysisMapping_marksOpenAiEndpointAsNoCloudBackend() {
        val state = state {
            cloudUrl = "https://api.openai.com/v1"
            dailyReportGeneratedAtTs = 1_800_000_000_000L
            dailyReportMetrics = listOf(
                io.aaps.copilot.ui.DailyReportMetricUi(
                    horizonMinutes = 30,
                    sampleCount = 64,
                    mae = 0.77,
                    rmse = 1.02,
                    mardPct = 11.2,
                    bias = 0.05
                )
            )
        }

        val ui = state.toAiAnalysisUiState()

        assertThat(ui.cloudConfigured).isFalse()
        assertThat(ui.localDailyMetrics).hasSize(1)
    }

    @Test
    fun aiAnalysisMapping_includesChatComposerState() {
        val now = System.currentTimeMillis()
        val state = state {
            latestDataAgeMinutes = 3
            staleDataMaxMinutes = 10
            aiAnalysisReady = true
            aiDataCoverageHours = 28.0
            aiMinDataHours = 24
        }

        val ui = state.toAiAnalysisUiState(
            chatMessages = listOf(
                AiChatMessageUi(
                    id = "m1",
                    role = "user",
                    text = "Что видно по тренду?",
                    ts = now,
                    attachments = listOf(
                        AiChatAttachmentUi(
                            id = "a1",
                            name = "trend.png",
                            kind = "image",
                            sizeLabel = "240 KB"
                        )
                    ),
                    voiceTranscript = true
                )
            ),
            chatInProgress = true,
            chatDraft = "Черновик",
            chatPendingAttachments = listOf(
                AiChatAttachmentUi(
                    id = "a2",
                    name = "report.txt",
                    kind = "file",
                    previewLabel = "summary"
                )
            ),
            chatVoiceRepliesEnabled = true,
            chatRecording = true,
            chatVoiceBusy = true,
            chatSpeaking = true
        )

        assertThat(ui.chatMessages).hasSize(1)
        assertThat(ui.chatMessages.first().voiceTranscript).isTrue()
        assertThat(ui.chatMessages.first().attachments.first().name).isEqualTo("trend.png")
        assertThat(ui.chatDraft).isEqualTo("Черновик")
        assertThat(ui.chatPendingAttachments).hasSize(1)
        assertThat(ui.chatVoiceRepliesEnabled).isTrue()
        assertThat(ui.chatRecording).isTrue()
        assertThat(ui.chatVoiceBusy).isTrue()
        assertThat(ui.chatSpeaking).isTrue()
        assertThat(ui.analysisReady).isTrue()
    }

    @Test
    fun clinicalReportMapping_keepsLocalSummaryAfterNetworkFailure() {
        val local = clinicalLocalReport()

        val ui = mapClinicalReportState(
            state = ClinicalReportState.Failed(
                requestId = "internal-request-id",
                local = local,
                reason = ClinicalReportFailureReason.NETWORK
            ),
            retryConfirmationRequested = false
        )

        assertThat(ui.summary24h?.days).isEqualTo(1)
        assertThat(ui.summary7d).isNotNull()
        assertThat(ui.summary30d).isNotNull()
        assertThat(ui.failure).isEqualTo(ClinicalReportFailureUi.NETWORK)
    }

    @Test
    fun clinicalReportMappingKeepsAapsCarbsDiagnosticSeparateFromFoodTotal() {
        val local = clinicalLocalReport().let { report ->
            report.copy(
                summary24h = report.summary24h?.copy(
                    totalCarbsG = 35.0,
                    aapsCarbsG = 99.0
                )
            )
        }

        val summary = checkNotNull(
            mapClinicalReportState(
                state = ClinicalReportState.LocalReady("diagnostic-carbs", local),
                retryConfirmationRequested = false
            ).summary24h
        )
        assertThat(summary.realCarbs).isEqualTo(35.0)
        assertThat(summary.aapsCarbs).isEqualTo(99.0)
    }

    @Test
    fun clinicalReportMappingExposesCarbohydrateEnergyAndActivityExpenditure() {
        val local = clinicalLocalReport().let { report ->
            val period = checkNotNull(report.summary24h).copy(
                totalCarbsG = 75.0,
                activity = ClinicalActivitySummary(activeCaloriesKcal = 321.0)
            )
            report.copy(summary24h = period)
        }

        val summary = checkNotNull(
            mapClinicalReportState(
                state = ClinicalReportState.LocalReady("energy-metrics", local),
                retryConfirmationRequested = false
            ).summary24h
        )

        assertThat(summary.carbohydrateEnergyKcal).isEqualTo(300.0)
        assertThat(summary.activeCaloriesKcal).isEqualTo(321.0)
    }

    @Test
    fun clinicalReportMapping_distinguishesDatasetCeilingFromInputAndRequestLimits() {
        val ui = mapClinicalReportState(
            state = ClinicalReportState.Failed(
                requestId = "dataset-too-large",
                local = clinicalLocalReport(),
                reason = ClinicalReportFailureReason.DATASET_TOO_LARGE
            ),
            retryConfirmationRequested = false
        )

        assertThat(ui.failure).isEqualTo(ClinicalReportFailureUi.DATASET_TOO_LARGE)
        assertThat(mapClinicalFailureReason(ClinicalReportFailureReason.INVALID_INPUT.name))
            .isEqualTo(ClinicalReportFailureUi.INVALID_INPUT)
        assertThat(mapClinicalFailureReason(ClinicalReportFailureReason.REQUEST_TOO_LARGE.name))
            .isEqualTo(ClinicalReportFailureUi.REQUEST_TOO_LARGE)
    }

    @Test
    fun clinicalReportMapping_preservesStageLocalProgressAndReductionLevel() {
        val ui = mapClinicalReportState(
            state = ClinicalReportState.Uploading(
                requestId = "reducing",
                local = clinicalLocalReport(),
                completed = 1,
                total = 3,
                stage = ClinicalOpenAiProgressStage.REDUCING,
                level = 2
            ),
            retryConfirmationRequested = false
        )

        assertThat(ui.phase).isEqualTo(ClinicalReportPhaseUi.UPLOADING)
        assertThat(ui.progressStage).isEqualTo(ClinicalReportProgressStageUi.REDUCING)
        assertThat(ui.progressLevel).isEqualTo(2)
        assertThat(ui.completedChunks).isEqualTo(1)
        assertThat(ui.totalChunks).isEqualTo(3)
        assertThat(ui.progress).isWithin(0.0001f).of(1f / 3f)
    }

    @Test
    fun clinicalReportMapping_mapsEveryProgressStageToDistinctUiStage() {
        val expected = listOf(
            ClinicalOpenAiProgressStage.PREPARING to
                ClinicalReportProgressStageUi.PREPARING,
            ClinicalOpenAiProgressStage.ANALYZING_CHUNK to
                ClinicalReportProgressStageUi.ANALYZING,
            ClinicalOpenAiProgressStage.REDUCING to
                ClinicalReportProgressStageUi.REDUCING,
            ClinicalOpenAiProgressStage.SYNTHESIZING to
                ClinicalReportProgressStageUi.SYNTHESIZING,
            ClinicalOpenAiProgressStage.VALIDATING to
                ClinicalReportProgressStageUi.VALIDATING,
            ClinicalOpenAiProgressStage.COMPLETED to
                ClinicalReportProgressStageUi.COMPLETED
        )

        val actual = expected.map { (stage, _) ->
            mapClinicalReportState(
                state = ClinicalReportState.Uploading(
                    requestId = stage.name,
                    local = clinicalLocalReport(),
                    completed = 0,
                    total = 1,
                    stage = stage,
                    level = 0
                ),
                retryConfirmationRequested = false
            ).progressStage
        }

        assertThat(actual).containsExactlyElementsIn(expected.map { it.second }).inOrder()
        assertThat(actual.distinct()).hasSize(expected.size)
    }

    @Test
    fun clinicalReportMapping_keepsLocalSummaryAfterEveryFailureCancelAndUnknown() {
        val local = clinicalLocalReport()

        ClinicalReportFailureReason.entries.forEach { reason ->
            val ui = mapClinicalReportState(
                state = ClinicalReportState.Failed(
                    requestId = "request-$reason",
                    local = local,
                    reason = reason
                ),
                retryConfirmationRequested = reason ==
                    ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME
            )

            assertThat(ui.summary7d).isNotNull()
            assertThat(ui.summary30d).isNotNull()
        }

        val cancelled = mapClinicalReportState(
            state = ClinicalReportState.Cancelled(
                requestId = "cancelled-request",
                local = local
            ),
            retryConfirmationRequested = false
        )
        assertThat(cancelled.summary7d).isNotNull()
        assertThat(cancelled.summary30d).isNotNull()
        assertThat(cancelled.phase).isEqualTo(ClinicalReportPhaseUi.CANCELLED)

        val unknown = mapClinicalReportState(
            state = ClinicalReportState.Failed(
                requestId = "unknown-request",
                local = local,
                reason = ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME
            ),
            retryConfirmationRequested = true
        )
        assertThat(unknown.phase).isEqualTo(ClinicalReportPhaseUi.UNKNOWN_OUTCOME)
        assertThat(unknown.showRetryConfirmation).isTrue()
    }

    @Test
    fun clinicalReportMapping_hidesLowCoverageGlucoseButKeepsTherapyTotals() {
        val lowCoverage = clinicalPeriodSummary(days = 7).copy(
            coveragePct = 24.9,
            meanMmol = 7.5,
            medianMmol = 7.0,
            coefficientOfVariationPct = 32.0,
            timeBelow4Pct = 1.0,
            timeInRangePct = 80.0,
            timeAboveRangePct = 19.0,
            totalInsulinU = 42.5,
            totalCarbsG = 315.0,
            quality = ClinicalDataQuality(
                expectedBuckets = 2_016,
                coveredBuckets = 502,
                missingBuckets = 1_514,
                maxGapMinutes = 4_320
            )
        )

        val ui = mapClinicalReportState(
            state = ClinicalReportState.LocalReady(
                requestId = "internal-request",
                local = clinicalLocalReport().copy(
                    summary7d = lowCoverage,
                    summary30d = lowCoverage.copy(days = 30)
                )
            ),
            retryConfirmationRequested = false
        )

        assertThat(ui.summary7d?.coveragePct).isEqualTo(24.9)
        assertThat(ui.summary7d?.meanGlucose).isNull()
        assertThat(ui.summary7d?.medianGlucose).isNull()
        assertThat(ui.summary7d?.variability).isNull()
        assertThat(ui.summary7d?.belowRange).isNull()
        assertThat(ui.summary7d?.inRange).isNull()
        assertThat(ui.summary7d?.aboveRange).isNull()
        assertThat(ui.summary7d?.recordedInsulin).isEqualTo(42.5)
        assertThat(ui.summary7d?.realCarbs).isEqualTo(315.0)
        assertThat(ui.summary7d?.maxGapMinutes).isEqualTo(4320)
        assertThat(ui.summary7d?.quality).isEqualTo(ClinicalLocalDataQualityUi.INSUFFICIENT)
    }

    @Test
    fun clinicalReportMapping_mapsEnumsAndEvidenceWithoutRawModelText() {
        val ui = mapClinicalReportState(
            state = ClinicalReportState.Complete(
                requestId = "private-request-id",
                local = clinicalLocalReport(),
                report = clinicalAdvisoryReport(),
                metadata = clinicalMetadata()
            ),
            retryConfirmationRequested = false
        )

        assertThat(ui.phase).isEqualTo(ClinicalReportPhaseUi.COMPLETE)
        assertThat(ui.complete?.patterns).hasSize(1)
        assertThat(ui.complete?.patterns?.first()?.topic)
            .isEqualTo(ClinicalReportTextKey.OBSERVATION_GLUCOSE_VARIABILITY)
        assertThat(ui.complete?.patterns?.first()?.evidence?.value).isEqualTo(31.4)
        assertThat(ui.complete?.patterns?.first()?.evidence?.unit)
            .isEqualTo(ClinicalNumericUnitUi.PERCENT)
        assertThat(ui.complete?.recommendations?.first()?.topic)
            .isEqualTo(ClinicalReportTextKey.DISCUSSION_ISF_CR_REVIEW)
        assertThat(ui.complete?.recommendations?.first()?.linkedEvidence).hasSize(1)
        assertThat(ui.complete?.recommendations?.first()?.linkedEvidence?.first())
            .isEqualTo(ui.complete?.patterns?.first())
        assertThat(mapClinicalPatternTopic("FUTURE_UNKNOWN_TOPIC")).isNull()
        assertThat(mapClinicalFailureReason("FUTURE_UNKNOWN_REASON"))
            .isEqualTo(ClinicalReportFailureUi.OTHER)
    }

    @Test
    fun clinicalReportMapping_preservesPersistedHistoricalProviderMetadata() {
        val ui = mapClinicalReportState(
            state = ClinicalReportState.Complete(
                requestId = "historical-request",
                local = clinicalLocalReport(),
                report = clinicalAdvisoryReport(),
                metadata = clinicalMetadata().copy(
                    providerId = ClinicalAiProviderId.ANTHROPIC,
                    requestedProviderId = ClinicalAiProviderId.ANTHROPIC
                )
            ),
            retryConfirmationRequested = false
        )

        assertThat(ui.complete?.metadata?.providerId)
            .isEqualTo(ClinicalAiProviderId.ANTHROPIC)
    }

    @Test
    fun clinicalDisclosureMappingExposesOnlyCompatibleHostname() {
        val compatible = ClinicalAiProviderConfig.normalized(
            providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
            modelId = "private-model",
            endpoint = "https://Gateway.Example.com:443/private/path",
            compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
        ).toClinicalReportDisclosureUi()
        val native = ClinicalAiProviderConfig.normalized(
            providerId = ClinicalAiProviderId.GEMINI,
            modelId = "gemini-3.6-flash"
        ).toClinicalReportDisclosureUi()

        assertThat(compatible.providerId).isEqualTo(ClinicalAiProviderId.OPENAI_COMPATIBLE)
        assertThat(compatible.model).isEqualTo("private-model")
        assertThat(compatible.endpointHost).isEqualTo("gateway.example.com")
        assertThat(compatible.configIdentity).isEqualTo(
            ClinicalAiProviderConfig.normalized(
                providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
                modelId = "private-model",
                endpoint = "https://Gateway.Example.com:443/private/path",
                compatibleProtocol = OpenAiCompatibleProtocol.RESPONSES
            ).executionIdentity()
        )
        assertThat(compatible.toString()).doesNotContain("https://")
        assertThat(compatible.toString()).doesNotContain("/private/path")
        assertThat(native.endpointHost).isNull()
    }

    @Test
    fun clinicalReportMapping_rejectsRecommendationWithHiddenOrOutOfRangeEvidence() {
        val valid = clinicalAdvisoryReport().patterns.single()
        val invalid = valid.copy(evidenceValue = Double.NaN)
        val report = clinicalAdvisoryReport().copy(
            patterns = listOf(valid, invalid),
            recommendations = listOf(
                clinicalAdvisoryReport().recommendations.single(),
                clinicalAdvisoryReport().recommendations.single().copy(
                    careTeamDiscussionTopic =
                        ClinicalCareTeamDiscussionTopic.DATA_QUALITY_REVIEW,
                    evidenceFindingIndices = listOf(1)
                ),
                clinicalAdvisoryReport().recommendations.single().copy(
                    careTeamDiscussionTopic =
                        ClinicalCareTeamDiscussionTopic.SENSOR_RELIABILITY,
                    evidenceFindingIndices = listOf(5)
                )
            )
        )

        val ui = mapClinicalReportState(
            state = ClinicalReportState.Complete(
                requestId = "internal",
                local = clinicalLocalReport(),
                report = report,
                metadata = clinicalMetadata()
            ),
            retryConfirmationRequested = false
        )

        assertThat(ui.complete?.patterns).hasSize(1)
        assertThat(ui.complete?.recommendations).hasSize(1)
        assertThat(ui.complete?.recommendations?.single()?.linkedEvidence).hasSize(1)
        assertThat(ui.toString()).doesNotContain("evidenceFindingIndices")
    }

    @Test
    fun clinicalReportMapping_sendAndRetryLocalRequireVisibleSummary() {
        val failedWithoutLocal = mapClinicalReportState(
            state = ClinicalReportState.Failed(
                requestId = "failed",
                local = null,
                reason = ClinicalReportFailureReason.NETWORK
            ),
            retryConfirmationRequested = false
        )
        val cancelledWithoutLocal = mapClinicalReportState(
            state = ClinicalReportState.Cancelled("cancelled", null),
            retryConfirmationRequested = false
        )
        val unknownWithoutLocal = mapClinicalReportState(
            state = ClinicalReportState.Failed(
                requestId = "unknown",
                local = null,
                reason = ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME
            ),
            retryConfirmationRequested = true
        )

        listOf(failedWithoutLocal, cancelledWithoutLocal, unknownWithoutLocal).forEach {
            assertThat(it.canSend).isFalse()
            assertThat(it.canRetryLocalPreparation).isTrue()
        }
        assertThat(unknownWithoutLocal.canRequestGuardedRetry).isFalse()
        assertThat(unknownWithoutLocal.showRetryConfirmation).isFalse()

        val unknownWithLocal = mapClinicalReportState(
            state = ClinicalReportState.Failed(
                requestId = "unknown",
                local = clinicalLocalReport(),
                reason = ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME
            ),
            retryConfirmationRequested = true,
            retryFeedback = ClinicalReportRetryFeedbackUi.PENDING
        )
        assertThat(unknownWithLocal.canSend).isFalse()
        assertThat(unknownWithLocal.canRetryLocalPreparation).isFalse()
        assertThat(unknownWithLocal.canRequestGuardedRetry).isTrue()
        assertThat(unknownWithLocal.showRetryConfirmation).isTrue()
        assertThat(unknownWithLocal.retryFeedback)
            .isEqualTo(ClinicalReportRetryFeedbackUi.PENDING)
    }

    @Test
    fun clinicalReportMapping_refreshedUnknownLocalStaysGuardedAndNeverBecomesSendable() {
        val refreshedLocal = clinicalLocalReport().copy(
            generatedAt = 1_900_000_000_000L
        )

        val ui = mapClinicalReportState(
            state = ClinicalReportState.Failed(
                requestId = "guarded-request",
                local = refreshedLocal,
                reason = ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME
            ),
            retryConfirmationRequested = false
        )

        assertThat(ui.phase).isEqualTo(ClinicalReportPhaseUi.UNKNOWN_OUTCOME)
        assertThat(ui.summary7d).isNotNull()
        assertThat(ui.summary30d).isNotNull()
        assertThat(ui.canSend).isFalse()
        assertThat(ui.canRetryLocalPreparation).isFalse()
        assertThat(ui.canRequestGuardedRetry).isTrue()
    }

    @Test
    fun clinicalReportMapping_sendRequiresLocalForEachRetryableTerminalState() {
        val local = clinicalLocalReport()
        val states = listOf(
            ClinicalReportState.LocalReady("local", local),
            ClinicalReportState.Failed(
                "failed",
                local,
                ClinicalReportFailureReason.NETWORK
            ),
            ClinicalReportState.Cancelled("cancelled", local)
        )

        states.forEach { state ->
            val ui = mapClinicalReportState(state, retryConfirmationRequested = false)
            assertThat(ui.canSend).isTrue()
            assertThat(ui.canRetryLocalPreparation).isFalse()
        }
    }

    @Test
    fun clinicalReportMapping_exposesExactRemoteEventPreviewBeforeSend() {
        val preview = """{"ev24":[{"type":"CUSTOM","note":"exact note"}],"ev7":[],"ev30":[]}"""

        val ui = mapClinicalReportState(
            ClinicalReportState.LocalReady(
                "local",
                clinicalLocalReport().copy(remoteEventPreviewJson = preview)
            ),
            retryConfirmationRequested = false
        )

        assertThat(ui.canSend).isTrue()
        assertThat(ui.remoteEventPreviewText).isEqualTo(preview)
        assertThat(ui.remoteEventPreviewText).doesNotContain("internal-request-hash")
    }

    @Test
    fun clinicalReportMapping_isIndependentOfDefaultLocaleAndHidesNonFiniteValues() {
        val previous = java.util.Locale.getDefault()
        val local = clinicalLocalReport().copy(
            summary7d = clinicalPeriodSummary(7).copy(
                meanMmol = Double.NaN,
                medianMmol = Double.POSITIVE_INFINITY
            )
        )
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            val englishIdentity = mapClinicalReportState(
                ClinicalReportState.LocalReady("internal", local),
                retryConfirmationRequested = false
            )
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ru-RU"))
            val russianIdentity = mapClinicalReportState(
                ClinicalReportState.LocalReady("internal", local),
                retryConfirmationRequested = false
            )

            assertThat(russianIdentity).isEqualTo(englishIdentity)
            assertThat(russianIdentity.summary7d?.meanGlucose).isNull()
            assertThat(russianIdentity.summary7d?.medianGlucose).isNull()
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun clinicalReportMapping_hidesInvalidGeneratedTimestamp() {
        val ui = mapClinicalReportState(
            state = ClinicalReportState.Complete(
                requestId = "internal",
                local = clinicalLocalReport().copy(generatedAt = Long.MAX_VALUE),
                report = clinicalAdvisoryReport(),
                metadata = clinicalMetadata()
            ),
            retryConfirmationRequested = false
        )

        assertThat(ui.complete?.metadata?.generatedAtTs).isNull()
    }

    @Test
    fun clinicalReportMapping_exposesNoRawIdsKeyPayloadOrFingerprint() {
        val ui = mapClinicalReportState(
            state = ClinicalReportState.Complete(
                requestId = "private-request-id",
                local = clinicalLocalReport().copy(requestHash = "private-local-hash"),
                report = clinicalAdvisoryReport(),
                metadata = clinicalMetadata().copy(
                    requestHash = "private-remote-hash",
                    systemFingerprint = "private-fingerprint"
                )
            ),
            retryConfirmationRequested = false
        )

        val renderedState = ui.toString()
        assertThat(renderedState).doesNotContain("private-request-id")
        assertThat(renderedState).doesNotContain("private-local-hash")
        assertThat(renderedState).doesNotContain("private-remote-hash")
        assertThat(renderedState).doesNotContain("private-fingerprint")
        assertThat(renderedState).doesNotContain("sk-private-key")
        assertThat(renderedState).doesNotContain("raw-medical-payload")

        val uiFieldNames = listOf(
            ClinicalReportUiState::class.java,
            ClinicalPeriodSummaryUi::class.java,
            ClinicalCompleteReportUi::class.java,
            ClinicalReportMetadataUi::class.java,
            ClinicalFindingUi::class.java,
            ClinicalEvidenceUi::class.java,
            ClinicalRecommendationUi::class.java
        ).flatMap { type -> type.declaredFields.map { it.name.lowercase() } }
        assertThat(uiFieldNames.joinToString()).doesNotContain("requestid")
        assertThat(uiFieldNames.joinToString()).doesNotContain("hash")
        assertThat(uiFieldNames.joinToString()).doesNotContain("payload")
        assertThat(uiFieldNames.joinToString()).doesNotContain("apikey")
        assertThat(uiFieldNames.joinToString()).doesNotContain("dataset")
        assertThat(uiFieldNames.joinToString()).doesNotContain("index")
    }

    @Test
    fun probableMealWindowsMapToAnalyticsAndClinicalReport() {
        val window = ProbableEatingWindow(
            medianMinuteOfDay = 510,
            startMinuteOfDay = 480,
            endMinuteOfDay = 540,
            iqrMinutes = 60,
            supportDays = 9,
            lookbackDays = 14,
            episodeCount = 10,
            enteredEpisodeCount = 8,
            uamEpisodeCount = 2,
            confidencePct = 64.3
        )
        val recentWindow = window.copy(
            medianMinuteOfDay = 495,
            lookbackDays = 7,
            supportDays = 5,
            episodeCount = 5,
            enteredEpisodeCount = 4,
            uamEpisodeCount = 1,
            confidencePct = 71.4
        )
        val analytics = state {}.toAnalyticsUiState(
            probableMealWindows = listOf(window),
            recentProbableMealWindows = listOf(recentWindow)
        )
        val local = clinicalLocalReport().let { report ->
            report.copy(
                summary30d = report.summary30d.copy(probableMealWindows = listOf(window))
            )
        }
        val clinical = mapClinicalReportState(
            state = ClinicalReportState.LocalReady("request", local),
            retryConfirmationRequested = false
        )

        assertThat(analytics.probableMealWindows.single().medianMinuteOfDay).isEqualTo(510)
        assertThat(analytics.probableMealWindows.single().supportDays).isEqualTo(9)
        assertThat(analytics.recentProbableMealWindows.single().medianMinuteOfDay).isEqualTo(495)
        assertThat(analytics.recentProbableMealWindows.single().lookbackDays).isEqualTo(7)
        assertThat(clinical.summary30d?.probableMealWindows).hasSize(1)
    }

    @Test
    fun clinicalReportStateMakesEmptyLegacyAiScreenRenderableOffline() {
        val clinical = mapClinicalReportState(
            state = ClinicalReportState.LocalReady(
                requestId = "internal-request",
                local = clinicalLocalReport()
            ),
            retryConfirmationRequested = false
        )

        val ui = AiAnalysisUiState(
            loadState = ScreenLoadState.EMPTY,
            isStale = true
        ).withClinicalReport(clinical)

        assertThat(ui.loadState).isEqualTo(ScreenLoadState.READY)
        assertThat(ui.clinicalReport.summary7d).isNotNull()
    }

    @Test
    fun overviewMapping_disablesManualCycleDuringPowerSave() {
        val state = state {
            latestGlucoseMmol = 7.1
            latestDataAgeMinutes = 1
            staleDataMaxMinutes = 10
            powerSaveUntilMs = System.currentTimeMillis() + PowerSaveController.ONE_HOUR_MS
        }

        val ui = state.toOverviewUiState(isProMode = false)

        assertThat(ui.powerSaveActive).isTrue()
        assertThat(ui.powerSaveIndefinite).isFalse()
        assertThat(ui.powerSaveRemainingText).isNotEmpty()
        assertThat(ui.canRunCycleNow).isFalse()
    }

    private fun acceptedCopilotSnapshot(
        isf: Double = 3.3,
        cr: Double = 24.5,
        timestamp: Long = 1_800_000_000_000L
    ): SensitivityRuntimeSnapshot = SensitivityRuntimeSnapshot(
        settingsRevision = 40L,
        forecastCycleId = "accepted-copilot-cycle",
        timestamp = timestamp,
        isf = SensitivityMetricDecision(
            requested = SensitivitySourcePreference.COPILOT,
            resolved = SensitivityResolvedSource.COPILOT_NATIVE,
            rawAaps = null,
            rawEvidence = null,
            rawCopilot = isf,
            blended = null,
            effective = isf,
            confidence = 0.7,
            fallbackReason = null
        ),
        cr = SensitivityMetricDecision(
            requested = SensitivitySourcePreference.COPILOT,
            resolved = SensitivityResolvedSource.COPILOT_NATIVE,
            rawAaps = null,
            rawEvidence = null,
            rawCopilot = cr,
            blended = null,
            effective = cr,
            confidence = 0.7,
            fallbackReason = null
        )
    )

    private fun acceptedAapsSnapshot(
        isf: Double,
        cr: Double,
        timestamp: Long = 1_800_000_000_000L
    ): SensitivityRuntimeSnapshot = SensitivityRuntimeSnapshot(
        settingsRevision = 41L,
        forecastCycleId = "accepted-aaps-cycle",
        timestamp = timestamp,
        isf = SensitivityMetricDecision(
            requested = SensitivitySourcePreference.AAPS,
            resolved = SensitivityResolvedSource.AAPS,
            rawAaps = isf,
            rawEvidence = null,
            rawCopilot = 4.5,
            blended = null,
            effective = isf,
            confidence = 1.0,
            fallbackReason = null
        ),
        cr = SensitivityMetricDecision(
            requested = SensitivitySourcePreference.AAPS,
            resolved = SensitivityResolvedSource.AAPS,
            rawAaps = cr,
            rawEvidence = null,
            rawCopilot = 26.1,
            blended = null,
            effective = cr,
            confidence = 1.0,
            fallbackReason = null
        )
    )

    private fun acceptedFallbackSnapshot(): SensitivityRuntimeSnapshot {
        val timestamp = 1_800_000_000_000L
        return SensitivityRuntimeSnapshot(
            settingsRevision = 42L,
            forecastCycleId = "accepted-overview-cycle",
            timestamp = timestamp,
            isf = SensitivityMetricDecision(
                requested = SensitivitySourcePreference.AAPS,
                resolved = SensitivityResolvedSource.COPILOT_NATIVE,
                rawAaps = null,
                rawEvidence = 4.2,
                rawCopilot = 3.1,
                blended = null,
                effective = 3.1,
                confidence = 0.61,
                fallbackReason = "aaps_missing;evidence_quality_failed"
            ),
            cr = SensitivityMetricDecision(
                requested = SensitivitySourcePreference.EVIDENCE,
                resolved = SensitivityResolvedSource.EVIDENCE_BLEND,
                rawAaps = 9.0,
                rawEvidence = 11.0,
                rawCopilot = 10.0,
                blended = 10.8,
                effective = 10.8,
                confidence = 0.8,
                fallbackReason = null
            )
        )
    }

    private fun acceptedDiagnostics(
        snapshot: SensitivityRuntimeSnapshot
    ): AcceptedSensitivityCandidateDiagnostics = AcceptedSensitivityCandidateDiagnostics(
        settingsRevision = snapshot.settingsRevision,
        forecastCycleId = snapshot.forecastCycleId,
        snapshotTimestamp = snapshot.timestamp,
        freshnessMs = 60L * 60L * 1_000L,
        isfCandidates = SensitivityCandidates(
            aaps = SensitivityCandidate(
                value = null,
                timestamp = null,
                confidence = 0.0,
                qualityPassed = false,
                sampleCount = 0,
                coverage = 0.0,
                unavailableReason = "missing"
            ),
            evidence = SensitivityCandidate(
                value = snapshot.isf.rawEvidence,
                timestamp = snapshot.timestamp - 2 * 60_000L,
                confidence = 0.72,
                qualityPassed = false,
                sampleCount = 18,
                coverage = 0.72,
                unavailableReason = "quality_failed"
            ),
            copilot = SensitivityCandidate(
                value = snapshot.isf.rawCopilot,
                timestamp = snapshot.timestamp,
                confidence = snapshot.isf.confidence,
                qualityPassed = true,
                sampleCount = 1,
                coverage = 1.0,
                unavailableReason = null
            )
        ),
        crCandidates = SensitivityCandidates(
            aaps = SensitivityCandidate(
                value = snapshot.cr.rawAaps,
                timestamp = snapshot.timestamp - 60_000L,
                confidence = 0.9,
                qualityPassed = true,
                sampleCount = 1,
                coverage = 1.0,
                unavailableReason = null
            ),
            evidence = SensitivityCandidate(
                value = snapshot.cr.rawEvidence,
                timestamp = snapshot.timestamp - 2 * 60_000L,
                confidence = snapshot.cr.confidence,
                qualityPassed = true,
                sampleCount = 24,
                coverage = 0.8,
                unavailableReason = null
            ),
            copilot = SensitivityCandidate(
                value = snapshot.cr.rawCopilot,
                timestamp = snapshot.timestamp,
                confidence = 0.7,
                qualityPassed = true,
                sampleCount = 1,
                coverage = 1.0,
                unavailableReason = null
            )
        )
    )

    private fun clinicalLocalReport(): ClinicalLocalReport = ClinicalLocalReport(
        summary24h = clinicalPeriodSummary(days = 1),
        summary7d = clinicalPeriodSummary(days = 7),
        summary30d = clinicalPeriodSummary(days = 30),
        requestHash = "internal-request-hash",
        generatedAt = 1_800_000_000_000L,
        zoneId = "UTC"
    )

    private fun clinicalPeriodSummary(days: Int): ClinicalPeriodSummary = ClinicalPeriodSummary(
        days = days,
        fromTs = 1_800_000_000_000L - days * 24L * 60L * 60L * 1_000L,
        throughTs = 1_800_000_000_000L,
        coveragePct = 96.5,
        meanMmol = 7.2,
        medianMmol = 6.8,
        coefficientOfVariationPct = 31.4,
        timeBelow4Pct = 2.1,
        timeInRangePct = 78.6,
        timeAboveRangePct = 19.3,
        totalInsulinU = 42.5,
        totalCarbsG = 315.0,
        meanTargetMmol = 5.8,
        weekdayPattern = emptyList(),
        weekendPattern = emptyList(),
        quality = ClinicalDataQuality(
            expectedBuckets = days * 288,
            coveredBuckets = (days * 288 * 0.965).toInt(),
            missingBuckets = (days * 288 * 0.035).toInt(),
            maxGapMinutes = 15
        )
    )

    private fun clinicalAdvisoryReport() = ClinicalAdvisoryReport(
        summary7dStatus = ClinicalSummaryStatus.STABLE,
        summary30dStatus = ClinicalSummaryStatus.HIGH_VARIABILITY,
        dataQuality = listOf(ClinicalDataQualityFlag.PARTIAL_COVERAGE),
        patterns = listOf(
            ClinicalFinding(
                topic = ClinicalPatternTopic.GLUCOSE_VARIABILITY,
                period = ClinicalEvidencePeriod.LAST_7_DAYS,
                direction = ClinicalPatternDirection.MIXED,
                confidence = ClinicalFindingConfidence.HIGH,
                timeBand = ClinicalTimeBand.ALL_DAY,
                evidenceMetric = ClinicalEvidenceMetric.COEFFICIENT_OF_VARIATION_PCT,
                evidenceValue = 31.4
            )
        ),
        safetyObservations = listOf(ClinicalSafetyObservation.HIGH_VARIABILITY_PATTERN),
        recommendations = listOf(
            ClinicalRecommendation(
                careTeamDiscussionTopic = ClinicalCareTeamDiscussionTopic.ISF_CR_REVIEW,
                priority = ClinicalAdvisoryPriority.MEDIUM,
                evidenceFindingIndices = listOf(0),
                period = ClinicalEvidencePeriod.LAST_7_DAYS
            )
        ),
        careTeamQuestions = listOf(ClinicalCareTeamQuestion.ISF_CR_CONTEXT)
    )

    private fun clinicalMetadata() = ClinicalOpenAiMetadata(
        model = "gpt-5-mini-2025-08-07",
        requestedModel = "gpt-5-mini-2025-08-07",
        systemFingerprint = "private-fingerprint",
        schemaName = "clinical_advisory_report",
        schemaVersion = 4,
        datasetSchemaVersion = 3,
        requestHash = "private-remote-hash",
        chunkCount = 1,
        usedSynthesis = false
    )
}
