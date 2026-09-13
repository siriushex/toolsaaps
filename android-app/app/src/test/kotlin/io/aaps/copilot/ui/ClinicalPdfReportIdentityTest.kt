package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.repository.ClinicalAdvisoryReport
import io.aaps.copilot.data.repository.ClinicalDataQuality
import io.aaps.copilot.data.repository.ClinicalDataQualityFlag
import io.aaps.copilot.data.repository.ClinicalLocalReport
import io.aaps.copilot.data.repository.ClinicalOpenAiClient
import io.aaps.copilot.data.repository.ClinicalOpenAiMetadata
import io.aaps.copilot.data.repository.ClinicalPeriodSummary
import io.aaps.copilot.data.repository.ClinicalPdfReportIdentity
import io.aaps.copilot.data.repository.ClinicalReportFailureReason
import io.aaps.copilot.data.repository.ClinicalReportState
import io.aaps.copilot.data.repository.ClinicalSafetyObservation
import io.aaps.copilot.data.repository.ClinicalSummaryStatus
import org.junit.Test

class ClinicalPdfReportIdentityTest {

    @Test
    fun identitySeparatesSameRequestLifecycleStatesAndSanitizesContent() {
        val local = localReport()
        val report = advisoryReport()
        val metadata = metadata()
        val states = listOf(
            ClinicalReportState.LocalReady(REQUEST_ID, local),
            ClinicalReportState.Uploading(REQUEST_ID, local, completed = 0, total = 1),
            ClinicalReportState.Complete(REQUEST_ID, local, report, metadata),
            ClinicalReportState.Failed(
                REQUEST_ID,
                local,
                ClinicalReportFailureReason.NETWORK
            ),
            ClinicalReportState.Cancelled(REQUEST_ID, local)
        )

        val identities = states.map(ClinicalPdfReportIdentity::from)

        assertThat(identities.distinct()).hasSize(states.size)
        assertThat(identities[0].isExportable).isTrue()
        assertThat(identities[1].isExportable).isFalse()
        assertThat(identities[2].isExportable).isTrue()
        assertThat(identities[3].isExportable).isFalse()
        assertThat(identities[4].isExportable).isFalse()
        identities.forEach { identity ->
            assertThat(identity.digest).matches("[0-9a-f]{64}")
            assertThat(identity.digest).doesNotContain(REQUEST_ID)
            assertThat(identity.digest).doesNotContain(metadata.model)
        }
    }

    @Test
    fun completeIdentityIsDeterministicAndBindsExactReportAndMetadata() {
        val local = localReport()
        val report = advisoryReport()
        val metadata = metadata()
        val original = ClinicalReportState.Complete(REQUEST_ID, local, report, metadata)

        val same = ClinicalPdfReportIdentity.from(original.copy())
        val changedReport = ClinicalPdfReportIdentity.from(
            original.copy(
                report = report.copy(summary7dStatus = ClinicalSummaryStatus.INSUFFICIENT_DATA)
            )
        )
        val changedMetadata = ClinicalPdfReportIdentity.from(
            original.copy(metadata = metadata.copy(durationMs = metadata.durationMs + 1L))
        )

        assertThat(ClinicalPdfReportIdentity.from(original)).isEqualTo(same)
        assertThat(changedReport).isNotEqualTo(same)
        assertThat(changedMetadata).isNotEqualTo(same)
    }

    private fun localReport(): ClinicalLocalReport {
        fun summary(days: Int) = ClinicalPeriodSummary(
            days = days,
            fromTs = 1L,
            throughTs = 2L,
            coveragePct = 100.0,
            meanMmol = 6.0,
            medianMmol = 6.0,
            coefficientOfVariationPct = 10.0,
            timeBelow4Pct = 0.0,
            timeInRangePct = 100.0,
            timeAboveRangePct = 0.0,
            totalInsulinU = 1.0,
            totalCarbsG = 1.0,
            meanTargetMmol = 6.0,
            weekdayPattern = emptyList(),
            weekendPattern = emptyList(),
            quality = ClinicalDataQuality(
                expectedBuckets = 1,
                coveredBuckets = 1,
                missingBuckets = 0,
                maxGapMinutes = 5
            )
        )
        return ClinicalLocalReport(
            summary7d = summary(7),
            summary30d = summary(30),
            requestHash = "a".repeat(64),
            generatedAt = 2L,
            zoneId = "UTC"
        )
    }

    private fun advisoryReport() = ClinicalAdvisoryReport(
        summary7dStatus = ClinicalSummaryStatus.STABLE,
        summary30dStatus = ClinicalSummaryStatus.MIXED,
        dataQuality = listOf(ClinicalDataQualityFlag.COMPLETE),
        patterns = emptyList(),
        safetyObservations = listOf(ClinicalSafetyObservation.NONE_IDENTIFIED),
        recommendations = emptyList(),
        careTeamQuestions = emptyList()
    )

    private fun metadata() = ClinicalOpenAiMetadata(
        model = ClinicalOpenAiClient.DEFAULT_MODEL,
        requestedModel = ClinicalOpenAiClient.DEFAULT_MODEL,
        systemFingerprint = "stable-fingerprint",
        schemaName = ClinicalOpenAiClient.SCHEMA_NAME,
        schemaVersion = ClinicalOpenAiClient.SCHEMA_VERSION,
        datasetSchemaVersion = 7,
        requestHash = "a".repeat(64),
        chunkCount = 1,
        usedSynthesis = false,
        durationMs = 10L
    )

    private companion object {
        const val REQUEST_ID = "same-request-id"
    }
}
