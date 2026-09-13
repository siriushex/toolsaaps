package io.aaps.copilot.data.repository

import java.util.WeakHashMap
import kotlinx.coroutines.sync.Mutex

internal object EpisodeAlertOperationLocks {
    private val locks = WeakHashMap<Any, Mutex>()

    fun forIdentity(identity: Any): Mutex = synchronized(locks) {
        locks.getOrPut(identity) { Mutex() }
    }
}
