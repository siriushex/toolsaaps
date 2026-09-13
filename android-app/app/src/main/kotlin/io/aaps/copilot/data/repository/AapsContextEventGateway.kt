package io.aaps.copilot.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import io.aaps.copilot.domain.events.CompensationEvent
import io.aaps.copilot.domain.events.CompensationEventManualPolicy
import io.aaps.copilot.domain.events.EventSource
import io.aaps.copilot.domain.profile.PhysiologicalSex
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal interface ContextEventGateway {
    suspend fun send(
        event: CompensationEvent,
        operation: AapsContextEventGateway.Operation
    ): ContextEventGatewayResult
}

internal sealed interface ContextEventGatewayResult {
    data class Acknowledged(val responseHash: String) : ContextEventGatewayResult
    data class Failed(val reason: String) : ContextEventGatewayResult
    data class Pending(val reason: String) : ContextEventGatewayResult
}

internal fun interface ContextEventBroadcastSender {
    fun send(intent: Intent, permission: String, receiver: BroadcastReceiver)
}

internal class AapsContextEventGateway(
    private val broadcastSender: ContextEventBroadcastSender,
    private val physiologicalSex: suspend () -> PhysiologicalSex = {
        PhysiologicalSex.UNSPECIFIED
    },
    private val clock: () -> Long = System::currentTimeMillis
) : ContextEventGateway {
    constructor(
        context: Context,
        physiologicalSex: suspend () -> PhysiologicalSex = {
            PhysiologicalSex.UNSPECIFIED
        }
    ) : this(
        broadcastSender = ContextEventBroadcastSender { intent, permission, receiver ->
            context.sendOrderedBroadcast(intent, permission, receiver, null, 0, null, null)
        },
        physiologicalSex = physiologicalSex
    )

    enum class Operation { CREATE, UPDATE, CLOSE, DELETE }

    override suspend fun send(
        event: CompensationEvent,
        operation: Operation
    ): ContextEventGatewayResult = send(event, operation, ACK_TIMEOUT_MS)

    internal suspend fun send(
        event: CompensationEvent,
        operation: Operation = Operation.CREATE,
        timeoutMs: Long
    ): ContextEventGatewayResult {
        val nowTs = clock()
        if (
            event.source != EventSource.USER ||
            !CopilotContextNoteMarker.isValidLocalEventId(event.localId) ||
            event.type.name !in ALLOWED_TYPES ||
            event.title.length > 60 ||
            (event.note?.length ?: 0) > 500 ||
            event.revision <= 0
        ) {
            return ContextEventGatewayResult.Failed("ineligible_event")
        }
        val candidate = when (operation) {
            Operation.CREATE, Operation.UPDATE -> {
                val currentSex = try {
                    physiologicalSex()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return ContextEventGatewayResult.Failed("ineligible_event")
                }
                try {
                    CompensationEventManualPolicy.normalizeContextOrNull(
                        event = event,
                        physiologicalSex = currentSex,
                        nowTs = nowTs
                    )
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            Operation.CLOSE -> CompensationEventManualPolicy.validateCloseOrNull(event, nowTs)
            Operation.DELETE -> CompensationEventManualPolicy.validateDeleteOrNull(event, nowTs)
        } ?: return ContextEventGatewayResult.Failed("ineligible_event")
        val idempotencyKey = idempotencyKey(candidate.localId)
        val hash = localEventHash(candidate.localId)
        val nonce = nonceFor(idempotencyKey)
        val expectedResponseHash = CopilotContextNoteMarker.baseMarker(candidate.localId)
        val intent = Intent(ACTION).setComponent(ComponentName(AAPS_PACKAGE, RECEIVER)).apply {
            putExtra("protocolVersion", 1); putExtra("nonce", nonce); putExtra("expiry", safeExpiry(nowTs))
            putExtra("operation", operation.name); putExtra("localEventHash", hash); putExtra("revision", candidate.revision); putExtra("idempotencyKey", idempotencyKey)
            putExtra("eventType", candidate.type.name); putExtra("startTs", candidate.startTs); putExtra("endTs", candidate.endTs); putExtra("severity", candidate.severity.name); putExtra("title", candidate.title); candidate.note?.let { putExtra("note", it) }
        }
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, resultIntent: Intent) {
                            if (!continuation.isActive) return
                            val extras = getResultExtras(false)
                            val responseHash = extras?.getString(EXTRA_RESPONSE_HASH)
                            val result = if (
                                isAcknowledged(
                                    resultCode = resultCode,
                                    responseHash = responseHash,
                                    expectedHash = expectedResponseHash,
                                    responseOperation = extras?.getString(EXTRA_OPERATION),
                                    expectedOperation = operation,
                                    responseRevision = extras?.getLong(EXTRA_REVISION, 0L) ?: 0L,
                                    expectedRevision = candidate.revision,
                                    responseStatus = extras?.getString(EXTRA_STATUS)
                                )
                            ) {
                                ContextEventGatewayResult.Acknowledged(checkNotNull(responseHash))
                            } else {
                                ContextEventGatewayResult.Failed("ack_rejected")
                            }
                            continuation.resume(result)
                        }
                    }
                    broadcastSender.send(intent, PERMISSION, receiver)
                }
            } ?: ContextEventGatewayResult.Pending("ack_timeout")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ContextEventGatewayResult.Failed("send_failed")
        }
    }

    companion object {
        const val ACTION = "info.nightscout.androidaps.action.WRITE_COPILOT_CONTEXT_NOTE"
        const val PERMISSION = "info.nightscout.androidaps.permission.WRITE_COPILOT_CONTEXT_NOTE"
        const val AAPS_PACKAGE = "info.nightscout.androidaps"
        const val RECEIVER = "app.aaps.receivers.CopilotContextEventReceiver"
        const val ACK_TIMEOUT_MS = 5_000L
        const val EXTRA_OPERATION = "operation"
        const val EXTRA_REVISION = "revision"
        const val EXTRA_STATUS = "status"
        const val EXTRA_RESPONSE_HASH = "responseHash"
        private val ACCEPTED_ACK_STATUSES = setOf("APPLIED", "DUPLICATE")
        val ALLOWED_TYPES = setOf(
            "STRESS",
            "ILLNESS",
            "SLEEP",
            "HORMONAL",
            "MEDICATION_STEROID",
            "ALCOHOL",
            "CUSTOM",
            "MENSTRUAL_CYCLE"
        )
        internal fun idempotencyKey(localEventId: String): String = "copilot:$localEventId"
        internal fun localEventHash(localEventId: String): String = MessageDigest.getInstance("SHA-256")
            .digest(localEventId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        internal fun nonceFor(idempotencyKey: String): String = MessageDigest.getInstance("SHA-256")
            .digest(idempotencyKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
        private fun safeExpiry(nowTs: Long): Long =
            if (nowTs > Long.MAX_VALUE - 300_000L) Long.MAX_VALUE else nowTs + 300_000L
        internal fun isAcknowledged(
            resultCode: Int,
            responseHash: String?,
            expectedHash: String,
            responseOperation: String?,
            expectedOperation: Operation,
            responseRevision: Long,
            expectedRevision: Long,
            responseStatus: String?
        ): Boolean =
            resultCode == 1 &&
                responseHash == expectedHash &&
                responseOperation == expectedOperation.name &&
                responseRevision == expectedRevision &&
                responseStatus in ACCEPTED_ACK_STATUSES
    }
}
