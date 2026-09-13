package io.aaps.copilot.storage

import android.content.ContentResolver
import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.aaps.copilot.report.ClinicalPdfContentSource
import io.aaps.copilot.report.ClinicalPdfRenderResult
import io.aaps.copilot.report.ClinicalReportPdfRenderer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ClinicalPdfReadyArtifact(
    internal val file: File,
    val sizeBytes: Long,
    val sha256: String,
    val pageCount: Int,
    val createdAt: Long
)

internal sealed interface ClinicalPdfStageResult {
    data class Ready(val artifact: ClinicalPdfReadyArtifact) : ClinicalPdfStageResult
    data object TooLarge : ClinicalPdfStageResult
    data object Failed : ClinicalPdfStageResult
}

internal sealed interface ClinicalPdfCopyResult {
    data object CopiedVerified : ClinicalPdfCopyResult
    data object CopiedUnverifiedProvider : ClinicalPdfCopyResult
    data object Failed : ClinicalPdfCopyResult
}

internal fun interface ClinicalPdfSourceRenderer {
    suspend fun render(source: ClinicalPdfContentSource, output: OutputStream): ClinicalPdfRenderResult
}

internal fun interface ClinicalPdfPageCounter {
    fun count(file: File): Int
}

internal interface ClinicalPdfPartWriter : AutoCloseable {
    val stream: OutputStream
    fun flushAndSync()
}

internal fun interface ClinicalPdfPartWriterFactory {
    fun open(file: File): ClinicalPdfPartWriter
}

internal interface ClinicalPdfSafAccess {
    fun openOutput(destination: Any, mode: String): OutputStream?
    fun openInput(destination: Any): InputStream?
    fun delete(destination: Any)
}

