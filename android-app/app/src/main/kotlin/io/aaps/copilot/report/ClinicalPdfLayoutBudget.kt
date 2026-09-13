package io.aaps.copilot.report

internal const val PAGE_WIDTH_POINTS = 595f
internal const val PAGE_HEIGHT_POINTS = 842f
internal const val PAGE_MARGIN_LEFT = 42f
internal const val PAGE_MARGIN_RIGHT = 42f
internal const val CONTENT_TOP = 42f
internal const val CONTENT_BOTTOM = 746f
internal const val FOOTER_TOP = 766f
internal const val FOOTER_BOTTOM_MARGIN = 22f
internal const val ITEM_INDENT = 8f
internal const val HEADING_GAP_BEFORE = 10f
internal const val METADATA_GAP_BEFORE = 12f
internal const val TITLE_LINE_HEIGHT = 28f
internal const val HEADING_LINE_HEIGHT = 19f
internal const val BODY_LINE_HEIGHT = 15f
internal const val METADATA_LINE_HEIGHT = 14f
internal const val DISCLAIMER_LINE_HEIGHT = 14f
internal const val FOOTER_LINE_HEIGHT = 10f

internal data class ClinicalPdfLayoutLowerBound(
    val renderedLineCount: Long,
    val pageCount: Long
)

internal object ClinicalPdfLayoutBudget {
    fun legacyLowerBound(
        itemCount: Long,
        sectionHeadingCount: Long,
        rangeCount: Long
    ): ClinicalPdfLayoutLowerBound {
        require(itemCount >= 0L && sectionHeadingCount >= 0L && rangeCount >= 0L)

        val fixedBodyLines = Math.addExact(rangeCount, LEGACY_FIXED_BODY_LINES)
        val bodyLines = Math.addExact(itemCount, fixedBodyLines)
        val headingLines = Math.addExact(sectionHeadingCount, LEGACY_FIXED_HEADINGS)
        val renderedLines = addExactOrMax(
            bodyLines,
            headingLines,
            LEGACY_METADATA_LINES,
            LEGACY_TITLE_LINES,
            LEGACY_DISCLAIMER_LINES
        )

        val contentHeight = CONTENT_BOTTOM - CONTENT_TOP
        val bodyLinesPerPage = (contentHeight / BODY_LINE_HEIGHT).toLong()
        val anyLinesPerPage = (contentHeight / MINIMUM_CONTENT_LINE_HEIGHT).toLong()
        val bodyPageBound = ceilDiv(bodyLines, bodyLinesPerPage)
        val linePageBound = ceilDiv(renderedLines, anyLinesPerPage)
        val minimumHeight =
            bodyLines * BODY_LINE_HEIGHT.toDouble() +
                headingLines * (HEADING_LINE_HEIGHT + HEADING_GAP_BEFORE).toDouble() +
                LEGACY_METADATA_LINES *
                (METADATA_LINE_HEIGHT + METADATA_GAP_BEFORE).toDouble() +
                LEGACY_TITLE_LINES * TITLE_LINE_HEIGHT.toDouble() +
                LEGACY_DISCLAIMER_LINES * DISCLAIMER_LINE_HEIGHT.toDouble()
        val heightPageBound = kotlin.math.ceil(minimumHeight / contentHeight).toLong()

        return ClinicalPdfLayoutLowerBound(
            renderedLineCount = renderedLines,
            pageCount = maxOf(1L, bodyPageBound, linePageBound, heightPageBound)
        )
    }

    private fun addExactOrMax(vararg values: Long): Long = try {
        values.fold(0L, Math::addExact)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

    private fun ceilDiv(value: Long, divisor: Long): Long {
        if (value == 0L) return 0L
        return 1L + (value - 1L) / divisor
    }

    private const val LEGACY_FIXED_BODY_LINES = 3L
    private const val LEGACY_FIXED_HEADINGS = 3L
    private const val LEGACY_METADATA_LINES = 1L
    private const val LEGACY_TITLE_LINES = 1L
    private const val LEGACY_DISCLAIMER_LINES = 1L
    private const val MINIMUM_CONTENT_LINE_HEIGHT = 14f
}
