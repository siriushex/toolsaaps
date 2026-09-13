package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.aaps.copilot.config.ClinicalAiProviderId
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertThrows
import org.junit.Test

class AnthropicClinicalAiGatewayTest {

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
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertThat(request.path).isEqualTo("/v1/messages")
            assertThat(request.getHeader("x-api-key")).isEqualTo(SECRET)
            assertThat(body.get("model").asString).isEqualTo(MODEL)
            assertThat(body.get("max_tokens").asInt)
                .isEqualTo(ClinicalOpenAiClient.ALERT_CAUSE_MAX_OUTPUT_TOKENS)
            val schema = requestSchema(body)
            assertThat(schema.get("additionalProperties").asBoolean).isFalse()
            assertThat(schema.getAsJsonArray("required").map { it.asString })
                .containsExactly(
                    "schemaVersion",
                    "primaryCauseCode",
                    "confidence",
                    "evidenceCodes",
                    "adviceCode"
                )
            assertThat(schema.getAsJsonObject("properties").keySet()).containsExactly(
                "schemaVersion",
                "primaryCauseCode",
                "confidence",
                "evidenceCodes",
                "adviceCode"
            )
            assertAnthropicSchemaCompatible(schema)
            assertThat(
                body.getAsJsonArray("messages").single().asJsonObject
                    .getAsJsonArray("content").single().asJsonObject
                    .get("text").asString
            ).isEqualTo(alertContextFixture().canonicalJson)
            assertThat(result.providerId).isEqualTo(ClinicalAiProviderId.ANTHROPIC)
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
                MockResponse().setBody(
                    responseBody(VALID_ALERT_RESULT_JSON, model = "wrong-model")
                )
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
    fun nativeMessagesRequestUsesExactRouteHeadersModelAndJsonSchema() = runTest {
        MockWebServer().use { server ->
            server.enqueue(successResponse(validReportJson()))
            val gateway = testGateway(server)

            val result = gateway.analyze(payload(datasetFixture()), { SECRET })

            val request = server.takeRequest()
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.path).isEqualTo("/v1/messages")
            assertThat(request.getHeader("x-api-key")).isEqualTo(SECRET)
            assertThat(request.getHeader("anthropic-version"))
                .isEqualTo(AnthropicClinicalAiGateway.ANTHROPIC_VERSION)
            assertThat(request.getHeader("Content-Type")).startsWith("application/json")
            assertThat(request.getHeader("Authorization")).isNull()
            assertThat(body.get("model").asString).isEqualTo(MODEL)
            assertThat(body.has("tools")).isFalse()
            assertThat(body.getAsJsonArray("messages")).hasSize(1)
            assertThat(
                body.getAsJsonObject("output_config")
                    .getAsJsonObject("format")
                    .get("type")
                    .asString
            ).isEqualTo("json_schema")
            val schema = requestSchema(body)
            assertThat(schema.getAsJsonObject("properties").has("summary7dStatus")).isTrue()
            assertThat(schema.keySet()).containsExactly(
                "type",
                "additionalProperties",
                "properties",
                "required"
            )
            val summaryStatus = schema.getAsJsonObject("properties")
                .getAsJsonObject("summary7dStatus")
            assertThat(summaryStatus.keySet()).containsExactly("type", "enum")
            val patterns = schema.getAsJsonObject("properties")
                .getAsJsonObject("patterns")
            assertThat(patterns.keySet()).containsExactly("type", "items")
            val recommendations = schema.getAsJsonObject("properties")
                .getAsJsonObject("recommendations")
            assertThat(recommendations.keySet()).containsExactly("type", "items")
            assertThat(recommendations.getAsJsonObject("items").keySet()).containsExactly(
                "type",
                "additionalProperties",
                "properties",
                "required"
            )
            assertAnthropicSchemaCompatible(schema)
            assertThat(result.metadata.providerId).isEqualTo(ClinicalAiProviderId.ANTHROPIC)
            assertThat(result.metadata.requestedProviderId)
                .isEqualTo(ClinicalAiProviderId.ANTHROPIC)
            assertThat(result.metadata.model).isEqualTo(MODEL)
            assertThat(result.metadata.requestedModel).isEqualTo(MODEL)
        }
    }

    @Test
    fun testConnectionIsBoundedAndContainsNoMedicalPayloadOrHashes() = runTest {
        MockWebServer().use { server ->
            server.enqueue(successResponse("""{"ok":true}"""))
            val gateway = testGateway(server)
            val medical = datasetFixture()
            val medicalPayload = payload(medical)

            val result = gateway.testConnection(SECRET)

            val request = server.takeRequest()
            val bodyText = request.body.readUtf8()
            val body = JsonParser.parseString(bodyText).asJsonObject
            assertThat(result.status).isEqualTo(ClinicalAiConnectionStatus.SUCCESS)
            assertThat(result.providerId).isEqualTo(ClinicalAiProviderId.ANTHROPIC)
            assertThat(result.modelId).isEqualTo(MODEL)
            assertThat(bodyText.toByteArray(Charsets.UTF_8).size)
                .isAtMost(AnthropicClinicalAiGateway.MAX_CONNECTION_REQUEST_BYTES)
            assertThat(bodyText).doesNotContain(medicalPayload.sha256)
            assertThat(bodyText).doesNotContain(medicalPayload.compactJson)
            assertThat(bodyText).doesNotContain(medical.generatedAt.toString())
            assertThat(body.getAsJsonArray("messages").single().asJsonObject.toString())
                .contains("Connection test")
            assertThat(
                body.getAsJsonObject("output_config")
                    .getAsJsonObject("format")
                    .getAsJsonObject("schema")
                    .getAsJsonObject("properties")
                    .keySet()
            ).containsExactly("ok")
            assertThat(
                requestSchema(body)
                    .getAsJsonObject("properties")
                    .getAsJsonObject("ok")
                    .keySet()
            ).containsExactly("type", "const")
            assertThat(
                requestSchema(body)
                    .getAsJsonObject("properties")
                    .getAsJsonObject("ok")
                    .get("const")
                    .asBoolean
            ).isTrue()
            assertAnthropicSchemaCompatible(requestSchema(body))
            assertThat(request.getHeader("Authorization")).isNull()
        }
    }

    @Test
    fun forcedMapReduceUsesAnthropicEnvelopeForEveryCallAndPreservesCoverage() = runTest {
        MockWebServer().use { server ->
            val bodies = CopyOnWriteArrayList<JsonObject>()
            val progress = CopyOnWriteArrayList<ClinicalOpenAiProgress>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = JsonParser.parseString(request.body.clone().readUtf8()).asJsonObject
                    bodies += body
                    val schema = body.getAsJsonObject("output_config")
                        .getAsJsonObject("format")
                        .getAsJsonObject("schema")
                    return if (schema.getAsJsonObject("properties").has("findings")) {
                        successResponse(validChunkJson())
                    } else {
                        successResponse(validReportJson())
                    }
                }
            }
            val dataset = datasetFixture(days = 4, rowsPerDay = 24)
            val directBytes = AnthropicClinicalAiGateway.requestBytesForTest(dataset, MODEL)
            val gateway = testGateway(server, requestByteBudget = directBytes - 1)

            val result = gateway.analyze(
                payload(dataset),
                { SECRET },
                ClinicalOpenAiProgressCallback(progress::add)
            )

            assertThat(result.metadata.usedSynthesis).isTrue()
            assertThat(result.metadata.chunkCount).isGreaterThan(1)
            assertThat(result.metadata.reductionLevels).isGreaterThan(0)
            assertThat(result.metadata.coverageLedgerHash).hasLength(64)
            assertThat(bodies.size).isGreaterThan(result.metadata.chunkCount + 1)
            assertThat(progress.map { it.stage }).containsAtLeast(
                ClinicalOpenAiProgressStage.ANALYZING_CHUNK,
                ClinicalOpenAiProgressStage.REDUCING,
                ClinicalOpenAiProgressStage.SYNTHESIZING,
                ClinicalOpenAiProgressStage.VALIDATING,
                ClinicalOpenAiProgressStage.COMPLETED
            )
            bodies.forEach { body ->
                assertThat(body.get("model").asString).isEqualTo(MODEL)
                assertThat(body.has("tools")).isFalse()
                assertThat(body.getAsJsonObject("output_config").has("format")).isTrue()
                assertAnthropicSchemaCompatible(requestSchema(body))
                assertThat(body.toString().toByteArray(Charsets.UTF_8).size)
                    .isAtMost(directBytes - 1)
            }
            val finalInput = messageText(bodies.last())
            assertThat(finalInput).contains("clinical_map_reduce_synthesis")
            assertThat(finalInput).doesNotContain("\"g30\":[")
        }
    }

    @Test
    fun localFinalParserRejectsOutOfRangeValueRemovedFromAnthropicWireSchema() = runTest {
        MockWebServer().use { server ->
            val report = JsonParser.parseString(validReportJson()).asJsonObject.apply {
                getAsJsonArray("patterns")
                    .single()
                    .asJsonObject
                    .addProperty("evidenceValue", 101.0)
            }
            server.enqueue(successResponse(report.toString()))

            val failure = runCatching {
                testGateway(server).analyze(payload(datasetFixture()), { SECRET })
            }.exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
            assertSanitized(failure)
        }
    }

    @Test
    fun nativeMessagesGatewayRejectsForbiddenNestedCommandEnvelope() = runTest {
        MockWebServer().use { server ->
            val report = JsonParser.parseString(validReportJson()).asJsonObject.apply {
                add(
                    "providerExtension",
                    JsonObject().apply {
                        add(
                            "nested",
                            JsonObject().apply {
                                addProperty("gramsOfCarbs", 20.0)
                            }
                        )
                    }
                )
            }
            server.enqueue(successResponse(report.toString()))

            val failure = runCatching {
                testGateway(server).analyze(payload(datasetFixture()), { SECRET })
            }.exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
            assertSanitized(failure)
        }
    }

    @Test
    fun localChunkParserRejectsTooManyItemsRemovedFromAnthropicWireSchema() = runTest {
        MockWebServer().use { server ->
            val chunk = JsonParser.parseString(validChunkJson()).asJsonObject.apply {
                val findings = getAsJsonArray("findings")
                findings.add(findings.single().deepCopy())
            }
            server.enqueue(successResponse(chunk.toString()))
            val dataset = datasetFixture(days = 4, rowsPerDay = 24)
            val budget = AnthropicClinicalAiGateway.requestBytesForTest(dataset, MODEL) - 1

            val failure = runCatching {
                testGateway(server, requestByteBudget = budget)
                    .analyze(payload(dataset), { SECRET })
            }.exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(ClinicalOpenAiException.ChunkFailed::class.java)
            assertThat((failure as ClinicalOpenAiException).kind)
                .isEqualTo(ClinicalOpenAiFailureKind.INVALID_RESPONSE)
            assertThat(server.requestCount).isEqualTo(1)
            assertSanitized(failure)
        }
    }

    @Test
    fun refusalAndIncompleteStopReasonsMapToSanitizedFailureKinds() = runTest {
        val cases = listOf(
            "max_tokens" to ClinicalOpenAiFailureKind.INCOMPLETE,
            "model_context_window_exceeded" to ClinicalOpenAiFailureKind.INCOMPLETE,
            "pause_turn" to ClinicalOpenAiFailureKind.INCOMPLETE
        )
        cases.forEach { (stopReason, expectedKind) ->
            listOf(
                "[]",
                """[{"type":"text","text":"Partial response.","citations":[]}]"""
            ).forEach { content ->
                MockWebServer().use { server ->
                    server.enqueue(
                        MockResponse().setBody(
                            responseBody(
                                output = "",
                                stopReason = stopReason,
                                content = content
                            )
                        )
                    )

                    val failure = runCatching {
                        testGateway(server).analyze(payload(datasetFixture()), { SECRET })
                    }.exceptionOrNull()

                    assertThat(failure).isInstanceOf(ClinicalOpenAiException::class.java)
                    assertThat((failure as ClinicalOpenAiException).kind).isEqualTo(expectedKind)
                    assertSanitized(failure)
                }
            }
        }
    }

    @Test
    fun successAcceptsMissingAndExplicitlyDisabledOptionalFields() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(responseBody(validReportJson())))
            server.enqueue(
                MockResponse().setBody(
                    responseBody(
                        validReportJson(),
                        content =
                            """[{"type":"text","text":${jsonString(validReportJson())},"citations":null}]""",
                        container = "null",
                        stopDetails = "null"
                    )
                )
            )
            server.enqueue(
                MockResponse().setBody(
                    responseBody(
                        validReportJson(),
                        content =
                            """[{"type":"text","text":${jsonString(validReportJson())},"citations":[]}]""",
                        container = "null",
                        stopDetails = "null"
                    )
                )
            )

            val gateway = testGateway(server)
            repeat(3) {
                val result = gateway.analyze(payload(datasetFixture()), { SECRET })
                assertThat(result.report.summary7dStatus)
                    .isEqualTo(ClinicalSummaryStatus.STABLE)
            }
        }
    }

    @Test
    fun refusalWithMissingNullOrStrictDetailsMapsBeforeTextExtraction() = runTest {
        val responses = listOf(
            responseBody(output = "", stopReason = "refusal", content = "[]"),
            responseBody(
                output = "",
                stopReason = "refusal",
                content = """[{"type":"text","text":"Request declined.","citations":null}]"""
            ),
            responseBody(
                output = "",
                stopReason = "refusal",
                content = "[]",
                stopDetails = "null"
            ),
            responseBody(
                output = "",
                stopReason = "refusal",
                content = "[]",
                stopDetails = """
                    {
                      "type":"refusal",
                      "category":"cyber",
                      "explanation":"Request declined."
                    }
                """.trimIndent()
            )
        )
        responses.forEach { body ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(body))

                val failure = runCatching {
                    testGateway(server).analyze(payload(datasetFixture()), { SECRET })
                }.exceptionOrNull()

                assertThat(failure).isInstanceOf(ClinicalOpenAiException.Refusal::class.java)
                assertSanitized(failure)
            }
        }
    }

    @Test
    fun malformedStopDetailsAndUnknownRootFieldsAreRejected() = runTest {
        val responses = listOf(
            responseBody(validReportJson(), stopDetails = jsonString("invalid")),
            responseBody(validReportJson(), stopDetails = "[]"),
            responseBody(validReportJson(), stopDetails = "1"),
            responseBody(validReportJson(), stopDetails = "true"),
            responseBody(validReportJson(), stopDetails = "{}"),
            responseBody(
                output = "",
                stopReason = "refusal",
                content = "[]",
                stopDetails = """{"type":"other","category":null,"explanation":null}"""
            ),
            responseBody(
                output = "",
                stopReason = "refusal",
                content = "[]",
                stopDetails = """{"type":"refusal","category":"cyber"}"""
            ),
            responseBody(
                output = "",
                stopReason = "refusal",
                content = "[]",
                stopDetails =
                    """{"type":"refusal","category":"cyber","explanation":null,"extra":true}"""
            ),
            responseBody(
                validReportJson(),
                stopDetails =
                    """{"type":"refusal","category":"cyber","explanation":"Denied."}"""
            ),
            responseBody(validReportJson(), extraRoot = ""","unexpected":"field"""")
        )
        responses.forEach { body ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(body))

                val failure = runCatching {
                    testGateway(server).analyze(payload(datasetFixture()), { SECRET })
                }.exceptionOrNull()

                assertThat(failure)
                    .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
                assertSanitized(failure)
            }
        }
    }

    @Test
    fun malformedFailureContentIsRejectedBeforeStopReasonMapping() = runTest {
        val cases = listOf(
            "refusal" to
                """[{"type":"text","text":"Denied.","unexpected":true}]""",
            "max_tokens" to
                """[{"type":"text","text":"Partial.","citations":null,"unexpected":true}]""",
            "model_context_window_exceeded" to
                """[{"type":"text","text":1}]""",
            "pause_turn" to
                """[{"type":"text","text":"First."},{"type":"text","text":"Second."}]""",
            "refusal" to
                """[{"type":"text","text":"   "}]"""
        )
        cases.forEach { (stopReason, content) ->
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setBody(
                        responseBody(
                            output = "",
                            stopReason = stopReason,
                            content = content
                        )
                    )
                )

                val failure = runCatching {
                    testGateway(server).analyze(payload(datasetFixture()), { SECRET })
                }.exceptionOrNull()

                assertThat(failure)
                    .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
                assertSanitized(failure)
            }
        }
    }

    @Test
    fun stopSequenceMustBeJsonNull() = runTest {
        listOf(jsonString("END"), "1", "true", "{}", "[]").forEach { stopSequence ->
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setBody(
                        responseBody(validReportJson(), stopSequence = stopSequence)
                    )
                )

                val failure = runCatching {
                    testGateway(server).analyze(payload(datasetFixture()), { SECRET })
                }.exceptionOrNull()

                assertThat(failure)
                    .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
                assertSanitized(failure)
            }
        }
    }

    @Test
    fun malformedOrNonNullContainerIsRejected() = runTest {
        val responses = listOf(
            responseBody(validReportJson(), container = "{}"),
            responseBody(validReportJson(), container = jsonString("container-id")),
            responseBody(validReportJson(), container = "[]")
        )
        responses.forEach { body ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(body))

                val failure = runCatching {
                    testGateway(server).analyze(payload(datasetFixture()), { SECRET })
                }.exceptionOrNull()

                assertThat(failure)
                    .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
                assertSanitized(failure)
            }
        }
    }

    @Test
    fun nonEmptyOrMalformedCitationsAreRejected() = runTest {
        val report = jsonString(validReportJson())
        val responses = listOf(
            responseBody(
                validReportJson(),
                content = """[{"type":"text","text":$report,"citations":[{}]}]"""
            ),
            responseBody(
                validReportJson(),
                content = """[{"type":"text","text":$report,"citations":"disabled"}]"""
            ),
            responseBody(
                validReportJson(),
                content = """[{"type":"text","text":$report,"citations":{}}]"""
            )
        )
        responses.forEach { body ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(body))

                val failure = runCatching {
                    testGateway(server).analyze(payload(datasetFixture()), { SECRET })
                }.exceptionOrNull()

                assertThat(failure)
                    .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
                assertSanitized(failure)
            }
        }
    }

    @Test
    fun exactlyOneExpectedTextBlockIsRequired() = runTest {
        val malformedBodies = listOf(
            responseBody(validReportJson(), content = "[]"),
            responseBody(
                validReportJson(),
                content =
                    """[{"type":"text","text":${jsonString(validReportJson())},"citations":null},{"type":"text","text":"{}","citations":null}]"""
            ),
            responseBody(
                validReportJson(),
                content = """[{"type":"tool_use","id":"tool-1","name":"report","input":{}}]"""
            ),
            responseBody(
                validReportJson(),
                content =
                    """[{"type":"text","text":${jsonString(validReportJson())},"citations":null,"unexpected":true}]"""
            ),
            responseBody(validReportJson(), usage = "null"),
            responseBody(validReportJson(), extraRoot = ""","unexpected":"field"""")
        )
        malformedBodies.forEach { body ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(body))

                val failure = runCatching {
                    testGateway(server).analyze(payload(datasetFixture()), { SECRET })
                }.exceptionOrNull()

                assertThat(failure)
                    .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
                assertSanitized(failure)
            }
        }
    }

    @Test
    fun malformedJsonAndUnknownStopReasonAreRejected() = runTest {
        val responses = listOf(
            "{not-json",
            responseBody(validReportJson(), stopReason = "future_reason")
        )
        responses.forEach { body ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(body))

                val failure = runCatching {
                    testGateway(server).analyze(payload(datasetFixture()), { SECRET })
                }.exceptionOrNull()

                assertThat(failure)
                    .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
                assertSanitized(failure)
            }
        }
    }

    @Test
    fun httpFailuresAreMappedWithoutRetryOrRedirect() = runTest {
        val cases = listOf(
            401 to ClinicalOpenAiFailureKind.UNAUTHORIZED,
            403 to ClinicalOpenAiFailureKind.UNAUTHORIZED,
            429 to ClinicalOpenAiFailureKind.RATE_LIMITED,
            500 to ClinicalOpenAiFailureKind.SERVER,
            503 to ClinicalOpenAiFailureKind.SERVER,
            400 to ClinicalOpenAiFailureKind.HTTP
        )
        cases.forEach { (code, kind) ->
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(code)
                        .setHeader("Location", server.url("/redirected"))
                        .setHeader("Retry-After", "0")
                )

                val failure = runCatching {
                    testGateway(server).analyze(payload(datasetFixture()), { SECRET })
                }.exceptionOrNull()

                assertThat(failure).isInstanceOf(ClinicalOpenAiException::class.java)
                assertThat((failure as ClinicalOpenAiException).kind).isEqualTo(kind)
                assertThat(server.requestCount).isEqualTo(1)
                assertSanitized(failure)
            }
        }
    }

    @Test
    fun nonSuccessfulStatusWinsOverOversizedErrorBody() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("credential=$SECRET " + "x".repeat(4_096))
            )

            val failure = runCatching {
                testGateway(server, maxResponseBytes = 1_024)
                    .analyze(payload(datasetFixture()), { SECRET })
            }.exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(ClinicalOpenAiException.Unauthorized::class.java)
            assertSanitized(failure)
        }
    }

    @Test
    fun successfulOversizedResponseIsRejected() = runTest {
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
    }

    @Test
    fun timeoutAndNetworkFailuresAreSanitized() = runTest {
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
        val networkFailure = runCatching {
            gateway.analyze(payload(datasetFixture()), { SECRET })
        }.exceptionOrNull()
        assertThat(networkFailure)
            .isInstanceOf(ClinicalOpenAiException.NetworkFailure::class.java)
        assertSanitized(networkFailure)
    }

    @Test
    fun credentialIsReadPerRequestAndNeverSentAsAuthorization() = runTest {
        MockWebServer().use { server ->
            server.dispatcher = structuredDispatcher()
            val dataset = datasetFixture(days = 4, rowsPerDay = 24)
            val budget = AnthropicClinicalAiGateway.requestBytesForTest(dataset, MODEL) - 1
            var reads = 0

            testGateway(server, requestByteBudget = budget).analyze(payload(dataset), {
                reads += 1
                "rotated-$reads"
            })

            val requests = (0 until server.requestCount).map { server.takeRequest() }
            assertThat(reads).isEqualTo(requests.size)
            assertThat(requests.map { it.getHeader("x-api-key") })
                .containsExactlyElementsIn((1..reads).map { "rotated-$it" })
                .inOrder()
            assertThat(requests.map { it.getHeader("Authorization") }.filterNotNull())
                .isEmpty()
        }
    }

    @Test
    fun cancellationAndFatalCredentialFailuresPreserveIdentity() = runTest {
        MockWebServer().use { server ->
            val cancellation = CancellationException("credential=$SECRET")
            val fatal = LinkageError("credential=$SECRET")

            val cancellationResult = runCatching {
                testGateway(server).analyze(payload(datasetFixture()), { throw cancellation })
            }.exceptionOrNull()
            val fatalResult = runCatching {
                testGateway(server).analyze(payload(datasetFixture()), { throw fatal })
            }.exceptionOrNull()

            assertThat(cancellationResult).isSameInstanceAs(cancellation)
            assertThat(fatalResult).isSameInstanceAs(fatal)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun credentialRejectedDuringHeaderConstructionIsSanitized() = runTest {
        MockWebServer().use { server ->
            val malformedSecret = "bad\n$SECRET"

            val failure = runCatching {
                testGateway(server).analyze(payload(datasetFixture()), { malformedSecret })
            }.exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(ClinicalOpenAiException.CredentialUnavailable::class.java)
            assertThat(failure.toString()).doesNotContain(malformedSecret)
            assertThat(failure.toString()).doesNotContain(SECRET)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun connectionStatusIsSanitizedForProviderFailures() = runTest {
        val cases = listOf(
            401 to ClinicalAiConnectionStatus.UNAUTHORIZED,
            403 to ClinicalAiConnectionStatus.UNAUTHORIZED,
            429 to ClinicalAiConnectionStatus.RATE_LIMITED,
            503 to ClinicalAiConnectionStatus.SERVICE_UNAVAILABLE
        )
        cases.forEach { (code, status) ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(code).setBody("credential=$SECRET"))

                val result = testGateway(server).testConnection(SECRET)

                assertThat(result.status).isEqualTo(status)
                assertThat(result.toString()).doesNotContain(SECRET)
            }
        }
    }

    @Test
    fun forTestRequiresExactLoopbackMessagesRoute() {
        listOf(
            "https://127.0.0.1/v1/messages",
            "http://example.com/v1/messages",
            "http://127.0.0.1/v1/messages?x=1",
            "http://127.0.0.1/v1/messages/",
            "http://127.0.0.1/v1/responses"
        ).forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) {
                AnthropicClinicalAiGateway.forTest(endpoint = endpoint, model = MODEL)
            }
        }
    }

    private fun testGateway(
        server: MockWebServer,
        requestByteBudget: Int = 256 * 1_024,
        maxResponseBytes: Int = 64 * 1_024,
        timeouts: ClinicalOpenAiTimeouts = ClinicalOpenAiTimeouts()
    ): AnthropicClinicalAiGateway = AnthropicClinicalAiGateway.forTest(
        endpoint = server.url("/v1/messages").toString(),
        model = MODEL,
        requestByteBudget = requestByteBudget,
        maxResponseBytes = maxResponseBytes,
        timeouts = timeouts
    )

    private fun structuredDispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val schema = JsonParser.parseString(request.body.clone().readUtf8())
                .asJsonObject
                .getAsJsonObject("output_config")
                .getAsJsonObject("format")
                .getAsJsonObject("schema")
            return if (schema.getAsJsonObject("properties").has("findings")) {
                successResponse(validChunkJson())
            } else {
                successResponse(validReportJson())
            }
        }
    }

    private fun successResponse(
        output: String,
        stopReason: String = "end_turn"
    ): MockResponse = MockResponse().setBody(
        responseBody(output = output, stopReason = stopReason)
    )

    private fun responseBody(
        output: String,
        model: String = MODEL,
        stopReason: String = "end_turn",
        content: String =
            """[{"type":"text","text":${jsonString(output)}}]""",
        container: String? = null,
        stopSequence: String = "null",
        stopDetails: String? = null,
        usage: String = """{"input_tokens":10,"output_tokens":20}""",
        extraRoot: String = ""
    ): String =
        """
        {
          "id":"msg_test",
          "type":"message",
          "role":"assistant",
          "model":${jsonString(model)},
          "content":$content,
          ${container?.let { """"container":$it,""" }.orEmpty()}
          "stop_reason":${jsonString(stopReason)},
          "stop_sequence":$stopSequence,
          "usage":$usage
          ${stopDetails?.let { ""","stop_details":$it""" }.orEmpty()}
          $extraRoot
        }
        """.trimIndent()

    private fun messageText(body: JsonObject): String =
        body.getAsJsonArray("messages")
            .single()
            .asJsonObject
            .getAsJsonArray("content")
            .single()
            .asJsonObject
            .get("text")
            .asString

    private fun requestSchema(body: JsonObject): JsonObject =
        body.getAsJsonObject("output_config")
            .getAsJsonObject("format")
            .getAsJsonObject("schema")

    private fun assertAnthropicSchemaCompatible(node: JsonElement) {
        when {
            node.isJsonObject -> {
                val value = node.asJsonObject
                assertThat(value.keySet().intersect(UNSUPPORTED_ANTHROPIC_SCHEMA_KEYS)).isEmpty()
                value.entrySet().forEach { (_, child) ->
                    assertAnthropicSchemaCompatible(child)
                }
            }
            node.isJsonArray -> node.asJsonArray.forEach(::assertAnthropicSchemaCompatible)
        }
    }

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
                    ClinicalTelemetryPoint(it.ts, "anthropic-test-marker", 1.0, "OK")
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
        const val MODEL = "claude-sonnet-5"
        const val SECRET = "anthropic-super-secret"
        const val DAY_MS = 24L * 60L * 60L * 1_000L
        val UNSUPPORTED_ANTHROPIC_SCHEMA_KEYS = setOf(
            "minimum",
            "maximum",
            "minItems",
            "maxItems",
            "exclusiveMinimum",
            "exclusiveMaximum",
            "multipleOf",
            "minLength",
            "maxLength",
            "uniqueItems",
            "minProperties",
            "maxProperties",
            "contains"
        )
    }
}