internal class ClinicalPdfStorageRepository internal constructor(
    private val stagingDirectory: File,
    private val renderer: ClinicalPdfSourceRenderer,
    private val pageCounter: ClinicalPdfPageCounter,
    private val writerFactory: ClinicalPdfPartWriterFactory,
    private val safAccess: ClinicalPdfSafAccess,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val idGenerator: () -> String,
    private val artifactDeleter: (File) -> Unit,
    startupCleanup: Boolean = false
) {
    private val activeFiles = linkedSetOf<File>()
    private val activeLock = Any()

    constructor(
        context: Context,
        renderer: ClinicalReportPdfRenderer,
        cleanupScope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        stagingDirectory = File(context.noBackupFilesDir, STAGING_DIRECTORY_NAME),
        renderer = ClinicalPdfSourceRenderer(renderer::renderSource),
        pageCounter = AndroidClinicalPdfPageCounter,
        writerFactory = RealClinicalPdfPartWriterFactory,
        safAccess = ContentResolverClinicalPdfSafAccess(context.contentResolver),
        ioDispatcher = ioDispatcher,
        clock = System::currentTimeMillis,
        idGenerator = { UUID.randomUUID().toString() },
        artifactDeleter = { file ->
            cleanupScope.launch(ioDispatcher) {
                file.delete()
            }
        },
        startupCleanup = true
    )

    init {
        stagingDirectory.mkdirs()
        if (startupCleanup) cleanup()
    }

    suspend fun stage(source: ClinicalPdfContentSource): ClinicalPdfStageResult {
        var promotedArtifact: ClinicalPdfReadyArtifact? = null
        try {
            return withContext(ioDispatcher) {
                cleanup()
                val id = idGenerator().takeIf(::isSafeId)
                    ?: return@withContext ClinicalPdfStageResult.Failed
                val part = File(stagingDirectory, "$id.pdf.part")
                val ready = File(stagingDirectory, "$id.ready.pdf")
                if (part.exists() || ready.exists()) return@withContext ClinicalPdfStageResult.Failed
                markActive(part)
                try {
                    val renderResult = writerFactory.open(part).use { writer ->
                        val result = renderer.render(
                            source,
                            SizeBoundOutputStream(writer.stream, MAX_PDF_BYTES)
                        )
                        if (result is ClinicalPdfRenderResult.Rendered) writer.flushAndSync()
                        result
                    }
                    val plan = when (renderResult) {
                        is ClinicalPdfRenderResult.Rendered -> renderResult.plan
                        is ClinicalPdfRenderResult.TooLarge ->
                            return@withContext ClinicalPdfStageResult.TooLarge
                        ClinicalPdfRenderResult.SourceChanged ->
                            return@withContext ClinicalPdfStageResult.Failed
                    }
                    val size = part.length()
                    if (size <= 0L || size > MAX_PDF_BYTES || !hasValidPdfEnvelope(part)) {
                        return@withContext ClinicalPdfStageResult.Failed
                    }
                    val actualPages = pageCounter.count(part)
                    if (actualPages <= 0 || actualPages != plan.pageCount) {
                        return@withContext ClinicalPdfStageResult.Failed
                    }
                    val hash = sha256(part)
                    Files.move(part.toPath(), ready.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    unmarkActive(part)
                    markActive(ready)
                    val artifact = ClinicalPdfReadyArtifact(
                        file = ready,
                        sizeBytes = size,
                        sha256 = hash,
                        pageCount = actualPages,
                        createdAt = clock()
                    )
                    promotedArtifact = artifact
                    ClinicalPdfStageResult.Ready(artifact)
                } catch (tooLarge: ClinicalPdfOutputTooLargeException) {
                    ClinicalPdfStageResult.TooLarge
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (fatal: Error) {
                    throw fatal
                } catch (_: Exception) {
                    ClinicalPdfStageResult.Failed
                } finally {
                    unmarkActive(part)
                    if (part.exists()) part.delete()
                }
            }
        } catch (cancelled: CancellationException) {
            promotedArtifact?.let(::release)
            throw cancelled
        }
    }

    suspend fun copyToDestination(
        artifact: ClinicalPdfReadyArtifact,
        destination: Any
    ): ClinicalPdfCopyResult {
        try {
            return withContext(ioDispatcher) {
                if (!artifact.isStillValidCancellable()) throw ClinicalPdfCopyFailureException()
                currentCoroutineContext().ensureActive()
                val output = safAccess.openOutput(destination, SAF_WRITE_MODE)
                    ?: throw ClinicalPdfCopyFailureException()
                output.use { destinationStream ->
                    currentCoroutineContext().ensureActive()
                    FileInputStream(artifact.file).use { source ->
                        copyCancellable(source, destinationStream)
                    }
                    currentCoroutineContext().ensureActive()
                    destinationStream.flush()
                    currentCoroutineContext().ensureActive()
                }
                val input = try {
                    safAccess.openInput(destination)
                } catch (_: java.io.FileNotFoundException) {
                    null
                } catch (_: SecurityException) {
                    null
                } catch (_: UnsupportedOperationException) {
                    null
                }
                if (input == null) {
                    return@withContext ClinicalPdfCopyResult.CopiedUnverifiedProvider
                }
                val observed = input.use {
                    currentCoroutineContext().ensureActive()
                    digestAndSizeCancellable(it)
                }
                if (observed.sizeBytes != artifact.sizeBytes || observed.sha256 != artifact.sha256) {
                    throw ClinicalPdfCopyFailureException()
                }
                ClinicalPdfCopyResult.CopiedVerified
            }
        } catch (cancelled: CancellationException) {
            deleteDestinationNonCancellable(destination)
            throw cancelled
        } catch (fatal: Error) {
            deleteDestinationNonCancellable(destination)
            throw fatal
        } catch (_: Exception) {
            deleteDestinationNonCancellable(destination)
            return ClinicalPdfCopyResult.Failed
        }
    }

    fun release(artifact: ClinicalPdfReadyArtifact) {
        unmarkActive(artifact.file)
        artifactDeleter(artifact.file)
    }

    internal fun activeFileCountForTest(): Int = synchronized(activeLock) { activeFiles.size }

    internal fun cleanup(activeFiles: Set<File> = synchronized(activeLock) { this.activeFiles.toSet() }) {
        val protected = activeFiles.mapTo(mutableSetOf()) { it.canonicalFile }
        val now = clock()
        val candidates = stagingDirectory.listFiles().orEmpty()
            .filter { it.isFile && (it.name.endsWith(PART_SUFFIX) || it.name.endsWith(READY_SUFFIX)) }
            .filterNot { it.canonicalFile in protected }
        candidates.filter { isExpired(now, it.lastModified()) }.forEach(File::delete)
        stagingDirectory.listFiles().orEmpty()
            .filter { it.isFile && (it.name.endsWith(PART_SUFFIX) || it.name.endsWith(READY_SUFFIX)) }
            .filterNot { it.canonicalFile in protected }
            .sortedByDescending(File::lastModified)
            .drop(MAX_STAGED_FILES)
            .forEach(File::delete)
    }

    private suspend fun ClinicalPdfReadyArtifact.isStillValidCancellable(): Boolean {
        if (!file.isFile || file.length() != sizeBytes || sizeBytes !in 1..MAX_PDF_BYTES) {
            return false
        }
        return try {
            FileInputStream(file).use { digestAndSizeCancellable(it).sha256 } == sha256
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun deleteDestinationNonCancellable(destination: Any) {
        withContext(NonCancellable + ioDispatcher) {
            runCatching { safAccess.delete(destination) }
        }
    }

    private fun markActive(file: File) = synchronized(activeLock) { activeFiles += file }
    private fun unmarkActive(file: File) = synchronized(activeLock) { activeFiles -= file }

    companion object {
        const val MAX_PDF_BYTES = 16L * 1024L * 1024L
        const val MAX_STAGED_FILES = 8
        internal const val STALE_AGE_MS = 24L * 60L * 60L * 1_000L
        private const val STAGING_DIRECTORY_NAME = "clinical-pdf"
        private const val PART_SUFFIX = ".pdf.part"
        private const val READY_SUFFIX = ".ready.pdf"
        private const val SAF_WRITE_MODE = "rwt"
    }
}

private object AndroidClinicalPdfPageCounter : ClinicalPdfPageCounter {
    override fun count(file: File): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use(PdfRenderer::getPageCount)
        }
}

private object RealClinicalPdfPartWriterFactory : ClinicalPdfPartWriterFactory {
    override fun open(file: File): ClinicalPdfPartWriter {
        val output = FileOutputStream(file)
        return object : ClinicalPdfPartWriter {
            override val stream: OutputStream = output
            override fun flushAndSync() {
                output.flush()
                output.fd.sync()
            }

            override fun close() = output.close()
        }
    }
}

private class ContentResolverClinicalPdfSafAccess(
    private val contentResolver: ContentResolver
) : ClinicalPdfSafAccess {
    override fun openOutput(destination: Any, mode: String): OutputStream? =
        contentResolver.openOutputStream(requireUri(destination), mode)

    override fun openInput(destination: Any): InputStream? =
        contentResolver.openInputStream(requireUri(destination))

    override fun delete(destination: Any) {
        contentResolver.delete(requireUri(destination), null, null)
    }

    private fun requireUri(destination: Any): Uri =
        requireNotNull(destination as? Uri) { "Unexpected PDF destination type" }
}

private data class ClinicalPdfDigest(val sizeBytes: Long, val sha256: String)

private fun digestAndSize(input: InputStream): ClinicalPdfDigest {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var size = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        size = Math.addExact(size, read.toLong())
        digest.update(buffer, 0, read)
    }
    return ClinicalPdfDigest(size, digest.digest().toHex())
}

private suspend fun digestAndSizeCancellable(input: InputStream): ClinicalPdfDigest {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var size = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        currentCoroutineContext().ensureActive()
        if (read < 0) break
        if (read == 0) continue
        size = Math.addExact(size, read.toLong())
        digest.update(buffer, 0, read)
    }
    return ClinicalPdfDigest(size, digest.digest().toHex())
}

private suspend fun copyCancellable(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        currentCoroutineContext().ensureActive()
        if (read < 0) return
        if (read == 0) continue
        output.write(buffer, 0, read)
        currentCoroutineContext().ensureActive()
    }
}

