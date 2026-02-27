package io.aaps.copilot.data.repository

import android.content.Context
import android.os.Build
import android.os.Environment
import io.aaps.copilot.config.AppSettingsStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class AapsAutoConnectRepository(
    private val context: Context,
    private val settingsStore: AppSettingsStore,
    private val auditLogger: AuditLogger
) {

    suspend fun bootstrap(): BootstrapResult = withContext(Dispatchers.IO) {
        val settings = settingsStore.settings.first()
        var exportConnected = !settings.exportFolderUri.isNullOrBlank()
        var exportPath = settings.exportFolderUri
        var rootEnabled = settings.rootExperimentalEnabled
        var updatedUrl = settings.nightscoutUrl
        var updatedSecret = settings.apiSecret
        var nightscoutSource = if (settings.nightscoutUrl.isNotBlank() && settings.apiSecret.isNotBlank()) {
            "settings"
        } else {
            null
        }
        var rootDbPath: String? = null

        if (!exportConnected) {
            val discoveredExportPath = discoverExportPath()
            if (!discoveredExportPath.isNullOrBlank()) {
                settingsStore.update { it.copy(exportFolderUri = discoveredExportPath) }
                exportConnected = true
                exportPath = discoveredExportPath
                auditLogger.info("aaps_auto_connect_export_path", mapOf("path" to discoveredExportPath))
            }
        }

        val rootAvailable = isRootAvailable()
        if (rootAvailable) {
            rootDbPath = discoverRootDbPath()
            rootDbPath?.let { path ->
                auditLogger.info("aaps_auto_connect_root_db_detected", mapOf("path" to path))
            }
            if (!settings.rootExperimentalEnabled) {
                settingsStore.update { it.copy(rootExperimentalEnabled = true) }
                rootEnabled = true
                auditLogger.info("aaps_auto_connect_root_enabled")
            }
            if (settings.nightscoutUrl.isBlank() || settings.apiSecret.isBlank()) {
                val discovered = discoverNightscoutFromRootPrefs()
                if (discovered != null) {
                    updatedUrl = settings.nightscoutUrl.ifBlank { discovered.url }
                    updatedSecret = settings.apiSecret.ifBlank { discovered.secret }
                    settingsStore.update {
                        it.copy(
                            nightscoutUrl = updatedUrl,
                            apiSecret = updatedSecret
                        )
                    }
                    auditLogger.info("aaps_auto_connect_nightscout_from_root", mapOf("url" to updatedUrl.take(80)))
                    nightscoutSource = "root_prefs:${File(discovered.sourceFile).name}"
                }
            }
        }

        val aapsPackage = detectInstalledPackage(AAPS_PACKAGES)
        val xdripPackage = detectInstalledPackage(XDRIP_PACKAGES)

        if (!exportConnected) {
            auditLogger.warn(
                "aaps_auto_connect_export_pending",
                mapOf(
                    "reason" to "no_export_path_found",
                    "allFilesAccess" to hasAllFilesAccess()
                )
            )
        }

        BootstrapResult(
            exportConnected = exportConnected,
            exportPath = exportPath,
            rootEnabled = rootEnabled,
            rootAvailable = rootAvailable,
            rootDbPath = rootDbPath,
            nightscoutConfigured = updatedUrl.isNotBlank() && updatedSecret.isNotBlank(),
            nightscoutSource = nightscoutSource,
            aapsPackage = aapsPackage,
            xdripPackage = xdripPackage,
            hasAllFilesAccess = hasAllFilesAccess()
        )
    }

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun discoverExportPath(): String? {
        if (!hasAllFilesAccess()) return null

        val candidates = buildList {
            add(File("/storage/emulated/0/AndroidAPS"))
            add(File("/storage/emulated/0/AAPS"))
            add(File("/sdcard/AndroidAPS"))
            add(File("/sdcard/AAPS"))
            add(File("/storage/emulated/0/Documents/AndroidAPS"))
            add(File("/storage/emulated/0/Documents/AAPS"))
            add(File("/storage/emulated/0/Download/AndroidAPS"))
            add(File("/storage/emulated/0/Download/AAPS"))
            val external = context.getExternalFilesDir(null)
            if (external != null) add(external)
        }

        val dirsToCheck = mutableListOf<File>()
        candidates.filter { it.exists() && it.isDirectory }.forEach { root ->
            dirsToCheck += root
            root.listFiles()?.filter { it.isDirectory }?.let { dirsToCheck += it }
        }

        return dirsToCheck.distinctBy { it.absolutePath }
            .firstOrNull { hasAapsJson(it) }
            ?.absolutePath
    }

    private fun hasAapsJson(dir: File): Boolean {
        val files = dir.listFiles() ?: return false
        if (files.none { it.isFile && it.name.endsWith(".json", ignoreCase = true) }) return false
        val interesting = files.count {
            it.isFile &&
                it.name.endsWith(".json", ignoreCase = true) &&
                (
                    it.name.contains("result", true) ||
                        it.name.contains("aps", true) ||
                        it.name.contains("prediction", true)
                    )
        }
        return interesting > 0 || files.count { it.isFile && it.name.endsWith(".json", ignoreCase = true) } >= 3
    }

    private fun discoverNightscoutFromRootPrefs(): RootNightscoutDiscovery? {
        for (pkg in AAPS_PACKAGES) {
            val listCmd = "ls /data/data/$pkg/shared_prefs/*.xml 2>/dev/null"
            val files = runRootCommandOutput(listCmd).lineSequence().map { it.trim() }.filter { it.endsWith(".xml") }.toList()
            for (file in files) {
                val xml = runRootCommandOutput("cat '$file'")
                if (xml.isBlank()) continue
                val kv = parseStringXml(xml)
                val url = kv.entries.firstOrNull {
                    it.value.startsWith("http", ignoreCase = true) &&
                        (
                            it.key.contains("nightscout", true) ||
                                it.key.contains("nsclient", true) ||
                                it.key.contains("url", true)
                            )
                }?.value ?: kv.values.firstOrNull { it.startsWith("http", ignoreCase = true) }

                val secret = kv.entries.firstOrNull {
                    it.key.contains("secret", true) ||
                        it.key.contains("token", true) ||
                        it.key.contains("api", true)
                }?.value

                if (!url.isNullOrBlank() && !secret.isNullOrBlank()) {
                    return RootNightscoutDiscovery(
                        url = url,
                        secret = secret,
                        sourceFile = file
                    )
                }
            }
        }
        return null
    }

    private fun discoverRootDbPath(): String? {
        for (candidate in ROOT_DB_CANDIDATES) {
            if (runRootCommand("test -f $candidate")) return candidate
        }
        return null
    }

    private fun detectInstalledPackage(candidates: List<String>): String? {
        return candidates.firstOrNull { pkg -> isPackageInstalled(pkg) }
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            context.packageManager.getPackageInfo(packageName, 0)
        }
    }.isSuccess

    private fun parseStringXml(xml: String): Map<String, String> {
        val regex = Regex("<string\\s+name=\"([^\"]+)\">(.*?)</string>")
        return regex.findAll(xml).associate { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
            key to value
        }
    }

    private fun isRootAvailable(): Boolean = runRootCommand("id")

    private fun runRootCommand(command: String): Boolean {
        return runCatching {
            val process = ProcessBuilder("sh", "-c", "su -c \"$command\"").start()
            process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }

    private fun runRootCommandOutput(command: String): String {
        return runCatching {
            val process = ProcessBuilder("sh", "-c", "su -c \"$command\"").start()
            process.waitFor(4, TimeUnit.SECONDS)
            process.inputStream.bufferedReader().readText()
        }.getOrDefault("")
    }

    data class BootstrapResult(
        val exportConnected: Boolean,
        val exportPath: String?,
        val rootEnabled: Boolean,
        val rootAvailable: Boolean,
        val rootDbPath: String?,
        val nightscoutConfigured: Boolean,
        val nightscoutSource: String?,
        val aapsPackage: String?,
        val xdripPackage: String?,
        val hasAllFilesAccess: Boolean
    )

    data class RootNightscoutDiscovery(
        val url: String,
        val secret: String,
        val sourceFile: String
    )

    private companion object {
        val AAPS_PACKAGES = listOf(
            "info.nightscout.androidaps",
            "info.nightscout.aaps"
        )
        val XDRIP_PACKAGES = listOf(
            "com.eveningoutpost.dexdrip",
            "com.eveningoutpost.dexdrip.debug"
        )
        val ROOT_DB_CANDIDATES = listOf(
            "/data/data/info.nightscout.androidaps/databases/androidaps.db",
            "/data/user/0/info.nightscout.androidaps/databases/androidaps.db",
            "/data/data/info.nightscout.aaps/databases/androidaps.db",
            "/data/user/0/info.nightscout.aaps/databases/androidaps.db"
        )
    }
}
