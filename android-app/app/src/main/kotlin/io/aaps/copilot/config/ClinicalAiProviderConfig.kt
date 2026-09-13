package io.aaps.copilot.config

import io.aaps.copilot.util.ClinicalAiUnicodePolicy
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale

enum class ClinicalAiProviderId {
    OPENAI,
    ANTHROPIC,
    GEMINI,
    OPENAI_COMPATIBLE
}

enum class OpenAiCompatibleProtocol {
    RESPONSES,
    CHAT_COMPLETIONS
}

enum class ClinicalAiModelTier {
    HIGHEST,
    RECOMMENDED,
    ECONOMY
}

data class ClinicalAiModelPreset(
    val id: String,
    val label: String,
    val tier: ClinicalAiModelTier,
    val preview: Boolean = false
) {
    init {
        ClinicalAiModelIdPolicy.requireValid(id)
        require(label.isNotBlank()) { "Model label must not be blank" }
    }
}

sealed interface ClinicalAiModelCatalogEntry {
    data class Preset(val value: ClinicalAiModelPreset) : ClinicalAiModelCatalogEntry
    data object CustomModel : ClinicalAiModelCatalogEntry
}

object ClinicalAiModelCatalog {
    private val compatible: List<ClinicalAiModelPreset> = immutableCopy(emptyList())

    val openAi: List<ClinicalAiModelPreset> = immutableListOf(
        preset("gpt-5.6-sol", ClinicalAiModelTier.HIGHEST),
        preset("gpt-5.6-terra", ClinicalAiModelTier.RECOMMENDED),
        preset("gpt-5.6-luna", ClinicalAiModelTier.ECONOMY)
    )
    val anthropic: List<ClinicalAiModelPreset> = immutableListOf(
        preset("claude-opus-5", ClinicalAiModelTier.HIGHEST),
        preset("claude-sonnet-5", ClinicalAiModelTier.RECOMMENDED),
        preset("claude-haiku-4-5", ClinicalAiModelTier.ECONOMY)
    )
    val gemini: List<ClinicalAiModelPreset> = immutableListOf(
        preset("gemini-3.1-pro-preview", ClinicalAiModelTier.HIGHEST, preview = true),
        preset("gemini-3.6-flash", ClinicalAiModelTier.RECOMMENDED),
        preset("gemini-3.5-flash-lite", ClinicalAiModelTier.ECONOMY)
    )

    fun forProvider(providerId: ClinicalAiProviderId): List<ClinicalAiModelPreset> =
        when (providerId) {
            ClinicalAiProviderId.OPENAI -> openAi
            ClinicalAiProviderId.ANTHROPIC -> anthropic
            ClinicalAiProviderId.GEMINI -> gemini
            ClinicalAiProviderId.OPENAI_COMPATIBLE -> compatible
        }

    fun entriesFor(providerId: ClinicalAiProviderId): List<ClinicalAiModelCatalogEntry> =
        immutableCopy(
            forProvider(providerId).map(ClinicalAiModelCatalogEntry::Preset) +
                ClinicalAiModelCatalogEntry.CustomModel
        )

    fun defaultFor(providerId: ClinicalAiProviderId): ClinicalAiModelPreset {
        require(providerId != ClinicalAiProviderId.OPENAI_COMPATIBLE) {
            "Compatible providers require an explicit model ID"
        }
        return forProvider(providerId).single { it.tier == ClinicalAiModelTier.RECOMMENDED }
    }

    private fun preset(
        id: String,
        tier: ClinicalAiModelTier,
        preview: Boolean = false
    ) = ClinicalAiModelPreset(id = id, label = id, tier = tier, preview = preview)

    private fun <T> immutableListOf(vararg values: T): List<T> =
        immutableCopy(values.asList())

    private fun <T> immutableCopy(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))
}

object ClinicalAiModelIdPolicy {
    private const val MAX_CODE_POINTS = 128

    fun requireValid(raw: String): String {
        val codePointCount = raw.codePointCount(0, raw.length)
        require(codePointCount in 1..MAX_CODE_POINTS) {
            "Model ID must contain between 1 and $MAX_CODE_POINTS code points"
        }

        var offset = 0
        while (offset < raw.length) {
            val codePoint = raw.codePointAt(offset)
            require(isVisibleAssignedCodePoint(codePoint)) {
                "Model ID must contain only visible assigned Unicode characters"
            }
            offset += Character.charCount(codePoint)
        }
        return raw
    }

