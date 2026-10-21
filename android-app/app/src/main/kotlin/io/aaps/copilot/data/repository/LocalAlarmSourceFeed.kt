package io.aaps.copilot.data.repository

import io.aaps.copilot.domain.alerts.LocalAlarmEvidence
import io.aaps.copilot.domain.alerts.LocalAlarmKey
import io.aaps.copilot.domain.alerts.LocalAlarmLevel
import io.aaps.copilot.domain.alerts.LocalAlarmSourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

data class LocalAlarmSourceClock(val wallMs: Long, val elapsedMs: Long, val bootCount: Int) {
    internal fun valid() = wallMs > 0L && elapsedMs >= 0L && bootCount >= 0
}

internal data class LocalAlarmSourceObservation(
    val level: LocalAlarmLevel,
    val evaluatedAtWallMs: Long,
    val measurementAtWallMs: Long,
    val validUntilWallMs: Long,
    val basisKey: String
)

internal data class LocalAlarmSourceWindow(
    val clock: LocalAlarmSourceClock,
    val untilWallMs: Long,
    val untilElapsedMs: Long
) {
    fun current(now: LocalAlarmSourceClock) = now.valid() && now.bootCount == clock.bootCount &&
        now.wallMs >= clock.wallMs && now.elapsedMs >= clock.elapsedMs &&
        now.wallMs < untilWallMs && now.elapsedMs < untilElapsedMs &&
        abs((now.wallMs - clock.wallMs) - (now.elapsedMs - clock.elapsedMs)) <= 5_000L

    fun tighten(until: Long, now: LocalAlarmSourceClock): LocalAlarmSourceWindow? {
        if (!current(now)) return null
        val deadline = create(now, until) ?: return null
        return copy(untilWallMs = minOf(untilWallMs, deadline.untilWallMs),
            untilElapsedMs = minOf(untilElapsedMs, deadline.untilElapsedMs))
    }

    companion object {
        fun create(clock: LocalAlarmSourceClock, until: Long): LocalAlarmSourceWindow? {
            if (!clock.valid() || until <= clock.wallMs) return null
            val remaining = until - clock.wallMs
            if (clock.elapsedMs > Long.MAX_VALUE - remaining) return null
            return LocalAlarmSourceWindow(clock, until, clock.elapsedMs + remaining)
        }
    }
}

internal class LocalAlarmSourceTicket(val observation: LocalAlarmSourceObservation, val window: LocalAlarmSourceWindow)

/** A checked source is not a hardware capability: settings, Room OFF and ownership still apply. */
class CheckedLocalAlarmSource internal constructor(
    val evidence: LocalAlarmEvidence,
    val pendingSinceElapsedMs: Long,
    internal val ticket: LocalAlarmSourceTicket
)

