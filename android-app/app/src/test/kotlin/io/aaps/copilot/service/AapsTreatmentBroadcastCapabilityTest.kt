package io.aaps.copilot.service

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [31, 35])
class AapsTreatmentBroadcastCapabilityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val destination = "info.nightscout.androidaps"
    private val glucoseAction = "com.eveningoutpost.dexdrip.NS_EMULATOR"
    private val treatmentAction = "info.nightscout.androidaps.action.LOCAL_TREATMENTS"

    @Test
    fun stockGlucoseReceiverIsNotTreatmentCapability() {
        register(glucoseAction)
        assertThat(canSend(glucoseAction)).isFalse()
    }

    @Test
    fun sameReceiverDeclaringLegacyExtensionRemainsUsable() {
        register(glucoseAction)
        register(treatmentAction)
        assertThat(canSend(glucoseAction)).isTrue()
        assertThat(canSend(treatmentAction)).isTrue()
    }

    @Test
    fun differentTreatmentReceiverDoesNotUpgradeGlucoseReceiver() {
        register(glucoseAction, name = "GlucoseReceiver")
        register(treatmentAction, name = "TreatmentReceiver")
        assertThat(canSend(glucoseAction)).isFalse()
        assertThat(canSend(treatmentAction)).isTrue()
    }

    @Test
    fun unscopedBroadcastCannotLeakTreatmentOrCredentials() {
        register(treatmentAction)
        assertThat(AapsTreatmentBroadcastCapability.canSend(context, Intent(treatmentAction))).isFalse()
        assertThat(AapsTreatmentBroadcastCapability.canSend(context, Intent(treatmentAction).setPackage("")))
            .isFalse()
    }

    @Test
    fun receiverInAnotherPackageCannotSupplyCapability() {
        register(glucoseAction)
        register(treatmentAction, packageName = "example.unrelated")
        assertThat(canSend(glucoseAction)).isFalse()
    }

    @Test
    fun disabledAndPrivateReceiversCannotSupplyCapability() {
        register(glucoseAction)
        register(treatmentAction, enabled = false)
        assertThat(canSend(glucoseAction)).isFalse()
        register(treatmentAction, exported = false)
        assertThat(canSend(glucoseAction)).isFalse()
    }

    @Test
    fun packageManagerFailureCannotEnableDelivery() {
        val unavailable = object : android.content.ContextWrapper(context) {
            override fun getPackageManager(): android.content.pm.PackageManager =
                throw SecurityException("package lookup denied")
        }
        assertThat(AapsTreatmentBroadcastCapability.canSend(
            unavailable, Intent(treatmentAction).setPackage(destination)
        )).isFalse()
    }

    @Test
    fun missingReceiversAndActionAreUnavailable() {
        assertThat(canSend(treatmentAction)).isFalse()
        assertThat(AapsTreatmentBroadcastCapability.canSend(context, Intent().setPackage(destination))).isFalse()
    }

    private fun canSend(action: String) =
        AapsTreatmentBroadcastCapability.canSend(context, Intent(action).setPackage(destination))

    @Suppress("DEPRECATION")
    private fun register(
        action: String,
        name: String = "TestReceiver",
        packageName: String = destination,
        enabled: Boolean = true,
        exported: Boolean = true
    ) {
        val receiver = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                this.name = "$packageName.$name"
                this.enabled = enabled
                this.exported = exported
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(action).setPackage(destination), receiver
        )
    }
}
