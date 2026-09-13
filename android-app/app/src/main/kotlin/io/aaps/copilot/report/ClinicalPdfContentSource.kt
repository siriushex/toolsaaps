package io.aaps.copilot.report

import java.time.format.DateTimeFormatter

data class ClinicalPdfContentIdentity(
    val requestSha256: String,
    val generatedAt: Long,
    val zoneId: String,
    val schemaVersion: Int
)

enum class ClinicalPdfContentRole {
    TITLE,
    METADATA,
    HEADING,
    BODY,
    ITEM,
    DISCLAIMER
}

data class ClinicalPdfContentBlock(
    val text: String,
    val role: ClinicalPdfContentRole,
    val stableKey: String? = null
)

interface ClinicalPdfContentCursor : AutoCloseable {
    fun next(): ClinicalPdfContentBlock?
}

interface ClinicalPdfContentSource {
    val identity: ClinicalPdfContentIdentity
    fun open(): ClinicalPdfContentCursor
}

internal class ClinicalReportDocumentContentSource(
    private val document: ClinicalReportDocument
) : ClinicalPdfContentSource {
    override val identity = ClinicalPdfContentIdentity(
        requestSha256 = document.metadata.datasetHash,
        generatedAt = document.createdAt.toEpochMilli(),
        zoneId = "document-adapter",
        schemaVersion = document.metadata.schemaVersion
    )

    override fun open(): ClinicalPdfContentCursor = openBlocks()

    private fun openBlocks(): ClinicalPdfContentCursor = iteratorCursor(sequence {
        yield(ClinicalPdfContentBlock(document.title, ClinicalPdfContentRole.TITLE))
        yield(
            ClinicalPdfContentBlock(
                "Generated: ${DateTimeFormatter.ISO_INSTANT.format(document.createdAt)}",
                ClinicalPdfContentRole.METADATA
            )
        )
        yield(ClinicalPdfContentBlock("Report ranges", ClinicalPdfContentRole.HEADING))
        document.ranges.forEach { range ->
            yield(
                ClinicalPdfContentBlock(
                    "${range.label}: ${DateTimeFormatter.ISO_INSTANT.format(range.from)} - " +
                        DateTimeFormatter.ISO_INSTANT.format(range.through),
                    ClinicalPdfContentRole.BODY
                )
            )
        }
        yield(ClinicalPdfContentBlock("Report metadata", ClinicalPdfContentRole.HEADING))
        yield(
            ClinicalPdfContentBlock(
                "Provider: ${document.metadata.provider ?: "Local only"}",
                ClinicalPdfContentRole.BODY
            )
        )
        val requestedModel = document.metadata.requestedModel
        val actualModel = document.metadata.model
        if (requestedModel != null && actualModel != null) {
            yield(
                ClinicalPdfContentBlock(
                    "Requested model: $requestedModel",
                    ClinicalPdfContentRole.BODY
                )
            )
            yield(
                ClinicalPdfContentBlock(
                    "Actual model: $actualModel",
                    ClinicalPdfContentRole.BODY
                )
            )
        } else if (actualModel != null) {
            yield(ClinicalPdfContentBlock("Model: $actualModel", ClinicalPdfContentRole.BODY))
        }
        yield(
            ClinicalPdfContentBlock(
                "Dataset SHA-256: ${document.metadata.datasetHash}",
                ClinicalPdfContentRole.BODY
            )
        )
        yield(
            ClinicalPdfContentBlock(
                "Dataset schema: ${document.metadata.schemaVersion}",
                ClinicalPdfContentRole.BODY
            )
        )
        document.sections.forEach { section ->
            yield(ClinicalPdfContentBlock(section.title, ClinicalPdfContentRole.HEADING))
            section.items.forEach { item ->
                yield(ClinicalPdfContentBlock(item.flattenedLine(), ClinicalPdfContentRole.ITEM))
            }
        }
        yield(ClinicalPdfContentBlock("Important safety notice", ClinicalPdfContentRole.HEADING))
        yield(ClinicalPdfContentBlock(document.disclaimer, ClinicalPdfContentRole.DISCLAIMER))
    }.iterator())
}

internal fun iteratorCursor(
    iterator: Iterator<ClinicalPdfContentBlock>
): ClinicalPdfContentCursor = object : ClinicalPdfContentCursor {
    override fun next(): ClinicalPdfContentBlock? =
        if (iterator.hasNext()) iterator.next() else null

    override fun close() = Unit
}
