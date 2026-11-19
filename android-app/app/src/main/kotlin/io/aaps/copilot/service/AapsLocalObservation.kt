package io.aaps.copilot.service

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Bundle
import io.aaps.copilot.data.repository.AapsCarbHistoryContract
import io.aaps.copilot.data.repository.AapsCarbHistoryRequest
import io.aaps.copilot.data.repository.AapsCarbHistoryResult
import io.aaps.copilot.data.repository.AapsCarbHistoryWireResponse
import io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity
import io.aaps.copilot.data.repository.HistoryJson
import io.aaps.copilot.data.repository.LocalV3RetainedSnapshot
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.util.UnitConverter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** A single observation. Never feeds the clinical database, targets or command transport. */
internal object AapsLocalObservation {
    private const val AAPS = "info.nightscout.androidaps"
    private const val AUTHORITY = "$AAPS.localobservation"
    private const val PERMISSION = "$AAPS.permission.READ_LOCAL_OBSERVATION"
    private const val DAY_MS = 86_400_000L

    fun read(context: Context): AapsV3InboundSummary? {
        val provider = context.packageManager.resolveContentProvider(AUTHORITY, 0) ?: return null
        val snapshot = readVerifiedSnapshot(context, provider)
        val now = System.currentTimeMillis()
        return if (snapshot == null) unavailable(now) else decode(snapshot, now)
    }

    fun readDisplayGlucose(context: Context): List<GlucosePoint>? {
        val provider = context.packageManager.resolveContentProvider(AUTHORITY, 0) ?: return null
        val snapshot = readVerifiedSnapshot(context, provider) ?: return null
        return decodeDisplayGlucose(snapshot, System.currentTimeMillis())
    }

    fun readTarget(context: Context): AapsObservedTarget? {
        val provider = context.packageManager.resolveContentProvider(AUTHORITY, 0) ?: return null
        val snapshot = readVerifiedSnapshot(context, provider, "target_v1") ?: return null
        return AapsObservedTarget.decode(snapshot, System.currentTimeMillis())
    }

    fun readMetrics(context: Context): AapsObservedMetrics? {
        val provider = context.packageManager.resolveContentProvider(AUTHORITY, 0) ?: return null
        readVerifiedSnapshot(context, provider, "metrics_v2")?.let {
            return AapsObservedMetrics.decode(it, System.currentTimeMillis(), version = 2L)
        }
        val snapshot = readVerifiedSnapshot(context, provider, "metrics_v1") ?: return null
        return AapsObservedMetrics.decode(snapshot, System.currentTimeMillis())
    }

    fun readComboJournal(context: Context): AapsV3ComboHistoryStatus? {
        val provider = context.packageManager.resolveContentProvider(AUTHORITY, 0) ?: return null
        val snapshot = readVerifiedSnapshot(context, provider, "journal_v1") ?: return null
        return readLocalComboJournalSummary(snapshot, System.currentTimeMillis())
    }

    fun readCarbPage(context: Context, request: AapsCarbHistoryRequest): AapsCarbHistoryResult {
        if (!AapsCarbHistoryContract.validateRequest(request)) return AapsCarbHistoryResult.Invalid
        val provider = context.packageManager.resolveContentProvider(AUTHORITY, 0) ?: return AapsCarbHistoryResult.Unavailable
        val args = Bundle().apply {
            putString("nonce", request.nonce); putLong("fromTs", request.fromTs); putLong("throughTs", request.throughTs)
            putLong("afterTimestamp", request.afterTimestamp); putLong("afterId", request.afterId); putInt("limit", request.limit)
        }
        val reply = callVerified(context, provider, "carb_history_page_v1", args) ?: return AapsCarbHistoryResult.Unavailable
        return decodeCarbReply(request, reply)
    }

    internal fun decodeCarbReply(request: AapsCarbHistoryRequest, reply: Bundle): AapsCarbHistoryResult {
        if (reply.isEmpty) return AapsCarbHistoryResult.Busy
        val fields = reply.keySet().associateWith { reply[it] }
        return AapsCarbHistoryContract.responseResult(request,
            AapsCarbHistoryWireResponse(AapsCarbHistoryContract.RESPONSE_ACTION, fields)) ?: AapsCarbHistoryResult.Invalid
    }

    private fun readVerifiedSnapshot(context: Context, provider: ProviderInfo, method: String = "snapshot_v1"): ByteArray? =
        callVerified(context, provider, method, null)?.getByteArray("snapshot")

    private fun callVerified(context: Context, provider: ProviderInfo, method: String, extras: Bundle?): Bundle? {
        val pm = context.packageManager
        if (provider.packageName != AAPS || provider.readPermission != PERMISSION ||
            provider.writePermission != PERMISSION || provider.grantUriPermissions ||
            pm.checkSignatures(context.packageName, AAPS) != PackageManager.SIGNATURE_MATCH) {
            return null
        }
        return try {
            context.contentResolver.call(Uri.parse("content://$AUTHORITY"), method, null, extras)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }

    /** Reads only the glucose fields from a signed local snapshot for display. */
    internal fun decodeDisplayGlucose(bytes: ByteArray, now: Long): List<GlucosePoint>? = try {
        val body = HistoryJson.parse(bytes, 196_608)
        HistoryJson.fields(body, setOf("version", "at", "glucose", "insulin"), setOf("profile"))
        require(HistoryJson.long(body.getValue("version")) == 1L)
        val at = HistoryJson.long(body.getValue("at"))
        require(now in 1..HistoryJson.maxTime && at in maxOf(1, now - 10_000L)..now)
        HistoryJson.array(body.getValue("glucose")).also { require(it.size in 1..AapsV3ForecastPreview.MAX_ROWS) }
            .map {
                val item = HistoryJson.obj(it)
                HistoryJson.fields(item, setOf("date", "sgv", "sensor"))
                val time = HistoryJson.long(item.getValue("date"))
                require(time in maxOf(1, at - AapsV3ForecastPreview.WINDOW_MS)..at)
                val sensor = HistoryJson.string(item.getValue("sensor"))
                require(HistoryJson.code.matches(sensor))
                val value = UnitConverter.mgdlToMmol(HistoryJson.double(item.getValue("sgv")))
                require(value.isFinite() && value in 1.0..33.0)
                GlucosePoint(time, value, sensor)
            }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
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
