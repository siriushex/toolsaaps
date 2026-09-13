package io.aaps.copilot.ui

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ContextEventLifecycleWiringTest {
    @Test
    fun `MainViewModel manual event commands use only lifecycle coordinator`() {
        val source = source("io/aaps/copilot/ui/MainViewModel.kt")
        val save = declaration(source, "saveManualEvent")
        val close = declaration(source, "closeManualEvent")
        val delete = declaration(source, "deleteManualEvent")

        assertThat(save).contains("contextEventSyncCoordinator.save(event, expectedRevision)")
        assertThat(close).contains("contextEventSyncCoordinator.close(event.localId, event.revision)")
        assertThat(delete).contains("contextEventSyncCoordinator.delete(event.localId, event.revision)")
        assertThat(save).contains("publishContextEventResult")
        assertThat(close).contains("publishContextEventResult")
        assertThat(delete).contains("publishContextEventResult")
        listOf(save, close, delete).forEach { command ->
            assertThat(command).doesNotContain("isfCrRepository")
            assertThat(command).doesNotContain("actionRepository")
            assertThat(command).doesNotContain("TargetManagerRepository")
        }
    }

    @Test
    fun `MainViewModel physiology tag commands use only lifecycle coordinator`() {
        val source = source("io/aaps/copilot/ui/MainViewModel.kt")
        val add = declaration(source, "addPhysioTag")
        val close = declaration(source, "closePhysioTag")
        val clear = declaration(source, "clearActivePhysioTags")

        assertThat(add).contains("contextEventSyncCoordinator.save(")
        assertThat(close).contains("contextEventSyncCoordinator.close(")
        assertThat(clear).contains("contextEventSyncCoordinator.closeAllActive(")
        listOf(add, close, clear).forEach { command ->
            assertThat(command).doesNotContain("isfCrRepository")
            assertThat(command).doesNotContain("physioContextTagDao")
            assertThat(command).doesNotContain("actionRepository")
            assertThat(command).doesNotContain("TargetManagerRepository")
        }
    }

    @Test
    fun `context sync housekeeping retains every unresolved predecessor`() {
        val source = source("io/aaps/copilot/data/local/dao/ContextEventSyncDao.kt")

        assertThat(source).contains("status = 'COMPLETED'")
        assertThat(source).doesNotContain("'FAILED'")
        assertThat(source).doesNotContain("'PENDING'")
    }

    @Test
    fun `runtime context retention excludes rows with pending sync`() {
        val dao = source("io/aaps/copilot/data/local/dao/PhysioContextTagDao.kt")
        val repository = source("io/aaps/copilot/data/repository/IsfCrRepository.kt")

        assertThat(dao).contains("NOT EXISTS")
        assertThat(dao).contains("context_event_sync")
        assertThat(dao).contains("status = 'PENDING'")
        assertThat(repository).contains("deleteOlderThanWithoutPendingSync(evidenceCutoff)")
        assertThat(repository).doesNotContain("physioContextTagDao().deleteOlderThan(evidenceCutoff)")
    }

    @Test
    fun `AppContainer wires coordinator to both Room DAOs and explicit AAPS gateway`() {
        val source = source("io/aaps/copilot/service/AppContainer.kt")
        val wiring = declaration(source, "contextEventSyncCoordinator")

        assertThat(wiring).contains("db.physioContextTagDao()")
        assertThat(wiring).contains("db.contextEventSyncDao()")
        assertThat(wiring).contains("RoomContextEventTransactionRunner(db)")
        assertThat(wiring).contains("AapsContextEventGateway(")
        assertThat(wiring).contains("context = appContext")
        assertThat(wiring).contains("physiologicalSex = physiologicalSexSource")
        assertThat(wiring).contains("gson")
    }

    @Test
    fun `startup drain and successful sync reconciliation add no scheduling`() {
        val container = source("io/aaps/copilot/service/AppContainer.kt")
        val syncRepository = source("io/aaps/copilot/data/repository/SyncRepository.kt")
        val reconciliation = declaration(container, "reconcilePendingContextEvents")

        assertThat(container).contains("settingsStore.settings.first()")
        assertThat(container).contains("reconcilePendingContextEvents(\"startup\")")
        assertThat(reconciliation).contains("reconcileStartupPending")
        assertThat(reconciliation).contains("trigger == \"startup\"")
        assertThat(container).contains("onSuccessfulNightscoutSync = {")
        assertThat(container).contains("reconcilePendingContextEvents(\"nightscout_sync\")")
        assertThat(syncRepository).contains("onSuccessfulNightscoutSync()")
        assertThat(syncRepository).contains("sgvFetchSucceeded")
        assertThat(syncRepository).contains("if (sgvFetchSucceeded)")
        assertThat(reconciliation).doesNotContain("WorkManager")
        assertThat(reconciliation).doesNotContain("enqueue")
        assertThat(reconciliation).doesNotContain("delay(")
        assertThat(reconciliation).doesNotContain("while (")
    }

    private fun source(relativePath: String): String = File(
        checkNotNull(generateSequence(File(System.getProperty("user.dir").orEmpty()).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "src/main/kotlin").isDirectory }) {
            "Unable to locate app module from ${System.getProperty("user.dir")}"
        },
        "src/main/kotlin/$relativePath"
    ).readText()

    private fun declaration(source: String, name: String): String {
        val start = Regex("(?:fun|val)\\s+$name\\b").find(source)?.range?.first
            ?: error("Missing declaration $name")
        val open = if (source.startsWith("fun", start)) {
            source.indexOf('{', start)
        } else {
            source.indexOfAny(charArrayOf('{', '('), start)
        }
        require(open >= 0)
        val opening = source[open]
        val closing = if (opening == '{') '}' else ')'
        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                opening -> depth++
                closing -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
            index++
        }
        error("Unclosed declaration $name")
    }
}
