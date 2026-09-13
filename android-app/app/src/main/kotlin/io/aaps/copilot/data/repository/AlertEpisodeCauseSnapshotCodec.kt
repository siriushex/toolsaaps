package io.aaps.copilot.data.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.aaps.copilot.domain.alerts.AlertCauseCode
import io.aaps.copilot.domain.alerts.AlertCauseSnapshot
import io.aaps.copilot.domain.alerts.AlertCauseSnapshotCodec
import io.aaps.copilot.util.ordinaryExceptionOrNull

data class DecodedAlertEpisodeCause(
    val initialStage: String,
    val cause: AlertCauseCode,
    val localCauseSnapshot: AlertCauseSnapshot,
    val canonicalEpisodeJson: String
)

object AlertEpisodeCauseSnapshotCodec {
    fun encode(
        initialStage: String,
        localCauseSnapshot: AlertCauseSnapshot,
        parseObject: (String) -> JsonObject = { JsonParser.parseString(it).asJsonObject }
    ): DecodedAlertEpisodeCause? {
        if (GlucoseAlertState.entries.none { it.name == initialStage }) return null
        val sanitized = AlertCauseSnapshotCodec.sanitize(
            localCauseSnapshot,
            parseObject
        ) ?: return null
        val sanitizedRoot = ordinaryExceptionOrNull {
            parseObject(sanitized.canonicalJson)
        } ?: return null
        val cause = ordinaryExceptionOrNull {
            AlertCauseCode.valueOf(sanitizedRoot.get("cause").asString)
        } ?: return null
        val canonicalEpisodeJson = JsonObject().apply {
            addProperty("initialStage", initialStage)
            sanitizedRoot.entrySet().forEach { (key, value) -> add(key, value) }
        }.toString().takeIf { it.length <= MAX_EPISODE_JSON_CHARS } ?: return null
        return DecodedAlertEpisodeCause(
            initialStage = initialStage,
            cause = cause,
            localCauseSnapshot = sanitized,
            canonicalEpisodeJson = canonicalEpisodeJson
        )
    }

    fun encodeMinimal(initialStage: String): String? {
        if (GlucoseAlertState.entries.none { it.name == initialStage }) return null
        return JsonObject().apply {
            addProperty("initialStage", initialStage)
        }.toString()
    }

    fun initialStage(
        episodeJson: String,
        parseObject: (String) -> JsonObject = { JsonParser.parseString(it).asJsonObject }
    ): String? {
        if (episodeJson.length !in 1..MAX_EPISODE_JSON_CHARS) return null
        val source = ordinaryExceptionOrNull { parseObject(episodeJson) } ?: return null
        val stage = ordinaryExceptionOrNull { source.get("initialStage")?.asString }
            ?.takeIf { candidate -> GlucoseAlertState.entries.any { it.name == candidate } }
            ?: return null
        if (episodeJson == encodeMinimal(stage)) return stage
        return decode(episodeJson, parseObject)?.initialStage
    }

    fun decode(
        episodeJson: String,
        parseObject: (String) -> JsonObject = { JsonParser.parseString(it).asJsonObject }
    ): DecodedAlertEpisodeCause? {
        if (episodeJson.length !in 1..MAX_EPISODE_JSON_CHARS) return null
        val source = ordinaryExceptionOrNull { parseObject(episodeJson) } ?: return null
        val initialStage = ordinaryExceptionOrNull { source.get("initialStage")?.asString }
            ?.takeIf { candidate ->
                GlucoseAlertState.entries.any { it.name == candidate }
            }
            ?: return null
        val causeRoot = JsonObject().apply {
            source.entrySet()
                .filter { (key, _) -> key != "initialStage" }
                .forEach { (key, value) -> add(key, value) }
        }
        val canonical = encode(
            initialStage = initialStage,
            localCauseSnapshot = AlertCauseSnapshot(causeRoot.toString()),
            parseObject = parseObject
        ) ?: return null
        return canonical.takeIf { episodeJson == it.canonicalEpisodeJson }
    }

    internal const val MAX_EPISODE_JSON_CHARS = 4_096
}
