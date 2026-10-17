package io.aaps.copilot.scheduler

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Call only inside the visible lifecycle. A failed read must not end later cutoffs. */
internal suspend fun observeDailyArchiveCutoffs(clock: () -> Long, earliestExpiry: () -> Long?,
    zone: () -> ZoneId, onDue: () -> Unit) {
    while (currentCoroutineContext().isActive) {
        val now = clock()
        val next = NightlyAdviceSchedule.next(Instant.ofEpochMilli(now).atZone(zone())).toInstant().toEpochMilli()
        val expiry = earliestExpiry()?.takeIf { it > now } ?: next
        delay((minOf(next, expiry) - now).coerceIn(1000, 86_400_000))
        onDue()
    }
}
