package io.aaps.copilot.data.remote.nightscout

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import io.aaps.copilot.service.ApiFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class NightscoutTreatmentSerializationTest {

    @Test
    fun fractionalCarbCorrectionIsPostedAsExactJsonNumber() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"_id":"remote-1","carbs":0.1}""")
                    .addHeader("Content-Type", "application/json")
            )

            val response = ApiFactory()
                .nightscoutApi(server.url("/").toString(), "")
                .postTreatment(
                    NightscoutTreatmentRequest(
                        createdAt = "2026-07-17T00:00:00Z",
                        date = 1_784_246_400_000L,
                        mills = 1_784_246_400_000L,
                        eventType = "Carb Correction",
                        carbs = 0.1,
                        notes = "UAM_ENGINE ver=2 id=episode seq=1",
                        reason = "uam_engine"
                    )
                )

            val recorded = requireNotNull(server.takeRequest())
            val body = JsonParser.parseString(recorded.body.readUtf8()).asJsonObject
            val carbs = body.getAsJsonPrimitive("carbs")

            assertThat(recorded.method).isEqualTo("POST")
            assertThat(recorded.requestUrl?.encodedPath).isEqualTo("/api/v1/treatments.json")
            assertThat(carbs.isNumber).isTrue()
            assertThat(carbs.asDouble).isEqualTo(0.1)
            assertThat(response.id).isEqualTo("remote-1")
        }
    }
}
