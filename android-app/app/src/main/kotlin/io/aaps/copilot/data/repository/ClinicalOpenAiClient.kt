package io.aaps.copilot.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.aaps.copilot.config.ClinicalAiModelIdPolicy
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.domain.target.TargetDecisionOutcome
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import okio.BufferedSink
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

private val CLINICAL_JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private fun codePointCount(value: String): Int = value.codePointCount(0, value.length)

enum class ClinicalSummaryStatus {
    STABLE,
    HIGH_VARIABILITY,
    LOW_EXPOSURE,
    HIGH_EXPOSURE,
    MIXED,
    INSUFFICIENT_DATA
}

enum class ClinicalDataQualityFlag {
    COMPLETE,
    PARTIAL_COVERAGE,
    MISSING_INTERVALS,
    SENSOR_GAPS,
    THERAPY_GAPS,
    TARGET_GAPS,
    FORECAST_GAPS,
    TELEMETRY_GAPS,
    INSUFFICIENT_DATA
}

enum class ClinicalPatternTopic {
    GLUCOSE_STABILITY,
    GLUCOSE_VARIABILITY,
    LOW_EXPOSURE,
    HIGH_EXPOSURE,
    MEAL_ASSOCIATION,
    OVERNIGHT_PATTERN,
    TARGET_ALIGNMENT,
    SENSOR_RELIABILITY,
    INFUSION_SET_SIGNAL,
    DATA_COVERAGE
}

enum class ClinicalPatternDirection {
    STABLE,
    INCREASING,
    DECREASING,
    INTERMITTENT,
    MIXED,
    NOT_APPLICABLE
}

enum class ClinicalTimeBand {
    ALL_DAY,
    OVERNIGHT,
    MORNING,
    AFTERNOON,
    EVENING
}

enum class ClinicalEvidenceMetric {
    MEAN_GLUCOSE,
    MEDIAN_GLUCOSE,
    MEAN_TARGET_MMOL,
    COEFFICIENT_OF_VARIATION_PCT,
    TIME_BELOW_RANGE_PCT,
    TIME_IN_RANGE_PCT,
    TIME_ABOVE_RANGE_PCT,
    COVERAGE_PCT,
    MAX_GAP_MINUTES,
    DURATION_MINUTES,
    TOTAL_INSULIN_UNITS_RECORDED,
    TOTAL_CARBS_GRAMS_RECORDED,
    SAMPLE_COUNT
}

private enum class ClinicalEvidenceJsonType(val schemaName: String) {
    NUMBER("number"),
    INTEGER("integer")
}

private data class ClinicalEvidenceRule(
    val type: ClinicalEvidenceJsonType,
    val minimum: Double,
    val maximum: Double
)

private fun evidenceRule(metric: ClinicalEvidenceMetric): ClinicalEvidenceRule = when (metric) {
    ClinicalEvidenceMetric.MEAN_GLUCOSE,
    ClinicalEvidenceMetric.MEDIAN_GLUCOSE,
    ClinicalEvidenceMetric.MEAN_TARGET_MMOL -> ClinicalEvidenceRule(
        ClinicalEvidenceJsonType.NUMBER,
        ClinicalOpenAiClient.MIN_CLINICAL_MMOL,
        ClinicalOpenAiClient.MAX_CLINICAL_MMOL
    )

    ClinicalEvidenceMetric.COEFFICIENT_OF_VARIATION_PCT -> ClinicalEvidenceRule(
        ClinicalEvidenceJsonType.NUMBER,
        0.0,
        ClinicalOpenAiClient.MAX_CV_PCT
    )

    ClinicalEvidenceMetric.TIME_BELOW_RANGE_PCT,
    ClinicalEvidenceMetric.TIME_IN_RANGE_PCT,
    ClinicalEvidenceMetric.TIME_ABOVE_RANGE_PCT,
    ClinicalEvidenceMetric.COVERAGE_PCT -> ClinicalEvidenceRule(
        ClinicalEvidenceJsonType.NUMBER,
        0.0,
        100.0
    )

    ClinicalEvidenceMetric.MAX_GAP_MINUTES,
    ClinicalEvidenceMetric.DURATION_MINUTES -> ClinicalEvidenceRule(
        ClinicalEvidenceJsonType.INTEGER,
        0.0,
        ClinicalOpenAiClient.MAX_30D_MINUTES.toDouble()
    )

    ClinicalEvidenceMetric.TOTAL_INSULIN_UNITS_RECORDED -> ClinicalEvidenceRule(
        ClinicalEvidenceJsonType.NUMBER,
        0.0,
        ClinicalOpenAiClient.MAX_RECORDED_INSULIN_UNITS
    )

    ClinicalEvidenceMetric.TOTAL_CARBS_GRAMS_RECORDED -> ClinicalEvidenceRule(
        ClinicalEvidenceJsonType.NUMBER,
        0.0,
        ClinicalOpenAiClient.MAX_RECORDED_CARBS_GRAMS
    )

    ClinicalEvidenceMetric.SAMPLE_COUNT -> ClinicalEvidenceRule(
        ClinicalEvidenceJsonType.INTEGER,
        0.0,
        ClinicalOpenAiClient.MAX_CANONICAL_30D_SAMPLE_COUNT.toDouble()
    )
}

internal object ClinicalEvidenceValuePolicy {
    fun isValid(metric: ClinicalEvidenceMetric, value: Double): Boolean {
        val rule = evidenceRule(metric)
        return value.isFinite() &&
            value in rule.minimum..rule.maximum &&
            (rule.type != ClinicalEvidenceJsonType.INTEGER || value % 1.0 == 0.0)
    }
}

private fun hasExclusiveEnumContradiction(values: List<Enum<*>>): Boolean =
    values.size > 1 && values.any { value ->
        value.name == "COMPLETE" ||
            value.name == "NONE" ||
            value.name.startsWith("NONE_") ||
            value.name.startsWith("NO_")
    }

data class ClinicalFinding(
    val topic: ClinicalPatternTopic,
    val period: ClinicalEvidencePeriod,
    val direction: ClinicalPatternDirection,
    val confidence: ClinicalFindingConfidence,
    val timeBand: ClinicalTimeBand,
    val evidenceMetric: ClinicalEvidenceMetric,
    val evidenceValue: Double
)

enum class ClinicalFindingConfidence {
    LOW,
    MEDIUM,
    HIGH
}

enum class ClinicalCareTeamDiscussionTopic {
    SENSOR_RELIABILITY,
    INFUSION_SET_REVIEW,
    MEAL_TIMING_REVIEW,
    ISF_CR_REVIEW,
    TARGET_PATTERN_REVIEW,
    DATA_QUALITY_REVIEW,
    LOW_RISK_REVIEW,
    OTHER_CLINICAL_REVIEW
}

enum class ClinicalAdvisoryPriority {
    LOW,
    MEDIUM,
    HIGH
}

enum class ClinicalEvidencePeriod {
    LAST_24_HOURS,
    LAST_7_DAYS,
    LAST_30_DAYS,
    COMPARATIVE_7D_30D
}

enum class ClinicalSafetyObservation {
    RECURRENT_LOW_PATTERN,
    PROLONGED_LOW_PATTERN,
    HIGH_EXPOSURE_PATTERN,
    HIGH_VARIABILITY_PATTERN,
    SENSOR_RELIABILITY_CONCERN,
    INFUSION_SET_REVIEW_SIGNAL,
    INSUFFICIENT_DATA,
    NONE_IDENTIFIED
}

enum class ClinicalCareTeamQuestion {
    SENSOR_RELIABILITY_CONTEXT,
    INFUSION_SET_CONTEXT,
    MEAL_TIMING_CONTEXT,
    ISF_CR_CONTEXT,
    TARGET_PATTERN_CONTEXT,
    LOW_PATTERN_CONTEXT,
    HIGH_PATTERN_CONTEXT,
    DATA_COMPLETENESS_CONTEXT
}

data class ClinicalRecommendation(
    val careTeamDiscussionTopic: ClinicalCareTeamDiscussionTopic,
    val priority: ClinicalAdvisoryPriority,
    val evidenceFindingIndices: List<Int>,
    val period: ClinicalEvidencePeriod
)

data class ClinicalAdvisoryReport(
    val summary7dStatus: ClinicalSummaryStatus,
    val summary30dStatus: ClinicalSummaryStatus,
    val dataQuality: List<ClinicalDataQualityFlag>,
    val patterns: List<ClinicalFinding>,
    val safetyObservations: List<ClinicalSafetyObservation>,
    val recommendations: List<ClinicalRecommendation>,
    val careTeamQuestions: List<ClinicalCareTeamQuestion>
)

data class ClinicalSourceRowCounts(
    val glucose: Int = 0,
    val insulin: Int = 0,
    val carbs: Int = 0,
    val targets: Int = 0
) {
    init {
        require(glucose >= 0)
        require(insulin >= 0)
        require(carbs >= 0)
        require(targets >= 0)
    }
}

data class ClinicalOpenAiMetadata(
    val model: String,
    val requestedModel: String,
    val systemFingerprint: String?,
    val schemaName: String,
    val schemaVersion: Int,
    val datasetSchemaVersion: Int,
    val requestHash: String,
    val chunkCount: Int,
    val usedSynthesis: Boolean,
    val reductionLevels: Int = 0,
    val coverageLedgerHash: String? = null,
    val maxRequestBytes: Long = 0L,
    val maxResponseBytes: Long = 0L,
    val totalRequestBytes: Long = 0L,
    val totalResponseBytes: Long = 0L,
    val durationMs: Long = 0L,
    val sourceRows: ClinicalSourceRowCounts = ClinicalSourceRowCounts(),
    val providerId: ClinicalAiProviderId = ClinicalAiProviderId.OPENAI,
    val requestedProviderId: ClinicalAiProviderId = ClinicalAiProviderId.OPENAI
) {
    init {
        require(chunkCount >= 0)
        require(reductionLevels >= 0)
        require(maxRequestBytes >= 0L)
        require(maxResponseBytes >= 0L)
        require(totalRequestBytes >= maxRequestBytes)
        require(totalResponseBytes >= maxResponseBytes)
        require(durationMs >= 0L)
    }
}

data class ClinicalOpenAiResult(
    val report: ClinicalAdvisoryReport,
    val metadata: ClinicalOpenAiMetadata
)

data class ClinicalChunkReport(
    val dataQuality: List<ClinicalDataQualityFlag>,
    val findings: List<ClinicalFinding>
)

data class ClinicalChunkMetadata(
    val index: Int,
    val count: Int,
    val day: String,
    val windowFromTs: Long,
    val windowThroughTs: Long,
    val hash: String
)

class ClinicalDatasetChunk internal constructor(
    val metadata: ClinicalChunkMetadata,
    internal val dataset: ClinicalReportDataset,
    val canonicalJson: String,
    val requestBytes: Int,
    private val requestInputFactory: () -> String
) {
    internal val requestInput: String
        get() = requestInputFactory()
}

data class ClinicalUploadPlan(
    val chunks: List<ClinicalDatasetChunk>,
    val requiresSynthesis: Boolean
)

data class ClinicalOpenAiTimeouts(
    val connectMillis: Long = 10_000,
    val writeMillis: Long = 20_000,
    val readMillis: Long = 60_000,
    val callMillis: Long = 75_000
) {
    init {
        require(connectMillis > 0)
        require(writeMillis > 0)
        require(readMillis > 0)
        require(callMillis > 0)
    }
}

enum class ClinicalOpenAiFailureKind {
    UNAUTHORIZED,
    RATE_LIMITED,
    SERVER,
    HTTP,
    TIMEOUT,
    NETWORK,
    REFUSAL,
    INCOMPLETE,
    INVALID_RESPONSE,
    OVERSIZED_RESPONSE,
    REQUEST_TOO_LARGE,
    DATASET_TOO_LARGE,
    CREDENTIAL,
    INPUT
}

sealed class ClinicalOpenAiException(
    message: String,
    val kind: ClinicalOpenAiFailureKind
) : Exception(message) {
    class Unauthorized : ClinicalOpenAiException(
        "Clinical analysis authorization failed",
        ClinicalOpenAiFailureKind.UNAUTHORIZED
    )

    class RateLimited : ClinicalOpenAiException(
        "Clinical analysis rate limit reached",
        ClinicalOpenAiFailureKind.RATE_LIMITED
    )

    class ServerFailure : ClinicalOpenAiException(
        "Clinical analysis service failed",
        ClinicalOpenAiFailureKind.SERVER
    )

    class HttpFailure : ClinicalOpenAiException(
        "Clinical analysis request failed",
        ClinicalOpenAiFailureKind.HTTP
    )

    class Timeout : ClinicalOpenAiException(
        "Clinical analysis timed out",
        ClinicalOpenAiFailureKind.TIMEOUT
    )

    class NetworkFailure : ClinicalOpenAiException(
        "Clinical analysis network request failed",
        ClinicalOpenAiFailureKind.NETWORK
    )

    class Refusal : ClinicalOpenAiException(
        "Clinical analysis was refused",
        ClinicalOpenAiFailureKind.REFUSAL
    )

    class Incomplete : ClinicalOpenAiException(
        "Clinical analysis was incomplete",
        ClinicalOpenAiFailureKind.INCOMPLETE
    )

    class InvalidResponse : ClinicalOpenAiException(
        "Clinical analysis returned an invalid response",
        ClinicalOpenAiFailureKind.INVALID_RESPONSE
    )

    class OversizedResponse : ClinicalOpenAiException(
        "Clinical analysis response exceeded the size limit",
        ClinicalOpenAiFailureKind.OVERSIZED_RESPONSE
    )

    class RequestTooLarge : ClinicalOpenAiException(
        "Clinical analysis request exceeded the size limit",
        ClinicalOpenAiFailureKind.REQUEST_TOO_LARGE
    )

    class DatasetTooLarge : ClinicalOpenAiException(
        "Clinical analysis dataset exceeded the local size limit",
        ClinicalOpenAiFailureKind.DATASET_TOO_LARGE
    )

    class CredentialUnavailable : ClinicalOpenAiException(
        "Clinical analysis credential is unavailable",
        ClinicalOpenAiFailureKind.CREDENTIAL
    )

    class InvalidInput : ClinicalOpenAiException(
        "Clinical analysis input is invalid",
        ClinicalOpenAiFailureKind.INPUT
    )

    class ChunkFailed(
        val chunkIndex: Int,
        val chunkCount: Int,
        val chunkFailure: ClinicalOpenAiFailureKind
    ) : ClinicalOpenAiException(
        "Clinical analysis chunk failed",
        chunkFailure
    )
}

