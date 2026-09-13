package io.aaps.copilot.report

object ClinicalReportExportRepository {
    private const val DEFAULT_FILENAME = "aaps-copilot-clinical-report"
    private const val PDF_SUFFIX = ".pdf"
    private const val MAX_FILENAME_LENGTH = 96

    fun suggestedFilename(suggested: String?): String {
        var base = suggested.orEmpty().trim()
        while (base.endsWith(PDF_SUFFIX, ignoreCase = true)) {
            base = base.dropLast(PDF_SUFFIX.length).trimEnd()
        }
        base = buildString(base.length) {
            base.forEach { character ->
                append(
                    when {
                        character.isLetterOrDigit() -> character
                        character == '-' || character == '_' || character == '.' -> character
                        else -> '_'
                    }
                )
            }
        }
            .replace(Regex("_+"), "_")
            .replace(Regex("\\.+"), ".")
            .trim('.', '_', '-')
            .take(MAX_FILENAME_LENGTH - PDF_SUFFIX.length)
            .trimEnd('.', '_', '-')
            .ifBlank { DEFAULT_FILENAME }

        return base + PDF_SUFFIX
    }
}
