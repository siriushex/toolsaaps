package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.aaps.copilot.domain.alerts.AlertCauseDirection
import io.aaps.copilot.domain.alerts.AlertCauseSnapshotCodec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.Assert.assertThrows
import org.junit.Test

class AlertAiCanonicalBytesTest {
    @Test
    fun streamingWriterMatchesIndependentLegacyGoldenBytesAndHash() {
        val expectedJson = legacyCanonicalJson(GOLDEN_FIXTURE_TEXT)
        val expectedBytes = expectedJson.toByteArray(StandardCharsets.UTF_8)

        val actual = AlertAiContextBuilder().build(goldenRequest(GOLDEN_FIXTURE_TEXT))

        assertThat(actual.canonicalJson).isEqualTo(expectedJson)
        assertThat(actual.canonicalBytes.toByteArray()).isEqualTo(expectedBytes)
        assertThat(actual.canonicalBytes.utf8()).isEqualTo(actual.canonicalJson)
        assertThat(actual.sha256).isEqualTo(sha256(expectedBytes))
        assertThat(expectedJson).contains("\\\"")
        assertThat(expectedJson).contains("\\\\")
        assertThat(expectedJson).contains("\\u0000")
        assertThat(expectedJson).contains("\\u001f")
        assertThat(expectedJson).contains("\u00e9\u03a9\u4e2d\ud83d\ude80")
        assertThat(expectedJson).contains("\\u2028\\u2029")
        assertThat(expectedJson).contains("\"meanGlucose\":5.125")
        assertThat(expectedJson).contains("\"insulinUnits\":0.0")
        assertThat(expectedJson).contains("\"subtype\":null")
        assertThat(
            JsonParser.parseString(actual.canonicalJson).asJsonObject
                .getAsJsonArray("events14d")[0].asJsonObject["note"].asString
        ).isEqualTo(GOLDEN_FIXTURE_TEXT)
    }

    @Test
    fun malformedHighAndLowSurrogatesAreRejectedFailClosed() {
        listOf(
            "high-\uD800-tail",
            "low-\uDC00-tail"
        ).forEach { malformed ->
            assertThrows(AlertAiContextException.InvalidInput::class.java) {
                AlertAiContextBuilder().build(goldenRequest(malformed))
            }
        }
    }

    @Test
    fun byteCapAcceptsExactUtf8BoundaryAndRejectsMultibyteEscapeExpansionNextByte() {
        val exactText = "\u00e9A"
        val overflowText = "\u00e9\n"
        val exactLegacyBytes = legacyCanonicalJson(exactText).toByteArray(StandardCharsets.UTF_8)
        val overflowLegacyBytes = legacyCanonicalJson(overflowText)
            .toByteArray(StandardCharsets.UTF_8)
        assertThat(overflowLegacyBytes.size).isEqualTo(exactLegacyBytes.size + 1)

        val exact = AlertAiContextBuilder(maxBytes = exactLegacyBytes.size)
            .build(goldenRequest(exactText))
        assertThat(exact.canonicalBytes.size).isEqualTo(exactLegacyBytes.size)
        assertThat(exact.canonicalBytes.toByteArray()).isEqualTo(exactLegacyBytes)

        val writes = mutableListOf<AlertAiContextByteObservation>()
        assertThrows(AlertAiContextException.LimitExceeded::class.java) {
            AlertAiContextBuilder(
                maxBytes = exactLegacyBytes.size,
                byteProbe = writes::add
            ).build(goldenRequest(overflowText))
        }
        assertThat(writes.last().attemptedBytes).isEqualTo(exactLegacyBytes.size + 1)
        assertThat(writes.last().retainedBytes).isEqualTo(exactLegacyBytes.size)
        assertThat(writes.last().limit).isEqualTo(exactLegacyBytes.size)
    }

