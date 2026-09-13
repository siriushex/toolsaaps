package io.aaps.copilot.ui.foundation.screens

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

internal class WindowSecureFlagOwnership(
    private val baselineSecure: Boolean
) {
    private val owners = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private val deferredReleases =
        Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    val ownerCount: Int
        get() = owners.size

    val hasDeferredReleases: Boolean
        get() = deferredReleases.isNotEmpty()

    fun acquire(owner: Any) {
        owners += owner
        deferredReleases -= owner
    }

    fun deferRelease(owner: Any) {
        if (owner in owners) {
            deferredReleases += owner
        }
    }

    fun release(owner: Any): Boolean {
        deferredReleases -= owner
        if (!owners.remove(owner)) return false
        return shouldClearFlag()
    }

    fun releaseDeferred(): Boolean {
        if (deferredReleases.isEmpty()) return false
        owners.removeAll(deferredReleases)
        deferredReleases.clear()
        return shouldClearFlag()
    }

    private fun shouldClearFlag(): Boolean = owners.isEmpty() && !baselineSecure
}

internal class WindowSecureFlagLease internal constructor(
    internal val window: WeakReference<Window>,
    internal val owner: Any
)

internal class WindowSecureFrameSource<T : Any>(
    private val isAttached: (T) -> Boolean
) {
    private var latestOwnerView: WeakReference<T>? = null

    fun update(view: T) {
        latestOwnerView = WeakReference(view)
    }

    fun select(decorView: T): T =
        latestOwnerView?.get()?.takeIf(isAttached) ?: decorView
}

internal object WindowSecureFlagController {
    private data class WindowState(
        val ownership: WindowSecureFlagOwnership,
        val lifecycle: WeakReference<Lifecycle>,
        val frameSource: WindowSecureFrameSource<View>,
        val observer: LifecycleEventObserver,
        var resumeGeneration: Long = 0L
    )

    private val states = WeakHashMap<Window, WindowState>()
    private val lock = Any()

    fun acquire(
        window: Window,
        lifecycleOwner: LifecycleOwner,
        frameView: View
    ): WindowSecureFlagLease {
        val owner = Any()
        synchronized(lock) {
            val state = states[window] ?: createState(
                window = window,
                lifecycle = lifecycleOwner.lifecycle
            ).also { created ->
                states[window] = created
                lifecycleOwner.lifecycle.addObserver(created.observer)
            }
            state.frameSource.update(frameView)
            state.ownership.acquire(owner)
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        return WindowSecureFlagLease(WeakReference(window), owner)
    }

    fun release(lease: WindowSecureFlagLease) {
        val window = lease.window.get() ?: return
        synchronized(lock) {
            val state = states[window] ?: return
            val shouldClear = state.ownership.release(lease.owner)
            removeEmptyState(window, state)
            if (shouldClear) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    fun releaseAfterSanitizedFrame(lease: WindowSecureFlagLease) {
        val window = lease.window.get() ?: return
        var scheduleNow = false
        synchronized(lock) {
            val state = states[window] ?: return
            state.ownership.deferRelease(lease.owner)
            scheduleNow = state.lifecycle.get()?.currentState
                ?.isAtLeast(Lifecycle.State.RESUMED) == true
        }
        if (scheduleNow) {
            scheduleDeferredRelease(lease.window)
        }
    }

    fun onActivityDestroyed(window: Window) {
        synchronized(lock) {
            val state = states.remove(window) ?: return
            state.lifecycle.get()?.removeObserver(state.observer)
        }
    }

    private fun createState(
        window: Window,
        lifecycle: Lifecycle
    ): WindowState {
        val windowReference = WeakReference(window)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> scheduleDeferredRelease(windowReference)
                Lifecycle.Event.ON_DESTROY -> {
                    windowReference.get()?.let(::onActivityDestroyed)
                }
                else -> Unit
            }
        }
        val baselineSecure =
            window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        return WindowState(
            ownership = WindowSecureFlagOwnership(baselineSecure),
            lifecycle = WeakReference(lifecycle),
            frameSource = WindowSecureFrameSource(View::isAttachedToWindow),
            observer = observer
        )
    }

    private fun scheduleDeferredRelease(windowReference: WeakReference<Window>) {
        val frameView: View
        val generation: Long
        synchronized(lock) {
            val window = windowReference.get() ?: return
            val state = states[window] ?: return
            if (!state.ownership.hasDeferredReleases) return
            state.resumeGeneration += 1L
            generation = state.resumeGeneration
            frameView = state.frameSource.select(window.decorView)
        }
        frameView.postOnAnimation {
            frameView.postOnAnimation {
                releaseDeferredAfterSanitizedFrame(windowReference, generation)
            }
        }
    }

    private fun releaseDeferredAfterSanitizedFrame(
        windowReference: WeakReference<Window>,
        generation: Long
    ) {
        synchronized(lock) {
            val window = windowReference.get() ?: return
            val state = states[window] ?: return
            if (state.resumeGeneration != generation) return
            val resumed = state.lifecycle.get()?.currentState
                ?.isAtLeast(Lifecycle.State.RESUMED) == true
            if (!resumed) return
            val shouldClear = state.ownership.releaseDeferred()
            removeEmptyState(window, state)
            if (shouldClear) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    private fun removeEmptyState(window: Window, state: WindowState) {
        if (state.ownership.ownerCount != 0) return
        states.remove(window)
        state.lifecycle.get()?.removeObserver(state.observer)
    }
}
