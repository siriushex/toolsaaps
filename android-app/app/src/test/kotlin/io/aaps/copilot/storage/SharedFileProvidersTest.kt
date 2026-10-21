package io.aaps.copilot.storage

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import androidx.core.content.FileProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 35], application = Application::class)
class SharedFileProvidersTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val files = linkedMapOf(
        "clinical-pdf" to "clinical-pdf-share/report.pdf",
        "meal-photo" to "meal-images/meal.jpg",
        "app-update" to "app-updates/verified/update.apk"
    )

    @Before fun clearPathStrategies() {
        val cache = ReflectionHelpers.getStaticField<MutableMap<String, Any>>(FileProvider::class.java, "sCache")
        files.keys.forEach { cache.remove("${app.packageName}.$it") }
    }

    @Test fun providersHaveDistinctAndroidComponentIdentities() {
        val components = files.keys.map { suffix ->
            val info = requireNotNull(app.packageManager.resolveContentProvider(
                "${app.packageName}.$suffix", PackageManager.GET_META_DATA))
            assertFalse(info.exported)
            assertTrue(info.grantUriPermissions)
            ComponentName(info.packageName, info.name)
        }
        assertEquals("Android indexes local providers by component, not just authority",
            files.size, components.toSet().size)
    }

    @Test fun resolverReadsExactBytesFromEveryDeclaredProvider() {
        files.forEach { (suffix, path) ->
            val bytes = "synthetic-$suffix-content".toByteArray()
            val file = File(app.cacheDir, path).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.$suffix", file)
            app.contentResolver.openInputStream(uri).use { input ->
                assertNotNull("Provider must serve $suffix", input)
                assertArrayEquals(bytes, input!!.readBytes())
            }
        }
    }

    @Test fun platformRegistersAllAuthoritiesWithoutReusingAnotherProvidersHolder() {
        val thread = RuntimeEnvironment.getActivityThread()
        val install = thread.javaClass.declaredMethods.single {
            it.name == "installProvider" && it.parameterCount == 6
        }.apply { isAccessible = true }
        val infos = files.keys.map { suffix ->
            requireNotNull(app.packageManager.resolveContentProvider(
                "${app.packageName}.$suffix", PackageManager.GET_META_DATA))
        }
        fun registeredAuthority(info: ProviderInfo): String {
            val holder = install.invoke(thread, app, null, info, false, true, true)
            return ReflectionHelpers.getField<ProviderInfo>(holder, "info").authority
        }
        // Negative control reproduces the old manifest using Android's real registration code.
        val oldAuthorities = infos.map { info ->
            registeredAuthority(ProviderInfo(info).apply { name = FileProvider::class.java.name })
        }
        assertEquals(1, oldAuthorities.toSet().size)
        infos.forEach { assertEquals(it.authority, registeredAuthority(it)) }
    }

    @Test fun providersCannotShareEachOthersFilesOrUnverifiedDownloads() {
        files.forEach { (suffix, _) ->
            val authority = "${app.packageName}.$suffix"
            val denied = files.filterKeys { it != suffix }.values +
                listOf("app-updates/unverified.part", "private-data.json")
            denied.forEach { path ->
                assertThrows(IllegalArgumentException::class.java) {
                    FileProvider.getUriForFile(app, authority, File(app.cacheDir, path))
                }
            }
        }
    }
}
