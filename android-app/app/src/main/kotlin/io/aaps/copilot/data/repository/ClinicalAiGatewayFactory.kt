package io.aaps.copilot.data.repository

import io.aaps.copilot.config.ClinicalAiProviderConfig
import io.aaps.copilot.config.ClinicalAiProviderId
import java.util.Collections

fun interface ClinicalAiGatewayBuilder {
    fun build(config: ClinicalAiProviderConfig): ClinicalAiGateway
}

sealed class ClinicalAiGatewayException(message: String) : Exception(message) {
    class UnsupportedProvider : ClinicalAiGatewayException(
        "Clinical AI provider is not available"
    )

    class IdentityMismatch : ClinicalAiGatewayException(
        "Clinical AI provider identity validation failed"
    )
}

class ClinicalAiGatewayFactory(
    builders: Map<ClinicalAiProviderId, ClinicalAiGatewayBuilder> = defaultBuilders()
) {
    private val builders = Collections.unmodifiableMap(HashMap(builders))

    fun create(config: ClinicalAiProviderConfig): ClinicalAiGateway {
        val builder = builders[config.providerId]
            ?: throw ClinicalAiGatewayException.UnsupportedProvider()
        val gateway = builder.build(config)
        if (gateway.providerId != config.providerId || gateway.modelId != config.modelId) {
            throw ClinicalAiGatewayException.IdentityMismatch()
        }
        return IdentityValidatingClinicalAiGateway(config, gateway)
    }

    private class IdentityValidatingClinicalAiGateway(
        private val config: ClinicalAiProviderConfig,
        private val delegate: ClinicalAiGateway
    ) : ClinicalAiGateway {
        override val providerId: ClinicalAiProviderId = config.providerId
        override val modelId: String = config.modelId

        override fun capabilities(): ClinicalAiCapabilities = delegate.capabilities()

        override suspend fun testConnection(credential: String): ClinicalAiConnectionTest {
            val result = delegate.testConnection(credential)
            return if (result.providerId == providerId && result.modelId == modelId) {
                result
            } else {
                ClinicalAiConnectionTest(
                    providerId = providerId,
                    modelId = modelId,
                    status = ClinicalAiConnectionStatus.IDENTITY_MISMATCH
                )
            }
        }

        override suspend fun analyze(
            payload: ClinicalReportPayload,
            credential: suspend () -> String,
            progress: ClinicalOpenAiProgressCallback
        ): ClinicalOpenAiResult {
            val result = delegate.analyze(payload, credential, progress)
            val metadata = result.metadata
            if (
                metadata.providerId != providerId ||
                metadata.requestedProviderId != providerId ||
                metadata.model != modelId ||
                metadata.requestedModel != modelId
            ) {
                throw ClinicalAiGatewayException.IdentityMismatch()
            }
            return result
        }

        override suspend fun analyzeAlert(
            context: AlertAiCanonicalContext,
            credential: suspend () -> String,
            networkLease: AlertAiValidatedNetworkLease
        ): AlertAiGatewayResult {
            val result = delegate.analyzeAlert(context, credential, networkLease)
            if (result.providerId != providerId || result.modelId != modelId) {
                throw ClinicalAiGatewayException.IdentityMismatch()
            }
            return result
        }
    }

    private companion object {
        fun defaultBuilders(): Map<ClinicalAiProviderId, ClinicalAiGatewayBuilder> =
            mapOf(
                ClinicalAiProviderId.OPENAI to ClinicalAiGatewayBuilder { config ->
                    ClinicalOpenAiClient(modelId = config.modelId)
                },
                ClinicalAiProviderId.ANTHROPIC to ClinicalAiGatewayBuilder { config ->
                    AnthropicClinicalAiGateway(modelId = config.modelId)
                }
            )
    }
}
