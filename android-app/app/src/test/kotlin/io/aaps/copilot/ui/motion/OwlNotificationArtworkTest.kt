package io.aaps.copilot.ui.motion

import android.app.Application
import android.app.Notification
import androidx.core.app.NotificationCompat
import io.aaps.copilot.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OwlNotificationArtworkTest {
    @Test fun decorativeArtworkPreservesRiskAndHidesClinicalContextOnPublicSurface() {
        val app=RuntimeEnvironment.getApplication()
        val builder=NotificationCompat.Builder(app,"test-alert")
            .setSmallIcon(R.drawable.copilot_glyph_low_now)
            .setContentTitle("LOW_NOW").setContentText("2.6")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM)
        OwlNotificationArtwork.decorate(builder,app,"test-alert",2.6,true)
        val alert=builder.build()
        assertEquals(Notification.VISIBILITY_PRIVATE,alert.visibility)
        assertEquals(Notification.PRIORITY_HIGH,alert.priority)
        assertEquals(Notification.CATEGORY_ALARM,alert.category)
        assertEquals("2.6",alert.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(R.drawable.copilot_glyph_low_now,alert.smallIcon.resId)
        val public=checkNotNull(alert.publicVersion)
        assertEquals(app.getString(R.string.app_name),public.extras.getString(Notification.EXTRA_TITLE))
        assertNull(public.extras.getString(Notification.EXTRA_TEXT))
        assertNull(public.actions)
        assertEquals(R.drawable.ic_copilot_notification,public.smallIcon.resId)
    }
    @Test fun losslessPaletteBitmapsAreBoundedAndReused() {
        val app=RuntimeEnvironment.getApplication()
        val ids=OwlColorBand.entries.map { OwlNotificationArtwork.resource(it) }
        assertEquals(ids.size,ids.toSet().size)
        OwlColorBand.entries.forEach { band ->
            val first=OwlNotificationArtwork.bitmap(app,band)
            assertEquals(96,first.width);assertEquals(96,first.height)
            assertSame(first,OwlNotificationArtwork.bitmap(app,band))
            assertEquals(0,first.getPixel(0,0) ushr 24)
        }
    }
}
