package io.aaps.copilot.data.repository

import com.google.gson.Gson
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.domain.model.BloodGlucoseCheck
import io.aaps.copilot.domain.model.GlucoseCalibrationModel
import io.aaps.copilot.domain.model.GlucoseCalibrationModelStatus

internal object CalibrationModelAuthority {
    const val SESSION_EVIDENCE_MAX_AGE_MS = 30L * 60L * 1000L
    const val SESSION_EVIDENCE_QUERY_LIMIT = 64
    val SESSION_EVIDENCE_KEYS = listOf(
        "sensor_age_hours",
        "isf_factor_sensor_age_hours"
    )

    fun selectBoundedSessionEvidence(
        queriedTelemetry: List<TelemetrySampleEntity>,
        nowTs: Long
    ): CalibrationSessionEvidenceSelection {
        if (nowTs < 0L) return CalibrationSessionEvidenceSelection(evidenceTruncated = true)
        val sinceTs = (nowTs - SESSION_EVIDENCE_MAX_AGE_MS).coerceAtLeast(0L)
        val inspected = queriedTelemetry
            .asSequence()
            .filter { it.key in SESSION_EVIDENCE_KEYS }
            .sortedWith(
                compareByDescending<TelemetrySampleEntity> { it.timestamp }
                    .thenByDescending { it.id }
            )
            .take(SESSION_EVIDENCE_QUERY_LIMIT + 1)
            .toList()
        val sentinel = inspected.getOrNull(SESSION_EVIDENCE_QUERY_LIMIT)
        return CalibrationSessionEvidenceSelection(
            telemetry = inspected
                .take(SESSION_EVIDENCE_QUERY_LIMIT)
                .filter { it.timestamp in sinceTs..nowTs },
            evidenceTruncated = sentinel != null && sentinel.timestamp >= sinceTs
        )
    }

    fun selectApplicableModel(
        model: GlucoseCalibrationModel?,
        nowTs: Long,
        pointTs: Long?,
        telemetry: List<TelemetrySampleEntity>,
        evidenceTruncated: Boolean = false
    ): GlucoseCalibrationModel? {
        val authorityContext = resolveAuthorityContext(
            nowTs = nowTs,
            pointTs = pointTs,
            telemetry = telemetry,
            evidenceTruncated = evidenceTruncated
        )
        return selectApplicableModel(model, nowTs, pointTs, authorityContext)
    }

    fun selectApplicableModel(
        model: GlucoseCalibrationModel?,
        nowTs: Long,
        pointTs: Long?,
        authorityContext: CalibrationAuthorityContext
    ): GlucoseCalibrationModel? {
        if (!isApplicableContext(nowTs, pointTs, evidenceTruncated = false)) return null
        if (!authorityContext.contextValid) return null
        val candidate = model ?: return null
        val applicationTs = requireNotNull(pointTs)
        val currentSessionKey = requireNotNull(authorityContext.currentSessionKey)
        return candidate.takeIf {
            isCausallyAvailableAt(it, nowTs) &&
                it.status == GlucoseCalibrationModelStatus.ACTIVE &&
                it.sensorSessionKey == currentSessionKey &&
                applicationTs in it.validFromTs..it.validToTs &&
                strengthAt(applicationTs, it) > 0.0
        }
    }

    fun isCausallyAvailableAt(
        model: GlucoseCalibrationModel,
        authoritativeNowTs: Long
    ): Boolean =
        authoritativeNowTs >= 0L &&
            model.createdAt in 0L..authoritativeNowTs &&
            model.validFromTs >= 0L &&
            model.validToTs >= model.validFromTs &&
            model.createdAt <= model.validToTs

