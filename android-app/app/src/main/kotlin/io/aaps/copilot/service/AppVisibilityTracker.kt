package io.aaps.copilot.service

import java.util.concurrent.atomic.AtomicBoolean

object AppVisibilityTracker {
    private val foreground = AtomicBoolean(false)

    fun markForeground(value: Boolean) {
        foreground.set(value)
    }

    fun isForeground(): Boolean = foreground.get()
}
