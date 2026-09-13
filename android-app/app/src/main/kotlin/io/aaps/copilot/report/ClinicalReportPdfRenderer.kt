package io.aaps.copilot.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.security.MessageDigest
import java.util.EnumMap
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ClinicalPdfLimits(
    val maxPages: Int,
    val maxInputBytes: Int,
    val maxEstimatedOutputBytes: Int = DEFAULT_MAX_ESTIMATED_OUTPUT_BYTES
) {
    init {
        require(maxPages > 0)
        require(maxInputBytes > 0)
        require(maxEstimatedOutputBytes > 0)
    }

    companion object {
        val DEFAULT = ClinicalPdfLimits(
            maxPages = 512,
            maxInputBytes = 16 * 1024 * 1024,
            maxEstimatedOutputBytes = DEFAULT_MAX_ESTIMATED_OUTPUT_BYTES
        )

        private const val DEFAULT_MAX_ESTIMATED_OUTPUT_BYTES = 32 * 1024 * 1024
    }
}

enum class ClinicalPdfLimitDimension {
    PAGES,
    INPUT_BYTES,
    ESTIMATED_OUTPUT_BYTES
}

data class ClinicalPdfPlan(
    val pageCount: Int,
    val contentLineCount: Long,
    val sourceBlockCount: Long,
    val encodedInputBytes: Long,
    val estimatedOutputBytes: Long,
    val contentSha256: String
)

sealed interface ClinicalPdfPlanResult {
    data class Ready(val plan: ClinicalPdfPlan) : ClinicalPdfPlanResult
    data class TooLarge(
        val dimension: ClinicalPdfLimitDimension,
        val limit: Long,
        val observed: Long
    ) : ClinicalPdfPlanResult
}

sealed interface ClinicalPdfRenderResult {
    data class Rendered(val plan: ClinicalPdfPlan) : ClinicalPdfRenderResult
    data class TooLarge(
        val dimension: ClinicalPdfLimitDimension,
        val limit: Long,
        val observed: Long
    ) : ClinicalPdfRenderResult
    data object SourceChanged : ClinicalPdfRenderResult
}

class ClinicalPdfTooLargeException(
    val dimension: ClinicalPdfLimitDimension
) : IllegalStateException("Clinical PDF exceeds its explicit safety limit")

