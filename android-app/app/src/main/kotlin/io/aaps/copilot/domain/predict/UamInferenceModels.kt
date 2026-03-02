package io.aaps.copilot.domain.predict

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
    fun buildTag(eventId: String, seq: Int, mode: UamMode): String {
        return "UAM_ENGINE|id=$eventId|seq=$seq|ver=1|mode=${mode.name}|"
    }

    fun parseUamTag(note: String?): UamTag? {
        val source = note?.trim().orEmpty()
        if (!source.contains("UAM_ENGINE|")) return null
        val parts = source.split('|')
        val map = mutableMapOf<String, String>()
        parts.forEach { token ->
            val idx = token.indexOf('=')
            if (idx <= 0 || idx >= token.lastIndex) return@forEach
            val key = token.substring(0, idx).trim()
            val value = token.substring(idx + 1).trim()
            if (key.isNotBlank() && value.isNotBlank()) {
                map[key] = value
            }
        }
        val id = map["id"] ?: return null
        val seq = map["seq"]?.toIntOrNull() ?: return null
        val ver = map["ver"]?.toIntOrNull() ?: 1
        val mode = map["mode"] ?: "NORMAL"
        if (seq <= 0) return null
        return UamTag(id = id, seq = seq, mode = mode, ver = ver)
    }
}

fun parseUamTag(note: String?): UamTag? = UamTagCodec.parseUamTag(note)