    fun resolveAuthorityContext(
        nowTs: Long,
        pointTs: Long?,
        telemetry: List<TelemetrySampleEntity>,
        evidenceTruncated: Boolean = false,
        latestCheck: BloodGlucoseCheck? = null,
        knownBoundaryAfterCheck: Boolean = false
    ): CalibrationAuthorityContext {
        if (!isApplicableContext(nowTs, pointTs, evidenceTruncated) || knownBoundaryAfterCheck) {
            return CalibrationAuthorityContext()
        }
        if (telemetry.none {
                it.key in SESSION_EVIDENCE_KEYS &&
                    it.timestamp in (nowTs - SESSION_EVIDENCE_MAX_AGE_MS)..nowTs
            }
        ) {
            val anchoredSession = resolveCurrentCalibrationSessionKey(
                ageSamples = emptyList(), latestCheck = latestCheck, nowTs = nowTs
            )
            if (anchoredSession != null) {
                return CalibrationAuthorityContext(currentSessionKey = anchoredSession, contextValid = true)
            }
            // Only genuinely absent evidence permits explicit sessionless RAW authority.
            return CalibrationAuthorityContext(rawWithoutSessionAllowed = true)
        }
        val currentSessionKey = resolveCurrentSessionKey(telemetry, nowTs)
            ?: return CalibrationAuthorityContext()
        return CalibrationAuthorityContext(
            currentSessionKey = currentSessionKey,
            contextValid = true
        )
    }

    fun isApplicableContext(
        nowTs: Long,
        pointTs: Long?,
        evidenceTruncated: Boolean
    ): Boolean = !evidenceTruncated && nowTs >= 0L && pointTs != null && pointTs in 0L..nowTs

    fun applyToGlucose(
        pointTs: Long,
        rawMmol: Double,
        model: GlucoseCalibrationModel
    ): Double {
        val strength = strengthAt(pointTs, model)
        if (
            model.status != GlucoseCalibrationModelStatus.ACTIVE ||
            pointTs !in model.validFromTs..model.validToTs ||
            strength <= 0.0
        ) return rawMmol
        val gain = 1.0 + (model.gain - 1.0) * strength
        val offset = model.offsetMmol * strength
        return (rawMmol * gain + offset).coerceIn(2.2, 22.0)
    }

    fun strengthAt(pointTs: Long, model: GlucoseCalibrationModel): Double {
        val ageMs = (pointTs - model.createdAt).coerceAtLeast(0L)
        return when {
            ageMs <= MODEL_FULL_STRENGTH_MS -> 1.0
            ageMs >= MODEL_DECAY_WINDOW_MS -> 0.0
            else -> 1.0 - (ageMs - MODEL_FULL_STRENGTH_MS).toDouble() /
                (MODEL_DECAY_WINDOW_MS - MODEL_FULL_STRENGTH_MS).toDouble()
        }.coerceIn(0.0, 1.0)
    }

    private fun resolveCurrentSessionKey(
        telemetry: List<TelemetrySampleEntity>,
        nowTs: Long
    ): String? {
        val freshRows = telemetry.filter { row ->
            row.key in SESSION_EVIDENCE_KEYS &&
                row.timestamp in (nowTs - SESSION_EVIDENCE_MAX_AGE_MS)..nowTs
        }
        val freshLatestBySourceAndKey = freshRows
            .asSequence()
            .filter { isCalibrationSensorSessionAgeKey(it.key) }
            .groupBy { it.source to it.key }
            .values
            .flatMap { rows ->
                val latestTs = rows.maxOf(TelemetrySampleEntity::timestamp)
                rows.filter { it.timestamp == latestTs }
            }
        if (freshLatestBySourceAndKey.isEmpty()) return null
        return resolveConsistentCalibrationSensorSessionKey(
            freshLatestBySourceAndKey.map { row ->
                CalibrationSensorAgeSample(
                    ts = row.timestamp,
                    ageHours = row.valueDouble
                        ?: row.valueText?.replace(",", ".")?.toDoubleOrNull()
                        ?: return null
                )
            }
        )
    }

    private const val MODEL_FULL_STRENGTH_MS = 12L * 60L * 60L * 1000L
    private const val MODEL_DECAY_WINDOW_MS = 72L * 60L * 60L * 1000L
}

internal data class CalibrationSessionEvidenceSelection(
    val telemetry: List<TelemetrySampleEntity> = emptyList(),
    val evidenceTruncated: Boolean = false
)

