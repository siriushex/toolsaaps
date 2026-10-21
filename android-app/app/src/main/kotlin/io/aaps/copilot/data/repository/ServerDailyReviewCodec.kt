package io.aaps.copilot.data.repository

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.*

/** Validates audit display fields and their bindings; unused cohort metrics stay inert. */
internal object DailyReviewCodec {
    private const val REVISION = "daily-review-read-v1"
    private val digest = Regex("[0-9a-f]{64}")
    private val modelCode = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
    private val excludedTypes = setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt(),
        Character.PRIVATE_USE.toInt(), Character.UNASSIGNED.toInt(), Character.DECIMAL_DIGIT_NUMBER.toInt(),
        Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt())
    private val summaryFields = setOf("sequence", "jobId", "requestId", "reportDate", "zoneId", "model",
        "storedAtMs", "expiresAtMs", "summary", "sourceCheck", "publicationState")
    private val sourceFields = setOf("revision", "scope", "meaning", "provenance", "dataCompleteness", "modelState",
        "reportKey", "reportRevision", "factsDigest", "sourceBinding", "sourceDigest", "replayedSourceDigest",
        "replayedFactsDigest", "originalAsOfMs", "originalSnapshotSequence", "checkedAtMs", "checkedSnapshotSequence",
        "windowStartMs", "originalWindowEndMs", "windowEndMs", "status", "reason")
    private val sourceBindingFields = setOf("reportKey", "reportRevision", "factsDigest", "sourceBinding", "sourceDigest",
        "originalAsOfMs", "originalSnapshotSequence", "windowStartMs", "originalWindowEndMs")
    private val alternatives = setOf("MEASUREMENT_ERROR_NOT_EXCLUDED", "OTHER_CONTEXT_NOT_ANALYZED",
        "SINGLE_DAY_OBSERVATION", "LIMITED_ELIGIBLE_HISTORY")

    fun listQuery(query: DailyReviewQuery): ByteArray {
        counter(query.afterSequence)
        query.snapshotSequence?.let { counter(it); check(query.afterSequence <= it) }
        return HistoryJson.bytes(buildJsonObject {
            put("afterSequence", query.afterSequence)
            put("snapshotSequence", query.snapshotSequence?.let(::JsonPrimitive) ?: JsonNull)
        })
    }

    fun detailQuery(jobId: String): ByteArray {
        HistoryJson.uuid(jobId)
        return HistoryJson.bytes(buildJsonObject { put("jobId", jobId) })
    }

    fun page(value: JsonObject, query: DailyReviewQuery, now: Long): DailyReviewPage {
        listQuery(query); HistoryJson.time(now)
        HistoryJson.fields(value, setOf("revision", "membershipMeaning", "snapshotSequence", "records", "hasMore", "nextAfterSequence"))
        literal(value, "revision", REVISION); literal(value, "membershipMeaning", "RETAINED_ARCHIVE_ONLY")
        val high = count(value, "snapshotSequence")
        check(high >= query.afterSequence && (query.snapshotSequence == null || high == query.snapshotSequence))
        val records = HistoryJson.array(value.getValue("records")).also { check(it.size <= 20) }
            .map { summary(HistoryJson.obj(it), now) }
        var previous = query.afterSequence
        records.forEach { check(it.sequence > previous && it.sequence <= high); previous = it.sequence }
        check(records.map { it.jobId }.distinct().size == records.size)
        val more = HistoryJson.bool(value.getValue("hasMore"))
        val next = value.getValue("nextAfterSequence").let { if (it == JsonNull) null else HistoryJson.long(it).also(::counter) }
        check(if (more) records.size == 20 && next != null && next == records.last().sequence && next < high else next == null)
        return DailyReviewPage(high, records, more, next)
    }

    fun summary(value: JsonObject, now: Long): DailyReviewSummary {
        HistoryJson.fields(value, summaryFields)
        literal(value, "sourceCheck", "NOT_CHECKED"); literal(value, "publicationState", "NOT_ADMITTED")
        HistoryJson.uuid(string(value, "requestId"))
        val header = header(value, now)
        return DailyReviewSummary(count(value, "sequence").also { check(it > 0) }, header.job,
            header.date, header.zone, header.model, header.stored, header.expires, qualitative(value, "summary", 260), value)
    }

    fun detail(value: JsonObject, requestedJob: String, now: Long): DailyReviewDetail {
        HistoryJson.uuid(requestedJob)
        HistoryJson.fields(value, setOf("revision", "jobId", "reportDate", "zoneId", "reportRevision", "model",
            "storedAtMs", "expiresAtMs", "currentState", "sourceCheck", "sourceAtReply", "review"))
        literal(value, "revision", REVISION)
        val header = header(value, now)
        check(header.job == requestedJob)
        val revision = count(value, "reportRevision").also { check(it > 0) }
        val original = source(HistoryJson.obj(value.getValue("sourceAtReply")), header.stored)
        literal(original, "status", "MATCHED")
        check(count(original, "reportRevision") == revision)
        val current = value.getValue("sourceCheck").let { if (it == JsonNull) null else source(HistoryJson.obj(it), now) }
        val state = DailyReviewSourceState.valueOf(string(value, "currentState"))
        current?.let {
            check(time(it, "checkedAtMs") >= header.stored)
            check(sourceBindingFields.all { key -> it.getValue(key) == original.getValue(key) })
        }
        when (state) {
            DailyReviewSourceState.SOURCE_MATCHED -> { check(current != null); literal(current, "status", "MATCHED") }
            DailyReviewSourceState.SOURCE_CHANGED -> { check(current != null); literal(current, "status", "CHANGED") }
            DailyReviewSourceState.SOURCE_UNAVAILABLE -> current?.let { literal(it, "status", "UNAVAILABLE") }
            DailyReviewSourceState.SUPERSEDED -> check(current == null)
        }
        val review = HistoryJson.obj(value.getValue("review"))
        HistoryJson.fields(review, setOf("validation", "publicationState", "textMeaning", "scope", "sourceFreshness",
            "factsDigest", "packetDigest", "status", "day", "cohorts", "findings", "omissions", "missingInputCodes", "summary"))
        literal(review, "validation", "REFERENCES_ONLY"); literal(review, "publicationState", "NOT_ADMITTED")
        literal(review, "textMeaning", "UNREVIEWED_AI_INTERPRETATION"); literal(review, "scope", "GLUCOSE_ONLY")
        literal(review, "sourceFreshness", "NOT_INDEPENDENTLY_VERIFIED")
        hash(review, "packetDigest"); check(hash(review, "factsDigest") == hash(original, "factsDigest"))
        val status = string(review, "status").also { check(it in setOf("FINDINGS", "NO_SUPPORTED_PATTERN", "LIMITED_DATA")) }
        day(HistoryJson.obj(review.getValue("day")), header, original)
        val cohortIds = HistoryJson.array(review.getValue("cohorts")).also { check(it.size <= 6) }.map {
            val cohort = HistoryJson.obj(it)
            HistoryJson.fields(cohort, setOf("cohortId", "source", "quality", "metrics", "comparison"))
            check(HistoryJson.code.matches(string(cohort, "source")))
            check(string(cohort, "quality") in setOf("OK", "UNKNOWN"))
            HistoryJson.obj(cohort.getValue("metrics")); HistoryJson.obj(cohort.getValue("comparison"))
            hash(cohort, "cohortId")
        }
        check(cohortIds.distinct().size == cohortIds.size)
        val candidateIds = mutableSetOf<String>()
        val findings = HistoryJson.array(review.getValue("findings")).also { check(it.size <= 5) }.map {
            val finding = HistoryJson.obj(it)
            HistoryJson.fields(finding, setOf("pattern", "action", "alternativeCodes", "interpretation"))
            literal(finding, "action", "REVIEW_MEASUREMENT_AND_RECORDED_CONTEXT")
            val pattern = HistoryJson.obj(finding.getValue("pattern"))
            HistoryJson.fields(pattern, setOf("candidateId", "cohortId", "band", "startHour", "endHour", "lookbackDays",
                "status", "qualifyingDays", "eligibleWindows", "lowMmol", "highMmol", "rangeVersion", "windowEvidence",
                "omittedEvidenceWindows", "excludedDays", "allowedAction"))
            check(candidateIds.add(hash(pattern, "candidateId")))
            check(hash(pattern, "cohortId") in cohortIds)
            literal(pattern, "allowedAction", "REVIEW_MEASUREMENT_AND_RECORDED_CONTEXT")
            codes(finding.getValue("alternativeCodes"), 3).also { codes -> check(codes.isNotEmpty() && codes.all { it in alternatives }) }
            qualitative(finding, "interpretation", 360)
        }
        check(status != "NO_SUPPORTED_PATTERN" || findings.isEmpty())
        check(status != "FINDINGS" || findings.isNotEmpty())
        val omissions = HistoryJson.obj(review.getValue("omissions"))
        HistoryJson.fields(omissions, setOf("calculatorPatterns", "inputPatterns", "calculatorEpisodes", "inputEpisodes",
            "inputCohorts", "selectedCandidateEvidenceWindows"))
        omissions.keys.forEach { count(omissions, it) }
        val missing = codes(review.getValue("missingInputCodes"), 32)
        return DailyReviewDetail(header.job, header.date, header.zone, revision, header.model, header.stored, header.expires,
            state, current?.let { time(it, "checkedAtMs") }, time(original, "checkedAtMs"), status,
            qualitative(review, "summary", 260), findings, missing, value)
    }

    private data class Header(val job: String, val date: String, val zone: String, val start: Long,
        val end: Long, val model: String, val stored: Long, val expires: Long)

    private fun header(value: JsonObject, now: Long): Header {
        HistoryJson.time(now)
        val job = string(value, "jobId").also(HistoryJson::uuid)
        val date = string(value, "reportDate").also { check(it.length == 10 && LocalDate.parse(it).toString() == it) }
        val zone = string(value, "zoneId").also { check(it.length in 1..64 && it in ZoneId.getAvailableZoneIds()) }
        val start = LocalDate.parse(date).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli().also(HistoryJson::time)
        val end = LocalDate.parse(date).plusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli().also(HistoryJson::time)
        check(end > start)
        val stored = time(value, "storedAtMs"); val expires = time(value, "expiresAtMs")
        check(stored <= now && now < expires && stored < expires && expires == end + HistoryJson.retentionMs)
        val model = string(value, "model").also { check(modelCode.matches(it)) }
        return Header(job, date, zone, start, end, model, stored, expires)
    }

    private fun source(value: JsonObject, now: Long): JsonObject {
        HistoryJson.fields(value, sourceFields)
        literal(value, "revision", "daily-source-check-v1"); literal(value, "scope", "GLUCOSE_ONLY")
        literal(value, "meaning", "RETAINED_HISTORY_AT_CHECK"); literal(value, "provenance", "DEVICE_DECLARED")
        literal(value, "dataCompleteness", "UNKNOWN"); literal(value, "modelState", "NOT_REQUESTED")
        for (key in listOf("reportKey", "factsDigest", "sourceBinding", "sourceDigest")) hash(value, key)
        for (key in listOf("replayedSourceDigest", "replayedFactsDigest")) if (value.getValue(key) != JsonNull) hash(value, key)
        check(count(value, "reportRevision") > 0)
        val original = time(value, "originalAsOfMs"); val checked = time(value, "checkedAtMs")
        check(original <= checked && checked <= now)
        val first = time(value, "windowStartMs")
        check(first < time(value, "originalWindowEndMs") && time(value, "originalWindowEndMs") <= original)
        check(first < time(value, "windowEndMs") && time(value, "windowEndMs") <= checked)
        count(value, "originalSnapshotSequence"); count(value, "checkedSnapshotSequence")
        val reason = string(value, "reason")
        when (string(value, "status")) {
            "MATCHED" -> {
                check(reason == "SOURCE_MATCHED")
                check(hash(value, "replayedSourceDigest") == hash(value, "sourceDigest"))
                check(hash(value, "replayedFactsDigest") == hash(value, "factsDigest"))
                check(count(value, "checkedSnapshotSequence") >= count(value, "originalSnapshotSequence"))
            }
            "CHANGED" -> check(reason in setOf("SOURCE_CHANGED", "NEWER_OBSERVATION"))
            "UNAVAILABLE" -> check(reason in setOf("RETENTION_WINDOW", "HISTORY_ROLLBACK", "FACTS_MISMATCH"))
            else -> error("invalid_daily_source")
        }
        return value
    }

    private fun day(value: JsonObject, header: Header, source: JsonObject) {
        HistoryJson.fields(value, setOf("window", "asOfMs", "snapshotSequence", "coveredMinutes", "qualityOkCoveredMinutes",
            "unverifiedCoveredMinutes", "missingMinutes", "coveragePct", "qualityCounts", "conflictingTimestamps", "maxUncoveredMinutes", "ranges"))
        val window = HistoryJson.obj(value.getValue("window"))
        HistoryJson.fields(window, setOf("reportDate", "zoneId", "startMs", "endMs"))
        check(string(window, "reportDate") == header.date && string(window, "zoneId") == header.zone)
        check(time(window, "startMs") == header.start && time(window, "endMs") == header.end)
        check(time(value, "asOfMs") == time(source, "originalAsOfMs"))
        check(count(value, "snapshotSequence") == count(source, "originalSnapshotSequence"))
        count(value, "conflictingTimestamps")
        for (key in listOf("coveredMinutes", "qualityOkCoveredMinutes", "unverifiedCoveredMinutes", "missingMinutes", "maxUncoveredMinutes"))
            check(HistoryJson.double(value.getValue(key)) in 0.0..((header.end - header.start) / 60_000.0))
        check(HistoryJson.double(value.getValue("coveragePct")) in 0.0..100.0)
        val quality = HistoryJson.obj(value.getValue("qualityCounts"))
        HistoryJson.fields(quality, setOf("OK", "UNKNOWN", "SUSPECT")); quality.keys.forEach { count(quality, it) }
        check(HistoryJson.array(value.getValue("ranges")).size <= 256)
    }

    private fun codes(value: JsonElement, max: Int) = HistoryJson.array(value).also { check(it.size <= max) }
        .map { HistoryJson.string(it).also { check(HistoryJson.code.matches(it)) } }
        .also { check(it.distinct().size == it.size) }
    private fun qualitative(value: JsonObject, key: String, max: Int): String = string(value, key).also { text ->
        check(text.isNotBlank() && text == text.trim() && text.codePointCount(0, text.length) <= max)
        check(text.none { it in "<>`{}[]" })
        check(listOf("://", "www.", "mailto:", "javascript:").none { text.contains(it, ignoreCase = true) })
        check(text.codePoints().noneMatch { Character.getType(it) in excludedTypes })
    }
    private fun counter(value: Long) { check(value in 0 until Long.MAX_VALUE) }
    private fun count(value: JsonObject, key: String) = HistoryJson.long(value.getValue(key)).also(::counter)
    private fun time(value: JsonObject, key: String) = HistoryJson.long(value.getValue(key)).also(HistoryJson::time)
    private fun string(value: JsonObject, key: String) = HistoryJson.string(value.getValue(key))
    private fun hash(value: JsonObject, key: String) = string(value, key).also { check(digest.matches(it)) }
    private fun literal(value: JsonObject, key: String, expected: String) { check(string(value, key) == expected) }
}
