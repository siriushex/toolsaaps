package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.predict.UamExportMode
import io.aaps.copilot.domain.predict.UamInferenceEvent
import io.aaps.copilot.domain.predict.UamInferenceState
import io.aaps.copilot.domain.predict.UamMode
import io.aaps.copilot.domain.predict.UamTagCodec
import kotlinx.coroutines.runBlocking
import org.junit.Test

class UamExportCoordinatorTest {

    @Test
    fun dedupExportWhenRemoteAlreadyHasSameIdSeq() = runBlocking {
        val gateway = FakeGateway(
            fetched = listOf(
                AapsCarbEntry(
                    remoteId = "r1",
                    tsMs = 1_760_000_000_000L - 20 * 60_000L,
                    grams = 18.0,
                    note = UamTagCodec.buildTag("evt-1", 1, UamMode.NORMAL)
                )
            )
        )
        val coordinator = UamExportCoordinator(gateway = gateway)
        val event = confirmedEvent(
            id = "evt-1",
            nowTs = 1_760_000_000_000L,
            carbsDisplay = 25.0,
            exported = 0.0,
            seq = 0
        )

        val out = coordinator.process(
            nowTs = 1_760_000_000_000L,
            events = listOf(event),
            config = defaultConfig(exportMode = UamExportMode.CONFIRMED_ONLY)
        )

        assertThat(gateway.posts).isEmpty()
        assertThat(out.events.single().exportSeq).isEqualTo(1)
        assertThat(out.events.single().exportedGrams).isAtLeast(18.0)
    }

    @Test
    fun incrementalExportSendsInitialThenDelta() = runBlocking {
        val nowTs = 1_760_000_000_000L
        val gateway = FakeGateway()
        val coordinator = UamExportCoordinator(gateway = gateway)
        val initial = confirmedEvent(
            id = "evt-2",
            nowTs = nowTs,
            carbsDisplay = 30.0,
            exported = 0.0,
            seq = 0
        )

        val first = coordinator.process(
            nowTs = nowTs,
            events = listOf(initial),
            config = defaultConfig(exportMode = UamExportMode.INCREMENTAL)
        )
        val afterFirst = first.events.single()
        val second = coordinator.process(
            nowTs = nowTs + 11 * 60_000L,
            events = listOf(afterFirst.copy(carbsDisplayG = 30.0)),
            config = defaultConfig(exportMode = UamExportMode.INCREMENTAL)
        )

        assertThat(gateway.posts).hasSize(2)
        assertThat(gateway.posts[0].grams).isEqualTo(15.0)
        assertThat(gateway.posts[0].note).contains("seq=1")
        assertThat(gateway.posts[1].grams).isEqualTo(15.0)
        assertThat(gateway.posts[1].note).contains("seq=2")
        assertThat(second.events.single().exportedGrams).isEqualTo(30.0)
    }

    @Test
    fun backdateClampUsesExportMaxBackdate() = runBlocking {
        val nowTs = 1_760_000_000_000L
        val gateway = FakeGateway()
        val coordinator = UamExportCoordinator(gateway = gateway)
        val event = confirmedEvent(
            id = "evt-3",
            nowTs = nowTs,
            carbsDisplay = 20.0,
            exported = 0.0,
            seq = 0,
            ingestionTs = nowTs - 10 * 60 * 60_000L
        )

        coordinator.process(
            nowTs = nowTs,
            events = listOf(event),
            config = defaultConfig(
                exportMode = UamExportMode.CONFIRMED_ONLY,
                exportMaxBackdateMin = 180
            )
        )

        assertThat(gateway.posts).hasSize(1)
        val expectedMinTs = nowTs - 180 * 60_000L
        assertThat(gateway.posts.single().tsMs).isEqualTo(expectedMinTs)
    }

    private fun defaultConfig(
        exportMode: UamExportMode,
        exportMaxBackdateMin: Int = 180
    ) = UamExportCoordinator.Config(
        enableUamExportToAaps = true,
        exportMode = exportMode,
        dryRunExport = false,
        minSnackG = 15,
        snackStepG = 5,
        exportMinIntervalMin = 10,
        exportMaxBackdateMin = exportMaxBackdateMin
    )

    private fun confirmedEvent(
        id: String,
        nowTs: Long,
        carbsDisplay: Double,
        exported: Double,
        seq: Int,
        ingestionTs: Long = nowTs - 25 * 60_000L
    ) = UamInferenceEvent(
        id = id,
        state = UamInferenceState.CONFIRMED,
        mode = UamMode.NORMAL,
        createdAt = nowTs - 30 * 60_000L,
        updatedAt = nowTs,
        ingestionTs = ingestionTs,
        carbsModelG = carbsDisplay,
        carbsDisplayG = carbsDisplay,
        confidence = 0.8,
        exportedGrams = exported,
        exportSeq = seq,
        lastExportTs = if (seq > 0) nowTs else null,
        learnedEligible = false
    )

    private class FakeGateway(
        var fetched: List<AapsCarbEntry> = emptyList()
    ) : AapsCarbGateway {

        val posts = mutableListOf<PostCall>()

        override suspend fun postCarbEntry(tsMs: Long, grams: Double, note: String): Result<String> {
            posts += PostCall(tsMs = tsMs, grams = grams, note = note)
            return Result.success("remote-${posts.size}")
        }

        override suspend fun fetchCarbEntries(sinceTsMs: Long): Result<List<AapsCarbEntry>> {
            return Result.success(fetched)
        }
    }

    private data class PostCall(
        val tsMs: Long,
        val grams: Double,
        val note: String
    )
}
