package io.aaps.copilot.service

import io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity
import io.aaps.copilot.data.repository.HistoryJson
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AapsV3EffectiveProfileProjectionTest {
    private val at = 1_800_000_000_000L
    private fun insulin(label: String = "historical insulin") = buildJsonObject {
        put("insulinLabel", label); put("insulinEndTime", 18_000_000); put("insulinPeakTime", 4_500_000); put("concentration", 1.0)
    }
    private fun profile(units: String = "mmol", zone: String = "Europe/Berlin") = buildJsonObject {
        put("units", units); put("timezone", zone)
        listOf("carbratio", "sens", "basal", "target_low", "target_high").forEach { key ->
            put(key, buildJsonArray {
                add(buildJsonObject { put("time", "00:00"); put("timeAsSeconds", 0); put("value", 5.5) })
                add(buildJsonObject { put("time", "06:00"); put("timeAsSeconds", 23400); put("value", 6.25) })
            })
        }
        put("iCfg", JsonObject(insulin() + ("insulinNickname" to JsonPrimitive("Historical"))))
    }
    private fun payload(profile: JsonObject = profile()) = buildJsonObject {
        put("app", "AAPS"); put("date", at); put("utcOffset", -300); put("eventType", "Note")
        put("originalProfileName", "Evening"); put("originalCustomizedName", "Evening 120%")
        put("originalTimeshift", -3_600_000); put("originalPercentage", 120)
        put("originalDuration", 3_600_000); put("originalEnd", at + 3_600_000)
        put("profileJson", profile.toString()); put("icfg", insulin())
    }
    private fun row(body: JsonObject = payload()) = LocalNightscoutV3DocumentEntity(
        "treatments", "native-profile-1", "local-v3-owner", at, body.toString(),
        HistoryJson.encodeUtf8(body.toString()).size, at + 1, at + 1000, false, false)
    private fun observed(body: JsonObject = payload()) =
        (AapsV3EffectiveProfileProjector.project(row(body)) as AapsV3ProfileProjection.Observed).observation
    private fun unavailable(row: LocalNightscoutV3DocumentEntity, reason: AapsV3ProfileUnavailable = AapsV3ProfileUnavailable.MALFORMED) {
        assertEquals(AapsV3ProfileProjection.Unavailable(reason), AapsV3EffectiveProfileProjector.project(row))
    }
    private fun replace(body: JsonObject, key: String, value: JsonElement) = JsonObject(body + (key to value))

    @Test fun preservesSourceTimeRevisionsNamesAndSignedOriginalModifiersWithoutReapplyingThem() {
        val result = observed()
        assertEquals("native-profile-1", result.identifier)
        assertEquals(at, result.eventAtMs)
        assertEquals(at + 1, result.createdRevision)
        assertEquals(at + 1000, result.modifiedRevision)
        assertEquals(-300L, result.utcOffsetMinutes)
        assertEquals("Evening", result.originalName)
        assertEquals("Evening 120%", result.customizedName)
        assertEquals(-3_600_000L, result.originalTimeshiftMs)
        assertEquals(120, result.originalPercentage)
        assertEquals(3_600_000L, result.originalDurationMs)
        assertEquals(at + 3_600_000, result.originalEndMs)
        assertEquals(6.25, result.profile.sensitivity[1].value, 0.0)
        assertEquals(6.25, result.profile.basal[1].value, 0.0)
        assertEquals(23400, result.profile.basal[1].seconds)
        assertEquals("06:00", result.profile.basal[1].label)
    }

    @Test fun preservesBothNativeUnitsAndTimezoneWithoutAnImplicitConversion() {
        listOf("mmol", "mg/dl").forEach { units ->
            val result = observed(payload(profile(units, "America/New_York")))
            assertEquals(units, result.profile.units)
            assertEquals("America/New_York", result.profile.timezone)
            assertEquals(5.5, result.profile.targetLow.first().value, 0.0)
        }
    }

    @Test fun historicalInsulinAndNicknameStayWithTheRecordRatherThanTodaysSelection() {
        val result = observed()
        assertEquals("historical insulin", result.insulin.label)
        assertEquals("Historical", result.profile.insulinNickname)
        assertEquals(18_000_000L, result.insulin.endTimeMs)
        assertEquals(4_500_000L, result.insulin.peakTimeMs)
        assertEquals(1.0, result.insulin.concentrationRatio, 0.0)
        assertEquals(result.profile.insulin, result.insulin)
    }

    @Test fun originalTemporaryEndDoesNotChangeTheEffectiveScheduleOrInventReversion() {
        val result = observed(replace(payload(), "originalEnd", JsonPrimitive(at - 1000)))
        assertEquals(at - 1000, result.originalEndMs)
        assertEquals(6.25, result.profile.carbRatio[1].value, 0.0)
    }

    @Test fun ordinaryNoteAndRequestedProfileSwitchCannotMasqueradeAsEffectiveEvidence() {
        listOf(JsonObject(payload() - "originalProfileName"), replace(payload(), "eventType", JsonPrimitive("Profile Switch"))).forEach {
            assertEquals(AapsV3ProfileProjection.NotEffectiveProfile, AapsV3EffectiveProfileProjector.project(row(it)))
        }
    }

    @Test fun nativeBolusAndCarbohydrateMapperBranchesCannotMasqueradeAsEffectiveProfiles() {
        listOf("insulin" to 1.0, "carbs" to 24.0, "carbs" to -10.0).forEach { (key, value) ->
            assertEquals(AapsV3ProfileProjection.NotEffectiveProfile,
                AapsV3EffectiveProfileProjector.project(row(replace(payload(), key, JsonPrimitive(value)))))
        }
    }

    @Test fun catalogDefaultIsNotAnEffectiveProfileObservation() {
        unavailable(row().copy(collection = "profile"), AapsV3ProfileUnavailable.INVALID_SOURCE)
    }

    @Test fun missingRecordInsulinNeverFallsBackToNestedOrCurrentInsulin() {
        unavailable(row(JsonObject(payload() - "icfg")), AapsV3ProfileUnavailable.MISSING_INSULIN)
        unavailable(row(replace(payload(), "icfg", JsonNull)), AapsV3ProfileUnavailable.MISSING_INSULIN)
    }

    @Test fun conflictingNestedAndRecordInsulinAreUnavailable() {
        unavailable(row(replace(payload(), "icfg", insulin("another insulin"))), AapsV3ProfileUnavailable.CONFLICTING_INSULIN)
    }

    @Test fun nestedInsulinIsOptionalButNeverRequiredToFillMissingHistory() {
        assertEquals("historical insulin", observed(payload(JsonObject(profile() - "iCfg"))).insulin.label)
    }

    @Test fun invalidatedRowsRemainUnavailableInsteadOfRevivingTheirOldProfile() {
        unavailable(row().copy(deleted = true), AapsV3ProfileUnavailable.INVALIDATED)
        unavailable(row(replace(payload(), "isValid", JsonPrimitive(false))), AapsV3ProfileUnavailable.INVALIDATED)
    }

    @Test fun corruptedOrUnownedEnvelopeCannotSupplyAProfile() {
        unavailable(row().copy(source = "other-source"), AapsV3ProfileUnavailable.INVALID_SOURCE)
        unavailable(row().copy(createdRevision = 0), AapsV3ProfileUnavailable.INVALID_SOURCE)
        unavailable(row().copy(modifiedRevision = at), AapsV3ProfileUnavailable.INVALID_SOURCE)
        unavailable(row().copy(eventAtMs = at + 1))
        unavailable(row().copy(payloadBytes = 1))
    }

    @Test fun malformedEffectiveMetadataHasNoPartialResult() {
        listOf("originalCustomizedName", "originalTimeshift", "originalPercentage", "originalDuration", "originalEnd", "utcOffset", "profileJson").forEach {
            unavailable(row(JsonObject(payload() - it)))
        }
        listOf("originalPercentage", "originalDuration", "originalEnd", "date").forEach {
            unavailable(row(replace(payload(), it, JsonPrimitive(-1))))
        }
        unavailable(row(replace(payload(), "utcOffset", JsonPrimitive(-1441))))
        unavailable(row(replace(payload(), "originalTimeshift", JsonPrimitive("-3600000"))))
        unavailable(row(replace(payload(), "originalPercentage", JsonPrimitive(0))))
    }

    @Test fun malformedInsulinCannotBecomeAUsableConfiguration() {
        listOf("insulinEndTime", "insulinPeakTime", "concentration").forEach { key ->
            unavailable(row(replace(payload(), "icfg", replace(insulin(), key, JsonPrimitive(0)))))
        }
        unavailable(row(replace(payload(), "icfg", replace(insulin(), "concentration", JsonPrimitive("1.0")))))
        unavailable(row(replace(payload(), "icfg", replace(insulin(), "insulinPeakTime", JsonPrimitive(19_000_000)))))
    }

    @Test fun malformedSchedulesUnitsAndZonesRefuseTheWholeObservation() {
        listOf(replace(profile(), "units", JsonPrimitive("mmol/l")),
            replace(profile(), "timezone", JsonPrimitive("Unknown/Region")),
            replace(profile(), "basal", JsonArray(emptyList())),
            replace(profile(), "target_high", buildJsonArray {
                add(buildJsonObject { put("time", "00:00"); put("timeAsSeconds", 0); put("value", 1.0) })
            })).forEach { unavailable(row(payload(it))) }
    }

    @Test fun duplicateJsonKeysInsideTheEncodedProfileAreRejected() {
        val raw = profile().toString().replace("\"units\":\"mmol\"", "\"units\":\"mmol\",\"units\":\"mg/dl\"")
        unavailable(row(replace(payload(), "profileJson", JsonPrimitive(raw))))
    }

    @Test fun sourceObjectsAndOutcomeLoggingDoNotExposePersonalNames() {
        val outcome = AapsV3EffectiveProfileProjector.project(row())
        assertFalse(outcome.toString().contains("Evening"))
        assertFalse(observed().profile.toString().contains("historical insulin"))
        assertFalse(observed().insulin.toString().contains("historical insulin"))
    }
}
