package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.SensitivityRuntimeSnapshotEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.model.GlucoseCalibrationModel
import io.aaps.copilot.domain.model.GlucoseCalibrationModelStatus
import io.aaps.copilot.domain.predict.AcceptedSensitivityTupleFreshness
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_CYCLE_ID_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY
import io.aaps.copilot.domain.predict.SENSITIVITY_ACCEPTED_SOURCE
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDecomposition
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDecompositionCodec
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastDigest
import io.aaps.copilot.domain.predict.SensitivityAcceptedForecastRow
import io.aaps.copilot.domain.predict.SensitivityMetricDecisionValidator
import io.aaps.copilot.domain.predict.SensitivityMetricKind
import io.aaps.copilot.domain.predict.SensitivityMetricDecision
import io.aaps.copilot.domain.predict.SensitivityResolvedSource
import io.aaps.copilot.domain.predict.SensitivityRuntimeSnapshot
import io.aaps.copilot.domain.predict.SensitivityRuntimeSettingsIdentity
import io.aaps.copilot.domain.predict.SensitivitySourcePreference

enum class AcceptedForecastTupleError(val uiReason: String) {
    SNAPSHOT_UNAVAILABLE("forecast_tuple_snapshot_unavailable"),
    REVISION_MISMATCH("forecast_tuple_revision_mismatch"),
    MARKER_SET_INVALID("forecast_tuple_marker_invalid"),
    CYCLE_MISMATCH("forecast_tuple_cycle_mismatch"),
    FORECAST_SET_INVALID("forecast_tuple_forecast_set_invalid"),
    FRESHNESS_INVALID("forecast_tuple_freshness_invalid"),
    DIGEST_MISMATCH("forecast_tuple_digest_mismatch"),
    CALIBRATION_MISMATCH("forecast_tuple_calibration_mismatch")
}

data class AcceptedForecastTuple(
    val sensitivity: SensitivityRuntimeSnapshot? = null,
    val forecastsByHorizon: Map<Int, ForecastEntity> = emptyMap(),
    val generationTimestamp: Long? = null,
    val forecastDigest: String? = null,
    val decomposition: SensitivityAcceptedForecastDecomposition? = null,
    val calibrationModel: GlucoseCalibrationModel? = null,
    val calibrationSessionKey: String? = null,
    val error: AcceptedForecastTupleError? = null
)

data class AcceptedCalibrationAuthority(
    val activeModel: GlucoseCalibrationModel?,
    val authorityToken: String = ACCEPTED_CALIBRATION_NONE,
    val currentSessionKey: String? = activeModel?.sensorSessionKey,
    val contextValid: Boolean = true
)

internal val ACCEPTED_SENSITIVITY_MARKER_KEYS = listOf(
    SENSITIVITY_ACCEPTED_CYCLE_ID_KEY,
    SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY,
    SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY,
    SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY,
    SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY,
    SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY
)

internal const val ACCEPTED_SENSITIVITY_MARKER_PAGE_SIZE = 32

object ForecastSnapshotResolver {
    private const val MINUTE_MS = 60_000L
    private val requiredHorizons = setOf(5, 30, 60)
    private val markerKeys = ACCEPTED_SENSITIVITY_MARKER_KEYS.toSet()