internal data class CalibrationAuthorityContext(
    val currentSessionKey: String? = null,
    val contextValid: Boolean = false,
    val rawWithoutSessionAllowed: Boolean = false
)

internal object CalibrationAuthorityStateCodec {
    private const val VERSION = 1
    private const val MAX_TOKEN_LENGTH = 1_024
    private const val MAX_ID_LENGTH = 256
    private const val MAX_NONCE_LENGTH = 128
    private val fingerprintPattern = Regex("^[0-9a-f]{64}$")
    private val gson = Gson()

    fun pending(nonce: String): String = encode(
        DurableCalibrationAuthorityState(
            state = DurableCalibrationAuthorityStatus.PENDING,
            nonce = requireBounded(nonce, MAX_NONCE_LENGTH, "authority nonce")
        )
    )

    fun raw(nonce: String, sessionKey: String?): String = encode(
        DurableCalibrationAuthorityState(
            state = DurableCalibrationAuthorityStatus.RAW,
            nonce = requireBounded(nonce, MAX_NONCE_LENGTH, "authority nonce"),
            sessionKey = sessionKey?.let {
                requireBounded(it, MAX_ID_LENGTH, "sensor session key")
            }
        )
    )

    fun active(nonce: String, model: GlucoseCalibrationModel): String = encode(
        DurableCalibrationAuthorityState(
            state = DurableCalibrationAuthorityStatus.ACTIVE,
            nonce = requireBounded(nonce, MAX_NONCE_LENGTH, "authority nonce"),
            sessionKey = requireBounded(model.sensorSessionKey, MAX_ID_LENGTH, "sensor session key"),
            modelId = requireBounded(model.id, MAX_ID_LENGTH, "calibration model id"),
            modelFingerprint = acceptedCalibrationModelFingerprint(model)
        )
    )

    fun decode(token: String?): DurableCalibrationAuthorityState? {
        val encoded = token?.takeIf { it.isNotBlank() && it.length <= MAX_TOKEN_LENGTH } ?: return null
        val wire = try {
            gson.fromJson(encoded, CalibrationAuthorityWire::class.java)
        } catch (_: Exception) {
            return null
        } ?: return null
        if (wire.version != VERSION) return null
        val nonce = wire.nonce?.takeIf { it.isBounded(MAX_NONCE_LENGTH) } ?: return null
        val state = when (wire.state) {
            DurableCalibrationAuthorityStatus.PENDING.name -> DurableCalibrationAuthorityStatus.PENDING
            DurableCalibrationAuthorityStatus.RAW.name -> DurableCalibrationAuthorityStatus.RAW
            DurableCalibrationAuthorityStatus.ACTIVE.name -> DurableCalibrationAuthorityStatus.ACTIVE
            else -> return null
        }
        val sessionKey = wire.sessionKey?.takeIf { it.isBounded(MAX_ID_LENGTH) }
        val modelId = wire.modelId?.takeIf { it.isBounded(MAX_ID_LENGTH) }
        val fingerprint = wire.modelFingerprint?.takeIf(fingerprintPattern::matches)
        return when (state) {
            DurableCalibrationAuthorityStatus.PENDING -> {
                if (wire.sessionKey != null || wire.modelId != null || wire.modelFingerprint != null) null
                else DurableCalibrationAuthorityState(state, nonce)
            }
            DurableCalibrationAuthorityStatus.RAW -> {
                if (
                    wire.modelId != null ||
                    wire.modelFingerprint != null ||
                    (wire.sessionKey != null && sessionKey == null)
                ) null
                else DurableCalibrationAuthorityState(state, nonce, sessionKey = sessionKey)
            }
            DurableCalibrationAuthorityStatus.ACTIVE -> {
                if (sessionKey == null || modelId == null || fingerprint == null) null
                else DurableCalibrationAuthorityState(
                    state = state,
                    nonce = nonce,
                    sessionKey = sessionKey,
                    modelId = modelId,
                    modelFingerprint = fingerprint
                )
            }
        }
    }

