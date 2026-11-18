package io.aaps.copilot.service

import android.os.Bundle
import io.aaps.copilot.data.local.entity.LocalNightscoutV3DocumentEntity
import io.aaps.copilot.data.repository.HistoryJson
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/** Separate from all REST credentials. At most one same-phone subscriber can be enrolled at a time. */
internal class LocalSettingsGrants(private val elapsedMs: () -> Long = android.os.SystemClock::elapsedRealtime) {
    internal class Lease(val clientId: String, private val digest: ByteArray, private val elapsedMs: () -> Long) {
        val documentIds = ENROLLMENT_TYPES.map { publicationId(it, clientId) }
        private val issued = elapsedMs()
        private val retired = AtomicBoolean(false)
        private var claimed = false

        fun remainingMs(): Long {
            val age = elapsedMs() - issued
            return if (!retired.get() && age in 0 until LIFETIME_MS) LIFETIME_MS - age else 0
        }
        fun live(): Boolean = remainingMs() > 0

        @Synchronized fun claim(token: String): Boolean {
            if (!live() || claimed || !TOKEN.matches(token)) return false
            val hash = digestOf(token)
            return try {
                MessageDigest.isEqual(hash, digest).also { if (it) claimed = true }
            } finally { hash.fill(0) }
        }

        @Synchronized fun retire() {
            retired.set(true)
            digest.fill(0)
        }
        override fun toString() = "LocalSettingsLease(redacted)"
    }

    internal class Ticket(val extras: Bundle, val live: () -> Boolean, val subscribed: () -> Boolean,
                          private val revoke: () -> Unit) : Closeable {
        override fun close() { revoke(); extras.remove("bearer") }
        override fun toString() = "LocalSettingsTicket(redacted)"
    }

    private val random = SecureRandom()
    private var current: Lease? = null
    private var closed = false

    @Synchronized fun issue(clientId: String, port: Int, root: ByteArray, leaf: ByteArray,
                            subscribed: (Lease) -> Boolean = { false }, wake: (Lease) -> Unit): Ticket? {
        if (closed || !CLIENT.matches(clientId) || current?.live() == true) return null
        current?.retire()
        val bytes = ByteArray(32)
        val token = try {
            random.nextBytes(bytes)
            "ls1." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        } finally { bytes.fill(0) }
        val lease = Lease(clientId, digestOf(token), elapsedMs)
        current = lease
        return Ticket(Bundle().apply {
            putString("bearer", token); putInt("port", port)
            putByteArray("root", root.copyOf()); putByteArray("leaf", leaf.copyOf())
        }, lease::live, { lease.live() && subscribed(lease) }) {
            lease.retire()
            wake(lease)
        }
    }

    @Synchronized fun claim(token: String?): Lease? =
        if (closed || token == null) null else current?.takeIf { it.claim(token) }

    @Synchronized fun close() { closed = true; current?.retire(); current = null }

    companion object {
        const val LIFETIME_MS = 120_000L
        private val CLIENT = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val TOKEN = Regex("ls1\\.[A-Za-z0-9_-]{43}")
        private fun digestOf(token: String): ByteArray {
            val bytes = token.toByteArray(Charsets.US_ASCII)
            return try { MessageDigest.getInstance("SHA-256").digest(bytes) } finally { bytes.fill(0) }
        }
    }
}

/** Routing only. AAPS must still verify the signature, replay counter, role and clinical policy. */
internal fun isLocalClientPublication(row: LocalNightscoutV3DocumentEntity, clientId: String, now: Long): Boolean {
    if (row.collection != "settings" || row.source != "local-v3-target" || row.deleted || row.readOnly ||
        row.eventAtMs != 946_684_800_001L || !row.identifier.endsWith("_$clientId")) return false
    return try {
        val body = HistoryJson.parse(HistoryJson.encodeUtf8(row.payloadJson), 65_536)
        require(body.keys - LOCAL_SUBJECT_FIELD == setOf("date", "utcOffset", "app", "schemaVersion", "envelope"))
        require(HistoryJson.string(body.getValue("app")) == "AAPS" && HistoryJson.long(body.getValue("schemaVersion")) == 1L)
        require(HistoryJson.long(body.getValue("date")) == row.eventAtMs && HistoryJson.long(body.getValue("utcOffset")) == 0L)
        val env = body.getValue("envelope") as kotlinx.serialization.json.JsonObject
        require(env.keys == setOf("clientId", "counter", "timestamp", "validUntil", "wantsAck", "type", "payload", "signature"))
        require(HistoryJson.string(env.getValue("clientId")) == clientId && HistoryJson.long(env.getValue("counter")) > 0)
        require(now in HistoryJson.long(env.getValue("timestamp"))..HistoryJson.long(env.getValue("validUntil")))
        val type = HistoryJson.string(env.getValue("type"))
        type in ENROLLMENT_TYPES && row.identifier == publicationId(type, clientId) &&
            SIGNATURE.matches(HistoryJson.string(env.getValue("signature")))
    } catch (_: RuntimeException) { false }
}

// An invitation proves reachability only. Post-enrollment commands need a separate authenticated session.
private val ENROLLMENT_TYPES = setOf("hello", "ping")
private val SIGNATURE = Regex("[0-9a-f]{64}")
private fun publicationId(type: String, clientId: String) =
    if (type == "hello") "aaps_clientcontrol_hello_$clientId" else "aaps_clientcontrol_cmd_${type}_$clientId"