    fun resolveAcceptedTuple(
        forecasts: List<ForecastEntity>,
        telemetry: List<TelemetrySampleEntity>,
        snapshot: SensitivityRuntimeSnapshot?,
        currentSettings: SensitivityRuntimeSettingsIdentity,
        authoritativeNowTs: Long,
        calibrationAuthority: AcceptedCalibrationAuthority? = null
    ): AcceptedForecastTuple {
        snapshot ?: return rejected(AcceptedForecastTupleError.SNAPSHOT_UNAVAILABLE)
        if (
            snapshot.settingsRevision != currentSettings.revision ||
            snapshot.isf.requested != currentSettings.isfSource ||
            snapshot.cr.requested != currentSettings.crSource
        ) {
            return rejected(AcceptedForecastTupleError.REVISION_MISMATCH)
        }

        val acceptedMarkers = telemetry.filter {
            it.source == SENSITIVITY_ACCEPTED_SOURCE && it.key in markerKeys
        }
        val markerTimestamp = acceptedMarkers.asSequence()
            .filter {
                it.key == SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY &&
                    it.valueText == SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED
            }
            .map(TelemetrySampleEntity::timestamp)
            .distinct()
            .sortedDescending()
            .firstOrNull { timestamp ->
                val rows = acceptedMarkers.filter { it.timestamp == timestamp }
                val groups = rows.groupBy(TelemetrySampleEntity::key)
                groups[SENSITIVITY_ACCEPTED_CYCLE_ID_KEY]
                    ?.any { it.valueText == snapshot.forecastCycleId } == true &&
                    groups[SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY]
                        ?.any { it.numericLongOrNull() == currentSettings.revision } == true
            }
            ?: return rejected(AcceptedForecastTupleError.MARKER_SET_INVALID)
        if (markerTimestamp <= 0L || markerTimestamp > authoritativeNowTs) {
            return rejected(AcceptedForecastTupleError.FRESHNESS_INVALID)
        }
        val markerRows = acceptedMarkers.filter { it.timestamp == markerTimestamp }
        val markerGroups = markerRows.groupBy { it.key }
        if (markerGroups.keys != markerKeys || markerGroups.values.any { it.size != 1 }) {
            return rejected(AcceptedForecastTupleError.MARKER_SET_INVALID)
        }

        val cycleId = markerGroups.getValue(SENSITIVITY_ACCEPTED_CYCLE_ID_KEY).single()
            .valueText?.takeIf { it.isNotBlank() }
            ?: return rejected(AcceptedForecastTupleError.MARKER_SET_INVALID)
        val revision = markerGroups.getValue(SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY).single()
            .numericLongOrNull()
            ?: return rejected(AcceptedForecastTupleError.MARKER_SET_INVALID)
        val generationTimestamp = markerGroups.getValue(SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY).single()
            .numericLongOrNull()
            ?: return rejected(AcceptedForecastTupleError.MARKER_SET_INVALID)
        if (!AcceptedSensitivityTupleFreshness.isFresh(generationTimestamp, authoritativeNowTs)) {
            return rejected(AcceptedForecastTupleError.FRESHNESS_INVALID)
        }
        val decomposition = markerGroups.getValue(SENSITIVITY_ACCEPTED_FORECAST_DECOMPOSITION_KEY).single()
            .valueText
            .let(SensitivityAcceptedForecastDecompositionCodec::decode)
            ?: return rejected(AcceptedForecastTupleError.MARKER_SET_INVALID)
        val digest = markerGroups.getValue(SENSITIVITY_ACCEPTED_FORECAST_DIGEST_KEY).single()
            .valueText?.trim()?.takeIf { it.isNotEmpty() }
            ?: return rejected(AcceptedForecastTupleError.MARKER_SET_INVALID)
        val publicationState = markerGroups.getValue(SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY).single()
            .valueText
        if (publicationState != SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED) {
            return rejected(AcceptedForecastTupleError.MARKER_SET_INVALID)
        }

        if (
            cycleId != snapshot.forecastCycleId ||
            revision != snapshot.settingsRevision ||
            revision != currentSettings.revision
        ) {
            return rejected(AcceptedForecastTupleError.CYCLE_MISMATCH)
        }
        if (
            // Forecast horizons are anchored to the last sample, not the later ISF/CR calculation.
            snapshot.timestamp > markerTimestamp ||
            generationTimestamp > markerTimestamp ||
            !AcceptedSensitivityTupleFreshness.isFresh(snapshot.timestamp, markerTimestamp)
        ) {
            return rejected(AcceptedForecastTupleError.FRESHNESS_INVALID)
        }
        val acceptedCalibration = calibrationAuthority?.let { authority ->
            resolveAcceptedCalibration(
                telemetry = telemetry,
                markerTimestamp = markerTimestamp,
                authority = authority
            ) ?: return rejected(AcceptedForecastTupleError.CALIBRATION_MISMATCH)
        }

        val generationRows = forecasts.filter { row ->
            runCatching {
                Math.subtractExact(
                    row.timestamp,
                    Math.multiplyExact(row.horizonMinutes.toLong(), MINUTE_MS)
                )
            }.getOrNull() == generationTimestamp
        }
        val horizonGroups = generationRows.groupBy { it.horizonMinutes }
        if (horizonGroups.keys != requiredHorizons || horizonGroups.values.any { it.size != 1 }) {
            return rejected(AcceptedForecastTupleError.FORECAST_SET_INVALID)
        }
        val exactRows = requiredHorizons.sorted().map { horizon -> horizonGroups.getValue(horizon).single() }
        val digestMatches = SensitivityAcceptedForecastDigest.matches(
            cycleId = cycleId,
            settingsRevision = revision,
            forecasts = exactRows.map(ForecastEntity::toAcceptedDigestRow),
            decomposition = decomposition,
            expectedDigest = digest
        )
        if (!digestMatches) return rejected(AcceptedForecastTupleError.DIGEST_MISMATCH)

        return AcceptedForecastTuple(
            sensitivity = snapshot,
            forecastsByHorizon = exactRows.associateBy { it.horizonMinutes },
            generationTimestamp = generationTimestamp,
            forecastDigest = digest,
            decomposition = decomposition,
            calibrationModel = acceptedCalibration?.model,
            calibrationSessionKey = acceptedCalibration?.sessionKey
        )
    }

