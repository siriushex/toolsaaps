package io.aaps.copilot.service

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import io.aaps.copilot.data.repository.HistoryJson
import kotlinx.coroutines.CancellationException

internal fun allowsAapsLocalUploadRequest(method: String, path: String, noQuery: Boolean): Boolean =
    noQuery && (method == "GET" && path == "/api/v3/status" ||
        method == "POST" && (path == "/api/v3/entries" || path == "/api/v3/treatments"))

internal fun allowsAapsLocalRecord(path: String, bytes: ByteArray): Boolean = runCatching {
    val body = HistoryJson.parse(bytes, 65_536)
    if (body.keys.any { it in forbiddenLocalFields } || HistoryJson.string(body.getValue("app")) != "AAPS-local")
        return@runCatching false
    when (path) {
        "/api/v3/entries" -> HistoryJson.string(body.getValue("type")) == "sgv"
        "/api/v3/treatments" -> when (HistoryJson.string(body.getValue("eventType"))) {
            "Correction Bolus", "Temp Basal", "Combo Bolus" -> true
            "Note" -> "originalProfileName" in body && "profileJson" in body
            else -> false
        }
        else -> false
    }
}.getOrDefault(false)

private val forbiddenLocalFields = setOf(
    "identifier", "srvCreated", "srvModified", "subject", "modifiedBy",
    "carbs", "targetTop", "targetBottom", "extendedEmulated"
)

internal object AapsLocalUpload {
    fun send(context: Context, ticket: Bundle): Boolean {
        val pm = context.packageManager
        val aaps = "info.nightscout.androidaps"
        val authority = "$aaps.localobservation"
        val permission = "$aaps.permission.READ_LOCAL_OBSERVATION"
        val provider = pm.resolveContentProvider(authority, 0) ?: return false
        if (provider.packageName != aaps || provider.readPermission != permission ||
            provider.writePermission != permission || provider.grantUriPermissions ||
            pm.checkSignatures(context.packageName, aaps) != PackageManager.SIGNATURE_MATCH) return false
        return try {
            context.contentResolver.call(Uri.parse("content://$authority"), "upload_v3", null, ticket)
                ?.getBoolean("accepted") == true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            false
        }
    }
}
