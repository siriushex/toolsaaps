package io.aaps.copilot.domain.isfcr

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.domain.model.DayType
import org.junit.Test

class IsfCrBaseFitterTest {

    @Test
    fun fit_resistsSingleIsfOutlierInHourBucket() {
        val fitter = IsfCrBaseFitter()
        val evidence = listOf(
            sample(ts = 1L, type = IsfCrSampleType.ISF, hour = 8, value = 2.4),
            sample(ts = 2L, type = IsfCrSampleType.ISF, hour = 8, value = 2.6),
            sample(ts = 3L, type = IsfCrSampleType.ISF, hour = 8, value = 12.0),
            sample(ts = 4L, type = IsfCrSampleType.CR, hour = 8, value = 10.0),
            sample(ts = 5L, type = IsfCrSampleType.CR, hour = 8, value = 11.0)
        )

        val fit = fitter.fit(
            evidence = evidence,
            defaults = 2.5 to 10.0
        )

        val isfHour = fit.hourlyIsf[8]
        assertThat(isfHour).isNotNull()
        assertThat(isfHour!!).isGreaterThan(2.2)
        assertThat(isfHour).isLessThan(4.0)
    }

    @Test
    fun fit_preservesCentralCrTrendWithExtremeOutlier() {
        val fitter = IsfCrBaseFitter()
        val evidence = listOf(
            sample(ts = 11L, type = IsfCrSampleType.ISF, hour = 12, value = 2.5),
            sample(ts = 12L, type = IsfCrSampleType.ISF, hour = 12, value = 2.7),
            sample(ts = 21L, type = IsfCrSampleType.CR, hour = 12, value = 9.0),
            sample(ts = 22L, type = IsfCrSampleType.CR, hour = 12, value = 9.5),
            sample(ts = 23L, type = IsfCrSampleType.CR, hour = 12, value = 46.0)
        )

        val fit = fitter.fit(
            evidence = evidence,
            defaults = 2.5 to 10.0
        )

        val crHour = fit.hourlyCr[12]
        assertThat(crHour).isNotNull()
        assertThat(crHour!!).isGreaterThan(7.5)
        assertThat(crHour).isLessThan(16.0)
    }

    private fun sample(
        ts: Long,
        type: IsfCrSampleType,
        hour: Int,
        value: Double,
        dayType: DayType = DayType.WEEKDAY
    ): IsfCrEvidenceSample {
        return IsfCrEvidenceSample(
            id = "s:$type:$ts",
            ts = ts,
            sampleType = type,
            hourLocal = hour,
            dayType = dayType,
            value = value,
            weight = 1.0,
            qualityScore = 1.0,
            context = emptyMap(),
            window = emptyMap()
        )
    }
}
