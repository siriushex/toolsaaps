package io.aaps.copilot.storage

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.aaps.copilot.report.ClinicalPdfContentBlock
import io.aaps.copilot.report.ClinicalPdfContentCursor
import io.aaps.copilot.report.ClinicalPdfContentIdentity
import io.aaps.copilot.report.ClinicalPdfContentRole
import io.aaps.copilot.report.ClinicalPdfContentSource
import io.aaps.copilot.report.ClinicalReportPdfRenderer
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClinicalPdfStorageRepositoryDeviceTest {

    @Test
    fun stagedArtifactPageCountMatchesAndroidPdfRenderer() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = ClinicalPdfStorageRepository(
            context = context,
            renderer = ClinicalReportPdfRenderer(),
            cleanupScope = this
        )

        val result = repository.stage(source(blocks = 400))

        assertTrue(result is ClinicalPdfStageResult.Ready)
        val artifact = (result as ClinicalPdfStageResult.Ready).artifact
        val devicePageCount = ParcelFileDescriptor.open(
            artifact.file,
            ParcelFileDescriptor.MODE_READ_ONLY
        ).use { descriptor ->
            PdfRenderer(descriptor).use { it.pageCount }
        }
        assertTrue(devicePageCount > 0)
        assertEquals(devicePageCount, artifact.pageCount)
        repository.release(artifact)
    }

    @Test
    fun androidPartFileIsRemovedWhenRendererWritesOneByteOverLimit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.noBackupFilesDir, "clinical-pdf-overflow-device")
            .apply {
                deleteRecursively()
                mkdirs()
            }
        val repository = ClinicalPdfStorageRepository(
            stagingDirectory = directory,
            renderer = ClinicalPdfSourceRenderer { _, output ->
                val chunk = ByteArray(8 * 1024)
                var remaining = ClinicalPdfStorageRepository.MAX_PDF_BYTES + 1L
                while (remaining > 0L) {
                    val count = minOf(chunk.size.toLong(), remaining).toInt()
                    output.write(chunk, 0, count)
                    remaining -= count
                }
                error("overflow must stop rendering")
            },
            pageCounter = ClinicalPdfPageCounter { error("must not count overflow") },
            writerFactory = ClinicalPdfPartWriterFactory { file ->
                val output = FileOutputStream(file)
                object : ClinicalPdfPartWriter {
                    override val stream: OutputStream = output
                    override fun flushAndSync() {
                        output.flush()
                        output.fd.sync()
                    }

                    override fun close() = output.close()
                }
            },
            safAccess = object : ClinicalPdfSafAccess {
                override fun openOutput(destination: Any, mode: String) = null
                override fun openInput(destination: Any) = null
                override fun delete(destination: Any) = Unit
            },
            ioDispatcher = Dispatchers.IO,
            clock = System::currentTimeMillis,
            idGenerator = { "overflow" },
            artifactDeleter = File::delete
        )

        assertEquals(ClinicalPdfStageResult.TooLarge, repository.stage(source(1)))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        directory.deleteRecursively()
    }

    private fun source(blocks: Int) = object : ClinicalPdfContentSource {
        override val identity = ClinicalPdfContentIdentity(
            requestSha256 = "a".repeat(64),
            generatedAt = 1_800_000_000_000L,
            zoneId = "UTC",
            schemaVersion = 7
        )

        override fun open(): ClinicalPdfContentCursor = object : ClinicalPdfContentCursor {
            private var index = 0
            override fun next(): ClinicalPdfContentBlock? {
                if (index >= blocks) return null
                val current = index++
                return ClinicalPdfContentBlock(
                    text = "Clinical PDF device row $current with bounded deterministic content",
                    role = if (current == 0) {
                        ClinicalPdfContentRole.TITLE
                    } else {
                        ClinicalPdfContentRole.ITEM
                    },
                    stableKey = "row/$current"
                )
            }

            override fun close() = Unit
        }
    }
}
