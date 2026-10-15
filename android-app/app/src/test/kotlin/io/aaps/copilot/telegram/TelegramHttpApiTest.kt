package io.aaps.copilot.telegram

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class TelegramHttpApiTest {
    private val token = "123456789:" + "a".repeat(35)
    private fun api(body: String, status: Int = 200) = TelegramHttpApi(
        OkHttpClient.Builder().addInterceptor { chain ->
            assertThat(chain.request().url.host).isEqualTo("api.telegram.org")
            assertThat(chain.request().url.isHttps).isTrue()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(body.toResponseBody()).build()
        }.build()
    )

    @Test fun verifiesBotIdentity() = runTest {
        val result = api("""{"ok":true,"result":{"id":123456789,"is_bot":true,"username":"TestCopilotBot"}}""")
            .identity(token)
        assertThat(result.username).isEqualTo("TestCopilotBot")
    }

    @Test fun rejectsMismatchedChallengeGroupAndDifferentSender() = runTest {
        val body = """{"ok":true,"result":[
            {"message":{"date":1000,"text":"/start correct","chat":{"id":42,"type":"private"},"from":{"id":42,"is_bot":false,"username":"trusted_user"}}},
            {"message":{"date":1000,"text":"/start wrong","chat":{"id":43,"type":"private"},"from":{"id":43,"is_bot":false,"username":"trusted_user"}}},
            {"message":{"date":1000,"text":"/start correct","chat":{"id":44,"type":"group"},"from":{"id":44,"is_bot":false,"username":"trusted_user"}}},
            {"message":{"date":1000,"text":"/start correct","chat":{"id":45,"type":"private"},"from":{"id":46,"is_bot":false,"username":"trusted_user"}}},
            {"message":{"date":999,"text":"/start correct","chat":{"id":47,"type":"private"},"from":{"id":47,"is_bot":false,"username":"trusted_user"}}}
        ]}"""
        assertThat(api(body).candidates(token, "correct", 1_000_000L))
            .containsExactly(TelegramPairCandidate(42L, "trusted_user"))
    }

    @Test fun remoteBodyAndCredentialNeverAppearInFailure() = runTest {
        try {
            api("private response $token", 400).sendText(token, 42, "test")
            throw AssertionError("Expected rejection")
        } catch (e: TelegramFailure) {
            assertThat(e.toString()).doesNotContain(token)
            assertThat(e.toString()).doesNotContain("private response")
            assertThat(e.cause).isNull()
        }
    }

    @Test fun oversizedResponseIsRejected() = runTest {
        try {
            api(" ".repeat(256 * 1_024 + 1)).identity(token)
            throw AssertionError("Expected bounded response")
        } catch (e: TelegramFailure) {
            assertThat(e.issue).isEqualTo(TelegramIssue.RESPONSE)
        }
    }

    @Test fun malformedResultCannotLeakItsContentThroughParserErrors() = runTest {
        try {
            api("""{"ok":true,"result":"private-data"}""").identity(token)
            throw AssertionError("Malformed result expected")
        } catch (e: TelegramFailure) {
            assertThat(e.toString()).doesNotContain("private-data")
            assertThat(e.cause).isNull()
        }
    }

    @Test fun messageTextIsPlainAndUsesNumericDestination() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer()
            chain.request().body!!.writeTo(buffer)
            val body = com.google.gson.JsonParser.parseString(buffer.readUtf8()).asJsonObject
            assertThat(body["chat_id"].asLong).isEqualTo(42L)
            assertThat(body["text"].asString).isEqualTo("<b>not markup</b>")
            assertThat(body.has("parse_mode")).isFalse()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("ok").body("""{"ok":true,"result":{"message_id":1,"chat":{"id":42}}}""".toResponseBody()).build()
        }.build()
        TelegramHttpApi(client).sendText(token, 42L, "<b>not markup</b>")
    }
}
