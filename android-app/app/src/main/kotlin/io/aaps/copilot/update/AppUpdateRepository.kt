package io.aaps.copilot.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
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

/** Isolated transport: no account credentials, medical context, redirects or automatic retries. */
class AppUpdateRepository(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).build()
) : AppUpdateSource {
    private val root = File(context.cacheDir, "app-updates")

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

    override suspend fun download(release: AppUpdateManifest, progress: (Int) -> Unit): File {
        try { release.validate(context.packageName) }
        catch (_: IllegalArgumentException) { throw AppUpdateException(UpdateFailure.INVALID_RELEASE) }
        if (release.minSdk > Build.VERSION.SDK_INT) throw AppUpdateException(UpdateFailure.INCOMPATIBLE)
        return request(release.apkUrl) { response, active ->
            val verified = File(root, "verified")
            if (!verified.isDirectory && !verified.mkdirs()) throw AppUpdateException(UpdateFailure.STORAGE)
            root.listFiles()?.filter { it.name.startsWith("download-") && it.extension == "part" }
                ?.forEach { it.delete() }
            if (root.usableSpace < release.sizeBytes + 16L * 1024 * 1024) {
                throw AppUpdateException(UpdateFailure.STORAGE)
            }
            if (response.body.contentLength() >= 0 && response.body.contentLength() != release.sizeBytes) {
                throw AppUpdateException(UpdateFailure.INVALID_APK)
            }
            // Unverified bytes never enter the FileProvider-visible directory.
            val partial = File.createTempFile("download-", ".part", root)
            try {
                response.body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        copyVerifiedUpdate(input, output, release, active, progress)
                    }
                }
                verifyArchive(partial, release)
                if (!active()) throw IOException("cancelled")
                val target = File(verified, "${release.sha256}.apk")
                if (!partial.renameTo(target)) throw AppUpdateException(UpdateFailure.STORAGE)
                verified.listFiles()?.filter { it != target && it.extension == "apk" }?.forEach { it.delete() }
                target
            } finally { partial.delete() }
        }
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

    private suspend fun <T> request(url: String, read: (Response, () -> Boolean) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(AppUpdateException(UpdateFailure.NETWORK))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (it.code != 200) throw AppUpdateException(UpdateFailure.NETWORK)
                            read(it) { continuation.isActive }
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            e as? AppUpdateException ?: AppUpdateException(UpdateFailure.NETWORK))
                    }
                }
            })
        }
}

internal fun copyVerifiedUpdate(
    input: java.io.InputStream, output: java.io.OutputStream, release: AppUpdateManifest,
    active: () -> Boolean, progress: (Int) -> Unit
) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    var percent = -1
    while (true) {
        if (!active()) throw IOException("cancelled")
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > release.sizeBytes || total > MAX_APK_BYTES) throw AppUpdateException(UpdateFailure.INVALID_APK)
        output.write(buffer, 0, read)
        digest.update(buffer, 0, read)
        val next = (total * 100 / release.sizeBytes).toInt()
        if (next != percent) { progress(next); percent = next }
    }
    val hash = digest.digest().joinToString("") { "%02x".format(it) }
    if (total != release.sizeBytes || hash != release.sha256) throw AppUpdateException(UpdateFailure.INVALID_APK)
}
