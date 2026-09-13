package io.aaps.copilot.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.aaps.copilot.config.ClinicalAiModelIdPolicy
import io.aaps.copilot.config.ClinicalAiProviderId
import java.nio.charset.StandardCharsets
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

class AnthropicClinicalAiGateway private constructor(
    private val delegate: ClinicalOpenAiClient
) : ClinicalAiGateway by delegate {

    constructor(
        modelId: String,
        requestByteBudget: Int = ClinicalOpenAiClient.DEFAULT_REQUEST_BYTE_BUDGET,
        maxResponseBytes: Int = ClinicalOpenAiClient.DEFAULT_MAX_RESPONSE_BYTES,
        timeouts: ClinicalOpenAiTimeouts = ClinicalOpenAiTimeouts()
    ) : this(
        ClinicalOpenAiClient.forTransport(
            transport = AnthropicClinicalStructuredTransport(
                endpoint = OFFICIAL_ENDPOINT.toHttpUrl(),
                modelId = ClinicalAiModelIdPolicy.requireValid(modelId)
            ),
            requestByteBudget = requestByteBudget,
            maxResponseBytes = maxResponseBytes,
            timeouts = timeouts
        )
    )

    companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MAX_CONNECTION_REQUEST_BYTES = 4 * 1_024

        private const val OFFICIAL_ENDPOINT = "https://api.anthropic.com/v1/messages"

        internal fun forTest(
            endpoint: String,
            model: String,
            requestByteBudget: Int = ClinicalOpenAiClient.DEFAULT_REQUEST_BYTE_BUDGET,
            maxResponseBytes: Int = ClinicalOpenAiClient.DEFAULT_MAX_RESPONSE_BYTES,
            timeouts: ClinicalOpenAiTimeouts = ClinicalOpenAiTimeouts(),
            monotonicClockNanos: () -> Long = System::nanoTime
        ): AnthropicClinicalAiGateway {
            val url = try {
                endpoint.toHttpUrl()
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid clinical test endpoint")
            }
            require(isExactLoopbackMessagesUrl(url)) {
                "Clinical test endpoint must be loopback /v1/messages"
            }
            return AnthropicClinicalAiGateway(
                ClinicalOpenAiClient.forTransport(
                    transport = AnthropicClinicalStructuredTransport(
                        endpoint = url,
                        modelId = ClinicalAiModelIdPolicy.requireValid(model)
                    ),
                    requestByteBudget = requestByteBudget,
                    maxResponseBytes = maxResponseBytes,
                    timeouts = timeouts,
                    monotonicClockNanos = monotonicClockNanos
                )
            )
        }

        internal fun requestBytesForTest(
            dataset: ClinicalReportDataset,
            model: String
        ): Int = ClinicalOpenAiClient.requestBytesForTest(
            dataset = dataset,
            transport = AnthropicClinicalStructuredTransport(
                endpoint = OFFICIAL_ENDPOINT.toHttpUrl(),
                modelId = ClinicalAiModelIdPolicy.requireValid(model)
            )
        )

        private fun isExactLoopbackMessagesUrl(url: HttpUrl): Boolean {
            val loopback = url.host == "127.0.0.1" ||
                url.host == "localhost" ||
                url.host == "::1"
            return loopback &&
                url.scheme == "http" &&
                url.encodedPath == "/v1/messages" &&
                url.query == null &&
                url.username.isEmpty() &&
                url.password.isEmpty()
        }
    }
}

