package io.aaps.copilot.update

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo
import org.robolectric.util.ReflectionHelpers
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppUpdateRepositoryTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun clearUpdateCache() { File(app.cacheDir, "app-updates").deleteRecursively() }

    @Test fun interruptedAtSeventyFivePercentResumesTheSameArtifact() = runBlocking {
        val bytes = ByteArray(120_000) { (it % 127).toByte() }
        val release = sampleRelease(bytes).copy(packageName = app.packageName)
        val ranges = mutableListOf<String?>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertNull(chain.request().header("Authorization"))
            ranges += chain.request().header("Range")
            if (ranges.size == 1) response(chain.request(), bytes).newBuilder()
                .body(interruptedBody(bytes, 90_000)).build()
            else response(chain.request(), bytes.copyOfRange(90_000, bytes.size), 206).newBuilder()
                .header("Content-Range", "bytes 90000-119999/120000").build()
        }.build()
        val progress = mutableListOf<Int>()
        try { AppUpdateRepository(app, client).download(release, progress::add); fail("Synthetic bytes are not an APK") }
        catch (failure: AppUpdateException) {
            // INVALID_APK here means the complete hash-checked transfer reached archive verification.
            assertEquals(UpdateFailure.INVALID_APK, failure.reason)
        }
        assertEquals(listOf(null, "bytes=90000-"), ranges)
        assertTrue(progress.contains(75))
        assertEquals(100, progress.last())
        assertTrue(File(app.cacheDir, "app-updates/verified").listFiles().orEmpty().isEmpty())
    }

    @Test fun exhaustedRetriesRetainBytesForAnExplicitRetryWithANewRepository() = runBlocking {
        val bytes = ByteArray(120_000) { (it % 127).toByte() }
        val release = sampleRelease(bytes).copy(packageName = app.packageName)
        var online = false
        val ranges = mutableListOf<String?>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            ranges += chain.request().header("Range")
            if (ranges.size == 1) response(chain.request(), bytes).newBuilder()
                .body(interruptedBody(bytes, 90_000)).build()
            else if (!online) throw IOException("synthetic offline")
            else response(chain.request(), bytes.copyOfRange(90_000, bytes.size), 206).newBuilder()
                .header("Content-Range", "bytes 90000-119999/120000").build()
        }.build()
        try { AppUpdateRepository(app, client).download(release) {}; fail("Expected offline failure") }
        catch (failure: AppUpdateException) { assertEquals(UpdateFailure.NETWORK, failure.reason) }
        assertEquals(3, ranges.size)
        assertEquals(90_000L, File(app.cacheDir, "app-updates/${release.sha256}.part").length())
        online = true
        try { AppUpdateRepository(app, client).download(release) {}; fail("Synthetic bytes are not an APK") }
        catch (failure: AppUpdateException) { assertEquals(UpdateFailure.INVALID_APK, failure.reason) }
        assertEquals("bytes=90000-", ranges.last())
        assertTrue(File(app.cacheDir, "app-updates").listFiles().orEmpty().none { it.extension == "part" })
    }

    @Test fun rangeIgnoredByServerRestartsCleanlyInsteadOfAppendingASecondApk() = runBlocking {
        val bytes = ByteArray(120_000) { (it % 127).toByte() }
        val release = sampleRelease(bytes).copy(packageName = app.packageName)
        val partial = File(app.cacheDir, "app-updates/${release.sha256}.part")
        partial.parentFile!!.mkdirs()
        partial.writeBytes(bytes.copyOf(90_000))
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("bytes=90000-", chain.request().header("Range"))
            assertEquals("identity", chain.request().header("Accept-Encoding"))
            response(chain.request(), bytes)
        }.build()
        val progress = mutableListOf<Int>()
        try { AppUpdateRepository(app, client).download(release, progress::add); fail("Synthetic bytes are not an APK") }
        catch (failure: AppUpdateException) { assertEquals(UpdateFailure.INVALID_APK, failure.reason) }
        assertEquals(1, calls)
        assertTrue(progress.contains(0))
        assertEquals(100, progress.last())
    }

    @Test fun wrongResumeRangeOrCorruptPrefixCannotPassIntegrityVerification() = runBlocking {
        val bytes = ByteArray(120_000) { (it % 127).toByte() }
        val release = sampleRelease(bytes).copy(packageName = app.packageName)
        for (wrongRange in listOf(true, false)) {
            val partial = File(app.cacheDir, "app-updates/${release.sha256}.part")
            partial.parentFile!!.mkdirs()
            partial.writeBytes(if (wrongRange) bytes.copyOf(90_000) else ByteArray(90_000))
            val progress = mutableListOf<Int>()
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                response(chain.request(), bytes.copyOfRange(90_000, bytes.size), 206).newBuilder()
                    .header("Content-Range", if (wrongRange) "bytes 0-29999/120000" else "bytes 90000-119999/120000").build()
            }.build()
            try { AppUpdateRepository(app, client).download(release, progress::add); fail("Expected refusal") }
            catch (failure: AppUpdateException) { assertEquals(UpdateFailure.INVALID_APK, failure.reason) }
            assertEquals(1, calls)
            assertFalse(partial.exists())
            assertTrue(File(app.cacheDir, "app-updates/verified").listFiles().orEmpty().isEmpty())
            if (wrongRange) assertFalse(progress.contains(100))
        }
    }

    @Test fun existingCompletedTransferIsCheckedLocallyWithoutAnotherGet() = runBlocking {
        val bytes = "synthetic completed transfer".toByteArray()
        val release = sampleRelease(bytes).copy(packageName = app.packageName)
        val partial = File(app.cacheDir, "app-updates/${release.sha256}.part")
        partial.parentFile!!.mkdirs()
        partial.writeBytes(bytes)
        val client = OkHttpClient.Builder().addInterceptor { fail("Unexpected request"); throw IOException() }.build()
        try { AppUpdateRepository(app, client).download(release) {}; fail("Synthetic bytes are not an APK") }
        catch (failure: AppUpdateException) { assertEquals(UpdateFailure.INVALID_APK, failure.reason) }
        assertFalse(partial.exists())
    }

    @Test fun onlyACompleteMatchingArchiveCanEnterTheVerifiedDirectory() = runBlocking {
        val bytes = "synthetic archive with platform-verified metadata".toByteArray()
        val release = sampleRelease(bytes).copy(packageName = app.packageName)
        val signing = ReflectionHelpers.callConstructor(SigningInfo::class.java)
        Shadow.extract<ShadowSigningInfo>(signing).setSignatures(arrayOf(Signature(byteArrayOf(1, 2, 3))))
        val manager = app.packageManager
        val platform = shadowOf(manager)
        val installed = manager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        installed.setLongVersionCode(1)
        installed.signingInfo = signing
        platform.installPackage(installed)
        val archive = PackageInfo().apply {
            packageName = release.packageName
            setLongVersionCode(release.versionCode)
            versionName = release.versionName
            applicationInfo = ApplicationInfo().apply { minSdkVersion = release.minSdk }
            signingInfo = signing
        }
        val root = File(app.cacheDir, "app-updates")
        platform.setPackageArchiveInfo(File(root, "${release.sha256}.part").path, archive)
        platform.setPackageArchiveInfo(File(root, "verified/${release.sha256}.apk").path, archive)
        val client = OkHttpClient.Builder().addInterceptor { chain -> response(chain.request(), bytes) }.build()
        val repository = AppUpdateRepository(app, client)
        val file = repository.download(release) {}
        assertEquals(File(root, "verified"), file.parentFile)
        assertArrayEquals(bytes, file.readBytes())
        repository.verify(file, release)
        assertTrue(root.listFiles().orEmpty().none { it.extension == "part" })
    }

    @Test fun progressingDownloadCanOutliveTheManifestCallDeadlineOverRealHttp() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("synthetic transfer").setBodyDelay(200, TimeUnit.MILLISECONDS))
            server.start()
            val bytes = "synthetic transfer".toByteArray()
            val release = sampleRelease(bytes).copy(packageName = app.packageName)
            val client = OkHttpClient.Builder().callTimeout(50, TimeUnit.MILLISECONDS)
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/artifact")).build()) }
                .build()
            try { AppUpdateRepository(app, client).download(release) {}; fail("Synthetic bytes are not an APK") }
            catch (failure: AppUpdateException) { assertEquals(UpdateFailure.INVALID_APK, failure.reason) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun overallDeadlineCancelsTheHttpReadAndReleasesThePartialFileLock() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("synthetic transfer").setBodyDelay(2, TimeUnit.SECONDS))
            server.start()
            val release = sampleRelease("synthetic transfer".toByteArray()).copy(packageName = app.packageName)
            val client = OkHttpClient.Builder()
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/artifact")).build()) }
                .build()
            try { AppUpdateRepository(app, client, downloadTimeoutMillis = 200).download(release) {}; fail("Expected deadline") }
            catch (failure: AppUpdateException) { assertEquals(UpdateFailure.NETWORK, failure.reason) }
            RandomAccessFile(File(app.cacheDir, "app-updates/download.lock"), "rw").use { file ->
                file.channel.tryLock().use { assertNotNull(it) }
            }
            assertTrue(File(app.cacheDir, "app-updates/verified").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun anotherDownloaderCannotRemoveOrWriteTheOwnedPartial() = runBlocking {
        val release = sampleRelease().copy(packageName = app.packageName)
        val partial = File(app.cacheDir, "app-updates/${release.sha256}.part")
        partial.parentFile!!.mkdirs()
        partial.writeText("sample")
        RandomAccessFile(File(partial.parentFile, "download.lock"), "rw").use { file ->
            file.channel.lock().use {
                try { AppUpdateRepository(app).download(release) {}; fail("Expected lock refusal") }
                catch (failure: AppUpdateException) { assertEquals(UpdateFailure.STORAGE, failure.reason) }
                assertEquals("sample", partial.readText())
            }
        }
    }

    @Test fun manifestRequestHasNoAuthorizationAndOnlyUsesFixedEndpoint() = runBlocking {
        val release = sampleRelease().copy(packageName = app.packageName)
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals(UPDATE_MANIFEST_URL, chain.request().url.toString())
            assertNull(chain.request().header("Authorization"))
            assertNull(chain.request().body)
            response(chain.request(), Json.encodeToString(release).toByteArray())
        }.build()
        assertEquals(release, AppUpdateRepository(app, client).check())
        assertEquals(1, calls)
    }

    @Test fun malformedReleaseAndRedirectFailClosed() = runBlocking {
        for (code in listOf(200, 302, 500)) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                response(chain.request(), "invalid".toByteArray(), code)
            }.build()
            try { AppUpdateRepository(app, client).check(); fail("Expected failure") }
            catch (failure: AppUpdateException) {
                assertEquals(if (code == 200) UpdateFailure.INVALID_RELEASE else UpdateFailure.NETWORK, failure.reason)
            }
        }
    }

    @Test fun hashValidButNotAnApkCannotReachTheProvider() = runBlocking {
        val bytes = "not an Android package".toByteArray()
        val release = sampleRelease(bytes).copy(packageName = app.packageName)
        val client = OkHttpClient.Builder().addInterceptor { chain -> response(chain.request(), bytes) }.build()
        try { AppUpdateRepository(app, client).download(release) {}; fail("Expected failure") }
        catch (failure: AppUpdateException) { assertEquals(UpdateFailure.INVALID_APK, failure.reason) }
        assertTrue(File(app.cacheDir, "app-updates/verified").listFiles().orEmpty().isEmpty())
        assertTrue(File(app.cacheDir, "app-updates").listFiles().orEmpty().none { it.extension == "part" })
    }

    @Test fun reinstallVerificationRejectsFilesOutsideTheNarrowCache() = runBlocking {
        val file = File(app.cacheDir, "sample.apk").apply { writeText("sample apk") }
        try { AppUpdateRepository(app).verify(file, sampleRelease()); fail("Expected failure") }
        catch (failure: AppUpdateException) { assertEquals(UpdateFailure.INVALID_APK, failure.reason) }
    }

    private fun response(request: okhttp3.Request, bytes: ByteArray, code: Int = 200) = Response.Builder()
        .request(request).code(code).message("sample").protocol(Protocol.HTTP_1_1)
        .body(bytes.toResponseBody()).build()

    private fun interruptedBody(bytes: ByteArray, after: Int): ResponseBody = object : ResponseBody() {
        private var copied = 0
        private val data = object : ForwardingSource(Buffer().write(bytes)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (copied == after) throw IOException("synthetic connection interruption")
                return super.read(sink, minOf(byteCount, (after - copied).toLong())).also {
                    if (it > 0) copied += it.toInt()
                }
            }
        }.buffer()
        override fun contentType() = null
        override fun contentLength() = bytes.size.toLong()
        override fun source() = data
    }
}
