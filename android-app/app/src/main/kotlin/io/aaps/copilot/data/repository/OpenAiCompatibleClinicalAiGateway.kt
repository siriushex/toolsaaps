package io.aaps.copilot.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.aaps.copilot.config.ClinicalAiProviderConfig
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.config.OpenAiCompatibleProtocol
import java.nio.charset.StandardCharsets
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

class OpenAiCompatibleClinicalAiGateway private constructor(
    private val delegate: ClinicalOpenAiClient
) : ClinicalAiGateway by delegate {

    constructor(
        config: ClinicalAiProviderConfig,
        requestByteBudget: Int = ClinicalOpenAiClient.DEFAULT_REQUEST_BYTE_BUDGET,
        maxResponseBytes: Int = ClinicalOpenAiClient.DEFAULT_MAX_RESPONSE_BYTES,
        timeouts: ClinicalOpenAiTimeouts = ClinicalOpenAiTimeouts()
    ) : this(
        ClinicalOpenAiClient.forTransport(
            transport = transport(config),
            requestByteBudget = requestByteBudget,
            maxResponseBytes = maxResponseBytes,
            timeouts = timeouts
        )
    )

    companion object {
        const val MAX_CONNECTION_REQUEST_BYTES = 4 * 1_024

        internal fun requestBytesForTest(
            dataset: ClinicalReportDataset,
            config: ClinicalAiProviderConfig
        ): Int = ClinicalOpenAiClient.requestBytesForTest(
            dataset = dataset,
            transport = transport(config)
        )

        private fun transport(
            config: ClinicalAiProviderConfig
        ): ClinicalAiStructuredTransport {
            require(config.providerId == ClinicalAiProviderId.OPENAI_COMPATIBLE) {
                "Compatible gateway requires an OPENAI_COMPATIBLE configuration"
            }
            val baseUrl = requireNotNull(config.endpoint).toHttpUrl()
            val protocol = requireNotNull(config.compatibleProtocol)
            val endpoint = endpoint(baseUrl, protocol)
            return when (protocol) {
                OpenAiCompatibleProtocol.RESPONSES ->
                    CompatibleResponsesTransport(endpoint, config.modelId)

                OpenAiCompatibleProtocol.CHAT_COMPLETIONS ->
                    CompatibleChatCompletionsTransport(endpoint, config.modelId)
            }
        }

        private fun endpoint(
            baseUrl: HttpUrl,
            protocol: OpenAiCompatibleProtocol
        ): HttpUrl {
            val builder = baseUrl.newBuilder()
            val hasVersion = baseUrl.encodedPathSegments.lastOrNull() == "v1"
            if (!hasVersion) builder.addPathSegment("v1")
            when (protocol) {
                OpenAiCompatibleProtocol.RESPONSES -> builder.addPathSegment("responses")
                OpenAiCompatibleProtocol.CHAT_COMPLETIONS ->
                    builder.addPathSegments("chat/completions")
            }
            return builder.build()
        }
    }
}

private class CompatibleResponsesTransport(
    private val endpoint: HttpUrl,
    override val modelId: String
) : ClinicalAiStructuredTransport {
    override val providerId: ClinicalAiProviderId = ClinicalAiProviderId.OPENAI_COMPATIBLE

    override fun requestBody(input: String, kind: ClinicalResponseKind): ByteArray =
        ClinicalOpenAiClient.buildRequestJson(input, modelId, kind)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

    override fun connectionRequestBody(): ByteArray =
        ClinicalOpenAiClient.buildConnectionRequestJson(modelId)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

    override fun request(credential: String, body: ByteArray): Request =
        bearerRequest(endpoint, credential, body)

    override fun parseEnvelope(raw: String): ClinicalStructuredEnvelope {
        val root = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (_: Exception) {
            invalid()
        }
        val status = root.get("status")
            ?.takeIf(JsonElement::isJsonPrimitive)
            ?.asJsonPrimitive
            ?.takeIf { it.isString }
            ?.asString
            ?: invalid()
        when (status) {
            "completed" -> Unit
            in INCOMPLETE_STATUSES -> throw ClinicalOpenAiException.Incomplete()
            else -> invalid()
        }
        CONFLICT_FIELDS.forEach { field ->
            root.get(field)?.let {
                if (!it.isJsonNull) invalid()
            }
        }
        return ClinicalOpenAiClient.parseOpenAiEnvelope(raw, modelId)
    }

    private fun invalid(): Nothing = throw ClinicalOpenAiException.InvalidResponse()

    private companion object {
        val INCOMPLETE_STATUSES = setOf(
            "in_progress",
            "failed",
            "incomplete",
            "cancelled",
            "queued"
        )
        val CONFLICT_FIELDS = setOf("error", "incomplete_details")
    }
}

