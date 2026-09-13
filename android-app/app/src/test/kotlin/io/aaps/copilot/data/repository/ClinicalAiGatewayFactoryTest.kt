package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.ClinicalAiProviderConfig
import io.aaps.copilot.config.ClinicalAiProviderId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class ClinicalAiGatewayFactoryTest {

    @Test
    fun defaultFactoryConstructsAnthropicWithoutResolvingCredentials() {
        val config = ClinicalAiProviderConfig(
            providerId = ClinicalAiProviderId.ANTHROPIC,
            modelId = "claude-sonnet-5"
        )

        val gateway = ClinicalAiGatewayFactory().create(config)

        assertThat(gateway.providerId).isEqualTo(config.providerId)
        assertThat(gateway.modelId).isEqualTo(config.modelId)
    }

    @Test
    fun factorySelectsInjectedBuilderAndPassesEffectiveConfig() {
        val expected = ClinicalAiProviderConfig(
            providerId = ClinicalAiProviderId.ANTHROPIC,
            modelId = "claude-sonnet-5"
        )
        var received: ClinicalAiProviderConfig? = null
        val factory = ClinicalAiGatewayFactory(
            mapOf(
                ClinicalAiProviderId.ANTHROPIC to ClinicalAiGatewayBuilder { config ->
                    received = config
                    FakeGateway(config.providerId, config.modelId)
                }
            )
        )

        val gateway = factory.create(expected)

        assertThat(received).isEqualTo(expected)
        assertThat(gateway.providerId).isEqualTo(ClinicalAiProviderId.ANTHROPIC)
        assertThat(gateway.modelId).isEqualTo("claude-sonnet-5")
    }

    @Test
    fun missingProviderBuilderFailsWithoutFallingBackToOpenAi() {
        val openAiBuilds = AtomicInteger()
        val factory = ClinicalAiGatewayFactory(
            mapOf(
                ClinicalAiProviderId.OPENAI to ClinicalAiGatewayBuilder { config ->
                    openAiBuilds.incrementAndGet()
                    FakeGateway(config.providerId, config.modelId)
                }
            )
        )

        val failure = assertThrows(ClinicalAiGatewayException.UnsupportedProvider::class.java) {
            factory.create(
                ClinicalAiProviderConfig(
                    providerId = ClinicalAiProviderId.GEMINI,
                    modelId = "gemini-3.6-flash"
                )
            )
        }

        assertThat(openAiBuilds.get()).isEqualTo(0)
        assertSanitized(failure)
    }

    @Test
    fun factoryRejectsGatewayProviderOrModelMismatchBeforeAnyRequest() {
        val requests = AtomicInteger()
        val providerMismatch = ClinicalAiGatewayFactory(
            mapOf(
                ClinicalAiProviderId.GEMINI to ClinicalAiGatewayBuilder {
                    FakeGateway(
                        providerId = ClinicalAiProviderId.OPENAI,
                        modelId = it.modelId,
                        analyzeCalls = requests
                    )
                }
            )
        )
        val modelMismatch = ClinicalAiGatewayFactory(
            mapOf(
                ClinicalAiProviderId.GEMINI to ClinicalAiGatewayBuilder {
                    FakeGateway(
                        providerId = it.providerId,
                        modelId = "wrong-model",
                        analyzeCalls = requests
                    )
                }
            )
        )
        val config = ClinicalAiProviderConfig(
            providerId = ClinicalAiProviderId.GEMINI,
            modelId = "gemini-3.6-flash"
        )

        val providerFailure = assertThrows(
            ClinicalAiGatewayException.IdentityMismatch::class.java
        ) {
            providerMismatch.create(config)
        }
        val modelFailure = assertThrows(
            ClinicalAiGatewayException.IdentityMismatch::class.java
        ) {
            modelMismatch.create(config)
        }

        assertThat(requests.get()).isEqualTo(0)
        assertSanitized(providerFailure)
        assertSanitized(modelFailure)
    }

    @Test
    fun gatewayRejectsNormalizedResponseWithDifferentProviderOrModel() = runTest {
        val config = ClinicalAiProviderConfig(
            providerId = ClinicalAiProviderId.ANTHROPIC,
            modelId = "claude-sonnet-5"
        )
        val wrongProvider = result(
            providerId = ClinicalAiProviderId.GEMINI,
            requestedProviderId = config.providerId,
            modelId = config.modelId,
            requestedModelId = config.modelId
        )
        val wrongModel = result(
            providerId = config.providerId,
            requestedProviderId = config.providerId,
            modelId = "claude-opus-5",
            requestedModelId = config.modelId
        )
        val providerGateway = factoryFor(config, wrongProvider).create(config)
        val modelGateway = factoryFor(config, wrongModel).create(config)

        val providerFailure = runCatching {
            providerGateway.analyze(payload(), { "credential-provider" }, NO_PROGRESS)
        }.exceptionOrNull()
        val modelFailure = runCatching {
            modelGateway.analyze(payload(), { "credential-model" }, NO_PROGRESS)
        }.exceptionOrNull()

        assertThat(providerFailure)
            .isInstanceOf(ClinicalAiGatewayException.IdentityMismatch::class.java)
        assertThat(modelFailure)
            .isInstanceOf(ClinicalAiGatewayException.IdentityMismatch::class.java)
        assertSanitized(providerFailure)
        assertSanitized(modelFailure)
    }

    @Test
    fun alertGatewayRejectsProviderOrModelMismatchWithoutFallback() = runTest {
        val config = ClinicalAiProviderConfig(
            providerId = ClinicalAiProviderId.GEMINI,
            modelId = "gemini-3.6-flash"
        )
        val wrong = ClinicalAiGatewayFactory(
            mapOf(
                config.providerId to ClinicalAiGatewayBuilder {
                    FakeGateway(
                        providerId = config.providerId,
                        modelId = config.modelId,
                        alertResult = AlertAiGatewayResult(
                            providerId = ClinicalAiProviderId.OPENAI,
                            modelId = config.modelId,
                            resultJson = "{}"
                        )
                    )
                }
            )
        ).create(config)

        val failure = runCatching {
            wrong.analyzeAlert(
                alertContextFixture(),
                { "credential-alert" },
                alertAiTestNetworkLease()
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ClinicalAiGatewayException.IdentityMismatch::class.java)
        assertSanitized(failure)
    }

    @Test
    fun gatewayPreservesCancellationAndFatalIdentityFailures() = runTest {
        val config = ClinicalAiProviderConfig(
            providerId = ClinicalAiProviderId.GEMINI,
            modelId = "gemini-3.6-flash"
        )
        val cancellationGateway = factoryFor(
            config,
            analyzeFailure = CancellationException("private-cancellation")
        ).create(config)
        val fatalGateway = factoryFor(
            config,
            analyzeFailure = LinkageError("private-linkage")
        ).create(config)

        val cancellation = runCatching {
            cancellationGateway.analyze(payload(), { "credential-cancel" }, NO_PROGRESS)
        }.exceptionOrNull()
        val fatal = runCatching {
            fatalGateway.analyze(payload(), { "credential-fatal" }, NO_PROGRESS)
        }.exceptionOrNull()

        assertThat(cancellation).isInstanceOf(CancellationException::class.java)
        assertThat(fatal).isInstanceOf(LinkageError::class.java)
    }

    @Test
    fun connectionIdentityMismatchReturnsSanitizedStatus() = runTest {
        val config = ClinicalAiProviderConfig(
            providerId = ClinicalAiProviderId.GEMINI,
            modelId = "gemini-3.6-flash"
        )
        val delegate = FakeGateway(
            providerId = config.providerId,
            modelId = config.modelId,
            connectionTest = ClinicalAiConnectionTest(
                providerId = ClinicalAiProviderId.OPENAI,
                modelId = config.modelId,
                status = ClinicalAiConnectionStatus.SUCCESS
            )
        )
        val gateway = ClinicalAiGatewayFactory(
            mapOf(config.providerId to ClinicalAiGatewayBuilder { delegate })
        ).create(config)

        val result = gateway.testConnection("credential-not-in-status")

        assertThat(result.providerId).isEqualTo(config.providerId)
        assertThat(result.modelId).isEqualTo(config.modelId)
        assertThat(result.status).isEqualTo(ClinicalAiConnectionStatus.IDENTITY_MISMATCH)
        assertThat(result.toString()).doesNotContain("credential-not-in-status")
    }

    @Test
    fun capabilityAndConnectionContractsRejectUnboundedValues() {
        assertThrows(IllegalArgumentException::class.java) {
            ClinicalAiCapabilities(
                maxRequestBytes = Int.MAX_VALUE,
                maxResponseBytes = 64 * 1_024,
                maxOutputTokens = 25_000,
                supportsStrictStructuredOutput = true
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClinicalAiConnectionTest(
                providerId = ClinicalAiProviderId.OPENAI,
                modelId = ClinicalOpenAiClient.DEFAULT_MODEL,
                status = ClinicalAiConnectionStatus.SUCCESS,
                latencyMs = Long.MAX_VALUE
            )
        }
    }

    private fun factoryFor(
        config: ClinicalAiProviderConfig,
        result: ClinicalOpenAiResult = result(
            providerId = config.providerId,
            requestedProviderId = config.providerId,
            modelId = config.modelId,
            requestedModelId = config.modelId
        ),
        analyzeFailure: Throwable? = null
    ) = ClinicalAiGatewayFactory(
        mapOf(
            config.providerId to ClinicalAiGatewayBuilder {
                FakeGateway(
                    providerId = config.providerId,
                    modelId = config.modelId,
                    result = result,
                    analyzeFailure = analyzeFailure
                )
            }
        )
    )

    private inner class FakeGateway(
        override val providerId: ClinicalAiProviderId,
        override val modelId: String,
        private val result: ClinicalOpenAiResult = result(
            providerId = providerId,
            requestedProviderId = providerId,
            modelId = modelId,
            requestedModelId = modelId
        ),
        private val connectionTest: ClinicalAiConnectionTest = ClinicalAiConnectionTest(
            providerId = providerId,
            modelId = modelId,
            status = ClinicalAiConnectionStatus.SUCCESS
        ),
        private val analyzeCalls: AtomicInteger = AtomicInteger(),
        private val analyzeFailure: Throwable? = null,
        private val alertResult: AlertAiGatewayResult = AlertAiGatewayResult(
            providerId = providerId,
            modelId = modelId,
            resultJson = VALID_ALERT_RESULT_JSON
        )
    ) : ClinicalAiGateway {
        override fun capabilities() = ClinicalAiCapabilities(
            maxRequestBytes = 512 * 1_024,
            maxResponseBytes = 128 * 1_024,
            maxOutputTokens = 25_000,
            supportsStrictStructuredOutput = true
        )

        override suspend fun testConnection(credential: String): ClinicalAiConnectionTest =
            connectionTest

        override suspend fun analyze(
            payload: ClinicalReportPayload,
            credential: suspend () -> String,
            progress: ClinicalOpenAiProgressCallback
        ): ClinicalOpenAiResult {
            analyzeCalls.incrementAndGet()
            analyzeFailure?.let { throw it }
            return result
        }

        override suspend fun analyzeAlert(
            context: AlertAiCanonicalContext,
            credential: suspend () -> String,
            networkLease: AlertAiValidatedNetworkLease
        ): AlertAiGatewayResult = alertResult
    }

    private fun result(
        providerId: ClinicalAiProviderId,
        requestedProviderId: ClinicalAiProviderId,
        modelId: String,
        requestedModelId: String
    ) = ClinicalOpenAiResult(
        report = ClinicalAdvisoryReport(
            summary7dStatus = ClinicalSummaryStatus.STABLE,
            summary30dStatus = ClinicalSummaryStatus.STABLE,
            dataQuality = listOf(ClinicalDataQualityFlag.COMPLETE),
            patterns = emptyList(),
            safetyObservations = listOf(ClinicalSafetyObservation.NONE_IDENTIFIED),
            recommendations = emptyList(),
            careTeamQuestions = emptyList()
        ),
        metadata = ClinicalOpenAiMetadata(
            model = modelId,
            requestedModel = requestedModelId,
            systemFingerprint = null,
            schemaName = ClinicalOpenAiClient.SCHEMA_NAME,
            schemaVersion = ClinicalOpenAiClient.SCHEMA_VERSION,
            datasetSchemaVersion = 1,
            requestHash = "a".repeat(64),
            chunkCount = 1,
            usedSynthesis = false,
            providerId = providerId,
            requestedProviderId = requestedProviderId
        )
    )

    private fun payload(): ClinicalReportPayload {
        val quality = ClinicalDataQuality(
            expectedBuckets = 0,
            coveredBuckets = 0,
            missingBuckets = 0,
            maxGapMinutes = null
        )
        fun summary(days: Int) = ClinicalPeriodSummary(
            days = days,
            fromTs = 1L,
            throughTs = 1L,
            coveragePct = 0.0,
            meanMmol = null,
            medianMmol = null,
            coefficientOfVariationPct = null,
            timeBelow4Pct = null,
            timeInRangePct = null,
            timeAboveRangePct = null,
            totalInsulinU = 0.0,
            totalCarbsG = 0.0,
            meanTargetMmol = null,
            weekdayPattern = emptyList(),
            weekendPattern = emptyList(),
            quality = quality
        )
        return ClinicalReportPayload(
            dataset = ClinicalReportDataset(
                schemaVersion = 1,
                generatedAt = 1L,
                zoneId = "UTC",
                detail24h = ClinicalDetailWindow(
                    fromTs = 1L,
                    throughTs = 1L,
                    glucose = emptyList(),
                    calibratedGlucose = emptyList(),
                    therapy = emptyList(),
                    targets = emptyList(),
                    forecasts = emptyList(),
                    telemetry = emptyList()
                ),
                glucose7d = emptyList(),
                therapy7d = emptyList(),
                targets7d = emptyList(),
                glucose30d = emptyList(),
                therapy30d = emptyList(),
                targets30d = emptyList(),
                summary7d = summary(7),
                summary30d = summary(30)
            ),
            compactJson = "{}",
            sha256 = "a".repeat(64)
        )
    }

    private fun assertSanitized(failure: Throwable?) {
        assertThat(failure).isNotNull()
        val rendered = failure.toString()
        listOf(
            "credential-provider",
            "credential-model",
            "private-cancellation",
            "private-linkage",
            "claude-sonnet-5",
            "gemini-3.6-flash"
        ).forEach { secret -> assertThat(rendered).doesNotContain(secret) }
    }

    private companion object {
        val NO_PROGRESS = ClinicalOpenAiProgressCallback {}
    }
}
