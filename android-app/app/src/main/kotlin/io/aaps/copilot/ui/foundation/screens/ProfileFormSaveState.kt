package io.aaps.copilot.ui.foundation.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal class ProfileFormSaveState(
    private val scope: CoroutineScope,
    private val onSaved: () -> Unit
) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<EnergyProfileSaveResult.Failed?>(null)
        private set

    fun submit(save: (EnergyProfileSaveCompletion) -> Unit) {
        if (busy) return
        busy = true
        error = null
        val completed = AtomicBoolean(false)
        val completion: EnergyProfileSaveCompletion = { result ->
            if (completed.compareAndSet(false, true)) {
                // A disposed form cannot close a newly opened editor with an old completion.
                scope.launch {
                    busy = false
                    when (result) {
                        EnergyProfileSaveResult.Saved -> onSaved()
                        is EnergyProfileSaveResult.Failed -> error = result
                    }
                }
            }
        }
        try {
            save(completion)
        } catch (cancellation: CancellationException) {
            completed.set(true)
            busy = false
            throw cancellation
        } catch (_: Exception) {
            completion(EnergyProfileSaveResult.Failed())
        }
    }
}

@Composable
internal fun rememberProfileFormSaveState(onSaved: () -> Unit): ProfileFormSaveState {
    val scope = rememberCoroutineScope()
    val currentOnSaved by rememberUpdatedState(onSaved)
    return remember(scope) { ProfileFormSaveState(scope) { currentOnSaved() } }
}
