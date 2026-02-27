package io.aaps.copilot.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.BaselinePointEntity
import io.aaps.copilot.util.UnitConverter
import java.io.BufferedReader
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AapsExportRepository(
    private val context: Context,
    private val db: CopilotDatabase,
    private val settingsStore: AppSettingsStore,
    private val auditLogger: AuditLogger
) {

    suspend fun importBaselineFromExports() = withContext(Dispatchers.IO) {
        val settings = settingsStore.settings.first()
        val location = settings.exportFolderUri
        if (location.isNullOrBlank()) {
            auditLogger.warn("baseline_import_skipped", mapOf("reason" to "missing_export_uri"))
            return@withContext
        }

        val jsonFiles = discoverJsonFiles(location)
        if (jsonFiles.isEmpty()) {
            auditLogger.warn("baseline_import_skipped", mapOf("reason" to "no_json_files"))
            return@withContext
        }

        val points = mutableListOf<BaselinePointEntity>()
        for (file in jsonFiles) {
            runCatching {
                when (file) {
                    is JsonSource.Document -> context.contentResolver.openInputStream(file.document.uri)?.bufferedReader()?.use(BufferedReader::readText)
                    is JsonSource.Path -> file.file.takeIf { it.exists() }?.readText()
                }
            }.onSuccess { json ->
                if (json != null) {
                    points += parseResultFile(json)
                }
            }
        }

        if (points.isNotEmpty()) {
            db.baselineDao().upsertAll(points)
            auditLogger.info(
                "baseline_import_completed",
                mapOf("points" to points.size, "files" to jsonFiles.size, "location" to location.take(120))
            )
        } else {
            auditLogger.warn("baseline_import_empty", mapOf("files" to jsonFiles.size))
        }
    }

    private suspend fun discoverJsonFiles(location: String): List<JsonSource> {
        val path = normalizePath(location)
        if (path != null) {
            val root = File(path)
            if (!root.exists() || !root.isDirectory) {
                auditLogger.warn("baseline_import_skipped", mapOf("reason" to "invalid_export_path", "path" to path))
                return emptyList()
            }
            return root.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
                ?.map { JsonSource.Path(it) }
                .orEmpty()
        }

        val root = DocumentFile.fromTreeUri(context, Uri.parse(location))
        if (root == null || !root.exists()) {
            auditLogger.warn("baseline_import_skipped", mapOf("reason" to "invalid_export_uri"))
            return emptyList()
        }
        return root.listFiles()
            .filter { it.isFile && it.name?.endsWith(".json", ignoreCase = true) == true }
            .map { JsonSource.Document(it) }
    }

    private fun normalizePath(location: String): String? {
        val trimmed = location.trim()
        if (trimmed.startsWith("/")) return trimmed
        if (trimmed.startsWith("file://")) return runCatching { Uri.parse(trimmed).path }.getOrNull()
        return null
    }

    private fun parseResultFile(content: String): List<BaselinePointEntity> {
        val root = JSONObject(content)
        val timestamp = when {
            root.has("date") -> root.optLong("date")
            root.has("timestamp") -> root.optLong("timestamp")
            root.has("created_at") -> runCatching { Instant.parse(root.getString("created_at")).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
            else -> System.currentTimeMillis()
        }

        val algorithm = root.optString("algorithm").ifBlank { "AAPS" }
        val predictions = mutableListOf<BaselinePointEntity>()

        if (root.has("predictions")) {
            predictions += parsePredictionsObject(root.getJSONObject("predictions"), timestamp, algorithm)
        }

        if (root.has("resultJson")) {
            val nestedRaw = root.optString("resultJson")
            if (nestedRaw.startsWith("{")) {
                val nested = JSONObject(nestedRaw)
                if (nested.has("predictions")) {
                    predictions += parsePredictionsObject(nested.getJSONObject("predictions"), timestamp, algorithm)
                }
            }
        }

        return predictions
    }

    private fun parsePredictionsObject(predictions: JSONObject, baseTs: Long, algorithm: String): List<BaselinePointEntity> {
        val result = mutableListOf<BaselinePointEntity>()
        val keys = predictions.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val array = predictions.optJSONArray(key) ?: continue
            result += parsePredictionArray(array, baseTs, "$algorithm:$key")
        }
        return result
    }

    private fun parsePredictionArray(array: JSONArray, baseTs: Long, algorithm: String): List<BaselinePointEntity> {
        val points = mutableListOf<BaselinePointEntity>()
        var horizon = 5
        for (i in 0 until array.length()) {
            val mgdl = array.optDouble(i, Double.NaN)
            if (mgdl.isNaN()) {
                horizon += 5
                continue
            }
            points += BaselinePointEntity(
                timestamp = baseTs + horizon * 60_000L,
                algorithm = algorithm,
                valueMmol = UnitConverter.mgdlToMmol(mgdl),
                horizonMinutes = horizon
            )
            horizon += 5
        }
        return points
    }

    private sealed interface JsonSource {
        data class Document(val document: DocumentFile) : JsonSource
        data class Path(val file: File) : JsonSource
    }
}
