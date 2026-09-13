package io.aaps.copilot.ui.foundation.screens

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class WindowSecureFlagControllerRobolectricTest {

    @Test
    fun detachedFirstOwnerDoesNotBlockDeferredReleaseThroughSecondOwnerFrame() {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        val lifecycleOwner = MutableLifecycleOwner(Lifecycle.State.RESUMED)
        val detachedFirstView = View(activity)
        val secondView = activity.window.decorView

        try {
            val firstOwner = WindowSecureFlagController.acquire(
                window = activity.window,
                lifecycleOwner = lifecycleOwner,
                frameView = detachedFirstView
            )
            val secondOwner = WindowSecureFlagController.acquire(
                window = activity.window,
                lifecycleOwner = lifecycleOwner,
                frameView = secondView
            )
            assertThat(activity.hasSecureFlag()).isTrue()

            lifecycleOwner.moveTo(Lifecycle.State.STARTED)
            WindowSecureFlagController.releaseAfterSanitizedFrame(firstOwner)
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            assertThat(activity.hasSecureFlag()).isTrue()

            lifecycleOwner.moveTo(Lifecycle.State.RESUMED)
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            assertThat(activity.hasSecureFlag()).isTrue()

            WindowSecureFlagController.release(secondOwner)
            assertThat(activity.hasSecureFlag()).isFalse()
        } finally {
            WindowSecureFlagController.onActivityDestroyed(activity.window)
            activityController.destroy()
        }
    }

    private class MutableLifecycleOwner(initialState: Lifecycle.State) : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply {
            currentState = initialState
        }

        override val lifecycle: Lifecycle = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    private fun Activity.hasSecureFlag(): Boolean =
        window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
}
