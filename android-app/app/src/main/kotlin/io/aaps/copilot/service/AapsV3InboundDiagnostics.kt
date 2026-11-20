package io.aaps.copilot.service

import io.aaps.copilot.data.repository.HistoryJson
import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import io.aaps.copilot.data.repository.LocalV3Collection
import io.aaps.copilot.data.repository.LocalV3StoreError
import io.aaps.copilot.data.repository.LocalV3StoreException
import kotlinx.coroutines.CancellationException

enum class AapsV3InboundGlucose { FRESH, STALE, NOT_OBSERVED, CONFLICTING, CLOCK, LIMIT_REACHED, UNAVAILABLE }

/** Positive time is the stored start, not a claim that the target is still active. */
data class LocalTargetRowCheck(val recordedAt: Long?)

data class AapsV3InboundSummary(
    val checkedAtMs: Long,
    val glucose: AapsV3InboundGlucose,
    val profile: AapsV3ObservedProfileStatus,
    val insulin: AapsV3ObservedInsulinStatus,
    val insulinRecordCount: Int,
    val samePhone: Boolean = false,
    val comboHistory: AapsV3ComboHistoryStatus = AapsV3ComboHistoryStatus.UNCHECKED,
    val targetRow: LocalTargetRowCheck? = null,
    val samePhoneGlucoseAtMs: Long? = null
) {
    internal fun hasFreshPairedGlucose(now: Long): Boolean =
        samePhone && glucose == AapsV3InboundGlucose.FRESH && now >= checkedAtMs &&
            samePhoneGlucoseAtMs?.let { it in 1..checkedAtMs && now - it <= 300_000L } == true
}

/** Checks locally retained uploads, without starting a session or projecting clinical inputs. */
internal class AapsV3InboundDiagnostics(private val store: LocalNightscoutV3Store) {
    private val profileSource = AapsV3EffectiveProfileSource(store)

    suspend fun check(now: Long): AapsV3InboundSummary {
        val evidence = profileSource.readEvidence(maxOf(1, now - HISTORY_MS), now, now)
        val count = evidence.insulin.records.count { !it.invalidated }
        val insulin = when (evidence.insulin.status) {
            AapsV3ObservedInsulinStatus.OBSERVED, AapsV3ObservedInsulinStatus.PARTIAL_CONTEXT ->
                if (count == 0) AapsV3ObservedInsulinStatus.NOT_OBSERVED else evidence.insulin.status
            else -> evidence.insulin.status
        }
        return AapsV3InboundSummary(
            now, readGlucose(now), evidence.profile.status, insulin, count,
            comboHistory = evidence.comboHistory.status
        )
    }

    suspend fun matchesGlucose(at: Long, mgdl: Double, now: Long): Boolean =
        readGlucose(now, at to mgdl) == AapsV3InboundGlucose.FRESH

    private suspend fun readGlucose(now: Long, expected: Pair<Long, Double>? = null): AapsV3InboundGlucose {
        if (now !in 1..LocalNightscoutV3Store.MAX_TIME) return AapsV3InboundGlucose.CLOCK
        return try {
            val page = store.latest(LocalV3Collection.ENTRIES, 128)
            if (page.collectionRevision > now) return AapsV3InboundGlucose.CLOCK
            // Results are sorted by event time. The final group may be cut off by the limit.
            var latest: Long? = null
            var countAtLatest = 0
            var mgdl: Double? = null
            for (row in page.documents) {
                if (latest != null && row.eventAtMs != latest) break
                if (row.source != "local-v3-owner") continue
                val body = HistoryJson.parse(HistoryJson.encodeUtf8(row.payloadJson), LocalNightscoutV3Store.MAX_PAYLOAD_BYTES)
                if (HistoryJson.string(body.getValue("type")) != "sgv") continue
                val at = HistoryJson.long(body.getValue("date"))
                require(at == row.eventAtMs && at in 1..LocalNightscoutV3Store.MAX_TIME)
                mgdl = HistoryJson.double(body.getValue("sgv")).also { require(it in Double.MIN_VALUE..1000.0) }
                latest = at
                countAtLatest++
            }
            when {
                page.hasMore && (latest == null || latest == page.documents.last().eventAtMs) ->
                    AapsV3InboundGlucose.LIMIT_REACHED
                latest == null -> AapsV3InboundGlucose.NOT_OBSERVED
                latest > now -> AapsV3InboundGlucose.CLOCK
                // Multiple owner SGVs at the same acquisition time are not proof of a unique sample.
                countAtLatest > 1 -> AapsV3InboundGlucose.CONFLICTING
                expected != null && (latest != expected.first || mgdl != expected.second) -> AapsV3InboundGlucose.CONFLICTING
                now - latest <= GLUCOSE_FRESH_MS -> AapsV3InboundGlucose.FRESH
                else -> AapsV3InboundGlucose.STALE
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LocalV3StoreException) {
            if (failure.reason == LocalV3StoreError.PAGE_CAPACITY) AapsV3InboundGlucose.LIMIT_REACHED
            else AapsV3InboundGlucose.UNAVAILABLE
        } catch (_: Exception) {
            AapsV3InboundGlucose.UNAVAILABLE
        }
    }

    private companion object {
        const val GLUCOSE_FRESH_MS = 5 * 60_000L
        const val HISTORY_MS = 24 * 60 * 60_000L
    }
}
