package io.aaps.copilot.service

import android.os.PowerManager

/** Renewed by the admission watchdog, not by an independent keep-alive loop. */
internal class ForecastTestWakeLock(
    private val lock: PowerManager.WakeLock,
    private val elapsedMs: () -> Long
) {
    private var renewedAt: Long? = null
    private var closed = false

    init { lock.setReferenceCounted(false) }

    @Synchronized
    fun renew() {
        if (closed) return
        val now = elapsedMs()
        val previous = renewedAt
        if (lock.isHeld && previous != null && now - previous in 0 until RENEW_MS) return
        lock.acquire(LEASE_MS)
        renewedAt = now
    }

    @Synchronized
    fun release() {
        closed = true
        if (lock.isHeld) lock.release()
    }

    companion object {
        const val RENEW_MS = 60_000L
        const val LEASE_MS = 120_000L
    }
}
