package io.aaps.copilot.report

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClinicalReportExportRepositoryTest {

    @Test
    fun suggestedFilenameRemovesPathsControlsAndNormalizesPdfSuffix() {
        assertThat(
            ClinicalReportExportRepository.suggestedFilename(
                "../private\\report\u0000 29 July.PDF.pdf"
            )
        ).isEqualTo("private_report_29_July.pdf")
        assertThat(ClinicalReportExportRepository.suggestedFilename("  "))
            .isEqualTo("aaps-copilot-clinical-report.pdf")
        assertThat(ClinicalReportExportRepository.suggestedFilename("weekly report"))
            .isEqualTo("weekly_report.pdf")
    }

    @Test
    fun suggestedFilenameIsBoundedAndContainsNoDirectorySeparator() {
        val result = ClinicalReportExportRepository.suggestedFilename(
            "../" + "clinical report ".repeat(20)
        )

        assertThat(result.length).isAtMost(96)
        assertThat(result).endsWith(".pdf")
        assertThat(result).doesNotContain("/")
        assertThat(result).doesNotContain("\\")
    }
}
