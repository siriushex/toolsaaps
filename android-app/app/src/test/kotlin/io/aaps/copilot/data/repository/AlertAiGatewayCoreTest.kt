package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.domain.alerts.AlertCauseDirection
import io.aaps.copilot.domain.alerts.AlertCauseSnapshot
import io.aaps.copilot.domain.alerts.AlertCauseSnapshotCodec
import java.util.concurrent.atomic.AtomicInteger
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.Request
import okio.ByteString.Companion.toByteString
import org.junit.Test

class AlertAiGatewayCoreTest {
    @Test
    fun alertOperationUsesDedicatedKindAndReturnsCanonicalTypedResult() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val transport = RecordingTransport(
                endpoint = server.url("/alert"),
                output = """{"adviceCode":"DATA_INCOMPLETE","evidenceCodes":["SENSOR_BLOCKED"],"confidence":"HIGH","primaryCauseCode":"SENSOR_QUALITY","schemaVersion":1}"""
            )
            val gateway = ClinicalOpenAiClient.forTransport(transport)
            val canonicalContext = alertCanonicalGoldenFixture()

            val result = gateway.analyzeAlert(
                canonicalContext,
                { "synthetic-credential" },
                alertAiTestNetworkLease()
            )

            assertThat(transport.kind).isEqualTo(ClinicalResponseKind.ALERT_CAUSE_V1)
            assertThat(transport.input).isEqualTo(canonicalContext.canonicalJson)
            assertThat(transport.input!!.toByteArray(Charsets.UTF_8))
                .isEqualTo(canonicalContext.canonicalBytes.toByteArray())
            assertThat(
                MessageDigest.getInstance("SHA-256")
                    .digest(transport.input!!.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            ).isEqualTo(canonicalContext.sha256)
            assertThat(transport.requests.get()).isEqualTo(1)
            assertThat(result.providerId).isEqualTo(ClinicalAiProviderId.OPENAI)
            assertThat(result.modelId).isEqualTo(MODEL)
            assertThat(result.resultJson).isEqualTo(
                """{"schemaVersion":1,"primaryCauseCode":"SENSOR_QUALITY","confidence":"HIGH","evidenceCodes":["SENSOR_BLOCKED"],"adviceCode":"DATA_INCOMPLETE"}"""
            )
        }
    }

    @Test
    fun invalidTypedOutputFailsClosedWithoutSecondRequest() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val transport = RecordingTransport(
                endpoint = server.url("/alert"),
                output = """{"schemaVersion":1,"primaryCauseCode":"SENSOR_QUALITY","confidence":"HIGH","evidenceCodes":[],"adviceCode":"DATA_INCOMPLETE","insulinDoseUnits":1}"""
            )
            val gateway = ClinicalOpenAiClient.forTransport(transport)

            val failure = runCatching {
                gateway.analyzeAlert(
                    context(),
                    { "synthetic-credential" },
                    alertAiTestNetworkLease()
                )
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
            assertThat(transport.requests.get()).isEqualTo(1)
        }
    }

    @Test
    fun rehashedContextWithUnknownFieldIsRejectedBeforeTransportRequest() = runTest {
        MockWebServer().use { server ->
            val transport = RecordingTransport(
                endpoint = server.url("/alert"),
                output = VALID_ALERT_RESULT_JSON
            )
            val gateway = ClinicalOpenAiClient.forTransport(transport)
            val base = context()
            val root = JsonParser.parseString(base.canonicalJson).asJsonObject.apply {
                addProperty("episodeId", "private-episode")
            }
            val bytes = root.toString().toByteArray(Charsets.UTF_8)
            val forged = AlertAiCanonicalContext(
                canonicalJson = bytes.toString(Charsets.UTF_8),
                canonicalBytes = bytes.toByteString(),
                sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) },
                rowCount = base.rowCount
            )

            val failure = runCatching {
                gateway.analyzeAlert(
                    forged,
                    { "synthetic-credential" },
                    alertAiTestNetworkLease()
                )
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(ClinicalOpenAiException.InvalidInput::class.java)
            assertThat(transport.requests.get()).isEqualTo(0)
        }
    }

    private class RecordingTransport(
        private val endpoint: okhttp3.HttpUrl,
        private val output: String
    ) : ClinicalAiStructuredTransport {
        override val providerId = ClinicalAiProviderId.OPENAI
        override val modelId = MODEL
        var kind: ClinicalResponseKind? = null
        var input: String? = null
        val requests = AtomicInteger()

        override fun requestBody(input: String, kind: ClinicalResponseKind): ByteArray {
            this.input = input
            this.kind = kind
            return "{}".toByteArray()
        }

        override fun connectionRequestBody(): ByteArray = "{}".toByteArray()

        override fun request(credential: String, body: ByteArray): Request {
            requests.incrementAndGet()
            return Request.Builder()
                .url(endpoint)
                .post(OneShotJsonRequestBody(body))
                .build()
        }

        override fun parseEnvelope(raw: String) = ClinicalStructuredEnvelope(
            output = output,
            model = modelId,
            systemFingerprint = null
        )
    }

    private fun context(): AlertAiCanonicalContext = alertContextFixture()

    private companion object {
        const val MODEL = "gpt-test"
    }
}

