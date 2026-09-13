package io.aaps.copilot.security

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

open class TherapyActionTransportBarrier {
    private val mutex = Mutex()
    private val armed = AtomicBoolean(false)

    suspend fun updateState(
        requestedArmed: Boolean,
        persist: suspend () -> Boolean
    ): Boolean = mutex.withLock {
        val persisted = persist()
        val actual = requestedArmed && persisted
        armed.set(actual)
        actual
    }

    suspend fun publishState(value: Boolean) {
        mutex.withLock {
            armed.set(value)
        }
    }

    suspend fun resolveState(resolve: suspend () -> Boolean): Boolean = mutex.withLock {
        resolve().also(armed::set)
    }

    suspend fun <T> withArmedLease(
        verifyPersistedState: suspend () -> Boolean,
        block: suspend () -> T
    ): T = mutex.withLock {
        if (!armed.get() || !verifyPersistedState()) {
            throw TherapyActionsNotArmedException()
        }
        block()
    }

    fun isArmed(): Boolean = armed.get()
}

class TherapyActionsNotArmedException :
    IllegalStateException("therapy_actions_not_armed")

object TherapyActionTransportGate : TherapyActionTransportBarrier()