private class CompatibleChatCompletionsTransport(
    private val endpoint: HttpUrl,
    override val modelId: String
) : ClinicalAiStructuredTransport {
    override val providerId: ClinicalAiProviderId = ClinicalAiProviderId.OPENAI_COMPATIBLE

    override fun requestBody(input: String, kind: ClinicalResponseKind): ByteArray {
        val isChunk = kind == ClinicalResponseKind.CHUNK_REPORT
        val isAlert = kind == ClinicalResponseKind.ALERT_CAUSE_V1
        return requestJson(
            input = input,
            system = if (isAlert) ClinicalOpenAiClient.ALERT_CAUSE_INSTRUCTIONS
            else ClinicalOpenAiClient.CLINICAL_INSTRUCTIONS,
            schemaName = when {
                isAlert -> ClinicalOpenAiClient.ALERT_CAUSE_SCHEMA_NAME
                isChunk -> ClinicalOpenAiClient.CHUNK_SCHEMA_NAME
                else -> ClinicalOpenAiClient.SCHEMA_NAME
            },
            schema = when {
                isAlert -> ClinicalOpenAiClient.strictAlertCauseSchemaJson()
                isChunk -> ClinicalOpenAiClient.strictChunkSchemaJson()
                else -> ClinicalOpenAiClient.strictSchemaJson()
            },
            maxTokens = when {
                isAlert -> ClinicalOpenAiClient.ALERT_CAUSE_MAX_OUTPUT_TOKENS
                isChunk -> ClinicalOpenAiClient.CHUNK_MAX_OUTPUT_TOKENS
                else -> ClinicalOpenAiClient.MAX_OUTPUT_TOKENS
            }
        )
    }

    override fun connectionRequestBody(): ByteArray =
        requestJson(
            input = "Connection test. Return ok=true.",
            system = "Return only the fixed connection status object.",
            schemaName = "clinical_ai_connection_test_v1",
            schema = connectionSchema(),
            maxTokens = 1_024
        )

    override fun request(credential: String, body: ByteArray): Request =
        bearerRequest(endpoint, credential, body)

    override fun parseEnvelope(raw: String): ClinicalStructuredEnvelope {
        val root = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (_: Exception) {
            invalid()
        }
        if (
            !root.keySet().containsAll(REQUIRED_ROOT_FIELDS) ||
            root.keySet().any { it !in ALLOWED_ROOT_FIELDS }
        ) {
            invalid()
        }
        validateRootMetadata(root)
        val responseModel = root.requiredString(
            "model",
            ClinicalOpenAiClient.MAX_MODEL_ID_CODE_POINTS
        )
        if (responseModel != modelId) throw ClinicalAiGatewayException.IdentityMismatch()

        val choices = root.get("choices")
            ?.takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
            ?: invalid()
        if (choices.size() != 1) invalid()
        val choice = choices.single()
            .takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
            ?: invalid()
        if (
            !choice.keySet().containsAll(REQUIRED_CHOICE_FIELDS) ||
            choice.keySet().any { it !in ALLOWED_CHOICE_FIELDS }
        ) {
            invalid()
        }
        validateChoiceMetadata(choice)

        val finishReason = choice.requiredString("finish_reason", 64)
        val output = when (finishReason) {
            "stop" -> parseAssistantMessage(choice.get("message"))
            "length", "max_tokens" -> throw ClinicalOpenAiException.Incomplete()
            "content_filter", "refusal" -> throw ClinicalOpenAiException.Refusal()
            else -> invalid()
        }
        return ClinicalStructuredEnvelope(
            output = output,
            model = responseModel,
            systemFingerprint = root.optionalString(
                "system_fingerprint",
                ClinicalOpenAiClient.MAX_SYSTEM_FINGERPRINT_CODE_POINTS
            )
        )
    }

    private fun parseAssistantMessage(element: JsonElement?): String {
        val message = element
            ?.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
            ?: invalid()
        if (
            !message.keySet().containsAll(REQUIRED_MESSAGE_FIELDS) ||
            message.keySet().any { it !in ALLOWED_MESSAGE_FIELDS }
        ) {
            invalid()
        }
        if (message.requiredString("role", 16) != "assistant") invalid()
        message.get("annotations")?.let {
            if (!it.isJsonArray) invalid()
        }
        message.get("refusal")?.let { refusal ->
            when {
                refusal.isJsonNull -> Unit
                refusal.isJsonPrimitive && refusal.asJsonPrimitive.isString -> {
                    if (refusal.asString.isBlank()) invalid()
                    throw ClinicalOpenAiException.Refusal()
                }
                else -> invalid()
            }
        }
        val content = message.requiredText("content")
        if (content.isBlank()) invalid()
        return content
    }

    private fun validateRootMetadata(root: JsonObject) {
        root.get("id")?.let { root.requiredString("id", 256) }
        root.get("object")?.let {
            if (root.requiredString("object", 32) != "chat.completion") invalid()
        }
        root.get("created")?.let {
            if (!it.isNonnegativeInteger()) invalid()
        }
        root.get("usage")?.let {
            if (!it.isJsonObject) invalid()
        }
        root.get("service_tier")?.let {
            if (!it.isJsonNull) root.requiredString("service_tier", 64)
        }
    }

    private fun validateChoiceMetadata(choice: JsonObject) {
        choice.get("index")?.let {
            if (!it.isNonnegativeInteger() || it.asInt != 0) invalid()
        }
        choice.get("logprobs")?.let {
            if (!it.isJsonNull && !it.isJsonObject) invalid()
        }
    }

    private fun requestJson(
        input: String,
        system: String,
        schemaName: String,
        schema: JsonObject,
        maxTokens: Int
    ): ByteArray = JsonObject().apply {
        addProperty("model", modelId)
        add("messages", JsonArray().apply {
            add(message("system", system))
            add(message("user", input))
        })
        addProperty("max_completion_tokens", maxTokens)
        add("response_format", JsonObject().apply {
            addProperty("type", "json_schema")
            add("json_schema", chatJsonSchema(schemaName, schema))
        })
    }.toString().toByteArray(StandardCharsets.UTF_8)

    private fun JsonObject.requiredString(key: String, maxCodePoints: Int): String {
        val element = get(key)
        if (
            element == null ||
            !element.isJsonPrimitive ||
            !element.asJsonPrimitive.isString
        ) {
            invalid()
        }
        val value = element.asString
        if (
            value.isEmpty() ||
            value != value.trim() ||
            value.codePointCount(0, value.length) > maxCodePoints
        ) {
            invalid()
        }
        return value
    }

    private fun JsonObject.optionalString(key: String, maxCodePoints: Int): String? {
        val element = get(key) ?: return null
        if (element.isJsonNull) return null
        return requiredString(key, maxCodePoints)
    }

    private fun JsonObject.requiredText(key: String): String {
        val element = get(key)
        if (
            element == null ||
            !element.isJsonPrimitive ||
            !element.asJsonPrimitive.isString
        ) {
            invalid()
        }
        return element.asString
    }

    private fun JsonElement.isNonnegativeInteger(): Boolean =
        isJsonPrimitive &&
            asJsonPrimitive.isNumber &&
            toString().toLongOrNull()?.let { it >= 0L } == true

    private fun invalid(): Nothing = throw ClinicalOpenAiException.InvalidResponse()

    private companion object {
        val REQUIRED_ROOT_FIELDS = setOf("model", "choices")
        val ALLOWED_ROOT_FIELDS = setOf(
            "id",
            "object",
            "created",
            "model",
            "choices",
            "usage",
            "system_fingerprint",
            "service_tier"
        )
        val REQUIRED_CHOICE_FIELDS = setOf("message", "finish_reason")
        val ALLOWED_CHOICE_FIELDS = setOf("index", "message", "logprobs", "finish_reason")
        val REQUIRED_MESSAGE_FIELDS = setOf("role", "content")
        val ALLOWED_MESSAGE_FIELDS = setOf("role", "content", "refusal", "annotations")
    }
}

private fun bearerRequest(
    endpoint: HttpUrl,
    credential: String,
    body: ByteArray
): Request = Request.Builder()
    .url(endpoint)
    .header("Authorization", "Bearer $credential")
    .header("Content-Type", "application/json")
    .post(OneShotJsonRequestBody(body))
    .build()

private fun message(role: String, content: String): JsonObject = JsonObject().apply {
    addProperty("role", role)
    addProperty("content", content)
}

private fun chatJsonSchema(name: String, schema: JsonObject): JsonObject = JsonObject().apply {
    addProperty("name", name)
    addProperty("strict", true)
    add("schema", schema)
}

private fun connectionSchema(): JsonObject = JsonObject().apply {
    addProperty("type", "object")
    addProperty("additionalProperties", false)
    add("properties", JsonObject().apply {
        add("ok", JsonObject().apply {
            addProperty("type", "boolean")
            add("const", JsonPrimitive(true))
        })
    })
    add("required", JsonArray().apply { add("ok") })
}
