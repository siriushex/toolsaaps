package io.aaps.copilot.service

import io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity
import io.aaps.copilot.data.repository.LocalV3RetainedSnapshot
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AapsV3ProfileHistoryTest {
    private val at = 1_800_000_000_000L
    private fun row(id: String, time: Long, revision: Long = at - 1,
        deleted: Boolean = false, zone: String = "Europe/Berlin"): LocalNightscoutV3DocumentEntity {
        val body = buildJsonObject {
            put("eventType", "Note"); put("date", time); put("utcOffset", 120)
            put("originalProfileName", id); put("originalCustomizedName", id)
            put("originalTimeshift", -3_600_000); put("originalPercentage", 120)
            put("originalDuration", 1000); put("originalEnd", time + 1000)
            put("icfg", buildJsonObject {
                put("insulinLabel", id); put("insulinEndTime", 18_000_000)
                put("insulinPeakTime", 4_500_000); put("concentration", 1.0)
            })
            put("profileJson", buildJsonObject {
                put("units", "mmol"); put("timezone", zone)
                listOf("carbratio", "sens", "basal", "target_low", "target_high").forEach { key ->
                    put(key, buildJsonArray {
                        add(buildJsonObject { put("time", "00:00"); put("timeAsSeconds", 0); put("value", 0.8) })
                        add(buildJsonObject { put("time", "06:00"); put("timeAsSeconds", 23401); put("value", 1.2) })
                    })
                }
            }.toString())
        }.toString()
        return LocalNightscoutV3DocumentEntity("treatments", id, "local-v3-owner", time, body,
            body.toByteArray().size, at - 1000, revision, deleted, false)
    }
    private fun timeline(vararg rows: LocalNightscoutV3DocumentEntity) = AapsV3EffectiveProfileTimeline(
        LocalV3RetainedSnapshot.Complete(rows.maxOfOrNull { it.modifiedRevision } ?: 0, rows.toList()))

    @Test fun coversTheHalfOpenWindowWithTheActualHistoricalProfiles() {
        val result = timeline(row("old", at - 60_000), row("new", at + 10_000))
            .readWindow(at, at + 20_000, at + 20_000)
        assertEquals(AapsV3ObservedProfileStatus.OBSERVED, result.status)
        assertEquals(listOf(at, at + 10_000), result.intervals.map { it.startMs })
        assertEquals(listOf(at + 10_000, at + 20_000), result.intervals.map { it.endMs })
        assertEquals(listOf("old", "new"), result.intervals.map { it.observation.insulin.label })
        assertEquals(23401, result.intervals.first().observation.profile.basal.last().seconds)
        assertFalse(result.upstreamCompletenessVerified)
    }

    @Test fun exactStartSelectsNewProfileAndExactEndDoesNotCreateAnEmptyInterval() {
        val result = timeline(row("old", at - 1), row("start", at), row("end", at + 10_000))
            .readWindow(at, at + 10_000, at + 10_000)
        assertEquals("start", result.intervals.single().observation.identifier)
        assertEquals(at + 10_000, result.intervals.single().endMs)
    }

    @Test fun missingAnchorNeverFillsTheBeginningWithTodaysProfile() {
        val result = timeline(row("later", at + 1)).readWindow(at, at + 10_000, at + 10_000)
        assertEquals(AapsV3ObservedProfileStatus.NOT_OBSERVED, result.status)
        assertTrue(result.intervals.isEmpty())
    }

    @Test fun invalidatedOrConflictingChangeCannotReturnAPartialWindow() {
        for (rows in listOf(arrayOf(row("old", at - 1), row("deleted", at + 1, deleted = true)),
            arrayOf(row("old", at - 1), row("first", at + 1), row("second", at + 1)))) {
            val result = timeline(*rows).readWindow(at, at + 10_000, at + 10_000)
            assertTrue(result.status in setOf(AapsV3ObservedProfileStatus.INVALIDATED, AapsV3ObservedProfileStatus.CONFLICTING))
            assertTrue(result.intervals.isEmpty())
        }
    }

    @Test fun lateCollectionRevisionCannotMasqueradeAsAnAsOfArchive() {
        val result = timeline(row("old", at - 60_000), row("moved", at + 100_000, at + 100_000))
            .readWindow(at - 30_000, at, at)
        assertEquals(AapsV3ObservedProfileStatus.LATE_EVIDENCE, result.status)
        assertTrue(result.intervals.isEmpty())
    }

    @Test fun queryLimitsAndOverLimitSnapshotsDoNotReturnTruncatedHistory() {
        val view = timeline(row("old", at - 1))
        assertEquals(AapsV3ObservedProfileStatus.INVALID, view.readWindow(at, at, at).status)
        assertEquals(AapsV3ObservedProfileStatus.INVALID, view.readWindow(at, at + 1, at).status)
        assertEquals(AapsV3ObservedProfileStatus.LIMIT_REACHED, view.readWindow(at, at + 172_800_001, at + 172_800_001).status)
        assertEquals(AapsV3ObservedProfileStatus.LIMIT_REACHED, AapsV3EffectiveProfileTimeline(
            LocalV3RetainedSnapshot.OverLimit(at)).readWindow(at - 1, at, at).status)
    }

    @Test fun excessiveProfileChangesRejectRatherThanTruncateTheLastInterval() {
        val rows = (0..2880).map { row("change-$it", at + it) }.toTypedArray()
        val result = timeline(*rows).readWindow(at, at + 2881, at + 2881)
        assertEquals(AapsV3ObservedProfileStatus.LIMIT_REACHED, result.status)
        assertTrue(result.intervals.isEmpty())
    }

    @Test fun invalidMetadataAndMalformedChangesDoNotBecomeScheduleGaps() {
        for (bad in listOf(row("bad", at + 1).copy(source = "another-owner"),
            row("bad", at + 1).copy(payloadJson = "{}"))) {
            val result = timeline(row("old", at - 1), bad).readWindow(at, at + 10_000, at + 10_000)
            assertEquals(AapsV3ObservedProfileStatus.INVALID, result.status)
            assertTrue(result.intervals.isEmpty())
        }
    }

    @Test fun profileTimezoneAndModifiersRemainProvenanceNotNewScheduleTransformations() {
        val result = timeline(row("old", at - 1, zone = "America/New_York"))
            .readWindow(at, at + 60_000, at + 60_000).intervals.single().observation
        assertEquals("America/New_York", result.profile.timezone)
        assertEquals(120, result.originalPercentage)
        assertEquals(-3_600_000L, result.originalTimeshiftMs)
        assertEquals(0.8, result.profile.basal.first().value, 0.0)
    }

    @Test fun immutableWindowRenderingDoesNotLeakProfileNames() {
        val result = timeline(row("private-profile", at - 1)).readWindow(at, at + 1, at + 1)
        assertThrows(UnsupportedOperationException::class.java) { (result.intervals as MutableList).clear() }
        assertFalse(result.toString().contains("private-profile"))
        assertFalse(result.intervals.single().toString().contains("private-profile"))
    }
}
