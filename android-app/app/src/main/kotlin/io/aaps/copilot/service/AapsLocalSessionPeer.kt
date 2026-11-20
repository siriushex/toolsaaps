package io.aaps.copilot.service

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.CancellationException

/** Private Android peer; a successful Binder return alone does not prove membership. */
internal object AapsLocalSessionPeer {
    private const val MASTER = "info.nightscout.androidaps"
    private const val AUTHORITY = "$MASTER.localsession"
    private const val PERMISSION = "$MASTER.permission.CHECK_LOCAL_CLIENT_SESSION"

    fun check(context: Context, masterId: String, nonce: String, request: ByteArray): ByteArray? =
        call(context, "witness_v1", Bundle().apply {
            putString("master", masterId); putString("nonce", nonce); putByteArray("request", request)
        })

    fun checkSettings(context: Context, masterId: String, nonce: String, ticket: LocalSettingsGrants.Ticket): ByteArray? =
        call(context, "settings_witness_v1", Bundle().apply {
            putString("master", masterId); putString("nonce", nonce); putBundle("ticket", ticket.extras)
        })

    fun hasMealPreview(context: Context): Boolean =
        trustedProvider(context, "$MASTER.localmealpreview", "$MASTER.permission.PREVIEW_LOCAL_MEAL")

    fun hasMealRecord(context: Context): Boolean =
        trustedProvider(context, "$MASTER.localmealrecord", "$MASTER.permission.RECORD_LOCAL_MEAL") && AapsLocalMealRecord.available(context)

    fun requestMealRecord(context: Context, master: String, request: ByteArray) {
        if (!hasMealRecord(context)) return
        val uri = Uri.parse("content://$MASTER.localmealrecord")
        context.contentResolver.acquireUnstableContentProviderClient(uri)?.use {
            // The RPC reply never replaces an actual AAPS carb row, including after a timeout.
            it.call("record_meal_v1", null, Bundle().apply { putString("master", master); putByteArray("request", request) })
        }
    }

    fun previewMeal(context: Context, master: String, request: ByteArray): ByteArray? =
        call(context, "preview_meal_v1", Bundle().apply { putString("master", master); putByteArray("request", request) },
            "$MASTER.localmealpreview", "$MASTER.permission.PREVIEW_LOCAL_MEAL", "preview", 16_384)

    fun previewTarget(context: Context, master: String, request: ByteArray): ByteArray? =
        call(context, "preview_target_v1", Bundle().apply { putString("master", master); putByteArray("request", request) },
            "$MASTER.localmealpreview", "$MASTER.permission.PREVIEW_LOCAL_MEAL", "preview", 16_384)

    private fun trustedProvider(context: Context, authority: String, permission: String): Boolean {
        val pm = context.packageManager
        val info = pm.resolveContentProvider(authority, 0) ?: return false
        return info.packageName == MASTER && info.exported && !info.grantUriPermissions &&
            info.readPermission == permission && info.writePermission == permission &&
            pm.checkSignatures(context.packageName, MASTER) == PackageManager.SIGNATURE_MATCH
    }

    private fun call(context: Context, method: String, extras: Bundle, authority: String = AUTHORITY,
                     permission: String = PERMISSION, reply: String = "witness", limit: Int = 2048): ByteArray? {
        if (!trustedProvider(context, authority, permission)) return null
        return try {
            context.contentResolver.acquireUnstableContentProviderClient(Uri.parse("content://$authority"))?.use { peer ->
                peer.call(method, null, extras)?.takeIf { it.keySet() == setOf(reply) }
                    ?.getByteArray(reply)?.takeIf { it.size in 1..limit }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
}
