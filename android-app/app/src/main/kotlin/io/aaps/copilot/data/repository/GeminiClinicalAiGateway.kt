package io.aaps.copilot.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.aaps.copilot.config.ClinicalAiModelIdPolicy
import io.aaps.copilot.config.ClinicalAiProviderId
import java.nio.charset.StandardCharsets
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

class GeminiClinicalAiGateway private constructor(
    private val delegate: ClinicalOpenAiClient
) : ClinicalAiGateway by delegate {

    constructor(
        modelId: String,
        requestByteBudget: Int = ClinicalOpenAiClient.DEFAULT_REQUEST_BYTE_BUDGET,
        maxResponseBytes: Int = ClinicalOpenAiClient.DEFAULT_MAX_RESPONSE_BYTES,
        timeouts: ClinicalOpenAiTimeouts = ClinicalOpenAiTimeouts()
    ) : this(
        ClinicalOpenAiClient.forTransport(
            transport = geminiTransport(OFFICIAL_BASE_URL.toHttpUrl(), modelId),
            requestByteBudget = requestByteBudget,
            maxResponseBytes = maxResponseBytes,
            timeouts = timeouts
        )
    )

    companion object {
        const val MAX_CONNECTION_REQUEST_BYTES = 4 * 1_024

        private const val OFFICIAL_BASE_URL = "https://generativelanguage.googleapis.com/"

        internal fun forTest(
            baseUrl: String,
            model: String,
            requestByteBudget: Int = ClinicalOpenAiClient.DEFAULT_REQUEST_BYTE_BUDGET,
            maxResponseBytes: Int = ClinicalOpenAiClient.DEFAULT_MAX_RESPONSE_BYTES,
            timeouts: ClinicalOpenAiTimeouts = ClinicalOpenAiTimeouts(),
            monotonicClockNanos: () -> Long = System::nanoTime
        ): GeminiClinicalAiGateway {
            val url = try {
                baseUrl.toHttpUrl()
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid clinical test base URL")
            }
            require(isExactLoopbackRoot(url)) {
                "Clinical test base URL must be an exact loopback root"
            }
            return GeminiClinicalAiGateway(
                ClinicalOpenAiClient.forTransport(
                    transport = geminiTransport(url, model),
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
            transport = geminiTransport(OFFICIAL_BASE_URL.toHttpUrl(), model)
        )

        private fun geminiTransport(
            baseUrl: HttpUrl,
            rawModelId: String
        ): GeminiClinicalStructuredTransport {
            val modelId = requireNativeModelId(rawModelId)
            val endpoint = baseUrl.newBuilder()
                .addPathSegments("v1beta/models")
                .addPathSegment("$modelId:generateContent")
                .build()
            return GeminiClinicalStructuredTransport(endpoint, modelId)
        }

        private fun requireNativeModelId(raw: String): String {
            val modelId = ClinicalAiModelIdPolicy.requireValid(raw)
            require(modelId.none { it == '/' || it == '?' || it == '#' }) {
                "Native Gemini model ID contains a route delimiter"
            }
            return modelId
        }

        private fun isExactLoopbackRoot(url: HttpUrl): Boolean {
            val loopback = url.host == "127.0.0.1" ||
                url.host == "localhost" ||
                url.host == "::1"
            return loopback &&
                url.scheme == "http" &&
                url.encodedPath == "/" &&
                url.query == null &&
                url.username.isEmpty() &&
                url.password.isEmpty()
        }
    }
}

private class GeminiClinicalStructuredTransport(
    private val endpoint: HttpUrl,
    override val modelId: String
) : ClinicalAiStructuredTransport {
    override val providerId: ClinicalAiProviderId = ClinicalAiProviderId.GEMINI

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
            .header("x-goog-api-key", credential)
            .header("Content-Type", "application/json")
            .post(OneShotJsonRequestBody(body))
            .build()

    override fun parseEnvelope(raw: String): ClinicalStructuredEnvelope {
        val root = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (_: Exception) {
            invalid()
        }
        if (root.keySet().any { it !in ALLOWED_ROOT_FIELDS }) invalid()
        validateIgnoredRootMetadata(root)
        validatePromptFeedback(root.get("promptFeedback"))

        val candidates = root.get("candidates")
            ?.takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
            ?: invalid()
        if (candidates.size() != 1) invalid()
        val candidate = candidates.single()
            .takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
            ?: invalid()
        if (
            !candidate.keySet().contains("finishReason") ||
            candidate.keySet().any { it !in ALLOWED_CANDIDATE_FIELDS }
        ) {
            invalid()
        }
        validateIgnoredCandidateMetadata(candidate)
        val finishReason = candidate.requiredString("finishReason", 64)
        val output = when (finishReason) {
            "STOP" -> validateCandidateContent(candidate.get("content"))
                .takeUnless(String::isBlank)
                ?: invalid()
            "MAX_TOKENS" -> throw ClinicalOpenAiException.Incomplete()
            "SAFETY",
            "RECITATION",
            "LANGUAGE",
            "BLOCKLIST",
            "PROHIBITED_CONTENT",
            "SPII",
            "IMAGE_SAFETY",
            "ESCALATION",
            "IMAGE_PROHIBITED_CONTENT",
            "IMAGE_RECITATION" -> throw ClinicalOpenAiException.Refusal()
            else -> invalid()
        }

        return ClinicalStructuredEnvelope(
            output = output,
            model = modelId,
            systemFingerprint = null
        )
    }

    private fun validateCandidateContent(element: JsonElement?): String {
        val content = element
            ?.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
            ?: invalid()
        if (content.keySet() != CONTENT_FIELDS) invalid()
        if (content.requiredString("role", 16) != "model") invalid()
        val parts = content.get("parts")
            ?.takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
            ?: invalid()
        if (parts.size() != 1) invalid()
        val part = parts.single()
            .takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
            ?: invalid()
        if (
            !part.has("text") ||
            part.keySet().any { it !in ALLOWED_PART_FIELDS }
        ) {
            invalid()
        }
        part.get("thoughtSignature")?.let(::validateThoughtSignature)
        return part.requiredText("text")
    }

    private fun validateThoughtSignature(element: JsonElement) {
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) invalid()
        val value = element.asString
        val codePoints = value.codePointCount(0, value.length)
        if (codePoints !in 1..MAX_THOUGHT_SIGNATURE_CODE_POINTS) invalid()

        val paddingStart = value.indexOf('=').let { if (it < 0) value.length else it }
        if (value.substring(0, paddingStart).any { !it.isBase64DataCharacter() }) invalid()
        val paddingCount = value.length - paddingStart
        if (
            value.substring(paddingStart).any { it != '=' } ||
            paddingCount > 2 ||
            value.length % 4 != 0
        ) {
            invalid()
        }
    }

    private fun Char.isBase64DataCharacter(): Boolean =
        this in 'A'..'Z' ||
            this in 'a'..'z' ||
            this in '0'..'9' ||
            this == '+' ||
            this == '/'

    private fun validateIgnoredCandidateMetadata(candidate: JsonObject) {
        CANDIDATE_ARRAY_FIELDS.forEach { field ->
            candidate.get(field)?.let {
                if (!it.isJsonArray) invalid()
            }
        }
        CANDIDATE_OBJECT_FIELDS.forEach { field ->
            candidate.get(field)?.let {
                if (!it.isJsonObject) invalid()
            }
        }
        CANDIDATE_NONNEGATIVE_INTEGER_FIELDS.forEach { field ->
            candidate.get(field)?.let {
                if (
                    !it.isJsonPrimitive ||
                    !it.asJsonPrimitive.isNumber ||
                    it.toString().toIntOrNull()?.let { value -> value >= 0 } != true
                ) {
                    invalid()
                }
            }
        }
        candidate.get("avgLogprobs")?.let {
            if (
                !it.isJsonPrimitive ||
                !it.asJsonPrimitive.isNumber ||
                !it.asDouble.isFinite()
            ) {
                invalid()
            }
        }
        candidate.get("finishMessage")?.let {
            candidate.requiredString("finishMessage", 4_096)
        }
    }

    private fun validatePromptFeedback(element: JsonElement?) {
        if (element == null) return
        if (!element.isJsonObject) invalid()
        val feedback = element.asJsonObject
        if (feedback.keySet().any { it !in PROMPT_FEEDBACK_FIELDS }) invalid()
        feedback.get("safetyRatings")?.let {
            if (!it.isJsonArray) invalid()
        }
        if (feedback.has("blockReason")) {
            feedback.requiredString("blockReason", 64)
            throw ClinicalOpenAiException.Refusal()
        }
    }

    private fun validateIgnoredRootMetadata(root: JsonObject) {
        root.get("usageMetadata")?.let {
            if (!it.isJsonObject) invalid()
        }
        root.get("responseId")?.let {
            root.requiredString("responseId", 256)
        }
        root.get("modelStatus")?.let {
            if (!it.isJsonObject) invalid()
        }
        root.get("modelVersion")?.let {
            val reported = root.requiredString(
                "modelVersion",
                ClinicalOpenAiClient.MAX_MODEL_ID_CODE_POINTS + MODEL_RESOURCE_PREFIX.length
            )
            // Gemini may report either the bare ID or its canonical models/{id} resource name.
            val normalized = reported.removePrefix(MODEL_RESOURCE_PREFIX)
            if (normalized != modelId || normalized.startsWith(MODEL_RESOURCE_PREFIX)) {
                throw ClinicalAiGatewayException.IdentityMismatch()
            }
        }
    }

    private fun requestJson(
        input: String,
        schema: JsonObject,
        maxTokens: Int,
        system: String
    ): ByteArray = JsonObject().apply {
        add("systemInstruction", JsonObject().apply {
            add("parts", JsonArray().apply {
                add(JsonObject().apply { addProperty("text", system) })
            })
        })
        add("contents", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "user")
                add("parts", JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", input) })
                })
            })
        })
        add("generationConfig", JsonObject().apply {
            addProperty("candidateCount", 1)
            addProperty("maxOutputTokens", maxTokens)
            addProperty("responseMimeType", "application/json")
            add("responseJsonSchema", schema)
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

    private fun invalid(): Nothing = throw ClinicalOpenAiException.InvalidResponse()

    private companion object {
        const val MODEL_RESOURCE_PREFIX = "models/"
        val ALLOWED_ROOT_FIELDS = setOf(
            "candidates",
            "promptFeedback",
            "usageMetadata",
            "responseId",
            "modelVersion",
            "modelStatus"
        )
        val PROMPT_FEEDBACK_FIELDS = setOf("blockReason", "safetyRatings")
        val ALLOWED_CANDIDATE_FIELDS = setOf(
            "content",
            "finishReason",
            "safetyRatings",
            "citationMetadata",
            "tokenCount",
            "groundingAttributions",
            "groundingMetadata",
            "avgLogprobs",
            "logprobsResult",
            "urlContextMetadata",
            "index",
            "finishMessage"
        )
        val CONTENT_FIELDS = setOf("role", "parts")
        val ALLOWED_PART_FIELDS = setOf("text", "thoughtSignature")
        const val MAX_THOUGHT_SIGNATURE_CODE_POINTS = 8_192
        val CANDIDATE_ARRAY_FIELDS = setOf("safetyRatings", "groundingAttributions")
        val CANDIDATE_OBJECT_FIELDS = setOf(
            "citationMetadata",
            "groundingMetadata",
            "logprobsResult",
            "urlContextMetadata"
        )
        val CANDIDATE_NONNEGATIVE_INTEGER_FIELDS = setOf("tokenCount", "index")
    }
}
