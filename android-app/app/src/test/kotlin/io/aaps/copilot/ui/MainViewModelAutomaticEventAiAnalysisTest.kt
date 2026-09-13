package io.aaps.copilot.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.config.AppSettingsStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelAutomaticEventAiAnalysisTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun setterPersistsBothValuesThroughRealDataStoreAndUpdatesFlow() = runTest {
        val dataStore = newDataStore(backgroundScope)
        val store = AppSettingsStore(dataStore, "automatic-alert-ai-test-install")
        val emissions = mutableListOf<Boolean>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.settings
                .map { it.automaticEventAiAnalysisEnabled }
                .take(3)
                .toList(emissions)
        }

        MainViewModel.setAutomaticEventAiAnalysisEnabled(
            enabled = false,
            updateSettings = store::update
        )
        MainViewModel.setAutomaticEventAiAnalysisEnabled(
            enabled = true,
            updateSettings = store::update
        )
        advanceUntilIdle()

        assertThat(emissions).containsExactly(true, false, true).inOrder()
        assertThat(dataStore.data.map { it[AUTOMATIC_ALERT_AI_KEY] }.take(1).toList())
            .containsExactly(true)
        collector.cancel()
    }

    @Test
    fun launchedToggleWriteFailureKeepsPersistedValueAndPublishesBoundedMessage() = runTest {
        val dataStore = newDataStore(backgroundScope)
        val store = AppSettingsStore(dataStore, "automatic-alert-ai-failure-install")
        val messages = mutableListOf<String>()
        val command = AutomaticEventAiAnalysisSettingCommand(
            scope = this,
            updateSettings = { throw IllegalStateException("private datastore detail") },
            onSaveFailed = { messages += "Automatic cause analysis could not be saved" }
        )

        command.setAutomaticEventAiAnalysisEnabled(false)
        advanceUntilIdle()

        assertThat(store.settings.first().automaticEventAiAnalysisEnabled).isTrue()
        assertThat(messages).containsExactly("Automatic cause analysis could not be saved")
        assertThat(messages.single()).doesNotContain("private datastore detail")
    }

    @Test
    fun launchedToggleRethrowsCancellationUnchangedWithoutSaveFailure() = runTest {
        val expected = CancellationException("cancelled by owner")
        val messages = mutableListOf<String>()
        var launched: (suspend () -> Unit)? = null
        val command = AutomaticEventAiAnalysisSettingCommand(
            scope = this,
            updateSettings = { throw expected },
            onSaveFailed = { messages += "save failed" },
            launchCommand = { block -> launched = block }
        )

        command.setAutomaticEventAiAnalysisEnabled(false)
        val actual = assertThrows(CancellationException::class.java) {
            runBlocking { requireNotNull(launched).invoke() }
        }

        assertThat(actual).isSameInstanceAs(expected)
        assertThat(messages).isEmpty()
    }

    @Test
    fun launchedToggleRethrowsFatalErrorUnchangedWithoutSaveFailure() = runTest {
        val expected = AssertionError("fatal datastore invariant")
        val messages = mutableListOf<String>()
        var launched: (suspend () -> Unit)? = null
        val command = AutomaticEventAiAnalysisSettingCommand(
            scope = this,
            updateSettings = { throw expected },
            onSaveFailed = { messages += "save failed" },
            launchCommand = { block -> launched = block }
        )

        command.setAutomaticEventAiAnalysisEnabled(false)
        val actual = assertThrows(AssertionError::class.java) {
            runBlocking { requireNotNull(launched).invoke() }
        }

        assertThat(actual).isSameInstanceAs(expected)
        assertThat(messages).isEmpty()
    }

    private fun newDataStore(scope: kotlinx.coroutines.CoroutineScope): DataStore<Preferences> {
        val directory = temporaryFolder.newFolder("settings")
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(directory, "settings.preferences_pb") }
        )
    }

    private companion object {
        val AUTOMATIC_ALERT_AI_KEY =
            booleanPreferencesKey("automatic_event_ai_analysis_enabled")
    }
}
