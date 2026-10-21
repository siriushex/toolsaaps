package io.aaps.copilot.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.coroutineContext

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

interface AppUpdateSource {
    suspend fun check(): AppUpdateManifest
    suspend fun download(release: AppUpdateManifest, progress: (Int) -> Unit): File
    suspend fun verify(file: File, release: AppUpdateManifest)
}

/** Isolated public transport; bounded retries apply only to the immutable APK GET. */
class AppUpdateRepository(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).build(),
    private val downloadTimeoutMillis: Long = TimeUnit.MINUTES.toMillis(20)
) : AppUpdateSource {
    private val root = File(context.cacheDir, "app-updates")
    // Reading is still subject to the idle timeout; a progressing download may exceed five minutes.
    private val downloadClient = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()

    override suspend fun check(): AppUpdateManifest = request(UPDATE_MANIFEST_URL) { response, active ->
        if (!active()) throw IOException("cancelled")
        val bytes = response.body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (output.size() <= MAX_MANIFEST_BYTES) {
                if (!active()) throw IOException("cancelled")
                val read = input.read(buffer, 0, minOf(buffer.size, MAX_MANIFEST_BYTES + 1 - output.size()))
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        try { AppUpdateManifest.parse(bytes, context.packageName) }
        catch (_: IllegalArgumentException) { throw AppUpdateException(UpdateFailure.INVALID_RELEASE) }
    }

    override suspend fun download(release: AppUpdateManifest, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        try { release.validate(context.packageName) }
        catch (_: IllegalArgumentException) { throw AppUpdateException(UpdateFailure.INVALID_RELEASE) }
        if (release.minSdk > Build.VERSION.SDK_INT) throw AppUpdateException(UpdateFailure.INCOMPATIBLE)
        updateStorage { if (!root.isDirectory && !root.mkdirs()) throw IOException("update_directory") }
        updateStorage { RandomAccessFile(File(root, "download.lock"), "rw") }.use { lockFile ->
            val lock = try { updateStorage { lockFile.channel.tryLock() } }
            catch (_: OverlappingFileLockException) { null }
            if (lock == null) throw AppUpdateException(UpdateFailure.STORAGE)
            lock.use {
                withTimeoutOrNull(downloadTimeoutMillis) { downloadLocked(release, progress) }
                    ?: throw AppUpdateException(UpdateFailure.NETWORK)
            }
        }
    }

    private suspend fun downloadLocked(release: AppUpdateManifest, progress: (Int) -> Unit): File {
        val verified = File(root, "verified")
        updateStorage { if (!verified.isDirectory && !verified.mkdirs()) throw IOException("update_directory") }
        val partial = File(root, "${release.sha256}.part")
        root.listFiles()?.filter { it != partial && it.extension == "part" }?.forEach { it.delete() }
        if (partial.length() > release.sizeBytes) updateStorage {
            if (!partial.delete()) throw IOException("update_cache")
        }
        if (root.usableSpace < release.sizeBytes - partial.length() + 16L * 1024 * 1024) {
            throw AppUpdateException(UpdateFailure.STORAGE)
        }
        try {
            val downloadContext = coroutineContext
            for (attempt in 0..2) {
                coroutineContext.ensureActive()
                if (partial.length() == release.sizeBytes) {
                    if (digestPrefix(partial) { downloadContext.ensureActive(); true }.hex() != release.sha256)
                        throw AppUpdateException(UpdateFailure.INVALID_APK)
                    break
                }
                val offset = partial.length()
                progress((offset * 100 / release.sizeBytes).toInt())
                try {
                    request(release.apkUrl, downloadClient, offset) { response, active ->
                        val start = if (response.code == 206) {
                            if (offset == 0L || response.header("Content-Range") !=
                                "bytes $offset-${release.sizeBytes - 1}/${release.sizeBytes}") {
                                throw AppUpdateException(UpdateFailure.INVALID_APK)
                            }
                            offset
                        } else 0L
                        val encoding = response.header("Content-Encoding")
                        if ((encoding != null && !encoding.equals("identity", true)) ||
                            (response.body.contentLength() >= 0 &&
                                response.body.contentLength() != release.sizeBytes - start)) {
                            throw AppUpdateException(UpdateFailure.INVALID_APK)
                        }
                        val digest = if (start > 0) digestPrefix(partial, active)
                            else MessageDigest.getInstance("SHA-256")
                        if (start == 0L) progress(0)
                        response.body.byteStream().use { input ->
                            val output = updateStorage { java.io.FileOutputStream(partial, start > 0) }
                            try {
                                try { copyVerifiedUpdate(input, output, release, active, progress, start, digest) }
                                catch (failure: AppUpdateException) {
                                    if (failure.reason == UpdateFailure.INVALID_APK && partial.length() < release.sizeBytes)
                                        throw IOException("incomplete_update")
                                    throw failure
                                }
                            } finally { updateStorage { output.close() } }
                        }
                    }
                    break
                } catch (failure: AppUpdateException) {
                    if (failure.reason != UpdateFailure.NETWORK || attempt == 2) throw failure
                    delay(500L * (attempt + 1))
                }
            }
            coroutineContext.ensureActive()
            verifyArchive(partial, release)
            coroutineContext.ensureActive()
            val target = File(verified, "${release.sha256}.apk")
            if (!partial.renameTo(target)) throw AppUpdateException(UpdateFailure.STORAGE)
            verified.listFiles()?.filter { it != target && it.extension == "apk" }?.forEach { it.delete() }
            return target
        } catch (failure: AppUpdateException) {
            if (failure.reason != UpdateFailure.NETWORK) partial.delete()
            throw failure
        }
    }

    private fun digestPrefix(file: File, active: () -> Boolean): MessageDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        updateStorage { file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (!active()) throw IOException("cancelled")
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        } }
        return digest
    }

    override suspend fun verify(file: File, release: AppUpdateManifest) = withContext(Dispatchers.IO) {
        if (file.parentFile?.canonicalFile != File(root, "verified").canonicalFile ||
            file.name != "${release.sha256}.apk" || file.length() != release.sizeBytes
        ) throw AppUpdateException(UpdateFailure.INVALID_APK)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != release.sha256) {
            throw AppUpdateException(UpdateFailure.INVALID_APK)
        }
        verifyArchive(file, release)
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(file: File, release: AppUpdateManifest) {
        val manager = context.packageManager
        val archive = manager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw AppUpdateException(UpdateFailure.INVALID_APK)
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        requireMatchingApk(release, installed.longVersionCode, archive.packageName,
            archive.longVersionCode, archive.versionName, archive.applicationInfo?.minSdkVersion ?: -1,
            installed.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty(),
            archive.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty())
    }

    private suspend fun <T> request(url: String, transport: OkHttpClient = client, offset: Long? = null,
                                   read: (Response, () -> Boolean) -> T): T {
        val finished = CompletableDeferred<Unit>()
        try { return suspendCancellableCoroutine { continuation ->
            try {
                val builder = Request.Builder().url(url)
                if (offset != null) {
                    builder.header("Accept-Encoding", "identity")
                    if (offset > 0) builder.header("Range", "bytes=$offset-")
                }
                val call = transport.newCall(builder.build())
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        try {
                            if (continuation.isActive) continuation.resumeWithException(AppUpdateException(UpdateFailure.NETWORK))
                        } finally { finished.complete(Unit) }
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use {
                                if (it.code != 200 && !(offset != null && it.code == 206))
                                    throw AppUpdateException(UpdateFailure.NETWORK)
                                read(it) { continuation.isActive }
                            }
                            if (continuation.isActive) continuation.resume(result)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(
                                e as? AppUpdateException ?: AppUpdateException(UpdateFailure.NETWORK))
                        } finally { finished.complete(Unit) }
                    }
                })
            } catch (failure: Exception) { finished.complete(Unit); throw failure }
        } } finally {
            // Keep the partial-file lock until the cancelled callback has stopped writing.
            withContext(NonCancellable) { finished.await() }
        }
    }
}

