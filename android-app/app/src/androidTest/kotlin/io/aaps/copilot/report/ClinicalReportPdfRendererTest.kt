package io.aaps.copilot.report

import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.aaps.copilot.config.ClinicalAiProviderId
import io.aaps.copilot.data.repository.ClinicalAdvisoryPriority
import io.aaps.copilot.data.repository.ClinicalAdvisoryReport
import io.aaps.copilot.data.repository.ClinicalCareTeamDiscussionTopic
import io.aaps.copilot.data.repository.ClinicalCareTeamQuestion
import io.aaps.copilot.data.repository.ClinicalDataQuality
import io.aaps.copilot.data.repository.ClinicalDataQualityFlag
import io.aaps.copilot.data.repository.ClinicalEvidenceMetric
import io.aaps.copilot.data.repository.ClinicalEvidencePeriod
import io.aaps.copilot.data.repository.ClinicalFinding
import io.aaps.copilot.data.repository.ClinicalFindingConfidence
import io.aaps.copilot.data.repository.ClinicalLocalReport
import io.aaps.copilot.data.repository.ClinicalOpenAiMetadata
import io.aaps.copilot.data.repository.ClinicalOpenAiResult
import io.aaps.copilot.data.repository.ClinicalPatternDirection
import io.aaps.copilot.data.repository.ClinicalPatternTopic
import io.aaps.copilot.data.repository.ClinicalPeriodSummary
import io.aaps.copilot.data.repository.ClinicalRecommendation
import io.aaps.copilot.data.repository.ClinicalRejectionCounts
import io.aaps.copilot.data.repository.ClinicalSafetyObservation
import io.aaps.copilot.data.repository.ClinicalSourceRowCounts
import io.aaps.copilot.data.repository.ClinicalSummaryStatus
import io.aaps.copilot.data.repository.ClinicalTimeBand
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClinicalReportPdfRendererTest {

    @Test
    fun renderWritesA4PdfAndPaginatesLongDocument() {
        val document = longDocument()
        val renderer = ClinicalReportPdfRenderer()
        val source = ClinicalReportDocumentContentSource(document)
        val planned = renderer.plan(source) as ClinicalPdfPlanResult.Ready
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("clinical-report-", ".pdf", context.cacheDir)

        try {
            val rendered = file.outputStream().use {
                renderBlocking(renderer, source, it) as ClinicalPdfRenderResult.Rendered
            }

            assertEquals(planned.plan, rendered.plan)
            assertEquals("%PDF-", file.readBytes().take(5).toByteArray().decodeToString())
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { pdf ->
                    assertEquals(rendered.plan.pageCount, pdf.pageCount)
                    assertTrue(pdf.pageCount > 1)
                    repeat(pdf.pageCount) { pageNumber ->
                        pdf.openPage(pageNumber).use { page ->
                            assertEquals(595, page.width)
                            assertEquals(842, page.height)
                        }
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun documentSourceContainsRequiredHeadingsMetadataAndDisclaimer() {
        val document = longDocument()
        val text = sourceText(document)

        assertTrue(text.contains(document.title))
        assertTrue(text.contains("Generated"))
        assertTrue(text.contains("7 days"))
        assertTrue(text.contains("30 days"))
        assertTrue(text.contains("Provider: ANTHROPIC"))
        assertTrue(text.contains("Requested model: claude-sonnet-5"))
        assertTrue(text.contains("Actual model: claude-sonnet-5-20260715"))
        assertTrue(text.contains("Dataset SHA-256: $DATASET_HASH"))
        assertTrue(text.contains("Dataset schema: 5"))
        assertTrue(text.contains("Data quality"))
        assertTrue(text.contains("Advisory findings"))
        assertTrue(text.contains("Safety observations"))
        assertTrue(text.contains("Discussion topics"))
        assertTrue(text.contains("Care team questions"))
        assertTrue(text.contains(document.disclaimer))
    }

    @Test
    fun rendererInputAndGeneratedBytesExcludeSensitiveFixtures() {
        val document = longDocument()
        val renderer = ClinicalReportPdfRenderer()
        val sourceText = sourceText(document)
        val output = ByteArrayOutputStream()

        val rendered = renderBlocking(
            renderer,
            ClinicalReportDocumentContentSource(document),
            output
        )
        assertTrue(rendered is ClinicalPdfRenderResult.Rendered)

        val pdfBytes = output.toByteArray().toString(Charsets.ISO_8859_1)
        SENSITIVE_FIXTURES.forEach { secret ->
            assertFalse(document.flattenedText().contains(secret))
            assertFalse(sourceText.contains(secret))
            assertFalse(pdfBytes.contains(secret))
        }
    }

    @Test
    fun outputFailureFinishesActivePageAndClosesDocument() {
        val backend = TrackingPdfDocument()
        val factory = TrackingPdfDocumentFactory(backend)
        val renderer = ClinicalReportPdfRenderer(
            factory
        )

        assertThrows(IOException::class.java) {
            renderBlocking(
                renderer,
                ClinicalReportDocumentContentSource(longDocument()),
                ThrowingOutputStream(IOException("write failed"))
            )
        }

        assertEquals(1, factory.createCalls)
        assertTrue(backend.startedPages > 0)
        assertEquals(backend.startedPages, backend.finishCalls)
        assertEquals(1, backend.closeCalls)
    }

    @Test
    fun outputStreamCancellationFinishesActivePageAndClosesDocument() {
        val backend = TrackingPdfDocument()
        val factory = TrackingPdfDocumentFactory(backend)
        val renderer = ClinicalReportPdfRenderer(
            factory
        )

        assertThrows(CancellationException::class.java) {
            renderBlocking(
                renderer,
                ClinicalReportDocumentContentSource(longDocument()),
                ThrowingOutputStream(CancellationException("cancelled"))
            )
        }

        assertEquals(1, factory.createCalls)
        assertTrue(backend.startedPages > 0)
        assertEquals(backend.startedPages, backend.finishCalls)
        assertEquals(1, backend.closeCalls)
    }

    @Test
    fun finishFailureIsNotRetriedAndCloseFailureIsSuppressedOnPrimary() {
        val firstFinishFailure = IllegalStateException("finish failed")
        val retryFinishFailure = IllegalStateException("finish retried")
        val closeFailure = IllegalStateException("close failed")
        val backend = TrackingPdfDocument(
            finishFailures = ArrayDeque(
                listOf(firstFinishFailure, retryFinishFailure)
            ),
            closeFailure = closeFailure
        )
        val factory = TrackingPdfDocumentFactory(backend)
        val renderer = ClinicalReportPdfRenderer(factory)

        val thrown = assertThrows(IllegalStateException::class.java) {
            renderBlocking(
                renderer,
                ClinicalReportDocumentContentSource(longDocument()),
                ByteArrayOutputStream()
            )
        }

        assertSame(firstFinishFailure, thrown)
        assertEquals(1, factory.createCalls)
        assertEquals(1, backend.finishCalls)
        assertEquals(1, backend.closeCalls)
        assertEquals(listOf(closeFailure), thrown.suppressed.toList())
    }

    @Test
    fun drawFailureKeepsFinishAndCloseFailuresSuppressedAndCleansUpOnce() {
        val drawFailure = IllegalArgumentException("draw failed")
        val finishFailure = IllegalStateException("cleanup finish failed")
        val closeFailure = IllegalStateException("cleanup close failed")
        val backend = TrackingPdfDocument(
            onDraw = { throw drawFailure },
            finishFailures = ArrayDeque(listOf(finishFailure)),
            closeFailure = closeFailure
        )
        val factory = TrackingPdfDocumentFactory(backend)
        val renderer = ClinicalReportPdfRenderer(factory)

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            renderBlocking(
                renderer,
                ClinicalReportDocumentContentSource(longDocument()),
                ByteArrayOutputStream()
            )
        }

        assertSame(drawFailure, thrown)
        assertEquals(1, backend.finishCalls)
        assertEquals(1, backend.closeCalls)
        assertEquals(listOf(finishFailure), thrown.suppressed.toList())
        assertEquals(listOf(closeFailure), finishFailure.suppressed.toList())
    }

    @Test
    fun coroutineCancellationDuringDrawStopsRenderingAndCleansUpActivePage() =
        runBlocking {
            val backend = TrackingPdfDocument()
            val factory = TrackingPdfDocumentFactory(backend)
            val renderer = ClinicalReportPdfRenderer(factory)
            lateinit var rendering: Deferred<ClinicalPdfRenderResult>
            backend.onDraw = {
                if (backend.drawCalls == 1) {
                    rendering.cancel(CancellationException("cancelled during draw"))
                }
            }
            rendering = async(start = CoroutineStart.LAZY) {
                renderer.renderSource(
                    ClinicalReportDocumentContentSource(longDocument()),
                    ByteArrayOutputStream()
                )
            }

            rendering.start()
            val thrown = runCatching { rendering.await() }.exceptionOrNull()

            assertNotNull(thrown)
            assertTrue(thrown is CancellationException)
            assertEquals(1, factory.createCalls)
            assertEquals(1, backend.startedPages)
            assertEquals(1, backend.drawCalls)
            assertEquals(1, backend.finishCalls)
            assertEquals(1, backend.closeCalls)
        }

    private fun renderBlocking(
        renderer: ClinicalReportPdfRenderer,
        source: ClinicalPdfContentSource,
        output: OutputStream
    ): ClinicalPdfRenderResult = runBlocking { renderer.renderSource(source, output) }

    private fun sourceText(document: ClinicalReportDocument): String = buildString {
        ClinicalReportDocumentContentSource(document).open().use { cursor ->
            while (true) appendLine(cursor.next()?.text ?: break)
        }
    }

    private fun longDocument(): ClinicalReportDocument {
        val findings = (0 until 32).map { index ->
            ClinicalFinding(
                topic = ClinicalPatternTopic.entries[
                    index % ClinicalPatternTopic.entries.size
                ],
                period = ClinicalEvidencePeriod.entries[
                    index % ClinicalEvidencePeriod.entries.size
                ],
                direction = ClinicalPatternDirection.entries[
                    index % ClinicalPatternDirection.entries.size
                ],
                confidence = ClinicalFindingConfidence.entries[
                    index % ClinicalFindingConfidence.entries.size
                ],
                timeBand = ClinicalTimeBand.entries[
                    index % ClinicalTimeBand.entries.size
                ],
                evidenceMetric = ClinicalEvidenceMetric.entries[
                    index % ClinicalEvidenceMetric.entries.size
                ],
                evidenceValue = index.toDouble()
            )
        }
        val complete = completeReport().let {
            it.copy(report = it.report.copy(patterns = findings))
        }
        return ClinicalReportDocument.from(localReport(), complete)
    }

    private fun localReport() = ClinicalLocalReport(
        summary7d = summary(
            days = 7,
            fromTs = GENERATED_AT - 7 * DAY_MS,
            coverage = 96.5,
            mean = 6.2,
            expected = 2_016,
            covered = 1_945,
            missing = 71
        ),
        summary30d = summary(
            days = 30,
            fromTs = GENERATED_AT - 30 * DAY_MS,
            coverage = 91.0,
            mean = 6.5,
            expected = 8_640,
            covered = 7_862,
            missing = 778
        ),
        requestHash = DATASET_HASH,
        generatedAt = GENERATED_AT,
        zoneId = "Asia/Tbilisi"
    )

    private fun summary(
        days: Int,
        fromTs: Long,
        coverage: Double,
        mean: Double,
        expected: Int,
        covered: Int,
        missing: Int
    ) = ClinicalPeriodSummary(
        days = days,
        fromTs = fromTs,
        throughTs = GENERATED_AT,
        coveragePct = coverage,
        meanMmol = mean,
        medianMmol = mean - 0.2,
        coefficientOfVariationPct = 24.0,
        timeBelow4Pct = 1.5,
        timeInRangePct = 82.0,
        timeAboveRangePct = 16.5,
        totalInsulinU = days * 20.0,
        totalCarbsG = days * 60.0,
        meanTargetMmol = 6.1,
        weekdayPattern = emptyList(),
        weekendPattern = emptyList(),
        quality = ClinicalDataQuality(
            expectedBuckets = expected,
            coveredBuckets = covered,
            missingBuckets = missing,
            maxGapMinutes = 15,
            rejected = ClinicalRejectionCounts(
                glucose = 1,
                therapy = 2,
                target = 3,
                forecast = 4,
                telemetry = 5
            )
        )
    )

    private fun completeReport() = ClinicalOpenAiResult(
        report = ClinicalAdvisoryReport(
            summary7dStatus = ClinicalSummaryStatus.HIGH_VARIABILITY,
            summary30dStatus = ClinicalSummaryStatus.STABLE,
            dataQuality = listOf(
                ClinicalDataQualityFlag.PARTIAL_COVERAGE,
                ClinicalDataQualityFlag.MISSING_INTERVALS
            ),
            patterns = emptyList(),
            safetyObservations = listOf(
                ClinicalSafetyObservation.SENSOR_RELIABILITY_CONCERN,
                ClinicalSafetyObservation.RECURRENT_LOW_PATTERN
            ),
            recommendations = listOf(
                ClinicalRecommendation(
                    careTeamDiscussionTopic = ClinicalCareTeamDiscussionTopic.ISF_CR_REVIEW,
                    priority = ClinicalAdvisoryPriority.MEDIUM,
                    evidenceFindingIndices = listOf(0),
                    period = ClinicalEvidencePeriod.LAST_30_DAYS
                ),
                ClinicalRecommendation(
                    careTeamDiscussionTopic =
                        ClinicalCareTeamDiscussionTopic.SENSOR_RELIABILITY,
                    priority = ClinicalAdvisoryPriority.HIGH,
                    evidenceFindingIndices = listOf(0),
                    period = ClinicalEvidencePeriod.LAST_7_DAYS
                )
            ),
            careTeamQuestions = listOf(
                ClinicalCareTeamQuestion.SENSOR_RELIABILITY_CONTEXT,
                ClinicalCareTeamQuestion.ISF_CR_CONTEXT
            )
        ),
        metadata = ClinicalOpenAiMetadata(
            model = "claude-sonnet-5-20260715",
            requestedModel = "claude-sonnet-5",
            systemFingerprint = SENSITIVE_FIXTURES[0],
            schemaName = "clinical_advisory_report_v3",
            schemaVersion = 3,
            datasetSchemaVersion = 5,
            requestHash = DATASET_HASH,
            chunkCount = 2,
            usedSynthesis = true,
            coverageLedgerHash = SENSITIVE_FIXTURES[1],
            maxRequestBytes = 10,
            maxResponseBytes = 10,
            totalRequestBytes = 20,
            totalResponseBytes = 20,
            durationMs = 100,
            sourceRows = ClinicalSourceRowCounts(
                glucose = 100,
                insulin = 20,
                carbs = 10,
                targets = 5
            ),
            providerId = ClinicalAiProviderId.ANTHROPIC,
            requestedProviderId = ClinicalAiProviderId.ANTHROPIC
        )
    )

    private class TrackingPdfDocumentFactory(
        private val document: TrackingPdfDocument
    ) : ClinicalPdfDocumentFactory {
        var createCalls = 0

        override fun create(): ClinicalPdfDocument {
            createCalls += 1
            return document
        }
    }

    private class TrackingPdfDocument(
        var onDraw: () -> Unit = {},
        private val finishFailures: ArrayDeque<Throwable> = ArrayDeque(),
        private val closeFailure: Throwable? = null
    ) : ClinicalPdfDocument {
        var startedPages = 0
        var finishCalls = 0
        var drawCalls = 0
        var closeCalls = 0

        override fun startPage(pageNumber: Int): ClinicalPdfPage {
            startedPages += 1
            return object : ClinicalPdfPage {
                override fun drawText(
                    text: String,
                    x: Float,
                    baseline: Float,
                    paint: Paint
                ) {
                    drawCalls += 1
                    onDraw()
                }

                override fun drawLine(
                    startX: Float,
                    startY: Float,
                    stopX: Float,
                    stopY: Float,
                    paint: Paint
                ) = Unit
            }
        }

        override fun finishPage(page: ClinicalPdfPage) {
            finishCalls += 1
            finishFailures.removeFirstOrNull()?.let { throw it }
        }

        override fun writeTo(output: OutputStream) {
            output.write(1)
        }

        override fun close() {
            closeCalls += 1
            closeFailure?.let { throw it }
        }
    }

    private class ThrowingOutputStream(
        private val failure: RuntimeException
    ) : OutputStream() {
        constructor(failure: IOException) : this(RuntimeException(failure))

        override fun write(value: Int) {
            val cause = failure.cause
            if (cause is IOException) throw cause
            throw failure
        }
    }

    private companion object {
        const val GENERATED_AT = 1_790_000_000_000L
        const val DAY_MS = 86_400_000L
        const val DATASET_HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val SENSITIVE_FIXTURES = listOf(
            "sk-test-secret-that-must-never-appear",
            "nightscout.example/internal-row-id-1",
            "android-id-1",
            "patient@example.invalid"
        )
    }
}
