package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.dao.TelemetrySampleLite
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TelemetryPagingTest {

    @Test
    fun scanTelemetryPages_preservesOrderAcrossPageBoundaries() = runTest {
        val rows = listOf(
            TestRow(id = "a", timestamp = 1000L),
            TestRow(id = "b", timestamp = 1000L),
            TestRow(id = "c", timestamp = 1001L),
            TestRow(id = "d", timestamp = 1001L),
            TestRow(id = "e", timestamp = 1002L)
        )
        val seen = mutableListOf<TestRow>()

        val stats = scanTelemetryPages(
            since = 1000L,
            pageSize = 2,
            fetchPage = { afterTimestamp, afterId, pageSize ->
                rows.filter { row ->
                    row.timestamp >= 1000L &&
                        (row.timestamp > afterTimestamp || (row.timestamp == afterTimestamp && row.id > afterId))
                }.sortedWith(compareBy<TestRow> { it.timestamp }.thenBy { it.id })
                    .take(pageSize)
            },
            getTimestamp = { it.timestamp },
            getId = { it.id }
        ) { page ->
            seen += page
        }

        assertThat(seen.map { it.id }).containsExactly("a", "b", "c", "d", "e").inOrder()
        assertThat(stats.pageCount).isEqualTo(3)
        assertThat(stats.rowCount).isEqualTo(5)
    }

    @Test
    fun scanTelemetryPages_handlesEmptyResult() = runTest {
        val stats = scanTelemetryPages(
            since = 1000L,
            pageSize = 2,
            fetchPage = { _, _, _ -> emptyList<TestRow>() },
            getTimestamp = { it.timestamp },
            getId = { it.id }
        ) { error("unexpected page") }

        assertThat(stats.pageCount).isEqualTo(0)
        assertThat(stats.rowCount).isEqualTo(0)
    }

    @Test
    fun scanTelemetryPagesIncludesLongMinLowerBound() = runTest {
        val rows = listOf(TestRow(id = "a", timestamp = Long.MIN_VALUE))
        val seen = mutableListOf<TestRow>()

        val stats = scanTelemetryPages(
            since = Long.MIN_VALUE,
            pageSize = 2,
            fetchPage = { afterTimestamp, afterId, pageSize ->
                rows.filter { row ->
                    row.timestamp > afterTimestamp ||
                        (row.timestamp == afterTimestamp && row.id > afterId)
                }.take(pageSize)
            },
            getTimestamp = { it.timestamp },
            getId = { it.id }
        ) { page ->
            seen += page
        }

        assertThat(seen).containsExactlyElementsIn(rows)
        assertThat(stats.rowCount).isEqualTo(1)
    }

    @Test
    fun liteTelemetryConversionPreservesUnitAndQuality() {
        val entity = TelemetrySampleLite(
            id = "telemetry-1",
            timestamp = 1_000L,
            source = "test",
            key = "sensor_age_hours",
            valueDouble = 24.0,
            valueText = null,
            unit = "h",
            quality = "OK"
        ).toEntity()

        assertThat(entity.unit).isEqualTo("h")
        assertThat(entity.quality).isEqualTo("OK")
    }

    private data class TestRow(
        val id: String,
        val timestamp: Long
    )
}
