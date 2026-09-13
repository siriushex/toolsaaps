package io.aaps.copilot.domain.predict

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

enum class UamExportMode {
    OFF,
    CONFIRMED_ONLY,
    INCREMENTAL
}

data class UamUserSettings(
    val minSnackG: Int = 15,
    val maxSnackG: Int = 60,
    val snackStepG: Int = 5,
    val backdateMinutesDefault: Int = 25,
    val disableUamWhenManualCobActive: Boolean = true,
    val manualCobThresholdG: Double = 5.0,
    val disableUamIfManualCarbsNearby: Boolean = true,
    val manualMergeWindowMinutes: Int = 45,
    val maxUamAbsorbRateGph_Normal: Double = 30.0,
    val maxUamAbsorbRateGph_Boost: Double = 45.0,
    val maxUamTotalG: Double = 120.0,
    val maxActiveUamEvents: Int = 2,
    val uamCarbMultiplier_Normal: Double = 1.0,
    val uamCarbMultiplier_Boost: Double = 2.0,
    val gAbsThreshold_Normal: Double = 2.0,
    val gAbsThreshold_Boost: Double = 1.2,
    val mOfN_Normal: Pair<Int, Int> = 3 to 4,
    val mOfN_Boost: Pair<Int, Int> = 2 to 3,
    val confirmConf_Normal: Double = 0.45,
    val confirmConf_Boost: Double = 0.35,
    val minConfirmAgeMin: Int = 10,
    val exportMinIntervalMin: Int = 10,
    val exportMaxBackdateMin: Int = 180
)

enum class UamInferenceState {
    SUSPECTED,
    CONFIRMED,
    MERGED,
    FINAL
}

enum class UamMode {
    NORMAL,
    BOOST
}

data class UamInferenceEvent(
    val id: String = UUID.randomUUID().toString(),
    val state: UamInferenceState,
    val mode: UamMode,
    val createdAt: Long,
    val updatedAt: Long,
    val ingestionTs: Long,
    val carbsModelG: Double,
    val carbsDisplayG: Double,
    val confidence: Double,
    val exportedGrams: Double = 0.0,
    val exportSeq: Int = 0,
    val lastExportTs: Long? = null,
    val learnedEligible: Boolean = false
)

data class UamTag(
    val id: String,
    val seq: Int,
    val mode: String,
    val ver: Int
)

object UamTagCodec {
    fun buildTag(eventId: String, seq: Int, mode: UamMode, version: Int = 1): String {
        require(seq > 0) { "UAM tag sequence must be positive" }
        return when (version) {
            1 -> {
                require(SAFE_V1_ID.matches(eventId)) { "Unsafe v1 UAM episode id" }
                "UAM_ENGINE|id=$eventId|seq=$seq|ver=1|mode=${mode.name}|"
            }
            2 -> {
                require(eventId.isNotEmpty()) { "UAM episode id must not be empty" }
                require(isWellFormedEpisodeId(eventId)) { "Malformed UTF-16 UAM episode id" }
                "UAM_ENGINE|id64=${encodeId(eventId)}|seq=$seq|ver=2|mode=${mode.name}|"
            }
            else -> throw IllegalArgumentException("Unsupported UAM tag version: $version")
        }
    }

    fun parseUamTag(note: String?): UamTag? {
        val source = note ?: return null
        if (!source.startsWith(PREFIX) || !source.endsWith(TERMINATOR)) return null
        val body = source.substring(PREFIX.length, source.length - TERMINATOR.length)
        if (body.isEmpty()) return null

        val fields = linkedMapOf<String, String>()
        for (token in body.split('|')) {
            val separator = token.indexOf('=')
            if (separator <= 0 || separator >= token.lastIndex) return null
            val key = token.substring(0, separator)
            val value = token.substring(separator + 1)
            if (key !in ALLOWED_KEYS || fields.put(key, value) != null) return null
        }

        val seqText = fields["seq"] ?: return null
        if (!POSITIVE_INTEGER.matches(seqText)) return null
        val seq = seqText.toIntOrNull() ?: return null
        val mode = fields["mode"] ?: return null
        if (UamMode.entries.none { it.name == mode }) return null
        val explicitVersion = fields["ver"]
        val ver = when (explicitVersion) {
            null -> 1
            "1" -> 1
            "2" -> 2
            else -> return null
        }
        val id = when (ver) {
            1 -> {
                val expectedKeys = if (explicitVersion == null) V1_KEYS_WITHOUT_VERSION else V1_KEYS
                if (fields.keys != expectedKeys) return null
                fields["id"]?.takeIf(SAFE_V1_ID::matches) ?: return null
            }
            2 -> {
                if (explicitVersion == null || fields.keys != V2_KEYS) return null
                decodeId(fields["id64"] ?: return null) ?: return null
            }
            else -> return null
        }
        return UamTag(id = id, seq = seq, mode = mode, ver = ver)
    }

    fun referencesEpisode(note: String?, episodeId: String): Boolean {
        val source = note ?: return false
        if (!source.startsWith(PREFIX) || !isWellFormedEpisodeId(episodeId)) return false
        val body = source.removePrefix(PREFIX).removeSuffix(TERMINATOR)
        val encodedId = encodeId(episodeId)
        return body.split('|').any { token ->
            val separator = token.indexOf('=')
            if (separator <= 0) return@any false
            val key = token.substring(0, separator)
            val value = token.substring(separator + 1)
            (key == "id" && value == episodeId) ||
                (key == "id64" && (value == encodedId || decodeIdLenient(value) == episodeId))
        }
    }

    fun isWellFormedEpisodeId(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                current.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
                    index += 2
                }
                current.isLowSurrogate() -> return false
                else -> index += 1
            }
        }
        return true
    }

    private fun encodeId(value: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeId(value: String): String? {
        if (!BASE64_URL_WITHOUT_PADDING.matches(value)) return null
        val decoded = decodeIdLenient(value) ?: return null
        val bytes = try {
            Base64.getUrlDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != value) return null
        return decoded
    }

    private fun decodeIdLenient(value: String): String? {
        val bytes = try {
            Base64.getUrlDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            null
        }
    }

    private const val PREFIX = "UAM_ENGINE|"
    private const val TERMINATOR = "|"
    private val SAFE_V1_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
    private val POSITIVE_INTEGER = Regex("[1-9][0-9]*")
    private val BASE64_URL_WITHOUT_PADDING = Regex("[A-Za-z0-9_-]+")
    private val ALLOWED_KEYS = setOf("id", "id64", "seq", "ver", "mode")
    private val V1_KEYS_WITHOUT_VERSION = setOf("id", "seq", "mode")
    private val V1_KEYS = setOf("id", "seq", "ver", "mode")
    private val V2_KEYS = setOf("id64", "seq", "ver", "mode")
}

fun parseUamTag(note: String?): UamTag? = UamTagCodec.parseUamTag(note)