class ClinicalReportPdfRenderer internal constructor(
    private val documentFactory: ClinicalPdfDocumentFactory,
    private val limits: ClinicalPdfLimits = ClinicalPdfLimits.DEFAULT,
    private val cancellationCheckpoint: ClinicalPdfCancellationCheckpoint? = null
) {
    private val paints = ClinicalPdfPaintCache()

    constructor() : this(
        documentFactory = ClinicalPdfDocumentFactory { AndroidClinicalPdfDocument() },
        limits = ClinicalPdfLimits.DEFAULT,
        cancellationCheckpoint = null
    )

    suspend fun render(
        document: ClinicalReportDocument,
        output: OutputStream
    ) {
        when (
            val result = renderSource(
                ClinicalReportDocumentContentSource(document),
                output
            )
        ) {
            is ClinicalPdfRenderResult.Rendered -> Unit
            is ClinicalPdfRenderResult.TooLarge ->
                throw ClinicalPdfTooLargeException(result.dimension)
            ClinicalPdfRenderResult.SourceChanged ->
                throw IllegalStateException("Clinical PDF content changed between passes")
        }
    }

    fun plan(source: ClinicalPdfContentSource): ClinicalPdfPlanResult =
        plan(source, ClinicalPdfCancellationCheckpoint.NONE)

    suspend fun renderSource(
        source: ClinicalPdfContentSource,
        output: OutputStream
    ): ClinicalPdfRenderResult {
        val coroutineContext = currentCoroutineContext()
        val cancellation = cancellationCheckpoint ?: ClinicalPdfCancellationCheckpoint {
            coroutineContext.ensureActive()
        }
        cancellation.ensureActive()
        val planned = when (val result = plan(source, cancellation)) {
            is ClinicalPdfPlanResult.Ready -> result.plan
            is ClinicalPdfPlanResult.TooLarge -> {
                return ClinicalPdfRenderResult.TooLarge(
                    result.dimension,
                    result.limit,
                    result.observed
                )
            }
        }
        cancellation.ensureActive()

        val pdf = documentFactory.create()
        var activePage: ClinicalPdfPage? = null
        var primaryFailure: Throwable? = null
        var committed = false
        try {
            val renderedPass = paginate(
                source = source,
                cancellation = cancellation,
                collectPageLines = true
            ) { pageNumber, pageLines ->
                cancellation.ensureActive()
                val page = pdf.startPage(pageNumber)
                activePage = page
                pageLines.forEach { line ->
                    cancellation.ensureActive()
                    val paint = paints.forStyle(line.style)
                    page.drawText(line.text, line.x, line.baseline, paint)
                    if (line.style == ClinicalPdfTextStyle.TITLE) {
                        page.drawLine(
                            PAGE_MARGIN_LEFT,
                            line.baseline + 7f,
                            PAGE_WIDTH_POINTS - PAGE_MARGIN_RIGHT,
                            line.baseline + 7f,
                            paints.rule
                        )
                    }
                }
                drawFooter(page, pageNumber, planned.pageCount, cancellation)
                cancellation.ensureActive()
                activePage = null
                pdf.finishPage(page)
                cancellation.ensureActive()
            }
            val renderPlan = renderedPass.toPlan()
            if (renderPlan != planned) {
                return ClinicalPdfRenderResult.SourceChanged
            }
            cancellation.ensureActive()
            pdf.writeTo(output)
            committed = true
            return ClinicalPdfRenderResult.Rendered(planned)
        } catch (tooLarge: ClinicalPdfLimitExceeded) {
            return ClinicalPdfRenderResult.TooLarge(
                tooLarge.dimension,
                tooLarge.limit,
                tooLarge.observed
            )
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            val unfinishedPage = activePage
            activePage = null
            unfinishedPage?.let { page ->
                try {
                    pdf.finishPage(page)
                } catch (error: Throwable) {
                    cleanupFailure = error
                }
            }
            try {
                pdf.close()
            } catch (error: Throwable) {
                val earlier = cleanupFailure
                if (earlier == null) cleanupFailure = error else earlier.addSuppressed(error)
            }
            cleanupFailure?.let { cleanup ->
                val failure = primaryFailure
                if (failure != null) {
                    failure.addSuppressed(cleanup)
                } else if (!committed) {
                    throw cleanup
                }
            }
        }
    }

    private fun plan(
        source: ClinicalPdfContentSource,
        cancellation: ClinicalPdfCancellationCheckpoint
    ): ClinicalPdfPlanResult = try {
        val pass = paginate(
            source = source,
            cancellation = cancellation,
            collectPageLines = false,
            onPage = { _, _ -> }
        )
        ClinicalPdfPlanResult.Ready(pass.toPlan())
    } catch (tooLarge: ClinicalPdfLimitExceeded) {
        ClinicalPdfPlanResult.TooLarge(
            tooLarge.dimension,
            tooLarge.limit,
            tooLarge.observed
        )
    }

    private fun paginate(
        source: ClinicalPdfContentSource,
        cancellation: ClinicalPdfCancellationCheckpoint,
        collectPageLines: Boolean,
        onPage: (Int, List<ClinicalPdfLayoutLine>) -> Unit
    ): ClinicalPdfPass {
        val paginator = ClinicalPdfStreamingPaginator(
            identity = source.identity,
            limits = limits,
            paints = paints,
            cancellation = cancellation,
            collectPageLines = collectPageLines,
            onPage = onPage
        )
        source.open().use { cursor ->
            while (true) {
                cancellation.ensureActive()
                val block = cursor.next() ?: break
                paginator.add(block)
            }
        }
        return paginator.complete()
    }

    private fun drawFooter(
        page: ClinicalPdfPage,
        pageNumber: Int,
        totalPages: Int,
        cancellation: ClinicalPdfCancellationCheckpoint
    ) {
        footerLines(pageNumber, totalPages).forEach { line ->
            cancellation.ensureActive()
            page.drawText(
                line.text,
                line.x,
                line.baseline,
                paints.forStyle(line.style)
            )
        }
    }

    private fun footerLines(pageNumber: Int, totalPages: Int): List<ClinicalPdfLayoutLine> {
        val lines = mutableListOf<ClinicalPdfLayoutLine>()
        var footerIndex = 0
        forEachWrappedLine(
            normalizeText(SAFETY_FOOTER),
            paints.forStyle(ClinicalPdfTextStyle.FOOTER),
            PAGE_WIDTH_POINTS - PAGE_MARGIN_LEFT - PAGE_MARGIN_RIGHT,
            ClinicalPdfCancellationCheckpoint.NONE
        ) { text ->
            if (footerIndex < MAX_FOOTER_LINES) {
                lines += ClinicalPdfLayoutLine(
                    text = text,
                    x = PAGE_MARGIN_LEFT,
                    baseline = FOOTER_TOP +
                        footerIndex * ClinicalPdfTextStyle.FOOTER.lineHeight,
                    style = ClinicalPdfTextStyle.FOOTER,
                    stableFooter = true
                )
                footerIndex += 1
            }
        }
        lines += ClinicalPdfLayoutLine(
            text = "Page $pageNumber of $totalPages",
            x = PAGE_MARGIN_LEFT,
            baseline = PAGE_HEIGHT_POINTS - FOOTER_BOTTOM_MARGIN,
            style = ClinicalPdfTextStyle.FOOTER,
            stableFooter = true
        )
        return lines
    }

    private inner class ClinicalPdfStreamingPaginator(
        identity: ClinicalPdfContentIdentity,
        private val limits: ClinicalPdfLimits,
        private val paints: ClinicalPdfPaintCache,
        private val cancellation: ClinicalPdfCancellationCheckpoint,
        private val collectPageLines: Boolean,
        private val onPage: (Int, List<ClinicalPdfLayoutLine>) -> Unit
    ) {
        private val framedDigest = ClinicalPdfFramedDigest(limits, cancellation)
        private var pageLines = mutableListOf<ClinicalPdfLayoutLine>()
        private var pageNumber = 1
        private var cursorTop = CONTENT_TOP
        private var sourceBlocks = 0L
        private var contentLines = 0L
        private var completed = false

        init {
            framedDigest.addIdentity(identity)
        }

        fun add(block: ClinicalPdfContentBlock) {
            check(!completed)
            cancellation.ensureActive()
            sourceBlocks = Math.addExact(sourceBlocks, 1L)
            framedDigest.addBlock(block)
            val normalized = normalizeText(block.text)

            val style = block.role.style
            val gapBefore = when (block.role) {
                ClinicalPdfContentRole.METADATA -> METADATA_GAP_BEFORE
                ClinicalPdfContentRole.HEADING -> HEADING_GAP_BEFORE
                else -> 0f
            }
            if (block.role == ClinicalPdfContentRole.HEADING) {
                ensureSpace(
                    ClinicalPdfTextStyle.HEADING.lineHeight +
                        ClinicalPdfTextStyle.BODY.lineHeight +
                        HEADING_GAP_BEFORE
                )
            }
            cursorTop += gapBefore
            val indent = if (block.role == ClinicalPdfContentRole.ITEM) ITEM_INDENT else 0f
            val x = PAGE_MARGIN_LEFT + indent
            val availableWidth = PAGE_WIDTH_POINTS - x - PAGE_MARGIN_RIGHT
            val paint = paints.forStyle(style)
            forEachWrappedLine(normalized, paint, availableWidth, cancellation) { wrapped ->
                ensureSpace(style.lineHeight)
                val baseline = cursorTop - paint.fontMetrics.ascent
                if (collectPageLines) {
                    pageLines += ClinicalPdfLayoutLine(
                        text = wrapped,
                        x = x,
                        baseline = baseline,
                        style = style
                    )
                }
                contentLines = Math.addExact(contentLines, 1L)
                cursorTop += style.lineHeight
            }
        }

        fun complete(): ClinicalPdfPass {
            check(!completed)
            cancellation.ensureActive()
            completed = true
            val contentSha256 = framedDigest.complete(sourceBlocks)
            emitPage()
            val estimated = conservativeEstimatedOutputBytes(
                framedDigest.encodedBytes,
                contentLines,
                pageNumber
            )
            if (estimated > limits.maxEstimatedOutputBytes.toLong()) {
                throw ClinicalPdfLimitExceeded(
                    ClinicalPdfLimitDimension.ESTIMATED_OUTPUT_BYTES,
                    limits.maxEstimatedOutputBytes.toLong(),
                    estimated
                )
            }
            return ClinicalPdfPass(
                pageCount = pageNumber,
                contentLineCount = contentLines,
                sourceBlockCount = sourceBlocks,
                encodedInputBytes = framedDigest.encodedBytes,
                estimatedOutputBytes = estimated,
                contentSha256 = contentSha256
            )
        }

        private fun ensureSpace(requiredHeight: Float) {
            cancellation.ensureActive()
            if (cursorTop + requiredHeight <= CONTENT_BOTTOM) return
            emitPage()
            pageNumber += 1
            if (pageNumber > limits.maxPages) {
                throw ClinicalPdfLimitExceeded(
                    ClinicalPdfLimitDimension.PAGES,
                    limits.maxPages.toLong(),
                    pageNumber.toLong()
                )
            }
            cursorTop = CONTENT_TOP
        }

        private fun emitPage() {
            cancellation.ensureActive()
            if (collectPageLines) {
                val completedPage = pageLines
                pageLines = mutableListOf()
                onPage(pageNumber, completedPage)
            } else {
                onPage(pageNumber, emptyList())
            }
        }
    }

    private companion object {
        const val MAX_FOOTER_LINES = 4
        const val SAFETY_FOOTER =
            "Advisory only. Not medical advice or a command for insulin, carbohydrates, " +
                "glucose targets, or calibration."
    }
}

