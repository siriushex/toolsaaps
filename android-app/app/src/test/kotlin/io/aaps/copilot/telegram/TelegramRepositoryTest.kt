package io.aaps.copilot.telegram

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TelegramRepositoryTest {
    private val token = "123456789:" + "a".repeat(35)
    private var now = 1_000_000L
    private val storage = object : TelegramPersistence {
        var value: String? = null
        var failWrite = false
        override suspend fun read() = value
        override suspend fun write(value: String) {
            if (failWrite) throw IllegalStateException("disk unavailable")
            this.value = value
        }
        override suspend fun clear() { value = null }
    }
    private val api = object : TelegramApi {
        var users = emptyList<TelegramPairCandidate>()
        val sent = mutableListOf<Long>()
        var fail = false
        var cancel = false
        override suspend fun identity(token: String) = TelegramBotIdentity(123456789L, "CopilotTestBot")
        override suspend fun candidates(token: String, code: String, since: Long) = users
        override suspend fun sendText(token: String, chatId: Long, text: String) {
            sent += chatId
            if (cancel) throw CancellationException("cancel")
            if (fail) throw TelegramFailure(TelegramIssue.NETWORK)
        }
    }
    private fun repo() = TelegramRepository(storage, api, clock = { now })

    private suspend fun connect(repository: TelegramRepository): Long {
        repository.initialize()
        repository.replaceToken(token)
        repository.beginPairing("@trusted_user")
        api.users = listOf(TelegramPairCandidate(42L, "trusted_user"))
        repository.checkPairing()
        repository.confirmRecipient(42L)
        repository.setEnabled(true)
        return 42L
    }

    @Test fun tokenIsNotExposedInUiAndPairingNeedsConfirmation() = runTest {
        val repository = repo()
        repository.initialize()
        repository.replaceToken(token)
        repository.beginPairing("trusted_user")
        api.users = listOf(TelegramPairCandidate(42L, "trusted_user"))
        repository.checkPairing()
        assertThat(repository.state.value.recipients).isEmpty()
        assertThat(repository.state.value.toString()).doesNotContain(token)
        repository.confirmRecipient(42L)
        assertThat(repository.state.value.recipients.single().chatId).isEqualTo(42L)
    }

    @Test fun wrongUsernameAndExpiredPairingCannotBecomeTrusted() = runTest {
        val repository = repo()
        repository.initialize()
        repository.replaceToken(token)
        repository.beginPairing("trusted_user")
        api.users = listOf(TelegramPairCandidate(42L, "someone_else"))
        repository.checkPairing()
        assertThat(repository.state.value.candidates).isEmpty()
        now += 16 * 60_000L
        api.users = listOf(TelegramPairCandidate(42L, "trusted_user"))
        repository.checkPairing()
        assertThat(repository.state.value.candidates).isEmpty()
    }

    @Test fun restartCannotRepeatDeliveredEpisode() = runTest {
        val first = repo()
        connect(first)
        now += 1_000L
        val alert = TelegramAlert("episode-1", now, "Possible low", urgent = true)
        first.sendAlert(alert, muted = false)
        val restarted = repo()
        restarted.initialize()
        restarted.sendAlert(alert, muted = false)
        assertThat(api.sent).containsExactly(42L)
    }

    @Test fun muteStaleAndPreEnrollmentEventsNeverSend() = runTest {
        val repository = repo()
        connect(repository)
        repository.sendAlert(TelegramAlert("old", now - 1, "Old", true), false)
        now += 1_000L
        repository.sendAlert(TelegramAlert("muted", now, "Muted", true), true)
        now += 180_000L
        repository.sendAlert(TelegramAlert("stale", now - 121_000L, "Stale", true), false)
        assertThat(api.sent).isEmpty()
    }

    @Test fun timeoutIsNotAutomaticallyRetried() = runTest {
        val repository = repo()
        connect(repository)
        now += 1_000L
        api.fail = true
        val alert = TelegramAlert("episode-1", now, "Possible low", true)
        repository.sendAlert(alert, false)
        api.fail = false
        repository.sendAlert(alert, false)
        assertThat(api.sent).hasSize(1)
        assertThat(repository.state.value.recipients.single().lastResult).isEqualTo(TelegramDeliveryResult.UNKNOWN)
    }

    @Test fun revokedContactAndDisabledRoutingCannotSend() = runTest {
        val repository = repo()
        connect(repository)
        repository.removeRecipient(42L)
        repository.sendAlert(TelegramAlert("episode-1", now, "Possible low", true), false)
        assertThat(api.sent).isEmpty()
        assertThat(repository.state.value.enabled).isFalse()
    }

    @Test fun reportsNeedSeparateRecipientConsent() = runTest {
        val repository = repo()
        connect(repository)
        now += 1_000L
        repository.sendReport("report-1", now, "7/30 day summary", automatic = false)
        assertThat(api.sent).isEmpty()
        repository.updateRecipient(42L, alerts = true, reports = true)
        now += 1_000L
        repository.sendReport("report-2", now, "7/30 day summary", automatic = false)
        assertThat(api.sent).containsExactly(42L)
    }

    @Test fun automaticReportsDefaultOffAndSoftAlertsNeedOptIn() = runTest {
        val repository = repo()
        connect(repository)
        repository.updateRecipient(42L, alerts = true, reports = true)
        now += 1_000L
        repository.sendReport("report-1", now, "summary", automatic = true)
        repository.sendAlert(TelegramAlert("soft", now, "Watch", false), false)
        assertThat(api.sent).isEmpty()
    }

    @Test fun cancellationPropagatesAndDoesNotReissueClaim() = runTest {
        val repository = repo()
        connect(repository)
        now += 1_000L
        api.cancel = true
        val alert = TelegramAlert("cancelled", now, "Possible low", true)
        try {
            repository.sendAlert(alert, false)
            throw AssertionError("Cancellation expected")
        } catch (_: CancellationException) { }
        api.cancel = false
        repository.sendAlert(alert, false)
        assertThat(api.sent).hasSize(1)
    }

    @Test fun claimStorageFailureCannotSendAndDisablesRouting() = runTest {
        val repository = repo()
        connect(repository)
        now += 1_000L
        storage.failWrite = true
        try {
            repository.sendAlert(TelegramAlert("write-failure", now, "Risk", true), false)
            throw AssertionError("Storage failure expected")
        } catch (e: TelegramFailure) {
            assertThat(e.issue).isEqualTo(TelegramIssue.STORAGE)
        }
        assertThat(api.sent).isEmpty()
        assertThat(repository.state.value.enabled).isFalse()
    }

    @Test fun botReplacementClearsTrustAndNeverEnablesRouting() = runTest {
        val repository = repo()
        connect(repository)
        repository.replaceToken(token)
        assertThat(repository.state.value.recipients).isEmpty()
        assertThat(repository.state.value.enabled).isFalse()
    }

    @Test fun ambiguousMatchingUsersCannotBeConfirmed() = runTest {
        val repository = repo()
        repository.initialize()
        repository.replaceToken(token)
        repository.beginPairing("trusted_user")
        api.users = listOf(TelegramPairCandidate(42, "trusted_user"), TelegramPairCandidate(43, "trusted_user"))
        repository.checkPairing()
        assertThat(repository.state.value.candidates).isEmpty()
    }

    @Test fun automaticSummaryNeedsBothSwitchesAndCannotRepeat() = runTest {
        val repository = repo()
        connect(repository)
        repository.setOptions(includeSoftAlerts = false, automaticReports = true)
        repository.updateRecipient(42, alerts = true, reports = true)
        now += 1_000L
        repository.sendReport("fresh", now, "summary", automatic = true)
        repository.sendReport("fresh", now, "summary", automatic = true)
        assertThat(api.sent).containsExactly(42L)
    }
}
