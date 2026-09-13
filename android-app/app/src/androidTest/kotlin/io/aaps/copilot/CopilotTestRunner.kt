package io.aaps.copilot

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

class CopilotTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context
    ): Application {
        require(context.packageName == UI_TEST_TARGET_PACKAGE) {
            "Refusing instrumentation target package: ${context.packageName}"
        }
        return super.newApplication(cl, UiTestApplication::class.java.name, context)
    }

    private companion object {
        const val UI_TEST_TARGET_PACKAGE = "io.aaps.predictivecopilot.uitest"
    }
}