private data class ClinicalPdfPass(
    val pageCount: Int,
    val contentLineCount: Long,
    val sourceBlockCount: Long,
    val encodedInputBytes: Long,
    val estimatedOutputBytes: Long,
    val contentSha256: String
) {
    fun toPlan() = ClinicalPdfPlan(
        pageCount = pageCount,
        contentLineCount = contentLineCount,
        sourceBlockCount = sourceBlockCount,
        encodedInputBytes = encodedInputBytes,
        estimatedOutputBytes = estimatedOutputBytes,
        contentSha256 = contentSha256
    )
}

private class ClinicalPdfLimitExceeded(
    val dimension: ClinicalPdfLimitDimension,
    val limit: Long,
    val observed: Long
) : RuntimeException()

private class ClinicalPdfFramedDigest(
    private val limits: ClinicalPdfLimits,
    private val cancellation: ClinicalPdfCancellationCheckpoint
) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val buffer = ByteArray(UTF8_BUFFER_BYTES)
    private var completed = false

    var encodedBytes: Long = 0L
        private set

    fun addIdentity(identity: ClinicalPdfContentIdentity) {
        writeStringFrame(FRAME_DOMAIN, FRAME_DOMAIN_VALUE)
        writeEmptyFrame(FRAME_IDENTITY_START)
        writeStringFrame(FRAME_REQUEST_SHA256, identity.requestSha256)
        writeStringFrame(FRAME_GENERATED_AT, identity.generatedAt.toString())
        writeStringFrame(FRAME_ZONE_ID, identity.zoneId)
        writeStringFrame(FRAME_SCHEMA_VERSION, identity.schemaVersion.toString())
        writeEmptyFrame(FRAME_IDENTITY_END)
        writeEmptyFrame(FRAME_BLOCKS_START)
    }

    fun addBlock(block: ClinicalPdfContentBlock) {
        check(!completed)
        writeEmptyFrame(FRAME_BLOCK_START)
        writeStringFrame(FRAME_BLOCK_ROLE, block.role.name)
        block.stableKey?.let { writeStringFrame(FRAME_STABLE_KEY, it) }
            ?: writeEmptyFrame(FRAME_STABLE_KEY_ABSENT)
        writeStringFrame(FRAME_BLOCK_TEXT, block.text)
        writeEmptyFrame(FRAME_BLOCK_END)
    }

    fun complete(blockCount: Long): String {
        check(!completed)
        cancellation.ensureActive()
        completed = true
        writeLongFrame(FRAME_BLOCK_COUNT, blockCount)
        writeEmptyFrame(FRAME_STREAM_END)
        return digest.digest().toHex()
    }

    private fun writeEmptyFrame(tag: Byte) {
        ensureCapacity(FRAME_HEADER_BYTES)
        updateByte(tag)
        updateLong(0L)
    }

    private fun writeLongFrame(tag: Byte, value: Long) {
        ensureCapacity(FRAME_HEADER_BYTES + Long.SIZE_BYTES)
        updateByte(tag)
        updateLong(Long.SIZE_BYTES.toLong())
        updateLong(value)
    }

    private fun writeStringFrame(tag: Byte, value: String) {
        val utf8Length = utf8Length(value)
        ensureCapacity(Math.addExact(FRAME_HEADER_BYTES, utf8Length))
        updateByte(tag)
        updateLong(utf8Length)
        updateUtf8(value, utf8Length)
    }

    private fun utf8Length(value: String): Long {
        var bytes = 0L
        var index = 0
        while (index < value.length) {
            if (index % CANCELLATION_STRIDE_CHARS == 0) cancellation.ensureActive()
            val character = value[index]
            bytes = Math.addExact(
                bytes,
                when {
                    character.code <= 0x7f -> 1L
                    character.code <= 0x7ff -> 2L
                    character.isHighSurrogate() -> {
                        require(value.getOrNull(index + 1)?.isLowSurrogate() == true) {
                            "Clinical PDF text contains an unpaired high surrogate"
                        }
                        index += 1
                        4L
                    }
                    character.isLowSurrogate() -> throw IllegalArgumentException(
                        "Clinical PDF text contains an unpaired low surrogate"
                    )
                    else -> 3L
                }
            )
            index += 1
        }
        cancellation.ensureActive()
        return bytes
    }

    private fun updateUtf8(value: String, utf8Length: Long) {
        var index = 0
        var buffered = 0
        while (index < value.length) {
            if (index % CANCELLATION_STRIDE_CHARS == 0) cancellation.ensureActive()
            val character = value[index]
            val codePoint: Int
            val byteCount: Int
            when {
                character.code <= 0x7f -> {
                    codePoint = character.code
                    byteCount = 1
                }
                character.code <= 0x7ff -> {
                    codePoint = character.code
                    byteCount = 2
                }
                character.isHighSurrogate() -> {
                    val low = value[index + 1]
                    codePoint = Character.toCodePoint(character, low)
                    byteCount = 4
                    index += 1
                }
                character.isLowSurrogate() -> error("UTF-8 length pass accepted an unpaired surrogate")
                else -> {
                    codePoint = character.code
                    byteCount = 3
                }
            }
            if (buffered + byteCount > buffer.size) {
                digest.update(buffer, 0, buffered)
                buffered = 0
            }
            when (byteCount) {
                1 -> buffer[buffered++] = codePoint.toByte()
                2 -> {
                    buffer[buffered++] = (0xc0 or (codePoint shr 6)).toByte()
                    buffer[buffered++] = (0x80 or (codePoint and 0x3f)).toByte()
                }
                3 -> {
                    buffer[buffered++] = (0xe0 or (codePoint shr 12)).toByte()
                    buffer[buffered++] = (0x80 or ((codePoint shr 6) and 0x3f)).toByte()
                    buffer[buffered++] = (0x80 or (codePoint and 0x3f)).toByte()
                }
                else -> {
                    buffer[buffered++] = (0xf0 or (codePoint shr 18)).toByte()
                    buffer[buffered++] = (0x80 or ((codePoint shr 12) and 0x3f)).toByte()
                    buffer[buffered++] = (0x80 or ((codePoint shr 6) and 0x3f)).toByte()
                    buffer[buffered++] = (0x80 or (codePoint and 0x3f)).toByte()
                }
            }
            index += 1
        }
        if (buffered > 0) digest.update(buffer, 0, buffered)
        encodedBytes = Math.addExact(encodedBytes, utf8Length)
    }

    private fun updateByte(value: Byte) {
        digest.update(value)
        encodedBytes = Math.addExact(encodedBytes, 1L)
    }

    private fun updateLong(value: Long) {
        var shift = 56
        while (shift >= 0) {
            buffer[(56 - shift) / 8] = (value ushr shift).toByte()
            shift -= 8
        }
        digest.update(buffer, 0, Long.SIZE_BYTES)
        encodedBytes = Math.addExact(encodedBytes, Long.SIZE_BYTES.toLong())
    }

    private fun ensureCapacity(additionalBytes: Long) {
        cancellation.ensureActive()
        val observed = try {
            Math.addExact(encodedBytes, additionalBytes)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        if (observed > limits.maxInputBytes.toLong()) {
            throw ClinicalPdfLimitExceeded(
                ClinicalPdfLimitDimension.INPUT_BYTES,
                limits.maxInputBytes.toLong(),
                observed
            )
        }
    }

    private companion object {
        const val UTF8_BUFFER_BYTES = 4 * 1_024
        const val CANCELLATION_STRIDE_CHARS = 1_024
        const val FRAME_HEADER_BYTES = 1L + Long.SIZE_BYTES
        const val FRAME_DOMAIN_VALUE = "io.aaps.copilot.clinical-pdf-content/v2"

        const val FRAME_DOMAIN: Byte = 0x01
        const val FRAME_IDENTITY_START: Byte = 0x02
        const val FRAME_REQUEST_SHA256: Byte = 0x03
        const val FRAME_GENERATED_AT: Byte = 0x04
        const val FRAME_ZONE_ID: Byte = 0x05
        const val FRAME_SCHEMA_VERSION: Byte = 0x06
        const val FRAME_IDENTITY_END: Byte = 0x07
        const val FRAME_BLOCKS_START: Byte = 0x08
        const val FRAME_BLOCK_START: Byte = 0x10
        const val FRAME_BLOCK_ROLE: Byte = 0x11
        const val FRAME_STABLE_KEY_ABSENT: Byte = 0x12
        const val FRAME_STABLE_KEY: Byte = 0x13
        const val FRAME_BLOCK_TEXT: Byte = 0x14
        const val FRAME_BLOCK_END: Byte = 0x15
        const val FRAME_BLOCK_COUNT: Byte = 0x16
        const val FRAME_STREAM_END: Byte = 0x17
    }
}

