package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.CopilotDatabase

internal const val CALIBRATION_SESSION_CHECK_MAX_AGE_MS = 72L * 60L * 60L * 1000L
internal const val CALIBRATION_SESSION_GAP_MS = 90L * 60L * 1000L
internal const val CALIBRATION_SESSION_CHECK_LIMIT = 256
// Covers the entire 72h anchor window plus a gap predecessor at one-minute CGM cadence.
internal const val CALIBRATION_SESSION_GLUCOSE_LIMIT = 5_000

/** Caller owns the Room transaction when session identity is used to accept persisted authority. */
internal suspend fun loadCalibrationSessionContextInTransaction(
    db: CopilotDatabase,
    nowTs: Long,
    pointTs: Long?,
    failOnEvidenceOverflow: Boolean = false,
    readBudget: AlertAiDaoReadBudget? = null
): CalibrationAuthorityContext {
    suspend fun <T> readEvidence(
        source: AlertAiDatasetSourceName,
        limit: Int,
        query: suspend (Int) -> List<T>
    ): List<T> = readBudget?.read(source, limit, query) ?: query(limit + 1)

    fun overflow(size: Int, limit: Int): Boolean {
        if (size <= limit) return false
        if (failOnEvidenceOverflow) throw AlertAiContextException.LimitExceeded()
        return true
    }

    val queriedTelemetry = readEvidence(
        AlertAiDatasetSourceName.CALIBRATION_EVIDENCE,
        CalibrationModelAuthority.SESSION_EVIDENCE_QUERY_LIMIT
    ) { limit ->
        db.telemetryDao().latestByKeysInWindow(
            keys = CalibrationModelAuthority.SESSION_EVIDENCE_KEYS,
            sinceTs = (nowTs - CalibrationModelAuthority.SESSION_EVIDENCE_MAX_AGE_MS).coerceAtLeast(0L),
            atTs = nowTs,
            limit = limit
        )
    }
    if (overflow(queriedTelemetry.size, CalibrationModelAuthority.SESSION_EVIDENCE_QUERY_LIMIT)) {
        return CalibrationAuthorityContext()
    }
    val evidence = CalibrationModelAuthority.selectBoundedSessionEvidence(queriedTelemetry, nowTs)
    val telemetryContext = CalibrationModelAuthority.resolveAuthorityContext(
        nowTs, pointTs, evidence.telemetry, evidence.evidenceTruncated
    )
    if (!telemetryContext.contextValid && !telemetryContext.rawWithoutSessionAllowed) return telemetryContext

    val checks = readEvidence(
        AlertAiDatasetSourceName.CALIBRATION_SESSION_CHECKS, CALIBRATION_SESSION_CHECK_LIMIT
    ) { limit ->
        db.bloodGlucoseCheckDao().recentForCalibrationSession(
            since = (nowTs - CALIBRATION_SESSION_CHECK_MAX_AGE_MS).coerceAtLeast(0L),
            through = nowTs,
            limit = limit
        )
    }
    if (overflow(checks.size, CALIBRATION_SESSION_CHECK_LIMIT)) return CalibrationAuthorityContext()
    val anchor = selectLatestCalibrationSessionAnchor(checks.map { it.toDomain() })
        ?: return telemetryContext

    val sensorBoundary = if (readBudget != null) {
        readBudget.readOptional(AlertAiDatasetSourceName.CALIBRATION_SESSION_BOUNDARY) {
            db.therapyDao().sensorBoundaryAfterForCalibrationSession(anchor.timestamp, nowTs)
        }
    } else {
        db.therapyDao().sensorBoundaryAfterForCalibrationSession(anchor.timestamp, nowTs)
    }
    if (sensorBoundary != null) return CalibrationAuthorityContext()

    // The bounded query includes one valid predecessor so a gap crossing the window is visible.
    val glucoseTimestamps = readEvidence(
        AlertAiDatasetSourceName.CALIBRATION_SESSION_GLUCOSE, CALIBRATION_SESSION_GLUCOSE_LIMIT
    ) { limit ->
        db.glucoseDao().validTimestampsForCalibrationSession(
            since = (anchor.timestamp - CALIBRATION_SESSION_GAP_MS).coerceAtLeast(0L),
            through = nowTs,
            limit = limit
        )
    }
    if (overflow(glucoseTimestamps.size, CALIBRATION_SESSION_GLUCOSE_LIMIT)) return CalibrationAuthorityContext()
    val gapAfterAnchor = glucoseTimestamps.zipWithNext().any { (previous, next) ->
        next > anchor.timestamp && next - previous >= CALIBRATION_SESSION_GAP_MS
    }
    // A history gap vetoes the old anchor, not explicit RAW with no session evidence.
    if (gapAfterAnchor && telemetryContext.rawWithoutSessionAllowed) return telemetryContext
    return CalibrationModelAuthority.resolveAuthorityContext(
        nowTs = nowTs,
        pointTs = pointTs,
        telemetry = evidence.telemetry,
        evidenceTruncated = evidence.evidenceTruncated,
        latestCheck = anchor,
        knownBoundaryAfterCheck = gapAfterAnchor
    )
}