    // Independent copy of the pre-ee1b55d Gson tree shape; no production writer is shared.
    private fun legacyCanonicalJson(note: String): String = JsonObject().apply {
        addProperty("schemaVersion", 1)
        addProperty("generatedAt", GOLDEN_FIXTURE_NOW)
        addProperty("stage", GOLDEN_FIXTURE_STAGE)
        addProperty("direction", AlertCauseDirection.LOW.name)
        add("localCause", JsonParser.parseString(GOLDEN_LEGACY_FAILURE_CAUSE))
        add("detail24h", JsonObject().apply {
            addProperty("fromTs", GOLDEN_FIXTURE_NOW - GOLDEN_FIXTURE_DAY_MS)
            addProperty("throughTsExclusive", GOLDEN_FIXTURE_NOW)
            add("rawGlucose", JsonArray().apply {
                add(JsonArray().apply { add(GOLDEN_FIXTURE_TS); add(5.125) })
            })
            add("calibratedGlucose", JsonArray().apply {
                add(JsonArray().apply { add(GOLDEN_FIXTURE_TS); add(5.125) })
            })
            add("therapy", JsonArray().apply {
                add(JsonArray().apply {
                    add(GOLDEN_FIXTURE_TS)
                    add(1.25)
                    add(JsonNull.INSTANCE)
                    add(false)
                    add(ClinicalInsulinEvidence.CONFIRMED.name)
                    add(JsonNull.INSTANCE)
                })
            })
            add("targets", JsonArray().apply {
                add(JsonArray().apply {
                    add(GOLDEN_FIXTURE_TS)
                    add(5.5)
                    add(JsonNull.INSTANCE)
                    add(JsonNull.INSTANCE)
                    add(JsonNull.INSTANCE)
                    add(JsonNull.INSTANCE)
                })
            })
            add("forecasts", JsonArray().apply {
                add(JsonArray().apply {
                    add(GOLDEN_FIXTURE_TS)
                    add(30)
                    add(6.125)
                    add(5.0)
                    add(7.25)
                })
            })
            add("telemetry", JsonArray().apply {
                add(JsonArray().apply {
                    add(GOLDEN_FIXTURE_TS)
                    add("iob_units")
                    add(1.25)
                    add("OK")
                    add(ClinicalTelemetryOrigin.OTHER.name)
                })
            })
        })
        addProperty("aggregate14dFromTs", GOLDEN_FIXTURE_NOW - GOLDEN_FIXTURE_FOURTEEN_DAYS_MS)
        addProperty("aggregate14dThroughTsExclusive", GOLDEN_FIXTURE_NOW)
        add("daily14d", JsonArray().apply {
            repeat(14) { day ->
                add(JsonObject().apply {
                    addProperty("day", day)
                    addProperty("glucoseCount", if (day == 13) 1 else 0)
                    addNullableNumber("meanGlucose", 5.125.takeIf { day == 13 })
                    addNullableNumber("minimumGlucose", 5.125.takeIf { day == 13 })
                    addNullableNumber("maximumGlucose", 5.125.takeIf { day == 13 })
                    addProperty("insulinUnits", if (day == 13) 1.25 else 0.0)
                    addProperty("enteredCarbsGrams", 0.0)
                    addProperty("uamCarbsGrams", 0.0)
                })
            }
        })
        add("events14d", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "STRESS")
                add("subtype", JsonNull.INSTANCE)
                addProperty("startTs", GOLDEN_FIXTURE_TS)
                add("endTs", JsonNull.INSTANCE)
                addProperty("severity", "INFO")
                addProperty("source", "AUTOMATIC")
                add("title", JsonNull.INSTANCE)
                addProperty("note", note)
            })
        })
    }.toString()

    private fun JsonObject.addNullableNumber(name: String, value: Double?) {
        if (value == null) add(name, JsonNull.INSTANCE) else addProperty(name, value)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

}

internal fun alertCanonicalGoldenFixture(): AlertAiCanonicalContext =
    AlertAiContextBuilder().build(goldenRequest(GOLDEN_FIXTURE_TEXT))

private fun goldenRequest(note: String): AlertAiContextRequest {
    val glucose = listOf(ClinicalGlucosePoint(GOLDEN_FIXTURE_TS, 5.125))
    val therapy = listOf(
        ClinicalTherapyPoint(
            ts = GOLDEN_FIXTURE_TS,
            insulinU = 1.25,
            insulinEvidence = ClinicalInsulinEvidence.CONFIRMED
        )
    )
    return AlertAiContextRequest(
        nowTs = GOLDEN_FIXTURE_NOW,
        dataset = AlertAiContextDataset(
            generatedAt = GOLDEN_FIXTURE_NOW,
            detail24h = ClinicalDetailWindow(
                fromTs = GOLDEN_FIXTURE_NOW - GOLDEN_FIXTURE_DAY_MS,
                throughTs = GOLDEN_FIXTURE_NOW,
                glucose = glucose,
                calibratedGlucose = glucose,
                therapy = therapy,
                targets = listOf(ClinicalTargetPoint(GOLDEN_FIXTURE_TS, 5.5, null)),
                forecasts = listOf(
                    ClinicalForecastPoint(GOLDEN_FIXTURE_TS, 30, 6.125, 5.0, 7.25)
                ),
                telemetry = listOf(
                    ClinicalTelemetryPoint(GOLDEN_FIXTURE_TS, "iob_units", 1.25, "OK")
                )
            ),
            glucose14d = glucose,
            therapy14d = therapy,
            events14d = listOf(
                ClinicalEventSummary(
                    localId = "not-serialized",
                    type = "STRESS",
                    subtype = "",
                    startTs = GOLDEN_FIXTURE_TS,
                    endTs = GOLDEN_FIXTURE_TS,
                    severity = "INFO",
                    source = "OTHER",
                    title = "",
                    note = note,
                    status = "ACTIVE",
                    provenance = "not-serialized"
                )
            )
        ),
        localCauseSnapshot = AlertCauseSnapshotCodec.failureSnapshot(),
        stage = GOLDEN_FIXTURE_STAGE,
        direction = AlertCauseDirection.LOW
    )
}

private const val GOLDEN_FIXTURE_DAY_MS = 24L * 60L * 60L * 1_000L
private const val GOLDEN_FIXTURE_FOURTEEN_DAYS_MS = 14L * GOLDEN_FIXTURE_DAY_MS
private const val GOLDEN_FIXTURE_NOW = 20L * GOLDEN_FIXTURE_DAY_MS
private const val GOLDEN_FIXTURE_TS = GOLDEN_FIXTURE_NOW - 60_000L
private const val GOLDEN_FIXTURE_STAGE = "LOW_PREDICTED_30"
private const val GOLDEN_LEGACY_FAILURE_CAUSE =
    "{\"version\":1,\"cause\":\"DATA_INCOMPLETE\",\"confidence\":\"LOW\",\"factors\":[\"DATA_INCOMPLETE\"],\"evidence\":[\"CURRENT_EVIDENCE_MISSING\"],\"identityStatus\":\"UNAVAILABLE\"}"

private val GOLDEN_FIXTURE_TEXT = buildString {
    append('"')
    append('\\')
    for (code in 0x00..0x1f) append(code.toChar())
    append("\u00e9\u03a9\u4e2d")
    append("\uD83D\uDE80")
    append('\u2028')
    append('\u2029')
}
