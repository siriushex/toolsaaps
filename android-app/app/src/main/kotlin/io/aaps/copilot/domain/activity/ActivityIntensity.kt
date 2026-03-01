package io.aaps.copilot.domain.activity

object ActivityIntensity {

    fun ratioFromPacePerMinute(pacePerMinute: Double): Double {
        val ratio = when {
            pacePerMinute >= 120.0 -> 1.8
            pacePerMinute >= 80.0 -> 1.5
            pacePerMinute >= 40.0 -> 1.25
            pacePerMinute >= 20.0 -> 1.1
            pacePerMinute >= 5.0 -> 1.0
            else -> 0.95
        }
        return ratio.coerceIn(0.8, 2.0)
    }

    fun labelFromRatio(activityRatio: Double): String = when {
        activityRatio >= 1.5 -> "high"
        activityRatio >= 1.15 -> "moderate"
        activityRatio >= 1.0 -> "light"
        else -> "rest"
    }
}

