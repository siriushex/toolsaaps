package io.aaps.copilot.service

import com.google.gson.GsonBuilder
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.config.resolvedNightscoutUrl
import io.aaps.copilot.data.remote.cloud.CopilotCloudApi
import io.aaps.copilot.data.remote.nightscout.NightscoutApi
import io.aaps.copilot.data.remote.nightscout.NightscoutAuthInterceptor
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
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
        val clientBuilder = baseClientBuilder()
            .addInterceptor(NightscoutAuthInterceptor { apiSecret })
        if (isLoopbackHttps(base)) {
            configureLoopbackTls(clientBuilder)
        }
        val client = clientBuilder.build()

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

    private fun isLoopbackHttps(url: String): Boolean {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return false
        val host = parsed.host?.lowercase() ?: return false
        val loopback = host == "127.0.0.1" || host == "localhost"
        return loopback && parsed.scheme.equals("https", ignoreCase = true)
    }

    private fun configureLoopbackTls(builder: OkHttpClient.Builder) {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        builder.hostnameVerifier { hostname, _ ->
            hostname.equals("127.0.0.1") || hostname.equals("localhost", ignoreCase = true)
        }
    }
}