internal fun alertContextFixture(
    now: Long = ALERT_AI_CONTEXT_FIXTURE_NOW,
    stage: String = "LOW_PREDICTED_30",
    direction: AlertCauseDirection = AlertCauseDirection.LOW,
    localCauseSnapshot: AlertCauseSnapshot = AlertCauseSnapshotCodec.failureSnapshot()
): AlertAiCanonicalContext {
    val glucose = listOf(ClinicalGlucosePoint(now - 1_000L, 5.6))
    val therapy = listOf(
        ClinicalTherapyPoint(
            ts = now - 2_000L,
            insulinU = 1.0,
            carbsG = 12.0,
            insulinEvidence = ClinicalInsulinEvidence.CONFIRMED
        )
    )
    val dataset = AlertAiContextDataset(
        generatedAt = now,
        detail24h = ClinicalDetailWindow(
            fromTs = now - 24L * 60L * 60L * 1_000L,
            throughTs = now,
            glucose = glucose,
            calibratedGlucose = glucose,
            therapy = therapy,
            targets = listOf(ClinicalTargetPoint(now - 3_000L, 5.5)),
            forecasts = listOf(ClinicalForecastPoint(now - 4_000L, 30, 5.8, 5.2, 6.4)),
            telemetry = emptyList()
        ),
        glucose14d = glucose,
        therapy14d = therapy,
        events14d = listOf(
            ClinicalEventSummary(
                localId = "internal-event-id",
                type = "STRESS",
                subtype = "WORK",
                startTs = now - 5_000L,
                endTs = now - 1_000L,
                severity = "MEDIUM",
                source = "USER",
                title = "Stress",
                note = "Ordinary note",
                status = "ACTIVE",
                provenance = "LOCAL"
            )
        )
    )
    return AlertAiContextBuilder().build(
        AlertAiContextRequest(
            nowTs = now,
            dataset = dataset,
            localCauseSnapshot = localCauseSnapshot,
            stage = stage,
            direction = direction
        )
    )
}

internal const val ALERT_AI_CONTEXT_FIXTURE_NOW = 20L * 24L * 60L * 60L * 1_000L

internal fun alertAiTestNetworkLease(
    identity: Long = 101L,
    calls: AtomicInteger? = null
): AlertAiValidatedNetworkLease = AlertAiValidatedNetworkLease(
    snapshot = AlertAiValidatedNetworkSnapshot(identity),
    callFactory = AlertAiBoundCallFactory { client, request ->
        calls?.incrementAndGet()
        client.newCall(request)
    }
)

internal const val VALID_ALERT_RESULT_JSON =
    "{\"schemaVersion\":1,\"primaryCauseCode\":\"SENSOR_QUALITY\",\"confidence\":\"HIGH\",\"evidenceCodes\":[\"SENSOR_BLOCKED\"],\"adviceCode\":\"DATA_INCOMPLETE\"}"
