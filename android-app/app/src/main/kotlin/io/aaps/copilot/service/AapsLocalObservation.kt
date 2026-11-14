package io.aaps.copilot.service

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity
import io.aaps.copilot.data.repository.HistoryJson
import io.aaps.copilot.data.repository.LocalV3RetainedSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** A single observation. Never feeds the clinical database, targets or command transport. */
internal object AapsLocalObservation {
    private const val AAPS = "info.nightscout.androidaps"
    private const val AUTHORITY = "$AAPS.localobservation"
    private const val PERMISSION = "$AAPS.permission.READ_LOCAL_OBSERVATION"
    private const val DAY_MS = 86_400_000L

    fun read(context: Context): AapsV3InboundSummary? {
        val pm = context.packageManager
        val provider = pm.resolveContentProvider(AUTHORITY, 0) ?: return null
        if (provider.packageName != AAPS || provider.readPermission != PERMISSION ||
            provider.writePermission != PERMISSION || provider.grantUriPermissions ||
            pm.checkSignatures(context.packageName, AAPS) != PackageManager.SIGNATURE_MATCH) {
            return unavailable(System.currentTimeMillis())
        }
        return try {
            val snapshot = context.contentResolver.call(Uri.parse("content://$AUTHORITY"), "snapshot_v1", null, null)
                ?.getByteArray("snapshot")
            val now = System.currentTimeMillis()
            if (snapshot == null) unavailable(now) else decode(snapshot, now)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            unavailable(System.currentTimeMillis())
        }
    }

    fun decode(bytes: ByteArray, now: Long): AapsV3InboundSummary = try {
        val body = HistoryJson.parse(bytes, 196_608)
        HistoryJson.fields(body, setOf("version", "at", "glucose", "insulin"), setOf("profile"))
        require(HistoryJson.long(body.getValue("version")) == 1L)
        val at = HistoryJson.long(body.getValue("at"))
        require(now in 1..HistoryJson.maxTime && at in maxOf(1, now - 10_000L)..now)
        val samples = HistoryJson.array(body.getValue("glucose")).also { require(it.size <= 256) }
            .map {
                val sample = HistoryJson.obj(it)
                HistoryJson.fields(sample, setOf("date", "sgv", "sensor"))
                require(HistoryJson.double(sample.getValue("sgv")) in Double.MIN_VALUE..1000.0)
                require(HistoryJson.code.matches(HistoryJson.string(sample.getValue("sensor"))))
                HistoryJson.long(sample.getValue("date")).also { require(it in maxOf(1, at - 30 * 60_000L)..at) }
            }
        val latest = samples.maxOrNull()
        val glucose = when {
            latest == null -> AapsV3InboundGlucose.NOT_OBSERVED
            samples.count { it == latest } > 1 -> AapsV3InboundGlucose.CONFLICTING
            now - latest <= 5 * 60_000L -> AapsV3InboundGlucose.FRESH
            else -> AapsV3InboundGlucose.STALE
        }
        val profile = body["profile"]?.let { value ->
            when (AapsV3EffectiveProfileProjector.project(observationRow(HistoryJson.obj(value), "profile", at))) {
                is AapsV3ProfileProjection.Observed -> AapsV3ObservedProfileStatus.OBSERVED
                else -> AapsV3ObservedProfileStatus.INVALID
            }
        } ?: AapsV3ObservedProfileStatus.NOT_OBSERVED
        val from = maxOf(1, at - DAY_MS)
        val records = HistoryJson.array(body.getValue("insulin")).also { require(it.size <= 512) }.mapIndexed { index, row ->
            val record = HistoryJson.obj(row)
            require(HistoryJson.string(record.getValue("eventType")) in setOf("Correction Bolus", "Temp Basal", "Combo Bolus"))
            observationRow(record, "insulin-$index", at).also { require(checkNotNull(it.eventAtMs) in from..at) }
        }
        val insulin = AapsV3InsulinInventoryTimeline(LocalV3RetainedSnapshot.Complete(at, records)).read(from, at, at)
        val count = insulin.records.count { !it.invalidated }
        AapsV3InboundSummary(now, glucose, profile, insulin.status, count, samePhone = true)
    } catch (_: Exception) {
        unavailable(now)
    }

    private fun observationRow(body: JsonObject, id: String, at: Long): LocalNightscoutV3DocumentEntity {
        val date = HistoryJson.long(body.getValue("date")).also { require(it in 1..at) }
        val json = body.toString()
        return LocalNightscoutV3DocumentEntity("treatments", id, "local-v3-owner", date, json,
            HistoryJson.encodeUtf8(json).size, at, at, false, true)
    }

    private fun unavailable(now: Long) = AapsV3InboundSummary(now, AapsV3InboundGlucose.UNAVAILABLE,
        AapsV3ObservedProfileStatus.ERROR, AapsV3ObservedInsulinStatus.ERROR, 0, samePhone = true)
}
