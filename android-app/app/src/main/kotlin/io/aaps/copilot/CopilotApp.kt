package io.aaps.copilot

import android.app.Application
import androidx.work.Configuration
import io.aaps.copilot.scheduler.WorkScheduler
import io.aaps.copilot.service.AppVisibilityTracker
import io.aaps.copilot.service.AppContainer
import io.aaps.copilot.service.LocalNightscoutServiceController

class CopilotApp : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppVisibilityTracker.markForeground(false)
        container = AppContainer(this)
        WorkScheduler.schedule(this)
        LocalNightscoutServiceController.start(
            context = this,
            allowBackground = true
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}
