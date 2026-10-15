package io.aaps.copilot.domain.meal

data class MealDeliveryClock(val bootId: String, val elapsedMs: Long, val wallMs: Long, val trusted: Boolean)
data class MealNotificationClaim(val episodeId: String, val bootId: String, val elapsedAtMs: Long, val wallAtMs: Long)
enum class MealNotificationBlock { MUTED, EPISODE_ALREADY_CLAIMED, COOLDOWN, CLOCK_UNCERTAIN, INVALID_IDENTITY }

/**
 * Pure quota check, NOT a reservation or permission to notify. The future Room
 * coordinator must recheck this and freshness inside the atomic claim transaction.
 */
object MealNotificationEligibility {
    const val INTERVAL_MS = 120L * 60_000L

    fun evaluate(
        clock: MealDeliveryClock,
        latestClaim: MealNotificationClaim?,
        episodeAlreadyClaimed: Boolean,
        mutedUntilMs: Long
    ): MealNotificationBlock? {
        if (!clock.trusted || clock.bootId.isBlank() || clock.elapsedMs < 0 || clock.wallMs <= 0 || mutedUntilMs < 0) {
            return MealNotificationBlock.CLOCK_UNCERTAIN
        }
        if (episodeAlreadyClaimed) return MealNotificationBlock.EPISODE_ALREADY_CLAIMED
        if (clock.wallMs < mutedUntilMs) return MealNotificationBlock.MUTED
        val claim = latestClaim ?: return null
        if (claim.bootId.isBlank() || claim.episodeId.isBlank() || claim.elapsedAtMs < 0 ||
            claim.wallAtMs <= 0 || clock.wallMs < claim.wallAtMs) return MealNotificationBlock.CLOCK_UNCERTAIN
        if (clock.bootId != claim.bootId) {
            // Wall time alone cannot prove elapsed time across a reboot. Waiting
            // two hours of known uptime conservatively includes any pre-boot claim.
            return if (clock.elapsedMs >= INTERVAL_MS) null else MealNotificationBlock.CLOCK_UNCERTAIN
        }
        if (clock.elapsedMs < claim.elapsedAtMs) return MealNotificationBlock.CLOCK_UNCERTAIN
        return if (clock.elapsedMs - claim.elapsedAtMs < INTERVAL_MS) MealNotificationBlock.COOLDOWN else null
    }
}
