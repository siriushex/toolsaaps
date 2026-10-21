package io.aaps.copilot.data.repository

import io.aaps.copilot.config.AppSettings
import java.util.concurrent.atomic.AtomicReference

/** Routing is not opt-in: only an already running foreground owner can accept. */
class GlucoseAlarmDeliveryRouter(private val clock: () -> LocalAlarmSourceClock?) {
    private data class Native(val source: CheckedLocalAlarmSource, val at: LocalAlarmSourceClock)
    private val owner = AtomicReference<GlucoseAlarmOwner?>(null)
    @Volatile private var native: Native? = null

    suspend fun attach(candidate: GlucoseAlarmOwner): Boolean {
        if (!owner.compareAndSet(null, candidate)) return false
        try { native?.let { candidate.nativeInitial(it.source, true, it.at) } }
        catch (error: Throwable) { owner.compareAndSet(candidate, null); throw error }
        return true
    }

    fun detach(candidate: GlucoseAlarmOwner) { owner.compareAndSet(candidate, null) }
    fun currentOwner(): GlucoseAlarmOwner? = owner.get()
    fun stop() { owner.get()?.stop() }

    suspend fun initial(source: CheckedLocalAlarmSource?, settings: AppSettings): LocalGlucoseInitialResult? =
        if (source == null) null else owner.get()?.startInitial(source, settings)

    suspend fun accepted(source: CheckedLocalAlarmSource?) { owner.get()?.acceptedWithoutInitial(source) }

    suspend fun nativeInitial(source: CheckedLocalAlarmSource?, played: Boolean) {
        if (source == null || !played) return
        val at = clock() ?: return
        native = Native(source, at)
        owner.get()?.let { it.nativeInitial(source, true, at); it.acceptedWithoutInitial(source) }
    }
}
