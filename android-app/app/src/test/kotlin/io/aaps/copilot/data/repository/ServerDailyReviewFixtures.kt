package io.aaps.copilot.data.repository

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

internal object DailyReviewFixtures {
    val start = Instant.parse("2026-10-13T00:00:00Z").toEpochMilli()
    val end = start + 86_400_000
    val now = end + 3_600_000
    val stored = now - 60_000
    val expires = end + HistoryJson.retentionMs
    val device = "d".repeat(64)
    const val text = "Review the observations and their limitations."
    fun id(index: Int = 1) = UUID(0, index.toLong()).toString()
    fun parse(value: String) = HistoryJson.parse(value.toByteArray(), 131_072)

    fun summary(index: Int = 1) = buildJsonObject {
        put("sequence", index); put("jobId", id(index)); put("requestId", id(index + 1000))
        put("reportDate", "2026-10-13"); put("zoneId", "UTC"); put("model", "sample-text-model")
        put("storedAtMs", stored); put("expiresAtMs", expires); put("summary", text)
        put("sourceCheck", "NOT_CHECKED"); put("publicationState", "NOT_ADMITTED")
    }

    fun page(records: List<JsonObject> = listOf(summary()), high: Long = 1, more: Boolean = false) = buildJsonObject {
        put("revision", "daily-review-read-v1"); put("membershipMeaning", "RETAINED_ARCHIVE_ONLY")
        put("snapshotSequence", high); put("records", JsonArray(records)); put("hasMore", more)
        put("nextAfterSequence", if (more) records.last().getValue("sequence") else JsonNull)
    }

    fun source(checked: Long = now - 500, status: String = "MATCHED") = buildJsonObject {
        put("revision", "daily-source-check-v1"); put("scope", "GLUCOSE_ONLY")
        put("meaning", "RETAINED_HISTORY_AT_CHECK"); put("provenance", "DEVICE_DECLARED")
        put("dataCompleteness", "UNKNOWN"); put("modelState", "NOT_REQUESTED")
        put("reportKey", "a".repeat(64)); put("reportRevision", 1); put("factsDigest", "b".repeat(64))
        put("sourceBinding", device); put("sourceDigest", "e".repeat(64))
        put("replayedSourceDigest", if (status == "MATCHED") JsonPrimitive("e".repeat(64)) else JsonNull)
        put("replayedFactsDigest", if (status == "MATCHED") JsonPrimitive("b".repeat(64)) else JsonNull)
        put("originalAsOfMs", stored - 2000); put("originalSnapshotSequence", 27)
        put("checkedAtMs", checked); put("checkedSnapshotSequence", 27)
        put("windowStartMs", start - 21 * 86_400_000L); put("originalWindowEndMs", end)
        put("windowEndMs", end); put("status", status)
        put("reason", if (status == "MATCHED") "SOURCE_MATCHED" else "NEWER_OBSERVATION")
    }

    fun detail(index: Int = 1) = buildJsonObject {
        put("revision", "daily-review-read-v1"); put("jobId", id(index))
        put("reportDate", "2026-10-13"); put("zoneId", "UTC"); put("reportRevision", 1)
        put("model", "sample-text-model"); put("storedAtMs", stored); put("expiresAtMs", expires)
        put("currentState", "SOURCE_MATCHED"); put("sourceAtReply", source(stored - 1000))
        put("sourceCheck", source())
        put("review", buildJsonObject {
            put("validation", "REFERENCES_ONLY"); put("publicationState", "NOT_ADMITTED")
            put("textMeaning", "UNREVIEWED_AI_INTERPRETATION"); put("scope", "GLUCOSE_ONLY")
            put("sourceFreshness", "NOT_INDEPENDENTLY_VERIFIED")
            put("factsDigest", "b".repeat(64)); put("packetDigest", "c".repeat(64))
            put("status", "LIMITED_DATA"); put("summary", text)
            put("day", buildJsonObject {
                put("window", buildJsonObject {
                    put("reportDate", "2026-10-13"); put("zoneId", "UTC")
                    put("startMs", start); put("endMs", end)
                })
                put("asOfMs", stored - 2000); put("snapshotSequence", 27)
                for (field in listOf("coveredMinutes", "qualityOkCoveredMinutes", "unverifiedCoveredMinutes", "coveragePct", "conflictingTimestamps")) put(field, 0)
                put("missingMinutes", 1440); put("maxUncoveredMinutes", 1440)
                put("qualityCounts", buildJsonObject { put("OK", 0); put("UNKNOWN", 0); put("SUSPECT", 0) })
                put("ranges", JsonArray(emptyList()))
            })
            put("cohorts", JsonArray(emptyList())); put("findings", JsonArray(emptyList()))
            put("omissions", buildJsonObject {
                for (field in listOf("calculatorPatterns", "inputPatterns", "calculatorEpisodes", "inputEpisodes", "inputCohorts", "selectedCandidateEvidenceWindows")) put(field, 0)
            })
            put("missingInputCodes", JsonArray(listOf(JsonPrimitive("MEALS_NOT_ANALYZED"), JsonPrimitive("INSULIN_NOT_ANALYZED"))))
        })
    }

    fun JsonObject.change(key: String, value: JsonElement) = JsonObject(this + (key to value))
}
