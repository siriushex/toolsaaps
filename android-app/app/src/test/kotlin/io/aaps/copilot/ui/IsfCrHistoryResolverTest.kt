package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IsfCrHistoryResolverTest {

    @Test
    fun resolveFiltersByWindowAndSortsAscending() {
        val now = 1_800_000_000_000L
        val points = listOf(
            point(now - 26L * 60L * 60L * 1000L, 2.0, 10.0),
            point(now - 3L * 60L * 60L * 1000L, 2.5, 9.8),
            point(now - 1L * 60L * 60L * 1000L, 2.7, 9.2),
            point(now - 10L * 60L * 1000L, 2.9, 8.9)
        )

        val resolved = IsfCrHistoryResolver.resolve(
            points = points,
            nowTs = now,
            window = IsfCrHistoryWindow.LAST_24H,
            maxPoints = 100
        )

        assertThat(resolved).hasSize(3)
        assertThat(resolved.map { it.timestamp }).containsExactly(
            now - 3L * 60L * 60L * 1000L,
            now - 1L * 60L * 60L * 1000L,
            now - 10L * 60L * 1000L
        ).inOrder()
    }

    @Test
    fun resolveDownsamplesAndKeepsLastPoint() {
        val now = 1_800_000_000_000L
        val start = now - 24L * 60L * 60L * 1000L
        val points = (0 until 100).map { index ->
            point(
                ts = start + index * 5L * 60L * 1000L,
                isf = 2.0 + index * 0.01,
                cr = 9.0 + index * 0.02
            )
        }

        val resolved = IsfCrHistoryResolver.resolve(
            points = points,
            nowTs = now,
            window = IsfCrHistoryWindow.LAST_30D,
            maxPoints = 20
        )

        assertThat(resolved.size).isAtMost(20)
        assertThat(resolved.last().timestamp).isEqualTo(points.last().timestamp)
        assertThat(resolved.first().timestamp).isAtLeast(points.first().timestamp)
    }

    @Test
    fun resolveDeduplicatesByTimestampUsingLatestValue() {
        val now = 1_800_000_000_000L
        val ts = now - 60L * 60L * 1000L
        val points = listOf(
            point(ts, 2.2, 9.2),
            point(ts, 2.8, 8.8),
            point(now - 10L * 60L * 1000L, 3.0, 8.5)
        )

        val resolved = IsfCrHistoryResolver.resolve(
            points = points,
            nowTs = now,
            window = IsfCrHistoryWindow.LAST_24H,
            maxPoints = 100
        )

        assertThat(resolved).hasSize(2)
        assertThat(resolved.first().isfMerged).isEqualTo(2.8)
        assertThat(resolved.first().crMerged).isEqualTo(8.8)
    }

    @Test
    fun resolveSupportsThreeAndSevenDayWindows() {
        val now = 1_800_000_000_000L
        val start = now - 7L * 24L * 60L * 60L * 1000L
        val points = (0 until 7 * 24 * 12).map { index ->
            point(
                ts = start + index * 5L * 60L * 1000L,
                isf = 2.0 + (index % 60) * 0.01,
                cr = 10.0 + (index % 40) * 0.02
            )
        }

        val last3d = IsfCrHistoryResolver.resolve(
            points = points,
            nowTs = now,
            window = IsfCrHistoryWindow.LAST_3D,
            maxPoints = 420
        )
        val last7d = IsfCrHistoryResolver.resolve(
            points = points,
            nowTs = now,
            window = IsfCrHistoryWindow.LAST_7D,
            maxPoints = 420
        )

        assertThat(last3d).isNotEmpty()
        assertThat(last7d).isNotEmpty()
        assertThat(last7d.size).isAtLeast(last3d.size)
        assertThat(last3d.last().timestamp).isEqualTo(points.last().timestamp)
        assertThat(last7d.last().timestamp).isEqualTo(points.last().timestamp)
    }

    @Test
    fun resolveDropsInvalidValuesAndKeepsValidPoints() {
        val now = 1_800_000_000_000L
        val points = listOf(
            point(now - 60L * 60L * 1000L, 2.2, 9.2),
            point(now - 40L * 60L * 1000L, Double.NaN, 9.0),
            point(now - 30L * 60L * 1000L, 22.0, 9.0),
            point(now - 20L * 60L * 1000L, 2.4, 65.0),
            point(now - 10L * 60L * 1000L, 2.5, 9.5)
        )

        val resolved = IsfCrHistoryResolver.resolve(
            points = points,
            nowTs = now,
            window = IsfCrHistoryWindow.LAST_24H,
            maxPoints = 100
        )

        assertThat(resolved).hasSize(2)
        assertThat(resolved.map { it.timestamp }).containsExactly(
            now - 60L * 60L * 1000L,
            now - 10L * 60L * 1000L
        ).inOrder()
    }

    private fun point(ts: Long, isf: Double, cr: Double): IsfCrHistoryPointUi {
        return IsfCrHistoryPointUi(
            timestamp = ts,
            isfMerged = isf,
            crMerged = cr,
            isfCalculated = isf,
            crCalculated = cr
        )
    }
}