private fun sha256(file: File): String = FileInputStream(file).use { digestAndSize(it).sha256 }

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

private fun hasValidPdfEnvelope(file: File): Boolean {
    if (file.length() < PDF_HEADER.size + PDF_EOF.size) return false
    FileInputStream(file).use { input ->
        val header = ByteArray(PDF_HEADER.size)
        if (input.read(header) != header.size || !header.contentEquals(PDF_HEADER)) return false
    }
    val tailSize = minOf(file.length(), PDF_TAIL_SCAN_BYTES.toLong()).toInt()
    val tail = ByteArray(tailSize)
    java.io.RandomAccessFile(file, "r").use { random ->
        random.seek(file.length() - tailSize)
        random.readFully(tail)
    }
    var end = tail.lastIndex
    while (end >= 0 && tail[end] in PDF_TRAILING_WHITESPACE) end -= 1
    if (end + 1 < PDF_EOF.size) return false
    val start = end + 1 - PDF_EOF.size
    return tail.copyOfRange(start, end + 1).contentEquals(PDF_EOF)
}

private fun isExpired(now: Long, modified: Long): Boolean =
    modified <= 0L || now < modified || now - modified > ClinicalPdfStorageRepository.STALE_AGE_MS

private fun isSafeId(id: String): Boolean =
    id.isNotBlank() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

private class SizeBoundOutputStream(
    private val delegate: OutputStream,
    private val limit: Long
) : OutputStream() {
    private var size = 0L

    override fun write(value: Int) {
        ensureCapacity(1)
        delegate.write(value)
        size += 1L
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        ensureCapacity(length)
        delegate.write(bytes, offset, length)
        size += length
    }

    override fun flush() = delegate.flush()

    private fun ensureCapacity(additional: Int) {
        if (additional > limit - size) throw ClinicalPdfOutputTooLargeException()
    }
}

private class ClinicalPdfOutputTooLargeException : java.io.IOException()
private class ClinicalPdfCopyFailureException : java.io.IOException()

private val PDF_HEADER = "%PDF-".encodeToByteArray()
private val PDF_EOF = "%%EOF".encodeToByteArray()
private val PDF_TRAILING_WHITESPACE = setOf(
    ' '.code.toByte(), '\t'.code.toByte(), '\n'.code.toByte(),
    '\u000c'.code.toByte(), '\r'.code.toByte()
)
private const val PDF_TAIL_SCAN_BYTES = 1024
