package io.aaps.copilot.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.BaselinePointEntity
import io.aaps.copilot.util.UnitConverter
import java.io.BufferedReader
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
        val uriString = settings.exportFolderUri
        if (uriString.isNullOrBlank()) {
            auditLogger.warn("baseline_import_skipped", mapOf("reason" to "missing_export_uri"))
            return@withContext
        }

        val root = DocumentFile.fromTreeUri(context, Uri.parse(uriString))
        if (root == null || !root.exists()) {
            auditLogger.warn("baseline_import_skipped", mapOf("reason" to "invalid_export_uri"))
            return@withContext
        }

        val jsonFiles = root.listFiles().filter { it.isFile && it.name?.endsWith(".json", ignoreCase = true) == true }
        if (jsonFiles.isEmpty()) {
            auditLogger.warn("baseline_import_skipped", mapOf("reason" to "no_json_files"))
            return@withContext
        }

        val points = mutableListOf<BaselinePointEntity>()
        for (file in jsonFiles) {
            runCatching {
                context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use(BufferedReader::readText)
            }.onSuccess { json ->
                if (json != null) {
                    points += parseResultFile(json)
                }
            }
        }

        if (points.isNotEmpty()) {
            db.baselineDao().upsertAll(points)
            auditLogger.info("baseline_import_completed", mapOf("points" to points.size, "files" to jsonFiles.size))
        } else {
            auditLogger.warn("baseline_import_empty", mapOf("files" to jsonFiles.size))
        }
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
}
