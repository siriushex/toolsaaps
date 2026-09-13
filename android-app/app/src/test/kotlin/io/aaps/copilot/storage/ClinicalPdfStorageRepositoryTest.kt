package io.aaps.copilot.storage

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.report.ClinicalPdfContentCursor
import io.aaps.copilot.report.ClinicalPdfContentIdentity
import io.aaps.copilot.report.ClinicalPdfContentSource
import io.aaps.copilot.report.ClinicalPdfPlan
import io.aaps.copilot.report.ClinicalPdfRenderResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClinicalPdfStorageRepositoryTest {
    private lateinit var root: File
    private lateinit var stageDir: File
    private lateinit var shareDir: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("clinical-pdf-storage-").toFile()
        stageDir = File(root, "clinical-pdf").apply { mkdirs() }
        shareDir = File(root, "clinical-pdf-share").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun validRenderFlushesSyncsClosesValidatesAndAtomicallyPromotes() = runTest {
        val bytes = validPdf(512)
        val events = mutableListOf<String>()
        val repository = repository(
            renderBytes = bytes,
            pageCount = 2,
            expectedPages = 2,
            writerFactory = trackingWriterFactory(events)
        )

        val result = repository.stage(SOURCE)

        assertThat(result).isInstanceOf(ClinicalPdfStageResult.Ready::class.java)
        val artifact = (result as ClinicalPdfStageResult.Ready).artifact
        assertThat(artifact.file.name).endsWith(".ready.pdf")
        assertThat(artifact.file.readBytes()).isEqualTo(bytes)
        assertThat(artifact.sizeBytes).isEqualTo(bytes.size.toLong())
        assertThat(artifact.sha256).isEqualTo(sha256(bytes))
        assertThat(artifact.pageCount).isEqualTo(2)
        assertThat(stageDir.listFiles().orEmpty().map(File::getName))
            .containsExactly(artifact.file.name)
        assertThat(events).containsExactly("flush", "sync", "close").inOrder()
    }

    @Test
    fun exactSixteenMiBIsAcceptedAndOneByteOverIsRejectedWithoutArtifact() = runTest {
        val accepted = repository(
            renderBytes = validPdf(ClinicalPdfStorageRepository.MAX_PDF_BYTES.toInt()),
            pageCount = 1
        ).stage(SOURCE)

        assertThat(accepted).isInstanceOf(ClinicalPdfStageResult.Ready::class.java)
        (accepted as ClinicalPdfStageResult.Ready).artifact.file.delete()

        val rejected = repository(
            renderBytes = validPdf(ClinicalPdfStorageRepository.MAX_PDF_BYTES.toInt() + 1),
            pageCount = 1
        ).stage(SOURCE)

        assertThat(rejected).isEqualTo(ClinicalPdfStageResult.TooLarge)
        assertThat(stageDir.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun malformedHeaderTerminalMarkerEmptyAndPageMismatchFailClosed() = runTest {
        val malformed = listOf(
            ByteArray(0),
            "not-pdf%%EOF\n".encodeToByteArray(),
            "%PDF-1.4\ntruncated".encodeToByteArray(),
            "%PDF-1.4\n%%EOF\nX".encodeToByteArray()
        )

        malformed.forEach { bytes ->
            val result = repository(renderBytes = bytes, pageCount = 1).stage(SOURCE)
            assertThat(result.toString())
                .isEqualTo(ClinicalPdfStageResult.Failed.toString())
            assertThat(stageDir.listFiles().orEmpty()).isEmpty()
        }

        val mismatch = repository(
            renderBytes = validPdf(128),
            pageCount = 1,
            expectedPages = 2
        ).stage(SOURCE)
        val zeroPages = repository(
            renderBytes = validPdf(128),
            pageCount = 0,
            expectedPages = 1
        ).stage(SOURCE)

        assertThat(mismatch).isEqualTo(ClinicalPdfStageResult.Failed)
        assertThat(zeroPages).isEqualTo(ClinicalPdfStageResult.Failed)
        assertThat(stageDir.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun rendererTooLargeSourceChangedFailureAndCancellationRemovePart() = runTest {
        listOf(
            ClinicalPdfRenderResult.TooLarge(
                io.aaps.copilot.report.ClinicalPdfLimitDimension.ESTIMATED_OUTPUT_BYTES,
                1,
                2
            ) to ClinicalPdfStageResult.TooLarge,
            ClinicalPdfRenderResult.SourceChanged to ClinicalPdfStageResult.Failed
        ).forEach { (renderResult, expected) ->
            val repository = repository(renderResult = renderResult)
            assertThat(repository.stage(SOURCE)).isEqualTo(expected)
            assertThat(stageDir.listFiles().orEmpty()).isEmpty()
        }

        val cancelled = repository(renderFailure = CancellationException("private path"))
        var thrown: Throwable? = null
        try {
            cancelled.stage(SOURCE)
        } catch (error: Throwable) {
            thrown = error
        }
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(stageDir.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun cancellationDuringReadyArtifactHandoffRemovesPromotedFile() = runTest {
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).launch {
            repository(
                onRender = { currentCoroutineContext().cancel() }
            ).stage(SOURCE)
        }

        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(stageDir.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun cleanupUsesAgeAndCountBoundsAndNeverDeletesActiveArtifact() {
        val now = ClinicalPdfStorageRepository.STALE_AGE_MS + 5_000_000L
        val active = File(stageDir, "active.ready.pdf").apply {
            writeText("active")
            setLastModified(1L)
        }
        File(stageDir, "old.pdf.part").apply {
            writeText("part")
            setLastModified(1L)
        }
        val ready = (0 until ClinicalPdfStorageRepository.MAX_STAGED_FILES + 3).map { index ->
            File(stageDir, "$index.ready.pdf").apply {
                writeText("ready")
                setLastModified(now - index)
            }
        }
        val parts = (0 until ClinicalPdfStorageRepository.MAX_STAGED_FILES + 3).map { index ->
            File(stageDir, "$index.pdf.part").apply {
                writeText("part")
                setLastModified(now - 100L - index)
            }
        }
        val unrelated = File(stageDir, "keep.txt").apply { writeText("keep") }
        val repository = repository(clock = { now })

        repository.cleanup(activeFiles = setOf(active))

        assertThat(active.exists()).isTrue()
        assertThat(unrelated.exists()).isTrue()
        assertThat(File(stageDir, "old.pdf.part").exists()).isFalse()
        assertThat((ready + parts).count(File::exists))
            .isAtMost(ClinicalPdfStorageRepository.MAX_STAGED_FILES)
    }

    @Test
    fun releaseUnregistersBeforeDeferredDeletionAcrossRepeatedLifecycles() = runTest {
        val pendingDeletes = mutableListOf<File>()
        var nextId = 0
        val repository = repository(
            idGenerator = { "ticket-${nextId++}" },
            artifactDeleter = { pendingDeletes += it }
        )

        repeat(ClinicalPdfStorageRepository.MAX_STAGED_FILES + 3) {
            val result = repository.stage(SOURCE) as ClinicalPdfStageResult.Ready

            repository.release(result.artifact)

            assertThat(repository.activeFileCountForTest()).isEqualTo(0)
            assertThat(result.artifact.file.exists()).isTrue()
            pendingDeletes.removeAt(0).delete()
        }

        assertThat(pendingDeletes).isEmpty()
        assertThat(stageDir.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun safCopyUsesRwtAndVerifiesReadableDestinationWithoutRendering() = runTest {
        val bytes = validPdf(256)
        val artifact = readyArtifact(bytes)
        val saf = FakeSafAccess(readBack = bytes)
        var renderCalls = 0
        val repository = repository(
            renderBytes = bytes,
            safAccess = saf,
            onRender = { renderCalls += 1 }
        )

        val result = repository.copyToDestination(artifact, "destination")

        assertThat(result).isEqualTo(ClinicalPdfCopyResult.CopiedVerified)
        assertThat(saf.mode).isEqualTo("rwt")
        assertThat(saf.written.toByteArray()).isEqualTo(bytes)
        assertThat(saf.outputClosed).isTrue()
        assertThat(renderCalls).isEqualTo(0)
        assertThat(saf.deleted).isFalse()
    }

    @Test
    fun safMismatchOrWriteFailureDeletesDestinationAndUnreadableProviderIsBoundedSuccess() = runTest {
        val bytes = validPdf(256)
        val artifact = readyArtifact(bytes)
        val mismatch = FakeSafAccess(readBack = validPdf(257))

        assertThat(repository(safAccess = mismatch).copyToDestination(artifact, "destination"))
            .isEqualTo(ClinicalPdfCopyResult.Failed)
        assertThat(mismatch.deleted).isTrue()

        val failed = FakeSafAccess(readBack = bytes, failWrite = true)
        assertThat(repository(safAccess = failed).copyToDestination(artifact, "destination"))
            .isEqualTo(ClinicalPdfCopyResult.Failed)
        assertThat(failed.deleted).isTrue()

        val unreadable = FakeSafAccess(readBack = null)
        assertThat(repository(safAccess = unreadable).copyToDestination(artifact, "destination"))
            .isEqualTo(ClinicalPdfCopyResult.CopiedUnverifiedProvider)
        assertThat(unreadable.deleted).isFalse()
    }

    @Test
    fun safCancellationDeletesPartialDestinationAndPropagates() = runTest {
        val bytes = validPdf(256)
        val artifact = readyArtifact(bytes)
        val saf = FakeSafAccess(readBack = bytes, cancelWrite = true)

        var thrown: Throwable? = null
        try {
            repository(safAccess = saf).copyToDestination(artifact, "destination")
        } catch (error: Throwable) {
            thrown = error
        }

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(saf.deleted).isTrue()
        assertThat(saf.outputClosed).isTrue()
    }

    @Test
    fun cancellationDuringOpenOutputClosesReturnedStreamBeforeDeletingDestination() = runTest {
        val bytes = validPdf(256)
        val artifact = readyArtifact(bytes)
        lateinit var copyJob: Job
        val saf = CancellingOpenSafAccess(bytes, CancelDuringOpen.OUTPUT) {
            copyJob.cancel()
        }
        var result: ClinicalPdfCopyResult? = null
        copyJob = launch(start = CoroutineStart.LAZY) {
            result = repository(safAccess = saf).copyToDestination(artifact, "destination")
        }

        copyJob.start()
        copyJob.join()

        assertThat(copyJob.isCancelled).isTrue()
        assertThat(result).isNull()
        assertThat(saf.outputClosed).isTrue()
        assertThat(saf.deleted).isTrue()
        assertThat(saf.destinationExists).isFalse()
        assertThat(saf.events.indexOf("closeOutput"))
            .isLessThan(saf.events.indexOf("delete"))
    }

    @Test
    fun cancellationDuringOpenInputClosesReturnedStreamBeforeDeletingDestination() = runTest {
        val bytes = validPdf(256)
        val artifact = readyArtifact(bytes)
        lateinit var copyJob: Job
        val saf = CancellingOpenSafAccess(bytes, CancelDuringOpen.INPUT) {
            copyJob.cancel()
        }
        var result: ClinicalPdfCopyResult? = null
        copyJob = launch(start = CoroutineStart.LAZY) {
            result = repository(safAccess = saf).copyToDestination(artifact, "destination")
        }

        copyJob.start()
        copyJob.join()

        assertThat(copyJob.isCancelled).isTrue()
        assertThat(result).isNull()
        assertThat(saf.outputClosed).isTrue()
        assertThat(saf.inputClosed).isTrue()
        assertThat(saf.deleted).isTrue()
        assertThat(saf.destinationExists).isFalse()
        assertThat(saf.events.indexOf("closeInput"))
            .isLessThan(saf.events.indexOf("delete"))
    }

    @Test
    fun parentCancellationWhileSafWriteIsBlockedDeletesDestinationAfterIoReturns() = runTest {
        val bytes = validPdf(256)
        val artifact = readyArtifact(bytes)
        val saf = BlockingSafAccess(bytes)
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val result = AtomicReference<ClinicalPdfCopyResult?>()
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            result.set(
                repository(safAccess = saf, ioDispatcher = dispatcher)
                    .copyToDestination(artifact, "destination")
            )
        }

        assertThat(saf.writeStarted.await(5, TimeUnit.SECONDS)).isTrue()
        job.cancel()
        saf.allowWriteToFinish.countDown()
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(result.get()).isNull()
        assertThat(saf.deleted).isTrue()
        assertThat(saf.outputClosed).isTrue()
        dispatcher.close()
    }

    @Test
    fun fatalSafWriteDeletesPartialDestinationBeforePropagating() = runTest {
        val bytes = validPdf(256)
        val artifact = readyArtifact(bytes)
        val saf = FakeSafAccess(readBack = bytes, fatalWrite = true)
        var thrown: Throwable? = null

        try {
            repository(safAccess = saf).copyToDestination(artifact, "destination")
        } catch (error: Throwable) {
            thrown = error
        }

        assertThat(thrown).isInstanceOf(AssertionError::class.java)
        assertThat(saf.deleted).isTrue()
        assertThat(saf.outputClosed).isTrue()
    }

    @Test
    fun shareCreatesVerifiedNarrowCacheCopyWithReadGrantAndPdfMime() = runTest {
        val bytes = validPdf(256)
        val artifact = readyArtifact(bytes)
        val repository = ClinicalPdfShareRepository(
            shareDirectory = shareDir,
            uriFactory = { Uri.parse("content://io.aaps.predictivecopilot.clinical-pdf/${it.name}") },
            ioDispatcher = Dispatchers.Unconfined,
            clock = { 9_000L }
        )

        val result = repository.createShare(artifact)

        assertThat(result).isInstanceOf(ClinicalPdfShareResult.Ready::class.java)
        val payload = (result as ClinicalPdfShareResult.Ready).payload
        assertThat(payload.mimeType).isEqualTo("application/pdf")
        assertThat(payload.flags).isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertThat(payload.uri.scheme).isEqualTo("content")
        val copies = shareDir.listFiles().orEmpty()
        assertThat(copies).hasLength(1)
        assertThat(copies.single().readBytes()).isEqualTo(bytes)
    }

    @Test
    fun parentCancellationWhileShareWriteIsBlockedDeletesTempAfterIoReturns() = runTest {
        val bytes = validPdf(256)
        val artifact = readyArtifact(bytes)
        val writeStarted = CountDownLatch(1)
        val allowWriteToFinish = CountDownLatch(1)
        val outputClosed = AtomicReference(false)
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val repository = ClinicalPdfShareRepository(
            shareDirectory = shareDir,
            uriFactory = { Uri.parse("content://clinical-pdf/${it.name}") },
            writerFactory = ClinicalPdfShareWriterFactory { file ->
                val fileOutput = FileOutputStream(file)
                object : ClinicalPdfShareWriter {
                    override val stream: OutputStream = object : OutputStream() {
                        override fun write(value: Int) = fileOutput.write(value)

                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            writeStarted.countDown()
                            check(allowWriteToFinish.await(5, TimeUnit.SECONDS))
                            fileOutput.write(bytes, offset, length)
                        }
                    }

                    override fun flushAndSync() {
                        fileOutput.flush()
                        fileOutput.fd.sync()
                    }

                    override fun close() {
                        outputClosed.set(true)
                        fileOutput.close()
                    }
                }
            },
            ioDispatcher = dispatcher,
            clock = { 9_000L }
        )
        val result = AtomicReference<ClinicalPdfShareResult?>()
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            result.set(repository.createShare(artifact))
        }

        assertThat(writeStarted.await(5, TimeUnit.SECONDS)).isTrue()
        job.cancel()
        allowWriteToFinish.countDown()
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(result.get()).isNull()
        assertThat(outputClosed.get()).isTrue()
        assertThat(shareDir.listFiles().orEmpty()).isEmpty()
        dispatcher.close()
    }

    @Test
    fun fatalShareWriteDeletesPartialTempBeforePropagating() = runTest {
        val bytes = validPdf(256)
        val artifact = readyArtifact(bytes)
        val repository = ClinicalPdfShareRepository(
            shareDirectory = shareDir,
            uriFactory = { Uri.parse("content://clinical-pdf/${it.name}") },
            writerFactory = ClinicalPdfShareWriterFactory { file ->
                val output = FileOutputStream(file)
                object : ClinicalPdfShareWriter {
                    override val stream: OutputStream = object : OutputStream() {
                        override fun write(value: Int) = output.write(value)

                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            output.write(bytes, offset, 1)
                            throw AssertionError("fatal share write")
                        }
                    }

                    override fun flushAndSync() = Unit
                    override fun close() = output.close()
                }
            },
            ioDispatcher = Dispatchers.Unconfined,
            clock = { 9_000L }
        )
        var thrown: Throwable? = null

        try {
            repository.createShare(artifact)
        } catch (error: Throwable) {
            thrown = error
        }

        assertThat(thrown).isInstanceOf(AssertionError::class.java)
        assertThat(shareDir.listFiles().orEmpty()).isEmpty()
    }

    private fun repository(
        renderBytes: ByteArray = validPdf(128),
        pageCount: Int = 1,
        expectedPages: Int = pageCount,
        renderResult: ClinicalPdfRenderResult? = null,
        renderFailure: Throwable? = null,
        writerFactory: ClinicalPdfPartWriterFactory = realWriterFactory(),
        safAccess: ClinicalPdfSafAccess = FakeSafAccess(readBack = null),
        ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        clock: () -> Long = { 2_000_000L },
        onRender: suspend () -> Unit = {},
        idGenerator: () -> String = { "ticket" },
        artifactDeleter: (File) -> Unit = File::delete
    ) = ClinicalPdfStorageRepository(
        stagingDirectory = stageDir,
        renderer = ClinicalPdfSourceRenderer { _, output ->
            onRender()
            renderFailure?.let { throw it }
            output.write(renderBytes)
            renderResult ?: ClinicalPdfRenderResult.Rendered(
                ClinicalPdfPlan(
                    pageCount = expectedPages,
                    contentLineCount = 1,
                    sourceBlockCount = 1,
                    encodedInputBytes = 1,
                    estimatedOutputBytes = renderBytes.size.toLong(),
                    contentSha256 = "a".repeat(64)
                )
            )
        },
        pageCounter = ClinicalPdfPageCounter { pageCount },
        writerFactory = writerFactory,
        safAccess = safAccess,
        ioDispatcher = ioDispatcher,
        clock = clock,
        idGenerator = idGenerator,
        artifactDeleter = artifactDeleter
    )

    private fun trackingWriterFactory(events: MutableList<String>) =
        ClinicalPdfPartWriterFactory { file ->
            val output = FileOutputStream(file)
            object : ClinicalPdfPartWriter {
                override val stream: OutputStream = output
                override fun flushAndSync() {
                    events += "flush"
                    output.flush()
                    events += "sync"
                    output.fd.sync()
                }

                override fun close() {
                    events += "close"
                    output.close()
                }
            }
        }

    private fun realWriterFactory() = ClinicalPdfPartWriterFactory { file ->
        val output = FileOutputStream(file)
        object : ClinicalPdfPartWriter {
            override val stream: OutputStream = output
            override fun flushAndSync() {
                output.flush()
                output.fd.sync()
            }

            override fun close() = output.close()
        }
    }

    private fun readyArtifact(bytes: ByteArray): ClinicalPdfReadyArtifact {
        val file = File(stageDir, "fixture.ready.pdf").apply { writeBytes(bytes) }
        return ClinicalPdfReadyArtifact(
            file = file,
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256(bytes),
            pageCount = 1,
            createdAt = 1L
        )
    }

    private fun validPdf(size: Int): ByteArray {
        require(size >= 14)
        val header = "%PDF-1.4\n".encodeToByteArray()
        val eof = "\n%%EOF\n".encodeToByteArray()
        return ByteArray(size) { ' '.code.toByte() }.also { bytes ->
            header.copyInto(bytes)
            eof.copyInto(bytes, bytes.size - eof.size)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private class FakeSafAccess(
        private val readBack: ByteArray?,
        private val failWrite: Boolean = false,
        private val cancelWrite: Boolean = false,
        private val fatalWrite: Boolean = false
    ) : ClinicalPdfSafAccess {
        val written = ByteArrayOutputStream()
        var mode: String? = null
        var outputClosed = false
        var deleted = false

        override fun openOutput(destination: Any, mode: String): OutputStream? {
            this.mode = mode
            return object : OutputStream() {
                override fun write(value: Int) {
                    if (fatalWrite) throw AssertionError("fatal write")
                    if (cancelWrite) throw CancellationException("cancel")
                    if (failWrite) error("write failed")
                    written.write(value)
                }

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (fatalWrite) throw AssertionError("fatal write")
                    if (cancelWrite) throw CancellationException("cancel")
                    if (failWrite) error("write failed")
                    written.write(bytes, offset, length)
                }

                override fun close() {
                    outputClosed = true
                }
            }
        }

        override fun openInput(destination: Any): InputStream? =
            readBack?.let(::ByteArrayInputStream)

        override fun delete(destination: Any) {
            deleted = true
        }
    }

    private class BlockingSafAccess(
        private val readBack: ByteArray
    ) : ClinicalPdfSafAccess {
        val writeStarted = CountDownLatch(1)
        val allowWriteToFinish = CountDownLatch(1)
        var outputClosed = false
        var deleted = false

        override fun openOutput(destination: Any, mode: String): OutputStream =
            object : OutputStream() {
                override fun write(value: Int) = Unit

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    writeStarted.countDown()
                    check(allowWriteToFinish.await(5, TimeUnit.SECONDS))
                }

                override fun close() {
                    outputClosed = true
                }
            }

        override fun openInput(destination: Any): InputStream = ByteArrayInputStream(readBack)

        override fun delete(destination: Any) {
            deleted = true
        }
    }

    private enum class CancelDuringOpen { OUTPUT, INPUT }

    private class CancellingOpenSafAccess(
        private val readBack: ByteArray,
        private val cancelDuringOpen: CancelDuringOpen,
        private val cancel: () -> Unit
    ) : ClinicalPdfSafAccess {
        val events = mutableListOf<String>()
        var outputClosed = false
        var inputClosed = false
        var deleted = false
        var destinationExists = false

        override fun openOutput(destination: Any, mode: String): OutputStream {
            events += "openOutput"
            destinationExists = true
            if (cancelDuringOpen == CancelDuringOpen.OUTPUT) cancel()
            return object : ByteArrayOutputStream() {
                override fun close() {
                    outputClosed = true
                    events += "closeOutput"
                    super.close()
                }
            }
        }

        override fun openInput(destination: Any): InputStream {
            events += "openInput"
            if (cancelDuringOpen == CancelDuringOpen.INPUT) cancel()
            return object : ByteArrayInputStream(readBack) {
                override fun close() {
                    inputClosed = true
                    events += "closeInput"
                    super.close()
                }
            }
        }

        override fun delete(destination: Any) {
            events += "delete"
            deleted = true
            destinationExists = false
        }
    }

    private companion object {
        val SOURCE = object : ClinicalPdfContentSource {
            override val identity = ClinicalPdfContentIdentity(
                requestSha256 = "a".repeat(64),
                generatedAt = 1L,
                zoneId = "UTC",
                schemaVersion = 7
            )

            override fun open(): ClinicalPdfContentCursor = object : ClinicalPdfContentCursor {
                override fun next() = null
                override fun close() = Unit
            }
        }
    }
}
