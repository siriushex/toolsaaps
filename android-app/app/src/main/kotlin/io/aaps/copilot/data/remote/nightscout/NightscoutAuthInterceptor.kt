package io.aaps.copilot.data.remote.nightscout

import java.security.MessageDigest
import okhttp3.Interceptor
import okhttp3.Response

class NightscoutAuthInterceptor(
    private val apiSecretProvider: () -> String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val secret = apiSecretProvider().trim()
        val requestBuilder = chain.request().newBuilder()
        if (secret.isNotEmpty()) {
            requestBuilder.header("api-secret", normalizeSecret(secret))
        }
        return chain.proceed(requestBuilder.build())
    }

    private fun normalizeSecret(secret: String): String {
        val hexRegex = Regex("^[a-fA-F0-9]{40}$")
        if (secret.matches(hexRegex)) return secret.lowercase()
        val digest = MessageDigest.getInstance("SHA-1")
        val hashed = digest.digest(secret.toByteArray())
        return hashed.joinToString(separator = "") { "%02x".format(it) }
    }
}
