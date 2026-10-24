package io.aaps.copilot.service

import android.os.SystemClock

object ForecastTestRuntime {
    val session = ForecastTestSession(SystemClock::elapsedRealtime)
}
