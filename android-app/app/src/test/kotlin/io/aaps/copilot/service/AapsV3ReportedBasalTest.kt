package io.aaps.copilot.service

import org.junit.Assert.*
import org.junit.Test

class AapsV3ReportedBasalTest {
    private val at = 1_800_000_000_000L
    private val from = at - 3_600_000
    private val revision = at - 1
    private fun profile(label: String, time: Long = from - 1): AapsV3EffectiveProfileObservation {
        val config = AapsV3InsulinConfiguration(label, 18_000_000, 4_500_000, 1.0)
        val values = listOf(AapsV3SchedulePoint("00:00", 0, 0.8))
        return AapsV3EffectiveProfileObservation(label, revision - 1, revision, time, 0, label, label,
            0, 100, 0, 0, AapsV3ProfileSnapshot("mmol", "Europe/Berlin", values, values, values, values,
                values, config, null), config)
    }
    private fun history(vararg intervals: AapsV3ProfileHistoryInterval) = AapsV3ProfileHistoryRead(
        AapsV3ObservedProfileStatus.OBSERVED, revision, intervals.toList().ifEmpty {
            listOf(AapsV3ProfileHistoryInterval(from, at, profile("historical")))
        })
    private fun record(id: String = "basal", start: Long = from, duration: Long = 3_600_000,
        dose: AapsV3InsulinDose = AapsV3InsulinDose.TempBasal(duration, 0.8, null),
        invalidated: Boolean = false) = AapsV3InsulinObservation(id, start, revision, revision - 1,
            revision, invalidated, "pump-identity-$id", 123, dose)
    private fun read(vararg records: AapsV3InsulinObservation, profiles: AapsV3ProfileHistoryRead = history(),
        insulinRevision: Long = revision) = AapsV3ReportedBasalProjector.read(from, at, at, profiles,
            AapsV3InsulinRead(AapsV3ObservedInsulinStatus.OBSERVED, insulinRevision, records.toList()))

    @Test fun clipsReportedAbsoluteRateWithoutCallingItCompleteDelivery() {
        val original = record(start = from - 3_600_000, duration = 10_800_000)
        val result = read(original)
        assertEquals(AapsV3ReportedBasalStatus.OBSERVED_WINDOW, result.status)
        val segment = result.segments.single()
        assertEquals(from, segment.startMs)
        assertEquals(at, segment.endMs)
        assertEquals(0.8, (segment.rate as AapsV3BasalRate.Absolute).unitsPerHour, 0.0)
        assertEquals(0.8, segment.reportedComponentUnits!!, 1e-12)
        assertEquals(original, segment.source)
        assertEquals(123L, segment.source.pumpEndId)
        assertTrue(result.gaps.isEmpty())
        assertFalse(result.upstreamCompletenessVerified)
    }

    @Test fun splitsAtHistoricalProfileChangesWithoutReplacingInsulinOrReapplyingModifiers() {
        val middle = from + 1_800_000
        val result = read(record(), profiles = history(
            AapsV3ProfileHistoryInterval(from, middle, profile("old insulin")),
            AapsV3ProfileHistoryInterval(middle, at, profile("new insulin", middle))))
        assertEquals(listOf("old insulin", "new insulin"), result.segments.map { it.profile.insulin.label })
        assertEquals(listOf(from, middle), result.segments.map { it.startMs })
        assertEquals(listOf(middle, at), result.segments.map { it.endMs })
        assertEquals(0.8, result.segments.sumOf { it.reportedComponentUnits!! }, 1e-12)
    }

    @Test fun reportsEveryGapRatherThanAssumingScheduledBasalWasDelivered() {
        val result = read(record("first", from + 300_000, 300_000), record("second", from + 1_200_000, 300_000))
        assertEquals(AapsV3ReportedBasalStatus.OBSERVED_WITH_GAPS, result.status)
        assertEquals(listOf(from, from + 600_000, from + 1_500_000), result.gaps.map { it.startMs })
        assertEquals(listOf(from + 300_000, from + 1_200_000, at), result.gaps.map { it.endMs })
        assertEquals(2, result.segments.size)
    }

