package io.aaps.copilot.telegram

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class TelegramIssue { INVALID_TOKEN, NETWORK, REJECTED, RESPONSE, PAIRING_EXPIRED, NO_MATCH, STORAGE, NOT_READY }
class TelegramFailure(val issue: TelegramIssue) : Exception(issue.name)
data class TelegramBotIdentity(val id: Long, val username: String)
data class TelegramPairCandidate(val chatId: Long, val username: String)

interface TelegramApi {
    suspend fun identity(token: String): TelegramBotIdentity
    suspend fun candidates(token: String, code: String, since: Long): List<TelegramPairCandidate>
    suspend fun sendText(token: String, chatId: Long, text: String)
}

class TelegramHttpApi internal constructor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS).connectTimeout(8, TimeUnit.SECONDS).build()
) : TelegramApi {
    override suspend fun identity(token: String): TelegramBotIdentity = sanitized {
        val result = request(token, "getMe", JsonObject()).asJsonObject
        if (result["is_bot"]?.asBoolean != true) throw TelegramFailure(TelegramIssue.RESPONSE)
        val id = result["id"]?.asLong ?: throw TelegramFailure(TelegramIssue.RESPONSE)
        val username = result["username"]?.asString.orEmpty()
        if (id <= 0 || !USERNAME.matches(username)) throw TelegramFailure(TelegramIssue.RESPONSE)
        TelegramBotIdentity(id, username)
    }

    override suspend fun candidates(token: String, code: String, since: Long): List<TelegramPairCandidate> = sanitized {
        val args = JsonObject().apply {
            addProperty("timeout", 0)
            addProperty("limit", 100)
            add("allowed_updates", JsonParser.parseString("[\"message\"]"))
        }
        val result = request(token, "getUpdates", args)
        if (!result.isJsonArray || result.asJsonArray.size() > 100) throw TelegramFailure(TelegramIssue.RESPONSE)
        result.asJsonArray.mapNotNull { row ->
            val message = row.asJsonObject["message"]?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return@mapNotNull null
            val chat = message["chat"]?.asJsonObject ?: return@mapNotNull null
            val sender = message["from"]?.asJsonObject ?: return@mapNotNull null
            if (chat["type"]?.asString != "private" || sender["is_bot"]?.asBoolean != false ||
                message["text"]?.asString != "/start $code" ||
                (message["date"]?.asLong ?: 0) < since / 1_000L) return@mapNotNull null
            val id = chat["id"]?.asLong ?: return@mapNotNull null
            val username = sender["username"]?.asString ?: return@mapNotNull null
            if (id <= 0 || sender["id"]?.asLong != id || !USERNAME.matches(username)) return@mapNotNull null
            TelegramPairCandidate(id, username)
        }.distinctBy { it.chatId }
    }

    override suspend fun sendText(token: String, chatId: Long, text: String): Unit = sanitized {
        require(chatId > 0 && text.isNotBlank() && text.length <= 3_500)
        val result = request(token, "sendMessage", JsonObject().apply {
            addProperty("chat_id", chatId)
            addProperty("text", text)
            addProperty("protect_content", true)
            add("link_preview_options", JsonObject().apply { addProperty("is_disabled", true) })
        }).asJsonObject
        if (result["chat"]?.asJsonObject?.get("id")?.asLong != chatId ||
            (result["message_id"]?.asLong ?: 0) <= 0) throw TelegramFailure(TelegramIssue.RESPONSE)
    }

    private suspend fun <T> sanitized(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: TelegramFailure) {
        throw e
    } catch (_: Exception) {
        throw TelegramFailure(TelegramIssue.RESPONSE)
    }

    private suspend fun request(token: String, method: String, payload: JsonObject): com.google.gson.JsonElement {
        if (!TOKEN.matches(token)) throw TelegramFailure(TelegramIssue.INVALID_TOKEN)
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$token/$method")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        // Token-bearing URLs and Telegram descriptions never leave this transport.
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(TelegramFailure(TelegramIssue.NETWORK))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!response.isSuccessful) throw TelegramFailure(
                                if (response.code == 401) TelegramIssue.INVALID_TOKEN else TelegramIssue.REJECTED
                            )
                            val body = response.body ?: throw TelegramFailure(TelegramIssue.RESPONSE)
                            val source = body.source()
                            if (source.request(MAX_RESPONSE_BYTES + 1)) throw TelegramFailure(TelegramIssue.RESPONSE)
                            val root = JsonParser.parseString(source.readUtf8()).asJsonObject
                            if (root["ok"]?.asBoolean != true) throw TelegramFailure(TelegramIssue.REJECTED)
                            val result = root["result"] ?: throw TelegramFailure(TelegramIssue.RESPONSE)
                            if (!continuation.isCancelled) continuation.resume(result)
                        } catch (e: Exception) {
                            if (!continuation.isCancelled) continuation.resumeWithException(
                                e as? TelegramFailure ?: TelegramFailure(TelegramIssue.RESPONSE)
                            )
                        }
                    }
                }
            })
        }
    }

    companion object {
        internal val TOKEN = Regex("[0-9]{5,20}:[A-Za-z0-9_-]{30,128}")
        internal val USERNAME = Regex("[A-Za-z][A-Za-z0-9_]{4,31}")
        private const val MAX_RESPONSE_BYTES = 256L * 1_024
    }
}
