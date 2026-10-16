package io.aaps.copilot.data.repository

import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

internal class AlertControlChanged : CancellationException("alert_control_changed")

/** Immediate intent revocation, separate from the persistent OFF transaction. */
internal class AlertControlIntentGate {
    class Stamp internal constructor()
    class Intent internal constructor(internal val owner: AlertControlIntentGate, val delivery: Job?) {
        internal var pending = true
    }
    private var stamp = Stamp()
    private var pending = 0
    private var delivery: Job? = null

    @Synchronized fun snapshot(): Stamp? = stamp.takeIf { pending == 0 }
    @Synchronized fun current(value: Stamp?): Boolean = value != null && value === stamp && pending == 0

    @Synchronized fun begin(): Intent {
        stamp = Stamp()
        pending++
        return Intent(this, delivery)
    }

    @Synchronized fun finish(intent: Intent) {
        if (intent.owner !== this || !intent.pending) return
        intent.pending = false
        pending--
    }

    @Synchronized fun register(value: Stamp?, job: Job): Boolean {
        if (!current(value) || delivery != null) return false
        delivery = job
        return true
    }

    @Synchronized fun release(job: Job) {
        if (delivery === job) delivery = null
    }

    companion object {
        private val gates = WeakHashMap<Any, AlertControlIntentGate>()
        fun forIdentity(identity: Any): AlertControlIntentGate = synchronized(gates) {
            gates.getOrPut(identity) { AlertControlIntentGate() }
        }
    }
}
