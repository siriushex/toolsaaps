package io.aaps.copilot.service

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import io.aaps.copilot.CopilotApp
import java.util.concurrent.Semaphore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Local final-AAPS enrollment only. No HTTP token, archive read, or command endpoint. */
class LocalClientTokenProvider : ContentProvider() {
    private val permit = Semaphore(1)
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val pm = ctx.packageManager
        val uid = Binder.getCallingUid()
        if (callingPackage != AAPS || pm.getPackagesForUid(uid)?.singleOrNull() != AAPS ||
            pm.checkSignatures(uid, android.os.Process.myUid()) != PackageManager.SIGNATURE_MATCH)
            throw SecurityException("Local pairing caller not authorized")
        ctx.enforceCallingPermission(PERMISSION, "Local pairing permission required")
        if (method != "issue_pending_v1" || arg != null || extras == null || !permit.tryAcquire()) return null
        return try {
            if (extras.keySet() != setOf("client", "master", "point", "subject")) return null
            val id = extras.getString("client")?.takeIf { it.length == 36 } ?: return null
            val master = extras.getString("master")?.takeIf { it.length in 1..128 } ?: return null
            val point = extras.getString("point")?.takeIf { it.length == 88 } ?: return null
            val subject = extras.getString("subject")?.takeIf(::isLocalClientSubject) ?: return null
            val db = (ctx.applicationContext as CopilotApp).container.db
            val bytes = runBlocking(Dispatchers.IO) {
                withTimeout(2500) { AapsV3TargetJournal(db, KeystoreAapsV3JournalCipher())
                    .issuePendingClientToken(id, master, point, subject, System.currentTimeMillis()) }
            }
            Bundle().apply { putByteArray("token", bytes) }
        } catch (_: Exception) { null } finally { permit.release() }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    private companion object {
        const val AAPS = "info.nightscout.androidaps"
        const val PERMISSION = "io.aaps.predictivecopilot.permission.ISSUE_LOCAL_CLIENT_TOKEN"
    }
}