    private fun resolveAcceptedCalibration(
        telemetry: List<TelemetrySampleEntity>,
        markerTimestamp: Long,
        authority: AcceptedCalibrationAuthority
    ): AcceptedCalibrationResolution? {
        if (!authority.contextValid) return null
        val rows = telemetry.filter { row ->
            row.source == ACCEPTED_CALIBRATION_SOURCE &&
                row.timestamp == markerTimestamp &&
                row.key in ACCEPTED_CALIBRATION_KEYS
        }
        val groups = rows.groupBy(TelemetrySampleEntity::key)
        if (groups.keys != ACCEPTED_CALIBRATION_KEYS.toSet() || groups.values.any { it.size != 1 }) {
            return null
        }
        fun value(key: String) = groups.getValue(key).single().valueText
        val preparedAt = value(ACCEPTED_CALIBRATION_PREPARED_AT_KEY)?.toLongOrNull()
            ?.takeIf { it > 0L && it <= markerTimestamp }
            ?: return null
        val modelId = value(ACCEPTED_CALIBRATION_MODEL_ID_KEY) ?: return null
        val sessionKey = value(ACCEPTED_CALIBRATION_SESSION_KEY) ?: return null
        val fingerprint = value(ACCEPTED_CALIBRATION_MODEL_FINGERPRINT_KEY) ?: return null
        val authorityToken = value(ACCEPTED_CALIBRATION_AUTHORITY_TOKEN_KEY) ?: return null
        if (authorityToken != authority.authorityToken) return null
        val currentSessionKey = authority.currentSessionKey?.takeIf(String::isNotBlank)
        if (currentSessionKey == null) {
            val rawAuthority = CalibrationAuthorityStateCodec.decode(authorityToken) ?: return null
            if (
                rawAuthority.state != DurableCalibrationAuthorityStatus.RAW ||
                rawAuthority.sessionKey != null ||
                sessionKey != ACCEPTED_CALIBRATION_NONE ||
                modelId != ACCEPTED_CALIBRATION_NONE ||
                fingerprint != ACCEPTED_CALIBRATION_NONE ||
                authority.activeModel != null
            ) return null
            return AcceptedCalibrationResolution(model = null, sessionKey = null)
        }
        if (sessionKey != currentSessionKey) return null
        val current = authority.activeModel
        if (modelId == ACCEPTED_CALIBRATION_NONE) {
            if (fingerprint != ACCEPTED_CALIBRATION_NONE || current != null) return null
            return AcceptedCalibrationResolution(model = null, sessionKey = sessionKey)
        }
        if (
            current == null ||
            current.status != GlucoseCalibrationModelStatus.ACTIVE ||
            current.id != modelId ||
            current.sensorSessionKey != sessionKey ||
            acceptedCalibrationModelFingerprint(current) != fingerprint
        ) {
            return null
        }
        return AcceptedCalibrationResolution(model = current, sessionKey = sessionKey)
    }

