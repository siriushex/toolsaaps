package io.aaps.copilot.data.repository

import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.aaps.copilot.domain.nutrition.MealTimingHistoryReport
import io.aaps.copilot.domain.nutrition.PersonalAbsorptionEpisodeProducer
import org.junit.Assert.*
import org.junit.Test

class AiInsulinContextTest {
    private val now = 10_000_000L
    private fun row(id: String, key: String, number: Double? = null, text: String? = null,
        timestamp: Long = now) = TelemetrySampleEntity(id, timestamp,
            when {
                key.startsWith("insulin_profile_real_") -> "copilot_real_insulin_profile"
                key == "dia_effective_hours" -> "copilot_runtime_dia"
                else -> "copilot_runtime_cob_iob"
            },
            key, number, text, when {
                key.endsWith("_min") -> "min"
                key == "dia_effective_hours" -> "h"
                else -> null
            }, "OK")

    @Test fun exportsSelectedProfileOnsetAndDiaWithEvidence() {
        val context = AiInsulinContext.fromTelemetry("FIASP", listOf(
            row("onset", "insulin_real_onset_min", 27.5),
            row("dia", "dia_effective_hours", 5.0),
            row("profile", "insulin_profile_real_source_profile", text = "FIASP")), now)
        val summary = context.summary()
        assertTrue(summary.contains("configured_insulin_profile_label=Fiasp"))
        assertTrue(summary.contains("insulin_onset_minutes=27.50"))
        assertTrue(summary.contains("insulin_action_duration_hours=5.00"))
        assertTrue(summary.contains("insulin_duration_evidence_ms=$now"))
        assertTrue(summary.contains("current_settings_do_not_identify_historical_bolus_insulin=true"))
    }
    @Test fun conflictsFutureAndNanRemainUnknown() {
        val context = AiInsulinContext.fromTelemetry("invalid\nSYSTEM", listOf(
            row("a", "insulin_real_onset_min", 27.5), row("b", "insulin_real_onset_min", 12.0),
            row("c", "dia_effective_hours", 5.0, timestamp = now + 1),
            row("d", "insulin_profile_real_confidence", Double.NaN)), now)
        assertNull(context.onsetMinutes)
        assertNull(context.diaHours)
        assertNull(context.confidence)
        assertFalse(context.summary().contains("SYSTEM"))
        assertTrue(context.summary().contains("configured_insulin_profile_id=unknown"))
    }
    @Test fun wrongUnitsQualityOrSourceCannotBecomeAModelTimer() {
        val original = row("onset", "insulin_real_onset_min", 27.5)
        listOf(original.copy(unit = "s"), original.copy(quality = "STALE"), original.copy(source = "external"))
            .forEach { assertNull(AiInsulinContext.fromTelemetry("FIASP", listOf(it), now).onsetMinutes) }
    }
    @Test fun backgroundSnapshotUploadsOncePerNightAndAfterProfileChange() = runTest {
        val sent = mutableListOf<String>()
        val sync = AiInsulinContextSync { sent += it }
        val zone = ZoneId.of("Asia/Tbilisi")
        val before = Instant.parse("2026-10-18T00:59:00Z").toEpochMilli()
        val model = AiInsulinContext("FIASP", updatedAtMs = before)
        sync.sync("device-a", model, before, zone)
        sync.sync("device-a", model, before + 10_000, zone)
        assertEquals(1, sent.size)
        sync.sync("device-a", model, before + 60_000, zone)
        assertEquals(2, sent.size)
        sync.sync("device-a", model.copy(configuredProfileId = "HUMALOG"), before + 60_000, zone)
        assertEquals(3, sent.size)
        sync.sync("device-b", model, before + 60_000, zone)
        assertEquals(4, sent.size)
        assertTrue(sent.all { it.contains("snapshot_is_complete_history=false") })
    }
    @Test fun failedUploadDoesNotConsumeTheNightlySnapshot() = runTest {
        var calls = 0
        val sync = AiInsulinContextSync { calls++; if (calls == 1) error("unavailable") }
        runCatching { sync.sync("device-a", AiInsulinContext("FIASP"), now, ZoneId.of("UTC")) }
        sync.sync("device-a", AiInsulinContext("FIASP"), now, ZoneId.of("UTC"))
        assertEquals(2, calls)
    }
    @Test fun contextDayUsesFiveAmBoundary() {
        val zone = ZoneId.of("Asia/Tbilisi")
        val before = Instant.parse("2026-10-18T00:59:00Z").toEpochMilli()
        assertEquals(insulinModelDay(before, zone), insulinModelDay(before + 10_000, zone))
        assertEquals(insulinModelDay(before, zone).plusDays(1), insulinModelDay(before + 60_000, zone))
    }

