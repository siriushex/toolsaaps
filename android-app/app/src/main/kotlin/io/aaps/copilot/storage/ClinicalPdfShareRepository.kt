package io.aaps.copilot.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class ClinicalPdfSharePayload(
    val uri: Uri,
    val mimeType: String,
    val flags: Int,
    internal val file: File? = null
)

internal sealed interface ClinicalPdfShareResult {
    data class Ready(val payload: ClinicalPdfSharePayload) : ClinicalPdfShareResult
    data object Failed : ClinicalPdfShareResult
}

internal interface ClinicalPdfShareWriter : AutoCloseable {
    val stream: OutputStream
    fun flushAndSync()
}

internal fun interface ClinicalPdfShareWriterFactory {
    fun open(file: File): ClinicalPdfShareWriter
}

internal class ClinicalPdfShareRepository internal constructor(
    private val shareDirectory: File,
    private val uriFactory: (File) -> Uri,
    private val writerFactory: ClinicalPdfShareWriterFactory = RealClinicalPdfShareWriterFactory,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    startupCleanup: Boolean = false
) {
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        shareDirectory = File(context.cacheDir, SHARE_DIRECTORY_NAME),
        uriFactory = { file ->
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.clinical-pdf",
                file
            )
        },
        writerFactory = RealClinicalPdfShareWriterFactory,
        ioDispatcher = ioDispatcher,
        clock = System::currentTimeMillis,
        startupCleanup = true
    )

    init {
        shareDirectory.mkdirs()
        if (startupCleanup) cleanup(deleteAll = true)
    }

    suspend fun createShare(artifact: ClinicalPdfReadyArtifact): ClinicalPdfShareResult {
        var temporaryCopy: File? = null
        try {
            return withContext(ioDispatcher) {
                if (!artifact.file.isFile || artifact.file.length() != artifact.sizeBytes) {
                    throw ClinicalPdfShareFailureException()
                }
                if (fileSha256Cancellable(artifact.file) != artifact.sha256) {
                    throw ClinicalPdfShareFailureException()
                }
                cleanup(deleteAll = false)
                val copy = File(shareDirectory, "clinical-report-${UUID.randomUUID()}.pdf")
                temporaryCopy = copy
                FileInputStream(artifact.file).use { input ->
                    writerFactory.open(copy).use { writer ->
                        copyShareCancellable(input, writer.stream)
                        currentCoroutineContext().ensureActive()
                        writer.flushAndSync()
                        currentCoroutineContext().ensureActive()
                    }
                }
                if (
                    copy.length() != artifact.sizeBytes ||
                    fileSha256Cancellable(copy) != artifact.sha256
                ) {
                    throw ClinicalPdfShareFailureException()
                }
                ClinicalPdfShareResult.Ready(
                    ClinicalPdfSharePayload(
                        uri = uriFactory(copy),
                        mimeType = PDF_MIME_TYPE,
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        file = copy
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            deleteTemporaryNonCancellable(temporaryCopy)
            throw cancelled
        } catch (fatal: Error) {
            deleteTemporaryNonCancellable(temporaryCopy)
            throw fatal
        } catch (_: Exception) {
            deleteTemporaryNonCancellable(temporaryCopy)
            return ClinicalPdfShareResult.Failed
        }
    }

    fun discard(payload: ClinicalPdfSharePayload) {
        val file = payload.file ?: return
        val safeFile = runCatching { file.canonicalFile }.getOrNull() ?: return
        val safeDirectory = runCatching { shareDirectory.canonicalFile }.getOrNull() ?: return
        if (
            safeFile.parentFile == safeDirectory &&
            safeFile.name.startsWith("clinical-report-") &&
            safeFile.name.endsWith(".pdf")
        ) {
            safeFile.delete()
        }
    }

    internal fun cleanup(deleteAll: Boolean) {
        val now = clock()
        val files = shareDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("clinical-report-") && it.name.endsWith(".pdf") }
        files.filter {
            deleteAll || isShareExpired(now, it.lastModified(), SHARE_MAX_AGE_MS)
        }.forEach(File::delete)
        shareDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("clinical-report-") && it.name.endsWith(".pdf") }
            .sortedByDescending(File::lastModified)
            .drop(MAX_SHARE_FILES)
            .forEach(File::delete)
    }

    private suspend fun deleteTemporaryNonCancellable(file: File?) {
        if (file == null) return
        withContext(NonCancellable + ioDispatcher) {
            file.delete()
        }
    }

    private companion object {
        const val SHARE_DIRECTORY_NAME = "clinical-pdf-share"
        const val PDF_MIME_TYPE = "application/pdf"
        const val MAX_SHARE_FILES = 4
        const val SHARE_MAX_AGE_MS = 60L * 60L * 1_000L
    }
}

private object RealClinicalPdfShareWriterFactory : ClinicalPdfShareWriterFactory {
    override fun open(file: File): ClinicalPdfShareWriter {
        val output = FileOutputStream(file)
        return object : ClinicalPdfShareWriter {
            override val stream: OutputStream = output

            override fun flushAndSync() {
                output.flush()
                output.fd.sync()
            }

            override fun close() = output.close()
        }
    }
}

private suspend fun fileSha256Cancellable(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            currentCoroutineContext().ensureActive()
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private suspend fun copyShareCancellable(input: FileInputStream, output: OutputStream) {
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

private fun isShareExpired(now: Long, modified: Long, maxAgeMs: Long): Boolean =
    modified <= 0L || now < modified || now - modified > maxAgeMs

private class ClinicalPdfShareFailureException : java.io.IOException()