    @Test fun percentageNeverBecomesUnitsUsingAProfileTimezoneOrConvertedStartScalar() {
        val dose = AapsV3InsulinDose.TempBasal(3_600_000, null, 50.0, reportedRateAtStart = 99.0)
        val segment = read(record(dose = dose)).segments.single()
        assertEquals(50.0, (segment.rate as AapsV3BasalRate.PercentAdjustment).percent, 0.0)
        assertNull(segment.reportedComponentUnits)
        assertEquals(99.0, (segment.source.dose as AapsV3InsulinDose.TempBasal).reportedRateAtStart!!, 0.0)
    }

    @Test fun emulatedExtendedIsOnlyAnAdditionalComponentNotTotalBasal() {
        val segment = read(record(start = from - 3_600_000,
            dose = AapsV3InsulinDose.Extended(1.2, 7_200_000, true))).segments.single()
        assertEquals(0.6, (segment.rate as AapsV3BasalRate.ExtendedAddition).unitsPerHour, 1e-12)
        assertEquals(0.6, segment.reportedComponentUnits!!, 1e-12)
    }

    @Test fun ordinaryExtendedAndBolusRemainOutsideTheBasalComponent() {
        val result = read(record("extended", dose = AapsV3InsulinDose.Extended(1.0, 3_600_000, false)),
            record("bolus", dose = AapsV3InsulinDose.Bolus(2.2, "SMB", false, null)))
        assertEquals(AapsV3ReportedBasalStatus.NOT_OBSERVED, result.status)
        assertTrue(result.segments.isEmpty())
        assertEquals(from, result.gaps.single().startMs)
        assertEquals(at, result.gaps.single().endMs)
    }

    @Test fun overlappingRecordsCannotBeTruncatedOrAddedTogetherSilently() {
        for (other in listOf(record("second", from + 1_800_000), record("extended", from + 1_800_000,
            dose = AapsV3InsulinDose.Extended(0.5, 1_800_000, true)))) {
            val result = read(record(), other)
            assertEquals(AapsV3ReportedBasalStatus.CONFLICTING, result.status)
            assertTrue(result.segments.isEmpty())
        }
    }

    @Test fun touchingRecordsAreDistinctWithoutAnInventedGap() {
        val result = read(record("first", duration = 1_800_000), record("second", from + 1_800_000, 1_800_000))
        assertEquals(AapsV3ReportedBasalStatus.OBSERVED_WINDOW, result.status)
        assertEquals(2, result.segments.size)
        assertTrue(result.gaps.isEmpty())
    }

    @Test fun invalidatedAndZeroDurationRecordsDoNotProveSuspensionOrNormalDelivery() {
        val result = read(record("deleted", invalidated = true), record("zero", duration = 0))
        assertEquals(AapsV3ReportedBasalStatus.NOT_OBSERVED, result.status)
        assertTrue(result.segments.isEmpty())
        assertEquals(1, result.gaps.size)
    }

    @Test fun explicitAbsoluteZeroIsRetainedWithItsNativeSuspensionType() {
        val dose = AapsV3InsulinDose.TempBasal(3_600_000, 0.0, null, type = AapsV3TempBasalType.PUMP_SUSPEND)
        val segment = read(record(dose = dose)).segments.single()
        assertEquals(0.0, segment.reportedComponentUnits!!, 0.0)
        assertEquals(AapsV3TempBasalType.PUMP_SUSPEND, (segment.source.dose as AapsV3InsulinDose.TempBasal).type)
    }

