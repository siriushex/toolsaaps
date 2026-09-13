package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.AlertDeliveryReceiptEntity
import io.aaps.copilot.data.local.entity.AlertEventEntity
import io.aaps.copilot.domain.alerts.DeliveryDiagnosticAssessment
import io.aaps.copilot.domain.alerts.DeliveryDiagnosticObservation
import io.aaps.copilot.domain.alerts.DeliveryDiagnosticPolicy
import io.aaps.copilot.domain.alerts.DeliveryDiagnosticReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Advisory only. Room claims are terminal even when Android posting has an unknown outcome. */
class DeliveryDiagnosticRepository(
    private val db: CopilotDatabase,
    private val episodeAlerts: EpisodeAlertDeliveryStateMachine,
    private val post: suspend (DeliveryDiagnosticAssessment) -> AlertReceiptResult,
    private val clear: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun accept(nowTs: Long, observation: DeliveryDiagnosticObservation?, enabled: Boolean, lowRisk: Boolean) {
        require(nowTs > 0)
        episodeAlerts.coordinateMutedSideEffect { muted ->
            // Mandatory removal cannot depend on decoding possibly corrupt diagnostic evidence.
            if (!enabled || lowRisk || muted) clear()
            val outcome = db.withTransaction { advance(nowTs, observation, enabled, lowRisk, muted) }
            if (outcome.clear) clear()
            val claim = outcome.claim ?: return@coordinateMutedSideEffect
            currentCoroutineContext().ensureActive()
            val postedAt = clock()
            val result = if (postedAt < nowTs || postedAt - nowTs > DeliveryDiagnosticPolicy.MAX_GAP_MS) {
                AlertReceiptResult.FAILED
            } else try {
                when (post(requireNotNull(outcome.assessment))) {
                    AlertReceiptResult.DELIVERED -> AlertReceiptResult.DELIVERED
                    else -> AlertReceiptResult.FAILED
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AlertReceiptResult.FAILED
            }
            db.withTransaction {
                check(db.alertDeliveryReceiptDao().update(claim.copy(
                    result = result.name,
                    deliveredAt = postedAt.takeIf { result == AlertReceiptResult.DELIVERED },
                    sanitizedError = "notification_unavailable".takeIf { result == AlertReceiptResult.FAILED }
                )) == 1)
                if (result == AlertReceiptResult.DELIVERED) {
                    val event = requireNotNull(db.alertEventDao().byEpisodeId(claim.episodeId))
                    db.alertEventDao().upsert(event.copy(lastNotificationAt = postedAt))
                }
            }
        }
    }

    private suspend fun advance(
        nowTs: Long, observation: DeliveryDiagnosticObservation?, enabled: Boolean, lowRisk: Boolean, muted: Boolean
    ): Outcome {
        val dao = db.alertEventDao()
        val saved = dao.byEpisodeId(STATE_ID)
        val state = saved?.let { DeliveryDiagnosticStateCodec.decode(it.localSnapshotJson) } ?: DeliveryDiagnosticState()
        require(dao.unresolvedDeliveryDiagnosticIds() == listOfNotNull(state.activeEpisodeId.takeIf { it.isNotEmpty() })) {
            "Diagnostic state does not match open episode"
        }
        val active = state.activeEpisodeId.takeIf { it.isNotEmpty() }?.let { id ->
            requireNotNull(dao.byEpisodeId(id)).also { require(it.eventType == EVENT_TYPE && it.status == "OPEN") }
        }
        if (nowTs <= state.lastSeenAt) return Outcome()
        var next = state.copy(lastSeenAt = nowTs)
        var outcome = Outcome()
        val valid = enabled && !lowRisk && observation != null && observation.observedAt == nowTs &&
            DeliveryDiagnosticPolicy.validObservation(observation)
        if (!valid) {
            next = next.copy(history = emptyList(), pendingSince = 0, pendingMuted = false)
        } else {
            val sample = requireNotNull(observation)
            val previous = state.history.lastOrNull()
            if (sample.glucoseTs <= state.lastGlucoseTs) {
                // Duplicate samples cannot replace a saved forecast or advance confirmation.
                if (sample.glucoseTs < state.lastGlucoseTs || previous == null ||
                    previous.glucoseMmol != sample.glucoseMmol || previous.basisKey != sample.basisKey
                ) next = next.copy(history = emptyList(), pendingSince = 0, pendingMuted = false)
            } else {
                val continuous = previous != null && sample.basisKey == previous.basisKey &&
                    sample.forecast30TargetTs > previous.forecast30TargetTs &&
                    sample.glucoseTs - previous.glucoseTs <= DeliveryDiagnosticPolicy.MAX_GAP_MS &&
                    sample.observedAt - previous.observedAt <= DeliveryDiagnosticPolicy.MAX_GAP_MS
                var history = if (continuous) state.history else emptyList()
                history = history.filter { sample.glucoseTs - it.glucoseTs <= DeliveryDiagnosticPolicy.HISTORY_MS &&
                    sample.observedAt - it.observedAt <= DeliveryDiagnosticPolicy.HISTORY_MS } + sample
                if (history.size > DeliveryDiagnosticPolicy.MAX_OBSERVATIONS) history = listOf(sample)
                next = next.copy(lastGlucoseTs = sample.glucoseTs, history = history,
                    pendingSince = state.pendingSince.takeIf { continuous && history.size > 1 } ?: 0,
                    pendingMuted = state.pendingMuted && continuous && history.size > 1)
                val assessment = DeliveryDiagnosticPolicy.assess(history, nowTs)
                when (assessment.reason) {
                    DeliveryDiagnosticReason.UNEXPECTED_RISE, DeliveryDiagnosticReason.PERSISTENT_HIGH -> {
                        if (active == null) {
                            next = next.copy(pendingSince = next.pendingSince.takeIf { it > 0 } ?: sample.glucoseTs,
                                pendingMuted = next.pendingMuted || muted)
                            if (sample.glucoseTs - next.pendingSince >= CONFIRMATION_MS) {
                                val sequence = maxOf(nowTs, Math.addExact(state.sequence, 1L))
                                val id = "delivery-diagnostic-$sequence"
                                val suppressed = next.pendingMuted
                                val receipt = AlertDeliveryReceiptEntity(
                                    receiptId = "$id-initial", episodeId = id,
                                    kind = if (suppressed) "SUPPRESSED_SNOOZE" else "INITIAL",
                                    attemptedAt = nowTs, result = if (suppressed) "SUPPRESSED" else "CLAIMED",
                                    suppressionUntil = if (suppressed) dao.byEpisodeId(EpisodeAlertDeliveryStateMachine.MUTE_STATE_ID)?.suppressionUntil else null,
                                    deliveredAt = null, sanitizedError = null
                                )
                                require(dao.byEpisodeId(id) == null)
                                dao.upsert(event(id, EVENT_TYPE, "OPEN", nowTs,
                                    Gson().toJson(assessment), cause = "DELIVERY_NONRESPONSE"))
                                check(db.alertDeliveryReceiptDao().insert(receipt) != -1L)
                                next = next.copy(sequence = sequence, activeEpisodeId = id, pendingSince = 0, pendingMuted = false)
                                outcome = Outcome(claim = receipt.takeUnless { suppressed }, assessment = assessment)
                            }
                        }
                    }
                    DeliveryDiagnosticReason.RECOVERY -> {
                        if (active != null) {
                            dao.upsert(active.copy(status = "RESOLVED", resolvedAt = nowTs, updatedAt = nowTs,
                                revision = Math.addExact(active.revision, 1)))
                            next = next.copy(activeEpisodeId = "")
                            outcome = Outcome(clear = true)
                        }
                        next = next.copy(pendingSince = 0, pendingMuted = false)
                    }
                    else -> next = next.copy(pendingSince = 0, pendingMuted = false)
                }
            }
        }
        if (saved != null || next.history.isNotEmpty() || active != null) {
            dao.upsert(event(STATE_ID, "DELIVERY_DIAGNOSTIC_STATE", "RESOLVED", nowTs,
                DeliveryDiagnosticStateCodec.encode(next)).copy(createdAt = saved?.createdAt ?: nowTs,
                resolvedAt = Long.MAX_VALUE, revision = Math.addExact(saved?.revision ?: 0, 1)))
        }
        return outcome
    }

    private fun event(id: String, type: String, status: String, now: Long, json: String, cause: String? = null) =
        AlertEventEntity(id, type, "ADVISORY", status, "WARNING", now, now, null, json, cause,
            null, null, null, 1)

    private data class Outcome(val clear: Boolean = false, val claim: AlertDeliveryReceiptEntity? = null,
        val assessment: DeliveryDiagnosticAssessment? = null)

    companion object {
        const val STATE_ID = "__delivery_diagnostic_state_v1"
        const val EVENT_TYPE = "DELIVERY_DIAGNOSTIC"
        private const val CONFIRMATION_MS = 5 * 60_000L
    }
}

private data class DeliveryDiagnosticState(
    val schemaVersion: Int = 1,
    val lastSeenAt: Long = 0,
    val lastGlucoseTs: Long = 0,
    val sequence: Long = 0,
    val activeEpisodeId: String = "",
    val pendingSince: Long = 0,
    val pendingMuted: Boolean = false,
    val history: List<DeliveryDiagnosticObservation> = emptyList()
)

private object DeliveryDiagnosticStateCodec {
    private val gson = Gson()
    fun encode(state: DeliveryDiagnosticState): String = gson.toJson(state).also { require(it.length <= 65_536) }

    fun decode(json: String): DeliveryDiagnosticState {
        require(json.length <= 65_536)
        val root = JsonParser.parseString(json).also { require(it.isJsonObject) }.asJsonObject
        val numbers = listOf("schemaVersion", "lastSeenAt", "lastGlucoseTs", "sequence", "pendingSince")
        numbers.forEach { root.integer(it) }
        require(root.get("schemaVersion").asInt == 1)
        require(root.get("activeEpisodeId")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true)
        require(root.get("pendingMuted")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean } == true)
        require(root.get("history")?.isJsonArray == true)
        val rows = root.getAsJsonArray("history")
        require(rows.size() <= DeliveryDiagnosticPolicy.MAX_OBSERVATIONS)
        rows.forEach { row ->
            require(row.isJsonObject)
            val o = row.asJsonObject
            listOf("observedAt", "glucoseTs", "forecast30TargetTs").forEach { o.integer(it) }
            listOf("glucoseMmol", "positiveIobUnits", "activityUnitsPerMinute", "isfMmolPerUnit", "crGramsPerUnit",
                "cobGrams", "forecast30Mmol", "forecast30CiLow", "forecast30CiHigh").forEach { key ->
                require(o.get(key)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asDouble.isFinite() } == true)
            }
            require(o.get("uamActive")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean } == true)
            require(o.get("basisKey")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true)
        }
        val state = gson.fromJson(root, DeliveryDiagnosticState::class.java)
        require(state.lastSeenAt > 0 && state.lastGlucoseTs in 0..state.lastSeenAt && state.sequence >= 0)
        require(state.pendingSince in 0..state.lastGlucoseTs)
        require(state.activeEpisodeId.isEmpty() || state.activeEpisodeId == "delivery-diagnostic-${state.sequence}")
        require(state.history.all(DeliveryDiagnosticPolicy::validObservation))
        require(state.history.zipWithNext().all { (a, b) -> b.glucoseTs > a.glucoseTs && b.observedAt > a.observedAt &&
            b.forecast30TargetTs > a.forecast30TargetTs &&
            b.glucoseTs - a.glucoseTs <= DeliveryDiagnosticPolicy.MAX_GAP_MS &&
            b.observedAt - a.observedAt <= DeliveryDiagnosticPolicy.MAX_GAP_MS && a.basisKey == b.basisKey })
        require(state.history.lastOrNull()?.let { it.glucoseTs == state.lastGlucoseTs && it.observedAt <= state.lastSeenAt } != false)
        return state
    }

    private fun JsonObject.integer(key: String): Long {
        val value = get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return try { value.asBigDecimal.longValueExact() } catch (_: ArithmeticException) { throw IllegalArgumentException("Invalid diagnostic timestamp") }
    }
}
