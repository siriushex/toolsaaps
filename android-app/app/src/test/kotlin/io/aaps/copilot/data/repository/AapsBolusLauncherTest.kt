package io.aaps.copilot.data.repository

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AapsBolusLauncherTest {

    @Test
    fun requestTargetsOnlyProtectedAapsComponent() {
        val request = AapsBolusLaunchRequest.create()

        assertThat(request.packageName).isEqualTo("info.nightscout.androidaps")
        assertThat(request.className).isEqualTo("app.aaps.activities.CopilotBolusEntryActivity")
        assertThat(request.action).isEqualTo("info.nightscout.androidaps.action.OPEN_BOLUS_DIALOG")
        assertThat(request.flags).isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertThat(request.extras).isEmpty()
        assertThat(request.data).isNull()
        assertThat(request.hasClipData).isFalse()
        assertThat(request.categories).isEmpty()
    }

    @Test
    fun successfulExplicitLaunchReturnsOpened() {
        val platform = RecordingPlatform()
        val launcher = AapsBolusLauncher(platform = platform)

        val result = launcher.open()

        assertThat(result).isEqualTo(AapsBolusLaunchResult.OPENED)
        assertThat(platform.requiredPackages).containsExactly("info.nightscout.androidaps")
        assertThat(platform.requests).containsExactly(AapsBolusLaunchRequest.create())
    }

    @Test
    fun missingPackageReturnsNotInstalledWithoutLaunchFallback() {
        val platform = RecordingPlatform(
            packageFailure = PackageManager.NameNotFoundException("missing")
        )
        val launcher = AapsBolusLauncher(platform = platform)

        val result = launcher.open()

        assertThat(result).isEqualTo(AapsBolusLaunchResult.AAPS_NOT_INSTALLED)
        assertThat(platform.requests).isEmpty()
    }

    @Test
    fun missingProtectedActivityReturnsIncompatibleBuildWithoutImplicitFallback() {
        val platform = RecordingPlatform(
            launchFailure = ActivityNotFoundException("missing component")
        )
        val launcher = AapsBolusLauncher(platform = platform)

        val result = launcher.open()

        assertThat(result).isEqualTo(AapsBolusLaunchResult.INCOMPATIBLE_BUILD)
        assertThat(platform.requests).containsExactly(AapsBolusLaunchRequest.create())
    }

    @Test
    fun securityFailureReturnsIncompatibleSignatureOrPermissionDenied() {
        val platform = RecordingPlatform(
            launchFailure = SecurityException("signature mismatch")
        )
        val launcher = AapsBolusLauncher(platform = platform)

        val result = launcher.open()

        assertThat(result)
            .isEqualTo(AapsBolusLaunchResult.INCOMPATIBLE_SIGNATURE_OR_PERMISSION_DENIED)
        assertThat(platform.requests).hasSize(1)
    }

    @Test
    fun unexpectedRuntimeFailureReturnsLaunchFailedWithSafeDiagnostic() {
        val diagnosticTypes = mutableListOf<String>()
        val platform = RecordingPlatform(
            launchFailure = IllegalStateException("sensitive runtime detail")
        )
        val launcher = AapsBolusLauncher(
            platform = platform,
            reportFailureType = diagnosticTypes::add
        )

        val result = launcher.open()

        assertThat(result).isEqualTo(AapsBolusLaunchResult.LAUNCH_FAILED)
        assertThat(diagnosticTypes).containsExactly("IllegalStateException")
        assertThat(diagnosticTypes.single()).doesNotContain("sensitive runtime detail")
    }

    private class RecordingPlatform(
        private val packageFailure: PackageManager.NameNotFoundException? = null,
        private val launchFailure: RuntimeException? = null
    ) : AapsBolusLaunchPlatform {
        val requiredPackages = mutableListOf<String>()
        val requests = mutableListOf<AapsBolusLaunchRequest>()

        override fun requirePackage(packageName: String) {
            requiredPackages += packageName
            packageFailure?.let { throw it }
        }

        override fun startActivity(request: AapsBolusLaunchRequest) {
            requests += request
            launchFailure?.let { throw it }
        }
    }
}
