package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.aaps.copilot.data.local.entity.EatingWindowSnapshotEntity
import io.aaps.copilot.domain.eating.EatingEvidencePoint
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EatingWindowSnapshotRepositoryTest {

    private val zone: ZoneId = ZoneOffset.ofHours(4)
    private val generatedAt = ZonedDateTime.of(2026, 8, 2, 3, 15, 0, 0, zone)
        .toInstant()
        .toEpochMilli()

    @Test
    fun refreshPublishesOneDualHorizonSnapshotForCompletedLocalDate() = runTest {
        val persisted = mutableMapOf<String, EatingWindowSnapshotEntity>()
        var sourceReads = 0
        val repository = EatingWindowSnapshotRepository(
            gson = Gson(),
            loadEvidence = { _, _ ->
                sourceReads += 1
                buildList {
                    repeat(14) { offset -> add(point(offset, 12, 0, 25.0)) }
                    repeat(5) { offset -> add(point(offset, 20, 30, 35.0)) }
                }
            },
            loadSnapshot = persisted::get,
            storeSnapshot = { persisted[it.localCompletedDate] = it }
        )

        val first = repository.refresh(generatedAt, zone)
        val repeated = repository.refresh(generatedAt + 30 * 60 * 1000, zone)

        assertThat(first.localCompletedDate).isEqualTo("2026-08-01")
        assertThat(first.snapshot.recent.lookbackDays).isEqualTo(7)
        assertThat(first.snapshot.stable.lookbackDays).isEqualTo(14)
        assertThat(first.snapshot.recent.windows.map { it.medianMinuteOfDay })
            .containsExactly(12 * 60, 20 * 60 + 30)
            .inOrder()
        assertThat(repeated).isEqualTo(first)
        assertThat(sourceReads).isEqualTo(1)
        assertThat(persisted).hasSize(1)
    }

    @Test
    fun malformedPersistedWindowJsonFailsClosedToEmptyWindows() {
        val repository = EatingWindowSnapshotRepository(
            gson = Gson(),
            loadEvidence = { _, _ -> emptyList() },
            loadSnapshot = { null },
            storeSnapshot = {}
        )

        val decoded = repository.decode(
            EatingWindowSnapshotEntity(
                localCompletedDate = "2026-08-01",
                generatedAt = generatedAt,
                sourceFingerprint = "fingerprint",
                recentWindowsJson = "{not-json",
                stableWindowsJson = "[not-json"
            )
        )

        assertThat(decoded.snapshot.recent.windows).isEmpty()
        assertThat(decoded.snapshot.stable.windows).isEmpty()
    }

    private fun point(dayOffset: Int, hour: Int, minute: Int, carbs: Double): EatingEvidencePoint {
        val date = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(generatedAt),
            zone
        ).toLocalDate().minusDays(1L + dayOffset)
        return EatingEvidencePoint(
            ts = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli(),
            carbsG = carbs,
            syntheticUam = false
        )
    }
}
