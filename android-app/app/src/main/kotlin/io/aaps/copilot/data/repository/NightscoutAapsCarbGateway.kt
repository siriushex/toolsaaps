package io.aaps.copilot.data.repository

import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.config.AppSettingsStore
import io.aaps.copilot.config.resolvedNightscoutUrl
import io.aaps.copilot.data.remote.nightscout.NightscoutTreatmentRequest
import io.aaps.copilot.domain.predict.UamTagCodec
import io.aaps.copilot.service.ApiFactory
import java.time.Instant
import kotlinx.coroutines.flow.first

class NightscoutAapsCarbGateway(
    private val settingsStore: AppSettingsStore,
    private val apiFactory: ApiFactory,
    private val carbsSendThrottle: CarbsSendThrottle,
    private val auditLogger: AuditLogger
) : AapsCarbGateway {

    override suspend fun postCarbEntry(tsMs: Long, grams: Double, note: String): Result<String> {
        val settings = settingsStore.settings.first()
        val nowMs = System.currentTimeMillis()
        val throttle = carbsSendThrottle.evaluate(nowMs)
        if (!throttle.allowed) {
            auditLogger.warn(
                "uam_export_blocked_rate_limit",
                mapOf(
                    "waitMinutes" to throttle.waitMinutes,
                    "lastSentTs" to (throttle.lastSentTs ?: 0L)
                )
            )
            return Result.failure(IllegalStateException("carbs_rate_limit_30m"))
        }
        val safeTs = tsMs.coerceAtLeast(1L)
        val safeGrams = grams.coerceAtLeast(0.1)
        val request = NightscoutTreatmentRequest(
            createdAt = Instant.ofEpochMilli(safeTs).toString(),
            date = safeTs,
            mills = safeTs,
            eventType = "Carb Correction",
            carbs = safeGrams,
            notes = note,
            reason = "uam_engine"
        )

        var lastError: Throwable? = null
        nightscoutTargets(settings).forEach { target ->
            val result = runCatching {
                apiFactory.nightscoutApi(target, settings.apiSecret).postTreatment(request)
            }
            if (result.isSuccess) {
                val id = result.getOrNull()?.id ?: "no_remote_id"
                carbsSendThrottle.recordGatewaySent(
                    nowMs = System.currentTimeMillis(),
                    idempotencyKey = gatewayIdempotencyKey(note = note, tsMs = safeTs),
                    payloadJson = """{"tsMs":$safeTs,"grams":$safeGrams,"source":"uam_gateway"}"""
                )
                auditLogger.info(
                    "uam_export_post_success",
                    mapOf("target" to target, "tsMs" to safeTs, "grams" to safeGrams, "id" to id)
                )
                return Result.success(id)
            }
            lastError = result.exceptionOrNull()
        }
        return Result.failure(lastError ?: IllegalStateException("nightscout_post_failed"))
    }

    override suspend fun fetchCarbEntries(sinceTsMs: Long): Result<List<AapsCarbEntry>> {
        val settings = settingsStore.settings.first()
        val sinceIso = Instant.ofEpochMilli(sinceTsMs.coerceAtLeast(1L)).toString()
        var lastError: Throwable? = null

        nightscoutTargets(settings).forEach { target ->
            val result = runCatching {
                apiFactory.nightscoutApi(target, settings.apiSecret).getTreatments(
                    mapOf(
                        "count" to "2000",
                        "find[created_at][\$gte]" to sinceIso
                    )
                )
            }
            if (result.isFailure) {
                lastError = result.exceptionOrNull()
                return@forEach
            }
            val rows = result.getOrDefault(emptyList())
            val mapped = rows.mapNotNull { treatment ->
                val grams = treatment.carbs ?: return@mapNotNull null
                if (grams <= 0.0) return@mapNotNull null
                val ts = parseTimestamp(treatment.createdAt, treatment.date) ?: return@mapNotNull null
                AapsCarbEntry(
                    remoteId = treatment.id,
                    tsMs = ts,
                    grams = grams,
                    note = treatment.notes
                )
            }
            return Result.success(mapped)
        }
        return Result.failure(lastError ?: IllegalStateException("nightscout_fetch_failed"))
    }

    private fun nightscoutTargets(settings: AppSettings): List<String> {
        val out = linkedSetOf<String>()
        val primary = normalizeTarget(settings.resolvedNightscoutUrl())
        if (primary.isNotBlank()) out += primary
        if (settings.localNightscoutEnabled) {
            out += normalizeTarget("https://127.0.0.1:${settings.localNightscoutPort}")
        }
        return out.toList()
    }

    private fun normalizeTarget(url: String): String = url.trim().trimEnd('/')

    private fun parseTimestamp(createdAt: String?, date: Long?): Long? {
        val fromCreated = runCatching { createdAt?.let { Instant.parse(it).toEpochMilli() } }.getOrNull()
        if (fromCreated != null && fromCreated > 0L) return fromCreated
        val fromDate = date?.takeIf { it > 0L } ?: return null
        return if (fromDate < 1_000_000_000_000L) fromDate * 1000L else fromDate
    }

    private fun gatewayIdempotencyKey(note: String, tsMs: Long): String {
        val tag = UamTagCodec.parseUamTag(note)
        if (tag != null) {
            return "uam_export:${tag.id}:${tag.seq}"
        }
        return "uam_export_note:${note.hashCode()}:$tsMs"
    }
}