    private fun rejected(error: AcceptedForecastTupleError) = AcceptedForecastTuple(error = error)

    private data class AcceptedCalibrationResolution(
        val model: GlucoseCalibrationModel?,
        val sessionKey: String?
    )
}

internal data class AcceptedSensitivityRoomTuple(
    val snapshot: SensitivityRuntimeSnapshot,
    val snapshotEntity: SensitivityRuntimeSnapshotEntity,
    val forecasts: List<ForecastEntity>,
    val accepted: AcceptedForecastTuple,
    val calibrationModel: GlucoseCalibrationModel?,
    val acceptedAtTs: Long
)

internal data class CurrentGlucoseCalibrationAuthority(
    val causalGlucose: CausalGlucoseSelection,
    val activeModel: GlucoseCalibrationModel?,
    val context: CalibrationAuthorityContext,
    val authorityToken: String?
)

internal suspend fun loadCurrentGlucoseCalibrationAuthorityInTransaction(
    db: CopilotDatabase,
    atTs: Long,
    failOnEvidenceOverflow: Boolean = false,
    readBudget: AlertAiDaoReadBudget? = null
): CurrentGlucoseCalibrationAuthority {
    val causalRows = readBudget?.readFixedLookup(
        source = AlertAiDatasetSourceName.CALIBRATION_CAUSAL_GLUCOSE,
        maximumRows = GlucoseSanitizer.CURRENT_CAUSAL_QUERY_LIMIT
    ) { boundedLimit ->
        db.glucoseDao().latestValidDistinctAtOrBefore(atTs = atTs, limit = boundedLimit)
    } ?: db.glucoseDao().latestValidDistinctAtOrBefore(
        atTs = atTs,
        limit = GlucoseSanitizer.CURRENT_CAUSAL_QUERY_LIMIT
    )
    val causalGlucose = GlucoseSanitizer.selectCausalEntities(
        samples = causalRows,
        atTs = atTs
    )
    val baseContext = loadCalibrationSessionContextInTransaction(
        db = db,
        nowTs = atTs,
        pointTs = causalGlucose.authorityPointTs,
        failOnEvidenceOverflow = failOnEvidenceOverflow,
        readBudget = readBudget
    )
    val activeModelEntity = if (readBudget != null) {
        readBudget.readOptional(AlertAiDatasetSourceName.CALIBRATION_ACTIVE_MODEL) {
            db.glucoseCalibrationModelDao().latestActive()
        }
    } else {
        db.glucoseCalibrationModelDao().latestActive()
    }
    val applicableModel = CalibrationModelAuthority.selectApplicableModel(
        model = activeModelEntity?.toDomain(),
        nowTs = atTs,
        pointTs = causalGlucose.authorityPointTs,
        authorityContext = baseContext
    )
    val authorityTokenRow = if (readBudget != null) {
        readBudget.readOptional(AlertAiDatasetSourceName.CALIBRATION_AUTHORITY_TOKEN) {
            db.telemetryDao().currentBySourceAndKey(
                source = CALIBRATION_AUTHORITY_SOURCE,
                key = CALIBRATION_AUTHORITY_TOKEN_KEY
            )
        }
    } else {
        db.telemetryDao().currentBySourceAndKey(
            source = CALIBRATION_AUTHORITY_SOURCE,
            key = CALIBRATION_AUTHORITY_TOKEN_KEY
        )
    }
    val authorityToken = authorityTokenRow?.valueText?.takeIf(String::isNotBlank)
    val durableAuthority = CalibrationAuthorityStateCodec.resolve(
        token = authorityToken,
        applicableModel = applicableModel,
        context = baseContext
    )
    return CurrentGlucoseCalibrationAuthority(
        causalGlucose = causalGlucose,
        activeModel = durableAuthority.activeModel,
        context = CalibrationAuthorityContext(
            currentSessionKey = durableAuthority.currentSessionKey,
            contextValid = durableAuthority.contextValid
        ),
        authorityToken = authorityToken
    )
}