enum class ClinicalOpenAiProgressStage {
    PREPARING,
    ANALYZING_CHUNK,
    REDUCING,
    SYNTHESIZING,
    VALIDATING,
    COMPLETED
}

data class ClinicalOpenAiProgress(
    val stage: ClinicalOpenAiProgressStage,
    val index: Int,
    val count: Int,
    val level: Int = 0
)

fun interface ClinicalOpenAiProgressCallback {
    fun onProgress(progress: ClinicalOpenAiProgress)
}

data class ClinicalOpenAiExecutionSnapshot(
    val maxRequestBytes: Long,
    val maxResponseBytes: Long,
    val totalRequestBytes: Long,
    val totalResponseBytes: Long,
    val durationMs: Long,
    val sourceRows: ClinicalSourceRowCounts
) {
    init {
        require(maxRequestBytes >= 0L)
        require(maxResponseBytes >= 0L)
        require(totalRequestBytes >= maxRequestBytes)
        require(totalResponseBytes >= maxResponseBytes)
        require(durationMs >= 0L)
    }
}

fun interface ClinicalOpenAiExecutionObserver {
    fun onExecutionSnapshot(snapshot: ClinicalOpenAiExecutionSnapshot)
}

private data class ParsedStructuredResponse<T>(
    val value: T,
    val model: String,
    val systemFingerprint: String?
)

internal data class ClinicalStructuredEnvelope(
    val output: String,
    val model: String,
    val systemFingerprint: String?
)

internal interface ClinicalAiStructuredTransport {
    val providerId: ClinicalAiProviderId
    val modelId: String

    fun requestBody(input: String, kind: ClinicalResponseKind): ByteArray

    fun connectionRequestBody(): ByteArray

    fun request(credential: String, body: ByteArray): Request

    fun parseEnvelope(raw: String): ClinicalStructuredEnvelope
}

private data class ClinicalTransportResponse(
    val json: String?,
    val bytes: Long,
    val statusCode: Int,
    val oversized: Boolean
)

private class ClinicalPartitionRemoteRequestBudget(
    private val maximumRequests: Int,
    requiredLeafRequests: Int
) {
    private var claimed = 0

    init {
        if (requiredLeafRequests > maximumRequests) {
            throw ClinicalOpenAiException.RequestTooLarge()
        }
    }

    fun claim() {
        if (claimed >= maximumRequests) {
            throw ClinicalOpenAiException.RequestTooLarge()
        }
        claimed++
    }
}

private class ClinicalExecutionStats(
    private val startedNanos: Long,
    private val sourceRows: ClinicalSourceRowCounts,
    private val monotonicClockNanos: () -> Long,
    private val observer: ClinicalOpenAiExecutionObserver?
) {
    private var maxRequestBytes = 0L
    private var maxResponseBytes = 0L
    private var totalRequestBytes = 0L
    private var totalResponseBytes = 0L

    fun recordRequest(bytes: Int) {
        if (bytes < 0) throw ClinicalOpenAiException.InvalidResponse()
        val value = bytes.toLong()
        maxRequestBytes = maxOf(maxRequestBytes, value)
        totalRequestBytes = addExact(totalRequestBytes, value)
        publish()
    }

    fun recordResponse(bytes: Long) {
        if (bytes < 0L) throw ClinicalOpenAiException.InvalidResponse()
        maxResponseBytes = maxOf(maxResponseBytes, bytes)
        totalResponseBytes = addExact(totalResponseBytes, bytes)
        publish()
    }

    fun snapshot(finishedNanos: Long): ClinicalOpenAiExecutionSnapshot {
        val elapsedNanos = try {
            Math.subtractExact(finishedNanos, startedNanos)
        } catch (_: ArithmeticException) {
            throw ClinicalOpenAiException.InvalidResponse()
        }
        if (elapsedNanos < 0L) throw ClinicalOpenAiException.InvalidResponse()
        return ClinicalOpenAiExecutionSnapshot(
            maxRequestBytes = maxRequestBytes,
            maxResponseBytes = maxResponseBytes,
            totalRequestBytes = totalRequestBytes,
            totalResponseBytes = totalResponseBytes,
            durationMs = minOf(
                elapsedNanos / 1_000_000L,
                MAX_EXECUTION_DURATION_MS
            ),
            sourceRows = sourceRows
        )
    }

    fun snapshot(): ClinicalOpenAiExecutionSnapshot = snapshot(monotonicClockNanos())

    fun publish() {
        val target = observer ?: return
        try {
            target.onExecutionSnapshot(snapshot())
        } catch (_: Throwable) {
            // Execution telemetry is observational and cannot alter analysis.
        }
    }

    private fun addExact(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        throw ClinicalOpenAiException.InvalidResponse()
    }

    private companion object {
        const val MAX_EXECUTION_DURATION_MS = 24L * 60L * 60L * 1_000L
    }
}

internal enum class ClinicalResponseKind {
    FINAL_REPORT,
    CHUNK_REPORT,
    ALERT_CAUSE_V1
}

internal class OneShotJsonRequestBody(
    private val bytes: ByteArray
) : RequestBody() {
    override fun contentType() = CLINICAL_JSON_MEDIA_TYPE

    override fun contentLength(): Long = bytes.size.toLong()

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
    }
}