private fun conservativeEstimatedOutputBytes(
    inputBytes: Long,
    contentLines: Long,
    pages: Int
): Long = try {
    Math.addExact(
        Math.addExact(
            Math.addExact(
                inputBytes,
                Math.multiplyExact(contentLines, OUTPUT_BYTES_PER_LINE)
            ),
            Math.multiplyExact(pages.toLong(), OUTPUT_BYTES_PER_PAGE)
        ),
        PDF_FIXED_OVERHEAD_BYTES
    )
} catch (_: ArithmeticException) {
    Long.MAX_VALUE
}

private const val OUTPUT_BYTES_PER_LINE = 64L
private const val OUTPUT_BYTES_PER_PAGE = 1_024L
private const val PDF_FIXED_OVERHEAD_BYTES = 4_096L

private class ClinicalPdfPaintCache {
    private val paints = EnumMap<ClinicalPdfTextStyle, Paint>(ClinicalPdfTextStyle::class.java)

    val rule: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(188, 198, 210)
        strokeWidth = 0.8f
    }

    init {
        ClinicalPdfTextStyle.entries.forEach { style ->
            paints[style] = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = style.color
                textSize = style.textSize
                typeface = Typeface.create(Typeface.DEFAULT, style.typefaceStyle)
            }
        }
    }

    fun forStyle(style: ClinicalPdfTextStyle): Paint = checkNotNull(paints[style])
}

