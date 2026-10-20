package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.model.ActionCommand

enum class NativeTargetOutcome { APPLIED, NOT_SENT, UNKNOWN }

/** A selected native channel never falls through to a second writer. */
interface NativeTargetTransport {
    suspend fun isConfigured(): Boolean
    suspend fun send(command: ActionCommand, deliveryGuard: suspend () -> String?): NativeTargetOutcome
    suspend fun reconcile(operationId: String): NativeTargetOutcome
}
