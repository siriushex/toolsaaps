package io.aaps.copilot.config

import java.net.URI

fun isOpenAiApiEndpoint(url: String?): Boolean {
    val raw = url?.trim().orEmpty()
    if (raw.isBlank()) return false
    val parsed = runCatching { URI(raw) }.getOrNull() ?: return false
    val host = parsed.host?.lowercase().orEmpty()
    if (host.isBlank()) return false
    return host == "api.openai.com" || host.endsWith(".openai.com")
}

fun isCopilotCloudBackendEndpoint(url: String?): Boolean {
    val raw = url?.trim().orEmpty()
    if (raw.isBlank()) return false
    return !isOpenAiApiEndpoint(raw)
}
