package io.aaps.copilot.domain.predict

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.GlucosePoint
import java.util.concurrent.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Test

class Glucose5mCanonicalizerTest {

    @Test
    fun buildsFiveMinuteCadenceFromMinuteLevelSeries() {
        val start = 1_000_000L
        val raw = (0 until 21).map { minute ->
            GlucosePoint(
                ts = start + minute * 60_000L,
                valueMmol = 6.0 + minute * 0.05,
                source = "sensor",
                quality = DataQuality.OK
            )
        }

        val canonical = Glucose5mCanonicalizer.build(raw)

        assertThat(canonical.points).hasSize(5)
        canonical.points.zipWithNext().forEach { (a, b) ->
            assertThat(b.ts - a.ts).isEqualTo(5 * 60_000L)
        }
        assertThat(canonical.observedCount).isAtLeast(4)
        assertThat(canonical.points.last().valueMmol).isWithin(0.15).of(raw.last().valueMmol)
    }

    @Test
    fun configurableWindowUsesClosedEndpointsAndPreservesExactNewestSample() {
        val start = 1_000_123L
        val through = start + 10 * 60_000L
        val raw = listOf(
            point(start, 5.0),
            point(start + 5 * 60_000L, 6.0),
            point(through - 60_000L, 8.0),
            point(through, 7.0),
            point(through + 1L, 20.0)
        )

        val canonical = Glucose5mCanonicalizer.build(
            raw,
            CanonicalGlucoseConfig(
                fromTs = start,
                throughTs = through,
                anchorTs = through,
                maxLookbackMs = 20 * 60_000L
            )
        )

        assertThat(canonical.points.map { it.ts }).containsExactly(
            start,
            start + 5 * 60_000L,
            through
        ).inOrder()
        assertThat(canonical.points.last().valueMmol).isEqualTo(7.0)
    }

    @Test
    fun interpolationIsBoundedAndPermutationIndependent() {
        val start = 10_000_123L
        val raw = listOf(
            point(start, 5.0),
            point(start + 10 * 60_000L, 7.0),
            point(start + 25 * 60_000L, 9.0)
        )
        val config = CanonicalGlucoseConfig(
            fromTs = start,
            throughTs = start + 25 * 60_000L,
            anchorTs = start + 25 * 60_000L,
            maxLookbackMs = 30 * 60_000L
        )

        val forward = Glucose5mCanonicalizer.build(raw, config)
        val reverse = Glucose5mCanonicalizer.build(raw.reversed(), config)

        assertThat(reverse).isEqualTo(forward)
        assertThat(forward.points.map { it.ts }).containsExactly(
            start,
            start + 5 * 60_000L,
            start + 10 * 60_000L,
            start + 25 * 60_000L
        ).inOrder()
        assertThat(forward.points[1].valueMmol).isEqualTo(6.0)
    }

    @Test
    fun exactTimestampPrefersOkQualityOverStaleRegardlessOfOrder() {
        val ts = 20_000_123L
        val stale = point(ts, 9.0).copy(quality = DataQuality.STALE)
        val ok = point(ts, 6.0)

        assertThat(Glucose5mCanonicalizer.build(listOf(stale, ok)).points.single().valueMmol)
            .isEqualTo(6.0)
        assertThat(Glucose5mCanonicalizer.build(listOf(ok, stale)).points.single().valueMmol)
            .isEqualTo(6.0)
    }

    @Test
    fun exactHalfWindowTieBelongsToLaterBucketExactlyOnce() {
        val start = 30_000_000L
        val tieTs = start + 2 * 60_000L + 30_000L
        val canonical = Glucose5mCanonicalizer.build(
            listOf(
                point(start, 5.0),
                point(tieTs, 9.0)
            ),
            CanonicalGlucoseConfig(
                fromTs = start,
                throughTs = start + 5 * 60_000L,
                anchorTs = start + 5 * 60_000L,
                maxLookbackMs = 5 * 60_000L
            )
        )

        assertThat(canonical.points.map { it.valueMmol }).containsExactly(5.0, 9.0).inOrder()
        assertThat(canonical.representativeTimestamps).containsExactly(start, tieTs)
    }

    @Test
    fun thirtyDaysOfMinuteRowsUsesLinearMovingWindowScan() {
        val start = 40_000_123L
        val rowCount = 30 * 24 * 60 + 1
        val raw = (0 until rowCount).map { minute ->
            point(start + minute * 60_000L, 5.0 + minute % 20 / 10.0)
        }

        val canonical = Glucose5mCanonicalizer.build(
            raw,
            CanonicalGlucoseConfig(
                fromTs = start,
                throughTs = raw.last().ts,
                anchorTs = raw.last().ts,
                maxLookbackMs = 30L * 24L * 60L * 60_000L
            )
        )

        assertThat(canonical.points).hasSize(30 * 24 * 12 + 1)
        assertThat(canonical.scanOperationCount)
            .isLessThan((raw.size + canonical.points.size) * 12L)
    }

    @Test
    fun cooperativeCheckpointCanCancelLongCanonicalization() {
        val raw = (0 until 10_000).map { minute ->
            point(50_000_123L + minute * 60_000L, 6.0)
        }
        var checkpoints = 0

        assertThrows(CancellationException::class.java) {
            Glucose5mCanonicalizer.build(raw, checkpoint = {
                checkpoints += 1
                if (checkpoints == 2) throw CancellationException("test")
            })
        }
        assertThat(checkpoints).isEqualTo(2)
    }

    private fun point(ts: Long, mmol: Double) = GlucosePoint(
        ts = ts,
        valueMmol = mmol,
        source = "nightscout",
        quality = DataQuality.OK
    )
}
