package io.aaps.copilot.telegram

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class TelegramDeliveryResult { NONE, CLAIMED, DELIVERED, UNKNOWN }

@Serializable
data class TelegramRecipient(
    val chatId: Long,
    val username: String,
    val alerts: Boolean = true,
    val reports: Boolean = false,
    val since: Long,
    val lastResult: TelegramDeliveryResult = TelegramDeliveryResult.NONE
)

data class TelegramUiState(
    val ready: Boolean = false,
    val configured: Boolean = false,
    val botUsername: String = "",
    val enabled: Boolean = false,
    val includeSoftAlerts: Boolean = false,
    val automaticReports: Boolean = false,
    val recipients: List<TelegramRecipient> = emptyList(),
    val pairingLink: String? = null,
    val candidates: List<TelegramPairCandidate> = emptyList(),
    val issue: TelegramIssue? = null,
    val routingRevision: Long = 0
)

data class TelegramAlert(val key: String, val timestamp: Long, val text: String, val urgent: Boolean)

@Serializable
private data class TelegramRecord(
    val version: Int = 1,
    val token: String = "",
    val botId: Long = 0,
    val botUsername: String = "",
    val enabled: Boolean = false,
    val since: Long = 0,
    val includeSoftAlerts: Boolean = false,
    val automaticReports: Boolean = false,
    val recipients: List<TelegramRecipient> = emptyList(),
    val claims: List<String> = emptyList()
) {
    override fun toString(): String = "TelegramRecord(redacted)"
}

