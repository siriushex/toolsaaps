package io.aaps.copilot.data.repository

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

internal data class AapsBolusLaunchRequest(
    val packageName: String,
    val className: String,
    val action: String,
    val flags: Int,
    val extras: Map<String, String>,
    val data: String?,
    val hasClipData: Boolean,
    val categories: Set<String>
) {
    companion object {
        fun create(): AapsBolusLaunchRequest = AapsBolusLaunchRequest(
            packageName = AAPS_PACKAGE,
            className = AAPS_BOLUS_ACTIVITY,
            action = AAPS_BOLUS_ACTION,
            flags = Intent.FLAG_ACTIVITY_NEW_TASK,
            extras = emptyMap(),
            data = null,
            hasClipData = false,
            categories = emptySet()
        )

        private const val AAPS_PACKAGE = "info.nightscout.androidaps"
        private const val AAPS_BOLUS_ACTIVITY = "app.aaps.activities.CopilotBolusEntryActivity"
        private const val AAPS_BOLUS_ACTION =
            "info.nightscout.androidaps.action.OPEN_BOLUS_DIALOG"
    }
}

enum class AapsBolusLaunchResult {
    OPENED,
    AAPS_NOT_INSTALLED,
    INCOMPATIBLE_BUILD,
    INCOMPATIBLE_SIGNATURE_OR_PERMISSION_DENIED,
    LAUNCH_FAILED
}

internal interface AapsBolusLaunchPlatform {
    @Throws(PackageManager.NameNotFoundException::class)
    fun requirePackage(packageName: String)

    fun startActivity(request: AapsBolusLaunchRequest)
}

internal object AapsBolusIntentFactory {
    fun create(request: AapsBolusLaunchRequest): Intent {
        require(request.extras.isEmpty())
        require(request.data == null)
        require(!request.hasClipData)
        require(request.categories.isEmpty())
        return Intent(request.action)
            .setComponent(ComponentName(request.packageName, request.className))
            .addFlags(request.flags)
    }
}

private class AndroidAapsBolusLaunchPlatform(
    private val applicationContext: Context
) : AapsBolusLaunchPlatform {
    @Suppress("DEPRECATION")
    override fun requirePackage(packageName: String) {
        applicationContext.packageManager.getPackageInfo(packageName, 0)
    }

    override fun startActivity(request: AapsBolusLaunchRequest) {
        applicationContext.startActivity(AapsBolusIntentFactory.create(request))
    }
}

class AapsBolusLauncher internal constructor(
    private val platform: AapsBolusLaunchPlatform,
    private val reportFailureType: (String) -> Unit = {}
) {
    constructor(context: Context) : this(
        platform = AndroidAapsBolusLaunchPlatform(context.applicationContext),
        reportFailureType = { failureType ->
            Log.w(LOG_TAG, "AAPS bolus dialog launch failed: $failureType")
        }
    )

    fun open(): AapsBolusLaunchResult {
        val request = AapsBolusLaunchRequest.create()
        return try {
            platform.requirePackage(request.packageName)
            platform.startActivity(request)
            AapsBolusLaunchResult.OPENED
        } catch (_: PackageManager.NameNotFoundException) {
            AapsBolusLaunchResult.AAPS_NOT_INSTALLED
        } catch (_: ActivityNotFoundException) {
            AapsBolusLaunchResult.INCOMPATIBLE_BUILD
        } catch (_: SecurityException) {
            AapsBolusLaunchResult.INCOMPATIBLE_SIGNATURE_OR_PERMISSION_DENIED
        } catch (error: RuntimeException) {
            runCatching { reportFailureType(error::class.java.simpleName) }
            AapsBolusLaunchResult.LAUNCH_FAILED
        }
    }

    private companion object {
        const val LOG_TAG = "AapsBolusLauncher"
    }
}
