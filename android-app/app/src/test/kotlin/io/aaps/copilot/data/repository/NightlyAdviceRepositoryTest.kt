package io.aaps.copilot.data.repository

import com.google.gson.Gson
import io.aaps.copilot.data.local.dao.ClinicalGlucoseProjection
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NightlyAdviceRepositoryTest {
    private val zone = ZoneId.of("Asia/Tbilisi")
    private val now = ZonedDateTime.parse("2026-10-17T06:00:00+04:00[Asia/Tbilisi]").toInstant().toEpochMilli()
    private val gson = Gson()

    @Test fun duplicateRunAndProcessRestartReuseTheCommittedNight() = runTest {
        var saved: TelemetrySampleEntity? = null
        var reads = 0
        fun repository() = NightlyAdviceRepository(gson,
            loadRows = { _, _ -> reads++; emptyList() },
            loadSnapshot = { saved }, storeSnapshot = { saved = it })
        val first = repository().refreshIfDue(now, zone)
        assertEquals(first, repository().refreshIfDue(now + 60_000, zone))
        assertEquals(1, reads)
        assertNotNull(saved)
        assertEquals(now, saved!!.timestamp)
        assertNotEquals(first.throughTs, first.generatedAt)
        repository().refreshIfDue(now + 86_400_000, zone)
        assertEquals(2, reads)
    }

    @Test fun failedReadAndCancellationKeepThePreviousSnapshot() = runTest {
        val old = TelemetrySampleEntity("old", now - 86_400_000, "local", "old", null, "old", null, "OK")
        for (failure in listOf(IllegalStateException("read"), CancellationException("cancel"))) {
            var stored = false
            val repo = NightlyAdviceRepository(gson,
                loadRows = { _, _ -> throw failure }, loadSnapshot = { old }, storeSnapshot = { stored = true })
            val result = runCatching { repo.refreshIfDue(now, zone) }
            val thrown = result.exceptionOrNull()
            assertNotNull(thrown)
            assertEquals(failure.javaClass, thrown!!.javaClass)
            assertTrue(generateSequence(thrown) { it.cause }.any { it === failure })
            assertFalse(stored)
        }
    }

    @Test fun malformedOrOversizedSnapshotCannotBecomeAdvice() = runTest {
        val report = NightlyAdviceCalculator.calculate(now - 3_600_000, now, zone, emptyList())
        val repo = NightlyAdviceRepository(gson, { _, _ -> emptyList() }, { null }, {})
        assertNull(repo.decode("{bad"))
        assertNull(repo.decode(" ".repeat(65_537)))
        assertNull(repo.decode(gson.toJson(report).replace("\"days\":3", "\"days\":300")))
        assertEquals(report, repo.decode(gson.toJson(report)))
    }

    @Test fun sourceOverTheBoundDoesNotPublishAPartialReport() = runTest {
        var stored = false
        val repo = NightlyAdviceRepository(gson, { _, _ ->
            List(NightlyAdviceRepository.MAX_ROWS + 1) { ClinicalGlucoseProjection(now, 6.0, "xdrip", "OK") }
        }, { null }, { stored = true })
        assertTrue(runCatching { repo.refreshIfDue(now, zone) }.isFailure)
        assertFalse(stored)
    }

    @Test fun incompleteMetricsAndImpossibleComparisonAreNotValidSavedEvidence() {
        val end = now - 3_600_000
        val report = NightlyAdviceCalculator.calculate(end, now, zone, emptyList())
        val repo = NightlyAdviceRepository(gson, { _, _ -> emptyList() }, { null }, {})
        val missing = report.copy(windows = report.windows.map {
            it.copy(qualityDays = it.days, coveragePct = 100.0, inRangePct = null)
        })
        assertNull(repo.decode(gson.toJson(missing)))
        val overlapping = report.copy(windows = report.windows.map { it.copy(recentDeltaPp = 10.0) })
        assertNull(repo.decode(gson.toJson(overlapping)))
    }

    @Test fun savedObservationIsAvailableWithoutAProviderOrTherapySetting() = runTest {
        var saved: TelemetrySampleEntity? = null
        val writer = NightlyAdviceRepository(gson, { _, _ -> emptyList() }, { null }, { saved = it })
        val report = writer.refreshIfDue(now, zone)
        val reader = NightlyAdviceRepository(gson, { _, _ -> error("not a refresh") }, { saved }, {}, flowOf(saved))
        assertEquals(report, reader.snapshots.first())
    }

    @Test fun anOlderRunCannotOverwriteANewerCommittedNight() = runTest {
        var saved: TelemetrySampleEntity? = null
        val repo = NightlyAdviceRepository(gson, { _, _ -> emptyList() }, { saved }, { saved = it })
        val newer = repo.refreshIfDue(now + 86_400_000L, zone)
        assertTrue(runCatching { repo.refreshIfDue(now, zone) }.isFailure)
        assertEquals(newer.generatedAt, saved!!.timestamp)
    }

    @Test fun anOlderTimezoneRequestCannotOverwriteANewerCommittedNight() = runTest {
        var saved: TelemetrySampleEntity? = null
        val repo = NightlyAdviceRepository(gson, { _, _ -> emptyList() }, { saved }, { saved = it })
        val newer = repo.refreshIfDue(now + 86_400_000L, zone)
        assertTrue(runCatching { repo.refreshIfDue(now, ZoneId.of("UTC")) }.isFailure)
        assertEquals(newer.generatedAt, saved!!.timestamp)
    }

    @Test fun repeatedUnchangedRoomRowsDoNotRepublishAdvice() = runTest {
        var saved: TelemetrySampleEntity? = null
        NightlyAdviceRepository(gson, { _, _ -> emptyList() }, { null }, { saved = it }).refreshIfDue(now, zone)
        val repo = NightlyAdviceRepository(gson, { _, _ -> emptyList() }, { saved }, {}, flowOf(saved, saved))
        assertEquals(1, repo.snapshots.toList().size)
    }
}