internal class AcceptedSensitivityTupleRoomLoader(
    private val db: CopilotDatabase,
    private val afterMarkerLookup: suspend () -> Unit = {},
    private val onMarkerPageLoaded: suspend (List<Long>) -> Unit = {}
) {
    internal suspend fun loadBoundedInTransaction(
        currentSettings: SensitivityRuntimeSettingsIdentity,
        atTs: Long,
        currentCalibrationAuthority: CurrentGlucoseCalibrationAuthority,
        markerLimit: Int,
        forecastLimit: Int,
        readBudget: AlertAiDaoReadBudget
    ): AcceptedSensitivityRoomTuple? {
        val markerTimestamps = readBudget.read(
            AlertAiDatasetSourceName.SENSITIVITY_MARKERS,
            markerLimit
        ) { boundedLimit ->
            db.telemetryDao().committedMarkerTimestampsPageAtOrBefore(
                source = SENSITIVITY_ACCEPTED_SOURCE,
                publicationStateKey = SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY,
                committedState = SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED,
                atTs = atTs,
                beforeTimestamp = null,
                limit = boundedLimit
            )
        }
        markerTimestamps.forEach { markerTimestamp ->
            loadMarkerBoundedInTransaction(
                currentSettings = currentSettings,
                markerTimestamp = markerTimestamp,
                atTs = atTs,
                currentCalibrationAuthority = currentCalibrationAuthority,
                forecastLimit = forecastLimit,
                readBudget = readBudget
            )?.let { return it }
        }
        return null
    }

    suspend fun load(currentSettings: SensitivityRuntimeSettingsIdentity, atTs: Long): AcceptedSensitivityRoomTuple? =
        db.withTransaction {
            loadPagedInTransaction(currentSettings, atTs)
        }

    suspend fun loadLatestCommitted(
        currentSettings: SensitivityRuntimeSettingsIdentity,
        atTs: Long
    ): AcceptedSensitivityRoomTuple? = db.withTransaction {
        val timestamps = db.telemetryDao().committedMarkerTimestampsPageAtOrBefore(
            source = SENSITIVITY_ACCEPTED_SOURCE,
            publicationStateKey = SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY,
            committedState = SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED,
            atTs = atTs,
            beforeTimestamp = null,
            limit = 1
        )
        onMarkerPageLoaded(timestamps)
        val timestamp = timestamps.singleOrNull() ?: return@withTransaction null
        afterMarkerLookup()
        loadMarkerInTransaction(currentSettings, timestamp, atTs)
    }

    suspend fun loadExact(
        currentSettings: SensitivityRuntimeSettingsIdentity,
        acceptedAtTs: Long,
        atTs: Long
    ): AcceptedSensitivityRoomTuple? = db.withTransaction {
        val markerTimestamp = db.telemetryDao().committedMarkerTimestampExact(
            source = SENSITIVITY_ACCEPTED_SOURCE,
            publicationStateKey = SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY,
            committedState = SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED,
            markerTs = acceptedAtTs
        ) ?: return@withTransaction null
        onMarkerPageLoaded(listOf(markerTimestamp))
        afterMarkerLookup()
        loadMarkerInTransaction(currentSettings, markerTimestamp, atTs)
    }

    private suspend fun loadPagedInTransaction(
        currentSettings: SensitivityRuntimeSettingsIdentity,
        atTs: Long
    ): AcceptedSensitivityRoomTuple? {
        var beforeTimestamp: Long? = null
        var firstPage = true
        while (true) {
            val markerTimestamps = db.telemetryDao().committedMarkerTimestampsPageAtOrBefore(
                source = SENSITIVITY_ACCEPTED_SOURCE,
                publicationStateKey = SENSITIVITY_ACCEPTED_PUBLICATION_STATE_KEY,
                committedState = SENSITIVITY_ACCEPTED_PUBLICATION_COMMITTED,
                atTs = atTs,
                beforeTimestamp = beforeTimestamp,
                limit = ACCEPTED_SENSITIVITY_MARKER_PAGE_SIZE
            )
            onMarkerPageLoaded(markerTimestamps)
            if (firstPage) {
                afterMarkerLookup()
                firstPage = false
            }
            markerTimestamps.forEach { markerTimestamp ->
                loadMarkerInTransaction(currentSettings, markerTimestamp, atTs)?.let { return it }
            }
            if (markerTimestamps.size < ACCEPTED_SENSITIVITY_MARKER_PAGE_SIZE) return null
            beforeTimestamp = markerTimestamps.last()
        }
    }

    private suspend fun loadMarkerInTransaction(
        currentSettings: SensitivityRuntimeSettingsIdentity,
        markerTimestamp: Long,
        atTs: Long
    ): AcceptedSensitivityRoomTuple? {
        val markerRows = db.telemetryDao().atTimestampBySourceAndKeys(
            source = SENSITIVITY_ACCEPTED_SOURCE,
            timestamp = markerTimestamp,
            keys = ACCEPTED_SENSITIVITY_MARKER_KEYS
        )
        val groups = markerRows.groupBy(TelemetrySampleEntity::key)
        val revision = groups[SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY]
            ?.singleOrNull()?.numericLongOrNull()
            ?: return null
        if (revision != currentSettings.revision) return null
        val cycleId = groups[SENSITIVITY_ACCEPTED_CYCLE_ID_KEY]
            ?.singleOrNull()?.valueText?.takeIf(String::isNotBlank)
            ?: return null
        val snapshotEntity = db.sensitivityRuntimeSnapshotDao().byCycleId(cycleId)
            ?: return null
        val snapshot = snapshotEntity.toSensitivityRuntimeSnapshotOrNull()
            ?: return null
        if (
            snapshot.isf.requested != currentSettings.isfSource ||
            snapshot.cr.requested != currentSettings.crSource
        ) return null
        val generationTimestamp = groups[SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY]
            ?.singleOrNull()?.numericLongOrNull()
            ?: return null
        val forecasts = db.forecastDao().atGenerationTimestamp(generationTimestamp)
        val currentCalibrationAuthority =
            loadCurrentGlucoseCalibrationAuthorityInTransaction(db, atTs)
        val calibrationAuthorityToken = currentCalibrationAuthority.authorityToken
            ?: ACCEPTED_CALIBRATION_NONE
        val calibrationRows = db.telemetryDao().atTimestampBySourceAndKeys(
            source = ACCEPTED_CALIBRATION_SOURCE,
            timestamp = markerTimestamp,
            keys = ACCEPTED_CALIBRATION_KEYS
        )
        val accepted = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = forecasts,
            telemetry = markerRows + calibrationRows,
            snapshot = snapshot,
            currentSettings = currentSettings,
            authoritativeNowTs = atTs,
            calibrationAuthority = AcceptedCalibrationAuthority(
                activeModel = currentCalibrationAuthority.activeModel,
                authorityToken = calibrationAuthorityToken,
                currentSessionKey = currentCalibrationAuthority.context.currentSessionKey,
                contextValid = currentCalibrationAuthority.context.contextValid
            )
        )
        if (accepted.error != null) return null
        return AcceptedSensitivityRoomTuple(
            snapshot = snapshot,
            snapshotEntity = snapshotEntity,
            forecasts = forecasts,
            accepted = accepted,
            calibrationModel = accepted.calibrationModel,
            acceptedAtTs = markerTimestamp
        )
    }

    private suspend fun loadMarkerBoundedInTransaction(
        currentSettings: SensitivityRuntimeSettingsIdentity,
        markerTimestamp: Long,
        atTs: Long,
        currentCalibrationAuthority: CurrentGlucoseCalibrationAuthority,
        forecastLimit: Int,
        readBudget: AlertAiDaoReadBudget
    ): AcceptedSensitivityRoomTuple? {
        val markerLimit = ACCEPTED_SENSITIVITY_MARKER_KEYS.size
        val markerRows = readBudget.read(
            AlertAiDatasetSourceName.SENSITIVITY_MARKER_DETAIL,
            markerLimit
        ) { boundedLimit ->
            db.telemetryDao().atTimestampBySourceAndKeysForAlertAi(
                source = SENSITIVITY_ACCEPTED_SOURCE,
                timestamp = markerTimestamp,
                keys = ACCEPTED_SENSITIVITY_MARKER_KEYS,
                limit = boundedLimit
            )
        }
        val groups = markerRows.groupBy(TelemetrySampleEntity::key)
        val revision = groups[SENSITIVITY_ACCEPTED_SETTINGS_REVISION_KEY]
            ?.singleOrNull()?.numericLongOrNull()
            ?: return null
        if (revision != currentSettings.revision) return null
        val cycleId = groups[SENSITIVITY_ACCEPTED_CYCLE_ID_KEY]
            ?.singleOrNull()?.valueText?.takeIf(String::isNotBlank)
            ?: return null
        val snapshotEntity = readBudget.readOptional(
            AlertAiDatasetSourceName.SENSITIVITY_RUNTIME_SNAPSHOT
        ) {
            db.sensitivityRuntimeSnapshotDao().byCycleId(cycleId)
        }
            ?: return null
        val snapshot = snapshotEntity.toSensitivityRuntimeSnapshotOrNull()
            ?: return null
        if (
            snapshot.isf.requested != currentSettings.isfSource ||
            snapshot.cr.requested != currentSettings.crSource
        ) return null
        val generationTimestamp = groups[SENSITIVITY_ACCEPTED_FORECAST_TIMESTAMP_KEY]
            ?.singleOrNull()?.numericLongOrNull()
            ?: return null
        val forecasts = readBudget.read(
            AlertAiDatasetSourceName.SENSITIVITY_FORECASTS,
            forecastLimit
        ) { boundedLimit ->
            db.forecastDao().atGenerationTimestampForAlertAi(
                generationTimestamp = generationTimestamp,
                limit = boundedLimit
            )
        }
        val calibrationLimit = ACCEPTED_CALIBRATION_KEYS.size
        val calibrationRows = readBudget.read(
            AlertAiDatasetSourceName.SENSITIVITY_CALIBRATION_MARKERS,
            calibrationLimit
        ) { boundedLimit ->
            db.telemetryDao().atTimestampBySourceAndKeysForAlertAi(
                source = ACCEPTED_CALIBRATION_SOURCE,
                timestamp = markerTimestamp,
                keys = ACCEPTED_CALIBRATION_KEYS,
                limit = boundedLimit
            )
        }
        val calibrationAuthorityToken = currentCalibrationAuthority.authorityToken
            ?: ACCEPTED_CALIBRATION_NONE
        val accepted = ForecastSnapshotResolver.resolveAcceptedTuple(
            forecasts = forecasts,
            telemetry = markerRows + calibrationRows,
            snapshot = snapshot,
            currentSettings = currentSettings,
            authoritativeNowTs = atTs,
            calibrationAuthority = AcceptedCalibrationAuthority(
                activeModel = currentCalibrationAuthority.activeModel,
                authorityToken = calibrationAuthorityToken,
                currentSessionKey = currentCalibrationAuthority.context.currentSessionKey,
                contextValid = currentCalibrationAuthority.context.contextValid
            )
        )
        if (accepted.error != null) return null
        return AcceptedSensitivityRoomTuple(
            snapshot = snapshot,
            snapshotEntity = snapshotEntity,
            forecasts = forecasts,
            accepted = accepted,
            calibrationModel = accepted.calibrationModel,
            acceptedAtTs = markerTimestamp
        )
    }
}

