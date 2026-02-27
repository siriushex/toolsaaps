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
        var exportConnected = false
        var rootEnabled = settings.rootExperimentalEnabled
        var updatedUrl = settings.nightscoutUrl
        var updatedSecret = settings.apiSecret

        if (settings.exportFolderUri.isNullOrBlank()) {
            val exportPath = discoverExportPath()
            if (!exportPath.isNullOrBlank()) {
                settingsStore.update { it.copy(exportFolderUri = exportPath) }
                exportConnected = true
                auditLogger.info("aaps_auto_connect_export_path", mapOf("path" to exportPath))
            }
        } else {
            exportConnected = true
        }

        if (isRootAvailable()) {
            if (!settings.rootExperimentalEnabled) {
                settingsStore.update { it.copy(rootExperimentalEnabled = true) }
                rootEnabled = true
                auditLogger.info("aaps_auto_connect_root_enabled")
            }
            if (settings.nightscoutUrl.isBlank() || settings.apiSecret.isBlank()) {
                val discovered = discoverNightscoutFromRootPrefs()
                if (discovered != null) {
                    updatedUrl = settings.nightscoutUrl.ifBlank { discovered.first }
                    updatedSecret = settings.apiSecret.ifBlank { discovered.second }
                    settingsStore.update {
                        it.copy(
                            nightscoutUrl = updatedUrl,
                            apiSecret = updatedSecret
                        )
                    }
                    auditLogger.info("aaps_auto_connect_nightscout_from_root", mapOf("url" to updatedUrl.take(80)))
                }
            }
        }

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
            rootEnabled = rootEnabled,
            nightscoutConfigured = updatedUrl.isNotBlank() && updatedSecret.isNotBlank(),
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

    private fun discoverNightscoutFromRootPrefs(): Pair<String, String>? {
        val packageCandidates = listOf(
            "info.nightscout.androidaps",
            "info.nightscout.aaps"
        )
        for (pkg in packageCandidates) {
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
                    return url to secret
                }
            }
        }
        return null
    }

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
        val rootEnabled: Boolean,
        val nightscoutConfigured: Boolean,
        val hasAllFilesAccess: Boolean
    )
}
