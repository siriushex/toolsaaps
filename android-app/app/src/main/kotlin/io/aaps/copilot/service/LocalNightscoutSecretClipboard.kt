package io.aaps.copilot.service

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

internal object LocalNightscoutSecretClipboard {
    const val DEFAULT_CLEAR_AFTER_MS = 60_000L

    fun copy(
        context: Context,
        secret: CharArray,
        clearAfterMs: Long = DEFAULT_CLEAR_AFTER_MS,
        nowMs: () -> Long = System::currentTimeMillis
    ) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val label = "$LABEL_PREFIX:${System.nanoTime()}"
        val boundedDelay = clearAfterMs.coerceIn(1_000L, MAX_CLEAR_AFTER_MS)
        val expiresAtMs = saturatedAdd(nowMs(), boundedDelay)
        val clip = ClipData.newPlainText(label, secret.concatToString()).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        context.getSharedPreferences(LEASE_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(LEASE_LABEL_KEY, label)
            .putLong(LEASE_EXPIRES_AT_KEY, expiresAtMs)
            .commit()
        scheduleOwnedClear(context.applicationContext, label, boundedDelay)
    }

    fun reconcile(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(LEASE_PREFERENCES, Context.MODE_PRIVATE)
        val label = preferences.getString(LEASE_LABEL_KEY, null)
        val expiresAtMs = preferences.getLong(LEASE_EXPIRES_AT_KEY, Long.MIN_VALUE)
        if (label.isNullOrBlank() || expiresAtMs == Long.MIN_VALUE) {
            preferences.edit().clear().commit()
            return
        }
        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
        val owned = runCatching {
            clipboard.primaryClipDescription?.label?.toString() == label
        }.getOrElse { return }
        if (!owned) {
            clearLease(preferences, label)
            return
        }
        val remainingMs = expiresAtMs - nowMs
        if (remainingMs <= 0L) {
            clearOwnedClip(clipboard, label)
            clearLease(preferences, label)
        } else {
            scheduleOwnedClear(appContext, label, remainingMs.coerceAtMost(MAX_CLEAR_AFTER_MS))
        }
    }

    internal fun clearLeaseForTest(context: Context) {
        context.getSharedPreferences(LEASE_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun scheduleOwnedClear(context: Context, label: String, delayMs: Long) {
        Handler(Looper.getMainLooper()).postDelayed(
            {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clearOwnedClip(clipboard, label)
                clearLease(
                    context.getSharedPreferences(LEASE_PREFERENCES, Context.MODE_PRIVATE),
                    label
                )
            },
            delayMs.coerceIn(1_000L, MAX_CLEAR_AFTER_MS)
        )
    }

    private fun clearOwnedClip(clipboard: ClipboardManager, label: String) {
        if (clipboard.primaryClipDescription?.label?.toString() != label) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, ""))
        }
    }

    private fun clearLease(preferences: android.content.SharedPreferences, label: String) {
        if (preferences.getString(LEASE_LABEL_KEY, null) != label) return
        preferences.edit().clear().commit()
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private const val LABEL_PREFIX = "AAPS Copilot Local Nightscout API secret"
    private const val LEASE_PREFERENCES = "local_nightscout_clipboard_lease"
    private const val LEASE_LABEL_KEY = "owned_label"
    private const val LEASE_EXPIRES_AT_KEY = "expires_at_ms"
    private const val MAX_CLEAR_AFTER_MS = 120_000L
}
