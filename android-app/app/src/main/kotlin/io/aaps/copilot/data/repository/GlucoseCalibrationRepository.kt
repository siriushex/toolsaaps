package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.dao.CalibrationInputWatermarkRow
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.model.BloodGlucoseCheck
import io.aaps.copilot.domain.model.BloodGlucoseCheckStatus
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucoseCalibrationModel
import io.aaps.copilot.domain.model.GlucoseCalibrationCycleIdentity
import io.aaps.copilot.domain.model.GlucoseCalibrationModelStatus
import io.aaps.copilot.domain.model.GlucoseCalibrationModelType
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.ResolvedGlucosePoint
import io.aaps.copilot.domain.model.CalibrationFitResult
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.util.SanitizedOperationalFailure
import io.aaps.copilot.util.UnitConverter
import io.aaps.copilot.util.boundedOperationalErrorType
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class GlucoseCalibrationRepository(
    private val db: CopilotDatabase,
    private val gson: Gson,
    private val auditLogger: AuditLogger,
    private val onManualCalibrationDurableMutation: suspend (ManualCalibrationDurableMutation) -> Unit = {},
    private val manualPostCommitFinalizationTimeoutMs: Long =
        DEFAULT_MANUAL_POST_COMMIT_FINALIZATION_TIMEOUT_MS,
    private val manualWidgetRefreshTimeoutMs: Long = DEFAULT_MANUAL_WIDGET_REFRESH_TIMEOUT_MS
) {

    init {
        require(manualPostCommitFinalizationTimeoutMs > 0L)
        require(manualWidgetRefreshTimeoutMs > 0L)
    }

    private val lifecycleMutex = Mutex()

    @Volatile
    private var refreshMemo: CalibrationRefreshMemo? = null

    @Volatile
    private var lastStaleModelSweepTs: Long = 0L

    private val calibrationSuppressedState = MutableStateFlow(false)
    private val calibrationSuppressed: Boolean
        get() = calibrationSuppressedState.value

    suspend fun addManualBloodGlucoseCheck(
        value: Double,
        units: String,
        timestamp: Long = System.currentTimeMillis(),
        note: String? = null,
        enteredAt: Long = System.currentTimeMillis()
    ): BloodGlucoseCheck = lifecycleMutex.withLock {
        addManualBloodGlucoseCheckLocked(
            value = value,
            units = units,
            timestamp = timestamp,
            note = note,
            enteredAt = enteredAt
        )
    }

    suspend fun resetManualCalibration(
        resetAt: Long = System.currentTimeMillis()
    ): GlucoseCalibrationResetResult = lifecycleMutex.withLock {
        val authorityNonce = UUID.randomUUID().toString()
        lateinit var authorityToken: String
        val result = db.withTransaction {
            authorityToken = CalibrationAuthorityStateCodec.raw(
                nonce = authorityNonce,
                sessionKey = resolveCurrentSensorSessionKey(resetAt)
            )
            db.telemetryDao().upsertAll(
                listOf(calibrationAuthorityTokenRow(authorityToken, resetAt))
            )
            GlucoseCalibrationResetResult(
                resetCheckCount = db.bloodGlucoseCheckDao().resetManualChecksThrough(resetAt),
                retiredModelCount = db.glucoseCalibrationModelDao().retireAllNonRetired(resetAt)
            )
        }
        refreshMemo = null
        withContext(NonCancellable) {
            reconcileManualCalibrationDurableState(
                expected = DurableManualCalibrationOutcome(model = null),
                atTs = resetAt,
                expectedCurrentAuthorityToken = authorityToken,
                finalAuthorityToken = authorityToken
            )
            notifyManualCalibrationDurableMutation(ManualCalibrationDurableMutation.RESET)
        }
        currentCoroutineContext().ensureActive()
        auditBestEffort {
            auditLogger.info(
                "glucose_calibration_manual_reset",
                mapOf(
                    "resetAt" to resetAt,
                    "resetCheckCount" to result.resetCheckCount,
                    "retiredModelCount" to result.retiredModelCount
                )
            )
        }
        result
    }

    private suspend fun addManualBloodGlucoseCheckLocked(
        value: Double,
        units: String,
        timestamp: Long,
        note: String?,
        enteredAt: Long
    ): BloodGlucoseCheck {
        val normalizedUnits = normalizeUnits(units)
        val mmol = normalizeMmol(value = value, units = normalizedUnits)
        require(mmol in MIN_GLUCOSE_MMOL..MAX_GLUCOSE_MMOL) {
            "blood check out of supported range"
        }

        val fitContext = buildFitContext(anchorTs = timestamp)
        val preparedRawGlucose = GlucoseCalibrationGuard.prepareRawGlucose(fitContext.glucose)
        val sessionKey = resolveSensorSessionKey(
            targetTs = timestamp,
            telemetry = fitContext.telemetry,
            therapy = fitContext.therapy,
            glucose = fitContext.glucose
        )
        val lagMinutes = resolveLagMinutesAt(
            targetTs = timestamp,
            telemetry = fitContext.telemetry
        ) ?: DEFAULT_FIT_LAG_MINUTES
        val delayedAssessment = assessCalibrationCheck(
            sessionKey = sessionKey,
            bloodTs = timestamp,
            bloodMmol = mmol,
            lagMinutes = lagMinutes,
            preparedRawGlucose = preparedRawGlucose,
            telemetry = fitContext.telemetry
        )
        val assessment = if (
            delayedAssessment.status == BloodGlucoseCheckStatus.OUT_OF_WINDOW &&
            delayedAssessment.reason == "aligned_glucose_gap"
        ) {
            selectInitialCalibrationAssessment(
                delayed = delayedAssessment,
                immediate = assessCalibrationCheck(
                    sessionKey = sessionKey,
                    bloodTs = timestamp,
                    bloodMmol = mmol,
                    lagMinutes = 0.0,
                    preparedRawGlucose = preparedRawGlucose,
                    telemetry = fitContext.telemetry
                ),
                enteredAt = enteredAt
            )
        } else {
            delayedAssessment
        }

        val check = BloodGlucoseCheck(
            id = "bgc-${UUID.randomUUID()}",
            timestamp = timestamp,
            mmol = mmol,
            units = normalizedUnits,
            source = "MANUAL",
            note = note?.trim()?.takeIf { it.isNotBlank() },
            enteredAt = enteredAt,
            sensorSessionKey = sessionKey,
            lagAlignedTs = assessment.lagAlignedTs,
            matchedRawGlucose = assessment.matchedRaw?.valueMmol,
            status = assessment.status,
            reason = assessment.reason
        )
        val authorityNonce = UUID.randomUUID().toString()
        val pendingAuthorityToken = CalibrationAuthorityStateCodec.pending(authorityNonce)
        db.withTransaction {
            db.bloodGlucoseCheckDao().upsert(check.toEntity())
            db.glucoseCalibrationModelDao().retireAllNonRetired(retiredAt = enteredAt)
            db.telemetryDao().upsertAll(
                listOf(calibrationAuthorityTokenRow(pendingAuthorityToken, enteredAt))
            )
        }
        refreshMemo = null
        finalizeManualAddAfterDurableCommit(
            enteredAt = enteredAt,
            authorityNonce = authorityNonce,
            authoritySessionKey = sessionKey,
            pendingAuthorityToken = pendingAuthorityToken,
            auditMessage = if (check.status == BloodGlucoseCheckStatus.VALID) {
                "blood_glucose_check_added"
            } else {
                "blood_glucose_check_rejected"
            },
            auditMetadata = mapOf(
                "id" to check.id,
                "bloodMmol" to mmol,
                "units" to normalizedUnits,
                "status" to check.status.name,
                "reason" to check.reason,
                "sensorSessionKey" to (sessionKey ?: "missing"),
                "bloodTs" to check.timestamp,
                "lagAlignedTs" to assessment.lagAlignedTs,
                "resolvedLagMinutes" to assessment.effectiveLagMinutes,
                "matchedRawMmol" to assessment.matchedRaw?.valueMmol,
                "absoluteGapMmol" to assessment.absoluteGapMmol,
                "relativeGap" to assessment.relativeGap,
                "alignedTrustAvailable" to assessment.alignedTrust.available,
                "alignedSensorBlocked" to assessment.alignedTrust.sensorBlocked,
                "alignedSensorSuspectFalseLow" to assessment.alignedTrust.sensorSuspectFalseLow,
                "alignedSensorStale" to assessment.alignedTrust.stale
            )
        )
        return check
    }

    private suspend fun finalizeManualAddAfterDurableCommit(
        enteredAt: Long,
        authorityNonce: String,
        authoritySessionKey: String?,
        pendingAuthorityToken: String,
        auditMessage: String,
        auditMetadata: Map<String, Any?>
    ) {
        var durableOutcome: DurableManualCalibrationOutcome? = null
        withContext(NonCancellable) {
            try {
                val completed = withTimeoutOrNull(manualPostCommitFinalizationTimeoutMs) {
                    auditBestEffort {
                        auditLogger.info(auditMessage, auditMetadata)
                    }
                    refreshCalibrationModelLocked(
                        nowTs = enteredAt,
                        force = false,
                        onDurableOutcome = { model ->
                            durableOutcome = DurableManualCalibrationOutcome(model)
                        }
                    )
                    true
                }
                if (completed == null) {
                    suppressCalibrationAfterFailure(
                        failureStage = "manual_post_commit_timeout",
                        error = IllegalStateException("manual calibration finalization timed out")
                    )
                }
            } finally {
                val finalAuthorityToken = durableOutcome?.model?.let { model ->
                    CalibrationAuthorityStateCodec.active(authorityNonce, model)
                } ?: CalibrationAuthorityStateCodec.raw(authorityNonce, authoritySessionKey)
                reconcileManualCalibrationDurableState(
                    expected = durableOutcome,
                    atTs = enteredAt,
                    expectedCurrentAuthorityToken = pendingAuthorityToken,
                    finalAuthorityToken = finalAuthorityToken
                )
                notifyManualCalibrationDurableMutation(ManualCalibrationDurableMutation.ADD)
            }
        }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun reconcileManualCalibrationDurableState(
        expected: DurableManualCalibrationOutcome?,
        atTs: Long,
        expectedCurrentAuthorityToken: String,
        finalAuthorityToken: String
    ) {
        val provenState = try {
            withTimeoutOrNull(manualPostCommitFinalizationTimeoutMs) {
                db.withTransaction {
                    check(currentCalibrationAuthorityToken() == expectedCurrentAuthorityToken) {
                        "manual calibration authority changed before durable reconciliation"
                    }
                    val persistedActive = db.glucoseCalibrationModelDao().latestActive()?.toDomain()
                    val expectedModel = expected?.model
                    if (expectedModel == null) {
                        check(persistedActive == null) {
                            "unexpected active calibration model remained before raw finalization"
                        }
                    } else {
                        check(persistedActive == expectedModel) {
                            "expected calibration model was not durably persisted"
                        }
                    }
                    if (expectedCurrentAuthorityToken != finalAuthorityToken) {
                        db.telemetryDao().upsertAll(
                            listOf(calibrationAuthorityTokenRow(finalAuthorityToken, atTs))
                        )
                    }
                    val sharedAuthority = loadCurrentGlucoseCalibrationAuthorityInTransaction(db, atTs)
                    check(sharedAuthority.authorityToken == finalAuthorityToken) {
                        "manual calibration authority marker did not finalize"
                    }
                    if (expectedModel == null) {
                        check(sharedAuthority.activeModel == null) {
                            "unexpected active calibration model remained authoritative"
                        }
                    } else {
                        check(sharedAuthority.activeModel == expectedModel) {
                            "expected active calibration model was not authoritative"
                        }
                    }
                    ProvenManualCalibrationDurableState(activeModel = persistedActive)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (operational: Exception) {
            throw ManualCalibrationDurableStateAmbiguousException(
                "could not prove coherent durable calibration state after manual operation",
                operational
            )
        } ?: throw ManualCalibrationDurableStateAmbiguousException(
            "could not prove coherent durable calibration state after manual operation: readback timed out"
        )

        if (provenState.activeModel != null) {
            calibrationSuppressedState.value = false
            return
        }
        refreshMemo = null
        calibrationSuppressedState.value = true
    }

    private suspend fun notifyManualCalibrationDurableMutation(
        mutation: ManualCalibrationDurableMutation
    ) {
        withContext(NonCancellable) {
            val refreshFailure = try {
                val completed = withTimeoutOrNull(manualWidgetRefreshTimeoutMs) {
                    onManualCalibrationDurableMutation(mutation)
                    true
                }
                if (completed == null) {
                    ManualWidgetRefreshFailure(
                        errorCode = MANUAL_WIDGET_REFRESH_TIMEOUT_ERROR_CODE,
                        errorType = MANUAL_WIDGET_REFRESH_TIMEOUT_ERROR_TYPE
                    )
                } else {
                    null
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (operational: Exception) {
                val sanitizedFailure = operational as? SanitizedOperationalFailure
                ManualWidgetRefreshFailure(
                    errorCode = sanitizedFailure?.errorCode
                        ?: MANUAL_WIDGET_REFRESH_CALLBACK_ERROR_CODE,
                    errorType = sanitizedFailure?.errorType
                        ?: boundedOperationalErrorType(operational)
                )
            }
            refreshFailure?.let { failure ->
                auditBestEffort {
                    auditLogger.warn(
                        "manual_glucose_calibration_widget_refresh_failed",
                        mapOf(
                            "mutation" to mutation.name,
                            "errorCode" to failure.errorCode,
                            "errorType" to failure.errorType
                        )
                    )
                }
            }
        }
    }

    suspend fun refreshCalibrationModel(
        nowTs: Long = System.currentTimeMillis(),
        force: Boolean = false
    ): GlucoseCalibrationModel? = lifecycleMutex.withLock {
        refreshCalibrationModelLocked(nowTs = nowTs, force = force)
    }

    internal suspend fun prepareAcceptedCycleCalibration(
        rawGlucose: List<GlucosePoint>,
        nowTs: Long,
        allowMaintenance: Boolean,
        additionalRawGlucose: List<GlucosePoint> = emptyList()
    ): PreparedCalibrationCycle = lifecycleMutex.withLock {
        val scopeChecks = db.bloodGlucoseCheckDao()
            .latest(limit = CALIBRATION_SCOPE_CHECK_LIMIT)
            .map { it.toDomain() }
        val inputScope = calibrationInputScope(nowTs = nowTs, recentChecks = scopeChecks)
        val inputWatermark = readCalibrationInputWatermark(inputScope)
        val inputAuthorityToken = currentCalibrationAuthorityToken()
        val currentSessionKey = resolveCurrentSensorSessionKey(nowTs)
        val latestNonRetired = db.glucoseCalibrationModelDao().latestNonRetired()?.toDomain()
        val computation = if (allowMaintenance) {
            buildCalibrationRefreshComputation(
                nowTs = nowTs,
                expectedInputScope = inputScope,
                expectedCurrentSessionKey = currentSessionKey
            )
        } else {
            null
        }
        requirePreparedCalibrationContextUnchanged(
            inputScope = inputScope,
            inputWatermark = inputWatermark,
            currentSessionKey = currentSessionKey,
            latestNonRetired = latestNonRetired,
            authorityToken = inputAuthorityToken,
            nowTs = nowTs
        )
        val shouldSweepStaleModels = allowMaintenance && shouldSweepStaleModels(
            nowTs = nowTs,
            force = false
        )
        val plannedModel = computation?.let { previewCalibrationOutcomeModel(it, nowTs) }
        val sanitized = GlucoseSanitizer.filterPoints(rawGlucose)
            .map { point ->
                GlucoseSampleEntity(
                    timestamp = point.ts,
                    mmol = point.valueMmol,
                    source = point.source,
                    quality = point.quality.name
                )
            }
            .sortedBy(GlucoseSampleEntity::timestamp)
        val baselineModels = if (sanitized.isEmpty()) {
            emptyList()
        } else {
            val durablyBoundCurrentModel = CalibrationAuthorityStateCodec.selectDurablyBoundModel(
                token = inputAuthorityToken,
                model = db.glucoseCalibrationModelDao().latestActive()?.toDomain()
            )?.takeIf { model -> CalibrationModelAuthority.isCausallyAvailableAt(model, nowTs) }
            db.glucoseCalibrationModelDao()
                .activeModelsSince(saturatingSubtract(sanitized.first().timestamp, MODEL_DECAY_WINDOW_MS))
                .map { it.toDomain() }
                .filter { model -> model.id == durablyBoundCurrentModel?.id }
                .sortedBy(GlucoseCalibrationModel::createdAt)
        }
        val effectiveModels = resolvePreparedActiveModels(
            baseline = baselineModels,
            computation = computation,
            plannedModel = plannedModel,
            shouldSweepStaleModels = shouldSweepStaleModels,
            nowTs = nowTs
        )
        val resolved = resolveSanitizedGlucoseWithModels(
            sanitized = sanitized,
            nowTs = nowTs,
            currentSessionKey = currentSessionKey,
            models = effectiveModels
        )
        val additionalResolved = resolveSanitizedGlucoseWithModels(
            sanitized = GlucoseSanitizer.filterPoints(additionalRawGlucose)
                .map { point ->
                    GlucoseSampleEntity(
                        timestamp = point.ts,
                        mmol = point.valueMmol,
                        source = point.source,
                        quality = point.quality.name
                    )
                }
                .sortedBy(GlucoseSampleEntity::timestamp),
            nowTs = nowTs,
            currentSessionKey = currentSessionKey,
            models = effectiveModels
        )
        val effectiveModel = when {
            plannedModel != null -> plannedModel
            (computation == null || computation.decision == CalibrationStateDecision.NoModel) &&
                currentSessionKey != null ->
                effectiveModels.lastOrNull { model ->
                    model.status == GlucoseCalibrationModelStatus.ACTIVE &&
                        model.sensorSessionKey == currentSessionKey &&
                        CalibrationModelAuthority.isCausallyAvailableAt(model, nowTs) &&
                        CalibrationModelAuthority.strengthAt(nowTs, model) > 0.0
                }
            else -> null
        }
        val authorityAlreadyExact = CalibrationAuthorityStateCodec.exactlyRepresents(
            token = inputAuthorityToken,
            model = effectiveModel,
            sessionKey = currentSessionKey
        )
        val shouldPublishAuthorityState = allowMaintenance && !authorityAlreadyExact
        val publishedAuthorityToken = if (shouldPublishAuthorityState) {
            val nonce = UUID.randomUUID().toString()
            effectiveModel?.let { model ->
                CalibrationAuthorityStateCodec.active(nonce, model)
            } ?: CalibrationAuthorityStateCodec.raw(nonce, currentSessionKey)
        } else {
            inputAuthorityToken
        }
        PreparedCalibrationCycle(
            resolvedGlucose = resolved,
            additionalResolvedGlucose = additionalResolved,
            model = effectiveModel,
            identity = GlucoseCalibrationCycleIdentity(
                modelId = effectiveModel?.id,
                sensorSessionKey = effectiveModel?.sensorSessionKey ?: currentSessionKey,
                preparedAtTs = nowTs
            ),
            maintenancePrepared = allowMaintenance,
            token = PreparedCalibrationToken(
                nowTs = nowTs,
                inputScope = inputScope,
                inputWatermark = inputWatermark,
                currentSessionKey = currentSessionKey,
                latestNonRetired = latestNonRetired,
                inputAuthorityToken = inputAuthorityToken,
                publishedAuthorityToken = publishedAuthorityToken,
                shouldPublishAuthorityState = shouldPublishAuthorityState,
                computation = computation,
                plannedModel = plannedModel,
                shouldSweepStaleModels = shouldSweepStaleModels
            )
        )
    }

    internal suspend fun <T> commitPreparedCalibrationAcceptance(
        prepared: PreparedCalibrationCycle,
        acceptedAtTs: Long,
        acceptedCommit: suspend () -> T
    ): T = lifecycleMutex.withLock {
        require(acceptedAtTs > 0L) { "accepted calibration timestamp must be positive" }
        val token = prepared.token as? PreparedCalibrationToken
            ?: error("prepared calibration token is invalid")
        var transactionOutcome: CalibrationTransactionOutcome? = null
        val result = db.withTransaction {
            requirePreparedCalibrationContextUnchanged(
                inputScope = token.inputScope,
                inputWatermark = token.inputWatermark,
                currentSessionKey = token.currentSessionKey,
                latestNonRetired = token.latestNonRetired,
                authorityToken = token.inputAuthorityToken,
                nowTs = token.nowTs
            )
            token.computation?.let { computation ->
                persistCalibrationComputation(
                    computation = computation,
                    nowTs = token.nowTs,
                    shouldSweepStaleModels = token.shouldSweepStaleModels
                ).also { outcome ->
                    check(outcome.model == token.plannedModel) {
                        "prepared calibration model identity changed during acceptance"
                    }
                    transactionOutcome = outcome
                }
            }
            if (token.shouldPublishAuthorityState) {
                db.telemetryDao().upsertAll(
                    listOf(
                        calibrationAuthorityTokenRow(
                            token = token.publishedAuthorityToken,
                            timestamp = acceptedAtTs
                        )
                    )
                )
            }
            persistPreparedCalibrationAcceptanceMarker(
                prepared = prepared,
                acceptedAtTs = acceptedAtTs,
                authorityToken = token.publishedAuthorityToken
            )
            acceptedCommit()
        }
        token.computation?.let { computation ->
            val outcome = requireNotNull(transactionOutcome)
            calibrationSuppressedState.value = false
            if (token.shouldSweepStaleModels) lastStaleModelSweepTs = token.nowTs
            val committedWatermark = readCalibrationInputWatermark(token.inputScope)
            refreshMemo = CalibrationRefreshMemo(
                computedAt = token.nowTs,
                latestCheckTs = computation.latestCheckTs,
                modelId = outcome.model?.id,
                model = outcome.model,
                currentSessionKey = token.currentSessionKey,
                inputWatermark = committedWatermark
            )
            emitSuccessfulRefreshAudits(computation, outcome, token.nowTs)
        }
        result
    }

    internal suspend fun reconcilePreparedCalibrationAcceptance(
        prepared: PreparedCalibrationCycle,
        acceptedAtTs: Long
    ): Boolean = readPreparedCalibrationAcceptance(prepared, acceptedAtTs) { true } == true

    internal suspend fun <T : Any> readPreparedCalibrationAcceptance(
        prepared: PreparedCalibrationCycle,
        acceptedAtTs: Long,
        acceptedRead: suspend () -> T?
    ): T? = lifecycleMutex.withLock {
        db.withTransaction {
            if (!preparedCalibrationAcceptanceMatches(prepared, acceptedAtTs)) {
                return@withTransaction null
            }
            acceptedRead()
        }
    }

    private suspend fun persistPreparedCalibrationAcceptanceMarker(
        prepared: PreparedCalibrationCycle,
        acceptedAtTs: Long,
        authorityToken: String
    ) {
        val telemetryDao = db.telemetryDao()
        val existing = telemetryDao.atTimestampBySourceAndKeys(
            source = ACCEPTED_CALIBRATION_SOURCE,
            timestamp = acceptedAtTs,
            keys = ACCEPTED_CALIBRATION_KEYS
        )
        check(existing.isEmpty()) { "accepted calibration marker already exists" }
        telemetryDao.upsertAll(
            listOf(
                acceptedCalibrationMarkerRow(
                    acceptedAtTs = acceptedAtTs,
                    key = ACCEPTED_CALIBRATION_MODEL_ID_KEY,
                    value = prepared.identity.modelId ?: ACCEPTED_CALIBRATION_NONE
                ),
                acceptedCalibrationMarkerRow(
                    acceptedAtTs = acceptedAtTs,
                    key = ACCEPTED_CALIBRATION_SESSION_KEY,
                    value = prepared.identity.sensorSessionKey ?: ACCEPTED_CALIBRATION_NONE
                ),
                acceptedCalibrationMarkerRow(
                    acceptedAtTs = acceptedAtTs,
                    key = ACCEPTED_CALIBRATION_PREPARED_AT_KEY,
                    value = prepared.identity.preparedAtTs.toString()
                ),
                acceptedCalibrationMarkerRow(
                    acceptedAtTs = acceptedAtTs,
                    key = ACCEPTED_CALIBRATION_MODEL_FINGERPRINT_KEY,
                    value = prepared.model?.let(::acceptedCalibrationModelFingerprint)
                        ?: ACCEPTED_CALIBRATION_NONE
                ),
                acceptedCalibrationMarkerRow(
                    acceptedAtTs = acceptedAtTs,
                    key = ACCEPTED_CALIBRATION_AUTHORITY_TOKEN_KEY,
                    value = authorityToken
                )
            )
        )
    }

    private suspend fun preparedCalibrationAcceptanceMatches(
        prepared: PreparedCalibrationCycle,
        acceptedAtTs: Long
    ): Boolean {
        val rows = db.telemetryDao().atTimestampBySourceAndKeys(
            source = ACCEPTED_CALIBRATION_SOURCE,
            timestamp = acceptedAtTs,
            keys = ACCEPTED_CALIBRATION_KEYS
        )
        val byKey = rows.groupBy(TelemetrySampleEntity::key)
        if (byKey.keys != ACCEPTED_CALIBRATION_KEYS.toSet() || byKey.values.any { it.size != 1 }) {
            return false
        }
        fun markerValue(key: String): String? = byKey.getValue(key).single().valueText
        if (markerValue(ACCEPTED_CALIBRATION_MODEL_ID_KEY) !=
            (prepared.identity.modelId ?: ACCEPTED_CALIBRATION_NONE)
        ) {
            return false
        }
        if (markerValue(ACCEPTED_CALIBRATION_SESSION_KEY) !=
            (prepared.identity.sensorSessionKey ?: ACCEPTED_CALIBRATION_NONE)
        ) {
            return false
        }
        if (markerValue(ACCEPTED_CALIBRATION_PREPARED_AT_KEY) !=
            prepared.identity.preparedAtTs.toString()
        ) {
            return false
        }
        if (markerValue(ACCEPTED_CALIBRATION_MODEL_FINGERPRINT_KEY) !=
            (prepared.model?.let(::acceptedCalibrationModelFingerprint) ?: ACCEPTED_CALIBRATION_NONE)
        ) {
            return false
        }
        if (markerValue(ACCEPTED_CALIBRATION_AUTHORITY_TOKEN_KEY) !=
            tokenFor(prepared).publishedAuthorityToken
        ) {
            return false
        }
        if (currentCalibrationAuthorityToken() != tokenFor(prepared).publishedAuthorityToken) {
            return false
        }
        return prepared.model?.let { model ->
            db.glucoseCalibrationModelDao().byId(model.id)?.toDomain() == model
        } ?: true
    }

    private fun acceptedCalibrationMarkerRow(
        acceptedAtTs: Long,
        key: String,
        value: String
    ) = TelemetrySampleEntity(
        id = UUID.randomUUID().toString(),
        timestamp = acceptedAtTs,
        source = ACCEPTED_CALIBRATION_SOURCE,
        key = key,
        valueDouble = null,
        valueText = value,
        unit = null,
        quality = "OK"
    )

    private suspend fun requirePreparedCalibrationContextUnchanged(
        inputScope: CalibrationInputScope,
        inputWatermark: CalibrationMemoInputWatermark,
        currentSessionKey: String?,
        latestNonRetired: GlucoseCalibrationModel?,
        authorityToken: String,
        nowTs: Long
    ) {
        check(readCalibrationInputWatermark(inputScope) == inputWatermark) {
            "calibration input changed before accepted publication"
        }
        check(resolveCurrentSensorSessionKey(nowTs) == currentSessionKey) {
            "calibration input changed before accepted publication: sensor session"
        }
        check(db.glucoseCalibrationModelDao().latestNonRetired()?.toDomain() == latestNonRetired) {
            "calibration input changed before accepted publication: model state"
        }
        check(currentCalibrationAuthorityToken() == authorityToken) {
            "calibration input changed before accepted publication: manual authority"
        }
    }

    private suspend fun currentCalibrationAuthorityToken(): String = db.telemetryDao()
        .currentBySourceAndKey(
            source = CALIBRATION_AUTHORITY_SOURCE,
            key = CALIBRATION_AUTHORITY_TOKEN_KEY
        )
        ?.valueText
        ?.takeIf(String::isNotBlank)
        ?: ACCEPTED_CALIBRATION_NONE

    private fun calibrationAuthorityTokenRow(token: String, timestamp: Long) = TelemetrySampleEntity(
        id = CALIBRATION_AUTHORITY_ROW_ID,
        timestamp = timestamp.coerceAtLeast(1L),
        source = CALIBRATION_AUTHORITY_SOURCE,
        key = CALIBRATION_AUTHORITY_TOKEN_KEY,
        valueDouble = null,
        valueText = token,
        unit = null,
        quality = "OK"
    )

    private fun tokenFor(prepared: PreparedCalibrationCycle): PreparedCalibrationToken =
        prepared.token as? PreparedCalibrationToken
            ?: error("prepared calibration token is invalid")

    private fun previewCalibrationOutcomeModel(
        computation: CalibrationRefreshComputation,
        nowTs: Long
    ): GlucoseCalibrationModel? = when (val decision = computation.decision) {
        is CalibrationStateDecision.ModelFit -> if (decision.reuseExisting) {
            val existing = requireNotNull(decision.existing)
            val candidate = decision.candidate
            existing.copy(
                createdAt = nowTs,
                validToTs = max(existing.validToTs, candidate.validToTs),
                confidence = candidate.confidence,
                checkCount = candidate.checkCount,
                sensorAgeHours = candidate.sensorAgeHours,
                lagMinutesAtFit = candidate.lagMinutesAtFit,
                status = candidate.status,
                diagnosticsJson = candidate.diagnosticsJson
            )
        } else {
            decision.candidate
        }
        else -> null
    }

    private fun resolvePreparedActiveModels(
        baseline: List<GlucoseCalibrationModel>,
        computation: CalibrationRefreshComputation?,
        plannedModel: GlucoseCalibrationModel?,
        shouldSweepStaleModels: Boolean,
        nowTs: Long
    ): List<GlucoseCalibrationModel> {
        val afterDecision = when (computation?.decision) {
            null, CalibrationStateDecision.NoModel -> baseline
            is CalibrationStateDecision.ModelFit -> listOfNotNull(
                plannedModel?.takeIf { it.status == GlucoseCalibrationModelStatus.ACTIVE }
            )
            else -> emptyList()
        }
        return afterDecision
            .filterNot { model ->
                shouldSweepStaleModels && model.createdAt <= nowTs - MODEL_DECAY_WINDOW_MS
            }
            .sortedBy(GlucoseCalibrationModel::createdAt)
    }

    private suspend fun refreshCalibrationModelLocked(
        nowTs: Long,
        force: Boolean,
        onDurableOutcome: ((GlucoseCalibrationModel?) -> Unit)? = null
    ): GlucoseCalibrationModel? {
        var contextChangeCount = 0
        while (contextChangeCount < MAX_CONTEXT_COMPUTE_ATTEMPTS) {
            try {
                val modelDao = db.glucoseCalibrationModelDao()
                val scopeChecks = db.bloodGlucoseCheckDao()
                    .latest(limit = CALIBRATION_SCOPE_CHECK_LIMIT)
                    .map { it.toDomain() }
                val inputScope = calibrationInputScope(
                    nowTs = nowTs,
                    recentChecks = scopeChecks
                )
                val latestCheck = scopeChecks
                    .asSequence()
                    .filter { it.timestamp >= inputScope.historySinceTs }
                    .maxByOrNull { it.timestamp }
                val latestCheckTs = latestCheck?.timestamp
                val inputWatermark = readCalibrationInputWatermark(inputScope)
                val latestNonRetired = modelDao.latestNonRetired()?.toDomain()
                val currentSessionKey = resolveCurrentSensorSessionKey(nowTs)
                val memo = refreshMemo
                val pendingAlignment = latestCheck?.let { check ->
                    shouldBypassCalibrationMemoForPendingAlignment(
                        nowTs = nowTs,
                        latestCheck = check
                    )
                } ?: false
                if (!force &&
                    !calibrationSuppressed &&
                    !pendingAlignment &&
                    memo?.currentSessionKey == currentSessionKey &&
                    (latestNonRetired == null || isCalibrationSessionContinuous(
                        currentSessionKey = currentSessionKey,
                        latestCheckSessionKey = latestNonRetired.sensorSessionKey
                    )) &&
                    shouldReuseRecentCalibrationModelStatic(
                        nowTs = nowTs,
                        latestCheckTs = latestCheckTs,
                        latestModelId = latestNonRetired?.id,
                        memoComputedAt = memo?.computedAt,
                        memoLatestCheckTs = memo?.latestCheckTs,
                        memoModelId = memo?.modelId,
                        currentInputWatermark = inputWatermark,
                        memoInputWatermark = memo?.inputWatermark
                    )
                ) {
                    val reused = memo?.model ?: latestNonRetired
                    onDurableOutcome?.invoke(reused)
                    return reused
                }

                val computation = buildCalibrationRefreshComputation(
                    nowTs = nowTs,
                    expectedInputScope = inputScope,
                    expectedCurrentSessionKey = currentSessionKey
                )
                val shouldSweepStaleModels = shouldSweepStaleModels(
                    nowTs = nowTs,
                    force = force
                )
                val commit = db.withTransaction {
                    if (readCalibrationInputWatermark(inputScope) != inputWatermark) {
                        throw CalibrationContextChangedException()
                    }
                    if (resolveCurrentSensorSessionKey(nowTs) != currentSessionKey) {
                        throw CalibrationContextChangedException()
                    }
                    val outcome = persistCalibrationComputation(
                        computation = computation,
                        nowTs = nowTs,
                        shouldSweepStaleModels = shouldSweepStaleModels
                    )
                    CalibrationTransactionCommit(
                        outcome = outcome,
                        inputWatermark = readCalibrationInputWatermark(inputScope)
                    )
                }

                calibrationSuppressedState.value = false
                if (shouldSweepStaleModels) {
                    lastStaleModelSweepTs = nowTs
                }
                refreshMemo = CalibrationRefreshMemo(
                    computedAt = nowTs,
                    latestCheckTs = computation.latestCheckTs,
                    modelId = commit.outcome.model?.id,
                    model = commit.outcome.model,
                    currentSessionKey = currentSessionKey,
                    inputWatermark = commit.inputWatermark
                )
                onDurableOutcome?.invoke(commit.outcome.model)
                emitSuccessfulRefreshAudits(
                    computation = computation,
                    outcome = commit.outcome,
                    nowTs = nowTs
                )
                return commit.outcome.model
            } catch (contextChanged: CalibrationContextChangedException) {
                contextChangeCount += 1
                if (contextChangeCount >= MAX_CONTEXT_COMPUTE_ATTEMPTS) {
                    suppressCalibrationAfterFailure(
                        failureStage = "context_changed_retry_exhausted",
                        error = contextChanged
                    )
                    return null
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                suppressCalibrationAfterFailure(
                    failureStage = "refresh_transaction",
                    error = error
                )
                return null
            }
        }
        return null
    }

    private suspend fun buildCalibrationRefreshComputation(
        nowTs: Long,
        expectedInputScope: CalibrationInputScope,
        expectedCurrentSessionKey: String?
    ): CalibrationRefreshComputation {
        val recentChecks = db.bloodGlucoseCheckDao()
            .since(expectedInputScope.historySinceTs)
            .map { it.toDomain() }
            .sortedBy { it.timestamp }
        val actualInputScope = calibrationInputScope(
            nowTs = nowTs,
            recentChecks = recentChecks
        )
        if (actualInputScope != expectedInputScope) {
            throw CalibrationContextChangedException()
        }
        val latestCheckTs = recentChecks.lastOrNull()?.timestamp
        if (latestCheckTs == null) {
            return CalibrationRefreshComputation(
                reassessedChecks = emptyList(),
                reassessmentAudits = emptyList(),
                decision = CalibrationStateDecision.NoModel,
                latestCheckTs = null
            )
        }

        val context = buildFitContext(
            anchorTs = nowTs,
            relevantThroughTs = actualInputScope.relevantThroughTs
        )
        val preparedRawGlucose = GlucoseCalibrationGuard.prepareRawGlucose(context.glucose)

        val reassessed = recentChecks.map { check ->
            reassessCheck(
                check = check,
                nowTs = nowTs,
                rawGlucose = context.glucose,
                preparedRawGlucose = preparedRawGlucose,
                telemetry = context.telemetry,
                therapy = context.therapy
            )
        }
        val reassessmentAudits = recentChecks.zip(reassessed).mapNotNull { (before, after) ->
            reassessmentAuditMetadata(
                before = before,
                after = after,
                telemetry = context.telemetry
            )
        }
        val latestSessionKey = reassessed.last().sensorSessionKey
            ?.takeIf { it.isNotBlank() }
        if (latestSessionKey == null) {
            return CalibrationRefreshComputation(
                reassessedChecks = reassessed,
                reassessmentAudits = reassessmentAudits,
                decision = CalibrationStateDecision.UnresolvedLatestCheck,
                latestCheckTs = latestCheckTs
            )
        }
        if (expectedCurrentSessionKey == null) {
            return CalibrationRefreshComputation(
                reassessedChecks = reassessed,
                reassessmentAudits = reassessmentAudits,
                decision = CalibrationStateDecision.UnresolvedCurrentSession,
                latestCheckTs = latestCheckTs
            )
        }
        if (!isCalibrationSessionContinuous(expectedCurrentSessionKey, latestSessionKey)) {
            return CalibrationRefreshComputation(
                reassessedChecks = reassessed,
                reassessmentAudits = reassessmentAudits,
                decision = CalibrationStateDecision.SessionMismatch(
                    currentSessionKey = expectedCurrentSessionKey,
                    latestCheckSessionKey = latestSessionKey
                ),
                latestCheckTs = latestCheckTs
            )
        }

        val fit = fitCalibrationModel(
            nowTs = nowTs,
            bloodChecks = reassessed,
            telemetry = context.telemetry,
            sensorSessionKey = latestSessionKey
        )
        if (fit.validChecks.isEmpty() || fit.model == null) {
            return CalibrationRefreshComputation(
                reassessedChecks = reassessed,
                reassessmentAudits = reassessmentAudits,
                decision = CalibrationStateDecision.InvalidFit(latestSessionKey),
                latestCheckTs = latestCheckTs
            )
        }

        val candidate = fit.model
        val existing = db.glucoseCalibrationModelDao()
            .latestNonRetiredInSession(candidate.sensorSessionKey)
            ?.toDomain()
        return CalibrationRefreshComputation(
            reassessedChecks = reassessed,
            reassessmentAudits = reassessmentAudits,
            decision = CalibrationStateDecision.ModelFit(
                candidate = candidate,
                existing = existing,
                reuseExisting = existing != null && areEquivalentModelsForReuse(existing, candidate)
            ),
            latestCheckTs = latestCheckTs
        )
    }

    private suspend fun readCalibrationInputWatermark(
        scope: CalibrationInputScope
    ): CalibrationMemoInputWatermark = db.glucoseCalibrationModelDao()
        .calibrationInputWatermark(
            historySinceTs = scope.historySinceTs,
            relevantThroughTs = scope.relevantThroughTs,
            telemetryKeys = CALIBRATION_TELEMETRY_KEYS.toList()
        )
        .toMemoInputWatermark()

    private fun fitCalibrationModel(
        nowTs: Long,
        bloodChecks: List<BloodGlucoseCheck>,
        telemetry: CalibrationTelemetryIndex,
        sensorSessionKey: String
    ): CalibrationFitResult {
        val sessionChecks = bloodChecks
            .filter { it.sensorSessionKey == sensorSessionKey }
            .sortedBy { it.timestamp }
        if (sessionChecks.isEmpty()) {
            return CalibrationFitResult(
                model = null,
                validChecks = emptyList(),
                rejectedChecks = emptyList(),
                diagnostics = mapOf("reason" to "no_session_checks")
            )
        }
        val fitScopeChecks = selectChecksForCalibrationFit(sessionChecks)

        val validChecks = fitScopeChecks.filter { it.status == BloodGlucoseCheckStatus.VALID }
        val rejectedChecks = sessionChecks.filter { it.status != BloodGlucoseCheckStatus.VALID }

        val model = buildModelForChecks(
            nowTs = nowTs,
            sessionKey = sensorSessionKey,
            checks = validChecks,
            telemetry = telemetry
        )
        return CalibrationFitResult(
            model = model,
            validChecks = validChecks,
            rejectedChecks = rejectedChecks,
            diagnostics = mapOf(
                "validCount" to validChecks.size,
                "rejectedCount" to rejectedChecks.size,
                "sensorSessionKey" to sensorSessionKey
            )
        )
    }

    private fun reassessmentAuditMetadata(
        before: BloodGlucoseCheck,
        after: BloodGlucoseCheck,
        telemetry: CalibrationTelemetryIndex
    ): Map<String, Any?>? {
        val assessmentChanged = before.status != after.status ||
            before.reason != after.reason ||
            before.lagAlignedTs != after.lagAlignedTs ||
            before.matchedRawGlucose != after.matchedRawGlucose
        if (!assessmentChanged) return null

        val alignedSnapshot = telemetrySnapshotAt(
            targetTs = calibrationQualityTimestamp(after),
            telemetry = telemetry
        )
        val matchedRawMmol = after.matchedRawGlucose
        val absoluteGapMmol = matchedRawMmol?.let { abs(after.mmol - it) }
        val relativeGap = matchedRawMmol?.let {
            abs(after.mmol - it) / maxOf(abs(after.mmol), abs(it), 1.0)
        }
        val resolvedLagMinutes = after.lagAlignedTs?.let { alignedTs ->
            (alignedTs - after.timestamp).coerceAtLeast(0L) / 60_000.0
        }
        return mapOf(
            "id" to after.id,
            "bloodTs" to after.timestamp,
            "lagAlignedTs" to after.lagAlignedTs,
            "resolvedLagMinutes" to resolvedLagMinutes,
            "previousStatus" to before.status.name,
            "status" to after.status.name,
            "reason" to after.reason,
            "matchedRawMmol" to matchedRawMmol,
            "absoluteGapMmol" to absoluteGapMmol,
            "relativeGap" to relativeGap,
            "alignedSensorQualityScore" to alignedSnapshot.sensorQualityScore,
            "alignedTrustAvailable" to alignedSnapshot.sensorTrustAvailable,
            "alignedSensorBlocked" to alignedSnapshot.sensorBlocked,
            "alignedSensorSuspectFalseLow" to alignedSnapshot.sensorSuspectFalseLow,
            "alignedSensorStale" to alignedSnapshot.stale
        )
    }

    private suspend fun persistCalibrationComputation(
        computation: CalibrationRefreshComputation,
        nowTs: Long,
        shouldSweepStaleModels: Boolean
    ): CalibrationTransactionOutcome {
        if (computation.reassessedChecks.isNotEmpty()) {
            db.bloodGlucoseCheckDao().upsertAll(computation.reassessedChecks.map { it.toEntity() })
        }
        val modelDao = db.glucoseCalibrationModelDao()
        val stateOutcome = when (val decision = computation.decision) {
            CalibrationStateDecision.NoModel -> CalibrationTransactionOutcome(model = null)
            CalibrationStateDecision.UnresolvedLatestCheck -> CalibrationTransactionOutcome(
                model = null,
                retiredAllSessions = modelDao.retireAllNonRetired(retiredAt = nowTs)
            )
            CalibrationStateDecision.UnresolvedCurrentSession -> CalibrationTransactionOutcome(
                model = null,
                retiredAllSessions = modelDao.retireAllNonRetired(retiredAt = nowTs)
            )
            is CalibrationStateDecision.SessionMismatch -> CalibrationTransactionOutcome(
                model = null,
                retiredAllSessions = modelDao.retireAllNonRetired(retiredAt = nowTs)
            )
            is CalibrationStateDecision.InvalidFit -> {
                val retiredOtherSessions = modelDao.retireOtherSessions(
                    sensorSessionKey = decision.sensorSessionKey,
                    retiredAt = nowTs
                )
                val retiredCurrentSession = modelDao.retireNonRetiredInSession(
                    sensorSessionKey = decision.sensorSessionKey,
                    retiredAt = nowTs
                )
                CalibrationTransactionOutcome(
                    model = null,
                    retiredCurrentSession = retiredCurrentSession,
                    retiredOtherSessions = retiredOtherSessions
                )
            }
            is CalibrationStateDecision.ModelFit -> {
                val model = if (decision.reuseExisting) {
                    val existing = requireNotNull(decision.existing)
                    val candidate = decision.candidate
                    val reused = existing.copy(
                        createdAt = nowTs,
                        validToTs = max(existing.validToTs, candidate.validToTs),
                        confidence = candidate.confidence,
                        checkCount = candidate.checkCount,
                        sensorAgeHours = candidate.sensorAgeHours,
                        lagMinutesAtFit = candidate.lagMinutesAtFit,
                        status = candidate.status,
                        diagnosticsJson = candidate.diagnosticsJson
                    )
                    val updated = modelDao.refreshExistingModel(
                        id = reused.id,
                        createdAt = reused.createdAt,
                        validToTs = reused.validToTs,
                        confidence = reused.confidence,
                        checkCount = reused.checkCount,
                        sensorAgeHours = reused.sensorAgeHours,
                        lagMinutesAtFit = reused.lagMinutesAtFit,
                        status = reused.status.name,
                        diagnosticsJson = reused.diagnosticsJson
                    )
                    check(updated == 1) { "calibration model refresh lost its target row" }
                    reused
                } else {
                    modelDao.upsert(decision.candidate.toEntity())
                    decision.candidate
                }
                CalibrationTransactionOutcome(
                    model = model,
                    reusedExisting = decision.reuseExisting,
                    retiredOtherSessions = modelDao.retireOtherSessions(
                        sensorSessionKey = model.sensorSessionKey,
                        retiredAt = model.validFromTs
                    ),
                    retiredOtherNonRetiredModels = modelDao.retireOtherNonRetiredModelsInSession(
                        sensorSessionKey = model.sensorSessionKey,
                        keepId = model.id,
                        retiredAt = model.validFromTs
                    )
                )
            }
        }
        val retiredStaleModels = if (shouldSweepStaleModels) {
            modelDao.retireExpiredNonRetired(
                expiredCreatedAt = nowTs - MODEL_DECAY_WINDOW_MS,
                retiredAt = nowTs
            )
        } else {
            0
        }
        return stateOutcome.copy(retiredStaleModels = retiredStaleModels)
    }

    private suspend fun emitSuccessfulRefreshAudits(
        computation: CalibrationRefreshComputation,
        outcome: CalibrationTransactionOutcome,
        nowTs: Long
    ) {
        computation.reassessmentAudits.forEach { metadata ->
            auditBestEffort {
                auditLogger.info("blood_glucose_check_reassessed", metadata)
            }
        }
        when (computation.decision) {
            CalibrationStateDecision.NoModel -> Unit
            CalibrationStateDecision.UnresolvedLatestCheck -> {
                if (outcome.retiredAllSessions > 0) {
                    auditBestEffort {
                        auditLogger.info(
                            "glucose_calibration_models_retired_unresolved_latest_check",
                            mapOf(
                                "retiredAllSessions" to outcome.retiredAllSessions,
                                "retiredAt" to nowTs
                            )
                        )
                    }
                }
            }
            CalibrationStateDecision.UnresolvedCurrentSession -> {
                if (outcome.retiredAllSessions > 0) {
                    auditBestEffort {
                        auditLogger.info(
                            "glucose_calibration_models_retired_unresolved_current_session",
                            mapOf(
                                "retiredAllSessions" to outcome.retiredAllSessions,
                                "retiredAt" to nowTs
                            )
                        )
                    }
                }
            }
            is CalibrationStateDecision.SessionMismatch -> {
                auditBestEffort {
                    auditLogger.info(
                        "glucose_calibration_models_retired_sensor_session_mismatch",
                        mapOf(
                            "currentSensorSessionKey" to computation.decision.currentSessionKey,
                            "latestCheckSessionKey" to computation.decision.latestCheckSessionKey,
                            "retiredAllSessions" to outcome.retiredAllSessions,
                            "retiredAt" to nowTs
                        )
                    )
                }
            }
            is CalibrationStateDecision.InvalidFit -> {
                if (outcome.retiredCurrentSession > 0 || outcome.retiredOtherSessions > 0) {
                    auditBestEffort {
                        auditLogger.info(
                            "glucose_calibration_models_retired_invalid_checks",
                            mapOf(
                                "retiredCurrentSession" to outcome.retiredCurrentSession,
                                "retiredOtherSessions" to outcome.retiredOtherSessions,
                                "retiredAt" to nowTs
                            )
                        )
                    }
                }
            }
            is CalibrationStateDecision.ModelFit -> {
                val model = outcome.model ?: return
                auditBestEffort {
                    auditLogger.info(
                        if (outcome.reusedExisting) {
                            "glucose_calibration_model_reused"
                        } else {
                            "glucose_calibration_model_updated"
                        },
                        mapOf(
                            "id" to model.id,
                            "sensorSessionKey" to model.sensorSessionKey,
                            "modelType" to model.modelType.name,
                            "gain" to model.gain,
                            "offsetMmol" to model.offsetMmol,
                            "confidence" to model.confidence,
                            "checkCount" to model.checkCount,
                            "retiredOtherSessions" to outcome.retiredOtherSessions,
                            "retiredOtherNonRetiredModels" to outcome.retiredOtherNonRetiredModels
                        )
                    )
                }
            }
        }
        if (outcome.retiredStaleModels > 0) {
            auditBestEffort {
                auditLogger.info(
                    "glucose_calibration_models_retired_stale",
                    mapOf(
                        "retiredModels" to outcome.retiredStaleModels,
                        "retiredAt" to nowTs
                    )
                )
            }
        }
    }

    private fun shouldSweepStaleModels(nowTs: Long, force: Boolean): Boolean {
        if (force || lastStaleModelSweepTs == 0L) return true
        val elapsedMs = nowTs - lastStaleModelSweepTs
        return elapsedMs !in 0L until STALE_MODEL_SWEEP_INTERVAL_MS
    }

    private suspend fun suppressCalibrationAfterFailure(
        failureStage: String,
        error: Throwable
    ) {
        refreshMemo = null
        calibrationSuppressedState.value = true
        auditBestEffort {
            auditLogger.error(
                "glucose_calibration_refresh_failed_closed",
                mapOf(
                    "failureStage" to failureStage,
                    "errorType" to error::class.java.simpleName,
                    "calibrationSuppressed" to true
                )
            )
        }
    }

    private suspend fun auditBestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // State persistence must not be rolled back or suppressed by audit-only failures.
        }
    }

    suspend fun resolveGlucoseHistory(
        rawGlucose: List<GlucoseSampleEntity>,
        nowTs: Long = System.currentTimeMillis()
    ): List<ResolvedGlucosePoint> = lifecycleMutex.withLock {
        resolveGlucoseHistoryLocked(rawGlucose = rawGlucose, nowTs = nowTs)
    }

    private suspend fun resolveGlucoseHistoryLocked(
        rawGlucose: List<GlucoseSampleEntity>,
        nowTs: Long
    ): List<ResolvedGlucosePoint> {
        if (rawGlucose.isEmpty()) return emptyList()
        val sanitized = GlucoseSanitizer.filterEntities(rawGlucose).sortedBy { it.timestamp }
        if (sanitized.isEmpty()) return emptyList()
        if (calibrationSuppressed) return sanitized.map { sample -> resolveRawSample(sample) }
        val currentModel = CalibrationAuthorityStateCodec.selectDurablyBoundModel(
            token = currentCalibrationAuthorityToken(),
            model = db.glucoseCalibrationModelDao().latestActive()?.toDomain()
        )?.takeIf { model -> CalibrationModelAuthority.isCausallyAvailableAt(model, nowTs) }
            ?: return sanitized.map { sample -> resolveRawSample(sample) }
        val currentSessionKey = resolveCurrentSensorSessionKey(nowTs)
        val models = db.glucoseCalibrationModelDao()
            .activeModelsSince(saturatingSubtract(sanitized.first().timestamp, MODEL_DECAY_WINDOW_MS))
            .map { it.toDomain() }
            .filter { model -> model.id == currentModel.id }
            .sortedBy { it.createdAt }
        return resolveSanitizedGlucoseWithModels(
            sanitized = sanitized,
            nowTs = nowTs,
            currentSessionKey = currentSessionKey,
            models = models
        )
    }

    private fun resolveSanitizedGlucoseWithModels(
        sanitized: List<GlucoseSampleEntity>,
        nowTs: Long,
        currentSessionKey: String?,
        models: List<GlucoseCalibrationModel>
    ): List<ResolvedGlucosePoint> {
        if (sanitized.isEmpty()) return emptyList()
        val runtimeSessionGateSince = saturatingSubtract(nowTs, STALE_CONTEXT_WINDOW_MS)
        return sanitized.map { sample ->
            if (sample.timestamp >= runtimeSessionGateSince && currentSessionKey == null) {
                resolveRawSample(sample)
            } else {
                resolveSample(
                    sample = sample,
                    models = models,
                    nowTs = nowTs,
                    requiredSensorSessionKey = currentSessionKey
                        ?.takeIf { sample.timestamp >= runtimeSessionGateSince }
                )
            }
        }
    }

    suspend fun resolveGlucosePoints(
        rawGlucose: List<GlucosePoint>,
        nowTs: Long = System.currentTimeMillis()
    ): List<ResolvedGlucosePoint> = lifecycleMutex.withLock {
        resolveGlucosePointsLocked(rawGlucose = rawGlucose, nowTs = nowTs)
    }

    internal suspend fun resolveAlertAiGlucosePoints(
        rawGlucose: List<GlucosePoint>,
        nowTs: Long,
        authority: CurrentGlucoseCalibrationAuthority
    ): List<ResolvedGlucosePoint> = lifecycleMutex.withLock {
        if (rawGlucose.isEmpty()) return@withLock emptyList()
        val sanitized = GlucoseSanitizer.filterPoints(rawGlucose)
            .map { point ->
                GlucoseSampleEntity(
                    timestamp = point.ts,
                    mmol = point.valueMmol,
                    source = point.source,
                    quality = point.quality.name
                )
            }
            .sortedBy(GlucoseSampleEntity::timestamp)
        if (sanitized.isEmpty() || calibrationSuppressed) {
            return@withLock sanitized.map(::resolveRawSample)
        }
        resolveSanitizedGlucoseWithModels(
            sanitized = sanitized,
            nowTs = nowTs,
            currentSessionKey = authority.context.currentSessionKey,
            models = listOfNotNull(authority.activeModel)
        )
    }

    private suspend fun resolveGlucosePointsLocked(
        rawGlucose: List<GlucosePoint>,
        nowTs: Long
    ): List<ResolvedGlucosePoint> {
        if (rawGlucose.isEmpty()) return emptyList()
        val entities = GlucoseSanitizer.filterPoints(rawGlucose).map { point ->
            GlucoseSampleEntity(
                timestamp = point.ts,
                mmol = point.valueMmol,
                source = point.source,
                quality = point.quality.name
            )
        }
        return resolveGlucoseHistoryLocked(entities, nowTs)
    }

    suspend fun resolveDomainGlucoseHistory(
        rawGlucose: List<GlucoseSampleEntity>,
        nowTs: Long = System.currentTimeMillis()
    ): List<GlucosePoint> = lifecycleMutex.withLock {
        resolveGlucoseHistoryLocked(rawGlucose, nowTs).map { it.toDomain() }
    }

    suspend fun resolveDomainGlucosePoints(
        rawGlucose: List<GlucosePoint>,
        nowTs: Long = System.currentTimeMillis()
    ): List<GlucosePoint> = lifecycleMutex.withLock {
        resolveGlucosePointsLocked(rawGlucose, nowTs).map { it.toDomain() }
    }

    suspend fun latestActiveModel(
        nowTs: Long = System.currentTimeMillis()
    ): GlucoseCalibrationModel? = lifecycleMutex.withLock {
        latestActiveModelLocked(nowTs)
    }

    private suspend fun latestActiveModelLocked(nowTs: Long): GlucoseCalibrationModel? {
        if (calibrationSuppressed) return null
        val currentSessionKey = resolveCurrentSensorSessionKey(nowTs) ?: return null
        val model = CalibrationAuthorityStateCodec.selectDurablyBoundModel(
            token = currentCalibrationAuthorityToken(),
            model = db.glucoseCalibrationModelDao().latestActive()?.toDomain()
        ) ?: return null
        return model.takeIf {
            CalibrationModelAuthority.isCausallyAvailableAt(it, nowTs) &&
                isCalibrationSessionContinuous(currentSessionKey, it.sensorSessionKey) &&
                CalibrationModelAuthority.strengthAt(nowTs, it) > 0.0
        }
    }

    fun observeLatestChecks(limit: Int): Flow<List<BloodGlucoseCheck>> =
        db.bloodGlucoseCheckDao().observeLatest(limit).map { rows -> rows.map { it.toDomain() } }

    fun observeLatestActiveModel(): Flow<GlucoseCalibrationModel?> =
        combine(
            db.glucoseCalibrationModelDao().observeLatestActive(),
            calibrationSuppressedState,
            db.telemetryDao().observeCurrentBySourceAndKey(
                source = CALIBRATION_AUTHORITY_SOURCE,
                key = CALIBRATION_AUTHORITY_TOKEN_KEY
            )
        ) { entity, suppressed, authorityRow ->
            CalibrationAuthorityStateCodec.selectDurablyBoundModel(
                token = authorityRow?.valueText,
                model = entity?.toDomain()
            ).takeUnless { suppressed }
        }

    fun observeLatestModel(): Flow<GlucoseCalibrationModel?> =
        db.glucoseCalibrationModelDao().observeLatest(limit = 1).map { rows ->
            rows.firstOrNull()?.toDomain()
        }

    private suspend fun buildFitContext(
        anchorTs: Long,
        relevantThroughTs: Long = Long.MAX_VALUE
    ): CalibrationFitContext {
        val historyStart = saturatingSubtract(anchorTs, FIT_CONTEXT_LOOKBACK_MS)
        val glucose = GlucoseSanitizer.filterEntities(
            db.glucoseDao().between(since = historyStart, through = relevantThroughTs)
        )
            .asSequence()
            .map { it.toDomain() }
            .toList()
        val therapy = TherapySanitizer.filterEntities(
            db.therapyDao().between(since = historyStart, through = relevantThroughTs)
        )
            .asSequence()
            .map { it.toDomain(gson) }
            .toList()
        val telemetryPoints = mutableListOf<CalibrationTelemetryPoint>()
        scanTelemetryByKeysPaged(
            telemetryDao = db.telemetryDao(),
            since = historyStart,
            through = relevantThroughTs,
            keys = CALIBRATION_TELEMETRY_KEYS,
            callerTag = "glucose_calibration_fit_context",
            auditLogger = null
        ) { page ->
            telemetryPoints += page.asSequence()
                .mapNotNull { sample -> CalibrationTelemetryPoint.fromEntity(sample.toEntity()) }
                .toList()
        }
        return CalibrationFitContext(
            glucose = glucose,
            therapy = therapy,
            telemetry = CalibrationTelemetryIndex(telemetryPoints)
        )
    }

    private fun assessCalibrationCheck(
        sessionKey: String?,
        bloodTs: Long,
        bloodMmol: Double,
        lagMinutes: Double,
        preparedRawGlucose: GlucoseCalibrationGuard.PreparedRawGlucose,
        telemetry: CalibrationTelemetryIndex
    ): CalibrationCheckAssessment {
        return GlucoseCalibrationGuard.assessPrepared(
            sessionKey = sessionKey,
            bloodTs = bloodTs,
            bloodMmol = bloodMmol,
            lagMinutes = lagMinutes,
            preparedRawGlucose = preparedRawGlucose,
            telemetryAt = { alignedTs ->
                val snapshot = telemetrySnapshotAt(alignedTs, telemetry)
                CalibrationTrustSnapshot(
                    sensorBlocked = snapshot.sensorBlocked,
                    sensorSuspectFalseLow = snapshot.sensorSuspectFalseLow,
                    stale = snapshot.stale,
                    available = snapshot.sensorTrustAvailable
                )
            }
        )
    }

    private fun reassessCheck(
        check: BloodGlucoseCheck,
        nowTs: Long,
        rawGlucose: List<GlucosePoint>,
        preparedRawGlucose: GlucoseCalibrationGuard.PreparedRawGlucose,
        telemetry: CalibrationTelemetryIndex,
        therapy: List<TherapyEvent>
    ): BloodGlucoseCheck {
        val sessionKey = resolveSensorSessionKey(
            targetTs = check.timestamp,
            telemetry = telemetry,
            therapy = therapy,
            glucose = rawGlucose
        )
        val lagMinutes = resolveLagMinutesAt(check.timestamp, telemetry) ?: DEFAULT_FIT_LAG_MINUTES
        val assessment = assessCalibrationCheck(
            sessionKey = sessionKey,
            bloodTs = check.timestamp,
            bloodMmol = check.mmol,
            lagMinutes = lagMinutes,
            preparedRawGlucose = preparedRawGlucose,
            telemetry = telemetry
        )
        val reassessed = check.copy(
            sensorSessionKey = sessionKey,
            lagAlignedTs = assessment.lagAlignedTs,
            matchedRawGlucose = assessment.matchedRaw?.valueMmol,
            status = assessment.status,
            reason = assessment.reason
        )
        return selectReassessedCalibrationCheck(
            previous = check,
            reassessed = reassessed,
            nowTs = nowTs
        )
    }

    private fun buildModelForChecks(
        nowTs: Long,
        sessionKey: String,
        checks: List<BloodGlucoseCheck>,
        telemetry: CalibrationTelemetryIndex
    ): GlucoseCalibrationModel? {
        if (checks.isEmpty()) return null
        val weightedChecks = checks.map { check ->
            val snapshot = telemetrySnapshotAt(
                targetTs = calibrationQualityTimestamp(check),
                telemetry = telemetry
            )
            val trendConsistency = snapshot.trendConsistency
            val quality = snapshot.sensorQualityScore
            val recencyWeight = recencyWeight(nowTs = nowTs, ts = check.timestamp)
            val weight = (recencyWeight * (0.45 + quality * 0.55) * trendConsistency)
                .coerceIn(0.1, 1.0)
            WeightedCheck(check = check, weight = weight)
        }
        val aligned = weightedChecks.mapNotNull { weighted ->
            val raw = weighted.check.matchedRawGlucose ?: return@mapNotNull null
            WeightedAlignment(
                blood = weighted.check.mmol,
                raw = raw,
                weight = weighted.weight,
                ts = weighted.check.timestamp
            )
        }
        if (aligned.isEmpty()) return null

        val rawSpan = aligned.maxOf { it.raw } - aligned.minOf { it.raw }
        val sensorAgeHours = resolveAgeHoursForSession(checks = checks, telemetry = telemetry)
        val lagMinutesAtFit = aligned
            .mapNotNull { alignment -> checks.firstOrNull { it.timestamp == alignment.ts }?.lagAlignedTs }
            .takeIf { it.isNotEmpty() }
            ?.let { alignedTs ->
                alignedTs.zip(aligned.map { it.ts }).map { (lagAlignedTs, bloodTs) ->
                    ((lagAlignedTs - bloodTs).coerceAtLeast(0L)) / 60_000.0
                }.average()
            }

        val affineCandidate = if (aligned.size >= 2 && rawSpan >= AFFINE_MIN_RAW_SPAN_MMOL) {
            fitAffine(aligned)
        } else {
            null
        }
        val useAffine = affineCandidate != null &&
            affineCandidate.gain in MIN_GAIN..MAX_GAIN &&
            affineCandidate.offset in MIN_OFFSET_MMOL..MAX_OFFSET_MMOL &&
            affineCandidate.rmse <= AFFINE_MAX_RMSE_MMOL

        val modelType = if (useAffine) GlucoseCalibrationModelType.AFFINE else GlucoseCalibrationModelType.OFFSET
        val gain = if (useAffine) affineCandidate!!.gain.coerceIn(MIN_GAIN, MAX_GAIN) else 1.0
        val offset = if (useAffine) {
            affineCandidate!!.offset.coerceIn(MIN_OFFSET_MMOL, MAX_OFFSET_MMOL)
        } else {
            weightedMedian(aligned.map { it.blood - it.raw to it.weight })
                .coerceIn(MIN_OFFSET_MMOL, MAX_OFFSET_MMOL)
        }
        val confidenceBase = when (modelType) {
            GlucoseCalibrationModelType.OFFSET -> 0.52 + min(0.28, aligned.size * 0.08)
            GlucoseCalibrationModelType.AFFINE -> 0.68 + min(0.20, aligned.size * 0.05)
        }
        val rmsePenalty = when {
            affineCandidate == null -> 0.0
            affineCandidate.rmse <= 0.4 -> 0.0
            affineCandidate.rmse >= 1.5 -> 0.28
            else -> (affineCandidate.rmse - 0.4) / (1.5 - 0.4) * 0.28
        }
        val confidence = (confidenceBase - rmsePenalty)
            .coerceIn(0.25, 0.95)

        val diagnostics = mapOf(
            "rawSpan" to rawSpan,
            "alignedChecks" to aligned.size,
            "rmse" to affineCandidate?.rmse,
            "gainUnclamped" to affineCandidate?.gain,
            "offsetUnclamped" to affineCandidate?.offset
        )

        return GlucoseCalibrationModel(
            id = "gcm-${UUID.randomUUID()}",
            sensorSessionKey = sessionKey,
            createdAt = nowTs,
            validFromTs = checks.minOf { it.timestamp },
            validToTs = nowTs + MODEL_DECAY_WINDOW_MS,
            modelType = modelType,
            gain = gain,
            offsetMmol = offset,
            confidence = confidence,
            checkCount = aligned.size,
            sensorAgeHours = sensorAgeHours,
            lagMinutesAtFit = lagMinutesAtFit,
            status = if (confidence >= ACTIVE_MIN_CONFIDENCE) {
                GlucoseCalibrationModelStatus.ACTIVE
            } else {
                GlucoseCalibrationModelStatus.SHADOW
            },
            diagnosticsJson = gson.toJson(diagnostics)
        )
    }

    private fun resolveSample(
        sample: GlucoseSampleEntity,
        models: List<GlucoseCalibrationModel>,
        nowTs: Long,
        requiredSensorSessionKey: String? = null
    ): ResolvedGlucosePoint {
        val model = models
            .asReversed()
            .firstOrNull { candidate ->
                sample.timestamp >= candidate.validFromTs &&
                    sample.timestamp <= candidate.validToTs &&
                    candidate.status == GlucoseCalibrationModelStatus.ACTIVE &&
                    (requiredSensorSessionKey == null ||
                        candidate.sensorSessionKey == requiredSensorSessionKey) &&
                    CalibrationModelAuthority.strengthAt(sample.timestamp, candidate) > 0.0
            }
        if (model == null) return resolveRawSample(sample)
        val strength = CalibrationModelAuthority.strengthAt(sample.timestamp, model)
        val appliedGain = 1.0 + (model.gain - 1.0) * strength
        val appliedOffset = model.offsetMmol * strength
        val calibrated = (sample.mmol * appliedGain + appliedOffset)
            .coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
        return ResolvedGlucosePoint(
            ts = sample.timestamp,
            rawMmol = sample.mmol,
            calibratedMmol = calibrated,
            gain = appliedGain,
            offsetMmolApplied = appliedOffset,
            calibrationApplied = abs(calibrated - sample.mmol) >= 1e-4,
            source = sample.source,
            quality = parseCalibrationDataQuality(sample.quality),
            calibrationModelId = model.id
        )
    }

    private fun resolveRawSample(sample: GlucoseSampleEntity): ResolvedGlucosePoint =
        ResolvedGlucosePoint(
            ts = sample.timestamp,
            rawMmol = sample.mmol,
            calibratedMmol = sample.mmol,
            gain = 1.0,
            offsetMmolApplied = 0.0,
            calibrationApplied = false,
            source = sample.source,
            quality = parseCalibrationDataQuality(sample.quality)
        )

    private fun recencyWeight(nowTs: Long, ts: Long): Double {
        val ageHours = ((nowTs - ts).coerceAtLeast(0L)) / 3_600_000.0
        return when {
            ageHours <= 6.0 -> 1.0
            ageHours >= 72.0 -> 0.25
            else -> 1.0 - (ageHours - 6.0) / 66.0 * 0.75
        }.coerceIn(0.25, 1.0)
    }

    private fun resolveSensorSessionKey(
        targetTs: Long,
        telemetry: CalibrationTelemetryIndex,
        therapy: List<TherapyEvent>,
        glucose: List<GlucosePoint>
    ): String? {
        val ageDerivedSession = telemetry.sensorSessionKeyAt(targetTs)
        if (ageDerivedSession != null) return ageDerivedSession
        val explicitStart = therapy
            .filter { it.ts <= targetTs }
            .filter { event ->
                when (event.type.trim().lowercase(Locale.US)) {
                    "sensor_change", "cgm_sensor_change", "sensor_start", "sensor_started" -> true
                    else -> false
                }
            }
            .maxOfOrNull { it.ts }
        if (explicitStart != null) {
            return calibrationSensorSessionKeyFromStart(explicitStart)
        }
        val inferred = inferSensorSessionBoundary(glucose.filter { it.ts <= targetTs })
        return inferred?.let(::calibrationSensorSessionKeyFromStart)
    }

    private suspend fun resolveCurrentSensorSessionKey(nowTs: Long): String? {
        return loadCalibrationSessionContextInTransaction(
            db = db,
            nowTs = nowTs,
            pointTs = nowTs
        ).currentSessionKey
    }

    private fun resolveLagMinutesAt(
        targetTs: Long,
        telemetry: CalibrationTelemetryIndex
    ): Double? {
        val snapshot = telemetrySnapshotAt(targetTs, telemetry)
        return snapshot.sensorLagMinutes?.coerceIn(0.0, 20.0)
    }

    private fun resolveAgeHoursForSession(
        checks: List<BloodGlucoseCheck>,
        telemetry: CalibrationTelemetryIndex
    ): Double? {
        val values = checks.mapNotNull { check ->
            telemetrySnapshotAt(
                targetTs = calibrationQualityTimestamp(check),
                telemetry = telemetry
            ).sensorAgeHours
        }
        return values.takeIf { it.isNotEmpty() }?.average()
    }

    private fun telemetrySnapshotAt(
        targetTs: Long,
        telemetry: CalibrationTelemetryIndex
    ): CalibrationTelemetrySnapshot = telemetry.snapshotAt(targetTs)

    private fun inferSensorSessionBoundary(glucose: List<GlucosePoint>): Long? {
        val sorted = glucose
            .filter { it.quality != DataQuality.SENSOR_ERROR }
            .sortedBy { it.ts }
        if (sorted.size < 4) return null
        return sorted.zipWithNext()
            .mapNotNull { (previous, next) ->
                val gapCandidate = next.ts - previous.ts >= SESSION_GAP_TRIGGER_MS
                if (gapCandidate) next.ts else null
            }
            .maxOrNull()
            ?: sorted.firstOrNull()?.ts
    }

    private fun fitAffine(aligned: List<WeightedAlignment>): AffineFit? {
        val weightSum = aligned.sumOf { it.weight }.takeIf { it > 0.0 } ?: return null
        val meanRaw = aligned.sumOf { it.weight * it.raw } / weightSum
        val meanBlood = aligned.sumOf { it.weight * it.blood } / weightSum
        val varRaw = aligned.sumOf { it.weight * (it.raw - meanRaw) * (it.raw - meanRaw) } / weightSum
        if (varRaw < 1e-6) return null
        val cov = aligned.sumOf { it.weight * (it.raw - meanRaw) * (it.blood - meanBlood) } / weightSum
        val gain = cov / varRaw
        val offset = meanBlood - gain * meanRaw
        val rmse = kotlin.math.sqrt(
            aligned.sumOf { alignment ->
                val predicted = gain * alignment.raw + offset
                val err = predicted - alignment.blood
                alignment.weight * err * err
            } / weightSum
        )
        return AffineFit(gain = gain, offset = offset, rmse = rmse)
    }

    private fun weightedMedian(values: List<Pair<Double, Double>>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sortedBy { it.first }
        val totalWeight = sorted.sumOf { it.second }.coerceAtLeast(1e-6)
        var cumulative = 0.0
        val midpoint = totalWeight / 2.0
        sorted.forEach { (value, weight) ->
            cumulative += max(weight, 1e-6)
            if (cumulative >= midpoint) return value
        }
        return sorted.last().first
    }

    private fun normalizeUnits(units: String): String {
        val normalized = units.trim().lowercase(Locale.US)
        return when {
            normalized.contains("mg") -> "mg/dL"
            else -> "mmol/L"
        }
    }

    private fun normalizeMmol(value: Double, units: String): Double {
        return if (units == "mg/dL") UnitConverter.mgdlToMmol(value) else value
    }

    private data class CalibrationFitContext(
        val glucose: List<GlucosePoint>,
        val therapy: List<TherapyEvent>,
        val telemetry: CalibrationTelemetryIndex
    )

    private data class CalibrationTelemetryPoint(
        val ts: Long,
        val source: String,
        val key: String,
        val value: Double?
    ) {
        companion object {
            fun fromEntity(entity: io.aaps.copilot.data.local.entity.TelemetrySampleEntity): CalibrationTelemetryPoint? {
                val numeric = entity.valueDouble
                    ?: entity.valueText?.trim()?.replace(',', '.')?.toDoubleOrNull()
                return CalibrationTelemetryPoint(
                    ts = entity.timestamp,
                    source = entity.source,
                    key = entity.key.trim(),
                    value = numeric?.takeIf { it.isFinite() }
                )
            }
        }
    }

    private data class CalibrationTelemetrySnapshot(
        val sensorLagMinutes: Double?,
        val sensorQualityScore: Double,
        val sensorBlocked: Boolean,
        val sensorSuspectFalseLow: Boolean,
        val trendConsistency: Double,
        val sensorAgeHours: Double?,
        val sensorTrustAvailable: Boolean,
        val stale: Boolean
    )

    private class CalibrationTelemetryIndex(
        points: List<CalibrationTelemetryPoint>
    ) {
        private val pointsByKey = points
            .groupBy { it.key }
            .mapValues { (_, values) -> values.sortedBy { it.ts } }
        private val agePointsBySourceAndKey = points
            .asSequence()
            .filter { it.key in SENSOR_AGE_KEYS }
            .groupBy { it.source to it.key }
            .mapValues { (_, values) -> values.sortedBy { it.ts } }
        private val snapshotCache = HashMap<Long, CalibrationTelemetrySnapshot>()

        fun snapshotAt(targetTs: Long): CalibrationTelemetrySnapshot {
            return snapshotCache.getOrPut(targetTs) {
                val qualityPoint = latestPointForKeys(targetTs, SENSOR_QUALITY_KEYS)
                val blockedPoint = latestPointForKeys(targetTs, SENSOR_BLOCKED_KEYS)
                val suspectFalseLowPoint = latestPointForKeys(targetTs, SENSOR_SUSPECT_FALSE_LOW_KEYS)
                val sensorTrustAvailable = areCalibrationTrustFieldsAvailable(
                    targetTs = targetTs,
                    staleWindowMs = STALE_CONTEXT_WINDOW_MS,
                    qualityScore = qualityPoint?.toTrustFieldValue(),
                    sensorBlocked = blockedPoint?.toTrustFieldValue(),
                    sensorSuspectFalseLow = suspectFalseLowPoint?.toTrustFieldValue()
                )
                val sensorQualityScore = qualityPoint?.value
                    ?.takeIf { it.isFinite() }
                    ?.coerceIn(0.0, 1.0)
                    ?: 1.0
                val trendConsistency = latestNumeric(targetTs, SENSOR_LAG_TREND_KEYS)
                    ?.coerceIn(0.35, 1.0)
                    ?: 1.0
                CalibrationTelemetrySnapshot(
                    sensorLagMinutes = latestNumeric(targetTs, SENSOR_LAG_KEYS),
                    sensorQualityScore = sensorQualityScore,
                    sensorBlocked = blockedPoint?.value?.takeIf { it.isFinite() }?.let { it >= 0.5 } ?: false,
                    sensorSuspectFalseLow = suspectFalseLowPoint?.value
                        ?.takeIf { it.isFinite() }
                        ?.let { it >= 0.5 }
                        ?: false,
                    trendConsistency = trendConsistency,
                    sensorAgeHours = latestNumeric(targetTs, SENSOR_AGE_KEYS),
                    sensorTrustAvailable = sensorTrustAvailable,
                    stale = !sensorTrustAvailable
                )
            }
        }

        fun sensorSessionKeyAt(targetTs: Long): String? {
            val freshSince = saturatingSubtract(targetTs, STALE_CONTEXT_WINDOW_MS)
            val samples = agePointsBySourceAndKey.values.flatMap { points ->
                latestPointsAtOrBefore(points, targetTs)
            }.filter { point -> point.ts >= freshSince }
                .map { point ->
                CalibrationSensorAgeSample(
                    ts = point.ts,
                    ageHours = point.value
                )
            }
            return resolveConsistentCalibrationSensorSessionKey(samples)
        }

        private fun latestNumeric(targetTs: Long, keys: Set<String>): Double? =
            latestPointForKeys(targetTs, keys)
                ?.value
                ?.takeIf { it.isFinite() }

        private fun latestPointForKeys(
            targetTs: Long,
            keys: Set<String>
        ): CalibrationTelemetryPoint? {
            return keys.asSequence()
                .mapNotNull { key -> latestPointAtOrBefore(pointsByKey[key].orEmpty(), targetTs) }
                .maxByOrNull { it.ts }
        }

        private fun CalibrationTelemetryPoint.toTrustFieldValue(): CalibrationTrustFieldValue =
            CalibrationTrustFieldValue(ts = ts, value = value)

        private fun latestPointAtOrBefore(
            points: List<CalibrationTelemetryPoint>,
            targetTs: Long
        ): CalibrationTelemetryPoint? = latestPointIndexAtOrBefore(points, targetTs)
            ?.let(points::get)

        private fun latestPointsAtOrBefore(
            points: List<CalibrationTelemetryPoint>,
            targetTs: Long
        ): List<CalibrationTelemetryPoint> {
            val latestIndex = latestPointIndexAtOrBefore(points, targetTs) ?: return emptyList()
            val latestTimestamp = points[latestIndex].ts
            var firstIndex = latestIndex
            while (firstIndex > 0 && points[firstIndex - 1].ts == latestTimestamp) {
                firstIndex -= 1
            }
            return points.subList(firstIndex, latestIndex + 1)
        }

        private fun latestPointIndexAtOrBefore(
            points: List<CalibrationTelemetryPoint>,
            targetTs: Long
        ): Int? {
            if (points.isEmpty()) return null
            var lo = 0
            var hi = points.lastIndex
            var best = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (points[mid].ts <= targetTs) {
                    best = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return best.takeIf { it >= 0 }
        }
    }

    private data class CalibrationRefreshMemo(
        val computedAt: Long,
        val latestCheckTs: Long?,
        val modelId: String?,
        val model: GlucoseCalibrationModel?,
        val currentSessionKey: String?,
        val inputWatermark: CalibrationMemoInputWatermark
    )

    private data class CalibrationRefreshComputation(
        val reassessedChecks: List<BloodGlucoseCheck>,
        val reassessmentAudits: List<Map<String, Any?>>,
        val decision: CalibrationStateDecision,
        val latestCheckTs: Long?
    )

    private data class PreparedCalibrationToken(
        val nowTs: Long,
        val inputScope: CalibrationInputScope,
        val inputWatermark: CalibrationMemoInputWatermark,
        val currentSessionKey: String?,
        val latestNonRetired: GlucoseCalibrationModel?,
        val inputAuthorityToken: String,
        val publishedAuthorityToken: String,
        val shouldPublishAuthorityState: Boolean,
        val computation: CalibrationRefreshComputation?,
        val plannedModel: GlucoseCalibrationModel?,
        val shouldSweepStaleModels: Boolean
    )

    private data class CalibrationTransactionCommit(
        val outcome: CalibrationTransactionOutcome,
        val inputWatermark: CalibrationMemoInputWatermark
    )

    private sealed class CalibrationStateDecision {
        data object NoModel : CalibrationStateDecision()
        data object UnresolvedLatestCheck : CalibrationStateDecision()
        data object UnresolvedCurrentSession : CalibrationStateDecision()
        data class SessionMismatch(
            val currentSessionKey: String,
            val latestCheckSessionKey: String
        ) : CalibrationStateDecision()
        data class InvalidFit(val sensorSessionKey: String) : CalibrationStateDecision()
        data class ModelFit(
            val candidate: GlucoseCalibrationModel,
            val existing: GlucoseCalibrationModel?,
            val reuseExisting: Boolean
        ) : CalibrationStateDecision()
    }

    private data class CalibrationTransactionOutcome(
        val model: GlucoseCalibrationModel?,
        val reusedExisting: Boolean = false,
        val retiredCurrentSession: Int = 0,
        val retiredOtherSessions: Int = 0,
        val retiredAllSessions: Int = 0,
        val retiredOtherNonRetiredModels: Int = 0,
        val retiredStaleModels: Int = 0
    )

    private class CalibrationContextChangedException : RuntimeException()

    private data class WeightedCheck(
        val check: BloodGlucoseCheck,
        val weight: Double
    )

    private data class WeightedAlignment(
        val blood: Double,
        val raw: Double,
        val weight: Double,
        val ts: Long
    )

    private data class ManualWidgetRefreshFailure(
        val errorCode: String,
        val errorType: String
    )

    private data class AffineFit(
        val gain: Double,
        val offset: Double,
        val rmse: Double
    )

    companion object {
        private const val MIN_GLUCOSE_MMOL = 2.2
        private const val MAX_GLUCOSE_MMOL = 22.0
        private const val DEFAULT_FIT_LAG_MINUTES = 10.0
        private const val FIT_CONTEXT_LOOKBACK_MS = 21L * 24L * 60L * 60L * 1000L
        private const val MODEL_DECAY_WINDOW_MS = 72L * 60L * 60L * 1000L
        private const val STALE_MODEL_SWEEP_INTERVAL_MS = 30L * 60L * 1000L
        private const val ACTIVE_MIN_CONFIDENCE = 0.45
        private const val AFFINE_MIN_RAW_SPAN_MMOL = 2.5
        private const val AFFINE_MAX_RMSE_MMOL = 1.4
        private const val MIN_GAIN = 0.85
        private const val MAX_GAIN = 1.15
        private const val MIN_OFFSET_MMOL = -3.0
        private const val MAX_OFFSET_MMOL = 3.0
        private const val STALE_CONTEXT_WINDOW_MS = 30L * 60L * 1000L
        private const val SESSION_GAP_TRIGGER_MS = 90L * 60L * 1000L
        private const val MAX_CONTEXT_COMPUTE_ATTEMPTS = 2
        private const val CALIBRATION_SCOPE_CHECK_LIMIT = 256
        private const val DEFAULT_MANUAL_POST_COMMIT_FINALIZATION_TIMEOUT_MS = 5_000L
        private const val DEFAULT_MANUAL_WIDGET_REFRESH_TIMEOUT_MS = 5_000L
        private const val MANUAL_WIDGET_REFRESH_TIMEOUT_ERROR_CODE =
            "MANUAL_WIDGET_REFRESH_TIMEOUT"
        private const val MANUAL_WIDGET_REFRESH_TIMEOUT_ERROR_TYPE = "Timeout"
        private const val MANUAL_WIDGET_REFRESH_CALLBACK_ERROR_CODE =
            "MANUAL_WIDGET_REFRESH_CALLBACK_FAILED"

        private val CALIBRATION_TELEMETRY_KEYS = setOf(
            "sensor_lag_effective_lag_minutes",
            "sensor_lag_minutes",
            "sensor_quality_score",
            "sensor_quality_blocked",
            "sensor_quality_suspect_false_low",
            "sensor_lag_trend_consistency",
            "sensor_age_hours",
            "sensor_lag_age_hours",
            "isf_factor_sensor_age_hours"
        )
        private val SENSOR_LAG_KEYS = setOf("sensor_lag_effective_lag_minutes", "sensor_lag_minutes")
        private val SENSOR_QUALITY_KEYS = setOf("sensor_quality_score")
        private val SENSOR_BLOCKED_KEYS = setOf("sensor_quality_blocked")
        private val SENSOR_SUSPECT_FALSE_LOW_KEYS = setOf("sensor_quality_suspect_false_low")
        private val SENSOR_LAG_TREND_KEYS = setOf("sensor_lag_trend_consistency")
        private val SENSOR_AGE_KEYS = CALIBRATION_TELEMETRY_KEYS
            .filterTo(mutableSetOf(), ::isCalibrationSensorSessionAgeKey)
    }
}

internal class PreparedCalibrationCycle internal constructor(
    val resolvedGlucose: List<ResolvedGlucosePoint>,
    val additionalResolvedGlucose: List<ResolvedGlucosePoint>,
    val model: GlucoseCalibrationModel?,
    val identity: GlucoseCalibrationCycleIdentity,
    val maintenancePrepared: Boolean,
    internal val token: Any
)

internal data class CalibrationInputScope(
    val historySinceTs: Long,
    val relevantThroughTs: Long
)

internal data class CalibrationMemoInputWatermark(
    val bloodCheckGeneration: Long,
    val glucoseGeneration: Long,
    val therapyGeneration: Long,
    val telemetryGeneration: Long,
    val modelGeneration: Long,
    val therapyBoundarySignature: String = ""
)

internal fun calibrationInputScope(
    nowTs: Long,
    recentChecks: List<BloodGlucoseCheck>
): CalibrationInputScope {
    val historySinceTs = saturatingSubtract(nowTs, CALIBRATION_HISTORY_LOOKBACK_MS)
    val scopedChecks = recentChecks
        .filter { it.timestamp >= historySinceTs }
    val relevantThroughTs = scopedChecks
        .asSequence()
        .map { check ->
            val alignedTs = check.lagAlignedTs
                ?: saturatingAdd(check.timestamp, DEFAULT_CALIBRATION_ALIGNMENT_LAG_MS)
            val pendingBeforeWindow = check.lagAlignedTs != null &&
                (check.status == BloodGlucoseCheckStatus.OUT_OF_WINDOW ||
                    check.status == BloodGlucoseCheckStatus.STALE) &&
                nowTs < saturatingSubtract(alignedTs, CALIBRATION_MATCH_MAX_FORWARD_MS)
            if (pendingBeforeWindow) {
                check.timestamp
            } else {
                saturatingAdd(alignedTs, CALIBRATION_MATCH_MAX_FORWARD_MS)
            }
        }
        .maxOrNull()
        ?.coerceAtLeast(historySinceTs)
        ?: historySinceTs
    return CalibrationInputScope(
        historySinceTs = historySinceTs,
        relevantThroughTs = relevantThroughTs
    )
}

private fun CalibrationInputWatermarkRow.toMemoInputWatermark(): CalibrationMemoInputWatermark =
    CalibrationMemoInputWatermark(
        bloodCheckGeneration = bloodCheckRowId,
        glucoseGeneration = glucoseRowId,
        therapyGeneration = therapyRowId,
        telemetryGeneration = telemetryRowId,
        modelGeneration = calibrationModelRowId,
        therapyBoundarySignature = checkNotNull(therapyBoundarySignature) {
            "calibration sensor history exceeds safe input budget"
        }
    )

internal data class CalibrationTrustFieldValue(
    val ts: Long,
    val value: Double?
)

internal data class CalibrationSensorAgeSample(
    val ts: Long,
    val ageHours: Double?
)

internal fun isCalibrationSensorSessionAgeKey(key: String): Boolean =
    key == "sensor_age_hours" || key == "isf_factor_sensor_age_hours"

internal fun resolveConsistentCalibrationSensorSessionKey(
    samples: List<CalibrationSensorAgeSample>
): String? {
    if (samples.isEmpty()) return null
    val sessionKeys = linkedSetOf<String>()
    samples.forEach { sample ->
        val ageHours = sample.ageHours ?: return@forEach
        val sessionKey = calibrationSensorSessionKeyFromAgeSample(
            sampleTs = sample.ts,
            ageHours = ageHours
        ) ?: return null
        sessionKeys += sessionKey
        if (sessionKeys.size > 1) return null
    }
    return sessionKeys.singleOrNull()
}

internal fun areCalibrationTrustFieldsAvailable(
    targetTs: Long,
    staleWindowMs: Long,
    qualityScore: CalibrationTrustFieldValue?,
    sensorBlocked: CalibrationTrustFieldValue?,
    sensorSuspectFalseLow: CalibrationTrustFieldValue?
): Boolean {
    if (targetTs < 0L || staleWindowMs < 0L) return false
    return listOf(qualityScore, sensorBlocked, sensorSuspectFalseLow).all { field ->
        field != null &&
            field.ts in 0L..targetTs &&
            field.value?.isFinite() == true &&
            targetTs - field.ts <= staleWindowMs
    }
}

internal fun shouldReuseRecentCalibrationModelStatic(
    nowTs: Long,
    latestCheckTs: Long?,
    latestModelId: String?,
    memoComputedAt: Long?,
    memoLatestCheckTs: Long?,
    memoModelId: String?,
    currentInputWatermark: CalibrationMemoInputWatermark,
    memoInputWatermark: CalibrationMemoInputWatermark?
): Boolean {
    if (memoComputedAt == null) return false
    if (memoLatestCheckTs != latestCheckTs) return false
    if (memoModelId != latestModelId) return false
    if (memoInputWatermark != currentInputWatermark) return false
    val elapsedMs = nowTs - memoComputedAt
    return elapsedMs in 0L until 10L * 60L * 1000L
}

internal fun shouldBypassCalibrationMemoForPendingAlignment(
    nowTs: Long,
    latestCheck: BloodGlucoseCheck
): Boolean {
    val lagAlignedTs = latestCheck.lagAlignedTs ?: return false
    if (
        latestCheck.status == BloodGlucoseCheckStatus.VALID &&
        latestCheck.reason == PROVISIONAL_CURRENT_RAW_REASON
    ) {
        return nowTs >= lagAlignedTs
    }
    if (latestCheck.status != BloodGlucoseCheckStatus.OUT_OF_WINDOW &&
        latestCheck.status != BloodGlucoseCheckStatus.STALE
    ) {
        return false
    }
    val bypassFromTs = saturatingSubtract(lagAlignedTs, CALIBRATION_MATCH_MAX_FORWARD_MS)
    val bypassUntilTs = saturatingAdd(lagAlignedTs, CALIBRATION_MATCH_MAX_FORWARD_MS)
    return nowTs >= bypassFromTs && nowTs < bypassUntilTs
}

internal fun areEquivalentModelsForReuse(
    existing: GlucoseCalibrationModel,
    candidate: GlucoseCalibrationModel
): Boolean {
    if (existing.sensorSessionKey != candidate.sensorSessionKey) return false
    if (existing.modelType != candidate.modelType) return false
    if (existing.checkCount != candidate.checkCount) return false
    if (existing.validFromTs != candidate.validFromTs) return false
    if (abs(existing.gain - candidate.gain) > 1e-4) return false
    if (abs(existing.offsetMmol - candidate.offsetMmol) > 1e-4) return false
    return true
}

internal fun calibrationSensorSessionKeyFromAgeSample(
    sampleTs: Long,
    ageHours: Double
): String? {
    if (sampleTs < 0L || !ageHours.isFinite() || ageHours !in 0.0..CALIBRATION_MAX_SESSION_AGE_HOURS) {
        return null
    }
    val ageMsDouble = ageHours * 3_600_000.0
    if (!ageMsDouble.isFinite() || ageMsDouble > sampleTs.toDouble()) return null
    return calibrationSensorSessionKeyFromStart(sampleTs - ageMsDouble.toLong())
}

internal fun isCalibrationSessionContinuous(
    currentSessionKey: String?,
    latestCheckSessionKey: String?
): Boolean = currentSessionKey?.takeIf { it.isNotBlank() } ==
    latestCheckSessionKey?.takeIf { it.isNotBlank() } && currentSessionKey != null

private fun calibrationSensorSessionKeyFromStart(startTs: Long): String? {
    if (startTs < 0L) return null
    val shiftedStart = saturatingAdd(startTs, CALIBRATION_SESSION_KEY_ROUNDING_MS / 2L)
    val roundedStart = (shiftedStart / CALIBRATION_SESSION_KEY_ROUNDING_MS) *
        CALIBRATION_SESSION_KEY_ROUNDING_MS
    return "sensor-$roundedStart"
}

private fun parseCalibrationDataQuality(raw: String): DataQuality = try {
    DataQuality.valueOf(raw)
} catch (_: IllegalArgumentException) {
    DataQuality.OK
}

private const val CALIBRATION_HISTORY_LOOKBACK_MS = 21L * 24L * 60L * 60L * 1000L
private const val DEFAULT_CALIBRATION_ALIGNMENT_LAG_MS = 10L * 60L * 1000L
private const val CALIBRATION_SESSION_KEY_ROUNDING_MS = 5L * 60L * 1000L
private const val CALIBRATION_MAX_SESSION_AGE_HOURS = 24.0 * 21.0

private fun saturatingAdd(value: Long, increment: Long): Long =
    if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

private fun saturatingSubtract(value: Long, decrement: Long): Long =
    if (value < Long.MIN_VALUE + decrement) Long.MIN_VALUE else value - decrement

internal const val PROVISIONAL_CURRENT_RAW_REASON = "provisional_current_raw"
internal const val ACCEPTED_CALIBRATION_SOURCE = "copilot_glucose_calibration_acceptance"
internal const val ACCEPTED_CALIBRATION_MODEL_ID_KEY = "calibration_model_id"
internal const val ACCEPTED_CALIBRATION_SESSION_KEY = "calibration_sensor_session_key"
internal const val ACCEPTED_CALIBRATION_PREPARED_AT_KEY = "calibration_prepared_at"
internal const val ACCEPTED_CALIBRATION_MODEL_FINGERPRINT_KEY = "calibration_model_fingerprint"
internal const val ACCEPTED_CALIBRATION_AUTHORITY_TOKEN_KEY = "calibration_authority_token"
internal const val ACCEPTED_CALIBRATION_NONE = "none"
internal const val CALIBRATION_AUTHORITY_SOURCE = "copilot_glucose_calibration_authority"
internal const val CALIBRATION_AUTHORITY_TOKEN_KEY = "glucose_calibration_authority_token"
private const val CALIBRATION_AUTHORITY_ROW_ID = "glucose-calibration-authority-current"
internal val ACCEPTED_CALIBRATION_KEYS = listOf(
    ACCEPTED_CALIBRATION_MODEL_ID_KEY,
    ACCEPTED_CALIBRATION_SESSION_KEY,
    ACCEPTED_CALIBRATION_PREPARED_AT_KEY,
    ACCEPTED_CALIBRATION_MODEL_FINGERPRINT_KEY,
    ACCEPTED_CALIBRATION_AUTHORITY_TOKEN_KEY
)

internal fun acceptedCalibrationModelFingerprint(model: GlucoseCalibrationModel): String {
    val canonical = buildString {
        fun appendText(value: String) {
            append(value.length).append(':').append(value).append('|')
        }
        fun appendDouble(value: Double) = appendText(java.lang.Double.doubleToRawLongBits(value).toString())
        fun appendNullableDouble(value: Double?) = appendText(
            value?.let { java.lang.Double.doubleToRawLongBits(it).toString() } ?: "null"
        )
        appendText("accepted-calibration-v1")
        appendText(model.id)
        appendText(model.sensorSessionKey)
        appendText(model.createdAt.toString())
        appendText(model.validFromTs.toString())
        appendText(model.validToTs.toString())
        appendText(model.modelType.name)
        appendDouble(model.gain)
        appendDouble(model.offsetMmol)
        appendDouble(model.confidence)
        appendText(model.checkCount.toString())
        appendNullableDouble(model.sensorAgeHours)
        appendNullableDouble(model.lagMinutesAtFit)
        appendText(model.status.name)
        appendText(model.diagnosticsJson)
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
}

data class GlucoseCalibrationResetResult(
    val resetCheckCount: Int,
    val retiredModelCount: Int
)

private data class DurableManualCalibrationOutcome(
    val model: GlucoseCalibrationModel?
)

private data class ProvenManualCalibrationDurableState(
    val activeModel: GlucoseCalibrationModel?
)

private class ManualCalibrationDurableStateAmbiguousException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

enum class ManualCalibrationDurableMutation {
    ADD,
    RESET
}

internal fun selectInitialCalibrationAssessment(
    delayed: CalibrationCheckAssessment,
    immediate: CalibrationCheckAssessment,
    enteredAt: Long
): CalibrationCheckAssessment {
    val delayedPointIsStillInFuture = delayed.lagAlignedTs?.let { it > enteredAt } == true
    if (
        delayed.status != BloodGlucoseCheckStatus.OUT_OF_WINDOW ||
        delayed.reason != "aligned_glucose_gap" ||
        !delayedPointIsStillInFuture ||
        immediate.status != BloodGlucoseCheckStatus.VALID ||
        immediate.matchedRaw == null
    ) {
        return delayed
    }
    return immediate.copy(
        lagAlignedTs = delayed.lagAlignedTs,
        effectiveLagMinutes = delayed.effectiveLagMinutes,
        reason = PROVISIONAL_CURRENT_RAW_REASON
    )
}

internal fun selectReassessedCalibrationCheck(
    previous: BloodGlucoseCheck,
    reassessed: BloodGlucoseCheck,
    nowTs: Long
): BloodGlucoseCheck {
    if (previous.reason == MANUAL_CALIBRATION_RESET_REASON) return previous
    val alignedTimeStillInFuture = previous.lagAlignedTs
        ?.let { alignedTs -> nowTs < alignedTs }
        ?: false
    val preserveUntilTs = previous.lagAlignedTs
        ?.let { saturatingAdd(it, CALIBRATION_MATCH_MAX_FORWARD_MS) }
    val alignedPointStillPending = preserveUntilTs != null && nowTs <= preserveUntilTs
    val provisionalHasNoActualAlignedEvidenceYet =
        previous.status == BloodGlucoseCheckStatus.VALID &&
            previous.reason == PROVISIONAL_CURRENT_RAW_REASON &&
            reassessed.status != BloodGlucoseCheckStatus.VALID &&
            reassessed.matchedRawGlucose == null &&
            alignedTimeStillInFuture
    return if (
        provisionalHasNoActualAlignedEvidenceYet ||
        (
            previous.status == BloodGlucoseCheckStatus.VALID &&
                previous.reason == PROVISIONAL_CURRENT_RAW_REASON &&
                reassessed.status == BloodGlucoseCheckStatus.OUT_OF_WINDOW &&
                reassessed.reason == "aligned_glucose_gap" &&
                alignedPointStillPending
            )
    ) {
        previous
    } else {
        reassessed
    }
}

internal const val MANUAL_CALIBRATION_RESET_REASON = "manual_reset"

internal fun selectChecksForCalibrationFit(
    sessionChecks: List<BloodGlucoseCheck>
): List<BloodGlucoseCheck> {
    val sorted = sessionChecks.sortedWith(
        compareBy<BloodGlucoseCheck> { it.timestamp }.thenBy { it.enteredAt }
    )
    val eligible = sorted.filter { check ->
        check.status == BloodGlucoseCheckStatus.VALID &&
            check.matchedRawGlucose?.isFinite() == true
    }
    val anchor = eligible.lastOrNull() ?: return sorted
    if (anchor.reason == PROVISIONAL_CURRENT_RAW_REASON) {
        return listOf(anchor)
    }
    val anchorRaw = anchor.matchedRawGlucose ?: return sorted
    val anchorOffset = anchor.mmol - anchorRaw
    val regimeSinceTs = saturatingSubtract(
        anchor.timestamp,
        CALIBRATION_REGIME_LOOKBACK_MS
    )
    return eligible
        .asReversed()
        .takeWhile { check ->
            val raw = check.matchedRawGlucose ?: return@takeWhile false
            check.timestamp >= regimeSinceTs &&
                abs((check.mmol - raw) - anchorOffset) <= CALIBRATION_REGIME_MAX_OFFSET_DELTA_MMOL
        }
        .take(CALIBRATION_REGIME_MAX_CHECKS)
        .asReversed()
}

private const val CALIBRATION_REGIME_LOOKBACK_MS = 12L * 60L * 60L * 1000L
private const val CALIBRATION_REGIME_MAX_OFFSET_DELTA_MMOL = 0.8
private const val CALIBRATION_REGIME_MAX_CHECKS = 6

internal fun selectLatestCalibrationSessionAnchor(
    checks: List<BloodGlucoseCheck>
): BloodGlucoseCheck? = checks
    .asSequence()
    .filter { check ->
        check.status == BloodGlucoseCheckStatus.VALID ||
            (
                check.status == BloodGlucoseCheckStatus.OUT_OF_WINDOW &&
                    check.reason in setOf("aligned_glucose_gap", PROVISIONAL_CURRENT_RAW_REASON)
                )
    }
    .maxWithOrNull(compareBy<BloodGlucoseCheck> { it.timestamp }.thenBy { it.enteredAt })

internal fun resolveCurrentCalibrationSessionKey(
    ageSamples: List<CalibrationSensorAgeSample>,
    latestCheck: BloodGlucoseCheck?,
    nowTs: Long,
    knownBoundaryAfterCheck: Boolean = false
): String? {
    if (knownBoundaryAfterCheck) return null
    if (ageSamples.isNotEmpty()) {
        return resolveConsistentCalibrationSensorSessionKey(ageSamples)
    }
    val check = latestCheck ?: return null
    val ageMs = nowTs - check.timestamp
    val checkCanAnchorSession = selectLatestCalibrationSessionAnchor(listOf(check)) != null
    return check.sensorSessionKey
        ?.trim()
        ?.takeIf {
            it.isNotEmpty() &&
                checkCanAnchorSession &&
                check.timestamp >= 0L && check.enteredAt in 0L..nowTs &&
                ageMs in 0L..CALIBRATION_SESSION_CHECK_MAX_AGE_MS
        }
}

internal fun hasKnownCalibrationSessionBoundaryAfterCheck(
    checkTs: Long,
    glucose: List<GlucosePoint>,
    therapy: List<TherapyEvent>
): Boolean {
    val explicitSensorChange = therapy.any { event ->
        event.ts > checkTs &&
            event.type.trim().lowercase(Locale.US) in setOf(
                "sensor_change",
                "cgm_sensor_change",
                "sensor_start",
                "sensor_started"
            )
    }
    if (explicitSensorChange) return true
    return glucose
        .asSequence()
        .filter { it.quality != DataQuality.SENSOR_ERROR }
        .sortedBy { it.ts }
        .zipWithNext()
        .any { (previous, next) ->
            next.ts > checkTs &&
                next.ts - previous.ts >= 90L * 60L * 1000L
        }
}
