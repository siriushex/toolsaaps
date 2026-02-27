package io.aaps.copilot.service

import com.google.gson.GsonBuilder
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.config.resolvedNightscoutUrl
import io.aaps.copilot.data.remote.cloud.CopilotCloudApi
import io.aaps.copilot.data.remote.nightscout.NightscoutApi
import io.aaps.copilot.data.remote.nightscout.NightscoutAuthInterceptor
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ApiFactory {

    fun nightscoutApi(settings: AppSettings): NightscoutApi {
        val resolved = settings.resolvedNightscoutUrl().ifBlank { settings.nightscoutUrl }
        return nightscoutApi(
            baseUrl = resolved,
            apiSecret = settings.apiSecret
        )
    }

    fun nightscoutApi(baseUrl: String, apiSecret: String): NightscoutApi {
        val base = normalizeBaseUrl(baseUrl)
        val client = baseClientBuilder()
            .addInterceptor(NightscoutAuthInterceptor { apiSecret })
            .build()

        return Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
            .build()
            .create(NightscoutApi::class.java)
    }

    fun cloudApi(settings: AppSettings): CopilotCloudApi {
        val base = normalizeBaseUrl(settings.cloudBaseUrl)
        val client = baseClientBuilder().build()

        return Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
            .build()
            .create(CopilotCloudApi::class.java)
    }

    private fun baseClientBuilder(): OkHttpClient.Builder {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(logging)
    }

    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim().ifEmpty { "https://example.com/" }
        return if (trimmed.endsWith('/')) trimmed else "$trimmed/"
    }
}
