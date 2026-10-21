package io.aaps.copilot.historyexport

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import io.aaps.copilot.BuildConfig
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.CopilotDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.time.ZoneId

internal interface ClinicalJsonExporter {
    suspend fun export(uri: Uri, period: ClinicalJsonPeriod, progress: (Long) -> Unit): Long
    suspend fun removeIncomplete(uri: Uri): Boolean
}

internal class ClinicalJsonExportStore(private val context: Context, private val db: CopilotDatabase,
    private val settings: AppSettingsStore) : ClinicalJsonExporter {
    override suspend fun export(uri: Uri, period: ClinicalJsonPeriod, progress: (Long) -> Unit): Long = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "A document URI is required" }
        val coroutine = currentCoroutineContext()
        val profile = ClinicalJsonSchema.currentProfile(settings.settings.first())
        val cutoff = System.currentTimeMillis()
        val path = requireNotNull(db.openHelper.readableDatabase.path)
        val directory = File(context.cacheDir, "clinical-json").apply { check(isDirectory || mkdirs()) }
        val temporary = File.createTempFile("report-", ".partial", directory)
        try {
            withClinicalReadSnapshot(path) { snapshot ->
                FileOutputStream(temporary).use { stream ->
                    val writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8))
                    ClinicalJsonReport().write(snapshot, writer, period, cutoff, ZoneId.systemDefault().id,
                        BuildConfig.VERSION_NAME, profile, { coroutine.ensureActive() }, progress)
                    writer.flush()
                    stream.fd.sync()
                }
            }
            coroutine.ensureActive()
            copyPreparedClinicalJson(temporary, {
                context.contentResolver.openOutputStream(uri, "wt") ?: error("No document output")
            }, {
                context.contentResolver.openInputStream(uri) ?: error("Cannot verify document")
            }, { coroutine.ensureActive() })
        } finally { temporary.delete() }
    }

    override suspend fun removeIncomplete(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") false else
            try { DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (_: Exception) { false }
    }
}

internal fun <T> withClinicalReadSnapshot(path: String, read: (SQLiteDatabase) -> T): T =
    SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { snapshot ->
        // Older Android rewrites BEGIN into a writer transaction. SAVEPOINT preserves a
        // deferred read snapshot on this separate single-connection, read-only database.
        snapshot.execSQL("SAVEPOINT report_snapshot")
        try { read(snapshot) } finally { snapshot.execSQL("RELEASE report_snapshot") }
    }

internal fun copyPreparedClinicalJson(file: File, openOutput: () -> OutputStream,
    openSaved: () -> InputStream, checkActive: () -> Unit): Long {
    var count = 0L
    val expected = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        openOutput().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkActive()
                val size = input.read(buffer)
                if (size < 0) break
                output.write(buffer, 0, size)
                expected.update(buffer, 0, size)
                count += size
            }
            output.flush()
        }
    }
    checkActive()
    check(count == file.length())
    val actual = MessageDigest.getInstance("SHA-256")
    var savedCount = 0L
    openSaved().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkActive()
            val size = input.read(buffer)
            if (size < 0) break
            actual.update(buffer, 0, size)
            savedCount += size
        }
    }
    checkActive()
    check(savedCount == count && MessageDigest.isEqual(expected.digest(), actual.digest())) { "Incomplete document" }
    return count
}