    @Test fun unsupportedBasalFormsCannotReturnAPartialUsableInventory() {
        for (dose in listOf(AapsV3InsulinDose.Bolus(1.0, "NORMAL", true, null),
            AapsV3InsulinDose.TempBasal(3_600_000, 0.8, null, type = AapsV3TempBasalType.FAKE_EXTENDED),
            AapsV3InsulinDose.Extended(1.0, 0, true))) {
            val result = read(record("known"), record("unsupported", dose = dose))
            assertEquals(AapsV3ReportedBasalStatus.UNSUPPORTED, result.status)
            assertTrue(result.segments.isEmpty())
        }
    }

    @Test fun profileAndInsulinMustBelongToTheSameSnapshot() {
        val result = read(record(), insulinRevision = revision - 1)
        assertEquals(AapsV3ReportedBasalStatus.REVISION_MISMATCH, result.status)
        assertTrue(result.segments.isEmpty())
    }

    @Test fun missingOrMalformedProfileWindowsCannotFillUnknownIntervals() {
        assertEquals(AapsV3ReportedBasalStatus.PROFILE_UNAVAILABLE, read(record(), profiles =
            AapsV3ProfileHistoryRead(AapsV3ObservedProfileStatus.INVALIDATED, revision)).status)
        assertEquals(AapsV3ReportedBasalStatus.INVALID, read(record(), profiles = history(
            AapsV3ProfileHistoryInterval(from + 1, at, profile("missing start")))).status)
    }

    @Test fun unavailableInsulinAndLateEvidenceNeverYieldAPartialInventory() {
        val source = AapsV3InsulinRead(AapsV3ObservedInsulinStatus.INVALID, revision)
        assertEquals(AapsV3ReportedBasalStatus.INPUT_UNAVAILABLE,
            AapsV3ReportedBasalProjector.read(from, at, at, history(), source).status)
        assertEquals(AapsV3ReportedBasalStatus.LATE_EVIDENCE, read(record().copy(availableAtMs = at + 1)).status)
    }

    @Test fun invalidTimesAndOverflowingQuantitiesAreRejected() {
        val source = AapsV3InsulinRead(AapsV3ObservedInsulinStatus.OBSERVED, revision)
        assertEquals(AapsV3ReportedBasalStatus.INVALID,
            AapsV3ReportedBasalProjector.read(at, at, at, history(), source).status)
        assertEquals(AapsV3ReportedBasalStatus.INVALID, read(record(dose =
            AapsV3InsulinDose.Extended(Double.MAX_VALUE, 1, true))).status)
        assertEquals(AapsV3ReportedBasalStatus.INVALID, read(record().copy(identifier = "")).status)
    }

    @Test fun recordBudgetDoesNotSilentlyTruncateTheLastSegment() {
        val records = (0..2880).map { record("row-$it", from + it, 1) }.toTypedArray()
        val result = read(*records)
        assertEquals(AapsV3ReportedBasalStatus.LIMIT_REACHED, result.status)
        assertTrue(result.segments.isEmpty())
    }

    @Test fun resultListsAreImmutableAndDefaultRenderingIsRedacted() {
        val result = read(record("private-record", from + 60_000, 60_000))
        assertThrows(UnsupportedOperationException::class.java) { (result.segments as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.gaps as MutableList).clear() }
        assertFalse(result.toString().contains("private-record"))
        assertFalse(result.segments.single().toString().contains("private-record"))
        assertFalse(result.segments.single().rate.toString().contains("0.8"))
    }

    @Test fun duplicatePumpIdentityCannotBecomeTwoNonOverlappingDeliveries() {
        val first = record("first", duration = 1_800_000)
        val second = record("second", from + 1_800_000, 1_800_000).copy(pumpRecordIdentity = first.pumpRecordIdentity)
        assertEquals(AapsV3ReportedBasalStatus.CONFLICTING, read(first, second).status)
    }

    @Test fun gapRenderingDoesNotExposeEpisodeTimes() {
        assertFalse(read().gaps.single().toString().contains(from.toString()))
    }
}