private fun forEachWrappedLine(
    normalizedText: String,
    paint: Paint,
    maxWidth: Float,
    cancellation: ClinicalPdfCancellationCheckpoint,
    emit: (String) -> Unit
) {
    cancellation.ensureActive()
    require(maxWidth > 0f)
    var start = 0
    while (start < normalizedText.length) {
        cancellation.ensureActive()
        val measuredCount = paint.breakText(
            normalizedText,
            start,
            normalizedText.length,
            true,
            maxWidth,
            null
        ).coerceAtLeast(1)
        var end = Math.addExact(start, measuredCount).coerceAtMost(normalizedText.length)
        if (
            end < normalizedText.length &&
            end > start &&
            normalizedText[end - 1].isHighSurrogate() &&
            normalizedText[end].isLowSurrogate()
        ) {
            end -= 1
        }
        if (end <= start) {
            end = start + Character.charCount(normalizedText.codePointAt(start))
        }
        if (end < normalizedText.length) {
            val wordBoundary = findClinicalPdfWordBoundary(normalizedText, start, end)
            if (wordBoundary > start) end = wordBoundary
        }
        emit(normalizedText.substring(start, end))
        start = end
        while (start < normalizedText.length && normalizedText[start] == ' ') start += 1
    }
}

internal fun findClinicalPdfWordBoundary(
    text: String,
    start: Int,
    end: Int,
    boundaryProbe: (() -> Unit)? = null
): Int {
    require(start in 0..end)
    require(end <= text.length)
    var index = end - 1
    while (index > start) {
        boundaryProbe?.invoke()
        if (text[index] == ' ') return index
        index -= 1
    }
    return end
}

