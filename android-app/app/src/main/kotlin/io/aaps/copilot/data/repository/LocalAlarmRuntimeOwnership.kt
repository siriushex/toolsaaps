package io.aaps.copilot.data.repository

import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex

// Lifecycle ownership is separate from the short database/global-OFF lock.
internal object LocalAlarmRuntimeOwnership {
    class Gate {
        val mutex = Mutex()
        val unavailable = AtomicBoolean(false)
    }
    private val owners = WeakHashMap<Any, Gate>()
    fun forIdentity(identity: Any): Gate = synchronized(owners) {
        owners.getOrPut(identity) { Gate() }
    }
}
