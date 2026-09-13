package io.aaps.copilot.data.repository

import io.aaps.copilot.config.ClinicalAiProviderId

data class ClinicalAiCapabilities(
    val maxRequestBytes: Int,
    val maxResponseBytes: Int,
    val maxOutputTokens: Int,
    val supportsStrictStructuredOutput: Boolean
) {
    init {
        require(maxRequestBytes in MIN_REQUEST_BYTES..MAX_REQUEST_BYTES)
        require(maxResponseBytes in MIN_RESPONSE_BYTES..MAX_RESPONSE_BYTES)
        require(maxOutputTokens in 1..MAX_OUTPUT_TOKENS)
    }

    private companion object {
        const val MIN_REQUEST_BYTES = 1_024
        const val MAX_REQUEST_BYTES = 4 * 1_024 * 1_024
        const val MIN_RESPONSE_BYTES = 1_024
        const val MAX_RESPONSE_BYTES = 1 * 1_024 * 1_024
        const val MAX_OUTPUT_TOKENS = 100_000
    }
}

enum class ClinicalAiConnectionStatus {
    SUCCESS,
    CREDENTIAL_UNAVAILABLE,
    UNAUTHORIZED,
    RATE_LIMITED,
    SERVICE_UNAVAILABLE,
    NETWORK_UNAVAILABLE,
    INVALID_RESPONSE,
    IDENTITY_MISMATCH
}

data class ClinicalAiConnectionTest(
    val providerId: ClinicalAiProviderId,
    val modelId: String,
    val status: ClinicalAiConnectionStatus,
    val latencyMs: Long? = null
) {
    init {
        require(modelId.isNotBlank())
        require(latencyMs == null || latencyMs in 0L..MAX_LATENCY_MS)
    }

    val successful: Boolean
        get() = status == ClinicalAiConnectionStatus.SUCCESS

    private companion object {
        const val MAX_LATENCY_MS = 120_000L
    }
}

data class AlertAiGatewayResult(
    val providerId: ClinicalAiProviderId,
    val modelId: String,
    val resultJson: String
)

interface ClinicalAiGateway {
    val providerId: ClinicalAiProviderId
    val modelId: String

    fun capabilities(): ClinicalAiCapabilities

    suspend fun testConnection(credential: String): ClinicalAiConnectionTest

    suspend fun analyze(
        payload: ClinicalReportPayload,
        credential: suspend () -> String,
        progress: ClinicalOpenAiProgressCallback = ClinicalOpenAiProgressCallback {}
    ): ClinicalOpenAiResult

    suspend fun analyzeAlert(
        context: AlertAiCanonicalContext,
        credential: suspend () -> String,
        networkLease: AlertAiValidatedNetworkLease
    ): AlertAiGatewayResult
}