private fun limitPlusOne(limit: Int): Int = try {
    Math.addExact(limit, 1)
} catch (_: ArithmeticException) {
    throw AlertAiContextException.LimitExceeded()
}

internal fun SensitivityRuntimeSnapshotEntity.toSensitivityRuntimeSnapshotOrNull(): SensitivityRuntimeSnapshot? {
    if (
        !SensitivityAcceptedForecastDigest.isValidCycleId(cycleId) ||
        settingsRevision < 0L ||
        generatedAt <= 0L
    ) return null
    val isfRequested = runCatching { SensitivitySourcePreference.valueOf(isfRequestedSource) }.getOrNull()
        ?: return null
    val isfResolved = runCatching { SensitivityResolvedSource.valueOf(isfResolvedSource) }.getOrNull()
        ?: return null
    val crRequested = runCatching { SensitivitySourcePreference.valueOf(crRequestedSource) }.getOrNull()
        ?: return null
    val crResolved = runCatching { SensitivityResolvedSource.valueOf(crResolvedSource) }.getOrNull()
        ?: return null
    val isf = SensitivityMetricDecision(
            requested = isfRequested,
            resolved = isfResolved,
            rawAaps = isfRawAaps,
            rawEvidence = isfRawEvidence,
            rawCopilot = isfRawCopilot,
            blended = isfBlended,
            effective = isfEffective,
            confidence = isfConfidence,
            fallbackReason = isfFallbackReason
        )
    val cr = SensitivityMetricDecision(
            requested = crRequested,
            resolved = crResolved,
            rawAaps = crRawAaps,
            rawEvidence = crRawEvidence,
            rawCopilot = crRawCopilot,
            blended = crBlended,
            effective = crEffective,
            confidence = crConfidence,
            fallbackReason = crFallbackReason
        )
    if (
        !SensitivityMetricDecisionValidator.isValid(SensitivityMetricKind.ISF, isf) ||
        !SensitivityMetricDecisionValidator.isValid(SensitivityMetricKind.CR, cr)
    ) return null
    return SensitivityRuntimeSnapshot(
        settingsRevision = settingsRevision,
        forecastCycleId = cycleId,
        timestamp = generatedAt,
        isf = isf,
        cr = cr
    )
}

