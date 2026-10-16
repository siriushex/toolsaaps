package io.aaps.copilot.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.domain.alerts.*
import kotlinx.coroutines.*

/** Notification actions can acknowledge an existing owner, never start or arm one. */
class GlucoseAlarmAckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val parsed = decode(intent) ?: return
        val owner = (context.applicationContext as? CopilotApp)?.container?.glucoseAlarmRouter?.currentOwner() ?: return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try { withTimeoutOrNull(8_000L) { owner.acknowledge(parsed.first, parsed.second) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* No successful acknowledgement is reported. */ }
            finally { result.finish() }
        }
    }

    companion object {
        private const val ACTION = "io.aaps.copilot.ALARM_ACK"
        fun pendingIntent(context: Context, session: String, action: LocalAlarmAcknowledgement): PendingIntent =
            PendingIntent.getBroadcast(context, 8414, encode(context, session, action), PendingIntent.FLAG_IMMUTABLE)

        internal fun encode(context: Context, session: String, a: LocalAlarmAcknowledgement) =
            Intent(context, GlucoseAlarmAckReceiver::class.java).setAction(ACTION).setData(
                Uri.Builder().scheme("alert-ack").authority(context.packageName).appendPath(session)
                    .appendPath(a.key.kind.name).appendPath(a.key.id).appendPath(a.generation.toString())
                    .appendPath(a.ordinal.toString()).appendPath(a.level.name).build())

        internal fun decode(intent: Intent?): Pair<String, LocalAlarmAcknowledgement>? {
            if (intent?.action != ACTION) return null
            val uri = intent.data?.takeIf { it.scheme == "alert-ack" && it.toString().length <= 512 } ?: return null
            val p = uri.pathSegments.takeIf { it.size == 6 } ?: return null
            if (!p[0].matches(Regex("[0-9a-fA-F-]{36}")) || p[2].length !in 1..128 || p[2].any { it !in ' '..'~' }) return null
            val generation = p[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val ordinal = p[4].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val kind = LocalAlarmSourceKind.entries.firstOrNull { it.name == p[1] } ?: return null
            val level = LocalAlarmLevel.entries.firstOrNull { it.name == p[5] && it.audible } ?: return null
            if (kind != LocalAlarmSourceKind.GLUCOSE) return null
            return p[0] to LocalAlarmAcknowledgement(LocalAlarmKey(kind, p[2]), generation, ordinal, level)
        }
    }
}
