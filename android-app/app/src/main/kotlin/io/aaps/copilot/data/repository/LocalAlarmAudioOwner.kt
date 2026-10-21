package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.alerts.LocalAlarmCycle
import java.util.concurrent.atomic.AtomicBoolean

/** Transport ownership only; source admission is rechecked independently. */
class LocalAlarmAudioOwner(
    val cycle: LocalAlarmCycle,
    private val admitted: () -> Boolean,
    private val onRevoke: () -> Unit,
    private val awaitCleanup: suspend () -> Boolean
) {
    private val revoked = AtomicBoolean(false)
    fun current(): Boolean = !revoked.get() && admitted() && !revoked.get()
    internal fun retained(): Boolean = !revoked.get()
    internal fun retire() { revoked.set(true) }
    fun revoke() { if (revoked.compareAndSet(false, true)) onRevoke() }
    suspend fun awaitRelease(): Boolean = awaitCleanup()
}