private fun normalizeText(rawText: String): String = rawText
    .replace(CONTROL_CHARACTERS, " ")
    .replace(WHITESPACE, " ")
    .trim()
    .ifBlank { "Not available" }

private fun ByteArray.toHex(): String = joinToString("") {
    String.format(Locale.ROOT, "%02x", it)
}

private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001f\\u007f]")
private val WHITESPACE = Regex("\\s+")

private val ClinicalPdfContentRole.style: ClinicalPdfTextStyle
    get() = when (this) {
        ClinicalPdfContentRole.TITLE -> ClinicalPdfTextStyle.TITLE
        ClinicalPdfContentRole.METADATA -> ClinicalPdfTextStyle.METADATA
        ClinicalPdfContentRole.HEADING -> ClinicalPdfTextStyle.HEADING
        ClinicalPdfContentRole.BODY,
        ClinicalPdfContentRole.ITEM -> ClinicalPdfTextStyle.BODY
        ClinicalPdfContentRole.DISCLAIMER -> ClinicalPdfTextStyle.DISCLAIMER
    }

internal fun interface ClinicalPdfCancellationCheckpoint {
    fun ensureActive()

    companion object {
        val NONE = ClinicalPdfCancellationCheckpoint {}
    }
}

internal data class ClinicalPdfLayoutLine(
    val text: String,
    val x: Float,
    val baseline: Float,
    val style: ClinicalPdfTextStyle,
    val stableFooter: Boolean = false
)

