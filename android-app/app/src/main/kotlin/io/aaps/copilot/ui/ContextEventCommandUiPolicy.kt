package io.aaps.copilot.ui

import androidx.annotation.StringRes
import io.aaps.copilot.R
import io.aaps.copilot.data.repository.ContextEventCommandResult

internal enum class ContextEventCommandAction {
    SAVE,
    CLOSE,
    DELETE
}

internal data class ContextEventCommandUiDisposition(
    @param:StringRes val messageRes: Int,
    val closeEditor: Boolean,
    val triggerInvalidation: Boolean,
    val keepDraftVisible: Boolean
)

internal object ContextEventCommandUiPolicy {
    fun resolve(
        action: ContextEventCommandAction,
        result: ContextEventCommandResult
    ): ContextEventCommandUiDisposition {
        val successful = result == ContextEventCommandResult.ACKNOWLEDGED ||
            result == ContextEventCommandResult.LOCAL_ONLY
        val messageRes = when (result) {
            ContextEventCommandResult.ACKNOWLEDGED,
            ContextEventCommandResult.LOCAL_ONLY -> when (action) {
                ContextEventCommandAction.SAVE -> R.string.events_command_saved
                ContextEventCommandAction.CLOSE -> R.string.events_command_closed
                ContextEventCommandAction.DELETE -> R.string.events_command_deleted
            }
            ContextEventCommandResult.PENDING -> R.string.events_command_pending
            ContextEventCommandResult.FAILED -> R.string.events_command_failed
            ContextEventCommandResult.REJECTED -> R.string.events_command_rejected
            ContextEventCommandResult.CONFLICT -> R.string.events_command_conflict
            ContextEventCommandResult.NOT_FOUND -> R.string.events_command_not_found
        }
        return ContextEventCommandUiDisposition(
            messageRes = messageRes,
            closeEditor = successful,
            triggerInvalidation = successful,
            keepDraftVisible = result == ContextEventCommandResult.PENDING
        )
    }
}
