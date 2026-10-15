package io.aaps.copilot.ui.motion

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
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
    @Test fun exactlyOneColoredBodyImagePreservesActionsAndGrayPublicContent() {
        val app = RuntimeEnvironment.getApplication()
        val intent = PendingIntent.getActivity(app, 7, Intent(app, io.aaps.copilot.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(app, "presentation-check")
            .setSmallIcon(R.drawable.copilot_glyph_low_now)
            .setContentTitle("Low glucose").setContentText("3.8")
            .setContentIntent(intent).addAction(R.drawable.ic_copilot_notification, "Open", intent)
            .setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
        OwlNotificationArtwork.decorate(builder, app, "presentation-check", 3.8, true)
        val alert = builder.build()
        val image = (checkNotNull(alert.getLargeIcon()).loadDrawable(app) as BitmapDrawable).bitmap
        assertArtwork(image, OwlNotificationArtwork.bitmap(app, OwlColorBand.GREEN))
        assertFalse(alert.extras.containsKey(Notification.EXTRA_PICTURE))
        assertEquals("presentation-check", alert.channelId)
        assertEquals(1, alert.actions.size)
        assertEquals(intent, alert.actions[0].actionIntent)
        assertEquals(intent, alert.contentIntent)
        assertTrue(alert.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(R.drawable.copilot_glyph_low_now, alert.smallIcon.resId)
        val public = checkNotNull(alert.publicVersion)
        val publicImage = (checkNotNull(public.getLargeIcon()).loadDrawable(app) as BitmapDrawable).bitmap
        assertArtwork(publicImage, OwlNotificationArtwork.bitmap(app, OwlColorBand.UNKNOWN))
        assertNull(public.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test fun staleAndInvalidValuesNeverUseAFreshColor() {
        val app = RuntimeEnvironment.getApplication()
        listOf(13.5 to false, Double.NaN to true).forEach { (glucose, fresh) ->
            val builder = NotificationCompat.Builder(app, "presentation-check")
                .setSmallIcon(R.drawable.copilot_glyph_low_now)
            OwlNotificationArtwork.decorate(builder, app, "presentation-check", glucose, fresh)
            val image = (checkNotNull(builder.build().getLargeIcon()).loadDrawable(app) as BitmapDrawable).bitmap
            assertArtwork(image, OwlNotificationArtwork.bitmap(app, OwlColorBand.UNKNOWN))
        }
    }

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

    private fun assertArtwork(actual: Bitmap, source: Bitmap) {
        // Notification.Builder scales large icons to the system template's bound.
        assertTrue(actual.width in 1..source.width)
        assertEquals(actual.width, actual.height)
        val expected = Bitmap.createScaledBitmap(source, actual.width, actual.height, true)
        assertTrue("system-sized notification artwork must retain the selected palette", actual.sameAs(expected))
    }
}
