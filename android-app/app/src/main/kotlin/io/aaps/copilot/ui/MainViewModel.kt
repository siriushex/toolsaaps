package io.aaps.copilot.ui

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.ClipData
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.security.KeyChain
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.R
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.config.ClinicalAiConfigState
import io.aaps.copilot.config.ClinicalAiEndpointPolicy
import io.aaps.copilot.config.ClinicalAiEndpointValidation
import io.aaps.copilot.config.ClinicalAiModelCatalog
import io.aaps.copilot.config.ClinicalAiModelIdPolicy
import io.aaps.copilot.config.ClinicalAiProviderConfig
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.config.OpenAiCompatibleProtocol
import io.aaps.copilot.config.UiStyle
import io.aaps.copilot.config.UamExportUiModeCommand
import io.aaps.copilot.config.UamExportUiModePolicy
import io.aaps.copilot.config.isCopilotCloudBackendEndpoint
import io.aaps.copilot.config.resolvedNightscoutUrl
import io.aaps.copilot.config.sensitivityRuntimeIdentity
import io.aaps.copilot.data.local.TelemetrySampleSelector
import io.aaps.copilot.data.local.entity.ActionCommandEntity
import io.aaps.copilot.data.repository.AapsAutoConnectRepository
import io.aaps.copilot.data.repository.AapsBolusLaunchResult
import io.aaps.copilot.data.repository.AtomicInsulinRuntimePacketContract
import io.aaps.copilot.data.local.entity.AuditLogEntity
import io.aaps.copilot.data.local.entity.BaselinePointEntity
import io.aaps.copilot.data.local.entity.CircadianPatternSnapshotEntity
import io.aaps.copilot.data.local.entity.CircadianSlotStatEntity
import io.aaps.copilot.data.local.entity.CircadianTransitionStatEntity
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.PatternWindowEntity
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import io.aaps.copilot.data.local.entity.ProfileEstimateEntity
import io.aaps.copilot.data.local.entity.ProfileSegmentEstimateEntity
import io.aaps.copilot.data.local.entity.RuleExecutionEntity
import io.aaps.copilot.data.local.entity.SyncStateEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.local.entity.UamInferenceEventEntity
import io.aaps.copilot.data.local.dao.PhysicalActivityMetricBucketProjection
import io.aaps.copilot.data.repository.CloudAnalysisHistoryUiModel
import io.aaps.copilot.data.repository.CloudAnalysisTrendUiModel
import io.aaps.copilot.data.repository.CloudJobsUiModel
import io.aaps.copilot.data.repository.CloudReplayUiModel
import io.aaps.copilot.data.repository.CircadianReplaySummary
import io.aaps.copilot.data.repository.ClinicalReportConfigurationChangedException
import io.aaps.copilot.data.repository.ClinicalReportDatasetBuilder
import io.aaps.copilot.data.repository.ClinicalOpenAiResult
import io.aaps.copilot.data.repository.ClinicalReportRunDisposition
import io.aaps.copilot.data.repository.ClinicalReportState
import io.aaps.copilot.data.repository.ClinicalPdfReportIdentity
import io.aaps.copilot.data.repository.ClinicalPdfReprepareResult
import io.aaps.copilot.data.repository.ClinicalPdfSourceCaptureResult
import io.aaps.copilot.data.repository.ClinicalPdfSourceLease
import io.aaps.copilot.data.repository.ClinicalPdfSourceLeaseResult
import io.aaps.copilot.data.repository.GlucoseAlertAudioProfiles
import io.aaps.copilot.data.repository.GlucoseAlertAudioSlot
import io.aaps.copilot.data.repository.GlucoseAlertBellAction
import io.aaps.copilot.data.repository.GlucoseAlertMuteOption
import io.aaps.copilot.data.repository.AlertsRepository
import io.aaps.copilot.data.repository.AlertHistoryWindow
import io.aaps.copilot.data.repository.CalibrationModelAuthority
import io.aaps.copilot.data.repository.ManualMealSubmission
import io.aaps.copilot.data.repository.CalibrationAuthorityStateCodec
import io.aaps.copilot.data.repository.GlucoseSanitizer
import io.aaps.copilot.data.repository.AcceptedCalibrationAuthority
import io.aaps.copilot.data.repository.AcceptedSensitivityCandidateDiagnostics
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_AUTHORITY_TOKEN_KEY
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_MODEL_FINGERPRINT_KEY
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_MODEL_ID_KEY
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_PREPARED_AT_KEY
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_SESSION_KEY
import io.aaps.copilot.data.repository.ACCEPTED_CALIBRATION_NONE
import io.aaps.copilot.data.repository.CALIBRATION_AUTHORITY_SOURCE
import io.aaps.copilot.data.repository.CALIBRATION_AUTHORITY_TOKEN_KEY
import io.aaps.copilot.data.repository.ForecastSnapshotResolver
import io.aaps.copilot.data.repository.TherapySanitizer
import io.aaps.copilot.data.repository.TargetManagerLiveStatus
import io.aaps.copilot.config.TargetManagerTimingSetting
import io.aaps.copilot.config.withTargetManagerTiming
import io.aaps.copilot.data.repository.TargetManagerLiveStatusCodec
import io.aaps.copilot.data.repository.EventTimelineRepository
import io.aaps.copilot.data.repository.EventTimelineSources
import io.aaps.copilot.data.repository.DeliveryTrustTelemetryValue
import io.aaps.copilot.data.repository.deliveryDiagnosticEventsForWindow
import io.aaps.copilot.data.repository.deliveryDiagnosticLookbackFrom
import io.aaps.copilot.data.repository.ContextEventCommandResult
import io.aaps.copilot.data.repository.UamExportPolicy
import io.aaps.copilot.data.repository.AiChatAttachmentRequest
import io.aaps.copilot.data.repository.AiChatTurn
import io.aaps.copilot.data.repository.toDomain
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.eating.EatingEvidencePoint
import io.aaps.copilot.domain.eating.EatingWindowSnapshotPlanner
import io.aaps.copilot.domain.model.BloodGlucoseCheck
import io.aaps.copilot.domain.model.DayType
import io.aaps.copilot.domain.model.GlucoseCalibrationModel
import io.aaps.copilot.domain.model.PatternWindow
import io.aaps.copilot.domain.model.SafetySnapshot
import io.aaps.copilot.domain.isfcr.IsfCrRealtimeSnapshot
import io.aaps.copilot.domain.isfcr.IsfCrRuntimeMode
import io.aaps.copilot.domain.isfcr.PhysioContextTag
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventProfilePolicy
import io.aaps.copilot.domain.activity.PhysicalActivityBucket
import io.aaps.copilot.domain.activity.PhysicalActivityTelemetryPolicy
import io.aaps.copilot.domain.events.CompensationEventStatus
import io.aaps.copilot.domain.events.CompensationEventType
import io.aaps.copilot.domain.events.EventSeverity
import io.aaps.copilot.domain.events.EventSource
import io.aaps.copilot.domain.events.EventTimeline
import io.aaps.copilot.domain.events.CompensationEventManualPolicy
import io.aaps.copilot.domain.predict.UamInferenceState
import io.aaps.copilot.domain.predict.BaselineComparator
import io.aaps.copilot.domain.predict.AcceptedSensitivityTupleFreshness
import io.aaps.copilot.domain.predict.ForecastQualityEvaluator
import io.aaps.copilot.domain.predict.InsulinActionProfileId
import io.aaps.copilot.domain.predict.SensitivityResolvedSource
import io.aaps.copilot.domain.predict.SensitivityMetricKind
import io.aaps.copilot.domain.predict.SensitivityRuntimeConsumer
import io.aaps.copilot.domain.predict.SensitivityRuntimeFanOut
import io.aaps.copilot.domain.predict.SensitivityRuntimeSnapshot
import io.aaps.copilot.domain.predict.SensitivitySourcePreference
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_CYCLE_ID_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY
import io.aaps.copilot.domain.predict.ProfileEstimator
import io.aaps.copilot.domain.predict.ProfileEstimatorConfig
import io.aaps.copilot.domain.predict.TelemetrySignal
import io.aaps.copilot.domain.predict.UamTagCodec
import io.aaps.copilot.domain.predict.UamExportMode
import io.aaps.copilot.domain.profile.CalorieGoalMode
import io.aaps.copilot.domain.profile.EnergyProfilePolicy
import io.aaps.copilot.domain.profile.EnergyProfileSettings
import io.aaps.copilot.domain.profile.ProfileResolution
import io.aaps.copilot.domain.profile.ProfileViolation
import io.aaps.copilot.security.ClinicalAiCredentialStatus
import io.aaps.copilot.security.ServerAiConnectionState
import io.aaps.copilot.domain.rules.AdaptiveTargetControllerRule
import io.aaps.copilot.domain.target.BaseTargetSchedule
import io.aaps.copilot.domain.target.BaseTargetSchedulePolicy
import io.aaps.copilot.domain.target.CircadianAutoState
import io.aaps.copilot.domain.target.EffectiveTargetRuntimeGates
import io.aaps.copilot.domain.target.SensorTrustState
import io.aaps.copilot.domain.target.DeliveryTrustStateWireCodec
import io.aaps.copilot.domain.target.TargetManagerMode
import io.aaps.copilot.scheduler.WorkScheduler
import io.aaps.copilot.scheduler.ClinicalInputInvalidationSource
import io.aaps.copilot.service.LocalNightscoutServiceController
import io.aaps.copilot.service.ClinicalAiProviderCredentialState
import io.aaps.copilot.service.LocalNightscoutIdentityResetResult
import io.aaps.copilot.service.LocalNightscoutRuntimeReason
import io.aaps.copilot.service.LocalNightscoutRuntimeState
import io.aaps.copilot.service.LocalNightscoutSecretClipboard
import io.aaps.copilot.service.LocalNightscoutUpgradeMigrationGate
import io.aaps.copilot.service.LocalNightscoutTls
import io.aaps.copilot.service.PowerSaveController
import io.aaps.copilot.service.PowerSaveRuntimeState
import io.aaps.copilot.ui.foundation.screens.AnalyticsUiState
import io.aaps.copilot.ui.foundation.screens.AlertsUiState
import io.aaps.copilot.ui.foundation.screens.AiAnalysisUiState
import io.aaps.copilot.ui.foundation.screens.AiCredentialUiState
import io.aaps.copilot.ui.foundation.screens.AiChatAttachmentUi
import io.aaps.copilot.ui.foundation.screens.AiChatMessageUi
import io.aaps.copilot.ui.foundation.screens.AppHealthUiState
import io.aaps.copilot.ui.foundation.screens.AuditItemUi
import io.aaps.copilot.ui.foundation.screens.AuditWindowUi
import io.aaps.copilot.ui.foundation.screens.AuditUiState
import io.aaps.copilot.ui.foundation.screens.BaseTargetBannerUiState
import io.aaps.copilot.ui.foundation.screens.BaseTargetSchedulePresentation
import io.aaps.copilot.ui.foundation.screens.CircadianPatternSectionUi
import io.aaps.copilot.ui.foundation.screens.CircadianReplayBucketUi
import io.aaps.copilot.ui.foundation.screens.CircadianReplayMetricUi
import io.aaps.copilot.ui.foundation.screens.CircadianReplaySummaryUi
import io.aaps.copilot.ui.foundation.screens.CircadianReplayWindowUi
import io.aaps.copilot.ui.foundation.screens.ChartPointUi
import io.aaps.copilot.ui.foundation.screens.ClinicalReportRetryFeedbackUi
import io.aaps.copilot.ui.foundation.screens.ClinicalAiConnectionTestPhaseUi
import io.aaps.copilot.ui.foundation.screens.ClinicalAiConnectionTestUiState
import io.aaps.copilot.ui.foundation.screens.ClinicalAiProtocolOptionUi
import io.aaps.copilot.ui.foundation.screens.ClinicalAiProviderOptionUi
import io.aaps.copilot.ui.foundation.screens.ClinicalAiSettingsLabels
import io.aaps.copilot.ui.foundation.screens.ClinicalAiSettingsUiState
import io.aaps.copilot.ui.foundation.screens.ClinicalAiSettingsValidationErrorUi
import io.aaps.copilot.ui.foundation.screens.ClinicalReportDisclosureUi
import io.aaps.copilot.ui.foundation.screens.ClinicalPdfExportTicketUi
import io.aaps.copilot.ui.foundation.screens.ClinicalPdfExportUiState
import io.aaps.copilot.ui.foundation.screens.ClinicalReportUiState
import io.aaps.copilot.ui.foundation.screens.ForecastLayerState
import io.aaps.copilot.ui.foundation.screens.ForecastRangeUi
import io.aaps.copilot.ui.foundation.screens.ForecastUiState
import io.aaps.copilot.ui.foundation.screens.OverviewUiState
import io.aaps.copilot.ui.foundation.screens.MetricRuntimeAvailabilityUi
import io.aaps.copilot.ui.foundation.screens.PhysioTagJournalItemUi
import io.aaps.copilot.ui.foundation.screens.SafetyUiState
import io.aaps.copilot.ui.foundation.screens.SensorLagTimelineSegmentUi
import io.aaps.copilot.ui.foundation.screens.SettingsUiState
import io.aaps.copilot.ui.foundation.screens.UamUiState
import io.aaps.copilot.ui.foundation.screens.UamExportControlUi
import io.aaps.copilot.ui.foundation.screens.toAnalyticsUiState
import io.aaps.copilot.ui.IsfCrOverlayPointUi
import io.aaps.copilot.ui.foundation.screens.mapClinicalReportState
import io.aaps.copilot.ui.foundation.screens.toAiAnalysisUiState
import io.aaps.copilot.ui.foundation.screens.withClinicalReport
import io.aaps.copilot.ui.foundation.screens.toAppHealthUiState
import io.aaps.copilot.ui.foundation.screens.toAuditUiState
import io.aaps.copilot.ui.foundation.screens.toForecastUiState
import io.aaps.copilot.ui.foundation.screens.toOverviewUiState
import io.aaps.copilot.ui.foundation.screens.withSensitivityApplyPresentation
import io.aaps.copilot.ui.foundation.screens.toSafetyUiState
import io.aaps.copilot.ui.foundation.screens.toSettingsUiState
import io.aaps.copilot.ui.foundation.screens.energyProfileSettingsUiState
import io.aaps.copilot.ui.foundation.screens.toUamUiState
import io.aaps.copilot.ui.foundation.screens.toUamExportControlUi
import io.aaps.copilot.ui.foundation.screens.toClinicalReportDisclosureUi
import io.aaps.copilot.report.ClinicalReportExportRepository
import io.aaps.copilot.report.ClinicalPdfContentSource
import io.aaps.copilot.storage.ClinicalPdfCopyResult
import io.aaps.copilot.storage.ClinicalPdfReadyArtifact
import io.aaps.copilot.storage.ClinicalPdfSharePayload
import io.aaps.copilot.storage.ClinicalPdfShareResult
import io.aaps.copilot.storage.ClinicalPdfStageResult
import io.aaps.copilot.util.LocaleFriendlyDecimalParser
import java.net.URI
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val EVENT_TIMELINE_RECENT_WINDOW_MS = 24L * 60L * 60L * 1_000L

internal fun Flow<Long>.shareAuthoritativeAlertMuteUntil(
    scope: CoroutineScope
): StateFlow<Long> = stateIn(
    scope = scope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = 0L
)

internal data class EventTimelineWindow(val nowTs: Long, val fromTs: Long, val throughTs: Long)

internal data class EventTimelineBoundaryPolicy(val includeRecent24h: Boolean = true)

private data class VersionedEventTimelineWindow(val version: Long, val window: EventTimelineWindow)

private sealed interface EventTimelineBoundarySignal<out T> {
    data class Input<T>(val windowVersion: Long, val value: T) : EventTimelineBoundarySignal<T>
    data class Boundary(val generation: Long) : EventTimelineBoundarySignal<Nothing>
}

internal fun eventTimelineWindowAt(
    nowTs: Long,
    pastWindowMs: Long,
    futureWindowMs: Long
): EventTimelineWindow {
    require(nowTs >= 0L && pastWindowMs > 0L && futureWindowMs > 0L)
    return EventTimelineWindow(
        nowTs = nowTs,
        fromTs = (nowTs - pastWindowMs).coerceAtLeast(0L),
        throughTs = nowTs + futureWindowMs.coerceAtMost(Long.MAX_VALUE - nowTs)
    )
}

internal fun nextEventTimelineBoundaryTs(
    events: List<CompensationEvent>,
    nowTs: Long,
    policy: EventTimelineBoundaryPolicy = EventTimelineBoundaryPolicy()
): Long? {
    require(nowTs >= 0L)
    var earliest: Long? = null
    fun accept(candidate: Long?) {
        if (candidate != null && candidate > nowTs && (earliest == null || candidate < earliest!!)) {
            earliest = candidate
        }
    }
    events.forEach { event ->
        if (nowTs < event.startTs) {
            accept(event.startTs)
        } else if (event.status != CompensationEventStatus.CLOSED && nowTs < event.endTs) {
            accept(event.endTs)
        }
        if (policy.includeRecent24h && event.startTs <= nowTs) {
            accept(safeEventTimelineAdd(event.endTs, EVENT_TIMELINE_RECENT_WINDOW_MS + 1L))
        }
    }
    return earliest
}

internal fun eventTimelineBoundaryDelayMs(boundaryTs: Long, nowTs: Long): Long =
    if (boundaryTs <= nowTs) 1L else (boundaryTs - nowTs).coerceAtLeast(1L)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun acceptedSensitivityTupleUiClock(
    generationTimestamps: Flow<Long?>,
    targetManagerStatusTimestamps: Flow<Long?> = flowOf(null),
    cobEvidence: Flow<Pair<Long?, Long>> = flowOf(null to 600_000L),
    clock: () -> Long
): Flow<Long> = combine(generationTimestamps, targetManagerStatusTimestamps, cobEvidence) { generationTs, statusTs, cob ->
    Triple(generationTs, statusTs, cob)
}
    .distinctUntilChanged()
    .flatMapLatest { (generationTs, statusTs, cob) ->
        flow {
            var authoritativeNowTs = clock()
            emit(authoritativeNowTs)
            uiExpiryBoundaries(
                nowTs = authoritativeNowTs,
                generationTs = generationTs,
                targetManagerStatusTs = statusTs,
                cobEvidence = cob
            ).forEach { boundaryTs ->
                delay((boundaryTs - authoritativeNowTs).coerceAtLeast(1L))
                authoritativeNowTs = max(clock(), boundaryTs)
                emit(authoritativeNowTs)
            }
        }
    }
    .distinctUntilChanged()

private fun uiExpiryBoundaries(
    nowTs: Long,
    generationTs: Long?,
    targetManagerStatusTs: Long?,
    cobEvidence: Pair<Long?, Long>
): List<Long> = listOfNotNull(
    uiExpiryBoundary(
        timestamp = generationTs,
        maxAgeMs = AcceptedSensitivityTupleFreshness.MAX_AGE_MS,
        nowTs = nowTs
    ),
    uiExpiryBoundary(
        timestamp = targetManagerStatusTs,
        maxAgeMs = TargetManagerLiveStatusCodec.MAX_AGE_MS,
        nowTs = nowTs
    ),
    uiExpiryBoundary(
        timestamp = cobEvidence.first,
        maxAgeMs = cobEvidence.second,
        nowTs = nowTs
    )
).distinct().sorted()

private fun uiExpiryBoundary(timestamp: Long?, maxAgeMs: Long, nowTs: Long): Long? {
    timestamp ?: return null
    val ageMs = runCatching { Math.subtractExact(nowTs, timestamp) }.getOrNull() ?: return null
    if (ageMs !in 0L..maxAgeMs) return null
    return runCatching { Math.addExact(timestamp, maxAgeMs + 1L) }.getOrNull()
}

private fun safeEventTimelineAdd(value: Long, increment: Long): Long? =
    if (value < 0L || increment < 0L || value > Long.MAX_VALUE - increment) null else value + increment

@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T : Any> boundaryDrivenEventTimeline(
    inputsForWindow: (EventTimelineWindow) -> kotlinx.coroutines.flow.Flow<T>,
    pastWindowMs: Long,
    futureWindowMs: Long,
    clock: () -> Long,
    project: suspend (T, EventTimelineWindow) -> List<CompensationEvent>,
    policy: EventTimelineBoundaryPolicy = EventTimelineBoundaryPolicy(),
    onTimerScheduled: (Long) -> Unit = {}
): kotlinx.coroutines.flow.Flow<EventTimeline> = channelFlow {
    fun capturedNow(): Long = clock().coerceAtLeast(0L)

    var windowVersion = 0L
    val windows = MutableStateFlow(
        VersionedEventTimelineWindow(
            version = windowVersion,
            window = eventTimelineWindowAt(capturedNow(), pastWindowMs, futureWindowMs)
        )
    )
    val signals = Channel<EventTimelineBoundarySignal<T>>(capacity = 1)
    val inputCollector = launch {
        windows.flatMapLatest { versioned ->
            inputsForWindow(versioned.window).map { value ->
                EventTimelineBoundarySignal.Input(versioned.version, value)
            }
        }.buffer(0).collect(signals::send)
    }
    var latestInput: T? = null
    var timerJob: Job? = null
    var timerGeneration = 0L
    var scheduledBoundaryTs: Long? = null

    suspend fun projectAndSchedule(input: T, refreshWindow: Boolean) {
        timerJob?.cancel()
        timerGeneration += 1L
        val nowTs = capturedNow()
        // Continuous inputs can cancel a due timer before its signal is handled.
        val refreshAfterProjection = refreshWindow || scheduledBoundaryTs?.let { nowTs >= it } == true
        val window = eventTimelineWindowAt(nowTs, pastWindowMs, futureWindowMs)
        val events = project(input, window)
        send(EventTimeline(events = events, generatedAt = nowTs))
        scheduledBoundaryTs = nextEventTimelineBoundaryTs(events, nowTs, policy)
        scheduledBoundaryTs?.let { boundaryTs ->
            val generation = timerGeneration
            onTimerScheduled(boundaryTs)
            timerJob = launch {
                delay(eventTimelineBoundaryDelayMs(boundaryTs, capturedNow()))
                signals.send(EventTimelineBoundarySignal.Boundary(generation))
            }
        }
        if (refreshAfterProjection) {
            windowVersion += 1L
            windows.value = VersionedEventTimelineWindow(windowVersion, window)
        }
    }

    try {
        for (signal in signals) {
            when (signal) {
                is EventTimelineBoundarySignal.Input -> if (signal.windowVersion == windowVersion) {
                    latestInput = signal.value
                    projectAndSchedule(signal.value, refreshWindow = false)
                }
                is EventTimelineBoundarySignal.Boundary -> if (signal.generation == timerGeneration) {
                    latestInput?.let { projectAndSchedule(it, refreshWindow = true) }
                }
            }
        }
    } finally {
        timerJob?.cancel()
        inputCollector.cancel()
        signals.close()
    }
}

private data class OverviewEventTimelineInputs(
    val therapyRows: List<TherapyEventEntity>,
    val contextTags: List<PhysioContextTag>,
    val plannedRows: List<PlannedActivityEventEntity>,
    val calibrationChecks: List<BloodGlucoseCheck>,
    val activityTelemetryRows: List<PhysicalActivityMetricBucketProjection>,
    val deliveryTelemetryRows: List<TelemetrySampleEntity>
)

private data class OverviewEventTelemetryInputs(
    val activityRows: List<PhysicalActivityMetricBucketProjection>,
    val deliveryRows: List<TelemetrySampleEntity>
)

private data class OverviewRuntimeContext(
    val state: MainUiState,
    val settings: AppSettings,
    val timeline: EventTimeline,
    val candidateDiagnostics: AcceptedSensitivityCandidateDiagnostics?
)

private data class TimedOverviewRuntimeContext(
    val runtime: OverviewRuntimeContext,
    val authoritativeNowTs: Long
)

private data class BaseTargetPresentationInput(
    val schedule: BaseTargetSchedule,
    val targetManagerMode: TargetManagerMode,
    val hardMinTargetMmol: Double,
    val hardMaxTargetMmol: Double,
    val sensorTrust: SensorTrustState,
    val sensorAgeHours: Double?,
    val lowRiskLatched: Boolean,
    val forecast5CiLowMmol: Double?,
    val forecast30CiLowMmol: Double?,
    val iobUnits: Double?,
    val effectiveCobGrams: Double?,
    val uamActive: Boolean
)

private data class SettingsUiDependencies(
    val settings: AppSettings,
    val credentialStatus: ClinicalAiCredentialStatus,
    val clinicalAi: ClinicalAiSettingsUiState,
    val energyProfile: io.aaps.copilot.ui.foundation.screens.EnergyProfileSettingsUiState
)

internal data class SensitivitySourceApplyUiState(
    val applying: Boolean = false,
    val pendingMetric: String? = null,
    val pendingValue: String? = null,
    val expectedSettingsRevision: Long? = null,
    val expectedIsfSource: String? = null,
    val expectedCrSource: String? = null,
    val error: String? = null
)

internal class SensitivitySourceApplyCoordinator(
    private val beforeAttemptRelease: suspend (Long) -> Unit = {}
) {
    private data class ApplyAttempt(
        val id: Long,
        val metricLabel: String,
        val requestedSource: String
    )

    private data class AwaitingAcceptedIdentity(
        val attempt: ApplyAttempt,
        val settingsRevision: Long,
        val isfSource: String,
        val crSource: String
    )

    private data class TerminalFailure(
        val attempt: ApplyAttempt,
        val message: String
    )

    private val operationMutex = Mutex()
    private val stateLock = Any()
    private val attempts = linkedMapOf<Long, ApplyAttempt>()
    private var nextAttemptId = 1L
    private var activeAttemptId: Long? = null
    private var awaitingAcceptedIdentity: AwaitingAcceptedIdentity? = null
    private var terminalFailure: TerminalFailure? = null
    private val mutableState = MutableStateFlow(SensitivitySourceApplyUiState())
    val state: StateFlow<SensitivitySourceApplyUiState> = mutableState.asStateFlow()

    suspend fun run(
        metricLabel: String,
        requestedSource: String,
        operation: suspend () -> SensitivityRuntimeSnapshot
    ): SensitivityRuntimeSnapshot {
        val attempt = synchronized(stateLock) {
            check(nextAttemptId != Long.MAX_VALUE) { "sensitivity apply attempt id exhausted" }
            ApplyAttempt(
                id = nextAttemptId++,
                metricLabel = metricLabel,
                requestedSource = requestedSource
            ).also { registered ->
                attempts[registered.id] = registered
                reconcileStateLocked()
            }
        }
        var acquired = false
        var cancelled = false
        var terminalError: String? = null
        var acceptedSnapshot: SensitivityRuntimeSnapshot? = null
        try {
            operationMutex.lock()
            acquired = true
            synchronized(stateLock) {
                check(activeAttemptId == null) { "sensitivity apply ownership overlap" }
                check(attempts[attempt.id] == attempt) { "sensitivity apply attempt was not registered" }
                activeAttemptId = attempt.id
                terminalFailure = null
                reconcileStateLocked()
            }
            return operation().also { acceptedSnapshot = it }
        } catch (cancellation: CancellationException) {
            cancelled = true
            throw cancellation
        } catch (error: Throwable) {
            terminalError = error.message ?: "runtime unavailable"
            throw error
        } finally {
            try {
                if (acquired) {
                    withContext(NonCancellable) { beforeAttemptRelease(attempt.id) }
                }
            } finally {
                synchronized(stateLock) {
                    attempts.remove(attempt.id)
                    if (acquired) {
                        check(activeAttemptId == attempt.id) { "sensitivity apply ownership lost" }
                        activeAttemptId = null
                        when {
                            acceptedSnapshot != null -> {
                                awaitingAcceptedIdentity = AwaitingAcceptedIdentity(
                                    attempt = attempt,
                                    settingsRevision = acceptedSnapshot.settingsRevision,
                                    isfSource = acceptedSnapshot.isf.requested.name,
                                    crSource = acceptedSnapshot.cr.requested.name
                                )
                                terminalFailure = null
                            }
                            terminalError != null -> {
                                awaitingAcceptedIdentity = null
                                terminalFailure = TerminalFailure(attempt, terminalError)
                            }
                            cancelled -> {
                                awaitingAcceptedIdentity = null
                                terminalFailure = null
                            }
                        }
                        operationMutex.unlock()
                    }
                    reconcileStateLocked()
                }
            }
        }
    }

    private fun reconcileStateLocked() {
        val visibleAttempt = activeAttemptId?.let(attempts::get) ?: attempts.values.firstOrNull()
        if (visibleAttempt != null) {
            val expected = awaitingAcceptedIdentity
            mutableState.value = SensitivitySourceApplyUiState(
                applying = true,
                pendingMetric = visibleAttempt.metricLabel,
                pendingValue = visibleAttempt.requestedSource,
                expectedSettingsRevision = expected?.settingsRevision,
                expectedIsfSource = expected?.isfSource,
                expectedCrSource = expected?.crSource
            )
            return
        }
        awaitingAcceptedIdentity?.let { expected ->
            mutableState.value = SensitivitySourceApplyUiState(
                applying = true,
                pendingMetric = expected.attempt.metricLabel,
                pendingValue = expected.attempt.requestedSource,
                expectedSettingsRevision = expected.settingsRevision,
                expectedIsfSource = expected.isfSource,
                expectedCrSource = expected.crSource
            )
            return
        }
        terminalFailure?.let { failure ->
            mutableState.value = SensitivitySourceApplyUiState(
                pendingMetric = failure.attempt.metricLabel,
                pendingValue = failure.attempt.requestedSource,
                error = failure.message
            )
            return
        }
        mutableState.value = SensitivitySourceApplyUiState()
    }

    fun presentationStateForOverview(
        overview: OverviewUiState,
        observedState: SensitivitySourceApplyUiState = state.value
    ): SensitivitySourceApplyUiState = synchronized(stateLock) {
        val current = mutableState.value
        if (current != observedState || !current.applying || attempts.isNotEmpty()) {
            return@synchronized current
        }
        val expectedRevision = current.expectedSettingsRevision ?: return@synchronized current
        val expectedIsf = current.expectedIsfSource ?: return@synchronized current
        val expectedCr = current.expectedCrSource ?: return@synchronized current
        val identityObserved = overview.currentIsfMmolPerUnit != null &&
            overview.currentCrGramsPerUnit != null &&
            overview.isfRuntime.availability == MetricRuntimeAvailabilityUi.AVAILABLE &&
            overview.crRuntime.availability == MetricRuntimeAvailabilityUi.AVAILABLE &&
            overview.isfRuntime.acceptedSettingsRevision == expectedRevision &&
            overview.crRuntime.acceptedSettingsRevision == expectedRevision &&
            overview.isfRuntime.requested.equals(expectedIsf, ignoreCase = true) &&
            overview.crRuntime.requested.equals(expectedCr, ignoreCase = true)
        if (!identityObserved) return@synchronized current

        awaitingAcceptedIdentity = null
        terminalFailure = null
        current.copy(
            applying = false,
            expectedSettingsRevision = null,
            expectedIsfSource = null,
            expectedCrSource = null
        ).also { mutableState.value = it }
    }
}

internal fun sensitivitySourceSelection(
    acceptedSource: String,
    authoritativeSource: String = acceptedSource,
    metric: String,
    apply: SensitivitySourceApplyUiState
): String = apply.pendingValue
    ?.takeIf { apply.pendingMetric.equals(metric, ignoreCase = true) }
    ?: acceptedSource.takeUnless { it.equals("UNAVAILABLE", ignoreCase = true) }
    ?: authoritativeSource

private const val MAX_CLINICAL_AI_MODEL_DRAFT_CHARS = 256
private const val MAX_CLINICAL_AI_ENDPOINT_DRAFT_CHARS = 2_048

internal data class ClinicalAiSettingsDraft(
    val providerId: ClinicalAiProviderId,
    val customModelSelected: Boolean,
    val modelDraft: String,
    val endpointDraft: String,
    val protocol: OpenAiCompatibleProtocol
) {
    init {
        require(modelDraft.length <= MAX_CLINICAL_AI_MODEL_DRAFT_CHARS)
        require(endpointDraft.length <= MAX_CLINICAL_AI_ENDPOINT_DRAFT_CHARS)
    }

    companion object {
        fun bounded(
            providerId: ClinicalAiProviderId,
            customModelSelected: Boolean,
            modelDraft: String,
            endpointDraft: String,
            protocol: OpenAiCompatibleProtocol
        ) = ClinicalAiSettingsDraft(
            providerId = providerId,
            customModelSelected = customModelSelected,
            modelDraft = modelDraft.take(MAX_CLINICAL_AI_MODEL_DRAFT_CHARS),
            endpointDraft = endpointDraft.take(MAX_CLINICAL_AI_ENDPOINT_DRAFT_CHARS),
            protocol = protocol
        )

        fun compatible(
            modelDraft: String,
            endpointDraft: String,
            protocol: OpenAiCompatibleProtocol
        ) = bounded(
            providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
            customModelSelected = true,
            modelDraft = modelDraft,
            endpointDraft = endpointDraft,
            protocol = protocol
        )
    }
}

internal data class ClinicalAiSettingsResolution(
    val effectiveConfig: ClinicalAiProviderConfig,
    val selectedProvider: ClinicalAiProviderId,
    val customModelSelected: Boolean,
    val modelDraft: String,
    val endpointDraft: String,
    val protocol: OpenAiCompatibleProtocol,
    val persistableConfig: ClinicalAiProviderConfig?,
    val validationError: ClinicalAiSettingsValidationErrorUi?
)

internal fun selectClinicalAiNativeConfig(
    providerId: ClinicalAiProviderId,
    currentConfig: ClinicalAiProviderConfig
): ClinicalAiProviderConfig {
    require(providerId != ClinicalAiProviderId.OPENAI_COMPATIBLE)
    val modelId = currentConfig.modelId.takeIf { current ->
        ClinicalAiModelCatalog.forProvider(providerId).any { it.id == current }
    } ?: ClinicalAiModelCatalog.defaultFor(providerId).id
    return ClinicalAiProviderConfig.normalized(
        providerId = providerId,
        modelId = modelId
    )
}

internal fun resolveClinicalAiSettings(
    persistedState: ClinicalAiConfigState,
    draft: ClinicalAiSettingsDraft?
): ClinicalAiSettingsResolution {
    val persistedConfig = when (persistedState) {
        is ClinicalAiConfigState.UnconfiguredDefault -> persistedState.config
        is ClinicalAiConfigState.Valid -> persistedState.config
        is ClinicalAiConfigState.Invalid -> ClinicalAiProviderConfig.defaultOpenAi()
    }
    if (draft == null) {
        val storedInvalid = persistedState is ClinicalAiConfigState.Invalid
        val presets = ClinicalAiModelCatalog.forProvider(persistedConfig.providerId)
        val custom = presets.none { it.id == persistedConfig.modelId }
        return ClinicalAiSettingsResolution(
            effectiveConfig = persistedConfig,
            selectedProvider = persistedConfig.providerId,
            customModelSelected = custom,
            modelDraft = persistedConfig.modelId.takeIf { custom }.orEmpty(),
            endpointDraft = persistedConfig.endpoint.orEmpty(),
            protocol = persistedConfig.compatibleProtocol
                ?: OpenAiCompatibleProtocol.RESPONSES,
            persistableConfig = persistedConfig.takeUnless { storedInvalid },
            validationError = ClinicalAiSettingsValidationErrorUi.INVALID_CONFIGURATION
                .takeIf { storedInvalid }
        )
    }

    if (runCatching { ClinicalAiModelIdPolicy.requireValid(draft.modelDraft) }.isFailure) {
        return draft.invalidResolution(
            fallback = persistedConfig,
            error = ClinicalAiSettingsValidationErrorUi.INVALID_MODEL
        )
    }
    if (draft.providerId == ClinicalAiProviderId.OPENAI_COMPATIBLE) {
        val endpoint = ClinicalAiEndpointPolicy.validate(draft.endpointDraft)
        if (endpoint !is ClinicalAiEndpointValidation.Valid) {
            return draft.invalidResolution(
                fallback = persistedConfig,
                error = ClinicalAiSettingsValidationErrorUi.INVALID_ENDPOINT
            )
        }
    }

    val normalized = runCatching {
        ClinicalAiProviderConfig.normalized(
            providerId = draft.providerId,
            modelId = draft.modelDraft,
            endpoint = draft.endpointDraft.takeIf {
                draft.providerId == ClinicalAiProviderId.OPENAI_COMPATIBLE
            },
            compatibleProtocol = draft.protocol.takeIf {
                draft.providerId == ClinicalAiProviderId.OPENAI_COMPATIBLE
            }
        )
    }.getOrElse {
        return draft.invalidResolution(
            fallback = persistedConfig,
            error = ClinicalAiSettingsValidationErrorUi.INVALID_CONFIGURATION
        )
    }
    return ClinicalAiSettingsResolution(
        effectiveConfig = normalized,
        selectedProvider = draft.providerId,
        customModelSelected = draft.customModelSelected,
        modelDraft = draft.modelDraft,
        endpointDraft = draft.endpointDraft,
        protocol = draft.protocol,
        persistableConfig = normalized,
        validationError = null
    )
}

private fun ClinicalAiSettingsDraft.invalidResolution(
    fallback: ClinicalAiProviderConfig,
    error: ClinicalAiSettingsValidationErrorUi
) = ClinicalAiSettingsResolution(
    effectiveConfig = fallback,
    selectedProvider = providerId,
    customModelSelected = customModelSelected,
    modelDraft = modelDraft,
    endpointDraft = endpointDraft,
    protocol = protocol,
    persistableConfig = null,
    validationError = error
)

internal enum class ClinicalAiRemotePreflightResult {
    READY,
    INVALID_CONFIGURATION,
    PERSISTENCE_PENDING,
    PERSISTENCE_FAILED
}

internal class ClinicalAiRemoteSendPreflight(
    private val persistedState: suspend () -> ClinicalAiConfigState,
    private val draftState: () -> ClinicalAiSettingsDraft?,
    private val persistencePending: () -> Boolean,
    private val persistenceFailed: () -> Boolean = { false }
) {
    suspend fun check(): ClinicalAiRemotePreflightResult {
        val storedState = persistedState()
        val resolution = resolveClinicalAiSettings(
            persistedState = storedState,
            draft = draftState()
        )
        if (resolution.persistableConfig == null) {
            return ClinicalAiRemotePreflightResult.INVALID_CONFIGURATION
        }
        val storedConfig = when (storedState) {
            is ClinicalAiConfigState.UnconfiguredDefault -> storedState.config
            is ClinicalAiConfigState.Valid -> storedState.config
            is ClinicalAiConfigState.Invalid -> null
        }
        if (persistenceFailed()) {
            return ClinicalAiRemotePreflightResult.PERSISTENCE_FAILED
        }
        return if (
            persistencePending() ||
            resolution.persistableConfig != storedConfig
        ) {
            ClinicalAiRemotePreflightResult.PERSISTENCE_PENDING
        } else {
            ClinicalAiRemotePreflightResult.READY
        }
    }
}

internal class ClinicalReportDisclosureSendGuard(
    private val persistedState: suspend () -> ClinicalAiConfigState,
    private val onMismatch: () -> Unit
) {
    suspend fun sendIfMatches(
        confirmedDisclosure: ClinicalReportDisclosureUi,
        send: suspend () -> Unit
    ): Boolean {
        val currentDisclosure = when (val state = persistedState()) {
            is ClinicalAiConfigState.UnconfiguredDefault -> state.config
            is ClinicalAiConfigState.Valid -> state.config
            is ClinicalAiConfigState.Invalid -> null
        }?.let { config ->
            runCatching { config.toClinicalReportDisclosureUi() }.getOrNull()
        }
        if (currentDisclosure != confirmedDisclosure) {
            onMismatch()
            return false
        }
        send()
        return true
    }
}

internal suspend fun persistClinicalAiSettingsResolution(
    resolution: ClinicalAiSettingsResolution,
    persist: suspend (ClinicalAiProviderConfig) -> Unit
): Boolean {
    val config = resolution.persistableConfig ?: return false
    persist(config)
    return true
}

internal enum class ClinicalAiPersistenceResult {
    CONFIRMED,
    FAILED,
    STALE
}

internal data class ClinicalAiPersistenceState(
    val version: Long = 0L,
    val draft: ClinicalAiSettingsDraft? = null,
    val pending: Boolean = false,
    val saveFailed: Boolean = false
) {
    fun begin(
        draft: ClinicalAiSettingsDraft,
        persistable: Boolean
    ): ClinicalAiPersistenceState = ClinicalAiPersistenceState(
        version = version + 1L,
        draft = draft,
        pending = persistable,
        saveFailed = this.saveFailed
    )

    fun complete(
        version: Long,
        result: ClinicalAiPersistenceResult
    ): ClinicalAiPersistenceState {
        if (version != this.version) return this
        return when (result) {
            ClinicalAiPersistenceResult.CONFIRMED -> copy(
                draft = null,
                pending = false,
                saveFailed = false
            )
            ClinicalAiPersistenceResult.FAILED -> copy(
                pending = false,
                saveFailed = true
            )
            ClinicalAiPersistenceResult.STALE -> copy(pending = false)
        }
    }
}

internal class ClinicalAiSettingsPersistenceCoordinator {
    private val mutex = Mutex()

    suspend fun persist(
        version: Long,
        config: ClinicalAiProviderConfig,
        currentVersion: () -> Long,
        write: suspend (ClinicalAiProviderConfig) -> Unit,
        persistedState: suspend () -> ClinicalAiConfigState
    ): ClinicalAiPersistenceResult = mutex.withLock {
        if (currentVersion() != version) {
            return@withLock ClinicalAiPersistenceResult.STALE
        }
        try {
            write(config)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            return@withLock ClinicalAiPersistenceResult.FAILED
        }
        if (currentVersion() != version) {
            return@withLock ClinicalAiPersistenceResult.STALE
        }
        val observed = try {
            persistedState()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            return@withLock ClinicalAiPersistenceResult.FAILED
        }
        if (currentVersion() != version) {
            return@withLock ClinicalAiPersistenceResult.STALE
        }
        val observedConfig = when (observed) {
            is ClinicalAiConfigState.UnconfiguredDefault -> observed.config
            is ClinicalAiConfigState.Valid -> observed.config
            is ClinicalAiConfigState.Invalid -> null
        }
        if (observedConfig == config) {
            ClinicalAiPersistenceResult.CONFIRMED
        } else {
            ClinicalAiPersistenceResult.FAILED
        }
    }
}

internal data class ClinicalAiConnectionTestToken(
    val generation: Long,
    val config: ClinicalAiProviderConfig
)

internal class ClinicalAiConnectionTestGenerationGuard {
    private var generation: Long = 0L

    fun begin(config: ClinicalAiProviderConfig): ClinicalAiConnectionTestToken {
        generation += 1L
        return ClinicalAiConnectionTestToken(generation, config)
    }

    fun invalidate() {
        generation += 1L
    }

    fun publishIfCurrent(
        token: ClinicalAiConnectionTestToken,
        currentConfig: ClinicalAiProviderConfig?,
        value: ClinicalAiConnectionTestUiState,
        publish: (ClinicalAiConnectionTestUiState) -> Unit
    ): Boolean {
        if (token.generation != generation || currentConfig != token.config) {
            return false
        }
        publish(value)
        return true
    }
}

internal class ClinicalAiRetryStateResetGate {
    private var lastUsable: Boolean? = null

    fun shouldClear(configurationUsable: Boolean): Boolean {
        val shouldClear = !configurationUsable && lastUsable != false
        lastUsable = configurationUsable
        return shouldClear
    }
}

internal data class ClinicalReportPdfExportCoordinatorState(
    val phase: ClinicalPdfExportUiState = ClinicalPdfExportUiState.IDLE,
    val ticket: ClinicalPdfExportTicketUi? = null,
    val canShare: Boolean = false,
    val requiresReprepare: Boolean = false
)

internal fun clinicalPdfReportIdentity(state: ClinicalReportState): ClinicalPdfReportIdentity =
    ClinicalPdfReportIdentity.from(state)

internal class ClinicalReportPdfReprepareCoordinator(
    private val currentReportIdentity: () -> ClinicalPdfReportIdentity,
    private val claimReprepare:
        suspend (ClinicalPdfReportIdentity) -> ClinicalPdfReprepareResult,
    private val prepare: suspend () -> Unit
) {
    private val mutex = Mutex()

    suspend fun reprepare() {
        if (!mutex.tryLock()) return
        try {
            val reportIdentity = currentReportIdentity()
            if (!reportIdentity.isExportable) return
            if (
                claimReprepare(reportIdentity) ==
                ClinicalPdfReprepareResult.PREPARE_REQUIRED
            ) {
                prepare()
            }
        } finally {
            mutex.unlock()
        }
    }
}

internal class ClinicalReportPdfExportCoordinator(
    private val scope: CoroutineScope,
    private val acquireSourceLease: suspend () -> ClinicalPdfSourceLeaseResult,
    private val sourceDispatcher: CoroutineDispatcher,
    private val stage: suspend (ClinicalPdfContentSource) -> ClinicalPdfStageResult,
    private val copy: suspend (ClinicalPdfReadyArtifact, Any) -> ClinicalPdfCopyResult,
    private val share: suspend (ClinicalPdfReadyArtifact) -> ClinicalPdfShareResult,
    private val discardShare: (ClinicalPdfSharePayload) -> Unit,
    private val currentReportIdentity: () -> ClinicalPdfReportIdentity,
    initialReportIdentity: ClinicalPdfReportIdentity,
    private val release: (ClinicalPdfReadyArtifact) -> Unit
) {
    private enum class WriteOperation { SAF, SHARE }

    private sealed interface ActiveExport {
        val id: Long
        val identity: ClinicalPdfReportIdentity

        data class Preparing(
            override val id: Long,
            override val identity: ClinicalPdfReportIdentity,
            var job: Job? = null
        ) : ActiveExport
        data class Ready(
            override val id: Long,
            override val identity: ClinicalPdfReportIdentity,
            val artifact: ClinicalPdfReadyArtifact,
            val retryCount: Int,
            var claimed: Boolean = false
        ) : ActiveExport
        data class Writing(
            override val id: Long,
            override val identity: ClinicalPdfReportIdentity,
            val artifact: ClinicalPdfReadyArtifact,
            val retryCount: Int,
            val operation: WriteOperation,
            var job: Job? = null
        ) : ActiveExport
        data class Retryable(
            override val id: Long,
            override val identity: ClinicalPdfReportIdentity,
            val artifact: ClinicalPdfReadyArtifact,
            val retryCount: Int
        ) : ActiveExport
    }

    private val lock = Any()
    private val mutableState = MutableStateFlow(ClinicalReportPdfExportCoordinatorState())
    private var nextId = 0L
    private var active: ActiveExport? = null
    private var closed = false
    private var observedReportIdentity = initialReportIdentity
    private var blockedReportIdentity: ClinicalPdfReportIdentity? = null

    val state: StateFlow<ClinicalReportPdfExportCoordinatorState> = mutableState.asStateFlow()

    fun begin() {
        if (!reconcileCurrentIdentity()) return
        val reportIdentity = currentReportIdentity()
        val preparing = synchronized(lock) {
            if (closed) return
            if (!reportIdentity.isExportable || observedReportIdentity != reportIdentity) return
            if (mutableState.value.requiresReprepare) return
            val current = active
            if (current is ActiveExport.Retryable) {
                if (current.identity != reportIdentity) return
                if (current.retryCount > MAX_LOCAL_RETRIES) return
                val id = nextTicketId() ?: return
                val ready = ActiveExport.Ready(
                    id = id,
                    identity = current.identity,
                    artifact = current.artifact,
                    retryCount = (current.retryCount + 1).coerceAtMost(MAX_LOCAL_RETRIES)
                )
                active = ready
                publishReady(ready)
                return
            }
            if (current != null) return
            val id = nextTicketId() ?: return
            ActiveExport.Preparing(id, reportIdentity).also {
                active = it
                mutableState.value = ClinicalReportPdfExportCoordinatorState(
                    ClinicalPdfExportUiState.PREPARING
                )
            }
        }
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val ownJob = checkNotNull(currentCoroutineContext()[Job])
            val registered = synchronized(lock) {
                val current = active as? ActiveExport.Preparing
                if (current?.id == preparing.id) {
                    current.job = ownJob
                    true
                } else {
                    false
                }
            }
            if (!registered) return@launch
            var lease: ClinicalPdfSourceLease? = null
            try {
                val acquired = acquireSourceLease()
                if (acquired !is ClinicalPdfSourceLeaseResult.Ready) {
                    if (!identityMatches(preparing.identity)) {
                        onReportIdentityChanged(currentReportIdentity())
                        return@launch
                    }
                    failPreparing(
                        preparing.id,
                        ClinicalPdfExportUiState.FAILED,
                        requiresReprepare = true,
                        reportIdentity = observedReportIdentity
                    )
                    return@launch
                }
                lease = acquired.lease
                if (acquired.lease.reportIdentity != preparing.identity) {
                    reconcileCurrentIdentity()
                    return@launch
                }
                if (!identityMatches(acquired.lease.reportIdentity)) {
                    onReportIdentityChanged(currentReportIdentity())
                    return@launch
                }
                val captured = withContext(sourceDispatcher) {
                    acquired.lease.buildSource()
                }
                if (captured !is ClinicalPdfSourceCaptureResult.Ready) {
                    failPreparing(
                        preparing.id,
                        ClinicalPdfExportUiState.FAILED,
                        requiresReprepare = true,
                        reportIdentity = acquired.lease.reportIdentity
                    )
                    return@launch
                }
                val result = stage(captured.source)
                if (!identityMatches(acquired.lease.reportIdentity)) {
                    onReportIdentityChanged(currentReportIdentity())
                    if (result is ClinicalPdfStageResult.Ready) release(result.artifact)
                    return@launch
                }
                when (result) {
                    is ClinicalPdfStageResult.Ready -> completePreparing(
                        preparing.id,
                        acquired.lease.reportIdentity,
                        result.artifact
                    )
                    ClinicalPdfStageResult.TooLarge ->
                        failPreparing(
                            preparing.id,
                            ClinicalPdfExportUiState.TOO_LARGE,
                            requiresReprepare = true,
                            reportIdentity = acquired.lease.reportIdentity
                        )
                    ClinicalPdfStageResult.Failed ->
                        failPreparing(preparing.id, ClinicalPdfExportUiState.FAILED)
                }
            } catch (cancelled: CancellationException) {
                cancelPreparingIfCurrent(preparing.id)
                throw cancelled
            } catch (_: Exception) {
                failPreparing(preparing.id, ClinicalPdfExportUiState.FAILED)
            } finally {
                lease?.let { owned ->
                    withContext(NonCancellable) {
                        owned.close()
                    }
                }
            }
        }
        synchronized(lock) {
            (active as? ActiveExport.Preparing)?.takeIf { it.id == preparing.id }?.job = job
        }
    }

    fun claimReadyTicket(ticketId: Long): Boolean {
        if (!reconcileCurrentIdentity()) return false
        return synchronized(lock) {
            val ready = active as? ActiveExport.Ready ?: return@synchronized false
            if (
                ready.id != ticketId ||
                ready.claimed ||
                ready.identity != observedReportIdentity
            ) return@synchronized false
            ready.claimed = true
            true
        }
    }

    fun beginShare(onReady: (ClinicalPdfSharePayload) -> Boolean) {
        if (!reconcileCurrentIdentity()) return
        val writing = synchronized(lock) {
            if (closed) return
            val (artifact, retryCount, identity) = when (val current = active) {
                is ActiveExport.Ready -> Triple(
                    current.artifact,
                    current.retryCount,
                    current.identity
                )
                is ActiveExport.Retryable -> Triple(
                    current.artifact,
                    current.retryCount,
                    current.identity
                )
                else -> return
            }
            if (identity != observedReportIdentity) return
            val id = nextTicketId() ?: return
            ActiveExport.Writing(
                id = id,
                identity = identity,
                artifact = artifact,
                retryCount = retryCount,
                operation = WriteOperation.SHARE
            ).also {
                active = it
                mutableState.value = ClinicalReportPdfExportCoordinatorState(
                    ClinicalPdfExportUiState.WRITING
                )
            }
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                completeSharing(writing.id, share(writing.artifact), onReady)
            } catch (cancelled: CancellationException) {
                cancelWritingIfCurrent(writing.id)
                throw cancelled
            } catch (_: Exception) {
                completeSharing(writing.id, ClinicalPdfShareResult.Failed, onReady)
            }
        }
        val registered = synchronized(lock) {
            (active as? ActiveExport.Writing)?.takeIf { it.id == writing.id }?.let {
                it.job = job
                true
            } ?: false
        }
        if (registered) job.start() else job.cancel()
    }

    fun onReportIdentityChanged(reportIdentity: ClinicalPdfReportIdentity) {
        val invalidated = synchronized(lock) {
            observedReportIdentity = reportIdentity
            val stale = active?.takeIf {
                it.identity != reportIdentity || !reportIdentity.isExportable
            }
            if (stale != null) {
                active = null
                mutableState.value = ClinicalReportPdfExportCoordinatorState(
                    ClinicalPdfExportUiState.CANCELLED
                )
            }
            if (
                !closed &&
                mutableState.value.requiresReprepare &&
                reportIdentity.isExportable &&
                reportIdentity != blockedReportIdentity
            ) {
                blockedReportIdentity = null
                mutableState.value = ClinicalReportPdfExportCoordinatorState()
            }
            stale
        }
        invalidated?.let(::disposeInvalidated)
    }

    fun resolvePicker(ticketId: Long, destination: Any?) {
        if (!reconcileCurrentIdentity()) return
        val writing = synchronized(lock) {
            val ready = active as? ActiveExport.Ready ?: return
            if (
                ready.id != ticketId ||
                !ready.claimed ||
                ready.identity != observedReportIdentity
            ) return
            if (destination == null) {
                cancelReady(ready)
                return
            }
            ActiveExport.Writing(
                id = ready.id,
                identity = ready.identity,
                artifact = ready.artifact,
                retryCount = ready.retryCount,
                operation = WriteOperation.SAF
            ).also {
                active = it
                mutableState.value = ClinicalReportPdfExportCoordinatorState(
                    ClinicalPdfExportUiState.WRITING
                )
            }
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                completeWriting(writing.id, copy(writing.artifact, checkNotNull(destination)))
            } catch (cancelled: CancellationException) {
                cancelWritingIfCurrent(writing.id)
                throw cancelled
            } catch (_: Exception) {
                completeWriting(writing.id, ClinicalPdfCopyResult.Failed)
            }
        }
        val registered = synchronized(lock) {
            (active as? ActiveExport.Writing)?.takeIf { it.id == writing.id }?.let {
                it.job = job
                true
            } ?: false
        }
        if (registered) job.start() else job.cancel()
    }

    fun cancel() {
        synchronized(lock) {
            when (val current = active) {
                is ActiveExport.Preparing -> current.job?.cancel()
                is ActiveExport.Ready -> release(current.artifact)
                is ActiveExport.Writing -> {
                    current.job?.cancel()
                    release(current.artifact)
                }
                is ActiveExport.Retryable -> release(current.artifact)
                null -> Unit
            }
            active = null
            mutableState.value = ClinicalReportPdfExportCoordinatorState(
                ClinicalPdfExportUiState.CANCELLED
            )
        }
    }

    fun close() {
        val owned = synchronized(lock) {
            if (closed) return
            closed = true
            active.also {
                active = null
                mutableState.value = ClinicalReportPdfExportCoordinatorState(
                    ClinicalPdfExportUiState.CANCELLED
                )
            }
        }
        when (owned) {
            is ActiveExport.Preparing -> owned.job?.cancel()
            is ActiveExport.Ready -> release(owned.artifact)
            is ActiveExport.Writing -> {
                owned.job?.cancel()
                release(owned.artifact)
            }
            is ActiveExport.Retryable -> release(owned.artifact)
            null -> Unit
        }
    }

    private fun completePreparing(
        ticketId: Long,
        reportIdentity: ClinicalPdfReportIdentity,
        artifact: ClinicalPdfReadyArtifact
    ) {
        if (!identityMatches(reportIdentity)) {
            onReportIdentityChanged(currentReportIdentity())
            release(artifact)
            return
        }
        synchronized(lock) {
            val preparing = active as? ActiveExport.Preparing
            if (preparing?.id != ticketId || preparing.identity != reportIdentity) {
                release(artifact)
                return
            }
            val ready = ActiveExport.Ready(
                ticketId,
                reportIdentity,
                artifact,
                retryCount = 0
            )
            active = ready
            publishReady(ready)
        }
    }

    private fun failPreparing(
        ticketId: Long,
        phase: ClinicalPdfExportUiState,
        requiresReprepare: Boolean = false,
        reportIdentity: ClinicalPdfReportIdentity? = null
    ) {
        synchronized(lock) {
            val preparing = active as? ActiveExport.Preparing ?: return
            if (preparing.id != ticketId) return
            active = null
            if (requiresReprepare) blockedReportIdentity = reportIdentity
            mutableState.value = ClinicalReportPdfExportCoordinatorState(
                phase = phase,
                requiresReprepare = requiresReprepare
            )
        }
    }

    private fun cancelPreparingIfCurrent(ticketId: Long) {
        synchronized(lock) {
            val preparing = active as? ActiveExport.Preparing ?: return
            if (preparing.id != ticketId) return
            active = null
            mutableState.value = ClinicalReportPdfExportCoordinatorState(
                ClinicalPdfExportUiState.CANCELLED
            )
        }
    }

    private fun completeWriting(ticketId: Long, result: ClinicalPdfCopyResult) {
        if (!reconcileCurrentIdentity()) return
        synchronized(lock) {
            val writing = active as? ActiveExport.Writing ?: return
            if (writing.id != ticketId || writing.operation != WriteOperation.SAF) return
            if (
                result == ClinicalPdfCopyResult.CopiedVerified ||
                result == ClinicalPdfCopyResult.CopiedUnverifiedProvider
            ) {
                release(writing.artifact)
                active = null
                mutableState.value = ClinicalReportPdfExportCoordinatorState(
                    ClinicalPdfExportUiState.COMPLETE
                )
            } else if (writing.retryCount < MAX_LOCAL_RETRIES) {
                active = ActiveExport.Retryable(
                    writing.id,
                    writing.identity,
                    writing.artifact,
                    writing.retryCount
                )
                mutableState.value = ClinicalReportPdfExportCoordinatorState(
                    ClinicalPdfExportUiState.FAILED,
                    canShare = true
                )
            } else {
                release(writing.artifact)
                active = null
                mutableState.value = ClinicalReportPdfExportCoordinatorState(
                    ClinicalPdfExportUiState.FAILED
                )
            }
        }
    }

    private fun completeSharing(
        ticketId: Long,
        result: ClinicalPdfShareResult,
        onReady: (ClinicalPdfSharePayload) -> Boolean
    ) {
        if (!reconcileCurrentIdentity()) {
            if (result is ClinicalPdfShareResult.Ready) discardShare(result.payload)
            return
        }
        if (result is ClinicalPdfShareResult.Ready) {
            val completion = synchronized(lock) {
                val writing = active as? ActiveExport.Writing
                if (writing?.id != ticketId || writing.operation != WriteOperation.SHARE) {
                    null
                } else {
                    val accepted = runCatching { onReady(result.payload) }.getOrDefault(false)
                    active = null
                    mutableState.value = ClinicalReportPdfExportCoordinatorState(
                        if (accepted) {
                            ClinicalPdfExportUiState.COMPLETE
                        } else {
                            ClinicalPdfExportUiState.FAILED
                        }
                    )
                    writing.artifact to accepted
                }
            }
            if (completion?.second != true) discardShare(result.payload)
            completion?.first?.let(release)
            return
        }
        val releaseArtifact = synchronized(lock) {
            val writing = active as? ActiveExport.Writing
            if (writing?.id != ticketId || writing.operation != WriteOperation.SHARE) return@synchronized null
            if (writing.retryCount < MAX_LOCAL_RETRIES) {
                active = ActiveExport.Retryable(
                    writing.id,
                    writing.identity,
                    writing.artifact,
                    writing.retryCount + 1
                )
                mutableState.value = ClinicalReportPdfExportCoordinatorState(
                    ClinicalPdfExportUiState.FAILED,
                    canShare = true
                )
                null
            } else {
                active = null
                mutableState.value = ClinicalReportPdfExportCoordinatorState(
                    ClinicalPdfExportUiState.FAILED
                )
                writing.artifact
            }
        }
        releaseArtifact?.let(release)
    }

    private fun cancelWritingIfCurrent(ticketId: Long) {
        synchronized(lock) {
            val writing = active as? ActiveExport.Writing ?: return
            if (writing.id != ticketId) return
            release(writing.artifact)
            active = null
            mutableState.value = ClinicalReportPdfExportCoordinatorState(
                ClinicalPdfExportUiState.CANCELLED
            )
        }
    }

    private fun cancelReady(ready: ActiveExport.Ready) {
        if (ready.retryCount < MAX_LOCAL_RETRIES) {
            active = ActiveExport.Retryable(
                ready.id,
                ready.identity,
                ready.artifact,
                ready.retryCount
            )
        } else {
            release(ready.artifact)
            active = null
        }
        mutableState.value = ClinicalReportPdfExportCoordinatorState(
            ClinicalPdfExportUiState.CANCELLED,
            canShare = active is ActiveExport.Retryable
        )
    }

    private fun publishReady(ready: ActiveExport.Ready) {
        val date = Instant.ofEpochMilli(ready.artifact.createdAt)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
        mutableState.value = ClinicalReportPdfExportCoordinatorState(
            phase = ClinicalPdfExportUiState.READY,
            ticket = ClinicalPdfExportTicketUi(
                id = ready.id,
                suggestedFilename = ClinicalReportExportRepository.suggestedFilename(
                    "aaps-copilot-clinical-report-$date"
                )
            ),
            canShare = true
        )
    }

    private fun reconcileCurrentIdentity(): Boolean {
        val current = currentReportIdentity()
        val invalidated = synchronized(lock) {
            observedReportIdentity = current
            val stale = active?.takeIf {
                it.identity != current || !current.isExportable
            }
            if (stale != null) {
                active = null
                mutableState.value = ClinicalReportPdfExportCoordinatorState(
                    ClinicalPdfExportUiState.CANCELLED
                )
            }
            if (
                !closed &&
                mutableState.value.requiresReprepare &&
                current.isExportable &&
                current != blockedReportIdentity
            ) {
                blockedReportIdentity = null
                mutableState.value = ClinicalReportPdfExportCoordinatorState()
            }
            stale
        }
        invalidated?.let(::disposeInvalidated)
        return current.isExportable && invalidated == null
    }

    private fun identityMatches(expected: ClinicalPdfReportIdentity): Boolean {
        val current = currentReportIdentity()
        return current.isExportable && current == expected
    }

    private fun disposeInvalidated(export: ActiveExport) {
        when (export) {
            is ActiveExport.Preparing -> export.job?.cancel()
            is ActiveExport.Ready -> release(export.artifact)
            is ActiveExport.Writing -> {
                export.job?.cancel()
                release(export.artifact)
            }
            is ActiveExport.Retryable -> release(export.artifact)
        }
    }

    private fun nextTicketId(): Long? {
        if (nextId == Long.MAX_VALUE) {
            active = null
            mutableState.value = ClinicalReportPdfExportCoordinatorState(
                ClinicalPdfExportUiState.FAILED
            )
            return null
        }
        nextId += 1L
        return nextId
    }

    private companion object {
        const val MAX_LOCAL_RETRIES = 1
    }
}

internal fun mapClinicalAiCredentialUiState(
    state: ClinicalAiProviderCredentialState
): AiCredentialUiState = AiCredentialUiState(
    configured = state.configured,
    busy = state.busy,
    migrationError = state.migrationFailed,
    readError = state.readFailed,
    legacyCleanupPending = state.legacyCleanupPending
)

internal fun ClinicalReportUiState.withClinicalAiRemoteActionsEnabled(
    enabled: Boolean
): ClinicalReportUiState {
    val blockedRemoteAction =
        !enabled && (canSend || canRequestGuardedRetry || showRetryConfirmation)
    return copy(
        canSend = canSend && enabled,
        canRequestGuardedRetry = canRequestGuardedRetry && enabled,
        showRetryConfirmation = showRetryConfirmation && enabled,
        retryFeedback = retryFeedback.takeIf { enabled },
        pointsToSettings = pointsToSettings || blockedRemoteAction
    )
}

private data class ClinicalAiTransientState(
    val persistence: ClinicalAiPersistenceState,
    val connectionTest: ClinicalAiConnectionTestUiState
)

internal fun interface AutomaticEventAiAnalysisSettingsAction {
    fun setAutomaticEventAiAnalysisEnabled(enabled: Boolean)
}

internal class AutomaticEventAiAnalysisSettingCommand(
    private val scope: CoroutineScope,
    private val updateSettings: suspend ((AppSettings) -> AppSettings) -> Unit,
    private val onSaveFailed: () -> Unit,
    private val launchCommand: ((suspend () -> Unit) -> Unit) = { block ->
        scope.launch { block() }
    }
) : AutomaticEventAiAnalysisSettingsAction {
    override fun setAutomaticEventAiAnalysisEnabled(enabled: Boolean) {
        launchCommand {
            try {
                MainViewModel.setAutomaticEventAiAnalysisEnabled(enabled, updateSettings)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (fatal: Error) {
                throw fatal
            } catch (_: Exception) {
                onSaveFailed()
            }
        }
    }
}

class MainViewModel(application: Application) :
    AndroidViewModel(application),
    AutomaticEventAiAnalysisSettingsAction {

    private val container = (application as CopilotApp).container
    private val db = container.db
    private val manualCarbSubmission = ManualMealSubmission(
        sendCarbs = container.actionRepository::submitCarbs,
        stageSelection = { command, selection, manualMealEnergyKcal ->
            container.energyProfileRepository.stageSelectionAfterSubmittedCarbAction(
                command = command,
                selection = selection,
                manualMealEnergyKcal = manualMealEnergyKcal,
                portion = selection.portionMetadata?.portion,
                portionProvenance = selection.portionMetadata?.provenance
            )
        },
        sendEatingSoon = container::submitManualEatingSoon,
        readCarbBlockReason = container.actionRepository::manualCarbBlockReason,
        onMealInput = { container.mealStateIngestion.offerInput(it) }
    )
    private val clinicalReportCommands = ClinicalReportUiCommands(
        state = container.clinicalReportRepository.state,
        clock = System::currentTimeMillis,
        zoneId = ZoneId::systemDefault,
        prepareLocal = { nowTs, zoneId ->
            container.clinicalReportRepository.prepareLocal(nowTs, zoneId)
        },
        start = { nowTs, zoneId, force, expectedConfigIdentity ->
            container.clinicalReportRepository.start(
                nowTs = nowTs,
                zoneId = zoneId,
                force = force,
                expectedConfigIdentity = expectedConfigIdentity
            ).disposition
        },
        cancelActive = container.clinicalReportRepository::cancelActive
    )
    private val clinicalReportPdfReprepareCoordinator =
        ClinicalReportPdfReprepareCoordinator(
            currentReportIdentity = {
                clinicalPdfReportIdentity(container.clinicalReportRepository.state.value)
            },
            claimReprepare =
                container.clinicalReportRepository::claimClinicalPdfReprepare,
            prepare = clinicalReportCommands::prepare
        )
    private val clinicalReportPdfExportCoordinator =
        ClinicalReportPdfExportCoordinator(
            scope = viewModelScope,
            acquireSourceLease = container.clinicalReportRepository::acquireClinicalPdfSourceLease,
            sourceDispatcher = Dispatchers.IO,
            stage = container.clinicalPdfStorageRepository::stage,
            copy = container.clinicalPdfStorageRepository::copyToDestination,
            share = container.clinicalPdfShareRepository::createShare,
            discardShare = container.clinicalPdfShareRepository::discard,
            currentReportIdentity = {
                clinicalPdfReportIdentity(container.clinicalReportRepository.state.value)
            },
            initialReportIdentity = clinicalPdfReportIdentity(
                container.clinicalReportRepository.state.value
            ),
            release = container.clinicalPdfStorageRepository::release
        )

    private val messageState = MutableStateFlow<String?>(null)
    private val automaticEventAiAnalysisSettingCommand =
        AutomaticEventAiAnalysisSettingCommand(
            scope = viewModelScope,
            updateSettings = container.settingsStore::update,
            onSaveFailed = {
                messageState.value = application.getString(
                    R.string.settings_clinical_ai_config_save_failed
                )
            }
        )
    private val selectedAlertEpisodeId = MutableStateFlow<String?>(null)
    private val alertsHistoryWindow = MutableStateFlow(
        AlertHistoryWindow(fromTsInclusive = 0L, throughTsExclusive = 0L)
    )
    private val energyProfileValidationMessage = MutableStateFlow<String?>(null)
    private val clinicalReportRetryConfirmationState = MutableStateFlow(false)
    private val clinicalReportRetryFeedbackState =
        MutableStateFlow<ClinicalReportRetryFeedbackUi?>(null)
    private val clinicalAiPersistenceState = MutableStateFlow(ClinicalAiPersistenceState())
    private val clinicalAiConnectionTestState =
        MutableStateFlow(ClinicalAiConnectionTestUiState())
    private val clinicalAiPersistenceCoordinator =
        ClinicalAiSettingsPersistenceCoordinator()
    private val clinicalAiConnectionTestGuard =
        ClinicalAiConnectionTestGenerationGuard()
    private var clinicalAiConnectionTestJob: Job? = null
    private val clinicalAiRemotePreflight = ClinicalAiRemoteSendPreflight(
        persistedState = {
            container.settingsStore.settings.first().clinicalAiConfigState
        },
        draftState = { clinicalAiPersistenceState.value.draft },
        persistencePending = { clinicalAiPersistenceState.value.pending },
        persistenceFailed = { clinicalAiPersistenceState.value.saveFailed }
    )
    private val clinicalReportDisclosureSendGuard = ClinicalReportDisclosureSendGuard(
        persistedState = {
            container.settingsStore.settings.first().clinicalAiConfigState
        },
        onMismatch = {
            messageState.value = getApplication<Application>().getString(
                R.string.settings_clinical_ai_report_settings_changed
            )
        }
    )
    private val dryRunState = MutableStateFlow<DryRunUi?>(null)
    private val cloudReplayState = MutableStateFlow<CloudReplayUiModel?>(null)
    private val cloudJobsState = MutableStateFlow<CloudJobsUiModel?>(null)
    private val analysisHistoryState = MutableStateFlow<CloudAnalysisHistoryUiModel?>(null)
    private val analysisTrendState = MutableStateFlow<CloudAnalysisTrendUiModel?>(null)
    private val circadianReplaySummaryState = MutableStateFlow<CircadianReplaySummaryUi?>(null)
    private val aiChatMessagesState = MutableStateFlow<List<AiChatMessageUi>>(emptyList())
    private val aiChatInProgressState = MutableStateFlow(false)
    private val aiChatDraftState = MutableStateFlow("")
    private val aiChatPendingAttachmentsState = MutableStateFlow<List<PendingAiAttachment>>(emptyList())
    private val aiChatVoiceRepliesEnabledState = MutableStateFlow(false)
    private val aiChatRecordingState = MutableStateFlow(false)
    private val aiChatVoiceBusyState = MutableStateFlow(false)
    private val aiChatSpeakingState = MutableStateFlow(false)
    private val insightsFilterState = MutableStateFlow(InsightsFilterUi())
    private val activeRouteState = MutableStateFlow(ROUTE_OVERVIEW)
    private val forecastRangeState = MutableStateFlow(ForecastRangeUi.H3)
    private val forecastLayersState = MutableStateFlow(ForecastLayerState())
    private val auditWindowState = MutableStateFlow(AuditWindowUi.H24)
    private val auditOnlyErrorsState = MutableStateFlow(false)
    private val killSwitchOverrideState = MutableStateFlow<Boolean?>(null)
    private val proModeState = MutableStateFlow(false)
    private val verboseLogsState = MutableStateFlow(false)
    private val glucoseAlertAudioPreviewState = container.glucoseAlertAudioController.playbackState
    private val sensitivitySourceApplyCoordinator = SensitivitySourceApplyCoordinator()
    private val sensitivitySourceApplyState = sensitivitySourceApplyCoordinator.state
    private val autoConnectState = MutableStateFlow<AutoConnectUi?>(null)
    private val qualityEvaluator = ForecastQualityEvaluator()
    private val baselineComparator = BaselineComparator()
    @Volatile
    private var yesterdayProfileLinesCache: CachedUiValue<List<String>>? = null
    @Volatile
    private var isfCrDeepLinesCache: CachedUiValue<List<String>>? = null
    @Volatile
    private var isfCrHistoryPointsCache: CachedUiValue<List<IsfCrHistoryPointUi>>? = null
    private var isfCrHistoryOverlayPointsCache: CachedUiValue<List<IsfCrOverlayPointUi>>? = null
    @Volatile
    private var circadianPatternSectionsCache: CachedUiValue<List<CircadianPatternSectionUi>>? = null
    @Volatile
    private var circadianReplayRefreshStartedAt: Long = 0L
    @Volatile
    private var circadianReplayRefreshRunning: Boolean = false
    private var aiMediaRecorder: MediaRecorder? = null
    private var aiRecordingFile: File? = null
    private var aiVoicePlayer: MediaPlayer? = null
    private val uiStateDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val telemetryObserveAnchorTs = System.currentTimeMillis()
    private val eventTimelineRepository = EventTimelineRepository(container.gson)
    private val calibrationSessionEvidence = db.telemetryDao()
        .observeCalibrationSessionEvidence(
            keys = CalibrationModelAuthority.SESSION_EVIDENCE_KEYS,
            limit = CalibrationModelAuthority.SESSION_EVIDENCE_QUERY_LIMIT + 1
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val overviewEventTimeline = projectOverviewEventTimeline(
        inputsForWindow = { window ->
            val telemetryInputs = combine(
                PhysicalActivityTelemetryPolicy.reportBucketWindow(
                    window.fromTs,
                    window.throughTs
                ).let { activityWindow ->
                    db.telemetryDao().observePhysicalActivityMetric5MinuteBuckets(
                        fromTs = activityWindow.fromTs,
                        toTsExclusive = activityWindow.toTsExclusive,
                        firstBucketTs = activityWindow.firstBucketTs,
                        keys = listOf(PhysicalActivityTelemetryPolicy.ACTIVITY_RATIO_KEY),
                        sources = PhysicalActivityTelemetryPolicy.TRUSTED_SOURCES.sorted(),
                        qualities = PhysicalActivityTelemetryPolicy.ACCEPTABLE_QUALITIES.sorted(),
                        bucketMs = PhysicalActivityTelemetryPolicy.REPORT_BUCKET_MS
                    )
                },
                db.telemetryDao().observeDeliveryTrustInWindow(
                    stableKey = DeliveryTrustStateWireCodec.STABLE_TELEMETRY_KEY,
                    legacyKey = DeliveryTrustStateWireCodec.LEGACY_TELEMETRY_KEY,
                    legacySource = DeliveryTrustStateWireCodec.LEGACY_SOURCE,
                    fromTs = deliveryDiagnosticLookbackFrom(window.fromTs),
                    throughTs = window.throughTs
                )
            ) { activityRows, deliveryRows ->
                OverviewEventTelemetryInputs(activityRows, deliveryRows)
            }
            combine(
                db.therapyDao().observeLatest(limit = EVENT_TIMELINE_THERAPY_LIMIT),
                container.isfCrRepository.observeRecentTags(sinceTs = window.fromTs),
                db.energyProfileDao().observeAllEvents(),
                container.glucoseCalibrationRepository.observeLatestChecks(limit = EVENT_TIMELINE_CALIBRATION_LIMIT),
                telemetryInputs
            ) { therapyRows, contextTags, plannedRows, calibrationChecks, telemetryRows ->
                OverviewEventTimelineInputs(
                    therapyRows = therapyRows,
                    contextTags = contextTags,
                    plannedRows = plannedRows,
                    calibrationChecks = calibrationChecks,
                    activityTelemetryRows = telemetryRows.activityRows,
                    deliveryTelemetryRows = telemetryRows.deliveryRows
                )
            }
        },
        pastWindowMs = EVENT_TIMELINE_PAST_WINDOW_MS,
        futureWindowMs = EVENT_TIMELINE_FUTURE_WINDOW_MS,
        clock = System::currentTimeMillis,
        gson = container.gson,
        projectionDispatcher = uiStateDispatcher,
        therapyRows = { it.therapyRows },
        maxRetainedRows = EVENT_TIMELINE_THERAPY_LIMIT,
        prepareTherapyEvent = eventTimelineRepository::prepareTherapyEvent,
        project = { input, window, preparedTherapyEvents ->
            val planned = eventTimelineRepository.plannedActivityEvents(
                rows = input.plannedRows.take(EVENT_TIMELINE_PLANNED_DEFINITION_LIMIT),
                fromTs = window.fromTs,
                throughTs = window.throughTs,
                statusAtTs = window.nowTs
            )
            val actual = eventTimelineRepository.actualActivityEventsFromBuckets(
                input.activityTelemetryRows.map { row ->
                    PhysicalActivityBucket(
                        bucketTs = row.bucketTs,
                        firstTs = row.firstTs,
                        lastTs = row.lastTs,
                        peakRatio = row.maxValue,
                        meanRatio = row.meanValue,
                        sampleCount = row.sampleCount,
                        source = row.source,
                        qualityEvidence = row.qualityEvidence
                    )
                }
            )
            val delivery = deliveryDiagnosticEventsForWindow(
                rows = input.deliveryTelemetryRows.map { row ->
                    DeliveryTrustTelemetryValue(row.timestamp, row.source, row.key, row.valueDouble)
                },
                fromTs = window.fromTs,
                throughTs = window.throughTs
            )
            eventTimelineRepository.aggregatePreparedTherapy(
                EventTimelineSources(
                    contextTags = input.contextTags
                        .filter { it.tsStart <= window.throughTs }
                        .map(PhysioContextTag::toEventTimelineEntity),
                    plannedActivity = planned,
                    actualActivity = actual,
                    calibrationEvents = eventTimelineRepository.calibrationEvents(
                        input.calibrationChecks.filter { it.timestamp in window.fromTs..window.throughTs }
                    ),
                    deliveryDiagnostics = delivery
                ),
                preparedTherapyEvents = preparedTherapyEvents,
                nowTs = window.nowTs
            )
        }
    )
        .conflate()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = EventTimeline(emptyList(), System.currentTimeMillis())
        )

    init {
        viewModelScope.launch {
            container.clinicalReportRepository.state
                .map(::clinicalPdfReportIdentity)
                .distinctUntilChanged()
                .collect(clinicalReportPdfExportCoordinator::onReportIdentityChanged)
        }
        viewModelScope.launch(Dispatchers.IO) {
            container.aapsCarbHistorySyncRepository.sync()
        }
        runAutoConnectNow(silent = true)
        normalizeLegacyActionStatusesAsync()
        ensureProfileAnalyticsHealthyAsync(reasonHint = "startup")
    }

    private val primaryUiState: StateFlow<MainUiState> = combine(
        db.glucoseDao().observeLatest(limit = PRIMARY_GLUCOSE_LATEST_LIMIT),
        db.forecastDao().observeLatest(limit = PRIMARY_FORECAST_LATEST_LIMIT),
        db.actionCommandDao().observeLatest(limit = PRIMARY_ACTION_LATEST_LIMIT),
        db.telemetryDao().observeLatestByKeysSince(
            limit = PRIMARY_TELEMETRY_LATEST_LIMIT,
            keys = PRIMARY_TELEMETRY_KEYS,
            since = telemetryObserveAnchorTs - PRIMARY_TELEMETRY_OBSERVE_WINDOW_MS
        ),
        db.syncStateDao().observeAll(),
        container.settingsStore.settings,
        container.glucoseCalibrationRepository.observeLatestChecks(limit = BLOOD_GLUCOSE_CHECK_LATEST_LIMIT),
        container.glucoseCalibrationRepository.observeLatestActiveModel(),
        db.telemetryDao().observeCurrentBySourceAndKey(
            source = CALIBRATION_AUTHORITY_SOURCE,
            key = CALIBRATION_AUTHORITY_TOKEN_KEY
        ),
        container.sensitivityRuntimeRepository.current,
        calibrationSessionEvidence
    ) { values ->
        buildPrimaryUiState(values)
    }
        .conflate()
        .flowOn(uiStateDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUiState()
        )

    private val baseTargetPresentationState: StateFlow<BaseTargetSchedulePresentation> = combine(
        container.settingsStore.settings,
        primaryUiState
    ) { settings, state ->
        BaseTargetPresentationInput(
            schedule = settings.baseTargetSchedule,
            targetManagerMode = settings.targetManagerMode,
            hardMinTargetMmol = settings.safetyMinTargetMmol,
            hardMaxTargetMmol = settings.safetyMaxTargetMmol,
            sensorTrust = when {
                state.sensorQualityBlocked == true -> SensorTrustState.BLOCKED
                state.sensorQualityScore != null &&
                    state.sensorQualityScore!! >= TARGET_UI_TRUSTED_SENSOR_SCORE &&
                    state.sensorQualitySuspectFalseLow != true -> SensorTrustState.TRUSTED
                else -> SensorTrustState.WARN
            },
            sensorAgeHours = state.sensorAgeHours,
            lowRiskLatched = state.targetLowRiskLatched == true,
            forecast5CiLowMmol = state.forecast5mCiLow,
            forecast30CiLowMmol = state.forecast30mCiLow,
            iobUnits = state.latestIobUnits,
            effectiveCobGrams = state.latestCobGrams,
            uamActive = resolveBaseTargetPresentationUamActiveStatic(state)
        )
    }
        .distinctUntilChanged()
        .conflate()
        .map(::resolveBaseTargetPresentation)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BaseTargetSchedulePresentation(
                schedule = BaseTargetSchedule.legacy(5.5),
                effectiveTargetMmol = 5.5,
                autoDeltaMmol = 0.0,
                autoState = CircadianAutoState.OFF
            )
        )

    private suspend fun resolveBaseTargetPresentation(
        input: BaseTargetPresentationInput
    ): BaseTargetSchedulePresentation {
        val now = System.currentTimeMillis()
        return try {
            val effective = container.circadianTargetRepository.resolveEffectiveTarget(
                now = now,
                schedule = input.schedule,
                zoneId = ZoneId.systemDefault(),
                targetManagerMode = input.targetManagerMode,
                hardMinTargetMmol = input.hardMinTargetMmol,
                hardMaxTargetMmol = input.hardMaxTargetMmol,
                gates = EffectiveTargetRuntimeGates(
                    sensorTrust = input.sensorTrust,
                    sensorAgeHours = input.sensorAgeHours,
                    lowRiskLatched = input.lowRiskLatched,
                    forecast5CiLowMmol = input.forecast5CiLowMmol,
                    forecast30CiLowMmol = input.forecast30CiLowMmol,
                    // Presentation-only preview; runtime lowering uses InsulinCycleContext.
                    safetyIobUnits = input.iobUnits,
                    effectiveCobGrams = input.effectiveCobGrams,
                    uamActive = input.uamActive
                )
            )
            BaseTargetSchedulePresentation(
                schedule = input.schedule,
                effectiveTargetMmol = effective.effectiveTargetMmol,
                autoDeltaMmol = effective.autoDeltaMmol,
                autoState = effective.state,
                autoReason = effective.reasonCodes.joinToString(", ").takeIf { it.isNotBlank() }
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Throwable) {
            val manual = BaseTargetSchedulePolicy.resolveManual(
                schedule = input.schedule,
                instant = Instant.ofEpochMilli(now),
                zoneId = ZoneId.systemDefault()
            )
            BaseTargetSchedulePresentation(
                schedule = input.schedule,
                effectiveTargetMmol = manual.targetMmol,
                autoDeltaMmol = 0.0,
                autoState = if (input.schedule.autoEnabled) {
                    CircadianAutoState.WAITING_DATA
                } else {
                    CircadianAutoState.OFF
                },
                autoReason = "ui_resolution_failed"
            )
        }
    }

    val uiState: StateFlow<MainUiState> = combine(
        db.glucoseDao().observeLatest(limit = GLUCOSE_LATEST_LIMIT),
        db.therapyDao().observeLatest(limit = THERAPY_LATEST_LIMIT),
        db.forecastDao().observeLatest(limit = FORECAST_LATEST_LIMIT),
        db.baselineDao().observeLatest(limit = BASELINE_LATEST_LIMIT),
        db.ruleExecutionDao().observeLatest(limit = 20),
        db.actionCommandDao().observeLatest(limit = 40),
        db.auditLogDao().observeLatest(limit = 80),
        db.telemetryDao().observeLatest(limit = TELEMETRY_LATEST_LIMIT),
        container.analyticsRepository.observePatterns(),
        container.analyticsRepository.observeCircadianSlotStats(),
        container.analyticsRepository.observeCircadianTransitionStats(),
        container.analyticsRepository.observeCircadianSnapshots(),
        container.analyticsRepository.observeCircadianReplaySlotStats(),
        container.analyticsRepository.observeProfileEstimate(),
        db.profileEstimateDao().observeHistory(limit = PROFILE_HISTORY_LIMIT),
        container.analyticsRepository.observeProfileSegments(),
        db.syncStateDao().observeAll(),
        container.settingsStore.settings,
        autoConnectState,
        messageState,
        dryRunState,
        cloudReplayState,
        cloudJobsState,
        analysisHistoryState,
        analysisTrendState,
        insightsFilterState,
        db.uamInferenceEventDao().observeLatest(limit = 300),
        container.analyticsRepository.observeIsfCrSnapshot(),
        container.analyticsRepository.observeIsfCrHistory(limit = ISF_CR_HISTORY_LIMIT),
        container.isfCrRepository.observeRecentTags(sinceTs = 0L),
        db.telemetryDao().observeLatestByKeysSince(
            limit = ISF_CR_HISTORY_TELEMETRY_LIMIT,
            keys = ISFCR_HISTORY_TELEMETRY_KEYS,
            since = telemetryObserveAnchorTs - ISF_CR_HISTORY_TELEMETRY_WINDOW_MS
        ),
        db.telemetryDao().observeLatestByKeysSince(
            limit = AI_TUNING_TELEMETRY_LIMIT,
            keys = AI_TUNING_TELEMETRY_KEYS,
            since = telemetryObserveAnchorTs - AI_TUNING_TELEMETRY_WINDOW_MS
        ),
        db.telemetryDao().observeLatestByKeysSince(
            limit = SENSOR_LAG_HISTORY_TELEMETRY_LIMIT,
            keys = SENSOR_LAG_HISTORY_TELEMETRY_KEYS,
            since = telemetryObserveAnchorTs - SENSOR_LAG_HISTORY_TELEMETRY_WINDOW_MS
        ),
        container.glucoseCalibrationRepository.observeLatestChecks(limit = BLOOD_GLUCOSE_CHECK_LATEST_LIMIT),
        container.glucoseCalibrationRepository.observeLatestActiveModel(),
        db.telemetryDao().observeCurrentBySourceAndKey(
            source = CALIBRATION_AUTHORITY_SOURCE,
            key = CALIBRATION_AUTHORITY_TOKEN_KEY
        ),
        db.glucoseDao().observeMinTimestamp(),
        db.glucoseDao().observeMaxTimestamp(),
        activeRouteState,
        container.sensitivityRuntimeRepository.current,
        calibrationSessionEvidence
    ) { values ->
        buildMainUiState(values)
    }
        .conflate()
        .flowOn(uiStateDispatcher)
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState()
    )

    private suspend fun buildMainUiState(values: Array<Any?>): MainUiState {
        @Suppress("UNCHECKED_CAST")
        val rawGlucose = values[0] as List<GlucoseSampleEntity>
        val glucose = GlucoseSanitizer.filterEntities(rawGlucose)
        @Suppress("UNCHECKED_CAST")
        val therapy = TherapySanitizer.filterEntities(values[1] as List<TherapyEventEntity>)
        @Suppress("UNCHECKED_CAST")
        val forecasts = values[2] as List<ForecastEntity>
        @Suppress("UNCHECKED_CAST")
        val baseline = values[3] as List<BaselinePointEntity>
        @Suppress("UNCHECKED_CAST")
        val ruleExec = values[4] as List<RuleExecutionEntity>
        @Suppress("UNCHECKED_CAST")
        val actionCommands = values[5] as List<ActionCommandEntity>
        @Suppress("UNCHECKED_CAST")
        val audits = values[6] as List<AuditLogEntity>
        @Suppress("UNCHECKED_CAST")
        val telemetry = values[7] as List<TelemetrySampleEntity>
        @Suppress("UNCHECKED_CAST")
        val patterns = values[8] as List<PatternWindowEntity>
        @Suppress("UNCHECKED_CAST")
        val circadianSlotStats = values[9] as List<CircadianSlotStatEntity>
        @Suppress("UNCHECKED_CAST")
        val circadianTransitions = values[10] as List<CircadianTransitionStatEntity>
        @Suppress("UNCHECKED_CAST")
        val circadianSnapshots = values[11] as List<CircadianPatternSnapshotEntity>
        @Suppress("UNCHECKED_CAST")
        val circadianReplayStats = values[12] as List<io.aaps.copilot.data.local.entity.CircadianReplaySlotStatEntity>
        val profile = values[13] as ProfileEstimateEntity?
        @Suppress("UNCHECKED_CAST")
        val profileHistory = values[14] as List<ProfileEstimateEntity>
        @Suppress("UNCHECKED_CAST")
        val profileSegments = values[15] as List<ProfileSegmentEstimateEntity>
        @Suppress("UNCHECKED_CAST")
        val syncStates = values[16] as List<SyncStateEntity>
        val settings = values[17] as AppSettings
        val autoConnect = values[18] as AutoConnectUi?
        val message = values[19] as String?
        val dryRun = values[20] as DryRunUi?
        val cloudReplay = values[21] as CloudReplayUiModel?
        val cloudJobs = values[22] as CloudJobsUiModel?
        val analysisHistory = values[23] as CloudAnalysisHistoryUiModel?
        val analysisTrend = values[24] as CloudAnalysisTrendUiModel?
        val insightsFilter = values[25] as InsightsFilterUi
        @Suppress("UNCHECKED_CAST")
        val uamEvents = values[26] as List<UamInferenceEventEntity>
        val isfCrSnapshot = values[27] as IsfCrRealtimeSnapshot?
        @Suppress("UNCHECKED_CAST")
        val isfCrHistory = values[28] as List<IsfCrRealtimeSnapshot>
        @Suppress("UNCHECKED_CAST")
        val physioTags = values[29] as List<PhysioContextTag>
        @Suppress("UNCHECKED_CAST")
        val isfCrTelemetryHistory = values[30] as List<TelemetrySampleEntity>
        @Suppress("UNCHECKED_CAST")
        val aiTuningTelemetry = values[31] as List<TelemetrySampleEntity>
        @Suppress("UNCHECKED_CAST")
        val sensorLagTelemetryHistory = values[32] as List<TelemetrySampleEntity>
        @Suppress("UNCHECKED_CAST")
        val bloodChecks = values[33] as List<BloodGlucoseCheck>
        val activeCalibrationModel = values[34] as GlucoseCalibrationModel?
        val calibrationAuthorityTokenRow = values[35] as TelemetrySampleEntity?
        val glucoseMinTs = values[36] as Long?
        val glucoseMaxTs = values[37] as Long?
        val activeRoute = values[38] as String
        val sensitivityRuntime = values[39] as SensitivityRuntimeSnapshot?
        val analyticsDetailRequested = activeRoute == ROUTE_ANALYTICS
        val reportAnalyticsRequested = activeRoute == ROUTE_ANALYTICS || activeRoute == ROUTE_AI_ANALYSIS
        val now = System.currentTimeMillis()
        val eventTimeline = EventTimelineRepository().aggregate(
            EventTimelineSources(
                therapyEvents = TherapySanitizer.toDomainEvents(therapy, container.gson),
                contextTags = physioTags.map { tag ->
                    tag.toEventTimelineEntity()
                }
            ),
            nowTs = now
        )

        val sortedGlucose = glucose.sortedBy { it.timestamp }
        val currentGlucose = selectOverviewCurrentGlucoseForUi(rawGlucose, now)
        val latest = currentGlucose.latest
        val prev = currentGlucose.previous
        val quality = qualityEvaluator.evaluate(forecasts, glucose)
        val baselineDeltas = baselineComparator.compare(
            forecasts = forecasts.map { it.toDomainForecast() },
            baseline = baseline
        )
        val aiDataCoverageHours = computeAiCoverageHours(
            oldestTs = glucoseMinTs,
            latestTs = glucoseMaxTs,
            nowTs = now
        )
        val aiAnalysisReady = aiDataCoverageHours >= AI_MIN_DATA_HOURS.toDouble()
        val nightscoutSyncTs = syncStates.firstOrNull { it.source == "nightscout" }?.lastSyncedTimestamp
        val cloudPushSyncTs = syncStates.firstOrNull { it.source == "cloud_push" }?.lastSyncedTimestamp
        val latestGlucoseTs = latest?.timestamp
        val latestDataAgeMinutes = minutesSince(now, latestGlucoseTs)
        val nightscoutAgeMinutes = minutesSince(now, nightscoutSyncTs)
        val cloudPushBacklogMinutes = if (latestGlucoseTs != null && cloudPushSyncTs != null) {
            ((latestGlucoseTs - cloudPushSyncTs).coerceAtLeast(0L)) / 60_000L
        } else {
            null
        }
        val staleTag = if (latestDataAgeMinutes != null && latestDataAgeMinutes > settings.staleDataMaxMinutes) "STALE" else "OK"
        val lastSyncIssue = audits.firstOrNull {
            it.level != "INFO" && (
                it.message.contains("sync", ignoreCase = true) ||
                    it.message.contains("cloud_push", ignoreCase = true)
                )
        }?.let { "${it.level}: ${it.message}" }

        val syncStatusLines = listOf(
            "Data age: ${minutesLabel(latestDataAgeMinutes)} ($staleTag)",
            "Nightscout last sync: ${formatTs(nightscoutSyncTs)} (age ${minutesLabel(nightscoutAgeMinutes)})",
            "Cloud push last sync: ${formatTs(cloudPushSyncTs)} (backlog ${minutesLabel(cloudPushBacklogMinutes)})",
        ) + listOfNotNull(lastSyncIssue?.let { "Last sync issue: $it" })

        val lastBroadcastIngest = audits.firstOrNull { it.message == "broadcast_ingest_completed" }
        val lastBroadcastSkip = audits.firstOrNull { it.message == "broadcast_ingest_skipped" }
        val lastLocalNsEntries = audits.firstOrNull { it.message == "local_nightscout_entries_post" }
        val lastLocalNsTreatments = audits.firstOrNull { it.message == "local_nightscout_treatments_post" }
        val lastLocalNsDeviceStatus = audits.firstOrNull { it.message == "local_nightscout_devicestatus_post" }
        val lastLocalNsReactive = audits.firstOrNull { it.message == "local_nightscout_reactive_automation_enqueued" }
        val lastInfusionSetChangeTs = therapy
            .filter { it.type == "infusion_set_change" || it.type == "site_change" || it.type == "cannula_change" }
            .maxOfOrNull { it.timestamp }
        val lastSensorChangeTs = therapy
            .filter { it.type == "sensor_change" }
            .maxOfOrNull { it.timestamp }
        val lastInsulinRefillTs = therapy
            .filter { it.type == "insulin_refill" || it.type == "insulin_change" || it.type == "reservoir_change" }
            .maxOfOrNull { it.timestamp }
        val lastPumpBatteryChangeTs = therapy
            .filter { it.type == "pump_battery_change" || it.type == "battery_change" || it.type == "battery_replacement" }
            .maxOfOrNull { it.timestamp }
        val tlsDiagnosticLines = buildAapsTlsDiagnosticLines(
            settings = settings,
            audits = audits,
            nowTs = now
        )
        val localNightscoutRuntime = LocalNightscoutRuntimeState.value
        val transportStatusLines = buildList {
            val effectiveNightscoutUrl = settings.resolvedNightscoutUrl()
            add(
                "Local Nightscout configuration: " + if (settings.localNightscoutEnabled) {
                    "configured; runtime=${localNightscoutRuntime.status.name}; " +
                        "exactPort=${settings.localNightscoutPort}"
                } else {
                    "disabled; runtime=${localNightscoutRuntime.status.name}"
                }
            )
            add(
                "Effective Nightscout URL: " + if (effectiveNightscoutUrl.isNotBlank()) {
                    effectiveNightscoutUrl
                } else {
                    "not configured"
                }
            )
            add(
                "Inbound local broadcast: " + if (settings.localBroadcastIngestEnabled) {
                    "enabled"
                } else {
                    "disabled"
                }
            )
            add(
                "Strict sender validation: " + if (settings.strictBroadcastSenderValidation) {
                    "enabled"
                } else {
                    "disabled"
                }
            )
            add("Outbound temp target/carbs: Nightscout API -> LOCAL_TREATMENTS -> NS_EMULATOR -> custom relay")
            add(
                "Local fallback relay: " + if (settings.localCommandFallbackEnabled) {
                    "enabled (${settings.localCommandPackage} / ${settings.localCommandAction})"
                } else {
                    "disabled"
                }
            )
            add("Direct AAPS broadcasts: build-dependent, use Action delivery/Audit log for per-channel validation")
            lastBroadcastIngest?.let {
                add("Last broadcast ingest: ${formatTs(it.timestamp)}")
            }
            lastBroadcastSkip?.let {
                add("Last broadcast skip: ${formatTs(it.timestamp)} (${it.message})")
            }
            lastLocalNsEntries?.let {
                add(
                    "Local NS entries POST: ${formatTs(it.timestamp)} " +
                        "(received=${auditMetaField(it, "received") ?: "?"}, inserted=${auditMetaField(it, "inserted") ?: "?"})"
                )
            }
            lastLocalNsTreatments?.let {
                add(
                    "Local NS treatments POST: ${formatTs(it.timestamp)} " +
                        "(received=${auditMetaField(it, "received") ?: "?"}, inserted=${auditMetaField(it, "inserted") ?: "?"}, telemetry=${auditMetaField(it, "telemetry") ?: "?"})"
                )
            }
            lastLocalNsDeviceStatus?.let {
                add(
                    "Local NS devicestatus POST: ${formatTs(it.timestamp)} " +
                        "(received=${auditMetaField(it, "received") ?: "?"}, telemetry=${auditMetaField(it, "telemetry") ?: "?"})"
                )
            }
            lastLocalNsReactive?.let {
                add(
                    "Local NS reactive automation: ${formatTs(it.timestamp)} " +
                        "(channel=${auditMetaField(it, "channel") ?: "?"}, inserted=${auditMetaField(it, "inserted") ?: "?"}, telemetry=${auditMetaField(it, "telemetry") ?: "?"})"
                )
            }
            add("Infusion set change: ${formatTs(lastInfusionSetChangeTs)}")
            add("Sensor change: ${formatTs(lastSensorChangeTs)}")
            add("Insulin refill: ${formatTs(lastInsulinRefillTs)}")
            add("Pump battery change: ${formatTs(lastPumpBatteryChangeTs)}")
            addAll(tlsDiagnosticLines)
        }
        val replacementHistoryLines = buildReplacementHistoryLines(
            therapy = therapy,
            nowTs = now
        )

        val jobStatusLines = if (cloudJobs == null) {
            emptyList()
        } else {
            val header = "Scheduler TZ: ${cloudJobs.timezone}"
            val items = cloudJobs.jobs.map { job ->
                "${job.jobId}: ${job.lastStatus ?: "NEVER"} | last=${formatTs(job.lastRunTs)} | next=${formatTs(job.nextRunTs)}" +
                    (job.lastMessage?.takeIf { it.isNotBlank() }?.let { " | msg=$it" } ?: "")
            }
            listOf(header) + items
        }
        val cloudJobRows = cloudJobs?.jobs?.map { job ->
            CloudJobRowUi(
                jobId = job.jobId,
                lastStatus = job.lastStatus,
                lastRunTs = job.lastRunTs,
                nextRunTs = job.nextRunTs,
                lastMessage = job.lastMessage
            )
        } ?: emptyList()
        val analysisHistoryRows = analysisHistory?.items?.map { item ->
            AnalysisHistoryRowUi(
                runTs = item.runTs,
                date = item.date,
                source = item.source,
                status = item.status,
                summary = item.summary,
                anomalies = item.anomalies,
                recommendations = item.recommendations,
                errorMessage = item.errorMessage
            )
        } ?: emptyList()
        val analysisTrendRows = analysisTrend?.items?.map { item ->
            AnalysisTrendRowUi(
                weekStart = item.weekStart,
                totalRuns = item.totalRuns,
                successRuns = item.successRuns,
                failedRuns = item.failedRuns,
                anomaliesCount = item.anomaliesCount,
                recommendationsCount = item.recommendationsCount
            )
        } ?: emptyList()
        val analysisHistoryLines = analysisHistory?.items?.map { item ->
            val counts = "an=${item.anomalies.size}, rec=${item.recommendations.size}"
            val summaryPreview = item.summary.replace('\n', ' ').trim().take(120)
            val anomalyPreview = item.anomalies.firstOrNull()?.replace('\n', ' ')?.take(80)
            val recommendationPreview = item.recommendations.firstOrNull()?.replace('\n', ' ')?.take(80)
            buildList {
                add("${item.date} ${item.source} ${item.status} | ${formatTs(item.runTs)} | $counts")
                if (summaryPreview.isNotBlank()) add("  summary: $summaryPreview")
                if (!anomalyPreview.isNullOrBlank()) add("  top anomaly: $anomalyPreview")
                if (!recommendationPreview.isNullOrBlank()) add("  top rec: $recommendationPreview")
                if (!item.errorMessage.isNullOrBlank()) add("  error: ${item.errorMessage.take(120)}")
            }.joinToString(separator = "\n")
        } ?: emptyList()
        val analysisTrendLines = analysisTrend?.items?.map { item ->
            "${item.weekStart}: runs=${item.totalRuns}, ok=${item.successRuns}, fail=${item.failedRuns}, an=${item.anomaliesCount}, rec=${item.recommendationsCount}"
        } ?: emptyList()
        val ruleCooldownLines = buildRuleCooldownLines(ruleExec, settings, now)
        val yesterdayProfileLines = if (analyticsDetailRequested) {
            resolveYesterdayProfileLines(
                glucose = sortedGlucose,
                therapy = therapy,
                telemetry = telemetry,
                nowTs = now
            )
        } else {
            emptyList()
        }
        val isfCrDeepLines = if (analyticsDetailRequested) {
            resolveIsfCrDeepLines(
                glucose = sortedGlucose,
                therapy = therapy,
                telemetry = telemetry,
                nowTs = now
            )
        } else {
            emptyList()
        }
        val telemetryByKey = mergeAtomicInsulinRuntimeTelemetryForUi(
            latestByKey = latestTelemetryByKey(telemetry, nowTs = now),
            samples = telemetry
        ).toMutableMap()
        val aiTuningTelemetryByKey = latestTelemetryByKey(aiTuningTelemetry, nowTs = now)
        val aiTuningStatus = resolveAiTuningStatus(
            telemetryByKey = aiTuningTelemetryByKey,
            nowTs = now
        )
        val activityLines = if (analyticsDetailRequested) {
            buildActivitySummaryLines(
                telemetry = telemetry,
                telemetryByKey = telemetryByKey,
                nowTs = now,
                activityPermissionGranted = isActivityRecognitionGranted(),
                audits = audits
            )
        } else {
            emptyList()
        }
        val telemetryCoverageLines = buildTelemetryCoverageLines(
            samples = telemetry,
            latestByKey = telemetryByKey,
            therapyEvents = therapy,
            profile = profile,
            nowTs = now
        )
        val telemetryLines = buildTelemetryLines(
            samples = telemetry,
            latestByKey = telemetryByKey
        )
        val actionLines = buildActionLines(actionCommands)
        val glucoseHistoryPoints = buildGlucoseHistoryPointsForUi(sortedGlucose, nowTs = now)
        val currentCalibrationAuthorityForUi = loadUiCalibrationAuthority(
            db = db,
            nowTs = now,
            observedAuthorityToken = calibrationAuthorityTokenRow?.valueText
        )
        val currentCalibrationModelForUi = currentCalibrationAuthorityForUi.activeModel
        val acceptedForecastTuple = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = forecasts,
            telemetry = telemetry,
            snapshot = sensitivityRuntime,
            currentSettings = settings.sensitivityRuntimeIdentity(),
            authoritativeNowTs = now,
            calibrationAuthority = AcceptedCalibrationAuthority(
                activeModel = currentCalibrationModelForUi,
                authorityToken = calibrationAuthorityTokenRow
                    ?.valueText?.takeIf(String::isNotBlank) ?: ACCEPTED_CALIBRATION_NONE,
                currentSessionKey = currentCalibrationAuthorityForUi.currentSessionKey,
                contextValid = currentCalibrationAuthorityForUi.contextValid
            )
        )
        val latestForecast5Row = acceptedForecastTuple.forecastsByHorizon[5]
        val latestForecast30Row = acceptedForecastTuple.forecastsByHorizon[30]
        val latestForecast60Row = acceptedForecastTuple.forecastsByHorizon[60]
        val forecast5Latest = latestForecast5Row?.valueMmol
        val forecast30Latest = latestForecast30Row?.valueMmol
        val forecast60Latest = latestForecast60Row?.valueMmol
        val forecast5CiLow = latestForecast5Row?.ciLow
        val forecast5CiHigh = latestForecast5Row?.ciHigh
        val forecast30CiLow = latestForecast30Row?.ciLow
        val forecast30CiHigh = latestForecast30Row?.ciHigh
        val forecast60CiLow = latestForecast60Row?.ciLow
        val forecast60CiHigh = latestForecast60Row?.ciHigh
        val latestIobUnits = resolveEffectivePositiveIobForUi(telemetryByKey)
            ?.coerceIn(0.0, 60.0)
        val latestIobRealUnits = resolveMetricByRecency(
            preferred = telemetryByKey["iob_net_units"],
            fallback = telemetryByKey["iob_real_units"]
        )
        val latestIobBolusUnits = telemetryByKey["iob_bolus_units"].toNumericValue()
        val latestIobBasalUnits = telemetryByKey["iob_basal_units"].toNumericValue()
        val latestInsulinActivity = telemetryByKey["insulin_activity"].toNumericValue()
        val latestIobRuntimeSource = telemetryByKey["iob_runtime_source"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val latestIobRuntimeConfidence = telemetryByKey["iob_runtime_confidence"].toNumericValue()
        val latestIobRuntimeTimestamp = telemetryByKey["iob_runtime_timestamp_ms"]
            .toNumericValue()?.toLong()?.takeIf { it > 0L }
        val latestIobEvidenceTimestamp = telemetryByKey["iob_runtime_evidence_timestamp_ms"]
            .toNumericValue()?.toLong()?.takeIf { it > 0L }
        val latestIobTherapyCoverage = telemetryByKey["iob_runtime_therapy_coverage"].toNumericValue()
        val latestIobRuntimeFallbackReason = telemetryByKey["iob_runtime_fallback_reason"]
            ?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val latestCobGrams = resolveMetricByRecency(
            preferred = telemetryByKey["cob_effective_grams"],
            fallback = telemetryByKey["cob_grams"]
        )
            ?.coerceIn(0.0, io.aaps.copilot.domain.nutrition.MealCarbLimits.MAX_MANUAL_MEAL_GRAMS)
        val insulinRealOnsetMinutes = telemetryByKey["insulin_real_onset_min"].toNumericValue()
        val insulinRealProfileCurveCompact = telemetryByKey["insulin_profile_real_curve_compact"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val insulinRealProfileUpdatedTs = telemetryByKey["insulin_profile_real_updated_ts"]
            .toNumericValue()
            ?.toLong()
        val insulinRealProfileConfidence = telemetryByKey["insulin_profile_real_confidence"]
            .toNumericValue()
            ?.coerceIn(0.0, 1.0)
        val insulinRealProfileSamples = telemetryByKey["insulin_profile_real_samples"]
            .toNumericValue()
            ?.toInt()
            ?.coerceAtLeast(0)
        val insulinRealProfileOnsetMinutes = telemetryByKey["insulin_profile_real_onset_min"]
            .toNumericValue()
        val insulinRealProfilePeakMinutes = telemetryByKey["insulin_profile_real_peak_min"]
            .toNumericValue()
        val insulinRealProfileScale = telemetryByKey["insulin_profile_real_scale"]
            .toNumericValue()
        val insulinRealProfileStatus = telemetryByKey["insulin_profile_real_status"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val latestActivityRatio = telemetryByKey["activity_ratio"].toNumericValue()
        val latestStepsCount = telemetryByKey["steps_count"].toNumericValue()
        val patternPriorConfidence = telemetryByKey["pattern_prior_confidence"].toNumericValue()
        val patternPriorBgMedian = telemetryByKey["pattern_prior_bg_median_mmol"].toNumericValue()
        val patternPrior30 = telemetryByKey["pattern_prior_30_mmol"].toNumericValue()
        val patternPrior60 = telemetryByKey["pattern_prior_60_mmol"].toNumericValue()
        val patternResidualBias30 = telemetryByKey["pattern_prior_residual_bias_30_mmol"].toNumericValue()
        val patternResidualBias60 = telemetryByKey["pattern_prior_residual_bias_60_mmol"].toNumericValue()
        val patternAcuteAttenuation = telemetryByKey["pattern_prior_acute_attenuation"].toNumericValue()
        val patternStaleBlocked = telemetryByKey["pattern_prior_stale_blocked"].toNumericValue()?.let { it >= 0.5 }
        val patternSegmentSource = telemetryByKey["pattern_prior_segment_source"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val calculatedUamFlag = telemetryByKey["uam_calculated_flag"].toNumericValue()
        val calculatedUamConfidence = telemetryByKey["uam_calculated_confidence"].toNumericValue()
        val calculatedUamCarbs = telemetryByKey["uam_calculated_carbs_grams"].toNumericValue()
        val calculatedUamDelta5 = telemetryByKey["uam_calculated_delta5_mmol"].toNumericValue()
        val calculatedUamRise15 = telemetryByKey["uam_calculated_rise15_mmol"].toNumericValue()
        val calculatedUamRise30 = telemetryByKey["uam_calculated_rise30_mmol"].toNumericValue()
        val uci0Mmol5m = resolveUiUamImpactStatic(
            telemetryByKey["uam_runtime_impact_mmol5"].toNumericValue()
        )
        val inferredUamFlag = telemetryByKey["uam_inferred_flag"].toNumericValue()
        val inferredUamConfidence = telemetryByKey["uam_inferred_confidence"].toNumericValue()
        val inferredUamCarbs = telemetryByKey["uam_inferred_carbs_grams"].toNumericValue()
        val inferredUamIngestionTs = telemetryByKey["uam_inferred_ingestion_ts"].toNumericValue()?.toLong()
        val inferredUamBoostMode = telemetryByKey["uam_inferred_boost_mode"].toNumericValue()
        val inferredUamManualCob = telemetryByKey["uam_manual_cob_grams"].toNumericValue()
        val inferredUamLastGAbs = telemetryByKey["uam_inferred_gabs_last5_g"].toNumericValue()
        val uamRuntime = resolveFullUiUamRuntimeSnapshotStatic(
            telemetryByKey = telemetryByKey,
            nowTs = now,
            acceptedCycleId = acceptedForecastTuple.sensitivity?.forecastCycleId
        )
        val dailyReportBundle = if (reportAnalyticsRequested) {
            buildDailyReportBundle(telemetryByKey)
        } else {
            emptyDailyReportBundle()
        }
        val sensorQualityScore = telemetryByKey["sensor_quality_score"].toNumericValue()
        val sensorQualityBlocked = telemetryByKey["sensor_quality_blocked"].toNumericValue()?.let { it >= 0.5 }
        val sensorQualityReason = telemetryByKey["sensor_quality_reason"]?.valueText
        val sensorQualitySuspectFalseLow = telemetryByKey["sensor_quality_suspect_false_low"].toNumericValue()
            ?.let { it >= 0.5 }
        val targetLowRiskLatched = telemetryByKey["target_low_risk_latched"].toNumericValue()
            ?.let { it >= 0.5 }
        val correctedGlucoseMmol = telemetryByKey["sensor_lag_corrected_glucose_mmol"].toNumericValue()
        val rawGlucoseMmol = latest?.mmol
        val calibrationModelForUi = resolveOverviewCalibrationModelForUi(
            acceptedTuple = acceptedForecastTuple,
            currentModel = currentCalibrationModelForUi
        )
        val calibrationTelemetrySourceTs = telemetryByKey["glucose_calibration_source_ts"]
            .toNumericValue()
            ?.toLong()
        val calibratedGlucoseMmol = resolveUiCalibratedGlucose(
            pointTs = latest?.timestamp,
            rawMmol = rawGlucoseMmol,
            telemetryCalibratedMmol = telemetryByKey["glucose_calibrated_mmol"].toNumericValue(),
            telemetryCalibrationSourceTs = calibrationTelemetrySourceTs,
            activeModel = calibrationModelForUi
        )
        val calibrationGain = calibrationModelForUi?.gain
        val calibrationOffsetMmol = calibrationModelForUi?.offsetMmol
        val calibrationConfidence = calibrationModelForUi?.confidence
        val calibrationLastCheckAgeMinutes = telemetryByKey["glucose_calibration_last_check_age_minutes"].toNumericValue()
            ?: bloodChecks.firstOrNull()?.timestamp?.let { checkTs ->
                ((now - checkTs).coerceAtLeast(0L)) / 60_000.0
            }
        val calibrationModelType = calibrationModelForUi?.modelType?.name
        val calibrationStatus = calibrationModelForUi?.status?.name
        val glucoseAlertState = telemetryByKey["glucose_alert_state"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val glucoseAlertDirection = telemetryByKey["glucose_alert_direction"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val glucoseAlertDisableReason = telemetryByKey["glucose_alert_disable_reason"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val glucoseAlertSoftActive = telemetryByKey["glucose_alert_soft_active"].toNumericValue()?.let { it >= 0.5 }
        val glucoseAlertStrongActive = telemetryByKey["glucose_alert_strong_active"].toNumericValue()?.let { it >= 0.5 }
        val glucoseAlertSoftLastTs = telemetryByKey["glucose_alert_soft_last_ts"].toNumericValue()?.toLong()
        val glucoseAlertStrongLastTs = telemetryByKey["glucose_alert_strong_last_ts"].toNumericValue()?.toLong()
        val glucoseAlertPred30 = telemetryByKey["glucose_alert_pred30"].toNumericValue()
        val glucoseAlertCiLow30 = telemetryByKey["glucose_alert_ci_low30"].toNumericValue()
        val glucoseAlertCiHigh30 = telemetryByKey["glucose_alert_ci_high30"].toNumericValue()
        val glucoseAlertLowThreshold = telemetryByKey["glucose_alert_low_threshold"].toNumericValue()
        val glucoseAlertHighThreshold = telemetryByKey["glucose_alert_high_threshold"].toNumericValue()
        val glucoseAlertUrgentLowThreshold = telemetryByKey["glucose_alert_urgent_low_threshold"].toNumericValue()
        val sensorLagMinutes = telemetryByKey["sensor_lag_minutes"].toNumericValue()
        val sensorAgeHours = telemetryByKey["sensor_age_hours"].toNumericValue()
            ?: telemetryByKey["sensor_lag_age_hours"].toNumericValue()
        val sensorAgeSource = telemetryByKey["sensor_lag_age_source"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val sensorLagConfidence = telemetryByKey["sensor_lag_confidence"].toNumericValue()
        val sensorLagWearBucket = telemetryByKey["sensor_lag_wear_bucket"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val sensorLagSourceConfidence = telemetryByKey["sensor_lag_source_confidence"].toNumericValue()
        val sensorLagTrendConsistency = telemetryByKey["sensor_lag_trend_consistency"].toNumericValue()
        val sensorLagReplayMultiplier = telemetryByKey["sensor_lag_replay_multiplier"].toNumericValue()
        val sensorLagEffectiveLagMinutes = telemetryByKey["sensor_lag_effective_lag_minutes"].toNumericValue()
        val sensorLagEffectiveCorrectionCap = telemetryByKey["sensor_lag_effective_correction_cap"].toNumericValue()
        val therapyHistorySourceMode = telemetryByKey["therapy_history_source_mode"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val therapyHistoryRawInsulin30d = telemetryByKey["therapy_history_raw_insulin_30d"].toNumericValue()?.roundToInt()
        val therapyHistoryInferredInsulin30d =
            telemetryByKey["therapy_history_inferred_insulin_30d"].toNumericValue()?.roundToInt()
        val therapyHistoryRealFetchedInsulin30d =
            telemetryByKey["therapy_history_real_fetched_insulin_30d"].toNumericValue()?.roundToInt()
        val therapyHistoryRecoveredInsulin30d =
            telemetryByKey["therapy_history_recovered_insulin_30d"].toNumericValue()?.roundToInt()
        val therapyHistoryUsableInsulin30d =
            telemetryByKey["therapy_history_usable_insulin_30d"].toNumericValue()?.roundToInt()
        val therapyHistoryBootstrapNeeded = telemetryByKey["therapy_history_bootstrap_needed"]
            ?.valueText
            ?.toBooleanStrictOrNull()
            ?: telemetryByKey["therapy_history_bootstrap_needed"].toNumericValue()?.let { it >= 0.5 }
        val therapyHistoryPlateauOnly = telemetryByKey["therapy_history_plateau_only"]
            ?.valueText
            ?.toBooleanStrictOrNull()
            ?: telemetryByKey["therapy_history_plateau_only"].toNumericValue()?.let { it >= 0.5 }
        val therapyHistorySyntheticRatioPct = telemetryByKey["therapy_history_synthetic_ratio_pct"].toNumericValue()
        val therapyHistoryLastSyncTreatmentCount =
            telemetryByKey["therapy_history_last_sync_treatment_count"].toNumericValue()?.roundToInt()
        val therapyHistoryLastSyncInsulinLikeCount =
            telemetryByKey["therapy_history_last_sync_insulin_like_count"].toNumericValue()?.roundToInt()
        val therapyHistoryLastSyncCarbLikeCount =
            telemetryByKey["therapy_history_last_sync_carb_like_count"].toNumericValue()?.roundToInt()
        val therapyHistoryLastSyncLocalActionCount =
            telemetryByKey["therapy_history_last_sync_local_action_count"].toNumericValue()?.roundToInt()
        val therapyHistoryUpstreamTempTargetOnly =
            telemetryByKey["therapy_history_upstream_temp_target_only"]
                ?.valueText
                ?.toBooleanStrictOrNull()
                ?: telemetryByKey["therapy_history_upstream_temp_target_only"].toNumericValue()?.let { it >= 0.5 }
        val sensorLagMode = telemetryByKey["sensor_lag_mode"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val sensorLagDisableReason = telemetryByKey["sensor_lag_disable_reason"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val sensorLagHistoryMinTs = now - SENSOR_LAG_HISTORY_WINDOW_MS
        val calibrationChartMinTs = now - 24L * 60L * 60L * 1000L
        val calibrationRawHistoryPoints = sortedGlucose
            .asSequence()
            .filter { it.timestamp >= calibrationChartMinTs }
            .map { ChartPointUi(ts = it.timestamp, value = it.mmol) }
            .toList()
        val calibrationResolvedHistoryPoints = sortedGlucose
            .asSequence()
            .filter { it.timestamp >= calibrationChartMinTs }
            .map { sample ->
                val resolved = calibrationModelForUi?.let { model ->
                    applyGlucoseCalibrationModelForUi(sample.timestamp, sample.mmol, model)
                } ?: sample.mmol
                ChartPointUi(ts = sample.timestamp, value = resolved)
            }
            .toList()
        val calibrationCheckPoints = bloodChecks
            .asSequence()
            .filter { it.timestamp >= calibrationChartMinTs }
            .map { ChartPointUi(ts = it.timestamp, value = it.mmol) }
            .toList()
        val sensorLagLagTrendPoints = buildTelemetrySeriesFromKeys(
            telemetry = sensorLagTelemetryHistory,
            preferredKeys = listOf("sensor_lag_minutes"),
            min = 0.0,
            max = 30.0,
            minTs = sensorLagHistoryMinTs
        ).map { ChartPointUi(ts = it.ts, value = it.value) }
        val sensorLagCorrectionTrendPoints = buildTelemetrySeriesFromKeys(
            telemetry = sensorLagTelemetryHistory,
            preferredKeys = listOf("sensor_lag_correction_mmol"),
            min = -2.0,
            max = 2.0,
            minTs = sensorLagHistoryMinTs
        ).map { ChartPointUi(ts = it.ts, value = it.value) }
        val sensorLagAgeTrendPoints = buildTelemetrySeriesFromKeys(
            telemetry = sensorLagTelemetryHistory,
            preferredKeys = listOf("sensor_lag_age_hours"),
            min = 0.0,
            max = 500.0,
            minTs = sensorLagHistoryMinTs
        )
        val sensorLagModeSegments = buildTelemetryTextSegments(
            telemetry = sensorLagTelemetryHistory,
            key = "sensor_lag_mode",
            minTs = sensorLagHistoryMinTs,
            windowEndTs = now
        ) { raw ->
            raw.trim()
                .uppercase(Locale.US)
                .takeIf { it in setOf("OFF", "SHADOW", "ACTIVE") }
        }.map { SensorLagTimelineSegmentUi(startTs = it.startTs, endTs = it.endTs, label = it.label) }
        val sensorLagBucketSegments = buildTimelineSegmentsFromValues(
            values = sensorLagAgeTrendPoints,
            minTs = sensorLagHistoryMinTs,
            windowEndTs = now
        ) { value ->
            sensorLagAgeBucketId(value)
        }.map { SensorLagTimelineSegmentUi(startTs = it.startTs, endTs = it.endTs, label = it.label) }
        val sensorLagTrendStartAgeHours = sensorLagAgeTrendPoints.firstOrNull()?.value
        val sensorLagTrendEndAgeHours = sensorLagAgeTrendPoints.lastOrNull()?.value ?: sensorAgeHours
        val smbContextSummary = buildSmbContextSummary(
            telemetryByKey = telemetryByKey,
            baseTargetMmol = settings.baseTargetMmol
        )
        val controllerWeightedError = if (forecast30Latest != null && forecast60Latest != null) {
            val e30 = forecast30Latest - settings.baseTargetMmol
            val e60 = forecast60Latest - settings.baseTargetMmol
            0.65 * e30 + 0.35 * e60
        } else {
            null
        }
        val latestAdaptiveExecution = ruleExec.firstOrNull { it.ruleId == AdaptiveTargetControllerRule.RULE_ID }
        val controllerAction = latestAdaptiveExecution?.actionJson?.let { parseRuleActionJson(it) }
        val controllerReasons = latestAdaptiveExecution?.reasonsJson?.let { parseRuleReasonsJson(it) }.orEmpty()
        val controllerConfidence = parseConfidenceFromReasons(controllerReasons)
        val controllerReason = controllerReasons.firstOrNull()
        val adaptiveAuditLines = audits
            .filter { it.message.startsWith("adaptive_controller_") }
            .take(10)
            .map { "${formatTs(it.timestamp)} ${it.level} ${it.message}" }
        val lastAction = actionCommands.firstOrNull()?.let { command ->
            val target = payloadDouble(command.payloadJson, "targetMmol")
            val duration = payloadDouble(command.payloadJson, "durationMinutes")?.toInt()
            val carbs = payloadDouble(command.payloadJson, "carbsGrams", "carbs", "grams")
            val summary = when {
                target != null && duration != null -> "target=${String.format(Locale.US, "%.2f", target)} mmol/L for ${duration}m"
                carbs != null -> "carbs=${String.format(Locale.US, "%.1f", carbs)} g"
                else -> command.payloadJson.take(100)
            }
            LastActionRowUi(
                type = command.type,
                status = command.status,
                timestamp = command.timestamp,
                tempTargetMmol = target,
                durationMinutes = duration,
                carbsGrams = carbs,
                idempotencyKey = command.idempotencyKey,
                payloadSummary = summary
            )
        }
        val manualCobActive = settings.uamDisableWhenManualCobActive &&
            ((inferredUamManualCob ?: 0.0) > settings.uamManualCobThresholdG)
        val uamEventRows = uamEvents
            .sortedByDescending { it.updatedAt }
            .map { event ->
                val manualNearby = hasManualCarbsNearby(
                    therapy = therapy,
                    centerTs = event.ingestionTs,
                    mergeWindowMinutes = settings.uamManualMergeWindowMinutes
                )
                val exportBlockedReason = when {
                    !settings.enableUamExportToAaps -> "export_disabled"
                    settings.dryRunExport -> "dry_run"
                    settings.uamExportMode == UamExportMode.OFF -> "mode_off"
                    settings.uamDisableIfManualCarbsNearby && manualNearby -> "manual_carbs_nearby"
                    manualCobActive -> "manual_cob_active"
                    event.state != UamInferenceState.CONFIRMED.name -> "event_not_confirmed"
                    else -> null
                }
                UamEventRowUi(
                    id = event.id,
                    state = event.state,
                    mode = event.mode,
                    createdAt = event.createdAt,
                    updatedAt = event.updatedAt,
                    ingestionTs = event.ingestionTs,
                    carbsDisplayG = event.carbsDisplayG,
                    confidence = event.confidence,
                    exportSeq = event.exportSeq,
                    exportedGrams = event.exportedGrams,
                    tag = UamTagCodec.buildTag(
                        eventId = event.id,
                        seq = event.exportSeq.coerceAtLeast(1),
                        mode = runCatching { io.aaps.copilot.domain.predict.UamMode.valueOf(event.mode) }
                            .getOrDefault(io.aaps.copilot.domain.predict.UamMode.NORMAL)
                    ),
                    manualCarbsNearby = manualNearby,
                    manualCobActive = manualCobActive,
                    exportBlockedReason = exportBlockedReason
                )
            }
        val auditRecords = buildAuditRecords(
            audits = audits,
            ruleExecutions = ruleExec,
            actions = actionCommands
        )
        val profileSegmentLines = if (analyticsDetailRequested) {
            profileSegments
                .sortedWith(compareBy<ProfileSegmentEstimateEntity> { it.dayType }.thenBy { it.timeSlot })
                .map { segment ->
                    val isfText = segment.isfMmolPerUnit?.let { String.format("%.2f", it) } ?: "-"
                    val crText = segment.crGramPerUnit?.let { String.format("%.2f", it) } ?: "-"
                    "${segment.dayType} ${segment.timeSlot}: ISF=$isfText, CR=$crText, conf=${String.format("%.0f", segment.confidence * 100)}%, n(ISF/CR)=${segment.isfSampleCount}/${segment.crSampleCount}"
                }
        } else {
            emptyList()
        }
        val profileHistoryPoints = if (analyticsDetailRequested) {
            resolveIsfCrHistoryPoints(
                profileHistory = profileHistory,
                isfCrHistory = isfCrHistory,
                telemetry = isfCrTelemetryHistory
            )
        } else {
            emptyList()
        }
        val profileHistoryOverlayPoints = if (analyticsDetailRequested) {
            resolveIsfCrHistoryOverlayPoints(
                historyPoints = profileHistoryPoints,
                telemetry = isfCrTelemetryHistory
            )
        } else {
            emptyList()
        }
        val circadianSections = if (analyticsDetailRequested) {
            resolveCircadianPatternSections(
                slotStats = circadianSlotStats,
                transitionStats = circadianTransitions,
                snapshots = circadianSnapshots,
                replayStats = circadianReplayStats,
                nowTs = now
            )
        } else {
            emptyList()
        }
        val freshRealtimeSnapshot = isfCrSnapshot?.takeIf { snapshot ->
            val ageMs = now - snapshot.ts
            ageMs in 0L..ISFCR_REALTIME_UI_FRESHNESS_MS
        }
        val snapshotFactorLines = if (analyticsDetailRequested) {
            when {
                freshRealtimeSnapshot != null -> {
                    freshRealtimeSnapshot.factors
                        .entries
                        .sortedBy { it.key }
                        .map { entry ->
                            "${entry.key.replace('_', ' ')}=${String.format(Locale.US, "%.3f", entry.value)}"
                        }
                }

                isfCrSnapshot != null -> {
                    listOf(
                        "stale realtime snapshot age=${minutesLabel(((now - isfCrSnapshot.ts).coerceAtLeast(0L)) / 60_000L)}"
                    )
                }

                else -> emptyList()
            }
        } else {
            emptyList()
        }
        val runtimeDiagnosticsSnapshot = if (analyticsDetailRequested) {
            buildIsfCrRuntimeDiagnosticsSnapshot(audits)
        } else {
            null
        }
        val runtimeDiagnosticsLines = if (analyticsDetailRequested) {
            buildIsfCrRuntimeDiagnosticsLines(runtimeDiagnosticsSnapshot)
        } else {
            emptyList()
        }
        val activationGateLines = if (analyticsDetailRequested) {
            buildIsfCrActivationGateLines(audits, telemetryByKey)
        } else {
            emptyList()
        }
        val isfCrDroppedReasons24h = if (analyticsDetailRequested) {
            buildIsfCrDroppedReasonSummaryLines(
                audits = audits,
                nowTs = now,
                windowMs = 24L * 60L * 60L * 1000L
            )
        } else {
            emptyList()
        }
        val isfCrDroppedReasons7d = if (analyticsDetailRequested) {
            buildIsfCrDroppedReasonSummaryLines(
                audits = audits,
                nowTs = now,
                windowMs = 7L * 24L * 60L * 60L * 1000L
            )
        } else {
            emptyList()
        }
        val isfCrWearImpact24h = if (analyticsDetailRequested) {
            buildIsfCrWearImpactSummaryLines(
                audits = audits,
                nowTs = now,
                windowMs = 24L * 60L * 60L * 1000L
            )
        } else {
            emptyList()
        }
        val isfCrWearImpact7d = if (analyticsDetailRequested) {
            buildIsfCrWearImpactSummaryLines(
                audits = audits,
                nowTs = now,
                windowMs = 7L * 24L * 60L * 60L * 1000L
            )
        } else {
            emptyList()
        }
        val physioTagLines = if (analyticsDetailRequested) {
            physioTags
                .sortedByDescending { it.tsStart }
                .take(12)
                .map { tag ->
                    val severity = String.format(Locale.US, "%.2f", tag.severity.coerceIn(0.0, 1.0))
                    "${tag.tagType} (sev=$severity, ${formatTs(tag.tsStart)}..${formatTs(tag.tsEnd)})"
                }
        } else {
            emptyList()
        }
        val insightsFilterLabel = buildInsightsFilterLabel(insightsFilter)
        val deepIsfCrLinesCombined = if (analyticsDetailRequested) {
            buildList {
                freshRealtimeSnapshot?.let { snapshot ->
                    add(
                        "Realtime snapshot: mode=${snapshot.mode.name}, " +
                            "ISF=${String.format(Locale.US, "%.2f", snapshot.isfEff)}, " +
                            "CR=${String.format(Locale.US, "%.2f", snapshot.crEff)}, " +
                            "conf=${String.format(Locale.US, "%.0f%%", snapshot.confidence * 100)}, " +
                            "q=${String.format(Locale.US, "%.0f%%", snapshot.qualityScore * 100)}"
                    )
                    add(
                        "Realtime CI: ISF[${String.format(Locale.US, "%.2f", snapshot.ciIsfLow)}..${String.format(Locale.US, "%.2f", snapshot.ciIsfHigh)}], " +
                            "CR[${String.format(Locale.US, "%.2f", snapshot.ciCrLow)}..${String.format(Locale.US, "%.2f", snapshot.ciCrHigh)}]"
                    )
                } ?: isfCrSnapshot?.let { staleSnapshot ->
                    add(
                        "Realtime snapshot stale: ${minutesLabel(((now - staleSnapshot.ts).coerceAtLeast(0L)) / 60_000L)} old"
                    )
                }
                addAll(runtimeDiagnosticsLines)
                addAll(snapshotFactorLines.take(6))
                addAll(isfCrDeepLines)
            }
        } else {
            emptyList()
        }

        val result = MainUiState()
        result.apply {
            this.nightscoutUrl = settings.nightscoutUrl
            this.cloudUrl = settings.cloudBaseUrl
            this.uiStyle = settings.uiStyle.name
            this.exportUri = settings.exportFolderUri
            this.killSwitch = settings.killSwitch
            this.powerSaveUntilMs = settings.powerSaveUntilMs
            this.localNightscoutEnabled = settings.localNightscoutEnabled
            this.localNightscoutPort = settings.localNightscoutPort
            this.localNightscoutLegacyMigrationAcknowledged =
                settings.localNightscoutLegacyMigrationAcknowledged
            this.resolvedNightscoutUrl = settings.resolvedNightscoutUrl()
            this.localBroadcastIngestEnabled = settings.localBroadcastIngestEnabled
            this.strictBroadcastSenderValidation = settings.strictBroadcastSenderValidation
            this.localCommandFallbackEnabled = settings.localCommandFallbackEnabled
            this.localCommandPackage = settings.localCommandPackage
            this.localCommandAction = settings.localCommandAction
            this.insulinProfileId = settings.insulinProfileId
            this.enableUamInference = settings.enableUamInference
            this.enableUamBoost = settings.enableUamBoost
            this.enableUamExportToAaps = settings.enableUamExportToAaps
            this.uamExportMode = settings.uamExportMode.name
            this.dryRunExport = settings.dryRunExport
            this.enableUamAutoExportCap = settings.enableUamAutoExportCap
            this.uamAutoExportCapGrams = settings.uamAutoExportCapGrams
            this.uamLearnedMultiplier = settings.uamLearnedMultiplier
            this.uamMinSnackG = settings.uamMinSnackG
            this.uamMaxSnackG = settings.uamMaxSnackG
            this.uamSnackStepG = settings.uamSnackStepG
            this.uamBackdateMinutesDefault = settings.uamBackdateMinutesDefault
            this.uamExportMinIntervalMin = settings.uamExportMinIntervalMin
            this.uamExportMaxBackdateMin = settings.uamExportMaxBackdateMin
            this.sensorLagCorrectionMode = settings.sensorLagCorrectionMode.name
            this.targetManagerMode = settings.targetManagerMode.name
            this.targetManagerModeManualOverride = settings.targetManagerModeManualOverride
            this.targetManagerCopilotPriorityEnabled = settings.targetManagerCopilotPriorityEnabled
            this.targetManagerPolicyRevision = settings.targetManagerPolicyRevision
            this.targetManagerLiveStatus = resolveTargetManagerLiveStatusForUi(telemetry)
            this.circadianPatternsEnabled = settings.circadianPatternsEnabled
            this.circadianStableLookbackDays = settings.circadianStableLookbackDays
            this.circadianRecencyLookbackDays = settings.circadianRecencyLookbackDays
            this.circadianUseWeekendSplit = settings.circadianUseWeekendSplit
            this.circadianUseReplayResidualBias = settings.circadianUseReplayResidualBias
            this.circadianForecastWeight30 = settings.circadianForecastWeight30
            this.circadianForecastWeight60 = settings.circadianForecastWeight60
            this.softAlertAudioUri = settings.softAlertAudioUri
            this.softAlertAudioDisplayName = settings.softAlertAudioDisplayName
            this.criticalAlertAudio1Uri = settings.criticalAlertAudio1Uri
            this.criticalAlertAudio1DisplayName = settings.criticalAlertAudio1DisplayName
            this.criticalAlertAudio2Uri = settings.criticalAlertAudio2Uri
            this.criticalAlertAudio2DisplayName = settings.criticalAlertAudio2DisplayName
            this.isfRuntimeSourcePreference = "UNAVAILABLE"
            this.crRuntimeSourcePreference = "UNAVAILABLE"
            this.isfCrShadowMode = settings.isfCrShadowMode
            this.isfCrConfidenceThreshold = settings.isfCrConfidenceThreshold
            this.isfCrUseActivity = settings.isfCrUseActivity
            this.isfCrUseManualTags = settings.isfCrUseManualTags
            this.isfCrMinIsfEvidencePerHour = settings.isfCrMinIsfEvidencePerHour
            this.isfCrMinCrEvidencePerHour = settings.isfCrMinCrEvidencePerHour
            this.isfCrCrMaxGapMinutes = settings.isfCrCrMaxGapMinutes
            this.isfCrCrMaxSensorBlockedRatePct = settings.isfCrCrMaxSensorBlockedRatePct
            this.isfCrCrMaxUamAmbiguityRatePct = settings.isfCrCrMaxUamAmbiguityRatePct
            this.isfCrSnapshotRetentionDays = settings.isfCrSnapshotRetentionDays
            this.isfCrEvidenceRetentionDays = settings.isfCrEvidenceRetentionDays
            this.isfCrAutoActivationEnabled = settings.isfCrAutoActivationEnabled
            this.isfCrAutoActivationLookbackHours = settings.isfCrAutoActivationLookbackHours
            this.isfCrAutoActivationMinSamples = settings.isfCrAutoActivationMinSamples
            this.isfCrAutoActivationMinMeanConfidence = settings.isfCrAutoActivationMinMeanConfidence
            this.isfCrAutoActivationMaxMeanAbsIsfDeltaPct = settings.isfCrAutoActivationMaxMeanAbsIsfDeltaPct
            this.isfCrAutoActivationMaxMeanAbsCrDeltaPct = settings.isfCrAutoActivationMaxMeanAbsCrDeltaPct
            this.isfCrAutoActivationMinSensorQualityScore = settings.isfCrAutoActivationMinSensorQualityScore
            this.isfCrAutoActivationMinSensorFactor = settings.isfCrAutoActivationMinSensorFactor
            this.isfCrAutoActivationMaxWearConfidencePenalty = settings.isfCrAutoActivationMaxWearConfidencePenalty
            this.isfCrAutoActivationMaxSensorAgeHighRatePct = settings.isfCrAutoActivationMaxSensorAgeHighRatePct
            this.isfCrAutoActivationMaxSuspectFalseLowRatePct = settings.isfCrAutoActivationMaxSuspectFalseLowRatePct
            this.isfCrAutoActivationMinDayTypeRatio = settings.isfCrAutoActivationMinDayTypeRatio
            this.isfCrAutoActivationMaxDayTypeSparseRatePct = settings.isfCrAutoActivationMaxDayTypeSparseRatePct
            this.isfCrAutoActivationRequireDailyQualityGate = settings.isfCrAutoActivationRequireDailyQualityGate
            this.isfCrAutoActivationDailyRiskBlockLevel = settings.isfCrAutoActivationDailyRiskBlockLevel
            this.isfCrAutoActivationMinDailyMatchedSamples = settings.isfCrAutoActivationMinDailyMatchedSamples
            this.isfCrAutoActivationMaxDailyMae30Mmol = settings.isfCrAutoActivationMaxDailyMae30Mmol
            this.isfCrAutoActivationMaxDailyMae60Mmol = settings.isfCrAutoActivationMaxDailyMae60Mmol
            this.isfCrAutoActivationMaxHypoRatePct = settings.isfCrAutoActivationMaxHypoRatePct
            this.isfCrAutoActivationMinDailyCiCoverage30Pct = settings.isfCrAutoActivationMinDailyCiCoverage30Pct
            this.isfCrAutoActivationMinDailyCiCoverage60Pct = settings.isfCrAutoActivationMinDailyCiCoverage60Pct
            this.isfCrAutoActivationMaxDailyCiWidth30Mmol = settings.isfCrAutoActivationMaxDailyCiWidth30Mmol
            this.isfCrAutoActivationMaxDailyCiWidth60Mmol = settings.isfCrAutoActivationMaxDailyCiWidth60Mmol
            this.isfCrAutoActivationRollingMinRequiredWindows = settings.isfCrAutoActivationRollingMinRequiredWindows
            this.isfCrAutoActivationRollingMaeRelaxFactor = settings.isfCrAutoActivationRollingMaeRelaxFactor
            this.isfCrAutoActivationRollingCiCoverageRelaxFactor = settings.isfCrAutoActivationRollingCiCoverageRelaxFactor
            this.isfCrAutoActivationRollingCiWidthRelaxFactor = settings.isfCrAutoActivationRollingCiWidthRelaxFactor
            this.baseTargetMmol = settings.baseTargetMmol
            this.postHypoThresholdMmol = settings.postHypoThresholdMmol
            this.postHypoDeltaThresholdMmol5m = settings.postHypoDeltaThresholdMmol5m
            this.postHypoTargetMmol = settings.postHypoTargetMmol
            this.postHypoDurationMinutes = settings.postHypoDurationMinutes
            this.postHypoLookbackMinutes = settings.postHypoLookbackMinutes
            this.adaptiveControllerEnabled = settings.adaptiveControllerEnabled
            this.adaptiveControllerPriority = settings.adaptiveControllerPriority
            this.adaptiveControllerRetargetMinutes = settings.adaptiveControllerRetargetMinutes
            this.adaptiveControllerSafetyProfile = settings.adaptiveControllerSafetyProfile
            this.adaptiveControllerStaleMaxMinutes = settings.adaptiveControllerStaleMaxMinutes
            this.adaptiveControllerMaxActions6h = settings.adaptiveControllerMaxActions6h
            this.adaptiveControllerMaxStepMmol = settings.adaptiveControllerMaxStepMmol
            this.aiMinDataHours = AI_MIN_DATA_HOURS
            this.aiDataCoverageHours = aiDataCoverageHours
            this.aiAnalysisReady = aiAnalysisReady
            this.aiTuningState = aiTuningStatus.state.name
            this.aiTuningReason = aiTuningStatus.reason
            this.aiTuningGeneratedTs = aiTuningStatus.generatedTs
            this.aiTuningConfidence = aiTuningStatus.confidence
            this.aiTuningStatusRaw = aiTuningStatus.statusRaw
            this.latestDataAgeMinutes = latestDataAgeMinutes
            this.nightscoutSyncAgeMinutes = nightscoutAgeMinutes
            this.cloudPushBacklogMinutes = cloudPushBacklogMinutes
            this.latestGlucoseMmol = latest?.mmol
            this.rawGlucoseMmol = rawGlucoseMmol
            this.calibratedGlucoseMmol = calibratedGlucoseMmol
            this.glucoseCalibrationGain = calibrationGain
            this.glucoseCalibrationOffsetMmol = calibrationOffsetMmol
            this.glucoseCalibrationConfidence = calibrationConfidence
            this.glucoseCalibrationLastCheckAgeMinutes = calibrationLastCheckAgeMinutes
            this.glucoseCalibrationModelType = calibrationModelType
            this.glucoseCalibrationStatus = calibrationStatus
            this.glucoseAlertState = glucoseAlertState
            this.glucoseAlertDirection = glucoseAlertDirection
            this.glucoseAlertDisableReason = glucoseAlertDisableReason
            this.glucoseAlertSoftActive = glucoseAlertSoftActive
            this.glucoseAlertStrongActive = glucoseAlertStrongActive
            this.glucoseAlertSoftLastTs = glucoseAlertSoftLastTs
            this.glucoseAlertStrongLastTs = glucoseAlertStrongLastTs
            this.glucoseAlertPred30 = glucoseAlertPred30
            this.glucoseAlertCiLow30 = glucoseAlertCiLow30
            this.glucoseAlertCiHigh30 = glucoseAlertCiHigh30
            this.glucoseAlertLowThreshold = glucoseAlertLowThreshold
            this.glucoseAlertHighThreshold = glucoseAlertHighThreshold
            this.glucoseAlertUrgentLowThreshold = glucoseAlertUrgentLowThreshold
            this.glucoseCalibrationChecks = bloodChecks
            this.glucoseCalibrationModel = activeCalibrationModel
            this.glucoseCalibrationRawHistoryPoints = calibrationRawHistoryPoints
            this.glucoseCalibrationResolvedHistoryPoints = calibrationResolvedHistoryPoints
            this.glucoseCalibrationCheckPoints = calibrationCheckPoints
            this.correctedGlucoseMmol = correctedGlucoseMmol
            this.glucoseDelta = if (latest != null && prev != null) latest.mmol - prev.mmol else null
            this.latestIobUnits = latestIobUnits
            this.latestIobRealUnits = latestIobRealUnits
            this.latestIobBolusUnits = latestIobBolusUnits
            this.latestIobBasalUnits = latestIobBasalUnits
            this.latestInsulinActivity = latestInsulinActivity
            this.latestIobRuntimeSource = latestIobRuntimeSource
            this.latestIobRuntimeConfidence = latestIobRuntimeConfidence
            this.latestIobRuntimeTimestamp = latestIobRuntimeTimestamp
            this.latestIobEvidenceTimestamp = latestIobEvidenceTimestamp
            this.latestIobTherapyCoverage = latestIobTherapyCoverage
            this.latestIobRuntimeFallbackReason = latestIobRuntimeFallbackReason
            this.latestCobGrams = latestCobGrams
            val externalCob = selectExternalCobForUi(
                telemetry, now, settings.staleDataMaxMinutes.coerceIn(5, 60) * 60_000L
            )
            this.latestExternalCobGrams = externalCob?.valueDouble
            this.latestExternalCobTimestamp = externalCob?.timestamp
            this.insulinRealOnsetMinutes = insulinRealOnsetMinutes
            this.insulinRealProfileCurveCompact = insulinRealProfileCurveCompact
            this.insulinRealProfileUpdatedTs = insulinRealProfileUpdatedTs
            this.insulinRealProfileConfidence = insulinRealProfileConfidence
            this.insulinRealProfileSamples = insulinRealProfileSamples
            this.insulinRealProfileOnsetMinutes = insulinRealProfileOnsetMinutes
            this.insulinRealProfilePeakMinutes = insulinRealProfilePeakMinutes
            this.insulinRealProfileScale = insulinRealProfileScale
            this.insulinRealProfileStatus = insulinRealProfileStatus
            this.latestActivityRatio = latestActivityRatio
            this.latestStepsCount = latestStepsCount
            this.forecast5m = forecast5Latest
            this.forecast5mCiLow = forecast5CiLow
            this.forecast5mCiHigh = forecast5CiHigh
            this.forecast30m = forecast30Latest
            this.forecast30mCiLow = forecast30CiLow
            this.forecast30mCiHigh = forecast30CiHigh
            this.forecast60m = forecast60Latest
            this.forecast60mCiLow = forecast60CiLow
            this.forecast60mCiHigh = forecast60CiHigh
            this.forecastTupleError = acceptedForecastTuple.error?.uiReason
            this.forecastAcceptedGenerationTs = acceptedForecastTuple.generationTimestamp
            this.acceptedSensitivityRuntimeSnapshot = acceptedForecastTuple.sensitivity
            this.trend60ComponentMmol = acceptedForecastTuple.decomposition?.trend60Mmol
            this.mealImpactStepsJson = telemetryByKey["forecast_meal_steps"]?.valueText
            this.therapy60ComponentMmol = acceptedForecastTuple.decomposition?.therapy60Mmol
            this.uam60ComponentMmol = acceptedForecastTuple.decomposition?.uam60Mmol
            this.residualRoc0Mmol5m = acceptedForecastTuple.decomposition?.residualRoc0Mmol5
            this.sigmaEMmol5m = acceptedForecastTuple.decomposition?.sigmaEMmol5
            this.kfSigmaGMmol = acceptedForecastTuple.decomposition?.kfSigmaGMmol
            this.calculatedUamActive = calculatedUamFlag?.let { it >= 0.5 }
            this.calculatedUamConfidence = calculatedUamConfidence
            this.calculatedUamCarbsGrams = calculatedUamCarbs
            this.calculatedUci0Mmol5m = uci0Mmol5m
            this.calculatedUamDelta5Mmol = calculatedUamDelta5
            this.calculatedUamRise15Mmol = calculatedUamRise15
            this.calculatedUamRise30Mmol = calculatedUamRise30
            this.inferredUamActive = inferredUamFlag?.let { it >= 0.5 }
            this.inferredUamConfidence = inferredUamConfidence
            this.inferredUamCarbsGrams = inferredUamCarbs
            this.inferredUamIngestionTs = inferredUamIngestionTs
            this.inferredUamBoostMode = inferredUamBoostMode?.let { it >= 0.5 }
            this.inferredUamManualCobGrams = inferredUamManualCob
            this.inferredUamLastGAbsGrams = inferredUamLastGAbs
            this.uamRuntimeActive = uamRuntime.active
            this.uamRuntimeEquivalentCarbsGrams = uamRuntime.equivalentCarbsGrams
            this.uamRuntimeConfidence = uamRuntime.confidence
            this.uamRuntimeState = uamRuntime.state
            this.uamRuntimeReason = uamRuntime.reason
            this.uamRuntimeSource = uamRuntime.source
            this.uamEventRows = uamEventRows
            this.sensorQualityScore = sensorQualityScore
            this.sensorQualityBlocked = sensorQualityBlocked
            this.sensorQualityReason = sensorQualityReason
            this.sensorQualitySuspectFalseLow = sensorQualitySuspectFalseLow
            this.targetLowRiskLatched = targetLowRiskLatched
            this.sensorLagMinutes = sensorLagMinutes
            this.sensorAgeHours = sensorAgeHours
            this.sensorAgeSource = sensorAgeSource
            this.sensorLagConfidence = sensorLagConfidence
            this.sensorLagWearBucket = sensorLagWearBucket
            this.sensorLagSourceConfidence = sensorLagSourceConfidence
            this.sensorLagTrendConsistency = sensorLagTrendConsistency
            this.sensorLagReplayMultiplier = sensorLagReplayMultiplier
            this.sensorLagEffectiveLagMinutes = sensorLagEffectiveLagMinutes
            this.sensorLagEffectiveCorrectionCap = sensorLagEffectiveCorrectionCap
            this.therapyHistorySourceMode = therapyHistorySourceMode
            this.therapyHistoryRawInsulin30d = therapyHistoryRawInsulin30d
            this.therapyHistoryInferredInsulin30d = therapyHistoryInferredInsulin30d
            this.therapyHistoryRealFetchedInsulin30d = therapyHistoryRealFetchedInsulin30d
            this.therapyHistoryRecoveredInsulin30d = therapyHistoryRecoveredInsulin30d
            this.therapyHistoryUsableInsulin30d = therapyHistoryUsableInsulin30d
            this.therapyHistoryBootstrapNeeded = therapyHistoryBootstrapNeeded
            this.therapyHistoryPlateauOnly = therapyHistoryPlateauOnly
            this.therapyHistorySyntheticRatioPct = therapyHistorySyntheticRatioPct
            this.therapyHistoryLastSyncTreatmentCount = therapyHistoryLastSyncTreatmentCount
            this.therapyHistoryLastSyncInsulinLikeCount = therapyHistoryLastSyncInsulinLikeCount
            this.therapyHistoryLastSyncCarbLikeCount = therapyHistoryLastSyncCarbLikeCount
            this.therapyHistoryLastSyncLocalActionCount = therapyHistoryLastSyncLocalActionCount
            this.therapyHistoryUpstreamTempTargetOnly = therapyHistoryUpstreamTempTargetOnly
            this.sensorLagMode = sensorLagMode
            this.sensorLagDisableReason = sensorLagDisableReason
            this.sensorLagTrendLagPoints = sensorLagLagTrendPoints
            this.sensorLagTrendCorrectionPoints = sensorLagCorrectionTrendPoints
            this.sensorLagModeSegments = sensorLagModeSegments
            this.sensorLagBucketSegments = sensorLagBucketSegments
            this.sensorLagTrendStartAgeHours = sensorLagTrendStartAgeHours
            this.sensorLagTrendEndAgeHours = sensorLagTrendEndAgeHours
            this.smbContextSummary = smbContextSummary
            this.lastRuleState = ruleExec.firstOrNull()?.state
            this.lastRuleId = ruleExec.firstOrNull()?.ruleId
            this.controllerState = latestAdaptiveExecution?.state
            this.controllerReason = controllerReason
            this.controllerConfidence = controllerConfidence
            this.controllerNextTarget = controllerAction?.targetMmol
            this.controllerDurationMinutes = controllerAction?.durationMinutes
            this.controllerForecast30 = forecast30Latest
            this.controllerForecast60 = forecast60Latest
            this.controllerWeightedError = controllerWeightedError
            this.profileIsf = profile?.calculatedIsfMmolPerUnit ?: profile?.isfMmolPerUnit
            this.profileCr = profile?.calculatedCrGramPerUnit ?: profile?.crGramPerUnit
            this.profileConfidence = profile?.calculatedConfidence ?: profile?.confidence
            this.profileSamples = if ((profile?.calculatedSampleCount ?: 0) > 0) profile?.calculatedSampleCount else profile?.sampleCount
            this.profileIsfSamples = if ((profile?.calculatedIsfSampleCount ?: 0) > 0) profile?.calculatedIsfSampleCount else profile?.isfSampleCount
            this.profileCrSamples = if ((profile?.calculatedCrSampleCount ?: 0) > 0) profile?.calculatedCrSampleCount else profile?.crSampleCount
            this.profileTelemetryIsfSamples = profile?.telemetryIsfSampleCount
            this.profileTelemetryCrSamples = profile?.telemetryCrSampleCount
            this.profileUamObservedCount = profile?.uamObservedCount
            this.profileUamFilteredIsfSamples = profile?.uamFilteredIsfSamples
            this.profileUamEpisodes = profile?.uamEpisodeCount
            this.profileUamCarbsGrams = profile?.uamEstimatedCarbsGrams
            this.profileUamRecentCarbsGrams = profile?.uamEstimatedRecentCarbsGrams
            this.profileCalculatedIsf = profile?.calculatedIsfMmolPerUnit
            this.profileCalculatedCr = profile?.calculatedCrGramPerUnit
            this.profileCalculatedConfidence = profile?.calculatedConfidence
            this.profileCalculatedSamples = profile?.calculatedSampleCount
            this.profileCalculatedIsfSamples = profile?.calculatedIsfSampleCount
            this.profileCalculatedCrSamples = profile?.calculatedCrSampleCount
            this.profileLookbackDays = profile?.lookbackDays
            this.isfCrRealtimeMode = freshRealtimeSnapshot?.mode?.name ?: isfCrSnapshot?.mode?.name
            this.isfCrRealtimeConfidence = freshRealtimeSnapshot?.confidence
            this.isfCrRealtimeQualityScore = freshRealtimeSnapshot?.qualityScore
            this.isfCrRealtimeIsfEff = freshRealtimeSnapshot?.isfEff
            this.isfCrRealtimeCrEff = freshRealtimeSnapshot?.crEff
            this.isfCrRealtimeIsfBase = freshRealtimeSnapshot?.isfBase
            this.isfCrRealtimeCrBase = freshRealtimeSnapshot?.crBase
            this.isfCrRealtimeCiIsfLow = freshRealtimeSnapshot?.ciIsfLow
            this.isfCrRealtimeCiIsfHigh = freshRealtimeSnapshot?.ciIsfHigh
            this.isfCrRealtimeCiCrLow = freshRealtimeSnapshot?.ciCrLow
            this.isfCrRealtimeCiCrHigh = freshRealtimeSnapshot?.ciCrHigh
            this.isfCrRealtimeFactors = snapshotFactorLines
            this.isfCrRuntimeDiagTs = runtimeDiagnosticsSnapshot?.realtimeTs
            this.isfCrRuntimeDiagMode = runtimeDiagnosticsSnapshot?.mode
            this.isfCrRuntimeDiagConfidence = runtimeDiagnosticsSnapshot?.confidence
            this.isfCrRuntimeDiagConfidenceThreshold = runtimeDiagnosticsSnapshot?.confidenceThreshold
            this.isfCrRuntimeDiagQualityScore = runtimeDiagnosticsSnapshot?.qualityScore
            this.isfCrRuntimeDiagUsedEvidence = runtimeDiagnosticsSnapshot?.usedEvidence
            this.isfCrRuntimeDiagDroppedEvidence = runtimeDiagnosticsSnapshot?.droppedEvidence
            this.isfCrRuntimeDiagDroppedReasons = runtimeDiagnosticsSnapshot?.droppedReasons
            this.isfCrRuntimeDiagCurrentDayType = runtimeDiagnosticsSnapshot?.currentDayType
            this.isfCrRuntimeDiagIsfBaseSource = runtimeDiagnosticsSnapshot?.isfBaseSource
            this.isfCrRuntimeDiagCrBaseSource = runtimeDiagnosticsSnapshot?.crBaseSource
            this.isfCrRuntimeDiagIsfDayTypeBaseAvailable = runtimeDiagnosticsSnapshot?.isfDayTypeBaseAvailable
            this.isfCrRuntimeDiagCrDayTypeBaseAvailable = runtimeDiagnosticsSnapshot?.crDayTypeBaseAvailable
            this.isfCrRuntimeDiagHourWindowIsfEvidence = runtimeDiagnosticsSnapshot?.hourWindowIsfEvidence
            this.isfCrRuntimeDiagHourWindowCrEvidence = runtimeDiagnosticsSnapshot?.hourWindowCrEvidence
            this.isfCrRuntimeDiagHourWindowIsfSameDayType = runtimeDiagnosticsSnapshot?.hourWindowIsfSameDayType
            this.isfCrRuntimeDiagHourWindowCrSameDayType = runtimeDiagnosticsSnapshot?.hourWindowCrSameDayType
            this.isfCrRuntimeDiagMinIsfEvidencePerHour = runtimeDiagnosticsSnapshot?.minIsfEvidencePerHour
            this.isfCrRuntimeDiagMinCrEvidencePerHour = runtimeDiagnosticsSnapshot?.minCrEvidencePerHour
            this.isfCrRuntimeDiagCrMaxGapMinutes = runtimeDiagnosticsSnapshot?.crMaxGapMinutes
            this.isfCrRuntimeDiagCrMaxSensorBlockedRatePct = runtimeDiagnosticsSnapshot?.crMaxSensorBlockedRatePct
            this.isfCrRuntimeDiagCrMaxUamAmbiguityRatePct = runtimeDiagnosticsSnapshot?.crMaxUamAmbiguityRatePct
            this.isfCrRuntimeDiagCoverageHoursIsf = runtimeDiagnosticsSnapshot?.coverageHoursIsf
            this.isfCrRuntimeDiagCoverageHoursCr = runtimeDiagnosticsSnapshot?.coverageHoursCr
            this.isfCrRuntimeDiagReasons = runtimeDiagnosticsSnapshot?.realtimeReasons
            this.isfCrRuntimeDiagLowConfidenceTs = runtimeDiagnosticsSnapshot?.lowConfidenceTs
            this.isfCrRuntimeDiagLowConfidenceReasons = runtimeDiagnosticsSnapshot?.lowConfidenceReasons
            this.isfCrRuntimeDiagFallbackTs = runtimeDiagnosticsSnapshot?.fallbackTs
            this.isfCrRuntimeDiagFallbackReasons = runtimeDiagnosticsSnapshot?.fallbackReasons
            this.isfCrActivationGateLines = activationGateLines
            this.isfCrDroppedReasons24hLines = isfCrDroppedReasons24h
            this.isfCrDroppedReasons7dLines = isfCrDroppedReasons7d
            this.isfCrWearImpact24hLines = isfCrWearImpact24h
            this.isfCrWearImpact7dLines = isfCrWearImpact7d
            this.isfCrActiveTags = physioTagLines
            this.events = eventTimeline.filter { it.visibleFor(settings.energyProfile.physiologicalSex) }
            this.physiologicalSex = settings.energyProfile.physiologicalSex
            this.isfCrHistoryPoints = profileHistoryPoints
            this.isfCrHistoryOverlayPoints = profileHistoryOverlayPoints
            this.isfCrHistoryLastUpdatedTs = listOfNotNull(
                profileHistoryPoints.lastOrNull()?.timestamp,
                profileHistoryOverlayPoints.lastOrNull()?.timestamp,
                isfCrTelemetryHistory.maxOfOrNull { it.timestamp },
                isfCrHistory.maxOfOrNull { it.ts },
                profileHistory.maxOfOrNull { it.timestamp }
            ).maxOrNull()
            this.circadianPatternSections = circadianSections
            this.circadianSlotStatCount = circadianSlotStats.size
            this.circadianTransitionStatCount = circadianTransitions.size
            this.circadianSnapshotCount = circadianSnapshots.size
            this.circadianReplayStatCount = circadianReplayStats.size
            this.circadianLatestSnapshotUpdatedTs = circadianSnapshots.maxOfOrNull { it.updatedAt }
                ?: circadianSlotStats.maxOfOrNull { it.updatedAt }
            this.circadianLatestReplayUpdatedTs = circadianReplayStats.maxOfOrNull { it.updatedAt }
            this.profileSegmentLines = profileSegmentLines
            this.yesterdayProfileLines = yesterdayProfileLines
            this.isfCrDeepLines = deepIsfCrLinesCombined
            this.activityLines = activityLines
            this.rulePostHypoEnabled = settings.rulePostHypoEnabled
            this.rulePatternEnabled = settings.rulePatternEnabled
            this.ruleSegmentEnabled = settings.ruleSegmentEnabled
            this.rulePostHypoPriority = settings.rulePostHypoPriority
            this.rulePatternPriority = settings.rulePatternPriority
            this.ruleSegmentPriority = settings.ruleSegmentPriority
            this.rulePostHypoCooldownMinutes = settings.rulePostHypoCooldownMinutes
            this.rulePatternCooldownMinutes = settings.rulePatternCooldownMinutes
            this.ruleSegmentCooldownMinutes = settings.ruleSegmentCooldownMinutes
            this.patternMinSamplesPerWindow = settings.patternMinSamplesPerWindow
            this.patternMinActiveDaysPerWindow = settings.patternMinActiveDaysPerWindow
            this.patternLowRateTrigger = settings.patternLowRateTrigger
            this.patternHighRateTrigger = settings.patternHighRateTrigger
            this.analyticsLookbackDays = settings.analyticsLookbackDays
            this.maxActionsIn6Hours = settings.maxActionsIn6Hours
            this.staleDataMaxMinutes = settings.staleDataMaxMinutes
            this.safetyMinTargetMmol = settings.safetyMinTargetMmol
            this.safetyMaxTargetMmol = settings.safetyMaxTargetMmol
            this.carbAbsorptionMaxAgeMinutes = settings.carbAbsorptionMaxAgeMinutes
            this.carbComputationMaxGrams = settings.carbComputationMaxGrams
            this.mealPortions = settings.mealPortions
            this.weekdayHotHours = patterns
                .filter { it.dayType == DayType.WEEKDAY.name && it.isRiskWindow }
                .sortedBy { it.hour }
                .map {
                    PatternWindow(
                        dayType = DayType.WEEKDAY,
                        hour = it.hour,
                        sampleCount = it.sampleCount,
                        activeDays = it.activeDays,
                        lowRate = it.lowRate,
                        highRate = it.highRate,
                        recommendedTargetMmol = it.recommendedTargetMmol,
                        isRiskWindow = it.isRiskWindow
                    )
                }
            this.weekendHotHours = patterns
                .filter { it.dayType == DayType.WEEKEND.name && it.isRiskWindow }
                .sortedBy { it.hour }
                .map {
                    PatternWindow(
                        dayType = DayType.WEEKEND,
                        hour = it.hour,
                        sampleCount = it.sampleCount,
                        activeDays = it.activeDays,
                        lowRate = it.lowRate,
                        highRate = it.highRate,
                        recommendedTargetMmol = it.recommendedTargetMmol,
                        isRiskWindow = it.isRiskWindow
                    )
                }
            this.qualityMetrics = quality.map {
                QualityMetricUi(
                    horizonMinutes = it.horizonMinutes,
                    sampleCount = it.sampleCount,
                    mae = it.mae,
                    rmse = it.rmse,
                    mardPct = it.mardPct
                )
            }
            this.dailyReportGeneratedAtTs = dailyReportBundle.generatedAtTs
            this.dailyReportMatchedSamples = dailyReportBundle.matchedSamples
            this.dailyReportForecastRows = dailyReportBundle.forecastRows
            this.dailyReportPeriodStartUtc = dailyReportBundle.periodStartUtc
            this.dailyReportPeriodEndUtc = dailyReportBundle.periodEndUtc
            this.dailyReportMarkdownPath = dailyReportBundle.markdownPath
            this.dailyReportMetrics = dailyReportBundle.metrics
            this.dailyReportRecommendations = dailyReportBundle.recommendations
            this.dailyReportIsfCrQualityLines = dailyReportBundle.isfCrQualityLines
            this.dailyReportReplayHotspots = dailyReportBundle.replayHotspots
            this.dailyReportReplayFactors = dailyReportBundle.replayFactors
            this.dailyReportReplayCoverage = dailyReportBundle.replayCoverage
            this.dailyReportReplayRegimes = dailyReportBundle.replayRegimes
            this.dailyReportReplayPairs = dailyReportBundle.replayPairs
            this.dailyReportReplayTopMisses = dailyReportBundle.replayTopMisses
            this.dailyReportReplayErrorClusters = dailyReportBundle.replayErrorClusters
            this.dailyReportReplayDayTypeGaps = dailyReportBundle.replayDayTypeGaps
            this.dailyReportReplayTopFactorsOverall = dailyReportBundle.replayTopFactorsOverall
            this.dailyReportSensorLagReplayBuckets = dailyReportBundle.sensorLagReplayBuckets
            this.dailyReportSensorLagShadowBuckets = dailyReportBundle.sensorLagShadowBuckets
            this.rollingReportLines = dailyReportBundle.reportLines
            this.baselineDeltaLines = baselineDeltas.map {
                "${it.horizonMinutes}m ${it.algorithm}: ${if (it.deltaMmol >= 0) "+" else ""}${"%.2f".format(it.deltaMmol)} mmol/L"
            }
            this.telemetryCoverageLines = telemetryCoverageLines
            this.telemetryLines = telemetryLines
            this.actionLines = actionLines
            this.autoConnectLines = autoConnect?.lines.orEmpty()
            this.transportStatusLines = transportStatusLines
            this.replacementHistoryLines = replacementHistoryLines
            this.syncStatusLines = syncStatusLines
            this.jobStatusLines = jobStatusLines
            this.cloudJobRows = cloudJobRows
            this.insightsFilterLabel = insightsFilterLabel
            this.insightsWindowDays = insightsFilter.days
            this.analysisHistoryItems = analysisHistoryRows
            this.analysisHistoryLines = analysisHistoryLines
            this.analysisTrendItems = analysisTrendRows
            this.analysisTrendLines = analysisTrendLines
            this.ruleCooldownLines = ruleCooldownLines
            this.dryRun = dryRun
            this.cloudReplay = cloudReplay
            this.adaptiveAuditLines = adaptiveAuditLines
            this.glucoseHistoryPoints = glucoseHistoryPoints
            this.lastAction = lastAction
            this.patternPriorConfidence = patternPriorConfidence
            this.patternPriorBgMedianMmol = patternPriorBgMedian
            this.patternPrior30Mmol = patternPrior30
            this.patternPrior60Mmol = patternPrior60
            this.patternPriorResidualBias30Mmol = patternResidualBias30
            this.patternPriorResidualBias60Mmol = patternResidualBias60
            this.patternPriorAcuteAttenuation = patternAcuteAttenuation
            this.patternPriorStaleBlocked = patternStaleBlocked
            this.patternPriorSegmentSource = patternSegmentSource
            this.auditRecords = auditRecords
            this.auditLines = audits.map { "${it.level}: ${it.message}" }
            this.message = message
            acceptedForecastTuple.sensitivity
                ?.let { SensitivityRuntimeFanOut(it).contextFor(SensitivityRuntimeConsumer.OVERVIEW) }
                ?.let { context ->
                    val snapshot = context.snapshot
                    this.isfRuntimeSourcePreference = snapshot.isf.requested.name
                    this.crRuntimeSourcePreference = snapshot.cr.requested.name
                    this.isfRuntimeResolved = snapshot.isf.resolved.uiCode()
                    this.crRuntimeResolved = snapshot.cr.resolved.uiCode()
                    this.isfRuntimeSelected = snapshot.isf.effective
                    this.crRuntimeSelected = snapshot.cr.effective
                    this.isfRuntimeAaps = snapshot.isf.rawAaps
                    this.crRuntimeAaps = snapshot.cr.rawAaps
                    this.isfRuntimeEvidence = snapshot.isf.rawEvidence
                    this.crRuntimeEvidence = snapshot.cr.rawEvidence
                    this.isfRuntimeFallbackActive = snapshot.isf.fallbackReason != null
                    this.crRuntimeFallbackActive = snapshot.cr.fallbackReason != null
                    this.isfCrRealtimeConfidence = minOf(snapshot.isf.confidence, snapshot.cr.confidence)
                }
        }
        return result
    }

    private suspend fun buildPrimaryUiState(values: Array<*>): MainUiState {
        @Suppress("UNCHECKED_CAST")
        val rawGlucose = values[0] as List<GlucoseSampleEntity>
        val glucose = GlucoseSanitizer.filterEntities(rawGlucose)
        @Suppress("UNCHECKED_CAST")
        val forecasts = values[1] as List<ForecastEntity>
        @Suppress("UNCHECKED_CAST")
        val actionCommands = values[2] as List<ActionCommandEntity>
        @Suppress("UNCHECKED_CAST")
        val telemetry = values[3] as List<TelemetrySampleEntity>
        @Suppress("UNCHECKED_CAST")
        val syncStates = values[4] as List<SyncStateEntity>
        val settings = values[5] as AppSettings
        @Suppress("UNCHECKED_CAST")
        val bloodChecks = values[6] as List<BloodGlucoseCheck>
        val activeCalibrationModel = values[7] as GlucoseCalibrationModel?
        val calibrationAuthorityTokenRow = values[8] as TelemetrySampleEntity?
        val sensitivityRuntime = values[9] as SensitivityRuntimeSnapshot?

        val now = System.currentTimeMillis()
        val sortedGlucose = glucose.sortedBy { it.timestamp }
        val currentGlucose = selectOverviewCurrentGlucoseForUi(rawGlucose, now)
        val latest = currentGlucose.latest
        val prev = currentGlucose.previous
        val glucoseHistoryPoints = buildGlucoseHistoryPointsForUi(sortedGlucose, nowTs = now)
        val latestDataAgeMinutes = minutesSince(now, latest?.timestamp)
        val nightscoutSyncTs = syncStates.firstOrNull { it.source == "nightscout" }?.lastSyncedTimestamp
        val nightscoutAgeMinutes = minutesSince(now, nightscoutSyncTs)
        val staleTag = if (latestDataAgeMinutes != null && latestDataAgeMinutes > settings.staleDataMaxMinutes) "STALE" else "OK"
        val syncStatusLines = listOf(
            "Data age: ${minutesLabel(latestDataAgeMinutes)} ($staleTag)",
            "Nightscout last sync: ${formatTs(nightscoutSyncTs)} (age ${minutesLabel(nightscoutAgeMinutes)})"
        )
        val telemetryByKey = mergeAtomicInsulinRuntimeTelemetryForUi(
            latestByKey = latestTelemetryByKey(telemetry, nowTs = now),
            samples = telemetry
        ).toMutableMap()
        val latestIobUnits = resolveEffectivePositiveIobForUi(telemetryByKey)
            ?.coerceIn(0.0, 60.0)
        val latestIobRealUnits = resolveMetricByRecency(
            preferred = telemetryByKey["iob_net_units"],
            fallback = telemetryByKey["iob_real_units"]
        )
        val latestIobBolusUnits = telemetryByKey["iob_bolus_units"].toNumericValue()
        val latestIobBasalUnits = telemetryByKey["iob_basal_units"].toNumericValue()
        val latestInsulinActivity = telemetryByKey["insulin_activity"].toNumericValue()
        val latestIobRuntimeSource = telemetryByKey["iob_runtime_source"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val latestIobRuntimeConfidence = telemetryByKey["iob_runtime_confidence"].toNumericValue()
        val latestIobRuntimeTimestamp = telemetryByKey["iob_runtime_timestamp_ms"]
            .toNumericValue()?.toLong()?.takeIf { it > 0L }
        val latestIobEvidenceTimestamp = telemetryByKey["iob_runtime_evidence_timestamp_ms"]
            .toNumericValue()?.toLong()?.takeIf { it > 0L }
        val latestIobTherapyCoverage = telemetryByKey["iob_runtime_therapy_coverage"].toNumericValue()
        val latestIobRuntimeFallbackReason = telemetryByKey["iob_runtime_fallback_reason"]
            ?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val latestCobGrams = resolveMetricByRecency(
            preferred = telemetryByKey["cob_effective_grams"],
            fallback = telemetryByKey["cob_grams"]
        )?.coerceIn(0.0, io.aaps.copilot.domain.nutrition.MealCarbLimits.MAX_MANUAL_MEAL_GRAMS)
        val correctedGlucoseMmol = telemetryByKey["sensor_lag_corrected_glucose_mmol"].toNumericValue()
        val rawGlucoseMmol = latest?.mmol
        val currentCalibrationAuthorityForUi = loadUiCalibrationAuthority(
            db = db,
            nowTs = now,
            observedAuthorityToken = calibrationAuthorityTokenRow?.valueText
        )
        val currentCalibrationModelForUi = currentCalibrationAuthorityForUi.activeModel
        val acceptedForecastTuple = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = forecasts,
            telemetry = telemetry,
            snapshot = sensitivityRuntime,
            currentSettings = settings.sensitivityRuntimeIdentity(),
            authoritativeNowTs = now,
            calibrationAuthority = AcceptedCalibrationAuthority(
                activeModel = currentCalibrationModelForUi,
                authorityToken = calibrationAuthorityTokenRow
                    ?.valueText?.takeIf(String::isNotBlank) ?: ACCEPTED_CALIBRATION_NONE,
                currentSessionKey = currentCalibrationAuthorityForUi.currentSessionKey,
                contextValid = currentCalibrationAuthorityForUi.contextValid
            )
        )
        val calibrationModelForUi = resolveOverviewCalibrationModelForUi(
            acceptedTuple = acceptedForecastTuple,
            currentModel = currentCalibrationModelForUi
        )
        val calibrationTelemetrySourceTs = telemetryByKey["glucose_calibration_source_ts"]
            .toNumericValue()
            ?.toLong()
        val calibratedGlucoseMmol = resolveUiCalibratedGlucose(
            pointTs = latest?.timestamp,
            rawMmol = rawGlucoseMmol,
            telemetryCalibratedMmol = telemetryByKey["glucose_calibrated_mmol"].toNumericValue(),
            telemetryCalibrationSourceTs = calibrationTelemetrySourceTs,
            activeModel = calibrationModelForUi
        )
        val calibrationResolvedHistoryPoints = buildCalibrationResolvedHistoryPointsForUi(
            glucose = glucoseHistoryPoints,
            calibrate = calibrationModelForUi?.let { model ->
                { timestamp, rawMmol -> applyGlucoseCalibrationModelForUi(timestamp, rawMmol, model) }
            }
        )
        val calibrationGain = calibrationModelForUi?.gain
        val calibrationOffsetMmol = calibrationModelForUi?.offsetMmol
        val calibrationConfidence = calibrationModelForUi?.confidence
        val calibrationLastCheckAgeMinutes = telemetryByKey["glucose_calibration_last_check_age_minutes"].toNumericValue()
            ?: bloodChecks.firstOrNull()?.timestamp?.let { checkTs ->
                ((now - checkTs).coerceAtLeast(0L)) / 60_000.0
            }
        val calibrationModelType = calibrationModelForUi?.modelType?.name
        val calibrationStatus = calibrationModelForUi?.status?.name
        val glucoseAlertState = telemetryByKey["glucose_alert_state"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val glucoseAlertDirection = telemetryByKey["glucose_alert_direction"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val glucoseAlertDisableReason = telemetryByKey["glucose_alert_disable_reason"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val glucoseAlertSoftActive = telemetryByKey["glucose_alert_soft_active"].toNumericValue()?.let { it >= 0.5 }
        val glucoseAlertStrongActive = telemetryByKey["glucose_alert_strong_active"].toNumericValue()?.let { it >= 0.5 }
        val glucoseAlertSoftLastTs = telemetryByKey["glucose_alert_soft_last_ts"].toNumericValue()?.toLong()
        val glucoseAlertStrongLastTs = telemetryByKey["glucose_alert_strong_last_ts"].toNumericValue()?.toLong()
        val glucoseAlertPred30 = telemetryByKey["glucose_alert_pred30"].toNumericValue()
        val glucoseAlertCiLow30 = telemetryByKey["glucose_alert_ci_low30"].toNumericValue()
        val glucoseAlertCiHigh30 = telemetryByKey["glucose_alert_ci_high30"].toNumericValue()
        val glucoseAlertLowThreshold = telemetryByKey["glucose_alert_low_threshold"].toNumericValue()
        val glucoseAlertHighThreshold = telemetryByKey["glucose_alert_high_threshold"].toNumericValue()
        val glucoseAlertUrgentLowThreshold = telemetryByKey["glucose_alert_urgent_low_threshold"].toNumericValue()
        val sensorQualityScore = telemetryByKey["sensor_quality_score"].toNumericValue()
        val sensorQualityBlocked = telemetryByKey["sensor_quality_blocked"].toNumericValue()?.let { it >= 0.5 }
        val sensorQualityReason = telemetryByKey["sensor_quality_reason"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val sensorQualitySuspectFalseLow = telemetryByKey["sensor_quality_suspect_false_low"].toNumericValue()
            ?.let { it >= 0.5 }
        val targetLowRiskLatched = telemetryByKey["target_low_risk_latched"].toNumericValue()
            ?.let { it >= 0.5 }
        val sensorLagMinutes = telemetryByKey["sensor_lag_minutes"].toNumericValue()
        val sensorAgeHours = telemetryByKey["sensor_age_hours"].toNumericValue()
            ?: telemetryByKey["sensor_lag_age_hours"].toNumericValue()
        val sensorAgeSource = telemetryByKey["sensor_lag_age_source"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val sensorLagConfidence = telemetryByKey["sensor_lag_confidence"].toNumericValue()
        val sensorLagWearBucket = telemetryByKey["sensor_lag_wear_bucket"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val sensorLagSourceConfidence = telemetryByKey["sensor_lag_source_confidence"].toNumericValue()
        val sensorLagTrendConsistency = telemetryByKey["sensor_lag_trend_consistency"].toNumericValue()
        val sensorLagReplayMultiplier = telemetryByKey["sensor_lag_replay_multiplier"].toNumericValue()
        val sensorLagEffectiveLagMinutes = telemetryByKey["sensor_lag_effective_lag_minutes"].toNumericValue()
        val sensorLagEffectiveCorrectionCap = telemetryByKey["sensor_lag_effective_correction_cap"].toNumericValue()
        val sensorLagMode = telemetryByKey["sensor_lag_mode"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val sensorLagDisableReason = telemetryByKey["sensor_lag_disable_reason"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
        val insulinRealOnsetMinutes = telemetryByKey["insulin_real_onset_min"].toNumericValue()
        val latestActivityRatio = telemetryByKey["activity_ratio"].toNumericValue()
        val latestStepsCount = telemetryByKey["steps_count"].toNumericValue()
        val calculatedUamFlag = telemetryByKey["uam_calculated_flag"].toNumericValue()
        val calculatedUamConfidence = telemetryByKey["uam_calculated_confidence"].toNumericValue()
        val calculatedUamCarbs = telemetryByKey["uam_calculated_carbs_grams"].toNumericValue()
        val calculatedUamDelta5 = telemetryByKey["uam_calculated_delta5_mmol"].toNumericValue()
        val calculatedUamRise15 = telemetryByKey["uam_calculated_rise15_mmol"].toNumericValue()
        val calculatedUamRise30 = telemetryByKey["uam_calculated_rise30_mmol"].toNumericValue()
        val uci0Mmol5m = resolveUiUamImpactStatic(
            telemetryByKey["uam_runtime_impact_mmol5"].toNumericValue()
        )
        val inferredUamFlag = telemetryByKey["uam_inferred_flag"].toNumericValue()
        val inferredUamConfidence = telemetryByKey["uam_inferred_confidence"].toNumericValue()
        val inferredUamCarbs = telemetryByKey["uam_inferred_carbs_grams"].toNumericValue()
        val inferredUamIngestionTs = telemetryByKey["uam_inferred_ingestion_ts"].toNumericValue()?.toLong()
        val inferredUamBoostMode = telemetryByKey["uam_inferred_boost_mode"].toNumericValue()
        val inferredUamManualCob = telemetryByKey["uam_manual_cob_grams"].toNumericValue()
        val inferredUamLastGAbs = telemetryByKey["uam_inferred_gabs_last5_g"].toNumericValue()
        val uamRuntime = resolvePrimaryUiUamRuntimeSnapshotStatic(
            telemetryByKey = telemetryByKey,
            nowTs = now,
            acceptedCycleId = acceptedForecastTuple.sensitivity?.forecastCycleId
        )
        val latestForecast5Row = acceptedForecastTuple.forecastsByHorizon[5]
        val latestForecast30Row = acceptedForecastTuple.forecastsByHorizon[30]
        val latestForecast60Row = acceptedForecastTuple.forecastsByHorizon[60]
        val publishedSensitivity = acceptedForecastTuple.sensitivity
            ?.let { SensitivityRuntimeFanOut(it).contextFor(SensitivityRuntimeConsumer.OVERVIEW) }
            ?.snapshot
        val realtimeIsf = publishedSensitivity?.isf?.rawEvidence
        val realtimeCr = publishedSensitivity?.cr?.rawEvidence
        val realtimeIsfCrConfidence = publishedSensitivity
            ?.let { minOf(it.isf.confidence, it.cr.confidence) }
        val realtimeIsfCrQuality = telemetryByKey["isf_realtime_quality_score"].toNumericValue()
        val isfRuntimeResolved = publishedSensitivity?.isf?.resolved?.uiCode()
        val crRuntimeResolved = publishedSensitivity?.cr?.resolved?.uiCode()
        val isfRuntimeSelected = publishedSensitivity?.isf?.effective
        val crRuntimeSelected = publishedSensitivity?.cr?.effective
        val isfRuntimeAaps = publishedSensitivity?.isf?.rawAaps
        val crRuntimeAaps = publishedSensitivity?.cr?.rawAaps
        val isfRuntimeFallback = publishedSensitivity?.let {
            if (it.isf.fallbackReason == null) 0.0 else 1.0
        }
        val crRuntimeFallback = publishedSensitivity?.let {
            if (it.cr.fallbackReason == null) 0.0 else 1.0
        }
        val lastAction = actionCommands.firstOrNull()?.let { command ->
            val target = payloadDouble(command.payloadJson, "targetMmol")
            val duration = payloadDouble(command.payloadJson, "durationMinutes")?.toInt()
            val carbs = payloadDouble(command.payloadJson, "carbsGrams", "carbs", "grams")
            val summary = when {
                target != null && duration != null -> "target=${String.format(Locale.US, "%.2f", target)} mmol/L for ${duration}m"
                carbs != null -> "carbs=${String.format(Locale.US, "%.1f", carbs)} g"
                else -> command.payloadJson.take(100)
            }
            LastActionRowUi(
                type = command.type,
                status = command.status,
                timestamp = command.timestamp,
                tempTargetMmol = target,
                durationMinutes = duration,
                carbsGrams = carbs,
                idempotencyKey = command.idempotencyKey,
                payloadSummary = summary
            )
        }

        return MainUiState().applyPrimaryUamSettingsForUi(settings).apply {
            this.killSwitch = settings.killSwitch
            this.powerSaveUntilMs = settings.powerSaveUntilMs
            this.targetManagerMode = settings.targetManagerMode.name
            this.targetManagerModeManualOverride = settings.targetManagerModeManualOverride
            this.targetManagerCopilotPriorityEnabled = settings.targetManagerCopilotPriorityEnabled
            this.targetManagerPolicyRevision = settings.targetManagerPolicyRevision
            this.targetManagerLiveStatus = resolveTargetManagerLiveStatusForUi(telemetry)
            this.baseTargetMmol = settings.baseTargetMmol
            this.safetyMinTargetMmol = settings.safetyMinTargetMmol
            this.safetyMaxTargetMmol = settings.safetyMaxTargetMmol
            this.staleDataMaxMinutes = settings.staleDataMaxMinutes
            this.carbComputationMaxGrams = settings.carbComputationMaxGrams
            this.mealPortions = settings.mealPortions
            this.isfRuntimeSourcePreference = publishedSensitivity?.isf?.requested?.name ?: "UNAVAILABLE"
            this.crRuntimeSourcePreference = publishedSensitivity?.cr?.requested?.name ?: "UNAVAILABLE"
            this.latestDataAgeMinutes = latestDataAgeMinutes
            this.nightscoutSyncAgeMinutes = nightscoutAgeMinutes
            this.latestGlucoseMmol = latest?.mmol
            this.rawGlucoseMmol = rawGlucoseMmol
            this.calibratedGlucoseMmol = calibratedGlucoseMmol
            this.glucoseCalibrationGain = calibrationGain
            this.glucoseCalibrationOffsetMmol = calibrationOffsetMmol
            this.glucoseCalibrationConfidence = calibrationConfidence
            this.glucoseCalibrationLastCheckAgeMinutes = calibrationLastCheckAgeMinutes
            this.glucoseCalibrationModelType = calibrationModelType
            this.glucoseCalibrationStatus = calibrationStatus
            this.glucoseAlertState = glucoseAlertState
            this.glucoseAlertDirection = glucoseAlertDirection
            this.glucoseAlertDisableReason = glucoseAlertDisableReason
            this.glucoseAlertSoftActive = glucoseAlertSoftActive
            this.glucoseAlertStrongActive = glucoseAlertStrongActive
            this.glucoseAlertSoftLastTs = glucoseAlertSoftLastTs
            this.glucoseAlertStrongLastTs = glucoseAlertStrongLastTs
            this.glucoseAlertPred30 = glucoseAlertPred30
            this.glucoseAlertCiLow30 = glucoseAlertCiLow30
            this.glucoseAlertCiHigh30 = glucoseAlertCiHigh30
            this.glucoseAlertLowThreshold = glucoseAlertLowThreshold
            this.glucoseAlertHighThreshold = glucoseAlertHighThreshold
            this.glucoseAlertUrgentLowThreshold = glucoseAlertUrgentLowThreshold
            this.glucoseCalibrationChecks = bloodChecks
            this.glucoseCalibrationModel = activeCalibrationModel
            this.glucoseCalibrationResolvedHistoryPoints = calibrationResolvedHistoryPoints
            this.correctedGlucoseMmol = correctedGlucoseMmol
            this.glucoseDelta = if (latest != null && prev != null) latest.mmol - prev.mmol else null
            this.latestIobUnits = latestIobUnits
            this.latestIobRealUnits = latestIobRealUnits
            this.latestIobBolusUnits = latestIobBolusUnits
            this.latestIobBasalUnits = latestIobBasalUnits
            this.latestInsulinActivity = latestInsulinActivity
            this.latestIobRuntimeSource = latestIobRuntimeSource
            this.latestIobRuntimeConfidence = latestIobRuntimeConfidence
            this.latestIobRuntimeTimestamp = latestIobRuntimeTimestamp
            this.latestIobEvidenceTimestamp = latestIobEvidenceTimestamp
            this.latestIobTherapyCoverage = latestIobTherapyCoverage
            this.latestIobRuntimeFallbackReason = latestIobRuntimeFallbackReason
            this.latestCobGrams = latestCobGrams
            val externalCob = selectExternalCobForUi(
                telemetry, now, settings.staleDataMaxMinutes.coerceIn(5, 60) * 60_000L
            )
            this.latestExternalCobGrams = externalCob?.valueDouble
            this.latestExternalCobTimestamp = externalCob?.timestamp
            this.insulinRealOnsetMinutes = insulinRealOnsetMinutes
            this.latestActivityRatio = latestActivityRatio
            this.latestStepsCount = latestStepsCount
            this.forecast5m = latestForecast5Row?.valueMmol
            this.forecast5mCiLow = latestForecast5Row?.ciLow
            this.forecast5mCiHigh = latestForecast5Row?.ciHigh
            this.forecast30m = latestForecast30Row?.valueMmol
            this.forecast30mCiLow = latestForecast30Row?.ciLow
            this.forecast30mCiHigh = latestForecast30Row?.ciHigh
            this.forecast60m = latestForecast60Row?.valueMmol
            this.forecast60mCiLow = latestForecast60Row?.ciLow
            this.forecast60mCiHigh = latestForecast60Row?.ciHigh
            this.forecastTupleError = acceptedForecastTuple.error?.uiReason
            this.forecastAcceptedGenerationTs = acceptedForecastTuple.generationTimestamp
            this.acceptedSensitivityRuntimeSnapshot = acceptedForecastTuple.sensitivity
            this.trend60ComponentMmol = acceptedForecastTuple.decomposition?.trend60Mmol
            this.mealImpactStepsJson = telemetryByKey["forecast_meal_steps"]?.valueText
            this.therapy60ComponentMmol = acceptedForecastTuple.decomposition?.therapy60Mmol
            this.uam60ComponentMmol = acceptedForecastTuple.decomposition?.uam60Mmol
            this.residualRoc0Mmol5m = acceptedForecastTuple.decomposition?.residualRoc0Mmol5
            this.sigmaEMmol5m = acceptedForecastTuple.decomposition?.sigmaEMmol5
            this.kfSigmaGMmol = acceptedForecastTuple.decomposition?.kfSigmaGMmol
            this.calculatedUamActive = calculatedUamFlag?.let { it >= 0.5 }
            this.calculatedUamConfidence = calculatedUamConfidence
            this.calculatedUamCarbsGrams = calculatedUamCarbs
            this.calculatedUci0Mmol5m = uci0Mmol5m
            this.calculatedUamDelta5Mmol = calculatedUamDelta5
            this.calculatedUamRise15Mmol = calculatedUamRise15
            this.calculatedUamRise30Mmol = calculatedUamRise30
            this.inferredUamActive = inferredUamFlag?.let { it >= 0.5 }
            this.inferredUamConfidence = inferredUamConfidence
            this.inferredUamCarbsGrams = inferredUamCarbs
            this.inferredUamIngestionTs = inferredUamIngestionTs
            this.inferredUamBoostMode = inferredUamBoostMode?.let { it >= 0.5 }
            this.inferredUamManualCobGrams = inferredUamManualCob
            this.inferredUamLastGAbsGrams = inferredUamLastGAbs
            this.uamRuntimeActive = uamRuntime.active
            this.uamRuntimeEquivalentCarbsGrams = uamRuntime.equivalentCarbsGrams
            this.uamRuntimeConfidence = uamRuntime.confidence
            this.uamRuntimeState = uamRuntime.state
            this.uamRuntimeReason = uamRuntime.reason
            this.uamRuntimeSource = uamRuntime.source
            this.sensorQualityScore = sensorQualityScore
            this.sensorQualityBlocked = sensorQualityBlocked
            this.sensorQualityReason = sensorQualityReason
            this.sensorQualitySuspectFalseLow = sensorQualitySuspectFalseLow
            this.targetLowRiskLatched = targetLowRiskLatched
            this.isfCrRealtimeConfidence = realtimeIsfCrConfidence
            this.isfCrRealtimeQualityScore = realtimeIsfCrQuality
            this.isfCrRealtimeIsfEff = realtimeIsf
            this.isfCrRealtimeCrEff = realtimeCr
            this.isfCrRealtimeIsfBase = realtimeIsf
            this.isfCrRealtimeCrBase = realtimeCr
            this.isfRuntimeResolved = isfRuntimeResolved
            this.crRuntimeResolved = crRuntimeResolved
            this.isfRuntimeSelected = isfRuntimeSelected
            this.crRuntimeSelected = crRuntimeSelected
            this.isfRuntimeAaps = isfRuntimeAaps
            this.crRuntimeAaps = crRuntimeAaps
            this.isfRuntimeEvidence = realtimeIsf
            this.crRuntimeEvidence = realtimeCr
            this.isfRuntimeFallbackActive = isfRuntimeFallback?.let { it >= 0.5 } ?: false
            this.crRuntimeFallbackActive = crRuntimeFallback?.let { it >= 0.5 } ?: false
            this.sensorLagMinutes = sensorLagMinutes
            this.sensorAgeHours = sensorAgeHours
            this.sensorAgeSource = sensorAgeSource
            this.sensorLagConfidence = sensorLagConfidence
            this.sensorLagWearBucket = sensorLagWearBucket
            this.sensorLagSourceConfidence = sensorLagSourceConfidence
            this.sensorLagTrendConsistency = sensorLagTrendConsistency
            this.sensorLagReplayMultiplier = sensorLagReplayMultiplier
            this.sensorLagEffectiveLagMinutes = sensorLagEffectiveLagMinutes
            this.sensorLagEffectiveCorrectionCap = sensorLagEffectiveCorrectionCap
            this.sensorLagMode = sensorLagMode
            this.sensorLagDisableReason = sensorLagDisableReason
            this.lastAction = lastAction
            this.syncStatusLines = syncStatusLines
            this.glucoseHistoryPoints = glucoseHistoryPoints
        }
    }

    val messageUiState: StateFlow<String?> = messageState.asStateFlow()

    val serverAiConnectionState: StateFlow<ServerAiConnectionState> =
        container.serverAiConnectionManager.state

    val uiStyleState: StateFlow<String> = container.settingsStore.settings
        .map { it.uiStyle.name }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UiStyle.MIDNIGHT_GLASS.name
        )

    val appHealthUiState: StateFlow<AppHealthUiState> = primaryUiState
        .map { it.toAppHealthUiState() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUiState().toAppHealthUiState()
        )

    val baseTargetBannerUiState: StateFlow<BaseTargetBannerUiState> = primaryUiState
        .map {
            BaseTargetBannerUiState(
                baseTargetMmol = it.baseTargetMmol,
                minTargetMmol = it.safetyMinTargetMmol,
                maxTargetMmol = it.safetyMaxTargetMmol
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BaseTargetBannerUiState(
                baseTargetMmol = 5.5,
                minTargetMmol = 4.0,
                maxTargetMmol = 10.0
            )
        )

    private val energyActivityOverviewStatusState = combine(
        container.settingsStore.settings,
        container.db.energyProfileDao().observeLatestSnapshot(),
        container.db.energyProfileDao().observeAllEvents(),
        primaryUiState
    ) { settings, snapshot, events, _ ->
        io.aaps.copilot.ui.foundation.screens.energyActivityOverviewStatus(
            settings = settings.energyProfile,
            snapshot = snapshot,
            events = events
        )
    }

    private val overviewRuntimeAndSettings = combine(
        primaryUiState,
        container.settingsStore.settings,
        overviewEventTimeline,
        container.sensitivityRuntimeRepository.acceptedCandidateDiagnostics
    ) { state, settings, timeline, candidateDiagnostics ->
        OverviewRuntimeContext(state, settings, timeline, candidateDiagnostics)
    }

    private val acceptedSensitivityTupleUiNow = acceptedSensitivityTupleUiClock(
        generationTimestamps = primaryUiState.map { it.forecastAcceptedGenerationTs },
        targetManagerStatusTimestamps = primaryUiState.map { it.targetManagerLiveStatus?.timestamp },
        cobEvidence = primaryUiState.map {
            it.latestExternalCobTimestamp to it.staleDataMaxMinutes.coerceIn(5, 60) * 60_000L
        },
        clock = System::currentTimeMillis
    )

    private val timedOverviewRuntime = combine(
        overviewRuntimeAndSettings,
        acceptedSensitivityTupleUiNow
    ) { runtime, authoritativeNowTs ->
        TimedOverviewRuntimeContext(runtime, authoritativeNowTs)
    }

    private val authoritativeGlucoseAlertMuteUntil = container.episodeAlertDelivery.mutedUntil
        .shareAuthoritativeAlertMuteUntil(viewModelScope)

    val overviewUiState: StateFlow<OverviewUiState> = combine(
        timedOverviewRuntime,
        proModeState,
        authoritativeGlucoseAlertMuteUntil,
        baseTargetPresentationState,
        energyActivityOverviewStatusState
    ) { timedRuntime, isProMode, mutedUntilTs, targetPresentation, energyActivityStatus ->
        val runtime = timedRuntime.runtime
        val state = runtime.state
        val settings = runtime.settings
        state.toOverviewUiState(
            isProMode = isProMode,
            baseTargetPresentation = targetPresentation,
            authoritativeIsfSource = settings.isfSourcePreference.name,
            authoritativeCrSource = settings.crSourcePreference.name,
            authoritativeSensitivitySettingsRevision = settings.sensitivitySettingsRevision,
            authoritativeTargetManagerMode = settings.targetManagerMode.name,
            authoritativeTargetManagerPriorityEnabled = settings.targetManagerCopilotPriorityEnabled,
            authoritativeTargetManagerPolicyRevision = settings.targetManagerPolicyRevision,
            acceptedCandidateDiagnostics = runtime.candidateDiagnostics,
            authoritativeNowTs = timedRuntime.authoritativeNowTs,
            eventTimeline = runtime.timeline.events,
            eventTimelineNowTs = runtime.timeline.generatedAt,
            profileSex = settings.energyProfile.physiologicalSex,
            showEventsOnGraph = settings.showEventsOnGraph
        ).copy(
            glucoseAlertMutedUntilTs = mutedUntilTs,
            energyActivityStatus = energyActivityStatus
        )
    }
        .combine(sensitivitySourceApplyState) { state, apply ->
            val presentation = sensitivitySourceApplyCoordinator.presentationStateForOverview(state, apply)
            state.withSensitivityApplyPresentation(
                applying = presentation.applying,
                pendingMetric = presentation.pendingMetric,
                pendingValue = presentation.pendingValue,
                error = presentation.error ?: state.sensitivitySourceApplyError
            )
        }
        .combine(container.pumpLinkMonitor.state) { state, pumpLink -> state.copy(pumpLinkStatus = pumpLink) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUiState().toOverviewUiState(
                isProMode = false,
                baseTargetPresentation = baseTargetPresentationState.value
            )
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val selectedAlertDetail = selectedAlertEpisodeId.flatMapLatest { episodeId ->
        episodeId?.let(container.alertsRepository::observeDetail) ?: flowOf(null)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val alertHistory = alertsHistoryWindow.flatMapLatest { window ->
        container.alertsRepository.observeHistory(
            fromTs = window.fromTsInclusive,
            throughTs = window.throughTsExclusive
        )
    }

    val alertsUiState: StateFlow<AlertsUiState> = combine(
        alertHistory,
        container.alertsRepository.observeActive(),
        selectedAlertDetail,
        authoritativeGlucoseAlertMuteUntil
    ) { history, active, selected, mutedUntilTs ->
        AlertsUiState(
            loadState = if (history.isEmpty() && active == null) {
                io.aaps.copilot.ui.foundation.screens.ScreenLoadState.EMPTY
            } else {
                io.aaps.copilot.ui.foundation.screens.ScreenLoadState.READY
            },
            mutedUntilTs = mutedUntilTs,
            active = active,
            history = history.filterNot { it.episodeId == active?.episodeId },
            selected = selected
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AlertsUiState()
    )

    val alertNavigationRequests = container.alertNavigationCoordinator.pendingRequest

    val forecastUiState: StateFlow<ForecastUiState> = combine(
        uiState,
        forecastRangeState,
        forecastLayersState,
        proModeState
    ) { state, range, layers, isProMode ->
        state.toForecastUiState(range = range, layers = layers, isProMode = isProMode)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ForecastUiState(loadState = io.aaps.copilot.ui.foundation.screens.ScreenLoadState.LOADING, isStale = true)
        )

    val uamUiState: StateFlow<UamUiState> = combine(uiState, proModeState) { state, isProMode ->
        state.toUamUiState(isProMode = isProMode)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UamUiState(loadState = io.aaps.copilot.ui.foundation.screens.ScreenLoadState.LOADING, isStale = true)
        )

    val safetyUiState: StateFlow<SafetyUiState> = combine(
        uiState,
        killSwitchOverrideState,
        LocalNightscoutRuntimeState.snapshot
    ) { state, killSwitchOverride, localNightscout ->
        val mapped = state.toSafetyUiState()
        val runtimeText = buildString {
            append(localNightscout.status.name)
            localNightscout.reason?.let { append(" (reason=").append(it).append(')') }
            localNightscout.caFingerprint?.let { append(" CA=").append(it) }
        }
        mapped.copy(
            killSwitchEnabled = killSwitchOverride ?: mapped.killSwitchEnabled,
            localNightscoutRuntimeStatus = localNightscout.status.name,
            localNightscoutRuntimeReason = localNightscout.reason,
            localNightscoutCaFingerprint = localNightscout.caFingerprint,
            localNightscoutTlsOk = localNightscout.status.name == "READY",
            localNightscoutTlsStatusText = runtimeText,
            checklist = mapped.checklist.map { item ->
                if (item.title == "Nightscout local runtime") {
                    item.copy(
                        ok = localNightscout.status.name == "READY",
                        details = runtimeText
                    )
                } else {
                    item
                }
            }
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SafetyUiState(
                loadState = io.aaps.copilot.ui.foundation.screens.ScreenLoadState.LOADING,
                isStale = true,
                killSwitchEnabled = false,
                staleMinutesLimit = 10,
                hardBounds = "4.0..10.0",
                hardMinTargetMmol = 4.0,
                hardMaxTargetMmol = 10.0,
                adaptiveBounds = "4.0..9.0",
                baseTarget = 5.5,
                maxActionsIn6h = 3,
                localNightscoutEnabled = false,
                localNightscoutPort = 17580
            )
        )

    val auditUiState: StateFlow<AuditUiState> = combine(
        uiState,
        auditWindowState,
        auditOnlyErrorsState
    ) { state, window, onlyErrors ->
        state.toAuditUiState(
            window = window,
            onlyErrors = onlyErrors
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AuditUiState(loadState = io.aaps.copilot.ui.foundation.screens.ScreenLoadState.LOADING, isStale = true)
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val probableMealWindowsState = db.eatingWindowSnapshotDao()
        .observeLatest()
        .flatMapLatest { stored ->
            stored?.let { snapshot ->
                flowOf(container.eatingWindowSnapshotRepository.decode(snapshot).snapshot)
            } ?: db.therapyDao()
                .observeLatestCarbCandidates(limit = PROBABLE_MEAL_CARB_LIMIT)
                .map { rows ->
                    val canonical = ClinicalReportDatasetBuilder.canonicalTherapyEvents(
                        rows.mapNotNull(ClinicalReportDatasetBuilder::sanitizeTherapyEvent)
                    ).map { it.point }
                    EatingWindowSnapshotPlanner.plan(
                        evidence = canonical.mapNotNull { point ->
                            point.carbsG?.let { carbs ->
                                EatingEvidencePoint(point.ts, carbs, point.syntheticUam)
                            }
                        },
                        generatedAt = System.currentTimeMillis(),
                        zoneId = ZoneId.systemDefault()
                    )
                }
        }
        .conflate()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    val analyticsUiState: StateFlow<AnalyticsUiState> = combine(
        uiState,
        circadianReplaySummaryState,
        probableMealWindowsState
    ) { state, circadianReplaySummary, probableMealSnapshot ->
        state.toAnalyticsUiState(
            circadianReplaySummary = circadianReplaySummary,
            probableMealWindows = probableMealSnapshot?.stable?.windows.orEmpty(),
            recentProbableMealWindows = probableMealSnapshot?.recent?.windows.orEmpty()
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AnalyticsUiState(loadState = io.aaps.copilot.ui.foundation.screens.ScreenLoadState.LOADING, isStale = true)
        )

    private val aiChatComposerHeadState: StateFlow<AiChatComposerHeadState> = combine(
        aiChatDraftState,
        aiChatPendingAttachmentsState,
        aiChatVoiceRepliesEnabledState,
        aiChatRecordingState
    ) { draft, pendingAttachments, voiceRepliesEnabled, recording ->
        AiChatComposerHeadState(
            draft = draft,
            pendingAttachments = pendingAttachments,
            voiceRepliesEnabled = voiceRepliesEnabled,
            recording = recording
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AiChatComposerHeadState()
    )

    private val aiChatComposerState: StateFlow<AiChatComposerState> = combine(
        aiChatComposerHeadState,
        aiChatVoiceBusyState,
        aiChatSpeakingState
    ) { head, voiceBusy, speaking ->
        AiChatComposerState(
            draft = head.draft,
            pendingAttachments = head.pendingAttachments,
            voiceRepliesEnabled = head.voiceRepliesEnabled,
            recording = head.recording,
            voiceBusy = voiceBusy,
            speaking = speaking
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AiChatComposerState()
    )

    private val clinicalAiTransientState: StateFlow<ClinicalAiTransientState> = combine(
        clinicalAiPersistenceState,
        clinicalAiConnectionTestState
    ) { persistence, connectionTest ->
        ClinicalAiTransientState(
            persistence = persistence,
            connectionTest = connectionTest
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ClinicalAiTransientState(
            persistence = ClinicalAiPersistenceState(),
            connectionTest = ClinicalAiConnectionTestUiState()
        )
    )

    private val clinicalAiSettingsUiState: StateFlow<ClinicalAiSettingsUiState> = combine(
        container.settingsStore.settings,
        container.clinicalAiProviderManager.status,
        clinicalAiTransientState
    ) { settings, providerStatuses, transient ->
        val resolution = resolveClinicalAiSettings(
            persistedState = settings.clinicalAiConfigState,
            draft = transient.persistence.draft
        )
        val credential = mapClinicalAiCredentialUiState(
            providerStatuses[resolution.selectedProvider]
                ?: ClinicalAiProviderCredentialState(
                    configured = false,
                    busy = true
                )
        )
        val configurationUsable =
            resolution.persistableConfig != null &&
                !transient.persistence.pending &&
                !transient.persistence.saveFailed &&
                !credential.resetRequired
        ClinicalAiSettingsUiState(
            effectiveConfig = resolution.effectiveConfig,
            selectedProvider = resolution.selectedProvider,
            providerOptions = clinicalAiProviderOptions(),
            modelPresets = ClinicalAiModelCatalog.forProvider(
                resolution.selectedProvider
            ),
            customModelSelected = resolution.customModelSelected,
            customModelDraft = resolution.modelDraft,
            endpointDraft = resolution.endpointDraft,
            selectedProtocol = resolution.protocol,
            protocolOptions = clinicalAiProtocolOptions(),
            automaticCauseAnalysisEnabled = settings.automaticEventAiAnalysisEnabled,
            credential = credential,
            usesLegacyCredentialState = false,
            localValidationError = resolution.validationError,
            saveError = transient.persistence.saveFailed,
            connectionTest = transient.connectionTest,
            canTestConnection =
                configurationUsable &&
                    credential.configured &&
                    !credential.busy &&
                    transient.connectionTest.phase !=
                    ClinicalAiConnectionTestPhaseUi.RUNNING,
            canSendReport =
                configurationUsable &&
                    credential.configured &&
                    !credential.busy,
            labels = clinicalAiLabels()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ClinicalAiSettingsUiState(
            effectiveConfig = ClinicalAiProviderConfig.defaultOpenAi(),
            providerOptions = clinicalAiProviderOptions(),
            protocolOptions = clinicalAiProtocolOptions(),
            labels = clinicalAiLabels()
        )
    )

    private val legacyAiAnalysisUiState: StateFlow<AiAnalysisUiState> = combine(
        uiState,
        circadianReplaySummaryState,
        aiChatMessagesState,
        aiChatInProgressState,
        aiChatComposerState
    ) { state, circadianReplaySummary, messages, inProgress, composer ->
        state.toAiAnalysisUiState(
            circadianReplaySummary = circadianReplaySummary,
            chatMessages = messages,
            chatInProgress = inProgress,
            chatDraft = composer.draft,
            chatPendingAttachments = composer.pendingAttachments.map { it.toUi() },
            chatVoiceRepliesEnabled = composer.voiceRepliesEnabled,
            chatRecording = composer.recording,
            chatVoiceBusy = composer.voiceBusy,
            chatSpeaking = composer.speaking
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AiAnalysisUiState(
                loadState = io.aaps.copilot.ui.foundation.screens.ScreenLoadState.LOADING,
                isStale = true
            )
        )

    private val clinicalAiRetryStateObserver = viewModelScope.launch {
        val resetGate = ClinicalAiRetryStateResetGate()
        clinicalAiSettingsUiState
            .map { it.canSendReport }
            .distinctUntilChanged()
            .collect { configurationUsable ->
                if (resetGate.shouldClear(configurationUsable)) {
                    clearClinicalReportRetryState()
                }
            }
    }

    val aiAnalysisUiState: StateFlow<AiAnalysisUiState> = combine(
        legacyAiAnalysisUiState,
        clinicalReportCommands.state,
        clinicalReportRetryConfirmationState,
        clinicalReportRetryFeedbackState,
        clinicalAiSettingsUiState
    ) {
            legacyState,
            clinicalState,
            retryConfirmationRequested,
            retryFeedback,
            clinicalAi ->
        val mapped = legacyState.withClinicalReport(
            clinicalReport = mapClinicalReportState(
                state = clinicalState,
                retryConfirmationRequested = retryConfirmationRequested,
                retryFeedback = retryFeedback,
                disclosure = clinicalAi.effectiveConfig.toClinicalReportDisclosureUi()
            )
        )
        mapped.copy(
            clinicalReport = mapped.clinicalReport.withClinicalAiRemoteActionsEnabled(
                clinicalAi.canSendReport
            )
        )
    }
        .combine(clinicalReportPdfExportCoordinator.state) { state, pdfExportState ->
            state.copy(
                clinicalReport = state.clinicalReport.copy(
                    canSavePdf =
                        (
                            state.clinicalReport.phase ==
                                io.aaps.copilot.ui.foundation.screens.ClinicalReportPhaseUi.LOCAL_READY ||
                            state.clinicalReport.phase ==
                                io.aaps.copilot.ui.foundation.screens.ClinicalReportPhaseUi.COMPLETE
                            ) && !pdfExportState.requiresReprepare,
                    pdfExportState = pdfExportState.phase,
                    pdfExportTicket = pdfExportState.ticket,
                    canSharePdf = pdfExportState.canShare,
                    pdfRequiresReprepare = pdfExportState.requiresReprepare
                )
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = legacyAiAnalysisUiState.value.withClinicalReport(
                clinicalReport = mapClinicalReportState(
                    state = clinicalReportCommands.state.value,
                    retryConfirmationRequested = false,
                    disclosure = clinicalAiSettingsUiState.value.effectiveConfig
                        .toClinicalReportDisclosureUi()
                )
            )
        )

    private val rawEnergyProfileSettingsState = combine(
        container.settingsStore.settings,
        container.db.energyProfileDao().observeLatestSnapshot(),
        container.db.energyProfileDao().observeAllEvents()
    ) { settings, snapshot, events ->
        energyProfileSettingsUiState(
            settings = settings.energyProfile,
            snapshot = snapshot,
            events = events
        )
    }

    private val energyProfileSettingsState = combine(
        rawEnergyProfileSettingsState,
        energyProfileValidationMessage
    ) { profile, validationMessage ->
        profile.copy(validationMessage = validationMessage)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = energyProfileSettingsUiState(
            settings = EnergyProfileSettings(),
            snapshot = null,
            events = emptyList()
        )
    )

    private val rawSettingsUiState: StateFlow<SettingsUiState> = combine(
        combine(
            container.settingsStore.settings,
            container.openAiCredentialProvider.status,
            clinicalAiSettingsUiState,
            energyProfileSettingsState
        ) { settings, credentialStatus, clinicalAi, energyProfile ->
            SettingsUiDependencies(settings, credentialStatus, clinicalAi, energyProfile)
        },
        verboseLogsState,
        proModeState,
        glucoseAlertAudioPreviewState,
        container.isfCrRepository.observeRecentTags(
            sinceTs = System.currentTimeMillis() - PHYSIO_TAG_JOURNAL_LOOKBACK_MS
        )
    ) { settingsAndCredential, verbose, proMode, previewState, tags ->
        val (settings, credentialStatus, clinicalAi, energyProfile) = settingsAndCredential
        val nowTs = System.currentTimeMillis()
        val activeTags = tags
            .asSequence()
            .filter { it.tsEnd >= nowTs }
            .sortedBy { it.tsEnd }
            .map { tag ->
                val pct = (tag.severity * 100.0).coerceIn(0.0, 100.0)
                String.format(Locale.US, "%s %.0f%%", tag.tagType, pct)
            }
            .toList()
        val tagJournal = tags
            .sortedByDescending { it.tsStart }
            .take(PHYSIO_TAG_JOURNAL_MAX_ROWS)
            .map { tag ->
                PhysioTagJournalItemUi(
                    id = tag.id,
                    revision = tag.revision,
                    tagType = tag.tagType,
                    severity = tag.severity.coerceIn(0.0, 1.0),
                    tsStart = tag.tsStart,
                    tsEnd = tag.tsEnd,
                    isActive = tag.tsEnd >= nowTs,
                    source = tag.source,
                    note = tag.note
                )
            }
        val softAudioSpec = GlucoseAlertAudioProfiles.resolve(settings, GlucoseAlertAudioSlot.SOFT)
        val criticalAudio1Spec = GlucoseAlertAudioProfiles.resolve(settings, GlucoseAlertAudioSlot.CRITICAL_PRIMARY)
        val criticalAudio2Spec = GlucoseAlertAudioProfiles.resolve(settings, GlucoseAlertAudioSlot.CRITICAL_SECONDARY)
        SettingsUiState(
            loadState = io.aaps.copilot.ui.foundation.screens.ScreenLoadState.READY,
            isStale = false,
            errorText = null,
            proModeEnabled = proMode,
            baseTarget = settings.baseTargetMmol,
            nightscoutUrl = settings.nightscoutUrl,
            aiApiUrl = settings.cloudBaseUrl,
            aiCredential = AiCredentialUiState(
                configured = credentialStatus.configured,
                busy = credentialStatus.busy,
                migrationError = credentialStatus.migrationFailed,
                readError = credentialStatus.readFailed,
                legacyCleanupPending = credentialStatus.legacyCleanupPending
            ),
            energyProfile = energyProfile,
            mealPortions = settings.mealPortions,
            clinicalAi = clinicalAi,
            uiStyle = settings.uiStyle.name,
            resolvedNightscoutUrl = settings.resolvedNightscoutUrl(),
            insulinProfileId = settings.insulinProfileId,
            localNightscoutEnabled = settings.localNightscoutEnabled,
            localNightscoutLegacyMigrationAcknowledged =
                settings.localNightscoutLegacyMigrationAcknowledged,
            localBroadcastIngestEnabled = settings.localBroadcastIngestEnabled,
            strictBroadcastSenderValidation = settings.strictBroadcastSenderValidation,
            enableUamInference = settings.enableUamInference,
            enableUamBoost = settings.enableUamBoost,
            uamExport = UamExportControlUi(
                available = settings.enableUamInference,
                mode = UamExportUiModePolicy.resolve(settings).name
            ),
            enableUamAutoExportCap = settings.enableUamAutoExportCap,
            uamAutoExportCapGrams = settings.uamAutoExportCapGrams,
            uamMinSnackG = settings.uamMinSnackG,
            uamMaxSnackG = settings.uamMaxSnackG,
            uamSnackStepG = settings.uamSnackStepG,
            circadianPatternsEnabled = settings.circadianPatternsEnabled,
            circadianStableLookbackDays = settings.circadianStableLookbackDays,
            circadianRecencyLookbackDays = settings.circadianRecencyLookbackDays,
            circadianUseWeekendSplit = settings.circadianUseWeekendSplit,
            circadianUseReplayResidualBias = settings.circadianUseReplayResidualBias,
            circadianForecastWeight30 = settings.circadianForecastWeight30,
            circadianForecastWeight60 = settings.circadianForecastWeight60,
            sensorLagCorrectionMode = settings.sensorLagCorrectionMode.name,
            targetManagerMode = settings.targetManagerMode.name,
            targetManagerModeManualOverride = settings.targetManagerModeManualOverride,
            targetManagerCopilotPriorityEnabled = settings.targetManagerCopilotPriorityEnabled,
            targetManagerPolicyRevision = settings.targetManagerPolicyRevision,
            adaptiveControllerRetargetMinutes = settings.adaptiveControllerRetargetMinutes,
            rulePostHypoCooldownMinutes = settings.rulePostHypoCooldownMinutes,
            rulePatternCooldownMinutes = settings.rulePatternCooldownMinutes,
            ruleSegmentCooldownMinutes = settings.ruleSegmentCooldownMinutes,
            softAlertEnabled = settings.softAlertEnabled,
            watch60AlertEnabled = settings.watch60AlertEnabled,
            warning30AlertEnabled = settings.warning30AlertEnabled,
            softHighAlertEnabled = settings.softHighAlertEnabled,
            critical5AlertEnabled = settings.critical5AlertEnabled,
            lowNowAlertEnabled = settings.lowNowAlertEnabled,
            softAlertLowMmol = settings.softAlertLowMmol,
            softAlertHighMmol = settings.softAlertHighMmol,
            urgentLowMmol = settings.urgentLowMmol,
            softAlertClipLabel = softAudioSpec.label,
            criticalAlertClip1Label = criticalAudio1Spec.label,
            criticalAlertClip2Label = criticalAudio2Spec.label,
            softAlertAudioStartSeconds = settings.softAlertAudioStartMs / 1_000,
            softAlertAudioDurationSeconds = settings.softAlertAudioDurationMs / 1_000,
            criticalAlertAudio1StartSeconds = settings.criticalAlertAudio1StartMs / 1_000,
            criticalAlertAudio1DurationSeconds = settings.criticalAlertAudio1DurationMs / 1_000,
            criticalAlertAudio2StartSeconds = settings.criticalAlertAudio2StartMs / 1_000,
            criticalAlertAudio2DurationSeconds = settings.criticalAlertAudio2DurationMs / 1_000,
            softAlertAudioValid = softAudioSpec.valid,
            criticalAlertAudio1Valid = criticalAudio1Spec.valid,
            criticalAlertAudio2Valid = criticalAudio2Spec.valid,
            softAlertAudioPreviewing = previewState.isPreview && previewState.activeSlot == GlucoseAlertAudioSlot.SOFT,
            criticalAlertAudio1Previewing = previewState.isPreview && previewState.activeSlot == GlucoseAlertAudioSlot.CRITICAL_PRIMARY,
            criticalAlertAudio2Previewing = previewState.isPreview && previewState.activeSlot == GlucoseAlertAudioSlot.CRITICAL_SECONDARY,
            isfRuntimeSourcePreference = settings.isfSourcePreference.name,
            crRuntimeSourcePreference = settings.crSourcePreference.name,
            isfCrShadowMode = settings.isfCrShadowMode,
            isfCrConfidenceThreshold = settings.isfCrConfidenceThreshold,
            isfCrUseActivity = settings.isfCrUseActivity,
            isfCrUseManualTags = settings.isfCrUseManualTags,
            isfCrMinIsfEvidencePerHour = settings.isfCrMinIsfEvidencePerHour,
            isfCrMinCrEvidencePerHour = settings.isfCrMinCrEvidencePerHour,
            isfCrCrMaxGapMinutes = settings.isfCrCrMaxGapMinutes,
            isfCrCrMaxSensorBlockedRatePct = settings.isfCrCrMaxSensorBlockedRatePct,
            isfCrCrMaxUamAmbiguityRatePct = settings.isfCrCrMaxUamAmbiguityRatePct,
            isfCrSnapshotRetentionDays = settings.isfCrSnapshotRetentionDays,
            isfCrEvidenceRetentionDays = settings.isfCrEvidenceRetentionDays,
            isfCrAutoActivationEnabled = settings.isfCrAutoActivationEnabled,
            isfCrAutoActivationLookbackHours = settings.isfCrAutoActivationLookbackHours,
            isfCrAutoActivationMinSamples = settings.isfCrAutoActivationMinSamples,
            isfCrAutoActivationMinMeanConfidence = settings.isfCrAutoActivationMinMeanConfidence,
            isfCrAutoActivationMaxMeanAbsIsfDeltaPct = settings.isfCrAutoActivationMaxMeanAbsIsfDeltaPct,
            isfCrAutoActivationMaxMeanAbsCrDeltaPct = settings.isfCrAutoActivationMaxMeanAbsCrDeltaPct,
            isfCrAutoActivationMinSensorQualityScore = settings.isfCrAutoActivationMinSensorQualityScore,
            isfCrAutoActivationMinSensorFactor = settings.isfCrAutoActivationMinSensorFactor,
            isfCrAutoActivationMaxWearConfidencePenalty = settings.isfCrAutoActivationMaxWearConfidencePenalty,
            isfCrAutoActivationMaxSensorAgeHighRatePct = settings.isfCrAutoActivationMaxSensorAgeHighRatePct,
            isfCrAutoActivationMaxSuspectFalseLowRatePct = settings.isfCrAutoActivationMaxSuspectFalseLowRatePct,
            isfCrAutoActivationMinDayTypeRatio = settings.isfCrAutoActivationMinDayTypeRatio,
            isfCrAutoActivationMaxDayTypeSparseRatePct = settings.isfCrAutoActivationMaxDayTypeSparseRatePct,
            isfCrAutoActivationRequireDailyQualityGate = settings.isfCrAutoActivationRequireDailyQualityGate,
            isfCrAutoActivationDailyRiskBlockLevel = settings.isfCrAutoActivationDailyRiskBlockLevel,
            isfCrAutoActivationMinDailyMatchedSamples = settings.isfCrAutoActivationMinDailyMatchedSamples,
            isfCrAutoActivationMaxDailyMae30Mmol = settings.isfCrAutoActivationMaxDailyMae30Mmol,
            isfCrAutoActivationMaxDailyMae60Mmol = settings.isfCrAutoActivationMaxDailyMae60Mmol,
            isfCrAutoActivationMaxHypoRatePct = settings.isfCrAutoActivationMaxHypoRatePct,
            isfCrAutoActivationMinDailyCiCoverage30Pct = settings.isfCrAutoActivationMinDailyCiCoverage30Pct,
            isfCrAutoActivationMinDailyCiCoverage60Pct = settings.isfCrAutoActivationMinDailyCiCoverage60Pct,
            isfCrAutoActivationMaxDailyCiWidth30Mmol = settings.isfCrAutoActivationMaxDailyCiWidth30Mmol,
            isfCrAutoActivationMaxDailyCiWidth60Mmol = settings.isfCrAutoActivationMaxDailyCiWidth60Mmol,
            isfCrAutoActivationRollingMinRequiredWindows = settings.isfCrAutoActivationRollingMinRequiredWindows,
            isfCrAutoActivationRollingMaeRelaxFactor = settings.isfCrAutoActivationRollingMaeRelaxFactor,
            isfCrAutoActivationRollingCiCoverageRelaxFactor = settings.isfCrAutoActivationRollingCiCoverageRelaxFactor,
            isfCrAutoActivationRollingCiWidthRelaxFactor = settings.isfCrAutoActivationRollingCiWidthRelaxFactor,
            isfCrActiveTags = activeTags,
            isfCrTagJournal = tagJournal,
            adaptiveControllerEnabled = settings.adaptiveControllerEnabled,
            safetyMinTargetMmol = settings.safetyMinTargetMmol,
            safetyMaxTargetMmol = settings.safetyMaxTargetMmol,
            postHypoThresholdMmol = settings.postHypoThresholdMmol,
            postHypoTargetMmol = settings.postHypoTargetMmol,
            verboseLogsEnabled = verbose,
            retentionDays = settings.analyticsLookbackDays,
            warningText = "Not a medical device. Verify all therapy decisions manually."
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(
                loadState = io.aaps.copilot.ui.foundation.screens.ScreenLoadState.LOADING,
                isStale = true,
                proModeEnabled = false,
                baseTarget = 5.5,
                nightscoutUrl = "",
                aiApiUrl = "",
                clinicalAi = clinicalAiSettingsUiState.value,
                uiStyle = UiStyle.MIDNIGHT_GLASS.name,
                resolvedNightscoutUrl = "",
                insulinProfileId = "NOVORAPID",
                localNightscoutEnabled = false,
                localBroadcastIngestEnabled = true,
                strictBroadcastSenderValidation = false,
                enableUamInference = true,
                enableUamBoost = true,
                uamExport = UamExportControlUi(),
                enableUamAutoExportCap = false,
                uamAutoExportCapGrams = 10,
                uamMinSnackG = 15,
                uamMaxSnackG = 60,
                uamSnackStepG = 5,
                circadianPatternsEnabled = true,
                circadianStableLookbackDays = 14,
                circadianRecencyLookbackDays = 5,
                circadianUseWeekendSplit = true,
                circadianUseReplayResidualBias = true,
                circadianForecastWeight30 = 0.25,
                circadianForecastWeight60 = 0.35,
                sensorLagCorrectionMode = "OFF",
                softAlertEnabled = true,
                watch60AlertEnabled = true,
                warning30AlertEnabled = true,
                softHighAlertEnabled = true,
                critical5AlertEnabled = true,
                lowNowAlertEnabled = true,
                softAlertLowMmol = 4.4,
                softAlertHighMmol = 10.0,
                urgentLowMmol = 3.9,
                softAlertClipLabel = "Elk Creek.mp3",
                criticalAlertClip1Label = "Night Waltz.mp3",
                criticalAlertClip2Label = "Snowbirds.mp3",
                softAlertAudioStartSeconds = 32,
                softAlertAudioDurationSeconds = 2,
                criticalAlertAudio1StartSeconds = 42,
                criticalAlertAudio1DurationSeconds = 20,
                criticalAlertAudio2StartSeconds = 36,
                criticalAlertAudio2DurationSeconds = 20,
                softAlertAudioValid = true,
                criticalAlertAudio1Valid = true,
                criticalAlertAudio2Valid = true,
                softAlertAudioPreviewing = false,
                criticalAlertAudio1Previewing = false,
                criticalAlertAudio2Previewing = false,
                isfRuntimeSourcePreference = "UNAVAILABLE",
                crRuntimeSourcePreference = "UNAVAILABLE",
                isfCrShadowMode = true,
                isfCrConfidenceThreshold = 0.45,
                isfCrUseActivity = true,
                isfCrUseManualTags = true,
                isfCrMinIsfEvidencePerHour = 2,
                isfCrMinCrEvidencePerHour = 2,
                isfCrCrMaxGapMinutes = 30,
                isfCrCrMaxSensorBlockedRatePct = 25.0,
                isfCrCrMaxUamAmbiguityRatePct = 60.0,
                isfCrSnapshotRetentionDays = 365,
                isfCrEvidenceRetentionDays = 730,
                isfCrAutoActivationEnabled = false,
                isfCrAutoActivationLookbackHours = 24,
                isfCrAutoActivationMinSamples = 72,
                isfCrAutoActivationMinMeanConfidence = 0.65,
                isfCrAutoActivationMaxMeanAbsIsfDeltaPct = 25.0,
                isfCrAutoActivationMaxMeanAbsCrDeltaPct = 25.0,
                isfCrAutoActivationMinSensorQualityScore = 0.46,
                isfCrAutoActivationMinSensorFactor = 0.90,
                isfCrAutoActivationMaxWearConfidencePenalty = 0.12,
                isfCrAutoActivationMaxSensorAgeHighRatePct = 70.0,
                isfCrAutoActivationMaxSuspectFalseLowRatePct = 35.0,
                isfCrAutoActivationMinDayTypeRatio = 0.30,
                isfCrAutoActivationMaxDayTypeSparseRatePct = 75.0,
                isfCrAutoActivationRequireDailyQualityGate = true,
                isfCrAutoActivationDailyRiskBlockLevel = 3,
                isfCrAutoActivationMinDailyMatchedSamples = 120,
                isfCrAutoActivationMaxDailyMae30Mmol = 0.90,
                isfCrAutoActivationMaxDailyMae60Mmol = 1.40,
                isfCrAutoActivationMaxHypoRatePct = 6.0,
                isfCrAutoActivationMinDailyCiCoverage30Pct = 55.0,
                isfCrAutoActivationMinDailyCiCoverage60Pct = 55.0,
                isfCrAutoActivationMaxDailyCiWidth30Mmol = 1.80,
                isfCrAutoActivationMaxDailyCiWidth60Mmol = 2.60,
                isfCrAutoActivationRollingMinRequiredWindows = 2,
                isfCrAutoActivationRollingMaeRelaxFactor = 1.15,
                isfCrAutoActivationRollingCiCoverageRelaxFactor = 0.90,
                isfCrAutoActivationRollingCiWidthRelaxFactor = 1.25,
                isfCrActiveTags = emptyList(),
                adaptiveControllerEnabled = true,
                safetyMinTargetMmol = 4.0,
                safetyMaxTargetMmol = 10.0,
                postHypoThresholdMmol = 4.0,
                postHypoTargetMmol = 4.4,
                verboseLogsEnabled = false,
                retentionDays = 30,
                warningText = "Not a medical device. Verify all decisions."
            )
        )

    val settingsUiState: StateFlow<SettingsUiState> = combine(
        rawSettingsUiState,
        baseTargetPresentationState,
        uiState,
        sensitivitySourceApplyState,
        LocalNightscoutRuntimeState.snapshot
    ) { state, targetPresentation, runtime, apply, localNightscout ->
        state.copy(
            baseTargetSchedule = targetPresentation.schedule,
            effectiveBaseTargetMmol = targetPresentation.effectiveTargetMmol,
            baseTargetAutoDeltaMmol = targetPresentation.autoDeltaMmol,
            baseTargetAutoState = targetPresentation.autoState,
            baseTargetAutoReason = targetPresentation.autoReason,
            uamExport = runtime.toUamExportControlUi(),
            isfRuntimeSourcePreference = sensitivitySourceSelection(
                acceptedSource = runtime.isfRuntimeSourcePreference,
                authoritativeSource = state.isfRuntimeSourcePreference,
                metric = SensitivityMetricKind.ISF.name,
                apply = apply
            ),
            crRuntimeSourcePreference = sensitivitySourceSelection(
                acceptedSource = runtime.crRuntimeSourcePreference,
                authoritativeSource = state.crRuntimeSourcePreference,
                metric = SensitivityMetricKind.CR.name,
                apply = apply
            ),
            sensitivitySourceApplying = apply.applying,
            sensitivitySourcePendingMetric = apply.pendingMetric,
            sensitivitySourcePendingValue = apply.pendingValue,
            sensitivitySourceApplyError = apply.error ?: runtime.sensitivitySourceApplyError,
            localNightscoutRuntimeStatus = localNightscout.status.name,
            localNightscoutRuntimeReason = localNightscout.reason,
            localNightscoutCaFingerprint = localNightscout.caFingerprint
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = rawSettingsUiState.value.copy(
            baseTargetSchedule = baseTargetPresentationState.value.schedule,
            effectiveBaseTargetMmol = baseTargetPresentationState.value.effectiveTargetMmol,
            baseTargetAutoDeltaMmol = baseTargetPresentationState.value.autoDeltaMmol,
            baseTargetAutoState = baseTargetPresentationState.value.autoState,
            baseTargetAutoReason = baseTargetPresentationState.value.autoReason,
            uamExport = uiState.value.toUamExportControlUi(),
            isfRuntimeSourcePreference = uiState.value.isfRuntimeSourcePreference,
            crRuntimeSourcePreference = uiState.value.crRuntimeSourcePreference
        )
    )

    fun setActiveRoute(route: String?) {
        val normalized = route?.takeIf { it.isNotBlank() } ?: ROUTE_OVERVIEW
        val previousRoute = activeRouteState.value
        activeRouteState.value = normalized
        if (normalized == ROUTE_ANALYTICS || normalized == ROUTE_AI_ANALYSIS) {
            ensureProfileAnalyticsHealthyAsync(reasonHint = "route:$normalized")
            ensureCircadianAnalyticsHealthyAsync(reasonHint = "route:$normalized")
            refreshCircadianReplaySummaryAsync(force = false)
        }
        if (normalized == ROUTE_AI_ANALYSIS) {
            if (shouldPrepareClinicalReportOnRouteTransition(previousRoute, normalized)) {
                prepareClinicalReport()
            }
            refreshCloudJobs(silent = true)
            refreshAnalysisInsights(silent = true)
            maybeGenerateLocalDailyAnalysis(silent = true)
        }
    }

    fun prepareClinicalReport() {
        viewModelScope.launch {
            clinicalReportCommands.prepare()
        }
    }

    fun retryLocalClinicalReportPreparation() {
        viewModelScope.launch {
            clinicalReportCommands.prepare()
        }
    }

    fun reprepareClinicalReportPdf() {
        viewModelScope.launch {
            clinicalReportPdfReprepareCoordinator.reprepare()
        }
    }

    fun beginClinicalReportPdfExport() = clinicalReportPdfExportCoordinator.begin()

    fun claimClinicalReportPdfExportTicket(ticketId: Long): Boolean =
        clinicalReportPdfExportCoordinator.claimReadyTicket(ticketId)

    val telegramRepository get() = container.telegramRepository

    suspend fun sendTelegramSummary() = container.telegramDeliveryController.sendCurrentSummary()

    fun shareClinicalReportPdf() {
        clinicalReportPdfExportCoordinator.beginShare { payload ->
            runCatching {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = payload.mimeType
                    putExtra(Intent.EXTRA_STREAM, payload.uri)
                    clipData = ClipData.newRawUri("clinical-pdf", payload.uri)
                    addFlags(payload.flags)
                }
                val chooser = Intent.createChooser(
                    send,
                    getApplication<Application>().getString(R.string.clinical_report_share_pdf)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or payload.flags)
                getApplication<Application>().startActivity(chooser)
                true
            }.getOrDefault(false)
        }
    }

    fun resolveClinicalReportPdfExport(
        ticketId: Long,
        uri: Uri?
    ) {
        clinicalReportPdfExportCoordinator.resolvePicker(ticketId, uri)
    }

    fun sendClinicalReport(confirmedDisclosure: ClinicalReportDisclosureUi) {
        clinicalReportRetryConfirmationState.value = false
        clinicalReportRetryFeedbackState.value = null
        viewModelScope.launch {
            if (!clinicalAiRemoteSendPreflight()) return@launch
            try {
                clinicalReportDisclosureSendGuard.sendIfMatches(confirmedDisclosure) {
                    clinicalReportCommands.send(confirmedDisclosure.configIdentity)
                }
            } catch (_: ClinicalReportConfigurationChangedException) {
                messageState.value = getApplication<Application>().getString(
                    R.string.settings_clinical_ai_report_settings_changed
                )
            }
        }
    }

    fun cancelClinicalReport() {
        clinicalReportRetryConfirmationState.value = false
        clinicalReportRetryFeedbackState.value = null
        viewModelScope.launch {
            clinicalReportCommands.cancel()
        }
    }

    fun requestUnknownClinicalReportRetryConfirmation() {
        val state = clinicalReportCommands.state.value as?
            io.aaps.copilot.data.repository.ClinicalReportState.Failed
        clinicalReportRetryConfirmationState.value =
            state?.reason ==
            io.aaps.copilot.data.repository.ClinicalReportFailureReason.UNKNOWN_REMOTE_OUTCOME &&
            state.local != null
        clinicalReportRetryFeedbackState.value = null
    }

    fun dismissUnknownClinicalReportRetryConfirmation() {
        clinicalReportRetryConfirmationState.value = false
        clinicalReportRetryFeedbackState.value = null
    }

    fun retryUnknownClinicalReport() {
        val confirmed = clinicalReportRetryConfirmationState.value
        if (
            !confirmed ||
            clinicalReportRetryFeedbackState.value == ClinicalReportRetryFeedbackUi.PENDING
        ) {
            return
        }
        clinicalReportRetryFeedbackState.value = ClinicalReportRetryFeedbackUi.PENDING
        viewModelScope.launch {
            if (!clinicalAiRemoteSendPreflight()) {
                clinicalReportRetryConfirmationState.value = false
                clinicalReportRetryFeedbackState.value = null
                return@launch
            }
            val disposition = runCatching {
                clinicalReportCommands.retryUnknown(confirmed)
            }.getOrNull()
            if (disposition == ClinicalReportRunDisposition.REMOTE_STARTED) {
                clinicalReportRetryConfirmationState.value = false
                clinicalReportRetryFeedbackState.value = null
            } else {
                clinicalReportRetryFeedbackState.value =
                    if (
                        disposition == ClinicalReportRunDisposition.LOCAL_IN_FLIGHT ||
                        disposition == ClinicalReportRunDisposition.REMOTE_IN_FLIGHT
                    ) {
                        ClinicalReportRetryFeedbackUi.IN_FLIGHT
                    } else {
                        ClinicalReportRetryFeedbackUi.FAILED
                    }
            }
        }
    }

    private fun clearClinicalReportRetryState() {
        clinicalReportRetryConfirmationState.value = false
        clinicalReportRetryFeedbackState.value = null
    }

    private suspend fun clinicalAiRemoteSendPreflight(): Boolean {
        return when (clinicalAiRemotePreflight.check()) {
            ClinicalAiRemotePreflightResult.READY -> true
            ClinicalAiRemotePreflightResult.INVALID_CONFIGURATION -> {
                messageState.value = getApplication<Application>().getString(
                    R.string.settings_clinical_ai_report_blocked_invalid
                )
                false
            }
            ClinicalAiRemotePreflightResult.PERSISTENCE_PENDING -> {
                messageState.value = getApplication<Application>().getString(
                    R.string.settings_clinical_ai_report_blocked_saving
                )
                false
            }
            ClinicalAiRemotePreflightResult.PERSISTENCE_FAILED -> {
                messageState.value = getApplication<Application>().getString(
                    R.string.settings_clinical_ai_config_save_failed
                )
                false
            }
        }
    }

    private fun refreshCircadianReplaySummaryAsync(force: Boolean) {
        val nowTs = System.currentTimeMillis()
        if (!force && circadianReplaySummaryState.value != null && nowTs - circadianReplayRefreshStartedAt < CIRCADIAN_REPLAY_REFRESH_TTL_MS) {
            return
        }
        if (circadianReplayRefreshRunning) return
        circadianReplayRefreshRunning = true
        circadianReplayRefreshStartedAt = nowTs
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val settings = container.settingsStore.settings.first()
                container.analyticsRepository.buildCircadianReplaySummary(
                    settings = settings,
                    nowTs = System.currentTimeMillis()
                )
            }.onSuccess { summary ->
                circadianReplaySummaryState.value = summary.toUi()
            }.onFailure { error ->
                container.auditLogger.warn(
                    "circadian_replay_summary_failed",
                    mapOf("error" to (error.message ?: error::class.java.simpleName))
                )
            }
            circadianReplayRefreshRunning = false
        }
    }

    private fun CircadianReplaySummary.toUi(): CircadianReplaySummaryUi {
        return CircadianReplaySummaryUi(
            generatedAtTs = generatedAtTs,
            windows = windows.map { window ->
                CircadianReplayWindowUi(
                    days = window.days,
                    appliedRows = window.appliedRows,
                    appliedPct = window.appliedPct,
                    meanShift30 = window.meanShift30,
                    meanShift60 = window.meanShift60,
                    buckets = window.buckets.map { bucket ->
                        CircadianReplayBucketUi(
                            bucket = bucket.bucket,
                            metrics = bucket.metrics.map { metric ->
                                CircadianReplayMetricUi(
                                    horizonMinutes = metric.horizonMinutes,
                                    sampleCount = metric.sampleCount,
                                    maeBaseline = metric.maeBaseline,
                                    maeCircadian = metric.maeCircadian,
                                    deltaMmol = metric.deltaMmol,
                                    deltaPct = metric.deltaPct,
                                    winRate = metric.winRate,
                                    qualityScore = metric.qualityScore,
                                    bucketStatus = metric.bucketStatus.name
                                )
                            }
                        )
                    }
                )
            }
        )
    }

    fun clearMessage() {
        messageState.value = null
    }

    fun runCycleNow() {
        runAutomationNow()
    }

    fun openAapsBolusDialog() {
        val result = container.aapsBolusLauncher.open()
        messageState.value = when (result) {
            AapsBolusLaunchResult.OPENED ->
                getApplication<Application>().getString(R.string.aaps_bolus_launch_opened)
            AapsBolusLaunchResult.AAPS_NOT_INSTALLED ->
                getApplication<Application>().getString(R.string.aaps_bolus_launch_not_installed)
            AapsBolusLaunchResult.INCOMPATIBLE_BUILD ->
                getApplication<Application>().getString(R.string.aaps_bolus_launch_incompatible_build)
            AapsBolusLaunchResult.INCOMPATIBLE_SIGNATURE_OR_PERMISSION_DENIED ->
                getApplication<Application>().getString(R.string.aaps_bolus_launch_incompatible)
            AapsBolusLaunchResult.LAUNCH_FAILED ->
                getApplication<Application>().getString(R.string.aaps_bolus_launch_failed)
        }
        viewModelScope.launch(Dispatchers.IO) {
            container.auditLogger.info(
                "aaps_bolus_dialog_launch",
                mapOf("result" to result.name)
            )
        }
    }

    fun muteGlucoseAlerts30m() {
        muteGlucoseAlerts(GlucoseAlertMuteOption.MINUTES_30)
    }

    fun muteGlucoseAlerts60m() {
        muteGlucoseAlerts(GlucoseAlertMuteOption.MINUTES_60)
    }

    fun refreshAlertsWindow(nowTs: Long = System.currentTimeMillis()) {
        alertsHistoryWindow.value = AlertsRepository.historyWindowAt(nowTs)
    }

    fun selectAlertEpisode(episodeId: String?) {
        selectedAlertEpisodeId.value = episodeId?.takeIf(AlertsRepository::isSafeEpisodeId)
    }

    fun acknowledgeAlertNavigation(token: Long): Boolean =
        container.alertNavigationCoordinator.acknowledge(token)

    fun resumeGlucoseAlerts() {
        viewModelScope.launch(Dispatchers.IO) {
            resumeGlucoseAlertsNow(System.currentTimeMillis())
        }
    }

    fun toggleGlucoseAlerts() {
        viewModelScope.launch(Dispatchers.IO) {
            val nowTs = System.currentTimeMillis()
            val transition = container.episodeAlertDelivery.toggleFromOverview(nowTs)
            when (transition.action) {
                GlucoseAlertBellAction.MUTE_30_MINUTES -> {
                    container.auditLogger.info(
                        "glucose_alert_muted_from_overview",
                        mapOf(
                            "durationMinutes" to GlucoseAlertMuteOption.MINUTES_30.minutes,
                            "mutedUntilTs" to transition.mutedUntilTs,
                            "source" to "overview_bell",
                            "sourceState" to overviewUiState.value.glucoseAlertState.orEmpty()
                        )
                    )
                    messageState.value = getApplication<Application>().getString(
                        R.string.alerts_muted_confirmation,
                        GlucoseAlertMuteOption.MINUTES_30.minutes
                    )
                }
                GlucoseAlertBellAction.RESUME -> {
                    completeGlucoseAlertResume(transition.previousMutedUntilTs)
                }
            }
        }
    }

    private suspend fun resumeGlucoseAlertsNow(nowTs: Long) {
        val previousMutedUntilTs = container.episodeAlertDelivery.currentMutedUntil()
        container.episodeAlertDelivery.resume(nowTs)
        completeGlucoseAlertResume(previousMutedUntilTs)
    }

    private suspend fun completeGlucoseAlertResume(previousMutedUntilTs: Long) {
        container.auditLogger.info(
            "glucose_alert_resumed_from_overview",
            mapOf(
                "previousMutedUntilTs" to previousMutedUntilTs,
                "sourceState" to overviewUiState.value.glucoseAlertState.orEmpty()
            )
        )
        messageState.value = getApplication<Application>().getString(R.string.alerts_resumed_confirmation)
        container.automationRepository.runAutomationCycle()
    }

    private fun muteGlucoseAlerts(option: GlucoseAlertMuteOption) {
        viewModelScope.launch(Dispatchers.IO) {
            applyGlucoseAlertMute(
                option = option,
                nowTs = System.currentTimeMillis(),
                source = "overview_action"
            )
        }
    }

    private suspend fun applyGlucoseAlertMute(
        option: GlucoseAlertMuteOption,
        nowTs: Long,
        source: String
    ) {
        val overview = overviewUiState.value
        val mutedUntilTs = container.episodeAlertDelivery.muteFor(nowTs, option.durationMs)
        container.auditLogger.info(
            "glucose_alert_muted_from_overview",
            mapOf(
                "durationMinutes" to option.minutes,
                "mutedUntilTs" to mutedUntilTs,
                "source" to source,
                "sourceState" to overview.glucoseAlertState.orEmpty()
            )
        )
        messageState.value = getApplication<Application>().getString(
            R.string.alerts_muted_confirmation,
            option.minutes
        )
    }

    fun addManualBloodGlucoseCheck(
        valueRaw: String,
        units: String,
        timestamp: Long = System.currentTimeMillis(),
        noteRaw: String = ""
    ) {
        viewModelScope.launch {
            val value = parseFlexibleDouble(valueRaw)
            if (value == null) {
                messageState.value = "Blood check failed: invalid glucose value"
                return@launch
            }
            val check = try {
                container.glucoseCalibrationRepository.addManualBloodGlucoseCheck(
                    value = value,
                    units = units,
                    timestamp = timestamp,
                    note = noteRaw.trim().takeIf { it.isNotBlank() }
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (fatal: Error) {
                throw fatal
            } catch (error: Exception) {
                messageState.value = "Blood check failed: ${error.message}"
                return@launch
            }
            messageState.value = "Blood check saved: ${String.format(Locale.US, "%.1f", check.mmol)} mmol/L"
            runManualCalibrationFollowUp {
                container.automationRepository.runAutomationCycle()
            }?.let { failure ->
                auditManualCalibrationFollowUpFailure("automation_cycle", failure)
            }
        }
    }

    fun resetManualGlucoseCalibration() {
        viewModelScope.launch {
            val result = try {
                container.glucoseCalibrationRepository.resetManualCalibration()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (fatal: Error) {
                throw fatal
            } catch (error: Exception) {
                messageState.value = "Calibration reset failed: ${error.message}"
                return@launch
            }
            messageState.value = if (result.retiredModelCount > 0 || result.resetCheckCount > 0) {
                "Glucose calibration reset"
            } else {
                "No active glucose calibration"
            }
        }
    }

    private suspend fun auditManualCalibrationFollowUpFailure(stage: String, failure: Exception) {
        runManualCalibrationFollowUp {
            container.auditLogger.warn(
                "manual_glucose_calibration_follow_up_failed",
                mapOf(
                    "stage" to stage,
                    "error" to (failure.message ?: failure::class.java.simpleName)
                )
            )
        }
    }

    fun setForecastRange(range: ForecastRangeUi) {
        forecastRangeState.value = range
    }

    fun setForecastLayers(
        showTrend: Boolean? = null,
        showTherapy: Boolean? = null,
        showUam: Boolean? = null,
        showCi: Boolean? = null
    ) {
        val current = forecastLayersState.value
        forecastLayersState.value = current.copy(
            showTrend = showTrend ?: current.showTrend,
            showTherapy = showTherapy ?: current.showTherapy,
            showUam = showUam ?: current.showUam,
            showCi = showCi ?: current.showCi
        )
    }

    fun setAuditWindow(window: AuditWindowUi) {
        auditWindowState.value = window
    }

    fun setAuditOnlyErrors(enabled: Boolean) {
        auditOnlyErrorsState.value = enabled
    }

    fun setVerboseLogsEnabled(enabled: Boolean) {
        verboseLogsState.value = enabled
        messageState.value = if (enabled) {
            "Verbose UI logs enabled"
        } else {
            "Verbose UI logs disabled"
        }
    }

    fun setProModeEnabled(enabled: Boolean) {
        proModeState.value = enabled
        messageState.value = if (enabled) {
            "Pro mode enabled"
        } else {
            "Pro mode disabled"
        }
    }

    fun markUamEventCorrect(eventId: String) {
        updateUamEvent(eventId) { event ->
            event.copy(
                state = UamInferenceState.FINAL.name,
                learnedEligible = true,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    fun markUamEventWrong(eventId: String) {
        updateUamEvent(eventId) { event ->
            event.copy(
                state = UamInferenceState.MERGED.name,
                learnedEligible = false,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    fun mergeUamEventWithManualCarbs(eventId: String) {
        updateUamEvent(eventId) { event ->
            event.copy(
                state = UamInferenceState.MERGED.name,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private fun updateUamEvent(
        eventId: String,
        updater: (UamInferenceEventEntity) -> UamInferenceEventEntity
    ) {
        viewModelScope.launch {
            val dao = db.uamInferenceEventDao()
            val current = dao.byId(eventId)
            if (current == null) {
                messageState.value = "UAM event not found"
                return@launch
            }
            dao.upsert(updater(current))
            messageState.value = "UAM event updated"
        }
    }

    fun saveConnections(nightscoutUrl: String, apiSecret: String, cloudUrl: String, exportUri: String?) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    nightscoutUrl = nightscoutUrl,
                    apiSecret = apiSecret,
                    cloudBaseUrl = cloudUrl,
                    exportFolderUri = exportUri
                )
            }
            messageState.value = "Connection settings saved"
        }
    }

    fun setExportFolderUri(exportUri: String?) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(exportFolderUri = exportUri)
            }
            messageState.value = "Export folder saved"
        }
    }

    fun setBaseTarget(mmol: Double) {
        viewModelScope.launch {
            container.settingsStore.updateBaseTargetDefault(mmol)
            recalculateAnalyticsAsync()
            messageState.value = "Base target updated"
        }
    }

    fun saveBaseTargetSchedule(schedule: BaseTargetSchedule) {
        viewModelScope.launch {
            try {
                container.settingsStore.saveBaseTargetSchedule(schedule)
                recalculateAnalyticsAsync()
                messageState.value = "Base target schedule updated"
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                messageState.value = error.message ?: "Unable to update base target schedule"
            }
        }
    }

    fun setGlucoseAlertSettings(
        softEnabled: Boolean? = null,
        watch60Enabled: Boolean? = null,
        warning30Enabled: Boolean? = null,
        softHighEnabled: Boolean? = null,
        critical5Enabled: Boolean? = null,
        lowNowEnabled: Boolean? = null,
        softLowMmol: Double? = null,
        softHighMmol: Double? = null,
        urgentLowMmol: Double? = null
    ) {
        viewModelScope.launch {
            container.settingsStore.update { current ->
                val nextSoftLow = (softLowMmol ?: current.softAlertLowMmol).coerceIn(3.6, 6.0)
                val nextSoftHigh = (softHighMmol ?: current.softAlertHighMmol).coerceIn(7.0, 13.9)
                    .coerceAtLeast(nextSoftLow + 0.1)
                val nextUrgentLow = (urgentLowMmol ?: current.urgentLowMmol).coerceIn(3.0, 4.4)
                    .coerceAtMost(nextSoftLow)
                current.copy(
                    softAlertEnabled = softEnabled ?: current.softAlertEnabled,
                    watch60AlertEnabled = watch60Enabled ?: current.watch60AlertEnabled,
                    warning30AlertEnabled = warning30Enabled ?: current.warning30AlertEnabled,
                    softHighAlertEnabled = softHighEnabled ?: current.softHighAlertEnabled,
                    critical5AlertEnabled = critical5Enabled ?: current.critical5AlertEnabled,
                    lowNowAlertEnabled = lowNowEnabled ?: current.lowNowAlertEnabled,
                    softAlertLowMmol = nextSoftLow,
                    softAlertHighMmol = nextSoftHigh,
                    urgentLowMmol = nextUrgentLow
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "Glucose alert settings updated"
        }
    }

    fun setSoftGlucoseAlertAudio(
        startSeconds: Int,
        durationSeconds: Int
    ) {
        viewModelScope.launch {
            val nextSettings = container.settingsStore.settings.first().copy(
                softAlertAudioStartMs = (startSeconds.coerceIn(0, 180) * 1_000),
                softAlertAudioDurationMs = (durationSeconds.coerceIn(1, 5) * 1_000)
            )
            container.settingsStore.update { current ->
                nextSettings
            }
            container.auditLogger.info(
                "glucose_alert_audio_profile_updated",
                mapOf(
                    "slot" to "SOFT",
                    "clip" to GlucoseAlertAudioProfiles.resolve(nextSettings, GlucoseAlertAudioSlot.SOFT).label,
                    "startSeconds" to startSeconds,
                    "durationSeconds" to durationSeconds
                )
            )
            messageState.value = "Soft alert clip updated"
        }
    }

    fun setCriticalGlucoseAlertAudio1(
        startSeconds: Int,
        durationSeconds: Int
    ) {
        viewModelScope.launch {
            val nextSettings = container.settingsStore.settings.first().copy(
                criticalAlertAudio1StartMs = (startSeconds.coerceIn(0, 180) * 1_000),
                criticalAlertAudio1DurationMs = (durationSeconds.coerceIn(15, 30) * 1_000)
            )
            container.settingsStore.update { current ->
                nextSettings
            }
            container.auditLogger.info(
                "glucose_alert_audio_profile_updated",
                mapOf(
                    "slot" to "CRITICAL_PRIMARY",
                    "clip" to GlucoseAlertAudioProfiles.resolve(nextSettings, GlucoseAlertAudioSlot.CRITICAL_PRIMARY).label,
                    "startSeconds" to startSeconds,
                    "durationSeconds" to durationSeconds
                )
            )
            messageState.value = "Critical alert clip #1 updated"
        }
    }

    fun setCriticalGlucoseAlertAudio2(
        startSeconds: Int,
        durationSeconds: Int
    ) {
        viewModelScope.launch {
            val nextSettings = container.settingsStore.settings.first().copy(
                criticalAlertAudio2StartMs = (startSeconds.coerceIn(0, 180) * 1_000),
                criticalAlertAudio2DurationMs = (durationSeconds.coerceIn(15, 30) * 1_000)
            )
            container.settingsStore.update { current ->
                nextSettings
            }
            container.auditLogger.info(
                "glucose_alert_audio_profile_updated",
                mapOf(
                    "slot" to "CRITICAL_SECONDARY",
                    "clip" to GlucoseAlertAudioProfiles.resolve(nextSettings, GlucoseAlertAudioSlot.CRITICAL_SECONDARY).label,
                    "startSeconds" to startSeconds,
                    "durationSeconds" to durationSeconds
                )
            )
            messageState.value = "Critical alert clip #2 updated"
        }
    }

    fun replaceGlucoseAlertAudioSource(
        slotRaw: String,
        uriString: String
    ) {
        viewModelScope.launch {
            val slot = when (slotRaw.uppercase(Locale.US)) {
                "SOFT" -> GlucoseAlertAudioSlot.SOFT
                "CRITICAL_PRIMARY" -> GlucoseAlertAudioSlot.CRITICAL_PRIMARY
                "CRITICAL_SECONDARY" -> GlucoseAlertAudioSlot.CRITICAL_SECONDARY
                else -> null
            }
            if (slot == null) {
                messageState.value = "Unknown alert audio slot"
                return@launch
            }
            val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            if (uri == null) {
                messageState.value = "Invalid alert audio file"
                return@launch
            }
            val displayName = resolveAlertAudioDisplayName(uri)
            val nextSettings = container.settingsStore.settings.first().copyWithAudioSource(
                slot = slot,
                uriString = uri.toString(),
                displayName = displayName
            )
            container.settingsStore.update { nextSettings }
            container.auditLogger.info(
                "glucose_alert_audio_source_updated",
                mapOf(
                    "slot" to slot.name,
                    "clip" to GlucoseAlertAudioProfiles.resolve(nextSettings, slot).label,
                    "uri" to uri.toString()
                )
            )
            messageState.value = "Alert clip file updated"
        }
    }

    fun previewGlucoseAlertAudio(slotRaw: String) {
        viewModelScope.launch {
            val slot = when (slotRaw.uppercase(Locale.US)) {
                "SOFT" -> GlucoseAlertAudioSlot.SOFT
                "CRITICAL_PRIMARY" -> GlucoseAlertAudioSlot.CRITICAL_PRIMARY
                "CRITICAL_SECONDARY" -> GlucoseAlertAudioSlot.CRITICAL_SECONDARY
                else -> null
            }
            if (slot == null) {
                messageState.value = "Unknown alert audio slot"
                return@launch
            }
            val settings = container.settingsStore.settings.first()
            val playback = container.glucoseAlertAudioController.preview(slot, settings)
            container.auditLogger.info(
                "glucose_alert_audio_preview",
                mapOf(
                    "slot" to slot.name,
                    "clip" to playback.clipLabel,
                    "fallbackUsed" to playback.fallbackUsed,
                    "startMs" to playback.startMs,
                    "durationMs" to playback.durationMs
                )
            )
            messageState.value = "Preview: ${playback.clipLabel ?: GlucoseAlertAudioProfiles.displayLabel(settings, slot)}"
        }
    }

    fun stopPreviewGlucoseAlertAudio() {
        viewModelScope.launch {
            val playbackState = container.glucoseAlertAudioController.playbackState.value
            container.glucoseAlertAudioController.stopPreview()
            container.auditLogger.info(
                "glucose_alert_audio_preview_stopped",
                mapOf(
                    "slot" to playbackState.activeSlot?.name,
                    "clip" to playbackState.clipLabel,
                    "wasPreview" to playbackState.isPreview
                )
            )
            messageState.value = "Alert audio preview stopped"
        }
    }

    fun resetRecommendedGlucoseAlertAudio() {
        viewModelScope.launch {
            container.settingsStore.update { current ->
                current.copy(
                    softAlertAudioStartMs = GlucoseAlertAudioProfiles.recommendedStartMs(GlucoseAlertAudioSlot.SOFT),
                    softAlertAudioDurationMs = GlucoseAlertAudioProfiles.recommendedDurationMs(GlucoseAlertAudioSlot.SOFT),
                    softAlertAudioUri = null,
                    softAlertAudioDisplayName = null,
                    criticalAlertAudio1StartMs = GlucoseAlertAudioProfiles.recommendedStartMs(GlucoseAlertAudioSlot.CRITICAL_PRIMARY),
                    criticalAlertAudio1DurationMs = GlucoseAlertAudioProfiles.recommendedDurationMs(GlucoseAlertAudioSlot.CRITICAL_PRIMARY),
                    criticalAlertAudio1Uri = null,
                    criticalAlertAudio1DisplayName = null,
                    criticalAlertAudio2StartMs = GlucoseAlertAudioProfiles.recommendedStartMs(GlucoseAlertAudioSlot.CRITICAL_SECONDARY),
                    criticalAlertAudio2DurationMs = GlucoseAlertAudioProfiles.recommendedDurationMs(GlucoseAlertAudioSlot.CRITICAL_SECONDARY),
                    criticalAlertAudio2Uri = null,
                    criticalAlertAudio2DisplayName = null
                )
            }
            container.auditLogger.info(
                "glucose_alert_audio_profile_reset",
                mapOf(
                    "softClip" to GlucoseAlertAudioProfiles.label(GlucoseAlertAudioSlot.SOFT),
                    "criticalClip1" to GlucoseAlertAudioProfiles.label(GlucoseAlertAudioSlot.CRITICAL_PRIMARY),
                    "criticalClip2" to GlucoseAlertAudioProfiles.label(GlucoseAlertAudioSlot.CRITICAL_SECONDARY)
                )
            )
            messageState.value = "Alert audio reset to recommended clips"
        }
    }

    private fun resolveAlertAudioDisplayName(uri: Uri): String {
        val context = getApplication<Application>().applicationContext
        val resolver = context.contentResolver
        val queriedName = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return queriedName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Custom clip"
    }

    private fun AppSettings.copyWithAudioSource(
        slot: GlucoseAlertAudioSlot,
        uriString: String,
        displayName: String
    ): AppSettings = when (slot) {
        GlucoseAlertAudioSlot.SOFT -> copy(
            softAlertAudioUri = uriString,
            softAlertAudioDisplayName = displayName
        )
        GlucoseAlertAudioSlot.CRITICAL_PRIMARY -> copy(
            criticalAlertAudio1Uri = uriString,
            criticalAlertAudio1DisplayName = displayName
        )
        GlucoseAlertAudioSlot.CRITICAL_SECONDARY -> copy(
            criticalAlertAudio2Uri = uriString,
            criticalAlertAudio2DisplayName = displayName
        )
    }

    fun setNightscoutUrl(url: String) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(nightscoutUrl = url.trim()) }
            messageState.value = "Nightscout URL updated"
        }
    }

    fun setAiApiSettings(apiUrl: String) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(cloudBaseUrl = apiUrl.trim())
            }
            messageState.value = getApplication<Application>().getString(R.string.settings_ai_api_updated)
        }
    }

    fun loadServerAiConnection() {
        viewModelScope.launch {
            container.serverAiConnectionManager.load()
        }
    }

    fun activateServerAiConnection(code: String) {
        viewModelScope.launch {
            container.serverAiConnectionManager.activate(code)
        }
    }

    fun resumeServerAiConnection() {
        viewModelScope.launch {
            container.serverAiConnectionManager.resume()
        }
    }

    fun checkServerAiConnection() {
        viewModelScope.launch {
            container.serverAiConnectionManager.check()
        }
    }

    fun replaceOpenAiCredential(value: String) {
        viewModelScope.launch {
            try {
                container.openAiCredentialProvider.replace(value)
                messageState.value = getApplication<Application>()
                    .getString(R.string.settings_ai_credential_updated)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (fatal: Error) {
                throw fatal
            } catch (_: Exception) {
                messageState.value = getApplication<Application>()
                    .getString(R.string.settings_ai_credential_update_failed)
            }
        }
    }

    fun deleteOpenAiCredential() {
        viewModelScope.launch {
            try {
                container.openAiCredentialProvider.delete()
                messageState.value = getApplication<Application>()
                    .getString(R.string.settings_ai_credential_deleted)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (fatal: Error) {
                throw fatal
            } catch (_: Exception) {
                messageState.value = getApplication<Application>()
                    .getString(R.string.settings_ai_credential_delete_failed)
            }
        }
    }

    fun setClinicalAiProvider(providerId: ClinicalAiProviderId) {
        val current = clinicalAiSettingsUiState.value
        val draft = if (providerId == ClinicalAiProviderId.OPENAI_COMPATIBLE) {
            ClinicalAiSettingsDraft.compatible(
                modelDraft = current.customModelDraft
                    .takeIf { current.selectedProvider == providerId }
                    .orEmpty()
                    .ifEmpty { current.effectiveConfig.modelId },
                endpointDraft = current.endpointDraft
                    .takeIf { current.selectedProvider == providerId }
                    .orEmpty(),
                protocol = current.selectedProtocol
            )
        } else {
            val config = selectClinicalAiNativeConfig(
                providerId = providerId,
                currentConfig = current.effectiveConfig
            )
            ClinicalAiSettingsDraft.bounded(
                providerId = providerId,
                customModelSelected = false,
                modelDraft = config.modelId,
                endpointDraft = "",
                protocol = OpenAiCompatibleProtocol.RESPONSES
            )
        }
        updateClinicalAiDraft(draft)
    }

    fun setClinicalAiModel(modelId: String) {
        val current = clinicalAiSettingsUiState.value
        if (
            current.selectedProvider == ClinicalAiProviderId.OPENAI_COMPATIBLE ||
            current.modelPresets.none { it.id == modelId }
        ) {
            return
        }
        updateClinicalAiDraft(
            ClinicalAiSettingsDraft.bounded(
                providerId = current.selectedProvider,
                customModelSelected = false,
                modelDraft = modelId,
                endpointDraft = "",
                protocol = OpenAiCompatibleProtocol.RESPONSES
            )
        )
    }

    fun setClinicalAiCustomModel(modelId: String) {
        val current = clinicalAiSettingsUiState.value
        val activeDraft = clinicalAiPersistenceState.value.draft
        updateClinicalAiDraft(
            ClinicalAiSettingsDraft.bounded(
                providerId = current.selectedProvider,
                customModelSelected = true,
                modelDraft = modelId,
                endpointDraft = activeDraft
                    ?.takeIf { it.providerId == current.selectedProvider }
                    ?.endpointDraft
                    ?: current.endpointDraft,
                protocol = activeDraft
                    ?.takeIf { it.providerId == current.selectedProvider }
                    ?.protocol
                    ?: current.selectedProtocol
            )
        )
    }

    fun setClinicalAiEndpoint(endpoint: String) {
        val current = clinicalAiSettingsUiState.value
        if (current.selectedProvider != ClinicalAiProviderId.OPENAI_COMPATIBLE) {
            return
        }
        val activeDraft = clinicalAiPersistenceState.value.draft
        updateClinicalAiDraft(
            ClinicalAiSettingsDraft.compatible(
                modelDraft = activeDraft
                    ?.takeIf {
                        it.providerId == ClinicalAiProviderId.OPENAI_COMPATIBLE
                    }
                    ?.modelDraft
                    ?: current.customModelDraft,
                endpointDraft = endpoint,
                protocol = activeDraft
                    ?.takeIf {
                        it.providerId == ClinicalAiProviderId.OPENAI_COMPATIBLE
                    }
                    ?.protocol
                    ?: current.selectedProtocol
            )
        )
    }

    fun setClinicalAiProtocol(protocol: OpenAiCompatibleProtocol) {
        val current = clinicalAiSettingsUiState.value
        if (current.selectedProvider != ClinicalAiProviderId.OPENAI_COMPATIBLE) {
            return
        }
        val activeDraft = clinicalAiPersistenceState.value.draft
        updateClinicalAiDraft(
            ClinicalAiSettingsDraft.compatible(
                modelDraft = activeDraft
                    ?.takeIf {
                        it.providerId == ClinicalAiProviderId.OPENAI_COMPATIBLE
                    }
                    ?.modelDraft
                    ?: current.customModelDraft,
                endpointDraft = activeDraft
                    ?.takeIf {
                        it.providerId == ClinicalAiProviderId.OPENAI_COMPATIBLE
                    }
                    ?.endpointDraft
                    ?: current.endpointDraft,
                protocol = protocol
            )
        )
    }

    override fun setAutomaticEventAiAnalysisEnabled(enabled: Boolean) {
        automaticEventAiAnalysisSettingCommand.setAutomaticEventAiAnalysisEnabled(enabled)
    }

    fun replaceClinicalAiCredential(
        providerId: ClinicalAiProviderId,
        value: String
    ) {
        viewModelScope.launch {
            try {
                container.clinicalAiProviderManager.replace(providerId, value)
                messageState.value = getApplication<Application>().getString(
                    R.string.settings_clinical_ai_credential_updated
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (fatal: Error) {
                throw fatal
            } catch (_: Exception) {
                messageState.value = getApplication<Application>().getString(
                    R.string.settings_clinical_ai_credential_update_failed
                )
            }
        }
    }

    fun deleteClinicalAiCredential(providerId: ClinicalAiProviderId) {
        viewModelScope.launch {
            try {
                container.clinicalAiProviderManager.delete(providerId)
                messageState.value = getApplication<Application>().getString(
                    R.string.settings_clinical_ai_credential_deleted
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (fatal: Error) {
                throw fatal
            } catch (_: Exception) {
                messageState.value = getApplication<Application>().getString(
                    R.string.settings_clinical_ai_credential_delete_failed
                )
            }
        }
    }

    fun testClinicalAiConnection() {
        val current = clinicalAiSettingsUiState.value
        if (
            clinicalAiConnectionTestState.value.phase ==
            ClinicalAiConnectionTestPhaseUi.RUNNING ||
            !current.canTestConnection
        ) {
            return
        }
        val resolution = resolveClinicalAiSettings(
            persistedState = ClinicalAiConfigState.Valid(current.effectiveConfig),
            draft = clinicalAiPersistenceState.value.draft
        )
        val config = resolution.persistableConfig ?: return
        if (
            clinicalAiPersistenceState.value.pending ||
            clinicalAiPersistenceState.value.saveFailed
        ) {
            return
        }
        val token = clinicalAiConnectionTestGuard.begin(config)
        clinicalAiConnectionTestState.value = ClinicalAiConnectionTestUiState(
            phase = ClinicalAiConnectionTestPhaseUi.RUNNING,
            providerId = config.providerId,
            modelId = config.modelId
        )
        clinicalAiConnectionTestJob = viewModelScope.launch {
            try {
                val result =
                    container.clinicalAiProviderManager.testConnection(config)
                clinicalAiConnectionTestGuard.publishIfCurrent(
                    token = token,
                    currentConfig = currentClinicalAiConnectionConfig(),
                    value = ClinicalAiConnectionTestUiState(
                        phase = if (result.successful) {
                            ClinicalAiConnectionTestPhaseUi.SUCCESS
                        } else {
                            ClinicalAiConnectionTestPhaseUi.FAILURE
                        },
                        providerId = config.providerId,
                        modelId = config.modelId
                    ),
                    publish = { clinicalAiConnectionTestState.value = it }
                )
            } catch (cancellation: CancellationException) {
                clinicalAiConnectionTestGuard.publishIfCurrent(
                    token = token,
                    currentConfig = currentClinicalAiConnectionConfig(),
                    value = ClinicalAiConnectionTestUiState(),
                    publish = { clinicalAiConnectionTestState.value = it }
                )
                throw cancellation
            } catch (fatal: Error) {
                clinicalAiConnectionTestGuard.publishIfCurrent(
                    token = token,
                    currentConfig = currentClinicalAiConnectionConfig(),
                    value = ClinicalAiConnectionTestUiState(),
                    publish = { clinicalAiConnectionTestState.value = it }
                )
                throw fatal
            } catch (_: Exception) {
                clinicalAiConnectionTestGuard.publishIfCurrent(
                    token = token,
                    currentConfig = currentClinicalAiConnectionConfig(),
                    value = ClinicalAiConnectionTestUiState(
                        phase = ClinicalAiConnectionTestPhaseUi.FAILURE,
                        providerId = config.providerId,
                        modelId = config.modelId
                    ),
                    publish = { clinicalAiConnectionTestState.value = it }
                )
            }
        }
    }

    private fun updateClinicalAiDraft(draft: ClinicalAiSettingsDraft) {
        val currentConfig = clinicalAiSettingsUiState.value.effectiveConfig
        val resolution = resolveClinicalAiSettings(
            persistedState = ClinicalAiConfigState.Valid(currentConfig),
            draft = draft
        )
        val config = resolution.persistableConfig
        val started = clinicalAiPersistenceState.value.begin(
            draft = draft,
            persistable = config != null
        )
        clinicalAiPersistenceState.value = started
        invalidateClinicalAiConnectionTest()
        if (config == null) return

        viewModelScope.launch {
            val result = clinicalAiPersistenceCoordinator.persist(
                version = started.version,
                config = config,
                currentVersion = { clinicalAiPersistenceState.value.version },
                write = container.settingsStore::setClinicalAiConfig,
                persistedState = {
                    container.settingsStore.settings
                        .map { it.clinicalAiConfigState }
                        .first { persisted ->
                            when (persisted) {
                                is ClinicalAiConfigState.UnconfiguredDefault ->
                                    persisted.config == config
                                is ClinicalAiConfigState.Valid ->
                                    persisted.config == config
                                is ClinicalAiConfigState.Invalid -> false
                            }
                        }
                }
            )
            val current = clinicalAiPersistenceState.value
            clinicalAiPersistenceState.value = current.complete(
                version = started.version,
                result = result
            )
            if (
                result == ClinicalAiPersistenceResult.FAILED &&
                current.version == started.version
            ) {
                messageState.value = getApplication<Application>().getString(
                    R.string.settings_clinical_ai_config_save_failed
                )
            }
        }
    }

    private fun invalidateClinicalAiConnectionTest() {
        clinicalAiConnectionTestGuard.invalidate()
        clinicalAiConnectionTestJob?.cancel()
        clinicalAiConnectionTestJob = null
        clinicalAiConnectionTestState.value = ClinicalAiConnectionTestUiState()
    }

    private fun currentClinicalAiConnectionConfig(): ClinicalAiProviderConfig? {
        val persistence = clinicalAiPersistenceState.value
        if (persistence.pending || persistence.saveFailed) return null
        val current = clinicalAiSettingsUiState.value
        return current.effectiveConfig.takeIf {
            current.localValidationError == null
        }
    }

    private fun clinicalAiProviderOptions(): List<ClinicalAiProviderOptionUi> {
        val application = getApplication<Application>()
        return listOf(
            ClinicalAiProviderOptionUi(
                ClinicalAiProviderId.OPENAI,
                application.getString(R.string.settings_clinical_ai_provider_openai)
            ),
            ClinicalAiProviderOptionUi(
                ClinicalAiProviderId.ANTHROPIC,
                application.getString(R.string.settings_clinical_ai_provider_anthropic)
            ),
            ClinicalAiProviderOptionUi(
                ClinicalAiProviderId.GEMINI,
                application.getString(R.string.settings_clinical_ai_provider_gemini)
            ),
            ClinicalAiProviderOptionUi(
                ClinicalAiProviderId.OPENAI_COMPATIBLE,
                application.getString(R.string.settings_clinical_ai_provider_compatible)
            )
        )
    }

    private fun clinicalAiProtocolOptions(): List<ClinicalAiProtocolOptionUi> {
        val application = getApplication<Application>()
        return listOf(
            ClinicalAiProtocolOptionUi(
                OpenAiCompatibleProtocol.RESPONSES,
                application.getString(R.string.settings_clinical_ai_protocol_responses)
            ),
            ClinicalAiProtocolOptionUi(
                OpenAiCompatibleProtocol.CHAT_COMPLETIONS,
                application.getString(
                    R.string.settings_clinical_ai_protocol_chat_completions
                )
            )
        )
    }

    private fun clinicalAiLabels(): ClinicalAiSettingsLabels {
        val application = getApplication<Application>()
        return ClinicalAiSettingsLabels(
            sectionTitle = application.getString(R.string.settings_clinical_ai_section),
            provider = application.getString(R.string.settings_clinical_ai_provider),
            model = application.getString(R.string.settings_clinical_ai_model),
            customModel = application.getString(
                R.string.settings_clinical_ai_custom_model
            ),
            customModelInput = application.getString(
                R.string.settings_clinical_ai_custom_model_input
            ),
            endpoint = application.getString(R.string.settings_clinical_ai_endpoint),
            protocol = application.getString(R.string.settings_clinical_ai_protocol),
            automaticCauseAnalysis = application.getString(
                R.string.settings_clinical_ai_automatic_cause_analysis
            ),
            credential = application.getString(R.string.settings_clinical_ai_credential),
            credentialAddTitle = application.getString(
                R.string.settings_clinical_ai_credential_add_title
            ),
            credentialReplaceTitle = application.getString(
                R.string.settings_clinical_ai_credential_replace_title
            ),
            credentialDeleteTitle = application.getString(
                R.string.settings_clinical_ai_credential_delete_title
            ),
            credentialDeleteMessage = application.getString(
                R.string.settings_clinical_ai_credential_delete_message
            ),
            credentialResetTitle = application.getString(
                R.string.settings_clinical_ai_credential_reset_title
            ),
            credentialResetMessage = application.getString(
                R.string.settings_clinical_ai_credential_reset_message
            ),
            testConnection = application.getString(
                R.string.settings_clinical_ai_test_connection
            ),
            testingConnection = application.getString(
                R.string.settings_clinical_ai_testing_connection
            ),
            invalidEndpoint = application.getString(
                R.string.settings_clinical_ai_invalid_endpoint
            ),
            invalidModel = application.getString(
                R.string.settings_clinical_ai_invalid_model
            ),
            invalidConfiguration = application.getString(
                R.string.settings_clinical_ai_invalid_configuration
            ),
            configSaveFailed = application.getString(
                R.string.settings_clinical_ai_config_save_failed
            ),
            connectionSuccess = application.getString(
                R.string.settings_clinical_ai_connection_success
            ),
            connectionFailure = application.getString(
                R.string.settings_clinical_ai_connection_failure
            )
        )
    }

    fun setUiStyle(styleRaw: String) {
        viewModelScope.launch {
            val style = UiStyle.fromRaw(styleRaw)
            container.settingsStore.update { it.copy(uiStyle = style) }
            messageState.value = "UI style updated: ${style.name}"
        }
    }

    fun setEnergyProfileEnabled(enabled: Boolean) {
        updateEnergyProfileSettings { current -> current.copy(enabled = enabled) }
    }

    fun saveMealPortionSettings(value: io.aaps.copilot.domain.nutrition.MealPortionSettings) {
        viewModelScope.launch {
            try {
                container.settingsStore.setMealPortionSettings(value)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                messageState.value = getApplication<Application>().getString(R.string.meal_portions_save_failed)
            }
        }
    }

    fun saveEnergyProfileUserProfile(draft: io.aaps.copilot.ui.foundation.screens.UserProfileDraftUi) {
        updateEnergyProfileSettings { current ->
            current.copy(
                birthDateEpochDay = draft.birthDateEpochDay,
                physiologicalSex = draft.physiologicalSex,
                heightCm = draft.heightCm,
                weightKg = draft.weightKg
            )
        }
    }

    fun saveEnergyProfileFoodSettings(draft: io.aaps.copilot.ui.foundation.screens.FoodProfileSettingsUi) {
        updateEnergyProfileSettings { current ->
            current.copy(
                foodProfileMode = draft.mode,
                manualFoodProfile = draft.manualProfile
            )
        }
    }

    fun saveEnergyProfileActivitySettings(draft: io.aaps.copilot.ui.foundation.screens.ActivityProfileSettingsUi) {
        updateEnergyProfileSettings { current ->
            current.copy(
                activityProfileMode = draft.mode,
                manualActivityProfile = draft.manualProfile,
                forecastActivityInfluenceEnabled = draft.forecastInfluenceEnabled
            )
        }
    }

    fun saveEnergyGoalSettings(draft: io.aaps.copilot.ui.foundation.screens.EnergyGoalSettingsUi) {
        updateEnergyProfileSettings { current ->
            current.copy(
                calorieGoalMode = draft.mode,
                manualCalorieTargetKcal = draft.manualTargetKcal,
                shareProfileWithAi = draft.shareProfileWithAi
            )
        }
    }

    fun savePlannedActivity(event: io.aaps.copilot.ui.foundation.screens.PlannedActivityEventUi) {
        viewModelScope.launch {
            val current = energyProfileSettingsState.value.plannedEvents
            if (!PlannedActivityInvalidationPolicy.saveChangesDurableState(current, event)) {
                energyProfileValidationMessage.value = null
                messageState.value = "Activity schedule saved"
                return@launch
            }
            val proposed = (current.filterNot { it.eventId == event.eventId } + event)
                .mapNotNull { it.toPlannedActivityScheduleOrNull() }
            if (proposed.size != current.filterNot { it.eventId == event.eventId }.size + 1) {
                energyProfileValidationMessage.value = "Invalid activity schedule"
                return@launch
            }
            when (val result = persistPlannedActivityClinicalInput(
                persistence = { container.energyProfileRepository.savePlannedActivitySchedule(proposed) },
                onClinicalInputPersisted = {
                    container.clinicalInputInvalidationCoordinator.invalidatePersistedInput(
                        ClinicalInputInvalidationSource.PLANNED_ACTIVITY
                    )
                }
            )) {
                is io.aaps.copilot.domain.profile.PlannedActivityScheduleSaveResult.Saved -> {
                    energyProfileValidationMessage.value = null
                    messageState.value = "Activity schedule saved"
                }
                is io.aaps.copilot.domain.profile.PlannedActivityScheduleSaveResult.Invalid -> {
                    energyProfileValidationMessage.value = when (result.validation) {
                        is io.aaps.copilot.domain.profile.ScheduleValidation.Overlap -> "Events overlap"
                        is io.aaps.copilot.domain.profile.ScheduleValidation.Invalid -> "Invalid activity schedule"
                        io.aaps.copilot.domain.profile.ScheduleValidation.Valid -> "Invalid activity schedule"
                    }
                    messageState.value = energyProfileValidationMessage.value
                }
            }
        }
    }

    internal fun saveManualEvent(
        event: CompensationEvent,
        expectedRevision: Long?,
        onCompleted: (ContextEventCommandUiDisposition) -> Unit
    ) {
        viewModelScope.launch {
            val sex = container.settingsStore.settings.first().energyProfile.physiologicalSex
            if (!CompensationEventProfilePolicy.canCreate(event.type, sex)) {
                onCompleted(publishContextEventResult(ContextEventCommandAction.SAVE, ContextEventCommandResult.REJECTED))
                return@launch
            }
            if (manualCompensationEventRoute(event.type) != ManualCompensationEventRoute.CONTEXT) {
                onCompleted(publishContextEventResult(ContextEventCommandAction.SAVE, ContextEventCommandResult.REJECTED))
                return@launch
            }
            val result = container.contextEventSyncCoordinator.save(event, expectedRevision)
            onCompleted(publishContextEventResult(ContextEventCommandAction.SAVE, result))
        }
    }

    fun closeManualEvent(event: CompensationEvent) {
        if (event.source != EventSource.USER || !CompensationEventManualPolicy.isContextOnly(event.type)) return
        viewModelScope.launch {
            val result = container.contextEventSyncCoordinator.close(event.localId, event.revision)
            publishContextEventResult(ContextEventCommandAction.CLOSE, result)
        }
    }

    fun deleteManualEvent(event: CompensationEvent) {
        if (event.source != EventSource.USER || !CompensationEventManualPolicy.isContextOnly(event.type)) return
        viewModelScope.launch {
            val result = container.contextEventSyncCoordinator.delete(event.localId, event.revision)
            publishContextEventResult(ContextEventCommandAction.DELETE, result)
        }
    }

    private fun publishContextEventResult(
        action: ContextEventCommandAction,
        result: ContextEventCommandResult
    ): ContextEventCommandUiDisposition {
        val disposition = ContextEventCommandUiPolicy.resolve(action, result)
        messageState.value = getApplication<Application>().getString(disposition.messageRes)
        return disposition
    }

    fun deletePlannedActivity(eventId: String) {
        viewModelScope.launch {
            val current = energyProfileSettingsState.value.plannedEvents
            if (!PlannedActivityInvalidationPolicy.deleteChangesDurableState(current, eventId)) {
                return@launch
            }
            val remaining = current
                .filterNot { it.eventId == eventId }
                .mapNotNull { it.toPlannedActivityScheduleOrNull() }
            when (persistPlannedActivityClinicalInput(
                persistence = { container.energyProfileRepository.savePlannedActivitySchedule(remaining) },
                onClinicalInputPersisted = {
                    container.clinicalInputInvalidationCoordinator.invalidatePersistedInput(
                        ClinicalInputInvalidationSource.PLANNED_ACTIVITY
                    )
                }
            )) {
                is io.aaps.copilot.domain.profile.PlannedActivityScheduleSaveResult.Saved -> {
                    energyProfileValidationMessage.value = null
                    messageState.value = "Activity schedule removed"
                }
                is io.aaps.copilot.domain.profile.PlannedActivityScheduleSaveResult.Invalid -> {
                    energyProfileValidationMessage.value = "Invalid activity schedule"
                    messageState.value = energyProfileValidationMessage.value
                }
            }
        }
    }

    private fun updateEnergyProfileSettings(
        transform: (EnergyProfileSettings) -> EnergyProfileSettings
    ) {
        viewModelScope.launch {
            val current = container.settingsStore.settings.first().energyProfile
            when (
                val decision = evaluateEnergyProfileSettingsUpdate(
                    current = current,
                    candidate = transform(current),
                    today = LocalDate.now()
                )
            ) {
                is EnergyProfileSettingsUpdate.Persist -> {
                    try {
                        if (current.physiologicalSex != decision.settings.physiologicalSex) {
                            sensitivitySourceApplyCoordinator.run(
                                metricLabel = "PHYSIOLOGICAL_SEX",
                                requestedSource = decision.settings.physiologicalSex.name
                            ) {
                                container.automationRepository.applySensitivitySettings { settings ->
                                    settings.copy(
                                        energyProfile = settings.energyProfile.copy(
                                            physiologicalSex = decision.settings.physiologicalSex
                                        )
                                    )
                                }
                            }
                        }
                        container.settingsStore.setEnergyProfileSettings(decision.settings)
                        if (decision.pediatricGoalReset) {
                            messageState.value = getApplication<Application>().getString(
                                R.string.energy_activity_pediatric_goal_reset
                            )
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        messageState.value = "Energy profile update failed: " +
                            (error.message ?: "runtime unavailable")
                    }
                }
                is EnergyProfileSettingsUpdate.Reject -> {
                    messageState.value = decision.violations
                        .sortedBy(ProfileViolation::name)
                        .joinToString(separator = " ") { violation ->
                            getApplication<Application>().getString(
                                energyProfilePolicyErrorRes(violation)
                            )
                        }
                }
            }
        }
    }

    fun setLocalNightscoutEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = container.settingsStore.settings.first()
            val safePort = current.localNightscoutPort.coerceIn(1_024, 65_535)
            val effectiveEnabled = LocalNightscoutUpgradeMigrationGate.canEnable(
                requestedEnabled = enabled,
                migrationAcknowledged = current.localNightscoutLegacyMigrationAcknowledged
            )
            container.settingsStore.update {
                it.copy(
                    localNightscoutEnabled = effectiveEnabled,
                    localNightscoutPort = safePort
                )
            }
            LocalNightscoutServiceController.reconcile(getApplication(), effectiveEnabled)
            if (enabled && !effectiveEnabled) {
                LocalNightscoutRuntimeState.setup(
                    safePort,
                    LocalNightscoutRuntimeState.value.caFingerprint,
                    LocalNightscoutRuntimeReason.LEGACY_MIGRATION_ACK_REQUIRED
                )
            }
            messageState.value = if (enabled && !effectiveEnabled) {
                "Remove the legacy CA and old AAPS credential, then acknowledge the Local Nightscout migration before enabling"
            } else if (effectiveEnabled) {
                "Local Nightscout requested on exact port $safePort; wait for SETUP/READY"
            } else {
                "Local Nightscout disabled"
            }
        }
    }

    fun setLocalNightscoutLegacyMigrationAcknowledged(acknowledged: Boolean) {
        viewModelScope.launch {
            val current = container.settingsStore.settings.first()
            container.settingsStore.update {
                it.copy(
                    localNightscoutLegacyMigrationAcknowledged = acknowledged,
                    localNightscoutEnabled = if (acknowledged) {
                        it.localNightscoutEnabled
                    } else {
                        false
                    }
                )
            }
            if (!acknowledged) {
                LocalNightscoutServiceController.stop(
                    getApplication(),
                    LocalNightscoutRuntimeReason.LEGACY_MIGRATION_ACK_REQUIRED
                )
            }
            messageState.value = if (acknowledged) {
                "Legacy Local Nightscout CA and AAPS credential removal acknowledged; install/export and enablement are now available"
            } else {
                "Local Nightscout migration acknowledgment cleared; integration disabled"
            }
        }
    }

    fun setInsulinProfile(profileRaw: String) {
        viewModelScope.launch {
            val normalized = InsulinActionProfileId.fromRaw(profileRaw).name
            container.settingsStore.update { it.copy(insulinProfileId = normalized) }
            triggerReactiveAutomationAsync()
            messageState.value = "Insulin profile set to $normalized"
        }
    }

    fun setUamExportUiMode(modeRaw: String) {
        val mode = UamExportUiModeCommand.parse(modeRaw) ?: return
        viewModelScope.launch {
            container.settingsStore.update { current ->
                UamExportUiModeCommand.apply(current, mode.name) ?: current
            }
            triggerReactiveAutomationAsync()
            messageState.value = "UAM mode updated: ${mode.name}"
        }
    }

    fun setUamRuntimeConfig(
        enableInference: Boolean,
        enableBoost: Boolean
    ) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    enableUamInference = enableInference,
                    enableUamBoost = enableBoost
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "UAM runtime config updated"
        }
    }

    fun setUamInferenceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(enableUamInference = enabled) }
            triggerReactiveAutomationAsync()
        }
    }

    fun setUamBoostEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(enableUamBoost = enabled) }
            triggerReactiveAutomationAsync()
        }
    }

    fun setUamAutoExportCap(enabled: Boolean, grams: Int) {
        viewModelScope.launch {
            val safeGrams = grams.coerceIn(1, UamExportPolicy.MAX_INCREMENT_G.toInt())
            container.settingsStore.update {
                it.copy(
                    enableUamAutoExportCap = enabled,
                    uamAutoExportCapGrams = safeGrams
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = if (enabled) {
                "UAM automatic export cap set to ${safeGrams} g"
            } else {
                "UAM automatic export cap disabled"
            }
        }
    }

    fun setUamInferenceTuning(
        minSnackG: Int,
        maxSnackG: Int,
        snackStepG: Int,
        backdateMinutes: Int,
        exportMinIntervalMin: Int,
        exportMaxBackdateMin: Int
    ) {
        viewModelScope.launch {
            val safeMin = minSnackG.coerceIn(5, 80)
            val safeMax = maxSnackG.coerceIn(safeMin, 120)
            val safeStep = snackStepG.coerceIn(1, 20)
            container.settingsStore.update {
                it.copy(
                    uamMinSnackG = safeMin,
                    uamMaxSnackG = safeMax,
                    uamSnackStepG = safeStep,
                    uamBackdateMinutesDefault = backdateMinutes.coerceIn(5, 120),
                    uamExportMinIntervalMin = exportMinIntervalMin.coerceIn(5, 60),
                    uamExportMaxBackdateMin = exportMaxBackdateMin.coerceIn(30, 360)
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "UAM tuning updated"
        }
    }

    fun setUamSnackConfig(
        minSnackG: Int,
        maxSnackG: Int,
        snackStepG: Int
    ) {
        viewModelScope.launch {
            val current = container.settingsStore.settings.first()
            setUamInferenceTuning(
                minSnackG = minSnackG,
                maxSnackG = maxSnackG,
                snackStepG = snackStepG,
                backdateMinutes = current.uamBackdateMinutesDefault,
                exportMinIntervalMin = current.uamExportMinIntervalMin,
                exportMaxBackdateMin = current.uamExportMaxBackdateMin
            )
        }
    }

    fun setPostHypoThreshold(mmol: Double) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(postHypoThresholdMmol = mmol.coerceIn(4.0, 10.0))
            }
            messageState.value = "Post-hypo threshold updated"
        }
    }

    fun setPostHypoTarget(mmol: Double) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(postHypoTargetMmol = mmol.coerceIn(4.0, 10.0))
            }
            messageState.value = "Post-hypo target updated"
        }
    }

    fun setRetentionDays(days: Int) {
        val safeDays = days.coerceIn(30, 730)
        launchSensitivitySettingsChange(
            operationLabel = "RETENTION",
            requestedValue = safeDays.toString(),
            successMessage = "Retention days updated",
            updater = { it.copy(analyticsLookbackDays = safeDays) },
            afterAccepted = ::recalculateAnalyticsAsync
        )
    }

    fun setCircadianPatternsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(circadianPatternsEnabled = enabled) }
            recalculateAnalyticsAsync()
            triggerReactiveAutomationAsync()
            messageState.value = if (enabled) {
                "Circadian patterns enabled"
            } else {
                "Circadian patterns disabled"
            }
        }
    }

    fun setCircadianLookback(stableDays: Int, recencyDays: Int) {
        viewModelScope.launch {
            val safeStable = when {
                stableDays >= 14 -> 14
                stableDays >= 10 -> 10
                else -> 7
            }
            val safeRecency = recencyDays.coerceIn(3, 7)
            container.settingsStore.update {
                it.copy(
                    circadianStableLookbackDays = safeStable,
                    circadianRecencyLookbackDays = safeRecency
                )
            }
            recalculateAnalyticsAsync()
            triggerReactiveAutomationAsync()
            messageState.value = "Circadian lookback updated"
        }
    }

    fun setCircadianWeekendSplit(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(circadianUseWeekendSplit = enabled) }
            recalculateAnalyticsAsync()
            triggerReactiveAutomationAsync()
            messageState.value = if (enabled) {
                "Circadian weekday/weekend split enabled"
            } else {
                "Circadian weekday/weekend split disabled"
            }
        }
    }

    fun setCircadianReplayResidualBias(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(circadianUseReplayResidualBias = enabled) }
            recalculateAnalyticsAsync()
            triggerReactiveAutomationAsync()
            messageState.value = if (enabled) {
                "Circadian replay residual bias enabled"
            } else {
                "Circadian replay residual bias disabled"
            }
        }
    }

    fun setCircadianForecastWeights(weight30: Double, weight60: Double) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    circadianForecastWeight30 = weight30.coerceIn(0.0, 0.45),
                    circadianForecastWeight60 = weight60.coerceIn(0.0, 0.55)
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "Circadian forecast weights updated"
        }
    }

    fun setSensorLagCorrectionMode(modeRaw: String) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    sensorLagCorrectionMode = io.aaps.copilot.config.SensorLagCorrectionMode.fromRaw(modeRaw)
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "Sensor lag correction mode updated"
        }
    }

    fun setTargetManagerTiming(setting: TargetManagerTimingSetting, minutes: Int) {
        viewModelScope.launch {
            try {
                container.settingsStore.update { it.withTargetManagerTiming(setting, minutes) }
                triggerReactiveAutomationAsync()
                messageState.value = getApplication<Application>().getString(R.string.settings_target_timing_saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                messageState.value = getApplication<Application>().getString(R.string.settings_target_timing_failed)
            }
        }
    }

    fun setTargetManagerMode(modeRaw: String) {
        val mode = runCatching {
            TargetManagerMode.valueOf(modeRaw.trim().uppercase(Locale.US))
        }.getOrNull() ?: return
        viewModelScope.launch {
            val previous = container.settingsStore.settings.first().targetManagerMode
            container.settingsStore.setTargetManagerModeManually(mode)
            container.auditLogger.info(
                "target_manager_mode_changed",
                mapOf(
                    "source" to "manual_ui",
                    "previousMode" to previous.name,
                    "mode" to mode.name,
                    "manualOverride" to true
                )
            )
            triggerReactiveAutomationAsync()
            messageState.value = "Target Manager: ${mode.name}"
        }
    }

    fun setTargetManagerAutomaticMode() {
        viewModelScope.launch {
            val previous = container.settingsStore.settings.first().targetManagerMode
            container.settingsStore.enableAutomaticTargetManagerMode()
            container.auditLogger.info(
                "target_manager_mode_changed",
                mapOf(
                    "source" to "manual_ui",
                    "previousMode" to previous.name,
                    "mode" to TargetManagerMode.SHADOW.name,
                    "manualOverride" to false
                )
            )
            triggerReactiveAutomationAsync()
            messageState.value = "Target Manager: AUTO"
        }
    }

    fun setTargetManagerCopilotPriorityEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val previous = container.settingsStore.settings.first()
            container.settingsStore.setTargetManagerCopilotPriorityEnabled(enabled)
            val saved = container.settingsStore.settings.first()
            container.auditLogger.info(
                "target_manager_copilot_priority_changed",
                mapOf(
                    "source" to "manual_ui",
                    "previousEnabled" to previous.targetManagerCopilotPriorityEnabled,
                    "enabled" to saved.targetManagerCopilotPriorityEnabled,
                    "previousPolicyRevision" to previous.targetManagerPolicyRevision,
                    "policyRevision" to saved.targetManagerPolicyRevision
                )
            )
            triggerReactiveAutomationAsync()
            messageState.value = if (saved.targetManagerCopilotPriorityEnabled) {
                "Target Manager Copilot priority enabled"
            } else {
                "Target Manager Copilot priority disabled"
            }
        }
    }

    fun setIsfRuntimeSourcePreference(modeRaw: String) {
        val preference = SensitivitySourcePreference.fromUserInput(modeRaw) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            publishSensitivitySourceChange(
                metric = SensitivityMetricKind.ISF,
                requestedSource = preference
            )
        }
    }

    fun setCrRuntimeSourcePreference(modeRaw: String) {
        val preference = SensitivitySourcePreference.fromUserInput(modeRaw) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            publishSensitivitySourceChange(
                metric = SensitivityMetricKind.CR,
                requestedSource = preference
            )
        }
    }

    private suspend fun publishSensitivitySourceChange(
        metric: SensitivityMetricKind,
        requestedSource: SensitivitySourcePreference
    ) {
        val metricLabel = metric.name
        try {
            val snapshot = sensitivitySourceApplyCoordinator.run(metricLabel, requestedSource.name) {
                applySensitivitySourceChange(
                    metric = metric,
                    requestedSource = requestedSource,
                    applyAtomically = container.automationRepository::applySensitivitySettings
                )
            }
            val decision = if (metric == SensitivityMetricKind.ISF) snapshot.isf else snapshot.cr
            messageState.value = "$metricLabel: ${decision.effective} (${decision.resolved.name})"
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val detail = error.message ?: "runtime unavailable"
            messageState.value = "$metricLabel source update failed: $detail"
        }
    }

    private fun launchSensitivitySettingsChange(
        operationLabel: String,
        requestedValue: String,
        successMessage: String,
        updater: (AppSettings) -> AppSettings,
        afterAccepted: suspend () -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sensitivitySourceApplyCoordinator.run(operationLabel, requestedValue) {
                    container.automationRepository.applySensitivitySettings(updater)
                }
                afterAccepted()
                messageState.value = successMessage
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                messageState.value = "$operationLabel update failed: ${error.message ?: "runtime unavailable"}"
            }
        }
    }

    fun setIsfCrShadowMode(enabled: Boolean) {
        launchSensitivitySettingsChange(
            operationLabel = "SHADOW_MODE",
            requestedValue = enabled.toString(),
            successMessage = if (enabled) {
                "ISF/CR engine set to SHADOW mode"
            } else {
                "ISF/CR engine set to ACTIVE mode"
            },
            updater = { it.copy(isfCrShadowMode = enabled) }
        )
    }

    fun setIsfCrConfidenceThreshold(value: Double) {
        val normalized = value.coerceIn(0.2, 0.95)
        launchSensitivitySettingsChange(
            operationLabel = "CONFIDENCE_THRESHOLD",
            requestedValue = normalized.toString(),
            successMessage = "ISF/CR confidence threshold updated",
            updater = { it.copy(isfCrConfidenceThreshold = normalized) }
        )
    }

    fun setIsfCrUseActivity(enabled: Boolean) {
        launchSensitivitySettingsChange(
            operationLabel = "ACTIVITY_FACTOR",
            requestedValue = enabled.toString(),
            successMessage = if (enabled) {
                "ISF/CR activity factor enabled"
            } else {
                "ISF/CR activity factor disabled"
            },
            updater = { it.copy(isfCrUseActivity = enabled) }
        )
    }

    fun setIsfCrUseManualTags(enabled: Boolean) {
        launchSensitivitySettingsChange(
            operationLabel = "MANUAL_TAGS",
            requestedValue = enabled.toString(),
            successMessage = if (enabled) {
                "ISF/CR manual tags enabled"
            } else {
                "ISF/CR manual tags disabled"
            },
            updater = { it.copy(isfCrUseManualTags = enabled) }
        )
    }

    fun setIsfCrMinEvidencePerHour(minIsfEvidence: Int, minCrEvidence: Int) {
        val normalizedIsf = minIsfEvidence.coerceIn(0, 12)
        val normalizedCr = minCrEvidence.coerceIn(0, 12)
        launchSensitivitySettingsChange(
            operationLabel = "MIN_EVIDENCE",
            requestedValue = "$normalizedIsf:$normalizedCr",
            successMessage = "ISF/CR hourly evidence minimums updated",
            updater = {
                it.copy(
                    isfCrMinIsfEvidencePerHour = normalizedIsf,
                    isfCrMinCrEvidencePerHour = normalizedCr
                )
            }
        )
    }

    fun setIsfCrCrIntegrityGateSettings(
        maxGapMinutes: Int,
        maxSensorBlockedRatePct: Double,
        maxUamAmbiguityRatePct: Double
    ) {
        val normalizedGap = maxGapMinutes.coerceIn(10, 60)
        val normalizedBlocked = maxSensorBlockedRatePct.coerceIn(0.0, 100.0)
        val normalizedUam = maxUamAmbiguityRatePct.coerceIn(0.0, 100.0)
        launchSensitivitySettingsChange(
            operationLabel = "CR_INTEGRITY_GATES",
            requestedValue = "$normalizedGap:$normalizedBlocked:$normalizedUam",
            successMessage = "ISF/CR CR-window integrity thresholds updated",
            updater = {
                it.copy(
                    isfCrCrMaxGapMinutes = normalizedGap,
                    isfCrCrMaxSensorBlockedRatePct = normalizedBlocked,
                    isfCrCrMaxUamAmbiguityRatePct = normalizedUam
                )
            }
        )
    }

    fun setIsfCrAutoActivationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(isfCrAutoActivationEnabled = enabled) }
            triggerReactiveAutomationAsync()
            messageState.value = if (enabled) {
                "ISF/CR shadow auto-activation enabled"
            } else {
                "ISF/CR shadow auto-activation disabled"
            }
        }
    }

    fun setIsfCrAutoActivationLookbackHours(hours: Int) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(isfCrAutoActivationLookbackHours = hours.coerceIn(6, 72)) }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR auto-activation lookback updated"
        }
    }

    fun setIsfCrAutoActivationMinSamples(samples: Int) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(isfCrAutoActivationMinSamples = samples.coerceIn(12, 288)) }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR auto-activation min samples updated"
        }
    }

    fun setIsfCrAutoActivationMinMeanConfidence(value: Double) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(isfCrAutoActivationMinMeanConfidence = value.coerceIn(0.2, 0.95))
            }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR auto-activation confidence target updated"
        }
    }

    fun setIsfCrAutoActivationMaxMeanAbsDeltaPct(
        isfPct: Double,
        crPct: Double
    ) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    isfCrAutoActivationMaxMeanAbsIsfDeltaPct = isfPct.coerceIn(5.0, 100.0),
                    isfCrAutoActivationMaxMeanAbsCrDeltaPct = crPct.coerceIn(5.0, 100.0)
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR auto-activation delta bounds updated"
        }
    }

    fun setIsfCrAutoActivationSensorThresholds(
        minSensorQualityScore: Double,
        minSensorFactor: Double,
        maxWearConfidencePenalty: Double,
        maxSensorAgeHighRatePct: Double,
        maxSuspectFalseLowRatePct: Double
    ) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    isfCrAutoActivationMinSensorQualityScore = minSensorQualityScore.coerceIn(0.0, 1.0),
                    isfCrAutoActivationMinSensorFactor = minSensorFactor.coerceIn(0.0, 1.0),
                    isfCrAutoActivationMaxWearConfidencePenalty = maxWearConfidencePenalty.coerceIn(0.0, 1.0),
                    isfCrAutoActivationMaxSensorAgeHighRatePct = maxSensorAgeHighRatePct.coerceIn(0.0, 100.0),
                    isfCrAutoActivationMaxSuspectFalseLowRatePct = maxSuspectFalseLowRatePct.coerceIn(0.0, 100.0)
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR auto-activation sensor gate updated"
        }
    }

    fun setIsfCrAutoActivationDayTypeThresholds(
        minDayTypeRatio: Double,
        maxDayTypeSparseRatePct: Double
    ) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    isfCrAutoActivationMinDayTypeRatio = minDayTypeRatio.coerceIn(0.0, 1.0),
                    isfCrAutoActivationMaxDayTypeSparseRatePct = maxDayTypeSparseRatePct.coerceIn(0.0, 100.0)
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR auto-activation day-type gate updated"
        }
    }

    fun setIsfCrAutoActivationRequireDailyQualityGate(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(isfCrAutoActivationRequireDailyQualityGate = enabled)
            }
            triggerReactiveAutomationAsync()
            messageState.value = if (enabled) {
                "ISF/CR daily quality gate enabled"
            } else {
                "ISF/CR daily quality gate disabled"
            }
        }
    }

    fun setIsfCrAutoActivationDailyRiskBlockLevel(level: Int) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(isfCrAutoActivationDailyRiskBlockLevel = level.coerceIn(2, 3))
            }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR daily risk gate threshold updated"
        }
    }

    fun setIsfCrAutoActivationDailyQualityThresholds(
        minDailyMatchedSamples: Int,
        maxDailyMae30Mmol: Double,
        maxDailyMae60Mmol: Double,
        maxHypoRatePct: Double,
        minDailyCiCoverage30Pct: Double,
        minDailyCiCoverage60Pct: Double,
        maxDailyCiWidth30Mmol: Double,
        maxDailyCiWidth60Mmol: Double
    ) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    isfCrAutoActivationMinDailyMatchedSamples = minDailyMatchedSamples.coerceIn(24, 720),
                    isfCrAutoActivationMaxDailyMae30Mmol = maxDailyMae30Mmol.coerceIn(0.3, 4.0),
                    isfCrAutoActivationMaxDailyMae60Mmol = maxDailyMae60Mmol.coerceIn(0.5, 6.0),
                    isfCrAutoActivationMaxHypoRatePct = maxHypoRatePct.coerceIn(0.5, 30.0),
                    isfCrAutoActivationMinDailyCiCoverage30Pct = minDailyCiCoverage30Pct.coerceIn(20.0, 99.0),
                    isfCrAutoActivationMinDailyCiCoverage60Pct = minDailyCiCoverage60Pct.coerceIn(20.0, 99.0),
                    isfCrAutoActivationMaxDailyCiWidth30Mmol = maxDailyCiWidth30Mmol.coerceIn(0.3, 6.0),
                    isfCrAutoActivationMaxDailyCiWidth60Mmol = maxDailyCiWidth60Mmol.coerceIn(0.5, 8.0)
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR daily quality thresholds updated"
        }
    }

    fun setIsfCrAutoActivationRollingGateSettings(
        minRequiredWindows: Int,
        maeRelaxFactor: Double,
        ciCoverageRelaxFactor: Double,
        ciWidthRelaxFactor: Double
    ) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    isfCrAutoActivationRollingMinRequiredWindows = minRequiredWindows.coerceIn(1, 3),
                    isfCrAutoActivationRollingMaeRelaxFactor = maeRelaxFactor.coerceIn(1.0, 1.5),
                    isfCrAutoActivationRollingCiCoverageRelaxFactor = ciCoverageRelaxFactor.coerceIn(0.70, 1.0),
                    isfCrAutoActivationRollingCiWidthRelaxFactor = ciWidthRelaxFactor.coerceIn(1.0, 1.5)
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR rolling quality gate thresholds updated"
        }
    }

    fun setIsfCrRetention(snapshotDays: Int, evidenceDays: Int) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    isfCrSnapshotRetentionDays = snapshotDays.coerceIn(30, 730),
                    isfCrEvidenceRetentionDays = evidenceDays.coerceIn(30, 1095)
                )
            }
            triggerReactiveAutomationAsync()
            messageState.value = "ISF/CR retention updated"
        }
    }

    fun addPhysioTag(tagType: String, severity: Double = 0.7, durationHours: Int = 6) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val safeType = normalizePhysioTagType(tagType).ifBlank { return@launch }
            val safeSeverity = severity.coerceIn(0.1, 1.0)
            val result = container.contextEventSyncCoordinator.save(
                CompensationEvent(
                    localId = "tag-${UUID.randomUUID()}",
                    startTs = now,
                    endTs = now + durationHours.coerceIn(1, 48) * 60L * 60L * 1000L,
                    type = quickPhysioEventType(safeType),
                    severity = when {
                        safeSeverity >= 0.8 -> EventSeverity.HIGH
                        safeSeverity >= 0.4 -> EventSeverity.MEDIUM
                        else -> EventSeverity.LOW
                    },
                    source = EventSource.USER,
                    attributes = mapOf(
                        "physioTagType" to safeType,
                        "physioSeverity" to safeSeverity.toString()
                    ),
                    note = "quick_tag:$safeType"
                ),
                expectedRevision = null
            )
            publishContextEventResult(ContextEventCommandAction.SAVE, result)
        }
    }

    fun closePhysioTag(tagId: String, expectedRevision: Long) {
        val safeId = tagId.trim()
        if (safeId.isEmpty()) return
        viewModelScope.launch {
            val result = container.contextEventSyncCoordinator.close(safeId, expectedRevision)
            publishContextEventResult(ContextEventCommandAction.CLOSE, result)
        }
    }

    fun clearActivePhysioTags() {
        viewModelScope.launch {
            val results = container.contextEventSyncCoordinator.closeAllActive()
            val representative = when {
                results.isEmpty() -> ContextEventCommandResult.NOT_FOUND
                results.all {
                    it == ContextEventCommandResult.ACKNOWLEDGED || it == ContextEventCommandResult.LOCAL_ONLY
                } -> ContextEventCommandResult.ACKNOWLEDGED
                ContextEventCommandResult.CONFLICT in results -> ContextEventCommandResult.CONFLICT
                ContextEventCommandResult.PENDING in results -> ContextEventCommandResult.PENDING
                ContextEventCommandResult.FAILED in results -> ContextEventCommandResult.FAILED
                ContextEventCommandResult.REJECTED in results -> ContextEventCommandResult.REJECTED
                else -> ContextEventCommandResult.NOT_FOUND
            }
            val disposition = ContextEventCommandUiPolicy.resolve(ContextEventCommandAction.CLOSE, representative)
            messageState.value = getApplication<Application>().getString(disposition.messageRes)
        }
    }

    fun setKillSwitch(enabled: Boolean) {
        killSwitchOverrideState.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.settingsStore.update { it.copy(killSwitch = enabled) }
                messageState.value = if (enabled) "Kill switch enabled" else "Kill switch disabled"
            } finally {
                killSwitchOverrideState.value = null
            }
        }
    }

    fun setShowEventsOnGraph(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(showEventsOnGraph = enabled) }
        }
    }

    fun enablePowerSave(durationMs: Long?) {
        val untilMs = durationMs?.let {
            PowerSaveController.untilForDuration(System.currentTimeMillis(), it)
        } ?: PowerSaveController.INDEFINITE_UNTIL_MS
        viewModelScope.launch(Dispatchers.IO) {
            container.settingsStore.update { it.copy(powerSaveUntilMs = untilMs) }
            PowerSaveRuntimeState.setUntil(untilMs)
            WorkScheduler.cancelRuntimeWork(getApplication())
            WorkScheduler.schedulePowerSaveResume(getApplication(), untilMs)
            container.stopRuntimeControllers(LocalNightscoutRuntimeReason.POWER_SAVE_ACTIVE)
            LocalNightscoutServiceController.stop(
                getApplication(),
                LocalNightscoutRuntimeReason.POWER_SAVE_ACTIVE
            )
            messageState.value = when (durationMs) {
                PowerSaveController.ONE_HOUR_MS -> "Power Save enabled for 1 hour"
                PowerSaveController.TWO_HOURS_MS -> "Power Save enabled for 2 hours"
                PowerSaveController.FOUR_HOURS_MS -> "Power Save enabled for 4 hours"
                else -> "Power Save enabled until manually turned on"
            }
        }
    }

    fun disablePowerSave() {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = container.settingsStore.settings.first()
            container.settingsStore.update {
                it.copy(powerSaveUntilMs = PowerSaveController.OFF_UNTIL_MS)
            }
            PowerSaveRuntimeState.clear()
            WorkScheduler.cancelPowerSaveResume(getApplication())
            WorkScheduler.schedule(getApplication())
            if (settings.localNightscoutEnabled || settings.localBroadcastIngestEnabled) {
                LocalNightscoutServiceController.start(getApplication())
            }
            messageState.value = "Power Save disabled"
        }
    }

    fun setLocalBroadcastIngestEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(localBroadcastIngestEnabled = enabled) }
            LocalNightscoutServiceController.start(getApplication())
            messageState.value = if (enabled) {
                "Local broadcast ingest enabled"
            } else {
                "Local broadcast ingest disabled"
            }
        }
    }

    fun setStrictBroadcastValidation(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(strictBroadcastSenderValidation = enabled) }
            messageState.value = if (enabled) {
                "Strict sender validation enabled"
            } else {
                "Strict sender validation disabled"
            }
        }
    }

    fun setLocalCommandFallbackConfig(enabled: Boolean, packageName: String, action: String) {
        viewModelScope.launch {
            val normalizedPackage = packageName.trim()
            val normalizedAction = action.trim()
            container.settingsStore.update {
                it.copy(
                    localCommandFallbackEnabled = enabled,
                    localCommandPackage = normalizedPackage.ifBlank { "info.nightscout.androidaps" },
                    localCommandAction = normalizedAction.ifBlank { "info.nightscout.client.NEW_TREATMENT" }
                )
            }
            messageState.value = if (enabled) {
                "Local command fallback enabled"
            } else {
                "Local command fallback disabled"
            }
        }
    }

    fun setLocalNightscoutConfig(enabled: Boolean, port: Int) {
        viewModelScope.launch {
            val safePort = port.coerceIn(1_024, 65_535)
            val current = container.settingsStore.settings.first()
            val effectiveEnabled = LocalNightscoutUpgradeMigrationGate.canEnable(
                requestedEnabled = enabled,
                migrationAcknowledged = current.localNightscoutLegacyMigrationAcknowledged
            )
            container.settingsStore.update {
                it.copy(
                    localNightscoutEnabled = effectiveEnabled,
                    localNightscoutPort = safePort
                )
            }
            LocalNightscoutServiceController.reconcile(getApplication(), effectiveEnabled)
            if (enabled && !effectiveEnabled) {
                LocalNightscoutRuntimeState.setup(
                    safePort,
                    LocalNightscoutRuntimeState.value.caFingerprint,
                    LocalNightscoutRuntimeReason.LEGACY_MIGRATION_ACK_REQUIRED
                )
            }
            messageState.value = if (enabled && !effectiveEnabled) {
                "Remove the legacy CA and old AAPS credential, then acknowledge the Local Nightscout migration before enabling"
            } else if (effectiveEnabled) {
                "Local Nightscout requested on exact port $safePort; wait for SETUP/READY"
            } else {
                "Local Nightscout disabled"
            }
        }
    }

    fun exportLocalNightscoutCertificate() {
        viewModelScope.launch {
            if (!container.settingsStore.settings.first().localNightscoutLegacyMigrationAcknowledged) {
                messageState.value = "Acknowledge legacy CA and old AAPS credential removal before exporting the new CA"
                return@launch
            }
            runCatching {
                val context = getApplication<Application>().applicationContext
                val caCertificate = LocalNightscoutTls.loadCaCertificate(context)
                val serverCertificate = LocalNightscoutTls.loadServerCertificate(context)
                LocalNightscoutRuntimeState.fingerprintAvailable(
                    LocalNightscoutTls.fingerprintSha256(caCertificate)
                )
                val caDerBytes = caCertificate.encoded
                val caPemBytes = LocalNightscoutTls.toPem(caCertificate).toByteArray()
                val serverPemBytes = LocalNightscoutTls.toPem(serverCertificate).toByteArray()
                val resolver = context.contentResolver

                fun exportDownload(fileName: String, mimeType: String, payload: ByteArray) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("failed_to_create_download_entry:$fileName")
                    resolver.openOutputStream(uri)?.use { output ->
                        output.write(payload)
                    } ?: error("failed_to_open_output_stream:$fileName")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val finalizeValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                        resolver.update(uri, finalizeValues, null, null)
                    }
                }

                exportDownload(
                    fileName = "copilot-local-nightscout-root-ca.cer",
                    mimeType = "application/pkix-cert",
                    payload = caDerBytes
                )
                exportDownload(
                    fileName = "copilot-local-nightscout-root-ca.crt",
                    mimeType = "application/x-x509-ca-cert",
                    payload = caPemBytes
                )
                exportDownload(
                    fileName = "copilot-local-nightscout-server.crt",
                    mimeType = "application/x-x509-ca-cert",
                    payload = serverPemBytes
                )
            }.onSuccess {
                messageState.value =
                    "TLS certificates exported (root CA + server). Install root CA in Android, then reconnect AAPS NSClient."
            }.onFailure {
                messageState.value = "Certificate export failed: ${it.message}"
            }
        }
    }

    fun copyLocalNightscoutApiSecret() {
        viewModelScope.launch {
            if (!container.settingsStore.settings.first().localNightscoutLegacyMigrationAcknowledged) {
                messageState.value = "Acknowledge legacy CA and old AAPS credential removal before copying the new API secret"
                return@launch
            }
            val context = getApplication<Application>().applicationContext
            val secret = runCatching {
                withContext(Dispatchers.IO) {
                    LocalNightscoutTls.withApiSecretChars(context) { it.copyOf() }
                }
            }.getOrElse { error ->
                messageState.value = "Local API secret unavailable: ${error.message}"
                return@launch
            }
            try {
                LocalNightscoutSecretClipboard.copy(context, secret)
                messageState.value =
                    "Local API secret copied as sensitive content; Copilot will make a best-effort owned-clip clear after about 60 seconds and on next startup."
            } finally {
                secret.fill('\u0000')
            }
        }
    }

    fun resetLocalNightscoutIdentity(confirmed: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                container.resetLocalNightscoutIdentity(confirmed)
            }
            if (result != LocalNightscoutIdentityResetResult.CANCELLED) {
                LocalNightscoutServiceController.stop(getApplication(), reason = null)
            }
            messageState.value = when (result) {
                LocalNightscoutIdentityResetResult.CANCELLED ->
                    "Local Nightscout identity reset cancelled"
                LocalNightscoutIdentityResetResult.RESET ->
                    "Local Nightscout identity reset. Export/install the new CA, copy the new API secret into AAPS, then reconnect NSClient."
                LocalNightscoutIdentityResetResult.FAILED ->
                    "Local Nightscout identity reset failed closed; integration remains disabled"
            }
        }
    }

    fun installLocalNightscoutCertificate() {
        viewModelScope.launch {
            if (!container.settingsStore.settings.first().localNightscoutLegacyMigrationAcknowledged) {
                messageState.value = "Acknowledge legacy CA and old AAPS credential removal before installing the new CA"
                return@launch
            }
            runCatching {
                val context = getApplication<Application>().applicationContext
                val certificate = LocalNightscoutTls.loadCaCertificate(context)
                LocalNightscoutRuntimeState.fingerprintAvailable(
                    LocalNightscoutTls.fingerprintSha256(certificate)
                )
                val installIntent = KeyChain.createInstallIntent().apply {
                    putExtra(KeyChain.EXTRA_CERTIFICATE, certificate.encoded)
                    putExtra(KeyChain.EXTRA_NAME, "AAPS Copilot Loopback Root CA")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(installIntent)
            }.onSuccess {
                messageState.value =
                    "System certificate installer opened for Root CA. If CA install is not offered, export certs and install root CA manually from Android settings."
            }.onFailure {
                messageState.value = "Certificate install launch failed: ${it.message}"
            }
        }
    }

    fun openCertificateSettings() {
        viewModelScope.launch {
            runCatching {
                val context = getApplication<Application>().applicationContext
                val intent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }.onSuccess {
                messageState.value = "Android security settings opened. Install CA certificate from Downloads."
            }.onFailure {
                messageState.value = "Failed to open security settings: ${it.message}"
            }
        }
    }

    fun setRuleConfig(
        postHypoEnabled: Boolean,
        patternEnabled: Boolean,
        segmentEnabled: Boolean,
        postHypoPriority: Int,
        patternPriority: Int,
        segmentPriority: Int,
        postHypoCooldownMinutes: Int,
        patternCooldownMinutes: Int,
        segmentCooldownMinutes: Int
    ) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    rulePostHypoEnabled = postHypoEnabled,
                    rulePatternEnabled = patternEnabled,
                    ruleSegmentEnabled = segmentEnabled,
                    rulePostHypoPriority = postHypoPriority.coerceIn(0, 200),
                    rulePatternPriority = patternPriority.coerceIn(0, 200),
                    ruleSegmentPriority = segmentPriority.coerceIn(0, 200),
                    rulePostHypoCooldownMinutes = postHypoCooldownMinutes.coerceIn(0, 240),
                    rulePatternCooldownMinutes = patternCooldownMinutes.coerceIn(0, 240),
                    ruleSegmentCooldownMinutes = segmentCooldownMinutes.coerceIn(0, 240)
                )
            }
            messageState.value = "Rule config updated"
        }
    }

    fun setSafetyLimits(
        maxActionsIn6h: Int,
        staleDataMaxMinutes: Int,
        safetyMinTargetMmol: Double? = null,
        safetyMaxTargetMmol: Double? = null,
        carbAbsorptionMaxAgeMinutes: Int? = null,
        carbComputationMaxGrams: Double? = null
    ) {
        viewModelScope.launch {
            container.settingsStore.updateSafetyLimits(
                maxActionsIn6Hours = maxActionsIn6h,
                staleDataMaxMinutes = staleDataMaxMinutes,
                safetyMinTargetMmol = safetyMinTargetMmol,
                safetyMaxTargetMmol = safetyMaxTargetMmol,
                carbAbsorptionMaxAgeMinutes = carbAbsorptionMaxAgeMinutes,
                carbComputationMaxGrams = carbComputationMaxGrams
            )
            messageState.value = "Safety limits updated"
        }
    }

    fun setSafetyTargetBounds(minTargetMmol: Double, maxTargetMmol: Double) {
        viewModelScope.launch {
            container.settingsStore.updateBaseTargetScheduleForBounds(
                minTarget = minTargetMmol,
                maxTarget = maxTargetMmol
            )
            messageState.value = "Safety target bounds updated"
        }
    }

    fun setPostHypoTuning(
        thresholdMmol: Double,
        deltaThresholdMmol5m: Double,
        targetMmol: Double,
        durationMinutes: Int,
        lookbackMinutes: Int
    ) {
        viewModelScope.launch {
            container.settingsStore.update {
                it.copy(
                    postHypoThresholdMmol = thresholdMmol.coerceIn(4.0, 10.0),
                    postHypoDeltaThresholdMmol5m = deltaThresholdMmol5m.coerceIn(0.05, 1.0),
                    postHypoTargetMmol = targetMmol.coerceIn(4.0, 10.0),
                    postHypoDurationMinutes = durationMinutes.coerceIn(15, 180),
                    postHypoLookbackMinutes = lookbackMinutes.coerceIn(30, 240)
                )
            }
            messageState.value = "Post-hypo rule tuning updated"
        }
    }

    fun setPatternTuning(
        minSamplesPerWindow: Int,
        minActiveDaysPerWindow: Int,
        lowRateTrigger: Double,
        highRateTrigger: Double,
        lookbackDays: Int
    ) {
        val normalizedLookback = lookbackDays.coerceIn(30, 730)
        launchSensitivitySettingsChange(
            operationLabel = "PATTERN_LOOKBACK",
            requestedValue = normalizedLookback.toString(),
            successMessage = "Pattern tuning updated",
            updater = { it.copy(analyticsLookbackDays = normalizedLookback) },
            afterAccepted = {
                container.settingsStore.update {
                    it.copy(
                        patternMinSamplesPerWindow = minSamplesPerWindow.coerceIn(10, 300),
                        patternMinActiveDaysPerWindow = minActiveDaysPerWindow.coerceIn(3, 60),
                        patternLowRateTrigger = lowRateTrigger.coerceIn(0.03, 0.60),
                        patternHighRateTrigger = highRateTrigger.coerceIn(0.05, 0.80)
                    )
                }
                recalculateAnalyticsAsync()
            }
        )
    }

    fun setAdaptiveControllerConfig(
        enabled: Boolean,
        priority: Int,
        retargetMinutes: Int,
        safetyProfile: String,
        staleMaxMinutes: Int,
        maxActions6h: Int,
        maxStepMmol: Double
    ) {
        viewModelScope.launch {
            val normalizedProfile = when (safetyProfile.trim().uppercase(Locale.US)) {
                "STRICT" -> "STRICT"
                "AGGRESSIVE" -> "AGGRESSIVE"
                else -> "BALANCED"
            }
            val normalizedRetarget = when (retargetMinutes) {
                5,
                15,
                30 -> retargetMinutes
                else -> 5
            }
            container.settingsStore.update {
                it.copy(
                    adaptiveControllerEnabled = enabled,
                    adaptiveControllerPriority = priority.coerceIn(0, 200),
                    adaptiveControllerRetargetMinutes = normalizedRetarget,
                    adaptiveControllerSafetyProfile = normalizedProfile,
                    adaptiveControllerStaleMaxMinutes = staleMaxMinutes.coerceIn(5, 60),
                    adaptiveControllerMaxActions6h = maxActions6h.coerceIn(1, 10),
                    adaptiveControllerMaxStepMmol = maxStepMmol.coerceIn(0.05, 1.00)
                )
            }
            messageState.value = "Adaptive controller settings updated"
        }
    }

    fun setAdaptiveControllerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(adaptiveControllerEnabled = enabled) }
            triggerReactiveAutomationAsync()
            messageState.value = if (enabled) {
                "Adaptive controller enabled"
            } else {
                "Adaptive controller disabled"
            }
        }
    }

    private fun triggerReactiveAutomationAsync() {
        viewModelScope.launch(Dispatchers.Default) {
            WorkScheduler.triggerReactiveAutomation(getApplication())
        }
    }

    private fun ensureProfileAnalyticsHealthyAsync(reasonHint: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (container.analyticsRepository.isProfileEstimatorRevisionPending()) {
                    container.automationRepository.runLocalReadOnlyCycle()
                } else if (reasonHint != "startup") {
                    val settings = container.settingsStore.settings.first()
                    container.analyticsRepository.ensureProfileStateHealthy(
                        settings = settings,
                        reasonHint = reasonHint
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                runCatching {
                    container.auditLogger.warn(
                        "profile_analytics_health_check_failed",
                        mapOf(
                            "reasonHint" to reasonHint,
                            "reason" to (failure.message ?: failure::class.simpleName.orEmpty())
                        )
                    )
                }.onFailure { auditFailure ->
                    if (auditFailure is CancellationException) throw auditFailure
                }
            }
        }
    }

    private fun ensureCircadianAnalyticsHealthyAsync(reasonHint: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = container.settingsStore.settings.first()
            val rebuilt = container.analyticsRepository.ensureCircadianStateHealthy(
                settings = settings,
                reasonHint = reasonHint
            )
            if (rebuilt && (activeRouteState.value == ROUTE_ANALYTICS || activeRouteState.value == ROUTE_AI_ANALYSIS)) {
                refreshCircadianReplaySummaryAsync(force = true)
            }
        }
    }

    private fun normalizeLegacyActionStatusesAsync() {
        viewModelScope.launch(Dispatchers.Default) {
            container.actionRepository.normalizeLegacyBlockedCommands()
        }
    }

    private fun recalculateAnalyticsAsync() {
        viewModelScope.launch(Dispatchers.Default) {
            val settings = container.settingsStore.settings.first()
            container.analyticsRepository.recalculate(settings)
        }
    }

    fun runDryRun(days: Int = 14) {
        viewModelScope.launch {
            runCatching {
                container.automationRepository.runDryRunSimulation(days)
            }.onSuccess { report ->
                dryRunState.value = DryRunUi(
                    periodDays = report.periodDays,
                    samplePoints = report.samplePoints,
                    lines = report.rules.map {
                        "${it.ruleId}: TRG=${it.triggered}, BLK=${it.blocked}, NO=${it.noMatch}"
                    }
                )
                messageState.value = "Dry-run completed"
            }.onFailure {
                messageState.value = "Dry-run failed: ${it.message}"
            }
        }
    }

    fun runAutomationNow() {
        viewModelScope.launch {
            runCatching {
                container.automationRepository.runAutomationCycle()
                "Automation cycle completed"
            }.onFailure {
                messageState.value = "Automation failed: ${it.message}"
            }.onSuccess {
                messageState.value = it
                refreshCloudJobs(silent = true)
            }
        }
    }

    fun sendManualTempTarget(targetRaw: String, durationRaw: String, reasonRaw: String) {
        viewModelScope.launch {
            val targetMmol = parseFlexibleDouble(targetRaw)?.takeIf { it in 4.0..10.0 }
            val durationMinutes = durationRaw.trim().toIntOrNull()?.takeIf { it in 5..720 }
            if (targetMmol == null || durationMinutes == null) {
                messageState.value = "Manual temp target failed: invalid target/duration"
                return@launch
            }

            val reason = reasonRaw.trim().ifBlank { "manual_ui_temp_target" }
            val sent = runCatching {
                val command = buildManualCommand(
                    type = "temp_target",
                    params = mapOf(
                        "targetMmol" to String.format(Locale.US, "%.2f", targetMmol),
                        "durationMinutes" to durationMinutes.toString(),
                        "reason" to reason
                    )
                )
                container.actionRepository.submitTempTarget(command)
            }.getOrDefault(false)

            messageState.value = if (sent) {
                "Manual temp target sent (${String.format(Locale.US, "%.2f", targetMmol)} mmol/L, ${durationMinutes}m)"
            } else {
                "Manual temp target failed (check Action delivery / Audit Log)"
            }
        }
    }

    fun sendManualCarbs(
        carbsRaw: String,
        reasonRaw: String,
        foodProfile: io.aaps.copilot.domain.profile.MealAbsorptionProfile,
        manualMealEnergyKcal: Double? = null,
        eatingSoon: Boolean = false,
        submissionId: String = UUID.randomUUID().toString(),
        portionMetadata: io.aaps.copilot.domain.nutrition.MealPortionMetadata? = null,
        glycemicIndex: io.aaps.copilot.domain.profile.MealGlycemicIndex? = null
    ) {
        viewModelScope.launch {
            val safetyCapGrams = io.aaps.copilot.domain.nutrition.MealCarbLimits.MAX_MANUAL_MEAL_GRAMS
            val carbsGrams = parseFlexibleDouble(carbsRaw)?.takeIf { it.isFinite() && it in 1.0..safetyCapGrams }
            if (carbsGrams == null) {
                messageState.value = "Manual carbs failed: invalid grams value (allowed 1..${String.format(Locale.US, "%.0f", safetyCapGrams)} g)"
                return@launch
            }
            if (!isManualMealEnergyInputValid(manualMealEnergyKcal)) {
                messageState.value = "Manual carbs failed: invalid optional meal energy"
                return@launch
            }

            val reason = reasonRaw.trim().ifBlank { "manual_ui_carbs" }
            if (!submissionId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) {
                messageState.value = getApplication<Application>().getString(R.string.overview_meal_unconfirmed)
                return@launch
            }
            val result = try {
                val command = buildManualCommand(
                    type = "carbs",
                    params = mapOf(
                        "carbsGrams" to serializeConfirmedMealGrams(carbsGrams),
                        "reason" to reason
                    )
                ).copy(idempotencyKey = "manual:meal:$submissionId")
                manualCarbSubmission.submit(
                    command = command,
                    selection = io.aaps.copilot.domain.profile.MealAbsorptionSelection(
                        foodProfile, portionMetadata = portionMetadata, glycemicIndex = glycemicIndex
                    ),
                    mealEnergyKcal = manualMealEnergyKcal,
                    eatingSoon = eatingSoon
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val app = getApplication<Application>()
            messageState.value = manualMealSubmissionMessage(app, carbsGrams, result)
        }
    }

    fun runAutoConnectNow(silent: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val result = container.autoConnectRepository.bootstrap()
                if (result.rootEnabled) {
                    container.rootDbRepository.syncIfEnabled()
                }
                container.exportRepository.importBaselineFromExports()
                result
            }.onSuccess { result ->
                autoConnectState.value = AutoConnectUi(lines = buildAutoConnectLines(result))
                if (!silent) {
                    messageState.value = "Auto-connect scan complete"
                }
            }.onFailure {
                if (!silent) {
                    messageState.value = "Auto-connect failed: ${it.message}"
                }
            }
        }
    }

    fun runNightscoutSelfTest() {
        viewModelScope.launch {
            val settings = container.settingsStore.settings.first()
            val url = settings.resolvedNightscoutUrl()
            if (url.isBlank()) {
                messageState.value = "Nightscout self-test failed: URL is not configured"
                return@launch
            }
            val loopback = isLoopbackUrl(url)

            runCatching {
                val nsApi = withContext(Dispatchers.IO) { container.apiFactory.nightscoutApi(url, settings) }
                val status = nsApi.getStatus()
                val statusText = status.status ?: "unknown"
                if (!loopback) {
                    nsApi.getTreatments(mapOf("count" to "1"))
                    return@runCatching "Nightscout reachable ($statusText). Read-only check on external URL."
                }
                val latest = nsApi.getTreatments(mapOf("count" to "6"))
                val sgvEcho = nsApi.getSgvEntries(mapOf("count" to "3"))
                "Local Nightscout reachable ($statusText). Read-only check: ${latest.size} treatments, ${sgvEcho.size} glucose points."
            }.onSuccess { message ->
                messageState.value = message
            }.onFailure { error ->
                if (error is CancellationException || error is Error) throw error
                messageState.value = "Nightscout self-test failed: ${error.message}"
            }
        }
    }

    fun runAapsTlsDiagnostic() {
        viewModelScope.launch {
            val nowTs = System.currentTimeMillis()
            val settings = container.settingsStore.settings.first()
            val audits = db.auditLogDao().observeLatest(limit = 500).first()
            val lines = buildAapsTlsDiagnosticLines(settings, audits, nowTs)
            messageState.value = if (lines.isEmpty()) {
                "AAPS TLS diagnostic: no data"
            } else {
                lines.take(3).joinToString(" | ")
            }
        }
    }

    fun runDailyAnalysisNow() {
        viewModelScope.launch {
            container.aapsCarbHistorySyncRepository.sync()
            val nowTs = System.currentTimeMillis()
            val coverageHours = loadAiCoverageHours(nowTs = nowTs)
            container.auditLogger.info(
                "daily_analysis_manual_requested",
                mapOf(
                    "coverageHours" to coverageHours,
                    "activeRoute" to activeRouteState.value
                )
            )
            if (coverageHours < AI_MIN_DATA_HOURS.toDouble()) {
                container.auditLogger.warn(
                    "daily_analysis_manual_blocked",
                    mapOf(
                        "reason" to "insufficient_coverage",
                        "coverageHours" to coverageHours,
                        "requiredHours" to AI_MIN_DATA_HOURS
                    )
                )
                messageState.value = "AI analysis requires at least 24h data (${String.format(Locale.US, "%.1f", coverageHours)}/24h)"
                return@launch
            }
            messageState.value = "Daily analysis started"
            runCatching {
                container.insightsRepository.runDailyAnalysis()
            }.onSuccess { result ->
                container.auditLogger.info(
                    "daily_analysis_manual_completed",
                    mapOf("coverageHours" to coverageHours)
                )
                messageState.value = result
                refreshCloudJobs(silent = true)
                refreshAnalysisInsights(silent = true)
            }.onFailure { error ->
                val message = error.message ?: "unknown error"
                container.auditLogger.error(
                    "daily_analysis_manual_failed",
                    mapOf(
                        "coverageHours" to coverageHours,
                        "error" to message.take(320)
                    )
                )
                messageState.value = "Daily analysis failed: ${message.take(240)}"
            }
        }
    }

    fun updateAiChatDraft(value: String) {
        aiChatDraftState.value = value
    }

    fun setAiChatVoiceRepliesEnabled(enabled: Boolean) {
        aiChatVoiceRepliesEnabledState.value = enabled
        if (!enabled) {
            stopAiVoicePlayback()
        }
    }

    fun removeAiChatAttachment(id: String) {
        aiChatPendingAttachmentsState.value = aiChatPendingAttachmentsState.value.filterNot { it.id == id }
    }

    fun addAiChatAttachment(uri: Uri, preferImage: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val attachment = copyAiAttachmentToCache(uri = uri, preferImage = preferImage)
                val updated = aiChatPendingAttachmentsState.value.toMutableList().apply {
                    add(attachment)
                }
                aiChatPendingAttachmentsState.value = updated.takeLast(MAX_AI_CHAT_ATTACHMENTS)
            }.onFailure { error ->
                messageState.value = "AI attachment failed: ${error.message ?: "unknown error"}"
            }
        }
    }

    fun startAiVoiceRecording() {
        if (ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            messageState.value = "Record audio permission is required for AI voice mode"
            return
        }
        if (aiChatRecordingState.value) return
        runCatching {
            stopAiVoicePlayback()
            aiRecordingFile?.delete()
            val audioDir = File(getApplication<Application>().cacheDir, "ai-chat-audio").apply { mkdirs() }
            val output = File(audioDir, "recording-${System.currentTimeMillis()}.m4a")
            aiMediaRecorder?.release()
            aiMediaRecorder = MediaRecorder(getApplication()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(output.absolutePath)
                prepare()
                start()
            }
            aiRecordingFile = output
            aiChatRecordingState.value = true
        }.onFailure { error ->
            aiMediaRecorder?.release()
            aiMediaRecorder = null
            aiRecordingFile = null
            aiChatRecordingState.value = false
            messageState.value = "AI voice recording failed: ${error.message ?: "unknown error"}"
        }
    }

    fun stopAiVoiceRecordingAndSend() {
        val recordedFile = aiRecordingFile ?: run {
            aiChatRecordingState.value = false
            return
        }
        runCatching {
            aiMediaRecorder?.apply {
                stop()
                reset()
                release()
            }
        }
        aiMediaRecorder = null
        aiRecordingFile = null
        aiChatRecordingState.value = false
        aiChatVoiceBusyState.value = true
        viewModelScope.launch {
            val transcript = runCatching {
                container.aiChatRepository.transcribeAudio(
                    audioFile = recordedFile,
                    promptHint = "Diabetes and AAPS context. Preserve numbers, units, glucose values, insulin, carbs, IOB, COB, ISF, CR, DIA, UAM, steps, and activity terms."
                )
            }.getOrElse { error ->
                aiChatVoiceBusyState.value = false
                messageState.value = "AI voice transcription failed: ${error.message ?: "unknown error"}"
                recordedFile.delete()
                return@launch
            }
            recordedFile.delete()
            aiChatVoiceBusyState.value = false
            if (transcript.isBlank()) {
                messageState.value = "AI voice transcription returned empty text"
                return@launch
            }
            aiChatDraftState.value = transcript
            sendAiChatPrompt(transcript, fromVoiceTranscript = true)
        }
    }

    fun sendAiChatPrompt(prompt: String = aiChatDraftState.value, fromVoiceTranscript: Boolean = false) {
        val question = prompt.trim()
        if (question.isBlank()) return

        viewModelScope.launch {
            val snapshot = uiState.value
            if (!snapshot.aiAnalysisReady) {
                val waiting = String.format(
                    Locale.US,
                    "%.1f/%dh",
                    snapshot.aiDataCoverageHours,
                    snapshot.aiMinDataHours
                )
                aiChatMessagesState.value = aiChatMessagesState.value + AiChatMessageUi(
                    id = "assistant-wait-${System.currentTimeMillis()}",
                    role = "assistant",
                    text = "Нужно накопить минимум 24ч данных для AI-чата. Сейчас: $waiting",
                    ts = System.currentTimeMillis()
                )
                return@launch
            }

            val now = System.currentTimeMillis()
            val pendingAttachments = aiChatPendingAttachmentsState.value
            val historyBeforeSend = aiChatMessagesState.value
                .takeLast(10)
                .map { msg -> AiChatTurn(role = msg.role, text = msg.text) }
            val userAttachmentsUi = pendingAttachments.map { it.toUi() }
            aiChatMessagesState.value = aiChatMessagesState.value + AiChatMessageUi(
                id = "user-$now",
                role = "user",
                text = question,
                ts = now,
                attachments = userAttachmentsUi,
                voiceTranscript = fromVoiceTranscript
            )
            aiChatDraftState.value = ""
            aiChatPendingAttachmentsState.value = emptyList()
            aiChatInProgressState.value = true

            val contextSummary = buildAiChatContextSummary(snapshot)

            runCatching {
                if (pendingAttachments.isEmpty()) {
                    container.aiChatRepository.ask(
                        question = question,
                        contextSummary = contextSummary,
                        history = historyBeforeSend
                    )
                } else {
                    container.aiChatRepository.askWithAttachments(
                        question = question,
                        contextSummary = contextSummary,
                        history = historyBeforeSend,
                        attachments = pendingAttachments.map { attachment ->
                            AiChatAttachmentRequest(
                                name = attachment.name,
                                mimeType = attachment.mimeType,
                                localPath = attachment.localPath,
                                kind = attachment.kind
                            )
                        }
                    )
                }
            }.onSuccess { response ->
                aiChatMessagesState.value = aiChatMessagesState.value + AiChatMessageUi(
                    id = "assistant-${System.currentTimeMillis()}",
                    role = "assistant",
                    text = response,
                    ts = System.currentTimeMillis()
                )
                if (aiChatVoiceRepliesEnabledState.value) {
                    playAiVoiceReply(response)
                }
            }.onFailure { error ->
                aiChatMessagesState.value = aiChatMessagesState.value + AiChatMessageUi(
                    id = "assistant-err-${System.currentTimeMillis()}",
                    role = "assistant",
                    text = "AI chat failed: ${error.message ?: "unknown error"}",
                    ts = System.currentTimeMillis()
                )
            }
            aiChatInProgressState.value = false
        }
    }

    private suspend fun playAiVoiceReply(text: String) {
        aiChatVoiceBusyState.value = true
        val audioDir = File(getApplication<Application>().cacheDir, "ai-chat-tts").apply { mkdirs() }
        runCatching {
            val speechFile = container.aiChatRepository.synthesizeSpeech(
                text = text,
                outputDir = audioDir
            )
            stopAiVoicePlayback()
            aiChatSpeakingState.value = true
            aiVoicePlayer = MediaPlayer().apply {
                setDataSource(speechFile.absolutePath)
                setOnCompletionListener {
                    aiChatSpeakingState.value = false
                    it.release()
                    aiVoicePlayer = null
                    speechFile.delete()
                }
                setOnErrorListener { player, _, _ ->
                    aiChatSpeakingState.value = false
                    player.release()
                    aiVoicePlayer = null
                    speechFile.delete()
                    true
                }
                prepare()
                start()
            }
        }.onFailure { error ->
            messageState.value = "AI voice reply failed: ${error.message ?: "unknown error"}"
        }
        aiChatVoiceBusyState.value = false
    }

    private fun stopAiVoicePlayback() {
        aiVoicePlayer?.runCatching {
            stop()
        }
        aiVoicePlayer?.release()
        aiVoicePlayer = null
        aiChatSpeakingState.value = false
    }

    private fun copyAiAttachmentToCache(
        uri: Uri,
        preferImage: Boolean
    ): PendingAiAttachment {
        val context = getApplication<Application>()
        val resolver = context.contentResolver
        val document = DocumentFile.fromSingleUri(context, uri)
        val mimeType = resolver.getType(uri) ?: document?.type
        val isImage = preferImage || mimeType?.startsWith("image/") == true
        val baseName = document?.name?.takeIf { it.isNotBlank() } ?: "attachment-${System.currentTimeMillis()}"
        val safeName = baseName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val attachmentDir = File(context.cacheDir, "ai-chat-attachments").apply { mkdirs() }
        val target = File(attachmentDir, "${System.currentTimeMillis()}-$safeName")
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Unable to open attachment")
        return PendingAiAttachment(
            id = UUID.randomUUID().toString(),
            name = baseName,
            mimeType = mimeType,
            localPath = target.absolutePath,
            kind = if (isImage) AiChatAttachmentRequest.Kind.IMAGE else AiChatAttachmentRequest.Kind.FILE,
            sizeLabel = formatBytes(target.length()),
            previewLabel = buildAttachmentPreview(baseName, mimeType, target)
        )
    }

    private fun buildAttachmentPreview(
        name: String,
        mimeType: String?,
        file: File
    ): String {
        return if (mimeType?.startsWith("image/") == true) {
            "photo"
        } else {
            container.aiChatRepository
                .let { io.aaps.copilot.data.repository.AiChatRepository.extractTextAttachmentPreviewStatic(file, mimeType) }
                ?.lineSequence()
                ?.firstOrNull()
                ?.take(48)
                ?.takeIf { it.isNotBlank() }
                ?: name.substringAfterLast('.').ifBlank { "file" }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    override fun onCleared() {
        clinicalReportPdfExportCoordinator.close()
        runCatching { aiMediaRecorder?.release() }
        aiMediaRecorder = null
        aiRecordingFile = null
        stopAiVoicePlayback()
        super.onCleared()
    }

    fun refreshCloudJobs(silent: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val cloudBackendEnabled = isCopilotCloudBackendEndpoint(
                container.settingsStore.settings.first().cloudBaseUrl
            )
            if (!cloudBackendEnabled) {
                cloudJobsState.value = CloudJobsUiModel(timezone = "local", jobs = emptyList())
                if (!silent) {
                    messageState.value = "Cloud jobs disabled: OpenAI chat mode"
                }
                return@launch
            }
            runCatching {
                container.insightsRepository.fetchJobsStatus()
            }.onSuccess { jobs ->
                cloudJobsState.value = jobs
                if (!silent) {
                    messageState.value = "Cloud jobs status refreshed"
                }
            }.onFailure {
                if (!silent) {
                    messageState.value = "Cloud jobs status failed: ${it.message}"
                }
            }
        }
    }

    fun applyInsightsFilters(sourceRaw: String, statusRaw: String, daysRaw: String, weeksRaw: String) {
        val days = normalizeAiAnalysisWindowDays(daysRaw.trim().toIntOrNull())
        val weeks = if (days >= 30) 4 else 1
        insightsFilterState.value = InsightsFilterUi(
            source = null,
            status = null,
            days = days,
            weeks = weeks
        )
        refreshAnalysisInsights(silent = false)
    }

    fun refreshAnalysisHistory(silent: Boolean = false) {
        refreshAnalysisInsights(silent)
    }

    private fun refreshAnalysisInsights(silent: Boolean) {
        maybeGenerateLocalDailyAnalysis(silent = true)
        viewModelScope.launch(Dispatchers.IO) {
            val filter = insightsFilterState.value
            runCatching {
                container.insightsRepository.fetchAnalysisHistory(
                    limit = 30,
                    source = filter.source,
                    status = filter.status,
                    days = filter.days
                )
            }.onSuccess { history ->
                analysisHistoryState.value = history
                if (!silent) {
                    messageState.value = "Analysis history refreshed"
                }
            }.onFailure {
                if (!silent) {
                    messageState.value = "Analysis history failed: ${it.message}"
                }
            }

            runCatching {
                container.insightsRepository.fetchAnalysisTrend(
                    weeks = filter.weeks,
                    source = filter.source,
                    status = filter.status
                )
            }.onSuccess { trend ->
                analysisTrendState.value = trend
            }.onFailure {
                if (!silent) {
                    messageState.value = "Analysis trend failed: ${it.message}"
                }
            }
        }
    }

    fun runCloudReplayNow(days: Int = 14, stepMinutes: Int = 5) {
        viewModelScope.launch {
            val cloudBackendEnabled = isCopilotCloudBackendEndpoint(
                container.settingsStore.settings.first().cloudBaseUrl
            )
            if (!cloudBackendEnabled) {
                messageState.value = "Cloud replay disabled: OpenAI chat mode"
                return@launch
            }
            runCatching {
                container.insightsRepository.runReplayReport(days, stepMinutes)
            }.onSuccess { report ->
                cloudReplayState.value = report
                messageState.value = "Cloud replay completed (${report.points} points)"
            }.onFailure {
                messageState.value = "Cloud replay failed: ${it.message}"
            }
        }
    }

    fun exportInsightsCsv() {
        viewModelScope.launch {
            val history = analysisHistoryState.value
            val trend = analysisTrendState.value
            if (history == null || trend == null) {
                messageState.value = "Refresh history first"
                return@launch
            }

            val filterLabel = buildInsightsFilterLabel(insightsFilterState.value)
            runCatching {
                container.insightsRepository.exportAnalysisCsv(history, trend, filterLabel)
            }.onSuccess { path ->
                messageState.value = "Insights CSV exported: $path"
            }.onFailure {
                messageState.value = "Insights CSV export failed: ${it.message}"
            }
        }
    }

    fun exportInsightsPdf() {
        viewModelScope.launch {
            val history = analysisHistoryState.value
            val trend = analysisTrendState.value
            if (history == null || trend == null) {
                messageState.value = "Refresh history first"
                return@launch
            }

            val filterLabel = buildInsightsFilterLabel(insightsFilterState.value)
            runCatching {
                container.insightsRepository.exportAnalysisPdf(history, trend, filterLabel)
            }.onSuccess { path ->
                messageState.value = "Insights PDF exported: $path"
            }.onFailure {
                messageState.value = "Insights PDF export failed: ${it.message}"
            }
        }
    }

    fun exportReplayCsv() {
        viewModelScope.launch {
            val report = cloudReplayState.value
            if (report == null) {
                messageState.value = "Run replay first"
                return@launch
            }
            runCatching {
                container.insightsRepository.exportReplayCsv(report)
            }.onSuccess { path ->
                messageState.value = "CSV exported: $path"
            }.onFailure {
                messageState.value = "CSV export failed: ${it.message}"
            }
        }
    }

    fun exportReplayPdf(horizonFilter: Int?) {
        viewModelScope.launch {
            val report = cloudReplayState.value
            if (report == null) {
                messageState.value = "Run replay first"
                return@launch
            }
            runCatching {
                container.insightsRepository.exportReplayPdf(report, horizonFilter)
            }.onSuccess { path ->
                messageState.value = "PDF exported: $path"
            }.onFailure {
                messageState.value = "PDF export failed: ${it.message}"
            }
        }
    }

    private fun maybeGenerateLocalDailyAnalysis(silent: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val nowTs = System.currentTimeMillis()
            val coverageHours = loadAiCoverageHours(nowTs = nowTs)
            if (coverageHours < AI_MIN_DATA_HOURS.toDouble()) {
                return@launch
            }

            val recentTelemetry = db.telemetryDao().latestByKeysSince(
                nowTs - AI_REPORT_RECENT_WINDOW_MS,
                DAILY_REPORT_REFRESH_TELEMETRY_KEYS
            )
            val refreshDecision = evaluateLocalDailyReportRefreshDecision(recentTelemetry)
            if (!refreshDecision.shouldGenerate) {
                return@launch
            }
            refreshDecision.reason
                ?.takeIf { it != DAILY_REPORT_REFRESH_REASON_MISSING_REPORT }
                ?.let { reason ->
                    container.auditLogger.info(
                        "daily_forecast_report_refresh_requested",
                        mapOf(
                            "reason" to reason,
                            "recentWindowHours" to (AI_REPORT_RECENT_WINDOW_MS / 3_600_000L),
                            "sensorLagInputsPresent" to true
                        )
                    )
                }

            runCatching {
                container.insightsRepository.generateDailyForecastReport(hours = AI_MIN_DATA_HOURS)
            }.onSuccess { report ->
                if (!silent) {
                    messageState.value = report.statusLine
                }
                refreshAnalysisInsights(silent = true)
            }.onFailure { error ->
                if (!silent) {
                    messageState.value = "Local AI report failed: ${error.message}"
                }
            }
        }
    }

    private fun computeAiCoverageHours(
        glucose: List<GlucoseSampleEntity>,
        nowTs: Long
    ): Double {
        val sorted = glucose.sortedBy { it.timestamp }
        return computeAiCoverageHours(
            oldestTs = sorted.firstOrNull()?.timestamp,
            latestTs = sorted.lastOrNull()?.timestamp,
            nowTs = nowTs
        )
    }

    private fun computeAiCoverageHours(
        oldestTs: Long?,
        latestTs: Long?,
        nowTs: Long
    ): Double {
        if (oldestTs == null || latestTs == null || latestTs <= oldestTs) return 0.0
        val effectiveEnd = maxOf(nowTs, latestTs)
        val spanMs = (effectiveEnd - oldestTs).coerceAtLeast(0L)
        return (spanMs / 3_600_000.0).coerceAtMost(72.0)
    }

    private suspend fun loadAiCoverageHours(nowTs: Long): Double {
        val oldestTs = db.glucoseDao().minTimestamp()
        val latestTs = db.glucoseDao().maxTimestamp()
        val fromDbSpan = computeAiCoverageHours(oldestTs = oldestTs, latestTs = latestTs, nowTs = nowTs)
        if (fromDbSpan > 0.0) {
            return fromDbSpan
        }
        val fallback = db.glucoseDao().latest(limit = AI_COVERAGE_LATEST_LIMIT)
        return computeAiCoverageHours(glucose = fallback, nowTs = nowTs)
    }

    private fun buildAiChatContextSummary(state: MainUiState): String {
        val reportMetrics = if (state.dailyReportMetrics.isEmpty()) {
            "daily_report_metrics: none"
        } else {
            state.dailyReportMetrics.joinToString(separator = "\n") { metric ->
                "${metric.horizonMinutes}m -> " +
                    "MAE=${formatNullable(metric.mae)}, " +
                    "RMSE=${formatNullable(metric.rmse)}, " +
                    "MARD=${formatNullable(metric.mardPct)}%, " +
                    "BIAS=${formatNullable(metric.bias)}, " +
                    "n=${metric.sampleCount ?: 0}"
            }
        }

        val topFactors = state.dailyReportReplayFactors
            .sortedByDescending { it.contributionScore }
            .take(6)
            .joinToString(separator = "; ") { factor ->
                "${factor.horizonMinutes}m:${factor.factor} score=${formatNullable(factor.contributionScore)} uplift=${formatNullable(factor.upliftPct)}%"
            }
            .ifBlank { "none" }

        val hotspots = state.dailyReportReplayHotspots
            .sortedByDescending { it.mae }
            .take(6)
            .joinToString(separator = "; ") { hotspot ->
                "${hotspot.horizonMinutes}m H${hotspot.hour}: MAE=${formatNullable(hotspot.mae)} MARD=${formatNullable(hotspot.mardPct)}%"
            }
            .ifBlank { "none" }

        val recommendations = state.dailyReportRecommendations
            .take(6)
            .joinToString(separator = "; ")
            .ifBlank { "none" }

        return buildString {
            appendLine("coverage_hours=${String.format(Locale.US, "%.1f", state.aiDataCoverageHours)}")
            appendLine("glucose_current=${formatNullable(state.latestGlucoseMmol)} mmol/L")
            appendLine("delta=${formatNullable(state.glucoseDelta)} mmol/L")
            appendLine("forecast_5=${formatNullable(state.forecast5m)} ci=[${formatNullable(state.forecast5mCiLow)}..${formatNullable(state.forecast5mCiHigh)}]")
            appendLine("forecast_30=${formatNullable(state.forecast30m)} ci=[${formatNullable(state.forecast30mCiLow)}..${formatNullable(state.forecast30mCiHigh)}]")
            appendLine("forecast_60=${formatNullable(state.forecast60m)} ci=[${formatNullable(state.forecast60mCiLow)}..${formatNullable(state.forecast60mCiHigh)}]")
            appendLine("iob=${formatNullable(state.latestIobUnits)}U iob_real=${formatNullable(state.latestIobRealUnits)}U cob=${formatNullable(state.latestCobGrams)}g dia_h=${formatNullable(state.insulinRealOnsetMinutes?.div(60.0))}")
            appendLine("isf_real=${formatNullable(state.isfCrRealtimeIsfEff)} isf_base=${formatNullable(state.isfCrRealtimeIsfBase)}")
            appendLine("cr_real=${formatNullable(state.isfCrRealtimeCrEff)} cr_base=${formatNullable(state.isfCrRealtimeCrBase)}")
            appendLine("isfcr_conf=${formatNullable(state.isfCrRealtimeConfidence)} quality=${formatNullable(state.isfCrRealtimeQualityScore)} mode=${state.isfCrRealtimeMode ?: "--"}")
            appendLine("activity_ratio=${formatNullable(state.latestActivityRatio)} steps=${formatNullable(state.latestStepsCount)}")
            appendLine("uam_calc_active=${state.calculatedUamActive ?: false} uci0=${formatNullable(state.calculatedUci0Mmol5m)} uam_carbs=${formatNullable(state.calculatedUamCarbsGrams)}")
            appendLine("sensor_quality_score=${formatNullable(state.sensorQualityScore)} sensor_blocked=${state.sensorQualityBlocked ?: false}")
            appendLine("adaptive_state=${state.controllerState ?: "--"} next_target=${formatNullable(state.controllerNextTarget)} confidence=${formatNullable(state.controllerConfidence)}")
            appendLine("daily_report_generated_ts=${state.dailyReportGeneratedAtTs ?: 0}")
            appendLine("daily_report_metrics:")
            appendLine(reportMetrics)
            appendLine("daily_replay_top_factors=$topFactors")
            appendLine("daily_replay_hotspots=$hotspots")
            appendLine("daily_recommendations=$recommendations")
        }
    }

    private fun formatNullable(value: Double?, decimals: Int = 2): String {
        if (value == null || !value.isFinite()) return "--"
        return String.format(Locale.US, "%.${decimals}f", value)
    }

    private fun minutesSince(now: Long, ts: Long?): Long? {
        if (ts == null) return null
        return ((now - ts).coerceAtLeast(0L)) / 60_000L
    }

    private fun minutesLabel(value: Long?): String = value?.let { "${it}m" } ?: "-"

    private fun formatTs(ts: Long?): String {
        if (ts == null) return "-"
        return Instant.ofEpochMilli(ts)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }

    private fun normalizeFilter(value: String): String? {
        val normalized = value.trim().lowercase()
        if (normalized.isBlank() || normalized == "all") return null
        return normalized
    }

    private fun buildInsightsFilterLabel(filter: InsightsFilterUi): String =
        "Window: ${normalizeAiAnalysisWindowDays(filter.days)}d"

    private fun normalizeAiAnalysisWindowDays(days: Int?): Int {
        return when (days) {
            3, 5, 7, 30 -> days
            null -> 7
            in 1..4 -> 3
            in 5..6 -> 5
            in 7..29 -> 7
            else -> 30
        }
    }

    private fun buildAutoConnectLines(result: AapsAutoConnectRepository.BootstrapResult): List<String> {
        return buildList {
            add("Export path: ${result.exportPath ?: "-"}")
            add("Export connected: ${if (result.exportConnected) "yes" else "no"}")
            add("Nightscout configured: ${if (result.nightscoutConfigured) "yes" else "no"}")
            add("Nightscout source: ${result.nightscoutSource ?: "not detected"}")
            add("Root available: ${if (result.rootAvailable) "yes" else "no"}")
            add("Root DB detected: ${result.rootDbPath ?: "not detected"}")
            add("Root import enabled: ${if (result.rootEnabled) "yes" else "no"}")
            add("AAPS package: ${result.aapsPackage ?: "not installed"}")
            add("xDrip package: ${result.xdripPackage ?: "not installed"}")
            add("All-files access: ${if (result.hasAllFilesAccess) "granted" else "missing"}")
        }
    }

    private suspend fun buildManualCommand(
        type: String,
        params: Map<String, String>
    ): ActionCommand {
        val settings = container.settingsStore.settings.first()
        val nowTs = System.currentTimeMillis()
        val latestGlucoseTs = db.glucoseDao().maxTimestamp()
        val dataFresh = if (latestGlucoseTs == null) {
            false
        } else {
            nowTs - latestGlucoseTs <= settings.staleDataMaxMinutes * 60_000L
        }
        val actionsLast6h = container.actionRepository.countSentActionsLast6h(nowTs)
        return ActionCommand(
            id = UUID.randomUUID().toString(),
            type = type,
            params = params,
            safetySnapshot = SafetySnapshot(
                killSwitch = settings.killSwitch,
                dataFresh = dataFresh,
                activeTempTargetMmol = null,
                actionsLast6h = actionsLast6h
            ),
            idempotencyKey = "manual:$type:$nowTs:${UUID.randomUUID()}"
        )
    }

    private fun parseFlexibleDouble(raw: String): Double? {
        return LocaleFriendlyDecimalParser.parse(raw)?.value
    }

    private fun isLoopbackUrl(url: String): Boolean {
        val host = runCatching { URI(url.trim()).host.orEmpty() }.getOrDefault("")
        return host.equals("127.0.0.1") || host.equals("localhost", ignoreCase = true)
    }

    private fun buildAapsTlsDiagnosticLines(
        settings: AppSettings,
        audits: List<AuditLogEntity>,
        nowTs: Long
    ): List<String> {
        val aapsTlsCompatibility = inspectAapsTlsCompatibility()
        fun withCompatibilityHints(lines: List<String>): List<String> {
            val extras = mutableListOf<String>()
            if (!aapsTlsCompatibility.installed) {
                extras.add("AAPS package not detected on this phone")
            } else {
                aapsTlsCompatibility.targetSdk?.let { sdk ->
                    extras.add("AAPS targetSdk: $sdk")
                }
                if (aapsTlsCompatibility.likelyRejectsUserCa) {
                    extras.add("Compatibility warning: this AAPS build likely rejects user-installed CA certs")
                    extras.add("Hint: use public Nightscout HTTPS (public CA) or patched AAPS build with user-CA trust")
                }
            }
            return lines + extras
        }

        val loopbackUrl = "https://127.0.0.1:${settings.localNightscoutPort}"
        if (!settings.localNightscoutEnabled) {
            return withCompatibilityHints(
                listOf(
                "AAPS transport: OFF (reason=NS_DISABLED)",
                "Source error: local Nightscout emulator is disabled",
                "Hint 1: enable local Nightscout and save settings"
                )
            )
        }

        val runtime = LocalNightscoutRuntimeState.value
        if (runtime.status.name != "READY") {
            val reason = runtime.reason ?: "RUNTIME_NOT_READY"
            val transportState = if (runtime.status.name == "FAILED") "FAIL" else "SETUP"
            return withCompatibilityHints(
                listOf(
                    "AAPS transport: $transportState (reason=$reason)",
                    "Local Nightscout runtime: ${runtime.status.name} on exact port ${settings.localNightscoutPort}",
                    "Clinical API routes remain unavailable until the pinned TLS handshake succeeds and AAPS authorizes its socket"
                )
            )
        }

        val recentExternal = audits
            .asSequence()
            .filter { it.message == "local_nightscout_external_request" }
            .filter { nowTs - it.timestamp <= TLS_DIAGNOSTIC_WINDOW_MS }
            .toList()
            .sortedByDescending { it.timestamp }
        val recentExternalAapsLike = recentExternal.filter(::isLikelyAapsClientAudit)
        val recentSocketSessions = audits
            .asSequence()
            .filter { it.message == "local_nightscout_socket_session_created" }
            .filter { nowTs - it.timestamp <= TLS_DIAGNOSTIC_WINDOW_MS }
            .toList()
            .sortedByDescending { it.timestamp }
        val recentSocketAapsLike = recentSocketSessions.filter(::isLikelyAapsClientAudit)
        val recentSocketAuth = audits
            .asSequence()
            .filter { it.message == "local_nightscout_socket_authorize" }
            .filter { nowTs - it.timestamp <= TLS_DIAGNOSTIC_WINDOW_MS }
            .toList()
            .sortedByDescending { it.timestamp }
        val recentSocketAuthAapsLike = recentSocketAuth.filter(::isLikelyAapsClientAudit)

        val latestAuth = recentSocketAuthAapsLike.firstOrNull()
        if (latestAuth != null) {
            val source = auditMetaField(latestAuth, "source") ?: "unknown"
            return withCompatibilityHints(
                listOf(
                "AAPS transport: OK (reason=NS_SOCKET_AUTH_OK)",
                "Last AUTH: ${formatTs(latestAuth.timestamp)} (source=$source)",
                "Socket sessions ${TLS_DIAGNOSTIC_WINDOW_MINUTES}m: total=${recentSocketSessions.size}, app-like=${recentSocketAapsLike.size}",
                "Loopback endpoint: $loopbackUrl"
                )
            )
        }

        val latestSocket = recentSocketAapsLike.firstOrNull()
        if (latestSocket != null) {
            val source = auditMetaField(latestSocket, "source") ?: "unknown"
            return withCompatibilityHints(
                listOf(
                "AAPS transport: FAIL (reason=NS_SOCKET_NO_AUTH)",
                "Source error: Socket session opened (${formatTs(latestSocket.timestamp)} source=$source), but no authorize event",
                "Hint 1: verify API Secret in AAPS equals Copilot value",
                "Hint 2: restart NSClientV1 and trigger sync in AAPS"
                )
            )
        }

        val latestExternalAaps = recentExternalAapsLike.firstOrNull()
        if (latestExternalAaps != null) {
            val method = auditMetaField(latestExternalAaps, "method") ?: "GET"
            val path = auditMetaField(latestExternalAaps, "path") ?: "/api/v1/*"
            return withCompatibilityHints(
                listOf(
                "AAPS transport: FAIL (reason=NS_HTTP_NO_SOCKET)",
                "Source error: HTTPS request seen (${formatTs(latestExternalAaps.timestamp)} $method $path), but no socket session",
                "Hint 1: in AAPS use NSClientV1 and URL exactly $loopbackUrl",
                "Hint 2: install loopback certificate as CA and restart NSClient"
                )
            )
        }

        val recentBrowser = recentExternal.filter {
            auditMetaField(it, "source").equals("browser", ignoreCase = true)
        }
        val sourceError = if (recentBrowser.isNotEmpty()) {
            "browser HTTPS traffic exists, but no AAPS-like client traffic in last ${TLS_DIAGNOSTIC_WINDOW_MINUTES}m"
        } else {
            "no successful external HTTPS requests to local NS in last ${TLS_DIAGNOSTIC_WINDOW_MINUTES}m"
        }
        val reasonCode = if (recentBrowser.isNotEmpty()) "NS_BROWSER_ONLY" else "NS_NO_TRAFFIC"
        val secretHint = if (settings.apiSecret.isBlank()) {
            "Hint 4: set non-empty API Secret in Copilot and AAPS"
        } else {
            "Hint 4: verify API Secret in AAPS equals Copilot value"
        }
        return withCompatibilityHints(
            listOf(
            "AAPS transport: FAIL (reason=$reasonCode)",
            "Source error: $sourceError",
            "Hint 1: in AAPS NSClient set URL exactly $loopbackUrl",
            "Hint 2: install loopback certificate as CA in Android",
            "Hint 3: restart AAPS NSClient and trigger sync",
            secretHint
            )
        )
    }

    private fun inspectAapsTlsCompatibility(): AapsTlsCompatibility {
        val context = getApplication<Application>().applicationContext
        val pm = context.packageManager
        val packageName = "info.nightscout.androidaps"
        val appInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
        }.getOrNull() ?: return AapsTlsCompatibility(
            installed = false,
            targetSdk = null,
            networkSecurityConfigRes = null,
            likelyRejectsUserCa = false
        )

        // Android 14+ blocks reflective access to hidden ApplicationInfo fields,
        // so we avoid probing networkSecurityConfigRes via reflection.
        val networkSecurityConfigRes: Int? = null
        val likelyRejectsUserCa = appInfo.targetSdkVersion >= Build.VERSION_CODES.N
        return AapsTlsCompatibility(
            installed = true,
            targetSdk = appInfo.targetSdkVersion,
            networkSecurityConfigRes = networkSecurityConfigRes,
            likelyRejectsUserCa = likelyRejectsUserCa
        )
    }

    private fun isLikelyAapsClientAudit(entry: AuditLogEntity): Boolean {
        val source = auditMetaField(entry, "source")?.lowercase(Locale.US).orEmpty()
        if (source == "browser") return false
        val userAgent = auditMetaField(entry, "userAgent")?.lowercase(Locale.US).orEmpty()
        if (
            userAgent.contains("mozilla") ||
            userAgent.contains("chrome") ||
            userAgent.contains("safari") ||
            userAgent.contains("curl") ||
            userAgent.contains("postman") ||
            userAgent.contains("insomnia")
        ) {
            return false
        }
        return source == "okhttp" || source == "dalvik" || source == "unknown"
    }

    private fun buildActionLines(commands: List<ActionCommandEntity>): List<String> {
        if (commands.isEmpty()) return listOf("No auto-actions yet")
        return commands.take(12).map { command ->
            val channel = payloadField(command.payloadJson, "deliveryChannel") ?: "nightscout"
            val target = payloadField(command.payloadJson, "targetMmol")
            val duration = payloadField(command.payloadJson, "durationMinutes")
            val carbs = payloadField(command.payloadJson, "carbsGrams")
                ?: payloadField(command.payloadJson, "carbs")
                ?: payloadField(command.payloadJson, "grams")
            val details = when {
                target != null && duration != null -> "target=$target mmol/L, duration=${duration}m"
                carbs != null -> "carbs=${carbs}g"
                target != null -> "target=$target mmol/L"
                else -> command.payloadJson.take(80)
            }
            "${command.status} ${command.type}: $details, channel=$channel (${formatTs(command.timestamp)})"
        }
    }

    private fun hasManualCarbsNearby(
        therapy: List<TherapyEventEntity>,
        centerTs: Long,
        mergeWindowMinutes: Int
    ): Boolean {
        val windowMs = mergeWindowMinutes.coerceIn(5, 240) * 60_000L
        return therapy.any { event ->
            if (!eventTypeLooksLikeCarbs(event.type)) return@any false
            val carbs = payloadDouble(event.payloadJson, "grams", "carbs", "enteredCarbs", "mealCarbs")
                ?: return@any false
            carbs > 0.0 && kotlin.math.abs(event.timestamp - centerTs) <= windowMs
        }
    }

    private fun eventTypeLooksLikeCarbs(type: String): Boolean {
        val normalized = type.trim().lowercase(Locale.US)
        return normalized.contains("carb") || normalized.contains("meal")
    }

    private fun buildAuditRecords(
        audits: List<AuditLogEntity>,
        ruleExecutions: List<RuleExecutionEntity>,
        actions: List<ActionCommandEntity>
    ): List<AuditRecordRowUi> {
        val auditRows = audits.map { row ->
            AuditRecordRowUi(
                id = "audit:${row.id}",
                ts = row.timestamp,
                source = "audit",
                level = row.level,
                summary = row.message,
                context = row.metadataJson.take(220)
            )
        }
        val ruleRows = ruleExecutions.map { row ->
            val reason = parseRuleReasonsJson(row.reasonsJson).joinToString("; ").ifBlank { "-" }
            AuditRecordRowUi(
                id = "rule:${row.id}",
                ts = row.timestamp,
                source = "rule",
                level = if (row.state == "TRIGGERED") "INFO" else if (row.state == "BLOCKED") "WARN" else "INFO",
                summary = "${row.ruleId} ${row.state}",
                context = reason,
                payloadSummary = row.actionJson?.take(220)
            )
        }
        val actionRows = actions.map { row ->
            val summary = when (row.type.lowercase(Locale.US)) {
                "temp_target" -> {
                    val target = payloadDouble(row.payloadJson, "targetMmol")
                    val duration = payloadDouble(row.payloadJson, "durationMinutes")?.toInt()
                    "temp_target ${target?.let { String.format(Locale.US, "%.2f", it) } ?: "-"} mmol/L ${duration ?: "-"}m"
                }
                "carbs" -> {
                    val grams = payloadDouble(row.payloadJson, "carbsGrams", "carbs", "grams")
                    "carbs ${grams?.let { String.format(Locale.US, "%.1f", it) } ?: "-"} g"
                }
                else -> row.type
            }
            AuditRecordRowUi(
                id = "action:${row.id}",
                ts = row.timestamp,
                source = "action",
                level = when (row.status.uppercase(Locale.US)) {
                    "FAILED" -> "ERROR"
                    "BLOCKED" -> "WARN"
                    "PENDING" -> "WARN"
                    else -> "INFO"
                },
                summary = summary,
                context = row.payloadJson.take(220),
                idempotencyKey = row.idempotencyKey,
                payloadSummary = row.payloadJson.take(220)
            )
        }
        return (auditRows + ruleRows + actionRows).sortedByDescending { it.ts }
    }

    private fun payloadField(payloadJson: String, key: String): String? {
        return runCatching {
            JSONObject(payloadJson).optString(key).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun payloadDouble(payloadJson: String, vararg keys: String): Double? {
        return runCatching {
            val obj = JSONObject(payloadJson)
            keys.firstNotNullOfOrNull { key ->
                obj.opt(key)?.toString()?.replace(",", ".")?.toDoubleOrNull()
            }
        }.getOrNull()
    }

    private data class ParsedRuleAction(
        val targetMmol: Double?,
        val durationMinutes: Int?
    )

    private fun parseRuleActionJson(actionJson: String): ParsedRuleAction? {
        return runCatching {
            val obj = JSONObject(actionJson)
            ParsedRuleAction(
                targetMmol = obj.optDouble("targetMmol").takeIf { !it.isNaN() },
                durationMinutes = obj.optInt("durationMinutes").takeIf { it > 0 }
            )
        }.getOrNull()
    }

    private fun parseRuleReasonsJson(reasonsJson: String): List<String> {
        return runCatching {
            val array = JSONArray(reasonsJson)
            buildList {
                for (i in 0 until array.length()) {
                    val raw = array.optString(i).trim()
                    if (raw.isNotBlank()) add(raw)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseReplayHotspotsJson(raw: String?): List<DailyReportReplayHotspotUi> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val horizonMinutes = item.optInt("horizonMinutes", 0)
                    val dayType = item.optString("dayType", "").trim().uppercase(Locale.US)
                    val hour = item.optInt("hour", -1)
                    val sampleCount = item.optInt("sampleCount", 0)
                    val mae = item.optDouble("mae", Double.NaN)
                    val mardPct = item.optDouble("mardPct", Double.NaN)
                    val bias = item.optDouble("bias", Double.NaN)
                    if (
                        horizonMinutes <= 0 ||
                        dayType !in setOf("WEEKDAY", "WEEKEND") ||
                        hour !in 0..23 ||
                        sampleCount <= 0 ||
                        !mae.isFinite() ||
                        !mardPct.isFinite() ||
                        !bias.isFinite()
                    ) {
                        continue
                    }
                    add(
                        DailyReportReplayHotspotUi(
                            horizonMinutes = horizonMinutes,
                            hour = hour,
                            sampleCount = sampleCount,
                            mae = mae,
                            mardPct = mardPct,
                            bias = bias
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedWith(
                compareBy<DailyReportReplayHotspotUi> { it.horizonMinutes }
                    .thenByDescending { it.mae }
            )
    }

    private fun parseReplayFactorsJson(raw: String?): List<DailyReportReplayFactorUi> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val horizonMinutes = item.optInt("horizonMinutes", 0)
                    val factor = item.optString("factor", "").trim()
                    val sampleCount = item.optInt("sampleCount", 0)
                    val corrAbsError = item.optDouble("corrAbsError", Double.NaN)
                    val maeHigh = item.optDouble("maeHigh", Double.NaN)
                    val maeLow = item.optDouble("maeLow", Double.NaN)
                    val upliftPct = item.optDouble("upliftPct", Double.NaN)
                    val contributionScore = item.optDouble("contributionScore", Double.NaN)
                    if (
                        horizonMinutes <= 0 ||
                        factor.isBlank() ||
                        sampleCount <= 0 ||
                        !corrAbsError.isFinite() ||
                        !maeHigh.isFinite() ||
                        !maeLow.isFinite() ||
                        !upliftPct.isFinite() ||
                        !contributionScore.isFinite()
                    ) {
                        continue
                    }
                    add(
                        DailyReportReplayFactorUi(
                            horizonMinutes = horizonMinutes,
                            factor = factor,
                            sampleCount = sampleCount,
                            corrAbsError = corrAbsError,
                            maeHigh = maeHigh,
                            maeLow = maeLow,
                            upliftPct = upliftPct,
                            contributionScore = contributionScore
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedWith(
                compareBy<DailyReportReplayFactorUi> { it.horizonMinutes }
                    .thenByDescending { it.contributionScore }
            )
    }

    private fun parseReplayCoverageJson(raw: String?): List<DailyReportReplayCoverageUi> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val horizonMinutes = item.optInt("horizonMinutes", 0)
                    val factor = item.optString("factor", "").trim()
                    val sampleCount = item.optInt("sampleCount", 0)
                    val coveragePct = item.optDouble("coveragePct", Double.NaN)
                    if (
                        horizonMinutes <= 0 ||
                        factor.isBlank() ||
                        sampleCount < 0 ||
                        !coveragePct.isFinite()
                    ) {
                        continue
                    }
                    add(
                        DailyReportReplayCoverageUi(
                            horizonMinutes = horizonMinutes,
                            factor = factor,
                            sampleCount = sampleCount,
                            coveragePct = coveragePct
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedWith(
                compareBy<DailyReportReplayCoverageUi> { it.horizonMinutes }
                    .thenBy { it.factor }
            )
    }

    private fun parseReplayRegimesJson(raw: String?): List<DailyReportReplayRegimeUi> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val horizonMinutes = item.optInt("horizonMinutes", 0)
                    val factor = item.optString("factor", "").trim()
                    val bucket = item.optString("bucket", "").trim().uppercase(Locale.US)
                    val sampleCount = item.optInt("sampleCount", 0)
                    val meanFactorValue = item.optDouble("meanFactorValue", Double.NaN)
                    val mae = item.optDouble("mae", Double.NaN)
                    val mardPct = item.optDouble("mardPct", Double.NaN)
                    val bias = item.optDouble("bias", Double.NaN)
                    if (
                        horizonMinutes <= 0 ||
                        factor.isBlank() ||
                        bucket !in setOf("LOW", "MID", "HIGH") ||
                        sampleCount <= 0 ||
                        !meanFactorValue.isFinite() ||
                        !mae.isFinite() ||
                        !mardPct.isFinite() ||
                        !bias.isFinite()
                    ) {
                        continue
                    }
                    add(
                        DailyReportReplayRegimeUi(
                            horizonMinutes = horizonMinutes,
                            factor = factor,
                            bucket = bucket,
                            sampleCount = sampleCount,
                            meanFactorValue = meanFactorValue,
                            mae = mae,
                            mardPct = mardPct,
                            bias = bias
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedWith(
                compareBy<DailyReportReplayRegimeUi> { it.horizonMinutes }
                    .thenBy { it.factor }
                    .thenBy {
                        when (it.bucket) {
                            "LOW" -> 0
                            "MID" -> 1
                            "HIGH" -> 2
                            else -> 3
                        }
                    }
            )
    }

    private fun parseReplayPairsJson(raw: String?): List<DailyReportReplayPairUi> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val horizonMinutes = item.optInt("horizonMinutes", 0)
                    val factorA = item.optString("factorA", "").trim()
                    val factorB = item.optString("factorB", "").trim()
                    val bucketA = item.optString("bucketA", "").trim().uppercase(Locale.US)
                    val bucketB = item.optString("bucketB", "").trim().uppercase(Locale.US)
                    val sampleCount = item.optInt("sampleCount", 0)
                    val meanFactorA = item.optDouble("meanFactorA", Double.NaN)
                    val meanFactorB = item.optDouble("meanFactorB", Double.NaN)
                    val mae = item.optDouble("mae", Double.NaN)
                    val mardPct = item.optDouble("mardPct", Double.NaN)
                    val bias = item.optDouble("bias", Double.NaN)
                    if (
                        horizonMinutes <= 0 ||
                        factorA.isBlank() ||
                        factorB.isBlank() ||
                        bucketA !in setOf("LOW", "HIGH") ||
                        bucketB !in setOf("LOW", "HIGH") ||
                        sampleCount <= 0 ||
                        !meanFactorA.isFinite() ||
                        !meanFactorB.isFinite() ||
                        !mae.isFinite() ||
                        !mardPct.isFinite() ||
                        !bias.isFinite()
                    ) {
                        continue
                    }
                    add(
                        DailyReportReplayPairUi(
                            horizonMinutes = horizonMinutes,
                            factorA = factorA,
                            factorB = factorB,
                            bucketA = bucketA,
                            bucketB = bucketB,
                            sampleCount = sampleCount,
                            meanFactorA = meanFactorA,
                            meanFactorB = meanFactorB,
                            mae = mae,
                            mardPct = mardPct,
                            bias = bias
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedWith(
                compareBy<DailyReportReplayPairUi> { it.horizonMinutes }
                    .thenBy { it.factorA }
                    .thenBy { it.factorB }
                    .thenBy {
                        when (it.bucketA) {
                            "LOW" -> 0
                            "HIGH" -> 1
                            else -> 2
                        }
                    }
                    .thenBy {
                        when (it.bucketB) {
                            "LOW" -> 0
                            "HIGH" -> 1
                            else -> 2
                        }
                    }
            )
    }

    private fun parseReplayTopMissJson(raw: String?): List<DailyReportReplayTopMissUi> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val horizonMinutes = item.optInt("horizonMinutes", 0)
                    val ts = item.optLong("ts", 0L)
                    val absError = item.optDouble("absError", Double.NaN)
                    val pred = item.optDouble("pred", Double.NaN)
                    val actual = item.optDouble("actual", Double.NaN)
                    val cob = item.optDouble("cob", Double.NaN)
                    val iob = item.optDouble("iob", Double.NaN)
                    val uam = item.optDouble("uam", Double.NaN)
                    val ciWidth = item.optDouble("ciWidth", Double.NaN)
                    val diaHours = item.optDouble("diaHours", Double.NaN)
                    val activity = item.optDouble("activity", Double.NaN)
                    val sensorQuality = item.optDouble("sensorQuality", Double.NaN)
                    if (
                        horizonMinutes <= 0 ||
                        ts <= 0L ||
                        !absError.isFinite() ||
                        !pred.isFinite() ||
                        !actual.isFinite() ||
                        !cob.isFinite() ||
                        !iob.isFinite() ||
                        !uam.isFinite() ||
                        !ciWidth.isFinite() ||
                        !diaHours.isFinite() ||
                        !activity.isFinite() ||
                        !sensorQuality.isFinite()
                    ) {
                        continue
                    }
                    add(
                        DailyReportReplayTopMissUi(
                            horizonMinutes = horizonMinutes,
                            ts = ts,
                            absError = absError,
                            pred = pred,
                            actual = actual,
                            cob = cob,
                            iob = iob,
                            uam = uam,
                            ciWidth = ciWidth,
                            diaHours = diaHours,
                            activity = activity,
                            sensorQuality = sensorQuality
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedBy { it.horizonMinutes }
    }

    private fun parseReplayErrorClustersJson(raw: String?): List<DailyReportReplayErrorClusterUi> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val horizonMinutes = item.optInt("horizonMinutes", 0)
                    val hour = item.optInt("hour", -1)
                    val dayType = item.optString("dayType", "").trim().uppercase(Locale.US)
                    val sampleCount = item.optInt("sampleCount", 0)
                    val mae = item.optDouble("mae", Double.NaN)
                    val mardPct = item.optDouble("mardPct", Double.NaN)
                    val bias = item.optDouble("bias", Double.NaN)
                    val meanCob = item.optDouble("meanCob", Double.NaN)
                    val meanIob = item.optDouble("meanIob", Double.NaN)
                    val meanUam = item.optDouble("meanUam", Double.NaN)
                    val meanCiWidth = item.optDouble("meanCiWidth", Double.NaN)
                    val dominantFactor = item.optString("dominantFactor", "").trim()
                        .takeIf { it.isNotBlank() }
                    val dominantScore = item.optDouble("dominantScore", Double.NaN)
                        .takeIf { it.isFinite() }
                    if (
                        horizonMinutes <= 0 ||
                        hour !in 0..23 ||
                        dayType !in setOf("WEEKDAY", "WEEKEND") ||
                        sampleCount <= 0 ||
                        !mae.isFinite() ||
                        !mardPct.isFinite() ||
                        !bias.isFinite() ||
                        !meanCob.isFinite() ||
                        !meanIob.isFinite() ||
                        !meanUam.isFinite() ||
                        !meanCiWidth.isFinite()
                    ) {
                        continue
                    }
                    add(
                        DailyReportReplayErrorClusterUi(
                            horizonMinutes = horizonMinutes,
                            hour = hour,
                            dayType = dayType,
                            sampleCount = sampleCount,
                            mae = mae,
                            mardPct = mardPct,
                            bias = bias,
                            meanCob = meanCob,
                            meanIob = meanIob,
                            meanUam = meanUam,
                            meanCiWidth = meanCiWidth,
                            dominantFactor = dominantFactor,
                            dominantScore = dominantScore
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedWith(
                compareBy<DailyReportReplayErrorClusterUi> { it.horizonMinutes }
                    .thenBy { it.dayType }
                    .thenByDescending { it.mae }
                    .thenByDescending { it.sampleCount }
            )
    }

    private fun parseReplayDayTypeGapsJson(raw: String?): List<DailyReportReplayDayTypeGapUi> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val horizonMinutes = item.optInt("horizonMinutes", 0)
                    val hour = item.optInt("hour", -1)
                    val worseDayType = item.optString("worseDayType", "").trim().uppercase(Locale.US)
                    val weekdaySampleCount = item.optInt("weekdaySampleCount", 0)
                    val weekendSampleCount = item.optInt("weekendSampleCount", 0)
                    val weekdayMae = item.optDouble("weekdayMae", Double.NaN)
                    val weekendMae = item.optDouble("weekendMae", Double.NaN)
                    val weekdayMardPct = item.optDouble("weekdayMardPct", Double.NaN)
                    val weekendMardPct = item.optDouble("weekendMardPct", Double.NaN)
                    val maeGapMmol = item.optDouble("maeGapMmol", Double.NaN)
                    val mardGapPct = item.optDouble("mardGapPct", Double.NaN)
                    val worseMeanCob = item.optDouble("worseMeanCob", Double.NaN)
                    val worseMeanIob = item.optDouble("worseMeanIob", Double.NaN)
                    val worseMeanUam = item.optDouble("worseMeanUam", Double.NaN)
                    val worseMeanCiWidth = item.optDouble("worseMeanCiWidth", Double.NaN)
                    val dominantFactor = item.optString("dominantFactor", "").trim()
                        .takeIf { it.isNotBlank() }
                    val dominantScore = item.optDouble("dominantScore", Double.NaN)
                        .takeIf { it.isFinite() }
                    if (
                        horizonMinutes <= 0 ||
                        hour !in 0..23 ||
                        worseDayType !in setOf("WEEKDAY", "WEEKEND") ||
                        weekdaySampleCount <= 0 ||
                        weekendSampleCount <= 0 ||
                        !weekdayMae.isFinite() ||
                        !weekendMae.isFinite() ||
                        !weekdayMardPct.isFinite() ||
                        !weekendMardPct.isFinite() ||
                        !maeGapMmol.isFinite() ||
                        !mardGapPct.isFinite() ||
                        !worseMeanCob.isFinite() ||
                        !worseMeanIob.isFinite() ||
                        !worseMeanUam.isFinite() ||
                        !worseMeanCiWidth.isFinite()
                    ) {
                        continue
                    }
                    add(
                        DailyReportReplayDayTypeGapUi(
                            horizonMinutes = horizonMinutes,
                            hour = hour,
                            worseDayType = worseDayType,
                            weekdaySampleCount = weekdaySampleCount,
                            weekendSampleCount = weekendSampleCount,
                            weekdayMae = weekdayMae,
                            weekendMae = weekendMae,
                            weekdayMardPct = weekdayMardPct,
                            weekendMardPct = weekendMardPct,
                            maeGapMmol = maeGapMmol,
                            mardGapPct = mardGapPct,
                            worseMeanCob = worseMeanCob,
                            worseMeanIob = worseMeanIob,
                            worseMeanUam = worseMeanUam,
                            worseMeanCiWidth = worseMeanCiWidth,
                            dominantFactor = dominantFactor,
                            dominantScore = dominantScore
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedWith(
                compareBy<DailyReportReplayDayTypeGapUi> { it.horizonMinutes }
                    .thenByDescending { kotlin.math.abs(it.maeGapMmol) }
                    .thenBy { it.hour }
            )
    }

    private fun parseSensorLagReplayBucketsJson(raw: String?): List<DailyReportSensorLagReplayUi> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val horizonMinutes = item.optInt("horizonMinutes", -1)
                    val bucket = item.optString("bucket", "").trim()
                    val sampleCount = item.optInt("sampleCount", 0)
                    val rawMae = item.optDouble("rawMae", Double.NaN)
                    val lagMae = item.optDouble("lagMae", Double.NaN)
                    val maeImprovement = item.optDouble("maeImprovementMmol", Double.NaN)
                    val rawBias = item.optDouble("rawBias", Double.NaN)
                    val lagBias = item.optDouble("lagBias", Double.NaN)
                    if (
                        horizonMinutes <= 0 ||
                        bucket.isBlank() ||
                        sampleCount <= 0 ||
                        !rawMae.isFinite() ||
                        !lagMae.isFinite() ||
                        !maeImprovement.isFinite() ||
                        !rawBias.isFinite() ||
                        !lagBias.isFinite()
                    ) {
                        continue
                    }
                    add(
                        DailyReportSensorLagReplayUi(
                            horizonMinutes = horizonMinutes,
                            bucket = bucket,
                            sampleCount = sampleCount,
                            rawMae = rawMae,
                            lagMae = lagMae,
                            maeImprovementMmol = maeImprovement,
                            rawBias = rawBias,
                            lagBias = lagBias
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedWith(
                compareBy<DailyReportSensorLagReplayUi> { it.horizonMinutes }
                    .thenBy { sensorLagBucketOrder(it.bucket) }
            )
    }

    private fun parseSensorLagShadowBucketsJson(raw: String?): List<DailyReportSensorLagShadowUi> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val bucket = item.optString("bucket", "").trim()
                    val sampleCount = item.optInt("sampleCount", 0)
                    val ruleChangedRatePct = item.optDouble("ruleChangedRatePct", Double.NaN)
                    val meanAbsTargetDeltaMmol = item.optDouble("meanAbsTargetDeltaMmol", Double.NaN)
                    if (
                        bucket.isBlank() ||
                        sampleCount <= 0 ||
                        !ruleChangedRatePct.isFinite()
                    ) {
                        continue
                    }
                    add(
                        DailyReportSensorLagShadowUi(
                            bucket = bucket,
                            sampleCount = sampleCount,
                            ruleChangedRatePct = ruleChangedRatePct,
                            meanAbsTargetDeltaMmol = meanAbsTargetDeltaMmol.takeIf { it.isFinite() }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
            .sortedBy { sensorLagBucketOrder(it.bucket) }
    }

    private fun sensorLagBucketOrder(bucket: String): Int = when (bucket) {
        "<24h" -> 0
        "1-10d" -> 1
        "10-12d" -> 2
        "12-14d" -> 3
        ">14d" -> 4
        else -> 5
    }

    private fun parseConfidenceFromReasons(reasons: List<String>): Double? {
        val marker = reasons.firstOrNull { it.startsWith("confidence=") } ?: return null
        return marker.substringAfter("=", "").replace(",", ".").toDoubleOrNull()
    }

    private fun auditMetaField(entry: AuditLogEntity, key: String): String? {
        return runCatching {
            val value = JSONObject(entry.metadataJson).opt(key)
            when (value) {
                null,
                JSONObject.NULL -> null
                is String -> value.takeIf { it.isNotBlank() }
                else -> value.toString()
            }
        }.getOrNull()
    }

    private fun buildRuleCooldownLines(
        executions: List<RuleExecutionEntity>,
        settings: AppSettings,
        nowTs: Long
    ): List<String> {
        val rules = listOf(
            Triple(AdaptiveTargetControllerRule.RULE_ID, "Adaptive", settings.adaptiveControllerRetargetMinutes),
            Triple("PostHypoReboundGuard.v1", "PostHypo", settings.rulePostHypoCooldownMinutes),
            Triple("PatternAdaptiveTarget.v1", "Pattern", settings.rulePatternCooldownMinutes),
            Triple("SegmentProfileGuard.v1", "Segment", settings.ruleSegmentCooldownMinutes)
        )
        return rules.map { (ruleId, title, cooldownMinutes) ->
            if (ruleId == AdaptiveTargetControllerRule.RULE_ID && !settings.adaptiveControllerEnabled) {
                return@map "$title: disabled"
            }
            if (cooldownMinutes <= 0) {
                return@map "$title: cooldown off"
            }
            val lastTriggeredTs = executions.firstOrNull {
                it.ruleId == ruleId && it.state == "TRIGGERED"
            }?.timestamp
            if (lastTriggeredTs == null) {
                return@map "$title: ready"
            }
            val remainingMs = cooldownMinutes * 60_000L - (nowTs - lastTriggeredTs)
            if (remainingMs <= 0) {
                "$title: ready"
            } else {
                val remainingMin = ((remainingMs + 59_999L) / 60_000L).coerceAtLeast(1L)
                "$title: ${remainingMin}m left (last ${formatTs(lastTriggeredTs)})"
            }
        }
    }

    private fun buildIsfCrActivationGateLines(
        audits: List<AuditLogEntity>,
        telemetryByKey: Map<String, TelemetrySampleEntity>
    ): List<String> {
        val latestKpi = audits
            .asSequence()
            .filter { it.message == "isfcr_shadow_activation_evaluated" }
            .maxByOrNull { it.timestamp }
        val latestQuality = audits
            .asSequence()
            .filter { it.message == "isfcr_shadow_quality_gate_evaluated" }
            .maxByOrNull { it.timestamp }
        val latestDayType = audits
            .asSequence()
            .filter { it.message == "isfcr_shadow_day_type_gate_evaluated" }
            .maxByOrNull { it.timestamp }
        val latestSensor = audits
            .asSequence()
            .filter { it.message == "isfcr_shadow_sensor_gate_evaluated" }
            .maxByOrNull { it.timestamp }
        val latestDataQualityRisk = audits
            .asSequence()
            .filter { it.message == "isfcr_shadow_data_quality_risk_gate_evaluated" }
            .maxByOrNull { it.timestamp }
        val latestRolling = audits
            .asSequence()
            .filter { it.message == "isfcr_shadow_rolling_gate_evaluated" }
            .maxByOrNull { it.timestamp }
        val latestPromoted = audits
            .asSequence()
            .filter { it.message == "isfcr_shadow_auto_promoted" }
            .maxByOrNull { it.timestamp }

        fun boolField(entry: AuditLogEntity?, key: String): Boolean? {
            val safeEntry = entry ?: return null
            return auditMetaField(safeEntry, key)?.toBooleanStrictOrNull()
        }

        fun intField(entry: AuditLogEntity?, key: String): Int? {
            val safeEntry = entry ?: return null
            return auditMetaField(safeEntry, key)?.toIntOrNull()
        }

        fun doubleField(entry: AuditLogEntity?, key: String): Double? {
            val safeEntry = entry ?: return null
            return auditMetaField(safeEntry, key)
                ?.replace(",", ".")
                ?.toDoubleOrNull()
        }

        fun fmt(value: Double?, decimals: Int = 2): String {
            return value?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: "--"
        }

        val lines = mutableListOf<String>()
        latestKpi?.let { entry ->
            val eligible = boolField(entry, "eligible")
            val reason = auditMetaField(entry, "reason") ?: "n/a"
            val sampleCount = intField(entry, "sampleCount")
            val meanConfidence = doubleField(entry, "meanConfidence")
            val meanIsfDelta = doubleField(entry, "meanAbsIsfDeltaPct")
            val meanCrDelta = doubleField(entry, "meanAbsCrDeltaPct")
            lines += buildString {
                append("KPI gate (${formatTs(entry.timestamp)}): eligible=${eligible ?: false}, reason=$reason")
                append(", n=${sampleCount ?: 0}, conf=${fmt(meanConfidence?.times(100.0), 0)}%")
                append(", |ΔISF|=${fmt(meanIsfDelta, 1)}%, |ΔCR|=${fmt(meanCrDelta, 1)}%")
            }
        }

        latestQuality?.let { entry ->
            val eligible = boolField(entry, "eligible")
            val reason = auditMetaField(entry, "reason") ?: "n/a"
            val matched = intField(entry, "matchedSamples")
            val mae30 = doubleField(entry, "mae30Mmol")
            val mae60 = doubleField(entry, "mae60Mmol")
            val hypo = doubleField(entry, "hypoRatePct24h")
            val cov30 = doubleField(entry, "ciCoverage30Pct")
            val cov60 = doubleField(entry, "ciCoverage60Pct")
            val width30 = doubleField(entry, "ciWidth30Mmol")
            val width60 = doubleField(entry, "ciWidth60Mmol")
            lines += buildString {
                append("Daily gate (${formatTs(entry.timestamp)}): eligible=${eligible ?: false}, reason=$reason")
                append(", n=${matched ?: 0}, MAE30=${fmt(mae30)}, MAE60=${fmt(mae60)}, hypo=${fmt(hypo, 1)}%")
            }
            lines += buildString {
                append("CI calib: cov30=${fmt(cov30, 1)}%, cov60=${fmt(cov60, 1)}%, width30=${fmt(width30)}, width60=${fmt(width60)}")
            }
        }

        latestDayType?.let { entry ->
            val eligible = boolField(entry, "eligible")
            val reason = auditMetaField(entry, "reason") ?: "n/a"
            val sampleCount = intField(entry, "sampleCount")
            val isfRatio = doubleField(entry, "meanIsfSameDayTypeRatio")
            val crRatio = doubleField(entry, "meanCrSameDayTypeRatio")
            val isfSparse = doubleField(entry, "isfSparseRatePct")
            val crSparse = doubleField(entry, "crSparseRatePct")
            lines += buildString {
                append("Day-type gate (${formatTs(entry.timestamp)}): eligible=${eligible ?: false}, reason=$reason")
                append(", n=${sampleCount ?: 0}, isfRatio=${fmt(isfRatio?.times(100.0), 0)}%, crRatio=${fmt(crRatio?.times(100.0), 0)}%")
                append(", isfSparse=${fmt(isfSparse, 1)}%, crSparse=${fmt(crSparse, 1)}%")
            }
        }

        latestSensor?.let { entry ->
            val eligible = boolField(entry, "eligible")
            val reason = auditMetaField(entry, "reason") ?: "n/a"
            val sampleCount = intField(entry, "sampleCount")
            val quality = doubleField(entry, "meanQualityScore")
            val sensorFactor = doubleField(entry, "meanSensorFactor")
            val wearPenalty = doubleField(entry, "meanWearPenalty")
            val sensorAgeHighRate = doubleField(entry, "sensorAgeHighRatePct")
            val suspectFalseLowRate = doubleField(entry, "suspectFalseLowRatePct")
            lines += buildString {
                append("Sensor gate (${formatTs(entry.timestamp)}): eligible=${eligible ?: false}, reason=$reason")
                append(", n=${sampleCount ?: 0}, quality=${fmt(quality?.times(100.0), 0)}%, factor=${fmt(sensorFactor?.times(100.0), 0)}%")
                append(", wearPenalty=${fmt(wearPenalty?.times(100.0), 1)}%, ageHigh=${fmt(sensorAgeHighRate, 1)}%")
                append(", falseLow=${fmt(suspectFalseLowRate, 1)}%")
            }
        }

        latestDataQualityRisk?.let { entry ->
            val eligible = boolField(entry, "eligible")
            val reason = auditMetaField(entry, "reason") ?: "n/a"
            val riskLevel = intField(entry, "riskLevel")
            val blockedRiskLevel = intField(entry, "blockedRiskLevel")
            val source = auditMetaField(entry, "riskLevelSource") ?: "unknown"
            lines += buildString {
                append("Data-quality risk gate (${formatTs(entry.timestamp)}): eligible=${eligible ?: false}, reason=$reason")
                append(", riskLevel=${riskLevel ?: 0}, blockAt=${blockedRiskLevel ?: 3}, source=$source")
            }
        }

        latestRolling?.let { entry ->
            val eligible = boolField(entry, "eligible")
            val reason = auditMetaField(entry, "reason") ?: "n/a"
            val requiredConfigured = intField(entry, "requiredWindowCountConfigured")
            val requiredEvaluated = intField(entry, "requiredWindowCount")
            val evaluated = intField(entry, "evaluatedWindowCount")
            val passed = intField(entry, "passedWindowCount")
            val maeRelax = doubleField(entry, "maeRelaxFactor")
            val ciCoverageRelax = doubleField(entry, "ciCoverageRelaxFactor")
            val ciWidthRelax = doubleField(entry, "ciWidthRelaxFactor")
            lines += buildString {
                append("Rolling gate (${formatTs(entry.timestamp)}): eligible=${eligible ?: false}, reason=$reason")
                append(", cfg=${requiredConfigured ?: requiredEvaluated ?: 0}, eval=${evaluated ?: 0}, pass=${passed ?: 0}")
            }
            lines += buildString {
                append("Rolling relax: MAE×${fmt(maeRelax)}, CIcov×${fmt(ciCoverageRelax)}, CIwidth×${fmt(ciWidthRelax)}")
            }
            lines += parseRollingGateWindows(auditMetaField(entry, "windows"))
                .map { window -> formatRollingGateWindowLine(window) { value -> fmt(value) } }
        }

        latestPromoted?.let { entry ->
            val reason = auditMetaField(entry, "reason") ?: "n/a"
            val qualityReason = auditMetaField(entry, "qualityReason") ?: "n/a"
            lines += "Last promotion (${formatTs(entry.timestamp)}): reason=$reason, qualityReason=$qualityReason"
        }

        val rollingKpiLines = listOf(14, 30, 90).mapNotNull { days ->
            val prefix = "rolling_report_${days}d"
            val matched = telemetryByKey["${prefix}_matched_samples"].toNumericValue()?.roundToInt()
            val mard30 = telemetryByKey["${prefix}_mard_30m_pct"].toNumericValue()
            val mard60 = telemetryByKey["${prefix}_mard_60m_pct"].toNumericValue()
            val hasAny = matched != null || mard30 != null || mard60 != null
            if (!hasAny) {
                null
            } else {
                buildString {
                    append("Rolling ${days}d: n=${matched ?: 0}")
                    mard30?.let { append(", MARD30=${fmt(it, 1)}%") }
                    mard60?.let { append(", MARD60=${fmt(it, 1)}%") }
                }
            }
        }
        lines += rollingKpiLines
        return lines
    }

    private data class IsfCrRuntimeDiagnosticsSnapshot(
        val realtimeTs: Long?,
        val mode: String?,
        val confidence: Double?,
        val confidenceThreshold: Double?,
        val qualityScore: Double?,
        val usedEvidence: Int?,
        val droppedEvidence: Int?,
        val droppedReasons: String?,
        val currentDayType: String?,
        val isfBaseSource: String?,
        val crBaseSource: String?,
        val isfDayTypeBaseAvailable: Boolean?,
        val crDayTypeBaseAvailable: Boolean?,
        val hourWindowIsfEvidence: Int?,
        val hourWindowCrEvidence: Int?,
        val hourWindowIsfSameDayType: Int?,
        val hourWindowCrSameDayType: Int?,
        val minIsfEvidencePerHour: Int?,
        val minCrEvidencePerHour: Int?,
        val crMaxGapMinutes: Double?,
        val crMaxSensorBlockedRatePct: Double?,
        val crMaxUamAmbiguityRatePct: Double?,
        val coverageHoursIsf: Int?,
        val coverageHoursCr: Int?,
        val realtimeReasons: String?,
        val lowConfidenceTs: Long?,
        val lowConfidenceReasons: String?,
        val fallbackTs: Long?,
        val fallbackReasons: String?
    )

    private fun buildIsfCrRuntimeDiagnosticsSnapshot(
        audits: List<AuditLogEntity>
    ): IsfCrRuntimeDiagnosticsSnapshot? {
        val latestRealtime = audits
            .asSequence()
            .filter { it.message == "isfcr_realtime_computed" }
            .maxByOrNull { it.timestamp }
        val latestLowConfidence = audits
            .asSequence()
            .filter { it.message == "isfcr_low_confidence" }
            .maxByOrNull { it.timestamp }
        val latestFallback = audits
            .asSequence()
            .filter { it.message == "isfcr_fallback_applied" }
            .maxByOrNull { it.timestamp }

        if (latestRealtime == null && latestLowConfidence == null && latestFallback == null) return null

        fun doubleField(entry: AuditLogEntity?, key: String): Double? {
            val safe = entry ?: return null
            return auditMetaField(safe, key)
                ?.replace(",", ".")
                ?.toDoubleOrNull()
        }

        fun intField(entry: AuditLogEntity?, key: String): Int? {
            val safe = entry ?: return null
            return auditMetaField(safe, key)?.toIntOrNull()
        }

        return IsfCrRuntimeDiagnosticsSnapshot(
            realtimeTs = latestRealtime?.timestamp,
            mode = latestRealtime?.let { auditMetaField(it, "mode") },
            confidence = doubleField(latestRealtime, "confidence"),
            confidenceThreshold = doubleField(latestRealtime, "confidenceThreshold"),
            qualityScore = doubleField(latestRealtime, "qualityScore"),
            usedEvidence = intField(latestRealtime, "usedEvidence"),
            droppedEvidence = intField(latestRealtime, "droppedEvidence"),
            droppedReasons = latestRealtime?.let { auditMetaField(it, "droppedReasons") },
            currentDayType = latestRealtime?.let { auditMetaField(it, "currentDayType") },
            isfBaseSource = latestRealtime?.let { auditMetaField(it, "isfBaseSource") },
            crBaseSource = latestRealtime?.let { auditMetaField(it, "crBaseSource") },
            isfDayTypeBaseAvailable = latestRealtime?.let {
                doubleField(it, "isfDayTypeBaseAvailable")?.let { value -> value >= 0.5 }
            },
            crDayTypeBaseAvailable = latestRealtime?.let {
                doubleField(it, "crDayTypeBaseAvailable")?.let { value -> value >= 0.5 }
            },
            hourWindowIsfEvidence = intField(latestRealtime, "hourWindowIsfEvidence"),
            hourWindowCrEvidence = intField(latestRealtime, "hourWindowCrEvidence"),
            hourWindowIsfSameDayType = intField(latestRealtime, "hourWindowIsfSameDayType"),
            hourWindowCrSameDayType = intField(latestRealtime, "hourWindowCrSameDayType"),
            minIsfEvidencePerHour = intField(latestRealtime, "minIsfEvidencePerHour"),
            minCrEvidencePerHour = intField(latestRealtime, "minCrEvidencePerHour"),
            crMaxGapMinutes = doubleField(latestRealtime, "crMaxGapMinutes"),
            crMaxSensorBlockedRatePct = doubleField(latestRealtime, "crMaxSensorBlockedRatePct"),
            crMaxUamAmbiguityRatePct = doubleField(latestRealtime, "crMaxUamAmbiguityRatePct"),
            coverageHoursIsf = intField(latestRealtime, "coverageHoursIsf"),
            coverageHoursCr = intField(latestRealtime, "coverageHoursCr"),
            realtimeReasons = latestRealtime?.let { auditMetaField(it, "reasons") },
            lowConfidenceTs = latestLowConfidence?.timestamp,
            lowConfidenceReasons = latestLowConfidence?.let { auditMetaField(it, "reasons") },
            fallbackTs = latestFallback?.timestamp,
            fallbackReasons = latestFallback?.let { auditMetaField(it, "reasons") }
        )
    }

    private fun buildIsfCrRuntimeDiagnosticsLines(
        snapshot: IsfCrRuntimeDiagnosticsSnapshot?
    ): List<String> {
        snapshot ?: return emptyList()

        fun fmt(value: Double?, decimals: Int = 2): String {
            return value?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: "--"
        }

        return buildList {
            if (snapshot.realtimeTs != null) {
                val mode = snapshot.mode ?: "n/a"
                add(
                    "Runtime (${formatTs(snapshot.realtimeTs)}): mode=$mode, " +
                        "conf=${fmt(snapshot.confidence?.times(100.0), 0)}% " +
                        "(thr=${fmt(snapshot.confidenceThreshold?.times(100.0), 0)}%), " +
                        "quality=${fmt(snapshot.qualityScore?.times(100.0), 0)}%"
                )
                add(
                    "Evidence: used=${snapshot.usedEvidence ?: 0}, dropped=${snapshot.droppedEvidence ?: 0}, " +
                        "coverage ISF/CR=${snapshot.coverageHoursIsf ?: 0}/${snapshot.coverageHoursCr ?: 0} hours"
                )
                if (
                    snapshot.isfBaseSource != null ||
                    snapshot.crBaseSource != null ||
                    snapshot.isfDayTypeBaseAvailable != null ||
                    snapshot.crDayTypeBaseAvailable != null
                ) {
                    add(
                        "Base source ISF/CR=${snapshot.isfBaseSource ?: "--"}/${snapshot.crBaseSource ?: "--"}, " +
                            "day-type available=${snapshot.isfDayTypeBaseAvailable ?: false}/${snapshot.crDayTypeBaseAvailable ?: false}"
                    )
                }
                if (
                    snapshot.hourWindowIsfEvidence != null ||
                    snapshot.hourWindowCrEvidence != null ||
                    snapshot.minIsfEvidencePerHour != null ||
                    snapshot.minCrEvidencePerHour != null
                ) {
                    add(
                        "Hour-window evidence ISF/CR=${snapshot.hourWindowIsfEvidence ?: 0}/${snapshot.hourWindowCrEvidence ?: 0} " +
                            "(min ${snapshot.minIsfEvidencePerHour ?: 0}/${snapshot.minCrEvidencePerHour ?: 0})"
                    )
                }
                if (
                    snapshot.crMaxGapMinutes != null ||
                    snapshot.crMaxSensorBlockedRatePct != null ||
                    snapshot.crMaxUamAmbiguityRatePct != null
                ) {
                    add(
                        "CR gate: gap<=${fmt(snapshot.crMaxGapMinutes, 0)}m, " +
                            "sensorBlocked<=${fmt(snapshot.crMaxSensorBlockedRatePct, 0)}%, " +
                            "uamAmbiguity<=${fmt(snapshot.crMaxUamAmbiguityRatePct, 0)}%"
                    )
                }
                if (
                    snapshot.currentDayType != null ||
                    snapshot.hourWindowIsfSameDayType != null ||
                    snapshot.hourWindowCrSameDayType != null
                ) {
                    add(
                        "Day-type evidence (${snapshot.currentDayType ?: "n/a"}) ISF/CR=" +
                            "${snapshot.hourWindowIsfSameDayType ?: 0}/${snapshot.hourWindowCrSameDayType ?: 0}"
                    )
                }
                snapshot.droppedReasons
                    ?.takeIf { it.isNotBlank() }
                    ?.let { add("Dropped reasons: $it") }
                snapshot.realtimeReasons
                    ?.takeIf { it.isNotBlank() }
                    ?.let { add("Runtime reasons: $it") }
            }
            if (snapshot.lowConfidenceTs != null) {
                add("Low confidence (${formatTs(snapshot.lowConfidenceTs)}): reasons=${snapshot.lowConfidenceReasons ?: "n/a"}")
            }
            if (snapshot.fallbackTs != null) {
                add("Fallback (${formatTs(snapshot.fallbackTs)}): reasons=${snapshot.fallbackReasons ?: "n/a"}")
            }
        }
    }

    private fun buildIsfCrDroppedReasonSummaryLines(
        audits: List<AuditLogEntity>,
        nowTs: Long,
        windowMs: Long,
        topLimit: Int = 8
    ): List<String> {
        val cutoffTs = nowTs - windowMs
        val primaryEvents = audits.filter {
            it.timestamp >= cutoffTs && it.message == "isfcr_evidence_extracted"
        }
        val selected = if (primaryEvents.isNotEmpty()) {
            primaryEvents
        } else {
            audits.filter {
                it.timestamp >= cutoffTs && it.message == "isfcr_realtime_computed"
            }
        }
        if (selected.isEmpty()) return emptyList()

        val reasonCounts = linkedMapOf<String, Int>()
        var droppedTotal = 0
        selected.forEach { entry ->
            droppedTotal += auditMetaField(entry, "droppedEvidence")?.toIntOrNull() ?: 0
            parseDroppedReasonCounters(auditMetaField(entry, "droppedReasons")).forEach { (reason, count) ->
                reasonCounts[reason] = (reasonCounts[reason] ?: 0) + count
            }
        }
        return formatIsfCrDroppedReasonSummaryLines(
            eventCount = selected.size,
            droppedTotal = droppedTotal,
            reasonCounts = reasonCounts,
            topLimit = topLimit
        )
    }

    private fun buildIsfCrWearImpactSummaryLines(
        audits: List<AuditLogEntity>,
        nowTs: Long,
        windowMs: Long
    ): List<String> {
        val cutoffTs = nowTs - windowMs
        val events = audits.filter {
            it.timestamp >= cutoffTs && it.message == "isfcr_realtime_computed"
        }
        if (events.isEmpty()) return emptyList()

        val setAgeValues = events.mapNotNull { auditMetaDouble(it, "setAgeHours") }
        val sensorAgeValues = events.mapNotNull { auditMetaDouble(it, "sensorAgeHours") }
        val setFactorValues = events.mapNotNull { auditMetaDouble(it, "setFactor") }
        val sensorFactorValues = events.mapNotNull { auditMetaDouble(it, "sensorFactor") }
        val ambiguityValues = events.mapNotNull { auditMetaDouble(it, "contextAmbiguity") }
        val wearPenaltyValues = events.mapNotNull { auditMetaDouble(it, "wearConfidencePenalty") }
        val confidenceValues = events.mapNotNull { auditMetaDouble(it, "confidence") }
        val dayTypeValues = events.mapNotNull { auditMetaField(it, "currentDayType")?.uppercase(Locale.US) }
        val weekdayCount = dayTypeValues.count { it == "WEEKDAY" }
        val weekendCount = dayTypeValues.count { it == "WEEKEND" }
        val isfSameDayTypeRatios = events.mapNotNull { entry ->
            val total = auditMetaDouble(entry, "hourWindowIsfEvidence") ?: return@mapNotNull null
            val same = auditMetaDouble(entry, "hourWindowIsfSameDayType") ?: return@mapNotNull null
            if (total <= 0.0) return@mapNotNull null
            (same / total).coerceIn(0.0, 1.0)
        }
        val crSameDayTypeRatios = events.mapNotNull { entry ->
            val total = auditMetaDouble(entry, "hourWindowCrEvidence") ?: return@mapNotNull null
            val same = auditMetaDouble(entry, "hourWindowCrSameDayType") ?: return@mapNotNull null
            if (total <= 0.0) return@mapNotNull null
            (same / total).coerceIn(0.0, 1.0)
        }
        val isfDayTypeSparseCount = events.count { auditReasonSet(it).contains("isf_day_type_evidence_sparse") }
        val crDayTypeSparseCount = events.count { auditReasonSet(it).contains("cr_day_type_evidence_sparse") }

        val setHigh = setAgeValues.count { it > 72.0 }
        val sensorHigh = sensorAgeValues.count { it > 120.0 }
        val setCount = setAgeValues.size.coerceAtLeast(1)
        val sensorCount = sensorAgeValues.size.coerceAtLeast(1)
        val meanSetAge = setAgeValues.average().takeIf { !it.isNaN() }
        val meanSensorAge = sensorAgeValues.average().takeIf { !it.isNaN() }
        val meanSetFactor = setFactorValues.average().takeIf { !it.isNaN() }
        val meanSensorFactor = sensorFactorValues.average().takeIf { !it.isNaN() }
        val meanAmbiguity = ambiguityValues.average().takeIf { !it.isNaN() }
        val meanWearPenalty = wearPenaltyValues.average().takeIf { !it.isNaN() }
        val meanConfidence = confidenceValues.average().takeIf { !it.isNaN() }
        val meanIsfSameDayTypeRatio = isfSameDayTypeRatios.average().takeIf { !it.isNaN() }
        val meanCrSameDayTypeRatio = crSameDayTypeRatios.average().takeIf { !it.isNaN() }

        fun fmt(value: Double?, digits: Int = 2): String {
            return value?.let { String.format(Locale.US, "%.${digits}f", it) } ?: "--"
        }

        return buildList {
            add(
                "Events=${events.size}, mean set/sensor age=${fmt(meanSetAge, 1)}h/${fmt(meanSensorAge, 1)}h"
            )
            add(
                "High wear set>72h=${(setHigh * 100.0 / setCount).toInt()}%, sensor>120h=${(sensorHigh * 100.0 / sensorCount).toInt()}%"
            )
            if (meanSetFactor != null || meanSensorFactor != null) {
                add("Mean factors set/sensor=${fmt(meanSetFactor)}/${fmt(meanSensorFactor)}")
            }
            if (dayTypeValues.isNotEmpty()) {
                add(
                    "Day type distribution: weekday=${(weekdayCount * 100.0 / dayTypeValues.size).toInt()}%, " +
                        "weekend=${(weekendCount * 100.0 / dayTypeValues.size).toInt()}%"
                )
            }
            if (meanIsfSameDayTypeRatio != null || meanCrSameDayTypeRatio != null) {
                add(
                    "Mean same-day-type ratio ISF/CR=" +
                        "${fmt(meanIsfSameDayTypeRatio?.times(100.0), 0)}%/${fmt(meanCrSameDayTypeRatio?.times(100.0), 0)}%"
                )
            }
            add(
                "Day-type sparse flags ISF/CR=" +
                    "${(isfDayTypeSparseCount * 100.0 / events.size).toInt()}%/" +
                    "${(crDayTypeSparseCount * 100.0 / events.size).toInt()}%"
            )
            if (meanAmbiguity != null || meanWearPenalty != null) {
                add(
                    "Ambiguity=${fmt(meanAmbiguity?.times(100.0), 0)}%, wear penalty=${fmt(meanWearPenalty?.times(100.0), 0)}%"
                )
            }
            if (meanConfidence != null) {
                add("Mean confidence=${fmt(meanConfidence * 100.0, 0)}%")
            }
        }
    }

    private fun parseDroppedReasonCounters(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        val counters = linkedMapOf<String, Int>()
        raw.split(';', ',', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { token ->
                val parts = token.split("=", limit = 2)
                val key = parts[0].trim()
                if (key.isBlank()) return@forEach
                val count = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 1
                counters[key] = (counters[key] ?: 0) + count.coerceAtLeast(0)
            }
        return counters
    }

    private fun auditMetaDouble(entry: AuditLogEntity, key: String): Double? {
        return auditMetaField(entry, key)
            ?.replace(",", ".")
            ?.toDoubleOrNull()
    }

    private fun auditReasonSet(entry: AuditLogEntity): Set<String> {
        val raw = auditMetaField(entry, "reasons").orEmpty()
        if (raw.isBlank()) return emptySet()
        return raw
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private data class TelemetryCoverageSpec(
        val primaryKey: String,
        val label: String,
        val staleThresholdMin: Long,
        val exactAliases: List<String> = emptyList(),
        val tokenAliases: List<String> = emptyList()
    )

    private fun telemetryCoverageSpecs(): List<TelemetryCoverageSpec> = listOf(
        TelemetryCoverageSpec(
            primaryKey = "iob_units",
            label = "IOB",
            staleThresholdMin = 30L,
            exactAliases = listOf("iob_real_units", "iob_effective_units", "raw_iob"),
            tokenAliases = listOf("iob", "insulinonboard")
        ),
        TelemetryCoverageSpec(
            primaryKey = "insulin_real_onset_min",
            label = "Insulin onset real",
            staleThresholdMin = 6 * 60L,
            tokenAliases = listOf("insulin_real_onset")
        ),
        TelemetryCoverageSpec(
            primaryKey = "cob_effective_grams",
            label = "COB",
            staleThresholdMin = 30L,
            exactAliases = listOf("cob_grams", "raw_cob"),
            tokenAliases = listOf("cob", "carbsonboard")
        ),
        TelemetryCoverageSpec(
            primaryKey = "carbs_grams",
            label = "Carbs",
            staleThresholdMin = 240L,
            exactAliases = listOf("status_carbs_grams", "raw_carbs"),
            tokenAliases = listOf("carbs", "enteredcarbs", "mealcarbs")
        ),
        TelemetryCoverageSpec(
            primaryKey = "insulin_units",
            label = "Insulin",
            staleThresholdMin = 240L,
            exactAliases = listOf("status_insulin_units", "raw_insulin", "raw_insulin_units"),
            tokenAliases = listOf("insulin_units", "bolusunits", "enteredinsulin")
        ),
        TelemetryCoverageSpec(
            primaryKey = "dia_effective_hours",
            label = "DIA",
            staleThresholdMin = 24 * 60L,
            exactAliases = listOf("dia_profile_hours", "dia_hours"),
            tokenAliases = listOf("dia", "insulinactiontime")
        ),
        TelemetryCoverageSpec(
            primaryKey = "steps_count",
            label = "Steps",
            staleThresholdMin = 24 * 60L,
            tokenAliases = listOf("steps", "stepcount")
        ),
        TelemetryCoverageSpec(
            primaryKey = "activity_ratio",
            label = "Activity ratio",
            staleThresholdMin = 180L,
            tokenAliases = listOf("activity", "activityratio", "sensitivityratio")
        ),
        TelemetryCoverageSpec(
            primaryKey = "distance_km",
            label = "Distance",
            staleThresholdMin = 24 * 60L,
            tokenAliases = listOf("distance", "distancekm")
        ),
        TelemetryCoverageSpec(
            primaryKey = "active_minutes",
            label = "Active minutes",
            staleThresholdMin = 24 * 60L,
            tokenAliases = listOf("activeminutes", "exerciseminutes")
        ),
        TelemetryCoverageSpec(
            primaryKey = "calories_active_kcal",
            label = "Active calories",
            staleThresholdMin = 24 * 60L,
            tokenAliases = listOf("activecalories", "caloriesactive")
        ),
        TelemetryCoverageSpec(
            primaryKey = "heart_rate_bpm",
            label = "Heart rate",
            staleThresholdMin = 180L,
            tokenAliases = listOf("heart", "heartrate")
        ),
        TelemetryCoverageSpec(
            primaryKey = "uam_inferred_flag",
            label = "UAM inferred",
            staleThresholdMin = 180L,
            exactAliases = listOf(
                "uam_calculated_flag",
                "uam_value",
                "uam_detected",
                "unannounced_meal",
                "has_uam",
                "is_uam"
            )
        ),
        TelemetryCoverageSpec(
            primaryKey = "isf_value",
            label = "ISF",
            staleThresholdMin = 7 * 24 * 60L,
            tokenAliases = listOf("isf", "sens", "sensitivity")
        ),
        TelemetryCoverageSpec(
            primaryKey = "cr_value",
            label = "CR",
            staleThresholdMin = 7 * 24 * 60L,
            tokenAliases = listOf("cr", "carb_ratio", "carbratio", "icratio")
        )
    )

    private fun buildTelemetryCoverageLines(
        samples: List<TelemetrySampleEntity>,
        latestByKey: Map<String, TelemetrySampleEntity>,
        therapyEvents: List<TherapyEventEntity>,
        profile: ProfileEstimateEntity?,
        nowTs: Long
    ): List<String> {
        val therapyFallback = resolveTherapyFallback(therapyEvents)
        val lines = telemetryCoverageSpecs().map { spec ->
            val sample = resolveTelemetrySample(spec, latestByKey)
            val fallback = resolveCoverageFallback(spec, therapyFallback, profile, nowTs)
            if (sample == null) {
                fallback ?: "${spec.label}: MISSING"
            } else if (fallback != null && shouldPreferFallback(spec, sample)) {
                "$fallback [override]"
            } else {
                val ageMin = ((nowTs - sample.timestamp).coerceAtLeast(0L)) / 60_000L
                val freshness = if (ageMin <= spec.staleThresholdMin) "fresh" else "stale ${ageMin}m"
                "${spec.label}: ${formatTelemetryLine(sample)} [$freshness]"
            }
        }
        return if (samples.isEmpty()) {
            listOf("Telemetry stream is empty; fallback/profile values are shown when available.") + lines
        } else {
            lines
        }
    }

    private fun buildTelemetryLines(
        samples: List<TelemetrySampleEntity>,
        latestByKey: Map<String, TelemetrySampleEntity>
    ): List<String> {
        if (samples.isEmpty()) return emptyList()
        val primaryKeys = listOf(
            "iob_units",
            "iob_effective_units",
            "iob_real_units",
            "cob_effective_grams",
            "cob_grams",
            "insulin_real_onset_min",
            "insulin_profile_base_onset_min",
            "insulin_real_onset_samples",
            "insulin_profile_real_confidence",
            "insulin_profile_real_samples",
            "insulin_profile_real_onset_min",
            "insulin_profile_real_peak_min",
            "insulin_profile_real_scale",
            "insulin_profile_real_status",
            "insulin_profile_real_source_profile",
            "insulin_profile_real_updated_ts",
            "insulin_profile_real_published_ts",
            "iob_external_raw_units",
            "cob_external_raw_grams",
            "iob_local_fallback_units",
            "cob_local_fallback_grams",
            "carbs_grams",
            "insulin_units",
            "dia_effective_hours",
            "dia_real_raw_hours",
            "dia_profile_hours",
            "dia_external_raw_hours",
            "dia_effective_source",
            "dia_hours",
            "steps_count",
            "activity_ratio",
            "activity_label",
            "distance_km",
            "active_minutes",
            "calories_active_kcal",
            "heart_rate_bpm",
            "uam_inferred_flag",
            "uam_inferred_confidence",
            "uam_inferred_carbs_grams",
            "uam_inferred_ingestion_ts",
            "uam_inferred_mode",
            "uam_inferred_boost_mode",
            "uam_manual_cob_grams",
            "uam_inferred_gabs_last5_g",
            "uam_calculated_flag",
            "uam_calculated_confidence",
            "uam_calculated_carbs_grams",
            "uam_calculated_delta5_mmol",
            "uam_calculated_rise15_mmol",
            "uam_calculated_rise30_mmol",
            "uam_value",
            "sensor_quality_score",
            "sensor_quality_blocked",
            "sensor_quality_reason",
            "sensor_quality_suspect_false_low",
            "sensor_quality_delta5_mmol",
            "sensor_quality_noise_std5",
            "sensor_quality_gap_min",
            "isf_value",
            "cr_value",
            "basal_rate_u_h",
            "insulin_req_units",
            "temp_target_low_mmol",
            "temp_target_high_mmol",
            "temp_target_duration_min",
            "profile_percent"
        )
        val syntheticPrimary = telemetryCoverageSpecs()
        val resolvedSyntheticKeys = mutableSetOf<String>()
        val primaryLines = primaryKeys.mapNotNull { key ->
            latestByKey[key]?.let { sample -> formatTelemetryLine(sample) }
        } + syntheticPrimary.mapNotNull { spec ->
            val sample = resolveTelemetrySample(spec, latestByKey) ?: return@mapNotNull null
            resolvedSyntheticKeys += sample.key
            if (sample.key in primaryKeys) return@mapNotNull null
            formatTelemetryLine(sample)
        }
        val otherLines = latestByKey
            .filterKeys { it !in primaryKeys && it !in resolvedSyntheticKeys }
            .values
            .sortedBy { it.key }
            .map { sample -> formatTelemetryLine(sample) }
        return primaryLines + otherLines
    }

    private fun latestTelemetryByKey(
        samples: List<TelemetrySampleEntity>,
        nowTs: Long = System.currentTimeMillis()
    ): Map<String, TelemetrySampleEntity> {
        if (samples.isEmpty()) return emptyMap()
        val todayStart = Instant.ofEpochMilli(nowTs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val latestByKey = HashMap<String, TelemetrySampleEntity>(256)
        val cumulativeTodayMax = HashMap<String, TelemetrySampleEntity>(CUMULATIVE_ACTIVITY_KEYS.size)
        val cumulativeAllTimeMax = HashMap<String, TelemetrySampleEntity>(CUMULATIVE_ACTIVITY_KEYS.size)

        samples.forEach { sample ->
            if (!isTelemetrySampleUsable(sample)) return@forEach

            val key = sample.key
            val latest = latestByKey[key]
            if (latest == null || TelemetrySampleSelector.isPreferred(sample, latest)) {
                latestByKey[key] = sample
            }

            if (key !in CUMULATIVE_ACTIVITY_KEYS) return@forEach

            val previousAllTimeMax = cumulativeAllTimeMax[key]
            if (previousAllTimeMax == null || sample.isHigherCumulativeSampleThan(previousAllTimeMax)) {
                cumulativeAllTimeMax[key] = sample
            }

            if (sample.timestamp >= todayStart) {
                val previousTodayMax = cumulativeTodayMax[key]
                if (previousTodayMax == null || sample.isHigherCumulativeSampleThan(previousTodayMax)) {
                    cumulativeTodayMax[key] = sample
                }
            }
        }

        CUMULATIVE_ACTIVITY_KEYS.forEach { key ->
            val preferred = cumulativeTodayMax[key] ?: cumulativeAllTimeMax[key]
            if (preferred != null) {
                latestByKey[key] = preferred
            }
        }

        return latestByKey
    }

    private fun resolveAiTuningStatus(
        telemetryByKey: Map<String, TelemetrySampleEntity>,
        nowTs: Long
    ): AiTuningStatusSnapshot {
        val generatedTs = telemetryByKey["daily_report_ai_opt_generated_ts"]
            .toNumericValue()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.toLong()
        val applyFlag = (telemetryByKey["daily_report_ai_opt_apply_flag"].toNumericValue() ?: 0.0) >= 0.5
        val confidence = telemetryByKey["daily_report_ai_opt_confidence"]
            .toNumericValue()
            ?.takeIf { it.isFinite() }
            ?.coerceIn(0.0, 1.0)
        val statusRaw = telemetryByKey["daily_report_ai_opt_status"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val reasonRaw = telemetryByKey["daily_report_ai_opt_reason"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val errorRaw = telemetryByKey["daily_report_ai_opt_error"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val matchedSamples = telemetryByKey["daily_report_matched_samples"]
            .toNumericValue()
            ?.takeIf { it.isFinite() }
            ?.coerceAtLeast(0.0)
        val riskLevel = telemetryByKey["daily_report_isfcr_quality_risk_level"]
            .toNumericValue()
            ?.takeIf { it.isFinite() }
            ?.coerceAtLeast(0.0)

        if (generatedTs == null) {
            return AiTuningStatusSnapshot(
                state = AiTuningState.BLOCKED,
                reason = "no optimizer report yet",
                generatedTs = null,
                confidence = confidence,
                statusRaw = statusRaw
            )
        }

        if (generatedTs > nowTs + AI_TUNING_FUTURE_SKEW_TOLERANCE_MS) {
            return AiTuningStatusSnapshot(
                state = AiTuningState.BLOCKED,
                reason = "invalid future report timestamp",
                generatedTs = generatedTs,
                confidence = confidence,
                statusRaw = statusRaw
            )
        }

        val ageMs = (nowTs - generatedTs).coerceAtLeast(0L)
        if (ageMs > AI_TUNING_MAX_AGE_MS) {
            return AiTuningStatusSnapshot(
                state = AiTuningState.STALE,
                reason = "report older than ${AI_TUNING_MAX_AGE_HOURS}h",
                generatedTs = generatedTs,
                confidence = confidence,
                statusRaw = statusRaw
            )
        }

        if (!applyFlag) {
            val reason = when {
                !errorRaw.isNullOrBlank() -> normalizeAiTuningError(errorRaw)
                !reasonRaw.isNullOrBlank() -> reasonRaw
                !statusRaw.isNullOrBlank() -> normalizeAiTuningStatusRaw(statusRaw)
                else -> "apply flag is off"
            }
            return AiTuningStatusSnapshot(
                state = AiTuningState.BLOCKED,
                reason = reason,
                generatedTs = generatedTs,
                confidence = confidence,
                statusRaw = statusRaw
            )
        }

        if ((confidence ?: 0.0) < AI_TUNING_MIN_CONFIDENCE) {
            return AiTuningStatusSnapshot(
                state = AiTuningState.BLOCKED,
                reason = "confidence ${String.format(Locale.US, "%.0f%%", (confidence ?: 0.0) * 100.0)} below ${String.format(Locale.US, "%.0f%%", AI_TUNING_MIN_CONFIDENCE * 100.0)}",
                generatedTs = generatedTs,
                confidence = confidence,
                statusRaw = statusRaw
            )
        }

        if ((matchedSamples ?: 0.0) < AI_TUNING_MIN_MATCHED_SAMPLES) {
            return AiTuningStatusSnapshot(
                state = AiTuningState.BLOCKED,
                reason = "matched samples ${matchedSamples?.toInt() ?: 0} < ${AI_TUNING_MIN_MATCHED_SAMPLES.toInt()}",
                generatedTs = generatedTs,
                confidence = confidence,
                statusRaw = statusRaw
            )
        }

        if ((riskLevel ?: 0.0) >= AI_TUNING_BLOCK_RISK_LEVEL) {
            return AiTuningStatusSnapshot(
                state = AiTuningState.BLOCKED,
                reason = "daily risk gate level ${riskLevel?.toInt() ?: 0}",
                generatedTs = generatedTs,
                confidence = confidence,
                statusRaw = statusRaw
            )
        }

        return AiTuningStatusSnapshot(
            state = AiTuningState.ACTIVE,
            reason = reasonRaw ?: "optimizer tuning applied",
            generatedTs = generatedTs,
            confidence = confidence,
            statusRaw = statusRaw
        )
    }

    private fun normalizeAiTuningError(raw: String): String {
        val text = raw.trim()
        val normalized = text.lowercase(Locale.US)
        return when {
            normalized.contains("read timed out") -> "timeout while waiting for OpenAI response"
            normalized.contains("empty structured output") -> "OpenAI returned no valid structured JSON"
            normalized.contains("api key is empty") -> "AI API key is empty"
            normalized.contains("401") || normalized.contains("unauthorized") -> "OpenAI authorization failed"
            normalized.contains("429") || normalized.contains("rate limit") -> "OpenAI rate limit reached"
            normalized.contains("500") || normalized.contains("502") || normalized.contains("503") || normalized.contains("504") -> {
                "OpenAI server error"
            }
            else -> "optimizer error: ${text.take(140)}"
        }
    }

    private fun normalizeAiTuningStatusRaw(raw: String): String {
        return when (raw.trim().uppercase(Locale.US)) {
            "ERROR" -> "optimizer request failed"
            "NO_DATA" -> "optimizer report not available yet"
            "NO_CHANGE" -> "optimizer kept neutral tuning"
            else -> "status=${raw.trim()}"
        }
    }

    private fun buildDailyReportBundle(
        telemetryByKey: Map<String, TelemetrySampleEntity>
    ): DailyReportBundle {
        val generatedAtTs = listOf(
            telemetryByKey["daily_report_matched_samples"],
            telemetryByKey["daily_report_forecast_rows"],
            telemetryByKey["daily_report_mae_5m"],
            telemetryByKey["daily_report_mae_30m"],
            telemetryByKey["daily_report_mae_60m"]
        ).firstNotNullOfOrNull { it?.timestamp }
        val matchedSamples = telemetryByKey["daily_report_matched_samples"].toNumericValue()?.roundToInt()
        val forecastRows = telemetryByKey["daily_report_forecast_rows"].toNumericValue()?.roundToInt()
        val markdownPath = telemetryByKey["daily_report_markdown_path"]?.valueText
        val periodStartUtc = telemetryByKey["daily_report_period_start"]?.valueText
        val periodEndUtc = telemetryByKey["daily_report_period_end"]?.valueText
        val metrics = listOf(5, 30, 60).mapNotNull { horizon ->
            val mae = telemetryByKey["daily_report_mae_${horizon}m"].toNumericValue()
            val rmse = telemetryByKey["daily_report_rmse_${horizon}m"].toNumericValue()
            val mardPct = telemetryByKey["daily_report_mard_${horizon}m_pct"].toNumericValue()
            val bias = telemetryByKey["daily_report_bias_${horizon}m"].toNumericValue()
            val sampleCount = telemetryByKey["daily_report_n_${horizon}m"].toNumericValue()?.roundToInt()
            val ciCoveragePct = telemetryByKey["daily_report_ci_coverage_${horizon}m_pct"].toNumericValue()
            val ciMeanWidth = telemetryByKey["daily_report_ci_width_${horizon}m"].toNumericValue()
            if (
                mae == null &&
                rmse == null &&
                mardPct == null &&
                bias == null &&
                sampleCount == null &&
                ciCoveragePct == null &&
                ciMeanWidth == null
            ) {
                null
            } else {
                DailyReportMetricUi(
                    horizonMinutes = horizon,
                    sampleCount = sampleCount,
                    mae = mae,
                    rmse = rmse,
                    mardPct = mardPct,
                    bias = bias,
                    ciCoveragePct = ciCoveragePct,
                    ciMeanWidth = ciMeanWidth
                )
            }
        }
        val recommendations = (1..3).mapNotNull { index ->
            telemetryByKey["daily_report_recommendation_$index"]
                ?.valueText
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }
        val droppedEvents = telemetryByKey["daily_report_isfcr_dropped_event_count"]
            .toNumericValue()
            ?.roundToInt()
        val droppedTotal = telemetryByKey["daily_report_isfcr_dropped_total"]
            .toNumericValue()
            ?.roundToInt()
        val droppedSource = telemetryByKey["daily_report_isfcr_dropped_source"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val topReasons = telemetryByKey["daily_report_isfcr_dropped_top_reasons"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val qualityRisk = telemetryByKey["daily_report_isfcr_quality_risk"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val gapDropRatePct = telemetryByKey["daily_report_isfcr_cr_gap_drop_rate_pct"]
            .toNumericValue()
        val sensorDropRatePct = telemetryByKey["daily_report_isfcr_cr_sensor_drop_rate_pct"]
            .toNumericValue()
        val uamDropRatePct = telemetryByKey["daily_report_isfcr_cr_uam_drop_rate_pct"]
            .toNumericValue()
        val isfCrQualityLines = buildList {
            if (droppedEvents != null || droppedTotal != null || droppedSource != null) {
                add(
                    "source=${droppedSource ?: "--"}, " +
                        "events=${droppedEvents ?: 0}, " +
                        "dropped=${droppedTotal ?: 0}"
                )
            }
            qualityRisk?.let { add("Quality risk: $it") }
            if (gapDropRatePct != null || sensorDropRatePct != null || uamDropRatePct != null) {
                add(
                    "CR integrity drop-rate: gap=${String.format(Locale.US, "%.1f", gapDropRatePct ?: 0.0)}%, " +
                        "sensorBlocked=${String.format(Locale.US, "%.1f", sensorDropRatePct ?: 0.0)}%, " +
                        "uamAmbiguity=${String.format(Locale.US, "%.1f", uamDropRatePct ?: 0.0)}%"
                )
            }
            topReasons?.let { add("Top dropped reasons: $it") }
        }
        val rollingLines = listOf(14, 30, 90).mapNotNull { days ->
            val prefix = "rolling_report_${days}d"
            val matched = telemetryByKey["${prefix}_matched_samples"].toNumericValue()?.roundToInt()
            val mae30 = telemetryByKey["${prefix}_mae_30m"].toNumericValue()
            val mae60 = telemetryByKey["${prefix}_mae_60m"].toNumericValue()
            val mard30 = telemetryByKey["${prefix}_mard_30m_pct"].toNumericValue()
            val mard60 = telemetryByKey["${prefix}_mard_60m_pct"].toNumericValue()
            val cov60 = telemetryByKey["${prefix}_ci_coverage_60m_pct"].toNumericValue()
            val width60 = telemetryByKey["${prefix}_ci_width_60m"].toNumericValue()
            val hasAny = listOf(
                matched?.toDouble(),
                mae30,
                mae60,
                mard30,
                mard60,
                cov60,
                width60
            ).any { it != null }
            if (!hasAny) {
                null
            } else {
                buildString {
                    append("${days}d: n=${matched ?: 0}")
                    mae30?.let { append(", MAE30=${String.format(Locale.US, "%.2f", it)}") }
                    mae60?.let { append(", MAE60=${String.format(Locale.US, "%.2f", it)}") }
                    mard30?.let { append(", MARD30=${String.format(Locale.US, "%.1f", it)}%") }
                    mard60?.let { append(", MARD60=${String.format(Locale.US, "%.1f", it)}%") }
                    cov60?.let { append(", CI60=${String.format(Locale.US, "%.1f", it)}%") }
                    width60?.let { append(", W60=${String.format(Locale.US, "%.2f", it)}") }
                }
            }
        }
        val replayTopFactorsOverall = telemetryByKey["daily_report_replay_top_factors_overall"]
            ?.valueText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val replayFactorsByHorizon = listOf(5, 30, 60).flatMap { horizon ->
            listOfNotNull(
                telemetryByKey["daily_report_replay_top_factor_${horizon}m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { "Replay 24h ${horizon}m factor: $it" },
                telemetryByKey["daily_report_replay_top_factor_hint_${horizon}m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { "Replay 24h ${horizon}m hint: $it" },
                telemetryByKey["daily_report_replay_hotspot_${horizon}m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { "Replay 24h ${horizon}m hotspot: $it" },
                telemetryByKey["daily_report_replay_top_miss_${horizon}m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { "Replay 24h ${horizon}m top miss: $it" },
                telemetryByKey["daily_report_replay_top_pair_${horizon}m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { "Replay 24h ${horizon}m top pair: $it" },
                telemetryByKey["daily_report_replay_top_pair_hint_${horizon}m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { "Replay 24h ${horizon}m pair hint: $it" },
                telemetryByKey["daily_report_replay_error_cluster_${horizon}m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { "Replay 24h ${horizon}m error cluster: $it" },
                telemetryByKey["daily_report_replay_error_cluster_hint_${horizon}m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { "Replay 24h ${horizon}m cluster hint: $it" },
                telemetryByKey["daily_report_replay_daytype_gap_${horizon}m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { "Replay 24h ${horizon}m day-type gap: $it" },
                telemetryByKey["daily_report_replay_daytype_gap_hint_${horizon}m"]?.valueText?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { "Replay 24h ${horizon}m day-type hint: $it" }
            )
        }
        val replayHotspots = parseReplayHotspotsJson(
            telemetryByKey["daily_report_replay_hotspots_json"]?.valueText
        )
        val replayFactors = parseReplayFactorsJson(
            telemetryByKey["daily_report_replay_factors_json"]?.valueText
        )
        val replayCoverage = parseReplayCoverageJson(
            telemetryByKey["daily_report_replay_factor_coverage_json"]?.valueText
        )
        val replayRegimes = parseReplayRegimesJson(
            telemetryByKey["daily_report_replay_factor_regime_json"]?.valueText
        )
        val replayPairs = parseReplayPairsJson(
            telemetryByKey["daily_report_replay_factor_pair_json"]?.valueText
        )
        val replayTopMisses = parseReplayTopMissJson(
            telemetryByKey["daily_report_replay_top_miss_json"]?.valueText
        )
        val replayErrorClusters = parseReplayErrorClustersJson(
            telemetryByKey["daily_report_replay_error_cluster_json"]?.valueText
        )
        val replayDayTypeGaps = parseReplayDayTypeGapsJson(
            telemetryByKey["daily_report_replay_daytype_gap_json"]?.valueText
        )
        val sensorLagReplayBuckets = parseSensorLagReplayBucketsJson(
            telemetryByKey["daily_report_sensor_lag_bucket_json"]?.valueText
        )
        val sensorLagShadowBuckets = parseSensorLagShadowBucketsJson(
            telemetryByKey["daily_report_sensor_lag_shadow_json"]?.valueText
        )
        val reportLines = buildList {
            addAll(rollingLines)
            replayTopFactorsOverall?.let { add("Replay 24h top factors: $it") }
            addAll(replayFactorsByHorizon)
            replayCoverage
                .filter { it.factor in setOf("COB", "IOB", "UAM", "CI") }
                .sortedWith(compareBy<DailyReportReplayCoverageUi> { it.horizonMinutes }.thenBy { it.factor })
                .forEach { row ->
                    add(
                        "Replay 24h ${row.horizonMinutes}m coverage ${row.factor}: " +
                            "${String.format(Locale.US, "%.1f", row.coveragePct)}% (n=${row.sampleCount})"
                    )
                }
            replayRegimes
                .filter { it.factor in setOf("COB", "IOB", "UAM", "CI") }
                .sortedWith(
                    compareBy<DailyReportReplayRegimeUi> { it.horizonMinutes }
                        .thenBy { it.factor }
                        .thenBy {
                            when (it.bucket) {
                                "LOW" -> 0
                                "MID" -> 1
                                "HIGH" -> 2
                                else -> 3
                            }
                        }
                )
                .forEach { row ->
                    add(
                        "Replay 24h ${row.horizonMinutes}m ${row.factor} ${row.bucket}: " +
                            "mean=${String.format(Locale.US, "%.2f", row.meanFactorValue)}, " +
                            "MAE=${String.format(Locale.US, "%.2f", row.mae)}, " +
                            "MARD=${String.format(Locale.US, "%.1f", row.mardPct)}%, " +
                            "bias=${String.format(Locale.US, "%.2f", row.bias)} (n=${row.sampleCount})"
                    )
                }
            replayPairs
                .filter { it.factorA in setOf("COB", "IOB", "UAM", "CI") && it.factorB in setOf("COB", "IOB", "UAM", "CI") }
                .sortedWith(
                    compareBy<DailyReportReplayPairUi> { it.horizonMinutes }
                        .thenBy { it.factorA }
                        .thenBy { it.factorB }
                        .thenBy {
                            when (it.bucketA) {
                                "LOW" -> 0
                                "HIGH" -> 1
                                else -> 2
                            }
                        }
                        .thenBy {
                            when (it.bucketB) {
                                "LOW" -> 0
                                "HIGH" -> 1
                                else -> 2
                            }
                        }
                )
                .forEach { row ->
                    add(
                        "Replay 24h ${row.horizonMinutes}m ${row.factorA}x${row.factorB} ${row.bucketA}x${row.bucketB}: " +
                            "meanA=${String.format(Locale.US, "%.2f", row.meanFactorA)}, " +
                            "meanB=${String.format(Locale.US, "%.2f", row.meanFactorB)}, " +
                            "MAE=${String.format(Locale.US, "%.2f", row.mae)}, " +
                            "MARD=${String.format(Locale.US, "%.1f", row.mardPct)}%, " +
                            "bias=${String.format(Locale.US, "%.2f", row.bias)} (n=${row.sampleCount})"
                    )
                }
        }

        return DailyReportBundle(
            generatedAtTs = generatedAtTs,
            matchedSamples = matchedSamples,
            forecastRows = forecastRows,
            markdownPath = markdownPath,
            periodStartUtc = periodStartUtc,
            periodEndUtc = periodEndUtc,
            metrics = metrics,
            recommendations = recommendations,
            isfCrQualityLines = isfCrQualityLines,
            reportLines = reportLines,
            replayHotspots = replayHotspots,
            replayFactors = replayFactors,
            replayCoverage = replayCoverage,
            replayRegimes = replayRegimes,
            replayPairs = replayPairs,
            replayTopMisses = replayTopMisses,
            replayErrorClusters = replayErrorClusters,
            replayDayTypeGaps = replayDayTypeGaps,
            replayTopFactorsOverall = replayTopFactorsOverall,
            sensorLagReplayBuckets = sensorLagReplayBuckets,
            sensorLagShadowBuckets = sensorLagShadowBuckets
        )
    }

    private fun emptyDailyReportBundle(): DailyReportBundle = DailyReportBundle(
        generatedAtTs = null,
        matchedSamples = null,
        forecastRows = null,
        markdownPath = null,
        periodStartUtc = null,
        periodEndUtc = null,
        metrics = emptyList(),
        recommendations = emptyList(),
        isfCrQualityLines = emptyList(),
        reportLines = emptyList(),
        replayHotspots = emptyList(),
        replayFactors = emptyList(),
        replayCoverage = emptyList(),
        replayRegimes = emptyList(),
        replayPairs = emptyList(),
        replayTopMisses = emptyList(),
        replayErrorClusters = emptyList(),
        replayDayTypeGaps = emptyList(),
        replayTopFactorsOverall = null,
        sensorLagReplayBuckets = emptyList(),
        sensorLagShadowBuckets = emptyList()
    )

    private fun TelemetrySampleEntity.isHigherCumulativeSampleThan(other: TelemetrySampleEntity): Boolean {
        val value = valueDouble ?: Double.NEGATIVE_INFINITY
        val otherValue = other.valueDouble ?: Double.NEGATIVE_INFINITY
        return if (value == otherValue) {
            timestamp >= other.timestamp
        } else {
            value > otherValue
        }
    }

    private fun latestForecastValue(forecasts: List<ForecastEntity>, horizonMinutes: Int): Double? {
        return forecasts
            .asSequence()
            .filter { it.horizonMinutes == horizonMinutes }
            .maxWithOrNull(compareBy<ForecastEntity> { it.timestamp }.thenBy { it.id })
            ?.valueMmol
    }

    private fun isTelemetrySampleUsable(sample: TelemetrySampleEntity): Boolean {
        if (sample.key != "uam_calculated_flag" && sample.key != "uam_inferred_flag") return true
        val numeric = sample.valueDouble ?: sample.valueText?.replace(",", ".")?.toDoubleOrNull()
        return numeric == null || numeric in 0.0..1.5
    }

    private fun TelemetrySampleEntity?.toNumericValue(): Double? {
        if (this == null) return null
        return valueDouble ?: valueText?.replace(",", ".")?.toDoubleOrNull()
    }

    private fun resolveMetricByRecency(
        preferred: TelemetrySampleEntity?,
        fallback: TelemetrySampleEntity?
    ): Double? {
        val preferredValue = preferred.toNumericValue()
        val fallbackValue = fallback.toNumericValue()
        return when {
            preferredValue == null -> fallbackValue
            fallbackValue == null -> preferredValue
            (preferred?.timestamp ?: Long.MIN_VALUE) >= (fallback?.timestamp ?: Long.MIN_VALUE) -> preferredValue
            else -> fallbackValue
        }
    }

    private fun resolveLatestMetricByRecency(
        vararg candidates: TelemetrySampleEntity?
    ): Double? = candidates.asSequence()
        .filterNotNull()
        .mapNotNull { sample ->
            sample.toNumericValue()
                ?.takeIf(Double::isFinite)
                ?.let { value -> sample to value }
        }
        .maxWithOrNull(compareBy<Pair<TelemetrySampleEntity, Double>> { it.first.timestamp }.thenBy { it.first.id })
        ?.second

    private fun buildSmbContextSummary(
        telemetryByKey: Map<String, TelemetrySampleEntity>,
        baseTargetMmol: Double
    ): String {
        val highTempTarget = telemetryByKey["temp_target_high_mmol"].toNumericValue()
        val highTempActive = highTempTarget != null && highTempTarget >= baseTargetMmol + 0.10

        val smbSignals = telemetryByKey.values
            .asSequence()
            .filter { sample ->
                val key = sample.key.lowercase(Locale.US)
                key.contains("smb") || key.contains("microbolus")
            }
            .sortedByDescending { it.timestamp }
            .take(3)
            .map { sample ->
                val value = sample.valueDouble?.let { String.format("%.2f", it) }
                    ?: sample.valueText
                    ?: "-"
                "${sample.key}=$value"
            }
            .toList()

        val chunks = mutableListOf<String>()
        if (highTempActive) {
            chunks += "High temp target active (${String.format("%.2f", highTempTarget)} mmol/L): SMB may be reduced by AAPS high-temp-target settings."
        }
        if (smbSignals.isNotEmpty()) {
            chunks += "SMB telemetry: ${smbSignals.joinToString("; ")}"
        }
        if (chunks.isEmpty()) {
            return "No explicit SMB telemetry flags detected."
        }
        return chunks.joinToString(" | ")
    }

    private fun resolveTelemetrySample(
        spec: TelemetryCoverageSpec,
        latestByKey: Map<String, TelemetrySampleEntity>
    ): TelemetrySampleEntity? {
        val exactKeys = listOf(spec.primaryKey) + spec.exactAliases
        exactKeys.forEach { key ->
            latestByKey[key]?.let { return it }
        }
        if (spec.tokenAliases.isEmpty()) return null
        return latestByKey.values
            .asSequence()
            .filter { sample ->
                spec.tokenAliases.any { alias -> keyContainsAliasToken(sample.key, alias) }
            }
            .maxByOrNull { it.timestamp }
    }

    private fun keyContainsAliasToken(key: String, alias: String): Boolean {
        val normalizedAlias = normalizeTelemetryKey(alias)
        if (normalizedAlias.isBlank()) return false
        val normalizedKey = normalizeTelemetryKey(key)
        if (normalizedKey == normalizedAlias || normalizedKey.endsWith("_$normalizedAlias")) return true
        val parts = normalizedKey.split('_').filter { it.isNotBlank() }
        return parts.contains(normalizedAlias)
    }

    private fun normalizeTelemetryKey(value: String): String {
        return value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private data class ReplacementSpec(
        val label: String,
        val types: Set<String>
    )

    private fun buildReplacementHistoryLines(
        therapy: List<TherapyEventEntity>,
        nowTs: Long
    ): List<String> {
        val specs = listOf(
            ReplacementSpec(
                label = "Infusion set",
                types = setOf("infusion_set_change", "site_change", "cannula_change", "set_change")
            ),
            ReplacementSpec(
                label = "Sensor",
                types = setOf("sensor_change", "sensor_start")
            ),
            ReplacementSpec(
                label = "Insulin refill",
                types = setOf("insulin_refill", "insulin_change", "reservoir_change", "cartridge_change", "pump_refill")
            ),
            ReplacementSpec(
                label = "Pump battery",
                types = setOf("pump_battery_change", "battery_change", "battery_replacement")
            )
        )
        return specs.map { spec ->
            val events = therapy
                .filter { spec.types.contains(it.type.lowercase(Locale.US)) }
                .sortedByDescending { it.timestamp }
            val lastTs = events.firstOrNull()?.timestamp
            val count7d = events.count { it.timestamp >= nowTs - 7L * 24 * 60 * 60 * 1000 }
            val count30d = events.count { it.timestamp >= nowTs - 30L * 24 * 60 * 60 * 1000 }
            val avgInterval30d = averageIntervalDays(
                events = events.filter { it.timestamp >= nowTs - 30L * 24 * 60 * 60 * 1000 }
            )
            val avgText = avgInterval30d?.let { String.format(Locale.US, "%.1f d", it) } ?: "-"
            "${spec.label}: last=${formatTs(lastTs)}, 7d=$count7d, 30d=$count30d, avg interval(30d)=$avgText"
        }
    }

    private fun averageIntervalDays(events: List<TherapyEventEntity>): Double? {
        if (events.size < 2) return null
        val sorted = events.sortedBy { it.timestamp }
        val deltas = sorted.zipWithNext().map { (a, b) ->
            (b.timestamp - a.timestamp).coerceAtLeast(0L).toDouble()
        }
        if (deltas.isEmpty()) return null
        return deltas.average() / (24.0 * 60 * 60 * 1000)
    }

    private data class TherapyCoverageFallback(
        val carbsGrams: Double?,
        val carbsTs: Long?,
        val insulinUnits: Double?,
        val insulinTs: Long?
    )

    private fun resolveTherapyFallback(events: List<TherapyEventEntity>): TherapyCoverageFallback {
        var carbs: Double? = null
        var carbsTs: Long? = null
        var insulin: Double? = null
        var insulinTs: Long? = null

        events.sortedByDescending { it.timestamp }.forEach { event ->
            if (carbs == null && (event.type.equals("carbs", true) || event.type.equals("meal_bolus", true))) {
                val grams = payloadDouble(event.payloadJson, "grams", "carbs", "enteredCarbs", "mealCarbs")
                if (grams != null && grams in 1.0..300.0) {
                    carbs = grams
                    carbsTs = event.timestamp
                }
            }
            if (insulin == null && (event.type.equals("meal_bolus", true) || event.type.equals("correction_bolus", true))) {
                val units = payloadDouble(event.payloadJson, "units", "bolusUnits", "insulin", "enteredInsulin")
                if (units != null && units in 0.05..25.0) {
                    insulin = units
                    insulinTs = event.timestamp
                }
            }
            if (carbs != null && insulin != null) return@forEach
        }

        return TherapyCoverageFallback(
            carbsGrams = carbs,
            carbsTs = carbsTs,
            insulinUnits = insulin,
            insulinTs = insulinTs
        )
    }

    private fun resolveCoverageFallback(
        spec: TelemetryCoverageSpec,
        therapyFallback: TherapyCoverageFallback,
        profile: ProfileEstimateEntity?,
        nowTs: Long
    ): String? {
        return when (spec.primaryKey) {
            "carbs_grams" -> therapyFallback.carbsGrams?.let {
                val ageMin = minutesSince(nowTs, therapyFallback.carbsTs) ?: 0L
                val freshness = if (ageMin <= spec.staleThresholdMin) "fresh" else "stale ${ageMin}m"
                "Carbs: ${String.format(Locale.US, "%.1f", it)} g (therapy fallback, ${formatTs(therapyFallback.carbsTs)}) [$freshness]"
            }
            "insulin_units" -> therapyFallback.insulinUnits?.let {
                val ageMin = minutesSince(nowTs, therapyFallback.insulinTs) ?: 0L
                val freshness = if (ageMin <= spec.staleThresholdMin) "fresh" else "stale ${ageMin}m"
                "Insulin: ${String.format(Locale.US, "%.2f", it)} U (therapy fallback, ${formatTs(therapyFallback.insulinTs)}) [$freshness]"
            }
            "isf_value" -> profile?.isfMmolPerUnit?.let {
                val realFirst = profile.calculatedIsfMmolPerUnit ?: it
                "ISF: ${String.format(Locale.US, "%.2f", realFirst)} mmol/L/U (real profile estimate)"
            }
            "cr_value" -> profile?.crGramPerUnit?.let {
                val realFirst = profile.calculatedCrGramPerUnit ?: it
                "CR: ${String.format(Locale.US, "%.2f", realFirst)} g/U (real profile estimate)"
            }
            "uam_value", "uam_calculated_flag" -> profile?.uamObservedCount?.let {
                val carbs = profile.uamEstimatedRecentCarbsGrams
                "UAM: observed=$it, recent=${String.format(Locale.US, "%.1f", carbs)} g (profile analyzer)"
            }
            else -> null
        }
    }

    private fun shouldPreferFallback(
        spec: TelemetryCoverageSpec,
        sample: TelemetrySampleEntity
    ): Boolean {
        return when (spec.primaryKey) {
            "carbs_grams" -> {
                sample.key == "carbs_grams" &&
                    sample.source == "aaps_broadcast" &&
                    (sample.valueDouble ?: 0.0) <= 0.0
            }
            "insulin_units" -> {
                sample.key == "insulin_units" && sample.source == "aaps_broadcast"
            }
            else -> false
        }
    }

    private fun formatTelemetryLine(sample: TelemetrySampleEntity): String {
        val title = sample.key.replace('_', ' ')
        val value = sample.valueDouble?.let {
            if (sample.unit == "steps") String.format("%.0f", it) else String.format("%.2f", it)
        } ?: sample.valueText.orEmpty()
        val unit = sample.unit?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return "$title: $value$unit (${sample.source}, ${formatTs(sample.timestamp)})"
    }

    private fun resolveYesterdayProfileLines(
        glucose: List<GlucoseSampleEntity>,
        therapy: List<TherapyEventEntity>,
        telemetry: List<TelemetrySampleEntity>,
        nowTs: Long
    ): List<String> {
        val key = buildAnalyticsCacheKey(
            prefix = "yesterday",
            nowBucket = resolveDayWindow(nowTs).startTodayTs,
            glucoseTs = glucose.maxOfOrNull { it.timestamp },
            therapyTs = therapy.maxOfOrNull { it.timestamp },
            telemetryTs = telemetry.maxOfOrNull { it.timestamp },
            sizes = intArrayOf(glucose.size, therapy.size, telemetry.size)
        )
        yesterdayProfileLinesCache?.takeIf { it.key == key }?.let { return it.value }
        return buildYesterdayProfileLines(
            glucose = glucose,
            therapy = therapy,
            telemetry = telemetry,
            nowTs = nowTs
        ).also { yesterdayProfileLinesCache = CachedUiValue(key = key, value = it) }
    }

    private fun resolveIsfCrDeepLines(
        glucose: List<GlucoseSampleEntity>,
        therapy: List<TherapyEventEntity>,
        telemetry: List<TelemetrySampleEntity>,
        nowTs: Long
    ): List<String> {
        val key = buildAnalyticsCacheKey(
            prefix = "isfcr_deep",
            nowBucket = (nowTs / (5 * 60_000L)) * (5 * 60_000L),
            glucoseTs = glucose.maxOfOrNull { it.timestamp },
            therapyTs = therapy.maxOfOrNull { it.timestamp },
            telemetryTs = telemetry.maxOfOrNull { it.timestamp },
            sizes = intArrayOf(glucose.size, therapy.size, telemetry.size)
        )
        isfCrDeepLinesCache?.takeIf { it.key == key }?.let { return it.value }
        return buildIsfCrDeepLines(
            glucose = glucose,
            therapy = therapy,
            telemetry = telemetry,
            nowTs = nowTs
        ).also { isfCrDeepLinesCache = CachedUiValue(key = key, value = it) }
    }

    private fun resolveIsfCrHistoryPoints(
        profileHistory: List<ProfileEstimateEntity>,
        isfCrHistory: List<IsfCrRealtimeSnapshot>,
        telemetry: List<TelemetrySampleEntity>
    ): List<IsfCrHistoryPointUi> {
        val key = buildAnalyticsCacheKey(
            prefix = "isfcr_history",
            nowBucket = 0L,
            glucoseTs = profileHistory.maxOfOrNull { it.timestamp },
            therapyTs = isfCrHistory.maxOfOrNull { it.ts },
            telemetryTs = telemetry.maxOfOrNull { it.timestamp },
            sizes = intArrayOf(profileHistory.size, isfCrHistory.size, telemetry.size)
        )
        isfCrHistoryPointsCache?.takeIf { it.key == key }?.let { return it.value }
        return buildIsfCrHistoryPoints(
            profileHistory = profileHistory,
            isfCrHistory = isfCrHistory,
            telemetry = telemetry
        ).also { isfCrHistoryPointsCache = CachedUiValue(key = key, value = it) }
    }

    private fun resolveIsfCrHistoryOverlayPoints(
        historyPoints: List<IsfCrHistoryPointUi>,
        telemetry: List<TelemetrySampleEntity>
    ): List<IsfCrOverlayPointUi> {
        val key = buildAnalyticsCacheKey(
            prefix = "isfcr_overlay",
            nowBucket = 0L,
            glucoseTs = historyPoints.maxOfOrNull { it.timestamp },
            therapyTs = null,
            telemetryTs = telemetry.maxOfOrNull { it.timestamp },
            sizes = intArrayOf(historyPoints.size, telemetry.size)
        )
        isfCrHistoryOverlayPointsCache?.takeIf { it.key == key }?.let { return it.value }
        return buildIsfCrOverlayPoints(
            historyPoints = historyPoints,
            telemetry = telemetry
        ).also { isfCrHistoryOverlayPointsCache = CachedUiValue(key = key, value = it) }
    }

    private fun resolveCircadianPatternSections(
        slotStats: List<CircadianSlotStatEntity>,
        transitionStats: List<CircadianTransitionStatEntity>,
        snapshots: List<CircadianPatternSnapshotEntity>,
        replayStats: List<io.aaps.copilot.data.local.entity.CircadianReplaySlotStatEntity>,
        nowTs: Long
    ): List<CircadianPatternSectionUi> {
        val key = buildAnalyticsCacheKey(
            prefix = "circadian_sections",
            nowBucket = (nowTs / (15L * 60L * 1000L)) * (15L * 60L * 1000L),
            glucoseTs = slotStats.maxOfOrNull { it.updatedAt },
            therapyTs = transitionStats.maxOfOrNull { it.updatedAt },
            telemetryTs = maxOf(
                snapshots.maxOfOrNull { it.updatedAt } ?: 0L,
                replayStats.maxOfOrNull { it.updatedAt } ?: 0L
            ),
            sizes = intArrayOf(slotStats.size, transitionStats.size, snapshots.size, replayStats.size)
        )
        circadianPatternSectionsCache?.takeIf { it.key == key }?.let { return it.value }
        return CircadianPatternUiResolver.buildSections(
            slotStats = slotStats,
            transitionStats = transitionStats,
            snapshots = snapshots,
            replayStats = replayStats,
            nowTs = nowTs
        ).also { circadianPatternSectionsCache = CachedUiValue(key = key, value = it) }
    }

    private fun buildAnalyticsCacheKey(
        prefix: String,
        nowBucket: Long,
        glucoseTs: Long?,
        therapyTs: Long?,
        telemetryTs: Long?,
        sizes: IntArray
    ): String {
        return buildString(96) {
            append(prefix)
            append(':').append(nowBucket)
            append(':').append(glucoseTs ?: -1L)
            append(':').append(therapyTs ?: -1L)
            append(':').append(telemetryTs ?: -1L)
            sizes.forEach { append(':').append(it) }
        }
    }

    private fun buildYesterdayProfileLines(
        glucose: List<GlucoseSampleEntity>,
        therapy: List<TherapyEventEntity>,
        telemetry: List<TelemetrySampleEntity>,
        nowTs: Long
    ): List<String> {
        val dayWindow = resolveDayWindow(nowTs)
        val glucoseYesterday = glucose.filter { it.timestamp in dayWindow.startYesterdayTs until dayWindow.startTodayTs }
        val therapyYesterday = therapy.filter { it.timestamp in dayWindow.startYesterdayTs until dayWindow.startTodayTs }
        val telemetryYesterday = telemetry.filter { it.timestamp in dayWindow.startYesterdayTs until dayWindow.startTodayTs }
        val dateLabel = dayWindow.yesterdayDate.toString()

        if (glucoseYesterday.size < 18) {
            return listOf("Yesterday ($dateLabel): insufficient glucose points (${glucoseYesterday.size})")
        }

        val estimator = ProfileEstimator(ProfileEstimatorConfig(lookbackDays = 1))
        val glucoseDomain = glucoseYesterday.map { it.toDomain() }
        val therapyDomain = therapyYesterday.mapNotNull { event ->
            runCatching { event.toDomain(container.gson) }.getOrNull()
        }
        val telemetrySignals = telemetryYesterday.map { sample ->
            TelemetrySignal(
                ts = sample.timestamp,
                key = sample.key,
                valueDouble = sample.valueDouble,
                valueText = sample.valueText,
                source = sample.source,
                quality = sample.quality
            )
        }
        val calculated = estimator.estimate(
            glucoseHistory = glucoseDomain,
            therapyEvents = therapyDomain,
            telemetrySignals = emptyList()
        )
        val hourlyCalculated = estimator.estimateHourly(
            glucoseHistory = glucoseDomain,
            therapyEvents = therapyDomain,
            telemetrySignals = emptyList()
        )
        val merged = estimator.estimate(
            glucoseHistory = glucoseDomain,
            therapyEvents = therapyDomain,
            telemetrySignals = telemetrySignals
        )
        val hourlyMerged = estimator.estimateHourly(
            glucoseHistory = glucoseDomain,
            therapyEvents = therapyDomain,
            telemetrySignals = telemetrySignals
        )

        return buildList {
            add("Yesterday ($dateLabel): CGM=${glucoseYesterday.size}, therapy=${therapyYesterday.size}, telemetry=${telemetryYesterday.size}")
            if (calculated == null) {
                add("History-only: insufficient correction/meal samples")
            } else {
                add(
                    "History-only (real): ISF=${String.format(Locale.US, "%.2f", calculated.isfMmolPerUnit)} mmol/L/U, " +
                        "CR=${String.format(Locale.US, "%.2f", calculated.crGramPerUnit)} g/U, " +
                        "conf=${String.format(Locale.US, "%.0f%%", calculated.confidence * 100)}, " +
                        "n=${calculated.sampleCount} (ISF=${calculated.isfSampleCount}, CR=${calculated.crSampleCount})"
                )
            }
            if (merged != null) {
                add(
                    "History + telemetry fallback: ISF=${String.format(Locale.US, "%.2f", merged.isfMmolPerUnit)} mmol/L/U, " +
                    "CR=${String.format(Locale.US, "%.2f", merged.crGramPerUnit)} g/U, " +
                    "conf=${String.format(Locale.US, "%.0f%%", merged.confidence * 100)}"
                )
            }
            if (hourlyCalculated.isEmpty()) {
                add("Hourly history-only (real): insufficient hourly samples")
            } else {
                add("Hourly history-only (real): ${hourlyCalculated.size}/24 hours")
                hourlyCalculated.forEach { hour ->
                    val isfText = hour.isfMmolPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                    val crText = hour.crGramPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                    add(
                        "${String.format(Locale.US, "%02d:00", hour.hour)} " +
                            "ISF=$isfText, CR=$crText, " +
                            "conf=${String.format(Locale.US, "%.0f%%", hour.confidence * 100)}, " +
                            "n(ISF/CR)=${hour.isfSampleCount}/${hour.crSampleCount}"
                    )
                }
            }
            if (hourlyMerged.isNotEmpty()) {
                add("Hourly history + telemetry fallback: ${hourlyMerged.size}/24 hours")
                hourlyMerged.forEach { hour ->
                    val isfText = hour.isfMmolPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                    val crText = hour.crGramPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                    add(
                        "${String.format(Locale.US, "%02d:00", hour.hour)} " +
                            "ISF=$isfText, CR=$crText, " +
                            "conf=${String.format(Locale.US, "%.0f%%", hour.confidence * 100)}, " +
                            "n(ISF/CR)=${hour.isfSampleCount}/${hour.crSampleCount}"
                    )
                }
            }
        }
    }

    private fun buildIsfCrDeepLines(
        glucose: List<GlucoseSampleEntity>,
        therapy: List<TherapyEventEntity>,
        telemetry: List<TelemetrySampleEntity>,
        nowTs: Long
    ): List<String> {
        if (glucose.isEmpty()) return listOf("ISF/CR deep analysis: no glucose data")

        val windowsDays = listOf(1, 3, 7, 14, 30)
        val historyWindowMs = 24L * 60L * 60L * 1000L

        return buildList {
            add("ISF/CR deep analysis (real + telemetry fallback)")
            windowsDays.forEach { days ->
                val startTs = nowTs - days * historyWindowMs
                val glucoseWindow = glucose.filter { it.timestamp in startTs..nowTs }
                val therapyWindow = therapy.filter { it.timestamp in startTs..nowTs }
                val telemetryWindow = telemetry.filter { it.timestamp in startTs..nowTs }

                if (glucoseWindow.size < 18) {
                    add("Last ${days}d: insufficient glucose points (${glucoseWindow.size})")
                    return@forEach
                }

                val estimator = ProfileEstimator(ProfileEstimatorConfig(lookbackDays = days))
                val glucoseDomain = glucoseWindow.map { it.toDomain() }
                val therapyDomain = therapyWindow.mapNotNull { event ->
                    runCatching { event.toDomain(container.gson) }.getOrNull()
                }
                val telemetrySignals = telemetryWindow.map { sample ->
                    TelemetrySignal(
                        ts = sample.timestamp,
                        key = sample.key,
                        valueDouble = sample.valueDouble,
                        valueText = sample.valueText,
                        source = sample.source,
                        quality = sample.quality
                    )
                }

                val historyOnly = estimator.estimate(
                    glucoseHistory = glucoseDomain,
                    therapyEvents = therapyDomain,
                    telemetrySignals = emptyList()
                )
                val merged = estimator.estimate(
                    glucoseHistory = glucoseDomain,
                    therapyEvents = therapyDomain,
                    telemetrySignals = telemetrySignals
                )

                add(
                    "Last ${days}d history-only: " + if (historyOnly == null) {
                        "insufficient correction/meal samples"
                    } else {
                        "ISF=${String.format(Locale.US, "%.2f", historyOnly.isfMmolPerUnit)} mmol/L/U, " +
                            "CR=${String.format(Locale.US, "%.2f", historyOnly.crGramPerUnit)} g/U, " +
                            "conf=${String.format(Locale.US, "%.0f%%", historyOnly.confidence * 100)}, " +
                            "n=${historyOnly.sampleCount}"
                    }
                )
                add(
                    "Last ${days}d telemetry fallback: " + if (merged == null) {
                        "insufficient samples"
                    } else {
                        "ISF=${String.format(Locale.US, "%.2f", merged.isfMmolPerUnit)} mmol/L/U, " +
                            "CR=${String.format(Locale.US, "%.2f", merged.crGramPerUnit)} g/U, " +
                            "conf=${String.format(Locale.US, "%.0f%%", merged.confidence * 100)}, " +
                            "n=${merged.sampleCount}"
                    }
                )

                if (days != 7) return@forEach

                val hourlyHistory = estimator.estimateHourly(
                    glucoseHistory = glucoseDomain,
                    therapyEvents = therapyDomain,
                    telemetrySignals = emptyList()
                )
                val hourlyMerged = estimator.estimateHourly(
                    glucoseHistory = glucoseDomain,
                    therapyEvents = therapyDomain,
                    telemetrySignals = telemetrySignals
                )
                val hourlyByDayTypeHistory = estimator.estimateHourlyByDayType(
                    glucoseHistory = glucoseDomain,
                    therapyEvents = therapyDomain,
                    telemetrySignals = emptyList()
                )

                add("Last 7d hourly history-only (${hourlyHistory.size}/24 hours):")
                if (hourlyHistory.isEmpty()) {
                    add("hourly history-only: insufficient hourly samples")
                } else {
                    hourlyHistory.forEach { hour ->
                        val isfText = hour.isfMmolPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                        val crText = hour.crGramPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                        add(
                            "${String.format(Locale.US, "%02d:00", hour.hour)} ISF=$isfText, CR=$crText, " +
                                "conf=${String.format(Locale.US, "%.0f%%", hour.confidence * 100)}, " +
                                "n(ISF/CR)=${hour.isfSampleCount}/${hour.crSampleCount}"
                        )
                    }
                }

                add("Last 7d hourly telemetry fallback (${hourlyMerged.size}/24 hours):")
                if (hourlyMerged.isEmpty()) {
                    add("hourly merged: insufficient hourly samples")
                } else {
                    hourlyMerged.forEach { hour ->
                        val isfText = hour.isfMmolPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                        val crText = hour.crGramPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                        add(
                            "${String.format(Locale.US, "%02d:00", hour.hour)} ISF=$isfText, CR=$crText, " +
                                "conf=${String.format(Locale.US, "%.0f%%", hour.confidence * 100)}, " +
                                "n(ISF/CR)=${hour.isfSampleCount}/${hour.crSampleCount}"
                        )
                    }
                }

                add("Last 7d hourly history-only by day type:")
                DayType.entries.forEach { dayType ->
                    val dayRows = hourlyByDayTypeHistory.filter { it.dayType == dayType }
                    if (dayRows.isEmpty()) {
                        add("${dayType.name}: no samples")
                    } else {
                        add("${dayType.name}:")
                        dayRows.forEach { row ->
                            val isfText = row.isfMmolPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                            val crText = row.crGramPerUnit?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                            add(
                                "  ${String.format(Locale.US, "%02d:00", row.hour)} " +
                                    "ISF=$isfText, CR=$crText, conf=${String.format(Locale.US, "%.0f%%", row.confidence * 100)}, " +
                                    "n(ISF/CR)=${row.isfSampleCount}/${row.crSampleCount}"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun buildActivitySummaryLines(
        telemetry: List<TelemetrySampleEntity>,
        telemetryByKey: Map<String, TelemetrySampleEntity>,
        nowTs: Long,
        activityPermissionGranted: Boolean,
        audits: List<AuditLogEntity>
    ): List<String> {
        val dayWindow = resolveDayWindow(nowTs)
        val todayStart = dayWindow.startTodayTs
        val yesterdayStart = dayWindow.startYesterdayTs

        val stepsToday = maxMetricInRange(telemetry, "steps_count", todayStart, nowTs + 1)
        val stepsYesterday = maxMetricInRange(telemetry, "steps_count", yesterdayStart, todayStart)
        val distanceTodayKm = maxMetricInRange(telemetry, "distance_km", todayStart, nowTs + 1)
        val activeMinutesToday = maxMetricInRange(telemetry, "active_minutes", todayStart, nowTs + 1)
        val activeCaloriesToday = maxMetricInRange(telemetry, "calories_active_kcal", todayStart, nowTs + 1)
        val activityRatioCurrent = telemetryByKey["activity_ratio"].toNumericValue()
        val activityRatioAvg6h = averageMetricInRange(
            telemetry = telemetry,
            key = "activity_ratio",
            startTs = (nowTs - 6 * 60 * 60_000L).coerceAtLeast(0L),
            endTs = nowTs + 1
        )
        val latestLocalSensorTs = telemetry
            .asSequence()
            .filter { it.source == "local_sensor" }
            .maxOfOrNull { it.timestamp }
        val localSensorAgeMin = minutesSince(nowTs, latestLocalSensorTs)
        val latestHealthConnectTs = telemetry
            .asSequence()
            .filter { it.source == "health_connect" }
            .maxOfOrNull { it.timestamp }
        val healthConnectAgeMin = minutesSince(nowTs, latestHealthConnectTs)
        val latestHealthConnectAudit = audits
            .asSequence()
            .filter { it.message == "health_connect_activity_status" }
            .maxByOrNull { it.timestamp }
        val healthConnectState = latestHealthConnectAudit?.let { auditMetaField(it, "state") }
        val healthConnectMissingPermissions = latestHealthConnectAudit?.let { auditMetaField(it, "missingPermissions") }
        val activityLabel = telemetryByKey["activity_label"]?.valueText?.trim().takeIf { !it.isNullOrBlank() }

        val hasAnyMetric = listOfNotNull(
            stepsToday,
            stepsYesterday,
            distanceTodayKm,
            activeMinutesToday,
            activeCaloriesToday,
            activityRatioCurrent,
            activityRatioAvg6h
        ).isNotEmpty() || activityLabel != null || latestLocalSensorTs != null

        return buildList {
            add(
                "Activity permission: " + if (activityPermissionGranted) {
                    "granted"
                } else {
                    "missing (grant ACTIVITY_RECOGNITION)"
                }
            )
            add(
                "Local sensor stream: " + if (latestLocalSensorTs == null) {
                    "no data yet"
                } else {
                    "${formatTs(latestLocalSensorTs)} (age ${minutesLabel(localSensorAgeMin)})"
                }
            )
            if (HEALTH_CONNECT_ENABLED) {
                add(
                    "Health Connect stream: " + if (latestHealthConnectTs == null) {
                        formatHealthConnectStatus(healthConnectState, healthConnectMissingPermissions)
                    } else {
                        "${formatTs(latestHealthConnectTs)} (age ${minutesLabel(healthConnectAgeMin)})"
                    }
                )
            } else {
                add("Health Connect stream: paused")
            }
            if (!hasAnyMetric) {
                add("No steps/activity telemetry yet")
                return@buildList
            }
            add(
                "Steps today: ${stepsToday?.let { String.format(Locale.US, "%.0f", it) } ?: "-"} | " +
                    "yesterday: ${stepsYesterday?.let { String.format(Locale.US, "%.0f", it) } ?: "-"}"
            )
            add(
                "Activity ratio: current=${activityRatioCurrent?.let { String.format(Locale.US, "%.2f", it) } ?: "-"}, " +
                    "6h avg=${activityRatioAvg6h?.let { String.format(Locale.US, "%.2f", it) } ?: "-"}"
            )
            add(
                "Active minutes today: ${activeMinutesToday?.let { String.format(Locale.US, "%.0f min", it) } ?: "-"}"
            )
            add(
                "Distance today: ${distanceTodayKm?.let { String.format(Locale.US, "%.2f km", it) } ?: "-"}"
            )
            add(
                "Active calories today: ${activeCaloriesToday?.let { String.format(Locale.US, "%.0f kcal", it) } ?: "-"}"
            )
            activityLabel?.let { add("Activity label: $it") }
        }
    }

    private fun normalizePhysioTagType(raw: String): String {
        return when (raw.trim().lowercase(Locale.US)) {
            "hormonal_phase" -> "hormonal"
            "steroid" -> "steroids"
            else -> raw.trim().lowercase(Locale.US)
        }
    }

    private fun quickPhysioEventType(tagType: String): CompensationEventType = when (tagType) {
        "stress" -> CompensationEventType.STRESS
        "illness" -> CompensationEventType.ILLNESS
        "hormonal" -> CompensationEventType.HORMONAL
        "steroid", "steroids" -> CompensationEventType.MEDICATION_STEROID
        else -> CompensationEventType.CUSTOM
    }

    private fun formatHealthConnectStatus(state: String?, missingPermissions: String?): String {
        return when {
            state == null -> "no data yet (grant Health Connect read permissions)"
            state == "ok" -> "available"
            state == "permission_missing" -> {
                if (missingPermissions.isNullOrBlank()) {
                    "permission missing"
                } else {
                    "permission missing: $missingPermissions"
                }
            }
            state.startsWith("sdk_unavailable") -> "sdk unavailable"
            state == "client_unavailable" -> "client unavailable"
            state == "permission_check_failed" -> "permission check failed"
            state == "sync_failed" -> "sync failed"
            else -> state
        }
    }

    private fun isActivityRecognitionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun maxMetricInRange(
        telemetry: List<TelemetrySampleEntity>,
        key: String,
        startTs: Long,
        endTs: Long
    ): Double? {
        return telemetry
            .asSequence()
            .filter { it.key == key && it.timestamp in startTs until endTs }
            .mapNotNull { it.toNumericValue() }
            .maxOrNull()
    }

    private fun averageMetricInRange(
        telemetry: List<TelemetrySampleEntity>,
        key: String,
        startTs: Long,
        endTs: Long
    ): Double? {
        val values = telemetry
            .asSequence()
            .filter { it.key == key && it.timestamp in startTs until endTs }
            .mapNotNull { it.toNumericValue() }
            .toList()
        if (values.isEmpty()) return null
        return values.average()
    }

    private data class DayWindow(
        val yesterdayDate: LocalDate,
        val startYesterdayTs: Long,
        val startTodayTs: Long
    )

    private enum class AiTuningState {
        ACTIVE,
        STALE,
        BLOCKED
    }

    private data class AiTuningStatusSnapshot(
        val state: AiTuningState,
        val reason: String,
        val generatedTs: Long?,
        val confidence: Double?,
        val statusRaw: String?
    )

    private fun resolveDayWindow(nowTs: Long): DayWindow {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowTs).atZone(zone).toLocalDate()
        val yesterday = today.minusDays(1)
        return DayWindow(
            yesterdayDate = yesterday,
            startYesterdayTs = yesterday.atStartOfDay(zone).toInstant().toEpochMilli(),
            startTodayTs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        )
    }

    companion object {
        internal suspend fun setAutomaticEventAiAnalysisEnabled(
            enabled: Boolean,
            updateSettings: suspend ((AppSettings) -> AppSettings) -> Unit
        ) {
            updateSettings { current ->
                current.copy(automaticEventAiAnalysisEnabled = enabled)
            }
        }

        internal fun resolveBaseTargetPresentationUamActiveStatic(state: MainUiState): Boolean =
            state.uamRuntimeActive == true

        internal fun isManualMealEnergyInputValid(value: Double?): Boolean =
            value == null || value.isFinite() && value in 1.0..10_000.0

        internal fun serializeConfirmedMealGrams(value: Double): String {
            require(value.isFinite() && value > 0.0)
            return value.toString()
        }

        internal fun shouldPrepareClinicalReportOnRouteTransition(
            currentRoute: String,
            nextRoute: String
        ): Boolean = currentRoute != nextRoute && nextRoute == ROUTE_AI_ANALYSIS

        internal data class UiUamRuntimeSnapshot(
            val active: Boolean,
            val equivalentCarbsGrams: Double?,
            val confidence: Double?,
            val state: String?,
            val reason: String?,
            val source: String?
        )

        internal fun resolveFullUiUamRuntimeSnapshotStatic(
            telemetryByKey: Map<String, TelemetrySampleEntity>,
            nowTs: Long,
            acceptedCycleId: String?
        ): UiUamRuntimeSnapshot = resolveUiUamRuntimeSnapshotStatic(
            telemetryByKey = telemetryByKey,
            nowTs = nowTs,
            acceptedCycleId = acceptedCycleId
        )

        internal fun resolvePrimaryUiUamRuntimeSnapshotStatic(
            telemetryByKey: Map<String, TelemetrySampleEntity>,
            nowTs: Long,
            acceptedCycleId: String?
        ): UiUamRuntimeSnapshot = resolveUiUamRuntimeSnapshotStatic(
            telemetryByKey = telemetryByKey,
            nowTs = nowTs,
            acceptedCycleId = acceptedCycleId
        )

        private fun resolveUiUamRuntimeSnapshotStatic(
            telemetryByKey: Map<String, TelemetrySampleEntity>,
            nowTs: Long,
            acceptedCycleId: String?
        ): UiUamRuntimeSnapshot {
            val telemetryCycleId = telemetryByKey["uam_runtime_sensitivity_cycle_id"]
                ?.valueText
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (acceptedCycleId.isNullOrBlank() || telemetryCycleId != acceptedCycleId) {
                return UiUamRuntimeSnapshot(
                    active = false,
                    equivalentCarbsGrams = null,
                    confidence = null,
                    state = "INACTIVE",
                    reason = UAM_RUNTIME_UI_ACCEPTED_CYCLE_MISMATCH_REASON,
                    source = null
                )
            }
            val snapshotTs = UAM_RUNTIME_UI_TIMESTAMP_KEYS
                .mapNotNull { telemetryByKey[it]?.timestamp }
                .maxOrNull()
            val isFresh = snapshotTs != null &&
                snapshotTs <= nowTs &&
                nowTs - snapshotTs <= UAM_RUNTIME_UI_TTL_MS
            if (!isFresh) {
                return UiUamRuntimeSnapshot(
                    active = false,
                    equivalentCarbsGrams = null,
                    confidence = null,
                    state = "INACTIVE",
                    reason = UAM_RUNTIME_UI_STALE_REASON,
                    source = null
                )
            }

            fun numericValue(key: String): Double? = telemetryByKey[key]?.let { sample ->
                sample.valueDouble ?: sample.valueText?.trim()?.toDoubleOrNull()
            }
            val flag = numericValue("uam_runtime_flag")
            val state = telemetryByKey["uam_runtime_state"]
                ?.valueText
                ?.trim()
                ?.uppercase(Locale.US)
                ?.takeIf { it.isNotBlank() }
            return UiUamRuntimeSnapshot(
                active = isUiActiveUamRuntime(flag = flag, state = state),
                equivalentCarbsGrams = numericValue("uam_runtime_equivalent_carbs_grams")
                    ?.takeIf { it.isFinite() && it >= 0.0 },
                confidence = numericValue("uam_runtime_confidence")
                    ?.takeIf { it.isFinite() }
                    ?.coerceIn(0.0, 1.0),
                state = state,
                reason = telemetryByKey["uam_runtime_reason"]
                    ?.valueText?.trim()?.takeIf { it.isNotBlank() },
                source = telemetryByKey["uam_runtime_source"]
                    ?.valueText?.trim()?.takeIf { it.isNotBlank() }
            )
        }

        internal fun resolveUiUamImpactStatic(
            runtimeImpactMmol5: Double?
        ): Double? = runtimeImpactMmol5?.takeIf { it.isFinite() }

        internal data class LocalDailyReportRefreshDecision(
            val shouldGenerate: Boolean,
            val reason: String?
        )

        internal fun evaluateLocalDailyReportRefreshDecision(
            recentTelemetry: List<TelemetrySampleEntity>
        ): LocalDailyReportRefreshDecision {
            val reportRows = recentTelemetry.filter { it.source == DAILY_REPORT_TELEMETRY_SOURCE }
            val latestReportByKey = reportRows
                .groupBy { it.key.lowercase(Locale.US) }
                .mapValues { (_, rows) -> rows.maxByOrNull { it.timestamp } }

            val hasRecentLocalReport = (latestReportByKey["daily_report_matched_samples"]?.valueDouble ?: 0.0) > 0.0
            if (!hasRecentLocalReport) {
                return LocalDailyReportRefreshDecision(
                    shouldGenerate = true,
                    reason = DAILY_REPORT_REFRESH_REASON_MISSING_REPORT
                )
            }

            val runtimeRows = recentTelemetry.filter { it.source != DAILY_REPORT_TELEMETRY_SOURCE }
            val hasSensorLagAge = runtimeRows.any {
                it.key.lowercase(Locale.US) in SENSOR_LAG_DAILY_REPORT_AGE_KEYS && it.valueDouble != null
            }
            val hasSensorLagForecast = runtimeRows.any {
                it.key.lowercase(Locale.US) in SENSOR_LAG_DAILY_REPORT_FORECAST_KEYS && it.valueDouble != null
            }
            val hasSensorLagMode = runtimeRows.any {
                it.key.equals("sensor_lag_mode", ignoreCase = true) &&
                    (it.valueText.equals("ACTIVE", ignoreCase = true) ||
                        it.valueText.equals("SHADOW", ignoreCase = true))
            }
            if (!(hasSensorLagAge && hasSensorLagForecast && hasSensorLagMode)) {
                return LocalDailyReportRefreshDecision(shouldGenerate = false, reason = null)
            }

            val replayJson = latestReportByKey["daily_report_sensor_lag_bucket_json"]?.valueText
            if (!hasMeaningfulDailyReportJson(replayJson)) {
                return LocalDailyReportRefreshDecision(
                    shouldGenerate = true,
                    reason = DAILY_REPORT_REFRESH_REASON_SENSOR_LAG_REPLAY_MISSING
                )
            }

            val needsShadowJson = runtimeRows.any {
                it.key.equals("sensor_lag_mode", ignoreCase = true) &&
                    it.valueText.equals("SHADOW", ignoreCase = true)
            }
            val shadowJson = latestReportByKey["daily_report_sensor_lag_shadow_json"]?.valueText
            if (needsShadowJson && !hasMeaningfulDailyReportJson(shadowJson)) {
                return LocalDailyReportRefreshDecision(
                    shouldGenerate = true,
                    reason = DAILY_REPORT_REFRESH_REASON_SENSOR_LAG_SHADOW_MISSING
                )
            }

            return LocalDailyReportRefreshDecision(shouldGenerate = false, reason = null)
        }

        // Covers 24h at one-minute cadence with two source rows per timestamp plus margin.
        private const val PRIMARY_GLUCOSE_LATEST_LIMIT = 3_600
        private const val PRIMARY_FORECAST_LATEST_LIMIT = 96
        private const val PRIMARY_ACTION_LATEST_LIMIT = 16
        private const val PRIMARY_TELEMETRY_LATEST_LIMIT = 1_000
        private const val PRIMARY_TELEMETRY_OBSERVE_WINDOW_MS = 24L * 60L * 60L * 1000L
        private const val EVENT_TIMELINE_PAST_WINDOW_MS = 30L * 24L * 60L * 60L * 1_000L
        private const val EVENT_TIMELINE_FUTURE_WINDOW_MS = 30L * 24L * 60L * 60L * 1_000L
        private const val EVENT_TIMELINE_THERAPY_LIMIT = 1_500
        private const val EVENT_TIMELINE_CALIBRATION_LIMIT = 256
        private const val EVENT_TIMELINE_PLANNED_DEFINITION_LIMIT = 256
        private const val UAM_RUNTIME_UI_TTL_MS = 10L * 60L * 1000L
        private const val UAM_RUNTIME_UI_STALE_REASON = "runtime_snapshot_stale"
        private const val UAM_RUNTIME_UI_ACCEPTED_CYCLE_MISMATCH_REASON = "accepted_cycle_mismatch"
        private val UAM_RUNTIME_UI_TIMESTAMP_KEYS = setOf(
            "uam_runtime_flag",
            "uam_runtime_state",
            "uam_runtime_equivalent_carbs_grams",
            "uam_runtime_confidence"
        )
        private const val BLOOD_GLUCOSE_CHECK_LATEST_LIMIT = 24
        private const val GLUCOSE_LATEST_LIMIT = 1_800
        private const val THERAPY_LATEST_LIMIT = 1_500
        private const val PROBABLE_MEAL_CARB_LIMIT = 1_000
        private const val FORECAST_LATEST_LIMIT = 3_500
        private const val BASELINE_LATEST_LIMIT = 600
        private const val TELEMETRY_LATEST_LIMIT = 2_500
        private const val PROFILE_HISTORY_LIMIT = 9_000
        private const val ISF_CR_HISTORY_LIMIT = 9_000
        private const val ISF_CR_HISTORY_TELEMETRY_LIMIT = 9_000
        private const val ISF_CR_HISTORY_TELEMETRY_WINDOW_MS = 31L * 24L * 60L * 60L * 1000L
        private const val AI_TUNING_TELEMETRY_LIMIT = 4_000
        private const val AI_TUNING_TELEMETRY_WINDOW_MS = 72L * 60L * 60L * 1000L
        private const val SENSOR_LAG_HISTORY_TELEMETRY_WINDOW_MS = 48L * 60L * 60L * 1000L
        private const val CIRCADIAN_REPLAY_REFRESH_TTL_MS = 15L * 60L * 1000L
        private const val TLS_DIAGNOSTIC_WINDOW_MINUTES = 15
        private const val TLS_DIAGNOSTIC_WINDOW_MS = TLS_DIAGNOSTIC_WINDOW_MINUTES * 60_000L
        private const val HEALTH_CONNECT_ENABLED = false
        private const val ROUTE_OVERVIEW = "overview"
        private const val ROUTE_ANALYTICS = "analytics"
        private const val ROUTE_AI_ANALYSIS = "ai_analysis"
        private const val AI_MIN_DATA_HOURS = 24
        private const val MAX_AI_CHAT_ATTACHMENTS = 4
        private const val AI_TUNING_MIN_CONFIDENCE = 0.45
        private const val AI_TUNING_MIN_MATCHED_SAMPLES = 36.0
        private const val AI_TUNING_BLOCK_RISK_LEVEL = 3.0
        private const val TARGET_UI_TRUSTED_SENSOR_SCORE = 0.65
        private const val AI_TUNING_MAX_AGE_HOURS = 36L
        private const val AI_TUNING_MAX_AGE_MS = AI_TUNING_MAX_AGE_HOURS * 60L * 60L * 1000L
        private const val AI_TUNING_FUTURE_SKEW_TOLERANCE_MS = 5L * 60L * 1000L
        private const val AI_COVERAGE_LATEST_LIMIT = 2_000
        private const val AI_REPORT_RECENT_WINDOW_MS = 20L * 60L * 60L * 1000L
        private const val DAILY_REPORT_TELEMETRY_SOURCE = "forecast_daily_report"
        private const val DAILY_REPORT_REFRESH_REASON_MISSING_REPORT = "missing_daily_report"
        private const val DAILY_REPORT_REFRESH_REASON_SENSOR_LAG_REPLAY_MISSING = "sensor_lag_replay_missing"
        private const val DAILY_REPORT_REFRESH_REASON_SENSOR_LAG_SHADOW_MISSING = "sensor_lag_shadow_missing"
        private const val PHYSIO_TAG_JOURNAL_MAX_ROWS = 40
        private const val PHYSIO_TAG_JOURNAL_LOOKBACK_MS = 180L * 24 * 60 * 60 * 1000
        private const val ISFCR_REALTIME_UI_FRESHNESS_MS = 10L * 60L * 1000L
        private val CUMULATIVE_ACTIVITY_KEYS = setOf(
            "steps_count",
            "distance_km",
            "active_minutes",
            "calories_active_kcal"
        )
        private val PRIMARY_TELEMETRY_KEYS = buildPrimaryTelemetryKeysForUi()
        private val DAILY_REPORT_REFRESH_TELEMETRY_KEYS = buildList {
            add("daily_report_matched_samples")
            add("daily_report_sensor_lag_bucket_json")
            add("daily_report_sensor_lag_shadow_json")
            addAll(SENSOR_LAG_DAILY_REPORT_AGE_KEYS)
            addAll(SENSOR_LAG_DAILY_REPORT_FORECAST_KEYS)
            add("sensor_lag_mode")
        }.distinct()
    }

}

internal suspend fun applySensitivitySourceChange(
    metric: SensitivityMetricKind,
    requestedSource: SensitivitySourcePreference,
    applyAtomically: suspend ((AppSettings) -> AppSettings) -> SensitivityRuntimeSnapshot
): SensitivityRuntimeSnapshot {
    val accepted = applyAtomically { current ->
        when (metric) {
            SensitivityMetricKind.ISF -> current.copy(isfSourcePreference = requestedSource)
            SensitivityMetricKind.CR -> current.copy(crSourcePreference = requestedSource)
        }
    }
    val acceptedRequestedSource = when (metric) {
        SensitivityMetricKind.ISF -> accepted.isf.requested
        SensitivityMetricKind.CR -> accepted.cr.requested
    }
    require(acceptedRequestedSource == requestedSource) {
        "accepted sensitivity requested source mismatch: expected=$requestedSource, " +
            "actual=$acceptedRequestedSource"
    }
    return accepted
}

internal fun resolveAtomicInsulinRuntimeTelemetryForUi(
    samples: List<TelemetrySampleEntity>
): Map<String, TelemetrySampleEntity> = AtomicInsulinRuntimePacketContract.decodeNewest(samples)

internal fun resolveEffectivePositiveIobForUi(
    packet: Map<String, TelemetrySampleEntity>
): Double? = AtomicInsulinRuntimePacketContract.effectivePositiveIob(packet)

internal fun mergeAtomicInsulinRuntimeTelemetryForUi(
    latestByKey: Map<String, TelemetrySampleEntity>,
    samples: List<TelemetrySampleEntity>
): Map<String, TelemetrySampleEntity> =
    AtomicInsulinRuntimePacketContract.mergeWithLatest(latestByKey, samples)

internal fun resolveTargetManagerLiveStatusForUi(
    samples: List<TelemetrySampleEntity>
): TargetManagerLiveStatus? = samples
    .asSequence()
    .filter { row ->
        row.id == TargetManagerLiveStatusCodec.ROW_ID &&
            row.source == TargetManagerLiveStatusCodec.SOURCE &&
            row.key == TargetManagerLiveStatusCodec.KEY
    }
    .maxByOrNull(TelemetrySampleEntity::timestamp)
    .let(TargetManagerLiveStatusCodec::decodeTelemetryRow)

internal fun buildPrimaryTelemetryKeysForUi(): List<String> = listOf(
    "forecast_meal_steps",
    SENSITIVITY_ACCEPTED_CYCLE_ID_KEY,
    SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY,
    SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY,
    SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY,
    SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY,
    SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY,
    ACCEPTED_CALIBRATION_MODEL_ID_KEY,
    ACCEPTED_CALIBRATION_SESSION_KEY,
    ACCEPTED_CALIBRATION_PREPARED_AT_KEY,
    ACCEPTED_CALIBRATION_MODEL_FINGERPRINT_KEY,
    ACCEPTED_CALIBRATION_AUTHORITY_TOKEN_KEY,
    TargetManagerLiveStatusCodec.KEY
) + AtomicInsulinRuntimePacketContract.PACKET_KEYS + listOf(
    "cob_grams",
    "raw_cob",
    "cob_effective_grams",
    "glucose_raw_mmol",
    "glucose_calibrated_mmol",
    "glucose_calibration_source_ts",
    "glucose_calibration_gain",
    "glucose_calibration_offset_mmol",
    "glucose_calibration_confidence",
    "glucose_calibration_last_check_age_minutes",
    "glucose_calibration_model_type",
    "glucose_calibration_status",
    "glucose_calibration_sensor_session_key",
    "glucose_alert_state",
    "glucose_alert_direction",
    "glucose_alert_disable_reason",
    "glucose_alert_soft_active",
    "glucose_alert_strong_active",
    "sensor_quality_score",
    "sensor_quality_blocked",
    "sensor_quality_reason",
    "sensor_quality_suspect_false_low",
    "target_low_risk_latched",
    "sensor_age_hours",
    "isf_factor_sensor_age_hours",
    "sensor_lag_age_hours",
    "sensor_lag_corrected_glucose_mmol",
    "sensor_lag_minutes",
    "sensor_lag_mode",
    "sensor_lag_disable_reason",
    "insulin_real_onset_min",
    "activity_ratio",
    "steps_count",
    "uam_uci0_mmol5",
    "uam_calculated_flag",
    "uam_calculated_confidence",
    "uam_calculated_carbs_grams",
    "uam_calculated_delta5_mmol",
    "uam_calculated_rise15_mmol",
    "uam_calculated_rise30_mmol",
    "uam_inferred_flag",
    "uam_inferred_confidence",
    "uam_inferred_carbs_grams",
    "uam_inferred_ingestion_ts",
    "uam_inferred_boost_mode",
    "uam_manual_cob_grams",
    "uam_inferred_gabs_last5_g",
    "uam_runtime_flag",
    "uam_runtime_equivalent_carbs_grams",
    "uam_runtime_confidence",
    "uam_runtime_state",
    "uam_runtime_reason",
    "uam_runtime_source",
    "uam_runtime_sensitivity_cycle_id",
    "isf_realtime_value",
    "cr_realtime_value",
    "isf_realtime_confidence",
    "isf_realtime_quality_score",
    "isf_value",
    "cr_value",
    "isf_aaps_raw_value",
    "cr_aaps_raw_value",
    "isf_runtime_source_resolved",
    "cr_runtime_source_resolved",
    "isf_runtime_selected_value",
    "cr_runtime_selected_value",
    "isf_runtime_fallback_active",
    "cr_runtime_fallback_active"
)

private fun buildIsfCrHistoryPoints(
    profileHistory: List<ProfileEstimateEntity>,
    isfCrHistory: List<IsfCrRealtimeSnapshot>,
    telemetry: List<TelemetrySampleEntity>
): List<IsfCrHistoryPointUi> {
    if (profileHistory.isEmpty() && isfCrHistory.isEmpty() && telemetry.isEmpty()) return emptyList()

    val latestTs = listOfNotNull(
        profileHistory.maxOfOrNull { it.timestamp },
        isfCrHistory.maxOfOrNull { it.ts },
        telemetry.maxOfOrNull { it.timestamp }
    ).maxOrNull() ?: return emptyList()
    val minTs = (latestTs - ISFCR_ANALYTICS_WINDOW_MS).coerceAtLeast(0L)

    val profileSorted = profileHistory
        .asSequence()
        .filter { it.timestamp >= minTs }
        .sortedBy { it.timestamp }
        .toList()
    val runtimeSorted = isfCrHistory
        .asSequence()
        .filter { it.ts >= minTs }
        .sortedBy { it.ts }
        .toList()
    val aapsIsfSeries = buildAapsSeriesFromTelemetry(
        telemetry = telemetry,
        keys = setOf("isf_value", "raw_isf", "aaps_isf", "isf"),
        min = 0.2,
        max = 18.0,
        minTs = minTs
    )
    val aapsCrSeries = buildAapsSeriesFromTelemetry(
        telemetry = telemetry,
        keys = setOf("cr_value", "raw_cr", "aaps_cr", "cr"),
        min = 2.0,
        max = 60.0,
        minTs = minTs
    )
    val runtimeSeries = runtimeSorted.map {
        TimedRuntimeValue(
            ts = it.ts,
            isfCompensation = sanitizeIsfValue(it.compensationIsfForAnalytics()),
            crCompensation = sanitizeCrValue(it.compensationCrForAnalytics()),
            isfFallback = sanitizeIsfValue(it.fallbackIsfForAnalytics()),
            crFallback = sanitizeCrValue(it.fallbackCrForAnalytics())
        )
    }
    val profileSeries = profileSorted.map {
        TimedProfileValue(
            ts = it.timestamp,
            isfMerged = it.isfMmolPerUnit,
            crMerged = it.crGramPerUnit,
            isfCalculated = it.calculatedIsfMmolPerUnit,
            crCalculated = it.calculatedCrGramPerUnit
        )
    }
    val gridStartTs = (minTs / ISFCR_HISTORY_BUCKET_MS) * ISFCR_HISTORY_BUCKET_MS
    val gridEndTs = (latestTs / ISFCR_HISTORY_BUCKET_MS) * ISFCR_HISTORY_BUCKET_MS
    val sortedTimeline = buildList {
        if (gridEndTs >= gridStartTs) {
            var ts = gridStartTs
            while (ts <= gridEndTs) {
                add(ts)
                ts += ISFCR_HISTORY_BUCKET_MS
            }
        }
    }
    var lastIsfFallback: TimedMetricValue? = null
    var lastCrFallback: TimedMetricValue? = null
    var lastIsfReal: TimedMetricValue? = null
    var lastCrReal: TimedMetricValue? = null
    var lastAapsIsf: TimedMetricValue? = null
    var lastAapsCr: TimedMetricValue? = null

    return buildList {
        sortedTimeline.forEach { ts ->
            val runtimePoint = findNearestByTs(
                series = runtimeSeries,
                ts = ts,
                tsSelector = { it.ts },
                maxDistanceMs = RUNTIME_HISTORY_MATCH_MAX_DISTANCE_MS
            )
            val profilePoint = findNearestByTs(
                series = profileSeries,
                ts = ts,
                tsSelector = { it.ts },
                maxDistanceMs = PROFILE_HISTORY_MATCH_MAX_DISTANCE_MS
            )
            val nearestAapsIsf = findNearestTimedValue(
                series = aapsIsfSeries,
                ts = ts,
                maxDistanceMs = AAPS_HISTORY_MATCH_MAX_DISTANCE_MS
            )
            val nearestAapsCr = findNearestTimedValue(
                series = aapsCrSeries,
                ts = ts,
                maxDistanceMs = AAPS_HISTORY_MATCH_MAX_DISTANCE_MS
            )

            val rawIsfReal = sanitizeIsfValue(runtimePoint?.isfCompensation ?: profilePoint?.isfCalculated)
            val rawCrReal = sanitizeCrValue(runtimePoint?.crCompensation ?: profilePoint?.crCalculated)
            val carriedIsfReal = carryForwardMetric(lastIsfReal, ts, ISFCR_REAL_CARRY_MAX_DISTANCE_MS)
            val carriedCrReal = carryForwardMetric(lastCrReal, ts, ISFCR_REAL_CARRY_MAX_DISTANCE_MS)
            val isfReal = rawIsfReal ?: carriedIsfReal
            val crReal = rawCrReal ?: carriedCrReal

            val carriedAapsIsf = carryForwardMetric(lastAapsIsf, ts, ISFCR_AAPS_CARRY_MAX_DISTANCE_MS)
            val carriedAapsCr = carryForwardMetric(lastAapsCr, ts, ISFCR_AAPS_CARRY_MAX_DISTANCE_MS)
            val isfAaps = sanitizeIsfValue(nearestAapsIsf ?: carriedAapsIsf)
            val crAaps = sanitizeCrValue(nearestAapsCr ?: carriedAapsCr)

            val rawIsfFallback = sanitizeIsfValue(runtimePoint?.isfFallback ?: profilePoint?.isfMerged ?: isfAaps)
            val rawCrFallback = sanitizeCrValue(runtimePoint?.crFallback ?: profilePoint?.crMerged ?: crAaps)
            val isfFallback = rawIsfFallback ?: carryForwardMetric(lastIsfFallback, ts, ISFCR_FALLBACK_CARRY_MAX_DISTANCE_MS)
            val crFallback = rawCrFallback ?: carryForwardMetric(lastCrFallback, ts, ISFCR_FALLBACK_CARRY_MAX_DISTANCE_MS)
            if (isfFallback == null || crFallback == null) return@forEach

            if (isfReal != null) {
                lastIsfReal = TimedMetricValue(ts = ts, value = isfReal)
            }
            if (crReal != null) {
                lastCrReal = TimedMetricValue(ts = ts, value = crReal)
            }
            if (isfAaps != null) {
                lastAapsIsf = TimedMetricValue(ts = ts, value = isfAaps)
            }
            if (crAaps != null) {
                lastAapsCr = TimedMetricValue(ts = ts, value = crAaps)
            }
            lastIsfFallback = TimedMetricValue(ts = ts, value = isfFallback)
            lastCrFallback = TimedMetricValue(ts = ts, value = crFallback)

            add(
                IsfCrHistoryPointUi(
                    timestamp = ts,
                    isfMerged = isfFallback,
                    crMerged = crFallback,
                    isfCalculated = isfReal,
                    crCalculated = crReal,
                    isfAaps = isfAaps,
                    crAaps = crAaps
                )
            )
        }
    }
}

internal fun buildIsfCrOverlayPoints(
    historyPoints: List<IsfCrHistoryPointUi>,
    telemetry: List<TelemetrySampleEntity>
): List<IsfCrOverlayPointUi> {
    if (historyPoints.isEmpty() || telemetry.isEmpty()) return emptyList()

    val minTs = historyPoints.first().timestamp
    val cobSeries = buildTelemetrySeriesFromKeys(
        telemetry = telemetry,
        preferredKeys = listOf("cob_effective_grams", "cob_grams", "raw_cob"),
        min = 0.0,
        max = 400.0,
        minTs = minTs
    )
    val uamSeries = buildTelemetrySeriesFromKeys(
        telemetry = telemetry,
        preferredKeys = listOf("uam_runtime_equivalent_carbs_grams"),
        min = 0.0,
        max = 200.0,
        minTs = minTs
    )
    val activitySeries = buildTelemetrySeriesFromKeys(
        telemetry = telemetry,
        preferredKeys = listOf("activity_ratio"),
        min = 0.2,
        max = 3.0,
        minTs = minTs
    )

    return historyPoints.map { point ->
        IsfCrOverlayPointUi(
            timestamp = point.timestamp,
            cobGrams = findNearestTimedValue(
                series = cobSeries,
                ts = point.timestamp,
                maxDistanceMs = 30L * 60L * 1000L
            ),
            uamGrams = findNearestTimedValue(
                series = uamSeries,
                ts = point.timestamp,
                maxDistanceMs = 30L * 60L * 1000L
            ),
            activityRatio = findNearestTimedValue(
                series = activitySeries,
                ts = point.timestamp,
                maxDistanceMs = 60L * 60L * 1000L
            )
        )
    }
}

private data class TimedMetricValue(
    val ts: Long,
    val value: Double
)

private data class TimedLabelSegment(
    val startTs: Long,
    val endTs: Long,
    val label: String
)

private data class BucketedTelemetryMetricValue(
    val ts: Long,
    val value: Double,
    val priority: Int
)

private data class CachedUiValue<T>(
    val key: String,
    val value: T
)

private data class DailyReportBundle(
    val generatedAtTs: Long?,
    val matchedSamples: Int?,
    val forecastRows: Int?,
    val markdownPath: String?,
    val periodStartUtc: String?,
    val periodEndUtc: String?,
    val metrics: List<DailyReportMetricUi>,
    val recommendations: List<String>,
    val isfCrQualityLines: List<String>,
    val reportLines: List<String>,
    val replayHotspots: List<DailyReportReplayHotspotUi>,
    val replayFactors: List<DailyReportReplayFactorUi>,
    val replayCoverage: List<DailyReportReplayCoverageUi>,
    val replayRegimes: List<DailyReportReplayRegimeUi>,
    val replayPairs: List<DailyReportReplayPairUi>,
    val replayTopMisses: List<DailyReportReplayTopMissUi>,
    val replayErrorClusters: List<DailyReportReplayErrorClusterUi>,
    val replayDayTypeGaps: List<DailyReportReplayDayTypeGapUi>,
    val replayTopFactorsOverall: String?,
    val sensorLagReplayBuckets: List<DailyReportSensorLagReplayUi>,
    val sensorLagShadowBuckets: List<DailyReportSensorLagShadowUi>
)

private data class TimedRuntimeValue(
    val ts: Long,
    val isfCompensation: Double?,
    val crCompensation: Double?,
    val isfFallback: Double?,
    val crFallback: Double?
)

private data class TimedProfileValue(
    val ts: Long,
    val isfMerged: Double,
    val crMerged: Double,
    val isfCalculated: Double?,
    val crCalculated: Double?
)

private fun buildAapsSeriesFromTelemetry(
    telemetry: List<TelemetrySampleEntity>,
    keys: Set<String>,
    min: Double,
    max: Double,
    minTs: Long
): List<TimedMetricValue> {
    val normalizedKeys = keys.map { it.lowercase(Locale.US) }.toSet()
    val byBucket = linkedMapOf<Long, TimedMetricValue>()
    telemetry
        .asSequence()
        .filter { sample ->
            sample.key.lowercase(Locale.US) in normalizedKeys &&
                isAapsLikeTelemetrySource(sample.source) &&
                sample.timestamp >= minTs &&
                sample.timestamp > 0L
        }
        .forEach { sample ->
            val numeric = sample.valueDouble ?: sample.valueText?.replace(",", ".")?.toDoubleOrNull()
            if (numeric == null) return@forEach
            val normalized = normalizeAapsTelemetryMetric(key = sample.key, value = numeric)
            val sanitized = normalized.takeIf { it.isFinite() && it in min..max } ?: return@forEach
            val bucketTs = (sample.timestamp / ISFCR_HISTORY_BUCKET_MS) * ISFCR_HISTORY_BUCKET_MS
            val current = byBucket[bucketTs]
            if (current == null || sample.timestamp >= current.ts) {
                byBucket[bucketTs] = TimedMetricValue(
                    ts = sample.timestamp,
                    value = sanitized
                )
            }
    }
    return byBucket.values.sortedBy { it.ts }
}

private fun buildTelemetrySeriesFromKeys(
    telemetry: List<TelemetrySampleEntity>,
    preferredKeys: List<String>,
    min: Double,
    max: Double,
    minTs: Long
): List<TimedMetricValue> {
    val keyPriority = preferredKeys
        .mapIndexed { index, key -> key.lowercase(Locale.US) to index }
        .toMap()
    if (keyPriority.isEmpty()) return emptyList()
    val byBucket = linkedMapOf<Long, BucketedTelemetryMetricValue>()
    telemetry
        .asSequence()
        .filter { sample ->
            sample.timestamp >= minTs &&
                sample.timestamp > 0L &&
                keyPriority.containsKey(sample.key.lowercase(Locale.US))
        }
        .forEach { sample ->
            val numeric = sample.valueDouble ?: sample.valueText?.replace(",", ".")?.toDoubleOrNull()
            val sanitized = numeric?.takeIf { it.isFinite() && it in min..max } ?: return@forEach
            val bucketTs = (sample.timestamp / ISFCR_HISTORY_BUCKET_MS) * ISFCR_HISTORY_BUCKET_MS
            val priority = keyPriority.getValue(sample.key.lowercase(Locale.US))
            val current = byBucket[bucketTs]
            if (
                current == null ||
                priority < current.priority ||
                (priority == current.priority && sample.timestamp >= current.ts)
            ) {
                byBucket[bucketTs] = BucketedTelemetryMetricValue(
                    ts = sample.timestamp,
                    value = sanitized,
                    priority = priority
                )
            }
        }
    return byBucket.values
        .map { TimedMetricValue(ts = it.ts, value = it.value) }
        .sortedBy { it.ts }
}

private fun buildTelemetryTextSegments(
    telemetry: List<TelemetrySampleEntity>,
    key: String,
    minTs: Long,
    windowEndTs: Long,
    normalize: (String) -> String?
): List<TimedLabelSegment> {
    val rows = telemetry
        .asSequence()
        .filter { sample ->
            sample.timestamp >= minTs &&
                sample.timestamp > 0L &&
                sample.key.equals(key, ignoreCase = true)
        }
        .mapNotNull { sample ->
            val normalized = sample.valueText?.let(normalize) ?: return@mapNotNull null
            sample.timestamp to normalized
        }
        .sortedBy { it.first }
        .toList()
    if (rows.isEmpty()) return emptyList()
    if (rows.size == 1) {
        return listOf(
            TimedLabelSegment(
                startTs = minTs,
                endTs = windowEndTs.coerceAtLeast(minTs + 1L),
                label = rows.first().second
            )
        )
    }
    return buildList {
        rows.forEachIndexed { index, (ts, label) ->
            val nextTs = rows.getOrNull(index + 1)?.first ?: windowEndTs
            val start = ts.coerceAtLeast(minTs)
            val end = nextTs.coerceAtLeast(start + 1L)
            if (label.isBlank() || end <= start) return@forEachIndexed
            val previous = lastOrNull()
            if (previous != null && previous.label == label && start <= previous.endTs + ISFCR_HISTORY_BUCKET_MS) {
                set(lastIndex, previous.copy(endTs = end.coerceAtLeast(previous.endTs)))
            } else {
                add(TimedLabelSegment(startTs = start, endTs = end, label = label))
            }
        }
    }
}

private fun buildTimelineSegmentsFromValues(
    values: List<TimedMetricValue>,
    minTs: Long,
    windowEndTs: Long,
    labelForValue: (Double) -> String
): List<TimedLabelSegment> {
    if (values.isEmpty()) return emptyList()
    if (values.size == 1) {
        return listOf(
            TimedLabelSegment(
                startTs = minTs,
                endTs = windowEndTs.coerceAtLeast(minTs + 1L),
                label = labelForValue(values.first().value)
            )
        )
    }
    return buildList {
        values.forEachIndexed { index, point ->
            val nextTs = values.getOrNull(index + 1)?.ts ?: windowEndTs
            val start = point.ts.coerceAtLeast(minTs)
            val end = nextTs.coerceAtLeast(start + 1L)
            val label = labelForValue(point.value)
            val previous = lastOrNull()
            if (previous != null && previous.label == label && start <= previous.endTs + ISFCR_HISTORY_BUCKET_MS) {
                set(lastIndex, previous.copy(endTs = end.coerceAtLeast(previous.endTs)))
            } else {
                add(TimedLabelSegment(startTs = start, endTs = end, label = label))
            }
        }
    }
}

private fun sensorLagAgeBucketId(ageHours: Double): String = when {
    ageHours < 24.0 -> "<24h"
    ageHours < 240.0 -> "1-10d"
    ageHours < 288.0 -> "10-12d"
    ageHours < 336.0 -> "12-14d"
    else -> ">14d"
}

private fun carryForwardMetric(
    last: TimedMetricValue?,
    ts: Long,
    maxDistanceMs: Long
): Double? {
    if (last == null) return null
    return last.value.takeIf { kotlin.math.abs(ts - last.ts) <= maxDistanceMs }
}

private fun normalizeAapsTelemetryMetric(
    key: String,
    value: Double
): Double {
    if (!value.isFinite()) return value
    if (!key.lowercase(Locale.US).contains("isf")) return value
    // Some sources report ISF in mg/dL/U; normalize to mmol/L/U for a single chart scale.
    return if (value > 18.0) value / MMOL_TO_MGDL else value
}

private fun sanitizeIsfValue(value: Double?): Double? =
    value?.takeIf { it.isFinite() && it in 0.2..18.0 }

private fun sanitizeCrValue(value: Double?): Double? =
    value?.takeIf { it.isFinite() && it in 2.0..60.0 }

internal fun buildGlucoseHistoryPointsForUi(
    glucose: List<GlucoseSampleEntity>,
    nowTs: Long
): List<GlucoseHistoryRowUi> {
    val cutoffTs = nowTs - UI_GLUCOSE_HISTORY_WINDOW_MS
    return glucose
        .asSequence()
        .filter { it.timestamp >= cutoffTs && it.timestamp <= nowTs && it.mmol.isFinite() }
        .sortedBy { it.timestamp }
        .map { GlucoseHistoryRowUi(timestamp = it.timestamp, valueMmol = it.mmol) }
        .toList()
}

internal suspend fun runManualCalibrationFollowUp(
    followUp: suspend () -> Unit
): Exception? = try {
    followUp()
    null
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (fatal: Error) {
    throw fatal
} catch (operational: Exception) {
    operational
}

internal fun selectOverviewCurrentGlucoseForUi(
    glucose: List<GlucoseSampleEntity>,
    nowTs: Long
) = GlucoseSanitizer.selectCausalEntities(glucose, atTs = nowTs)

internal fun buildCalibrationResolvedHistoryPointsForUi(
    glucose: List<GlucoseHistoryRowUi>,
    calibrate: ((timestamp: Long, rawMmol: Double) -> Double)?
): List<ChartPointUi> = glucose.map { point ->
    ChartPointUi(
        ts = point.timestamp,
        value = calibrate?.invoke(point.timestamp, point.valueMmol) ?: point.valueMmol
    )
}

internal fun resolveUiCalibratedGlucose(
    pointTs: Long?,
    rawMmol: Double?,
    telemetryCalibratedMmol: Double?,
    telemetryCalibrationSourceTs: Long?,
    activeModel: GlucoseCalibrationModel?
): Double? {
    if (
        pointTs != null &&
        rawMmol?.isFinite() == true &&
        activeModel != null &&
        isCalibrationModelApplicableForUi(pointTs, activeModel)
    ) {
        return applyGlucoseCalibrationModelForUi(
            pointTs = pointTs,
            rawMmol = rawMmol,
            model = activeModel
        )
    }
    return rawMmol?.takeIf { it.isFinite() }
}

internal fun resolveOverviewCalibrationModelForUi(
    acceptedTuple: io.aaps.copilot.data.repository.AcceptedForecastTuple,
    currentModel: GlucoseCalibrationModel?
): GlucoseCalibrationModel? = if (acceptedTuple.error == null) {
    acceptedTuple.calibrationModel
} else {
    currentModel
}

internal fun selectUiActiveCalibrationModel(
    model: GlucoseCalibrationModel?,
    nowTs: Long,
    pointTs: Long? = nowTs,
    sensorAgeSamples: List<TelemetrySampleEntity>,
    evidenceTruncated: Boolean = false
): GlucoseCalibrationModel? = CalibrationModelAuthority.selectApplicableModel(
    model = model,
    nowTs = nowTs,
    pointTs = pointTs,
    telemetry = sensorAgeSamples,
    evidenceTruncated = evidenceTruncated
)

internal fun selectUiActiveCalibrationModelFromBoundedEvidence(
    model: GlucoseCalibrationModel?,
    nowTs: Long,
    pointTs: Long?,
    queriedSensorEvidence: List<TelemetrySampleEntity>,
    authorityToken: String? = null
): GlucoseCalibrationModel? = resolveUiCalibrationAuthorityFromBoundedEvidence(
    model = model,
    nowTs = nowTs,
    pointTs = pointTs,
    queriedSensorEvidence = queriedSensorEvidence,
    authorityToken = authorityToken
).activeModel

internal suspend fun loadUiCalibrationAuthority(
    db: io.aaps.copilot.data.local.CopilotDatabase,
    nowTs: Long,
    observedAuthorityToken: String?
): UiCalibrationAuthoritySelection = db.withTransaction {
    val authority = io.aaps.copilot.data.repository.loadCurrentGlucoseCalibrationAuthorityInTransaction(db, nowTs)
    // An independently observed token must not authorize an older UI generation after reset/edit.
    val tokenMatches = authority.authorityToken == observedAuthorityToken
    UiCalibrationAuthoritySelection(
        activeModel = authority.activeModel.takeIf { tokenMatches },
        currentSessionKey = authority.context.currentSessionKey,
        contextValid = authority.context.contextValid && tokenMatches
    )
}

internal fun resolveUiCalibrationAuthorityFromBoundedEvidence(
    model: GlucoseCalibrationModel?,
    nowTs: Long,
    pointTs: Long?,
    queriedSensorEvidence: List<TelemetrySampleEntity>,
    authorityToken: String? = null
): UiCalibrationAuthoritySelection {
    val evidence = CalibrationModelAuthority.selectBoundedSessionEvidence(
        queriedTelemetry = queriedSensorEvidence,
        nowTs = nowTs
    )
    val authorityContext = CalibrationModelAuthority.resolveAuthorityContext(
        nowTs = nowTs,
        pointTs = pointTs,
        telemetry = evidence.telemetry,
        evidenceTruncated = evidence.evidenceTruncated
    )
    val applicableModel = selectUiActiveCalibrationModel(
        model = model,
        nowTs = nowTs,
        pointTs = pointTs,
        sensorAgeSamples = evidence.telemetry,
        evidenceTruncated = evidence.evidenceTruncated
    )
    val durableAuthority = CalibrationAuthorityStateCodec.resolve(
        token = authorityToken,
        applicableModel = applicableModel,
        context = authorityContext
    )
    return UiCalibrationAuthoritySelection(
        activeModel = durableAuthority.activeModel,
        currentSessionKey = durableAuthority.currentSessionKey,
        contextValid = durableAuthority.contextValid
    )
}

internal data class UiCalibrationAuthoritySelection(
    val activeModel: GlucoseCalibrationModel?,
    val currentSessionKey: String?,
    val contextValid: Boolean
)

private fun applyGlucoseCalibrationModelForUi(
    pointTs: Long,
    rawMmol: Double,
    model: GlucoseCalibrationModel
): Double {
    if (!isCalibrationModelApplicableForUi(pointTs, model)) return rawMmol
    return CalibrationModelAuthority.applyToGlucose(pointTs, rawMmol, model)
}

private fun isCalibrationModelApplicableForUi(
    pointTs: Long,
    model: GlucoseCalibrationModel
): Boolean =
    model.status == io.aaps.copilot.domain.model.GlucoseCalibrationModelStatus.ACTIVE &&
        pointTs in model.validFromTs..model.validToTs &&
        CalibrationModelAuthority.strengthAt(pointTs, model) > 0.0

private fun isUiActiveUamRuntime(flag: Double?, state: String?): Boolean {
    if (flag?.isFinite() != true || flag < 0.5) return false
    return state?.trim()?.uppercase(Locale.US) in setOf("ACTIVE", "DECAYING")
}

private fun isAapsLikeTelemetrySource(source: String): Boolean {
    val normalized = source.lowercase(Locale.US)
    return normalized.contains("aaps") ||
        normalized.contains("xdrip") ||
        normalized.contains("openaps") ||
        normalized.contains("nightscout") ||
        normalized.contains("broadcast")
}

private fun SensitivityResolvedSource.uiCode(): Double = when (this) {
    SensitivityResolvedSource.AAPS -> 1.0
    SensitivityResolvedSource.EVIDENCE_BLEND -> 2.0
    SensitivityResolvedSource.COPILOT_NATIVE -> 3.0
}

private fun findNearestTimedValue(
    series: List<TimedMetricValue>,
    ts: Long,
    maxDistanceMs: Long
): Double? {
    if (series.isEmpty()) return null
    var low = 0
    var high = series.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        val midTs = series[mid].ts
        when {
            midTs < ts -> low = mid + 1
            midTs > ts -> high = mid - 1
            else -> return series[mid].value
        }
    }

    val right = series.getOrNull(low)
    val left = series.getOrNull(low - 1)
    val nearest = when {
        left == null -> right
        right == null -> left
        else -> {
            val leftDistance = kotlin.math.abs(left.ts - ts)
            val rightDistance = kotlin.math.abs(right.ts - ts)
            if (leftDistance <= rightDistance) left else right
        }
    } ?: return null

    val distance = kotlin.math.abs(nearest.ts - ts)
    return nearest.value.takeIf { distance <= maxDistanceMs }
}

private fun <T> findNearestByTs(
    series: List<T>,
    ts: Long,
    tsSelector: (T) -> Long,
    maxDistanceMs: Long
): T? {
    if (series.isEmpty()) return null
    var low = 0
    var high = series.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        val midTs = tsSelector(series[mid])
        when {
            midTs < ts -> low = mid + 1
            midTs > ts -> high = mid - 1
            else -> return series[mid]
        }
    }
    val right = series.getOrNull(low)
    val left = series.getOrNull(low - 1)
    val nearest = when {
        left == null -> right
        right == null -> left
        else -> {
            val leftDistance = kotlin.math.abs(tsSelector(left) - ts)
            val rightDistance = kotlin.math.abs(tsSelector(right) - ts)
            if (leftDistance <= rightDistance) left else right
        }
    } ?: return null

    val distance = kotlin.math.abs(tsSelector(nearest) - ts)
    return nearest.takeIf { distance <= maxDistanceMs }
}

private const val AAPS_HISTORY_MATCH_MAX_DISTANCE_MS = 30L * 60L * 1000L
private const val ISFCR_HISTORY_BUCKET_MS = 5L * 60L * 1000L
private const val ISFCR_ANALYTICS_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L
private const val RUNTIME_HISTORY_MATCH_MAX_DISTANCE_MS = 45L * 60L * 1000L
private const val SENSOR_LAG_HISTORY_WINDOW_MS = 24L * 60L * 60L * 1000L
private const val UI_GLUCOSE_HISTORY_WINDOW_MS = 24L * 60L * 60L * 1000L

private const val PROFILE_HISTORY_MATCH_MAX_DISTANCE_MS = 45L * 60L * 1000L
private const val ISFCR_REAL_CARRY_MAX_DISTANCE_MS = 90L * 60L * 1000L
private const val ISFCR_AAPS_CARRY_MAX_DISTANCE_MS = 60L * 60L * 1000L
private const val ISFCR_FALLBACK_CARRY_MAX_DISTANCE_MS = 4L * 60L * 60L * 1000L
private const val MMOL_TO_MGDL = 18.01559
private val ISFCR_HISTORY_TELEMETRY_KEYS = listOf(
    "isf_value",
    "raw_isf",
    "aaps_isf",
    "isf",
    "cr_value",
    "raw_cr",
    "aaps_cr",
    "cr",
    "cob_effective_grams",
    "cob_grams",
    "raw_cob",
    "uam_runtime_equivalent_carbs_grams",
    "activity_ratio"
)
private val AI_TUNING_TELEMETRY_KEYS = listOf(
    "daily_report_ai_opt_generated_ts",
    "daily_report_ai_opt_status",
    "daily_report_ai_opt_error",
    "daily_report_ai_opt_reason",
    "daily_report_ai_opt_confidence",
    "daily_report_ai_opt_apply_flag",
    "daily_report_ai_opt_gain_scale_5m",
    "daily_report_ai_opt_gain_scale_30m",
    "daily_report_ai_opt_gain_scale_60m",
    "daily_report_ai_opt_max_up_scale_5m",
    "daily_report_ai_opt_max_up_scale_30m",
    "daily_report_ai_opt_max_up_scale_60m",
    "daily_report_ai_opt_max_down_scale_5m",
    "daily_report_ai_opt_max_down_scale_30m",
    "daily_report_ai_opt_max_down_scale_60m",
    "daily_report_matched_samples",
    "daily_report_isfcr_quality_risk_level"
)
private const val SENSOR_LAG_HISTORY_TELEMETRY_LIMIT = 2_000
private val SENSOR_LAG_HISTORY_TELEMETRY_KEYS = listOf(
    "sensor_lag_minutes",
    "sensor_lag_correction_mmol",
    "sensor_lag_age_hours",
    "sensor_lag_mode"
)
private val SENSOR_LAG_DAILY_REPORT_AGE_KEYS = setOf(
    "sensor_lag_age_hours",
    "sensor_age_hours",
    "isf_factor_sensor_age_hours"
)
private val SENSOR_LAG_DAILY_REPORT_FORECAST_KEYS = setOf(
    "sensor_lag_candidate_forecast_5m",
    "sensor_lag_candidate_forecast_30m",
    "sensor_lag_candidate_forecast_60m",
    "sensor_lag_control_forecast_5m",
    "sensor_lag_control_forecast_30m",
    "sensor_lag_control_forecast_60m"
)

private fun hasMeaningfulDailyReportJson(value: String?): Boolean {
    val normalized = value?.trim().orEmpty()
    return normalized.isNotBlank() && normalized != "[]" && normalized != "{}" && normalized != "null"
}

private fun ForecastEntity.toDomainForecast(): io.aaps.copilot.domain.model.Forecast =
    io.aaps.copilot.domain.model.Forecast(
        ts = timestamp,
        horizonMinutes = horizonMinutes,
        valueMmol = valueMmol,
        ciLow = ciLow,
        ciHigh = ciHigh,
        modelVersion = modelVersion
    )

private fun IsfCrRealtimeSnapshot.compensationIsfForAnalytics(): Double? {
    val raw = factors["raw_isf_eff"]
    return when (mode) {
        IsfCrRuntimeMode.FALLBACK -> raw?.takeIf { it.isFinite() && it > 0.0 }
        else -> isfEff.takeIf { it.isFinite() && it > 0.0 }
    }
}

private fun IsfCrRealtimeSnapshot.compensationCrForAnalytics(): Double? {
    val raw = factors["raw_cr_eff"]
    return when (mode) {
        IsfCrRuntimeMode.FALLBACK -> raw?.takeIf { it.isFinite() && it > 0.0 }
        else -> crEff.takeIf { it.isFinite() && it > 0.0 }
    }
}

private fun IsfCrRealtimeSnapshot.fallbackIsfForAnalytics(): Double? {
    val candidate = if (mode == IsfCrRuntimeMode.FALLBACK) isfEff else isfBase
    return candidate.takeIf { it.isFinite() && it > 0.0 }
}

private fun IsfCrRealtimeSnapshot.fallbackCrForAnalytics(): Double? {
    val candidate = if (mode == IsfCrRuntimeMode.FALLBACK) crEff else crBase
    return candidate.takeIf { it.isFinite() && it > 0.0 }
}

private data class AapsTlsCompatibility(
    val installed: Boolean,
    val targetSdk: Int?,
    val networkSecurityConfigRes: Int?,
    val likelyRejectsUserCa: Boolean
)

class MainUiState {
    var nightscoutUrl: String = ""
    var cloudUrl: String = ""
    var uiStyle: String = UiStyle.MIDNIGHT_GLASS.name
    var exportUri: String? = null
    var killSwitch: Boolean = false
    var powerSaveUntilMs: Long = 0L
    var localNightscoutEnabled: Boolean = false
    var localNightscoutPort: Int = 17580
    var localNightscoutLegacyMigrationAcknowledged: Boolean = false
    var resolvedNightscoutUrl: String = ""
    var localBroadcastIngestEnabled: Boolean = true
    var strictBroadcastSenderValidation: Boolean = false
    var localCommandFallbackEnabled: Boolean = false
    var localCommandPackage: String = "info.nightscout.androidaps"
    var localCommandAction: String = "info.nightscout.client.NEW_TREATMENT"
    var insulinProfileId: String = "NOVORAPID"
    var enableUamInference: Boolean = true
    var enableUamBoost: Boolean = true
    var enableUamExportToAaps: Boolean = true
    var uamExportMode: String = "CONFIRMED_ONLY"
    var dryRunExport: Boolean = false
    var enableUamAutoExportCap: Boolean = false
    var uamAutoExportCapGrams: Int = 10
    var uamLearnedMultiplier: Double = 1.0
    var uamMinSnackG: Int = 15
    var uamMaxSnackG: Int = 60
    var uamSnackStepG: Int = 5
    var uamBackdateMinutesDefault: Int = 25
    var uamExportMinIntervalMin: Int = 10
    var uamExportMaxBackdateMin: Int = 180
    var sensorLagCorrectionMode: String = "OFF"
    var targetManagerMode: String = TargetManagerMode.SHADOW.name
    var targetManagerModeManualOverride: Boolean = false
    var targetManagerCopilotPriorityEnabled: Boolean = false
    var targetManagerPolicyRevision: Long = 0L
    internal var targetManagerLiveStatus: TargetManagerLiveStatus? = null
    var circadianPatternsEnabled: Boolean = true
    var circadianStableLookbackDays: Int = 14
    var circadianRecencyLookbackDays: Int = 5
    var circadianUseWeekendSplit: Boolean = true
    var circadianUseReplayResidualBias: Boolean = true
    var circadianForecastWeight30: Double = 0.25
    var circadianForecastWeight60: Double = 0.35
    var softAlertEnabled: Boolean = true
    var watch60AlertEnabled: Boolean = true
    var warning30AlertEnabled: Boolean = true
    var softHighAlertEnabled: Boolean = true
    var critical5AlertEnabled: Boolean = true
    var lowNowAlertEnabled: Boolean = true
    var softAlertLowMmol: Double = 4.4
    var softAlertHighMmol: Double = 10.0
    var urgentLowMmol: Double = 3.9
    var softAlertAudioUri: String? = null
    var softAlertAudioDisplayName: String? = null
    var softAlertAudioStartMs: Int = 32_000
    var softAlertAudioDurationMs: Int = 2_000
    var criticalAlertAudio1Uri: String? = null
    var criticalAlertAudio1DisplayName: String? = null
    var criticalAlertAudio1StartMs: Int = 42_000
    var criticalAlertAudio1DurationMs: Int = 20_000
    var criticalAlertAudio2Uri: String? = null
    var criticalAlertAudio2DisplayName: String? = null
    var criticalAlertAudio2StartMs: Int = 36_000
    var criticalAlertAudio2DurationMs: Int = 20_000
    var isfRuntimeSourcePreference: String = "UNAVAILABLE"
    var crRuntimeSourcePreference: String = "UNAVAILABLE"
    var isfRuntimeResolved: Double? = null
    var crRuntimeResolved: Double? = null
    var isfRuntimeSelected: Double? = null
    var crRuntimeSelected: Double? = null
    var isfRuntimeAaps: Double? = null
    var crRuntimeAaps: Double? = null
    var isfRuntimeEvidence: Double? = null
    var crRuntimeEvidence: Double? = null
    var isfRuntimeFallbackActive: Boolean = false
    var crRuntimeFallbackActive: Boolean = false
    var sensitivitySourceApplying: Boolean = false
    var sensitivitySourcePendingMetric: String? = null
    var sensitivitySourcePendingValue: String? = null
    var sensitivitySourceApplyError: String? = null
    var isfCrShadowMode: Boolean = true
    var isfCrConfidenceThreshold: Double = 0.55
    var isfCrUseActivity: Boolean = true
    var isfCrUseManualTags: Boolean = true
    var isfCrMinIsfEvidencePerHour: Int = 2
    var isfCrMinCrEvidencePerHour: Int = 2
    var isfCrCrMaxGapMinutes: Int = 30
    var isfCrCrMaxSensorBlockedRatePct: Double = 25.0
    var isfCrCrMaxUamAmbiguityRatePct: Double = 60.0
    var isfCrSnapshotRetentionDays: Int = 365
    var isfCrEvidenceRetentionDays: Int = 730
    var isfCrAutoActivationEnabled: Boolean = false
    var isfCrAutoActivationLookbackHours: Int = 24
    var isfCrAutoActivationMinSamples: Int = 72
    var isfCrAutoActivationMinMeanConfidence: Double = 0.65
    var isfCrAutoActivationMaxMeanAbsIsfDeltaPct: Double = 25.0
    var isfCrAutoActivationMaxMeanAbsCrDeltaPct: Double = 25.0
    var isfCrAutoActivationMinSensorQualityScore: Double = 0.46
    var isfCrAutoActivationMinSensorFactor: Double = 0.90
    var isfCrAutoActivationMaxWearConfidencePenalty: Double = 0.12
    var isfCrAutoActivationMaxSensorAgeHighRatePct: Double = 70.0
    var isfCrAutoActivationMaxSuspectFalseLowRatePct: Double = 35.0
    var isfCrAutoActivationMinDayTypeRatio: Double = 0.30
    var isfCrAutoActivationMaxDayTypeSparseRatePct: Double = 75.0
    var isfCrAutoActivationRequireDailyQualityGate: Boolean = true
    var isfCrAutoActivationDailyRiskBlockLevel: Int = 3
    var isfCrAutoActivationMinDailyMatchedSamples: Int = 120
    var isfCrAutoActivationMaxDailyMae30Mmol: Double = 0.90
    var isfCrAutoActivationMaxDailyMae60Mmol: Double = 1.40
    var isfCrAutoActivationMaxHypoRatePct: Double = 6.0
    var isfCrAutoActivationMinDailyCiCoverage30Pct: Double = 55.0
    var isfCrAutoActivationMinDailyCiCoverage60Pct: Double = 55.0
    var isfCrAutoActivationMaxDailyCiWidth30Mmol: Double = 1.80
    var isfCrAutoActivationMaxDailyCiWidth60Mmol: Double = 2.60
    var isfCrAutoActivationRollingMinRequiredWindows: Int = 2
    var isfCrAutoActivationRollingMaeRelaxFactor: Double = 1.15
    var isfCrAutoActivationRollingCiCoverageRelaxFactor: Double = 0.90
    var isfCrAutoActivationRollingCiWidthRelaxFactor: Double = 1.25
    var baseTargetMmol: Double = 5.5
    var postHypoThresholdMmol: Double = 4.0
    var postHypoDeltaThresholdMmol5m: Double = 0.20
    var postHypoTargetMmol: Double = 4.4
    var postHypoDurationMinutes: Int = 60
    var postHypoLookbackMinutes: Int = 90
    var adaptiveControllerEnabled: Boolean = true
    var adaptiveControllerPriority: Int = 120
    var adaptiveControllerRetargetMinutes: Int = 5
    var adaptiveControllerSafetyProfile: String = "BALANCED"
    var adaptiveControllerStaleMaxMinutes: Int = 15
    var adaptiveControllerMaxActions6h: Int = 4
    var adaptiveControllerMaxStepMmol: Double = 0.25
    var aiMinDataHours: Int = 24
    var aiDataCoverageHours: Double = 0.0
    var aiAnalysisReady: Boolean = false
    var aiTuningState: String = "BLOCKED"
    var aiTuningReason: String = "no optimizer report yet"
    var aiTuningGeneratedTs: Long? = null
    var aiTuningConfidence: Double? = null
    var aiTuningStatusRaw: String? = null
    var latestDataAgeMinutes: Long? = null
    var nightscoutSyncAgeMinutes: Long? = null
    var cloudPushBacklogMinutes: Long? = null
    var latestGlucoseMmol: Double? = null
    var rawGlucoseMmol: Double? = null
    var calibratedGlucoseMmol: Double? = null
    var glucoseCalibrationGain: Double? = null
    var glucoseCalibrationOffsetMmol: Double? = null
    var glucoseCalibrationConfidence: Double? = null
    var glucoseCalibrationLastCheckAgeMinutes: Double? = null
    var glucoseCalibrationModelType: String? = null
    var glucoseCalibrationStatus: String? = null
    var glucoseAlertState: String? = null
    var glucoseAlertDirection: String? = null
    var glucoseAlertDisableReason: String? = null
    var glucoseAlertSoftActive: Boolean? = null
    var glucoseAlertStrongActive: Boolean? = null
    var glucoseAlertSoftLastTs: Long? = null
    var glucoseAlertStrongLastTs: Long? = null
    var glucoseAlertPred30: Double? = null
    var glucoseAlertCiLow30: Double? = null
    var glucoseAlertCiHigh30: Double? = null
    var glucoseAlertLowThreshold: Double? = null
    var glucoseAlertHighThreshold: Double? = null
    var glucoseAlertUrgentLowThreshold: Double? = null
    var glucoseCalibrationChecks: List<BloodGlucoseCheck> = emptyList()
    var glucoseCalibrationModel: GlucoseCalibrationModel? = null
    var glucoseCalibrationRawHistoryPoints: List<ChartPointUi> = emptyList()
    var glucoseCalibrationResolvedHistoryPoints: List<ChartPointUi> = emptyList()
    var glucoseCalibrationCheckPoints: List<ChartPointUi> = emptyList()
    var correctedGlucoseMmol: Double? = null
    var glucoseDelta: Double? = null
    var latestIobUnits: Double? = null
    var latestIobRealUnits: Double? = null
    var latestIobBolusUnits: Double? = null
    var latestIobBasalUnits: Double? = null
    var latestInsulinActivity: Double? = null
    var latestIobRuntimeSource: String? = null
    var latestIobRuntimeConfidence: Double? = null
    var latestIobRuntimeTimestamp: Long? = null
    var latestIobEvidenceTimestamp: Long? = null
    var latestIobTherapyCoverage: Double? = null
    var latestIobRuntimeFallbackReason: String? = null
    var latestCobGrams: Double? = null
    var latestExternalCobGrams: Double? = null
    var latestExternalCobTimestamp: Long? = null
    var insulinRealOnsetMinutes: Double? = null
    var insulinRealProfileCurveCompact: String? = null
    var insulinRealProfileUpdatedTs: Long? = null
    var insulinRealProfileConfidence: Double? = null
    var insulinRealProfileSamples: Int? = null
    var insulinRealProfileOnsetMinutes: Double? = null
    var insulinRealProfilePeakMinutes: Double? = null
    var insulinRealProfileScale: Double? = null
    var insulinRealProfileStatus: String? = null
    var latestActivityRatio: Double? = null
    var latestStepsCount: Double? = null
    var forecast5m: Double? = null
    var forecast5mCiLow: Double? = null
    var forecast5mCiHigh: Double? = null
    var forecast30m: Double? = null
    var forecast30mCiLow: Double? = null
    var forecast30mCiHigh: Double? = null
    var forecast60m: Double? = null
    var forecast60mCiLow: Double? = null
    var forecast60mCiHigh: Double? = null
    var forecastTupleError: String? = null
    var forecastAcceptedGenerationTs: Long? = null
    var acceptedSensitivityRuntimeSnapshot: SensitivityRuntimeSnapshot? = null
    var calculatedUamActive: Boolean? = null
    var calculatedUamConfidence: Double? = null
    var calculatedUamCarbsGrams: Double? = null
    var calculatedUci0Mmol5m: Double? = null
    var calculatedUamDelta5Mmol: Double? = null
    var calculatedUamRise15Mmol: Double? = null
    var calculatedUamRise30Mmol: Double? = null
    var inferredUamActive: Boolean? = null
    var inferredUamConfidence: Double? = null
    var inferredUamCarbsGrams: Double? = null
    var inferredUamIngestionTs: Long? = null
    var inferredUamBoostMode: Boolean? = null
    var inferredUamManualCobGrams: Double? = null
    var inferredUamLastGAbsGrams: Double? = null
    var uamRuntimeActive: Boolean? = null
    var uamRuntimeEquivalentCarbsGrams: Double? = null
    var uamRuntimeConfidence: Double? = null
    var uamRuntimeState: String? = null
    var uamRuntimeReason: String? = null
    var uamRuntimeSource: String? = null
    var uamEventRows: List<UamEventRowUi> = emptyList()
    var sensorQualityScore: Double? = null
    var sensorQualityBlocked: Boolean? = null
    var sensorQualityReason: String? = null
    var sensorQualitySuspectFalseLow: Boolean? = null
    var targetLowRiskLatched: Boolean? = null
    var sensorLagMinutes: Double? = null
    var sensorAgeHours: Double? = null
    var sensorAgeSource: String? = null
    var sensorLagConfidence: Double? = null
    var sensorLagWearBucket: String? = null
    var sensorLagSourceConfidence: Double? = null
    var sensorLagTrendConsistency: Double? = null
    var sensorLagReplayMultiplier: Double? = null
    var sensorLagEffectiveLagMinutes: Double? = null
    var sensorLagEffectiveCorrectionCap: Double? = null
    var therapyHistorySourceMode: String? = null
    var therapyHistoryRawInsulin30d: Int? = null
    var therapyHistoryInferredInsulin30d: Int? = null
    var therapyHistoryRealFetchedInsulin30d: Int? = null
    var therapyHistoryRecoveredInsulin30d: Int? = null
    var therapyHistoryUsableInsulin30d: Int? = null
    var therapyHistoryBootstrapNeeded: Boolean? = null
    var therapyHistoryPlateauOnly: Boolean? = null
    var therapyHistorySyntheticRatioPct: Double? = null
    var therapyHistoryLastSyncTreatmentCount: Int? = null
    var therapyHistoryLastSyncInsulinLikeCount: Int? = null
    var therapyHistoryLastSyncCarbLikeCount: Int? = null
    var therapyHistoryLastSyncLocalActionCount: Int? = null
    var therapyHistoryUpstreamTempTargetOnly: Boolean? = null
    var sensorLagMode: String? = null
    var sensorLagDisableReason: String? = null
    var sensorLagTrendLagPoints: List<ChartPointUi> = emptyList()
    var sensorLagTrendCorrectionPoints: List<ChartPointUi> = emptyList()
    var sensorLagModeSegments: List<SensorLagTimelineSegmentUi> = emptyList()
    var sensorLagBucketSegments: List<SensorLagTimelineSegmentUi> = emptyList()
    var sensorLagTrendStartAgeHours: Double? = null
    var sensorLagTrendEndAgeHours: Double? = null
    var smbContextSummary: String = "No explicit SMB telemetry flags detected."
    var lastRuleState: String? = null
    var lastRuleId: String? = null
    var controllerState: String? = null
    var controllerReason: String? = null
    var controllerConfidence: Double? = null
    var controllerNextTarget: Double? = null
    var controllerDurationMinutes: Int? = null
    var controllerForecast30: Double? = null
    var controllerForecast60: Double? = null
    var controllerWeightedError: Double? = null
    var profileIsf: Double? = null
    var profileCr: Double? = null
    var profileConfidence: Double? = null
    var profileSamples: Int? = null
    var profileIsfSamples: Int? = null
    var profileCrSamples: Int? = null
    var profileTelemetryIsfSamples: Int? = null
    var profileTelemetryCrSamples: Int? = null
    var profileUamObservedCount: Int? = null
    var profileUamFilteredIsfSamples: Int? = null
    var profileUamEpisodes: Int? = null
    var profileUamCarbsGrams: Double? = null
    var profileUamRecentCarbsGrams: Double? = null
    var profileCalculatedIsf: Double? = null
    var profileCalculatedCr: Double? = null
    var profileCalculatedConfidence: Double? = null
    var profileCalculatedSamples: Int? = null
    var profileCalculatedIsfSamples: Int? = null
    var profileCalculatedCrSamples: Int? = null
    var profileLookbackDays: Int? = null
    var isfCrRealtimeMode: String? = null
    var isfCrRealtimeConfidence: Double? = null
    var isfCrRealtimeQualityScore: Double? = null
    var isfCrRealtimeIsfEff: Double? = null
    var isfCrRealtimeCrEff: Double? = null
    var isfCrRealtimeIsfBase: Double? = null
    var isfCrRealtimeCrBase: Double? = null
    var isfCrRealtimeCiIsfLow: Double? = null
    var isfCrRealtimeCiIsfHigh: Double? = null
    var isfCrRealtimeCiCrLow: Double? = null
    var isfCrRealtimeCiCrHigh: Double? = null
    var isfCrRealtimeFactors: List<String> = emptyList()
    var isfCrRuntimeDiagTs: Long? = null
    var isfCrRuntimeDiagMode: String? = null
    var isfCrRuntimeDiagConfidence: Double? = null
    var isfCrRuntimeDiagConfidenceThreshold: Double? = null
    var isfCrRuntimeDiagQualityScore: Double? = null
    var isfCrRuntimeDiagUsedEvidence: Int? = null
    var isfCrRuntimeDiagDroppedEvidence: Int? = null
    var isfCrRuntimeDiagDroppedReasons: String? = null
    var isfCrRuntimeDiagCurrentDayType: String? = null
    var isfCrRuntimeDiagIsfBaseSource: String? = null
    var isfCrRuntimeDiagCrBaseSource: String? = null
    var isfCrRuntimeDiagIsfDayTypeBaseAvailable: Boolean? = null
    var isfCrRuntimeDiagCrDayTypeBaseAvailable: Boolean? = null
    var isfCrRuntimeDiagHourWindowIsfEvidence: Int? = null
    var isfCrRuntimeDiagHourWindowCrEvidence: Int? = null
    var isfCrRuntimeDiagHourWindowIsfSameDayType: Int? = null
    var isfCrRuntimeDiagHourWindowCrSameDayType: Int? = null
    var isfCrRuntimeDiagMinIsfEvidencePerHour: Int? = null
    var isfCrRuntimeDiagMinCrEvidencePerHour: Int? = null
    var isfCrRuntimeDiagCrMaxGapMinutes: Double? = null
    var isfCrRuntimeDiagCrMaxSensorBlockedRatePct: Double? = null
    var isfCrRuntimeDiagCrMaxUamAmbiguityRatePct: Double? = null
    var isfCrRuntimeDiagCoverageHoursIsf: Int? = null
    var isfCrRuntimeDiagCoverageHoursCr: Int? = null
    var isfCrRuntimeDiagReasons: String? = null
    var isfCrRuntimeDiagLowConfidenceTs: Long? = null
    var isfCrRuntimeDiagLowConfidenceReasons: String? = null
    var isfCrRuntimeDiagFallbackTs: Long? = null
    var isfCrRuntimeDiagFallbackReasons: String? = null
    var isfCrActivationGateLines: List<String> = emptyList()
    var isfCrDroppedReasons24hLines: List<String> = emptyList()
    var isfCrDroppedReasons7dLines: List<String> = emptyList()
    var isfCrWearImpact24hLines: List<String> = emptyList()
    var isfCrWearImpact7dLines: List<String> = emptyList()
    var isfCrActiveTags: List<String> = emptyList()
    var events: List<CompensationEvent> = emptyList()
    var physiologicalSex: io.aaps.copilot.domain.profile.PhysiologicalSex = io.aaps.copilot.domain.profile.PhysiologicalSex.UNSPECIFIED
    var isfCrHistoryPoints: List<IsfCrHistoryPointUi> = emptyList()
    var isfCrHistoryOverlayPoints: List<IsfCrOverlayPointUi> = emptyList()
    var isfCrHistoryLastUpdatedTs: Long? = null
    var circadianPatternSections: List<CircadianPatternSectionUi> = emptyList()
    var circadianSlotStatCount: Int = 0
    var circadianTransitionStatCount: Int = 0
    var circadianSnapshotCount: Int = 0
    var circadianReplayStatCount: Int = 0
    var circadianLatestSnapshotUpdatedTs: Long? = null
    var circadianLatestReplayUpdatedTs: Long? = null
    var profileSegmentLines: List<String> = emptyList()
    var yesterdayProfileLines: List<String> = emptyList()
    var isfCrDeepLines: List<String> = emptyList()
    var activityLines: List<String> = emptyList()
    var rulePostHypoEnabled: Boolean = true
    var rulePatternEnabled: Boolean = true
    var ruleSegmentEnabled: Boolean = true
    var rulePostHypoPriority: Int = 100
    var rulePatternPriority: Int = 50
    var ruleSegmentPriority: Int = 40
    var rulePostHypoCooldownMinutes: Int = 30
    var rulePatternCooldownMinutes: Int = 60
    var ruleSegmentCooldownMinutes: Int = 60
    var patternMinSamplesPerWindow: Int = 40
    var patternMinActiveDaysPerWindow: Int = 7
    var patternLowRateTrigger: Double = 0.12
    var patternHighRateTrigger: Double = 0.18
    var analyticsLookbackDays: Int = 30
    var maxActionsIn6Hours: Int = 3
    var staleDataMaxMinutes: Int = 10
    var safetyMinTargetMmol: Double = 4.0
    var safetyMaxTargetMmol: Double = 10.0
    var carbAbsorptionMaxAgeMinutes: Int = 180
    var carbComputationMaxGrams: Double = 60.0
    var mealPortions: io.aaps.copilot.domain.nutrition.MealPortionSettings =
        io.aaps.copilot.domain.nutrition.MealPortionSettings()
    var weekdayHotHours: List<PatternWindow> = emptyList()
    var weekendHotHours: List<PatternWindow> = emptyList()
    var qualityMetrics: List<QualityMetricUi> = emptyList()
    var dailyReportGeneratedAtTs: Long? = null
    var dailyReportMatchedSamples: Int? = null
    var dailyReportForecastRows: Int? = null
    var dailyReportPeriodStartUtc: String? = null
    var dailyReportPeriodEndUtc: String? = null
    var dailyReportMarkdownPath: String? = null
    var dailyReportMetrics: List<DailyReportMetricUi> = emptyList()
    var dailyReportRecommendations: List<String> = emptyList()
    var dailyReportIsfCrQualityLines: List<String> = emptyList()
    var dailyReportReplayHotspots: List<DailyReportReplayHotspotUi> = emptyList()
    var dailyReportReplayFactors: List<DailyReportReplayFactorUi> = emptyList()
    var dailyReportReplayCoverage: List<DailyReportReplayCoverageUi> = emptyList()
    var dailyReportReplayRegimes: List<DailyReportReplayRegimeUi> = emptyList()
    var dailyReportReplayPairs: List<DailyReportReplayPairUi> = emptyList()
    var dailyReportReplayTopMisses: List<DailyReportReplayTopMissUi> = emptyList()
    var dailyReportReplayErrorClusters: List<DailyReportReplayErrorClusterUi> = emptyList()
    var dailyReportReplayDayTypeGaps: List<DailyReportReplayDayTypeGapUi> = emptyList()
    var dailyReportReplayTopFactorsOverall: String? = null
    var dailyReportSensorLagReplayBuckets: List<DailyReportSensorLagReplayUi> = emptyList()
    var dailyReportSensorLagShadowBuckets: List<DailyReportSensorLagShadowUi> = emptyList()
    var rollingReportLines: List<String> = emptyList()
    var baselineDeltaLines: List<String> = emptyList()
    var telemetryCoverageLines: List<String> = emptyList()
    var telemetryLines: List<String> = emptyList()
    var actionLines: List<String> = emptyList()
    var autoConnectLines: List<String> = emptyList()
    var transportStatusLines: List<String> = emptyList()
    var replacementHistoryLines: List<String> = emptyList()
    var syncStatusLines: List<String> = emptyList()
    var jobStatusLines: List<String> = emptyList()
    var cloudJobRows: List<CloudJobRowUi> = emptyList()
    var insightsFilterLabel: String = "Window: 7d"
    var insightsWindowDays: Int = 7
    var analysisHistoryItems: List<AnalysisHistoryRowUi> = emptyList()
    var analysisHistoryLines: List<String> = emptyList()
    var analysisTrendItems: List<AnalysisTrendRowUi> = emptyList()
    var analysisTrendLines: List<String> = emptyList()
    var ruleCooldownLines: List<String> = emptyList()
    var dryRun: DryRunUi? = null
    var cloudReplay: CloudReplayUiModel? = null
    var adaptiveAuditLines: List<String> = emptyList()
    var glucoseHistoryPoints: List<GlucoseHistoryRowUi> = emptyList()
    var lastAction: LastActionRowUi? = null
    var trend60ComponentMmol: Double? = null
    var mealImpactStepsJson: String? = null
    var therapy60ComponentMmol: Double? = null
    var uam60ComponentMmol: Double? = null
    var residualRoc0Mmol5m: Double? = null
    var sigmaEMmol5m: Double? = null
    var kfSigmaGMmol: Double? = null
    var patternPriorConfidence: Double? = null
    var patternPriorBgMedianMmol: Double? = null
    var patternPrior30Mmol: Double? = null
    var patternPrior60Mmol: Double? = null
    var patternPriorResidualBias30Mmol: Double? = null
    var patternPriorResidualBias60Mmol: Double? = null
    var patternPriorAcuteAttenuation: Double? = null
    var patternPriorStaleBlocked: Boolean? = null
    var patternPriorSegmentSource: String? = null
    var auditRecords: List<AuditRecordRowUi> = emptyList()
    var auditLines: List<String> = emptyList()
    var message: String? = null
}

internal fun MainUiState.applyPrimaryUamSettingsForUi(settings: AppSettings): MainUiState = apply {
    enableUamInference = settings.enableUamInference
    enableUamBoost = settings.enableUamBoost
    enableUamExportToAaps = settings.enableUamExportToAaps
    uamExportMode = settings.uamExportMode.name
    dryRunExport = settings.dryRunExport
    enableUamAutoExportCap = settings.enableUamAutoExportCap
    uamAutoExportCapGrams = settings.uamAutoExportCapGrams
}

/**
 * Keeps persistence of optional profile fields safe without making incomplete
 * demographic drafts unusable. This function has no writer and is unit-tested.
 */
internal sealed interface EnergyProfileSettingsUpdate {
    data class Persist(
        val settings: EnergyProfileSettings,
        val pediatricGoalReset: Boolean = false
    ) : EnergyProfileSettingsUpdate

    data class Reject(val violations: Set<ProfileViolation>) : EnergyProfileSettingsUpdate
}

internal fun evaluateEnergyProfileSettingsUpdate(
    current: EnergyProfileSettings,
    candidate: EnergyProfileSettings,
    today: LocalDate,
    policy: EnergyProfilePolicy = EnergyProfilePolicy()
): EnergyProfileSettingsUpdate {
    val candidateAge = candidate.birthDateEpochDay
        ?.let { epochDay -> runCatching { LocalDate.ofEpochDay(epochDay) }.getOrNull() }
        ?.let { birthDate -> policy.ageOn(birthDate, today) }
    val pediatricGoalReset = candidateAge in 3..18 &&
        candidate.calorieGoalMode in setOf(CalorieGoalMode.LOSS, CalorieGoalMode.GAIN)
    val normalized = if (pediatricGoalReset) {
        candidate.copy(
            calorieGoalMode = CalorieGoalMode.OFF,
            manualCalorieTargetKcal = null
        )
    } else {
        candidate
    }
    return when (val resolution = policy.resolve(normalized, today)) {
        is ProfileResolution.Invalid -> EnergyProfileSettingsUpdate.Reject(resolution.violations)
        is ProfileResolution.Complete,
        is ProfileResolution.Incomplete -> EnergyProfileSettingsUpdate.Persist(
            settings = normalized,
            pediatricGoalReset = pediatricGoalReset && current != normalized
        )
    }
}

internal fun energyProfilePolicyErrorRes(violation: ProfileViolation): Int = when (violation) {
    ProfileViolation.AGE_OUT_OF_RANGE -> R.string.energy_activity_policy_age_invalid
    ProfileViolation.HEIGHT_OUT_OF_RANGE -> R.string.energy_activity_policy_height_invalid
    ProfileViolation.WEIGHT_OUT_OF_RANGE -> R.string.energy_activity_policy_weight_invalid
    ProfileViolation.MANUAL_CALORIE_TARGET_OUT_OF_RANGE ->
        R.string.energy_activity_policy_manual_calories_invalid
    ProfileViolation.PEDIATRIC_CALORIE_GOAL_RESTRICTED ->
        R.string.energy_activity_pediatric_restriction
}

private fun io.aaps.copilot.ui.foundation.screens.PlannedActivityEventUi.toPlannedActivityScheduleOrNull():
    io.aaps.copilot.domain.profile.PlannedActivitySchedule? {
    val type = runCatching {
        io.aaps.copilot.domain.profile.PlannedActivityType.valueOf(activityType)
    }.getOrNull() ?: return null
    val intensity = runCatching {
        io.aaps.copilot.domain.profile.PlannedActivityIntensity.valueOf(intensity)
    }.getOrNull() ?: return null
    val start = runCatching { java.time.LocalDateTime.parse(localStartIso) }.getOrNull() ?: return null
    val zone = runCatching { ZoneId.of(timezoneId) }.getOrNull() ?: return null
    val days = java.time.DayOfWeek.entries.filter { day ->
        recurrenceDaysMask and (1 shl (day.value - 1)) != 0
    }.toSet()
    val recurrenceEnd = recurrenceEndEpochDay?.let { epochDay ->
        runCatching { LocalDate.ofEpochDay(epochDay) }.getOrNull() ?: return null
    }
    if (
        eventId.isBlank() ||
        title.isBlank() ||
        durationMinutes <= 0 ||
        revision < 0L ||
        recurrenceEnd?.isBefore(start.toLocalDate()) == true
    ) {
        return null
    }
    return io.aaps.copilot.domain.profile.PlannedActivitySchedule(
        eventId = eventId.trim(),
        enabled = enabled,
        title = title.trim(),
        type = type,
        intensity = intensity,
        localStart = start,
        durationMinutes = durationMinutes,
        timezoneId = zone.id,
        recurrenceDays = days,
        recurrenceEndEpochDay = recurrenceEnd?.toEpochDay(),
        revision = revision,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs
    )
}

internal fun resolvePrimaryAapsMetricValue(
    rawValue: Double?,
    directAapsValue: Double?
): Double? = rawValue
    ?.takeIf { it.isFinite() }
    ?: directAapsValue?.takeIf { it.isFinite() }

data class QualityMetricUi(
    val horizonMinutes: Int,
    val sampleCount: Int,
    val mae: Double,
    val rmse: Double,
    val mardPct: Double
)

data class DailyReportMetricUi(
    val horizonMinutes: Int,
    val sampleCount: Int?,
    val mae: Double?,
    val rmse: Double?,
    val mardPct: Double?,
    val bias: Double?,
    val ciCoveragePct: Double? = null,
    val ciMeanWidth: Double? = null
)

data class DailyReportReplayHotspotUi(
    val horizonMinutes: Int,
    val hour: Int,
    val sampleCount: Int,
    val mae: Double,
    val mardPct: Double,
    val bias: Double
)

data class DailyReportReplayFactorUi(
    val horizonMinutes: Int,
    val factor: String,
    val sampleCount: Int,
    val corrAbsError: Double,
    val maeHigh: Double,
    val maeLow: Double,
    val upliftPct: Double,
    val contributionScore: Double
)

data class DailyReportReplayCoverageUi(
    val horizonMinutes: Int,
    val factor: String,
    val sampleCount: Int,
    val coveragePct: Double
)

data class DailyReportReplayRegimeUi(
    val horizonMinutes: Int,
    val factor: String,
    val bucket: String,
    val sampleCount: Int,
    val meanFactorValue: Double,
    val mae: Double,
    val mardPct: Double,
    val bias: Double
)

data class DailyReportReplayPairUi(
    val horizonMinutes: Int,
    val factorA: String,
    val factorB: String,
    val bucketA: String,
    val bucketB: String,
    val sampleCount: Int,
    val meanFactorA: Double,
    val meanFactorB: Double,
    val mae: Double,
    val mardPct: Double,
    val bias: Double
)

data class DailyReportReplayTopMissUi(
    val horizonMinutes: Int,
    val ts: Long,
    val absError: Double,
    val pred: Double,
    val actual: Double,
    val cob: Double,
    val iob: Double,
    val uam: Double,
    val ciWidth: Double,
    val diaHours: Double,
    val activity: Double,
    val sensorQuality: Double
)

data class DailyReportReplayErrorClusterUi(
    val horizonMinutes: Int,
    val hour: Int,
    val dayType: String,
    val sampleCount: Int,
    val mae: Double,
    val mardPct: Double,
    val bias: Double,
    val meanCob: Double,
    val meanIob: Double,
    val meanUam: Double,
    val meanCiWidth: Double,
    val dominantFactor: String? = null,
    val dominantScore: Double? = null
)

data class DailyReportReplayDayTypeGapUi(
    val horizonMinutes: Int,
    val hour: Int,
    val worseDayType: String,
    val weekdaySampleCount: Int,
    val weekendSampleCount: Int,
    val weekdayMae: Double,
    val weekendMae: Double,
    val weekdayMardPct: Double,
    val weekendMardPct: Double,
    val maeGapMmol: Double,
    val mardGapPct: Double,
    val worseMeanCob: Double,
    val worseMeanIob: Double,
    val worseMeanUam: Double,
    val worseMeanCiWidth: Double,
    val dominantFactor: String? = null,
    val dominantScore: Double? = null
)

data class DailyReportSensorLagReplayUi(
    val horizonMinutes: Int,
    val bucket: String,
    val sampleCount: Int,
    val rawMae: Double,
    val lagMae: Double,
    val maeImprovementMmol: Double,
    val rawBias: Double,
    val lagBias: Double
)

data class DailyReportSensorLagShadowUi(
    val bucket: String,
    val sampleCount: Int,
    val ruleChangedRatePct: Double,
    val meanAbsTargetDeltaMmol: Double? = null
)

private data class PendingAiAttachment(
    val id: String,
    val name: String,
    val mimeType: String?,
    val localPath: String,
    val kind: AiChatAttachmentRequest.Kind,
    val sizeLabel: String?,
    val previewLabel: String?
) {
    fun toUi(): AiChatAttachmentUi = AiChatAttachmentUi(
        id = id,
        name = name,
        kind = kind.name.lowercase(Locale.US),
        mimeType = mimeType,
        sizeLabel = sizeLabel,
        previewLabel = previewLabel
    )
}

private data class AiChatComposerHeadState(
    val draft: String = "",
    val pendingAttachments: List<PendingAiAttachment> = emptyList(),
    val voiceRepliesEnabled: Boolean = false,
    val recording: Boolean = false
)

private data class AiChatComposerState(
    val draft: String = "",
    val pendingAttachments: List<PendingAiAttachment> = emptyList(),
    val voiceRepliesEnabled: Boolean = false,
    val recording: Boolean = false,
    val voiceBusy: Boolean = false,
    val speaking: Boolean = false
)

data class DryRunUi(
    val periodDays: Int,
    val samplePoints: Int,
    val lines: List<String>
)

data class InsightsFilterUi(
    val source: String? = null,
    val status: String? = null,
    val days: Int = 7,
    val weeks: Int = 1
)

data class AutoConnectUi(
    val lines: List<String>
)

data class GlucoseHistoryRowUi(
    val timestamp: Long,
    val valueMmol: Double
)

data class LastActionRowUi(
    val type: String,
    val status: String,
    val timestamp: Long,
    val tempTargetMmol: Double? = null,
    val durationMinutes: Int? = null,
    val carbsGrams: Double? = null,
    val idempotencyKey: String? = null,
    val payloadSummary: String? = null
)

data class CloudJobRowUi(
    val jobId: String,
    val lastStatus: String?,
    val lastRunTs: Long?,
    val nextRunTs: Long?,
    val lastMessage: String?
)

data class AnalysisHistoryRowUi(
    val runTs: Long,
    val date: String,
    val source: String,
    val status: String,
    val summary: String,
    val anomalies: List<String>,
    val recommendations: List<String>,
    val errorMessage: String?
)

data class AnalysisTrendRowUi(
    val weekStart: String,
    val totalRuns: Int,
    val successRuns: Int,
    val failedRuns: Int,
    val anomaliesCount: Int,
    val recommendationsCount: Int
)

data class UamEventRowUi(
    val id: String,
    val state: String,
    val mode: String,
    val createdAt: Long,
    val updatedAt: Long,
    val ingestionTs: Long,
    val carbsDisplayG: Double,
    val confidence: Double,
    val exportSeq: Int,
    val exportedGrams: Double,
    val tag: String,
    val manualCarbsNearby: Boolean,
    val manualCobActive: Boolean,
    val exportBlockedReason: String?
)

data class AuditRecordRowUi(
    val id: String,
    val ts: Long,
    val source: String,
    val level: String,
    val summary: String,
    val context: String,
    val idempotencyKey: String? = null,
    val payloadSummary: String? = null
)
