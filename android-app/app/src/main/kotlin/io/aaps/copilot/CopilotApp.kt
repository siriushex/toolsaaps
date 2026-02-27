package io.aaps.copilot

import android.app.Application
import androidx.work.Configuration
import io.aaps.copilot.scheduler.WorkScheduler
import io.aaps.copilot.service.AppContainer

class CopilotApp : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        WorkScheduler.schedule(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}
