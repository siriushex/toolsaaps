package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.aaps.copilot.config.ClinicalAiProviderId
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.AbstractList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertThrows
import org.junit.Test

class ClinicalOpenAiClientTest {

    @Test
    fun alertCauseUsesResponsesStoreFalseSelectedModelAndStrictAlertSchema() = runTest {
        MockWebServer().use { server ->
            server.enqueue(successResponse(output = VALID_ALERT_RESULT_JSON))
            val boundCalls = AtomicInteger()

            val result = testClient(server).analyzeAlert(
                alertContextFixture(),
                { SECRET },
                alertAiTestNetworkLease(calls = boundCalls)
            )

            val request = server.takeRequest()
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertThat(request.path).isEqualTo("/v1/responses")
            assertThat(body.get("model").asString).isEqualTo(ClinicalOpenAiClient.DEFAULT_MODEL)
            assertThat(body.get("store").asBoolean).isFalse()
            assertThat(body.get("input").asString).isEqualTo(alertContextFixture().canonicalJson)
            assertThat(body.get("max_output_tokens").asInt)
                .isEqualTo(ClinicalOpenAiClient.ALERT_CAUSE_MAX_OUTPUT_TOKENS)
            val format = body.getAsJsonObject("text").getAsJsonObject("format")
            assertThat(format.get("name").asString)
                .isEqualTo(ClinicalOpenAiClient.ALERT_CAUSE_SCHEMA_NAME)
            assertThat(format.getAsJsonObject("schema"))
                .isEqualTo(ClinicalOpenAiClient.strictAlertCauseSchemaJson())
            assertThat(result.providerId).isEqualTo(ClinicalAiProviderId.OPENAI)
            assertThat(result.modelId).isEqualTo(ClinicalOpenAiClient.DEFAULT_MODEL)
            assertThat(boundCalls.get()).isEqualTo(1)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun alertCauseWrongResponseModelIsIdentityMismatch() = runTest {
        MockWebServer().use { server ->
            server.enqueue(successResponse(model = "wrong-model", output = VALID_ALERT_RESULT_JSON))

            val failure = runCatching {
                testClient(server).analyzeAlert(
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
    fun segmentedRequestEmitterMatchesGsonTransportEscapingAtExactBoundary() = runTest {
        val input = ClinicalSegmentedJsonInput.of(
            "{\"prefix\":\"",
            "\u00e9\\quoted\"\n\u2028\ud83d\ude00",
            "\",\"suffix\":true}"
        )
        val materialized = input.materialize(null)
        val expected = ClinicalOpenAiClient.buildRequestJson(
            materialized,
            ClinicalOpenAiClient.DEFAULT_MODEL,
            ClinicalResponseKind.FINAL_REPORT
        ).toString().toByteArray(Charsets.UTF_8)
        val marker = "segmented-request-test-marker"
        val markerBody = ClinicalOpenAiClient.buildRequestJson(
            marker,
            ClinicalOpenAiClient.DEFAULT_MODEL,
            ClinicalResponseKind.FINAL_REPORT
        ).toString().toByteArray(Charsets.UTF_8)
        val template = ClinicalRequestWireTemplate.capture(
            markerBody,
            JsonPrimitive(marker).toString().toByteArray(Charsets.UTF_8)
        )

        val exact = input.measuredRequestBytes(template, expected.size, null)
        val oneByteShort = input.measuredRequestBytes(template, expected.size - 1, null)
        val emitted = input.requestBodyBytes(template, exact, null)

        assertThat(exact).isEqualTo(expected.size)
        assertThat(oneByteShort).isEqualTo(expected.size)
        assertThat(emitted.asList()).containsExactlyElementsIn(expected.asList()).inOrder()
    }

    @Test
    fun cancellationDuringEventShapeValidationStopsBeforeSerializationOrRequest() = runTest {
        MockWebServer().use { server ->
            lateinit var operation: Deferred<ClinicalUploadPlan>
            val lastReadIndex = AtomicInteger(-1)
            val event = ClinicalEventSummary(
                localId = "event",
                type = "CUSTOM",
                subtype = "",
                startTs = 1L,
                endTs = 1L,
                severity = "LOW",
                source = "USER",
                title = "event",
                note = null,
                status = "CLOSED",
                provenance = "private"
            )
            val events = object : AbstractList<ClinicalEventSummary>() {
                override val size: Int = 2_000

                override fun get(index: Int): ClinicalEventSummary {
                    lastReadIndex.accumulateAndGet(index) { current, next -> maxOf(current, next) }
                    if (index == 300) operation.cancel(CancellationException("shape cancellation"))
                    return event.copy(localId = "event-$index")
                }
            }
            operation = async(start = CoroutineStart.LAZY) {
                testClient(server).buildUploadPlan(
                    datasetFixture().copy(eventSummaries = events)
                )
            }

            operation.start()
            val failure = runCatching { operation.await() }.exceptionOrNull()

            assertThat(failure).isInstanceOf(CancellationException::class.java)
            assertThat(lastReadIndex.get()).isLessThan(events.lastIndex)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun localTargetManagerActivityEvidenceIsRedactedAndRemoteValidatorRejectsIt() = runTest {
        MockWebServer().use { server ->
            server.enqueue(successResponse())
            val base = datasetFixture()
            val dataset = base.copy(
                energyProfile = ClinicalEnergyProfileSummary(),
                plannedActivities = listOf(
                    ClinicalPlannedActivitySummary(
                        type = "AEROBIC",
                        intensity = "MEDIUM",
                        plannedStartMs = base.summary30d.fromTs,
                        plannedDurationMinutes = 45,
                        adherence = "NOT_MEASURED",
                        targetDecision = "BLOCK_SENSOR_TRUST",
                        targetBlockers = listOf("sensor_untrusted")
                    )
                )
            )

            assertThrows(ClinicalOpenAiException.InvalidInput::class.java) {
                ClinicalOpenAiClient.validateRemoteDatasetForTest(dataset)
            }

            testClient(server).analyze(payload(dataset), credentialProvider = { SECRET })

            val request = server.takeRequest().body.readUtf8()
            assertThat(request).doesNotContain("BLOCK_SENSOR_TRUST")
            assertThat(request).doesNotContain("sensor_untrusted")
            assertThat(request).doesNotContain("targetDecision")
            assertThat(request).doesNotContain("targetBlockers")
        }
    }

    @Test
    fun actualRemotePayloadUsesCanonicalEventDtoAndMatchesDisclosurePreview() = runTest {
        MockWebServer().use { server ->
            server.enqueue(successResponse())
            val base = datasetFixture()
            val marker = CopilotContextNoteMarker.commandHeader(
                localEventId = "manual:remote-contract",
                revision = 3L,
                operation = AapsContextEventGateway.Operation.UPDATE
            )
            val title = "Exact custom title"
            val note = "Exact custom note " + "n".repeat(450)
            val event = ClinicalEventSummary(
                localId = "private-local-id",
                type = "CUSTOM",
                subtype = "MANUAL",
                startTs = base.generatedAt - 2L * 60_000L,
                endTs = base.generatedAt - 60_000L,
                severity = "HIGH",
                source = "USER",
                title = title,
                note = note,
                status = "CLOSED",
                provenance = "therapy_events:aapsId=42:$marker"
            )
            val dataset = base.copy(eventSummaries = listOf(event))

            testClient(server).analyze(payload(dataset), credentialProvider = { SECRET })

            val request = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            val input = JsonParser.parseString(request.get("input").asString).asJsonObject
            val remoteDataset = input.getAsJsonObject("dataset")
            val remoteEvents = JsonObject().apply {
                add("ev24", remoteDataset.get("ev24").deepCopy())
                add("ev7", remoteDataset.get("ev7").deepCopy())
                add("ev30", remoteDataset.get("ev30").deepCopy())
            }
            val preview = JsonParser.parseString(
                ClinicalReportDatasetBuilder.remoteEventPreviewJson(dataset)
            ).asJsonObject
            val remoteEvent = remoteDataset.getAsJsonArray("ev24").single().asJsonObject

            assertThat(remoteEvents).isEqualTo(preview)
            assertThat(remoteEvent.keySet()).containsExactly(
                "type",
                "subtype",
                "startTs",
                "endTs",
                "severity",
                "source",
                "title",
                "note"
            ).inOrder()
            assertThat(remoteEvent.get("startTs").asLong).isEqualTo(event.startTs)
            assertThat(remoteEvent.get("endTs").asLong).isEqualTo(event.endTs)
            assertThat(remoteEvent.get("title").asString).isEqualTo(title)
            assertThat(remoteEvent.get("note").asString).isEqualTo(note)
            listOf(
                "private-local-id",
                "provenance",
                "aapsId",
                "COPILOT_CONTEXT_V1",
                "syntheticUam",
                "status"
            ).forEach { forbidden ->
                assertThat(remoteDataset.toString()).doesNotContain(forbidden)
            }
        }
    }

    @Test
    fun remoteValidatorAcceptsExactUnionActivityEdgesAndRejectsNonOverlaps() {
        val base = datasetFixture()
        val summaryThrough = base.generatedAt - 2L * 60_000L
        val from30d = base.generatedAt - 30L * DAY_MS
        val exact = base.copy(
            detail24h = base.detail24h.copy(throughTs = base.generatedAt),
            summary7d = base.summary7d.copy(
                fromTs = base.generatedAt - 7L * DAY_MS,
                throughTs = summaryThrough
            ),
            summary30d = base.summary30d.copy(
                fromTs = from30d,
                throughTs = summaryThrough
            ),
            energyProfile = ClinicalEnergyProfileSummary(shareProfileWithAi = true),
            plannedActivities = listOf(
                ClinicalPlannedActivitySummary("AEROBIC", "MEDIUM", from30d - 30L * 60_000L, 60),
                ClinicalPlannedActivitySummary("WALKING", "LIGHT", summaryThrough + 60_000L, 30),
                ClinicalPlannedActivitySummary("MIXED", "HIGH", base.generatedAt, 20)
            )
        )

        ClinicalOpenAiClient.validateRemoteDatasetForTest(exact)

        listOf(
            ClinicalPlannedActivitySummary(
                "STRENGTH",
                "MEDIUM",
                from30d - 60L * 60_000L,
                60
            ),
            ClinicalPlannedActivitySummary(
                "STRENGTH",
                "MEDIUM",
                base.generatedAt + 1L,
                30
            )
        ).forEach { invalid ->
            assertThrows(ClinicalOpenAiException.InvalidInput::class.java) {
                ClinicalOpenAiClient.validateRemoteDatasetForTest(
                    exact.copy(plannedActivities = listOf(invalid))
                )
            }
        }
    }

    @Test
    fun remoteValidatorRejectsPlannedActivityEndOverflow() {
        val base = datasetFixture().copy(
            energyProfile = ClinicalEnergyProfileSummary(shareProfileWithAi = true)
        )
        val boundedFrom = Long.MAX_VALUE -
            ClinicalPlannedActivityPeriodPolicy.MAX_PREPARED_REPORT_SPAN_MS
        val overflowWindow = base.copy(
            detail24h = base.detail24h.copy(
                fromTs = boundedFrom,
                throughTs = Long.MAX_VALUE
            ),
            summary7d = base.summary7d.copy(
                fromTs = boundedFrom,
                throughTs = Long.MAX_VALUE
            ),
            summary30d = base.summary30d.copy(
                fromTs = boundedFrom,
                throughTs = Long.MAX_VALUE
            ),
            plannedActivities = listOf(
                ClinicalPlannedActivitySummary(
                    "STRENGTH",
                    "HIGH",
                    Long.MAX_VALUE - 1L,
                    1
                )
            )
        )
        assertThrows(ClinicalOpenAiException.InvalidInput::class.java) {
            ClinicalOpenAiClient.validateRemoteDatasetForTest(overflowWindow)
        }
    }

    @Test
    fun mapReduceProgressStagesAreGenuineDistinctEnumValues() {
        val stages = listOf(
            ClinicalOpenAiProgressStage.ANALYZING_CHUNK,
            ClinicalOpenAiProgressStage.REDUCING,
            ClinicalOpenAiProgressStage.SYNTHESIZING,
            ClinicalOpenAiProgressStage.VALIDATING
        )

        assertThat(stages.distinct()).hasSize(4)
        assertThat(stages.map(ClinicalOpenAiProgressStage::name)).containsExactly(
            "ANALYZING_CHUNK",
            "REDUCING",
            "SYNTHESIZING",
            "VALIDATING"
        ).inOrder()
    }

    @Test
    fun sendsStoreFalseLowReasoningStrictSchemaAndNoTools() = runTest {
        MockWebServer().use { server ->
            server.enqueue(successResponse())
            val client = testClient(server)

            val result = client.analyze(payload(datasetFixture()), credentialProvider = { SECRET })

            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/v1/responses")
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer $SECRET")
            val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertThat(json.get("model").asString).isEqualTo(ClinicalOpenAiClient.DEFAULT_MODEL)
            assertThat(json.get("model").asString).isEqualTo("gpt-5.6-terra")
            assertThat(json.get("store").asBoolean).isFalse()
            assertThat(json.getAsJsonObject("reasoning").get("effort").asString).isEqualTo("low")
            assertThat(json.get("max_output_tokens").asInt)
                .isEqualTo(25_000)
            assertThat(ClinicalOpenAiClient.MAX_OUTPUT_TOKENS).isEqualTo(25_000)
            val instructions = json.get("instructions").asString
            assertThat(instructions).contains("advisory")
            assertThat(instructions).contains("no direct insulin dosing")
            assertThat(instructions).contains("no carbs-to-add")
            assertThat(instructions).contains("no target setting")
            assertThat(instructions).contains("no calibration")
            assertThat(instructions).contains("no urgent treatment command")
            assertThat(instructions).contains("generate no prose")
            assertSourceCodebook(json)
            assertThat(json.has("tools")).isFalse()
            assertThat(json.has("response_format")).isFalse()
            val format = json.getAsJsonObject("text").getAsJsonObject("format")
            assertThat(format.get("type").asString).isEqualTo("json_schema")
            assertThat(format.get("name").asString).isEqualTo(ClinicalOpenAiClient.SCHEMA_NAME)
            assertThat(format.get("name").asString).isEqualTo("clinical_advisory_report_v3")
            assertThat(ClinicalOpenAiClient.SCHEMA_VERSION).isEqualTo(3)
            assertThat(format.get("strict").asBoolean).isTrue()
            assertAdditionalPropertiesFalseRecursively(format.getAsJsonObject("schema"))
            assertThat(result.metadata.model).isEqualTo(ClinicalOpenAiClient.DEFAULT_MODEL)
            assertThat(result.metadata.requestedModel).isEqualTo(ClinicalOpenAiClient.DEFAULT_MODEL)
            assertThat(result.metadata.providerId).isEqualTo(ClinicalAiProviderId.OPENAI)
            assertThat(result.metadata.requestedProviderId)
                .isEqualTo(ClinicalAiProviderId.OPENAI)
            assertThat(result.metadata.systemFingerprint).isEqualTo(FINGERPRINT)
            assertThat(result.metadata.schemaName).isEqualTo(ClinicalOpenAiClient.SCHEMA_NAME)
            assertThat(result.metadata.schemaVersion).isEqualTo(ClinicalOpenAiClient.SCHEMA_VERSION)
        }
    }

    @Test
    fun strictSchemaContainsOnlyAdvisoryFields() {
        val schema = ClinicalOpenAiClient.strictSchemaJson()
        val canonical = schema.toString().lowercase()

        listOf(
            "insulindose",
            "insulinunits",
            "carbstoadd",
            "targettoset",
            "calibrationcommand",
            "function_call",
            "tool_call",
            "\"tools\"",
            "\"actions\""
        ).forEach { forbidden ->
            assertThat(canonical).doesNotContain(forbidden)
        }
        assertAdditionalPropertiesFalseRecursively(schema)
        assertNoFreeFormStringSchemas(schema)
        assertThat(ClinicalOpenAiClient.MAX_SCHEMA_RESPONSE_BYTES)
            .isAtMost(ClinicalOpenAiClient.DEFAULT_MAX_RESPONSE_BYTES)
        val recommendationProperties = schema.getAsJsonObject("properties")
            .getAsJsonObject("recommendations")
            .getAsJsonObject("items")
            .getAsJsonObject("properties")
        val findingSchema = schema.getAsJsonObject("properties")
            .getAsJsonObject("patterns")
            .getAsJsonObject("items")
        val variants = findingSchema.getAsJsonArray("anyOf")
        assertThat(variants).hasSize(ClinicalEvidenceMetric.entries.size)
        val valueSchemas = variants.associate { variant ->
            val properties = variant.asJsonObject.getAsJsonObject("properties")
            assertThat(properties.keySet()).containsExactly(
                "topic",
                "period",
                "direction",
                "confidence",
                "timeBand",
                "evidenceMetric",
                "evidenceValue"
            )
            val metricValues = properties.getAsJsonObject("evidenceMetric")
                .getAsJsonArray("enum")
            assertThat(metricValues).hasSize(1)
            ClinicalEvidenceMetric.valueOf(metricValues[0].asString) to
                properties.getAsJsonObject("evidenceValue")
        }
        assertEvidenceSchema(
            valueSchemas,
            setOf(
                ClinicalEvidenceMetric.MEAN_GLUCOSE,
                ClinicalEvidenceMetric.MEDIAN_GLUCOSE,
                ClinicalEvidenceMetric.MEAN_TARGET_MMOL
            ),
            "number",
            ClinicalOpenAiClient.MIN_CLINICAL_MMOL,
            ClinicalOpenAiClient.MAX_CLINICAL_MMOL
        )
        assertEvidenceSchema(
            valueSchemas,
            setOf(
                ClinicalEvidenceMetric.TIME_BELOW_RANGE_PCT,
                ClinicalEvidenceMetric.TIME_IN_RANGE_PCT,
                ClinicalEvidenceMetric.TIME_ABOVE_RANGE_PCT,
                ClinicalEvidenceMetric.COVERAGE_PCT
            ),
            "number",
            0.0,
            100.0
        )
        assertEvidenceSchema(
            valueSchemas,
            setOf(ClinicalEvidenceMetric.COEFFICIENT_OF_VARIATION_PCT),
            "number",
            0.0,
            ClinicalOpenAiClient.MAX_CV_PCT
        )
        assertEvidenceSchema(
            valueSchemas,
            setOf(
                ClinicalEvidenceMetric.MAX_GAP_MINUTES,
                ClinicalEvidenceMetric.DURATION_MINUTES
            ),
            "integer",
            0.0,
            ClinicalOpenAiClient.MAX_30D_MINUTES.toDouble()
        )
        assertEvidenceSchema(
            valueSchemas,
            setOf(ClinicalEvidenceMetric.TOTAL_INSULIN_UNITS_RECORDED),
            "number",
            0.0,
            ClinicalOpenAiClient.MAX_RECORDED_INSULIN_UNITS
        )
        assertEvidenceSchema(
            valueSchemas,
            setOf(ClinicalEvidenceMetric.TOTAL_CARBS_GRAMS_RECORDED),
            "number",
            0.0,
            ClinicalOpenAiClient.MAX_RECORDED_CARBS_GRAMS
        )
        assertEvidenceSchema(
            valueSchemas,
            setOf(ClinicalEvidenceMetric.SAMPLE_COUNT),
            "integer",
            0.0,
            ClinicalOpenAiClient.MAX_CANONICAL_30D_SAMPLE_COUNT.toDouble()
        )
        assertThat(recommendationProperties.keySet()).containsExactly(
            "careTeamDiscussionTopic",
            "priority",
            "evidenceFindingIndices",
            "period"
        )
        assertExclusiveEnumSchema(
            schema.getAsJsonObject("properties").getAsJsonObject("dataQuality"),
            "COMPLETE"
        )
        assertExclusiveEnumSchema(
            schema.getAsJsonObject("properties").getAsJsonObject("safetyObservations"),
            "NONE_IDENTIFIED"
        )
        assertThat(
            recommendationProperties.getAsJsonObject("careTeamDiscussionTopic")
                .getAsJsonArray("enum").map { it.asString }
        ).containsExactlyElementsIn(ClinicalCareTeamDiscussionTopic.entries.map { it.name })
        assertThat(
            recommendationProperties.getAsJsonObject("period")
                .getAsJsonArray("enum").map { it.asString }
        ).containsExactlyElementsIn(ClinicalEvidencePeriod.entries.map { it.name })
        assertThat(
            recommendationProperties.getAsJsonObject("priority")
                .getAsJsonArray("enum").map { it.asString }
        ).containsExactlyElementsIn(ClinicalAdvisoryPriority.entries.map { it.name })
        assertThat(schema.getAsJsonObject("properties").keySet()).containsExactly(
            "summary7dStatus",
            "summary30dStatus",
            "dataQuality",
            "patterns",
            "safetyObservations",
            "recommendations",
            "careTeamQuestions"
        )
    }

    @Test
    fun redirectsAndRetryAfterAreTerminalAndNeverReplaySensitivePost() = runTest {
        val terminalResponses = listOf(
            MockResponse()
                .setResponseCode(307)
                .setHeader("Location", "SECOND_HOST"),
            MockResponse()
                .setResponseCode(308)
                .setHeader("Location", "SECOND_HOST"),
            MockResponse()
                .setResponseCode(503)
                .setHeader("Retry-After", "0")
        )

        terminalResponses.forEachIndexed { index, template ->
            MockWebServer().use { first ->
                MockWebServer().use { second ->
                    val response = template.clone()
                    if (index < 2) {
                        response.setHeader("Location", second.url("/capture"))
                    }
                    first.enqueue(response)

                    val failure = runCatching {
                        testClient(first).analyze(payload(datasetFixture()), { SECRET })
                    }.exceptionOrNull()

                    if (index < 2) {
                        assertThat(failure)
                            .isInstanceOf(ClinicalOpenAiException.HttpFailure::class.java)
                    } else {
                        assertThat(failure)
                            .isInstanceOf(ClinicalOpenAiException.ServerFailure::class.java)
                    }
                    assertThat(first.requestCount).isEqualTo(1)
                    assertThat(second.requestCount).isEqualTo(0)
                    assertSanitized(failure)
                }
            }
        }
    }

    @Test
    fun parsesTopLevelAndNestedOutputTextShapes() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                        {
                          "status":"completed",
                          "model":"${ClinicalOpenAiClient.DEFAULT_MODEL}",
                          "system_fingerprint":"$FINGERPRINT",
                          "output_text":${jsonString(validReportJson())}
                        }
                    """.trimIndent()
                )
            )
            server.enqueue(
                MockResponse().setBody(
                    """
                        {
                          "status":"completed",
                          "model":"${ClinicalOpenAiClient.DEFAULT_MODEL}",
                          "system_fingerprint":"$FINGERPRINT",
                          "output":[{"content":[{"type":"output_text","text":${jsonString(validReportJson())}}]}]
                        }
                    """.trimIndent()
                )
            )
            server.enqueue(
                MockResponse().setBody(
                    """
                        {
                          "status":"completed",
                          "model":"${ClinicalOpenAiClient.DEFAULT_MODEL}",
                          "system_fingerprint":"$FINGERPRINT",
                          "output":[{"content":[{"type":"output_text","text":{"value":${jsonString(validReportJson())}}}]}]
                        }
                    """.trimIndent()
                )
            )
            val client = testClient(server)

            val direct = client.analyze(payload(datasetFixture()), { SECRET })
            val nested = client.analyze(payload(datasetFixture()), { SECRET })
            val nestedValue = client.analyze(payload(datasetFixture()), { SECRET })

            assertThat(direct.report.summary7dStatus).isEqualTo(ClinicalSummaryStatus.STABLE)
            assertThat(nested.report.patterns.single().topic)
                .isEqualTo(ClinicalPatternTopic.GLUCOSE_STABILITY)
            assertThat(nestedValue.report.recommendations.single().priority)
                .isEqualTo(ClinicalAdvisoryPriority.MEDIUM)
        }
    }

    @Test
    fun rejectsMissingOrUnexpectedResponseModelAndReturnsActualIdentity() = runTest {
        MockWebServer().use { server ->
            server.enqueue(successResponse(model = "gpt-4o-mini"))
            server.enqueue(
                MockResponse().setBody(
                    """{"status":"completed","output_text":${jsonString(validReportJson())}}"""
                )
            )
            server.enqueue(successResponse(fingerprint = null))
            server.enqueue(successResponse(fingerprint = " $FINGERPRINT"))
            val emoji = "\uD83D\uDE42"
            val maxFingerprint = emoji.repeat(
                ClinicalOpenAiClient.MAX_SYSTEM_FINGERPRINT_CODE_POINTS
            )
            server.enqueue(successResponse(fingerprint = maxFingerprint))
            server.enqueue(successResponse(fingerprint = maxFingerprint + emoji))
            server.enqueue(successResponse(model = "${ClinicalOpenAiClient.DEFAULT_MODEL} "))
            val client = testClient(server)

            val wrongModel = runCatching {
                client.analyze(payload(datasetFixture()), { SECRET })
            }.exceptionOrNull()
            val missingModel = runCatching {
                client.analyze(payload(datasetFixture()), { SECRET })
            }.exceptionOrNull()
            val withoutFingerprint = client.analyze(payload(datasetFixture()), { SECRET })
            val whitespaceFingerprint = runCatching {
                client.analyze(payload(datasetFixture()), { SECRET })
            }.exceptionOrNull()
            val atLimit = client.analyze(payload(datasetFixture()), { SECRET })
            val oversizedFingerprint = runCatching {
                client.analyze(payload(datasetFixture()), { SECRET })
            }.exceptionOrNull()
            val whitespaceModel = runCatching {
                client.analyze(payload(datasetFixture()), { SECRET })
            }.exceptionOrNull()

            assertThat(wrongModel)
                .isInstanceOf(ClinicalAiGatewayException.IdentityMismatch::class.java)
            assertThat(missingModel)
                .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
            assertThat(withoutFingerprint.metadata.model)
                .isEqualTo(ClinicalOpenAiClient.DEFAULT_MODEL)
            assertThat(withoutFingerprint.metadata.systemFingerprint).isNull()
            assertThat(whitespaceFingerprint)
                .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
            assertThat(atLimit.metadata.systemFingerprint).isEqualTo(maxFingerprint)
            assertThat(oversizedFingerprint)
                .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
            assertThat(whitespaceModel)
                .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
            assertSanitized(wrongModel)
            assertSanitized(missingModel)
            assertSanitized(whitespaceFingerprint)
            assertSanitized(oversizedFingerprint)
            assertSanitized(whitespaceModel)
        }
    }

    @Test
    fun configuredModelIsUsedForDirectChunkReductionSynthesisAndMetadata() = runTest {
        MockWebServer().use { server ->
            val model = "gpt-5.6-sol"
            val requestBodies = CopyOnWriteArrayList<String>()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(
                    request: okhttp3.mockwebserver.RecordedRequest
                ): MockResponse {
                    val body = request.body.clone().readUtf8()
                    requestBodies += body
                    val schemaName = JsonParser.parseString(body).asJsonObject
                        .getAsJsonObject("text")
                        .getAsJsonObject("format")
                        .get("name")
                        .asString
                    return if (schemaName == ClinicalOpenAiClient.CHUNK_SCHEMA_NAME) {
                        chunkSuccessResponse(model = model)
                    } else {
                        successResponse(model = model)
                    }
                }
            }
            val direct = testClient(server, model = model)
                .analyze(payload(datasetFixture()), { SECRET })
            val dataset = productionLikeThirtyDayDataset()
            val mapReduce = testClient(
                server,
                model = model,
                requestByteBudget = 64 * 1_024
            ).analyze(payload(dataset), { SECRET })

            assertThat(requestBodies).isNotEmpty()
            requestBodies.forEach { body ->
                assertThat(JsonParser.parseString(body).asJsonObject.get("model").asString)
                    .isEqualTo(model)
            }
            assertThat(mapReduce.metadata.chunkCount).isGreaterThan(1)
            assertThat(mapReduce.metadata.reductionLevels).isGreaterThan(0)
            listOf(direct, mapReduce).forEach { result ->
                assertThat(result.metadata.model).isEqualTo(model)
                assertThat(result.metadata.requestedModel).isEqualTo(model)
                assertThat(result.metadata.providerId).isEqualTo(ClinicalAiProviderId.OPENAI)
                assertThat(result.metadata.requestedProviderId)
                    .isEqualTo(ClinicalAiProviderId.OPENAI)
            }
        }
    }

    @Test
    fun productionConstructorsPreservePositionalCompatibilityAndAcceptValidatedModel() {
        val defaults = ClinicalOpenAiClient()
        val positional = ClinicalOpenAiClient(
            256 * 1_024,
            64 * 1_024,
            ClinicalOpenAiTimeouts()
        )
        val customModel = ClinicalOpenAiClient(
            "gpt-5.6-luna",
            256 * 1_024,
            64 * 1_024,
            ClinicalOpenAiTimeouts()
        )

        assertThat(defaults.modelId).isEqualTo("gpt-5.6-terra")
        assertThat(positional.modelId).isEqualTo("gpt-5.6-terra")
        assertThat(customModel.modelId).isEqualTo("gpt-5.6-luna")
        listOf("", " model", "model ").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                ClinicalOpenAiClient(invalid)
            }
        }
    }

    @Test
    fun explicitConnectionTestUsesBoundedNonClinicalRequestAndSanitizedStatus() = runTest {
        MockWebServer().use { server ->
            val model = "gpt-5.6-luna"
            server.enqueue(connectionSuccessResponse(model))
            server.enqueue(MockResponse().setResponseCode(401).setBody(SECRET))
            val client = testClient(server, model = model)

            val success = client.testConnection(SECRET)
            val unauthorized = client.testConnection(SECRET)
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            val json = JsonParser.parseString(body).asJsonObject

            assertThat(body.toByteArray(Charsets.UTF_8).size).isAtMost(4 * 1_024)
            assertThat(json.get("model").asString).isEqualTo(model)
            assertThat(json.get("store").asBoolean).isFalse()
            assertThat(json.getAsJsonObject("reasoning").get("effort").asString)
                .isEqualTo("low")
            assertThat(json.get("max_output_tokens").asInt).isEqualTo(1_024)
            assertThat(json.get("input").asString).doesNotContain("glucose")
            assertThat(json.get("input").asString).doesNotContain("insulin")
            assertThat(json.get("input").asString).doesNotContain("therapy")
            assertThat(success).isEqualTo(
                ClinicalAiConnectionTest(
                    providerId = ClinicalAiProviderId.OPENAI,
                    modelId = model,
                    status = ClinicalAiConnectionStatus.SUCCESS,
                    latencyMs = success.latencyMs
                )
            )
            assertThat(unauthorized.status).isEqualTo(ClinicalAiConnectionStatus.UNAUTHORIZED)
            assertThat(success.toString()).doesNotContain(SECRET)
            assertThat(unauthorized.toString()).doesNotContain(SECRET)
        }
    }

    @Test
    fun connectionTestRequiresCompletedOutcome() = runTest {
        MockWebServer().use { server ->
            val model = "gpt-5.6-luna"
            server.enqueue(connectionSuccessResponse(model, status = "incomplete"))
            server.enqueue(connectionSuccessResponse(model, status = "completed"))
            val client = testClient(server, model = model)

            val incomplete = client.testConnection(SECRET)
            val completed = client.testConnection(SECRET)

            assertThat(incomplete.status).isEqualTo(ClinicalAiConnectionStatus.INVALID_RESPONSE)
            assertThat(completed.status).isEqualTo(ClinicalAiConnectionStatus.SUCCESS)
        }
    }

    @Test
    fun connectionTestClassifiesHttpStatusBeforeOversizedBody() = runTest {
        MockWebServer().use { server ->
            val oversizedBody = "x".repeat(17 * 1_024)
            listOf(401, 403, 429, 500, 200).forEach { status ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(status)
                        .setBody(oversizedBody)
                )
            }
            val client = testClient(server)

            val results = List(5) { client.testConnection(SECRET).status }

            assertThat(results).containsExactly(
                ClinicalAiConnectionStatus.UNAUTHORIZED,
                ClinicalAiConnectionStatus.UNAUTHORIZED,
                ClinicalAiConnectionStatus.RATE_LIMITED,
                ClinicalAiConnectionStatus.SERVICE_UNAVAILABLE,
                ClinicalAiConnectionStatus.INVALID_RESPONSE
            ).inOrder()
        }
    }

    @Test
    fun rejectsRefusalIncompleteMissingAndInvalidOutput() = runTest {
        val bodies = listOf(
            """{"status":"completed","output":[{"content":[{"type":"refusal","refusal":"private server text"}]}]}""",
            """{"status":"incomplete","incomplete_details":{"reason":"private server text"}}""",
            """{"status":"completed","output":[]}""",
            """{"status":"completed","output_text":"not-json"}""",
            """
                {
                  "status":" completed",
                  "model":"${ClinicalOpenAiClient.DEFAULT_MODEL}",
                  "output_text":${jsonString(validReportJson())}
                }
            """.trimIndent()
        )

        bodies.forEachIndexed { index, body ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(body))
                val failure = runCatching {
                    testClient(server).analyze(payload(datasetFixture()), { SECRET })
                }.exceptionOrNull()

                when (index) {
                    0 -> assertThat(failure).isInstanceOf(ClinicalOpenAiException.Refusal::class.java)
                    1 -> assertThat(failure).isInstanceOf(ClinicalOpenAiException.Incomplete::class.java)
                    else -> assertThat(failure)
                        .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
                }
                assertSanitized(failure)
            }
        }
    }

    @Test
    fun parserRejectsUnknownFieldsRecursivelyForbiddenKeysAndOversizedPayloads() {
        val rootExtra = reportObject().apply { addProperty("extra", "x") }
        val nestedExtra = reportObject().apply {
            getAsJsonArray("patterns")[0].asJsonObject.addProperty("extra", "x")
        }
        val forbidden = reportObject().apply {
            getAsJsonArray("recommendations")[0].asJsonObject.addProperty("insulinDose", 2.0)
        }
        val commandAsRecommendation = reportObject().apply {
            getAsJsonArray("recommendations")[0].asJsonObject.addProperty(
                "careTeamDiscussionTopic",
                "TAKE_2_UNITS_NOW"
            )
        }
        val commandLike = reportObject().apply {
            getAsJsonArray("patterns")[0].asJsonObject.addProperty("toolCall", "x")
        }

        listOf(
            rootExtra,
            nestedExtra,
            forbidden,
            commandAsRecommendation,
            commandLike
        ).forEach { invalid ->
            val failure = runCatching {
                ClinicalReportSchemaParser.parse(invalid.toString())
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
            assertSanitized(failure)
        }
        assertThrows(ClinicalOpenAiException.InvalidResponse::class.java) {
            ClinicalReportSchemaParser.parse(
                " ".repeat(ClinicalOpenAiClient.MAX_SCHEMA_RESPONSE_BYTES + 1) +
                    reportObject()
            )
        }
    }

    @Test
    fun parserRejectsCommandFieldsAndNestedCaseOrAliasVariants() {
        val forbiddenKeys = listOf(
            "insulinDose",
            "INSULIN_DOSE",
            "bolusUnits",
            "suggestedBolus",
            "unitsOfInsulin",
            "carbsToAdd",
            "CARBS-TO-ADD",
            "carbohydrateGrams",
            "gramsOfCarbs",
            "suggestedCarbs",
            "targetMmol",
            "TARGET_MMOL",
            "glucoseTarget",
            "newTarget",
            "suggestedTarget",
            "calibrationCommand",
            "CALIBRATION_COMMAND",
            "calibrateSensor",
            "bgCalibration"
        )

        forbiddenKeys.forEach { forbiddenKey ->
            val nested = JsonObject().apply {
                add(
                    "outer",
                    JsonObject().apply {
                        add(
                            "items",
                            com.google.gson.JsonArray().apply {
                                add(
                                    JsonObject().apply {
                                        addProperty(forbiddenKey, 1.0)
                                    }
                                )
                            }
                        )
                    }
                )
            }

            val failure = assertThrows(ClinicalOpenAiException.InvalidResponse::class.java) {
                ClinicalReportSchemaParser.rejectForbiddenFields(nested)
            }

            assertSanitized(failure)
        }
    }

    @Test
    fun nativeResponsesGatewayRejectsForbiddenNestedCommandEnvelope() = runTest {
        MockWebServer().use { server ->
            val report = reportObject().apply {
                add(
                    "providerExtension",
                    JsonObject().apply {
                        add(
                            "nested",
                            JsonObject().apply {
                                addProperty("bolusUnits", 2.0)
                            }
                        )
                    }
                )
            }
            server.enqueue(successResponse(output = report.toString()))

            val failure = runCatching {
                testClient(server).analyze(payload(datasetFixture()), { SECRET })
            }.exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
            assertSanitized(failure)
        }
    }

    @Test
    fun parserKeepsMeanTargetEvidenceAndTargetAlignmentObservationsAdvisory() {
        val report = reportWithEvidence(
            ClinicalEvidenceMetric.MEAN_TARGET_MMOL,
            JsonPrimitive(6.0)
        ).apply {
            getAsJsonArray("patterns")[0].asJsonObject.apply {
                addProperty("topic", ClinicalPatternTopic.TARGET_ALIGNMENT.name)
                addProperty("direction", ClinicalPatternDirection.STABLE.name)
            }
        }

        val parsed = ClinicalReportSchemaParser.parse(report.toString())

        assertThat(parsed.patterns.single().topic)
            .isEqualTo(ClinicalPatternTopic.TARGET_ALIGNMENT)
        assertThat(parsed.patterns.single().evidenceMetric)
            .isEqualTo(ClinicalEvidenceMetric.MEAN_TARGET_MMOL)
        assertThat(parsed.patterns.single().evidenceValue).isEqualTo(6.0)
    }

    @Test
    fun rejectsWrongTypesAndTooManyItems() {
        val numericAdvisory = reportObject().apply {
            getAsJsonArray("recommendations")[0].asJsonObject
                .addProperty("careTeamDiscussionTopic", 2.0)
        }
        val tooMany = reportObject().apply {
            val values = getAsJsonArray("careTeamQuestions")
            repeat(ClinicalOpenAiClient.MAX_LIST_ITEMS) { values.add("Another question?") }
        }
        val unknownEvidenceIndex = reportObject().apply {
            getAsJsonArray("recommendations")[0].asJsonObject
                .getAsJsonArray("evidenceFindingIndices").set(
                    0,
                    com.google.gson.JsonPrimitive(99)
                )
        }
        val missingEvidenceReference = reportObject().apply {
            getAsJsonArray("recommendations")[0].asJsonObject
                .add("evidenceFindingIndices", com.google.gson.JsonArray())
        }
        val stringEvidenceValue = reportObject().apply {
            getAsJsonArray("patterns")[0].asJsonObject
                .addProperty("evidenceValue", "90")
        }
        val oversizedEvidenceValue = reportObject().apply {
            getAsJsonArray("patterns")[0].asJsonObject.addProperty(
                "evidenceValue",
                100.01
            )
        }

        listOf(
            numericAdvisory,
            tooMany,
            unknownEvidenceIndex,
            missingEvidenceReference,
            stringEvidenceValue,
            oversizedEvidenceValue
        ).forEach { invalid ->
            assertThrows(ClinicalOpenAiException.InvalidResponse::class.java) {
                ClinicalReportSchemaParser.parse(invalid.toString())
            }
        }
    }

    @Test
    fun parserEnforcesMetricSpecificEvidenceTypesAndRanges() {
        data class EvidenceCase(
            val metric: ClinicalEvidenceMetric,
            val minimum: Number,
            val maximum: Number
        )

        val cases = listOf(
            EvidenceCase(
                ClinicalEvidenceMetric.MEAN_GLUCOSE,
                ClinicalOpenAiClient.MIN_CLINICAL_MMOL,
                ClinicalOpenAiClient.MAX_CLINICAL_MMOL
            ),
            EvidenceCase(
                ClinicalEvidenceMetric.MEDIAN_GLUCOSE,
                ClinicalOpenAiClient.MIN_CLINICAL_MMOL,
                ClinicalOpenAiClient.MAX_CLINICAL_MMOL
            ),
            EvidenceCase(
                ClinicalEvidenceMetric.MEAN_TARGET_MMOL,
                ClinicalOpenAiClient.MIN_CLINICAL_MMOL,
                ClinicalOpenAiClient.MAX_CLINICAL_MMOL
            ),
            EvidenceCase(ClinicalEvidenceMetric.COEFFICIENT_OF_VARIATION_PCT, 0.0, 200.0),
            EvidenceCase(ClinicalEvidenceMetric.TIME_BELOW_RANGE_PCT, 0.0, 100.0),
            EvidenceCase(ClinicalEvidenceMetric.TIME_IN_RANGE_PCT, 0.0, 100.0),
            EvidenceCase(ClinicalEvidenceMetric.TIME_ABOVE_RANGE_PCT, 0.0, 100.0),
            EvidenceCase(ClinicalEvidenceMetric.COVERAGE_PCT, 0.0, 100.0),
            EvidenceCase(
                ClinicalEvidenceMetric.MAX_GAP_MINUTES,
                0.0,
                ClinicalOpenAiClient.MAX_30D_MINUTES.toDouble()
            ),
            EvidenceCase(
                ClinicalEvidenceMetric.DURATION_MINUTES,
                0.0,
                ClinicalOpenAiClient.MAX_30D_MINUTES.toDouble()
            ),
            EvidenceCase(
                ClinicalEvidenceMetric.TOTAL_INSULIN_UNITS_RECORDED,
                0.0,
                ClinicalOpenAiClient.MAX_RECORDED_INSULIN_UNITS
            ),
            EvidenceCase(
                ClinicalEvidenceMetric.TOTAL_CARBS_GRAMS_RECORDED,
                0.0,
                ClinicalOpenAiClient.MAX_RECORDED_CARBS_GRAMS
            ),
            EvidenceCase(
                ClinicalEvidenceMetric.SAMPLE_COUNT,
                0,
                ClinicalOpenAiClient.MAX_CANONICAL_30D_SAMPLE_COUNT
            )
        )

        cases.forEach { case ->
            listOf(case.minimum, case.maximum).forEach { edge ->
                val parsed = ClinicalReportSchemaParser.parse(
                    reportWithEvidence(case.metric, com.google.gson.JsonPrimitive(edge)).toString()
                )
                assertThat(parsed.patterns.single().evidenceValue)
                    .isEqualTo(edge.toDouble())
            }
            val below = if (case.minimum.toDouble() == 0.0) {
                -0.01
            } else {
                case.minimum.toDouble() - 0.01
            }
            val above = case.maximum.toDouble() + 0.01
            listOf(below, above).forEach { invalidValue ->
                assertThrows(ClinicalOpenAiException.InvalidResponse::class.java) {
                    ClinicalReportSchemaParser.parse(
                        reportWithEvidence(
                            case.metric,
                            com.google.gson.JsonPrimitive(invalidValue)
                        ).toString()
                    )
                }
            }
        }

        assertThrows(ClinicalOpenAiException.InvalidResponse::class.java) {
            ClinicalReportSchemaParser.parse(
                reportWithEvidence(
                    ClinicalEvidenceMetric.SAMPLE_COUNT,
                    com.google.gson.JsonPrimitive(1.5)
                ).toString()
            )
        }
        listOf(
            ClinicalEvidenceMetric.MAX_GAP_MINUTES,
            ClinicalEvidenceMetric.DURATION_MINUTES
        ).forEach { metric ->
            assertThrows(ClinicalOpenAiException.InvalidResponse::class.java) {
                ClinicalReportSchemaParser.parse(
                    reportWithEvidence(metric, com.google.gson.JsonPrimitive(1.5)).toString()
                )
            }
        }
        assertThrows(ClinicalOpenAiException.InvalidResponse::class.java) {
            val nonFiniteJson = reportObject().toString().replace(
                "\"evidenceValue\":90.0",
                "\"evidenceValue\":1e309"
            )
            assertThat(nonFiniteJson).contains("\"evidenceValue\":1e309")
            ClinicalReportSchemaParser.parse(
                nonFiniteJson
            )
        }
        assertThrows(ClinicalOpenAiException.InvalidResponse::class.java) {
            ClinicalReportSchemaParser.parse(
                reportWithEvidence(
                    ClinicalEvidenceMetric.TIME_IN_RANGE_PCT,
                    com.google.gson.JsonNull.INSTANCE
                ).toString()
            )
        }
    }

    @Test
    fun exclusiveSentinelsAllowSingletonsAndRejectContradictions() {
        val completeOnly = reportObject()
        val noneOnly = reportObject()
        val contradictoryQuality = reportObject().apply {
            getAsJsonArray("dataQuality").add("SENSOR_GAPS")
        }
        val contradictorySafety = reportObject().apply {
            getAsJsonArray("safetyObservations").add("RECURRENT_LOW_PATTERN")
        }

        assertThat(ClinicalReportSchemaParser.parse(completeOnly.toString()).dataQuality)
            .containsExactly(ClinicalDataQualityFlag.COMPLETE)
        assertThat(ClinicalReportSchemaParser.parse(noneOnly.toString()).safetyObservations)
            .containsExactly(ClinicalSafetyObservation.NONE_IDENTIFIED)
        listOf(contradictoryQuality, contradictorySafety).forEach { invalid ->
            assertThrows(ClinicalOpenAiException.InvalidResponse::class.java) {
                ClinicalReportSchemaParser.parse(invalid.toString())
            }
        }
    }

    @Test
    fun modelGeneratedCommandsCannotFitClosedFinalOrChunkContracts() {
        val invalidReports = listOf(
            reportObject().apply {
                addProperty("summary7dStatus", "INCREASE_BASAL")
            },
            reportObject().apply {
                getAsJsonArray("dataQuality")
                    .set(0, com.google.gson.JsonPrimitive("SUSPEND_PUMP"))
            },
            reportObject().apply {
                getAsJsonArray("patterns")[0].asJsonObject.addProperty("topic", "EAT_CARBS")
            },
            reportObject().apply {
                getAsJsonArray("patterns")[0].asJsonObject
                    .addProperty("direction", "RESUME_PUMP")
            },
            reportObject().apply {
                getAsJsonArray("safetyObservations")
                    .set(0, com.google.gson.JsonPrimitive("INCREASE_BASAL"))
            },
            reportObject().apply {
                getAsJsonArray("careTeamQuestions")
                    .set(0, com.google.gson.JsonPrimitive("SUSPEND_OR_RESUME"))
            }
        )

        invalidReports.forEach { report ->
            assertThrows(ClinicalOpenAiException.InvalidResponse::class.java) {
                ClinicalReportSchemaParser.parse(report.toString())
            }
        }
        val invalidChunk = chunkReportObject().apply {
            getAsJsonArray("findings")[0].asJsonObject.addProperty("topic", "EAT_15_G_CARBS")
        }
        assertThrows(ClinicalOpenAiException.InvalidResponse::class.java) {
            ClinicalChunkReportSchemaParser.parse(invalidChunk.toString())
        }
    }

    @Test
    fun parserUsesExactCaseAndWhitespaceSensitiveEnums() {
        val enumCaseMismatch = reportObject().apply {
            getAsJsonArray("recommendations")[0].asJsonObject.addProperty("priority", "Medium")
        }
        val enumWhitespaceMismatch = reportObject().apply {
            getAsJsonArray("recommendations")[0].asJsonObject.addProperty("priority", " MEDIUM ")
        }
        val topicCaseMismatch = reportObject().apply {
            getAsJsonArray("recommendations")[0].asJsonObject.addProperty(
                "careTeamDiscussionTopic",
                "sensor_reliability"
            )
        }
        val periodWhitespaceMismatch = reportObject().apply {
            getAsJsonArray("recommendations")[0].asJsonObject.addProperty(
                "period",
                "LAST_7_DAYS "
            )
        }
        val summaryWhitespaceMismatch = reportObject().apply {
            addProperty("summary30dStatus", "STABLE ")
        }

        assertThrows(ClinicalOpenAiException.InvalidResponse::class.java) {
            ClinicalReportSchemaParser.parse(enumCaseMismatch.toString())
        }
        assertThrows(ClinicalOpenAiException.InvalidResponse::class.java) {
            ClinicalReportSchemaParser.parse(enumWhitespaceMismatch.toString())
        }
        assertThrows(ClinicalOpenAiException.InvalidResponse::class.java) {
            ClinicalReportSchemaParser.parse(topicCaseMismatch.toString())
        }
        assertThrows(ClinicalOpenAiException.InvalidResponse::class.java) {
            ClinicalReportSchemaParser.parse(periodWhitespaceMismatch.toString())
        }
        assertThrows(ClinicalOpenAiException.InvalidResponse::class.java) {
            ClinicalReportSchemaParser.parse(summaryWhitespaceMismatch.toString())
        }
    }

    @Test
    fun mapsHttpFailuresAndNeverExposesBodiesHeadersKeyOrPayload() = runTest {
        val cases = listOf(
            401 to ClinicalOpenAiException.Unauthorized::class.java,
            429 to ClinicalOpenAiException.RateLimited::class.java,
            500 to ClinicalOpenAiException.ServerFailure::class.java,
            418 to ClinicalOpenAiException.HttpFailure::class.java
        )

        cases.forEach { (code, type) ->
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(code)
                        .setHeader("X-Secret", "private-header")
                        .setBody("""{"error":{"message":"private-server-message"}}""")
                )
                val medicalMarker = "private-medical-payload"
                val failure = runCatching {
                    testClient(server).analyze(
                        payload(datasetFixture().copy(zoneId = medicalMarker)),
                        { SECRET }
                    )
                }.exceptionOrNull()

                assertThat(failure).isInstanceOf(type)
                assertSanitized(failure, medicalMarker)
            }
        }
    }

    @Test
    fun nonSuccessfulStatusPrecedesOversizedBodyWithoutIncludingBody() = runTest {
        MockWebServer().use { server ->
            val responseLimit = 1_024
            val privateBody = "private-oversized-error-" + "x".repeat(4_096)
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody(privateBody)
            )
            val snapshots = CopyOnWriteArrayList<ClinicalOpenAiExecutionSnapshot>()
            val observer = object :
                ClinicalOpenAiProgressCallback,
                ClinicalOpenAiExecutionObserver {
                override fun onProgress(progress: ClinicalOpenAiProgress) = Unit

                override fun onExecutionSnapshot(snapshot: ClinicalOpenAiExecutionSnapshot) {
                    snapshots += snapshot
                }
            }
            val failure = runCatching {
                testClient(server, maxResponseBytes = responseLimit).analyze(
                    payload(datasetFixture()),
                    credentialProvider = { SECRET },
                    progress = observer
                )
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(ClinicalOpenAiException.ServerFailure::class.java)
            assertSanitized(failure)
            assertThat(snapshots.last().maxResponseBytes)
                .isEqualTo(responseLimit + 1L)
            assertThat(snapshots.last().totalResponseBytes)
                .isEqualTo(responseLimit + 1L)
            assertThat(snapshots.toString()).doesNotContain(privateBody)
        }
    }

    @Test
    fun timeoutIsTypedAndSanitized() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val snapshots = CopyOnWriteArrayList<ClinicalOpenAiExecutionSnapshot>()
            val observer = object :
                ClinicalOpenAiProgressCallback,
                ClinicalOpenAiExecutionObserver {
                override fun onProgress(progress: ClinicalOpenAiProgress) = Unit

                override fun onExecutionSnapshot(snapshot: ClinicalOpenAiExecutionSnapshot) {
                    snapshots += snapshot
                }
            }
            val failure = runCatching {
                testClient(
                    server,
                    timeouts = ClinicalOpenAiTimeouts(
                        connectMillis = 200,
                        writeMillis = 200,
                        readMillis = 200,
                        callMillis = 300
                    )
                ).analyze(
                    payload(datasetFixture()),
                    credentialProvider = { SECRET },
                    progress = observer
                )
            }.exceptionOrNull()
            val request = server.takeRequest()

            assertThat(failure).isInstanceOf(ClinicalOpenAiException.Timeout::class.java)
            assertThat(failure?.cause).isNull()
            assertSanitized(failure)
            assertThat(snapshots.last().totalRequestBytes).isEqualTo(request.bodySize)
            assertThat(snapshots.last().totalResponseBytes).isEqualTo(0L)
        }
    }

    @Test
    fun coroutineCancellationCancelsOkHttpCall() = runTest {
        MockWebServer().use { server ->
            val requestStarted = CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    requestStarted.countDown()
                    return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
            }
            val client = testClient(
                server,
                timeouts = ClinicalOpenAiTimeouts(5_000, 5_000, 5_000, 5_000)
            )
            val deferred = async(Dispatchers.IO) {
                client.analyze(payload(datasetFixture()), { SECRET })
            }
            assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue()

            deferred.cancel()

            val failure = runCatching { deferred.await() }.exceptionOrNull()
            assertThat(failure).isInstanceOf(CancellationException::class.java)
        }
    }

    @Test
    fun underBudgetSendsExactlyOnce() = runTest {
        MockWebServer().use { server ->
            server.enqueue(successResponse())
            val dataset = datasetFixture(days = 2, rowsPerDay = 2)
            val client = testClient(server, requestByteBudget = 256 * 1_024)

            val result = client.analyze(payload(dataset), { SECRET })

            assertThat(server.requestCount).isEqualTo(1)
            assertThat(result.metadata.chunkCount).isEqualTo(1)
            assertThat(result.metadata.usedSynthesis).isFalse()
            assertThat(result.metadata.reductionLevels).isEqualTo(0)
            assertThat(result.metadata.coverageLedgerHash).isNull()
        }
    }

    @Test
    fun underBudgetPublishesSynthesisValidationAndOnlyCompletesAtTerminal() = runTest {
        MockWebServer().use { server ->
            server.enqueue(successResponse())
            val observed = CopyOnWriteArrayList<ClinicalOpenAiProgress>()

            testClient(server).analyze(
                payload(datasetFixture(days = 2, rowsPerDay = 2)),
                credentialProvider = { SECRET },
                progress = ClinicalOpenAiProgressCallback { observed += it }
            )

            assertThat(observed.map(ClinicalOpenAiProgress::stage)).containsExactly(
                ClinicalOpenAiProgressStage.PREPARING,
                ClinicalOpenAiProgressStage.SYNTHESIZING,
                ClinicalOpenAiProgressStage.VALIDATING,
                ClinicalOpenAiProgressStage.COMPLETED
            ).inOrder()
            assertThat(observed.dropLast(1).map { it.index to it.count }).containsExactly(
                0 to 1,
                0 to 1,
                0 to 1
            ).inOrder()
            assertThat(observed.last().index to observed.last().count).isEqualTo(1 to 1)
        }
    }

    @Test
    fun serverFailurePublishesBoundedPartialExecutionSnapshotWithExactRequestBytes() =
        runTest {
            MockWebServer().use { server ->
                val privateBody = "ошибка датчика 🔒"
                server.enqueue(
                    MockResponse().setResponseCode(500).setBody(privateBody)
                )
                val snapshots = CopyOnWriteArrayList<ClinicalOpenAiExecutionSnapshot>()
                val observer = object :
                    ClinicalOpenAiProgressCallback,
                    ClinicalOpenAiExecutionObserver {
                    override fun onProgress(progress: ClinicalOpenAiProgress) = Unit

                    override fun onExecutionSnapshot(
                        snapshot: ClinicalOpenAiExecutionSnapshot
                    ) {
                        snapshots += snapshot
                    }
                }

                val failure = runCatching {
                    testClient(server).analyze(
                        payload(datasetFixture(days = 2, rowsPerDay = 2)),
                        credentialProvider = { SECRET },
                        progress = observer
                    )
                }.exceptionOrNull()
                val request = server.takeRequest()

                assertThat(failure).isInstanceOf(ClinicalOpenAiException.ServerFailure::class.java)
                assertThat(snapshots).isNotEmpty()
                assertThat(snapshots.last().maxRequestBytes).isEqualTo(request.bodySize)
                assertThat(snapshots.last().totalRequestBytes).isEqualTo(request.bodySize)
                val responseBytes = privateBody.toByteArray(Charsets.UTF_8).size.toLong()
                assertThat(snapshots.last().maxResponseBytes).isEqualTo(responseBytes)
                assertThat(snapshots.last().totalResponseBytes).isEqualTo(responseBytes)
                assertThat(snapshots.last().durationMs).isAtLeast(0L)
                assertThat(snapshots.last().sourceRows.glucose).isGreaterThan(0)
                assertThat(failure.toString()).doesNotContain(privateBody)
                assertThat(snapshots.toString()).doesNotContain(privateBody)
            }
        }

    @Test
    fun emptyUnauthorizedBodyRecordsZeroResponseBytesAndPreservesFailureKind() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody(""))
            val snapshots = CopyOnWriteArrayList<ClinicalOpenAiExecutionSnapshot>()
            val observer = object :
                ClinicalOpenAiProgressCallback,
                ClinicalOpenAiExecutionObserver {
                override fun onProgress(progress: ClinicalOpenAiProgress) = Unit

                override fun onExecutionSnapshot(snapshot: ClinicalOpenAiExecutionSnapshot) {
                    snapshots += snapshot
                }
            }

            val failure = runCatching {
                testClient(server).analyze(
                    payload(datasetFixture()),
                    credentialProvider = { SECRET },
                    progress = observer
                )
            }.exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(ClinicalOpenAiException.Unauthorized::class.java)
            assertThat(snapshots.last().maxResponseBytes).isEqualTo(0L)
            assertThat(snapshots.last().totalResponseBytes).isEqualTo(0L)
        }
    }

    @Test
    fun cancellationDuringActiveCallPublishesTerminalPartialExecutionSnapshot() = runTest {
        MockWebServer().use { server ->
            val requestStarted = CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(
                    request: okhttp3.mockwebserver.RecordedRequest
                ): MockResponse {
                    requestStarted.countDown()
                    return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
            }
            val snapshots = CopyOnWriteArrayList<ClinicalOpenAiExecutionSnapshot>()
            val observer = object :
                ClinicalOpenAiProgressCallback,
                ClinicalOpenAiExecutionObserver {
                override fun onProgress(progress: ClinicalOpenAiProgress) = Unit

                override fun onExecutionSnapshot(snapshot: ClinicalOpenAiExecutionSnapshot) {
                    snapshots += snapshot
                }
            }
            val deferred = async(Dispatchers.IO) {
                testClient(
                    server,
                    timeouts = ClinicalOpenAiTimeouts(5_000, 5_000, 30_000, 30_000)
                ).analyze(
                    payload(datasetFixture()),
                    credentialProvider = { SECRET },
                    progress = observer
                )
            }
            assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue()
            val request = server.takeRequest()

            deferred.cancel()

            val failure = runCatching { deferred.await() }.exceptionOrNull()
            assertThat(failure).isInstanceOf(CancellationException::class.java)
            assertThat(snapshots.last().totalRequestBytes).isEqualTo(request.bodySize)
            assertThat(snapshots.last().totalResponseBytes).isEqualTo(0L)
        }
    }

    @Test
    fun executionObserverFailureIsSwallowedWithoutChangingSuccessfulResult() = runTest {
        MockWebServer().use { server ->
            server.enqueue(successResponse())
            val observerCalls = AtomicInteger()
            val observer = object :
                ClinicalOpenAiProgressCallback,
                ClinicalOpenAiExecutionObserver {
                override fun onProgress(progress: ClinicalOpenAiProgress) = Unit

                override fun onExecutionSnapshot(snapshot: ClinicalOpenAiExecutionSnapshot) {
                    observerCalls.incrementAndGet()
                    throw IllegalStateException("observer-private-error")
                }
            }

            val result = testClient(server).analyze(
                payload(datasetFixture()),
                credentialProvider = { SECRET },
                progress = observer
            )

            assertThat(result.report.summary7dStatus).isEqualTo(ClinicalSummaryStatus.STABLE)
            assertThat(observerCalls.get()).isAtLeast(2)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun underBudgetMetadataTracksExactTransportBytesDurationAndSourceRows() = runTest {
        MockWebServer().use { server ->
            val responseBody = successResponseBody()
            server.enqueue(MockResponse().setBody(responseBody))
            val dataset = datasetFixture(days = 2, rowsPerDay = 4)
            val monotonicNanos = ArrayDeque(listOf(1_000_000L, 8_000_000L))
            val client = testClient(
                server = server,
                monotonicClockNanos = { monotonicNanos.removeFirst() }
            )

            val result = client.analyze(payload(dataset), { SECRET })
            val transportedRequestBytes = server.takeRequest().bodySize

            assertThat(result.metadata.maxRequestBytes)
                .isEqualTo(transportedRequestBytes)
            assertThat(result.metadata.totalRequestBytes)
                .isEqualTo(transportedRequestBytes)
            assertThat(result.metadata.maxResponseBytes)
                .isEqualTo(responseBody.toByteArray(Charsets.UTF_8).size.toLong())
            assertThat(result.metadata.totalResponseBytes)
                .isEqualTo(responseBody.toByteArray(Charsets.UTF_8).size.toLong())
            assertThat(result.metadata.durationMs).isEqualTo(7L)
            assertThat(result.metadata.sourceRows).isEqualTo(
                ClinicalSourceRowCounts(
                    glucose = dataset.detail24h.glucose.size +
                        dataset.detail24h.calibratedGlucose.size +
                        dataset.glucose7d.size +
                        dataset.glucose30d.size,
                    insulin = dataset.detail24h.therapy.count { it.insulinU != null } +
                        dataset.therapy7d.count { it.insulinU != null } +
                        dataset.therapy30d.count { it.insulinU != null },
                    carbs = dataset.detail24h.therapy.count { it.carbsG != null } +
                        dataset.therapy7d.count { it.carbsG != null } +
                        dataset.therapy30d.count { it.carbsG != null },
                    targets = dataset.detail24h.targets.size +
                        dataset.targets7d.size +
                        dataset.targets30d.size
                )
            )
        }
    }

    @Test
    fun concurrentAnalyzeKeepsExecutionMetadataIsolated() = runTest {
        MockWebServer().use { server ->
            val requestBytesByHash = ConcurrentHashMap<String, Long>()
            val responseBody = successResponseBody()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(
                    request: okhttp3.mockwebserver.RecordedRequest
                ): MockResponse {
                    val requestJson = JsonParser.parseString(
                        request.body.clone().readUtf8()
                    ).asJsonObject
                    val input = JsonParser.parseString(requestJson.get("input").asString)
                        .asJsonObject
                    requestBytesByHash[input.get("requestHash").asString] = request.bodySize
                    return MockResponse().setBody(responseBody)
                }
            }
            val firstPayload = payload(datasetFixture(days = 1, rowsPerDay = 3))
            val secondPayload = payload(datasetFixture(days = 3, rowsPerDay = 7))
            val monotonic = AtomicLong(0L)
            val client = testClient(
                server = server,
                requestByteBudget = 512 * 1_024,
                monotonicClockNanos = {
                    monotonic.addAndGet(1_000_000L)
                }
            )

            val results = listOf(
                async { client.analyze(firstPayload, { SECRET }) },
                async { client.analyze(secondPayload, { SECRET }) }
            ).map { it.await() }

            assertThat(requestBytesByHash).hasSize(2)
            results.forEach { result ->
                val expectedRequestBytes = checkNotNull(
                    requestBytesByHash[result.metadata.requestHash]
                )
                assertThat(result.metadata.totalRequestBytes)
                    .isEqualTo(expectedRequestBytes)
                assertThat(result.metadata.maxRequestBytes)
                    .isEqualTo(expectedRequestBytes)
                assertThat(result.metadata.totalResponseBytes)
                    .isEqualTo(responseBody.toByteArray(Charsets.UTF_8).size.toLong())
            }
        }
    }

    @Test
    fun denseThirtyDayDatasetUsesRecursiveMapReduceWithinBudget() = runTest {
        MockWebServer().use { server ->
            val requestBodies = CopyOnWriteArrayList<String>()
            val responseBodyBytes = CopyOnWriteArrayList<Long>()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(
                    request: okhttp3.mockwebserver.RecordedRequest
                ): MockResponse {
                    val body = request.body.clone().readUtf8()
                    requestBodies += body
                    val schemaName = JsonParser.parseString(body).asJsonObject
                        .getAsJsonObject("text")
                        .getAsJsonObject("format")
                        .get("name")
                        .asString
                    val responseBody = if (
                        schemaName == ClinicalOpenAiClient.CHUNK_SCHEMA_NAME
                    ) {
                        chunkSuccessResponseBody()
                    } else {
                        successResponseBody()
                    }
                    responseBodyBytes += responseBody.toByteArray(Charsets.UTF_8).size.toLong()
                    return MockResponse().setBody(responseBody)
                }
            }
            val dataset = datasetFixture(days = 30, rowsPerDay = 64)
            val budget = 23 * 1_024
            val observed = mutableListOf<ClinicalOpenAiProgress>()
            val client = testClient(server, requestByteBudget = budget)

            val result = client.analyze(
                payload(dataset),
                { SECRET },
                ClinicalOpenAiProgressCallback(observed::add)
            )

            assertThat(result.metadata.usedSynthesis).isTrue()
            assertThat(result.metadata.chunkCount).isGreaterThan(32)
            assertThat(result.metadata.reductionLevels).isGreaterThan(1)
            assertThat(result.metadata.coverageLedgerHash)
                .matches(java.util.regex.Pattern.compile("[0-9a-f]{64}"))
            assertThat(result.metadata.requestHash).isEqualTo(payload(dataset).sha256)
            val requestBodyBytes = requestBodies.map {
                it.toByteArray(Charsets.UTF_8).size.toLong()
            }
            assertThat(result.metadata.maxRequestBytes)
                .isEqualTo(requestBodyBytes.maxOrNull())
            assertThat(result.metadata.totalRequestBytes)
                .isEqualTo(requestBodyBytes.sum())
            assertThat(result.metadata.maxResponseBytes)
                .isEqualTo(responseBodyBytes.maxOrNull())
            assertThat(result.metadata.totalResponseBytes)
                .isEqualTo(responseBodyBytes.sum())
            assertThat(result.metadata.durationMs).isAtLeast(0L)
            assertThat(observed.any {
                it.stage == ClinicalOpenAiProgressStage.REDUCING && it.level > 0
            }).isTrue()
            assertThat(observed.any {
                it.stage == ClinicalOpenAiProgressStage.VALIDATING
            }).isTrue()
            assertThat(observed.last().stage)
                .isEqualTo(ClinicalOpenAiProgressStage.COMPLETED)
            assertThat(requestBodies).isNotEmpty()
            requestBodies.forEach { body ->
                assertThat(body.toByteArray(Charsets.UTF_8).size).isAtMost(budget)
                assertThat(JsonParser.parseString(body).asJsonObject.get("store").asBoolean)
                    .isFalse()
            }
            val finalInput = JsonParser.parseString(requestBodies.last()).asJsonObject
                .get("input")
                .asString
            val finalInputJson = JsonParser.parseString(finalInput).asJsonObject
            val rootReport = finalInputJson.getAsJsonObject("rootReport")
            val rootDigestHash = finalInputJson.get("rootDigestHash").asString
            val coverageLedgerHash = finalInputJson.getAsJsonObject("coverageLedger")
                .get("ledgerHash").asString
            val requestHash = finalInputJson.getAsJsonObject("original")
                .get("requestHash").asString
            assertThat(finalInput).contains("\"rootReport\"")
            assertThat(finalInput).contains("\"coverageLedger\"")
            assertThat(rootReport.has("sourceHashes")).isFalse()
            assertThat(finalInputJson.get("rootReportHash").asString)
                .isEqualTo(ClinicalReportDatasetBuilder.sha256(rootReport.toString()))
            assertThat(rootDigestHash).matches(java.util.regex.Pattern.compile("[0-9a-f]{64}"))
            assertThat(finalInputJson.get("ledgerRootBindingHash").asString)
                .isEqualTo(
                    expectedLedgerRootBindingHash(
                        rootDigestHash,
                        coverageLedgerHash,
                        requestHash
                    )
                )
            assertThat(finalInput).contains("\"summary7d\"")
            assertThat(finalInput).contains("\"summary30d\"")
            assertThat(finalInput).contains("\"currentSnapshot\"")
            assertThat(finalInput).doesNotContain("\"dataset\"")
            assertThat(finalInput).doesNotContain("\"chunkReports\"")
            listOf(
                "\"d24\"",
                "\"g7\"",
                "\"e7\"",
                "\"t7\"",
                "\"g30\"",
                "\"e30\"",
                "\"t30\"",
                "\"glucoseSeries\""
            ).forEach { rawField ->
                assertThat(finalInput).doesNotContain(rawField)
            }
        }
    }

    @Test
    fun forcedPartitionCarriesOptionalCurrentMetadataOnlyInSynthesisRequest() = runTest {
        MockWebServer().use { server ->
            val requestInputs = CopyOnWriteArrayList<JsonObject>()
            val delegate = structuredSuccessDispatcher()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(
                    request: okhttp3.mockwebserver.RecordedRequest
                ): MockResponse {
                    val requestJson = JsonParser.parseString(
                        request.body.clone().readUtf8()
                    ).asJsonObject
                    requestInputs += JsonParser.parseString(requestJson.get("input").asString)
                        .asJsonObject
                    return delegate.dispatch(request)
                }
            }
            val current = ClinicalCurrentSnapshot(
                selectedIsfMmolPerUnit = 2.0,
                selectedIsfSourceCode = 1.0,
                selectedCrGramsPerUnit = 10.0,
                selectedCrSourceCode = 3.0,
                sensitivitySettingsRevision = 42L,
                selectedIsfSource = "AAPS",
                selectedCrSource = "COPILOT_NATIVE"
            )
            val dataset = datasetFixture(days = 4, rowsPerDay = 24).copy(
                currentSnapshot = current
            )
            val client = testClient(
                server,
                requestByteBudget = requestBytesFor(dataset) - 1
            )

            val result = client.analyze(payload(dataset), { SECRET })

            val leafInputs = requestInputs.filter {
                it.get("kind")?.asString == "clinical_dataset_chunk"
            }
            val synthesisInputs = requestInputs.filter {
                it.get("kind")?.asString == "clinical_map_reduce_synthesis"
            }
            assertThat(result.metadata.usedSynthesis).isTrue()
            assertThat(leafInputs).hasSize(result.metadata.chunkCount)
            assertThat(synthesisInputs).hasSize(1)
            val leafCurrents = leafInputs.map {
                it.getAsJsonObject("dataset").getAsJsonObject("c")
            }
            leafCurrents.forEach { leafCurrent ->
                assertThat(leafCurrent.has("sensitivityRev")).isFalse()
                assertThat(leafCurrent.has("isfProvenance")).isFalse()
                assertThat(leafCurrent.has("crProvenance")).isFalse()
            }
            val synthesisCurrent = synthesisInputs.single()
                .getAsJsonObject("currentSnapshot")
            assertThat(synthesisCurrent.get("sensitivityRev").asLong).isEqualTo(42L)
            assertThat(synthesisCurrent.get("isfSrc").asDouble).isEqualTo(1.0)
            assertThat(synthesisCurrent.get("isfProvenance").asString).isEqualTo("AAPS")
            assertThat(synthesisCurrent.get("crSrc").asDouble).isEqualTo(3.0)
            assertThat(synthesisCurrent.get("crProvenance").asString)
                .isEqualTo("COPILOT_NATIVE")
            listOf("sensitivityRev", "isfProvenance", "crProvenance").forEach { key ->
                assertThat((leafCurrents + synthesisCurrent).count { it.has(key) }).isEqualTo(1)
            }
        }
    }

    @Test
    fun partitionedAnalyzeMaterializesOnlyPreparedRootAndLeafCanonicalStrings() = runTest {
        MockWebServer().use { server ->
            val materializations = AtomicInteger()
            val clinicalInputMaterializations = AtomicInteger()
            val clinicalRequestBodyMaterializations = AtomicInteger()
            val maximumRetainedLeafArtifacts = AtomicInteger()
            val probe = object : ClinicalDatasetSerializationProbe {
                override fun onFullDatasetAssemblyStarted() {
                    materializations.incrementAndGet()
                }

                override fun onClinicalInputMaterialized() {
                    clinicalInputMaterializations.incrementAndGet()
                }

                override fun onClinicalRequestBodyMaterialized() {
                    clinicalRequestBodyMaterializations.incrementAndGet()
                }

                override fun onPartitionLeafRetained(retainedLargeArtifacts: Int) {
                    maximumRetainedLeafArtifacts.accumulateAndGet(retainedLargeArtifacts, ::maxOf)
                }
            }
            val dataset = datasetFixture(days = 4, rowsPerDay = 24)
            val compact = ClinicalReportDatasetBuilder.serializeForTest(
                dataset,
                ClinicalOpenAiClient.MAX_COMPACT_DATASET_BYTES,
                probe
            )
            val reportPayload = ClinicalReportPayload(
                dataset,
                compact,
                ClinicalReportDatasetBuilder.sha256(compact)
            )
            val client = testClient(
                server,
                requestByteBudget = requestBytesFor(dataset) - 1,
                serializationProbe = probe
            )
            server.dispatcher = structuredSuccessDispatcher()

            val result = client.analyze(reportPayload, { SECRET })

            assertThat(result.metadata.usedSynthesis).isTrue()
            assertThat(materializations.get()).isEqualTo(result.metadata.chunkCount + 1)
            assertThat(clinicalInputMaterializations.get()).isEqualTo(0)
            assertThat(clinicalRequestBodyMaterializations.get())
                .isEqualTo(result.metadata.chunkCount)
            assertThat(maximumRetainedLeafArtifacts.get()).isEqualTo(1)
        }
    }

    @Test
    fun partitionedUploadPlanMaterializesOneRootAndEachLeafOnly() = runTest {
        MockWebServer().use { server ->
            val materializations = AtomicInteger()
            val clinicalInputMaterializations = AtomicInteger()
            val clinicalRequestBodyMaterializations = AtomicInteger()
            val probe = object : ClinicalDatasetSerializationProbe {
                override fun onFullDatasetAssemblyStarted() {
                    materializations.incrementAndGet()
                }

                override fun onClinicalInputMaterialized() {
                    clinicalInputMaterializations.incrementAndGet()
                }

                override fun onClinicalRequestBodyMaterialized() {
                    clinicalRequestBodyMaterializations.incrementAndGet()
                }
            }
            val dataset = datasetFixture(days = 4, rowsPerDay = 24)
            val client = testClient(
                server,
                requestByteBudget = requestBytesFor(dataset) - 1,
                serializationProbe = probe
            )

            val plan = client.buildUploadPlan(dataset)

            assertThat(plan.requiresSynthesis).isTrue()
            assertThat(materializations.get()).isEqualTo(plan.chunks.size + 1)
            assertThat(clinicalInputMaterializations.get()).isEqualTo(0)
            assertThat(clinicalRequestBodyMaterializations.get()).isEqualTo(0)
            assertThat(ClinicalPartitionLeaf::class.java.declaredFields.map { it.name })
                .containsNoneOf("requestInput", "requestBodyJson")
        }
    }

    @Test
    fun cancellationDuringFullInputSizeProbeStopsBeforeBodyOrLeafMaterialization() = runTest {
        MockWebServer().use { server ->
            lateinit var operation: Deferred<ClinicalOpenAiResult>
            val scannedChunks = AtomicInteger()
            val requestBodies = AtomicInteger()
            val leafCanonicalizations = AtomicInteger()
            val probe = object : ClinicalDatasetSerializationProbe {
                override fun onTransportInputChunkProcessed() {
                    if (scannedChunks.incrementAndGet() == 2) {
                        operation.cancel(CancellationException("request-size cancellation"))
                    }
                }

                override fun onClinicalRequestBodyMaterialized() {
                    requestBodies.incrementAndGet()
                }

                override fun onPartitionLeafRetained(retainedLargeArtifacts: Int) {
                    leafCanonicalizations.incrementAndGet()
                }
            }
            val dataset = datasetFixture(days = 4, rowsPerDay = 300)
            val reportPayload = payload(dataset)
            val client = testClient(
                server,
                requestByteBudget = requestBytesFor(dataset) - 1,
                serializationProbe = probe
            )
            server.dispatcher = structuredSuccessDispatcher()
            operation = async(start = CoroutineStart.LAZY) {
                client.analyze(reportPayload, { SECRET })
            }

            operation.start()
            val failure = runCatching { operation.await() }.exceptionOrNull()

            assertThat(failure).isInstanceOf(CancellationException::class.java)
            assertThat(scannedChunks.get()).isAtLeast(2)
            assertThat(requestBodies.get()).isEqualTo(0)
            assertThat(leafCanonicalizations.get()).isEqualTo(0)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun cancellationDuringStreamingWireCoverageStopsBeforeAnyRequest() = runTest {
        MockWebServer().use { server ->
            lateinit var operation: Deferred<ClinicalOpenAiResult>
            val coverageRows = AtomicInteger()
            val probe = object : ClinicalDatasetSerializationProbe {
                override fun onWireCoverageRowRead() {
                    if (coverageRows.incrementAndGet() == 300) {
                        operation.cancel(CancellationException("coverage cancellation"))
                    }
                }
            }
            val dataset = datasetFixture(days = 4, rowsPerDay = 300)
            val client = testClient(
                server,
                requestByteBudget = requestBytesFor(dataset) - 1,
                serializationProbe = probe
            )
            server.dispatcher = structuredSuccessDispatcher()
            operation = async(start = CoroutineStart.LAZY) {
                client.analyze(payload(dataset), { SECRET })
            }

            operation.start()
            val failure = runCatching { operation.await() }.exceptionOrNull()

            assertThat(failure).isInstanceOf(CancellationException::class.java)
            assertThat(coverageRows.get()).isEqualTo(300)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun partitionLeafLimitFailsClosedBeforeAnyNetworkRequest() = runTest {
        MockWebServer().use { server ->
            val canonicalizations = AtomicInteger()
            val probe = object : ClinicalDatasetSerializationProbe {
                override fun onFullDatasetAssemblyStarted() {
                    canonicalizations.incrementAndGet()
                }
            }
            val dataset = datasetFixture(days = 3, rowsPerDay = 2)
            val client = testClient(
                server = server,
                requestByteBudget = requestBytesFor(dataset) - 1,
                serializationProbe = probe,
                partitionLeafLimit = 2
            )
            server.dispatcher = structuredSuccessDispatcher()

            val failure = runCatching {
                client.analyze(payload(dataset), { SECRET })
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(ClinicalOpenAiException.RequestTooLarge::class.java)
            assertThat(canonicalizations.get()).isAtMost(2)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun partitionRequestLimitAllowsBoundaryAndBlocksNextNetworkCall() = runTest {
        MockWebServer().use { server ->
            val dataset = datasetFixture(days = 4, rowsPerDay = 300)
            val requestByteBudget = requestBytesFor(dataset) - 1
            val sizingClient = testClient(server, requestByteBudget = requestByteBudget)
            val partition = sizingClient.buildPartitionPlanForTest(dataset)
            assertThat(partition.leaves.size).isGreaterThan(1)
            val client = testClient(
                server = server,
                requestByteBudget = requestByteBudget,
                partitionRequestLimit = partition.leaves.size
            )
            server.dispatcher = structuredSuccessDispatcher()

            val failure = runCatching {
                client.analyze(payload(dataset), { SECRET })
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(ClinicalOpenAiException.RequestTooLarge::class.java)
            assertThat(server.requestCount).isEqualTo(partition.leaves.size)
        }
    }

    @Test
    fun partitionedUploadCanonicalActivitiesMatchHalfOpenChunkDatasets() = runTest {
        MockWebServer().use { server ->
            val base = datasetFixture(days = 4, rowsPerDay = 24)
            val firstMidnight = base.summary30d.fromTs + DAY_MS / 2L
            val activities = listOf(
                ClinicalPlannedActivitySummary(
                    "STRENGTH",
                    "LIGHT",
                    firstMidnight + 60_000L,
                    20
                ),
                ClinicalPlannedActivitySummary("WALKING", "MEDIUM", firstMidnight, 30),
                ClinicalPlannedActivitySummary(
                    "AEROBIC",
                    "HIGH",
                    base.summary30d.fromTs + 60_000L,
                    45
                )
            )
            val source = base.copy(
                energyProfile = ClinicalEnergyProfileSummary(shareProfileWithAi = true),
                plannedActivities = activities
            )
            fun plannedRows(canonicalJson: String): List<String> =
                JsonParser.parseString(canonicalJson).asJsonObject
                    .getAsJsonArray("pa")
                    .map { it.toString() }
            val expectedRows = plannedRows(ClinicalReportDatasetBuilder.serialize(source))
            val client = testClient(
                server,
                requestByteBudget = requestBytesFor(source) - 1
            )

            val plan = client.buildUploadPlan(source)

            assertThat(plan.requiresSynthesis).isTrue()
            assertThat(plan.chunks.flatMap { plannedRows(it.canonicalJson) })
                .containsExactlyElementsIn(expectedRows).inOrder()
            plan.chunks.forEach { chunk ->
                assertThat(plannedRows(chunk.canonicalJson))
                    .containsExactlyElementsIn(
                        plannedRows(ClinicalReportDatasetBuilder.serialize(chunk.dataset))
                    ).inOrder()
                assertThat(chunk.dataset.plannedActivities.all { activity ->
                    activity.plannedStartMs >= chunk.metadata.windowFromTs &&
                        activity.plannedStartMs < chunk.metadata.windowThroughTs
                }).isTrue()
            }
        }
    }

    @Test
    fun analyzeSendsPlannedActivitiesOnceAcrossRecursiveHalfOpenWireLeaves() = runTest {
        MockWebServer().use { server ->
            val requestInputs = CopyOnWriteArrayList<JsonObject>()
            val delegate = structuredSuccessDispatcher()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(
                    request: okhttp3.mockwebserver.RecordedRequest
                ): MockResponse {
                    val requestJson = JsonParser.parseString(
                        request.body.clone().readUtf8()
                    ).asJsonObject
                    requestInputs += JsonParser.parseString(requestJson.get("input").asString)
                        .asJsonObject
                    return delegate.dispatch(request)
                }
            }
            val base = datasetFixture(days = 1, rowsPerDay = 500).copy(
                energyProfile = ClinicalEnergyProfileSummary(shareProfileWithAi = true)
            )
            val client = testClient(server, requestByteBudget = 23 * 1_024)
            val seedPartition = client.buildPartitionPlanForTest(base)
            val boundary = seedPartition.leaves.zipWithNext()
                .first { (left, right) ->
                    left.descriptor.throughTsExclusive == right.descriptor.fromTs &&
                        (left.descriptor.depth > 0 || right.descriptor.depth > 0) &&
                        left.descriptor.throughTsExclusive > base.summary30d.fromTs &&
                        left.descriptor.throughTsExclusive < base.summary30d.throughTs
                }
                .first.descriptor.throughTsExclusive
            val activities = listOf(
                ClinicalPlannedActivitySummary(
                    "MIXED",
                    "MEDIUM",
                    base.summary30d.fromTs - 30L * 60_000L,
                    60
                ),
                ClinicalPlannedActivitySummary("AEROBIC", "MEDIUM", boundary - 1L, 30),
                ClinicalPlannedActivitySummary("WALKING", "LIGHT", boundary, 20),
                ClinicalPlannedActivitySummary("STRENGTH", "HIGH", boundary + 1L, 45)
            )
            val source = base.copy(plannedActivities = activities.reversed())
            val expectedPartition = client.buildPartitionPlanForTest(source)
            val permutedPartition = client.buildPartitionPlanForTest(
                source.copy(plannedActivities = activities)
            )

            val result = client.analyze(payload(source), { SECRET })

            val leafInputs = requestInputs.filter {
                it.get("kind")?.asString == "clinical_dataset_chunk"
            }
            val synthesisInputs = requestInputs.filter {
                it.get("kind")?.asString == "clinical_map_reduce_synthesis"
            }
            fun activityTypes(input: JsonObject): List<String> =
                input.getAsJsonObject("dataset").getAsJsonArray("pa")
                    .map { it.asJsonObject.get("type").asString }
            assertThat(seedPartition.leaves.map { it.descriptor.depth }.max())
                .isGreaterThan(0)
            assertThat(expectedPartition.leaves.map { it.descriptor.depth }.max())
                .isGreaterThan(0)
            assertThat(expectedPartition.leaves.any {
                it.descriptor.throughTsExclusive == boundary
            }).isTrue()
            assertThat(expectedPartition.leaves.any {
                it.descriptor.fromTs == boundary
            }).isTrue()
            assertThat(permutedPartition.leaves.map(ClinicalPartitionLeaf::canonicalJson))
                .containsExactlyElementsIn(
                    expectedPartition.leaves.map(ClinicalPartitionLeaf::canonicalJson)
                ).inOrder()
            assertThat(result.metadata.usedSynthesis).isTrue()
            assertThat(result.metadata.chunkCount).isEqualTo(expectedPartition.leaves.size)
            assertThat(result.metadata.reductionLevels).isGreaterThan(0)
            assertThat(result.metadata.coverageLedgerHash)
                .matches(java.util.regex.Pattern.compile("[0-9a-f]{64}"))
            assertThat(server.requestCount).isEqualTo(requestInputs.size)
            assertThat(leafInputs).hasSize(result.metadata.chunkCount)
            assertThat(synthesisInputs).hasSize(1)
            assertThat(leafInputs.map { it.getAsJsonObject("dataset").toString() })
                .containsExactlyElementsIn(
                    expectedPartition.leaves.map(ClinicalPartitionLeaf::canonicalJson)
                ).inOrder()

            leafInputs.forEach { input ->
                val metadata = input.getAsJsonObject("metadata")
                val fromTs = metadata.get("fromTs").asLong
                val throughTsExclusive = metadata.get("throughTsExclusive").asLong
                val expectedTypes = activities
                    .filter {
                        maxOf(it.plannedStartMs, base.summary30d.fromTs) >= fromTs &&
                            maxOf(it.plannedStartMs, base.summary30d.fromTs) < throughTsExclusive
                    }
                    .sortedWith(CLINICAL_PLANNED_ACTIVITY_ORDER)
                    .map(ClinicalPlannedActivitySummary::type)
                assertThat(activityTypes(input)).containsExactlyElementsIn(expectedTypes).inOrder()
            }
            assertThat(leafInputs.flatMap(::activityTypes))
                .containsExactly("MIXED", "AEROBIC", "WALKING", "STRENGTH").inOrder()

            val carryInOwner = leafInputs.single { activityTypes(it).contains("MIXED") }
                .getAsJsonObject("metadata")
            val beforeOwner = leafInputs.single { activityTypes(it).contains("AEROBIC") }
                .getAsJsonObject("metadata")
            val boundaryOwner = leafInputs.single { activityTypes(it).contains("WALKING") }
                .getAsJsonObject("metadata")
            val afterOwner = leafInputs.single { activityTypes(it).contains("STRENGTH") }
                .getAsJsonObject("metadata")
            assertThat(carryInOwner.get("fromTs").asLong).isEqualTo(base.summary30d.fromTs)
            assertThat(beforeOwner.get("throughTsExclusive").asLong).isEqualTo(boundary)
            assertThat(boundaryOwner.get("fromTs").asLong).isEqualTo(boundary)
            assertThat(afterOwner).isEqualTo(boundaryOwner)
        }
    }

    @Test
    fun compactComparisonAndHashMismatchFailClosedBeforeCredentialRead() = runTest {
        MockWebServer().use { server ->
            val dataset = datasetFixture()
            val valid = payload(dataset)
            val mutatedCompact = valid.compactJson.replaceFirst("\"zone\":\"UTC\"", "\"zone\":\"Etc/UTC\"")
            val invalidPayloads = listOf(
                valid.copy(
                    compactJson = mutatedCompact,
                    sha256 = ClinicalReportDatasetBuilder.sha256(mutatedCompact)
                ),
                valid.copy(sha256 = "0".repeat(64))
            )
            val credentialReads = AtomicInteger()

            invalidPayloads.forEach { invalid ->
                val failure = runCatching {
                    testClient(server).analyze(
                        invalid,
                        credentialProvider = {
                            credentialReads.incrementAndGet()
                            SECRET
                        }
                    )
                }.exceptionOrNull()

                assertThat(failure)
                    .isInstanceOf(ClinicalOpenAiException.InvalidInput::class.java)
                assertSanitized(failure)
            }
            assertThat(credentialReads.get()).isEqualTo(0)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun sendsExactPlannedLeafAndReductionBodies() = runTest {
        MockWebServer().use { server ->
            val dataset = datasetFixture(days = 4, rowsPerDay = 300)
            val budget = requestBytesFor(dataset) - 1
            val client = testClient(server, requestByteBudget = budget)
            val partition = client.buildPartitionPlanForTest(dataset)
            val expectedReductionBodies = expectedReductionBodies(client, partition, budget)
            server.dispatcher = structuredSuccessDispatcher()

            client.analyze(payload(dataset), { SECRET })

            val bodies = (0 until server.requestCount).map {
                server.takeRequest().body.readUtf8()
            }
            val expectedLeafBodies = partition.leaves.map { leaf ->
                client.partitionRequestBodyForTest(leaf).toString(Charsets.UTF_8)
            }
            assertThat(bodies.take(partition.leaves.size))
                .containsExactlyElementsIn(expectedLeafBodies)
                .inOrder()
            assertThat(
                bodies.drop(partition.leaves.size).dropLast(1)
            ).containsExactlyElementsIn(expectedReductionBodies).inOrder()
        }
    }

    @Test
    fun singleOversizedLocalDayRecursivelySplitsIntoBoundedLeaves() = runTest {
        MockWebServer().use { server ->
            val dataset = datasetFixture(days = 1, rowsPerDay = 500)
            val budget = 23 * 1_024
            val client = testClient(server, requestByteBudget = budget)

            val partition = client.buildPartitionPlanForTest(dataset)

            assertThat(partition.leaves.size).isGreaterThan(2)
            assertThat(partition.leaves.map { it.descriptor.depth }.max())
                .isGreaterThan(0)
            partition.leaves.forEach { leaf ->
                assertThat(leaf.descriptor.requestBytes).isAtMost(budget)
                assertThat(client.partitionRequestBodyForTest(leaf).size)
                    .isEqualTo(leaf.descriptor.requestBytes)
            }
        }
    }

    @Test
    fun locallyAssignedRootCoverageMismatchIsRejectedByInternalInvariant() = runTest {
        MockWebServer().use { server ->
            val client = testClient(server, requestByteBudget = 64 * 1_024)
            val first = client.buildPartitionPlanForTest(datasetFixture(days = 3, rowsPerDay = 48))
            val second = client.buildPartitionPlanForTest(datasetFixture(days = 4, rowsPerDay = 48))
            val parsed = ClinicalChunkReportSchemaParser.parse(chunkReportObject().toString())
            val root = ClinicalChunkDigest.create(
                id = "root",
                fromTs = first.coverage.expectedFromTs,
                throughTsExclusive = first.coverage.expectedThroughTsExclusive,
                sourceHashes = first.coverage.leafHashes,
                dataQuality = parsed.dataQuality,
                findings = parsed.findings
            )

            client.verifyRootCoverage(root, first.coverage)
            val failure = runCatching {
                client.verifyRootCoverage(root, second.coverage)
            }.exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(ClinicalOpenAiException.InvalidResponse::class.java)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun invalidLeafSchemaStopsBeforeReductionOrFinalReport() = runTest {
        MockWebServer().use { server ->
            val dataset = datasetFixture(days = 4, rowsPerDay = 300)
            val budget = requestBytesFor(dataset) - 1
            val client = testClient(server, requestByteBudget = budget)
            val invalid = chunkReportObject().apply {
                addProperty("unexpectedTherapyCommand", "private")
            }
            server.enqueue(chunkSuccessResponse(invalid))

            val failure = runCatching {
                client.analyze(payload(dataset), { SECRET })
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(ClinicalOpenAiException.ChunkFailed::class.java)
            assertThat(server.requestCount).isEqualTo(1)
            assertSanitized(failure, "private")
        }
    }

    @Test
    fun invalidReductionSchemaStopsBeforeFinalReportWithBoundedCalls() = runTest {
        MockWebServer().use { server ->
            val dataset = datasetFixture(days = 4, rowsPerDay = 300)
            val budget = requestBytesFor(dataset) - 1
            val client = testClient(server, requestByteBudget = budget)
            val partition = client.buildPartitionPlanForTest(dataset)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(
                    request: okhttp3.mockwebserver.RecordedRequest
                ): MockResponse {
                    val input = JsonParser.parseString(request.body.clone().readUtf8())
                        .asJsonObject.get("input").asString
                    val reduction = JsonParser.parseString(input).asJsonObject
                        .get("schema")?.asString == "clinical-reduction-input"
                    return if (reduction) {
                        chunkSuccessResponse(
                            chunkReportObject().apply {
                                addProperty("unexpectedTherapyCommand", "private")
                            }
                        )
                    } else {
                        chunkSuccessResponse()
                    }
                }
            }

            val failure = runCatching {
                client.analyze(payload(dataset), { SECRET })
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(ClinicalOpenAiException.ChunkFailed::class.java)
            assertThat(server.requestCount).isEqualTo(partition.leaves.size + 1)
            assertSanitized(failure, "private")
        }
    }

    @Test
    fun reductionFailureIsSanitizedAndStopsWithoutRetry() = runTest {
        MockWebServer().use { server ->
            val dataset = datasetFixture(days = 4, rowsPerDay = 300)
            val budget = requestBytesFor(dataset) - 1
            val client = testClient(server, requestByteBudget = budget)
            val partition = client.buildPartitionPlanForTest(dataset)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(
                    request: okhttp3.mockwebserver.RecordedRequest
                ): MockResponse {
                    val input = JsonParser.parseString(request.body.clone().readUtf8())
                        .asJsonObject.get("input").asString
                    return if (
                        JsonParser.parseString(input).asJsonObject
                            .get("schema")?.asString == "clinical-reduction-input"
                    ) {
                        MockResponse().setResponseCode(500).setBody("private-reduction")
                    } else {
                        chunkSuccessResponse()
                    }
                }
            }

            val failure = runCatching {
                client.analyze(payload(dataset), { SECRET })
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(ClinicalOpenAiException.ChunkFailed::class.java)
            assertThat(server.requestCount).isEqualTo(partition.leaves.size + 1)
            assertSanitized(failure, "private-reduction")
        }
    }

    @Test
    fun cancellationBeforeSecondReductionLevelStopsFurtherRequests() = runTest {
        MockWebServer().use { server ->
            val dataset = datasetFixture(days = 30, rowsPerDay = 64)
            val budget = 23 * 1_024
            val client = testClient(server, requestByteBudget = budget)
            val partition = client.buildPartitionPlanForTest(dataset)
            val firstLevelCount = firstReductionLevelCount(client, partition, budget)
            server.dispatcher = structuredSuccessDispatcher()

            val failure = runCatching {
                client.analyze(
                    payload(dataset),
                    { SECRET },
                    ClinicalOpenAiProgressCallback {
                        if (
                            it.stage == ClinicalOpenAiProgressStage.REDUCING &&
                            it.level == 2
                        ) {
                            throw CancellationException("cancel-between-levels")
                        }
                    }
                )
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(CancellationException::class.java)
            assertThat(server.requestCount)
                .isEqualTo(partition.leaves.size + firstLevelCount)
        }
    }

    @Test
    fun reductionNoProgressFailsAfterBoundedNumberOfRequests() = runTest {
        MockWebServer().use { server ->
            val dataset = datasetFixture(days = 30, rowsPerDay = 64)
            val budget = 20 * 1_024
            val client = testClient(server, requestByteBudget = budget)
            val partition = client.buildPartitionPlanForTest(dataset)
            val boundedReductionRequests =
                reductionRequestsBeforeNoProgress(client, partition, budget)
            server.dispatcher = structuredSuccessDispatcher()

            val failure = runCatching {
                client.analyze(payload(dataset), { SECRET })
            }.exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(ClinicalOpenAiException.RequestTooLarge::class.java)
            assertThat(server.requestCount)
                .isEqualTo(partition.leaves.size + boundedReductionRequests)
            assertSanitized(failure)
        }
    }

    @Test
    fun overBudgetUsesDeterministicDailyChunksWithExactRowAccountingAndSynthesis() = runTest {
        MockWebServer().use { server ->
            val dataset = datasetFixture(days = 4, rowsPerDay = 300)
            val budget = requestBytesFor(dataset) - 1
            val planner = testClient(server, requestByteBudget = budget)
            val plan = planner.buildUploadPlan(dataset)
            server.dispatcher = structuredSuccessDispatcher()

            val result = planner.analyze(payload(dataset), { SECRET })

            assertThat(plan.requiresSynthesis).isTrue()
            assertThat(plan.chunks.map { it.metadata.index })
                .containsExactlyElementsIn(plan.chunks.indices).inOrder()
            assertThat(plan.chunks.map { it.metadata.count }.distinct())
                .containsExactly(plan.chunks.size)
            assertThat(plan.chunks.map { it.metadata.day }).containsNoDuplicates()
            assertExactRowAccounting(dataset, plan)
            plan.chunks.forEach { chunk ->
                assertThat(chunk.metadata.hash).isEqualTo(ClinicalReportDatasetBuilder.sha256(chunk.canonicalJson))
                assertThat(chunk.requestBytes).isAtMost(budget)
            }
            assertThat(server.requestCount).isGreaterThan(plan.chunks.size + 1)
            val requests = (0 until server.requestCount).map { server.takeRequest() }
            val requestJsons = requests.map {
                JsonParser.parseString(it.body.clone().readUtf8()).asJsonObject
            }
            requestJsons.filter { requestJson ->
                JsonParser.parseString(requestJson.get("input").asString)
                    .asJsonObject
                    .has("isfCrSourceCodebook")
            }.forEach(::assertSourceCodebook)
            requests.dropLast(1).forEach { request ->
                val requestJson = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                assertThat(
                    requestJson.getAsJsonObject("text").getAsJsonObject("format")
                        .get("name").asString
                ).isEqualTo(ClinicalOpenAiClient.CHUNK_SCHEMA_NAME)
                assertThat(ClinicalOpenAiClient.CHUNK_SCHEMA_NAME)
                    .isEqualTo("clinical_chunk_findings_v3")
                assertThat(requestJson.get("max_output_tokens").asInt)
                    .isEqualTo(ClinicalOpenAiClient.CHUNK_MAX_OUTPUT_TOKENS)
                assertThat(ClinicalOpenAiClient.CHUNK_MAX_OUTPUT_TOKENS).isEqualTo(25_000)
            }
            val finalInput = JsonParser.parseString(requests.last().body.readUtf8())
                .asJsonObject.get("input").asString
            assertThat(finalInput).contains("\"kind\":\"clinical_map_reduce_synthesis\"")
            assertThat(finalInput).contains("\"summary7d\"")
            assertThat(finalInput).contains("\"rootReport\"")
            assertThat(finalInput).contains("\"coverageLedger\"")
            assertThat(finalInput).doesNotContain("\"chunkReports\"")
            listOf("\"d24\"", "\"g7\"", "\"e7\"", "\"t7\"", "\"g30\"", "\"e30\"", "\"t30\"")
                .forEach { rawField -> assertThat(finalInput).doesNotContain(rawField) }
            assertThat(result.metadata.chunkCount).isEqualTo(plan.chunks.size)
            assertThat(result.metadata.usedSynthesis).isTrue()
            assertThat(result.metadata.reductionLevels).isGreaterThan(0)
            assertThat(result.metadata.coverageLedgerHash)
                .matches(java.util.regex.Pattern.compile("[0-9a-f]{64}"))
        }
    }

    @Test
    fun failedChunkStopsWithoutRetryOrSynthesis() = runTest {
        MockWebServer().use { server ->
            val dataset = datasetFixture(days = 3, rowsPerDay = 300)
            val budget = requestBytesFor(dataset) - 1
            val client = testClient(server, requestByteBudget = budget)
            val plan = client.buildUploadPlan(dataset)
            server.enqueue(chunkSuccessResponse())
            server.enqueue(MockResponse().setResponseCode(500).setBody("private failure"))
            repeat(plan.chunks.size) { server.enqueue(chunkSuccessResponse()) }

            val failure = runCatching {
                client.analyze(payload(dataset), { SECRET })
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(ClinicalOpenAiException.ChunkFailed::class.java)
            assertThat(server.requestCount).isEqualTo(2)
            assertSanitized(failure)
        }
    }

    @Test
    fun chunkHashesAndPlanAreStableAcrossInputPermutation() = runTest {
        val dataset = datasetFixture(days = 4, rowsPerDay = 6)
        val permuted = dataset.copy(
            detail24h = dataset.detail24h.copy(
                glucose = dataset.detail24h.glucose.reversed(),
                calibratedGlucose = dataset.detail24h.calibratedGlucose.reversed(),
                therapy = dataset.detail24h.therapy.reversed(),
                targets = dataset.detail24h.targets.reversed(),
                forecasts = dataset.detail24h.forecasts.reversed(),
                telemetry = dataset.detail24h.telemetry.reversed()
            ),
            glucose7d = dataset.glucose7d.reversed(),
            therapy7d = dataset.therapy7d.reversed(),
            targets7d = dataset.targets7d.reversed(),
            glucose30d = dataset.glucose30d.reversed(),
            therapy30d = dataset.therapy30d.reversed(),
            targets30d = dataset.targets30d.reversed()
        )
        MockWebServer().use { server ->
            val budget = requestBytesFor(dataset) - 1
            val client = testClient(server, requestByteBudget = budget)

            val first = client.buildUploadPlan(dataset)
            val second = client.buildUploadPlan(permuted)

            assertThat(second.chunks.map { it.metadata }).isEqualTo(first.chunks.map { it.metadata })
            assertThat(second.chunks.map { it.canonicalJson }).isEqualTo(first.chunks.map { it.canonicalJson })
        }
    }

    @Test
    fun chunkCurrentSnapshotIsRecomputedFromFilteredDetailRows() = runTest {
        val zone = ZoneId.of("UTC")
        val generatedAt = ZonedDateTime.of(2026, 7, 20, 12, 0, 0, 0, zone)
        val generatedAtTs = generatedAt.toInstant().toEpochMilli()
        val currentDayStart = ZonedDateTime.of(2026, 7, 20, 0, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val outOfWindowTs = currentDayStart - 5 * 60_000L
        val inWindowTs = generatedAtTs - 4 * 60_000L

        fun canonical(value: Double) = kotlin.math.round(value * 1_000.0) / 1_000.0

        fun expectedSnapshot(seed: Double, sourceCode: Double) = ClinicalCurrentSnapshot(
            rawGlucoseMmol = canonical(6.0 + seed),
            calibratedGlucoseMmol = canonical(6.2 + seed),
            glucoseSampleAgeMinutes = if (seed == 0.0) 5.0 else 4.0,
            prediction30mMmol = canonical(7.0 + seed),
            prediction30mAgeMinutes = if (seed == 0.0) 5.0 else 4.0,
            effectiveIobUnits = canonical(1.0 + seed),
            effectiveCobGrams = canonical(10.0 + seed),
            selectedIsfMmolPerUnit = canonical(2.0 + seed),
            selectedIsfSourceCode = sourceCode,
            selectedCrGramsPerUnit = canonical(8.0 + seed),
            selectedCrSourceCode = sourceCode,
            uamActiveFlag = sourceCode % 2.0,
            uamEquivalentCarbsGrams = canonical(12.0 + seed),
            uamConfidence = canonical(0.7 + seed),
            sensorQualityScore = canonical(0.8 + seed),
            sensorBlockedFlag = sourceCode % 2.0,
            sensorAgeHours = canonical(24.0 + seed),
            sensorLagMinutes = canonical(3.0 + seed),
            activityRatio = canonical(1.1 + seed),
            stepsCount = canonical(1_000.0 + seed * 1_000.0),
            activeTargetLowMmol = canonical(5.0 + seed),
            activeTargetHighMmol = canonical(6.0 + seed)
        )

        fun telemetryAt(ts: Long, seed: Double, sourceCode: Double) = listOf(
            ClinicalTelemetryPoint(ts, "iob_effective_units", 1.0 + seed, "OK"),
            ClinicalTelemetryPoint(ts, "cob_effective_grams", 10.0 + seed, "OK"),
            ClinicalTelemetryPoint(ts, "isf_runtime_selected_value", 2.0 + seed, "OK"),
            ClinicalTelemetryPoint(ts, "isf_runtime_source_resolved", sourceCode, "OK"),
            ClinicalTelemetryPoint(ts, "cr_runtime_selected_value", 8.0 + seed, "OK"),
            ClinicalTelemetryPoint(ts, "cr_runtime_source_resolved", sourceCode, "OK"),
            ClinicalTelemetryPoint(ts, "uam_runtime_control_flag", sourceCode % 2.0, "OK"),
            ClinicalTelemetryPoint(
                ts,
                "uam_runtime_equivalent_carbs_grams",
                12.0 + seed,
                "OK"
            ),
            ClinicalTelemetryPoint(ts, "uam_runtime_confidence", 0.7 + seed, "OK"),
            ClinicalTelemetryPoint(ts, "sensor_quality_score", 0.8 + seed, "OK"),
            ClinicalTelemetryPoint(ts, "sensor_quality_blocked", sourceCode % 2.0, "OK"),
            ClinicalTelemetryPoint(ts, "sensor_age_hours", 24.0 + seed, "OK"),
            ClinicalTelemetryPoint(ts, "sensor_lag_minutes", 3.0 + seed, "OK"),
            ClinicalTelemetryPoint(
                ts,
                "activity_ratio",
                1.1 + seed,
                "OK",
                ClinicalTelemetryOrigin.LOCAL_ACTIVITY
            ),
            ClinicalTelemetryPoint(
                ts,
                "steps_count",
                1_000.0 + seed * 1_000.0,
                "OK",
                ClinicalTelemetryOrigin.LOCAL_ACTIVITY
            )
        )

        val outExpected = expectedSnapshot(seed = 0.0, sourceCode = 2.0)
        val inExpected = expectedSnapshot(seed = 0.1, sourceCode = 3.0)
        val leakSentinel = ClinicalCurrentSnapshot(
            rawGlucoseMmol = 30.0,
            calibratedGlucoseMmol = 31.0,
            glucoseSampleAgeMinutes = 999.0,
            prediction30mMmol = 32.0,
            prediction30mAgeMinutes = 13.0,
            effectiveIobUnits = 20.0,
            effectiveCobGrams = 400.0,
            selectedIsfMmolPerUnit = 17.0,
            selectedIsfSourceCode = 0.0,
            selectedCrGramsPerUnit = 50.0,
            selectedCrSourceCode = 0.0,
            uamActiveFlag = 1.0,
            uamEquivalentCarbsGrams = 400.0,
            uamConfidence = 0.1,
            sensorQualityScore = 0.1,
            sensorBlockedFlag = 1.0,
            sensorAgeHours = 700.0,
            sensorLagMinutes = 59.0,
            activityRatio = 2.9,
            stepsCount = 149_000.0,
            activeTargetLowMmol = 14.0,
            activeTargetHighMmol = 15.0
        )
        val dataset = datasetFixture(
            days = 4,
            rowsPerDay = 24,
            zoneId = zone.id,
            generatedAt = generatedAt
        ).copy(
            currentSnapshot = leakSentinel,
            detail24h = ClinicalDetailWindow(
                fromTs = generatedAtTs - DAY_MS,
                throughTs = generatedAtTs,
                glucose = listOf(
                    ClinicalGlucosePoint(outOfWindowTs, outExpected.rawGlucoseMmol!!),
                    ClinicalGlucosePoint(inWindowTs, inExpected.rawGlucoseMmol!!)
                ),
                calibratedGlucose = listOf(
                    ClinicalGlucosePoint(
                        outOfWindowTs,
                        outExpected.calibratedGlucoseMmol!!
                    ),
                    ClinicalGlucosePoint(inWindowTs, inExpected.calibratedGlucoseMmol!!)
                ),
                therapy = emptyList(),
                targets = listOf(
                    ClinicalTargetPoint(
                        outOfWindowTs,
                        outExpected.activeTargetLowMmol,
                        outExpected.activeTargetHighMmol,
                        durationMs = 30 * 60_000L
                    ),
                    ClinicalTargetPoint(
                        inWindowTs,
                        inExpected.activeTargetLowMmol,
                        inExpected.activeTargetHighMmol,
                        durationMs = 30 * 60_000L
                    )
                ),
                forecasts = listOf(
                    ClinicalForecastPoint(
                        outOfWindowTs,
                        30,
                        outExpected.prediction30mMmol!!,
                        5.0,
                        9.0
                    ),
                    ClinicalForecastPoint(
                        inWindowTs,
                        30,
                        inExpected.prediction30mMmol!!,
                        5.0,
                        9.0
                    )
                ),
                telemetry = telemetryAt(outOfWindowTs, 0.0, 2.0) +
                    telemetryAt(inWindowTs, 0.1, 3.0)
            )
        )
        MockWebServer().use { server ->
            val budget = requestBytesFor(dataset) - 1
            val plan = testClient(server, requestByteBudget = budget).buildUploadPlan(dataset)

            assertThat(plan.chunks).isNotEmpty()
            plan.chunks.forEach { chunk ->
                val expected = when {
                    chunk.dataset.detail24h.glucose.any { it.ts == outOfWindowTs } -> outExpected
                    chunk.dataset.detail24h.glucose.any { it.ts == inWindowTs } -> inExpected
                    else -> ClinicalCurrentSnapshot()
                }
                assertThat(chunk.dataset.currentSnapshot).isEqualTo(expected)
                assertThat(chunk.dataset.currentSnapshot).isNotEqualTo(leakSentinel)
            }
            assertThat(plan.chunks.count { it.dataset.currentSnapshot == outExpected }).isEqualTo(1)
            assertThat(plan.chunks.count { it.dataset.currentSnapshot == inExpected }).isEqualTo(1)
            assertThat(plan.chunks.any {
                it.dataset.currentSnapshot == ClinicalCurrentSnapshot()
            }).isTrue()
        }
    }

    @Test
    fun wireAccountingUsesCanonicalRowsAndRejectsOmissionOrDuplication() = runTest {
        val source = datasetFixture(days = 4, rowsPerDay = 6)
        val duplicatedAndInvalid = source.copy(
            glucose30d = source.glucose30d +
                ClinicalGlucosePoint(source.glucose30d.first().ts, 9.9) +
                ClinicalGlucosePoint(source.glucose30d.last().ts + 1L, Double.NaN)
        )
        MockWebServer().use { server ->
            val compact = ClinicalReportDatasetBuilder.serialize(duplicatedAndInvalid)
            val budget = requestBytesFor(duplicatedAndInvalid) - 1
            val plan = testClient(server, requestByteBudget = budget)
                .buildUploadPlan(duplicatedAndInvalid)

            assertThat(duplicatedAndInvalid.glucose30d.size)
                .isGreaterThan(ClinicalWireAccounting.rowCounts(compact).getValue("g30"))
            ClinicalWireAccounting.verify(
                fullDatasetJson = compact,
                chunkCanonicalJsons = plan.chunks.map {
                    """{"dataset":${it.canonicalJson}}"""
                }
            )

            val chunkWithRows = plan.chunks.indexOfFirst { chunk ->
                JsonParser.parseString(chunk.canonicalJson).asJsonObject
                    .getAsJsonArray("g30").size() > 0
            }
            assertThat(chunkWithRows).isAtLeast(0)
            val omitted = plan.chunks.map { it.canonicalJson }.toMutableList()
            omitted[chunkWithRows] = JsonParser.parseString(omitted[chunkWithRows])
                .asJsonObject.apply {
                    getAsJsonArray("g30").remove(0)
                }.toString()
            assertThrows(ClinicalOpenAiException.InvalidInput::class.java) {
                ClinicalWireAccounting.verify(
                    compact,
                    omitted.map { """{"dataset":$it}""" }
                )
            }

            val duplicated = plan.chunks.map { it.canonicalJson }.toMutableList()
            duplicated[chunkWithRows] = JsonParser.parseString(duplicated[chunkWithRows])
                .asJsonObject.apply {
                    val rows = getAsJsonArray("g30")
                    rows.add(rows[0].deepCopy())
                }.toString()
            assertThrows(ClinicalOpenAiException.InvalidInput::class.java) {
                ClinicalWireAccounting.verify(
                    compact,
                    duplicated.map { """{"dataset":$it}""" }
                )
            }
        }
    }

    @Test
    fun wireAccountingAcceptsOptionalTwentyFourHourSummary() {
        val source = datasetFixture(days = 4, rowsPerDay = 2)
        val dataset = source.copy(
            summary24h = source.summary7d.copy(
                days = 1,
                fromTs = source.summary7d.throughTs - DAY_MS
            )
        )
        val compact = ClinicalReportDatasetBuilder.serialize(dataset)

        ClinicalWireAccounting.verify(
            fullDatasetJson = compact,
            chunkCanonicalJsons = listOf("""{"dataset":$compact}""")
        )
    }

    @Test
    fun boundedChunkSchemaAllowsZeroOrOneClosedFindingWithoutInventingEvidence() {
        val one = chunkReportObject()
        val empty = chunkReportObject().apply {
            add("dataQuality", com.google.gson.JsonArray())
            add("findings", com.google.gson.JsonArray())
        }

        val oneParsed = ClinicalChunkReportSchemaParser.parse(one.toString())
        val emptyParsed = ClinicalChunkReportSchemaParser.parse(empty.toString())

        assertThat(oneParsed.dataQuality).containsExactly(ClinicalDataQualityFlag.COMPLETE)
        assertThat(oneParsed.findings.single().topic)
            .isEqualTo(ClinicalPatternTopic.GLUCOSE_STABILITY)
        assertThat(emptyParsed.dataQuality).isEmpty()
        assertThat(emptyParsed.findings).isEmpty()
        assertThat(one.toString().toByteArray(Charsets.UTF_8).size)
            .isAtMost(ClinicalOpenAiClient.MAX_CHUNK_REPORT_BYTES)
        assertAdditionalPropertiesFalseRecursively(ClinicalOpenAiClient.strictChunkSchemaJson())
        assertNoFreeFormStringSchemas(ClinicalOpenAiClient.strictChunkSchemaJson())
        assertThat(
            ClinicalOpenAiClient.strictChunkSchemaJson()
                .getAsJsonObject("properties").keySet()
        ).containsExactly("dataQuality", "findings")
    }

    @Test
    fun pathologicalCalendarSpanFailsBeforeCredentialRead() = runTest {
        MockWebServer().use { server ->
            val original = datasetFixture()
            val pathological = original.copy(
                summary30d = original.summary30d.copy(
                    fromTs = original.generatedAt - 400L * DAY_MS
                )
            )
            val credentialReads = AtomicInteger()

            val failure = runCatching {
                testClient(server, requestByteBudget = 1_024).analyze(
                    payload(pathological),
                    credentialProvider = {
                        credentialReads.incrementAndGet()
                        SECRET
                    }
                )
            }.exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(ClinicalOpenAiException.DatasetTooLarge::class.java)
            assertThat(credentialReads.get()).isEqualTo(0)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun analyzeRejectsPreparedSpanAboveCapBeforeCanonicalOrTransportWork() = runTest {
        MockWebServer().use { server ->
            val dataset = datasetWithPreparedSpan(
                datasetFixture(),
                ClinicalPlannedActivityPeriodPolicy.MAX_PREPARED_REPORT_SPAN_MS + 1L
            )
            val reportPayload = payload(dataset)
            assertThat(reportPayload.sha256)
                .isEqualTo(ClinicalReportDatasetBuilder.sha256(reportPayload.compactJson))
            val probe = EarliestPreflightProbe()
            val credentialReads = AtomicInteger()
            server.dispatcher = structuredSuccessDispatcher()

            val failure = runCatching {
                testClient(server, serializationProbe = probe).analyze(
                    reportPayload,
                    credentialProvider = {
                        credentialReads.incrementAndGet()
                        SECRET
                    }
                )
            }.exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(ClinicalOpenAiException.DatasetTooLarge::class.java)
            assertThat((failure as ClinicalOpenAiException).kind)
                .isEqualTo(ClinicalOpenAiFailureKind.DATASET_TOO_LARGE)
            assertSanitized(failure)
            probe.assertUntouched()
            assertThat(credentialReads.get()).isEqualTo(0)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun buildUploadPlanRejectsPreparedSpanAboveCapBeforeCanonicalOrTransportWork() = runTest {
        MockWebServer().use { server ->
            val dataset = datasetWithPreparedSpan(
                datasetFixture(),
                ClinicalPlannedActivityPeriodPolicy.MAX_PREPARED_REPORT_SPAN_MS + 1L
            )
            val reportPayload = payload(dataset)
            assertThat(reportPayload.sha256)
                .isEqualTo(ClinicalReportDatasetBuilder.sha256(reportPayload.compactJson))
            val probe = EarliestPreflightProbe()
            val client = testClient(server, serializationProbe = probe)

            val failure = runCatching {
                client.buildUploadPlan(reportPayload.dataset)
            }.exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(ClinicalOpenAiException.DatasetTooLarge::class.java)
            assertThat((failure as ClinicalOpenAiException).kind)
                .isEqualTo(ClinicalOpenAiFailureKind.DATASET_TOO_LARGE)
            assertSanitized(failure)
            probe.assertUntouched()
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun exactPreparedSpanCapRemainsValidOnSingleRequestPath() = runTest {
        MockWebServer().use { server ->
            val dataset = datasetWithPreparedSpan(
                datasetFixture(),
                ClinicalPlannedActivityPeriodPolicy.MAX_PREPARED_REPORT_SPAN_MS
            )
            val reportPayload = payload(dataset)
            val probe = EarliestPreflightProbe()
            val credentialReads = AtomicInteger()
            server.enqueue(successResponse())

            val result = testClient(server, serializationProbe = probe).analyze(
                reportPayload,
                credentialProvider = {
                    credentialReads.incrementAndGet()
                    SECRET
                }
            )

            assertThat(result.metadata.usedSynthesis).isFalse()
            assertThat(probe.canonicalPasses.get()).isGreaterThan(0)
            assertThat(probe.requestBodies.get()).isEqualTo(1)
            assertThat(credentialReads.get()).isEqualTo(1)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun absoluteDatasetCeilingsUseDistinctFailureBeforeCredentialRead() = runTest {
        MockWebServer().use { server ->
            val original = datasetFixture()
            val tooManyRows = original.copy(
                glucose30d = List(ClinicalOpenAiClient.MAX_TOTAL_SERIES_ROWS + 1) { index ->
                    ClinicalGlucosePoint(original.generatedAt - index * 60_000L, 6.0)
                }
            )
            val tooManyCalibratedRows = original.copy(
                detail24h = original.detail24h.copy(
                    calibratedGlucose =
                        List(ClinicalOpenAiClient.MAX_TOTAL_SERIES_ROWS + 1) { index ->
                            ClinicalGlucosePoint(original.generatedAt - index * 60_000L, 6.1)
                        }
                )
            )
            val oversizedCompact = original.copy(
                zoneId = "x".repeat(ClinicalOpenAiClient.MAX_COMPACT_DATASET_BYTES + 1)
            )

            listOf(tooManyRows, tooManyCalibratedRows, oversizedCompact).forEach { invalid ->
                val credentialReads = AtomicInteger()
                val failure = runCatching {
                    testClient(server).analyze(
                        payload(invalid),
                        credentialProvider = {
                            credentialReads.incrementAndGet()
                            SECRET
                        }
                    )
                }.exceptionOrNull()

                assertThat(failure)
                    .isInstanceOf(ClinicalOpenAiException.DatasetTooLarge::class.java)
                assertThat((failure as ClinicalOpenAiException).kind)
                    .isEqualTo(ClinicalOpenAiFailureKind.DATASET_TOO_LARGE)
                assertThat(credentialReads.get()).isEqualTo(0)
                assertThat(server.requestCount).isEqualTo(0)
            }
        }
    }

    @Test
    fun productionLikeThirtyDayDatasetCompletesMapReduceWithBoundedRequests() = runTest {
        MockWebServer().use { server ->
            val requestBodies = CopyOnWriteArrayList<String>()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(
                    request: okhttp3.mockwebserver.RecordedRequest
                ): MockResponse {
                    val body = request.body.clone().readUtf8()
                    requestBodies += body
                    val schemaName = JsonParser.parseString(body).asJsonObject
                        .getAsJsonObject("text")
                        .getAsJsonObject("format")
                        .get("name")
                        .asString
                    return if (schemaName == ClinicalOpenAiClient.CHUNK_SCHEMA_NAME) {
                        chunkSuccessResponse()
                    } else {
                        successResponse()
                    }
                }
            }
            val dataset = productionLikeThirtyDayDataset()
            val reportPayload = payload(dataset)
            val budget = 64 * 1_024
            val client = testClient(server, requestByteBudget = budget)

            val result = client.analyze(reportPayload, { SECRET })

            assertThat(dataset.glucose30d).hasSize(30 * 288)
            assertThat(dataset.glucose7d).hasSize(7 * 288)
            assertThat(dataset.detail24h.glucose).hasSize(288)
            assertThat(dataset.glucose7d.first().ts)
                .isEqualTo(dataset.generatedAt - 7 * DAY_MS)
            assertThat(dataset.detail24h.glucose.first().ts)
                .isEqualTo(dataset.generatedAt - DAY_MS)
            assertThat(
                listOf(
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
                    dataset.targets30d.size
                ).sum()
            ).isAtMost(ClinicalOpenAiClient.MAX_TOTAL_SERIES_ROWS)
            assertThat(requestBodies.size).isGreaterThan(result.metadata.chunkCount + 1)
            requestBodies.forEach { body ->
                assertThat(body.toByteArray(Charsets.UTF_8).size).isAtMost(budget)
                assertThat(JsonParser.parseString(body).asJsonObject.get("store").asBoolean)
                    .isFalse()
            }
            val finalInput = JsonParser.parseString(requestBodies.last()).asJsonObject
                .get("input").asString
            val finalJson = JsonParser.parseString(finalInput).asJsonObject
            val rootReport = finalJson.getAsJsonObject("rootReport")
            val rootHash = finalJson.get("rootDigestHash").asString
            val ledgerHash = finalJson.getAsJsonObject("coverageLedger")
                .get("ledgerHash").asString
            assertThat(rootReport.has("sourceHashes")).isFalse()
            assertThat(finalJson.get("rootReportHash").asString)
                .isEqualTo(ClinicalReportDatasetBuilder.sha256(rootReport.toString()))
            assertThat(rootHash).matches(java.util.regex.Pattern.compile("[0-9a-f]{64}"))
            assertThat(finalJson.get("ledgerRootBindingHash").asString).isEqualTo(
                expectedLedgerRootBindingHash(rootHash, ledgerHash, reportPayload.sha256)
            )
            listOf(
                "\"d24\"",
                "\"g7\"",
                "\"e7\"",
                "\"t7\"",
                "\"g30\"",
                "\"e30\"",
                "\"t30\""
            ).forEach { rawField -> assertThat(finalInput).doesNotContain(rawField) }
            assertThat(result.metadata.usedSynthesis).isTrue()
            assertThat(result.metadata.chunkCount).isGreaterThan(1)
            assertThat(result.metadata.reductionLevels).isGreaterThan(0)
            assertThat(result.metadata.requestHash).isEqualTo(reportPayload.sha256)
            assertThat(result.metadata.coverageLedgerHash).isEqualTo(ledgerHash)
        }
    }

    @Test
    fun directCurrentSnapshotRejectsFieldSpecificExtremeAndFractionalValues() = runTest {
        MockWebServer().use { server ->
            val valid = ClinicalCurrentSnapshot(
                rawGlucoseMmol = 1.0,
                calibratedGlucoseMmol = 40.0,
                glucoseSampleAgeMinutes = 1_440.0,
                prediction30mMmol = 1.0,
                prediction30mAgeMinutes = 15.0,
                effectiveIobUnits = -30.0,
                effectiveCobGrams = 500.0,
                selectedIsfMmolPerUnit = 0.2,
                selectedIsfSourceCode = 0.0,
                selectedCrGramsPerUnit = 60.0,
                selectedCrSourceCode = 3.0,
                uamActiveFlag = 1.0,
                uamEquivalentCarbsGrams = 500.0,
                uamConfidence = 1.0,
                sensorQualityScore = 0.0,
                sensorBlockedFlag = 0.0,
                sensorAgeHours = 720.0,
                sensorLagMinutes = 60.0,
                activityRatio = 0.2,
                stepsCount = 150_000.0,
                activeTargetLowMmol = 2.2,
                activeTargetHighMmol = 15.0
            )
            val invalid = listOf(
                valid.copy(prediction30mAgeMinutes = 15.001),
                valid.copy(prediction30mAgeMinutes = null),
                valid.copy(prediction30mMmol = null),
                valid.copy(rawGlucoseMmol = 40.001),
                valid.copy(calibratedGlucoseMmol = 0.999),
                valid.copy(glucoseSampleAgeMinutes = 1_440.001),
                valid.copy(prediction30mMmol = 40.001),
                valid.copy(effectiveIobUnits = -30.001),
                valid.copy(effectiveCobGrams = 500.001),
                valid.copy(selectedIsfMmolPerUnit = 0.199),
                valid.copy(selectedIsfSourceCode = 1.5),
                valid.copy(selectedCrGramsPerUnit = 60.001),
                valid.copy(selectedCrSourceCode = 2.5),
                valid.copy(uamActiveFlag = 0.5),
                valid.copy(uamEquivalentCarbsGrams = 500.001),
                valid.copy(uamConfidence = 1.001),
                valid.copy(sensorQualityScore = -0.001),
                valid.copy(sensorBlockedFlag = 0.5),
                valid.copy(sensorAgeHours = 720.001),
                valid.copy(sensorLagMinutes = 60.001),
                valid.copy(activityRatio = 0.199),
                valid.copy(stepsCount = 150_000.001),
                valid.copy(activeTargetLowMmol = 2.199),
                valid.copy(activeTargetHighMmol = 15.001)
            )
            val client = testClient(server)

            assertThat(runCatching {
                client.buildUploadPlan(datasetFixture().copy(currentSnapshot = valid))
            }.exceptionOrNull()).isNull()
            invalid.forEach { snapshot ->
                assertThat(runCatching {
                    client.buildUploadPlan(datasetFixture().copy(currentSnapshot = snapshot))
                }.exceptionOrNull()).isInstanceOf(
                    ClinicalOpenAiException.InvalidInput::class.java
                )
            }
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun directCurrentSnapshotRejectsReversedActiveTargetBeforeRequest() = runTest {
        MockWebServer().use { server ->
            server.enqueue(successResponse())
            val dataset = datasetFixture().copy(
                currentSnapshot = ClinicalCurrentSnapshot(
                    activeTargetLowMmol = 15.0,
                    activeTargetHighMmol = 2.2
                )
            )
            val credentialReads = AtomicInteger()

            val failure = runCatching {
                testClient(server).analyze(
                    payload(dataset),
                    credentialProvider = {
                        credentialReads.incrementAndGet()
                        SECRET
                    }
                )
            }.exceptionOrNull()

            assertThat(failure)
                .isInstanceOf(ClinicalOpenAiException.InvalidInput::class.java)
            assertThat(credentialReads.get()).isEqualTo(0)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun localDayChunksHandleDstAndExactMidnightWithoutDuplicateEndpoints() = runTest {
        val zone = ZoneId.of("America/New_York")
        val timestamps = listOf(
            ZonedDateTime.of(2026, 3, 7, 23, 30, 0, 0, zone),
            ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, zone),
            ZonedDateTime.of(2026, 3, 8, 1, 30, 0, 0, zone),
            ZonedDateTime.of(2026, 3, 8, 3, 30, 0, 0, zone),
            ZonedDateTime.of(2026, 3, 9, 0, 0, 0, 0, zone),
            ZonedDateTime.of(2026, 3, 10, 0, 0, 0, 0, zone)
        ).map { it.toInstant().toEpochMilli() }
        val dataset = datasetFixture(
            days = 3,
            rowsPerDay = 2,
            zoneId = zone.id,
            generatedAt = ZonedDateTime.of(2026, 3, 10, 0, 0, 0, 0, zone),
            explicitTimestamps = timestamps
        )
        MockWebServer().use { server ->
            val budget = requestBytesFor(dataset) - 1
            val plan = testClient(server, requestByteBudget = budget).buildUploadPlan(dataset)

            assertThat(plan.chunks.map { it.metadata.day }).containsExactly(
                "2026-03-07",
                "2026-03-08",
                "2026-03-09",
                "2026-03-10"
            ).inOrder()
            val springForward = plan.chunks.single { it.metadata.day == "2026-03-08" }
            assertThat(springForward.metadata.windowThroughTs - springForward.metadata.windowFromTs)
                .isEqualTo(23L * 60L * 60L * 1_000L)
            assertExactRowAccounting(dataset, plan)
            val allGlucose = plan.chunks.flatMap { it.dataset.glucose30d }
            assertThat(allGlucose.map { it.ts }).containsExactlyElementsIn(timestamps)
            assertThat(allGlucose.map { it.ts }).containsNoDuplicates()
            assertThat(
                plan.chunks.single { it.metadata.day == "2026-03-10" }
                    .dataset.glucose30d.single().ts
            ).isEqualTo(timestamps.last())
        }
    }

    @Test
    fun ordinaryProgressObserverFailuresNeverChangeSuccessfulResult() = runTest {
        MockWebServer().use { server ->
            val dataset = datasetFixture(days = 4, rowsPerDay = 300)
            val budget = requestBytesFor(dataset) - 1
            val client = testClient(server, requestByteBudget = budget)
            val plan = client.buildUploadPlan(dataset)
            server.dispatcher = structuredSuccessDispatcher()
            val observed = mutableListOf<ClinicalOpenAiProgressStage>()

            val result = client.analyze(
                payload(dataset),
                credentialProvider = { SECRET },
                progress = ClinicalOpenAiProgressCallback {
                    observed += it.stage
                    throw IllegalStateException("observer-private-error")
                }
            )

            assertThat(result.report.summary7dStatus).isEqualTo(ClinicalSummaryStatus.STABLE)
            assertThat(observed).containsAtLeast(
                ClinicalOpenAiProgressStage.PREPARING,
                ClinicalOpenAiProgressStage.ANALYZING_CHUNK,
                ClinicalOpenAiProgressStage.SYNTHESIZING,
                ClinicalOpenAiProgressStage.COMPLETED
            )
            assertThat(observed.last()).isEqualTo(ClinicalOpenAiProgressStage.COMPLETED)
            assertThat(server.requestCount).isGreaterThan(plan.chunks.size + 1)
        }
    }

    @Test
    fun progressObserverCancellationAndFatalLinkageStillPropagate() = runTest {
        MockWebServer().use { server ->
            val cancellation = runCatching {
                testClient(server).analyze(
                    payload(datasetFixture()),
                    { SECRET },
                    ClinicalOpenAiProgressCallback { throw CancellationException("cancel") }
                )
            }.exceptionOrNull()
            val linkage = runCatching {
                testClient(server).analyze(
                    payload(datasetFixture()),
                    { SECRET },
                    ClinicalOpenAiProgressCallback { throw LinkageError("fatal") }
                )
            }.exceptionOrNull()

            assertThat(cancellation).isInstanceOf(CancellationException::class.java)
            assertThat(linkage).isInstanceOf(LinkageError::class.java)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun credentialIsResolvedForEveryRequestAndCanRotate() = runTest {
        MockWebServer().use { server ->
            val dataset = datasetFixture(days = 4, rowsPerDay = 300)
            val budget = requestBytesFor(dataset) - 1
            val client = testClient(server, requestByteBudget = budget)
            val plan = client.buildUploadPlan(dataset)
            server.dispatcher = structuredSuccessDispatcher()
            val credentialReads = AtomicInteger()

            client.analyze(payload(dataset), credentialProvider = {
                "rotated-credential-${credentialReads.incrementAndGet()}"
            })

            val requests = (0 until server.requestCount).map { server.takeRequest() }
            assertThat(credentialReads.get()).isEqualTo(server.requestCount)
            assertThat(requests.map { it.getHeader("Authorization") }).containsExactlyElementsIn(
                (1..server.requestCount).map { "Bearer rotated-credential-$it" }
            ).inOrder()
            assertThat(server.requestCount).isGreaterThan(plan.chunks.size + 1)
        }
    }

    @Test
    fun cancellationFromCredentialReaderBetweenChunksStopsBeforeNextRequest() = runTest {
        MockWebServer().use { server ->
            val dataset = datasetFixture(days = 3, rowsPerDay = 300)
            val budget = requestBytesFor(dataset) - 1
            val client = testClient(server, requestByteBudget = budget)
            server.enqueue(chunkSuccessResponse())
            val credentialReads = AtomicInteger()

            val failure = runCatching {
                client.analyze(payload(dataset), credentialProvider = {
                    if (credentialReads.incrementAndGet() == 1) SECRET
                    else throw CancellationException("credential removed")
                })
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(CancellationException::class.java)
            assertThat(credentialReads.get()).isEqualTo(2)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun testEndpointMustBeExactLoopbackResponsesPath() {
        assertThrows(IllegalArgumentException::class.java) {
            ClinicalOpenAiClient.forTest(endpoint = "https://example.com/v1/responses")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClinicalOpenAiClient.forTest(endpoint = "http://127.0.0.1/v1/chat/completions")
        }
    }

    private fun expectedReductionBodies(
        client: ClinicalOpenAiClient,
        partition: ClinicalPartitionPlan,
        budget: Int
    ): List<String> {
        val report = ClinicalChunkReportSchemaParser.parse(chunkReportObject().toString())
        var digests = partition.leaves.map { leaf ->
            ClinicalChunkDigest.create(
                id = leaf.descriptor.id,
                fromTs = leaf.descriptor.fromTs,
                throughTsExclusive = leaf.descriptor.throughTsExclusive,
                sourceHashes = listOf(leaf.descriptor.canonicalHash),
                dataQuality = report.dataQuality,
                findings = report.findings
            )
        }
        val planner = ClinicalReportReductionPlanner(
            requestBudgetBytes = budget,
            wireTemplate = client.reductionWireTemplate()
        )
        val bodies = mutableListOf<String>()
        while (digests.size > 1) {
            val level = planner.nextLevel(digests)
            bodies += level.groups.map { it.requestBodyBytes.utf8() }
            digests = level.groups.map { group ->
                ClinicalChunkDigest.create(
                    id = group.id,
                    fromTs = group.fromTs,
                    throughTsExclusive = group.throughTsExclusive,
                    sourceHashes = group.sources.flatMap { it.sourceHashes },
                    dataQuality = report.dataQuality,
                    findings = report.findings
                )
            }
        }
        return bodies
    }

    private fun firstReductionLevelCount(
        client: ClinicalOpenAiClient,
        partition: ClinicalPartitionPlan,
        budget: Int
    ): Int {
        val report = ClinicalChunkReportSchemaParser.parse(chunkReportObject().toString())
        val digests = partition.leaves.map { leaf ->
            ClinicalChunkDigest.create(
                id = leaf.descriptor.id,
                fromTs = leaf.descriptor.fromTs,
                throughTsExclusive = leaf.descriptor.throughTsExclusive,
                sourceHashes = listOf(leaf.descriptor.canonicalHash),
                dataQuality = report.dataQuality,
                findings = report.findings
            )
        }
        return ClinicalReportReductionPlanner(
            requestBudgetBytes = budget,
            wireTemplate = client.reductionWireTemplate()
        ).nextLevel(digests).groups.size
    }

    private fun reductionRequestsBeforeNoProgress(
        client: ClinicalOpenAiClient,
        partition: ClinicalPartitionPlan,
        budget: Int
    ): Int {
        val report = ClinicalChunkReportSchemaParser.parse(chunkReportObject().toString())
        var digests = partition.leaves.map { leaf ->
            ClinicalChunkDigest.create(
                id = leaf.descriptor.id,
                fromTs = leaf.descriptor.fromTs,
                throughTsExclusive = leaf.descriptor.throughTsExclusive,
                sourceHashes = listOf(leaf.descriptor.canonicalHash),
                dataQuality = report.dataQuality,
                findings = report.findings
            )
        }
        val planner = ClinicalReportReductionPlanner(
            requestBudgetBytes = budget,
            wireTemplate = client.reductionWireTemplate()
        )
        var requestCount = 0
        while (digests.size > 1) {
            val level = try {
                planner.nextLevel(digests)
            } catch (_: ClinicalReductionException) {
                return requestCount
            }
            requestCount += level.groups.size
            digests = level.groups.map { group ->
                ClinicalChunkDigest.create(
                    id = group.id,
                    fromTs = group.fromTs,
                    throughTsExclusive = group.throughTsExclusive,
                    sourceHashes = group.sources.flatMap { it.sourceHashes },
                    dataQuality = report.dataQuality,
                    findings = report.findings
                )
            }
        }
        throw AssertionError("Expected reduction planning to stop without progress")
    }

    private fun testClient(
        server: MockWebServer,
        model: String = ClinicalOpenAiClient.DEFAULT_MODEL,
        requestByteBudget: Int = 256 * 1_024,
        maxResponseBytes: Int = 64 * 1_024,
        timeouts: ClinicalOpenAiTimeouts = ClinicalOpenAiTimeouts(),
        monotonicClockNanos: () -> Long = System::nanoTime,
        serializationProbe: ClinicalDatasetSerializationProbe? = null,
        partitionLeafLimit: Int = ClinicalReportPartitionPlanner.MAX_PARTITION_LEAVES,
        partitionRequestLimit: Int = ClinicalOpenAiClient.MAX_PARTITION_REMOTE_REQUESTS
    ): ClinicalOpenAiClient = ClinicalOpenAiClient.forTest(
        endpoint = server.url("/v1/responses").toString(),
        model = model,
        requestByteBudget = requestByteBudget,
        maxResponseBytes = maxResponseBytes,
        timeouts = timeouts,
        monotonicClockNanos = monotonicClockNanos,
        serializationProbe = serializationProbe,
        partitionLeafLimit = partitionLeafLimit,
        partitionRequestLimit = partitionRequestLimit
    )

    private fun requestBytesFor(dataset: ClinicalReportDataset): Int =
        ClinicalOpenAiClient.requestBytesForTest(
            dataset = dataset,
            model = ClinicalOpenAiClient.DEFAULT_MODEL
        )

    private fun payload(dataset: ClinicalReportDataset): ClinicalReportPayload {
        val compact = ClinicalReportDatasetBuilder.serialize(dataset)
        return ClinicalReportPayload(dataset, compact, ClinicalReportDatasetBuilder.sha256(compact))
    }

    private fun datasetWithPreparedSpan(
        dataset: ClinicalReportDataset,
        spanMs: Long
    ): ClinicalReportDataset = dataset.copy(
        summary30d = dataset.summary30d.copy(
            fromTs = Math.subtractExact(dataset.generatedAt, spanMs),
            throughTs = dataset.generatedAt
        )
    )

    private suspend fun ClinicalOpenAiClient.buildPartitionPlanForTest(
        dataset: ClinicalReportDataset
    ): ClinicalPartitionPlan {
        val compact = ClinicalReportDatasetBuilder.serializeCancellable(dataset)
        return buildPartitionPlan(dataset, compact)
    }

    private fun expectedLedgerRootBindingHash(
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

    private fun productionLikeThirtyDayDataset(): ClinicalReportDataset {
        val generatedAt = ZonedDateTime.of(
            2026,
            7,
            28,
            9,
            0,
            0,
            0,
            ZoneId.of("UTC")
        ).toInstant().toEpochMilli()
        val start30d = generatedAt - 30 * DAY_MS
        val glucose30d = (0 until 30 * 288).map { index ->
            ClinicalGlucosePoint(
                ts = start30d + index * 5 * 60_000L,
                mmol = 6.0 + kotlin.math.sin(index / 24.0) * 0.8
            )
        }
        val glucose7d = glucose30d.filter { it.ts >= generatedAt - 7 * DAY_MS }
        val detailGlucose = glucose30d.filter { it.ts >= generatedAt - DAY_MS }
        val therapy30d = (0 until 30 * 24).map { index ->
            ClinicalTherapyPoint(
                ts = start30d + index * 60 * 60_000L,
                insulinU = if (index % 6 == 0) 1.2 else 0.05,
                carbsG = if (index % 6 == 0) 18.0 else null
            )
        }
        val therapy7d = therapy30d.filter { it.ts >= generatedAt - 7 * DAY_MS }
        val detailTherapy = therapy30d.filter { it.ts >= generatedAt - DAY_MS }
        val targets30d = (0 until 30 * 4).map { index ->
            ClinicalTargetPoint(
                ts = start30d + index * 6 * 60 * 60_000L,
                lowMmol = 5.6,
                highMmol = 6.4,
                durationMs = 6 * 60 * 60_000L
            )
        }
        val targets7d = targets30d.filter { it.ts >= generatedAt - 7 * DAY_MS }
        val detailTargets = targets30d.filter { it.ts >= generatedAt - DAY_MS }
        val hourly = (0..23).map { hour ->
            ClinicalHourlyMetric(hour, 84, 6.0, 6.0)
        }

        fun summary(days: Int) = ClinicalPeriodSummary(
            days = days,
            fromTs = generatedAt - days * DAY_MS,
            throughTs = generatedAt,
            coveragePct = 100.0,
            meanMmol = 6.0,
            medianMmol = 6.0,
            coefficientOfVariationPct = 13.3,
            timeBelow4Pct = 0.5,
            timeInRangePct = 94.0,
            timeAboveRangePct = 5.5,
            totalInsulinU = days * 36.0,
            totalCarbsG = days * 72.0,
            meanTargetMmol = 6.0,
            weekdayPattern = hourly,
            weekendPattern = hourly,
            quality = ClinicalDataQuality(
                expectedBuckets = days * 288,
                coveredBuckets = days * 288,
                missingBuckets = 0,
                maxGapMinutes = 5
            )
        )

        return ClinicalReportDataset(
            schemaVersion = ClinicalReportDatasetBuilder.SCHEMA_VERSION,
            generatedAt = generatedAt,
            zoneId = "UTC",
            detail24h = ClinicalDetailWindow(
                fromTs = generatedAt - DAY_MS,
                throughTs = generatedAt,
                glucose = detailGlucose,
                calibratedGlucose = detailGlucose.map { it.copy(mmol = it.mmol + 0.05) },
                therapy = detailTherapy,
                targets = detailTargets,
                forecasts = detailGlucose.map {
                    ClinicalForecastPoint(
                        it.ts,
                        30,
                        it.mmol + 0.1,
                        it.mmol - 0.4,
                        it.mmol + 0.6
                    )
                },
                telemetry = detailGlucose.map {
                    ClinicalTelemetryPoint(it.ts, "iob_units", 1.2, "OK")
                }
            ),
            glucose7d = glucose7d,
            therapy7d = therapy7d,
            targets7d = targets7d,
            glucose30d = glucose30d,
            therapy30d = therapy30d,
            targets30d = targets30d,
            summary7d = summary(7),
            summary30d = summary(30)
        )
    }

    private fun datasetFixture(
        days: Int = 2,
        rowsPerDay: Int = 3,
        zoneId: String = "UTC",
        generatedAt: ZonedDateTime? = null,
        explicitTimestamps: List<Long>? = null
    ): ClinicalReportDataset {
        val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.of("UTC"))
        val generatedAtTs = (generatedAt ?: ZonedDateTime.of(2026, 7, 20, 12, 0, 0, 0, zone))
            .toInstant().toEpochMilli()
        val start = explicitTimestamps?.minOrNull() ?: generatedAtTs - days * DAY_MS
        val rowStep = DAY_MS / rowsPerDay
        val glucose = (explicitTimestamps ?: (0 until days * rowsPerDay).map { index ->
            start + index * rowStep
        }).mapIndexed { index, timestamp ->
            ClinicalGlucosePoint(timestamp, 5.5 + (index % 5) * 0.1)
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
        val detailRows = glucose.filter { it.ts >= generatedAtTs - DAY_MS }
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
            throughTs = generatedAtTs,
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
            generatedAt = generatedAtTs,
            zoneId = zone.id,
            detail24h = ClinicalDetailWindow(
                fromTs = generatedAtTs - DAY_MS,
                throughTs = generatedAtTs,
                glucose = detailRows,
                calibratedGlucose = detailRows.map { it.copy(mmol = it.mmol + 0.1) },
                therapy = therapy.filter { it.ts >= generatedAtTs - DAY_MS },
                targets = targets.filter { it.ts >= generatedAtTs - DAY_MS },
                forecasts = detailRows.map {
                    ClinicalForecastPoint(it.ts, 30, it.mmol + 0.2, it.mmol - 0.3, it.mmol + 0.7)
                },
                telemetry = detailRows.map {
                    ClinicalTelemetryPoint(it.ts, "iob_units", 1.0, "OK")
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

    private fun validReportJson(): String = reportObject().toString()

    private fun reportWithEvidence(
        metric: ClinicalEvidenceMetric,
        value: com.google.gson.JsonElement
    ): JsonObject = reportObject().apply {
        getAsJsonArray("patterns")[0].asJsonObject.apply {
            addProperty("evidenceMetric", metric.name)
            add("evidenceValue", value)
        }
    }

    private fun reportObject(): JsonObject = JsonParser.parseString(
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
    ).asJsonObject

    private fun successResponse(
        model: String = ClinicalOpenAiClient.DEFAULT_MODEL,
        fingerprint: String? = FINGERPRINT,
        output: String = validReportJson()
    ): MockResponse = MockResponse().setBody(successResponseBody(model, fingerprint, output))

    private fun connectionSuccessResponse(
        model: String,
        status: String = "completed"
    ): MockResponse =
        MockResponse().setBody(
            JsonObject().apply {
                addProperty("status", status)
                addProperty("model", model)
                addProperty("output_text", """{"ok":true}""")
            }.toString()
        )

    private fun successResponseBody(
        model: String = ClinicalOpenAiClient.DEFAULT_MODEL,
        fingerprint: String? = FINGERPRINT,
        output: String = validReportJson()
    ): String {
        val root = JsonObject().apply {
            addProperty("status", "completed")
            addProperty("model", model)
            if (fingerprint != null) addProperty("system_fingerprint", fingerprint)
            addProperty("output_text", output)
        }
        return root.toString()
    }

    private fun chunkReportObject(): JsonObject = JsonParser.parseString(
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
    ).asJsonObject

    private fun chunkSuccessResponse(
        report: JsonObject = chunkReportObject(),
        model: String = ClinicalOpenAiClient.DEFAULT_MODEL
    ): MockResponse = MockResponse().setBody(chunkSuccessResponseBody(report, model))

    private fun chunkSuccessResponseBody(
        report: JsonObject = chunkReportObject(),
        model: String = ClinicalOpenAiClient.DEFAULT_MODEL
    ): String =
        JsonObject().apply {
            addProperty("status", "completed")
            addProperty("model", model)
            addProperty("system_fingerprint", FINGERPRINT)
            addProperty("output_text", report.toString())
        }.toString()

    private fun structuredSuccessDispatcher(): okhttp3.mockwebserver.Dispatcher =
        object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(
                request: okhttp3.mockwebserver.RecordedRequest
            ): MockResponse {
                val schemaName = JsonParser.parseString(request.body.clone().readUtf8())
                    .asJsonObject
                    .getAsJsonObject("text")
                    .getAsJsonObject("format")
                    .get("name")
                    .asString
                return if (schemaName == ClinicalOpenAiClient.CHUNK_SCHEMA_NAME) {
                    chunkSuccessResponse()
                } else {
                    successResponse()
                }
            }
        }

    private fun jsonString(value: String): String =
        com.google.gson.Gson().toJson(value)

    private fun assertAdditionalPropertiesFalseRecursively(node: com.google.gson.JsonElement) {
        if (node.isJsonObject) {
            val objectNode = node.asJsonObject
            if (objectNode.get("type")?.asString == "object") {
                assertThat(objectNode.get("additionalProperties")?.asBoolean).isFalse()
            }
            objectNode.entrySet().forEach { (_, child) ->
                assertAdditionalPropertiesFalseRecursively(child)
            }
        } else if (node.isJsonArray) {
            node.asJsonArray.forEach(::assertAdditionalPropertiesFalseRecursively)
        }
    }

    private fun assertNoFreeFormStringSchemas(node: com.google.gson.JsonElement) {
        if (node.isJsonObject) {
            val objectNode = node.asJsonObject
            if (objectNode.get("type")?.takeIf { it.isJsonPrimitive }?.asString == "string") {
                assertThat(objectNode.has("enum")).isTrue()
                assertThat(objectNode.getAsJsonArray("enum")).isNotEmpty()
            }
            objectNode.entrySet().forEach { (_, child) ->
                assertNoFreeFormStringSchemas(child)
            }
        } else if (node.isJsonArray) {
            node.asJsonArray.forEach(::assertNoFreeFormStringSchemas)
        }
    }

    private fun assertEvidenceSchema(
        schemas: Map<ClinicalEvidenceMetric, JsonObject>,
        metrics: Set<ClinicalEvidenceMetric>,
        expectedType: String,
        expectedMinimum: Double,
        expectedMaximum: Double
    ) {
        metrics.forEach { metric ->
            val schema = schemas.getValue(metric)
            assertThat(schema.get("type").asString).isEqualTo(expectedType)
            assertThat(schema.get("minimum").asDouble).isEqualTo(expectedMinimum)
            assertThat(schema.get("maximum").asDouble).isEqualTo(expectedMaximum)
        }
    }

    private fun assertExclusiveEnumSchema(schema: JsonObject, exclusiveValue: String) {
        val variants = schema.getAsJsonArray("anyOf")
        assertThat(variants).hasSize(2)
        val exclusive = variants
            .map(JsonElement::getAsJsonObject)
            .single { variant ->
                variant.getAsJsonObject("items").getAsJsonArray("enum")
                    .any { it.asString == exclusiveValue }
            }
        assertThat(exclusive.get("maxItems").asInt).isEqualTo(1)
        assertThat(exclusive.getAsJsonObject("items").getAsJsonArray("enum"))
            .containsExactly(JsonPrimitive(exclusiveValue))
        val nonExclusive = variants
            .map(JsonElement::getAsJsonObject)
            .single { it !== exclusive }
        assertThat(
            nonExclusive.getAsJsonObject("items").getAsJsonArray("enum")
                .map(JsonElement::getAsString)
        ).doesNotContain(exclusiveValue)
    }

    private fun assertExactRowAccounting(
        source: ClinicalReportDataset,
        plan: ClinicalUploadPlan
    ) {
        fun <T> combined(select: (ClinicalReportDataset) -> List<T>) =
            plan.chunks.flatMap { select(it.dataset) }

        assertThat(combined { it.detail24h.glucose }).containsExactlyElementsIn(source.detail24h.glucose)
        assertThat(combined { it.detail24h.calibratedGlucose })
            .containsExactlyElementsIn(source.detail24h.calibratedGlucose)
        assertThat(combined { it.detail24h.therapy }).containsExactlyElementsIn(source.detail24h.therapy)
        assertThat(combined { it.detail24h.targets }).containsExactlyElementsIn(source.detail24h.targets)
        assertThat(combined { it.detail24h.forecasts }).containsExactlyElementsIn(source.detail24h.forecasts)
        assertThat(combined { it.detail24h.telemetry }).containsExactlyElementsIn(source.detail24h.telemetry)
        assertThat(combined { it.glucose7d }).containsExactlyElementsIn(source.glucose7d)
        assertThat(combined { it.therapy7d }).containsExactlyElementsIn(source.therapy7d)
        assertThat(combined { it.targets7d }).containsExactlyElementsIn(source.targets7d)
        assertThat(combined { it.glucose30d }).containsExactlyElementsIn(source.glucose30d)
        assertThat(combined { it.therapy30d }).containsExactlyElementsIn(source.therapy30d)
        assertThat(combined { it.targets30d }).containsExactlyElementsIn(source.targets30d)
    }

    private fun assertSanitized(failure: Throwable?, medicalMarker: String = "private-medical-payload") {
        val rendered = generateSequence(failure) { it.cause }
            .joinToString("\n") { "${it::class.java.name}:${it.message}:${it.stackTraceToString()}" }
        listOf(
            SECRET,
            "private-server-message",
            "private-response",
            "private-header",
            medicalMarker
        ).forEach { secret ->
            assertThat(rendered).doesNotContain(secret)
        }
    }

    private fun assertSourceCodebook(requestJson: JsonObject) {
        assertThat(requestJson.get("instructions").asString).contains(
            "0=NO_OVERRIDE, 1=AAPS, 2=EVIDENCE, 3=COPILOT_NATIVE"
        )
        assertThat(requestJson.get("instructions").asString).contains(
            "p30 is the +30m horizon from a forecast generated p30Age minutes ago"
        )
        val input = JsonParser.parseString(requestJson.get("input").asString).asJsonObject
        val codebook = input.getAsJsonObject("isfCrSourceCodebook")
        assertThat(codebook.entrySet().associate { (code, meaning) ->
            code to meaning.asString
        }).containsExactly(
            "0", "NO_OVERRIDE",
            "1", "AAPS",
            "2", "EVIDENCE",
            "3", "COPILOT_NATIVE"
        )
    }

    private class EarliestPreflightProbe : ClinicalDatasetSerializationProbe {
        val canonicalPasses = AtomicInteger()
        val transportPasses = AtomicInteger()
        val requestBodies = AtomicInteger()

        override fun onCanonicalEventWindowsComputed() {
            canonicalPasses.incrementAndGet()
        }

        override fun onSeriesRowEmitted() {
            canonicalPasses.incrementAndGet()
        }

        override fun onFullDatasetAssemblyStarted() {
            canonicalPasses.incrementAndGet()
        }

        override fun onTransportInputChunkProcessed() {
            transportPasses.incrementAndGet()
        }

        override fun onClinicalInputMaterialized() {
            transportPasses.incrementAndGet()
        }

        override fun onClinicalRequestBodyMaterialized() {
            requestBodies.incrementAndGet()
        }

        override fun onPartitionLeafRetained(retainedLargeArtifacts: Int) {
            transportPasses.incrementAndGet()
        }

        override fun onWireCoverageRowRead() {
            transportPasses.incrementAndGet()
        }

        fun assertUntouched() {
            assertThat(canonicalPasses.get()).isEqualTo(0)
            assertThat(transportPasses.get()).isEqualTo(0)
            assertThat(requestBodies.get()).isEqualTo(0)
        }
    }

    private companion object {
        const val SECRET = "test-credential-marker"
        const val FINGERPRINT = "fp_test_clinical"
        const val DAY_MS = 24L * 60L * 60L * 1_000L
    }
}
