package io.aaps.copilot.report

import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking

internal fun captureClinicalPdfText(document: ClinicalReportDocument): String {
    val pdf = CapturingClinicalPdfDocument()
    runBlocking {
        ClinicalReportPdfRenderer(ClinicalPdfDocumentFactory { pdf })
            .render(document, ByteArrayOutputStream())
    }
    return pdf.drawnText.joinToString(" ")
}

private class CapturingClinicalPdfDocument : ClinicalPdfDocument {
    private var activePage: CapturingClinicalPdfPage? = null
    private var closed = false
    val drawnText = mutableListOf<String>()

    override fun startPage(pageNumber: Int): ClinicalPdfPage {
        check(!closed)
        check(activePage == null)
        return CapturingClinicalPdfPage(this).also { activePage = it }
    }

    override fun finishPage(page: ClinicalPdfPage) {
        val captured = requireNotNull(page as? CapturingClinicalPdfPage)
        require(captured.owner === this)
        check(activePage === captured)
        check(!captured.finished)
        captured.finished = true
        activePage = null
    }

    override fun writeTo(output: OutputStream) {
        check(!closed)
        check(activePage == null)
        output.write("%PDF-test".toByteArray())
    }

    override fun close() {
        check(!closed)
        check(activePage == null)
        closed = true
    }

    private class CapturingClinicalPdfPage(
        val owner: CapturingClinicalPdfDocument
    ) : ClinicalPdfPage {
        var finished = false

        override fun drawText(text: String, x: Float, baseline: Float, paint: Paint) {
            checkDrawable()
            owner.drawnText += text
        }

        override fun drawLine(
            startX: Float,
            startY: Float,
            stopX: Float,
            stopY: Float,
            paint: Paint
        ) {
            checkDrawable()
        }

        private fun checkDrawable() {
            check(!owner.closed)
            check(owner.activePage === this)
            check(!finished)
        }
    }
}
