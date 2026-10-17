package io.aaps.copilot.ui

import io.aaps.copilot.data.repository.AiChatTurn
import io.aaps.copilot.data.repository.ServerAiChatError
import io.aaps.copilot.data.repository.ServerAiChatFailure
import io.aaps.copilot.ui.foundation.screens.AiChatFailureUi
import io.aaps.copilot.ui.foundation.screens.AiChatMessageUi
import org.junit.Assert.*
import org.junit.Test

class AiChatMessagePolicyTest {
    @Test fun diagnosticFailureIsNotAnAssistantAnswerInModelHistory() {
        val question = AiChatMessageUi("question", "user", "Review the supplied data", 1)
        val failed = AiChatMessageUi("failed", "assistant", "Private network diagnostics", 2,
            failure = AiChatFailureUi.CONNECTION)
        assertEquals(listOf(AiChatTurn("user", question.text)), aiChatModelHistory(listOf(question, failed)))
    }

    @Test fun diagnosticsDoNotDisplaceTheLastTenActualMessages() {
        val messages = (1..10).map { AiChatMessageUi("message-$it", "user", "Question $it", it.toLong()) }
        val failures = (1..12).map { AiChatMessageUi("failure-$it", "assistant", "Diagnostics", it.toLong(),
            failure = AiChatFailureUi.UNAVAILABLE) }
        assertEquals(messages.map { AiChatTurn(it.role, it.text) }, aiChatModelHistory(messages + failures))
    }

    @Test fun blankAndNonConversationRolesDoNotBecomeUserObservations() {
        val messages = listOf(
            AiChatMessageUi("blank", "user", "  ", 1),
            AiChatMessageUi("status", "status", "Request unavailable", 2),
            AiChatMessageUi("answer", "assistant", "A supplied observation", 3)
        )
        assertEquals(listOf(AiChatTurn("assistant", "A supplied observation")), aiChatModelHistory(messages))
    }

    @Test fun legitimateDiscussionOfAnErrorRemainsAConversationMessage() {
        val messages = listOf(AiChatMessageUi("question", "user", "What does this network error mean?", 1))
        assertEquals(listOf(AiChatTurn("user", messages.single().text)), aiChatModelHistory(messages))
    }

    @Test fun sendGuardsApplyToEveryBusyCombination() {
        for (inProgress in listOf(false, true)) for (voiceBusy in listOf(false, true))
            for (recording in listOf(false, true)) {
                assertEquals(!inProgress && !voiceBusy && !recording,
                    canSubmitAiChat("Question", inProgress, voiceBusy, recording))
                assertFalse(canSubmitAiChat(" \n ", inProgress, voiceBusy, recording))
            }
    }

    @Test fun ambiguousOutcomeTakesPriorityOverEveryUnderlyingError() {
        ServerAiChatError.entries.forEach { reason ->
            assertEquals(AiChatFailureUi.UNKNOWN_OUTCOME,
                aiChatFailureUi(ServerAiChatFailure(reason, outcomeUnknown = true)))
        }
    }

    @Test fun everyServerFailureHasALocalizedNonDiagnosticMessage() {
        ServerAiChatError.entries.forEach { reason ->
            assertTrue(aiChatFailureMessage(aiChatFailureUi(ServerAiChatFailure(reason))) != 0)
        }
        assertEquals(AiChatFailureUi.TEXT_ONLY,
            aiChatFailureUi(ServerAiChatFailure(ServerAiChatError.UNSUPPORTED_MODALITY)))
        assertEquals(AiChatFailureUi.CONTEXT,
            aiChatFailureUi(ServerAiChatFailure(ServerAiChatError.CONTEXT_SYNC)))
        assertEquals(AiChatFailureUi.ACCESS,
            aiChatFailureUi(ServerAiChatFailure(ServerAiChatError.UNAUTHORIZED)))
    }

    @Test fun untypedFailureDoesNotTurnItsTextIntoAnAnswer() {
        assertEquals(AiChatFailureUi.OTHER, aiChatFailureUi(IllegalStateException("Private diagnostics")))
        assertEquals(AiChatFailureUi.TIMEOUT, aiChatFailureUi(java.net.SocketTimeoutException()))
        assertEquals(AiChatFailureUi.CONNECTION, aiChatFailureUi(java.io.IOException()))
    }
}