internal fun SensitivityRuntimeSnapshot.toEntity() = SensitivityRuntimeSnapshotEntity(
    cycleId = forecastCycleId,
    settingsRevision = settingsRevision,
    generatedAt = timestamp,
    isfRequestedSource = isf.requested.name,
    isfResolvedSource = isf.resolved.name,
    isfRawAaps = isf.rawAaps,
    isfRawEvidence = isf.rawEvidence,
    isfRawCopilot = isf.rawCopilot,
    isfBlended = isf.blended,
    isfEffective = isf.effective,
    isfConfidence = isf.confidence,
    isfFallbackReason = isf.fallbackReason,
    crRequestedSource = cr.requested.name,
    crResolvedSource = cr.resolved.name,
    crRawAaps = cr.rawAaps,
    crRawEvidence = cr.rawEvidence,
    crRawCopilot = cr.rawCopilot,
    crBlended = cr.blended,
    crEffective = cr.effective,
    crConfidence = cr.confidence,
    crFallbackReason = cr.fallbackReason
)

private fun ForecastEntity.toAcceptedDigestRow() = SensitivityAcceptedForecastRow(
    horizonMinutes = horizonMinutes,
    targetTimestamp = timestamp,
    valueMmol = valueMmol,
    ciLow = ciLow,
    ciHigh = ciHigh,
    modelVersion = modelVersion
)

private fun TelemetrySampleEntity.numericLongOrNull(): Long? {
    val value = valueDouble ?: valueText?.replace(",", ".")?.toDoubleOrNull()
    val candidate = value?.takeIf(Double::isFinite)?.toLong() ?: return null
    return candidate.takeIf { it.toDouble() == value }
}
