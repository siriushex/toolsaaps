package io.aaps.copilot.receiver

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PumpLinkHealthReceiverTest {
    @Test fun mergedManifestRequiresExistingSignaturePermissionOnDedicatedReceiver() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context, PumpLinkHealthReceiver::class.java)
        val info = context.packageManager.getReceiverInfo(component, PackageManager.GET_META_DATA)
        assertThat(info.exported).isTrue()
        assertThat(info.permission).isEqualTo(PumpLinkHealthContract.PERMISSION)
        val queried = context.packageManager.queryBroadcastReceivers(
            Intent(PumpLinkHealthContract.ACTION).setPackage(context.packageName), 0)
        assertThat(queried.map { it.activityInfo.name }).containsExactly(PumpLinkHealthReceiver::class.java.name)
    }

    @Test fun unrelatedOrMalformedPacketsDoNotRequireApplicationContainer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val receiver = PumpLinkHealthReceiver()
        receiver.onReceive(context, Intent("unrelated"))
        receiver.onReceive(context, Intent(PumpLinkHealthContract.ACTION).putExtra("protocolVersion", 99))
        receiver.onReceive(context, null)
    }
}
