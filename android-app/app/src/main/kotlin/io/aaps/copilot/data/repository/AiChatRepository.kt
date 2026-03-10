package io.aaps.copilot.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.config.isOpenAiApiEndpoint
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class AiChatTurn(
    val role: String,
    val text: String
)

data class AiChatAttachmentRequest(
    val name: String,
    val mimeType: String?,
    val localPath: String,
    val kind: Kind
) {
    enum class Kind {
        IMAGE,
        FILE
    }
}

data class AiForecastOptimizationResult(
    val model: String,
    val status: String,
    val confidence: Double,
    val focusHorizonMinutes: Int?,
    val reason: String,
    val notes: List<String>,
    val gainScale5m: Double,
    val gainScale30m: Double,
    val gainScale60m: Double,
    val maxUpScale5m: Double,
    val maxUpScale30m: Double,
    val maxUpScale60m: Double,
    val maxDownScale5m: Double,
    val maxDownScale30m: Double,
    val maxDownScale60m: Double,
    val responseId: String?,
    val responseStatus: String?,
    val outputJson: String
)

class AiChatRepository(
    private val settingsStore: AppSettingsStore,
    private val auditLogger: AuditLogger
) {
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val optimizerHttpClient: OkHttpClient = httpClient.newBuilder()
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun ask(
        question: String,
        contextSummary: String,
        history: List<AiChatTurn> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val settings = settingsStore.settings.first()
        val apiKey = settings.openAiApiKey.trim()
        if (apiKey.isBlank()) {
            error("AI API key is empty. Set it in Settings -> AI API key.")
        }

        val endpoint = normalizeBaseUrl(settings.cloudBaseUrl) + "chat/completions"
        val start = System.currentTimeMillis()
        val requestJson = buildRequestJson(
            question = question,
            contextSummary = contextSummary,
            history = history
        )
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("AI API error ${response.code}: ${responseBody.take(240)}")
                }
                val content = parseAssistantContent(responseBody)
                if (content.isBlank()) {
                    error("AI API returned empty response")
                }
                val latencyMs = System.currentTimeMillis() - start
                auditLogger.info(
                    "ai_chat_completed",
                    mapOf(
                        "latencyMs" to latencyMs,
                        "questionLength" to question.length,
                        "answerLength" to content.length
                    )
                )
                content
            }
        }.getOrElse { error ->
            val latencyMs = System.currentTimeMillis() - start
            val message = error.message ?: "unknown error"
            auditLogger.warn(
                "ai_chat_failed",
                mapOf(
                    "latencyMs" to latencyMs,
                    "error" to message
                )
            )
            throw error
        }
    }

    suspend fun askWithAttachments(
        question: String,
        contextSummary: String,
        history: List<AiChatTurn> = emptyList(),
        attachments: List<AiChatAttachmentRequest> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        if (attachments.isEmpty()) {
            return@withContext ask(question = question, contextSummary = contextSummary, history = history)
        }
        val settings = settingsStore.settings.first()
        val apiKey = settings.openAiApiKey.trim()
        if (apiKey.isBlank()) {
            error("AI API key is empty. Set it in Settings -> AI API key.")
        }
        val endpoint = normalizeBaseUrl(settings.cloudBaseUrl) + "responses"
        val start = System.currentTimeMillis()
        val requestJson = buildResponsesRequestJson(
            question = question,
            contextSummary = contextSummary,
            history = history,
            attachments = attachments
        )
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        runCatching {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("AI API error ${response.code}: ${responseBody.take(240)}")
                }
                val content = parseResponsesOutputTextStatic(responseBody)
                if (content.isBlank()) {
                    error("AI API returned empty response")
                }
                auditLogger.info(
                    "ai_chat_multimodal_completed",
                    mapOf(
                        "latencyMs" to (System.currentTimeMillis() - start),
                        "questionLength" to question.length,
                        "attachments" to attachments.size
                    )
                )
                content
            }
        }.getOrElse { error ->
            auditLogger.warn(
                "ai_chat_multimodal_failed",
                mapOf(
                    "latencyMs" to (System.currentTimeMillis() - start),
                    "attachments" to attachments.size,
                    "error" to (error.message ?: "unknown error")
                )
            )
            throw error
        }
    }

    suspend fun transcribeAudio(
        audioFile: File,
        promptHint: String? = null
    ): String = withContext(Dispatchers.IO) {
        val settings = settingsStore.settings.first()
        val apiKey = settings.openAiApiKey.trim()
        if (apiKey.isBlank()) {
            error("AI API key is empty. Set it in Settings -> AI API key.")
        }
        val endpoint = normalizeBaseUrl(settings.cloudBaseUrl) + "audio/transcriptions"
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", TRANSCRIBE_MODEL_NAME)
            .addFormDataPart("response_format", "json")
            .apply {
                promptHint?.trim()?.takeIf { it.isNotBlank() }?.let {
                    addFormDataPart("prompt", it.take(MAX_TRANSCRIBE_PROMPT_CHARS))
                }
                addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody("audio/mp4".toMediaType())
                )
            }
            .build()
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(multipart)
            .build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Audio transcription error ${response.code}: ${responseBody.take(240)}")
            }
            val text = parseTranscriptionTextStatic(responseBody)
            if (text.isBlank()) {
                error("Audio transcription returned empty text")
            }
            text
        }
    }

    suspend fun synthesizeSpeech(
        text: String,
        outputDir: File
    ): File = withContext(Dispatchers.IO) {
        val settings = settingsStore.settings.first()
        val apiKey = settings.openAiApiKey.trim()
        if (apiKey.isBlank()) {
            error("AI API key is empty. Set it in Settings -> AI key.")
        }
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        val endpoint = normalizeBaseUrl(settings.cloudBaseUrl) + "audio/speech"
        val requestJson = JSONObject()
            .put("model", TTS_MODEL_NAME)
            .put("voice", TTS_VOICE_NAME)
            .put("input", text.take(MAX_TTS_INPUT_CHARS))
            .put("instructions", "Speak clearly, calmly, and concisely. Keep the clinical tone neutral.")
            .put("response_format", "mp3")
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val outputFile = File(outputDir, "ai-chat-tts-${System.currentTimeMillis()}.mp3")
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body?.string().orEmpty()
                error("Audio speech error ${response.code}: ${responseBody.take(240)}")
            }
            val bytes = response.body?.bytes() ?: error("Audio speech returned empty body")
            outputFile.writeBytes(bytes)
        }
        outputFile
    }

    suspend fun requestDailyForecastOptimization(
        payload: DailyForecastReportPayload
    ): AiForecastOptimizationResult = withContext(Dispatchers.IO) {
        val settings = settingsStore.settings.first()
        val apiKey = settings.openAiApiKey.trim()
        if (apiKey.isBlank()) {
            error("AI API key is empty. Set it in Settings -> AI API key.")
        }
        if (!isOpenAiApiEndpoint(settings.cloudBaseUrl)) {
            error("AI optimizer supports OpenAI API endpoint only.")
        }

        val baseUrl = normalizeBaseUrl(settings.cloudBaseUrl)
        val modelsUrl = "${baseUrl}models"
        val responsesUrl = "${baseUrl}responses"
        val model = resolvePreferredOptimizerModel(
            availableModelIds = fetchModelIds(
                url = modelsUrl,
                apiKey = apiKey
            )
        )
        val requestJson = buildOptimizerRequestJson(
            model = model,
            payload = payload
        )
        val start = System.currentTimeMillis()
        val initialResponse = postJson(
            url = responsesUrl,
            apiKey = apiKey,
            body = requestJson,
            client = optimizerHttpClient
        )
        val responseId = initialResponse.optString("id").takeIf { it.isNotBlank() }
        val finalResponse = pollResponseIfNeeded(
            baseUrl = baseUrl,
            apiKey = apiKey,
            initial = initialResponse,
            responseId = responseId,
            client = optimizerHttpClient
        )
        val outputText = parseResponsesOutputTextStatic(finalResponse.toString())
        if (outputText.isBlank()) {
            error("OpenAI daily optimizer returned empty structured output.")
        }
        val parsed = parseOptimizerOutputStatic(outputText).copy(
            model = model,
            responseId = responseId,
            responseStatus = finalResponse.optString("status").ifBlank { null },
            outputJson = outputText
        )
        val latencyMs = System.currentTimeMillis() - start
        auditLogger.info(
            "ai_daily_optimizer_completed",
            mapOf(
                "latencyMs" to latencyMs,
                "model" to model,
                "status" to parsed.status,
                "confidence" to parsed.confidence,
                "focusHorizonMinutes" to parsed.focusHorizonMinutes
            )
        )
        parsed
    }

    private fun buildRequestJson(
        question: String,
        contextSummary: String,
        history: List<AiChatTurn>
    ): JSONObject {
        val messages = JSONArray().apply {
            put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        "You are an assistant for AAPS Predictive Copilot. " +
                            "Use only provided telemetry and analysis context. " +
                            "Be concise, practical, and safety-aware. " +
                            "Do not prescribe medication doses. " +
                            "If data is insufficient, say exactly what is missing."
                    )
            )
            put(
                JSONObject()
                    .put("role", "system")
                    .put("content", "Data context:\n$contextSummary")
            )
            history.takeLast(MAX_HISTORY_TURNS).forEach { turn ->
                val role = turn.role.lowercase()
                if (role == "user" || role == "assistant") {
                    put(
                        JSONObject()
                            .put("role", role)
                            .put("content", turn.text.take(MAX_CONTENT_CHARS))
                    )
                }
            }
            put(
                JSONObject()
                    .put("role", "user")
                    .put("content", question.take(MAX_CONTENT_CHARS))
            )
        }

        return JSONObject()
            .put("model", MODEL_NAME)
            .put("temperature", 0.2)
            .put("messages", messages)
    }

    private fun buildResponsesRequestJson(
        question: String,
        contextSummary: String,
        history: List<AiChatTurn>,
        attachments: List<AiChatAttachmentRequest>
    ): JSONObject {
        val input = JSONArray().apply {
            put(
                buildResponsesMessage(
                    role = "system",
                    textItems = listOf(
                        "You are an assistant for AAPS Predictive Copilot. " +
                            "Use only provided telemetry and analysis context. " +
                            "Be concise, practical, and safety-aware. " +
                            "Do not prescribe medication doses. " +
                            "If data is insufficient, say exactly what is missing."
                    )
                )
            )
            put(
                buildResponsesMessage(
                    role = "system",
                    textItems = listOf("Data context:\n$contextSummary")
                )
            )
            history.takeLast(MAX_HISTORY_TURNS).forEach { turn ->
                val role = turn.role.lowercase(Locale.US)
                if (role == "user" || role == "assistant") {
                    put(
                        buildResponsesMessage(
                            role = role,
                            textItems = listOf(turn.text.take(MAX_CONTENT_CHARS))
                        )
                    )
                }
            }
            val attachmentContent = buildAttachmentContent(attachments)
            put(
                buildResponsesMessage(
                    role = "user",
                    textItems = listOf(question.take(MAX_CONTENT_CHARS)),
                    extraContent = attachmentContent
                )
            )
        }

        return JSONObject()
            .put("model", RESPONSES_MODEL_NAME)
            .put("reasoning", JSONObject().put("effort", "low"))
            .put("text", JSONObject().put("verbosity", "medium"))
            .put("input", input)
    }

    private fun buildOptimizerRequestJson(
        model: String,
        payload: DailyForecastReportPayload
    ): JSONObject {
        val compactPayload = JSONObject().apply {
            put("matchedSamples", payload.matchedSamples)
            put("forecastRows", payload.forecastRows)
            put(
                "horizons",
                JSONArray().apply {
                    payload.horizonStats
                        .sortedBy { it.horizonMinutes }
                        .forEach { stat ->
                            put(
                                JSONObject()
                                    .put("horizon", stat.horizonMinutes)
                                    .put("n", stat.sampleCount)
                                    .put("mae", stat.mae)
                                    .put("mardPct", stat.mardPct)
                                    .put("bias", stat.bias)
                                    .put("ciCoveragePct", stat.ciCoveragePct)
                                    .put("ciWidth", stat.ciMeanWidth)
                            )
                        }
                }
            )
            put(
                "topFactors",
                JSONArray().apply {
                    payload.factorContributions
                        .groupBy { it.horizonMinutes }
                        .toSortedMap()
                        .forEach { (horizon, rows) ->
                            val top = rows.maxByOrNull { it.contributionScore } ?: return@forEach
                            put(
                                JSONObject()
                                    .put("horizon", horizon)
                                    .put("factor", top.factor)
                                    .put("score", top.contributionScore)
                                    .put("corrAbsError", top.corrAbsError)
                                    .put("upliftPct", top.upliftPct)
                                    .put("n", top.sampleCount)
                            )
                        }
                }
            )
            put(
                "topPairs",
                JSONArray().apply {
                    payload.replayFactorPairs
                        .filter { it.bucketA == "HIGH" && it.bucketB == "HIGH" }
                        .sortedByDescending { it.mae }
                        .take(6)
                        .forEach { row ->
                            put(
                                JSONObject()
                                    .put("horizon", row.horizonMinutes)
                                    .put("pair", "${row.factorA}x${row.factorB}")
                                    .put("mae", row.mae)
                                    .put("mardPct", row.mardPct)
                                    .put("n", row.sampleCount)
                            )
                        }
                }
            )
            put(
                "recommendations",
                JSONArray().apply {
                    payload.recommendations.take(6).forEach { put(it) }
                }
            )
        }

        val userPrompt = buildString {
            appendLine("Optimize daily forecast calibration for 5/30/60 min horizons.")
            appendLine("Goal: reduce MAE/MARD on 30m/60m without increasing hypo risk.")
            appendLine("Only output bounded calibration scales; no dosing or therapy commands.")
            appendLine("Use conservative changes. Prefer no-change when confidence is low.")
            appendLine("Compact report payload:")
            appendLine(compactPayload.toString())
        }

        return JSONObject()
            .put("model", model)
            .put("background", true)
            .put("input", JSONArray().apply {
                put(
                    JSONObject()
                        .put("role", "system")
                        .put(
                            "content",
                            "You tune forecast-calibration coefficients only. " +
                                "Never suggest medication doses. " +
                                "Return strict JSON matching schema."
                        )
                )
                put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", userPrompt.take(MAX_OPTIMIZER_PROMPT_CHARS))
                )
            })
            .put(
                "text",
                JSONObject().put(
                    "format",
                    JSONObject()
                        .put("type", "json_schema")
                        .put("name", "forecast_optimizer_v1")
                        .put("strict", true)
                        .put("schema", optimizerSchemaJson())
                )
            )
    }

    private fun parseAssistantContent(responseJson: String): String {
        val root = JSONObject(responseJson)
        val choices = root.optJSONArray("choices") ?: return ""
        val firstChoice = choices.optJSONObject(0) ?: return ""
        val message = firstChoice.optJSONObject("message") ?: return ""
        val contentRaw = message.opt("content") ?: return ""
        return when (contentRaw) {
            is String -> contentRaw.trim()
            is JSONArray -> buildString {
                for (i in 0 until contentRaw.length()) {
                    val item = contentRaw.opt(i)
                    when (item) {
                        is JSONObject -> {
                            val type = item.optString("type")
                            if (type == "text") {
                                append(item.optString("text"))
                                append('\n')
                            }
                        }
                        is String -> {
                            append(item)
                            append('\n')
                        }
                    }
                }
            }.trim()
            else -> contentRaw.toString().trim()
        }
    }

    private suspend fun fetchModelIds(
        url: String,
        apiKey: String
    ): List<String> {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("models endpoint failed ${response.code}: ${body.take(240)}")
                }
                val root = JSONObject(body)
                val data = root.optJSONArray("data") ?: return@use emptyList()
                buildList {
                    for (i in 0 until data.length()) {
                        val id = data.optJSONObject(i)?.optString("id").orEmpty().trim()
                        if (id.isNotEmpty()) add(id)
                    }
                }
            }
        }.getOrElse { error ->
            auditLogger.warn(
                "ai_daily_optimizer_model_list_failed",
                mapOf("error" to (error.message ?: "unknown"))
            )
            emptyList()
        }
    }

    private fun postJson(
        url: String,
        apiKey: String,
        body: JSONObject,
        client: OkHttpClient = httpClient
    ): JSONObject {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("AI API error ${response.code}: ${responseBody.take(320)}")
            }
            JSONObject(responseBody)
        }
    }

    private fun getJson(
        url: String,
        apiKey: String,
        client: OkHttpClient = httpClient
    ): JSONObject {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        return client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("AI API polling error ${response.code}: ${responseBody.take(320)}")
            }
            JSONObject(responseBody)
        }
    }

    private suspend fun pollResponseIfNeeded(
        baseUrl: String,
        apiKey: String,
        initial: JSONObject,
        responseId: String?,
        client: OkHttpClient = httpClient
    ): JSONObject {
        if (responseId.isNullOrBlank()) return initial
        var current = initial
        var status = current.optString("status").lowercase(Locale.US)
        var attempts = 0
        while (
            status in OPTIMIZER_PENDING_STATUSES &&
            attempts < MAX_OPTIMIZER_POLL_ATTEMPTS
        ) {
            delay(OPTIMIZER_POLL_DELAY_MS)
            current = getJson(
                url = "${baseUrl}responses/$responseId",
                apiKey = apiKey,
                client = client
            )
            status = current.optString("status").lowercase(Locale.US)
            attempts += 1
        }
        if (status in OPTIMIZER_FAILED_STATUSES) {
            error("OpenAI daily optimizer failed with status=$status")
        }
        return current
    }

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().ifEmpty { "https://api.openai.com/v1/" }
        val withSlash = if (trimmed.endsWith('/')) trimmed else "$trimmed/"
        return if (withSlash.endsWith("v1/")) withSlash else "${withSlash}v1/"
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MODEL_NAME = "gpt-4.1-mini"
        private const val RESPONSES_MODEL_NAME = "gpt-4.1-mini"
        private const val TRANSCRIBE_MODEL_NAME = "gpt-4o-mini-transcribe"
        private const val TTS_MODEL_NAME = "gpt-4o-mini-tts"
        private const val TTS_VOICE_NAME = "alloy"
        private const val OPTIMIZER_FALLBACK_MODEL = "gpt-4.1-mini"
        private const val MAX_HISTORY_TURNS = 8
        private const val MAX_CONTENT_CHARS = 4_000
        private const val MAX_ATTACHMENT_TEXT_CHARS = 12_000
        private const val MAX_ATTACHMENT_IMAGE_BYTES = 4 * 1024 * 1024
        private const val MAX_TTS_INPUT_CHARS = 1_600
        private const val MAX_TRANSCRIBE_PROMPT_CHARS = 400
        private const val MAX_OPTIMIZER_PROMPT_CHARS = 24_000
        private const val MAX_OPTIMIZER_POLL_ATTEMPTS = 12
        private const val OPTIMIZER_POLL_DELAY_MS = 3_000L
        private val OPTIMIZER_PENDING_STATUSES = setOf(
            "queued",
            "in_progress",
            "running",
            "pending"
        )
        private val OPTIMIZER_FAILED_STATUSES = setOf(
            "failed",
            "cancelled",
            "expired"
        )
        private val OPTIMIZER_MODEL_PREFERENCES = listOf(
            "gpt-5.2-pro",
            "gpt-5.2 pro",
            "gpt-5-pro",
            "gpt-5.2",
            "gpt-5",
            "gpt-4.1"
        )
        internal fun resolvePreferredOptimizerModel(availableModelIds: List<String>): String {
            if (availableModelIds.isEmpty()) return OPTIMIZER_FALLBACK_MODEL
            OPTIMIZER_MODEL_PREFERENCES.forEach { preferred ->
                availableModelIds.firstOrNull { it.equals(preferred, ignoreCase = true) }?.let { return it }
            }
            availableModelIds.firstOrNull {
                val normalized = it.lowercase(Locale.US)
                normalized.startsWith("gpt-5") && normalized.contains("pro")
            }?.let { return it }
            availableModelIds.firstOrNull {
                it.lowercase(Locale.US).startsWith("gpt-5")
            }?.let { return it }
            availableModelIds.firstOrNull {
                it.lowercase(Locale.US).startsWith("gpt-4.1")
            }?.let { return it }
            return availableModelIds.firstOrNull().orEmpty().ifBlank { OPTIMIZER_FALLBACK_MODEL }
        }

        internal fun parseResponsesOutputTextStatic(rootJson: String): String {
            val root = runCatching { JsonParser.parseString(rootJson).asJsonObject }.getOrNull() ?: return ""
            val direct = root.get("output_text")?.asString?.trim().orEmpty()
            if (direct.isNotBlank()) return direct
            val candidates = linkedSetOf<String>()
            collectStructuredOutputCandidates(root, candidates)
            candidates.firstOrNull(::looksLikeOptimizerJson)?.let { return it.trim() }
            candidates.firstOrNull { candidate ->
                val trimmed = candidate.trim()
                trimmed.startsWith("{") || trimmed.startsWith("[")
            }?.let { return it.trim() }
            return candidates.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        }

        internal fun parseTranscriptionTextStatic(rootJson: String): String {
            val root = runCatching { JsonParser.parseString(rootJson).asJsonObject }.getOrNull() ?: return ""
            return root.get("text")?.asString?.trim().orEmpty()
        }

        internal fun extractTextAttachmentPreviewStatic(
            file: File,
            mimeType: String?
        ): String? {
            val normalizedMime = mimeType.orEmpty().lowercase(Locale.US)
            val normalizedName = file.name.lowercase(Locale.US)
            val textLike = normalizedMime.startsWith("text/") ||
                normalizedMime.contains("json") ||
                normalizedMime.contains("xml") ||
                normalizedMime.contains("csv") ||
                normalizedMime.contains("yaml") ||
                normalizedName.endsWith(".txt") ||
                normalizedName.endsWith(".log") ||
                normalizedName.endsWith(".md") ||
                normalizedName.endsWith(".csv") ||
                normalizedName.endsWith(".json") ||
                normalizedName.endsWith(".xml") ||
                normalizedName.endsWith(".yaml") ||
                normalizedName.endsWith(".yml")
            if (!textLike) return null
            return runCatching {
                file.readText(StandardCharsets.UTF_8)
                    .replace("\u0000", "")
                    .trim()
                    .take(MAX_ATTACHMENT_TEXT_CHARS)
            }.getOrNull()
        }

        private fun buildResponsesMessage(
            role: String,
            textItems: List<String>,
            extraContent: JSONArray = JSONArray()
        ): JSONObject {
            val content = JSONArray()
            textItems.forEach { text ->
                if (text.isNotBlank()) {
                    content.put(
                        JSONObject()
                            .put("type", "input_text")
                            .put("text", text)
                    )
                }
            }
            for (i in 0 until extraContent.length()) {
                content.put(extraContent.get(i))
            }
            return JSONObject()
                .put("role", role)
                .put("content", content)
        }

        private fun buildAttachmentContent(
            attachments: List<AiChatAttachmentRequest>
        ): JSONArray {
            val content = JSONArray()
            attachments.forEach { attachment ->
                val file = File(attachment.localPath)
                if (!file.exists()) return@forEach
                when (attachment.kind) {
                    AiChatAttachmentRequest.Kind.IMAGE -> {
                        val bytes = file.readBytes()
                        require(bytes.size <= MAX_ATTACHMENT_IMAGE_BYTES) {
                            "Attached image ${attachment.name} is too large"
                        }
                        val mime = attachment.mimeType?.takeIf { it.isNotBlank() } ?: "image/jpeg"
                        val dataUrl = "data:$mime;base64," +
                            Base64.getEncoder().encodeToString(bytes)
                        content.put(
                            JSONObject()
                                .put("type", "input_image")
                                .put("image_url", dataUrl)
                        )
                        content.put(
                            JSONObject()
                                .put("type", "input_text")
                                .put("text", "Attached image: ${attachment.name}")
                        )
                    }
                    AiChatAttachmentRequest.Kind.FILE -> {
                        val preview = extractTextAttachmentPreviewStatic(
                            file = file,
                            mimeType = attachment.mimeType
                        )
                        if (preview.isNullOrBlank()) {
                            content.put(
                                JSONObject()
                                    .put("type", "input_text")
                                    .put(
                                        "text",
                                        "Attached file metadata only: ${attachment.name} " +
                                            "(mime=${attachment.mimeType ?: "unknown"}, size=${file.length()} bytes). " +
                                            "Binary content preview is unavailable on-device."
                                    )
                            )
                        } else {
                            content.put(
                                JSONObject()
                                    .put("type", "input_text")
                                    .put(
                                        "text",
                                        "Attached file ${attachment.name}:\n$preview"
                                    )
                            )
                        }
                    }
                }
            }
            return content
        }

        internal fun parseOptimizerOutputStatic(outputJson: String): AiForecastOptimizationResult {
            val root = runCatching { JsonParser.parseString(outputJson).asJsonObject }.getOrElse {
                error("Optimizer output is not valid JSON: ${it.message}")
            }
            val calibration = root.getAsJsonObject("calibration") ?: JsonObject()
            val status = root.get("status")?.asString?.uppercase(Locale.US).orEmpty().ifBlank { "NO_CHANGE" }
            val confidence = root.get("confidence")?.asDouble?.coerceIn(0.0, 1.0) ?: 0.0
            val focusRaw = root.get("focus_horizon_min")?.asInt ?: 0
            val focusHorizonMinutes = focusRaw.takeIf { it in setOf(5, 30, 60) }
            val reason = root.get("reason")?.asString.orEmpty().ifBlank { "No reason provided" }
            val notes = root.getAsJsonArray("notes")
                ?.let { arr -> parseStringArray(arr) }
                ?: emptyList()

            fun scale(name: String, defaultValue: Double, min: Double, max: Double): Double {
                return calibration.get(name)?.asDouble?.coerceIn(min, max) ?: defaultValue
            }

            return AiForecastOptimizationResult(
                model = "",
                status = if (status == "APPLY") "APPLY" else "NO_CHANGE",
                confidence = confidence,
                focusHorizonMinutes = focusHorizonMinutes,
                reason = reason,
                notes = notes,
                gainScale5m = scale("gain_scale_5m", 1.0, 0.80, 1.50),
                gainScale30m = scale("gain_scale_30m", 1.0, 0.80, 1.50),
                gainScale60m = scale("gain_scale_60m", 1.0, 0.80, 1.50),
                maxUpScale5m = scale("max_up_scale_5m", 1.0, 0.80, 1.80),
                maxUpScale30m = scale("max_up_scale_30m", 1.0, 0.80, 1.80),
                maxUpScale60m = scale("max_up_scale_60m", 1.0, 0.80, 1.80),
                maxDownScale5m = scale("max_down_scale_5m", 1.0, 0.80, 1.50),
                maxDownScale30m = scale("max_down_scale_30m", 1.0, 0.80, 1.50),
                maxDownScale60m = scale("max_down_scale_60m", 1.0, 0.80, 1.50),
                responseId = null,
                responseStatus = null,
                outputJson = outputJson
            )
        }

        private fun parseStringArray(array: JsonArray): List<String> {
            return buildList {
                for (i in 0 until array.size()) {
                    val text = array.get(i)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                    if (text.isNotBlank()) add(text)
                }
            }
        }

        private fun parseContentTextChunks(content: JsonArray): List<String> {
            return buildList {
                for (j in 0 until content.size()) {
                    val item = content.get(j)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val textPrimitive = item.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                    if (textPrimitive.isNotBlank()) {
                        add(textPrimitive)
                    }
                    val textElement = item.get("text")
                    if (textElement != null && textElement.isJsonObject) {
                        val textObjValue = textElement.asJsonObject
                            .get("value")
                            ?.takeIf { it.isJsonPrimitive }
                            ?.asString
                            ?.trim()
                            .orEmpty()
                        if (textObjValue.isNotBlank()) {
                            add(textObjValue)
                        }
                    }
                }
            }
        }

        private fun collectStructuredOutputCandidates(
            node: com.google.gson.JsonElement?,
            out: MutableSet<String>
        ) {
            when {
                node == null || node.isJsonNull -> return
                node.isJsonPrimitive -> {
                    val text = runCatching { node.asString }.getOrNull()?.trim().orEmpty()
                    if (text.isNotBlank()) {
                        out += text
                    }
                }
                node.isJsonArray -> {
                    val array = node.asJsonArray
                    for (i in 0 until array.size()) {
                        collectStructuredOutputCandidates(array.get(i), out)
                    }
                }
                node.isJsonObject -> {
                    val obj = node.asJsonObject
                    if (looksLikeOptimizerObject(obj)) {
                        out += obj.toString()
                    }
                    obj.get("output_text")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(out::add)
                    obj.get("text")?.let { textNode ->
                        when {
                            textNode.isJsonPrimitive -> {
                                textNode.asString.trim()
                                    .takeIf { it.isNotBlank() }
                                    ?.let(out::add)
                            }
                            textNode.isJsonObject -> {
                                textNode.asJsonObject
                                    .get("value")
                                    ?.takeIf { it.isJsonPrimitive }
                                    ?.asString
                                    ?.trim()
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(out::add)
                            }
                        }
                    }
                    obj.get("parsed")
                        ?.takeIf { it.isJsonObject || it.isJsonArray }
                        ?.toString()
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(out::add)
                    obj.get("json")
                        ?.takeIf { it.isJsonObject || it.isJsonArray }
                        ?.toString()
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(out::add)
                    obj.get("arguments")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(out::add)
                    obj.entrySet().forEach { (_, value) ->
                        collectStructuredOutputCandidates(value, out)
                    }
                }
            }
        }

        private fun looksLikeOptimizerJson(text: String): Boolean {
            val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return false
            return looksLikeOptimizerObject(obj)
        }

        private fun looksLikeOptimizerObject(obj: JsonObject): Boolean {
            return obj.has("status") &&
                obj.has("confidence") &&
                obj.has("reason") &&
                obj.has("calibration")
        }

        private fun optimizerSchemaJson(): JSONObject {
            val calibrationProps = JSONObject()
                .put("gain_scale_5m", JSONObject().put("type", "number"))
                .put("gain_scale_30m", JSONObject().put("type", "number"))
                .put("gain_scale_60m", JSONObject().put("type", "number"))
                .put("max_up_scale_5m", JSONObject().put("type", "number"))
                .put("max_up_scale_30m", JSONObject().put("type", "number"))
                .put("max_up_scale_60m", JSONObject().put("type", "number"))
                .put("max_down_scale_5m", JSONObject().put("type", "number"))
                .put("max_down_scale_30m", JSONObject().put("type", "number"))
                .put("max_down_scale_60m", JSONObject().put("type", "number"))
            val calibrationRequired = JSONArray().apply {
                put("gain_scale_5m")
                put("gain_scale_30m")
                put("gain_scale_60m")
                put("max_up_scale_5m")
                put("max_up_scale_30m")
                put("max_up_scale_60m")
                put("max_down_scale_5m")
                put("max_down_scale_30m")
                put("max_down_scale_60m")
            }
            return JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "status",
                            JSONObject()
                                .put("type", "string")
                                .put("enum", JSONArray().put("NO_CHANGE").put("APPLY"))
                        )
                        .put("confidence", JSONObject().put("type", "number"))
                        .put("reason", JSONObject().put("type", "string"))
                        .put(
                            "focus_horizon_min",
                            JSONObject()
                                .put("type", "integer")
                                .put("enum", JSONArray().put(0).put(5).put(30).put(60))
                        )
                        .put(
                            "notes",
                            JSONObject()
                                .put("type", "array")
                                .put("items", JSONObject().put("type", "string"))
                        )
                        .put(
                            "calibration",
                            JSONObject()
                                .put("type", "object")
                                .put("additionalProperties", false)
                                .put("properties", calibrationProps)
                                .put("required", calibrationRequired)
                        )
                )
                .put(
                    "required",
                    JSONArray()
                        .put("status")
                        .put("confidence")
                        .put("reason")
                        .put("focus_horizon_min")
                        .put("notes")
                        .put("calibration")
                )
        }
    }
}
