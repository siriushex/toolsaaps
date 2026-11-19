package io.aaps.copilot.service

import android.os.Bundle
import io.aaps.copilot.data.repository.HistoryJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** Volatile handoff for an explicitly local PIN offer. Never imports settings or grants a role. */
internal class LocalEnrollmentOffers(
    private val scope: CoroutineScope,
    private val openTicket: (String) -> LocalSettingsGrants.Ticket?,
    private val now: () -> Long = System::currentTimeMillis
) {
    internal class Offer(val clientId: String, val wrapped: JsonObject, val handle: String,
                         val issued: Long, val expires: Long, val ticket: LocalSettingsGrants.Ticket) {
        override fun toString() = "LocalEnrollmentOffer(redacted)"
    }

    @Volatile private var active: Offer? = null
    private var expiry: Job? = null

    @Synchronized fun publish(bytes: ByteArray): Bundle? {
        val at = now()
        if (active?.let { valid(it, at) } == true || bytes.size !in 1..4096) return null
        clear()
        val body = HistoryJson.parse(bytes, 4096)
        val id = AapsV3PairingProtocol.validateLocalOffer(body, at)
        val deadline = HistoryJson.long(body.getValue("expiresAt"))
        val ticket = openTicket(id) ?: return null
        val item = Offer(id, body, UUID.randomUUID().toString(), at, deadline, ticket)
        active = item
        expiry = scope.launch {
            delay(minOf(deadline - at, LocalSettingsGrants.LIFETIME_MS))
            withdraw(item.clientId, item.handle)
        }
        return Bundle().apply { putBundle("ticket", Bundle(ticket.extras)); putString("handle", item.handle) }
    }

    fun current(): Offer? = active?.takeIf { valid(it, now()) }

    // Called inside a Room transaction. Do not acquire the offer or socket monitor.
    fun ready(item: Offer): Boolean = active === item && valid(item, now()) && item.ticket.subscribed()

    private fun valid(item: Offer, at: Long) = at in item.issued until item.expires && item.ticket.live()

    @Synchronized fun withdraw(id: String, handle: String): Boolean {
        val item = active ?: return false
        if (item.clientId != id || item.handle != handle) return false
        clear()
        return true
    }

    @Synchronized fun clear() {
        val old = active
        active = null
        expiry?.cancel()
        expiry = null
        old?.ticket?.close()
    }
}
