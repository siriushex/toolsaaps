package io.aaps.copilot.ui.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.aaps.copilot.CopilotApp
import io.aaps.copilot.config.SetupProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class SetupUiState(
    val progress: SetupProgress? = null,
    val therapyArmed: Boolean = false,
    val saving: Boolean = false,
    val failed: Boolean = false
)

class SetupViewModel(application: Application) : AndroidViewModel(application) {
    private val store = (application as CopilotApp).container.settingsStore
    private val mutableState = MutableStateFlow(SetupUiState())
    val state = mutableState.asStateFlow()
    private var observation: Job? = null

    init { reload() }

    fun reload() {
        observation?.cancel()
        mutableState.value = mutableState.value.copy(failed = false)
        observation = viewModelScope.launch {
            try {
                combine(store.setupProgress, store.settings) { setup, settings ->
                    setup to settings.therapyActionsArmed
                }.collect { (progress, armed) ->
                    mutableState.value = mutableState.value.copy(progress = progress, therapyArmed = armed)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(progress = null, failed = true)
            }
        }
    }

    fun save(next: SetupProgress, onSaved: () -> Unit = {}) {
        val before = mutableState.value
        val expected = before.progress ?: return
        if (before.saving) return
        mutableState.value = before.copy(saving = true, failed = false)
        viewModelScope.launch {
            try {
                if (store.saveSetupProgress(expected, next)) {
                    mutableState.value = mutableState.value.copy(progress = next, saving = false)
                    onSaved()
                } else {
                    mutableState.value = mutableState.value.copy(saving = false, failed = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(saving = false, failed = true)
            }
        }
    }
}