    fun selectDurablyBoundModel(
        token: String?,
        model: GlucoseCalibrationModel?
    ): GlucoseCalibrationModel? {
        val candidate = model ?: return null
        val authority = decode(token) ?: return null
        return candidate.takeIf {
            authority.state == DurableCalibrationAuthorityStatus.ACTIVE &&
                authority.sessionKey == candidate.sensorSessionKey &&
                authority.modelId == candidate.id &&
                authority.modelFingerprint == acceptedCalibrationModelFingerprint(candidate)
        }
    }

    fun resolve(
        token: String?,
        applicableModel: GlucoseCalibrationModel?,
        context: CalibrationAuthorityContext
    ): DurableCalibrationAuthorityResolution {
        val authority = decode(token) ?: return DurableCalibrationAuthorityResolution()
        if (
            context.rawWithoutSessionAllowed &&
            context.currentSessionKey == null &&
            authority.state == DurableCalibrationAuthorityStatus.RAW &&
            authority.sessionKey == null &&
            applicableModel == null
        ) {
            return DurableCalibrationAuthorityResolution(contextValid = true)
        }
        if (!context.contextValid) return DurableCalibrationAuthorityResolution()
        val currentSessionKey = context.currentSessionKey ?: return DurableCalibrationAuthorityResolution()
        if (authority.sessionKey != currentSessionKey) return DurableCalibrationAuthorityResolution()
        return when (authority.state) {
            DurableCalibrationAuthorityStatus.PENDING -> DurableCalibrationAuthorityResolution()
            DurableCalibrationAuthorityStatus.RAW -> DurableCalibrationAuthorityResolution(
                currentSessionKey = currentSessionKey,
                contextValid = true
            )
            DurableCalibrationAuthorityStatus.ACTIVE -> {
                val activeModel = selectDurablyBoundModel(token, applicableModel)
                    ?: return DurableCalibrationAuthorityResolution()
                DurableCalibrationAuthorityResolution(
                    activeModel = activeModel,
                    currentSessionKey = currentSessionKey,
                    contextValid = true
                )
            }
        }
    }

    fun exactlyRepresents(
        token: String?,
        model: GlucoseCalibrationModel?,
        sessionKey: String?
    ): Boolean {
        val authority = decode(token) ?: return false
        return if (model == null) {
            authority.state == DurableCalibrationAuthorityStatus.RAW &&
                authority.sessionKey == sessionKey
        } else {
            authority.sessionKey == sessionKey && selectDurablyBoundModel(token, model) == model
        }
    }

    private fun encode(state: DurableCalibrationAuthorityState): String {
        val encoded = gson.toJson(
            CalibrationAuthorityWire(
                version = VERSION,
                state = state.state.name,
                nonce = state.nonce,
                sessionKey = state.sessionKey,
                modelId = state.modelId,
                modelFingerprint = state.modelFingerprint
            )
        )
        check(encoded.length <= MAX_TOKEN_LENGTH) { "calibration authority token is too long" }
        return encoded
    }

    private fun requireBounded(value: String, maxLength: Int, label: String): String {
        require(value.isBounded(maxLength)) { "$label is invalid" }
        return value
    }

    private fun String.isBounded(maxLength: Int): Boolean = isNotBlank() && length <= maxLength

    private data class CalibrationAuthorityWire(
        val version: Int? = null,
        val state: String? = null,
        val nonce: String? = null,
        val sessionKey: String? = null,
        val modelId: String? = null,
        val modelFingerprint: String? = null
    )
}

internal enum class DurableCalibrationAuthorityStatus {
    PENDING,
    RAW,
    ACTIVE
}

internal data class DurableCalibrationAuthorityState(
    val state: DurableCalibrationAuthorityStatus,
    val nonce: String,
    val sessionKey: String? = null,
    val modelId: String? = null,
    val modelFingerprint: String? = null
)

internal data class DurableCalibrationAuthorityResolution(
    val activeModel: GlucoseCalibrationModel? = null,
    val currentSessionKey: String? = null,
    val contextValid: Boolean = false
)
