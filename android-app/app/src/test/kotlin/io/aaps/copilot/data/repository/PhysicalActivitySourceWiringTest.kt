package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class PhysicalActivitySourceWiringTest {

    @Test
    fun collectorsUseMinuteUpsertWithoutAddingAnotherScheduler() {
        listOf(
            source("io/aaps/copilot/service/LocalActivitySensorCollector.kt"),
            source("io/aaps/copilot/service/HealthConnectActivityCollector.kt")
        ).forEach { collector ->
            assertThat(collector).contains("fromPhysicalActivityKeyValueMap(")
            assertThat(collector).contains("upsertPhysicalActivityMinute(telemetry.persistedMetrics)")
            assertThat(collector).doesNotContain("upsertAll(telemetry)")
            assertThat(collector).doesNotContain("PeriodicWorkRequest")
            assertThat(collector).doesNotContain("WakeLock")
        }
    }

    @Test
    fun reportAndOverviewUseTheSameReactiveSqlBucketContractWithoutRawLimits() {
        val report = source("io/aaps/copilot/data/repository/ClinicalReportDatasetBuilder.kt")
        val container = source("io/aaps/copilot/service/AppContainer.kt")
        val overview = source("io/aaps/copilot/ui/MainViewModel.kt")

        assertThat(report).contains("physicalActivityMetric5MinuteBuckets(")
        assertThat(report).contains("filterNot(PhysicalActivityTelemetryPolicy.CLINICAL_ACTIVITY_METRIC_KEYS::contains)")
        assertThat(report).doesNotContain("trustedPhysicalActivityForClinicalReport(")
        assertThat(report).contains("PhysicalActivityTelemetryPolicy.reportBucketWindow(")
        assertThat(container).contains("physicalActivityMetric5MinuteBuckets(")
        assertThat(container).contains("actualActivityEventsFromBuckets(")
        assertThat(container).contains("PhysicalActivityTelemetryPolicy.reportBucketWindow(")
        assertThat(report).doesNotContain("toTsInclusive + 1L")
        assertThat(container).doesNotContain("throughTs + 1L")
        assertThat(overview).contains("observePhysicalActivityMetric5MinuteBuckets(")
        assertThat(overview).contains("actualActivityEventsFromBuckets(")
        assertThat(overview).doesNotContain("EVENT_TIMELINE_ACTIVITY_LIMIT")
    }

    @Test
    fun cleanupUsesExactPhysicalPolicyAnd45DayRetention() {
        val automation = source("io/aaps/copilot/data/repository/AutomationRepository.kt")

        assertThat(automation).contains("TELEMETRY_PHYSICAL_ACTIVITY_RETENTION_MS = 45L * DAY_MS")
        assertThat(automation).contains("deleteOlderThanWithReportProfileAndPhysicalActivityRetentionLimit")
        assertThat(automation).contains("PhysicalActivityTelemetryPolicy.TRUSTED_SOURCES.sorted()")
        assertThat(automation).contains("PhysicalActivityTelemetryPolicy.ACCEPTABLE_QUALITIES.sorted()")
    }

    private fun source(relative: String): String = File(
        checkNotNull(generateSequence(File(System.getProperty("user.dir").orEmpty()).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "src/main/kotlin").isDirectory }) {
            "Unable to locate app module from ${System.getProperty("user.dir")}"
        },
        "src/main/kotlin/$relative"
    ).readText()
}
