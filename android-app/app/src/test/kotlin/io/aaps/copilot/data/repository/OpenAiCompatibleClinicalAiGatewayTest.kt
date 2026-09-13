package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.aaps.copilot.config.ClinicalAiProviderConfig
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.config.OpenAiCompatibleProtocol
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

class OpenAiCompatibleClinicalAiGatewayTest {

    @Test
    fun everyCompatibleProtocolUsesSelectedRouteModelAndStrictAlertSchema() = runTest {
        OpenAiCompatibleProtocol.entries.forEach { protocol ->
            MockWebServer().use { server ->
                server.enqueue(
                    when (protocol) {
                        OpenAiCompatibleProtocol.RESPONSES ->
                            responsesSuccess(VALID_ALERT_RESULT_JSON)
                        OpenAiCompatibleProtocol.CHAT_COMPLETIONS ->
                            chatSuccess(VALID_ALERT_RESULT_JSON)
                    }
                )
                val boundCalls = AtomicInteger()

                val result = gateway(server, protocol)
                    .analyzeAlert(
                        alertContextFixture(),
                        { SECRET },
                        alertAiTestNetworkLease(calls = boundCalls)
                    )

                val request = server.takeRequest()
                val body = requestBody(request)
                assertThat(request.path).isEqualTo(
                    if (protocol == OpenAiCompatibleProtocol.RESPONSES) {
                        "/v1/responses"
                    } else {
                        "/v1/chat/completions"
                    }
                )
                assertThat(body.get("model").asString).isEqualTo(MODEL)
                if (protocol == OpenAiCompatibleProtocol.RESPONSES) {
                    val format = body.getAsJsonObject("text").getAsJsonObject("format")
                    assertStrictFormat(
                        format,
                        ClinicalOpenAiClient.ALERT_CAUSE_SCHEMA_NAME,
                        ClinicalOpenAiClient.strictAlertCauseSchemaJson()
                    )
                } else {
                    assertStrictChatFormat(
                        body.getAsJsonObject("response_format"),
                        ClinicalOpenAiClient.ALERT_CAUSE_SCHEMA_NAME,
                        ClinicalOpenAiClient.strictAlertCauseSchemaJson()
                    )
                }
                assertThat(result.providerId)
                    .isEqualTo(ClinicalAiProviderId.OPENAI_COMPATIBLE)
                assertThat(result.modelId).isEqualTo(MODEL)
                assertThat(boundCalls.get()).isEqualTo(1)
                assertThat(server.requestCount).isEqualTo(1)
            }
        }
    }

