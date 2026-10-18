package io.aaps.copilot.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal const val UPDATE_TEST_PACKAGE = "io.aaps.predictivecopilot"
internal fun sampleRelease(bytes: ByteArray = "sample apk".toByteArray()) = AppUpdateManifest(
    1, UPDATE_TEST_PACKAGE, 3, "0.1.2", 31, bytes.size.toLong(), sha256(bytes),
    "$UPDATE_ORIGIN/app-updates/${sha256(bytes)}.apk", "Update notes")

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateTest {
    @Test fun validManifestRoundTrips() {
        val release = sampleRelease()
        assertEquals(release, AppUpdateManifest.parse(Json.encodeToString(release).toByteArray(), UPDATE_TEST_PACKAGE))
    }

    @Test fun hostileAndMalformedManifestFieldsAreRejected() {
        val good = sampleRelease()
        listOf(good.copy(schema = 2), good.copy(packageName = "other.app"), good.copy(versionCode = 0),
            good.copy(versionCode = 2_100_000_001L), good.copy(versionName = "\n"), good.copy(minSdk = 0),
            good.copy(sizeBytes = 0), good.copy(sizeBytes = MAX_APK_BYTES + 1), good.copy(sha256 = "x".repeat(64)),
            good.copy(apkUrl = good.apkUrl.replace("https:", "http:")),
            good.copy(apkUrl = "https://other.example.test/${good.sha256}.apk"),
            good.copy(apkUrl = good.apkUrl + "?key=sample"), good.copy(apkUrl = good.apkUrl + "#fragment"),
            good.copy(apkUrl = "$UPDATE_ORIGIN/app-updates/../private.apk"),
            good.copy(notes = "a".repeat(2001)), good.copy(notes = "bad\u0000text")
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid.validate(UPDATE_TEST_PACKAGE) } }
    }

    @Test fun oversizedUnknownFieldsAndInvalidUtf8AreRejected() {
        assertThrows(Exception::class.java) { AppUpdateManifest.parse(ByteArray(MAX_MANIFEST_BYTES + 1), UPDATE_TEST_PACKAGE) }
        assertThrows(Exception::class.java) { AppUpdateManifest.parse(byteArrayOf(0xc3.toByte(), 0x28), UPDATE_TEST_PACKAGE) }
        val json = Json.encodeToString(sampleRelease()).dropLast(1) + ",\"extra\":true}"
        assertThrows(Exception::class.java) { AppUpdateManifest.parse(json.toByteArray(), UPDATE_TEST_PACKAGE) }
    }

    @Test fun streamCopiesOnlyTheExactAdvertisedArtifact() {
        val bytes = ByteArray(150_000) { (it % 127).toByte() }
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Int>()
        copyVerifiedUpdate(ByteArrayInputStream(bytes), output, sampleRelease(bytes), { true }, progress::add)
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(100, progress.last())
        assertEquals(progress.distinct().sorted(), progress)
    }

    @Test fun truncatedOverlongCorruptOrCancelledStreamsCannotSucceed() {
        val release = sampleRelease()
        listOf("sample ap", "sample apk extra", "wrong data").forEach { text ->
            assertThrows(AppUpdateException::class.java) {
                copyVerifiedUpdate(text.byteInputStream(), ByteArrayOutputStream(), release, { true }, {})
            }
        }
        assertThrows(java.io.IOException::class.java) {
            copyVerifiedUpdate("sample apk".byteInputStream(), ByteArrayOutputStream(), release, { false }, {})
        }
    }

    @Test fun failedDiskWriteIsStorageFailureNotNetworkFailure() {
        val output = object : OutputStream() {
            override fun write(value: Int) { throw IOException("disk write failed") }
        }
        val failure = assertThrows(AppUpdateException::class.java) {
            copyVerifiedUpdate("sample apk".byteInputStream(), output, sampleRelease(), { true }, {})
        }
        assertEquals(UpdateFailure.STORAGE, failure.reason)
    }

    @Test fun deniedDiskWriteIsStorageFailure() {
        val output = object : OutputStream() {
            override fun write(value: Int) { throw SecurityException("write denied") }
        }
        val failure = assertThrows(AppUpdateException::class.java) {
            copyVerifiedUpdate("sample apk".byteInputStream(), output, sampleRelease(), { true }, {})
        }
        assertEquals(UpdateFailure.STORAGE, failure.reason)
    }

    @Test fun failedNetworkReadIsNotReclassifiedAsStorageFailure() {
        val input = object : java.io.InputStream() {
            override fun read(): Int = throw IOException("network read failed")
        }
        val failure = assertThrows(IOException::class.java) {
            copyVerifiedUpdate(input, ByteArrayOutputStream(), sampleRelease(), { true }, {})
        }
        assertEquals(IOException::class.java, failure.javaClass)
    }

    @Test fun signaturePackageVersionAndSdkMustAllMatch() {
        val release = sampleRelease()
        fun verify(pkg: String = release.packageName, version: Long = release.versionCode,
            name: String? = release.versionName, min: Int = release.minSdk, current: Long = 2,
            installed: Set<String> = setOf("trusted"), archive: Set<String> = setOf("trusted")) =
            requireMatchingApk(release, current, pkg, version, name, min, installed, archive)
        verify()
        listOf<() -> Unit>({ verify(pkg = "other") }, { verify(version = 4) }, { verify(name = null) },
            { verify(min = 32) }, { verify(current = 3) }, { verify(installed = emptySet()) },
            { verify(archive = setOf("untrusted")) }, { verify(archive = setOf("trusted", "extra")) }
        ).forEach { assertThrows(AppUpdateException::class.java, it) }
    }

    @Test fun checkingNeverDownloadsAndEqualOrOlderVersionsAreNotOffered() = runTest {
        for (current in listOf(3L, 4L)) {
            val source = FakeUpdates()
            val controller = AppUpdateController(source, current, 35, backgroundScope)
            assertEquals(0, source.checks)
            controller.check(); runCurrent()
            assertEquals(UpdatePhase.CURRENT, controller.state.value.phase)
            controller.download(); runCurrent()
            assertEquals(0, source.downloads)
        }
    }

    @Test fun availableDownloadAndInstallAreSeparateExplicitSteps() = runTest {
        val source = FakeUpdates()
        val controller = AppUpdateController(source, 2, 35, backgroundScope)
        controller.check(); runCurrent()
        assertEquals(UpdatePhase.AVAILABLE, controller.state.value.phase)
        assertEquals(0, source.downloads)
        controller.download(); controller.download(); runCurrent()
        assertEquals(1, source.downloads)
        assertEquals(UpdatePhase.READY, controller.state.value.phase)
        assertEquals(0, source.verifications)
        assertEquals(source.file, controller.verifiedFile())
        assertEquals(1, source.verifications)
    }

    @Test fun incompatibleAndroidNeverOffersDownload() = runTest {
        val source = FakeUpdates().apply { release = sampleRelease().copy(minSdk = 36) }
        val controller = AppUpdateController(source, 2, 35, backgroundScope)
        controller.check(); runCurrent()
        assertEquals(UpdateFailure.INCOMPATIBLE, controller.state.value.failure)
        controller.download(); runCurrent()
        assertEquals(0, source.downloads)
    }

    @Test fun failureDoesNotRetryOrRetainAnInstallableFile() = runTest {
        val source = FakeUpdates().apply { failure = UpdateFailure.NETWORK }
        val controller = AppUpdateController(source, 2, 35, backgroundScope)
        controller.check(); runCurrent(); runCurrent()
        assertEquals(1, source.checks)
        assertEquals(UpdatePhase.ERROR, controller.state.value.phase)
        assertNull(controller.verifiedFile())
        source.failure = null
        controller.check(); runCurrent()
        controller.download(); runCurrent()
        source.failure = UpdateFailure.INVALID_APK
        assertNull(controller.verifiedFile())
        assertNull(controller.state.value.file)
    }

    @Test fun cancellationRejectsLateProgressAndAllowsFreshCheck() = runTest {
        val source = FakeUpdates().apply { waitForDownload = CompletableDeferred() }
        val controller = AppUpdateController(source, 2, 35, backgroundScope)
        controller.check(); runCurrent()
        controller.download(); runCurrent()
        controller.cancel()
        source.progress?.invoke(99)
        source.waitForDownload?.complete(Unit); runCurrent()
        assertEquals(UpdatePhase.IDLE, controller.state.value.phase)
        assertEquals(0, controller.state.value.percent)
        controller.check(); runCurrent()
        assertEquals(UpdatePhase.AVAILABLE, controller.state.value.phase)
    }

    @Test fun repeatedCheckWhileBusyIsSingleFlight() = runTest {
        val source = FakeUpdates().apply { waitForCheck = CompletableDeferred() }
        val controller = AppUpdateController(source, 2, 35, backgroundScope)
        controller.check(); controller.check(); runCurrent()
        assertEquals(1, source.checks)
        controller.cancel(); source.waitForCheck?.complete(Unit); runCurrent()
        assertEquals(UpdatePhase.IDLE, controller.state.value.phase)
    }

    @Test fun cancelledCheckCannotReplaceNewerCheckWithLateSuccess() = runTest {
        val oldCheck = CompletableDeferred<Unit>()
        val source = FakeUpdates().apply { waitForCheck = oldCheck; ignoreCancellation = true }
        val controller = AppUpdateController(source, 2, 35, backgroundScope)
        controller.check(); runCurrent(); controller.cancel()
        source.waitForCheck = null
        controller.check(); runCurrent()
        val current = controller.state.value
        source.release = sampleRelease().copy(versionCode = 99)
        oldCheck.complete(Unit); runCurrent()
        assertEquals(current, controller.state.value)
    }

    @Test fun cancelledDownloadCannotPublishLateFileOrFailure() = runTest {
        for (failure in listOf(null, UpdateFailure.NETWORK)) {
            val oldDownload = CompletableDeferred<Unit>()
            val source = FakeUpdates().apply { waitForDownload = oldDownload; ignoreCancellation = true }
            val controller = AppUpdateController(source, 2, 35, backgroundScope)
            controller.check(); runCurrent(); controller.download(); runCurrent(); controller.cancel()
            controller.check(); runCurrent()
            val current = controller.state.value
            source.failure = failure
            oldDownload.complete(Unit); runCurrent()
            assertEquals(current, controller.state.value)
            assertNull(controller.state.value.file)
        }
    }
}

private class FakeUpdates : AppUpdateSource {
    var release = sampleRelease()
    var checks = 0
    var downloads = 0
    var verifications = 0
    var failure: UpdateFailure? = null
    var waitForCheck: CompletableDeferred<Unit>? = null
    var waitForDownload: CompletableDeferred<Unit>? = null
    var ignoreCancellation = false
    var progress: ((Int) -> Unit)? = null
    val file = File("sample.apk")
    private fun fail() { failure?.let { throw AppUpdateException(it) } }
    private suspend fun await(wait: CompletableDeferred<Unit>?) {
        if (ignoreCancellation) withContext(NonCancellable) { wait?.await() } else wait?.await()
    }
    override suspend fun check(): AppUpdateManifest { checks++; await(waitForCheck); fail(); return release }
    override suspend fun download(release: AppUpdateManifest, progress: (Int) -> Unit): File {
        downloads++; this.progress = progress; await(waitForDownload); fail(); progress(100); return file
    }
    override suspend fun verify(file: File, release: AppUpdateManifest) { verifications++; fail() }
}
