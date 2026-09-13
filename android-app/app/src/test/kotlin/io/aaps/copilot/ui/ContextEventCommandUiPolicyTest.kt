package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.repository.ContextEventCommandResult
import org.junit.Test

class ContextEventCommandUiPolicyTest {
    @Test
    fun `only acknowledged and local-only close editor and invalidate`() {
        ContextEventCommandResult.entries.forEach { result ->
            val disposition = ContextEventCommandUiPolicy.resolve(
                action = ContextEventCommandAction.SAVE,
                result = result
            )
            val successful = result == ContextEventCommandResult.ACKNOWLEDGED ||
                result == ContextEventCommandResult.LOCAL_ONLY

            assertThat(disposition.closeEditor).isEqualTo(successful)
            assertThat(disposition.triggerInvalidation).isEqualTo(successful)
            assertThat(disposition.keepDraftVisible)
                .isEqualTo(result == ContextEventCommandResult.PENDING)
        }
    }

    @Test
    fun `every result and action has a truthful localized message`() {
        ContextEventCommandAction.entries.forEach { action ->
            ContextEventCommandResult.entries.forEach { result ->
                val disposition = ContextEventCommandUiPolicy.resolve(action, result)

                assertThat(disposition.messageRes).isNotEqualTo(0)
                if (result != ContextEventCommandResult.ACKNOWLEDGED &&
                    result != ContextEventCommandResult.LOCAL_ONLY
                ) {
                    assertThat(disposition.closeEditor).isFalse()
                    assertThat(disposition.triggerInvalidation).isFalse()
                }
            }
        }
    }
}