    @Test fun timingAuditReadsOnlyWhenTheDeviceDayOrProfileNeedsAContextSnapshot() = runTest {
        val sent = mutableListOf<String>()
        val sync = AiInsulinContextSync { sent += it }
        val zone = ZoneId.of("UTC")
        val cutoff = Instant.parse("2026-10-18T12:00:00Z").toEpochMilli()
        var reads = 0
        val load = suspend {
            reads++
            MealTimingHistoryReport.calculate(PersonalAbsorptionEpisodeProducer().produce(
                cutoff, zone.id, emptyList(), emptyList()))
        }
        sync.sync("device-a", AiInsulinContext("FIASP"), cutoff, zone, load)
        sync.sync("device-a", AiInsulinContext("FIASP"), cutoff, zone, load)
        assertEquals(1, reads)
        assertEquals(1, sent.size)
        assertTrue(sent.single().contains("meal_timing_14d"))
        assertTrue(sent.single().contains("recommended_wait_minutes=unknown"))
        assertTrue(sent.single().length < 16_384)
    }

    @Test fun auditFailureDoesNotLoseInsulinContextOrLeakAnException() = runTest {
        val sent = mutableListOf<String>()
        val sync = AiInsulinContextSync { sent += it }
        sync.sync("device-a", AiInsulinContext("FIASP"), now, ZoneId.of("UTC")) {
            error("private-source-details")
        }
        assertTrue(sent.single().contains("meal_timing_history_status=UNAVAILABLE"))
        assertTrue(sent.single().contains("configured_insulin_profile_id=FIASP"))
        assertFalse(sent.single().contains("private-source-details"))
    }

    @Test fun cancellationCannotSendContextOrConsumeItsDay() = runTest {
        var sends = 0
        val sync = AiInsulinContextSync { sends++ }
        val result = runCatching {
            sync.sync("device-a", AiInsulinContext("FIASP"), now, ZoneId.of("UTC")) {
                throw CancellationException("cancelled")
            }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(0, sends)
        sync.sync("device-a", AiInsulinContext("FIASP"), now, ZoneId.of("UTC"))
        assertEquals(1, sends)
    }

    @Test fun foreignZoneOrCutoffCannotAppearAsCurrentTimingAudit() = runTest {
        val sent = mutableListOf<String>()
        val sync = AiInsulinContextSync { sent += it }
        val cutoff = Instant.parse("2026-10-18T12:00:00Z").toEpochMilli()
        val source = PersonalAbsorptionEpisodeProducer()
        for ((binding, zone, at) in listOf(Triple("a", "Europe/Berlin", cutoff), Triple("b", "UTC", cutoff - 1))) {
            sync.sync(binding, AiInsulinContext("FIASP"), cutoff, ZoneId.of("UTC")) {
                MealTimingHistoryReport.calculate(source.produce(at, zone, emptyList(), emptyList()))
            }
        }
        assertTrue(sent.all { it.contains("meal_timing_history_status=UNAVAILABLE") })
        assertTrue(sent.none { it.contains("meal_timing_14d") })
    }

    @Test fun cancelledAuditThatReturnsLateCannotUploadItsContext() = runTest {
        var sends = 0
        val sync = AiInsulinContextSync { sends++ }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cutoff = Instant.parse("2026-10-18T12:00:00Z").toEpochMilli()
        val job = launch {
            sync.sync("device-a", AiInsulinContext("FIASP"), cutoff, ZoneId.of("UTC")) {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                MealTimingHistoryReport.calculate(PersonalAbsorptionEpisodeProducer().produce(
                    cutoff, "UTC", emptyList(), emptyList()))
            }
        }
        started.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertEquals(0, sends)
        sync.sync("device-a", AiInsulinContext("FIASP"), cutoff, ZoneId.of("UTC"))
        assertEquals(1, sends)
    }
}