class TelegramRepository(
    private val persistence: TelegramPersistence,
    private val api: TelegramApi,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(TelegramUiState())
    val state = mutableState.asStateFlow()
    private var record = TelegramRecord()
    private var pairing: Pairing? = null
    private data class Pairing(val username: String, val code: String, val started: Long)

    suspend fun initialize() = mutex.withLock {
        if (mutableState.value.ready) return@withLock
        try {
            val raw = persistence.read()
            require(raw == null || raw.length <= 256 * 1_024)
            val loaded = raw?.let { Json.decodeFromString<TelegramRecord>(it) } ?: TelegramRecord()
            require(loaded.version == 1 && loaded.recipients.size <= 10 && loaded.claims.size <= 512)
            require(loaded.token.isEmpty() || TelegramHttpApi.TOKEN.matches(loaded.token))
            require(loaded.token.isEmpty() ||
                (loaded.botId > 0 && TelegramHttpApi.USERNAME.matches(loaded.botUsername)))
            require(!loaded.enabled || (loaded.token.isNotEmpty() && loaded.recipients.isNotEmpty()))
            require(loaded.recipients.all { it.chatId > 0 && TelegramHttpApi.USERNAME.matches(it.username) })
            require(loaded.recipients.map { it.chatId }.distinct().size == loaded.recipients.size)
            record = loaded.copy(recipients = loaded.recipients.map {
                if (it.lastResult == TelegramDeliveryResult.CLAIMED) it.copy(lastResult = TelegramDeliveryResult.UNKNOWN)
                else it
            })
            publish(routingChanged = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.value = TelegramUiState(ready = true, issue = TelegramIssue.STORAGE)
        }
    }

    suspend fun replaceToken(value: String) {
        val token = value.trim()
        if (!TelegramHttpApi.TOKEN.matches(token)) throw TelegramFailure(TelegramIssue.INVALID_TOKEN)
        val identity = api.identity(token)
        mutex.withLock {
            save(TelegramRecord(token = token, botId = identity.id, botUsername = identity.username), true)
            pairing = null
            mutableState.value = mutableState.value.copy(pairingLink = null, candidates = emptyList())
        }
    }

    suspend fun disconnect() = mutex.withLock {
        // Disable queued routing before storage cleanup; a failed cleanup never re-enables it in this process.
        record = TelegramRecord()
        pairing = null
        publish(routingChanged = true)
        persistence.clear()
    }

    suspend fun setEnabled(enabled: Boolean) = mutex.withLock {
        requireConfigured()
        if (enabled && record.recipients.isEmpty()) throw TelegramFailure(TelegramIssue.NOT_READY)
        save(record.copy(enabled = enabled, since = clock()), true)
    }

    suspend fun setOptions(includeSoftAlerts: Boolean, automaticReports: Boolean) = mutex.withLock {
        requireConfigured()
        save(record.copy(includeSoftAlerts = includeSoftAlerts, automaticReports = automaticReports,
            since = clock()), true)
    }

    suspend fun beginPairing(username: String) = mutex.withLock {
        requireConfigured()
        val normalized = username.trim().removePrefix("@")
        if (!TelegramHttpApi.USERNAME.matches(normalized) || record.recipients.size >= 10) {
            throw TelegramFailure(TelegramIssue.NOT_READY)
        }
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        pairing = Pairing(normalized, code, clock())
        mutableState.value = mutableState.value.copy(
            pairingLink = "https://t.me/${record.botUsername}?start=$code",
            candidates = emptyList(), issue = null
        )
    }

    suspend fun checkPairing() {
        val (current, token) = mutex.withLock {
            val pending = pairing ?: throw TelegramFailure(TelegramIssue.NOT_READY)
            if (!pairingFresh(pending)) {
                pairing = null
                mutableState.value = mutableState.value.copy(candidates = emptyList(), pairingLink = null,
                    issue = TelegramIssue.PAIRING_EXPIRED)
                return
            }
            pending to record.token
        }
        val candidates = api.candidates(token, current.code, current.started)
            .filter { it.chatId > 0 && it.username.equals(current.username, ignoreCase = true) }
            .distinctBy { it.chatId }.take(2)
        mutex.withLock {
            if (pairing != current || record.token != token) return@withLock
            val accepted = candidates.takeIf { it.size == 1 && pairingFresh(current) }.orEmpty()
            mutableState.value = mutableState.value.copy(candidates = accepted,
                issue = if (accepted.isEmpty()) TelegramIssue.NO_MATCH else null)
        }
    }

    suspend fun confirmRecipient(chatId: Long) = mutex.withLock {
        val pending = pairing ?: throw TelegramFailure(TelegramIssue.NOT_READY)
        val candidate = mutableState.value.candidates.singleOrNull { it.chatId == chatId }
            ?: throw TelegramFailure(TelegramIssue.NOT_READY)
        if (!pairingFresh(pending)) throw TelegramFailure(TelegramIssue.PAIRING_EXPIRED)
        val next = record.recipients.filterNot { it.chatId == chatId } +
            TelegramRecipient(chatId, candidate.username, since = clock())
        save(record.copy(recipients = next), true)
        pairing = null
        mutableState.value = mutableState.value.copy(pairingLink = null, candidates = emptyList())
    }

    suspend fun updateRecipient(chatId: Long, alerts: Boolean, reports: Boolean) = mutex.withLock {
        save(record.copy(recipients = record.recipients.map {
            if (it.chatId == chatId) it.copy(alerts = alerts, reports = reports, since = clock()) else it
        }), true)
    }

    suspend fun removeRecipient(chatId: Long) = mutex.withLock {
        val recipients = record.recipients.filterNot { it.chatId == chatId }
        save(record.copy(recipients = recipients, enabled = record.enabled && recipients.isNotEmpty()), true)
    }

    suspend fun sendAlert(alert: TelegramAlert, muted: Boolean) {
        if (muted) return
        send("alert:" + alert.key, alert.timestamp, alert.text, isReport = false,
            automatic = false, urgent = alert.urgent)
    }

    suspend fun sendReport(key: String, timestamp: Long, text: String, automatic: Boolean) =
        send("report:$key", timestamp, text, isReport = true, automatic = automatic, urgent = false)

    private suspend fun send(
        key: String, timestamp: Long, text: String, isReport: Boolean, automatic: Boolean, urgent: Boolean
    ) {
        if (key.length > 256 || text.isBlank() || text.length > 3_500) return
        val ids = mutex.withLock { record.recipients.map { it.chatId } }
        for (id in ids) {
            currentCoroutineContext().ensureActive()
            val token = mutex.withLock {
                val now = clock()
                val recipient = record.recipients.singleOrNull { it.chatId == id } ?: return@withLock null
                val maxAge = if (isReport) 60 * 60_000L else 2 * 60_000L
                if (!record.enabled || record.token.isBlank() || timestamp > now || now - timestamp > maxAge ||
                    ((!isReport || automatic) && timestamp < maxOf(record.since, recipient.since)) ||
                    (isReport && (!recipient.reports || (automatic && !record.automaticReports))) ||
                    (!isReport && (!recipient.alerts || (!urgent && !record.includeSoftAlerts)))) return@withLock null
                val claim = digest("${record.botId}|$id|$key")
                if (claim in record.claims) return@withLock null
                // Claim durably before HTTP: uncertain sends are never retried automatically.
                save(record.copy(claims = (record.claims + claim).takeLast(512),
                    recipients = record.recipients.map {
                        if (it.chatId == id) it.copy(lastResult = TelegramDeliveryResult.CLAIMED) else it
                    }), false)
                record.token
            } ?: continue
            try {
                currentCoroutineContext().ensureActive()
                api.sendText(token, id, text)
                finish(id, token, TelegramDeliveryResult.DELIVERED)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { finish(id, token, TelegramDeliveryResult.UNKNOWN) }
                throw e
            } catch (_: Exception) {
                finish(id, token, TelegramDeliveryResult.UNKNOWN)
            }
        }
    }

    private suspend fun finish(id: Long, token: String, result: TelegramDeliveryResult) = mutex.withLock {
        if (record.token != token) return@withLock
        save(record.copy(recipients = record.recipients.map {
            if (it.chatId == id) it.copy(lastResult = result) else it
        }), false)
    }

    private suspend fun save(next: TelegramRecord, routingChanged: Boolean) {
        try {
            persistence.write(Json.encodeToString(next))
            record = next
            publish(routingChanged)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            record = record.copy(enabled = false)
            publish(routingChanged = true)
            mutableState.value = mutableState.value.copy(issue = TelegramIssue.STORAGE)
            throw TelegramFailure(TelegramIssue.STORAGE)
        }
    }

    private fun publish(routingChanged: Boolean) {
        mutableState.value = mutableState.value.copy(
            ready = true, configured = record.token.isNotBlank(), botUsername = record.botUsername,
            enabled = record.enabled, includeSoftAlerts = record.includeSoftAlerts,
            automaticReports = record.automaticReports, recipients = record.recipients.toList(),
            issue = null, routingRevision = mutableState.value.routingRevision + if (routingChanged) 1 else 0
        )
    }
    private fun requireConfigured() {
        if (record.token.isBlank()) throw TelegramFailure(TelegramIssue.NOT_READY)
    }
    private fun pairingFresh(value: Pairing): Boolean = clock() in value.started..(value.started + 15 * 60_000L)
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
