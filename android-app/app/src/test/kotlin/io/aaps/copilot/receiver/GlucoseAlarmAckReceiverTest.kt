package io.aaps.copilot.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.aaps.copilot.domain.alerts.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class GlucoseAlarmAckReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val session = "d07a12c2-e9ab-4b6d-b917-1c53bcb48936"
    private val action = LocalAlarmAcknowledgement(LocalAlarmKey(LocalAlarmSourceKind.GLUCOSE, "event-one"), 1, 1, LocalAlarmLevel.LOW_NOW)

    @Test fun exactIdentityRoundTripsAndDifferentOrdinalsUseDifferentPendingOperations() {
        assertEquals(session to action, GlucoseAlarmAckReceiver.decode(GlucoseAlarmAckReceiver.encode(context, session, action)))
        assertNotEquals(GlucoseAlarmAckReceiver.pendingIntent(context, session, action),
            GlucoseAlarmAckReceiver.pendingIntent(context, session, action.copy(ordinal = 2)))
    }
    @Test fun malformedOrUnrelatedActionIsRejected() {
        assertNull(GlucoseAlarmAckReceiver.decode(null))
        assertNull(GlucoseAlarmAckReceiver.decode(Intent("unrelated")))
        assertNull(GlucoseAlarmAckReceiver.decode(GlucoseAlarmAckReceiver.encode(context, "old", action)))
        assertNull(GlucoseAlarmAckReceiver.decode(GlucoseAlarmAckReceiver.encode(context, session, action.copy(ordinal = 0))))
        assertNull(GlucoseAlarmAckReceiver.decode(GlucoseAlarmAckReceiver.encode(context, session,
            action.copy(key = LocalAlarmKey(LocalAlarmSourceKind.PUMP_LINK, "event-one")))))
    }
    @Test fun actionWithoutRunningApplicationOwnerDoesNotStartAnyService() {
        GlucoseAlarmAckReceiver().onReceive(context, GlucoseAlarmAckReceiver.encode(context, session, action))
        assertNull(org.robolectric.Shadows.shadowOf(context as android.app.Application).nextStartedService)
    }
}
