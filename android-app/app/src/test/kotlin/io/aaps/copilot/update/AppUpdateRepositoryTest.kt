package io.aaps.copilot.update

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppUpdateRepositoryTest {
    private val app get() = RuntimeEnvironment.getApplication()

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
}
