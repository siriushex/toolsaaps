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

    fun check(context: Context, masterId: String, nonce: String, request: ByteArray): ByteArray? {
        val pm = context.packageManager
        val info = pm.resolveContentProvider(AUTHORITY, 0) ?: return null
        if (info.packageName != MASTER || !info.exported || info.grantUriPermissions ||
            info.readPermission != PERMISSION || info.writePermission != PERMISSION ||
            pm.checkSignatures(context.packageName, MASTER) != PackageManager.SIGNATURE_MATCH) return null
        return try {
            context.contentResolver.acquireUnstableContentProviderClient(Uri.parse("content://$AUTHORITY"))?.use { peer ->
                peer.call("witness_v1", null, Bundle().apply {
                    putString("master", masterId); putString("nonce", nonce); putByteArray("request", request)
                })?.takeIf { it.keySet() == setOf("witness") }?.getByteArray("witness")?.takeIf { it.size in 1..2048 }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
}
