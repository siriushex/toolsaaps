package io.aaps.copilot.ui

import androidx.annotation.StringRes
import io.aaps.copilot.R
import io.aaps.copilot.data.repository.AiChatTurn
import io.aaps.copilot.data.repository.AiChatAttachmentFailure
import io.aaps.copilot.data.repository.ServerAiChatError
import io.aaps.copilot.data.repository.ServerAiChatFailure
import io.aaps.copilot.ui.foundation.screens.AiChatFailureUi
import io.aaps.copilot.ui.foundation.screens.AiChatMessageUi
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale

internal fun canSubmitAiChat(draft: String, inProgress: Boolean, voiceBusy: Boolean, recording: Boolean) =
    draft.isNotBlank() && !inProgress && !voiceBusy && !recording

internal fun aiChatModelHistory(messages: List<AiChatMessageUi>): List<AiChatTurn> =
    messages.asSequence()
        .filter { it.failure == null && it.text.isNotBlank() }
        .map { AiChatTurn(role = it.role.lowercase(Locale.ROOT), text = it.text) }
        .filter { it.role == "user" || it.role == "assistant" }
        .toList().takeLast(10)

internal fun aiChatFailureUi(error: Throwable): AiChatFailureUi = when (error) {
    is AiChatAttachmentFailure -> AiChatFailureUi.INVALID_ATTACHMENT
    is ServerAiChatFailure -> if (error.outcomeUnknown) AiChatFailureUi.UNKNOWN_OUTCOME else when (error.reason) {
        ServerAiChatError.NETWORK -> AiChatFailureUi.CONNECTION
        ServerAiChatError.ROUTING_UNAVAILABLE, ServerAiChatError.DISABLED,
        ServerAiChatError.REJECTED -> AiChatFailureUi.UNAVAILABLE
        ServerAiChatError.UNAUTHORIZED -> AiChatFailureUi.ACCESS
        ServerAiChatError.UNSUPPORTED_MODALITY -> AiChatFailureUi.TEXT_ONLY
        ServerAiChatError.INVALID_REQUEST -> AiChatFailureUi.INVALID_REQUEST
        ServerAiChatError.CONTEXT_SYNC -> AiChatFailureUi.CONTEXT
        ServerAiChatError.UNKNOWN -> AiChatFailureUi.UNKNOWN_OUTCOME
        ServerAiChatError.TIMED_OUT -> AiChatFailureUi.TIMEOUT
        ServerAiChatError.CANCELLED -> AiChatFailureUi.CANCELLED
        ServerAiChatError.INVALID_RESPONSE, ServerAiChatError.RESULT_UNAVAILABLE -> AiChatFailureUi.INVALID_RESPONSE
        ServerAiChatError.FAILED -> AiChatFailureUi.OTHER
    }
    is SocketTimeoutException -> AiChatFailureUi.TIMEOUT
    is IOException -> AiChatFailureUi.CONNECTION
    else -> AiChatFailureUi.OTHER
}

@StringRes
internal fun aiChatFailureMessage(failure: AiChatFailureUi): Int = when (failure) {
    AiChatFailureUi.INVALID_ATTACHMENT -> R.string.ai_chat_attachment_failed
    AiChatFailureUi.CONNECTION -> R.string.ai_chat_failure_connection
    AiChatFailureUi.UNAVAILABLE -> R.string.ai_chat_failure_unavailable
    AiChatFailureUi.ACCESS -> R.string.ai_chat_failure_access
    AiChatFailureUi.TEXT_ONLY -> R.string.ai_chat_text_only
    AiChatFailureUi.INVALID_REQUEST -> R.string.ai_chat_failure_request
    AiChatFailureUi.CONTEXT -> R.string.ai_chat_failure_context
    AiChatFailureUi.UNKNOWN_OUTCOME -> R.string.ai_chat_failure_unknown
    AiChatFailureUi.TIMEOUT -> R.string.ai_chat_failure_timeout
    AiChatFailureUi.CANCELLED -> R.string.ai_chat_failure_cancelled
    AiChatFailureUi.INVALID_RESPONSE -> R.string.ai_chat_failure_response
    AiChatFailureUi.OTHER -> R.string.ai_chat_failure_other
}
