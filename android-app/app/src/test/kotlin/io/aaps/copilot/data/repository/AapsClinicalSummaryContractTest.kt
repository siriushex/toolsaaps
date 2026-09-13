package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AapsClinicalSummaryContractTest {

    @Test
    fun exactAapsTddSnapshotPreservesAllInsulinAndCarbs() {
        val through = 1_800_000_000_000L
        val request = AapsClinicalSummaryRequest(
            nonce = "12345678-1234-1234-1234-123456789012",
            detailFromTs = through - DAY_MS,
            from7d = through - 7L * DAY_MS,
            from30d = through - 30L * DAY_MS,
            throughTs = through
        )
        val snapshot = ClinicalAapsTddSnapshot(
            generatedAt = through + 1_000L,
            detail24h = period(request.detailFromTs, through, 10.0, 5.0, 120.0),
            period7d = period(request.from7d, through, 70.0, 35.0, 620.0),
            period30d = period(request.from30d, through, 300.0, 150.0, 2_450.0)
        )

        val validated = AapsClinicalSummaryContract.validateSnapshot(request, snapshot)

        assertThat(validated?.period7d?.totalInsulinU).isEqualTo(105.0)
        assertThat(validated?.period7d?.carbsG).isEqualTo(620.0)
        assertThat(validated?.period30d?.basalInsulinU).isEqualTo(300.0)
        assertThat(validated?.period30d?.bolusInsulinU).isEqualTo(150.0)
    }

    @Test
    fun validSevenDayRawHistoryIsRetainedWhenThirtyDayBasalHistoryIsUnavailable() {
        val through = 1_800_000_000_000L
        val request = AapsClinicalSummaryRequest(
            nonce = "12345678-1234-1234-1234-123456789012",
            detailFromTs = through - DAY_MS,
            from7d = through - 7L * DAY_MS,
            from30d = through - 30L * DAY_MS,
            throughTs = through
        )
        val snapshot = ClinicalAapsTddSnapshot(
            generatedAt = through + 1_000L,
            detail24h = null,
            period7d = period(request.from7d, through, 70.0, 35.0, 620.0),
            period30d = null
        )

        val validated = AapsClinicalSummaryContract.validateSnapshot(request, snapshot)

        assertThat(validated?.period7d?.totalInsulinU).isEqualTo(105.0)
        assertThat(validated?.period7d?.carbsG).isEqualTo(620.0)
        assertThat(validated?.period30d).isNull()
    }

    @Test
    fun mismatchedWindowOrInconsistentTotalIsRejected() {
        val through = 1_800_000_000_000L
        val request = AapsClinicalSummaryRequest(
            nonce = "12345678-1234-1234-1234-123456789012",
            detailFromTs = through - DAY_MS,
            from7d = through - 7L * DAY_MS,
            from30d = through - 30L * DAY_MS,
            throughTs = through
        )
        val snapshot = ClinicalAapsTddSnapshot(
            generatedAt = through,
            detail24h = null,
            period7d = period(request.from7d + 1L, through, 70.0, 35.0, 620.0),
            period30d = ClinicalAapsTddPeriod(
                request.from30d,
                through,
                basalInsulinU = 300.0,
                bolusInsulinU = 150.0,
                totalInsulinU = 900.0,
                carbsG = 2_450.0
            )
        )

        assertThat(AapsClinicalSummaryContract.validateSnapshot(request, snapshot)).isNull()
    }

    private fun period(
        fromTs: Long,
        throughTs: Long,
        basal: Double,
        bolus: Double,
        carbs: Double
    ) = ClinicalAapsTddPeriod(
        fromTs = fromTs,
        throughTs = throughTs,
        basalInsulinU = basal,
        bolusInsulinU = bolus,
        totalInsulinU = basal + bolus,
        carbsG = carbs
    )

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1_000L
    }
}
