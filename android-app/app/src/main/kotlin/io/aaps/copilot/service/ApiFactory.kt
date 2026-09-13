package io.aaps.copilot.service

import com.google.gson.GsonBuilder
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.config.resolvedNightscoutUrl
import io.aaps.copilot.data.remote.cloud.CopilotCloudApi
import io.aaps.copilot.data.remote.nightscout.NightscoutApi
import io.aaps.copilot.data.remote.nightscout.NightscoutAuthInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.tls.HandshakeCertificates
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ApiFactory internal constructor(
    private val localIdentityProvider: (() -> LocalNightscoutClientIdentity)? = null
) {

    fun nightscoutApi(settings: AppSettings): NightscoutApi {
        val resolved = settings.resolvedNightscoutUrl().ifBlank { settings.nightscoutUrl }
        return nightscoutApi(
            baseUrl = resolved,
            settings = settings
        )
    }

    fun nightscoutApi(baseUrl: String, settings: AppSettings): NightscoutApi {
        val ownEndpoint = isOwnedLocalNightscoutEndpoint(baseUrl, settings)
        val identity = if (ownEndpoint) {
            localIdentityProvider?.invoke() ?: throw IOException("Local Nightscout identity unavailable")
        } else null
        return createNightscoutApi(baseUrl, identity?.apiSecretSha1 ?: settings.apiSecret, identity)
    }

    fun nightscoutApi(baseUrl: String, apiSecret: String): NightscoutApi =
        createNightscoutApi(baseUrl, apiSecret, null)

    private fun createNightscoutApi(
        baseUrl: String,
        apiSecret: String,
        localIdentity: LocalNightscoutClientIdentity?
    ): NightscoutApi {
        require(baseUrl.isNotBlank()) { "Nightscout endpoint is not configured" }
        val base = normalizeBaseUrl(baseUrl)
        val clientBuilder = baseClientBuilder()
            .connectTimeout(NIGHTSCOUT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NIGHTSCOUT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(NIGHTSCOUT_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(NIGHTSCOUT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(NightscoutAuthInterceptor { apiSecret })
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header(COPILOT_CLIENT_HEADER, COPILOT_CLIENT_VALUE)
                    .build()
                chain.proceed(request)
            }
        if (localIdentity != null) {
            val certificates = HandshakeCertificates.Builder()
                .addTrustedCertificate(localIdentity.caCertificate).build()
            clientBuilder.sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
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

    private companion object {
        private const val COPILOT_CLIENT_HEADER = "X-AAPS-Copilot-Client"
        private const val COPILOT_CLIENT_VALUE = "io.aaps.predictivecopilot"
        private const val NIGHTSCOUT_CONNECT_TIMEOUT_SECONDS = 5L
        private const val NIGHTSCOUT_READ_TIMEOUT_SECONDS = 8L
        private const val NIGHTSCOUT_WRITE_TIMEOUT_SECONDS = 8L
        private const val NIGHTSCOUT_CALL_TIMEOUT_SECONDS = 10L
    }
}
