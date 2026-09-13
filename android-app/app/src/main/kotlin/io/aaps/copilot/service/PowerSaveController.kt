package io.aaps.copilot.service

import io.aaps.copilot.config.AppSettings
import java.util.concurrent.atomic.AtomicLong

object PowerSaveController {
    const val OFF_UNTIL_MS: Long = 0L
    const val INDEFINITE_UNTIL_MS: Long = Long.MAX_VALUE
    const val ONE_HOUR_MS: Long = 60L * 60_000L
    const val TWO_HOURS_MS: Long = 2L * ONE_HOUR_MS
    const val FOUR_HOURS_MS: Long = 4L * ONE_HOUR_MS

    fun untilForDuration(nowMs: Long, durationMs: Long): Long {
        return (nowMs + durationMs).coerceAtLeast(nowMs + 1L)
    }

    fun isActive(settings: AppSettings, nowMs: Long = System.currentTimeMillis()): Boolean {
        return isActiveUntil(settings.powerSaveUntilMs, nowMs)
    }

    fun isActiveUntil(untilMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        return untilMs == INDEFINITE_UNTIL_MS || untilMs > nowMs
    }

    fun isIndefinite(untilMs: Long): Boolean = untilMs == INDEFINITE_UNTIL_MS
}

object PowerSaveRuntimeState {
    private val activeUntilMs = AtomicLong(PowerSaveController.OFF_UNTIL_MS)

    fun update(settings: AppSettings) {
        setUntil(settings.powerSaveUntilMs)
    }

    fun setUntil(untilMs: Long) {
        activeUntilMs.set(untilMs.coerceAtLeast(PowerSaveController.OFF_UNTIL_MS))
    }

    fun clear() {
        activeUntilMs.set(PowerSaveController.OFF_UNTIL_MS)
    }

    fun isActive(nowMs: Long = System.currentTimeMillis()): Boolean {
        return PowerSaveController.isActiveUntil(activeUntilMs.get(), nowMs)
    }

    fun untilMs(): Long = activeUntilMs.get()
}
