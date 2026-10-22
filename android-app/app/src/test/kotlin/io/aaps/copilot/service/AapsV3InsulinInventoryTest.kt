package io.aaps.copilot.service

import io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity
import io.aaps.copilot.data.repository.LocalV3RetainedSnapshot
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AapsV3InsulinInventoryTest {
    private val at = 1_800_000_000_000L
    private fun config(label: String = "historical") = buildJsonObject {
        put("insulinLabel", label); put("insulinEndTime", 18_000_000)
        put("insulinPeakTime", 4_500_000); put("concentration", 1.0)
    }
    private fun row(id: String = "dose-1", time: Long = at - 60_000,
        fields: JsonObject = buildJsonObject { put("insulin", 2.2); put("icfg", config()) },
        revision: Long = at - 1, deleted: Boolean = false): LocalNightscoutV3DocumentEntity {
        val body = buildJsonObject { put("eventType", "Correction Bolus"); put("date", time); put("isValid", true) } + fields
        val json = JsonObject(body).toString()
        return LocalNightscoutV3DocumentEntity("treatments", id, "local-v3-owner", time, json,
            json.toByteArray().size, at - 1000, revision, deleted, false)
    }
    private fun read(vararg rows: LocalNightscoutV3DocumentEntity, from: Long = at - 3_600_000,
        known: Long = at) = AapsV3InsulinInventoryTimeline(LocalV3RetainedSnapshot.Complete(
            rows.maxOfOrNull { it.modifiedRevision } ?: 0, rows.toList())).read(from, at, known)

    @Test fun usesNativeBolusBranchAndItsOwnInsulinContext() {
        val source = row(fields = buildJsonObject {
            put("eventType", "Note"); put("insulin", 2.2); put("type", "SMB"); put("icfg", config("old insulin"))
        })
        val result = read(source)
        assertEquals(AapsV3ObservedInsulinStatus.OBSERVED, result.status)
        val dose = result.records.single().dose as AapsV3InsulinDose.Bolus
        assertEquals(2.2, dose.units, 0.0)
        assertEquals("SMB", dose.type)
        assertEquals("old insulin", dose.configuration!!.label)
        assertFalse(result.upstreamCompletenessVerified)
    }

    @Test fun basalExtendedAndEmulatedDeliveryKeepDistinctSemantics() {
        val basal = row("basal", fields = buildJsonObject {
            put("eventType", "Temp Basal"); put("duration", 30); put("absolute", 0.8)
        })
        val extended = row("extended", fields = buildJsonObject {
            put("eventType", "Combo Bolus"); put("duration", 30); put("enteredinsulin", 1.5)
            put("isEmulatingTempBasal", true)
        })
        val result = read(basal, extended)
        assertEquals(AapsV3ObservedInsulinStatus.OBSERVED, result.status)
        val temp = result.records.single { it.identifier == "basal" }.dose as AapsV3InsulinDose.TempBasal
        assertEquals(1_800_000L, temp.durationMs)
        assertEquals(0.8, temp.absoluteUnitsPerHour!!, 0.0)
        val ext = result.records.single { it.identifier == "extended" }.dose as AapsV3InsulinDose.Extended
        assertTrue(ext.emulatingTempBasal)
        assertEquals(1.5, ext.units, 0.0)
    }

    @Test fun intervalStartingBeforeQueryRemainsWhenItOverlaps() {
        val source = row(time = at - 7_200_000, fields = buildJsonObject {
            put("eventType", "Temp Basal"); put("duration", 180); put("percent", -100)
        })
        assertEquals(1, read(source).records.size)
    }

    @Test fun nativeEmulatedBasalSelectsOnlyTheExtendedChildAndItsPumpMetadata() {
        val child = buildJsonObject {
            put("duration", 30); put("enteredinsulin", 0.25); put("isEmulatingTempBasal", true)
            put("pumpId", 9); put("pumpType", "pump"); put("pumpSerial", "child-serial"); put("endId", 12)
        }
        val wrapped = row(fields = buildJsonObject {
            put("eventType", "Temp Basal"); put("absolute", 99); put("duration", 60)
            put("pumpId", 8); put("extendedEmulated", child)
        })
        val result = read(wrapped)
        val dose = result.records.single().dose as AapsV3InsulinDose.Extended
        assertEquals(0.25, dose.units, 0.0)
        assertEquals(1_800_000L, dose.durationMs)
        assertEquals(12L, result.records.single().pumpEndId)
        val direct = row("direct", fields = JsonObject(child + ("eventType" to JsonPrimitive("Combo Bolus"))))
        assertEquals(AapsV3ObservedInsulinStatus.CONFLICTING, read(wrapped, direct).status)
    }

    @Test fun deletionAndInvalidationRemainVisibleWithoutRestoringAnOldDose() {
        for (source in listOf(row(deleted = true), row(fields = buildJsonObject {
            put("insulin", 2.2); put("isValid", false)
        }))) {
            assertTrue(read(source).records.single().invalidated)
        }
    }

    @Test fun missingConfigurationStaysUnknownRatherThanUsingTodaysInsulin() {
        val source = row(fields = buildJsonObject { put("insulin", 2.2) })
        assertNull((read(source).records.single().dose as AapsV3InsulinDose.Bolus).configuration)
    }

    @Test fun malformedRelevantRecordDoesNotYieldAPartialInventory() {
        for (fields in listOf(buildJsonObject { put("insulin", "NaN") }, buildJsonObject {
            put("eventType", "Combo Bolus"); put("enteredinsulin", 1.5); put("duration", 30); put("durationInMilliseconds", 1)
        }, buildJsonObject { put("insulin", 2.2); put("type", "UNKNOWN") })) {
            val result = read(row(), row("bad", fields = fields))
            assertEquals(AapsV3ObservedInsulinStatus.INVALID, result.status)
            assertTrue(result.records.isEmpty())
        }
    }

    @Test fun duplicatePumpIdentityCannotBecomeTwoDeliveries() {
        fun fields() = buildJsonObject {
            put("insulin", 2.2); put("pumpId", 123); put("pumpType", "pump"); put("pumpSerial", "serial")
        }
        val result = read(row("first", fields = fields()), row("second", fields = fields()))
        assertEquals(AapsV3ObservedInsulinStatus.CONFLICTING, result.status)
        assertTrue(result.records.isEmpty())
    }

    @Test fun pumpIdentityIsScopedToNativeRecordKindAndEndIdentitySurvives() {
        val common = buildJsonObject { put("pumpId", 123); put("pumpType", "pump"); put("pumpSerial", "serial"); put("endId", 456) }
        val bolus = row("bolus", fields = JsonObject(common + ("insulin" to JsonPrimitive(2.2))))
        val basal = row("basal", fields = JsonObject(common + buildJsonObject {
            put("eventType", "Temp Basal"); put("duration", 30); put("absolute", 0)
        }))
        val result = read(bolus, basal)
        assertEquals(2, result.records.size)
        assertNotEquals(result.records[0].pumpRecordIdentity, result.records[1].pumpRecordIdentity)
        assertTrue(result.records.all { it.pumpEndId == 456L })
    }

    @Test fun lateCorrectionCannotBeUsedAsIfItWereAvailableAtPredictionTime() {
        val result = read(row(revision = at + 10))
        assertEquals(AapsV3ObservedInsulinStatus.LATE_EVIDENCE, result.status)
        assertTrue(result.records.isEmpty())
    }

    @Test fun emptyAndOverLimitAreExplicitAndNeverProveCoverage() {
        val empty = read()
        assertEquals(AapsV3ObservedInsulinStatus.NOT_OBSERVED, empty.status)
        assertFalse(empty.upstreamCompletenessVerified)
        val limited = AapsV3InsulinInventoryTimeline(LocalV3RetainedSnapshot.OverLimit(at)).read(1, at, at)
        assertEquals(AapsV3ObservedInsulinStatus.LIMIT_REACHED, limited.status)
    }

    @Test fun insulinWithConflictingDeliveryFieldsCannotLoseItsDuration() {
        val source = row(fields = buildJsonObject { put("insulin", 2.2); put("duration", 30) })
        assertEquals(AapsV3ObservedInsulinStatus.INVALID, read(source).status)
    }

    @Test fun invalidMetadataTimeAndUnitsNeverYieldPartialSuccess() {
        val source = row()
        for (bad in listOf(source.copy(source = "another-owner"), source.copy(payloadBytes = 1),
            source.copy(eventAtMs = at), source.copy(createdRevision = at + 10))) {
            assertEquals(AapsV3ObservedInsulinStatus.INVALID, read(bad).status)
        }
        assertEquals(AapsV3ObservedInsulinStatus.INVALID, read(source, from = at + 1).status)
        assertEquals(AapsV3ObservedInsulinStatus.INVALID, read(source, known = at - 1).status)
    }

    @Test fun recordsAreImmutableAndDefaultRenderingIsRedacted() {
        val result = read(row())
        assertThrows(UnsupportedOperationException::class.java) { (result.records as MutableList).clear() }
        assertFalse(result.toString().contains("2.2"))
        assertFalse(result.records.single().toString().contains("dose-1"))
        assertFalse(result.records.single().dose.toString().contains("historical"))
    }
}