private fun MessageDigest.hex() = digest().joinToString("") { "%02x".format(it) }

private inline fun <T> updateStorage(block: () -> T): T = try { block() }
catch (_: IOException) { throw AppUpdateException(UpdateFailure.STORAGE) }
catch (_: SecurityException) { throw AppUpdateException(UpdateFailure.STORAGE) }

internal fun copyVerifiedUpdate(
    input: java.io.InputStream, output: java.io.OutputStream, release: AppUpdateManifest,
    active: () -> Boolean, progress: (Int) -> Unit, initialBytes: Long = 0,
    digest: MessageDigest = MessageDigest.getInstance("SHA-256")
) {
    val buffer = ByteArray(64 * 1024)
    var total = initialBytes
    var percent = -1
    while (true) {
        if (!active()) throw IOException("cancelled")
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > release.sizeBytes || total > MAX_APK_BYTES) throw AppUpdateException(UpdateFailure.INVALID_APK)
        updateStorage { output.write(buffer, 0, read) }
        digest.update(buffer, 0, read)
        val next = (total * 100 / release.sizeBytes).toInt()
        if (next != percent) { progress(next); percent = next }
    }
    val hash = digest.digest().joinToString("") { "%02x".format(it) }
    if (total != release.sizeBytes || hash != release.sha256) throw AppUpdateException(UpdateFailure.INVALID_APK)
}