    private fun isVisibleAssignedCodePoint(codePoint: Int): Boolean {
        if (!Character.isValidCodePoint(codePoint)) return false
        if (codePoint in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) return false
        if (ClinicalAiUnicodePolicy.isDefaultIgnorable(codePoint)) return false
        if (isNoncharacter(codePoint)) return false
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) return false
        return when (Character.getType(codePoint).toByte()) {
            Character.CONTROL,
            Character.FORMAT,
            Character.PRIVATE_USE,
            Character.SURROGATE,
            Character.UNASSIGNED,
            Character.SPACE_SEPARATOR,
            Character.LINE_SEPARATOR,
            Character.PARAGRAPH_SEPARATOR -> false
            else -> true
        }
    }

    private fun isNoncharacter(codePoint: Int): Boolean =
        codePoint in 0xfdd0..0xfdef ||
            codePoint and 0xffff == 0xfffe ||
            codePoint and 0xffff == 0xffff
}

enum class ClinicalAiEndpointError {
    BLANK,
    MALFORMED,
    UNSUPPORTED_SCHEME,
    MISSING_HOST,
    CREDENTIALS_NOT_ALLOWED,
    QUERY_NOT_ALLOWED,
    FRAGMENT_NOT_ALLOWED,
    REMOTE_HTTP_NOT_ALLOWED,
    UNSAFE_PORT,
    UNSAFE_PATH
}

data class ClinicalAiEndpoint(
    val normalized: String,
    val scheme: String,
    val host: String,
    val port: Int?,
    val basePath: String
)

sealed interface ClinicalAiEndpointValidation {
    data class Valid(val endpoint: ClinicalAiEndpoint) : ClinicalAiEndpointValidation
    data class Invalid(val reason: ClinicalAiEndpointError) : ClinicalAiEndpointValidation
}

object ClinicalAiEndpointPolicy {
    private val exactLoopbackHosts = setOf("127.0.0.1", "localhost", "::1")
    private val pathLookalikes = setOf(
        0x2044,
        0x2215,
        0x29f8,
        0xff0f,
        0x2216,
        0x29f5,
        0xff3c,
        0x2024,
        0xfe52,
        0xff0e,
        0x3002,
        0xff61
    )
    private val unsafePorts = setOf(
        1, 7, 9, 11, 13, 15, 17, 19, 20, 21, 22, 23, 25, 37, 42, 43, 53, 69,
        77, 79, 87, 95, 101, 102, 103, 104, 109, 110, 111, 113, 115, 117, 119,
        123, 135, 137, 139, 143, 161, 179, 389, 427, 465, 512, 513, 514, 515,
        526, 530, 531, 532, 540, 548, 554, 556, 563, 587, 601, 636, 993, 995,
        2049, 3659, 4045, 6000, 6665, 6666, 6667, 6668, 6669, 6697
    )