private class AnthropicClinicalStructuredTransport(
    private val endpoint: HttpUrl,
    override val modelId: String
) : ClinicalAiStructuredTransport {
    override val providerId: ClinicalAiProviderId = ClinicalAiProviderId.ANTHROPIC

    override fun requestBody(input: String, kind: ClinicalResponseKind): ByteArray =
        requestJson(
            input = input,
            schema = when (kind) {
                ClinicalResponseKind.FINAL_REPORT -> ClinicalOpenAiClient.strictSchemaJson()
                ClinicalResponseKind.CHUNK_REPORT ->
                    ClinicalOpenAiClient.strictChunkSchemaJson()
                ClinicalResponseKind.ALERT_CAUSE_V1 ->
                    ClinicalOpenAiClient.strictAlertCauseSchemaJson()
            },
            maxTokens = when (kind) {
                ClinicalResponseKind.FINAL_REPORT -> ClinicalOpenAiClient.MAX_OUTPUT_TOKENS
                ClinicalResponseKind.CHUNK_REPORT ->
                    ClinicalOpenAiClient.CHUNK_MAX_OUTPUT_TOKENS
                ClinicalResponseKind.ALERT_CAUSE_V1 ->
                    ClinicalOpenAiClient.ALERT_CAUSE_MAX_OUTPUT_TOKENS
            },
            system = if (kind == ClinicalResponseKind.ALERT_CAUSE_V1) {
                ClinicalOpenAiClient.ALERT_CAUSE_INSTRUCTIONS
            } else {
                ClinicalOpenAiClient.CLINICAL_INSTRUCTIONS
            }
        )

    override fun connectionRequestBody(): ByteArray =
        requestJson(
            input = "Connection test. Return ok=true.",
            schema = JsonObject().apply {
                addProperty("type", "object")
                addProperty("additionalProperties", false)
                add("properties", JsonObject().apply {
                    add("ok", JsonObject().apply {
                        addProperty("type", "boolean")
                        add("const", JsonPrimitive(true))
                    })
                })
                add("required", JsonArray().apply { add("ok") })
            },
            maxTokens = 1_024,
            system = "Return only the fixed connection status object."
        )

    override fun request(credential: String, body: ByteArray): Request =
        Request.Builder()
            .url(endpoint)
            .header("x-api-key", credential)
            .header("anthropic-version", AnthropicClinicalAiGateway.ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .post(OneShotJsonRequestBody(body))
            .build()

    override fun parseEnvelope(raw: String): ClinicalStructuredEnvelope {
        val root = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (_: Exception) {
            invalid()
        }
        val rootFields = root.keySet()
        if (
            !rootFields.containsAll(REQUIRED_ROOT_FIELDS) ||
            rootFields.any { it !in ALLOWED_ROOT_FIELDS }
        ) {
            invalid()
        }
        root.requiredString("id", 256)
        if (root.requiredString("type", 16) != "message") invalid()
        if (root.requiredString("role", 16) != "assistant") invalid()
        val responseModel = root.requiredString(
            "model",
            ClinicalOpenAiClient.MAX_MODEL_ID_CODE_POINTS
        )
        if (responseModel != modelId) throw ClinicalAiGatewayException.IdentityMismatch()
        root.get("container")?.let { container ->
            if (!container.isJsonNull) invalid()
        }

        if (root.get("stop_sequence")?.isJsonNull != true) invalid()
        val stopReason = root.requiredString("stop_reason", 64)
        validateStopDetails(root.get("stop_details"), stopReason)
        if (root.get("usage")?.isJsonObject != true) invalid()

        val allowEmptyContent = when (stopReason) {
            "end_turn" -> false
            "refusal",
            "max_tokens",
            "model_context_window_exceeded",
            "pause_turn" -> true
            else -> invalid()
        }
        val output = validateContent(root.get("content"), allowEmptyContent)

        when (stopReason) {
            "end_turn" -> Unit
            "refusal" -> throw ClinicalOpenAiException.Refusal()
            "max_tokens",
            "model_context_window_exceeded",
            "pause_turn" -> throw ClinicalOpenAiException.Incomplete()
            else -> invalid()
        }

        return ClinicalStructuredEnvelope(
            output = output ?: invalid(),
            model = responseModel,
            systemFingerprint = null
        )
    }

    private fun validateContent(contentElement: JsonElement?, allowEmpty: Boolean): String? {
        val content = contentElement
            ?.takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
            ?: invalid()
        if (content.size() == 0) {
            if (allowEmpty) return null
            invalid()
        }
        if (content.size() != 1) invalid()
        val block = content.single()
            .takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
            ?: invalid()
        val blockFields = block.keySet()
        if (
            !blockFields.containsAll(REQUIRED_TEXT_BLOCK_FIELDS) ||
            blockFields.any { it !in ALLOWED_TEXT_BLOCK_FIELDS }
        ) {
            invalid()
        }
        if (block.requiredString("type", 16) != "text") invalid()
        block.get("citations")?.let { citations ->
            if (
                !citations.isJsonNull &&
                (!citations.isJsonArray || citations.asJsonArray.size() != 0)
            ) {
                invalid()
            }
        }
        val output = block.requiredString("text", Int.MAX_VALUE)
        if (output.isBlank()) invalid()
        return output
    }

    private fun validateStopDetails(stopDetails: JsonElement?, stopReason: String) {
        if (stopReason != "refusal") {
            if (stopDetails != null && !stopDetails.isJsonNull) invalid()
            return
        }
        if (stopDetails == null || stopDetails.isJsonNull) return
        if (!stopDetails.isJsonObject) invalid()
        val details = stopDetails.asJsonObject
        if (details.keySet() != STOP_DETAILS_FIELDS) invalid()
        if (details.requiredString("type", 32) != "refusal") invalid()
        details.requiredNullableString("category", 128)
        details.requiredNullableString("explanation", 4_096)
    }

    private fun requestJson(
        input: String,
        schema: JsonObject,
        maxTokens: Int,
        system: String
    ): ByteArray = JsonObject().apply {
        addProperty("model", modelId)
        addProperty("max_tokens", maxTokens)
        addProperty("system", system)
        add("messages", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "user")
                add("content", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", input)
                    })
                })
            })
        })
        add("output_config", JsonObject().apply {
            add("format", JsonObject().apply {
                addProperty("type", "json_schema")
                add("schema", anthropicCompatibleSchema(schema))
            })
        })
    }.toString().toByteArray(StandardCharsets.UTF_8)

    private fun anthropicCompatibleSchema(schema: JsonObject): JsonObject =
        schema.deepCopy().also(::removeUnsupportedSchemaConstraints)

    private fun removeUnsupportedSchemaConstraints(node: JsonElement) {
        when {
            node.isJsonObject -> {
                val value = node.asJsonObject
                UNSUPPORTED_SCHEMA_KEYS.forEach(value::remove)
                value.entrySet().forEach { (_, child) ->
                    removeUnsupportedSchemaConstraints(child)
                }
            }
            node.isJsonArray -> node.asJsonArray.forEach(::removeUnsupportedSchemaConstraints)
        }
    }

    private fun JsonObject.requiredString(key: String, maxCodePoints: Int): String {
        val element = get(key)
        if (element == null || !element.isJsonPrimitive ||
            !element.asJsonPrimitive.isString
        ) {
            invalid()
        }
        val value = element.asString
        if (value.isEmpty() ||
            value != value.trim() ||
            value.codePointCount(0, value.length) > maxCodePoints
        ) {
            invalid()
        }
        return value
    }

    private fun JsonObject.requiredNullableString(key: String, maxCodePoints: Int): String? {
        val element = get(key) ?: invalid()
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) invalid()
        val value = element.asString
        if (value.isEmpty() ||
            value != value.trim() ||
            value.codePointCount(0, value.length) > maxCodePoints
        ) {
            invalid()
        }
        return value
    }

    private fun invalid(): Nothing = throw ClinicalOpenAiException.InvalidResponse()

    private companion object {
        val REQUIRED_ROOT_FIELDS = setOf(
            "id",
            "type",
            "role",
            "model",
            "content",
            "stop_reason",
            "stop_sequence",
            "usage"
        )
        val ALLOWED_ROOT_FIELDS = REQUIRED_ROOT_FIELDS + setOf("container", "stop_details")
        val REQUIRED_TEXT_BLOCK_FIELDS = setOf("type", "text")
        val ALLOWED_TEXT_BLOCK_FIELDS = REQUIRED_TEXT_BLOCK_FIELDS + "citations"
        val STOP_DETAILS_FIELDS = setOf("type", "category", "explanation")
        val UNSUPPORTED_SCHEMA_KEYS = setOf(
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
