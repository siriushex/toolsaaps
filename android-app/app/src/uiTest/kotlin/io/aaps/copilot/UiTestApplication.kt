package io.aaps.copilot

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat

class UiTestApplication : Application() {
    val pdfPickerRegistry = ClinicalPdfPickerActivityResultRegistry()
    val pdfPickerRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = pdfPickerRegistry
    }
}

class ClinicalPdfPickerActivityResultRegistry : ActivityResultRegistry() {
    var launchCount: Int = 0
        private set
    private var requestCode: Int? = null

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?
    ) {
        launchCount += 1
        this.requestCode = requestCode
    }

    fun reset() {
        launchCount = 0
        requestCode = null
    }

    fun deliver(uri: Uri) {
        val launchedRequestCode = checkNotNull(requestCode)
        dispatchResult(
            launchedRequestCode,
            Activity.RESULT_OK,
            Intent().setData(uri)
        )
    }
}
