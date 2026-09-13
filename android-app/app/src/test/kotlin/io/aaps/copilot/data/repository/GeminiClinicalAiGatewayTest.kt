package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.aaps.copilot.config.ClinicalAiProviderId
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertThrows
import org.junit.Test

class GeminiClinicalAiGatewayTest {

    @Test
    fun alertCauseUsesNativeRouteSelectedModelAndStrictAlertSchema() = runTest {
        MockWebServer().use { server ->
            server.enqueue(successResponse(VALID_ALERT_RESULT_JSON))
            val boundCalls = AtomicInteger()

            val result = testGateway(server).analyzeAlert(
                alertContextFixture(),
                { SECRET },
                alertAiTestNetworkLease(calls = boundCalls)
            )

            val request = server.takeRequest()
            val body = requestBody(request)
            assertThat(request.path).isEqualTo("/v1beta/models/$MODEL:generateContent")
            assertThat(request.getHeader("x-goog-api-key")).isEqualTo(SECRET)
            assertThat(body.getAsJsonObject("generationConfig").get("maxOutputTokens").asInt)
                .isEqualTo(ClinicalOpenAiClient.ALERT_CAUSE_MAX_OUTPUT_TOKENS)
            assertThat(requestSchema(body))
                .isEqualTo(ClinicalOpenAiClient.strictAlertCauseSchemaJson())
            assertThat(userText(body)).isEqualTo(alertContextFixture().canonicalJson)
            assertThat(result.providerId).isEqualTo(ClinicalAiProviderId.GEMINI)
            assertThat(result.modelId).isEqualTo(MODEL)
            assertThat(result.resultJson).isEqualTo(VALID_ALERT_RESULT_JSON)
            assertThat(boundCalls.get()).isEqualTo(1)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun alertCauseWrongResponseModelIsIdentityMismatch() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                successResponse(VALID_ALERT_RESULT_JSON, modelVersion = "wrong-model")
            )

            val failure = runCatching {
                testGateway(server).analyzeAlert(
                    alertContextFixture(),
                    { SECRET },
                    alertAiTestNetworkLease()
                )
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(ClinicalAiGatewayException.IdentityMismatch::class.java)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun nativeGenerateContentRequestUsesExactRouteHeaderAndSharedSchema() = runTest {
        MockWebServer().use { server ->
            server.enqueue(successResponse(validReportJson()))

            val result = testGateway(server).analyze(payload(datasetFixture()), { SECRET })

            val request = server.takeRequest()
            val body = requestBody(request)
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.path)
                .isEqualTo("/v1beta/models/$MODEL:generateContent")
            assertThat(request.getHeader("x-goog-api-key")).isEqualTo(SECRET)
            assertThat(request.getHeader("Authorization")).isNull()
            assertThat(request.requestUrl?.query).isNull()
            assertThat(request.getHeader("Content-Type")).startsWith("application/json")
            assertThat(body.keySet())
                .containsExactly("systemInstruction", "contents", "generationConfig")
            assertThat(body.getAsJsonArray("contents")).hasSize(1)
            val content = body.getAsJsonArray("contents").single().asJsonObject
            assertThat(content.get("role").asString).isEqualTo("user")
            assertThat(content.getAsJsonArray("parts")).hasSize(1)
            assertThat(content.getAsJsonArray("parts").single().asJsonObject.keySet())
                .containsExactly("text")
            val system = body.getAsJsonObject("systemInstruction")
            assertThat(system.getAsJsonArray("parts")).hasSize(1)
            assertThat(system.getAsJsonArray("parts").single().asJsonObject.get("text").asString)
                .isEqualTo(ClinicalOpenAiClient.CLINICAL_INSTRUCTIONS)
            val config = body.getAsJsonObject("generationConfig")
            assertThat(config.get("maxOutputTokens").asInt)
                .isEqualTo(ClinicalOpenAiClient.MAX_OUTPUT_TOKENS)
            assertThat(config.get("responseMimeType").asString).isEqualTo("application/json")
            assertThat(config.get("candidateCount").asInt).isEqualTo(1)
            val schema = requestSchema(body)
            assertThat(schema).isEqualTo(ClinicalOpenAiClient.strictSchemaJson())
            assertThat(result.metadata.providerId).isEqualTo(ClinicalAiProviderId.GEMINI)
            assertThat(result.metadata.requestedProviderId).isEqualTo(ClinicalAiProviderId.GEMINI)
            assertThat(result.metadata.model).isEqualTo(MODEL)
            assertThat(result.metadata.requestedModel).isEqualTo(MODEL)
            assertThat(result.report.summary7dStatus).isEqualTo(ClinicalSummaryStatus.STABLE)
        }
    }

    @Test
    fun modelPathIsPercentSafeAndRouteDelimitersAreRejectedBeforeRequest() = runTest {
        MockWebServer().use { server ->
            val percentModel = "gemini%2Fsafe"
            server.enqueue(successResponse(validReportJson(), modelVersion = percentModel))

            GeminiClinicalAiGateway.forTest(
                baseUrl = server.url("/").toString(),
                model = percentModel
            ).analyze(payload(datasetFixture()), { SECRET })

            assertThat(server.takeRequest().path)
                .isEqualTo("/v1beta/models/gemini%252Fsafe:generateContent")
        }

        listOf("gemini/escape", "gemini?key=leak", "gemini#fragment").forEach { model ->
            assertThrows(IllegalArgumentException::class.java) {
                GeminiClinicalAiGateway(modelId = model)
            }
        }
    }

    @Test
    fun forTestRequiresExactLoopbackRootBase() {
        listOf(
            "https://127.0.0.1/",
            "http://example.com/",
            "http://127.0.0.1/v1beta/",
            "http://127.0.0.1/?x=1",
            "http://user@127.0.0.1/"
        ).forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) {
                GeminiClinicalAiGateway.forTest(baseUrl = endpoint, model = MODEL)
            }
        }
    }

    @Test
    fun testConnectionIsBoundedAndContainsNoMedicalPayloadOrHashes() = runTest {
        MockWebServer().use { server ->
            server.enqueue(successResponse("""{"ok":true}"""))
            val medical = datasetFixture()
            val medicalPayload = payload(medical)

            val result = testGateway(server).testConnection(SECRET)

            val request = server.takeRequest()
            val bodyText = request.body.readUtf8()
            val body = JsonParser.parseString(bodyText).asJsonObject
            assertThat(result.status).isEqualTo(ClinicalAiConnectionStatus.SUCCESS)
            assertThat(result.providerId).isEqualTo(ClinicalAiProviderId.GEMINI)
            assertThat(result.modelId).isEqualTo(MODEL)
            assertThat(bodyText.toByteArray(Charsets.UTF_8).size)
                .isAtMost(GeminiClinicalAiGateway.MAX_CONNECTION_REQUEST_BYTES)
            assertThat(bodyText).doesNotContain(medicalPayload.sha256)
            assertThat(bodyText).doesNotContain(medicalPayload.compactJson)
            assertThat(bodyText).doesNotContain(medical.generatedAt.toString())
            assertThat(userText(body)).contains("Connection test")
            val schema = requestSchema(body)
            assertThat(schema.keySet()).containsExactly(
                "type",
                "additionalProperties",
                "properties",
                "required"
            )
            assertThat(schema.get("type").asString).isEqualTo("object")
            assertThat(schema.get("additionalProperties").asBoolean).isFalse()
            assertThat(schema.getAsJsonArray("required").map { it.asString })
                .containsExactly("ok")
            assertThat(schema.getAsJsonObject("properties").keySet()).containsExactly("ok")
            val okSchema = schema.getAsJsonObject("properties").getAsJsonObject("ok")
            assertThat(okSchema.keySet()).containsExactly("type")
            assertThat(okSchema.get("type").asString).isEqualTo("boolean")
            assertThat(body.getAsJsonObject("generationConfig").get("maxOutputTokens").asInt)
                .isAtMost(1_024)
            assertThat(request.getHeader("x-goog-api-key")).isEqualTo(SECRET)
            assertThat(request.getHeader("Authorization")).isNull()
        }
    }

    @Test
    fun forcedMapReduceUsesGeminiEnvelopeModelAndCredentialForEveryCall() = runTest {
        MockWebServer().use { server ->
            val bodies = CopyOnWriteArrayList<JsonObject>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = requestBody(request)
                    bodies += body
                    return if (
                        requestSchema(body).getAsJsonObject("properties").has("findings")
                    ) {
                        successResponse(validChunkJson())
                    } else {
                        successResponse(validReportJson())
                    }
                }
            }
            val dataset = datasetFixture(days = 4, rowsPerDay = 24)
            val directBytes = GeminiClinicalAiGateway.requestBytesForTest(dataset, MODEL)
            var credentialReads = 0

            val result = testGateway(server, requestByteBudget = directBytes - 1)
                .analyze(payload(dataset), {
                    credentialReads += 1
                    "rotated-$credentialReads"
                })

            assertThat(result.metadata.usedSynthesis).isTrue()
            assertThat(result.metadata.chunkCount).isGreaterThan(1)
            assertThat(result.metadata.reductionLevels).isGreaterThan(0)
            assertThat(result.metadata.coverageLedgerHash).hasLength(64)
            val requests = (0 until server.requestCount).map { server.takeRequest() }
            assertThat(bodies).hasSize(requests.size)
            assertThat(credentialReads).isEqualTo(requests.size)
            requests.forEachIndexed { index, request ->
                assertThat(request.path).isEqualTo("/v1beta/models/$MODEL:generateContent")
                assertThat(request.getHeader("x-goog-api-key")).isEqualTo("rotated-${index + 1}")
                assertThat(request.getHeader("Authorization")).isNull()
                assertThat(request.requestUrl?.query).isNull()
            }
            bodies.forEach { body ->
                assertThat(body.getAsJsonObject("generationConfig").get("candidateCount").asInt)
                    .isEqualTo(1)
                assertThat(body.toString().toByteArray(Charsets.UTF_8).size)
                    .isAtMost(directBytes - 1)
            }
            assertThat(userText(bodies.last())).contains("clinical_map_reduce_synthesis")
            assertThat(userText(bodies.last())).doesNotContain("\"g30\":[")
        }
    }

    @Test
    fun promptBlockMapsToRefusalBeforeCandidateValidation() = runTest {
        listOf("SAFETY", "OTHER", "BLOCKLIST", "PROHIBITED_CONTENT", "IMAGE_SAFETY")
            .forEach { reason ->
                MockWebServer().use { server ->
                    server.enqueue(
                        MockResponse().setBody(
                            """{"candidates":[],"promptFeedback":{"blockReason":"$reason"}}"""
                        )
                    )

                    val failure = analyzeFailure(server)

                    assertThat(failure)
                        .isInstanceOf(ClinicalOpenAiException.Refusal::class.java)
                    assertSanitized(failure)
                }
            }
    }

    @Test
    fun finishReasonsMapToProviderNeutralFailures() = runTest {
        val refusalReasons = listOf(
            "SAFETY",
            "RECITATION",
            "LANGUAGE",
            "BLOCKLIST",
            "PROHIBITED_CONTENT",
            "SPII",
            "IMAGE_SAFETY",
            "ESCALATION",
            "IMAGE_PROHIBITED_CONTENT",
            "IMAGE_RECITATION"
        )
        refusalReasons.forEach { reason ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(responseBody("", finishReason = reason)))
                val failure = analyzeFailure(server)
                assertThat(failure)
                    .isInstanceOf(ClinicalOpenAiException.Refusal::class.java)
                assertSanitized(failure)
            }
        }

        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(responseBody("", finishReason = "MAX_TOKENS")))
            val failure = analyzeFailure(server)
            assertThat(failure)
                .isInstanceOf(ClinicalOpenAiException.Incomplete::class.java)
            assertSanitized(failure)
        }

        listOf(
            "FINISH_REASON_UNSPECIFIED",
            "OTHER",
            "MALFORMED_FUNCTION_CALL",
            "MALFORMED_RESPONSE",
            "FUTURE_REASON"
        ).forEach { reason ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(responseBody("", finishReason = reason)))
                val failure = analyzeFailure(server)
                assertThat(failure)
                    .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
                assertSanitized(failure)
            }
        }
    }

    @Test
    fun stopAcceptsBoundedBase64ThoughtSignatureAndDiscardsIt() = runTest {
        MockWebServer().use { server ->
            val report = validReportJson()
            server.enqueue(
                MockResponse().setBody(
                    responseBody(
                        report,
                        content =
                            """{"role":"model","parts":[{"text":${jsonString(report)},"thoughtSignature":"YWJjZA=="}]}"""
                    )
                )
            )

            val result = testGateway(server).analyze(payload(datasetFixture()), { SECRET })

            assertThat(result.report.summary7dStatus).isEqualTo(ClinicalSummaryStatus.STABLE)
            assertThat(result.metadata.model).isEqualTo(MODEL)
        }
    }

    @Test
    fun malformedThoughtSignaturesAreRejected() = runTest {
        val report = jsonString(validReportJson())
        val signatures = listOf(
            "null",
            "false",
            jsonString(""),
            jsonString("abc"),
            jsonString("ab=c"),
            jsonString("YWJjZA==="),
            jsonString("A".repeat(20_000))
        )
        signatures.forEach { signature ->
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setBody(
                        responseBody(
                            validReportJson(),
                            content =
                                """{"role":"model","parts":[{"text":$report,"thoughtSignature":$signature}]}"""
                        )
                    )
                )

                val failure = analyzeFailure(server)

                assertThat(failure)
                    .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
                assertSanitized(failure)
            }
        }
    }

    @Test
    fun nonSuccessFinishReasonsWinOverAbsentEmptyAndPartialContent() = runTest {
        val contents = listOf(
            null,
            "{}",
            """{"role":"model"}""",
            """{"role":"model","parts":[]}""",
            """{"role":"model","parts":[{}]}""",
            """{"role":"model","parts":[{"text":"partial"}]}"""
        )
        val cases = listOf(
            "MAX_TOKENS" to ClinicalOpenAiFailureKind.INCOMPLETE,
            "SAFETY" to ClinicalOpenAiFailureKind.REFUSAL
        )
        cases.forEach { (finishReason, expectedKind) ->
            contents.forEach { content ->
                MockWebServer().use { server ->
                    server.enqueue(
                        MockResponse().setBody(
                            responseBody(
                                output = "",
                                finishReason = finishReason,
                                content = content
                            )
                        )
                    )

                    val failure = analyzeFailure(server)

                    assertThat(failure).isInstanceOf(ClinicalOpenAiException::class.java)
                    assertThat((failure as ClinicalOpenAiException).kind)
                        .isEqualTo(expectedKind)
                    assertSanitized(failure)
                }
            }
        }
    }

    @Test
    fun nonSuccessStillRejectsCandidateFieldsMetadataAndFinishReasonType() = runTest {
        val responses = listOf(
            responseBody(
                output = "",
                finishReason = "SAFETY",
                content = null,
                candidateExtra = ""","unexpected":true"""
            ),
            responseBody(
                output = "",
                finishReason = "MAX_TOKENS",
                content = null,
                candidateExtra = ""","safetyRatings":{}"""
            ),
            """
            {
              "candidates":[{
                "finishReason":1
              }]
            }
            """.trimIndent()
        )
        responses.forEach { body ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(body))

                val failure = analyzeFailure(server)

                assertThat(failure)
                    .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
                assertSanitized(failure)
            }
        }
    }

    @Test
    fun successRequiresExactlyOneStrictModelTextCandidate() = runTest {
        val report = jsonString(validReportJson())
        val malformed = listOf(
            "{not-json",
            """{"candidates":[]}""",
            """{"candidates":[{},{}]}""",
            """{"candidates":[{"finishReason":"STOP"}]}""",
            responseBody(validReportJson(), content = """{"role":"user","parts":[{"text":$report}]}"""),
            responseBody(validReportJson(), content = """{"role":"model","parts":[]}"""),
            responseBody(
                validReportJson(),
                content = """{"role":"model","parts":[{"text":$report},{"text":"{}"}]}"""
            ),
            responseBody(
                validReportJson(),
                content = """{"role":"model","parts":[{"text":$report,"thought":false}]}"""
            ),
            responseBody(
                validReportJson(),
                content = """{"role":"model","parts":[{"inlineData":{}}]}"""
            ),
            responseBody(
                validReportJson(),
                content = """{"role":"model","parts":[{"text":"   "}]}"""
            ),
            responseBody(
                validReportJson(),
                content = """{"role":"model","parts":[{"text":$report}],"unexpected":true}"""
            ),
            responseBody(validReportJson(), candidateExtra = ""","safetyRatings":{}"""),
            responseBody(validReportJson(), candidateExtra = ""","citationMetadata":[]"""),
            responseBody(validReportJson(), candidateExtra = ""","tokenCount":"10""""),
            responseBody(validReportJson(), candidateExtra = ""","groundingAttributions":{}"""),
            responseBody(validReportJson(), candidateExtra = ""","groundingMetadata":[]"""),
            responseBody(validReportJson(), candidateExtra = ""","avgLogprobs":"0.5""""),
            responseBody(validReportJson(), candidateExtra = ""","logprobsResult":[]"""),
            responseBody(validReportJson(), candidateExtra = ""","urlContextMetadata":[]"""),
            responseBody(validReportJson(), candidateExtra = ""","index":-1"""),
            responseBody(validReportJson(), candidateExtra = ""","finishMessage":1"""),
            responseBody(validReportJson(), candidateExtra = ""","unexpected":true"""),
            responseBody(validReportJson(), rootExtra = ""","unexpected":true""")
        )
        malformed.forEach { body ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(body))
                val failure = analyzeFailure(server)
                assertThat(failure)
                    .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
                assertSanitized(failure)
            }
        }
    }

    @Test
    fun topLevelMetadataIsAllowListedAndModelVersionUsesDocumentedNormalization() = runTest {
        listOf(null, MODEL, "models/$MODEL").forEach { modelVersion ->
            MockWebServer().use { server ->
                server.enqueue(
                    successResponse(
                        validReportJson(),
                        modelVersion = modelVersion,
                        rootExtra =
                            ""","usageMetadata":{"totalTokenCount":30},"responseId":"response-1""""
                    )
                )

                val result = testGateway(server)
                    .analyze(payload(datasetFixture()), { SECRET })

                assertThat(result.metadata.model).isEqualTo(MODEL)
                assertThat(result.metadata.requestedModel).isEqualTo(MODEL)
            }
        }

        listOf("gemini-other", "models/gemini-other", "models/models/$MODEL").forEach {
            modelVersion ->
            MockWebServer().use { server ->
                server.enqueue(successResponse(validReportJson(), modelVersion = modelVersion))
                val failure = analyzeFailure(server)
                assertThat(failure)
                    .isInstanceOf(ClinicalAiGatewayException.IdentityMismatch::class.java)
                assertSanitized(failure)
            }
        }
    }

    @Test
    fun malformedPromptFeedbackAndKnownMetadataTypesFailClosed() = runTest {
        val responses = listOf(
            """{"candidates":[],"promptFeedback":"SAFETY"}""",
            """{"candidates":[],"promptFeedback":{"blockReason":1}}""",
            """{"candidates":[],"promptFeedback":{"blockReason":"SAFETY","unexpected":true}}""",
            responseBody(validReportJson(), rootExtra = ""","usageMetadata":[]"""),
            responseBody(validReportJson(), rootExtra = ""","responseId":1"""),
            responseBody(validReportJson(), rootExtra = ""","modelVersion":1""")
        )
        responses.forEach { body ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(body))
                val failure = analyzeFailure(server)
                assertThat(failure)
                    .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
                assertSanitized(failure)
            }
        }
    }

    @Test
    fun sharedFinalAndChunkParsersRemainStrict() = runTest {
        MockWebServer().use { server ->
            val report = JsonParser.parseString(validReportJson()).asJsonObject.apply {
                getAsJsonArray("patterns").single().asJsonObject
                    .addProperty("evidenceValue", 101.0)
            }
            server.enqueue(successResponse(report.toString()))

            val failure = analyzeFailure(server)

            assertThat(failure)
                .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
        }

        MockWebServer().use { server ->
            val chunk = JsonParser.parseString(validChunkJson()).asJsonObject.apply {
                val findings = getAsJsonArray("findings")
                findings.add(findings.single().deepCopy())
            }
            server.enqueue(successResponse(chunk.toString()))
            val dataset = datasetFixture(days = 4, rowsPerDay = 24)
            val budget = GeminiClinicalAiGateway.requestBytesForTest(dataset, MODEL) - 1

            val failure = runCatching {
                testGateway(server, requestByteBudget = budget)
                    .analyze(payload(dataset), { SECRET })
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(ClinicalOpenAiException.ChunkFailed::class.java)
            assertThat((failure as ClinicalOpenAiException).kind)
                .isEqualTo(ClinicalOpenAiFailureKind.INVALID_RESPONSE)
        }
    }

    @Test
    fun nativeGeminiGatewayRejectsForbiddenNestedCommandEnvelope() = runTest {
        MockWebServer().use { server ->
            val report = JsonParser.parseString(validReportJson()).asJsonObject.apply {
                add(
                    "providerExtension",
                    JsonObject().apply {
                        add(
                            "nested",
                            JsonObject().apply {
                                addProperty("glucoseTarget", 5.0)
                            }
                        )
                    }
                )
            }
            server.enqueue(successResponse(report.toString()))

            val failure = analyzeFailure(server)

            assertThat(failure)
                .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
            assertSanitized(failure)
        }
    }

    @Test
    fun httpFailuresMapWithoutRetryOrRedirect() = runTest {
        val cases = listOf(
            401 to ClinicalOpenAiFailureKind.UNAUTHORIZED,
            403 to ClinicalOpenAiFailureKind.UNAUTHORIZED,
            429 to ClinicalOpenAiFailureKind.RATE_LIMITED,
            500 to ClinicalOpenAiFailureKind.SERVER,
            503 to ClinicalOpenAiFailureKind.SERVER,
            307 to ClinicalOpenAiFailureKind.HTTP
        )
        cases.forEach { (code, expectedKind) ->
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(code)
                        .setHeader("Location", server.url("/redirected"))
                        .setHeader("Retry-After", "0")
                )

                val failure = analyzeFailure(server)

                assertThat(failure).isInstanceOf(ClinicalOpenAiException::class.java)
                assertThat((failure as ClinicalOpenAiException).kind).isEqualTo(expectedKind)
                assertThat(server.requestCount).isEqualTo(1)
                assertSanitized(failure)
            }
        }
    }

    @Test
    fun oversizedTimeoutAndNetworkFailuresAreSanitized() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(2_048)))
            val failure = runCatching {
                testGateway(server, maxResponseBytes = 1_024)
                    .analyze(payload(datasetFixture()), { SECRET })
            }.exceptionOrNull()
            assertThat(failure)
                .isInstanceOf(ClinicalOpenAiException.OversizedResponse::class.java)
            assertSanitized(failure)
        }

        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val failure = runCatching {
                testGateway(
                    server,
                    timeouts = ClinicalOpenAiTimeouts(
                        connectMillis = 100,
                        writeMillis = 100,
                        readMillis = 100,
                        callMillis = 1_000
                    )
                ).analyze(payload(datasetFixture()), { SECRET })
            }.exceptionOrNull()
            assertThat(failure)
                .isInstanceOf(ClinicalOpenAiException.Timeout::class.java)
            assertSanitized(failure)
        }

        val server = MockWebServer()
        server.start()
        val gateway = testGateway(server)
        server.shutdown()
        val failure = runCatching {
            gateway.analyze(payload(datasetFixture()), { SECRET })
        }.exceptionOrNull()
        assertThat(failure)
            .isInstanceOf(ClinicalOpenAiException.NetworkFailure::class.java)
        assertSanitized(failure)
    }

    @Test
    fun connectionStatusUsesProviderNeutralHttpMapping() = runTest {
        val cases = listOf(
            401 to ClinicalAiConnectionStatus.UNAUTHORIZED,
            403 to ClinicalAiConnectionStatus.UNAUTHORIZED,
            429 to ClinicalAiConnectionStatus.RATE_LIMITED,
            503 to ClinicalAiConnectionStatus.SERVICE_UNAVAILABLE
        )
        cases.forEach { (code, expectedStatus) ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(code).setBody("secret=$SECRET"))

                val result = testGateway(server).testConnection(SECRET)

                assertThat(result.status).isEqualTo(expectedStatus)
                assertThat(result.toString()).doesNotContain(SECRET)
                assertThat(server.requestCount).isEqualTo(1)
            }
        }
    }

    private suspend fun analyzeFailure(server: MockWebServer): Throwable? =
        runCatching {
            testGateway(server).analyze(payload(datasetFixture()), { SECRET })
        }.exceptionOrNull()

    private fun testGateway(
        server: MockWebServer,
        requestByteBudget: Int = 256 * 1_024,
        maxResponseBytes: Int = 64 * 1_024,
        timeouts: ClinicalOpenAiTimeouts = ClinicalOpenAiTimeouts()
    ): GeminiClinicalAiGateway = GeminiClinicalAiGateway.forTest(
        baseUrl = server.url("/").toString(),
        model = MODEL,
        requestByteBudget = requestByteBudget,
        maxResponseBytes = maxResponseBytes,
        timeouts = timeouts
    )

    private fun successResponse(
        output: String,
        modelVersion: String? = null,
        rootExtra: String = ""
    ): MockResponse = MockResponse().setBody(
        responseBody(
            output = output,
            modelVersion = modelVersion,
            rootExtra = rootExtra
        )
    )

    private fun responseBody(
        output: String,
        finishReason: String = "STOP",
        content: String? =
            """{"role":"model","parts":[{"text":${jsonString(output)}}]}""",
        modelVersion: String? = null,
        candidateExtra: String = "",
        rootExtra: String = ""
    ): String =
        """
        {
          "candidates":[{
            ${content?.let { """"content":$it,""" }.orEmpty()}
            "finishReason":${jsonString(finishReason)}
            $candidateExtra
          }]
          ${modelVersion?.let { ""","modelVersion":${jsonString(it)}""" }.orEmpty()}
          $rootExtra
        }
        """.trimIndent()

    private fun requestBody(request: RecordedRequest): JsonObject =
        JsonParser.parseString(request.body.clone().readUtf8()).asJsonObject

    private fun requestSchema(body: JsonObject): JsonObject =
        body.getAsJsonObject("generationConfig").getAsJsonObject("responseJsonSchema")

    private fun userText(body: JsonObject): String =
        body.getAsJsonArray("contents")
            .single()
            .asJsonObject
            .getAsJsonArray("parts")
            .single()
            .asJsonObject
            .get("text")
            .asString

    private fun payload(dataset: ClinicalReportDataset): ClinicalReportPayload {
        val compact = ClinicalReportDatasetBuilder.serialize(dataset)
        return ClinicalReportPayload(
            dataset = dataset,
            compactJson = compact,
            sha256 = ClinicalReportDatasetBuilder.sha256(compact)
        )
    }

    private fun datasetFixture(
        days: Int = 2,
        rowsPerDay: Int = 3
    ): ClinicalReportDataset {
        val zone = ZoneId.of("UTC")
        val generatedAt = ZonedDateTime.of(2026, 7, 20, 12, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val start = generatedAt - days * DAY_MS
        val rowStep = DAY_MS / rowsPerDay
        val glucose = (0 until days * rowsPerDay).map { index ->
            ClinicalGlucosePoint(start + index * rowStep, 5.5 + (index % 5) * 0.1)
        }
        val therapy = glucose.mapIndexed { index, point ->
            ClinicalTherapyPoint(
                ts = point.ts,
                insulinU = if (index % 2 == 0) 1.0 else null,
                carbsG = if (index % 2 == 1) 10.0 else null
            )
        }
        val targets = glucose.map {
            ClinicalTargetPoint(it.ts, 5.5, 6.5, durationMs = 30 * 60_000L)
        }
        val detailRows = glucose.filter { it.ts >= generatedAt - DAY_MS }
        val quality = ClinicalDataQuality(
            expectedBuckets = days * rowsPerDay,
            coveredBuckets = days * rowsPerDay,
            missingBuckets = 0,
            maxGapMinutes = 5
        )
        val hourly = (0..23).map { ClinicalHourlyMetric(it, 1, 6.0, 6.0) }
        fun summary(periodDays: Int) = ClinicalPeriodSummary(
            days = periodDays,
            fromTs = start,
            throughTs = generatedAt,
            coveragePct = 100.0,
            meanMmol = 6.0,
            medianMmol = 6.0,
            coefficientOfVariationPct = 10.0,
            timeBelow4Pct = 1.0,
            timeInRangePct = 90.0,
            timeAboveRangePct = 9.0,
            totalInsulinU = 10.0,
            totalCarbsG = 100.0,
            meanTargetMmol = 6.0,
            weekdayPattern = hourly,
            weekendPattern = hourly,
            quality = quality
        )
        return ClinicalReportDataset(
            schemaVersion = ClinicalReportDatasetBuilder.SCHEMA_VERSION,
            generatedAt = generatedAt,
            zoneId = zone.id,
            detail24h = ClinicalDetailWindow(
                fromTs = generatedAt - DAY_MS,
                throughTs = generatedAt,
                glucose = detailRows,
                calibratedGlucose = detailRows.map { it.copy(mmol = it.mmol + 0.1) },
                therapy = therapy.filter { it.ts >= generatedAt - DAY_MS },
                targets = targets.filter { it.ts >= generatedAt - DAY_MS },
                forecasts = detailRows.map {
                    ClinicalForecastPoint(
                        it.ts,
                        30,
                        it.mmol + 0.2,
                        it.mmol - 0.3,
                        it.mmol + 0.7
                    )
                },
                telemetry = detailRows.map {
                    ClinicalTelemetryPoint(it.ts, "gemini-test-marker", 1.0, "OK")
                }
            ),
            glucose7d = glucose,
            therapy7d = therapy,
            targets7d = targets,
            glucose30d = glucose,
            therapy30d = therapy,
            targets30d = targets,
            summary7d = summary(7),
            summary30d = summary(30)
        )
    }

    private fun validReportJson(): String =
        """
        {
          "summary7dStatus":"STABLE",
          "summary30dStatus":"MIXED",
          "dataQuality":["COMPLETE"],
          "patterns":[{
            "topic":"GLUCOSE_STABILITY",
            "period":"LAST_7_DAYS",
            "direction":"STABLE",
            "confidence":"MEDIUM",
            "timeBand":"OVERNIGHT",
            "evidenceMetric":"TIME_IN_RANGE_PCT",
            "evidenceValue":90.0
          }],
          "safetyObservations":["NONE_IDENTIFIED"],
          "recommendations":[{
            "careTeamDiscussionTopic":"SENSOR_RELIABILITY",
            "priority":"MEDIUM",
            "evidenceFindingIndices":[0],
            "period":"LAST_7_DAYS"
          }],
          "careTeamQuestions":["SENSOR_RELIABILITY_CONTEXT"]
        }
        """.trimIndent()

    private fun validChunkJson(): String =
        """
        {
          "dataQuality":["COMPLETE"],
          "findings":[{
            "topic":"GLUCOSE_STABILITY",
            "period":"LAST_24_HOURS",
            "direction":"STABLE",
            "confidence":"MEDIUM",
            "timeBand":"OVERNIGHT",
            "evidenceMetric":"TIME_IN_RANGE_PCT",
            "evidenceValue":90.0
          }]
        }
        """.trimIndent()

    private fun jsonString(value: String): String = Gson().toJson(value)

    private fun assertSanitized(failure: Throwable?) {
        assertThat(failure).isNotNull()
        assertThat(failure.toString()).doesNotContain(SECRET)
        assertThat(failure?.message.orEmpty()).doesNotContain(SECRET)
    }

    private companion object {
        const val MODEL = "gemini-3.6-flash"
        const val SECRET = "gemini-super-secret"
        const val DAY_MS = 24L * 60L * 60L * 1_000L
    }
}
