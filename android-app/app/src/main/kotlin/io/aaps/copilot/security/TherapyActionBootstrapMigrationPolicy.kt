package io.aaps.copilot.security

data class TherapyActionBootstrapMigrationInput(
    val alreadyEvaluatedForInstall: Boolean,
    val firstInstallTimeMs: Long,
    val lastUpdateTimeMs: Long,
    val sentCommandCount: Int,
    val earliestSentCommandMs: Long?,
    val latestSentCommandMs: Long?
)

object TherapyActionBootstrapMigrationPolicy {
    private const val MINIMUM_HISTORY_SPAN_MS = 24L * 60L * 60_000L
    private const val MAXIMUM_LAST_COMMAND_AGE_MS = 7L * 24L * 60L * 60_000L

    fun shouldAutoArm(input: TherapyActionBootstrapMigrationInput): Boolean {
        if (input.alreadyEvaluatedForInstall) return false
        if (input.firstInstallTimeMs <= 0L) return false
        if (input.lastUpdateTimeMs <= input.firstInstallTimeMs) return false
        if (input.sentCommandCount < 2) return false

        val earliest = input.earliestSentCommandMs ?: return false
        val latest = input.latestSentCommandMs ?: return false
        if (earliest < input.firstInstallTimeMs) return false
        if (latest >= input.lastUpdateTimeMs) return false
        if (latest - earliest < MINIMUM_HISTORY_SPAN_MS) return false
        if (input.lastUpdateTimeMs - latest > MAXIMUM_LAST_COMMAND_AGE_MS) return false
        return true
    }
}