internal enum class ClinicalPdfTextStyle(
    val textSize: Float,
    val lineHeight: Float,
    val typefaceStyle: Int,
    val color: Int
) {
    TITLE(20f, TITLE_LINE_HEIGHT, Typeface.BOLD, Color.rgb(20, 42, 68)),
    HEADING(13f, HEADING_LINE_HEIGHT, Typeface.BOLD, Color.rgb(20, 42, 68)),
    BODY(10.5f, BODY_LINE_HEIGHT, Typeface.NORMAL, Color.rgb(28, 34, 42)),
    METADATA(9.5f, METADATA_LINE_HEIGHT, Typeface.NORMAL, Color.rgb(75, 88, 102)),
    DISCLAIMER(9.5f, DISCLAIMER_LINE_HEIGHT, Typeface.BOLD, Color.rgb(126, 40, 40)),
    FOOTER(8f, FOOTER_LINE_HEIGHT, Typeface.NORMAL, Color.rgb(80, 91, 104))
}

internal fun interface ClinicalPdfDocumentFactory {
    fun create(): ClinicalPdfDocument
}

internal interface ClinicalPdfDocument {
    fun startPage(pageNumber: Int): ClinicalPdfPage
    fun finishPage(page: ClinicalPdfPage)
    fun writeTo(output: OutputStream)
    fun close()
}

internal interface ClinicalPdfPage {
    fun drawText(text: String, x: Float, baseline: Float, paint: Paint)
    fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint)
}

private class AndroidClinicalPdfDocument : ClinicalPdfDocument {
    private val delegate = PdfDocument()

    override fun startPage(pageNumber: Int): ClinicalPdfPage {
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
        return AndroidClinicalPdfPage(delegate.startPage(pageInfo))
    }

    override fun finishPage(page: ClinicalPdfPage) {
        val androidPage = requireNotNull(page as? AndroidClinicalPdfPage) {
            "Unexpected PDF page implementation"
        }
        delegate.finishPage(androidPage.delegate)
    }

    override fun writeTo(output: OutputStream) = delegate.writeTo(output)
    override fun close() = delegate.close()
}

private class AndroidClinicalPdfPage(
    val delegate: PdfDocument.Page
) : ClinicalPdfPage {
    private val canvas: Canvas = delegate.canvas

    override fun drawText(text: String, x: Float, baseline: Float, paint: Paint) {
        canvas.drawText(text, x, baseline, paint)
    }

    override fun drawLine(
        startX: Float,
        startY: Float,
        stopX: Float,
        stopY: Float,
        paint: Paint
    ) {
        canvas.drawLine(startX, startY, stopX, stopY, paint)
    }
}