    fun validate(raw: String): ClinicalAiEndpointValidation {
        if (raw.isBlank()) {
            return ClinicalAiEndpointValidation.Invalid(ClinicalAiEndpointError.BLANK)
        }
        if (containsForbiddenUnicode(raw)) {
            return ClinicalAiEndpointValidation.Invalid(ClinicalAiEndpointError.MALFORMED)
        }

        val uri = try {
            URI(raw)
        } catch (_: URISyntaxException) {
            return ClinicalAiEndpointValidation.Invalid(ClinicalAiEndpointError.MALFORMED)
        }
        if (uri.isOpaque) {
            return ClinicalAiEndpointValidation.Invalid(ClinicalAiEndpointError.MALFORMED)
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?: return ClinicalAiEndpointValidation.Invalid(ClinicalAiEndpointError.UNSUPPORTED_SCHEME)
        if (scheme != "https" && scheme != "http") {
            return ClinicalAiEndpointValidation.Invalid(ClinicalAiEndpointError.UNSUPPORTED_SCHEME)
        }
        if (uri.rawUserInfo != null) {
            return ClinicalAiEndpointValidation.Invalid(
                ClinicalAiEndpointError.CREDENTIALS_NOT_ALLOWED
            )
        }
        if (uri.rawQuery != null) {
            return ClinicalAiEndpointValidation.Invalid(ClinicalAiEndpointError.QUERY_NOT_ALLOWED)
        }
        if (uri.rawFragment != null) {
            return ClinicalAiEndpointValidation.Invalid(ClinicalAiEndpointError.FRAGMENT_NOT_ALLOWED)
        }

        val host = uri.host
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?: return ClinicalAiEndpointValidation.Invalid(ClinicalAiEndpointError.MISSING_HOST)
        if (scheme == "http" && host !in exactLoopbackHosts) {
            return ClinicalAiEndpointValidation.Invalid(
                ClinicalAiEndpointError.REMOTE_HTTP_NOT_ALLOWED
            )
        }

        val explicitPort = uri.port.takeIf { it >= 0 }
        if (uri.port > 65_535 || uri.port == 0 || explicitPort in unsafePorts) {
            return ClinicalAiEndpointValidation.Invalid(ClinicalAiEndpointError.UNSAFE_PORT)
        }

        val normalizedPath = canonicalizePath(uri.rawPath.orEmpty())
            ?: return ClinicalAiEndpointValidation.Invalid(ClinicalAiEndpointError.UNSAFE_PATH)

        val defaultPort = (scheme == "https" && explicitPort == 443) ||
            (scheme == "http" && explicitPort == 80)
        val normalizedPort = explicitPort?.takeUnless { defaultPort }
        val renderedHost = if (':' in host) "[$host]" else host
        val normalized = buildString {
            append(scheme)
            append("://")
            append(renderedHost)
            normalizedPort?.let {
                append(':')
                append(it)
            }
            append(normalizedPath)
        }
        return ClinicalAiEndpointValidation.Valid(
            ClinicalAiEndpoint(
                normalized = normalized,
                scheme = scheme,
                host = host,
                port = normalizedPort,
                basePath = normalizedPath
            )
        )
    }

    fun requireValid(raw: String): ClinicalAiEndpoint {
        return when (val result = validate(raw)) {
            is ClinicalAiEndpointValidation.Valid -> result.endpoint
            is ClinicalAiEndpointValidation.Invalid ->
                throw IllegalArgumentException("Invalid clinical AI endpoint: ${result.reason}")
        }
    }

    private fun containsForbiddenUnicode(raw: String): Boolean {
        var offset = 0
        while (offset < raw.length) {
            val codePoint = raw.codePointAt(offset)
            if (
                codePoint in pathLookalikes ||
                ClinicalAiUnicodePolicy.isDefaultIgnorable(codePoint) ||
                Character.isWhitespace(codePoint) ||
                Character.isSpaceChar(codePoint)
            ) {
                return true
            }
            when (Character.getType(codePoint).toByte()) {
                Character.CONTROL,
                Character.FORMAT,
                Character.SPACE_SEPARATOR,
                Character.LINE_SEPARATOR,
                Character.PARAGRAPH_SEPARATOR,
                Character.SURROGATE,
                Character.UNASSIGNED -> return true
            }
            offset += Character.charCount(codePoint)
        }
        return false
    }

    private fun canonicalizePath(rawPath: String): String? {
        if (rawPath.isEmpty() || rawPath == "/") return ""
        if ('\\' in rawPath || rawPath.startsWith("//") || "//" in rawPath) return null

        val canonical = StringBuilder(rawPath.length)
        val securityView = StringBuilder(rawPath.length)
        var index = 0
        while (index < rawPath.length) {
            val character = rawPath[index]
            if (character == '%') {
                val runStart = index
                val bytes = ArrayList<Byte>()
                while (index < rawPath.length && rawPath[index] == '%') {
                    if (index + 2 >= rawPath.length) return null
                    val high = rawPath[index + 1].digitToIntOrNull(16) ?: return null
                    val low = rawPath[index + 2].digitToIntOrNull(16) ?: return null
                    val value = (high shl 4) or low
                    if (value == 0x25 || value == 0x2f || value == 0x5c) return null
                    if (value <= 0x20 || value == 0x7f) return null
                    bytes += value.toByte()
                    index += 3
                }
                val decodedRun = decodeUtf8Strict(bytes.toByteArray()) ?: return null
                securityView.append(decodedRun)

                var byteIndex = 0
                var rawIndex = runStart
                while (byteIndex < bytes.size) {
                    val value = bytes[byteIndex].toInt() and 0xff
                    if (value < 0x80 && isAsciiUnreserved(value)) {
                        canonical.append(value.toChar())
                    } else {
                        canonical.append('%')
                        canonical.append(rawPath[rawIndex + 1].uppercaseChar())
                        canonical.append(rawPath[rawIndex + 2].uppercaseChar())
                    }
                    byteIndex += 1
                    rawIndex += 3
                }
            } else {
                val codePoint = rawPath.codePointAt(index)
                canonical.appendCodePoint(codePoint)
                securityView.appendCodePoint(codePoint)
                index += Character.charCount(codePoint)
            }
        }

        if (containsForbiddenUnicode(securityView.toString())) return null
        if ('\\' in securityView || "//" in securityView) return null
        if (securityView.split('/').any { it == "." || it == ".." }) return null

        val normalized = canonical.toString().trimEnd('/')
        if (normalized.isEmpty()) return ""
        if (normalized.startsWith("//") || "//" in normalized) return null
        return normalized
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String? {
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
    }

    private fun isAsciiUnreserved(value: Int): Boolean =
        value in 'a'.code..'z'.code ||
            value in 'A'.code..'Z'.code ||
            value in '0'.code..'9'.code ||
            value == '-'.code ||
            value == '.'.code ||
            value == '_'.code ||
            value == '~'.code
}

enum class ClinicalAiConfigInvalidReason {
    PARTIAL_CONFIGURATION,
    UNKNOWN_PROVIDER,
    UNKNOWN_PROTOCOL,
    INVALID_MODEL,
    INVALID_ENDPOINT,
    UNEXPECTED_ENDPOINT,
    UNEXPECTED_PROTOCOL,
    MISSING_ENDPOINT,
    MISSING_PROTOCOL
}

sealed interface ClinicalAiConfigState {
    data class UnconfiguredDefault(val config: ClinicalAiProviderConfig) : ClinicalAiConfigState
    data class Valid(val config: ClinicalAiProviderConfig) : ClinicalAiConfigState
    data class Invalid(val reason: ClinicalAiConfigInvalidReason) : ClinicalAiConfigState

    companion object {
        fun fromStored(
            providerId: String?,
            modelId: String?,
            endpoint: String?,
            compatibleProtocol: String?
        ): ClinicalAiConfigState {
            if (
                providerId == null &&
                modelId == null &&
                endpoint == null &&
                compatibleProtocol == null
            ) {
                return UnconfiguredDefault(ClinicalAiProviderConfig.defaultOpenAi())
            }
            if (providerId == null || modelId == null) {
                return Invalid(ClinicalAiConfigInvalidReason.PARTIAL_CONFIGURATION)
            }

            val parsedProvider = runCatching {
                ClinicalAiProviderId.valueOf(providerId)
            }.getOrElse {
                return Invalid(ClinicalAiConfigInvalidReason.UNKNOWN_PROVIDER)
            }
            if (runCatching { ClinicalAiModelIdPolicy.requireValid(modelId) }.isFailure) {
                return Invalid(ClinicalAiConfigInvalidReason.INVALID_MODEL)
            }

            if (parsedProvider != ClinicalAiProviderId.OPENAI_COMPATIBLE) {
                if (endpoint != null) {
                    return Invalid(ClinicalAiConfigInvalidReason.UNEXPECTED_ENDPOINT)
                }
                if (compatibleProtocol != null) {
                    return Invalid(ClinicalAiConfigInvalidReason.UNEXPECTED_PROTOCOL)
                }
                return Valid(ClinicalAiProviderConfig(parsedProvider, modelId))
            }

            if (endpoint == null) {
                return Invalid(ClinicalAiConfigInvalidReason.MISSING_ENDPOINT)
            }
            if (compatibleProtocol == null) {
                return Invalid(ClinicalAiConfigInvalidReason.MISSING_PROTOCOL)
            }
            val parsedProtocol = runCatching {
                OpenAiCompatibleProtocol.valueOf(compatibleProtocol)
            }.getOrElse {
                return Invalid(ClinicalAiConfigInvalidReason.UNKNOWN_PROTOCOL)
            }
            val normalizedEndpoint = when (val result = ClinicalAiEndpointPolicy.validate(endpoint)) {
                is ClinicalAiEndpointValidation.Valid -> result.endpoint.normalized
                is ClinicalAiEndpointValidation.Invalid ->
                    return Invalid(ClinicalAiConfigInvalidReason.INVALID_ENDPOINT)
            }
            return Valid(
                ClinicalAiProviderConfig(
                    providerId = parsedProvider,
                    modelId = modelId,
                    endpoint = normalizedEndpoint,
                    compatibleProtocol = parsedProtocol
                )
            )
        }
    }
}

data class ClinicalAiProviderConfig(
    val providerId: ClinicalAiProviderId,
    val modelId: String,
    val endpoint: String? = null,
    val compatibleProtocol: OpenAiCompatibleProtocol? = null
) {
    init {
        ClinicalAiModelIdPolicy.requireValid(modelId)
        when (providerId) {
            ClinicalAiProviderId.OPENAI_COMPATIBLE -> {
                requireNotNull(endpoint) { "Compatible provider endpoint is required" }
                requireNotNull(compatibleProtocol) { "Compatible provider protocol is required" }
                require(ClinicalAiEndpointPolicy.requireValid(endpoint).normalized == endpoint) {
                    "Compatible provider endpoint must be normalized"
                }
            }

            else -> {
                require(endpoint == null) { "Native providers do not accept a custom endpoint" }
                require(compatibleProtocol == null) {
                    "Native providers do not accept a compatible protocol"
                }
            }
        }
    }

    companion object {
        fun defaultOpenAi(): ClinicalAiProviderConfig = ClinicalAiProviderConfig(
            providerId = ClinicalAiProviderId.OPENAI,
            modelId = ClinicalAiModelCatalog.defaultFor(ClinicalAiProviderId.OPENAI).id
        )

        fun normalized(
            providerId: ClinicalAiProviderId,
            modelId: String,
            endpoint: String? = null,
            compatibleProtocol: OpenAiCompatibleProtocol? = null
        ): ClinicalAiProviderConfig {
            return when (providerId) {
                ClinicalAiProviderId.OPENAI_COMPATIBLE -> ClinicalAiProviderConfig(
                    providerId = providerId,
                    modelId = ClinicalAiModelIdPolicy.requireValid(modelId),
                    endpoint = ClinicalAiEndpointPolicy.requireValid(
                        requireNotNull(endpoint) { "Compatible provider endpoint is required" }
                    ).normalized,
                    compatibleProtocol = requireNotNull(compatibleProtocol) {
                        "Compatible provider protocol is required"
                    }
                )

                else -> ClinicalAiProviderConfig(
                    providerId = providerId,
                    modelId = ClinicalAiModelIdPolicy.requireValid(modelId),
                    endpoint = endpoint,
                    compatibleProtocol = compatibleProtocol
                )
            }
        }

    }
}

object ClinicalAiConfigIdentityPolicy {
    private val canonicalSha256 = Regex("^[0-9a-f]{64}$")

    fun requireValid(identity: String): String {
        require(canonicalSha256.matches(identity)) {
            "Clinical AI configuration identity must be a lowercase SHA-256 value"
        }
        return identity
    }
}

fun ClinicalAiProviderConfig.executionIdentity(): String {
    val canonical = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeCanonicalField("clinical-ai-provider-config-v1")
            output.writeCanonicalField(providerId.name)
            output.writeCanonicalField(modelId)
            output.writeCanonicalNullableField(endpoint)
            output.writeCanonicalNullableField(compatibleProtocol?.name)
        }
        buffer.toByteArray()
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical)
        .toLowercaseHex()
        .also(ClinicalAiConfigIdentityPolicy::requireValid)
}

private fun DataOutputStream.writeCanonicalField(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataOutputStream.writeCanonicalNullableField(value: String?) {
    if (value == null) {
        writeInt(-1)
    } else {
        writeCanonicalField(value)
    }
}

private fun ByteArray.toLowercaseHex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        this@toLowercaseHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}
