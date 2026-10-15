package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.pump.InfusionSetCondition
import io.aaps.copilot.domain.pump.InfusionSetEvidence
import io.aaps.copilot.domain.pump.InfusionSetPolicy
import org.junit.Assert.*
import org.junit.Test

class InfusionSetEvidenceReaderTest {
    private val start = 1_800_000_000_000L
    private fun row(ts: Long = start, json: String = "\"date\":\"$ts\"") =
        TherapyEventEntity("site-entry", ts, "infusion_set_change",
            "{\"source\":\"nightscout_treatment\",$json}")
    private fun read(vararg rows: TherapyEventEntity) = InfusionSetEvidenceReader.read(rows.toList(), start + 1000)

    @Test fun originalTimestampEstablishesAgeWithoutReceiptRenewal() {
        val evidence = read(row())!!
        assertEquals(start, evidence.occurrenceTs)
        assertEquals("nightscout_treatment", evidence.source)
        assertEquals(evidence.generation, read(row().copy(id = "reimport-entry"))!!.generation)
        assertNull(read(row().copy(timestamp = start + 1000)))
    }

    @Test fun missingOriginalDateAndAgeOnlyPacketsRemainUnknown() {
        listOf("\"cage\":3", "\"cage_days\":3", "\"cage_hours\":72", "\"notes\":\"site\"")
            .forEach { assertNull(read(row(json = it))) }
        assertNull(read())
    }

    @Test fun explicitIsoAndSecondsAreParsedWithoutNowFallback() {
        val iso = java.time.Instant.ofEpochMilli(start).toString()
        assertEquals(start, read(row(json = "\"createdAt\":\"$iso\""))!!.occurrenceTs)
        assertEquals(start, read(row(json = "\"mills\":${start / 1000}"))!!.occurrenceTs)
    }

    @Test fun conflictingInvalidOrFutureDatesFailClosed() {
        listOf("\"date\":$start,\"mills\":${start - 1000}", "\"date\":1.5",
            "\"date\":\"NaN\"", "\"date\":9223372036854775808", "\"date\":-1",
            "\"createdAt\":\"invalid\"", "\"date\":${start + 2000}")
            .forEach { assertNull(read(row(json = it))) }
    }

    @Test fun latestDeletedOrInvalidMarkerCannotExposeOlderNormalAge() {
        val old = row(start - 1000)
        assertNull(read(old, row(json = "\"date\":$start,\"isValid\":\"false\"")))
        assertNull(read(old, row(json = "\"date\":$start,\"deleted\":true")))
        assertNull(read(old, row(json = "\"date\":$start,\"isValid\":\"invalid\"")))
    }

    @Test fun duplicateTombstoneConflictsButDuplicateImportsDoNotRenew() {
        val valid = row()
        assertEquals(read(valid), read(valid, valid.copy(id = "second-entry")))
        assertNull(read(valid, row(json = "\"date\":$start,\"isValid\":false")))
    }

    @Test fun unrelatedMaintenanceDoesNotResetAge() {
        listOf("sensor_change", "insulin_refill", "pump_battery_change", "prime", "reservoir_change")
            .forEach { type -> assertEquals(read(row()), read(row(), row(start + 1000).copy(type = type))) }
    }

    @Test fun echoesInferencesAndUnknownSourceCannotEstablishAChange() {
        assertNull(read(row().copy(id = "copilot-site-entry")))
        listOf("\"date\":$start,\"enteredBy\":\"Copilot\"", "\"date\":$start,\"inferred\":true",
            "\"date\":$start,\"classification\":\"SYNTHETIC\"").forEach { assertNull(read(row(json = it))) }
        assertNull(read(row().copy(payloadJson = "{\"source\":\"local_broadcast\",\"cage\":0}")))
    }

    @Test fun overflowDoesNotSelectAnArbitraryLatestMarker() {
        assertNull(InfusionSetEvidenceReader.read(List(33) { row().copy(id = "entry-$it") }, start + 1000))
    }

    @Test fun correctionChangesOccurrenceGeneration() {
        assertNotEquals(read(row())!!.generation, read(row(start - 1000))!!.generation)
    }

    @Test fun exactlySeventyTwoElapsedHoursIsDueRegardlessOfZone() {
        val evidence = read(row())!!
        val due = start + 72 * 3_600_000L
        assertEquals(InfusionSetCondition.NOT_DUE, InfusionSetPolicy.evaluate(due - 1, evidence).condition)
        assertEquals(InfusionSetCondition.DUE, InfusionSetPolicy.evaluate(due, evidence).condition)
        assertEquals(due, InfusionSetPolicy.evaluate(due, evidence).dueTs)
        assertEquals(72 * 3_600_000L, InfusionSetPolicy.evaluate(due, evidence).ageMs)
    }

    @Test fun unknownFutureAndArithmeticOverflowAreNotNormal() {
        assertEquals(InfusionSetCondition.UNKNOWN, InfusionSetPolicy.evaluate(start, null).condition)
        assertEquals(InfusionSetCondition.UNKNOWN, InfusionSetPolicy.evaluate(start - 1, read(row())).condition)
        assertEquals(InfusionSetCondition.UNKNOWN, InfusionSetPolicy.evaluate(Long.MAX_VALUE,
            InfusionSetEvidence(Long.MAX_VALUE - 1, "infusion-old", "nightscout_treatment")).condition)
    }
}
