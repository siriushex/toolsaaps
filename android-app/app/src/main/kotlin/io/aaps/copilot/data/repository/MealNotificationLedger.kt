package io.aaps.copilot.data.repository

import androidx.room.withTransaction
import io.aaps.copilot.data.local.CopilotDatabase
import io.aaps.copilot.data.local.entity.MealNotificationClaimAliasEntity
import io.aaps.copilot.data.local.entity.MealNotificationClaimEntity
import io.aaps.copilot.domain.meal.MealDeliveryClock
import io.aaps.copilot.domain.meal.MealNotificationBlock
import io.aaps.copilot.domain.meal.MealNotificationClaim
import io.aaps.copilot.domain.meal.MealNotificationEligibility

/**
 * Durable quota reservation only, never clinical authorization or a notification sender.
 * A committed claim is not retried, even if the process dies before delivery.
 * The future coordinator must validate release gates, permission, snapshot and mute
 * freshness before reserving. Aliases must come from explicit reconciliation, not time proximity.
 */
internal class MealNotificationLedger(private val db: CopilotDatabase) {
    suspend fun reserve(
        episodeId: String,
        establishedAliases: Set<String>,
        clock: MealDeliveryClock,
        mutedUntilMs: Long
    ): MealNotificationBlock? {
        val aliases = establishedAliases.toList()
        if (episodeId.isBlank() || episodeId !in aliases || aliases.size !in 1..64 ||
            aliases.any { it.isBlank() || it.length > 256 }) return MealNotificationBlock.INVALID_IDENTITY
        return db.withTransaction {
            val dao = db.mealNotificationClaimDao()
            val prior = dao.latest()?.let {
                MealNotificationClaim(it.episodeId, it.bootId, it.elapsedAtMs, it.wallAtMs)
            }
            val blocked = MealNotificationEligibility.evaluate(clock, prior, dao.hasClaimedAlias(aliases), mutedUntilMs)
            if (blocked != null) return@withTransaction blocked
            val id = dao.insert(MealNotificationClaimEntity(
                episodeId = episodeId, bootId = clock.bootId,
                elapsedAtMs = clock.elapsedMs, wallAtMs = clock.wallMs
            ))
            dao.insertAliases(aliases.map { MealNotificationClaimAliasEntity(it, id) })
            null
        }
    }
}