open class ClinicalOpenAiClient private constructor(
    private val transport: ClinicalAiStructuredTransport,
    private val requestByteBudget: Int,
    private val maxResponseBytes: Int,
    timeouts: ClinicalOpenAiTimeouts,
    private val monotonicClockNanos: () -> Long,
    private val serializationProbe: ClinicalDatasetSerializationProbe?,
    private val partitionLeafLimit: Int,
    private val partitionRequestLimit: Int
) : ClinicalAiGateway {
    final override val providerId: ClinicalAiProviderId = transport.providerId
    final override val modelId: String = transport.modelId

    private val finalReportWireTemplate by lazy {
        captureRequestWireTemplate(ClinicalResponseKind.FINAL_REPORT)
    }
    private val chunkReportWireTemplate by lazy {
        captureRequestWireTemplate(ClinicalResponseKind.CHUNK_REPORT)
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(timeouts.connectMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(timeouts.writeMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeouts.readMillis, TimeUnit.MILLISECONDS)
        .callTimeout(timeouts.callMillis, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    constructor(
        requestByteBudget: Int = DEFAULT_REQUEST_BYTE_BUDGET,
        maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
        timeouts: ClinicalOpenAiTimeouts = ClinicalOpenAiTimeouts()
    ) : this(
        transport = OpenAiClinicalStructuredTransport(
            endpoint = OFFICIAL_ENDPOINT.toHttpUrl(),
            modelId = DEFAULT_MODEL
        ),
        requestByteBudget = validateRequestBudget(requestByteBudget),
        maxResponseBytes = validateResponseBudget(maxResponseBytes),
        timeouts = timeouts,
        monotonicClockNanos = System::nanoTime,
        serializationProbe = null,
        partitionLeafLimit = ClinicalReportPartitionPlanner.MAX_PARTITION_LEAVES,
        partitionRequestLimit = MAX_PARTITION_REMOTE_REQUESTS
    )

    constructor(
        modelId: String,
        requestByteBudget: Int = DEFAULT_REQUEST_BYTE_BUDGET,
        maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
        timeouts: ClinicalOpenAiTimeouts = ClinicalOpenAiTimeouts()
    ) : this(
        transport = OpenAiClinicalStructuredTransport(
            endpoint = OFFICIAL_ENDPOINT.toHttpUrl(),
            modelId = validateModel(modelId)
        ),
        requestByteBudget = validateRequestBudget(requestByteBudget),
        maxResponseBytes = validateResponseBudget(maxResponseBytes),
        timeouts = timeouts,
        monotonicClockNanos = System::nanoTime,
        serializationProbe = null,
        partitionLeafLimit = ClinicalReportPartitionPlanner.MAX_PARTITION_LEAVES,
        partitionRequestLimit = MAX_PARTITION_REMOTE_REQUESTS
    )

    override fun capabilities(): ClinicalAiCapabilities = ClinicalAiCapabilities(
        maxRequestBytes = requestByteBudget,
        maxResponseBytes = maxResponseBytes,
        maxOutputTokens = MAX_OUTPUT_TOKENS,
        supportsStrictStructuredOutput = true
    )

    override suspend fun testConnection(credential: String): ClinicalAiConnectionTest {
        val startedNanos = monotonicClockNanos()
        if (credential.isBlank()) {
            return connectionResult(
                ClinicalAiConnectionStatus.CREDENTIAL_UNAVAILABLE,
                startedNanos
            )
        }
        return try {
            val requestBytes = transport.connectionRequestBody()
            if (requestBytes.size > MAX_CONNECTION_REQUEST_BYTES) {
                return connectionResult(ClinicalAiConnectionStatus.INVALID_RESPONSE, startedNanos)
            }
            val request = createRequest(credential, requestBytes)
            val response = execute(request, MAX_CONNECTION_RESPONSE_BYTES)
            val status = when {
                response.statusCode == 401 || response.statusCode == 403 ->
                    ClinicalAiConnectionStatus.UNAUTHORIZED
                response.statusCode == 429 -> ClinicalAiConnectionStatus.RATE_LIMITED
                response.statusCode !in 200..299 ->
                    ClinicalAiConnectionStatus.SERVICE_UNAVAILABLE
                response.oversized -> ClinicalAiConnectionStatus.INVALID_RESPONSE
                else -> {
                    parseStructuredResponse(
                        response.json ?: throw ClinicalOpenAiException.InvalidResponse()
                    ) { output ->
                        val value = try {
                            JsonParser.parseString(output).asJsonObject
                        } catch (_: Exception) {
                            throw ClinicalOpenAiException.InvalidResponse()
                        }
                        if (
                            value.keySet() != setOf("ok") ||
                            !value.get("ok").isJsonPrimitive ||
                            !value.getAsJsonPrimitive("ok").isBoolean ||
                            !value.get("ok").asBoolean
                        ) {
                            throw ClinicalOpenAiException.InvalidResponse()
                        }
                    }
                    ClinicalAiConnectionStatus.SUCCESS
                }
            }
            connectionResult(status, startedNanos)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (_: ClinicalAiGatewayException.IdentityMismatch) {
            connectionResult(ClinicalAiConnectionStatus.IDENTITY_MISMATCH, startedNanos)
        } catch (failure: ClinicalOpenAiException) {
            connectionResult(connectionStatus(failure.kind), startedNanos)
        } catch (_: Exception) {
            connectionResult(ClinicalAiConnectionStatus.NETWORK_UNAVAILABLE, startedNanos)
        }
    }

    private fun connectionResult(
        status: ClinicalAiConnectionStatus,
        startedNanos: Long
    ): ClinicalAiConnectionTest {
        val elapsedNanos = monotonicClockNanos() - startedNanos
        val latencyMs = elapsedNanos
            .takeIf { it >= 0L }
            ?.div(1_000_000L)
            ?.takeIf { it <= 120_000L }
        return ClinicalAiConnectionTest(
            providerId = providerId,
            modelId = modelId,
            status = status,
            latencyMs = latencyMs
        )
    }

    private fun connectionStatus(
        failureKind: ClinicalOpenAiFailureKind
    ): ClinicalAiConnectionStatus = when (failureKind) {
        ClinicalOpenAiFailureKind.UNAUTHORIZED -> ClinicalAiConnectionStatus.UNAUTHORIZED
        ClinicalOpenAiFailureKind.RATE_LIMITED -> ClinicalAiConnectionStatus.RATE_LIMITED
        ClinicalOpenAiFailureKind.SERVER,
        ClinicalOpenAiFailureKind.HTTP -> ClinicalAiConnectionStatus.SERVICE_UNAVAILABLE
        ClinicalOpenAiFailureKind.TIMEOUT,
        ClinicalOpenAiFailureKind.NETWORK -> ClinicalAiConnectionStatus.NETWORK_UNAVAILABLE
        ClinicalOpenAiFailureKind.CREDENTIAL -> ClinicalAiConnectionStatus.CREDENTIAL_UNAVAILABLE
        ClinicalOpenAiFailureKind.REFUSAL,
        ClinicalOpenAiFailureKind.INCOMPLETE,
        ClinicalOpenAiFailureKind.INVALID_RESPONSE,
        ClinicalOpenAiFailureKind.OVERSIZED_RESPONSE,
        ClinicalOpenAiFailureKind.REQUEST_TOO_LARGE,
        ClinicalOpenAiFailureKind.DATASET_TOO_LARGE,
        ClinicalOpenAiFailureKind.INPUT -> ClinicalAiConnectionStatus.INVALID_RESPONSE
    }

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    open override suspend fun analyze(
        payload: ClinicalReportPayload,
        credentialProvider: suspend () -> String,
        progress: ClinicalOpenAiProgressCallback
    ): ClinicalOpenAiResult {
        val startedNanos = monotonicClockNanos()
        val canonicalDatasetJson = validatePayload(payload, serializationProbe)
        val remotePayload = payload.copy(
            dataset = remoteDataset(payload.dataset),
            compactJson = canonicalDatasetJson
        )
        val execution = ClinicalExecutionStats(
            startedNanos = startedNanos,
            sourceRows = sourceRowCounts(payload.dataset),
            monotonicClockNanos = monotonicClockNanos,
            observer = progress as? ClinicalOpenAiExecutionObserver
        )
        return try {
            notifyProgress(
                progress,
                ClinicalOpenAiProgress(ClinicalOpenAiProgressStage.PREPARING, 0, 1)
            )
            singleRequestBodyOrNull(remotePayload)?.let { fullRequestBody ->
                notifyProgress(
                    progress,
                    ClinicalOpenAiProgress(
                        ClinicalOpenAiProgressStage.SYNTHESIZING,
                        0,
                        1
                    )
                )
                val response = requestFinalReport(
                    fullRequestBody,
                    credentialProvider,
                    execution = execution,
                    beforeParse = {
                        notifyProgress(
                            progress,
                            ClinicalOpenAiProgress(
                                ClinicalOpenAiProgressStage.VALIDATING,
                                0,
                                1
                            )
                        )
                    }
                )
                notifyProgress(
                    progress,
                    ClinicalOpenAiProgress(ClinicalOpenAiProgressStage.COMPLETED, 1, 1)
                )
                return result(
                    payload = payload,
                    response = response,
                    chunkCount = 1,
                    usedSynthesis = false,
                    execution = execution.snapshot()
                )
            }

            val partitionPlan = buildPartitionPlan(
                remotePayload.dataset,
                remotePayload.compactJson
            )
            val partitionRequests = ClinicalPartitionRemoteRequestBudget(
                maximumRequests = partitionRequestLimit,
                requiredLeafRequests = partitionPlan.leaves.size
            )
            var digests = ArrayList<ClinicalChunkDigest>(partitionPlan.leaves.size)
            partitionPlan.leaves.forEachIndexed { index, leaf ->
                currentCoroutineContext().ensureActive()
                notifyProgress(
                    progress,
                    ClinicalOpenAiProgress(
                        ClinicalOpenAiProgressStage.ANALYZING_CHUNK,
                        index + 1,
                        partitionPlan.leaves.size
                    )
                )
                partitionRequests.claim()
                val report = try {
                    val requestBody = partitionLeafInput(leaf).requestBodyBytes(
                        template = chunkReportWireTemplate,
                        exactBytes = leaf.descriptor.requestBytes,
                        probe = serializationProbe
                    )
                    requestChunkReport(
                        requestBody,
                        credentialProvider,
                        execution
                    ).value
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: ClinicalOpenAiException) {
                    throw ClinicalOpenAiException.ChunkFailed(
                        chunkIndex = index + 1,
                        chunkCount = partitionPlan.leaves.size,
                        chunkFailure = failure.kind
                    )
                }
                digests += createDigest(
                    id = leaf.descriptor.id,
                    fromTs = leaf.descriptor.fromTs,
                    throughTsExclusive = leaf.descriptor.throughTsExclusive,
                    sourceHashes = listOf(leaf.descriptor.canonicalHash),
                    report = report
                )
            }

            val reductionPlanner = ClinicalReportReductionPlanner(
                requestBudgetBytes = requestByteBudget,
                wireTemplate = reductionWireTemplate()
            )
            var reductionLevels = 0
            while (digests.size > 1) {
                currentCoroutineContext().ensureActive()
                val level = try {
                    reductionPlanner.nextLevel(digests)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: ClinicalReductionException) {
                    throw sanitizedReductionFailure(failure)
                }
                val next = ArrayList<ClinicalChunkDigest>(level.groups.size)
                level.groups.forEach { group ->
                    currentCoroutineContext().ensureActive()
                    notifyProgress(
                        progress,
                        ClinicalOpenAiProgress(
                            stage = ClinicalOpenAiProgressStage.REDUCING,
                            index = group.index,
                            count = group.count,
                            level = reductionLevels + 1
                        )
                    )
                    partitionRequests.claim()
                    val report = try {
                        requestChunkReport(
                            group.requestBodyBytes.toByteArray(),
                            credentialProvider,
                            execution
                        ).value
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: ClinicalOpenAiException) {
                        throw ClinicalOpenAiException.ChunkFailed(
                            chunkIndex = group.index,
                            chunkCount = group.count,
                            chunkFailure = failure.kind
                        )
                    }
                    next += createDigest(
                        id = group.id,
                        fromTs = group.fromTs,
                        throughTsExclusive = group.throughTsExclusive,
                        sourceHashes = group.sources.flatMap { it.sourceHashes },
                        report = report
                    )
                }
                digests = next
                reductionLevels += 1
            }

            val rootDigest = digests.singleOrNull()
                ?: throw ClinicalOpenAiException.InvalidResponse()
            verifyRootCoverage(rootDigest, partitionPlan.coverage)
            notifyProgress(
                progress,
                ClinicalOpenAiProgress(
                    ClinicalOpenAiProgressStage.SYNTHESIZING,
                    0,
                    1,
                    reductionLevels
                )
            )
            val synthesisInput = buildSynthesisInput(
                payload = remotePayload,
                rootDigest = rootDigest,
                coverage = partitionPlan.coverage,
                rootWireProjection = partitionPlan.rootWireProjection
            )
            if (requestBytes(synthesisInput, ClinicalResponseKind.FINAL_REPORT) >
                requestByteBudget
            ) {
                throw ClinicalOpenAiException.RequestTooLarge()
            }
            partitionRequests.claim()
            val response = requestFinalReport(
                input = synthesisInput,
                credentialProvider = credentialProvider,
                execution = execution,
                beforeParse = {
                    notifyProgress(
                        progress,
                        ClinicalOpenAiProgress(
                            ClinicalOpenAiProgressStage.VALIDATING,
                            0,
                            1,
                            reductionLevels
                        )
                    )
                }
            )
            verifyRootCoverage(rootDigest, partitionPlan.coverage)
            notifyProgress(
                progress,
                ClinicalOpenAiProgress(
                    ClinicalOpenAiProgressStage.COMPLETED,
                    1,
                    1,
                    reductionLevels
                )
            )
            result(
                payload = payload,
                response = response,
                chunkCount = partitionPlan.leaves.size,
                usedSynthesis = true,
                reductionLevels = reductionLevels,
                coverageLedgerHash = partitionPlan.coverage.ledgerHash,
                execution = execution.snapshot()
            )
        } finally {
            execution.publish()
        }
    }

    override suspend fun analyzeAlert(
        context: AlertAiCanonicalContext,
        credential: suspend () -> String,
        networkLease: AlertAiValidatedNetworkLease
    ): AlertAiGatewayResult {
        if (!AlertAiContextValidator.isValid(context)) {
            throw ClinicalOpenAiException.InvalidInput()
        }
        val execution = ClinicalExecutionStats(
            startedNanos = monotonicClockNanos(),
            sourceRows = ClinicalSourceRowCounts(),
            monotonicClockNanos = monotonicClockNanos,
            observer = null
        )
        val response = requestStructured(
            input = context.canonicalJson,
            kind = ClinicalResponseKind.ALERT_CAUSE_V1,
            credentialProvider = credential,
            execution = execution,
            networkLease = networkLease
        ) { output ->
            AlertAiTypedPresentationParser.parse(output)
                ?: throw ClinicalOpenAiException.InvalidResponse()
        }
        val canonicalResult = AlertAiTypedPresentationParser.encodeCanonical(response.value)
            ?: throw ClinicalOpenAiException.InvalidResponse()
        return AlertAiGatewayResult(
            providerId = providerId,
            modelId = response.model,
            resultJson = canonicalResult
        )
    }

    private fun sourceRowCounts(dataset: ClinicalReportDataset): ClinicalSourceRowCounts = try {
        ClinicalSourceRowCounts(
            glucose = addRowCounts(
                dataset.detail24h.glucose.size,
                dataset.detail24h.calibratedGlucose.size,
                dataset.glucose7d.size,
                dataset.glucose30d.size
            ),
            insulin = addRowCounts(
                dataset.detail24h.therapy.count { it.insulinU != null },
                dataset.therapy7d.count { it.insulinU != null },
                dataset.therapy30d.count { it.insulinU != null }
            ),
            carbs = addRowCounts(
                dataset.detail24h.therapy.count { it.carbsG != null },
                dataset.therapy7d.count { it.carbsG != null },
                dataset.therapy30d.count { it.carbsG != null }
            ),
            targets = addRowCounts(
                dataset.detail24h.targets.size,
                dataset.targets7d.size,
                dataset.targets30d.size
            )
        )
    } catch (_: ArithmeticException) {
        throw ClinicalOpenAiException.InvalidInput()
    }

    private fun addRowCounts(vararg values: Int): Int =
        values.fold(0, Math::addExact)

    internal suspend fun buildUploadPlan(dataset: ClinicalReportDataset): ClinicalUploadPlan {
        val context = currentCoroutineContext()
        validateDatasetShape(dataset, allowTargetManagerEvidence = true, context::ensureActive)
        val remoteDataset = remoteDataset(dataset)
        validateDatasetShape(remoteDataset, allowTargetManagerEvidence = false, context::ensureActive)
        val plannedActivityWindow = ClinicalPlannedActivityPeriodPolicy
            .preparedWindow(remoteDataset)
            ?: throw ClinicalOpenAiException.InvalidInput()
        context.ensureActive()
        val compact = try {
            ClinicalReportDatasetBuilder.serializeCancellable(remoteDataset, serializationProbe)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (tooLarge: ClinicalOpenAiException.DatasetTooLarge) {
            throw tooLarge
        } catch (_: Exception) {
            throw ClinicalOpenAiException.InvalidInput()
        }
        val fullPayload = ClinicalReportPayload(
            dataset = remoteDataset,
            compactJson = compact,
            sha256 = ClinicalReportDatasetBuilder.sha256Cancellable(compact)
        )
        if (singleRequestBodyOrNull(fullPayload) != null) {
            return ClinicalUploadPlan(emptyList(), requiresSynthesis = false)
        }

        val partitionPlan = buildPartitionPlan(remoteDataset, compact)
        val zone = try {
            ZoneId.of(remoteDataset.zoneId)
        } catch (_: Exception) {
            throw ClinicalOpenAiException.InvalidInput()
        }
        val chunks = partitionPlan.leaves.mapIndexed { index, leaf ->
            val filtered = filterDataset(
                dataset = remoteDataset,
                fromTs = leaf.descriptor.fromTs,
                throughTs = leaf.descriptor.throughTsExclusive,
                includeThrough = false,
                plannedActivityWindow = plannedActivityWindow
            )
            ClinicalDatasetChunk(
                metadata = ClinicalChunkMetadata(
                    index = index,
                    count = partitionPlan.leaves.size,
                    day = Instant.ofEpochMilli(leaf.descriptor.fromTs)
                        .atZone(zone)
                        .toLocalDate()
                        .format(DateTimeFormatter.ISO_LOCAL_DATE),
                    windowFromTs = leaf.descriptor.fromTs,
                    windowThroughTs = leaf.descriptor.throughTsExclusive,
                    hash = leaf.descriptor.canonicalHash
                ),
                dataset = filtered,
                canonicalJson = leaf.canonicalJson,
                requestBytes = leaf.descriptor.requestBytes,
                requestInputFactory = {
                    partitionLeafInput(leaf).materialize(serializationProbe)
                }
            )
        }
        return ClinicalUploadPlan(chunks, requiresSynthesis = true)
    }

    internal suspend fun buildPartitionPlan(
        dataset: ClinicalReportDataset,
        fullCanonicalJson: String
    ): ClinicalPartitionPlan {
        val planner = ClinicalReportPartitionPlanner(
            requestBudgetBytes = requestByteBudget,
            maximumLeafCount = partitionLeafLimit,
            requestFactory = { _, identity, canonicalJson ->
                val input = partitionLeafInput(identity, canonicalJson)
                ClinicalSizedLeafRequest(
                    bytes = input.measuredRequestBytes(
                        template = chunkReportWireTemplate,
                        maxBytes = requestByteBudget,
                        probe = serializationProbe
                    )
                )
            },
            canonicalizer = { leaf, bounds ->
                ClinicalReportDatasetBuilder.serializePartitionCancellable(
                    leaf,
                    bounds,
                    serializationProbe
                )
            },
            probe = serializationProbe
        )
        return try {
            planner.plan(dataset, fullCanonicalJson)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (tooLarge: ClinicalOpenAiException.DatasetTooLarge) {
            throw tooLarge
        } catch (failure: ClinicalPartitionException) {
            throw when (failure) {
                is ClinicalPartitionException.MinimumIntervalTooLarge ->
                    ClinicalOpenAiException.RequestTooLarge()
                is ClinicalPartitionException.CoverageMismatch ->
                    ClinicalOpenAiException.InvalidInput()
            }
        } catch (_: Exception) {
            throw ClinicalOpenAiException.InvalidInput()
        }
    }

    internal suspend fun partitionRequestBodyForTest(
        leaf: ClinicalPartitionLeaf
    ): ByteArray = partitionLeafInput(leaf).requestBodyBytes(
        template = chunkReportWireTemplate,
        exactBytes = leaf.descriptor.requestBytes,
        probe = null
    )

    private fun requestBytes(input: String, kind: ClinicalResponseKind): Int =
        transport.requestBody(input, kind).size

    private suspend fun singleRequestBodyOrNull(payload: ClinicalReportPayload): ByteArray? {
        val input = fullDatasetInput(payload)
        val bytes = input.measuredRequestBytes(
            template = finalReportWireTemplate,
            maxBytes = requestByteBudget,
            probe = serializationProbe
        )
        if (bytes > requestByteBudget) return null
        return input.requestBodyBytes(finalReportWireTemplate, bytes, serializationProbe)
    }

    private fun captureRequestWireTemplate(
        kind: ClinicalResponseKind
    ): ClinicalRequestWireTemplate {
        val marker = "clinical-request-input-marker-v1"
        return ClinicalRequestWireTemplate.capture(
            body = transport.requestBody(marker, kind),
            quotedMarker = JsonPrimitive(marker).toString()
                .toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun fullDatasetInput(
        payload: ClinicalReportPayload
    ): ClinicalSegmentedJsonInput = segmentedDatasetInput(
        shell = JsonObject().apply {
            addProperty("kind", "clinical_dataset")
            addProperty("datasetSchemaVersion", payload.dataset.schemaVersion)
            addProperty("requestHash", payload.sha256)
            add("isfCrSourceCodebook", sourceCodebookJson())
            add("wireCodebook", wireCodebookJson())
            addProperty("dataset", SEGMENTED_DATASET_MARKER)
        }.toString(),
        canonicalDatasetJson = payload.compactJson
    )

    private fun partitionLeafInput(
        leaf: ClinicalPartitionLeaf
    ): ClinicalSegmentedJsonInput = partitionLeafInput(
        ClinicalLeafIdentity(
            id = leaf.descriptor.id,
            fromTs = leaf.descriptor.fromTs,
            throughTsExclusive = leaf.descriptor.throughTsExclusive,
            depth = leaf.descriptor.depth,
            canonicalHash = leaf.descriptor.canonicalHash
        ),
        leaf.canonicalJson
    )

    private fun partitionLeafInput(
        identity: ClinicalLeafIdentity,
        canonicalDatasetJson: String
    ): ClinicalSegmentedJsonInput = segmentedDatasetInput(
        shell = JsonObject().apply {
            addProperty("kind", "clinical_dataset_chunk")
            add("metadata", JsonObject().apply {
                addProperty("id", identity.id)
                addProperty("fromTs", identity.fromTs)
                addProperty("throughTsExclusive", identity.throughTsExclusive)
                addProperty("depth", identity.depth)
                addProperty("hash", identity.canonicalHash)
            })
            addProperty("dataset", SEGMENTED_DATASET_MARKER)
            add("isfCrSourceCodebook", sourceCodebookJson())
        }.toString(),
        canonicalDatasetJson = canonicalDatasetJson
    )

    private fun segmentedDatasetInput(
        shell: String,
        canonicalDatasetJson: String
    ): ClinicalSegmentedJsonInput {
        val marker = JsonPrimitive(SEGMENTED_DATASET_MARKER).toString()
        val index = shell.indexOf(marker)
        if (index < 0 || shell.indexOf(marker, index + marker.length) >= 0) {
            throw ClinicalOpenAiException.InvalidInput()
        }
        return ClinicalSegmentedJsonInput.of(
            shell.substring(0, index),
            canonicalDatasetJson,
            shell.substring(index + marker.length)
        )
    }

    private fun createRequest(credential: String, body: ByteArray): Request = try {
        transport.request(credential, body)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (fatal: Error) {
        throw fatal
    } catch (_: Exception) {
        throw ClinicalOpenAiException.CredentialUnavailable()
    }

    private suspend fun readCredential(provider: suspend () -> String): String {
        val credential = try {
            provider()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            throw ClinicalOpenAiException.CredentialUnavailable()
        }
        if (credential.isBlank()) {
            throw ClinicalOpenAiException.CredentialUnavailable()
        }
        return credential
    }

    private suspend fun requestFinalReport(
        input: String,
        credentialProvider: suspend () -> String,
        execution: ClinicalExecutionStats,
        beforeParse: () -> Unit = {}
    ): ParsedStructuredResponse<ClinicalAdvisoryReport> =
        requestStructured(
            input = input,
            kind = ClinicalResponseKind.FINAL_REPORT,
            credentialProvider = credentialProvider,
            execution = execution,
            beforeParse = beforeParse
        ) {
            ClinicalReportSchemaParser.parse(it)
        }

    private suspend fun requestFinalReport(
        exactRequestBody: ByteArray,
        credentialProvider: suspend () -> String,
        execution: ClinicalExecutionStats,
        beforeParse: () -> Unit = {}
    ): ParsedStructuredResponse<ClinicalAdvisoryReport> =
        requestStructured(
            exactRequestBody = exactRequestBody,
            credentialProvider = credentialProvider,
            execution = execution,
            beforeParse = beforeParse
        ) {
            ClinicalReportSchemaParser.parse(it)
        }

    private suspend fun requestChunkReport(
        exactRequestBody: ByteArray,
        credentialProvider: suspend () -> String,
        execution: ClinicalExecutionStats
    ): ParsedStructuredResponse<ClinicalChunkReport> =
        requestStructured(
            exactRequestBody = exactRequestBody,
            credentialProvider = credentialProvider,
            execution = execution
        ) {
            ClinicalChunkReportSchemaParser.parse(it)
        }

    private suspend fun <T> requestStructured(
        input: String,
        kind: ClinicalResponseKind,
        credentialProvider: suspend () -> String,
        execution: ClinicalExecutionStats,
        networkLease: AlertAiValidatedNetworkLease? = null,
        beforeParse: () -> Unit = {},
        parser: (String) -> T
    ): ParsedStructuredResponse<T> {
        val requestBytes = transport.requestBody(input, kind)
        return requestStructured(
            requestBytes,
            credentialProvider,
            execution,
            networkLease,
            beforeParse,
            parser
        )
    }

    private suspend fun <T> requestStructured(
        exactRequestBody: ByteArray,
        credentialProvider: suspend () -> String,
        execution: ClinicalExecutionStats,
        networkLease: AlertAiValidatedNetworkLease? = null,
        beforeParse: () -> Unit = {},
        parser: (String) -> T
    ): ParsedStructuredResponse<T> {
        if (exactRequestBody.size > requestByteBudget) {
            throw ClinicalOpenAiException.RequestTooLarge()
        }
        val credential = readCredential(credentialProvider)
        val request = createRequest(credential, exactRequestBody)
        execution.recordRequest(exactRequestBody.size)
        val response = execute(request, networkLease = networkLease)
        execution.recordResponse(response.bytes)
        if (response.statusCode !in 200..299) {
            throw httpFailure(response.statusCode)
        }
        if (response.oversized) {
            throw ClinicalOpenAiException.OversizedResponse()
        }
        beforeParse()
        return parseStructuredResponse(
            response.json ?: throw ClinicalOpenAiException.InvalidResponse(),
            parser
        )
    }

    private fun createDigest(
        id: String,
        fromTs: Long,
        throughTsExclusive: Long,
        sourceHashes: List<String>,
        report: ClinicalChunkReport
    ): ClinicalChunkDigest = try {
        ClinicalChunkDigest.create(
            id = id,
            fromTs = fromTs,
            throughTsExclusive = throughTsExclusive,
            sourceHashes = sourceHashes,
            dataQuality = report.dataQuality,
            findings = report.findings
        )
    } catch (_: ClinicalReductionException) {
        throw ClinicalOpenAiException.InvalidResponse()
    }

    internal fun reductionWireTemplate(): ClinicalReductionWireTemplate {
        val placeholder = "clinical-reduction-wire-input-placeholder-v1"
        val requestBody = transport.requestBody(
            input = placeholder,
            kind = ClinicalResponseKind.CHUNK_REPORT
        ).toByteString()
        val escapedPlaceholder = JsonPrimitive(placeholder).toString().encodeUtf8()
        val index = requestBody.indexOf(escapedPlaceholder)
        if (
            index < 0 ||
            requestBody.indexOf(escapedPlaceholder, index + escapedPlaceholder.size) >= 0
        ) {
            throw ClinicalOpenAiException.InvalidInput()
        }
        return try {
            ClinicalReductionWireTemplate.create(
                prefixBytes = requestBody.substring(0, index),
                suffixBytes = requestBody.substring(index + escapedPlaceholder.size)
            )
        } catch (_: ClinicalReductionException) {
            throw ClinicalOpenAiException.InvalidInput()
        }
    }

    private fun sanitizedReductionFailure(
        failure: ClinicalReductionException
    ): ClinicalOpenAiException = when (failure) {
        is ClinicalReductionException.DigestTooLarge,
        is ClinicalReductionException.CannotReduce ->
            ClinicalOpenAiException.RequestTooLarge()
        is ClinicalReductionException.InvalidDigest,
        is ClinicalReductionException.CoverageMismatch ->
            ClinicalOpenAiException.InvalidResponse()
    }

    internal fun verifyRootCoverage(
        root: ClinicalChunkDigest,
        coverage: ClinicalCoverageLedger
    ) {
        if (
            ClinicalReportDatasetBuilder.sha256(root.canonicalJson) != root.hash ||
            root.fromTs != coverage.expectedFromTs ||
            root.throughTsExclusive != coverage.expectedThroughTsExclusive ||
            root.sourceHashes != coverage.leafHashes
        ) {
            throw ClinicalOpenAiException.InvalidResponse()
        }
    }

    private fun notifyProgress(
        callback: ClinicalOpenAiProgressCallback,
        progress: ClinicalOpenAiProgress
    ) {
        try {
            callback.onProgress(progress)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (fatal: LinkageError) {
            throw fatal
        } catch (_: Throwable) {
            // Progress is observational and cannot alter a completed or in-flight analysis.
        }
    }

    private suspend fun execute(
        request: Request,
        responseByteLimit: Int = maxResponseBytes,
        networkLease: AlertAiValidatedNetworkLease? = null
    ): ClinicalTransportResponse = suspendCancellableCoroutine { continuation ->
        val call = networkLease?.newCall(httpClient, request) ?: httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isActive) return
                continuation.resumeWithException(sanitizeTransportFailure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val outcome = runCatching {
                    response.use {
                        readBoundedBody(it, responseByteLimit)
                    }
                }
                if (!continuation.isActive) return
                outcome.fold(
                    onSuccess = continuation::resume,
                    onFailure = { failure ->
                        val sanitized = when (failure) {
                            is ClinicalOpenAiException -> failure
                            is IOException -> sanitizeTransportFailure(failure)
                            else -> ClinicalOpenAiException.InvalidResponse()
                        }
                        continuation.resumeWithException(sanitized)
                    }
                )
            }
        })
    }

    private fun readBoundedBody(
        response: Response,
        responseByteLimit: Int
    ): ClinicalTransportResponse {
        val body = response.body
        val source = body.source()
        val buffer = Buffer()
        val retainBody = response.isSuccessful
        var total = 0L
        while (true) {
            val remaining = responseByteLimit + 1L - total
            if (remaining <= 0L) {
                buffer.clear()
                return ClinicalTransportResponse(
                    json = null,
                    bytes = total,
                    statusCode = response.code,
                    oversized = true
                )
            }
            val read = source.read(buffer, minOf(8_192L, remaining))
            if (read == -1L) break
            total += read
            if (!retainBody) buffer.clear()
            if (total > responseByteLimit) {
                buffer.clear()
                return ClinicalTransportResponse(
                    json = null,
                    bytes = total,
                    statusCode = response.code,
                    oversized = true
                )
            }
        }
        return ClinicalTransportResponse(
            json = if (retainBody) {
                buffer.readString(StandardCharsets.UTF_8)
            } else {
                buffer.clear()
                null
            },
            bytes = total,
            statusCode = response.code,
            oversized = false
        )
    }

    private fun <T> parseStructuredResponse(
        raw: String,
        parser: (String) -> T
    ): ParsedStructuredResponse<T> {
        if (raw.toByteArray(StandardCharsets.UTF_8).size > maxResponseBytes) {
            throw ClinicalOpenAiException.OversizedResponse()
        }
        val envelope = transport.parseEnvelope(raw)
        return ParsedStructuredResponse(
            value = parser(envelope.output),
            model = envelope.model,
            systemFingerprint = envelope.systemFingerprint
        )
    }

    private fun result(
        payload: ClinicalReportPayload,
        response: ParsedStructuredResponse<ClinicalAdvisoryReport>,
        chunkCount: Int,
        usedSynthesis: Boolean,
        reductionLevels: Int = 0,
        coverageLedgerHash: String? = null,
        execution: ClinicalOpenAiExecutionSnapshot
    ) = ClinicalOpenAiResult(
        report = response.value,
        metadata = ClinicalOpenAiMetadata(
            model = response.model,
            requestedModel = modelId,
            systemFingerprint = response.systemFingerprint,
            schemaName = SCHEMA_NAME,
            schemaVersion = SCHEMA_VERSION,
            datasetSchemaVersion = payload.dataset.schemaVersion,
            requestHash = payload.sha256,
            chunkCount = chunkCount,
            usedSynthesis = usedSynthesis,
            reductionLevels = reductionLevels,
            coverageLedgerHash = coverageLedgerHash,
            maxRequestBytes = execution.maxRequestBytes,
            maxResponseBytes = execution.maxResponseBytes,
            totalRequestBytes = execution.totalRequestBytes,
            totalResponseBytes = execution.totalResponseBytes,
            durationMs = execution.durationMs,
            sourceRows = execution.sourceRows,
            providerId = providerId,
            requestedProviderId = providerId
        )
    )

    companion object {
        const val DEFAULT_MODEL = "gpt-5.6-terra"
        const val SCHEMA_NAME = "clinical_advisory_report_v3"
        const val CHUNK_SCHEMA_NAME = "clinical_chunk_findings_v3"
        const val ALERT_CAUSE_SCHEMA_NAME = "alert_cause_v1"
        const val SCHEMA_VERSION = 3
        const val MAX_LIST_ITEMS = 4
        const val MAX_OUTPUT_TOKENS = 25_000
        const val CHUNK_MAX_OUTPUT_TOKENS = 25_000
        const val ALERT_CAUSE_MAX_OUTPUT_TOKENS = 1_024
        const val MIN_CLINICAL_MMOL = 1.0
        const val MAX_CLINICAL_MMOL = 40.0
        const val MAX_CV_PCT = 200.0
        const val MAX_30D_MINUTES = 30 * 24 * 60
        const val MAX_RECORDED_INSULIN_UNITS = 10_000.0
        const val MAX_RECORDED_CARBS_GRAMS = 100_000.0
        private const val CANONICAL_BUCKET_MINUTES = 5
        const val MAX_CANONICAL_30D_SAMPLE_COUNT =
            MAX_30D_MINUTES / CANONICAL_BUCKET_MINUTES + 1
        const val MAX_MODEL_ID_CODE_POINTS = 128
        const val MAX_SYSTEM_FINGERPRINT_CODE_POINTS = 128
        const val MAX_TOTAL_SERIES_ROWS = 20_000
        const val MAX_COMPACT_DATASET_BYTES = 8 * 1_024 * 1_024
        private const val MAX_PLANNED_ACTIVITY_OCCURRENCES = 128
        private const val MAX_LOCAL_TARGET_BLOCKERS = 12
        private val ALLOWED_FOOD_PROFILES = setOf("FAST", "MIXED", "FAT_PROTEIN")
        private val ALLOWED_ACTIVITY_PROFILES = setOf("LOW", "MODERATE", "HIGH")
        private val ALLOWED_ACTIVITY_TYPES = setOf("WALKING", "AEROBIC", "STRENGTH", "MIXED")
        private val ALLOWED_ACTIVITY_INTENSITIES = setOf("LIGHT", "MEDIUM", "HIGH")
        private val ALLOWED_PROFILE_SOURCES = setOf("AUTO", "MANUAL", "DEFAULT")
        private val ALLOWED_PROFILE_CONFIDENCE = setOf(
            "INSUFFICIENT_DATA", "PROVISIONAL", "STABLE", "HIGH_CONFIDENCE"
        )
        private val ALLOWED_CALORIE_GOALS = setOf(
            "OFF", "MAINTENANCE", "LOSS", "GAIN", "MANUAL_CLINICIAN"
        )
        private val ALLOWED_SEX = setOf("FEMALE", "MALE")
        private val ALLOWED_ADHERENCE = setOf("NOT_MEASURED", "MEASURED")
        private val ALLOWED_LOCAL_TARGET_DECISIONS =
            TargetDecisionOutcome.entries.map(TargetDecisionOutcome::name).toSet() + "NO_DECISION"
        private val LOCAL_TARGET_BLOCKER = Regex("[A-Za-z0-9_.:=#-]{1,120}")
        private const val MAX_ENUM_CODE_POINTS = 64
        const val MAX_SCHEMA_RESPONSE_BYTES = 16 * 1_024
        const val MAX_CHUNK_REPORT_BYTES = 4 * 1_024
        const val DEFAULT_REQUEST_BYTE_BUDGET = 512 * 1_024
        const val DEFAULT_MAX_RESPONSE_BYTES = 128 * 1_024
        // A partition may consume one map call per bounded leaf and at most one
        // additional reduction/final call per leaf before failing closed.
        internal const val MAX_PARTITION_REMOTE_REQUESTS =
            ClinicalReportPartitionPlanner.MAX_PARTITION_LEAVES * 2

        private const val OFFICIAL_ENDPOINT = "https://api.openai.com/v1/responses"
        private const val MAX_CONNECTION_REQUEST_BYTES = 4 * 1_024
        private const val MAX_CONNECTION_RESPONSE_BYTES = 16 * 1_024
        private const val CONNECTION_MAX_OUTPUT_TOKENS = 1_024
        private const val CANCELLATION_CHECKPOINT_ROWS = 256
        private const val SEGMENTED_DATASET_MARKER = "clinical-segmented-dataset-marker-v1"
        internal const val CLINICAL_INSTRUCTIONS =
            "Analyze the supplied clinical dataset for advisory review only. " +
                "Provide no direct insulin dosing, no carbs-to-add, no target setting, " +
                "no calibration, no urgent treatment command, and no executable action. " +
                "Return only schema-defined enums and bounded numeric evidence; generate no prose. " +
                "Frame recommendations only as care-team discussion topics. " +
                "p30 is the +30m horizon from a forecast generated p30Age minutes ago. " +
                "Summary carbs include entered food plus UAM carbohydrates. " +
                "When insulinSource=AAPS_RAW_HISTORY, insulin is authoritative delivered basal plus bolus; " +
                "insulinIobDerived is diagnostic evidence and must not be added again. " +
                "When insulinSource=COPILOT_EVENTS, insulin and carbs are incomplete local event " +
                "capture, not all delivered therapy, and must not be described as totals. " +
                "Use physical activity only when telemetry origin is LOCAL_ACTIVITY or HEALTH_CONNECT. " +
                "Compact therapy rows are [minute,insulin,carbs,uam,evidence,context], " +
                "where evidence 1=confirmed and 2=IOB-derived; telemetry rows are " +
                "[minute,key,value,quality,origin], where origins 2 and 3 are physical activity. " +
                "ISF/CR source codebook: " +
                "0=NO_OVERRIDE, 1=AAPS, 2=EVIDENCE, 3=COPILOT_NATIVE."

        internal fun forTest(
            endpoint: String,
            model: String = DEFAULT_MODEL,
            requestByteBudget: Int = DEFAULT_REQUEST_BYTE_BUDGET,
            maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
            timeouts: ClinicalOpenAiTimeouts = ClinicalOpenAiTimeouts(),
            monotonicClockNanos: () -> Long = System::nanoTime,
            serializationProbe: ClinicalDatasetSerializationProbe? = null,
            partitionLeafLimit: Int = ClinicalReportPartitionPlanner.MAX_PARTITION_LEAVES,
            partitionRequestLimit: Int = MAX_PARTITION_REMOTE_REQUESTS
        ): ClinicalOpenAiClient {
            val url = try {
                endpoint.toHttpUrl()
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid clinical test endpoint")
            }
            require(isExactLoopbackResponsesUrl(url)) {
                "Clinical test endpoint must be loopback /v1/responses"
            }
            validateModel(model)
            return ClinicalOpenAiClient(
                transport = OpenAiClinicalStructuredTransport(
                    endpoint = url,
                    modelId = validateModel(model)
                ),
                requestByteBudget = validateRequestBudget(requestByteBudget),
                maxResponseBytes = validateResponseBudget(maxResponseBytes),
                timeouts = timeouts,
                monotonicClockNanos = monotonicClockNanos,
                serializationProbe = serializationProbe,
                partitionLeafLimit = partitionLeafLimit.also {
                    require(it in 1..ClinicalReportPartitionPlanner.MAX_PARTITION_LEAVES)
                },
                partitionRequestLimit = partitionRequestLimit.also {
                    require(it in 1..MAX_PARTITION_REMOTE_REQUESTS)
                }
            )
        }

        internal fun forTransport(
            transport: ClinicalAiStructuredTransport,
            requestByteBudget: Int = DEFAULT_REQUEST_BYTE_BUDGET,
            maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
            timeouts: ClinicalOpenAiTimeouts = ClinicalOpenAiTimeouts(),
            monotonicClockNanos: () -> Long = System::nanoTime,
            serializationProbe: ClinicalDatasetSerializationProbe? = null
        ): ClinicalOpenAiClient = ClinicalOpenAiClient(
            transport = transport,
            requestByteBudget = validateRequestBudget(requestByteBudget),
            maxResponseBytes = validateResponseBudget(maxResponseBytes),
            timeouts = timeouts,
            monotonicClockNanos = monotonicClockNanos,
            serializationProbe = serializationProbe,
            partitionLeafLimit = ClinicalReportPartitionPlanner.MAX_PARTITION_LEAVES,
            partitionRequestLimit = MAX_PARTITION_REMOTE_REQUESTS
        )

        internal fun requestBytesForTest(
            dataset: ClinicalReportDataset,
            model: String
        ): Int {
            validateDataset(dataset)
            val compact = ClinicalReportDatasetBuilder.serialize(dataset)
            val payload = ClinicalReportPayload(
                dataset = dataset,
                compactJson = compact,
                sha256 = ClinicalReportDatasetBuilder.sha256(compact)
            )
            return requestBytes(
                buildFullInputForTest(payload),
                validateModel(model),
                ClinicalResponseKind.FINAL_REPORT
            )
        }

        internal fun requestBytesForTest(
            dataset: ClinicalReportDataset,
            transport: ClinicalAiStructuredTransport
        ): Int {
            validateDataset(dataset)
            val compact = ClinicalReportDatasetBuilder.serialize(dataset)
            val payload = ClinicalReportPayload(
                dataset = dataset,
                compactJson = compact,
                sha256 = ClinicalReportDatasetBuilder.sha256(compact)
            )
            return transport.requestBody(
                buildFullInputForTest(payload),
                ClinicalResponseKind.FINAL_REPORT
            ).size
        }

        internal fun strictSchemaJson(): JsonObject {
            val recommendation = strictObject(
                properties = JsonObject().apply {
                    add(
                        "careTeamDiscussionTopic",
                        enumSchema(ClinicalCareTeamDiscussionTopic.entries.map { it.name })
                    )
                    add(
                        "priority",
                        enumSchema(ClinicalAdvisoryPriority.entries.map { it.name })
                    )
                    add("evidenceFindingIndices", JsonObject().apply {
                        addProperty("type", "array")
                        addProperty("minItems", 1)
                        addProperty("maxItems", MAX_LIST_ITEMS)
                        add("items", JsonObject().apply {
                            addProperty("type", "integer")
                            addProperty("minimum", 0)
                            addProperty("maximum", MAX_LIST_ITEMS - 1)
                        })
                    })
                    add(
                        "period",
                        enumSchema(ClinicalEvidencePeriod.entries.map { it.name })
                    )
                },
                required = listOf(
                    "careTeamDiscussionTopic",
                    "priority",
                    "evidenceFindingIndices",
                    "period"
                )
            )
            val properties = JsonObject().apply {
                add(
                    "summary7dStatus",
                    enumSchema(ClinicalSummaryStatus.entries.map { it.name })
                )
                add(
                    "summary30dStatus",
                    enumSchema(ClinicalSummaryStatus.entries.map { it.name })
                )
                add(
                    "dataQuality",
                    exclusiveEnumArraySchema(
                        MAX_LIST_ITEMS,
                        ClinicalDataQualityFlag.entries.map { it.name },
                        setOf(ClinicalDataQualityFlag.COMPLETE.name)
                    )
                )
                add("patterns", JsonObject().apply {
                    addProperty("type", "array")
                    addProperty("maxItems", MAX_LIST_ITEMS)
                    add("items", findingSchema())
                })
                add(
                    "safetyObservations",
                    exclusiveEnumArraySchema(
                        MAX_LIST_ITEMS,
                        ClinicalSafetyObservation.entries.map { it.name },
                        ClinicalSafetyObservation.entries.map { it.name }
                            .filterTo(mutableSetOf()) {
                                it == "NONE" || it.startsWith("NONE_") || it.startsWith("NO_")
                            }
                    )
                )
                add("recommendations", JsonObject().apply {
                    addProperty("type", "array")
                    addProperty("maxItems", MAX_LIST_ITEMS)
                    add("items", recommendation)
                })
                add(
                    "careTeamQuestions",
                    enumArraySchema(
                        MAX_LIST_ITEMS,
                        ClinicalCareTeamQuestion.entries.map { it.name }
                    )
                )
            }
            return strictObject(
                properties,
                listOf(
                    "summary7dStatus",
                    "summary30dStatus",
                    "dataQuality",
                    "patterns",
                    "safetyObservations",
                    "recommendations",
                    "careTeamQuestions"
                )
            )
        }

        internal fun strictAlertCauseSchemaJson(): JsonObject = JsonObject().apply {
            addProperty("type", "object")
            addProperty("additionalProperties", false)
            add("properties", JsonObject().apply {
                add("schemaVersion", JsonObject().apply {
                    addProperty("type", "integer")
                    add("const", JsonPrimitive(1))
                })
                add("primaryCauseCode", JsonObject().apply {
                    addProperty("type", "string")
                    add("enum", JsonArray().apply {
                        io.aaps.copilot.domain.alerts.AlertCauseCode.entries.forEach { add(it.name) }
                    })
                })
                add("confidence", JsonObject().apply {
                    addProperty("type", "string")
                    add("enum", JsonArray().apply {
                        io.aaps.copilot.domain.alerts.CauseConfidence.entries.forEach { add(it.name) }
                    })
                })
                add("evidenceCodes", JsonObject().apply {
                    addProperty("type", "array")
                    addProperty("maxItems", 8)
                    add("items", JsonObject().apply {
                        addProperty("type", "string")
                        add("enum", JsonArray().apply {
                            AlertAiTypedPresentationParser.allowedEvidenceCodes()
                                .forEach(::add)
                        })
                    })
                })
                add("adviceCode", JsonObject().apply {
                    addProperty("type", "string")
                    add("enum", JsonArray().apply {
                        io.aaps.copilot.domain.alerts.AlertCauseCode.entries.forEach { add(it.name) }
                    })
                })
            })
            add("required", JsonArray().apply {
                add("schemaVersion")
                add("primaryCauseCode")
                add("confidence")
                add("evidenceCodes")
                add("adviceCode")
            })
        }

        internal fun strictChunkSchemaJson(): JsonObject =
            strictObject(
                JsonObject().apply {
                    add(
                        "dataQuality",
                        exclusiveEnumArraySchema(
                            1,
                            ClinicalDataQualityFlag.entries.map { it.name },
                            setOf(ClinicalDataQualityFlag.COMPLETE.name)
                        )
                    )
                    add("findings", JsonObject().apply {
                        addProperty("type", "array")
                        addProperty("maxItems", 1)
                        add("items", findingSchema())
                    })
                },
                listOf("dataQuality", "findings")
            )

        private fun enumSchema(values: List<String>) = JsonObject().apply {
            addProperty("type", "string")
            add("enum", JsonArray().apply { values.forEach(::add) })
        }

        private fun enumArraySchema(maxItems: Int, values: List<String>) =
            JsonObject().apply {
                addProperty("type", "array")
                addProperty("maxItems", maxItems)
                add("items", enumSchema(values))
            }

        private fun exclusiveEnumArraySchema(
            maxItems: Int,
            values: List<String>,
            exclusiveValues: Set<String>
        ) = JsonObject().apply {
            add("anyOf", JsonArray().apply {
                add(enumArraySchema(1, exclusiveValues.sorted()))
                add(enumArraySchema(maxItems, values.filterNot(exclusiveValues::contains)))
            })
        }

        private fun findingSchema() = JsonObject().apply {
            add("anyOf", JsonArray().apply {
                ClinicalEvidenceMetric.entries.forEach { metric ->
                    val rule = evidenceRule(metric)
                    add(
                        strictObject(
                            JsonObject().apply {
                                add(
                                    "topic",
                                    enumSchema(ClinicalPatternTopic.entries.map { it.name })
                                )
                                add(
                                    "period",
                                    enumSchema(ClinicalEvidencePeriod.entries.map { it.name })
                                )
                                add(
                                    "direction",
                                    enumSchema(ClinicalPatternDirection.entries.map { it.name })
                                )
                                add(
                                    "confidence",
                                    enumSchema(ClinicalFindingConfidence.entries.map { it.name })
                                )
                                add(
                                    "timeBand",
                                    enumSchema(ClinicalTimeBand.entries.map { it.name })
                                )
                                add("evidenceMetric", enumSchema(listOf(metric.name)))
                                add("evidenceValue", JsonObject().apply {
                                    addProperty("type", rule.type.schemaName)
                                    addProperty("minimum", rule.minimum)
                                    addProperty("maximum", rule.maximum)
                                })
                            },
                            listOf(
                                "topic",
                                "period",
                                "direction",
                                "confidence",
                                "timeBand",
                                "evidenceMetric",
                                "evidenceValue"
                            )
                        )
                    )
                }
            })
        }

        private fun strictObject(
            properties: JsonObject,
            required: List<String>
        ) = JsonObject().apply {
            addProperty("type", "object")
            addProperty("additionalProperties", false)
            add("properties", properties)
            add("required", JsonArray().apply { required.forEach(::add) })
        }

        private suspend fun validatePayload(
            payload: ClinicalReportPayload,
            serializationProbe: ClinicalDatasetSerializationProbe?
        ): String {
            val context = currentCoroutineContext()
            validateDatasetShape(
                payload.dataset,
                allowTargetManagerEvidence = true,
                checkpoint = context::ensureActive
            )
            val remoteDataset = remoteDataset(payload.dataset)
            validateDatasetShape(
                remoteDataset,
                allowTargetManagerEvidence = false,
                checkpoint = context::ensureActive
            )
            if (clinicalUtf8SizeAtMost(payload.compactJson, MAX_COMPACT_DATASET_BYTES) >
                MAX_COMPACT_DATASET_BYTES
            ) {
                throw ClinicalOpenAiException.DatasetTooLarge()
            }
            try {
                ClinicalReportDatasetBuilder.validateCanonicalCancellable(
                    remoteDataset,
                    payload.compactJson,
                    serializationProbe
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (tooLarge: ClinicalOpenAiException.DatasetTooLarge) {
                throw tooLarge
            } catch (_: Exception) {
                throw ClinicalOpenAiException.InvalidInput()
            }
            if (payload.sha256 != ClinicalReportDatasetBuilder.sha256Cancellable(payload.compactJson)) {
                throw ClinicalOpenAiException.InvalidInput()
            }
            return payload.compactJson
        }

        private fun remoteDataset(dataset: ClinicalReportDataset): ClinicalReportDataset = dataset.copy(
            plannedActivities = dataset.plannedActivities.map { activity ->
                activity.copy(targetDecision = null, targetBlockers = emptyList())
            }
        )

        private fun validateDataset(dataset: ClinicalReportDataset) {
            if (dataset.schemaVersion != ClinicalReportDatasetBuilder.SCHEMA_VERSION) {
                throw ClinicalOpenAiException.InvalidInput()
            }
        }

        internal fun validateRemoteDatasetForTest(dataset: ClinicalReportDataset) {
            validateDatasetShape(dataset, allowTargetManagerEvidence = false, checkpoint = null)
        }

        private fun validateDatasetShape(
            dataset: ClinicalReportDataset,
            allowTargetManagerEvidence: Boolean,
            checkpoint: (() -> Unit)? = null
        ) {
            validateDataset(dataset)
            ClinicalPlannedActivityPeriodPolicy.requireBoundedPreparedWindow(dataset)
            validateCurrentSnapshot(dataset.currentSnapshot)
            validateEnergyMetadata(dataset, allowTargetManagerEvidence)
            val counts = intArrayOf(
                dataset.detail24h.glucose.size,
                dataset.detail24h.calibratedGlucose.size,
                dataset.detail24h.therapy.size,
                dataset.detail24h.targets.size,
                dataset.detail24h.forecasts.size,
                dataset.detail24h.telemetry.size,
                dataset.glucose7d.size,
                dataset.therapy7d.size,
                dataset.targets7d.size,
                dataset.glucose30d.size,
                dataset.therapy30d.size,
                dataset.targets30d.size,
                dataset.eventSummaries.size
            )
            val total = try {
                counts.fold(0) { accumulator, count -> Math.addExact(accumulator, count) }
            } catch (_: ArithmeticException) {
                throw ClinicalOpenAiException.DatasetTooLarge()
            }
            if (total > MAX_TOTAL_SERIES_ROWS) {
                throw ClinicalOpenAiException.DatasetTooLarge()
            }
            validateEventSummaries(dataset.eventSummaries, checkpoint)
        }

        private fun validateEventSummaries(
            events: List<ClinicalEventSummary>,
            checkpoint: (() -> Unit)?
        ) {
            events.forEachIndexed { index, event ->
                if (index % 256 == 0) checkpoint?.invoke()
                if (
                    event.localId.isBlank() ||
                    event.startTs < 0L ||
                    event.endTs < event.startTs ||
                    event.type.isBlank() || event.type.length > 32 ||
                    event.subtype.length > 48 ||
                    event.severity.isBlank() || event.severity.length > 16 ||
                    event.source.isBlank() || event.source.length > 16 ||
                    event.title.length > 120 ||
                    (event.note?.length ?: 0) > 2_000
                ) {
                    throw ClinicalOpenAiException.InvalidInput()
                }
            }
        }

        /** Keep optional profile data typed, bounded and free of schedule identifiers/text. */
        internal fun isValidEnergyMetadata(
            dataset: ClinicalReportDataset,
            allowTargetManagerEvidence: Boolean
        ): Boolean {
            val profile = dataset.energyProfile
            val plannedActivityWindow = ClinicalPlannedActivityPeriodPolicy.preparedWindow(dataset)
                ?: return false
            if (dataset.plannedActivities.size > MAX_PLANNED_ACTIVITY_OCCURRENCES) {
                return false
            }
            if (profile == null && dataset.plannedActivities.isNotEmpty()) {
                return false
            }
            profile?.let {
                if (it.derivedAgeYears != null && it.derivedAgeYears !in 0..130 ||
                    it.evidenceDays !in 0..14 ||
                    it.foodProfile !in ALLOWED_FOOD_PROFILES && it.foodProfile != null ||
                    it.activityProfile !in ALLOWED_ACTIVITY_PROFILES && it.activityProfile != null ||
                    it.foodProfileSource !in ALLOWED_PROFILE_SOURCES ||
                    it.activityProfileSource !in ALLOWED_PROFILE_SOURCES ||
                    it.confidence !in ALLOWED_PROFILE_CONFIDENCE ||
                    it.calorieGoalMode !in ALLOWED_CALORIE_GOALS ||
                    it.sex != null && it.sex !in ALLOWED_SEX
                ) {
                    return false
                }
                it.maintenanceEnergyKcal?.let { range ->
                    if (range.minimum !in 0.0..15_000.0 || range.maximum !in 0.0..15_000.0) {
                        return false
                    }
                }
            }
            dataset.plannedActivities.forEach { activity ->
                val invalidTargetEvidence = if (allowTargetManagerEvidence) {
                    (activity.targetDecision != null &&
                        activity.targetDecision !in ALLOWED_LOCAL_TARGET_DECISIONS) ||
                        activity.targetBlockers.size > MAX_LOCAL_TARGET_BLOCKERS ||
                        activity.targetBlockers.any { blocker ->
                            !LOCAL_TARGET_BLOCKER.matches(blocker)
                        }
                } else {
                    activity.targetDecision != null || activity.targetBlockers.isNotEmpty()
                }
                if (activity.type !in ALLOWED_ACTIVITY_TYPES ||
                    activity.intensity !in ALLOWED_ACTIVITY_INTENSITIES ||
                    activity.plannedDurationMinutes !in 1..1_440 ||
                    activity.observedMinutes?.let { it !in 0..activity.plannedDurationMinutes } == true ||
                    activity.adherence !in ALLOWED_ADHERENCE ||
                    invalidTargetEvidence ||
                    !ClinicalPlannedActivityPeriodPolicy.overlaps(activity, plannedActivityWindow)
                ) {
                    return false
                }
            }
            return true
        }

        private fun validateEnergyMetadata(
            dataset: ClinicalReportDataset,
            allowTargetManagerEvidence: Boolean
        ) {
            if (dataset.plannedActivities.size > MAX_PLANNED_ACTIVITY_OCCURRENCES) {
                throw ClinicalOpenAiException.DatasetTooLarge()
            }
            if (!isValidEnergyMetadata(dataset, allowTargetManagerEvidence)) {
                throw ClinicalOpenAiException.InvalidInput()
            }
        }

        internal fun isValidCurrentSnapshot(snapshot: ClinicalCurrentSnapshot): Boolean =
            ClinicalCurrentWireSchema.isValid(snapshot)

        private fun validateCurrentSnapshot(snapshot: ClinicalCurrentSnapshot) {
            if (!isValidCurrentSnapshot(snapshot)) throw ClinicalOpenAiException.InvalidInput()
        }

        private fun validateModel(model: String): String =
            ClinicalAiModelIdPolicy.requireValid(model)

        private fun validateRequestBudget(value: Int): Int {
            require(value in 1_024..4 * 1_024 * 1_024) {
                "Invalid clinical request budget"
            }
            return value
        }

        private fun validateResponseBudget(value: Int): Int {
            require(value in 1_024..1 * 1_024 * 1_024) {
                "Invalid clinical response budget"
            }
            return value
        }

        private fun isExactLoopbackResponsesUrl(url: HttpUrl): Boolean {
            val loopback = url.host == "127.0.0.1" ||
                url.host == "localhost" ||
                url.host == "::1"
            return loopback &&
                url.scheme == "http" &&
                url.encodedPath == "/v1/responses" &&
                url.query == null &&
                url.username.isEmpty() &&
                url.password.isEmpty()
        }

        private fun buildFullInputForTest(payload: ClinicalReportPayload): String = JsonObject().apply {
            addProperty("kind", "clinical_dataset")
            addProperty("datasetSchemaVersion", payload.dataset.schemaVersion)
            addProperty("requestHash", payload.sha256)
            add("isfCrSourceCodebook", sourceCodebookJson())
            add("wireCodebook", wireCodebookJson())
            add("dataset", JsonParser.parseString(payload.compactJson))
        }.toString()

        private fun sourceCodebookJson(): JsonObject = JsonObject().apply {
            addProperty("0", "NO_OVERRIDE")
            addProperty("1", "AAPS")
            addProperty("2", "EVIDENCE")
            addProperty("3", "COPILOT_NATIVE")
        }

        private fun wireCodebookJson(): JsonObject = JsonObject().apply {
            addProperty("glucoseRow", "[minuteOffset,mmol]")
            addProperty(
                "therapyRow",
                "[minuteOffset,insulinU,carbsG,syntheticUamFlag,insulinEvidence,contextKind]"
            )
            add("insulinEvidence", JsonObject().apply {
                addProperty("1", "CONFIRMED")
                addProperty("2", "IOB_DERIVED")
            })
            add("therapyContext", JsonObject().apply {
                addProperty("1", "INFUSION_SET_CHANGE")
                addProperty("2", "SENSOR_CHANGE")
                addProperty("3", "INSULIN_REFILL")
                addProperty("4", "PUMP_BATTERY_CHANGE")
                addProperty("5", "EXERCISE")
                addProperty("6", "PROFILE_SWITCH")
            })
            addProperty("telemetryRow", "[minuteOffset,key,value,quality,origin]")
            add("telemetryOrigin", JsonObject().apply {
                addProperty("0", "OTHER")
                addProperty("1", "AAPS")
                addProperty("2", "LOCAL_ACTIVITY")
                addProperty("3", "HEALTH_CONNECT")
                addProperty("4", "COPILOT_RUNTIME")
            })
        }

        internal fun buildRequestJson(
            input: String,
            model: String,
            kind: ClinicalResponseKind
        ): JsonObject = JsonObject().apply {
            val isChunk = kind == ClinicalResponseKind.CHUNK_REPORT
            val isAlert = kind == ClinicalResponseKind.ALERT_CAUSE_V1
            addProperty("model", model)
            addProperty("store", false)
            addProperty(
                "instructions",
                if (isAlert) ALERT_CAUSE_INSTRUCTIONS else CLINICAL_INSTRUCTIONS
            )
            addProperty(
                "max_output_tokens",
                when {
                    isAlert -> ALERT_CAUSE_MAX_OUTPUT_TOKENS
                    isChunk -> CHUNK_MAX_OUTPUT_TOKENS
                    else -> MAX_OUTPUT_TOKENS
                }
            )
            add("reasoning", JsonObject().apply { addProperty("effort", "low") })
            add("text", JsonObject().apply {
                add("format", JsonObject().apply {
                    addProperty("type", "json_schema")
                    addProperty(
                        "name",
                        when {
                            isAlert -> ALERT_CAUSE_SCHEMA_NAME
                            isChunk -> CHUNK_SCHEMA_NAME
                            else -> SCHEMA_NAME
                        }
                    )
                    addProperty("strict", true)
                    add(
                        "schema",
                        when {
                            isAlert -> strictAlertCauseSchemaJson()
                            isChunk -> strictChunkSchemaJson()
                            else -> strictSchemaJson()
                        }
                    )
                })
            })
            addProperty("input", input)
        }

        internal const val ALERT_CAUSE_INSTRUCTIONS =
            "Return only the alert_cause_v1 code object. Use only supplied evidence. " +
                "Do not emit prose, insulin doses, carbohydrate amounts, target values, " +
                "calibration commands, therapy actions, or treatment instructions."

        internal fun buildConnectionRequestJson(model: String): JsonObject = JsonObject().apply {
            addProperty("model", model)
            addProperty("store", false)
            add("reasoning", JsonObject().apply {
                addProperty("effort", "low")
            })
            addProperty("max_output_tokens", CONNECTION_MAX_OUTPUT_TOKENS)
            addProperty(
                "instructions",
                "Return only the fixed connection status object."
            )
            add("text", JsonObject().apply {
                add("format", JsonObject().apply {
                    addProperty("type", "json_schema")
                    addProperty("name", "clinical_ai_connection_test_v1")
                    addProperty("strict", true)
                    add("schema", JsonObject().apply {
                        addProperty("type", "object")
                        addProperty("additionalProperties", false)
                        add("properties", JsonObject().apply {
                            add("ok", JsonObject().apply {
                                addProperty("type", "boolean")
                                add("const", JsonPrimitive(true))
                            })
                        })
                        add("required", JsonArray().apply { add("ok") })
                    })
                })
            })
            addProperty("input", "Connection test. Return ok=true.")
        }

        private fun requestBytes(
            input: String,
            model: String,
            kind: ClinicalResponseKind
        ): Int = utf8Size(buildRequestJson(input, model, kind).toString())

        internal fun parseOpenAiEnvelope(
            raw: String,
            expectedModel: String
        ): ClinicalStructuredEnvelope {
            val root = try {
                JsonParser.parseString(raw).asJsonObject
            } catch (_: Exception) {
                throw ClinicalOpenAiException.InvalidResponse()
            }
            val status = if (root.has("status")) {
                root.requiredIdentityString("status", maxCodePoints = 16)
            } else {
                null
            }
            if (status != null && status != "completed") {
                throw ClinicalOpenAiException.Incomplete()
            }
            if (containsRefusal(root)) {
                throw ClinicalOpenAiException.Refusal()
            }
            val responseModel = root.requiredIdentityString(
                "model",
                maxCodePoints = MAX_MODEL_ID_CODE_POINTS
            )
            if (!sameExpectedModel(expectedModel, responseModel)) {
                throw ClinicalAiGatewayException.IdentityMismatch()
            }
            val systemFingerprint = root.optionalIdentityString(
                "system_fingerprint",
                maxCodePoints = MAX_SYSTEM_FINGERPRINT_CODE_POINTS
            )
            val output = extractOutputText(root)
            if (output.isBlank()) {
                throw ClinicalOpenAiException.InvalidResponse()
            }
            return ClinicalStructuredEnvelope(
                output = output,
                model = responseModel,
                systemFingerprint = systemFingerprint
            )
        }

        private fun utf8Size(value: String): Int =
            value.toByteArray(StandardCharsets.UTF_8).size

        private fun sameExpectedModel(expected: String, actual: String): Boolean =
            expected == actual

        private fun JsonObject.requiredIdentityString(
            key: String,
            maxCodePoints: Int
        ): String {
            val element = get(key)
            if (element == null || !element.isJsonPrimitive ||
                !element.asJsonPrimitive.isString
            ) {
                throw ClinicalOpenAiException.InvalidResponse()
            }
            val value = element.asString
            if (value.isEmpty() ||
                value != value.trim() ||
                codePointCount(value) > maxCodePoints
            ) {
                throw ClinicalOpenAiException.InvalidResponse()
            }
            return value
        }

        private fun JsonObject.optionalIdentityString(
            key: String,
            maxCodePoints: Int
        ): String? {
            if (!has(key) || get(key).isJsonNull) return null
            return requiredIdentityString(key, maxCodePoints)
        }

        private fun buildSynthesisInput(
            payload: ClinicalReportPayload,
            rootDigest: ClinicalChunkDigest,
            coverage: ClinicalCoverageLedger,
            rootWireProjection: ClinicalPartitionRootWireProjection
        ): String {
            val bindingHash = ledgerRootBindingHash(
                rootDigestHash = rootDigest.hash,
                coverageLedgerHash = coverage.ledgerHash,
                requestHash = payload.sha256
            )
            val rootReport = JsonParser.parseString(rootDigest.canonicalJson)
                .asJsonObject
                .apply { remove("sourceHashes") }
            val rootReportCanonical = rootReport.toString()
            return JsonObject().apply {
                addProperty("kind", "clinical_map_reduce_synthesis")
                add("original", JsonObject().apply {
                    addProperty("datasetSchemaVersion", payload.dataset.schemaVersion)
                    addProperty("requestHash", payload.sha256)
                })
                add("isfCrSourceCodebook", sourceCodebookJson())
                add("localSummaries", JsonObject().apply {
                    add("summary7d", JsonParser.parseString(rootWireProjection.summary7dJson))
                    add("summary30d", JsonParser.parseString(rootWireProjection.summary30dJson))
                })
                add("currentSnapshot", JsonParser.parseString(rootWireProjection.currentJson))
                add("rootReport", rootReport)
                addProperty(
                    "rootReportHash",
                    ClinicalReportDatasetBuilder.sha256(rootReportCanonical)
                )
                addProperty("rootDigestHash", rootDigest.hash)
                addProperty("ledgerRootBindingHash", bindingHash)
                add("coverageLedger", JsonObject().apply {
                    addProperty("expectedFromTs", coverage.expectedFromTs)
                    addProperty(
                        "expectedThroughTsExclusive",
                        coverage.expectedThroughTsExclusive
                    )
                    add("sourceRows", JsonObject().apply {
                        addProperty("glucose", coverage.sourceRows.glucose)
                        addProperty("insulin", coverage.sourceRows.insulin)
                        addProperty("carbs", coverage.sourceRows.carbs)
                        addProperty("targets", coverage.sourceRows.targets)
                    })
                    addProperty("leafCount", coverage.leafCount)
                    addProperty("ledgerHash", coverage.ledgerHash)
                })
            }.toString()
        }

        private fun ledgerRootBindingHash(
            rootDigestHash: String,
            coverageLedgerHash: String,
            requestHash: String
        ): String = ClinicalReportDatasetBuilder.sha256(
            JsonObject().apply {
                addProperty("schema", "clinical-root-ledger-binding")
                addProperty("version", 1)
                addProperty("rootDigestHash", rootDigestHash)
                addProperty("coverageLedgerHash", coverageLedgerHash)
                addProperty("requestHash", requestHash)
            }.toString()
        )

        private suspend fun filterDataset(
            dataset: ClinicalReportDataset,
            fromTs: Long,
            throughTs: Long,
            includeThrough: Boolean,
            plannedActivityWindow: ClinicalPreparedPeriodWindow
        ): ClinicalReportDataset {
            fun inWindow(ts: Long): Boolean =
                ts >= fromTs && (ts < throughTs || includeThrough && ts == throughTs)

            val filteredDetail = dataset.detail24h.copy(
                glucose = filterChecked(dataset.detail24h.glucose) { inWindow(it.ts) },
                calibratedGlucose = filterChecked(
                    dataset.detail24h.calibratedGlucose
                ) { inWindow(it.ts) },
                therapy = filterChecked(dataset.detail24h.therapy) { inWindow(it.ts) },
                targets = filterChecked(dataset.detail24h.targets) { inWindow(it.ts) },
                forecasts = filterChecked(dataset.detail24h.forecasts) { inWindow(it.ts) },
                telemetry = filterChecked(dataset.detail24h.telemetry) { inWindow(it.ts) }
            )
            val snapshotAt = if (includeThrough) throughTs else throughTs - 1L
            return dataset.copy(
                currentSnapshot = ClinicalReportDatasetBuilder.currentSnapshot(
                    snapshotAt,
                    filteredDetail
                ),
                detail24h = filteredDetail,
                glucose7d = filterChecked(dataset.glucose7d) { inWindow(it.ts) },
                therapy7d = filterChecked(dataset.therapy7d) { inWindow(it.ts) },
                targets7d = filterChecked(dataset.targets7d) { inWindow(it.ts) },
                glucose30d = filterChecked(dataset.glucose30d) { inWindow(it.ts) },
                therapy30d = filterChecked(dataset.therapy30d) { inWindow(it.ts) },
                targets30d = filterChecked(dataset.targets30d) { inWindow(it.ts) },
                plannedActivities = filterChecked(dataset.plannedActivities) { activity ->
                    ClinicalPlannedActivityPeriodPolicy
                        .ownershipTimestamp(activity, plannedActivityWindow)
                        ?.let(::inWindow) == true
                },
                eventSummaries = filterChecked(dataset.eventSummaries) { event ->
                    maxOf(event.startTs, plannedActivityWindow.fromTs)
                        .takeIf {
                            event.startTs <= plannedActivityWindow.throughTs &&
                                event.endTs >= plannedActivityWindow.fromTs
                        }
                        ?.let(::inWindow) == true
                }
            )
        }

        private suspend fun <T> filterChecked(
            rows: List<T>,
            predicate: (T) -> Boolean
        ): List<T> {
            val output = ArrayList<T>(rows.size)
            rows.forEachIndexed { index, row ->
                if (index % CANCELLATION_CHECKPOINT_ROWS == 0) {
                    currentCoroutineContext().ensureActive()
                }
                if (predicate(row)) output += row
            }
            return output
        }

        private fun containsRefusal(root: JsonObject): Boolean {
            val output = root.get("output")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
                ?: return false
            return output.any { item ->
                val content = item.takeIf(JsonElement::isJsonObject)?.asJsonObject
                    ?.get("content")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
                    ?: return@any false
                content.any { part ->
                    val objectPart = part.takeIf(JsonElement::isJsonObject)?.asJsonObject
                        ?: return@any false
                    objectPart.get("type")?.takeIf(JsonElement::isJsonPrimitive)
                        ?.asString == "refusal" || objectPart.has("refusal")
                }
            }
        }

        private fun extractOutputText(root: JsonObject): String {
            root.get("output_text")?.takeIf(JsonElement::isJsonPrimitive)
                ?.asString?.trim()?.takeIf(String::isNotBlank)?.let { return it }
            val output = root.get("output")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
                ?: return ""
            output.forEach { item ->
                val content = item.takeIf(JsonElement::isJsonObject)?.asJsonObject
                    ?.get("content")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
                    ?: return@forEach
                content.forEach { part ->
                    val objectPart = part.takeIf(JsonElement::isJsonObject)?.asJsonObject
                        ?: return@forEach
                    if (objectPart.get("type")?.takeIf(JsonElement::isJsonPrimitive)
                            ?.asString != "output_text"
                    ) {
                        return@forEach
                    }
                    val text = objectPart.get("text") ?: return@forEach
                    when {
                        text.isJsonPrimitive -> text.asString.trim()
                            .takeIf(String::isNotBlank)?.let { return it }
                        text.isJsonObject -> text.asJsonObject.get("value")
                            ?.takeIf(JsonElement::isJsonPrimitive)
                            ?.asString?.trim()?.takeIf(String::isNotBlank)?.let { return it }
                    }
                }
            }
            return ""
        }

        private fun httpFailure(code: Int): ClinicalOpenAiException = when (code) {
            401, 403 -> ClinicalOpenAiException.Unauthorized()
            429 -> ClinicalOpenAiException.RateLimited()
            in 500..599 -> ClinicalOpenAiException.ServerFailure()
            else -> ClinicalOpenAiException.HttpFailure()
        }

        private fun sanitizeTransportFailure(error: IOException): ClinicalOpenAiException =
            if (error is SocketTimeoutException) {
                ClinicalOpenAiException.Timeout()
            } else {
                ClinicalOpenAiException.NetworkFailure()
            }
    }
}

private class OpenAiClinicalStructuredTransport(
    private val endpoint: HttpUrl,
    override val modelId: String
) : ClinicalAiStructuredTransport {
    override val providerId: ClinicalAiProviderId = ClinicalAiProviderId.OPENAI

    override fun requestBody(input: String, kind: ClinicalResponseKind): ByteArray =
        ClinicalOpenAiClient.buildRequestJson(input, modelId, kind)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

    override fun connectionRequestBody(): ByteArray =
        ClinicalOpenAiClient.buildConnectionRequestJson(modelId)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

    override fun request(credential: String, body: ByteArray): Request =
        Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $credential")
            .header("Content-Type", "application/json")
            .post(OneShotJsonRequestBody(body))
            .build()

    override fun parseEnvelope(raw: String): ClinicalStructuredEnvelope =
        ClinicalOpenAiClient.parseOpenAiEnvelope(raw, modelId)
}

internal object ClinicalWireAccounting {
    private val REQUIRED_ROOT_FIELDS = setOf(
        "v", "generatedAt", "zone", "c", "d24",
        "g7", "e7", "t7", "g30", "e30", "t30", "s7", "s30",
        "ev24", "ev7", "ev30"
    )
    private val OPTIONAL_ROOT_FIELDS = setOf("s24", "ep", "pa")
    private val ROW_PATHS = listOf(
        "d24/g",
        "d24/gc",
        "d24/e",
        "d24/t",
        "d24/f",
        "d24/m",
        "g7",
        "e7",
        "t7",
        "g30",
        "e30",
        "t30",
        "pa",
        "ev24",
        "ev7",
        "ev30"
    )

    fun rowCounts(fullDatasetJson: String): Map<String, Int> =
        rows(parseDataset(fullDatasetJson)).mapValues { (_, rows) -> rows.size }

    fun verify(
        fullDatasetJson: String,
        chunkCanonicalJsons: List<String>
    ) {
        val expected = rows(parseDataset(fullDatasetJson))
        val actual = ROW_PATHS.associateWith { mutableMapOf<String, Int>() }
        chunkCanonicalJsons.forEach { chunkJson ->
            val dataset = try {
                JsonParser.parseString(chunkJson).asJsonObject
                    .getAsJsonObject("dataset")
            } catch (_: Exception) {
                invalid()
            }
            validateDataset(dataset)
            rows(dataset).forEach { (path, values) ->
                val counts = actual.getValue(path)
                values.forEach { row ->
                    val next = try {
                        Math.addExact(counts[row] ?: 0, 1)
                    } catch (_: ArithmeticException) {
                        invalid()
                    }
                    counts[row] = next
                }
            }
        }
        ROW_PATHS.forEach { path ->
            val expectedCounts = expected.getValue(path).groupingBy { it }.eachCount()
            if (expectedCounts != actual.getValue(path)) invalid()
        }
    }

    private fun parseDataset(json: String): JsonObject {
        val dataset = try {
            JsonParser.parseString(json).asJsonObject
        } catch (_: Exception) {
            invalid()
        }
        validateDataset(dataset)
        return dataset
    }

    private fun validateDataset(dataset: JsonObject) {
        val fields = dataset.keySet().toSet()
        if (!fields.containsAll(REQUIRED_ROOT_FIELDS) ||
            !fields.all { it in REQUIRED_ROOT_FIELDS || it in OPTIONAL_ROOT_FIELDS } ||
            fields.contains("ep") != fields.contains("pa")
        ) {
            invalid()
        }
        val current = dataset.get("c")
            ?.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
            ?: invalid()
        if (!ClinicalCurrentWireSchema.isValidWireObject(current)) invalid()
    }

    private fun rows(dataset: JsonObject): Map<String, List<String>> =
        ROW_PATHS.associateWith { path ->
            if (path == "pa" && !dataset.has(path)) {
                return@associateWith emptyList()
            }
            val array = try {
                if (path.startsWith("d24/")) {
                    dataset.getAsJsonObject("d24").getAsJsonArray(path.substringAfter('/'))
                } else {
                    dataset.getAsJsonArray(path)
                }
            } catch (_: Exception) {
                invalid()
            }
            array.map { row ->
                if (!row.isJsonArray && !row.isJsonObject) invalid()
                row.toString()
            }
        }

    private fun invalid(): Nothing = throw ClinicalOpenAiException.InvalidInput()
}

internal object ClinicalChunkReportSchemaParser {
    private val ROOT_FIELDS = setOf("dataQuality", "findings")

    fun parse(raw: String): ClinicalChunkReport {
        if (raw.toByteArray(StandardCharsets.UTF_8).size >
            ClinicalOpenAiClient.MAX_CHUNK_REPORT_BYTES
        ) {
            invalid()
        }
        val root = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (_: Exception) {
            invalid()
        }
        ClinicalReportSchemaParser.rejectForbiddenFields(root)
        requireFields(root, ROOT_FIELDS)
        val findings = objectList(root, "findings", 1).map { finding ->
            ClinicalReportSchemaParser.parseFinding(finding)
        }
        return ClinicalChunkReport(
            dataQuality = enumList<ClinicalDataQualityFlag>(
                root,
                "dataQuality",
                1
            ),
            findings = findings
        )
    }

    private fun requireFields(value: JsonObject, expected: Set<String>) {
        if (value.keySet() != expected) invalid()
    }

    private inline fun <reified T : Enum<T>> enumList(
        value: JsonObject,
        key: String,
        maxItems: Int
    ): List<T> {
        val array = value.get(key)?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: invalid()
        if (array.size() > maxItems) invalid()
        val values = array.map { item -> requiredEnum<T>(item) }
        if (values.distinct().size != values.size ||
            hasExclusiveEnumContradiction(values)
        ) {
            invalid()
        }
        return values
    }

    private fun objectList(
        value: JsonObject,
        key: String,
        maxItems: Int
    ): List<JsonObject> {
        val array = value.get(key)?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: invalid()
        if (array.size() > maxItems) invalid()
        return array.map { item ->
            item.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: invalid()
        }
    }

    private inline fun <reified T : Enum<T>> requiredEnum(element: JsonElement): T {
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) invalid()
        val raw = element.asString
        if (raw.isEmpty() || codePointCount(raw) > 64) invalid()
        return enumValues<T>().singleOrNull { it.name == raw } ?: invalid()
    }

    private fun invalid(): Nothing = throw ClinicalOpenAiException.InvalidResponse()
}

internal object ClinicalReportSchemaParser {
    private val ROOT_FIELDS = setOf(
        "summary7dStatus",
        "summary30dStatus",
        "dataQuality",
        "patterns",
        "safetyObservations",
        "recommendations",
        "careTeamQuestions"
    )
    private val FINDING_FIELDS = setOf(
        "topic",
        "period",
        "direction",
        "confidence",
        "timeBand",
        "evidenceMetric",
        "evidenceValue"
    )
    private val RECOMMENDATION_FIELDS = setOf(
        "careTeamDiscussionTopic",
        "priority",
        "evidenceFindingIndices",
        "period"
    )

    fun parse(raw: String): ClinicalAdvisoryReport {
        if (raw.toByteArray(StandardCharsets.UTF_8).size >
            ClinicalOpenAiClient.MAX_SCHEMA_RESPONSE_BYTES
        ) {
            invalid()
        }
        val root = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (_: Exception) {
            invalid()
        }
        rejectForbiddenKeys(root)
        requireFields(root, ROOT_FIELDS)

        val patterns = objectList(root, "patterns", ClinicalOpenAiClient.MAX_LIST_ITEMS).map { item ->
            parseFinding(item)
        }
        val recommendations = objectList(
            root,
            "recommendations",
            ClinicalOpenAiClient.MAX_LIST_ITEMS
        ).map { item ->
            requireFields(item, RECOMMENDATION_FIELDS)
            val evidenceFindingIndices = integerList(
                item,
                "evidenceFindingIndices",
                ClinicalOpenAiClient.MAX_LIST_ITEMS,
                maxValue = patterns.lastIndex
            )
            ClinicalRecommendation(
                careTeamDiscussionTopic = requiredEnum<ClinicalCareTeamDiscussionTopic>(
                    item,
                    "careTeamDiscussionTopic"
                ),
                priority = requiredEnum<ClinicalAdvisoryPriority>(item, "priority"),
                evidenceFindingIndices = evidenceFindingIndices,
                period = requiredEnum<ClinicalEvidencePeriod>(
                    item,
                    "period"
                )
            )
        }
        return ClinicalAdvisoryReport(
            summary7dStatus = requiredEnum(root, "summary7dStatus"),
            summary30dStatus = requiredEnum(root, "summary30dStatus"),
            dataQuality = enumList(
                root,
                "dataQuality",
                ClinicalOpenAiClient.MAX_LIST_ITEMS
            ),
            patterns = patterns,
            safetyObservations = enumList(
                root,
                "safetyObservations",
                ClinicalOpenAiClient.MAX_LIST_ITEMS
            ),
            recommendations = recommendations,
            careTeamQuestions = enumList(
                root,
                "careTeamQuestions",
                ClinicalOpenAiClient.MAX_LIST_ITEMS
            )
        )
    }

    internal fun rejectForbiddenFields(root: JsonElement) {
        rejectForbiddenKeys(root)
    }

    internal fun parseFinding(item: JsonObject): ClinicalFinding {
        requireFields(item, FINDING_FIELDS)
        val evidenceMetric = requiredEnum<ClinicalEvidenceMetric>(item, "evidenceMetric")
        return ClinicalFinding(
            topic = requiredEnum(item, "topic"),
            period = requiredEnum(item, "period"),
            direction = requiredEnum(item, "direction"),
            confidence = requiredEnum(item, "confidence"),
            timeBand = requiredEnum(item, "timeBand"),
            evidenceMetric = evidenceMetric,
            evidenceValue = requiredEvidenceValue(item, "evidenceValue", evidenceMetric)
        )
    }

    private fun requireFields(value: JsonObject, expected: Set<String>) {
        if (value.keySet() != expected) invalid()
    }

    private inline fun <reified T : Enum<T>> requiredEnum(
        value: JsonObject,
        key: String
    ): T {
        val element = value.get(key)
        if (element == null || !element.isJsonPrimitive ||
            !element.asJsonPrimitive.isString
        ) {
            invalid()
        }
        val raw = element.asString
        if (raw.isEmpty() || codePointCount(raw) > 64) invalid()
        return enumValues<T>().singleOrNull { it.name == raw } ?: invalid()
    }

    private fun requiredEvidenceValue(
        value: JsonObject,
        key: String,
        metric: ClinicalEvidenceMetric
    ): Double {
        val element = value.get(key)
        if (element == null || !element.isJsonPrimitive ||
            !element.asJsonPrimitive.isNumber
        ) {
            invalid()
        }
        val number = try {
            element.asDouble
        } catch (_: Exception) {
            invalid()
        }
        if (!ClinicalEvidenceValuePolicy.isValid(metric, number)) invalid()
        return number
    }

    private fun integerList(
        value: JsonObject,
        key: String,
        maxItems: Int,
        maxValue: Int
    ): List<Int> {
        val array = value.get(key)?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: invalid()
        if (array.size() !in 1..maxItems) invalid()
        val values = array.map { item ->
            if (!item.isJsonPrimitive || !item.asJsonPrimitive.isNumber) invalid()
            val raw = item.toString()
            if (!raw.matches(Regex("""0|[1-9]\d*"""))) invalid()
            val parsed = raw.toIntOrNull() ?: invalid()
            if (parsed !in 0..maxValue) invalid()
            parsed
        }
        if (values.distinct().size != values.size) invalid()
        return values
    }

    private inline fun <reified T : Enum<T>> enumList(
        value: JsonObject,
        key: String,
        maxItems: Int
    ): List<T> {
        val array = value.get(key)?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: invalid()
        if (array.size() > maxItems) invalid()
        val values = array.map { item ->
            if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) invalid()
            val raw = item.asString
            if (raw.isEmpty() || codePointCount(raw) > 64) invalid()
            enumValues<T>().singleOrNull { it.name == raw } ?: invalid()
        }
        if (values.distinct().size != values.size ||
            hasExclusiveEnumContradiction(values)
        ) {
            invalid()
        }
        return values
    }

    private fun objectList(
        value: JsonObject,
        key: String,
        maxItems: Int
    ): List<JsonObject> {
        val array = value.get(key)?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: invalid()
        if (array.size() > maxItems) invalid()
        return array.map { item ->
            item.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: invalid()
        }
    }

    private fun rejectForbiddenKeys(node: JsonElement) {
        when {
            node.isJsonObject -> node.asJsonObject.entrySet().forEach { (key, child) ->
                if (forbiddenCommandKey(key)) invalid()
                if (child.isJsonPrimitive &&
                    child.asJsonPrimitive.isString &&
                    containsDirectTherapyCommand(child.asString)
                ) {
                    invalid()
                }
                rejectForbiddenKeys(child)
            }
            node.isJsonArray -> node.asJsonArray.forEach { child ->
                if (child.isJsonPrimitive &&
                    child.asJsonPrimitive.isString &&
                    containsDirectTherapyCommand(child.asString)
                ) {
                    invalid()
                }
                rejectForbiddenKeys(child)
            }
        }
    }

    private fun containsDirectTherapyCommand(value: String): Boolean =
        DIRECT_THERAPY_COMMAND_PATTERNS.any { it.containsMatchIn(value) }

    private fun forbiddenCommandKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.US).filter(Char::isLetterOrDigit)
        return normalized in FORBIDDEN_COMMAND_KEY_ALIASES ||
            normalized.contains("command") ||
            normalized.contains("toolcall") ||
            normalized.contains("functioncall") ||
            normalized == "tool" ||
            normalized == "tools" ||
            normalized == "function" ||
            normalized == "functions" ||
            normalized == "action" ||
            normalized == "actions" ||
            normalized.contains("calibrat") ||
            normalized.contains("targettoset") ||
            normalized.contains("targetmmol") ||
            normalized.contains("targetvalue") ||
            normalized.contains("insulindose") ||
            normalized.contains("insulinunit") ||
            normalized.contains("insulinamount") ||
            normalized.contains("carbstoadd") ||
            normalized.contains("carbgram") ||
            normalized.contains("carbdose") ||
            normalized.contains("carbamount")
    }

    private fun invalid(): Nothing = throw ClinicalOpenAiException.InvalidResponse()

    private val DIRECT_THERAPY_COMMAND_PATTERNS = listOf(
        Regex(
            """\b(?:increase|decrease|raise|lower)\s+(?:the\s+)?basal\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:suspend|resume|stop|restart)\s+(?:the\s+)?pump\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:take|inject|administer|deliver|give|bolus)\s+(?:\S+\s+){0,3}""" +
                """\d+(?:[.,]\d+)?\s*(?:u|unit|units)\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:add|eat|consume|take)\s+(?:\S+\s+){0,3}""" +
                """\d+(?:[.,]\d+)?\s*(?:g|gram|grams)\s+(?:of\s+)?carb""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\bset\s+(?:the\s+)?target(?:\s+(?:to|at))?\s+\d+(?:[.,]\d+)?""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """(?:^|[.!?]\s*)calibrate(?:\s+(?:now|immediately))?\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:treat|correct)\s+(?:the\s+)?(?:low|hypoglyc(?:emia|aemia))""" +
                """\s+(?:now|immediately)\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:call\s+emergency\s+services|seek\s+urgent\s+care|""" +
                """go\s+to\s+(?:the\s+)?emergency\s+(?:department|room))""" +
                """\s+(?:now|immediately)\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:введите|введи|уколите|уколи)\s+(?:\S+\s+){0,3}""" +
                """\d+(?:[.,]\d+)?\s*(?:ед|ед\.|единиц)""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:сделайте|сделай)\s+болюс\s+(?:\S+\s+){0,3}""" +
                """\d+(?:[.,]\d+)?\s*(?:ед|ед\.|единиц)""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:примите|прими|добавьте|добавь|съешьте|съешь)\s+""" +
                """(?:\S+\s+){0,3}\d+(?:[.,]\d+)?\s*(?:г|грамм\w*)\s+углевод""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:установите|установи|задайте|задай)\s+(?:\S+\s+){0,3}""" +
                """(?:цель|таргет)\s+(?:\S+\s+){0,2}\d+(?:[.,]\d+)?""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:сделайте|сделай|выполните|выполни)\s+калибров\w*""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:вызовите|вызови)\s+скорую(?:\s+помощь)?\s+(?:сейчас|немедленно)""",
            RegexOption.IGNORE_CASE
        )
    )

    private val FORBIDDEN_COMMAND_KEY_ALIASES = setOf(
        "bolusunits",
        "suggestedbolus",
        "recommendedbolus",
        "unitsofinsulin",
        "carbohydrategrams",
        "carbohydratestoadd",
        "gramsofcarbs",
        "suggestedcarbs",
        "recommendedcarbs",
        "glucosetarget",
        "newtarget",
        "suggestedtarget",
        "recommendedtarget",
        "requestedtarget"
    )
}
