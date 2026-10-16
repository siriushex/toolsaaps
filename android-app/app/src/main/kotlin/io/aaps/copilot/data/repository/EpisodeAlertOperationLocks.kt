package io.aaps.copilot.data.repository

import java.util.WeakHashMap
import kotlinx.coroutines.sync.Mutex

internal object EpisodeAlertOperationLocks {
    private val locks = WeakHashMap<Any, Mutex>()
    private val deliveries = WeakHashMap<Any, Mutex>()

    fun forIdentity(identity: Any): Mutex = synchronized(locks) {
        locks.getOrPut(identity) { Mutex() }
    }

    // Delivery order is independent of the short persistence lock used by local audio.
    fun forDeliveryIdentity(identity: Any): Mutex = synchronized(deliveries) {
        deliveries.getOrPut(identity) { Mutex() }
    }
}
