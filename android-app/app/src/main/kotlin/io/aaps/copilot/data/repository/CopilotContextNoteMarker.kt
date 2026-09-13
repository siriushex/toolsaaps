package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.events.CompensationEventManualPolicy
import io.aaps.copilot.util.ordinaryExceptionOrNull
import java.security.MessageDigest
import java.util.Locale

internal object CopilotContextNoteMarker {
    const val PREFIX = "COPILOT_CONTEXT_V1"
    private const val IDEMPOTENCY_PREFIX = "copilot:"
    private const val MAX_LOCAL_EVENT_ID_CHARS = 248
    private val baseMarker = Regex(
        "COPILOT_CONTEXT_V1\\|([0-9a-fA-F]{16})\\|id=(copilot:[A-Za-z0-9._:-]{1,$MAX_LOCAL_EVENT_ID_CHARS})(?=\\||$)"
    )
    private val commandHeader = Regex(
        "COPILOT_CONTEXT_V1\\|([0-9a-fA-F]{16})\\|id=(copilot:[A-Za-z0-9._:-]{1,$MAX_LOCAL_EVENT_ID_CHARS})" +
            "\\|rev=([1-9][0-9]{0,18})\\|op=(CREATE|UPDATE|CLOSE|DELETE)(?=\\||$)"
    )
    private val commandSuffix = Regex(
        "\\|rev=([1-9][0-9]{0,18})\\|op=(CREATE|UPDATE|CLOSE|DELETE)(?=\\||$)"
    )

    data class Parsed(
        val localEventId: String,
        val revision: Long,
        val operation: AapsContextEventGateway.Operation
    )

    fun baseMarker(localEventId: String): String {
        require(isValidLocalEventId(localEventId))
        return "$PREFIX|${localEventHash(localEventId).take(16)}|id=$IDEMPOTENCY_PREFIX$localEventId"
    }

    fun commandHeader(
        localEventId: String,
        revision: Long,
        operation: AapsContextEventGateway.Operation
    ): String {
        require(revision > 0L)
        return "${baseMarker(localEventId)}|rev=$revision|op=${operation.name}"
    }

    fun parseAtStart(value: String): Parsed? = commandHeader.find(value)
        ?.takeIf { it.range.first == 0 }
        ?.let { parseMatch(it, AapsContextEventGateway.Operation::valueOf) }

    internal fun parseAtStart(
        value: String,
        parseOperation: (String) -> AapsContextEventGateway.Operation
    ): Parsed? = commandHeader.find(value)
        ?.takeIf { it.range.first == 0 }
        ?.let { parseMatch(it, parseOperation) }

    fun containsProtectedMarker(value: String): Boolean = baseMarker.findAll(value)
        .any(::isValidBaseMatch)

    fun stripProtectedMarkers(value: String): String {
        var sanitized = value
        baseMarker.findAll(value)
            .filter(::isValidBaseMatch)
            .map { base ->
                val suffixStart = base.range.last + 1
                commandSuffix.find(value, suffixStart)
                    ?.takeIf { suffix ->
                        suffix.range.first == suffixStart &&
                            suffix.groupValues[1].toLongOrNull()?.let { it > 0L } == true
                    }
                    ?.let { suffix -> base.range.first..suffix.range.last }
                    ?: base.range
            }
            .toList()
            .asReversed()
            .forEach { range -> sanitized = sanitized.removeRange(range) }
        return sanitized
    }

    private fun parseMatch(
        match: MatchResult,
        parseOperation: (String) -> AapsContextEventGateway.Operation
    ): Parsed? {
        val idempotencyKey = match.groupValues[2]
        val localEventId = idempotencyKey.removePrefix(IDEMPOTENCY_PREFIX)
        if (!isValidLocalEventId(localEventId)) return null
        val expectedHash = localEventHash(localEventId).take(16)
        if (!match.groupValues[1].equals(expectedHash, ignoreCase = true)) return null
        val revision = match.groupValues[3].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val operation = ordinaryExceptionOrNull { parseOperation(match.groupValues[4]) }
            ?: return null
        return Parsed(localEventId, revision, operation)
    }

    private fun isValidBaseMatch(match: MatchResult): Boolean {
        val localEventId = match.groupValues[2].removePrefix(IDEMPOTENCY_PREFIX)
        return isValidLocalEventId(localEventId) &&
            match.groupValues[1].equals(localEventHash(localEventId).take(16), ignoreCase = true)
    }

    fun isValidLocalEventId(localEventId: String): Boolean =
        CompensationEventManualPolicy.validLocalEventId(localEventId)

    private fun localEventHash(localEventId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(localEventId.toByteArray())
        .joinToString("") { "%02x".format(Locale.US, it) }
}