    @Test
    fun everyCompatibleProtocolMapsWrongAlertResponseModelToIdentityMismatch() = runTest {
        OpenAiCompatibleProtocol.entries.forEach { protocol ->
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setBody(
                        when (protocol) {
                            OpenAiCompatibleProtocol.RESPONSES ->
                                responsesBody(VALID_ALERT_RESULT_JSON, model = "wrong-model")
                            OpenAiCompatibleProtocol.CHAT_COMPLETIONS ->
                                chatBody(VALID_ALERT_RESULT_JSON, model = "wrong-model")
                        }
                    )
                )

                val failure = runCatching {
                    gateway(server, protocol)
                        .analyzeAlert(
                            alertContextFixture(),
                            { SECRET },
                            alertAiTestNetworkLease()
                        )
                }.exceptionOrNull()

                assertThat(failure)
                    .isInstanceOf(ClinicalAiGatewayException.IdentityMismatch::class.java)
                assertThat(server.requestCount).isEqualTo(1)
            }
        }
    }

    @Test
    fun responsesUsesExactRouteBearerHeaderSharedEnvelopeAndCompatibleMetadata() = runTest {
        MockWebServer().use { server ->
            server.enqueue(responsesSuccess(validReportJson()))

            val result = gateway(server, OpenAiCompatibleProtocol.RESPONSES)
                .analyze(payload(datasetFixture()), { SECRET })

            val request = server.takeRequest()
            val body = requestBody(request)
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.path).isEqualTo("/v1/responses")
            assertBearerOnly(request)
            assertThat(body.get("model").asString).isEqualTo(MODEL)
            assertThat(body.get("store").asBoolean).isFalse()
            assertThat(body.get("max_output_tokens").asInt)
                .isEqualTo(ClinicalOpenAiClient.MAX_OUTPUT_TOKENS)
            val format = body.getAsJsonObject("text").getAsJsonObject("format")
            assertStrictFormat(
                format = format,
                name = ClinicalOpenAiClient.SCHEMA_NAME,
                schema = ClinicalOpenAiClient.strictSchemaJson()
            )
            assertCompatibleMetadata(result)
            assertThat(result.report.summary7dStatus).isEqualTo(ClinicalSummaryStatus.STABLE)
        }
    }

    @Test
    fun chatCompletionsUsesExactRouteMessagesAndStrictSharedSchema() = runTest {
        MockWebServer().use { server ->
            server.enqueue(chatSuccess(validReportJson()))

            val result = gateway(
                server,
                OpenAiCompatibleProtocol.CHAT_COMPLETIONS,
                basePath = "/v1"
            ).analyze(payload(datasetFixture()), { SECRET })

            val request = server.takeRequest()
            val body = requestBody(request)
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.path).isEqualTo("/v1/chat/completions")
            assertBearerOnly(request)
            assertThat(body.keySet()).containsExactly(
                "model",
                "messages",
                "max_completion_tokens",
                "response_format"
            )
            assertThat(body.get("model").asString).isEqualTo(MODEL)
            assertThat(body.get("max_completion_tokens").asInt)
                .isEqualTo(ClinicalOpenAiClient.MAX_OUTPUT_TOKENS)
            val messages = body.getAsJsonArray("messages")
            assertThat(messages).hasSize(2)
            assertThat(messages[0].asJsonObject.get("role").asString).isEqualTo("system")
            assertThat(messages[0].asJsonObject.get("content").asString)
                .isEqualTo(ClinicalOpenAiClient.CLINICAL_INSTRUCTIONS)
            assertThat(messages[1].asJsonObject.get("role").asString).isEqualTo("user")
            assertThat(messages[1].asJsonObject.get("content").asString)
                .contains("clinical_dataset")
            assertStrictChatFormat(
                responseFormat = body.getAsJsonObject("response_format"),
                name = ClinicalOpenAiClient.SCHEMA_NAME,
                schema = ClinicalOpenAiClient.strictSchemaJson()
            )
            assertCompatibleMetadata(result)
            assertThat(result.report.summary7dStatus).isEqualTo(ClinicalSummaryStatus.STABLE)
        }
    }

    @Test
    fun endpointAppendsVersionExactlyOnceAndPreservesSafePrefixes() = runTest {
        data class Case(
            val protocol: OpenAiCompatibleProtocol,
            val basePath: String,
            val expectedPath: String
        )
        val cases = listOf(
            Case(OpenAiCompatibleProtocol.RESPONSES, "/", "/v1/responses"),
            Case(OpenAiCompatibleProtocol.RESPONSES, "/v1", "/v1/responses"),
            Case(OpenAiCompatibleProtocol.RESPONSES, "/safe/prefix", "/safe/prefix/v1/responses"),
            Case(OpenAiCompatibleProtocol.RESPONSES, "/safe/prefix/v1", "/safe/prefix/v1/responses"),
            Case(
                OpenAiCompatibleProtocol.CHAT_COMPLETIONS,
                "/",
                "/v1/chat/completions"
            ),
            Case(
                OpenAiCompatibleProtocol.CHAT_COMPLETIONS,
                "/v1",
                "/v1/chat/completions"
            ),
            Case(
                OpenAiCompatibleProtocol.CHAT_COMPLETIONS,
                "/safe/prefix",
                "/safe/prefix/v1/chat/completions"
            ),
            Case(
                OpenAiCompatibleProtocol.CHAT_COMPLETIONS,
                "/safe/prefix/v1",
                "/safe/prefix/v1/chat/completions"
            )
        )

        cases.forEach { case ->
            MockWebServer().use { server ->
                server.enqueue(success(case.protocol, validReportJson()))

                gateway(server, case.protocol, case.basePath)
                    .analyze(payload(datasetFixture()), { SECRET })

                assertThat(server.takeRequest().path).isEqualTo(case.expectedPath)
                assertThat(server.requestCount).isEqualTo(1)
            }
        }
    }

    @Test
    fun endpointPolicyRejectsUnsafeEndpointsAndWrongProviderBeforeNetwork() {
        MockWebServer().use { server ->
            val port = server.port
            val rejected = listOf(
                "http://user:password@127.0.0.1:$port",
                "http://127.0.0.1:$port?credential=$SECRET",
                "http://127.0.0.1:$port#fragment",
                "http://127.0.0.1:$port/safe/%2e%2e/escape",
                "http://127.0.0.1:$port/safe/%2Fescape",
                "http://example.com:$port"
            )
            rejected.forEach { endpoint ->
                assertThrows(IllegalArgumentException::class.java) {
                    compatibleConfig(endpoint, OpenAiCompatibleProtocol.RESPONSES)
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                OpenAiCompatibleClinicalAiGateway(ClinicalAiProviderConfig.defaultOpenAi())
            }
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun protocolsProduceIdenticalReportAndProviderModelIdentity() = runTest {
        val payload = payload(datasetFixture())
        lateinit var responsesResult: ClinicalOpenAiResult
        lateinit var chatResult: ClinicalOpenAiResult
        MockWebServer().use { server ->
            server.enqueue(responsesSuccess(validReportJson()))
            responsesResult = gateway(server, OpenAiCompatibleProtocol.RESPONSES)
                .analyze(payload, { SECRET })
        }
        MockWebServer().use { server ->
            server.enqueue(chatSuccess(validReportJson()))
            chatResult = gateway(server, OpenAiCompatibleProtocol.CHAT_COMPLETIONS)
                .analyze(payload, { SECRET })
        }

        assertThat(chatResult.report).isEqualTo(responsesResult.report)
        assertThat(chatResult.metadata.providerId).isEqualTo(responsesResult.metadata.providerId)
        assertThat(chatResult.metadata.requestedProviderId)
            .isEqualTo(responsesResult.metadata.requestedProviderId)
        assertThat(chatResult.metadata.model).isEqualTo(responsesResult.metadata.model)
        assertThat(chatResult.metadata.requestedModel)
            .isEqualTo(responsesResult.metadata.requestedModel)
        assertThat(chatResult.metadata.requestHash).isEqualTo(responsesResult.metadata.requestHash)
        assertThat(chatResult.metadata.schemaName).isEqualTo(responsesResult.metadata.schemaName)
        assertThat(chatResult.metadata.schemaVersion)
            .isEqualTo(responsesResult.metadata.schemaVersion)
    }

    @Test
    fun everyCompatibleProtocolRejectsForbiddenNestedCommandEnvelope() = runTest {
        OpenAiCompatibleProtocol.entries.forEach { protocol ->
            MockWebServer().use { server ->
                val report = JsonParser.parseString(validReportJson()).asJsonObject.apply {
                    add(
                        "providerExtension",
                        JsonObject().apply {
                            add(
                                "nested",
                                JsonObject().apply {
                                    addProperty("calibrationCommand", "apply")
                                }
                            )
                        }
                    )
                }
                server.enqueue(success(protocol, report.toString()))

                val failure = analyzeFailure(server, protocol)

                assertThat(failure)
                    .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
                assertSanitized(failure)
                assertThat(server.requestCount).isEqualTo(1)
            }
        }
    }

    @Test
    fun connectionRequestsAreBoundedStrictAndContainNoMedicalDataOrHashes() = runTest {
        OpenAiCompatibleProtocol.entries.forEach { protocol ->
            MockWebServer().use { server ->
                server.enqueue(success(protocol, """{"ok":true}"""))
                val medicalPayload = payload(datasetFixture())

                val result = gateway(server, protocol).testConnection(SECRET)

                val request = server.takeRequest()
                val bodyText = request.body.readUtf8()
                val body = JsonParser.parseString(bodyText).asJsonObject
                assertThat(result.status).isEqualTo(ClinicalAiConnectionStatus.SUCCESS)
                assertThat(result.providerId)
                    .isEqualTo(ClinicalAiProviderId.OPENAI_COMPATIBLE)
                assertThat(result.modelId).isEqualTo(MODEL)
                assertThat(bodyText.toByteArray(Charsets.UTF_8).size)
                    .isAtMost(OpenAiCompatibleClinicalAiGateway.MAX_CONNECTION_REQUEST_BYTES)
                assertThat(bodyText).doesNotContain(medicalPayload.compactJson)
                assertThat(bodyText).doesNotContain(medicalPayload.sha256)
                assertThat(bodyText).doesNotContain(
                    medicalPayload.dataset.generatedAt.toString()
                )
                assertBearerOnly(request)
                when (protocol) {
                    OpenAiCompatibleProtocol.RESPONSES -> {
                        assertThat(body.get("store").asBoolean).isFalse()
                        assertThat(body.get("max_output_tokens").asInt).isAtMost(1_024)
                        assertStrictConnectionSchema(
                            body.getAsJsonObject("text").getAsJsonObject("format")
                        )
                    }

                    OpenAiCompatibleProtocol.CHAT_COMPLETIONS -> {
                        assertThat(body.get("max_completion_tokens").asInt).isAtMost(1_024)
                        assertThat(body.getAsJsonArray("messages")).hasSize(2)
                        assertStrictChatConnectionSchema(
                            body.getAsJsonObject("response_format")
                        )
                    }
                }
            }
        }
    }

    @Test
    fun forcedMapReduceUsesCommonPipelineAndReadsModelAndCredentialPerRequest() = runTest {
        OpenAiCompatibleProtocol.entries.forEach { protocol ->
            MockWebServer().use { server ->
                val bodies = CopyOnWriteArrayList<JsonObject>()
                server.dispatcher = structuredDispatcher(protocol, bodies)
                val dataset = datasetFixture(days = 4, rowsPerDay = 24)
                val config = compatibleConfig(server.url("/").toString(), protocol)
                val directBytes =
                    OpenAiCompatibleClinicalAiGateway.requestBytesForTest(dataset, config)
                var credentialReads = 0

                val result = OpenAiCompatibleClinicalAiGateway(
                    config = config,
                    requestByteBudget = directBytes - 1
                ).analyze(payload(dataset), {
                    credentialReads += 1
                    "rotated-$credentialReads"
                })

                val requests = (0 until server.requestCount).map { server.takeRequest() }
                assertThat(result.metadata.usedSynthesis).isTrue()
                assertThat(result.metadata.chunkCount).isGreaterThan(1)
                assertThat(result.metadata.reductionLevels).isGreaterThan(0)
                assertThat(result.metadata.coverageLedgerHash).hasLength(64)
                assertCompatibleMetadata(result)
                assertThat(credentialReads).isEqualTo(requests.size)
                assertThat(bodies).hasSize(requests.size)
                requests.forEachIndexed { index, request ->
                    assertThat(request.getHeader("Authorization"))
                        .isEqualTo("Bearer rotated-${index + 1}")
                    assertThat(request.requestUrl?.query).isNull()
                }
                bodies.forEach { body ->
                    assertThat(body.get("model").asString).isEqualTo(MODEL)
                    if (protocol == OpenAiCompatibleProtocol.CHAT_COMPLETIONS) {
                        val responseFormat = body.getAsJsonObject("response_format")
                        assertThat(responseFormat.keySet())
                            .containsExactly("type", "json_schema")
                        assertThat(responseFormat.get("type").asString)
                            .isEqualTo("json_schema")
                        assertThat(responseFormat.getAsJsonObject("json_schema").keySet())
                            .containsExactly("name", "strict", "schema")
                    }
                    assertThat(body.toString().toByteArray(Charsets.UTF_8).size)
                        .isAtMost(directBytes - 1)
                }
                assertThat(userInput(bodies.last(), protocol))
                    .contains("clinical_map_reduce_synthesis")
                assertThat(userInput(bodies.last(), protocol)).doesNotContain("\"g30\":[")
            }
        }
    }

    @Test
    fun chatFinishReasonsRefusalAndExplicitRefusalMapStrictly() = runTest {
        val cases = listOf(
            "length" to ClinicalOpenAiFailureKind.INCOMPLETE,
            "max_tokens" to ClinicalOpenAiFailureKind.INCOMPLETE,
            "content_filter" to ClinicalOpenAiFailureKind.REFUSAL,
            "refusal" to ClinicalOpenAiFailureKind.REFUSAL,
            "future_reason" to ClinicalOpenAiFailureKind.INVALID_RESPONSE
        )
        cases.forEach { (finishReason, kind) ->
            MockWebServer().use { server ->
                server.enqueue(
                    chatSuccess(
                        output = "",
                        finishReason = finishReason,
                        content = "null"
                    )
                )

                assertFailure(server, OpenAiCompatibleProtocol.CHAT_COMPLETIONS, kind)
            }
        }

        MockWebServer().use { server ->
            server.enqueue(
                chatSuccess(
                    output = "",
                    finishReason = "stop",
                    content = "null",
                    refusal = jsonString("Request declined.")
                )
            )
            assertFailure(
                server,
                OpenAiCompatibleProtocol.CHAT_COMPLETIONS,
                ClinicalOpenAiFailureKind.REFUSAL
            )
        }
    }

    @Test
    fun chatRejectsCardinalityRoleContentAndUnknownConsumedShapes() = runTest {
        val valid = jsonString(validReportJson())
        val malformed = listOf(
            chatBody(validReportJson(), choices = "[]"),
            chatBody(
                validReportJson(),
                choices =
                    """[${chatChoice(valid)},${chatChoice(valid, index = 1)}]"""
            ),
            chatBody(validReportJson(), role = "user"),
            chatBody(validReportJson(), content = "null"),
            chatBody(validReportJson(), content = """{"text":$valid}"""),
            chatBody(validReportJson(), content = jsonString("   ")),
            chatBody(validReportJson(), choiceExtra = ""","unexpected":true"""),
            chatBody(validReportJson(), messageExtra = ""","unexpected":true"""),
            chatBody(validReportJson(), rootExtra = ""","unexpected":true"""),
            "{not-json"
        )
        malformed.forEach { response ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(response))
                assertFailure(
                    server,
                    OpenAiCompatibleProtocol.CHAT_COMPLETIONS,
                    ClinicalOpenAiFailureKind.INVALID_RESPONSE
                )
            }
        }
    }

    @Test
    fun responsesReusesSharedParserForRefusalIncompleteAndMalformedOutput() = runTest {
        val cases = listOf(
            responsesBody("", statusJson = jsonString("incomplete")) to
                ClinicalOpenAiFailureKind.INCOMPLETE,
            """
            {
              "status":"completed",
              "model":${jsonString(MODEL)},
              "output":[{
                "content":[{"type":"refusal","refusal":"Request declined."}]
              }]
            }
            """.trimIndent() to ClinicalOpenAiFailureKind.REFUSAL,
            responsesBody("not-json") to ClinicalOpenAiFailureKind.INVALID_RESPONSE
        )
        cases.forEach { (response, kind) ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(response))
                assertFailure(server, OpenAiCompatibleProtocol.RESPONSES, kind)
            }
        }
    }

    @Test
    fun responsesRequiresCompletedStringStatusBeforeSharedParsing() = runTest {
        val cases = listOf(
            responsesBody(validReportJson(), statusJson = null) to
                ClinicalOpenAiFailureKind.INVALID_RESPONSE,
            responsesBody(validReportJson(), statusJson = "null") to
                ClinicalOpenAiFailureKind.INVALID_RESPONSE,
            responsesBody(validReportJson(), statusJson = "1") to
                ClinicalOpenAiFailureKind.INVALID_RESPONSE,
            responsesBody(validReportJson(), statusJson = jsonString("future_status")) to
                ClinicalOpenAiFailureKind.INVALID_RESPONSE,
            responsesBody("", statusJson = jsonString("in_progress")) to
                ClinicalOpenAiFailureKind.INCOMPLETE,
            responsesBody("", statusJson = jsonString("failed")) to
                ClinicalOpenAiFailureKind.INCOMPLETE,
            responsesBody("", statusJson = jsonString("incomplete")) to
                ClinicalOpenAiFailureKind.INCOMPLETE,
            responsesBody("", statusJson = jsonString("cancelled")) to
                ClinicalOpenAiFailureKind.INCOMPLETE,
            responsesBody("", statusJson = jsonString("queued")) to
                ClinicalOpenAiFailureKind.INCOMPLETE
        )
        cases.forEach { (response, kind) ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(response))
                assertFailure(server, OpenAiCompatibleProtocol.RESPONSES, kind)
            }
        }
    }

    @Test
    fun completedResponsesPermitOnlyAbsentOrNullConflictFields() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    responsesBody(
                        output = validReportJson(),
                        errorJson = "null",
                        incompleteDetailsJson = "null"
                    )
                )
            )

            val result = gateway(server, OpenAiCompatibleProtocol.RESPONSES)
                .analyze(payload(datasetFixture()), { SECRET })

            assertCompatibleMetadata(result)
            assertThat(result.report.summary7dStatus).isEqualTo(ClinicalSummaryStatus.STABLE)
        }

        listOf(
            responsesBody(
                output = validReportJson(),
                errorJson = """{"code":"server_error","message":"conflict"}"""
            ),
            responsesBody(
                output = validReportJson(),
                incompleteDetailsJson = """{"reason":"max_output_tokens"}"""
            )
        ).forEach { response ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(response))
                assertFailure(
                    server,
                    OpenAiCompatibleProtocol.RESPONSES,
                    ClinicalOpenAiFailureKind.INVALID_RESPONSE
                )
            }
        }
    }

    @Test
    fun schemaFailureHasNoPlainTextOrProtocolFallback() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("unsupported schema"))
            server.enqueue(chatSuccess(validReportJson()))

            assertFailure(
                server,
                OpenAiCompatibleProtocol.CHAT_COMPLETIONS,
                ClinicalOpenAiFailureKind.HTTP
            )

            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun httpFailuresRedirectsAndRetriesAreProviderNeutral() = runTest {
        val cases = listOf(
            401 to ClinicalOpenAiFailureKind.UNAUTHORIZED,
            403 to ClinicalOpenAiFailureKind.UNAUTHORIZED,
            429 to ClinicalOpenAiFailureKind.RATE_LIMITED,
            500 to ClinicalOpenAiFailureKind.SERVER,
            503 to ClinicalOpenAiFailureKind.SERVER,
            302 to ClinicalOpenAiFailureKind.HTTP
        )
        OpenAiCompatibleProtocol.entries.forEach { protocol ->
            cases.forEach { (code, kind) ->
                MockWebServer().use { server ->
                    server.enqueue(
                        MockResponse()
                            .setResponseCode(code)
                            .setHeader("Location", server.url("/redirected"))
                            .setHeader("Retry-After", "0")
                            .setBody("credential=$SECRET")
                    )

                    assertFailure(server, protocol, kind)

                    assertThat(server.requestCount).isEqualTo(1)
                }
            }
        }
    }

    @Test
    fun timeoutNetworkAndOversizedFailuresAreProviderNeutralAndSanitized() = runTest {
        OpenAiCompatibleProtocol.entries.forEach { protocol ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val failure = analyzeFailure(
                    server = server,
                    protocol = protocol,
                    timeouts = ClinicalOpenAiTimeouts(
                        connectMillis = 100,
                        writeMillis = 100,
                        readMillis = 100,
                        callMillis = 1_000
                    )
                )
                assertThat(failure)
                    .isInstanceOf(ClinicalOpenAiException.Timeout::class.java)
                assertSanitized(failure)
            }

            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("x".repeat(2_048)))
                val failure = analyzeFailure(
                    server = server,
                    protocol = protocol,
                    maxResponseBytes = 1_024
                )
                assertThat(failure)
                    .isInstanceOf(ClinicalOpenAiException.OversizedResponse::class.java)
                assertSanitized(failure)
            }

            val server = MockWebServer()
            server.start()
            val gateway = gateway(server, protocol)
            server.shutdown()
            val failure = runCatching {
                gateway.analyze(payload(datasetFixture()), { SECRET })
            }.exceptionOrNull()
            assertThat(failure)
                .isInstanceOf(ClinicalOpenAiException.NetworkFailure::class.java)
            assertSanitized(failure)
        }
    }

    @Test
    fun malformedCredentialIsSanitizedAndRejectedBeforeRequest() = runTest {
        OpenAiCompatibleProtocol.entries.forEach { protocol ->
            MockWebServer().use { server ->
                val malformed = "bad\n$SECRET"

                val failure = runCatching {
                    gateway(server, protocol)
                        .analyze(payload(datasetFixture()), { malformed })
                }.exceptionOrNull()

                assertThat(failure)
                    .isInstanceOf(ClinicalOpenAiException.CredentialUnavailable::class.java)
                assertThat(failure.toString()).doesNotContain(malformed)
                assertSanitized(failure)
                assertThat(server.requestCount).isEqualTo(0)
            }
        }
    }

    private fun gateway(
        server: MockWebServer,
        protocol: OpenAiCompatibleProtocol,
        basePath: String = "/",
        requestByteBudget: Int = 256 * 1_024,
        maxResponseBytes: Int = 64 * 1_024,
        timeouts: ClinicalOpenAiTimeouts = ClinicalOpenAiTimeouts()
    ): OpenAiCompatibleClinicalAiGateway = OpenAiCompatibleClinicalAiGateway(
        config = compatibleConfig(server.url(basePath).toString(), protocol),
        requestByteBudget = requestByteBudget,
        maxResponseBytes = maxResponseBytes,
        timeouts = timeouts
    )

    private fun compatibleConfig(
        endpoint: String,
        protocol: OpenAiCompatibleProtocol
    ): ClinicalAiProviderConfig = ClinicalAiProviderConfig.normalized(
        providerId = ClinicalAiProviderId.OPENAI_COMPATIBLE,
        modelId = MODEL,
        endpoint = endpoint,
        compatibleProtocol = protocol
    )

    private fun structuredDispatcher(
        protocol: OpenAiCompatibleProtocol,
        bodies: MutableList<JsonObject>
    ): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = requestBody(request)
            bodies += body
            val schema = requestSchema(body, protocol)
            val output = if (schema.getAsJsonObject("properties").has("findings")) {
                validChunkJson()
            } else {
                validReportJson()
            }
            return success(protocol, output)
        }
    }

    private fun requestSchema(
        body: JsonObject,
        protocol: OpenAiCompatibleProtocol
    ): JsonObject = when (protocol) {
        OpenAiCompatibleProtocol.RESPONSES ->
            body.getAsJsonObject("text")
                .getAsJsonObject("format")
                .getAsJsonObject("schema")

        OpenAiCompatibleProtocol.CHAT_COMPLETIONS ->
            body.getAsJsonObject("response_format")
                .getAsJsonObject("json_schema")
                .getAsJsonObject("schema")
    }

    private fun userInput(
        body: JsonObject,
        protocol: OpenAiCompatibleProtocol
    ): String = when (protocol) {
        OpenAiCompatibleProtocol.RESPONSES -> body.get("input").asString
        OpenAiCompatibleProtocol.CHAT_COMPLETIONS ->
            body.getAsJsonArray("messages")[1].asJsonObject.get("content").asString
    }

    private fun assertBearerOnly(request: RecordedRequest) {
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer $SECRET")
        assertThat(request.getHeader("x-api-key")).isNull()
        assertThat(request.getHeader("api-key")).isNull()
        assertThat(request.requestUrl?.query).isNull()
        assertThat(request.body.clone().readUtf8()).doesNotContain(SECRET)
        assertThat(request.getHeader("Content-Type")).startsWith("application/json")
    }

    private fun assertStrictFormat(format: JsonObject, name: String, schema: JsonObject) {
        assertThat(format.keySet()).containsExactly("type", "name", "strict", "schema")
        assertThat(format.get("type").asString).isEqualTo("json_schema")
        assertThat(format.get("name").asString).isEqualTo(name)
        assertThat(format.get("strict").asBoolean).isTrue()
        assertThat(format.getAsJsonObject("schema")).isEqualTo(schema)
    }

    private fun assertStrictChatFormat(
        responseFormat: JsonObject,
        name: String,
        schema: JsonObject
    ) {
        assertThat(responseFormat.keySet()).containsExactly("type", "json_schema")
        assertThat(responseFormat.get("type").asString).isEqualTo("json_schema")
        val jsonSchema = responseFormat.getAsJsonObject("json_schema")
        assertThat(jsonSchema.keySet()).containsExactly("name", "strict", "schema")
        assertThat(jsonSchema.get("name").asString).isEqualTo(name)
        assertThat(jsonSchema.get("strict").asBoolean).isTrue()
        assertThat(jsonSchema.getAsJsonObject("schema")).isEqualTo(schema)
    }

    private fun assertStrictConnectionSchema(format: JsonObject) {
        assertStrictFormat(
            format = format,
            name = "clinical_ai_connection_test_v1",
            schema = format.getAsJsonObject("schema")
        )
        val schema = format.getAsJsonObject("schema")
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
        assertThat(schema.getAsJsonObject("properties").getAsJsonObject("ok").keySet())
            .containsExactly("type", "const")
    }

    private fun assertStrictChatConnectionSchema(responseFormat: JsonObject) {
        val schema = responseFormat.getAsJsonObject("json_schema").getAsJsonObject("schema")
        assertStrictChatFormat(
            responseFormat = responseFormat,
            name = "clinical_ai_connection_test_v1",
            schema = schema
        )
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
        assertThat(schema.getAsJsonObject("properties").getAsJsonObject("ok").keySet())
            .containsExactly("type", "const")
    }

    private fun assertCompatibleMetadata(result: ClinicalOpenAiResult) {
        assertThat(result.metadata.providerId)
            .isEqualTo(ClinicalAiProviderId.OPENAI_COMPATIBLE)
        assertThat(result.metadata.requestedProviderId)
            .isEqualTo(ClinicalAiProviderId.OPENAI_COMPATIBLE)
        assertThat(result.metadata.model).isEqualTo(MODEL)
        assertThat(result.metadata.requestedModel).isEqualTo(MODEL)
    }

    private suspend fun assertFailure(
        server: MockWebServer,
        protocol: OpenAiCompatibleProtocol,
        kind: ClinicalOpenAiFailureKind
    ) {
        val failure = analyzeFailure(server, protocol)
        assertThat(failure).isInstanceOf(ClinicalOpenAiException::class.java)
        assertThat((failure as ClinicalOpenAiException).kind).isEqualTo(kind)
        assertSanitized(failure)
    }

    private suspend fun analyzeFailure(
        server: MockWebServer,
        protocol: OpenAiCompatibleProtocol,
        maxResponseBytes: Int = 64 * 1_024,
        timeouts: ClinicalOpenAiTimeouts = ClinicalOpenAiTimeouts()
    ): Throwable? = runCatching {
        gateway(
            server = server,
            protocol = protocol,
            maxResponseBytes = maxResponseBytes,
            timeouts = timeouts
        ).analyze(payload(datasetFixture()), { SECRET })
    }.exceptionOrNull()

    private fun success(
        protocol: OpenAiCompatibleProtocol,
        output: String
    ): MockResponse = when (protocol) {
        OpenAiCompatibleProtocol.RESPONSES -> responsesSuccess(output)
        OpenAiCompatibleProtocol.CHAT_COMPLETIONS -> chatSuccess(output)
    }

    private fun responsesSuccess(output: String): MockResponse =
        MockResponse().setBody(responsesBody(output))

    private fun responsesBody(
        output: String,
        model: String = MODEL,
        statusJson: String? = jsonString("completed"),
        errorJson: String? = null,
        incompleteDetailsJson: String? = null
    ): String =
        """
        {
          ${statusJson?.let { """"status":$it,""" }.orEmpty()}
          "model":${jsonString(model)},
          "system_fingerprint":"compatible-fingerprint",
          "output_text":${jsonString(output)}
          ${errorJson?.let { ""","error":$it""" }.orEmpty()}
          ${incompleteDetailsJson?.let { ""","incomplete_details":$it""" }.orEmpty()}
        }
        """.trimIndent()

    private fun chatSuccess(
        output: String,
        finishReason: String = "stop",
        content: String = jsonString(output),
        refusal: String = "null"
    ): MockResponse = MockResponse().setBody(
        chatBody(
            output = output,
            finishReason = finishReason,
            content = content,
            refusal = refusal
        )
    )

    private fun chatBody(
        output: String,
        model: String = MODEL,
        finishReason: String = "stop",
        role: String = "assistant",
        content: String = jsonString(output),
        refusal: String = "null",
        choices: String? = null,
        choiceExtra: String = "",
        messageExtra: String = "",
        rootExtra: String = ""
    ): String {
        val renderedChoice = chatChoice(
            content = content,
            finishReason = finishReason,
            role = role,
            refusal = refusal,
            choiceExtra = choiceExtra,
            messageExtra = messageExtra
        )
        return """
        {
          "id":"chatcmpl-compatible",
          "object":"chat.completion",
          "created":1780000000,
          "model":${jsonString(model)},
          "choices":${choices ?: "[$renderedChoice]"},
          "usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30},
          "system_fingerprint":"compatible-fingerprint"
          $rootExtra
        }
        """.trimIndent()
    }

    private fun chatChoice(
        content: String,
        finishReason: String = "stop",
        role: String = "assistant",
        refusal: String = "null",
        index: Int = 0,
        choiceExtra: String = "",
        messageExtra: String = ""
    ): String =
        """
        {
          "index":$index,
          "message":{
            "role":${jsonString(role)},
            "content":$content,
            "refusal":$refusal
            $messageExtra
          },
          "logprobs":null,
          "finish_reason":${jsonString(finishReason)}
          $choiceExtra
        }
        """.trimIndent()

    private fun requestBody(request: RecordedRequest): JsonObject =
        JsonParser.parseString(request.body.clone().readUtf8()).asJsonObject

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
                    ClinicalTelemetryPoint(it.ts, "compatible-test-marker", 1.0, "OK")
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
        const val MODEL = "local-compatible-model"
        const val SECRET = "compatible-super-secret"
        const val DAY_MS = 24L * 60L * 60L * 1_000L
    }
}