/** Clock/identity mechanics only. Clinical acceptance and committed episode binding belong to the producer. */
internal class LocalAlarmSourceFeed(
    private val kind: LocalAlarmSourceKind,
    private val readClock: () -> LocalAlarmSourceClock?
) {
    private data class Measurement(val at: Long, val window: LocalAlarmSourceWindow)
    private data class Cohort(val id: String, val boot: Int, val generation: Long,
        val level: LocalAlarmLevel, val pendingSince: Long)
    private val mutableSource = MutableStateFlow<CheckedLocalAlarmSource?>(null)
    val source = mutableSource.asStateFlow()
    private var measurement: Measurement? = null
    private var lastEvaluation = 0L
    private var cohort: Cohort? = null
    private var pending: LocalAlarmSourceTicket? = null

    fun current(): CheckedLocalAlarmSource? {
        val value = mutableSource.value ?: return null
        val now = try { clock() } catch (failure: Throwable) { fail(value.ticket); throw failure }
        if (now == null || !value.ticket.window.current(now)) {
            fail(value.ticket)
            return null
        }
        return value.takeIf { mutableSource.value === it }
    }

    fun begin(observation: LocalAlarmSourceObservation?): LocalAlarmSourceTicket? = try {
        beginAt(observation, if (observation == null) null else clock())
    } catch (failure: Throwable) { invalidate(); throw failure }

    @Synchronized private fun beginAt(observation: LocalAlarmSourceObservation?, now: LocalAlarmSourceClock?): LocalAlarmSourceTicket? {
        fun rejected(): LocalAlarmSourceTicket? { invalidate(); return null }
        if (observation == null || now == null || !now.valid() || !accepts(observation.level) ||
            observation.evaluatedAtWallMs <= 0 || observation.evaluatedAtWallMs > now.wallMs ||
            observation.measurementAtWallMs <= 0 || observation.measurementAtWallMs > observation.evaluatedAtWallMs ||
            observation.validUntilWallMs <= now.wallMs || observation.basisKey.isBlank() || observation.basisKey.length > 256
        ) return rejected()
        val previous = measurement?.takeIf { it.window.clock.bootCount == now.bootCount }
        if (previous != null && (observation.measurementAtWallMs < previous.at || observation.evaluatedAtWallMs < lastEvaluation)) {
            return rejected()
        }
        val window = if (previous?.at == observation.measurementAtWallMs) {
            previous.window.tighten(observation.validUntilWallMs, now)
        } else LocalAlarmSourceWindow.create(now, observation.validUntilWallMs)
        if (window == null) return rejected()
        measurement = Measurement(observation.measurementAtWallMs, window)
        lastEvaluation = observation.evaluatedAtWallMs
        val current = mutableSource.value
        if (current?.ticket?.observation?.let {
            it.level == observation.level && it.measurementAtWallMs == observation.measurementAtWallMs &&
                it.basisKey == observation.basisKey && current.ticket.window == window
        } != true) mutableSource.value = null
        return LocalAlarmSourceTicket(observation, window).also { pending = it }
    }

    fun publish(ticket: LocalAlarmSourceTicket, episodeId: String, episodeRevision: Long,
        mutedUntilWallMs: Long = 0L): Boolean = try {
        publishAt(ticket, episodeId, episodeRevision, mutedUntilWallMs, clock())
    } catch (failure: Throwable) { fail(ticket); throw failure }

    @Synchronized private fun publishAt(ticket: LocalAlarmSourceTicket, id: String, revision: Long,
        mutedUntil: Long, now: LocalAlarmSourceClock?): Boolean {
        if (pending !== ticket) return false
        if (now == null || !ticket.window.current(now) || mutedUntil < 0 || mutedUntil > now.wallMs ||
            revision <= 0 || id.length !in 1..128 || id.isBlank() || id.any { it !in ' '..'~' }
        ) { invalidate(); return false }
        val old = cohort?.takeIf { it.id == id && it.boot == now.bootCount }
        if (old != null && revision < old.generation) { invalidate(); return false }
        val next = Cohort(id, now.bootCount, old?.generation ?: revision, ticket.observation.level,
            if (old?.level == ticket.observation.level) old.pendingSince else now.elapsedMs)
        cohort = next
        mutableSource.value = CheckedLocalAlarmSource(LocalAlarmEvidence(LocalAlarmKey(kind, id), next.generation,
            next.level, next.boot, ticket.window.clock.elapsedMs, ticket.window.untilElapsedMs, authorized = true),
            next.pendingSince, ticket)
        pending = null
        return true
    }

    @Synchronized fun fail(ticket: LocalAlarmSourceTicket?) {
        if (ticket != null && (pending === ticket || mutableSource.value?.ticket === ticket)) invalidate()
    }

    @Synchronized fun invalidate() {
        pending = null
        cohort = null
        mutableSource.value = null
    }

    private fun accepts(level: LocalAlarmLevel): Boolean = level.audible && when (kind) {
        LocalAlarmSourceKind.GLUCOSE -> level != LocalAlarmLevel.PUMP_LINK && level != LocalAlarmLevel.DELIVERY_DIAGNOSTIC
        LocalAlarmSourceKind.DELIVERY_DIAGNOSTIC -> level == LocalAlarmLevel.DELIVERY_DIAGNOSTIC
        LocalAlarmSourceKind.PUMP_LINK -> level == LocalAlarmLevel.PUMP_LINK
    }

    private fun clock(): LocalAlarmSourceClock? = try { readClock() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
}
