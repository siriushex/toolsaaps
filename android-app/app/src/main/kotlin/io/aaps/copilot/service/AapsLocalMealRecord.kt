package io.aaps.copilot.service

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Positive evidence of one AAPS carb record, not consumption of the meal or pump delivery. */
internal object AapsLocalMealRecord {
    private const val PACKAGE = "info.nightscout.androidaps"
    private const val AUTHORITY = "$PACKAGE.localobservation"
    private const val PERMISSION = "$PACKAGE.permission.READ_LOCAL_OBSERVATION"
    private val keyFormat = Regex("manual:meal:[A-Za-z0-9_-]{1,80}")

    fun available(context: Context): Boolean {
        val pm = context.packageManager
        val provider = pm.resolveContentProvider(AUTHORITY, 0) ?: return false
        return provider.packageName == PACKAGE && provider.exported && !provider.grantUriPermissions &&
            provider.readPermission == PERMISSION && provider.writePermission == PERMISSION &&
            pm.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH
    }

    suspend fun recorded(context: Context, key: String, at: Long, grams: Double): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!keyFormat.matches(key) || at !in (now - 86_400_000L)..now || grams !in 1.0..120.0) return@withContext false
        try {
            if (!available(context)) return@withContext false
            val reply = context.contentResolver.call(Uri.parse("content://$AUTHORITY"), "meal_receipt_v1", key,
                Bundle().apply { putLong("at", at); putDouble("grams", grams) })
            reply != null && reply.keySet() == setOf("recorded") && reply.getBoolean("recorded", false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    suspend fun recordedDuring(context: Context, key: String, issued: Long, grams: Double): Long? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!keyFormat.matches(key) || issued !in (now - 86_400_000L)..now || grams !in 1.0..120.0) return@withContext null
        val until = issued + AapsV3TargetProtocol.TTL_MS
        try {
            if (!available(context)) return@withContext null
            val reply = context.contentResolver.call(Uri.parse("content://$AUTHORITY"), "meal_receipt_v2", key,
                Bundle().apply { putLong("at", issued); putLong("until", until); putDouble("grams", grams) })
            reply?.takeIf { it.keySet() == setOf("recordedAt") }?.getLong("recordedAt", -1)
                ?.takeIf { it in issued until until && it <= System.currentTimeMillis() }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
}
