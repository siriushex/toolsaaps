package io.aaps.copilot.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

internal object AapsTreatmentBroadcastCapability {
    private const val GLUCOSE_ACTION = "com.eveningoutpost.dexdrip.NS_EMULATOR"
    private const val TREATMENT_ACTION = "info.nightscout.androidaps.action.LOCAL_TREATMENTS"

    fun canSend(context: Context, intent: Intent): Boolean {
        val destination = intent.`package`?.takeIf { it.isNotBlank() } ?: return false
        if (intent.action.isNullOrBlank()) return false
        val receivers = receiverComponents(context, intent, destination)
        if (receivers.isEmpty()) return false
        if (intent.action != GLUCOSE_ACTION) return true

        // Stock AAPS uses NS_EMULATOR for glucose only. The legacy extension
        // declares both actions on the same receiver; package/version is not proof.
        val treatmentReceivers = receiverComponents(
            context,
            Intent(TREATMENT_ACTION).setPackage(destination),
            destination
        )
        return receivers.any { it in treatmentReceivers }
    }

    private fun receiverComponents(
        context: Context,
        intent: Intent,
        destination: String
    ): Set<ComponentName> = runCatching {
        val manager = context.packageManager
        val receivers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.queryBroadcastReceivers(intent, 0)
        }
        receivers.mapNotNull { resolved ->
            val info = resolved.activityInfo ?: return@mapNotNull null
            if (!info.enabled || !info.exported || info.packageName != destination ||
                info.name.isNullOrBlank()) return@mapNotNull null
            ComponentName(info.packageName, info.name)
        }.toSet()
    }.getOrDefault(emptySet())
}
