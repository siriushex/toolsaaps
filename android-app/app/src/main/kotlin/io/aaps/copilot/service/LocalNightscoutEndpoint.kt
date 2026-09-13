package io.aaps.copilot.service

import io.aaps.copilot.config.AppSettings
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Authentication and transport-lease routing must agree on the owned endpoint.
internal fun isOwnedLocalNightscoutEndpoint(baseUrl: String, settings: AppSettings): Boolean {
    val url = baseUrl.trim().toHttpUrlOrNull() ?: return false
    return settings.localNightscoutEnabled && url.scheme == "https" &&
        url.host in setOf("127.0.0.1", "localhost") && url.port == settings.localNightscoutPort &&
        url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" &&
        url.query == null && url.fragment == null
}
